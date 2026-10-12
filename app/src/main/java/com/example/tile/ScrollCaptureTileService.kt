package com.example.tile

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.ui.CaptureTrampolineActivity

/**
 * Quick Settings Tile allowing fast access to scrolling screenshot capture from any screen.
 * Complies with Android 14+ PendingIntent requirements for tile expansion collapse.
 */
class ScrollCaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val hasOverlay = Settings.canDrawOverlays(this)
        tile.state = if (hasOverlay) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.subtitle = if (hasOverlay) "Ready" else "Setup required"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasOverlay || !hasNotification) {
            // Missing permissions: route to MainActivity with prominent disclosure screen
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_REQUEST_PERMISSIONS, true)
            }
            launchTarget(mainIntent)
            return
        }

        // Permissions present: launch transparent trampoline activity for MediaProjection consent
        val trampolineIntent = Intent(this, CaptureTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        launchTarget(trampolineIntent)
    }

    private fun launchTarget(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ (API 34) requires PendingIntent overload
            val pendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 1000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
