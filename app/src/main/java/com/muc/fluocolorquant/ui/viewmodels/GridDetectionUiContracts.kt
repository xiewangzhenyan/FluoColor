package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.CaptureRole
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.domain.detection.GridAnalysisModelOption
import com.muc.fluocolorquant.domain.detection.GridAnalyteQuantitationDraft
import com.muc.fluocolorquant.domain.detection.GridDetectionBlockReason
import com.muc.fluocolorquant.domain.detection.GridDetectionStage
import com.muc.fluocolorquant.domain.detection.GridExperimentTemplateOption
import com.muc.fluocolorquant.domain.detection.GridLayoutConfigurationSource
import com.muc.fluocolorquant.domain.detection.GridLocalizationPresentation
import com.muc.fluocolorquant.domain.detection.isConfigurationComplete
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegion

/** 定位确认页展示的分析物摘要。 */
data class GridLocalizationAnalyte(
    val id: String,
    val name: String,
    val concentrationUnit: String,
    val maxConcentration: Double? = null
)

data class GridLocalizationEvidence(val role: CaptureRole, val path: String)

/** 物理位点的真实矫正坐标与紧致分割区，用于生成不失真的缩略图。 */
data class GridLocalizationSitePreview(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val rectifiedX: Double,
    val rectifiedY: Double,
    val cropRegion: ArrayUnitRegion? = null
)

/** 页面只读定位摘要；配置状态由 ViewModel 持有，Compose 重组不会丢失选择。 */
data class GridLocalizationPreview(
    val runId: String,
    val originalImageUri: String,
    val detectionMode: String = "",
    val rows: Int,
    val columns: Int,
    val siteCount: Int,
    val observedRatio: Double,
    val meanConfidence: Double,
    val frameQcIssueCount: Int,
    val rectifiedImagePath: String?,
    val cropHalfSizePx: Double,
    val sites: List<GridLocalizationSitePreview>,
    val analytes: List<GridLocalizationAnalyte>,
    val evidence: List<GridLocalizationEvidence>,
    val initialAssignments: List<GridLayoutAssignmentDraft> = emptyList(),
    val configurationSource: GridLayoutConfigurationSource = GridLayoutConfigurationSource.MANUAL,
    val selectedTemplateId: String? = null,
    val availableTemplates: List<GridExperimentTemplateOption> = emptyList(),
    val availableModels: List<GridAnalysisModelOption> = emptyList(),
    val quantitationDrafts: List<GridAnalyteQuantitationDraft> = emptyList(),
    val selectedQuantitationAnalyteId: String? = null,
    val presentation: GridLocalizationPresentation = GridLocalizationPresentation.MICROFLUIDIC
)

/** 孔位布局页的一次性操作结果，页面负责映射为中英文 Toast。 */
sealed interface GridConfigurationEvent {
    data object TemplateApplied : GridConfigurationEvent
    data object TemplateIncompatible : GridConfigurationEvent
    data object ModelApplied : GridConfigurationEvent
    data object ModelIncompatible : GridConfigurationEvent
    data object FitReady : GridConfigurationEvent
    data object FitUnavailable : GridConfigurationEvent
    data object CurveSaveFailed : GridConfigurationEvent
    data class TemplateSaved(val name: String) : GridConfigurationEvent
    data object TemplateNameConflict : GridConfigurationEvent
    data object TemplateSaveIncomplete : GridConfigurationEvent
    data object TemplateSaveFailed : GridConfigurationEvent
    data object LowQualityCalibrationBlocked : GridConfigurationEvent
    data object LowQualityCalibrationConfirmationRequired : GridConfigurationEvent
    data object LowQualityCalibrationApplied : GridConfigurationEvent
    data object OperationFailed : GridConfigurationEvent
}

