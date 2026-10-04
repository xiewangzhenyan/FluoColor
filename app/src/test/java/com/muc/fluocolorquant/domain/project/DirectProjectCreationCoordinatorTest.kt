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
