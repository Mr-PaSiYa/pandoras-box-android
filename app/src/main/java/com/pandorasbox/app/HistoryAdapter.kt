package com.pandorasbox.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
    private val onCopyLink: (String) -> Unit,
    private val onRemove: (DownloadJob) -> Unit
) : ListAdapter<DownloadJob, HistoryAdapter.ViewHolder>(DiffCallback) {

    var compact = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    // Small in-memory cache so scrolling the list doesn't keep re-decoding the same
    // video frame. Keyed by file path; cleared automatically when the fragment/adapter
    // is garbage collected (process-lifetime only, nothing persisted to disk).
    private val thumbnailCache = LruCache<String, Bitmap>(40)
    private val loadingThumbnails = mutableSetOf<String>()
    private val resolutionCache = LruCache<String, String>(80)
    private val loadingResolutions = mutableSetOf<String>()

    private val AUDIO_ONLY_FORMATS = setOf("mp3", "m4a", "aac", "wav", "opus", "flac", "ogg")
    private fun isAudioOnly(format: String) = format.trim().lowercase(Locale.US) in AUDIO_ONLY_FORMATS

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: LinearLayout = view.findViewById(R.id.history_content)
        val thumbContainer: MaterialCardView = view.findViewById(R.id.history_thumb_container)
        val actions: LinearLayout = view.findViewById(R.id.history_actions)
        val more: View = view.findViewById(R.id.btn_history_more)
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
        val density = holder.itemView.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val thumbnailSize = holder.thumbContainer.layoutParams
        thumbnailSize.width = dp(if (compact) 56 else 112)
        thumbnailSize.height = dp(if (compact) 56 else 84)
        holder.thumbContainer.layoutParams = thumbnailSize
        val padding = dp(if (compact) 10 else 16)
        holder.content.setPadding(padding, padding, padding, padding)
        (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = dp(if (compact) 6 else 12)
            holder.itemView.layoutParams = it
        }
        holder.tvTitle.maxLines = if (compact) 1 else 2
        holder.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 15f)
        holder.actions.isVisible = !compact

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

        val optionsStr = formatOptions(item, fileExists)
        holder.tvOptions.text = optionsStr
        holder.tvOptions.isVisible = !compact && optionsStr.isNotBlank()

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
        holder.more.contentDescription = "Actions for ${item.title.ifBlank { "Untitled" }}"
        holder.more.setOnClickListener {
            PopupMenu(holder.itemView.context, holder.more).apply {
                if (compact) {
                    if (canOpen) {
                        menu.add(0, 1, 0, "Open file")
                        menu.add(0, 2, 1, "Open folder")
                    }
                    if (canRetry) menu.add(0, 3, 2, holder.btnRetry.text)
                    if (item.url.isNotBlank()) menu.add(0, 4, 3, "Copy link")
                }
                menu.add(0, 5, 4, "Remove from history")
                setOnMenuItemClickListener { selected ->
                    when (selected.itemId) {
                        1 -> onOpenFile(item.filePath)
                        2 -> onOpenFolder(item.filePath)
                        3 -> onRetry(item)
                        4 -> onCopyLink(item.url)
                        5 -> onRemove(item)
                    }
                    true
                }
                show()
            }
        }

        loadThumbnail(holder, item)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.ivThumb.setImageDrawable(null)
    }

    private fun loadThumbnail(holder: ViewHolder, item: DownloadJob) {
        holder.ivThumb.setImageDrawable(null)
        val stored = ThumbnailStore.file(holder.itemView.context, item.id)
        if (stored.isFile && stored.length() > 0) {
            holder.ivThumb.load(stored)
            return
        }
        val path = item.filePath
        if (path.isNotBlank()) {
            thumbnailCache.get(path)?.let {
                holder.ivThumb.setImageBitmap(it)
                return
            }
        }
        if (!loadingThumbnails.add(item.id)) return
        lifecycleScope.launch {
            try {
                val artwork = ThumbnailStore.ensure(holder.itemView.context.applicationContext, item)
                var available = artwork != null
                if (!available && path.isNotBlank() && !isAudioOnly(item.format)) {
                    val bitmap = withContext(Dispatchers.IO) { extractVideoFrame(path) }
                    if (bitmap != null) {
                        thumbnailCache.put(path, bitmap)
                        available = true
                    }
                }
                if (available) {
                    val currentPos = currentList.indexOfFirst { it.id == item.id }
                    if (currentPos >= 0) notifyItemChanged(currentPos)
                }
            } finally {
                loadingThumbnails.remove(item.id)
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
            val targetSize = 320
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

    /** Show the saved video's resolution, not yt-dlp's internal format selector. */
    private fun formatOptions(item: DownloadJob, fileExists: Boolean): String {
        val parts = mutableListOf<String>()
        videoResolution(item, fileExists)?.let(parts::add)
        if (item.format.isNotBlank()) parts.add(item.format.uppercase(Locale.US))
        if (!item.audioFormatId.isNullOrBlank()) parts.add("Audio: ${item.audioFormatId}")
        if (item.subtitles) parts.add("Subtitles")
        if (item.embedMeta) parts.add("Metadata")
        return parts.joinToString(" · ")
    }

    private fun videoResolution(item: DownloadJob, fileExists: Boolean): String? {
        if (isAudioOnly(item.format)) return null
        val path = item.filePath
        if (fileExists) {
            resolutionCache.get(path)?.takeIf { it.isNotBlank() }?.let { return it }
            if (resolutionCache.get(path) == null && loadingResolutions.add(path)) {
                lifecycleScope.launch {
                    try {
                        val actual = withContext(Dispatchers.IO) { readVideoResolution(path) }
                        resolutionCache.put(path, actual.orEmpty())
                        val position = currentList.indexOfFirst { it.filePath == path }
                        if (position >= 0) notifyItemChanged(position)
                    } finally {
                        loadingResolutions.remove(path)
                    }
                }
            }
        }
        val height = when {
            item.quality.startsWith("h:") -> item.quality.substringAfter("h:").toIntOrNull()
            item.quality.startsWith("id:") -> item.quality.substringAfterLast(':').toIntOrNull()
            else -> Regex("^(\\d{3,4})p$", RegexOption.IGNORE_CASE)
                .matchEntire(item.quality)?.groupValues?.get(1)?.toIntOrNull()
        }
        return height?.takeIf { it in 144..8640 }?.let { "${it}p" }
    }

    private fun readVideoResolution(path: String): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            if (width == null || height == null) null
            else minOf(width, height).takeIf { it in 144..8640 }?.let { "${it}p" }
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
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
