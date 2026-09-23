package com.pandorasbox.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadFragment : Fragment() {

    private lateinit var etUrl: TextInputEditText
    private lateinit var btnPaste: MaterialButton
    private lateinit var layoutPreview: LinearLayout
    private lateinit var ivThumbnail: ImageView
    private lateinit var tvPreviewTitle: TextView
    private lateinit var tvPreviewMeta: TextView

    private lateinit var layoutPlaylistPanel: LinearLayout
    private lateinit var etPlaylistName: EditText
    private lateinit var switchNumbering: MaterialSwitch
    private lateinit var tvPlaylistCount: TextView
    private lateinit var rvPlaylistItems: RecyclerView

    private lateinit var spinnerFormat: Spinner
    private lateinit var spinnerQuality: Spinner
    private lateinit var layoutQualityContainer: LinearLayout
    private lateinit var spinnerDuplicate: Spinner

    private lateinit var switchSubtitles: MaterialSwitch
    private lateinit var switchMetadata: MaterialSwitch

    private lateinit var etReferer: EditText
    private lateinit var etUserAgent: EditText
    private lateinit var btnChangeFolder: MaterialButton
    private lateinit var tvFolderPath: TextView
    private lateinit var btnDownload: MaterialButton

    private var previewJob: Job? = null
    private var currentPreviewResult: PreviewResult? = null
    private var playlistEntries = mutableListOf<PlaylistEntry>()
    private var playlistAdapter: PlaylistAdapter? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val path = FileUtils.getPathFromTreeUri(it)
            DownloadManager.setDownloadFolder(path)
            Toast.makeText(requireContext(), "Saved download folder", Toast.LENGTH_SHORT).show()
        }
    }

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
        observeSettings()
    }

    private fun bindViews(view: View) {
        etUrl = view.findViewById(R.id.et_url)
        btnPaste = view.findViewById(R.id.btn_paste)
        layoutPreview = view.findViewById(R.id.layout_preview)
        ivThumbnail = view.findViewById(R.id.iv_thumbnail)
        tvPreviewTitle = view.findViewById(R.id.tv_preview_title)
        tvPreviewMeta = view.findViewById(R.id.tv_preview_meta)

        layoutPlaylistPanel = view.findViewById(R.id.layout_playlist_panel)
        etPlaylistName = view.findViewById(R.id.et_playlist_name)
        switchNumbering = view.findViewById(R.id.switch_numbering)
        tvPlaylistCount = view.findViewById(R.id.tv_playlist_count)
        rvPlaylistItems = view.findViewById(R.id.rv_playlist_items)

        spinnerFormat = view.findViewById(R.id.spinner_format)
        spinnerQuality = view.findViewById(R.id.spinner_quality)
        layoutQualityContainer = view.findViewById(R.id.layout_quality_container)
        spinnerDuplicate = view.findViewById(R.id.spinner_duplicate)

        switchSubtitles = view.findViewById(R.id.switch_subtitles)
        switchMetadata = view.findViewById(R.id.switch_metadata)

        etReferer = view.findViewById(R.id.et_referer)
        etUserAgent = view.findViewById(R.id.et_user_agent)
        btnChangeFolder = view.findViewById(R.id.btn_change_folder)
        tvFolderPath = view.findViewById(R.id.tv_folder_path)
        btnDownload = view.findViewById(R.id.btn_download)

        rvPlaylistItems.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun setupSpinners() {
        val formatAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("MP4 · Video", "WebM · Video", "MP3 · Audio")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerFormat.adapter = formatAdapter

        val qualityAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("Best available", "2160p or lower", "1440p or lower", "1080p or lower", "720p or lower", "480p or lower", "360p or lower")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerQuality.adapter = qualityAdapter

        val duplicateAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            listOf("Create a new copy", "Skip if file exists", "Overwrite existing file")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerDuplicate.adapter = duplicateAdapter

        spinnerFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isMp3 = position == 2
                layoutQualityContainer.isVisible = !isMp3
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        btnPaste.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                etUrl.setText(text)
                triggerPreview(text)
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            }
        }

        etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                previewJob?.cancel()
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

        btnChangeFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
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

            val quality = when (spinnerQuality.selectedItemPosition) {
                1 -> "h:2160"
                2 -> "h:1440"
                3 -> "h:1080"
                4 -> "h:720"
                5 -> "h:480"
                6 -> "h:360"
                else -> "best"
            }

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
                    userAgent = userAgent
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
                    userAgent = userAgent
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
                    userAgent = userAgent
                )
                Toast.makeText(requireContext(), "Added to download queue!", Toast.LENGTH_SHORT).show()
            }

            etUrl.setText("")
            resetPreviewUI()
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.settings.collect { settings ->
                tvFolderPath.text = settings.downloadFolder
            }
        }
    }

    private fun triggerPreview(urlStr: String) {
        val lines = urlStr.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        if (lines.size > 1) {
            resetPreviewUI()
            btnDownload.text = "↓ Add ${lines.size} to Queue"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val referer = etReferer.text.toString().trim()
            val userAgent = etUserAgent.text.toString().trim()

            val ffmpegPath = FFmpegHelper.getFFmpegExecutablePath(requireContext())
            val result = YtDlpEngine.extractInfo(
                url = lines[0],
                referer = referer.ifBlank { null },
                userAgent = userAgent.ifBlank { null },
                ffmpegPath = ffmpegPath
            )
            currentPreviewResult = result

            if (result.error != null) {
                resetPreviewUI()
                return@launch
            }

            if (result.isPlaylist) {
                layoutPreview.isVisible = false
                layoutPlaylistPanel.isVisible = true
                val info = result.playlistInfo
                etPlaylistName.setText(info?.playlistTitle ?: "Playlist")

                playlistEntries.clear()
                info?.entries?.let { playlistEntries.addAll(it) }

                playlistAdapter = PlaylistAdapter(playlistEntries) {
                    updatePlaylistCount()
                }
                rvPlaylistItems.adapter = playlistAdapter
                updatePlaylistCount()
                btnDownload.text = "↓ Download Playlist"
            } else {
                layoutPlaylistPanel.isVisible = false
                layoutPreview.isVisible = true
                val info = result.videoInfo
                tvPreviewTitle.text = info?.title ?: "Untitled"
                tvPreviewMeta.text = listOf(info?.uploader, info?.duration).filter { !it.isNullOrBlank() }.joinToString(" · ")
                ivThumbnail.load(info?.thumbnail)
                btnDownload.text = "↓ Add to Queue"
            }
        }
    }

    private fun updatePlaylistCount() {
        val includedCount = playlistEntries.count { it.included }
        tvPlaylistCount.text = "$includedCount of ${playlistEntries.size} videos selected"
    }

    private fun resetPreviewUI() {
        currentPreviewResult = null
        layoutPreview.isVisible = false
        layoutPlaylistPanel.isVisible = false
        btnDownload.text = "↓ Add to Queue"
    }
}
