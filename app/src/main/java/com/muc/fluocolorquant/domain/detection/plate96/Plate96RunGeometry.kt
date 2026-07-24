package com.muc.fluocolorquant.domain.detection.plate96

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.domain.detection.array.ArrayBackgroundAnnulus
import com.muc.fluocolorquant.domain.detection.array.ArrayGridCoordinate
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayImageTransformSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationDiagnostics
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationFlag
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteFlag
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.grid.RegularGridGeometry
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitRegionSource
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitSegmentationResult
import kotlin.math.roundToInt

/** 96孔板运行几何的首个稳定持久化版本。 */
const val PLATE96_RUN_GEOMETRY_SCHEMA_V1: String = "plate96-run-geometry-v1"

/**
 * 单个圆孔的运行几何快照。
 *
 * 前景掩膜不写入JSON：96孔板前景由圆心、半径和紧致边界唯一重建。这样既冻结了真实
 * 裁切边界，又避免把96份大ByteArray塞入DetectionRun。历史结果只读浓度和几何，不会
 * 重新采样；本对象主要用于原图投影、单孔详情和科学审计。
 */
data class Plate96RunSiteGeometry(
    val siteIndex: Int,
    val displayLabel: String,
    val canonicalCoordinate: ArrayGridCoordinate,
    val sourceCoordinate: ArrayGridCoordinate,
    val normalizedCenter: ArrayImagePoint,
    val sourceCenter: ArrayImagePoint,
    val normalizedBounds: ArrayImageBounds,
    val sourceBounds: ArrayImageBounds,
    val normalizedBackgroundAnnulus: ArrayBackgroundAnnulus,
    val sourceBackgroundAnnulus: ArrayBackgroundAnnulus,
    val normalizedCropBounds: ArrayUnitBounds,
    val sourceCropBounds: ArrayUnitBounds,
    val radiusPx: Double,
    val confidence: Double,
    val source: ArraySiteLocalizationSource,
    val flags: Set<ArraySiteLocalizationFlag>
) {
    fun requireValid(): Plate96RunSiteGeometry = apply {
        require(siteIndex in 0 until Plate96LayoutContract.SITE_COUNT) { "96孔板位点索引越界" }
        require(displayLabel == Plate96LayoutContract.displayLabel(
            canonicalCoordinate.rowIndex,
            canonicalCoordinate.columnIndex
        )) { "96孔板位点标签与标准坐标不一致" }
        normalizedBounds.requireValid()
        sourceBounds.requireValid()
        normalizedBackgroundAnnulus.requireValid()
        sourceBackgroundAnnulus.requireValid()
        normalizedCropBounds.requireValid()
        sourceCropBounds.requireValid()
        require(radiusPx.isFinite() && radiusPx > 0.0) { "96孔板圆孔半径必须为正有限数值" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "96孔板置信度必须位于0到1" }
    }
}

/**
 * 一次新96孔板运行冻结的完整几何协议。
 *
 * 该协议明确保存标准8×12、原图方向、正逆矩阵和96个圆孔，不依赖行列数猜测载体，也
 * 不借用PG-Grid的JSON名称。算法以后升级时，旧运行仍按本快照恢复而不会重新定位。
 */
