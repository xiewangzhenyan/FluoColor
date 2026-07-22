package com.muc.fluocolorquant.ui.screens.project

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
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
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.project.DirectCarrierPreset
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.components.analysisFeatureLabel
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
                    expectedSpectrumTracks = 1
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
        onAnalyteChange = viewModel::updateAnalyte,
        onConcentrationUnitChange = viewModel::updateConcentrationUnit,
        onAnalysisModelChange = viewModel::updateAnalysisModel,
        onSampleIdChange = viewModel::updateSampleId,
        onReferenceRowChange = viewModel::updateColorReferenceRow,
        onReferenceColumnChange = viewModel::updateColorReferenceColumn,
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
    onAnalyteChange: (String) -> Unit,
    onConcentrationUnitChange: (String) -> Unit,
    onAnalysisModelChange: (String?) -> Unit,
    onSampleIdChange: (String) -> Unit,
    onReferenceRowChange: (String) -> Unit,
    onReferenceColumnChange: (String) -> Unit,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onCreate: () -> Unit
) {
    var showAnalytePicker by rememberSaveable { mutableStateOf(false) }
    var showUnitPicker by rememberSaveable { mutableStateOf(false) }
    var showAnalysisModelPicker by rememberSaveable { mutableStateOf(false) }
    val selectedAnalyte = state.analytes.firstOrNull { it.id == state.form.selectedAnalyteId }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.direct_create_title),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            DirectCreateBottomBar(
                canSubmit = state.form.canSubmit,
                isSubmitting = state.form.isSubmitting,
                onCreate = onCreate
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
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
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )

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
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = state.form.customRowsInput,
                                onValueChange = onCustomRowsChange,
                                label = { Text(stringResource(R.string.direct_create_rows)) },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.form.customColumnsInput,
                                onValueChange = onCustomColumnsChange,
                                label = { Text(stringResource(R.string.direct_create_columns)) },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DirectFormSectionHeader(
                    icon = Icons.Default.Biotech,
                    title = stringResource(R.string.direct_create_analysis_section),
                    subtitle = stringResource(R.string.direct_create_analysis_help)
                )
                ScientificSelectionField(
                    label = stringResource(R.string.analyte),
                    value = selectedAnalyte?.name,
                    placeholder = stringResource(R.string.direct_create_select_analyte),
                    icon = Icons.Default.Biotech,
                    onClick = { showAnalytePicker = true },
                    enabled = !state.isLoading,
                    supportingValue = if (state.isLoading) {
                        stringResource(R.string.direct_create_loading_options)
                    } else {
                        null
                    }
                )
                ScientificSelectionField(
                    label = stringResource(R.string.concentration_unit),
                    value = state.form.concentrationUnit,
                    placeholder = stringResource(R.string.direct_create_select_unit),
                    icon = Icons.Default.Straighten,
                    onClick = { showUnitPicker = true }
                )
                OutlinedTextField(
                    value = state.form.sampleId,
                    onValueChange = onSampleIdChange,
                    label = { Text(stringResource(R.string.direct_create_sample_id)) },
                    supportingText = {
                        Text(stringResource(R.string.direct_create_sample_id_support))
                    },
                    leadingIcon = {
                        // 样本编号属于实验标签信息，使用标签图标比无线信号图标更符合用户认知。
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )

                DirectAnalysisModelSelector(
                    models = state.compatibleModels,
                    selectedModelId = state.form.selectedAnalysisModelId,
                    automaticallySelected = state.compatibleModels.size == 1 &&
                        !state.form.analysisModelSelectionExplicit,
                    onOpenPicker = { showAnalysisModelPicker = true }
                )

                AnimatedVisibility(
                    visible = state.form.requiresColorReference,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DirectFormSectionHeader(
                            icon = Icons.Default.WaterDrop,
                            title = stringResource(R.string.direct_create_reference_section),
                            subtitle = stringResource(R.string.direct_create_reference_desc)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = state.form.colorReferenceRowInput,
                                onValueChange = onReferenceRowChange,
                                label = {
                                    Text(stringResource(R.string.direct_create_reference_row))
                                },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !state.form.colorReferenceValid,
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.form.colorReferenceColumnInput,
                                onValueChange = onReferenceColumnChange,
                                label = {
                                    Text(stringResource(R.string.direct_create_reference_column))
                                },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                isError = !state.form.colorReferenceValid,
                                shape = RoundedCornerShape(14.dp),
                                singleLine = true
                            )
                        }
                        if (!state.form.colorReferenceValid) {
                            Text(
                                text = stringResource(R.string.direct_create_reference_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
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

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showAnalytePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_analyte),
            options = state.analytes.map { analyte ->
                ScientificPickerOption(
                    id = analyte.id,
                    title = analyte.name,
                    icon = Icons.Default.Biotech
                )
            },
            selectedId = state.form.selectedAnalyteId,
            onSelect = onAnalyteChange,
            onDismiss = { showAnalytePicker = false }
        )
    }

    if (showUnitPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_unit),
            options = state.concentrationUnits.map { unit ->
                ScientificPickerOption(
                    id = unit,
                    title = unit,
                    icon = Icons.Default.Straighten
                )
            },
            selectedId = state.form.concentrationUnit,
            onSelect = onConcentrationUnitChange,
            onDismiss = { showUnitPicker = false }
        )
    }

    if (showAnalysisModelPicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.direct_create_select_model),
            options = listOf(
                ScientificPickerOption(
                    id = SIGNAL_ONLY_OPTION_ID,
                    title = stringResource(R.string.direct_create_signal_only_option),
                    subtitle = stringResource(R.string.direct_create_signal_only_model_desc),
                    icon = Icons.Default.Info
                )
            ) + state.compatibleModels.map { model ->
                ScientificPickerOption(
                    id = model.id,
                    title = model.name,
                    icon = Icons.Default.AutoGraph
                )
            },
            selectedId = state.form.selectedAnalysisModelId ?: SIGNAL_ONLY_OPTION_ID,
            onSelect = { selectedId ->
                onAnalysisModelChange(selectedId.takeUnless { it == SIGNAL_ONLY_OPTION_ID })
            },
            onDismiss = { showAnalysisModelPicker = false }
        )
    }
}

