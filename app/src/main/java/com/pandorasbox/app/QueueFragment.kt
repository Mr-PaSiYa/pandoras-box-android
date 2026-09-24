package com.pandorasbox.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private lateinit var tvSummary: TextView
    private lateinit var btnPauseQueue: MaterialButton
    private lateinit var rvQueue: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var queueAdapter: QueueAdapter

    // Highest percent shown so far per job id, so the UI never moves backwards.
    private val maxPercent = mutableMapOf<String, Float>()
    private val lastStatus = mutableMapOf<String, String>()

    // ---- Queue animation state ----------------------------------------------------------

    private class QueueState(
        val active: List<DownloadJob>,
        val queued: List<DownloadJob>,
        val history: List<DownloadJob>
    )

    /**
     * A job that just left the live lists. It stays in the Queue list for a moment so it can play
     * its exit animation. [leaving] = confirmed completed (slide right); otherwise we are still
     * waiting for the history list to tell us whether it completed, failed or was cancelled.
     */
    private class Exiting(var job: DownloadJob, val index: Int) {
        var leaving = false
        var animating = false
    }

    private val exiting = LinkedHashMap<String, Exiting>()
    private var latestState: QueueState? = null
    private var lastShown: List<DownloadJob> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSummary = view.findViewById(R.id.tv_queue_summary)
        btnPauseQueue = view.findViewById(R.id.btn_pause_queue)
        rvQueue = view.findViewById(R.id.rv_queue)
        tvEmpty = view.findViewById(R.id.tv_queue_empty)

        exiting.clear()
        lastShown = emptyList()
        latestState = null

        queueAdapter = QueueAdapter { jobId ->
            DownloadManager.cancelJob(jobId)
        }

        rvQueue.layoutManager = LinearLayoutManager(requireContext())
        rvQueue.adapter = queueAdapter
        // The finish animation is handled by hand, on the same alpha/translationX properties the
        // default ItemAnimator also touches for add/remove. Left enabled, the built-in add/remove
        // animations race our own ViewPropertyAnimator calls and reset or override them mid-flight,
        // which is why the custom animations were invisible. Zeroing add/remove/change durations
        // hands full control to our own code while still keeping a smooth "move" animation for
        // rows that just shift position (e.g. the remaining rows sliding up after one is removed).
        (rvQueue.itemAnimator as? SimpleItemAnimator)?.apply {
            supportsChangeAnimations = false
            addDuration = 0
            removeDuration = 0
            changeDuration = 0
        }

        btnPauseQueue.setOnClickListener {
            if (DownloadManager.isPaused.value) {
                DownloadManager.resumeQueue()
            } else {
                DownloadManager.pauseQueue()
            }
        }

        observeQueue()

    }

    /**
     * The downloader reports progress per stream (video, then audio, then conversion), so the raw
     * value can drop. Clamp it to the highest value seen for this job. The only reset is when the
     * job enters the "converting" phase, which is a separate step that legitimately starts at 0.
     */
    private fun monotonic(job: DownloadJob): DownloadJob {
        val enteredConverting = job.status == "converting" && lastStatus[job.id] != "converting"
        lastStatus[job.id] = job.status
        val previous = if (enteredConverting) 0f else (maxPercent[job.id] ?: 0f)
        val shown = maxOf(previous, job.percent).coerceIn(0f, 100f)
        maxPercent[job.id] = shown
        return if (shown == job.percent) job else job.copy(percent = shown)
    }

    private fun observeQueue() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                DownloadManager.activeJobs,
                DownloadManager.queuedJobs,
                DownloadManager.isPaused,
                DownloadManager.historyJobs
            ) { active, queued, paused, history ->
                Pair(QueueState(active, queued, history), paused)
            }.collect { (state, paused) ->
                latestState = state
                btnPauseQueue.text = if (paused) "Resume queue" else "Pause queue"
                btnPauseQueue.setIconResource(
                    if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
                )

                val total = state.active.size + state.queued.size
                btnPauseQueue.isVisible = total > 0
                tvEmpty.isVisible = total == 0
                tvSummary.text = if (total > 0) {
                    "${state.active.size} running · ${state.queued.size} waiting"
                } else {
                    "No active downloads."
                }

                render()
            }
        }
    }

    /** Builds the list shown in the Queue: live jobs plus any jobs still playing an exit animation. */
    private fun render() {
        val state = latestState ?: return

        val live = (state.active + state.queued).map { monotonic(it) }
        val liveIds = live.map { it.id }.toSet()

        // 1) Jobs that were on screen and are no longer live. A job that was still "queued" can
        //    only have been cancelled, so it just disappears. Anything that had started might have
        //    completed, so keep the row until history tells us.
        lastShown.forEachIndexed { index, job ->
            if (job.id in liveIds || job.id in exiting) return@forEachIndexed
            // A job that was still "queued" (or paused, which is just "queued" mid-download) can
            // only have left the live lists because it was cancelled, so it just disappears.
            if (job.status == "queued" || job.status == "paused") return@forEachIndexed
            exiting[job.id] = Exiting(job, index)
            scheduleExitTimeout(job.id)
        }

        // 2) Ask history what happened to the jobs that are waiting for an answer.
        val recentHistory = state.history.take(30)
        for ((id, exit) in exiting.entries.toList()) {
            if (exit.leaving) continue
            val entry = recentHistory.firstOrNull { it.id == id } ?: continue
            if (entry.status == "completed") {
                exit.leaving = true
                exit.job = exit.job.copy(percent = 100f, status = "completed").also {
                    it.stage = "Completed"
                }
            } else {
                exiting.remove(id) // failed / cancelled: no fly-away, just leave the list
            }
        }

        // 3) Live jobs, with the exiting rows put back at the position they had.
        val display = live.toMutableList()
        exiting.values.sortedBy { it.index }.forEach {
            display.add(minOf(it.index, display.size), it.job)
        }
        lastShown = display.map { it.copy() }

        queueAdapter.submitList(display) {
            exiting.entries.toList().forEach { (id, exit) ->
                if (exit.leaving && !exit.animating) startFinishAnimation(id)
            }
        }

        val liveShownIds = display.map { it.id }.toSet()
        maxPercent.keys.retainAll(liveShownIds)
        lastStatus.keys.retainAll(liveShownIds)
    }

    // ---- Finish animation ---------------------------------------------------------------

    private fun startFinishAnimation(id: String) {
        val exit = exiting[id] ?: return
        exit.animating = true

        val row = findRow(id)
        if (row == null) { // scrolled off screen: nothing to animate
            retire(id)
            return
        }

        row.animate().cancel()
        row.animate()
            .translationX(rvQueue.width.toFloat())
            .alpha(0f)
            .setStartDelay(150) // lets the bar visibly reach 100% first
            .setDuration(320)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction { retire(id) }
            .start()

        // Safety net in case the animation never reports its end (e.g. view detached).
        viewLifecycleOwner.lifecycleScope.launch {
            delay(900)
            retire(id)
        }
    }

    /** Removes the row from the Queue list for good (the list then collapses with the normal remove animation). */
    private fun retire(id: String) {
        if (exiting.remove(id) == null) return
        lastShown = lastShown.filter { it.id != id }
        render()
    }

    /** If history never answers (should not happen), do not leave a stale row behind. */
    private fun scheduleExitTimeout(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            delay(400)
            val exit = exiting[id]
            if (exit != null && !exit.leaving) retire(id)
        }
    }

    private fun findRow(jobId: String): View? {
        for (i in 0 until rvQueue.childCount) {
            val child = rvQueue.getChildAt(i)
            val holder = rvQueue.getChildViewHolder(child) as? QueueAdapter.ViewHolder
            if (holder?.boundJobId == jobId) return child
        }
        return null
    }

}
