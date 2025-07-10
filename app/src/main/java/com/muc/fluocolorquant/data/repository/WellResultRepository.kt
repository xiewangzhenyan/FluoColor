package com.muc.fluocolorquant.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.ui.viewmodels.WellDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 孔位结果仓库
 * 处理孔位结果相关的业务逻辑
 */
@Singleton
class WellResultRepository @Inject constructor(
    private val wellResultDao: WellResultDao,
    private val detectionRunDao: DetectionRunDao,
    private val projectDao: ProjectDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "WellResultRepository"
        private const val CONCENTRATION_MODEL_PATH = "models/improved_concentration_model_lite.ptl"
        private const val CROPPED_WELL_SIZE = 128 // 裁剪后的孔位图像大小
        
        // 96孔板的规格：12列 x 8行
        private const val PLATE_COLUMNS = 12
        private const val PLATE_ROWS = 8
        
        // 每个孔位之间的预期间距（以像素为单位，根据实际情况调整）
        private const val EXPECTED_GAP = 50f
        
        // 孔位分组的容错距离，小于该值的孔位被认为是在同一行/列
        private const val ROW_COLUMN_TOLERANCE = 30f
        
        // 并行处理的批大小 - 根据设备CPU核心数动态调整
        private val PARALLEL_BATCH_SIZE by lazy { 
            maxOf(2, minOf(8, Runtime.getRuntime().availableProcessors()))
        }
    }
    
    /**
     * 保存检测结果到数据库
     * @param projectId 项目ID
     * @param detections 检测到的孔位列表
     * @param confThreshold 置信度阈值
     * @param iouThreshold IoU阈值
     * @return 运行ID
     */
    suspend fun saveDetectionResults(
        projectId: String,
        detections: List<WellDetection>,
        confThreshold: Float,
        iouThreshold: Float
    ): String = withContext(Dispatchers.IO) {
        // 生成运行ID
        val runId = UUID.randomUUID().toString()
        
        try {
            // 创建检测运行记录
            val detectionRun = DetectionRun(
                runId = runId,
                projectId = projectId,
                timestamp = Date(),
                detectionModelUsed = "best_lite.ptl", // 当前使用的模型
                concentrationModelUsed = null, // 暂未使用浓度模型
                status = "Processing", // 初始状态为处理中
                errorMessage = null,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold,
                wellsDetected = detections.size
            )
            
            // 保存检测运行记录
            detectionRunDao.insertDetectionRun(detectionRun)
            
            // 对检测结果进行排序，确保从左到右、从上到下的顺序
            val sortedDetections = sortDetectionsInPlateOrder(detections)
            Log.d(TAG, "原始检测数量: ${detections.size}, 排序后: ${sortedDetections.size}")
            
            // 将检测结果转换为孔位结果列表
            val wellResults = sortedDetections.mapIndexed { index, detection ->
                WellResult(
                    runId = runId,
                    projectId = projectId,
                    wellIndex = index,
                    predictedConcentration = null, // 暂未预测浓度
                    trueConcentration = null,
                    isStandard = false,
                    detectedRectLeft = detection.rect.left,
                    detectedRectTop = detection.rect.top,
                    detectedRectRight = detection.rect.right,
                    detectedRectBottom = detection.rect.bottom,
                    detectionConfidence = detection.confidence,
                    croppedImageIdentifier = null,
                    roleType = "NONE" // 默认角色类型，未分配
                )
            }
            
            // 保存孔位结果
            wellResultDao.insertWellResults(wellResults)
            
            // 更新项目的最后运行时间
            val project = projectDao.getProjectById(projectId)
            project?.let {
                val updatedProject = it.copy(lastRunTimestamp = Date())
                projectDao.updateProject(updatedProject)
            }
            
            // 返回运行ID
            runId
        } catch (e: Exception) {
            Log.e(TAG, "保存检测结果失败", e)
            // 如果发生错误，创建失败状态的运行记录
            val errorRun = DetectionRun(
                runId = runId,
                projectId = projectId,
                timestamp = Date(),
                detectionModelUsed = "best_lite.ptl",
                concentrationModelUsed = null,
                status = "Failed",
                errorMessage = e.message,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold,
                wellsDetected = detections.size
            )
            detectionRunDao.insertDetectionRun(errorRun)
            
            runId
        }
    }
    
    /**
     * 对检测结果进行排序，确保从左到右、从上到下的96孔板布局顺序
     * 使用行列聚类和排序算法
     */
    private fun sortDetectionsInPlateOrder(detections: List<WellDetection>): List<WellDetection> {
        if (detections.size <= 1) return detections
        
        // 1. 提取所有孔位的中心点
        val centers = detections.map { detection ->
            val centerX = (detection.rect.left + detection.rect.right) / 2
            val centerY = (detection.rect.top + detection.rect.bottom) / 2
            Pair(centerX, centerY) to detection
        }
        
        try {
            // 2. 按Y坐标（纵向）进行聚类，识别行
            val rowClusters = clusterByCoordinate(centers, isRow = true)
            Log.d(TAG, "识别到 ${rowClusters.size} 行")
            
            // 3. 按行排序（从上到下）
            val sortedRows = rowClusters.sortedBy { row -> row.first().first.second }
            
            // 4. 对每一行内的点，按X坐标排序（从左到右）
            val sortedDetections = mutableListOf<WellDetection>()
            for (row in sortedRows) {
                // 按X坐标排序当前行
                val sortedRow = row.sortedBy { it.first.first }
                sortedRow.forEach { sortedDetections.add(it.second) }
            }
            
            // 5. 确保检测数量一致
            require(sortedDetections.size == detections.size) {
                "排序后的检测数量 (${sortedDetections.size}) 与原始检测数量 (${detections.size}) 不一致"
            }
            
            return sortedDetections
        } catch (e: Exception) {
            // 排序算法失败时，返回原始列表
            Log.e(TAG, "孔位排序失败: ${e.message}. 使用原始顺序", e)
            return detections
        }
    }
    
    /**
     * 根据坐标对点进行聚类
     * @param points 需要聚类的点集，包含中心坐标和对应的检测对象
     * @param isRow 是否按行聚类（true=行聚类，false=列聚类）
     * @return 聚类后的点集列表，每个子列表代表一行或一列
     */
    private fun clusterByCoordinate(
        points: List<Pair<Pair<Float, Float>, WellDetection>>,
        isRow: Boolean
    ): List<List<Pair<Pair<Float, Float>, WellDetection>>> {
        val clusters = mutableListOf<MutableList<Pair<Pair<Float, Float>, WellDetection>>>()
        
        // 遍历所有点
        for (point in points) {
            val coordinate = if (isRow) point.first.second else point.first.first
            
            // 尝试将点加入到现有簇
            var addedToCluster = false
            for (cluster in clusters) {
                if (cluster.isEmpty()) continue
                
                // 计算当前簇的平均坐标
                val avgCoord = cluster.map {
                    if (isRow) it.first.second else it.first.first
                }.average().toFloat()
                
                // 如果点与簇的平均坐标足够近，则加入该簇
                if (abs(coordinate - avgCoord) < ROW_COLUMN_TOLERANCE) {
                    cluster.add(point)
                    addedToCluster = true
                    break
                }
            }
            
            // 如果不属于任何现有簇，创建新簇
            if (!addedToCluster) {
                clusters.add(mutableListOf(point))
            }
        }
        
        // 预期行列数校验
        val expectedCount = if (isRow) PLATE_ROWS else PLATE_COLUMNS
        if (clusters.size != expectedCount) {
            Log.w(TAG, "警告: 检测到 ${clusters.size} ${if (isRow) "行" else "列"}, 预期 $expectedCount")
        }
        
        return clusters
    }
    
    /**
     * 根据检测结果裁剪孔位图像并预测浓度
     * @param runId 运行ID
     * @param originalBitmap 原始图像
     * @return 预测是否成功
     */
    suspend fun cropWellsAndPredictConcentration(
        runId: String,
        originalBitmap: Bitmap
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 获取检测运行记录
            val detectionRun = detectionRunDao.getDetectionRunById(runId)
            if (detectionRun == null) {
                Log.e(TAG, "找不到检测运行记录: $runId")
                return@withContext false
            }
            
            // 获取孔位结果
            val wellResults = wellResultDao.getWellResultsByRunId(runId)
            if (wellResults.isEmpty()) {
                Log.e(TAG, "找不到孔位结果: $runId")
                return@withContext false
            }
            
            // 更新检测运行状态
            detectionRunDao.updateDetectionRun(
                detectionRun.copy(
                    status = "Processing", 
                    concentrationModelUsed = CONCENTRATION_MODEL_PATH
                )
            )
            
            // 加载浓度预测模型
            val modelPath = assetFilePath(context, CONCENTRATION_MODEL_PATH)
            if (modelPath == null) {
                Log.e(TAG, "加载浓度预测模型失败")
                throw Exception("加载浓度预测模型失败")
            }
            
            val concentrationModel = LiteModuleLoader.load(modelPath)
            Log.d(TAG, "浓度预测模型加载成功")
            
            // 裁剪图像并预测浓度
            val updatedWellResults = wellResults.mapIndexed { index, wellResult ->
                try {
                    // 从孔位结果中获取检测框
                    val rect = RectF(
                        wellResult.detectedRectLeft ?: 0f,
                        wellResult.detectedRectTop ?: 0f,
                        wellResult.detectedRectRight ?: 0f,
                        wellResult.detectedRectBottom ?: 0f
                    )
                    
                    // 裁剪孔位图像
                    val croppedBitmap = cropWellImage(originalBitmap, rect)
                    
                    // 预测浓度
                    val concentration = predictConcentration(croppedBitmap, concentrationModel)
                    
                    // 保存裁剪图像（可选）
                    val identifier = saveWellImage(croppedBitmap, runId, index)
                    
                    // 更新孔位结果
                    wellResult.copy(
                        predictedConcentration = concentration,
                        croppedImageIdentifier = identifier
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "处理孔位 #$index 失败", e)
                    // 如果处理单个孔位失败，保留原始孔位结果但不更新浓度
                    wellResult
                }
            }
            
            // 批量更新孔位结果
            wellResultDao.updateWellResults(updatedWellResults)
            
            // 更新检测运行状态为完成
            detectionRunDao.updateDetectionRun(
                detectionRun.copy(
                    status = "Completed"
                )
            )
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "裁剪孔位并预测浓度失败", e)
            
            // 更新检测运行状态为失败
            val detectionRun = detectionRunDao.getDetectionRunById(runId)
            if (detectionRun != null) {
                detectionRunDao.updateDetectionRun(
                    detectionRun.copy(
                        status = "Failed",
                        errorMessage = e.message
                    )
                )
            }
            
            false
        }
    }
    
    /**
     * 裁剪孔位图像
     * @param originalBitmap 原始图像
     * @param rect 孔位矩形区域
     * @return 裁剪后的图像
     */
    private fun cropWellImage(originalBitmap: Bitmap, rect: RectF): Bitmap {
        // 计算裁剪区域
        val left = rect.left.toInt().coerceAtLeast(0)
        val top = rect.top.toInt().coerceAtLeast(0)
        val width = rect.width().toInt().coerceAtMost(originalBitmap.width - left)
        val height = rect.height().toInt().coerceAtMost(originalBitmap.height - top)
        
        // 裁剪图像
        var croppedBitmap = Bitmap.createBitmap(
            originalBitmap,
            left,
            top,
            width,
            height
        )
        
        // 调整为模型输入大小
        croppedBitmap = Bitmap.createScaledBitmap(
            croppedBitmap,
            CROPPED_WELL_SIZE,
            CROPPED_WELL_SIZE,
            true
        )
        
        return croppedBitmap
    }
    
    /**
     * 预测浓度
     * @param wellBitmap 孔位图像
     * @param model 浓度预测模型
     * @return 预测的浓度值
     */
    private fun predictConcentration(wellBitmap: Bitmap, model: org.pytorch.Module): Double {
        // 准备输入
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(wellBitmap, mean, std)
        
        // 执行推理
        val outputTensor = model.forward(IValue.from(inputTensor)).toTensor()
        val outputData = outputTensor.dataAsFloatArray
        
        // 预测结果是单一浓度值
        val concentration = outputData[0].toDouble()
        
        // 通常模型会输出归一化的值，可能需要还原到真实浓度范围
        // 这里假设模型输出的就是实际浓度
        return concentration
    }
    
    /**
     * 保存孔位图像
     * @param wellBitmap 孔位图像
     * @param runId 运行ID
     * @param wellIndex 孔位索引
     * @return 图像标识符（文件路径或URI）
     */
    private fun saveWellImage(wellBitmap: Bitmap, runId: String, wellIndex: Int): String {
        // 创建保存目录
        val directory = File(context.filesDir, "wells/$runId")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        
        // 创建图像文件
        val imageFile = File(directory, "well_${wellIndex}.jpg")
        
        // 保存图像
        FileOutputStream(imageFile).use { outputStream ->
            wellBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.flush()
        }
        
        // 返回文件路径作为标识符
        return imageFile.absolutePath
    }
    
    /**
     * 将资源文件提取到本地文件系统
     */
    fun assetFilePath(context: Context, assetName: String): String? {
        try {
            // 从完整路径中提取文件名和目录
            val lastSeparatorIndex = assetName.lastIndexOf('/')
            val fileName = if (lastSeparatorIndex != -1) assetName.substring(lastSeparatorIndex + 1) else assetName
            val directoryPath = if (lastSeparatorIndex != -1) assetName.substring(0, lastSeparatorIndex) else ""
            
            // 创建目标目录
            val directory = File(context.filesDir, directoryPath)
            if (!directory.exists()) {
                val dirCreated = directory.mkdirs()
                Log.d(TAG, "创建目录结果: $dirCreated (${directory.absolutePath})")
            }
            
            // 创建输出文件
            val outFile = File(directory, fileName)
            Log.d(TAG, "输出文件路径: ${outFile.absolutePath}")
            
            // 如果文件已存在且不为空，则直接返回路径
            if (outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "文件已存在，直接使用: ${outFile.absolutePath}")
                return outFile.absolutePath
            }
            
            // 复制文件
            context.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(4 * 1024)
                    var read: Int
                    var totalBytes = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                    output.flush()
                    Log.d(TAG, "已从assets复制文件到: ${outFile.absolutePath}, 大小: $totalBytes 字节")
                }
            }
            
            // 再次检查文件是否已成功创建
            if (outFile.exists() && outFile.length() > 0) {
                return outFile.absolutePath
            } else {
                Log.e(TAG, "文件复制后检查失败: ${outFile.absolutePath}")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制文件时出错: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 手动模式：分析单个裁剪图像
     * @param projectId 项目ID
     * @param croppedImageUri 裁剪图像URI
     * @return 预测的浓度值，如果失败则返回null
     */
    suspend fun analyzeManualCroppedImage(
        projectId: String,
        croppedImageUri: Uri
    ): Double? = withContext(Dispatchers.IO) {
        try {
            // 加载浓度预测模型
            val modelPath = assetFilePath(context, CONCENTRATION_MODEL_PATH)
            if (modelPath == null) {
                Log.e(TAG, "加载浓度预测模型失败")
                return@withContext null
            }
            
            val concentrationModel = LiteModuleLoader.load(modelPath)
            Log.d(TAG, "浓度预测模型加载成功")
            
            // 从URI加载图像
            val inputStream = context.contentResolver.openInputStream(croppedImageUri)
            val croppedBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (croppedBitmap == null) {
                Log.e(TAG, "无法从URI加载图像: $croppedImageUri")
                return@withContext null
            }
            
            // 调整图像大小
            val resizedBitmap = Bitmap.createScaledBitmap(
                croppedBitmap,
                CROPPED_WELL_SIZE,
                CROPPED_WELL_SIZE,
                true
            )
            
            // 预测浓度
            val concentration = predictConcentration(resizedBitmap, concentrationModel)
            
            // 保存到数据库
            val wellResult = WellResult(
                runId = null, // 手动模式没有运行ID
                projectId = projectId,
                wellIndex = -1, // 手动模式使用特殊索引
                predictedConcentration = concentration,
                trueConcentration = null,
                isStandard = false,
                detectedRectLeft = null,
                detectedRectTop = null,
                detectedRectRight = null,
                detectedRectBottom = null,
                detectionConfidence = null,
                croppedImageIdentifier = croppedImageUri.toString(),
                roleType = "Sample" // 默认角色类型
            )
            
            wellResultDao.insertWellResult(wellResult)
            
            // 更新项目的最后运行时间
            val project = projectDao.getProjectById(projectId)
            project?.let {
                val updatedProject = it.copy(lastRunTimestamp = Date())
                projectDao.updateProject(updatedProject)
            }
            
            concentration
        } catch (e: Exception) {
            Log.e(TAG, "分析手动裁剪图像失败", e)
            null
        }
    }
    
    /**
     * 仅裁剪孔位图像，不进行浓度预测
     * @param runId 运行ID
     * @param originalBitmap 原始图像
     * @return 裁剪成功的孔位列表
     */
    suspend fun cropWellsOnly(
        runId: String,
        originalBitmap: Bitmap
    ): List<WellResult> = withContext(Dispatchers.IO) {
        try {
            // 获取检测运行记录
            val detectionRun = detectionRunDao.getDetectionRunById(runId)
            if (detectionRun == null) {
                Log.e(TAG, "找不到检测运行记录: $runId")
                return@withContext emptyList()
            }
            
            // 获取孔位结果
            val wellResults = wellResultDao.getWellResultsByRunId(runId)
            if (wellResults.isEmpty()) {
                Log.e(TAG, "找不到孔位结果: $runId")
                return@withContext emptyList()
            }
            
            // 更新检测运行状态
            detectionRunDao.updateDetectionRun(
                detectionRun.copy(
                    status = "Processing", 
                    concentrationModelUsed = CONCENTRATION_MODEL_PATH
                )
            )
            
            // 裁剪图像并保存，使用并行处理提高性能
            val updatedWellResults = wellResults.mapIndexed { index, wellResult ->
                try {
                    // 从孔位结果中获取检测框
                    val rect = RectF(
                        wellResult.detectedRectLeft ?: 0f,
                        wellResult.detectedRectTop ?: 0f,
                        wellResult.detectedRectRight ?: 0f,
                        wellResult.detectedRectBottom ?: 0f
                    )
                    
                    // 裁剪孔位图像
                    val croppedBitmap = cropWellImage(originalBitmap, rect)
                    
                    // 保存裁剪图像
                    val identifier = saveWellImage(croppedBitmap, runId, index)
                    
                    // 更新孔位结果
                    wellResult.copy(croppedImageIdentifier = identifier)
                } catch (e: Exception) {
                    Log.e(TAG, "裁剪孔位 #$index 图像失败", e)
                    wellResult
                }
            }
            
            // 批量更新孔位结果
            wellResultDao.updateWellResults(updatedWellResults)
            
            updatedWellResults
        } catch (e: Exception) {
            Log.e(TAG, "裁剪孔位图像失败", e)
            emptyList()
        }
    }
    
    /**
     * 仅对已裁剪的孔位进行浓度预测，支持进度回调
     * @param runId 运行ID
     * @param progressCallback 进度回调函数，参数为0-100的整数
     * @return 预测成功的孔位列表
     */
    suspend fun predictConcentrationOnly(
        runId: String,
        progressCallback: (Int) -> Unit = {}
    ): List<WellResult> = withContext(Dispatchers.IO) {
        try {
            // 初始化进度
            progressCallback(0)
            
            // 获取检测运行记录
            val detectionRun = detectionRunDao.getDetectionRunById(runId)
            if (detectionRun == null) {
                Log.e(TAG, "找不到检测运行记录: $runId")
                return@withContext emptyList()
            }
            
            // 获取孔位结果
            val wellResults = wellResultDao.getWellResultsByRunId(runId)
            if (wellResults.isEmpty()) {
                Log.e(TAG, "找不到孔位结果: $runId")
                return@withContext emptyList()
            }
            
            // 更新进度到10%
            progressCallback(10)
            
            // 加载浓度预测模型
            val modelPath = assetFilePath(context, CONCENTRATION_MODEL_PATH)
            if (modelPath == null) {
                Log.e(TAG, "加载浓度预测模型失败")
                return@withContext emptyList()
            }
            
            val concentrationModel = LiteModuleLoader.load(modelPath)
            Log.d(TAG, "浓度预测模型加载成功")
            
            // 更新进度到20%
            progressCallback(20)
            
            // 预测浓度
            val totalWells = wellResults.size
            val updatedWellResults = mutableListOf<WellResult>()
            
            // 处理每个孔位并更新进度
            wellResults.forEachIndexed { index, wellResult ->
                try {
                    // 检查是否已有裁剪图像
                    val imagePath = wellResult.croppedImageIdentifier
                    if (imagePath.isNullOrEmpty()) {
                        Log.w(TAG, "孔位 #$index 没有裁剪图像")
                        updatedWellResults.add(wellResult)
                    } else {
                        // 加载裁剪图像
                        val imageFile = File(imagePath)
                        if (!imageFile.exists()) {
                            Log.w(TAG, "孔位图像文件不存在: $imagePath")
                            updatedWellResults.add(wellResult)
                        } else {
                            val wellBitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
                            if (wellBitmap == null) {
                                Log.w(TAG, "无法加载孔位图像: $imagePath")
                                updatedWellResults.add(wellResult)
                            } else {
                                // 预测浓度
                                val concentration = predictConcentration(wellBitmap, concentrationModel)
                                
                                // 更新孔位结果
                                updatedWellResults.add(wellResult.copy(predictedConcentration = concentration))
                            }
                        }
                    }
                    
                    // 计算并更新当前进度（20-90%范围内）
                    val progress = 20 + (index + 1) * 70 / totalWells
                    progressCallback(progress)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "预测孔位 #$index 浓度失败", e)
                    updatedWellResults.add(wellResult)
                }
                
                // 每处理8个孔位，批量更新一次数据库
                if ((index + 1) % 8 == 0 || index == wellResults.size - 1) {
                    val startIdx = maxOf(0, index - 7)
                    val endIdx = index
                    val batchToUpdate = updatedWellResults.subList(startIdx, endIdx + 1)
                    if (batchToUpdate.isNotEmpty()) {
                        wellResultDao.updateWellResults(batchToUpdate)
                    }
                }
            }
            
            // 批量更新孔位结果
            wellResultDao.updateWellResults(updatedWellResults)
            
            // 更新检测运行状态为完成
            detectionRunDao.updateDetectionRun(
                detectionRun.copy(
                    status = "Completed"
                )
            )
            
            // 完成进度
            progressCallback(100)
            
            updatedWellResults
        } catch (e: Exception) {
            Log.e(TAG, "预测浓度失败", e)
            
            // 更新检测运行状态为失败
            val detectionRun = detectionRunDao.getDetectionRunById(runId)
            if (detectionRun != null) {
                detectionRunDao.updateDetectionRun(
                    detectionRun.copy(
                        status = "Failed",
                        errorMessage = e.message
                    )
                )
            }
            
            emptyList()
        }
    }
    
    /**
     * 为了向后兼容旧代码，提供predictConcentrationByModel作为predictConcentrationOnly的别名
     * @param runId 运行ID
     * @return 预测成功的孔位列表
     */
    suspend fun predictConcentrationByModel(
        runId: String
    ): List<WellResult> = withContext(Dispatchers.IO) {
        // 直接调用现有的predictConcentrationOnly方法，不传递进度回调
        predictConcentrationOnly(runId) {}
    }
    
    /**
     * 使用并行处理方式裁剪孔位图像，提高处理速度
     * @param runId 运行ID
     * @param originalBitmap 原始图像
     * @return 裁剪成功的孔位列表
     */
    suspend fun cropWellsWithParallelProcessing(
        runId: String,
        originalBitmap: Bitmap
    ): List<WellResult> = withContext(Dispatchers.IO) {
        try {
            // 获取检测运行记录和孔位结果
            val detectionRun = detectionRunDao.getDetectionRunById(runId) ?: 
                throw Exception("找不到检测运行记录: $runId")
            
            val wellResults = wellResultDao.getWellResultsByRunId(runId)
            if (wellResults.isEmpty()) {
                throw Exception("找不到孔位结果: $runId")
            }
            
            // 更新检测运行状态
            detectionRunDao.updateDetectionRun(
                detectionRun.copy(
                    status = "Processing", 
                    concentrationModelUsed = CONCENTRATION_MODEL_PATH
                )
            )
            
            // 按批次处理孔位图像，减少内存压力
            val processedResults = mutableListOf<WellResult>()
            
            // 并行处理每批孔位
            wellResults.chunked(PARALLEL_BATCH_SIZE).forEach { batch ->
                val batchResults = coroutineScope {
                    batch.map { wellResult ->
                        async(Dispatchers.Default) {
                            val index = wellResult.wellIndex
                            try {
                                // 从孔位结果中获取检测框
                                val rect = RectF(
                                    wellResult.detectedRectLeft ?: 0f,
                                    wellResult.detectedRectTop ?: 0f,
                                    wellResult.detectedRectRight ?: 0f,
                                    wellResult.detectedRectBottom ?: 0f
                                )
                                
                                // 裁剪孔位图像
                                val croppedBitmap = cropWellImage(originalBitmap, rect)
                                
                                // 保存裁剪图像
                                val identifier = saveWellImage(croppedBitmap, runId, index)
                                
                                // 更新孔位结果
                                wellResult.copy(croppedImageIdentifier = identifier)
                            } catch (e: Exception) {
                                Log.e(TAG, "处理孔位 #$index 失败", e)
                                wellResult
                            }
                        }
                    }.awaitAll()
                }
                
                // 添加到处理结果列表
                processedResults.addAll(batchResults)
                
                // 批量更新孔位结果
                wellResultDao.updateWellResults(batchResults)
            }
            
            return@withContext processedResults
        } catch (e: Exception) {
            Log.e(TAG, "裁剪孔位图像失败", e)
            emptyList()
        }
    }
    
    /**
     * 获取项目对应的最新运行ID
     * @param projectId 项目ID
     * @return 最新的运行ID，如果没有则返回null
     */
    suspend fun getLatestRunIdForProject(projectId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 获取项目关联的所有结果，按创建时间倒序排列，取第一个（最新的）
                val results = wellResultDao.getWellResultsByProjectId(projectId)
                if (results.isNotEmpty()) {
                    // 返回最新的runId
                    results.first().runId
                } else {
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("WellResultRepository", "获取项目最新运行ID失败: ${e.message}", e)
                null
            }
        }
    }

    /**
     * 根据检测框坐标裁剪孔位图像，并更新数据库中的图像标识符
     * @param runId 运行ID
     * @param originalBitmap 原始图像
     * @return 更新后的孔位结果列表
     */
    suspend fun cropAndSaveWellImages(
        runId: String,
        originalBitmap: Bitmap
    ): List<WellResult> = withContext(Dispatchers.IO) {
        try {
            val wellResults = wellResultDao.getWellResultsByRunId(runId)
            if (wellResults.isEmpty()) {
                throw Exception("找不到运行ID为 $runId 的孔位结果")
            }

            Log.d(TAG, "开始裁剪 ${wellResults.size} 个孔位的图像")

            val updatedResults = coroutineScope {
                wellResults.map { wellResult ->
                    async(Dispatchers.Default) {
                        try {
                            val rect = RectF(
                                wellResult.detectedRectLeft ?: 0f,
                                wellResult.detectedRectTop ?: 0f,
                                wellResult.detectedRectRight ?: 0f,
                                wellResult.detectedRectBottom ?: 0f
                            )
                            val croppedBitmap = cropWellImage(originalBitmap, rect)
                            val identifier = saveWellImage(croppedBitmap, runId, wellResult.wellIndex)
                            wellResult.copy(croppedImageIdentifier = identifier)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理孔位 #${wellResult.wellIndex} 失败", e)
                            wellResult // 如果单个失败，返回原始结果
                        }
                    }
                }.awaitAll()
            }

            // 批量更新数据库
            wellResultDao.updateWellResults(updatedResults)
            Log.d(TAG, "成功裁剪并更新了 ${updatedResults.size} 个孔位的图像标识符")
            updatedResults

        } catch (e: Exception) {
            Log.e(TAG, "裁剪和保存孔位图像失败", e)
            emptyList()
        }
    }

    /**
     * 根据运行ID获取孔位结果
     * @param runId 运行ID
     * @return 孔位结果列表
     */
    suspend fun getWellResultsByRunId(runId: String): List<WellResult> {
        return wellResultDao.getWellResultsByRunId(runId)
    }

    /**
     * 根据运行ID和分析物ID获取孔位结果
     * @param runId 运行ID
     * @param analyteId 分析物ID
     * @return 孔位结果列表
     */
    suspend fun getWellResultsByRunIdAndAnalyteId(runId: String, analyteId: String): List<WellResult> {
        return wellResultDao.getWellResultsByRunIdAndAnalyteId(runId, analyteId)
    }

    /**
     * 根据结果ID获取孔位结果
     * @param resultId 结果ID
     * @return 孔位结果
     */
    suspend fun getWellResultById(resultId: Long): WellResult? {
        return wellResultDao.getWellResultById(resultId)
    }

    /**
     * 更新孔位结果
     * @param wellResult 要更新的孔位结果
     */
    suspend fun updateWellResult(wellResult: WellResult) {
        wellResultDao.updateWellResult(wellResult)
    }
    
    /**
     * 批量更新孔位结果
     * @param wellResults 要更新的孔位结果列表
     */
    suspend fun updateWellResults(wellResults: List<WellResult>) {
        wellResultDao.updateWellResults(wellResults)
    }
} 