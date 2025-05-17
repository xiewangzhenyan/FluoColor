package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.ui.viewmodels.DetectionViewModel
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.launch
import android.net.Uri

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
    fun handleContinue() {
        // 获取当前检测状态
        val currentState = viewModel.detectionState.value
        
        if (currentState is DetectionViewModel.DetectionState.Success) {
            // 检查是否有检测结果
            val detections = currentState.detections
            if (detections.isEmpty()) {
                toastManager.showToast("未检测到孔位，请重试", ToastType.ERROR)
                return
            }
            
            // 检查项目ID是否为空
            if (projectId.isNullOrEmpty()) {
                toastManager.showToast("项目ID为空，无法保存检测结果", ToastType.ERROR)
                return
            }
            
            // 显示保存进度Toast
            toastManager.showToast("正在保存检测结果...", ToastType.INFO)
            
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
                        toastManager.showToast("保存检测结果失败", ToastType.ERROR)
                    }
                } catch (e: Exception) {
                    toastManager.showToast("保存检测结果失败: ${e.message}", ToastType.ERROR)
                }
            }
        }
    }
    
    // 启动检测
    LaunchedEffect(key1 = imageUri) {
        if (imageUri != null) {
            viewModel.detectWells(imageUri)
        } else {
            toastManager.showToast("图像URI为空，无法进行检测", ToastType.ERROR)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("孔阵检测") },
                navigationIcon = {
                    IconButton(onClick = { handleBackPress() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 添加信息按钮，显示使用帮助
                    IconButton(onClick = { showInfoDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "使用帮助"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("使用帮助") },
                text = { 
                    Text(
                        "孔位检测使用说明：\n\n" +
                        "1. 页面加载时会自动检测孔位，显示红色方框\n" +
                        "2. 点击孔位可以选中它（变为绿色）\n" +
                        "3. 选中后，拖动可以调整孔位的位置\n" +
                        "4. 调整完成后，点击「继续」进入下一步"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("确定")
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
                            text = "准备进行孔阵检测...",
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
                                text = "正在检测孔阵，请稍候...",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    
                    is DetectionViewModel.DetectionState.Success -> {
                        val detections = (detectionState as DetectionViewModel.DetectionState.Success).detections
                        
                        Text(
                            text = "检测到 ${detections.size} 个孔位",
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
                                    contentDescription = "孔阵图像",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                                
                                // 绘制检测框
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { offset ->
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
                                                    
                                                    // 将点击坐标转换为原始图像坐标
                                                    val imageX = (offset.x - leftPadding) / scale
                                                    val imageY = (offset.y - topPadding) / scale
                                                    
                                                    // 检查点击是否在某个孔位内
                                                    for (i in detections.indices) {
                                                        val rect = detections[i].rect
                                                        if (imageX >= rect.left && imageX <= rect.right &&
                                                            imageY >= rect.top && imageY <= rect.bottom) {
                                                            // 选中孔位
                                                            viewModel.selectWell(i)
                                                            break
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
                                                        
                                                        // 更新孔位位置
                                                        viewModel.moveSelectedWell(dx, dy)
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
                                text = "点击孔位选中并拖动进行微调",
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
                                contentDescription = "错误",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
            Text(
                                text = "检测出错: $error",
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
                                Text("重试")
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
                        Text("返回")
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // 继续按钮
                    Button(
                        onClick = { handleContinue() },
                        modifier = Modifier.weight(1f),
                        enabled = detectionState is DetectionViewModel.DetectionState.Success
                    ) {
                        Text("继续")
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