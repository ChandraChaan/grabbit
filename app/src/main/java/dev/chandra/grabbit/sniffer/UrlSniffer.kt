package dev.chandra.grabbit.sniffer

import android.net.Uri
import android.webkit.WebResourceRequest
import java.util.Locale

/**
 * Pure URL heuristics: decides whether a network request the WebView makes looks like media,
 * and what to call it. Runs on the WebView's IO thread, so it must be cheap and side-effect free.
 */
object UrlSniffer {
    private val videoExt = setOf("mp4", "m4v", "webm", "mkv", "mov", "flv", "3gp", "avi", "mpeg", "mpg", "ogv")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba")
    private val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
    private val hlsExt = setOf("m3u8", "m3u")

    /** Hosts whose media URLs we never want to treat as downloads (ads/trackers/segments). */
    private val ignoredHostParts = listOf("doubleclick", "googlesyndication", "google-analytics", "adservice")

    fun classify(request: WebResourceRequest): MediaItem? = classify(request.url, request.requestHeaders["Accept"])

    fun classify(uri: Uri, accept: String? = null): MediaItem? {
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (ignoredHostParts.any { host.contains(it) }) return null
        val scheme = uri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val path = uri.path ?: ""
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)

        // YouTube / googlevideo progressive and DASH streams carry mime= and itag= in the query.
        if (host.endsWith("googlevideo.com") && path.contains("videoplayback")) {
            return youtubeItem(uri)
        }

        val type = when {
            ext in hlsExt -> MediaType.VIDEO
            ext in videoExt -> MediaType.VIDEO
            ext in audioExt -> MediaType.AUDIO
            ext in imageExt -> MediaType.IMAGE
            else -> null
        }
        if (type == null) {
            // Some CDNs hide the extension; use mime hints in the query string.
            val mimeParam = uri.getQueryParameter("mime") ?: uri.getQueryParameter("type") ?: return null
            return when {
                mimeParam.startsWith("video/") -> MediaItem(uri.toString(), MediaType.VIDEO, "", "", mime = mimeParam, source = "network")
                mimeParam.startsWith("audio/") -> MediaItem(uri.toString(), MediaType.AUDIO, "", "", mime = mimeParam, source = "network")
                else -> null
            }
        }
        // Images are everywhere (icons, trackers, thumbnails); the JS sniffer reports images with a
        // real rendered size instead, so the network layer only tracks video/audio.
        if (type == MediaType.IMAGE) return null
        // HLS segment .ts files are not useful on their own; the playlist is.
        return MediaItem(uri.toString(), type, "", "", isHls = ext in hlsExt, source = "network")
    }


    private fun youtubeItem(uri: Uri): MediaItem {
        val mime = uri.getQueryParameter("mime") ?: "video/mp4"
        val itag = uri.getQueryParameter("itag")?.toIntOrNull()
        val isAudio = mime.startsWith("audio/")
        // Drop the range so we fetch the whole stream instead of the player's chunk.
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "range" || name == "rn" || name == "rbuf") continue
            uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
        }
        val label = when (itag) {
            18 -> "360p (video + audio)"
            22 -> "720p (video + audio)"
            else -> if (isAudio) "audio only" else {
                val h = heightForItag(itag)
                if (h > 0) "${h}p video only (no audio)" else "video only (no audio)"
            }
        }
        return MediaItem(
            url = builder.build().toString(),
            type = if (isAudio) MediaType.AUDIO else MediaType.VIDEO,
            pageUrl = "", pageTitle = "", mime = mime, label = label, source = "youtube"
        )
    }

    private fun heightForItag(itag: Int?): Int = when (itag) {
        133, 160, 242, 278, 394, 395 -> 240
        134, 243, 396 -> 360
        135, 244, 397 -> 480
        136, 247, 298, 302, 398 -> 720
        137, 248, 299, 303, 399 -> 1080
        264, 271, 308, 400 -> 1440
        266, 313, 315, 401 -> 2160
        else -> 0
    }

    fun extensionOf(url: String, mime: String?, type: MediaType, isHls: Boolean): String {
        if (isHls) return "ts"
        val fromMime = when (mime?.substringBefore(';')?.trim()) {
            "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"; "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/webm" -> "webm"; "audio/ogg" -> "ogg"; "audio/wav" -> "wav"
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
            else -> null
        }
        if (fromMime != null) return fromMime
        val path = runCatching { Uri.parse(url).path ?: "" }.getOrDefault("")
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.length in 2..5 && (ext in videoExt || ext in audioExt || ext in imageExt)) return ext
        return when (type) { MediaType.VIDEO -> "mp4"; MediaType.AUDIO -> "mp3"; MediaType.IMAGE -> "jpg" }
    }
}
