package com.muc.fluocolorquant.ui.screens.result.array

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.detection.quantification.BuiltInSharedConcentrationModel
import com.muc.fluocolorquant.ui.components.LatexAlignment
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultUiState
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultViewModel
import com.muc.fluocolorquant.ui.viewmodels.ArrayRunHistoryItem
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlin.math.abs
import kotlin.math.sqrt

const val ARRAY_RESULT_SCREEN_TAG: String = "array_result_screen"
const val ARRAY_RESULT_ANALYTE_TAB_TAG: String = "array_result_tab_analytes"
const val ARRAY_ANALYTE_CHIP_TAG_PREFIX: String = "array_analyte_chip_"
const val ARRAY_HEATMAP_CARD_TAG_PREFIX: String = "array_heatmap_card_"
const val ARRAY_RUN_HISTORY_BUTTON_TAG: String = "array_run_history_button"
const val ARRAY_RUN_SWITCH_PROGRESS_TAG: String = "array_run_switch_progress"
const val ARRAY_RESULT_EXPORT_BUTTON_TAG: String = "array_result_export_button"

private enum class ArrayResultTab(val icon: ImageVector) {
    OVERVIEW(Icons.Outlined.GridOn),
    ANALYTES(Icons.Outlined.Analytics),
    IMAGE(Icons.Outlined.Image),
    PROCESS(Icons.Outlined.AccountTree),
    QC(Icons.Outlined.Verified)
}

/** 新微流控/通用阵列结果页面入口。 */
@Composable
fun ArrayResultScreen(
    navController: NavController,
    runId: String?,
    viewModel: ArrayResultViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(runId) { viewModel.load(runId) }
    ArrayResultContent(
        state = state,
        onBack = {
            // 无论用户是刚完成检测还是从历史记录进入，最终结果页返回都应落到首页首屏，
            // 不能退回定位、布局或历史 Pager 的中间状态。
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Home.route) { inclusive = true }
                launchSingleTop = true
            }
        },
        onRetry = viewModel::retry,
        onSelectRun = viewModel::selectRun
    )
}

