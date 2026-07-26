package com.muc.fluocolorquant.ui.screens.result.plate96

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.ui.components.FluoNumericText
import com.muc.fluocolorquant.ui.components.FluoScientificMetric
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapCell
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapRangeSource
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapScaleMode
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.ArrayResultExportCoordinator
import com.muc.fluocolorquant.ui.screens.result.array.arrayResultPdfLabels
import com.muc.fluocolorquant.ui.screens.result.ResultQualityBar
import com.muc.fluocolorquant.ui.screens.result.array.buildAnalyteHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.buildResultQualitySummary
import com.muc.fluocolorquant.ui.screens.result.array.buildSignalDistribution
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.Plate96ResultUiState
import com.muc.fluocolorquant.ui.viewmodels.Plate96ResultViewModel
import com.muc.fluocolorquant.utils.HeatmapColorUtil

const val PLATE96_RESULT_SCREEN_TAG: String = "plate96_result_screen"
const val PLATE96_RESULT_EXPORT_TAG: String = "plate96_result_export"
const val PLATE96_RESULT_ANALYSIS_TAB_TAG: String = "plate96_result_tab_analysis"
const val PLATE96_RESULT_VALIDATION_TAB_TAG: String = "plate96_result_tab_validation"
const val PLATE96_RESULT_PROCESS_TAB_TAG: String = "plate96_result_tab_process"

private enum class Plate96ResultTab(val icon: ImageVector) {
    RESULT(Icons.Outlined.GridView),
    ANALYSIS(Icons.Outlined.Analytics),
    VALIDATION(Icons.AutoMirrored.Outlined.FactCheck),
    PROCESS(Icons.Outlined.Timeline)
}

@Composable
fun Plate96ResultScreen(
    navController: NavController,
    runId: String?,
    viewModel: Plate96ResultViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(runId) { viewModel.load(runId) }
    Plate96ResultContent(
        state = state,
        onBack = {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Home.route) { inclusive = true }
                launchSingleTop = true
            }
        },
        onRetry = viewModel::retry,
        onSaveValidation = viewModel::saveValidation
    )
}

