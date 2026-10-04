package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQualityLevel
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.resolveArrayMeasurementQuality
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import kotlin.math.max
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val ARRAY_HEATMAP_CELL_TAG_PREFIX: String = "array_heatmap_cell_"
const val ARRAY_HEATMAP_TRANSFORM_TAG: String = "array_heatmap_transform_container"
const val ARRAY_HEATMAP_ZOOM_TOGGLE_TAG: String = "array_heatmap_zoom_toggle"
const val ARRAY_HEATMAP_ZOOMED_CONTENT_TAG: String = "array_heatmap_zoomed_content"
const val ARRAY_HEATMAP_EXTRAPOLATED_MARKER_TAG_PREFIX: String = "array_heatmap_extrapolated_marker_"
const val ARRAY_HEATMAP_BELOW_RANGE_MARKER_TAG_PREFIX: String = "array_heatmap_below_range_marker_"
const val ARRAY_HEATMAP_ABOVE_RANGE_MARKER_TAG_PREFIX: String = "array_heatmap_above_range_marker_"
const val ARRAY_HEATMAP_SCALE_SELECTOR_TAG: String = "array_heatmap_scale_selector"
const val ARRAY_HEATMAP_SCALE_OPTION_TAG_PREFIX: String = "array_heatmap_scale_option_"

/** 热力图底色所表达的科学量，QC 不得通过切换该枚举来改变底色含义。 */
enum class ArrayHeatmapScaleMode {
    /** 芯片总览只表达位点可靠性百分比，不混合不同分析物的浓度单位。 */
    OVERVIEW_RELIABILITY,

    /** 当前分析物存在可用浓度时，使用本次分布或项目量程。 */
    CONCENTRATION,

    /** 整个分析物只能保留信号时，改用当前模型声明的主特征。 */
    PRIMARY_FEATURE
}

/**
 * 浓度热力图的显示归一化方式。
 *
 * 该枚举只改变颜色映射，不修改、截断或重新计算冻结浓度。默认结果页使用本次分布增强孔间差异，
 * 项目量程模式用于跨实验按同一绝对尺度比较。
 */
enum class ArrayHeatmapConcentrationScaleMode {
    RUN_DISTRIBUTION,
    PROJECT_RANGE
}

/** 色带边界的来源会展示给用户，防止把观测范围误认为模型可靠范围。 */
enum class ArrayHeatmapRangeSource {
    FIXED_PERCENTAGE,
    PROJECT_RANGE,
    RUN_DISTRIBUTION,
    OBSERVED_VALUES,
    EMPTY_FALLBACK
}

/** 热力图色带只保存数值学含义，不夹带警告或失败颜色。 */
data class ArrayHeatmapScale(
    val mode: ArrayHeatmapScaleMode,
    val minimum: Double,
    val maximum: Double,
    val unit: String,
    val featureName: String?,
    val rangeSource: ArrayHeatmapRangeSource,
    /** 非浓度模式为 null；浓度模式记录当前实际采用的颜色尺度。 */
    val concentrationScaleMode: ArrayHeatmapConcentrationScaleMode? = null,
    /** 只有同时具备本次有限浓度与合法项目量程时，页面才展示双尺度切换。 */
    val availableConcentrationScaleModes: Set<ArrayHeatmapConcentrationScaleMode> = emptySet()
)

/**
 * 热力图单元的数值状态与质量状态相互独立。
 *
 * 浓度底色只由该状态和显示值决定；几何、光度等质量风险只能添加轻量提示，不能覆盖底色。
 */
enum class ArrayHeatmapValueState {
    /** 位于可执行范围内，直接显示计算得到的浓度或信号。 */
    QUANTIFIED,

    /** 超出曲线真实标定区间、但仍位于项目量程内，显示实际外推浓度。 */
    CALIBRATION_EXTRAPOLATED,

    /** 只能报告低端方向或上限；可能来自项目边界、可信边界或删失观测。 */
    BELOW_PROJECT_RANGE,

    /** 只能报告高端方向或下限；可能来自项目边界、可信边界或删失观测。 */
    ABOVE_PROJECT_RANGE,

    /** 当前热力图维度确实没有可解释数值。 */
    UNAVAILABLE
}

/**
 * 位点质量采用并行通道编码。
 *
 * warning 只在小阵列使用弱边框，failure 表示真正没有可用结果，lowSignal 使用低对比度微标记。
 * 曲线外推和项目量程方向由 [ArrayHeatmapValueState] 表达，不再混入质量布尔值。
 */
data class ArrayHeatmapQcEncoding(
    val warning: Boolean,
    val failure: Boolean,
    val lowSignal: Boolean
)

/** 从领域快照提取出的轻量输入，便于纯 JVM 测试色带规则。 */
data class ArrayHeatmapValueInput(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val siteKey: String,
    val enabled: Boolean,
    val roleCode: String?,
    /** false 表示该物理位点属于其他分析物或全局资源，不应计为当前分析物漏检。 */
    val applicable: Boolean = true,
    val hasMeasurement: Boolean = true,
    val concentrationValue: Double?,
    val primaryFeatureValue: Double?,
    val reliableRangeStatus: String?,
    val signalDetectable: Boolean,
    val qualityReliable: Boolean,
    val geometryFlags: Set<String>,
    val photometryFlags: Set<String>,
    /** 量化执行状态用于区分“没有模型结果”和普通图像复核提示。 */
    val quantificationStatus: String? = null,
    /** Room 15 强类型结果：QUANTIFIED / ESTIMATED / BOUND_ONLY / UNAVAILABLE。 */
    val quantificationState: String? = null,
    /** BOUND_ONLY 时用于区分“>下界”和“<上界”。 */
    val censoringDirection: String? = null,
    /** 严重饱和才属于不可用；轻度饱和继续保留浓度并提示复核。 */
    val saturationRatio: Double? = null,
    /** ROI 大面积超出图像边界时结果不可用，小比例裁切仅提示复核。 */
    val roiClipRatio: Double? = null,
    /** 背景环大面积裁切时局部背景扣除同样失去依据。 */
    val annulusClipRatio: Double? = null
)

