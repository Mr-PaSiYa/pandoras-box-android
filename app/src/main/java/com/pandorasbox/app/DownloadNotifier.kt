package com.pandorasbox.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Builds and shows every download notification.
 *
 * This class does NOT look at the "Download notifications" setting. DownloadManager checks the
 * setting and only calls in here when notifications are switched on. What this class does check
 * is whether Android itself allows the app to post (runtime permission on Android 13+, or the
 * user blocking the app in system settings).
 *
 * Two channels (required on Android 8+):
 *  - "Download progress": low importance, silent, used for the running-download notification.
 *  - "Download results":  default importance, used for "complete" / "failed".
 *
 * Each job uses one notification id, so "finished" replaces "in progress" for that job.
 */
object DownloadNotifier {

    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    private const val CHANNEL_PROGRESS = "download_progress"
    private const val CHANNEL_RESULT = "download_result"

    // Android drops notification updates that come too fast, so progress is sent at most
    // once per second per download.
    private const val MIN_UPDATE_INTERVAL_MS = 1000L
    private val lastUpdate = HashMap<String, Long>()

    // ------------------------------------------------------------------
    // Setup / permission
    // ------------------------------------------------------------------

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            "Download progress",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows the progress of downloads that are running"
            setShowBadge(false)
        }

        val result = NotificationChannel(
            CHANNEL_RESULT,
            "Download results",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tells you when a download has finished or failed"
        }

        nm.createNotificationChannel(progress)
        nm.createNotificationChannel(result)
    }

    /** True only on Android 13+ when the user has not (yet) allowed notifications. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED

    private fun canPost(context: Context): Boolean =
        !needsPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    // ------------------------------------------------------------------
    // Showing / removing notifications
    // ------------------------------------------------------------------

    /** Start + progress notification (ongoing, with a progress bar). */
    fun showProgress(context: Context, job: DownloadJob) {
        val now = System.currentTimeMillis()
        synchronized(lastUpdate) {
            val last = lastUpdate[job.id]
            if (last != null && now - last < MIN_UPDATE_INTERVAL_MS) return
            lastUpdate[job.id] = now
        }
        if (!canPost(context)) return

        val percent = job.percent.toInt().coerceIn(0, 100)
        val indeterminate = percent <= 0

        val details = mutableListOf<String>()
        details += job.stage.ifBlank { "Downloading" }.replaceFirstChar { it.uppercase() }
        if (!indeterminate) details += "$percent%"
        if (hasValue(job.speed)) details += job.speed
        if (hasValue(job.eta)) details += "ETA ${job.eta}"

        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(displayName(job, useFileName = false))
            .setContentText(details.joinToString(" \u2022 "))
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .build()

        post(context, notificationId(job.id), notification)
    }

    /** Final notification for a finished job: completed (also "skipped") or failed. */
    fun showFinished(context: Context, job: DownloadJob) {
        synchronized(lastUpdate) { lastUpdate.remove(job.id) }
        if (!canPost(context)) return

        val success = job.status == "completed"
        val skipped = success && job.stage == "Skipped"
        val reason = job.error.trim()

        val text = when {
            skipped -> reason.ifBlank { "Skipped" }
            success -> "Download complete"
            reason.isBlank() -> "Download failed"
            else -> "Download failed: ${reason.take(120)}"
        }
        val longText = when {
            !success && reason.isNotBlank() -> "Download failed\n${reason.take(400)}"
            else -> text
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(displayName(job, useFileName = true))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
            .setProgress(0, 0, false) // removes the progress bar
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .build()

        post(context, notificationId(job.id), notification)
    }

    /** Removes one job's notification (cancelled / paused). */
    fun cancel(context: Context, jobId: String) {
        synchronized(lastUpdate) { lastUpdate.remove(jobId) }
        NotificationManagerCompat.from(context).cancel(notificationId(jobId))
    }

    /** Removes every notification of this app (used when the toggle is switched off). */
    fun cancelAll(context: Context) {
        synchronized(lastUpdate) { lastUpdate.clear() }
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * If the app process was killed while downloading, its "in progress" notification would stay
     * on screen forever. Call this on a fresh start (with nothing running) to sweep them away.
     */
    fun clearStaleOngoing(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.activeNotifications.forEach { sbn ->
                if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) {
                    nm.cancel(sbn.tag, sbn.id)
                }
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    // canPost() has already verified the permission; lint cannot see through that.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun notificationId(jobId: String): Int = jobId.hashCode()

    private fun hasValue(s: String): Boolean = s.isNotBlank() && s != "\u2014"

    private fun displayName(job: DownloadJob, useFileName: Boolean): String {
        if (job.title.isNotBlank()) return job.title
        if (useFileName && job.filePath.isNotBlank()) {
            val name = File(job.filePath).name
            if (name.isNotBlank()) return name
        }
        val host = try {
            Uri.parse(job.url).host
        } catch (_: Exception) {
            null
        }
        return host?.takeIf { it.isNotBlank() } ?: job.url.take(60)
    }

    /** Tapping a notification brings the app to the front (or starts it). */
    private fun openAppIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
