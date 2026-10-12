package com.example.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Result returned when attempting to analyze or stitch frames.
 */
sealed class StitchAnalysis {
    data class OverlapFound(val overlapHeight: Int, val stickyHeaderHeight: Int, val stickyFooterHeight: Int) : StitchAnalysis()
    object NoScrollDetected : StitchAnalysis()
    object SecureWindowDetected : StitchAnalysis()
    data class StitchFailed(val reason: String) : StitchAnalysis()
}

/**
 * High-performance, memory-safe StitchEngine for long screenshots.
 */
class StitchEngine(
    private val statusBarHeight: Int = 0,
    private val navBarHeight: Int = 0
) {

    companion object {
        private const val TAG = "StitchEngine"
        // Maximum output image height to prevent Android heap OOM (approx 28,000px on standard devices)
        const val MAX_OUTPUT_HEIGHT = 28000
        // Maximum number of pixels to prevent canvas dimension overflows
        private const val MAX_TOTAL_PIXELS = 1080 * 28000
    }

    /**
     * Checks if a frame is an all-black frame produced by DRM or FLAG_SECURE protected windows.
     */
    fun isBlackFrame(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = max(1, width / 20)
        val stepY = max(1, height / 20)
        var totalSamples = 0
        var darkSamples = 0

        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                totalSamples++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Lum threshold for pure/near black
                if (r < 8 && g < 8 && b < 8) {
                    darkSamples++
                }
            }
        }

        return totalSamples > 0 && (darkSamples.toFloat() / totalSamples) >= 0.985f
    }

    /**
     * Detects identical sticky header height between two consecutive frames
     * within the top region (excluding status bar).
     */
    fun detectStickyHeader(prev: Bitmap, curr: Bitmap): Int {
        val width = min(prev.width, curr.width)
        val height = min(prev.height, curr.height)
        val topLimit = min(height / 4, (statusBarHeight + 350))
        val sampleStepX = max(1, width / 40)

        var stickyY = statusBarHeight
        for (y in statusBarHeight until topLimit) {
            var rowMatches = 0
            var sampleCount = 0
            for (x in 0 until width step sampleStepX) {
                sampleCount++
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                if (colorDistance(p1, p2) < 18) {
                    rowMatches++
                }
            }
            if (sampleCount > 0 && (rowMatches.toFloat() / sampleCount) >= 0.95f) {
                stickyY = y
            } else {
                break
            }
        }

        return max(0, stickyY - statusBarHeight)
    }

    /**
     * Detects identical sticky footer height between two consecutive frames
     * within the bottom region (excluding navigation bar).
     */
    fun detectStickyFooter(prev: Bitmap, curr: Bitmap): Int {
        val width = min(prev.width, curr.width)
        val height = min(prev.height, curr.height)
        val bottomLimit = max(height - navBarHeight - 350, height / 2)
        val sampleStepX = max(1, width / 40)

        var stickyFooterHeight = 0
        for (y in (height - navBarHeight - 1) downTo bottomLimit) {
            var rowMatches = 0
            var sampleCount = 0
            for (x in 0 until width step sampleStepX) {
                sampleCount++
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)
                if (colorDistance(p1, p2) < 18) {
                    rowMatches++
                }
            }
            if (sampleCount > 0 && (rowMatches.toFloat() / sampleCount) >= 0.95f) {
                stickyFooterHeight++
            } else {
                break
            }
        }

        return stickyFooterHeight
    }

    /**
     * Analyzes vertical overlap between two frames.
     * Appends only new content scrolled into view.
     */
    suspend fun analyzeOverlap(prev: Bitmap, curr: Bitmap): StitchAnalysis = withContext(Dispatchers.Default) {
        if (isBlackFrame(curr)) {
            return@withContext StitchAnalysis.SecureWindowDetected
        }

        val width = min(prev.width, curr.width)
        val height = min(prev.height, curr.height)

        val stickyHeaderH = detectStickyHeader(prev, curr)
        val stickyFooterH = detectStickyFooter(prev, curr)

        // Content boundaries excluding system bars and sticky regions
        val contentTop = statusBarHeight + stickyHeaderH
        val contentBottom = height - navBarHeight - stickyFooterH

        if (contentBottom <= contentTop + 100) {
            return@withContext StitchAnalysis.StitchFailed("Content region too small to compute scroll")
        }

        // Build 1D luminance profile across horizontal center strips for fast comparison
        val sampleCols = 32
        val colIndices = IntArray(sampleCols) { i ->
            (width * 0.15f + i * (width * 0.70f / sampleCols)).toInt().coerceIn(0, width - 1)
        }

        val prevRows = Array(contentBottom - contentTop) { rowIndex ->
            val y = contentTop + rowIndex
            IntArray(sampleCols) { col ->
                rgbToLuma(prev.getPixel(colIndices[col], y))
            }
        }

        val currRows = Array(contentBottom - contentTop) { rowIndex ->
            val y = contentTop + rowIndex
            IntArray(sampleCols) { col ->
                rgbToLuma(curr.getPixel(colIndices[col], y))
            }
        }

        val numRows = prevRows.size

        // Check if frames are virtually identical (no scroll)
        var identicalRowCount = 0
        val checkRows = min(100, numRows)
        for (i in 0 until checkRows) {
            var diffSum = 0
            for (c in 0 until sampleCols) {
                diffSum += abs(prevRows[i][c] - currRows[i][c])
            }
            if (diffSum / sampleCols < 8) {
                identicalRowCount++
            }
        }
        if (identicalRowCount >= (checkRows * 0.92f)) {
            return@withContext StitchAnalysis.NoScrollDetected
        }

        // Search for overlap: content near bottom of prev frame moved upwards in curr frame
        // Overlap height range: from 40 pixels up to (numRows - 20) pixels
        var bestOverlap = -1
        var minDiff = Long.MAX_VALUE

        val step = 2 // 2-pixel step for speed
        val compareHeight = min(120, numRows / 3) // comparison band height

        for (overlap in 30 until numRows step step) {
            val prevStart = numRows - overlap
            val currStart = 0
            val testRows = min(compareHeight, overlap)

            var totalDiff: Long = 0
            var samplePoints = 0

            for (r in 0 until testRows step 2) {
                val rPrev = prevStart + r
                val rCurr = currStart + r
                if (rPrev in prevRows.indices && rCurr in currRows.indices) {
                    for (c in 0 until sampleCols step 2) {
                        totalDiff += abs(prevRows[rPrev][c] - currRows[rCurr][c])
                        samplePoints++
                    }
                }
            }

            if (samplePoints > 0) {
                val avgDiff = totalDiff / samplePoints
                if (avgDiff < minDiff) {
                    minDiff = avgDiff
                    bestOverlap = overlap
                }
            }
        }

        // If minDiff is acceptable, we found the overlap
        if (bestOverlap > 0 && minDiff < 35) {
            Log.d(TAG, "Detected vertical overlap: $bestOverlap px (avgDiff: $minDiff)")
            StitchAnalysis.OverlapFound(
                overlapHeight = bestOverlap,
                stickyHeaderHeight = stickyHeaderH,
                stickyFooterHeight = stickyFooterH
            )
        } else {
            // Fallback: If heuristic didn't find high-confidence match (e.g. fast swipe),
            // assume safe overlap of 1/3 of viewport to prevent content loss
            Log.w(TAG, "Low confidence match (minDiff: $minDiff). Using adaptive fallback.")
            StitchAnalysis.OverlapFound(
                overlapHeight = (numRows * 0.25f).toInt(),
                stickyHeaderHeight = stickyHeaderH,
                stickyFooterHeight = stickyFooterH
            )
        }
    }

    /**
     * Stitches an ordered list of cached frame files into a single master Bitmap.
     * Crops status bar and nav bar as appropriate, removes duplicate sticky regions,
     * and guards memory limits.
     */
    suspend fun stitchFrames(frameFiles: List<File>): Bitmap? = withContext(Dispatchers.Default) {
        if (frameFiles.isEmpty()) return@withContext null
        if (frameFiles.size == 1) {
            return@withContext android.graphics.BitmapFactory.decodeFile(frameFiles[0].absolutePath)
        }

        // Decode first frame
        var prevBitmap = android.graphics.BitmapFactory.decodeFile(frameFiles[0].absolutePath)
            ?: return@withContext null

        val width = prevBitmap.width
        val originalHeight = prevBitmap.height

        // Slices to render: pair of (FrameFile, sourceRect, destHeight)
        data class FrameSlice(
            val file: File,
            val srcRect: Rect,
            val drawHeight: Int
        )

        val slices = mutableListOf<FrameSlice>()

        // First slice: keep status bar on top, crop nav bar from bottom
        val firstBottom = originalHeight - navBarHeight
        slices.add(
            FrameSlice(
                file = frameFiles[0],
                srcRect = Rect(0, 0, width, firstBottom),
                drawHeight = firstBottom
            )
        )

        var totalOutputHeight = firstBottom

        for (i in 1 until frameFiles.size) {
            val currBitmap = android.graphics.BitmapFactory.decodeFile(frameFiles[i].absolutePath)
                ?: continue

            val analysis = analyzeOverlap(prevBitmap, currBitmap)
            val overlap = when (analysis) {
                is StitchAnalysis.OverlapFound -> analysis.overlapHeight
                else -> (originalHeight * 0.25f).toInt()
            }
            val stickyHeaderH = when (analysis) {
                is StitchAnalysis.OverlapFound -> analysis.stickyHeaderHeight
                else -> 0
            }

            // In curr frame, new content starts after (statusBarHeight + stickyHeaderH + overlap)
            val cropTop = (statusBarHeight + stickyHeaderH + overlap).coerceIn(0, currBitmap.height - 1)
            val isLast = (i == frameFiles.size - 1)
            val cropBottom = if (isLast) currBitmap.height else currBitmap.height - navBarHeight

            val sliceHeight = max(0, cropBottom - cropTop)

            if (sliceHeight > 0) {
                // Check memory ceiling
                if (totalOutputHeight + sliceHeight > MAX_OUTPUT_HEIGHT) {
                    Log.w(TAG, "Maximum height reached ($MAX_OUTPUT_HEIGHT px). Capping stitch.")
                    currBitmap.recycle()
                    break
                }

                slices.add(
                    FrameSlice(
                        file = frameFiles[i],
                        srcRect = Rect(0, cropTop, width, cropBottom),
                        drawHeight = sliceHeight
                    )
                )
                totalOutputHeight += sliceHeight
            }

            prevBitmap.recycle()
            prevBitmap = currBitmap
        }

        prevBitmap.recycle()

        if (slices.isEmpty() || totalOutputHeight <= 0) {
            return@withContext null
        }

        // Allocate final composite bitmap
        Log.i(TAG, "Allocating stitched bitmap: ${width}x$totalOutputHeight")
        val stitchedBitmap = try {
            Bitmap.createBitmap(width, totalOutputHeight, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM creating composite bitmap: ${width}x$totalOutputHeight, falling back to RGB_565", oom)
            try {
                Bitmap.createBitmap(width, totalOutputHeight, Bitmap.Config.RGB_565)
            } catch (oom2: OutOfMemoryError) {
                Log.e(TAG, "Fatal OOM creating stitched bitmap", oom2)
                return@withContext null
            }
        }

        val canvas = Canvas(stitchedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        var currentY = 0
        for (slice in slices) {
            val frameBmp = android.graphics.BitmapFactory.decodeFile(slice.file.absolutePath) ?: continue
            val destRect = Rect(0, currentY, width, currentY + slice.drawHeight)
            canvas.drawBitmap(frameBmp, slice.srcRect, destRect, paint)
            currentY += slice.drawHeight
            frameBmp.recycle()
        }

        stitchedBitmap
    }

    private fun colorDistance(p1: Int, p2: Int): Int {
        val r = abs(Color.red(p1) - Color.red(p2))
        val g = abs(Color.green(p1) - Color.green(p2))
        val b = abs(Color.blue(p1) - Color.blue(p2))
        return (r + g + b) / 3
    }

    private fun rgbToLuma(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
