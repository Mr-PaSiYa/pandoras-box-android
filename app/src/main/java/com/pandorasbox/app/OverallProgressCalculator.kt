package com.pandorasbox.app

/**
 * A single named phase of a download job, expressed as a slice of the overall
 * 0-100 job progress bar.
 *
 * [startPercent] is the overall percent shown when this stage is at 0%, and
 * [endPercent] is the overall percent shown when this stage is at 100%.
 */
data class ProgressStage(
    val name: String,
    val startPercent: Float,
    val endPercent: Float
)

/**
 * The fixed set of stages (and their overall-progress weights) for one kind of
 * download job. These are UX weights, not real time measurements.
 */
class ProgressConfig(val stages: List<ProgressStage>) {

    fun stageFor(name: String): ProgressStage? = stages.find { it.name == name }

    companion object {
        /** Normal video + audio, merged and finished with FFmpeg. */
        val VIDEO_AUDIO_FFMPEG = ProgressConfig(
            listOf(
                ProgressStage("video", 0f, 55f),
                ProgressStage("audio", 55f, 85f),
                ProgressStage("merging", 85f, 92f),
                ProgressStage("finishing", 92f, 100f)
            )
        )

        /** Video + audio, no FFmpeg merge/finish step available. */
        val VIDEO_AUDIO_NO_FFMPEG = ProgressConfig(
            listOf(
                ProgressStage("video", 0f, 65f),
                ProgressStage("audio", 65f, 100f)
            )
        )

        /** Audio-only job. */
        val AUDIO_ONLY = ProgressConfig(
            listOf(
                ProgressStage("audio", 0f, 85f),
                ProgressStage("finishing", 85f, 100f)
            )
        )

        /** Direct-file fallback: a single stage spans the whole job. */
        val DIRECT_FILE = ProgressConfig(
            listOf(
                ProgressStage("video", 0f, 100f)
            )
        )
    }
}

/**
 * Result of one [OverallProgressCalculator.update] call.
 *
 * [overallPercent] is the job-wide 0-100 percent to display. [wasTotalRevision] is true only
 * when this update was accepted specifically because yt-dlp corrected the denominator
 * ([totalBytes]) it uses to compute stage-relative percent -- e.g. an HLS/DASH size estimate
 * becoming more accurate mid-stage -- and is false for every ordinary update (same total,
 * unknown total, a tiny estimate refinement, a downward total change, or a calculator's very
 * first update). The caller (DownloadManager) uses this flag to decide whether its own,
 * separate jobId-level guard should replace its floor with [overallPercent] instead of
 * clamping it back up with `maxOf`.
 */
data class ProgressUpdateResult(
    val overallPercent: Float,
    val wasTotalRevision: Boolean
)

/**
 * Converts yt-dlp's stage-relative progress (0-100 within whatever stage is
 * currently running) into a single overall job progress (0-100), according to
 * the weights in [config].
 *
 * This class is a small, self-contained pure-Kotlin utility. It has no
 * dependency on DownloadManager, does no I/O, and knows nothing about jobIds,
 * coroutines, or Android. It is safe to construct one instance per running
 * job and discard it when the job finishes.
 *
 * Internal state is the last overall percent this calculator has returned, plus a per-stage
 * sticky revision anchor recording the `totalBytes` denominator each stage's percent is being
 * measured against. The percent guard is enough on its own to guarantee the calculator's own
 * output never moves backwards from ordinary stage jitter or a stage transition landing below
 * where the previous stage left off. The one case that guard must NOT apply to is a *legitimate*
 * denominator revision -- yt-dlp correcting an HLS/DASH `total_bytes_estimate` mid-stage, which
 * makes a lower percent the *correct* one, not a regression. [update] tells those two cases apart
 * using the per-stage revision anchor and a relative tolerance; see [isMeaningfulUpwardRevision].
 *
 * Note: this is a *local* monotonic guard, scoped to one calculator instance.
 * A separate global jobId -> highest overall percent guard (Stage 2, to live
 * in DownloadManager) is responsible for surviving calculator recreation --
 * e.g. pause/resume of the same job id -- while the app process stays alive.
 * Both maps are plain in-memory maps, so neither one survives the process
 * actually dying; that is out of scope here and is not something this pair
 * of classes provides.
 */
