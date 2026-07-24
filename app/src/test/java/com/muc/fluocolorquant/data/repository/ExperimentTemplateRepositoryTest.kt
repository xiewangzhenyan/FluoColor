package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.enums.DetectionModality
import com.muc.fluocolorquant.data.enums.InputProtocol
import com.muc.fluocolorquant.data.enums.ReadoutLayout
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.enums.TemplateSiteRole
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import com.muc.fluocolorquant.data.model.TemplateQuantitationBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * 版本化实验模板仓库测试。
 *
 * Fake DAO 保存真实主档和子表实体，并复用 DAO 默认事务函数，重点验证科学模板发布时
 * 不会覆盖正在被项目使用的旧版本。
 */
class ExperimentTemplateRepositoryTest {

    @Test
    fun `创建草稿会统一主档子表ID并原子保存`() = runBlocking {
        val dao = FakeExperimentTemplateDao()
        val repository = ExperimentTemplateRepositoryImpl(dao)

        val saved = repository.createDraft(templateBundle("双分析物芯片模板"))

        assertNotEquals("temporary-template", saved.template.id)
        assertEquals(1, saved.template.version)
        assertEquals(TemplateLifecycleStatus.DRAFT.code, saved.template.status)
        assertEquals(
            setOf(saved.template.id),
            saved.analyteConfigs.map { it.templateId }.toSet()
        )
        assertEquals(
            setOf(saved.template.id),
            saved.siteAssignments.map { it.templateId }.toSet()
        )
        assertEquals(
            setOf(saved.template.id),
            saved.quantitationBindings.map { it.templateId }.toSet()
        )
        assertEquals(2, repository.getBundle(saved.template.id)?.analyteConfigs?.size)
        assertEquals(4, repository.getBundle(saved.template.id)?.siteAssignments?.size)
        assertEquals(2, repository.getBundle(saved.template.id)?.quantitationBindings?.size)
    }

    @Test
    fun `创建下一版本时旧发布版本保持发布直到新版本发布`() = runBlocking {
        val dao = FakeExperimentTemplateDao()
        val repository = ExperimentTemplateRepositoryImpl(dao)
        val v1 = repository.createDraft(templateBundle("10×10 肿瘤标志物芯片"))
        repository.publish(v1.template.id)

        val v2 = repository.createNextDraft(v1.template.id)

        assertEquals(
            TemplateLifecycleStatus.PUBLISHED.code,
            dao.getTemplateById(v1.template.id)?.status
        )
        assertEquals(TemplateLifecycleStatus.DRAFT.code, v2.template.status)
        assertEquals(2, v2.template.version)

        repository.publish(v2.template.id)

        assertEquals(
            TemplateLifecycleStatus.ARCHIVED.code,
            dao.getTemplateById(v1.template.id)?.status
        )
        assertEquals(
            TemplateLifecycleStatus.PUBLISHED.code,
            dao.getTemplateById(v2.template.id)?.status
        )
    }

    @Test
    fun `已发布模板允许直接编辑且保持原版本和发布状态`() {
        runBlocking {
            val dao = FakeExperimentTemplateDao()
            val repository = ExperimentTemplateRepositoryImpl(dao)
            val draft = repository.createDraft(templateBundle("CEA 芯片模板"))
            repository.publish(draft.template.id)

            repository.updateDraft(
                draft.copy(template = draft.template.copy(templateName = "CEA 芯片模板（已编辑）"))
            )

            val updated = dao.getTemplateById(draft.template.id)
            assertEquals("CEA 芯片模板（已编辑）", updated?.templateName)
            assertEquals(1, updated?.version)
            assertEquals(TemplateLifecycleStatus.PUBLISHED.code, updated?.status)
        }
    }

    private fun templateBundle(name: String): ExperimentTemplateBundle {
        val templateId = "temporary-template"
        return ExperimentTemplateBundle(
            template = ExperimentTemplate(
                id = templateId,
                templateName = name,
                analyteId = null,
                reagentAntigenId = null,
                reagentAntibodyId = null,
                fkCurveModelId = null,
                reliableRangeMin = 0.0,
                reliableRangeMax = 0.0,
                concentrationUnit = "",
                defaultLayoutJson = null,
                carrierProfileId = "carrier-10x10",
                detectionMode = DetectionModality.COLORIMETRIC.code,
                readoutLayout = ReadoutLayout.GRID_SITES.code,
                acquisitionProfileId = "device-v1",
                inputProtocol = InputProtocol.ENDPOINT_ONLY.code
            ),
            analyteConfigs = listOf(
                TemplateAnalyteConfig(
                    id = "config-cea",
                    templateId = templateId,
                    analyteId = "cea",
                    analysisModelId = "model-cea",
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.1,
                    reliableRangeMax = 100.0,
                    displayOrder = 0
                ),
                TemplateAnalyteConfig(
                    id = "config-nse",
                    templateId = templateId,
                    analyteId = "nse",
                    analysisModelId = "model-nse",
                    concentrationUnit = "ng/mL",
                    reliableRangeMin = 0.1,
                    reliableRangeMax = 200.0,
                    displayOrder = 1
                )
            ),
            siteAssignments = listOf(
                site(templateId, 0, 0, "cea", TemplateSiteRole.SAMPLE),
                site(templateId, 0, 1, "cea", TemplateSiteRole.BLANK),
                site(templateId, 1, 0, "nse", TemplateSiteRole.SAMPLE),
                site(templateId, 1, 1, "nse", TemplateSiteRole.BLANK)
            ),
            quantitationBindings = listOf(
                binding(templateId, "cea"),
                binding(templateId, "nse")
            )
        )
    }

