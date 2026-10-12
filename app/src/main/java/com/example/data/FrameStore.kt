package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages caching captured frames on disk to prevent OutOfMemoryError
 * during extended scrolling screenshot sessions.
 */
class FrameStore(private val context: Context) {

    private val cacheDir = File(context.cacheDir, "scroll_capture_session").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val frameFiles = mutableListOf<File>()

    /**
     * Clears any leftover cached frames from previous sessions.
     */
    fun clearSession() {
        frameFiles.clear()
        cacheDir.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete cached frame: ${file.name}", e)
            }
        }
    }

    /**
     * Saves a captured Bitmap to disk cache and returns its index.
     */
    suspend fun saveFrame(bitmap: Bitmap): Int = withContext(Dispatchers.IO) {
        val index = frameFiles.size
        val file = File(cacheDir, "frame_${System.currentTimeMillis()}_$index.png")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
            frameFiles.add(file)
            index
        } catch (e: IOException) {
            Log.e(TAG, "Error saving frame to disk cache", e)
            -1
        }
    }

    /**
     * Returns the count of captured frames in the current session.
     */
    fun getFrameCount(): Int = frameFiles.size

    /**
     * Reads a cached frame bitmap from disk.
     */
    suspend fun loadFrame(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (index !in frameFiles.indices) return@withContext null
        val file = frameFiles[index]
        if (!file.exists()) return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * Returns all frame files in order.
     */
    fun getFrameFiles(): List<File> = frameFiles.toList()

    companion object {
        private const val TAG = "FrameStore"
    }
}
