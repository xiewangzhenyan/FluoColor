package com.muc.fluocolorquant.domain.project

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.CarrierType
import com.muc.fluocolorquant.data.enums.SiteShape
import com.muc.fluocolorquant.data.enums.SpectrumLightSource
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.domain.detection.GridCarrierRoute
import com.muc.fluocolorquant.domain.detection.GridDetectionRouteResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无模板直接创建必须完整保存多分析物关系，并等待后续布局页分配物理位点。 */
class DirectProjectCreationCoordinatorTest {
    private val repository = FakeProjectRepository()
    private val coordinator = DirectProjectCreationCoordinator(repository)
    private val cea = Analyte(id = "cea", name = "CEA")
    private val afp = Analyte(id = "afp", name = "AFP")

    @Test
    fun `十乘十项目保存两个分析物及各自单位和最大浓度`() = runTest {
        val outcome = coordinator.create(
            request(
                analytes = listOf(
                    DirectProjectAnalyteRequest(cea, "ng/mL", 120.0),
                    DirectProjectAnalyteRequest(afp, "pg/mL", 2500.0)
                )
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertEquals(ProjectDetectionDestination.GRID_ENDPOINT, outcome.destination)
        assertEquals(10, outcome.project.rows)
        assertEquals(10, outcome.project.columns)
        assertEquals("SIGNAL_ONLY", outcome.project.analysisMethod)
        assertEquals(null, outcome.project.lightSource)
        assertEquals(listOf("cea", "afp"), snapshot.analytes.map { it.analyte.id })
        assertEquals(
            listOf("ng/mL", "pg/mL"),
            snapshot.analytes.map { it.templateConfig.concentrationUnit }
        )
        assertEquals(
            listOf(120.0, 2500.0),
            snapshot.analytes.map { it.templateConfig.reliableRangeMax }
        )
        assertEquals(2, repository.savedJoins.size)
        assertEquals(
            mapOf("cea" to "ng/mL", "afp" to "pg/mL"),
            repository.savedJoins.associate { it.analyteId to it.concentrationUnit }
        )
        assertEquals(
            mapOf("cea" to 120.0, "afp" to 2500.0),
            repository.savedJoins.associate { it.analyteId to it.maxConcentration }
        )
    }

    @Test
    fun `创建阶段不会把全部阵列位点自动分给第一个分析物`() = runTest {
        val outcome = coordinator.create(
            request(
                analytes = listOf(
                    DirectProjectAnalyteRequest(cea, "ng/mL"),
                    DirectProjectAnalyteRequest(afp, "ng/mL")
                )
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertTrue(snapshot.siteAssignments.isEmpty())
    }

    @Test
    fun `重复分析物请求会被拒绝`() = runTest {
        val outcome = coordinator.create(
            request(
                analytes = listOf(
                    DirectProjectAnalyteRequest(cea, "ng/mL"),
                    DirectProjectAnalyteRequest(cea, "pg/mL")
                )
            )
        )

        assertTrue(outcome is DirectProjectCreationOutcome.InvalidRequest)
        assertEquals(null, repository.savedProject)
    }

    @Test
    fun `九十六孔板快照保持八乘十二几何`() = runTest {
        val outcome = coordinator.create(
            request(
                carrierPreset = DirectCarrierPreset.PLATE_96,
                analytes = listOf(DirectProjectAnalyteRequest(cea, "ng/mL"))
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertEquals(8, snapshot.carrierProfile.rows)
        assertEquals(12, snapshot.carrierProfile.columns)
        assertEquals("PLATE", snapshot.carrierProfile.carrierType)
    }

    @Test
    fun `圆形自定义阵列冻结圆形掩膜但仍进入PGGrid路由`() = runTest {
        val outcome = coordinator.create(
            request(
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_CUSTOM,
                customRows = 6,
                customColumns = 9,
                customSiteShape = SiteShape.CIRCLE,
                analytes = listOf(DirectProjectAnalyteRequest(cea, "ng/mL"))
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertEquals(6, snapshot.carrierProfile.rows)
        assertEquals(9, snapshot.carrierProfile.columns)
        assertEquals(SiteShape.CIRCLE.code, snapshot.carrierProfile.siteShape)
        assertEquals(CarrierType.MICROFLUIDIC_CHIP.code, snapshot.carrierProfile.carrierType)
        assertEquals(
            GridCarrierRoute.MICROFLUIDIC_PG_GRID,
            GridDetectionRouteResolver.resolve(
                requireNotNull(CarrierType.fromCode(snapshot.carrierProfile.carrierType))
            )
        )
    }

    @Test
    fun `自定义阵列拒绝没有采样契约的位点形状`() = runTest {
        val outcome = coordinator.create(
            request(
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_CUSTOM,
                customRows = 6,
                customColumns = 9,
                customSiteShape = SiteShape.POINT,
                analytes = listOf(DirectProjectAnalyteRequest(cea, "ng/mL"))
            )
        )

        assertTrue(outcome is DirectProjectCreationOutcome.InvalidRequest)
    }

    @Test
    fun `光谱项目同样保存全部分析物关联`() = runTest {
        val outcome = coordinator.create(
            request(
                modality = DetectionModality.SPECTRUM,
                analytes = listOf(
                    DirectProjectAnalyteRequest(cea, "ng/mL"),
                    DirectProjectAnalyteRequest(afp, "pg/mL")
                )
            )
        ) as DirectProjectCreationOutcome.Created

        assertEquals(ProjectDetectionDestination.SPECTRUM_SINGLE, outcome.destination)
        assertEquals(SpectrumLightSource.HALOGEN.name, outcome.project.lightSource)
        assertEquals(2, repository.savedJoins.size)
    }

    @Test
    fun `光谱项目拒绝缺少人工确认的光源`() = runTest {
        val outcome = coordinator.create(
            request(
                modality = DetectionModality.SPECTRUM,
                spectrumLightSource = null,
                analytes = listOf(DirectProjectAnalyteRequest(cea, "ng/mL"))
            )
        )

        assertTrue(outcome is DirectProjectCreationOutcome.InvalidRequest)
    }

    @Test
    fun `多分析物荧光项目分别冻结各自发射通道与同单位参考截断值并隔离曲线版本`() = runTest {
        val ceaWithPreset = Analyte(
            id = "cea",
            name = "CEA",
            defaultUnit = "ng/mL",
            defaultMaxConcentration = 100.0,
            defaultFluorescenceChannel = "RED",
            referenceCutoff = 5.0
        )
        val afpWithPreset = Analyte(
            id = "afp",
            name = "AFP",
            defaultUnit = "ng/mL",
            defaultMaxConcentration = 400.0,
            defaultFluorescenceChannel = "BLUE",
            referenceCutoff = 20.0
        )

        val outcome = coordinator.create(
            request(
                modality = DetectionModality.FLUORESCENCE,
                analytes = listOf(
                    DirectProjectAnalyteRequest(
                        analyte = ceaWithPreset,
                        concentrationUnit = "ng/mL",
                        maxConcentration = 100.0,
                        fluorescenceChannel = com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel.RED
                    ),
                    // 故意把 AFP 单位改为 pg/mL，验证单位不一致时不误冻结 ng/mL 的参考阈值
                    DirectProjectAnalyteRequest(
                        analyte = afpWithPreset,
                        concentrationUnit = "pg/mL",
                        maxConcentration = 2000.0,
                        fluorescenceChannel = com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel.BLUE
                    )
                )
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        val ceaSnapshot = snapshot.analytes[0]
        val afpSnapshot = snapshot.analytes[1]
        assertEquals(
            com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel.RED,
            com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                ceaSnapshot.templateConfig.displayConfigJson
            )
        )
        assertEquals(
            com.muc.fluocolorquant.domain.detection.photometry.FluorescenceChannel.BLUE,
            com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec.decodeFluorescenceChannel(
                afpSnapshot.templateConfig.displayConfigJson
            )
        )
        assertEquals("v1-ch-RED", ceaSnapshot.analysisModel.model.processorVersion)
        assertEquals("v1-ch-BLUE", afpSnapshot.analysisModel.model.processorVersion)
        assertEquals(
            5.0,
            requireNotNull(
                com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec.decodeReferenceCutoff(
                    ceaSnapshot.templateConfig.displayConfigJson,
                    "ng/mL"
                )
            ),
            1e-9
        )
        assertEquals(
            null,
            com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec.decodeReferenceCutoff(
                afpSnapshot.templateConfig.displayConfigJson,
                "pg/mL"
            )
        )
    }

    @Test
    fun `关联试剂批次信息在比色与荧光新建时均写入外键并冻结溯源摘要`() = runTest {
        val cea = Analyte(id = "cea", name = "CEA", defaultUnit = "ng/mL")
        val outcome = coordinator.create(
            request(
                modality = DetectionModality.COLORIMETRIC,
                analytes = listOf(
                    DirectProjectAnalyteRequest(
                        analyte = cea,
                        concentrationUnit = "ng/mL",
                        maxConcentration = 100.0,
                        reagentAntibodyId = "reagent_cea_ab_1",
                        reagentSummary = "Anti-CEA mAb (Abcam · Lot:2026A)"
                    )
                )
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        val config = snapshot.analytes.single().templateConfig
        assertEquals("reagent_cea_ab_1", config.reagentAntibodyId)
        assertEquals(
            "Anti-CEA mAb (Abcam · Lot:2026A)",
            com.muc.fluocolorquant.domain.detection.ScientificDetectionConfigCodec.decodeReagentSummary(
                config.displayConfigJson
            )
        )
    }

    private fun request(
        modality: DetectionModality = DetectionModality.FLUORESCENCE,
        carrierPreset: DirectCarrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
        customRows: Int? = null,
        customColumns: Int? = null,
        customSiteShape: SiteShape? = null,
        spectrumLightSource: SpectrumLightSource? = SpectrumLightSource.HALOGEN,
        analytes: List<DirectProjectAnalyteRequest>
    ) = DirectProjectCreateRequest(
        name = "多分析物项目",
        detectionModality = modality,
        carrierPreset = carrierPreset,
        customRows = customRows,
        customColumns = customColumns,
        customSiteShape = customSiteShape,
        spectrumLightSource = spectrumLightSource,
        analytes = analytes,
        imageUri = "content://chip/1",
        userId = "1"
    )

    private class FakeProjectRepository : ProjectRepository {
        var savedProject: Project? = null
        var savedJoins: List<ProjectAnalyteJoin> = emptyList()

        override suspend fun getAllProjects(): List<Project> = listOfNotNull(savedProject)

        override suspend fun getProjectById(projectId: String): Project? =
            savedProject?.takeIf { it.id == projectId }

        override suspend fun createProject(project: Project) {
            savedProject = project
        }

        override suspend fun createProjectWithAnalytes(
            project: Project,
            joins: List<ProjectAnalyteJoin>
        ) {
            savedProject = project
            savedJoins = joins
        }

        override suspend fun updateProject(project: Project) {
            savedProject = project
        }

        override suspend fun deleteProject(projectId: String) {
            if (savedProject?.id == projectId) savedProject = null
        }

        override suspend fun updateSpectrumConfig(
            projectId: String,
            lightSource: String?,
            spectrumColumnCount: Int,
            spectrumColumnMappingJson: String?
        ) = Unit
    }
}
