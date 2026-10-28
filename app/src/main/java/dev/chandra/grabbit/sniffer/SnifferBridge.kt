package dev.chandra.grabbit.sniffer

import android.webkit.JavascriptInterface
import org.json.JSONObject

/** Receives reports from assets/sniffer.js. Called on a WebView background thread. */
class SnifferBridge(private val registry: MediaRegistry, private val context: android.content.Context? = null) {
    private val blobs = HashMap<String, dev.chandra.grabbit.download.MediaSink>()
    private val blobBytes = HashMap<String, Long>()

    /** Receives a blob: URL the page offered for download, in base64 chunks, and saves it like any other file. */
    @JavascriptInterface
    fun onBlob(id: String, name: String, mime: String, chunk: String, index: Int, total: Int, error: String?) {
        val ctx = context ?: return
        try {
            if (!error.isNullOrBlank()) { android.util.Log.w("Grabbit", "blob $id failed: $error"); blobs.remove(id)?.abort(); return }
            if (index == 0) android.util.Log.i("Grabbit", "blob $id start name=$name mime=$mime chunks=$total")
            val sink = blobs.getOrPut(id) { dev.chandra.grabbit.download.MediaSink(ctx, name, mime.ifBlank { "application/octet-stream" }) }
            if (chunk.isNotEmpty()) {
                val bytes = android.util.Base64.decode(chunk, android.util.Base64.DEFAULT)
                sink.writeSequential(bytes, bytes.size)
                blobBytes[id] = (blobBytes[id] ?: 0L) + bytes.size
            }
            if (index >= total - 1) {
                sink.finish()
                blobs.remove(id)
                val size = blobBytes.remove(id) ?: 0L
                dev.chandra.grabbit.download.DownloadStore.add(
                    dev.chandra.grabbit.download.DownloadRecord(
                        id = System.currentTimeMillis(), url = "blob:$id", fileName = sink.displayName, mime = mime, referer = registry.pageUrl,
                        userAgent = "", isHls = false, createdAt = System.currentTimeMillis(),
                        status = dev.chandra.grabbit.download.DownloadStatus.DONE, bytesDone = size, bytesTotal = size, savedUri = sink.uri.toString(), pageTitle = registry.pageTitle,
                    )
                )
                dev.chandra.grabbit.download.DownloadStore.flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("Grabbit", "blob $id write failed", e)
            blobs.remove(id)?.abort()
        }
    }

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
                thumbUrl = if (type == MediaType.IMAGE) url else o.optString("poster").takeIf { it.startsWith("http") },
            )
            registry.offer(item)
        } catch (_: Exception) { }
    }
}
