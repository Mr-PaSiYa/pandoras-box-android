package com.pandorasbox.app

import android.content.Context
import java.io.File

object FFmpegHelper {

    fun getFFmpegExecutablePath(context: Context): String? {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val ffmpegSo = File(nativeLibDir, "libffmpeg.so")
        if (ffmpegSo.exists() && ffmpegSo.length() > 0L) {
            return ffmpegSo.absolutePath
        }

        if (nativeLibDir.exists() && nativeLibDir.isDirectory) {
            val files = nativeLibDir.listFiles()
            if (files != null) {
                for (f in files) {
                    val name = f.name.lowercase()
                    if (name.contains("ffmpeg")) {
                        return f.absolutePath
                    }
                }
            }
        }

        return null
    }

    fun prepareFFmpeg(context: Context): String? {
        return getFFmpegExecutablePath(context)
    }
}
