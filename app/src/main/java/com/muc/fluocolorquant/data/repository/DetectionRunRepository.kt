package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.WellResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 检测运行仓库
 * 提供对检测运行和孔位结果的数据访问
 */
@Singleton
class DetectionRunRepository @Inject constructor(
    private val detectionRunDao: DetectionRunDao,
    private val wellResultDao: WellResultDao
) {
    /**
     * 获取指定项目的所有检测运行
     */
    suspend fun getDetectionRunsByProjectId(projectId: String): List<DetectionRun> {
        return detectionRunDao.getDetectionRunsByProjectId(projectId)
    }
    
    /**
     * 获取指定ID的检测运行
     */
    suspend fun getDetectionRunById(detectionRunId: String): DetectionRun? {
        return detectionRunDao.getDetectionRunById(detectionRunId)
    }
    
    /**
     * 创建新的检测运行
     */
    suspend fun createDetectionRun(detectionRun: DetectionRun) {
        detectionRunDao.insertDetectionRun(detectionRun)
    }
    
    /**
     * 更新检测运行
     */
    suspend fun updateDetectionRun(detectionRun: DetectionRun) {
        detectionRunDao.updateDetectionRun(detectionRun)
    }
    
    /**
     * 删除检测运行
     */
    suspend fun deleteDetectionRun(detectionRun: DetectionRun) {
        detectionRunDao.deleteDetectionRun(detectionRun.runId)
    }
    
    /**
     * 获取指定检测运行的所有孔位结果
     */
    suspend fun getWellResultsByDetectionRunId(detectionRunId: String): List<WellResult> {
        return wellResultDao.getWellResultsByRunId(detectionRunId)
    }
    
    /**
     * 获取指定项目的所有孔位结果
     */
    suspend fun getWellResultsByProjectId(projectId: String): List<WellResult> {
        return wellResultDao.getWellResultsByProjectId(projectId)
    }
    
    /**
     * 获取指定ID的孔位结果
     */
    suspend fun getWellResultById(wellResultId: Long): WellResult? {
        return wellResultDao.getWellResultById(wellResultId)
    }
    
    /**
     * 创建新的孔位结果
     */
    suspend fun createWellResult(wellResult: WellResult): Long {
        return wellResultDao.insertWellResult(wellResult)
    }
    
    /**
     * 批量创建孔位结果
     */
    suspend fun createWellResults(wellResults: List<WellResult>) {
        wellResultDao.insertWellResults(wellResults)
    }
    
    /**
     * 更新孔位结果
     */
    suspend fun updateWellResult(wellResult: WellResult) {
        wellResultDao.updateWellResult(wellResult)
    }
    
    /**
     * 批量更新孔位结果
     */
    suspend fun updateWellResults(wellResults: List<WellResult>) {
        wellResultDao.updateWellResults(wellResults)
    }
    
    /**
     * 删除孔位结果
     */
    suspend fun deleteWellResult(wellResult: WellResult) {
        wellResultDao.deleteWellResult(wellResult.resultId)
    }
    
    /**
     * 批量删除孔位结果
     */
    suspend fun deleteWellResults(wellResults: List<WellResult>) {
        wellResults.forEach { wellResult ->
            wellResultDao.deleteWellResult(wellResult.resultId)
        }
    }
} 