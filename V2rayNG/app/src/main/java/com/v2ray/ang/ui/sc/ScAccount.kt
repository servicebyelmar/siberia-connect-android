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
        private const val K_GUEST = "acc_guest"
        private const val K_DEAD = "dead_token"
        val BAD_STATES = setOf("expired", "blocked", "device_limit", "no_account")
    }

    /** Последний статус (state, message, link, app_only, …). */
    @Volatile
    var last: JSONObject? = null
        private set

    val token: String get() = prefs.getString(K_TOKEN, "").orEmpty()

    /**
     * unified_v1: входа ещё нет — ссылку «Подключить в 1 клик» из бота принимаем без лишнего вопроса.
     * acc_switch_v1 (1.2.2): открыт гостевой пробный аккаунт (без Telegram и оплат) — тоже без вопроса:
     * раньше гость получал «Войти как …?», который затирался другими окнами, и войти в свой аккаунт было нельзя.
     */
    val replaceable: Boolean get() = token.isEmpty() || prefs.getBoolean(K_GUEST, false)

    private fun rememberKind(st: JSONObject?) {
        if (st == null) return
        prefs.edit().putBoolean(K_GUEST, st.optBoolean("app_only") && !st.optBoolean("paid")).apply()
    }

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

    /** Итог последнего запроса: сеть/5xx/таймаут (NETWORK) никогда не ведут к удалению ключа. */
    enum class Outcome { OK, NETWORK, REVOKED, LOCKED, REBIND }

    @Volatile
    var outcome: Outcome = Outcome.OK
        private set

    /** Явный отказ сервера: 401/403, DEVICE_LOCKED, Key Expired. Всё остальное — временная ошибка. */
    private fun classify(code: Int, r: JSONObject?): Outcome {
        val c = r?.optString("code").orEmpty()
        val e = r?.optString("error").orEmpty()
        return when {
            c == "DEVICE_LOCKED" -> Outcome.LOCKED
            c == "REBIND_AVAILABLE" -> Outcome.REBIND
            code == 401 || code == 403 -> Outcome.REVOKED
            c.equals("KEY_EXPIRED", true) || e.contains("Key Expired", true) -> Outcome.REVOKED
            code in 200..299 -> Outcome.OK
            else -> Outcome.NETWORK     // 0 (таймаут/нет сети/смена Wi-Fi↔4G), 5xx, 429, прочие 4xx
        }
    }

    /** Блокировка по привязке устройства: запоминаем до какого момента (retry_after — секунды). */
    private fun rememberLock(r: JSONObject?) {
        val sec = r?.optLong("retry_after", 0L) ?: 0L
        val at = r?.optLong("rebind_available_at", 0L) ?: 0L
        val until = when {
            sec > 0 -> System.currentTimeMillis() + sec * 1000
            at > 0 -> at * 1000
            else -> System.currentTimeMillis() + 72L * 3600_000
        }
        prefs.edit().putLong(ScSecureStore.K_LOCK_UNTIL, until)
            .putString(ScSecureStore.K_LOCK_NAME, r?.optString("bound_device_name").orEmpty()).apply()
    }

    private fun rememberRebind(r: JSONObject?) {
        prefs.edit().putString(ScSecureStore.K_REBIND_NAME, r?.optString("bound_device_name").orEmpty().ifEmpty { "-" }).apply()
    }

    /** Токен и ключ стираем только при 401/403/Key Expired (не при DEVICE_LOCKED); сам список серверов убирает вызывающий. */
    private fun wipeCredentials() {
        val old = token
        val e = prefs.edit().putString(K_TOKEN, "").remove(ScSecureStore.K_KEY)
        if (old.isNotEmpty()) e.putString(K_DEAD, old)     // 1.2.6: страница не сможет вернуть этот токен обратно
        e.apply()
        last = null
    }

    /** 1.2.6: последний отозванный токен. Кабинет (WebView) может ещё хранить его — принимать его обратно нельзя. */
    fun deadToken(): String = prefs.getString(K_DEAD, "").orEmpty()

    /**
     * 1.2.6: полный выход (кнопка, 401, кабинет вышел). Токен, ключ, метки блокировки стираются одним сохранением;
     * автосоздание гостя не повторяется (K_REG_DONE). Список серверов и VPN убирает вызывающий.
     */
    fun signOut() {
        val old = token
        val e = prefs.edit().putString(K_TOKEN, "").remove(ScSecureStore.K_KEY).remove("tok_seen")
            .remove(ScSecureStore.K_LOCK_UNTIL).remove(ScSecureStore.K_LOCK_NAME).remove(ScSecureStore.K_REBIND_NAME)
            .putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true).putBoolean(K_GUEST, false)
        if (old.isNotEmpty()) e.putString(K_DEAD, old)
        e.apply()
        last = null
    }

    /** Вход по секрету установки: новый аккаунт (первый запуск) или тот же/связанный. true — токен получен. */
    fun autoRegister(): Boolean {
        val (code, r) = post("device", JSONObject().put("secret", secret()).put("did", did()).put("platform", "android"))
        outcome = classify(code, r)
        if (outcome == Outcome.LOCKED) rememberLock(r)
        if (code != 200 || r == null || !r.optBoolean("ok")) {
            // known_device: телефон уже был в Telegram-аккаунте — гостя не создаём, кабинет покажет вход
            if (code == 429 || code == 400 || r?.optBoolean("known_device") == true) prefs.edit().putBoolean(K_REG_DONE, true).apply()
            return false
        }
        val t = r.optString("token")
        if (t.isEmpty()) return false
        prefs.edit().putString(K_TOKEN, t).putBoolean(K_FROM_INSTALL, true).putBoolean(K_REG_DONE, true).apply()
        r.optJSONObject("status")?.let { last = it; rememberKind(it) }
        return true
    }

    /** Кабинет вошёл/вышел — запоминаем его токен (один аккаунт на всё приложение). */
    fun adopt(t: String) {
        val v = t.trim()
        if (v.length > 100 || v == token) return
        if (v.isEmpty()) { signOut(); return }                    // кабинет вышел — выходим целиком
        if (v == deadToken()) return                              // 1.2.6: отозванный токен обратно не принимаем
        prefs.edit().putString(K_TOKEN, v).putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true)
            .putBoolean(K_GUEST, false).remove(K_DEAD).apply()
    }

    /** Единый статус. null — проверить не удалось (нет входа или связи): подключаемся как раньше. */
    fun status(): JSONObject? {
        outcome = Outcome.OK
        if (token.isEmpty()) return null
        val body = JSONObject().put("did", did()).put("platform", "android").put("device_name", ScSecureStore.deviceName())
        val (code, r) = post("status", body, token)
        val fromInstall = prefs.getBoolean(K_FROM_INSTALL, false)
        val res = classify(code, r)
        outcome = res
        when (res) {
            Outcome.NETWORK -> return null          // таймаут / 5xx / смена сети: ключ и токен остаются
            Outcome.LOCKED -> {      // DEVICE_LOCKED: ключ не отозван, ничего не стираем
                rememberLock(r)
                return null
            }
            Outcome.REBIND -> {
                rememberRebind(r)
                return null
            }
            Outcome.REVOKED -> {
                // сессия истекла или аккаунт связали с Telegram — заходим заново по секрету установки (только «своим» входом)
                if (fromInstall && autoRegister()) { outcome = Outcome.OK; return last }
                if (outcome == Outcome.LOCKED || outcome == Outcome.REBIND) return null
                outcome = Outcome.REVOKED           // автовход не вышел, но исходный отказ (401/403) был явным
                wipeCredentials()
                return null
            }
            Outcome.OK -> Unit
        }
        val st = if (code == 200) r?.optJSONObject("status") else null
        if (st == null) return null
        if (st.optString("state") == "relogin" && fromInstall) return if (autoRegister()) last else null
        last = st
        rememberKind(st)
        return st
    }

    sealed class Activation {
        data class Ok(val link: String, val days: Int) : Activation()
        /** Ключ привязан к другому устройству; [untilMs] — когда можно перепривязать. */
        data class Locked(val deviceName: String, val untilMs: Long) : Activation()
        /** Прошло 72 ч: можно перепривязать ключ на это устройство. */
        data class Rebind(val deviceName: String) : Activation()
        data class Failed(val message: String, val retryable: Boolean) : Activation()
    }

    /** Код активации (30 минут): POST /activate. Токен и ключ сохраняются только при успехе. */
    fun activate(rawCode: String): Activation {
        val code = rawCode.trim().uppercase()
        val body = JSONObject().put("code", code).put("device_id", did())
            .put("device_name", ScSecureStore.deviceName()).put("platform", "android")
        val (http, r) = post("activate", body)
        if (http == 0 || http >= 500) return Activation.Failed("Нет связи с сервером. Проверь интернет и повтори", true)
        if (r == null) return Activation.Failed("Неожиданный ответ сервера", true)
        if (r.optString("code") == "DEVICE_LOCKED") {
            rememberLock(r)
            return Activation.Locked(prefs.getString(ScSecureStore.K_LOCK_NAME, "").orEmpty(), prefs.getLong(ScSecureStore.K_LOCK_UNTIL, 0L))
        }
        if (r.optString("code") == "REBIND_AVAILABLE") {
            rememberRebind(r)
            return Activation.Rebind(r.optString("bound_device_name"))
        }
        val link = r.optString("link")
        val tok = r.optString("token")
        if (http == 200 && r.optBoolean("ok") && link.isNotEmpty() && tok.isNotEmpty()) {
            prefs.edit().putString(K_TOKEN, tok).putString(ScSecureStore.K_KEY, link)
                .putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true).putBoolean(K_GUEST, false)
                .remove(ScSecureStore.K_LOCK_UNTIL).remove(ScSecureStore.K_LOCK_NAME).apply()
            return Activation.Ok(link, r.optInt("days"))
        }
        return Activation.Failed(r.optString("error").ifEmpty { "Не удалось активировать код" }, http == 429)
    }

    /** Перепривязка ключа на это устройство после 72 ч (нужен вход: токен). */
    fun rebind(): Activation {
        if (token.isEmpty()) return Activation.Failed("Сначала введи код активации", false)
        val body = JSONObject().put("device_id", did()).put("device_name", ScSecureStore.deviceName())
            .put("platform", "android").put("confirm", true)
        val (http, r) = post("rebind", body, token)
        if (http == 0 || http >= 500) return Activation.Failed("Нет связи с сервером. Проверь интернет и повтори", true)
        if (r?.optString("code") == "DEVICE_LOCKED") {
            rememberLock(r)
            return Activation.Locked(prefs.getString(ScSecureStore.K_LOCK_NAME, "").orEmpty(), prefs.getLong(ScSecureStore.K_LOCK_UNTIL, 0L))
        }
        val link = r?.optString("link").orEmpty()
        if (http == 200 && r?.optBoolean("ok") == true && link.isNotEmpty()) {
            prefs.edit().putString(ScSecureStore.K_KEY, link).remove(ScSecureStore.K_REBIND_NAME)
                .remove(ScSecureStore.K_LOCK_UNTIL).remove(ScSecureStore.K_LOCK_NAME).apply()
            return Activation.Ok(link, 0)
        }
        return Activation.Failed(r?.optString("error").orEmpty().ifEmpty { "Не удалось перепривязать" }, false)
    }

    data class Node(val key: String, val name: String, val available: Boolean, val load: Double, val rank: Int)
    data class Rank(val nodes: List<Node>, val best: String, val connectTimeoutMs: Long)

    /** Рейтинг нод с сервера. null — недоступно (сеть/5xx/нет входа): вызывающий проверит серверы пингом. */
    fun nodesRank(): Rank? {
        if (token.isEmpty()) return null
        val (code, r) = post("nodes/rank", JSONObject(), token)
        if (code != 200 || r == null || !r.optBoolean("ok")) return null
        val arr = r.optJSONArray("nodes") ?: return null
        val nodes = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            Node(it.optString("key"), it.optString("name"), it.optBoolean("available", true), it.optDouble("load", 0.0), it.optInt("rank", 99))
        }
        return Rank(nodes, r.optString("best"), r.optLong("connect_timeout_ms", 6000L).coerceIn(3000L, 15000L))
    }

    // ───────────── 1.2.6: умный ввод (ключ / код VPN-… / код восстановления / номер подписки) ─────────────

    sealed class Redeem {
        data class Ok(val link: String, val kind: String) : Redeem()
        /** Номер подписки: вход подтверждается в Telegram, затем опрос redeemPoll(req). */
        data class WaitTelegram(val req: String, val message: String) : Redeem()
        data class Failed(val message: String) : Redeem()
    }

    fun redeem(input: String): Redeem {
        val body = JSONObject().put("input", input.trim()).put("did", did()).put("platform", "android")
            .put("device_name", ScSecureStore.deviceName())
        val (http, r) = post("redeem", body)
        if (http == 0) return Redeem.Failed("Нет связи с сервером. Проверь интернет и повтори")
        if (r != null && r.optString("need") == "telegram") return Redeem.WaitTelegram(r.optString("req"), r.optString("message"))
        return finishRedeem(http, r)
    }

    /** null — ещё ждём подтверждения (или нет связи: повторим). */
    fun redeemPoll(req: String): Redeem? {
        val (http, r) = post("redeem/poll", JSONObject().put("req", req))
        if (http == 0 || r == null || r.optBoolean("wait")) return null
        if (r.optBoolean("expired") || r.optBoolean("denied")) return Redeem.Failed(r.optString("error").ifEmpty { "Вход не подтверждён" })
        return finishRedeem(http, r)
    }

    private fun finishRedeem(http: Int, r: JSONObject?): Redeem {
        val tok = r?.optString("token").orEmpty()
        if (http != 200 || r == null || tok.isEmpty())
            return Redeem.Failed(r?.optString("error").orEmpty().ifEmpty { "Не удалось войти — проверь, что вставил ключ или код целиком" })
        prefs.edit().putString(K_TOKEN, tok).putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true)
            .putBoolean(K_GUEST, false).remove(K_DEAD).remove(ScSecureStore.K_LOCK_UNTIL).remove(ScSecureStore.K_LOCK_NAME).apply()
        return Redeem.Ok(r.optString("link"), r.optString("kind"))
    }

    // ───────────── 1.2.6: привязка телевизора ─────────────

    data class TvPair(val pairId: String, val secret: String, val userCode: String, val qrUrl: String, val expiresIn: Int, val interval: Int)

    sealed class TvPoll {
        object Pending : TvPoll()
        object Expired : TvPoll()
        data class Approved(val link: String, val accountState: String, val message: String) : TvPoll()
    }

    /** ТВ: запросить код и QR. null — нет связи. */
    fun tvPairStart(deviceName: String): TvPair? {
        val (c, r) = post("tv/pair/start", JSONObject().put("did", did()).put("device_name", deviceName))
        if (c != 200 || r == null || !r.optBoolean("ok")) return null
        val id = r.optString("pair_id")
        if (id.isEmpty()) return null
        return TvPair(id, r.optString("device_secret"), r.optString("user_code"), r.optString("qr_url"),
            r.optInt("expires_in", 600), r.optInt("interval", 3).coerceIn(2, 10))
    }

    /** ТВ: опрос. null — нет связи (повторим). При подтверждении токен уже сохранён. */
    fun tvPairPoll(p: TvPair): TvPoll? {
        val (c, r) = post("tv/pair/poll", JSONObject().put("pair_id", p.pairId).put("device_secret", p.secret))
        if (c == 0 || r == null) return null
        return when (r.optString("state")) {
            "pending" -> TvPoll.Pending
            "approved" -> {
                val tok = r.optString("token")
                if (tok.isEmpty()) TvPoll.Expired else {
                    prefs.edit().putString(K_TOKEN, tok).putBoolean(K_FROM_INSTALL, false).putBoolean(K_REG_DONE, true)
                        .putBoolean(K_GUEST, false).remove(K_DEAD).apply()
                    TvPoll.Approved(r.optString("link"), r.optString("account_state"), r.optString("message"))
                }
            }
            else -> TvPoll.Expired                 // expired / invalid / consumed — просим новый код
        }
    }

    /** Телефон (приложение): подтвердить код с экрана ТВ. null — успех, иначе текст ошибки. */
    fun tvApprove(code: String): String? {
        if (token.isEmpty()) return "Сначала войди в приложение"
        val (c, r) = post("tv/approve", JSONObject().put("code", code.filter { it.isDigit() }), token)
        if (c == 0) return "Нет связи с сервером. Проверь интернет и повтори"
        if (c == 200 && r != null && r.optBoolean("ok")) return null
        return r?.optString("error").orEmpty().ifEmpty { "Не удалось подключить телевизор" }
    }

    /** Код связки с Telegram: (код, ссылка) или (null, текст ошибки). */
    fun linkCode(): Pair<String?, String> {
        if (token.isEmpty()) return null to "Сначала войди в приложение"
        val (c, r) = post("link", JSONObject(), token)
        if (c == 200 && r != null && r.optBoolean("ok")) return r.optString("code") to r.optString("url")
        return null to (r?.optString("error")?.takeIf { it.isNotEmpty() } ?: "Нет связи с сервером")
    }
}