@Composable
fun Plate96ResultContent(
    state: Plate96ResultUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSaveValidation: (String, Map<Int, Double>) -> Unit = { _, _ -> }
) {
    when (state) {
        Plate96ResultUiState.Loading -> Plate96CenteredState(
            text = stringResource(R.string.plate96_result_loading),
            showProgress = true,
            onRetry = null
        )
        Plate96ResultUiState.NotFound -> Plate96CenteredState(
            text = stringResource(R.string.plate96_result_not_found),
            showProgress = false,
            onRetry = null
        )
        is Plate96ResultUiState.InvalidSnapshot -> Plate96CenteredState(
            text = stringResource(R.string.plate96_result_invalid_snapshot),
            showProgress = false,
            onRetry = null
        )
        Plate96ResultUiState.Error -> Plate96CenteredState(
            text = stringResource(R.string.plate96_result_load_failed),
            showProgress = false,
            onRetry = onRetry
        )
        is Plate96ResultUiState.Success -> Plate96ResultSuccess(
            state = state,
            onBack = onBack,
            onSaveValidation = onSaveValidation
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Plate96ResultSuccess(
    state: Plate96ResultUiState.Success,
    onBack: () -> Unit,
    onSaveValidation: (String, Map<Int, Double>) -> Unit
) {
    val snapshot = state.snapshot
    var selectedTab by rememberSaveable(snapshot.runId) { mutableIntStateOf(0) }
    var selectedAnalyteId by rememberSaveable(snapshot.runId) {
        mutableStateOf(snapshot.arraySnapshot.analytes.firstOrNull()?.analyteId)
    }
    var selectedWellIndex by rememberSaveable(snapshot.runId) { mutableStateOf<Int?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val analyte = snapshot.arraySnapshot.analytes.firstOrNull { it.analyteId == selectedAnalyteId }
        ?: snapshot.arraySnapshot.analytes.firstOrNull()
    val tabs = Plate96ResultTab.entries

    // 切换分析物后清除旧孔位选择，避免把上一分析物的状态错误显示在当前结果中。
    LaunchedEffect(analyte?.analyteId) { selectedWellIndex = null }

    Scaffold(
        modifier = Modifier.testTag(PLATE96_RESULT_SCREEN_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            FluoTopBar(
                title = snapshot.projectName,
                subtitle = stringResource(
                    R.string.plate_result_subtitle_format,
                    snapshot.arraySnapshot.rows,
                    snapshot.arraySnapshot.columns
                ),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { showExport = true },
                        modifier = Modifier.testTag(PLATE96_RESULT_EXPORT_TAG)
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.array_export_open)
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
            // 运行质量裁决固定在顶栏下方、四个一级页之上：用户翻到"过程"页核对证据时，
            // 仍能同时看到"这批数据能不能用"的总判定。裁决按当前分析物计算——同一次运行
            // 里不同分析物的曲线质量可以差别很大，用整版一个判定会掩盖问题。
            analyte?.let { current ->
                val qualityModel = remember(snapshot.runId, current.analyteId) {
                    buildAnalyteHeatmapModel(snapshot.arraySnapshot, current)
                }
                val qualitySummary = remember(snapshot.runId, current.analyteId) {
                    buildResultQualitySummary(snapshot.arraySnapshot, current, qualityModel)
                }
                ResultQualityBar(
                    summary = qualitySummary,
                    formatValue = ::formatArrayHeatmapValue
                )
            }
            if (snapshot.arraySnapshot.analytes.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = snapshot.arraySnapshot.analytes,
                        key = ArrayAnalyteResult::analyteId
                    ) { option ->
                        FilterChip(
                            selected = option.analyteId == analyte?.analyteId,
                            onClick = { selectedAnalyteId = option.analyteId },
                            label = { Text(option.name, maxLines = 1) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        )
                    }
                }
            }
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selectedTab,
                        onClick = { selectedTab = index },
                        modifier = Modifier.testTag(
                            when (tab) {
                                Plate96ResultTab.ANALYSIS -> PLATE96_RESULT_ANALYSIS_TAB_TAG
                                Plate96ResultTab.VALIDATION -> PLATE96_RESULT_VALIDATION_TAB_TAG
                                Plate96ResultTab.PROCESS -> PLATE96_RESULT_PROCESS_TAB_TAG
                                Plate96ResultTab.RESULT -> "plate96_result_tab_result"
                            }
                        ),
                        text = { Text(stringResource(tab.titleRes()), maxLines = 1) },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }
            // 四个一级页共用同一块区域，切换时用横向滑动 + 淡入表达"同层平移"关系：
            // 向右选择标签时新内容自右进入，向左选择时相反，方向与标签顺序一致。
            // 不使用缩放或纵向位移，避免热力图和曲线在过渡期间出现视觉形变。
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState > initialState
                    val offset: (Int) -> Int = { width ->
                        if (forward) width / 6 else -width / 6
                    }
                    val exitOffset: (Int) -> Int = { width ->
                        if (forward) -width / 6 else width / 6
                    }
                    (slideInHorizontally(
                        animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Standard),
                        initialOffsetX = offset
                    ) + fadeIn(
                        animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Decelerate)
                    )).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(FluoMotion.STANDARD_MS, easing = FluoMotion.Standard),
                            targetOffsetX = exitOffset
                        ) + fadeOut(
                            animationSpec = tween(FluoMotion.MICRO_MS, easing = FluoMotion.Accelerate)
                        )
                    )
                },
                label = "plate96ResultTabContent"
            ) { tabIndex ->
                when (tabs[tabIndex]) {
                    Plate96ResultTab.RESULT -> analyte?.let { selectedAnalyte ->
                        Plate96OverviewContent(
                            snapshot = snapshot,
                            analyte = selectedAnalyte,
                            selectedWellIndex = selectedWellIndex,
                            onWellClick = { cell ->
                                selectedWellIndex = if (selectedWellIndex == cell.siteIndex) {
                                    null
                                } else {
                                    cell.siteIndex
                                }
                            },
                            onClearWellSelection = { selectedWellIndex = null }
                        )
                    } ?: Plate96CenteredState(
                        text = stringResource(R.string.plate96_result_no_analyte),
                        showProgress = false,
                        onRetry = null
                    )
                    Plate96ResultTab.ANALYSIS -> analyte?.let { selectedAnalyte ->
                        Plate96AnalysisContent(
                            snapshot = snapshot,
                            analyte = selectedAnalyte
                        )
                    }
                    Plate96ResultTab.VALIDATION -> analyte?.let { selectedAnalyte ->
                        Plate96ValidationContent(
                            snapshot = snapshot,
                            analyte = selectedAnalyte,
                            validation = state.validations[selectedAnalyte.analyteId],
                            saving = state.validationSavingAnalyteId == selectedAnalyte.analyteId,
                            saveFailed = state.validationSaveFailed,
                            onSave = { values ->
                                onSaveValidation(selectedAnalyte.analyteId, values)
                            }
                        )
                    }
                    Plate96ResultTab.PROCESS -> Plate96ProcessingContent(snapshot)
                }
            }
        }
    }

    ArrayResultExportCoordinator(
        snapshot = snapshot.arraySnapshot,
        visible = showExport,
        onDismiss = { showExport = false },
        selectedAnalyteId = analyte?.analyteId,
        validations = state.validations,
        pdfLabelsOverride = arrayResultPdfLabels().copy(
            documentTitle = stringResource(R.string.plate96_pdf_document_title),
            overviewHeatmap = stringResource(R.string.plate96_pdf_overview_heatmap),
            physicalSites = stringResource(R.string.plate96_pdf_physical_wells),
            analyteSection = stringResource(R.string.plate96_pdf_analyte_section)
        )
    )
}

