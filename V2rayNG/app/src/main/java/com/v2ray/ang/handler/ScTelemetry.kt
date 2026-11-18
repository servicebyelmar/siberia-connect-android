package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.ui.sc.ScAccount
import com.v2ray.ang.ui.sc.ScActivity
import com.v2ray.ang.ui.sc.ScSecureStore
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 1.2.7: чистая логика фоновой телеметрии (без сети, Android и состояния) — её проверяет юнит-тест ScTelemetryGateTest.
 * Правило одно: отчёт уходит только о РЕАЛЬНОМ сбое VPN. Обычного интернета нет, пользователь/приложение само остановило VPN,
 * устройство только что проснулось, та же причина уже отправлялась недавно — молчим. Логика совпадает с Windows (TelemetryGate).
 */
object ScTelemetryGate {
    const val TUNNEL_TIMEOUT = "TUNNEL_TIMEOUT"       // сервер не отвечает при подключении / ядро не поднялось
    const val DEVICE_LOCKED = "DEVICE_LOCKED"         // 409: ключ привязан к другому устройству
    const val NO_TRAFFIC = "NO_TRAFFIC"               // туннель поднят, трафика нет (заморозка / блокировка протокола)
    const val SESSION_DROPPED = "SESSION_DROPPED"     // VPN неожиданно остановился
    // AUTH_FAILED не шлём: при 401 токен уже недействителен, а отчёт без токена сервер не принимает.

    val REASONS: Set<String> = setOf(TUNNEL_TIMEOUT, DEVICE_LOCKED, NO_TRAFFIC, SESSION_DROPPED)

    const val DEDUP_SECONDS = 600.0            // не чаще 1 отчёта по одной причине раз в 10 минут
    const val AFTER_SLEEP_SECONDS = 10.0       // первые 10 секунд после пробуждения устройства — шум сети
    const val USER_STOP_QUIET_SECONDS = 30.0   // после остановки VPN пользователем/приложением
    const val MAX_LINES = 25
    const val MAX_LINE_LENGTH = 300

    /** null — отправлять можно; иначе короткий код причины отказа. secSince* — секунд с события, +∞ — события не было. */
    fun decide(
        reason: String?,
        baseNetOk: Boolean,
        secSinceWake: Double,
        secSinceUserStop: Double,
        secSinceLastSame: Double,
    ): String? {
        if (reason == null || reason !in REASONS) return "bad_reason"
        if (!baseNetOk) return "no_base_net"
        if (secSinceUserStop < USER_STOP_QUIET_SECONDS) return "user_disconnect"
        if (secSinceWake < AFTER_SLEEP_SECONDS) return "after_sleep"
        if (secSinceLastSame < DEDUP_SECONDS) return "duplicate"
        return null
    }

