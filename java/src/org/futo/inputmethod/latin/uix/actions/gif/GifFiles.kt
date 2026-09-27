package org.futo.inputmethod.latin.uix.actions.gif

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Build
import com.squareup.gifencoder.GifEncoder
import com.squareup.gifencoder.ImageOptions
import com.squareup.gifencoder.MedianCutQuantizer
import com.squareup.gifencoder.NearestColorDitherer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GifFiles {
    private const val DIR = "gif"
    private const val MAX_DOWNLOAD_BYTES = 40L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L

    // Converted GIFs are kept small so they encode quickly and stay under messaging app limits
    private const val CONVERT_MAX_WIDTH = 320
    private const val CONVERT_FPS = 10
    private const val CONVERT_MAX_MS = 6_000L

    /** File names handed out through the content provider */
    val SAFE_NAME = Regex("^[0-9a-f]{40}(_conv)?\\.(gif|mp4|webp|jpg)$")

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.')) {
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "webp" -> "image/webp"
        "jpg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    /** Resolves a provider request to a file inside the cache dir, rejecting anything else */
    fun resolve(context: Context, name: String): File {
        require(SAFE_NAME.matches(name)) { "Invalid gif file name" }
        return File(dir(context), name)
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (ext in setOf("gif", "mp4", "webp", "jpg")) ext else if (ext == "jpeg") "jpg" else "gif"
    }

    suspend fun download(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val file = File(dir(context), "${sha1(url)}.${extensionOf(url)}")
        if (file.exists() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }

        val tmp = File(file.path + ".tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "FUTO-Keyboard-Cow/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw GifApiException("Download failed (HTTP ${conn.responseCode})", conn.responseCode)
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) throw GifApiException("File is too large")
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(file)) throw IOException("Could not save download")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
        file
    }

    /** Decodes a grid preview. Animated (AnimatedImageDrawable) on Android 9+, first frame otherwise. */
    suspend fun loadPreview(context: Context, media: GifMedia, targetWidth: Int): Drawable? {
        val file = download(context, media.url)
        return withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val w = info.size.width
                    if (w > targetWidth) {
                        decoder.setTargetSize(targetWidth, (info.size.height * targetWidth.toFloat() / w).roundToInt().coerceAtLeast(1))
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.let { BitmapDrawable(context.resources, it) }
            }
        }
    }

    /**
     * For apps that take GIFs but not videos: turns an MP4 into a small looping GIF
     * (up to 6 seconds, 10 fps, 320px wide).
     */
    suspend fun convertMp4ToGif(context: Context, mp4: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Default) {
        val out = File(dir(context), mp4.nameWithoutExtension + "_conv.gif")
        if (out.exists() && out.length() > 0) return@withContext out

        val retriever = MediaMetadataRetriever()
        val tmp = File(out.path + ".tmp")
        try {
            retriever.setDataSource(mp4.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var videoW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var videoH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = videoW; videoW = videoH; videoH = t }
            if (videoW <= 0 || videoH <= 0) throw IOException("Could not read video")

            val outW = min(CONVERT_MAX_WIDTH, videoW)
            val outH = max(1, (videoH * outW.toFloat() / videoW).roundToInt())
            val clipMs = min(if (durationMs > 0) durationMs else CONVERT_MAX_MS, CONVERT_MAX_MS)
            val frameCount = max(1, (clipMs * CONVERT_FPS / 1000).toInt())
            val frameDelayMs = 1000L / CONVERT_FPS

            val options = ImageOptions()
                .setDelay(frameDelayMs, TimeUnit.MILLISECONDS)
                .setColorQuantizer(MedianCutQuantizer.INSTANCE)
                .setDitherer(NearestColorDitherer.INSTANCE)

            tmp.outputStream().buffered().use { os ->
                val encoder = GifEncoder(os, outW, outH, 0)
                val pixels = IntArray(outW * outH)
                for (i in 0 until frameCount) {
                    coroutineContext.ensureActive()
                    val timeUs = i * frameDelayMs * 1000L
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } ?: continue

                    val scaled = if (frame.width != outW || frame.height != outH) {
                        Bitmap.createScaledBitmap(frame, outW, outH, true).also { if (it !== frame) frame.recycle() }
                    } else frame

                    scaled.getPixels(pixels, 0, outW, 0, 0, outW, outH)
                    scaled.recycle()
                    encoder.addImage(pixels, outW, options)
                    onProgress((i + 1).toFloat() / frameCount)
                }
                encoder.finishEncoding()
            }
            if (!tmp.renameTo(out)) throw IOException("Could not save GIF")
        } finally {
            retriever.release()
            tmp.delete()
        }
        out
    }

    /** Deletes the least recently used files once the cache grows past its limit */
    suspend fun trimCache(context: Context) = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles()?.filter { it.isFile } ?: return@withContext
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return@withContext
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }
}
