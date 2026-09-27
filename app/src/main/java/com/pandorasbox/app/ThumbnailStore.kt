package com.pandorasbox.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A bounded, app-private copy of artwork returned by the video extractor. */
object ThumbnailStore {
    private const val MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val deletedIds = ConcurrentHashMap.newKeySet<String>()

    fun file(context: Context, jobId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(jobId.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "thumbnails"), "$digest.jpg")
    }

    suspend fun ensure(context: Context, job: DownloadJob): File? = withContext(Dispatchers.IO) {
        val target = file(context, job.id)
        if (job.id in deletedIds) return@withContext null
        if (target.isFile && target.length() > 0) return@withContext target
        val lock = locks.getOrPut(job.id) { Mutex() }
        lock.lock()
        try {
            if (target.isFile && target.length() > 0) return@withContext target
            if ((failedUntil[job.id] ?: 0L) > System.currentTimeMillis()) return@withContext null

            val thumbnailUrl = job.thumbnailUrl.ifBlank {
                // Older history and multi-link jobs did not save artwork at enqueue time.
                YtDlpEngine.extractInfo(job.url, job.referer, job.userAgent)
                    .videoInfo?.thumbnail.orEmpty()
            }
            var bitmap = fetchBitmap(thumbnailUrl, job.referer, job.userAgent)
            if (bitmap == null && job.thumbnailUrl.isNotBlank()) {
                val refreshed = YtDlpEngine.extractInfo(job.url, job.referer, job.userAgent)
                    .videoInfo?.thumbnail.orEmpty()
                if (refreshed != thumbnailUrl) bitmap = fetchBitmap(refreshed, job.referer, job.userAgent)
            }
            if (bitmap == null) {
                failedUntil[job.id] = System.currentTimeMillis() + 60_000L
                return@withContext null
            }
            try {
                if (job.id in deletedIds) return@withContext null
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${target.name}.tmp")
                try {
                    temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    if (!temporary.renameTo(target)) return@withContext null
                } finally {
                    temporary.delete()
                }
                return@withContext target
            } finally {
                bitmap.recycle()
            }
        } finally {
            lock.unlock()
        }
    }

    fun delete(context: Context, jobId: String) {
        deletedIds.add(jobId)
        file(context, jobId).delete()
        failedUntil.remove(jobId)
        locks.remove(jobId)
    }

    private fun fetchBitmap(source: String, referer: String?, userAgent: String?): Bitmap? {
        var url = source.takeIf { it.isNotBlank() } ?: return null
        repeat(4) {
            val parsed = try { URL(url) } catch (_: Exception) { return null }
            if (parsed.protocol !in listOf("http", "https") || !isPublicHost(parsed.host)) return null
            val connection = (parsed.openConnection() as? HttpURLConnection) ?: return null
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*")
                if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
                if (!userAgent.isNullOrBlank()) connection.setRequestProperty("User-Agent", userAgent)
                if (connection.responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    url = URL(parsed, location).toString()
                    return@repeat
                }
                if (connection.responseCode != 200 || connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return null
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_DOWNLOAD_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (!isSupportedImage(bytes)) return null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1024) sampleSize *= 2
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
                val maxSide = maxOf(decoded.width, decoded.height)
                if (maxSide <= 512) return decoded
                val factor = 512f / maxSide
                val scaled = Bitmap.createScaledBitmap(decoded,
                    (decoded.width * factor).toInt().coerceAtLeast(1),
                    (decoded.height * factor).toInt().coerceAtLeast(1), true)
                decoded.recycle()
                return scaled
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun isPublicHost(host: String): Boolean = try {
        host.isNotBlank() && InetAddress.getAllByName(host).all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }
    } catch (_: Exception) { false }

    private fun isSupportedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val webp = String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        val gif = String(bytes, 0, 3, Charsets.US_ASCII) == "GIF"
        return jpeg || png || webp || gif
    }
}