/** 单个物理位点的最终绘制模型。 */
data class ArrayHeatmapCell(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val siteKey: String,
    val enabled: Boolean,
    val roleCode: String?,
    val applicable: Boolean,
    val hasMeasurement: Boolean,
    val displayValue: Double?,
    val normalizedValue: Float?,
    val valueState: ArrayHeatmapValueState,
    val qc: ArrayHeatmapQcEncoding,
    /** 保留冻结的原始范围状态，统计时不能从用于绘图的方向状态反向猜测。 */
    val reliableRangeStatus: String? = null,
    /** 保留 QUANTIFIED / ESTIMATED / BOUND_ONLY / UNAVAILABLE 科学语义。 */
    val quantificationState: String? = null,
    /** 单侧界限方向，用于区分“仅下限”和“仅上限”。 */
    val censoringDirection: String? = null
)

/** 一张热力图及其统计摘要。 */
data class ArrayHeatmapModel(
    val rows: Int,
    val columns: Int,
    val analyteId: String?,
    val scale: ArrayHeatmapScale,
    val cells: List<ArrayHeatmapCell>,
    val measuredCount: Int,
    /** 当前显示维度已经形成有限值或明确项目量程方向的位点数量。 */
    val calculatedCount: Int,
    /** 标定范围内且质量可用的点浓度数量。 */
    val quantifiedCount: Int = 0,
    /** 标定范围外但仍通过可信区间门槛的估计数量。 */
    val estimatedCount: Int = 0,
    /** 单侧界限或不可用、需要复测的数量。 */
    val retestCount: Int = 0,
    /** 直接位于曲线标定范围内的浓度数量。 */
    val withinCalibrationRangeCount: Int,
    /** 位于项目量程内、但使用曲线外推得到浓度的数量。 */
    val calibrationExtrapolatedCount: Int,
    /** 低于或高于项目声明量程、只保留方向并投影到色带端点显示的数量。 */
    val outsideProjectRangeCount: Int,
    val reliableCount: Int,
    val warningCount: Int,
    val failureCount: Int,
    val lowSignalCount: Int,
    val missingCount: Int,
    val roleCounts: Map<String, Int>,
    /**
     * 旧版本曾把标定范围外的有限信号保存为“无浓度”，导致同一运行只有少量位点有浓度。
     * 结果页不能把浓度和信号混进同一色带，因此检测到这种历史快照时统一回退为信号热力图。
     */
    val historicalConcentrationIncomplete: Boolean = false
)

/** 零基行列转线性索引的唯一公式，禁止复用旧 8×12 孔板映射。 */
fun resolveArraySiteIndex(rowIndex: Int, columnIndex: Int, columns: Int): Int {
    require(rowIndex >= 0) { "阵列行号不能为负数" }
    require(columnIndex >= 0) { "阵列列号不能为负数" }
    require(columns > 0) { "阵列列数必须大于零" }
    require(columnIndex < columns) { "阵列列号超出范围" }
    return rowIndex * columns + columnIndex
}

/**
 * 为单个分析物创建热力图。
 *
 * 只要存在至少一个有限浓度，整张图就保持浓度语义；只有完全没有浓度时才整体切到
 * 主特征信号，避免同一色带中混合 ng/mL 与 ΔE、灰度或荧光强度。
 */
