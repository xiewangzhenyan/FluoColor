@file:OptIn(ExperimentalMaterial3Api::class)

package com.muc.fluocolorquant.ui.screens.image

import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.util.Range
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.CameraCaptureEffect
import com.muc.fluocolorquant.ui.viewmodels.CameraCaptureViewModel
import com.muc.fluocolorquant.utils.camera.CameraCaptureDefaults
import com.muc.fluocolorquant.utils.camera.CameraCaptureCapabilitiesSnapshot
import com.muc.fluocolorquant.utils.camera.CameraZoomSnapshot
import com.muc.fluocolorquant.utils.camera.FixedCameraCaptureRequest
import com.muc.fluocolorquant.utils.camera.toFixedCameraCaptureRequest
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun CameraCaptureScreen(
    navController: NavController,
    outputPath: String?,
    captureMode: String?,
    expectedSpectrumTracks: Int,
    viewModel: CameraCaptureViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toastManager = LocalToastManager.current
    val uiState by viewModel.uiState.collectAsState()

    val awbAutoLabel = stringResource(R.string.camera_capture_awb_auto)
    val awbAutoLockLabel = stringResource(R.string.camera_capture_awb_auto_lock)
    val compactAwbAutoLabel = stringResource(R.string.camera_capture_compact_wb_auto_value)
    val compactAwbLockLabel = stringResource(R.string.camera_capture_compact_wb_lock_value)
    val captureLockedLabel = stringResource(R.string.camera_capture_status_locked_short)
    val captureFallbackLabel = stringResource(R.string.camera_capture_status_fallback_short)
    val zoomLabel = stringResource(R.string.camera_capture_compact_zoom_label)
    val qualityIssueOverExposed = stringResource(R.string.spectrum_quality_issue_over_exposed)
    val qualityIssueUnderExposed = stringResource(R.string.spectrum_quality_issue_under_exposed)
    val qualityIssueBlurred = stringResource(R.string.spectrum_quality_issue_blurred)
    val qualityIssueTilted = stringResource(R.string.spectrum_quality_issue_tilted)
    val qualityIssueMergedChannels = stringResource(R.string.spectrum_quality_issue_merged_channels)
    val qualityIssueUnevenBackground = stringResource(R.string.spectrum_quality_issue_uneven_background)

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(outputPath, captureMode, expectedSpectrumTracks) {
        viewModel.initialize(
            outputPath = outputPath,
            captureMode = captureMode,
            expectedSpectrumTracks = expectedSpectrumTracks
        )
    }

    LaunchedEffect(previewView, lifecycleOwner, uiState.outputPath) {
        if (!uiState.outputPath.isNullOrBlank()) {
            viewModel.bindCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = previewView
            )
        }
    }

    DisposableEffect(previewView, viewModel) {
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    viewModel.adjustZoomBy(detector.scaleFactor)
                    return true
                }
            }
        )
        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    viewModel.focusAt(previewView, e.x, e.y)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }
            true
        }

        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    LaunchedEffect(viewModel, context, navController, toastManager) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CameraCaptureEffect.NavigateBack -> {
                    navController.popBackStack()
                }

                is CameraCaptureEffect.NavigateToCrop -> {
                    navController.navigate(
                        "${Screen.ImageCrop.route}?imageUri=${Uri.encode(effect.imageUri)}"
                    ) {
                        popUpTo(Screen.ImageCapture.route) { inclusive = true }
                    }
                }

                is CameraCaptureEffect.ShowToast -> {
                    toastManager.showToast(
                        effect.message.asString(context),
                        effect.type
                    )
                }
            }
        }
    }

    val currentCaptureRequest = uiState.appliedRequest ?: uiState.controlState.toFixedCameraCaptureRequest()
    val compactWhiteBalanceLabel = if (currentCaptureRequest.requestAwbLock) {
        compactAwbLockLabel
    } else {
        compactAwbAutoLabel
    }
    val awbModeLabel = if (currentCaptureRequest.requestAwbLock) {
        awbAutoLockLabel
    } else {
        awbAutoLabel
    }
    val captureModeLabel = if (uiState.capabilities?.appliedManualSensor == true) {
        captureLockedLabel
    } else {
        captureFallbackLabel
    }
    val zoomRatioLabel = formatZoomRatio(uiState.zoomSnapshot.zoomRatio)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        CameraGuideOverlay(
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(196.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.46f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(250.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.22f),
                            Color.Black.copy(alpha = 0.54f)
                        )
                    )
                )
        )

        CameraOverlayTopBar(
            title = stringResource(R.string.camera_capture_title),
            onBack = { navController.popBackStack() },
            onInfo = { viewModel.setInfoDialogVisible(true) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .statusBarsPadding()
        )

        uiState.bindError?.asString(context)?.let { message ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp, start = 20.dp, end = 20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }

        CameraBottomControls(
            request = currentCaptureRequest,
            captureModeLabel = captureModeLabel,
            compactWhiteBalanceLabel = compactWhiteBalanceLabel,
            zoomRatioLabel = zoomRatioLabel,
            zoomLabel = zoomLabel,
            isSaving = uiState.isSaving || uiState.isBinding,
            onOpenAdvanced = { viewModel.setAdvancedSheetVisible(true) },
            onCapture = viewModel::captureImage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .navigationBarsPadding()
        )
    }

    if (uiState.showInfoDialog) {
        CaptureInfoDialog(
            request = currentCaptureRequest,
            awbModeLabel = awbModeLabel,
            capabilities = uiState.capabilities,
            onDismiss = { viewModel.setInfoDialogVisible(false) }
        )
    }

    if (uiState.showAdvancedSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setAdvancedSheetVisible(false) },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null
        ) {
            AdvancedCaptureSettingsSheet(
                request = currentCaptureRequest,
                awbModeLabel = awbModeLabel,
                capabilities = uiState.capabilities,
                zoomSnapshot = uiState.zoomSnapshot,
                onDismiss = { viewModel.setAdvancedSheetVisible(false) },
                onIsoChange = viewModel::updateIso,
                onExposureTimeChange = viewModel::updateExposureTime,
                onExposureCompensationChange = viewModel::updateExposureCompensation,
                onAwbLockChange = viewModel::updateAwbLock,
                onZoomRatioChange = viewModel::updateZoomRatio,
                onResetDefaults = viewModel::resetDefaults
            )
        }
    }

    uiState.pendingSpectrumQualityReview?.let { review ->
        val qualityStatus = when (review.report.decision) {
            com.muc.fluocolorquant.utils.math.SpectrumImageQualityDecision.PASS ->
                stringResource(R.string.spectrum_quality_status_good)
            com.muc.fluocolorquant.utils.math.SpectrumImageQualityDecision.REVIEW ->
                stringResource(R.string.spectrum_quality_status_review)
            com.muc.fluocolorquant.utils.math.SpectrumImageQualityDecision.RETAKE ->
                stringResource(R.string.spectrum_quality_status_retake)
        }
        val issueSummary = review.report.issues.distinct().joinToString(separator = " / ") { issue ->
            when (issue) {
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.OVER_EXPOSED ->
                    qualityIssueOverExposed
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.UNDER_EXPOSED ->
                    qualityIssueUnderExposed
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.BLURRED ->
                    qualityIssueBlurred
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.TILTED ->
                    qualityIssueTilted
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.MERGED_CHANNELS ->
                    qualityIssueMergedChannels
                com.muc.fluocolorquant.utils.math.SpectrumImageQualityIssueType.UNEVEN_BACKGROUND ->
                    qualityIssueUnevenBackground
            }
        }

        AlertDialog(
            onDismissRequest = viewModel::dismissSpectrumQualityReview,
            title = {
                Text(
                    text = stringResource(R.string.camera_capture_quality_dialog_title)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.camera_capture_quality_dialog_status,
                            qualityStatus,
                            review.report.score
                        )
                    )
                    if (issueSummary.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.camera_capture_quality_dialog_issues,
                                issueSummary
                            )
                        )
                    }
                    Text(text = stringResource(R.string.camera_capture_quality_dialog_desc))
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::continueAfterSpectrumQualityReview) {
                    Text(text = stringResource(R.string.camera_capture_quality_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::retakeAfterSpectrumQualityReview) {
                    Text(text = stringResource(R.string.camera_capture_quality_retake))
                }
            }
        )
    }
}

