package com.muc.fluocolorquant.ui.viewmodels

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisPreferences
import com.muc.fluocolorquant.domain.detection.array.ArrayAnalysisScope
import com.muc.fluocolorquant.domain.detection.array.ArrayLocatorMode

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

/**
 * 同一定位页里的当前尝试，仅在内存中存活。原图可复用，但上一方法的叠加和采样不能串入本次尝试。
 * previousMode 只表示有可恢复的正式会话；对照成功也绝不生成可继续定量的 Ready。
 */
data class ArrayLocatorAttempt(
    val bitmap: Bitmap,
    val scope: ArrayAnalysisScope,
    val mode: ArrayLocatorMode,
    val status: ArrayLocatorAttemptStatus,
    val previousMode: ArrayLocatorMode? = null
)

sealed interface ArrayLocatorAttemptStatus {
    data object Running : ArrayLocatorAttemptStatus
    data object NoGrid : ArrayLocatorAttemptStatus
    data object Failed : ArrayLocatorAttemptStatus
    data class Preview(val grid: PgGridResult) : ArrayLocatorAttemptStatus
}

/** 物理位点的真实矫正/原图坐标、紧致分割区与 GenePix 风格单孔检视摘要。 */
data class GridLocalizationSitePreview(
    val siteIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val rectifiedX: Double,
    val rectifiedY: Double,
    val cropRegion: ArrayUnitRegion? = null,
    val originalX: Double = rectifiedX,
    val originalY: Double = rectifiedY,
    val foregroundMedianGray: Double = 0.0,
    val foregroundMeanGray: Double = 0.0,
    /** 当前所选孔内取值方式对应的实际前景读数（默认全孔中位数时与 [foregroundMedianGray] 相等）。 */
    val selectedReadoutGray: Double = foregroundMedianGray,
    val backgroundMedianGray: Double = 0.0,
    val signalToNoiseRatio: Double = 0.0,
    val pctAboveBgPlus2Sd: Double = 0.0,
    val qualityReliable: Boolean = true,
    val manuallyFlaggedBad: Boolean = false,
    /** 是否对该孔执行过人工中心微调或采样框缩放。 */
    val isUserAdjusted: Boolean = false,
    /** 采样框相对自动基线的线性缩放比例（0.60～1.40）。 */
    val boxScale: Double = 1.0,
    /** 自动定位基线的矫正坐标，用于固定单孔高倍检视窗的视野中心，使微调移动清晰可见。 */
    val autoRectifiedX: Double = rectifiedX,
    val autoRectifiedY: Double = rectifiedY
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
    val presentation: GridLocalizationPresentation = GridLocalizationPresentation.MICROFLUIDIC,
    val manualGeometry: Boolean = false,
    val analysisScope: ArrayAnalysisScope = ArrayAnalysisScope.CHIP,
    val analysisPreferences: ArrayAnalysisPreferences = ArrayAnalysisPreferences(),
    val trustedGeometry: Boolean = true,
    val rectifiedWidth: Int = 0,
    val rectifiedHeight: Int = 0,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    /** 过程证据图刷新版本号，确保单孔微调重写同名 PNG 后图像加载器不会命中旧内存缓存。 */
    val evidenceRevision: Int = 0
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
    data object DiagnosticReadout : GridConfigurationEvent
}

/** 未配置位点不入列表；禁用位点保留记录但不进入信号或浓度计算。 */
data class GridLayoutAssignmentDraft(
    val rowIndex: Int,
    val columnIndex: Int,
    val analyteId: String?,
    val role: TemplateSiteRole,
    val standardConcentration: Double? = null,
    val sampleId: String? = null,
    val repeatGroup: String? = null
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
    /** 位图就是科学采样的原图，不经 EXIF 自动翻转或另一次解码。草稿只在本次 ViewModel 中存活。 */
    data class ManualCorners(
        val bitmap: Bitmap,
        val rows: Int,
        val columns: Int,
        val anchors: List<GridPoint> = emptyList(),
        val previewSites: List<GridPoint> = emptyList(),
        val applyFailed: Boolean = false,
        /** 圆孔板的角点与内径须分别确认：角点间距无法推出真实孔径。 */
        val diameterRatio: Double? = null,
        val diameterConfirmed: Boolean = false,
        val rimPreviews: List<List<GridPoint>> = emptyList()
    ) : GridDetectionUiState
    data class Processing(
        val stage: GridDetectionStage,
        val presentation: GridLocalizationPresentation = GridLocalizationPresentation.MICROFLUIDIC
    ) : GridDetectionUiState
    data class LocatingArray(val attempt: ArrayLocatorAttempt) : GridDetectionUiState
    data class LocalizationReady(
        val preview: GridLocalizationPreview,
        val editingLayout: Boolean = false,
        /** 仅孔内取值重算会在原页报告阶段；旧预览保留，但完成前禁止确认或再提交设置。 */
        val readoutStage: GridDetectionStage? = null
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
