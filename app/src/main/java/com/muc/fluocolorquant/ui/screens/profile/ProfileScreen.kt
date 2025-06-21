package com.muc.fluocolorquant.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import android.widget.Toast
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.launch
import com.muc.fluocolorquant.ui.navigation.Screen
import coil.request.ImageRequest
import coil.size.Size
import com.muc.fluocolorquant.R
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    userViewModel: UserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUser by userViewModel.currentUser.collectAsState()
    
    // 图片处理相关变量
    val tempImageUri = remember { mutableStateOf<Uri?>(null) }
    
    // 相机启动器
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempImageUri.value != null) {
            // 导航到裁剪页面
            navController.navigate("${Screen.ImageCrop.route}?imageUri=${Uri.encode(tempImageUri.value.toString())}")
        } else if (!success) {
            Toast.makeText(context, "拍照已取消", Toast.LENGTH_SHORT).show()
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
            android.util.Log.e("ProfileScreen", "Error creating temp image uri", e)
            Toast.makeText(context, "无法创建临时图像文件", Toast.LENGTH_SHORT).show()
            null
        }
    }
    
    // 相机权限状态
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
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
    
    // 确保在组件初始化时获取用户数据
    LaunchedEffect(Unit) {
        // 如果需要，可以在这里添加刷新用户数据的逻辑
    }
    
    // 状态管理 - 确保使用当前用户数据初始化
    var username by remember { mutableStateOf(currentUser?.username ?: "") }
    var email by remember { mutableStateOf(currentUser?.email ?: "") }
    var profilePicUrl by remember { mutableStateOf(currentUser?.profilePicUrl) }
    
    // 检测到用户数据变化时更新状态
    LaunchedEffect(currentUser) {
        currentUser?.let {
            username = it.username
            email = it.email ?: ""
            profilePicUrl = it.profilePicUrl
        }
    }
    
    // 监听从裁剪页面返回的结果
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.get<String>("croppedImageUri")?.let { uri ->
            // 更新头像URL
            profilePicUrl = uri
            // 更新用户数据
            currentUser?.let { user ->
                scope.launch {
                    userViewModel.updateUserProfile(
                        userId = user.id,
                        username = username,
                        email = email,
                        profilePicUrl = uri
                    )
                    // 清除savedStateHandle中的数据，防止重复处理
                    savedStateHandle.remove<String>("croppedImageUri")
                    Toast.makeText(context, "头像已更新", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    var showImagePickerDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    
    // 检测字段是否被修改
    val isModified = remember(username, email, currentUser) {
        currentUser != null && (username != currentUser?.username || email != currentUser?.email)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "个人信息",
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
        containerColor = Color(0xFFF5F5F5) // 浅灰色背景
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 用户头像
            Box(
                modifier = Modifier
                    .padding(vertical = 24.dp)
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEEEEEE))
                    .clickable { showImagePickerDialog = true }
            ) {
                // 使用Coil加载头像
                if (profilePicUrl != null) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(context)
                                .data(Uri.parse(profilePicUrl))
                                .size(Size.ORIGINAL)
                                .placeholder(R.drawable.placeholder_image)
                                .error(R.drawable.placeholder_image)
                                .build()
                        ),
                        contentDescription = "用户头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "添加头像",
                        tint = Color(0xFF5D6B98),
                        modifier = Modifier
                            .size(40.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            // 用户信息表单
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Color(0xFF5D6B98)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFF5D6B98),
                    unfocusedBorderColor = Color(0xFFDDDDDD)
                ),
                shape = RoundedCornerShape(8.dp)
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = Color(0xFF5D6B98)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = Color(0xFF5D6B98),
                    unfocusedBorderColor = Color(0xFFDDDDDD)
                ),
                shape = RoundedCornerShape(8.dp)
            )

            // 保存修改按钮
            Button(
                onClick = {
                    scope.launch {
                        userViewModel.updateUserProfile(username, email)
                        Toast.makeText(context, "保存成功", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5D6B98)
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = isModified
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("保存修改")
            }

            // 修改密码按钮
            Button(
                onClick = { showPasswordDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4E5C82)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("修改密码")
            }
        }

        // 头像选择对话框
        if (showImagePickerDialog) {
            AlertDialog(
                onDismissRequest = { showImagePickerDialog = false },
                title = { 
                    Text(
                        "选择头像",
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
                                    // 启动相机并导航到裁剪页面
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

        // 修改密码对话框 - 美化版本
        if (showPasswordDialog) {
            var oldPassword by remember { mutableStateOf("") }
            var newPassword by remember { mutableStateOf("") }
            var confirmPassword by remember { mutableStateOf("") }
            var passwordError by remember { mutableStateOf<String?>(null) }
            var isPasswordVisible by remember { mutableStateOf(false) }
            var isSubmitting by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { 
                    Text(
                        "修改密码",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF5D6B98)
                    ) 
                },
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.White,
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        // 错误信息显示
                        if (passwordError != null) {
                            Text(
                                text = passwordError!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        
                        // 当前密码
                        OutlinedTextField(
                            value = oldPassword,
                            onValueChange = { 
                                oldPassword = it
                                passwordError = null 
                            },
                            label = { Text("当前密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None 
                                                 else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff
                                                   else Icons.Default.Visibility,
                                        contentDescription = "切换密码可见性"
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF5D6B98),
                                focusedLabelColor = Color(0xFF5D6B98)
                            )
                        )
                        
                        // 新密码
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = { 
                                newPassword = it
                                passwordError = null
                            },
                            label = { Text("新密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None 
                                                 else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff
                                                   else Icons.Default.Visibility,
                                        contentDescription = "切换密码可见性"
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF5D6B98),
                                focusedLabelColor = Color(0xFF5D6B98)
                            )
                        )
                        
                        // 确认新密码
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { 
                                confirmPassword = it 
                                passwordError = null
                            },
                            label = { Text("确认新密码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None 
                                                 else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff
                                                   else Icons.Default.Visibility,
                                        contentDescription = "切换密码可见性"
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF5D6B98),
                                focusedLabelColor = Color(0xFF5D6B98)
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                isSubmitting = true
                                passwordError = null
                                
                                // 验证输入
                                when {
                                    oldPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty() -> {
                                        passwordError = "所有字段都必须填写"
                                        isSubmitting = false
                                    }
                                    newPassword != confirmPassword -> {
                                        passwordError = "新密码与确认密码不匹配"
                                        isSubmitting = false
                                    }
                                    newPassword.length < 6 -> {
                                        passwordError = "新密码长度必须至少为6个字符"
                                        isSubmitting = false
                                    }
                                    else -> {
                                        // 尝试更新密码
                                        currentUser?.let { user ->
                                            try {
                                                val result = userViewModel.changePassword(
                                                    userId = user.id,
                                                    oldPassword = oldPassword,
                                                    newPassword = newPassword
                                                )
                                                
                                                if (result) {
                                                    // 密码修改成功
                                                    Toast.makeText(context, "密码修改成功", Toast.LENGTH_SHORT).show()
                                                    showPasswordDialog = false
                                                } else {
                                                    // 密码验证失败
                                                    passwordError = "当前密码不正确"
                                                }
                                            } catch (e: Exception) {
                                                passwordError = "密码修改失败: ${e.message}"
                                            }
                                        }
                                        isSubmitting = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
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
                            Text("确认修改")
                        }
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showPasswordDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF5D6B98)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = SolidColor(Color(0xFF5D6B98))
                        )
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
} 