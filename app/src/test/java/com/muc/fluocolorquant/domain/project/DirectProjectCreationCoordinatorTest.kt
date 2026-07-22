package com.muc.fluocolorquant.domain.project

import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.repository.ProjectRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无模板直接创建必须生成完整快照，同时保持定量结果为明确的仅信号状态。 */
class DirectProjectCreationCoordinatorTest {
    private val repository = FakeProjectRepository()
    private val coordinator = DirectProjectCreationCoordinator(repository)
    private val analyte = Analyte(id = "cea", name = "CEA")

    @Test
    fun `十乘十荧光项目无需模板和模型记录即可创建`() = runTest {
        val outcome = coordinator.create(
            DirectProjectCreateRequest(
                name = "CEA 10×10",
                detectionModality = DetectionModality.FLUORESCENCE,
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
                analyte = analyte,
                imageUri = "content://chip/1",
                userId = "1",
                concentrationUnit = "ng/mL",
                sampleId = "sample-001"
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertEquals(ProjectDetectionDestination.GRID_ENDPOINT, outcome.destination)
        assertEquals(10, outcome.project.rows)
        assertEquals(10, outcome.project.columns)
        assertEquals("SIGNAL_ONLY", outcome.project.analysisMethod)
        assertEquals(100, snapshot.siteAssignments.size)
        assertEquals(
            100,
            snapshot.siteAssignments.count { it.roleType == TemplateSiteRole.SAMPLE.code }
        )
        assertEquals("{}", snapshot.analytes.single().analysisModel.standardCurve?.parametersJson)
        assertEquals(outcome.project.id, repository.savedProject?.id)
        assertEquals("sample-001", snapshot.siteAssignments.first().defaultSampleSlot)
    }

    @Test
    fun `微流控比色只使用用户明确指定的参考位`() = runTest {
        val outcome = coordinator.create(
            DirectProjectCreateRequest(
                name = "CEA 比色",
                detectionModality = DetectionModality.COLORIMETRIC,
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
                analyte = analyte,
                imageUri = "content://chip/2",
                userId = "1",
                concentrationUnit = "ng/mL",
                colorReferenceRow = 2,
                colorReferenceColumn = 3
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        val references = snapshot.siteAssignments.filter {
            it.roleType == TemplateSiteRole.REFERENCE.code
        }
        assertEquals(1, references.size)
        assertEquals(2, references.single().rowIndex)
        assertEquals(3, references.single().columnIndex)
    }

    @Test
    fun `微流控比色缺少参考位时拒绝创建而不是自动伪造`() = runTest {
        val outcome = coordinator.create(
            DirectProjectCreateRequest(
                name = "CEA 比色",
                detectionModality = DetectionModality.COLORIMETRIC,
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
                analyte = analyte,
                imageUri = "content://chip/2",
                userId = "1",
                concentrationUnit = "ng/mL"
            )
        )

        assertTrue(outcome is DirectProjectCreationOutcome.InvalidRequest)
        assertNull(repository.savedProject)
    }

    @Test
    fun `九十六孔板快照保持旧孔板路由`() = runTest {
        val outcome = coordinator.create(
            DirectProjectCreateRequest(
                name = "96 孔板",
                detectionModality = DetectionModality.FLUORESCENCE,
                carrierPreset = DirectCarrierPreset.PLATE_96,
                analyte = analyte,
                imageUri = "content://plate/1",
                userId = "1",
                concentrationUnit = "ng/mL"
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        assertEquals(8, snapshot.carrierProfile.rows)
        assertEquals(12, snapshot.carrierProfile.columns)
        assertEquals("PLATE", snapshot.carrierProfile.carrierType)
    }

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
