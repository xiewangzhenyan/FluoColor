package com.muc.fluocolorquant.ui.screens.result.array

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCalibrationPointResult
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.export.ArrayResultPdfExporter
import com.muc.fluocolorquant.domain.result.export.ArrayResultPdfLabels
import com.muc.fluocolorquant.domain.result.export.ArrayResultPngExporter
import com.muc.fluocolorquant.domain.result.export.ArrayResultPngLabels
import com.muc.fluocolorquant.domain.result.export.ArrayResultValidationPdfLabels
import com.muc.fluocolorquant.domain.result.validation.ResultValidationEngine
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PDF 文件结构和导出格式面板的真实 Android 测试。 */
@RunWith(AndroidJUnit4::class)
class ArrayResultExportTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `PDF至少一页且文件头和分析物分页有效`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bytes = ArrayResultPdfExporter.createPdf(context, snapshot(), pdfLabels())
        assertTrue(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "%PDF")

        val file = File(context.cacheDir, "array-result-export-test.pdf")
        val keepVisualArtifact = InstrumentationRegistry.getArguments()
            .getString("keepPdf") == "true"
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    // 一页统一 XML 封面 + 一页总览 + 一页分析物 + 一页质量摘要/追溯。
                    assertEquals(4, renderer.pageCount)
                    renderer.openPage(0).use { coverPage ->
                        val bitmap = Bitmap.createBitmap(
                            coverPage.width,
                            coverPage.height,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            coverPage.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            // 左上角必须来自 pdf_cover_page.xml 的 #006E1C 页眉，防止以后再次退化成
                            // 没有统一模板的纯 Canvas 首页。
                            val headerColor = bitmap.getPixel(20, 20)
                            assertTrue(Color.red(headerColor) <= 5)
                            assertTrue(kotlin.math.abs(Color.green(headerColor) - 110) <= 5)
                            assertTrue(kotlin.math.abs(Color.blue(headerColor) - 28) <= 5)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            // 本地视觉审计可通过 keepPdf=true 保留产物并用 adb run-as 拉取；普通 CI
            // 仍自动删除临时文件，不在测试设备中长期堆积报告。
            if (keepVisualArtifact) {
                // connectedAndroidTest 结束后会卸载测试包，应用私有目录随之删除；通过
                // MediaStore 写入公共 Download，便于 pdftoppm 做逐页视觉审计。
                runCatching {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf("array-result-export-test.pdf")
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "array-result-export-test.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val outputUri = requireNotNull(
                    context.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values
                    )
                )
                context.contentResolver.openOutputStream(outputUri).use { output ->
                    requireNotNull(output).write(bytes)
                }
            } else {
                file.delete()
            }
        }
    }

    @Test
    fun `PNG以固定分辨率导出圆孔与方格热力图`() {
        val labels = ArrayResultPngLabels(
            concentrationTitleFormat = "%1\$s concentration heatmap",
            signalTitleFormat = "%1\$s signal heatmap",
            concentration = "Concentration",
            signal = "Signal",
            noValue = "N/A"
        )
        // 同时覆盖旧4×4、自定义6×8、标准8×12圆孔板和15×15微流控，防止导出器
        // 再次把行列或位点形状写死为某一种载体规格。
        val cases = listOf(
            Triple(4 to 4, "CIRCLE", "legacy-4x4"),
            Triple(6 to 8, "CIRCLE", "custom-6x8"),
            Triple(8 to 12, "CIRCLE", "plate-8x12"),
            Triple(15 to 15, "SQUARE", "grid-15x15")
        )
        cases.forEach { (layout, shape, caseName) ->
            val source = snapshot(
                rows = layout.first,
                columns = layout.second,
                siteShape = shape
            )
            val bytes = ArrayResultPngExporter.createHeatmapPng(
                snapshot = source,
                analyteId = "analyte",
                labels = labels
            )

            assertTrue("$caseName 应输出合法PNG", bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals("$caseName 宽度", 1600, bitmap.width)
                assertEquals("$caseName 高度", 1100, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `PDF存在验证修订时增加回归与BlandAltman图表页`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val points = listOf(
            ResultValidationPoint(0, "A1", 12.5, 12.0),
            ResultValidationPoint(1, "A2", 25.0, 24.0)
        )
        val calculated = ResultValidationEngine.calculate(points)
        val validation = ResultValidationSnapshot(
            validationId = "validation",
            runId = "run-export",
            analyteId = "analyte",
            revision = 1,
            concentrationUnit = "ng/mL",
            points = points,
            regression = calculated.regression,
            blandAltman = calculated.blandAltman,
            processorVersion = ResultValidationEngine.PROCESSOR_VERSION,
            inputFingerprint = "fingerprint",
            createdAtEpochMillis = 1_000L
        )
        val bytes = ArrayResultPdfExporter.createPdf(
            context = context,
            snapshot = snapshot(),
            labels = pdfLabels(),
            validations = mapOf("analyte" to validation),
            validationLabels = validationPdfLabels()
        )
        val file = File(context.cacheDir, "array-result-validation-export-test.pdf")
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(5, renderer.pageCount)
                }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `PDF与独立PNG对同一冻结浓度使用完全相同的色带`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = snapshot()
        val expectedColor = HeatmapColorUtil.getColor(0.0, 0.0, 100.0).toArgb()
        val pngBytes = ArrayResultPngExporter.createHeatmapPng(
            snapshot = source,
            analyteId = "analyte",
            labels = ArrayResultPngLabels(
                concentrationTitleFormat = "%1\$s concentration heatmap",
                signalTitleFormat = "%1\$s signal heatmap",
                concentration = "Concentration",
                signal = "Signal",
                noValue = "N/A"
            )
        )
        val pngBitmap = requireNotNull(BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size))
        val pdfFile = File(context.cacheDir, "array-result-shared-heatmap.pdf")
        try {
            assertTrue(
                "独立PNG应包含冻结浓度对应的标准色带颜色",
                bitmapContainsColor(pngBitmap, expectedColor)
            )
            pdfFile.writeBytes(ArrayResultPdfExporter.createPdf(context, source, pdfLabels()))
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(2).use { analytePage ->
                        val rendered = Bitmap.createBitmap(
                            analytePage.width,
                            analytePage.height,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            analytePage.render(
                                rendered,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            assertTrue(
                                "PDF分析物页必须与独立PNG包含同一个冻结浓度颜色",
                                bitmapContainsColor(rendered, expectedColor)
                            )
                        } finally {
                            rendered.recycle()
                        }
                    }
                }
            }
        } finally {
            pngBitmap.recycle()
            pdfFile.delete()
        }
    }

    @Test
    fun `圆孔板PDF恢复逐孔裁切图与对应结果附录`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cropColor = Color.rgb(26, 170, 112)
        val crop = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            Canvas(this).drawCircle(
                width / 2f,
                height / 2f,
                width * 0.34f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cropColor }
            )
        }
        val requestedIndices = mutableListOf<Int>()
        val baseSnapshot = snapshot(rows = 8, columns = 12)
        val source = baseSnapshot.copy(
            analytes = listOf(
                baseSnapshot.analytes.single().copy(
                    fittingFunction = "linear",
                    fittingParameters = mapOf("a" to 2.0, "b" to 1.0),
                    calibrationPoints = listOf(
                        ArrayCalibrationPointResult(0.0, 1.0, 0),
                        ArrayCalibrationPointResult(25.0, 51.0, 0),
                        ArrayCalibrationPointResult(50.0, 101.0, 0),
                        ArrayCalibrationPointResult(100.0, 201.0, 0)
                    ),
                    calibrationRangeMin = 0.0,
                    calibrationRangeMax = 100.0,
                    validationMetrics = mapOf("R2" to 0.9987)
                )
            )
        )
        val bytes = try {
            ArrayResultPdfExporter.createPdf(
                context = context,
                snapshot = source,
                labels = pdfLabels(),
                wellImageProvider = { siteIndex ->
                    requestedIndices += siteIndex
                    crop
                }
            )
        } finally {
            // PdfDocument.writeTo返回后已经完成图片编码；测试用Bitmap不参与生产缓存，可立即回收。
        }
        val file = File(context.cacheDir, "array-result-well-details.pdf")
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    // 旧版标准曲线页恢复后为5页主体；96孔再按每页10行生成10页逐孔附录。
                    assertEquals(15, renderer.pageCount)
                    renderer.openPage(renderer.pageCount - 1).use { detailPage ->
                        val rendered = Bitmap.createBitmap(
                            detailPage.width,
                            detailPage.height,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            detailPage.render(
                                rendered,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            assertTrue(
                                "逐孔附录必须真正绘制裁切图，而不是只显示图片路径",
                                bitmapContainsColor(rendered, cropColor, channelTolerance = 4)
                            )
                            val firstCropBounds = requireNotNull(
                                bitmapColorBounds(
                                    bitmap = rendered,
                                    expected = cropColor,
                                    searchBounds = Rect(78, 132, 128, 192),
                                    channelTolerance = 4
                                )
                            ) { "逐孔附录第一页必须能定位到真实圆孔像素" }
                            assertTrue(
                                "PDF不得把圆孔裁切图拉成长椭圆，实际像素边界=${firstCropBounds.width()}×${firstCropBounds.height()}",
                                kotlin.math.abs(firstCropBounds.width() - firstCropBounds.height()) <= 2
                            )
                        } finally {
                            rendered.recycle()
                        }
                    }
                }
            }
            assertEquals((0 until 96).toList(), requestedIndices)
            if (InstrumentationRegistry.getArguments().getString("keepPdf") == "true") {
                val displayName = "array-result-well-details.pdf"
                runCatching {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(displayName)
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val outputUri = requireNotNull(
                    context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                )
                context.contentResolver.openOutputStream(outputUri).use { output ->
                    requireNotNull(output).write(bytes)
                }
            }
        } finally {
            crop.recycle()
            file.delete()
        }
    }

    @Test
    fun `方阵PDF逐孔裁切图保持原始宽高比`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cropColor = Color.rgb(184, 72, 125)
        val crop = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(cropColor)
        }
        val bytes = ArrayResultPdfExporter.createPdf(
            context = context,
            snapshot = snapshot(rows = 1, columns = 1, siteShape = "SQUARE"),
            labels = pdfLabels(),
            wellImageProvider = { crop }
        )
        val file = File(context.cacheDir, "array-result-rectangular-crop.pdf")
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(renderer.pageCount - 1).use { detailPage ->
                        val rendered = Bitmap.createBitmap(
                            detailPage.width,
                            detailPage.height,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            detailPage.render(
                                rendered,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            val renderedBounds = requireNotNull(
                                bitmapColorBounds(
                                    bitmap = rendered,
                                    expected = cropColor,
                                    searchBounds = Rect(78, 132, 128, 192),
                                    channelTolerance = 4
                                )
                            ) { "方形载体附录必须能定位到真实裁切图像素" }
                            val renderedRatio = renderedBounds.width().toFloat() /
                                renderedBounds.height().coerceAtLeast(1)
                            assertTrue(
                                "PDF必须保留非方形裁切图的2:1原始比例，实际比例=$renderedRatio",
                                kotlin.math.abs(renderedRatio - 2f) <= 0.15f
                            )
                        } finally {
                            rendered.recycle()
                        }
                    }
                }
            }
        } finally {
            crop.recycle()
            file.delete()
        }
    }

    @Test
    fun `导出面板同时提供CSVPNG与PDF和ZIP且回调独立`() {
        var selectedFormat: String? = null
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultExportSheet(
                    onDismiss = {},
                    onCsvExport = { selectedFormat = "csv" },
                    onPngExport = { selectedFormat = "png" },
                    onPdfExport = { selectedFormat = "pdf" },
                    onZipExport = { selectedFormat = "zip" }
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_CSV_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_PNG_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_PDF_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_ZIP_TAG).performClick()
        composeRule.runOnIdle { assertEquals("zip", selectedFormat) }
    }

    private fun snapshot(
        rows: Int = 1,
        columns: Int = 1,
        siteShape: String = "CIRCLE"
    ): ArrayResultSnapshot {
        val analyte = ArrayAnalyteResult(
            analyteId = "analyte",
            name = "CEA",
            displayOrder = 0,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 100.0,
            modelId = "model",
            modelName = "CEA curve",
            modelType = "STANDARD_CURVE",
            modelVersion = 2,
            primaryFeature = "DELTA_E_2000",
            processorName = "colorimetric-photometry",
            processorVersion = "v1",
            projectRangeMin = 0.0,
            projectRangeMax = 100.0,
            calibrationRangeMin = 28.0,
            calibrationRangeMax = 34.0
        )
        val measurement = ArraySiteMeasurementResult(
            measurementId = 1L,
            analyteId = analyte.analyteId,
            detectionMode = "COLORIMETRIC",
            primaryFeatureName = analyte.primaryFeature,
            primaryFeatureValue = 12.5,
            concentrationValue = 12.5,
            concentrationUnit = analyte.concentrationUnit,
            reliableRangeStatus = "WITHIN_RANGE",
            backgroundValue = 1.0,
            signalToNoiseRatio = 9.0,
            confidence = 0.98,
            signalDetectable = true,
            qualityReliable = true,
            processorName = analyte.processorName,
            processorVersion = analyte.processorVersion,
            modelSnapshotJson = "{}",
            rawSignalJson = "{}",
            correctedSignalJson = "{}",
            qcJson = "{}",
            quantificationQcJson = "{}",
            qc = ArrayMeasurementQc(
                geometrySourceCode = "CANDIDATE_REFINED",
                geometryFlags = emptySet(),
                photometryFlags = emptySet(),
                quantificationStatus = "QUANTIFIED",
                quantificationScope = null,
                quantificationReason = null
            ),
            detail = ArrayMeasurementDetail.LegacyUnparsed("{}", "{}")
        )
        val siteCount = rows * columns
        return ArrayResultSnapshot(
            runId = "run-pdf",
            projectId = "project-pdf",
            projectName = "PDF chip",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier",
                name = "${rows}×${columns} array",
                carrierType = if (siteShape == "CIRCLE") "WELL_PLATE" else "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = siteShape,
                orientationMarkerJson = null
            ),
            rows = rows,
            columns = columns,
            analytes = listOf(analyte),
            sites = List(siteCount) { index ->
                val row = index / columns
                val column = index % columns
                val point = GridPoint(column * 20.0 + 10.0, row * 20.0 + 10.0)
                ArrayPhysicalSiteResult(
                    siteIndex = index,
                    rowIndex = row,
                    columnIndex = column,
                    siteKey = "R${(row + 1).toString().padStart(2, '0')}C${(column + 1).toString().padStart(2, '0')}",
                    enabled = true,
                    roleCode = "SAMPLE",
                    analyteId = analyte.analyteId,
                    defaultSampleSlot = "S${index + 1}",
                    sampleSlot = "S${index + 1}",
                    overrideReason = null,
                    standardConcentration = null,
                    repeatGroup = null,
                    referenceScope = null,
                    geometry = ArraySiteGeometry(
                        rectified = point,
                        original = point,
                        confidence = 0.98,
                        source = GridPointSource.CANDIDATE_REFINED,
                        flags = emptySet()
                    ),
                    measurements = listOf(
                        measurement.copy(
                            measurementId = index.toLong() + 1L,
                            primaryFeatureValue = index.toDouble() + 1.0,
                            concentrationValue = index.toDouble() / maxOf(siteCount - 1, 1) * 100.0
                        )
                    )
                )
            },
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = columns * 20,
                rectifiedHeight = rows * 20,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = siteCount,
                    outlierCount = 0,
                    meanConfidence = 0.98
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{\"templateVersion\":1}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun bitmapContainsColor(
        bitmap: Bitmap,
        expected: Int,
        channelTolerance: Int = 0
    ): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { actual ->
            kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= channelTolerance &&
                kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= channelTolerance &&
                kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= channelTolerance
        }
    }

    /**
     * 只在指定 PDF 单元格内测量目标颜色的真实像素包围框，避免表头或其他孔位干扰。
     * 这个断言专门约束导出渲染比例；它不把测试布局坐标写入生产代码。
     */
    private fun bitmapColorBounds(
        bitmap: Bitmap,
        expected: Int,
        searchBounds: Rect,
        channelTolerance: Int
    ): Rect? {
        val safeBounds = Rect(
            searchBounds.left.coerceIn(0, bitmap.width),
            searchBounds.top.coerceIn(0, bitmap.height),
            searchBounds.right.coerceIn(0, bitmap.width),
            searchBounds.bottom.coerceIn(0, bitmap.height)
        )
        var minX = safeBounds.right
        var minY = safeBounds.bottom
        var maxX = -1
        var maxY = -1
        for (y in safeBounds.top until safeBounds.bottom) {
            for (x in safeBounds.left until safeBounds.right) {
                val actual = bitmap.getPixel(x, y)
                val matches = kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= channelTolerance &&
                    kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= channelTolerance &&
                    kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= channelTolerance
                if (matches) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        return if (maxX >= minX && maxY >= minY) {
            Rect(minX, minY, maxX + 1, maxY + 1)
        } else {
            null
        }
    }

    private companion object {
        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }

    private fun pdfLabels(): ArrayResultPdfLabels {
        return ArrayResultPdfLabels(
            documentTitle = "Array result",
            frozenEvidenceNote = "Frozen evidence only",
            project = "Project",
            runId = "Run",
            runTime = "Time",
            status = "Status",
            detectionMode = "Mode",
            carrier = "Carrier",
            layout = "Layout",
            physicalSites = "Sites",
            measurements = "Measurements",
            validMeasurements = "Valid",
            reviewMeasurements = "Review",
            unavailableMeasurements = "Unavailable",
            overviewHeatmap = "Overview",
            analyteSection = "Analyte",
            model = "Model",
            primaryFeature = "Feature",
            projectRange = "Project range",
            calibrationRange = "Calibration range",
            concentrationHeatmap = "Concentration",
            signalHeatmap = "Signal",
            qualitySummary = "Quality",
            frameReviewCount = "Frame review",
            reviewMeasurementCount = "Review",
            unavailableMeasurementCount = "Unavailable",
            lowSignalCount = "Low signal",
            traceability = "Traceability",
            locator = "Locator",
            processor = "Processor",
            frozenSnapshot = "Snapshot",
            noValue = "N/A",
            pageFormat = "Page %1\$d / %2\$d"
        )
    }

    private fun validationPdfLabels() = ArrayResultValidationPdfLabels(
        title = "Prediction validation",
        summaryFormat = "%1\$s · revision %2\$d · %3\$d points",
        regressionTitle = "Prediction vs reference",
        blandAltmanTitle = "Bland-Altman",
        referenceAxis = "Reference",
        predictedAxis = "Prediction",
        meanAxis = "Mean",
        differenceAxis = "Prediction - reference",
        rSquared = "R2",
        slope = "Slope",
        rmse = "RMSE",
        mae = "MAE",
        meanBias = "Bias",
        lowerLimit = "Lower",
        upperLimit = "Upper",
        withinLimits = "Within"
    )
}