@Composable
private fun CameraGuideOverlay(
    modifier: Modifier = Modifier
) {
    val guideColor = Color.White.copy(alpha = 0.28f)
    Canvas(
        modifier = modifier.size(220.dp)
    ) {
        val strokeWidth = 3f
        val gridStroke = 1.2f
        val width = size.width
        val height = size.height
        val thirdX = width / 3f
        val thirdY = height / 3f

        drawRoundRect(
            color = guideColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f, 22f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
        )
        drawLine(
            color = guideColor.copy(alpha = 0.52f),
            start = androidx.compose.ui.geometry.Offset(thirdX, 0f),
            end = androidx.compose.ui.geometry.Offset(thirdX, height),
            strokeWidth = gridStroke
        )
        drawLine(
            color = guideColor.copy(alpha = 0.52f),
            start = androidx.compose.ui.geometry.Offset(thirdX * 2f, 0f),
            end = androidx.compose.ui.geometry.Offset(thirdX * 2f, height),
            strokeWidth = gridStroke
        )
        drawLine(
            color = guideColor.copy(alpha = 0.52f),
            start = androidx.compose.ui.geometry.Offset(0f, thirdY),
            end = androidx.compose.ui.geometry.Offset(width, thirdY),
            strokeWidth = gridStroke
        )
        drawLine(
            color = guideColor.copy(alpha = 0.52f),
            start = androidx.compose.ui.geometry.Offset(0f, thirdY * 2f),
            end = androidx.compose.ui.geometry.Offset(width, thirdY * 2f),
            strokeWidth = gridStroke
        )
    }
}

