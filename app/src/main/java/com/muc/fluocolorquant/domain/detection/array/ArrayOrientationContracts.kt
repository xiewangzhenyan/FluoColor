package com.muc.fluocolorquant.domain.detection.array

import com.google.gson.annotations.SerializedName

/**
 * 通用阵列方向快照的首个稳定结构版本。
 *
 * 方向信息会进入检测运行快照和历史结果，因此字段语义发生破坏性变化时必须提升版本，
 * 不能依赖 Gson 缺省值静默解释已经完成的实验。
 */
const val ARRAY_ORIENTATION_SCHEMA_V1: String = "array-orientation-v1"

/** 将原始工作图旋转到载体标准方向时使用的整数四分之一圈。 */
enum class ArrayQuarterTurn(val degreesClockwise: Int) {
    @SerializedName("rotate_0")
    ROTATE_0(0),

    @SerializedName("rotate_90_cw")
    ROTATE_90_CW(90),

    @SerializedName("rotate_180")
    ROTATE_180(180),

    @SerializedName("rotate_270_cw")
    ROTATE_270_CW(270)
}

/** 方向结论的来源；历史页据此区分算法裁决、用户确认和旧数据假设。 */
enum class ArrayOrientationSource {
    @SerializedName("auto")
    AUTO,

    @SerializedName("user_confirmed")
    USER_CONFIRMED,

    @SerializedName("legacy_assumed")
    LEGACY_ASSUMED
}

/**
 * 标准阵列原点（96孔板即 A1）在原图中的角落。
 *
 * 该枚举不绑定“孔板”名称，因此以后带明确方向标记的其他规则阵列也可以复用。
 */
enum class ArrayOriginCorner {
    @SerializedName("top_left")
    TOP_LEFT,

    @SerializedName("top_right")
    TOP_RIGHT,

    @SerializedName("bottom_left")
    BOTTOM_LEFT,

    @SerializedName("bottom_right")
    BOTTOM_RIGHT
}

/** 零基阵列坐标；物理身份不能由当前屏幕排序或检测框原始顺序推断。 */
data class ArrayGridCoordinate(
    val rowIndex: Int,
    val columnIndex: Int
) {
    init {
        require(rowIndex >= 0 && columnIndex >= 0) { "阵列行列不能为负数" }
    }
}

/** 图像左上角为原点、x 向右、y 向下的有限二维坐标。 */
data class ArrayImagePoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x.isFinite() && y.isFinite()) { "图像坐标必须为有限数值" }
    }
}

/**
 * 原图到标准方向工作图的正逆矩阵快照。
 *
 * 两个矩阵均为行主序 3×3。96孔板的90°整数旋转不会插值，但仍冻结矩阵，保证原图
 * 叠加、手动微调和历史恢复始终使用同一套像素对应关系。
 */
data class ArrayImageTransformSnapshot(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val normalizedWidth: Int,
    val normalizedHeight: Int,
    val sourceToNormalized: List<Double>,
    val normalizedToSource: List<Double>,
    val processorVersion: String
) {
    fun requireValid(): ArrayImageTransformSnapshot = apply {
        require(sourceWidth > 0 && sourceHeight > 0) { "原图宽高必须大于0" }
        require(normalizedWidth > 0 && normalizedHeight > 0) { "校正图宽高必须大于0" }
        require(sourceToNormalized.size == MATRIX_ELEMENT_COUNT) { "正向矩阵必须包含9个元素" }
        require(normalizedToSource.size == MATRIX_ELEMENT_COUNT) { "逆向矩阵必须包含9个元素" }
        require(sourceToNormalized.all(Double::isFinite)) { "正向矩阵包含无效数值" }
        require(normalizedToSource.all(Double::isFinite)) { "逆向矩阵包含无效数值" }
        require(processorVersion.isNotBlank()) { "图像方向处理器版本不能为空" }
    }

    private companion object {
        const val MATRIX_ELEMENT_COUNT: Int = 9
    }
}

/**
 * 一次阵列运行冻结的标准规格、原图呈现规格和方向裁决。
 *
 * [canonicalRows]/[canonicalColumns] 是科学身份；[sourceRows]/[sourceColumns] 只是图片
 * 当前呈现。96孔板始终保持8×12，竖拍照片只会令 source 变为12×8。
 */
