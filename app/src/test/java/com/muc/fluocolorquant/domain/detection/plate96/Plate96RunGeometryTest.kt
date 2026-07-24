package com.muc.fluocolorquant.domain.detection.plate96

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.domain.detection.array.ArrayBackgroundAnnulus
import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds
import com.muc.fluocolorquant.domain.detection.array.ArrayImagePoint
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationDiagnostics
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizationResult
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayOriginCorner
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationFlag
import com.muc.fluocolorquant.domain.detection.array.ArraySiteLocalizationSource
import com.muc.fluocolorquant.domain.detection.array.ArrayLocalizedSite
import com.muc.fluocolorquant.domain.detection.array.Plate96LayoutContract
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBitmapCropper
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitBounds
import com.muc.fluocolorquant.domain.detection.segmentation.ArrayUnitShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 96孔板运行几何必须在编码、结果恢复和科学采样三条路径中保持同一坐标。 */
class Plate96RunGeometryTest {
    @Test
    fun `运行几何编码解码后保持96孔方向和圆孔边界`() {
        val localization = localization()

        val decoded = Plate96RunGeometryCodec.decode(
            Plate96RunGeometryCodec.encode(Plate96RunGeometrySnapshot.from(localization))
        )

        assertEquals(Plate96LayoutContract.SITE_COUNT, decoded.sites.size)
        assertEquals("A1", decoded.sites.first().displayLabel)
        assertEquals("H12", decoded.sites.last().displayLabel)
        assertEquals(28.0, decoded.sites[37].radiusPx, 0.0)
        assertEquals(localization.imageTransform, decoded.imageTransform)
    }

    @Test
    fun `持久化几何恢复为结果视图时补位来源和标准索引不漂移`() {
        val source = localization(imputedIndex = 95)
        val grid = Plate96RunGeometrySnapshot.from(source).toResultGrid()

        assertEquals(8, grid.rows)
        assertEquals(12, grid.columns)
        assertEquals(96, grid.sites.size)
        assertEquals(GridPointSource.MODEL_IMPUTED, grid.sites.last().source)
        assertEquals(7, grid.sites.last().key.rowIndex)
        assertEquals(11, grid.sites.last().key.columnIndex)
        assertEquals(source.sites.last().sourceCenter.x, grid.sites.last().original.x, 0.0)
    }

    @Test
    fun `现场采样适配器复用真实圆形区域且不改变持久化协议`() {
        val source = localization()
        val sampling = Plate96ScientificSamplingAdapter.create(source)

        assertEquals(96, sampling.segmentation.regions.size)
        assertTrue(sampling.segmentation.regions.all { it.shape == ArrayUnitShape.CIRCLE })
        assertTrue(sampling.segmentation.regions.all { it.foregroundPixelCount > 0 })
        assertEquals(source.sites[10].normalizedCenter.x, sampling.grid.sites[10].rectified.x, 0.0)
        assertFalse(Plate96RunGeometryCodec.encode(Plate96RunGeometrySnapshot.from(source)).contains("pgGrid"))
    }

    @Test
    fun `采集元数据冻结用户方向和EXIF处理而不覆盖已有字段`() {
        val geometry = Plate96RunGeometrySnapshot.from(localization())
        val json = Plate96RunGeometryCodec.mergeAcquisitionMetadata(
            existingJson = "{\"camera\":\"rear\"}",
            geometry = geometry,
            exifRotationDegrees = 90,
            exifFlipped = true
        )
        val root = Gson().fromJson(json, JsonObject::class.java)
        val orientation = root.getAsJsonObject("plate96Orientation")

        assertEquals("rear", root.get("camera").asString)
        assertEquals(0, orientation.get("quarterTurnsClockwise").asInt)
        assertTrue(orientation.get("userConfirmed").asBoolean)
        assertEquals(90, orientation.get("exifRotationDegrees").asInt)
        assertTrue(orientation.get("exifFlipped").asBoolean)
    }

