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

/** 全局晶格平差诊断，供帧级 QC、原图叠加和 Android/Python 对照使用。 */
data class GridGeometryDiagnostics(
    val model: String = "homography",
    val candidateSupportRatio: Double?,
    val trusted: Boolean,
    val observedRatio: Double,
    val geometryRmsePx: Double?,
    val inlierCount: Int,
    val outlierCount: Int,
    val meanConfidence: Double
) {
    fun requireValid(siteCount: Int): GridGeometryDiagnostics = apply {
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
