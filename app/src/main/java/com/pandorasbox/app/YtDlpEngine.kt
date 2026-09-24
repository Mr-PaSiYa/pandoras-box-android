package com.pandorasbox.app

import com.chaquo.python.PyObject
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FormatOption(
    val formatId: String,
    val height: Int,
    val label: String,
    val language: String = "",
)

data class PlaylistEntry(
    val title: String,
    val url: String,
    var included: Boolean = true
)

data class VideoInfo(
    val title: String,
    val uploader: String,
    val duration: String,
    val thumbnail: String,
    val videoFormats: List<FormatOption>,
    val audioFormats: List<FormatOption>,
    val isM3u8: Boolean
)

data class PlaylistInfo(
    val playlistTitle: String,
    val entries: List<PlaylistEntry>
)

data class PreviewResult(
    val isPlaylist: Boolean,
    val videoInfo: VideoInfo? = null,
    val playlistInfo: PlaylistInfo? = null,
    val error: String? = null
)

data class DownloadProgress(
    val status: String,
    val percent: Float,
    val downloaded: String,
    val total: String,
    val speed: String,
    val eta: String,
    val error: String = ""
)

data class DownloadResult(
    val success: Boolean,
    val filePath: String? = null,
    val error: String? = null
)

fun interface PyProgressCallback {
    fun invoke(
        status: String,
        percent: Double,
        downloaded: String,
        total: String,
        speed: String,
        eta: String,
        error: String
    )
}

object YtDlpEngine {

    private fun getHelperModule(): PyObject {
        PythonRuntime.ensureStarted()
        val py = Python.getInstance()
        return py.getModule("ytdlp_helper")
    }

    fun initCustomPath(targetDir: String) {
        try {
            val module = getHelperModule()
            module.callAttr("load_custom_path", targetDir)
        } catch (_: Exception) {}
    }

    suspend fun updateYtDlp(targetDir: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val module = getHelperModule()
            val resultStr = module.callAttr("update_ytdlp", targetDir).toString()
            val json = JSONObject(resultStr)
            val success = json.optString("status") == "success"
            val message = if (success) json.optString("message", "yt-dlp updated successfully!") else json.optString("error", "Update failed")
            Pair(success, message)
        } catch (e: Exception) {
            Pair(false, e.localizedMessage ?: "Failed to update yt-dlp engine")
        }
    }

    suspend fun extractInfo(
        url: String,
        referer: String? = null,
        userAgent: String? = null,
        ffmpegPath: String? = null
    ): PreviewResult = withContext(Dispatchers.IO) {
        try {
            val module = getHelperModule()
            val jsonStr = module.callAttr("extract_info", url, referer, userAgent, ffmpegPath).toString()
            val json = JSONObject(jsonStr)

            if (json.has("error")) {
                return@withContext PreviewResult(isPlaylist = false, error = json.getString("error"))
            }

            val isPlaylist = json.optBoolean("is_playlist", false)
            if (isPlaylist) {
                val playlistTitle = json.optString("playlist_title", "Playlist")
                val entriesArray = json.optJSONArray("entries")
                val entriesList = mutableListOf<PlaylistEntry>()

                if (entriesArray != null) {
                    for (i in 0 until entriesArray.length()) {
                        val item = entriesArray.getJSONObject(i)
                        entriesList.add(
                            PlaylistEntry(
                                title = item.optString("title", "Untitled"),
                                url = item.optString("url", "")
                            )
                        )
                    }
                }

                PreviewResult(
                    isPlaylist = true,
                    playlistInfo = PlaylistInfo(playlistTitle, entriesList)
                )
            } else {
                val title = json.optString("title", "Untitled")
                val uploader = json.optString("uploader", "")
                val duration = json.optString("duration", "")
                val thumbnail = json.optString("thumbnail", "")
                val isM3u8 = json.optBoolean("is_m3u8", false)

                val videoFormats = mutableListOf<FormatOption>()
                val vArray = json.optJSONArray("video_formats")
                if (vArray != null) {
                    for (i in 0 until vArray.length()) {
                        val item = vArray.getJSONObject(i)
                        videoFormats.add(
                            FormatOption(
                                formatId = item.optString("format_id", ""),
                                height = item.optInt("height", 0),
                                label = item.optString("label", "")
                            )
                        )
                    }
                }

                val audioFormats = mutableListOf<FormatOption>()
                val aArray = json.optJSONArray("audio_formats")
                if (aArray != null) {
                    for (i in 0 until aArray.length()) {
                        val item = aArray.getJSONObject(i)
                        audioFormats.add(
                            FormatOption(
                                formatId = item.optString("format_id", ""),
                                height = 0,
                                label = item.optString("label", item.optString("ext", "")),
                                language = item.optString("language", "")
                            )
                        )
                    }
                }

                PreviewResult(
                    isPlaylist = false,
                    videoInfo = VideoInfo(
                        title = title,
                        uploader = uploader,
                        duration = duration,
                        thumbnail = thumbnail,
                        videoFormats = videoFormats,
                        audioFormats = audioFormats,
                        isM3u8 = isM3u8
                    )
                )
            }
        } catch (e: Exception) {
            PreviewResult(isPlaylist = false, error = e.localizedMessage ?: "Failed to extract video details.")
        }
    }

    suspend fun downloadVideo(
        url: String,
        outputPath: String,
        formatType: String = "mp4",
        quality: String = "best",
        subtitles: Boolean = false,
        embedMeta: Boolean = false,
        referer: String? = null,
        userAgent: String? = null,
        ffmpegPath: String? = null,
        audioFormatId: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val module = getHelperModule()
            val pyCallback = PyProgressCallback { status, percent, downloaded, total, speed, eta, error ->
                onProgress(
                    DownloadProgress(
                        status = status,
                        percent = percent.toFloat(),
                        downloaded = downloaded,
                        total = total,
                        speed = speed,
                        eta = eta,
                        error = error
                    )
                )
            }

            val resultStr = module.callAttr(
                "download_video",
                url,
                outputPath,
                formatType,
                quality,
                subtitles,
                embedMeta,
                referer,
                userAgent,
                ffmpegPath,
                audioFormatId,
                pyCallback
            ).toString()

            val json = JSONObject(resultStr)
            val success = json.optString("status") == "completed"
            val filePath = json.optString("file_path").ifEmpty { null }
            val error = json.optString("error").ifEmpty { null }

            DownloadResult(success = success, filePath = filePath, error = error)
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Download error"
            onProgress(
                DownloadProgress(
                    status = "failed",
                    percent = 0f,
                    downloaded = "—",
                    total = "—",
                    speed = "—",
                    eta = "—",
                    error = errMsg
                )
            )
            DownloadResult(success = false, error = errMsg)
        }
    }
}
