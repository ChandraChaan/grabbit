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
) {
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
            val suffix = label?.takeIf { it.isNotBlank() && !it.contains("(") }?.let { " [$it]" } ?: ""
            return "$base$suffix.$extension"
        }
}
