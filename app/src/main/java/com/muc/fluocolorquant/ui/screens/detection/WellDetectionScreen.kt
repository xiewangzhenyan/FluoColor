package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science // 用于增强检测图标
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.ui.window.Dialog
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
import android.graphics.Bitmap

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

    // 添加单个孔位缩放预览状态
    var showSingleWellZoomDialog by remember { mutableStateOf(false) }
    var singleWellCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var singleWellOriginalRect by remember { mutableStateOf<RectF?>(null) }
    var singleWellAdjustedRect by remember { mutableStateOf<RectF?>(null) }
    var selectedWellId by remember { mutableStateOf<Int?>(null) }

    // 在@Composable上下文中获取所有需要的字符串资源
    val noWellsDetectedMsg = stringResource(R.string.no_wells_detected)
    val projectIdEmptyMsg = stringResource(R.string.project_id_empty)
    val savingDetectionResultsMsg = stringResource(R.string.saving_detection_results)
    val saveDetectionFailedMsg = stringResource(R.string.save_detection_failed)
    val saveDetectionFailedWithErrorMsg = stringResource(R.string.save_detection_failed_with_error)
    val imageUriEmptyMsg = stringResource(R.string.image_uri_empty)
    val enhancedDetectionStartingMsg = stringResource(R.string.enhanced_detection_starting)
    val standardDetectionStartingMsg = stringResource(R.string.standard_detection_starting)
    val switchDetectionModeDescription = stringResource(R.string.switch_to_standard_detection)
    val zoomWellMsg = stringResource(R.string.zoom_well)

    // 单个孔位缩放预览相关字符串
    val singleWellZoomTitleMsg = stringResource(R.string.single_well_zoom_title)
    val singleWellZoomMessageMsg = stringResource(R.string.single_well_zoom_message)
    val singleWellZoomConfirmMsg = stringResource(R.string.single_well_zoom_confirm)
    val singleWellZoomCancelMsg = stringResource(R.string.single_well_zoom_cancel)
    val pleaseSelectWellFirstMsg = stringResource(R.string.please_select_well_first)
    val wellAdjustedSuccessMsg = stringResource(R.string.well_adjusted_success)

    // 保存当前项目ID到浓度预测ViewModel
    LaunchedEffect(projectId) {
        concentrationViewModel.setCurrentProjectId(projectId)
    }

    // 保存原始图像到浓度预测ViewModel
    LaunchedEffect(originalBitmap) {
        originalBitmap?.let {
            android.util.Log.d("WellDetectionScreen", "将原始图像传递给ConcentrationViewModel, 大小: ${it.width}x${it.height}")
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

            // 确保原始图像存在
            val originalBitmap = viewModel.originalBitmap.value
            if (originalBitmap == null) {
                android.util.Log.e("WellDetectionScreen", "原始图像为空，无法继续")
                toastManager.showToast(context.getString(R.string.original_image_load_error), ToastType.ERROR)
                return@handleContinue
            }

            // 显示保存进度Toast
            toastManager.showToast(savingDetectionResultsMsg, ToastType.INFO)

            coroutineScope.launch {
                try {
                    // 保存检测结果 - 直接使用 DetectionViewModel 保存，不再通过 ConcentrationViewModel
                    android.util.Log.d("WellDetectionScreen", "开始保存检测结果，共 ${detections.size} 个孔位")
                    val newRunId = viewModel.saveDetectionResults(
                        detections = detections, // 这里传递的是包含用户拖动调整后的孔位信息
                        projectId = projectId
                    )

                    // 检查runId是否为空
                    if (newRunId != null) {
                        android.util.Log.d("WellDetectionScreen", "检测结果保存成功，runId: $newRunId")
                        
                        // 确保projectId不为空，传递projectId到CurveFittingScreen
                        android.util.Log.d("WellDetectionScreen", "导航到曲线拟合屏幕，projectId: $projectId, runId: $newRunId")
                        
                        // 对URI进行编码，避免特殊字符导致的导航问题
                        if (!decodedImageUri.isNullOrEmpty()) {
                            val encodedUri = android.net.Uri.encode(decodedImageUri)
                            android.util.Log.d("WellDetectionScreen", "原始URI: $decodedImageUri")
                            android.util.Log.d("WellDetectionScreen", "编码后URI: $encodedUri")
                            
                            navController.navigate(Screen.CurveFitting.createRoute(projectId, newRunId, encodedUri)) {
                                popUpTo(Screen.WellDetection.route) {
                                    inclusive = true
                                }
                            }
                        } else {
                            // 如果imageUri意外为空，给出提示
                            toastManager.showToast(context.getString(R.string.image_uri_empty), ToastType.ERROR)
                            android.util.Log.e("WellDetectionScreen", "无法获取图像信息，导航失败")
                        }
                    } else {
                        android.util.Log.e("WellDetectionScreen", "保存检测结果失败，runId为空")
                        toastManager.showToast(saveDetectionFailedMsg, ToastType.ERROR)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("WellDetectionScreen", "保存检测结果异常: ${e.message}", e)
                    toastManager.showToast(
                        String.format(saveDetectionFailedWithErrorMsg, e.message ?: ""),
                        ToastType.ERROR
                    )
                }
            }
        } else {
            android.util.Log.e("WellDetectionScreen", "当前状态不是成功状态，无法继续")
            toastManager.showToast(context.getString(R.string.detection_not_complete), ToastType.ERROR)
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

    // 处理单个孔位缩放
    val handleSingleWellZoom = lambda@{
        // 获取当前选中的孔位ID
        val wellId = viewModel.getSelectedWellId()

        if (wellId == null) {
            // 如果没有选中的孔位，显示提示
            toastManager.showToast(pleaseSelectWellFirstMsg, ToastType.WARNING)
            return@lambda
        }

        // 检查对话框是否已经显示
        if (showSingleWellZoomDialog) {
            // 如果对话框已经显示，直接返回，不做任何改变
            return@lambda
        }

        // 获取裁剪后的孔位图像
        val croppedBitmap = viewModel.getCroppedWellBitmap(wellId)

        if (croppedBitmap != null) {
            // 获取原始矩形
            val originalRect = viewModel.getSelectedWellRect()

            if (originalRect != null) {
                // 检查是否是同一个孔位
                val isNewWell = selectedWellId != wellId

                // 保存孔位ID
                selectedWellId = wellId

                // 只有在新孔位时才更新原始矩形
                if (isNewWell) {
                    singleWellOriginalRect = originalRect
                    // 仅当是新的孔位或调整后的矩形为null时才初始化为原始矩形
                    if (singleWellAdjustedRect == null || isNewWell) {
                        singleWellAdjustedRect = RectF(originalRect)
                    }
                } else if (singleWellAdjustedRect == null) {
                    // 如果是同一个孔位，但调整矩形丢失了，则重新初始化
                    singleWellAdjustedRect = RectF(originalRect)
                }


                // 保存裁剪后的图像
                singleWellCroppedBitmap = croppedBitmap
                // 显示对话框
                showSingleWellZoomDialog = true
            }
        }
    }

    // 使用变量存储选中的孔位索引值，避免智能转换问题
    val currentSelectedIndex = selectedWellIndex ?: -1

    // 单个孔位缩放预览对话框
    if (showSingleWellZoomDialog && singleWellCroppedBitmap != null && singleWellAdjustedRect != null) {
        Dialog(
            onDismissRequest = {
                // 只关闭对话框和清理临时资源，保留调整状态
                showSingleWellZoomDialog = false
                singleWellCroppedBitmap = null
                // 不重置singleWellAdjustedRect，以保持用户的调整状态
            }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 标题
                    Text(
                        text = singleWellZoomTitleMsg,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 提示信息
                    Text(
                        text = singleWellZoomMessageMsg,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 预览区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(8.dp)
                    ) {
                        // 显示裁剪后的图像
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(singleWellCroppedBitmap)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // 绘制可调整的矩形框
                        val croppedBitmap = singleWellCroppedBitmap!!
                        val originalRect = singleWellOriginalRect!!
                        val adjustedRect = singleWellAdjustedRect!!

                        // 计算裁剪区域（与DetectionViewModel.getCroppedWellBitmap方法保持一致）
                        val centerX = (originalRect.left + originalRect.right) / 2
                        val centerY = (originalRect.top + originalRect.bottom) / 2
                        val expansionFactor = 1.5f
                        val width = originalRect.width() * expansionFactor
                        val height = originalRect.height() * expansionFactor

                        // 计算裁剪区域的左上角坐标
                        val cropLeft = (centerX - width / 2).coerceAtLeast(0f)
                        val cropTop = (centerY - height / 2).coerceAtLeast(0f)

                        // 添加拖动状态跟踪
                        var dragMode by remember { mutableStateOf(0) } // 0: 无拖动, 1-4: 四个角, 5: 整体移动

                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            // 计算缩放比例
                                            val scale = minOf(
                                                size.width / croppedBitmap.width.toFloat(),
                                                size.height / croppedBitmap.height.toFloat()
                                            )

                                            // 计算图像在Canvas中的实际位置和尺寸
                                            val scaledWidth = croppedBitmap.width * scale
                                            val scaledHeight = croppedBitmap.height * scale
                                            val leftPadding = (size.width - scaledWidth) / 2
                                            val topPadding = (size.height - scaledHeight) / 2

                                            // 计算当前触摸点在原始图像坐标系中的位置
                                            val touchX = (offset.x - leftPadding) / scale + cropLeft
                                            val touchY = (offset.y - topPadding) / scale + cropTop

                                            // 计算四个角落点的位置
                                            val cornerSize = 25f // 增大角落判断半径，使得更容易选中角落
                                            val corners = listOf(
                                                Pair(adjustedRect.left, adjustedRect.top), // 左上
                                                Pair(adjustedRect.right, adjustedRect.top), // 右上
                                                Pair(adjustedRect.left, adjustedRect.bottom), // 左下
                                                Pair(adjustedRect.right, adjustedRect.bottom) // 右下
                                            )

                                            // 计算触摸点与各个角落的距离
                                            val distances = corners.mapIndexed { index, corner ->
                                                val distX = touchX - corner.first
                                                val distY = touchY - corner.second
                                                Triple(index + 1, Math.sqrt((distX * distX + distY * distY).toDouble()), corner)
                                            }

                                            // 找到最近的角落点
                                            val minDistance = distances.minByOrNull { it.second }
                                            if (minDistance != null && minDistance.second < cornerSize) {
                                                // 设置拖动模式为对应的角落
                                                dragMode = minDistance.first
                                            } else if (touchX >= adjustedRect.left && touchX <= adjustedRect.right &&
                                                touchY >= adjustedRect.top && touchY <= adjustedRect.bottom) {
                                                // 如果在矩形内部，设置为整体移动模式
                                                dragMode = 5
                                            } else {
                                                // 不在任何有效区域，不进行拖动
                                                dragMode = 0
                                            }
                                        },
                                        onDragEnd = {
                                            // 重置拖动模式
                                            dragMode = 0
                                        },
                                        onDragCancel = {
                                            // 重置拖动模式
                                            dragMode = 0
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()

                                            if (dragMode == 0) {
                                                return@detectDragGestures
                                            }

                                            // 直接从 state 读取最新的矩形
                                            val currentRect = singleWellAdjustedRect ?: return@detectDragGestures

                                            // 计算缩放比例
                                            val scale = minOf(
                                                size.width / croppedBitmap.width.toFloat(),
                                                size.height / croppedBitmap.height.toFloat()
                                            )

                                            // 将拖动量转换为原始图像坐标系
                                            val dx = dragAmount.x / scale
                                            val dy = dragAmount.y / scale

                                            // 创建新的矩形，基于当前的矩形状态进行修改
                                            val newRect = RectF(currentRect)

                                            // 根据拖动模式调整矩形
                                            when (dragMode) {
                                                1 -> { // 左上角
                                                    newRect.left += dx
                                                    newRect.top += dy
                                                }
                                                2 -> { // 右上角
                                                    newRect.right += dx
                                                    newRect.top += dy
                                                }
                                                3 -> { // 左下角
                                                    newRect.left += dx
                                                    newRect.bottom += dy
                                                }
                                                4 -> { // 右下角
                                                    newRect.right += dx
                                                    newRect.bottom += dy
                                                }
                                                5 -> { // 整体移动
                                                    newRect.left += dx
                                                    newRect.top += dy
                                                    newRect.right += dx
                                                    newRect.bottom += dy
                                                }
                                            }

                                            // 确保矩形不超出裁剪区域且宽高为正
                                            val maxRight = cropLeft + croppedBitmap.width
                                            val maxBottom = cropTop + croppedBitmap.height

                                            // 添加最小尺寸限制，防止矩形过小
                                            val minSize = 20f

                                            // 应用约束
                                            val constrainedRect = RectF(newRect)

                                            // 确保左边界在有效范围内
                                            constrainedRect.left = constrainedRect.left.coerceIn(cropLeft, maxRight - minSize)

                                            // 确保上边界在有效范围内
                                            constrainedRect.top = constrainedRect.top.coerceIn(cropTop, maxBottom - minSize)

                                            // 确保右边界在有效范围内，并且宽度至少为minSize
                                            constrainedRect.right = constrainedRect.right.coerceIn(
                                                constrainedRect.left + minSize,
                                                maxRight
                                            )

                                            // 确保下边界在有效范围内，并且高度至少为minSize
                                            constrainedRect.bottom = constrainedRect.bottom.coerceIn(
                                                constrainedRect.top + minSize,
                                                maxBottom
                                            )

                                            // 更新调整后的矩形状态
                                            singleWellAdjustedRect = constrainedRect
                                        }
                                    )
                                }
                        ) {
                            // 计算缩放比例
                            val scale = minOf(
                                size.width / croppedBitmap.width.toFloat(),
                                size.height / croppedBitmap.height.toFloat()
                            )

                            // 计算图像在Canvas中的实际位置和尺寸
                            val scaledWidth = croppedBitmap.width * scale
                            val scaledHeight = croppedBitmap.height * scale
                            val leftPadding = (size.width - scaledWidth) / 2
                            val topPadding = (size.height - scaledHeight) / 2

                            // 绘制调整后的矩形框
                            val rectLeft = leftPadding + (adjustedRect.left - cropLeft) * scale
                            val rectTop = topPadding + (adjustedRect.top - cropTop) * scale
                            val rectRight = leftPadding + (adjustedRect.right - cropLeft) * scale
                            val rectBottom = topPadding + (adjustedRect.bottom - cropTop) * scale

                            // 绘制矩形框
                            drawRect(
                                color = Color.Green,
                                topLeft = Offset(rectLeft, rectTop),
                                size = androidx.compose.ui.geometry.Size(
                                    width = rectRight - rectLeft,
                                    height = rectBottom - rectTop
                                ),
                                style = Stroke(width = 2.dp.toPx())
                            )

                            // 绘制四个角落的拖动点
                            val cornerRadius = 10.dp.toPx()
                            val corners = listOf(
                                Offset(rectLeft, rectTop),
                                Offset(rectRight, rectTop),
                                Offset(rectLeft, rectBottom),
                                Offset(rectRight, rectBottom)
                            )

                            corners.forEachIndexed { index, corner ->
                                // 根据当前拖动模式设置颜色
                                val cornerColor = if (dragMode == index + 1) {
                                    Color.Yellow // 被拖动的角落点高亮显示
                                } else {
                                    Color.Green
                                }

                                // 绘制角落点
                                drawCircle(
                                    color = cornerColor,
                                    radius = cornerRadius,
                                    center = corner,
                                    alpha = 0.8f
                                )
                            }

                            // 如果是整体移动模式，绘制一个半透明的填充矩形表示选中状态
                            if (dragMode == 5) {
                                drawRect(
                                    color = Color.Green,
                                    topLeft = Offset(rectLeft, rectTop),
                                    size = androidx.compose.ui.geometry.Size(
                                        width = rectRight - rectLeft,
                                        height = rectBottom - rectTop
                                    ),
                                    alpha = 0.2f // 半透明
                                )
                            }
                        }
                    }

                    // 按钮区域
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // 取消按钮
                        OutlinedButton(
                            onClick = {
                                // 只关闭对话框和清理临时资源，保留调整状态
                                showSingleWellZoomDialog = false
                                singleWellCroppedBitmap = null
                                // 不重置singleWellAdjustedRect，以保持用户的调整状态
                            }
                        ) {
                            Text(singleWellZoomCancelMsg)
                        }

                        // 确认按钮
                        Button(
                            onClick = {
                                // 应用调整后的矩形
                                selectedWellId?.let { wellId ->
                                    singleWellAdjustedRect?.let { adjustedRect ->
                                        // 应用更改到视图模型
                                        viewModel.adjustSingleWellSize(wellId, adjustedRect)
                                        toastManager.showToast(wellAdjustedSuccessMsg, ToastType.SUCCESS)
                                    }
                                }

                                // 关闭对话框和清理临时资源，但保留调整状态
                                showSingleWellZoomDialog = false
                                singleWellCroppedBitmap = null
                                // 不重置singleWellAdjustedRect，以保持用户的调整状态
                            }
                        ) {
                            Text(singleWellZoomConfirmMsg)
                        }
                    }
                }
            }
        }
    }

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
                        onClick = { toggleDetectionMode() },
                        enabled = detectionState !is DetectionViewModel.DetectionState.Loading
                    ) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = if (isEnhancedDetection) switchDetectionModeDescription else stringResource(R.string.enhanced_detection),
                            tint = if (isEnhancedDetection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // 添加单个孔位缩放按钮，仅在标准检测模式下可用
                    if (!isEnhancedDetection && detectionState is DetectionViewModel.DetectionState.Success) {
                        IconButton(
                            onClick = { handleSingleWellZoom() },
                            enabled = detectionState !is DetectionViewModel.DetectionState.Loading
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = zoomWellMsg,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                                            selectedWellIndex?.let { index ->
                                                                if (index >= 0 && index < detections.size) {
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