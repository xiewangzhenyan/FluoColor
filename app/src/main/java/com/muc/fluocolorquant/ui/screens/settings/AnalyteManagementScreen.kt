package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.AnalyteViewModel
import kotlinx.coroutines.launch

/**
 * 分析物管理界面
 * 显示分析物列表，支持添加、编辑和删除操作
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AnalyteManagementScreen(
    viewModel: AnalyteViewModel = hiltViewModel(),
    navigateBack: () -> Unit
) {
    val toastManager = LocalToastManager.current
    
    // 状态收集
    val analytes by viewModel.analytes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    // 对话框状态
    var showAddDialog by remember { mutableStateOf(false) }
    var analyteToDelete by remember { mutableStateOf<Analyte?>(null) }
    var analyteToEdit by remember { mutableStateOf<Analyte?>(null) }
    
    // 预先加载字符串资源，避免在非Composable上下文中调用
    val analyteAddedSuccessMsg = stringResource(R.string.analyte_added_success)
    val analyteDeletedSuccessMsg = stringResource(R.string.analyte_deleted_success)
    val analyteUpdatedSuccessMsg = stringResource(R.string.analyte_updated_success)
    
    // 错误消息处理
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
            viewModel.clearErrorMessage()
        }
    }
    
    // Scaffold布局
    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.analyte_management_title),
                onBack = { navigateBack() }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_analyte)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (analytes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_analytes_found),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.add_your_first_analyte),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // 添加固定头部，显示分析物总数
                    stickyHeader {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                            tonalElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    // 使用字符串资源传递总数
                                    text = stringResource(R.string.history_total_records, analytes.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    items(analytes, key = { it.id }) { analyte ->
                        AnalyteItem(
                            analyte = analyte,
                            onDelete = { analyteToDelete = analyte },
                            onEdit = { analyteToEdit = analyte }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // 底部间距
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
    
    // 添加分析物对话框
    if (showAddDialog) {
        AddAnalyteDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                viewModel.addAnalyte(name)
                showAddDialog = false
                toastManager.showToast(
                    analyteAddedSuccessMsg,
                    ToastType.SUCCESS
                )
            }
        )
    }
    
    // 删除分析物对话框
    analyteToDelete?.let { analyte ->
        DeleteAnalyteDialog(
            analyteName = analyte.name,
            onDismiss = { analyteToDelete = null },
            onConfirm = {
                viewModel.deleteAnalyte(analyte.id)
                analyteToDelete = null
                toastManager.showToast(
                    analyteDeletedSuccessMsg,
                    ToastType.SUCCESS
                )
            }
        )
    }
    
    // 编辑分析物对话框
    analyteToEdit?.let { analyte ->
        EditAnalyteDialog(
            analyte = analyte,
            onDismiss = { analyteToEdit = null },
            onConfirm = { id, name ->
                viewModel.updateAnalyte(id, name)
                analyteToEdit = null
                toastManager.showToast(
                    analyteUpdatedSuccessMsg,
                    ToastType.SUCCESS
                )
            }
        )
    }
}

/**
 * 分析物列表项
 */
@Composable
fun AnalyteItem(
    analyte: Analyte,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = analyte.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onDelete
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 添加分析物对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAnalyteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_analyte)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.analyte_name)) },
                    placeholder = { Text(stringResource(R.string.enter_analyte_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.analyte_name_empty))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameError = name.isBlank()
                    if (!nameError) {
                        onConfirm(name)
                    }
                }
            ) {
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
 * 编辑分析物对话框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAnalyteDialog(
    analyte: Analyte,
    onDismiss: () -> Unit,
    onConfirm: (id: String, name: String) -> Unit
) {
    var analyteName by remember { mutableStateOf(analyte.name) }
    var nameError by remember { mutableStateOf(false) }
    
    // 检测是否有变化
    val hasChanges = analyteName != analyte.name && analyteName.isNotBlank()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_analyte)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = analyteName,
                    onValueChange = { 
                        analyteName = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.analyte_name)) },
                    placeholder = { Text(stringResource(R.string.enter_analyte_name)) },
                    isError = nameError,
                    supportingText = {
                        if (nameError) {
                            Text(stringResource(R.string.analyte_name_empty))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    nameError = analyteName.isBlank()
                    if (!nameError) {
                        onConfirm(analyte.id, analyteName)
                    }
                },
                enabled = hasChanges
            ) {
                Text(stringResource(R.string.update))
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
 * 删除分析物确认对话框
 */
@Composable
fun DeleteAnalyteDialog(
    analyteName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_analyte_title)) },
        text = { 
            Text(
                stringResource(
                    R.string.confirm_delete_analyte_with_name,
                    analyteName
                )
            ) 
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
} 