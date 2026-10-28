package dev.chandra.grabbit.extract

import android.net.Uri
import dev.chandra.grabbit.sniffer.MediaItem
import dev.chandra.grabbit.sniffer.MediaType
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Resolves a YouTube watch / shorts / youtu.be URL into downloadable qualities using NewPipeExtractor:
 * muxed streams (video+audio), DASH video-only streams paired with the best matching audio track for
 * merging, and audio-only streams.
 */
object YouTubeExtractor {
    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(NewPipeDownloader(), Localization("en", "US"), ContentCountry("US"))
            initialized = true
        }
    }

    fun isYouTubeUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        if (!(host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com"))) return false
        return videoId(url) != null
    }

    fun videoId(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        u.getQueryParameter("v")?.takeIf { it.length == 11 }?.let { return it }
        val segs = u.pathSegments
        val i = segs.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
        if (i >= 0 && segs.size > i + 1 && segs[i + 1].length == 11) return segs[i + 1]
        if (u.host == "youtu.be" && segs.isNotEmpty() && segs[0].length == 11) return segs[0]
        return null
    }

    data class Result(val title: String, val thumb: String?, val items: List<MediaItem>)

    /** Network call: run on IO. Throws on failure. */
    fun extract(pageUrl: String): Result {
        ensureInit()
        val id = videoId(pageUrl) ?: throw IllegalArgumentException("Not a YouTube video URL")
        val canonical = "https://www.youtube.com/watch?v=$id"
        val info = StreamInfo.getInfo(ServiceList.YouTube, canonical)
        val title = info.name.ifBlank { "YouTube $id" }
        val thumb = info.thumbnails.maxByOrNull { it.width }?.url
        val group = "yt:$id"
        val out = ArrayList<MediaItem>()
        val dur = info.duration.toDouble()

        fun item(url: String, type: MediaType, mime: String, label: String, height: Int, container: String, audio: String? = null) =
            MediaItem(url = url, type = type, pageUrl = pageUrl, pageTitle = title, mime = mime, height = height, width = 0,
                durationSec = dur, label = label, source = "youtube", thumbUrl = thumb, audioUrl = audio, groupKey = group, container = container)

        // 1) Progressive streams: video + audio in one file. Simple and always playable.
        for (v in info.videoStreams.filterNotNull()) {
            val url = v.content ?: continue
            val h = heightOf(v)
            val container = containerOf(v.format?.suffix)
            out.add(item(url, MediaType.VIDEO, mimeOf(container, true), "${h}p · video + audio", h, container))
        }
        // 2) DASH video-only streams + best audio of the same container, merged on device after download.
        val audios = info.audioStreams.filterNotNull().filter { it.content != null }
        val bestM4a = audios.filter { it.format?.suffix == "m4a" }.maxByOrNull { it.averageBitrate }
        val bestWebm = audios.filter { it.format?.suffix == "webm" }.maxByOrNull { it.averageBitrate }
        // Per resolution keep one stream, preferring codecs the phone's muxer handles: H.264 (mp4) first,
        // then VP9 (webm), and AV1 only when nothing else exists at that height.
        val byHeight = info.videoOnlyStreams.filterNotNull().filter { it.content != null }.groupBy { heightOf(it) }
        for ((h, streams) in byHeight.entries.sortedByDescending { it.key }) {
            if (h <= 0) continue
            val pick = streams.minByOrNull { codecRank(it) } ?: continue
            val container = containerOf(pick.format?.suffix)
            val audio = (if (container == "webm") bestWebm else bestM4a) ?: continue
            val codec = codecName(pick)
            val note = if (codec == "av1") " · AV1 (may not play on older phones)" else ""
            out.add(item(pick.content!!, MediaType.VIDEO, mimeOf(container, true), "${h}p · ${container.uppercase()}$note · merged with audio", h, container, audio.content))
        }
        // 3) Audio only.
        for (a in audios.sortedByDescending { it.averageBitrate }) {
            val container = containerOf(a.format?.suffix)
            out.add(item(a.content!!, MediaType.AUDIO, mimeOf(container, false), "Audio · ${a.averageBitrate} kbps · ${container.uppercase()}", 0, container))
        }
        // Dedupe by URL, drop mp4-less duplicates of the same label.
        val dedup = LinkedHashMap<String, MediaItem>()
        for (i in out) dedup.putIfAbsent(i.key, i)
        return Result(title, thumb, dedup.values.toList())
    }

    private fun codecName(v: VideoStream): String {
        val c = (runCatching { v.codec }.getOrNull() ?: "").lowercase()
        val itag = v.itag
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> "avc"
            c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
            c.startsWith("av01") || c.startsWith("av1") -> "av1"
            itag in 394..399 || itag in 571..572 -> "av1"
            v.format?.suffix == "webm" -> "vp9"
            else -> "avc"
        }
    }

    private fun codecRank(v: VideoStream): Int = when (codecName(v)) { "avc" -> 0; "vp9" -> 1; else -> 2 }

    private fun heightOf(v: VideoStream): Int {
        v.height.takeIf { it > 0 }?.let { return it }
        return v.resolution?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }

    private fun containerOf(suffix: String?): String = when (suffix) { "webm", "opus" -> "webm"; else -> "mp4" }

    private fun mimeOf(container: String, video: Boolean) = when {
        container == "webm" && video -> "video/webm"
        container == "webm" -> "audio/webm"
        video -> "video/mp4"
        else -> "audio/mp4"
    }
}
