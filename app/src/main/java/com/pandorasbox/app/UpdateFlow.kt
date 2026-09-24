package com.pandorasbox.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Drives the whole in-app update experience. One instance lives in MainActivity.
 *
 *  check(manual = false)  -> app start: silent unless a newer version exists
 *  check(manual = true)   -> "Check for updates" button: always reports the outcome
 *
 * The user is never forced to update: every dialog can be dismissed.
 */
class UpdateFlow(private val activity: FragmentActivity) : DefaultLifecycleObserver {

    companion object {
        private const val PREFS = "update_prefs"
        private const val KEY_IGNORED_VERSION = "ignored_version"
        private const val APK_MIME = "application/vnd.android.package-archive"

        /** Opens a web page in the user's browser. */
        fun openUrl(context: Context, url: String) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No app found to open this link.", Toast.LENGTH_SHORT).show()
            }
        }

        private fun formatSize(bytes: Long): String =
            String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var dialog: AlertDialog? = null

    /** Set while the user is on Android's "Install unknown apps" screen. */
    private var pendingAfterPermission: UpdateInfo? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    // ------------------------------------------------------------------ public

    /**
     * @param onDone called once when the *check* finishes (not the download) with a short status
     *               line for the Settings screen. Not called if the activity is destroyed first.
     */
    fun check(manual: Boolean, onDone: ((String) -> Unit)? = null) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) {
            onDone?.invoke("An update check or download is already in progress.")
            return
        }
        checkJob = activity.lifecycleScope.launch {
            when (val result = UpdateChecker.checkForUpdate(activity)) {
                is UpdateCheckResult.Available -> {
                    val info = result.info
                    onDone?.invoke("Update available: v${info.versionName}")
                    val ignored = prefs.getString(KEY_IGNORED_VERSION, null) == info.versionName
                    // Startup checks respect "Skip this version"; manual checks always show it.
                    if ((manual || !ignored) && canShowDialog()) showUpdateDialog(info)
                }
                is UpdateCheckResult.UpToDate ->
                    onDone?.invoke(result.note ?: "You're on the latest version (v${result.currentVersion}).")
                is UpdateCheckResult.Failed ->
                    onDone?.invoke("Couldn't check for updates. ${result.message}")
            }
        }
    }

    // --------------------------------------------------------------- lifecycle

    /** Back from the "Install unknown apps" screen: carry on if the user allowed it. */
    override fun onResume(owner: LifecycleOwner) {
        val info = pendingAfterPermission ?: return
        pendingAfterPermission = null
        if (canInstallPackages()) {
            download(info)
        } else {
            toast("Install permission wasn't granted, so the update was cancelled.")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dialog?.dismiss()
        dialog = null
    }

    // ----------------------------------------------------------------- dialogs

    private fun canShowDialog(): Boolean =
        !activity.isFinishing && !activity.isDestroyed &&
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun showUpdateDialog(info: UpdateInfo) {
        val message = buildString {
            append("Version ${info.versionName} is available (you have ${UpdateChecker.currentVersionName(activity)}).")
            if (info.apkSize > 0) append("\nDownload size: ${formatSize(info.apkSize)}")
            val notes = info.notes.trim()
            if (notes.isNotEmpty()) {
                append("\n\nWhat's new:\n")
                append(if (notes.length > 600) notes.take(600) + "…" else notes)
            }
        }
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Update available")
            .setMessage(message)
            .setPositiveButton("Update") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ ->
                prefs.edit().putString(KEY_IGNORED_VERSION, info.versionName).apply()
            }
            .show()
    }

    private fun showError(title: String, message: String, info: UpdateInfo? = null) {
        if (!canShowDialog()) return
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
        if (info != null) {
            builder.setNeutralButton("Open release page") { _, _ -> openUrl(activity, info.releaseUrl) }
        }
        dialog?.dismiss()
        dialog = builder.show()
    }

    // ------------------------------------------------------------- permission

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else {
            true // below Android 8 the global "unknown sources" toggle applies; nothing to request
        }

    private fun startUpdate(info: UpdateInfo) {
        if (canInstallPackages()) {
            download(info)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Allow installing updates")
            .setMessage(
                "Android needs your permission before Pandora's Box can install an update. " +
                    "On the next screen, turn on \"Allow from this source\", then press Back to return."
            )
            .setPositiveButton("Open settings") { _, _ ->
                pendingAfterPermission = info
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } catch (_: ActivityNotFoundException) {
                    pendingAfterPermission = null
                    toast("Couldn't open that screen. Enable it under Settings > Apps > Special access > Install unknown apps.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --------------------------------------------------------------- download

    private fun download(info: UpdateInfo) {
        val builder = MaterialAlertDialogBuilder(activity)
        val ctx = builder.context // themed like the dialog, so text colours match

        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        val label = TextView(ctx).apply { text = "Starting download…" }
        val progress = LinearProgressIndicator(ctx).apply {
            isIndeterminate = false
            max = 100
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(label)
            addView(
                progress,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(12) }
            )
        }

        dialog?.dismiss()
        dialog = builder
            .setTitle("Downloading update")
            .setView(content)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> downloadJob?.cancel() }
            .show()

        downloadJob = activity.lifecycleScope.launch {
            try {
                val apk = UpdateChecker.downloadApk(activity, info) { done, total ->
                    // Called from the IO thread; View.post is safe to use from there.
                    progress.post {
                        if (total > 0) {
                            progress.setProgressCompat((done * 100 / total).toInt(), false)
                            label.text = "${formatSize(done)} of ${formatSize(total)}"
                        } else {
                            label.text = formatSize(done)
                        }
                    }
                }
                dialog?.dismiss()
                launchInstaller(apk, info)
            } catch (e: CancellationException) {
                dialog?.dismiss()
                throw e
            } catch (e: Exception) {
                dialog?.dismiss()
                showError("Update failed", e.message ?: "Please check your connection and try again.", info)
            }
        }
    }

    // ---------------------------------------------------------------- install

    private fun launchInstaller(apk: File, info: UpdateInfo) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            showError("Couldn't start the installer", e.message ?: "Unknown error.", info)
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
