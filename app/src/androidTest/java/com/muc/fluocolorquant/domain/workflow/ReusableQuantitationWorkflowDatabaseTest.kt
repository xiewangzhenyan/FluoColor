package com.muc.fluocolorquant.domain.workflow

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.SiteMeasurement
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateQuantitationBinding
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepositoryImpl
import com.muc.fluocolorquant.data.repository.AnalyteRepositoryImpl
import com.muc.fluocolorquant.data.repository.ArrayResultRepositoryImpl
import com.muc.fluocolorquant.data.repository.CarrierProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.ExperimentTemplateBundle
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepositoryImpl
import com.muc.fluocolorquant.data.repository.GridDetectionPersistenceBundle
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepositoryImpl
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationMethod
import com.muc.fluocolorquant.domain.calibration.AnalyteQuantitationSnapshot
import com.muc.fluocolorquant.domain.calibration.AppliedCalibrationSnapshot
import com.muc.fluocolorquant.domain.calibration.CalibrationPolicy
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationBindingFingerprint
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshot
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.detection.grid.GridGeometryDiagnostics
import com.muc.fluocolorquant.domain.detection.grid.GridHomography
import com.muc.fluocolorquant.domain.detection.grid.GridLocalizedSite
import com.muc.fluocolorquant.domain.detection.grid.GridPoint
import com.muc.fluocolorquant.domain.detection.grid.GridPointSource
import com.muc.fluocolorquant.domain.detection.grid.GridSiteKey
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.PgGridJsonCodec
import com.muc.fluocolorquant.domain.detection.grid.PgGridResult
import com.muc.fluocolorquant.domain.detection.quantification.EndpointQuantificationResult
import com.muc.fluocolorquant.domain.detection.quantification.StandardCurveQuantifier
import com.muc.fluocolorquant.domain.project.TemplateProjectCreateRequest
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 可复用定量方案的固定设备业务闭环。
 *
 * 该测试不依赖系统文件选择器或随机图像信号，而是在真实 Room 外键、事务和结果 Mapper
 * 上验证用户最关心的完整链路：保存现场曲线、保存实验模板、新项目应用模板、执行冻结
 * 曲线反算、保存 DetectionRun，最后从历史入口只读重建同一浓度和曲线参数。
 */
@RunWith(AndroidJUnit4::class)
class ReusableQuantitationWorkflowDatabaseTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `保存现场曲线模板后新项目完成定量并从历史恢复同一结果`() = runBlocking {
        val analyte = Analyte(id = ANALYTE_ID, name = "CEA")
        database.analyteDao().insertAnalyte(analyte)
        database.carrierProfileDao().insert(carrier())
        database.acquisitionProfileDao().insert(acquisition())

        // 第一步：把用户已经审阅的现场线性曲线保存并发布到统一分析模型库。
        val modelRepository = AnalysisModelRepositoryImpl(database.analysisModelDao())
        val savedCurve = modelRepository.createDraft(onsiteCurveDraft())
        modelRepository.publish(savedCurve.model.id)
        val publishedCurve = requireNotNull(modelRepository.getBundle(savedCurve.model.id))

        // 第二步：模板保存完整资源快照，同时保留曲线库 ID 作为可追溯关联。
        val quantitation = onsiteQuantitationSnapshot(publishedCurve.model.id)
        val frozenResource = TemplateQuantitationResourceSnapshot(
            quantitation = quantitation,
            analysisModel = publishedCurve
        )
        val temporaryTemplateId = "template-placeholder"
        val templateRepository = ExperimentTemplateRepositoryImpl(database.experimentTemplateDao())
        val templateDraft = templateRepository.createDraft(
            ExperimentTemplateBundle(
                template = experimentTemplate(temporaryTemplateId),
                analyteConfigs = listOf(
                    TemplateAnalyteConfig(
                        id = "config-placeholder",
                        templateId = temporaryTemplateId,
                        analyteId = ANALYTE_ID,
                        analysisModelId = publishedCurve.model.id,
                        concentrationUnit = UNIT,
                        reliableRangeMin = 0.0,
                        reliableRangeMax = 100.0
                    )
                ),
                siteAssignments = siteAssignments(temporaryTemplateId),
                quantitationBindings = listOf(
                    TemplateQuantitationBinding(
                        id = "binding-placeholder",
                        templateId = temporaryTemplateId,
                        analyteId = ANALYTE_ID,
                        method = AnalyteQuantitationMethod.ONSITE_CALIBRATION.name,
                        sourceResourceId = publishedCurve.model.id,
                        resourceSnapshotJson =
                            TemplateQuantitationResourceSnapshotCodec.encode(frozenResource),
                        contentFingerprint =
                            TemplateQuantitationBindingFingerprint.create(frozenResource),
                        processorName = publishedCurve.model.processorName,
                        processorVersion = publishedCurve.model.processorVersion
                    )
                )
            )
        )
        templateRepository.publish(templateDraft.template.id)

