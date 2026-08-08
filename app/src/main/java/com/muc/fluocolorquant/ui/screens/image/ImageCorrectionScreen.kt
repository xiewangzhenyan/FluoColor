package com.muc.fluocolorquant.ui.screens.image

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ImageCorrectionViewModel

/**
 * 图像矫正页面
 * 显示原始图像和矫正后的图像，用户可以点击下一步进入孔阵检测
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCorrectionScreen(
    navController: NavController,
    imageUri: String? = null,
    projectId: String? = null,
    viewModel: ImageCorrectionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    
    // 状态管理
    var isLoading by remember { mutableStateOf(true) }
    var correctedImageUri by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // 提前声明 Toast 消息
    val correctionSuccessMessage = stringResource(R.string.image_correction_success)
    val correctionFailedMessage = stringResource(R.string.image_correction_failed)
    val missingParamsMessage = stringResource(R.string.missing_parameters)
    // 导航失败提示此前在 onClick 里硬编码中文字面量，英文环境下会直接显示中文。
    // stringResource 只能在 Composable 上下文调用，因此先读到局部变量再供回调使用
    // （AGENTS.md 5）。复用首页已有的 navigation_failed，中英文占位符一致。
    val navigationFailedMessage = stringResource(R.string.navigation_failed)
    
    // 加载和处理图像
    LaunchedEffect(imageUri) {
        if (imageUri == null) {
            isLoading = false
            errorMessage = missingParamsMessage
            toastManager.showToast(missingParamsMessage, ToastType.ERROR)
            return@LaunchedEffect
        }
        
        viewModel.correctImageWithErrorHandling(
            imageUri = Uri.parse(imageUri),
            projectId = projectId ?: "",
            onSuccess = { correctedUri ->
                correctedImageUri = correctedUri.toString()
                isLoading = false
                toastManager.showToast(correctionSuccessMessage, ToastType.SUCCESS)
            },
            onError = { error ->
                errorMessage = error
                isLoading = false
                toastManager.showToast("$correctionFailedMessage: $error", ToastType.ERROR)
            }
        )
    }
    
    Scaffold(
        topBar = {
            // 这是整条检测链路上唯一把顶栏染成 primaryContainer 的页面：从新建项目一路
            // 走过来，到这里会突然多出一条蓝色横条，回到下一页又消失。改用统一顶栏后
            // 与前后页面一致，同时修正了此处已废弃的 Icons.Default.ArrowBack（RTL 不镜像）。
            FluoTopBar(
                title = stringResource(R.string.image_correction),
                onBack = { navController.navigateUp() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 主要内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 原始图像部分
                Text(
                    text = stringResource(R.string.original_image),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(FluoRadius.control))
                        // 原实现写死 0xFFF0F0F0 底色与 0xFFDDDDDD 描边，深色模式下这两个
                        // 图片框会变成整屏最亮的两块白斑。改用主题表面色后自动跟随明暗。
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(FluoRadius.control)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = stringResource(R.string.original_image),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.no_image_available),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 矫正后图像部分
                Text(
                    text = stringResource(R.string.corrected_image),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(FluoRadius.control))
                        // 原实现写死 0xFFF0F0F0 底色与 0xFFDDDDDD 描边，深色模式下这两个
                        // 图片框会变成整屏最亮的两块白斑。改用主题表面色后自动跟随明暗。
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(FluoRadius.control)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.processing_image),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else if (correctedImageUri != null) {
                        AsyncImage(
                            model = correctedImageUri,
                            contentDescription = stringResource(R.string.corrected_image),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.no_image_available),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // 下一步按钮
                Button(
                    onClick = {
                        val targetUri = correctedImageUri ?: imageUri
                        if (targetUri != null && projectId != null) {
                            try {
                                // 对URI进行编码，确保特殊字符不会影响路由解析
                                val encodedUri = java.net.URLEncoder.encode(targetUri, "UTF-8")
                                android.util.Log.d("ImageCorrection", "导航到WellDetection: encodedUri=$encodedUri, projectId=$projectId")
                                navController.navigate(
                                    Screen.WellDetection.createRoute(encodedUri, projectId)
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("ImageCorrection", "导航失败", e)
                                toastManager.showToast(
                                    navigationFailedMessage.format(e.message ?: ""),
                                    ToastType.ERROR
                                )
                            }
                        } else {
                            toastManager.showToast(missingParamsMessage, ToastType.ERROR)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(FluoRadius.control),
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.next_step))
                }
            }
        }
    }
}