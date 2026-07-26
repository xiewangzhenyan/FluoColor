package com.muc.fluocolorquant.ui.screens.detection.plate96

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationMode
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.ui.screens.detection.ArrayLayoutEditor
import com.muc.fluocolorquant.ui.screens.detection.Plate96SiteVisualStyle
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutAssignmentDraft
import com.muc.fluocolorquant.ui.viewmodels.GridLayoutPaintIntent
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationAnalyte
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationPreview
import com.muc.fluocolorquant.ui.viewmodels.GridLocalizationSitePreview
import com.muc.fluocolorquant.ui.viewmodels.GridPaintMergeResult

/** 96孔板布局页测试标签，避免设备回归依赖中英文可见文本。 */
internal object Plate96LayoutTestTags {
    const val ROOT: String = "plate96_layout_root"
}

/**
 * 96孔板现代化布局页。
 *
 * 页面直接复用通用阵列布局与逐分析物定量工作台，仅注入标准方向Bitmap和圆孔视觉策略。
 * 因此多笔画笔、已分配孔位保护、实验模板、现场拟合、已有曲线、深度学习和仅信号模式
 * 与微流控使用同一套实现；96孔板不会再维护第二套容易漂移的业务状态机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Plate96LayoutScreen(
    normalizedBitmap: Bitmap,
    preview: GridLocalizationPreview,
    assignments: Map<Int, GridLayoutAssignmentDraft>,
    onAssignmentsChange: (Map<Int, GridLayoutAssignmentDraft>) -> Unit,
    onPaintAssignments: (GridLayoutPaintIntent) -> GridPaintMergeResult,
    onBack: () -> Unit,
    onReviewLocalization: () -> Unit,
    onFinalize: () -> Unit,
    onUseManualConfiguration: () -> Unit = {},
    onApplyTemplate: (String) -> Unit = {},
    onSelectQuantitationAnalyte: (String) -> Unit = {},
    onSetQuantitationMode: (String, GridAnalyteQuantitationMode) -> Unit = { _, _ -> },
    onSelectAnalysisModel: (String, String) -> Unit = { _, _ -> },
    onUpdateOnsiteAdvanced: (String, Set<AnalysisPrimaryFeature>, Set<FittingFunction>) -> Unit =
        { _, _, _ -> },
    onUpdateStandardConcentrations: (String, Map<Int, Double?>) -> Unit = { _, _ -> },
    onPreviewOnsiteFit: (String) -> Unit = {},
    onSelectOnsiteCandidate: (String, String) -> Unit = { _, _ -> },
    onSetOnsiteSaveToLibrary: (String, Boolean) -> Unit = { _, _ -> },
    onEditOnsiteCalibration: (String) -> Unit = {},
    onConfirmQuantitationAnalyte: (String, Boolean) -> Unit = { _, _ -> },
    onSaveTemplate: (String) -> Unit = {}
) {
    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.plate96_layout_screen_title),
                onBack = onBack
            )
        }
    ) { paddingValues ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag(Plate96LayoutTestTags.ROOT)
        ) {
            ArrayLayoutEditor(
                preview = preview,
                assignments = assignments,
                onAssignmentsChange = onAssignmentsChange,
                onPaintAssignments = onPaintAssignments,
                onBackToLocalization = onReviewLocalization,
                onFinalize = onFinalize,
                onUseManualConfiguration = onUseManualConfiguration,
                onApplyTemplate = onApplyTemplate,
                onSelectQuantitationAnalyte = onSelectQuantitationAnalyte,
                onSetQuantitationMode = onSetQuantitationMode,
                onSelectAnalysisModel = onSelectAnalysisModel,
                onUpdateOnsiteAdvanced = onUpdateOnsiteAdvanced,
                onUpdateStandardConcentrations = onUpdateStandardConcentrations,
                onPreviewOnsiteFit = onPreviewOnsiteFit,
                onSelectOnsiteCandidate = onSelectOnsiteCandidate,
                onSetOnsiteSaveToLibrary = onSetOnsiteSaveToLibrary,
                onEditOnsiteCalibration = onEditOnsiteCalibration,
                onConfirmQuantitationAnalyte = onConfirmQuantitationAnalyte,
                onSaveTemplate = onSaveTemplate,
                realCropSourceBitmap = normalizedBitmap,
                visualStyle = Plate96SiteVisualStyle,
                compactHeader = true,
                includeQuantitationStep = true,
                realPreviewTitleRes = R.string.plate96_layout_real_preview
            )
        }
    }
}

/**
 * 把96孔板定位结果适配为通用布局/定量工作台输入。
 *
 * 适配只复制轻量坐标和资源摘要；真实标准方向Bitmap仍由定位ViewModel持有，96张裁切按
 * [com.muc.fluocolorquant.domain.detection.array.ArrayLocalizedSite.normalizedRegion] 现场生成，
 * 不重复定位、不写JPEG，也不会改变圆形前景掩膜。
 */
internal fun ArrayLocalizationResult.toPlate96LayoutPreview(
    runId: String,
    originalImageUri: String,
    detectionMode: String,
    analytes: List<GridLocalizationAnalyte>,
    initialAssignments: List<GridLayoutAssignmentDraft> = emptyList(),
    configurationSource: GridLayoutConfigurationSource = GridLayoutConfigurationSource.MANUAL,
    selectedTemplateId: String? = null,
    availableTemplates: List<GridExperimentTemplateOption> = emptyList(),
    availableModels: List<GridAnalysisModelOption> = emptyList(),
    quantitationDrafts: List<GridAnalyteQuantitationDraft> = emptyList(),
    selectedQuantitationAnalyteId: String? = analytes.firstOrNull()?.id
): GridLocalizationPreview {
    requireValid()
    return GridLocalizationPreview(
        runId = runId,
        originalImageUri = originalImageUri,
        detectionMode = detectionMode,
        rows = orientation.canonicalRows,
        columns = orientation.canonicalColumns,
        siteCount = sites.size,
        observedRatio = diagnostics.observedSiteCount.toDouble() / sites.size.coerceAtLeast(1),
        meanConfidence = diagnostics.meanConfidence,
        frameQcIssueCount = 0,
        rectifiedImagePath = null,
        cropHalfSizePx = 0.0,
        sites = sites.map { site ->
            GridLocalizationSitePreview(
                siteIndex = site.siteIndex,
                rowIndex = site.canonicalCoordinate.rowIndex,
                columnIndex = site.canonicalCoordinate.columnIndex,
                rectifiedX = site.normalizedCenter.x,
                rectifiedY = site.normalizedCenter.y,
                cropRegion = site.normalizedRegion
            )
        },
        analytes = analytes,
        evidence = emptyList(),
        initialAssignments = initialAssignments,
        configurationSource = configurationSource,
        selectedTemplateId = selectedTemplateId,
        availableTemplates = availableTemplates,
        availableModels = availableModels,
        quantitationDrafts = quantitationDrafts,
        selectedQuantitationAnalyteId = selectedQuantitationAnalyteId
    )
}
