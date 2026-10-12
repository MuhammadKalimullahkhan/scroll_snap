package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Manages the MediaProjection session, single VirtualDisplay, and ImageReader.
 * Converts hardware frames into clean Bitmaps with row-stride handling.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onSessionTerminated: (reason: String) -> Unit
) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME = "ScrollSnap_VirtualDisplay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    val displayWidth: Int
    val displayHeight: Int
    val densityDpi: Int
    val initialRotation: Int

    val statusBarHeight: Int
    val navBarHeight: Int

    init {
        val windowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        displayWidth = bounds.width()
        displayHeight = bounds.height()

        val displayMetrics = context.resources.displayMetrics
        densityDpi = displayMetrics.densityDpi

        initialRotation = getDisplayRotation()

        // Calculate system bars insets
        val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
        )
        statusBarHeight = insets.top.coerceAtLeast(0)
        navBarHeight = insets.bottom.coerceAtLeast(0)

        setupMediaProjectionCallback()
        setupVirtualDisplay()
    }

    private fun getDisplayRotation(): Int {
        return context.display?.rotation ?: Display.DEFAULT_DISPLAY
    }

    private fun setupMediaProjectionCallback() {
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system or user")
                mainHandler.post {
                    onSessionTerminated("Capture session stopped by user or system.")
                }
            }
        }, mainHandler)
    }

    private fun setupVirtualDisplay() {
        // Create ONE ImageReader for the entire session
        imageReader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            3
        )

        // Create ONE VirtualDisplay for the entire session.
        // Android 14+ prohibits creating multiple VirtualDisplays with the same MediaProjection token.
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            displayWidth,
            displayHeight,
            densityDpi,
            flags,
            imageReader?.surface,
            null,
            mainHandler
        )
        Log.i(TAG, "Initialized VirtualDisplay ${displayWidth}x$displayHeight @ $densityDpi dpi")
    }

    /**
     * Checks if display orientation changed since capture session started.
     */
    fun hasOrientationChanged(): Boolean {
        return getDisplayRotation() != initialRotation
    }

    /**
     * Acquires the latest screen frame from the ImageReader.
     * Strips row stride padding and returns a pristine Bitmap.
     */
    suspend fun acquireLatestFrame(): Bitmap? = withContext(Dispatchers.Default) {
        val reader = imageReader ?: return@withContext null

        // Try acquiring image with brief retry to allow frame rendering
        var image: Image? = null
        for (attempt in 0..4) {
            image = reader.acquireLatestImage()
            if (image != null) break
            delay(40)
        }

        if (image == null) {
            Log.w(TAG, "No frame available in ImageReader after retries")
            return@withContext null
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            val rowPadding = rowStride - pixelStride * displayWidth

            // Create temporary bitmap accommodating row stride padding
            val paddedBitmap = Bitmap.createBitmap(
                displayWidth + (rowPadding / pixelStride),
                displayHeight,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)

            // Crop out row-stride padding if present
            val cleanBitmap = if (paddedBitmap.width != displayWidth) {
                val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, displayWidth, displayHeight)
                paddedBitmap.recycle()
                cropped
            } else {
                paddedBitmap
            }

            cleanBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Image to Bitmap", e)
            null
        } finally {
            image.close()
        }
    }

    /**
     * Releases VirtualDisplay, ImageReader, and MediaProjection safely.
     */
    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing VirtualDisplay", e)
        }

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ImageReader", e)
        }

        try {
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
    }
}
