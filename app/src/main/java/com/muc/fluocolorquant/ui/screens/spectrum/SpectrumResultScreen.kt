package com.muc.fluocolorquant.ui.screens.spectrum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoIconBadge
import com.muc.fluocolorquant.ui.components.FluoMetricTile
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationIssue
import com.muc.fluocolorquant.data.model.SpectrumAutoCalibrationQualityLevel
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.charts.ChartData
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ExportViewModel
import com.muc.fluocolorquant.ui.viewmodels.SpectrumCalibrationComparisonUiModel
import com.muc.fluocolorquant.ui.viewmodels.SpectrumChannelUiModel
import com.muc.fluocolorquant.ui.viewmodels.SpectrumCurveMode
import com.muc.fluocolorquant.ui.viewmodels.SpectrumPeakUiModel
import com.muc.fluocolorquant.ui.viewmodels.SpectrumResultViewModel
import kotlinx.coroutines.launch
import java.util.Locale
import com.muc.fluocolorquant.ui.theme.FluoRadius

/**
 * 光谱分析结果展示页面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpectrumResultScreen(
    navController: NavHostController,
    projectId: String,
    viewModel: SpectrumResultViewModel = hiltViewModel(),
    exportViewModel: ExportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val exportState by exportViewModel.exportState.collectAsState()
    val toastManager = LocalToastManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    val pageBackgroundColor = colorScheme.background
    val secondaryTextColor = colorScheme.onSurfaceVariant
    val exportPrepareFailed = stringResource(R.string.spectrum_export_prepare_failed)
    var showQuickSettings by remember(projectId) { mutableStateOf(false) }
    var showComparison by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<SpectrumChannelUiModel?>(null) }
    var pendingSmoothing by remember(state.smoothingLevel) { mutableStateOf(state.smoothingLevel.toFloat()) }
    var showExportSheet by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        if (projectId.isNotBlank()) {
            viewModel.loadProjectResults(projectId)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            toastManager.showToast(it.asString(context), ToastType.ERROR)
            viewModel.clearErrorMessage()
        }
    }

    LaunchedEffect(exportState) {
        when (val currentExportState = exportState) {
            is ExportViewModel.ExportState.Success -> {
                toastManager.showToast(currentExportState.message, ToastType.SUCCESS)
                exportViewModel.resetExportState()
            }
            is ExportViewModel.ExportState.Error -> {
                toastManager.showToast(currentExportState.message, ToastType.ERROR)
                exportViewModel.resetExportState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.spectrum_result_title),
                onBack = { navController.navigateUp() },
                actions = {
                    if (state.results.isNotEmpty()) {
                        IconButton(onClick = { showQuickSettings = !showQuickSettings }) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = stringResource(R.string.spectrum_quick_settings_title)
                            )
                        }
                        IconButton(onClick = { showExportSheet = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.export),
                                contentDescription = stringResource(R.string.spectrum_export_title),
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = accentColor)
                }
            }

            state.results.isEmpty() -> {
                SpectrumEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onReturnCalibration = {
                        val imageUri = state.projectImageUri
                        if (!imageUri.isNullOrBlank()) {
                            navController.navigate(Screen.SpectrumCalibration.createRoute(projectId, imageUri))
                        } else {
                            navController.navigateUp()
                        }
                    }
                )
            }

            else -> {
                val pagerState = rememberPagerState(pageCount = { state.results.size })

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .background(pageBackgroundColor)
                ) {
                    if (showQuickSettings) {
                        QuickAdjustCard(
                            pendingSmoothing = pendingSmoothing,
                            sensitivity = state.sensitivity,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            onSmoothingChange = { pendingSmoothing = it },
                            onSmoothingChangeFinished = {
                                viewModel.updateSmoothing(pendingSmoothing.toInt())
                            },
                            onSensitivityChange = viewModel::updateSensitivity
                        )
                    }

                    if (state.comparisonChartData != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            FilterChip(
                                selected = showComparison,
                                onClick = { showComparison = !showComparison },
                                label = { Text(stringResource(R.string.spectrum_compare_toggle)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }

                    if (showComparison && state.comparisonChartData != null) {
                        SpectrumComparisonCard(
                            chartData = state.comparisonChartData,
                            channels = state.results,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(
                                R.string.spectrum_channel_format,
                                pagerState.currentPage + 1,
                                state.results.size
                            ),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(state.results.size) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(if (index == pagerState.currentPage) 12.dp else 9.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (index == pagerState.currentPage) accentColor
                                            else secondaryTextColor.copy(alpha = 0.28f)
                                        )
                                        .clickable {
                                            scope.launch { pagerState.animateScrollToPage(index) }
                                        }
                                )
                            }
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f)
                    ) { page ->
                        ChannelResultPage(
                            channelData = state.results[page],
                            curveMode = state.curveMode,
                            modifier = Modifier.fillMaxSize(),
                            onEditAnalyte = { editingChannel = state.results[page] },
                            onCurveModeChange = viewModel::setCurveMode
                        )
                    }
                }
            }
        }
    }

    if (showExportSheet) {
        SpectrumExportBottomSheet(
            onDismiss = { showExportSheet = false },
            onCsvExport = {
                viewModel.getExportData()?.let { data ->
                    exportViewModel.startSpectrumCsvExport(data)
                } ?: toastManager.showToast(exportPrepareFailed, ToastType.ERROR)
            },
            onPngExport = { isMerged ->
                viewModel.getExportData()?.let { data ->
                    exportViewModel.startSpectrumPngExport(data, isMerged)
                } ?: toastManager.showToast(exportPrepareFailed, ToastType.ERROR)
            },
            onPdfExport = {
                viewModel.getExportData()?.let { data ->
                    exportViewModel.startSpectrumPdfExport(data)
                } ?: toastManager.showToast(exportPrepareFailed, ToastType.ERROR)
            }
        )
    }

    if (exportState is ExportViewModel.ExportState.InProgress) {
        ExportProgressDialog()
    }

    editingChannel?.let { channel ->
        AnalyteBindingDialog(
            availableAnalytes = state.availableAnalytes,
            currentAnalyteId = channel.analyteId,
            onDismiss = { editingChannel = null },
            onConfirm = { analyteId ->
                viewModel.updateChannelAnalyte(channel.resultId, channel.channelIndex, analyteId)
                editingChannel = null
            }
        )
    }
}

/**
 * 快速调参卡片。
 */
