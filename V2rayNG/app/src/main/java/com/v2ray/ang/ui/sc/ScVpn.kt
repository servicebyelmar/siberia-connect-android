package com.v2ray.ang.ui.sc

import android.content.Context
import android.os.Build
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ScLock
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.2.6: подписка и выбор сервера без привязки к Activity — общий слой для ТВ-приложения и фонового сторожа
 * (ScWatchdog). Логика та же, что в ScActivity (мобильное приложение), но без WebView.
 * TODO: перевести ScActivity на этот объект и убрать дубли (отдельным шагом, с проверкой на устройстве).
 */
object ScVpn {
    fun ourSub(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == ScLock.SUB_NAME && ScLock.isOurSub(it) }

    fun serverCount(): Int = ourSub()?.let { MmkvManager.decodeServerList(it.guid).size } ?: 0

    fun hasSub(): Boolean = serverCount() > 0

    /** Выход / отзыв ключа: подписка и ключ стираются. */
    fun dropSub(ctx: Context) {
        ourSub()?.let { runCatching { MmkvManager.removeSubscription(it.guid) } }
        ScSecureStore.clearKey(ctx)
    }

    private fun placeholderOnly(): Boolean {
        val sub = ourSub() ?: return true
        val list = MmkvManager.decodeServerList(sub.guid)
        return list.isNotEmpty() && list.all { MmkvManager.decodeServerConfig(it)?.server == "127.0.0.1" }
    }

    private fun applySub(ctx: Context, did: String, url: String): JSONObject {
        val out = JSONObject().put("type", "sub")
        if (!ScLock.isAllowedSubUrl(url)) return out.put("ok", false).put("error", ScLock.DENIED_KEY)
        val existing = ourSub()
        val item = existing?.subscription ?: SubscriptionItem()
        item.remarks = ScLock.SUB_NAME
        item.url = url
        item.enabled = true
        item.autoUpdate = true
        item.updateInterval = 60
        item.requestHeaders = JSONObject()
            .put("x-hwid", did)
            .put("x-device-os", "Android")
            .put("x-ver-os", Build.VERSION.RELEASE ?: "")
            .put("x-device-model", Build.MODEL ?: "")
            .toString()
        MmkvManager.encodeSubscription(existing?.guid ?: "", item)
        val sub = ourSub() ?: return out.put("ok", false).put("error", "Не удалось сохранить подписку")
        val res = AngConfigManager.updateConfigViaSub(sub)
        runCatching { SubscriptionUpdater.syncOne(ctx.applicationContext, sub.guid) }
        val list = MmkvManager.decodeServerList(sub.guid)
        val sel = MmkvManager.getSelectServer()
        if (list.isNotEmpty() && (sel == null || !list.contains(sel))) MmkvManager.setSelectServer(list.first())
        return out.put("ok", list.isNotEmpty()).put("count", list.size).put("updated", res.successCount)
            .put("error", if (list.isEmpty()) "Сервер не выдал список. Проверь интернет или лимит устройств" else "")
    }

    /** Та же подписка с резервом: основной адрес слоя подписок, затем relay (и наоборот). Не вызывать из главного потока. */
    fun applySubAny(ctx: Context, did: String, url: String): JSONObject {
        var last: JSONObject? = null
        for (u in ScLock.subCandidates(url)) {
            val r = runCatching { applySub(ctx, did, u) }.getOrElse {
                JSONObject().put("type", "sub").put("ok", false).put("error", it.message ?: "Ошибка")
            }
            if (r.optBoolean("ok") && !placeholderOnly()) {
                ScSecureStore.saveKey(ctx, u)
                return r
            }
            if (r.optBoolean("ok")) r.put("ok", false).put("error", "Сервер не принял ключ — проверь лимит устройств")
            last = r
        }
        return last ?: applySub(ctx, did, url)
    }

    /** Сервер выдал (новый) ключ этого устройства — ставим его. true — подписка заменена. */
    fun applyActiveLink(ctx: Context, did: String, st: JSONObject?): Boolean {
        if (st?.optString("state") != "active") return false
        val link = st.optString("link")
        if (!ScLock.isAllowedSubUrl(link)) return false
        if (link in ScLock.subCandidates(ourSub()?.subscription?.url)) return false
        return applySubAny(ctx, did, link).optBoolean("ok")
    }

    private fun nodeOf(rank: ScAccount.Rank?, guid: String): ScAccount.Node? {
        val title = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()
        if (rank == null || title.isEmpty()) return null
        return rank.nodes.firstOrNull { (it.name.isNotEmpty() && title.contains(it.name, true)) || (it.key.isNotEmpty() && title.contains(it.key, true)) }
    }

    /**
     * «Авто»: задержка до серверов через ядро и рейтинг нагрузки с сервера. Не больше [parallel] замеров одновременно
     * (на ТВ-приставках с 1–2 ГБ ОЗУ — 2), всё не дольше 12 с. Ничего не ответило — прежний выбор не трогаем.
     */
    suspend fun pickAuto(ctx: Context, account: ScAccount, parallel: Int = 2) {
        val sub = ourSub() ?: return
        val list = MmkvManager.decodeServerList(sub.guid)
        if (list.size < 2) return
        val rank = runCatching { account.nodesRank() }.getOrNull()
        runCatching { CoreNativeManager.initCoreEnv(ctx.applicationContext) }
        val url = SettingsManager.getDelayTestUrl()
        val res = ConcurrentHashMap<Int, Long>()
        val gate = Semaphore(parallel.coerceAtLeast(1))
        withTimeoutOrNull(12_000L) {
            coroutineScope {
                list.mapIndexed { i, g ->
                    async(Dispatchers.IO) {
                        gate.withPermit {
                            val cfg = CoreConfigManager.getV2rayConfig4Speedtest(ctx, g)
                            res[i] = if (cfg.status) CoreNativeManager.measureOutboundDelay(cfg.content, url) else -1L
                        }
                    }
                }.awaitAll()
            }
        }
        var ok = list.indices.filter { (res[it] ?: -1L) > 0L }
        if (ok.isEmpty()) return
        ok.filter { nodeOf(rank, list[it])?.available != false }.takeIf { it.isNotEmpty() }?.let { ok = it }
        val best = ok.minOf { res[it]!! }
        val slack = maxOf(20L, best * 15 / 100)
        val pick = ok.filter { res[it]!! <= best + slack }
            .minWithOrNull(compareBy({ nodeOf(rank, list[it])?.rank ?: 99 }, { it })) ?: return
        MmkvManager.setSelectServer(list[pick])
    }
}
