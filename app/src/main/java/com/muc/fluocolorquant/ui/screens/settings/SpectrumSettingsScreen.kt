package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
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
    var defaultTrackText by remember { mutableStateOf(uiState.spectrumDefaultTrackCount.toString()) }
    var maxTrackText by remember { mutableStateOf(uiState.spectrumMaxTrackCount.toString()) }

    // 同步 State -> 文本
    LaunchedEffect(uiState.spectrumMinWavelength, uiState.spectrumMaxWavelength) {
        minText = uiState.spectrumMinWavelength.toString()
        maxText = uiState.spectrumMaxWavelength.toString()
    }
    LaunchedEffect(uiState.spectrumSmoothing) {
        smoothingValue = uiState.spectrumSmoothing.toFloat()
    }
    LaunchedEffect(uiState.spectrumDefaultTrackCount, uiState.spectrumMaxTrackCount) {
        defaultTrackText = uiState.spectrumDefaultTrackCount.toString()
        maxTrackText = uiState.spectrumMaxTrackCount.toString()
    }

    fun saveWavelengths() {
        val min = minText.toFloatOrNull()
        val max = maxText.toFloatOrNull()
        if (min == null || max == null || max <= min) {
            toastManager.showToast(invalidWavelengthMsg, ToastType.ERROR)
            return
        }
        viewModel.updateWavelengthRange(min, max)
    }

    fun saveTrackConfig() {
        val defaultTracks = defaultTrackText.toIntOrNull()
        val maxTracks = maxTrackText.toIntOrNull()
        if (defaultTracks == null || maxTracks == null || maxTracks <= 0 || defaultTracks <= 0 || defaultTracks > maxTracks) {
            toastManager.showToast(invalidWavelengthMsg, ToastType.ERROR)
            return
        }
        viewModel.updateTrackCountConfig(defaultTracks, maxTracks)
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

            // 通道数量配置
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_track_config),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = defaultTrackText,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(numberRegex)) {
                                    defaultTrackText = value
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveTrackConfig()
                                },
                            singleLine = true,
                            label = { Text(stringResource(R.string.label_default_tracks)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                saveTrackConfig()
                            })
                        )
                        OutlinedTextField(
                            value = maxTrackText,
                            onValueChange = { value ->
                                if (value.isEmpty() || value.matches(numberRegex)) {
                                    maxTrackText = value
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    if (!focusState.isFocused) saveTrackConfig()
                                },
                            singleLine = true,
                            label = { Text(stringResource(R.string.label_max_tracks)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus()
                                saveTrackConfig()
                            })
                        )
                    }
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

            // 灵敏度
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.label_sensitivity),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SensitivityChip(
                            label = stringResource(R.string.sensitivity_low),
                            selected = uiState.spectrumSensitivity.equals("Low", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("Low") }
                        )
                        SensitivityChip(
                            label = stringResource(R.string.sensitivity_medium),
                            selected = uiState.spectrumSensitivity.equals("Medium", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("Medium") }
                        )
                        SensitivityChip(
                            label = stringResource(R.string.sensitivity_high),
                            selected = uiState.spectrumSensitivity.equals("High", ignoreCase = true),
                            onClick = { viewModel.updateSensitivity("High") }
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
                    saveWavelengths()
                    saveTrackConfig()
                    viewModel.updateSmoothing(smoothingValue.roundToInt())
                    toastManager.showToast(
                        message = successMsg,
                        type = ToastType.SUCCESS
                    )
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
}

@Composable
private fun SensitivityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}