    private fun binding(templateId: String, analyteId: String) =
        TemplateQuantitationBinding(
            id = "binding-$analyteId",
            templateId = templateId,
            analyteId = analyteId,
            method = "SIGNAL_ONLY",
            resourceSnapshotJson = "{}",
            contentFingerprint = "fingerprint-$analyteId",
            processorName = "test",
            processorVersion = "1"
        )

    private fun site(
        templateId: String,
        row: Int,
        column: Int,
        analyteId: String,
        role: TemplateSiteRole
    ) = TemplateSiteAssignment(
        id = "site-$row-$column",
        templateId = templateId,
        rowIndex = row,
        columnIndex = column,
        analyteId = analyteId,
        roleType = role.code
    )

    /** 内存 Fake DAO，子表按模板 ID 分组保存。 */
    private class FakeExperimentTemplateDao : ExperimentTemplateDao {
        private val templates = MutableStateFlow<List<ExperimentTemplate>>(emptyList())
        private val analyteConfigs = mutableMapOf<String, MutableList<TemplateAnalyteConfig>>()
        private val siteAssignments = mutableMapOf<String, MutableList<TemplateSiteAssignment>>()
        private val quantitationBindings =
            mutableMapOf<String, MutableList<TemplateQuantitationBinding>>()

        override fun getAllTemplates(): Flow<List<ExperimentTemplate>> = templates

        override suspend fun getTemplateById(id: String): ExperimentTemplate? =
            templates.value.find { it.id == id }

        override suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate> =
            templates.value.filter { it.analyteId == analyteId }

        override suspend fun insertTemplate(template: ExperimentTemplate) {
            templates.value = templates.value.filterNot { it.id == template.id } + template
        }

        override suspend fun insertTemplateStrict(template: ExperimentTemplate) {
            check(templates.value.none { it.id == template.id })
            templates.value = templates.value + template
        }

        override suspend fun updateTemplate(template: ExperimentTemplate) {
            templates.value = templates.value.map { if (it.id == template.id) template else it }
        }

        override suspend fun deleteTemplate(template: ExperimentTemplate) {
            deleteTemplateById(template.id)
        }

        override suspend fun deleteTemplateById(id: String) {
            templates.value = templates.value.filterNot { it.id == id }
            analyteConfigs.remove(id)
            siteAssignments.remove(id)
            quantitationBindings.remove(id)
        }

        override suspend fun getAnalyteConfigs(templateId: String): List<TemplateAnalyteConfig> =
            analyteConfigs[templateId].orEmpty()

        override suspend fun getSiteAssignments(templateId: String): List<TemplateSiteAssignment> =
            siteAssignments[templateId].orEmpty()

        override suspend fun getQuantitationBindings(
            templateId: String
        ): List<TemplateQuantitationBinding> = quantitationBindings[templateId].orEmpty()

        override suspend fun upsertAnalyteConfigs(configs: List<TemplateAnalyteConfig>) {
            configs.groupBy(TemplateAnalyteConfig::templateId).forEach { (templateId, values) ->
                analyteConfigs.getOrPut(templateId) { mutableListOf() }.apply {
                    removeAll { old -> values.any { it.id == old.id } }
                    addAll(values)
                }
            }
        }

        override suspend fun upsertSiteAssignments(assignments: List<TemplateSiteAssignment>) {
            assignments.groupBy(TemplateSiteAssignment::templateId).forEach { (templateId, values) ->
                siteAssignments.getOrPut(templateId) { mutableListOf() }.apply {
                    removeAll { old -> values.any { it.id == old.id } }
                    addAll(values)
                }
            }
        }

        override suspend fun upsertQuantitationBindings(
            bindings: List<TemplateQuantitationBinding>
        ) {
            bindings.groupBy(TemplateQuantitationBinding::templateId)
                .forEach { (templateId, values) ->
                    quantitationBindings.getOrPut(templateId) { mutableListOf() }.apply {
                        removeAll { old -> values.any { it.id == old.id } }
                        addAll(values)
                    }
                }
        }

        override suspend fun deleteAnalyteConfigs(templateId: String) {
            analyteConfigs.remove(templateId)
        }

        override suspend fun deleteSiteAssignments(templateId: String) {
            siteAssignments.remove(templateId)
        }

        override suspend fun deleteQuantitationBindings(templateId: String) {
            quantitationBindings.remove(templateId)
        }

        override suspend fun getLatestVersionByName(name: String): Int? =
            templates.value.filter { it.templateName == name }.maxOfOrNull(ExperimentTemplate::version)

        override suspend fun updateLifecycle(
            id: String,
            status: String,
            publishedAt: Date?,
            updatedAt: Date
        ) {
            templates.value = templates.value.map { template ->
                if (template.id == id) {
                    template.copy(status = status, publishedAt = publishedAt, updatedAt = updatedAt)
                } else {
                    template
                }
            }
        }

        override suspend fun archivePublishedSiblings(
            name: String,
            exceptId: String,
            updatedAt: Date
        ) {
            templates.value = templates.value.map { template ->
                if (template.templateName == name && template.id != exceptId &&
                    template.status == TemplateLifecycleStatus.PUBLISHED.code
                ) {
                    template.copy(
                        status = TemplateLifecycleStatus.ARCHIVED.code,
                        updatedAt = updatedAt
                    )
                } else {
                    template
                }
            }
        }
    }
}
