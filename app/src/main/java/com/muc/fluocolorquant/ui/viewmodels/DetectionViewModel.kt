package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.WellResult
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.repository.ProjectRepository
import com.muc.fluocolorquant.data.repository.WellResultRepository
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f

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
            Log.d("DetectionViewModel", "创建目录结果: $dirCreated (${directory.absolutePath})")
        }
        
        // 创建输出文件
        val outFile = File(directory, fileName)
        Log.d("DetectionViewModel", "输出文件路径: ${outFile.absolutePath}")
        
        // 如果文件已存在且不为空，则直接返回路径
        if (outFile.exists() && outFile.length() > 0) {
            Log.d("DetectionViewModel", "文件已存在，直接使用: ${outFile.absolutePath}")
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
                Log.d("DetectionViewModel", "已从assets复制文件到: ${outFile.absolutePath}, 大小: $totalBytes 字节")
            }
        }
        
        // 再次检查文件是否已成功创建
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        } else {
            Log.e("DetectionViewModel", "文件复制后检查失败: ${outFile.absolutePath}")
            return null
        }
    } catch (e: Exception) {
        Log.e("DetectionViewModel", "复制文件时出错: ${e.message}", e)
        return null
    }
}

/**
 * 孔阵检测数据类，存储单个孔位的信息
 * @param id 孔位ID
 * @param rect 孔位矩形区域
 * @param confidence 检测置信度
 */
data class WellDetection(
    val id: Int,
    var rect: RectF,
    val confidence: Float
)

/**
 * 增强型孔阵检测数据类，存储更多细节信息
 * @param id 孔位ID
 * @param rect 初始检测的矩形区域
 * @param circleX 精确圆心X坐标
 * @param circleY 精确圆心Y坐标
 * @param radius 圆半径
 * @param centerColor 中心区域平均颜色
 * @param confidence 检测置信度
 */
data class EnhancedWellDetection(
    val id: Int,
    var rect: RectF,
    var circleX: Float? = null,
    var circleY: Float? = null,
    var radius: Float? = null,
    var centerColor: Int? = null,
    val confidence: Float
)

