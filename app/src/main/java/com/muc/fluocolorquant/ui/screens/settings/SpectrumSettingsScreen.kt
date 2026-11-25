package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val toastManager = LocalToastManager.current
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val invalidWavelengthMsg = stringResource(R.string.error_invalid_wavelength)
    val numberRegex = remember { Regex("^[0-9]*\\.?[0-9]*$") }
    val successMsg = stringResource(R.string.settings_update_success)

    var minText by remember { mutableStateOf(uiState.spectrumMinWavelength.toString()) }
    var maxText by remember { mutableStateOf(uiState.spectrumMaxWavelength.toString()) }
    var smoothingValue by remember { mutableStateOf(uiState.spectrumSmoothing.toFloat()) }
    var showLightSourcePicker by remember { mutableStateOf(false) }

    // 同步 State -> 文本
    LaunchedEffect(uiState.spectrumMinWavelength, uiState.spectrumMaxWavelength) {
        minText = uiState.spectrumMinWavelength.toString()
        maxText = uiState.spectrumMaxWavelength.toString()
    }
    LaunchedEffect(uiState.spectrumSmoothing) {
        smoothingValue = uiState.spectrumSmoothing.toFloat()
    }

    fun saveWavelengths(): Boolean {
        val min = minText.toFloatOrNull()
        val max = maxText.toFloatOrNull()
        if (min == null || max == null || max <= min) {
            toastManager.showToast(invalidWavelengthMsg, ToastType.ERROR)
            return false
        }
        viewModel.updateWavelengthRange(min, max)
        return true
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.pref_spectrum_settings),
                onBack = { navController.navigateUp() }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 波长范围配置
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.spectrum_config_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.label_wavelength_range),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(numberRegex)) {
                                    minText = value
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveWavelengths()
                                },
                            singleLine = true,
                            label = { Text(stringResource(R.string.label_min_wavelength)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                saveWavelengths()
                            })
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(numberRegex)) {
                                    maxText = value
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveWavelengths()
                                },
                            singleLine = true,
                            label = { Text(stringResource(R.string.label_max_wavelength)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                saveWavelengths()
                            })
                        )
                    }
                }
            }

            // 默认光源只用于新建光谱项目预填。这里的选择不会修改任何已有项目，也不会
            // 作为光谱校正参数进入算法。
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.spectrum_default_light_source_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    ScientificSelectionField(
                        label = null,
                        value = stringResource(uiState.spectrumDefaultLightSource.displayNameRes),
                        placeholder = stringResource(R.string.spectrum_light_source_label),
                        icon = Icons.Filled.Flare,
                        supportingValue = stringResource(
                            R.string.spectrum_default_light_source_help
                        ),
                        onClick = { showLightSourcePicker = true }
                    )
                }
            }

            // 平滑度
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_smoothing_level, smoothingValue.roundToInt()),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = smoothingValue,
                        onValueChange = { smoothingValue = it },
                        valueRange = 0f..10f,
                        steps = 9,
                        onValueChangeFinished = {
                            viewModel.updateSmoothing(smoothingValue.roundToInt())
                        }
                    )
                }
            }

            // 灵敏度：使用等宽、等高的仪器式分段卡，避免长说明把“高”档挤成三行。
            // 图标只辅助快速识别，低/中/高文字与单选语义仍完整保留，不能只靠颜色表达状态。
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.label_sensitivity),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SensitivityOption(
                            title = stringResource(R.string.sensitivity_level_low),
                            supportingText = stringResource(R.string.sensitivity_hint_noise),
                            icon = Icons.Filled.FilterAlt,
                            selected = uiState.spectrumSensitivity.equals("Low", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("Low") },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(SpectrumSettingsTestTags.SENSITIVITY_LOW)
                        )
                        SensitivityOption(
                            title = stringResource(R.string.sensitivity_level_medium),
                            supportingText = stringResource(R.string.sensitivity_hint_balanced),
                            icon = Icons.Filled.Tune,
                            selected = uiState.spectrumSensitivity.equals("Medium", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("Medium") },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(SpectrumSettingsTestTags.SENSITIVITY_MEDIUM)
                        )
                        SensitivityOption(
                            title = stringResource(R.string.sensitivity_level_high),
                            supportingText = stringResource(R.string.sensitivity_hint_weak_peak),
                            icon = Icons.Outlined.Insights,
                            selected = uiState.spectrumSensitivity.equals("High", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("High") },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag(SpectrumSettingsTestTags.SENSITIVITY_HIGH)
                        )
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.spectrum_quality_check_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.spectrum_quality_check_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = uiState.spectrumQualityCheckEnabled,
                        onCheckedChange = viewModel::setSpectrumQualityCheckEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 保存按钮（参考 DetectionSettingsScreen 样式）
            Button(
                onClick = {
                    // 只有全部输入通过校验并实际提交后才显示成功，不能让错误 Toast 后
                    // 紧跟一个“保存成功”，否则用户无法判断生产配置是否真的生效。
                    if (saveWavelengths()) {
                        viewModel.updateSmoothing(smoothingValue.roundToInt())
                        toastManager.showToast(
                            message = successMsg,
                            type = ToastType.SUCCESS
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.save))
            }

            TextButton(
                onClick = { viewModel.resetSpectrumDefaults() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Filled.Restore, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.btn_reset_defaults))
            }
        }
    }

    if (showLightSourcePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.spectrum_default_light_source_title),
            options = SpectrumLightSource.entries.map { lightSource ->
                ScientificPickerOption(
                    id = lightSource.name,
                    title = stringResource(lightSource.displayNameRes),
                    icon = Icons.Filled.Flare
                )
            },
            selectedId = uiState.spectrumDefaultLightSource.name,
            onSelect = { selectedName ->
                SpectrumLightSource.entries.firstOrNull { it.name == selectedName }?.let {
                    viewModel.updateSpectrumDefaultLightSource(it)
                    toastManager.showToast(successMsg, ToastType.SUCCESS)
                }
            },
            onDismiss = { showLightSourcePicker = false }
        )
    }
}

@Composable
private fun SensitivityOption(
    title: String,
    supportingText: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "sensitivityContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        label = "sensitivityBorder"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "sensitivityContent"
    )

    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            ),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else contentColor
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.82f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 稳定语义标签，供 360dp、大字体和交互回归定位三档灵敏度选项。 */
internal object SpectrumSettingsTestTags {
    const val SENSITIVITY_LOW = "spectrum_sensitivity_low"
    const val SENSITIVITY_MEDIUM = "spectrum_sensitivity_medium"
    const val SENSITIVITY_HIGH = "spectrum_sensitivity_high"
}
