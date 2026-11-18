package com.v2ray.ang.handler

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.ui.sc.ScAccount
import com.v2ray.ang.ui.sc.ScSecureStore
import com.v2ray.ang.ui.sc.ScVpn
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Siberia Connect 1.2.3: проверка связи, пока VPN включён. Раз в минуту (и через 8 с после смены сети Wi-Fi↔LTE)
 * проверяем выбранный сервер; два неудачных замера подряд (с паузой 15 с) — в режиме «Авто» переключаемся на самый быстрый
 * из остальных и перезапускаем ядро на месте. Не чаще раза в 3 минуты. Ручной выбор сервера не трогаем.
 *
 * 1.2.6:
 *  • раз в 3 минуты — статус ключа на сервере: ключ перевыпустили → ставим новый и перезапускаем ядро; подписка кончилась,
 *    заблокирован, ключ отозван → VPN выключается (а не висит «Подключено» без трафика);
 *  • раз в 5 минут и сразу после смены сети — реальный объём через туннель (~128 КБ): 204 проходит, а поток обрывается
 *    (заморозка ТСПУ) — два провала подряд → следующий сервер. Трафик: ~37 МБ в сутки.
 */
class ScWatchdog(private val reload: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastFailover = 0L
    private var tickN = 0
    private var bulkFails = 0
    @Volatile private var forceBulk = false

    fun start() {
        stop()
        tickN = 0
        bulkFails = 0
        job = scope.launch {
            delay(60_000)
            while (isActive) {
                runCatching { tick() }.onFailure { LogUtil.e("ScWatchdog", "tick: ${it.message}", it) }
                delay(60_000)
            }
        }
    }

    /** после смены сети: проверить быстрее (и объём тоже) */
    fun kick() {
        scope.launch {
            delay(8_000)
            forceBulk = true
            runCatching { tick(extra = true) }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private fun measure(ctx: Context, guid: String): Long {
        val cfg = CoreConfigManager.getV2rayConfig4Speedtest(ctx, guid)
        return if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, SettingsManager.getDelayTestUrl()) else -1L
    }

    /**
     * Реальный трафик через запущенное ядро: ~128 КБ через его локальный SOCKS-вход. Наше приложение исключено из VPN,
     * поэтому обычный запрос туннель не проверит — идём именно через SOCKS. Если у SOCKS включён логин (в нашей сборке нет) —
     * проверку пропускаем, не рискуя ложной тревогой.
     */
    private fun bulkOk(): Boolean {
        if (SettingsManager.getSocksUsername() != null) return true
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", SettingsManager.getSocksPort()))
        for (u in BULK_URLS) {
            try {
                val c = URL(u).openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.setRequestProperty("Range", "bytes=0-${BULK_BYTES + 11071}")
                try {
                    val buf = ByteArray(16384)
                    var total = 0
                    c.inputStream.use { s ->
                        while (total < BULK_BYTES) {
                            val n = s.read(buf)
                            if (n < 0) break
                            total += n
                        }
                    }
                    if (total >= BULK_BYTES) return true
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** Статус ключа. Возвращает true, если ядро надо перезапустить (ключ/серверы заменились). */
    private fun checkAccount(ctx: Context): Boolean {
        val did = ScSecureStore.deviceUuid(ctx)
        val acc = ScAccount(ScSecureStore.prefs(ctx)) { did }
        if (acc.token.isEmpty()) return false
        val st = acc.status()
        when (acc.outcome) {
            ScAccount.Outcome.REVOKED -> {
                LogUtil.i("ScWatchdog", "ключ отозван — выключаю VPN")
                ScVpn.dropSub(ctx)
                stopVpn(ctx)
                return false
            }
            ScAccount.Outcome.LOCKED, ScAccount.Outcome.REBIND -> {
                LogUtil.i("ScWatchdog", "ключ привязан к другому устройству — выключаю VPN")
                ScTelemetry.log("watchdog: key is bound to another device")
                ScTelemetry.report(ctx, ScTelemetryGate.DEVICE_LOCKED, ScTelemetry.locationOf(MmkvManager.getSelectServer()))   // 1.2.7: фон
                stopVpn(ctx)
                return false
            }
            else -> Unit
        }
        if (st == null) return false          // нет связи / нет входа — ничего не трогаем
        val state = st.optString("state")
        if (state in ScAccount.BAD_STATES) {
            LogUtil.i("ScWatchdog", "статус $state — выключаю VPN")
            stopVpn(ctx)
            return false
        }
        if (state == "active") return ScVpn.applyActiveLink(ctx, did, st)
        return false
    }

    private fun stopVpn(ctx: Context) {
        Handler(Looper.getMainLooper()).post { runCatching { LauncherManager.stopService(ctx) } }
    }

    private suspend fun tick(extra: Boolean = false) {
        val ctx = AngApplication.application
        if (!extra) tickN++
        if (!extra && tickN % 3 == 0) {
            if (runCatching { checkAccount(ctx) }.getOrDefault(false)) {
                LogUtil.i("ScWatchdog", "ключ обновлён — перезапускаю ядро")
                reload()
                return
            }
        }
        if (!ctx.getSharedPreferences("sc_app", Context.MODE_PRIVATE).getBoolean("auto", true)) return
        val sub = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == ScLock.SUB_NAME && ScLock.isOurSub(it) } ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.size < 2) return
        val sel = MmkvManager.getSelectServer() ?: return
        val alive = measure(ctx, sel) > 0
        if (alive) {
            // 204 есть — раз в 5 минут (или после смены сети) проверяем, что идёт именно объём
            val wantBulk = forceBulk || (!extra && tickN % 5 == 0)
            if (!wantBulk) return
            forceBulk = false
            if (bulkOk()) { bulkFails = 0; return }
            if (++bulkFails < 2) return
            bulkFails = 0
            LogUtil.i("ScWatchdog", "204 проходит, а объём обрывается — переключаюсь")
            ScTelemetry.log("watchdog: 204 ok but bulk transfer stalls")
            ScTelemetry.report(ctx, ScTelemetryGate.NO_TRAFFIC, ScTelemetry.locationOf(sel))     // 1.2.7: заморозка потока; фон
        } else {
            delay(15_000)
            if (measure(ctx, sel) > 0) return
            ScTelemetry.log("watchdog: selected server does not answer twice in a row")
            ScTelemetry.report(ctx, ScTelemetryGate.NO_TRAFFIC, ScTelemetry.locationOf(sel))     // фон: сам отсеет «нет интернета»
        }
        if (System.currentTimeMillis() - lastFailover < 180_000L) return
        val others = list.filter { it != sel }
            .map { g -> scope.async { g to measure(ctx, g) } }.awaitAll()
            .filter { it.second > 0 }
        val best = others.minByOrNull { it.second } ?: return      // никто не отвечает — скорее всего, нет интернета
        lastFailover = System.currentTimeMillis()
        LogUtil.i("ScWatchdog", "сервер не отвечает, переключаюсь (${best.second} мс)")
        MmkvManager.setSelectServer(best.first)
        reload()
    }

    companion object {
        private const val BULK_BYTES = 120_000
        private val BULK_URLS = listOf(
            "https://speed.cloudflare.com/__down?bytes=131072",
            "https://proof.ovh.net/files/1Mb.dat",
        )
    }
}
