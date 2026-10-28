package dev.chandra.grabbit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import dev.chandra.grabbit.download.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Tiny thumbnail loader: memory cache + OkHttp + downsampled decode. No library needed. */
object Thumbs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val TARGET = 220

    fun load(view: ImageView, url: String?, referer: String?, userAgent: String?, fallback: Int) {
        view.tag = url
        if (url.isNullOrBlank()) { view.setImageResource(fallback); return }
        cache.get(url)?.let { view.setImageBitmap(it); view.scaleType = ImageView.ScaleType.CENTER_CROP; view.setColorFilter(null); return }
        view.setImageResource(fallback)
        scope.launch {
            val bmp = runCatching { fetch(url, referer, userAgent) }.getOrNull() ?: return@launch
            cache.put(url, bmp)
            withContext(Dispatchers.Main) {
                if (view.tag == url) { view.setImageBitmap(bmp); view.scaleType = ImageView.ScaleType.CENTER_CROP; view.setColorFilter(null) }
            }
        }
    }

    /** Thumbnail of a saved file via MediaStore (API 29+) or a decode of the file. */
    fun loadLocal(context: Context, view: ImageView, uri: String?, fallback: Int) {
        view.tag = uri
        if (uri.isNullOrBlank()) { view.setImageResource(fallback); return }
        cache.get(uri)?.let { view.setImageBitmap(it); view.scaleType = ImageView.ScaleType.CENTER_CROP; view.setColorFilter(null); return }
        view.setImageResource(fallback)
        scope.launch {
            val bmp = runCatching {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(Uri.parse(uri), Size(TARGET, TARGET), null)
                else context.contentResolver.openInputStream(Uri.parse(uri))?.use { decode(it.readBytes()) }
            }.getOrNull() ?: return@launch
            cache.put(uri, bmp)
            withContext(Dispatchers.Main) {
                if (view.tag == uri) { view.setImageBitmap(bmp); view.scaleType = ImageView.ScaleType.CENTER_CROP; view.setColorFilter(null) }
            }
        }
    }

    private fun fetch(url: String, referer: String?, userAgent: String?): Bitmap? {
        val bytes = Http.client.newCall(Http.request(url, referer, userAgent)).execute().use { r ->
            if (!r.isSuccessful) return null
            val len = r.body?.contentLength() ?: -1
            if (len > 12L * 1024 * 1024) return null
            r.body?.bytes() ?: return null
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET && bounds.outHeight / (sample * 2) >= TARGET) sample *= 2
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
    }
}
