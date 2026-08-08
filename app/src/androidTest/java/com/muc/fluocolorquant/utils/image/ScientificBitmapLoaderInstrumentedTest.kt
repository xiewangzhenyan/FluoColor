package com.muc.fluocolorquant.utils.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 固定科研图片进入定位算法前的EXIF方向处理，防止90°照片被错误解释为孔板规格。 */
@RunWith(AndroidJUnit4::class)
class ScientificBitmapLoaderInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val loader = ScientificBitmapLoader(context)

    @Test
    fun absoluteJpegPath_appliesExifRotationBeforeReturningBitmap() {
        runBlocking {
            val source = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(72, 118, 146))
            }
            val file = File(context.cacheDir, "plate96-exif-rotate-90.jpg")
            FileOutputStream(file).use { output ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 96, output))
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_ROTATE_90.toString()
                )
                saveAttributes()
            }

            val loaded = requireNotNull(loader.load(file.absolutePath))

            assertEquals(90, loaded.exifRotationDegrees)
            assertFalse(loaded.exifFlipped)
            assertEquals(24, loaded.bitmap.width)
            assertEquals(40, loaded.bitmap.height)
            source.recycle()
            loaded.bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun flipThenRotate_keepsExactCornerCorrespondence() {
        val source = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(2, 0, Color.GREEN)
            setPixel(0, 1, Color.BLUE)
            setPixel(2, 1, Color.YELLOW)
        }

        val transformed = loader.applyExif(source, rotationDegrees = 90, flipped = true)

        assertEquals(2, transformed.width)
        assertEquals(3, transformed.height)
        assertEquals(Color.YELLOW, transformed.getPixel(0, 0))
        assertEquals(Color.GREEN, transformed.getPixel(1, 0))
        assertEquals(Color.BLUE, transformed.getPixel(0, 2))
        assertEquals(Color.RED, transformed.getPixel(1, 2))
        source.recycle()
        transformed.recycle()
    }
}
