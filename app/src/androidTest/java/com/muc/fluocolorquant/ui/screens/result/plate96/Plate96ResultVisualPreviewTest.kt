package com.muc.fluocolorquant.ui.screens.result.plate96

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
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
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshotMapper
import com.muc.fluocolorquant.ui.theme.FluoColorTheme
import com.muc.fluocolorquant.ui.viewmodels.Plate96ResultUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

/**
 * 结果页视觉预览。
 *
 * 用途是**人工目检**而不是断言回归：结果页需要一次真实检测运行才能打开，为了看一眼
 * 改版效果去跑完整的拍照—定位—布局—定量链路成本过高，而设计是否成立必须眼见为实。
 *
 * 这里用合成快照直接渲染页面，配合 `-e plate96VisualHold true` 让画面停留，便于截图。
 * 合成数据只服务于目检，不参与任何科学结论：它不进入数据库、不产生运行记录，
 * 也不会被导出（AGENTS.md 11 要求过程图必须来自真实数据，此处仅为界面预览）。
 *
 * 运行：
 * ```
 * gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 *   com.muc.fluocolorquant.ui.screens.result.plate96.Plate96ResultVisualPreviewTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class Plate96ResultVisualPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 良好质量：R² 高、重复孔一致、绝大多数在量程内。 */
    @Test
    fun previewGoodQuality() {
        renderAndHold("01-good", rSquared = 0.9987, replicateNoise = 0.03, retestCount = 0)
    }

    /** 待复核：重复孔变异偏大，裁决条应显示警告语义与降级原因。 */
    @Test
    fun previewNeedsReview() {
        renderAndHold("02-review", rSquared = 0.9962, replicateNoise = 0.22, retestCount = 9)
    }

    /** 仅信号运行：没有标准曲线，指标带应换成信号统计而不是浓度。 */
    @Test
    fun previewSignalOnly() {
        renderAndHold("03-signal-only", rSquared = null, replicateNoise = 0.05, retestCount = 0, signalOnly = true)
    }

    private fun renderAndHold(
        fileName: String,
        rSquared: Double?,
        replicateNoise: Double,
        retestCount: Int,
        signalOnly: Boolean = false
    ) {
        val snapshot = buildSnapshot(rSquared, replicateNoise, retestCount, signalOnly)
        composeRule.setContent {
            FluoColorTheme {
                Plate96ResultContent(
                    state = Plate96ResultUiState.Success(snapshot),
                    onBack = {},
                    onRetry = {}
                )
            }
        }
        composeRule.waitForIdle()
        // 直接把根节点渲染成位图落盘，而不是靠外部 screencap 抓取：
        // Compose 测试会接管动画时钟并在测试结束后立即销毁 Activity，
        // 外部抓屏几乎必然错过画面。
        captureRoot(fileName)
    }

    /**
     * 把当前界面存到应用外部文件目录，供 `adb pull` 取出目检。
     *
     * 使用 captureToImage 而不是 Instrumentation 截屏：前者只取 Compose 根节点，
     * 不含状态栏与导航栏，画面即所见的页面本身。
     */
    private fun captureRoot(fileName: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // 写进 AGP 的 additionalTestOutputDir：构建结束后由 Gradle 自动拉回主机的
        // build/outputs/connected_android_test_additional_output/，不依赖 adb 访问
        // /sdcard/Android/data（Android 11 起该路径对 adb shell 不可见），也不受
        // 应用被卸载的影响。
        val arguments = InstrumentationRegistry.getArguments()
        val outputRoot = arguments.getString("additionalTestOutputDir")
            ?: InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath
        val directory = File(outputRoot).apply { mkdirs() }
        val target = File(directory, fileName + ".png")
        FileOutputStream(target).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        android.util.Log.i("VisualPreview", "已写入 " + target.absolutePath)
    }

    private fun buildSnapshot(
        rSquared: Double?,
        replicateNoise: Double,
        retestCount: Int,
        signalOnly: Boolean
    ): Plate96ResultSnapshot {
        val analyte = analyte(rSquared, signalOnly)
        val array = ArrayResultSnapshot(
            runId = "plate96-visual-preview",
            projectId = "preview-project",
            projectName = "CEA 血清批次 2026-07",
            runTimestampEpochMillis = 1_760_000_000_000L,
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
            sites = (0 until 96).map { index ->
                site(index, analyte, replicateNoise, retestCount, signalOnly)
            },
            frame = ArrayFrameResult(
                locatorName = "plate96-yolo-circle",
                locatorVersion = "1.0",
                rectifiedWidth = 1200,
                rectifiedHeight = 800,
                chipRegionMethod = "YOLO_HOUGH_GRID",
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = 0.94,
                    trusted = true,
                    observedRatio = 0.94,
                    geometryRmsePx = 0.38,
                    inlierCount = 90,
                    outlierCount = 6,
                    meanConfidence = 0.93
                ),
                qcIssues = emptyList(),
                frameQcJson = "{}"
            ),
            artifacts = emptyList(),
            effectiveConfigSnapshotJson = "{}",
            configurationDeviationJson = null,
            acquisitionMetadataJson = ORIENTATION_JSON,
            processingVersionJson = null,
            modelUsageJson = null,
            siteQcSummaryJson = null
        )
        return (Plate96ResultSnapshotMapper.map(array) as Plate96ResultLoadResult.Success).snapshot
    }

    private fun analyte(rSquared: Double?, signalOnly: Boolean) = ArrayAnalyteResult(
        analyteId = "cea",
        name = "CEA",
        displayOrder = 0,
        concentrationUnit = if (signalOnly) "" else "ng/mL",
        reliableRangeMin = 0.5,
        reliableRangeMax = 60.0,
        modelId = if (signalOnly) "" else "curve-cea",
        modelName = if (signalOnly) "" else "CEA 四参数曲线",
        modelType = if (signalOnly) "SIGNAL_ONLY" else "STANDARD_CURVE",
        modelVersion = 1,
        primaryFeature = "DELTA_E_2000",
        processorName = "colorimetric-photometry",
        processorVersion = "v2",
        fittingFunction = if (signalOnly) null else "four_parameter_logistic",
        fittingParameters = if (signalOnly) emptyMap() else mapOf("a" to 1.9, "d" to 84.0),
        calibrationPoints = if (signalOnly) {
            emptyList()
        } else {
            // 标准品按对数梯度分布，贴近免疫比色实际标定
            listOf(0.5, 2.0, 8.0, 20.0, 40.0, 60.0).mapIndexed { position, concentration ->
                ArrayCalibrationPointResult(concentration, signalFor(concentration), position)
            }
        },
        validationMetrics = if (rSquared == null) {
            emptyMap()
        } else {
            mapOf("R2" to rSquared, "RMSE" to 1.42, "MAE" to 1.08)
        },
        projectRangeMin = 0.5,
        projectRangeMax = 60.0,
        calibrationRangeMin = 0.5,
        calibrationRangeMax = 60.0
    )

    /** 四参数曲线的正向响应，用于让标准点与样本信号自洽。 */
    private fun signalFor(concentration: Double): Double =
        1.9 + (84.0 - 1.9) / (1.0 + (12.0 / concentration.coerceAtLeast(0.01)).pow(1.15))

    private fun site(
        index: Int,
        analyte: ArrayAnalyteResult,
        replicateNoise: Double,
        retestCount: Int,
        signalOnly: Boolean
    ): ArrayPhysicalSiteResult {
        val row = index / 12
        val column = index % 12
        val point = GridPoint(column * 80.0 + 40.0, row * 80.0 + 40.0)

        // 前 12 孔为标准品（两组重复），其余为样本；样本按重复组三孔一组。
        val isStandard = index < 12
        val role = if (isStandard) "STANDARD" else "SAMPLE"
        val sampleOrdinal = index - 12
        val repeatGroup = if (isStandard) null else "S${sampleOrdinal / 3 + 1}"

        // 样本浓度按重复组给一个基准值，组内叠加噪声——这样重复孔 CV 才有真实意义。
        val groupBase = if (isStandard) {
            listOf(0.5, 2.0, 8.0, 20.0, 40.0, 60.0)[(index % 6)]
        } else {
            LOG_LADDER[(sampleOrdinal / 3) % LOG_LADDER.size]
        }
        val jitter = 1.0 + replicateNoise * ((index % 3) - 1)
        val trueConcentration = groupBase * jitter

        // 末尾若干孔标为需复测，用于观察裁决条的降级表现。
        val needsRetest = !isStandard && index >= 96 - retestCount
        val concentration = when {
            signalOnly -> null
            needsRetest -> null
            else -> trueConcentration
        }
        val rangeStatus = when {
            needsRetest -> "ABOVE_PROJECT_RANGE"
            concentration != null && concentration > 60.0 -> "ABOVE_PROJECT_RANGE"
            concentration != null && concentration < 0.5 -> "BELOW_PROJECT_RANGE"
            else -> "WITHIN_RANGE"
        }

        return ArrayPhysicalSiteResult(
            siteIndex = index,
            rowIndex = row,
            columnIndex = column,
            siteKey = "R${(row + 1).toString().padStart(2, '0')}C${(column + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = role,
            analyteId = analyte.analyteId,
            defaultSampleSlot = repeatGroup,
            sampleSlot = repeatGroup,
            overrideReason = null,
            standardConcentration = if (isStandard) groupBase else null,
            repeatGroup = repeatGroup,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = point,
                original = point,
                confidence = if (index >= 92) 0.52 else 0.95,
                source = if (index >= 92) GridPointSource.MODEL_IMPUTED else GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            ),
            measurements = listOf(
                ArraySiteMeasurementResult(
                    measurementId = index.toLong() + 1,
                    analyteId = analyte.analyteId,
                    detectionMode = "COLORIMETRIC",
                    primaryFeatureName = analyte.primaryFeature,
                    primaryFeatureValue = signalFor(trueConcentration),
                    concentrationValue = concentration,
                    concentrationUnit = analyte.concentrationUnit,
                    reliableRangeStatus = rangeStatus,
                    backgroundValue = 3.2,
                    signalToNoiseRatio = 11.0,
                    confidence = 0.94,
                    signalDetectable = true,
                    qualityReliable = !needsRetest,
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
                        quantificationStatus = when {
                            signalOnly -> "SIGNAL_ONLY"
                            concentration == null -> "OUT_OF_PROJECT_RANGE"
                            else -> "QUANTIFIED"
                        },
                        quantificationScope = null,
                        quantificationReason = null
                    ),
                    detail = ArrayMeasurementDetail.LegacyUnparsed("{}", null)
                )
            )
        )
    }

    private companion object {
        /** 样本浓度梯度，跨两个数量级，便于观察色带刻度是否读得出量级。 */
        val LOG_LADDER = listOf(0.8, 1.6, 3.2, 6.4, 12.8, 22.0, 32.0, 44.0)

        const val ORIENTATION_JSON =
            """{"plate96Orientation":{"sourceRows":12,"sourceColumns":8,""" +
                """"quarterTurnsClockwise":1,"originCorner":"TOP_LEFT","userConfirmed":true}}"""
    }
}
