@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.domain.project.ProjectDetectionDestination
import com.muc.fluocolorquant.domain.project.TemplatePreflightCode
import com.muc.fluocolorquant.domain.project.TemplatePreflightIssue
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

/** Compose UI 测试使用的稳定语义标签。 */
object NewProjectTestTags {
    const val TEMPLATE_SELECTOR = "template_selector"
    const val TEMPLATE_SUMMARY = "template_summary"
    const val PROJECT_NAME_INPUT = "project_name_input"
    const val SAMPLE_GROUP_SUMMARY = "sample_group_summary"
    const val SAMPLE_SITE_EDITOR = "sample_site_editor"
    const val PREFLIGHT_ISSUES = "preflight_issue_list"
    const val CREATE_BUTTON = "create_project_button"
}

/** 页面动作集中建模，便于纯内容函数独立做 Compose 测试。 */
sealed interface TemplateProjectAction {
    data object NavigateBack : TemplateProjectAction
    data object OpenTemplateLibrary : TemplateProjectAction
    data object ChooseImage : TemplateProjectAction
    data object RemoveImage : TemplateProjectAction
    data object CreateProject : TemplateProjectAction
    data class SelectTemplate(val templateId: String) : TemplateProjectAction
    data class ChangeProjectName(val value: String) : TemplateProjectAction
    data class ChangeProjectBatch(val value: String) : TemplateProjectAction
    data class ChangeSampleBatch(val value: String) : TemplateProjectAction
    data class ApplySampleSlot(val siteKeys: List<String>, val value: String) : TemplateProjectAction
}

/**
 * 新建项目页面外壳：负责导航、图片来源、登录会话和一次性 Toast；所有可测试布局由
 * [TemplateProjectContent] 承担。
 */
