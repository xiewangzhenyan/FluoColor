package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AnalysisModelDao
import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.DeepLearningModelDefinition
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Date

/**
 * 分析模型仓库的版本与发布事务测试。
 *
 * Fake DAO 保存真实实体并执行与 Room DAO 相同的状态变化，测试 Repository 自身而不是
 * Mock 调用次数，确保历史发布版本在新草稿编辑期间仍然可用。
 */
class AnalysisModelRepositoryTest {

    @Test
    fun `创建下一版本时旧发布版本保持发布直到新版本发布`() = runBlocking {
        val dao = FakeAnalysisModelDao()
        val repository = AnalysisModelRepositoryImpl(dao)
        val v1 = repository.createDraft(standardCurveBundle(name = "CEA 比色模型"))

        repository.publish(v1.model.id)
        val v2 = repository.createNextDraft(v1.model.id)

        assertEquals(
            AnalysisModelLifecycleStatus.PUBLISHED.code,
            dao.getById(v1.model.id)?.status
        )
        assertEquals(AnalysisModelLifecycleStatus.DRAFT.code, dao.getById(v2.model.id)?.status)
        assertEquals(2, v2.model.version)
        assertNotEquals(v1.model.id, v2.model.id)

        repository.publish(v2.model.id)

        assertEquals(
            AnalysisModelLifecycleStatus.ARCHIVED.code,
            dao.getById(v1.model.id)?.status
        )
        assertEquals(
            AnalysisModelLifecycleStatus.PUBLISHED.code,
            dao.getById(v2.model.id)?.status
        )
    }

    @Test
    fun `创建新版本时标准曲线定义和重复标定点使用新模型ID`() = runBlocking {
        val dao = FakeAnalysisModelDao()
        val repository = AnalysisModelRepositoryImpl(dao)
        val v1 = repository.createDraft(standardCurveBundle(name = "NSE 荧光曲线"))

        val v2 = repository.createNextDraft(v1.model.id)
        val loaded = repository.getBundle(v2.model.id)

        assertNotNull(loaded)
        assertEquals(v2.model.id, loaded?.standardCurve?.analysisModelId)
        assertEquals(2, loaded?.calibrationPoints?.size)
        assertEquals(
            setOf(v2.model.id),
            loaded?.calibrationPoints?.map { it.analysisModelId }?.toSet()
        )
    }

    @Test
    fun `已发布模型不能通过草稿更新接口原地覆盖`() {
        runBlocking {
            val dao = FakeAnalysisModelDao()
            val repository = AnalysisModelRepositoryImpl(dao)
            val draft = repository.createDraft(standardCurveBundle(name = "CEA 模型"))
            repository.publish(draft.model.id)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    repository.updateDraft(
                        draft.copy(model = draft.model.copy(processorVersion = "2.0.0"))
                    )
                }
            }
        }
    }

    private fun standardCurveBundle(name: String): AnalysisModelBundle {
        val model = AnalysisModel(
            id = "temporary-id",
            name = name,
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = "analyte-cea",
            detectionMode = DetectionModality.COLORIMETRIC.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.DELTA_E_2000.code,
            processorName = "ColorimetricProcessor",
            processorVersion = "1.0.0",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            compatibleAcquisitionProfileIdsJson = "[\"device-v1\"]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0
        )
        return AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = model.id,
                fittingFunction = "linear",
                parametersJson = "{\"a\":1.0,\"b\":0.0}",
                monotonicDirection = "INCREASING"
            ),
            calibrationPoints = listOf(
                CalibrationPoint(
                    id = "point-1",
                    analysisModelId = model.id,
                    concentration = 1.0,
                    signalValue = 2.0,
                    repeatIndex = 1
                ),
                CalibrationPoint(
                    id = "point-2",
                    analysisModelId = model.id,
                    concentration = 10.0,
                    signalValue = 20.0,
                    repeatIndex = 1
                )
            )
        )
    }

    /** 内存 Fake DAO，复用 DAO 默认事务方法来验证与 Room 一致的状态编排。 */
    private class FakeAnalysisModelDao : AnalysisModelDao {
        private val models = MutableStateFlow<List<AnalysisModel>>(emptyList())
        private val curves = mutableMapOf<String, StandardCurveDefinition>()
        private val deepModels = mutableMapOf<String, DeepLearningModelDefinition>()
        private val points = mutableMapOf<String, MutableList<CalibrationPoint>>()

        override fun observeAll(): Flow<List<AnalysisModel>> = models

        override suspend fun getById(id: String): AnalysisModel? =
            models.value.find { it.id == id }

        override suspend fun findCompatibleCandidates(
            analyteId: String,
            detectionMode: String,
            inputProtocol: String
        ): List<AnalysisModel> = models.value.filter {
            it.analyteId == analyteId &&
                it.detectionMode == detectionMode &&
                it.inputProtocol == inputProtocol &&
                it.status == AnalysisModelLifecycleStatus.PUBLISHED.code
        }

        override suspend fun insert(model: AnalysisModel) {
            check(models.value.none { it.id == model.id })
            models.value = models.value + model
        }

        override suspend fun update(model: AnalysisModel) {
            models.value = models.value.map { current ->
                if (current.id == model.id) model else current
            }
        }

        override suspend fun upsertStandardCurve(definition: StandardCurveDefinition) {
            curves[definition.analysisModelId] = definition
        }

        override suspend fun upsertDeepLearningDefinition(
            definition: DeepLearningModelDefinition
        ) {
            deepModels[definition.analysisModelId] = definition
        }

        override suspend fun insertCalibrationPoints(points: List<CalibrationPoint>) {
            points.groupBy(CalibrationPoint::analysisModelId).forEach { (modelId, values) ->
                this.points.getOrPut(modelId) { mutableListOf() }.addAll(values)
            }
        }

        override suspend fun getCalibrationPoints(analysisModelId: String): List<CalibrationPoint> =
            points[analysisModelId].orEmpty()

        override suspend fun getStandardCurveDefinition(
            analysisModelId: String
        ): StandardCurveDefinition? = curves[analysisModelId]

        override suspend fun getDeepLearningDefinition(
            analysisModelId: String
        ): DeepLearningModelDefinition? = deepModels[analysisModelId]

        override suspend fun deleteStandardCurveDefinition(analysisModelId: String) {
            curves.remove(analysisModelId)
        }

        override suspend fun deleteDeepLearningDefinition(analysisModelId: String) {
            deepModels.remove(analysisModelId)
        }

        override suspend fun deleteCalibrationPoints(analysisModelId: String) {
            points.remove(analysisModelId)
        }

        override suspend fun getLatestVersionByName(name: String): Int? =
            models.value.filter { it.name == name }.maxOfOrNull(AnalysisModel::version)

        override suspend fun updateStatus(id: String, status: String, updatedAt: Date) {
            models.value = models.value.map { current ->
                if (current.id == id) current.copy(status = status, updatedAt = updatedAt) else current
            }
        }

        override suspend fun archivePublishedSiblings(
            name: String,
            exceptId: String,
            updatedAt: Date
        ) {
            models.value = models.value.map { current ->
                if (current.name == name && current.id != exceptId &&
                    current.status == AnalysisModelLifecycleStatus.PUBLISHED.code
                ) {
                    current.copy(
                        status = AnalysisModelLifecycleStatus.ARCHIVED.code,
                        updatedAt = updatedAt
                    )
                } else {
                    current
                }
            }
        }
    }
}