@Composable
private fun CameraOverlayTopBar(
    title: String,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OverlayIconButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            onClick = onBack
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        OverlayIconButton(
            imageVector = Icons.Filled.Info,
            contentDescription = stringResource(R.string.camera_capture_info_action),
            onClick = onInfo
        )
    }
}

@Composable
private fun OverlayIconButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.32f)
        )
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = Color.White
            )
        }
    }
}

@Composable
private fun CameraBottomControls(
    request: FixedCameraCaptureRequest,
    captureModeLabel: String,
    compactWhiteBalanceLabel: String,
    zoomRatioLabel: String,
    zoomLabel: String,
    isSaving: Boolean,
    onOpenAdvanced: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipScrollState = rememberScrollState()

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusBadge(value = captureModeLabel, highlighted = true)
            StatusBadge(
                label = zoomLabel,
                value = zoomRatioLabel
            )
            StatusBadge(
                label = stringResource(R.string.camera_capture_compact_iso_label),
                value = request.requestedIso.toString()
            )
            StatusBadge(
                label = stringResource(R.string.camera_capture_compact_exposure_label),
                value = "${formatExposureTimeMillis(request.requestedExposureTimeNs)} ms"
            )
            StatusBadge(
                label = stringResource(R.string.camera_capture_compact_ev_label),
                value = formatSignedIndex(request.requestedExposureCompensationIndex)
            )
            StatusBadge(
                label = stringResource(R.string.camera_capture_compact_wb_label),
                value = compactWhiteBalanceLabel
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onOpenAdvanced,
                modifier = Modifier.height(58.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.camera_capture_advanced_action),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Button(
                onClick = onCapture,
                enabled = !isSaving,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.camera_capture_shutter),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(
    value: String,
    label: String? = null,
    highlighted: Boolean = false
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.84f)
            } else {
                Color.Black.copy(alpha = 0.30f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                Color.White.copy(alpha = 0.10f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        Color.White.copy(alpha = 0.74f)
                    }
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    Color.White
                }
            )
        }
    }
}

