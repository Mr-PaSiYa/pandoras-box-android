package com.pandorasbox.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class QueueFragment : Fragment() {

    private lateinit var tvSummary: TextView
    private lateinit var btnPauseQueue: MaterialButton
    private lateinit var rvQueue: RecyclerView
    private lateinit var queueAdapter: QueueAdapter

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

        queueAdapter = QueueAdapter { jobId ->
            DownloadManager.cancelJob(jobId)
        }

        rvQueue.layoutManager = LinearLayoutManager(requireContext())
        rvQueue.adapter = queueAdapter

        btnPauseQueue.setOnClickListener {
            if (DownloadManager.isPaused.value) {
                DownloadManager.resumeQueue()
            } else {
                DownloadManager.pauseQueue()
            }
        }

        observeQueue()
    }

    private fun observeQueue() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                DownloadManager.activeJobs,
                DownloadManager.queuedJobs,
                DownloadManager.isPaused
            ) { active, queued, paused ->
                Triple(active, queued, paused)
            }.collect { (active, queued, paused) ->
                btnPauseQueue.text = if (paused) "Resume queue" else "Pause queue"

                val total = active.size + queued.size
                tvSummary.text = if (total > 0) {
                    "${active.size} running · ${queued.size} waiting"
                } else {
                    "Nothing queued."
                }

                val combined = mutableListOf<DownloadJob>()
                combined.addAll(active)
                combined.addAll(queued)
                queueAdapter.submitList(combined)
            }
        }
    }
}