/** 页面状态渲染与导航解耦，后续热力图和详情测试可以直接注入冻结快照。 */
@Composable
fun ArrayResultContent(
    state: ArrayResultUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectRun: (String) -> Unit = {}
) {
    when (state) {
        ArrayResultUiState.Loading -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_loading),
            showProgress = true,
            onRetry = null
        )
        ArrayResultUiState.NotFound -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_not_found),
            showProgress = false,
            onRetry = null
        )
        is ArrayResultUiState.CorruptSnapshot -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_corrupt_snapshot),
            showProgress = false,
            onRetry = null
        )
        ArrayResultUiState.Error -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_load_error),
            showProgress = false,
            onRetry = onRetry
        )
        is ArrayResultUiState.Success -> ArrayResultSuccess(
            snapshot = state.snapshot,
            history = state.history,
            switchingRunId = state.switchingRunId,
            historyReadFailed = state.historyReadFailed,
            onBack = onBack,
            onSelectRun = onSelectRun
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrayResultSuccess(
    snapshot: ArrayResultSnapshot,
    history: List<ArrayRunHistoryItem>,
    switchingRunId: String?,
    historyReadFailed: Boolean,
    onBack: () -> Unit,
    onSelectRun: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedSite by remember(snapshot.runId) { mutableStateOf<ArraySiteSelection?>(null) }
    var showRunHistory by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    val tabs = ArrayResultTab.entries
    Scaffold(
        modifier = Modifier.testTag(ARRAY_RESULT_SCREEN_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(snapshot.projectName, maxLines = 1)
                        Text(
                            text = stringResource(
                                R.string.array_result_current_run_title,
                                shortRunId(snapshot.runId)
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showExportSheet = true },
                        modifier = Modifier.testTag(ARRAY_RESULT_EXPORT_BUTTON_TAG)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.array_export_open)
                        )
                    }
                    IconButton(
                        onClick = { showRunHistory = true },
                        modifier = Modifier.testTag(ARRAY_RUN_HISTORY_BUTTON_TAG)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = stringResource(R.string.array_run_history_open)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ArrayResultHero(snapshot)
            if (switchingRunId != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ARRAY_RUN_SWITCH_PROGRESS_TAG)
                )
            }
            // 一级结果视图只有五项，使用等宽标签可让用户一眼看到全部入口。
            // ScrollableTabRow 在手机宽度下会把“质量控制”裁出屏幕，且没有明确的可滚动提示。
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        modifier = Modifier.testTag(
                            if (tab == ArrayResultTab.ANALYTES) {
                                ARRAY_RESULT_ANALYTE_TAB_TAG
                            } else {
                                "array_result_tab_${tab.name.lowercase()}"
                            }
                        ),
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = stringResource(tab.titleResource()),
                                maxLines = 1,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }
            when (tabs[selectedTab]) {
                // 帧级 QC 无论严重度如何都只负责提示复核，不再隐藏已经生成的热力图和
                // 分析物结果。每个位点的可靠性、补位和光度失败仍会在图中明确标色，用户
                // 可以同时查看结果、原图、九步处理证据和质控原因后自行决定是否重拍。
                ArrayResultTab.OVERVIEW -> ArrayOverviewTab(
                    snapshot = snapshot,
                    onSiteClick = { selectedSite = it }
                )
                ArrayResultTab.ANALYTES -> ArrayAnalytesTab(
                    snapshot = snapshot,
                    onSiteClick = { selectedSite = it }
                )
                ArrayResultTab.IMAGE -> ArrayImageTab(
                    snapshot = snapshot,
                    onSiteClick = { selectedSite = it }
                )
                ArrayResultTab.PROCESS -> ArrayProcessingEvidenceTab(snapshot)
                ArrayResultTab.QC -> ArrayQcPanel(
                    snapshot = snapshot,
                    onSiteClick = { site ->
                        selectedSite = ArraySiteSelection(site.siteIndex, site.analyteId)
                    }
                )
            }
        }
    }
    selectedSite?.let { selection ->
        ArraySiteDetailSheet(
            snapshot = snapshot,
            selection = selection,
            onDismiss = { selectedSite = null }
        )
    }
    if (showRunHistory) {
        ArrayRunHistorySheet(
            history = history,
            currentRunId = snapshot.runId,
            switchingRunId = switchingRunId,
            historyReadFailed = historyReadFailed,
            onSelectRun = { selectedRunId ->
                onSelectRun(selectedRunId)
                // 选择后收起面板，让用户继续看到当前结果和顶部的轻量切换进度。
                showRunHistory = false
            },
            onDismiss = { showRunHistory = false }
        )
    }
    ArrayResultExportCoordinator(
        snapshot = snapshot,
        visible = showExportSheet,
        onDismiss = { showExportSheet = false }
    )
}

@Composable
private fun ArrayResultHero(snapshot: ArrayResultSnapshot) {
    val measuredSiteCount = snapshot.sites.count { it.measurements.isNotEmpty() }
    val reliableMeasurementCount = snapshot.sites.sumOf { site ->
        site.measurements.count { it.qualityReliable }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Biotech,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        text = snapshot.userFacingCarrierName(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(
                            R.string.array_result_carrier_summary,
                            snapshot.rows,
                            snapshot.columns,
                            arrayRunStatusLabel(snapshot.runStatus)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricText(stringResource(R.string.array_result_physical_sites), snapshot.sites.size)
                MetricText(stringResource(R.string.array_result_measured_sites), measuredSiteCount)
                MetricText(stringResource(R.string.array_result_reliable_measurements), reliableMeasurementCount)
            }
        }
    }
}

/**
 * 直接创建流程会把内置载体的稳定机器名冻结到运行快照中。结果页只对已知的内置机器名
 * 做本地化展示，自定义载体继续原样显示，避免擅自改写用户命名。
 */
@Composable
private fun ArrayResultSnapshot.userFacingCarrierName(): String {
    val builtInMicrofluidicName = "microfluidic-${rows}x${columns}"
    return if (
        carrier.carrierType.equals("MICROFLUIDIC_CHIP", ignoreCase = true) &&
        carrier.name.equals(builtInMicrofluidicName, ignoreCase = true)
    ) {
        stringResource(R.string.array_result_microfluidic_carrier_format, rows, columns)
    } else {
        carrier.name
    }
}

