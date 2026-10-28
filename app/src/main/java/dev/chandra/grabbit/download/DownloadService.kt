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
            if (rec.isHls) {
                HlsDownloader(rec, sink, progress) { done, total -> DownloadStore.update(rec.id) { it.segmentsDone = done; it.segmentsTotal = total } }.run()
            } else {
                DirectDownloader(rec, sink, progress).run()
            }
            sink.finish()
            DownloadStore.update(rec.id) { it.status = DownloadStatus.DONE; it.savedUri = sink.uri.toString(); it.fileName = sink.displayName; it.speedBps = 0; it.etaSec = -1; if (it.bytesTotal <= 0) it.bytesTotal = it.bytesDone }
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
