@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.screens.result

import android.graphics.Bitmap
// import android.graphics.BitmapFactory // 不再直接使用
// import android.graphics.Color as AndroidColor // 不再直接使用
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
// import androidx.compose.foundation.interaction.MutableInteractionSource // 不再直接使用
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
// import androidx.compose.foundation.layout.BoxScope // 不再直接使用
import androidx.compose.foundation.layout.Column
// import androidx.compose.foundation.layout.PaddingValues // 不再直接使用
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
import androidx.compose.material.icons.filled.ArrowBack // 使用 AutoMirrored 版本
import androidx.compose.material.icons.automirrored.filled.ArrowBack // 新增：AutoMirrored 版本
// import androidx.compose.material.icons.filled.FileDownload // 使用 drawable 替代
import androidx.compose.material.icons.filled.Info
// import androidx.compose.material.icons.filled.MoreVert // 不再直接使用
// import androidx.compose.material.icons.filled.Share // 不再直接使用
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
// import androidx.compose.material3.Divider // 不再直接使用
import androidx.compose.material3.ExperimentalMaterial3Api
// import androidx.compose.material3.HorizontalDivider // 不再直接使用
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
// import androidx.compose.material3.LinearProgressIndicator // 不再直接使用
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
import androidx.compose.runtime.rememberCoroutineScope
// import androidx.compose.runtime.saveable.rememberSaveable // 不再直接使用
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
// import androidx.compose.ui.draw.shadow // 不再直接使用
import androidx.compose.ui.geometry.Offset
// import androidx.compose.ui.geometry.Size // Canvas Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
// import androidx.compose.ui.graphics.drawscope.DrawScope // 不再直接使用
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
// import androidx.compose.ui.text.style.TextOverflow // 不再直接使用
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
// import com.muc.fluocolorquant.ui.components.LocalToastManager // 不再直接使用
// import com.muc.fluocolorquant.ui.components.ToastType // 不再直接使用
import com.muc.fluocolorquant.ui.viewmodels.ResultViewModel
import com.muc.fluocolorquant.utils.HeatmapColorUtil
import com.muc.fluocolorquant.utils.Screen
// import kotlinx.coroutines.CoroutineScope // 使用 rememberCoroutineScope
import kotlinx.coroutines.launch
// import java.io.File // 不再直接使用
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.toArgb
// import androidx.compose.ui.graphics.graphicsLayer // 不再直接使用
// import androidx.compose.ui.graphics.TransformOrigin // 不再直接使用
// import androidx.compose.ui.unit.Dp // 不再直接使用
import kotlin.math.max // 已在ViewModel中使用
// import kotlin.math.min // 不再直接使用
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import com.muc.fluocolorquant.R // 已有

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
    val concentrationUnit by viewModel.concentrationUnit.collectAsState() // 从ViewModel获取单位
    val minConcentrationState by viewModel.minConcentration.collectAsState()
    val maxConcentrationState by viewModel.maxConcentration.collectAsState()

    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    var showExportPanel by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(runId, projectId) {
        when {
            !runId.isNullOrEmpty() -> viewModel.loadResultsByRunId(runId)
            !projectId.isNullOrEmpty() -> viewModel.loadResultsByProjectId(projectId)
            else -> viewModel.loadDefaultOrMostRecentResults()
        }
    }

    val logTagString = stringResource(R.string.log_tag_result_screen)
    val logErrorScreenshotFailedString = stringResource(R.string.log_error_screenshot_failed)

    val captureScreenshot = remember(view, logTagString, logErrorScreenshotFailedString) {
        {
            try {
                val rootView = view.rootView
                val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                rootView.draw(canvas)
                val statusBarHeight = getStatusBarHeight(view.context)
                Bitmap.createBitmap(bitmap, 0, statusBarHeight, bitmap.width, bitmap.height - statusBarHeight)
            } catch (e: Exception) {
                android.util.Log.e(logTagString, logErrorScreenshotFailedString, e)
                null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.detection_results)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        // 使用 AutoMirrored 版本以支持RTL布局
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showExportPanel = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.export), // 使用 drawable 资源
                            contentDescription = stringResource(R.string.export),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 16.dp) // 应用内边距
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
                        Text(stringResource(R.string.loading_result_data))
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
                        ProjectInfoCard(project, concentrationUnit) // 传递从ViewModel获取的concentrationUnit
                        Spacer(modifier = Modifier.height(16.dp))

                        if (project.recognitionType == "AUTO") {
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                                when (page) {
                                    0 -> PlateHeatmapCard(wellResults, concentrationUnit, minConcentrationState, maxConcentrationState, viewModel)
                                    1 -> SquareHeatmapCard(wellResults, concentrationUnit, minConcentrationState, maxConcentrationState, viewModel)
                                    2 -> ConcentrationChartCard(wellResults, concentrationUnit, viewModel)
                                }
                            }
                            // PageIndicator移到这里，在HorizontalPager下方
                            PageIndicator(
                                pagerState = pagerState,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = 16.dp, bottom = 24.dp) // 调整上下边距
                            )
                        } else {
                            SingleWellResultCard(wellResults, concentrationUnit, viewModel) // 传递concentrationUnit
                        }

                        // Spacer(modifier = Modifier.height(24.dp)) // PageIndicator自带一些边距，这个可以调整或移除
                        androidx.compose.material3.Button(
                            onClick = { navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route) { inclusive = false } } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp), // 原始按钮的边距
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = stringResource(R.string.return_to_home), style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(modifier = Modifier.height(16.dp)) // 底部额外间距
                    }
                }

                is ResultViewModel.ResultState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    if (showExportPanel) {
        (resultState as? ResultViewModel.ResultState.Success)?.let { successState ->
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

// ProjectInfoCard, PlateHeatmapCard, PlateWell, SquareHeatmapCard, SquareWell, HeatmapLegend, formatDate, PageIndicator,  SingleWellResultCard, getStatusBarHeight 函数保持不变
// 但需要确保它们内部使用 stringResource 和从 ViewModel 传递的 concentrationUnit

@Composable
fun ProjectInfoCard(project: Project, concentrationUnit: String) { // 接收 concentrationUnit
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = project.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.detection_mode_res,
                    when (project.detectionMode) {
                        "FLUORESCENCE" -> stringResource(R.string.fluorescence_detection_mode)
                        "COLORIMETRIC" -> stringResource(R.string.colorimetric_detection_mode)
                        else -> project.detectionMode
                    }
                )
            )
            Text(
                text = stringResource(
                    R.string.recognition_type_res,
                    when (project.recognitionType) {
                        "AUTO" -> stringResource(R.string.auto_recognition)
                        "MANUAL" -> stringResource(R.string.manual_crop)
                        else -> project.recognitionType
                    }
                )
            )
            Text(text = stringResource(R.string.creation_time, formatDate(project.createTime)))
            project.maxConcentration?.let { maxConc ->
                Text(text = stringResource(R.string.max_concentration_res, maxConc.toString(), concentrationUnit)) // 使用传入的 concentrationUnit
            }
        }
    }
}

