package dev.chandra.grabbit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ActivityDownloadsBinding
import dev.chandra.grabbit.databinding.ItemDownloadBinding
import dev.chandra.grabbit.download.DownloadRecord
import dev.chandra.grabbit.download.DownloadService
import dev.chandra.grabbit.download.DownloadStatus
import dev.chandra.grabbit.download.DownloadStore

class DownloadsActivity : AppCompatActivity() {
    private lateinit var b: ActivityDownloadsBinding
    private val adapter = Adapter()
    private val listener: (List<DownloadRecord>) -> Unit = { list ->
        adapter.submit(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toolbar.inflateMenu(R.menu.menu_downloads)
        b.toolbar.setOnMenuItemClickListener { if (it.itemId == R.id.action_clear) { DownloadStore.clearFinished(); true } else false }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
    }

    override fun onStart() { super.onStart(); DownloadStore.addListener(listener) }
    override fun onStop() { super.onStop(); DownloadStore.removeListener(listener) }

    private fun open(r: DownloadRecord) {
        val uri = r.savedUri?.let { Uri.parse(it) } ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(Intent.createChooser(i, getString(R.string.open))) }
            .onFailure { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(r: DownloadRecord) {
        AlertDialog.Builder(this).setTitle(R.string.delete).setMessage(r.fileName)
            .setPositiveButton(R.string.delete) { _, _ ->
                r.savedUri?.let { runCatching { contentResolver.delete(Uri.parse(it), null, null) } }
                DownloadStore.remove(r.id)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private var data: List<DownloadRecord> = emptyList()
        fun submit(l: List<DownloadRecord>) { data = l; notifyDataSetChanged() }
        override fun getItemCount() = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
    }

    private inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(r: DownloadRecord) {
            b.title.text = r.fileName
            val mime = r.mime
            val icon = when { mime.startsWith("audio") -> R.drawable.ic_audio; mime.startsWith("image") -> R.drawable.ic_image; else -> R.drawable.ic_video }
            b.icon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            b.icon.setColorFilter(getColor(when { mime.startsWith("audio") -> R.color.audio; mime.startsWith("image") -> R.color.image; else -> R.color.video }))
            if (r.status == DownloadStatus.DONE && !mime.startsWith("audio")) Thumbs.loadLocal(this@DownloadsActivity, b.icon, r.savedUri, icon) else { b.icon.tag = null; b.icon.setImageResource(icon) }
            val pct = r.progressPercent
            when (r.status) {
                DownloadStatus.QUEUED -> { b.status.text = "Queued"; b.bar.visibility = View.VISIBLE; b.bar.isIndeterminate = true }
                DownloadStatus.RUNNING -> {
                    b.bar.visibility = View.VISIBLE; b.bar.isIndeterminate = pct < 0
                    if (pct >= 0) b.bar.progress = pct
                    b.status.text = if (r.isHls) (if (pct >= 0) "Downloading stream · $pct%" else "Downloading stream…") else "${DownloadService.fmt(r.bytesDone)}" + (if (r.bytesTotal > 0) " / ${DownloadService.fmt(r.bytesTotal)} · $pct%" else "")
                }
                DownloadStatus.DONE -> { b.bar.visibility = View.GONE; b.status.text = "Saved · ${DownloadService.fmt(r.bytesTotal)} · Download/Grabbit" }
                DownloadStatus.FAILED -> { b.bar.visibility = View.GONE; b.status.text = "Failed" + (r.error?.let { ": $it" } ?: "") }
                DownloadStatus.CANCELLED -> { b.bar.visibility = View.GONE; b.status.text = "Cancelled" }
            }
            val active = r.status == DownloadStatus.RUNNING || r.status == DownloadStatus.QUEUED
            b.btnAction.setImageResource(if (active) R.drawable.ic_close else R.drawable.ic_delete)
            b.btnAction.contentDescription = getString(if (active) R.string.cancel else R.string.delete)
            b.btnAction.setOnClickListener { if (active) DownloadService.cancel(this@DownloadsActivity, r.id) else confirmDelete(r) }
            b.root.setOnClickListener {
                when (r.status) {
                    DownloadStatus.DONE -> open(r)
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        DownloadStore.remove(r.id)
                        DownloadService.enqueue(this@DownloadsActivity, r.copy(id = System.currentTimeMillis(), status = DownloadStatus.QUEUED, bytesDone = 0, error = null, savedUri = null))
                    }
                    else -> {}
                }
            }
        }
    }
}
