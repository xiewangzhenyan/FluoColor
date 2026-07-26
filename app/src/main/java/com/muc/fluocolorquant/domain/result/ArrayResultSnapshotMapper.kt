package com.muc.fluocolorquant.domain.result

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_CORRECTED_SIGNAL_SCHEMA_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE
import com.muc.fluocolorquant.domain.detection.photometry.COLORIMETRIC_REFERENCE_EVIDENCE_SCHEMA_VERSION
import com.muc.fluocolorquant.domain.detection.photometry.BaseSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.ColorimetricSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry
import com.muc.fluocolorquant.domain.detection.photometry.LabPhotometry
import com.muc.fluocolorquant.domain.detection.photometry.RgbPhotometry
import com.muc.fluocolorquant.domain.detection.plate96.Plate96RunGeometryCodec
import com.muc.fluocolorquant.domain.detection.plate96.toResultGrid
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideCodec
import com.muc.fluocolorquant.domain.project.TemplateProjectOverrideSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.domain.project.TemplateSiteKey

/**
 * 将一次检测运行的冻结数据库证据映射为通用阵列结果。
 *
 * 本 Mapper 是历史结果的唯一拼装入口。它禁止读取当前模板库，也不会用程序默认值修复
 * 损坏的科学快照；任何已声明版本的证据损坏都返回稳定错误，由页面提示无法重建。
 */
object ArrayResultSnapshotMapper {
    private val gson = Gson()