@Composable
fun PlateHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String, // 接收 concentrationUnit
    minConcentration: Double,
    maxConcentration: Double,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp), // 为PageIndicator留出空间
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.concentration_heatmap), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.unit_label, concentrationUnit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 使用传入的 concentrationUnit
            }
            Spacer(modifier = Modifier.height(16.dp))
            HeatmapLegend(minValue = minConcentration, maxValue = maxConcentration, unit = concentrationUnit) // 使用传入的 concentrationUnit
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                val wellMap = remember(wellResults) { wellResults.associateBy { it.wellIndex } }
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(20.dp))
                            for (col in 1..12) {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    Text(text = col.toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }
                        for (row in 0 until 8) {
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    Text(text = ('A' + row).toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                                for (col in 0 until 12) {
                                    val index = row * 12 + col
                                    Box(modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                        PlateWell(
                                            wellResult = wellMap[index],
                                            minConcentration = minConcentration,
                                            maxConcentration = maxConcentration,
                                            project = project,
                                            concentrationUnit = concentrationUnit, // 传递 concentrationUnit
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

@Composable
fun PlateWell(
    wellResult: WellResult?,
    minConcentration: Double,
    maxConcentration: Double,
    project: Project?,
    concentrationUnit: String, // 接收 concentrationUnit
    viewModel: ResultViewModel = hiltViewModel()
) {
    val percentValue = wellResult?.predictedConcentration
    val actualConcentration = if (project != null && percentValue != null) {
        viewModel.calculateActualConcentration(percentValue, project.maxConcentration)
    } else null

    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(value = actualConcentration, minValue = minConcentration, maxValue = maxConcentration)
    } else Color(224, 224, 224, 180) // androidx.compose.ui.graphics.Color

    var showTooltip by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clip(CircleShape)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.3f), CircleShape) // androidx.compose.ui.graphics.Color
            .clickable { showTooltip = !showTooltip },
        contentAlignment = Alignment.Center
    ) {
        if (showTooltip && actualConcentration != null && wellResult != null && percentValue != null) {
            Popup(alignment = Alignment.Center, onDismissRequest = { showTooltip = false }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.well_position_short, ('A' + wellResult.wellIndex / 12).toString(), (wellResult.wellIndex % 12) + 1),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = stringResource(R.string.concentration_percent_format, percentValue), style = MaterialTheme.typography.bodySmall)
                        Text(text = stringResource(R.string.concentration_format, actualConcentration, concentrationUnit), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) // 使用传入的 concentrationUnit
                    }
                }
            }
        }
    }
}

