package com.muc.fluocolorquant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.model.CaptureArtifact
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectRepositoryImpl
import com.muc.fluocolorquant.data.storage.ProjectFileCleaner
import java.io.File
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 项目级联删除与应用私有证据清理的真实 Room/Android 文件系统契约测试。 */
@RunWith(AndroidJUnit4::class)
class ProjectDeletionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        testRoot = File(context.filesDir, "project-deletion-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
    }

    @After
    fun tearDown() {
        database.close()
        testRoot.deleteRecursively()
    }

    @Test
    fun `删除项目会级联删库清理私有证据但保留另一项目共享图片`() = runBlocking {
        val sharedImage = File(testRoot, "shared.jpg").apply { writeText("shared") }
        val runId = "run-${UUID.randomUUID()}"
        val runInput = File(context.filesDir, "run_inputs/$runId/input.jpg").apply {
            parentFile?.mkdirs()
            writeText("input")
        }
        val firstProject = project("project-delete", sharedImage.path)
        val secondProject = project("project-keep", sharedImage.path)
        database.projectDao().insertProject(firstProject)
        database.projectDao().insertProject(secondProject)
        database.detectionRunDao().insertDetectionRun(
            DetectionRun(
                runId = runId,
                projectId = firstProject.id,
                timestamp = Date(),
                detectionModelUsed = "test",
                concentrationModelUsed = null,
                status = "Completed",
                errorMessage = null,
                confThreshold = null,
                iouThreshold = null,
                wellsDetected = 1
            )
        )
        database.captureArtifactDao().insert(
            CaptureArtifact(
                runId = runId,
                captureRole = "ENDPOINT",
                originalPath = runInput.path,
                capturedAt = Date()
            )
        )
        val repository = ProjectRepositoryImpl(
            projectDao = database.projectDao(),
            projectFileCleaner = ProjectFileCleaner(context)
        )

        repository.deleteProject(firstProject.id)

        assertNull(database.projectDao().getProjectById(firstProject.id))
        assertNull(database.detectionRunDao().getDetectionRunById(runId))
        assertTrue(database.captureArtifactDao().getByRun(runId).isEmpty())
        assertFalse(runInput.exists())
        // 第二个项目仍引用共享图，文件保护集合必须阻止误删。
        assertNotNull(database.projectDao().getProjectById(secondProject.id))
        assertTrue(sharedImage.exists())
    }

    private fun project(id: String, imagePath: String) = Project(
        id = id,
        name = id,
        detectionMode = "COLORIMETRIC",
        recognitionType = "AUTO",
        imageUri = imagePath,
        rows = 2,
        columns = 2,
        createTime = Date(),
        userId = "1",
        lastRunTimestamp = null,
        analysisMethod = "TEMPLATE_MANAGED"
    )
}