@Composable
fun NewProjectScreen(
    navController: NavController,
    projectViewModel: ProjectViewModel = hiltViewModel(),
    userViewModel: UserViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val toastManager = LocalToastManager.current
    val state by projectViewModel.uiState.collectAsState()
    val currentUser by userViewModel.currentUser.collectAsState()
    var showImageSourceDialog by rememberSaveable { mutableStateOf(false) }

    val creationSuccess = stringResource(R.string.project_creation_success)
    val formIncomplete = stringResource(R.string.project_form_incomplete_toast)
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
                        ProjectDetectionDestination.GRID_ENDPOINT -> {
                            navController.navigate(
                                Screen.WellDetection.createRoute(
                                    imageUri = Uri.encode(event.imageUri),
                                    projectId = event.projectId
                                )
                            )
                        }

                        ProjectDetectionDestination.SPECTRUM_SINGLE -> {
                            navController.navigate(
                                Screen.SpectrumCalibration.createRoute(
                                    projectId = event.projectId,
                                    imageUri = event.imageUri
                                )
                            )
                        }

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

    // 裁剪页面把结果写回当前返回栈条目；消费后立即清除，避免重组时重复覆盖图片。
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

    TemplateProjectContent(
        state = state,
        onAction = { action ->
            when (action) {
                TemplateProjectAction.NavigateBack -> navController.navigateUp()
                TemplateProjectAction.OpenTemplateLibrary ->
                    navController.navigate(Screen.ExperimentTemplateManagement.route)
                TemplateProjectAction.ChooseImage -> showImageSourceDialog = true
                TemplateProjectAction.RemoveImage -> projectViewModel.updateImageUri(null)
                TemplateProjectAction.CreateProject -> {
                    val userId = currentUser?.id?.toString().orEmpty()
                    if (userId.isBlank()) {
                        toastManager.showToast(sessionInvalid, ToastType.ERROR)
                    } else {
                        projectViewModel.createProject(userId)
                    }
                }
                is TemplateProjectAction.SelectTemplate -> projectViewModel.selectTemplate(action.templateId)
                is TemplateProjectAction.ChangeProjectName -> projectViewModel.updateProjectName(action.value)
                is TemplateProjectAction.ChangeProjectBatch -> projectViewModel.updateProjectBatch(action.value)
                is TemplateProjectAction.ChangeSampleBatch -> projectViewModel.updateSampleBatch(action.value)
                is TemplateProjectAction.ApplySampleSlot ->
                    projectViewModel.applySampleSlotToSites(action.siteKeys, action.value)
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

/** 可独立测试的模板优先页面内容。 */
@Composable
fun TemplateProjectContent(
    state: ProjectUiState,
    onAction: (TemplateProjectAction) -> Unit
) {
    val scrollState = rememberScrollState()
    var showSampleEditor by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_project_title)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(TemplateProjectAction.NavigateBack) }) {
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TemplateSelectorCard(state, onAction)

            state.resolvedConfiguration?.let { configuration ->
                TemplateLockedSummary(
                    configuration = configuration,
                    modifier = Modifier.testTag(NewProjectTestTags.TEMPLATE_SUMMARY)
                )
            }

            RunInformationCard(state, onAction)
            if (state.form.requiredSampleSites.isNotEmpty()) {
                SampleMappingCard(
                    state = state,
                    expanded = showSampleEditor,
                    onToggleExpanded = { showSampleEditor = !showSampleEditor },
                    onAction = onAction
                )
            }
            ProjectImageCard(state, onAction)
            PreflightCard(state)

            Button(
                onClick = { onAction(TemplateProjectAction.CreateProject) },
                enabled = state.form.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(NewProjectTestTags.CREATE_BUTTON),
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
                    Text(stringResource(R.string.project_create_button))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun TemplateSelectorCard(
    state: ProjectUiState,
    onAction: (TemplateProjectAction) -> Unit
) {
    var showTemplatePicker by rememberSaveable { mutableStateOf(false) }
    val selected = state.publishedTemplates.firstOrNull {
        it.id == state.form.selectedTemplateId
    }
    SectionCard {
        SectionHeader(
            icon = Icons.Default.Science,
            title = stringResource(R.string.project_template_select_label)
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.isLoadingTemplates -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.project_template_loading))
                }
            }

            state.publishedTemplates.isEmpty() -> {
                EmptyTemplateState(onAction)
            }

            else -> {
                ScientificSelectionField(
                    label = null,
                    value = selected?.templateName,
                    supportingValue = selected?.let {
                        stringResource(R.string.project_template_version, it.version)
                    },
                    placeholder = stringResource(R.string.project_template_select_support_short),
                    icon = Icons.Default.Science,
                    onClick = { showTemplatePicker = true },
                    modifier = Modifier.testTag(NewProjectTestTags.TEMPLATE_SELECTOR)
                )
                if (state.isResolvingTemplate) {
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.project_template_resolving),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (showTemplatePicker) {
        ScientificPickerSheet(
            title = stringResource(R.string.project_template_select_label),
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
            onSelect = { templateId ->
                onAction(TemplateProjectAction.SelectTemplate(templateId))
            },
            onDismiss = { showTemplatePicker = false }
        )
    }
}

@Composable
private fun EmptyTemplateState(onAction: (TemplateProjectAction) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.project_no_published_templates_title),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.project_no_published_templates_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = { onAction(TemplateProjectAction.OpenTemplateLibrary) }) {
                Text(stringResource(R.string.project_open_template_library))
            }
        }
    }
}

@Composable
private fun TemplateLockedSummary(
    configuration: com.muc.fluocolorquant.domain.project.ResolvedTemplateProjectConfiguration,
    modifier: Modifier = Modifier
) {
    val snapshot = configuration.snapshot
    val listSeparator = stringResource(R.string.project_list_separator)
    SectionCard(modifier) {
        SectionHeader(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.project_template_locked_title)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ScientificMetadataChip(
                text = modalityLabel(snapshot.template.detectionMode),
                icon = Icons.Default.Science
            )
            ScientificMetadataChip(
                text = protocolLabel(snapshot.template.inputProtocol),
                icon = Icons.Default.CheckCircle
            )
            ScientificMetadataChip(
                text = stringResource(
                    R.string.project_template_carrier_compact,
                    snapshot.carrierProfile.name,
                    snapshot.carrierProfile.rows,
                    snapshot.carrierProfile.columns
                ),
                icon = Icons.Default.GridView
            )
            ScientificMetadataChip(
                text = snapshot.acquisitionProfile.name,
                icon = Icons.Default.CameraAlt
            )
            ScientificMetadataChip(
                text = snapshot.analytes.joinToString(separator = listSeparator) {
                    it.analyte.name
                },
                icon = Icons.Default.Biotech
            )
            ScientificMetadataChip(
                text = snapshot.analytes.joinToString(separator = listSeparator) {
                    it.analysisModel.model.name
                },
                icon = Icons.Default.Lock
            )
        }
    }
}

