package com.muc.fluocolorquant.domain.result.plate96

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot

/**
 * 将通用冻结测量快照收窄为圆孔板专属结果。
 *
 * 这里不使用“刚好有96个位点”作为孔板判断依据；必须同时校验载体类型和圆孔形状。
 * 标准96孔板仍由上游固定为8×12，自定义圆孔板则使用项目运行快照中冻结的行列。
 */
object Plate96ResultSnapshotMapper {
    private val gson = Gson()

    fun map(source: ArrayResultLoadResult): Plate96ResultLoadResult {
        return when (source) {
            is ArrayResultLoadResult.Success -> map(source.snapshot)
            is ArrayResultLoadResult.Failure -> Plate96ResultLoadResult.Failure(
                Plate96ResultErrorCode.SOURCE_NOT_AVAILABLE
            )
        }
    }

    fun map(source: ArrayResultSnapshot): Plate96ResultLoadResult {
        if (CarrierType.fromCode(source.carrier.carrierType) != CarrierType.PLATE) {
            return failure(Plate96ResultErrorCode.NOT_A_PLATE_CARRIER)
        }
        if (SiteShape.fromCode(source.carrier.siteShape) != SiteShape.CIRCLE) {
            return failure(Plate96ResultErrorCode.NOT_A_CIRCULAR_CARRIER)
        }
        if (source.rows <= 0 || source.columns <= 0) {
            return failure(Plate96ResultErrorCode.INVALID_STANDARD_LAYOUT)
        }
        val expectedSiteCount = source.rows * source.columns
        if (
            source.sites.size != expectedSiteCount ||
            source.sites.map { it.siteIndex }.toSet() != (0 until expectedSiteCount).toSet()
        ) {
            return failure(Plate96ResultErrorCode.INCOMPLETE_WELL_INDEX)
        }

        val wells = source.sites.sortedBy { it.siteIndex }.map { site ->
            val expectedRow = site.siteIndex / source.columns
            val expectedColumn = site.siteIndex % source.columns
            if (site.rowIndex != expectedRow || site.columnIndex != expectedColumn) {
                return failure(Plate96ResultErrorCode.INCONSISTENT_WELL_COORDINATE)
            }
            Plate96WellResult(
                wellIndex = site.siteIndex,
                rowIndex = expectedRow,
                columnIndex = expectedColumn,
                wellLabel = plateWellLabel(expectedRow, expectedColumn),
                site = site
            )
        }

        return Plate96ResultLoadResult.Success(
            Plate96ResultSnapshot(
                arraySnapshot = source,
                wells = wells,
                orientation = parseOrientation(source.acquisitionMetadataJson)
            )
        )
    }

    /**
     * P6会把方向快照写入采集元数据；P5先采用宽容只读解析，确保旧测试资产不会被伪造补值。
     */
    private fun parseOrientation(json: String?): Plate96OrientationEvidence {
        val root = json?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
        }
        val orientation = root?.getAsJsonObject("plate96Orientation") ?: root
        return Plate96OrientationEvidence(
            sourceRows = orientation?.intOrNull("sourceRows"),
            sourceColumns = orientation?.intOrNull("sourceColumns"),
            quarterTurnsClockwise = orientation?.intOrNull("quarterTurnsClockwise"),
            originCorner = orientation?.stringOrNull("originCorner"),
            userConfirmed = orientation?.booleanOrNull("userConfirmed")
        )
    }

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asString }?.getOrNull()

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull()

    private fun failure(code: Plate96ResultErrorCode): Plate96ResultLoadResult =
        Plate96ResultLoadResult.Failure(code)
}
