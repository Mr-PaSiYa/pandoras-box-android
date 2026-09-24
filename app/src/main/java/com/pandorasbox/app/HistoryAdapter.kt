package com.pandorasbox.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class HistoryAdapter(
    private val lifecycleScope: CoroutineScope,
    private val onOpenFile: (String) -> Unit,
    private val onOpenFolder: (String) -> Unit,
    private val onRetry: (DownloadJob) -> Unit,
    private val onCopyLink: (String) -> Unit
) : ListAdapter<DownloadJob, HistoryAdapter.ViewHolder>(DiffCallback) {

    // Small in-memory cache so scrolling the list doesn't keep re-decoding the same
    // video frame. Keyed by file path; cleared automatically when the fragment/adapter
    // is garbage collected (process-lifetime only, nothing persisted to disk).
    private val thumbnailCache = LruCache<String, Bitmap>(40)

    private val AUDIO_ONLY_FORMATS = setOf("mp3", "m4a", "aac", "wav", "opus", "flac", "ogg")
    private fun isAudioOnly(format: String) = format.trim().lowercase(Locale.US) in AUDIO_ONLY_FORMATS

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.iv_history_thumb)
        val tvIcon: TextView = view.findViewById(R.id.tv_history_icon)
        val tvTitle: TextView = view.findViewById(R.id.tv_history_title)
        val tvSub: TextView = view.findViewById(R.id.tv_history_sub)
        val tvOptions: TextView = view.findViewById(R.id.tv_history_options)
        val btnOpenFile: MaterialButton = view.findViewById(R.id.btn_open_file)
        val btnOpenFolder: MaterialButton = view.findViewById(R.id.btn_open_folder)
        val btnRetry: MaterialButton = view.findViewById(R.id.btn_retry)
        val btnCopyLink: MaterialButton = view.findViewById(R.id.btn_copy_link)
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
                holder.tvIcon.setTextColor(0xFF10B981.toInt())
            }
            "cancelled" -> {
                holder.tvIcon.text = "✕"
                holder.tvIcon.setTextColor(0xFFF59E0B.toInt())
            }
            else -> {
                holder.tvIcon.text = "!"
                holder.tvIcon.setTextColor(0xFFEF4444.toInt())
            }
        }

        holder.tvTitle.text = item.title.ifBlank { "Untitled" }

        val hasPath = item.filePath.isNotBlank()
        val fileExists = hasPath && File(item.filePath).exists()
        val sizeStr = if (fileExists) formatFileSize(File(item.filePath).length()) else null

        val fmtStr = item.format.uppercase(Locale.US)
        val sizeTag = if (sizeStr != null) " · $sizeStr" else ""
        val playlistTag = if (!item.playlistTitle.isNullOrBlank()) " · ${item.playlistTitle}" else ""
        val errTag = if (item.error.isNotBlank()) " · ${item.error}" else ""
        val missingTag = if (hasPath && !fileExists) " · File missing" else ""
        holder.tvSub.text = "$fmtStr · ${item.createdAt}$sizeTag$playlistTag$errTag$missingTag"

        val optionsStr = formatOptions(item)
        holder.tvOptions.text = optionsStr
        holder.tvOptions.isVisible = optionsStr.isNotBlank()

        // A missing file (e.g. the user deleted it, or it was on removable storage) can
        // still be re-downloaded from the saved URL, same as a failed/cancelled job.
        val canRetry = item.status == "failed" || item.status == "cancelled" || (hasPath && !fileExists)
        val canOpen = fileExists

        holder.btnOpenFile.isVisible = canOpen
        holder.btnOpenFolder.isVisible = canOpen
        holder.btnRetry.isVisible = canRetry
        holder.btnRetry.text = if (item.status == "failed" || item.status == "cancelled") "Retry" else "Redownload"
        holder.btnCopyLink.isVisible = item.url.isNotBlank()

        holder.btnOpenFile.setOnClickListener { onOpenFile(item.filePath) }
        holder.btnOpenFolder.setOnClickListener { onOpenFolder(item.filePath) }
        holder.btnRetry.setOnClickListener { onRetry(item) }
        holder.btnCopyLink.setOnClickListener { onCopyLink(item.url) }

        loadThumbnail(holder, item)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.ivThumb.setImageDrawable(null)
    }

    private fun loadThumbnail(holder: ViewHolder, item: DownloadJob) {
        holder.ivThumb.setImageDrawable(null)

        val path = item.filePath
        if (path.isBlank() || isAudioOnly(item.format)) return

        thumbnailCache.get(path)?.let {
            holder.ivThumb.setImageBitmap(it)
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { extractVideoFrame(path) }
            if (bitmap != null) {
                thumbnailCache.put(path, bitmap)
                // Guard against the view having been recycled for a different row
                // while the frame was being decoded on the IO thread.
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION && getItem(currentPos).filePath == path) {
                    holder.ivThumb.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Grabs a frame from the downloaded video file itself and scales it down for the list row. */
    private fun extractVideoFrame(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val targetSize = 160
            if (frame.width <= targetSize && frame.height <= targetSize) {
                frame
            } else {
                val scale = targetSize.toFloat() / maxOf(frame.width, frame.height)
                val w = (frame.width * scale).toInt().coerceAtLeast(1)
                val h = (frame.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(frame, w, h, true)
            }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    /** Summarizes the download options the user picked for this job, e.g. "1080P · MP4 · Subtitles". */
    private fun formatOptions(item: DownloadJob): String {
        val parts = mutableListOf<String>()
        if (item.quality.isNotBlank()) parts.add(item.quality.uppercase(Locale.US))
        if (item.format.isNotBlank()) parts.add(item.format.uppercase(Locale.US))
        if (!item.audioFormatId.isNullOrBlank()) parts.add("Audio: ${item.audioFormatId}")
        if (item.subtitles) parts.add("Subtitles")
        if (item.embedMeta) parts.add("Metadata")
        return parts.joinToString(" · ")
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