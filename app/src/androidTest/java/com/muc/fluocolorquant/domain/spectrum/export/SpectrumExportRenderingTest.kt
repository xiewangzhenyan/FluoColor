package com.muc.fluocolorquant.domain.spectrum.export

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.SpectrumChannelExportModel
import com.muc.fluocolorquant.data.model.SpectrumExportData
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.utils.LocaleHelper
import java.io.ByteArrayOutputStream
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 光谱导出渲染的设备冒烟测试。
 *
 * 这组用例本身就是下沉的收益证明：图表渲染与 PDF 组页现在可以**脱离 ViewModel、Hilt 和
 * 整个依赖图**直接调用。此前它们埋在 `ExportViewModel` 的 3000 行里，想验证一次导出就得
 * 先构造出完整的 ViewModel。
 */
@RunWith(AndroidJUnit4::class)
class SpectrumExportRenderingTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val labels = SpectrumChartLabels(
        wavelengthAxis = "Wavelength (nm)",
        intensityAxis = "Normalized intensity"
    )

    private fun channel(
        index: Int,
        analyte: String,
        points: List<Pair<Double, Double>>
    ) = SpectrumChannelExportModel(
        channelIndex = index,
        analyteName = analyte,
        analyteId = "analyte-$index",
        peakWavelength = points.maxByOrNull { it.second }?.first?.toFloat(),
        peakIntensity = points.maxOfOrNull { it.second },
        dataPointCount = points.size,
        minWavelength = points.minOfOrNull { it.first } ?: 0.0,
        maxWavelength = points.maxOfOrNull { it.first } ?: 0.0,
        wavelengths = points.map { it.first },
        intensities = points.map { it.second },
        chartData = ChartData(curvePoints = points)
    )

    private fun sampleChannels(count: Int) = (1..count).map { index ->
        channel(
            index = index,
            analyte = "Analyte$index",
            points = (0..40).map { step ->
                val wavelength = 400.0 + step * 5.0
                wavelength to kotlin.math.exp(-((step - 20 - index) * 0.2) * ((step - 20 - index) * 0.2))
            }
        )
    }

    private fun project() = Project(
        id = "spectrum-export-test",
        name = "SpectrumExportTest",
        detectionMode = "SPECTRUM",
        recognitionType = "AUTO",
        imageUri = "",
        rows = 1,
        columns = 1,
        spectrumColumnCount = 3,
        createTime = Date(0L),
        userId = "tester",
        lastRunTimestamp = null,
        analysisMethod = "SIGNAL_ONLY"
    )

    @Test
    fun `单通道曲线图按声明尺寸渲染且非空白`() {
        val bitmap = SpectrumChartRenderer.renderChannelCurve(
            channel = sampleChannels(1).first(),
            title = "Channel 1",
            labels = labels
        )
        assertNotNull(bitmap)
        requireNotNull(bitmap)
        try {
            assertEquals(1200, bitmap.width)
            assertEquals(900, bitmap.height)
            assertTrue("图表不应是纯白画布，说明曲线没有画上去", hasNonWhitePixels(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `无曲线数据的通道返回空而不是空白图`() {
        // 返回 null 让调用方明确跳过该通道；给一张空白图会让报告里出现看不出问题的空图表。
        val empty = SpectrumChartRenderer.renderChannelCurve(
            channel = channel(1, "Empty", emptyList()),
            title = "Empty",
            labels = labels
        )
        assertNull(empty)
        assertNull(SpectrumChartRenderer.renderMergedCurves(emptyList(), "None", labels))
    }

    @Test
    fun `强度全等的平坦光谱不会因除零而画不出来`() {
        // 修复前 `yMax == yMin` 会让归一化除零得到 NaN 坐标，画出空白且不报错。
        val flat = channel(
            index = 1,
            analyte = "Flat",
            points = (0..20).map { step -> 400.0 + step * 5.0 to 0.5 }
        )
        val bitmap = SpectrumChartRenderer.renderChannelCurve(flat, "Flat", labels)
        assertNotNull(bitmap)
        requireNotNull(bitmap)
        try {
            assertTrue("平坦光谱仍应画出可见曲线", hasNonWhitePixels(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `多通道合并图渲染成功`() {
        val bitmap = SpectrumChartRenderer.renderMergedCurves(sampleChannels(3), "Merged", labels)
        assertNotNull(bitmap)
        requireNotNull(bitmap)
        try {
            assertEquals(1400, bitmap.width)
            assertEquals(1000, bitmap.height)
            assertTrue(hasNonWhitePixels(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `PDF页数为封面加总览加逐通道且能写出有效文件`() {
        val channels = sampleChannels(3)
        val document = SpectrumPdfExporter.createDocument(
            context = context,
            data = SpectrumExportData(project = project(), channels = channels)
        )
        try {
            assertEquals("封面 + 总览 + 每通道一页", channels.size + 2, document.pages.size)
            document.pages.forEachIndexed { index, page ->
                assertEquals("页码应从 1 连续递增", index + 1, page.pageNumber)
                assertEquals(595, page.pageWidth)
                assertEquals(842, page.pageHeight)
            }

            val bytes = ByteArrayOutputStream().use { stream ->
                document.writeTo(stream)
                stream.toByteArray()
            }
            assertTrue("导出的 PDF 不应为空", bytes.size > 1024)
            // PDF 文件必须以 %PDF- magic 开头，否则第三方阅读器无法识别。
            assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
        } finally {
            document.close()
        }
    }

    @Test
    fun `没有通道时仍输出封面与总览两页`() {
        val document = SpectrumPdfExporter.createDocument(
            context = context,
            data = SpectrumExportData(project = project(), channels = emptyList())
        )
        try {
            assertEquals(2, document.pages.size)
        } finally {
            document.close()
        }
    }

    @Test
    fun `光谱报告标题和已知光源严格跟随指定的中英文Context`() {
        // 设备系统语言不参与断言：报告调用方必须显式提供应用语言 Context，才能保证用户在
        // 英文系统中选择中文后，PDF 仍输出中文标题与光源名称。
        val chineseContext = LocaleHelper.createLocalizedContext(context, "zh")
        assertEquals("光谱分析报告", chineseContext.getString(R.string.spectrum_pdf_cover_title))
        assertEquals(
            "汞灯",
            SpectrumPdfExporter.localizedLightSourceName(chineseContext, "MERCURY")
        )

        val englishContext = LocaleHelper.createLocalizedContext(context, "en")
        assertEquals(
            "Spectrum Analysis Report",
            englishContext.getString(R.string.spectrum_pdf_cover_title)
        )
        assertEquals(
            "Mercury Lamp",
            SpectrumPdfExporter.localizedLightSourceName(englishContext, "MERCURY")
        )
    }

    /** 采样若干像素判断画布是否只有白底，用于识别“渲染成功但什么都没画”。 */
    private fun hasNonWhitePixels(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 60).coerceAtLeast(1)
        val stepY = (bitmap.height / 60).coerceAtLeast(1)
        var x = 0
        while (x < bitmap.width) {
            var y = 0
            while (y < bitmap.height) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) return true
                y += stepY
            }
            x += stepX
        }
        return false
    }
}