fun buildAnalyteHeatmapModel(
    rows: Int,
    columns: Int,
    analyte: ArrayAnalyteResult,
    inputs: List<ArrayHeatmapValueInput>,
    concentrationScaleMode: ArrayHeatmapConcentrationScaleMode =
        ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE
): ArrayHeatmapModel {
    require(rows > 0 && columns > 0) { "阵列行列必须大于零" }
    require(inputs.map { it.siteIndex }.distinct().size == inputs.size) { "热力图输入包含重复位点" }
    require(inputs.all { it.siteIndex in 0 until rows * columns }) { "热力图输入位点越界" }
    require(inputs.all {
        it.rowIndex in 0 until rows &&
            it.columnIndex in 0 until columns &&
            resolveArraySiteIndex(it.rowIndex, it.columnIndex, columns) == it.siteIndex
    }) { "热力图输入的行列与线性索引不一致" }

    // 色带只能由当前分析物适用位点形成；其他分析物或全局参考位不能偷偷拉伸本次分布。
    val finiteConcentrations = inputs.filter { it.hasMeasurement && it.applicable }
        .mapNotNull { it.concentrationValue?.takeIf(Double::isFinite) }
    val finiteFeatures = inputs.filter { it.hasMeasurement && it.applicable }
        .mapNotNull { it.primaryFeatureValue?.takeIf(Double::isFinite) }
    val historicalConcentrationIncomplete = inputs.any { input ->
        input.applicable &&
            input.hasMeasurement &&
            input.concentrationValue?.isFinite() != true &&
            input.primaryFeatureValue?.isFinite() == true &&
            input.reliableRangeStatus?.uppercase() in setOf("BELOW_RANGE", "ABOVE_RANGE") &&
            // 新版深度学习链会在同一范围状态下保存 BOUND_ONLY。它是完整的单侧浓度
            // 结论，不属于旧版“范围状态存在但强类型浓度字段遗漏”的兼容场景。
            input.quantificationState.isNullOrBlank() &&
            input.quantificationStatus.equals("OUT_OF_RELIABLE_RANGE", ignoreCase = true)
    }
    val hasValidProjectRange = analyte.projectRangeMin?.isFinite() == true &&
        analyte.projectRangeMax?.isFinite() == true &&
        requireNotNull(analyte.projectRangeMax) > requireNotNull(analyte.projectRangeMin)
    val hasProjectBoundaryResult = inputs.any { input ->
        input.applicable &&
            input.hasMeasurement &&
            (
                input.reliableRangeStatus?.uppercase() in setOf(
                    "BELOW_PROJECT_RANGE",
                    "ABOVE_PROJECT_RANGE",
                    "BELOW_TRUSTED_RANGE",
                    "ABOVE_TRUSTED_RANGE"
                ) || input.quantificationState.equals("BOUND_ONLY", ignoreCase = true)
            )
    }
    // 旧运行只保存了部分浓度时，整张图统一使用信号值；绝不能让同一色带同时表达浓度和信号。
    // 即使全部位点都落在项目量程外，只要项目边界完整，仍应保持浓度语义并显示方向标记；
    // 不能因为没有精确浓度而退回信号色带，导致用户丢失“低于/高于量程”的直接解释。
    val useConcentration = !historicalConcentrationIncomplete && (
        finiteConcentrations.isNotEmpty() || (hasValidProjectRange && hasProjectBoundaryResult)
    )
    val scale = if (useConcentration) {
        buildConcentrationScale(
            analyte = analyte,
            finiteValues = finiteConcentrations,
            requestedMode = concentrationScaleMode
        )
    } else {
        buildFeatureScale(analyte, finiteFeatures)
    }
    val inputByIndex = inputs.associateBy(ArrayHeatmapValueInput::siteIndex)
    val cells = List(rows * columns) { siteIndex ->
        val rowIndex = siteIndex / columns
        val columnIndex = siteIndex % columns
        val input = inputByIndex[siteIndex]
        if (input == null) {
            emptyHeatmapCell(siteIndex, rowIndex, columnIndex)
        } else {
            val rawValue = if (!input.hasMeasurement) {
                null
            } else if (useConcentration) {
                input.concentrationValue?.takeIf(Double::isFinite)
            } else {
                input.primaryFeatureValue?.takeIf(Double::isFinite)
            }
            val initialValueState = input.resolveValueState(
                useConcentration = useConcentration,
                rawValue = rawValue
            )
            val initialDisplayValue = when (initialValueState) {
                ArrayHeatmapValueState.BELOW_PROJECT_RANGE -> scale.minimum
                ArrayHeatmapValueState.ABOVE_PROJECT_RANGE -> scale.maximum
                ArrayHeatmapValueState.QUANTIFIED,
                ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> rawValue
                ArrayHeatmapValueState.UNAVAILABLE -> null
            }
            val hardFailure = input.hasMeasurement && input.isHardFailure(initialDisplayValue)
            val valueState = if (hardFailure) {
                ArrayHeatmapValueState.UNAVAILABLE
            } else {
                initialValueState
            }
            val displayValue = initialDisplayValue.takeUnless { hardFailure }
            ArrayHeatmapCell(
                siteIndex = siteIndex,
                rowIndex = rowIndex,
                columnIndex = columnIndex,
                siteKey = input.siteKey,
                enabled = input.enabled,
                roleCode = input.roleCode,
                applicable = input.applicable,
                hasMeasurement = input.hasMeasurement,
                displayValue = displayValue,
                normalizedValue = displayValue?.let { normalizeHeatmapValue(it, scale) },
                valueState = valueState,
                qc = if (input.hasMeasurement) {
                    input.toQcEncoding(
                        hardFailure = hardFailure,
                        displayValue = initialDisplayValue
                    )
                } else {
                    ArrayHeatmapQcEncoding(
                        warning = input.geometryFlags.isNotEmpty(),
                        failure = false,
                        lowSignal = false
                    )
                },
                reliableRangeStatus = input.reliableRangeStatus,
                quantificationState = input.quantificationState,
                censoringDirection = input.censoringDirection
            )
        }
    }
    return buildHeatmapModel(
        rows = rows,
        columns = columns,
        analyteId = analyte.analyteId,
        scale = scale,
        cells = cells,
        historicalConcentrationIncomplete = historicalConcentrationIncomplete
    )
}

/** 从冻结运行快照提取当前分析物，不读取当前模板或模型库。 */
fun buildAnalyteHeatmapModel(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult,
    concentrationScaleMode: ArrayHeatmapConcentrationScaleMode =
        ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE
): ArrayHeatmapModel {
    val inputs = snapshot.sites.map { site ->
        val measurement = site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
        site.toHeatmapInput(
            measurement = measurement,
            applicable = site.analyteId == analyte.analyteId
        )
    }
    return buildAnalyteHeatmapModel(
        rows = snapshot.rows,
        columns = snapshot.columns,
        analyte = analyte,
        inputs = inputs,
        concentrationScaleMode = concentrationScaleMode
    )
}

/**
 * 芯片总览按物理位点汇总可靠性，不把不同分析物的绝对浓度放进同一色带。
 * 100 表示质量可靠且处于模型可靠范围，50 表示需关注，0 表示质量失败。
 */
