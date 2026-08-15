package com.johan.ghostball

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler

/**
 * Thin wrapper around a single [MediaProjection] session used by
 * [OverlayService] for on-demand screenshot capture.
 *
 * The session is acquired once after user consent and kept alive for the whole
 * lifetime of the service (no repeated consent dialogs). Each
 * [captureOneFrame] call creates a fresh [ImageReader] + [VirtualDisplay],
 * grabs exactly ONE frame, then releases both immediately — no continuous
 * capture stream runs in the background.
 */
class ScreenCapture(
    private val context: Context,
    private val mainHandler: Handler
) {

    private var mediaProjection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var onStopped: (() -> Unit)? = null

    val isActive: Boolean get() = mediaProjection != null

    /**
     * Binds the user-granted capture consent to a [MediaProjection] session.
     * If the system later revokes it (user stops it in quick settings, for
     * example), [onStopped] fires and the session becomes inactive — the next
     * capture will have to re-ask for consent.
     */
    fun acquire(resultCode: Int, data: Intent, onStopped: () -> Unit) {
        release()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data) ?: return
        this.onStopped = onStopped
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mediaProjection = null
                    this@ScreenCapture.onStopped?.invoke()
                }
            }
            projection.registerCallback(callback, mainHandler)
        }
        mediaProjection = projection
    }

    fun release() {
        val cb = callback
        if (cb != null) {
            runCatching { mediaProjection?.unregisterCallback(cb) }
            callback = null
        }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        onStopped = null
    }

    /**
     * Captures one full-screen frame. Both callbacks run on the main thread.
     * A 3-second timeout guards against never-firing listeners.
     */
    fun captureOneFrame(
        width: Int,
        height: Int,
        densityDpi: Int,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val projection = mediaProjection
        if (projection == null) {
            onError("Sin sesión de captura")
            return
        }

        var finished = false
        var timeout: Runnable? = null
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        fun finish(bitmap: Bitmap?, error: String?) {
            if (finished) return
            finished = true
            timeout?.let { mainHandler.removeCallbacks(it) }
            runCatching { reader.close() }
            runCatching { virtualDisplay?.release() }
            if (bitmap != null) {
                mainHandler.post { onBitmap(bitmap) }
            } else {
                mainHandler.post { onError(error ?: "Error de captura") }
            }
        }

        timeout = Runnable { finish(null, "Tiempo de captura agotado") }
        mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image == null) {
                finish(null, "Sin imagen disponible")
                return@setOnImageAvailableListener
            }
            val bitmap = imageToBitmap(image, image.width, image.height)
            image.close()
            finish(bitmap, null)
        }, mainHandler)

        runCatching {
            virtualDisplay = projection.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
        }.onFailure { finish(null, "No se pudo crear la captura") }
    }

    /**
     * Converts one RGBA_8888 [Image] plane to a [Bitmap], handling row-stride
     * padding (which shows up as "shifted" images when ignored).
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        full.copyPixelsFromBuffer(plane.buffer)
        return if (paddedWidth == width) full
        else Bitmap.createBitmap(full, 0, 0, width, height)
    }

    companion object {
        const val DISPLAY_NAME = "ghostball_capture"
        private const val CAPTURE_TIMEOUT_MS = 3000L
    }
}