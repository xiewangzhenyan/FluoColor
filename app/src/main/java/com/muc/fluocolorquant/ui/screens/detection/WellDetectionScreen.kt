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
import com.muc.fluocolorquant.utils.Screen
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
    val coroutineScope = rememberCoroutineScope()
    
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
                        // 导航到曲线拟合屏幕
                        val encodedImageUri = Uri.encode(imageUri ?: "") 
                        navController.navigate("${Screen.CurveFitting.route}?runId=$newRunId&imageUri=$encodedImageUri") {
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
    
    // 启动检测
    LaunchedEffect(key1 = imageUri) {
        if (imageUri != null) {
            viewModel.detectWells(imageUri)
        } else {
            toastManager.showToast(imageUriEmptyMsg, ToastType.ERROR)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
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
                        
                        Text(
                            text = stringResource(R.string.wells_detected, detections.size),
                            style = MaterialTheme.typography.titleMedium
                        )
                        
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
                                
                                // 绘制检测框
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
                                                    for (i in detections.indices) {
                                                        val rect = detections[i].rect
                                                        if (imageX >= rect.left && imageX <= rect.right &&
                                                            imageY >= rect.top && imageY <= rect.bottom) {
                                                            // 选中孔位
                                                            viewModel.selectWell(i)
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
                                                                // 选中孔位，准备拖动
                                                                viewModel.selectWell(i)
                                                                break
                                                            }
                                                        }
                                                    }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    
                                                    // 如果有选中的孔位，则移动它
                                                    selectedWellIndex?.let { index ->
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
                                                        viewModel.moveSelectedWell(dx, dy, imageBounds)
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
                                    
                                    // 绘制所有检测框
                                    for (i in detections.indices) {
                                        val well = detections[i]
                                        
                                        // 确定颜色: 选中的为绿色，其他为红色
                                        val strokeColor = if (i == selectedWellIndex) {
                                            Color.Green
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
                                        
                                        // 绘制孔位ID
                                        drawCircle(
                                            color = strokeColor.copy(alpha = 0.7f),
                                            radius = 12.dp.toPx(),
                                            center = Offset(
                                                x = leftPadding + (well.rect.left + 20) * scale,
                                                y = topPadding + (well.rect.top + 20) * scale
                                            )
                                        )
                                        
                                        // 孔位编号文本（简化处理，实际项目中可能需要使用TextDrawer）
                                        val wellNumberText = "${i + 1}"
                                        val textColor = Color.White.toArgb()
                                        
                                        // 在实际应用中会使用DrawScope的drawIntoCanvas来绘制文本
                                        // 这里简化处理，仅显示框
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = stringResource(R.string.click_and_drag_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
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
                                    if (imageUri != null) {
                                        viewModel.detectWells(imageUri)
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