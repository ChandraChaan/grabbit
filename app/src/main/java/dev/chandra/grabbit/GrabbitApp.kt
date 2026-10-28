package dev.chandra.grabbit

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.chandra.grabbit.download.DownloadStore

class GrabbitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
        DownloadStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Download progress and completion"; setShowBadge(false) }
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