    fun map(source: ArrayResultSnapshotSource): ArrayResultLoadResult {
        if (source.run.projectId != source.project.id) {
            return failure(ArrayResultErrorCode.PROJECT_RUN_MISMATCH)
        }
        if (source.artifacts.any { it.runId != source.run.runId }) {
            return failure(ArrayResultErrorCode.INCONSISTENT_SNAPSHOT)
        }
        if (source.measurements.any { it.runId != source.run.runId }) {
            return failure(ArrayResultErrorCode.INVALID_MEASUREMENT)
        }

        val effectiveJson = source.run.effectiveConfigSnapshotJson
            ?.takeIf(String::isNotBlank)
            ?: return failure(ArrayResultErrorCode.MISSING_EFFECTIVE_CONFIG_SNAPSHOT)
        val templateSnapshot = try {
            TemplateProjectSnapshotCodec.decode(effectiveJson)
        } catch (_: RuntimeException) {
            return failure(ArrayResultErrorCode.CORRUPT_EFFECTIVE_CONFIG_SNAPSHOT)
        }
        if (!isSnapshotInternallyConsistent(templateSnapshot)) {
            return failure(ArrayResultErrorCode.INCONSISTENT_SNAPSHOT)
        }

        val overrideSnapshot = decodeOverride(source.run.configurationDeviationJson)
            ?: if (source.run.configurationDeviationJson.isNullOrBlank()) {
                null
            } else {
                return failure(ArrayResultErrorCode.CORRUPT_CONFIGURATION_DEVIATION)
            }

        val carrierType = CarrierType.fromCode(templateSnapshot.carrierProfile.carrierType)
            ?: return failure(ArrayResultErrorCode.INCONSISTENT_SNAPSHOT)
        val gridResult = when (val parsed = parseRunGeometry(source.run.frameQcJson, carrierType)) {
            is RunGeometryParseResult.Success -> parsed.grid
            RunGeometryParseResult.MissingPgGrid ->
                return failure(ArrayResultErrorCode.MISSING_PG_GRID_GEOMETRY)
            RunGeometryParseResult.CorruptPgGrid ->
                return failure(ArrayResultErrorCode.CORRUPT_PG_GRID_GEOMETRY)
            RunGeometryParseResult.MissingPlate96 ->
                return failure(ArrayResultErrorCode.MISSING_PLATE96_GEOMETRY)
            RunGeometryParseResult.CorruptPlate96 ->
                return failure(ArrayResultErrorCode.CORRUPT_PLATE96_GEOMETRY)
        }
        if (
            gridResult.rows != templateSnapshot.carrierProfile.rows ||
            gridResult.columns != templateSnapshot.carrierProfile.columns
        ) {
            return failure(ArrayResultErrorCode.INCONSISTENT_GEOMETRY)
        }

        val physicalKeys = gridResult.sites.map { site ->
            TemplateSiteKey.format(site.key.rowIndex, site.key.columnIndex)
        }.toSet()
        if (overrideSnapshot != null && overrideSnapshot.sampleSlotMapping.keys.any { it !in physicalKeys }) {
            return failure(ArrayResultErrorCode.INCONSISTENT_SNAPSHOT)
        }

        val assignmentsByIndex = templateSnapshot.siteAssignments.associateBy { assignment ->
            assignment.rowIndex * gridResult.columns + assignment.columnIndex
        }
        val analytesById = templateSnapshot.analytes.associateBy { it.analyte.id }
        val measurementKeys = mutableSetOf<MeasurementIdentity>()
        val mappedMeasurements = linkedMapOf<Int, MutableList<ArraySiteMeasurementResult>>()

        source.measurements.sortedWith(compareBy(SiteMeasurement::siteIndex, SiteMeasurement::id))
            .forEach { measurement ->
                val assignment = assignmentsByIndex[measurement.siteIndex]
                if (!isMeasurementConsistent(
                        measurement = measurement,
                        assignment = assignment,
                        analytesById = analytesById,
                        grid = gridResult,
                        expectedDetectionMode = requireNotNull(templateSnapshot.template.detectionMode)
                    )
                ) {
                    return failure(ArrayResultErrorCode.INVALID_MEASUREMENT)
                }
                val identity = MeasurementIdentity(
                    siteIndex = measurement.siteIndex,
                    analyteId = measurement.analyteId,
                    primaryFeatureName = measurement.primaryFeatureName
                )
                if (!measurementKeys.add(identity)) {
                    return failure(ArrayResultErrorCode.INVALID_MEASUREMENT)
                }
                val detail = when (val parsed = parseMeasurementDetail(measurement, assignment, gridResult)) {
                    is DetailParseResult.Success -> parsed.detail
                    DetailParseResult.CorruptDeclaredSchema -> {
                        return failure(ArrayResultErrorCode.CORRUPT_DECLARED_SIGNAL_SCHEMA)
                    }
                }
                mappedMeasurements.getOrPut(measurement.siteIndex, ::mutableListOf) +=
                    measurement.toResult(detail)
            }

        val sites = gridResult.sites.map { localizedSite ->
            val assignment = assignmentsByIndex[localizedSite.siteIndex]
            val siteKey = TemplateSiteKey.format(
                localizedSite.key.rowIndex,
                localizedSite.key.columnIndex
            )
            ArrayPhysicalSiteResult(
                siteIndex = localizedSite.siteIndex,
                rowIndex = localizedSite.key.rowIndex,
                columnIndex = localizedSite.key.columnIndex,
                siteKey = siteKey,
                enabled = assignment?.enabled ?: false,
                roleCode = assignment?.roleType,
                analyteId = assignment?.analyteId,
                defaultSampleSlot = assignment?.defaultSampleSlot,
                sampleSlot = overrideSnapshot?.sampleSlotMapping?.get(siteKey)
                    ?: assignment?.defaultSampleSlot,
                overrideReason = overrideSnapshot?.reasons?.get(siteKey),
                standardConcentration = assignment?.standardConcentration,
                repeatGroup = assignment?.repeatGroup,
                referenceScope = assignment?.referenceScope,
                geometry = ArraySiteGeometry(
                    rectified = localizedSite.rectified,
                    original = localizedSite.original,
                    confidence = localizedSite.confidence,
                    source = localizedSite.source,
                    flags = localizedSite.flags
                ),
                measurements = mappedMeasurements[localizedSite.siteIndex].orEmpty()
            )
        }

        val snapshot = ArrayResultSnapshot(
            runId = source.run.runId,
            projectId = source.project.id,
            projectName = source.project.name,
            runTimestampEpochMillis = source.run.timestamp.time,
            runStatus = source.run.status,
            detectionMode = requireNotNull(templateSnapshot.template.detectionMode),
            carrier = ArrayCarrierResult(
                id = templateSnapshot.carrierProfile.id,
                name = templateSnapshot.carrierProfile.name,
                carrierType = templateSnapshot.carrierProfile.carrierType,
                version = templateSnapshot.carrierProfile.version,
                siteShape = templateSnapshot.carrierProfile.siteShape,
                orientationMarkerJson = templateSnapshot.carrierProfile.orientationMarkerJson
            ),
            rows = gridResult.rows,
            columns = gridResult.columns,
            analytes = templateSnapshot.analytes
                .sortedBy { it.templateConfig.displayOrder }
                .map { analyteSnapshot -> analyteSnapshot.toResult() },
            sites = sites,
            frame = ArrayFrameResult(
                locatorName = gridResult.locatorName,
                locatorVersion = gridResult.locatorVersion,
                rectifiedWidth = gridResult.rectifiedWidth,
                rectifiedHeight = gridResult.rectifiedHeight,
                chipRegionMethod = gridResult.chipRegionMethod,
                geometry = gridResult.geometry,
                qcIssues = gridResult.frameQc,
                frameQcJson = requireNotNull(source.run.frameQcJson)
            ),
            artifacts = source.artifacts
                .sortedWith(compareBy({ it.capturedAt }, { it.revision }))
                .map { artifact ->
                    ArrayCaptureEvidence(
                        artifactId = artifact.id,
                        captureRole = artifact.captureRole,
                        originalPath = artifact.originalPath,
                        derivedPath = artifact.derivedPath,
                        capturedAtEpochMillis = artifact.capturedAt.time,
                        operatorId = artifact.operatorId,
                        actualMetadataJson = artifact.actualMetadataJson,
                        profileSnapshotJson = artifact.profileSnapshotJson,
                        imageQcJson = artifact.imageQcJson,
                        checksumSha256 = artifact.checksumSha256,
                        locked = artifact.locked,
                        revision = artifact.revision
                    )
                },
            effectiveConfigSnapshotJson = effectiveJson,
            configurationDeviationJson = source.run.configurationDeviationJson,
            acquisitionMetadataJson = source.run.acquisitionMetadataJson,
            processingVersionJson = source.run.processingVersionJson,
            modelUsageJson = source.run.concentrationModelUsed,
            siteQcSummaryJson = source.run.siteQcSummaryJson
        )
        return ArrayResultLoadResult.Success(snapshot)
    }