    // Шаблон → подстановка. Порядок важен: сначала ссылки и ключи, потом общие маски. Регистр не важен.
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("""\b(?:vless|vmess|trojan|ss|hysteria2?|tuic)://\S+""", RegexOption.IGNORE_CASE) to "[link]",
        Regex("""https?://\S*(?:sub|key|token)\S*""", RegexOption.IGNORE_CASE) to "[url]",
        Regex("""\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""", RegexOption.IGNORE_CASE) to "[uuid]",
        Regex("""\bSC-[A-Z0-9]{4,5}(?:-[A-Z0-9]{4,5}){1,2}\b""", RegexOption.IGNORE_CASE) to "[sc-key]",
        Regex("""(x-app-token|authorization|bearer|token|secret|password|pass|key)\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE) to "\$1=[hidden]",
        Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""") to "[email]",
        Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""") to "[ip]",
        Regex("""\b(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}\b""", RegexOption.IGNORE_CASE) to "[ipv6]",
        Regex("""\b[0-9a-f]{20,}\b""", RegexOption.IGNORE_CASE) to "[hex]",
        Regex("""\b[A-Za-z0-9_-]{32,}\b""") to "[token]",
    )

    /** Последние ≤25 строк, каждая ≤300 символов, без ссылок/UUID/токенов/ключей/e-mail/IP. */
    fun cleanLog(lines: List<String>?): List<String> {
        val tail = (lines ?: emptyList()).takeLast(MAX_LINES)
        return tail.map { raw ->
            var s = if (raw.length > 2000) raw.substring(0, 2000) else raw
            for ((rx, rep) in RULES) s = rx.replace(s, rep)
            if (s.length > MAX_LINE_LENGTH) s.substring(0, MAX_LINE_LENGTH) else s
        }
    }
}

/**
 * 1.2.7: фоновая отправка отчётов о сбоях VPN (POST /app/api/v1/telemetry). Ничего не показывает пользователю сама, не блокирует
 * подключение и не бросает исключений. Приложение не идёт через собственный VPN (addDisallowedApplication), поэтому проверка
 * «обычный интернет есть» работает и при включённом туннеле — в отличие от Windows в режиме «Весь компьютер».
 */
object ScTelemetry {
    private const val TAG = "ScTelemetry"
    private val lock = Any()
    private val ring = java.util.ArrayDeque<String>()
    private val lastSent = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private var wakeAt = 0L
    private var userStopAt = 0L
    private var restartAt = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Сервер принял отчёт: код обращения INC-XXXXXX (экран показывает «Написать в поддержку»). */
    @Volatile var onIncident: ((String) -> Unit)? = null
    @Volatile private var lastIncident: Pair<String, Long>? = null

    /** Код недавнего инцидента (например, для экрана после возврата в приложение). */
    fun recentIncident(maxAgeMs: Long): String? =
        lastIncident?.takeIf { System.currentTimeMillis() - it.second <= maxAgeMs }?.first

    /** Короткий журнал для отчёта (чистится перед отправкой). */
    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            ring.addLast("$t $msg")
            while (ring.size > 200) ring.removeFirst()
        }
    }

    /** Название сервера для отчёта (по guid в MMKV). */
    fun locationOf(guid: String?): String = guid?.let { runCatching { MmkvManager.decodeServerConfig(it)?.remarks }.getOrNull() }.orEmpty()

    fun noteWake() = synchronized(lock) { wakeAt = System.currentTimeMillis() }

    /** VPN остановил пользователь или само приложение: ближайшие секунды сбоем не считаются. */
    fun noteUserStop() = synchronized(lock) { userStopAt = System.currentTimeMillis() }

    /** Приложение само перезапускает ядро (смена сервера, обновление ключа): кратковременная остановка — не «обрыв сессии». */
    fun noteAppRestart() = synchronized(lock) { restartAt = System.currentTimeMillis() }

    private fun secSince(t: Long): Double = if (t == 0L) Double.POSITIVE_INFINITY else (System.currentTimeMillis() - t) / 1000.0

    private class Snap(val wake: Double, val user: Double, val last: Double)

    private fun snap(reason: String): Snap = synchronized(lock) {
        // перезапуск ядра приложением глушит только отчёт «обрыв сессии»; сбои подключения после перезапуска (все серверы молчат) остаются
        val stop = if (reason == ScTelemetryGate.SESSION_DROPPED) minOf(secSince(userStopAt), secSince(restartAt)) else secSince(userStopAt)
        Snap(secSince(wakeAt), stop, secSince(lastSent[reason] ?: 0L))
    }

    /** Поставить отчёт в очередь; фильтры и сеть — в фоне. delayMs — если стоит подождать, пока сеть успокоится. */
    fun report(ctx: Context, reason: String, location: String = "", delayMs: Long = 0L) {
        val app = ctx.applicationContext
        synchronized(lock) { if (!inFlight.add(reason)) return }
        scope.launch {
            try {
                send(app, reason, location, delayMs)
            } catch (e: Exception) {
                LogUtil.e(TAG, "telemetry: ${e.message}", e)
            } finally {
                synchronized(lock) { inFlight.remove(reason) }
            }
        }
    }

    private suspend fun send(ctx: Context, reason: String, location: String, delayMs: Long) {
        val did = ScSecureStore.deviceUuid(ctx)
        val token = ScAccount(ScSecureStore.prefs(ctx)) { did }.token
        if (token.isEmpty()) return
        if (delayMs > 0) delay(delayMs)
        var s = snap(reason)
        // дешёвые проверки до сети (base_net_ok считаем истинным, чтобы отсеять остальное)
        val pre = ScTelemetryGate.decide(reason, true, s.wake, s.user, s.last)
        if (pre != null) { log("telemetry: $reason skipped ($pre)"); return }
        val net = baseNetOk()
        s = snap(reason)
        val why = ScTelemetryGate.decide(reason, net, s.wake, s.user, s.last)
        if (why != null) { log("telemetry: $reason skipped ($why)"); return }
        val now = System.currentTimeMillis()
        synchronized(lock) { lastSent[reason] = now }

        val lines = synchronized(lock) { ring.toList() }
        val body = JSONObject()
            .put("reason", reason)
            .put("base_net_ok", true)
            .put("user_initiated", false)
            .put("did", did)
            .put("platform", "android")
            .put("app_ver", BuildConfig.VERSION_NAME)
            .put("location", location)
            .put("log", JSONArray(ScTelemetryGate.cleanLog(lines)))
        if (s.wake.isFinite() && s.wake < 3600) body.put("since_wake_s", Math.round(s.wake))

        for (host in listOf(ScActivity.PRIMARY_HOST, ScActivity.FALLBACK_HOST)) {      // основной и резервный адреса
            val res = post(host, body, token) ?: continue
            if (res.first == 401) return                    // токен недействителен — повторять незачем
            if (res.first !in 200..299) continue            // другой адрес может ответить
            val inc = res.second?.optString("incident").orEmpty()
            log("telemetry: $reason accepted" + if (inc.isNotEmpty()) " $inc" else "")
            if (res.second?.optBoolean("accepted") == true && inc.isNotEmpty()) {
                lastIncident = inc to System.currentTimeMillis()
                onIncident?.invoke(inc)
            }
            return
        }
    }

    private fun post(host: String, body: JSONObject, token: String): Pair<Int, JSONObject?>? = try {
        val c = URL("https://$host/app/api/v1/telemetry").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "SiberiaConnectAndroid/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("X-App-Token", token)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        code to runCatching { JSONObject(text) }.getOrNull()
    } catch (e: Exception) {
        LogUtil.e(TAG, "post $host: ${e.message}", e)
        null
    }

    /** Обычный интернет: TCP 443 к нейтральным 1.1.1.1 / 8.8.8.8 и generate_204. Достаточно одного ответа. */
    private suspend fun baseNetOk(): Boolean = coroutineScope {
        val probes = listOf(
            async(Dispatchers.IO) { tcp("1.1.1.1") },
            async(Dispatchers.IO) { tcp("8.8.8.8") },
            async(Dispatchers.IO) { http204() },
        )
        var ok = false
        for (p in probes) if (p.await()) { ok = true; break }
        ok
    }

    private fun tcp(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, 443), 2500) }
        true
    } catch (e: Exception) {
        false
    }

    private fun http204(): Boolean = try {
        val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
        c.connectTimeout = 2500
        c.readTimeout = 2500
        val ok = c.responseCode in 200..399
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }
}
