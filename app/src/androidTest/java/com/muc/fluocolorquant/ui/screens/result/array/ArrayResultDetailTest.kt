package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridFrameQcCode
import com.muc.fluocolorquant.domain.detection.grid.GridFrameQcIssue
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridQcSeverity
import com.muc.fluocolorquant.domain.detection.grid.GridSiteFlag
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.LabPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.SitePhotometryQc
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayColorimetricCalibrationContext
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 位点详情、原图定位图例和 QC 语义分流的 Compose 测试。 */
@RunWith(AndroidJUnit4::class)
class ArrayResultDetailTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** 从当前测试应用资源读取本地化文案，避免在 Compose 测试中硬编码中英文。 */
    private fun string(id: Int): String {
        return ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    }

    @Test
    fun `点击比色位点只显示比色专用科学字段`() {
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(colorimetricSnapshot()),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RESULT_ANALYTE_TAB_TAG).performClick()
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CELL_TAG_PREFIX}0", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag(ARRAY_SITE_DETAIL_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_SITE_DETAIL_COLORIMETRIC_TAG).assertExists()
        composeRule.onAllNodesWithTag(ARRAY_SITE_DETAIL_FLUORESCENCE_TAG).assertCountEquals(0)
    }

    @Test
    fun `点击荧光位点只显示荧光专用科学字段`() {
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(fluorescenceSnapshot()),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_RESULT_ANALYTE_TAB_TAG).performClick()
        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CELL_TAG_PREFIX}0", useUnmergedTree = true)
            .performClick()
        composeRule.onNodeWithTag(ARRAY_SITE_DETAIL_SHEET_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_SITE_DETAIL_FLUORESCENCE_TAG).assertExists()
        composeRule.onAllNodesWithTag(ARRAY_SITE_DETAIL_COLORIMETRIC_TAG).assertCountEquals(0)
    }

    @Test
    fun `QC面板将模型补位和低信号显示为不同问题`() {
        val snapshot = qcSnapshot()
        composeRule.setContent {
            FluoColorTheme {
                ArrayQcPanel(snapshot = snapshot, onSiteClick = {})
            }
        }

        composeRule.onNodeWithTag(ARRAY_QC_PANEL_TAG).performScrollToIndex(2)
        composeRule.onNodeWithTag(
            "${ARRAY_QC_IMPUTED_TAG_PREFIX}0",
            useUnmergedTree = true
        ).assertExists()
        val imputedAdviceTag =
            "${ARRAY_QC_ADVICE_TAG_PREFIX}0_${ArraySiteQcCode.MODEL_IMPUTED.name.lowercase()}"
        composeRule.onAllNodesWithTag(imputedAdviceTag, useUnmergedTree = true)
            .assertCountEquals(0)
        composeRule.onNodeWithTag(
            "${ARRAY_QC_IMPUTED_TAG_PREFIX}0",
            useUnmergedTree = true
        ).performClick()
        composeRule.onNodeWithTag(imputedAdviceTag, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_QC_PANEL_TAG).performScrollToIndex(3)
        composeRule.onNodeWithTag(
            "${ARRAY_QC_LOW_SIGNAL_TAG_PREFIX}1",
            useUnmergedTree = true
        ).assertExists()
    }

    @Test
    fun `原图叠加图例明确区分定位来源和质量失败`() {
        val snapshot = qcSnapshot()
        composeRule.setContent {
            FluoColorTheme {
                ArrayImageOverlay(
                    artifact = artifact(),
                    sites = snapshot.sites,
                    onSiteClick = {},
                    imageOverride = ArrayOverlayImage(
                        bitmap = ImageBitmap(width = 200, height = 100),
                        originalWidth = 200,
                        originalHeight = 100
                    )
                )
            }
        }

        composeRule.onNodeWithTag(ARRAY_IMAGE_OVERLAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_IMAGE_LEGEND_CANDIDATE_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_IMAGE_LEGEND_IMPUTED_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_IMAGE_LEGEND_UNADJUSTED_TAG).assertExists()
        composeRule.onNodeWithTag(ARRAY_IMAGE_LEGEND_FAILURE_TAG).assertExists()
    }

    @Test
    fun `严重帧级风险保留后台证据但普通质控页不再显示整帧警告`() {
        val source = colorimetricSnapshot()
        val failed = source.copy(
            frame = source.frame.copy(
                qcIssues = listOf(
                    GridFrameQcIssue(
                        code = GridFrameQcCode.OVER_EXPOSED,
                        severity = GridQcSeverity.FAILURE,
                        measuredValue = 0.42,
                        threshold = 0.10
                    )
                )
            )
        )
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(failed),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("${ARRAY_HEATMAP_CARD_TAG_PREFIX}color-analyte")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("array_result_tab_qc").performClick()
        composeRule.onAllNodesWithText(string(R.string.array_qc_frame_failure_title))
            .assertCountEquals(0)
    }

    @Test
    fun `处理过程标签只展示冻结的派生证据`() {
        val source = colorimetricSnapshot()
        val withEvidence = source.copy(
            artifacts = source.artifacts + processingArtifact()
        )
        composeRule.setContent {
            FluoColorTheme {
                ArrayResultContent(
                    state = ArrayResultUiState.Success(withEvidence),
                    onBack = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("array_result_tab_process").performClick()
        composeRule.onNodeWithTag(ARRAY_PROCESSING_TAB_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ARRAY_PROCESSING_IMAGE_TAG).assertExists()
    }

    private fun colorimetricSnapshot(): ArrayResultSnapshot {
        val analyte = analyte(
            id = "color-analyte",
            mode = "COLORIMETRIC",
            feature = AnalysisPrimaryFeature.DELTA_E_2000
        )
        val base = basePhotometry(signalDetectable = true, qualityReliable = true)
        val detail = ArrayMeasurementDetail.Colorimetric(
            site = ColorimetricSitePhotometry(
                base = base,
                whiteBalancedRgb = RgbPhotometry(112.0, 96.0, 85.0),
                lab = LabPhotometry(45.0, 12.0, 18.0),
                deltaE2000 = 6.2,
                opticalDensity = 0.34,
                primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000,
                primaryFeatureValue = 6.2,
                qc = base.qc
            ),
            calibrationContext = ArrayColorimetricCalibrationContext(
                referenceIndices = listOf(0),
                whiteBalanceGains = RgbPhotometry(1.02, 1.0, 0.98),
                referenceRgb = RgbPhotometry(120.0, 120.0, 120.0),
                referenceLab = LabPhotometry(50.0, 0.0, 0.0)
            ),
            schemaVersion = "colorimetric-corrected-signal-v1"
        )
        return snapshot(
            mode = "COLORIMETRIC",
            analyte = analyte,
            sites = listOf(
                site(
                    siteIndex = 0,
                    analyte = analyte,
                    measurement = measurement(
                        analyte = analyte,
                        detail = detail,
                        concentration = 5.5
                    )
                )
            )
        )
    }

    private fun fluorescenceSnapshot(): ArrayResultSnapshot {
        val analyte = analyte(
            id = "fluorescence-analyte",
            mode = "FLUORESCENCE",
            feature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
        )
        val base = basePhotometry(signalDetectable = true, qualityReliable = true)
        val detail = ArrayMeasurementDetail.Fluorescence(
            site = FluorescenceSitePhotometry(
                base = base,
                channel = FluorescenceChannel.GREEN,
                netIntensity = 42.0,
                integratedIntensity = 4200.0,
                signalToNoiseRatio = 8.4,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY,
                primaryFeatureValue = 42.0,
                qc = base.qc
            )
        )
        return snapshot(
            mode = "FLUORESCENCE",
            analyte = analyte,
            sites = listOf(
                site(
                    siteIndex = 0,
                    analyte = analyte,
                    measurement = measurement(
                        analyte = analyte,
                        detail = detail,
                        concentration = 3.2
                    )
                )
            )
        )
    }

    private fun qcSnapshot(): ArrayResultSnapshot {
        val analyte = analyte(
            id = "qc-analyte",
            mode = "FLUORESCENCE",
            feature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
        )
        val imputedMeasurement = measurement(
            analyte = analyte,
            detail = ArrayMeasurementDetail.LegacyUnparsed("{}", null),
            concentration = 2.0
        )
        val lowSignalMeasurement = measurement(
            analyte = analyte,
            detail = ArrayMeasurementDetail.LegacyUnparsed("{}", null),
            concentration = null,
            signalDetectable = false,
            photometryFlags = setOf("LOW_SNR")
        )
        return snapshot(
            mode = "FLUORESCENCE",
            analyte = analyte,
            sites = listOf(
                site(
                    siteIndex = 0,
                    analyte = analyte,
                    measurement = imputedMeasurement,
                    source = GridPointSource.MODEL_IMPUTED,
                    geometryFlags = setOf(GridSiteFlag.IMPUTED_POSITION)
                ),
                site(
                    siteIndex = 1,
                    analyte = analyte,
                    measurement = lowSignalMeasurement,
                    source = GridPointSource.CANDIDATE_REFINED
                )
            ),
            columns = 2
        )
    }

    private fun snapshot(
        mode: String,
        analyte: ArrayAnalyteResult,
        sites: List<ArrayPhysicalSiteResult>,
        columns: Int = 1
    ): ArrayResultSnapshot {
        return ArrayResultSnapshot(
            runId = "run-$mode",
            projectId = "project-$mode",
            projectName = "$mode project",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = mode,
            carrier = ArrayCarrierResult(
                id = "carrier",
                name = "Test chip",
                carrierType = "MICROFLUIDIC_CHIP",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 1,
            columns = columns,
            analytes = listOf(analyte),
            sites = sites,
            frame = ArrayFrameResult(
                locatorName = "pg-grid",
                locatorVersion = "2.1",
                rectifiedWidth = 200,
                rectifiedHeight = 100,
                chipRegionMethod = "test",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 0.8,
                    trusted = true,
                    observedRatio = 0.8,
                    geometryRmsePx = 0.5,
                    inlierCount = sites.size,
                    outlierCount = 0,
                    meanConfidence = 0.8
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = listOf(artifact()),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = null,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun analyte(
        id: String,
        mode: String,
        feature: AnalysisPrimaryFeature
    ): ArrayAnalyteResult {
        return ArrayAnalyteResult(
            analyteId = id,
            name = if (mode == "COLORIMETRIC") "AFP" else "CEA",
            displayOrder = 0,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.0,
            reliableRangeMax = 10.0,
            modelId = "model-$id",
            modelName = "Model $id",
            modelType = "STANDARD_CURVE",
            modelVersion = 1,
            primaryFeature = feature.code,
            processorName = if (mode == "COLORIMETRIC") {
                "colorimetric-photometry"
            } else {
                "fluorescence-photometry"
            },
            processorVersion = "v1"
        )
    }

    private fun site(
        siteIndex: Int,
        analyte: ArrayAnalyteResult,
        measurement: ArraySiteMeasurementResult,
        source: GridPointSource = GridPointSource.CANDIDATE_REFINED,
        geometryFlags: Set<GridSiteFlag> = emptySet()
    ): ArrayPhysicalSiteResult {
        val point = GridPoint(siteIndex * 80.0 + 40.0, 50.0)
        return ArrayPhysicalSiteResult(
            siteIndex = siteIndex,
            rowIndex = 0,
            columnIndex = siteIndex,
            siteKey = "R01C${(siteIndex + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = "SAMPLE",
            analyteId = analyte.analyteId,
            defaultSampleSlot = "S${siteIndex + 1}",
            sampleSlot = "S${siteIndex + 1}",
            overrideReason = null,
            standardConcentration = null,
            repeatGroup = null,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = point,
                original = point,
                confidence = if (source == GridPointSource.MODEL_IMPUTED) 0.3 else 0.9,
                source = source,
                flags = geometryFlags
            ),
            measurements = listOf(measurement)
        )
    }

    private fun measurement(
        analyte: ArrayAnalyteResult,
        detail: ArrayMeasurementDetail,
        concentration: Double?,
        signalDetectable: Boolean = true,
        qualityReliable: Boolean = true,
        photometryFlags: Set<String> = emptySet()
    ): ArraySiteMeasurementResult {
        return ArraySiteMeasurementResult(
            measurementId = 1L,
            analyteId = analyte.analyteId,
            detectionMode = if (analyte.processorName.startsWith("colorimetric")) {
                "COLORIMETRIC"
            } else {
                "FLUORESCENCE"
            },
            primaryFeatureName = analyte.primaryFeature,
            primaryFeatureValue = 6.2,
            concentrationValue = concentration,
            concentrationUnit = analyte.concentrationUnit,
            reliableRangeStatus = if (concentration == null) null else "WITHIN_RANGE",
            backgroundValue = 10.0,
            signalToNoiseRatio = if (signalDetectable) 8.0 else 1.2,
            confidence = 0.9,
            signalDetectable = signalDetectable,
            qualityReliable = qualityReliable,
            processorName = analyte.processorName,
            processorVersion = analyte.processorVersion,
            modelSnapshotJson = "{}",
            rawSignalJson = "{}",
            correctedSignalJson = "{}",
            qcJson = "{}",
            quantificationQcJson = "{}",
            qc = ArrayMeasurementQc(
                geometrySourceCode = null,
                geometryFlags = emptySet(),
                photometryFlags = photometryFlags,
                quantificationStatus = if (concentration == null) "SIGNAL_ONLY" else "QUANTIFIED",
                quantificationScope = null,
                quantificationReason = null
            ),
            detail = detail
        )
    }

    private fun basePhotometry(
        signalDetectable: Boolean,
        qualityReliable: Boolean
    ): BaseSitePhotometry {
        val qc = SitePhotometryQc(emptySet(), signalDetectable, qualityReliable)
        val point = GridPoint(40.0, 50.0)
        return BaseSitePhotometry(
            siteIndex = 0,
            rowIndex = 0,
            columnIndex = 0,
            rectifiedCenter = point,
            originalCenter = point,
            roiMedianRgb = RgbPhotometry(100.0, 120.0, 90.0),
            roiMedianGray = 105.0,
            backgroundMedianRgb = RgbPhotometry(10.0, 12.0, 9.0),
            backgroundMedianGray = 10.0,
            backgroundSigmaRgb = RgbPhotometry(2.0, 2.0, 2.0),
            backgroundSigmaGray = 2.0,
            correctedMedianRgb = RgbPhotometry(95.0, 108.0, 84.0),
            correctedMedianGray = 96.0,
            signalGray = 95.0,
            signalRatio = 9.5,
            correctedSignalGray = 86.0,
            integratedSignalRgb = RgbPhotometry(9500.0, 10800.0, 8400.0),
            integratedSignalGray = 9_600.0,
            signalToNoiseRatio = if (signalDetectable) 8.0 else 1.2,
            saturationRatio = 0.01,
            roiContaminationRatio = 0.02,
            hotPixelRatio = 0.001,
            roiClipRatio = 0.0,
            annulusClipRatio = 0.0,
            qc = qc
        )
    }

    private fun artifact(): ArrayCaptureEvidence {
        return ArrayCaptureEvidence(
            artifactId = "artifact-1",
            captureRole = "ENDPOINT",
            originalPath = "C:/test/endpoint.png",
            derivedPath = null,
            capturedAtEpochMillis = 1_000L,
            operatorId = "user",
            actualMetadataJson = null,
            profileSnapshotJson = null,
            imageQcJson = null,
            checksumSha256 = null,
            locked = true,
            revision = 1
        )
    }

    private fun processingArtifact(): ArrayCaptureEvidence {
        return ArrayCaptureEvidence(
            artifactId = "artifact-process-grid",
            captureRole = "PROCESS_GRID_OVERLAY",
            originalPath = "C:/test/process-grid.png",
            derivedPath = null,
            capturedAtEpochMillis = 1_000L,
            operatorId = "user",
            actualMetadataJson = "{\"schemaVersion\":\"pg-processing-evidence-v1\",\"order\":4}",
            profileSnapshotJson = null,
            imageQcJson = null,
            checksumSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            locked = true,
            revision = 4
        )
    }
}
