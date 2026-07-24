package com.muc.fluocolorquant.domain.result.plate96

import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

/** 96孔板结果固定采用行业标准的8行×12列语义。 */
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
    NOT_A_PLATE_CARRIER,
    NOT_A_CIRCULAR_CARRIER,
    INVALID_STANDARD_LAYOUT,
    INCOMPLETE_WELL_INDEX,
    INCONSISTENT_WELL_COORDINATE
}

sealed interface Plate96ResultLoadResult {
    data class Success(val snapshot: Plate96ResultSnapshot) : Plate96ResultLoadResult
    data class Failure(val errorCode: Plate96ResultErrorCode) : Plate96ResultLoadResult
}

/**
 * 单个标准孔位。
 *
 * [wellLabel] 永远使用A1～H12标准孔号；底层科学测量仍保留在[site]中，避免复制浓度、
 * 信号和质量字段后产生两套互相漂移的数据。
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
 * 独立96孔板结果快照。
 *
 * [arraySnapshot] 是冻结科学事实，[wells]与[orientation]是孔板页面需要的专属语义视图。
 * 微流控页面不会依赖本对象，因此孔板统计和视觉可以独立演进。
 */
data class Plate96ResultSnapshot(
    val arraySnapshot: ArrayResultSnapshot,
    val wells: List<Plate96WellResult>,
    val orientation: Plate96OrientationEvidence
) {
    val runId: String get() = arraySnapshot.runId
    val projectId: String get() = arraySnapshot.projectId
    val projectName: String get() = arraySnapshot.projectName
}

/** 将零基行列转换为标准孔号，例如0,0→A1，7,11→H12。 */
fun plate96WellLabel(rowIndex: Int, columnIndex: Int): String {
    require(rowIndex in 0 until PLATE96_RESULT_ROWS) { "96孔板行索引越界" }
    require(columnIndex in 0 until PLATE96_RESULT_COLUMNS) { "96孔板列索引越界" }
    return "${('A'.code + rowIndex).toChar()}${columnIndex + 1}"
}