fun buildOverviewHeatmapModel(snapshot: ArrayResultSnapshot): ArrayHeatmapModel {
    require(snapshot.rows > 0 && snapshot.columns > 0) { "阵列行列必须大于零" }
    val siteByIndex = snapshot.sites.associateBy(ArrayPhysicalSiteResult::siteIndex)
    val scale = ArrayHeatmapScale(
        mode = ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY,
        minimum = 0.0,
        maximum = 100.0,
        unit = "%",
        featureName = null,
        rangeSource = ArrayHeatmapRangeSource.FIXED_PERCENTAGE
    )
    val cells = List(snapshot.rows * snapshot.columns) { siteIndex ->
        val rowIndex = siteIndex / snapshot.columns
        val columnIndex = siteIndex % snapshot.columns
        val site = siteByIndex[siteIndex]
        if (site == null) {
            emptyHeatmapCell(siteIndex, rowIndex, columnIndex)
        } else {
            val measurement = site.measurements.firstOrNull { it.analyteId == site.analyteId }
                ?: site.measurements.firstOrNull()
            if (measurement == null) {
                ArrayHeatmapCell(
                    siteIndex = siteIndex,
                    rowIndex = rowIndex,
                    columnIndex = columnIndex,
                    siteKey = site.siteKey,
                    enabled = site.enabled,
                    roleCode = site.roleCode,
                    applicable = true,
                    hasMeasurement = false,
                    displayValue = null,
                    normalizedValue = null,
                    valueState = ArrayHeatmapValueState.UNAVAILABLE,
                    qc = ArrayHeatmapQcEncoding(false, false, false)
                )
            } else {
                val input = site.toHeatmapInput(measurement)
                val hardFailure = input.isHardFailure(measurement.primaryFeatureValue)
                val qc = input.toQcEncoding(
                    hardFailure = hardFailure,
                    displayValue = measurement.primaryFeatureValue
                )
                val reliabilityPercent = when {
                    qc.failure -> 0.0
                    qc.warning || qc.lowSignal -> 50.0
                    else -> 100.0
                }
                ArrayHeatmapCell(
                    siteIndex = siteIndex,
                    rowIndex = rowIndex,
                    columnIndex = columnIndex,
                    siteKey = site.siteKey,
                    enabled = site.enabled,
                    roleCode = site.roleCode,
                    applicable = true,
                    hasMeasurement = true,
                    displayValue = reliabilityPercent,
                    normalizedValue = normalizeHeatmapValue(reliabilityPercent, scale),
                    valueState = if (hardFailure) {
                        ArrayHeatmapValueState.UNAVAILABLE
                    } else {
                        ArrayHeatmapValueState.QUANTIFIED
                    },
                    qc = qc
                )
            }
        }
    }
    return buildHeatmapModel(
        rows = snapshot.rows,
        columns = snapshot.columns,
        analyteId = null,
        scale = scale,
        cells = cells
    )
}

