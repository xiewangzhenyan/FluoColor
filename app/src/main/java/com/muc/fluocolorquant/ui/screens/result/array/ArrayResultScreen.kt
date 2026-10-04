package com.muc.fluocolorquant.ui.screens.result.array

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayRangeRecoveryResult
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.detection.quantification.BuiltInSharedConcentrationModel
import com.muc.fluocolorquant.domain.detection.quantification.RangeRecoveryStatus
import com.muc.fluocolorquant.ui.components.LatexAlignment
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultUiState
import com.muc.fluocolorquant.ui.viewmodels.ArrayResultViewModel
import com.muc.fluocolorquant.ui.viewmodels.ArrayRunHistoryItem
import com.muc.fluocolorquant.ui.viewmodels.DualModalAdjudicationViewModel
import com.muc.fluocolorquant.ui.viewmodels.DualModalUiState
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.math.FittingEngine
import kotlin.math.abs
import kotlin.math.sqrt
import com.muc.fluocolorquant.ui.theme.FluoRadius

const val ARRAY_RESULT_SCREEN_TAG: String = "array_result_screen"
const val ARRAY_RESULT_ANALYTE_TAB_TAG: String = "array_result_tab_analytes"
const val ARRAY_ANALYTE_CHIP_TAG_PREFIX: String = "array_analyte_chip_"
const val ARRAY_HEATMAP_CARD_TAG_PREFIX: String = "array_heatmap_card_"
const val ARRAY_HEATMAP_LEGEND_TAG_PREFIX: String = "array_heatmap_legend_"
const val ARRAY_RANGE_REVIEW_CARD_TAG_PREFIX: String = "array_range_review_card_"
const val ARRAY_DEEP_LEARNING_OUTPUT_WARNING_TAG_PREFIX: String =
    "array_deep_learning_output_warning_"
const val ARRAY_RUN_HISTORY_BUTTON_TAG: String = "array_run_history_button"
const val ARRAY_RUN_SWITCH_PROGRESS_TAG: String = "array_run_switch_progress"
const val ARRAY_RESULT_EXPORT_BUTTON_TAG: String = "array_result_export_button"

private enum class ArrayResultTab(val icon: ImageVector) {
    OVERVIEW(Icons.Outlined.GridOn),
    ANALYSIS(Icons.Outlined.Analytics),
    PROCESS(Icons.Outlined.AccountTree)
}

/** 新微流控/通用阵列结果页面入口。 */
@Composable
fun ArrayResultScreen(
    navController: NavController,
    runId: String?,
    viewModel: ArrayResultViewModel = hiltViewModel(),
    dualModalViewModel: DualModalAdjudicationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val dualModalState by dualModalViewModel.uiState.collectAsState()
    LaunchedEffect(runId) { viewModel.load(runId) }
    val loadedSnapshot = (state as? ArrayResultUiState.Success)?.snapshot
    LaunchedEffect(loadedSnapshot?.runId, loadedSnapshot?.detectionMode) {
        loadedSnapshot?.let { snapshot -> dualModalViewModel.bind(snapshot.runId, snapshot.detectionMode) }
    }
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
        onSelectRun = viewModel::selectRun,
        dualModalState = dualModalState,
        dualModalActions = DualModalCardActions(
            onLoadCandidates = dualModalViewModel::loadCandidates,
            onPair = dualModalViewModel::pair,
            onUnpair = dualModalViewModel::unpair,
            onReAdjudicate = dualModalViewModel::reAdjudicate,
            onDismissNotice = dualModalViewModel::dismissNotice,
            onRetry = dualModalViewModel::retry
        )
    )
}

