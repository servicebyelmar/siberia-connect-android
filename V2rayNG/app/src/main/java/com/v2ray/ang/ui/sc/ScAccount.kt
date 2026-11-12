package com.v2ray.ang.ui.sc

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Siberia Connect 1.1.0: центр авторизации (/app/api/v1/…, модуль id_v1 на сервере).
 *
 * - Первый запуск без ключа: аккаунт создаётся сам по секрету установки (хранится только в приложении),
 *   сервер сразу выдаёт пробный день.
 * - Перед подключением — единый статус: active | expired | blocked | device_limit | no_account | relogin.
 *   Подписка кончилась — понятное сообщение вместо «вечного подключения»; ключ сбросили — новый подтягивается сам.
 * - Код для связки с Telegram.
 *
 * Вход общий с кабинетом: тот же токен лежит в localStorage страницы под ключом sc_tok (см. ScActivity.syncToken).
 * Секрет установки, токен и код в журнал не пишутся.
 */
class ScAccount(private val prefs: SharedPreferences, private val did: () -> String) {

    companion object {
        private const val BASE = "https://${ScActivity.PRIMARY_HOST}/app/api/v1/"
        private const val K_SECRET = "inst_secret"
        private const val K_TOKEN = "auth_token"
        private const val K_FROM_INSTALL = "token_from_install"
        private const val K_REG_DONE = "auto_reg_done"
        val BAD_STATES = setOf("expired", "blocked", "device_limit", "no_account")
    }

    /** Последний статус (state, message, link, app_only, …). */
    @Volatile
    var last: JSONObject? = null
        private set

    val token: String get() = prefs.getString(K_TOKEN, "").orEmpty()

    fun needsAutoRegister(hasSub: Boolean): Boolean =
        !hasSub && token.isEmpty() && !prefs.getBoolean(K_REG_DONE, false)

    private fun secret(): String {
        prefs.getString(K_SECRET, null)?.takeIf { it.length >= 32 }?.let { return it }
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val s = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(K_SECRET, s).apply()
        return s
    }

    private fun post(path: String, body: JSONObject, auth: String? = null): Pair<Int, JSONObject?> {
        return try {
            val c = URL(BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            if (!auth.isNullOrEmpty()) c.setRequestProperty("X-App-Token", auth)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            code to runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            0 to null
        }
    }

    /** Вход по секрету установки: новый аккаунт (первый запуск) или тот же/связанный. true — токен получен. */
    fun autoRegister(): Boolean {
        val (code, r) = post("device", JSONObject().put("secret", secret()).put("did", did()).put("platform", "android"))
        if (code != 200 || r == null || !r.optBoolean("ok")) {
            if (code == 429 || code == 400) prefs.edit().putBoolean(K_REG_DONE, true).apply()   // не долбим сервер
            return false
        }
        val t = r.optString("token")
        if (t.isEmpty()) return false
        prefs.edit().putString(K_TOKEN, t).putBoolean(K_FROM_INSTALL, true).putBoolean(K_REG_DONE, true).apply()
        r.optJSONObject("status")?.let { last = it }
        return true
    }

    /** Кабинет вошёл/вышел — запоминаем его токен (один аккаунт на всё приложение). */
    fun adopt(t: String) {
        val v = t.trim()
        if (v.length > 100 || v == token) return
        prefs.edit().putString(K_TOKEN, v).putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true).apply()
        if (v.isEmpty()) last = null
    }

    /** Единый статус. null — проверить не удалось (нет входа или связи): подключаемся как раньше. */
    fun status(): JSONObject? {
        if (token.isEmpty()) return null
        val body = JSONObject().put("did", did()).put("platform", "android")
        val (code, r) = post("status", body, token)
        val fromInstall = prefs.getBoolean(K_FROM_INSTALL, false)
        if (code == 401) {
            // сессия истекла или аккаунт связали с Telegram — заходим заново по секрету установки (только «своим» входом)
            if (fromInstall && autoRegister()) return last
            prefs.edit().putString(K_TOKEN, "").apply()
            return null
        }
        val st = if (code == 200) r?.optJSONObject("status") else null
        if (st == null) return null
        if (st.optString("state") == "relogin" && fromInstall) return if (autoRegister()) last else null
        last = st
        return st
    }

    /** Код связки с Telegram: (код, ссылка) или (null, текст ошибки). */
    fun linkCode(): Pair<String?, String> {
        if (token.isEmpty()) return null to "Сначала войди в приложение"
        val (c, r) = post("link", JSONObject(), token)
        if (c == 200 && r != null && r.optBoolean("ok")) return r.optString("code") to r.optString("url")
        return null to (r?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Нет связи с сервером")
    }
}