data class Plate96RunGeometrySnapshot(
    val schemaVersion: String = PLATE96_RUN_GEOMETRY_SCHEMA_V1,
    val locatorName: String,
    val locatorVersion: String,
    val orientation: ArrayOrientationSnapshot,
    val imageTransform: ArrayImageTransformSnapshot,
    val sites: List<Plate96RunSiteGeometry>,
    val diagnostics: ArrayLocalizationDiagnostics
) {
    fun requireValid(): Plate96RunGeometrySnapshot = apply {
        require(schemaVersion == PLATE96_RUN_GEOMETRY_SCHEMA_V1) { "不支持的96孔板运行几何版本" }
        require(locatorName.isNotBlank() && locatorVersion.isNotBlank()) { "96孔板定位器版本不能为空" }
        orientation.requireValid()
        imageTransform.requireValid()
        require(
            orientation.canonicalRows == Plate96LayoutContract.ROWS &&
                orientation.canonicalColumns == Plate96LayoutContract.COLUMNS
        ) { "96孔板运行几何必须采用标准8×12" }
        require(sites.size == Plate96LayoutContract.SITE_COUNT) { "96孔板运行几何必须包含96个孔位" }
        sites.forEachIndexed { index, site ->
            site.requireValid()
            require(site.siteIndex == index) { "96孔板位点必须按标准行优先顺序保存" }
            require(site.canonicalCoordinate == Plate96LayoutContract.coordinate(index)) {
                "96孔板位点坐标与标准索引不一致"
            }
        }
        diagnostics.requireValid(Plate96LayoutContract.SITE_COUNT)
    }

    companion object {
        fun from(localization: ArrayLocalizationResult): Plate96RunGeometrySnapshot {
            localization.requireValid()
            return Plate96RunGeometrySnapshot(
                locatorName = localization.locatorName,
                locatorVersion = localization.locatorVersion,
                orientation = localization.orientation,
                imageTransform = localization.imageTransform,
                sites = localization.sites.map { site ->
                    Plate96RunSiteGeometry(
                        siteIndex = site.siteIndex,
                        displayLabel = site.displayLabel,
                        canonicalCoordinate = site.canonicalCoordinate,
                        sourceCoordinate = site.sourceCoordinate,
                        normalizedCenter = site.normalizedCenter,
                        sourceCenter = site.sourceCenter,
                        normalizedBounds = site.normalizedBounds,
                        sourceBounds = site.sourceBounds,
                        normalizedBackgroundAnnulus = site.normalizedBackgroundAnnulus,
                        sourceBackgroundAnnulus = site.sourceBackgroundAnnulus,
                        normalizedCropBounds = site.normalizedRegion.bounds,
                        sourceCropBounds = site.sourceRegion.bounds,
                        radiusPx = requireNotNull(site.radiusPx) { "96孔板圆孔缺少半径" },
                        confidence = site.confidence,
                        source = site.source,
                        flags = site.flags
                    )
                },
                diagnostics = localization.diagnostics
            ).requireValid()
        }
    }
}

/** 96孔板运行几何的严格JSON编解码器。 */
object Plate96RunGeometryCodec {
    private val gson = Gson()

    fun encode(snapshot: Plate96RunGeometrySnapshot): String =
        gson.toJson(snapshot.requireValid())

    fun decode(json: String): Plate96RunGeometrySnapshot {
        require(json.isNotBlank()) { "96孔板运行几何JSON不能为空" }
        return gson.fromJson(json, Plate96RunGeometrySnapshot::class.java).requireValid()
    }

    /**
     * 在保留已有采集元数据的基础上补充方向证据。
     *
     * 结果页只读取这里冻结的字段；以后系统设置或定位算法改变，都不会影响旧运行方向。
     */
    fun mergeAcquisitionMetadata(
        existingJson: String?,
        geometry: Plate96RunGeometrySnapshot,
        exifRotationDegrees: Int,
        exifFlipped: Boolean
    ): String {
        val root = existingJson?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
        } ?: JsonObject()
        val orientation = geometry.orientation
        root.add(
            "plate96Orientation",
            gson.toJsonTree(
                linkedMapOf(
                    "schemaVersion" to orientation.schemaVersion,
                    "sourceRows" to orientation.sourceRows,
                    "sourceColumns" to orientation.sourceColumns,
                    "quarterTurnsClockwise" to orientation.rotation.degreesClockwise / 90,
                    "originCorner" to orientation.originCorner.name,
                    "mirrored" to orientation.mirrored,
                    "source" to orientation.source.name,
                    "confidence" to orientation.confidence,
                    "userConfirmed" to (orientation.source == ArrayOrientationSource.USER_CONFIRMED),
                    "exifRotationDegrees" to exifRotationDegrees,
                    "exifFlipped" to exifFlipped
                )
            )
        )
        root.add("plate96ImageTransform", gson.toJsonTree(geometry.imageTransform))
        return gson.toJson(root)
    }
}

