@file:OptIn(ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.components.FluoIconBadge
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LatexView
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryEvent
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryItem
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryViewModel
import com.muc.fluocolorquant.utils.math.FittingEngine
import java.util.Locale

/**
 * 标准曲线库（集成右上角「拟合规则」快捷入口，统一承载全站标准曲线管理与预览）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandardCurveLibraryScreen(
    navController: NavController,
    viewModel: StandardCurveLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val toastManager = LocalToastManager.current
    var pendingDelete by remember { mutableStateOf<StandardCurveLibraryItem?>(null) }
    var selectedAnalyteFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val deletedMessage = stringResource(R.string.standard_curve_deleted)
    val deleteFailedMessage = stringResource(R.string.standard_curve_delete_failed)

    LaunchedEffect(viewModel, toastManager) {
        viewModel.events.collect { event ->
            when (event) {
                StandardCurveLibraryEvent.Deleted ->
                    toastManager.showToast(deletedMessage, ToastType.SUCCESS)
                StandardCurveLibraryEvent.DeleteFailed ->
                    toastManager.showToast(deleteFailedMessage, ToastType.ERROR)
            }
        }
    }

    val distinctAnalytes = remember(state.items) {
        state.items.mapNotNull { it.analyteName.takeIf(String::isNotBlank) }.distinct()
    }
    val filteredItems = remember(state.items, selectedAnalyteFilter) {
        if (selectedAnalyteFilter == null) {
            state.items
        } else {
            state.items.filter { it.analyteName == selectedAnalyteFilter }
        }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.standard_curve_library_title),
                onBack = { navController.navigateUp() },
                actions = {
                    TextButton(onClick = { navController.navigate(Screen.CalibrationSettings.route) }) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.standard_curve_rules_action))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Screen.ManualDataInput.createRoute()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.standard_curve_unified_title)) }
            )
        }
    ) { innerPadding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.items.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                FluoStatePlaceholder(
                    icon = Icons.Default.Analytics,
                    text = stringResource(R.string.standard_curve_library_empty_title),
                    supportingText = stringResource(R.string.standard_curve_library_empty_desc),
                    actionText = stringResource(R.string.standard_curve_unified_title),
                    onAction = { navController.navigate(Screen.ManualDataInput.createRoute()) }
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "curve_summary") {
                    Text(
                        text = stringResource(R.string.standard_curve_library_summary, state.items.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (distinctAnalytes.size >= 2) {
                    item(key = "analyte_filters") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedAnalyteFilter == null,
                                onClick = { selectedAnalyteFilter = null },
                                label = { Text(stringResource(R.string.resource_filter_all)) }
                            )
                            distinctAnalytes.forEach { name ->
                                FilterChip(
                                    selected = selectedAnalyteFilter == name,
                                    onClick = {
                                        selectedAnalyteFilter = if (selectedAnalyteFilter == name) null else name
                                    },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }

                items(filteredItems, key = { it.model.id }) { item ->
                    StandardCurveLibraryCard(
                        item = item,
                        deleting = state.deletingModelId == item.model.id,
                        onEdit = {
                            navController.navigate(Screen.ManualDataInput.createRoute(item.model.id))
                        },
                        onDelete = { pendingDelete = item }
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.standard_curve_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.model.name, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.standard_curve_delete_message))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(item.model.id)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun StandardCurveLibraryCard(
    item: StandardCurveLibraryItem,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable(item.model.id) { mutableStateOf(false) }
    val channelAccent = item.fluorescenceChannel?.let { ch ->
        when (ch) {
            FluorescenceChannel.RED -> Color(0xFFE53935)
            FluorescenceChannel.GREEN -> Color(0xFF2E7D32)
            FluorescenceChannel.BLUE -> Color(0xFF1E88E5)
            FluorescenceChannel.GRAY -> Color(0xFF607D8B)
        }
    } ?: MaterialTheme.colorScheme.primary

    val latexFormula = remember(item.fittingFunction, item.parameters) {
        val fn = item.fittingFunction ?: return@remember null
        if (item.parameters.isNotEmpty()) {
            runCatching { FittingEngine.formatParametersToLatex(fn, item.parameters) }.getOrNull()
        } else {
            fn.latexFormula
        }
    }

    FluoSectionCard(
        modifier = Modifier.clickable { expanded = !expanded },
        contentPadding = FluoSpacing.md,
        verticalSpacing = 9.dp,
        accentColor = channelAccent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FluoIconBadge(
                icon = Icons.Default.Analytics,
                accentColor = channelAccent
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.model.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.analyteName.isNotBlank()) {
                    Text(
                        text = item.analyteName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand)
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            CurveMetaBadge(
                text = detectionModeLabel(item.modality),
                container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                content = MaterialTheme.colorScheme.onPrimaryContainer
            )
            item.fluorescenceChannel?.let { ch ->
                val chLabel = stringResource(
                    when (ch) {
                        FluorescenceChannel.RED -> R.string.analyte_channel_red_short
                        FluorescenceChannel.GREEN -> R.string.analyte_channel_green_short
                        FluorescenceChannel.BLUE -> R.string.analyte_channel_blue_short
                        FluorescenceChannel.GRAY -> R.string.analyte_channel_gray_short
                    }
                )
                Surface(
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = channelAccent.copy(alpha = 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(channelAccent, CircleShape)
                        )
                        Text(
                            text = chLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = channelAccent,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            item.fittingFunction?.let { fn ->
                CurveMetaBadge(
                    text = functionLabel(fn),
                    container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    content = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            item.rSquared?.let { r2 ->
                CurveMetaBadge(
                    text = String.format(Locale.US, "R² = %.4f", r2),
                    container = Color(0xFF0D9488).copy(alpha = 0.14f),
                    content = Color(0xFF0F766E)
                )
            }
            CurveMetaBadge(
                text = analysisFeatureLabel(item.feature),
                container = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(
                        R.string.standard_curve_library_range,
                        item.model.reliableRangeMin.compactNumber(),
                        item.model.reliableRangeMax.compactNumber(),
                        item.model.concentrationUnit
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (item.carrierTypes.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.standard_curve_library_carriers,
                            carrierSummary(item.carrierTypes)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, enabled = !deleting, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = onDelete, enabled = !deleting, modifier = Modifier.size(40.dp)) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && (latexFormula != null || item.standardPointCount > 0),
            enter = FluoMotion.expandEnter,
            exit = FluoMotion.expandExit
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (item.standardPointCount > 0) {
                    Text(
                        text = stringResource(R.string.standard_curve_points_summary, item.standardPointCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                latexFormula?.let { formula ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(FluoRadius.badge))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LatexView(
                            latex = formula,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CurveMetaBadge(
    text: String,
    container: Color,
    content: Color
) {
    Surface(
        shape = RoundedCornerShape(FluoRadius.badge),
        color = container
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun detectionModeLabel(modality: DetectionModality): String = when (modality) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.colorimetric_mode_short)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.fluorescence_mode_short)
    DetectionModality.SPECTRUM -> stringResource(R.string.spectrum_mode_short)
}

@Composable
private fun carrierSummary(carriers: Set<CarrierType>): String {
    val plateLabel = stringResource(R.string.analysis_model_carrier_plate)
    val chipLabel = stringResource(R.string.analysis_model_carrier_chip)
    val customLabel = stringResource(R.string.analysis_model_carrier_custom)
    return carriers.sortedBy(CarrierType::code).joinToString(separator = ", ") { carrier ->
        when (carrier) {
            CarrierType.PLATE -> plateLabel
            CarrierType.MICROFLUIDIC_CHIP -> chipLabel
            CarrierType.CUSTOM -> customLabel
        }
    }
}

private fun Double.compactNumber(): String = when {
    this == 0.0 -> "0"
    this % 1.0 == 0.0 -> toLong().toString()
    else -> String.format(Locale.ROOT, "%.6g", this)
}
