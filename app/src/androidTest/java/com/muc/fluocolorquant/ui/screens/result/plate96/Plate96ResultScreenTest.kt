package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.R
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
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultLoadResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSource
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
import com.muc.fluocolorquant.domain.result.validation.ResultValidationEngine
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import com.muc.fluocolorquant.domain.result.validation.ResultValidationSnapshot
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.Plate96ResultUiState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 96孔板独立结果页的圆孔视觉、标签和一级信息架构回归。 */
@RunWith(AndroidJUnit4::class)
class Plate96ResultScreenTest {
    @Test
    fun validationInput_showsPhysicalWellThumbnailAndReferenceField() {
        setResultContent()

        composeRule.onNodeWithTag(PLATE96_RESULT_VALIDATION_TAB_TAG).performClick()
        composeRule.onNodeWithText(string(R.string.plate_validation_input_action)).performClick()

        composeRule.onNodeWithTag("${PLATE96_WELL_THUMBNAIL_TAG_PREFIX}0").assertExists()
        composeRule.onNodeWithText("A1").assertExists()
        composeRule.onAllNodesWithText(
            string(R.string.plate_validation_reference_short)
        ).onFirst().assertExists()
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `结果页显示标准圆孔热力图并在页面内展开H12详情`() {
        setResultContent()

        composeRule.onNodeWithTag(PLATE96_RESULT_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PLATE96_HEATMAP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("${PLATE96_WELL_TAG_PREFIX}0").assertExists()
        composeRule.onNodeWithTag("${PLATE96_WELL_TAG_PREFIX}95").assertExists().performClick()
        composeRule.onNodeWithTag(PLATE96_INLINE_WELL_DETAIL_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("H12").assertIsDisplayed()
        composeRule.onNodeWithTag("plate96_well_detail_sheet").assertDoesNotExist()
    }

    @Test
    fun `数值模式分析页和过程页使用独立入口`() {
        setResultContent()

        composeRule.onNodeWithText(string(R.string.plate96_result_view_values)).performClick()
        composeRule.onNodeWithTag("${PLATE96_WELL_TAG_PREFIX}20").assertExists()
        composeRule.onNodeWithTag(PLATE96_RESULT_ANALYSIS_TAB_TAG).performClick()
        composeRule.onNodeWithTag(PLATE96_ANALYSIS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PLATE96_CURVE_CARD_TAG).assertExists()
        composeRule.onNodeWithTag(PLATE96_FIT_METRICS_TAG).assertExists()
        composeRule.onNodeWithText("0.9980").assertExists()
        composeRule.onNodeWithTag(PLATE96_ANALYSIS_TAG).performScrollToIndex(2)
        composeRule.onNodeWithTag(PLATE96_REPEATABILITY_TAG).assertExists()
        composeRule.onNodeWithTag(PLATE96_ANALYSIS_TAG).performScrollToIndex(3)
        composeRule.onNodeWithTag(PLATE96_DISTRIBUTION_TAG).assertExists()
        composeRule.onNodeWithTag(PLATE96_ANALYSIS_TAG).performScrollToIndex(4)
        composeRule.onNodeWithTag(PLATE96_SAMPLE_TABLE_TAG).assertExists()
        composeRule.onNodeWithTag(PLATE96_RESULT_PROCESS_TAB_TAG).performClick()
        composeRule.onNodeWithTag(PLATE96_PROCESSING_TAG).assertIsDisplayed()
    }

    @Test
    fun `旧96孔板历史明确提示缺失过程不会重新推断`() {
        // 旧版WellResult只保存最终孔位数据，没有现代方向矩阵和处理中间图。
        // 此断言用于防止后续重构时误把今天的算法结果补画到历史记录中。
        setResultContent(snapshot().copy(source = Plate96ResultSource.LEGACY_WELL_RESULT))

        composeRule.onNodeWithTag(PLATE96_RESULT_PROCESS_TAB_TAG).performClick()
        composeRule.onNodeWithTag(PLATE96_PROCESSING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PLATE96_LEGACY_HISTORY_NOTICE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.plate96_process_legacy_notice_title)).assertIsDisplayed()
    }

    /** 显式开启后保留页面，供ADB在360dp下截图检查圆孔密度与中英文排版。 */
    @Test
    fun visualPlate96Result_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔板结果页ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 保留孔板分析首屏，检查曲线卡片和统计卡片的窄屏层级。 */
    @Test
    fun visualPlate96Analysis_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_ANALYSIS_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔板分析页ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent()
        composeRule.onNodeWithTag(PLATE96_RESULT_ANALYSIS_TAB_TAG).performClick()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 保留预测验证页，检查指标卡、回归图和Bland–Altman图的窄屏布局。 */
    @Test
    fun visualPlate96Validation_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_VALIDATION_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔板验证页ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent(validations = mapOf("cea" to validation()))
        composeRule.onNodeWithTag(PLATE96_RESULT_VALIDATION_TAB_TAG).performClick()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 保留导出面板，检查CSV、PNG、PDF和ZIP四种格式的图标与信息层级。 */
    @Test
    fun visualPlate96Export_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_EXPORT_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔板导出面板ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent(validations = mapOf("cea" to validation()))
        composeRule.onNodeWithTag(PLATE96_RESULT_EXPORT_TAG).performClick()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 保留处理过程首屏，检查步骤证据的科研仪器式呈现。 */
    @Test
    fun visualPlate96Process_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_PROCESS_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求96孔板过程页ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent()
        composeRule.onNodeWithTag(PLATE96_RESULT_PROCESS_TAB_TAG).performClick()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    /** 保留旧历史过程页，供ADB确认兼容提示清晰且不会补画不存在的现代步骤。 */
    @Test
    fun visualLegacyPlate96Process_holdsForAdbReview() {
        val shouldHold = InstrumentationRegistry.getArguments()
            .getString(ARGUMENT_HOLD_LEGACY_PROCESS_SCREENSHOT)
            ?.toBooleanStrictOrNull()
            ?: false
        assumeTrue("未请求旧96孔板过程页ADB视觉自审，跳过保留页面", shouldHold)
        setResultContent(snapshot().copy(source = Plate96ResultSource.LEGACY_WELL_RESULT))
        composeRule.onNodeWithTag(PLATE96_RESULT_PROCESS_TAB_TAG).performClick()
        composeRule.waitForIdle()
        Thread.sleep(SCREENSHOT_HOLD_MILLIS)
    }

    private fun setResultContent(
        snapshot: Plate96ResultSnapshot = snapshot(),
        validations: Map<String, ResultValidationSnapshot> = emptyMap()
    ) {
        composeRule.setContent {
            FluoColorTheme {
                Plate96ResultContent(
                    state = Plate96ResultUiState.Success(snapshot, validations = validations),
                    onBack = {},
                    onRetry = {}
                )
            }
        }
    }

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun snapshot(): Plate96ResultSnapshot {
        val mapped = Plate96ResultSnapshotMapper.map(arraySnapshot())
        return (mapped as Plate96ResultLoadResult.Success).snapshot
    }

    private fun arraySnapshot(): ArrayResultSnapshot {
        val analyte = analyte()
        return ArrayResultSnapshot(
            runId = "plate96-result-ui",
            projectId = "plate96-project",
            projectName = "CEA孔板实验",
            runTimestampEpochMillis = 1_000L,
            runStatus = "Completed",
            detectionMode = "COLORIMETRIC",
            carrier = ArrayCarrierResult(
                id = "plate96",
                name = "标准96孔板",
                carrierType = "PLATE",
                version = 1,
                siteShape = "CIRCLE",
                orientationMarkerJson = null
            ),
            rows = 8,
            columns = 12,
            analytes = listOf(analyte),
            sites = (0 until 96).map { index -> site(index, analyte) },
            frame = ArrayFrameResult(
                locatorName = "plate96-yolo-circle",
                locatorVersion = "1.0",
                rectifiedWidth = 1200,
                rectifiedHeight = 800,
                chipRegionMethod = "YOLO_HOUGH_GRID",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 0.92,
                    trusted = true,
                    observedRatio = 0.92,
                    geometryRmsePx = 0.4,
                    inlierCount = 88,
                    outlierCount = 8,
                    meanConfidence = 0.91
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = """{"plate96Orientation":{"sourceRows":12,"sourceColumns":8,"quarterTurnsClockwise":1,"originCorner":"TOP_LEFT","userConfirmed":true}}""",
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
    }

    private fun analyte(): ArrayAnalyteResult = ArrayAnalyteResult(
        analyteId = "cea",
        name = "CEA",
        displayOrder = 0,
        concentrationUnit = "ng/mL",
        reliableRangeMin = 0.0,
        reliableRangeMax = 100.0,
        modelId = "curve-cea",
        modelName = "CEA现场曲线",
        modelType = "STANDARD_CURVE",
        modelVersion = 1,
        primaryFeature = "DELTA_E_2000",
        processorName = "colorimetric-photometry",
        processorVersion = "v1",
        fittingFunction = "linear",
        fittingParameters = mapOf("a" to 0.8, "b" to 2.0),
        calibrationPoints = listOf(
            ArrayCalibrationPointResult(0.0, 2.0, 0),
            ArrayCalibrationPointResult(25.0, 22.0, 0),
            ArrayCalibrationPointResult(50.0, 42.0, 0),
            ArrayCalibrationPointResult(75.0, 62.0, 0),
            ArrayCalibrationPointResult(100.0, 82.0, 0)
        ),
        validationMetrics = mapOf(
            "R2" to 0.998,
            "RMSE" to 2.41,
            "MAE" to 1.86
        ),
        projectRangeMin = 0.0,
        projectRangeMax = 100.0,
        calibrationRangeMin = 0.0,
        calibrationRangeMax = 100.0
    )

    private fun validation(): ResultValidationSnapshot {
        val points = listOf(
            ResultValidationPoint(10, "A11", 10.5, 10.0),
            ResultValidationPoint(11, "A12", 21.2, 20.0),
            ResultValidationPoint(12, "B1", 29.6, 30.0),
            ResultValidationPoint(13, "B2", 40.8, 40.0),
            ResultValidationPoint(14, "B3", 49.5, 50.0)
        )
        val calculated = ResultValidationEngine.calculate(points)
        return ResultValidationSnapshot(
            validationId = "validation-cea",
            runId = "plate96-result-ui",
            analyteId = "cea",
            revision = 2,
            concentrationUnit = "ng/mL",
            points = points,
            regression = calculated.regression,
            blandAltman = calculated.blandAltman,
            processorVersion = ResultValidationEngine.PROCESSOR_VERSION,
            inputFingerprint = "visual-fixture",
            createdAtEpochMillis = 2_000L
        )
    }

    private fun site(index: Int, analyte: ArrayAnalyteResult): ArrayPhysicalSiteResult {
        val row = index / 12
        val column = index % 12
        val point = GridPoint(column * 80.0 + 40.0, row * 80.0 + 40.0)
        val role = if (index < 10) "STANDARD" else "SAMPLE"
        val rangeStatus = when (index) {
            5 -> "ABOVE_RANGE"
            6 -> "BELOW_PROJECT_RANGE"
            7 -> "ABOVE_PROJECT_RANGE"
            else -> "WITHIN_RANGE"
        }
        val concentration = when (index) {
            6, 7 -> null
            else -> index / 95.0 * 100.0
        }
        return ArrayPhysicalSiteResult(
            siteIndex = index,
            rowIndex = row,
            columnIndex = column,
            siteKey = "R${(row + 1).toString().padStart(2, '0')}C${(column + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = role,
            analyteId = analyte.analyteId,
            defaultSampleSlot = if (role == "SAMPLE") "S${index - 9}" else null,
            sampleSlot = if (role == "SAMPLE") "S${index - 9}" else null,
            overrideReason = null,
            standardConcentration = if (role == "STANDARD") index * 10.0 else null,
            repeatGroup = if (role == "SAMPLE") "G${index % 4}" else null,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = point,
                original = point,
                confidence = if (index >= 88) 0.45 else 0.94,
                source = if (index >= 88) GridPointSource.MODEL_IMPUTED else GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            ),
            measurements = listOf(measurement(index, analyte, concentration, rangeStatus))
        )
    }

    private fun measurement(
        index: Int,
        analyte: ArrayAnalyteResult,
        concentration: Double?,
        rangeStatus: String
    ): ArraySiteMeasurementResult = ArraySiteMeasurementResult(
        measurementId = index.toLong() + 1,
        analyteId = analyte.analyteId,
        detectionMode = "COLORIMETRIC",
        primaryFeatureName = analyte.primaryFeature,
        primaryFeatureValue = index / 95.0 * 82.0,
        concentrationValue = concentration,
        concentrationUnit = analyte.concentrationUnit,
        reliableRangeStatus = rangeStatus,
        backgroundValue = 4.0,
        signalToNoiseRatio = 9.0,
        confidence = 0.93,
        signalDetectable = true,
        qualityReliable = true,
        processorName = analyte.processorName,
        processorVersion = analyte.processorVersion,
        modelSnapshotJson = "{}",
        rawSignalJson = "{}",
        correctedSignalJson = null,
        qcJson = "{}",
        quantificationQcJson = "{}",
        qc = ArrayMeasurementQc(
            geometrySourceCode = null,
            geometryFlags = emptySet(),
            photometryFlags = emptySet(),
            quantificationStatus = if (concentration == null) "OUT_OF_PROJECT_RANGE" else "QUANTIFIED",
            quantificationScope = null,
            quantificationReason = null
        ),
        detail = ArrayMeasurementDetail.LegacyUnparsed("{}", null)
    )

    companion object {
        private const val ARGUMENT_HOLD_SCREENSHOT = "plate96ResultHoldScreenshot"
        private const val ARGUMENT_HOLD_ANALYSIS_SCREENSHOT = "plate96AnalysisHoldScreenshot"
        private const val ARGUMENT_HOLD_VALIDATION_SCREENSHOT = "plate96ValidationHoldScreenshot"
        private const val ARGUMENT_HOLD_EXPORT_SCREENSHOT = "plate96ExportHoldScreenshot"
        private const val ARGUMENT_HOLD_PROCESS_SCREENSHOT = "plate96ProcessHoldScreenshot"
        private const val ARGUMENT_HOLD_LEGACY_PROCESS_SCREENSHOT = "plate96LegacyProcessHoldScreenshot"
        private const val SCREENSHOT_HOLD_MILLIS = 55_000L
    }
}