class OverallProgressCalculator(
    private val config: ProgressConfig
) {
    private var lastOverallPercent: Float = 0f
    private var hasStarted: Boolean = false

    // Sticky per-stage revision anchor: the totalBytes value new observations are compared
    // against. Unlike a "last observed" cache, this only ever moves when a genuine revision is
    // confirmed (see isMeaningfulUpwardRevision) -- it does not slide on every observation, so
    // several small upward refinements accumulate against one fixed point instead of hiding
    // their combined drift, and a value that dips and climbs back within the anchor's range is
    // never mistaken for a revision. "video" and "audio" totals are unrelated quantities, so
    // this is tracked per stage, not once per job -- otherwise a video -> audio stage transition
    // would look exactly like a denominator revision. A fresh calculator (e.g. the DIRECT_FILE
    // fallback swap-in) starts with an empty map, so its first update is always treated as
    // "first total for this stage", never a revision.
    private val stageTotals = mutableMapOf<String, Long>()

    companion object {
        // How much a stage's totalBytes has to grow, relative to that stage's sticky revision
        // anchor, before it counts as a genuine denominator revision rather than an ordinary
        // estimate refinement. yt-dlp's routine total_bytes_estimate jitter
        // for an in-progress stream is normally within a few percent of the previous estimate;
        // the kind of HLS/DASH re-estimate that caused the 55% lock (e.g. 20MB -> 40MB) is
        // typically a large fraction or a multiple of the old value. 15% sits comfortably above
        // normal noise and comfortably below a genuine re-estimate, without needing a heuristic
        // based on speed, ETA, or downloaded bytes.
        private const val TOTAL_REVISION_RELATIVE_TOLERANCE = 0.15f
    }

    /**
     * Feed in a stage-relative progress update and get back the new overall progress plus
     * whether this update was a legitimate denominator revision.
     *
     * [stagePercent] is clamped to 0..100 before use. [totalBytes] is the raw byte total
     * yt-dlp/the Python helper used as the denominator for [stagePercent]; 0 means "no
     * denominator known for this update" and is never treated as the total becoming zero.
     *
     * The returned [ProgressUpdateResult.overallPercent] is always clamped to 0..100. It never
     * moves backwards relative to the value this calculator previously returned -- except for
     * the very first update of a fresh calculator (a genuine 0% at the start of a job is valid
     * and preserved), and except when [totalBytes] represents a meaningful upward revision of
     * the stage's denominator, in which case a lower, corrected value is allowed through and
     * [ProgressUpdateResult.wasTotalRevision] is true.
     *
     * Unknown stage names are handled safely: no crash, no invented weight. The calculator
     * simply returns its current overall value unchanged, and never a revision.
     */
    fun update(stage: String, stagePercent: Float, totalBytes: Long): ProgressUpdateResult {
        val stageConfig = config.stageFor(stage)
            ?: return ProgressUpdateResult(lastOverallPercent, wasTotalRevision = false)

        val clampedStagePercent = stagePercent.coerceIn(0f, 100f)
        val span = stageConfig.endPercent - stageConfig.startPercent
        val raw = stageConfig.startPercent + span * (clampedStagePercent / 100f)
        val clampedOverall = raw.coerceIn(0f, 100f)

        // Must run even on the calculator's first-ever update, so the stage's first meaningful
        // total gets recorded as a baseline -- but a brand-new stageTotals map means this can
        // never itself report true on that first call (see isMeaningfulUpwardRevision).
        val isRevision = isMeaningfulUpwardRevision(stage, totalBytes)

        val result = when {
            !hasStarted -> clampedOverall
            isRevision -> clampedOverall // corrected value replaces the old baseline, even if lower
            else -> maxOf(lastOverallPercent, clampedOverall)
        }

        lastOverallPercent = result
        hasStarted = true
        return ProgressUpdateResult(result, isRevision)
    }

    /**
     * Updates [stage]'s revision anchor (when a genuine revision occurs) and reports whether
     * this update is a meaningful *upward* revision of it.
     *
     * [stageTotals] holds a *sticky* anchor per stage: once established, it only ever moves by
     * being replaced with a confirmed revision. It never slides to track ordinary observations,
     * whether those observations go down or creep up within tolerance. This is what lets several
     * small upward refinements accumulate against one fixed comparison point instead of each
     * being compared only to the last one -- and what stops a value that dips and climbs back
     * within the range already implied by the anchor from ever being mistaken for a revision.
     *
     * - `totalBytes <= 0`: unknown denominator. Ignored completely; anchor untouched, never a
     *   revision.
     * - No anchor exists yet for this stage: [totalBytes] becomes the anchor. Nothing to revise
     *   against yet, so this is never a revision.
     * - `totalBytes <= anchor` (equal or a downward move): not a revision, and the anchor is
     *   left exactly as it is. A total at or below the anchor only ever produces a percent at or
     *   above what's already been shown, which the normal monotonic path in [update] already
     *   allows through -- there is nothing here that needs correcting, so nothing to update.
     * - `totalBytes > anchor` by no more than [TOTAL_REVISION_RELATIVE_TOLERANCE]: an ordinary
     *   estimate refinement, not a revision. The anchor is left unchanged, so a run of several
     *   such small increases keeps being measured against the same original anchor -- letting
     *   their *cumulative* growth be caught once it genuinely exceeds tolerance, rather than
     *   each one resetting the comparison point and hiding the total drift.
     * - `totalBytes > anchor` by more than the tolerance: a meaningful revision. The anchor
     *   advances to this new, confirmed value, so the next comparison is against it.
     */
    private fun isMeaningfulUpwardRevision(stage: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0L) return false

        val anchor = stageTotals[stage]
        if (anchor == null) {
            stageTotals[stage] = totalBytes
            return false
        }

        if (totalBytes <= anchor) return false

        val isRevision = (totalBytes - anchor).toFloat() / anchor.toFloat() > TOTAL_REVISION_RELATIVE_TOLERANCE
        if (isRevision) {
            stageTotals[stage] = totalBytes
        }
        return isRevision
    }

    /** The most recent overall percent this calculator has returned (0 if none yet). */
    fun currentOverallPercent(): Float = lastOverallPercent
}