/** 单张实验配置工作单，取代原页面多层重复卡片。 */
@Composable
private fun DirectConfigurationCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content
        )
    }
}

/** 章节标题只负责建立阅读锚点，说明文字压缩为一行，避免标题与卡片层级重复。 */
@Composable
private fun DirectFormSectionHeader(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 三等分检测方式选择器，继承旧版分段控件的直接性，同时支持图标和暗色主题。 */
@Composable
private fun DirectDetectionModeSelector(
    selected: DetectionModality,
    onSelect: (DetectionModality) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DetectionModality.entries.forEach { modality ->
                val isSelected = selected == modality
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 68.dp)
                        .clickable { onSelect(modality) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shadowElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = detectionModeIcon(modality),
                            contentDescription = null,
                            modifier = Modifier.size(21.dp)
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

/** 两列载体预设网格，规格和阵列尺寸在选择前即可直接比较。 */
@Composable
private fun DirectCarrierPresetGrid(
    selected: DirectCarrierPreset,
    customRows: String,
    customColumns: String,
    onSelect: (DirectCarrierPreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DirectCarrierPreset.entries.chunked(2).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
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
    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
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
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = carrierPresetIcon(preset),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
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
 * 定量曲线选择器。
 *
 * 唯一候选会自动选中；多个候选通过全宽底部面板选择；用户始终可以主动切换到仅信号。
 * 页面只显示曲线名称和可理解的信号类型，不暴露拟合参数 JSON 或处理器机器字段。
 */
@Composable
private fun DirectAnalysisModelSelector(
    models: List<com.muc.fluocolorquant.data.model.AnalysisModel>,
    selectedModelId: String?,
    automaticallySelected: Boolean,
    onOpenPicker: () -> Unit
) {
    val selectedModel = models.firstOrNull { it.id == selectedModelId }
    val selectedFeature = selectedModel?.primaryFeature
        ?.let(AnalysisPrimaryFeature::fromCode)
    val selectedFeatureLabel = if (selectedFeature != null) {
        analysisFeatureLabel(selectedFeature)
    } else {
        null
    }

    if (models.isEmpty()) {
        DirectSignalOnlyNotice()
        return
    }

    ScientificSelectionField(
        label = stringResource(R.string.direct_create_quantitation_model),
        value = selectedModel?.name ?: stringResource(R.string.direct_create_signal_only_option),
        placeholder = stringResource(R.string.direct_create_select_model),
        icon = Icons.Default.AutoGraph,
        onClick = onOpenPicker,
        supportingValue = when {
            selectedModel == null -> stringResource(R.string.direct_create_signal_only_model_desc)
            automaticallySelected -> stringResource(R.string.direct_create_model_auto_selected)
            else -> selectedFeatureLabel
        }
    )
}

/** 仅信号说明压缩为轻量提示行，不再占据一张独立大卡片。 */
@Composable
private fun DirectSignalOnlyNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.direct_create_signal_only_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.direct_create_signal_only_compact_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 底部选择器使用的内部稳定 ID，不会写入表单或数据库。 */
private const val SIGNAL_ONLY_OPTION_ID = "__signal_only__"

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
        shape = RoundedCornerShape(20.dp),
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
                    shape = RoundedCornerShape(20.dp),
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
                    shape = RoundedCornerShape(10.dp),
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
                shape = RoundedCornerShape(15.dp),
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
            shape = RoundedCornerShape(22.dp),
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
        shape = RoundedCornerShape(18.dp),
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
                shape = RoundedCornerShape(15.dp),
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
    DetectionModality.COLORIMETRIC -> stringResource(R.string.colorimetric_detection)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.fluorescence_detection)
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
