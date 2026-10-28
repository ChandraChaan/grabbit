package dev.chandra.grabbit.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import kotlinx.coroutines.withContext

/** Bottom sheet listing everything the sniffer found on the current page. */
class MediaSheet(private val activity: MainActivity, private val registry: MediaRegistry) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adapter = Adapter()
    private val probed = HashSet<String>()

    fun show() {
        val b = SheetMediaBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)
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
        dialog.setOnDismissListener { registry.removeListener(listener); scope.cancel() }
        dialog.show()
    }

    /** Fetch size/mime for new items and expand HLS master playlists into their quality variants. */
    private fun probe(items: List<MediaItem>) {
        for (item in items) {
            if (!probed.add(item.key)) continue
            scope.launch(Dispatchers.IO) {
                if (item.isHls) expandHls(item) else {
                    val head = Http.head(item.url, item.pageUrl, activity.userAgent) ?: return@launch
                    registry.update(item.key) { it.copy(sizeBytes = head.contentLength, mime = it.mime ?: head.contentType?.substringBefore(';')) }
                }
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

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private var data: List<MediaItem> = emptyList()
        fun submit(items: List<MediaItem>) { data = items; notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
    }

    private inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem) {
            val (icon, color, typeName) = when (item.type) {
                MediaType.VIDEO -> Triple(R.drawable.ic_video, R.color.video, "Video")
                MediaType.AUDIO -> Triple(R.drawable.ic_audio, R.color.audio, "Audio")
                MediaType.IMAGE -> Triple(R.drawable.ic_image, R.color.image, "Image")
            }
            b.icon.setImageResource(icon)
            b.icon.setColorFilter(activity.getColor(color))
            b.title.text = item.suggestedFileName
            val parts = ArrayList<String>()
            parts.add(typeName + " · " + item.extension.uppercase())
            item.label?.let { parts.add(it) }
            if (item.width > 0 && item.height > 0 && item.label?.contains("p") != true) parts.add("${item.width}×${item.height}")
            if (item.durationSec > 0) parts.add(fmtDuration(item.durationSec))
            if (item.sizeBytes > 0) parts.add(DownloadService.fmt(item.sizeBytes))
            b.subtitle.text = parts.joinToString("  ·  ")
            b.host.text = runCatching { android.net.Uri.parse(item.url).host }.getOrNull() ?: ""
            b.btnDownload.setOnClickListener { enqueue(activity, item, activity.userAgent, item.suggestedFileName) }
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
