package com.pandorasbox.app

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var etMaxConcurrent: EditText
    private lateinit var etStallTimeout: EditText
    private lateinit var switchNotifications: MaterialSwitch
    private lateinit var btnSelectFolder: MaterialButton
    private lateinit var etSaveFolder: EditText
    private lateinit var btnCheckUpdates: MaterialButton
    private lateinit var tvUpdateStatus: TextView
    private lateinit var spinnerDefaultDuplicate: Spinner
    private lateinit var switchDefaultSubtitles: MaterialSwitch
    private lateinit var switchDefaultMetadata: MaterialSwitch

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
            etSaveFolder.setText(path)
            Toast.makeText(requireContext(), "Saved download folder", Toast.LENGTH_SHORT).show()
        }
    }

    // Android 13+: asks "Allow Pandora's Box to send notifications?".
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                requireContext(),
                "Notifications are blocked by Android. Allow them in Settings > Apps > Pandora's Box > Notifications.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etMaxConcurrent = view.findViewById(R.id.et_max_concurrent)
        etStallTimeout = view.findViewById(R.id.et_stall_timeout)
        switchNotifications = view.findViewById(R.id.switch_notifications)
        btnSelectFolder = view.findViewById(R.id.btn_select_folder)
        etSaveFolder = view.findViewById(R.id.et_save_folder)
        spinnerDefaultDuplicate = view.findViewById(R.id.spinner_default_duplicate)
        switchDefaultSubtitles = view.findViewById(R.id.switch_default_subtitles)
        switchDefaultMetadata = view.findViewById(R.id.switch_default_metadata)

        view.findViewById<View>(R.id.btn_back_home).setOnClickListener {
            (activity as? MainActivity)?.closeSettings()
        }
        val advancedSettings = view.findViewById<View>(R.id.layout_advanced_settings)
        val advancedToggle = view.findViewById<View>(R.id.btn_toggle_advanced_settings)
        val advancedArrow = view.findViewById<ImageView>(R.id.iv_advanced_settings_arrow)
        advancedToggle.setOnClickListener {
            advancedSettings.isVisible = !advancedSettings.isVisible
            val rotation = if (advancedSettings.isVisible) 180f else 0f
            advancedArrow.animate().cancel()
            if (android.provider.Settings.Global.getFloat(
                    requireContext().contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                ) > 0f) {
                advancedArrow.animate().rotation(rotation).setDuration(180).start()
            } else {
                advancedArrow.rotation = rotation
            }
            advancedToggle.contentDescription = if (advancedSettings.isVisible) {
                "Collapse advanced settings"
            } else {
                "Expand advanced settings"
            }
        }

        observeSettings()
        setupDownloadDefaults()
        setupListeners()
        setupAboutAndUpdates(view)
    }

    private fun setupDownloadDefaults() {
        val adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_spinner,
            listOf("Create a new copy", "Skip if file exists", "Overwrite existing file")
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        spinnerDefaultDuplicate.adapter = adapter
        val settings = DownloadManager.settings.value
        spinnerDefaultDuplicate.setSelection(when (settings.duplicatePolicy) {
            "skip" -> 1
            "overwrite" -> 2
            else -> 0
        })
        switchDefaultSubtitles.isChecked = settings.downloadSubtitles
        switchDefaultMetadata.isChecked = settings.embedMetadata

        fun saveDefaults() {
            val duplicate = when (spinnerDefaultDuplicate.selectedItemPosition) {
                1 -> "skip"
                2 -> "overwrite"
                else -> "rename"
            }
            DownloadManager.updateDownloadDefaults(
                duplicate,
                switchDefaultSubtitles.isChecked,
                switchDefaultMetadata.isChecked
            )
        }
        spinnerDefaultDuplicate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = saveDefaults()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        switchDefaultSubtitles.setOnCheckedChangeListener { _, _ -> saveDefaults() }
        switchDefaultMetadata.setOnCheckedChangeListener { _, _ -> saveDefaults() }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.settings.collect { settings ->
                if (etMaxConcurrent.text.toString() != settings.maxConcurrent.toString()) {
                    etMaxConcurrent.setText(settings.maxConcurrent.toString())
                }
                if (etStallTimeout.text.toString() != settings.stallTimeout.toString()) {
                    etStallTimeout.setText(settings.stallTimeout.toString())
                }
                if (switchNotifications.isChecked != settings.notificationsEnabled) {
                    switchNotifications.isChecked = settings.notificationsEnabled
                }
                if (etSaveFolder.text.toString() != settings.downloadFolder) {
                    etSaveFolder.setText(settings.downloadFolder)
                }
            }
        }
    }

    private fun setupListeners() {
        etMaxConcurrent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveSettings()
            }
        })

        etStallTimeout.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveSettings()
            }
        })

        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            saveSettings()
            // Turning it on is the moment to make sure Android will really allow notifications.
            if (isChecked && DownloadNotifier.needsPermission(requireContext())) {
                notificationPermissionLauncher.launch(DownloadNotifier.PERMISSION)
            }
        }

        btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        etSaveFolder.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val path = s?.toString()?.trim() ?: ""
                if (path.isNotBlank()) {
                    DownloadManager.setDownloadFolder(path)
                }
            }
        })
    }

    private fun setupAboutAndUpdates(view: View) {
        val tvVersion = view.findViewById<TextView>(R.id.tv_app_version)
        val tvGithubLink = view.findViewById<TextView>(R.id.tv_github_link)
        val rowGithub = view.findViewById<View>(R.id.row_github)
        btnCheckUpdates = view.findViewById(R.id.btn_check_updates)
        tvUpdateStatus = view.findViewById(R.id.tv_update_status)

        tvVersion.text = "Version ${UpdateChecker.currentVersionName(requireContext())}"
        tvGithubLink.paintFlags = tvGithubLink.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        // GitHub link: the whole row is tappable.
        rowGithub.setOnClickListener {
            UpdateFlow.openUrl(requireContext(), UpdateChecker.GITHUB_REPO_URL)
        }
        view.findViewById<View>(R.id.row_donate).setOnClickListener {
            UpdateFlow.openUrl(requireContext(), "https://buymeacoffee.com/pasindusheshan")
        }

        // Manual update check (same flow as the automatic one at app start).
        btnCheckUpdates.setOnClickListener {
            val flow = (activity as? MainActivity)?.updateFlow ?: return@setOnClickListener
            btnCheckUpdates.isEnabled = false
            tvUpdateStatus.visibility = View.VISIBLE
            tvUpdateStatus.text = "Checking for updates…"
            flow.check(manual = true) { message ->
                tvUpdateStatus.text = message
                btnCheckUpdates.isEnabled = true
            }
        }
    }

    private fun saveSettings() {
        val maxConcurrent = etMaxConcurrent.text.toString().toIntOrNull() ?: 2
        val stallTimeout = etStallTimeout.text.toString().toIntOrNull() ?: 120
        val notifications = switchNotifications.isChecked

        DownloadManager.updateSettings(
            maxConcurrent = maxConcurrent.coerceIn(1, 6),
            stallTimeout = stallTimeout.coerceIn(30, 600),
            notifications = notifications
        )
    }
}