/** 页面状态渲染与导航解耦，后续热力图和详情测试可以直接注入冻结快照。 */
@Composable
fun ArrayResultContent(
    state: ArrayResultUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectRun: (String) -> Unit = {},
    dualModalState: DualModalUiState = DualModalUiState.Hidden,
    dualModalActions: DualModalCardActions = DualModalCardActions()
) {
    // 加载、损坏快照和数据库错误同样属于完整页面状态，必须响应系统返回键。
    // 旧实现只在 Success 顶栏暴露返回动作，错误页因此会把用户困在当前导航目的地。
    BackHandler(onBack = onBack)
    when (state) {
        ArrayResultUiState.Loading -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_loading),
            showProgress = true,
            onRetry = null,
            onBack = onBack
        )
        ArrayResultUiState.NotFound -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_not_found),
            showProgress = false,
            onRetry = null,
            onBack = onBack
        )
        is ArrayResultUiState.CorruptSnapshot -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_corrupt_snapshot),
            showProgress = false,
            onRetry = null,
            onBack = onBack
        )
        ArrayResultUiState.Error -> ArrayResultCenteredState(
            title = stringResource(R.string.array_result_load_error),
            showProgress = false,
            onRetry = onRetry,
            onBack = onBack
        )
        is ArrayResultUiState.Success -> ArrayResultSuccess(
            snapshot = state.snapshot,
            history = state.history,
            switchingRunId = state.switchingRunId,
            historyReadFailed = state.historyReadFailed,
            onBack = onBack,
            onSelectRun = onSelectRun,
            dualModalState = dualModalState,
            dualModalActions = dualModalActions
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
    onSelectRun: (String) -> Unit,
    dualModalState: DualModalUiState = DualModalUiState.Hidden,
    dualModalActions: DualModalCardActions = DualModalCardActions()
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
            FluoTopBar(
                title = snapshot.projectName,
                subtitle = stringResource(
                    R.string.array_result_current_run_title,
                    shortRunId(snapshot.runId)
                ),
                onBack = onBack,
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
                }
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
            // 一级结果页只保留用户真正需要反复访问的三类信息：结果、分析与过程。
            // 原始图像作为过程证据的第一步展示；详细 QC 继续保存在位点详情和导出数据中。
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        modifier = Modifier.testTag(
                            if (tab == ArrayResultTab.ANALYSIS) {
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
            // 与96孔板结果页使用同一套标签切换过渡：横向滑动方向跟随标签顺序，
            // 幅度取容器宽度的 1/6，避免热力图和曲线在过渡期间形变。
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(
                        animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Standard),
                        initialOffsetX = { width -> if (forward) width / 6 else -width / 6 }
                    ) + fadeIn(
                        animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Decelerate)
                    )).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Standard),
                            targetOffsetX = { width -> if (forward) -width / 6 else width / 6 }
                        ) + fadeOut(
                            animationSpec = tween(FluoMotion.MICRO_MS, easing = FluoMotion.Accelerate)
                        )
                    )
                },
                label = "arrayResultTabContent"
            ) { tabIndex ->
                when (tabs[tabIndex]) {
                    ArrayResultTab.OVERVIEW -> ArrayOverviewTab(
                        snapshot = snapshot,
                        onSiteClick = { selectedSite = it },
                        dualModalState = dualModalState,
                        dualModalActions = dualModalActions
                    )
                    ArrayResultTab.ANALYSIS -> ArrayAnalytesTab(
                        snapshot = snapshot
                    )
                    ArrayResultTab.PROCESS -> ArrayProcessingEvidenceTab(snapshot)
                }
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
private fun ArrayOverviewTab(
    snapshot: ArrayResultSnapshot,
    onSiteClick: (ArraySiteSelection) -> Unit,
    dualModalState: DualModalUiState = DualModalUiState.Hidden,
    dualModalActions: DualModalCardActions = DualModalCardActions()
) {
    var selectedAnalyteId by rememberSaveable(snapshot.runId) {
        mutableStateOf(snapshot.analytes.firstOrNull()?.analyteId)
    }
    val selectedAnalyte = snapshot.analytes.firstOrNull { analyte ->
        analyte.analyteId == selectedAnalyteId
    } ?: snapshot.analytes.firstOrNull()
    var concentrationScaleMode by rememberSaveable(snapshot.runId, selectedAnalyte?.analyteId) {
        // 结果首屏优先回答“本次孔间有什么差异”；项目量程仍通过显式切换保留给跨实验比较。
        mutableStateOf(ArrayHeatmapConcentrationScaleMode.RUN_DISTRIBUTION)
    }
    val heatmapModel = selectedAnalyte?.let { analyte ->
        remember(snapshot, analyte, concentrationScaleMode) {
            buildAnalyteHeatmapModel(
                snapshot = snapshot,
                analyte = analyte,
                concentrationScaleMode = concentrationScaleMode
            )
        }
    }
    val selectedAnalyteRecords = remember(snapshot.runId, selectedAnalyte?.analyteId) {
        selectedAnalyte?.let { analyte ->
            snapshot.analyteRecords(analyte.analyteId)
        }.orEmpty()
    }
    val outputDomainFailures = remember(selectedAnalyteRecords) {
        selectedAnalyteRecords.map { it.measurement }.filter { measurement ->
            measurement.qc.quantificationReason.equals(
                "OUTPUT_OUT_OF_DECLARED_RANGE",
                ignoreCase = true
            )
        }
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
            selectedAnalyte.rangeRecovery
                ?.takeIf { recovery ->
                    recovery.status != RangeRecoveryStatus.NOT_TRIGGERED
                }
                ?.let { recovery ->
                    item {
                        ArrayRangeRecoveryCard(
                            analyteId = selectedAnalyte.analyteId,
                            recovery = recovery
                        )
                    }
                }
            if (outputDomainFailures.isNotEmpty()) {
                item {
                    ArrayDeepLearningOutputWarningCard(
                        analyteId = selectedAnalyte.analyteId,
                        failures = outputDomainFailures,
                        total = selectedAnalyteRecords.size
                    )
                }
            }
            item {
                ArrayHeatmapResultCard(
                    title = heatmapTitle(heatmapModel, selectedAnalyte),
                    subtitle = heatmapSubtitle(heatmapModel, selectedAnalyte),
                    model = heatmapModel,
                    onScaleModeChange = { concentrationScaleMode = it },
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
            if (dualModalState !is DualModalUiState.Hidden) {
                item {
                    ArrayDualModalCard(
                        state = dualModalState,
                        analyte = selectedAnalyte,
                        actions = dualModalActions
                    )
                }
            }
            item {
                AnalyteSnapshotCard(
                    analyte = selectedAnalyte,
                    historicalRangeSemantics = heatmapModel.historicalConcentrationIncomplete
                )
            }
        }
    }
}

/**
 * 解释共享深度学习模型的逐孔输出离域。
 *
 * 卡片只读取运行时冻结的 QC，不重新推理，也不把离域百分比展示成浓度。默认只显示异常标题，
 * 用户主动展开后才呈现位点数量、声明范围和处置建议，避免结果首屏被诊断文字淹没。
 */
@Composable
private fun ArrayDeepLearningOutputWarningCard(
    analyteId: String,
    failures: List<ArraySiteMeasurementResult>,
    total: Int
) {
    val validCount = (total - failures.size).coerceAtLeast(0)
    val rawOutputs = failures.mapNotNull { it.qc.rawModelOutput?.takeIf(Double::isFinite) }
    val declaredMinimum = failures.mapNotNull {
        it.qc.declaredOutputMin?.takeIf(Double::isFinite)
    }.minOrNull()
    val declaredMaximum = failures.mapNotNull {
        it.qc.declaredOutputMax?.takeIf(Double::isFinite)
    }.maxOrNull()
    var expanded by rememberSaveable(
        analyteId,
        failures.size,
        total,
        rawOutputs.minOrNull(),
        rawOutputs.maxOrNull()
    ) { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_DEEP_LEARNING_OUTPUT_WARNING_TAG_PREFIX$analyteId"),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f)
        )
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = stringResource(R.string.array_deep_learning_output_warning_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = stringResource(
                        if (expanded) {
                            R.string.array_deep_learning_output_warning_collapse
                        } else {
                            R.string.array_deep_learning_output_warning_expand
                        }
                    ),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(FluoMotion.standard()) +
                    expandVertically(animationSpec = FluoMotion.standard()),
                exit = fadeOut(FluoMotion.standard()) +
                    shrinkVertically(animationSpec = FluoMotion.standard())
            ) {
                Column(
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (validCount > 0) {
                            stringResource(
                                R.string.array_deep_learning_output_warning_partial_body,
                                failures.size,
                                total,
                                validCount
                            )
                        } else {
                            stringResource(
                                R.string.array_deep_learning_output_warning_all_body,
                                failures.size,
                                total
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    if (
                        rawOutputs.isNotEmpty() &&
                        declaredMinimum != null &&
                        declaredMaximum != null
                    ) {
                        Text(
                            text = stringResource(
                                R.string.array_deep_learning_output_warning_range,
                                declaredMinimum,
                                declaredMaximum,
                                rawOutputs.min(),
                                rawOutputs.max()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f)
                        )
                    }
                    Text(
                        text = stringResource(R.string.array_deep_learning_output_warning_advice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f)
                    )
                }
            }
        }
    }
}

/**
 * 展示多数样品越界后的冻结批次决策。
 *
 * 卡片只解释已经写入运行快照的诊断或独立质控校正，不在结果页重新统计、重新校正，
 * 也不把未知样品分布包装成“自动提高准确度”。
 */
@Composable
private fun ArrayRangeRecoveryCard(
    analyteId: String,
    recovery: ArrayRangeRecoveryResult
) {
    val containerColor = when (recovery.status) {
        RangeRecoveryStatus.CORRECTION_APPLIED ->
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        RangeRecoveryStatus.CORRECTION_REJECTED ->
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)
        RangeRecoveryStatus.TRIGGERED_REVIEW_REQUIRED ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f)
        RangeRecoveryStatus.NOT_TRIGGERED -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when (recovery.status) {
        RangeRecoveryStatus.CORRECTION_APPLIED -> MaterialTheme.colorScheme.onPrimaryContainer
        RangeRecoveryStatus.CORRECTION_REJECTED -> MaterialTheme.colorScheme.onErrorContainer
        RangeRecoveryStatus.TRIGGERED_REVIEW_REQUIRED ->
            MaterialTheme.colorScheme.onTertiaryContainer
        RangeRecoveryStatus.NOT_TRIGGERED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val explanation = when (recovery.status) {
        RangeRecoveryStatus.CORRECTION_APPLIED ->
            stringResource(R.string.array_range_review_applied)
        RangeRecoveryStatus.CORRECTION_REJECTED ->
            stringResource(R.string.array_range_review_rejected)
        RangeRecoveryStatus.TRIGGERED_REVIEW_REQUIRED ->
            stringResource(R.string.array_range_review_required)
        RangeRecoveryStatus.NOT_TRIGGERED -> return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$ARRAY_RANGE_REVIEW_CARD_TAG_PREFIX$analyteId"),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.FactCheck,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stringResource(R.string.array_range_review_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
            Text(
                text = stringResource(
                    R.string.array_range_review_counts,
                    recovery.validSampleCount,
                    recovery.belowRangeCount,
                    recovery.aboveRangeCount,
                    recovery.outOfRangeRatio * 100.0
                ),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.88f)
            )
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
        shape = RoundedCornerShape(FluoRadius.card),
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
                shape = RoundedCornerShape(FluoRadius.control),
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
                    primaryFeatureLabel(analyte.primaryFeature)
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
    snapshot: ArrayResultSnapshot
) {
    var selectedAnalyteId by remember(snapshot.runId) {
        mutableStateOf(snapshot.analytes.firstOrNull()?.analyteId)
    }
    val selectedAnalyte = snapshot.analytes.firstOrNull { it.analyteId == selectedAnalyteId }
        ?: snapshot.analytes.firstOrNull()
    val selectedHeatmapModel = selectedAnalyte?.let { analyte ->
        remember(snapshot, analyte) { buildAnalyteHeatmapModel(snapshot, analyte) }
    }
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
                item {
                    AnalyteSnapshotCard(
                        analyte = selectedAnalyte,
                        historicalRangeSemantics =
                            selectedHeatmapModel?.historicalConcentrationIncomplete == true
                    )
                }
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
            }
        }
    }
}

