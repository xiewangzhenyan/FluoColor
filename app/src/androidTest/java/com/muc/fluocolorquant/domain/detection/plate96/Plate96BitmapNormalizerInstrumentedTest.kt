package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 验证整数方向校正不会插值或打乱四角像素。 */
@RunWith(AndroidJUnit4::class)
class Plate96BitmapNormalizerInstrumentedTest {
    @Test
    fun rotate90Clockwise_keepsExactCornerColors() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(2, 0, Color.GREEN)
            setPixel(0, 1, Color.BLUE)
            setPixel(2, 1, Color.YELLOW)
        }
        val orientation = Plate96LayoutContract.orientation(
            originCorner = ArrayOriginCorner.BOTTOM_LEFT,
            source = ArrayOrientationSource.USER_CONFIRMED,
            confidence = 1.0
        )

        val normalized = Plate96BitmapNormalizer.normalize(source, orientation)

        assertEquals(2, normalized.width)
        assertEquals(3, normalized.height)
        assertEquals(Color.BLUE, normalized.getPixel(0, 0))
        assertEquals(Color.RED, normalized.getPixel(1, 0))
        assertEquals(Color.YELLOW, normalized.getPixel(0, 2))
        assertEquals(Color.GREEN, normalized.getPixel(1, 2))
        source.recycle()
        normalized.recycle()
    }
}
