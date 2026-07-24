package com.muc.fluocolorquant.domain.project

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import java.util.Locale

/**
 * 创建项目时冻结的完整实验模板快照。
 *
 * 历史项目必须只依赖该快照重建科学配置，不能在查看结果时重新读取可能已经升级、
 * 归档或被管理员替换的模板、载体、设备和分析模型。版本号用于未来对 JSON 结构执行
 * 显式迁移，禁止用“字段缺失时套默认值”的方式静默改变历史实验语义。
 */
data class TemplateProjectSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val frozenAtEpochMillis: Long,
    val template: ExperimentTemplate,
    val carrierProfile: CarrierProfile,
    val acquisitionProfile: AcquisitionProfile,
    val analytes: List<TemplateProjectAnalyteSnapshot>,
    val siteAssignments: List<TemplateSiteAssignment>,
    /**
     * 布局页临时应用资源库模板时，项目根模板 ID 仍保持项目自己的冻结身份；这里单独记录
     * 来源模板，既保证现有外键/预检关系不被破坏，也让历史结果能追溯本次复用了哪个方案。
     */
    val sourceTemplateId: String? = null,
    val sourceTemplateName: String? = null,
    val sourceTemplateVersion: Int? = null
) {
    companion object {
        /** 当前快照 JSON 的稳定结构版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * 单个分析物在项目快照中的完整定义。
 *
 * 同时保存分析物显示身份、模板中的范围/单位/试剂绑定，以及分析模型主档和类型专用
 * 子表，确保标准曲线原始点或智能模型文件元数据不会因模型库后续升级而漂移。
 */
data class TemplateProjectAnalyteSnapshot(
    val analyte: Analyte,
    val templateConfig: TemplateAnalyteConfig,
    val analysisModel: AnalysisModelBundle,
    /** 为空表示旧快照，运行时会根据冻结模型类型安全推断。 */
    val quantitationMode: String? = null,
    /** 现场拟合高级设置；为空时后台自动比较当前模态允许的全部候选信号。 */
    val onsiteSelectedFeature: String? = null,
    /** 现场拟合高级设置；为空时后台自动比较线性、4PL 和 5PL。 */
    val onsiteSelectedFunction: String? = null
)

/**
 * 项目相对模板的运行期覆盖记录。
 *
 * 样本槽位属于一次具体实验，不属于可复用模板；覆盖原因单独保留，便于报告和导出明确
 * 区分“模板计划值”与“本项目实际值”。
 */
data class TemplateProjectOverrideSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val sampleSlotMapping: Map<String, String>,
    val reasons: Map<String, String> = emptyMap()
) {
    companion object {
        /** 当前项目覆盖 JSON 的稳定结构版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** 模板项目创建所需的少量运行期输入；科学配置全部来自模板快照。 */
data class TemplateProjectCreateRequest(
    val name: String,
    val templateId: String,
    val projectBatch: String = "",
    val sampleBatch: String = "",
    val sampleSlotMapping: Map<String, String>,
    val imageUri: String,
    val userId: String,
    val overrideReasons: Map<String, String> = emptyMap()
)

/** 项目创建结果，页面可据此导航或展示可翻译的预检问题。 */
sealed interface TemplateProjectCreationOutcome {
    data class Created(
        val project: Project,
        val destination: ProjectDetectionDestination
    ) : TemplateProjectCreationOutcome

    data class Blocked(
        val issues: List<TemplatePreflightIssue>
    ) : TemplateProjectCreationOutcome
}

/**
 * 通用阵列位点的稳定显示键。
 *
 * 数据库内部仍保存零基行列，项目覆盖与导出使用 R01C01，避免 A1 在超过 26 行时产生
 * 歧义，也避免孔板专属命名泄漏到微流控芯片领域。
 */
object TemplateSiteKey {
    fun format(rowIndex: Int, columnIndex: Int): String {
        require(rowIndex >= 0 && columnIndex >= 0) { "位点行列不能为负数" }
        return String.format(Locale.ROOT, "R%02dC%02d", rowIndex + 1, columnIndex + 1)
    }
}

/**
 * 项目创建完成后应进入的检测链路。
 *
 * 比色和荧光共享规则阵列几何入口，但其后续光度处理器会在工作包 4 中分离；普通光谱
 * 和 LSPR 配对具有完全不同的采集状态机，因此必须在这里显式区分。
 */
enum class ProjectDetectionDestination {
    GRID_ENDPOINT,
    SPECTRUM_SINGLE,
    LSPR_PAIRED,
    UNSUPPORTED
}

/** 根据稳定领域编码解析检测目的地，任何不一致组合均大声返回不支持。 */
object ProjectDetectionRouter {
    fun resolve(
        detectionMode: String?,
        inputProtocol: String?,
        readoutLayout: String?
    ): ProjectDetectionDestination {
        return when {
            detectionMode in setOf(
                DetectionModality.COLORIMETRIC.code,
                DetectionModality.FLUORESCENCE.code
            ) && inputProtocol == InputProtocol.ENDPOINT_ONLY.code &&
                readoutLayout == ReadoutLayout.GRID_SITES.code -> {
                ProjectDetectionDestination.GRID_ENDPOINT
            }

            detectionMode == DetectionModality.SPECTRUM.code &&
                inputProtocol == InputProtocol.SINGLE_SPECTRUM_ANALYSIS.code &&
                readoutLayout in setOf(
                    ReadoutLayout.SPECTRAL_TRACKS.code,
                    ReadoutLayout.SINGLE_REGION.code,
                    ReadoutLayout.PER_SITE_SPECTRUM.code
                ) -> {
                ProjectDetectionDestination.SPECTRUM_SINGLE
            }

            detectionMode == DetectionModality.SPECTRUM.code &&
                inputProtocol == InputProtocol.LSPR_PAIRED_QUANTIFICATION.code &&
                readoutLayout in setOf(
                    ReadoutLayout.SPECTRAL_TRACKS.code,
                    ReadoutLayout.PER_SITE_SPECTRUM.code
                ) -> {
                ProjectDetectionDestination.LSPR_PAIRED
            }

            else -> ProjectDetectionDestination.UNSUPPORTED
        }
    }
}

/** 模板项目快照的唯一 JSON 编解码入口。 */
object TemplateProjectSnapshotCodec {
    private val gson: Gson = snapshotGson()

    fun encode(snapshot: TemplateProjectSnapshot): String {
        require(snapshot.schemaVersion == TemplateProjectSnapshot.CURRENT_SCHEMA_VERSION) {
            "不支持写入模板项目快照版本：${snapshot.schemaVersion}"
        }
        return gson.toJson(snapshot)
    }

    fun decode(json: String): TemplateProjectSnapshot {
        require(json.isNotBlank()) { "模板项目快照不能为空" }
        val snapshot = parse(json, TemplateProjectSnapshot::class.java, "模板项目快照")
        require(snapshot.schemaVersion == TemplateProjectSnapshot.CURRENT_SCHEMA_VERSION) {
            "不支持的模板项目快照版本：${snapshot.schemaVersion}"
        }
        return snapshot
    }
}

/** 项目覆盖记录的唯一 JSON 编解码入口。 */
object TemplateProjectOverrideCodec {
    private val gson: Gson = snapshotGson()

    fun encode(snapshot: TemplateProjectOverrideSnapshot): String {
        require(snapshot.schemaVersion == TemplateProjectOverrideSnapshot.CURRENT_SCHEMA_VERSION) {
            "不支持写入项目覆盖版本：${snapshot.schemaVersion}"
        }
        return gson.toJson(snapshot)
    }

    fun decode(json: String): TemplateProjectOverrideSnapshot {
        require(json.isNotBlank()) { "项目覆盖记录不能为空" }
        val snapshot = parse(json, TemplateProjectOverrideSnapshot::class.java, "项目覆盖记录")
        require(snapshot.schemaVersion == TemplateProjectOverrideSnapshot.CURRENT_SCHEMA_VERSION) {
            "不支持的项目覆盖版本：${snapshot.schemaVersion}"
        }
        return snapshot
    }
}

/**
 * 统一配置 Gson，保证快照包含显式 null 字段且不对中文和科学符号做 HTML 转义。
 */
private fun snapshotGson(): Gson = GsonBuilder()
    .serializeNulls()
    .disableHtmlEscaping()
    .create()

/** 将底层 Gson 异常转换为上层可识别的契约异常，避免页面依赖 JSON 库细节。 */
private fun <T> parse(json: String, type: Class<T>, label: String): T {
    return try {
        snapshotGson().fromJson(json, type)
            ?: throw IllegalArgumentException("$label 解析结果为空")
    } catch (error: JsonParseException) {
        throw IllegalArgumentException("$label JSON 无效", error)
    }
}
