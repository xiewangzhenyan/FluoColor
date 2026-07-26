package com.muc.fluocolorquant.domain.result.plate96

import com.google.gson.Gson
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.result.ArrayAnalyteResult
import com.muc.fluocolorquant.domain.result.ArrayCalibrationPointResult
import com.muc.fluocolorquant.domain.result.ArrayCaptureEvidence
import com.muc.fluocolorquant.domain.result.ArrayCarrierResult
import com.muc.fluocolorquant.domain.result.ArrayFrameResult
import com.muc.fluocolorquant.domain.result.ArrayMeasurementDetail
import com.muc.fluocolorquant.domain.result.ArrayMeasurementQc
import com.muc.fluocolorquant.domain.result.ArrayPhysicalSiteResult
import com.muc.fluocolorquant.domain.result.ArrayResultSnapshot
import com.muc.fluocolorquant.domain.result.ArraySiteGeometry
import com.muc.fluocolorquant.domain.result.ArraySiteMeasurementResult
import com.muc.fluocolorquant.domain.signal.SignalFeatureCatalog
import com.muc.fluocolorquant.utils.PixelExtractionUtils
import kotlin.math.ceil

/** 旧单分析物记录没有外键时使用的稳定占位ID，结果与验证适配必须保持一致。 */
internal const val LEGACY_DEFAULT_ANALYTE_ID: String = "legacy-default-analyte"

/** 旧96孔板适配所需的同事务只读实体集合。 */
data class LegacyPlateRunSource(
    val run: DetectionRun,
    val project: Project,
    val wellResults: List<WellResult>,
    val projectAnalytes: List<ProjectAnalyteJoin>,
    val analytesById: Map<String, Analyte>,
    val curveModelsById: Map<String, CurveModel>,
    val artifacts: List<CaptureArtifact>
)

/**
 * 将旧 `DetectionRun + WellResult` 只读投影为新的96孔板结果快照。
 *
 * 适配器绝不重新定位、裁切、拟合或计算浓度。旧版当时使用 `wellIndex / 12` 和
 * `wellIndex % 12` 展示孔位，因此这里继续固定同一8×12语义；即使原图是竖拍，也不会
 * 根据今天的方向算法改写历史A1位置。
 */
object LegacyPlateRunAdapter {
    private const val ADAPTER_SCHEMA = "legacy-plate96-adapter-v1"
    private const val ADAPTER_PROCESSOR = "legacy-well-result-adapter"
    private val gson = Gson()

