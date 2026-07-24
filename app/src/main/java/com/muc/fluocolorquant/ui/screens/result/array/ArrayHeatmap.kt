package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import kotlin.math.max

const val ARRAY_HEATMAP_CELL_TAG_PREFIX: String = "array_heatmap_cell_"
const val ARRAY_HEATMAP_TRANSFORM_TAG: String = "array_heatmap_transform_container"
const val ARRAY_HEATMAP_ZOOM_TOGGLE_TAG: String = "array_heatmap_zoom_toggle"

/** 热力图底色所表达的科学量，QC 不得通过切换该枚举来改变底色含义。 */
enum class ArrayHeatmapScaleMode {
    /** 芯片总览只表达位点可靠性百分比，不混合不同分析物的浓度单位。 */
    OVERVIEW_RELIABILITY,

    /** 当前分析物存在可用浓度时，使用该分析物自己的可靠范围或观测范围。 */
    CONCENTRATION,

    /** 整个分析物只能保留信号时，改用当前模型声明的主特征。 */
    PRIMARY_FEATURE
}

/** 色带边界的来源会展示给用户，防止把观测范围误认为模型可靠范围。 */
enum class ArrayHeatmapRangeSource {
    FIXED_PERCENTAGE,
    RELIABLE_RANGE,
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
    val rangeSource: ArrayHeatmapRangeSource
)

/**
 * 位点质量采用并行通道编码。
 *
 * warning 用边框，failure 用斜纹，lowSignal 用独立圆点；三者均不会覆盖 normalizedValue，
 * 因此同一个科学值在不同 QC 状态下仍保持同一种底色。
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
    val photometryFlags: Set<String>
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
    val qc: ArrayHeatmapQcEncoding
)

/** 一张热力图及其统计摘要。 */
data class ArrayHeatmapModel(
    val rows: Int,
    val columns: Int,
    val analyteId: String?,
    val scale: ArrayHeatmapScale,
    val cells: List<ArrayHeatmapCell>,
    val measuredCount: Int,
    val reliableCount: Int,
    val warningCount: Int,
    val failureCount: Int,
    val lowSignalCount: Int,
    val missingCount: Int,
    val roleCounts: Map<String, Int>
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
    inputs: List<ArrayHeatmapValueInput>
): ArrayHeatmapModel {
    require(rows > 0 && columns > 0) { "阵列行列必须大于零" }
    require(inputs.map { it.siteIndex }.distinct().size == inputs.size) { "热力图输入包含重复位点" }
    require(inputs.all { it.siteIndex in 0 until rows * columns }) { "热力图输入位点越界" }
    require(inputs.all {
        it.rowIndex in 0 until rows &&
            it.columnIndex in 0 until columns &&
            resolveArraySiteIndex(it.rowIndex, it.columnIndex, columns) == it.siteIndex
    }) { "热力图输入的行列与线性索引不一致" }

    val finiteConcentrations = inputs.filter(ArrayHeatmapValueInput::hasMeasurement)
        .mapNotNull { it.concentrationValue?.takeIf(Double::isFinite) }
    val finiteFeatures = inputs.filter(ArrayHeatmapValueInput::hasMeasurement)
        .mapNotNull { it.primaryFeatureValue?.takeIf(Double::isFinite) }
    val useConcentration = finiteConcentrations.isNotEmpty()
    val scale = if (useConcentration) {
        buildConcentrationScale(analyte, finiteConcentrations)
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
            val value = if (!input.hasMeasurement) {
                null
            } else if (useConcentration) {
                input.concentrationValue?.takeIf(Double::isFinite)
            } else {
                input.primaryFeatureValue?.takeIf(Double::isFinite)
            }
            ArrayHeatmapCell(
                siteIndex = siteIndex,
                rowIndex = rowIndex,
                columnIndex = columnIndex,
                siteKey = input.siteKey,
                enabled = input.enabled,
                roleCode = input.roleCode,
                applicable = input.applicable,
                hasMeasurement = input.hasMeasurement,
                displayValue = value,
                normalizedValue = value?.let { normalizeHeatmapValue(it, scale) },
                qc = if (input.hasMeasurement) {
                    input.toQcEncoding()
                } else {
                    ArrayHeatmapQcEncoding(
                        warning = input.geometryFlags.isNotEmpty(),
                        failure = false,
                        lowSignal = false
                    )
                }
            )
        }
    }
    return buildHeatmapModel(
        rows = rows,
        columns = columns,
        analyteId = analyte.analyteId,
        scale = scale,
        cells = cells
    )
}

