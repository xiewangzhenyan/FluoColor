package com.muc.fluocolorquant.ui.screens.detection

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.screens.detection.plate96.Plate96LocalizationScreen
import com.muc.fluocolorquant.ui.viewmodels.GridConfigurationEvent
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionUiState
import com.muc.fluocolorquant.ui.viewmodels.GridDetectionViewModel
import java.util.Locale

/**
 * 规则阵列检测的唯一生产入口。
 *
 * 微流控使用PG-Grid定位确认，标准96孔板进入圆孔方向/定位确认；两者随后共享布局和
 * 定量工作台。旧 `DetectionViewModel → CurveFittingScreen` 写入链已经退役。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WellDetectionScreen(
    navController: NavController,
    imageUri: String? = null,
    projectId: String? = null,
    gridViewModel: GridDetectionViewModel = hiltViewModel()
) {
    val gridState by gridViewModel.uiState.collectAsState()
    val toastManager = LocalToastManager.current
    // stringResource只能在Composable上下文读取；事件协程只消费提前解析好的文本。
    val templateAppliedMessage = stringResource(R.string.grid_event_template_applied)
    val templateIncompatibleMessage = stringResource(R.string.grid_event_template_incompatible)
    val modelAppliedMessage = stringResource(R.string.grid_event_model_applied)
    val modelIncompatibleMessage = stringResource(R.string.grid_event_model_incompatible)
    val fitReadyMessage = stringResource(R.string.grid_event_fit_ready)
    val fitUnavailableMessage = stringResource(R.string.grid_event_fit_unavailable)
    val curveSaveFailedMessage = stringResource(R.string.grid_event_curve_save_failed)
    val templateSavedFormat = stringResource(R.string.grid_event_template_saved)
    val templateNameConflictMessage = stringResource(R.string.grid_event_template_name_conflict)
    val templateSaveIncompleteMessage = stringResource(R.string.grid_event_template_save_incomplete)
    val templateSaveFailedMessage = stringResource(R.string.grid_event_template_save_failed)
    val lowQualityBlockedMessage = stringResource(R.string.grid_quant_low_quality_blocked)
    val lowQualityConfirmationRequiredMessage = stringResource(
        R.string.grid_quant_low_quality_confirmation_required
    )
    val lowQualityAppliedMessage = stringResource(R.string.grid_quant_low_quality_applied)
    val operationFailedMessage = stringResource(R.string.grid_event_operation_failed)
    val decodedImageUri = remember(imageUri) {
        runCatching { imageUri?.let { java.net.URLDecoder.decode(it, "UTF-8") } }
            .getOrDefault(imageUri)
    }

    LaunchedEffect(projectId, decodedImageUri) {
        gridViewModel.start(projectId, decodedImageUri)
    }

    LaunchedEffect(gridViewModel) {
        gridViewModel.configurationEvents.collect { event ->
            when (event) {
                GridConfigurationEvent.TemplateApplied ->
                    toastManager.showToast(templateAppliedMessage, ToastType.SUCCESS)
                GridConfigurationEvent.TemplateIncompatible ->
                    toastManager.showToast(templateIncompatibleMessage, ToastType.WARNING)
                GridConfigurationEvent.ModelApplied ->
                    toastManager.showToast(modelAppliedMessage, ToastType.SUCCESS)
                GridConfigurationEvent.ModelIncompatible ->
                    toastManager.showToast(modelIncompatibleMessage, ToastType.WARNING)
                GridConfigurationEvent.FitReady ->
                    toastManager.showToast(fitReadyMessage, ToastType.SUCCESS)
                GridConfigurationEvent.FitUnavailable ->
                    toastManager.showToast(fitUnavailableMessage, ToastType.WARNING)
                GridConfigurationEvent.CurveSaveFailed ->
                    toastManager.showToast(curveSaveFailedMessage, ToastType.ERROR)
                is GridConfigurationEvent.TemplateSaved -> toastManager.showToast(
                    String.format(Locale.getDefault(), templateSavedFormat, event.name),
                    ToastType.SUCCESS
                )
                GridConfigurationEvent.TemplateNameConflict ->
                    toastManager.showToast(templateNameConflictMessage, ToastType.WARNING)
                GridConfigurationEvent.TemplateSaveIncomplete ->
                    toastManager.showToast(templateSaveIncompleteMessage, ToastType.WARNING)
                GridConfigurationEvent.TemplateSaveFailed ->
                    toastManager.showToast(templateSaveFailedMessage, ToastType.ERROR)
                GridConfigurationEvent.LowQualityCalibrationBlocked ->
                    toastManager.showToast(lowQualityBlockedMessage, ToastType.WARNING)
                GridConfigurationEvent.LowQualityCalibrationConfirmationRequired ->
                    toastManager.showToast(lowQualityConfirmationRequiredMessage, ToastType.INFO)
                GridConfigurationEvent.LowQualityCalibrationApplied ->
                    toastManager.showToast(lowQualityAppliedMessage, ToastType.WARNING)
                GridConfigurationEvent.OperationFailed ->
                    toastManager.showToast(operationFailedMessage, ToastType.ERROR)
            }
        }
    }

    // 保存成功后直接进入结果网关；96孔板与微流控会再按冻结载体协议分流。
    LaunchedEffect(gridState) {
        val completed = gridState as? GridDetectionUiState.Completed ?: return@LaunchedEffect
        navController.navigate(Screen.NewResult.createRoute(completed.runId)) {
            popUpTo(Screen.WellDetection.route) { inclusive = true }
        }
    }

    if (gridState == GridDetectionUiState.Plate96Localization) {
        Plate96LocalizationScreen(
            imageUri = imageUri,
            onBack = {
                if (gridViewModel.hasActivePlate96LayoutSession()) {
                    gridViewModel.cancelPlate96LocalizationReview()
                } else {
                    navController.popBackStack()
                }
            },
            onContinue = gridViewModel::acceptPlate96Localization
        )
    } else {
        GridDetectionGatewayContent(
            state = gridState,
            onBack = { navController.popBackStack() },
            onRetry = gridViewModel::retry,
            onOpenLayout = gridViewModel::openLayoutEditor,
            onReviewLocalization = gridViewModel::showLocalizationPreview,
            onAssignmentsChange = gridViewModel::updateLayoutDraft,
            onPaintAssignments = gridViewModel::paintLayoutDraft,
            onFinalizeLayout = gridViewModel::finalizeLayout,
            onUseManualConfiguration = gridViewModel::useManualConfiguration,
            onApplyTemplate = gridViewModel::applyExperimentTemplate,
            onSelectQuantitationAnalyte = gridViewModel::selectQuantitationAnalyte,
            onSetQuantitationMode = gridViewModel::setQuantitationMode,
            onSelectAnalysisModel = gridViewModel::selectAnalysisModel,
            onUpdateOnsiteAdvanced = gridViewModel::updateOnsiteAdvanced,
            onUpdateStandardConcentrations = gridViewModel::updateStandardConcentrations,
            onPreviewOnsiteFit = gridViewModel::previewOnsiteFit,
            onSelectOnsiteCandidate = gridViewModel::selectOnsiteCandidate,
            onSetOnsiteSaveToLibrary = gridViewModel::setOnsiteSaveToLibrary,
            onEditOnsiteCalibration = gridViewModel::editOnsiteCalibration,
            onConfirmQuantitationAnalyte = { analyteId, lowQualityConfirmed ->
                gridViewModel.confirmQuantitationAnalyte(analyteId, lowQualityConfirmed)
            },
            onSaveTemplate = gridViewModel::saveCurrentConfigurationAsTemplate
        )
    }
}
