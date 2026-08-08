package com.muc.fluocolorquant.domain.detection.grid

import com.google.gson.annotations.SerializedName

/**
 * Android 与 Python PG-Grid 交换数据时使用的稳定结构版本。
 *
 * 版本号会写入检测运行快照和金标准 JSON。任何破坏字段含义、坐标系或单位的修改，
 * 都必须提升该版本，不能依赖 Gson 的缺省值静默解释旧实验结果。
 */
const val PG_GRID_SCHEMA_V2_1: String = "pg-grid-v2.1"

/** 微流控位点在矫正图中的实际目标极性，由图像证据自动裁决，不再与规格绑定。 */
enum class GridTargetPolarity {
    @SerializedName("dark")
    DARK,

    @SerializedName("bright")
    BRIGHT
}

/**
 * 最终点位坐标的来源。
 *
 * [CANDIDATE_REFINED] 表示局部图像存在真实候选证据；[MODEL_IMPUTED] 表示该位点
 * 只由全局晶格模型补齐；[UNADJUSTED] 表示全局平差无法可靠执行，保留上游点位。
 */
enum class GridPointSource {
    @SerializedName("candidate_refined")
    CANDIDATE_REFINED,

    @SerializedName("model_imputed")
    MODEL_IMPUTED,

    @SerializedName("unadjusted")
    UNADJUSTED
}

/** 只描述几何定位阶段产生的逐位点标志，光度 QC 使用独立契约。 */
enum class GridSiteFlag {
    @SerializedName("imputed_position")
    IMPUTED_POSITION,

    @SerializedName("low_local_evidence")
    LOW_LOCAL_EVIDENCE,

    @SerializedName("extrapolated_position")
    EXTRAPOLATED_POSITION
}

/** 帧级质量问题的严重程度；页面稍后把稳定枚举映射到中英文资源。 */
enum class GridQcSeverity {
    @SerializedName("info")
    INFO,

    @SerializedName("warning")
    WARNING,

    @SerializedName("failure")
    FAILURE
}

/**
 * PG-Grid 帧级质量原因码。
 *
 * 原因码进入数据库和导出文件，因此不能直接保存面向用户的中文文本；Compose 页面
 * 应根据原因码选择 strings.xml 文案和可执行建议。
 */
enum class GridFrameQcCode {
    @SerializedName("chip_region_fallback")
    CHIP_REGION_FALLBACK,

    @SerializedName("grid_support_low")
    GRID_SUPPORT_LOW,

    /**
     * 网格没有覆盖邻域内足够多的已检出单元，通常说明主区域框把阵列截断了。
     *
     * 与 [GRID_SUPPORT_LOW] 方向相反：支撑率问“网格点旁边有没有单元”，对被区域框
     * 切掉的整行无感（那些单元根本没进矫正图）；包围率问“图里的单元有没有被网格
     * 覆盖”，因此能直接抓住区域截断。
     */
    @SerializedName("grid_coverage_low")
    GRID_COVERAGE_LOW,

    /**
     * 晶格疑似整体平移一个间距，凭空多出一条边缘行或列。
     *
     * 这类错误对支撑率、包围率和观测率全部免疫——三者都是共享行上的比值，平移后
     * 几乎不动，只能在边界看见。判据见 PgGridLatticeIntegrity。
     */
    @SerializedName("phantom_lattice_edge")
    PHANTOM_LATTICE_EDGE,

    @SerializedName("high_imputed_ratio")
    HIGH_IMPUTED_RATIO,

    @SerializedName("geometry_rmse_high")
    GEOMETRY_RMSE_HIGH,

    @SerializedName("over_exposed")
    OVER_EXPOSED,

    @SerializedName("under_exposed")
    UNDER_EXPOSED,

    @SerializedName("blurred")
    BLURRED,

    @SerializedName("illumination_non_uniform")
    ILLUMINATION_NON_UNIFORM,

    @SerializedName("perspective_excessive")
    PERSPECTIVE_EXCESSIVE
}

