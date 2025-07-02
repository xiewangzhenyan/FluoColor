package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.PlateLayoutDao
import com.muc.fluocolorquant.data.model.PlateLayout
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 孔板布局仓库接口
 * 定义孔板布局相关的数据操作
 */
interface PlateLayoutRepository {
    /**
     * 根据项目ID获取孔板布局
     * @param projectId 项目ID
     * @return 孔板布局列表流
     */
    fun getPlateLayoutsByProjectId(projectId: String): Flow<List<PlateLayout>>
    
    /**
     * 根据ID获取孔板布局
     * @param id 孔板布局ID
     * @return 孔板布局实体，如果不存在则返回null
     */
    suspend fun getPlateLayoutById(id: Long): PlateLayout?
    
    /**
     * 根据项目ID和孔位索引获取孔板布局
     * @param projectId 项目ID
     * @param wellIndex 孔位索引
     * @return 孔板布局实体，如果不存在则返回null
     */
    suspend fun getPlateLayoutByWellIndex(projectId: String, wellIndex: Int): PlateLayout?
    
    /**
     * 根据项目ID和分析物ID获取孔板布局
     * @param projectId 项目ID
     * @param analyteId 分析物ID
     * @return 孔板布局列表流
     */
    fun getPlateLayoutsByAnalyteId(projectId: String, analyteId: String): Flow<List<PlateLayout>>
    
    /**
     * 根据项目ID和角色类型获取孔板布局
     * @param projectId 项目ID
     * @param roleType 角色类型
     * @return 孔板布局列表流
     */
    fun getPlateLayoutsByRoleType(projectId: String, roleType: String): Flow<List<PlateLayout>>
    
    /**
     * 添加孔板布局
     * @param plateLayout 孔板布局实体
     * @return 添加成功返回新记录的ID，失败返回-1
     */
    suspend fun addPlateLayout(plateLayout: PlateLayout): Long
    
    /**
     * 批量添加孔板布局
     * @param plateLayouts 孔板布局实体列表
     * @return 添加成功返回新记录的ID列表
     */
    suspend fun addPlateLayouts(plateLayouts: List<PlateLayout>): List<Long>
    
    /**
     * 创建新的孔板布局
     * @param projectId 项目ID
     * @param wellIndex 孔位索引
     * @param analyteId 分析物ID
     * @param roleType 角色类型
     * @return 创建的孔板布局实体
     */
    suspend fun createPlateLayout(
        projectId: String,
        wellIndex: Int,
        analyteId: String?,
        roleType: String
    ): PlateLayout
    
    /**
     * 更新孔板布局
     * @param plateLayout 孔板布局实体
     */
    suspend fun updatePlateLayout(plateLayout: PlateLayout)
    
    /**
     * 删除孔板布局
     * @param plateLayout 孔板布局实体
     */
    suspend fun deletePlateLayout(plateLayout: PlateLayout)
    
    /**
     * 删除项目的所有孔板布局
     * @param projectId 项目ID
     */
    suspend fun deleteAllLayoutsForProject(projectId: String)
}

/**
 * 孔板布局仓库实现类
 * @param plateLayoutDao 孔板布局数据访问对象
 */
@Singleton
class PlateLayoutRepositoryImpl @Inject constructor(
    private val plateLayoutDao: PlateLayoutDao
) : PlateLayoutRepository {
    
    override fun getPlateLayoutsByProjectId(projectId: String): Flow<List<PlateLayout>> {
        return plateLayoutDao.getPlateLayoutsByProjectId(projectId)
    }
    
    override suspend fun getPlateLayoutById(id: Long): PlateLayout? {
        return plateLayoutDao.getPlateLayoutById(id)
    }
    
    override suspend fun getPlateLayoutByWellIndex(projectId: String, wellIndex: Int): PlateLayout? {
        return plateLayoutDao.getPlateLayoutByWellIndex(projectId, wellIndex)
    }
    
    override fun getPlateLayoutsByAnalyteId(projectId: String, analyteId: String): Flow<List<PlateLayout>> {
        return plateLayoutDao.getPlateLayoutsByAnalyteId(projectId, analyteId)
    }
    
    override fun getPlateLayoutsByRoleType(projectId: String, roleType: String): Flow<List<PlateLayout>> {
        return plateLayoutDao.getPlateLayoutsByRoleType(projectId, roleType)
    }
    
    override suspend fun addPlateLayout(plateLayout: PlateLayout): Long {
        return plateLayoutDao.insertPlateLayout(plateLayout)
    }
    
    override suspend fun addPlateLayouts(plateLayouts: List<PlateLayout>): List<Long> {
        return plateLayoutDao.insertPlateLayouts(plateLayouts)
    }
    
    override suspend fun createPlateLayout(
        projectId: String,
        wellIndex: Int,
        analyteId: String?,
        roleType: String
    ): PlateLayout {
        val plateLayout = PlateLayout(
            projectId = projectId,
            wellIndex = wellIndex,
            analyteId = analyteId,
            roleType = roleType
        )
        
        val id = plateLayoutDao.insertPlateLayout(plateLayout)
        return plateLayout.copy(id = id)
    }
    
    override suspend fun updatePlateLayout(plateLayout: PlateLayout) {
        plateLayoutDao.updatePlateLayout(plateLayout)
    }
    
    override suspend fun deletePlateLayout(plateLayout: PlateLayout) {
        plateLayoutDao.deletePlateLayout(plateLayout)
    }
    
    override suspend fun deleteAllLayoutsForProject(projectId: String) {
        plateLayoutDao.deleteAllLayoutsForProject(projectId)
    }
} 