    fun map(source: LegacyPlateRunSource): Plate96ResultLoadResult {
        val project = source.project
        if (
            project.rows != PLATE96_RESULT_ROWS ||
            project.columns != PLATE96_RESULT_COLUMNS ||
            project.detectionMode !in setOf("COLORIMETRIC", "FLUORESCENCE") ||
            source.run.projectId != project.id ||
            source.wellResults.isEmpty()
        ) {
            return failure(Plate96ResultErrorCode.NOT_A_LEGACY_PLATE96_RUN)
        }
        if (source.wellResults.any { it.wellIndex !in 0 until PLATE96_RESULT_SITE_COUNT }) {
            return failure(Plate96ResultErrorCode.INVALID_LEGACY_WELL_INDEX)
        }

        // 旧页面对重复孔位采用 associateBy 的最后一条结果。这里按自增主键取最新记录，
        // 保持旧页面实际显示语义，同时不修改数据库中的任何历史行。
        val latestResults = source.wellResults
            .groupBy { it.wellIndex to normalizedAnalyteId(it.fkAnalyteId) }
            .mapValues { (_, values) -> values.maxBy(WellResult::resultId) }
            .values
            .sortedWith(compareBy(WellResult::wellIndex, WellResult::resultId))
        val joinsByAnalyte = source.projectAnalytes.associateBy(ProjectAnalyteJoin::analyteId)
        val curveByAnalyte = source.projectAnalytes.mapNotNull { join ->
            join.fkCurveModelId?.let(source.curveModelsById::get)?.let { join.analyteId to it }
        }.toMap()
        val analyteIds = buildList {
            addAll(source.projectAnalytes.map(ProjectAnalyteJoin::analyteId))
            addAll(latestResults.map { normalizedAnalyteId(it.fkAnalyteId) })
        }.distinct()
        val analytes = analyteIds.mapIndexed { index, analyteId ->
            buildAnalyte(
                source = source,
                analyteId = analyteId,
                displayOrder = index,
                join = joinsByAnalyte[analyteId],
                curve = curveByAnalyte[analyteId]
            )
        }
        val resultsBySite = latestResults.groupBy(WellResult::wellIndex)
        val sites = List(PLATE96_RESULT_SITE_COUNT) { siteIndex ->
            buildSite(
                siteIndex = siteIndex,
                results = resultsBySite[siteIndex].orEmpty(),
                joinsByAnalyte = joinsByAnalyte,
                curveByAnalyte = curveByAnalyte,
                detectionMode = project.detectionMode
            )
        }
        val observedGeometryCount = latestResults.count { it.hasUsableBounds() }
        val confidenceValues = latestResults.mapNotNull { result ->
            result.detectionConfidence?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
        }
        val artifacts = buildArtifacts(source)
        val snapshot = ArrayResultSnapshot(
            runId = source.run.runId,
            projectId = project.id,
            projectName = project.name,
            runTimestampEpochMillis = source.run.timestamp.time,
            runStatus = source.run.status,
            detectionMode = project.detectionMode,
            carrier = ArrayCarrierResult(
                id = "legacy-plate96",
                name = "legacy-plate96",
                carrierType = CarrierType.PLATE.code,
                version = 0,
                siteShape = SiteShape.CIRCLE.code,
                orientationMarkerJson = null
            ),
            rows = PLATE96_RESULT_ROWS,
            columns = PLATE96_RESULT_COLUMNS,
            analytes = analytes,
            sites = sites,
            frame = ArrayFrameResult(
                locatorName = source.run.detectionModelUsed.orEmpty(),
                locatorVersion = "legacy",
                rectifiedWidth = legacyCanvasWidth(latestResults),
                rectifiedHeight = legacyCanvasHeight(latestResults),
                chipRegionMethod = ADAPTER_SCHEMA,
                geometry = GridGeometryDiagnostics(
                    candidateSupportRatio = null,
                    trusted = false,
                    observedRatio = observedGeometryCount.toDouble() / PLATE96_RESULT_SITE_COUNT,
                    geometryRmsePx = null,
                    inlierCount = observedGeometryCount.coerceAtMost(PLATE96_RESULT_SITE_COUNT),
                    outlierCount = 0,
                    meanConfidence = confidenceValues.averageOrZero()
                ),
                qcIssues = emptyList(),
                frameQcJson = source.run.frameQcJson.orEmpty()
            ),
            artifacts = artifacts,
            effectiveConfigSnapshotJson = source.run.effectiveConfigSnapshotJson
                ?: gson.toJson(mapOf("schemaVersion" to ADAPTER_SCHEMA)),
            configurationDeviationJson = source.run.configurationDeviationJson,
            acquisitionMetadataJson = source.run.acquisitionMetadataJson,
            processingVersionJson = source.run.processingVersionJson,
            modelUsageJson = source.run.concentrationModelUsed,
            siteQcSummaryJson = source.run.siteQcSummaryJson
        )
        return Plate96ResultLoadResult.Success(
            Plate96ResultSnapshot(
                arraySnapshot = snapshot,
                wells = sites.map { site ->
                    Plate96WellResult(
                        wellIndex = site.siteIndex,
                        rowIndex = site.rowIndex,
                        columnIndex = site.columnIndex,
                        wellLabel = plate96WellLabel(site.rowIndex, site.columnIndex),
                        site = site
                    )
                },
                // 旧表从未冻结图片朝向，必须保持未知，禁止根据检测框重新猜测。
                orientation = Plate96OrientationEvidence(
                    sourceRows = null,
                    sourceColumns = null,
                    quarterTurnsClockwise = null,
                    originCorner = null,
                    userConfirmed = null
                ),
                visualEvidence = Plate96WellVisualEvidence(
                    normalizedImagePath = null,
                    sourceImagePath = artifacts.lastOrNull { artifact ->
                        artifact.captureRole == "ENDPOINT"
                    }?.let { artifact -> artifact.derivedPath ?: artifact.originalPath },
                    cropBounds = emptyMap(),
                    legacyCropPaths = resultsBySite.mapNotNull { (siteIndex, results) ->
                        results.firstNotNullOfOrNull { result ->
                            result.croppedImageIdentifier?.takeIf(String::isNotBlank)
                        }?.let { path -> siteIndex to path }
                    }.toMap()
                ),
                source = Plate96ResultSource.LEGACY_WELL_RESULT
            )
        )
    }

