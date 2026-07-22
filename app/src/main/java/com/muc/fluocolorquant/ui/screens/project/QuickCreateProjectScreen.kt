@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.muc.fluocolorquant.ui.screens.project

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.ResolvedTemplateProjectConfiguration
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ScientificMetadataChip
import com.muc.fluocolorquant.ui.components.ScientificPickerOption
import com.muc.fluocolorquant.ui.components.ScientificPickerSheet
import com.muc.fluocolorquant.ui.components.ScientificSectionTitle
import com.muc.fluocolorquant.ui.components.ScientificSelectionField
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.ProjectEvent
import com.muc.fluocolorquant.ui.viewmodels.ProjectUiState
import com.muc.fluocolorquant.ui.viewmodels.ProjectViewModel
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compose 自动化测试使用的稳定语义标签，避免测试依赖具体排版尺寸。 */
object QuickCreateProjectTestTags {
    const val TEMPLATE_SELECTOR = "quick_template_selector"
    const val TEMPLATE_SUMMARY = "quick_template_summary"
    const val PROJECT_NAME = "quick_project_name"
    const val WHOLE_CHIP_SAMPLE = "quick_whole_chip_sample"
    const val COPY_TEMPLATE = "quick_copy_template"
    const val CUSTOM_TEMPLATE = "quick_custom_template"
    const val CREATE_BUTTON = "quick_create_button"
}

/**
 * 科研项目的快速主入口。
 *
 * 页面只收集一次实验运行真正会变化的信息：项目名称、已发布方案、整芯片样本编号、可选
 * 批次和图片。检测模态、载体几何、分析物、荧光通道、模型和位点布局全部来自已发布模板，
 * 从根源上禁止“按分析物数量平均分格”和“自动伪造空白位”等不具备实验依据的行为。
 */
@Composable
fun QuickCreateProjectScreen(
    navController: NavController,
    projectViewModel: ProjectViewModel = hiltViewModel(),
    userViewModel: UserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val state by projectViewModel.uiState.collectAsState()
    val currentUser by userViewModel.currentUser.collectAsState()
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }

    // 所有回调使用的用户可见文本都在 Composable 上下文中提前解析，禁止在事件回调里
    // 直接调用 stringResource()，同时保持中英文资源 ID 完全一致。
    val creationSuccess = stringResource(R.string.project_creation_success)
    val formIncomplete = stringResource(R.string.quick_create_template_incomplete)
    val preflightFailed = stringResource(R.string.project_preflight_failed_toast)
    val unexpectedFailure = stringResource(R.string.project_unexpected_failure_toast)
    val sessionInvalid = stringResource(R.string.project_session_invalid_toast)
    val cameraPermissionRequired = stringResource(R.string.camera_permission_required)
    val cameraFileFailed = stringResource(R.string.project_camera_file_failed_toast)
    val routeUnavailable = stringResource(R.string.project_route_unavailable_toast)

    LaunchedEffect(projectViewModel, navController) {
        projectViewModel.events.collect { event ->
            when (event) {
                is ProjectEvent.Created -> {
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
                        ProjectDetectionDestination.UNSUPPORTED -> {
                            toastManager.showToast(routeUnavailable, ToastType.ERROR)
                        }
                    }
                }

                is ProjectEvent.ValidationBlocked -> {
                    toastManager.showToast(preflightFailed, ToastType.WARNING)
                }

                ProjectEvent.FormIncomplete -> {
                    toastManager.showToast(formIncomplete, ToastType.WARNING)
                }

                ProjectEvent.UnexpectedFailure -> {
                    toastManager.showToast(unexpectedFailure, ToastType.ERROR)
                }
            }
        }
    }

    // 裁剪页将 URI 写回当前返回栈；读取后立即清除，避免重组或返回页面时重复消费。
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.get<String>("croppedImageUri")?.let { imageUri ->
            projectViewModel.updateImageUri(imageUri)
            savedStateHandle.remove<String>("croppedImageUri")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            navController.navigate(Screen.ImageCrop.createRoute(Uri.encode(it.toString())))
        }
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
                    captureMode = state.resolvedConfiguration?.snapshot?.template?.detectionMode,
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

    QuickCreateProjectContent(
        state = state,
        onNavigateBack = { navController.navigateUp() },
        onSelectTemplate = projectViewModel::selectTemplate,
        onCopyTemplate = { templateId ->
            navController.navigate(Screen.CreateExperimentTemplate.createCopyRoute(templateId))
        },
        onCreateCustomTemplate = {
            navController.navigate(Screen.CreateExperimentTemplate.createRoute())
        },
        onProjectNameChange = projectViewModel::updateProjectName,
        onWholeChipSampleChange = { sampleId ->
            projectViewModel.applySampleSlotToSites(
                state.form.requiredSampleSites.map { it.siteKey },
                sampleId
            )
        },
        onProjectBatchChange = projectViewModel::updateProjectBatch,
        onSampleBatchChange = projectViewModel::updateSampleBatch,
        onChooseImage = { showImageSourceDialog = true },
        onRemoveImage = { projectViewModel.updateImageUri(null) },
        onCreateProject = {
            val userId = currentUser?.id?.toString().orEmpty()
            if (userId.isBlank()) {
                toastManager.showToast(sessionInvalid, ToastType.ERROR)
            } else {
                projectViewModel.createProject(userId)
            }
        }
    )

    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = { Text(stringResource(R.string.project_image_source_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showImageSourceDialog = false
                            galleryLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.project_image_from_gallery))
                    }
                    OutlinedButton(
                        onClick = {
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
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.project_image_from_camera))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * 无状态快速新建内容，便于 Compose 测试直接注入模板和表单状态。
 *
 * “复制并调整”与“自定义新方案”明确属于方案维护动作，和本次实验运行信息分开，避免用户
 * 误以为在当前页面临时改动会悄悄改变正式模板。
 */
