package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.ProjectFileReference
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date

/**
 * 项目仓库原子创建测试。
 *
 * 新模板项目必须一次性保存主档和全部分析物关联；如果中途失败，Room 事务会整体回滚，
 * 不能遗留一个没有分析物配置的“半项目”。
 */
class ProjectRepositoryTest {

    @Test
    fun `模板项目通过单个事务入口保存项目和全部分析物关联`() = runTest {
        val dao = RecordingProjectDao()
        val repository = ProjectRepositoryImpl(dao)
        val project = project()
        val joins = listOf(join(project.id, "cea"), join(project.id, "nse"))

        repository.createProjectWithAnalytes(project, joins)

        assertEquals(listOf(project), dao.strictProjects)
        assertEquals(listOf("cea", "nse"), dao.strictJoins.map { it.analyteId })
        assertEquals(1, dao.atomicInsertCalls)
    }

    @Test
    fun `分析物关联不属于当前项目时事务在写入前失败`() = runTest {
        val dao = RecordingProjectDao()
        val repository = ProjectRepositoryImpl(dao)
        val project = project()

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.createProjectWithAnalytes(
                    project,
                    listOf(join("another-project", "cea"))
                )
            }
        }
        assertEquals(0, dao.strictProjects.size)
        assertEquals(0, dao.strictJoins.size)
    }

    private fun project() = Project(
        id = "project-1",
        name = "CEA 10×10 项目",
        detectionMode = "COLORIMETRIC",
        recognitionType = "AUTO",
        imageUri = "content://chip/1",
        rows = 10,
        columns = 10,
        createTime = Date(1_753_000_000_000L),
        userId = "1",
        lastRunTimestamp = null,
        analysisMethod = "TEMPLATE_MANAGED",
        templateId = "template-v1",
        templateVersion = 1,
        templateSnapshotJson = "{}",
        overrideJson = "{}"
    )

    private fun join(projectId: String, analyteId: String) = ProjectAnalyteJoin(
        projectId = projectId,
        analyteId = analyteId,
        maxConcentration = 100.0,
        concentrationUnit = "ng/mL",
        fkTemplateId = "template-v1"
    )

    /** 记录严格插入调用的 Fake DAO，其他旧 CRUD 只提供最小空实现。 */
    private class RecordingProjectDao : ProjectDao {
        val strictProjects = mutableListOf<Project>()
        val strictJoins = mutableListOf<ProjectAnalyteJoin>()
        var atomicInsertCalls: Int = 0

        override suspend fun getAllProjects(): List<Project> = emptyList()
        override suspend fun getLatestProject(): Project? = null
        override suspend fun getProjectById(projectId: String): Project? = null
        override suspend fun getProjectsByUserId(userId: String): List<Project> = emptyList()
        override suspend fun insertProject(project: Project) = Unit
        override suspend fun updateProject(project: Project) = Unit
        override suspend fun getFileReferencesForProject(projectId: String): List<ProjectFileReference> = emptyList()
        override suspend fun getAllPersistedFileReferences(): List<ProjectFileReference> = emptyList()
        override suspend fun updateSpectrumConfig(
            projectId: String,
            lightSource: String?,
            spectrumColumnCount: Int,
            spectrumColumnMappingJson: String?
        ) = Unit
        override suspend fun deleteProject(projectId: String) = Unit

        override suspend fun insertProjectStrict(project: Project) {
            strictProjects += project
        }

        override suspend fun insertProjectAnalytesStrict(joins: List<ProjectAnalyteJoin>) {
            strictJoins += joins
        }

        override suspend fun insertProjectWithAnalytes(
            project: Project,
            joins: List<ProjectAnalyteJoin>
        ) {
            atomicInsertCalls += 1
            require(joins.all { it.projectId == project.id }) {
                "项目分析物关联必须属于同一项目"
            }
            insertProjectStrict(project)
            if (joins.isNotEmpty()) insertProjectAnalytesStrict(joins)
        }
    }
}
