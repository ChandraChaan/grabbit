package dev.chandra.grabbit.download

import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Builds a request that looks like it came from the page: same cookies, referer and UA. */
    fun request(url: String, referer: String?, userAgent: String?, rangeStart: Long = -1): Request {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent ?: DEFAULT_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (!referer.isNullOrBlank()) {
            b.header("Referer", referer)
            runCatching { val u = java.net.URL(referer); b.header("Origin", "${u.protocol}://${u.host}") }
        }
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.let { if (it.isNotBlank()) b.header("Cookie", it) }
        if (rangeStart > 0) b.header("Range", "bytes=$rangeStart-")
        return b.build()
    }

    data class Head(val contentLength: Long, val contentType: String?)

    fun head(url: String, referer: String?, userAgent: String?): Head? = runCatching {
        // Many CDNs reject HEAD; do a ranged GET and close immediately.
        val req = request(url, referer, userAgent).newBuilder().header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) { android.util.Log.w("Grabbit", "probe ${r.code} for ${url.take(120)} req-headers=${req.headers.names()}"); return null }
            val type = r.header("Content-Type")
            val len = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code != 206 } ?: -1L
            Head(len, type)
        }
    }.getOrNull()
}
