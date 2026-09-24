package com.pandorasbox.app

data class DownloadJob(
    val id: String,
    val url: String,
    var title: String = "",
    val format: String = "mp4",
    val quality: String = "best",
    val duplicatePolicy: String = "rename",
    val audioFormatId: String? = null,
    val playlistTitle: String? = null,
    val playlistIndex: Int? = null,
    val playlistPad: Int = 2,
    val subtitles: Boolean = false,
    val embedMeta: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    var status: String = "queued", // queued, starting, downloading, paused, completed, failed, cancelled
    var stage: String = "Queued",
    var percent: Float = 0f,
    var downloaded: String = "—",
    var total: String = "—",
    var speed: String = "—",
    var eta: String = "—",
    var filePath: String = "",
    var error: String = "",
    val createdAt: String = ""
)

data class AppSettings(
    var maxConcurrent: Int = 2,
    var stallTimeout: Int = 120,
    var notificationsEnabled: Boolean = true,
    var downloadFolder: String = "",
    var lastReferer: String = "",
    var lastUserAgent: String = "",
    var duplicatePolicy: String = "rename",
    var downloadSubtitles: Boolean = false,
    var embedMetadata: Boolean = false
)