@Composable
private fun MetricText(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun ArrayOverviewTab(
    snapshot: ArrayResultSnapshot,
    onSiteClick: (ArraySiteSelection) -> Unit
) {
    var selectedAnalyteId by rememberSaveable(snapshot.runId) {
        mutableStateOf(snapshot.analytes.firstOrNull()?.analyteId)
    }
    val selectedAnalyte = snapshot.analytes.firstOrNull { analyte ->
        analyte.analyteId == selectedAnalyteId
    } ?: snapshot.analytes.firstOrNull()
    val heatmapModel = selectedAnalyte?.let { analyte ->
        remember(snapshot, analyte) { buildAnalyteHeatmapModel(snapshot, analyte) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (snapshot.analytes.isEmpty() || selectedAnalyte == null || heatmapModel == null) {
            item {
                SectionCard(
                    icon = Icons.Outlined.Science,
                    title = stringResource(R.string.array_result_no_analytes),
                    body = stringResource(R.string.array_result_no_analytes_body)
                )
            }
        } else {
            // 不同分析物可能使用 ng/mL、IU/mL 等不同单位，所以总览必须先选分析物，
            // 再绘制单一单位的浓度色带，绝不能把多个分析物的绝对浓度混成一张图。
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(snapshot.analytes, key = { it.analyteId }) { analyte ->
                        FilterChip(
                            modifier = Modifier.testTag(
                                "array_overview_analyte_chip_${analyte.analyteId}"
                            ),
                            selected = analyte.analyteId == selectedAnalyte.analyteId,
                            onClick = { selectedAnalyteId = analyte.analyteId },
                            label = { Text(analyte.name) }
                        )
                    }
                }
            }
            item {
                ArrayHeatmapResultCard(
                    title = stringResource(
                        R.string.array_heatmap_analyte_title,
                        selectedAnalyte.name
                    ),
                    subtitle = heatmapSubtitle(heatmapModel, selectedAnalyte),
                    model = heatmapModel,
                    onSiteClick = { cell ->
                        onSiteClick(
                            ArraySiteSelection(
                                siteIndex = cell.siteIndex,
                                analyteId = selectedAnalyte.analyteId
                            )
                        )
                    }
                )
            }
            item { ArrayHeatmapStatistics(heatmapModel) }
            item {
                ArraySampleConcentrationTable(
                    snapshot = snapshot,
                    analyte = selectedAnalyte,
                    onSiteClick = onSiteClick
                )
            }
            item { AnalyteSnapshotCard(selectedAnalyte) }
        }
    }
}

/** 只有函数、完整参数和至少两个有限标准点同时存在时才展示曲线，避免伪图。 */
private fun ArrayAnalyteResult.hasRenderableCurve(): Boolean {
    val function = fittingFunction?.let(FittingFunction::fromIdentifier) ?: return false
    return calibrationPoints.size >= 2 &&
        function.requiredParams.all(fittingParameters::containsKey)
}

/**
 * 展示本次运行真正冻结的标准曲线，而不是回读当前曲线库。
 * 图表复用项目已有 Canvas 曲线组件，公式则由 jlatexmath-android 排版。
 */
@Composable
private fun ArrayStandardCurveCard(analyte: ArrayAnalyteResult) {
    val function = remember(analyte.fittingFunction) {
        analyte.fittingFunction?.let(FittingFunction::fromIdentifier)
    } ?: return
    val parameters = analyte.fittingParameters
    val points = remember(analyte.calibrationPoints) {
        analyte.calibrationPoints.map { point -> point.concentration to point.signalValue }
    }
    val fittedCurve = remember(function, parameters) {
        FittingEngine.createFunctionFromParameters(function, parameters)
    }
    val latex = remember(function, parameters) {
        FittingEngine.formatParametersToLatex(function, parameters)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.array_result_curve_title, analyte.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.array_result_curve_subtitle,
                    points.size,
                    analyte.concentrationUnit
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                LatexView(
                    latex = latex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    alignment = LatexAlignment.CENTER
                )
            }
            CurveChart(
                fittedCurve = fittedCurve,
                selectedFunction = function,
                parameters = parameters,
                xAxisLabel = stringResource(
                    R.string.array_result_curve_x_axis,
                    analyte.concentrationUnit
                ),
                yAxisLabel = stringResource(
                    R.string.array_result_curve_y_axis,
                    analyte.primaryFeature
                ),
                title = "",
                dataPoints = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        }
    }
}

