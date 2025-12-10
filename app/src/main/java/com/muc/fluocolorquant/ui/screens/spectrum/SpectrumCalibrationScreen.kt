package com.muc.fluocolorquant.ui.screens.spectrum

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.CalibrationMode
import com.muc.fluocolorquant.ui.viewmodels.SpectrumCalibrationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import kotlin.math.abs
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumCalibrationScreen(
    navController: NavHostController,
    projectId: String?,
    imageUri: String?,
    viewModel: SpectrumCalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(projectId, imageUri) {
        if (!projectId.isNullOrBlank() && !imageUri.isNullOrBlank()) {
            viewModel.loadOriginal(projectId, Uri.decode(imageUri))
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { toastManager.showToast(it, ToastType.ERROR) }
    }

    // 监听成功提示信息
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            toastManager.showToast(it, ToastType.SUCCESS)
            viewModel.clearInfoMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spectrum_calibration_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val pid = projectId ?: return@TextButton
                            viewModel.completeCalibration(pid) {
                                navController.navigate(Screen.Result.createRoute(pid)) {
                                    popUpTo(Screen.SpectrumCalibration.route) { inclusive = true }
                                }
                            }
                        },
                        enabled = state.coefficients.size == state.trackRects.size && state.trackRects.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.finish_calibration))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (state.calibrationMode) {
                CalibrationMode.AUTO -> AutoCalibrationSection(
                    state = state,
                    onUpload = { bitmap -> viewModel.onCalibrationImageLoaded(bitmap) },
                    onReferenceChanged = { viewModel.setReferenceWavelengths(it) },
                    onStartFitting = { viewModel.performAutoCalibration() }
                )

                CalibrationMode.MANUAL -> ManualCalibrationSection(
                    state = state,
                    onAddPoint = { track, point, wavelength -> viewModel.addManualPoint(track, point, wavelength) },
                    onTrackChange = { viewModel.updateCurrentTrack(it) },
                    onMagicApply = { viewModel.applyMagicFromTrack0() },
                    onUpdatePointY = { track, pointId, newY -> viewModel.updateManualPointY(track, pointId, newY) },
                    onDeletePoint = { track, pointId -> viewModel.deleteManualPoint(track, pointId) }
                )

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.loading))
                    }
                }
            }

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    // 模式选择对话框
    if (state.showModeDialog) {
        AlertDialog(
            onDismissRequest = { /* modal */ },
            title = { Text(stringResource(R.string.select_calibration_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.calibration_mode_hint))
                    OutlinedButton(
                        onClick = { viewModel.setCalibrationMode(CalibrationMode.AUTO) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.auto_calibration_option))
                    }
                    OutlinedButton(
                        onClick = { viewModel.setCalibrationMode(CalibrationMode.MANUAL) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.manual_calibration_option))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // 尺寸不匹配对话框
    if (state.showDimensionMismatchDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDimensionMismatchDialog() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE68A00)) },
            title = { Text(stringResource(R.string.calibration_image_mismatch_title)) },
            text = { Text(stringResource(R.string.calibration_image_mismatch_desc)) },
            confirmButton = {
                TextButton(onClick = { viewModel.switchToManualMode() }) {
                    Text(stringResource(R.string.switch_to_manual))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDimensionMismatchDialog() }) {
                    Text(stringResource(R.string.reupload))
                }
            }
        )
    }
}

