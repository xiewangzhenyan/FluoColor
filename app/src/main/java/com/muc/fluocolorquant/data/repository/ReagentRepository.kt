package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.ReagentDao
import com.muc.fluocolorquant.data.model.Reagent
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 试剂仓库接口
 * 定义试剂相关的数据操作
 */
interface ReagentRepository {
    /**
     * 获取所有试剂
     * @return 试剂列表流
     */
    fun getAllReagents(): Flow<List<Reagent>>
    
    /**
     * 根据分析物ID获取试剂
     * @param analyteId 分析物ID
     * @return 试剂列表流
     */
    fun getReagentsByAnalyteId(analyteId: String): Flow<List<Reagent>>
    
    /**
     * 根据ID获取试剂
     * @param id 试剂ID
     * @return 试剂实体，如果不存在则返回null
     */
    suspend fun getReagentById(id: String): Reagent?
    
    /**
     * 添加试剂
     * @param analyteId 分析物ID
     * @param reagentName 试剂名称
     * @param reagentType 试剂类型 ("antigen" 或 "antibody")
     * @param manufacturer 制造商（可选）
     * @param molecularWeight 分子量（可选）
     * @param unit 浓度单位（可选）
     * @return 如果添加成功返回true，否则返回false
     */
    suspend fun addReagent(
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String? = null,
        molecularWeight: Double? = null,
        unit: String? = null,
        lotNumber: String? = null
    ): Boolean

    suspend fun updateReagent(
        id: String,
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String? = null,
        molecularWeight: Double? = null,
        unit: String? = null,
        lotNumber: String? = null
    ): Boolean
    
    /**
     * 删除试剂
     * @param reagent 要删除的试剂实体
     */
    suspend fun deleteReagent(reagent: Reagent)
}

/**
 * 试剂仓库实现类
 * @param reagentDao 试剂数据访问对象
 */
@Singleton
class ReagentRepositoryImpl @Inject constructor(
    private val reagentDao: ReagentDao
) : ReagentRepository {
    
    override fun getAllReagents(): Flow<List<Reagent>> {
        return reagentDao.getAllReagents()
    }
    
    override fun getReagentsByAnalyteId(analyteId: String): Flow<List<Reagent>> {
        return reagentDao.getReagentsByAnalyteId(analyteId)
    }
    
    override suspend fun getReagentById(id: String): Reagent? {
        return reagentDao.getReagentById(id)
    }
    
    override suspend fun addReagent(
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String?,
        molecularWeight: Double?,
        unit: String?,
        lotNumber: String?
    ): Boolean {
        val trimmedName = reagentName.trim()
        if (trimmedName.isEmpty() || analyteId.isEmpty() || reagentType.isEmpty()) {
            return false
        }
        val reagent = Reagent(
            id = "reagent_" + UUID.randomUUID().toString(),
            analyteId = analyteId,
            reagentName = trimmedName,
            reagentType = reagentType,
            manufacturer = manufacturer?.trim()?.takeIf { it.isNotEmpty() },
            molecularWeight = molecularWeight,
            unit = unit?.trim()?.takeIf { it.isNotEmpty() },
            lotNumber = lotNumber?.trim()?.takeIf { it.isNotEmpty() }
        )
        reagentDao.insertReagent(reagent)
        return true
    }

    override suspend fun updateReagent(
        id: String,
        analyteId: String,
        reagentName: String,
        reagentType: String,
        manufacturer: String?,
        molecularWeight: Double?,
        unit: String?,
        lotNumber: String?
    ): Boolean {
        val trimmedName = reagentName.trim()
        if (trimmedName.isEmpty() || analyteId.isEmpty() || reagentType.isEmpty() || id.isEmpty()) {
            return false
        }
        val existingReagent = getReagentById(id) ?: return false
        val updatedReagent = existingReagent.copy(
            analyteId = analyteId,
            reagentName = trimmedName,
            reagentType = reagentType,
            manufacturer = manufacturer?.trim()?.takeIf { it.isNotEmpty() },
            molecularWeight = molecularWeight ?: existingReagent.molecularWeight,
            unit = unit?.trim()?.takeIf { it.isNotEmpty() } ?: existingReagent.unit,
            lotNumber = lotNumber?.trim()?.takeIf { it.isNotEmpty() }
        )
        reagentDao.updateReagent(updatedReagent)
        return true
    }
    
    override suspend fun deleteReagent(reagent: Reagent) {
        reagentDao.deleteReagent(reagent)
    }
} 