package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.FrameStore
import com.example.data.StitchAnalysis
import com.example.data.StitchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ScrollSnap", appName)
    }

    @Test
    fun `detect black frame flags secure window`() {
        val blackBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        blackBmp.eraseColor(Color.BLACK)

        val engine = StitchEngine()
        assertTrue(engine.isBlackFrame(blackBmp))

        val whiteBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        whiteBmp.eraseColor(Color.WHITE)
        assertFalse(engine.isBlackFrame(whiteBmp))
    }

    @Test
    fun `detect no scroll between identical frames`() = runBlocking {
        val bmp1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bmp1.eraseColor(Color.RED)
        val bmp2 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        bmp2.eraseColor(Color.RED)

        val engine = StitchEngine()
        val analysis = engine.analyzeOverlap(bmp1, bmp2)
        assertEquals(StitchAnalysis.NoScrollDetected, analysis)
    }

    @Test
    fun `frame store saves and counts frames`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val frameStore = FrameStore(context)
        frameStore.clearSession()

        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)

        val index = frameStore.saveFrame(bmp)
        assertEquals(0, index)
        assertEquals(1, frameStore.getFrameCount())

        val loaded = frameStore.loadFrame(0)
        assertTrue(loaded != null)
        assertEquals(50, loaded?.width)
        assertEquals(50, loaded?.height)

        frameStore.clearSession()
        assertEquals(0, frameStore.getFrameCount())
    }
}
