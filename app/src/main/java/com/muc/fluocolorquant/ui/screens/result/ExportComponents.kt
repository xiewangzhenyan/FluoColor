package com.muc.fluocolorquant.ui.screens.result

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.AnalyteResultDetails
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.repository.ProjectAnalyteJoinRepository
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.muc.fluocolorquant.ui.theme.FluoRadius

/**
 * 导出选项面板
 */
@Composable
fun ExportBottomPanel(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    project: Project,
    wellResults: List<WellResult>,
    detectionRun: DetectionRun?,
    captureScreenshot: () -> Bitmap?,
    projectAnalyteJoinRepository: ProjectAnalyteJoinRepository,
    analyteResultDetails: Map<String, AnalyteResultDetails> = emptyMap(),
    exportViewModel: ExportViewModel
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val coroutineScope = rememberCoroutineScope()
    val exportState by exportViewModel.exportState.collectAsState()

    // 分析物选择状态
    val analyteList = remember(analyteResultDetails) {
        analyteResultDetails.values.map { it.analyte }.sortedBy { it.name }
    }
    val selectedAnalytes = remember { mutableStateListOf<Analyte>() }
    var showAnalyteSelection by remember { mutableStateOf(isVisible) }
    var showExportOptions by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }

    // 监听导出状态变化
    LaunchedEffect(exportState) {
        when (exportState) {
            is ExportViewModel.ExportState.InProgress -> {
                showProgress = true
            }
            is ExportViewModel.ExportState.Success -> {
                showProgress = false
                val success = exportState as ExportViewModel.ExportState.Success
                toastManager.showToast(success.message, ToastType.SUCCESS)
                success.filePath?.let { path ->
                    toastManager.showToast(
                        context.getString(R.string.export_file_path, path),
                        ToastType.INFO
                    )
                }
                onDismiss()
                exportViewModel.resetExportState()
            }
            is ExportViewModel.ExportState.Error -> {
                showProgress = false
                val error = exportState as ExportViewModel.ExportState.Error
                toastManager.showToast(error.message, ToastType.ERROR)
                exportViewModel.resetExportState()
            }
            else -> {
                showProgress = false
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            showAnalyteSelection = true
            showExportOptions = false
            selectedAnalytes.clear()
        }
    }

    if (isVisible) {
        // 第一步：分析物选择对话框
        if (showAnalyteSelection) {
            AnalyteSelectionDialog(
                analyteList = analyteList,
                selectedAnalytes = selectedAnalytes,
                onConfirm = {
                    showAnalyteSelection = false
                    showExportOptions = true
                },
                onDismiss = onDismiss
            )
        }

        // 第二步：导出选项面板
        if (showExportOptions) {
            ExportOptionsDialog(
                onDismiss = onDismiss,
                onExportCsv = {
                    val selectedAnalyteDetails = analyteResultDetails.values
                        .filter { selectedAnalytes.contains(it.analyte) }

                    val reportData = ExportViewModel.ReportData(
                        project = project,
                        analyteDetails = selectedAnalyteDetails.toList(),
                        detectionRun = detectionRun
                    )

                    exportViewModel.startCsvExport(reportData)
                },
                onExportPng = {
                    val selectedAnalyteDetails = analyteResultDetails.values
                        .filter { selectedAnalytes.contains(it.analyte) }

                    val reportData = ExportViewModel.ReportData(
                        project = project,
                        analyteDetails = selectedAnalyteDetails.toList(),
                        detectionRun = detectionRun
                    )

                    exportViewModel.startPngExport(reportData)
                },
                onExportPdf = {
                    val selectedAnalyteDetails = analyteResultDetails.values
                        .filter { selectedAnalytes.contains(it.analyte) }

                    val reportData = ExportViewModel.ReportData(
                        project = project,
                        analyteDetails = selectedAnalyteDetails.toList(),
                        detectionRun = detectionRun
                    )

                    exportViewModel.startPdfExport(reportData)
                },
                onExportArchive = {
                    val selectedAnalyteDetails = analyteResultDetails.values
                        .filter { selectedAnalytes.contains(it.analyte) }

                    val reportData = ExportViewModel.ReportData(
                        project = project,
                        analyteDetails = selectedAnalyteDetails.toList(),
                        detectionRun = detectionRun
                    )

                    exportViewModel.startArchiveExport(reportData, captureScreenshot())
                }
            )
        }

        // 进度指示器对话框
        if (showProgress) {
            AlertDialog(
                onDismissRequest = { /* 防止用户取消 */ },
                title = { Text(stringResource(R.string.export)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.export_in_progress))
                    }
                },
                confirmButton = { }
            )
        }
    }
}

