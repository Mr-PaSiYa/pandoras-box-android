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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvTitle.text = item.title.ifBlank { item.url }

        val fmtStr = item.format.uppercase()
        val playlistTag = if (!item.playlistTitle.isNullOrBlank()) " · ${item.playlistTitle}" else ""
        holder.tvSub.text = "$fmtStr$playlistTag · ${item.stage}"

        holder.tvStatusPill.text = item.status.uppercase()
        holder.progressBar.progress = item.percent.toInt()
        holder.tvPercent.text = "${item.percent.toInt()}%"
        holder.tvDownloadedTotal.text = "${item.downloaded} / ${item.total}"
        holder.tvSpeed.text = item.speed
        holder.tvEta.text = if (item.eta != "—") "ETA ${item.eta}" else "—"

        holder.btnCancel.setOnClickListener {
            onCancelClick(item.id)
        }
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
