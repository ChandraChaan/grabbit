package dev.chandra.grabbit.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer

/**
 * Downloads a plain file. When the server supports byte ranges and the file is large enough, the
 * file is split into parts that download on parallel connections and are written at their offsets.
 * Each part retries from where it stopped, so a network blip does not restart the download.
 */
class DirectDownloader(private val rec: DownloadRecord, private val sink: MediaSink, private val progress: Progress) {

    suspend fun run() {
        val head = probe()
        val total = head?.length ?: -1L
        progress.total = total
        val parts = when {
            head == null || !head.ranges || total < MIN_PARALLEL_BYTES || sink.channel == null -> 1
            total < 64L * 1024 * 1024 -> 4
            else -> 6
        }
        if (parts == 1) { single(); return }
        val partSize = total / parts
        val limiter = Semaphore(parts)
        coroutineScope {
            (0 until parts).map { i ->
                val start = i * partSize
                val end = if (i == parts - 1) total - 1 else (i + 1) * partSize - 1
                async(Dispatchers.IO) { limiter.withPermit { range(start, end) } }
            }.forEach { it.await() }
        }
        progress.finish()
    }

    private data class Probe(val length: Long, val ranges: Boolean, val contentType: String?)

    private fun probe(): Probe? = runCatching {
        val req = Http.request(rec.url, rec.referer, rec.userAgent).newBuilder().header("Range", "bytes=0-0").build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            rejectIfHtml(r.header("Content-Type"))
            val ranges = r.code == 206
            val len = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()?.takeIf { !ranges } ?: -1L
            Probe(len, ranges && len > 0, r.header("Content-Type"))
        }
    }.getOrNull()

    private fun rejectIfHtml(contentType: String?) {
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return
        if (ct.startsWith("text/html") || ct.startsWith("application/json"))
            throw IllegalStateException("Site returned a web page, not a media file (login or expired link)")
    }

    /** Single-connection stream for servers without range support; resumes with Range on failure if possible. */
    private suspend fun single() {
        var offset = 0L
        var attempt = 0
        while (true) {
            try {
                val req = Http.request(rec.url, rec.referer, rec.userAgent, rangeStart = offset)
                Http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    rejectIfHtml(resp.header("Content-Type"))
                    if (offset > 0 && resp.code != 206) throw IllegalStateException("Server does not support resume")
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    if (progress.total <= 0 && offset == 0L) progress.total = body.contentLength()
                    val buf = ByteArray(128 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.writeSequential(buf, n)
                            offset += n
                            progress.add(n)
                        }
                    }
                }
                progress.finish()
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only resume when the server told us it supports ranges for this stream.
                if (++attempt > MAX_RETRIES || progress.total <= 0 || sink.channel == null) throw e
                delay(700L * attempt)
            }
        }
    }

    private suspend fun range(start: Long, endInclusive: Long) {
        var pos = start
        var attempt = 0
        val buf = ByteArray(128 * 1024)
        while (pos <= endInclusive) {
            try {
                val req = Http.request(rec.url, rec.referer, rec.userAgent).newBuilder().header("Range", "bytes=$pos-$endInclusive").build()
                Http.client.newCall(req).execute().use { resp ->
                    if (resp.code != 206) throw IllegalStateException("HTTP ${resp.code} for range")
                    val input = resp.body?.byteStream() ?: throw IllegalStateException("Empty body")
                    input.use {
                        while (pos <= endInclusive) {
                            currentCoroutineContext().ensureActive()
                            val n = it.read(buf)
                            if (n < 0) break
                            sink.channel!!.write(ByteBuffer.wrap(buf, 0, n), pos)
                            pos += n
                            progress.add(n)
                        }
                    }
                }
                if (pos <= endInclusive) throw IllegalStateException("Connection closed early")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt > MAX_RETRIES) throw e
                delay(600L * attempt)
            }
        }
    }

    companion object {
        const val MIN_PARALLEL_BYTES = 3L * 1024 * 1024
        const val MAX_RETRIES = 6
    }
}
