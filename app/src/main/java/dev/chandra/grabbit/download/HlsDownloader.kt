package dev.chandra.grabbit.download

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Downloads an HLS stream: picks the best variant of a master playlist, then fetches segments on
 * several connections at once while writing them to the file strictly in order.
 */
class HlsDownloader(private val rec: DownloadRecord, private val sink: MediaSink, private val progress: Progress, private val onSegments: (done: Int, total: Int) -> Unit) {

    suspend fun run() {
        var playlistUrl = rec.url
        var text = fetchText(playlistUrl)
        if (Hls.isMaster(text)) {
            val variants = Hls.parseMaster(text, playlistUrl)
            if (variants.isEmpty()) throw IllegalStateException("No streams in playlist")
            playlistUrl = variants.first().url
            text = fetchText(playlistUrl)
        }
        val media = Hls.parseMedia(text, playlistUrl)
        if (media.segments.isEmpty()) throw IllegalStateException("Playlist has no segments")
        val keyCache = HashMap<String, ByteArray>()
        media.initUrl?.let { val b = fetchBytes(it); sink.writeSequential(b, b.size); progress.add(b.size) }

        val total = media.segments.size
        val limiter = Semaphore(PARALLEL)
        coroutineScope {
            val window = ArrayDeque<Pair<Int, Deferred<ByteArray>>>()
            var next = 0
            var written = 0
            // Keep up to WINDOW segments in flight; write strictly in order.
            while (written < total) {
                while (next < total && window.size < WINDOW) {
                    val seg = media.segments[next]
                    val idx = next
                    window.addLast(idx to async(Dispatchers.IO) {
                        limiter.withPermit {
                            var bytes = fetchBytes(seg.url)
                            seg.keyUrl?.let { ku ->
                                val key = synchronized(keyCache) { keyCache[ku] } ?: fetchBytes(ku).also { k -> synchronized(keyCache) { keyCache[ku] = k } }
                                bytes = Hls.decrypt(bytes, key, seg)
                            }
                            bytes
                        }
                    })
                    next++
                }
                val (_, job) = window.removeFirst()
                val bytes = job.await()
                currentCoroutineContext().ensureActive()
                sink.writeSequential(bytes, bytes.size)
                written++
                onSegments(written, total)
                progress.commit(bytes.size)
            }
        }
        progress.finish()
    }

    private fun fetchText(url: String): String =
        Http.client.newCall(Http.request(url, rec.referer, rec.userAgent)).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} for playlist")
            r.body?.string() ?: ""
        }

    /** Fetches one segment, counting bytes toward speed as they arrive; a failed attempt un-counts them. */
    private suspend fun fetchBytes(url: String): ByteArray {
        var attempt = 0
        while (true) {
            var counted = 0L
            try {
                return Http.client.newCall(Http.request(url, rec.referer, rec.userAgent)).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: return@use ByteArray(0)
                    val out = java.io.ByteArrayOutputStream(body.contentLength().coerceIn(0, 32L * 1024 * 1024).toInt())
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            counted += n
                            progress.addInflight(n.toLong())
                        }
                    }
                    out.toByteArray()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.addInflight(-counted)
                if (++attempt > 5) throw e
                delay(600L * attempt)
            }
        }
    }

    companion object {
        const val PARALLEL = 4
        const val WINDOW = 8
    }
}