/** 从冻结运行快照提取当前分析物，不读取当前模板或模型库。 */
fun buildAnalyteHeatmapModel(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult
): ArrayHeatmapModel {
    val inputs = snapshot.sites.map { site ->
        val measurement = site.measurements.firstOrNull { it.analyteId == analyte.analyteId }
        site.toHeatmapInput(
            measurement = measurement,
            applicable = site.analyteId == analyte.analyteId
        )
    }
    return buildAnalyteHeatmapModel(snapshot.rows, snapshot.columns, analyte, inputs)
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
                    qc = ArrayHeatmapQcEncoding(false, false, false)
                )
            } else {
                val input = site.toHeatmapInput(measurement)
                val qc = input.toQcEncoding()
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
                .clip(RoundedCornerShape(16.dp))
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
                    .padding(gap / 2f)
            ) {
                repeat(model.rows) { rowIndex ->
                    Row(modifier = Modifier.weight(1f)) {
                        repeat(model.columns) { columnIndex ->
                            val siteIndex = resolveArraySiteIndex(rowIndex, columnIndex, model.columns)
                            ArrayHeatmapCellView(
                                cell = model.cells[siteIndex],
                                scaleMode = model.scale.mode,
                                showText = largestDimension <= 4,
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
                        interactionEnabled = !interactionEnabled
                        if (!interactionEnabled) {
                            // 退出交互模式时恢复完整阵列，避免下次进入仍停留在局部放大状态。
                            userScale = 1f
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
    scaleMode: ArrayHeatmapScaleMode,
    showText: Boolean,
    gap: androidx.compose.ui.unit.Dp,
    onClick: (ArrayHeatmapCell) -> Unit,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(if (showText) 10.dp else 3.dp)
    val baseColor = if (cell.enabled && cell.applicable) {
        arrayHeatmapBaseColor(cell.normalizedValue, scaleMode)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val warningColor = Color(0xFFF59E0B)
    val failureColor = MaterialTheme.colorScheme.error
    val lowSignalColor = Color(0xFF0288D1)
    val textColor = if (baseColor.luminance() < 0.42f) Color.White else Color(0xFF17212B)
    val noValueText = stringResource(R.string.array_heatmap_no_value_symbol)

    Box(
        modifier = modifier
            .padding(gap / 2f)
            .clip(shape)
            .background(baseColor)
            .then(
                if (cell.qc.warning) Modifier.border(1.5.dp, warningColor, shape)
                else Modifier
            )
            .clickable { onClick(cell) }
            .testTag("$ARRAY_HEATMAP_CELL_TAG_PREFIX${cell.siteIndex}"),
        contentAlignment = Alignment.Center
    ) {
        if (cell.qc.failure) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(failureColor.copy(alpha = 0.16f))
                val step = 8.dp.toPx()
                var startX = -size.height
                while (startX < size.width) {
                    drawLine(
                        color = failureColor.copy(alpha = 0.72f),
                        start = Offset(startX, 0f),
                        end = Offset(startX + size.height, size.height),
                        strokeWidth = 1.25.dp.toPx()
                    )
                    startX += step
                }
            }
        }
        if (cell.qc.lowSignal) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(if (showText) 5.dp else 1.dp)
                    .size(if (showText) 8.dp else 3.dp)
                    .background(lowSignalColor, CircleShape)
            )
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
                    text = cell.displayValue?.let { value ->
                        val formatted = formatArrayHeatmapValue(value)
                        if (scaleMode == ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY) {
                            stringResource(R.string.array_heatmap_cell_percent, formatted)
                        } else {
                            formatted
                        }
                    } ?: noValueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

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
    finiteValues: List<Double>
): ArrayHeatmapScale {
    val reliableMin = analyte.reliableRangeMin?.takeIf(Double::isFinite)
    val reliableMax = analyte.reliableRangeMax?.takeIf(Double::isFinite)
    val hasReliableRange = reliableMin != null && reliableMax != null && reliableMax > reliableMin
    val minimum = if (hasReliableRange) reliableMin!! else finiteValues.minOrNull() ?: 0.0
    val maximum = if (hasReliableRange) reliableMax!! else finiteValues.maxOrNull() ?: 1.0
    return ArrayHeatmapScale(
        mode = ArrayHeatmapScaleMode.CONCENTRATION,
        minimum = minimum,
        maximum = maximum,
        unit = analyte.concentrationUnit,
        featureName = null,
        rangeSource = if (hasReliableRange) {
            ArrayHeatmapRangeSource.RELIABLE_RANGE
        } else {
            ArrayHeatmapRangeSource.OBSERVED_VALUES
        }
    )
}

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

private fun ArrayHeatmapValueInput.toQcEncoding(): ArrayHeatmapQcEncoding {
    val rangeWarning = reliableRangeStatus != null &&
        !reliableRangeStatus.equals("WITHIN_RANGE", ignoreCase = true)
    val nonLowSignalPhotometryFlags = photometryFlags.filterNot {
        it.equals("LOW_SNR", ignoreCase = true) || it.equals("low_snr", ignoreCase = true)
    }
    return ArrayHeatmapQcEncoding(
        warning = rangeWarning || geometryFlags.isNotEmpty() || nonLowSignalPhotometryFlags.isNotEmpty(),
        failure = !qualityReliable,
        lowSignal = !signalDetectable || photometryFlags.any {
            it.equals("LOW_SNR", ignoreCase = true) || it.equals("low_snr", ignoreCase = true)
        }
    )
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
        photometryFlags = measurement?.qc?.photometryFlags.orEmpty()
    )
}

private fun buildHeatmapModel(
    rows: Int,
    columns: Int,
    analyteId: String?,
    scale: ArrayHeatmapScale,
    cells: List<ArrayHeatmapCell>
): ArrayHeatmapModel {
    val measuredCells = cells.filter(ArrayHeatmapCell::hasMeasurement)
    return ArrayHeatmapModel(
        rows = rows,
        columns = columns,
        analyteId = analyteId,
        scale = scale,
        cells = cells,
        measuredCount = measuredCells.size,
        reliableCount = measuredCells.count { !it.qc.failure && !it.qc.warning },
        warningCount = measuredCells.count { !it.qc.failure && it.qc.warning },
        failureCount = measuredCells.count { it.qc.failure },
        lowSignalCount = measuredCells.count { it.qc.lowSignal },
        missingCount = cells.count { it.enabled && it.applicable && !it.hasMeasurement },
        roleCounts = cells.mapNotNull(ArrayHeatmapCell::roleCode).groupingBy { it }.eachCount()
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
        qc = ArrayHeatmapQcEncoding(false, false, false)
    )
}