@Composable
fun SquareHeatmapCard(
    wellResults: List<WellResult>,
    concentrationUnit: String, // 接收 concentrationUnit
    minConcentration: Double,
    maxConcentration: Double,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp), // 为PageIndicator留出空间
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.concentration_values), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(text = stringResource(R.string.unit_label, concentrationUnit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 使用传入的 concentrationUnit
            }
            Spacer(modifier = Modifier.height(16.dp))
            HeatmapLegend(minValue = minConcentration, maxValue = maxConcentration, unit = concentrationUnit) // 使用传入的 concentrationUnit
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp)),
                shape = RoundedCornerShape(8.dp),
                shadowElevation = 2.dp
            ) {
                val wellMap = remember(wellResults) { wellResults.associateBy { it.wellIndex } }
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(20.dp))
                            for (col in 1..12) {
                                Box(modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                    Text(text = col.toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                            }
                        }
                        for (row in 0 until 8) {
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                    Text(text = ('A' + row).toString(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                }
                                for (col in 0 until 12) {
                                    val index = row * 12 + col
                                    Box(modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f), contentAlignment = Alignment.Center) {
                                        SquareWell(
                                            wellResult = wellMap[index],
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
    } else null

    val wellColor = if (actualConcentration != null) {
        HeatmapColorUtil.getColor(value = actualConcentration, minValue = minConcentration, maxValue = maxConcentration)
    } else Color(224, 224, 224, 180) // androidx.compose.ui.graphics.Color

    val displayText = if (actualConcentration != null) stringResource(R.string.value_format, actualConcentration) else ""
    val textColor = if (calculateLuminance(wellColor) > 0.5f) Color.Black else Color.White // androidx.compose.ui.graphics.Color

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .background(wellColor)
            .border(0.5.dp, Color.DarkGray.copy(alpha = 0.2f)), // androidx.compose.ui.graphics.Color
        contentAlignment = Alignment.Center
    ) {
        Text(text = displayText, fontSize = 6.sp, fontWeight = FontWeight.Bold, color = textColor, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun HeatmapLegend(minValue: Double, maxValue: Double, unit: String) { // 接收 unit
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                HeatmapColorUtil.getLegendColors(20).forEach { color ->
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(color))
                }
            }
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) Box(modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight(0.5f)
                    .background(Color.White.copy(alpha = 0.7f))) // androidx.compose.ui.graphics.Color
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..4) {
                val value = minValue + (maxValue - minValue) * (i / 4.0)
                Text(
                    // text = "%.1f %s".format(value, unit), // 将单位结合进来
                    text = stringResource(R.string.value_format, value), // 或者如果value_format只包含数字部分
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(40.dp) // 可能需要调整宽度以适应单位
                )
            }
        }
        // 显示单位在图例下方
        Text(
            text = stringResource(R.string.unit_label, unit),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        )
    }
}

