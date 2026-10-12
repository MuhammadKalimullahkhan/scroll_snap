package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata for a saved screenshot displayed in the app gallery.
 */
data class SavedScreenshotItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

/**
 * Handles saving stitched screenshot bitmaps to MediaStore in Pictures/Screenshots
 * with IS_PENDING safety, and posting the success notification.
 */
class ScreenshotSaver(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotSaver"
        const val CHANNEL_ID_SAVED = "channel_screenshot_saved"
        const val NOTIFICATION_ID_SAVED_BASE = 2000
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID_SAVED,
            context.getString(R.string.notification_channel_saved_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_saved_desc)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Saves the bitmap to MediaStore Pictures/Screenshots and returns its Uri.
     */
    suspend fun saveToMediaStore(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val isVeryTall = bitmap.height > 15000
        val extension = if (isVeryTall) "jpg" else "png"
        val mimeType = if (isVeryTall) "image/jpeg" else "image/png"
        val fileName = "ScrollSnap_$timestamp.$extension"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val collectionUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val imageUri = resolver.insert(collectionUri, values)

        if (imageUri == null) {
            Log.e(TAG, "Failed to create MediaStore entry for $fileName")
            return@withContext null
        }

        try {
            resolver.openOutputStream(imageUri).use { outStream ->
                if (outStream == null) throw IOException("Failed to open output stream for $imageUri")
                if (isVeryTall) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, outStream)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outStream)
                }
                outStream.flush()
            }

            // Mark IS_PENDING as 0 to make the screenshot accessible system-wide
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(imageUri, values, null, null)

            Log.i(TAG, "Successfully saved screenshot to $imageUri")
            showSavedNotification(imageUri, bitmap)
            imageUri
        } catch (e: Exception) {
            Log.e(TAG, "Error writing bitmap data to MediaStore", e)
            try {
                resolver.delete(imageUri, null, null)
            } catch (delEx: Exception) {
                Log.w(TAG, "Failed to clean up pending entry", delEx)
            }
            null
        }
    }

    private fun showSavedNotification(uri: Uri, previewBitmap: Bitmap) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 10000).toInt(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Generate scaled thumbnail for notification large icon
        val thumbSize = 128
        val thumb = try {
            val scale = thumbSize.toFloat() / previewBitmap.width
            val targetH = (previewBitmap.height * scale).toInt().coerceIn(thumbSize, thumbSize * 2)
            Bitmap.createScaledBitmap(previewBitmap, thumbSize, targetH, true)
        } catch (e: Exception) {
            null
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val sharePendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() % 10000).toInt() + 1,
            Intent.createChooser(shareIntent, "Share Screenshot"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SAVED)
            .setSmallIcon(R.drawable.ic_tile_capture)
            .setContentTitle(context.getString(R.string.notification_saved_title))
            .setContentText(context.getString(R.string.notification_saved_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .apply {
                if (thumb != null) {
                    setLargeIcon(thumb)
                }
            }
            .addAction(R.drawable.ic_capture_save, context.getString(R.string.notification_saved_action_view), pendingIntent)
            .addAction(R.drawable.ic_tile_capture, "Share", sharePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_SAVED_BASE + (System.currentTimeMillis() % 100).toInt(), notification)
    }

    /**
     * Queries MediaStore for existing screenshots saved by ScrollSnap in Pictures/Screenshots.
     */
    suspend fun querySavedScreenshots(): List<SavedScreenshotItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<SavedScreenshotItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("ScrollSnap_%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val name = cursor.getString(nameCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)

                    list.add(
                        SavedScreenshotItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            width = width,
                            height = height,
                            sizeBytes = size,
                            dateAddedSec = date
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query saved screenshots", e)
        }
        list
    }
}