/** 通用阵列网格；15×15 及更大自定义阵列启用双指缩放和平移。 */
@Composable
fun ArrayHeatmap(
    model: ArrayHeatmapModel,
    onSiteClick: (ArrayHeatmapCell) -> Unit,
    modifier: Modifier = Modifier
) {
    val largestDimension = max(model.rows, model.columns)
    val transformEnabled = largestDimension >= 15
    val gap = when {
        largestDimension <= 4 -> 4.dp
        largestDimension <= 10 -> 2.dp
        else -> 1.dp
    }
    var userScale by remember(model.rows, model.columns, model.analyteId) {
        mutableFloatStateOf(1f)
    }
    var panOffset by remember(model.rows, model.columns, model.analyteId) {
        mutableStateOf(Offset.Zero)
    }
    var interactionEnabled by remember(model.rows, model.columns, model.analyteId) {
        mutableStateOf(false)
    }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(model.rows, model.columns, model.analyteId, model.scale.mode) {
        // 切换分析物或色带语义时回到完整阵列，避免用户误以为少了位点。
        userScale = 1f
        panOffset = Offset.Zero
        interactionEnabled = false
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (userScale * zoomChange).coerceIn(1f, 4f)
        val maxPanX = viewportSize.width * (nextScale - 1f) / 2f
        val maxPanY = viewportSize.height * (nextScale - 1f) / 2f
        userScale = nextScale
        panOffset = Offset(
            x = (panOffset.x + panChange.x).coerceIn(-maxPanX, maxPanX),
            y = (panOffset.y + panChange.y).coerceIn(-maxPanY, maxPanY)
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 高而窄的自定义阵列限制最大高度，并按原始行列比缩小宽度，仍保持单元格为方形。
        val naturalHeight = maxWidth * model.rows.toFloat() / model.columns.toFloat()
        val gridHeight = minOf(naturalHeight, 520.dp)
        val gridWidth = gridHeight * model.columns.toFloat() / model.rows.toFloat()
        Box(
            modifier = Modifier
                .width(gridWidth)
                .height(gridHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(FluoRadius.control))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .onSizeChanged { viewportSize = it }
                .testTag(if (transformEnabled) ARRAY_HEATMAP_TRANSFORM_TAG else "array_heatmap_static_container")
                // 大阵列默认把单指纵向拖动交给外层结果页滚动；只有用户明确点击缩放按钮后，
                // 才启用缩放和平移手势。这样既保留大阵列细看能力，也不会把下方统计、
                // 标准曲线和角色分布困在热力图之后。
                .then(
                    if (transformEnabled && interactionEnabled) {
                        Modifier.transformable(transformState)
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = userScale,
                        scaleY = userScale,
                        translationX = panOffset.x,
                        translationY = panOffset.y
                    )
                    .testTag(
                        if (userScale > 1.05f) {
                            ARRAY_HEATMAP_ZOOMED_CONTENT_TAG
                        } else {
                            "array_heatmap_full_content"
                        }
                    )
                    .padding(gap / 2f)
            ) {
                repeat(model.rows) { rowIndex ->
                    Row(modifier = Modifier.weight(1f)) {
                        repeat(model.columns) { columnIndex ->
                            val siteIndex = resolveArraySiteIndex(rowIndex, columnIndex, model.columns)
                            ArrayHeatmapCellView(
                                cell = model.cells[siteIndex],
                                scale = model.scale,
                                showText = largestDimension <= 4,
                                showWarningBorder = largestDimension < 15,
                                showLowSignalMarker = largestDimension < 15,
                                gap = gap,
                                onClick = onSiteClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
            if (transformEnabled) {
                IconButton(
                    onClick = {
                        if (interactionEnabled) {
                            // 退出交互模式时恢复完整阵列，避免下次进入仍停留在局部放大状态。
                            interactionEnabled = false
                            userScale = 1f
                            panOffset = Offset.Zero
                        } else {
                            // 首次点击必须立即产生清晰的视觉反馈；进入交互模式后直接放大，
                            // 用户随后可以单指拖动或双指继续调整 1～4 倍缩放。
                            interactionEnabled = true
                            userScale = DEFAULT_ARRAY_INSPECTION_SCALE
                            panOffset = Offset.Zero
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(38.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            CircleShape
                        )
                        .testTag(ARRAY_HEATMAP_ZOOM_TOGGLE_TAG)
                ) {
                    Icon(
                        imageVector = if (interactionEnabled) {
                            Icons.Default.ZoomOutMap
                        } else {
                            Icons.Default.ZoomIn
                        },
                        contentDescription = stringResource(
                            if (interactionEnabled) {
                                R.string.array_heatmap_zoom_exit
                            } else {
                                R.string.array_heatmap_zoom_enter
                            }
                        ),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 浓度热力图的双尺度选择器。
 *
 * 使用紧凑的同层胶囊切换，避免在专业结果页增加解释段落。若当前运行没有有限浓度或没有合法
 * 项目量程，函数直接不渲染，页面继续使用唯一可执行的色带。
 */
@Composable
fun ArrayHeatmapScaleSelector(
    scale: ArrayHeatmapScale,
    onScaleModeChange: (ArrayHeatmapConcentrationScaleMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedMode = scale.concentrationScaleMode ?: return
    val options = ArrayHeatmapConcentrationScaleMode.entries.filter {
        it in scale.availableConcentrationScaleModes
    }
    if (options.size < 2) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ARRAY_HEATMAP_SCALE_SELECTOR_TAG),
        shape = RoundedCornerShape(FluoRadius.badge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { option ->
                val selected = option == selectedMode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            onClick = { onScaleModeChange(option) },
                            role = Role.RadioButton
                        )
                        .testTag("$ARRAY_HEATMAP_SCALE_OPTION_TAG_PREFIX${option.name}"),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (option) {
                                ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION ->
                                    R.string.array_heatmap_scale_run_distribution
                                ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE ->
                                    R.string.array_heatmap_scale_project_range
                            }
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 热力图使用与旧结果页一致的专业渐变，但输入永远是已经归一化的 0～1。 */
fun arrayHeatmapBaseColor(
    normalizedValue: Float?,
    scaleMode: ArrayHeatmapScaleMode = ArrayHeatmapScaleMode.CONCENTRATION
): Color {
    val normalized = normalizedValue?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
        ?: return Color(0xFFE1E5EA)
    return if (scaleMode == ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY) {
        // 可靠性总览使用“失败红 → 警告黄 → 可靠绿”，避免沿用浓度色带后把高可靠画成红色。
        if (normalized <= 0.5f) {
            lerp(Color(0xFFD32F2F), Color(0xFFF9A825), normalized / 0.5f)
        } else {
            lerp(Color(0xFFF9A825), Color(0xFF16856B), (normalized - 0.5f) / 0.5f)
        }
    } else {
        HeatmapColorUtil.getColor(normalized.toDouble(), 0.0, 1.0)
    }
}

@Composable
private fun ArrayHeatmapCellView(
    cell: ArrayHeatmapCell,
    scale: ArrayHeatmapScale,
    showText: Boolean,
    showWarningBorder: Boolean,
    showLowSignalMarker: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    onClick: (ArrayHeatmapCell) -> Unit,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(if (showText) 10.dp else 3.dp)
    val baseColor = if (cell.enabled && cell.applicable) {
        // 单侧界限的 displayValue 已按方向投影到当前色带端点。底色表达“趋近量程低端/高端”，
        // 箭头与 < / > 继续表达它不是精确点浓度，因此既恢复完整色彩，也不篡改冻结数据。
        arrayHeatmapBaseColor(cell.normalizedValue, scale.mode)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val warningColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
    // 所有状态标记都采用与底色明暗自适应的中性色，避免与浓度色带争夺视觉注意力。
    val subtleMarkerColor = if (baseColor.luminance() < 0.42f) {
        Color.White.copy(alpha = 0.32f)
    } else {
        Color.Black.copy(alpha = 0.24f)
    }
    val textColor = if (baseColor.luminance() < 0.42f) Color.White else Color(0xFF17212B)
    val noValueText = stringResource(R.string.array_heatmap_no_value_symbol)

    Box(
        modifier = modifier
            .padding(gap / 2f)
            .clip(shape)
            .background(baseColor)
            .then(
                // 15×15 主视图优先呈现科学数值，轻度复核数量统一放在下方摘要中；
                // 10×10 及更小阵列仍保留逐格边框，便于直接定位具体位点。
                if (cell.qc.warning && showWarningBorder) {
                    Modifier.border(1.dp, warningColor, shape)
                }
                else Modifier
            )
            .clickable { onClick(cell) }
            .testTag("$ARRAY_HEATMAP_CELL_TAG_PREFIX${cell.siteIndex}"),
        contentAlignment = Alignment.Center
    ) {
        // 大阵列不逐格显示低信号标记，避免 225 个状态点破坏浓度分布的连续阅读。
        if (!cell.qc.failure && cell.qc.lowSignal && showLowSignalMarker) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (showText) 5.dp else 1.dp)
                    .size(if (showText) 4.dp else 2.dp)
                    .background(subtleMarkerColor, CircleShape)
            )
        }
        if (!cell.qc.failure && cell.valueState == ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(if (showText) 4.dp else 1.dp)
                    .size(if (showText) 6.dp else 3.dp)
                    .testTag("$ARRAY_HEATMAP_EXTRAPOLATED_MARKER_TAG_PREFIX${cell.siteIndex}")
            ) {
                // 左下角微三角只表达“曲线范围外估算”，不改变该格的浓度底色。
                val triangle = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(triangle, color = subtleMarkerColor)
            }
        }
        if (
            !showText &&
            !cell.qc.failure &&
            cell.valueState == ArrayHeatmapValueState.BELOW_PROJECT_RANGE
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showText) 4.dp else 1.dp)
                    .width(if (showText) 8.dp else 3.dp)
                    .height(if (showText) 5.dp else 2.dp)
                    .testTag("$ARRAY_HEATMAP_BELOW_RANGE_MARKER_TAG_PREFIX${cell.siteIndex}")
            ) {
                // 向下三角明确表达“低于下限”，同时继续保持低透明度和极小占用。
                val triangle = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(triangle, color = subtleMarkerColor)
            }
        }
        if (
            !showText &&
            !cell.qc.failure &&
            cell.valueState == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (showText) 4.dp else 1.dp)
                    .width(if (showText) 8.dp else 3.dp)
                    .height(if (showText) 5.dp else 2.dp)
                    .testTag("$ARRAY_HEATMAP_ABOVE_RANGE_MARKER_TAG_PREFIX${cell.siteIndex}")
            ) {
                // 向上三角明确表达“高于上限”，方向与低于量程标记不再依赖位置猜测。
                val triangle = Path().apply {
                    moveTo(size.width / 2f, 0f)
                    lineTo(0f, size.height)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(triangle, color = subtleMarkerColor)
            }
        }
        if (showText) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = cell.siteKey,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Text(
                    text = when (cell.valueState) {
                        ArrayHeatmapValueState.BELOW_PROJECT_RANGE ->
                            stringResource(R.string.array_heatmap_upper_bound_symbol)
                        ArrayHeatmapValueState.ABOVE_PROJECT_RANGE ->
                            stringResource(R.string.array_heatmap_lower_bound_symbol)
                        ArrayHeatmapValueState.QUANTIFIED,
                        ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED -> cell.displayValue?.let { value ->
                            val formatted = formatArrayHeatmapValue(value)
                            if (scale.mode == ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY) {
                                stringResource(R.string.array_heatmap_cell_percent, formatted)
                            } else {
                                formatted
                            }
                        } ?: noValueText
                        ArrayHeatmapValueState.UNAVAILABLE -> noValueText
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** 点击缩放按钮后使用的默认观察比例；既能看清单元，又保留足够上下文供拖动定位。 */
private const val DEFAULT_ARRAY_INSPECTION_SCALE: Float = 1.8f

/** 数值标签保持紧凑；极大或极小值使用科学计数法，避免挤压 4×4 单元。 */
fun formatArrayHeatmapValue(value: Double): String {
    require(value.isFinite()) { "热力图显示值必须是有限数值" }
    val absolute = kotlin.math.abs(value)
    return when {
        absolute != 0.0 && (absolute >= 10_000.0 || absolute < 0.01) -> "%.2e".format(value)
        absolute >= 100.0 -> "%.0f".format(value)
        absolute >= 10.0 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
}

private fun buildConcentrationScale(
    analyte: ArrayAnalyteResult,
    finiteValues: List<Double>,
    requestedMode: ArrayHeatmapConcentrationScaleMode
): ArrayHeatmapScale {
    val projectMinimum = analyte.projectRangeMin?.takeIf(Double::isFinite)
    val projectMaximum = analyte.projectRangeMax?.takeIf(Double::isFinite)
    val projectRange = if (
        projectMinimum != null && projectMaximum != null && projectMaximum > projectMinimum
    ) {
        NumericHeatmapRange(projectMinimum, projectMaximum)
    } else {
        null
    }
    val runDistributionRange = buildRunDistributionRange(finiteValues)
    val availableModes = buildSet {
        if (runDistributionRange != null) add(ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION)
        if (projectRange != null) add(ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE)
    }
    val resolvedMode = when {
        requestedMode in availableModes -> requestedMode
        ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION in availableModes ->
            ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION
        ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE in availableModes ->
            ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE
        else -> null
    }
    val selectedRange = when (resolvedMode) {
        ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION -> requireNotNull(runDistributionRange)
        ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE -> requireNotNull(projectRange)
        null -> NumericHeatmapRange(0.0, 1.0)
    }
    return ArrayHeatmapScale(
        mode = ArrayHeatmapScaleMode.CONCENTRATION,
        minimum = selectedRange.minimum,
        maximum = selectedRange.maximum,
        unit = analyte.concentrationUnit,
        featureName = null,
        rangeSource = when (resolvedMode) {
            ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION ->
                ArrayHeatmapRangeSource.RUN_DISTRIBUTION
            ArrayHeatmapConcentrationScaleMode.PROJECT_RANGE ->
                ArrayHeatmapRangeSource.PROJECT_RANGE
            null -> ArrayHeatmapRangeSource.EMPTY_FALLBACK
        },
        concentrationScaleMode = resolvedMode,
        availableConcentrationScaleModes = availableModes
    )
}

/** 本次分布使用P5～P95抑制偶发离群值；点数过少时退回完整最小/最大值，避免稀疏数据被过度裁切。 */
private fun buildRunDistributionRange(finiteValues: List<Double>): NumericHeatmapRange? {
    val sorted = finiteValues.filter(Double::isFinite).sorted()
    if (sorted.isEmpty()) return null
    val minimum = if (sorted.size >= MINIMUM_ROBUST_DISTRIBUTION_VALUES) {
        percentile(sorted, 0.05)
    } else {
        sorted.first()
    }
    val maximum = if (sorted.size >= MINIMUM_ROBUST_DISTRIBUTION_VALUES) {
        percentile(sorted, 0.95)
    } else {
        sorted.last()
    }
    if (maximum > minimum) return NumericHeatmapRange(minimum, maximum)

    // 浓度完全相同时仍要形成可执行色带；仅增加显示用微小跨度，不改写任何孔位数值。
    val padding = max(kotlin.math.abs(minimum) * 0.05, 1e-6)
    val expandedMinimum = (minimum - padding).coerceAtLeast(0.0)
    val expandedMaximum = maximum + padding
    return NumericHeatmapRange(
        minimum = expandedMinimum,
        maximum = if (expandedMaximum > expandedMinimum) expandedMaximum else expandedMinimum + 1e-6
    )
}

/** 对已排序数据做线性插值分位数，保证同一冻结输入始终得到确定性色带。 */
private fun percentile(sortedValues: List<Double>, fraction: Double): Double {
    require(sortedValues.isNotEmpty()) { "分位数输入不能为空" }
    require(fraction in 0.0..1.0) { "分位数比例必须位于0到1之间" }
    if (sortedValues.size == 1) return sortedValues.single()
    val position = fraction * (sortedValues.lastIndex)
    val lowerIndex = kotlin.math.floor(position).toInt()
    val upperIndex = kotlin.math.ceil(position).toInt()
    if (lowerIndex == upperIndex) return sortedValues[lowerIndex]
    val weight = position - lowerIndex
    return sortedValues[lowerIndex] * (1.0 - weight) + sortedValues[upperIndex] * weight
}

private data class NumericHeatmapRange(
    val minimum: Double,
    val maximum: Double
)

private const val MINIMUM_ROBUST_DISTRIBUTION_VALUES: Int = 8

private fun buildFeatureScale(
    analyte: ArrayAnalyteResult,
    finiteValues: List<Double>
): ArrayHeatmapScale {
    val minimum = finiteValues.minOrNull() ?: 0.0
    val maximum = finiteValues.maxOrNull() ?: 1.0
    return ArrayHeatmapScale(
        mode = ArrayHeatmapScaleMode.PRIMARY_FEATURE,
        minimum = minimum,
        maximum = maximum,
        unit = "",
        featureName = analyte.primaryFeature,
        rangeSource = if (finiteValues.isEmpty()) {
            ArrayHeatmapRangeSource.EMPTY_FALLBACK
        } else {
            ArrayHeatmapRangeSource.OBSERVED_VALUES
        }
    )
}

private fun normalizeHeatmapValue(value: Double, scale: ArrayHeatmapScale): Float {
    if (!value.isFinite()) return 0.5f
    if (scale.maximum <= scale.minimum) return 0.5f
    return ((value - scale.minimum) / (scale.maximum - scale.minimum))
        .coerceIn(0.0, 1.0)
        .toFloat()
}

private fun ArrayHeatmapValueInput.toQcEncoding(
    hardFailure: Boolean,
    displayValue: Double?
): ArrayHeatmapQcEncoding {
    val qualityLevel = resolveArrayMeasurementQuality(
        displayValue = displayValue,
        qualityReliable = qualityReliable,
        saturationRatio = saturationRatio,
        roiClipRatio = roiClipRatio,
        annulusClipRatio = annulusClipRatio
    )
    return ArrayHeatmapQcEncoding(
        // 范围、低信号和普通 flags 都有自己的视觉编码，不能重复计入“质量复核”。
        warning = !hardFailure && qualityLevel == ArrayMeasurementQualityLevel.REVIEW,
        failure = hardFailure,
        lowSignal = !hardFailure && (
            !signalDetectable || photometryFlags.any {
                it.equals("LOW_SNR", ignoreCase = true) || it.equals("low_snr", ignoreCase = true)
            }
        )
    )
}

/**
 * 将冻结结果解释成热力图专用的数值状态。
 *
 * 项目量程外没有精确浓度时仍保留方向状态，并把显示值投影到当前色带端点；方向符号负责
 * 明确它只是边界结果。信号热力图忽略浓度范围标志，防止旧运行字段污染当前信号色带。
 */
private fun ArrayHeatmapValueInput.resolveValueState(
    useConcentration: Boolean,
    rawValue: Double?
): ArrayHeatmapValueState {
    if (!hasMeasurement) return ArrayHeatmapValueState.UNAVAILABLE
    if (!useConcentration) {
        return if (rawValue?.isFinite() == true) {
            ArrayHeatmapValueState.QUANTIFIED
        } else {
            ArrayHeatmapValueState.UNAVAILABLE
        }
    }
    when (quantificationState?.uppercase()) {
        "UNAVAILABLE" -> return ArrayHeatmapValueState.UNAVAILABLE
        "ESTIMATED" -> return if (rawValue?.isFinite() == true) {
            ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED
        } else {
            ArrayHeatmapValueState.UNAVAILABLE
        }
        "QUANTIFIED" -> return if (rawValue?.isFinite() == true) {
            ArrayHeatmapValueState.QUANTIFIED
        } else {
            ArrayHeatmapValueState.UNAVAILABLE
        }
        "BOUND_ONLY" -> return when {
            reliableRangeStatus?.uppercase() in setOf(
                "BELOW_PROJECT_RANGE",
                "BELOW_TRUSTED_RANGE",
                "BELOW_RANGE"
            ) || censoringDirection.equals("UPPER_BOUND", ignoreCase = true) ->
                ArrayHeatmapValueState.BELOW_PROJECT_RANGE
            else -> ArrayHeatmapValueState.ABOVE_PROJECT_RANGE
        }
    }
    return when (reliableRangeStatus?.uppercase()) {
        "BELOW_PROJECT_RANGE", "BELOW_TRUSTED_RANGE" ->
            ArrayHeatmapValueState.BELOW_PROJECT_RANGE
        "ABOVE_PROJECT_RANGE", "ABOVE_TRUSTED_RANGE" ->
            ArrayHeatmapValueState.ABOVE_PROJECT_RANGE
        "BELOW_RANGE", "ABOVE_RANGE" -> if (rawValue?.isFinite() == true) {
            ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED
        } else {
            ArrayHeatmapValueState.UNAVAILABLE
        }
        else -> if (rawValue?.isFinite() == true) {
            ArrayHeatmapValueState.QUANTIFIED
        } else {
            ArrayHeatmapValueState.UNAVAILABLE
        }
    }
}

/**
 * 只有确实无法解释的数值才进入“不可用”。
 *
 * 轻度和严重成像风险都保留已经得到的有限结果供用户复核；单侧界限由色带端点投影和方向
 * 短线共同表达。只有当前显示维度确实没有有限值时才显示为无法计算。
 */
private fun ArrayHeatmapValueInput.isHardFailure(displayValue: Double?): Boolean {
    return resolveArrayMeasurementQuality(
        displayValue = displayValue,
        qualityReliable = qualityReliable,
        saturationRatio = saturationRatio,
        roiClipRatio = roiClipRatio,
        annulusClipRatio = annulusClipRatio
    ) == ArrayMeasurementQualityLevel.UNAVAILABLE
}

private fun ArrayPhysicalSiteResult.toHeatmapInput(
    measurement: ArraySiteMeasurementResult?,
    applicable: Boolean = true
): ArrayHeatmapValueInput {
    return ArrayHeatmapValueInput(
        siteIndex = siteIndex,
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        siteKey = siteKey,
        enabled = enabled,
        roleCode = roleCode,
        applicable = applicable,
        hasMeasurement = measurement != null,
        concentrationValue = measurement?.concentrationValue,
        primaryFeatureValue = measurement?.primaryFeatureValue,
        reliableRangeStatus = measurement?.reliableRangeStatus,
        signalDetectable = measurement?.signalDetectable ?: true,
        qualityReliable = measurement?.qualityReliable ?: true,
        geometryFlags = geometry.flags.mapTo(mutableSetOf()) { it.name } +
            measurement?.qc?.geometryFlags.orEmpty(),
        photometryFlags = measurement?.qc?.photometryFlags.orEmpty(),
        quantificationStatus = measurement?.qc?.quantificationStatus,
        quantificationState = measurement?.quantificationState,
        censoringDirection = measurement?.censoringDirection,
        saturationRatio = measurement?.detail?.basePhotometryOrNull()?.saturationRatio,
        roiClipRatio = measurement?.detail?.basePhotometryOrNull()?.roiClipRatio,
        annulusClipRatio = measurement?.detail?.basePhotometryOrNull()?.annulusClipRatio
    )
}

/** 不同模态共享基础光度质量字段，结果页只在这里做一次安全拆包。 */
private fun com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail.basePhotometryOrNull() =
    when (this) {
        is com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail.Colorimetric -> site.base
        is com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail.Fluorescence -> site.base
        is com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail.ColorimetricReference -> site
        is com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail.LegacyUnparsed -> null
    }

private fun buildHeatmapModel(
    rows: Int,
    columns: Int,
    analyteId: String?,
    scale: ArrayHeatmapScale,
    cells: List<ArrayHeatmapCell>,
    historicalConcentrationIncomplete: Boolean = false
): ArrayHeatmapModel {
    // 当前分析物的三类统计只计算适用位点；其他分析物或全局参考孔不能混入分母。
    val measuredCells = cells.filter { it.hasMeasurement && it.applicable }
    val quantifiedCount = measuredCells.count {
        it.valueState == ArrayHeatmapValueState.QUANTIFIED
    }
    val estimatedCount = measuredCells.count {
        it.valueState == ArrayHeatmapValueState.CALIBRATION_EXTRAPOLATED
    }
    val retestCount = measuredCells.count {
        it.valueState == ArrayHeatmapValueState.BELOW_PROJECT_RANGE ||
            it.valueState == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE ||
            it.valueState == ArrayHeatmapValueState.UNAVAILABLE
    }
    return ArrayHeatmapModel(
        rows = rows,
        columns = columns,
        analyteId = analyteId,
        scale = scale,
        cells = cells,
        measuredCount = measuredCells.size,
        calculatedCount = quantifiedCount + estimatedCount,
        quantifiedCount = quantifiedCount,
        estimatedCount = estimatedCount,
        retestCount = retestCount,
        withinCalibrationRangeCount = quantifiedCount,
        calibrationExtrapolatedCount = estimatedCount,
        outsideProjectRangeCount = measuredCells.count { cell ->
            // 绘图状态会把可信边界和严重饱和的单侧界限也编码成上下方向；只有原始机器
            // 状态明确写为项目量程外，才允许进入“超出项目量程”统计。
            cell.reliableRangeStatus?.uppercase() in setOf(
                "BELOW_PROJECT_RANGE",
                "ABOVE_PROJECT_RANGE"
            )
        },
        reliableCount = measuredCells.count {
            !it.qc.failure && !it.qc.warning
        },
        warningCount = measuredCells.count { !it.qc.failure && it.qc.warning },
        failureCount = measuredCells.count { it.qc.failure },
        lowSignalCount = measuredCells.count { it.qc.lowSignal },
        missingCount = cells.count { it.enabled && it.applicable && !it.hasMeasurement },
        roleCounts = cells.mapNotNull(ArrayHeatmapCell::roleCode).groupingBy { it }.eachCount(),
        historicalConcentrationIncomplete = historicalConcentrationIncomplete
    )
}

private fun emptyHeatmapCell(
    siteIndex: Int,
    rowIndex: Int,
    columnIndex: Int
): ArrayHeatmapCell {
    return ArrayHeatmapCell(
        siteIndex = siteIndex,
        rowIndex = rowIndex,
        columnIndex = columnIndex,
        siteKey = "R${(rowIndex + 1).toString().padStart(2, '0')}C${(columnIndex + 1).toString().padStart(2, '0')}",
        enabled = false,
        roleCode = null,
        applicable = false,
        hasMeasurement = false,
        displayValue = null,
        normalizedValue = null,
        valueState = ArrayHeatmapValueState.UNAVAILABLE,
        qc = ArrayHeatmapQcEncoding(false, false, false)
    )
}