/** 零基位点行列键；持久化顺序必须与 `siteIndex = row * columns + column` 一致。 */
data class GridSiteKey(
    val rowIndex: Int,
    val columnIndex: Int
) {
    init {
        require(rowIndex >= 0) { "位点行号不能为负数" }
        require(columnIndex >= 0) { "位点列号不能为负数" }
    }
}

/** 二维像素坐标；当前 V2.1 坐标原点均为图像左上角，x 向右、y 向下。 */
data class GridPoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite()) { "位点坐标必须是有限数值" }
    }
}

/** 原图到矫正图及其逆向映射，矩阵均采用行主序 3×3 共 9 个 Double。 */
data class GridHomography(
    val forward: List<Double>,
    val inverse: List<Double>
) {
    /** 显式验证可防止从 JSON 读取到长度错误或包含 NaN 的矩阵。 */
    fun requireValid(): GridHomography = apply {
        require(forward.size == HOMOGRAPHY_ELEMENT_COUNT) { "正向单应矩阵必须包含 9 个元素" }
        require(inverse.size == HOMOGRAPHY_ELEMENT_COUNT) { "逆向单应矩阵必须包含 9 个元素" }
        require(forward.all(Double::isFinite)) { "正向单应矩阵包含无效数值" }
        require(inverse.all(Double::isFinite)) { "逆向单应矩阵包含无效数值" }
    }

    private companion object {
        const val HOMOGRAPHY_ELEMENT_COUNT: Int = 9
    }
}

/** 单个位点的几何定位结果，同时保存矫正图坐标和回投影后的原图坐标。 */
data class GridLocalizedSite(
    val key: GridSiteKey,
    val siteIndex: Int,
    val rectified: GridPoint,
    val original: GridPoint,
    val confidence: Double,
    val source: GridPointSource,
    val flags: Set<GridSiteFlag>
) {
    /** 验证与单点自身相关的约束；全阵列顺序由 [PgGridResult.requireValid] 检查。 */
    fun requireValid(): GridLocalizedSite = apply {
        require(siteIndex >= 0) { "位点索引不能为负数" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "位点置信度必须位于 0 到 1"
        }
        if (source == GridPointSource.MODEL_IMPUTED) {
            require(GridSiteFlag.IMPUTED_POSITION in flags) {
                "模型补位点必须携带 imputed_position 标志"
            }
        }
    }

    companion object {
        /**
         * 按 Python PG-Grid 已验证契约创建模型补位点。
         *
         * 0.3 是“存在全局晶格约束但没有局部图像证据”的固定置信度，不代表图像算法
         * 真实观测到了该位点，结果页必须继续展示补位警告。
         */
        fun modelImputed(
            key: GridSiteKey,
            siteIndex: Int,
            rectified: GridPoint,
            original: GridPoint
        ): GridLocalizedSite {
            return GridLocalizedSite(
                key = key,
                siteIndex = siteIndex,
                rectified = rectified,
                original = original,
                confidence = MODEL_IMPUTED_CONFIDENCE,
                source = GridPointSource.MODEL_IMPUTED,
                flags = setOf(GridSiteFlag.IMPUTED_POSITION)
            )
        }

        const val MODEL_IMPUTED_CONFIDENCE: Double = 0.3
    }
}

/**
 * 候选支撑率检查的三态结论。
 *
 * 必须与“支撑率数值”分开保存：支撑率为 0 既可能是网格真的错了，也可能是检测器在
 * 这张图上整体失效（重模糊、单元尺寸超出闸值）。前者应判不可信，后者属于“检查手段
 * 缺席”，据此判错会误伤逐点精修已经充分的正确结果。
 */
enum class GridSupportCheck {
    /** 候选充足，支撑率结论有效。 */
    @SerializedName("ok")
    OK,

    /** 检测器几乎没有产出候选，本次检查无法给出结论，不据此判不可信。 */
    @SerializedName("inconclusive")
    INCONCLUSIVE,

    /** 当前规格没有可用的候选检测器，检查不适用。 */
    @SerializedName("unavailable")
    UNAVAILABLE
}

