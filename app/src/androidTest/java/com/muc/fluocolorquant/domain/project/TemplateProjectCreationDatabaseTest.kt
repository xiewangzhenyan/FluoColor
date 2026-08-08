package com.muc.fluocolorquant.domain.project

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.AppDatabase
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.ResourceStatus
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AcquisitionProfile
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CarrierProfile
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.repository.AcquisitionProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.AnalysisModelRepositoryImpl
import com.muc.fluocolorquant.data.repository.AnalyteRepositoryImpl
import com.muc.fluocolorquant.data.repository.CarrierProfileRepositoryImpl
import com.muc.fluocolorquant.data.repository.ExperimentTemplateRepositoryImpl
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

/**
 * 使用真实 Room 事务验证模板项目创建。
 *
 * 该测试证明项目主档、不可变快照和分析物关联能在真实 SQLite 外键约束下共同落库，
 * 并证明模板生命周期变化不会反向污染已经冻结的历史项目。
 */
@RunWith(AndroidJUnit4::class)
class TemplateProjectCreationDatabaseTest {

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
    fun createTenByTenProjectFreezesSnapshotAndPersistsAnalyteAtomically() = runBlocking {
        val analyte = Analyte(id = "cea", name = "CEA")
        database.analyteDao().insertAnalyte(analyte)

        val carrier = CarrierProfile(
            id = "carrier-10x10",
            name = "10×10 微流控芯片",
            carrierType = CarrierType.MICROFLUIDIC_CHIP.code,
            rows = 10,
            columns = 10,
            siteShape = SiteShape.CIRCLE.code,
            status = ResourceStatus.ACTIVE.code
        )
        database.carrierProfileDao().insert(carrier)

        val acquisition = AcquisitionProfile(
            id = "device-v1",
            name = "实验室固定比色装置",
            supportedModesJson = "[\"COLORIMETRIC\"]",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            cameraControlStrategy = "FIXED_PROFILE",
            status = ResourceStatus.ACTIVE.code
        )
        database.acquisitionProfileDao().insert(acquisition)

        val model = AnalysisModel(
            id = "model-cea",
            name = "CEA ΔE 标准曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.COLORIMETRIC.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
            processorName = "ColorimetricProcessor",
            processorVersion = "1.0.0",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-v1\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        database.analysisModelDao().insert(model)

        val template = ExperimentTemplate(
            id = "template-v1",
            templateName = "CEA 10×10 比色芯片",
            analyteId = null,
            reagentAntigenId = null,
            reagentAntibodyId = null,
            fkCurveModelId = null,
            reliableRangeMin = 0.0,
            reliableRangeMax = 0.0,
            concentrationUnit = "",
            defaultLayoutJson = null,
            version = 1,
            status = TemplateLifecycleStatus.PUBLISHED.code,
            carrierProfileId = carrier.id,
            detectionMode = DetectionModality.COLORIMETRIC.code,
            readoutLayout = ReadoutLayout.GRID_SITES.code,
            acquisitionProfileId = acquisition.id,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            publishedAt = Date()
        )
        val config = TemplateAnalyteConfig(
            id = "config-cea",
            templateId = template.id,
            analyteId = analyte.id,
            analysisModelId = model.id,
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0
        )
        val assignments = (0 until 10).flatMap { row ->
            (0 until 10).map { column ->
                TemplateSiteAssignment(
                    id = "site-$row-$column",
                    templateId = template.id,
                    rowIndex = row,
                    columnIndex = column,
                    analyteId = analyte.id,
                    roleType = TemplateSiteRole.SAMPLE.code,
                    defaultSampleSlot = TemplateSiteKey.format(row, column)
                )
            }
        }
        database.experimentTemplateDao().insertBundle(
            template = template,
            analyteConfigs = listOf(config),
            siteAssignments = assignments,
            quantitationBindings = emptyList()
        )

        val coordinator = TemplateProjectCreationCoordinator(
            templateRepository = ExperimentTemplateRepositoryImpl(database.experimentTemplateDao()),
            carrierProfileRepository = CarrierProfileRepositoryImpl(database.carrierProfileDao()),
            acquisitionProfileRepository = AcquisitionProfileRepositoryImpl(database.acquisitionProfileDao()),
            analysisModelRepository = AnalysisModelRepositoryImpl(database.analysisModelDao()),
            analyteRepository = AnalyteRepositoryImpl(database.analyteDao()),
            projectRepository = ProjectRepositoryImpl(database.projectDao())
        )
        val outcome = coordinator.createProject(
            TemplateProjectCreateRequest(
                name = "CEA 芯片批次 01",
                templateId = template.id,
                projectBatch = "P-01",
                sampleBatch = "S-01",
                sampleSlotMapping = assignments.associate { assignment ->
                    TemplateSiteKey.format(assignment.rowIndex, assignment.columnIndex) to
                        assignment.defaultSampleSlot.orEmpty()
                },
                imageUri = "content://chip/1",
                userId = "1"
            )
        )

        assertTrue(outcome is TemplateProjectCreationOutcome.Created)
        val created = outcome as TemplateProjectCreationOutcome.Created
        val storedProject = database.projectDao().getProjectById(created.project.id)
        val joins = database.projectAnalyteJoinDao().getProjectAnalyteJoins(created.project.id)
        assertNotNull(storedProject)
        assertEquals(10, storedProject?.rows)
        assertEquals(10, storedProject?.columns)
        assertEquals(template.id, storedProject?.templateId)
        assertEquals(1, joins.size)
        assertEquals(template.id, joins.single().fkTemplateId)

        val frozenJson = requireNotNull(storedProject?.templateSnapshotJson)
        val frozen = TemplateProjectSnapshotCodec.decode(frozenJson)
        assertEquals(100, frozen.siteAssignments.size)
        assertEquals("R01C01", TemplateSiteKey.format(
            frozen.siteAssignments.first().rowIndex,
            frozen.siteAssignments.first().columnIndex
        ))

        database.experimentTemplateDao().updateLifecycle(
            id = template.id,
            status = TemplateLifecycleStatus.ARCHIVED.code,
            publishedAt = template.publishedAt,
            updatedAt = Date()
        )
        val afterArchive = database.projectDao().getProjectById(created.project.id)
        assertEquals(frozenJson, afterArchive?.templateSnapshotJson)
    }
}
