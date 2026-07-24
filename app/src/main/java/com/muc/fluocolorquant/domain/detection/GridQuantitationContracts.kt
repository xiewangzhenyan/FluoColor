package com.muc.fluocolorquant.domain.detection

import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.FittingFunction
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
 * 现场拟合预览。
 *
 * 这里只保存页面真正需要展示和最终冻结的结构化数据，不暴露参数 JSON。标准点来源于本次
 * 图片，指标来源于现有成熟拟合引擎；用户确认后再转换为标准曲线快照。
 */
data class GridOnsiteFitPreview(
    val analyteId: String,
    val primaryFeature: AnalysisPrimaryFeature,
    val function: FittingFunction,
    val parameters: Map<String, Double>,
    val standardPoints: List<Pair<Double, Double>>,
    val curvePoints: List<Pair<Double, Double>>,
    val latexFormula: String,
    val rSquared: Double,
    val rmse: Double?,
    val mae: Double?,
    val acceptedStandardRatio: Double?,
    val accepted: Boolean
)

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
    val onsitePreview: GridOnsiteFitPreview? = null,
    val fittingInProgress: Boolean = false,
    /**
     * 用户是否已经明确确认当前分析物方案。
     *
     * 该状态只属于本次布局编辑会话，不写入模型参数或模板 JSON。只要定量方式、资源、
     * 标准孔或相关孔位发生变化，ViewModel 就会把它重置为 false，防止旧确认误用于新方案。
     */
    val configurationConfirmed: Boolean = false
)

/**
 * 当前草稿是否已经具备可供用户确认的完整信息。
 *
 * 现场拟合必须真正产出拟合预览；资源模式必须选择了兼容资源；仅信号无需额外输入。
 * 该纯函数同时供 ViewModel 二次校验和 Compose 按钮门控使用，避免两处规则漂移。
 */
fun GridAnalyteQuantitationDraft.isReadyForConfirmation(): Boolean = when (mode) {
    GridAnalyteQuantitationMode.ONSITE_AUTO_FIT -> onsitePreview != null && !fittingInProgress
    GridAnalyteQuantitationMode.EXISTING_STANDARD_CURVE,
    GridAnalyteQuantitationMode.DEEP_LEARNING_MODEL -> !selectedAnalysisModelId.isNullOrBlank()
    GridAnalyteQuantitationMode.SIGNAL_ONLY -> true
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
    val builtInShared: Boolean = false
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
