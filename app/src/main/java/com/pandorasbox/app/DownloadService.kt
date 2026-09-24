package com.pandorasbox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the app process alive as a foreground service for as long as at least one
 * download is actively running.
 *
 * Why this is needed: DownloadManager runs downloads in its own long-lived
 * CoroutineScope, which is correct, but that scope lives inside a plain app
 * process. As soon as the app goes to the background (or the screen locks),
 * Android applies background execution / network limits and, after a while,
 * can freeze or kill the process outright -- which is why downloads were
 * stopping. Promoting the process to the "foreground" priority band via a
 * foreground service (with its mandatory ongoing notification) is the
 * documented, modern way to stop the system from doing that.
 *
 * This service does not do any downloading itself -- DownloadManager's
 * coroutines still do that exactly as before. It only exists to hold the
 * foreground-priority notification that keeps the process alive. The
 * detailed, per-job progress notifications are still entirely handled by
 * DownloadNotifier, unchanged, and still respect the user's notification
 * toggle in Settings.
 */
class DownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground state in case the service process was already alive
        // but got a fresh start command (e.g. system restarted it after low memory).
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    /**
     * Minimal, silent, low-priority notification. This is separate from the app's
     * own progress notifications (DownloadNotifier) and exists purely to satisfy
     * Android's requirement that a foreground service show an ongoing notification.
     * It shows regardless of the in-app "Download notifications" toggle, the same
     * way it would for any other download manager -- there is no way around this,
     * it's how Android's foreground service contract works.
     */
    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pandora's Box")
            .setContentText("Downloading in the background\u2026")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps downloads running while the app is in the background"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "download_foreground_service"
        private const val FOREGROUND_NOTIFICATION_ID = 9001

        // Simple in-process guard so DownloadManager can call ensureStarted()/stop()
        // freely (e.g. every time checkAndDispatch runs) without spamming the
        // system with redundant service start/stop calls.
        @Volatile
        private var isRunning = false

        /** Starts the foreground service if it isn't already running. Safe to call repeatedly. */
        @Synchronized
        fun ensureStarted(context: Context) {
            if (isRunning) return
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            isRunning = true
        }

        /** Stops the foreground service if it is running. Safe to call repeatedly. */
        @Synchronized
        fun stop(context: Context) {
            if (!isRunning) return
            context.stopService(Intent(context, DownloadService::class.java))
            isRunning = false
        }
    }
}