/**
 * 单个主区域假设的仲裁证据。
 *
 * 三条区域检测路径各有系统性偏好，没有一条在所有成像条件下占优，因此不按固定优先级
 * 取一条，而是各自走完晶格拟合后用图像证据择优。本结构如实记录每个假设的得分，
 * 使“为什么选了这个区域”在过程证据和历史快照中可复查。
 */
data class GridRegionHypothesis(
    val method: String,
    val coverage: Double,
    val support: Double?,
    val score: Double,
    val selected: Boolean
) {
    fun requireValid(): GridRegionHypothesis = apply {
        require(method.isNotBlank()) { "区域假设方法名不能为空" }
        require(coverage.isFinite() && coverage in 0.0..1.0) { "区域包围率必须位于 0 到 1" }
        require(support == null || support in 0.0..1.0) { "区域支撑率必须位于 0 到 1" }
        require(score.isFinite()) { "区域仲裁得分必须为有限数值" }
    }
}

/**
 * 幻影边缘诊断：晶格是否整体平移了一个间距。
 *
 * [flagged] 为被判定为幻影的边缘名（row_lo/row_hi/col_lo/col_hi），无则为 null；
 * [available] 为 false 表示当前没有候选可供检查，不代表检查通过。
 */
data class GridPhantomEdgeDiagnostics(
    val available: Boolean,
    val flagged: String? = null,
    val flaggedOverhang: Double? = null,
    val anisotropy: Double? = null,
    val rowPitchPx: Double? = null,
    val columnPitchPx: Double? = null
) {
    fun requireValid(): GridPhantomEdgeDiagnostics = apply {
        require(flaggedOverhang == null || flaggedOverhang.isFinite()) { "幻影外伸量必须为有限数值" }
        require(anisotropy == null || anisotropy.isFinite() && anisotropy >= 0.0) {
            "两轴间距失配必须为非负有限数值"
        }
        if (flagged != null) {
            require(flagged.isNotBlank()) { "幻影边缘名不能为空白" }
            require(available) { "标记幻影边缘时检查必须可用" }
        }
    }
}

/**
 * 全局晶格平差诊断，供帧级 QC、原图叠加和 Android/Python 对照使用。
 *
 * 后四个字段一律**可空**，而不是给非空类型配 Kotlin 默认值：Gson 不识别 Kotlin 的默认
 * 参数，JSON 缺键时会直接注入 null，非空声明只会在 requireValid 里变成 NPE。可空同时
 * 也更贴近语义——历史运行快照确实没有执行过这些检查，null 表示“无此证据”，而不是
 * “检查通过”。旧结果不因此重新定位或重新计算。
 */
data class GridGeometryDiagnostics(
    val model: String = "homography",
    val candidateSupportRatio: Double?,
    val trusted: Boolean,
    val observedRatio: Double,
    val geometryRmsePx: Double?,
    val inlierCount: Int,
    val outlierCount: Int,
    val meanConfidence: Double,
    /** 支撑率检查的三态结论；旧快照未执行该检查时为 null。 */
    val supportCheck: GridSupportCheck? = null,
    /** 原图坐标系下“已检出单元被网格覆盖”的比例；旧快照没有该证据时为 null。 */
    val gridCoverageRatio: Double? = null,
    /** 本次实际展开并比较过的主区域假设；证据充分而短路时可能只有一项，旧快照为 null。 */
    val regionHypotheses: List<GridRegionHypothesis>? = null,
    /** 幻影边缘检查结果；旧快照没有执行过该检查时为 null。 */
    val phantomEdge: GridPhantomEdgeDiagnostics? = null
) {
    fun requireValid(siteCount: Int): GridGeometryDiagnostics = apply {
        require(gridCoverageRatio == null || gridCoverageRatio in 0.0..1.0) {
            "网格包围率必须位于 0 到 1"
        }
        regionHypotheses?.forEach(GridRegionHypothesis::requireValid)
        require(regionHypotheses.orEmpty().count(GridRegionHypothesis::selected) <= 1) {
            "最多只能有一个区域假设被选中"
        }
        phantomEdge?.requireValid()
        require(model.isNotBlank()) { "晶格模型名称不能为空" }
        require(candidateSupportRatio == null || candidateSupportRatio in 0.0..1.0) {
            "候选支撑率必须位于 0 到 1"
        }
        require(observedRatio in 0.0..1.0) { "有效观测比例必须位于 0 到 1" }
        require(geometryRmsePx == null || geometryRmsePx.isFinite() && geometryRmsePx >= 0.0) {
            "几何 RMSE 必须为非负有限数值"
        }
        require(inlierCount >= 0 && outlierCount >= 0) { "内点和外点数量不能为负数" }
        require(inlierCount + outlierCount <= siteCount) { "内点与外点数量超过阵列位点总数" }
        require(meanConfidence.isFinite() && meanConfidence in 0.0..1.0) {
            "平均置信度必须位于 0 到 1"
        }
    }
}

