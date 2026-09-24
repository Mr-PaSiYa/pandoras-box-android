package com.pandorasbox.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class QueueAdapter(
    private val onCancelClick: (String) -> Unit
) : ListAdapter<DownloadJob, QueueAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvSub: TextView = view.findViewById(R.id.tv_sub)
        val tvStatusPill: TextView = view.findViewById(R.id.tv_status_pill)
        val progressBar: ProgressBar = view.findViewById(R.id.progress_bar)
        val tvPercent: TextView = view.findViewById(R.id.tv_percent)
        val tvDownloadedTotal: TextView = view.findViewById(R.id.tv_downloaded_total)
        val tvSpeed: TextView = view.findViewById(R.id.tv_speed)
        val tvEta: TextView = view.findViewById(R.id.tv_eta)
        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel)
        var boundJobId: String? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // A row that is reused for a different job must not keep a leftover slide/drop offset.
        // (Same-job rebinds are left alone so a running animation is not interrupted.)
        if (holder.boundJobId != item.id) resetRowTransform(holder.itemView)

        holder.tvTitle.text = item.title.ifBlank { item.url }

        val fmtStr = item.format.uppercase()
        val playlistTag = if (!item.playlistTitle.isNullOrBlank()) " · ${item.playlistTitle}" else ""
        holder.tvSub.text = "$fmtStr$playlistTag · ${item.stage}"

        when (item.status) {
            "downloading" -> {
                holder.tvStatusPill.text = "DOWNLOADING"
                holder.tvStatusPill.setTextColor(0xFF818CF8.toInt())
            }
            "starting" -> {
                holder.tvStatusPill.text = "STARTING"
                holder.tvStatusPill.setTextColor(0xFF06B6D4.toInt())
            }
            "queued" -> {
                holder.tvStatusPill.text = "QUEUED"
                holder.tvStatusPill.setTextColor(0xFFF59E0B.toInt())
            }
            "paused" -> {
                holder.tvStatusPill.text = "PAUSED"
                holder.tvStatusPill.setTextColor(0xFF94A3B8.toInt())
            }
            "completed" -> {
                holder.tvStatusPill.text = "COMPLETED"
                holder.tvStatusPill.setTextColor(0xFF22C55E.toInt())
            }
            else -> {
                holder.tvStatusPill.text = item.status.uppercase()
                holder.tvStatusPill.setTextColor(0xFF9CA3AF.toInt())
            }
        }

        val pct = item.percent.toInt()
        // Animate only when the same job updates; snap when a recycled row shows a different job.
        val sameJob = holder.boundJobId == item.id
        holder.boundJobId = item.id
        holder.progressBar.setProgress(pct, sameJob)
        holder.tvPercent.text = "$pct%"
        holder.tvDownloadedTotal.text = "${item.downloaded} / ${item.total}"
        holder.tvSpeed.text = item.speed
        holder.tvEta.text = if (item.eta != "—") "ETA ${item.eta}" else "—"

        // A finished row is on its way out: hide (not remove) Cancel so the row height stays put.
        holder.btnCancel.visibility = if (item.status == "completed") View.INVISIBLE else View.VISIBLE
        holder.btnCancel.setOnClickListener {
            onCancelClick(item.id)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        resetRowTransform(holder.itemView)
        holder.boundJobId = null
    }

    private fun resetRowTransform(view: View) {
        view.animate().cancel()
        view.translationX = 0f
        view.translationY = 0f
        view.alpha = 1f
    }

    object DiffCallback : DiffUtil.ItemCallback<DownloadJob>() {
        override fun areItemsTheSame(oldItem: DownloadJob, newItem: DownloadJob): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: DownloadJob, newItem: DownloadJob): Boolean {
            return oldItem == newItem
        }
    }
}