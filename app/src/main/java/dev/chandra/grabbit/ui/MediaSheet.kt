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
            }
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
        data class Media(val item: MediaItem) : Row()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(items: List<MediaItem>) {
            val out = ArrayList<Row>()
            for ((type, title) in listOf(MediaType.VIDEO to R.string.section_videos, MediaType.AUDIO to R.string.section_audio, MediaType.IMAGE to R.string.section_images)) {
                val group = items.filter { it.type == type }
                if (group.isEmpty()) continue
                out.add(Row.Header(activity.getString(title), group.size))
                group.forEach { out.add(Row.Media(it)) }
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
                is Row.Media -> (holder as VH).bind(row.item)
            }
        }
    }

    private class HeaderVH(val view: TextView) : RecyclerView.ViewHolder(view)

    private inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem) {
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
            item.label?.let { parts.add(it) }
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
                dev.chandra.grabbit.player.PlayerActivity.start(activity, listOf(item.url), listOf(item.suggestedFileName.substringBeforeLast('.')), listOf(item.mime ?: if (item.type == MediaType.AUDIO) "audio/*" else "video/*"), 0, item.pageUrl, activity.userAgent)
            }
            b.btnDownload.setOnClickListener {
                enqueue(activity, item, activity.userAgent, item.suggestedFileName)
                it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
            }
            b.root.setOnLongClickListener {
                activity.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("url", item.url))
                Toast.makeText(activity, R.string.copy_link, Toast.LENGTH_SHORT).show(); true
            }
        }
    }

    companion object {
        fun fmtDuration(sec: Double): String {
            val s = sec.toLong(); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
        }

        fun enqueue(context: Context, item: MediaItem, userAgent: String, fileName: String) {
            val mime = item.mime?.substringBefore(';') ?: when (item.type) {
                MediaType.VIDEO -> if (item.isHls) "video/mp2t" else "video/mp4"
                MediaType.AUDIO -> "audio/mpeg"
                MediaType.IMAGE -> "image/jpeg"
            }
            val rec = DownloadRecord(
                id = System.currentTimeMillis(), url = item.url, fileName = fileName, mime = mime,
                referer = item.pageUrl, userAgent = userAgent, isHls = item.isHls,
                createdAt = System.currentTimeMillis(), pageTitle = item.pageTitle,
            )
            DownloadService.enqueue(context, rec)
            Toast.makeText(context, context.getString(R.string.download_queued, fileName), Toast.LENGTH_SHORT).show()
        }
    }
}
