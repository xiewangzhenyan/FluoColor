package com.muc.fluocolorquant.ui.screens.history

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.HistoryViewModel
import com.muc.fluocolorquant.utils.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.vector.ImageVector

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    // 获取ViewModel状态
    val loadingState by viewModel.loadingState.collectAsState()
    val projects by viewModel.filteredProjects.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val filterSettings by viewModel.filterSettings.collectAsState()
    val sortSettings by viewModel.sortSettings.collectAsState()

    // UI状态
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var projectToDelete by remember { mutableStateOf<Project?>(null) }

    // 搜索栏动画状态
    val searchBarVisibleState = remember { MutableTransitionState(false) }
    searchBarVisibleState.targetState = showSearchBar

    // 选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedProjects = remember { mutableStateListOf<String>() }

    // Toast管理器
    val toastManager = LocalToastManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 处理删除状态
    LaunchedEffect(deleteState) {
        when (deleteState) {
            is HistoryViewModel.DeleteState.Success -> {
                toastManager.showToast("删除成功", ToastType.SUCCESS)
                selectedProjects.clear()
                isSelectionMode = false
            }
            is HistoryViewModel.DeleteState.Error -> {
                val message = (deleteState as HistoryViewModel.DeleteState.Error).message
                toastManager.showToast("删除失败: $message", ToastType.ERROR)
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            if (isSelectionMode) {
                                Text("已选择 ${selectedProjects.size} 项")
                            } else {
                                Text("历史记录")
                            }
                        }

                        AnimatedVisibility(
                            visibleState = searchBarVisibleState,
                            enter = fadeIn(animationSpec = tween(300)) +
                                    expandHorizontally(animationSpec = tween(300)),
                            exit = fadeOut(animationSpec = tween(300)) +
                                    shrinkHorizontally(animationSpec = tween(300))
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("搜索项目...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = TextFieldDefaults.outlinedTextFieldColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        showSearchBar = false
                                        viewModel.setSearchQuery("")
                                    }) {
                                        Icon(Icons.Default.Close, "清除搜索")
                                    }
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedProjects.clear()
                        } else if (showSearchBar) {
                            showSearchBar = false
                            viewModel.setSearchQuery("")
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = if (isSelectionMode) "取消" else "返回"
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        // 删除选中项
                        IconButton(onClick = {
                            if (selectedProjects.isNotEmpty()) {
                                showDeleteConfirmDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Delete, "删除选中项")
                        }
                    } else {
                        // 搜索按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showSearchBar = true }) {
                                Icon(Icons.Default.Search, "搜索")
                            }
                        }

                        // 筛选按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showFilterDialog = true }) {
                                Icon(Icons.Default.FilterList, "筛选")
                            }
                        }

                        // 排序按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.Sort, "排序")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            if (isSelectionMode && selectedProjects.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showDeleteConfirmDialog = true },
                    icon = { Icon(Icons.Default.Delete, "删除") },
                    text = { Text("删除选中项") },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            }
        }
    ) { paddingValues ->
        when (loadingState) {
            HistoryViewModel.LoadingState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            HistoryViewModel.LoadingState.Empty -> {
                EmptyHistoryView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            HistoryViewModel.LoadingState.FilteredEmpty -> {
                NoResultsView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    searchQuery = searchQuery,
                    onClearFilters = {
                        viewModel.setSearchQuery("")
                        viewModel.setTimeFilter(HistoryViewModel.TimeRange.ALL)
                        viewModel.setDetectionModeFilter(emptySet())
                        viewModel.setRecognitionTypeFilter(emptySet())
                    }
                )
            }

            HistoryViewModel.LoadingState.Success -> {
                ProjectList(
                    projects = projects,
                    onProjectClick = { project ->
                        if (isSelectionMode) {
                            if (project.id in selectedProjects) {
                                selectedProjects.remove(project.id)
                            } else {
                                selectedProjects.add(project.id)
                            }
                        } else {
                            // 导航到结果页面
                            navController.navigate("${Screen.Result.route}?projectId=${project.id}")
                        }
                    },
                    onProjectLongClick = { project ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedProjects.add(project.id)
                        }
                    },
                    onDeleteClick = { project ->
                        projectToDelete = project
                        showDeleteConfirmDialog = true
                    },
                    isSelectionMode = isSelectionMode,
                    selectedProjects = selectedProjects,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }

            is HistoryViewModel.LoadingState.Error -> {
                val message = (loadingState as HistoryViewModel.LoadingState.Error).message
                ErrorView(
                    message = message,
                    onRetry = { viewModel.loadUserProjects() },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }

        // 筛选对话框
        if (showFilterDialog) {
            FilterDialog(
                currentTimeRange = filterSettings.timeRange,
                currentDetectionModes = filterSettings.detectionModes,
                currentRecognitionTypes = filterSettings.recognitionTypes,
                onTimeRangeSelected = { viewModel.setTimeFilter(it) },
                onDetectionModesSelected = { viewModel.setDetectionModeFilter(it) },
                onRecognitionTypesSelected = { viewModel.setRecognitionTypeFilter(it) },
                onDismiss = { showFilterDialog = false }
            )
        }

        // 排序对话框
        if (showSortDialog) {
            EnhancedSortDialog(
                currentField = sortSettings.field,
                currentDirection = sortSettings.direction,
                onSortOrderSelected = { field, direction ->
                    viewModel.setSortOrder(field, direction)
                },
                onDismiss = { showSortDialog = false }
            )
        }

        // 删除确认对话框
        if (showDeleteConfirmDialog) {
            DeleteConfirmDialog(
                isMultiSelect = isSelectionMode && selectedProjects.isNotEmpty(),
                projectCount = if (isSelectionMode) selectedProjects.size else 1,
                onConfirm = {
                    if (isSelectionMode && selectedProjects.isNotEmpty()) {
                        viewModel.deleteProjects(selectedProjects.toList())
                    } else if (projectToDelete != null) {
                        viewModel.deleteProject(projectToDelete!!.id)
                    }
                    showDeleteConfirmDialog = false
                    projectToDelete = null
                },
                onDismiss = {
                    showDeleteConfirmDialog = false
                    projectToDelete = null
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Project) -> Unit,
    onProjectLongClick: (Project) -> Unit,
    onDeleteClick: (Project) -> Unit,
    isSelectionMode: Boolean,
    selectedProjects: List<String>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp) // 增加项目间距
    ) {
        items(
            items = projects,
            key = { it.id }
        ) { project ->
            ProjectItem(
                project = project,
                onClick = { onProjectClick(project) },
                onLongClick = { onProjectLongClick(project) },
                onDeleteClick = { onDeleteClick(project) },
                isSelected = project.id in selectedProjects,
                isSelectionMode = isSelectionMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItemPlacement(tween(300)) // 添加项目移动动画
            )
        }

        // 底部间距
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectItem(
    project: Project,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp, // 增加按下时的阴影
            focusedElevation = 4.dp
        ),
        shape = RoundedCornerShape(12.dp), // 增加卡片圆角
        colors = if (isSelected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 勾选框（选择模式下）
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // 项目缩略图
            Box(
                modifier = Modifier
                    .size(84.dp) // 稍微增大图片大小
                    .clip(RoundedCornerShape(10.dp)) // 增加图片圆角
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (project.imageUri.isNotEmpty()) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(Uri.parse(project.imageUri))
                                .error(R.drawable.placeholder_image)
                                .placeholder(R.drawable.placeholder_image)
                                .build()
                        ),
                        contentDescription = "项目图片",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // 项目信息
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                // 项目名称
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold // 加粗字体
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp)) // 增大间距

                // 检测模式
                val detectionMode = when (project.detectionMode) {
                    "FLUORESCENCE" -> "荧光检测"
                    "COLORIMETRIC" -> "比色检测"
                    else -> project.detectionMode
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 添加一个小图标
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = detectionMode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 识别类型
                val recognitionType = when (project.recognitionType) {
                    "AUTO" -> "自动识别"
                    "MANUAL" -> "手动裁剪"
                    else -> project.recognitionType
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 添加一个小图标
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = recognitionType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 创建时间
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = formatDate(project.createTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 删除按钮 (非选择模式下)
            if (!isSelectionMode) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FilterDialog(
    currentTimeRange: HistoryViewModel.TimeRange,
    currentDetectionModes: Set<String>,
    currentRecognitionTypes: Set<String>,
    onTimeRangeSelected: (HistoryViewModel.TimeRange) -> Unit,
    onDetectionModesSelected: (Set<String>) -> Unit,
    onRecognitionTypesSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // 标题
                Text(
                    text = "筛选历史记录",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 时间筛选
                Text(
                    text = "时间范围",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 时间选项
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TimeFilterChip(
                        label = "全部时间",
                        selected = currentTimeRange is HistoryViewModel.TimeRange.ALL,
                        onClick = { onTimeRangeSelected(HistoryViewModel.TimeRange.ALL) }
                    )

                    TimeFilterChip(
                        label = "今天",
                        selected = currentTimeRange is HistoryViewModel.TimeRange.TODAY,
                        onClick = { onTimeRangeSelected(HistoryViewModel.TimeRange.TODAY) }
                    )

                    TimeFilterChip(
                        label = "最近7天",
                        selected = currentTimeRange is HistoryViewModel.TimeRange.LAST_WEEK,
                        onClick = { onTimeRangeSelected(HistoryViewModel.TimeRange.LAST_WEEK) }
                    )

                    TimeFilterChip(
                        label = "最近30天",
                        selected = currentTimeRange is HistoryViewModel.TimeRange.LAST_MONTH,
                        onClick = { onTimeRangeSelected(HistoryViewModel.TimeRange.LAST_MONTH) }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 检测模式筛选
                Text(
                    text = "检测模式",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 检测模式选项
                var localDetectionModes by remember { mutableStateOf(currentDetectionModes) }

                Row(modifier = Modifier.fillMaxWidth()) {
                    DetectionModeFilterChip(
                        label = "荧光检测",
                        selected = "FLUORESCENCE" in localDetectionModes,
                        onClick = {
                            localDetectionModes = if ("FLUORESCENCE" in localDetectionModes) {
                                localDetectionModes - "FLUORESCENCE"
                            } else {
                                localDetectionModes + "FLUORESCENCE"
                            }
                            onDetectionModesSelected(localDetectionModes)
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    DetectionModeFilterChip(
                        label = "比色检测",
                        selected = "COLORIMETRIC" in localDetectionModes,
                        onClick = {
                            localDetectionModes = if ("COLORIMETRIC" in localDetectionModes) {
                                localDetectionModes - "COLORIMETRIC"
                            } else {
                                localDetectionModes + "COLORIMETRIC"
                            }
                            onDetectionModesSelected(localDetectionModes)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 识别类型筛选
                Text(
                    text = "识别类型",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 识别类型选项
                var localRecognitionTypes by remember { mutableStateOf(currentRecognitionTypes) }

                Row(modifier = Modifier.fillMaxWidth()) {
                    RecognitionTypeFilterChip(
                        label = "自动识别",
                        selected = "AUTO" in localRecognitionTypes,
                        onClick = {
                            localRecognitionTypes = if ("AUTO" in localRecognitionTypes) {
                                localRecognitionTypes - "AUTO"
                            } else {
                                localRecognitionTypes + "AUTO"
                            }
                            onRecognitionTypesSelected(localRecognitionTypes)
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    RecognitionTypeFilterChip(
                        label = "手动裁剪",
                        selected = "MANUAL" in localRecognitionTypes,
                        onClick = {
                            localRecognitionTypes = if ("MANUAL" in localRecognitionTypes) {
                                localRecognitionTypes - "MANUAL"
                            } else {
                                localRecognitionTypes + "MANUAL"
                            }
                            onRecognitionTypesSelected(localRecognitionTypes)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            onTimeRangeSelected(HistoryViewModel.TimeRange.ALL)
                            onDetectionModesSelected(emptySet())
                            onRecognitionTypesSelected(emptySet())
                        }
                    ) {
                        Text("重置")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(onClick = onDismiss) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

@Composable
fun TimeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
        } else null,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun DetectionModeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
fun RecognitionTypeFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

@Composable
fun EnhancedSortDialog(
    currentField: HistoryViewModel.SortField,
    currentDirection: HistoryViewModel.SortDirection,
    onSortOrderSelected: (HistoryViewModel.SortField, HistoryViewModel.SortDirection) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // 标题
                Text(
                    text = "排序方式",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 排序字段
                var localField by remember { mutableStateOf(currentField) }
                var localDirection by remember { mutableStateOf(currentDirection) }

                // 排序方向选择器
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "排序方向:",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = localDirection == HistoryViewModel.SortDirection.ASCENDING,
                            onClick = {
                                localDirection = HistoryViewModel.SortDirection.ASCENDING
                                onSortOrderSelected(localField, localDirection)
                            },
                            label = { Text("升序") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        FilterChip(
                            selected = localDirection == HistoryViewModel.SortDirection.DESCENDING,
                            onClick = {
                                localDirection = HistoryViewModel.SortDirection.DESCENDING
                                onSortOrderSelected(localField, localDirection)
                            },
                            label = { Text("降序") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "排序字段:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 排序字段选项
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 按名称排序
                    EnhancedSortOptionItem(
                        title = "项目名称",
                        icon = Icons.Default.Title,
                        selected = localField == HistoryViewModel.SortField.NAME,
                        onClick = {
                            localField = HistoryViewModel.SortField.NAME
                            onSortOrderSelected(localField, localDirection)
                        }
                    )

                    // 按创建时间排序
                    EnhancedSortOptionItem(
                        title = "创建时间",
                        icon = Icons.Default.DateRange,
                        selected = localField == HistoryViewModel.SortField.CREATE_TIME,
                        onClick = {
                            localField = HistoryViewModel.SortField.CREATE_TIME
                            onSortOrderSelected(localField, localDirection)
                        }
                    )

                    // 按最后运行时间排序
                    EnhancedSortOptionItem(
                        title = "最后运行时间",
                        icon = Icons.Default.Update,
                        selected = localField == HistoryViewModel.SortField.LAST_RUN,
                        onClick = {
                            localField = HistoryViewModel.SortField.LAST_RUN
                            onSortOrderSelected(localField, localDirection)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 底部按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("取消")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onSortOrderSelected(localField, localDirection)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedSortOptionItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.weight(1f))

            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(
    isMultiSelect: Boolean,
    projectCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = {
            Text(
                text = if (isMultiSelect)
                    "确定要删除选中的 $projectCount 个项目吗？此操作无法撤销。"
                else
                    "确定要删除此项目吗？此操作无法撤销。"
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun EmptyHistoryView(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(100.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "暂无历史记录",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "您的检测项目历史将显示在这里",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NoResultsView(
    searchQuery: String,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.FilterList,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "未找到匹配的项目",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (searchQuery.isNotEmpty())
                "没有与\"$searchQuery\"匹配的项目"
            else
                "当前筛选条件下没有匹配的项目",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = onClearFilters) {
            Text("清除筛选条件")
        }
    }
}

@Composable
fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = CircleShape
                )
                .padding(16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "加载失败",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

// 工具函数：格式化日期
private fun formatDate(date: Date): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(date)
} 