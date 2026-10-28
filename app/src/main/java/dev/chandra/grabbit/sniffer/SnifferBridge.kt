package dev.chandra.grabbit.sniffer

import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Receives reports from assets/sniffer.js. Called on a WebView background thread. */
class SnifferBridge(private val registry: MediaRegistry) {
    @JavascriptInterface
    fun onMedia(json: String) {
        try {
            val o = JSONObject(json)
            val url = o.optString("url")
            if (url.isBlank()) return
            val page = o.optString("page")
            if (url.substringBefore('#') == page.substringBefore('#')) return // element with an empty src resolves to the page itself
            val type = when (o.optString("type")) {
                "audio" -> MediaType.AUDIO
                "image" -> MediaType.IMAGE
                else -> MediaType.VIDEO
            }
            // Let the URL heuristics refine (e.g. detects m3u8, googlevideo labels).
            val sniffed = UrlSniffer.classify(android.net.Uri.parse(url))
            val item = MediaItem(
                url = sniffed?.url ?: url,
                type = sniffed?.type ?: type,
                pageUrl = o.optString("page"),
                pageTitle = o.optString("title"),
                mime = sniffed?.mime,
                width = o.optInt("width"),
                height = o.optInt("height"),
                durationSec = o.optDouble("duration", 0.0),
                isHls = sniffed?.isHls ?: url.contains(".m3u8"),
                label = sniffed?.label,
                source = "page",
            )
            registry.offer(item)
        } catch (_: Exception) { }
    }
}