    /**
     * 结果重建不能依赖当前 Project 字段，因此这里仅校验快照自身的关系图。
     *
     * 检测前协调器会做同类校验；结果侧再次防御，是为了安全读取旧数据库、导入归档或
     * 人工损坏的数据，不能假设“能写入就永远不会坏”。
     */
    private fun isSnapshotInternallyConsistent(snapshot: TemplateProjectSnapshot): Boolean {
        val rows = snapshot.carrierProfile.rows
        val columns = snapshot.carrierProfile.columns
        if (rows <= 0 || columns <= 0 || snapshot.template.detectionMode.isNullOrBlank()) return false
        if (
            snapshot.template.carrierProfileId != snapshot.carrierProfile.id ||
            snapshot.template.acquisitionProfileId != snapshot.acquisitionProfile.id ||
            snapshot.analytes.isEmpty()
        ) {
            return false
        }
        val analyteIds = snapshot.analytes.map { it.analyte.id }
        if (analyteIds.toSet().size != analyteIds.size) return false
        if (snapshot.analytes.any { !hasConsistentAnalyteRelations(snapshot, it) }) return false

        val coordinates = mutableSetOf<Pair<Int, Int>>()
        return snapshot.siteAssignments.all { assignment ->
            assignment.templateId == snapshot.template.id &&
                assignment.rowIndex in 0 until rows &&
                assignment.columnIndex in 0 until columns &&
                coordinates.add(assignment.rowIndex to assignment.columnIndex) &&
                (assignment.analyteId == null || assignment.analyteId in analyteIds)
        }
    }

    /** 校验分析物、模板配置、模型主档与类型专用定义属于同一冻结关系。 */
    private fun hasConsistentAnalyteRelations(
        snapshot: TemplateProjectSnapshot,
        analyteSnapshot: TemplateProjectAnalyteSnapshot
    ): Boolean {
        val analyteId = analyteSnapshot.analyte.id
        val config = analyteSnapshot.templateConfig
        val bundle = analyteSnapshot.analysisModel
        val model = bundle.model
        if (
            config.templateId != snapshot.template.id ||
            config.analyteId != analyteId ||
            config.analysisModelId != model.id ||
            model.analyteId != analyteId ||
            bundle.standardCurve?.analysisModelId?.let { it != model.id } == true ||
            bundle.deepLearning?.analysisModelId?.let { it != model.id } == true ||
            bundle.calibrationPoints.any { it.analysisModelId != model.id }
        ) {
            return false
        }
        return when (AnalysisModelType.fromCode(model.modelType)) {
            AnalysisModelType.STANDARD_CURVE ->
                bundle.standardCurve != null && bundle.deepLearning == null
            AnalysisModelType.DEEP_LEARNING ->
                bundle.deepLearning != null &&
                    bundle.standardCurve == null &&
                    bundle.calibrationPoints.isEmpty()
            null -> false
        }
    }

