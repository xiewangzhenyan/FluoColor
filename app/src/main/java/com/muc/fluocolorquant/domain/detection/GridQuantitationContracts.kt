package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.calibration.CalibrationResultSet
import com.muc.fluocolorquant.domain.calibration.OnsiteCalibrationState
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot

/**
 * 孔位布局页的配置来源。
 *
 * 实验模板和手动配置只决定“如何得到布局与逐分析物定量方案”，并不直接参与浓度计算。
 * 两条路径最终都会生成同一份不可变 [com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot]。
 */
enum class GridLayoutConfigurationSource {
    EXPERIMENT_TEMPLATE,
    MANUAL
}

/**
 * 单个分析物在一次阵列运行中的定量方式。
 *
 * 该枚举刻意把“现场标准品”和“已有标准曲线”分开：前者需要从本次图片的标准孔重新
 * 提取信号并拟合，后者直接执行资源库中已经验证并冻结到项目快照的曲线。深度学习模型
 * 同样只是一种定量工具；[SIGNAL_ONLY] 明确表示只保存科学信号，不生成伪造浓度。
 */
enum class GridAnalyteQuantitationMode(val code: String) {
    ONSITE_AUTO_FIT("ONSITE_AUTO_FIT"),
    EXISTING_STANDARD_CURVE("EXISTING_STANDARD_CURVE"),
    DEEP_LEARNING_MODEL("DEEP_LEARNING_MODEL"),
    SIGNAL_ONLY("SIGNAL_ONLY");

    companion object {
        fun fromCode(code: String?): GridAnalyteQuantitationMode? =
            entries.firstOrNull { it.code == code }
    }
}

/**
 * 布局页持有的逐分析物定量草稿。
 *
 * 所有选择都由 ViewModel 保存，禁止只放在 Compose 的 remember 中；这样旋转屏幕、返回
 * 定位复核或科学门控失败后，曲线、模型和高级拟合选择仍能完整恢复。
 */
data class GridAnalyteQuantitationDraft(
    val analyteId: String,
    val mode: GridAnalyteQuantitationMode,
    val selectedAnalysisModelId: String? = null,
    val selectedFeature: AnalysisPrimaryFeature? = null,
    val selectedFunction: FittingFunction? = null,
    /** 空集合表示后台自动比较推荐信号；非空时仅比较用户明确选择的候选。 */
    val selectedFeatures: Set<AnalysisPrimaryFeature> = emptySet(),
    /** 空集合表示后台自动比较默认函数；非空时逐一计算用户选择的候选函数。 */
    val selectedFunctions: Set<FittingFunction> = emptySet(),
    /** 现场拟合使用明确状态机，禁止继续组合“加载中/预览为空/已确认”等互斥布尔值。 */
    val onsiteState: OnsiteCalibrationState = OnsiteCalibrationState.Editing,
    /**
     * 用户完成当前分析物配置后立即冻结的不可变快照。
     *
     * 现场曲线、已有曲线、深度学习和仅信号最终都会形成该对象；只要相关输入变化就清空，
     * 从而使“完成进度”与真正可执行的科学配置严格一致。
     */
    val appliedSnapshot: AnalyteQuantitationSnapshot? = null
)

/**
 * 当前草稿是否已经具备可供用户确认的完整信息。
 *
 * 现场拟合必须真正产出拟合预览；资源模式必须选择了兼容资源；仅信号无需额外输入。
 * 该纯函数同时供 ViewModel 二次校验和 Compose 按钮门控使用，避免两处规则漂移。
 */
fun GridAnalyteQuantitationDraft.isReadyForConfirmation(): Boolean = when (mode) {
    GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> {
        val reviewing = onsiteState as? OnsiteCalibrationState.Reviewing
        reviewing?.resultSet?.candidate(reviewing.selectedCandidateId) != null
    }
    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
    GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> !selectedAnalysisModelId.isNullOrBlank()
    GridAnalyteQuantitationMode.SIGNAL_ONLY -> true
}

/** 当前分析物是否已经真正冻结，而不是只完成了表单选择。 */
fun GridAnalyteQuantitationDraft.isConfigurationComplete(): Boolean = appliedSnapshot != null

/**
 * 开始一次现场拟合，并立即让旧的已应用快照失效。
 *
 * [requestId] 是异步结果的唯一身份。后续成功或失败回调必须携带同一 ID；较早请求即使
 * 最后才返回，也不能覆盖用户已经重新发起的拟合。
 */
fun GridAnalyteQuantitationDraft.startOnsiteFitting(
    requestId: String,
    inputFingerprint: String = "pending:$requestId"
): GridAnalyteQuantitationDraft {
    require(mode == GridAnalyteQuantitationMode.ONSITE_AUTO_FIT) {
        "只有现场拟合模式可以开始拟合"
    }
    require(requestId.isNotBlank()) { "现场拟合请求ID不能为空" }
    return copy(
        onsiteState = OnsiteCalibrationState.Fitting(
            requestId = requestId,
            inputFingerprint = inputFingerprint
        ),
        appliedSnapshot = null
    )
}