@Composable
private fun CaptureInfoDialog(
    request: FixedCameraCaptureRequest,
    awbModeLabel: String,
    capabilities: CameraCaptureCapabilitiesSnapshot?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        title = {
            Text(text = stringResource(R.string.camera_capture_fixed_settings_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.camera_capture_settings_summary),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.camera_capture_iso_value,
                        request.requestedIso
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.camera_capture_exposure_time_value,
                        formatExposureTimeMillis(request.requestedExposureTimeNs)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.camera_capture_exposure_compensation_value,
                        request.requestedExposureCompensationIndex
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(
                        R.string.camera_capture_awb_value,
                        awbModeLabel
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                capabilities?.let { snapshot ->
                    Text(
                        text = if (snapshot.appliedManualSensor) {
                            stringResource(R.string.camera_capture_manual_sensor_enabled)
                        } else {
                            stringResource(R.string.camera_capture_manual_sensor_fallback)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.camera_capture_metadata_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun AdvancedCaptureSettingsSheet(
    request: FixedCameraCaptureRequest,
    awbModeLabel: String,
    capabilities: CameraCaptureCapabilitiesSnapshot?,
    zoomSnapshot: CameraZoomSnapshot,
    onDismiss: () -> Unit,
    onIsoChange: (Int) -> Unit,
    onExposureTimeChange: (Long) -> Unit,
    onExposureCompensationChange: (Int) -> Unit,
    onAwbLockChange: (Boolean) -> Unit,
    onZoomRatioChange: (Float) -> Unit,
    onResetDefaults: () -> Unit
) {
    val contentScrollState = rememberScrollState()
    var showCapabilityHelp by remember { mutableStateOf(false) }
    val isoOptions = remember(capabilities?.sensorIsoRange) {
        buildIntOptions(
            range = capabilities?.sensorIsoRange,
            defaultValue = CameraCaptureDefaults.ISO,
            targetCount = 13
        )
    }
    val exposureOptions = remember(capabilities?.sensorExposureTimeRangeNs) {
        buildLongOptions(
            range = capabilities?.sensorExposureTimeRangeNs,
            defaultValue = CameraCaptureDefaults.EXPOSURE_TIME_NS,
            targetCount = 13
        )
    }
    val evOptions = remember(capabilities?.exposureCompensationRange) {
        buildIntOptions(
            range = capabilities?.exposureCompensationRange ?: (0..0),
            defaultValue = CameraCaptureDefaults.EXPOSURE_COMPENSATION_INDEX,
            targetCount = 9
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(contentScrollState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 8.dp)
                    .background(Color.Transparent)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(Color.Transparent)
                        .padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.camera_capture_advanced_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(onClick = onDismiss) {
                            Text(text = stringResource(android.R.string.ok))
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .background(Color.Transparent),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (capabilities?.appliedManualSensor == true) {
                                    stringResource(R.string.camera_capture_manual_sensor_enabled)
                                } else {
                                    stringResource(R.string.camera_capture_manual_sensor_fallback_compact)
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            // 设备能力属于按需排查信息：默认仅显示紧凑状态，避免高级设置首屏
                            // 被两段重复说明占满；用户主动点击后再查看完整兼容性解释。
                            if (capabilities != null && capabilities.appliedManualSensor != true) {
                                IconButton(onClick = { showCapabilityHelp = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.Info,
                                        contentDescription = stringResource(
                                            R.string.camera_capture_manual_sensor_help_action
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    CaptureSettingsGroupCard(
                        title = stringResource(R.string.camera_capture_group_basic_title),
                        description = stringResource(R.string.camera_capture_group_basic_desc)
                    ) {
                        ZoomSliderSection(
                            title = stringResource(R.string.camera_capture_zoom_section_title),
                            valueText = stringResource(
                                R.string.camera_capture_zoom_value,
                                formatZoomRatio(zoomSnapshot.zoomRatio)
                            ),
                            rangeText = stringResource(
                                R.string.camera_capture_supported_range_format,
                                "${formatZoomRatio(zoomSnapshot.minZoomRatio)} - ${formatZoomRatio(zoomSnapshot.maxZoomRatio)}"
                            ),
                            zoomRatio = zoomSnapshot.zoomRatio,
                            minZoomRatio = zoomSnapshot.minZoomRatio,
                            maxZoomRatio = zoomSnapshot.maxZoomRatio,
                            onZoomRatioChange = onZoomRatioChange
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        DialSliderSection(
                            title = stringResource(R.string.camera_capture_exposure_compensation_section_title),
                            valueText = stringResource(
                                R.string.camera_capture_exposure_compensation_value,
                                request.requestedExposureCompensationIndex
                            ),
                            rangeText = capabilities?.exposureCompensationRange?.let {
                                stringResource(
                                    R.string.camera_capture_supported_range_format,
                                    "${formatSignedIndex(it.first)} - ${formatSignedIndex(it.last)}"
                                )
                            },
                            optionLabels = evOptions.map(::formatSignedIndex),
                            selectedIndex = optionIndexOf(evOptions, request.requestedExposureCompensationIndex),
                            onSelectedIndexChange = { index ->
                                onExposureCompensationChange(evOptions[index])
                            }
                        )
                    }

                    CaptureSettingsGroupCard(
                        title = stringResource(R.string.camera_capture_group_lock_title),
                        description = stringResource(R.string.camera_capture_group_lock_desc)
                    ) {
                        if (capabilities?.supportsManualSensor == true) {
                            DialSliderSection(
                                title = stringResource(R.string.camera_capture_iso_section_title),
                                valueText = stringResource(
                                    R.string.camera_capture_iso_value,
                                    request.requestedIso
                                ),
                                rangeText = capabilities.sensorIsoRange?.let {
                                    stringResource(
                                        R.string.camera_capture_supported_range_format,
                                        "${it.first} - ${it.last}"
                                    )
                                },
                                optionLabels = isoOptions.map(Int::toString),
                                selectedIndex = optionIndexOf(isoOptions, request.requestedIso),
                                onSelectedIndexChange = { index ->
                                    onIsoChange(isoOptions[index])
                                }
                            )

                            Spacer(modifier = Modifier.height(18.dp))

                            DialSliderSection(
                                title = stringResource(R.string.camera_capture_exposure_section_title),
                                valueText = stringResource(
                                    R.string.camera_capture_exposure_time_value,
                                    formatExposureTimeMillis(request.requestedExposureTimeNs)
                                ),
                                rangeText = capabilities.sensorExposureTimeRangeNs?.let {
                                    stringResource(
                                        R.string.camera_capture_supported_range_format,
                                        "${formatExposureTimeMillis(it.first)} - ${formatExposureTimeMillis(it.last)} ms"
                                    )
                                },
                                optionLabels = exposureOptions.map { "${formatExposureTimeMillis(it)} ms" },
                                selectedIndex = optionIndexOf(exposureOptions, request.requestedExposureTimeNs),
                                onSelectedIndexChange = { index ->
                                    onExposureTimeChange(exposureOptions[index])
                                }
                            )

                            Spacer(modifier = Modifier.height(18.dp))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                            ),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.camera_capture_awb_lock_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = awbModeLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = request.requestAwbLock,
                                    onCheckedChange = onAwbLockChange,
                                    enabled = capabilities?.supportsAwbLock != false
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = onResetDefaults,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.camera_capture_reset_defaults))
                    }
                }
            }
        }
    }

    if (showCapabilityHelp) {
        AlertDialog(
            onDismissRequest = { showCapabilityHelp = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null
                )
            },
            title = {
                Text(stringResource(R.string.camera_capture_manual_sensor_help_title))
            },
            text = {
                Text(
                    stringResource(
                        if (capabilities?.supportsManualSensor == false) {
                            R.string.camera_capture_manual_controls_unavailable
                        } else {
                            R.string.camera_capture_manual_sensor_fallback
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showCapabilityHelp = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun CaptureSettingsGroupCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    var showDescription by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { showDescription = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(
                            R.string.camera_capture_group_help_action,
                            title
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }

    if (showDescription) {
        AlertDialog(
            onDismissRequest = { showDescription = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null
                )
            },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = { showDescription = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun ZoomSliderSection(
    title: String,
    valueText: String,
    rangeText: String,
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onZoomRatioChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = rangeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = zoomRatio,
                onValueChange = onZoomRatioChange,
                valueRange = minZoomRatio..maxZoomRatio,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        }
    }
}

@Composable
private fun DialSliderSection(
    title: String,
    valueText: String,
    rangeText: String?,
    optionLabels: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    val safeSelectedIndex = selectedIndex.coerceIn(0, max(optionLabels.lastIndex, 0))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
        ),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            rangeText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DialTicks(
                optionCount = optionLabels.size,
                selectedIndex = safeSelectedIndex
            )

            Slider(
                value = safeSelectedIndex.toFloat(),
                onValueChange = { value ->
                    onSelectedIndexChange(value.roundToInt().coerceIn(0, optionLabels.lastIndex))
                },
                valueRange = 0f..optionLabels.lastIndex.coerceAtLeast(0).toFloat(),
                steps = optionLabels.size.coerceAtLeast(2) - 2,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = optionLabels.firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = optionLabels.getOrNull(optionLabels.lastIndex / 2).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = optionLabels.lastOrNull().orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DialTicks(
    optionCount: Int,
    selectedIndex: Int
) {
    val safeCount = max(optionCount, 2)
    val selectedColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val spacing = size.width / (safeCount - 1)
        val centerY = size.height / 2f
        val majorHeight = size.height * 0.65f
        val minorHeight = size.height * 0.36f

        for (index in 0 until safeCount) {
            val isSelected = index == selectedIndex
            val isMajor = index % 2 == 0
            val tickHeight = if (isMajor) majorHeight else minorHeight
            val x = spacing * index
            drawLine(
                color = if (isSelected) selectedColor else tickColor,
                start = androidx.compose.ui.geometry.Offset(x, centerY - tickHeight / 2f),
                end = androidx.compose.ui.geometry.Offset(x, centerY + tickHeight / 2f),
                strokeWidth = if (isSelected) 6f else 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun buildIntOptions(
    range: IntRange?,
    defaultValue: Int,
    targetCount: Int
): List<Int> {
    val safeRange = range ?: (defaultValue..defaultValue)
    if (safeRange.first == safeRange.last) return listOf(safeRange.first)

    val stepCount = max(targetCount - 1, 1)
    val step = max((safeRange.last - safeRange.first) / stepCount, 1)
    val values = mutableListOf<Int>()
    var current = safeRange.first
    while (current < safeRange.last) {
        values += current
        current += step
    }
    values += safeRange.last
    if (defaultValue in safeRange) {
        values += defaultValue
    }
    return values.distinct().sorted()
}

private fun buildLongOptions(
    range: LongRange?,
    defaultValue: Long,
    targetCount: Int
): List<Long> {
    val safeRange = range ?: (defaultValue..defaultValue)
    if (safeRange.first == safeRange.last) return listOf(safeRange.first)

    val stepCount = max(targetCount - 1, 1)
    val step = max((safeRange.last - safeRange.first) / stepCount, 1L)
    val values = mutableListOf<Long>()
    var current = safeRange.first
    while (current < safeRange.last) {
        values += current
        current += step
    }
    values += safeRange.last
    if (defaultValue in safeRange) {
        values += defaultValue
    }
    return values.distinct().sorted()
}

private fun optionIndexOf(options: List<Int>, value: Int): Int {
    return options.indexOf(value).takeIf { it >= 0 } ?: 0
}

private fun optionIndexOf(options: List<Long>, value: Long): Int {
    return options.indexOf(value).takeIf { it >= 0 } ?: 0
}

private fun Int.coerceInRange(range: IntRange?): Int {
    if (range == null) return this
    return coerceIn(range.first, range.last)
}

private fun Long.coerceInRange(range: LongRange?): Long {
    if (range == null) return this
    return coerceIn(range.first, range.last)
}

private fun Range<Int>.toIntRange(): IntRange = lower..upper

private fun Range<Long>.toLongRange(): LongRange = lower..upper

private fun formatExposureTimeMillis(exposureTimeNs: Long): String {
    return String.format(Locale.US, "%.1f", exposureTimeNs / 1_000_000f)
}

private fun formatSignedIndex(value: Int): String {
    return if (value > 0) "+$value" else value.toString()
}

private fun formatZoomRatio(value: Float): String {
    return String.format(Locale.US, "%.1fx", value)
}

