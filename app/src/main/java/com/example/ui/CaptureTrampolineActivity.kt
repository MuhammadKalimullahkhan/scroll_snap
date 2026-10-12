package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.service.CaptureForegroundService

/**
 * Lightweight, transparent trampoline Activity responsible for requesting
 * MediaProjection consent via the system prompt and forwarding tokens to
 * CaptureForegroundService upon user authorization.
 */
class CaptureTrampolineActivity : ComponentActivity() {

    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Android 14+ rule: Start ForegroundService ONLY after consent is granted
            val serviceIntent = Intent(this, CaptureForegroundService::class.java).apply {
                putExtra(CaptureForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        // Always finish trampoline immediately to return user to previous app
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            finish()
            return
        }

        try {
            captureConsentLauncher.launch(mpManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            finish()
        }
    }
}