@Composable
private fun RunInformationCard(
    state: ProjectUiState,
    onAction: (TemplateProjectAction) -> Unit
) {
    var showTraceabilityFields by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        SectionHeader(
            icon = Icons.Default.FolderOpen,
            title = stringResource(R.string.project_form_title)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.form.projectName,
            onValueChange = { onAction(TemplateProjectAction.ChangeProjectName(it)) },
            label = { Text(stringResource(R.string.project_name_label)) },
            leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().testTag(NewProjectTestTags.PROJECT_NAME_INPUT),
            singleLine = true
        )
        TextButton(
            onClick = { showTraceabilityFields = !showTraceabilityFields },
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.Inventory2, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.quick_create_traceability_optional))
            Icon(
                if (showTraceabilityFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
        if (showTraceabilityFields) {
            OutlinedTextField(
                value = state.form.projectBatch,
                onValueChange = { onAction(TemplateProjectAction.ChangeProjectBatch(it)) },
                label = { Text(stringResource(R.string.project_batch_label)) },
                leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.form.sampleBatch,
                onValueChange = { onAction(TemplateProjectAction.ChangeSampleBatch(it)) },
                label = { Text(stringResource(R.string.project_sample_batch_label)) },
                leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun SampleMappingCard(
    state: ProjectUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAction: (TemplateProjectAction) -> Unit
) {
    val listSeparator = stringResource(R.string.project_list_separator)
    SectionCard {
        SectionHeader(
            icon = Icons.Default.Hub,
            title = stringResource(R.string.project_sample_mapping_title)
        )
        Spacer(Modifier.height(12.dp))
        if (state.form.requiredSampleSites.isEmpty()) {
            Text(
                stringResource(R.string.project_sample_mapping_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        Column(
            modifier = Modifier.testTag(NewProjectTestTags.SAMPLE_GROUP_SUMMARY),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.project_sample_group_count,
                    state.form.sampleGroups.size,
                    state.form.requiredSampleSites.size
                ),
                fontWeight = FontWeight.SemiBold
            )
            state.form.sampleGroups.take(6).forEach { group ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            group.sampleSlot,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(
                                R.string.project_sample_group_sites,
                                group.siteKeys.size,
                                group.analyteNames.joinToString(separator = listSeparator)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state.form.missingSampleSiteKeys.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.project_sample_mapping_missing,
                        state.form.missingSampleSiteKeys.size
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        TextButton(onClick = onToggleExpanded, modifier = Modifier.align(Alignment.End)) {
            Text(
                stringResource(
                    if (expanded) R.string.project_sample_mapping_hide
                    else R.string.project_sample_mapping_manage
                )
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }

        if (expanded) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag(NewProjectTestTags.SAMPLE_SITE_EDITOR),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.form.sampleGroups.forEach { group ->
                    OutlinedTextField(
                        value = group.sampleSlot,
                        onValueChange = {
                            onAction(TemplateProjectAction.ApplySampleSlot(group.siteKeys, it))
                        },
                        label = { Text(stringResource(R.string.project_sample_slot_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.project_sample_group_sites,
                                    group.siteKeys.size,
                                    group.analyteNames.joinToString(separator = listSeparator)
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                state.form.requiredSampleSites
                    .filter { it.siteKey in state.form.missingSampleSiteKeys }
                    .forEach { site ->
                        OutlinedTextField(
                            value = state.form.sampleSlotMapping[site.siteKey].orEmpty(),
                            onValueChange = {
                                onAction(
                                    TemplateProjectAction.ApplySampleSlot(
                                        listOf(site.siteKey),
                                        it
                                    )
                                )
                            },
                            label = { Text(stringResource(R.string.project_sample_slot_label)) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        R.string.project_sample_site_label,
                                        site.siteKey,
                                        site.analyteName
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
            }
        }
    }
}

@Composable
private fun ProjectImageCard(
    state: ProjectUiState,
    onAction: (TemplateProjectAction) -> Unit
) {
    val isSpectrum = state.resolvedConfiguration
        ?.snapshot
        ?.template
        ?.detectionMode == DetectionModality.SPECTRUM.code
    val titleRes = if (isSpectrum) R.string.project_spectrum_image_title
    else R.string.project_image_title
    SectionCard {
        SectionHeader(
            icon = Icons.Default.Image,
            title = stringResource(titleRes)
        )
        Spacer(Modifier.height(12.dp))
        val imageUri = state.form.imageUri
        if (imageUri.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onAction(TemplateProjectAction.ChooseImage) },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
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
                    .height(220.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.04f))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = stringResource(titleRes),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { onAction(TemplateProjectAction.RemoveImage) },
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
            TextButton(
                onClick = { onAction(TemplateProjectAction.ChooseImage) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.project_image_change))
            }
        }
    }
}

@Composable
private fun PreflightCard(state: ProjectUiState) {
    val issues = state.form.preflightIssues.distinct()
    if (issues.isEmpty()) return

    SectionCard {
        SectionHeader(
            icon = Icons.Default.WarningAmber,
            title = stringResource(R.string.project_preflight_title)
        )
        Spacer(Modifier.height(10.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = stringResource(R.string.project_preflight_blocked_title),
                    fontWeight = FontWeight.SemiBold
                )
                if (issues.isNotEmpty()) {
                    Column(
                        modifier = Modifier.testTag(NewProjectTestTags.PREFLIGHT_ISSUES),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        issues.forEach { issue ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    stringResource(preflightIssueMessage(issue.code)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    ScientificSectionTitle(title = title, icon = icon)
}

@Composable
private fun modalityLabel(code: String?): String = when (code) {
    DetectionModality.COLORIMETRIC.code -> stringResource(R.string.project_modality_colorimetric)
    DetectionModality.FLUORESCENCE.code -> stringResource(R.string.project_modality_fluorescence)
    DetectionModality.SPECTRUM.code -> stringResource(R.string.project_modality_spectrum)
    else -> stringResource(R.string.project_unknown_value)
}

@Composable
private fun protocolLabel(code: String?): String = when (code) {
    InputProtocol.ENDPOINT_ONLY.code -> stringResource(R.string.project_protocol_endpoint)
    InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code -> stringResource(R.string.project_protocol_single_spectrum)
    InputProtocol.LSPR_PAIRED_QUANTIFICATION.code -> stringResource(R.string.project_protocol_lspr)
    else -> stringResource(R.string.project_unknown_value)
}

private fun preflightIssueMessage(code: TemplatePreflightCode): Int = when (code) {
    TemplatePreflightCode.TEMPLATE_NOT_FOUND,
    TemplatePreflightCode.TEMPLATE_NOT_PUBLISHED -> R.string.project_preflight_issue_template
    TemplatePreflightCode.CARRIER_MISSING,
    TemplatePreflightCode.CARRIER_ARCHIVED -> R.string.project_preflight_issue_carrier
    TemplatePreflightCode.ACQUISITION_MISSING,
    TemplatePreflightCode.ACQUISITION_ARCHIVED,
    TemplatePreflightCode.ACQUISITION_MODALITY_MISMATCH,
    TemplatePreflightCode.ACQUISITION_CARRIER_MISMATCH -> R.string.project_preflight_issue_acquisition
    TemplatePreflightCode.ANALYTE_CONFIG_MISSING,
    TemplatePreflightCode.ANALYTE_MISSING -> R.string.project_preflight_issue_analyte
    TemplatePreflightCode.ANALYSIS_MODEL_MISSING,
    TemplatePreflightCode.ANALYSIS_MODEL_NOT_PUBLISHED -> R.string.project_preflight_issue_model
    TemplatePreflightCode.MODEL_ANALYTE_MISMATCH,
    TemplatePreflightCode.MODEL_MODALITY_MISMATCH,
    TemplatePreflightCode.MODEL_PROTOCOL_MISMATCH,
    TemplatePreflightCode.MODEL_CARRIER_MISMATCH,
    TemplatePreflightCode.MODEL_ACQUISITION_MISMATCH ->
        R.string.project_preflight_issue_model_compatibility
    TemplatePreflightCode.MODEL_UNIT_MISMATCH,
    TemplatePreflightCode.MODEL_RELIABLE_RANGE_MISMATCH ->
        R.string.project_preflight_issue_model_range
    TemplatePreflightCode.LAYOUT_INCOMPLETE,
    TemplatePreflightCode.LAYOUT_OUT_OF_BOUNDS -> R.string.project_preflight_issue_layout
    TemplatePreflightCode.SITE_ANALYTE_UNKNOWN -> R.string.project_preflight_issue_site
    TemplatePreflightCode.UNSUPPORTED_DETECTION_ROUTE -> R.string.project_preflight_issue_route
    TemplatePreflightCode.PROJECT_NAME_MISSING -> R.string.project_preflight_issue_name
    TemplatePreflightCode.IMAGE_MISSING -> R.string.project_preflight_issue_image
    TemplatePreflightCode.USER_MISSING -> R.string.project_preflight_issue_user
    TemplatePreflightCode.SAMPLE_MAPPING_INCOMPLETE -> R.string.project_preflight_issue_sample
}
