package com.pandorasbox.app

import androidx.core.content.FileProvider

/**
 * Separate FileProvider used only to hand the downloaded update APK to Android's installer.
 * It exists so the existing provider (and res/xml/file_paths.xml) stay untouched.
 */
class UpdateFileProvider : FileProvider()
