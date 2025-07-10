package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ExperimentTemplateDao
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import kotlinx.coroutines.flow.Flow
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
} 