@Composable
fun QuickCreateProjectContent(
    state: ProjectUiState,
    onNavigateBack: () -> Unit,
    onSelectTemplate: (String) -> Unit,
    onCopyTemplate: (String) -> Unit,
    onCreateCustomTemplate: () -> Unit,
    onProjectNameChange: (String) -> Unit,
    onWholeChipSampleChange: (String) -> Unit,
    onProjectBatchChange: (String) -> Unit,
    onSampleBatchChange: (String) -> Unit,
    onChooseImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onCreateProject: () -> Unit
) {
    var traceabilityExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            QuickSchemeCard(
                state = state,
                onSelectTemplate = onSelectTemplate,
                onCopyTemplate = onCopyTemplate,
                onCreateCustomTemplate = onCreateCustomTemplate
            )

            OutlinedTextField(
                value = state.form.projectName,
                onValueChange = onProjectNameChange,
                label = { Text(stringResource(R.string.project_name_label)) },
                leadingIcon = {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(QuickCreateProjectTestTags.PROJECT_NAME),
                singleLine = true
            )

            if (state.form.templateReady && state.form.requiredSampleSites.isNotEmpty()) {
                val distinctSampleIds = state.form.requiredSampleSites
                    .mapNotNull { site -> state.form.sampleSlotMapping[site.siteKey]?.trim() }
                    .filter(String::isNotEmpty)
                    .distinct()
                val wholeChipSampleId = distinctSampleIds.singleOrNull().orEmpty()
                OutlinedTextField(
                    value = wholeChipSampleId,
                    onValueChange = onWholeChipSampleChange,
                    label = { Text(stringResource(R.string.quick_create_sample_id_label)) },
                    leadingIcon = {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                    },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.quick_create_sample_id_support,
                                state.form.requiredSampleSites.size
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(QuickCreateProjectTestTags.WHOLE_CHIP_SAMPLE),
                    singleLine = true
                )
            }

            TraceabilityFields(
                expanded = traceabilityExpanded,
                projectBatch = state.form.projectBatch,
                sampleBatch = state.form.sampleBatch,
                onExpandedChange = { traceabilityExpanded = !traceabilityExpanded },
                onProjectBatchChange = onProjectBatchChange,
                onSampleBatchChange = onSampleBatchChange
            )

            QuickProjectImage(
                imageUri = state.form.imageUri,
                onChoose = onChooseImage,
                onRemove = onRemoveImage
            )

            if (state.form.preflightIssues.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            stringResource(
                                R.string.quick_create_preflight_issue_count,
                                state.form.preflightIssues.size
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Button(
                onClick = onCreateProject,
                enabled = state.form.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(QuickCreateProjectTestTags.CREATE_BUTTON),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.form.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.project_creating_button))
                } else {
                    Text(stringResource(R.string.quick_create_submit))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 已发布方案选择与方案维护入口；页面不会自动选择第一条模板。 */
@Composable
private fun QuickSchemeCard(
    state: ProjectUiState,
    onSelectTemplate: (String) -> Unit,
    onCopyTemplate: (String) -> Unit,
    onCreateCustomTemplate: () -> Unit
) {
    var showTemplatePicker by rememberSaveable { mutableStateOf(false) }
    val selectedTemplate = state.publishedTemplates.firstOrNull {
        it.id == state.form.selectedTemplateId
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ScientificSectionTitle(
                title = stringResource(R.string.quick_create_scheme_label),
                icon = Icons.Default.Science
            )

            when {
                state.isLoadingTemplates -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.project_template_loading))
                    }
                }

                state.publishedTemplates.isEmpty() -> {
                    Text(
                        stringResource(R.string.quick_create_no_published_scheme),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    ScientificSelectionField(
                        label = null,
                        value = selectedTemplate?.templateName,
                        supportingValue = selectedTemplate?.let {
                            stringResource(R.string.project_template_version, it.version)
                        },
                        placeholder = stringResource(R.string.project_template_select_label),
                        icon = Icons.Default.Science,
                        onClick = { showTemplatePicker = true },
                        modifier = Modifier.testTag(QuickCreateProjectTestTags.TEMPLATE_SELECTOR)
                    )
                }
            }

            if (state.isResolvingTemplate) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.project_template_resolving),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.resolvedConfiguration?.let { configuration ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                QuickSchemeSummary(
                    configuration = configuration,
                    modifier = Modifier.testTag(QuickCreateProjectTestTags.TEMPLATE_SUMMARY)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        state.form.selectedTemplateId?.let(onCopyTemplate)
                    },
                    enabled = state.form.templateReady,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(QuickCreateProjectTestTags.COPY_TEMPLATE)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.quick_create_copy_and_adjust),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onCreateCustomTemplate,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(QuickCreateProjectTestTags.CUSTOM_TEMPLATE)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.quick_create_custom_scheme),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showTemplatePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.quick_create_use_scheme),
            options = state.publishedTemplates.map { template ->
                ScientificPickerOption(
                    id = template.id,
                    title = template.templateName,
                    subtitle = stringResource(
                        R.string.project_template_version,
                        template.version
                    ),
                    icon = Icons.Default.Science
                )
            },
            selectedId = state.form.selectedTemplateId,
            onSelect = onSelectTemplate,
            onDismiss = { showTemplatePicker = false }
        )
    }
}

