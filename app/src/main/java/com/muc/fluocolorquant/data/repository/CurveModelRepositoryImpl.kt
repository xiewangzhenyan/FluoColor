package com.muc.fluocolorquant.data.repository

import com.google.gson.Gson
import com.muc.fluocolorquant.data.dao.CurveModelDao
import com.muc.fluocolorquant.data.model.CurveModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 曲线模型仓库实现类
 */
@Singleton
class CurveModelRepositoryImpl @Inject constructor(
    private val curveModelDao: CurveModelDao
) : CurveModelRepository {
    
    private val gson = Gson()
    
    override fun getAllCurveModels(): Flow<List<CurveModel>> {
        return curveModelDao.getAllCurveModels()
    }
    
    override suspend fun getCurveModelById(id: String): CurveModel? {
        return curveModelDao.getCurveModelById(id)
    }
    
    override suspend fun saveCurveModel(curveModel: CurveModel) {
        val updatedModel = curveModel.copy(updatedAt = Date())
        curveModelDao.insertCurveModel(updatedModel)
    }
    
    override suspend fun updateCurveModel(curveModel: CurveModel) {
        val updatedModel = curveModel.copy(updatedAt = Date())
        curveModelDao.updateCurveModel(updatedModel)
    }
    
    override suspend fun deleteCurveModel(curveModel: CurveModel) {
        curveModelDao.deleteCurveModel(curveModel)
    }
    
    override suspend fun deleteCurveModelById(id: String) {
        curveModelDao.deleteCurveModelById(id)
    }
    
    override suspend fun exportCurveModel(curveModel: CurveModel, filePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(curveModel)
            val file = File(filePath)
            
            FileOutputStream(file).use { stream ->
                stream.write(json.toByteArray())
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    override suspend fun importCurveModelFromFile(filePath: String): CurveModel? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null
            
            val json = FileInputStream(file).bufferedReader().use { it.readText() }
            gson.fromJson(json, CurveModel::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    override suspend fun importDataPointsFromExcel(filePath: String): List<List<Double>>? = withContext(Dispatchers.IO) {
        try {
            // 这里应该实现从Excel读取数据的逻辑
            // 需要添加Excel读取库，如Apache POI
            // 简化实现，返回模拟数据
            listOf(
                listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0),  // 第一列数据（如X值）
                listOf(0.1, 0.8, 2.3, 3.5, 4.2, 5.1)   // 第二列数据（如Y值）
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
} 