@Composable
private fun ArrayAnalytesTab(
    snapshot: ArrayResultSnapshot,
    onSiteClick: (ArraySiteSelection) -> Unit
) {
    var selectedAnalyteId by remember(snapshot.runId) {
        mutableStateOf(snapshot.analytes.firstOrNull()?.analyteId)
    }
    val selectedAnalyte = snapshot.analytes.firstOrNull { it.analyteId == selectedAnalyteId }
        ?: snapshot.analytes.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (snapshot.analytes.isEmpty()) {
            item {
                SectionCard(
                    icon = Icons.Outlined.Science,
                    title = stringResource(R.string.array_result_no_analytes),
                    body = stringResource(R.string.array_result_no_analytes_body)
                )
            }
        } else {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(snapshot.analytes, key = { it.analyteId }) { analyte ->
                        FilterChip(
                            modifier = Modifier.testTag("$ARRAY_ANALYTE_CHIP_TAG_PREFIX${analyte.analyteId}"),
                            selected = analyte.analyteId == selectedAnalyte?.analyteId,
                            onClick = {
                                selectedAnalyteId = analyte.analyteId
                            },
                            label = { Text(analyte.name) }
                        )
                    }
                }
            }
            if (selectedAnalyte != null) {
                item { AnalyteSnapshotCard(selectedAnalyte) }
                if (selectedAnalyte.hasRenderableCurve()) {
                    item { ArrayStandardCurveCard(selectedAnalyte) }
                    item { ArrayCurveMetricsCard(selectedAnalyte) }
                } else {
                    item {
                        ArrayQuantitationMethodSummary(
                            snapshot = snapshot,
                            analyte = selectedAnalyte
                        )
                    }
                }
                item {
                    ArrayRepeatabilityCard(
                        snapshot = snapshot,
                        analyte = selectedAnalyte
                    )
                }
                item {
                    ArrayStandardRecoveryCard(
                        snapshot = snapshot,
                        analyte = selectedAnalyte
                    )
                }
                item {
                    ArraySampleConcentrationTable(
                        snapshot = snapshot,
                        analyte = selectedAnalyte,
                        onSiteClick = onSiteClick
                    )
                }
            }
        }
    }
}

/** 曲线/统计页展示冻结拟合质量，不重新选择模型或修改历史结果。 */
@Composable
private fun ArrayCurveMetricsCard(analyte: ArrayAnalyteResult) {
    val metrics = analyte.validationMetrics
    val acceptedRatio = metrics["ACCEPTED_STANDARD_RATIO"]
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.array_statistics_fit_metrics_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ResultMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_r2),
                    value = metrics["R2"]?.let(::formatArrayHeatmapValue).orEmpty()
                )
                ResultMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_rmse),
                    value = metrics["RMSE"]?.let(::formatArrayHeatmapValue).orEmpty()
                )
                ResultMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_mae),
                    value = metrics["MAE"]?.let(::formatArrayHeatmapValue).orEmpty()
                )
                ResultMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.grid_quant_fit_metric_acceptance),
                    value = acceptedRatio?.let { ratio ->
                        stringResource(R.string.array_statistics_percent_value, ratio * 100.0)
                    }.orEmpty()
                )
            }
            if (metrics.isEmpty()) {
                Text(
                    text = stringResource(R.string.array_statistics_metrics_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResultMetric(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value.ifBlank { stringResource(R.string.grid_quant_value_unavailable) },
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 深度学习或仅信号没有标准曲线时，展示真实执行计数而不是伪造空曲线。 */
@Composable
private fun ArrayQuantitationMethodSummary(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult
) {
    val records = remember(snapshot.runId, analyte.analyteId) {
        snapshot.analyteRecords(analyte.analyteId)
    }
    val quantified = records.count { record -> record.measurement.concentrationValue != null }
    val outOfRange = records.count { record ->
        record.measurement.reliableRangeStatus in setOf("BELOW_RANGE", "ABOVE_RANGE")
    }
    val signalOnly = records.size - quantified - outOfRange
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.array_statistics_quantitation_summary),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeatmapMetric(
                    value = quantified.toString(),
                    label = stringResource(R.string.array_statistics_quantified)
                )
                HeatmapMetric(
                    value = outOfRange.toString(),
                    label = stringResource(R.string.array_statistics_out_of_range)
                )
                HeatmapMetric(
                    value = signalOnly.toString(),
                    label = stringResource(R.string.array_statistics_signal_only)
                )
            }
        }
    }
}

