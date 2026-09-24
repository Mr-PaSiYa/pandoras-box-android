package com.pandorasbox.app

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Everything the UI needs to know about a newer release. */
data class UpdateInfo(
    val versionName: String,   // e.g. "0.2" (tag with any "v" prefix stripped)
    val tag: String,           // raw tag, e.g. "v0.2"
    val apkUrl: String,
    val apkSize: Long,         // bytes, 0 if unknown
    val notes: String,         // release description
    val releaseUrl: String     // the release page on GitHub
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val note: String? = null) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

/**
 * Talks to the public GitHub Releases API. No token needed (60 requests/hour per IP is plenty).
 * All functions here are safe to call from the main thread: the work runs on Dispatchers.IO.
 */
object UpdateChecker {

    const val GITHUB_REPO_URL = "https://github.com/Mr-PaSiYa/pandoras-box-android"

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/Mr-PaSiYa/pandoras-box-android/releases/latest"
    private const val USER_AGENT = "PandorasBox-Android"

    /** Sub-folder of cacheDir where APKs are stored. Must match res/xml/update_paths.xml. */
    private const val UPDATE_DIR = "updates"

    private val VERSION_REGEX = Regex("""\d+(?:\.\d+)*""")

    // ---------------------------------------------------------------- versions

    @Suppress("DEPRECATION")
    fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    /**
     * Compares dotted version numbers numerically ("0.10" > "0.9", "1.2" == "1.2.0").
     * Any prefix/suffix such as "v" or "-beta" is ignored. Returns <0, 0 or >0.
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> =
        VERSION_REGEX.find(v)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()

    private fun extractVersion(tag: String): String = VERSION_REGEX.find(tag)?.value ?: tag

    // ------------------------------------------------------------------- check

    suspend fun checkForUpdate(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val current = currentVersionName(context)
        var conn: HttpURLConnection? = null
        try {
            conn = open(LATEST_RELEASE_API, accept = "application/vnd.github+json")
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND ->
                    return@withContext UpdateCheckResult.UpToDate(current, "No releases have been published yet.")
                HttpURLConnection.HTTP_FORBIDDEN, 429 ->
                    return@withContext UpdateCheckResult.Failed("GitHub is limiting requests right now. Try again later.")
                else ->
                    return@withContext UpdateCheckResult.Failed("GitHub returned an error (HTTP $code).")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            parseRelease(json, current)
        } catch (_: IOException) {
            UpdateCheckResult.Failed("Couldn't reach GitHub. Check your internet connection.")
        } catch (_: JSONException) {
            UpdateCheckResult.Failed("GitHub's response couldn't be read.")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject, current: String): UpdateCheckResult {
        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) return UpdateCheckResult.Failed("The latest release has no version tag.")
        if (compareVersions(tag, current) <= 0) return UpdateCheckResult.UpToDate(current)

        // First .apk attached to the release.
        val assets = json.optJSONArray("assets") ?: JSONArray()
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apk = asset
                break
            }
        }
        val apkUrl = apk?.optString("browser_download_url").orEmpty()
        if (apk == null || apkUrl.isEmpty()) {
            return UpdateCheckResult.Failed("Version $tag is out, but the release has no APK attached.")
        }

        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = extractVersion(tag),
                tag = tag,
                apkUrl = apkUrl,
                apkSize = apk.optLong("size", 0L),
                notes = if (json.isNull("body")) "" else json.optString("body"),
                releaseUrl = json.optString("html_url").ifEmpty { "$GITHUB_REPO_URL/releases" }
            )
        )
    }

    // ---------------------------------------------------------------- download

    /**
     * Downloads the APK into cacheDir/updates and checks it is a genuine, newer build of this app.
     * Throws IOException with a user-readable message on any failure.
     * Cancelling the calling coroutine stops the download.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, UPDATE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // drop older downloads
        val target = File(dir, "PandorasBox-${info.versionName}.apk")
        val part = File(dir, target.name + ".part")
        var conn: HttpURLConnection? = null
        try {
            conn = open(info.apkUrl) // GitHub redirects to its CDN; HttpURLConnection follows it
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed (HTTP ${conn.responseCode}).")
            }
            val total = if (info.apkSize > 0) info.apkSize else conn.contentLengthLong

            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 100_000_000L) { // ~10 UI updates per second
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }

            if (info.apkSize > 0 && part.length() != info.apkSize) {
                throw IOException("The download was incomplete. Please try again.")
            }
            if (!part.renameTo(target)) throw IOException("Couldn't save the update file.")

            verifyApk(context, target)?.let {
                target.delete()
                throw IOException(it)
            }
            target
        } finally {
            conn?.disconnect()
            if (part.exists()) part.delete()
        }
    }

    /** Returns null if the APK is OK to install, otherwise a message explaining why not. */
    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: return "The downloaded file isn't a valid APK."
        if (archive.packageName != context.packageName) {
            return "The downloaded APK belongs to a different app."
        }
        val installedCode = PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(context.packageName, 0))
        val newCode = PackageInfoCompat.getLongVersionCode(archive)
        if (newCode <= installedCode) {
            return "The release APK has versionCode $newCode, which isn't higher than the installed " +
                "$installedCode, so Android would refuse to install it. Raise versionCode in build.gradle " +
                "and publish a new release."
        }
        return null
    }

    private fun open(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) setRequestProperty("Accept", accept)
        }
}
