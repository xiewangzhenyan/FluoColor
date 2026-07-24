@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.CalibrationStrategy
import com.muc.fluocolorquant.domain.calibration.LowQualityCalibrationAction
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.viewmodels.CalibrationSettingsViewModel

/**
 * 曲线拟合设置页。
 *
 * 页面采用“常用决策在前、专业阈值在后”的科研工具布局；所有选择均通过受限控件完成，
 * 不提供自由 JSON、函数名或无范围数字输入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationSettingsScreen(
    navController: NavController,
    viewModel: CalibrationSettingsViewModel = hiltViewModel()
) {
    val policy by viewModel.policy.collectAsState()
    val toastManager = LocalToastManager.current
    val resetMessage = stringResource(R.string.calibration_settings_reset_success)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.calibration_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = navController::navigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_strategy_title),
                    icon = Icons.Default.AutoGraph
                ) {
                    StrategySelector(
                        selected = policy.strategy,
                        onSelected = viewModel::setStrategy
                    )
                }
            }

            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_candidates_title),
                    icon = Icons.Default.Functions
                ) {
                    FunctionSelector(
                        selected = policy.allowedFunctions,
                        onToggle = viewModel::toggleFunction
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ThresholdSlider(
                        policy = policy,
                        onValueChange = viewModel::setSimplicityTolerance
                    )
                    LevelStepper(
                        label = stringResource(R.string.calibration_settings_4pl_levels),
                        value = policy.minimumFourParameterLevels,
                        canDecrease = policy.minimumFourParameterLevels > 5,
                        onDecrease = { viewModel.changeMinimumFourParameterLevels(-1) },
                        onIncrease = { viewModel.changeMinimumFourParameterLevels(1) }
                    )
                    LevelStepper(
                        label = stringResource(R.string.calibration_settings_5pl_levels),
                        value = policy.minimumFiveParameterLevels,
                        canDecrease = policy.minimumFiveParameterLevels > 6,
                        onDecrease = { viewModel.changeMinimumFiveParameterLevels(-1) },
                        onIncrease = { viewModel.changeMinimumFiveParameterLevels(1) }
                    )
                }
            }

            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_quality_title),
                    icon = Icons.Default.Tune
                ) {
                    LowQualityRSquaredSlider(
                        value = policy.lowQualityRSquaredThreshold,
                        onValueChange = viewModel::setLowQualityRSquaredThreshold
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    LowQualitySelector(
                        selected = policy.lowQualityAction,
                        onSelected = viewModel::setLowQualityAction
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.calibration_settings_save_default),
                        checked = policy.saveToLibraryByDefault,
                        onCheckedChange = viewModel::setSaveToLibraryByDefault
                    )
                }
            }

            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_color_features),
                    icon = Icons.Default.ColorLens
                ) {
                    FeatureSelector(
                        features = CalibrationPolicy.DEFAULT_COLORIMETRIC_FEATURES,
                        selected = policy.colorimetricFeatures,
                        onToggle = viewModel::toggleColorimetricFeature
                    )
                }
            }

            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_fluorescence_features),
                    icon = Icons.Default.Science
                ) {
                    FeatureSelector(
                        features = CalibrationPolicy.DEFAULT_FLUORESCENCE_FEATURES,
                        selected = policy.fluorescenceFeatures,
                        onToggle = viewModel::toggleFluorescenceFeature
                    )
                }
            }

            item {
                CalibrationSettingsSection(
                    title = stringResource(R.string.calibration_settings_weighting_title),
                    icon = Icons.Default.Tune
                ) {
                    WeightingSelector(
                        selected = policy.enabledWeightingCodes,
                        onToggle = viewModel::toggleWeighting
                    )
                }
            }

            item { CalibrationRulePreview(policy = policy) }

            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.reset()
                        toastManager.showToast(resetMessage, ToastType.SUCCESS)
                    }
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.calibration_settings_reset))
                }
            }
        }
    }
}

/** 统一设置分组外观，减少整页卡片风格漂移。 */
@Composable
private fun CalibrationSettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            content()
        }
    }
}

@Composable
private fun StrategySelector(
    selected: CalibrationStrategy,
    onSelected: (CalibrationStrategy) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CalibrationStrategy.entries.forEach { strategy ->
            val title = when (strategy) {
                CalibrationStrategy.ROBUST -> stringResource(R.string.calibration_strategy_robust)
                CalibrationStrategy.R_SQUARED_FIRST ->
                    stringResource(R.string.calibration_strategy_r2_first)
                CalibrationStrategy.SIMPLE_MODEL_FIRST ->
                    stringResource(R.string.calibration_strategy_simple_first)
            }
            val description = when (strategy) {
                CalibrationStrategy.ROBUST ->
                    stringResource(R.string.calibration_strategy_robust_desc)
                CalibrationStrategy.R_SQUARED_FIRST ->
                    stringResource(R.string.calibration_strategy_r2_first_desc)
                CalibrationStrategy.SIMPLE_MODEL_FIRST ->
                    stringResource(R.string.calibration_strategy_simple_first_desc)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelected(strategy) },
                shape = RoundedCornerShape(14.dp),
                color = if (selected == strategy) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FunctionSelector(
    selected: Set<FittingFunction>,
    onToggle: (FittingFunction) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CalibrationPolicy.DEFAULT_FUNCTIONS.forEach { function ->
            FilterChip(
                selected = function in selected,
                onClick = { onToggle(function) },
                label = { Text(functionLabel(function)) }
            )
        }
    }
}

@Composable
private fun ThresholdSlider(
    policy: CalibrationPolicy,
    onValueChange: (Double) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.calibration_settings_r2_tolerance),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(
                    R.string.calibration_settings_decimal_value,
                    policy.rSquaredSimplicityTolerance
                ),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = policy.rSquaredSimplicityTolerance.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = 0f..0.020f,
            steps = 19
        )
    }
}

