package com.muc.fluocolorquant.ui.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.*

/**
 * 将资源文件提取到本地文件系统
 */
private fun assetFilePath(context: Context, assetName: String): String? {
    try {
        // 从完整路径中提取文件名和目录
        val lastSeparatorIndex = assetName.lastIndexOf('/')
        val fileName = if (lastSeparatorIndex != -1) assetName.substring(lastSeparatorIndex + 1) else assetName
        val directoryPath = if (lastSeparatorIndex != -1) assetName.substring(0, lastSeparatorIndex) else ""
        
        // 创建目标目录
        val directory = File(context.filesDir, directoryPath)
        if (!directory.exists()) {
            val dirCreated = directory.mkdirs()
            Log.d("ImageCorrection", "创建目录结果: $dirCreated (${directory.absolutePath})")
        }
        
        // 创建输出文件
        val outFile = File(directory, fileName)
        Log.d("ImageCorrection", "输出文件路径: ${outFile.absolutePath}")
        
        // 如果文件已存在且不为空，则直接返回路径
        if (outFile.exists() && outFile.length() > 0) {
            Log.d("ImageCorrection", "文件已存在，直接使用: ${outFile.absolutePath}")
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
                Log.d("ImageCorrection", "已从assets复制文件到: ${outFile.absolutePath}, 大小: $totalBytes 字节")
            }
        }
        
        // 再次检查文件是否已成功创建
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        } else {
            Log.e("ImageCorrection", "文件复制后检查失败: ${outFile.absolutePath}")
            return null
        }
    } catch (e: Exception) {
        Log.e("ImageCorrection", "复制文件时出错: ${e.message}", e)
        return null
    }
}

/**
 * 孔阵检测数据类，存储单个孔位的信息
 */
private data class CorrectionWellDetection(
    val id: Int,
    var rect: RectF,
    val confidence: Float
)

/**
 * 临时数据类，用于存储NMS处理前的原始检测结果（坐标在1280x1280空间）
 */
private data class RawDetection(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float,
    val classIndex: Int
) {
    // 用于NMS计算的RectF
    fun getRectF(): RectF {
        return RectF(x1, y1, x2, y2)
    }
}

/**
 * Letterbox参数信息类，用于坐标转换
 */
private data class LetterboxInfo(
    val scale: Float,
    val paddingX: Float,
    val paddingY: Float,
    val inputWidth: Int,
    val inputHeight: Int
)

/**
 * 图像矫正ViewModel
 * 负责处理图像矫正相关的业务逻辑，实现了基于自适应网格约束的迭代单应性与畸变参数估计方法
 */