@Composable
private fun AutoCalibrationSection(
    state: com.muc.fluocolorquant.ui.viewmodels.SpectrumCalibrationUiState,
    onUpload: (Bitmap) -> Unit,
    onReferenceChanged: (List<Float>) -> Unit,
    onStartFitting: () -> Unit
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val scope = rememberCoroutineScope()

    val calibrationPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.onSuccess { bmp ->
                    if (bmp != null) onUpload(bmp) else toastManager.showToast(
                        context.getString(R.string.upload_calibration_image_failed),
                        ToastType.ERROR
                    )
                }.onFailure {
                    toastManager.showToast(it.message ?: "", ToastType.ERROR)
                }
            }
        }
    }

    // 文本输入状态（UI 层面，成功解析后同步给 ViewModel）
    val wavelengthInputs = remember { mutableStateListOf("435.8", "546.1", "578.0") }

    LaunchedEffect(wavelengthInputs) {
        val parsed = wavelengthInputs.mapNotNull { it.toFloatOrNull() }
        if (parsed.isNotEmpty()) onReferenceChanged(parsed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.calibration_image_section), fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.calibration_image_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { calibrationPicker.launch("image/*") }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_calibration_image))
                    }
                    if (state.calibrationImageBitmap != null) {
                        Text(
                            stringResource(R.string.calibration_image_ready),
                            color = Color(0xFF2D6A4F),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reference_wavelengths_title), fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(R.string.reference_wavelengths_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                wavelengthInputs.forEachIndexed { index, value ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { newValue ->
                            wavelengthInputs[index] = newValue
                            val parsed = wavelengthInputs.mapNotNull { it.toFloatOrNull() }
                            onReferenceChanged(parsed)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.reference_wavelength_item, index + 1)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { wavelengthInputs.add("") }) {
                        Text(stringResource(R.string.add_wavelength))
                    }
                    if (wavelengthInputs.size > 1) {
                        OutlinedButton(onClick = { wavelengthInputs.removeLast() }) {
                            Text(stringResource(R.string.remove_wavelength))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onStartFitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !state.isAutoFitting
        ) {
            if (state.isAutoFitting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.fitting_in_progress))
            } else {
                Text(stringResource(R.string.start_fitting))
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun ManualCalibrationSection(
    state: com.muc.fluocolorquant.ui.viewmodels.SpectrumCalibrationUiState,
    onAddPoint: (Int, PointF, Float) -> Unit,
    onTrackChange: (Int) -> Unit,
    onMagicApply: () -> Unit,
    onUpdatePointY: (Int, String, Float) -> Unit,
    onDeletePoint: (Int, String) -> Unit
) {
    val bitmap = state.originalBitmap ?: return
    var userScale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize(0, 0)) }
    var pendingPoint by remember { mutableStateOf<PointF?>(null) }
    var wavelengthInput by remember { mutableStateOf("") }
    
    // 拖动状态
    var draggedPointId by remember { mutableStateOf<String?>(null) }
    var draggedY by remember { mutableStateOf<Float?>(null) }

    val density = LocalDensity.current
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        userScale = (userScale * zoomChange).coerceIn(0.5f, 5f)
        pan += panChange
    }

    val baseSizePx = remember(containerSize, bitmap) {
        val w = containerSize.width.toFloat()
        val h = containerSize.height.toFloat()
        if (w == 0f || h == 0f) Pair(0f, 0f) else {
            val scale = min(w / bitmap.width, h / bitmap.height)
            Pair(bitmap.width * scale, bitmap.height * scale)
        }
    }
    val baseOffset = remember(containerSize, baseSizePx) {
        Offset(
            x = ((containerSize.width - baseSizePx.first) / 2f),
            y = ((containerSize.height - baseSizePx.second) / 2f)
        )
    }
    
    val currentTrackRect = state.trackRects.getOrNull(state.currentTrackIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .onGloballyPositioned { coords -> containerSize = coords.size }
                .transformable(transformState)
                .pointerInput(state.trackRects, bitmap, userScale, pan, containerSize, state.currentTrackIndex, state.manualPoints) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // 检测是否点击在某条标定线附近
                            if (baseSizePx.first == 0f || baseSizePx.second == 0f) return@detectDragGestures
                            val baseScale = if (bitmap.width == 0 || bitmap.height == 0) 1f else baseSizePx.first / bitmap.width.toFloat()
                            val scaleY = baseSizePx.second / bitmap.height.toFloat()
                            
                            val marks = state.manualPoints[state.currentTrackIndex].orEmpty()
                            val touchThreshold = 20f / userScale // 触摸阈值
                            
                            val found = marks.find { mark ->
                                val displayY = baseOffset.y + pan.y + mark.point.y * scaleY * userScale
                                abs(offset.y - displayY) < touchThreshold
                            }
                            
                            if (found != null) {
                                draggedPointId = found.id
                                draggedY = found.point.y
                            }
                        },
                        onDrag = { change, _ ->
                            val pointId = draggedPointId ?: return@detectDragGestures
                            val track = currentTrackRect ?: return@detectDragGestures
                            
                            if (baseSizePx.first == 0f || baseSizePx.second == 0f) return@detectDragGestures
                            val baseScale = if (bitmap.width == 0 || bitmap.height == 0) 1f else baseSizePx.first / bitmap.width.toFloat()
                            val scaleY = baseSizePx.second / bitmap.height.toFloat()
                            
                            // 转换触摸坐标到图像坐标
                            val imageY = (change.position.y - baseOffset.y - pan.y) / (scaleY * userScale)
                            
                            // 限制在当前通道 ROI 内
                            val clampedY = imageY.coerceIn(track.top.toFloat(), track.bottom.toFloat())
                            draggedY = clampedY
                        },
                        onDragEnd = {
                            val pointId = draggedPointId
                            val newY = draggedY
                            if (pointId != null && newY != null) {
                                onUpdatePointY(state.currentTrackIndex, pointId, newY)
                            }
                            draggedPointId = null
                            draggedY = null
                        },
                        onDragCancel = {
                            draggedPointId = null
                            draggedY = null
                        }
                    )
                }
                .pointerInput(state.trackRects, bitmap, userScale, pan, containerSize, state.currentTrackIndex, state.manualPoints) {
                    detectTapGestures(
                        onTap = { tap ->
                            // 点击添加标定点
                            if (baseSizePx.first == 0f || baseSizePx.second == 0f) return@detectTapGestures
                            val baseScale = if (bitmap.width == 0 || bitmap.height == 0) 1f else baseSizePx.first / bitmap.width.toFloat()
                            val imageX = (tap.x - baseOffset.x - pan.x) / (baseScale * userScale)
                            val imageY = (tap.y - baseOffset.y - pan.y) / (baseScale * userScale)
                            if (imageX in 0f..bitmap.width.toFloat() && imageY in 0f..bitmap.height.toFloat()) {
                                pendingPoint = PointF(imageX, imageY)
                                wavelengthInput = ""
                            }
                        },
                        onLongPress = { longPress ->
                            // 长按删除标定点
                            if (baseSizePx.first == 0f || baseSizePx.second == 0f) return@detectTapGestures
                            val scaleY = baseSizePx.second / bitmap.height.toFloat()
                            val marks = state.manualPoints[state.currentTrackIndex].orEmpty()
                            val touchThreshold = 20f / userScale
                            
                            val found = marks.find { mark ->
                                val displayY = baseOffset.y + pan.y + mark.point.y * scaleY * userScale
                                abs(longPress.y - displayY) < touchThreshold
                            }
                            
                            if (found != null) {
                                onDeletePoint(state.currentTrackIndex, found.id)
                            }
                        }
                    )
                }
        ) {
            val displayWidth = baseSizePx.first
            val displayHeight = baseSizePx.second
            val displayWidthDp: Dp
            val displayHeightDp: Dp
            with(density) {
                displayWidthDp = displayWidth.toDp()
                displayHeightDp = displayHeight.toDp()
            }

            val transformModifier = Modifier
                .size(displayWidthDp, displayHeightDp)
                .graphicsLayer(
                    translationX = baseOffset.x + pan.x,
                    translationY = baseOffset.y + pan.y,
                    scaleX = userScale,
                    scaleY = userScale
                )

            // 1. 绘制原始图片
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = transformModifier,
                contentScale = ContentScale.FillBounds
            )

            // 2. 绘制聚光灯效果 + 标定线
            Canvas(modifier = transformModifier) {
                val track = currentTrackRect
                val scaleX = displayWidth / bitmap.width
                val scaleY = displayHeight / bitmap.height

                // 绘制半透明黑色遮罩,通过绘制四个矩形块避开当前通道区域
                if (track != null) {
                    val left = track.left * scaleX
                    val top = track.top * scaleY
                    val right = track.right * scaleX
                    val bottom = track.bottom * scaleY
                    
                    drawIntoCanvas { canvas ->
                        val overlayPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(153, 0, 0, 0) // alpha = 0.6 * 255 ≈ 153
                        }
                        
                        // 上方矩形
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, top, overlayPaint)
                        // 下方矩形
                        canvas.nativeCanvas.drawRect(0f, bottom, size.width, size.height, overlayPaint)
                        // 左侧矩形
                        canvas.nativeCanvas.drawRect(0f, top, left, bottom, overlayPaint)
                        // 右侧矩形
                        canvas.nativeCanvas.drawRect(right, top, size.width, bottom, overlayPaint)
                    }
                } else {
                    // 如果没有选中通道,整个区域都覆盖遮罩
                    drawIntoCanvas { canvas ->
                        val overlayPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(153, 0, 0, 0)
                        }
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, overlayPaint)
                    }
                }

                // 绘制红色边框
                if (track != null) {
                    val left = track.left * scaleX
                    val top = track.top * scaleY
                    val right = track.right * scaleX
                    val bottom = track.bottom * scaleY
                    
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }

                // 绘制标定线,限制在当前通道区域内
                if (track != null) {
                    val left = track.left * scaleX
                    val right = track.right * scaleX
                    
                    val marks = state.manualPoints[state.currentTrackIndex].orEmpty()
                    marks.forEach { mark ->
                        val y = if (draggedPointId == mark.id && draggedY != null) {
                            draggedY!! * scaleY
                        } else {
                            mark.point.y * scaleY
                        }
                        
                        // 只在高亮区域内绘制标定线
                        drawLine(
                            color = Color(0xFFFFC107),
                            start = Offset(left, y),
                            end = Offset(right, y),
                            strokeWidth = 3f
                        )
                        
                        // 绘制波长文本
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply {
                                color = Color.White.toArgb()
                                textSize = 32f
                                isAntiAlias = true
                            }
                            canvas.nativeCanvas.drawText(
                                "${mark.wavelength} nm",
                                left + 12f,
                                y - 8f,
                                paint
                            )
                        }
                    }
                }
            }
        }

        // 底部控制区域 - 优化后的现代化设计
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFF8F9FA),
                            Color.White
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    clip = false
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 通道信息卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF5D6B98).copy(alpha = 0.1f),
                                Color(0xFF8B9DC3).copy(alpha = 0.05f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧通道标签
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF5D6B98)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Column {
                        Text(
                            text = stringResource(R.string.current_track_label, state.currentTrackIndex + 1),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF2D3142)
                        )
                        Text(
                            text = "${state.manualPoints[state.currentTrackIndex]?.size ?: 0} 个标定点",
                            fontSize = 12.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }

                // 右侧导航按钮
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Prev 按钮
                    IconButton(
                        onClick = { onTrackChange((state.currentTrackIndex - 1).coerceAtLeast(0)) },
                        enabled = state.currentTrackIndex > 0,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (state.currentTrackIndex > 0) Color(0xFF5D6B98)
                                else Color(0xFFE5E7EB)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Prev",
                            tint = if (state.currentTrackIndex > 0) Color.White else Color(0xFF9CA3AF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    // Next 按钮
                    IconButton(
                        onClick = { onTrackChange((state.currentTrackIndex + 1).coerceAtMost(state.trackRects.lastIndex)) },
                        enabled = state.currentTrackIndex < state.trackRects.lastIndex,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (state.currentTrackIndex < state.trackRects.lastIndex) Color(0xFF5D6B98)
                                else Color(0xFFE5E7EB)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Next",
                            tint = if (state.currentTrackIndex < state.trackRects.lastIndex) Color.White else Color(0xFF9CA3AF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // 操作按钮和提示区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 一键应用按钮
                val magicEnabled = (state.manualPoints[0]?.size ?: 0) >= 2 && state.coefficients.containsKey(0)
                Button(
                    onClick = onMagicApply,
                    enabled = magicEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5D6B98),
                        disabledContainerColor = Color(0xFFE5E7EB)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp,
                        disabledElevation = 0.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.magic_apply),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 提示信息卡片
                Row(
                    modifier = Modifier
                        .weight(1.2f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF0F4F8))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color(0xFF5D6B98),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.tap_to_add_point),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (pendingPoint != null) {
        AlertDialog(
            onDismissRequest = { pendingPoint = null },
            title = { Text(stringResource(R.string.input_wavelength)) },
            text = {
                OutlinedTextField(
                    value = wavelengthInput,
                    onValueChange = { wavelengthInput = it },
                    label = { Text(stringResource(R.string.reference_wavelength_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = wavelengthInput.toFloatOrNull()
                        val point = pendingPoint
                        if (value != null && point != null) {
                            onAddPoint(state.currentTrackIndex, point, value)
                            pendingPoint = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPoint = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
