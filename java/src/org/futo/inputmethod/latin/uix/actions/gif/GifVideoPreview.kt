package org.futo.inputmethod.latin.uix.actions.gif

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.sync.Semaphore

/** Limits how many previews decode at once, to spare battery, data and hardware decoders */
internal val VideoPreviewSlots = Semaphore(6)

/**
 * Muted, looping, center-cropped video for GIF previews (RedGIFs only has MP4s).
 * Streams straight from the URL; call [release] when the view goes away.
 */
class LoopingVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var url: String? = null
    private var videoW = 0
    private var videoH = 0

    /** Called once the first frame is on screen, so the still thumbnail can be hidden */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setVideo(newUrl: String) {
        if (newUrl == url) return
        release()
        url = newUrl
        start()
    }

    private fun start() {
        val texture = surfaceTexture ?: return
        val source = url ?: return
        if (player != null) return

        val newSurface = Surface(texture)
        surface = newSurface
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            setSurface(newSurface)
            isLooping = true
            setVolume(0f, 0f)
            setOnPreparedListener { it.start() }
            setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                applyCenterCrop()
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame?.invoke()
                false
            }
            // On error the still thumbnail simply stays visible
            setOnErrorListener { _, _, _ -> true }
        }
        try {
            mediaPlayer.setDataSource(source)
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            release()
        }
    }

    fun release() {
        player?.let {
            it.setOnInfoListener(null)
            it.release()
        }
        player = null
        surface?.release()
        surface = null
    }

    private fun applyCenterCrop() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0 || videoW <= 0 || videoH <= 0) return
        // TextureView stretches the video to the view; scale it back up so it fills and crops instead
        val scale = maxOf(w / videoW, h / videoH)
        val sx = videoW * scale / w
        val sy = videoH * scale / h
        setTransform(Matrix().apply { setScale(sx, sy, w / 2f, h / 2f) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCenterCrop()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = start()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = applyCenterCrop()
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
}
