package com.muc.fluocolorquant.domain.result.plate96

import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

/** 标准96孔板固定采用行业标准的8行×12列语义；自定义圆孔板使用各自冻结行列。 */
const val PLATE96_RESULT_ROWS: Int = 8
const val PLATE96_RESULT_COLUMNS: Int = 12
const val PLATE96_RESULT_SITE_COUNT: Int = PLATE96_RESULT_ROWS * PLATE96_RESULT_COLUMNS

/**
 * 96孔板结果映射失败原因。
 *
 * 页面只显示稳定、可翻译的错误，不把Room、JSON或内部类名直接暴露给普通用户。
 */
enum class Plate96ResultErrorCode {
    SOURCE_NOT_AVAILABLE,
    NOT_A_LEGACY_PLATE96_RUN,
    INVALID_LEGACY_WELL_INDEX,
    NOT_A_PLATE_CARRIER,
    NOT_A_CIRCULAR_CARRIER,
    INVALID_STANDARD_LAYOUT,
    INCOMPLETE_WELL_INDEX,
    INCONSISTENT_WELL_COORDINATE
}

/**
 * 96孔板结果的数据来源。
 *
 * 新运行拥有完整冻结几何和过程附件；旧运行只保存 `WellResult`，适配时必须显式标记，
 * 这样结果页不会把逻辑8×12坐标误称为重新定位得到的图像证据。
 */
enum class Plate96ResultSource {
    MODERN_SNAPSHOT,
    LEGACY_WELL_RESULT
}

sealed interface Plate96ResultLoadResult {
    data class Success(val snapshot: Plate96ResultSnapshot) : Plate96ResultLoadResult
    data class Failure(val errorCode: Plate96ResultErrorCode) : Plate96ResultLoadResult
}

/**
 * 单个标准孔位。
 *
 * [wellLabel] 使用Excel式行号与一基列号；标准96孔板仍稳定为A1～H12。底层科学测量
 * 保留在[site]中，避免复制浓度、信号和质量字段后产生两套互相漂移的数据。
 */
data class Plate96WellResult(
    val wellIndex: Int,
    val rowIndex: Int,
    val columnIndex: Int,
    val wellLabel: String,
    val site: ArrayPhysicalSiteResult
)

/** 原图方向证据；旧运行缺少字段时只显示“未记录”，不会重新猜测历史图片。 */
data class Plate96OrientationEvidence(
    val sourceRows: Int?,
    val sourceColumns: Int?,
    val quarterTurnsClockwise: Int?,
    val originCorner: String?,
    val userConfirmed: Boolean?
)

/**
 * 独立圆孔板结果快照。
 *
 * [arraySnapshot] 是冻结科学事实，[wells]与[orientation]是孔板页面需要的专属语义视图。
 * 微流控页面不会依赖本对象，因此孔板统计和视觉可以独立演进。
 */
data class Plate96ResultSnapshot(
    val arraySnapshot: ArrayResultSnapshot,
    val wells: List<Plate96WellResult>,
    val orientation: Plate96OrientationEvidence,
    val source: Plate96ResultSource = Plate96ResultSource.MODERN_SNAPSHOT
) {
    val runId: String get() = arraySnapshot.runId
    val projectId: String get() = arraySnapshot.projectId
    val projectName: String get() = arraySnapshot.projectName
}

/** 将零基行列转换为标准孔号，例如0,0→A1，7,11→H12。 */
fun plate96WellLabel(rowIndex: Int, columnIndex: Int): String {
    require(rowIndex in 0 until PLATE96_RESULT_ROWS) { "96孔板行索引越界" }
    require(columnIndex in 0 until PLATE96_RESULT_COLUMNS) { "96孔板列索引越界" }
    return plateWellLabel(rowIndex, columnIndex)
}

/** 自定义圆孔板通用孔号；A～Z之后继续使用AA、AB，列号始终从1开始。 */
fun plateWellLabel(rowIndex: Int, columnIndex: Int): String {
    require(rowIndex >= 0) { "圆孔板行索引不能为负数" }
    require(columnIndex >= 0) { "圆孔板列索引不能为负数" }
    var value = rowIndex + 1
    val rowLabel = StringBuilder()
    while (value > 0) {
        value -= 1
        rowLabel.append(('A'.code + value % 26).toChar())
        value /= 26
    }
    return "${rowLabel.reverse()}${columnIndex + 1}"
}
