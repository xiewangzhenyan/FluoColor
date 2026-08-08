package com.muc.fluocolorquant.domain.spectrum

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.utils.math.SpectrumCVUtils
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * 光谱通道数链路回归。
 *
 * 修复前：项目创建从不写入 `Project.spectrumColumnCount`，它只能落到默认值 1；标定页按
 * `project.spectrumColumnCount` 检测轨道，因此无论用户选了几个分析物都只切出 1 条轨道，
 * 逐通道的分析物归属（`spectrumColumnMappingJson`）也一并丢失。
 *
 * 本测试用用户实拍的 9 通道光谱图钉死两件事：
 * 1. 期望通道数确实会改变检测结果——传 1 只得到 1 条，传 9 能得到 9 条；
 * 2. 9 条轨道在几何上是真实、互不重叠、按左右有序的，而不是把同一条切成多份。
 *
 * 前者正是回归本身：只要有人再把通道数写死，第 2 个断言立刻失败。
 */
@RunWith(AndroidJUnit4::class)
class SpectrumChannelPipelineTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `实拍光谱图的轨道数由期望通道数决定而不是固定为一`() {
        val bitmap = loadCorpusBitmap()
        try {
            val single = SpectrumCVUtils.detectSpectrumTracks(bitmap, 1)
            assertEquals("期望 1 条时只应切出 1 条轨道", 1, single.size)

            val full = SpectrumCVUtils.detectSpectrumTracks(bitmap, EXPECTED_TRACKS)
            Log.i(
                LOG_TAG,
                "SPECTRUM_TRACKS expected=$EXPECTED_TRACKS actual=${full.size} " +
                    "rects=${full.joinToString { "${it.left},${it.top},${it.width()}x${it.height()}" }}"
            )
            assertEquals(
                "9 通道实拍图应切出 9 条轨道；若退回 1 说明通道数又被写死",
                EXPECTED_TRACKS,
                full.size
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `九条轨道互不重叠且按从左到右有序`() {
        val bitmap = loadCorpusBitmap()
        try {
            val tracks = SpectrumCVUtils.detectSpectrumTracks(bitmap, EXPECTED_TRACKS)
            assertEquals(EXPECTED_TRACKS, tracks.size)

            val sorted = tracks.sortedBy { it.left }
            assertEquals("检测结果本身就应按左右有序，避免下游按索引错配分析物", tracks, sorted)

            sorted.zipWithNext().forEach { (left, right) ->
                assertTrue(
                    "相邻轨道不应重叠：${left.left}+${left.width()} 越过了 ${right.left}",
                    left.left + left.width() <= right.left
                )
            }
            sorted.forEach { rect ->
                assertTrue("轨道宽高必须为正：$rect", rect.width() > 0 && rect.height() > 0)
                assertTrue("轨道不应超出图像边界：$rect", rect.left >= 0 && rect.top >= 0)
                assertTrue(
                    "轨道不应覆盖整幅图像，否则说明分离失败：$rect",
                    rect.width() < bitmap.width
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `语料图片字节与冻结摘要一致`() {
        // 输入被钉死，输出的任何变化才能归因到代码。
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(IMAGE_ASSET).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it) }
        assertEquals("实拍光谱语料已被改动", EXPECTED_SHA256, digest)
    }

    private fun loadCorpusBitmap() =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open(IMAGE_ASSET)
            .use { input ->
                requireNotNull(BitmapFactory.decodeStream(input)) { "无法解码光谱语料图片" }
            }

    private companion object {
        const val LOG_TAG: String = "SpectrumChannelPipeline"
        const val IMAGE_ASSET: String = "spectrum_v1/images/spectrum_9ch.png"
        const val EXPECTED_TRACKS: Int = 9
        const val EXPECTED_SHA256: String =
            "5379FDF3A450EF4C0E80CE1871E348F4B6351BE92AC3FBBF08B70E52B2E2F9FF"
    }
}
