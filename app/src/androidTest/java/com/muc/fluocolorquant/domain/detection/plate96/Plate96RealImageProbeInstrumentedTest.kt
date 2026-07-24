package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * 可选实拍探针。
 *
 * 调用方通过 instrumentation 参数 `plate96ImagePath` 指向设备上的图片；固定CI没有该图片时
 * 自动跳过，因此不会把用户个人实验图误提交到仓库，也不会让普通回归依赖外部状态。
 */
@RunWith(AndroidJUnit4::class)
class Plate96RealImageProbeInstrumentedTest {
    @Test
    fun suppliedRealImage_runsCompletePlate96Localization() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val imagePath = InstrumentationRegistry.getArguments().getString(ARGUMENT_IMAGE_PATH)
        assumeTrue("未提供96孔板实拍路径，跳过可选探针", !imagePath.isNullOrBlank())
        val imageFile = File(requireNotNull(imagePath))
        assumeTrue("96孔板实拍文件不存在：$imagePath", imageFile.exists())
        check(OpenCVLoader.initDebug()) { "OpenCV初始化失败" }
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: error("无法解码96孔板实拍图")
        try {
            val locator = Plate96Locator(
                objectDetector = Plate96YoloDetector(instrumentation.targetContext),
                circleRefiner = Plate96CircleRefiner(),
                orientationResolver = Plate96OrientationResolver(),
                gridAssembler = Plate96GridAssembler()
            )
            val session = locator.localizeSession(bitmap)
            Log.i(
                TAG,
                "实拍定位：observed=${session.result.diagnostics.observedSiteCount}, " +
                    "refined=${session.result.diagnostics.shapeRefinedSiteCount}, " +
                    "imputed=${session.result.diagnostics.imputedSiteCount}, " +
                    "orientation=${session.result.orientation.sourceRows}x${session.result.orientation.sourceColumns}"
            )
            assertEquals(96, session.result.sites.size)
            assertEquals("A1", session.result.sites.first().displayLabel)
            assertEquals("H12", session.result.sites.last().displayLabel)
            assertEquals(8, session.result.orientation.sourceRows)
            assertEquals(12, session.result.orientation.sourceColumns)
            assertTrue(session.result.diagnostics.observedSiteCount >= MINIMUM_REAL_OBSERVED_SITES)
            assertTrue(session.result.diagnostics.shapeRefinedSiteCount >= MINIMUM_REAL_REFINED_SITES)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG: String = "Plate96RealImageProbe"
        const val ARGUMENT_IMAGE_PATH: String = "plate96ImagePath"
        const val MINIMUM_REAL_OBSERVED_SITES: Int = 72
        const val MINIMUM_REAL_REFINED_SITES: Int = 90
    }
}