/**
 * 一行式科学方案摘要。
 *
 * 荧光通道从模板冻结的分析物配置解析，不把缺失通道静默解释为绿色；缺失配置会由模板
 * 预检阻止创建，因此摘要只呈现真实声明过的通道。
 */
@Composable
private fun QuickSchemeSummary(
    configuration: ResolvedTemplateProjectConfiguration,
    modifier: Modifier = Modifier
) {
    val snapshot = configuration.snapshot
    val listSeparator = stringResource(R.string.project_list_separator)
    val modality = detectionModalityLabel(snapshot.template.detectionMode)
    // joinToString 的 transform 不是 Composable 上下文，因此先解析资源文本，再做纯 Kotlin 映射。
    val fluorescenceChannelLabels = mapOf(
        FluorescenceChannel.RED to stringResource(
            R.string.template_wizard_fluorescence_channel_red
        ),
        FluorescenceChannel.GREEN to stringResource(
            R.string.template_wizard_fluorescence_channel_green
        ),
        FluorescenceChannel.BLUE to stringResource(
            R.string.template_wizard_fluorescence_channel_blue
        ),
        FluorescenceChannel.GRAY to stringResource(
            R.string.template_wizard_fluorescence_channel_gray
        )
    )
    val analytes = snapshot.analytes
        .map { it.analyte.name }
        .distinct()
        .joinToString(separator = listSeparator)
    val channelSummary = if (
        snapshot.template.detectionMode == DetectionModality.FLUORESCENCE.code
    ) {
        snapshot.analytes
            .mapNotNull { analyte ->
                ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                    analyte.templateConfig.displayConfigJson
                )
            }
            .distinct()
            .joinToString(separator = listSeparator) { channel ->
                fluorescenceChannelLabels.getValue(channel)
            }
    } else {
        ""
    }
    // 方案摘要属于关键信息，必须在当前屏幕完整可见；自动换行比横向滚动更适合快速核对。
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        ScientificMetadataChip(
            text = modality,
            icon = when (DetectionModality.fromCode(snapshot.template.detectionMode)) {
                DetectionModality.COLORIMETRIC -> Icons.Default.GridView
                DetectionModality.FLUORESCENCE -> Icons.Default.Flare
                DetectionModality.SPECTRUM -> Icons.Default.Science
                null -> Icons.Default.Science
            }
        )
        ScientificMetadataChip(
            text = stringResource(
                R.string.quick_create_carrier_summary,
                snapshot.carrierProfile.name,
                snapshot.carrierProfile.rows,
                snapshot.carrierProfile.columns
            ),
            icon = Icons.Default.GridView
        )
        ScientificMetadataChip(text = analytes, icon = Icons.Default.Biotech)
        if (channelSummary.isNotBlank()) {
            ScientificMetadataChip(text = channelSummary, icon = Icons.Default.Flare)
        }
    }
}

