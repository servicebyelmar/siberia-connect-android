package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionCache
import java.net.URI

/**
 * Siberia Connect: замок. В приложение попадает только наш ключ (подписка с наших адресов),
 * запускаются только серверы из этой подписки. Ручной импорт, QR, ссылки v2rayng:// и чужие подписки закрыты.
 */
object ScLock {
    val ENABLED = true
    const val SUB_NAME = "Siberia Connect"
    /** unified_v1: + relay sibir-cloud-api.ru — подписка с резервного адреса тоже «своя» */
    val ALLOWED_HOSTS = setOf("snezhnet.duckdns.org", "sibirapp.duckdns.org", "sibir-cloud-api.ru")

    /** unified_v1: адреса слоя подписок (тот же путь и ключ) — основной и резерв; при сбое пробуем следующий */
    val SUB_AUTHORITIES = listOf("snezhnet.duckdns.org:2096", "sibir-cloud-api.ru:9443")

    /** Та же подписка на всех адресах слоя подписок: сначала исходный, затем остальные. */
    fun subCandidates(url: String?): List<String> {
        if (url.isNullOrBlank()) return emptyList()
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return listOf(url)
        val auth = u.rawAuthority ?: return listOf(url)
        if (auth !in SUB_AUTHORITIES) return listOf(url)
        val tail = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        return listOf(url) + SUB_AUTHORITIES.filter { it != auth }.map { "https://$it$tail" }
    }
    const val DENIED_KEY = "Это не ключ Siberia Connect"
    const val DENIED_RUN = "Можно подключаться только к серверам Siberia Connect"

    fun isAllowedSubUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return runCatching {
            val u = URI(url.trim())
            u.scheme.equals("https", ignoreCase = true) &&
                (u.host ?: "").lowercase() in ALLOWED_HOSTS &&
                u.userInfo == null
        }.getOrDefault(false)
    }

    fun isOurSub(sub: SubscriptionCache?): Boolean = sub != null && isAllowedSubUrl(sub.subscription.url)

    fun ourSubIds(): Set<String> =
        MmkvManager.decodeSubscriptions().filter { isOurSub(it) }.map { it.guid }.toSet()

    fun canRun(guid: String?): Boolean {
        if (!ENABLED) return true
        if (guid.isNullOrEmpty()) return false
        val p = MmkvManager.decodeServerConfig(guid) ?: return false
        val subId: String = p.subscriptionId ?: return false
        return subId in ourSubIds()
    }
}
