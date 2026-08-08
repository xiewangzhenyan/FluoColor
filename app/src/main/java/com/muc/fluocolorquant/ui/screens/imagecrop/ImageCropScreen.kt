package com.muc.fluocolorquant.ui.screens.imagecrop

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.viewmodels.ConcentrationViewModel
import com.muc.fluocolorquant.ui.navigation.Screen
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import com.yalantis.ucrop.model.AspectRatio // 导入 AspectRatio
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCropScreen(
    navController: NavController,
    imageUri: String? = null,
    viewModel: ConcentrationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // uCrop 的配置在 LaunchedEffect 里构造，而 stringResource 与 MaterialTheme 只能在
    // Composable 上下文读取，因此先取到局部变量（AGENTS.md 5）。
    val cropTitle = stringResource(R.string.image_crop_title)
    val accentColorArgb = MaterialTheme.colorScheme.primary.toArgb()
    // var isLoading by remember { mutableStateOf(false) } // isLoading 似乎没有在UI中使用，可以考虑移除

    val destinationUri = remember {
        Uri.fromFile(File(context.cacheDir, "cropped_${UUID.randomUUID()}.jpg"))
    }

    val sourceUri = remember(imageUri) {
        if (imageUri != null) Uri.parse(imageUri) else null
    }

    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                navController.previousBackStackEntry?.savedStateHandle?.set(
                    "croppedImageUri",
                    resultUri.toString()
                )

                val previousRoute = navController.previousBackStackEntry?.destination?.route
                if (previousRoute?.contains(Screen.NewProject.route) == true) {
                    navController.popBackStack()
                } else {
                    scope.launch {
                        try {
                            val projectId = navController.previousBackStackEntry?.savedStateHandle?.get<String>("projectId")
                            if (!projectId.isNullOrEmpty()) {
                                viewModel.analyzeManualCroppedImage(projectId, resultUri)
                                val runId = viewModel.getCurrentRunId() // 假设有这个方法
                                if (runId != null) {
                                    navController.navigate(Screen.Result.createRoute(runId)) {
                                        popUpTo(navController.currentBackStackEntry?.destination?.route ?: "") {
                                            inclusive = true
                                        }
                                    }
                                } else {
                                    // 如果没有runId，返回上一级页面并显示警告
                                    android.util.Log.w("ImageCropScreen", "无法获取runId，无法导航到结果页面")
                                    navController.popBackStack()
                                    // 这里可以添加错误处理或Toast提示
                                }
                            } else {
                                navController.popBackStack()
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ImageCropScreen", "分析裁剪图像失败", e)
                            navController.popBackStack()
                        }
                    }
                }
            }  else { // resultUri 为 null 的情况
                android.util.Log.e("ImageCropScreen", "裁剪成功但返回的Uri为null")
                navController.popBackStack()
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            android.util.Log.e("ImageCropScreen", "裁剪发生错误: ${cropError?.message}", cropError) // 打印完整的Error
            navController.popBackStack()
        } else {
            // 用户取消了裁剪或发生其他情况 (result.resultCode != Activity.RESULT_OK)
            android.util.Log.d("ImageCropScreen", "用户取消裁剪或裁剪未成功完成")
            navController.popBackStack()
        }
    }

    LaunchedEffect(sourceUri) {
        sourceUri?.let {
            try {
                val options = UCrop.Options()

                // uCrop 运行在自己的 Activity 里，不受 Compose 主题影响，只能通过 Options
                // 逐项配色。原实现把底部活动控件设成橙色 #FFA500（注释里还写着"示例橙色"），
                // 从科研蓝的应用跳进裁剪页会突然出现一片橙，是全流程最明显的视觉断层。
                // 这里改为跟随应用主色。
                //
                // 工具栏保持黑色不动：裁剪页整屏是照片，深色工具栏与暗化蒙版才能让用户
                // 看清真实图像；此处的黑白与主题无关，属于"浮在照片上的控件"，
                // 与热力图色带同理，不应跟随明暗主题。
                options.setToolbarTitle(cropTitle)
                options.setToolbarColor(android.graphics.Color.BLACK)
                options.setStatusBarColor(android.graphics.Color.BLACK)
                options.setToolbarWidgetColor(android.graphics.Color.WHITE)
                options.setActiveControlsWidgetColor(accentColorArgb)
                options.setCropFrameColor(android.graphics.Color.WHITE)
                options.setCropGridColor(android.graphics.Color.parseColor("#80FFFFFF"))


                options.setAllowedGestures(
                    UCropActivity.SCALE,
                    UCropActivity.ROTATE,
                    UCropActivity.ALL
                )

                options.setShowCropGrid(true)
                options.setShowCropFrame(true)
                options.setCircleDimmedLayer(false)

                options.setFreeStyleCropEnabled(true) // 允许自由调整裁切框，这也会影响宽高比选项的行为

                options.setHideBottomControls(false)

                // 添加宽高比选项 (对应图3的功能)
                // 第一个参数是默认选中的宽高比的索引
                options.setAspectRatioOptions(
                    0, // 默认选中第一个 (例如 "1:1")
                    AspectRatio("1:1", 1f, 1f),
                    AspectRatio("4:3", 4f, 3f),
                    AspectRatio("ORIGINAL", 0f, 0f), // 0,0 表示原始比例
                    AspectRatio("3:2", 3f, 2f),
                    AspectRatio("16:9", 16f, 9f)
                )
                // 注意：如果 setFreeStyleCropEnabled(true)，用户仍然可以自由拖动边框改变宽高比，
                // 宽高比选项更多是作为预设的快速选择。
                // 如果希望严格锁定宽高比，需要将 setFreeStyleCropEnabled 设置为 false，
                // 但这样底部可能就不会显示所有三个图标了，只会显示允许的操作。
                // UCrop的行为是，如果设置了 withAspectRatio() 或者 setFreeStyleCropEnabled(false) 并且提供了宽高比选项，
                // 它会显示宽高比切换的UI。演示图中的效果更像是 setFreeStyleCropEnabled(true) 配合 setAspectRatioOptions。


                options.setMaxScaleMultiplier(15f)
                options.setImageToCropBoundsAnimDuration(300)
                options.setDimmedLayerColor(android.graphics.Color.parseColor("#AA000000")) // 暗色层颜色，可以调整透明度

                val uCrop = UCrop.of(it, destinationUri)
                    // .withAspectRatio(1f, 1f) // <--- 移除这一行以启用多种宽高比选择
                    .withMaxResultSize(1080, 1920) // 根据需要设置最大输出尺寸
                    .withOptions(options)

                cropLauncher.launch(uCrop.getIntent(context))
            } catch (e: Exception) {
                android.util.Log.e("ImageCropScreen", "启动裁剪失败", e)
                navController.popBackStack()
            }
        } ?: run {
            android.util.Log.e("ImageCropScreen", "图片URI为空，无法启动裁剪")
            navController.popBackStack()
        }
    }

    // 保持加载指示器，因为UCrop是启动一个新的Activity
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}