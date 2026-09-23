package com.pandorasbox.app

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

object FileUtils {

    fun getDefaultDownloadFolder(context: Context): String {
        return try {
            val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (publicDir != null && (publicDir.exists() || publicDir.mkdirs())) {
                publicDir.absolutePath
            } else {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                    ?: context.filesDir.absolutePath
            }
        } catch (_: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                ?: context.filesDir.absolutePath
        }
    }

    fun getPathFromTreeUri(uri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }

        if (docId != null) {
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val type = parts[0]
                val path = parts[1]
                return if ("primary".equals(type, ignoreCase = true)) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/$path"
                } else {
                    "/storage/$type/$path"
                }
            }
        }

        return uri.path ?: uri.toString()
    }
}