private fun formatDate(date: Date): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(date)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageIndicator(pagerState: androidx.compose.foundation.pager.PagerState, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val scope = rememberCoroutineScope()
        repeat(pagerState.pageCount) { index ->
            val isSelected = pagerState.currentPage == index
            val width by animateDpAsState(
                targetValue = if (isSelected) 24.dp else 8.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                label = "PageIndicatorWidth"
            )
            val color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = width, height = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConcentrationChartCard(
    wellResults: List<WellResult>,
    concentrationUnit: String, // 接收 concentrationUnit
    viewModel: ResultViewModel = hiltViewModel()
) {
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    val validResults = wellResults.filter { it.predictedConcentration != null && it.predictedConcentration!!.isFinite() }
    val sortedResults = remember(validResults) { validResults.sortedBy { it.wellIndex } }
    val yAxisMaxConcentration = project?.maxConcentration ?: 100.0
    var selectedPointIndex by remember { mutableStateOf<Int?>(null) }

    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp), // 为PageIndicator留出空间
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)) {
            Text(text = stringResource(R.string.concentration_chart), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.well_concentration_trend, concentrationUnit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 使用传入的 concentrationUnit
            Spacer(modifier = Modifier.height(16.dp))

            if (sortedResults.isNotEmpty()) {
                val chartHeight = 250.dp
                // 移除原来的 selectedPointIndex?.let Card 定义

                // 图表Canvas部分
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .height(chartHeight - 36.dp)
                            .align(Alignment.CenterStart)
                            .offset(x = (-38).dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (i in 5 downTo 0) {
                            val percentOfMax = i * 20.0 / 100.0
                            val actualValue = percentOfMax * yAxisMaxConcentration
                            Text(text = stringResource(R.string.value_format, actualValue), fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(36.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                    }
                    if (sortedResults.size > 1) {
                        val scrollState = rememberScrollState()
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(scrollState)) {
                            val dataPointWidth = 40.dp
                            val chartWidth = maxOf(dataPointWidth * sortedResults.size, 350.dp)
                            val primaryColorArgb = MaterialTheme.colorScheme.primary.toArgb()
                            val primaryColorHighlightedArgb = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f).toArgb()
                            val canvasBackgroundColor = Color.White // androidx.compose.ui.graphics.Color
                            val dataPoints = remember { mutableStateListOf<Pair<Offset, Int>>() }
                            var tapSelectedIndex by remember { mutableStateOf<Int?>(null) }
                            LaunchedEffect(tapSelectedIndex) { selectedPointIndex = tapSelectedIndex }

                            val xLabels = remember(sortedResults, R.string.well_position_short, context) {
                                if (sortedResults.isEmpty()) {
                                    emptyList()
                                } else {
                                    sortedResults.map { result ->
                                        val rowChar = ('A' + result.wellIndex / 12).toChar()
                                        val colNumber = (result.wellIndex % 12) + 1
                                        context.getString(R.string.well_position_short, rowChar.toString(), colNumber)
                                    }
                                }
                            }

                            Canvas(
                                modifier = Modifier
                                    .width(chartWidth)
                                    .fillMaxHeight()
                                    .pointerInput(Unit) {
                                        detectTapGestures { tapPosition ->
                                            val closestPoint = dataPoints.minByOrNull { (position, _) ->
                                                val dx = position.x - tapPosition.x; val dy = position.y - tapPosition.y; dx * dx + dy * dy
                                            }
                                            closestPoint?.let { (position, index) ->
                                                val dx = position.x - tapPosition.x; val dy = position.y - tapPosition.y
                                                if (kotlin.math.sqrt(dx * dx + dy * dy) < 25) tapSelectedIndex = index
                                            }
                                        }
                                    }
                            ) {
                                val height = size.height; val width = size.width; val pointCount = sortedResults.size
                                dataPoints.clear()
                                val gridColor = Color.Gray.copy(alpha = 0.15f); val gridStrokeWidth = 1f // androidx.compose.ui.graphics.Color
                                for (i in 0..5) { val y = height - (height * i / 5); drawLine(gridColor, Offset(0f, y), Offset(width, y), gridStrokeWidth) }
                                val pointDistance = if (pointCount > 1) width / (pointCount - 1f) else width
                                for (i in 0 until pointCount) if (i % 2 == 0) { val x = i * pointDistance; drawLine(gridColor, Offset(x, 0f), Offset(x, height), gridStrokeWidth) }
                                val path = Path(); var firstPoint = true
                                sortedResults.forEachIndexed { i, result ->
                                    val x = i * pointDistance
                                    val actualConcentrationValue = viewModel.calculateActualConcentration(result.predictedConcentration, project?.maxConcentration) ?: 0.0
                                    val normalizedY = if (yAxisMaxConcentration > 0) (actualConcentrationValue / yAxisMaxConcentration).toFloat() else 0f
                                    val y = (height - (normalizedY * height)).coerceIn(0f, height)
                                    dataPoints.add(Offset(x, y) to i)
                                    if (firstPoint) { path.moveTo(x, y); firstPoint = false } else path.lineTo(x, y)
                                    val pointRadius = if (i == selectedPointIndex) 8f else 5f
                                    val pointColor = if (i == selectedPointIndex) Color(primaryColorHighlightedArgb) else Color(primaryColorArgb) // androidx.compose.ui.graphics.Color
                                    if (i == selectedPointIndex) drawCircle(canvasBackgroundColor, pointRadius + 2f, Offset(x, y))
                                    drawCircle(pointColor, pointRadius, Offset(x, y))
                                }
                                drawPath(path, Color(primaryColorArgb), style = Stroke(width = 2.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.cornerPathEffect(5f))) // androidx.compose.ui.graphics.Color
                            }
                            Box(modifier = Modifier
                                .width(chartWidth)
                                .height(24.dp)
                                .align(Alignment.BottomCenter)) {
                                val density = LocalDensity.current
                                val textColorArgb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f).toArgb()
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val textPaint = android.text.TextPaint().apply { textSize = with(density) { 9.sp.toPx() }; color = textColorArgb; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
                                    val pointDistance = if (xLabels.size > 1) size.width / (xLabels.size - 1f) else size.width
                                    xLabels.forEachIndexed { i, label ->
                                        val xPos = i * pointDistance
                                        this.drawContext.canvas.nativeCanvas.drawText(label, xPos, size.height - 4.dp.toPx(), textPaint)
                                    }
                                }
                            }
                        }
                    } else if (sortedResults.size == 1) { // 单个数据点的情况
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val result = sortedResults[0]
                            // val actualConcentrationValue = viewModel.calculateActualConcentration(result.predictedConcentration, project?.maxConcentration) ?: 0.0
                            val rowChar = ('A' + result.wellIndex / 12).toChar()
                            val colNumber = (result.wellIndex % 12) + 1
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(4.dp), contentAlignment = Alignment.Center) {
                                    Box(modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(Color.White)) // androidx.compose.ui.graphics.Color
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = stringResource(R.string.well_position_short, rowChar.toString(), colNumber), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                // 这里可以不显示具体浓度，因为下方会有详细信息卡片
                                // Text(text = stringResource(R.string.concentration_format, actualConcentrationValue, concentrationUnit), fontSize = 11.sp)
                            }
                        }
                    }
                } // 结束图表Canvas Box

                Spacer(modifier = Modifier.height(16.dp)) // 图表与下方信息的间距

                // 将 selectedPointIndex 的信息卡片移到这里，图表的下方
                selectedPointIndex?.let { index ->
                    if (index < sortedResults.size) {
                        val result = sortedResults[index]
                        val rowChar = ('A' + result.wellIndex / 12).toChar()
                        val colNumber = (result.wellIndex % 12) + 1
                        val percentValue = result.predictedConcentration ?: 0.0
                        val actualConcentrationValue = viewModel.calculateActualConcentration(percentValue, project?.maxConcentration) ?: 0.0
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(text = stringResource(R.string.well_position_short, rowChar.toString(), colNumber), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(text = stringResource(R.string.concentration_percent_and_value, percentValue, actualConcentrationValue, concentrationUnit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                        .clickable { selectedPointIndex = null },
                                    contentAlignment = Alignment.Center
                                ) { Text(stringResource(R.string.close_button), fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = stringResource(R.string.click_datapoint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sortedResults.size > 10) { // 根据实际情况调整，判断何时显示滑动提示
                    Text(text = stringResource(R.string.swipe_for_more), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            } else { // sortedResults为空
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp), contentAlignment = Alignment.Center) {
                    Text(text = stringResource(R.string.no_concentration_data), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun calculateLuminance(color: Color): Float { // androidx.compose.ui.graphics.Color
    val red = color.red; val green = color.green; val blue = color.blue
    return (0.299f * red + 0.587f * green + 0.114f * blue)
}

@Composable
fun SingleWellResultCard(
    wellResults: List<WellResult>,
    concentrationUnit: String, // 接收 concentrationUnit
    viewModel: ResultViewModel
) {
    val singleWell = wellResults.firstOrNull { it.predictedConcentration != null }
    val project = (viewModel.resultState.collectAsState().value as? ResultViewModel.ResultState.Success)?.project
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(), // 让内容横向填充
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = project?.name ?: stringResource(R.string.manual_crop_result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            project?.detectionMode?.let { mode ->
                val detectionModeText = when (mode) {
                    "FLUORESCENCE" -> stringResource(R.string.fluorescence_detection_mode)
                    "COLORIMETRIC" -> stringResource(R.string.colorimetric_detection_mode)
                    else -> mode
                }
                Text(text = detectionModeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (singleWell != null) {
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .aspectRatio(1f)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    val imageSource = viewModel.getWellImageFile(singleWell)
                    if (imageSource != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(imageSource).crossfade(true).build(),
                            contentDescription = stringResource(R.string.well_image), contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        Box(modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray), contentAlignment = Alignment.Center) { // androidx.compose.ui.graphics.Color
                            Text(stringResource(R.string.no_image))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
                singleWell.predictedConcentration?.let { percentValue ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.concentration_percent), style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.concentration_percent_value, percentValue), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    val actualConcentrationValue = viewModel.calculateActualConcentration(percentValue, project?.maxConcentration)
                    if (actualConcentrationValue != null) {
                        Text(text = stringResource(R.string.actual_concentration, project?.maxConcentration ?: 100.0, concentrationUnit), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) // 使用传入的 concentrationUnit
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = stringResource(R.string.concentration_value, actualConcentrationValue, concentrationUnit), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) // 使用传入的 concentrationUnit
                    }
                } ?: Text(text = stringResource(R.string.unable_to_measure), color = MaterialTheme.colorScheme.error)
            } else {
                Box(modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) { // androidx.compose.ui.graphics.Color
                    Text(text = stringResource(R.string.no_valid_results), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

private fun getStatusBarHeight(context: android.content.Context): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
}