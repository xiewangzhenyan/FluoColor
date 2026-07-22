package com.muc.fluocolorquant.domain.project

import com.muc.fluocolorquant.data.enums.AnalysisModelLifecycleStatus
import com.muc.fluocolorquant.data.enums.AnalysisModelType
import com.muc.fluocolorquant.data.enums.AnalysisPrimaryFeature
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.AnalysisModel
import com.muc.fluocolorquant.data.model.Analyte
import com.muc.fluocolorquant.data.model.CalibrationPoint
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.ProjectAnalyteJoin
import com.muc.fluocolorquant.data.model.StandardCurveDefinition
import com.muc.fluocolorquant.data.repository.AnalysisModelBundle
import com.muc.fluocolorquant.data.repository.AnalysisModelRepository
import com.muc.fluocolorquant.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无模板直接创建必须生成完整快照，同时保持定量结果为明确的仅信号状态。 */
class DirectProjectCreationCoordinatorTest {
    private val repository = FakeProjectRepository()
    private val modelRepository = FakeAnalysisModelRepository()
    private val coordinator = DirectProjectCreationCoordinator(repository, modelRepository)
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

    @Test
    fun `选择兼容标准曲线时项目冻结真实模型并启用曲线定量`() = runTest {
        val bundle = compatibleCurveBundle()
        modelRepository.bundles[bundle.model.id] = bundle
        modelRepository.models.value = listOf(bundle.model)

        val outcome = coordinator.create(
            DirectProjectCreateRequest(
                name = "CEA 定量项目",
                detectionModality = DetectionModality.FLUORESCENCE,
                carrierPreset = DirectCarrierPreset.MICROFLUIDIC_10_X_10,
                analyte = analyte,
                imageUri = "content://chip/curve",
                userId = "1",
                concentrationUnit = "ng/mL",
                analysisModelId = bundle.model.id
            )
        ) as DirectProjectCreationOutcome.Created

        val snapshot = TemplateProjectSnapshotCodec.decode(
            requireNotNull(outcome.project.templateSnapshotJson)
        )
        val frozen = snapshot.analytes.single()
        assertEquals("CURVE_FIT", outcome.project.analysisMethod)
        assertEquals(bundle.model.id, frozen.templateConfig.analysisModelId)
        assertEquals(bundle.model.id, frozen.analysisModel.model.id)
        assertEquals(0.1, frozen.templateConfig.reliableRangeMin)
        assertEquals(100.0, frozen.templateConfig.reliableRangeMax)
        assertEquals(3, frozen.analysisModel.calibrationPoints.size)
    }

    private fun compatibleCurveBundle(): AnalysisModelBundle {
        val model = AnalysisModel(
            id = "curve-cea-fluorescence",
            name = "CEA 荧光标准曲线",
            modelType = AnalysisModelType.STANDARD_CURVE.code,
            analyteId = analyte.id,
            detectionMode = DetectionModality.FLUORESCENCE.code,
            inputProtocol = InputProtocol.ENDPOINT_ONLY.code,
            primaryFeature = AnalysisPrimaryFeature.NET_FLUORESCENCE_INTENSITY.code,
            processorName = "fluorescence-photometry",
            processorVersion = "v1",
            compatibleCarrierTypesJson = "[\"MICROFLUIDIC_CHIP\"]",
            // 空数组表示由直接拍摄流程自动记录手机与曝光元数据。
            compatibleAcquisitionProfileIdsJson = "[]",
            concentrationUnit = "ng/mL",
            reliableRangeMin = 0.1,
            reliableRangeMax = 100.0,
            status = AnalysisModelLifecycleStatus.PUBLISHED.code
        )
        return AnalysisModelBundle(
            model = model,
            standardCurve = StandardCurveDefinition(
                analysisModelId = model.id,
                fittingFunction = "linear",
                parametersJson = "{\"a\":2.0,\"b\":1.0}",
                monotonicDirection = "INCREASING"
            ),
            calibrationPoints = listOf(0.1, 10.0, 100.0).mapIndexed { index, concentration ->
                CalibrationPoint(
                    id = "point-$index",
                    analysisModelId = model.id,
                    concentration = concentration,
                    signalValue = concentration * 2.0 + 1.0,
                    repeatIndex = 0
                )
            }
        )
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

    private class FakeAnalysisModelRepository : AnalysisModelRepository {
        val models = MutableStateFlow<List<AnalysisModel>>(emptyList())
        val bundles = mutableMapOf<String, AnalysisModelBundle>()

        override fun observeAll(): Flow<List<AnalysisModel>> = models
        override suspend fun getBundle(id: String): AnalysisModelBundle? = bundles[id]
        override suspend fun createDraft(bundle: AnalysisModelBundle): AnalysisModelBundle = bundle
        override suspend fun updateDraft(bundle: AnalysisModelBundle) = Unit
        override suspend fun replace(bundle: AnalysisModelBundle) {
            bundles[bundle.model.id] = bundle
        }
        override suspend fun delete(id: String) {
            bundles.remove(id)
            models.value = models.value.filterNot { it.id == id }
        }
        override suspend fun createNextDraft(previousId: String): AnalysisModelBundle =
            requireNotNull(bundles[previousId])
        override suspend fun publish(id: String) = Unit
        override suspend fun archive(id: String) = Unit
    }
}
