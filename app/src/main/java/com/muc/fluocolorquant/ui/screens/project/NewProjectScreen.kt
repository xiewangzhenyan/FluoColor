@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)

package com.muc.fluocolorquant.ui.screens.project

import android.content.Context
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
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
import com.muc.fluocolorquant.ui.navigation.Screen
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType

// 检测模式枚举
enum class DetectionMode {
    FLUORESCENCE, COLORIMETRIC
}

// 识别类型枚举
enum class RecognitionType {
    AUTO, MANUAL
}

@Composable
fun NewProjectScreen(
    navController: NavController,
    projectViewModel: ProjectViewModel = hiltViewModel(),
    userViewModel: UserViewModel = hiltViewModel(),
    concentrationViewModel: ConcentrationViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastManager = LocalToastManager.current

    // 使用当前用户信息
    val currentUser by userViewModel.currentUser.collectAsState()

    // 获取浓度预测状态
    val concentrationState by concentrationViewModel.concentrationState.collectAsState()
    
    // 刷新设置，确保获取最新的设置值
    LaunchedEffect(Unit) {
        settingsViewModel.refreshSettings()
    }
    
    // 获取默认浓度单位和可用单位列表
    val defaultDetectionMode by settingsViewModel.defaultDetectionMode.collectAsState()
    val defaultConcentrationUnit by settingsViewModel.defaultConcentrationUnit.collectAsState()
    val availableConcentrationUnits by settingsViewModel.concentrationUnits.collectAsState()
    val defaultRows by settingsViewModel.defaultRows.collectAsState()
    val defaultColumns by settingsViewModel.defaultColumns.collectAsState()
    
    // 提前获取所有需要在非Composable上下文中使用的字符串资源
    val tempFileCreationErrorMessage = stringResource(R.string.temp_file_creation_error)
    val cameraPermissionRequiredMessage = stringResource(R.string.camera_permission_required)
    val enterProjectNameMessage = stringResource(R.string.enter_project_name)
    val selectImageMessage = stringResource(R.string.select_image)
    val projectCreationSuccessMessage = stringResource(R.string.project_creation_success)
    val analyzingImageMessage = stringResource(R.string.analyzing_image)
    val projectCreationErrorMessage = stringResource(R.string.project_creation_error)

    // 状态管理 - 使用rememberSaveable而不是remember
    var projectName by rememberSaveable { mutableStateOf("") }
    
    // 根据默认设置初始化检测模式
    var detectionMode by rememberSaveable(defaultDetectionMode) { 
        mutableStateOf(
            when (defaultDetectionMode) {
                "FLUORESCENCE" -> DetectionMode.FLUORESCENCE
                "COLORIMETRIC" -> DetectionMode.COLORIMETRIC
                else -> DetectionMode.FLUORESCENCE
            }
        ) 
    }
    
    var recognitionType by rememberSaveable { mutableStateOf(RecognitionType.AUTO) }
    var projectImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var maxConcentration by rememberSaveable { mutableStateOf("") }
    
    // 根据默认设置初始化浓度单位
    var concentrationUnit by rememberSaveable(defaultConcentrationUnit) { 
        mutableStateOf(defaultConcentrationUnit) 
    }
    
    // 根据默认设置初始化行列
    var rows by rememberSaveable(defaultRows) { 
        mutableStateOf(defaultRows) 
    }
    var columns by rememberSaveable(defaultColumns) { 
        mutableStateOf(defaultColumns) 
    }
    
    // 添加图像矫正选项
    var enableImageCorrection by rememberSaveable { mutableStateOf(false) }
    
    var showImagePickerDialog by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isRecognitionTypeMenuExpanded by remember { mutableStateOf(false) }
    var isConcentrationUnitMenuExpanded by remember { mutableStateOf(false) }

    // 当默认检测模式变化时，更新当前检测模式
    LaunchedEffect(defaultDetectionMode) {
        detectionMode = when (defaultDetectionMode) {
            "FLUORESCENCE" -> DetectionMode.FLUORESCENCE
            "COLORIMETRIC" -> DetectionMode.COLORIMETRIC
            else -> DetectionMode.FLUORESCENCE
        }
    }
    
    // 当默认浓度单位变化时，更新当前浓度单位
    LaunchedEffect(defaultConcentrationUnit) {
        concentrationUnit = defaultConcentrationUnit
    }

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
                // 获取当前运行ID（如果有）
                val runId = concentrationViewModel.getCurrentRunId()
                // 导航到结果页面
                if (runId != null) {
                    navController.navigate(Screen.Result.createRoute(runId)) {
                        // 可选: 设置导航选项，例如弹出当前页面
                        popUpTo(Screen.NewProject.route) { inclusive = true }
                    }
                } else {
                    // 如果没有runId，回退到使用projectId（较少情况）
                    toastManager.showToast("未获取到运行ID，可能影响数据显示", ToastType.WARNING)
                    navController.popBackStack()
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
            toastManager.showToast(tempFileCreationErrorMessage, ToastType.ERROR)
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
                } ?: toastManager.showToast(tempFileCreationErrorMessage, ToastType.ERROR)
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
                // 如果是刚刚授予的权限，自动启动相机
                tempImageUri.value = createTempImageUri()
                tempImageUri.value?.let { uri ->
                    cameraLauncher.launch(uri)
                } ?: toastManager.showToast(tempFileCreationErrorMessage, ToastType.ERROR)
            }
            is PermissionStatus.Denied -> {
                // 权限被拒绝，显示提示
                if ((cameraPermissionState.status as PermissionStatus.Denied).shouldShowRationale) {
                    toastManager.showToast(cameraPermissionRequiredMessage, ToastType.WARNING)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.new_project_title),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
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
                label = { Text(stringResource(R.string.project_name)) },
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
                text = stringResource(R.string.detection_mode),
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
                        text = stringResource(R.string.fluorescence_mode),
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
                        text = stringResource(R.string.colorimetric_mode),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 识别类型下拉菜单
            Text(
                text = stringResource(R.string.recognition_type),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )

            ExposedDropdownMenuBox(
                expanded = isRecognitionTypeMenuExpanded,
                onExpandedChange = { isRecognitionTypeMenuExpanded = !isRecognitionTypeMenuExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                OutlinedTextField(
                    value = when (recognitionType) {
                        RecognitionType.AUTO -> stringResource(R.string.auto_recognition_option)
                        RecognitionType.MANUAL -> stringResource(R.string.manual_crop_option)
                    },
                    onValueChange = { /* No action needed for readOnly field */ },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isRecognitionTypeMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor() // Important for ExposedDropdownMenuBox
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF5D6B98),
                        unfocusedBorderColor = Color(0xFFDDDDDD),
                        focusedTrailingIconColor = Color(0xFF5D6B98),
                        unfocusedTrailingIconColor = Color(0xFF5D6B98),
                        disabledTextColor = LocalContentColor.current,
                        disabledBorderColor = Color(0xFFDDDDDD),
                        disabledTrailingIconColor = Color(0xFF5D6B98)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                ExposedDropdownMenu(
                    expanded = isRecognitionTypeMenuExpanded,
                    onDismissRequest = { isRecognitionTypeMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f) // Keep original width factor
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.auto_recognition_option)) },
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
                        text = { Text(stringResource(R.string.manual_crop_option)) },
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

            // 添加图像矫正选项
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.image_correction),
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
                // 启用图像矫正
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { enableImageCorrection = true }
                ) {
                    RadioButton(
                        selected = enableImageCorrection,
                        onClick = { enableImageCorrection = true },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF5D6B98)
                        )
                    )
                    Text(
                        text = stringResource(R.string.yes),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                // 禁用图像矫正
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { enableImageCorrection = false }
                ) {
                    RadioButton(
                        selected = !enableImageCorrection,
                        onClick = { enableImageCorrection = false },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF5D6B98)
                        )
                    )
                    Text(
                        text = stringResource(R.string.no),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            
            // 在项目图片部分之后添加图像矫正描述
            if (enableImageCorrection) {
                Text(
                    text = stringResource(R.string.image_correction_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 项目图片
            Text(
                text = stringResource(R.string.project_image),
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
                        contentDescription = stringResource(R.string.project_image),
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
                            contentDescription = stringResource(R.string.delete),
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
                            contentDescription = stringResource(R.string.project_image),
                            tint = Color(0xFF5D6B98),
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.upload_project_image),
                            color = Color(0xFF666666),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 孔阵行列设置 - 移到项目图片之后
            Text(
                text = stringResource(R.string.row_column_settings),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            
            // 行列输入框放在同一行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 行数输入框
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.rows),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    // 使用与SettingsScreen相同的逻辑
                    var rowsText by remember(rows) { mutableStateOf(rows.toString()) }
                    var rowInputError by remember { mutableStateOf(false) }
                    
                    OutlinedTextField(
                        value = rowsText,
                        onValueChange = { value ->
                            // 仅接受数字输入
                            if (value.isEmpty()) {
                                rowsText = value
                                rowInputError = false
                            } else if (value.matches(Regex("^[0-9]+$"))) {
                                val numValue = value.toInt()
                                if (numValue in 1..8) {
                                    rowsText = value
                                    rows = numValue
                                    rowInputError = false
                                } else {
                                    rowInputError = true
                                    toastManager.showToast(context.getString(R.string.row_limit_exceeded), ToastType.WARNING)
                                }
                            } else {
                                // 非数字输入，不更新值，显示错误
                                rowInputError = true
                                toastManager.showToast(context.getString(R.string.input_number_only), ToastType.ERROR)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.GridView,
                                contentDescription = null,
                                tint = Color(0xFF5D6B98)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = if (rowInputError) Color.Red else Color(0xFF5D6B98),
                            unfocusedBorderColor = if (rowInputError) Color.Red else Color(0xFFDDDDDD),
                            errorBorderColor = Color.Red,
                            errorTrailingIconColor = Color.Red
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        isError = rowInputError
                    )
                }
                
                // 列数输入框
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.columns),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF666666),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    
                    // 使用与SettingsScreen相同的逻辑
                    var columnsText by remember(columns) { mutableStateOf(columns.toString()) }
                    var columnInputError by remember { mutableStateOf(false) }
                    
                    OutlinedTextField(
                        value = columnsText,
                        onValueChange = { value ->
                            // 仅接受数字输入
                            if (value.isEmpty()) {
                                columnsText = value
                                columnInputError = false
                            } else if (value.matches(Regex("^[0-9]+$"))) {
                                val numValue = value.toInt()
                                if (numValue in 1..12) {
                                    columnsText = value
                                    columns = numValue
                                    columnInputError = false
                                } else {
                                    columnInputError = true
                                    toastManager.showToast(context.getString(R.string.column_limit_exceeded), ToastType.WARNING)
                                }
                            } else {
                                // 非数字输入，不更新值，显示错误
                                columnInputError = true
                                toastManager.showToast(context.getString(R.string.input_number_only), ToastType.ERROR)
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.GridView,
                                contentDescription = null,
                                tint = Color(0xFF5D6B98)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = if (columnInputError) Color.Red else Color(0xFF5D6B98),
                            unfocusedBorderColor = if (columnInputError) Color.Red else Color(0xFFDDDDDD),
                            errorBorderColor = Color.Red,
                            errorTrailingIconColor = Color.Red
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        isError = columnInputError
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 最大浓度标题和输入框
            Text(
                text = stringResource(R.string.max_concentration),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )
            
            // 最大浓度输入框
            OutlinedTextField(
                value = maxConcentration,
                onValueChange = { 
                    // 仅允许数字输入
                    if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) {
                        maxConcentration = it
                    }
                },
                label = { Text(stringResource(R.string.max_concentration)) },
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

            Spacer(modifier = Modifier.height(16.dp))

            // 浓度单位选择
            Text(
                text = stringResource(R.string.concentration_unit),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontWeight = FontWeight.Medium,
                color = Color(0xFF333333)
            )

            ExposedDropdownMenuBox(
                expanded = isConcentrationUnitMenuExpanded,
                onExpandedChange = { isConcentrationUnitMenuExpanded = !isConcentrationUnitMenuExpanded },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                OutlinedTextField(
                    value = concentrationUnit,
                    onValueChange = { /* No action needed for readOnly field */ },
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isConcentrationUnitMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor() // Important for ExposedDropdownMenuBox
                        .fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF5D6B98),
                        unfocusedBorderColor = Color(0xFFDDDDDD),
                        focusedTrailingIconColor = Color(0xFF5D6B98),
                        unfocusedTrailingIconColor = Color(0xFF5D6B98),
                        disabledTextColor = LocalContentColor.current,
                        disabledBorderColor = Color(0xFFDDDDDD),
                        disabledTrailingIconColor = Color(0xFF5D6B98)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                ExposedDropdownMenu(
                    expanded = isConcentrationUnitMenuExpanded,
                    onDismissRequest = { isConcentrationUnitMenuExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f) // Keep original width factor
                ) {
                    availableConcentrationUnits.toList().sorted().forEach { unit ->
                        DropdownMenuItem(
                            text = { Text(unit) },
                            onClick = {
                                concentrationUnit = unit
                                isConcentrationUnitMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 提交按钮
            Button(
                onClick = {
                    if (projectName.isBlank()) {
                        toastManager.showToast(enterProjectNameMessage, ToastType.WARNING)
                        return@Button
                    }

                    if (projectImageUri == null) {
                        toastManager.showToast(selectImageMessage, ToastType.WARNING)
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
                                concentrationUnit = concentrationUnit,
                                userId = currentUser?.id.toString(), // 使用当前用户ID
                                rows = rows,
                                columns = columns
                            )

                            if (newProjectId != null) {
                                toastManager.showToast(projectCreationSuccessMessage, ToastType.SUCCESS)
                                
                                // 根据是否启用图像矫正和识别类型决定导航
                                if (enableImageCorrection) {
                                    // 导航到图像矫正页面 - 使用 createRoute 方法
                                    navController.navigate(
                                        Screen.ImageCorrection.createRoute(Uri.encode(projectImageUri.toString()), newProjectId)
                                    ) {
                                        popUpTo(Screen.NewProject.route) { inclusive = true }
                                    }
                                } else {
                                    // 根据识别类型决定导航
                                    if (recognitionType == RecognitionType.AUTO) {
                                        // 自动识别 - 导航到孔阵检测页面，使用 createRoute 方法
                                        navController.navigate(
                                            Screen.WellDetection.createRoute(Uri.encode(projectImageUri.toString()), newProjectId)
                                        ) {
                                            // 可选: 设置导航选项，例如弹出当前页面
                                            popUpTo(Screen.NewProject.route) { inclusive = true }
                                        }
                                    } else {
                                        // 手动裁剪 - 立即分析裁剪图像
                                        android.util.Log.d("NewProjectScreen", "开始分析手动裁剪图像: $newProjectId, ${projectImageUri.toString()}")
                                        // 设置为加载状态
                                        toastManager.showToast(analyzingImageMessage, ToastType.INFO)
                                        // 调用浓度预测
                                        concentrationViewModel.analyzeManualCroppedImage(
                                            projectId = newProjectId,
                                            croppedImageUri = projectImageUri!!
                                        )
                                        // 不立即返回，等浓度预测完成后通过LaunchedEffect中的监听跳转
                                    }
                                }
                            } else {
                                toastManager.showToast(projectCreationErrorMessage, ToastType.ERROR)
                                isSubmitting = false
                            }
                        } catch (e: Exception) {
                            toastManager.showToast(context.getString(R.string.project_creation_error, e.message ?: ""), ToastType.ERROR)
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
                    Text(stringResource(R.string.create_project))
                }
            }
        }

        // 图片选择对话框
        if (showImagePickerDialog) {
            AlertDialog(
                onDismissRequest = { showImagePickerDialog = false },
                title = {
                    Text(
                        stringResource(R.string.select_image_title),
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
                                    text = stringResource(R.string.select_from_gallery),
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
                                    text = stringResource(R.string.take_photo),
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
                        Text(stringResource(R.string.cancel))
                    }
                },
                confirmButton = {}
            )
        }
    }
} 