@Composable
private fun QuickAdjustCard(
    pendingSmoothing: Float,
    sensitivity: String,
    modifier: Modifier = Modifier,
    onSmoothingChange: (Float) -> Unit,
    onSmoothingChangeFinished: () -> Unit,
    onSensitivityChange: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.outlinedCardColors(containerColor = colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = accentColor
                )
                Text(
                    text = stringResource(R.string.spectrum_quick_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = stringResource(R.string.label_smoothing_level, pendingSmoothing.toInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Slider(
                value = pendingSmoothing,
                onValueChange = onSmoothingChange,
                valueRange = 0f..10f,
                steps = 9,
                onValueChangeFinished = onSmoothingChangeFinished
            )

            Text(
                text = stringResource(R.string.label_sensitivity),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SensitivityFilterChip(
                    label = stringResource(R.string.sensitivity_low),
                    selected = sensitivity.equals("Low", ignoreCase = true),
                    onClick = { onSensitivityChange("Low") }
                )
                SensitivityFilterChip(
                    label = stringResource(R.string.sensitivity_medium),
                    selected = sensitivity.equals("Medium", ignoreCase = true),
                    onClick = { onSensitivityChange("Medium") }
                )
                SensitivityFilterChip(
                    label = stringResource(R.string.sensitivity_high),
                    selected = sensitivity.equals("High", ignoreCase = true),
                    onClick = { onSensitivityChange("High") }
                )
            }
        }
    }
}

/**
 * 空状态页面。
 */
@Composable
private fun SpectrumEmptyState(
    modifier: Modifier = Modifier,
    onReturnCalibration: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }

            Text(
                text = stringResource(R.string.spectrum_no_data),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = stringResource(R.string.spectrum_no_data_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Button(onClick = onReturnCalibration) {
                Text(stringResource(R.string.spectrum_return_calibration))
            }
        }
    }
}

