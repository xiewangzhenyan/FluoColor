package com.muc.fluocolorquant.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.muc.fluocolorquant.data.model.ExperimentTemplate
import com.muc.fluocolorquant.data.model.TemplateAnalyteConfig
import com.muc.fluocolorquant.data.model.TemplateSiteAssignment
import kotlinx.coroutines.flow.Flow
import java.util.Date

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

    /** 新模板创建使用严格插入，禁止相同 ID 静默覆盖历史科学配置。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplateStrict(template: ExperimentTemplate)
    
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

    /** 获取模板中全部分析物配置。 */
    @Query("SELECT * FROM template_analyte_configs WHERE templateId = :templateId ORDER BY displayOrder, id")
    suspend fun getAnalyteConfigs(templateId: String): List<TemplateAnalyteConfig>

    /** 获取模板中全部位点分配。 */
    @Query("SELECT * FROM template_site_assignments WHERE templateId = :templateId ORDER BY rowIndex, columnIndex")
    suspend fun getSiteAssignments(templateId: String): List<TemplateSiteAssignment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalyteConfigs(configs: List<TemplateAnalyteConfig>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSiteAssignments(assignments: List<TemplateSiteAssignment>)

    @Query("DELETE FROM template_analyte_configs WHERE templateId = :templateId")
    suspend fun deleteAnalyteConfigs(templateId: String)

    @Query("DELETE FROM template_site_assignments WHERE templateId = :templateId")
    suspend fun deleteSiteAssignments(templateId: String)

    @Query("SELECT MAX(version) FROM experiment_templates WHERE templateName = :name")
    suspend fun getLatestVersionByName(name: String): Int?

    /** 生命周期变更只更新状态时间，不触碰模板科学参数和子表。 */
    @Query(
        """
        UPDATE experiment_templates
        SET status = :status, publishedAt = :publishedAt, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateLifecycle(
        id: String,
        status: String,
        publishedAt: Date?,
        updatedAt: Date
    )

    @Query(
        """
        UPDATE experiment_templates
        SET status = 'ARCHIVED', updatedAt = :updatedAt
        WHERE templateName = :name
          AND status = 'PUBLISHED'
          AND id != :exceptId
        """
    )
    suspend fun archivePublishedSiblings(name: String, exceptId: String, updatedAt: Date)

    /**
     * 原子替换模板子配置，避免用户保存过程中只写入一半布局。
     */
    @Transaction
    suspend fun replaceTemplateChildren(
        templateId: String,
        analyteConfigs: List<TemplateAnalyteConfig>,
        siteAssignments: List<TemplateSiteAssignment>
    ) {
        deleteSiteAssignments(templateId)
        deleteAnalyteConfigs(templateId)
        if (analyteConfigs.isNotEmpty()) upsertAnalyteConfigs(analyteConfigs)
        if (siteAssignments.isNotEmpty()) upsertSiteAssignments(siteAssignments)
    }

    /** 原子创建模板主档、多分析物定义和全阵列位点配置。 */
    @Transaction
    suspend fun insertBundle(
        template: ExperimentTemplate,
        analyteConfigs: List<TemplateAnalyteConfig>,
        siteAssignments: List<TemplateSiteAssignment>
    ) {
        insertTemplateStrict(template)
        if (analyteConfigs.isNotEmpty()) upsertAnalyteConfigs(analyteConfigs)
        if (siteAssignments.isNotEmpty()) upsertSiteAssignments(siteAssignments)
    }

    /** 草稿更新在同一事务中替换主档和全部子项，避免半张芯片布局。 */
    @Transaction
    suspend fun replaceDraftBundle(
        template: ExperimentTemplate,
        analyteConfigs: List<TemplateAnalyteConfig>,
        siteAssignments: List<TemplateSiteAssignment>
    ) {
        updateTemplate(template)
        replaceTemplateChildren(template.id, analyteConfigs, siteAssignments)
    }

    /** 发布新版本时才归档同名旧发布版本。 */
    @Transaction
    suspend fun publishVersion(id: String, name: String, publishedAt: Date) {
        archivePublishedSiblings(name = name, exceptId = id, updatedAt = publishedAt)
        updateLifecycle(
            id = id,
            status = "PUBLISHED",
            publishedAt = publishedAt,
            updatedAt = publishedAt
        )
    }
}
