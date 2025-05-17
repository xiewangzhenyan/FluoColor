package com.muc.fluocolorquant.ui.screens.project

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.viewmodels.ProjectViewModel
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus

// 检测模式枚举
enum class DetectionMode {
    FLUORESCENCE, COLORIMETRIC
}

// 识别类型枚举
enum class RecognitionType {
    AUTO, MANUAL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun NewProjectScreen(
    navController: NavController,
    projectViewModel: ProjectViewModel = hiltViewModel(),
    userViewModel: UserViewModel = hiltViewModel(),
    concentrationViewModel: ConcentrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 使用当前用户信息
    val currentUser by userViewModel.currentUser.collectAsState()
    
    // 获取浓度预测状态
    val concentrationState by concentrationViewModel.concentrationState.collectAsState()
    
    // 状态管理 - 使用rememberSaveable而不是remember
    var projectName by rememberSaveable { mutableStateOf("") }
    var detectionMode by rememberSaveable { mutableStateOf(DetectionMode.FLUORESCENCE) }
    var recognitionType by rememberSaveable { mutableStateOf(RecognitionType.AUTO) }
    var projectImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var maxConcentration by rememberSaveable { mutableStateOf("") }
    var showImagePickerDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isRecognitionTypeMenuExpanded by remember { mutableStateOf(false) }
    
    // 检查裁剪后的图片URI
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.get<String>("croppedImageUri")?.let { uri ->
            projectImageUri = Uri.parse(uri)
            // 清除保存的状态，防止重复处理
            savedStateHandle.remove<String>("croppedImageUri")
        }
    }
    
    // 监听浓度预测状态变化，完成后导航到结果页面
    LaunchedEffect(concentrationState) {
        if (concentrationState is ConcentrationViewModel.ConcentrationState.Success) {
            // 获取当前项目ID
            val projectId = concentrationViewModel.currentProjectId.value
            if (projectId != null) {
                // 导航到结果页面
                navController.navigate("${Screen.Result.route}?projectId=$projectId") {
                    // 可选: 设置导航选项，例如弹出当前页面
                    popUpTo(Screen.NewProject.route) { inclusive = true }
                }
            }
        }
    }
    