    private fun localization(imputedIndex: Int? = null): ArrayLocalizationResult {
        val orientation = Plate96LayoutContract.orientation(
            originCorner = ArrayOriginCorner.TOP_LEFT,
            source = ArrayOrientationSource.USER_CONFIRMED,
            confidence = 1.0
        )
        val transform = ArrayCoordinateTransformer.createImageTransform(
            sourceWidth = WIDTH,
            sourceHeight = HEIGHT,
            rotation = orientation.rotation,
            mirrored = orientation.mirrored
        )
        val sites = List(Plate96LayoutContract.SITE_COUNT) { index ->
            val coordinate = Plate96LayoutContract.coordinate(index)
            val center = ArrayImagePoint(
                x = 65.0 + coordinate.columnIndex * 88.0,
                y = 65.0 + coordinate.rowIndex * 88.0
            )
            val bounds = ArrayUnitBounds(
                left = (center.x - 28.0).toInt(),
                top = (center.y - 28.0).toInt(),
                right = (center.x + 28.0).toInt(),
                bottom = (center.y + 28.0).toInt()
            )
            val sourceKind = if (index == imputedIndex) {
                ArraySiteLocalizationSource.GRID_IMPUTED
            } else {
                ArraySiteLocalizationSource.SHAPE_REFINED
            }
            val flags = if (index == imputedIndex) {
                setOf(
                    ArraySiteLocalizationFlag.GRID_IMPUTED,
                    ArraySiteLocalizationFlag.LOW_GEOMETRIC_SUPPORT
                )
            } else {
                emptySet()
            }
            ArrayLocalizedSite(
                siteIndex = index,
                displayLabel = Plate96LayoutContract.displayLabel(
                    coordinate.rowIndex,
                    coordinate.columnIndex
                ),
                canonicalCoordinate = coordinate,
                sourceCoordinate = coordinate,
                normalizedCenter = center,
                sourceCenter = center,
                normalizedBounds = ArrayImageBounds(
                    bounds.left.toDouble(),
                    bounds.top.toDouble(),
                    bounds.right.toDouble(),
                    bounds.bottom.toDouble()
                ),
                sourceBounds = ArrayImageBounds(
                    bounds.left.toDouble(),
                    bounds.top.toDouble(),
                    bounds.right.toDouble(),
                    bounds.bottom.toDouble()
                ),
                normalizedBackgroundAnnulus = ArrayBackgroundAnnulus(center, 34.0, 41.0),
                sourceBackgroundAnnulus = ArrayBackgroundAnnulus(center, 34.0, 41.0),
                radiusPx = 28.0,
                confidence = if (index == imputedIndex) 0.3 else 0.96,
                source = sourceKind,
                flags = flags,
                normalizedRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                    siteIndex = index,
                    rowIndex = coordinate.rowIndex,
                    columnIndex = coordinate.columnIndex,
                    shape = ArrayUnitShape.CIRCLE,
                    bounds = bounds
                ),
                sourceRegion = ArrayUnitBitmapCropper.detectedGeometryRegion(
                    siteIndex = index,
                    rowIndex = coordinate.rowIndex,
                    columnIndex = coordinate.columnIndex,
                    shape = ArrayUnitShape.CIRCLE,
                    bounds = bounds
                )
            )
        }
        return ArrayLocalizationResult(
            locatorName = "plate96-test-locator",
            locatorVersion = "1.0",
            orientation = orientation,
            imageTransform = transform,
            sites = sites,
            diagnostics = ArrayLocalizationDiagnostics(
                observedSiteCount = if (imputedIndex == null) 96 else 95,
                shapeRefinedSiteCount = if (imputedIndex == null) 96 else 95,
                imputedSiteCount = if (imputedIndex == null) 0 else 1,
                orientationScore = 0.98,
                orientationAlternativeScore = 0.62,
                orientationAmbiguous = false,
                meanConfidence = if (imputedIndex == null) 0.96 else 0.953
            )
        ).requireValid()
    }

    private companion object {
        const val WIDTH: Int = 1100
        const val HEIGHT: Int = 720
    }
}
