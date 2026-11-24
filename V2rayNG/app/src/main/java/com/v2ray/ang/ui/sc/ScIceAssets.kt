package com.v2ray.ang.ui.sc

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Тестовая сборка (flavor icetest): новый интерфейс лежит внутри APK (assets/ice/index.html) и отдаётся WebView
 * по адресу https://<основной хост>/sc-ice/ — вместо страницы с сервера. Сервер на него повлиять не может,
 * поэтому тестер гарантированно видит именно этот интерфейс, в том числе при повторном открытии и без сети.
 *
 * Путь /sc-ice/ намеренно вне области service worker кабинета (/app/): иначе его fetch-обработчик мог бы
 * перехватить навигацию раньше приложения. Origin тот же, что у кабинета, — localStorage (токен входа) общий.
 */
internal class ScIceAssets(private val context: Context) {

    companion object {
        const val PATH = "/sc-ice/"
        private const val ASSET = "ice/index.html"
        private val CABINET_HOSTS = setOf(ScActivity.PRIMARY_HOST, ScActivity.FALLBACK_HOST)

        /** Адрес нового интерфейса на основном хосте. */
        const val HOME = "https://${ScActivity.PRIMARY_HOST}${PATH}"

        fun isIceUrl(url: String?): Boolean {
            val u = url ?: return false
            return CABINET_HOSTS.any { u.startsWith("https://$it$PATH") }
        }
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != "https" || uri.authority !in CABINET_HOSTS) return null
        if (!request.method.equals("GET", true)) return null
        val p = uri.path.orEmpty()
        if (p != PATH && p != PATH + "index.html") return null
        val body = runCatching { context.assets.open(ASSET) }.getOrNull() ?: return null
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            body
        )
    }
}
