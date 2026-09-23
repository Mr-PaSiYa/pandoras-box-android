package com.pandorasbox.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.io.File

class HistoryFragment : Fragment() {

    private lateinit var btnClearHistory: MaterialButton
    private lateinit var rvHistory: RecyclerView
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnClearHistory = view.findViewById(R.id.btn_clear_history)
        rvHistory = view.findViewById(R.id.rv_history)

        historyAdapter = HistoryAdapter(
            onOpenFile = { path -> openFile(path) },
            onOpenFolder = { path -> openFolder(path) },
            onRetry = { job ->
                DownloadManager.retryJob(job)
                Toast.makeText(requireContext(), "Re-queued download!", Toast.LENGTH_SHORT).show()
            }
        )

        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter

        btnClearHistory.setOnClickListener {
            DownloadManager.clearHistory()
            Toast.makeText(requireContext(), "History cleared.", Toast.LENGTH_SHORT).show()
        }

        observeHistory()
    }

    private fun observeHistory() {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadManager.historyJobs.collect { historyList ->
                historyAdapter.submitList(historyList)
            }
        }
    }

    private fun openFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "File not found.", Toast.LENGTH_SHORT).show()
                return
            }
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (file.extension == "mp3") "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFolder(filePath: String) {
        try {
            val file = File(filePath)
            val parent = if (file.isDirectory) file else file.parentFile
            if (parent != null && parent.exists()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(parent.absolutePath), "*/*")
                }
                startActivity(intent)
            } else {
                Toast.makeText(requireContext(), "Folder not found.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Folder path: ${File(filePath).parent}", Toast.LENGTH_LONG).show()
        }
    }
}
