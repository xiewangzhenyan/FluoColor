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

/**
 * 检测信号的科学模态。
 *
 * 该编码会同时进入采集设备、分析模型、实验模板和项目快照，因此必须位于领域层，
 * 不能由某个 Compose 页面私自维护另一套字符串常量。
 */
enum class DetectionModality(override val code: String) : StableDomainCode {
    COLORIMETRIC("COLORIMETRIC"),
    FLUORESCENCE("FLUORESCENCE"),
    SPECTRUM("SPECTRUM");

    companion object {
        fun fromCode(code: String?): DetectionModality? = findByStableCode(code)
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
 * 实验模板中的通用位点角色。
 *
 * 这些编码同时进入模板子表、项目快照、定位结果和导出文件，禁止使用孔板专属的
 * “well role”命名。未配置位点不保存记录；确实不参与实验的物理位点使用 [DISABLED]。
 */
enum class TemplateSiteRole(override val code: String) : StableDomainCode {
    SAMPLE("SAMPLE"),
    STANDARD("STANDARD"),
    BLANK("BLANK"),
    NEGATIVE_CONTROL("NEGATIVE_CONTROL"),
    POSITIVE_CONTROL("POSITIVE_CONTROL"),
    REFERENCE("REFERENCE"),
    DISABLED("DISABLED");

    companion object {
        fun fromCode(code: String?): TemplateSiteRole? = findByStableCode(code)
    }
}

/** 空白或参考位的作用范围，默认只服务所属分析物。 */
enum class TemplateReferenceScope(override val code: String) : StableDomainCode {
    ANALYTE("ANALYTE"),
    GLOBAL("GLOBAL");

    companion object {
        fun fromCode(code: String?): TemplateReferenceScope? = findByStableCode(code)
    }
}

/**
 * 分析模型的发布生命周期。
 *
 * 草稿允许原地补全；已发布版本不可覆盖，修改时必须创建下一版本；归档版本只供历史
 * 项目和审计读取。旧曲线迁移得到的记录使用 [LEGACY]，不会自动参与新模板匹配。
 */
enum class AnalysisModelLifecycleStatus(override val code: String) : StableDomainCode {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    ARCHIVED("ARCHIVED"),
    LEGACY("LEGACY");

    companion object {
        fun fromCode(code: String?): AnalysisModelLifecycleStatus? = findByStableCode(code)
    }
}

/**
 * 分析模型用于定量或指标计算的稳定主特征编码。
 *
 * 这里只列入当前设计已经定义且能够解释的特征；后续新增特征必须同时补充处理器版本、
 * 兼容性校验和发布验证，不能仅在界面中增加一个自由文本选项。
 */
enum class AnalysisPrimaryFeature(override val code: String) : StableDomainCode {
    DELTA_E_2000("DELTA_E_2000"),
    OPTICAL_DENSITY("OPTICAL_DENSITY"),
    // 常用 RGB/灰度特征保留为比色高级选项，用于兼容已有实验数据和多列 CSV。
    // 新模型仍优先推荐 ΔE2000 或光密度，不能把这些特征静默用于荧光定量。
    GRAY_LUMINOSITY("GRAY_LUMINOSITY"),
    RED_INTENSITY("RED_INTENSITY"),
    GREEN_INTENSITY("GREEN_INTENSITY"),
    BLUE_INTENSITY("BLUE_INTENSITY"),
    AVERAGE_RGB("AVERAGE_RGB"),
    NET_FLUORESCENCE_INTENSITY("NET_FLUORESCENCE_INTENSITY"),
    INTEGRATED_FLUORESCENCE_INTENSITY("INTEGRATED_FLUORESCENCE_INTENSITY"),
    FLUORESCENCE_SNR("FLUORESCENCE_SNR"),
    PEAK_WAVELENGTH_NM("PEAK_WAVELENGTH_NM"),
    DELTA_PEAK_WAVELENGTH_NM("DELTA_PEAK_WAVELENGTH_NM");

    companion object {
        fun fromCode(code: String?): AnalysisPrimaryFeature? = findByStableCode(code)
    }
}

/**
 * 一次检测运行中的原始采集与派生处理证据角色。
 *
 * 设备波长标定图不属于这里，它应进入独立的设备标定档案；这里仅描述项目运行输入。
 * `PROCESS_*` 角色是算法从冻结原图生成的只读诊断证据，不得作为定量输入再次计算。
 */
enum class CaptureRole(override val code: String) : StableDomainCode {
    ENDPOINT("ENDPOINT"),
    SPECTRUM_SINGLE("SPECTRUM_SINGLE"),
    DARK("DARK"),
    REFERENCE("REFERENCE"),
    PRE_ANALYTE_BASELINE("PRE_ANALYTE_BASELINE"),
    POST_REACTION_ENDPOINT("POST_REACTION_ENDPOINT"),
    PROCESS_ORIGINAL_GEOMETRY("PROCESS_ORIGINAL_GEOMETRY"),
    PROCESS_CANDIDATE_RESPONSE("PROCESS_CANDIDATE_RESPONSE"),
    /** 96孔板按照A1～H12标准方向生成的无插值工作图。 */
    PROCESS_ORIENTATION_NORMALIZED("PROCESS_ORIENTATION_NORMALIZED"),
    /** 96孔板目标检测候选叠加图。 */
    PROCESS_YOLO_OVERLAY("PROCESS_YOLO_OVERLAY"),
    /** 96孔板霍夫圆/轮廓圆精定位叠加图。 */
    PROCESS_HOUGH_CIRCLE_OVERLAY("PROCESS_HOUGH_CIRCLE_OVERLAY"),
    /** 将标准孔号和圆孔位置反投影回原图的证据图。 */
    PROCESS_ORIGINAL_PROJECTION_OVERLAY("PROCESS_ORIGINAL_PROJECTION_OVERLAY"),
    /** 96个圆孔无损裁切的接触表。 */
    PROCESS_CROP_CONTACT_SHEET("PROCESS_CROP_CONTACT_SHEET"),
    PROCESS_RECTIFIED("PROCESS_RECTIFIED"),
    PROCESS_GRID_OVERLAY("PROCESS_GRID_OVERLAY"),
    PROCESS_ROI_BACKGROUND("PROCESS_ROI_BACKGROUND"),
    PROCESS_BACKGROUND_FIELD("PROCESS_BACKGROUND_FIELD"),
    PROCESS_SIGNAL_HEATMAP("PROCESS_SIGNAL_HEATMAP"),
    PROCESS_SNR_HEATMAP("PROCESS_SNR_HEATMAP"),
    PROCESS_CORRECTED_COLOR("PROCESS_CORRECTED_COLOR");

    /** 结果页据此把原始图与处理过程分开，避免在原图标签错误叠加矫正坐标。 */
    val isProcessingEvidence: Boolean
        get() = name.startsWith("PROCESS_")

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
