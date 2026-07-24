package com.muc.fluocolorquant.domain.detection

import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.Project
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
import com.muc.fluocolorquant.data.repository.GridDetectionRunRepositoryImpl
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import com.muc.fluocolorquant.domain.calibration.CalibrationResourceFingerprint
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationBindingFingerprint
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshot
import com.muc.fluocolorquant.domain.calibration.TemplateQuantitationResourceSnapshotCodec
import com.muc.fluocolorquant.domain.detection.grid.GridTargetPolarity
import com.muc.fluocolorquant.domain.detection.grid.OpenCvPgGridLocator
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel
import com.muc.fluocolorquant.domain.detection.photometry.FluorescencePhotometryProcessor
import com.muc.fluocolorquant.domain.detection.photometry.FluorescenceProcessorConfig
import com.muc.fluocolorquant.domain.project.TemplateProjectAnalyteSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectCreateRequest
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationCoordinator
import com.muc.fluocolorquant.domain.project.TemplateProjectCreationOutcome
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshot
import com.muc.fluocolorquant.domain.project.TemplateProjectSnapshotCodec
import com.muc.fluocolorquant.domain.result.ArrayResultLoadResult
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader

/**
 * 用户实拍15×15芯片的完整现场标定资源闭环。
 *
 * 与纯数据库闭环不同，本测试首先让生产 PG-Grid 和 PG-Quant 处理真实 JPEG，再从真实
 * 单元信号生成标准浓度、调用统一标定引擎、冻结用户选择、保存曲线和模板。随后创建一个
 * 新项目重新处理同一实拍图，最终通过历史结果仓库恢复浓度与曲线，防止“合成图能运行、
 * 实拍图只能定位却无法完成定量”的回归。
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoOnsiteQuantitationWorkflowTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        check(OpenCVLoader.initDebug()) { "OpenCV 初始化失败" }
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
    fun `实拍十五乘十五完成现场拟合模板复用定量和历史恢复`() = runBlocking {
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open(REAL_IMAGE_ASSET)
            .use { input -> requireNotNull(BitmapFactory.decodeStream(input)) }
        val analyte = Analyte(id = ANALYTE_ID, name = "CEA")
        val carrier = carrierProfile()
        val acquisition = acquisitionProfile()
        database.analyteDao().insertAnalyte(analyte)
        database.carrierProfileDao().insert(carrier)
        database.acquisitionProfileDao().insert(acquisition)

        val initialSnapshot = initialSnapshot(analyte, carrier, acquisition)
        val initialProject = projectForSnapshot(
            id = "real-photo-calibration-project",
            snapshot = initialSnapshot,
            imageUri = "asset://$REAL_IMAGE_ASSET"
        )
        val coordinator = GridDetectionCoordinator(
            locator = OpenCvPgGridLocator(),
            repository = GridDetectionRunRepositoryImpl(database)
        )

        // 第一阶段只定位和采样，不需要预先伪造孔位角色或标准浓度。
        val localization = coordinator.localize(
            GridDetectionRequest(
                project = initialProject,
                snapshot = initialSnapshot,
                endpointBitmap = bitmap,
                endpointPath = initialProject.imageUri,
                operatorId = initialProject.userId,
                runId = "real-photo-calibration-localization"
            )
        )
        assertTrue(localization is GridLocalizationOutcome.Ready)
        val calibrationSession = (localization as GridLocalizationOutcome.Ready).session
        assertEquals(225, calibrationSession.grid.sites.size)
        assertEquals(225, calibrationSession.quant.sites.size)

        // 从同一次真实采样的中间动态范围选择六个标准位点，并把现场标定范围固定为28～34。
        // 这样芯片两端会自然产生低于/高于标定范围的外推值，可直接回归用户本次遇到的真实问题：
        // 项目量程仍是0～100，标定范围外推必须保留浓度，不能再次被抑制为“无结果”。
        val fluorescence = FluorescencePhotometryProcessor.process(
            calibrationSession.quant,
            FluorescenceProcessorConfig(
                channel = FluorescenceChannel.GREEN,
                primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY
            )
        )
        val distinctSignals = fluorescence.sites
            .filter { site -> site.primaryFeatureValue.isFinite() }
            .sortedBy { site -> site.primaryFeatureValue }
            .distinctBy { site -> site.primaryFeatureValue }
        assertTrue("实拍图至少应提供六个不同的净荧光信号", distinctSignals.size >= 6)
        val standards = selectMiddleRangeStandards(distinctSignals)
        val minimumSignal = standards.minOf { site -> site.primaryFeatureValue }
        val maximumSignal = standards.maxOf { site -> site.primaryFeatureValue }
        val signalSpan = maximumSignal - minimumSignal
        assertTrue("现场标准位点必须覆盖有限信号区间", signalSpan.isFinite() && signalSpan > 0.0)
        val standardConcentrations = standards.associate { site ->
            site.base.siteIndex to CALIBRATION_MINIMUM +
                (site.primaryFeatureValue - minimumSignal) / signalSpan *
                (CALIBRATION_MAXIMUM - CALIBRATION_MINIMUM)
        }
        val fittingSnapshot = initialSnapshot.copy(
            analytes = initialSnapshot.analytes.map { snapshot ->
                snapshot.copy(
                    quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                    onsiteSelectedFeature =
                        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                    onsiteSelectedFunction = FittingFunction.LINEAR.identifier
                )
            },
            siteAssignments = List(225) { index ->
                TemplateSiteAssignment(
                    id = "calibration-site-$index",
                    templateId = initialSnapshot.template.id,
                    rowIndex = index / 15,
                    columnIndex = index % 15,
                    analyteId = ANALYTE_ID,
                    roleType = if (index in standardConcentrations) {
                        TemplateSiteRole.STANDARD.code
                    } else {
                        TemplateSiteRole.SAMPLE.code
                    },
                    standardConcentration = standardConcentrations[index],
                    enabled = true
                )
            }
        )

        val resultSet = coordinator.previewOnsiteCalibration(
            snapshot = fittingSnapshot,
            quant = calibrationSession.quant,
            analyteId = ANALYTE_ID
        )
        val candidate = requireNotNull(resultSet.candidate(resultSet.recommendedCandidateId))
        assertEquals(FittingFunction.LINEAR, candidate.function)
        assertTrue(candidate.rSquared > 0.999999)
        assertEquals(CALIBRATION_MINIMUM, candidate.standardPoints.minOf { it.first }, 1e-9)
        assertEquals(CALIBRATION_MAXIMUM, candidate.standardPoints.maxOf { it.first }, 1e-9)

        // 保存现场曲线时使用与生产 ViewModel 相同的科学字段和内容指纹。
        val modelRepository = AnalysisModelRepositoryImpl(database.analysisModelDao())
        val savedCurve = savePublishedCurve(
            repository = modelRepository,
            snapshot = fittingSnapshot,
            resultSet = resultSet,
            candidate = candidate
        )
        val selectedSnapshot = coordinator.applyOnsiteCalibrationSelection(
            snapshot = fittingSnapshot,
            resultSet = resultSet,
            selectedCandidateId = candidate.id,
            runId = "real-photo-calibration-localization",
            sourceResourceId = savedCurve.model.id
        )
        val reusableSnapshot = selectedSnapshot.copy(
            analytes = selectedSnapshot.analytes.map { snapshot ->
                // 与生产 ViewModel 使用同一同步入口，固定验证“保存并应用”不会只替换
                // 模型对象而遗漏模板配置中的模型ID。
                snapshot.withPersistedOnsiteCurveResource(savedCurve)
            }
        )
        val synchronizedAnalyte = reusableSnapshot.analytes.single()
        assertEquals(savedCurve.model.id, synchronizedAnalyte.templateConfig.analysisModelId)
        assertEquals(savedCurve.model.id, synchronizedAnalyte.analysisModel.model.id)
        assertEquals(
            savedCurve.model.id,
            synchronizedAnalyte.analyteQuantitationSnapshot?.sourceResourceId
        )

        // 实验模板属于整套布局和定量方案，必须在曲线已经冻结之后统一保存。
        val templateRepository = ExperimentTemplateRepositoryImpl(database.experimentTemplateDao())
        val savedTemplate = savePublishedTemplate(templateRepository, reusableSnapshot)
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
                name = "实拍15×15模板复用项目",
                templateId = savedTemplate.template.id,
                sampleSlotMapping = reusableSnapshot.siteAssignments.associate { assignment ->
                    siteKey(assignment.rowIndex, assignment.columnIndex) to
                        "样本-${assignment.rowIndex + 1}-${assignment.columnIndex + 1}"
                },
                imageUri = "asset://$REAL_IMAGE_ASSET",
                userId = "real-photo-device-test"
            )
        )
        assertTrue(creation is TemplateProjectCreationOutcome.Created)
        val project = (creation as TemplateProjectCreationOutcome.Created).project
        val frozenProjectSnapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(project.templateSnapshotJson)
        )
        assertEquals(savedCurve.model.id, frozenProjectSnapshot.analytes.single().analysisModel.model.id)
        assertEquals(
            savedCurve.model.id,
            frozenProjectSnapshot.analytes.single().analyteQuantitationSnapshot?.sourceResourceId
        )

        // 新项目重新执行真实图像定位，之后直接使用模板冻结曲线计算，不允许重新拟合。
        val runId = "real-photo-reused-template-run"
        val newLocalization = coordinator.localize(
            GridDetectionRequest(
                project = project,
                snapshot = frozenProjectSnapshot,
                endpointBitmap = bitmap,
                endpointPath = project.imageUri,
                operatorId = project.userId,
                runId = runId,
                capturedAt = Date(3_000L)
            )
        )
        assertTrue(newLocalization is GridLocalizationOutcome.Ready)
        val outcome = coordinator.finalizeLocalized(
            session = (newLocalization as GridLocalizationOutcome.Ready).session,
            finalizedSnapshot = frozenProjectSnapshot
        )
        assertTrue(outcome is GridDetectionOutcome.Completed)
        val completed = outcome as GridDetectionOutcome.Completed
        assertEquals(225, completed.measurementCount)
        assertTrue(completed.signalOnlyAnalyteIds.isEmpty())

        // 历史入口只读取 DetectionRun 冻结证据；曲线、单位、浓度和实拍几何必须完整恢复。
        val history = ArrayResultRepositoryImpl(database).loadSnapshot(runId)
        assertTrue(history is ArrayResultLoadResult.Success)
        val historySnapshot = (history as ArrayResultLoadResult.Success).snapshot
        assertEquals(15, historySnapshot.rows)
        assertEquals(15, historySnapshot.columns)
        assertEquals(225, historySnapshot.sites.size)
        assertEquals(225, historySnapshot.sites.sumOf { site -> site.measurements.size })
        assertEquals("linear", historySnapshot.analytes.single().fittingFunction)
        assertEquals(candidate.parameters, historySnapshot.analytes.single().fittingParameters)
        val historyMeasurements = historySnapshot.sites.flatMap { site -> site.measurements }
        val quantifiedSites = historyMeasurements.count { measurement ->
            measurement.concentrationValue != null
        }
        assertEquals("项目量程内的标定外推不得抑制浓度", 225, quantifiedSites)
        assertTrue(historyMeasurements.any { it.reliableRangeStatus == "BELOW_RANGE" })
        assertTrue(historyMeasurements.any { it.reliableRangeStatus == "ABOVE_RANGE" })
        assertTrue(historyMeasurements.none {
            it.reliableRangeStatus == "BELOW_PROJECT_RANGE" ||
                it.reliableRangeStatus == "ABOVE_PROJECT_RANGE"
        })
        val restoredAnalyte = historySnapshot.analytes.single()
        assertEquals(PROJECT_MINIMUM, restoredAnalyte.projectRangeMin ?: Double.NaN, 1e-9)
        assertEquals(PROJECT_MAXIMUM, restoredAnalyte.projectRangeMax ?: Double.NaN, 1e-9)
        assertEquals(CALIBRATION_MINIMUM, restoredAnalyte.calibrationRangeMin ?: Double.NaN, 1e-9)
        assertEquals(CALIBRATION_MAXIMUM, restoredAnalyte.calibrationRangeMax ?: Double.NaN, 1e-9)
        standards.forEach { standard ->
            val expected = requireNotNull(standardConcentrations[standard.base.siteIndex])
            val actual = historySnapshot.sites[standard.base.siteIndex]
                .measurements.single().concentrationValue
            assertNotNull(actual)
            assertEquals(expected, requireNotNull(actual), 1e-5)
        }
    }

    /**
     * 从20%～80%的中间信号区间选取六个标准位点，刻意保留两端样本用于验证标定外推。
     * 所有索引均来自去重后的有序有限信号，不依赖合成图片或固定孔位编号。
     */
    private fun selectMiddleRangeStandards(
        sites: List<com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry>
    ): List<com.muc.fluocolorquant.domain.detection.photometry.FluorescenceSitePhotometry> {
        val last = sites.lastIndex
        return listOf(
            last / 5,
            last * 8 / 25,
            last * 11 / 25,
            last * 14 / 25,
            last * 17 / 25,
            last * 4 / 5
        )
            .distinct()
            .map(sites::get)
    }

    /** 将统一标定候选转换成可复用且可审计的标准曲线资源。 */
    private suspend fun savePublishedCurve(
        repository: AnalysisModelRepositoryImpl,
        snapshot: TemplateProjectSnapshot,
        resultSet: com.muc.fluocolorquant.domain.calibration.CalibrationResultSet,
        candidate: com.muc.fluocolorquant.domain.calibration.CalibrationCandidate
    ): AnalysisModelBundle {
        val fingerprint = CalibrationResourceFingerprint.create(
            analyteId = ANALYTE_ID,
            modalityCode = DetectionModality.FLUORESCENCE.code,
            concentrationUnit = UNIT,
            candidate = candidate,
            processorVersion = resultSet.processorVersion,
            engineVersion = resultSet.engineVersion
        )
        val placeholderId = "real-photo-curve-placeholder"
        val draft = repository.createDraft(
            AnalysisModelBundle(
                model = snapshot.analytes.single().analysisModel.model.copy(
                    id = placeholderId,
                    name = "CEA 实拍现场曲线",
                    modelType = AnalysisModelType.STANDARD_CURVE.code,
                    primaryFeature = candidate.primaryFeature.code,
                    processorVersion = resultSet.processorVersion,
                    reliableRangeMin = candidate.standardPoints.minOf { point -> point.first },
                    reliableRangeMax = candidate.standardPoints.maxOf { point -> point.first },
                    validationMetricsJson = gson.toJson(
                        mapOf(
                            "R2" to candidate.rSquared,
                            "NORMALIZED_RMSE" to candidate.normalizedRmse,
                            "ACCEPTED_STANDARD_RATIO" to candidate.acceptedStandardRatio
                        )
                    ),
                    contentFingerprint = fingerprint,
                    status = AnalysisModelLifecycleStatus.DRAFT.code
                ),
                standardCurve = StandardCurveDefinition(
                    analysisModelId = placeholderId,
                    fittingFunction = candidate.function.identifier,
                    parametersJson = gson.toJson(candidate.parameters),
                    monotonicDirection = "AUTO"
                ),
                calibrationPoints = candidate.standardPoints.mapIndexed {
                        index, (concentration, signal) ->
                    CalibrationPoint(
                        id = "real-photo-point-$index",
                        analysisModelId = placeholderId,
                        concentration = concentration,
                        signalValue = signal,
                        repeatIndex = index,
                        runId = "real-photo-calibration-localization"
                    )
                }
            )
        )
        repository.publish(draft.model.id)
        return requireNotNull(repository.getBundle(draft.model.id))
    }

    /** 保存整套15×15布局和现场曲线冻结摘要，模板本身不复制任何模型二进制。 */
    private suspend fun savePublishedTemplate(
        repository: ExperimentTemplateRepositoryImpl,
        snapshot: TemplateProjectSnapshot
    ): ExperimentTemplateBundle {
        val temporaryTemplateId = "real-photo-template-placeholder"
        val analyteSnapshot = snapshot.analytes.single()
        val quantitation = requireNotNull(analyteSnapshot.analyteQuantitationSnapshot)
        val frozen = TemplateQuantitationResourceSnapshot(
            quantitation = quantitation,
            analysisModel = analyteSnapshot.analysisModel
        )
        val draft = repository.createDraft(
            ExperimentTemplateBundle(
                template = snapshot.template.copy(
                    id = temporaryTemplateId,
                    templateName = "实拍15×15现场标定模板",
                    analyteId = ANALYTE_ID,
                    reliableRangeMin = analyteSnapshot.templateConfig.reliableRangeMin ?: 0.0,
                    reliableRangeMax = requireNotNull(
                        analyteSnapshot.templateConfig.reliableRangeMax
                    ),
                    concentrationUnit = UNIT,
                    status = TemplateLifecycleStatus.DRAFT.code,
                    publishedAt = null
                ),
                analyteConfigs = listOf(
                    analyteSnapshot.templateConfig.copy(
                        id = "real-photo-template-config-placeholder",
                        templateId = temporaryTemplateId,
                        analysisModelId = analyteSnapshot.analysisModel.model.id
                    )
                ),
                siteAssignments = snapshot.siteAssignments.mapIndexed { index, assignment ->
                    assignment.copy(
                        id = "real-photo-template-site-$index",
                        templateId = temporaryTemplateId
                    )
                },
                quantitationBindings = listOf(
                    TemplateQuantitationBinding(
                        id = "real-photo-binding-placeholder",
                        templateId = temporaryTemplateId,
                        analyteId = ANALYTE_ID,
                        method = quantitation.method.name,
                        sourceResourceId = quantitation.sourceResourceId,
                        resourceSnapshotJson =
                            TemplateQuantitationResourceSnapshotCodec.encode(frozen),
                        contentFingerprint = TemplateQuantitationBindingFingerprint.create(frozen),
                        processorName = analyteSnapshot.analysisModel.model.processorName,
                        processorVersion = quantitation.processorVersion
                    )
                )
            )
        )
        repository.publish(draft.template.id)
        return requireNotNull(repository.getBundle(draft.template.id))
    }

    /** 初始快照只声明载体、分析物和现场拟合入口，孔位角色由定位完成后再配置。 */
    private fun initialSnapshot(
        analyte: Analyte,
        carrier: CarrierProfile,
        acquisition: AcquisitionProfile
    ): TemplateProjectSnapshot {
        val templateId = "real-photo-initial-template"
        val provisionalModel = AnalysisModel(
            id = "real-photo-provisional-model",
            name = "CEA 现场拟合占位",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = ANALYTE_ID,
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            compatibleCarrierTypesJson = "[\"${CarrierType.MICROFLUIDIC_CHIP.code}\"]",
            compatibleAcquisitionProfileIdsJson = "[\"${acquisition.id}\"]",
            concentrationUnit = UNIT,
            reliableRangeMin = PROJECT_MINIMUM,
            reliableRangeMax = PROJECT_MAXIMUM,
            status = AnalysisModelLifecycleStatus.DRAFT.code
        )
        return TemplateProjectSnapshot(
            frozenAtEpochMillis = 1_000L,
            template = ExperimentTemplate(
                id = templateId,
                templateName = "实拍15×15现场标定草稿",
                analyteId = ANALYTE_ID,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = PROJECT_MINIMUM,
                reliableRangeMax = PROJECT_MAXIMUM,
                concentrationUnit = UNIT,
                defaultLayoutJson = null,
                status = TemplateLifecycleStatus.DRAFT.code,
                carrierProfileId = carrier.id,
                detectionMode = DetectionModality.FLUORESCENCE.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = acquisition.id,
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            ),
            carrierProfile = carrier,
            acquisitionProfile = acquisition,
            analytes = listOf(
                TemplateProjectAnalyteSnapshot(
                    analyte = analyte,
                    templateConfig = TemplateAnalyteConfig(
                        id = "real-photo-initial-config",
                        templateId = templateId,
                        analyteId = ANALYTE_ID,
                        analysisModelId = provisionalModel.id,
                        concentrationUnit = UNIT,
                        reliableRangeMin = PROJECT_MINIMUM,
                        reliableRangeMax = PROJECT_MAXIMUM,
                        displayConfigJson = ScientificDetectionConfigCodec
                            .encodeFluorescenceDisplay(FluorescenceChannel.GREEN)
                    ),
                    analysisModel = AnalysisModelBundle(
                        model = provisionalModel,
                        standardCurve = StandardCurveDefinition(
                            analysisModelId = provisionalModel.id,
                            fittingFunction = FittingFunction.LINEAR.identifier,
                            parametersJson = "{\"a\":1.0,\"b\":0.0}",
                            monotonicDirection = "INCREASING"
                        )
                    ),
                    quantitationMode = GridAnalyteQuantitationMode.ONSITE_AUTO_FIT.code,
                    onsiteSelectedFeature =
                        AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
                    onsiteSelectedFunction = FittingFunction.LINEAR.identifier
                )
            ),
            siteAssignments = emptyList()
        )
    }

    private fun carrierProfile(): CarrierProfile = CarrierProfile(
        id = CARRIER_ID,
        name = "15×15 实拍芯片",
        carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
        rows = 15,
        columns = 15,
        siteShape = SiteShape.SQUARE.code,
        locatorConfigJson = ScientificDetectionConfigCodec.encodeCarrierLocator(
            GridTargetPolarity.DARK
        ),
        status = ResourceStatus.ACTIVE.code
    )

    private fun acquisitionProfile(): AcquisitionProfile = AcquisitionProfile(
        id = ACQUISITION_ID,
        name = "实拍设备测试档案",
        supportedModesJson = "[\"${DetectionModality.FLUORESCENCE.code}\"]",
        compatibleCarrierTypesJson = "[\"${CarrierType.MICROFLUIDIC_CHIP.code}\"]",
        cameraControlStrategy = "AUTO_LOCKED",
        status = ResourceStatus.ACTIVE.code
    )

    private fun projectForSnapshot(
        id: String,
        snapshot: TemplateProjectSnapshot,
        imageUri: String
    ): Project = Project(
        id = id,
        name = id,
        detectionMode = DetectionModality.FLUORESCENCE.code,
        recognitionType = "AUTO",
        imageUri = imageUri,
        rows = 15,
        columns = 15,
        createTime = Date(1_000L),
        userId = "real-photo-device-test",
        lastRunTimestamp = null,
        analysisMethod = "ONSITE_CALIBRATION",
        templateId = snapshot.template.id,
        templateVersion = snapshot.template.version,
        templateSnapshotJson = TemplateProjectSnapshotCodec.encode(snapshot)
    )

    private fun siteKey(rowIndex: Int, columnIndex: Int): String =
        "R%02dC%02d".format(rowIndex + 1, columnIndex + 1)

    private companion object {
        val gson = Gson()
        const val REAL_IMAGE_ASSET = "pg_grid/real_v1/images/real_15x15_01.jpg"
        const val ANALYTE_ID = "real-photo-cea"
        const val CARRIER_ID = "real-photo-carrier-15x15"
        const val ACQUISITION_ID = "real-photo-acquisition"
        const val UNIT = "ng/mL"
        const val PROJECT_MINIMUM = 0.0
        const val PROJECT_MAXIMUM = 100.0
        const val CALIBRATION_MINIMUM = 28.0
        const val CALIBRATION_MAXIMUM = 34.0
    }
}
