package com.muc.fluocolorquant.ui.screens.result.array

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.os.Environment
import android.provider.MediaStore
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
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
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
    fun `导出面板同时提供CSVPDF和ZIP且回调独立`() {
        var selectedFormat: String? = null
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultExportSheet(
                    onDismiss = {},
                    onCsvExport = { selectedFormat = "csv" },
                    onPdfExport = { selectedFormat = "pdf" },
                    onZipExport = { selectedFormat = "zip" }
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_CSV_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_PDF_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_RESULT_EXPORT_ZIP_TAG).performClick()
        composeRule.runOnIdle { assertEquals("zip", selectedFormat) }
    }

    private fun snapshot(): ArrayResultSnapshot {
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
        val point = GridPoint(10.0, 10.0)
        return ArrayResultSnapshot(
            runId = "run-pdf",
            projectId = "project-pdf",
            projectName = "PDF chip",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "carrier",
                name = "1×1 chip",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 1,
            columns = 1,
            analytes = listOf(analyte),
            sites = listOf(
                ArrayPhysicalSiteResult(
                    siteIndex = 0,
                    rowIndex = 0,
                    columnIndex = 0,
                    siteKey = "R01C01",
                    enabled = true,
                    roleCode = "SAMPLE",
                    analyteId = analyte.analyteId,
                    defaultSampleSlot = "S1",
                    sampleSlot = "S1",
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
                    measurements = listOf(measurement)
                )
            ),
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 20,
                rectifiedHeight = 20,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 1.0,
                    trusted = true,
                    observedRatio = 1.0,
                    geometryRmsePx = 0.1,
                    inlierCount = 1,
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
}
