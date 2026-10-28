package dev.chandra.grabbit.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.chandra.grabbit.GrabbitApp
import dev.chandra.grabbit.R
import dev.chandra.grabbit.ui.DownloadsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that runs downloads with OkHttp (so we can send the page's cookies,
 * referer and user agent, which most CDNs require). Handles direct files and HLS playlists.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val parallel = Semaphore(3)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                val rec = DownloadStore.get(id) ?: return START_NOT_STICKY
                goForeground(buildNotification(rec))
                jobs[id] = scope.launch { parallel.withPermit { run(rec) } }
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                jobs.remove(id)?.cancel()
                DownloadStore.update(id) { it.status = DownloadStatus.CANCELLED }
                NotificationManagerCompat.from(this).cancel(id.toInt())
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(FOREGROUND_ID, n)
    }

    private suspend fun run(rec: DownloadRecord) {
        DownloadStore.update(rec.id) { it.status = DownloadStatus.RUNNING; it.error = null }
        var sink: MediaSink? = null
        try {
            sink = MediaSink(this, rec.fileName, rec.mime)
            var lastNotify = 0L
            val progress = Progress { done, total, speed, eta ->
                DownloadStore.update(rec.id) { it.bytesDone = done; it.bytesTotal = total; it.speedBps = speed; it.etaSec = eta }
                val now = System.currentTimeMillis()
                if (now - lastNotify > 600) {
                    lastNotify = now
                    notify(rec.id, buildNotification(DownloadStore.get(rec.id) ?: rec))
                }
            }
            when {
                rec.audioUrl != null -> downloadAndMerge(rec, sink, progress)
                rec.isHls -> downloadHlsAsMp4(rec, sink, progress)
                else -> DirectDownloader(rec, sink, progress).run()
            }
            sink.finish()
            val note = if (rec.mime.startsWith("video")) checkAudio(sink.uri) else null
            DownloadStore.update(rec.id) { it.status = DownloadStatus.DONE; it.savedUri = sink.uri.toString(); it.fileName = sink.displayName; it.speedBps = 0; it.etaSec = -1; it.note = note; if (it.bytesTotal <= 0) it.bytesTotal = it.bytesDone }
            notify(rec.id, buildNotification(DownloadStore.get(rec.id) ?: rec))
            withContext(Dispatchers.Main) { Toast.makeText(this@DownloadService, getString(R.string.download_complete), Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            sink?.abort()
            if (e is kotlinx.coroutines.CancellationException) {
                DownloadStore.update(rec.id) { it.status = DownloadStatus.CANCELLED; it.speedBps = 0 }
            } else {
                DownloadStore.update(rec.id) { it.status = DownloadStatus.FAILED; it.error = e.message ?: e.javaClass.simpleName; it.speedBps = 0 }
                notify(rec.id, buildNotification(DownloadStore.get(rec.id) ?: rec))
                com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(e)
            }
        } finally {
            jobs.remove(rec.id)
            DownloadStore.flush()
            withContext(Dispatchers.Main) { stopIfIdle() }
        }
    }

    /** YouTube DASH: fetch video-only and audio-only tracks to cache, then stream-copy them into one file. */
    private suspend fun downloadAndMerge(rec: DownloadRecord, sink: MediaSink, progress: Progress) {
        val video = MediaSink.temp(this, "${rec.id}-v.bin")
        val audio = MediaSink.temp(this, "${rec.id}-a.bin")
        try {
            DownloadStore.update(rec.id) { it.error = null }
            DirectDownloader(rec, video, progress).run()
            video.finish()
            val audioRec = rec.copy(url = rec.audioUrl!!)
            val audioProgress = Progress { done, total, speed, eta -> DownloadStore.update(rec.id) { it.bytesDone = progress.total.coerceAtLeast(0) + done; it.bytesTotal = -1; it.speedBps = speed; it.etaSec = eta } }
            DirectDownloader(audioRec, audio, audioProgress).run()
            audio.finish()
            DownloadStore.update(rec.id) { it.speedBps = 0; it.etaSec = -1; it.error = null }
            notify(rec.id, buildNotification((DownloadStore.get(rec.id) ?: rec).also { it.error = "merging" }))
            val fd = sink.fileDescriptor ?: throw IllegalStateException("Cannot open target for merge")
            val vf = java.io.File(video.path!!); val af = java.io.File(audio.path!!)
            android.util.Log.i("Grabbit", "merge inputs: video=${vf.length()} B head=${headHex(vf)} audio=${af.length()} B head=${headHex(af)}")
            val err = AvMuxer.merge(vf.absolutePath, af.absolutePath, fd, webm = rec.container == "webm")
            if (err != null) {
                android.util.Log.e("Grabbit", "merge failed: $err")
                // keep inputs for inspection
                vf.copyTo(java.io.File(cacheDir, "last-merge-video.bin"), overwrite = true)
                af.copyTo(java.io.File(cacheDir, "last-merge-audio.bin"), overwrite = true)
                throw IllegalStateException("Merge failed: $err (video ${fmt(vf.length())}, audio ${fmt(af.length())})")
            }
            DownloadStore.update(rec.id) { it.bytesTotal = java.io.File(video.path!!).length() + java.io.File(audio.path!!).length(); it.bytesDone = it.bytesTotal }
        } finally {
            video.abort(); audio.abort()
        }
    }

    /** HLS: download the TS stream to cache, then remux into MP4; fall back to raw TS if the codecs don't fit. */
    private suspend fun downloadHlsAsMp4(rec: DownloadRecord, sink: MediaSink, progress: Progress) {
        val ts = MediaSink.temp(this, "${rec.id}-hls.ts")
        try {
            HlsDownloader(rec, ts, progress) { done, total -> DownloadStore.update(rec.id) { it.segmentsDone = done; it.segmentsTotal = total } }.run()
            ts.finish()
            val fd = sink.fileDescriptor
            val remuxed = fd != null && rec.fileName.endsWith(".mp4", true) && AvMuxer.remuxToMp4(ts.path!!, fd)
            if (!remuxed) {
                // Copy the raw TS as-is into the target.
                if (fd != null) { sink.channel?.truncate(0) }
                java.io.FileInputStream(ts.path!!).use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; sink.writeSequential(buf, n) }
                }
                DownloadStore.update(rec.id) { if (it.fileName.endsWith(".mp4", true)) it.fileName = it.fileName.removeSuffix(".mp4") + ".ts" }
                sink.renameTo(DownloadStore.get(rec.id)?.fileName, "video/mp2t")
            }
        } finally {
            ts.abort()
        }
    }

    /** A finished video with no audio track gets a visible warning instead of a silent surprise. */
    private fun checkAudio(uri: android.net.Uri): String? = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val t = AvMuxer.inspect(fd = pfd.fileDescriptor) ?: return null
            if (t.hasVideo && !t.hasAudio) getString(R.string.note_no_audio) else null
        }
    }.getOrNull()

    private fun headHex(f: java.io.File): String = runCatching {
        f.inputStream().use { val b = ByteArray(16); val n = it.read(b); b.take(n.coerceAtLeast(0)).joinToString("") { "%02x".format(it) } }
    }.getOrDefault("?")

    private fun buildNotification(rec: DownloadRecord): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, GrabbitApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(rec.fileName)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        when (rec.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                val cancel = PendingIntent.getService(this, rec.id.toInt(), Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, rec.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val pct = rec.progressPercent
                val line = statusLine(rec)
                b.setOngoing(true)
                    .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                    .setContentText(line)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(line))
                    .setSubText(if (pct >= 0) "$pct%" else null)
                    .addAction(R.drawable.ic_close, getString(R.string.cancel), cancel)
            }
            DownloadStatus.DONE -> b.setContentText(getString(R.string.download_complete)).setAutoCancel(true)
            DownloadStatus.FAILED -> b.setContentText(getString(R.string.download_failed) + (rec.error?.let { ": $it" } ?: "")).setAutoCancel(true)
            DownloadStatus.CANCELLED -> b.setContentText(getString(R.string.cancel)).setAutoCancel(true)
        }
        return b.build()
    }

    private fun notify(id: Long, n: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(id.toInt(), n) }
    }

    private fun stopIfIdle() {
        if (jobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.chandra.grabbit.START"
        const val ACTION_CANCEL = "dev.chandra.grabbit.CANCEL"
        const val EXTRA_ID = "id"
        private const val FOREGROUND_ID = 1

        fun enqueue(context: Context, record: DownloadRecord) {
            DownloadStore.add(record)
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, record.id)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context, id: Long) {
            context.startService(Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id))
        }

        /** "12.3 MB / 27.0 MB · 2.4 MB/s · 6 s left" (or segments for HLS). */
        fun statusLine(rec: DownloadRecord): String {
            val parts = ArrayList<String>()
            parts.add(fmt(rec.bytesDone) + (if (rec.bytesTotal > 0) " / ${fmt(rec.bytesTotal)}" else ""))
            if (rec.isHls && rec.segmentsTotal > 0) parts.add("${rec.segmentsDone}/${rec.segmentsTotal} parts")
            if (rec.speedBps > 0) parts.add(fmt(rec.speedBps) + "/s")
            if (rec.etaSec >= 0) parts.add(fmtEta(rec.etaSec) + " left")
            else if (rec.isHls && rec.segmentsTotal > 0 && rec.segmentsDone > 0 && rec.speedBps > 0) {
                val perSeg = rec.bytesDone / rec.segmentsDone
                parts.add(fmtEta((rec.segmentsTotal - rec.segmentsDone) * perSeg / rec.speedBps) + " left")
            }
            return parts.joinToString("  ·  ")
        }

        fun fmtEta(sec: Long): String = when {
            sec < 60 -> "$sec s"
            sec < 3600 -> "${sec / 60} min ${sec % 60} s"
            else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
        }

        fun fmt(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
            else -> "%.2f GB".format(bytes / 1073741824.0)
        }
    }
}
