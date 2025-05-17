@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.screens.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.ResultViewModel
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import com.muc.fluocolorquant.R

/**
 * 结果展示页面
 * 根据项目类型显示96孔板热力图或单孔位浓度
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ResultScreen(
    navController: NavController,
    runId: String? = null,
    projectId: String? = null,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val resultState by viewModel.resultState.collectAsState()
    val concentrationUnit by viewModel.concentrationUnit.collectAsState()
    val minConcentration by viewModel.minConcentration.collectAsState()
    val maxConcentration by viewModel.maxConcentration.collectAsState()
    
    // 页面状态管理
    val pagerState = rememberPagerState(initialPage = 0) { 3 } // 三个结果页面
    
    // 是否显示导出面板
    var showExportPanel by remember { mutableStateOf(false) }
    
    // 获取当前视图以实现截图功能
    val view = LocalView.current
    
    // 加载结果数据
    LaunchedEffect(runId, projectId) {
        when {
            !runId.isNullOrEmpty() -> viewModel.loadResultsByRunId(runId)
            !projectId.isNullOrEmpty() -> viewModel.loadResultsByProjectId(projectId)
            else -> {
                // 没有runId或projectId时的处理
                // 显示相应的提示或错误信息
                viewModel.loadDefaultOrMostRecentResults()
            }
        }
    }
    
    // 截图函数
    val captureScreenshot = {
        try {
            // 获取当前View树的根视图
            val rootView = view.rootView
            
            // 创建与视图大小相同的Bitmap
            val bitmap = Bitmap.createBitmap(
                rootView.width, 
                rootView.height, 
                Bitmap.Config.ARGB_8888
            )
            
            // 将视图绘制到Canvas上
            val canvas = android.graphics.Canvas(bitmap)
            rootView.draw(canvas)
            
            // 裁剪掉状态栏和导航栏的部分（可选）
            val statusBarHeight = getStatusBarHeight(view.context)
            val contentBitmap = Bitmap.createBitmap(
                bitmap,
                0, 
                statusBarHeight,
                bitmap.width,
                bitmap.height - statusBarHeight
            )
            
            contentBitmap
        } catch (e: Exception) {
            android.util.Log.e("ResultScreen", "截图失败", e)
            null
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检测结果") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 改为导出按钮
                    IconButton(onClick = { showExportPanel = true }) {
                        // 使用本地图标而不是Icons.Default.FileDownload
                        Icon(
                            painter = painterResource(id = R.drawable.export),
                            contentDescription = "导出",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        // 添加内容引用，用于截图
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            when (val state = resultState) {
                is ResultViewModel.ResultState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("加载结果数据...")
                    }
                }
                
                is ResultViewModel.ResultState.Success -> {
                    val project = state.project
                    val wellResults = state.wellResults
                    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // 项目信息
                        ProjectInfoCard(project)
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 按照项目类型显示不同的结果
                        if (project.recognitionType == "AUTO") {
                            // AUTO模式 - 添加分页显示
                            
                            // 页面指示器
                            PageIndicator(
                                pagerState = pagerState,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(bottom = 16.dp)
                            )
                            
                            // 水平分页器
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxWidth()
                            ) { page ->
                                when (page) {
                                    0 -> {
                                        // 第一种显示：圆形热力图
                                        PlateHeatmapCard(
                                            wellResults = wellResults,
                                            concentrationUnit = concentrationUnit,
                                            minConcentration = minConcentration,
                                            maxConcentration = maxConcentration,
                                            viewModel = viewModel
                                        )
                                    }
                                    1 -> {
                                        // 第二种显示：方形热力图带数值
                                        SquareHeatmapCard(
                                            wellResults = wellResults,
                                            concentrationUnit = concentrationUnit,
                                            minConcentration = minConcentration,
                                            maxConcentration = maxConcentration,
                                            viewModel = viewModel
                                        )
                                    }
                                    2 -> {
                                        // 第三种显示：折线图
                                        ConcentrationChartCard(
                                            wellResults = wellResults,
                                            concentrationUnit = concentrationUnit,
                                            viewModel = viewModel
                                        )
                                    }
                                }
                            }
                        } else {
                            // 手动模式下的单孔位结果
                            SingleWellResultCard(
                                wellResults = wellResults,
                                concentrationUnit = concentrationUnit,
                                viewModel = viewModel
                            )
                        }
                        
                        // 添加返回首页按钮
                        Spacer(modifier = Modifier.height(24.dp))
                        androidx.compose.material3.Button(
                            onClick = {
                                // 导航到首页，清除返回栈
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Home.route) {
                                        inclusive = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "返回首页",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        // 为底部留出空间
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
                
                is ResultViewModel.ResultState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // 显示导出面板
    if (showExportPanel) {
        val successState = resultState as? ResultViewModel.ResultState.Success
        if (successState != null) {
            ExportBottomPanel(
                isVisible = true,
                onDismiss = { showExportPanel = false },
                project = successState.project,
                wellResults = successState.wellResults,
                detectionRun = successState.detectionRun,
                captureScreenshot = captureScreenshot
            )
        }
    }
}

/**
 * 项目信息卡片
 */
