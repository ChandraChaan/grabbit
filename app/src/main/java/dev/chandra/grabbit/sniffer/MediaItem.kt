package dev.chandra.grabbit.sniffer

enum class MediaType { VIDEO, AUDIO, IMAGE }

/** One downloadable thing found on the current page. Keyed by [url] without fragment. */
data class MediaItem(
    val url: String,
    val type: MediaType,
    val pageUrl: String,
    val pageTitle: String,
    val mime: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Double = 0.0,
    val isHls: Boolean = false,
    /** Human label such as "720p", "audio only", "YouTube video (no audio)". */
    val label: String? = null,
    /** Content-Length when known (filled lazily by a HEAD request). -1 = unknown. */
    val sizeBytes: Long = -1,
    val source: String = "page",
    /** Poster frame or the image itself, for the sheet thumbnail. */
    val thumbUrl: String? = null,
    /** Separate audio track to merge into this video-only stream (YouTube DASH). */
    val audioUrl: String? = null,
    /** Items sharing a group are shown as one row with a quality picker. */
    val groupKey: String? = null,
    /** Container hint: "mp4" or "webm". */
    val container: String? = null,
) {
    val group: String get() = groupKey ?: key
    /** Higher is better; used to order quality choices and pick the default. */
    val qualityRank: Int get() = height * 10 + (if (audioUrl == null && label?.contains("no audio") != true) 5 else 0)

    val key: String get() = url.substringBefore('#')

    val extension: String
        get() = UrlSniffer.extensionOf(url, mime, type, isHls)

    val suggestedFileName: String
        get() {
            // Images usually carry a meaningful file name in the URL; so does a media file opened directly.
            val last = runCatching { android.net.Uri.parse(url).lastPathSegment }.getOrNull()
            val looksLikeFile = last != null && last.contains('.') && last.length in 5..100 && !last.startsWith("getuid") &&
                last.substringAfterLast('.').length in 2..4
            val pageIsTheFile = pageUrl.substringBefore('?') == url.substringBefore('?') || pageTitle.endsWith(last ?: "\u0000")
            if (looksLikeFile && (type == MediaType.IMAGE || pageIsTheFile)) return last!!
            val base = pageTitle.ifBlank { pageUrl.substringAfter("://").substringBefore('/') }
                .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
                .replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "grabbit" }
            val tag = label?.substringBefore('·')?.trim()?.takeIf { it.isNotBlank() && !it.contains("(") && !it.startsWith("Audio") }
            val suffix = tag?.let { " [$it]" } ?: ""
            return "$base$suffix.$extension"
        }
}
