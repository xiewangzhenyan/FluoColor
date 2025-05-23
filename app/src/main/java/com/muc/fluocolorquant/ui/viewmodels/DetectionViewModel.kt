package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
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
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.min

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
            android.util.Log.d("DetectionViewModel", "创建目录结果: $dirCreated (${directory.absolutePath})")
        }
        
        // 创建输出文件
        val outFile = File(directory, fileName)
        android.util.Log.d("DetectionViewModel", "输出文件路径: ${outFile.absolutePath}")
        
        // 如果文件已存在且不为空，则直接返回路径
        if (outFile.exists() && outFile.length() > 0) {
            android.util.Log.d("DetectionViewModel", "文件已存在，直接使用: ${outFile.absolutePath}")
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
                android.util.Log.d("DetectionViewModel", "已从assets复制文件到: ${outFile.absolutePath}, 大小: $totalBytes 字节")
            }
        }
        
        // 再次检查文件是否已成功创建
        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        } else {
            android.util.Log.e("DetectionViewModel", "文件复制后检查失败: ${outFile.absolutePath}")
            return null
        }
    } catch (e: Exception) {
        android.util.Log.e("DetectionViewModel", "复制文件时出错: ${e.message}", e)
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
 * 孔阵检测视图模型，负责孔阵的自动检测和状态管理
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
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

    init {
        // 在后台加载模型
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 模型路径 (修改为指向优化后的 .ptl 文件)
                val assetsModelPath = "models/best_lite.ptl"
                
                // 加载模型
                try {
                    android.util.Log.d("DetectionViewModel", "开始加载模型: $assetsModelPath")
                    val startTime = System.currentTimeMillis()
                    
                    // 获取模型文件路径
                    val path = assetFilePath(context, assetsModelPath)
                    if (path != null) {
                        // 验证文件是否存在
                        val modelFile = File(path)
                        if (modelFile.exists() && modelFile.length() > 0) {
                            android.util.Log.d("DetectionViewModel", "模型文件准备就绪: $path, 大小: ${modelFile.length()} 字节")
                            model = LiteModuleLoader.load(path)
                            android.util.Log.d("DetectionViewModel", "成功使用LiteModuleLoader加载模型")
                        } else {
                            throw IOException("模型文件不存在或为空: $path")
                        }
                    } else {
                        throw IOException("无法提取模型文件: $assetsModelPath")
                    }
                    
                    val duration = System.currentTimeMillis() - startTime
                    android.util.Log.d("DetectionViewModel", "模型加载成功，耗时: ${duration}ms")
                } catch (e: Exception) {
                    android.util.Log.e("DetectionViewModel", "模型加载失败: ${e.message}", e)
                    throw e
                }
            } catch (e: Exception) {
                // 记录错误但不立即显示
                android.util.Log.e("DetectionViewModel", "初始化过程中出错: ${e.message}", e)
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
                        android.util.Log.e("DetectionViewModel", errorMsg)
                        throw Exception(errorMsg)
                    }
                }

                // 在IO线程上执行推理和后处理
                val finalDetections = withContext(Dispatchers.IO) {
                    try {
                        // 1. 准备输入（使用Letterbox处理）
                        val letterboxedBitmap = prepareInputBitmap(bitmap)
                        android.util.Log.d("DetectionViewModel", "准备输入完成，处理后图像大小: ${letterboxedBitmap.width}x${letterboxedBitmap.height}")
                        val letterboxInfo = lastLetterboxInfo ?: throw Exception("缺少Letterbox参数")
                        
                        // 2. 转换为PyTorch张量
                        val mean = floatArrayOf(0.0f, 0.0f, 0.0f)
                        val std = floatArrayOf(1.0f, 1.0f, 1.0f)
                        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(letterboxedBitmap, mean, std)
                        android.util.Log.d("DetectionViewModel", "张量转换完成，开始执行推理")

                        // 3. 执行推理
                        val startTime = System.currentTimeMillis()
                        val modelOutput = model!!.forward(IValue.from(inputTensor))
                        val duration = System.currentTimeMillis() - startTime
                        android.util.Log.d("DetectionViewModel", "推理完成，耗时: ${duration}ms")
                        
                        // 4. 获取原始输出张量
                        val outputTensor = getOutputTensor(modelOutput)
                        val outputShape = outputTensor.shape()
                        android.util.Log.d("DetectionViewModel", "原始检测结果形状: ${outputShape.contentToString()}") // e.g., [1, 100800, 6]
                        
                        // --- 后处理开始 (匹配Python流程) ---
                        
                        // 5. 解析原始输出 + 置信度过滤 (坐标仍在1280x1280空间)
                        // 使用Python默认置信度阈值 0.25
                        val confThreshold = 0.25f
                        val rawDetections = parseRawDetections(outputTensor, confThreshold)
                        android.util.Log.d("DetectionViewModel", "通过置信度阈值 (${confThreshold}) 的检测数量: ${rawDetections.size}")
                        
                        // 6. 应用非极大值抑制 (NMS) (坐标仍在1280x1280空间)
                        // 使用Python默认IoU阈值 0.45
                        val iouThreshold = 0.45f
                        val nmsResults = applyNMS(rawDetections, iouThreshold)
                        android.util.Log.d("DetectionViewModel", "NMS (${iouThreshold}) 后检测数量: ${nmsResults.size}")
                        
                        // 7. 坐标转换: 将NMS后的结果从1280x1280空间映射到原始图像空间
                        val scaledDetections = scaleBoxes(nmsResults, letterboxInfo, bitmap.width, bitmap.height)
                        android.util.Log.d("DetectionViewModel", "坐标转换后最终检测数量: ${scaledDetections.size}")
                        
                        // --- 后处理结束 ---
                        
                        // 返回最终处理结果
                        // 如果需要限制最多96个，可以在这里对scaledDetections排序后取前96个
                        if (scaledDetections.size > 96) {
                            android.util.Log.d("DetectionViewModel", "检测数量超过96，按置信度取前96个")
                            scaledDetections.sortByDescending { it.confidence }
                            scaledDetections.take(96)
                        } else {
                            scaledDetections
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DetectionViewModel", "推理或后处理过程中出错: ${e.message}", e)
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
            android.util.Log.d("DetectionViewModel", "尝试直接将输出转换为张量")
            modelOutput.toTensor()
        } catch (e: Exception) {
            android.util.Log.d("DetectionViewModel", "直接转换失败，尝试作为元组处理: ${e.message}")
            try {
                val tuple = modelOutput.toTuple()
                android.util.Log.d("DetectionViewModel", "成功获取元组，元素数量: ${tuple.size}")
                if (tuple.isNotEmpty()) {
                    tuple[0].toTensor()
                } else {
                    throw Exception("模型输出为空元组")
                }
            } catch (e2: Exception) {
                android.util.Log.e("DetectionViewModel", "元组处理失败: ${e2.message}")
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
        
        android.util.Log.d("DetectionViewModel", 
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
            android.util.Log.d("DetectionViewModel", "解析原始输出，元素数量/检测: $elementsPerDetection")

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
                            android.util.Log.w("DetectionViewModel", "忽略无效框 (w=$w, h=$h): index=$i")
                            continue
                        }

                        // 记录转换前后的坐标（调试用）
                        if (detections.size < 10) { // 只记录前10个通过阈值的
                             android.util.Log.d("DetectionViewModel", 
                                "原始输出(xywh)#${detections.size}: xc=${xc.toInt()}, yc=${yc.toInt()}, w=${w.toInt()}, h=${h.toInt()} => xyxy: x1=${x1.toInt()}, y1=${y1.toInt()}, x2=${x2.toInt()}, y2=${y2.toInt()}, conf=$confidence")
                        }
                        
                        detections.add(RawDetection(x1, y1, x2, y2, confidence, cls.toInt()))
                    }
                }
            }
        } else {
            android.util.Log.w("DetectionViewModel", "输出形状异常: ${outputShape.contentToString()}")
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
                 android.util.Log.d("DetectionViewModel", "忽略转换后尺寸过小的框: id=$index, ($scaledX1,$scaledY1,$scaledX2,$scaledY2)")
                 continue
            }
            
            val rectF = RectF(scaledX1, scaledY1, scaledX2, scaledY2)
            
            // 记录前10个转换后的详细信息
            if (index < 10) {
                android.util.Log.d("DetectionViewModel", 
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
     * 选择孔位
     */
    fun selectWell(index: Int?) {
        _selectedWellIndex.value = index
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
     * 保存检测结果，以便后续处理
     */
    fun saveDetectionResults(): List<RectF>? {
        val currentState = _detectionState.value
        return if (currentState is DetectionState.Success) {
            currentState.detections.map { it.rect }
        } else {
            null
        }
    }
} 
 