/**
 * 孔阵检测视图模型，负责孔阵的自动检测和状态管理
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val wellResultRepository: WellResultRepository,
    private val wellResultDao: WellResultDao
) : ViewModel() {
    // 在类内部定义TAG常量
    companion object {
        private const val TAG = "DetectionViewModel"
    }

    // 定义检测状态
    sealed class DetectionState {
        object Idle : DetectionState()
        object Loading : DetectionState()
        data class Success(val detections: List<WellDetection>) : DetectionState()
        data class Error(val message: String) : DetectionState()
    }

    // 检测状态流
    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    // 原始图像
    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    // 选中的孔位
    private val _selectedWellIndex = MutableStateFlow<Int?>(null)
    val selectedWellIndex: StateFlow<Int?> = _selectedWellIndex.asStateFlow()

    // 当前项目信息
    private val _currentProject = MutableStateFlow<Project?>(null)
    val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()
    
    // 当前项目的最大孔位数（行×列）
    private val _maxWellCount = MutableStateFlow<Int>(96) // 默认为96，会根据项目信息更新
    val maxWellCount: StateFlow<Int> = _maxWellCount.asStateFlow()

    // 加载PyTorch模型
    private var model: Module? = null
    
    // 存储预处理的Letterbox参数，用于后续坐标转换
    private data class LetterboxInfo(
        val scale: Float,
        val paddingX: Float,
        val paddingY: Float,
        val inputWidth: Int,
        val inputHeight: Int
    )
    private var lastLetterboxInfo: LetterboxInfo? = null

    // 初始化OpenCV
    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!OpenCVLoader.initDebug()) {
                    Log.e("DetectionViewModel", "OpenCV initialization failed")
                } else {
                    Log.d("DetectionViewModel", "OpenCV initialization successful")
                }
            } catch (e: Exception) {
                Log.e("DetectionViewModel", "OpenCV initialization error: ${e.message}")
            }
        }

        // 在后台加载模型
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 模型路径 (修改为指向优化后的 .ptl 文件)
                val assetsModelPath = "models/best_lite.ptl"
                
                // 加载模型
                try {
                    Log.d("DetectionViewModel", "开始加载模型: $assetsModelPath")
                    val startTime = System.currentTimeMillis()
                    
                    // 获取模型文件路径
                    val path = assetFilePath(context, assetsModelPath)
                    if (path != null) {
                        // 验证文件是否存在
                        val modelFile = File(path)
                        if (modelFile.exists() && modelFile.length() > 0) {
                            Log.d("DetectionViewModel", "模型文件准备就绪: $path, 大小: ${modelFile.length()} 字节")
                            model = LiteModuleLoader.load(path)
                            Log.d("DetectionViewModel", "成功使用LiteModuleLoader加载模型")
                        } else {
                            throw IOException("模型文件不存在或为空: $path")
                        }
                    } else {
                        throw IOException("无法提取模型文件: $assetsModelPath")
                    }
                    
                    val duration = System.currentTimeMillis() - startTime
                    Log.d("DetectionViewModel", "模型加载成功，耗时: ${duration}ms")
                } catch (e: Exception) {
                    Log.e("DetectionViewModel", "模型加载失败: ${e.message}", e)
                    throw e
                }
            } catch (e: Exception) {
                // 记录错误但不立即显示
                Log.e("DetectionViewModel", "初始化过程中出错: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    /**
     * 从URI加载图像
     */
    fun loadImage(imageUri: String?) {
        if (imageUri == null) {
            _detectionState.value = DetectionState.Error("图像URI为空")
            return
        }

        viewModelScope.launch {
            try {
                val uri = Uri.parse(imageUri)
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("无法打开图像")
                    BitmapFactory.decodeStream(inputStream).also {
                        inputStream.close()
                    }
                }
                _originalBitmap.value = bitmap
            } catch (e: Exception) {
                _detectionState.value = DetectionState.Error("加载图像失败: ${e.message}")
            }
        }
    }

    /**
     * 执行孔阵检测
     */
    fun detectWells(imageUri: String?) {
        if (imageUri == null) {
            _detectionState.value = DetectionState.Error("图像URI为空")
            return
        }

        viewModelScope.launch {
            try {
                // 设置为加载状态
                _detectionState.value = DetectionState.Loading

                // 加载原始图像
                if (_originalBitmap.value == null) {
                    loadImage(imageUri)
                    // 确保图像已加载
                    while (_originalBitmap.value == null) {
                        delay(100)
                    }
                }

                // 获取原始图像
                val bitmap = _originalBitmap.value ?: throw Exception("图像加载失败")

                // 确保模型已加载
                if (model == null) {
                    // 等待模型加载
                    var timeoutCounter = 0
                    while (model == null && timeoutCounter < 100) {
                        delay(100)
                        timeoutCounter++
                    }
                    
                    if (model == null) {
                        val errorMsg = "模型加载超时或失败，请检查是否支持您的设备架构"
                        Log.e("DetectionViewModel", errorMsg)
                        throw Exception(errorMsg)
                    }
                }

                // 在IO线程上执行推理和后处理
                val finalDetections = withContext(Dispatchers.IO) {
                    try {
                        // 1. 准备输入（使用Letterbox处理）
                        val letterboxedBitmap = prepareInputBitmap(bitmap)
                        Log.d("DetectionViewModel", "准备输入完成，处理后图像大小: ${letterboxedBitmap.width}x${letterboxedBitmap.height}")
                        val letterboxInfo = lastLetterboxInfo ?: throw Exception("缺少Letterbox参数")
                        
                        // 2. 转换为PyTorch张量
                        val mean = floatArrayOf(0.0f, 0.0f, 0.0f)
                        val std = floatArrayOf(1.0f, 1.0f, 1.0f)
                        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(letterboxedBitmap, mean, std)
                        Log.d("DetectionViewModel", "张量转换完成，开始执行推理")

                        // 3. 执行推理
                        val startTime = System.currentTimeMillis()
                        val modelOutput = model!!.forward(IValue.from(inputTensor))
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("DetectionViewModel", "推理完成，耗时: ${duration}ms")
                        
                        // 4. 获取原始输出张量
                        val outputTensor = getOutputTensor(modelOutput)
                        val outputShape = outputTensor.shape()
                        Log.d("DetectionViewModel", "原始检测结果形状: ${outputShape.contentToString()}") // e.g., [1, 100800, 6]
                        
                        // --- 后处理开始 (匹配Python流程) ---
                        
                        // 5. 解析原始输出 + 置信度过滤 (坐标仍在1280x1280空间)
                        // 使用Python默认置信度阈值 0.25
                        val confThreshold = 0.25f
                        val rawDetections = parseRawDetections(outputTensor, confThreshold)
                        Log.d("DetectionViewModel", "通过置信度阈值 (${confThreshold}) 的检测数量: ${rawDetections.size}")
                        
                        // 6. 应用非极大值抑制 (NMS) (坐标仍在1280x1280空间)
                        // 使用Python默认IoU阈值 0.45
                        val iouThreshold = 0.45f
                        val nmsResults = applyNMS(rawDetections, iouThreshold)
                        Log.d("DetectionViewModel", "NMS (${iouThreshold}) 后检测数量: ${nmsResults.size}")
                        
                        // 7. 坐标转换: 将NMS后的结果从1280x1280空间映射到原始图像空间
                        val scaledDetections = scaleBoxes(nmsResults, letterboxInfo, bitmap.width, bitmap.height)
                        Log.d("DetectionViewModel", "坐标转换后最终检测数量: ${scaledDetections.size}")
                        
                        // --- 后处理结束 ---
                        
                        // 返回最终处理结果
                        // 根据项目的行列限制检测数量
                        val currentMaxCount = _maxWellCount.value
                        if (scaledDetections.size > currentMaxCount) {
                            scaledDetections.sortByDescending { it.confidence }
                            scaledDetections.take(currentMaxCount)
                        } else {
                            scaledDetections
                        }
                    } catch (e: Exception) {
                        Log.e("DetectionViewModel", "推理或后处理过程中出错: ${e.message}", e)
                        throw e // 重新抛出异常，以便外层catch块处理
                    }
                }

                // 更新状态为成功
                _detectionState.value = DetectionState.Success(finalDetections)
            } catch (e: Exception) {
                e.printStackTrace()
                _detectionState.value = DetectionState.Error("检测失败: ${e.message}")
            }
        }
    }

    /**
     * 从模型输出IValue中提取Tensor
     */
    private fun getOutputTensor(modelOutput: IValue): org.pytorch.Tensor {
        return try {
            Log.d("DetectionViewModel", "尝试直接将输出转换为张量")
            modelOutput.toTensor()
        } catch (e: Exception) {
            Log.d("DetectionViewModel", "直接转换失败，尝试作为元组处理: ${e.message}")
            try {
                val tuple = modelOutput.toTuple()
                Log.d("DetectionViewModel", "成功获取元组，元素数量: ${tuple.size}")
                if (tuple.isNotEmpty()) {
                    tuple[0].toTensor()
                } else {
                    throw Exception("模型输出为空元组")
                }
            } catch (e2: Exception) {
                Log.e("DetectionViewModel", "元组处理失败: ${e2.message}")
                throw Exception("无法处理模型输出，既不是张量也不是预期的元组格式", e2)
            }
        }
    }

    /**
     * 准备输入图像，使用Letterbox处理保持宽高比
     */
    private fun prepareInputBitmap(original: Bitmap): Bitmap {
        val modelInputSize = 1280
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
        
        Log.d("DetectionViewModel", 
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
     * 解析原始检测结果并应用置信度阈值 (坐标在模型输入空间, e.g., 1280x1280)
     * 假设模型输出格式为 [center_x, center_y, width, height, confidence, class]
     */
    private fun parseRawDetections(detectionsTensor: org.pytorch.Tensor, confidenceThreshold: Float): MutableList<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val output = detectionsTensor.dataAsFloatArray
        val outputShape = detectionsTensor.shape()
        
        if (outputShape.size >= 2) {
            val numDetections = outputShape[1].toInt()
            // 确保元素数量至少为6 (xc, yc, w, h, conf, cls)
            val elementsPerDetection = if (outputShape.size >= 3) outputShape[2].toInt().coerceAtLeast(6) else 6
            Log.d("DetectionViewModel", "解析原始输出，元素数量/检测: $elementsPerDetection")

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
                        val cls = if(elementsPerDetection > 5 && offset + 5 < output.size) output[offset + 5] else 0f // 获取类别，如果存在
                        
                        // 将 xywh 转换为 xyxy
                        val x1 = xc - w / 2
                        val y1 = yc - h / 2
                        val x2 = xc + w / 2
                        val y2 = yc + h / 2
                        
                         // 添加基本有效性检查 (宽度和高度应为正)
                        if (w <= 0 || h <= 0) {
                            Log.w("DetectionViewModel", "忽略无效框 (w=$w, h=$h): index=$i")
                            continue
                        }

                        // 记录转换前后的坐标（调试用）
                        if (detections.size < 10) { // 只记录前10个通过阈值的
                             Log.d("DetectionViewModel", 
                                "原始输出(xywh)#${detections.size}: xc=${xc.toInt()}, yc=${yc.toInt()}, w=${w.toInt()}, h=${h.toInt()} => xyxy: x1=${x1.toInt()}, y1=${y1.toInt()}, x2=${x2.toInt()}, y2=${y2.toInt()}, conf=$confidence")
                        }
                        
                        detections.add(RawDetection(x1, y1, x2, y2, confidence, cls.toInt()))
                    }
                }
            }
        } else {
            Log.w("DetectionViewModel", "输出形状异常: ${outputShape.contentToString()}")
        }
        return detections
    }

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
     * 应用非极大值抑制 (NMS) 算法 (处理RawDetection列表)
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
     * 坐标转换：将NMS后的检测框从模型输入空间映射到原始图像空间
     */
    private fun scaleBoxes(nmsResults: List<RawDetection>, letterboxInfo: LetterboxInfo, originalWidth: Int, originalHeight: Int): MutableList<WellDetection> {
        val scaledDetections = mutableListOf<WellDetection>()
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
            
            // 过滤掉那些在转换后宽度或高度几乎为零的框 (可能对应于黑边区域的误检)
            if (scaledX2 - scaledX1 < 1f || scaledY2 - scaledY1 < 1f) {
                 Log.d("DetectionViewModel", "忽略转换后尺寸过小的框: id=$index, ($scaledX1,$scaledY1,$scaledX2,$scaledY2)")
                 continue
            }
            
            val rectF = RectF(scaledX1, scaledY1, scaledX2, scaledY2)
            
            // 记录前10个转换后的详细信息
            if (index < 10) {
                Log.d("DetectionViewModel", 
                    "缩放后检测#$index - " +
                    "原始坐标(1280): (${x1.toInt()},${y1.toInt()},${x2.toInt()},${y2.toInt()}), " +
                    "转换后: (${rectF.left},${rectF.top},${rectF.right},${rectF.bottom}), " +
                    "置信度: ${detection.confidence}")
            }
            
            scaledDetections.add(WellDetection(index, rectF, detection.confidence))
        }
        
        return scaledDetections
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
     * 选择孔位（通过数组索引）
     */
    fun selectWell(index: Int?) {
        _selectedWellIndex.value = index
    }
    
    /**
     * 通过ID选择孔位（解决位置移动后选择问题）
     */
    fun selectWellById(wellId: Int) {
        val currentState = _detectionState.value
        if (currentState is DetectionState.Success) {
            // 查找与给定ID匹配的孔位在当前数组中的索引
            val index = currentState.detections.indexOfFirst { it.id == wellId }
            if (index >= 0) {
                _selectedWellIndex.value = index
            }
        }
    }

    /**
     * 移动选中的孔位（带边界检查）
     */
    fun moveSelectedWell(dx: Float, dy: Float, imageBounds: RectF) {
        val index = _selectedWellIndex.value ?: return
        
        val currentState = _detectionState.value
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections.toMutableList()
            if (index >= 0 && index < detections.size) {
                val well = detections[index]
                
                // 计算新的矩形位置
                var newLeft = well.rect.left + dx
                var newTop = well.rect.top + dy
                var newRight = well.rect.right + dx
                var newBottom = well.rect.bottom + dy
                
                // 应用边界检查，确保检测框不超出图像范围
                if (newLeft < imageBounds.left) {
                    val offset = imageBounds.left - newLeft
                    newLeft += offset
                    newRight += offset
                }
                if (newTop < imageBounds.top) {
                    val offset = imageBounds.top - newTop
                    newTop += offset
                    newBottom += offset
                }
                if (newRight > imageBounds.right) {
                    val offset = newRight - imageBounds.right
                    newLeft -= offset
                    newRight -= offset
                }
                if (newBottom > imageBounds.bottom) {
                    val offset = newBottom - imageBounds.bottom
                    newTop -= offset
                    newBottom -= offset
                }
                
                val updatedRect = RectF(newLeft, newTop, newRight, newBottom)
                detections[index] = well.copy(rect = updatedRect)
                _detectionState.value = DetectionState.Success(detections)
            }
        }
    }

    /**
     * 移动选中的孔位（不带边界检查的原始方法，保持向后兼容）
     */
    fun moveSelectedWellUnconstrained(dx: Float, dy: Float) {
        val index = _selectedWellIndex.value ?: return
        
        val currentState = _detectionState.value
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections.toMutableList()
            if (index >= 0 && index < detections.size) {
                val well = detections[index]
                val updatedRect = RectF(
                    well.rect.left + dx,
                    well.rect.top + dy,
                    well.rect.right + dx,
                    well.rect.bottom + dy
                )
                detections[index] = well.copy(rect = updatedRect)
                _detectionState.value = DetectionState.Success(detections)
            }
        }
    }

    /**
     * 保存检测结果
     * @param detections 检测到的孔位列表
     * @param projectId 项目ID
     * @param confThreshold 置信度阈值
     * @param iouThreshold IoU阈值
     * @return 生成的runId，如果保存失败则返回null
     */
    suspend fun saveDetectionResults(
        detections: List<WellDetection>,
        projectId: String,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f
    ): String? {
        return try {
            android.util.Log.d(TAG, "开始保存检测结果，projectId: $projectId, 检测数量: ${detections.size}")
            
            // 调用仓库保存检测结果
            val runId = wellResultRepository.saveDetectionResults(
                projectId = projectId,
                detections = detections,
                confThreshold = confThreshold,
                iouThreshold = iouThreshold
            )
            
            android.util.Log.d(TAG, "检测结果保存成功，runId: $runId")
            
            // 如果有增强型检测结果，将其与runId关联并保存到数据库
            val enhancedResults = _enhancedDetections.value
            if (enhancedResults.isNotEmpty() && runId != null) {
                android.util.Log.d(TAG, "保存增强型检测结果，数量: ${enhancedResults.size}")
                
                // 保存增强型检测的颜色信息，仅保存检测到了圆心和颜色的信息
                val wellsWithColorInfo = enhancedResults
                    .filter { it.circleX != null && it.circleY != null && it.radius != null && it.centerColor != null }
                    .map { enhancedWell ->
                        WellResult(
                            runId = runId,
                            projectId = projectId,
                            wellIndex = enhancedWell.id,
                            predictedConcentration = null, // 暂未预测浓度
                            trueConcentration = null,
                            isStandard = false,
                            detectedRectLeft = enhancedWell.rect.left,
                            detectedRectTop = enhancedWell.rect.top,
                            detectedRectRight = enhancedWell.rect.right,
                            detectedRectBottom = enhancedWell.rect.bottom,
                            detectionConfidence = enhancedWell.confidence,
                            croppedImageIdentifier = null,
                        )
                    }
                
                // 更新已有孔位信息
                if (wellsWithColorInfo.isNotEmpty()) {
                    wellResultDao.updateWellResults(wellsWithColorInfo)
                    Log.d(TAG, "已保存 ${wellsWithColorInfo.size} 个增强型检测结果")
                }
            }
            
            runId
        } catch (e: Exception) {
            Log.e(TAG, "保存检测结果失败", e)
            null
        }
    }

    // 保存增强型检测结果
    private val _enhancedDetections = MutableStateFlow<List<EnhancedWellDetection>>(emptyList())
    val enhancedDetections: StateFlow<List<EnhancedWellDetection>> = _enhancedDetections.asStateFlow()
    
    /**
     * 执行增强型孔阵检测
     * 使用YOLOv5s进行初始检测，然后用霍夫圆变换精确定位圆孔
     */
    fun enhancedWellDetection(imageUri: String?) {
        if (imageUri == null) {
            _detectionState.value = DetectionState.Error("图像URI为空")
            return
        }

        viewModelScope.launch {
            try {
                // 设置为加载状态
                _detectionState.value = DetectionState.Loading

                // 加载原始图像
                if (_originalBitmap.value == null) {
                    loadImage(imageUri)
                    // 确保图像已加载
                    while (_originalBitmap.value == null) {
                        delay(100)
                    }
                }

                // 获取原始图像
                val bitmap = _originalBitmap.value ?: throw Exception("图像加载失败")

                // 确保模型已加载
                if (model == null) {
                    // 等待模型加载
                    var timeoutCounter = 0
                    while (model == null && timeoutCounter < 100) {
                        delay(100)
                        timeoutCounter++
                    }
                    
                    if (model == null) {
                        throw Exception("模型加载超时或失败")
                    }
                }

                // 执行YOLOv5s初步检测
                val initialDetections = withContext(Dispatchers.IO) {
                    val letterboxedBitmap = prepareInputBitmap(bitmap)
                    val letterboxInfo = lastLetterboxInfo ?: throw Exception("缺少Letterbox参数")
                    
                    val mean = floatArrayOf(0.0f, 0.0f, 0.0f)
                    val std = floatArrayOf(1.0f, 1.0f, 1.0f)
                    val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(letterboxedBitmap, mean, std)
                    
                    val modelOutput = model!!.forward(IValue.from(inputTensor))
                    val outputTensor = getOutputTensor(modelOutput)
                    
                    val confThreshold = 0.25f
                    val rawDetections = parseRawDetections(outputTensor, confThreshold)
                    
                    val iouThreshold = 0.45f
                    val nmsResults = applyNMS(rawDetections, iouThreshold)
                    
                    // 获取初步检测结果
                    val scaledDetections = scaleBoxes(nmsResults, letterboxInfo, bitmap.width, bitmap.height)
                    
                    // 根据项目的行列限制检测数量
                    val currentMaxCount = _maxWellCount.value
                    if (scaledDetections.size > currentMaxCount) {
                        // 按置信度排序并只保留前 currentMaxCount 个
                        scaledDetections.sortedByDescending { it.confidence }.take(currentMaxCount)
                    } else {
                        scaledDetections
                    }
                }
                
                // 进行霍夫圆变换和中心取色增强
                val enhancedDetections = withContext(Dispatchers.IO) {
                    enhanceDetectionsWithHoughCircles(initialDetections, bitmap)
                }
                
                // 将增强型检测结果转换为标准WellDetection用于显示和后续处理
                val finalDetections = enhancedDetections.mapIndexed { index, enhancedWell ->
                    // 如果检测到了圆，使用圆的边界矩形，否则使用原始矩形
                    val finalRect = if (enhancedWell.circleX != null && enhancedWell.circleY != null && enhancedWell.radius != null) {
                        val circleX = enhancedWell.circleX!!
                        val circleY = enhancedWell.circleY!!
                        val radius = enhancedWell.radius!!
                        RectF(
                            circleX - radius,
                            circleY - radius,
                            circleX + radius,
                            circleY + radius
                        )
                    } else {
                        enhancedWell.rect
                    }
                    
                    WellDetection(
                        id = enhancedWell.id,
                        rect = finalRect,
                        confidence = enhancedWell.confidence
                    )
                }
                
                // 再次检查最终结果是否超过最大孔位数量限制
                val currentMaxCount = _maxWellCount.value
                val limitedDetections = if (finalDetections.size > currentMaxCount) {
                    // 按置信度排序并只保留前 currentMaxCount 个
                    finalDetections.sortedByDescending { it.confidence }.take(currentMaxCount)
                } else {
                    finalDetections
                }
                
                // 更新状态为成功
                _detectionState.value = DetectionState.Success(limitedDetections)
                
                // 保存增强型检测结果，可用于将来的浓度分析
                // 同样限制增强型检测结果的数量
                _enhancedDetections.value = if (enhancedDetections.size > currentMaxCount) {
                    enhancedDetections.sortedByDescending { it.confidence }.take(currentMaxCount)
                } else {
                    enhancedDetections
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                _detectionState.value = DetectionState.Error("增强型检测失败: ${e.message}")
            }
        }
    }
    
    /**
     * 使用霍夫圆变换和中心取色增强检测结果
     * @param detections 初步检测结果
     * @param originalBitmap 原始图像
     * @return 增强后的检测结果
     */
    private fun enhanceDetectionsWithHoughCircles(
        detections: List<WellDetection>,
        originalBitmap: Bitmap
    ): List<EnhancedWellDetection> {
        val enhancedResults = mutableListOf<EnhancedWellDetection>()
        
        // 创建OpenCV Mat对象
        val rgbaMat = Mat()
        Utils.bitmapToMat(originalBitmap, rgbaMat)
        
        // 转换为灰度图像用于霍夫变换（使用CLAHE增强）
        // 灰度图增强
        val grayRaw = Mat()
        Imgproc.cvtColor(rgbaMat, grayRaw, Imgproc.COLOR_RGBA2GRAY)

        // 使用 CLAHE（对比度受限自适应直方图均衡）
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 4.0
        val grayMat = Mat()
        clahe.apply(grayRaw, grayMat)
        grayRaw.release()

        // 检查每个检测结果是否与已处理的区域重叠，用于解决同一区域检测多个圆的问题
        val processedAreas = mutableListOf<RectF>()
        
        for (detection in detections) {
            try {
                // 检查当前检测区域是否与已处理区域重叠过多
                val overlapThreshold = 0.7f // 70%的重叠阈值
                var shouldSkip = false
                for (processedArea in processedAreas) {
                    val intersection = RectF()
                    if (intersection.setIntersect(detection.rect, processedArea)) {
                        val intersectionArea = intersection.width() * intersection.height()
                        val detectionArea = detection.rect.width() * detection.rect.height()
                        if (intersectionArea / detectionArea > overlapThreshold) {
                            // 如果重叠面积超过阈值，跳过该区域检测
                            shouldSkip = true
                            break
                        }
                    }
                }
                
                if (shouldSkip) {
                    continue
                }
                
                // 创建增强型检测对象，初始包含原始检测信息
                val enhancedDetection = EnhancedWellDetection(
                    id = detection.id,
                    rect = detection.rect,
                    confidence = detection.confidence
                )
                
                // 1. 裁剪原始矩形区域，扩大约10%以确保包含完整的圆
                val expansionFactor = 0.03f
                val centerX = (detection.rect.left + detection.rect.right) / 2
                val centerY = (detection.rect.top + detection.rect.bottom) / 2
                val width = detection.rect.width() * (1 + expansionFactor)
                val height = detection.rect.height() * (1 + expansionFactor)
                
                // 确保裁剪区域不超出图像边界
                val cropLeft = (centerX - width / 2).coerceAtLeast(0f).toInt()
                val cropTop = (centerY - height / 2).coerceAtLeast(0f).toInt()
                val cropRight = (centerX + width / 2).coerceAtMost(originalBitmap.width.toFloat()).toInt()
                val cropBottom = (centerY + height / 2).coerceAtMost(originalBitmap.height.toFloat()).toInt()
                val cropWidth = cropRight - cropLeft
                val cropHeight = cropBottom - cropTop
                
                // 2. 提取感兴趣区域(ROI)
                val roi = Mat(grayMat, org.opencv.core.Rect(cropLeft, cropTop, cropWidth, cropHeight))
                
                // 3. 应用高斯模糊减少噪声
                Imgproc.GaussianBlur(roi, roi, Size(5.0, 5.0), 2.0, 2.0)
                
                // 4. 使用霍夫圆变换检测圆 (修改参数以解决多重检测问题)
                val circles = Mat()
                Imgproc.HoughCircles(
                    roi,
                    circles,
                    Imgproc.HOUGH_GRADIENT,
                    1.0,                // 分辨率比例
                    roi.rows() / 1.2,   // 进一步增大最小圆心距离，减少重复检测（从roi.rows()/1.5改为roi.rows()/1.2）
                    100.0,              // Canny边缘检测器的高阈值
                    30.0,               // 提高累加器阈值（从25.0提高到30.0），减少误检
                    min(roi.width(), roi.height()) / 6, // 最小半径
                    min(roi.width(), roi.height()) / 2  // 最大半径
                )
                
                // 5. 找到最合适的圆
                if (circles.cols() > 0) {
                    // 选择第一个检测到的圆（通常是最显著的）
                    val circleData = FloatArray(3)
                    circles.get(0, 0, circleData)
                    
                    // 圆心和半径（需要加上ROI偏移量转换为原图坐标）
                    val circleX = circleData[0] + cropLeft
                    val circleY = circleData[1] + cropTop
                    val radius = circleData[2]
                    
                    // 更新增强型检测信息
                    enhancedDetection.circleX = circleX
                    enhancedDetection.circleY = circleY
                    enhancedDetection.radius = radius
                    
                    // 将当前检测区域添加到已处理区域列表
                    processedAreas.add(RectF(
                        circleX - radius,
                        circleY - radius,
                        circleX + radius,
                        circleY + radius
                    ))
                    
                    // 6. 计算圆形中心区域的平均颜色
                    // 取半径的1/3作为中心区域
                    val centerRadius = radius / 3
                    val centerMask = Mat.zeros(rgbaMat.size(), CvType.CV_8UC1)
                    Imgproc.circle(
                        centerMask,
                        Point(circleX.toDouble(), circleY.toDouble()),
                        centerRadius.toInt(),
                        Scalar(255.0),
                        -1
                    )
                    
                    // 创建一个临时的彩色ROI
                    val colorRoi = Mat()
                    Core.bitwise_and(rgbaMat, rgbaMat, colorRoi, centerMask)
                    
                    // 计算非零区域的平均颜色
                    val meanColor = Core.mean(colorRoi, centerMask)
                    // 转换为ARGB颜色值 (从RGBA格式)
                    val avgColor = Color.argb(
                        255,
                        meanColor.`val`[0].roundToInt(),
                        meanColor.`val`[1].roundToInt(),
                        meanColor.`val`[2].roundToInt()
                    )
                    
                    enhancedDetection.centerColor = avgColor
                    
                    // 释放临时Mat
                    colorRoi.release()
                    centerMask.release()
                } else {
                    // 备选策略：霍夫未检测到圆，尝试轮廓拟合圆形
                    Log.d("DetectionViewModel", "霍夫圆检测失败，尝试轮廓拟合 - 孔位 #${detection.id}")
                    
                    // 二值化图像
                    val binary = Mat()
                    Imgproc.threshold(roi, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                    
                    // 查找轮廓
                    val contours = mutableListOf<MatOfPoint>()
                    Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    
                    // 选出最大轮廓
                    val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                    if (largestContour != null && Imgproc.contourArea(largestContour) > 20.0) {
                        val points2f = MatOfPoint2f(*largestContour.toArray())
                        val center = Point()
                        val radiusArr = FloatArray(1)
                        Imgproc.minEnclosingCircle(points2f, center, radiusArr)
                        
                        // 转换为原图坐标
                        val circleX = center.x + cropLeft
                        val circleY = center.y + cropTop
                        val radius = radiusArr[0]
                        
                        Log.d("DetectionViewModel", "轮廓拟合成功 - 孔位 #${detection.id}, 圆心: (${circleX.toInt()}, ${circleY.toInt()}), 半径: ${radius.toInt()}")
                        
                        // 更新检测对象
                        enhancedDetection.circleX = circleX.toFloat()
                        enhancedDetection.circleY = circleY.toFloat()
                        enhancedDetection.radius = radius
                        
                        // 继续取色 - 复用圆形检测后的取色逻辑
                        val centerRadius = radius / 3
                        val centerMask = Mat.zeros(rgbaMat.size(), CvType.CV_8UC1)
                        Imgproc.circle(
                            centerMask,
                            Point(circleX.toDouble(), circleY.toDouble()),
                            centerRadius.toInt(),
                            Scalar(255.0),
                            -1
                        )
                        
                        // 创建一个临时的彩色ROI
                        val colorRoi = Mat()
                        Core.bitwise_and(rgbaMat, rgbaMat, colorRoi, centerMask)
                        
                        // 计算非零区域的平均颜色
                        val meanColor = Core.mean(colorRoi, centerMask)
                        // 转换为ARGB颜色值 (从RGBA格式)
                        val avgColor = Color.argb(
                            255,
                            meanColor.`val`[0].roundToInt(),
                            meanColor.`val`[1].roundToInt(),
                            meanColor.`val`[2].roundToInt()
                        )
                        
                        enhancedDetection.centerColor = avgColor
                        
                        // 释放临时Mat
                        colorRoi.release()
                        centerMask.release()
                    } else {
                        Log.d("DetectionViewModel", "轮廓拟合失败 - 孔位 #${detection.id}, 未找到有效轮廓或轮廓过小")
                    }
                    
                    // 释放轮廓检测用的Mat
                    binary.release()
                    contours.forEach { it.release() }
                }
                
                // 释放临时Mat
                roi.release()
                circles.release()
                
                // 添加到结果列表
                enhancedResults.add(enhancedDetection)
                
            } catch (e: Exception) {
                Log.e("DetectionViewModel", "增强失败: 孔位 #${detection.id}", e)
                // 如果增强失败，至少保留原始检测信息
                enhancedResults.add(EnhancedWellDetection(
                    id = detection.id,
                    rect = detection.rect,
                    confidence = detection.confidence
                ))
            }
        }
        
        // 释放OpenCV Mat
        rgbaMat.release()
        grayMat.release()
        
        return enhancedResults
    }

    /**
     * 根据调整后的边界框更新所有孔位
     * 这个方法会按照调整后边界框的比例重新计算所有孔位的位置和大小
     */
    fun updateAllWellsWithAdjustedBox(adjustedBox: RectF) {
        val currentState = _detectionState.value
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections
            if (detections.isEmpty()) return
            
            // 计算当前所有孔位的边界框
            var currentMinX = Float.MAX_VALUE
            var currentMinY = Float.MAX_VALUE
            var currentMaxX = 0f
            var currentMaxY = 0f
            
            detections.forEach { well ->
                currentMinX = minOf(currentMinX, well.rect.left)
                currentMinY = minOf(currentMinY, well.rect.top)
                currentMaxX = maxOf(currentMaxX, well.rect.right)
                currentMaxY = maxOf(currentMaxY, well.rect.bottom)
            }
            
            val currentWidth = currentMaxX - currentMinX
            val currentHeight = currentMaxY - currentMinY
            
            // 计算缩放比例
            val scaleX = adjustedBox.width() / currentWidth
            val scaleY = adjustedBox.height() / currentHeight
            
            // 计算偏移量
            val offsetX = adjustedBox.left - currentMinX
            val offsetY = adjustedBox.top - currentMinY
            
            // 更新所有孔位
            val updatedDetections = detections.map { well ->
                // 计算新的矩形位置
                val newLeft = well.rect.left * scaleX + offsetX
                val newTop = well.rect.top * scaleY + offsetY
                val newRight = well.rect.right * scaleX + offsetX
                val newBottom = well.rect.bottom * scaleY + offsetY
                
                val updatedRect = RectF(newLeft, newTop, newRight, newBottom)
                well.copy(rect = updatedRect)
            }
            
            // 更新检测状态
            _detectionState.value = DetectionState.Success(updatedDetections)
            
            // 记录调整信息
            Log.d("DetectionViewModel", "已根据调整后的边界框更新所有孔位 - " +
                    "原始边界: ($currentMinX,$currentMinY,$currentMaxX,$currentMaxY), " +
                    "调整后: (${adjustedBox.left},${adjustedBox.top},${adjustedBox.right},${adjustedBox.bottom}), " +
                    "缩放比例: ($scaleX,$scaleY), 偏移量: ($offsetX,$offsetY)")
        }
    }
    
    /**
     * 使用调整后的边界框执行增强型孔阵检测
     * 这个方法会基于标准模式下调整好的边界框位置进行增强型检测
     */
    fun enhancedWellDetectionWithAdjustedBox(imageUri: String?, adjustedBox: RectF) {
        if (imageUri == null) {
            _detectionState.value = DetectionState.Error("图像URI为空")
            return
        }

        viewModelScope.launch {
            try {
                // 设置为加载状态
                _detectionState.value = DetectionState.Loading

                // 加载原始图像
                if (_originalBitmap.value == null) {
                    loadImage(imageUri)
                    // 确保图像已加载
                    while (_originalBitmap.value == null) {
                        delay(100)
                    }
                }

                // 获取原始图像
                val bitmap = _originalBitmap.value ?: throw Exception("图像加载失败")
                
                // 使用调整后的边界框创建初步检测结果
                val initialDetections = withContext(Dispatchers.IO) {
                    // 根据调整后的边界框创建孔位
                    // 计算每个孔位的大小
                    val currentProject = _currentProject.value
                    val rows = currentProject?.rows ?: 8
                    val columns = currentProject?.columns ?: 12
                    
                    val wellWidth = adjustedBox.width() / columns
                    val wellHeight = adjustedBox.height() / rows
                    
                    val detections = mutableListOf<WellDetection>()
                    var wellId = 0
                    
                    for (row in 0 until rows) {
                        for (col in 0 until columns) {
                            val left = adjustedBox.left + col * wellWidth
                            val top = adjustedBox.top + row * wellHeight
                            val right = left + wellWidth
                            val bottom = top + wellHeight
                            
                            val rect = RectF(left, top, right, bottom)
                            detections.add(WellDetection(wellId++, rect, 1.0f))
                        }
                    }
                    
                    detections
                }
                
                // 进行霍夫圆变换和中心取色增强
                val enhancedDetections = withContext(Dispatchers.IO) {
                    enhanceDetectionsWithHoughCircles(initialDetections, bitmap)
                }
                
                // 将增强型检测结果转换为标准WellDetection用于显示和后续处理
                val finalDetections = enhancedDetections.mapIndexed { index, enhancedWell ->
                    // 如果检测到了圆，使用圆的边界矩形，否则使用原始矩形
                    val finalRect = if (enhancedWell.circleX != null && enhancedWell.circleY != null && enhancedWell.radius != null) {
                        val circleX = enhancedWell.circleX!!
                        val circleY = enhancedWell.circleY!!
                        val radius = enhancedWell.radius!!
                        RectF(
                            circleX - radius,
                            circleY - radius,
                            circleX + radius,
                            circleY + radius
                        )
                    } else {
                        enhancedWell.rect
                    }
                    
                    WellDetection(
                        id = enhancedWell.id,
                        rect = finalRect,
                        confidence = enhancedWell.confidence
                    )
                }
                
                // 更新状态为成功
                _detectionState.value = DetectionState.Success(finalDetections)
                
                // 保存增强型检测结果，可用于将来的浓度分析
                _enhancedDetections.value = enhancedDetections
                
                Log.d("DetectionViewModel", "使用调整后的边界框完成增强型检测，检测到 ${finalDetections.size} 个孔位")
                
            } catch (e: Exception) {
                e.printStackTrace()
                _detectionState.value = DetectionState.Error("增强型检测失败: ${e.message}")
            }
        }
    }

    /**
     * 调整单个孔位的大小
     * @param wellId 孔位ID
     * @param newRect 新的矩形区域
     */
    fun adjustSingleWellSize(wellId: Int, newRect: RectF) {
        val currentState = _detectionState.value
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections.toMutableList()
            val index = detections.indexOfFirst { it.id == wellId }
            
            if (index >= 0) {
                // 更新孔位矩形
                detections[index] = detections[index].copy(rect = newRect)
                _detectionState.value = DetectionState.Success(detections)
                
                // 记录调整信息
                Log.d("DetectionViewModel", "已调整孔位 #$wellId 的大小 - " +
                        "新位置: (${newRect.left},${newRect.top},${newRect.right},${newRect.bottom})")
            }
        }
    }
    
    /**
     * 获取裁剪后的孔位图像，用于预览窗口
     * @param wellId 孔位ID
     * @param expansionFactor 扩展因子，默认为1.5（扩大50%）
     * @return 裁剪后的Bitmap，如果失败则返回null
     */
    fun getCroppedWellBitmap(wellId: Int, expansionFactor: Float = 1.5f): Bitmap? {
        val bitmap = _originalBitmap.value ?: return null
        val currentState = _detectionState.value
        
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections
            val wellIndex = detections.indexOfFirst { it.id == wellId }
            
            if (wellIndex >= 0) {
                val well = detections[wellIndex]
                
                // 计算裁剪区域（扩大以便于预览）
                val centerX = (well.rect.left + well.rect.right) / 2
                val centerY = (well.rect.top + well.rect.bottom) / 2
                val width = well.rect.width() * expansionFactor
                val height = well.rect.height() * expansionFactor
                
                // 确保裁剪区域不超出图像边界
                val cropLeft = (centerX - width / 2).coerceAtLeast(0f).toInt()
                val cropTop = (centerY - height / 2).coerceAtLeast(0f).toInt()
                val cropRight = (centerX + width / 2).coerceAtMost(bitmap.width.toFloat()).toInt()
                val cropBottom = (centerY + height / 2).coerceAtMost(bitmap.height.toFloat()).toInt()
                
                // 检查裁剪区域是否有效
                if (cropRight <= cropLeft || cropBottom <= cropTop) {
                    Log.e("DetectionViewModel", "无效的裁剪区域: ($cropLeft,$cropTop,$cropRight,$cropBottom)")
                    return null
                }
                
                // 创建裁剪后的Bitmap
                try {
                    return Bitmap.createBitmap(
                        bitmap,
                        cropLeft,
                        cropTop,
                        cropRight - cropLeft,
                        cropBottom - cropTop
                    )
                } catch (e: Exception) {
                    Log.e("DetectionViewModel", "裁剪Bitmap失败: ${e.message}", e)
                    return null
                }
            }
        }
        
        return null
    }

    /**
     * 获取选中孔位的ID
     * @return 选中孔位的ID，如果没有选中则返回null
     */
    fun getSelectedWellId(): Int? {
        val index = _selectedWellIndex.value ?: return null
        val currentState = _detectionState.value
        
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections
            if (index >= 0 && index < detections.size) {
                return detections[index].id
            }
        }
        
        return null
    }
    
    /**
     * 获取选中孔位的矩形
     * @return 选中孔位的矩形，如果没有选中则返回null
     */
    fun getSelectedWellRect(): RectF? {
        val index = _selectedWellIndex.value ?: return null
        val currentState = _detectionState.value
        
        if (currentState is DetectionState.Success) {
            val detections = currentState.detections
            if (index >= 0 && index < detections.size) {
                return detections[index].rect
            }
        }
        
        return null
    }

    /**
     * 保存检测结果的简化版本，仅返回检测框列表
     */
    fun saveDetectionResults(): List<RectF>? {
        val currentState = _detectionState.value
        return if (currentState is DetectionState.Success) {
            currentState.detections.map { it.rect }
        } else {
            null
        }
    }
    
    /**
     * 加载项目信息
     */
    fun loadProject(projectId: String) {
        viewModelScope.launch {
            try {
                val project = projectRepository.getProjectById(projectId)
                _currentProject.value = project
                
                // 根据项目的行列数更新最大孔位数
                project?.let {
                    _maxWellCount.value = it.rows * it.columns
                }
            } catch (e: Exception) {
                Log.e("DetectionViewModel", "加载项目失败: ${e.message}", e)
            }
        }
    }
} 
 