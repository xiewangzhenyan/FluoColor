package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import kotlinx.coroutines.flow.Flow

/** 模板主档与多分析物、通用阵列位点配置的原子数据包。 */
data class ExperimentTemplateBundle(
    val template: ExperimentTemplate,
    val analyteConfigs: List<TemplateAnalyteConfig> = emptyList(),
    val siteAssignments: List<TemplateSiteAssignment> = emptyList()
)

/**
 * 实验模板仓库接口
 * 定义实验模板相关的数据操作
 */
interface ExperimentTemplateRepository {
    /**
     * 获取所有实验模板
     * @return 实验模板列表流
     */
    fun getAllTemplates(): Flow<List<ExperimentTemplate>>
    
    /**
     * 根据ID获取实验模板
     * @param id 实验模板ID
     * @return 实验模板实体，如果不存在则返回null
     */
    suspend fun getTemplateById(id: String): ExperimentTemplate?
    
    /**
     * 根据分析物ID获取实验模板
     * @param analyteId 分析物ID
     * @return 实验模板列表
     */
    suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate>
    
    /**
     * 保存实验模板（新增或更新）
     * @param template 要保存的实验模板
     */
    suspend fun saveTemplate(template: ExperimentTemplate)
    
    /**
     * 更新实验模板
     * @param template 要更新的实验模板
     */
    suspend fun updateTemplate(template: ExperimentTemplate)
    
    /**
     * 删除实验模板
     * @param template 要删除的实验模板
     */
    suspend fun deleteTemplate(template: ExperimentTemplate)
    
    /**
     * 根据ID删除实验模板
     * @param id 实验模板ID
     */
    suspend fun deleteTemplateById(id: String)

    /** 以下接口供新模板向导使用，明确区分草稿、发布版本和归档。 */
    suspend fun getBundle(id: String): ExperimentTemplateBundle?
    suspend fun createDraft(bundle: ExperimentTemplateBundle): ExperimentTemplateBundle
    suspend fun updateDraft(bundle: ExperimentTemplateBundle)
    suspend fun createNextDraft(previousId: String): ExperimentTemplateBundle
    suspend fun publish(id: String)
    suspend fun archive(id: String)
}
