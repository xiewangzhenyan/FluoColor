package com.muc.fluocolorquant.ui.screens.project

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.ui.components.FluoAnimatedSection
import com.muc.fluocolorquant.ui.components.FluoScreenScaffold
import com.muc.fluocolorquant.ui.components.FluoScrollableContent
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.components.FluoSectionHeader
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ScientificSelectionFieldDensity
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.DirectProjectEvent
import com.muc.fluocolorquant.ui.viewmodels.DirectProjectUiState
import com.muc.fluocolorquant.ui.viewmodels.DirectProjectViewModel
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 普通用户的直接新建项目页面。
 *
 * 交互以 Git 旧版新建页的“连续实验表单”为基线：项目名称、检测方式、实验配置、图片和
 * 主按钮形成一条自然阅读路径；当前微流控规格、真实参考位和仅信号能力继续保留。页面
 * 不暴露模板发布、设备档案、模型校验和或处理器参数等后台概念。
 */
@Composable
fun DirectCreateProjectScreen(
    navController: NavController,
    viewModel: DirectProjectViewModel = hiltViewModel(),
    userViewModel: UserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val state by viewModel.uiState.collectAsState()
    val currentUser by userViewModel.currentUser.collectAsState()
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showExistingImageDialog by rememberSaveable { mutableStateOf(false) }

    // 事件回调中不能调用 stringResource，因此在 Composable 上下文提前读取全部文案。
    val creationSuccess = stringResource(R.string.project_creation_success)
    val formIncomplete = stringResource(R.string.direct_create_form_incomplete)
    val unexpectedFailure = stringResource(R.string.project_unexpected_failure_toast)
    val sessionInvalid = stringResource(R.string.project_session_invalid_toast)
    val cameraPermissionRequired = stringResource(R.string.camera_permission_required)
    val cameraFileFailed = stringResource(R.string.project_camera_file_failed_toast)
    val routeUnavailable = stringResource(R.string.project_route_unavailable_toast)

    LaunchedEffect(viewModel, navController) {
        viewModel.events.collect { event ->
            when (event) {
                is DirectProjectEvent.Created -> {
                    toastManager.showToast(creationSuccess, ToastType.SUCCESS)
                    when (event.destination) {
                        ProjectDetectionDestination.GRID_ENDPOINT -> navController.navigate(
                            Screen.WellDetection.createRoute(
                                imageUri = Uri.encode(event.imageUri),
                                projectId = event.projectId
                            )
                        )

                        ProjectDetectionDestination.SPECTRUM_SINGLE -> navController.navigate(
                            Screen.SpectrumCalibration.createRoute(
                                projectId = event.projectId,
                                imageUri = event.imageUri
                            )
                        )

                        ProjectDetectionDestination.LSPR_PAIRED,
                        ProjectDetectionDestination.UNSUPPORTED ->
                            toastManager.showToast(routeUnavailable, ToastType.ERROR)
                    }
                }

                DirectProjectEvent.FormIncomplete ->
                    toastManager.showToast(formIncomplete, ToastType.WARNING)

                DirectProjectEvent.UnexpectedFailure ->
                    toastManager.showToast(unexpectedFailure, ToastType.ERROR)
            }
        }
    }

    // 图片裁剪页通过返回栈写回 URI；消费后立即移除，避免重组时重复覆盖表单。
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.get<String>("croppedImageUri")?.let { imageUri ->
            viewModel.updateImageUri(imageUri)
            savedStateHandle.remove<String>("croppedImageUri")
        }
    }

    // 系统相册和系统文件选择器只负责返回图片 URI，后续统一进入同一个裁剪流程，
    // 避免不同导入来源产生不一致的图片处理、表单状态或项目创建行为。
    val openImageCrop: (Uri?) -> Unit = { uri ->
        uri?.let {
            navController.navigate(Screen.ImageCrop.createRoute(Uri.encode(it.toString())))
        }
    }

    val systemGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = openImageCrop
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        openImageCrop(uri)
    }

    val launchCamera: () -> Unit = {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val outputFile = runCatching {
            File.createTempFile("JPEG_${timestamp}_", ".jpg", directory)
        }.getOrNull()
        if (outputFile == null) {
            toastManager.showToast(cameraFileFailed, ToastType.ERROR)
        } else {
            navController.navigate(
                Screen.ImageCapture.createRoute(
                    outputPath = Uri.encode(outputFile.absolutePath),
                    captureMode = state.form.detectionModality.code,
                    // 光谱通道与所选分析物一一对应，拍摄页据此提示需要对齐的轨道数。
                    expectedSpectrumTracks = state.form.selectedAnalytes.size.coerceAtLeast(1)
                )
            )
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else toastManager.showToast(cameraPermissionRequired, ToastType.WARNING)
    }

    DirectCreateProjectContent(
        state = state,
        onBack = navController::navigateUp,
        onProjectNameChange = viewModel::updateProjectName,
        onDetectionModeChange = viewModel::updateDetectionModality,
        onCarrierPresetChange = viewModel::updateCarrierPreset,
        onCustomRowsChange = viewModel::updateCustomRows,
        onCustomColumnsChange = viewModel::updateCustomColumns,
        onCustomSiteShapeChange = viewModel::updateCustomSiteShape,
        onSpectrumLightSourceChange = viewModel::updateSpectrumLightSource,
        onAnalytesChange = { analytes ->
            viewModel.updateSelectedAnalytes(analytes.map { it.id })
        },
        onAnalyteUnitChange = viewModel::updateAnalyteConcentrationUnit,
        onAnalyteMaxConcentrationChange = viewModel::updateAnalyteMaxConcentration,
        onRemoveAnalyte = viewModel::removeAnalyte,
        onChooseImage = { showImageSourceDialog = true },
        onRemoveImage = { viewModel.updateImageUri(null) },
        onCreate = {
            val userId = currentUser?.id?.toString().orEmpty()
            if (userId.isBlank()) {
                toastManager.showToast(sessionInvalid, ToastType.ERROR)
            } else {
                viewModel.createProject(userId)
            }
        }
    )

    if (showImageSourceDialog) {
        DirectImageSourceDialog(
            onDismiss = { showImageSourceDialog = false },
            onSelectGallery = {
                showImageSourceDialog = false
                showExistingImageDialog = true
            },
            onTakePhoto = {
                showImageSourceDialog = false
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCamera()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )
    }

    if (showExistingImageDialog) {
        DirectExistingImageDialog(
            onDismiss = { showExistingImageDialog = false },
            onSelectSystemGallery = {
                showExistingImageDialog = false
                systemGalleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onSelectFile = {
                showExistingImageDialog = false
                filePickerLauncher.launch(arrayOf("image/*"))
            }
        )
    }
}

/**
 * 新建项目的无状态页面主体。
 *
 * 页面只保留两个主要视觉容器：实验配置工作单与项目图片。这样既继承旧版连续表单的
 * 清晰节奏，也避免当前版本为每一小组字段重复套卡片造成的碎片感。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectCreateProjectContent(
    state: DirectProjectUiState,
    onBack: () -> Unit,
    onProjectNameChange: (String) -> Unit,
    onDetectionModeChange: (DetectionModality) -> Unit,
    onCarrierPresetChange: (DirectCarrierPreset) -> Unit,
    onCustomRowsChange: (String) -> Unit,
    onCustomColumnsChange: (String) -> Unit,
    onCustomSiteShapeChange: (SiteShape) -> Unit,
    onSpectrumLightSourceChange: (SpectrumLightSource) -> Unit,
    onAnalytesChange: (List<com.muc.fluocolorquant.data.model.Analyte>) -> Unit,
    onAnalyteUnitChange: (String, String) -> Unit,
    onAnalyteMaxConcentrationChange: (String, String) -> Unit,
    onRemoveAnalyte: (String) -> Unit,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onCreate: () -> Unit
) {
    var showAnalytePicker by rememberSaveable { mutableStateOf(false) }
    var unitPickerAnalyteId by rememberSaveable { mutableStateOf<String?>(null) }
    var showLightSourcePicker by rememberSaveable { mutableStateOf(false) }
    val selectedAnalytes = state.form.selectedAnalytes.mapNotNull { selection ->
        state.analytes.firstOrNull { it.id == selection.analyteId }?.let { analyte ->
            analyte to selection
        }
    }

    FluoScreenScaffold(
        title = stringResource(R.string.direct_create_title),
        onBack = onBack,
        bottomBar = {
            DirectCreateBottomBar(
                canSubmit = state.form.canSubmit,
                isSubmitting = state.form.isSubmitting,
                onCreate = onCreate
            )
        }
    ) { padding ->
        FluoScrollableContent(padding = padding) {
            // 三个区块按 0/60/120ms 错峰入场，建立"名称 → 配置 → 图片"的阅读顺序。
            // 总延迟控制在 120ms 内，用户不会感到页面响应变慢（AGENTS.md 9.2）。
            FluoAnimatedSection {
                OutlinedTextField(
                    value = state.form.projectName,
                    onValueChange = onProjectNameChange,
                    label = { Text(stringResource(R.string.project_name_label)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluoRadius.control),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    singleLine = true
                )
            }

            FluoAnimatedSection(delayMillis = 60) {
            DirectConfigurationCard {
                DirectFormSectionHeader(
                    icon = Icons.Default.Science,
                    title = stringResource(R.string.direct_create_detection_section),
                    subtitle = stringResource(R.string.direct_create_detection_help)
                )
                DirectDetectionModeSelector(
                    selected = state.form.detectionModality,
                    onSelect = onDetectionModeChange
                )

                if (state.form.detectionModality != DetectionModality.SPECTRUM) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DirectFormSectionHeader(
                        icon = Icons.Default.GridView,
                        title = stringResource(R.string.direct_create_carrier_section),
                        subtitle = stringResource(R.string.direct_create_carrier_help)
                    )
                    DirectCarrierPresetGrid(
                        selected = state.form.carrierPreset,
                        customRows = state.form.customRowsInput,
                        customColumns = state.form.customColumnsInput,
                        onSelect = onCarrierPresetChange
                    )

                    AnimatedVisibility(
                        visible = state.form.carrierPreset ==
                            DirectCarrierPreset.MICROFLUIDIC_CUSTOM,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = state.form.customRowsInput,
                                    onValueChange = onCustomRowsChange,
                                    label = { Text(stringResource(R.string.direct_create_rows)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(FluoRadius.control),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.form.customColumnsInput,
                                    onValueChange = onCustomColumnsChange,
                                    label = { Text(stringResource(R.string.direct_create_columns)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = RoundedCornerShape(FluoRadius.control),
                                    singleLine = true
                                )
                            }
                            DirectCustomSiteShapeSelector(
                                selected = state.form.customSiteShape,
                                onSelect = onCustomSiteShapeChange
                            )
                        }
                    }
                } else {
                    // 光谱不使用规则阵列载体，但必须让用户看见"通道数 = 分析物数"这层绑定：
                    // 每个分析物占一条光谱轨道，标定与逐通道结果都按这个顺序归属。
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DirectFormSectionHeader(
                        icon = Icons.Default.Sensors,
                        title = stringResource(R.string.direct_create_spectrum_channel_section),
                        subtitle = stringResource(R.string.direct_create_spectrum_channel_help)
                    )
                    // 光源是本项目采集条件的人工确认项，不是自动校正开关。选择后会写入
                    // Project，并在生成结果时再次冻结，后续更改默认设置不会影响本项目。
                    ScientificSelectionField(
                        label = stringResource(R.string.spectrum_light_source_label),
                        value = stringResource(state.form.spectrumLightSource.displayNameRes),
                        placeholder = stringResource(R.string.spectrum_light_source_label),
                        icon = Icons.Default.Sensors,
                        supportingValue = stringResource(
                            R.string.direct_create_spectrum_light_source_help
                        ),
                        onClick = { showLightSourcePicker = true }
                    )
                    DirectSpectrumChannelBinding(
                        analytes = selectedAnalytes.map { it.first }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DirectFormSectionHeader(
                    icon = Icons.Default.Biotech,
                    title = stringResource(R.string.direct_create_analysis_section),
                    subtitle = stringResource(R.string.direct_create_analysis_help)
                )
                DirectAnalyteSelectionArea(
                    selectedAnalytes = selectedAnalytes,
                    isLoading = state.isLoading,
                    onOpenPicker = { showAnalytePicker = true },
                    onOpenUnitPicker = { analyteId -> unitPickerAnalyteId = analyteId },
                    onMaxConcentrationChange = onAnalyteMaxConcentrationChange,
                    onRemoveAnalyte = onRemoveAnalyte
                )
            }
            }

            FluoAnimatedSection(delayMillis = 120) {
                Column(verticalArrangement = Arrangement.spacedBy(FluoSpacing.md)) {
                    DirectFormSectionHeader(
                        icon = Icons.Default.AddPhotoAlternate,
                        title = stringResource(R.string.project_image),
                        subtitle = stringResource(R.string.direct_create_image_help)
                    )
                    DirectImageCard(
                        imageUri = state.form.imageUri,
                        onChooseImage = onChooseImage,
                        onRemoveImage = onRemoveImage
                    )
                }
            }
        }
    }

    if (showAnalytePicker) {
        AnalyteSelectionDialog(
            availableAnalytes = state.analytes,
            selectedAnalytes = selectedAnalytes.map { it.first },
            onConfirm = { analytes ->
                onAnalytesChange(analytes)
                showAnalytePicker = false
            },
            onDismiss = { showAnalytePicker = false }
        )
    }

    val unitSelection = state.form.selectedAnalytes.firstOrNull {
        it.analyteId == unitPickerAnalyteId
    }
    if (unitSelection != null) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_unit),
            options = state.concentrationUnits.map { unit ->
                com.muc.fluocolorquant.ui.components.ScientificPickerOption(
                    id = unit,
                    title = unit
                )
            },
            selectedId = unitSelection.concentrationUnit,
            onSelect = { unit ->
                onAnalyteUnitChange(unitSelection.analyteId, unit)
            },
            onDismiss = { unitPickerAnalyteId = null }
        )
    }

    if (showLightSourcePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.spectrum_light_source_label),
            options = SpectrumLightSource.entries.map { lightSource ->
                com.muc.fluocolorquant.ui.components.ScientificPickerOption(
                    id = lightSource.name,
                    title = stringResource(lightSource.displayNameRes),
                    icon = Icons.Default.Sensors
                )
            },
            selectedId = state.form.spectrumLightSource.name,
            onSelect = { selectedName ->
                SpectrumLightSource.entries.firstOrNull { it.name == selectedName }
                    ?.let(onSpectrumLightSourceChange)
            },
            onDismiss = { showLightSourcePicker = false }
        )
    }
}

/** 单张实验配置工作单，取代原页面多层重复卡片。形状与内边距统一取自设计令牌。 */
@Composable
private fun DirectConfigurationCard(content: @Composable ColumnScope.() -> Unit) {
    FluoSectionCard(content = content)
}

/** 章节标题只负责建立阅读锚点，说明文字压缩为一行，避免标题与卡片层级重复。 */
@Composable
private fun DirectFormSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    FluoSectionHeader(title = title, icon = icon, subtitle = subtitle)
}

/** 三等分检测方式选择器，继承旧版分段控件的直接性，同时支持图标和暗色主题。 */
@Composable
private fun DirectDetectionModeSelector(
    selected: DetectionModality,
    onSelect: (DetectionModality) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FluoRadius.card),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(FluoSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
        ) {
            DetectionModality.entries.forEach { modality ->
                val isSelected = selected == modality
                // 选中态在 180ms 内完成颜色过渡，避免三个分段在切换瞬间同时"跳色"。
                // 只做颜色变化，不加缩放或弹跳，防止分段控件在快速切换时抖动。
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "detectionModeContainer"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "detectionModeContent"
                )
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 68.dp)
                        .clickable { onSelect(modality) },
                    shape = RoundedCornerShape(FluoRadius.control),
                    color = containerColor,
                    contentColor = contentColor
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = FluoSpacing.xs,
                            vertical = FluoSpacing.sm
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
                    ) {
                        Icon(
                            imageVector = detectionModeIcon(modality),
                            contentDescription = null,
                            modifier = Modifier.size(FluoIconSize.medium)
                        )
                        Text(
                            text = detectionModeLabel(modality),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 自定义阵列的物理位点形状选择。
 *
 * PG-Grid 只负责恢复规则晶格，不会可靠判断位点应使用圆形还是方形采样掩膜；
 * 因此必须由用户依据真实载体显式选择，并随 CarrierProfile 冻结。
 */
@Composable
private fun DirectCustomSiteShapeSelector(
    selected: SiteShape,
    onSelect: (SiteShape) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.direct_create_custom_shape_label),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DirectSiteShapeOption(
                label = stringResource(R.string.direct_create_custom_shape_circle),
                icon = Icons.Default.Circle,
                selected = selected == SiteShape.CIRCLE,
                onClick = { onSelect(SiteShape.CIRCLE) },
                modifier = Modifier.weight(1f)
            )
            DirectSiteShapeOption(
                label = stringResource(R.string.direct_create_custom_shape_square),
                icon = Icons.Default.CropSquare,
                selected = selected == SiteShape.SQUARE,
                onClick = { onSelect(SiteShape.SQUARE) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = stringResource(R.string.direct_create_custom_shape_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DirectSiteShapeOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.control),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/** 两列载体预设网格，规格和阵列尺寸在选择前即可直接比较。 */
@Composable
private fun DirectCarrierPresetGrid(
    selected: DirectCarrierPreset,
    customRows: String,
    customColumns: String,
    onSelect: (DirectCarrierPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(FluoSpacing.sm)) {
        DirectCarrierPreset.entries.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
            ) {
                rowPresets.forEach { preset ->
                    DirectCarrierPresetTile(
                        preset = preset,
                        selected = selected == preset,
                        meta = carrierPresetMeta(preset, customRows, customColumns),
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowPresets.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DirectCarrierPresetTile(
    preset: DirectCarrierPreset,
    selected: Boolean,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 选中反馈只用底色、描边和字重表达，不用缩放或位移：载体预设是一次性配置动作，
    // 网格里同时出现的弹跳会干扰"比较规格"这件真正的任务（AGENTS.md 9.3）。
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = FluoMotion.micro(),
        label = "carrierTileContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = FluoMotion.micro(),
        label = "carrierTileBorder"
    )
    val iconContainerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = FluoMotion.micro(),
        label = "carrierTileIconContainer"
    )
    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.control),
        color = containerColor,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier.padding(FluoSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
        ) {
            Surface(
                modifier = Modifier.size(FluoIconSize.badgeContainer),
                shape = RoundedCornerShape(FluoRadius.badge),
                color = iconContainerColor
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = carrierPresetIcon(preset),
                        contentDescription = null,
                        modifier = Modifier.size(FluoIconSize.medium),
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = carrierPresetLabel(preset),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 多分析物选择与逐分析物单位配置区。
 *
 * 多选入口保持一个清晰主操作；确认后每个分析物独立成卡，单位使用系统设置中的下拉选项，
 * 从交互层阻止自由文本、拼写差异和“一个全局单位覆盖全部分析物”的数据错误。
 */
/**
 * 展示“光谱通道 ↔ 分析物”的一一绑定。
 *
 * 光谱不走规则阵列载体，因此这里不选行列，而是明确告诉用户：所选的第 n 个分析物就对应
 * 第 n 条光谱轨道。该顺序会被写入项目的 `spectrumColumnMappingJson`，标定页按它检测轨道
 * 数量、逐通道结果按它归属分析物，因此界面顺序与科学归属必须一致。
 */
@Composable
private fun DirectSpectrumChannelBinding(
    analytes: List<com.muc.fluocolorquant.data.model.Analyte>
) {
    if (analytes.isEmpty()) {
        Text(
            text = stringResource(R.string.direct_create_spectrum_channel_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Surface(
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.direct_create_spectrum_channel_count,
                    analytes.size
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            analytes.forEachIndexed { index, analyte ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        // 展示用 1 基通道号，与写入映射的键保持同一约定。
                        text = stringResource(
                            R.string.direct_create_spectrum_channel_label,
                            index + 1
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = analyte.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectAnalyteSelectionArea(
    selectedAnalytes: List<Pair<com.muc.fluocolorquant.data.model.Analyte, DirectAnalyteSelection>>,
    isLoading: Boolean,
    onOpenPicker: () -> Unit,
    onOpenUnitPicker: (String) -> Unit,
    onMaxConcentrationChange: (String, String) -> Unit,
    onRemoveAnalyte: (String) -> Unit
) {
    ScientificSelectionField(
        label = stringResource(R.string.analyte),
        value = if (selectedAnalytes.isNotEmpty()) {
            stringResource(R.string.direct_create_analyte_count, selectedAnalytes.size)
        } else {
            null
        },
        placeholder = stringResource(R.string.direct_create_select_analytes),
        icon = Icons.Default.Biotech,
        onClick = onOpenPicker,
        enabled = !isLoading,
        supportingValue = if (isLoading) {
            stringResource(R.string.direct_create_loading_options)
        } else {
            stringResource(R.string.direct_create_analyte_multi_help)
        }
    )

    selectedAnalytes.forEach { (analyte, selection) ->
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.control),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.padding(start = 13.dp, top = 10.dp, end = 8.dp, bottom = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = analyte.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { onRemoveAnalyte(analyte.id) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(
                                R.string.direct_create_remove_analyte,
                                analyte.name
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(0.48f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        /*
                         * 最大浓度与右侧单位统一采用“外置标签 + 64dp 控件”的结构。此前左侧
                         * 使用浮动标签 TextField、右侧使用外置标签选择器，导致顶部基线和高度
                         * 永远无法齐平；这里从组件结构上消除差异，而不是靠魔法边距微调。
                         */
                        Text(
                            text = stringResource(R.string.max_concentration),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selection.maxConcentration == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = selection.maxConcentrationInput,
                            onValueChange = { value ->
                                onMaxConcentrationChange(analyte.id, value)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            leadingIcon = {
                                Icon(Icons.Default.Science, contentDescription = null)
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            isError = selection.maxConcentration == null,
                            singleLine = true,
                            shape = RoundedCornerShape(FluoRadius.control)
                        )
                    }
                    ScientificSelectionField(
                        label = stringResource(R.string.concentration_unit),
                        value = selection.concentrationUnit,
                        placeholder = stringResource(R.string.direct_create_select_unit),
                        icon = Icons.Default.Straighten,
                        onClick = { onOpenUnitPicker(analyte.id) },
                        modifier = Modifier.weight(0.52f),
                        density = ScientificSelectionFieldDensity.COMPACT
                    )
                }
                if (selection.maxConcentration == null) {
                    Text(
                        text = stringResource(R.string.direct_create_max_concentration_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 大图预览继承旧版 240dp 图片区的优点，使用 Fit 显示完整实验图，不把芯片边缘裁掉。
 */
@Composable
private fun DirectImageCard(
    imageUri: String?,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChooseImage),
        shape = RoundedCornerShape(FluoRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (imageUri.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    modifier = Modifier.size(62.dp),
                    shape = RoundedCornerShape(FluoRadius.card),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(31.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.direct_create_choose_image),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.direct_create_image_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.project_image_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = stringResource(R.string.direct_create_change_image),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
                IconButton(
                    onClick = onRemoveImage,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp)
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.remove_image),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/** 固定底部主操作，用户不需要滚回页面末尾寻找创建按钮。 */
@Composable
private fun DirectCreateBottomBar(
    canSubmit: Boolean,
    isSubmitting: Boolean,
    onCreate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                text = stringResource(
                    if (canSubmit) {
                        R.string.direct_create_ready_hint
                    } else {
                        R.string.direct_create_pending_hint
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (canSubmit) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Button(
                onClick = onCreate,
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(FluoRadius.control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.direct_create_submit),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * 图片来源弹窗恢复旧版双入口布局，拍照和相册选择一眼可见。
 *
 * 这里不使用 Material3 默认 AlertDialog 的独立按钮区，避免在两个大入口下方留下过多空白；
 * 关闭操作放入标题栏右侧，既保持可发现性，也让弹窗在手机竖屏中更紧凑。
 */
@Composable
private fun DirectImageSourceDialog(
    onDismiss: () -> Unit,
    onSelectGallery: () -> Unit,
    onTakePhoto: () -> Unit
) {
    DirectTwoOptionDialog(
        title = stringResource(R.string.project_image_source_title),
        onDismiss = onDismiss,
        firstIcon = Icons.Default.PhotoLibrary,
        firstTitle = stringResource(R.string.select_from_gallery),
        firstSubtitle = stringResource(R.string.direct_create_gallery_desc),
        onFirstClick = onSelectGallery,
        secondIcon = Icons.Default.CameraAlt,
        secondTitle = stringResource(R.string.take_photo),
        secondSubtitle = stringResource(R.string.direct_create_camera_desc),
        onSecondClick = onTakePhoto
    )
}

/**
 * 已有图片的第二层选择面板。
 *
 * “系统相册”使用 Android Photo Picker，适合按照片缩略图快速选择；“文件”使用系统
 * DocumentsUI，允许用户自由浏览 Download、Pictures、DCIM 或其他文件提供方。界面只显示
 * 用户能理解的来源名称，不暴露 ActivityResultContract 等实现细节。
 */
@Composable
private fun DirectExistingImageDialog(
    onDismiss: () -> Unit,
    onSelectSystemGallery: () -> Unit,
    onSelectFile: () -> Unit
) {
    DirectTwoOptionDialog(
        title = stringResource(R.string.direct_create_existing_image_title),
        onDismiss = onDismiss,
        firstIcon = Icons.Default.PhotoLibrary,
        firstTitle = stringResource(R.string.direct_create_system_gallery),
        firstSubtitle = stringResource(R.string.direct_create_system_gallery_desc),
        onFirstClick = onSelectSystemGallery,
        secondIcon = Icons.Default.FolderOpen,
        secondTitle = stringResource(R.string.direct_create_file),
        secondSubtitle = stringResource(R.string.direct_create_file_desc),
        onSecondClick = onSelectFile
    )
}

/**
 * 图片来源弹窗共用的双选项骨架。
 *
 * 两级弹窗复用完全相同的尺寸、间距和关闭位置，使用户在继续细分图片来源时仍保持稳定的
 * 视觉节奏，同时避免两份近似布局在后续迭代中逐渐出现样式差异。
 */
@Composable
private fun DirectTwoOptionDialog(
    title: String,
    onDismiss: () -> Unit,
    firstIcon: ImageVector,
    firstTitle: String,
    firstSubtitle: String,
    onFirstClick: () -> Unit,
    secondIcon: ImageVector,
    secondTitle: String,
    secondSubtitle: String,
    onSecondClick: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.card),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DirectImageSourceOption(
                        icon = firstIcon,
                        title = firstTitle,
                        subtitle = firstSubtitle,
                        onClick = onFirstClick,
                        modifier = Modifier.weight(1f)
                    )
                    DirectImageSourceOption(
                        icon = secondIcon,
                        title = secondTitle,
                        subtitle = secondSubtitle,
                        onClick = onSecondClick,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectImageSourceOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FluoRadius.card),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(FluoRadius.control),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun detectionModeLabel(modality: DetectionModality): String = when (modality) {
    // 三等分控件使用已有短标签；完整检测名称已由上方章节标题和图标表达。这样在
    // 360dp、1.3 倍字体下仍可完整显示，不会把末尾文字硬裁掉。
    DetectionModality.COLORIMETRIC -> stringResource(R.string.colorimetric_mode_short)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.fluorescence_mode_short)
    DetectionModality.SPECTRUM -> stringResource(R.string.spectrum_detection)
}

private fun detectionModeIcon(modality: DetectionModality): ImageVector = when (modality) {
    DetectionModality.COLORIMETRIC -> Icons.Default.WaterDrop
    DetectionModality.FLUORESCENCE -> Icons.Default.Science
    DetectionModality.SPECTRUM -> Icons.Default.Sensors
}

@Composable
private fun carrierPresetLabel(preset: DirectCarrierPreset): String = when (preset) {
    DirectCarrierPreset.PLATE_96 -> stringResource(R.string.direct_create_carrier_96)
    DirectCarrierPreset.MICROFLUIDIC_10_X_10 -> {
        stringResource(R.string.direct_create_carrier_10x10)
    }

    DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> {
        stringResource(R.string.direct_create_carrier_15x15)
    }

    DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> {
        stringResource(R.string.direct_create_carrier_custom)
    }
}

private fun carrierPresetIcon(preset: DirectCarrierPreset): ImageVector = when (preset) {
    DirectCarrierPreset.PLATE_96 -> Icons.Default.ViewModule
    DirectCarrierPreset.MICROFLUIDIC_10_X_10,
    DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> Icons.Default.GridView
    DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> Icons.Default.Tune
}

@Composable
private fun carrierPresetMeta(
    preset: DirectCarrierPreset,
    customRows: String,
    customColumns: String
): String = when (preset) {
    DirectCarrierPreset.PLATE_96 -> stringResource(R.string.direct_create_carrier_meta, 8, 12)
    DirectCarrierPreset.MICROFLUIDIC_10_X_10 -> {
        stringResource(R.string.direct_create_carrier_meta, 10, 10)
    }

    DirectCarrierPreset.MICROFLUIDIC_15_X_15 -> {
        stringResource(R.string.direct_create_carrier_meta, 15, 15)
    }

    DirectCarrierPreset.MICROFLUIDIC_CUSTOM -> stringResource(
        R.string.direct_create_carrier_custom_meta,
        customRows.ifBlank { "–" },
        customColumns.ifBlank { "–" }
    )
}