/** 帧级问题只保存稳定机器码和结构化数值，用户文案由 UI 资源映射。 */
data class GridFrameQcIssue(
    val code: GridFrameQcCode,
    val severity: GridQcSeverity,
    val measuredValue: Double? = null,
    val threshold: Double? = null
) {
    fun requireValid(): GridFrameQcIssue = apply {
        require(measuredValue == null || measuredValue.isFinite()) { "QC 实测值必须为有限数值" }
        require(threshold == null || threshold.isFinite()) { "QC 阈值必须为有限数值" }
    }
}

/**
 * 一次 PG-Grid 定位的完整不可变结果。
 *
 * 矫正图只用于定位和诊断；后续科学光度应通过 [homography] 把 ROI 映射回原始定量图，
 * 避免透视插值、CLAHE 或伪彩增强改变保存的科学信号。
 */
data class PgGridResult(
    val schemaVersion: String = PG_GRID_SCHEMA_V2_1,
    val rows: Int,
    val columns: Int,
    val rectifiedWidth: Int,
    val rectifiedHeight: Int,
    /** 定位器根据当前图片实际裁决的极性，而不是载体配置的未经验证默认值。 */
    val targetPolarity: GridTargetPolarity,
    val chipRegionMethod: String,
    val chipCorners: List<GridPoint>,
    val homography: GridHomography,
    val sites: List<GridLocalizedSite>,
    val geometry: GridGeometryDiagnostics,
    val frameQc: List<GridFrameQcIssue>,
    val locatorName: String,
    val locatorVersion: String
) {
    /**
     * 对构造对象和 JSON 解码对象执行同一套强校验。
     *
     * Gson 在部分运行时可能绕过 Kotlin init，因此科学契约不能只依赖构造器检查。
     */
    fun requireValid(): PgGridResult = apply {
        require(schemaVersion == PG_GRID_SCHEMA_V2_1) { "不支持的 PG-Grid schema：$schemaVersion" }
        require(rows > 0 && columns > 0) { "阵列行列必须大于 0" }
        require(rectifiedWidth > 0 && rectifiedHeight > 0) { "矫正图尺寸必须大于 0" }
        require(chipRegionMethod.isNotBlank()) { "芯片区域定位方法不能为空" }
        require(chipCorners.size == CHIP_CORNER_COUNT) { "芯片区域必须包含四个有序角点" }
        require(locatorName.isNotBlank() && locatorVersion.isNotBlank()) { "定位器名称和版本不能为空" }
        homography.requireValid()

        val expectedCount = rows * columns
        require(sites.size == expectedCount) {
            "阵列应包含 $expectedCount 个位点，实际为 ${sites.size}"
        }
        val uniqueKeys = HashSet<GridSiteKey>(expectedCount)
        sites.forEachIndexed { expectedIndex, site ->
            site.requireValid()
            val expectedKey = GridSiteKey(
                rowIndex = expectedIndex / columns,
                columnIndex = expectedIndex % columns
            )
            require(site.siteIndex == expectedIndex) { "位点索引必须按行优先连续排列" }
            require(site.key == expectedKey) { "位点行列与行优先索引不一致" }
            require(uniqueKeys.add(site.key)) { "阵列包含重复位点：${site.key}" }
        }
        geometry.requireValid(siteCount = expectedCount)
        frameQc.forEach(GridFrameQcIssue::requireValid)
    }

    private companion object {
        const val CHIP_CORNER_COUNT: Int = 4
    }
}