/** 低质量R²门槛只控制推荐状态，不绕过参数有限、单调和可反算等数学硬条件。 */
@Composable
private fun LowQualityRSquaredSlider(
    value: Double,
    onValueChange: (Double) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.calibration_settings_low_quality_r2),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.calibration_settings_r2_value, value),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = 0.80f..0.999f,
            steps = 198
        )
        Text(
            text = stringResource(R.string.calibration_settings_low_quality_r2_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LevelStepper(
    label: String,
    value: Int,
    canDecrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrease, enabled = canDecrease) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(R.string.calibration_settings_decrease)
                )
            }
            Text(
                text = value.toString(),
                modifier = Modifier.padding(horizontal = 8.dp),
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onIncrease, enabled = value < 20) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.calibration_settings_increase)
                )
            }
        }
    }
}

@Composable
private fun LowQualitySelector(
    selected: LowQualityCalibrationAction,
    onSelected: (LowQualityCalibrationAction) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LowQualityCalibrationAction.entries.forEach { action ->
            FilterChip(
                selected = action == selected,
                onClick = { onSelected(action) },
                label = {
                    Text(
                        when (action) {
                            LowQualityCalibrationAction.WARN_AND_ALLOW ->
                                stringResource(R.string.calibration_quality_warn_allow)
                            LowQualityCalibrationAction.REQUIRE_CONFIRMATION ->
                                stringResource(R.string.calibration_quality_confirm)
                            LowQualityCalibrationAction.VIEW_ONLY ->
                                stringResource(R.string.calibration_quality_view_only)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FeatureSelector(
    features: Set<AnalysisPrimaryFeature>,
    selected: Set<AnalysisPrimaryFeature>,
    onToggle: (AnalysisPrimaryFeature) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        features.forEach { feature ->
            FilterChip(
                selected = feature in selected,
                onClick = { onToggle(feature) },
                label = { Text(analysisFeatureLabel(feature)) }
            )
        }
    }
}

@Composable
private fun WeightingSelector(
    selected: Set<Int>,
    onToggle: (Int) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0..3).forEach { code ->
            val label = when (code) {
                0 -> stringResource(R.string.calibration_weight_none)
                1 -> stringResource(R.string.calibration_weight_inverse_y)
                2 -> stringResource(R.string.calibration_weight_inverse_y2)
                else -> stringResource(R.string.calibration_weight_inverse_variance)
            }
            FilterChip(
                selected = code in selected,
                onClick = { onToggle(code) },
                label = { Text(label) }
            )
        }
    }
}

/** 使用固定示例直观展示当前规则，不读取也不保存用户实验数据。 */
@Composable
private fun CalibrationRulePreview(policy: CalibrationPolicy) {
    val recommended = previewRecommendation(policy)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.calibration_settings_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.calibration_settings_preview_metrics),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(
                    R.string.calibration_settings_preview_recommendation,
                    functionLabel(recommended)
                ),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 固定示例为线性0.9940、4PL 0.9994、5PL 0.9996。 */
private fun previewRecommendation(policy: CalibrationPolicy): FittingFunction {
    val rSquared = mapOf(
        FittingFunction.LINEAR to 0.9940,
        FittingFunction.RODBARD to 0.9994,
        FittingFunction.LOGISTIC to 0.9996
    )
    val available = CalibrationPolicy.DEFAULT_FUNCTIONS.filter { it in policy.allowedFunctions }
    if (available.isEmpty()) return FittingFunction.LINEAR
    return when (policy.strategy) {
        CalibrationStrategy.R_SQUARED_FIRST -> available.maxBy { rSquared.getValue(it) }
        CalibrationStrategy.SIMPLE_MODEL_FIRST -> available.minBy(::modelComplexity)
        CalibrationStrategy.ROBUST -> {
            val best = available.maxOf { rSquared.getValue(it) }
            available.filter { best - rSquared.getValue(it) <= policy.rSquaredSimplicityTolerance }
                .minBy(::modelComplexity)
        }
    }
}

private fun modelComplexity(function: FittingFunction): Int = when (function) {
    FittingFunction.LINEAR -> 2
    FittingFunction.RODBARD -> 4
    FittingFunction.LOGISTIC -> 5
    else -> function.requiredParams.size
}

@Composable
private fun functionLabel(function: FittingFunction): String = stringResource(
    when (function) {
        FittingFunction.LINEAR -> R.string.fitting_function_linear
        FittingFunction.RODBARD -> R.string.fitting_function_rodbard_4pl
        FittingFunction.LOGISTIC -> R.string.fitting_function_logistic_5pl
        else -> R.string.fitting_function_linear
    }
)