@Composable
fun ProjectInfoCard(project: Project) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "检测模式: ${
                    when (project.detectionMode) {
                        "FLUORESCENCE" -> "荧光检测"
                        "COLORIMETRIC" -> "比色检测"
                        else -> project.detectionMode
                    }
                }"
            )
            
            Text(
                text = "识别类型: ${
                    when (project.recognitionType) {
                        "AUTO" -> "自动识别"
                        "MANUAL" -> "手动裁剪"
                        else -> project.recognitionType
                    }
                }"
            )
            
            Text(
                text = "创建时间: ${formatDate(project.createTime)}"
            )
            
            // 显示最大浓度（如果有）
            project.maxConcentration?.let { maxConc ->
                Text(
                    text = "最大浓度: $maxConc ng/ml"
                )
            }
        }
    }
}

/**
 * 96孔板热力图卡片
 */
@Composable
fun PlateHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    minConcentration: Double,
    maxConcentration: Double,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题和说明
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "浓度热力图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "单位: $concentrationUnit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 热力图图例 - 改进设计
            HeatmapLegend(
                minValue = minConcentration,
                maxValue = maxConcentration,
                unit = concentrationUnit
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 96孔板容器 - 添加边框和阴影
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f) // 适当调整宽高比，使其可以容纳所有孔位
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                // 创建孔位位置到结果的映射
                val wellMap = remember(wellResults) {
                    wellResults.associateBy { it.wellIndex }
                }
                
                // 使用Box+Column布局确保所有内容都能显示
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 表头
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 空白格子（左上角）
                            Box(
                                modifier = Modifier.size(20.dp)
                            )
                            
                            // 列标题 (1-12)
                            for (col in 1..12) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = col.toString(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        
                        // 孔位行（A-H）
                        for (row in 0 until 8) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 行标签 (A-H)
                                Box(
                                    modifier = Modifier
                                        .size(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = ('A' + row).toString(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                
                                // 96孔板的每个孔位
                                for (col in 0 until 12) {
                                    val index = row * 12 + col
                                    val wellResult = wellMap[index]
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        PlateWell(
                                            wellResult = wellResult,
                                            minConcentration = minConcentration,
                                            maxConcentration = maxConcentration,
                                            project = project,
                                            viewModel = viewModel
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个孔位组件（用于热力图）
 */
@Composable
fun PlateWell(
    wellResult: WellResult?,
    minConcentration: Double,
    maxConcentration: Double,
    project: Project?,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val percentValue = wellResult?.predictedConcentration
    val actualConcentration = if (project != null && percentValue != null) {
        viewModel.calculateActualConcentration(percentValue, project.maxConcentration)
    } else {
        null
    }
    
    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(
            value = actualConcentration,
            minValue = minConcentration,
            maxValue = maxConcentration
        )
    } else {
        Color(224, 224, 224, 180) // 半透明浅灰色
    }
    
    // 悬停工具提示状态
    var showTooltip by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(CircleShape)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.3f), CircleShape)
            .clickable { showTooltip = !showTooltip },
        contentAlignment = Alignment.Center
    ) {
        // 对于小的孔位，我们不直接显示具体数值，但可以显示气泡提示
        if (showTooltip && actualConcentration != null) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { showTooltip = false }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "孔位 ${('A' + wellResult!!.wellIndex / 12).toChar()}${wellResult.wellIndex % 12 + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format("%.2f%%", percentValue),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = String.format("%.2f ng/ml", actualConcentration),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 方形热力图卡片 - 使用紧密连接的小正方形，显示具体浓度值
 */
@Composable
fun SquareHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    minConcentration: Double,
    maxConcentration: Double,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题和说明
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "浓度数值图",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "单位: $concentrationUnit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 热力图图例
            HeatmapLegend(
                minValue = minConcentration,
                maxValue = maxConcentration,
                unit = concentrationUnit
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 创建孔位位置到结果的映射
            val wellMap = remember(wellResults) {
                wellResults.associateBy { it.wellIndex }
            }
            
            // 方形热力图容器
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f) // 调整宽高比以容纳所有孔位
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 表头
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 空白格子（左上角）
                            Box(
                                modifier = Modifier.size(20.dp)
                            )
                            
                            // 列标题 (1-12)
                            for (col in 1..12) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = col.toString(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        
                        // 孔位行（A-H）
                        for (row in 0 until 8) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 行标签 (A-H)
                                Box(
                                    modifier = Modifier
                                        .size(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = ('A' + row).toString(),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                
                                // 96孔板的每个孔位
                                for (col in 0 until 12) {
                                    val index = row * 12 + col
                                    val wellResult = wellMap[index]
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SquareWell(
                                            wellResult = wellResult,
                                            minConcentration = minConcentration,
                                            maxConcentration = maxConcentration,
                                            project = project,
                                            viewModel = viewModel
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个方形孔位组件 - 显示具体数值
 */
@Composable
fun SquareWell(
    wellResult: WellResult?,
    minConcentration: Double,
    maxConcentration: Double,
    project: Project?,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val percentValue = wellResult?.predictedConcentration
    val actualConcentration = if (project != null && percentValue != null) {
        viewModel.calculateActualConcentration(percentValue, project.maxConcentration)
    } else {
        null
    }
    
    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(
            value = actualConcentration,
            minValue = minConcentration,
            maxValue = maxConcentration
        )
    } else {
        Color(224, 224, 224, 180) // 半透明浅灰色
    }
    
    // 计算显示的文本和文本颜色
    val displayText = if (actualConcentration != null) {
        String.format("%.1f", actualConcentration)
    } else ""
    
    // 根据背景颜色计算对比色（浅色背景用深色文字，深色背景用浅色文字）
    val textColor = if (calculateLuminance(wellColor) > 0.5f) Color.Black else Color.White
    
    // 方形孔位
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        // 显示浓度数值
        Text(
            text = displayText,
            fontSize = 6.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * 热力图图例
 */
@Composable
fun HeatmapLegend(
    minValue: Double,
    maxValue: Double,
    unit: String
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 颜色渐变条 - 更美观的设计
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            // 使用20个颜色区块实现平滑渐变
            Row(modifier = Modifier.fillMaxSize()) {
                val colors = HeatmapColorUtil.getLegendColors(20)
                colors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(color)
                    )
                }
            }
            
            // 添加刻度线
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight(0.5f)
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 刻度值和单位标签
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 5个刻度点
            for (i in 0..4) {
                val value = minValue + (maxValue - minValue) * i / 4
                Text(
                    text = String.format("%.1f", value),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

/**
 * 格式化日期
 */
private fun formatDate(date: Date): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(date)
}

/**
 * 页面指示器
 * 带有动画效果的切换指示器，当前页面显示为黑色长条，其他页面显示为灰色圆点
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageIndicator(
    pagerState: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pagerState.pageCount) { index ->
            val isSelected = pagerState.currentPage == index
            
            // 使用动画效果平滑过渡宽度和颜色
            val width by animateDpAsState(
                targetValue = if (isSelected) 24.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                ), 
                label = "width"
            )
            
            val color = if (isSelected) 
                MaterialTheme.colorScheme.onSurface 
            else 
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = width, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .clickable {
                        // 点击切换到对应页面
                        // 通过协程启动，避免直接修改状态
                        MainScope().launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
            )
        }
    }
}

/**
 * 浓度折线图卡片 - 改进版
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConcentrationChartCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    viewModel: ResultViewModel = hiltViewModel()
) {
    // 获取项目信息
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    
    // 过滤并排序有效的浓度结果 (按孔位顺序)
    val validResults = wellResults
        .filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }
    
    // 将validResults按照wellIndex排序，确保显示顺序符合A1-H12
    val sortedResults = remember(validResults) {
        validResults.sortedBy { it.wellIndex }
    }
    
    // 找出最大浓度用于归一化
    val maxConcentration = project?.maxConcentration ?: 100.0
    
    // 记录选中的数据点
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "浓度折线图",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "孔位浓度趋势分析 (单位: $concentrationUnit)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (sortedResults.isNotEmpty()) {
                // 图表高度
                val chartHeight = 250.dp
                
                // 显示当前选中的数据点详情
                selectedPointIndex?.let { index ->
                    if (index < sortedResults.size) {
                        val result = sortedResults[index]
                        val rowChar = ('A' + result.wellIndex / 12).toChar()
                        val colNumber = (result.wellIndex % 12) + 1
                        val percentValue = result.predictedConcentration ?: 0.0
                        val actualConcentration = viewModel.calculateActualConcentration(
                            percentValue, project?.maxConcentration
                        ) ?: 0.0
                        
                        // 选中的数据点信息卡
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // 孔位信息
                                Column {
                                    Text(
                                        text = "孔位 $rowChar$colNumber",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = String.format("%.2f%% (%.2f $concentrationUnit)", 
                                            percentValue, actualConcentration),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                
                                // 关闭按钮
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        .clickable { selectedPointIndex = null },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("×", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                
                // 折线图容器
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .padding(
                            start = 40.dp, // 为Y轴刻度留出空间
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 24.dp // 为X轴标签留出空间
                        )
                ) {
                    // Y轴标签 (浓度刻度)
                    Column(
                        modifier = Modifier
                            .height(chartHeight - 36.dp) // 减去上下padding
                            .align(Alignment.CenterStart)
                            .offset(x = (-38).dp), // 将Y轴标签向左偏移
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 显示5个标签 (100%, 75%, 50%, 25%, 0%)
                        for (i in 5 downTo 0) {
                            if (i % 1 == 0) { // 仅显示整数百分比点
                                val percent = i * 20
                                val actualValue = (percent / 100.0) * maxConcentration
                                
                                Text(
                                    text = String.format("%.1f", actualValue),
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(36.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    // 绘制折线图
                    if (sortedResults.size > 1) {
                        val scrollState = rememberScrollState()
                        
                        // 可滚动的图表区域
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(scrollState)
                        ) {
                            // 计算图表宽度 - 每个点至少40dp宽
                            val dataPointWidth = 40.dp
                            val chartWidth = maxOf(
                                dataPointWidth * sortedResults.size,
                                350.dp // 最小宽度
                            )
                            
                            // 获取主色调 - 在Canvas外获取，避免在绘制中调用
                            val primaryColor = MaterialTheme.colorScheme.primary.toArgb()
                            val primaryColorHighlighted = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f).toArgb()
                            val backgroundColor = Color.White
                            
                            // 使用remember存储数据点位置
                            val dataPoints = remember { mutableStateListOf<Pair<Offset, Int>>() }
                            val density = LocalDensity.current
                            
                            // 使用remember保存选中点的位置，避免在detectTapGestures中直接修改selectedPointIndex
                            var tapSelectedIndex by remember { mutableStateOf<Int?>(null) }
                            
                            // 在Canvas外处理点击事件的效果
                            LaunchedEffect(tapSelectedIndex) {
                                selectedPointIndex = tapSelectedIndex
                            }
                            
                            Canvas(
                                modifier = Modifier
                                    .width(chartWidth)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        detectTapGestures { tapPosition ->
                                            // 找到最近的数据点 - 在lambda中不调用Composable函数
                                            val closestPoint = dataPoints.minByOrNull { (position, _): Pair<Offset, Int> ->
                                                val dx = position.x - tapPosition.x
                                                val dy = position.y - tapPosition.y
                                                dx * dx + dy * dy // 平方距离
                                            }
                                            
                                            // 如果点击位置在数据点附近，更新保存的选中点索引
                                            closestPoint?.let { (position, index) ->
                                                val dx = position.x - tapPosition.x
                                                val dy = position.y - tapPosition.y
                                                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                                
                                                // 如果点击位置在数据点25像素范围内
                                                if (distance < 25) {
                                                    // 更新临时状态，让LaunchedEffect处理UI更新
                                                    tapSelectedIndex = index
                                                }
                                            }
                                        }
                                    }
                            ) {
                                val height = size.height
                                val width = size.width
                                val pointCount = sortedResults.size // 使用排序后的结果
                                
                                // 清空并重新记录数据点位置
                                dataPoints.clear()
                                
                                // 绘制网格线
                                val gridColor = Color.Gray.copy(alpha = 0.15f)
                                val gridStrokeWidth = 1f
                                
                                // 横向网格线（浓度刻度）
                                for (i in 0..5) {
                                    val y = height - (height * i / 5)
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(0f, y),
                                        end = Offset(width, y),
                                        strokeWidth = gridStrokeWidth
                                    )
                                }
                                
                                // 计算点之间的间距
                                val pointDistance = width / (pointCount - 0.5f)
                                
                                // 竖向网格线（每隔1个数据点）
                                for (i in 0 until pointCount) {
                                    if (i % 2 == 0) { // 每隔1个点显示网格线
                                        val x = i * pointDistance
                                        drawLine(
                                            color = gridColor,
                                            start = Offset(x, 0f),
                                            end = Offset(x, height),
                                            strokeWidth = gridStrokeWidth
                                        )
                                    }
                                }
                                
                                // 绘制折线和数据点
                                val path = Path()
                                var firstPoint = true
                                
                                // 绘制所有数据点和线条 - 使用排序后的结果
                                sortedResults.forEachIndexed { i, result ->
                                    val x = i * pointDistance
                                    
                                    // 计算浓度值和归一化Y坐标
                                    val actualConcentration = if (project != null && result.predictedConcentration != null) {
                                        viewModel.calculateActualConcentration(
                                            result.predictedConcentration,
                                            project.maxConcentration
                                        ) ?: 0.0
                                    } else 0.0
                                    
                                    val normalizedY = (actualConcentration / maxConcentration).toFloat()
                                    val y = height - (normalizedY * height)
                                    
                                    // 记录数据点位置
                                    dataPoints.add(Offset(x, y) to i)
                                    
                                    // 画路径
                                    if (firstPoint) {
                                        path.moveTo(x, y)
                                        firstPoint = false
                                    } else {
                                        path.lineTo(x, y)
                                    }
                                    
                                    // 绘制数据点
                                    val pointRadius = if (i == selectedPointIndex) 8f else 5f
                                    val pointColor = if (i == selectedPointIndex) {
                                        Color(primaryColorHighlighted)
                                    } else {
                                        Color(primaryColor)
                                    }
                                    
                                    // 绘制数据点外圈（选中时）
                                    if (i == selectedPointIndex) {
                                        drawCircle(
                                            color = backgroundColor,
                                            radius = pointRadius + 2f,
                                            center = Offset(x, y)
                                        )
                                    }
                                    
                                    // 绘制数据点
                                    drawCircle(
                                        color = pointColor,
                                        radius = pointRadius,
                                        center = Offset(x, y)
                                    )
                                }
                                
                                // 绘制路径（线条）
                                drawPath(
                                    path = path,
                                    color = Color(primaryColor),
                                    style = Stroke(
                                        width = 2.5f,
                                        pathEffect = androidx.compose.ui.graphics.PathEffect.cornerPathEffect(5f)
                                    )
                                )
                            }
                            
                            // X轴标签 - 在Canvas下方
                            Box(
                                modifier = Modifier
                                    .width(chartWidth)
                                    .height(24.dp)
                                    .align(Alignment.BottomCenter)
                            ) {
                                // 使用LocalDensity获取像素转换
                                val density = LocalDensity.current
                                
                                // 修改：使用精确定位的方式显示每个点的标签
                                // 首先确保按照wellIndex顺序排序validResults
                                val sortedResults = remember(validResults) {
                                    validResults.sortedBy { it.wellIndex }
                                }
                                
                                // 绘制X轴标签
                                // 关键修改：提前获取颜色，不在Canvas内部调用MaterialTheme
                                val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f).toArgb()
                                
                                Canvas(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    val textPaint = android.text.TextPaint().apply { 
                                        textSize = with(density) { 9.sp.toPx() }
                                        color = textColor  // 使用外部获取的颜色
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        isFakeBoldText = false
                                        isAntiAlias = true
                                    }
                                    
                                    // 计算点之间的间距
                                    val pointDistance = size.width / (sortedResults.size - 0.5f)
                                    
                                    // 为每个点绘制标签
                                    sortedResults.forEachIndexed { i, result ->
                                        val x = i * pointDistance
                                        val rowChar = ('A' + result.wellIndex / 12).toChar()
                                        val colNumber = (result.wellIndex % 12) + 1
                                        val label = "$rowChar$colNumber"
                                        
                                        // 使用原生Canvas绘制文本，确保精确定位
                                        // 为第一个标签增加一点偏移，确保A1完全可见
                                        val xPos = if (i == 0) x + 14f else x
                                        
                                        this.drawContext.canvas.nativeCanvas.drawText(
                                            label,
                                            xPos,  // 调整后的X坐标
                                            size.height - 4.dp.toPx(),  // 距离底部适当距离
                                            textPaint
                                        )
                                    }
                                }
                            }
                        }
                    } else if (sortedResults.size == 1) {
                        // 只有一个数据点的情况
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // 绘制单个数据点
                            val result = sortedResults[0]
                            val actualConcentration = if (project != null && result.predictedConcentration != null) {
                                viewModel.calculateActualConcentration(
                                    result.predictedConcentration,
                                    project.maxConcentration
                                ) ?: 0.0
                            } else 0.0
                            
                            val rowChar = ('A' + result.wellIndex / 12).toChar()
                            val colNumber = (result.wellIndex % 12) + 1
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 数据点
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(Color.White)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // 标签
                                Text(
                                    text = "$rowChar$colNumber",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Text(
                                    text = String.format("%.2f %s", actualConcentration, concentrationUnit),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                
                // 提示信息
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "点击数据点查看详细信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // 滚动提示（仅当数据点较多时显示）
                if (sortedResults.size > 10) {
                    Text(
                        text = "左右滑动查看更多数据",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                // 没有有效数据的显示
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "没有有效的浓度数据",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 计算颜色亮度
 */
private fun calculateLuminance(color: Color): Float {
    val red = color.red
    val green = color.green
    val blue = color.blue
    return (red * 0.299f + green * 0.587f + blue * 0.114f) / 255f
}

/**
 * 单孔位结果卡片（用于手动裁剪模式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleWellResultCard(
    wellResults: List<WellResult>,
    concentrationUnit: String,
    viewModel: ResultViewModel
) {
    // 获取第一个有效的孔位结果
    val singleWell = wellResults.firstOrNull { it.predictedConcentration != null }
    
    // 获取成功状态下的项目
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 6.dp
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题改为项目名称
            Text(
                text = project?.name ?: "手动裁剪结果",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 显示检测模式
            project?.detectionMode?.let { mode ->
                val detectionModeText = when (mode) {
                    "FLUORESCENCE" -> "荧光检测"
                    "COLORIMETRIC" -> "比色检测"
                    else -> mode
                }
                Text(
                    text = detectionModeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (singleWell != null) {
                // 显示裁剪的孔位图像
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    // 获取图像来源（可能是File或URI）
                    val imageSource = viewModel.getWellImageFile(singleWell)
                    
                    if (imageSource != null) {
                        // 显示孔位图像 - 现在可以处理File或URI
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(imageSource)
                                .crossfade(true)
                                .build(),
                            contentDescription = "孔位图像",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.LightGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("无图像")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 浓度值 - 分为百分比和实际浓度显示
                singleWell.predictedConcentration?.let { percentValue ->
                    // 原始百分比值
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "浓度百分比:",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = String.format("%.2f%%", percentValue),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 实际浓度值 = 百分比 × 最大浓度
                    val actualConcentration = viewModel.calculateActualConcentration(
                        percentValue = percentValue,
                        maxConcentration = project?.maxConcentration
                    )
                    
                    if (actualConcentration != null) {
                        // 显示浓度单位和最大浓度信息
                        Text(
                            text = "实际浓度 (最大浓度: ${project?.maxConcentration ?: 100.0} $concentrationUnit)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 显示计算后的浓度值
                        Text(
                            text = String.format("%.2f %s", actualConcentration, concentrationUnit),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } ?: Text(
                    text = "未能测量浓度",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEEEEEE)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "未找到\n有效的检测结果",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 获取状态栏高度
 */
private fun getStatusBarHeight(context: android.content.Context): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) {
        context.resources.getDimensionPixelSize(resourceId)
    } else {
        0
    }
} 