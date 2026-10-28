package dev.chandra.grabbit.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ActivityLibraryBinding
import dev.chandra.grabbit.databinding.ItemLibraryBinding
import dev.chandra.grabbit.databinding.ItemLibraryHeaderBinding
import dev.chandra.grabbit.download.DownloadService
import dev.chandra.grabbit.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** All videos / music on the phone, grouped by folder, playable with the built-in player. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var b: ActivityLibraryBinding
    private var showVideos = true
    private val adapter = Adapter()

    data class Entry(val uri: Uri, val title: String, val folder: String, val durationMs: Long, val size: Long, val mime: String, val dateAdded: Long)
    private sealed class Row { data class Header(val folder: String, val count: Int) : Row(); data class Media(val e: Entry) : Row() }

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.tabs.addOnButtonCheckedListener { _, id, checked -> if (checked) { showVideos = id == R.id.tabVideos; load() } }
        b.btnGrant.setOnClickListener { askPermission.launch(neededPermissions()) }
        load()
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasPermission(): Boolean = neededPermissions().any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun load() {
        if (!hasPermission()) {
            b.permissionBox.visibility = View.VISIBLE; b.empty.visibility = View.GONE; adapter.submit(emptyList())
            if (!askedOnce) { askedOnce = true; askPermission.launch(neededPermissions()) }
            return
        }
        b.permissionBox.visibility = View.GONE
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) { query(showVideos) }
            val rows = ArrayList<Row>()
            entries.groupBy { it.folder }.toSortedMap(compareBy { it.lowercase() }).forEach { (folder, list) ->
                rows.add(Row.Header(folder, list.size))
                list.sortedByDescending { it.dateAdded }.forEach { rows.add(Row.Media(it)) }
            }
            adapter.submit(rows)
            b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    private var askedOnce = false

    private fun query(videos: Boolean): List<Entry> {
        val collection = if (videos) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val idCol = MediaStore.MediaColumns._ID
        val projection = arrayOf(idCol, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATA)
        val out = ArrayList<Entry>()
        val selection = if (videos) null else "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.MediaColumns.DURATION} > 30000"
        contentResolver.query(collection, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(idCol); val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION); val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE); val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iData = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.getString(iData) ?: ""
                val folder = File(path).parentFile?.name?.ifBlank { null } ?: "Other"
                out.add(Entry(ContentUris.withAppendedId(collection, id), c.getString(iName) ?: "Untitled", folder, c.getLong(iDur), c.getLong(iSize), c.getString(iMime) ?: (if (videos) "video/*" else "audio/*"), c.getLong(iDate)))
            }
        }
        return out
    }

    private fun play(e: Entry) {
        val all = adapter.rows.filterIsInstance<Row.Media>().map { it.e }
        val sameFolder = all.filter { it.folder == e.folder }
        val index = sameFolder.indexOfFirst { it.uri == e.uri }.coerceAtLeast(0)
        PlayerActivity.start(this, sameFolder.map { it.uri.toString() }, sameFolder.map { it.title.substringBeforeLast('.') }, sameFolder.map { it.mime }, index)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
        fun submit(r: List<Row>) { rows = r; notifyDataSetChanged() }
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) object : RecyclerView.ViewHolder(ItemLibraryHeaderBinding.inflate(inf, parent, false).root) {}
            else object : RecyclerView.ViewHolder(ItemLibraryBinding.inflate(inf, parent, false).root) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> ItemLibraryHeaderBinding.bind(holder.itemView).apply { header.text = row.folder; count.text = "${row.count}" }
                is Row.Media -> ItemLibraryBinding.bind(holder.itemView).apply {
                    val e = row.e
                    title.text = e.title
                    subtitle.text = listOf(DownloadService.fmt(e.size), e.mime.substringAfter('/').uppercase()).joinToString("  ·  ")
                    duration.visibility = if (e.durationMs > 0) View.VISIBLE else View.GONE
                    duration.text = MediaSheet.fmtDuration(e.durationMs / 1000.0)
                    thumb.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    thumb.setColorFilter(getColor(if (showVideos) R.color.video else R.color.audio))
                    if (showVideos) Thumbs.loadLocal(this@LibraryActivity, thumb, e.uri.toString(), R.drawable.ic_video) else { thumb.tag = null; thumb.setImageResource(R.drawable.ic_audio) }
                    root.setOnClickListener { play(e) }
                }
            }
        }
    }
}