    private fun buildAnalyte(
        source: LegacyPlateRunSource,
        analyteId: String,
        displayOrder: Int,
        join: ProjectAnalyteJoin?,
        curve: CurveModel?
    ): ArrayAnalyteResult {
        val projectRangeMax = join?.maxConcentration?.takeIf { it.isFinite() && it > 0.0 }
        val calibrationConcentrations = curve?.dataPoints.orEmpty()
            .map { it.first }
            .filter(Double::isFinite)
        val primaryFeature = curve?.signalFeatureCode
            ?: curve?.pixelType?.identifier
            ?: "legacy_signal"
        return ArrayAnalyteResult(
            analyteId = analyteId,
            name = source.analytesById[analyteId]?.name ?: source.project.name,
            displayOrder = displayOrder,
            concentrationUnit = join?.concentrationUnit.orEmpty(),
            reliableRangeMin = projectRangeMax?.let { 0.0 },
            reliableRangeMax = projectRangeMax,
            modelId = curve?.id ?: join?.dlModelName ?: "legacy-model-$analyteId",
            modelName = curve?.name ?: join?.dlModelName ?: source.project.analysisMethod,
            modelType = if (curve != null) "STANDARD_CURVE" else source.project.analysisMethod,
            modelVersion = 0,
            primaryFeature = primaryFeature,
            processorName = ADAPTER_PROCESSOR,
            processorVersion = ADAPTER_SCHEMA,
            fittingFunction = curve?.function?.identifier,
            fittingParameters = curve?.parameters.orEmpty().filterValues(Double::isFinite),
            calibrationPoints = curve?.dataPoints.orEmpty().mapIndexedNotNull { repeatIndex, point ->
                if (!point.first.isFinite() || !point.second.isFinite()) null else {
                    ArrayCalibrationPointResult(point.first, point.second, repeatIndex)
                }
            },
            validationMetrics = curve?.metrics.orEmpty().filterValues(Double::isFinite),
            projectRangeMin = projectRangeMax?.let { 0.0 },
            projectRangeMax = projectRangeMax,
            calibrationRangeMin = calibrationConcentrations.minOrNull(),
            calibrationRangeMax = calibrationConcentrations.maxOrNull()
        )
    }

    private fun buildSite(
        siteIndex: Int,
        results: List<WellResult>,
        joinsByAnalyte: Map<String, ProjectAnalyteJoin>,
        curveByAnalyte: Map<String, CurveModel>,
        detectionMode: String
    ): ArrayPhysicalSiteResult {
        val row = siteIndex / PLATE96_RESULT_COLUMNS
        val column = siteIndex % PLATE96_RESULT_COLUMNS
        val representative = results.firstOrNull()
        val center = representative?.boundsCenterOrNull()
            ?: GridPoint(column + 0.5, row + 0.5)
        val confidence = representative?.detectionConfidence?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: 0.0
        val role = representative?.normalizedRole()
        val analyteId = representative?.fkAnalyteId?.let(::normalizedAnalyteId)
        return ArrayPhysicalSiteResult(
            siteIndex = siteIndex,
            rowIndex = row,
            columnIndex = column,
            siteKey = "R${(row + 1).toString().padStart(2, '0')}C${(column + 1).toString().padStart(2, '0')}",
            enabled = true,
            roleCode = role,
            analyteId = analyteId,
            defaultSampleSlot = null,
            sampleSlot = null,
            overrideReason = null,
            // 旧表的trueConcentration既曾用于标准孔，也曾用于结果页预测精度验证。
            // 只有明确标记为标准品的孔位才能映射为标准浓度，样本真值由验证适配层单独读取。
            standardConcentration = representative?.trueConcentration
                ?.takeIf { concentration -> role == "STANDARD" && concentration.isFinite() },
            repeatGroup = null,
            referenceScope = null,
            geometry = ArraySiteGeometry(
                rectified = center,
                original = center,
                confidence = confidence,
                source = GridPointSource.UNADJUSTED,
                flags = emptySet()
            ),
            measurements = results.map { result ->
                val normalizedId = normalizedAnalyteId(result.fkAnalyteId)
                val curve = curveByAnalyte[normalizedId]
                val join = joinsByAnalyte[normalizedId]
                result.toMeasurement(
                    analyteId = normalizedId,
                    detectionMode = detectionMode,
                    curve = curve,
                    concentrationUnit = join?.concentrationUnit
                )
            }
        )
    }

