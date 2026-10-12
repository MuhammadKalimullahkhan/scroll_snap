package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.R
import com.example.data.FrameStore
import com.example.data.ScreenshotSaver
import com.example.data.StitchAnalysis
import com.example.data.StitchEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service running with foregroundServiceType="mediaProjection".
 * Coordinates screen projection, frame analysis, floating controls, and stitching.
 */
class CaptureForegroundService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID_FGS = "channel_capture_fgs"
        private const val NOTIFICATION_ID_FGS = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var captureManager: ScreenCaptureManager? = null
    private var overlayController: OverlayViewController? = null
    private lateinit var frameStore: FrameStore
    private lateinit var screenshotSaver: ScreenshotSaver
    private lateinit var stitchEngine: StitchEngine

    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        frameStore = FrameStore(this)
        screenshotSaver = ScreenshotSaver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_STOP) {
            Log.i(TAG, "Received ACTION_STOP via notification")
            stopSessionAndCleanUp()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "Missing MediaProjection consent tokens in intent")
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Immediately start Foreground Service before touching MediaProjection
        startServiceInForeground()

        // 2. Initialize MediaProjection and single VirtualDisplay session
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                Log.e(TAG, "Failed to acquire MediaProjection from tokens")
                stopSessionAndCleanUp()
                return START_NOT_STICKY
            }

            captureManager = ScreenCaptureManager(this, mediaProjection) { reason ->
                showToast(reason)
                stopSessionAndCleanUp()
            }

            val mgr = captureManager!!
            stitchEngine = StitchEngine(
                statusBarHeight = mgr.statusBarHeight,
                navBarHeight = mgr.navBarHeight
            )
            frameStore.clearSession()

            setupOverlayController()
            overlayController?.show()

        } catch (e: Exception) {
            Log.e(TAG, "Error initiating capture session", e)
            stopSessionAndCleanUp()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_FGS,
            getString(R.string.notification_channel_capture_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_capture_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startServiceInForeground() {
        val stopIntent = Intent(this, CaptureForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_FGS)
            .setSmallIcon(R.drawable.ic_tile_capture)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_capture_close, getString(R.string.notification_action_stop), stopPendingIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_FGS,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun setupOverlayController() {
        overlayController = OverlayViewController(
            context = this,
            onCaptureClicked = { handleCaptureClick() },
            onSaveClicked = { handleSaveClick() },
            onCancelClicked = {
                showToast(getString(R.string.toast_session_ended))
                stopSessionAndCleanUp()
            }
        )
    }

    private fun handleCaptureClick() {
        if (isCapturing) return
        val mgr = captureManager ?: return

        serviceScope.launch {
            isCapturing = true
            overlayController?.setControlsEnabled(false)

            // Rotation check
            if (mgr.hasOrientationChanged()) {
                showToast(getString(R.string.toast_rotation_detected))
                stopSessionAndCleanUp()
                return@launch
            }

            try {
                overlayController?.hideTemporarilyAndCapture {
                    val bitmap = mgr.acquireLatestFrame()
                    if (bitmap == null) {
                        showToast("Failed to acquire frame. Try again.")
                        return@hideTemporarilyAndCapture
                    }

                    if (stitchEngine.isBlackFrame(bitmap)) {
                        bitmap.recycle()
                        showToast(getString(R.string.toast_secure_window))
                        return@hideTemporarilyAndCapture
                    }

                    val currentCount = frameStore.getFrameCount()
                    if (currentCount == 0) {
                        // First frame
                        frameStore.saveFrame(bitmap)
                        bitmap.recycle()
                        overlayController?.updateFrameCount(1)
                        showToast(getString(R.string.toast_frame_captured, 1))
                    } else {
                        // Compare against previous frame
                        val prevBmp = frameStore.loadFrame(currentCount - 1)
                        if (prevBmp != null) {
                            val analysis = stitchEngine.analyzeOverlap(prevBmp, bitmap)
                            prevBmp.recycle()

                            if (analysis is StitchAnalysis.NoScrollDetected) {
                                bitmap.recycle()
                                showToast(getString(R.string.toast_no_scroll_detected))
                                return@hideTemporarilyAndCapture
                            }
                        }

                        frameStore.saveFrame(bitmap)
                        bitmap.recycle()
                        val newCount = frameStore.getFrameCount()
                        overlayController?.updateFrameCount(newCount)
                        showToast(getString(R.string.toast_frame_captured, newCount))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during capture step", e)
            } finally {
                overlayController?.setControlsEnabled(true)
                isCapturing = false
            }
        }
    }

    private fun handleSaveClick() {
        if (isCapturing) return
        serviceScope.launch {
            val count = frameStore.getFrameCount()
            if (count == 0) {
                showToast("No frames captured yet. Tap capture first.")
                return@launch
            }

            isCapturing = true
            overlayController?.setControlsEnabled(false)
            showToast(getString(R.string.toast_stitching))

            // Hide overlay while stitching
            overlayController?.hide()

            val stitchedBitmap = withContext(Dispatchers.Default) {
                stitchEngine.stitchFrames(frameStore.getFrameFiles())
            }

            if (stitchedBitmap != null) {
                val savedUri = screenshotSaver.saveToMediaStore(stitchedBitmap)
                stitchedBitmap.recycle()
                if (savedUri != null) {
                    showToast(getString(R.string.toast_screenshot_saved))
                } else {
                    showToast("Failed to save screenshot to gallery.")
                }
            } else {
                showToast(getString(R.string.toast_stitch_failed))
            }

            stopSessionAndCleanUp()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun stopSessionAndCleanUp() {
        overlayController?.hide()
        overlayController = null

        captureManager?.release()
        captureManager = null

        frameStore.clearSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        overlayController?.hide()
        captureManager?.release()
        frameStore.clearSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
