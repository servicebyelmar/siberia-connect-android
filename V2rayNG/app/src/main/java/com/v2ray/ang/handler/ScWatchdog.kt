package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AngApplication
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
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

/**
 * Siberia Connect 1.2.3: проверка связи, пока VPN включён. Раз в минуту (и через 8 с после смены сети Wi-Fi↔LTE)
 * проверяем выбранный сервер; два неудачных замера подряд (с паузой 15 с) — в режиме «Авто» переключаемся на самый быстрый
 * из остальных и перезапускаем ядро на месте. Не чаще раза в 3 минуты. Ручной выбор сервера не трогаем.
 */
class ScWatchdog(private val reload: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastFailover = 0L

    fun start() {
        stop()
        job = scope.launch {
            delay(60_000)
            while (isActive) {
                runCatching { tick() }.onFailure { LogUtil.e("ScWatchdog", "tick: ${it.message}", it) }
                delay(60_000)
            }
        }
    }

    /** после смены сети: проверить быстрее */
    fun kick() {
        scope.launch {
            delay(8_000)
            runCatching { tick() }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private fun measure(ctx: Context, guid: String): Long {
        val cfg = CoreConfigManager.getV2rayConfig4Speedtest(ctx, guid)
        return if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, SettingsManager.getDelayTestUrl()) else -1L
    }

    private suspend fun tick() {
        val ctx = AngApplication.application
        if (!ctx.getSharedPreferences("sc_app", Context.MODE_PRIVATE).getBoolean("auto", true)) return
        val sub = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == ScLock.SUB_NAME && ScLock.isOurSub(it) } ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.size < 2) return
        val sel = MmkvManager.getSelectServer() ?: return
        if (measure(ctx, sel) > 0) return
        delay(15_000)
        if (measure(ctx, sel) > 0) return
        if (System.currentTimeMillis() - lastFailover < 180_000L) return
        val alive = list.filter { it != sel }
            .map { g -> scope.async { g to measure(ctx, g) } }.awaitAll()
            .filter { it.second > 0 }
        val best = alive.minByOrNull { it.second } ?: return      // никто не отвечает — скорее всего, нет интернета
        lastFailover = System.currentTimeMillis()
        LogUtil.i("ScWatchdog", "сервер не отвечает, переключаюсь (${best.second} мс)")
        MmkvManager.setSelectServer(best.first)
        reload()
    }
}
