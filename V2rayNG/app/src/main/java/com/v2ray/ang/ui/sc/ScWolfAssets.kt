package com.v2ray.ang.ui.sc

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Файлы анимации логотипа (assets/wolf/: ролик, кадры, вой) для WebView кабинета.
 * Страница берёт их по адресу https://sc-assets.local/<имя> (его отдаёт [BASE] через SCNative.assetBase()),
 * сеть при этом не используется. Видео запрашивается кусками (Range), поэтому ответы 206 поддержаны.
 */
internal class ScWolfAssets(private val context: Context) {

    companion object {
        const val HOST = "sc-assets.local"
        const val BASE = "https://$HOST/"
        private val NAME = Regex("^wolf[-_][a-z]+\\.(mp4|mp3|jpg|webp|ogg)$")
        private val RANGE = Regex("^bytes=(\\d*)-(\\d*)$")
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        if (uri.scheme != "https" || uri.host != HOST) return null
        val name = uri.lastPathSegment.orEmpty()
        if (!NAME.matches(name)) return response(404, "Not Found", "text/plain", emptyMap(), null)

        val path = "wolf/$name"
        val total = runCatching { context.assets.open(path).use { it.available().toLong() } }.getOrNull()
            ?: return response(404, "Not Found", "text/plain", emptyMap(), null)  // например, TV-сборка без роликов
        val mime = when (name.substringAfterLast('.')) { "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "webp" -> "image/webp"; "ogg" -> "audio/ogg"; else -> "image/jpeg" }
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*")

        val m = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value?.let { RANGE.matchEntire(it.trim()) }
        if (m == null) {
            headers["Content-Length"] = total.toString()
            return response(200, "OK", mime, headers, context.assets.open(path))
        }
        val (a, b) = m.destructured
        val start = if (a.isEmpty()) (total - (b.toLongOrNull() ?: 0L)).coerceAtLeast(0) else a.toLong()
        val end = if (a.isEmpty() || b.isEmpty()) total - 1 else minOf(b.toLong(), total - 1)
        if (start > end || start >= total) {
            headers["Content-Range"] = "bytes */$total"
            return response(416, "Range Not Satisfiable", mime, headers, null)
        }
        headers["Content-Range"] = "bytes $start-$end/$total"
        headers["Content-Length"] = (end - start + 1).toString()
        val stream = context.assets.open(path)
        var skipped = 0L
        while (skipped < start) {
            val n = stream.skip(start - skipped)
            if (n <= 0) break
            skipped += n
        }
        return response(206, "Partial Content", mime, headers, Limited(stream, end - start + 1))
    }

    private fun response(code: Int, reason: String, mime: String, headers: Map<String, String>, body: InputStream?) =
        WebResourceResponse(mime, null, code, reason, headers, body ?: "".byteInputStream())

    /** Отдаёт не больше [left] байт и закрывает файл вместе с потоком. */
    private class Limited(src: InputStream, private var left: Long) : FilterInputStream(src) {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = super.read()
            if (r >= 0) left--
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }
}
