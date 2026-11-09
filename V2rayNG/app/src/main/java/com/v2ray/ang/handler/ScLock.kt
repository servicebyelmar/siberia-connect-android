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
    val ALLOWED_HOSTS = setOf("snezhnet.duckdns.org", "sibirapp.duckdns.org")
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
