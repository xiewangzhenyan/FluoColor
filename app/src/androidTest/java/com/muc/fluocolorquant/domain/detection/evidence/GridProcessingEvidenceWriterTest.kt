package com.muc.fluocolorquant.domain.detection.evidence

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.grid.PgGridLocatorConfig
import com.muc.fluocolorquant.domain.detection.photometry.PgQuantSampler
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/** 验证一次真实 10×10 运行会写出九张可解码、可校验的处理中间证据。 */
@RunWith(AndroidJUnit4::class)
class GridProcessingEvidenceWriterTest {

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
    }

    @Test
    fun `完整定位与光度结果生成九张有序PNG证据`() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets
            .open("pg_grid/synthetic_10x10_dark_squares.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
        val grid = OpenCvPgGridLocator().locate(
            bitmap,
            PgGridLocatorConfig(10, 10, GridTargetPolarity.DARK)
        )
        val quant = PgQuantSampler.sample(bitmap, grid)
        val writer = AndroidGridProcessingEvidenceWriter(instrumentation.targetContext)
        val runId = "writer-test-${System.nanoTime()}"

        val records = writer.write(runId, bitmap, grid, quant)

        assertEquals(9, records.size)
        assertEquals(
            listOf(
                CaptureRole.PROCESS_ORIGINAL_GEOMETRY,
                CaptureRole.PROCESS_CANDIDATE_RESPONSE,
                CaptureRole.PROCESS_RECTIFIED,
                CaptureRole.PROCESS_GRID_OVERLAY,
                CaptureRole.PROCESS_ROI_BACKGROUND,
                CaptureRole.PROCESS_BACKGROUND_FIELD,
                CaptureRole.PROCESS_SIGNAL_HEATMAP,
                CaptureRole.PROCESS_SNR_HEATMAP,
                CaptureRole.PROCESS_CORRECTED_COLOR
            ),
            records.map(GridProcessingEvidenceRecord::role)
        )
        records.forEachIndexed { index, record ->
            val file = File(record.path)
            assertTrue("证据文件不存在：${record.path}", file.isFile)
            assertTrue("证据文件为空：${record.path}", file.length() > 0L)
            val decoded = BitmapFactory.decodeFile(record.path)
            assertTrue("证据 PNG 无法解码：${record.path}", decoded != null)
            requireNotNull(decoded).recycle()
            assertEquals(record.checksumSha256, sha256(file))

            // 项目当前锁定的 Gson 版本仍使用实例解析 API；这里保持与生产代码兼容，
            // 避免仅因测试使用了新版静态 API 而阻断整套设备端回归。
            @Suppress("DEPRECATION")
            val metadata = JsonParser().parse(record.metadataJson).asJsonObject
            assertEquals(GRID_PROCESSING_EVIDENCE_SCHEMA, metadata["schemaVersion"].asString)
            assertEquals(index + 1, metadata["order"].asInt)
            assertEquals("diagnostic_only", metadata["scientificUse"].asString)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
