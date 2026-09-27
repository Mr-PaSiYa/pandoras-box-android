package com.pandorasbox.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
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
        val builder = MaterialAlertDialogBuilder(activity)
        val ctx = builder.context
        fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).toInt()
        fun color(attribute: Int): Int = TypedValue().let { value ->
            ctx.theme.resolveAttribute(attribute, value, true)
            value.data
        }

        val primary = color(R.attr.appTextPrimary)
        val secondary = color(R.attr.appTextSecondary)
        val accent = color(R.attr.appAccent)
        val notes = info.notes.trim()

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        content.addView(TextView(ctx).apply {
            text = "Version ${info.versionName}"
            setTextColor(primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
        })
        content.addView(TextView(ctx).apply {
            text = buildString {
                append("Installed ${UpdateChecker.currentVersionName(activity)}")
                if (info.apkSize > 0) append("  ·  ${formatSize(info.apkSize)} download")
            }
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        if (notes.isNotEmpty()) {
            content.addView(TextView(ctx).apply {
                text = "WHAT'S NEW"
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.08f
                setTypeface(null, Typeface.BOLD)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            })

            val screenHeight = ctx.resources.displayMetrics.heightPixels
            val notesHeight = minOf((screenHeight * 0.55f).toInt(), screenHeight - dp(280)).coerceAtLeast(dp(80))
            val scroll = BoundedNotesScrollView(ctx, notesHeight).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(TextView(ctx).apply {
                    text = formatReleaseNotes(notes, primary, accent)
                    setTextColor(primary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, 0, dp(4), dp(8))
                })
            }
            content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val skip = TextView(ctx).apply {
            text = "Skip this version"
            setTextColor(secondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
            setOnClickListener {
                prefs.edit().putString(KEY_IGNORED_VERSION, info.versionName).apply()
                dialog?.dismiss()
            }
        }
        content.addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })

        dialog?.dismiss()
        dialog = builder
            .setTitle("Update available")
            .setView(content)
            .setPositiveButton("Update") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
        dialog?.window?.setLayout(
            minOf(ctx.resources.displayMetrics.widthPixels - dp(32), dp(520)),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private fun formatReleaseNotes(markdown: String, primary: Int, accent: Int): CharSequence {
        val result = SpannableStringBuilder()
        val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
        val bulletPattern = Regex("^\\s*[-*+]\\s+(.+)$")
        val lines = markdown.replace("\r\n", "\n").lines().dropLastWhile { it.isBlank() }
        lines.forEachIndexed { index, raw ->
            if (index > 0) result.append('\n')
            val line = raw.trimEnd()
            val heading = headingPattern.matchEntire(line.trimStart())
            val bullet = bulletPattern.matchEntire(line)
            val rendered = when {
                heading != null -> heading.groupValues[2]
                bullet != null -> "•  ${bullet.groupValues[1]}"
                line.trim() == "```" -> ""
                else -> line
            }.replace("**", "").replace("`", "")
            val start = result.length
            result.append(rendered)
            if (heading != null) {
                val end = result.length
                result.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(RelativeSizeSpan(if (heading.groupValues[1].length == 1) 1.25f else 1.1f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(ForegroundColorSpan(if (heading.groupValues[1].length == 1) accent else primary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
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

/** Keeps long release notes scrollable while the dialog actions remain visible. */
private class BoundedNotesScrollView(context: Context, private val maximumHeight: Int) : NestedScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentMode = View.MeasureSpec.getMode(heightMeasureSpec)
        val allowedHeight = if (parentMode == View.MeasureSpec.UNSPECIFIED) {
            maximumHeight
        } else {
            minOf(maximumHeight, View.MeasureSpec.getSize(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(allowedHeight, View.MeasureSpec.AT_MOST))
    }
}
