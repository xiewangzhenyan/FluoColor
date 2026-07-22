package com.muc.fluocolorquant.ui.viewmodels

import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.model.CurveModel
import com.muc.fluocolorquant.data.repository.CurveModelRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurveModelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `自动拟合会计算函数参数并按用户选择的信号特征保存`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val repository = FakeCurveModelRepository()
            val viewModel = CurveModelViewModel(repository)
            advanceUntilIdle()
            viewModel.startManualDataInput()
            viewModel.updateDataPixelType(PixelType.RED)

            viewModel.performFitFromPoints(
                listOf(0.0 to 10.0, 1.0 to 20.0, 2.0 to 30.0, 3.0 to 40.0)
            )
            advanceUntilIdle()

            val state = viewModel.activeCreationFlow.value as CreationFlowState.ManualDataInput
            assertNotNull(state.fittingResult)
            assertEquals(FittingFunction.LINEAR, state.fittingResult?.function)

            viewModel.saveModel("CEA 自动曲线")
            advanceUntilIdle()

            assertEquals(1, repository.saved.size)
            assertEquals(PixelType.RED, repository.saved.single().pixelType)
            assertEquals(FittingFunction.LINEAR, repository.saved.single().function)
        }

    @Test
    fun `用户指定函数时只执行该函数而不要求输入参数`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val viewModel = CurveModelViewModel(FakeCurveModelRepository())
            advanceUntilIdle()
            viewModel.startManualDataInput()
            viewModel.updateDataFittingFunction(FittingFunction.QUADRATIC)

            viewModel.performFitFromPoints(
                listOf(0.0 to 1.0, 1.0 to 4.0, 2.0 to 9.0, 3.0 to 16.0)
            )
            advanceUntilIdle()

            val state = viewModel.activeCreationFlow.value as CreationFlowState.ManualDataInput
            assertEquals(FittingFunction.QUADRATIC, state.fittingResult?.function)
            assertNotNull(state.fittingResult?.params)
        }

    private class FakeCurveModelRepository : CurveModelRepository {
        val models = MutableStateFlow<List<CurveModel>>(emptyList())
        val saved = mutableListOf<CurveModel>()

        override fun getAllCurveModels(): Flow<List<CurveModel>> = models
        override suspend fun getCurveModelById(id: String) = models.value.find { it.id == id }
        override suspend fun saveCurveModel(curveModel: CurveModel) {
            saved += curveModel
            models.value = models.value.filterNot { it.id == curveModel.id } + curveModel
        }
        override suspend fun updateCurveModel(curveModel: CurveModel) = saveCurveModel(curveModel)
        override suspend fun deleteCurveModel(curveModel: CurveModel) {
            models.value = models.value.filterNot { it.id == curveModel.id }
        }
        override suspend fun deleteCurveModelById(id: String) {
            models.value = models.value.filterNot { it.id == id }
        }
        override suspend fun exportCurveModel(curveModel: CurveModel, filePath: String) = true
        override suspend fun importCurveModelFromFile(filePath: String): CurveModel? = null
        override suspend fun importDataPointsFromExcel(filePath: String): List<List<Double>>? = null
    }
}