/** 可选追溯字段默认折叠，避免批次信息抢占快速主路径的视觉优先级。 */
@Composable
private fun TraceabilityFields(
    expanded: Boolean,
    projectBatch: String,
    sampleBatch: String,
    onExpandedChange: () -> Unit,
    onProjectBatchChange: (String) -> Unit,
    onSampleBatchChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandedChange),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.quick_create_traceability_optional),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = projectBatch,
                    onValueChange = onProjectBatchChange,
                    label = { Text(stringResource(R.string.project_batch_label)) },
                    leadingIcon = {
                        Icon(Icons.Default.Inventory2, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sampleBatch,
                    onValueChange = onSampleBatchChange,
                    label = { Text(stringResource(R.string.project_sample_batch_label)) },
                    leadingIcon = {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}

/** 单图终点检测的图片输入；布局保持克制，不在快速页暴露底层拍摄参数。 */
@Composable
private fun QuickProjectImage(
    imageUri: String?,
    onChoose: () -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.project_image_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (imageUri.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(onClick = onChoose),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.project_image_select))
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(205.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.04f))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(R.string.project_image_title),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.project_image_remove),
                            tint = Color.White,
                            modifier = Modifier.padding(7.dp).size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 检测模态只在 Composable 中映射资源文本，数据库继续保存稳定机器码。 */
@Composable
private fun detectionModalityLabel(code: String?): String = when (
    DetectionModality.fromCode(code)
) {
    DetectionModality.COLORIMETRIC -> stringResource(R.string.project_modality_colorimetric)
    DetectionModality.FLUORESCENCE -> stringResource(R.string.project_modality_fluorescence)
    DetectionModality.SPECTRUM -> stringResource(R.string.project_modality_spectrum)
    null -> stringResource(R.string.project_unknown_value)
}
