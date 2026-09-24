package com.pandorasbox.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private lateinit var etUrl: EditText
    private lateinit var btnPaste: MaterialButton
    private lateinit var layoutResult: View
    private lateinit var layoutPreview: View
    private lateinit var ivThumbnail: ImageView
    private lateinit var tvPreviewTitle: TextView
    private lateinit var tvPreviewMeta: TextView

    private lateinit var layoutFetchStatus: View
    private lateinit var progressFetch: LottieAnimationView
    private lateinit var tvFetchStatus: TextView

    private lateinit var layoutPlaylistPanel: View
    private lateinit var etPlaylistName: EditText
    private lateinit var switchNumbering: MaterialSwitch
    private lateinit var tvPlaylistCount: TextView
    private lateinit var rvPlaylistItems: RecyclerView

    private lateinit var spinnerFormat: Spinner
    private lateinit var spinnerQuality: Spinner
    private lateinit var layoutQualityContainer: LinearLayout
    private lateinit var layoutAudioTrack: LinearLayout
    private lateinit var spinnerAudioTrack: Spinner
    private lateinit var spinnerDuplicate: Spinner

    private lateinit var switchSubtitles: MaterialSwitch
    private lateinit var switchMetadata: MaterialSwitch

    private lateinit var btnToggleAdvanced: View
    private lateinit var layoutAdvancedContent: View
    private lateinit var ivAdvancedArrow: ImageView
    private lateinit var btnInfoAdvanced: ImageView
    private lateinit var spinnerUaPreset: Spinner

    private lateinit var etReferer: EditText
    private lateinit var etUserAgent: EditText
    private lateinit var btnDownload: MaterialButton

    private var previewJob: Job? = null
    private var fetchJob: Job? = null
    private var currentPreviewResult: PreviewResult? = null
    private var playlistEntries = mutableListOf<PlaylistEntry>()
    private var playlistAdapter: PlaylistAdapter? = null

    // Spinner entries for the Audio Track picker. Only filled when a video has 2+ audio tracks;
    // empty otherwise (and then the spinner is hidden and no track id is sent).
    private var currentAudioTrackOptions: List<FormatOption> = emptyList()

    // Always the first entry. It has no format id, so choosing it (the default) means "no
    // explicit track" and yt-dlp picks its normal audio exactly as it did before this feature.
    private val automaticAudioTrack = FormatOption(formatId = "", height = 0, label = "Automatic (default)")

    private var currentQualityOptions = listOf(
        "Best available" to "best",
        "2160p or lower" to "h:2160",
        "1440p or lower" to "h:1440",
        "1080p or lower" to "h:1080",
        "720p or lower" to "h:720",
        "480p or lower" to "h:480",
        "360p or lower" to "h:360"
    )

    private val userAgentPresets = listOf(
        "Default (Automatic)" to "",
        "Chrome Windows (Desktop)" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Chrome Android (Mobile)" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
        "Safari macOS (Desktop)" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
        "Firefox Windows (Desktop)" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_download, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupSpinners()
        setupListeners()
        applyDownloadDefaults()
    }

    private fun bindViews(view: View) {
        etUrl = view.findViewById(R.id.et_url)
        btnPaste = view.findViewById(R.id.btn_paste)
        layoutResult = view.findViewById(R.id.layout_result)
        layoutPreview = view.findViewById(R.id.layout_preview)
        ivThumbnail = view.findViewById(R.id.iv_thumbnail)
        tvPreviewTitle = view.findViewById(R.id.tv_preview_title)
        tvPreviewMeta = view.findViewById(R.id.tv_preview_meta)

        layoutFetchStatus = view.findViewById(R.id.layout_fetch_status)
        progressFetch = view.findViewById(R.id.progress_fetch)
        tvFetchStatus = view.findViewById(R.id.tv_fetch_status)

        layoutPlaylistPanel = view.findViewById(R.id.layout_playlist_panel)
        etPlaylistName = view.findViewById(R.id.et_playlist_name)
        switchNumbering = view.findViewById(R.id.switch_numbering)
        tvPlaylistCount = view.findViewById(R.id.tv_playlist_count)
        rvPlaylistItems = view.findViewById(R.id.rv_playlist_items)

        spinnerFormat = view.findViewById(R.id.spinner_format)
        spinnerQuality = view.findViewById(R.id.spinner_quality)
        layoutQualityContainer = view.findViewById(R.id.layout_quality_container)
        layoutAudioTrack = view.findViewById(R.id.layout_audio_track)
        spinnerAudioTrack = view.findViewById(R.id.spinner_audio_track)
        spinnerDuplicate = view.findViewById(R.id.spinner_duplicate)

        switchSubtitles = view.findViewById(R.id.switch_subtitles)
        switchMetadata = view.findViewById(R.id.switch_metadata)

        btnToggleAdvanced = view.findViewById(R.id.btn_toggle_advanced)
        layoutAdvancedContent = view.findViewById(R.id.layout_advanced_content)
        ivAdvancedArrow = view.findViewById(R.id.iv_advanced_arrow)
        btnInfoAdvanced = view.findViewById(R.id.btn_info_advanced)
        spinnerUaPreset = view.findViewById(R.id.spinner_ua_preset)

        etReferer = view.findViewById(R.id.et_referer)
        etUserAgent = view.findViewById(R.id.et_user_agent)
        btnDownload = view.findViewById(R.id.btn_download)

        rvPlaylistItems.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupSpinners() {
        val formatAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            listOf("MP4 · Video", "WebM · Video", "MP3 · Audio")
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerFormat.adapter = formatAdapter

        val qualityAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            currentQualityOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerQuality.adapter = qualityAdapter

        val duplicateAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            listOf("Create a new copy", "Skip if file exists", "Overwrite existing file")
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerDuplicate.adapter = duplicateAdapter

        val uaAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            userAgentPresets.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerUaPreset.adapter = uaAdapter

        spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isMp3 = position == 2
                layoutQualityContainer.isVisible = !isMp3
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerUaPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedUa = userAgentPresets.getOrNull(position)?.second ?: ""
                etUserAgent.setText(selectedUa)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        requireView().findViewById<View>(R.id.btn_open_settings).setOnClickListener {
            (activity as? MainActivity)?.openSettings()
        }
        btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                etUrl.setText(text)
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            }
        }

        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                previewJob?.cancel()
                fetchJob?.cancel()
                layoutFetchStatus.isVisible = false
                progressFetch.cancelAnimation()
                hideResult()
                currentPreviewResult = null
                // The old video's tracks must not be applied to whatever URL is typed next.
                clearAudioTracks()
                val text = s?.toString()?.trim() ?: ""
                if (text.isNotBlank()) {
                    previewJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(600)
                        triggerPreview(text)
                    }
                } else {
                    resetPreviewUI()
                }
            }
        })

        btnToggleAdvanced.setOnClickListener {
            val isVis = layoutAdvancedContent.isVisible
            if (isVis) {
                layoutAdvancedContent.isVisible = false
            } else {
                reveal(layoutAdvancedContent)
            }
            if (motionEnabled()) {
                ivAdvancedArrow.animate().rotation(if (isVis) 0f else 180f).setDuration(180).start()
            } else {
                ivAdvancedArrow.rotation = if (isVis) 0f else 180f
            }
            btnToggleAdvanced.contentDescription = if (isVis) "More options for this download" else "Hide more options"
        }

        btnInfoAdvanced.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Advanced Headers")
                .setMessage("Referer and User-Agent headers help bypass anti-bot, geo-blocking, or hotlink protections used by certain websites.\n\n• Referer: Tells the site where you came from.\n• User-Agent: Tells the site what browser you are using.\n\nSelecting a preset automatically configures modern browser headers for troublesome sites.")
                .setPositiveButton("OK", null)
                .show()
        }

        btnDownload.setOnClickListener {
            val rawText = etUrl.text?.toString()?.trim() ?: ""
            if (rawText.isBlank()) {
                Toast.makeText(requireContext(), "Please enter a video or playlist URL.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val format = when (spinnerFormat.selectedItemPosition) {
                1 -> "webm"
                2 -> "mp3"
                else -> "mp4"
            }

            val quality = currentQualityOptions.getOrNull(spinnerQuality.selectedItemPosition)?.second ?: "best"

            val audioFormatId = selectedAudioFormatId()

            val duplicatePolicy = when (spinnerDuplicate.selectedItemPosition) {
                1 -> "skip"
                2 -> "overwrite"
                else -> "rename"
            }

            val subtitles = switchSubtitles.isChecked
            val embedMeta = switchMetadata.isChecked
            val referer = etReferer.text.toString().trim()
            val userAgent = etUserAgent.text.toString().trim()

            val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

            if (currentPreviewResult?.isPlaylist == true) {
                val playlistInfo = currentPreviewResult?.playlistInfo
                val pTitle = etPlaylistName.text.toString().trim().ifBlank { playlistInfo?.playlistTitle ?: "Playlist" }
                val numbering = switchNumbering.isChecked

                DownloadManager.enqueuePlaylist(
                    playlistTitle = pTitle,
                    entries = playlistEntries,
                    numbering = numbering,
                    format = format,
                    quality = quality,
                    duplicatePolicy = duplicatePolicy,
                    subtitles = subtitles,
                    embedMeta = embedMeta,
                    referer = referer,
                    userAgent = userAgent,
                    audioFormatId = audioFormatId
                )
                Toast.makeText(requireContext(), "Playlist queued!", Toast.LENGTH_SHORT).show()
            } else if (lines.size > 1) {
                DownloadManager.enqueueBatch(
                    urls = lines,
                    format = format,
                    quality = quality,
                    duplicatePolicy = duplicatePolicy,
                    subtitles = subtitles,
                    embedMeta = embedMeta,
                    referer = referer,
                    userAgent = userAgent,
                    audioFormatId = audioFormatId
                )
                Toast.makeText(requireContext(), "Queued ${lines.size} downloads!", Toast.LENGTH_SHORT).show()
            } else {
                val previewTitle = currentPreviewResult?.videoInfo?.title ?: ""
                DownloadManager.enqueueDownload(
                    url = lines[0],
                    title = previewTitle,
                    format = format,
                    quality = quality,
                    duplicatePolicy = duplicatePolicy,
                    subtitles = subtitles,
                    embedMeta = embedMeta,
                    referer = referer,
                    userAgent = userAgent,
                    audioFormatId = audioFormatId
                )
                Toast.makeText(requireContext(), "Added to download queue!", Toast.LENGTH_SHORT).show()
            }

            etUrl.setText("")
            resetPreviewUI()
            applyDownloadDefaults()
            etReferer.setText("")
            etUserAgent.setText("")
            spinnerUaPreset.setSelection(0)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::spinnerDuplicate.isInitialized && currentPreviewResult == null) applyDownloadDefaults()
        if (::progressFetch.isInitialized && layoutFetchStatus.isVisible && progressFetch.isVisible && motionEnabled()) {
            progressFetch.playAnimation()
        }
    }

    override fun onPause() {
        if (::progressFetch.isInitialized) progressFetch.pauseAnimation()
        super.onPause()
    }

    private fun applyDownloadDefaults() {
        val settings = DownloadManager.settings.value
        spinnerDuplicate.setSelection(when (settings.duplicatePolicy) {
            "skip" -> 1
            "overwrite" -> 2
            else -> 0
        })
        switchSubtitles.isChecked = settings.downloadSubtitles
        switchMetadata.isChecked = settings.embedMetadata
    }

    private fun reveal(target: View) {
        target.animate().cancel()
        target.isVisible = true
        if (!motionEnabled()) {
            target.alpha = 1f
            target.translationY = 0f
            return
        }
        val offset = 18f * resources.displayMetrics.density
        target.alpha = 0f
        target.translationY = offset
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun motionEnabled(): Boolean =
        Settings.Global.getFloat(requireContext().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun hideResult() {
        layoutResult.animate().cancel()
        layoutResult.isVisible = false
        layoutResult.alpha = 1f
        layoutResult.translationY = 0f
        layoutAdvancedContent.isVisible = false
        ivAdvancedArrow.rotation = 0f
    }

    private fun showFetchStatus(message: String, isError: Boolean, showSpinner: Boolean) {
        layoutFetchStatus.isVisible = true
        progressFetch.isVisible = showSpinner
        if (showSpinner && motionEnabled()) {
            if (isResumed) progressFetch.playAnimation()
            else progressFetch.progress = 0.35f
        } else {
            progressFetch.cancelAnimation()
            if (showSpinner) progressFetch.progress = 0.35f
        }
        tvFetchStatus.text = message
        tvFetchStatus.setTextColor(
            if (isError) 0xFFEF5350.toInt()
            else ContextCompat.getColor(requireContext(), R.color.accent_blue_soft)
        )
    }

    private fun triggerPreview(urlStr: String) {
        val lines = urlStr.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        if (lines.size > 1) {
            resetPreviewUI()
            btnDownload.text = "Add ${lines.size} to Queue"
            reveal(layoutResult)
            return
        }

        fetchJob?.cancel()
        fetchJob = viewLifecycleOwner.lifecycleScope.launch {
            val referer = etReferer.text.toString().trim()
            val userAgent = etUserAgent.text.toString().trim()

            // Clear the old preview and tell the user we are working on it
            currentPreviewResult = null
            hideResult()
            layoutPreview.isVisible = false
            layoutPlaylistPanel.isVisible = false
            showFetchStatus("Fetching video details…", isError = false, showSpinner = true)

            val hintJob = launch {
                delay(15_000)
                showFetchStatus("Still fetching… this site is slow to respond.", isError = false, showSpinner = true)
                delay(30_000)
                showFetchStatus(
                    "This is taking very long. You can keep waiting, or tap Add to Queue to try downloading anyway.",
                    isError = false,
                    showSpinner = true
                )
            }

            val ffmpegPath = FFmpegHelper.getFFmpegExecutablePath(requireContext())
            val result = try {
                YtDlpEngine.extractInfo(
                    url = lines[0],
                    referer = referer.ifBlank { null },
                    userAgent = userAgent.ifBlank { null },
                    ffmpegPath = ffmpegPath
                )
            } finally {
                hintJob.cancel()
            }
            currentPreviewResult = result

            if (result.error != null) {
                resetPreviewUI()
                showFetchStatus(
                    "Couldn't load video details: ${result.error}\nYou can still tap Add to Queue to try downloading.",
                    isError = true,
                    showSpinner = false
                )
                reveal(layoutResult)
                return@launch
            }

            layoutFetchStatus.isVisible = false
            progressFetch.cancelAnimation()

            if (result.isPlaylist) {
                layoutPreview.isVisible = false
                layoutPlaylistPanel.isVisible = true
                clearAudioTracks()
                val info = result.playlistInfo
                etPlaylistName.setText(info?.playlistTitle ?: "Playlist")

                playlistEntries.clear()
                info?.entries?.let { playlistEntries.addAll(it) }

                playlistAdapter = PlaylistAdapter(playlistEntries) {
                    updatePlaylistCount()
                }
                rvPlaylistItems.adapter = playlistAdapter
                playlistAdapter?.enableDragReorder(rvPlaylistItems)
                updatePlaylistCount()
                btnDownload.text = "Download Playlist"
            } else {
                layoutPlaylistPanel.isVisible = false
                layoutPreview.isVisible = true
                val info = result.videoInfo
                tvPreviewTitle.text = info?.title ?: "Untitled"
                tvPreviewMeta.text = listOf(info?.uploader, info?.duration).filter { !it.isNullOrBlank() }.joinToString(" · ")

                val vFormats = info?.videoFormats ?: emptyList()
                if (vFormats.isNotEmpty()) {
                    val qualityList = mutableListOf<Pair<String, String>>()
                    qualityList.add("Best available" to "best")
                    vFormats.forEach { f ->
                        qualityList.add(f.label to "id:${f.formatId}:${f.height}")
                    }
                    currentQualityOptions = qualityList
                    val qualityAdapter = ArrayAdapter(
                        requireContext(),
                        R.layout.item_spinner,
                        qualityList.map { it.first }
                    ).apply {
                        setDropDownViewResource(R.layout.item_spinner_dropdown)
                    }
                    spinnerQuality.adapter = qualityAdapter
                }

                bindAudioTracks(info?.audioFormats ?: emptyList())

                val thumbUrl = info?.thumbnail?.ifBlank { null }
                if (!thumbUrl.isNullOrBlank()) {
                    ivThumbnail.load(thumbUrl) {
                        crossfade(true)
                        placeholder(R.drawable.ic_download)
                        error(R.drawable.ic_download)
                    }
                } else {
                    ivThumbnail.setImageResource(R.drawable.ic_download)
                }
                btnDownload.text = "Add to Queue"
            }
            reveal(layoutResult)
        }
    }

    /** Hides the Audio Track picker and forgets its entries. */
    private fun clearAudioTracks() {
        currentAudioTrackOptions = emptyList()
        layoutAudioTrack.isVisible = false
    }

    /**
     * Shows the Audio Track picker only when the video really has 2+ tracks. The spinner gets
     * an "Automatic (default)" entry first, followed by one entry per track.
     */
    private fun bindAudioTracks(tracks: List<FormatOption>) {
        if (tracks.size < 2) {
            clearAudioTracks()
            return
        }

        currentAudioTrackOptions = listOf(automaticAudioTrack) + tracks
        val audioTrackAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            currentAudioTrackOptions.map { it.label }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerAudioTrack.adapter = audioTrackAdapter // a new adapter selects the first entry
        layoutAudioTrack.isVisible = true
    }

    /** The yt-dlp format id of the chosen track, or null when no explicit track is chosen. */
    private fun selectedAudioFormatId(): String? {
        if (currentAudioTrackOptions.isEmpty()) return null
        return currentAudioTrackOptions
            .getOrNull(spinnerAudioTrack.selectedItemPosition)
            ?.formatId
            ?.ifBlank { null }
    }

    private fun updatePlaylistCount() {
        val includedCount = playlistEntries.count { it.included }
        tvPlaylistCount.text = "$includedCount of ${playlistEntries.size} videos selected"
    }

    private fun resetPreviewUI() {
        currentPreviewResult = null
        layoutFetchStatus.isVisible = false
        progressFetch.cancelAnimation()
        layoutPreview.isVisible = false
        layoutPlaylistPanel.isVisible = false
        hideResult()
        btnDownload.text = "Add to Queue"

        clearAudioTracks()

        currentQualityOptions = listOf(
            "Best available" to "best",
            "2160p or lower" to "h:2160",
            "1440p or lower" to "h:1440",
            "1080p or lower" to "h:1080",
            "720p or lower" to "h:720",
            "480p or lower" to "h:480",
            "360p or lower" to "h:360"
        )
        val qualityAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            currentQualityOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinnerQuality.adapter = qualityAdapter
    }
}
