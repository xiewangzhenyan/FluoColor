package com.muc.fluocolorquant.ui.screens.curvefitting

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 曲线拟合/浓度预测界面
 * 自动处理孔位裁剪和浓度预测，并展示裁剪后的孔位图像网格
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurveFittingScreen(
    navController: NavController,
    runId: String? = null,
    imageUri: String? = null, // Accept imageUri
    viewModel: ConcentrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val concentrationState by viewModel.concentrationState.collectAsState()
    val currentRunId by viewModel.currentRunId.collectAsState()
    val isPredicting by viewModel.isPredicting.collectAsState()
    val predictionProgress by viewModel.predictionProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() } // 可以保留用于其他提示
    
    // 使用传入的runId或ViewModel中的currentRunId
    val effectiveRunId = runId ?: currentRunId
    
    // Load the original bitmap when the screen launches using the passed imageUri
    LaunchedEffect(key1 = imageUri) {
        if (!imageUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(imageUri)
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    inputStream?.use { BitmapFactory.decodeStream(it) } 
                }
                bitmap?.let { 
                    viewModel.setOriginalBitmap(it)
                    android.util.Log.d("CurveFittingScreen", "Original bitmap loaded successfully.")
                 } ?: run {
                    android.util.Log.e("CurveFittingScreen", "Failed to load bitmap from URI: $imageUri")
                    // Optionally show an error message via Snackbar or Toast
                }
            } catch (e: Exception) {
                android.util.Log.e("CurveFittingScreen", "Error loading bitmap from URI: $imageUri", e)
                // Optionally show an error message
            }
        } else {
            android.util.Log.w("CurveFittingScreen", "imageUri is null or empty, cannot load bitmap.")
        }
    }
    
    // 启动处理 (Only if bitmap is loaded and runId is valid)
    LaunchedEffect(key1 = effectiveRunId, key2 = viewModel.originalBitmap.value) {
        val currentBitmap = viewModel.originalBitmap.value
        if (!effectiveRunId.isNullOrEmpty() && currentBitmap != null) {
            android.util.Log.d("CurveFittingScreen", "Processing LaunchedEffect triggered for runId: $effectiveRunId. State: ${concentrationState::class.simpleName}")
            // 只有在Idle状态才触发
            if (concentrationState is ConcentrationViewModel.ConcentrationState.Idle) { 
                // 使用分阶段处理代替一次性处理
                viewModel.processInStages(effectiveRunId)
            }
        } else {
            android.util.Log.w("CurveFittingScreen", "Processing LaunchedEffect: runId ($effectiveRunId) or bitmap ($currentBitmap) is invalid.")
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("孔位处理与预览") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) } // 保留SnackbarHost
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = concentrationState) {
                is ConcentrationViewModel.ConcentrationState.Idle -> {
                    // If bitmap is still null or runId is missing, show appropriate message
                    if (viewModel.originalBitmap.value == null && !imageUri.isNullOrEmpty()) {
                        Text("正在加载原始图像...", textAlign = TextAlign.Center)
                    } else if (effectiveRunId.isNullOrEmpty()) {
                        Text("缺少有效的运行ID，无法处理。", textAlign = TextAlign.Center)
                    } else {
                        Text("准备处理孔位...", textAlign = TextAlign.Center)
                    }
                }
                is ConcentrationViewModel.ConcentrationState.Loading -> {
                    // 加载状态
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在处理孔位图像...", textAlign = TextAlign.Center)
                }
                is ConcentrationViewModel.ConcentrationState.ImagesCropped,
                is ConcentrationViewModel.ConcentrationState.Success -> {
                    // 图像裁剪完成或浓度预测完成，显示图像网格
                    val wellResults = when (state) {
                        is ConcentrationViewModel.ConcentrationState.ImagesCropped -> state.wellResults
                        is ConcentrationViewModel.ConcentrationState.Success -> state.wellResults
                        else -> emptyList() // 不会执行到这里
                    }
                    
                    // 显示是否为预测状态
                    when {
                        state is ConcentrationViewModel.ConcentrationState.Success -> {
                            Text("孔位处理和浓度预测完成，共 ${wellResults.size} 个孔位", 
                                style = MaterialTheme.typography.titleMedium)
                        }
                        isPredicting -> {
                            Text("孔位图像处理完成，共 ${wellResults.size} 个孔位。正在后台预测浓度...", 
                                style = MaterialTheme.typography.titleMedium)
                        }
                        else -> {
                            Text("孔位图像处理完成，共 ${wellResults.size} 个孔位", 
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 显示图像网格
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8), // 8列
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(wellResults, key = { it.resultId }) { wellResult ->
                            // croppedImageIdentifier 存储的是文件路径
                            val imageFile = wellResult.croppedImageIdentifier?.let { File(it) }
                            
                            Box(
                                modifier = Modifier.aspectRatio(1f), // 保持正方形容器
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(imageFile) // 直接加载文件
                                        .error(android.R.drawable.ic_menu_gallery) // 加载错误时显示占位符
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "孔位 ${wellResult.wellIndex + 1}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(2.dp) // 给圆形加一点内边距
                                        .clip(androidx.compose.foundation.shape.CircleShape), // 圆形裁剪
                                    contentScale = ContentScale.Fit // 使用Fit而不是Crop，保持纵横比
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 预测完成时显示详细结果按钮
                    if (state is ConcentrationViewModel.ConcentrationState.Success) {
                        Button(
                            onClick = {
                                navController.navigate("${Screen.Result.route}?runId=$effectiveRunId")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text("查看详细结果")
                        }
                    }
                    
                    // 显示预测进度条（如果正在预测）
                    if (isPredicting) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { predictionProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "浓度预测进度: $predictionProgress%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                is ConcentrationViewModel.ConcentrationState.Error -> {
                    // 错误状态
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "错误",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "处理失败: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // Add a check for bitmap existence before allowing retry
                        val canRetry = !effectiveRunId.isNullOrEmpty() && viewModel.originalBitmap.value != null
                        Button(
                            onClick = {
                                // 使用分阶段处理重试
                                viewModel.processInStages(effectiveRunId)
                            },
                            enabled = canRetry
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
} 