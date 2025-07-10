package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import kotlinx.coroutines.flow.Flow

/**
 * 实验模板数据访问对象
 */
@Dao
interface ExperimentTemplateDao {
    /**
     * 获取所有实验模板
     */
    @Query("SELECT * FROM experiment_templates ORDER BY updatedAt DESC")
    fun getAllTemplates(): Flow<List<ExperimentTemplate>>
    
    /**
     * 根据ID获取实验模板
     */
    @Query("SELECT * FROM experiment_templates WHERE id = :id")
    suspend fun getTemplateById(id: String): ExperimentTemplate?
    
    /**
     * 根据分析物ID获取实验模板
     */
    @Query("SELECT * FROM experiment_templates WHERE analyteId = :analyteId ORDER BY updatedAt DESC")
    suspend fun getTemplatesByAnalyteId(analyteId: String): List<ExperimentTemplate>
    
    /**
     * 插入实验模板，如果已存在则替换
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: ExperimentTemplate)
    
    /**
     * 更新实验模板
     */
    @Update
    suspend fun updateTemplate(template: ExperimentTemplate)
    
    /**
     * 删除实验模板
     */
    @Delete
    suspend fun deleteTemplate(template: ExperimentTemplate)
    
    /**
     * 根据ID删除实验模板
     */
    @Query("DELETE FROM experiment_templates WHERE id = :id")
    suspend fun deleteTemplateById(id: String)
} 