package dev.chandra.grabbit.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ItemMediaBinding
import dev.chandra.grabbit.databinding.SheetMediaBinding
import dev.chandra.grabbit.databinding.SheetQualityBinding
import dev.chandra.grabbit.databinding.ItemQualityBinding
import dev.chandra.grabbit.extract.YouTubeExtractor
import dev.chandra.grabbit.download.DownloadRecord
import dev.chandra.grabbit.download.DownloadService
import dev.chandra.grabbit.download.Hls
import dev.chandra.grabbit.download.Http
import dev.chandra.grabbit.sniffer.MediaItem
import dev.chandra.grabbit.sniffer.MediaRegistry
import dev.chandra.grabbit.sniffer.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom sheet listing everything the sniffer found on the current page, grouped by type. */
class MediaSheet(private val activity: MainActivity, private val registry: MediaRegistry) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter = Adapter()
    private val probed = HashSet<String>()
    private var dialog: BottomSheetDialog? = null
    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        val b = SheetMediaBinding.inflate(LayoutInflater.from(activity))
        val d = BottomSheetDialog(activity)
        d.setContentView(b.root)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = adapter
        val listener: (List<MediaItem>) -> Unit = { items ->
            adapter.submit(items)
            b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.count.text = if (items.isEmpty()) "" else "${items.size}"
            probe(items)
        }
        registry.addListener(listener)
        listener(registry.snapshot())
        d.setOnDismissListener { registry.removeListener(listener); scope.cancel() }
        dialog = d
        d.show()
        maybeExtractYouTube(b)
    }

    /** YouTube pages: replace the sniffed chunk streams with real quality options from the extractor. */
    private fun maybeExtractYouTube(b: SheetMediaBinding) {
        val page = registry.pageUrl
        if (!YouTubeExtractor.isYouTubeUrl(page)) return
        val id = YouTubeExtractor.videoId(page) ?: return
        if (registry.snapshot().any { it.groupKey == "yt:$id" }) return
        b.loading.visibility = View.VISIBLE
        b.loadingSpinner.visibility = View.VISIBLE
        b.loadingText.text = activity.getString(R.string.fetching_qualities)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { YouTubeExtractor.extract(page) } }
            result.onSuccess { r ->
                registry.pageTitle = r.title
                registry.removeGroup("yt-sniffed")
                r.items.forEach { probed.add(it.key); registry.offer(it) }
                b.loading.visibility = View.GONE
                // Sizes for the picker.
                r.items.forEach { item -> probed.remove(item.key) }
                probe(r.items)
            }.onFailure { e ->
                b.loadingSpinner.visibility = View.GONE
                b.loadingText.text = activity.getString(R.string.extract_failed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Fetch size/mime for new items, drop non-media, and expand HLS master playlists into quality variants. */
    private fun probe(items: List<MediaItem>) {
        for (item in items) {
            if (!probed.add(item.key)) continue
            scope.launch(Dispatchers.IO) {
                if (item.isHls) { expandHls(item); return@launch }
                val head = Http.head(item.url, item.pageUrl, activity.userAgent)
                if (head == null) { if (item.type == MediaType.IMAGE) registry.remove(item.key); return@launch }
                val ct = head.contentType?.substringBefore(';')?.trim() ?: ""
                // Tracking pixels, HTML pages and placeholders are not media worth saving.
                if (ct.startsWith("text/") || ct.startsWith("application/json")) { registry.remove(item.key); return@launch }
                if (item.type == MediaType.IMAGE && head.contentLength in 0 until 10_000) { registry.remove(item.key); return@launch }
                val fixedType = when {
                    ct.startsWith("audio/") -> MediaType.AUDIO
                    ct.startsWith("image/") -> MediaType.IMAGE
                    ct.startsWith("video/") -> MediaType.VIDEO
                    else -> item.type
                }
                registry.update(item.key) { it.copy(sizeBytes = head.contentLength, mime = it.mime ?: ct.ifBlank { null }, type = fixedType) }
                if (item.label == "stream track") inspectSplitTrack(item)
            }
        }
    }

    /**
     * Instagram / Facebook serve video and audio as separate DASH tracks that both look like .mp4.
     * Peek at the first bytes to learn which is which, then pair each video with the audio that was
     * requested right after it so the download gets merged into one file with sound.
     */
    private fun inspectSplitTrack(item: MediaItem) {
        val tmp = java.io.File(activity.cacheDir, "probe-${item.key.hashCode()}.bin")
        try {
            val req = Http.request(item.url, item.pageUrl, activity.userAgent).newBuilder().header("Range", "bytes=0-786431").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return
                r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            val t = dev.chandra.grabbit.download.AvMuxer.inspect(path = tmp.absolutePath) ?: return
            when {
                t.hasVideo && !t.hasAudio -> registry.update(item.key) { it.copy(type = MediaType.VIDEO, label = activity.getString(R.string.label_video_only), width = t.width, height = t.height, container = "mp4") }
                !t.hasVideo && t.hasAudio -> registry.update(item.key) { it.copy(type = MediaType.AUDIO, label = activity.getString(R.string.label_audio_only), mime = "audio/mp4", container = "mp4") }
                t.hasVideo && t.hasAudio -> registry.update(item.key) { it.copy(label = null, width = t.width, height = t.height) }
            }
            pairSplitTracks()
        } catch (_: Exception) {
        } finally {
            tmp.delete()
        }
    }

    private fun pairSplitTracks() {
        val videoOnly = activity.getString(R.string.label_video_only)
        val audioOnly = activity.getString(R.string.label_audio_only)
        val all = registry.snapshot()
        val audios = all.filter { it.label == audioOnly }
        if (audios.isEmpty()) return
        for (v in all.filter { it.label == videoOnly && it.audioUrl == null }) {
            val vi = registry.insertionIndex(v.key)
            val a = audios.minByOrNull { kotlin.math.abs(registry.insertionIndex(it.key) - vi) } ?: continue
            registry.update(v.key) { it.copy(audioUrl = a.url, label = activity.getString(R.string.label_paired), container = "mp4", groupKey = it.groupKey ?: it.key) }
        }
    }

    private fun expandHls(item: MediaItem) {
        runCatching {
            val text = Http.client.newCall(Http.request(item.url, item.pageUrl, activity.userAgent)).execute().use { it.body?.string() ?: "" }
            if (Hls.isMaster(text)) {
                val variants = Hls.parseMaster(text, item.url)
                if (variants.isNotEmpty()) {
                    val expanded = variants.map { v ->
                        item.copy(url = v.url, label = "HLS ${v.label}", height = v.height, isHls = true).also { probed.add(it.key) }
                    }
                    registry.replace(item.key, expanded)
                }
            } else {
                val media = Hls.parseMedia(text, item.url)
                registry.update(item.key) { it.copy(label = it.label ?: "HLS stream", durationSec = media.durationSec) }
            }
        }
    }

    private sealed class Row {
        data class Header(val title: String, val count: Int) : Row()
        /** One row per group; [item] is the best variant, [variants] all of them (best first). */
        data class Media(val item: MediaItem, val variants: List<MediaItem>) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(items: List<MediaItem>) {
            val out = ArrayList<Row>()
            for ((type, title) in listOf(MediaType.VIDEO to R.string.section_videos, MediaType.AUDIO to R.string.section_audio, MediaType.IMAGE to R.string.section_images)) {
                val ofType = items.filter { it.type == type }
                if (ofType.isEmpty()) continue
                val groups = ofType.groupBy { it.group }.values.map { g -> g.sortedByDescending { it.qualityRank } }
                out.add(Row.Header(activity.getString(title), groups.size))
                groups.forEach { out.add(Row.Media(it.first(), it)) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_media_header, parent, false) as TextView)
            else VH(ItemMediaBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).view.text = "${row.title}  ·  ${row.count}"
                is Row.Media -> (holder as VH).bind(row.item, row.variants)
            }
        }
    }

    private class HeaderVH(val view: TextView) : RecyclerView.ViewHolder(view)

    private inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem, variants: List<MediaItem>) {
            val (icon, color, typeName) = when (item.type) {
                MediaType.VIDEO -> Triple(R.drawable.ic_video, R.color.video, "Video")
                MediaType.AUDIO -> Triple(R.drawable.ic_audio, R.color.audio, "Audio")
                MediaType.IMAGE -> Triple(R.drawable.ic_image, R.color.image, "Image")
            }
            b.icon.scaleType = ImageView.ScaleType.FIT_CENTER
            b.icon.setColorFilter(activity.getColor(color))
            Thumbs.load(b.icon, item.thumbUrl, item.pageUrl, activity.userAgent, icon)
            b.title.text = item.suggestedFileName
            val parts = ArrayList<String>()
            parts.add(item.extension.uppercase())
            if (variants.size > 1) parts.add("${variants.size} qualities") else item.label?.let { parts.add(it) }
            if (item.width > 0 && item.height > 0 && item.label?.contains("p") != true) parts.add("${item.width}×${item.height}")
            if (item.sizeBytes > 0) parts.add(DownloadService.fmt(item.sizeBytes))
            b.subtitle.text = parts.joinToString("  ·  ")
            b.duration.visibility = if (item.durationSec > 0) View.VISIBLE else View.GONE
            b.duration.text = fmtDuration(item.durationSec)
            b.host.text = runCatching {
                val u = android.net.Uri.parse(item.url)
                val last = u.lastPathSegment?.takeIf { it.length in 1..48 }
                if (last != null) "${u.host}/…/$last" else u.host ?: ""
            }.getOrDefault("")
            b.btnPlay.visibility = if (item.type == MediaType.IMAGE) View.GONE else View.VISIBLE
            b.btnPlay.setOnClickListener {
                // Streams that need merging cannot be played directly; pick the best self-contained one.
                val playable = variants.firstOrNull { it.audioUrl == null } ?: item
                dev.chandra.grabbit.player.PlayerActivity.start(activity, listOf(playable.url), listOf(playable.suggestedFileName.substringBeforeLast('.')), listOf(playable.mime ?: if (playable.type == MediaType.AUDIO) "audio/*" else "video/*"), 0, playable.pageUrl, activity.userAgent)
            }
            b.btnDownload.setOnClickListener {
                it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
                if (variants.size > 1) showQualityPicker(item, variants) else enqueueChecked(item)
            }
            b.root.setOnLongClickListener {
                activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("url", item.url))
                Toast.makeText(activity, R.string.copy_link, Toast.LENGTH_SHORT).show(); true
            }
        }
    }

    /** Warn before saving a stream the site serves without sound. */
    private fun enqueueChecked(item: MediaItem) {
        val silent = item.type == MediaType.VIDEO && item.audioUrl == null && (item.label?.contains("no audio", true) == true)
        if (!silent) { enqueue(activity, item, activity.userAgent, item.suggestedFileName); return }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.no_audio_title).setMessage(R.string.no_audio_msg)
            .setPositiveButton(R.string.download_anyway) { _, _ -> enqueue(activity, item, activity.userAgent, item.suggestedFileName) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun showQualityPicker(rep: MediaItem, variants: List<MediaItem>) {
        val b = SheetQualityBinding.inflate(LayoutInflater.from(activity))
        val d = BottomSheetDialog(activity)
        d.setContentView(b.root)
        b.subtitle.text = rep.pageTitle.ifBlank { rep.suggestedFileName }
        b.note.visibility = if (variants.any { it.audioUrl != null }) View.VISIBLE else View.GONE
        b.list.layoutManager = LinearLayoutManager(activity)
        b.list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = variants.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
                object : RecyclerView.ViewHolder(ItemQualityBinding.inflate(LayoutInflater.from(parent.context), parent, false).root) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val v = variants[position]
                val vb = ItemQualityBinding.bind(holder.itemView)
                val q = v.label?.substringBefore('·')?.trim()?.replace("HLS ", "") ?: (if (v.height > 0) "${v.height}p" else v.extension.uppercase())
                vb.quality.text = q
                val rest = v.label?.substringAfter('·', "")?.trim().orEmpty()
                vb.label.text = (if (rest.isNotBlank()) rest else v.extension.uppercase()).replaceFirstChar { it.uppercase() }
                val detail = ArrayList<String>()
                detail.add(v.extension.uppercase())
                if (v.sizeBytes > 0) detail.add(DownloadService.fmt(v.sizeBytes))
                if (v.audioUrl != null) detail.add("video + audio merged")
                vb.detail.text = detail.joinToString("  ·  ")
                holder.itemView.setOnClickListener { enqueueChecked(v); d.dismiss() }
            }
        }
        d.show()
    }

    companion object {
        fun fmtDuration(sec: Double): String {
            val s = sec.toLong(); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
        }

        fun enqueue(context: Context, item: MediaItem, userAgent: String, fileName: String, force: Boolean = false) {
            val all = dev.chandra.grabbit.download.DownloadStore.all()
            val active = all.firstOrNull { it.url == item.url && (it.status == dev.chandra.grabbit.download.DownloadStatus.RUNNING || it.status == dev.chandra.grabbit.download.DownloadStatus.QUEUED) }
            if (active != null) { Toast.makeText(context, R.string.already_downloading, Toast.LENGTH_SHORT).show(); return }
            val done = if (force) null else all.firstOrNull { it.status == dev.chandra.grabbit.download.DownloadStatus.DONE && it.savedUri != null && (it.url == item.url || it.fileName == fileName) }
            if (done != null) {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle(R.string.already_downloaded)
                    .setMessage(context.getString(R.string.already_downloaded_msg, done.fileName, DownloadService.fmt(done.bytesTotal)))
                    .setPositiveButton(R.string.open) { _, _ ->
                        if (done.mime.startsWith("video") || done.mime.startsWith("audio")) dev.chandra.grabbit.player.PlayerActivity.start(context, listOf(done.savedUri!!), listOf(done.fileName.substringBeforeLast('.')), listOf(done.mime), 0)
                        else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(done.savedUri), done.mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    }
                    .setNeutralButton(R.string.download_again) { _, _ -> enqueue(context, item, userAgent, fileName, force = true) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
            val mime = item.mime?.substringBefore(';') ?: when (item.type) {
                MediaType.VIDEO -> "video/mp4" // HLS is remuxed to MP4; the service renames to .ts only if that fails
                MediaType.AUDIO -> "audio/mpeg"
                MediaType.IMAGE -> "image/jpeg"
            }
            val rec = DownloadRecord(
                id = System.currentTimeMillis(), url = item.url, fileName = fileName, mime = mime,
                referer = item.pageUrl, userAgent = userAgent, isHls = item.isHls,
                createdAt = System.currentTimeMillis(), pageTitle = item.pageTitle,
                audioUrl = item.audioUrl, container = item.container,
            )
            DownloadService.enqueue(context, rec)
            Toast.makeText(context, context.getString(R.string.download_queued, fileName), Toast.LENGTH_SHORT).show()
        }
    }
}
