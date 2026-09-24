package com.pandorasbox.app

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/** Keeps download-engine startup off the screen-rendering thread. */
object PythonRuntime {
    private val lock = Any()
    private lateinit var appContext: Context
    private var scheduled = false
    private var customPathLoaded = false

    fun prepare(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
        }
    }

    fun startAsync(context: Context) {
        prepare(context)
        synchronized(lock) {
            if (scheduled) return
            scheduled = true
        }
        Thread({
            try {
                ensureStarted()
            } catch (e: Exception) {
                Log.e("PythonRuntime", "Unable to prepare the download engine", e)
            }
        }, "python-startup").start()
    }

    fun ensureStarted() {
        synchronized(lock) {
            check(::appContext.isInitialized) { "PythonRuntime.startAsync must be called first" }
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            if (!customPathLoaded) {
                val path = File(appContext.filesDir, "python_packages").absolutePath
                Python.getInstance().getModule("ytdlp_helper").callAttr("load_custom_path", path)
                customPathLoaded = true
            }
        }
    }
}
