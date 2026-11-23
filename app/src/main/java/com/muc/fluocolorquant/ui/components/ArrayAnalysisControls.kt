package com.muc.fluocolorquant.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ScatterPlot
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode
import com.muc.fluocolorquant.domain.detection.array.MultiHoleStatistic
import com.muc.fluocolorquant.domain.detection.photometry.WellReadoutMode
import kotlin.math.roundToInt

/** 新建页和设置页用同一语义图标，图标不承担没有文字的操作含义。 */
internal fun detectionModeIcon(modality: DetectionModality): ImageVector = when (modality) {
    DetectionModality.COLORIMETRIC -> Icons.Default.WaterDrop
    DetectionModality.FLUORESCENCE -> Icons.Default.Science
    DetectionModality.SPECTRUM -> Icons.Default.Sensors
}

@StringRes
internal fun ArrayLocatorMode.label(scope: ArrayAnalysisScope): Int = when (this) {
    ArrayLocatorMode.AUTO -> if (scope == ArrayAnalysisScope.STANDARD_PLATE) R.string.plate96_algorithm_auto else R.string.array_locator_pg
    ArrayLocatorMode.OBJECT_DETECTION -> R.string.plate96_algorithm_object
    ArrayLocatorMode.GEOMETRIC_SHAPE -> R.string.plate96_algorithm_circle
    ArrayLocatorMode.PROJECTION -> R.string.array_locator_projection
    ArrayLocatorMode.KMEANS -> R.string.array_locator_kmeans
    ArrayLocatorMode.SPOT_TOPOLOGY -> R.string.array_locator_spot_topology
    ArrayLocatorMode.CIRCLES_GRID -> R.string.array_locator_circles_grid
    ArrayLocatorMode.HOUGH_GRID -> R.string.array_locator_hough
    ArrayLocatorMode.ELLIPSE_GRID -> R.string.array_locator_ellipse
    ArrayLocatorMode.MANUAL_CORNERS -> R.string.array_locator_manual
    ArrayLocatorMode.DBSCAN -> R.string.array_locator_dbscan
    ArrayLocatorMode.LOG_SPOTS -> R.string.array_locator_log
}

/** 短名只改展示，不改变支持的载体、默认值、算法身份或历史追溯。 */
private fun ArrayLocatorMode.icon(): ImageVector = when (this) {
    ArrayLocatorMode.AUTO -> Icons.Outlined.AutoAwesome
    ArrayLocatorMode.OBJECT_DETECTION, ArrayLocatorMode.GEOMETRIC_SHAPE -> Icons.Outlined.CenterFocusStrong
    ArrayLocatorMode.PROJECTION -> Icons.Outlined.ViewColumn
    ArrayLocatorMode.KMEANS, ArrayLocatorMode.DBSCAN, ArrayLocatorMode.SPOT_TOPOLOGY, ArrayLocatorMode.LOG_SPOTS -> Icons.Outlined.ScatterPlot
    ArrayLocatorMode.CIRCLES_GRID -> Icons.Outlined.GridView
    ArrayLocatorMode.HOUGH_GRID, ArrayLocatorMode.ELLIPSE_GRID -> Icons.Outlined.RadioButtonUnchecked
    ArrayLocatorMode.MANUAL_CORNERS -> Icons.Outlined.Gesture
}

@StringRes
internal fun ArrayAnalysisScope.label(): Int = when (this) {
    ArrayAnalysisScope.CHIP -> R.string.array_analysis_chip
    ArrayAnalysisScope.STANDARD_PLATE -> R.string.array_analysis_plate96
    ArrayAnalysisScope.ROUND_ARRAY -> R.string.array_analysis_round
}

@StringRes
internal fun WellReadoutMode.label(): Int = when (this) {
    WellReadoutMode.FULL_MEDIAN -> R.string.array_readout_full_median
    WellReadoutMode.FULL_MEAN -> R.string.array_readout_full_mean
    WellReadoutMode.CENTER_MEDIAN -> R.string.array_readout_center_median
    WellReadoutMode.CENTER_MEAN -> R.string.array_readout_center_mean
    WellReadoutMode.FIVE_REGION_MEAN -> R.string.array_readout_five
    WellReadoutMode.CENTER_PIXEL -> R.string.array_readout_center_pixel
}

@StringRes
internal fun MultiHoleStatistic.label(): Int = when (this) {
    MultiHoleStatistic.INDIVIDUAL -> R.string.array_group_individual
    MultiHoleStatistic.MEAN -> R.string.array_group_mean
    MultiHoleStatistic.MEDIAN -> R.string.array_group_median
}

/** 默认值和本次选择用同一词汇和选项源；手动角点另外提供操作，不存成自动默认方法。 */
@Composable
internal fun ArrayLocatorPicker(
    scope: ArrayAnalysisScope,
    selected: ArrayLocatorMode,
    onSelected: (ArrayLocatorMode) -> Unit,
    modifier: Modifier = Modifier,
    includeComparisons: Boolean = false,
    enabled: Boolean = true
) = ArrayOptionField(
    titleRes = R.string.plate96_algorithm_title,
    options = if (includeComparisons) scope.localizationChoices else scope.algorithms,
    selected = selected,
    label = { it.label(scope) },
    icon = Icons.Outlined.CenterFocusStrong,
    onSelected = onSelected,
    modifier = modifier.testTag("array_locator_picker"),
    optionIcon = { it.icon() },
    compactOptions = true,
    enabled = enabled
)

@Composable
internal fun <Value : Enum<Value>> ArrayOptionField(
    @StringRes titleRes: Int,
    options: List<Value>,
    selected: Value,
    label: (Value) -> Int,
    icon: ImageVector,
    onSelected: (Value) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (Value) -> Int? = { null },
    optionIcon: (Value) -> ImageVector? = { null },
    compactOptions: Boolean = false,
    allowValueWrap: Boolean = false,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(titleRes)
    ScientificSelectionField(
        title, stringResource(label(selected)), title, icon,
        onClick = { expanded = true }, modifier = modifier, allowValueWrap = allowValueWrap, enabled = enabled
    )
    if (expanded) {
        ScientificPickerSheet(
            title = title,
            options = options.map { option ->
                ScientificPickerOption(id = option.name, title = stringResource(label(option)),
                    subtitle = subtitle(option)?.let { stringResource(it) }, icon = optionIcon(option))
            },
            selectedId = selected.name,
            onSelect = { id ->
                expanded = false
                options.firstOrNull { it.name == id }?.let(onSelected)
            },
            onDismiss = { expanded = false },
            modifier = Modifier.testTag("array_option_sheet"),
            compact = compactOptions
        )
    }
}

@Composable
internal fun ArrayPercentSlider(@StringRes labelRes: Int, value: Double, range: IntRange, enabled: Boolean = true, onChange: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf((value * 100).toFloat().coerceIn(range.first.toFloat(), range.last.toFloat())) }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes, draft.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(value = draft, onValueChange = { draft = it }, valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
            onValueChangeFinished = { onChange(draft.roundToInt() / 100.0) })
    }
}
