package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science // 用于增强检测图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.ui.viewmodels.DetectionViewModel
import com.muc.fluocolorquant.ui.viewmodels.EnhancedWellDetection
import com.muc.fluocolorquant.ui.navigation.Screen
import kotlinx.coroutines.launch
import android.net.Uri
import android.graphics.RectF

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WellDetectionScreen(
    navController: NavController,
    imageUri: String? = null,
    projectId: String? = null,
    viewModel: DetectionViewModel = hiltViewModel(),
    concentrationViewModel: ConcentrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val detectionState by viewModel.detectionState.collectAsState()
    val originalBitmap by viewModel.originalBitmap.collectAsState()
    val selectedWellIndex by viewModel.selectedWellIndex.collectAsState()
    val enhancedDetections by viewModel.enhancedDetections.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 解码imageUri (如果是被编码的)
    val decodedImageUri = remember(imageUri) {
        try {
            imageUri?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } catch (e: Exception) {
            android.util.Log.e("WellDetection", "解码imageUri失败: ${e.message}", e)
            imageUri // 如果解码失败，则使用原始URI
        }
    }

    // 跟踪是否正在使用增强型检测
    var isEnhancedDetection by remember { mutableStateOf(false) } // 默认不是增强模式

    // 获取全局Toast管理器
    val toastManager = LocalToastManager.current

    // 显示提示对话框的状态
    var showInfoDialog by remember { mutableStateOf(false) }

    // 在@Composable上下文中获取所有需要的字符串资源
    val noWellsDetectedMsg = stringResource(R.string.no_wells_detected)
    val projectIdEmptyMsg = stringResource(R.string.project_id_empty)
    val savingDetectionResultsMsg = stringResource(R.string.saving_detection_results)
    val saveDetectionFailedMsg = stringResource(R.string.save_detection_failed)
    val saveDetectionFailedWithErrorMsg = stringResource(R.string.save_detection_failed_with_error)
    val imageUriEmptyMsg = stringResource(R.string.image_uri_empty)
    val enhancedDetectionStartingMsg = stringResource(R.string.enhanced_detection_starting)
    val standardDetectionStartingMsg = stringResource(R.string.standard_detection_starting) // 新增字符串
    val switchDetectionModeDescription = stringResource(R.string.switch_to_standard_detection) // 新增字符串

    // 保存当前项目ID到浓度预测ViewModel
    LaunchedEffect(projectId) {
        concentrationViewModel.setCurrentProjectId(projectId)
    }

    // 保存原始图像到浓度预测ViewModel
    LaunchedEffect(originalBitmap) {
        originalBitmap?.let {
            concentrationViewModel.setOriginalBitmap(it)
        }
    }

    // 处理返回导航
    val handleBackPress = {
        navController.popBackStack()
    }

    // 处理继续导航
    val handleContinue = handleContinue@{
        // 获取当前检测状态
        val currentState = viewModel.detectionState.value

        if (currentState is DetectionViewModel.DetectionState.Success) {
            // 检查是否有检测结果
            val detections = currentState.detections
            if (detections.isEmpty()) {
                toastManager.showToast(noWellsDetectedMsg, ToastType.ERROR)
                return@handleContinue
            }

            // 检查项目ID是否为空
            if (projectId.isNullOrEmpty()) {
                toastManager.showToast(projectIdEmptyMsg, ToastType.ERROR)
                return@handleContinue
            }

            // 如果使用了增强型检测，将增强型检测结果传递给浓度ViewModel
            if (isEnhancedDetection) {
                concentrationViewModel.setEnhancedDetections(enhancedDetections)
            }

            // 显示保存进度Toast
            toastManager.showToast(savingDetectionResultsMsg, ToastType.INFO)

            coroutineScope.launch {
                try {
                    // 保存检测结果
                    val newRunId = concentrationViewModel.saveDetectionResults(
                        detections = detections, // 这里传递的是包含用户拖动调整后的孔位信息
                        projectId = projectId
                    )

                    // 检查runId是否为空
                    if (newRunId != null) {
                        // 导航到曲线拟合屏幕，同时传递原始图像URI
                        navController.navigate(Screen.CurveFitting.createRoute(newRunId, decodedImageUri)) {
                            popUpTo(Screen.WellDetection.route) {
                                inclusive = true
                            }
                        }
                    } else {
                        toastManager.showToast(saveDetectionFailedMsg, ToastType.ERROR)
                    }
                } catch (e: Exception) {
                    toastManager.showToast(
                        String.format(saveDetectionFailedWithErrorMsg, e.message ?: ""),
                        ToastType.ERROR
                    )
                }
            }
        }
    }

    // 启动检测 (仅在imageUri变化时触发，避免重复检测)
    LaunchedEffect(key1 = imageUri) {
        if (decodedImageUri != null) {
            // 如果有项目ID，先加载项目信息
            if (!projectId.isNullOrEmpty()) {
                viewModel.loadProject(projectId)
            }
            // 默认使用标准检测模式
            isEnhancedDetection = false // 确保初始状态为标准检测
            viewModel.detectWells(decodedImageUri)
        } else {
            toastManager.showToast(imageUriEmptyMsg, ToastType.ERROR)
        }
    }

    // 处理切换检测模式 (标准检测 <-> 增强型检测)
    val toggleDetectionMode = {
        if (decodedImageUri == null) {
            toastManager.showToast(imageUriEmptyMsg, ToastType.ERROR)
        } else {
            // 如果当前是增强型模式，切换回标准模式；否则切换到增强型模式
            if (isEnhancedDetection) {
                isEnhancedDetection = false // 切换到标准模式
                toastManager.showToast(standardDetectionStartingMsg, ToastType.INFO)
                viewModel.detectWells(decodedImageUri) // 启动标准检测
            } else {
                isEnhancedDetection = true // 切换到增强型模式
                toastManager.showToast(enhancedDetectionStartingMsg, ToastType.INFO)
                viewModel.enhancedWellDetection(decodedImageUri) // 启动增强型检测
            }
        }
    }

    // 使用变量存储选中的孔位索引值，避免智能转换问题
    val currentSelectedIndex = selectedWellIndex ?: -1

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.well_detection_title)) },
                navigationIcon = {
                    IconButton(onClick = { handleBackPress() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.go_back)
                        )
                    }
                },
                actions = {
                    // 切换检测模式按钮
                    IconButton(
                        onClick = { toggleDetectionMode() }, // 调用新的切换函数
                        enabled = detectionState !is DetectionViewModel.DetectionState.Loading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science, // 图标可以根据模式变化
                            contentDescription = if (isEnhancedDetection) switchDetectionModeDescription else stringResource(R.string.enhanced_detection), // 切换描述
                            tint = if (isEnhancedDetection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant // 可以给增强模式加一个突出颜色
                        )
                    }

                    // 添加信息按钮，显示使用帮助
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.help_info)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text(stringResource(R.string.help_dialog_title)) },
                text = {
                    Text(stringResource(R.string.help_dialog_text))
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 内容区域
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 状态显示
                when (detectionState) {
                    is DetectionViewModel.DetectionState.Idle -> {
                        Text(
                            text = stringResource(R.string.preparing_detection),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                    }

                    is DetectionViewModel.DetectionState.Loading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.detecting_wells),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    is DetectionViewModel.DetectionState.Success -> {
                        val detections = (detectionState as DetectionViewModel.DetectionState.Success).detections
                        val currentProject by viewModel.currentProject.collectAsState()
                        val maxWellCount by viewModel.maxWellCount.collectAsState()

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.wells_detected, detections.size),
                                style = MaterialTheme.typography.titleMedium
                            )

                            // 显示项目行列信息（如果有）
                            if (currentProject != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.well_plate_size_info,
                                        currentProject?.rows ?: 8,
                                        currentProject?.columns ?: 12,
                                        maxWellCount
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // 显示是否使用增强型检测
                            if (isEnhancedDetection) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.enhanced_detection_active),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            // 图像和检测结果显示
                            if (originalBitmap != null) {
                                // 生成可交互的带检测框的图像
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(
                                            originalBitmap!!.width.toFloat() / originalBitmap!!.height.toFloat()
                                        )
                                ) {
                                    // 显示原始图像
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(originalBitmap)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = stringResource(R.string.well_array_image),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )

                                    // 绘制检测框或圆形
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                // 使用detectTapGestures处理点击事件
                                                detectTapGestures { offset ->
                                                    // 检查是否点击了某个孔位
                                                    val scale = minOf(
                                                        size.width / originalBitmap!!.width.toFloat(),
                                                        size.height / originalBitmap!!.height.toFloat()
                                                    )

                                                    // 计算图像在Canvas中的实际位置和尺寸
                                                    val scaledWidth = originalBitmap!!.width * scale
                                                    val scaledHeight = originalBitmap!!.height * scale
                                                    val leftPadding = (size.width - scaledWidth) / 2
                                                    val topPadding = (size.height - scaledHeight) / 2

                                                    // 检查点击是否在图像范围内
                                                    val clickInImageBounds = offset.x >= leftPadding &&
                                                            offset.x <= leftPadding + scaledWidth &&
                                                            offset.y >= topPadding &&
                                                            offset.y <= topPadding + scaledHeight

                                                    if (clickInImageBounds) {
                                                        // 将点击坐标转换为原始图像坐标
                                                        val imageX = (offset.x - leftPadding) / scale
                                                        val imageY = (offset.y - topPadding) / scale

                                                        // 检查点击是否在某个孔位内
                                                        var foundWell = false

                                                        // 查找点击位置下的孔位，不依赖于原始数组索引
                                                        for (i in detections.indices) {
                                                            val rect = detections[i].rect
                                                            if (imageX >= rect.left && imageX <= rect.right &&
                                                                imageY >= rect.top && imageY <= rect.bottom) {

                                                                // 选中孔位 - 使用孔位的唯一ID而非数组索引
                                                                viewModel.selectWellById(detections[i].id)
                                                                foundWell = true
                                                                break
                                                            }
                                                        }

                                                        // 如果没有点击到任何孔位，取消选中
                                                        if (!foundWell) {
                                                            viewModel.selectWell(null)
                                                        }
                                                    } else {
                                                        // 点击在图像外部，取消选中
                                                        viewModel.selectWell(null)
                                                    }
                                                }
                                            }
                                            .pointerInput(Unit) {
                                                // 使用detectDragGestures处理拖动事件
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        // 检查是否点击了某个孔位（用于拖动开始）
                                                        val scale = minOf(
                                                            size.width / originalBitmap!!.width.toFloat(),
                                                            size.height / originalBitmap!!.height.toFloat()
                                                        )

                                                        // 计算图像在Canvas中的实际位置和尺寸
                                                        val scaledWidth = originalBitmap!!.width * scale
                                                        val scaledHeight = originalBitmap!!.height * scale
                                                        val leftPadding = (size.width - scaledWidth) / 2
                                                        val topPadding = (size.height - scaledHeight) / 2

                                                        // 检查拖动开始位置是否在图像范围内
                                                        val dragInImageBounds = offset.x >= leftPadding &&
                                                                offset.x <= leftPadding + scaledWidth &&
                                                                offset.y >= topPadding &&
                                                                offset.y <= topPadding + scaledHeight

                                                        if (dragInImageBounds) {
                                                            // 将点击坐标转换为原始图像坐标
                                                            val imageX = (offset.x - leftPadding) / scale
                                                            val imageY = (offset.y - topPadding) / scale

                                                            // 检查点击是否在某个孔位内
                                                            for (i in detections.indices) {
                                                                val rect = detections[i].rect
                                                                if (imageX >= rect.left && imageX <= rect.right &&
                                                                    imageY >= rect.top && imageY <= rect.bottom) {
                                                                    // 选中孔位，准备拖动 - 使用孔位的唯一ID
                                                                    viewModel.selectWellById(detections[i].id)
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()

                                                        // 如果不是增强型检测模式且有选中的孔位，则移动它
                                                        if (!isEnhancedDetection && selectedWellIndex != null) {
                                                            // 计算缩放比例
                                                            val scale = minOf(
                                                                size.width / originalBitmap!!.width.toFloat(),
                                                                size.height / originalBitmap!!.height.toFloat()
                                                            )

                                                            // 将拖动量转换为原始图像坐标系
                                                            val dx = dragAmount.x / scale
                                                            val dy = dragAmount.y / scale

                                                            // 定义图像边界
                                                            val imageBounds = RectF(
                                                                0f,
                                                                0f,
                                                                originalBitmap!!.width.toFloat(),
                                                                originalBitmap!!.height.toFloat()
                                                            )

                                                            // 使用带边界检查的移动方法
                                                            // 确保 selectedWellIndex 有效，并且它对应的孔位在当前 detections 列表中
                                                            selectedWellIndex?.let { index -> // 使用?.let安全地处理可空值
                                                                if (index >= 0 && index < detections.size) {
                                                                    // 直接通过索引移动选中的孔位
                                                                    viewModel.moveSelectedWell(dx, dy, imageBounds)
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        // 计算缩放比例
                                        val scale = minOf(
                                            size.width / originalBitmap!!.width.toFloat(),
                                            size.height / originalBitmap!!.height.toFloat()
                                        )

                                        // 计算图像在Canvas中的实际位置和尺寸
                                        val scaledWidth = originalBitmap!!.width * scale
                                        val scaledHeight = originalBitmap!!.height * scale
                                        val leftPadding = (size.width - scaledWidth) / 2
                                        val topPadding = (size.height - scaledHeight) / 2

                                        if (!isEnhancedDetection || enhancedDetections.isEmpty()) {
                                            // 标准模式：绘制矩形框
                                            for (i in detections.indices) {
                                                val well = detections[i]

                                                // 确定颜色: 选中的孔位显示为绿色，其他为红色
                                                val strokeColor = if (currentSelectedIndex >= 0 && currentSelectedIndex < detections.size) {
                                                    val selectedWellInDetections = detections.getOrNull(currentSelectedIndex) // 安全访问
                                                    if (selectedWellInDetections != null && selectedWellInDetections.id == well.id) Color.Green else Color.Red
                                                } else {
                                                    Color.Red
                                                }

                                                // 绘制矩形框
                                                drawRect(
                                                    color = strokeColor,
                                                    topLeft = Offset(
                                                        x = leftPadding + well.rect.left * scale,
                                                        y = topPadding + well.rect.top * scale
                                                    ),
                                                    size = androidx.compose.ui.geometry.Size(
                                                        width = well.rect.width() * scale,
                                                        height = well.rect.height() * scale
                                                    ),
                                                    style = Stroke(width = 2.dp.toPx())
                                                )

                                                // 绘制孔位ID指示器
                                                drawCircle(
                                                    color = strokeColor.copy(alpha = 0.7f),
                                                    radius = 12.dp.toPx(),
                                                    center = Offset(
                                                        x = leftPadding + (well.rect.left + 20) * scale,
                                                        y = topPadding + (well.rect.top + 20) * scale
                                                    )
                                                )
                                            }
                                        } else {
                                            // 增强型模式：绘制圆形和填充颜色
                                            for (i in enhancedDetections.indices) {
                                                val enhancedWell = enhancedDetections[i]

                                                // 确定颜色: 选中的孔位显示为绿色，其他为根据检测到的平均颜色或红色
                                                val isSelected = currentSelectedIndex >= 0 &&
                                                        currentSelectedIndex < detections.size &&
                                                        detections[currentSelectedIndex].id == enhancedWell.id

                                                if (enhancedWell.circleX != null && enhancedWell.circleY != null && enhancedWell.radius != null) {
                                                    // 圆心和半径
                                                    val circleX = enhancedWell.circleX!!
                                                    val circleY = enhancedWell.circleY!!
                                                    val radius = enhancedWell.radius!!

                                                    // 检测到的中心颜色或默认颜色
                                                    val fillColor = if (enhancedWell.centerColor != null) {
                                                        Color(enhancedWell.centerColor!!)
                                                    } else {
                                                        Color.Red.copy(alpha = 0.3f)
                                                    }

                                                    // 圆形轮廓颜色
                                                    val strokeColor = if (isSelected) Color.Green else Color.Red

                                                    // 绘制填充圆形（使用检测到的中心颜色）
                                                    drawCircle(
                                                        color = fillColor.copy(alpha = 0.7f), // 使颜色半透明
                                                        radius = radius * scale,
                                                        center = Offset(
                                                            x = leftPadding + circleX * scale,
                                                            y = topPadding + circleY * scale
                                                        )
                                                    )

                                                    // 绘制圆形轮廓
                                                    drawCircle(
                                                        color = strokeColor,
                                                        radius = radius * scale,
                                                        center = Offset(
                                                            x = leftPadding + circleX * scale,
                                                            y = topPadding + circleY * scale
                                                        ),
                                                        style = Stroke(width = 2.dp.toPx())
                                                    )

                                                    // 绘制孔位ID指示器
                                                    drawCircle(
                                                        color = strokeColor.copy(alpha = 0.7f),
                                                        radius = 12.dp.toPx(),
                                                        center = Offset(
                                                            x = leftPadding + (enhancedWell.rect.left + 20) * scale,
                                                            y = topPadding + (enhancedWell.rect.top + 20) * scale
                                                        )
                                                    )
                                                } else {
                                                    // 如果圆检测失败，回退到矩形显示
                                                    val strokeColor = if (isSelected) Color.Green else Color.Red

                                                    drawRect(
                                                        color = strokeColor,
                                                        topLeft = Offset(
                                                            x = leftPadding + enhancedWell.rect.left * scale,
                                                            y = topPadding + enhancedWell.rect.top * scale
                                                        ),
                                                        size = androidx.compose.ui.geometry.Size(
                                                            width = enhancedWell.rect.width() * scale,
                                                            height = enhancedWell.rect.height() * scale
                                                        ),
                                                        style = Stroke(width = 2.dp.toPx())
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // 提示信息
                                if (isEnhancedDetection) {
                                    Text(
                                        text = stringResource(R.string.enhanced_detection_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.click_and_drag_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    is DetectionViewModel.DetectionState.Error -> {
                        val error = (detectionState as DetectionViewModel.DetectionState.Error).message

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = stringResource(R.string.detection_error, ""),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = stringResource(R.string.detection_error, error),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (decodedImageUri != null) {
                                        // 根据当前模式重试
                                        if (isEnhancedDetection) {
                                            viewModel.enhancedWellDetection(decodedImageUri)
                                        } else {
                                            viewModel.detectWells(decodedImageUri)
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            }

            // 底部按钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 返回按钮
                    OutlinedButton(
                        onClick = { handleBackPress() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.go_back))
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // 继续按钮
                    Button(
                        onClick = { handleContinue() },
                        modifier = Modifier.weight(1f),
                        enabled = detectionState is DetectionViewModel.DetectionState.Success
                    ) {
                        Text(stringResource(R.string.continue_button))
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
