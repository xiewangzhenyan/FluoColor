package com.muc.fluocolorquant.ui.screens.imagecrop

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.isseiaoki.simplecropview.CropImageView
import com.isseiaoki.simplecropview.callback.CropCallback
import com.isseiaoki.simplecropview.callback.LoadCallback
import com.isseiaoki.simplecropview.callback.SaveCallback
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

enum class TabOption { SCALE, ROTATE, RATIO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    navController: NavController,
    imageUri: String? = null,
    viewModel: ConcentrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 状态
    var isLoading by remember { mutableStateOf(false) }
    var cropView by remember { mutableStateOf<CropImageView?>(null) }
    var selectedTab by remember { mutableStateOf(TabOption.SCALE) }
    var scaleValue by remember { mutableStateOf(100) }
    var currentRatio by remember { mutableStateOf(CropImageView.CropMode.SQUARE) }
    
    // 添加旋转状态跟踪
    var currentRotation by remember { mutableStateOf(0) }
    
    // 创建保存图片的URI
    fun createSaveUri(): Uri {
        val file = File(context.cacheDir, "cropped_${UUID.randomUUID()}.jpg")
        return Uri.fromFile(file)
    }
    
    // 旋转图片并更新状态
    fun rotateImage(degrees: Int) {
        cropView?.let {
            currentRotation = (currentRotation + degrees) % 360
            when (degrees) {
                90 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_90D)
                180 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_180D)
                270 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_270D)
            }
        }
    }
    
    // 重置旋转到初始状态
    fun resetRotation() {
        cropView?.let {
            // 计算需要旋转多少度才能回到0度
            val degreesToReset = when {
                currentRotation == 0 -> 0
                currentRotation == 90 -> 270
                currentRotation == 180 -> 180
                currentRotation == 270 -> 90
                else -> 0
            }
            
            if (degreesToReset > 0) {
                when (degreesToReset) {
                    90 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_90D)
                    180 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_180D)
                    270 -> it.rotateImage(CropImageView.RotateDegrees.ROTATE_270D)
                }
            }
            
            // 重置状态
            currentRotation = 0
        }
    }
    
    // 回调
    val loadCallback = remember {
        object : LoadCallback {
            override fun onSuccess() {
                isLoading = false
            }
            
            override fun onError(e: Throwable) {
                isLoading = false
            }
        }
    }
    
    val cropCallback = remember {
        object : CropCallback {
            override fun onSuccess(cropped: Bitmap) {
                isLoading = true
                
                // 保存裁剪后的图片
                cropView?.save(cropped)
                    ?.execute(createSaveUri(), object : SaveCallback {
                        override fun onSuccess(uri: Uri) {
                            isLoading = false
                            // 返回裁剪后的图片URI
                            navController.previousBackStackEntry?.savedStateHandle?.set(
                                "croppedImageUri", 
                                uri.toString()
                            )
                            
                            // 判断是否来自新建项目页面（手动模式）
                            val previousRoute = navController.previousBackStackEntry?.destination?.route
                            if (previousRoute?.contains(Screen.NewProject.route) == true) {
                                // 如果是从新建项目页面来，则返回上一级
                                navController.popBackStack()
                            } else {
                                // 如果是从其他页面来（可能是手动分析模式），分析裁剪后的图像
                                // 获取项目ID，通常会从导航参数中传递
                                scope.launch {
                                    try {
                                        // 获取项目ID - 这里需要根据实际情况修改，可以从路由、参数或保存的状态中获取
                                        val projectId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("projectId")
                                        
                                        if (!projectId.isNullOrEmpty()) {
                                            // 分析裁剪后的图像
                                            viewModel.analyzeManualCroppedImage(projectId, uri)
                                            
                                            // 导航到结果页面
                                            navController.navigate("${Screen.Result.route}?projectId=$projectId") {
                                                // 弹出到前一个页面，避免回退到裁剪页面
                                                popUpTo(navController.currentBackStackEntry?.destination?.route ?: "") {
                                                    inclusive = true
                                                }
                                            }
                                        } else {
                                            // 如果无法获取项目ID，则返回上一级
                                            navController.popBackStack()
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("ImageCropScreen", "分析裁剪图像失败", e)
                            navController.popBackStack()
                                    }
                                }
                            }
                        }
                        
                        override fun onError(e: Throwable) {
                            isLoading = false
                        }
                    })
            }
            
            override fun onError(e: Throwable) {
                isLoading = false
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "裁剪",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            cropView?.let {
                                isLoading = true
                                it.crop(it.sourceUri).execute(cropCallback)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "完成"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // SimpleCropView集成 - 向上移动40dp
            AndroidView(
                factory = { ctx ->
                    CropImageView(ctx).apply {
                        // 设置裁剪模式为正方形
                        setCropMode(CropImageView.CropMode.SQUARE)
                        
                        // 设置UI颜色
                        setHandleColor(AndroidColor.WHITE)
                        setGuideColor(AndroidColor.WHITE)
                        setFrameColor(AndroidColor.WHITE)
                        setOverlayColor(AndroidColor.parseColor("#88000000"))
                        
                        // 设置UI特性
                        setHandleSizeInDp(14)
                        setTouchPaddingInDp(8)
                        setMinFrameSizeInDp(50)
                        setFrameStrokeWeightInDp(1)
                        setGuideStrokeWeightInDp(1)
                        
                        // 设置UI显示模式
                        setGuideShowMode(CropImageView.ShowMode.SHOW_ALWAYS)
                        setHandleShowMode(CropImageView.ShowMode.SHOW_ALWAYS)
                        
                        // 设置初始裁剪框比例
                        setInitialFrameScale(0.75f)
                        
                        // 设置动画
                        setAnimationEnabled(true)
                        setAnimationDuration(200)
                        
                        // 启用阴影
                        setHandleShadowEnabled(true)
                        
                        // 加载图片
                        if (imageUri != null) {
                            isLoading = true
                            load(Uri.parse(imageUri)).execute(loadCallback)
                        }
                        
                        cropView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 90.dp) // 添加底部padding，使图片向上移动40dp
            )
            
            // 底部控制面板
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black)
            ) {
                // 根据选中的选项卡显示相应控件
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color.DarkGray.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    when (selectedTab) {
                        TabOption.SCALE -> {
                            // 缩放控制
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 重置按钮
                                IconButton(
                                    onClick = {
                                        scaleValue = 100
                                        cropView?.setInitialFrameScale(0.75f)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "重置缩放",
                                        tint = Color.White
                                    )
                                }
                                
                                // 滑动条
                                Slider(
                                    value = scaleValue.toFloat(),
                                    onValueChange = { value ->
                                        scaleValue = value.toInt()
                                        cropView?.setInitialFrameScale(value / 133f)
                                    },
                                    valueRange = 50f..150f,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                // 显示百分比
                                Text(
                                    text = "$scaleValue%",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        TabOption.ROTATE -> {
                            // 旋转控制
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 重置按钮
                                IconButton(
                                    onClick = {
                                        // 恢复默认旋转
                                        resetRotation()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Autorenew,
                                        contentDescription = "重置旋转",
                                        tint = Color.White
                                    )
                                }
                                
                                // 旋转选项
                                Row(
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                ) {
                                    // 反向旋转90度
                                    IconButton(
                                        onClick = {
                                            rotateImage(270)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RotateLeft,
                                            contentDescription = "反向旋转90°",
                                            tint = Color.White
                                        )
                                    }
                                    
                                    // 正向旋转90度
                                    IconButton(
                                        onClick = {
                                            rotateImage(90)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "正向旋转90°",
                                            tint = Color.White
                                        )
                                    }
                                    
                                    // 正向旋转180度
                                    IconButton(
                                        onClick = {
                                            rotateImage(180)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RotateRight,
                                            contentDescription = "正向旋转180°",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                        TabOption.RATIO -> {
                            // 比例控制
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 8.dp)
                            ) {
                                // 所有比例选项
                                val ratioOptions = listOf(
                                    "全选" to CropImageView.CropMode.FIT_IMAGE,
                                    "正方形" to CropImageView.CropMode.SQUARE,
                                    "自由" to CropImageView.CropMode.FREE,
                                    "圆形" to CropImageView.CropMode.CIRCLE,
                                    "4:3" to CropImageView.CropMode.RATIO_4_3,
                                    "3:4" to CropImageView.CropMode.RATIO_3_4,
                                    "16:9" to CropImageView.CropMode.RATIO_16_9,
                                    "9:16" to CropImageView.CropMode.RATIO_9_16,
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                ratioOptions.forEach { (label, mode) ->
                                    val isSelected = currentRatio == mode
                                    
                                    TextButton(
                                        onClick = {
                                            currentRatio = mode
                                            cropView?.setCropMode(mode)
                                        },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = if (isSelected) Color.Green else Color.White
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Text(text = label)
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }
                }
                
                // 选项卡底部
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 缩放选项卡
                    TabButton(
                        icon = Icons.Default.ZoomIn,
                        label = "缩放",
                        isSelected = selectedTab == TabOption.SCALE,
                        onClick = { selectedTab = TabOption.SCALE }
                    )
                    
                    // 旋转选项卡
                    TabButton(
                        icon = Icons.Default.RotateRight,
                        label = "旋转",
                        isSelected = selectedTab == TabOption.ROTATE,
                        onClick = { selectedTab = TabOption.ROTATE }
                    )
                    
                    // 比例选项卡
                    TabButton(
                        icon = Icons.Default.AspectRatio,
                        label = "比例",
                        isSelected = selectedTab == TabOption.RATIO,
                        onClick = { selectedTab = TabOption.RATIO }
                    )
                }
            }
            
            // 加载状态指示器
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x88000000)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
fun TabButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color.Black else Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
} 