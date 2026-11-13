package com.v2ray.ang.handler

import android.content.Context
import android.os.Looper
import com.v2ray.ang.AngApplication
import com.v2ray.ang.util.LogUtil
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Siberia Connect 1.2.3: адреса наших серверов (DuckDNS-имена) разрешаются через DoH по IP-адресам Cloudflare/Google,
 * а не через DNS провайдера, который в РФ может подменять или блокировать имена. Результат кладётся в dns.hosts конфига Xray
 * (SNI и проверка Reality не меняются). Не вышло — конфиг строится как раньше. Кэш 6 часов, при сбое сети — до 24 часов.
 */
object ScDns {
    private const val TTL = 6 * 3600_000L
    private const val STALE = 24 * 3600_000L
    private val DOH = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )

    private fun prefs() = AngApplication.application.getSharedPreferences("sc_dns", Context.MODE_PRIVATE)

    /** домен → IPv4 для имён серверов нашей подписки */
    fun ourHosts(): Map<String, String> {
        val sub = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.remarks == ScLock.SUB_NAME && ScLock.isOurSub(it) }
            ?: return emptyMap()
        val names = MmkvManager.decodeServerList(sub.guid)
            .mapNotNull { MmkvManager.decodeServerConfig(it)?.server }
            .filter { it.isNotBlank() && it.any { c -> c.isLetter() } && it.contains('.') }
            .map { it.lowercase() }.distinct()
        val out = LinkedHashMap<String, String>()
        for (n in names) resolve(n)?.let { out[n] = it }
        return out
    }

    private fun resolve(host: String): String? {
        val now = System.currentTimeMillis()
        val cached = prefs().getString(host, null)?.split("|")
        val ip = cached?.getOrNull(0)
        val ts = cached?.getOrNull(1)?.toLongOrNull() ?: 0L
        if (ip != null && now - ts < TTL) return ip
        // в главном потоке сеть запрещена — берём что есть
        if (Looper.myLooper() == Looper.getMainLooper()) return if (ip != null && now - ts < STALE) ip else null
        // DoH недоступен (заблокирован) — не ждём таймаутов при каждой сборке конфига, повтор через 10 минут
        if (now - prefs().getLong("_fail", 0L) > 10 * 60_000L) {
        for (tpl in DOH) {
            val r = runCatching { doh(String.format(tpl, host)) }.getOrNull()
            if (r != null) {
                prefs().edit().putString(host, "$r|$now").apply()
                LogUtil.i("ScDns", "$host -> DoH")
                return r
            }
        }
        prefs().edit().putLong("_fail", now).apply()
        }
        return if (ip != null && now - ts < STALE) ip else null
    }

    private fun doh(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500
        c.setRequestProperty("accept", "application/dns-json")
        try {
            if (c.responseCode != 200) return null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer") ?: return null
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                if (a.optInt("type") == 1) {
                    val d = a.optString("data")
                    // защита от мусора: только публичный IPv4
                    val ia = InetAddress.getByName(d)
                    if (ia.address.size == 4 && !ia.isAnyLocalAddress && !ia.isLoopbackAddress && !ia.isSiteLocalAddress) return d
                }
            }
        } finally {
            c.disconnect()
        }
        return null
    }
}