data class ArrayOrientationSnapshot(
    val schemaVersion: String = ARRAY_ORIENTATION_SCHEMA_V1,
    val canonicalRows: Int,
    val canonicalColumns: Int,
    val sourceRows: Int,
    val sourceColumns: Int,
    val rotation: ArrayQuarterTurn,
    val mirrored: Boolean,
    val originCorner: ArrayOriginCorner,
    val source: ArrayOrientationSource,
    val confidence: Double?
) {
    val canonicalSiteCount: Int get() = canonicalRows * canonicalColumns

    fun requireValid(): ArrayOrientationSnapshot = apply {
        require(schemaVersion == ARRAY_ORIENTATION_SCHEMA_V1) {
            "不支持的阵列方向快照版本：$schemaVersion"
        }
        require(canonicalRows > 0 && canonicalColumns > 0) { "标准阵列行列必须大于0" }
        require(sourceRows > 0 && sourceColumns > 0) { "原图阵列行列必须大于0" }
        val swapsAxes = rotation == ArrayQuarterTurn.ROTATE_90_CW ||
            rotation == ArrayQuarterTurn.ROTATE_270_CW
        val expectedSourceRows = if (swapsAxes) canonicalColumns else canonicalRows
        val expectedSourceColumns = if (swapsAxes) canonicalRows else canonicalColumns
        require(sourceRows == expectedSourceRows && sourceColumns == expectedSourceColumns) {
            "原图行列与旋转方向不一致：期望${expectedSourceRows}×${expectedSourceColumns}，" +
                "实际${sourceRows}×${sourceColumns}"
        }
        require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0) {
            "方向置信度必须位于0到1"
        }
    }
}

/** 96孔板唯一的标准科学布局；任何图片方向都必须回到该契约。 */
object Plate96LayoutContract {
    const val ROWS: Int = 8
    const val COLUMNS: Int = 12
    const val SITE_COUNT: Int = ROWS * COLUMNS

    /** 将标准行列转换为稳定行优先索引。 */
    fun siteIndex(rowIndex: Int, columnIndex: Int): Int {
        require(rowIndex in 0 until ROWS) { "96孔板行号越界" }
        require(columnIndex in 0 until COLUMNS) { "96孔板列号越界" }
        return rowIndex * COLUMNS + columnIndex
    }

    /** 将稳定索引恢复为标准行列。 */
    fun coordinate(siteIndex: Int): ArrayGridCoordinate {
        require(siteIndex in 0 until SITE_COUNT) { "96孔板位点索引越界" }
        return ArrayGridCoordinate(
            rowIndex = siteIndex / COLUMNS,
            columnIndex = siteIndex % COLUMNS
        )
    }

    /** 生成面向用户的标准孔号，例如 A1、A10、H12。 */
    fun displayLabel(rowIndex: Int, columnIndex: Int): String {
        siteIndex(rowIndex, columnIndex)
        return "${('A'.code + rowIndex).toChar()}${columnIndex + 1}"
    }

    /** 根据原点所在角落返回将原图旋转到标准方向所需的整数旋转。 */
    fun rotationForOriginCorner(originCorner: ArrayOriginCorner): ArrayQuarterTurn {
        return when (originCorner) {
            ArrayOriginCorner.TOP_LEFT -> ArrayQuarterTurn.ROTATE_0
            ArrayOriginCorner.BOTTOM_LEFT -> ArrayQuarterTurn.ROTATE_90_CW
            ArrayOriginCorner.BOTTOM_RIGHT -> ArrayQuarterTurn.ROTATE_180
            ArrayOriginCorner.TOP_RIGHT -> ArrayQuarterTurn.ROTATE_270_CW
        }
    }

    /** 构造通过强校验的96孔板方向快照。 */
    fun orientation(
        originCorner: ArrayOriginCorner,
        source: ArrayOrientationSource,
        confidence: Double?,
        mirrored: Boolean = false
    ): ArrayOrientationSnapshot {
        val rotation = rotationForOriginCorner(originCorner)
        val swapsAxes = rotation == ArrayQuarterTurn.ROTATE_90_CW ||
            rotation == ArrayQuarterTurn.ROTATE_270_CW
        return ArrayOrientationSnapshot(
            canonicalRows = ROWS,
            canonicalColumns = COLUMNS,
            sourceRows = if (swapsAxes) COLUMNS else ROWS,
            sourceColumns = if (swapsAxes) ROWS else COLUMNS,
            rotation = rotation,
            mirrored = mirrored,
            originCorner = originCorner,
            source = source,
            confidence = confidence
        ).requireValid()
    }
}
