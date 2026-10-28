package dev.chandra.grabbit.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/** Writes a finished file into the public Download/Grabbit folder, via MediaStore on Android 10+. */
class MediaSink(private val context: Context, fileName: String, private val mime: String) {
    val uri: Uri
    private val file: File?
    val stream: OutputStream
    val displayName: String
    /** Seekable channel for parallel positional writes (null if the storage does not allow it). */
    val channel: FileChannel?
    private var pfd: ParcelFileDescriptor? = null
    private var raf: RandomAccessFile? = null
    private var sequentialPos = 0L

    init {
        displayName = uniqueName(fileName)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grabbit")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
            val fd = resolver.openFileDescriptor(uri, "rw")
            pfd = fd
            if (fd != null) {
                val fos = FileOutputStream(fd.fileDescriptor)
                stream = fos
                channel = fos.channel
            } else {
                stream = resolver.openOutputStream(uri) ?: error("Cannot open output")
                channel = null
            }
            file = null
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grabbit").apply { mkdirs() }
            file = File(dir, displayName)
            val r = RandomAccessFile(file, "rw")
            raf = r
            channel = r.channel
            stream = FileOutputStream(r.fd)
            uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
    }

    /** Appends in order (used by single-connection and HLS downloads). */
    @Synchronized
    fun writeSequential(buf: ByteArray, len: Int) {
        val ch = channel
        if (ch != null) {
            var written = 0
            val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
            while (bb.hasRemaining()) written += ch.write(bb, sequentialPos + written)
            sequentialPos += len
        } else stream.write(buf, 0, len)
    }

    private fun uniqueName(name: String): String {
        if (Build.VERSION.SDK_INT >= 29) return name // MediaStore de-duplicates (adds " (1)")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Grabbit")
        if (!File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        var i = 1
        while (File(dir, "$base ($i).$ext").exists()) i++
        return "$base ($i).$ext"
    }

    fun finish() {
        runCatching { channel?.force(true) }
        runCatching { stream.flush() }
        runCatching { stream.close() }
        runCatching { raf?.close() }
        runCatching { pfd?.close() }
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            file?.let { android.media.MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), arrayOf(mime), null) }
        }
    }

    fun abort() {
        runCatching { stream.close() }
        runCatching { raf?.close() }
        runCatching { pfd?.close() }
        if (Build.VERSION.SDK_INT >= 29) runCatching { context.contentResolver.delete(uri, null, null) }
        else file?.delete()
    }
}