/** 未配置位点不入列表；禁用位点保留记录但不进入信号或浓度计算。 */
data class GridLayoutAssignmentDraft(
    val rowIndex: Int,
    val columnIndex: Int,
    val analyteId: String?,
    val role: TemplateSiteRole,
    val standardConcentration: Double? = null,
    val sampleId: String? = null
)

/** UI 只提交本笔经过的位点，避免把旧 Map 回传后覆盖较新的画笔操作。 */
data class GridLayoutPaintIntent(
    val paintedSiteIndices: Set<Int>,
    val analyteId: String?,
    val role: TemplateSiteRole,
    val standardConcentration: Double? = null,
    val sampleId: String? = null,
    val clearMode: Boolean = false
)

data class GridPaintMergeResult(
    val assignments: Map<Int, GridLayoutAssignmentDraft>,
    val protectedSiteCount: Int
)

enum class ArrayLayoutReadinessIssue {
    NO_ASSIGNED_SITES,
    NO_QUANTITATION_DRAFTS,
    QUANTITATION_INCOMPLETE
}

/** 孔位与逐分析物定量方案共享同一完成门控，页面和提交逻辑不能各维护一套条件。 */
data class ArrayLayoutReadiness(
    val assignedSiteCount: Int,
    val totalSiteCount: Int,
    val completedAnalyteCount: Int,
    val totalAnalyteCount: Int,
    val issues: Set<ArrayLayoutReadinessIssue>
) {
    val canStart: Boolean get() = issues.isEmpty()
}

fun evaluateArrayLayoutReadiness(
    assignments: Collection<GridLayoutAssignmentDraft>,
    quantitationDrafts: Collection<GridAnalyteQuantitationDraft>,
    totalSiteCount: Int
): ArrayLayoutReadiness {
    val activeAssignments = assignments.count { it.role != TemplateSiteRole.DISABLED }
    val completedAnalytes = quantitationDrafts.count(GridAnalyteQuantitationDraft::isConfigurationComplete)
    val issues = buildSet {
        if (activeAssignments == 0) add(ArrayLayoutReadinessIssue.NO_ASSIGNED_SITES)
        if (quantitationDrafts.isEmpty()) add(ArrayLayoutReadinessIssue.NO_QUANTITATION_DRAFTS)
        else if (completedAnalytes != quantitationDrafts.size) {
            add(ArrayLayoutReadinessIssue.QUANTITATION_INCOMPLETE)
        }
    }
    return ArrayLayoutReadiness(
        assignedSiteCount = activeAssignments,
        totalSiteCount = totalSiteCount.coerceAtLeast(0),
        completedAnalyteCount = completedAnalytes,
        totalAnalyteCount = quantitationDrafts.size,
        issues = issues
    )
}

/** 检测网关状态只携带稳定枚举，所有用户文案由 Compose 资源化映射。 */
sealed interface GridDetectionUiState {
    data object ResolvingProject : GridDetectionUiState
    data object Plate96Localization : GridDetectionUiState
    data class Processing(
        val stage: GridDetectionStage,
        val presentation: GridLocalizationPresentation = GridLocalizationPresentation.MICROFLUIDIC
    ) : GridDetectionUiState
    data class LocalizationReady(
        val preview: GridLocalizationPreview,
        val editingLayout: Boolean = false
    ) : GridDetectionUiState
    data class Completed(
        val runId: String,
        val measurementCount: Int,
        val signalOnlyAnalyteIds: Set<String>,
        val frameQcIssueCount: Int = 0
    ) : GridDetectionUiState
    data class RetakeRequired(val runId: String) : GridDetectionUiState
    data class Blocked(val reasons: Set<GridDetectionBlockReason>) : GridDetectionUiState
    data class Error(val reason: GridDetectionUiError) : GridDetectionUiState
}

enum class GridDetectionUiError {
    INVALID_ARGUMENTS,
    PROJECT_NOT_FOUND,
    SNAPSHOT_MISSING_OR_INVALID,
    IMAGE_LOAD_FAILED,
    EXECUTION_FAILED
}
