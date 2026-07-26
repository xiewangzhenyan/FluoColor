package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryEvent
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryItem
import com.muc.fluocolorquant.ui.viewmodels.StandardCurveLibraryViewModel
import java.util.Locale

/**
 * 面向普通用户的标准曲线库。
 *
 * 页面采用紧凑的科研工作台风格：首屏直接展示可用曲线、分析物、模式、信号和范围，操作
 * 只有“新建、编辑、删除”。版本、归档和后台模型参数不会再次进入普通用户流程。
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

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.standard_curve_library_title),
                onBack = { navController.navigateUp() }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.ManualDataInput.createRoute()) }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.standard_curve_unified_title)
                )
            }
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

            state.items.isEmpty() -> StandardCurveLibraryEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onCreate = { navController.navigate(Screen.ManualDataInput.createRoute()) },
                onLegacy = { navController.navigate(Screen.LegacyCurveModelLibrary.route) }
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StandardCurveLibraryHeader(
                        count = state.items.size,
                        onLegacy = { navController.navigate(Screen.LegacyCurveModelLibrary.route) }
                    )
                }
                items(state.items, key = { it.model.id }) { item ->
                    StandardCurveLibraryCard(
                        item = item,
                        deleting = state.deletingModelId == item.model.id,
                        onEdit = {
                            navController.navigate(
                                Screen.ManualDataInput.createRoute(item.model.id)
                            )
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
                    Text(stringResource(R.string.delete))
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

/** 有数据时的轻量摘要，不使用大面积宣传卡抢占首屏。 */
@Composable
private fun StandardCurveLibraryHeader(count: Int, onLegacy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.standard_curve_library_summary, count),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onLegacy) {
            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(5.dp))
            Text(stringResource(R.string.standard_curve_library_legacy))
        }
    }
}

@Composable
private fun StandardCurveLibraryCard(
    item: StandardCurveLibraryItem,
    deleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Analytics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 11.dp)
                ) {
                    Text(
                        text = item.model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.analyteName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StandardCurveTag(
                    icon = Icons.Default.Science,
                    text = detectionModeLabel(item.modality)
                )
                StandardCurveTag(
                    icon = Icons.Default.Biotech,
                    text = analysisFeatureLabel(item.feature)
                )
            }

            Text(
                text = stringResource(
                    R.string.standard_curve_library_range,
                    item.model.reliableRangeMin.compactNumber(),
                    item.model.reliableRangeMax.compactNumber(),
                    item.model.concentrationUnit
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(
                    R.string.standard_curve_library_carriers,
                    carrierSummary(item.carrierTypes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onEdit, enabled = !deleting) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(5.dp))
                    Text(stringResource(R.string.edit))
                }
                TextButton(onClick = onDelete, enabled = !deleting) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** 静态信息标签不使用 Chip 的点击语义，避免用户误以为模式或信号可以在列表中切换。 */
@Composable
private fun StandardCurveTag(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StandardCurveLibraryEmptyState(
    modifier: Modifier,
    onCreate: () -> Unit,
    onLegacy: () -> Unit
) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Analytics,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.size(18.dp))
        Text(
            stringResource(R.string.standard_curve_library_empty_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.size(7.dp))
        Text(
            stringResource(R.string.standard_curve_library_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(18.dp))
        TextButton(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.standard_curve_unified_title))
        }
        TextButton(onClick = onLegacy) {
            Text(stringResource(R.string.standard_curve_library_legacy))
        }
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
    // stringResource 只能在 Composable 上下文直接调用，因此先解析三类文案，再做普通集合拼接。
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