/**
 * 单个通道的结果页面。
 */
@Composable
private fun ChannelResultPage(
    channelData: SpectrumChannelUiModel,
    curveMode: SpectrumCurveMode,
    modifier: Modifier = Modifier,
    onEditAnalyte: () -> Unit,
    onCurveModeChange: (SpectrumCurveMode) -> Unit
) {
    val activeChartData = channelData.resolveChartData(curveMode)
    val activePeaks = channelData.resolvePeaks(curveMode)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AnalyteTitleCard(
            analyteName = channelData.analyteName,
            channelIndex = channelData.channelIndex,
            croppedImagePath = channelData.croppedImagePath,
            onEditClick = onEditAnalyte,
            modifier = Modifier.fillMaxWidth()
        )

        channelData.calibrationComparison?.let { comparison ->
            CalibrationComparisonCard(
                comparison = comparison,
                spectrumImagePath = channelData.croppedImagePath,
                channelIndex = channelData.channelIndex,
                modifier = Modifier.fillMaxWidth()
            )
        }

        SpectrumCurveCard(
            chartData = activeChartData,
            curveMode = curveMode,
            modifier = Modifier.fillMaxWidth(),
            onCurveModeChange = onCurveModeChange
        )

        PeakInfoCard(
            peaks = activePeaks,
            curveMode = curveMode,
            dataPointCount = channelData.dataPointCount,
            modifier = Modifier.fillMaxWidth()
        )

        DataRangeCard(
            minWavelength = channelData.minWavelength,
            maxWavelength = channelData.maxWavelength,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 分析物标题卡片。
 */
@Composable
private fun AnalyteTitleCard(
    analyteName: String,
    channelIndex: Int,
    croppedImagePath: String?,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val unboundText = stringResource(R.string.spectrum_unbound)
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(FluoRadius.control))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                if (!croppedImagePath.isNullOrBlank()) {
                    AsyncImage(
                        model = croppedImagePath,
                        contentDescription = stringResource(R.string.spectrum_thumbnail_desc, channelIndex),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.spectrum_analyte_label),
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = analyteName,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (analyteName == unboundText) colorScheme.onSurfaceVariant else colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.spectrum_channel_label, channelIndex),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.spectrum_edit_binding),
                    tint = colorScheme.primary
                )
            }
        }
    }
}

/**
 * 标定图与光谱图的对照卡片。
 */
