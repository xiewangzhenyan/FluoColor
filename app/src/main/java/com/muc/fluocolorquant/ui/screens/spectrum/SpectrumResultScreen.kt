package com.muc.fluocolorquant.ui.screens.spectrum

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.charts.CurveChart
import com.muc.fluocolorquant.ui.viewmodels.SpectrumChannelUiModel
import com.muc.fluocolorquant.ui.viewmodels.SpectrumResultViewModel

/**
 * 光谱分析结果展示页面
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpectrumResultScreen(
    navController: NavHostController,
    projectId: String,
    viewModel: SpectrumResultViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val toastManager = LocalToastManager.current
    
    // 加载数据
    LaunchedEffect(projectId) {
        if (projectId.isNotBlank()) {
            viewModel.loadProjectResults(projectId)
        }
    }
    
    // 显示错误信息
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            toastManager.showToast(it, ToastType.ERROR)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.spectrum_result_title),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF5D6B98),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF5D6B98))
            }
        } else if (state.results.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.spectrum_no_data),
                    color = Color.Gray,
                    fontSize = 16.sp
                )
            }
        } else {
            // 使用 HorizontalPager 分页展示
            val pagerState = rememberPagerState(pageCount = { state.results.size })
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Color(0xFFF5F7FA))
            ) {
                // 页面指示器
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            R.string.spectrum_channel_format,
                            pagerState.currentPage + 1,
                            state.results.size
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3142)
                    )
                    
                    // 圆点指示器
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        repeat(state.results.size) { index ->
                            Box(
                                modifier = Modifier
                                    .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage) 
                                            Color(0xFF5D6B98) 
                                        else 
                                            Color(0xFFCCCCCC)
                                    )
                            )
                        }
                    }
                }
                
                // Pager 内容
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val channelData = state.results[page]
                    ChannelResultPage(channelData = channelData)
                }
            }
        }
    }
}

/**
 * 单个通道的结果页面
 */
@Composable
private fun ChannelResultPage(
    channelData: SpectrumChannelUiModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 分析物标题卡片
        AnalyteTitleCard(
            analyteName = channelData.analyteName,
            channelIndex = channelData.channelIndex,
            modifier = Modifier.fillMaxWidth()
        )
        
        // 光谱曲线卡片
        SpectrumCurveCard(
            chartData = channelData.chartData,
            modifier = Modifier.fillMaxWidth()
        )
        
        // 峰值信息卡片
        PeakInfoCard(
            peakWavelength = channelData.peakWavelength,
            peakIntensity = channelData.peakIntensity,
            dataPointCount = channelData.dataPointCount,
            modifier = Modifier.fillMaxWidth()
        )
        
        // 数据范围卡片
        DataRangeCard(
            minWavelength = channelData.minWavelength,
            maxWavelength = channelData.maxWavelength,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 分析物标题卡片
 */
@Composable
private fun AnalyteTitleCard(
    analyteName: String,
    channelIndex: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.spectrum_analyte_label),
                fontSize = 14.sp,
                color = Color(0xFF6B7280),
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            val unboundText = stringResource(R.string.spectrum_unbound)
            Text(
                text = analyteName,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (analyteName == unboundText) Color(0xFF9CA3AF) else Color(0xFF5D6B98)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.spectrum_channel_label, channelIndex),
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

/**
 * 光谱曲线展示卡片
 */
@Composable
private fun SpectrumCurveCard(
    chartData: com.muc.fluocolorquant.ui.components.charts.ChartData,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF5D6B98)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ShowChart,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.spectrum_curve_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF2D3142)
                )
            }
            
            CurveChart(
                data = chartData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )
        }
    }
}

/**
 * 峰值信息卡片
 */
@Composable
private fun PeakInfoCard(
    peakWavelength: Float?,
    peakIntensity: Double?,
    dataPointCount: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFF6B6B)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.spectrum_peak_result_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF2D3142)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoItem(
                    label = stringResource(R.string.spectrum_peak_wavelength),
                    value = peakWavelength?.let { String.format("%.1f nm", it) } ?: "--",
                    modifier = Modifier.weight(1f),
                    highlightColor = Color(0xFFFF6B6B)
                )
                
                InfoItem(
                    label = stringResource(R.string.spectrum_peak_intensity),
                    value = peakIntensity?.let { String.format("%.3f", it) } ?: "--",
                    modifier = Modifier.weight(1f),
                    highlightColor = Color(0xFF5D6B98)
                )
                
                InfoItem(
                    label = stringResource(R.string.spectrum_data_points),
                    value = dataPointCount.toString(),
                    modifier = Modifier.weight(1f),
                    highlightColor = Color(0xFF10B981)
                )
            }
        }
    }
}

/**
 * 数据范围卡片
 */
@Composable
private fun DataRangeCard(
    minWavelength: Double,
    maxWavelength: Double,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.spectrum_wavelength_range),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF2D3142),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF667eea).copy(alpha = 0.1f),
                                Color(0xFF764ba2).copy(alpha = 0.1f)
                            )
                        )
                    )
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.spectrum_min_wavelength),
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280)
                    )
                    Text(
                        text = String.format("%.1f nm", minWavelength),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF667eea)
                    )
                }
                
                Text(
                    text = "→",
                    fontSize = 24.sp,
                    color = Color(0xFF9CA3AF)
                )
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.spectrum_max_wavelength),
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280)
                    )
                    Text(
                        text = String.format("%.1f nm", maxWavelength),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF764ba2)
                    )
                }
            }
        }
    }
}

/**
 * 信息项组件
 */
@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlightColor: Color = Color(0xFF5D6B98)
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(highlightColor.copy(alpha = 0.08f))
            .border(1.dp, highlightColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = highlightColor
        )
    }
}