/** 现场定量阶段使用的兼容采样对象；它不会作为PG-Grid几何写入数据库。 */
data class Plate96ScientificSampling(
    val grid: PgGridResult,
    val segmentation: ArrayUnitSegmentationResult
)

/**
 * 将标准方向圆阵适配给已经稳定的通用光度和定量内核。
 *
 * `PgQuantSampler`目前消费的是规则晶格、正逆矩阵和形状感知区域，而不是PG-Grid特有的
 * 候选算法。这里仅在内存中建立兼容对象以复用科学采样；持久化仍使用独立
 * `plate96Geometry`，从而避免复制比色、荧光、曲线和深度学习执行代码。
 */
object Plate96ScientificSamplingAdapter {
    fun create(localization: ArrayLocalizationResult): Plate96ScientificSampling {
        localization.requireValid()
        val points = localization.sites.map { GridPoint(it.normalizedCenter.x, it.normalizedCenter.y) }
        val pitch = RegularGridGeometry.estimatePitch(
            points = points,
            rows = Plate96LayoutContract.ROWS,
            columns = Plate96LayoutContract.COLUMNS
        )
        val representativePitch = minOf(pitch.horizontalPx, pitch.verticalPx).coerceAtLeast(1.0)
        val regions = localization.sites.map { it.normalizedRegion }
        val segmentation = ArrayUnitSegmentationResult(
            rows = Plate96LayoutContract.ROWS,
            columns = Plate96LayoutContract.COLUMNS,
            imageWidth = localization.imageTransform.normalizedWidth,
            imageHeight = localization.imageTransform.normalizedHeight,
            pitchPx = representativePitch,
            medianWidthPx = medianInt(regions.map { it.bounds.width }),
            medianHeightPx = medianInt(regions.map { it.bounds.height }),
            regions = regions
        ).requireValid()

        val sites = localization.sites.map { site ->
            GridLocalizedSite(
                key = GridSiteKey(
                    rowIndex = site.canonicalCoordinate.rowIndex,
                    columnIndex = site.canonicalCoordinate.columnIndex
                ),
                siteIndex = site.siteIndex,
                rectified = GridPoint(site.normalizedCenter.x, site.normalizedCenter.y),
                original = GridPoint(site.sourceCenter.x, site.sourceCenter.y),
                confidence = site.confidence,
                source = site.source.toScientificPointSource(),
                flags = site.flags.toScientificFlags()
            )
        }
        val transform = localization.imageTransform
        val grid = PgGridResult(
            rows = Plate96LayoutContract.ROWS,
            columns = Plate96LayoutContract.COLUMNS,
            rectifiedWidth = transform.normalizedWidth,
            rectifiedHeight = transform.normalizedHeight,
            // 极性只属于PG候选检测；通用采样器并不读取该字段。固定值仅满足内存兼容契约。
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "plate96_circular_grid",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint((transform.sourceWidth - 1).toDouble(), 0.0),
                GridPoint(
                    (transform.sourceWidth - 1).toDouble(),
                    (transform.sourceHeight - 1).toDouble()
                ),
                GridPoint(0.0, (transform.sourceHeight - 1).toDouble())
            ),
            homography = GridHomography(
                forward = transform.sourceToNormalized,
                inverse = transform.normalizedToSource
            ),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                model = "plate96_orientation_and_circular_grid",
                candidateSupportRatio = localization.diagnostics.observedSiteCount.toDouble() /
                    Plate96LayoutContract.SITE_COUNT,
                trusted = true,
                observedRatio = localization.diagnostics.observedSiteCount.toDouble() /
                    Plate96LayoutContract.SITE_COUNT,
                geometryRmsePx = null,
                inlierCount = localization.diagnostics.observedSiteCount,
                outlierCount = localization.diagnostics.imputedSiteCount,
                meanConfidence = localization.diagnostics.meanConfidence
            ),
            frameQc = emptyList(),
            locatorName = localization.locatorName,
            locatorVersion = localization.locatorVersion
        ).requireValid()
        return Plate96ScientificSampling(grid = grid, segmentation = segmentation)
    }

    private fun medianInt(values: List<Int>): Int {
        val sorted = values.filter { it > 0 }.sorted()
        require(sorted.isNotEmpty()) { "96孔板圆孔边界不能为空" }
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1] + sorted[middle]) / 2.0).roundToInt()
    }
}

