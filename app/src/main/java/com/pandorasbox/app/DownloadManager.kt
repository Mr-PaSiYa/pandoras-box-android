package com.pandorasbox.app

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
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
import kotlin.math.abs

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

    // Formats that are audio-only. Everything else is treated as video and gets
    // converted to a universal-compatibility MP4 after it has downloaded.
    // Set to true to re-encode every finished video into a strict H.264/AAC MP4 (slower, uses battery).
    private const val ENABLE_UNIVERSAL_REENCODE = false

    private val AUDIO_ONLY_FORMATS = setOf("mp3", "m4a", "aac", "wav", "opus", "flac", "ogg")

    // ------------------------------------------------------------------
    // Overall progress pipeline
    //
    //   yt-dlp raw stage progress -> OverallProgressCalculator -> jobId-based
    //   overall monotonic guard -> DownloadJob.percent -> existing UI/notification.
    //
    // This replaces the old (stage label, total bytes) -> percent guard. That guard keyed its
    // floor on yt-dlp's raw, stage-relative percent, which is exactly what made it impossible to
    // combine multiple stages (video/audio/merging/finishing) into one sane 0-100 job percent --
    // each stage restarting near 0% looked like a regression to the UI. Two independent systems
    // must never both be allowed to write DownloadJob.percent, so that old guard is gone; this is
    // the only thing that computes DownloadJob.percent for a running job now.
    //
    // Two pieces of per-job state, both keyed by jobId (not by calculator instance), so they
    // outlive any single calculator and are what actually survives pause/resume:
    //
    //  - jobCalculators   : one OverallProgressCalculator per running job, holding the weighted
    //                       stage config appropriate to that job (video+audio+ffmpeg, video+audio
    //                       without ffmpeg, audio-only, or the single-stage direct-file
    //                       fallback -- see calculatorFor). Converts yt-dlp's stage-relative
    //                       percent into one overall 0-100 percent.
    //  - overallProgressGuard : jobId -> highest overall percent reached so far. This is a
    //                       SEPARATE guard from the calculator's own internal monotonic guard
    //                       (see OverallProgressCalculator's doc comment): the calculator's guard
    //                       only lives as long as that calculator instance does, while this map
    //                       is what protects monotonicity across an internal yt-dlp retry or any
    //                       other event that could hand back a lower raw stage percentage without
    //                       recreating the calculator. It always operates on the already-computed
    //                       overall percentage, never on raw yt-dlp numbers.
    //
    // Pausing a job must NOT touch either map (see pauseQueue): the same job id resumes later and
    // has to keep climbing from where it left off, even if yt-dlp restarts its current stage from
    // a lower raw percentage after resume. Retrying from the UI always creates a brand new job id
    // (see retryJob/enqueueDownload), so it naturally starts both maps fresh -- no explicit reset
    // needed. Every real terminal path (completed, skipped, cancelled, failed) removes both
    // entries for that job id via clearOverallProgressState.
    // ------------------------------------------------------------------
    private val jobCalculators = mutableMapOf<String, OverallProgressCalculator>()
    private val overallProgressGuard = mutableMapOf<String, Float>()

    // jobId -> whether the calculator currently cached in jobCalculators for that job was built
    // for the direct-file fallback (DIRECT_FILE config) or a normal multi-stage download. Needed
    // only to detect the one real-time transition described in calculatorFor's doc comment: a
    // job that already produced some normal progress before yt-dlp fell back to the direct-file
    // path. Cleared together with jobCalculators in clearOverallProgressState.
    private val jobCalculatorIsDirectFile = mutableMapOf<String, Boolean>()

    /**
     * Returns this job's calculator, creating it (with the right weighted config) on first use
     * and reusing the same instance for every later update -- including after resume, since the
     * job id does not change and this map is never cleared on pause.
     */
    private fun calculatorFor(jobId: String, isDirectFile: Boolean, audioOnly: Boolean, ffmpegAvailable: Boolean): OverallProgressCalculator {
        val existing = jobCalculators[jobId]
        if (existing != null) {
            // Edge case: normal yt-dlp attempts can already have produced some progress -- and
            // therefore already created a normally-weighted calculator for this job -- before
            // the real-time signal reports that the direct-file fallback has actually started
            // (yt-dlp only falls back after its own attempts fail). isDirectFile flowing from
            // false -> true for a job whose calculator already exists means exactly that
            // transition, once, and the cached calculator is wrong for what the job is doing
            // from now on, so it is replaced here with a fresh DIRECT_FILE one. This never fires
            // the other direction (a direct-file job never becomes non-direct-file), and after
            // the replacement this branch is not hit again for the same job, since
            // jobCalculatorIsDirectFile is updated at the same time. The separate
            // overallProgressGuard floor is untouched, so the percent shown in the UI still
            // cannot move backwards even though the calculator itself restarts from 0 internally.
            if (isDirectFile && jobCalculatorIsDirectFile[jobId] != true) {
                val fresh = OverallProgressCalculator(ProgressConfig.DIRECT_FILE)
                jobCalculators[jobId] = fresh
                jobCalculatorIsDirectFile[jobId] = true
                return fresh
            }
            return existing
        }

        val config = when {
            // Direct-file fallback (e.g. TikTok's single combined stream): one undivided
            // 0-100% stage. isDirectFile comes from YtDlpEngine's real-time signal (see
            // downloadVideo's onProgress usage in runDownload), never from a URL/hostname guess.
            isDirectFile -> ProgressConfig.DIRECT_FILE
            audioOnly -> ProgressConfig.AUDIO_ONLY
            ffmpegAvailable -> ProgressConfig.VIDEO_AUDIO_FFMPEG
            else -> ProgressConfig.VIDEO_AUDIO_NO_FFMPEG
        }
        jobCalculatorIsDirectFile[jobId] = isDirectFile
        return OverallProgressCalculator(config).also { jobCalculators[jobId] = it }
    }

    /**
     * Applies the jobId -> highest-overall-percent-reached floor described above.
     *
     * Normally this only ever raises the floor (`maxOf`), which is what protects e.g. the
     * direct-file fallback's fresh calculator restarting its own internal percent from 0 -- see
     * calculatorFor's doc comment. The one deliberate exception is [wasTotalRevision]: when
     * OverallProgressCalculator reports that this update corrected a stale denominator (an
     * HLS/DASH total_bytes_estimate revision -- see its own doc comment), the old floor was
     * computed against that stale total and is itself wrong, so it is replaced with the
     * corrected value instead of clamping it back up. This is the only path in DownloadManager
     * that can lower this guard's floor, and it only fires on the calculator's explicit signal
     * -- never merely because a calculator is new (a fresh calculator's first update always
     * reports wasTotalRevision = false; see OverallProgressCalculator.update).
     */
    private fun guardedOverallPercent(jobId: String, overallPercent: Float, wasTotalRevision: Boolean): Float {
        val clamped = overallPercent.coerceIn(0f, 100f)
        val guarded = if (wasTotalRevision) {
            clamped
        } else {
            val floor = overallProgressGuard[jobId] ?: 0f
            maxOf(floor, clamped)
        }
        overallProgressGuard[jobId] = guarded
        return guarded
    }

    /**
     * Maps yt-dlp/the Python helper's free-text stage label onto the fixed stage keys
     * ProgressConfig understands ("video" / "audio" / "merging" / "finishing"). This is
     * deliberately forgiving of either short raw tokens or the human-readable labels described
     * where stageLabel is built below ("Downloading video", "Merging", ...): it matches on
     * keyword, not exact string. A label that matches nothing known falls through unchanged, and
     * OverallProgressCalculator safely ignores unknown stage names (no crash, no invented
     * weight; see its own doc comment) -- so an unexpected label never corrupts overall percent.
     */
    private fun normalizeStageKey(rawStage: String): String {
        val lower = rawStage.lowercase(Locale.US)
        return when {
            "merg" in lower -> "merging"
            "finish" in lower -> "finishing"
            "audio" in lower -> "audio"
            "video" in lower -> "video"
            else -> lower
        }
    }

    /** Removes every piece of per-job progress state. Called on every real terminal path. */
    private fun clearOverallProgressState(jobId: String) {
        jobCalculators.remove(jobId)
        jobCalculatorIsDirectFile.remove(jobId)
        overallProgressGuard.remove(jobId)
    }

    private fun isAudioOnly(format: String): Boolean =
        format.trim().lowercase(Locale.US) in AUDIO_ONLY_FORMATS

    fun init(context: Context) {
        appContext = context.applicationContext

        // Notification channels must exist before any notification is posted (Android 8+).
        DownloadNotifier.createChannels(appContext)

        val defaultFolder = FileUtils.getDefaultDownloadFolder(appContext)
        val prefs = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        val currentSettings = AppSettings(
            maxConcurrent = prefs.getInt("max_concurrent", 2),
            stallTimeout = prefs.getInt("stall_timeout", 120),
            notificationsEnabled = prefs.getBoolean("notifications", true),
            downloadFolder = prefs.getString("download_folder", defaultFolder) ?: defaultFolder,
            lastReferer = prefs.getString("last_referer", "") ?: "",
            lastUserAgent = prefs.getString("last_user_agent", "") ?: "",
            duplicatePolicy = prefs.getString("duplicate_policy", "rename") ?: "rename",
            downloadSubtitles = prefs.getBoolean("download_subtitles", false),
            embedMetadata = prefs.getBoolean("embed_metadata", false)
        )
        _settings.value = currentSettings

        loadHistory()

        // A killed app can leave an "in progress" notification behind. If nothing is running
        // (init() is also called again when the screen rotates), remove such leftovers.
        val nothingRunning = synchronized(this) { runningJobIds.isEmpty() }
        if (nothingRunning) DownloadNotifier.clearStaleOngoing(appContext)

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
        // Same lock as the code that posts notifications, so a notification cannot slip in
        // right after the toggle was switched off.
        synchronized(this) {
            val wasEnabled = _settings.value.notificationsEnabled
            _settings.value = _settings.value.copy(
                maxConcurrent = maxConcurrent,
                stallTimeout = stallTimeout,
                notificationsEnabled = notifications
            )
            saveSettings()

            // Switched off: take away everything that is currently showing.
            if (wasEnabled && !notifications) DownloadNotifier.cancelAll(appContext)
        }
        checkAndDispatch()
    }

    fun updateDownloadDefaults(duplicatePolicy: String, subtitles: Boolean, embedMetadata: Boolean) {
        _settings.value = _settings.value.copy(
            duplicatePolicy = duplicatePolicy,
            downloadSubtitles = subtitles,
            embedMetadata = embedMetadata
        )
        saveSettings()
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
            .putString("duplicate_policy", s.duplicatePolicy)
            .putBoolean("download_subtitles", s.downloadSubtitles)
            .putBoolean("embed_metadata", s.embedMetadata)
            .apply()
    }

    fun enqueueDownload(
        url: String,
        title: String = "",
        thumbnailUrl: String = "",
        format: String,
        quality: String,
        duplicatePolicy: String,
        subtitles: Boolean,
        embedMeta: Boolean,
        referer: String?,
        userAgent: String?,
        audioFormatId: String? = null
    ) {
        val job = DownloadJob(
            id = UUID.randomUUID().toString().take(12),
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            format = format,
            quality = quality,
            duplicatePolicy = duplicatePolicy,
            audioFormatId = audioFormatId,
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
        userAgent: String?,
        audioFormatId: String? = null
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
                        audioFormatId = audioFormatId,
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
        userAgent: String?,
        audioFormatId: String? = null
    ) {
        val list = _queuedJobs.value.toMutableList()

        // Only entries that will really be downloaded get a number, so excluded (or URL-less)
        // videos never leave gaps. The list order is the download order (the user may have
        // reordered it), and the zero-padding width is based on how many are being downloaded.
        val toDownload = entries.filter { it.included && it.url.isNotBlank() }
        val pad = toDownload.size.toString().length

        toDownload.forEachIndexed { index, entry ->
            list.add(
                DownloadJob(
                    id = UUID.randomUUID().toString().take(12),
                        url = entry.url,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnail,
                    format = format,
                    quality = quality,
                    duplicatePolicy = duplicatePolicy,
                    audioFormatId = audioFormatId,
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
        _queuedJobs.value = list
        saveLastHeaders(referer ?: "", userAgent ?: "")
        checkAndDispatch()
    }

    /**
     * Pauses the queue: stops new jobs from being dispatched AND halts every job that is
     * currently running. There is no way to suspend the underlying yt-dlp/ffmpeg process in
     * place, so a running job is cancelled (same mechanism as [cancelJob]) and put back at the
     * front of the queue with status "paused". Its progress fields (percent/downloaded/total)
     * are kept so the row does not visually reset, and QueueFragment's monotonic-percent guard
     * additionally ensures the shown percent never moves backwards once resumed. Most sources
     * let yt-dlp resume the partially written file rather than starting over from 0 bytes.
     */
    fun pauseQueue() {
        synchronized(this) {
            if (_isPaused.value) return
            _isPaused.value = true

            if (runningJobIds.isEmpty()) return

            val pausedJobs = mutableListOf<DownloadJob>()
            for (id in runningJobIds.toList()) {
                runningCoroutines[id]?.cancel()
                runningCoroutines.remove(id)
                DownloadNotifier.cancel(appContext, id)

                val job = activeJobsMap.remove(id) ?: continue
                job.status = "paused"
                job.stage = "Paused"
                job.speed = "—"
                job.eta = "—"
                pausedJobs.add(job)
            }
            runningJobIds.clear()
            updateActiveList()
            DownloadService.stop(appContext)

            // Put the paused jobs back at the head of the queue so they are the first to resume.
            _queuedJobs.value = pausedJobs + _queuedJobs.value
        }
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
                // Percent is deliberately left untouched -- cancelled jobs preserve wherever
                // progress had reached.
                addToHistory(job)
                activeJobsMap.remove(jobId)
                runningJobIds.remove(jobId)
                clearOverallProgressState(jobId)
                updateActiveList()
                DownloadNotifier.cancel(appContext, jobId)
            }
        }
        checkAndDispatch()
    }

    fun retryJob(job: DownloadJob) {
        enqueueDownload(
            url = job.url,
            title = job.title,
            thumbnailUrl = job.thumbnailUrl,
            format = job.format,
            quality = job.quality,
            duplicatePolicy = job.duplicatePolicy,
            subtitles = job.subtitles,
            embedMeta = job.embedMeta,
            referer = job.referer,
            userAgent = job.userAgent,
            audioFormatId = job.audioFormatId
        )
    }

    fun clearHistory() {
        _historyJobs.value.forEach { ThumbnailStore.delete(appContext, it.id) }
        _historyJobs.value = emptyList()
        saveHistory()
    }

    fun removeHistoryItem(jobId: String) {
        val remaining = _historyJobs.value.filterNot { it.id == jobId }
        if (remaining.size == _historyJobs.value.size) return
        _historyJobs.value = remaining
        ThumbnailStore.delete(appContext, jobId)
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
            notifyProgress(job) // "download started"

            val jobCoroutine = scope.launch {
                executeJob(job)
            }
            runningCoroutines[job.id] = jobCoroutine
        }

        // A foreground service is what stops Android from freezing/killing this process
        // once the app goes to the background or the screen locks. Keep it running for
        // exactly as long as there is at least one active job, and let it go otherwise.
        if (runningJobIds.isNotEmpty()) {
            DownloadService.ensureStarted(appContext)
        } else {
            DownloadService.stop(appContext)
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

        val audioOnly = isAudioOnly(job.format)
        val downloadFormat = when (job.format) {
            "webm" -> "webm"
            "mp3" -> "mp3"
            else -> "mp4"
        }

        // Duplicate handling runs here, BEFORE yt-dlp is started (see resolveOutputFile).
        when (val plan = resolveOutputFile(targetDir, baseFilename, downloadFormat, job.duplicatePolicy)) {
            is OutputPlan.Skip -> finishWithoutDownload(
                job = job,
                success = true,
                stage = "Skipped",
                message = plan.message,
                filePath = plan.existingPath
            )
            is OutputPlan.Fail -> finishWithoutDownload(
                job = job,
                success = false,
                stage = "Failed",
                message = plan.message
            )
            is OutputPlan.Download -> try {
                runDownload(job, plan.file, downloadFormat, audioOnly)
            } finally {
                releaseOutputFile(plan.file)
            }
        }
    }

    // ------------------------------------------------------------------
    // Duplicate handling
    //
    //  skip      : file exists            -> do not start yt-dlp, job completes as "skipped"
    //  rename    : file exists            -> "name (1).ext", "name (2).ext", ... first free name
    //  overwrite : always                 -> delete the old file and any .part / .ytdl leftovers
    //
    // Only applies when the real file name is known before the download starts, i.e. a title
    // was supplied (single video and playlist entries). Batch jobs have no title yet, so their
    // name is a yt-dlp template ("%(title)s") and they keep the old behaviour (known gap).
    // ------------------------------------------------------------------

    private const val MSG_ALREADY_EXISTS = "File already exists \u2014 skipped"
    private const val MSG_ALREADY_RUNNING = "Same file is already being downloaded by another job \u2014 skipped"

    private sealed class OutputPlan {
        /** Download to [file]. [overwrite] means old files must be cleared out first. */
        class Download(val file: File, val overwrite: Boolean = false) : OutputPlan()
        class Skip(val message: String, val existingPath: String) : OutputPlan()
        class Fail(val message: String) : OutputPlan()
    }

    /**
     * Output files that running jobs are writing right now (lower-cased paths). Without this,
     * two jobs with the same title could both see "no file yet" and write to the same name.
     * Guarded by synchronized(this).
     */
    private val reservedOutputPaths = mutableSetOf<String>()

    private fun pathKey(file: File): String = file.absolutePath.lowercase(Locale.US)

    /** Must be called inside synchronized(this). */
    private fun reserve(file: File, overwrite: Boolean = false): OutputPlan.Download {
        reservedOutputPaths.add(pathKey(file))
        return OutputPlan.Download(file, overwrite)
    }

    private fun releaseOutputFile(file: File) {
        synchronized(this) { reservedOutputPaths.remove(pathKey(file)) }
    }

    private fun resolveOutputFile(dir: File, baseName: String, ext: String, policy: String): OutputPlan {
        val desired = File(dir, "$baseName.$ext")

        // The name is still a yt-dlp template, so we cannot know what file will be produced.
        if (baseName.contains("%(")) return OutputPlan.Download(desired)

        // Decide and reserve under one lock so concurrent jobs cannot pick the same name.
        val plan: OutputPlan = synchronized(this) {
            val runningElsewhere = pathKey(desired) in reservedOutputPaths
            val exists = desired.exists()

            when (policy) {
                "skip" -> when {
                    runningElsewhere -> OutputPlan.Skip(MSG_ALREADY_RUNNING, "")
                    exists -> OutputPlan.Skip(MSG_ALREADY_EXISTS, desired.absolutePath)
                    else -> reserve(desired)
                }

                // Never wipe files that another running job is still writing.
                "overwrite" -> if (runningElsewhere) {
                    OutputPlan.Skip(MSG_ALREADY_RUNNING, "")
                } else {
                    reserve(desired, overwrite = true)
                }

                // "rename" (also the fallback for any unknown / old value): keep every file.
                else -> {
                    var candidate = desired
                    var n = 1
                    while (candidate.exists() || pathKey(candidate) in reservedOutputPaths) {
                        candidate = File(dir, "$baseName ($n).$ext")
                        n++
                    }
                    reserve(candidate)
                }
            }
        }

        // File deletion happens outside the lock; the name is already reserved for this job.
        if (plan is OutputPlan.Download && plan.overwrite) {
            if (!deleteExistingOutput(dir, baseName, plan.file)) {
                releaseOutputFile(plan.file)
                return OutputPlan.Fail("Could not delete the existing file, so it was not overwritten")
            }
        }
        return plan
    }

    /**
     * Deletes [file] plus yt-dlp's unfinished-download leftovers for the same name:
     * "name.ext.part", "name.ext.ytdl" and the per-stream forms "name.f137.mp4.part" / ".ytdl".
     * Returns false only if the real file exists and could not be deleted.
     */
    private fun deleteExistingOutput(dir: File, baseName: String, file: File): Boolean {
        if (file.exists() && !file.delete()) return false

        val leftover = Regex(
            "^" + Regex.escape(baseName) + "(?:\\.f[^.]+)?\\.[A-Za-z0-9]{1,5}\\.(?:part|ytdl)\$",
            RegexOption.IGNORE_CASE
        )
        dir.listFiles()?.forEach { candidate ->
            if (candidate.isFile && leftover.matches(candidate.name)) candidate.delete()
        }
        return true
    }

    /** Ends a job that never started yt-dlp (skipped, or failed while preparing). */
    private fun finishWithoutDownload(
        job: DownloadJob,
        success: Boolean,
        stage: String,
        message: String,
        filePath: String = ""
    ) {
        synchronized(this) {
            runningJobIds.remove(job.id)
            runningCoroutines.remove(job.id)
            activeJobsMap.remove(job.id)
            updateActiveList()

            job.status = if (success) "completed" else "failed"
            job.stage = stage
            job.error = message
            if (success) {
                // Skipped (a completed-without-downloading job): show 100% before cleanup.
                job.percent = 100f
                job.filePath = filePath
            }
            // Failed-before-download: percent is left untouched, preserving wherever it was.
            clearOverallProgressState(job.id)
            addToHistory(job)
            notifyFinished(job)
        }
        checkAndDispatch()
    }

    /** The actual yt-dlp download and everything that follows it. */
    private suspend fun runDownload(job: DownloadJob, outputFile: File, downloadFormat: String, audioOnly: Boolean) {
        val ffmpegPath = FFmpegHelper.getFFmpegExecutablePath(appContext)

        // audioOnly/ffmpegAvailable are stable for the whole job (the format doesn't change;
        // ffmpeg availability doesn't change mid-job). Whether this job is using the direct-file
        // fallback is NOT decided up front: it is only known once YtDlpEngine's real-time signal
        // reports it on a progress event (progress.isDirectFile below), since Python itself does
        // not know until the normal yt-dlp attempts have already failed.
        val ffmpegAvailable = !ffmpegPath.isNullOrBlank()

        val result = YtDlpEngine.downloadVideo(
            url = job.url,
            outputPath = outputFile.absolutePath,
            formatType = downloadFormat,
            quality = job.quality,
            subtitles = job.subtitles,
            embedMeta = job.embedMeta,
            referer = job.referer,
            userAgent = job.userAgent,
            ffmpegPath = ffmpegPath,
            audioFormatId = job.audioFormatId
        ) { progress ->
            synchronized(this) {
                val current = activeJobsMap[job.id] ?: return@downloadVideo
                // The Python helper tags every update with its own stage (e.g. "Downloading
                // video" / "Downloading audio" / "Merging" / "Finishing") and a percent that is
                // relative to that stage only. Fall back to the old generic label if a stage is
                // ever missing (e.g. from an older cached helper module).
                val stageLabel = progress.stage.ifBlank {
                    if (progress.status == "downloading") "Downloading" else progress.status
                }
                current.status = progress.status

                // yt-dlp raw stage progress -> OverallProgressCalculator -> jobId-based overall
                // monotonic guard -> DownloadJob.percent (see the pipeline doc comment above).
                // This is the ONLY place that writes DownloadJob.percent for a running job now.
                // progress.isDirectFile is YtDlpEngine's real-time signal: false for every event
                // until (and unless) the Python side actually enters the direct-file fallback
                // path, then true for the rest of this invocation -- see the doc comment on
                // calculatorFor for how a job that already has a normal calculator handles that
                // transition.
                val calcStageKey = if (progress.isDirectFile) "video" else normalizeStageKey(progress.stage)
                val calculator = calculatorFor(job.id, progress.isDirectFile, audioOnly, ffmpegAvailable)
                // progress.totalBytes is the raw denominator the Python helper used to compute
                // progress.percent for this update (0 when unknown). The calculator uses it to
                // tell a legitimate total_bytes_estimate revision apart from ordinary jitter --
                // see OverallProgressCalculator.update and ProgressUpdateResult.
                val update = calculator.update(calcStageKey, progress.percent, progress.totalBytes)
                current.percent = guardedOverallPercent(job.id, update.overallPercent, update.wasTotalRevision)

                current.downloaded = progress.downloaded
                current.total = progress.total
                current.speed = progress.speed
                current.eta = progress.eta
                current.stage = stageLabel
                if (progress.error.isNotBlank()) {
                    current.error = progress.error
                }
                updateActiveList()
                notifyProgress(current)
            }
        }

        // ---- Universal-compatibility re-encode (video only) ----
        var finalPath = result.filePath ?: outputFile.absolutePath
        var conversionWarning = ""
        if (result.success && downloadFormat == "mp4" && ENABLE_UNIVERSAL_REENCODE) {
            val conversion = convertToUniversalMp4(job.id, File(finalPath), ffmpegPath)
            val convertedFile = conversion.file
            if (convertedFile != null) {
                finalPath = convertedFile.absolutePath
            } else {
                // Keep the original download, but tell the user the conversion did not happen.
                conversionWarning = "Saved, but conversion to universal MP4 failed: ${conversion.error}"
            }
        }

        // Tell Android about the new file so it shows up in the Gallery / Photos apps.
        if (result.success) {
            try {
                MediaScannerConnection.scanFile(appContext, arrayOf(finalPath), null, null)
            } catch (_: Exception) {
            }
        }

        synchronized(this) {
            runningJobIds.remove(job.id)
            runningCoroutines.remove(job.id)
            activeJobsMap.remove(job.id)
            // Free the file name now, before checkAndDispatch() below can start a queued job
            // with the same name (the finally in executeJob is only a safety net).
            reservedOutputPaths.remove(pathKey(outputFile))
            updateActiveList()

            if (result.success) {
                // Completed: show 100% before cleanup.
                job.status = "completed"
                job.stage = "Completed"
                job.percent = 100f
                job.filePath = finalPath
                if (conversionWarning.isNotBlank()) {
                    job.error = conversionWarning
                }
            } else {
                // Failed: percent is left untouched, preserving wherever it was.
                job.status = "failed"
                job.stage = "Failed"
                if (!result.error.isNullOrBlank()) {
                    job.error = result.error
                }
            }
            clearOverallProgressState(job.id)
            addToHistory(job)
            notifyFinished(job)
        }

        checkAndDispatch()
    }

    // ------------------------------------------------------------------
    // Universal MP4 conversion
    //
    //  Container : MP4, fast start (moov atom at the front)
    //  Video     : H.264 High profile, Level 4.1, 8-bit yuv420p, square pixels,
    //              30 fps (or 24 / 23.976 fps if that is what the source is)
    //  Audio     : AAC-LC, stereo, 48 kHz, 160 kbps
    // ------------------------------------------------------------------

    private data class ConversionResult(val file: File?, val error: String = "")

    private data class MediaInfo(val fps: Double?, val durationSec: Double?)

    private fun setStage(jobId: String, status: String, stage: String, percent: Float) {
        synchronized(this) {
            val current = activeJobsMap[jobId] ?: return
            current.status = status
            current.stage = stage
            // setStage's only caller is the universal re-encode ("converting") path, which is
            // currently disabled (ENABLE_UNIVERSAL_REENCODE = false) and is therefore never
            // actually invoked. "converting" is deliberately not one of OverallProgressCalculator's
            // known stage names -- it is out of scope for this integration, and inventing a
            // weighted slice for it here would be exactly the kind of fake progress requirement 6
            // warns against. This still clamps the raw percent and applies the same job-level
            // monotonic floor as the main pipeline, so percent can't regress if this path is ever
            // re-enabled, but it does not run it through a calculator.
            current.percent = guardedOverallPercent(jobId, percent, wasTotalRevision = false)
            updateActiveList()
            notifyProgress(current)
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    //
    // These two wrappers are the ONLY place that reads the "Download notifications" setting.
    // Always call them inside synchronized(this), after the job's state has been updated.
    // ------------------------------------------------------------------

    private fun notifyProgress(job: DownloadJob) {
        if (_settings.value.notificationsEnabled) DownloadNotifier.showProgress(appContext, job)
    }

    private fun notifyFinished(job: DownloadJob) {
        if (_settings.value.notificationsEnabled) DownloadNotifier.showFinished(appContext, job)
    }

    /** Runs a process, feeds each output line (stdout + stderr merged) to onLine, returns the exit code. */
    private suspend fun runProcess(args: List<String>, onLine: (String) -> Unit): Int = coroutineScope {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        // If this job is cancelled, kill ffmpeg so the read loop below ends.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                process.destroy()
            }
        }
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            process.waitFor()
        } finally {
            watcher.cancel()
        }
    }

    /** Asks ffmpeg to describe the file so we can read its frame rate and length. */
    private suspend fun probe(ffmpegPath: String, file: File): MediaInfo {
        val out = StringBuilder()
        try {
            runProcess(listOf(ffmpegPath, "-hide_banner", "-nostdin", "-i", file.absolutePath)) {
                out.appendLine(it)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val text = out.toString()

        val duration = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(text)?.let {
            val h = it.groupValues[1].toDouble()
            val m = it.groupValues[2].toDouble()
            val s = it.groupValues[3].toDouble()
            h * 3600 + m * 60 + s
        }

        val videoLine = text.lineSequence().firstOrNull { it.contains("Video:") }
        val fps = videoLine?.let {
            Regex("""(\d+(?:\.\d+)?)\s+fps""").find(it)?.groupValues?.get(1)?.toDoubleOrNull()
        }
        return MediaInfo(fps, duration)
    }

    /** Only 24, 23.976 and 30 fps are allowed. Everything else becomes 30. */
    private fun pickFrameRate(source: Double?): String = when {
        source == null -> "30"
        abs(source - 23.976) < 0.02 -> "24000/1001"
        abs(source - 24.0) < 0.02 -> "24"
        else -> "30"
    }

    private suspend fun convertToUniversalMp4(jobId: String, input: File, ffmpegPath: String?): ConversionResult {
        if (ffmpegPath.isNullOrBlank()) return ConversionResult(null, "ffmpeg program was not found in the app")
        if (!input.exists()) return ConversionResult(null, "downloaded file not found")
        val dir = input.parentFile ?: return ConversionResult(null, "invalid folder")

        setStage(jobId, "converting", "Converting to universal MP4", 0f)

        val info = probe(ffmpegPath, input)
        val fps = pickFrameRate(info.fps)

        val base = input.nameWithoutExtension
        val tmp = File(dir, "$base.compat.tmp")

        // Pick a final name that does not overwrite a different existing file.
        var finalFile = File(dir, "$base.mp4")
        var n = 1
        while (finalFile.exists() && finalFile.absolutePath != input.absolutePath) {
            finalFile = File(dir, "$base ($n).mp4")
            n++
        }

        // 1) make pixels square   2) fit inside 1920x1080 (or 1080x1920 for portrait) without upscaling
        // 3) force even width/height   4) fixed frame rate   5) 8-bit 4:2:0
        val videoFilter = listOf(
            "scale=iw*sar:ih",
            "setsar=1",
            "scale='min(iw,if(gt(iw,ih),1920,1080))':'min(ih,if(gt(iw,ih),1080,1920))':force_original_aspect_ratio=decrease",
            "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            "setsar=1",
            "fps=$fps",
            "format=yuv420p"
        ).joinToString(",")

        val args = listOf(
            ffmpegPath, "-hide_banner", "-nostdin", "-y",
            "-loglevel", "error", "-nostats", "-progress", "pipe:1",
            "-i", input.absolutePath,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-map_metadata", "0",
            "-vf", videoFilter,
            "-c:v", "libx264", "-profile:v", "high", "-level:v", "4.1",
            "-preset", "veryfast", "-crf", "21",
            "-maxrate", "20M", "-bufsize", "40M",
            "-pix_fmt", "yuv420p", "-tag:v", "avc1",
            "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "160k", "-ac", "2", "-ar", "48000",
            "-movflags", "+faststart",
            "-f", "mp4", tmp.absolutePath
        )

        var succeeded = false
        try {
            val progressLine = Regex("^[A-Za-z_0-9]+=\\S*$")
            val errorTail = ArrayDeque<String>()
            var lastPercent = -1

            val exitCode = runProcess(args) { line ->
                when {
                    line.startsWith("out_time_ms=") -> {
                        val micros = line.substringAfter("=").trim().toLongOrNull()
                        val total = info.durationSec
                        if (micros != null && total != null && total > 0) {
                            val pct = ((micros / 1_000_000.0) / total * 100).toInt().coerceIn(0, 99)
                            if (pct != lastPercent) {
                                lastPercent = pct
                                setStage(jobId, "converting", "Converting to universal MP4", pct.toFloat())
                            }
                        }
                    }
                    progressLine.matches(line.trim()) -> Unit
                    line.isNotBlank() -> {
                        errorTail.addLast(line.trim())
                        if (errorTail.size > 4) errorTail.removeFirst()
                    }
                }
            }

            if (exitCode != 0 || !tmp.exists() || tmp.length() == 0L) {
                val reason = errorTail.joinToString(" ").ifBlank { "ffmpeg exited with code $exitCode" }
                return ConversionResult(null, reason)
            }

            if (finalFile.exists()) finalFile.delete() // only ever the original input file
            if (!tmp.renameTo(finalFile)) {
                tmp.copyTo(finalFile, overwrite = true)
                tmp.delete()
            }
            if (input.absolutePath != finalFile.absolutePath) {
                input.delete()
            }
            succeeded = true
            return ConversionResult(finalFile)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ConversionResult(null, e.message ?: "unknown error")
        } finally {
            if (!succeeded) tmp.delete()
        }
    }

    private fun updateActiveList() {
        _activeJobs.value = activeJobsMap.values.map { it.copy() }
    }

    private fun addToHistory(job: DownloadJob) {
        val currentHistory = _historyJobs.value.toMutableList()
        currentHistory.add(0, job.copy())
        if (currentHistory.size > 300) {
            ThumbnailStore.delete(appContext, currentHistory.removeAt(currentHistory.size - 1).id)
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
                obj.put("thumbnailUrl", j.thumbnailUrl)
                obj.put("format", j.format)
                obj.put("quality", j.quality)
                obj.put("duplicatePolicy", j.duplicatePolicy)
                obj.put("audioFormatId", j.audioFormatId ?: "")
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
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        format = obj.optString("format", "mp4"),
                        quality = obj.optString("quality", "best"),
                        duplicatePolicy = obj.optString("duplicatePolicy", "rename"),
                        audioFormatId = obj.optString("audioFormatId", "").ifEmpty { null },
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