@Composable
private fun Plate96OverviewContent(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    selectedWellIndex: Int?,
    onWellClick: (ArrayHeatmapCell) -> Unit,
    onClearWellSelection: () -> Unit
) {
    var displayMode by rememberSaveable(snapshot.runId, analyte.analyteId) {
        mutableStateOf(Plate96ResultDisplayMode.HEATMAP)
    }
    val model = remember(snapshot.runId, analyte.analyteId) {
        buildAnalyteHeatmapModel(snapshot.arraySnapshot, analyte)
    }
    val selectedCell = selectedWellIndex?.let { index ->
        model.cells.firstOrNull { it.siteIndex == index }
    }
    val selectedWell = selectedWellIndex?.let { index ->
        snapshot.wells.firstOrNull { it.wellIndex == index }
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = analyte.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            if (model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION) {
                                R.string.plate96_result_concentration_heatmap
                            } else {
                                R.string.plate96_result_signal_heatmap
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Plate96DisplayModeToggle(
                    displayMode = displayMode,
                    onDisplayModeChange = { displayMode = it }
                )
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(FluoRadius.card),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Plate96Heatmap(
                        model = model,
                        displayMode = displayMode,
                        onWellClick = onWellClick,
                        selectedSiteIndex = selectedWellIndex
                    )
                    Plate96ScaleLegend(model)
                    Plate96StateLegend(model)
                }
            }
        }
        item { Plate96ScientificMetrics(snapshot = snapshot, analyte = analyte, model = model) }
        if (selectedCell != null && selectedWell != null) {
            item(key = "selected-well-${selectedWell.wellIndex}") {
                Plate96InlineWellDetailCard(
                    snapshot = snapshot,
                    well = selectedWell,
                    analyte = analyte,
                    valueState = selectedCell.valueState,
                    scaleMode = model.scale.mode,
                    onDismiss = onClearWellSelection
                )
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(FluoRadius.badge),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = stringResource(
                        if (model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION) {
                            R.string.plate96_result_note
                        } else {
                            R.string.plate96_result_signal_note
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/**
 * 结果页专用的紧凑双态切换器。
 *
 * Material默认分段按钮在英文界面占宽过大，会挤压分析物标题；这里保留同样的选中语义，
 * 但使用科研仪器常见的短标签胶囊，确保360dp中英文首屏都保持一行。
 */
@Composable
private fun Plate96DisplayModeToggle(
    displayMode: Plate96ResultDisplayMode,
    onDisplayModeChange: (Plate96ResultDisplayMode) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(132.dp)
            .height(38.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Plate96ResultDisplayMode.entries.forEach { mode ->
                Surface(
                    onClick = { onDisplayModeChange(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    color = if (displayMode == mode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (mode == Plate96ResultDisplayMode.HEATMAP) {
                                R.string.plate96_result_view_heatmap_short
                            } else {
                                R.string.plate96_result_view_values
                            }
                        ),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 浓度/信号色带图例。
 *
 * 原实现只有"最小值 | 单位 | 最大值"三个标签，中间大片色带没有任何刻度——用户看到一格
 * 偏橙的孔，无法判断它대概是多少。这里补到五个等距刻度，并单独标出色带覆盖范围的来源
 * （项目量程 / 实测范围 / 标定范围），让颜色可以被读成数值而不只是"深浅"。
 *
 * 刻度按线性插值：热力图着色本身就是线性归一化，刻度必须与之一致，否则读数会系统性偏移。
 */
@Composable
private fun Plate96ScaleLegend(model: ArrayHeatmapModel) {
    val scale = model.scale
    val ticks = remember(scale.minimum, scale.maximum) {
        val span = scale.maximum - scale.minimum
        if (!span.isFinite() || span <= 0.0) {
            listOf(scale.minimum)
        } else {
            (0..4).map { index -> scale.minimum + span * index / 4.0 }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(FluoSpacing.xs)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(
                    Brush.horizontalGradient(HeatmapColorUtil.getLegendColors(7)),
                    RoundedCornerShape(FluoRadius.chip)
                )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ticks.forEach { tick ->
                FluoNumericText(
                    text = formatArrayHeatmapValue(tick),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 单位与色带范围来源单独一行：单位属于整条色带而不是某个刻度，
        // 放在刻度之间会被误读成"该刻度的单位"。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = scale.unit,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(scale.rangeSource.labelRes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun Plate96StateLegend(model: ArrayHeatmapModel) {
    val items = buildList<Pair<ImageVector, Int>> {
        if (model.calibrationExtrapolatedCount > 0) {
            add(Icons.Outlined.TrendingUp to R.string.plate96_result_legend_extrapolated)
        }
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.BELOW_PROJECT_RANGE }) {
            add(Icons.Outlined.ArrowDownward to R.string.plate96_result_legend_below)
        }
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE }) {
            add(Icons.Outlined.ArrowUpward to R.string.plate96_result_legend_above)
        }
        if (model.missingCount > 0) {
            add(Icons.Outlined.RemoveCircleOutline to R.string.plate96_result_legend_unavailable)
        }
    }
    if (items.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.take(4).forEach { (icon, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 首屏科学指标带。
 *
 * 原实现是"已定量 / 估算 / 需复测"三个计数卡——那是处理状态统计，不是科学结论。
 * 研究者扫一眼结果页要得到的是量级与离散度：测出来多少、集中在哪、重复得怎么样。
 * 计数信息降级到裁决条的支撑数字行，不再占据首屏黄金位置。
 *
 * 仅信号运行没有浓度，指标换成信号分布并使用信号特征名作单位；绝不复用浓度标签，
 * 避免把原始信号伪装成浓度（AGENTS.md 11）。
 */
@Composable
private fun Plate96ScientificMetrics(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    model: ArrayHeatmapModel
) {
    val concentrationMode = model.scale.mode == ArrayHeatmapScaleMode.CONCENTRATION
    val summary = remember(snapshot.runId, analyte.analyteId) {
        buildResultQualitySummary(snapshot.arraySnapshot, analyte, model)
    }
    val signal = remember(snapshot.runId, analyte.analyteId) {
        buildSignalDistribution(model)
    }

    val minimum = if (concentrationMode) summary.concentrationMinimum else signal.minimum
    val maximum = if (concentrationMode) summary.concentrationMaximum else signal.maximum
    val median = if (concentrationMode) summary.concentrationMedian else signal.median
    val unit = model.scale.unit.takeIf(String::isNotBlank)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(FluoSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.lg)
        ) {
            FluoScientificMetric(
                modifier = Modifier.weight(1f),
                label = stringResource(
                    if (concentrationMode) R.string.result_metric_range
                    else R.string.result_metric_signal_range
                ),
                value = if (minimum != null && maximum != null) {
                    stringResource(
                        R.string.result_metric_range_format,
                        formatArrayHeatmapValue(minimum),
                        formatArrayHeatmapValue(maximum)
                    )
                } else {
                    null
                },
                unit = unit
            )
            FluoScientificMetric(
                modifier = Modifier.weight(1f),
                label = stringResource(
                    if (concentrationMode) R.string.result_metric_median
                    else R.string.result_metric_signal_median
                ),
                value = median?.let(::formatArrayHeatmapValue),
                unit = unit
            )
            // 重复孔 CV 只在浓度模式下有意义：信号的重复性受曝光与背景影响，
            // 与浓度重复性不是同一回事，不放在同一格里比较。
            if (concentrationMode) {
                FluoScientificMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.result_metric_replicate_cv),
                    value = summary.repeatCvPercent?.let(::formatArrayHeatmapValue),
                    unit = "%"
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Plate96MetricCard(
    modifier: Modifier,
    value: String,
    label: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 加载 / 空数据 / 失败共用同一占位实现，与其他页面保持一致的状态反馈。
 *
 * 非加载态显示明确图标而不是只有一行文字：用户需要立刻区分"还在算"和"没有结果"。
 */
@Composable
private fun Plate96CenteredState(text: String, showProgress: Boolean, onRetry: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        FluoStatePlaceholder(
            text = text,
            icon = if (showProgress) null else Icons.Outlined.Info,
            actionText = onRetry?.let { stringResource(R.string.action_retry) },
            onAction = onRetry
        )
    }
}

private fun Plate96ResultTab.titleRes(): Int = when (this) {
    Plate96ResultTab.RESULT -> R.string.plate96_result_tab_result
    Plate96ResultTab.ANALYSIS -> R.string.plate96_result_tab_analysis
    Plate96ResultTab.VALIDATION -> R.string.plate96_result_tab_validation
    Plate96ResultTab.PROCESS -> R.string.plate96_result_tab_process
}

/** 色带覆盖范围的来源。让用户知道颜色是按项目量程还是按实测极值归一化的。 */
private fun ArrayHeatmapRangeSource.labelRes(): Int = when (this) {
    ArrayHeatmapRangeSource.FIXED_PERCENTAGE -> R.string.result_scale_source_fixed
    ArrayHeatmapRangeSource.RELIABLE_RANGE -> R.string.result_scale_source_range
    ArrayHeatmapRangeSource.OBSERVED_VALUES -> R.string.result_scale_source_observed
    ArrayHeatmapRangeSource.EMPTY_FALLBACK -> R.string.result_scale_source_empty
}