    // 图片选择器
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 导航到裁剪页面
            navController.navigate("${Screen.ImageCrop.route}?imageUri=${Uri.encode(uri.toString())}")
        }
    }
    
    // 相机启动器
    val tempImageUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempImageUri.value != null) {
            // 导航到裁剪页面
            navController.navigate("${Screen.ImageCrop.route}?imageUri=${Uri.encode(tempImageUri.value.toString())}")
        }
    }
    
    // 相机权限状态
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // 创建临时文件和URI的函数
    val createTempImageUri: () -> Uri? = {
        try {
            // 创建临时文件
            val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val imageFileName = "JPEG_${timeStamp}_"
            val storageDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            val tempFile = java.io.File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
            )
            
            // 使用FileProvider获取内容URI
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                tempFile
            )
        } catch (e: Exception) {
            android.util.Log.e("NewProjectScreen", "Error creating temp image uri", e)
            Toast.makeText(context, "无法创建临时图像文件", Toast.LENGTH_SHORT).show()
            null
        }
    }
    
    // 打开相机前检查权限的函数
    val checkCameraPermissionAndLaunch: () -> Unit = {
        when {
            // 已有权限，直接启动相机
            cameraPermissionState.status.isGranted -> {
                tempImageUri.value = createTempImageUri()
                tempImageUri.value?.let { uri ->
                    cameraLauncher.launch(uri)
                } ?: Toast.makeText(context, "无法创建临时图像文件", Toast.LENGTH_SHORT).show()
            }
            // 请求相机权限
            else -> {
                cameraPermissionState.launchPermissionRequest()
            }
        }
    }
    
    // 权限结果监听
    LaunchedEffect(cameraPermissionState.status) {
        when (cameraPermissionState.status) {
            is PermissionStatus.Granted -> {
                // 如果是刚刚授予的权限，不需要自动启动相机
                // 用户需要再次点击拍照按钮
            }
            is PermissionStatus.Denied -> {
                // 权限被拒绝，显示提示
                if ((cameraPermissionState.status as PermissionStatus.Denied).shouldShowRationale) {
                    Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "新建项目",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        },
        containerColor = Color(0xFFF5F5F5)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 项目名称输入框
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it },
                label = { Text("项目名称") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color(0xFF5D6B98)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFF5D6B98),
                    unfocusedBorderColor = Color(0xFFDDDDDD)
                ),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 检测模式选择
            Text(
                text = "检测模式",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 荧光检测
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { detectionMode = DetectionMode.FLUORESCENCE }
                ) {
                    RadioButton(
                        selected = detectionMode == DetectionMode.FLUORESCENCE,
                        onClick = { detectionMode = DetectionMode.FLUORESCENCE },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF5D6B98)
                        )
                    )
                    Text(
                        text = "荧光检测",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                
                // 比色检测
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { detectionMode = DetectionMode.COLORIMETRIC }
                ) {
                    RadioButton(
                        selected = detectionMode == DetectionMode.COLORIMETRIC,
                        onClick = { detectionMode = DetectionMode.COLORIMETRIC },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF5D6B98)
                        )
                    )
                    Text(
                        text = "比色检测",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 识别类型下拉菜单
            Text(
                text = "识别类型",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                OutlinedTextField(
                    value = when (recognitionType) {
                        RecognitionType.AUTO -> "自动识别"
                        RecognitionType.MANUAL -> "手动裁剪"
                    },
                    onValueChange = { },
                    readOnly = true,
                    trailingIcon = {
                        IconButton(onClick = { isRecognitionTypeMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "展开",
                                tint = Color(0xFF5D6B98)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isRecognitionTypeMenuExpanded = true },
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = Color(0xFF5D6B98),
                        unfocusedBorderColor = Color(0xFFDDDDDD)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                
                DropdownMenu(
                    expanded = isRecognitionTypeMenuExpanded,
                    onDismissRequest = { isRecognitionTypeMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    DropdownMenuItem(
                        text = { Text("自动识别") },
                        onClick = { 
                            recognitionType = RecognitionType.AUTO
                            isRecognitionTypeMenuExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF5D6B98)
                            )
                        }
                    )
                    
                    DropdownMenuItem(
                        text = { Text("手动裁剪") },
                        onClick = { 
                            recognitionType = RecognitionType.MANUAL
                            isRecognitionTypeMenuExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentCut,
                                contentDescription = null,
                                tint = Color(0xFF5D6B98)
                            )
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 项目图片
            Text(
                text = "项目图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF0F0F0))
                    .border(
                        width = 1.dp,
                        color = Color(0xFFDDDDDD),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { showImagePickerDialog = true },
                contentAlignment = Alignment.Center
            ) {
                if (projectImageUri != null) {
                    // 显示选择的图片
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(projectImageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "项目图片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    
                    // 添加删除按钮
                    IconButton(
                        onClick = { projectImageUri = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "删除图片",
                            tint = Color.White
                        )
                    }
                } else {
                    // 显示上传图标
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "上传图片",
                            tint = Color(0xFF5D6B98),
                            modifier = Modifier.size(48.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "点击上传项目图片",
                            color = Color(0xFF666666),
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 最大浓度输入框
            OutlinedTextField(
                value = maxConcentration,
                onValueChange = { 
                    // 仅允许数字输入
                    if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) {
                        maxConcentration = it 
                    }
                },
                label = { Text("最大浓度 (ng/ml)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = null,
                        tint = Color(0xFF5D6B98)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFF5D6B98),
                    unfocusedBorderColor = Color(0xFFDDDDDD)
                ),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = KeyboardType.Number
                ),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 提交按钮
            Button(
                onClick = {
                    if (projectName.isBlank()) {
                        Toast.makeText(context, "请输入项目名称", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    if (projectImageUri == null) {
                        Toast.makeText(context, "请上传项目图片", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    
                    isSubmitting = true
                    
                    // 解析最大浓度，如果为空则使用默认值
                    val maxConc = if (maxConcentration.isBlank()) null else maxConcentration.toDoubleOrNull()
                    
                    // 创建项目 - 使用当前用户ID
                    scope.launch {
                        try {
                            val newProjectId = projectViewModel.createProject(
                                name = projectName,
                                detectionMode = detectionMode,
                                recognitionType = recognitionType,
                                imageUri = projectImageUri.toString(),
                                maxConcentration = maxConc,
                                userId = currentUser?.id.toString() // 使用当前用户ID
                            )
                            
                            if (newProjectId != null) {
                                Toast.makeText(context, "项目创建成功", Toast.LENGTH_SHORT).show()
                                // 根据识别类型决定导航
                                if (recognitionType == RecognitionType.AUTO) {
                                    // 自动识别 - 导航到孔阵检测页面
                                    navController.navigate(
                                        "${Screen.WellDetection.route}?imageUri=${Uri.encode(projectImageUri.toString())}&projectId=$newProjectId"
                                    ) {
                                        // 可选: 设置导航选项，例如弹出当前页面
                                        popUpTo(Screen.NewProject.route) { inclusive = true }
                                    }
                                } else {
                                    // 手动裁剪 - 立即分析裁剪图像
                                    android.util.Log.d("NewProjectScreen", "开始分析手动裁剪图像: $newProjectId, ${projectImageUri.toString()}")
                                    // 设置为加载状态
                                    Toast.makeText(context, "开始分析图像...", Toast.LENGTH_SHORT).show()
                                    // 调用浓度预测
                                    concentrationViewModel.analyzeManualCroppedImage(
                                        projectId = newProjectId,
                                        croppedImageUri = projectImageUri!!
                                    )
                                    // 不立即返回，等浓度预测完成后通过LaunchedEffect中的监听跳转
                                }
                            } else {
                                Toast.makeText(context, "项目创建失败", Toast.LENGTH_SHORT).show()
                                isSubmitting = false
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "发生错误: ${e.message}", Toast.LENGTH_SHORT).show()
                            isSubmitting = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5D6B98)
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("创建项目")
                }
            }
        }
        
        // 图片选择对话框
        if (showImagePickerDialog) {
            AlertDialog(
                onDismissRequest = { showImagePickerDialog = false },
                title = { 
                    Text(
                        "选择图片",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) 
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.White,
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // 从相册选择
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    galleryLauncher.launch("image/*")
                                    showImagePickerDialog = false
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF5D6B98).copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Photo,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                        tint = Color(0xFF5D6B98)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "从相册选择",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF5D6B98)
                                )
                            }
                            
                            // 拍照
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    // 启动相机
                                    showImagePickerDialog = false
                                    
                                    // 先检查相机权限
                                    checkCameraPermissionAndLaunch()
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF5D6B98).copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                        tint = Color(0xFF5D6B98)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "拍照",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF5D6B98)
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showImagePickerDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5D6B98)
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("取消")
                    }
                },
                confirmButton = {}
            )
        }
    }
} 