@Composable
private fun CalibrationComparisonCard(
    comparison: SpectrumCalibrationComparisonUiModel,
    spectrumImagePath: String?,
    channelIndex: Int,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val qualityScore = comparison.qualityScore
    val qualityLabel = when (comparison.qualityLevel) {
        SpectrumAutoCalibrationQualityLevel.EXCELLENT ->
            stringResource(R.string.spectrum_calibration_quality_excellent)
        SpectrumAutoCalibrationQualityLevel.USABLE ->
            stringResource(R.string.spectrum_calibration_quality_usable)
        SpectrumAutoCalibrationQualityLevel.REVIEW ->
            stringResource(R.string.spectrum_calibration_quality_review)
        null -> when {
            qualityScore == null -> stringResource(R.string.spectrum_calibration_quality_pending)
            qualityScore >= 85 -> stringResource(R.string.spectrum_calibration_quality_excellent)
            qualityScore >= 70 -> stringResource(R.string.spectrum_calibration_quality_usable)
            else -> stringResource(R.string.spectrum_calibration_quality_review)
        }
    }
    val issueLabels = comparison.issues.map { issue ->
        when (issue) {
            SpectrumAutoCalibrationIssue.FIT_RMSE_HIGH ->
                stringResource(R.string.spectrum_calibration_issue_fit_rmse_high)
            SpectrumAutoCalibrationIssue.EFFECTIVE_HEIGHT_LOW ->
                stringResource(R.string.spectrum_calibration_issue_effective_height_low)
            SpectrumAutoCalibrationIssue.EFFECTIVE_HEIGHT_HIGH ->
                stringResource(R.string.spectrum_calibration_issue_effective_height_high)
            SpectrumAutoCalibrationIssue.FALLBACK_ALIGNMENT ->
                stringResource(R.string.spectrum_calibration_issue_fallback_alignment)
            SpectrumAutoCalibrationIssue.IMAGE_QUALITY_WARNING ->
                stringResource(R.string.spectrum_calibration_issue_image_quality_warning)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FluoIconBadge(
                    icon = Icons.Default.Science,
                    accentColor = colorScheme.secondary
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.spectrum_calibration_compare_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.spectrum_calibration_compare_subtitle, channelIndex),
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = qualityScore?.toString() ?: "--",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = colorScheme.secondary
                    )
                    Text(
                        text = qualityLabel,
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ComparisonImagePanel(
                    title = stringResource(R.string.spectrum_calibration_compare_reference),
                    imagePath = comparison.calibrationImagePath,
                    fallbackLabel = stringResource(R.string.spectrum_calibration_compare_missing),
                    modifier = Modifier.weight(1f)
                )
                ComparisonImagePanel(
                    title = stringResource(R.string.spectrum_calibration_compare_spectrum),
                    imagePath = spectrumImagePath,
                    fallbackLabel = stringResource(R.string.spectrum_calibration_compare_missing),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoItem(
                    label = stringResource(R.string.spectrum_calibration_quality_score),
                    value = qualityScore?.let { stringResource(R.string.spectrum_calibration_quality_score_value, it) }
                        ?: "--",
                    modifier = Modifier.weight(1f)
                )
                InfoItem(
                    label = stringResource(R.string.spectrum_calibration_rmse),
                    value = comparison.fitRmse?.let { String.format(Locale.US, "%.4f", it) } ?: "--",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                InfoItem(
                    label = stringResource(R.string.spectrum_calibration_effective_height),
                    value = comparison.effectiveCoverage?.let {
                        stringResource(
                            R.string.spectrum_calibration_effective_height_value,
                            (it * 100.0).toInt()
                        )
                    } ?: "--",
                    modifier = Modifier.weight(1f)
                )
                InfoItem(
                    label = stringResource(R.string.spectrum_calibration_peak_match),
                    value = stringResource(
                        R.string.spectrum_calibration_peak_match_value,
                        comparison.detectedPeakCount,
                        comparison.referencePeakCount
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            if (comparison.meanAbsoluteResidual != null || comparison.maxResidual != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InfoItem(
                        label = stringResource(R.string.spectrum_calibration_mean_residual),
                        value = comparison.meanAbsoluteResidual?.let {
                            String.format(Locale.US, "%.4f", it)
                        } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    InfoItem(
                        label = stringResource(R.string.spectrum_calibration_max_residual),
                        value = comparison.maxResidual?.let {
                            String.format(Locale.US, "%.4f", it)
                        } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            comparison.equation?.takeIf { it.isNotBlank() }?.let { equation ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluoRadius.control),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = colorScheme.surfaceVariant.copy(alpha = 0.18f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.spectrum_calibration_equation),
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = equation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurface
                        )
                    }
                }
            }

            if (comparison.residualPoints.isNotEmpty()) {
                CalibrationResidualSection(
                    residualPoints = comparison.residualPoints,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (issueLabels.isNotEmpty()) {
                Text(
                    text = issueLabels.joinToString(separator = " / "),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            if (comparison.usedFallbackAlignment) {
                Text(
                    text = stringResource(R.string.spectrum_calibration_alignment_fallback),
                    fontSize = 12.sp,
                    color = colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CalibrationResidualSection(
    residualPoints: List<com.muc.fluocolorquant.data.model.SpectrumCalibrationResidualPoint>,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.outlinedCardColors(
            containerColor = colorScheme.surfaceVariant.copy(alpha = 0.18f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.spectrum_calibration_residual_section_title),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant
            )

            residualPoints.forEach { point ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(FluoRadius.badge))
                        .background(colorScheme.surface.copy(alpha = 0.88f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.spectrum_calibration_residual_rank,
                                point.rank
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.spectrum_calibration_residual_reference_value,
                                point.referenceWavelength
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.spectrum_calibration_residual_fitted_value,
                                point.fittedWavelength
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.spectrum_calibration_residual_delta_value,
                                point.residual
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (kotlin.math.abs(point.residual) <= 1.0) {
                                Color(0xFF10B981)
                            } else {
                                colorScheme.secondary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonImagePanel(
    title: String,
    imagePath: String?,
    fallbackLabel: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.outlinedCardColors(containerColor = colorScheme.surfaceVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(RoundedCornerShape(FluoRadius.badge))
                    .background(colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (!imagePath.isNullOrBlank()) {
                    AsyncImage(
                        model = imagePath,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = fallbackLabel,
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * 光谱曲线展示卡片
 */
@Composable
private fun SpectrumCurveCard(
    chartData: ChartData,
    curveMode: SpectrumCurveMode,
    modifier: Modifier = Modifier,
    onCurveModeChange: (SpectrumCurveMode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var showCurveModeHelp by remember(curveMode) { mutableStateOf(false) }
    val curveModeTitle = when (curveMode) {
        SpectrumCurveMode.RAW -> stringResource(R.string.spectrum_curve_mode_raw)
        SpectrumCurveMode.CLASSIC -> stringResource(R.string.spectrum_curve_mode_classic)
        SpectrumCurveMode.BASELINE -> stringResource(R.string.spectrum_curve_mode_baseline)
        SpectrumCurveMode.ENHANCED -> stringResource(R.string.spectrum_curve_mode_enhanced)
    }
    val curveModeDescription = when (curveMode) {
        SpectrumCurveMode.RAW -> stringResource(R.string.spectrum_curve_mode_raw_desc)
        SpectrumCurveMode.CLASSIC -> stringResource(R.string.spectrum_curve_mode_classic_desc)
        SpectrumCurveMode.BASELINE -> stringResource(R.string.spectrum_curve_mode_baseline_desc)
        SpectrumCurveMode.ENHANCED -> stringResource(R.string.spectrum_curve_mode_enhanced_desc)
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FluoIconBadge(
                        icon = Icons.Default.ShowChart,
                        accentColor = colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.spectrum_curve_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = colorScheme.onSurface
                    )
                }
                IconButton(onClick = { showCurveModeHelp = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.spectrum_curve_mode_help_action),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            CurveModeSegmentedControl(
                curveMode = curveMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                onCurveModeChange = onCurveModeChange
            )

            CurveChart(
                data = chartData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        }
    }

    if (showCurveModeHelp) {
        AlertDialog(
            onDismissRequest = { showCurveModeHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null
                )
            },
            title = {
                Text(stringResource(R.string.spectrum_curve_mode_help_title, curveModeTitle))
            },
            text = { Text(curveModeDescription) },
            confirmButton = {
                TextButton(onClick = { showCurveModeHelp = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

/**
 * 峰值信息卡片。
 */
@Composable
private fun CurveModeSegmentedControl(
    curveMode: SpectrumCurveMode,
    modifier: Modifier = Modifier,
    onCurveModeChange: (SpectrumCurveMode) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FluoRadius.control))
            .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 同行按钮统一取最高者的高度：Baseline Review 折行时不会只把这一行撑高、
        // 造成 2×2 网格上下两行错落。
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            CurveModeSegmentButton(
                text = stringResource(R.string.spectrum_curve_mode_raw),
                selected = curveMode == SpectrumCurveMode.RAW,
                modifier = Modifier.weight(1f),
                onClick = { onCurveModeChange(SpectrumCurveMode.RAW) }
            )
            CurveModeSegmentButton(
                text = stringResource(R.string.spectrum_curve_mode_classic),
                selected = curveMode == SpectrumCurveMode.CLASSIC,
                modifier = Modifier.weight(1f),
                onClick = { onCurveModeChange(SpectrumCurveMode.CLASSIC) }
            )
        }
        // 同行按钮统一取最高者的高度：Baseline Review 折行时不会只把这一行撑高、
        // 造成 2×2 网格上下两行错落。
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.height(IntrinsicSize.Min)
        ) {
            CurveModeSegmentButton(
                text = stringResource(R.string.spectrum_curve_mode_baseline),
                selected = curveMode == SpectrumCurveMode.BASELINE,
                modifier = Modifier.weight(1f),
                onClick = { onCurveModeChange(SpectrumCurveMode.BASELINE) }
            )
            CurveModeSegmentButton(
                text = stringResource(R.string.spectrum_curve_mode_enhanced),
                selected = curveMode == SpectrumCurveMode.ENHANCED,
                modifier = Modifier.weight(1f),
                onClick = { onCurveModeChange(SpectrumCurveMode.ENHANCED) }
            )
        }
    }

}

@Composable
private fun CurveModeSegmentButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FluoRadius.badge))
            .background(
                if (selected) colorScheme.primary
                else Color.Transparent
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else colorScheme.outline.copy(alpha = 0.38f),
                shape = RoundedCornerShape(FluoRadius.badge)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun PeakInfoCard(
    peaks: List<SpectrumPeakUiModel>,
    curveMode: SpectrumCurveMode,
    dataPointCount: Int,
    modifier: Modifier = Modifier
) {
    val primaryPeak = peaks.firstOrNull()
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                FluoIconBadge(
                    icon = Icons.Default.Star,
                    accentColor = colorScheme.tertiary
                )
                Text(
                    text = stringResource(R.string.spectrum_peak_result_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = colorScheme.onSurface
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoItem(
                    label = stringResource(R.string.spectrum_peak_wavelength),
                    value = primaryPeak?.let { String.format(Locale.US, "%.1f", it.wavelength) } ?: "--",
                    unit = primaryPeak?.let { "nm" },
                    modifier = Modifier.weight(1f)
                )
                
                InfoItem(
                    label = stringResource(R.string.spectrum_peak_intensity),
                    value = primaryPeak?.let { String.format(Locale.US, "%.3f", it.intensity) } ?: "--",
                    modifier = Modifier.weight(1f)
                )
                
                InfoItem(
                    label = stringResource(R.string.spectrum_data_points),
                    value = dataPointCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            if (curveMode == SpectrumCurveMode.BASELINE || curveMode == SpectrumCurveMode.ENHANCED) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoItem(
                        label = stringResource(R.string.spectrum_peak_prominence),
                        value = primaryPeak?.let { String.format(Locale.US, "%.3f", it.prominence) } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                    InfoItem(
                        label = stringResource(R.string.spectrum_peak_fwhm),
                        value = primaryPeak?.let { String.format(Locale.US, "%.2f", it.fullWidthHalfMax) } ?: "--",
                        unit = primaryPeak?.let { "nm" },
                        modifier = Modifier.weight(1f)
                    )
                    InfoItem(
                        label = stringResource(R.string.spectrum_peak_snr),
                        value = primaryPeak?.let { String.format(Locale.US, "%.2f", it.signalToNoise) } ?: "--",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                InfoItem(
                    label = stringResource(R.string.spectrum_peak_area),
                    value = primaryPeak?.let { String.format(Locale.US, "%.3f", it.area) } ?: "--",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (peaks.size > 1) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.spectrum_secondary_peaks_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peaks.drop(1).forEach { peak ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(FluoRadius.badge))
                                .background(colorScheme.surfaceVariant.copy(alpha = 0.22f))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.spectrum_peak_rank_label, peak.rank),
                                color = colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f nm", peak.wavelength),
                                color = colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(Locale.US, "%.3f", peak.intensity),
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (curveMode == SpectrumCurveMode.BASELINE || curveMode == SpectrumCurveMode.ENHANCED) {
                            Text(
                                text = stringResource(
                                    R.string.spectrum_peak_secondary_metrics,
                                    String.format(Locale.US, "%.2f", peak.signalToNoise),
                                    String.format(Locale.US, "%.2f nm", peak.fullWidthHalfMax)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 数据范围卡片
 */
@Composable
private fun DataRangeCard(
    minWavelength: Double,
    maxWavelength: Double,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.spectrum_wavelength_range),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FluoRadius.badge))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                colorScheme.primary.copy(alpha = 0.12f),
                                colorScheme.secondary.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.spectrum_min_wavelength),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%.1f nm", minWavelength),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary
                    )
                }
                
                Text(
                    text = stringResource(R.string.spectrum_range_arrow),
                    fontSize = 24.sp,
                    color = colorScheme.onSurfaceVariant
                )
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.spectrum_max_wavelength),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = String.format("%.1f nm", maxWavelength),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.secondary
                    )
                }
            }
        }
    }
}

/**
 * 多通道叠加对比卡片。
 */
@Composable
private fun SpectrumComparisonCard(
    chartData: ChartData?,
    channels: List<SpectrumChannelUiModel>,
    modifier: Modifier = Modifier
) {
    if (chartData == null || channels.isEmpty()) return
    val colorScheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.spectrum_compare_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface
            )

            CurveChart(
                data = chartData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                interactive = false
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                channels.withIndex().toList().chunked(2).forEach { rowChannels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowChannels.forEach { indexedChannel ->
                            val lineColor = if (indexedChannel.index == 0) {
                                chartData.curveColor
                            } else {
                                chartData.overlayLines.getOrNull(indexedChannel.index - 1)?.color
                                    ?: colorScheme.onSurfaceVariant
                            }

                            ComparisonLegendItem(
                                lineColor = lineColor,
                                channel = indexedChannel.value,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (rowChannels.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonLegendItem(
    lineColor: Color,
    channel: SpectrumChannelUiModel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(lineColor)
        )
        Text(
            text = stringResource(
                R.string.spectrum_compare_line_legend,
                channel.channelIndex,
                channel.analyteName
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 导出进度对话框。
 */
@Composable
private fun ExportProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(stringResource(R.string.spectrum_export_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.spectrum_export_progress_desc),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    )
}

/**
 * 分析物重绑对话框。
 */
@Composable
private fun AnalyteBindingDialog(
    availableAnalytes: List<Analyte>,
    currentAnalyteId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var selectedAnalyteId by remember(currentAnalyteId) { mutableStateOf(currentAnalyteId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spectrum_rebind_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedAnalyteId = null }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.spectrum_clear_binding))
                        if (selectedAnalyteId == null) {
                            Text(
                                text = stringResource(R.string.confirm),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                availableAnalytes.forEach { analyte ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedAnalyteId = analyte.id }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(analyte.name)
                            if (selectedAnalyteId == analyte.id) {
                                Text(
                                    text = stringResource(R.string.confirm),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedAnalyteId) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * 灵敏度筛选项。
 */
@Composable
private fun SensitivityFilterChip(
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

/**
 * 光谱指标块，直接委派给统一的 [FluoMetricTile]。
 *
 * 旧实现是本页手搓的一套：标签在上、数值在下、居中对齐，单位直接拼进数值字符串，且没有
 * 任何 `maxLines`。后果在真机上都能看到——"Peak Wavelength" 被从词中间断成
 * "Peak Wavelengt / h"，"514.5 nm" 连着单位一起折行，三块指标因行数不同而高低不齐；
 * 每块还各带一个硬编码高饱和色（全页共 7 种），与"低饱和状态色、颜色只表达语义"
 * 的风格约束相悖（AGENTS.md 7.2）。
 *
 * 统一组件把数值与单位分开排版、限制标签行数、使用同一容器色，三块自然等高。
 */
@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null
) {
    FluoMetricTile(
        label = label,
        value = value,
        unit = unit,
        modifier = modifier
    )
}
