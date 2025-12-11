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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info // 用于记录总数图标
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.DoneAll // 全选图标
import androidx.compose.material.icons.outlined.RemoveDone // 取消全选图标
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.HistoryViewModel
import com.muc.fluocolorquant.ui.navigation.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
    val allAnalytes by viewModel.allAnalytes.collectAsState()

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
    val context = LocalContext.current // 获取context
    val coroutineScope = rememberCoroutineScope()

    // 提前获取需要在协程中使用的字符串资源
    val noRunFoundMessage = stringResource(R.string.no_run_found_for_project)
    val errorLoadingMessage = stringResource(R.string.error_loading_project_data)

    // 处理项目点击的函数,明确指定类型为 (Project) -> Unit
    val handleProjectClick: (Project) -> Unit = { project ->
        if (isSelectionMode) {
            if (project.id in selectedProjects) {
                selectedProjects.remove(project.id)
            } else {
                selectedProjects.add(project.id)
            }
        } else {
            // 判断是否为光谱项目
            if (project.detectionMode == "SPECTRUM") {
                // 光谱项目直接跳转到光谱结果页面
                navController.navigate(Screen.SpectrumResult.createRoute(project.id))
            } else {
                // 常规项目(荧光/比色)导航到结果页面
                // 先获取项目对应的最新运行ID
                coroutineScope.launch {
                    try {
                        val runId = viewModel.getLatestRunIdForProject(project.id)
                        if (runId != null) {
                            navController.navigate(Screen.Result.createRoute(runId))
                        } else {
                            // 如果没有run,显示提示信息
                            toastManager.showToast(
                                noRunFoundMessage,
                                ToastType.WARNING
                            )
                        }
                    } catch (e: Exception) {
                        toastManager.showToast(
                            errorLoadingMessage,
                            ToastType.ERROR
                        )
                        android.util.Log.e("HistoryScreen", "获取运行ID失败", e)
                    }
                }
            }
        }
    }

    // 处理删除状态
    LaunchedEffect(deleteState) {
        when (deleteState) {
            is HistoryViewModel.DeleteState.Success -> {
                toastManager.showToast(context.getString(R.string.toast_delete_successful), ToastType.SUCCESS) // 使用context
                selectedProjects.clear()
                isSelectionMode = false
            }
            is HistoryViewModel.DeleteState.Error -> {
                val message = (deleteState as HistoryViewModel.DeleteState.Error).message
                toastManager.showToast(context.getString(R.string.toast_delete_failed, message), ToastType.ERROR) // 使用context
            }
            else -> {}
        }
    }

    // 确保选择模式与选中项一致 - 当没有任何选中项时自动退出选择模式
    LaunchedEffect(selectedProjects.size) {
        if (selectedProjects.isEmpty() && isSelectionMode) {
            isSelectionMode = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
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
                            // 计算实际选中数量
                            val actualSelectedCount = selectedProjects.size

                            // 只有在有选择项且处于选择模式时显示计数
                            if (isSelectionMode && actualSelectedCount > 0) {
                                Text(stringResource(R.string.history_selected_items_title, actualSelectedCount))
                            } else {
                                Text(stringResource(R.string.history_records))
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
                                placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = TextFieldDefaults.colors( // 使用TextFieldDefaults.colors
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                                ),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        showSearchBar = false
                                        viewModel.setSearchQuery("")
                                    }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.history_clear_search_description))
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
                            contentDescription = if (isSelectionMode) stringResource(R.string.cancel_selection) else stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        // 删除选中项
                        IconButton(onClick = {
                            if (selectedProjects.isNotEmpty()) { // 只有在有选中项时才显示删除确认
                                showDeleteConfirmDialog = true
                            }
                        }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete_selected))
                        }
                    } else {
                        // 搜索按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showSearchBar = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.search))
                            }
                        }

                        // 筛选按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showFilterDialog = true }) {
                                Icon(Icons.Default.FilterList, stringResource(R.string.filter))
                            }
                        }

                        // 排序按钮
                        AnimatedVisibility(
                            visible = !showSearchBar,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut()
                        ) {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.Sort, stringResource(R.string.sort))
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
            if (isSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 28.dp, bottom = 16.dp), // 调整内边距以匹配Scaffold通常的FAB边距
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allSelected = projects.isNotEmpty() && selectedProjects.size == projects.size
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (allSelected) {
                                selectedProjects.clear()
                            } else {
                                selectedProjects.clear()
                                selectedProjects.addAll(projects.map { it.id })
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (allSelected) Icons.Outlined.RemoveDone else Icons.Outlined.DoneAll,
                                contentDescription = if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all),
                            )
                        },
                        text = { Text(if (allSelected) stringResource(R.string.deselect_all) else stringResource(R.string.select_all)) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )

                    if (selectedProjects.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            onClick = { showDeleteConfirmDialog = true },
                            icon = { Icon(Icons.Default.Delete, stringResource(R.string.delete_action)) },
                            text = { Text(stringResource(R.string.delete_selected)) },
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
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
                        viewModel.setAnalysisMethodFilter(emptySet())
                        viewModel.setAnalyteFilter(emptySet())
                    }
                )
            }

            HistoryViewModel.LoadingState.Success -> {
                ProjectList(
                    projects = projects,
                    onProjectClick = handleProjectClick,
                    onProjectLongClick = { project ->
                        if (!isSelectionMode) {
                            isSelectionMode = true
                            selectedProjects.add(project.id) // 长按时默认选中当前项
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
                        .padding(paddingValues),
                    totalProjects = projects.size // 传递项目总数
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
                filterSettings = filterSettings,
                allAnalytes = allAnalytes,
                onTimeRangeSelected = { viewModel.setTimeFilter(it) },
                onDetectionModesSelected = { viewModel.setDetectionModeFilter(it) },
                onAnalysisMethodsSelected = { viewModel.setAnalysisMethodFilter(it) },
                onAnalytesSelected = { viewModel.setAnalyteFilter(it) },
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
                        // 先保存一份要删除的项目ID
                        val projectsToDelete = selectedProjects.toList()

                        // 立即清除选择状态和模式，不等待删除完成
                        selectedProjects.clear()
                        isSelectionMode = false

                        // 然后执行删除
                        viewModel.deleteProjects(projectsToDelete)
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
    modifier: Modifier = Modifier,
    totalProjects: Int // 新增参数：项目总数
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp) // 增加项目间距
    ) {
        // 固定头部，显示项目总数
        stickyHeader {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp), // 增加圆角
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f), // 调整颜色和透明度
                tonalElevation = 3.dp // 增加一点阴影
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp), // 调整内边距
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info, // 更换为Info图标
                        contentDescription = stringResource(R.string.history_total_records_icon_description),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer // 适配颜色
                    )
                    Spacer(modifier = Modifier.width(12.dp)) // 调整间距
                    Text(
                        text = stringResource(R.string.history_total_records, totalProjects),
                        style = MaterialTheme.typography.titleSmall, // 调整字体大小
                        fontWeight = FontWeight.SemiBold, // 调整字重
                        color = MaterialTheme.colorScheme.onSecondaryContainer // 适配颜色
                    )
                }
            }
        }

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
                                .error(R.drawable.placeholder_image) // 确保 placeholder_image 存在
                                .placeholder(R.drawable.placeholder_image) // 确保 placeholder_image 存在
                                .build()
                        ),
                        contentDescription = stringResource(R.string.history_project_image_description),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Search, // 后备图标
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
                val detectionModeText = when (project.detectionMode) {
                    "FLUORESCENCE" -> stringResource(R.string.fluorescence_detection)
                    "COLORIMETRIC" -> stringResource(R.string.colorimetric_detection)
                    "SPECTRUM" -> stringResource(R.string.spectrum_detection_title)
                    else -> project.detectionMode
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 添加一个小图标
                    Icon(
                        imageVector = Icons.Default.Check, // 示例图标
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = detectionModeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 【已修改】识别类型 -> 分析方法
                val analysisMethodText = when (project.analysisMethod) {
                    "DL_MODEL" -> stringResource(R.string.deep_learning_analysis)
                    "CURVE_FIT" -> stringResource(R.string.curve_fitting_analysis)
                    "LSPR_SPECTRUM" -> stringResource(R.string.lspr_spectrum_analysis)
                    else -> project.analysisMethod
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 添加一个小图标
                    Icon(
                        imageVector = Icons.Default.Search, // 示例图标
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = analysisMethodText,
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
                        contentDescription = stringResource(R.string.history_delete_icon_description),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}


/**
 * 【已修改】筛选对话框，优化了UI布局和交互
 */
@Composable
fun FilterDialog(
    filterSettings: HistoryViewModel.FilterSettings,
    allAnalytes: List<Analyte>,
    onTimeRangeSelected: (HistoryViewModel.TimeRange) -> Unit,
    onDetectionModesSelected: (Set<String>) -> Unit,
    onAnalysisMethodsSelected: (Set<String>) -> Unit,
    onAnalytesSelected: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp), // 增加圆角
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .heightIn(max = 720.dp) // 限制对话框最大高度
            ) {
                // --- 标题 ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.filter),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.history_filter_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider()


                // --- 可滚动内容区域 ---
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false) // 占据可用空间，但内容溢出时可滚动
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp) // 与分割线保持距离
                ) {
                    // 1. 检测模式筛选
                    FilterSection(
                        title = stringResource(R.string.detection_mode),
                        icon = Icons.Default.Science
                    ) {
                        val detectionModes = setOf("FLUORESCENCE", "COLORIMETRIC", "SPECTRUM")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detectionModes.forEach { mode ->
                                FilterChip(
                                    selected = mode in filterSettings.detectionModes,
                                    onClick = {
                                        val newSet = filterSettings.detectionModes.toMutableSet()
                                        if (mode in newSet) newSet.remove(mode) else newSet.add(mode)
                                        onDetectionModesSelected(newSet)
                                    },
                                    label = {
                                        Text(
                                            when (mode) {
                                                "FLUORESCENCE" -> stringResource(R.string.history_fluorescence_label)
                                                "COLORIMETRIC" -> stringResource(R.string.history_colorimetric_label)
                                                "SPECTRUM" -> stringResource(R.string.spectrum_detection)
                                                else -> mode
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 2. 分析方法筛选
                    FilterSection(
                        title = stringResource(R.string.analysis_method),
                        icon = Icons.Default.Analytics
                    ) {
                        val analysisMethods = setOf("DL_MODEL", "CURVE_FIT")
                        // 【已修改】使用Column使每个选项占一行
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            analysisMethods.forEach { method ->
                                FilterChip(
                                    selected = method in filterSettings.analysisMethods,
                                    onClick = {
                                        val newSet = filterSettings.analysisMethods.toMutableSet()
                                        if (method in newSet) newSet.remove(method) else newSet.add(method)
                                        onAnalysisMethodsSelected(newSet)
                                    },
                                    label = {
                                        Text(
                                            when (method) {
                                                "DL_MODEL" -> stringResource(R.string.dl_model_option)
                                                else -> stringResource(R.string.curve_fit_option)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // 3. 分析物筛选
                    FilterSection(
                        title = stringResource(R.string.analyte),
                        icon = Icons.Default.Search
                    ) {
                        AnalyteFilterSelector(
                            allAnalytes = allAnalytes,
                            selectedAnalyteIds = filterSettings.analyteIds,
                            onSelectionChanged = onAnalytesSelected
                        )
                    }

                    // 4. 时间筛选
                    FilterSection(
                        title = stringResource(R.string.time_range),
                        icon = Icons.Default.DateRange
                    ) {
                        val timeRanges = listOf(
                            HistoryViewModel.TimeRange.ALL to stringResource(R.string.all_time),
                            HistoryViewModel.TimeRange.TODAY to stringResource(R.string.today),
                            HistoryViewModel.TimeRange.LAST_WEEK to stringResource(R.string.last_7_days),
                            HistoryViewModel.TimeRange.LAST_MONTH to stringResource(R.string.last_30_days)
                        )
                        // 【已修改】使用 chunked(2) 实现两列布局
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            timeRanges.chunked(2).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowItems.forEach { (range, label) ->
                                        TimeFilterChip(
                                            label = label,
                                            selected = filterSettings.timeRange == range,
                                            onClick = { onTimeRangeSelected(range) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- 底部按钮 ---
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            onTimeRangeSelected(HistoryViewModel.TimeRange.ALL)
                            onDetectionModesSelected(emptySet())
                            onAnalysisMethodsSelected(emptySet())
                            onAnalytesSelected(emptySet())
                        }
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}


/**
 * 【已修改】为筛选部分增加图标和优化样式
 */
@Composable
private fun FilterSection(
    title: String,
    icon: ImageVector, // 修改为必传，并添加图标
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold // 加粗标题
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(modifier = Modifier.padding(start = 4.dp)) { // 内容稍微缩进
            content()
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AnalyteFilterSelector(
    allAnalytes: List<Analyte>,
    selectedAnalyteIds: Set<String>,
    onSelectionChanged: (Set<String>) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredAnalytes = if (searchQuery.isBlank()) {
        allAnalytes
    } else {
        allAnalytes.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_analytes)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp) // 给一个固定高度，使其可滚动
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
        ) {
            LazyColumn {
                if(filteredAnalytes.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_matching_analytes))
                        }
                    }
                } else {
                    items(filteredAnalytes, key = { it.id }) { analyte ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newSet = selectedAnalyteIds.toMutableSet()
                                    if (analyte.id in newSet) newSet.remove(analyte.id) else newSet.add(analyte.id)
                                    onSelectionChanged(newSet)
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (analyte.id in selectedAnalyteIds) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (analyte.id in selectedAnalyteIds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(analyte.name)
                        }
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
        } else null,
        modifier = modifier.padding(vertical = 4.dp)
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
                    text = stringResource(R.string.history_sort_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 排序字段和方向
                var localField by remember { mutableStateOf(currentField) }
                var localDirection by remember { mutableStateOf(currentDirection) }

                // 排序方向文本标签
                Text(
                    text = stringResource(R.string.sort_direction),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp) // 在标签和选项之间添加一些间距
                )

                // 排序方向选择器 - 现在是单独的一行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp), // 在选项和分割线之间添加间距
                    horizontalArrangement = Arrangement.spacedBy(8.dp), // 增加FilterChip之间的间距
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = localDirection == HistoryViewModel.SortDirection.ASCENDING,
                        onClick = {
                            localDirection = HistoryViewModel.SortDirection.ASCENDING
                            onSortOrderSelected(localField, localDirection)
                        },
                        label = { Text(stringResource(R.string.ascending)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    FilterChip(
                        selected = localDirection == HistoryViewModel.SortDirection.DESCENDING,
                        onClick = {
                            localDirection = HistoryViewModel.SortDirection.DESCENDING
                            onSortOrderSelected(localField, localDirection)
                        },
                        label = { Text(stringResource(R.string.descending)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }

                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.sort_field),
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
                        title = stringResource(R.string.history_project_name_sort),
                        icon = Icons.Default.Title,
                        selected = localField == HistoryViewModel.SortField.NAME,
                        onClick = {
                            localField = HistoryViewModel.SortField.NAME
                            onSortOrderSelected(localField, localDirection)
                        }
                    )

                    // 按创建时间排序
                    EnhancedSortOptionItem(
                        title = stringResource(R.string.history_creation_time_sort),
                        icon = Icons.Default.DateRange,
                        selected = localField == HistoryViewModel.SortField.CREATE_TIME,
                        onClick = {
                            localField = HistoryViewModel.SortField.CREATE_TIME
                            onSortOrderSelected(localField, localDirection)
                        }
                    )

                    // 按最后运行时间排序
                    EnhancedSortOptionItem(
                        title = stringResource(R.string.history_last_run_sort),
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
                        Text(stringResource(R.string.cancel))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onSortOrderSelected(localField, localDirection)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.confirm))
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
        title = { Text(stringResource(R.string.confirm_delete)) },
        text = {
            Text(
                text = if (isMultiSelect)
                    stringResource(R.string.confirm_delete_multiple, projectCount)
                else
                    stringResource(R.string.confirm_delete_single)
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
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
            text = stringResource(R.string.no_history),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.history_will_appear),
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
            text = stringResource(R.string.no_matching_projects),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (searchQuery.isNotEmpty())
                stringResource(R.string.no_match_for_query, searchQuery)
            else
                stringResource(R.string.no_match_for_filters),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(onClick = onClearFilters) {
            Text(stringResource(R.string.clear_filters))
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
            imageVector = Icons.Default.Close, // 错误图标
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
            text = stringResource(R.string.loading_failed),
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
            Text(stringResource(R.string.retry))
        }
    }
}

// 工具函数：格式化日期
private fun formatDate(date: Date): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(date)
}