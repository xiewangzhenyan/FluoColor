package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.ExperimentTemplate
import kotlinx.coroutines.flow.Flow

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
     * 根据分析物ID获取相关的实验模板
     * @param analyteId 分析物ID
     * @return 实验模板列表流
     */
    fun getTemplatesByAnalyteId(analyteId: String): Flow<List<ExperimentTemplate>>
    
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
} 