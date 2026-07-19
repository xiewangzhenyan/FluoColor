package com.muc.fluocolorquant.data.enums

/**
 * 多模态领域枚举的公共约定。
 *
 * [code] 会进入 Room 数据库、模板快照和导出文件，因此它是稳定持久化协议，
 * 不能直接依赖枚举名称或面向用户的翻译文本。
 */
private interface StableDomainCode {
    val code: String
}

/**
 * 严格按照持久化编码恢复枚举。
 *
 * 未知编码返回 null，由上层显示“不兼容/需要迁移”，不能静默套用第一个默认值，
 * 否则历史实验可能被错误解释。
 */
private inline fun <reified T> findByStableCode(code: String?): T?
    where T : Enum<T>, T : StableDomainCode {
    return enumValues<T>().firstOrNull { it.code == code }
}

/** 实验载体的物理类型。 */
enum class CarrierType(override val code: String) : StableDomainCode {
    PLATE("PLATE"),
    MICROFLUIDIC_CHIP("MICROFLUIDIC_CHIP"),
    CUSTOM("CUSTOM");

    companion object {
        fun fromCode(code: String?): CarrierType? = findByStableCode(code)
    }
}

/** 载体单元在几何定位和结果展示中的形状。 */
enum class SiteShape(override val code: String) : StableDomainCode {
    CIRCLE("CIRCLE"),
    SQUARE("SQUARE"),
    POINT("POINT"),
    CUSTOM("CUSTOM");

    companion object {
        fun fromCode(code: String?): SiteShape? = findByStableCode(code)
    }
}

/** 可版本化实验资源的通用生命周期状态。 */
enum class ResourceStatus(override val code: String) : StableDomainCode {
    ACTIVE("ACTIVE"),
    LEGACY("LEGACY"),
    ARCHIVED("ARCHIVED");

    companion object {
        fun fromCode(code: String?): ResourceStatus? = findByStableCode(code)
    }
}

/** 图像或光谱在载体上的读出组织方式。 */
enum class ReadoutLayout(override val code: String) : StableDomainCode {
    GRID_SITES("GRID_SITES"),
    SPECTRAL_TRACKS("SPECTRAL_TRACKS"),
    SINGLE_REGION("SINGLE_REGION"),
    PER_SITE_SPECTRUM("PER_SITE_SPECTRUM");

    companion object {
        fun fromCode(code: String?): ReadoutLayout? = findByStableCode(code)
    }
}

/**
 * 分析模型所要求的输入协议。
 *
 * 该字段比“检测模态”更具体：比色和荧光当前使用单终点，普通光谱使用单图，
 * LSPR 则要求同一通道的基线—终点配对。
 */
enum class InputProtocol(override val code: String) : StableDomainCode {
    ENDPOINT_ONLY("ENDPOINT_ONLY"),
    SINGLE_SPECTRUM_ANALYSIS("SINGLE_SPECTRUM_ANALYSIS"),
    LSPR_PAIRED_QUANTIFICATION("LSPR_PAIRED_QUANTIFICATION");

    companion object {
        fun fromCode(code: String?): InputProtocol? = findByStableCode(code)
    }
}

/** 实验方案模板的发布生命周期。 */
enum class TemplateLifecycleStatus(override val code: String) : StableDomainCode {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    ARCHIVED("ARCHIVED"),
    LEGACY("LEGACY");

    companion object {
        fun fromCode(code: String?): TemplateLifecycleStatus? = findByStableCode(code)
    }
}

/** 分析模型的实现类型。 */
enum class AnalysisModelType(override val code: String) : StableDomainCode {
    STANDARD_CURVE("STANDARD_CURVE"),
    DEEP_LEARNING("DEEP_LEARNING");

    companion object {
        fun fromCode(code: String?): AnalysisModelType? = findByStableCode(code)
    }
}

/**
 * 一次检测运行中的原始采集附件角色。
 *
 * 设备波长标定图不属于这里，它应进入独立的设备标定档案；这里仅描述项目运行输入。
 */
enum class CaptureRole(override val code: String) : StableDomainCode {
    ENDPOINT("ENDPOINT"),
    SPECTRUM_SINGLE("SPECTRUM_SINGLE"),
    DARK("DARK"),
    REFERENCE("REFERENCE"),
    PRE_ANALYTE_BASELINE("PRE_ANALYTE_BASELINE"),
    POST_REACTION_ENDPOINT("POST_REACTION_ENDPOINT");

    companion object {
        fun fromCode(code: String?): CaptureRole? = findByStableCode(code)
    }
}

/**
 * 从旧数据库迁移到新领域模型时使用的显式默认语义。
 *
 * 这些值只用于兼容历史数据，不代表新建模板或项目的默认选择。
 */
object LegacyDomainDefaults {
    val PROJECT_CARRIER_TYPE: CarrierType = CarrierType.PLATE
    val TEMPLATE_STATUS: TemplateLifecycleStatus = TemplateLifecycleStatus.LEGACY
    val STANDARD_INPUT_PROTOCOL: InputProtocol = InputProtocol.ENDPOINT_ONLY
    val SPECTRUM_INPUT_PROTOCOL: InputProtocol = InputProtocol.SINGLE_SPECTRUM_ANALYSIS
}
