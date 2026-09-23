package com.pandorasbox.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DownloadManager {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var appContext: Context

    private val _activeJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val activeJobs: StateFlow<List<DownloadJob>> = _activeJobs.asStateFlow()

    private val _queuedJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val queuedJobs: StateFlow<List<DownloadJob>> = _queuedJobs.asStateFlow()

    private val _historyJobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val historyJobs: StateFlow<List<DownloadJob>> = _historyJobs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val runningJobIds = mutableSetOf<String>()
    private val activeJobsMap = mutableMapOf<String, DownloadJob>()
    private val runningCoroutines = mutableMapOf<String, Job>()

    fun init(context: Context) {
        appContext = context.applicationContext

        val defaultFolder = FileUtils.getDefaultDownloadFolder(appContext)
        val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val currentSettings = AppSettings(
            maxConcurrent = prefs.getInt("max_concurrent", 2),
            stallTimeout = prefs.getInt("stall_timeout", 120),
            notificationsEnabled = prefs.getBoolean("notifications", true),
            downloadFolder = prefs.getString("download_folder", defaultFolder) ?: defaultFolder,
            lastReferer = prefs.getString("last_referer", "") ?: "",
            lastUserAgent = prefs.getString("last_user_agent", "") ?: ""
        )
        _settings.value = currentSettings

        loadHistory()
        checkAndDispatch()
    }

    fun getDownloadFolder(): String {
        val folder = _settings.value.downloadFolder
        if (folder.isBlank()) {
            return FileUtils.getDefaultDownloadFolder(appContext)
        }
        return folder
    }

    fun setDownloadFolder(path: String) {
        _settings.value = _settings.value.copy(downloadFolder = path)
        saveSettings()
    }

    fun updateSettings(maxConcurrent: Int, stallTimeout: Int, notifications: Boolean) {
        _settings.value = _settings.value.copy(
            maxConcurrent = maxConcurrent,
            stallTimeout = stallTimeout,
            notificationsEnabled = notifications
        )
        saveSettings()
        checkAndDispatch()
    }

    fun saveLastHeaders(referer: String, userAgent: String) {
        if (referer.isNotBlank() || userAgent.isNotBlank()) {
            _settings.value = _settings.value.copy(
                lastReferer = referer,
                lastUserAgent = userAgent
            )
            saveSettings()
        }
    }

    private fun saveSettings() {
        val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val s = _settings.value
        prefs.edit()
            .putInt("max_concurrent", s.maxConcurrent)
            .putInt("stall_timeout", s.stallTimeout)
            .putBoolean("notifications", s.notificationsEnabled)
            .putString("download_folder", s.downloadFolder)
            .putString("last_referer", s.lastReferer)
            .putString("last_user_agent", s.lastUserAgent)
            .apply()
    }

    fun enqueueDownload(
        url: String,
        title: String = "",
        format: String,
        quality: String,
        duplicatePolicy: String,
        subtitles: Boolean,
        embedMeta: Boolean,
        referer: String?,
        userAgent: String?
    ) {
        val job = DownloadJob(
            id = UUID.randomUUID().toString().take(12),
            url = url,
            title = title,
            format = format,
            quality = quality,
            duplicatePolicy = duplicatePolicy,
            subtitles = subtitles,
            embedMeta = embedMeta,
            referer = referer,
            userAgent = userAgent,
            createdAt = getCurrentTimestamp()
        )

        val list = _queuedJobs.value.toMutableList()
        list.add(job)
        _queuedJobs.value = list

        saveLastHeaders(referer ?: "", userAgent ?: "")
        checkAndDispatch()
    }

    fun enqueueBatch(
        urls: List<String>,
        format: String,
        quality: String,
        duplicatePolicy: String,
        subtitles: Boolean,
        embedMeta: Boolean,
        referer: String?,
        userAgent: String?
    ) {
        val list = _queuedJobs.value.toMutableList()
        for (u in urls) {
            val url = u.trim()
            if (url.isNotBlank()) {
                list.add(
                    DownloadJob(
                        id = UUID.randomUUID().toString().take(12),
                        url = url,
                        format = format,
                        quality = quality,
                        duplicatePolicy = duplicatePolicy,
                        subtitles = subtitles,
                        embedMeta = embedMeta,
                        referer = referer,
                        userAgent = userAgent,
                        createdAt = getCurrentTimestamp()
                    )
                )
            }
        }
        _queuedJobs.value = list
        saveLastHeaders(referer ?: "", userAgent ?: "")
        checkAndDispatch()
    }

    fun enqueuePlaylist(
        playlistTitle: String,
        entries: List<PlaylistEntry>,
        numbering: Boolean,
        format: String,
        quality: String,
        duplicatePolicy: String,
        subtitles: Boolean,
        embedMeta: Boolean,
        referer: String?,
        userAgent: String?
    ) {
        val list = _queuedJobs.value.toMutableList()
        val total = entries.size
        val pad = total.toString().length

        entries.forEachIndexed { index, entry ->
            if (entry.included && entry.url.isNotBlank()) {
                list.add(
                    DownloadJob(
                        id = UUID.randomUUID().toString().take(12),
                        url = entry.url,
                        title = entry.title,
                        format = format,
                        quality = quality,
                        duplicatePolicy = duplicatePolicy,
                        playlistTitle = playlistTitle,
                        playlistIndex = if (numbering) index + 1 else null,
                        playlistPad = pad,
                        subtitles = subtitles,
                        embedMeta = embedMeta,
                        referer = referer,
                        userAgent = userAgent,
                        createdAt = getCurrentTimestamp()
                    )
                )
            }
        }
        _queuedJobs.value = list
        saveLastHeaders(referer ?: "", userAgent ?: "")
        checkAndDispatch()
    }

    fun pauseQueue() {
        _isPaused.value = true
    }

    fun resumeQueue() {
        _isPaused.value = false
        checkAndDispatch()
    }

    fun cancelJob(jobId: String) {
        synchronized(this) {
            val queued = _queuedJobs.value.filter { it.id != jobId }
            _queuedJobs.value = queued

            val runningJob = runningCoroutines[jobId]
            if (runningJob != null) {
                runningJob.cancel()
                runningCoroutines.remove(jobId)
            }

            val job = activeJobsMap[jobId]
            if (job != null) {
                job.status = "cancelled"
                job.stage = "Cancelled"
                addToHistory(job)
                activeJobsMap.remove(jobId)
                runningJobIds.remove(jobId)
                updateActiveList()
            }
        }
        checkAndDispatch()
    }

    fun retryJob(job: DownloadJob) {
        enqueueDownload(
            url = job.url,
            title = job.title,
            format = job.format,
            quality = job.quality,
            duplicatePolicy = job.duplicatePolicy,
            subtitles = job.subtitles,
            embedMeta = job.embedMeta,
            referer = job.referer,
            userAgent = job.userAgent
        )
    }

    fun clearHistory() {
        _historyJobs.value = emptyList()
        saveHistory()
    }

    @Synchronized
    private fun checkAndDispatch() {
        if (_isPaused.value) return

        val maxConcurrent = _settings.value.maxConcurrent
        while (runningJobIds.size < maxConcurrent && _queuedJobs.value.isNotEmpty()) {
            val queuedList = _queuedJobs.value.toMutableList()
            if (queuedList.isEmpty()) break

            val job = queuedList.removeAt(0)
            _queuedJobs.value = queuedList

            job.status = "starting"
            job.stage = "Starting..."
            runningJobIds.add(job.id)
            activeJobsMap[job.id] = job
            updateActiveList()

            val jobCoroutine = scope.launch {
                executeJob(job)
            }
            runningCoroutines[job.id] = jobCoroutine
        }
    }

    private suspend fun executeJob(job: DownloadJob) {
        val targetDir = if (!job.playlistTitle.isNullOrBlank()) {
            File(getDownloadFolder(), job.playlistTitle ?: "")
        } else {
            File(getDownloadFolder())
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val baseFilename = if (job.playlistIndex != null) {
            String.format(Locale.US, "%0${job.playlistPad}d - %s", job.playlistIndex, sanitizeFilename(job.title.ifBlank { "%(title)s" }))
        } else if (job.title.isNotBlank()) {
            sanitizeFilename(job.title)
        } else {
            "%(title)s"
        }

        val extension = if (job.format == "mp3") "mp3" else "%(ext)s"
        val outputFile = File(targetDir, "$baseFilename.$extension")

        val ffmpegPath = FFmpegHelper.getFFmpegExecutablePath(appContext)

        val result = YtDlpEngine.downloadVideo(
            url = job.url,
            outputPath = outputFile.absolutePath,
            formatType = job.format,
            quality = job.quality,
            subtitles = job.subtitles,
            embedMeta = job.embedMeta,
            referer = job.referer,
            userAgent = job.userAgent,
            ffmpegPath = ffmpegPath
        ) { progress ->
            synchronized(this) {
                val current = activeJobsMap[job.id] ?: return@downloadVideo
                current.status = progress.status
                current.percent = progress.percent
                current.downloaded = progress.downloaded
                current.total = progress.total
                current.speed = progress.speed
                current.eta = progress.eta
                current.stage = if (progress.status == "downloading") "Downloading" else progress.status
                if (progress.error.isNotBlank()) {
                    current.error = progress.error
                }
                updateActiveList()
            }
        }

        synchronized(this) {
            runningJobIds.remove(job.id)
            runningCoroutines.remove(job.id)
            activeJobsMap.remove(job.id)
            updateActiveList()

            if (result.success) {
                job.status = "completed"
                job.stage = "Completed"
                job.percent = 100f
                job.filePath = result.filePath ?: outputFile.absolutePath
            } else {
                job.status = "failed"
                job.stage = "Failed"
                if (!result.error.isNullOrBlank()) {
                    job.error = result.error
                }
            }
            addToHistory(job)
        }

        checkAndDispatch()
    }

    private fun updateActiveList() {
        _activeJobs.value = activeJobsMap.values.map { it.copy() }
    }

    private fun addToHistory(job: DownloadJob) {
        val currentHistory = _historyJobs.value.toMutableList()
        currentHistory.add(0, job.copy())
        if (currentHistory.size > 300) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        _historyJobs.value = currentHistory
        saveHistory()
    }

    private fun saveHistory() {
        try {
            val array = JSONArray()
            _historyJobs.value.forEach { j ->
                val obj = JSONObject()
                obj.put("id", j.id)
                obj.put("url", j.url)
                obj.put("title", j.title)
                obj.put("format", j.format)
                obj.put("quality", j.quality)
                obj.put("duplicatePolicy", j.duplicatePolicy)
                obj.put("playlistTitle", j.playlistTitle ?: "")
                obj.put("subtitles", j.subtitles)
                obj.put("embedMeta", j.embedMeta)
                obj.put("status", j.status)
                obj.put("filePath", j.filePath)
                obj.put("error", j.error)
                obj.put("createdAt", j.createdAt)
                array.put(obj)
            }
            val historyFile = File(appContext.filesDir, "history.json")
            historyFile.writeText(array.toString())
        } catch (_: Exception) {}
    }

    private fun loadHistory() {
        try {
            val historyFile = File(appContext.filesDir, "history.json")
            if (!historyFile.exists()) return
            val array = JSONArray(historyFile.readText())
            val list = mutableListOf<DownloadJob>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadJob(
                        id = obj.optString("id", ""),
                        url = obj.optString("url", ""),
                        title = obj.optString("title", ""),
                        format = obj.optString("format", "mp4"),
                        quality = obj.optString("quality", "best"),
                        duplicatePolicy = obj.optString("duplicatePolicy", "rename"),
                        playlistTitle = obj.optString("playlistTitle", "").ifEmpty { null },
                        subtitles = obj.optBoolean("subtitles", false),
                        embedMeta = obj.optBoolean("embedMeta", false),
                        status = obj.optString("status", "completed"),
                        filePath = obj.optString("filePath", ""),
                        error = obj.optString("error", ""),
                        createdAt = obj.optString("createdAt", "")
                    )
                )
            }
            _historyJobs.value = list
        } catch (_: Exception) {}
    }

    private fun sanitizeFilename(name: String): String {
        var clean = name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "")
        clean = clean.trim().trimEnd('.')
        return if (clean.isBlank()) "video" else clean.take(180)
    }

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }
}