/**
 * 接收拟合成功结果；请求已经过期时返回 null，由调用方静默丢弃旧异步回调。
 */
fun GridAnalyteQuantitationDraft.acceptOnsiteFitResult(
    requestId: String,
    resultSet: CalibrationResultSet,
    saveToLibraryByDefault: Boolean
): GridAnalyteQuantitationDraft? {
    val fitting = onsiteState as? OnsiteCalibrationState.Fitting ?: return null
    if (fitting.requestId != requestId || resultSet.analyteId != analyteId) return null
    return copy(
        onsiteState = OnsiteCalibrationState.Reviewing(
            resultSet = resultSet,
            saveToLibrary = saveToLibraryByDefault
        ),
        appliedSnapshot = null
    )
}

/** 拟合技术失败只改变同一请求的状态，科学上“候选均不可用”仍由 Reviewing 表达。 */
fun GridAnalyteQuantitationDraft.rejectOnsiteFitResult(
    requestId: String
): GridAnalyteQuantitationDraft? {
    val fitting = onsiteState as? OnsiteCalibrationState.Fitting ?: return null
    if (fitting.requestId != requestId) return null
    return copy(
        onsiteState = OnsiteCalibrationState.TechnicalFailure(
            requestId = requestId,
            inputFingerprint = fitting.inputFingerprint
        ),
        appliedSnapshot = null
    )
}

/** 候选必须真实存在于当前结果集，禁止页面提交过期或伪造的候选 ID。 */
fun GridAnalyteQuantitationDraft.selectOnsiteCandidate(
    candidateId: String
): GridAnalyteQuantitationDraft? {
    val reviewing = onsiteState as? OnsiteCalibrationState.Reviewing ?: return null
    if (reviewing.resultSet.candidate(candidateId) == null) return null
    return copy(
        onsiteState = reviewing.copy(selectedCandidateId = candidateId),
        appliedSnapshot = null
    )
}

/** 保存开关只控制是否发布可复用资源，不改变当前结果集中的科学参数。 */
fun GridAnalyteQuantitationDraft.setOnsiteSaveToLibrary(
    saveToLibrary: Boolean
): GridAnalyteQuantitationDraft? {
    val reviewing = onsiteState as? OnsiteCalibrationState.Reviewing ?: return null
    return copy(onsiteState = reviewing.copy(saveToLibrary = saveToLibrary))
}

/** 返回浓度编辑后，旧候选和旧冻结快照必须同时失效。 */
fun GridAnalyteQuantitationDraft.returnToOnsiteEditing(): GridAnalyteQuantitationDraft = copy(
    onsiteState = OnsiteCalibrationState.Editing,
    appliedSnapshot = null
)

/**
 * 从审阅进入应用阶段。返回 null 表示当前没有合法候选，调用方不能启动资源写入或快照冻结。
 */
fun GridAnalyteQuantitationDraft.startOnsiteApplying(): GridAnalyteQuantitationDraft? {
    val reviewing = onsiteState as? OnsiteCalibrationState.Reviewing ?: return null
    val candidateId = reviewing.selectedCandidateId ?: return null
    if (reviewing.resultSet.candidate(candidateId) == null) return null
    return copy(
        onsiteState = OnsiteCalibrationState.Applying(
            resultSet = reviewing.resultSet,
            selectedCandidateId = candidateId,
            saveToLibrary = reviewing.saveToLibrary
        )
    )
}

/**
 * 只允许当前 Applying 所对应的候选完成应用，防止迟到的保存回调覆盖新的编辑或拟合。
 */
fun GridAnalyteQuantitationDraft.completeOnsiteApplying(
    selectedCandidateId: String,
    snapshot: AnalyteQuantitationSnapshot
): GridAnalyteQuantitationDraft? {
    val applying = onsiteState as? OnsiteCalibrationState.Applying ?: return null
    if (
        applying.selectedCandidateId != selectedCandidateId ||
        snapshot.analyteId != analyteId
    ) {
        return null
    }
    return copy(
        onsiteState = OnsiteCalibrationState.Applied(
            snapshot = snapshot,
            resultSet = applying.resultSet,
            selectedCandidateId = selectedCandidateId
        ),
        appliedSnapshot = snapshot
    )
}

/** 应用失败回到原结果集供用户重试，不能更换候选或清除“保存到曲线库”选择。 */
fun GridAnalyteQuantitationDraft.restoreOnsiteReviewingAfterApplyFailure(
    selectedCandidateId: String
): GridAnalyteQuantitationDraft? {
    val applying = onsiteState as? OnsiteCalibrationState.Applying ?: return null
    if (applying.selectedCandidateId != selectedCandidateId) return null
    return copy(
        onsiteState = OnsiteCalibrationState.Reviewing(
            resultSet = applying.resultSet,
            selectedCandidateId = applying.selectedCandidateId,
            saveToLibrary = applying.saveToLibrary
        ),
        appliedSnapshot = null
    )
}

