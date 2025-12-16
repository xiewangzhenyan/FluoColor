package com.muc.fluocolorquant.ui.screens.spectrum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muc.fluocolorquant.R

/**
 * 光谱导出选项底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumExportBottomSheet(
    onDismiss: () -> Unit,
    onCsvExport: () -> Unit,
    onPngExport: (isMerged: Boolean) -> Unit,  // 修改：添加模式参数
    onPdfExport: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPngModeDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题
            Text(
                text = stringResource(R.string.spectrum_export_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2D3142),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // CSV导出选项
            ExportOptionItem(
                icon = Icons.Default.Description,
                title = stringResource(R.string.spectrum_export_csv_title),
                description = stringResource(R.string.spectrum_export_csv_desc),
                iconTint = Color(0xFF10B981),
                onClick = {
                    onCsvExport()
                    onDismiss()
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // PNG导出选项
            ExportOptionItem(
                icon = Icons.Default.Image,
                title = stringResource(R.string.spectrum_export_png_title),
                description = stringResource(R.string.spectrum_export_png_desc),
                iconTint = Color(0xFF3B82F6),
                onClick = {
                    showPngModeDialog = true  // 显示PNG模式选择对话框
                }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // PDF导出选项
            ExportOptionItem(
                icon = Icons.Default.PictureAsPdf,
                title = stringResource(R.string.spectrum_export_pdf_title),
                description = stringResource(R.string.spectrum_export_pdf_desc),
                iconTint = Color(0xFFEF4444),
                onClick = {
                    onPdfExport()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // PNG导出模式选择对话框
    if (showPngModeDialog) {
        PngExportModeDialog(
            onDismiss = { showPngModeDialog = false },
            onModeSelected = { isMerged ->
                showPngModeDialog = false
                onPngExport(isMerged)
                onDismiss()
            }
        )
    }
}

/**
 * 导出选项单项
 */
@Composable
private fun ExportOptionItem(
    icon: ImageVector,
    title: String,
    description: String,
    iconTint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.medium,
            color = iconTint.copy(alpha = 0.1f)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 文字内容
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2D3142)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = Color(0xFF6B7280),
                lineHeight = 18.sp
            )
        }

        // 使用自定义导出图标替代箭头图标
        Icon(
            painter = painterResource(id = R.drawable.export),
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * PNG导出模式选择对话框
 */
@Composable
private fun PngExportModeDialog(
    onDismiss: () -> Unit,
    onModeSelected: (isMerged: Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = Color(0xFF3B82F6),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.spectrum_png_export_mode_title))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 单通道模式选项
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(false) },
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF3B82F6).copy(alpha = 0.1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = Color(0xFF3B82F6),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.spectrum_png_single_channel_title),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF2D3142)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.spectrum_png_single_channel_desc),
                                    fontSize = 13.sp,
                                    color = Color(0xFF6B7280)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 合并模式选项
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(true) },
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF10B981).copy(alpha = 0.1f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.spectrum_png_merged_title),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF2D3142)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.spectrum_png_merged_desc),
                                    fontSize = 13.sp,
                                    color = Color(0xFF6B7280)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