/**
 * 将已持久化的96孔板几何映射为通用结果层当前使用的规则阵列几何视图。
 *
 * 该转换只服务历史展示，不包含分割掩膜，也不会触发任何图像读取或重新定位。
 */
fun Plate96RunGeometrySnapshot.toResultGrid(): PgGridResult {
    requireValid()
    val transform = imageTransform
    return PgGridResult(
        rows = Plate96LayoutContract.ROWS,
        columns = Plate96LayoutContract.COLUMNS,
        rectifiedWidth = transform.normalizedWidth,
        rectifiedHeight = transform.normalizedHeight,
        targetPolarity = GridTargetPolarity.DARK,
        chipRegionMethod = "plate96_circular_grid",
        chipCorners = listOf(
            GridPoint(0.0, 0.0),
            GridPoint((transform.sourceWidth - 1).toDouble(), 0.0),
            GridPoint(
                (transform.sourceWidth - 1).toDouble(),
                (transform.sourceHeight - 1).toDouble()
            ),
            GridPoint(0.0, (transform.sourceHeight - 1).toDouble())
        ),
        homography = GridHomography(
            forward = transform.sourceToNormalized,
            inverse = transform.normalizedToSource
        ),
        sites = sites.map { site ->
            GridLocalizedSite(
                key = GridSiteKey(
                    rowIndex = site.canonicalCoordinate.rowIndex,
                    columnIndex = site.canonicalCoordinate.columnIndex
                ),
                siteIndex = site.siteIndex,
                rectified = GridPoint(site.normalizedCenter.x, site.normalizedCenter.y),
                original = GridPoint(site.sourceCenter.x, site.sourceCenter.y),
                confidence = site.confidence,
                source = site.source.toScientificPointSource(),
                flags = site.flags.toScientificFlags()
            )
        },
        geometry = GridGeometryDiagnostics(
            model = "plate96_orientation_and_circular_grid",
            candidateSupportRatio = diagnostics.observedSiteCount.toDouble() /
                Plate96LayoutContract.SITE_COUNT,
            trusted = true,
            observedRatio = diagnostics.observedSiteCount.toDouble() /
                Plate96LayoutContract.SITE_COUNT,
            geometryRmsePx = null,
            inlierCount = diagnostics.observedSiteCount,
            outlierCount = diagnostics.imputedSiteCount,
            meanConfidence = diagnostics.meanConfidence
        ),
        frameQc = emptyList(),
        locatorName = locatorName,
        locatorVersion = locatorVersion
    ).requireValid()
}

private fun ArraySiteLocalizationSource.toScientificPointSource(): GridPointSource = when (this) {
    ArraySiteLocalizationSource.GRID_IMPUTED -> GridPointSource.MODEL_IMPUTED
    ArraySiteLocalizationSource.OBJECT_DETECTION -> GridPointSource.UNADJUSTED
    ArraySiteLocalizationSource.SHAPE_REFINED,
    ArraySiteLocalizationSource.CONTOUR_REFINED,
    ArraySiteLocalizationSource.USER_ADJUSTED -> GridPointSource.CANDIDATE_REFINED
}

private fun Set<ArraySiteLocalizationFlag>.toScientificFlags(): Set<GridSiteFlag> = buildSet {
    if (ArraySiteLocalizationFlag.GRID_IMPUTED in this@toScientificFlags) {
        add(GridSiteFlag.IMPUTED_POSITION)
    }
    if (ArraySiteLocalizationFlag.LOW_GEOMETRIC_SUPPORT in this@toScientificFlags) {
        add(GridSiteFlag.LOW_LOCAL_EVIDENCE)
    }
}
