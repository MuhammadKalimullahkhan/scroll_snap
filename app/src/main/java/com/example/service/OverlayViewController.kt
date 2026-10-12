package com.example.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Controls the floating classic View overlay attached to WindowManager.
 * Manages dragging, button interactions, badge counts, and clean temporary hiding.
 */
class OverlayViewController(
    private val context: Context,
    private val onCaptureClicked: () -> Unit,
    private val onSaveClicked: () -> Unit,
    private val onCancelClicked: () -> Unit
) {

    companion object {
        private const val TAG = "OverlayViewController"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    private var tvFrameCounter: TextView? = null
    private var btnCapture: ImageButton? = null
    private var btnSave: ImageButton? = null
    private var btnCancel: ImageButton? = null
    private var dragHandle: ImageView? = null

    fun show() {
        if (isAttached) return

        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.overlay_capture_controls, null)

        val view = overlayView ?: return

        tvFrameCounter = view.findViewById(R.id.tv_frame_counter)
        btnCapture = view.findViewById(R.id.btn_capture_step)
        btnSave = view.findViewById(R.id.btn_save_finish)
        btnCancel = view.findViewById(R.id.btn_cancel_session)
        dragHandle = view.findViewById(R.id.btn_drag_handle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 280
        }
        layoutParams = params

        setupInteractions()

        try {
            windowManager.addView(view, params)
            isAttached = true
            Log.i(TAG, "Floating overlay added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating overlay", e)
        }
    }

    private fun setupInteractions() {
        btnCapture?.setOnClickListener {
            onCaptureClicked()
        }

        btnSave?.setOnClickListener {
            onSaveClicked()
        }

        btnCancel?.setOnClickListener {
            onCancelClicked()
        }

        // Draggable behavior on drag handle and root
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val touchListener = View.OnTouchListener { _, event ->
            val params = layoutParams ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = (initialX + dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    if (isAttached && overlayView != null) {
                        try {
                            windowManager.updateViewLayout(overlayView, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update overlay position", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        dragHandle?.setOnTouchListener(touchListener)
        overlayView?.setOnTouchListener(touchListener)
    }

    /**
     * Updates frame count badge.
     */
    fun updateFrameCount(count: Int) {
        mainHandler.post {
            tvFrameCounter?.text = count.toString()
        }
    }

    /**
     * Temporarily hides the overlay so it does not appear in the screenshot,
     * waits for display rendering, executes the capture, and restores visibility.
     */
    suspend fun hideTemporarilyAndCapture(captureAction: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            overlayView?.visibility = View.INVISIBLE
        }

        // Wait brief delay to allow SurfaceFlinger / display compositor to render next clean frame
        delay(90)

        try {
            captureAction()
        } finally {
            withContext(Dispatchers.Main) {
                overlayView?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Enables or disables buttons during processing.
     */
    fun setControlsEnabled(enabled: Boolean) {
        mainHandler.post {
            btnCapture?.isEnabled = enabled
            btnSave?.isEnabled = enabled
            btnCancel?.isEnabled = enabled
            overlayView?.alpha = if (enabled) 1.0f else 0.6f
        }
    }

    /**
     * Removes overlay from WindowManager.
     */
    fun hide() {
        if (!isAttached || overlayView == null) return
        try {
            windowManager.removeView(overlayView)
            Log.i(TAG, "Floating overlay removed from WindowManager")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay view", e)
        } finally {
            overlayView = null
            isAttached = false
        }
    }
}
