package com.pandorasbox.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
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

        observeSettings()
        setupListeners()
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

        switchNotifications.setOnCheckedChangeListener { _, _ ->
            saveSettings()
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
