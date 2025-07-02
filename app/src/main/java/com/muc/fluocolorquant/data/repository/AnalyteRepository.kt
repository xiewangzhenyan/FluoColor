package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.AnalyteDao
import com.muc.fluocolorquant.data.model.Analyte
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分析物仓库接口
 * 定义分析物相关的数据操作
 */
interface AnalyteRepository {
    /**
     * 获取所有分析物
     * @return 分析物列表流
     */
    fun getAllAnalytes(): Flow<List<Analyte>>
    
    /**
     * 根据ID获取分析物
     * @param id 分析物ID
     * @return 分析物实体，如果不存在则返回null
     */
    suspend fun getAnalyteById(id: String): Analyte?
    
    /**
     * 根据名称获取分析物
     * @param name 分析物名称
     * @return 分析物实体，如果不存在则返回null
     */
    suspend fun getAnalyteByName(name: String): Analyte?
    
    /**
     * 添加分析物
     * @param name 分析物名称
     * @return 如果添加成功返回true，否则返回false
     */
    suspend fun addAnalyte(name: String): Boolean
    
    /**
     * 更新分析物
     * @param id 分析物ID
     * @param name 新的分析物名称
     * @return 如果更新成功返回true，否则返回false
     */
    suspend fun updateAnalyte(id: String, name: String): Boolean
    
    /**
     * 删除分析物
     * @param analyte 要删除的分析物实体
     */
    suspend fun deleteAnalyte(analyte: Analyte)
    
    /**
     * 删除分析物
     * @param id 要删除的分析物ID
     */
    suspend fun deleteAnalyte(id: String)
}

/**
 * 分析物仓库实现类
 * @param analyteDao 分析物数据访问对象
 */
@Singleton
class AnalyteRepositoryImpl @Inject constructor(
    private val analyteDao: AnalyteDao
) : AnalyteRepository {
    
    override fun getAllAnalytes(): Flow<List<Analyte>> {
        return analyteDao.getAllAnalytes()
    }
    
    override suspend fun getAnalyteById(id: String): Analyte? {
        return analyteDao.getAnalyteById(id)
    }
    
    override suspend fun getAnalyteByName(name: String): Analyte? {
        return analyteDao.getAnalyteByName(name)
    }
    
    override suspend fun addAnalyte(name: String): Boolean {
        val trimmedName = name.trim()
        
        // 检查名称是否为空
        if (trimmedName.isEmpty()) {
            return false
        }
        
        // 检查是否已存在相同名称的分析物
        val existingAnalyte = getAnalyteByName(trimmedName)
        if (existingAnalyte != null) {
            return false
        }
        
        // 创建新的分析物实体并保存
        val analyte = Analyte(
            id = "analyte_" + UUID.randomUUID().toString(),
            name = trimmedName
        )
        
        analyteDao.insertAnalyte(analyte)
        return true
    }
    
    override suspend fun updateAnalyte(id: String, name: String): Boolean {
        val trimmedName = name.trim()
        
        // 检查名称是否为空
        if (trimmedName.isEmpty()) {
            return false
        }
        
        // 检查是否已存在相同名称的分析物（排除当前ID）
        val existingAnalyte = getAnalyteByName(trimmedName)
        if (existingAnalyte != null && existingAnalyte.id != id) {
            return false
        }
        
        // 获取要更新的分析物
        val analyte = getAnalyteById(id) ?: return false
        
        // 更新分析物名称
        val updatedAnalyte = analyte.copy(name = trimmedName)
        analyteDao.updateAnalyte(updatedAnalyte)
        return true
    }
    
    override suspend fun deleteAnalyte(analyte: Analyte) {
        analyteDao.deleteAnalyte(analyte)
    }
    
    override suspend fun deleteAnalyte(id: String) {
        val analyte = getAnalyteById(id) ?: return
        deleteAnalyte(analyte)
    }
} 