/** 供孔位布局页展示的实验模板摘要；完整内容只在用户实际应用时从仓库重新读取。 */
data class GridExperimentTemplateOption(
    val id: String,
    val name: String,
    val version: Int,
    val analyteIds: Set<String>
)

/** 供逐分析物选择器展示的分析模型摘要；模型二进制路径、校验和和输入尺寸不在普通 UI 暴露。 */
data class GridAnalysisModelOption(
    val id: String,
    val name: String,
    val version: Int,
    val analyteId: String,
    val modelType: AnalysisModelType,
    val primaryFeature: AnalysisPrimaryFeature,
    val concentrationUnit: String,
    val reliableRangeMin: Double? = null,
    val reliableRangeMax: Double? = null,
    /** 内置共享模型使用本地化名称，普通用户不看到数据库中的稳定机器资源名。 */
    val builtInShared: Boolean = false,
    /**
     * 当前项目是否超出模型已有验证范围。
     *
     * 该标记只控制风险提示与显式确认，不绕过文件校验、运行兼容检查或输出域判断。
     */
    val requiresExplicitScopeConfirmation: Boolean = false
)

/**
 * 解析旧项目快照中的定量方式。
 *
 * 旧 JSON 没有 quantitationMode 字段，因此必须从已冻结模型推断，而不能一律降为仅信号。
 * 空参数的 direct 占位曲线是明确的仅信号；有完整曲线定义或深度学习定义时分别恢复。
 */
fun TemplateProjectAnalyteSnapshot.resolvedGridQuantitationMode(): GridAnalyteQuantitationMode {
    GridAnalyteQuantitationMode.fromCode(quantitationMode)?.let { return it }
    val bundle = analysisModel
    return when (AnalysisModelType.fromCode(bundle.model.modelType)) {
        AnalysisModelType.DEEP_LEARNING -> if (bundle.deepLearning != null) {
            GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL
        } else {
            GridAnalyteQuantitationMode.SIGNAL_ONLY
        }

        AnalysisModelType.STANDARD_CURVE -> {
            val parameters = bundle.standardCurve?.parametersJson.orEmpty().trim()
            when {
                bundle.model.name == "onsite-auto-fit" ->
                    GridAnalyteQuantitationMode.ONSITE_AUTO_FIT
                parameters.isNotEmpty() && parameters != "{}" ->
                    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE
                else -> GridAnalyteQuantitationMode.SIGNAL_ONLY
            }
        }

        null -> GridAnalyteQuantitationMode.SIGNAL_ONLY
    }
}

/**
 * 将已经保存并发布的现场标准曲线资源同步回本次运行快照。
 *
 * 现场拟合在保存到曲线库后会获得新的模型ID。此时不能只替换 [analysisModel]，否则
 * [com.muc.fluocolorquant.data.model.TemplateAnalyteConfig.analysisModelId] 仍指向创建项目时
 * 的仅信号占位模型，预检就会把“配置模型ID”和“实际模型ID”判定为关系不一致。
 *
 * 该函数只同步模型ID、单位和定量快照来源，同时保留“现场拟合”方式以及用户已经选择的
 * 信号、函数和冻结参数。模板配置中的上下限是用户声明的“项目量程”，而曲线资源上下限
 * 是“标定范围”，两者绝不能在保存资源时互相覆盖。
 */
internal fun TemplateProjectAnalyteSnapshot.withPersistedOnsiteCurveResource(
    bundle: AnalysisModelBundle
): TemplateProjectAnalyteSnapshot {
    require(bundle.model.analyteId == analyte.id) { "现场曲线与当前分析物不一致" }
    require(AnalysisModelType.fromCode(bundle.model.modelType) == AnalysisModelType.STANDARD_CURVE) {
        "现场拟合只能绑定标准曲线资源"
    }
    require(bundle.standardCurve?.analysisModelId == bundle.model.id) {
        "现场曲线定义与模型ID不一致"
    }
    require(bundle.deepLearning == null) { "现场曲线不能混入深度学习定义" }
    require(bundle.calibrationPoints.all { point -> point.analysisModelId == bundle.model.id }) {
        "现场曲线标定点与模型ID不一致"
    }
    val synchronizedQuantitation = analyteQuantitationSnapshot?.let { snapshot ->
        require(snapshot.method == AnalyteQuantitationMethod.ONSITE_CALIBRATION) {
            "现场曲线资源只能同步到现场标定快照"
        }
        snapshot.copy(sourceResourceId = bundle.model.id)
    }
    return copy(
        templateConfig = templateConfig.copy(
            analysisModelId = bundle.model.id,
            concentrationUnit = bundle.model.concentrationUnit
        ),
        analysisModel = bundle,
        quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
        analyteQuantitationSnapshot = synchronizedQuantitation
    )
}
