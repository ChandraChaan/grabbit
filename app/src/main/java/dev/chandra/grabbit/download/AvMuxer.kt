package dev.chandra.grabbit.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Stream-copy muxing with the platform MediaExtractor/MediaMuxer (no re-encoding):
 *  - merge a video-only file with an audio-only file into one MP4/WebM,
 *  - remux an MPEG-TS (HLS) file into MP4 so it plays everywhere.
 */
object AvMuxer {
    private class Track(val extractor: MediaExtractor, val index: Int, val format: MediaFormat)

    private fun openTrack(path: String, prefix: String): Track? {
        val ex = MediaExtractor()
        ex.setDataSource(path)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                ex.selectTrack(i)
                return Track(ex, i, f)
            }
        }
        ex.release()
        return null
    }

    /** Returns null on success, else an error description. Caller supplies an open "rw" descriptor at position 0. */
    fun merge(videoPath: String, audioPath: String, out: FileDescriptor, webm: Boolean, onProgress: (Float) -> Unit = {}): String? {
        val v = try { openTrack(videoPath, "video/") ?: return "no video track in downloaded video file" } catch (e: Exception) { return "video file unreadable: ${e.message}" }
        val a = try { openTrack(audioPath, "audio/") } catch (e: Exception) { v.extractor.release(); return "audio file unreadable: ${e.message}" }
        if (a == null) { v.extractor.release(); return "no audio track in downloaded audio file" }
        val muxer = MediaMuxer(out, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vOut = muxer.addTrack(v.format)
            val aOut = muxer.addTrack(a.format)
            runCatching { v.format.getInteger("rotation-degrees") }.getOrNull()?.let { muxer.setOrientationHint(it) }
            muxer.start()
            val vDur = v.format.getLong(MediaFormat.KEY_DURATION, 1L)
            copy(v, muxer, vOut) { onProgress(0.5f * it / vDur) }
            val aDur = a.format.getLong(MediaFormat.KEY_DURATION, 1L)
            copy(a, muxer, aOut) { onProgress(0.5f + 0.5f * it / aDur) }
            muxer.stop()
            return null
        } catch (e: Exception) {
            return "mux error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { muxer.release() }
            v.extractor.release(); a.extractor.release()
        }
    }

    /** Remux an MPEG-TS/other container into MP4 (H.264/H.265 + AAC). Returns false if the codecs don't fit MP4. */
    fun remuxToMp4(inputPath: String, out: FileDescriptor, onProgress: (Float) -> Unit = {}): Boolean {
        val ex = MediaExtractor()
        try { ex.setDataSource(inputPath) } catch (e: Exception) { ex.release(); return false }
        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val map = HashMap<Int, Int>()
            var durUs = 1L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val ok = mime == "video/avc" || mime == "video/hevc" || mime == "audio/mp4a-latm" || mime == "audio/mpeg"
                if (!ok) continue
                // Extractor formats from TS may lack csd; MediaMuxer needs them for AVC/AAC. Skip if absent.
                if (mime.startsWith("video/") && !f.containsKey("csd-0")) continue
                if (mime == "audio/mp4a-latm" && !f.containsKey("csd-0")) continue
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
                durUs = maxOf(durUs, f.getLong(MediaFormat.KEY_DURATION, 0L))
            }
            if (map.isEmpty() || map.keys.none { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }) return false
            muxer.start()
            val buf = ByteBuffer.allocateDirect(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTrackIndex
                val outIdx = map[t]
                if (outIdx != null) {
                    info.offset = 0; info.size = n; info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(outIdx, buf, info)
                    if (durUs > 1) onProgress((ex.sampleTime.toFloat() / durUs).coerceIn(0f, 1f))
                }
                ex.advance()
            }
            muxer.stop()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }

    data class Tracks(val hasVideo: Boolean, val hasAudio: Boolean, val width: Int, val height: Int)

    /** Which tracks a (possibly partial) media file contains; null if it cannot be parsed at all. */
    fun inspect(path: String? = null, fd: FileDescriptor? = null): Tracks? {
        val ex = MediaExtractor()
        try {
            if (path != null) ex.setDataSource(path) else if (fd != null) ex.setDataSource(fd) else return null
            var v = false; var a = false; var w = 0; var h = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { v = true; w = f.getInteger(MediaFormat.KEY_WIDTH, 0); h = f.getInteger(MediaFormat.KEY_HEIGHT, 0) }
                if (mime.startsWith("audio/")) a = true
            }
            return Tracks(v, a, w, h)
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }

    private fun copy(t: Track, muxer: MediaMuxer, outIndex: Int, progress: (Long) -> Unit) {
        val buf = ByteBuffer.allocateDirect(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var count = 0
        while (true) {
            val n = t.extractor.readSampleData(buf, 0)
            if (n < 0) break
            info.offset = 0; info.size = n; info.presentationTimeUs = t.extractor.sampleTime
            info.flags = if (t.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(outIndex, buf, info)
            if (++count % 50 == 0) progress(t.extractor.sampleTime)
            t.extractor.advance()
        }
    }
}
