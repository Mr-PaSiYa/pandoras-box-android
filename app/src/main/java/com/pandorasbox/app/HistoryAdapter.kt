package com.pandorasbox.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class HistoryAdapter(
    private val onOpenFile: (String) -> Unit,
    private val onOpenFolder: (String) -> Unit,
    private val onRetry: (DownloadJob) -> Unit
) : ListAdapter<DownloadJob, HistoryAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tv_history_icon)
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvSub: TextView = view.findViewById(R.id.tv_history_sub)
        val btnOpenFile: MaterialButton = view.findViewById(R.id.btn_open_file)
        val btnOpenFolder: MaterialButton = view.findViewById(R.id.btn_open_folder)
        val btnRetry: MaterialButton = view.findViewById(R.id.btn_retry)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        when (item.status) {
            "completed" -> {
                holder.tvIcon.text = "✓"
                holder.tvIcon.setTextColor(0xFF3DDC8A.toInt())
            }
            "cancelled" -> {
                holder.tvIcon.text = "×"
                holder.tvIcon.setTextColor(0xFFF0BF4C.toInt())
            }
            else -> {
                holder.tvIcon.text = "!"
                holder.tvIcon.setTextColor(0xFFFF6B6F.toInt())
            }
        }

        holder.tvTitle.text = item.title.ifBlank { "Untitled" }

        val fmtStr = item.format.uppercase()
        val playlistTag = if (!item.playlistTitle.isNullOrBlank()) " · ${item.playlistTitle}" else ""
        val errTag = if (item.error.isNotBlank()) " · ${item.error}" else ""
        holder.tvSub.text = "$fmtStr · ${item.createdAt}$playlistTag$errTag"

        val hasPath = item.filePath.isNotBlank()
        holder.btnOpenFile.isVisible = hasPath
        holder.btnOpenFolder.isVisible = hasPath
        holder.btnRetry.isVisible = item.status == "failed" || item.status == "cancelled"

        holder.btnOpenFile.setOnClickListener { onOpenFile(item.filePath) }
        holder.btnOpenFolder.setOnClickListener { onOpenFolder(item.filePath) }
        holder.btnRetry.setOnClickListener { onRetry(item) }
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
