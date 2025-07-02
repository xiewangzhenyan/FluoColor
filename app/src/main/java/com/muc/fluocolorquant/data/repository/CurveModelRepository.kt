package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.model.CurveModel
import kotlinx.coroutines.flow.Flow

/**
 * 曲线模型仓库接口
 * 定义曲线模型相关的数据操作
 */
interface CurveModelRepository {
    /**
     * 获取所有曲线模型
     * @return 曲线模型列表流
     */
    fun getAllCurveModels(): Flow<List<CurveModel>>
    
    /**
     * 根据ID获取曲线模型
     * @param id 曲线模型ID
     * @return 曲线模型实体，如果不存在则返回null
     */
    suspend fun getCurveModelById(id: String): CurveModel?
    
    /**
     * 保存曲线模型（新增或更新）
     */
    suspend fun saveCurveModel(curveModel: CurveModel)
    
    /**
     * 更新曲线模型
     */
    suspend fun updateCurveModel(curveModel: CurveModel)
    
    /**
     * 删除曲线模型
     */
    suspend fun deleteCurveModel(curveModel: CurveModel)
    
    /**
     * 根据ID删除曲线模型
     */
    suspend fun deleteCurveModelById(id: String)
    
    /**
     * 导出曲线模型到文件
     */
    suspend fun exportCurveModel(curveModel: CurveModel, filePath: String): Boolean
    
    /**
     * 从文件导入曲线模型
     */
    suspend fun importCurveModelFromFile(filePath: String): CurveModel?
    
    /**
     * 从Excel文件导入数据点
     */
    suspend fun importDataPointsFromExcel(filePath: String): List<List<Double>>?
} 