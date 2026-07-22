package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.enums.TemplateLifecycleStatus
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实验模板仓库实现类
 */
@Singleton
class ExperimentTemplateRepositoryImpl @Inject constructor(
    private val experimentTemplateDao: ExperimentTemplateDao
) : ExperimentTemplateRepository {

    /**
     * 获取所有实验模板
     */
    override fun getAllTemplates(): Flow<List<ExperimentTemplate>> {
        return experimentTemplateDao.getAllTemplates()
    }

    /**
     * 获取指定分析物的所有实验模板
     */
    override suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate> {
        return experimentTemplateDao.getTemplatesByAnalyteId(analyteId)
    }

    /**
     * 根据ID获取实验模板
     */
    override suspend fun getTemplateById(id: String): ExperimentTemplate? {
        return experimentTemplateDao.getTemplateById(id)
    }

    /**
     * 创建或更新实验模板
     */
    override suspend fun saveTemplate(template: ExperimentTemplate) {
        experimentTemplateDao.insertTemplate(template)
    }

    /**
     * 更新实验模板
     */
    override suspend fun updateTemplate(template: ExperimentTemplate) {
        experimentTemplateDao.updateTemplate(template)
    }

    /**
     * 删除实验模板
     */
    override suspend fun deleteTemplate(template: ExperimentTemplate) {
        experimentTemplateDao.deleteTemplate(template)
    }

    /**
     * 根据ID删除实验模板
     */
    override suspend fun deleteTemplateById(id: String) {
        experimentTemplateDao.deleteTemplateById(id)
    }

    override suspend fun getBundle(id: String): ExperimentTemplateBundle? {
        val template = experimentTemplateDao.getTemplateById(id) ?: return null
        return ExperimentTemplateBundle(
            template = template,
            analyteConfigs = experimentTemplateDao.getAnalyteConfigs(id),
            siteAssignments = experimentTemplateDao.getSiteAssignments(id)
        )
    }

    override suspend fun createDraft(
        bundle: ExperimentTemplateBundle
    ): ExperimentTemplateBundle {
        val normalizedName = bundle.template.templateName.trim()
        require(normalizedName.isNotEmpty()) { "实验模板名称不能为空" }
        val now = Date()
        val template = bundle.template.copy(
            id = UUID.randomUUID().toString(),
            templateName = normalizedName,
            version = (experimentTemplateDao.getLatestVersionByName(normalizedName) ?: 0) + 1,
            status = TemplateLifecycleStatus.DRAFT.code,
            createdAt = now,
            updatedAt = now,
            publishedAt = null
        )
        val normalized = bundle.withTemplateIdentity(template, regenerateChildIds = true)
        experimentTemplateDao.insertBundle(
            template = normalized.template,
            analyteConfigs = normalized.analyteConfigs,
            siteAssignments = normalized.siteAssignments
        )
        return normalized
    }

    override suspend fun updateDraft(bundle: ExperimentTemplateBundle) {
        val current = experimentTemplateDao.getTemplateById(bundle.template.id)
            ?: throw IllegalArgumentException("实验模板不存在")
        check(current.status != TemplateLifecycleStatus.ARCHIVED.code) {
            "已删除或归档的实验模板不能继续编辑"
        }
        val template = bundle.template.copy(
            id = current.id,
            version = current.version,
            status = current.status,
            createdAt = current.createdAt,
            updatedAt = Date(),
            publishedAt = null
        )
        val normalized = bundle.withTemplateIdentity(template, regenerateChildIds = false)
        experimentTemplateDao.replaceDraftBundle(
            template = normalized.template,
            analyteConfigs = normalized.analyteConfigs,
            siteAssignments = normalized.siteAssignments
        )
    }

    override suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle {
        val previous = getBundle(previousId)
            ?: throw IllegalArgumentException("源实验模板不存在")
        return createDraft(previous)
    }

    override suspend fun publish(id: String) {
        val template = experimentTemplateDao.getTemplateById(id)
            ?: throw IllegalArgumentException("实验模板不存在")
        if (template.status == TemplateLifecycleStatus.PUBLISHED.code) return
        check(template.status == TemplateLifecycleStatus.DRAFT.code) {
            "只有草稿模板可以发布"
        }
        experimentTemplateDao.publishVersion(
            id = template.id,
            name = template.templateName,
            publishedAt = Date()
        )
    }

    override suspend fun archive(id: String) {
        val template = experimentTemplateDao.getTemplateById(id)
            ?: throw IllegalArgumentException("实验模板不存在")
        if (template.status == TemplateLifecycleStatus.ARCHIVED.code) return
        experimentTemplateDao.updateLifecycle(
            id = template.id,
            status = TemplateLifecycleStatus.ARCHIVED.code,
            publishedAt = template.publishedAt,
            updatedAt = Date()
        )
    }

    /**
     * 创建新版本时为每个子项生成新主键；更新同一草稿时保留主键，便于审计编辑过程。
     */
    private fun ExperimentTemplateBundle.withTemplateIdentity(
        template: ExperimentTemplate,
        regenerateChildIds: Boolean
    ): ExperimentTemplateBundle {
        return ExperimentTemplateBundle(
            template = template,
            analyteConfigs = analyteConfigs.map { config ->
                config.copy(
                    id = if (regenerateChildIds) UUID.randomUUID().toString() else config.id,
                    templateId = template.id
                )
            },
            siteAssignments = siteAssignments.map { assignment ->
                assignment.copy(
                    id = if (regenerateChildIds) UUID.randomUUID().toString() else assignment.id,
                    templateId = template.id
                )
            }
        )
    }
}
