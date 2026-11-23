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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.ui.components.FluoIconBadge
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoStatePlaceholder
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.ExperimentTemplateViewModel
import com.muc.fluocolorquant.ui.viewmodels.TemplateAnalyteSummaryItem
import com.muc.fluocolorquant.ui.viewmodels.TemplateLibraryCardModel

/**
 * 轻量化重构后的实验模板库。
 *
 * 告别繁琐的五步协议向导与草稿版本锁：清晰展示每套方案的载体规格、分析物通道、绑定定量模型与孔位分布，
 * 支持直接改名、删除，以及点击「用此模板检测」一键跳转新建页（仅需填项目名与实验图即可直接开测）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentTemplateManagementScreen(
    navController: NavController,
    viewModel: ExperimentTemplateViewModel = hiltViewModel()
) {
    val toastManager = LocalToastManager.current
    val cards by viewModel.templateCards.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var modalityFilter by rememberSaveable { mutableStateOf<DetectionModality?>(null) }
    var templateToRename by remember { mutableStateOf<ExperimentTemplate?>(null) }
    var templateToDelete by remember { mutableStateOf<ExperimentTemplate?>(null) }

    val renamedToast = stringResource(R.string.template_renamed_success)
    val deletedToast = stringResource(R.string.template_deleted_success)

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearError()
        }
    }

    val filteredCards = remember(cards, modalityFilter) {
        if (modalityFilter == null) cards else cards.filter { it.modality == modalityFilter }
    }

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.library_template_title),
                onBack = { navController.navigateUp() }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("template_library_screen")
        ) {
            when {
                isLoading && cards.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 10.dp,
                            bottom = 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "header_guide") {
                            FluoSectionCard(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                verticalSpacing = 6.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    FluoIconBadge(
                                        icon = Icons.Outlined.AutoAwesome,
                                        accentColor = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.template_library_summary_count, cards.size),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = stringResource(R.string.template_library_save_guide),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (cards.size >= 2) {
                            item(key = "modality_filters") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = modalityFilter == null,
                                        onClick = { modalityFilter = null },
                                        label = { Text(stringResource(R.string.resource_filter_all)) }
                                    )
                                    listOf(
                                        DetectionModality.FLUORESCENCE to R.string.analysis_model_mode_fluorescence,
                                        DetectionModality.COLORIMETRIC to R.string.analysis_model_mode_colorimetric
                                    ).forEach { (mode, labelRes) ->
                                        FilterChip(
                                            selected = modalityFilter == mode,
                                            onClick = {
                                                modalityFilter = if (modalityFilter == mode) null else mode
                                            },
                                            label = { Text(stringResource(labelRes)) }
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredCards.isEmpty()) {
                            item(key = "empty_templates") {
                                FluoStatePlaceholder(
                                    icon = Icons.Outlined.AutoAwesome,
                                    text = stringResource(R.string.no_templates),
                                    supportingText = stringResource(R.string.template_library_empty_guide),
                                    actionText = stringResource(R.string.direct_create_title),
                                    onAction = {
                                        navController.navigate(Screen.DirectCreateProject.createRoute())
                                    }
                                )
                            }
                        } else {
                            items(filteredCards, key = { it.template.id }) { card ->
                                TemplateLibraryCard(
                                    card = card,
                                    onUseTemplate = {
                                        navController.navigate(
                                            Screen.DirectCreateProject.createRoute(card.template.id)
                                        )
                                    },
                                    onRename = { templateToRename = card.template },
                                    onDelete = { templateToDelete = card.template }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    templateToRename?.let { editing ->
        RenameTemplateDialog(
            initialName = editing.templateName,
            onDismiss = { templateToRename = null },
            onConfirm = { newName ->
                viewModel.renameTemplate(editing, newName) {
                    templateToRename = null
                    toastManager.showToast(renamedToast, ToastType.SUCCESS)
                }
            }
        )
    }

    templateToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { templateToDelete = null },
            title = { Text(stringResource(R.string.delete_template_title)) },
            text = {
                Text(stringResource(R.string.delete_template_confirmation, target.templateName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTemplate(target)
                        templateToDelete = null
                        toastManager.showToast(deletedToast, ToastType.SUCCESS)
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { templateToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun TemplateLibraryCard(
    card: TemplateLibraryCardModel,
    onUseTemplate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by rememberSaveable(card.template.id) { mutableStateOf(false) }
    val modeLabel = when (card.modality) {
        DetectionModality.FLUORESCENCE -> stringResource(R.string.analysis_model_mode_fluorescence)
        DetectionModality.COLORIMETRIC -> stringResource(R.string.analysis_model_mode_colorimetric)
        DetectionModality.SPECTRUM -> stringResource(R.string.analysis_model_mode_spectrum)
        null -> stringResource(R.string.unknown)
    }
    val carrierLabel = if (card.isPlate96) {
        stringResource(R.string.array_analysis_plate96)
    } else {
        "${card.rows}×${card.columns} ${stringResource(R.string.array_analysis_chip)}"
    }

    FluoSectionCard(
        modifier = Modifier.clickable { expanded = !expanded },
        contentPadding = FluoSpacing.md,
        verticalSpacing = 10.dp,
        accentColor = MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = card.template.templateName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TemplateMetaBadge(
                        text = modeLabel,
                        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        content = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TemplateMetaBadge(
                        text = carrierLabel,
                        container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        content = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand)
                )
            }
        }

        if (card.analytes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                card.analytes.forEach { item ->
                    TemplateAnalyteRow(item)
                }
            }
        }

        Text(
            text = stringResource(
                R.string.direct_create_template_layout_summary,
                card.standardCount,
                card.sampleCount,
                card.referenceCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AnimatedVisibility(
            visible = expanded && card.roleGrid.isNotEmpty(),
            enter = FluoMotion.expandEnter,
            exit = FluoMotion.expandExit
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TemplateMiniLayoutGrid(
                    rows = card.rows,
                    columns = card.columns,
                    roleGrid = card.roleGrid
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRename, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            if (card.isRunnable) {
                Button(
                    onClick = onUseTemplate,
                    shape = RoundedCornerShape(FluoRadius.control),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.template_use_for_detection_action))
                }
            }
        }
    }
}

@Composable
private fun TemplateAnalyteRow(item: TemplateAnalyteSummaryItem) {
    val methodText = when (item.methodCode) {
        AnalyteQuantitationMethod.STANDARD_CURVE_RESOURCE.name ->
            item.modelName ?: stringResource(R.string.standard_curve_library_title)
        AnalyteQuantitationMethod.ONSITE_CALIBRATION.name ->
            stringResource(R.string.template_method_frozen_curve)
        AnalyteQuantitationMethod.DEEP_LEARNING_MODEL.name ->
            stringResource(R.string.template_method_deep_learning)
        else -> stringResource(R.string.template_method_signal_only)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item.fluorescenceChannel?.let { channel ->
            val dotColor = when (channel) {
                FluorescenceChannel.RED -> Color(0xFFE53935)
                FluorescenceChannel.GREEN -> Color(0xFF2E7D32)
                FluorescenceChannel.BLUE -> Color(0xFF1E88E5)
                FluorescenceChannel.GRAY -> Color(0xFF607D8B)
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dotColor, CircleShape)
            )
        }
        Text(
            text = "${item.analyteName} (${item.unit}) · $methodText",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TemplateMiniLayoutGrid(
    rows: Int,
    columns: Int,
    roleGrid: Map<Pair<Int, Int>, String>
) {
    val safeRows = rows.coerceIn(1, 16)
    val safeCols = columns.coerceIn(1, 16)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(safeRows) { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                repeat(safeCols) { c ->
                    val role = roleGrid[r to c]
                    val cellColor = when (role) {
                        TemplateSiteRole.STANDARD.code -> Color(0xFF2563EB)
                        TemplateSiteRole.SAMPLE.code -> Color(0xFF0D9488)
                        TemplateSiteRole.BLANK.code,
                        TemplateSiteRole.REFERENCE.code -> Color(0xFFD97706)
                        TemplateSiteRole.POSITIVE_CONTROL.code,
                        TemplateSiteRole.NEGATIVE_CONTROL.code -> Color(0xFF7C3AED)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(cellColor, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateMetaBadge(
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
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RenameTemplateDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.template_rename_title), fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.template_name_label)) },
                singleLine = true,
                shape = RoundedCornerShape(FluoRadius.control),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