    private fun WellResult.toMeasurement(
        analyteId: String,
        detectionMode: String,
        curve: CurveModel?,
        concentrationUnit: String?
    ): ArraySiteMeasurementResult {
        val rawJson = pixelValueJson?.takeIf(String::isNotBlank) ?: "{}"
        val values = PixelExtractionUtils.jsonToMap(rawJson)
        val featureName = curve?.signalFeatureCode
            ?: curve?.pixelType?.identifier
            ?: values.keys.sorted().firstOrNull()
            ?: "legacy_signal"
        val featureValue = curve?.let { model ->
            SignalFeatureCatalog.resolveValue(values, model.signalFeatureCode, model.pixelType)
        } ?: values[featureName]?.takeIf(Double::isFinite)
        val concentration = predictedConcentration?.takeIf(Double::isFinite)
        val rangeAudit = if (isOutOfRange) {
            gson.toJson(
                mapOf(
                    "status" to "LEGACY_OUT_OF_RANGE",
                    "reason" to "DIRECTION_NOT_RECORDED"
                )
            )
        } else {
            null
        }
        return ArraySiteMeasurementResult(
            measurementId = resultId,
            analyteId = analyteId,
            detectionMode = detectionMode,
            primaryFeatureName = featureName,
            primaryFeatureValue = featureValue,
            concentrationValue = concentration,
            concentrationUnit = concentrationUnit?.takeIf { concentration != null },
            // 旧布尔值没有保存高于还是低于范围，不能伪造方向标记。
            reliableRangeStatus = null,
            backgroundValue = null,
            signalToNoiseRatio = null,
            confidence = detectionConfidence?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 },
            signalDetectable = featureValue != null || concentration != null,
            qualityReliable = true,
            processorName = ADAPTER_PROCESSOR,
            processorVersion = ADAPTER_SCHEMA,
            modelSnapshotJson = null,
            rawSignalJson = rawJson,
            correctedSignalJson = null,
            qcJson = null,
            quantificationQcJson = rangeAudit,
            qc = ArrayMeasurementQc(
                geometrySourceCode = "LEGACY_UNADJUSTED",
                geometryFlags = emptySet(),
                photometryFlags = emptySet(),
                quantificationStatus = if (isOutOfRange) "LEGACY_OUT_OF_RANGE" else null,
                quantificationScope = null,
                quantificationReason = if (isOutOfRange) "DIRECTION_NOT_RECORDED" else null
            ),
            detail = ArrayMeasurementDetail.LegacyUnparsed(rawJson, null)
        )
    }

    private fun buildArtifacts(source: LegacyPlateRunSource): List<ArrayCaptureEvidence> {
        val stored = source.artifacts.map { it.toEvidence() }
        if (stored.any { it.captureRole == "ENDPOINT" }) return stored
        val imagePath = source.project.imageUri.takeIf(String::isNotBlank) ?: return stored
        return stored + ArrayCaptureEvidence(
            artifactId = "legacy-endpoint-${source.run.runId}",
            captureRole = "ENDPOINT",
            originalPath = imagePath,
            derivedPath = null,
            capturedAtEpochMillis = source.run.timestamp.time,
            operatorId = source.project.userId,
            actualMetadataJson = null,
            profileSnapshotJson = null,
            imageQcJson = null,
            checksumSha256 = null,
            locked = true,
            revision = 1
        )
    }

    private fun CaptureArtifact.toEvidence(): ArrayCaptureEvidence = ArrayCaptureEvidence(
        artifactId = id,
        captureRole = captureRole,
        originalPath = originalPath,
        derivedPath = derivedPath,
        capturedAtEpochMillis = capturedAt.time,
        operatorId = operatorId,
        actualMetadataJson = actualMetadataJson,
        profileSnapshotJson = profileSnapshotJson,
        imageQcJson = imageQcJson,
        checksumSha256 = checksumSha256,
        locked = locked,
        revision = revision
    )

    private fun normalizedAnalyteId(analyteId: String?): String =
        analyteId?.takeIf(String::isNotBlank) ?: LEGACY_DEFAULT_ANALYTE_ID

    private fun WellResult.normalizedRole(): String = roleType?.takeIf(String::isNotBlank)
        ?: if (isStandard) "STANDARD" else "SAMPLE"

    private fun WellResult.hasUsableBounds(): Boolean = boundsCenterOrNull() != null

    private fun WellResult.boundsCenterOrNull(): GridPoint? {
        val left = detectedRectLeft?.toDouble() ?: return null
        val top = detectedRectTop?.toDouble() ?: return null
        val right = detectedRectRight?.toDouble() ?: return null
        val bottom = detectedRectBottom?.toDouble() ?: return null
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
        if (right <= left || bottom <= top) return null
        return GridPoint((left + right) / 2.0, (top + bottom) / 2.0)
    }

    private fun legacyCanvasWidth(results: List<WellResult>): Int = results
        .mapNotNull(WellResult::detectedRectRight)
        .filter(Float::isFinite)
        .maxOrNull()
        ?.let { ceil(it.toDouble()).toInt().coerceAtLeast(1) }
        ?: PLATE96_RESULT_COLUMNS

    private fun legacyCanvasHeight(results: List<WellResult>): Int = results
        .mapNotNull(WellResult::detectedRectBottom)
        .filter(Float::isFinite)
        .maxOrNull()
        ?.let { ceil(it.toDouble()).toInt().coerceAtLeast(1) }
        ?: PLATE96_RESULT_ROWS

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun failure(code: Plate96ResultErrorCode): Plate96ResultLoadResult =
        Plate96ResultLoadResult.Failure(code)
}