        // 第三步：新项目应用刚发布的模板，项目内部得到不可变曲线和定量策略快照。
        val projectCoordinator = TemplateProjectCreationCoordinator(
            templateRepository = templateRepository,
            carrierProfileRepository = CarrierProfileRepositoryImpl(database.carrierProfileDao()),
            acquisitionProfileRepository =
                AcquisitionProfileRepositoryImpl(database.acquisitionProfileDao()),
            analysisModelRepository = modelRepository,
            analyteRepository = AnalyteRepositoryImpl(database.analyteDao()),
            projectRepository = ProjectRepositoryImpl(database.projectDao())
        )
        val creation = projectCoordinator.createProject(
            TemplateProjectCreateRequest(
                name = "CEA 模板复用批次",
                templateId = templateDraft.template.id,
                sampleSlotMapping = siteAssignments(templateDraft.template.id).associate { site ->
                    "R%02dC%02d".format(site.rowIndex + 1, site.columnIndex + 1) to
                        "样本-${site.rowIndex}-${site.columnIndex}"
                },
                imageUri = "content://workflow/real-endpoint.png",
                userId = "device-test-user"
            )
        )
        assertTrue(creation is TemplateProjectCreationOutcome.Created)
        val project = (creation as TemplateProjectCreationOutcome.Created).project
        val projectSnapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(project.templateSnapshotJson)
        )
        val frozenAnalyte = projectSnapshot.analytes.single()
        assertEquals(
            publishedCurve.model.id,
            frozenAnalyte.analyteQuantitationSnapshot?.sourceResourceId
        )
        assertEquals(
            mapOf("a" to 2.0, "b" to 1.0),
            frozenAnalyte.analyteQuantitationSnapshot?.calibration?.parameters
        )

        // 第四步：最终定量直接执行项目中冻结的模型参数，绝不重新拟合标准点。
        val quantified = StandardCurveQuantifier.quantify(
            bundle = frozenAnalyte.analysisModel,
            signalValue = 51.0
        )
        assertTrue(quantified is EndpointQuantificationResult.Quantified)
        val concentration = quantified as EndpointQuantificationResult.Quantified
        assertEquals(25.0, concentration.concentration, 1e-8)
        assertEquals(UNIT, concentration.unit)

        // 第五步：把运行、原图证据和浓度测量放入同一个事务，再模拟历史记录重开。
        val runId = "run-reusable-quantitation"
        GridDetectionRunRepositoryImpl(database).save(
            GridDetectionPersistenceBundle(
                run = DetectionRun(
                    runId = runId,
                    projectId = project.id,
                    timestamp = Date(2_000L),
                    detectionModelUsed = "fixed-device-workflow",
                    concentrationModelUsed = "{\"modelId\":\"${publishedCurve.model.id}\"}",
                    status = "Completed",
                    errorMessage = null,
                    confThreshold = null,
                    iouThreshold = null,
                    wellsDetected = 1,
                    effectiveConfigSnapshotJson = TemplateProjectSnapshotCodec.encode(projectSnapshot),
                    processingVersionJson = "{\"workflow\":\"reusable-quantitation-v1\"}",
                    frameQcJson = frameQcJson(grid()),
                    configurationDeviationJson = project.overrideJson
                ),
                endpointArtifact = CaptureArtifact(
                    id = "artifact-reusable-endpoint",
                    runId = runId,
                    captureRole = "ENDPOINT",
                    originalPath = project.imageUri,
                    capturedAt = Date(2_000L),
                    locked = true
                ),
                measurements = listOf(
                    SiteMeasurement(
                        runId = runId,
                        siteIndex = 0,
                        analyteId = ANALYTE_ID,
                        detectionMode = DetectionModality.FLUORESCENCE.code,
                        rawSignalJson = "{\"netFluorescence\":51.0}",
                        correctedSignalJson = null,
                        primaryFeatureName =
                            AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                        primaryFeatureValue = 51.0,
                        confidence = 0.98,
                        signalDetectable = true,
                        qualityReliable = true,
                        processorName = publishedCurve.model.processorName,
                        processorVersion = publishedCurve.model.processorVersion,
                        concentrationValue = concentration.concentration,
                        concentrationUnit = concentration.unit,
                        reliableRangeStatus = concentration.rangeStatus.name,
                        modelSnapshotJson = concentration.modelSnapshotJson
                    )
                )
            )
        )

        val reopened = ArrayResultRepositoryImpl(database).loadSnapshot(runId)
        assertTrue(reopened is ArrayResultLoadResult.Success)
        val historySnapshot = (reopened as ArrayResultLoadResult.Success).snapshot
        assertEquals(project.id, historySnapshot.projectId)
        assertEquals(25.0, historySnapshot.sites.first().measurements.single().concentrationValue!!, 1e-8)
        assertEquals(UNIT, historySnapshot.analytes.single().concentrationUnit)
        assertEquals("linear", historySnapshot.analytes.single().fittingFunction)
        assertEquals(
            mapOf("a" to 2.0, "b" to 1.0),
            historySnapshot.analytes.single().fittingParameters
        )
    }

    /** 构造一条确定性的 y=2x+1 现场曲线，便于设备测试精确断言反算值。 */
    private fun onsiteCurveDraft(): AnalysisModelBundle {
        val placeholderId = "curve-placeholder"
        return AnalysisModelBundle(
            model = AnalysisModel(
                id = placeholderId,
                name = "CEA 现场曲线",
                modelType = AnalysisModelType.STANDARD_CURVE.code,
                analyteId = ANALYTE_ID,
                detectionMode = DetectionModality.FLUORESCENCE.code,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                processorName = "fluorescence-photometry",
                processorVersion = "v2",
                compatibleCarrierTypesJson = "[\"${CarrierType.MICROFLUIDIC_CHIP.code}\"]",
                compatibleAcquisitionProfileIdsJson = "[\"$ACQUISITION_ID\"]",
                concentrationUnit = UNIT,
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                validationMetricsJson = "{\"R2\":1.0}",
                contentFingerprint = "fixed-device-onsite-curve-v1"
            ),
            standardCurve = StandardCurveDefinition(
                analysisModelId = placeholderId,
                fittingFunction = "linear",
                parametersJson = "{\"a\":2.0,\"b\":1.0}",
                monotonicDirection = "INCREASING"
            ),
            calibrationPoints = listOf(0.0, 25.0, 50.0, 75.0, 100.0).mapIndexed {
                    index, concentration ->
                CalibrationPoint(
                    id = "point-placeholder-$index",
                    analysisModelId = placeholderId,
                    concentration = concentration,
                    signalValue = 2.0 * concentration + 1.0,
                    repeatIndex = index
                )
            }
        )
    }

    /** 模板绑定保存现场曲线的完整冻结摘要，供资源删除或升级后继续复现实验。 */
    private fun onsiteQuantitationSnapshot(modelId: String): AnalyteQuantitationSnapshot {
        return AnalyteQuantitationSnapshot(
            analyteId = ANALYTE_ID,
            method = AnalyteQuantitationMethod.ONSITE_CALIBRATION,
            concentrationUnit = UNIT,
            sourceResourceId = modelId,
            calibration = AppliedCalibrationSnapshot(
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                fittingFunction = "linear",
                parameters = mapOf("a" to 2.0, "b" to 1.0),
                standardPoints = listOf(0.0 to 1.0, 25.0 to 51.0, 100.0 to 201.0),
                latexFormula = "y=2x+1",
                reliableRangeMin = 0.0,
                reliableRangeMax = 100.0,
                rSquared = 1.0,
                rmse = 0.0,
                normalizedRmse = 0.0,
                mae = 0.0,
                backCalculatedRmsePercent = 0.0,
                acceptedStandardRatio = 1.0,
                weightingCode = 0,
                accepted = true,
                policySnapshot = CalibrationPolicy.DEFAULT,
                processorVersion = "v2",
                engineVersion = "ArrayCalibration-v1",
                inputFingerprint = "fixed-device-input-v1"
            ),
            processorVersion = "v2",
            inputFingerprint = "fixed-device-input-v1"
        )
    }

    private fun carrier(): CarrierProfile = CarrierProfile(
        id = CARRIER_ID,
        name = "2×2 固定测试芯片",
        carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
        rows = 2,
        columns = 2,
        siteShape = SiteShape.SQUARE.code,
        status = ResourceStatus.ACTIVE.code
    )

    private fun acquisition(): AcquisitionProfile = AcquisitionProfile(
        id = ACQUISITION_ID,
        name = "固定设备测试采集档案",
        supportedModesJson = "[\"${DetectionModality.FLUORESCENCE.code}\"]",
        compatibleCarrierTypesJson = "[\"${CarrierType.MICROFLUIDIC_CHIP.code}\"]",
        cameraControlStrategy = "AUTO_LOCKED",
        status = ResourceStatus.ACTIVE.code
    )

    private fun experimentTemplate(id: String): ExperimentTemplate = ExperimentTemplate(
        id = id,
        templateName = "CEA 可复用定量模板",
        analyteId = ANALYTE_ID,
        reagentAntigenId = null,
        reagentAntibodyId = null,
        fkCurveModelId = null,
        reliableRangeMin = 0.0,
        reliableRangeMax = 100.0,
        concentrationUnit = UNIT,
        defaultLayoutJson = null,
        carrierProfileId = CARRIER_ID,
        detectionMode = DetectionModality.FLUORESCENCE.code,
        readoutLayout = ReadoutLayout.GRID_SITES.code,
        acquisitionProfileId = ACQUISITION_ID,
        inputProtocol = InputProtocol.ENDPOINT_ONLY.code
    )

    private fun siteAssignments(templateId: String): List<TemplateSiteAssignment> {
        return List(4) { index ->
            TemplateSiteAssignment(
                id = "site-$templateId-$index",
                templateId = templateId,
                rowIndex = index / 2,
                columnIndex = index % 2,
                analyteId = ANALYTE_ID,
                roleType = TemplateSiteRole.SAMPLE.code,
                enabled = true
            )
        }
    }

    /** 历史结果 Mapper 需要完整 PG-Grid 几何；这里使用固定2×2可信晶格。 */
    private fun grid(): PgGridResult {
        val sites = List(4) { index ->
            GridLocalizedSite(
                key = GridSiteKey(index / 2, index % 2),
                siteIndex = index,
                rectified = GridPoint((index % 2) * 10.0 + 5.0, (index / 2) * 10.0 + 5.0),
                original = GridPoint((index % 2) * 11.0 + 6.0, (index / 2) * 11.0 + 6.0),
                confidence = 0.98,
                source = GridPointSource.CANDIDATE_REFINED,
                flags = emptySet()
            )
        }
        return PgGridResult(
            rows = 2,
            columns = 2,
            rectifiedWidth = 20,
            rectifiedHeight = 20,
            targetPolarity = GridTargetPolarity.DARK,
            chipRegionMethod = "fixed-device-test",
            chipCorners = listOf(
                GridPoint(0.0, 0.0),
                GridPoint(20.0, 0.0),
                GridPoint(20.0, 20.0),
                GridPoint(0.0, 20.0)
            ),
            homography = GridHomography(forward = IDENTITY, inverse = IDENTITY),
            sites = sites,
            geometry = GridGeometryDiagnostics(
                candidateSupportRatio = 1.0,
                trusted = true,
                observedRatio = 1.0,
                geometryRmsePx = 0.0,
                inlierCount = 4,
                outlierCount = 0,
                meanConfidence = 0.98
            ),
            frameQc = emptyList(),
            locatorName = "fixed-device-pg-grid",
            locatorVersion = "2.1"
        ).requireValid()
    }

    private fun frameQcJson(grid: PgGridResult): String {
        return gson.toJson(
            linkedMapOf(
                "frame" to linkedMapOf<String, Any>(
                    "geometry" to grid.geometry,
                    "issues" to grid.frameQc
                ),
                "pgGrid" to gson.fromJson(PgGridJsonCodec.encode(grid), JsonObject::class.java)
            )
        )
    }

    private companion object {
        val gson = Gson()
        val IDENTITY = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        const val ANALYTE_ID = "workflow-cea"
        const val CARRIER_ID = "workflow-carrier"
        const val ACQUISITION_ID = "workflow-acquisition"
        const val UNIT = "ng/mL"
    }
}