/** 重复组按模板 repeatGroup 或重复样本编号聚合，计算平均值、样本标准差和 CV。 */
@Composable
private fun ArrayRepeatabilityCard(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult
) {
    val groups = remember(snapshot.runId, analyte.analyteId) {
        snapshot.analyteRecords(analyte.analyteId)
            .mapNotNull { record ->
                val value = record.measurement.concentrationValue ?: return@mapNotNull null
                val groupKey = record.repeatGroup
                    ?: record.sampleSlot?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                groupKey to value
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { values -> values.size >= 2 }
            .map { (name, values) -> RepeatabilitySummary.create(name, values) }
            .sortedBy(RepeatabilitySummary::name)
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = stringResource(R.string.array_statistics_repeatability_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.array_statistics_repeatability_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                groups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = stringResource(
                                    R.string.array_statistics_repeatability_mean,
                                    formatArrayHeatmapValue(group.mean),
                                    analyte.concentrationUnit,
                                    group.count
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = group.cvPercent?.let { cv ->
                                stringResource(R.string.array_statistics_cv_value, cv)
                            } ?: stringResource(R.string.grid_quant_value_unavailable),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** 标准品有已知浓度时计算回收率；无真实标准结果时明确显示空状态。 */
@Composable
private fun ArrayStandardRecoveryCard(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult
) {
    val recoveries = remember(snapshot.runId, analyte.analyteId) {
        snapshot.analyteRecords(analyte.analyteId).mapNotNull { record ->
            if (record.roleCode != TemplateSiteRole.STANDARD.code) return@mapNotNull null
            val expected = record.standardConcentration?.takeIf { it.isFinite() && it != 0.0 }
                ?: return@mapNotNull null
            val measured = record.measurement.concentrationValue ?: return@mapNotNull null
            measured / expected * 100.0
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.array_statistics_recovery_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (recoveries.isEmpty()) {
                Text(
                    text = stringResource(R.string.array_statistics_recovery_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val mean = recoveries.average()
                Text(
                    text = stringResource(
                        R.string.array_statistics_recovery_summary,
                        mean,
                        recoveries.minOrNull() ?: mean,
                        recoveries.maxOrNull() ?: mean,
                        recoveries.size
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 结果页与曲线统计页共用的逐样本浓度表。 */
@Composable
private fun ArraySampleConcentrationTable(
    snapshot: ArrayResultSnapshot,
    analyte: ArrayAnalyteResult,
    onSiteClick: (ArraySiteSelection) -> Unit
) {
    val records = remember(snapshot.runId, analyte.analyteId) {
        snapshot.analyteRecords(analyte.analyteId).filter { record ->
            record.roleCode == TemplateSiteRole.SAMPLE.code
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.array_statistics_sample_table_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.array_statistics_sample_table_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.array_statistics_site),
                        modifier = Modifier.width(54.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        stringResource(R.string.array_statistics_sample),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        stringResource(R.string.array_statistics_concentration),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                HorizontalDivider()
                records.take(MAXIMUM_VISIBLE_SAMPLE_ROWS).forEach { record ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSiteClick(
                                    ArraySiteSelection(record.siteIndex, analyte.analyteId)
                                )
                            }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(record.siteKey, modifier = Modifier.width(54.dp))
                        Text(
                            text = record.sampleSlot.orEmpty().ifBlank {
                                stringResource(R.string.array_statistics_unnamed_sample)
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = record.measurement.concentrationValue?.let { value ->
                                stringResource(
                                    R.string.array_statistics_concentration_value,
                                    formatArrayHeatmapValue(value),
                                    analyte.concentrationUnit
                                )
                            } ?: stringResource(R.string.array_statistics_not_quantified),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (record.measurement.concentrationValue != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                val hiddenCount = records.size - MAXIMUM_VISIBLE_SAMPLE_ROWS
                if (hiddenCount > 0) {
                    Text(
                        text = stringResource(R.string.array_statistics_more_rows, hiddenCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 一个分析物在某物理位点的结果行，避免多个统计组件重复遍历和拼接快照字段。 */
private data class ArrayAnalyteSiteRecord(
    val siteIndex: Int,
    val siteKey: String,
    val roleCode: String?,
    val sampleSlot: String?,
    val repeatGroup: String?,
    val standardConcentration: Double?,
    val measurement: ArraySiteMeasurementResult
)

private fun ArrayResultSnapshot.analyteRecords(analyteId: String): List<ArrayAnalyteSiteRecord> {
    return sites.mapNotNull { site ->
        val measurement = site.measurements.firstOrNull { it.analyteId == analyteId }
            ?: return@mapNotNull null
        ArrayAnalyteSiteRecord(
            siteIndex = site.siteIndex,
            siteKey = site.siteKey,
            roleCode = site.roleCode,
            sampleSlot = site.sampleSlot ?: site.defaultSampleSlot,
            repeatGroup = site.repeatGroup,
            standardConcentration = site.standardConcentration,
            measurement = measurement
        )
    }
}

private data class RepeatabilitySummary(
    val name: String,
    val count: Int,
    val mean: Double,
    val cvPercent: Double?
) {
    companion object {
        fun create(name: String, values: List<Double>): RepeatabilitySummary {
            val mean = values.average()
            val variance = values.sumOf { value -> (value - mean) * (value - mean) } /
                (values.size - 1).coerceAtLeast(1)
            val standardDeviation = sqrt(variance.coerceAtLeast(0.0))
            val cv = (standardDeviation / abs(mean) * 100.0).takeIf {
                mean != 0.0 && it.isFinite()
            }
            return RepeatabilitySummary(name, values.size, mean, cv)
        }
    }
}

private const val MAXIMUM_VISIBLE_SAMPLE_ROWS = 50

/** 热力图卡片统一承载网格、科学色带和独立 QC 图例。 */
@Composable
private fun ArrayHeatmapResultCard(
    title: String,
    subtitle: String,
    model: ArrayHeatmapModel,
    onSiteClick: (ArrayHeatmapCell) -> Unit
) {
    var showZoomHint by rememberSaveable(model.analyteId, model.rows, model.columns) {
        mutableStateOf(maxOf(model.rows, model.columns) >= 15)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_HEATMAP_CARD_TAG_PREFIX${model.analyteId ?: "overview"}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            // 色带来源是必要的科学语义，但压缩成一行状态标签，避免结果页出现说明段落。
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            if (showZoomHint) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.array_heatmap_zoom_hint),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        IconButton(
                            onClick = { showZoomHint = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.array_heatmap_zoom_hint_dismiss),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
            ArrayHeatmap(model = model, onSiteClick = onSiteClick)
            ArrayHeatmapScaleLegend(model.scale)
            ArrayHeatmapQcLegend()
        }
    }
}

/** 当前色带的最小值、最大值和来源必须与分析物切换同步。 */
@Composable
private fun ArrayHeatmapScaleLegend(scale: ArrayHeatmapScale) {
    val unitSuffix = scale.unit.takeIf(String::isNotBlank).orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            HeatmapColorUtil.getLegendColors(24).forEach { color ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(8.dp)
                        .background(color)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatArrayHeatmapValue(scale.minimum),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = scaleLegendTitle(scale, unitSuffix),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatArrayHeatmapValue(scale.maximum),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 警告、失败和低信号必须使用不同符号，避免含义被合并。 */
@Composable
private fun ArrayHeatmapQcLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QcLegendItem(
            color = Color(0xFFF59E0B),
            label = stringResource(R.string.array_heatmap_legend_warning)
        )
        QcLegendItem(
            color = MaterialTheme.colorScheme.error,
            label = stringResource(R.string.array_heatmap_legend_failure)
        )
        QcLegendItem(
            color = Color(0xFF0288D1),
            label = stringResource(R.string.array_heatmap_legend_low_signal)
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QcLegendItem(color: Color, label: String) {
    Row(
        modifier = Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, RoundedCornerShape(3.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 统计卡明确区分质量警告、质量失败、低信号和未检测。 */
@Composable
private fun ArrayHeatmapStatistics(model: ArrayHeatmapModel) {
    val reliablePercent = if (model.measuredCount == 0) 0.0 else {
        model.reliableCount.toDouble() / model.measuredCount * 100.0
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.array_heatmap_statistics_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HeatmapMetric(
                    value = stringResource(R.string.array_heatmap_percent, reliablePercent),
                    label = stringResource(R.string.array_heatmap_reliable)
                )
                HeatmapMetric(
                    value = model.warningCount.toString(),
                    label = stringResource(R.string.array_heatmap_warning)
                )
                HeatmapMetric(
                    value = model.failureCount.toString(),
                    label = stringResource(R.string.array_heatmap_failure)
                )
                HeatmapMetric(
                    value = model.missingCount.toString(),
                    label = stringResource(R.string.array_heatmap_missing)
                )
            }
            Text(
                text = stringResource(R.string.array_heatmap_low_signal_count, model.lowSignalCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HeatmapMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 总览只统计模板冻结的角色分布，不展示跨分析物绝对数值。 */
@Composable
private fun ArrayRoleDistribution(roleCounts: Map<String, Int>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.array_heatmap_role_distribution),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (roleCounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.array_heatmap_role_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                roleCounts.entries.sortedBy { it.key }.forEach { (code, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(roleLabel(code), style = MaterialTheme.typography.bodyMedium)
                        Text(count.toString(), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyteSnapshotCard(analyte: ArrayAnalyteResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = analyte.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.array_result_analyte_model,
                    analyte.userFacingModelName(),
                    analyte.modelVersion
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    R.string.array_result_analyte_feature,
                    analyte.primaryFeature,
                    analyte.concentrationUnit
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 内置共享模型在历史结果页同样使用本地化名称，不暴露稳定机器资源名。 */
@Composable
private fun ArrayAnalyteResult.userFacingModelName(): String {
    return if (BuiltInSharedConcentrationModel.isBuiltInResourceName(modelName)) {
        stringResource(R.string.grid_quant_builtin_shared_model)
    } else {
        modelName
    }
}

@Composable
private fun heatmapSubtitle(model: ArrayHeatmapModel, analyte: ArrayAnalyteResult): String {
    return when (model.scale.mode) {
        ArrayHeatmapScaleMode.CONCENTRATION -> when (model.scale.rangeSource) {
            ArrayHeatmapRangeSource.RELIABLE_RANGE -> stringResource(
                R.string.array_heatmap_concentration_reliable_subtitle,
                analyte.concentrationUnit
            )
            else -> stringResource(
                R.string.array_heatmap_concentration_observed_subtitle,
                analyte.concentrationUnit
            )
        }
        ArrayHeatmapScaleMode.PRIMARY_FEATURE -> stringResource(
            R.string.array_heatmap_signal_subtitle,
            analyte.primaryFeature
        )
        ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY -> stringResource(
            R.string.array_heatmap_overview_subtitle
        )
    }
}

@Composable
private fun scaleLegendTitle(scale: ArrayHeatmapScale, unitSuffix: String): String {
    return when (scale.mode) {
        ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY -> stringResource(
            R.string.array_heatmap_scale_reliability
        )
        ArrayHeatmapScaleMode.CONCENTRATION -> stringResource(
            R.string.array_heatmap_scale_concentration,
            unitSuffix
        )
        ArrayHeatmapScaleMode.PRIMARY_FEATURE -> stringResource(
            R.string.array_heatmap_scale_signal,
            scale.featureName.orEmpty()
        )
    }
}

@Composable
private fun roleLabel(code: String): String {
    return when (TemplateSiteRole.fromCode(code)) {
        TemplateSiteRole.SAMPLE -> stringResource(R.string.template_array_role_sample)
        TemplateSiteRole.STANDARD -> stringResource(R.string.template_array_role_standard)
        TemplateSiteRole.BLANK -> stringResource(R.string.template_array_role_blank)
        TemplateSiteRole.NEGATIVE_CONTROL -> stringResource(R.string.template_array_role_negative_control)
        TemplateSiteRole.POSITIVE_CONTROL -> stringResource(R.string.template_array_role_positive_control)
        TemplateSiteRole.REFERENCE -> stringResource(R.string.template_array_role_reference)
        TemplateSiteRole.DISABLED -> stringResource(R.string.template_array_role_disabled)
        null -> code
    }
}

@Composable
private fun ArrayImageTab(
    snapshot: ArrayResultSnapshot,
    onSiteClick: (ArraySiteSelection) -> Unit
) {
    val captureArtifacts = remember(snapshot.runId, snapshot.artifacts) {
        snapshot.artifacts.filter { artifact ->
            CaptureRole.fromCode(artifact.captureRole)?.isProcessingEvidence != true
        }
    }
    var selectedArtifactId by remember(snapshot.runId) {
        mutableStateOf(captureArtifacts.firstOrNull()?.artifactId)
    }
    val selectedArtifact = captureArtifacts.firstOrNull { it.artifactId == selectedArtifactId }
        ?: captureArtifacts.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (captureArtifacts.isEmpty()) {
            item {
                SectionCard(
                    icon = Icons.Outlined.BrokenImage,
                    title = stringResource(R.string.array_result_no_artifact),
                    body = stringResource(R.string.array_result_no_artifact_body)
                )
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(captureArtifacts, key = { it.artifactId }) { artifact ->
                        FilterChip(
                            selected = artifact.artifactId == selectedArtifact?.artifactId,
                            onClick = { selectedArtifactId = artifact.artifactId },
                            label = { Text(captureRoleLabel(artifact.captureRole)) }
                        )
                    }
                }
            }
            selectedArtifact?.let { artifact ->
                item {
                    ArrayImageOverlay(
                        artifact = artifact,
                        sites = snapshot.sites,
                        onSiteClick = { site ->
                            onSiteClick(ArraySiteSelection(site.siteIndex, site.analyteId))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun captureRoleLabel(code: String): String {
    return when (CaptureRole.fromCode(code)) {
        CaptureRole.ENDPOINT -> stringResource(R.string.array_capture_role_endpoint)
        CaptureRole.SPECTRUM_SINGLE -> stringResource(R.string.array_capture_role_spectrum)
        CaptureRole.DARK -> stringResource(R.string.array_capture_role_dark)
        CaptureRole.REFERENCE -> stringResource(R.string.array_capture_role_reference)
        CaptureRole.PRE_ANALYTE_BASELINE -> stringResource(R.string.array_capture_role_pre_baseline)
        CaptureRole.POST_REACTION_ENDPOINT -> stringResource(R.string.array_capture_role_post_endpoint)
        CaptureRole.PROCESS_ORIGINAL_GEOMETRY,
        CaptureRole.PROCESS_CANDIDATE_RESPONSE,
        CaptureRole.PROCESS_RECTIFIED,
        CaptureRole.PROCESS_GRID_OVERLAY,
        CaptureRole.PROCESS_ROI_BACKGROUND,
        CaptureRole.PROCESS_BACKGROUND_FIELD,
        CaptureRole.PROCESS_SIGNAL_HEATMAP,
        CaptureRole.PROCESS_SNR_HEATMAP,
        CaptureRole.PROCESS_CORRECTED_COLOR -> processingEvidenceShortTitle(CaptureRole.fromCode(code))
        null -> stringResource(R.string.array_capture_role_unknown)
    }
}

@Composable
private fun SectionCard(icon: ImageVector, title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ArrayResultCenteredState(
    title: String,
    showProgress: Boolean,
    onRetry: (() -> Unit)?
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (showProgress) CircularProgressIndicator()
            else Icon(Icons.Outlined.BrokenImage, contentDescription = null)
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (onRetry != null) {
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

private fun ArrayResultTab.titleResource(): Int = when (this) {
    ArrayResultTab.OVERVIEW -> R.string.array_result_tab_overview
    ArrayResultTab.ANALYTES -> R.string.array_result_tab_analytes
    ArrayResultTab.IMAGE -> R.string.array_result_tab_image
    ArrayResultTab.PROCESS -> R.string.array_result_tab_process
    ArrayResultTab.QC -> R.string.array_result_tab_qc
}

/** 顶栏只显示便于辨认的运行 ID 尾段，完整 ID 仍在历史面板中保留。 */
private fun shortRunId(runId: String): String {
    return if (runId.length <= 12) runId else runId.takeLast(12)
}