/** 分析页展示冻结拟合质量，不重新选择模型或修改历史结果。 */
@Composable
private fun ArrayCurveMetricsCard(analyte: ArrayAnalyteResult) {
    val metrics = analyte.validationMetrics
    val acceptedRatio = metrics["ACCEPTED_STANDARD_RATIO"]
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
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
        shape = RoundedCornerShape(FluoRadius.badge),
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
        record.measurement.quantificationState.equals("BOUND_ONLY", ignoreCase = true) ||
            (
                record.measurement.concentrationValue != null &&
                    record.measurement.reliableRangeStatus in setOf("BELOW_RANGE", "ABOVE_RANGE")
                )
    }
    val signalOnly = records.count { record ->
        record.measurement.concentrationValue == null &&
            !record.measurement.quantificationState.equals("BOUND_ONLY", ignoreCase = true)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card)
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(FluoRadius.card)) {
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(FluoRadius.card)) {
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
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(FluoRadius.card)) {
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
                records.take(MAXIMUM_VISIBLE_SAMPLE_ROWS).forEach { record ->
                    val measurement = record.measurement
                    val concentration = measurement.concentrationValue
                    val rangeStatus = measurement.reliableRangeStatus?.uppercase()
                    val hasBoundary = measurement.quantificationState.equals(
                        "BOUND_ONLY",
                        ignoreCase = true
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSiteClick(
                                    ArraySiteSelection(record.siteIndex, analyte.analyteId)
                                )
                            },
                        shape = RoundedCornerShape(FluoRadius.control),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(FluoRadius.chip),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = record.siteKey,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.sampleSlot.orEmpty().ifBlank {
                                        stringResource(R.string.array_statistics_unnamed_sample)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = when {
                                        hasBoundary && rangeStatus == "BELOW_RANGE" -> stringResource(
                                            R.string.array_qc_site_below_range_title
                                        )
                                        hasBoundary && rangeStatus == "ABOVE_RANGE" -> stringResource(
                                            R.string.array_qc_site_above_range_title
                                        )
                                        rangeStatus in setOf("BELOW_RANGE", "ABOVE_RANGE") -> stringResource(
                                            R.string.array_statistics_extrapolated
                                        )
                                        rangeStatus in setOf(
                                            "BELOW_PROJECT_RANGE",
                                            "ABOVE_PROJECT_RANGE"
                                        ) -> stringResource(
                                            R.string.array_statistics_outside_project_range
                                        )
                                        else -> stringResource(R.string.array_statistics_sample_result)
                                    },
                                    /*
                                     * 深度学习的 BELOW/ABOVE_RANGE + BOUND_ONLY 是模型可靠范围
                                     * 外的单侧界限，不能继续套用标准曲线“已外推”的说明。
                                     */
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = sampleConcentrationResultText(measurement, analyte),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (concentration != null || hasBoundary) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
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

/** 样本表与单孔详情共用同一科学顺序：点浓度优先，其次单侧界限，最后才是未定量。 */
@Composable
private fun sampleConcentrationResultText(
    measurement: ArraySiteMeasurementResult,
    analyte: ArrayAnalyteResult
): String {
    val concentration = measurement.concentrationValue
    return when {
        concentration?.isFinite() == true -> stringResource(
            R.string.array_statistics_concentration_value,
            formatArrayHeatmapValue(concentration),
            analyte.concentrationUnit
        )
        measurement.censoringDirection.equals("UPPER_BOUND", ignoreCase = true) &&
            measurement.concentrationUpperBound?.isFinite() == true -> stringResource(
                R.string.array_site_concentration_below_boundary,
                formatArrayHeatmapValue(requireNotNull(measurement.concentrationUpperBound)),
                measurement.concentrationUnit ?: analyte.concentrationUnit
            )
        measurement.censoringDirection.equals("LOWER_BOUND", ignoreCase = true) &&
            measurement.concentrationLowerBound?.isFinite() == true -> stringResource(
                R.string.array_site_concentration_above_boundary,
                formatArrayHeatmapValue(requireNotNull(measurement.concentrationLowerBound)),
                measurement.concentrationUnit ?: analyte.concentrationUnit
            )
        else -> stringResource(R.string.array_statistics_not_quantified)
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

@Composable
private fun AnalyteSnapshotCard(
    analyte: ArrayAnalyteResult,
    historicalRangeSemantics: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card)
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
                    primaryFeatureLabel(analyte.primaryFeature),
                    analyte.concentrationUnit
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val projectMin = analyte.projectRangeMin
            val projectMax = analyte.projectRangeMax
            if (!historicalRangeSemantics && projectMin != null && projectMax != null) {
                Text(
                    text = stringResource(
                        R.string.array_result_project_range,
                        formatArrayHeatmapValue(projectMin),
                        formatArrayHeatmapValue(projectMax),
                        analyte.concentrationUnit
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (historicalRangeSemantics) {
                Text(
                    text = stringResource(R.string.array_result_legacy_project_range_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            val calibrationMin = analyte.calibrationRangeMin
            val calibrationMax = analyte.calibrationRangeMax
            if (calibrationMin != null && calibrationMax != null) {
                Text(
                    text = stringResource(
                        R.string.array_result_calibration_range,
                        formatArrayHeatmapValue(calibrationMin),
                        formatArrayHeatmapValue(calibrationMax),
                        analyte.concentrationUnit
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            ArrayHeatmapRangeSource.PROJECT_RANGE -> stringResource(
                R.string.array_heatmap_concentration_reliable_subtitle,
                analyte.concentrationUnit
            )
            ArrayHeatmapRangeSource.RUN_DISTRIBUTION -> stringResource(
                R.string.array_heatmap_concentration_observed_subtitle,
                analyte.concentrationUnit
            )
            else -> stringResource(
                R.string.array_heatmap_concentration_observed_subtitle,
                analyte.concentrationUnit
            )
        }
        ArrayHeatmapScaleMode.PRIMARY_FEATURE -> {
            val subtitle = when {
                model.historicalConcentrationIncomplete &&
                    AnalysisModelType.fromCode(analyte.modelType) ==
                    AnalysisModelType.DEEP_LEARNING ->
                    R.string.array_heatmap_historical_deep_learning_signal_subtitle
                model.historicalConcentrationIncomplete ->
                    R.string.array_heatmap_historical_signal_subtitle
                else -> R.string.array_heatmap_signal_subtitle
            }
            stringResource(subtitle, primaryFeatureLabel(analyte.primaryFeature))
        }
        ArrayHeatmapScaleMode.OVERVIEW_RELIABILITY -> stringResource(
            R.string.array_heatmap_overview_subtitle
        )
    }
}

/** 标题必须与当前色带一致，历史兼容信号图不能继续误称为浓度“结果热力图”。 */
@Composable
private fun heatmapTitle(model: ArrayHeatmapModel, analyte: ArrayAnalyteResult): String {
    return stringResource(
        if (model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION) {
            R.string.array_heatmap_analyte_concentration_title
        } else {
            R.string.array_heatmap_analyte_signal_title
        },
        analyte.name
    )
}

@Composable
internal fun scaleLegendTitle(scale: ArrayHeatmapScale, unitSuffix: String): String {
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
        CaptureRole.PROCESS_ORIENTATION_NORMALIZED,
        CaptureRole.PROCESS_YOLO_OVERLAY,
        CaptureRole.PROCESS_HOUGH_CIRCLE_OVERLAY,
        CaptureRole.PROCESS_ORIGINAL_PROJECTION_OVERLAY,
        CaptureRole.PROCESS_CROP_CONTACT_SHEET,
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
        shape = RoundedCornerShape(FluoRadius.card),
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
    onRetry: (() -> Unit)?,
    onBack: () -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.back))
                }
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}

private fun ArrayResultTab.titleResource(): Int = when (this) {
    ArrayResultTab.OVERVIEW -> R.string.array_result_tab_overview
    ArrayResultTab.ANALYSIS -> R.string.array_result_tab_analytes
    ArrayResultTab.PROCESS -> R.string.array_result_tab_process
}

/** 顶栏只显示便于辨认的运行 ID 尾段，完整 ID 仍在历史面板中保留。 */
private fun shortRunId(runId: String): String {
    return if (runId.length <= 12) runId else runId.takeLast(12)
}
