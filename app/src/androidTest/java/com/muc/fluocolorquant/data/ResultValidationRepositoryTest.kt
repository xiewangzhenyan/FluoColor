package com.muc.fluocolorquant.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ResultValidationRepositoryImpl
import com.muc.fluocolorquant.domain.result.validation.ResultValidationPoint
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 验证记录的真实Room追加修订与历史重开测试。 */
@RunWith(AndroidJUnit4::class)
class ResultValidationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ResultValidationRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repository = ResultValidationRepositoryImpl(database)
        val project = Project(
            id = "validation-project",
            name = "预测精度验证",
            detectionMode = "COLORIMETRIC",
            recognitionType = "AUTO",
            imageUri = "content://validation/input.png",
            rows = 8,
            columns = 12,
            createTime = Date(1_000L),
            userId = "operator",
            lastRunTimestamp = Date(2_000L),
            analysisMethod = "CURVE_FIT"
        )
        database.projectDao().insertProject(project)
        database.detectionRunDao().insertDetectionRun(
            DetectionRun(
                runId = "validation-run",
                projectId = project.id,
                timestamp = Date(2_000L),
                detectionModelUsed = "plate96-yolo-circle",
                concentrationModelUsed = "curve",
                status = "Completed",
                errorMessage = null,
                confThreshold = 0.25f,
                iouThreshold = 0.45f,
                wellsDetected = 96
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun save_appendsRevisionAndLatestCanBeReloaded() = runBlocking {
        val first = repository.save(
            runId = "validation-run",
            analyteId = "cea",
            concentrationUnit = "ng/mL",
            points = listOf(point(0, 1.0, 1.1), point(1, 2.0, 2.2))
        )
        val second = repository.save(
            runId = "validation-run",
            analyteId = "cea",
            concentrationUnit = "ng/mL",
            points = listOf(point(0, 1.0, 1.0), point(1, 2.0, 2.0))
        )

        assertEquals(1, first.revision)
        assertEquals(2, second.revision)
        val reopened = requireNotNull(repository.getLatestByRun("validation-run")["cea"])
        assertEquals(2, reopened.revision)
        assertEquals(1.0, reopened.regression.rSquared ?: Double.NaN, 0.0)
        assertEquals(2, database.resultValidationDao().getByRun("validation-run").size)
    }

    private fun point(index: Int, predicted: Double, reference: Double) = ResultValidationPoint(
        siteIndex = index,
        siteLabel = "A${index + 1}",
        predictedValue = predicted,
        referenceValue = reference
    )
}