    private fun decodeOverride(json: String?): TemplateProjectOverrideSnapshot? {
        if (json.isNullOrBlank()) return null
        return try {
            TemplateProjectOverrideCodec.decode(json)
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * 按冻结载体类型选择几何协议，禁止仅凭行列或位点数量猜测。
     *
     * 96孔板使用独立`plate96Geometry`；微流控继续读取`pgGrid`。两者最终收敛为结果层
     * 当前使用的规则阵列视图，但解析阶段不会互相兜底，从而及时暴露错误持久化。
     */
    private fun parseRunGeometry(
        frameQcJson: String?,
        carrierType: CarrierType
    ): RunGeometryParseResult {
        if (frameQcJson.isNullOrBlank()) {
            return if (carrierType == CarrierType.PLATE) {
                RunGeometryParseResult.MissingPlate96
            } else {
                RunGeometryParseResult.MissingPgGrid
            }
        }
        val root = try {
            gson.fromJson(frameQcJson, JsonObject::class.java)
        } catch (_: RuntimeException) {
            null
        } ?: return if (carrierType == CarrierType.PLATE) {
            RunGeometryParseResult.CorruptPlate96
        } else {
            RunGeometryParseResult.CorruptPgGrid
        }
        return when (carrierType) {
            CarrierType.PLATE -> {
                val geometry = root.get("plate96Geometry")
                    ?: return RunGeometryParseResult.MissingPlate96
                try {
                    RunGeometryParseResult.Success(
                        Plate96RunGeometryCodec.decode(gson.toJson(geometry)).toResultGrid()
                    )
                } catch (_: RuntimeException) {
                    RunGeometryParseResult.CorruptPlate96
                }
            }
            CarrierType.MICROFLUIDIC_CHIP,
            CarrierType.CUSTOM -> {
                val pgGrid = root.get("pgGrid") ?: return RunGeometryParseResult.MissingPgGrid
                try {
                    RunGeometryParseResult.Success(
                        PgGridJsonCodec.decode(gson.toJson(pgGrid)).requireValid()
                    )
                } catch (_: RuntimeException) {
                    RunGeometryParseResult.CorruptPgGrid
                }
            }
        }
    }

    private fun isMeasurementConsistent(
        measurement: SiteMeasurement,
        assignment: TemplateSiteAssignment?,
        analytesById: Map<String, TemplateProjectAnalyteSnapshot>,
        grid: PgGridResult,
        expectedDetectionMode: String
    ): Boolean {
        if (measurement.siteIndex !in grid.sites.indices || assignment?.enabled != true) return false
        if (measurement.detectionMode != expectedDetectionMode) return false
        if (!hasValidMeasurementValues(measurement)) return false
        if (measurement.primaryFeatureName == COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE) {
            return measurement.analyteId == null &&
                assignment.analyteId == null &&
                assignment.roleType in setOf("BLANK", "REFERENCE") &&
                measurement.concentrationValue == null &&
                measurement.concentrationUnit == null &&
                measurement.reliableRangeStatus == null
        }
        val analyteId = measurement.analyteId ?: return false
        return analyteId in analytesById && assignment.analyteId == analyteId
    }

    /**
     * 拒绝非有限数值以及“超项目量程却仍携带浓度”等自相矛盾记录。
     *
     * `BELOW_RANGE` / `ABOVE_RANGE` 从当前版本起表示“已得到有限浓度，但属于标定范围外推”，
     * 因而允许携带浓度；旧运行曾在这两个状态下抑制浓度，空值也必须继续兼容。
     * `BELOW_PROJECT_RANGE` / `ABOVE_PROJECT_RANGE` 才表示超过用户声明的项目量程，不能携带浓度。
     */
    private fun hasValidMeasurementValues(measurement: SiteMeasurement): Boolean {
        if (measurement.primaryFeatureValue?.isFinite() == false) return false
        if (measurement.backgroundValue?.isFinite() == false) return false
        if (measurement.signalToNoiseRatio?.let { !it.isFinite() || it < 0.0 } == true) return false
        if (measurement.confidence?.let { !it.isFinite() || it !in 0.0..1.0 } == true) return false
        if (measurement.concentrationValue?.isFinite() == false) return false
        return when (measurement.reliableRangeStatus?.uppercase()) {
            "BELOW_RANGE", "ABOVE_RANGE", "WITHIN_RANGE" -> measurement.concentrationValue == null ||
                !measurement.concentrationUnit.isNullOrBlank()
            "BELOW_PROJECT_RANGE", "ABOVE_PROJECT_RANGE" -> measurement.concentrationValue == null
            null -> measurement.concentrationValue == null ||
                !measurement.concentrationUnit.isNullOrBlank()
            else -> false
        }
    }

    private fun parseMeasurementDetail(
        measurement: SiteMeasurement,
        assignment: TemplateSiteAssignment?,
        grid: PgGridResult
    ): DetailParseResult {
        if (measurement.primaryFeatureName == COLORIMETRIC_REFERENCE_EVIDENCE_FEATURE) {
            return parseColorimetricReference(measurement, assignment)
        }
        return when (measurement.detectionMode) {
            "COLORIMETRIC" -> parseColorimetricMeasurement(measurement, grid)
            "FLUORESCENCE" -> parseFluorescenceMeasurement(measurement)
            else -> DetailParseResult.Success(
                ArrayMeasurementDetail.LegacyUnparsed(
                    rawSignalJson = measurement.rawSignalJson,
                    correctedSignalJson = measurement.correctedSignalJson
                )
            )
        }
    }

    private fun parseColorimetricMeasurement(
        measurement: SiteMeasurement,
        grid: PgGridResult
    ): DetailParseResult {
        val corrected = measurement.correctedSignalJson
            ?: return legacyDetail(measurement)
        val root = parseJsonObject(corrected) ?: return legacyDetail(measurement)
        val declaredVersion = root.string("schemaVersion") ?: return legacyDetail(measurement)
        if (declaredVersion != COLORIMETRIC_CORRECTED_SIGNAL_SCHEMA_VERSION) {
            return DetailParseResult.CorruptDeclaredSchema
        }
        return try {
            val envelope = gson.fromJson(corrected, ColorimetricEnvelopeDto::class.java)
            val site = envelope.site ?: return DetailParseResult.CorruptDeclaredSchema
            val context = envelope.calibrationContext
                ?: return DetailParseResult.CorruptDeclaredSchema
            val indices = context.referenceIndices
                ?: return DetailParseResult.CorruptDeclaredSchema
            val gains = context.whiteBalanceGains
                ?: return DetailParseResult.CorruptDeclaredSchema
            val referenceRgb = context.referenceRgb
            val referenceLab = context.referenceLab
            if (
                site.base.siteIndex != measurement.siteIndex ||
                indices.distinct().size != indices.size ||
                indices.any { it !in grid.sites.indices } ||
                // 声明了参考位时必须同时保存参考RGB和Lab；直接信号允许三者全部为空。
                (indices.isNotEmpty() && (referenceRgb == null || referenceLab == null))
            ) {
                return DetailParseResult.CorruptDeclaredSchema
            }
            DetailParseResult.Success(
                ArrayMeasurementDetail.Colorimetric(
                    site = site,
                    calibrationContext = ArrayColorimetricCalibrationContext(
                        referenceIndices = indices,
                        whiteBalanceGains = gains,
                        referenceRgb = referenceRgb,
                        referenceLab = referenceLab
                    ),
                    schemaVersion = declaredVersion
                )
            )
        } catch (_: RuntimeException) {
            DetailParseResult.CorruptDeclaredSchema
        }
    }

    private fun parseColorimetricReference(
        measurement: SiteMeasurement,
        assignment: TemplateSiteAssignment?
    ): DetailParseResult {
        val root = parseJsonObject(measurement.rawSignalJson)
            ?: return DetailParseResult.CorruptDeclaredSchema
        val declaredVersion = root.string("schemaVersion")
            ?: return DetailParseResult.CorruptDeclaredSchema
        if (declaredVersion != COLORIMETRIC_REFERENCE_EVIDENCE_SCHEMA_VERSION) {
            return DetailParseResult.CorruptDeclaredSchema
        }
        return try {
            val envelope = gson.fromJson(
                measurement.rawSignalJson,
                ColorimetricReferenceEnvelopeDto::class.java
            )
            val roleCode = envelope.roleType
                ?: return DetailParseResult.CorruptDeclaredSchema
            val site = envelope.site ?: return DetailParseResult.CorruptDeclaredSchema
            if (
                site.siteIndex != measurement.siteIndex ||
                roleCode != assignment?.roleType
            ) {
                return DetailParseResult.CorruptDeclaredSchema
            }
            DetailParseResult.Success(
                ArrayMeasurementDetail.ColorimetricReference(
                    roleCode = roleCode,
                    site = site,
                    schemaVersion = declaredVersion
                )
            )
        } catch (_: RuntimeException) {
            DetailParseResult.CorruptDeclaredSchema
        }
    }

    private fun parseFluorescenceMeasurement(measurement: SiteMeasurement): DetailParseResult {
        val corrected = measurement.correctedSignalJson ?: return legacyDetail(measurement)
        return try {
            val site = gson.fromJson(corrected, FluorescenceSitePhotometry::class.java)
                ?: return legacyDetail(measurement)
            if (site.base.siteIndex != measurement.siteIndex) return legacyDetail(measurement)
            DetailParseResult.Success(ArrayMeasurementDetail.Fluorescence(site))
        } catch (_: RuntimeException) {
            legacyDetail(measurement)
        }
    }

    private fun SiteMeasurement.toResult(detail: ArrayMeasurementDetail): ArraySiteMeasurementResult {
        val siteQcObject = parseJsonObject(qcJson)
        val quantificationQcObject = parseJsonObject(quantificationQcJson)
        return ArraySiteMeasurementResult(
            measurementId = id,
            analyteId = analyteId,
            detectionMode = detectionMode,
            primaryFeatureName = primaryFeatureName,
            primaryFeatureValue = primaryFeatureValue,
            concentrationValue = concentrationValue,
            concentrationUnit = concentrationUnit,
            reliableRangeStatus = reliableRangeStatus,
            quantificationState = quantificationState,
            concentrationLowerBound = concentrationLowerBound,
            concentrationUpperBound = concentrationUpperBound,
            intervalConfidenceLevel = intervalConfidenceLevel,
            censoringDirection = censoringDirection,
            quantificationVersion = quantificationVersion,
            backgroundValue = backgroundValue,
            signalToNoiseRatio = signalToNoiseRatio,
            confidence = confidence,
            signalDetectable = signalDetectable,
            qualityReliable = qualityReliable,
            processorName = processorName,
            processorVersion = processorVersion,
            modelSnapshotJson = modelSnapshotJson,
            rawSignalJson = rawSignalJson,
            correctedSignalJson = correctedSignalJson,
            qcJson = qcJson,
            quantificationQcJson = quantificationQcJson,
            qc = ArrayMeasurementQc(
                geometrySourceCode = siteQcObject?.string("geometrySource"),
                geometryFlags = parseStringSet(siteQcObject?.array("geometryFlags")),
                photometryFlags = parseStringSet(siteQcObject?.array("photometryFlags")),
                quantificationStatus = quantificationQcObject?.string("status"),
                quantificationScope = quantificationQcObject?.string("scope"),
                quantificationReason = quantificationQcObject?.string("reason")
            ),
            detail = detail
        )
    }

    private fun TemplateProjectAnalyteSnapshot.toResult(): ArrayAnalyteResult {
        val model = analysisModel.model
        val standardCurve = analysisModel.standardCurve
        return ArrayAnalyteResult(
            analyteId = analyte.id,
            name = analyte.name,
            displayOrder = templateConfig.displayOrder,
            concentrationUnit = templateConfig.concentrationUnit,
            reliableRangeMin = templateConfig.reliableRangeMin,
            reliableRangeMax = templateConfig.reliableRangeMax,
            modelId = model.id,
            modelName = model.name,
            modelType = model.modelType,
            modelVersion = model.version,
            primaryFeature = model.primaryFeature,
            processorName = model.processorName,
            processorVersion = model.processorVersion,
            fittingFunction = normalizeFittingFunction(standardCurve?.fittingFunction),
            fittingParameters = parseFiniteDoubleMap(standardCurve?.parametersJson),
            calibrationPoints = analysisModel.calibrationPoints
                .asSequence()
                .filter { point ->
                    point.concentration.isFinite() && point.signalValue.isFinite()
                }
                .sortedWith(
                    compareBy<com.muc.fluocolorquant.data.model.CalibrationPoint> {
                        it.concentration
                    }.thenBy { it.repeatIndex }
                )
                .map { point ->
                    ArrayCalibrationPointResult(
                        concentration = point.concentration,
                        signalValue = point.signalValue,
                        repeatIndex = point.repeatIndex
                    )
                }
                .toList(),
            validationMetrics = parseFiniteDoubleMap(model.validationMetricsJson),
            projectRangeMin = templateConfig.reliableRangeMin,
            projectRangeMax = templateConfig.reliableRangeMax,
            calibrationRangeMin = model.reliableRangeMin.takeIf(Double::isFinite),
            calibrationRangeMax = model.reliableRangeMax.takeIf(Double::isFinite)
        )
    }

    /**
     * 从冻结参数 JSON 中只提取有限数值。
     *
     * 损坏或仍为仅信号占位的空参数不会让整个历史结果页崩溃；它们只是不显示曲线卡，
     * 原始 JSON 仍完整保存在运行快照中供导出和审计。
     */
    private fun parseFiniteDoubleMap(json: String?): Map<String, Double> {
        val root = parseJsonObject(json) ?: return emptyMap()
        return root.entrySet().mapNotNull { (name, element) ->
            val value = runCatching {
                element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            }.getOrNull()
            value?.takeIf(Double::isFinite)?.let { name to it }
        }.toMap()
    }

    /** 兼容旧快照保存枚举名、新快照保存稳定 identifier 的两种历史格式。 */
    private fun normalizeFittingFunction(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return FittingFunction.fromIdentifier(value)?.identifier
            ?: FittingFunction.entries.firstOrNull { function ->
                function.name.equals(value, ignoreCase = true)
            }?.identifier
    }

    private fun parseJsonObject(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (_: JsonParseException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun parseStringSet(array: JsonArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet {
            array.forEach { element ->
                if (element.isJsonPrimitive) add(element.asString)
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        return get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }

    /** 非数组扩展字段按未知处理，不能让历史 QC JSON 的形态差异中断整个结果页。 */
    private fun JsonObject.array(key: String): JsonArray? {
        return get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    }

    private fun legacyDetail(measurement: SiteMeasurement): DetailParseResult.Success {
        return DetailParseResult.Success(
            ArrayMeasurementDetail.LegacyUnparsed(
                rawSignalJson = measurement.rawSignalJson,
                correctedSignalJson = measurement.correctedSignalJson
            )
        )
    }

    private fun failure(code: ArrayResultErrorCode): ArrayResultLoadResult.Failure {
        return ArrayResultLoadResult.Failure(code)
    }

    private sealed interface RunGeometryParseResult {
        data class Success(val grid: PgGridResult) : RunGeometryParseResult
        data object MissingPgGrid : RunGeometryParseResult
        data object CorruptPgGrid : RunGeometryParseResult
        data object MissingPlate96 : RunGeometryParseResult
        data object CorruptPlate96 : RunGeometryParseResult
    }

    private sealed interface DetailParseResult {
        data class Success(val detail: ArrayMeasurementDetail) : DetailParseResult
        data object CorruptDeclaredSchema : DetailParseResult
    }

    private data class MeasurementIdentity(
        val siteIndex: Int,
        val analyteId: String?,
        val primaryFeatureName: String
    )

    /** Gson DTO 字段使用可空类型，显式拒绝缺字段，不能依赖 Kotlin 非空声明。 */
    private data class ColorimetricEnvelopeDto(
        val schemaVersion: String? = null,
        val site: ColorimetricSitePhotometry? = null,
        val calibrationContext: ColorimetricCalibrationContextDto? = null
    )

    private data class ColorimetricCalibrationContextDto(
        val referenceIndices: List<Int>? = null,
        val whiteBalanceGains: RgbPhotometry? = null,
        val referenceRgb: RgbPhotometry? = null,
        val referenceLab: LabPhotometry? = null
    )

    private data class ColorimetricReferenceEnvelopeDto(
        val schemaVersion: String? = null,
        val roleType: String? = null,
        val site: BaseSitePhotometry? = null
    )
}
