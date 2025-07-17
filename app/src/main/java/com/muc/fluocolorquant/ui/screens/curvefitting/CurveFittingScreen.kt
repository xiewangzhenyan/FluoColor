package com.muc.fluocolorquant.ui.screens.curvefitting

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ManualFittingDialog
import com.muc.fluocolorquant.ui.components.RealWellPreviewGrid
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.VirtualLayoutInteractionBoard
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.WellLayoutViewModel
import com.muc.fluocolorquant.utils.math.FittingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 孔位处理与预览页面
 * 用于孔板布局定义和分析方案配置
 */
@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurveFittingScreen(
    navController: NavController,
    projectId: String? = null,
    runId: String? = null,
    imageUri: String? = null,
    wellLayoutViewModel: WellLayoutViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastManager = LocalToastManager.current

    // 在Composable的顶层对接收到的URI进行解码
    val decodedImageUri by remember(imageUri) {
        mutableStateOf(
            try {
                imageUri?.let { android.net.Uri.decode(it) }
            } catch (e: Exception) {
                android.util.Log.e("CurveFittingScreen", "解码Image URI失败: ${e.message}", e)
                null
            }
        )
    }

    // 核心状态只来自 wellLayoutViewModel
    val layoutState by wellLayoutViewModel.layoutState.collectAsState()
    val currentProject by wellLayoutViewModel.currentProject.collectAsState()
    val wellResults by wellLayoutViewModel.wellResults.collectAsState()
    val availableAnalytes by wellLayoutViewModel.availableAnalytes.collectAsState()
    val selectedAnalyte by wellLayoutViewModel.selectedAnalyte.collectAsState()
    val selectedRoleType by wellLayoutViewModel.selectedRoleType.collectAsState()
    val availableTemplates by wellLayoutViewModel.availableTemplates.collectAsState()
    val pixelExtractionProgress by wellLayoutViewModel.pixelExtractionProgress.collectAsState()

    // 获取拟合对话框相关状态
    val showManualFittingDialog by wellLayoutViewModel.showManualFittingDialog.collectAsState()
    val fittingResults by wellLayoutViewModel.fittingResults.collectAsState()
    val isFittingLoading by wellLayoutViewModel.isFittingLoading.collectAsState()
    val standardCount by wellLayoutViewModel.standardWellsCount.collectAsState()

    // 新增：获取多分析物状态
    val analyteFittingStatus by wellLayoutViewModel.analyteFittingStatus.collectAsState()
    val allAnalytesConfigured by wellLayoutViewModel.allAnalytesConfigured.collectAsState()

    // 初始化处理 - 仅执行一次 (修改部分，添加导航回调)
    LaunchedEffect(key1 = projectId, key2 = runId, key3 = decodedImageUri) {
        // 确保所有必要的参数都已提供
        if (projectId.isNullOrEmpty() || runId.isNullOrEmpty()) {
            android.util.Log.e("CurveFittingScreen", "缺少 projectId 或 runId，无法初始化。projectId: $projectId, runId: $runId")
            toastManager.showToast(context.getString(R.string.missing_parameters), ToastType.ERROR)
            return@LaunchedEffect
        }

        android.util.Log.d("CurveFittingScreen", "开始初始化，projectId: $projectId, runId: $runId, imageUri: $decodedImageUri")

        // 首先，从解码后的URI加载原始图像并设置到ViewModel中
        if (!decodedImageUri.isNullOrEmpty()) {
            try {
                android.util.Log.d("CurveFittingScreen", "尝试从解码后的URI加载图像: $decodedImageUri")
                val uri = android.net.Uri.parse(decodedImageUri)
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    inputStream?.use { BitmapFactory.decodeStream(it) }
                }

                if (bitmap != null) {
                    android.util.Log.d("CurveFittingScreen", "成功从URI加载图像，大小: ${bitmap.width}x${bitmap.height}")
                    wellLayoutViewModel.setOriginalBitmap(bitmap)
                } else {
                    android.util.Log.e("CurveFittingScreen", "从URI加载图像失败，bitmap为null")
                    toastManager.showToast(
                        message = context.getString(R.string.image_load_failed),
                        type = ToastType.ERROR
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("CurveFittingScreen", "加载图像异常: ${e.message}", e)
                toastManager.showToast(
                    message = context.getString(R.string.image_load_failed),
                    type = ToastType.ERROR
                )
            }
        } else {
            android.util.Log.w("CurveFittingScreen", "解码后的imageUri为空，无法加载原始图像")
        }

        // 使用传入的 projectId 和 runId 初始化布局，并添加导航回调
        android.util.Log.d("CurveFittingScreen", "使用 projectId: $projectId 和 runId: $runId 初始化布局")
        wellLayoutViewModel.initLayout(projectId, runId) { finalRunId ->
            // 这是处理成功后的导航回调，自动进入结果页面
            navController.navigate(Screen.Result.createRoute(finalRunId)) {
                // 避免返回到曲线拟合页面
                popUpTo(Screen.CurveFitting.route + "/{projectId}/{runId}/{imageUri}") {
                    inclusive = true
                }
            }
        }
    }

    // 显示手动拟合对话框
    if (showManualFittingDialog) {
        ManualFittingDialog(
            standardWells = wellLayoutViewModel.getStandardWells(),
            fittingResults = fittingResults,
            isLoading = isFittingLoading,
            onDismiss = {
                wellLayoutViewModel.dismissManualFitDialog()
            },
            onStartFitting = { concentrations: Map<Int, Double>, functions: Set<FittingFunction>, pixelTypes: Set<PixelType> ->
                wellLayoutViewModel.executeFitting(concentrations, functions, pixelTypes)
            },
            onResultSelected = { result: FittingResult ->
                wellLayoutViewModel.onFittingResultSelected(result)
            },
            onSaveAsTemplate = { result: FittingResult ->
                scope.launch {
                    val templateId = wellLayoutViewModel.saveFittingResultAsTemplate(result)
                    if (templateId != null) {
                        wellLayoutViewModel.dismissManualFitDialog()
                        // 导航到模板编辑页面 - 使用CreateExperimentTemplate替代EditExperimentTemplate
                        navController.navigate(Screen.CreateExperimentTemplate.createRoute(templateId))
                    } else {
                        toastManager.showToast(
                            message = context.getString(R.string.save_template_failed),
                            type = ToastType.ERROR
                        )
                    }
                }
            },
            onProcessNext = { result: FittingResult ->
                wellLayoutViewModel.confirmManualFit(result)
            },
            allAnalytesConfigured = allAnalytesConfigured
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.well_layout_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        // 【关键修改】直接导航回主页，并清空之上的所有页面
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp), // 只保留水平padding
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 16.dp) // 垂直padding给content
        ) {
            // UI完全由 layoutState 驱动
            when (val state = layoutState) {
                is WellLayoutViewModel.LayoutState.Loading -> {
                    // 初始加载状态
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.loading),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is WellLayoutViewModel.LayoutState.Processing -> {
                    // 处理中状态，显示具体消息和进度
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )

                            if (state.progress > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress / 100f },
                                    modifier = Modifier.fillMaxWidth(0.8f)
                                )
                                Text(
                                    text = "${state.progress}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                // 新增：添加AutoProcessing状态UI
                is WellLayoutViewModel.LayoutState.AutoProcessing -> {
                    // 自动处理状态，显示消息和进度
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )

                            if (state.progress > 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress / 100f },
                                    modifier = Modifier.fillMaxWidth(0.8f)
                                )
                                Text(
                                    text = "${state.progress}%",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            // 提示用户此为自动处理流程
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = stringResource(R.string.auto_processing_info),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                is WellLayoutViewModel.LayoutState.Error -> {
                    item {
                        ErrorDisplay(errorMessage = state.message) {
                            if (!runId.isNullOrEmpty()) {
                                val projectId = currentProject?.id ?: ""
                                if (projectId.isNotEmpty()) {
                                    wellLayoutViewModel.initLayout(projectId, runId) { finalRunId ->
                                        navController.navigate(Screen.Result.createRoute(finalRunId)) {
                                            popUpTo(Screen.CurveFitting.route + "/{projectId}/{runId}/{imageUri}") {
                                                inclusive = true
                                            }
                                        }
                                    }
                                } else {
                                    toastManager.showToast(
                                        message = context.getString(R.string.project_not_found),
                                        type = ToastType.ERROR
                                    )
                                }
                            }
                        }
                    }
                }

                is WellLayoutViewModel.LayoutState.Ready -> {
                    // 数据准备就绪，显示双层布局
                    val project = currentProject ?: return@LazyColumn
                    val results = wellResults

                    // 项目信息
                    item {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Text(
                            text = "${project.rows} × ${project.columns} ${stringResource(R.string.well_plate)}",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }

                    // 真实孔位预览
                    item {
                        RealWellPreviewGrid(
                            wellResults = results,
                            project = project,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 分隔线
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // 虚拟布局交互板
                    item {
                        // 不再根据分析方法分别显示不同的面板，而是统一显示VirtualLayoutInteractionBoard
                        // VirtualLayoutInteractionBoard内部会自己处理不同工作流的渲染
                        VirtualLayoutInteractionBoard(
                            project = project,
                            availableAnalytes = availableAnalytes,
                            selectedAnalyte = selectedAnalyte,
                            selectedRoleType = selectedRoleType,
                            availableTemplates = availableTemplates,
                            wellResults = results,
                            standardWellsCount = standardCount,
                            availableDlModels = wellLayoutViewModel.availableDlModels.collectAsState().value,
                            analyteFittingStatus = analyteFittingStatus.keys, // 只传递分析物ID的集合
                            onAnalyteSelected = { wellLayoutViewModel.selectAnalyte(it) },
                            onRoleTypeSelected = { wellLayoutViewModel.selectRoleType(it) },
                            onTemplateSelected = { wellLayoutViewModel.selectTemplate(it) },
                            onWellClicked = { row, col -> wellLayoutViewModel.markWell(row, col) },
                            onManualFittingClicked = {
                                wellLayoutViewModel.openManualFittingDialog()
                            },
                            onApplyModel = { modelName ->
                                val successMessage = context.getString(R.string.model_applied_successfully)
                                wellLayoutViewModel.applyDlModel(modelName) {
                                    toastManager.showToast(
                                        successMessage,
                                        ToastType.SUCCESS
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 下一步按钮区域
                    item {
                        Spacer(modifier = Modifier.height(24.dp))

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 显示分析物配置状态
                            val configuredAnalytes = analyteFittingStatus.size
                            val totalAnalytes = availableAnalytes.size

                            LinearProgressIndicator(
                                progress = { configuredAnalytes.toFloat() / totalAnalytes.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = stringResource(
                                    R.string.analyte_configuration_status,
                                    configuredAnalytes,
                                    totalAnalytes
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    if (allAnalytesConfigured) {
                                        // 所有分析物已配置，可以直接计算结果
                                        // 提前获取消息字符串
                                        val processFailedMsg = context.getString(R.string.process_failed)
                                        scope.launch {
                                            wellLayoutViewModel.processAllSamples { success ->
                                                if (success) {
                                                    // 导航到结果页面
                                                    runId?.let { id ->
                                                        navController.navigate(Screen.Result.createRoute(id)) {
                                                            // 避免返回到曲线拟合页面
                                                            popUpTo(Screen.CurveFitting.route + "/{projectId}/{runId}/{imageUri}") {
                                                                inclusive = true
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // 处理失败，显示错误提示
                                                    toastManager.showToast(
                                                        message = processFailedMsg,
                                                        type = ToastType.ERROR
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        // 尝试处理下一个分析物
                                        // 提前获取所有可能用到的消息字符串
                                        val analyteSuccessMsg = context.getString(R.string.analyte_processed_successfully)
                                        val analyteFailedMsg = context.getString(R.string.analyte_process_failed)
                                        val allConfiguredMsg = context.getString(R.string.all_analytes_configured)
                                        val needMoreStandardsMsg = context.getString(R.string.need_more_standards)

                                        scope.launch {
                                            wellLayoutViewModel.processNextAnalyte { success, nextAnalyteId ->
                                                if (success && nextAnalyteId != null) {
                                                    // 有可以自动处理的分析物
                                                    wellLayoutViewModel.processSingleAnalyte(nextAnalyteId) { processingSuccess ->
                                                        if (processingSuccess) {
                                                            toastManager.showToast(
                                                                message = analyteSuccessMsg,
                                                                type = ToastType.SUCCESS
                                                            )
                                                        } else {
                                                            toastManager.showToast(
                                                                message = analyteFailedMsg,
                                                                type = ToastType.ERROR
                                                            )
                                                        }
                                                    }
                                                } else if (success && nextAnalyteId == null) {
                                                    // 所有分析物已处理完成
                                                    toastManager.showToast(
                                                        message = allConfiguredMsg,
                                                        type = ToastType.SUCCESS
                                                    )
                                                } else {
                                                    // 需要手动配置
                                                    if (wellLayoutViewModel.hasEnoughStandards()) {
                                                        wellLayoutViewModel.openManualFittingDialog()
                                                    } else {
                                                        toastManager.showToast(
                                                            message = needMoreStandardsMsg,
                                                            type = ToastType.WARNING
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = allAnalytesConfigured // 只有全部配置完成时才可点击
                            ) {
                                Text(
                                    text = stringResource(R.string.show_results)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 错误显示
 */
@Composable
fun ErrorDisplay(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}