/**
 * 分析物选择对话框 (修改后)
 */
@Composable
fun AnalyteSelectionDialog(
    analyteList: List<Analyte>,
    selectedAnalytes: MutableList<Analyte>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.ChecklistRtl,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.export_analyte_selection_title))
            }
        },
        text = {
            // 限制对话框内容最大高度为屏幕的60%
            Column(modifier = Modifier.heightIn(max = screenHeight * 0.6f)) {
                Text(stringResource(R.string.export_analyte_selection_message))
                Spacer(modifier = Modifier.height(16.dp))

                if (analyteList.isEmpty()) {
                    Text(
                        text = stringResource(R.string.export_no_analytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedAnalytes.clear()
                            selectedAnalytes.addAll(analyteList)
                        }) {
                            Text(stringResource(R.string.export_select_all))
                        }
                        TextButton(onClick = { selectedAnalytes.clear() }) {
                            Text(stringResource(R.string.export_deselect_all))
                        }
                    }

                    Divider(modifier = Modifier.padding(bottom = 8.dp))

                    LazyColumn {
                        items(analyteList) { analyte ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(FluoRadius.chip))
                                    .clickable {
                                        if (selectedAnalytes.contains(analyte)) {
                                            selectedAnalytes.remove(analyte)
                                        } else {
                                            selectedAnalytes.add(analyte)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedAnalytes.contains(analyte),
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            selectedAnalytes.add(analyte)
                                        } else {
                                            selectedAnalytes.remove(analyte)
                                        }
                                    }
                                )
                                Icon(
                                    imageVector = Icons.Filled.Science,
                                    contentDescription = stringResource(R.string.export_analyte_icon_desc),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(20.dp)
                                )
                                Text(
                                    text = analyte.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = selectedAnalytes.isNotEmpty()
            ) {
                Text(stringResource(R.string.next_step))
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
 * 导出选项对话框
 */
@Composable
fun ExportOptionsDialog(
    onDismiss: () -> Unit,
    onExportCsv: () -> Unit,
    onExportPng: () -> Unit,
    onExportPdf: () -> Unit,
    onExportArchive: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // 使用DialogWindowProvider来设置背景半透明效果
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0.5f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clickable(enabled = false) { /* 拦截点击，防止关闭面板 */ },
                shape = RoundedCornerShape(FluoRadius.control),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 8.dp
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.export_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 导出数据选项（CSV）
                    ExportOption(
                        title = stringResource(R.string.export_data_csv),
                        description = stringResource(R.string.export_data_csv_desc),
                        icon = Icons.Default.Description,
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        onClick = onExportCsv
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 导出图表选项（PNG）
                    ExportOption(
                        title = stringResource(R.string.export_chart_png),
                        description = stringResource(R.string.export_chart_png_desc),
                        icon = Icons.Default.Image,
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        onClick = onExportPng
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 导出完整报告选项（PDF）
                    ExportOption(
                        title = stringResource(R.string.export_report_pdf),
                        description = stringResource(R.string.export_report_pdf_desc),
                        icon = Icons.Default.PictureAsPdf,
                        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        onClick = onExportPdf
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ExportOption(
                        title = stringResource(R.string.export_archive_title),
                        description = stringResource(R.string.export_archive_desc),
                        icon = Icons.Default.FileDownload,
                        backgroundColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                        onClick = onExportArchive
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 取消按钮
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

/**
 * 导出选项组件
 */
@Composable
fun ExportOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluoRadius.chip))
            .background(backgroundColor.copy(alpha = 0.7f))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(FluoRadius.chip))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // 使用自定义导出图标替代FileDownload图标
        Icon(
            painter = painterResource(id = R.drawable.export),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}