@HiltViewModel
class ImageCorrectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val TAG = "ImageCorrection"

    // 校正处理状态
    sealed class CorrectionState {
        object Idle : CorrectionState()
        object Processing : CorrectionState()
        data class Success(val imageUri: Uri) : CorrectionState()
        data class Error(val message: String) : CorrectionState()
    }
    
    // 校正状态流
    private val _correctionState = MutableStateFlow<CorrectionState>(CorrectionState.Idle)
    val correctionState: StateFlow<CorrectionState> = _correctionState.asStateFlow()
    
    // 原始图像
    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()
    
    // 校正后图像
    private val _correctedBitmap = MutableStateFlow<Bitmap?>(null)
    val correctedBitmap: StateFlow<Bitmap?> = _correctedBitmap.asStateFlow()
    
    // 当前项目信息
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()
    
    // YOLOv5模型
    private var model: Module? = null
    
    // 存储预处理的Letterbox参数，用于坐标转换
    private var lastLetterboxInfo: LetterboxInfo? = null
    
    // 初始化OpenCV和PyTorch模型
    init {
        try {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV初始化失败")
            } else {
                Log.d(TAG, "OpenCV初始化成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV初始化错误: ${e.message}", e)
        }
        
        // 在后台加载YOLOv5模型
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 模型路径
                val assetsModelPath = "models/best_lite.ptl"
                
                // 加载模型
                try {
                    Log.d(TAG, "开始加载模型: $assetsModelPath")
                    val startTime = System.currentTimeMillis()
                    
                    // 获取模型文件路径
                    val path = assetFilePath(context, assetsModelPath)
                    if (path != null) {
                        // 验证文件是否存在
                        val modelFile = File(path)
                        if (modelFile.exists() && modelFile.length() > 0) {
                            Log.d(TAG, "模型文件准备就绪: $path, 大小: ${modelFile.length()} 字节")
                            model = LiteModuleLoader.load(path)
                            Log.d(TAG, "成功使用LiteModuleLoader加载模型")
                        } else {
                            throw IOException("模型文件不存在或为空: $path")
                        }
                    } else {
                        throw IOException("无法提取模型文件: $assetsModelPath")
                    }
                    
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "模型加载成功，耗时: ${duration}ms")
        } catch (e: Exception) {
                    Log.e(TAG, "模型加载失败: ${e.message}", e)
            throw e
                }
            } catch (e: Exception) {
                // 记录错误但不立即显示
                Log.e(TAG, "初始化过程中出错: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 设置当前项目
     * @param projectId 项目ID
     */
    @SuppressLint("LongLogTag")
    fun setCurrentProject(projectId: String) {
        viewModelScope.launch {
            try {
                val project = projectRepository.getProjectById(projectId)
                _currentProject.value = project
                Log.d(TAG, "项目加载成功，行数: ${project?.rows}, 列数: ${project?.columns}")
            } catch (e: Exception) {
                Log.e(TAG, "加载项目失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载原始图像
     * @param imageUri 图像URI
     */
    fun loadImage(imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(imageUri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                _originalBitmap.value = bitmap
            } catch (e: Exception) {
                Log.e(TAG, "加载图像失败: ${e.message}", e)
            }
        }
    }

    /**
     * 矫正图像并使用回调处理结果
     * @param imageUri 原始图像的Uri
     * @param projectId 项目ID
     * @param onSuccess 成功回调
     * @param onError 错误回调
     */
    fun correctImageWithErrorHandling(
        imageUri: Uri,
        projectId: String,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _correctionState.value = CorrectionState.Processing
            try {
                // 加载项目信息
                val project = projectRepository.getProjectById(projectId) 
                    ?: throw Exception("找不到项目信息")
                _currentProject.value = project
                
                // 获取行列数
                val rows = project.rows
                val columns = project.columns
                
                // 执行图像矫正
                val correctedImageUri = correctImage(imageUri, rows, columns)
                
                // 设置状态并调用成功回调
                _correctionState.value = CorrectionState.Success(correctedImageUri)
                onSuccess(correctedImageUri)
            } catch (e: Exception) {
                Log.e(TAG, "图像矫正失败: ${e.message}", e)
                _correctionState.value = CorrectionState.Error(e.message ?: "未知错误")
                onError(e.message ?: "未知错误")
            }
        }
    }

    /**
     * 矫正图像
     * @param imageUri 原始图像的Uri
     * @param rows 孔阵行数
     * @param columns 孔阵列数
     * @return 矫正后图像的Uri
     */
    suspend fun correctImage(imageUri: Uri, rows: Int, columns: Int): Uri = withContext(Dispatchers.IO) {
            try {
                // 读取原始图像
                val inputStream = context.contentResolver.openInputStream(imageUri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            _originalBitmap.value = originalBitmap
            
            // 初始孔位检测和校正
            val correctedBitmap = correctDistortion(originalBitmap, rows, columns)
            _correctedBitmap.value = correctedBitmap

                // 保存矫正后的图像
                val correctedImageFile = createTempImageFile("corrected_")
                val outputStream = FileOutputStream(correctedImageFile)
            correctedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                outputStream.close()

            // 创建并返回矫正后图像的Uri
            FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    correctedImageFile
                )
        } catch (e: Exception) {
            Log.e(TAG, "图像校正错误", e)
            throw e
        }
    }

    /**
     * 实现图像畸变校正算法
     * @param bitmap 输入图像
     * @param rows 孔阵行数
     * @param columns 孔阵列数
     * @return 校正后的图像
     */
    private suspend fun correctDistortion(bitmap: Bitmap, rows: Int, columns: Int): Bitmap = withContext(Dispatchers.IO) {
        // 记录开始时间，用于性能监控
        val startTime = System.currentTimeMillis()
        
        Log.d(TAG, "开始图像畸变校正，图像尺寸: ${bitmap.width} x ${bitmap.height}, 孔阵: $rows x $columns")
        
        // 等待模型加载完成（最多等待6秒）
        var waitCount = 0
        while (model == null && waitCount < 60) {
            try {
                Thread.sleep(100)
                waitCount++
            } catch (e: InterruptedException) {
                Log.e(TAG, "等待模型加载被中断", e)
                break
            }
        }
        
        if (model == null) {
            Log.w(TAG, "模型未加载完成，将使用OpenCV备用方法")
        } else {
            Log.d(TAG, "YOLOv5模型已加载，将使用模型进行孔位检测")
        }
        
        // 步骤1: 转换为OpenCV格式
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        
        // 保存原始输入图像，用于调试
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "original_input.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "原始输入图像已保存: ${file.absolutePath}")
            } catch (e: Exception) {
            Log.e(TAG, "保存原始图像失败: ${e.message}", e)
        }
        
        // 灰度化
        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        
        // 步骤2: 使用YOLOv5检测孔位中心点 (如果模型可用)
        val wellCenters = detectWellCenters(grayMat, rows, columns)
        
        if (wellCenters.isEmpty()) {
            Log.e(TAG, "未检测到任何孔位点，返回原始图像")
            inputMat.release()
            grayMat.release()
            return@withContext bitmap.copy(bitmap.config, true)
        }
        
        Log.d(TAG, "检测到 ${wellCenters.size} 个孔位点")
        
        if (wellCenters.size < min(rows * columns / 3, 4)) {
            Log.e(TAG, "检测到的孔位点数量不足 (${wellCenters.size})，可能导致畸变校正失败，返回原始图像")
            inputMat.release()
            grayMat.release()
            return@withContext bitmap.copy(bitmap.config, true)
        }
        
        // 步骤3: 执行迭代优化算法，同时估计单应性矩阵和径向畸变参数
        val correctedMat = performIterativeCorrection(inputMat, wellCenters, rows, columns)
        
        // 步骤4: 转换回Bitmap
        val resultBitmap = Bitmap.createBitmap(
            correctedMat.cols(), correctedMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(correctedMat, resultBitmap)
        
        // 保存校正后的图像，用于调试
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "corrected_result.jpg")
            FileOutputStream(file).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "校正后的图像已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存校正结果失败: ${e.message}", e)
        }
        
        // 释放OpenCV资源
        inputMat.release()
        grayMat.release()
        correctedMat.release()
        
        // 记录总处理时间
        val processingTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "图像畸变校正完成，处理耗时: $processingTime ms")
        
        return@withContext resultBitmap
    }
    
    /**
     * 使用YOLOv5模型检测孔位中心点
     * @param inputMat 输入图像矩阵
     * @param rows 孔阵行数
     * @param columns 孔阵列数
     * @return 检测到的孔位中心点列表
     */
    private fun detectWellCenters(grayMat: Mat, rows: Int, columns: Int): List<Point> {
        Log.d(TAG, "开始使用YOLOv5模型检测孔位中心点，行数: $rows, 列数: $columns")
        
        try {
            // 确保模型已加载
            if (model == null) {
                Log.e(TAG, "YOLOv5模型未加载，尝试使用备用检测方法")
                // 如果模型未加载，使用备用方法
                return detectWellCentersWithOpenCV(grayMat, rows, columns)
            }
            
            // 1. 将OpenCV Mat转换为Bitmap
            val inputBitmap = Bitmap.createBitmap(grayMat.cols(), grayMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(grayMat, inputBitmap)
            
            // 保存原始输入图像用于调试
            try {
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "yolo_input.jpg")
                FileOutputStream(file).use { out ->
                    inputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Log.d(TAG, "YOLO输入图像保存到: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "保存YOLO输入图像失败: ${e.message}")
            }
            
            // 2. 准备图像输入 (letterbox处理)
            val letterboxedBitmap = prepareInputBitmap(inputBitmap)
            val letterboxInfo = lastLetterboxInfo ?: throw Exception("Letterbox参数缺失")
            
            // 3. 转换为PyTorch张量
            val mean = floatArrayOf(0.0f, 0.0f, 0.0f)
            val std = floatArrayOf(1.0f, 1.0f, 1.0f)
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(letterboxedBitmap, mean, std)
            
            // 4. 执行推理
            val startTime = System.currentTimeMillis()
            val modelOutput = model!!.forward(IValue.from(inputTensor))
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "YOLOv5推理完成，耗时: ${inferenceTime}ms")
            
            // 5. 获取原始输出张量
            val outputTensor = getOutputTensor(modelOutput)
            
            // 6. 解析检测结果和过滤
            val confThreshold = 0.25f  // 置信度阈值
            val rawDetections = parseRawDetections(outputTensor, confThreshold)
            Log.d(TAG, "原始检测数量: ${rawDetections.size}")
            
            // 7. 应用非极大值抑制 (NMS)
            val iouThreshold = 0.45f  // IoU阈值
            val nmsResults = applyNMS(rawDetections, iouThreshold)
            Log.d(TAG, "NMS后检测数量: ${nmsResults.size}")
            
            // 8. 坐标转换
            val wellDetections = scaleBoxes(nmsResults, letterboxInfo, grayMat.cols(), grayMat.rows())
            Log.d(TAG, "最终检测数量: ${wellDetections.size}")
            
            // 9. 根据项目孔数限制数量
            var finalDetections = wellDetections
            val expectedWellCount = rows * columns
            if (wellDetections.size > expectedWellCount) {
                // 按置信度排序，取前expectedWellCount个
                finalDetections = wellDetections.sortedByDescending { it.confidence }
                    .take(expectedWellCount)
                    .toMutableList()
                Log.d(TAG, "根据孔阵大小限制检测数量: $expectedWellCount")
            }
            
            // 10. 生成检测结果可视化图像用于调试
            try {
                val debugBitmap = inputBitmap.copy(inputBitmap.config, true)
                val canvas = Canvas(debugBitmap)
                val paint = android.graphics.Paint().apply { 
                    color = Color.GREEN
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 3f
                }
                
                for (detection in finalDetections) {
                    canvas.drawRect(detection.rect, paint)
                }
                
                val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "yolo_detections.jpg")
                FileOutputStream(file).use { out ->
                    debugBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Log.d(TAG, "检测结果可视化保存到: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "保存检测可视化失败: ${e.message}")
            }
            
            // 11. 将检测矩形框转换为中心点
            val centerPoints = convertDetectionsToPoints(finalDetections)
            Log.d(TAG, "最终孔位中心点数量: ${centerPoints.size}")
            
            return if (centerPoints.isEmpty()) {
                Log.w(TAG, "YOLOv5未检测到任何孔位，使用备用方法")
                detectWellCentersWithOpenCV(grayMat, rows, columns)
            } else {
                centerPoints
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "YOLOv5检测过程出错: ${e.message}", e)
            // 出错时使用备用方法
            return detectWellCentersWithOpenCV(grayMat, rows, columns)
        }
    }
    
    /**
     * 使用OpenCV方法检测孔位中心点（作为备用方法）
     */
    private fun detectWellCentersWithOpenCV(grayMat: Mat, rows: Int, columns: Int): List<Point> {
        Log.d(TAG, "使用OpenCV备用方法检测孔位中心点")
        
        // 增强对比度 - 使用CLAHE
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 3.0
        val enhancedMat = Mat()
        clahe.apply(grayMat, enhancedMat)
        
        // 降噪处理 - 尝试不同的高斯模糊参数
        Imgproc.GaussianBlur(enhancedMat, enhancedMat, Size(5.0, 5.0), 2.0, 2.0)
        
        // 记录处理过程中的中间图像尺寸，用于调试
        Log.d(TAG, "增强后图像大小: ${enhancedMat.width()} x ${enhancedMat.height()}")
        
        // 使用自适应二值化增强边缘对比度
        val binaryMat = Mat()
        Imgproc.adaptiveThreshold(
            enhancedMat,
            binaryMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            11,  // 区块大小，可以尝试调整
            2.0  // 常数C，可以尝试调整
        )
        
        // 保存中间处理结果用于调试
        saveIntermediateResult(binaryMat, "opencv_binary_mat.jpg")
        
        // 基于图像尺寸和孔阵数量估计孔的半径范围
        val minRadius = (enhancedMat.width() / (columns * 3).toDouble()).toInt()
        val maxRadius = (enhancedMat.width() / columns.toDouble()).toInt()
        
        Log.d(TAG, "孔半径范围估计：最小 $minRadius，最大 $maxRadius 像素")
        
        // 尝试不同的霍夫圆参数组合
        val paramSets = listOf(
            Triple(150.0, 30.0, 1.0),  // 原始参数
            Triple(100.0, 20.0, 1.0),  // 降低阈值
            Triple(200.0, 40.0, 1.0)   // 提高阈值
        )
        
        var detectedCircles = mutableListOf<Point>()
        
        for ((param1, param2, dp) in paramSets) {
            if (detectedCircles.isNotEmpty()) break
            
            val circles = Mat()
            try {
                Imgproc.HoughCircles(
                    enhancedMat,
                    circles,
                    Imgproc.HOUGH_GRADIENT,
                    dp,                    // 分辨率比例
                    enhancedMat.rows() / (Math.max(rows, columns) + 2).toDouble(),  // 最小圆心距离
                    param1,                // Canny边缘检测器的高阈值
                    param2,                // 累加器阈值
                    minRadius,             // 最小半径
                    maxRadius              // 最大半径
                )
                
                if (circles.cols() > 0) {
                    Log.d(TAG, "霍夫变换检测到圆的数量: ${circles.cols()}")
                    
                    for (i in 0 until min(circles.cols(), rows * columns * 2)) {
                        val circle = FloatArray(3)
                        circles.get(0, i, circle)
                        detectedCircles.add(org.opencv.core.Point(circle[0].toDouble(), circle[1].toDouble()))
                    }
                    
                    // 保存检测结果用于调试
                    saveDetectionResult(enhancedMat, detectedCircles, "opencv_detected_circles.jpg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "霍夫圆检测失败: ${e.message}", e)
            } finally {
                circles.release()
            }
        }
        
        // 如果霍夫变换不成功，尝试轮廓检测
        if (detectedCircles.isEmpty()) {
            Log.d(TAG, "尝试使用轮廓检测寻找圆形区域")
            
            try {
                // 找到轮廓
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(
                    binaryMat, 
                    contours, 
                    hierarchy, 
                    Imgproc.RETR_EXTERNAL, 
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                
                Log.d(TAG, "轮廓检测找到 ${contours.size} 个轮廓")
                
                // 针对每个轮廓进行拟合
                for (contour in contours) {
                    // 过滤太小的轮廓
                    val area = Imgproc.contourArea(contour)
                    if (area < 50) continue
                    
                    // 拟合圆
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val center = Point()
                    val radius = FloatArray(1)
                    Imgproc.minEnclosingCircle(contour2f, center, radius)
                    
                    // 检查拟合的圆是否在合理范围内
                    if (radius[0] >= minRadius && radius[0] <= maxRadius * 1.5) {
                        detectedCircles.add(center)
                        
                        // 限制检测数量
                        if (detectedCircles.size >= rows * columns) break
                    }
                    
                    contour2f.release()
                }
                
                // 保存检测结果用于调试
                if (detectedCircles.isNotEmpty()) {
                    saveDetectionResult(enhancedMat, detectedCircles, "opencv_contour_circles.jpg")
                }
                
                hierarchy.release()
                for (contour in contours) {
                    contour.release()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "轮廓检测失败: ${e.message}", e)
            }
        }
        
        enhancedMat.release()
        binaryMat.release()
        
        Log.d(TAG, "OpenCV最终检测到孔位点: ${detectedCircles.size}")
        
        // 如果检测到的圆太少，使用均匀网格
        if (detectedCircles.size < min(rows * columns / 2, 16)) {
            Log.w(TAG, "检测到的圆太少: ${detectedCircles.size}，使用均匀网格")
            return generateUniformGridBackup(grayMat.width(), grayMat.height(), rows, columns)
        }
        
        // 对检测到的圆进行过滤
        if (detectedCircles.size > rows * columns) {
            detectedCircles = filterCirclesByGridTopology(detectedCircles, rows, columns).toMutableList()
            Log.d(TAG, "过滤后的圆数量: ${detectedCircles.size}")
            saveDetectionResult(grayMat, detectedCircles, "opencv_filtered_circles.jpg")
        }
        
        return detectedCircles
    }
    
    /**
     * 保存中间处理结果（用于调试）
     */
    private fun saveIntermediateResult(mat: Mat, fileName: String) {
        try {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bitmap)
            
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            
            Log.d(TAG, "中间校正结果已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存中间校正结果失败: ${e.message}")
        }
    }
    
    /**
     * 保存带有检测圆的结果图（用于调试）
     */
    private fun saveDetectionResult(mat: Mat, circles: List<Point>, fileName: String) {
        try {
            val resultMat = mat.clone()
            
            // 在图像上绘制检测到的圆
            for (point in circles) {
                Imgproc.circle(
                    resultMat, 
                    point, 
                    10,  // 圆半径
                    Scalar(0.0, 255.0, 0.0),  // 绿色
                    2    // 线宽
                )
            }
            
            val resultBitmap = Bitmap.createBitmap(resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultMat, resultBitmap)
            
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
            FileOutputStream(file).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Log.d(TAG, "检测结果已保存: ${file.absolutePath}")
            
            resultMat.release()
        } catch (e: Exception) {
            Log.e(TAG, "保存检测结果失败: ${e.message}", e)
        }
    }
    
    /**
     * 通过检测网格角点来获取孔位（备用方法）
     */
    private fun detectGridCorners(grayMat: Mat, rows: Int, columns: Int): List<Point> {
        val corners = MatOfPoint2f()
        val patternSize = Size(columns.toDouble(), rows.toDouble())
        
        val found = Calib3d.findChessboardCorners(
            grayMat, 
            patternSize,
            corners,
            Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE
        )
        
        return if (found && corners.total() > 0) {
            val cornerArray = corners.toArray()
            cornerArray.toList()
        } else {
            // 如果仍然失败，尝试更简单的方法：均匀网格
            generateUniformGridBackup(grayMat.width(), grayMat.height(), rows, columns)
        }
    }
    
    /**
     * 根据图像尺寸生成均匀网格点（最后的备用方法）
     */
    private fun generateUniformGridBackup(width: Int, height: Int, rows: Int, columns: Int): List<Point> {
        val points = mutableListOf<Point>()
        
        // 假设所有孔位均匀分布
        val marginX = width * 0.1
        val marginY = height * 0.1
        val stepX = (width - 2 * marginX) / (columns - 1)
        val stepY = (height - 2 * marginY) / (rows - 1)
        
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                // 使用Double类型的x和y值
                val x = marginX + c * stepX
                val y = marginY + r * stepY
                // 明确使用OpenCV的Point类
                points.add(Point(x, y))
            }
        }
        
        Log.d(TAG, "生成均匀网格: ${points.size} 个点")
        return points
    }
    
    /**
     * 通过分析网格拓扑结构过滤检测到的圆
     * 使用垂直和水平投影来找出最可能符合网格排列的点
     */
    private fun filterCirclesByGridTopology(circles: List<Point>, rows: Int, columns: Int): List<Point> {
        // 如果检测到的圈少于要求的网格点，则返回所有点
        if (circles.size <= rows * columns) return circles
        
        // 步骤1: 确定主方向 - 使用PCA分析
        val points = Mat(circles.size, 2, CvType.CV_64FC1)
        for (i in circles.indices) {
            points.put(i, 0, circles[i].x, circles[i].y)
        }
        
        val mean = Mat()
        val eigenvectors = Mat()
        val eigenvalues = Mat()
        Core.PCACompute2(points, mean, eigenvectors, eigenvalues)
        
        // 获取主方向向量
        val primaryDirection = org.opencv.core.Point(
            eigenvectors.get(0, 0)[0],
            eigenvectors.get(0, 1)[0]
        )
        val secondaryDirection = org.opencv.core.Point(
            eigenvectors.get(1, 0)[0],
            eigenvectors.get(1, 1)[0]
        )
        
        // 步骤2: 将点投影到主方向上
        val projections1 = circles.map { point ->
            val proj = (point.x * primaryDirection.x + point.y * primaryDirection.y)
            Pair(point, proj)
        }.sortedBy { it.second }
        
        val projections2 = circles.map { point ->
            val proj = (point.x * secondaryDirection.x + point.y * secondaryDirection.y)
            Pair(point, proj)
        }.sortedBy { it.second }
        
        // 步骤3: 尝试聚类为行和列
        val selectedPoints = mutableListOf<Point>()
        
        // 根据投影值进行聚类
        if (rows > 1 && columns > 1) {
            // 行聚类
            val rowClusters = clusterByProjection(projections1, rows)
            
            // 从每行中进一步选择列点
            for (rowCluster in rowClusters) {
                // 将当前行的点按列方向投影进行排序
                val rowPoints = rowCluster.map { it.first }
                val sortedByCol = rowPoints.map { point ->
                    val proj = (point.x * secondaryDirection.x + point.y * secondaryDirection.y)
                    Pair(point, proj)
                }.sortedBy { it.second }
                
                // 选择列代表点
                val colClusters = clusterByProjection(sortedByCol, columns)
                selectedPoints.addAll(colClusters.map { it.first().first })
            }
        }
        
        // 如果上述方法失败，退回到简单的前N个点选择
        if (selectedPoints.size < min(4, rows * columns)) {
            // 释放资源
            points.release()
            mean.release()
            eigenvectors.release()
            eigenvalues.release()
            
            // 简单地选择前rows*columns个点
            val sortedPoints = circles.sortedBy { it.x * 1000000 + it.y }
            return sortedPoints.take(rows * columns)
        }
        
        // 释放资源
        points.release()
        mean.release()
        eigenvectors.release()
        eigenvalues.release()
        
        return selectedPoints
    }
    
    /**
     * 根据投影值对点进行聚类
     */
    private fun clusterByProjection(
        projectedPoints: List<Pair<Point, Double>>, 
        clusterCount: Int
    ): List<List<Pair<Point, Double>>> {
        if (projectedPoints.isEmpty() || clusterCount <= 0) return emptyList()
        if (projectedPoints.size <= clusterCount) {
            return projectedPoints.map { listOf(it) }
        }
        
        val minProj = projectedPoints.first().second
        val maxProj = projectedPoints.last().second
        val range = maxProj - minProj
        val step = range / clusterCount
        
        val clusters = MutableList<MutableList<Pair<Point, Double>>>(clusterCount) { mutableListOf() }
        
        for (point in projectedPoints) {
            val clusterIdx = min(((point.second - minProj) / step).toInt(), clusterCount - 1)
            clusters[clusterIdx].add(point)
        }
        
        return clusters.filter { it.isNotEmpty() }
    }
    
    /**
     * 生成理想网格模型
     * @param rows 行数
     * @param columns 列数
     * @return 理想网格坐标点
     */
    private fun generateIdealGrid(rows: Int, columns: Int): List<org.opencv.core.Point> {
        val gridPoints = mutableListOf<org.opencv.core.Point>()
        
        // 使用归一化坐标，将孔间距设为1个单位
        val halfWidth = (columns - 1) / 2.0
        val halfHeight = (rows - 1) / 2.0
        
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = (c - halfWidth) * 1.0
                val y = (r - halfHeight) * 1.0
                gridPoints.add(org.opencv.core.Point(x, y))
            }
        }
        
        return gridPoints
    }
    
    /**
     * 生成目标像素坐标系下的理想网格
     * 与generateIdealGrid不同，这个函数生成的坐标点是在输出图像像素坐标系中的位置
     * @param rows 行数
     * @param columns 列数
     * @param outputWidth 输出图像宽度
     * @param outputHeight 输出图像高度
     * @return 目标像素坐标系下的网格坐标点
     */
    private fun generateTargetPixelGrid(
        rows: Int,
        columns: Int,
        outputWidth: Double,
        outputHeight: Double
    ): List<org.opencv.core.Point> {
        val gridPoints = mutableListOf<org.opencv.core.Point>()
        
        // 使用一定的边距，避免点靠近边缘
        val marginX = outputWidth * 0.1  // 边距为图像宽度的10%
        val marginY = outputHeight * 0.1 // 边距为图像高度的10%
        
        // 计算网格有效区域
        val effectiveWidth = outputWidth - 2 * marginX
        val effectiveHeight = outputHeight - 2 * marginY
        
        // 计算每个孔位间的距离
        val stepX = if (columns > 1) effectiveWidth / (columns - 1) else effectiveWidth
        val stepY = if (rows > 1) effectiveHeight / (rows - 1) else effectiveHeight
        
        // 生成网格点
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = marginX + c * stepX
                val y = marginY + r * stepY
                gridPoints.add(org.opencv.core.Point(x, y))
            }
        }
        
        return gridPoints
    }
    
    /**
     * 执行迭代优化，联合估计单应性矩阵和径向畸变参数
     * @param inputMat 输入图像
     * @param wellCenters 检测到的孔中心点
     * @param rows 孔阵行数
     * @param columns 孔阵列数
     * @return 校正后的图像
     */
    private fun performIterativeCorrection(
        inputMat: Mat,
        wellCenters: List<Point>,
        rows: Int,
        columns: Int
    ): Mat {
        Log.d(TAG, "开始执行迭代优化校正，检测到孔位点: ${wellCenters.size}")
        
        // 最小需要的点数
        val minPointsForCorrection = 4
        
        // 如果检测到的点不足，直接返回原图
        if (wellCenters.size < minPointsForCorrection) {
            Log.e(TAG, "检测到的点数量不足以进行校正 (${wellCenters.size} < $minPointsForCorrection)")
            return inputMat.clone()
        }
        
        // 获取转换到原始图像坐标系的点
        val originalCoordinatePoints = convertPointsToOriginalCoordinates(wellCenters)
        
        // 使用改进的网格线提取算法
        val gridLines = extractGridLines(originalCoordinatePoints, rows, columns)
        
        // 如果网格线提取失败，直接返回原图
        if (gridLines.isEmpty()) {
            Log.e(TAG, "网格线提取失败，无法进行校正")
            return inputMat.clone()
        }
        
        // 初始化径向畸变参数
        val (k1, k2) = estimateRadialDistortionParameters(gridLines, 0.0, 0.0)
        Log.d(TAG, "估计的径向畸变参数: k1=$k1, k2=$k2")
        
        // 如果径向畸变参数几乎为零，直接返回原图
        if (Math.abs(k1) < 1e-4 && Math.abs(k2) < 1e-4) {
            Log.d(TAG, "径向畸变参数几乎为零，无需校正")
            return inputMat.clone()
        }
        
        // 设置相机矩阵
        val imageCenter = Point(inputMat.cols() / 2.0, inputMat.rows() / 2.0)
        val nominalFocalLength = Math.max(inputMat.cols(), inputMat.rows()).toDouble()
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, nominalFocalLength)  // fx
        cameraMatrix.put(1, 1, nominalFocalLength)  // fy
        cameraMatrix.put(0, 2, imageCenter.x)       // cx
        cameraMatrix.put(1, 2, imageCenter.y)       // cy
        
        // 创建畸变系数矩阵
        val distCoeffs = MatOfDouble(k1, k2, 0.0, 0.0)
        
        // 应用径向畸变校正（注释掉单应性变换部分，仅保留径向畸变校正）
        val undistortedMat = Mat()
        try {
            // 计算最优相机矩阵
            val optimalCameraMatrix = Calib3d.getOptimalNewCameraMatrix(
                cameraMatrix,
                distCoeffs,
                inputMat.size(),
                1.0,  // alpha参数：1.0表示所有像素都保留
                inputMat.size()
            )
            
            // 应用畸变校正
            Calib3d.undistort(
                inputMat,
                undistortedMat,
                cameraMatrix,
                distCoeffs,
                optimalCameraMatrix
            )
            
            Log.d(TAG, "径向畸变校正已应用")
            
            // 保存中间结果用于调试
            saveIntermediateResult(undistortedMat, "undistorted_only.jpg")
            
            // 释放资源
            optimalCameraMatrix.release()
        } catch (e: Exception) {
            Log.e(TAG, "径向畸变校正出错: ${e.message}")
            inputMat.copyTo(undistortedMat)  // 出错时使用原始图像
        }
        
        // 释放资源
        cameraMatrix.release()
        distCoeffs.release()
        
        return undistortedMat
    }
    
    /**
     * 估计径向畸变参数
     * @param gridLines 网格线集合
     * @param initialK1 初始k1值
     * @param initialK2 初始k2值
     * @return 估计的(k1, k2)参数对
     */
    private fun estimateRadialDistortionParameters(
        gridLines: List<List<Point>>,
        initialK1: Double,
        initialK2: Double
    ): Pair<Double, Double> {
        // 如果网格线不足，使用保守的初始估计
        if (gridLines.size < 5) {
            Log.w(TAG, "网格线数量不足(${gridLines.size} < 5)，使用默认初始畸变参数")
            // 轻微初始径向畸变，避免算法跳过校正
            return Pair(0.05, 0.01)
        }
        
        // 计算初始曲率
        val initialCurvature = calculateGridLinesCurvature(gridLines)
        Log.d(TAG, "初始网格线曲率: $initialCurvature")
        
        var bestK1 = initialK1
        var bestK2 = initialK2
        var minCurvature = initialCurvature
        
        // 设置更大范围的参数搜索空间
        val k1Range = listOf(-0.5, -0.3, -0.1, -0.05, -0.01, 0.0, 0.01, 0.05, 0.1, 0.3, 0.5) 
        val k2Range = listOf(-0.1, -0.05, -0.01, 0.0, 0.01, 0.05, 0.1)
        
        // 第一阶段：粗略搜索
        for (k1 in k1Range) {
            for (k2 in k2Range) {
                val curvature = calculateGridLinesDistortedCurvature(gridLines, k1, k2)
                if (curvature < minCurvature) {
                    bestK1 = k1
                    bestK2 = k2
                    minCurvature = curvature
                    Log.d(TAG, "找到更好的参数: k1=$k1, k2=$k2, 曲率=$curvature")
                }
            }
        }
        
        // 如果粗略搜索没有改进，退出
        if (Math.abs(bestK1 - initialK1) < 1e-6 && Math.abs(bestK2 - initialK2) < 1e-6) {
            Log.d(TAG, "粗略搜索没有找到更好的参数，保持初始值")
            return Pair(initialK1, initialK2)
        }
        
        // 第二阶段：细化搜索（在最佳参数附近）
        val refineStep1 = 0.02
        val refineStep2 = 0.005
        
        // 在最佳k1附近搜索
        for (delta in listOf(-refineStep1, refineStep1)) {
            val k1 = bestK1 + delta
            val curvature = calculateGridLinesDistortedCurvature(gridLines, k1, bestK2)
            if (curvature < minCurvature) {
                bestK1 = k1
                minCurvature = curvature
                Log.d(TAG, "细化k1: $k1, 曲率=$curvature")
            }
        }
        
        // 在最佳k2附近搜索
        for (delta in listOf(-refineStep2, refineStep2)) {
            val k2 = bestK2 + delta
            val curvature = calculateGridLinesDistortedCurvature(gridLines, bestK1, k2)
            if (curvature < minCurvature) {
                bestK2 = k2
                minCurvature = curvature
                Log.d(TAG, "细化k2: $k2, 曲率=$curvature")
            }
        }
        
        // 如果曲率减少显著，认为有效
        val improvementRatio = initialCurvature / minCurvature
        if (improvementRatio > 1.2) {
            Log.d(TAG, "畸变参数估计成功: k1=$bestK1, k2=$bestK2, 曲率改善率=${improvementRatio}")
            return Pair(bestK1, bestK2)
        } else {
            // 如果改善不显著且参数很小，可能意味着图像畸变很小
            if (Math.abs(bestK1) < 0.02 && Math.abs(bestK2) < 0.01) {
                Log.d(TAG, "曲率改善不显著且参数很小，可能图像无明显畸变，使用零值")
                return Pair(0.0, 0.0)
            }
            return Pair(bestK1, bestK2)
        }
    }
    
    /**
     * 计算网格线的曲率
     * @param gridLines 网格线集合
     * @return 曲率度量值
     */
    private fun calculateGridLinesCurvature(gridLines: List<List<Point>>): Double {
        var totalCurvature = 0.0
        var lineCount = 0
        
        for (line in gridLines) {
            if (line.size < 3) continue  // 至少需要3个点来衡量曲率
            
            totalCurvature += calculateLineCurvature(line)
            lineCount++
        }
        
        return if (lineCount > 0) totalCurvature / lineCount else Double.MAX_VALUE
    }
    
    /**
     * 计算应用畸变后的网格线曲率
     * @param gridLines 网格线集合
     * @param k1 径向畸变参数k1
     * @param k2 径向畸变参数k2
     * @return 曲率度量值
     */
    private fun calculateGridLinesDistortedCurvature(
        gridLines: List<List<Point>>,
        k1: Double,
        k2: Double
    ): Double {
        var totalCurvature = 0.0
        var lineCount = 0
        
        for (line in gridLines) {
            if (line.size < 3) continue
            
            // 应用径向畸变模型
            val distortedLine = applyRadialDistortion(line, k1, k2)
            totalCurvature += calculateLineCurvature(distortedLine)
            lineCount++
        }
        
        return if (lineCount > 0) totalCurvature / lineCount else Double.MAX_VALUE
    }
    
    /**
     * 应用径向畸变模型到点集
     * @param points 输入点集
     * @param k1 径向畸变参数k1
     * @param k2 径向畸变参数k2
     * @return 畸变后的点集
     */
    private fun applyRadialDistortion(
        points: List<Point>,
        k1: Double,
        k2: Double
    ): List<Point> {
        // 假设畸变中心是图像中心
        val cx = 0.5
        val cy = 0.5
        
        return points.map { point ->
            // 归一化坐标
            val x = point.x - cx
            val y = point.y - cy
            
            // 计算径向距离的平方
            val r2 = x * x + y * y
            val r4 = r2 * r2
            
            // 应用径向畸变
            val distortionFactor = 1.0 + k1 * r2 + k2 * r4
            
            // 计算畸变后的坐标
            val xDistorted = cx + x * distortionFactor
            val yDistorted = cy + y * distortionFactor
            
            Point(xDistorted, yDistorted)
        }
    }
    
    /**
     * 计算单条线的曲率
     * @param points 构成线的点集
     * @return 曲率度量值
     */
    private fun calculateLineCurvature(points: List<Point>): Double {
        if (points.size < 3) return 0.0
        
        val first = points.first()
        val last = points.last()
        
        // 计算首尾连线
        val lineVec = Point(last.x - first.x, last.y - first.y)
        val lineLength = sqrt(lineVec.x * lineVec.x + lineVec.y * lineVec.y)
        
        if (lineLength < 1e-6) return 0.0
        
        // 单位向量
        val unitVec = Point(lineVec.x / lineLength, lineVec.y / lineLength)
        
        // 计算中间点到直线的距离平方和
        var sumSquaredDistances = 0.0
        for (i in 1 until points.size - 1) {
            val point = points[i]
            
            // 向量：从起点到当前点
            val vec = Point(point.x - first.x, point.y - first.y)
            
            // 向量在直线上的投影长度
            val projection = vec.x * unitVec.x + vec.y * unitVec.y
            
            // 投影点坐标
            val projPoint = Point(
                first.x + unitVec.x * projection,
                first.y + unitVec.y * projection
            )
            
            // 点到直线的距离
            val distance = sqrt(
                (point.x - projPoint.x) * (point.x - projPoint.x) +
                (point.y - projPoint.y) * (point.y - projPoint.y)
            )
            
            sumSquaredDistances += distance * distance
        }
        
        // 归一化曲率：距离平方和 / 点数
        return sumSquaredDistances / (points.size - 2)
    }
    
    /**
     * 保存点列表到文件（用于调试）
     */
    private fun savePointsToFile(points: List<Point>, fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
            FileOutputStream(file).use { output ->
                val writer = output.writer()
                writer.write("x,y\n")
                
                for (point in points) {
                    writer.write("${point.x},${point.y}\n")
                }
                
                writer.flush()
            }
            Log.d(TAG, "点列表已保存到: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存点列表失败: ${e.message}")
        }
    }

    /**
     * 从模型输出IValue中提取Tensor
     */
    private fun getOutputTensor(modelOutput: IValue): org.pytorch.Tensor {
        return try {
            modelOutput.toTensor()
        } catch (e: Exception) {
            try {
                val tuple = modelOutput.toTuple()
                if (tuple.isNotEmpty()) {
                    tuple[0].toTensor()
                } else {
                    throw Exception("模型输出为空元组")
                }
            } catch (e2: Exception) {
                throw Exception("无法处理模型输出，既不是张量也不是预期的元组格式", e2)
            }
        }
    }
    
    /**
     * 解析原始检测结果并应用置信度阈值 (坐标在模型输入空间, e.g., 1280x1280)
     */
    private fun parseRawDetections(detectionsTensor: org.pytorch.Tensor, confidenceThreshold: Float): MutableList<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val output = detectionsTensor.dataAsFloatArray
        val outputShape = detectionsTensor.shape()
        
        if (outputShape.size >= 2) {
            val numDetections = outputShape[1].toInt()
            // 确保元素数量至少为6 (xc, yc, w, h, conf, cls)
            val elementsPerDetection = if (outputShape.size >= 3) outputShape[2].toInt().coerceAtLeast(6) else 6
            
            for (i in 0 until numDetections) {
                val offset = i * elementsPerDetection
                // 至少需要前5个元素 (xc, yc, w, h, conf)
                if (offset + 4 < output.size) { 
                    val confidence = output[offset + 4]
                    if (confidence >= confidenceThreshold) {
                        // 解析 xywh
                        val xc = output[offset]
                        val yc = output[offset + 1]
                        val w = output[offset + 2]
                        val h = output[offset + 3]
                        val cls = if(elementsPerDetection > 5 && offset + 5 < output.size) output[offset + 5] else 0f
                        
                        // 将 xywh 转换为 xyxy (左上角和右下角坐标)
                        val x1 = xc - w / 2
                        val y1 = yc - h / 2
                        val x2 = xc + w / 2
                        val y2 = yc + h / 2
                        
                        // 添加基本有效性检查 (宽度和高度应为正)
                        if (w <= 0 || h <= 0) continue

                        detections.add(RawDetection(x1, y1, x2, y2, confidence, cls.toInt()))
                    }
                }
            }
        }
        return detections
    }
    
    /**
     * 应用非极大值抑制 (NMS) 算法
     */
    private fun applyNMS(detections: List<RawDetection>, iouThreshold: Float): MutableList<RawDetection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<RawDetection>()
        
        if (sortedDetections.isEmpty()) return result
        
        val selected = BooleanArray(sortedDetections.size) { false }
        
        for (i in sortedDetections.indices) {
            if (selected[i]) continue
            
            result.add(sortedDetections[i])
            selected[i] = true
            
            val rect1 = sortedDetections[i].getRectF()
            
            for (j in (i + 1) until sortedDetections.size) {
                if (selected[j]) continue
                
                val rect2 = sortedDetections[j].getRectF()
                val iou = calculateIoU(rect1, rect2)
                
                if (iou > iouThreshold) {
                    selected[j] = true // 抑制重叠框
                }
            }
        }
        
        return result
    }
    
    /**
     * 计算两个矩形的IoU (Intersection over Union)
     */
    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(rect1, rect2)) {
            return 0f
        }
        
        val intersectionArea = intersection.width() * intersection.height()
        val area1 = rect1.width() * rect1.height()
        val area2 = rect2.width() * rect2.height()
        
        // 计算IoU
        val unionArea = area1 + area2 - intersectionArea
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * 坐标转换：将NMS后的检测框从模型输入空间映射到原始图像空间
     */
    private fun scaleBoxes(nmsResults: List<RawDetection>, letterboxInfo: LetterboxInfo, originalWidth: Int, originalHeight: Int): MutableList<CorrectionWellDetection> {
        val scaledDetections = mutableListOf<CorrectionWellDetection>()
        val originalWidthF = originalWidth.toFloat()
        val originalHeightF = originalHeight.toFloat()
        
        for ((index, detection) in nmsResults.withIndex()) {
            val x1 = detection.x1
            val y1 = detection.y1
            val x2 = detection.x2
            val y2 = detection.y2
            
            // 转换坐标：从Letterbox空间转回原始图像空间
            val scaledX1 = ((x1 - letterboxInfo.paddingX) / letterboxInfo.scale).coerceIn(0f, originalWidthF)
            val scaledY1 = ((y1 - letterboxInfo.paddingY) / letterboxInfo.scale).coerceIn(0f, originalHeightF)
            val scaledX2 = ((x2 - letterboxInfo.paddingX) / letterboxInfo.scale).coerceIn(0f, originalWidthF)
            val scaledY2 = ((y2 - letterboxInfo.paddingY) / letterboxInfo.scale).coerceIn(0f, originalHeightF)
            
            // 过滤掉那些在转换后宽度或高度几乎为零的框
            if (scaledX2 - scaledX1 < 1f || scaledY2 - scaledY1 < 1f) {
                continue
            }
            
            val rectF = RectF(scaledX1, scaledY1, scaledX2, scaledY2)
            scaledDetections.add(CorrectionWellDetection(index, rectF, detection.confidence))
        }
        
        return scaledDetections
    }
    
    /**
     * 将检测到的矩形框转换为网格点
     */
    private fun convertDetectionsToPoints(detections: List<CorrectionWellDetection>): List<Point> {
        return detections.map { detection ->
            val centerX = (detection.rect.left + detection.rect.right) / 2
            val centerY = (detection.rect.top + detection.rect.bottom) / 2
            Point(centerX.toDouble(), centerY.toDouble())
        }
    }
    
    /**
     * 从检测到的孔位中提取网格线
     * @param points 孔位点
     * @param rows 行数
     * @param columns 列数
     * @return 提取的网格线集合
     */
    private fun extractGridLines(points: List<Point>, rows: Int, columns: Int): List<List<Point>> {
        // 如果点数太少，返回空列表
        if (points.size < min(rows, columns) * 2) {
            Log.w(TAG, "提取网格线失败：点数不足 (${points.size})")
            return emptyList()
        }

        val lines = mutableListOf<List<Point>>()
        
        try {
            // 收集所有X坐标和Y坐标
            val xCoords = points.map { it.x }
            val yCoords = points.map { it.y }
            
            // 使用K-means聚类算法进行行列分组
            val rowClusters = kMeansClustering(yCoords, rows)
            val columnClusters = kMeansClustering(xCoords, columns)
            
            // 计算每个聚类中心
            val rowCenters = rowClusters.map { cluster -> cluster.average() }
            val columnCenters = columnClusters.map { cluster -> cluster.average() }
            
            Log.d(TAG, "网格聚类 - 行中心: $rowCenters")
            Log.d(TAG, "网格聚类 - 列中心: $columnCenters")
            
            // 提取行线
            for (rowCenter in rowCenters) {
                // 计算容差 - 使用平均行间距的30%
                val tolerance = if (rowCenters.size > 1) {
                    val avgRowGap = (rowCenters.maxOrNull()!! - rowCenters.minOrNull()!!) / (rowCenters.size - 1)
                    avgRowGap * 0.3
                } else {
                    20.0 // 默认容差
                }
                
                // 选取接近行中心的点
                val rowPoints = points.filter { 
                    Math.abs(it.y - rowCenter) < tolerance 
                }.sortedBy { it.x }
                
                if (rowPoints.size >= 3) { // 至少需要3个点定义一行
                    lines.add(rowPoints)
                    Log.d(TAG, "提取到行，y≈${rowCenter.toInt()}，包含 ${rowPoints.size} 个点")
                }
            }
            
            // 提取列线
            for (columnCenter in columnCenters) {
                // 计算容差 - 使用平均列间距的30%
                val tolerance = if (columnCenters.size > 1) {
                    val avgColGap = (columnCenters.maxOrNull()!! - columnCenters.minOrNull()!!) / (columnCenters.size - 1)
                    avgColGap * 0.3
                } else {
                    20.0 // 默认容差
                }
                
                // 选取接近列中心的点
                val columnPoints = points.filter { 
                    Math.abs(it.x - columnCenter) < tolerance 
                }.sortedBy { it.y }
                
                if (columnPoints.size >= 3) { // 至少需要3个点定义一列
                    lines.add(columnPoints)
                    Log.d(TAG, "提取到列，x≈${columnCenter.toInt()}，包含 ${columnPoints.size} 个点")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "网格线提取过程中出错: ${e.message}", e)
        }
        
        Log.d(TAG, "总共提取到 ${lines.size} 条网格线 (期望: ${rows + columns})")
        return lines
    }

    /**
     * 简单K-means聚类实现
     */
    private fun kMeansClustering(values: List<Double>, k: Int): List<List<Double>> {
        if (values.isEmpty() || k <= 0 || k > values.size) {
            return emptyList()
        }
        
        // 1. 初始化聚类中心 - 使用数据范围内的均匀间隔点
        val minValue = values.minOrNull()!!
        val maxValue = values.maxOrNull()!!
        val range = maxValue - minValue
        
        val centers = (0 until k).map { i ->
            minValue + range * (i + 0.5) / k
        }.toMutableList()
        
        // 2. 迭代聚类
        val maxIterations = 50
        var changed = true
        var iteration = 0
        
        val clusters = MutableList<MutableList<Double>>(k) { mutableListOf() }
        
        while (changed && iteration < maxIterations) {
            // 清空上一轮的聚类结果
            clusters.forEach { it.clear() }
            
            // 分配每个点到最近的中心
            for (value in values) {
                var minDist = Double.MAX_VALUE
                var closestCluster = 0
                
                for (i in centers.indices) {
                    val dist = Math.abs(value - centers[i])
                    if (dist < minDist) {
                        minDist = dist
                        closestCluster = i
                    }
                }
                
                clusters[closestCluster].add(value)
            }
            
            // 重新计算聚类中心
            changed = false
            for (i in centers.indices) {
                if (clusters[i].isNotEmpty()) {
                    val newCenter = clusters[i].average()
                    if (Math.abs(newCenter - centers[i]) > 0.001) {
                        centers[i] = newCenter
                        changed = true
                    }
                }
            }
            
            iteration++
        }
        
        return clusters
    }
    
    /**
     * 生成均匀网格点（当提取失败时使用）
     */
    private fun generateUniformGrid(width: Int, height: Int, rows: Int, columns: Int): List<Point> {
        val points = mutableListOf<Point>()
        
        // 使用一定的边距，避免点靠近边缘
        val marginX = width * 0.1  // 边距为图像宽度的10%
        val marginY = height * 0.1 // 边距为图像高度的10%
        
        // 计算步长
        val stepX = if (columns > 1) (width - 2 * marginX) / (columns - 1) else width - 2 * marginX
        val stepY = if (rows > 1) (height - 2 * marginY) / (rows - 1) else height - 2 * marginY
        
        // 生成网格点
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = marginX + c * stepX
                val y = marginY + r * stepY
                points.add(Point(x, y))
            }
        }
        
        Log.d(TAG, "生成均匀网格: ${points.size} 个点")
        return points
    }

    /**
     * 创建临时图像文件
     * @param prefix 文件名前缀
     * @return 临时文件
     */
    private fun createTempImageFile(prefix: String): File {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val imageFileName = "${prefix}${timeStamp}_"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        )
    }
    
    /**
     * 准备输入图像，使用Letterbox处理保持宽高比
     */
    private fun prepareInputBitmap(original: Bitmap): Bitmap {
        val modelInputSize = 1280 // YOLOv5s模型输入大小
        val originalWidth = original.width.toFloat()
        val originalHeight = original.height.toFloat()
        
        // 计算缩放比例（保持宽高比）
        val scale = min(modelInputSize / originalWidth, modelInputSize / originalHeight)
        val scaledWidth = (originalWidth * scale).toInt()
        val scaledHeight = (originalHeight * scale).toInt()
        
        // 计算填充
        val paddingX = (modelInputSize - scaledWidth) / 2f
        val paddingY = (modelInputSize - scaledHeight) / 2f
        
        // 保存Letterbox信息，用于后续坐标转换
        lastLetterboxInfo = LetterboxInfo(
            scale = scale,
            paddingX = paddingX,
            paddingY = paddingY,
            inputWidth = modelInputSize,
            inputHeight = modelInputSize
        )
        
        Log.d(TAG, 
            "Letterbox - 原始: ${originalWidth}x${originalHeight}, " +
            "缩放: $scale, 填充: paddingX=$paddingX, paddingY=$paddingY, " +
            "输出: ${scaledWidth}x${scaledHeight}")
        
        // 创建缩放后的Bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true)
        
        // 创建目标Bitmap（添加黑边填充）
        val outputBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        
        // 填充黑色背景
        canvas.drawColor(Color.BLACK)
        
        // 绘制缩放后的图像（居中）
        canvas.drawBitmap(
            scaledBitmap,
            paddingX,
            paddingY,
            null
        )
        
        return outputBitmap
    }

    /**
     * 将检测到的点从模型空间转换回原始图像空间
     */
    private fun convertPointsToOriginalCoordinates(points: List<Point>): List<Point> {
        if (lastLetterboxInfo == null) return points
        
        return points.map { point ->
            Point(
                (point.x - lastLetterboxInfo!!.paddingX) / lastLetterboxInfo!!.scale,
                (point.y - lastLetterboxInfo!!.paddingY) / lastLetterboxInfo!!.scale
            )
        }
    }

    /**
     * 将原始图像空间的点转换到模型空间
     */
    private fun convertPointsToModelSpace(points: List<Point>): List<Point> {
        if (lastLetterboxInfo == null) return points
        
        return points.map { point ->
            Point(
                point.x * lastLetterboxInfo!!.scale + lastLetterboxInfo!!.paddingX,
                point.y * lastLetterboxInfo!!.scale + lastLetterboxInfo!!.paddingY
            )
        }
    }
} 