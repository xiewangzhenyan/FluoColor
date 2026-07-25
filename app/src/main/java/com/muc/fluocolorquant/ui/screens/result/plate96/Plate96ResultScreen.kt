package com.muc.fluocolorquant.ui.screens.result.plate96

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Brush
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
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.plate96.Plate96ResultSnapshot
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapCell
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.ArrayHeatmapValueState
import com.muc.fluocolorquant.ui.screens.result.array.ArrayResultExportCoordinator
import com.muc.fluocolorquant.ui.screens.result.array.arrayResultPdfLabels
import com.muc.fluocolorquant.ui.screens.result.array.buildAnalyteHeatmapModel
import com.muc.fluocolorquant.ui.screens.result.array.formatArrayHeatmapValue
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
    var selectedCell by remember(snapshot.runId) { mutableStateOf<ArrayHeatmapCell?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val analyte = snapshot.arraySnapshot.analytes.firstOrNull { it.analyteId == selectedAnalyteId }
        ?: snapshot.arraySnapshot.analytes.firstOrNull()
    val tabs = Plate96ResultTab.entries

    Scaffold(
        modifier = Modifier.testTag(PLATE96_RESULT_SCREEN_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(snapshot.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = stringResource(
                                R.string.plate_result_subtitle_format,
                                snapshot.arraySnapshot.rows,
                                snapshot.arraySnapshot.columns
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
                        onClick = { showExport = true },
                        modifier = Modifier.testTag(PLATE96_RESULT_EXPORT_TAG)
                    ) {
                        Icon(
                            Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.array_export_open)
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
            Plate96ResultSummaryBar(snapshot)
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
            when (tabs[selectedTab]) {
                Plate96ResultTab.RESULT -> analyte?.let { selectedAnalyte ->
                    Plate96OverviewContent(
                        snapshot = snapshot,
                        analyte = selectedAnalyte,
                        onWellClick = { selectedCell = it }
                    )
                } ?: Plate96CenteredState(
                    text = stringResource(R.string.plate96_result_no_analyte),
                    showProgress = false,
                    onRetry = null
                )
                Plate96ResultTab.ANALYSIS -> analyte?.let { selectedAnalyte ->
                    Plate96AnalysisContent(
                        snapshot = snapshot,
                        analyte = selectedAnalyte,
                        onWellClick = { wellIndex ->
                            val model = buildAnalyteHeatmapModel(snapshot.arraySnapshot, selectedAnalyte)
                            selectedCell = model.cells.firstOrNull { it.siteIndex == wellIndex }
                        }
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

    if (analyte != null) {
        selectedCell?.let { cell ->
            snapshot.wells.firstOrNull { it.wellIndex == cell.siteIndex }?.let { well ->
                Plate96WellDetailSheet(
                    well = well,
                    analyte = analyte,
                    valueState = cell.valueState,
                    onDismiss = { selectedCell = null }
                )
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
private fun Plate96ResultSummaryBar(snapshot: Plate96ResultSnapshot) {
    val measured = snapshot.wells.count { it.site.measurements.isNotEmpty() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Plate96SummaryItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.GridView,
                value = stringResource(
                    R.string.plate_result_layout_format,
                    snapshot.arraySnapshot.rows,
                    snapshot.arraySnapshot.columns
                ),
                label = stringResource(R.string.plate96_result_wells)
            )
            Plate96SummaryItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Science,
                value = snapshot.arraySnapshot.analytes.size.toString(),
                label = stringResource(R.string.plate96_result_summary_analytes)
            )
            Plate96SummaryItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.TaskAlt,
                value = measured.toString(),
                label = stringResource(R.string.plate96_result_summary_measured)
            )
        }
    }
}

@Composable
private fun Plate96SummaryItem(
    modifier: Modifier,
    icon: ImageVector,
    value: String,
    label: String
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(7.dp).size(17.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Plate96OverviewContent(
    snapshot: Plate96ResultSnapshot,
    analyte: ArrayAnalyteResult,
    onWellClick: (ArrayHeatmapCell) -> Unit
) {
    var displayMode by rememberSaveable(snapshot.runId, analyte.analyteId) {
        mutableStateOf(Plate96ResultDisplayMode.HEATMAP)
    }
    val model = remember(snapshot.runId, analyte.analyteId) {
        buildAnalyteHeatmapModel(snapshot.arraySnapshot, analyte)
    }
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(analyte.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = stringResource(
                            if (model.scale.mode.name == "CONCENTRATION") {
                                R.string.plate96_result_concentration_heatmap
                            } else {
                                R.string.plate96_result_signal_heatmap
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SingleChoiceSegmentedButtonRow {
                    Plate96ResultDisplayMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = displayMode == mode,
                            onClick = { displayMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, Plate96ResultDisplayMode.entries.size),
                            label = {
                                Text(
                                    stringResource(
                                        if (mode == Plate96ResultDisplayMode.HEATMAP) {
                                            R.string.plate96_result_view_heatmap
                                        } else R.string.plate96_result_view_values
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Plate96Heatmap(model, displayMode, onWellClick)
                    Plate96ScaleLegend(model)
                    Plate96StateLegend(model)
                }
            }
        }
        item { Plate96ResultMetrics(model) }
        item {
            Text(
                text = stringResource(R.string.plate96_result_open_analysis_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun Plate96ScaleLegend(model: ArrayHeatmapModel) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(
                    Brush.horizontalGradient(HeatmapColorUtil.getLegendColors(7)),
                    RoundedCornerShape(50)
                )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatArrayHeatmapValue(model.scale.minimum), style = MaterialTheme.typography.labelSmall)
            Text(model.scale.unit, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Text(formatArrayHeatmapValue(model.scale.maximum), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Plate96StateLegend(model: ArrayHeatmapModel) {
    val items = buildList {
        if (model.calibrationExtrapolatedCount > 0) add(R.string.plate96_result_legend_extrapolated)
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.BELOW_PROJECT_RANGE }) {
            add(R.string.plate96_result_legend_below)
        }
        if (model.cells.any { it.valueState == ArrayHeatmapValueState.ABOVE_PROJECT_RANGE }) {
            add(R.string.plate96_result_legend_above)
        }
        if (model.missingCount > 0) add(R.string.plate96_result_legend_unavailable)
    }
    if (items.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            items.take(4).forEach { label ->
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Plate96ResultMetrics(model: ArrayHeatmapModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Plate96MetricCard(
            modifier = Modifier.weight(1f),
            value = model.calculatedCount.toString(),
            label = stringResource(R.string.plate96_result_calculated)
        )
        Plate96MetricCard(
            modifier = Modifier.weight(1f),
            value = model.withinCalibrationRangeCount.toString(),
            label = stringResource(R.string.plate96_result_within_curve)
        )
        Plate96MetricCard(
            modifier = Modifier.weight(1f),
            value = model.outsideProjectRangeCount.toString(),
            label = stringResource(R.string.plate96_result_outside_range)
        )
    }
}

@Composable
private fun Plate96MetricCard(modifier: Modifier, value: String, label: String) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Plate96CenteredState(text: String, showProgress: Boolean, onRetry: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showProgress) CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (onRetry != null) Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

private fun Plate96ResultTab.titleRes(): Int = when (this) {
    Plate96ResultTab.RESULT -> R.string.plate96_result_tab_result
    Plate96ResultTab.ANALYSIS -> R.string.plate96_result_tab_analysis
    Plate96ResultTab.VALIDATION -> R.string.plate96_result_tab_validation
    Plate96ResultTab.PROCESS -> R.string.plate96_result_tab_process
}
