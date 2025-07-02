package com.muc.fluocolorquant.ui.viewmodels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
// import android.graphics.PointF // Not used directly, OpenCV's Point is used
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
import org.opencv.core.Point // Explicitly use OpenCV's Point
import org.opencv.imgproc.Imgproc
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
// import java.io.InputStream // Not used directly
import javax.inject.Inject
import kotlin.math.*

/**
 * 将资源文件提取到本地文件系统
 */
private fun assetFilePath(context: Context, assetName: String): String? {
    try {
        val lastSeparatorIndex = assetName.lastIndexOf('/')
        val fileName = if (lastSeparatorIndex != -1) assetName.substring(lastSeparatorIndex + 1) else assetName
        val directoryPath = if (lastSeparatorIndex != -1) assetName.substring(0, lastSeparatorIndex) else ""

        val targetDirectory = if (directoryPath.isEmpty()) {
            context.filesDir // Save in root of filesDir if no subdirectories in assetName
        } else {
            File(context.filesDir, directoryPath)
        }

        if (!targetDirectory.exists()) {
            val dirCreated = targetDirectory.mkdirs()
            Log.d("ImageCorrectionViewModel", "创建目录结果: $dirCreated (${targetDirectory.absolutePath})")
            if (!dirCreated && !targetDirectory.isDirectory) { // Check if mkdirs failed and it's not already a directory
                Log.e("ImageCorrectionViewModel", "无法创建目录: ${targetDirectory.absolutePath}")
                return null
            }
        }

        val outFile = File(targetDirectory, fileName)
        Log.d("ImageCorrectionViewModel", "输出文件路径: ${outFile.absolutePath}")

        if (outFile.exists() && outFile.length() > 0) {
            Log.d("ImageCorrectionViewModel", "文件已存在，直接使用: ${outFile.absolutePath}")
            return outFile.absolutePath
        }

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
                Log.d("ImageCorrectionViewModel", "已从assets复制文件到: ${outFile.absolutePath}, 大小: $totalBytes 字节")
            }
        }

        if (outFile.exists() && outFile.length() > 0) {
            return outFile.absolutePath
        } else {
            Log.e("ImageCorrectionViewModel", "文件复制后检查失败: ${outFile.absolutePath}")
            return null
        }
    } catch (e: Exception) {
        Log.e("ImageCorrectionViewModel", "复制文件时出错 ($assetName): ${e.message}", e)
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

    private val TAG = "ImageCorrectionViewModel"

    sealed class CorrectionState {
        object Idle : CorrectionState()
        object Processing : CorrectionState()
        data class Success(val imageUri: Uri) : CorrectionState()
        data class Error(val message: String) : CorrectionState()
    }

    private val _correctionState = MutableStateFlow<CorrectionState>(CorrectionState.Idle)
    val correctionState: StateFlow<CorrectionState> = _correctionState.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _correctedBitmap = MutableStateFlow<Bitmap?>(null)
    val correctedBitmap: StateFlow<Bitmap?> = _correctedBitmap.asStateFlow()

    private val _currentProject = MutableStateFlow<Project?>(null)
    // val currentProject: StateFlow<Project?> = _currentProject.asStateFlow() // Not directly exposed if only used internally

    private var model: Module? = null
    private var lastLetterboxInfo: LetterboxInfo? = null

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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val assetsModelPath = "models/best_lite.ptl"
                Log.d(TAG, "开始加载模型: $assetsModelPath")
                val startTime = System.currentTimeMillis()
                val path = assetFilePath(context, assetsModelPath)
                if (path != null) {
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
                // Consider setting a state or notifying UI about model load failure
            }
        }
    }

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

    fun loadImage(imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    _originalBitmap.value = bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载图像失败: ${e.message}", e)
            }
        }
    }

    fun correctImageWithErrorHandling(
        imageUri: Uri,
        projectId: String,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            _correctionState.value = CorrectionState.Processing
            try {
                val project = _currentProject.value ?: projectRepository.getProjectById(projectId)
                if (project == null) {
                     _currentProject.value = projectRepository.getProjectById(projectId)
                     if(_currentProject.value == null) throw Exception("找不到项目信息 (ID: $projectId)")
                }


                val rows = _currentProject.value!!.rows
                val columns = _currentProject.value!!.columns

                val correctedImageUriResult = correctImage(imageUri, rows, columns)
                _correctionState.value = CorrectionState.Success(correctedImageUriResult)
                onSuccess(correctedImageUriResult)
            } catch (e: Exception) {
                Log.e(TAG, "图像矫正失败: ${e.message}", e)
                _correctionState.value = CorrectionState.Error(e.message ?: "未知错误")
                onError(e.message ?: "未知错误")
            }
        }
    }

    private suspend fun correctImage(imageUri: Uri, rows: Int, columns: Int): Uri = withContext(Dispatchers.IO) {
        try {
            val originalBitmapLocal = context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: throw IOException("无法从URI加载Bitmap")
            _originalBitmap.postValue(originalBitmapLocal) // Use postValue if on background thread

            val correctedBitmapResult = correctDistortion(originalBitmapLocal, rows, columns)
            _correctedBitmap.postValue(correctedBitmapResult)

            val correctedImageFile = createTempImageFile("corrected_")
            FileOutputStream(correctedImageFile).use { outputStream ->
                correctedBitmapResult.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                correctedImageFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "图像校正错误", e)
            throw e // Re-throw to be caught by correctImageWithErrorHandling
        }
    }

    private suspend fun correctDistortion(bitmap: Bitmap, rows: Int, columns: Int): Bitmap = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "开始图像畸变校正，图像尺寸: ${bitmap.width} x ${bitmap.height}, 孔阵: $rows x $columns")

        var waitCount = 0
        while (model == null && waitCount < 60) { // Increased wait time
            try {
                kotlinx.coroutines.delay(100) // Use coroutine delay
                waitCount++
            } catch (e: InterruptedException) {
                Log.e(TAG, "等待模型加载被中断", e)
                Thread.currentThread().interrupt() // Restore interrupt status
                break
            }
        }

        if (model == null) {
            Log.w(TAG, "模型未加载完成，将使用OpenCV备用方法进行孔位检测")
        } else {
            Log.d(TAG, "YOLOv5模型已加载，将使用模型进行孔位检测")
        }

        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        saveMatAsImage(inputMat, "debug_original_input_mat.jpg")


        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        val wellCenters = detectWellCenters(grayMat, rows, columns) // This is suspend

        if (wellCenters.isEmpty()) {
            Log.e(TAG, "未检测到任何孔位点，返回原始图像")
            inputMat.release()
            grayMat.release()
            return@withContext bitmap.copy(bitmap.config, true)
        }
        Log.d(TAG, "检测到 ${wellCenters.size} 个孔位点")
        // Ensure at least 4 points for homography
        if (wellCenters.size < min(rows * columns / 3, 4)) {
            Log.e(TAG, "检测到的孔位点数量不足 (${wellCenters.size})，可能导致畸变校正失败，返回原始图像")
            inputMat.release()
            grayMat.release()
            return@withContext bitmap.copy(bitmap.config, true)
        }

        val correctedMat = performIterativeCorrection(inputMat, wellCenters, rows, columns)

        val resultBitmap = Bitmap.createBitmap(
            correctedMat.cols(), correctedMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(correctedMat, resultBitmap)
        saveBitmapImage(resultBitmap, "debug_corrected_final_bitmap.jpg")


        inputMat.release()
        grayMat.release()
        correctedMat.release()

        val processingTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "图像畸变校正完成，处理耗时: $processingTime ms")
        return@withContext resultBitmap
    }

    private suspend fun performIterativeCorrection(
        originalMat: Mat,
        detectedWellCenters: List<Point>,
        rows: Int,
        columns: Int,
        maxIterations: Int = 10,
        convergenceThresholdK: Double = 1e-4, // Threshold for k parameters
        convergenceThresholdH: Double = 1e-3  // Threshold for Homography matrix (average element change)
    ): Mat = withContext(Dispatchers.IO) {
        Log.d(TAG, "Performing iterative correction. Detected points: ${detectedWellCenters.size}")
        val minPointsForHomography = 4
        if (detectedWellCenters.size < minPointsForHomography) {
            Log.w(TAG, "Not enough well centers (${detectedWellCenters.size}) for correction.")
            return@withContext originalMat.clone()
        }

        var currentK1 = 0.0
        var currentK2 = 0.0
        val imageCenter = Point(originalMat.cols() / 2.0, originalMat.rows() / 2.0)
        val nominalFocalLength = max(originalMat.cols(), originalMat.rows()).toDouble()
        
        // Nominal camera matrix (fx=fy=nominalFocalLength, cx=imageCenter.x, cy=imageCenter.y)
        val currentCameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        currentCameraMatrix.put(0, 0, nominalFocalLength)
        currentCameraMatrix.put(1, 1, nominalFocalLength)
        currentCameraMatrix.put(0, 2, imageCenter.x)
        currentCameraMatrix.put(1, 2, imageCenter.y)

        var currentHomography = Mat.eye(3, 3, CvType.CV_64F)

        val outputWidth = originalMat.cols().toDouble()
        val outputHeight = originalMat.rows().toDouble()
        val targetPixelPointsList = generateTargetPixelGrid(rows, columns, outputWidth, outputHeight)
        if (targetPixelPointsList.size < minPointsForHomography) {
            Log.e(TAG, "Not enough target grid points generated.")
            currentCameraMatrix.release()
            currentHomography.release()
            return@withContext originalMat.clone()
        }
        
        val detectedWellCentersMat = MatOfPoint2f()
        detectedWellCentersMat.fromList(detectedWellCenters)

        for (iter in 0 until maxIterations) {
            Log.i(TAG, "Correction Iteration ${iter + 1}")

            val prevK1 = currentK1
            val prevK2 = currentK2
            val prevHomography = currentHomography.clone()

            // --- 1. Lens Undistort Points for Homography Estimation ---
            val pointsForHomographyEstimation = MatOfPoint2f()
            val distCoeffsForH = MatOfDouble(currentK1, currentK2, 0.0, 0.0) // p1, p2 = 0
            try {
                // Undistort original detected points using current k1, k2
                Calib3d.undistortPoints(detectedWellCentersMat, pointsForHomographyEstimation, currentCameraMatrix, distCoeffsForH, Mat(), currentCameraMatrix)
                 Log.d(TAG, "Iter ${iter+1}: Undistorted points for H. Input: ${detectedWellCentersMat.rows()}, Output: ${pointsForHomographyEstimation.rows()}")
            } catch (e: CvException) {
                Log.e(TAG, "Iter ${iter+1}: CvException in undistortPoints for H: ${e.message}")
                detectedWellCentersMat.copyTo(pointsForHomographyEstimation) // Fallback
            } finally {
                 distCoeffsForH.release()
            }
            savePointListAsCsv(pointsForHomographyEstimation.toList(), "debug_iter${iter+1}_pts_for_H.csv")


            // --- 2. Estimate Homography (H) ---
            // Ensure targetPixelPointsMat matches the size of pointsForHomographyEstimation for findHomography
            val targetPixelPointsMatSized = MatOfPoint2f()
            targetPixelPointsMatSized.fromList(targetPixelPointsList.take(pointsForHomographyEstimation.rows()))

            if (pointsForHomographyEstimation.rows() >= minPointsForHomography && targetPixelPointsMatSized.rows() >= minPointsForHomography) {
                 if (pointsForHomographyEstimation.checkVector(2) >= minPointsForHomography && targetPixelPointsMatSized.checkVector(2) >= minPointsForHomography) {
                    val H_new = Calib3d.findHomography(pointsForHomographyEstimation, targetPixelPointsMatSized, Calib3d.RANSAC, 15.0) // Increased RANSAC threshold
                    if (!H_new.empty() && H_new.rows() == 3 && H_new.cols() == 3) {
                        currentHomography.release()
                        currentHomography = H_new
                        Log.d(TAG, "Iter ${iter+1}: Homography updated.")
                    } else {
                        Log.w(TAG, "Iter ${iter+1}: Homography estimation failed or returned empty/invalid matrix. Keeping previous H.")
                        H_new?.release() // Release if not null and empty
                    }
                 } else {
                    Log.w(TAG, "Iter ${iter+1}: Not enough valid points for findHomography. Src: ${pointsForHomographyEstimation.checkVector(2)}, Dst: ${targetPixelPointsMatSized.checkVector(2)}")
                 }
            } else {
                Log.w(TAG, "Iter ${iter+1}: Not enough point rows for H input. Undistorted: ${pointsForHomographyEstimation.rows()}, Target: ${targetPixelPointsMatSized.rows()}")
            }
            pointsForHomographyEstimation.release()
            targetPixelPointsMatSized.release()


            // --- 3. Re-estimate Radial Distortion (k1, k2) using current H ---
            val pointsTransformedByH = MatOfPoint2f()
            if (!currentHomography.empty() && detectedWellCentersMat.checkVector(2) > 0) {
                try {
                    Core.perspectiveTransform(detectedWellCentersMat, pointsTransformedByH, currentHomography)
                     Log.d(TAG, "Iter ${iter+1}: Points transformed by H for K estimation. Input: ${detectedWellCentersMat.rows()}, Output: ${pointsTransformedByH.rows()}")
                } catch (e: CvException) {
                    Log.e(TAG, "Iter ${iter+1}: CvException in perspectiveTransform for K est: ${e.message}")
                    detectedWellCentersMat.copyTo(pointsTransformedByH) // Fallback
                }
            } else {
                 Log.w(TAG, "Iter ${iter+1}: currentHomography is empty or no detected points for K estimation.")
                detectedWellCentersMat.copyTo(pointsTransformedByH)
            }
             savePointListAsCsv(pointsTransformedByH.toList(), "debug_iter${iter+1}_pts_transformed_by_H.csv")


            if (pointsTransformedByH.rows() >= minPointsForHomography) { // Need enough points to form lines
                val (newK1, newK2) = refineKValuesIteratively(
                    pointsTransformedByH.toList(),
                    currentK1, currentK2,
                    currentCameraMatrix, // This camera matrix is for the space *after* H transform
                    rows, columns,
                    numRefinementIterations = 5,
                    stepSize = 0.005 // Smaller step size for K
                )
                currentK1 = newK1
                currentK2 = newK2
                Log.d(TAG, "Iter ${iter+1}: Updated k1=$currentK1, k2=$currentK2")
            }
            pointsTransformedByH.release()

            // --- Convergence Check ---
            val k1Diff = abs(currentK1 - prevK1)
            val k2Diff = abs(currentK2 - prevK2)
            var hDiff = Double.MAX_VALUE
            if (!currentHomography.empty() && !prevHomography.empty() && currentHomography.size() == prevHomography.size()) {
                val diffMatH = Mat()
                Core.absdiff(currentHomography, prevHomography, diffMatH)
                val sumDiffH = Core.sumElems(diffMatH).`val`[0]
                hDiff = sumDiffH / (currentHomography.rows() * currentHomography.cols())
                diffMatH.release()
            }

            Log.d(TAG, "Iter ${iter+1} Diffs: k1=$k1Diff, k2=$k2Diff, hAvg=$hDiff")
            if (k1Diff < convergenceThresholdK && k2Diff < convergenceThresholdK && hDiff < convergenceThresholdH) {
                Log.i(TAG, "Converged at iteration ${iter + 1}")
                prevHomography.release()
                break
            }
            prevHomography.release()
        }

        // --- Final Correction ---
        val finalCorrectedMat = Mat()
        val finalDistCoeffs = MatOfDouble(currentK1, currentK2, 0.0, 0.0)
        val lensUndistortedImage = Mat()

        Log.d(TAG, "Final Correction with K1=$currentK1, K2=$currentK2")

        if (abs(currentK1) > 1e-7 || abs(currentK2) > 1e-7) { // Threshold for applying distortion
            try {
                // Important: Use the original camera matrix for the original image
                val optimalNewCameraMatrix = Calib3d.getOptimalNewCameraMatrix(currentCameraMatrix, finalDistCoeffs, originalMat.size(), 1.0, originalMat.size())
                val map1 = Mat()
                val map2 = Mat()
                Imgproc.initUndistortRectifyMap(currentCameraMatrix, finalDistCoeffs, Mat(), optimalNewCameraMatrix, originalMat.size(), CvType.CV_32FC1, map1, map2)
                Imgproc.remap(originalMat, lensUndistortedImage, map1, map2, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0,0.0,0.0))
                Log.d(TAG, "Final lens undistortion applied using remap.")
                saveMatAsImage(lensUndistortedImage, "debug_final_lens_undistorted.jpg")
                optimalNewCameraMatrix.release()
                map1.release()
                map2.release()
            } catch (e: CvException) {
                Log.e(TAG, "CvException in final lens undistortion: ${e.message}")
                originalMat.copyTo(lensUndistortedImage) // Fallback
            }
        } else {
            Log.d(TAG, "Skipping final lens undistortion as K values are negligible.")
            originalMat.copyTo(lensUndistortedImage)
        }

        if (!currentHomography.empty()) {
            Imgproc.warpPerspective(
                lensUndistortedImage,
                finalCorrectedMat,
                currentHomography,
                Size(outputWidth, outputHeight),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0) // Fill with black for areas outside
            )
            Log.d(TAG, "Final perspective warp applied.")
        } else {
            Log.w(TAG, "Final homography is empty, using lens undistorted image as final.")
            lensUndistortedImage.copyTo(finalCorrectedMat)
        }
        saveMatAsImage(finalCorrectedMat, "debug_final_corrected_mat.jpg")

        currentCameraMatrix.release()
        currentHomography.release()
        detectedWellCentersMat.release()
        finalDistCoeffs.release()
        lensUndistortedImage.release()

        return@withContext finalCorrectedMat
    }

    private fun refineKValuesIteratively(
        pointsInHTransformedSpace: List<Point>, // Points after H, should form straight lines if lens distortion is removed
        initialK1: Double, initialK2: Double,
        cameraMatrixForUndistortion: Mat, // Camera matrix for the space of pointsInHTransformedSpace
        rows: Int, columns: Int,
        numRefinementIterations: Int,
        stepSizeK: Double // Renamed to avoid conflict
    ): Pair<Double, Double> {
        var bestK1 = initialK1
        var bestK2 = initialK2

        if (pointsInHTransformedSpace.size < 4) {
            Log.w(TAG, "Not enough points for K refinement: ${pointsInHTransformedSpace.size}")
            return Pair(bestK1, bestK2)
        }
        
        // Initial cost calculation
        var minCost = calculateTotalLineCurvature(
            pointsInHTransformedSpace,
            bestK1, bestK2, cameraMatrixForUndistortion, rows, columns
        )
        Log.d(TAG, "Initial K refinement cost: $minCost for K1=$bestK1, K2=$bestK2")


        for (i in 0 until numRefinementIterations) {
            var k1ChangedInIter = false
            var k2ChangedInIter = false
            // Try adjusting k1
            for (deltaK1Factor in listOf(-1.0, 1.0, -0.5, 0.5)) { // Try smaller steps too
                val currentK1Attempt = bestK1 + deltaK1Factor * stepSizeK
                val cost = calculateTotalLineCurvature(pointsInHTransformedSpace, currentK1Attempt, bestK2, cameraMatrixForUndistortion, rows, columns)
                if (cost < minCost) {
                    minCost = cost
                    bestK1 = currentK1Attempt
                    k1ChangedInIter = true
                    Log.d(TAG, "K Refinement iter ${i+1}: New best K1=$bestK1, Cost=$minCost")
                }
            }
            // Try adjusting k2
            for (deltaK2Factor in listOf(-1.0, 1.0, -0.5, 0.5)) { // k2 usually smaller impact/range
                val currentK2Attempt = bestK2 + deltaK2Factor * stepSizeK * 0.5 // Smaller step for k2
                val cost = calculateTotalLineCurvature(pointsInHTransformedSpace, bestK1, currentK2Attempt, cameraMatrixForUndistortion, rows, columns)
                if (cost < minCost) {
                    minCost = cost
                    bestK2 = currentK2Attempt
                    k2ChangedInIter = true
                    Log.d(TAG, "K Refinement iter ${i+1}: New best K2=$bestK2, Cost=$minCost")
                }
            }
            if (!k1ChangedInIter && !k2ChangedInIter) {
                 Log.d(TAG, "K Refinement iter ${i+1}: No improvement, stopping K refinement early.")
                 break // Stop if no improvement in this iteration
            }
        }
        Log.d(TAG, "Final K refinement: K1=$bestK1, K2=$bestK2, MinCost=$minCost")
        return Pair(bestK1, bestK2)
    }

    private fun calculateTotalLineCurvature(
        pointsInSpaceBeforeLensUndistortion: List<Point>, // These points are in some plane, lens distortion needs to be "removed"
        k1: Double, k2: Double,
        cameraMatrix: Mat, // Camera matrix for the space of pointsInSpaceBeforeLensUndistortion
        rows: Int, columns: Int
    ): Double {
        if (pointsInSpaceBeforeLensUndistortion.isEmpty()) return Double.MAX_VALUE

        val pointsToUndistortMat = MatOfPoint2f()
        pointsToUndistortMat.fromList(pointsInSpaceBeforeLensUndistortion)

        val undistortedPointsMat = MatOfPoint2f()
        val distCoeffs = MatOfDouble(k1, k2, 0.0, 0.0)

        try {
            // Undistort these points using the given k1, k2 and cameraMatrix
            Calib3d.undistortPoints(pointsToUndistortMat, undistortedPointsMat, cameraMatrix, distCoeffs, Mat(), cameraMatrix)
        } catch (e: CvException) {
            Log.e(TAG, "CvException in undistortPoints for curvature calc: ${e.message}")
            pointsToUndistortMat.copyTo(undistortedPointsMat) // Fallback
        }

        val undistortedPointsList = undistortedPointsMat.toList()
        pointsToUndistortMat.release()
        undistortedPointsMat.release()
        distCoeffs.release()

        if (undistortedPointsList.isEmpty()) return Double.MAX_VALUE

        // Extract grid lines from these "undistorted" points
        val lines = extractGridLines(undistortedPointsList, rows, columns)
        var totalCurvature = 0.0
        var lineCount = 0
        for (line in lines) {
            if (line.size >= 3) { // Need at least 3 points to define curvature
                totalCurvature += calculateSingleLineCurvatureScore(line)
                lineCount++
            }
        }
        
        val averageCurvature = if (lineCount > 0) totalCurvature / lineCount else Double.MAX_VALUE
        // Log.d(TAG, "Calculated avg curvature: $averageCurvature for k1=$k1, k2=$k2 with $lineCount lines")
        return averageCurvature
    }

    private fun calculateSingleLineCurvatureScore(points: List<Point>): Double {
        if (points.size < 3) return if (points.isEmpty()) Double.MAX_VALUE else 0.0

        val first = points.first()
        val last = points.last()

        val lineVecX = last.x - first.x
        val lineVecY = last.y - first.y
        val lineLengthSq = lineVecX * lineVecX + lineVecY * lineVecY

        if (lineLengthSq < 1e-9) { // Points are coincident or very close, treat as straight
            return 0.0
        }

        var sumSqDist = 0.0
        for (i in 1 until points.size - 1) {
            val p = points[i]
            // Distance from point p to line defined by first and last
            val num = abs(lineVecX * (first.y - p.y) - (first.x - p.x) * lineVecY)
            val dist = num / sqrt(lineLengthSq)
            sumSqDist += dist * dist
        }
        return if (points.size > 2) sumSqDist / (points.size - 2) else 0.0
    }


    private suspend fun detectWellCenters(grayMat: Mat, rows: Int, columns: Int): List<Point> = withContext(Dispatchers.IO){
        Log.d(TAG, "开始使用YOLOv5模型检测孔位中心点，行数: $rows, 列数: $columns")
        try {
            if (model == null) {
                Log.e(TAG, "YOLOv5模型未加载，尝试使用备用检测方法")
                return@withContext detectWellCentersWithOpenCV(grayMat, rows, columns)
            }

            val inputBitmap = Bitmap.createBitmap(grayMat.cols(), grayMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(grayMat, inputBitmap)
            saveBitmapImage(inputBitmap, "debug_yolo_input_gray_bitmap.jpg")


            val letterboxedBitmap = prepareInputBitmap(inputBitmap) // Uses lastLetterboxInfo
            val letterboxInfo = lastLetterboxInfo ?: throw Exception("Letterbox参数缺失")
            saveBitmapImage(letterboxedBitmap, "debug_yolo_letterboxed_bitmap.jpg")


            val mean = floatArrayOf(0.0f, 0.0f, 0.0f) // Assuming model trained on non-normalized images or normalized in model
            val std = floatArrayOf(1.0f, 1.0f, 1.0f)  // Or 255.0f if scaling to [0,1]
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(letterboxedBitmap, mean, std)

            val startTime = System.currentTimeMillis()
            val modelOutput = model!!.forward(IValue.from(inputTensor))
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "YOLOv5推理完成，耗时: ${inferenceTime}ms")

            val outputTensor = getOutputTensor(modelOutput)
            val confThreshold = 0.25f
            val rawDetections = parseRawDetections(outputTensor, confThreshold)
            Log.d(TAG, "原始检测数量: ${rawDetections.size}")

            val iouThreshold = 0.45f
            val nmsResults = applyNMS(rawDetections, iouThreshold)
            Log.d(TAG, "NMS后检测数量: ${nmsResults.size}")

            val wellDetections = scaleBoxes(nmsResults, letterboxInfo, grayMat.cols(), grayMat.rows())
            Log.d(TAG, "最终检测数量 (scaled): ${wellDetections.size}")

            var finalDetections = wellDetections
            val expectedWellCount = rows * columns
            if (wellDetections.size > expectedWellCount * 1.5) { // Allow some extra detections before strict cut
                finalDetections = wellDetections.sortedByDescending { it.confidence }
                    .take( (expectedWellCount * 1.2).toInt() ) // Take a bit more for robustness
                    .toMutableList()
                Log.d(TAG, "根据孔阵大小限制检测数量 (宽松): ${finalDetections.size}")
            }
            
            saveDetectionsOnBitmap(inputBitmap, finalDetections, "debug_yolo_final_detections.jpg")


            val centerPoints = convertDetectionsToPoints(finalDetections)
            Log.d(TAG, "YOLOv5最终孔位中心点数量: ${centerPoints.size}")

            return@withContext if (centerPoints.size < min(4, rows*columns/2) ) { // Stricter check for minimum points
                Log.w(TAG, "YOLOv5检测到的点过少 (${centerPoints.size})，使用备用方法")
                detectWellCentersWithOpenCV(grayMat, rows, columns)
            } else {
                centerPoints
            }

        } catch (e: Exception) {
            Log.e(TAG, "YOLOv5检测过程出错: ${e.message}", e)
            return@withContext detectWellCentersWithOpenCV(grayMat, rows, columns)
        }
    }

    private fun detectWellCentersWithOpenCV(grayMat: Mat, rows: Int, columns: Int): List<Point> {
        Log.d(TAG, "使用OpenCV备用方法检测孔位中心点")
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0 // Reduced clip limit
        clahe.tilesGridSize = Size(8.0,8.0)
        val enhancedMat = Mat()
        clahe.apply(grayMat, enhancedMat)
        saveMatAsImage(enhancedMat, "debug_opencv_clahe.jpg")


        Imgproc.GaussianBlur(enhancedMat, enhancedMat, Size(5.0, 5.0), 1.5) // Sigma 1.5
         saveMatAsImage(enhancedMat, "debug_opencv_gaussian.jpg")

        val binaryMat = Mat()
        Imgproc.adaptiveThreshold(
            enhancedMat, binaryMat, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, // INV for dark circles on light bg
            15, // Block size
            3.0  // C value
        )
        saveMatAsImage(binaryMat, "debug_opencv_binary.jpg")


        // Morphological operations to clean up noise and connect/separate components
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(binaryMat, binaryMat, Imgproc.MORPH_OPEN, kernel)
        saveMatAsImage(binaryMat, "debug_opencv_morph_open.jpg")
        // Imgproc.morphologyEx(binaryMat, binaryMat, Imgproc.MORPH_CLOSE, kernel, Point(-1.0,-1.0), 2) // More closing
        // saveMatAsImage(binaryMat, "debug_opencv_morph_close.jpg")


        val minEstimatedRadius = (min(enhancedMat.width(), enhancedMat.height()) / (max(rows,columns) * 2.5)).toInt().coerceAtLeast(5)
        val maxEstimatedRadius = (min(enhancedMat.width(), enhancedMat.height()) / (max(rows,columns) * 1.0)).toInt().coerceAtMost(100)

        Log.d(TAG, "OpenCV孔半径范围估计：最小 $minEstimatedRadius，最大 $maxEstimatedRadius 像素")

        var detectedCircles = mutableListOf<Point>()
        // Try Hough Circles first
        val circles = Mat()
        try {
            Imgproc.HoughCircles(
                enhancedMat, circles, Imgproc.HOUGH_GRADIENT,
                1.0, // dp (inverse ratio of accumulator resolution)
                minEstimatedRadius * 1.8, // minDist between centers
                75.0, // param1 (Canny high threshold)
                20.0, // param2 (accumulator threshold for center detection) - lowered
                minEstimatedRadius,
                maxEstimatedRadius
            )
            if (circles.cols() > 0) {
                Log.d(TAG, "OpenCV霍夫变换检测到圆的数量: ${circles.cols()}")
                for (i in 0 until circles.cols()) {
                    val circleParams = circles.get(0, i)
                    detectedCircles.add(Point(circleParams[0], circleParams[1]))
                }
                saveDetectionsOnMat(enhancedMat, detectedCircles, "debug_opencv_hough_circles.jpg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV霍夫圆检测失败: ${e.message}", e)
        } finally {
            circles.release()
        }


        // If HoughCircles not very successful, try contour detection
        if (detectedCircles.size < rows * columns / 2) {
            Log.d(TAG, "霍夫圆结果不足 (${detectedCircles.size})，尝试轮廓检测")
            detectedCircles.clear() // Clear previous results
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Log.d(TAG, "轮廓检测找到 ${contours.size} 个轮廓")

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val center = Point()
                val radiusArray = FloatArray(1)
                Imgproc.minEnclosingCircle(contour2f, center, radiusArray)
                val radius = radiusArray[0]

                val area = Imgproc.contourArea(contour)
                val circularity = if (radius > 0) (4 * PI * area) / ( (2 * PI * radius) * (2 * PI * radius) ) else 0.0

                if (radius >= minEstimatedRadius * 0.8 && radius <= maxEstimatedRadius * 1.2 && circularity > 0.6) {
                    detectedCircles.add(center)
                }
                contour2f.release()
                contour.release()
            }
            hierarchy.release()
            if (detectedCircles.isNotEmpty()) {
                 saveDetectionsOnMat(enhancedMat, detectedCircles, "debug_opencv_contour_circles.jpg")
            }
        }
        
        kernel.release()
        enhancedMat.release()
        binaryMat.release()

        Log.d(TAG, "OpenCV最终检测到孔位点: ${detectedCircles.size}")
        if (detectedCircles.size < min(4, rows * columns / 3)) {
            Log.w(TAG, "OpenCV检测到的圆太少 (${detectedCircles.size})，使用均匀网格")
            return generateUniformGrid(grayMat.width(), grayMat.height(), rows, columns)
        }

        // Filter and sort points if too many
        if (detectedCircles.size > rows * columns * 1.5) {
            // Simple sort by y then x, and take top N (can be improved with clustering)
            detectedCircles.sortBy { it.y * grayMat.width() + it.x } // Sort top-to-bottom, left-to-right
            detectedCircles = detectedCircles.take(rows * columns).toMutableList()
        }
         Log.d(TAG, "OpenCV过滤/排序后孔位点: ${detectedCircles.size}")
        return detectedCircles
    }
    
    // --- Helper functions for debugging ---
    private fun saveMatAsImage(mat: Mat, fileName: String) {
        if (!mat.empty()) {
            try {
                val tempBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(mat, tempBitmap)
                saveBitmapImage(tempBitmap, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "保存Mat为图像失败 ($fileName): ${e.message}")
            }
        } else {
            Log.w(TAG, "尝试保存空Mat ($fileName)")
        }
    }

    private fun saveBitmapImage(bitmap: Bitmap, fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "调试图像已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存Bitmap图像失败 ($fileName): ${e.message}", e)
        }
    }
     private fun saveDetectionsOnBitmap(baseBitmap: Bitmap, detections: List<CorrectionWellDetection>, fileName: String) {
        try {
            val debugBitmap = baseBitmap.copy(baseBitmap.config, true)
            val canvas = Canvas(debugBitmap)
            val paint = android.graphics.Paint().apply {
                color = Color.GREEN
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f // Thinner stroke
            }
            detections.forEach { canvas.drawRect(it.rect, paint) }
            saveBitmapImage(debugBitmap, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "保存带检测框的Bitmap失败 ($fileName): ${e.message}")
        }
    }

    private fun saveDetectionsOnMat(baseMat: Mat, points: List<Point>, fileName: String) {
        if (baseMat.empty()) return
        try {
            val colorMat = Mat()
            if (baseMat.channels() == 1) {
                Imgproc.cvtColor(baseMat, colorMat, Imgproc.COLOR_GRAY2BGR)
            } else {
                baseMat.copyTo(colorMat)
            }
            points.forEach { Imgproc.circle(colorMat, it, 5, Scalar(0.0, 255.0, 0.0), 2) }
            saveMatAsImage(colorMat, fileName)
            colorMat.release()
        } catch (e: Exception) {
            Log.e(TAG, "保存带检测点的Mat失败 ($fileName): ${e.message}")
        }
    }
     private fun savePointListAsCsv(points: List<Point>?, fileName: String) {
        if (points == null || points.isEmpty()) {
            Log.w(TAG, "Point list is null or empty for $fileName")
            return
        }
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)
            FileOutputStream(file).use { fos ->
                fos.bufferedWriter().use { writer ->
                    writer.appendLine("x,y")
                    points.forEach { point ->
                        writer.appendLine("${point.x},${point.y}")
                    }
                }
            }
            Log.d(TAG, "点列表已保存到CSV: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "保存点列表到CSV失败 ($fileName): ${e.message}", e)
        }
    }

    private fun generateUniformGrid(width: Int, height: Int, rows: Int, columns: Int): List<Point> {
        val points = mutableListOf<Point>()
        val marginX = width * 0.15 // Increased margin
        val marginY = height * 0.15
        val stepX = if (columns > 1) (width - 2 * marginX) / (columns - 1) else 0.0
        val stepY = if (rows > 1) (height - 2 * marginY) / (rows - 1) else 0.0

        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = marginX + c * stepX
                val y = marginY + r * stepY
                points.add(Point(x, y))
            }
        }
        Log.d(TAG, "Generated uniform grid with ${points.size} points.")
        return points
    }
    
    private fun generateTargetPixelGrid(
        rows: Int, columns: Int,
        outputWidth: Double, outputHeight: Double
    ): List<Point> {
        val gridPoints = mutableListOf<Point>()
        val marginX = outputWidth * 0.1 // 10% margin
        val marginY = outputHeight * 0.1
        val effectiveWidth = outputWidth - 2 * marginX
        val effectiveHeight = outputHeight - 2 * marginY
        val stepX = if (columns > 1) effectiveWidth / (columns - 1) else 0.0 // Avoid division by zero if columns=1
        val stepY = if (rows > 1) effectiveHeight / (rows - 1) else 0.0     // Avoid division by zero if rows=1

        for (r_idx in 0 until rows) {
            for (c_idx in 0 until columns) {
                val x = marginX + c_idx * stepX
                val y = marginY + r_idx * stepY
                gridPoints.add(Point(x, y))
            }
        }
        return gridPoints
    }

    private fun extractGridLines(points: List<Point>, rows: Int, columns: Int): List<List<Point>> {
        if (points.size < min(rows, columns, 2)) { // Need at least 2 points for a line
             Log.w(TAG, "extractGridLines: Not enough points (${points.size}) to form lines for $rows x $columns grid.")
            return emptyList()
        }

        val lines = mutableListOf<List<Point>>()
        // Sort points primarily by Y, then by X (for row-wise grouping)
        val sortedPoints = points.sortedWith(compareBy({ it.y }, { it.x }))

        // Estimate row height and column width for grouping tolerance
        val yCoords = sortedPoints.map { it.y }.distinct().sorted()
        val xCoords = sortedPoints.map { it.x }.distinct().sorted()
        
        val typicalRowHeight = if (yCoords.size > 1 && rows > 1) (yCoords.last() - yCoords.first()) / (rows -1) else (yCoords.lastOrNull() ?: 100.0) / 2.0
        val typicalColWidth = if (xCoords.size > 1 && columns > 1) (xCoords.last() - xCoords.first()) / (columns -1) else (xCoords.lastOrNull() ?: 100.0) / 2.0

        val yTolerance = typicalRowHeight * 0.35 // Tolerance for grouping points into a row
        val xTolerance = typicalColWidth * 0.35 // Tolerance for grouping points into a column


        // Extract rows
        var remainingPointsForRowExtraction = points.toMutableList()
        for (r in 0 until rows) {
            if (remainingPointsForRowExtraction.isEmpty()) break
            // Find a seed point for the current row (e.g., median y of top band)
            val seedY = yCoords.getOrNull(r * yCoords.size / rows) ?: remainingPointsForRowExtraction.minByOrNull { it.y }?.y ?: break
            
            val currentRowPoints = remainingPointsForRowExtraction.filter { abs(it.y - seedY) < yTolerance }.sortedBy { it.x }
            if (currentRowPoints.size >= 2) { // Need at least 2 points for a line
                lines.add(currentRowPoints)
                // remainingPointsForRowExtraction.removeAll(currentRowPoints) // Avoid re-using points for rows
            }
        }
        
        // Extract columns (similar logic, sort by X then Y)
        var remainingPointsForColExtraction = points.toMutableList()
         for (c in 0 until columns) {
            if (remainingPointsForColExtraction.isEmpty()) break
            val seedX = xCoords.getOrNull(c * xCoords.size / columns) ?: remainingPointsForColExtraction.minByOrNull { it.x }?.x ?: break

            val currentColPoints = remainingPointsForColExtraction.filter { abs(it.x - seedX) < xTolerance }.sortedBy { it.y }
            if (currentColPoints.size >= 2) {
                lines.add(currentColPoints)
                // remainingPointsForColExtraction.removeAll(currentColPoints)
            }
        }
        Log.d(TAG, "Extracted ${lines.size} grid lines from ${points.size} points.")
        return lines.filter { it.size >=2 } // Ensure all lines have at least 2 points
    }

    private fun createTempImageFile(prefix: String): File {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        val imageFileName = "${prefix}${timeStamp}_"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir // Fallback to internal storage if external is not available
        if (storageDir != null && !storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File.createTempFile(imageFileName, ".jpg", storageDir)
    }

    private fun prepareInputBitmap(original: Bitmap): Bitmap {
        val modelInputSize = 1280
        val originalWidth = original.width.toFloat()
        val originalHeight = original.height.toFloat()
        val scale = min(modelInputSize / originalWidth, modelInputSize / originalHeight)
        val scaledWidth = (originalWidth * scale).roundToInt() // Use roundToInt for better precision
        val scaledHeight = (originalHeight * scale).roundToInt()
        val paddingX = (modelInputSize - scaledWidth) / 2f
        val paddingY = (modelInputSize - scaledHeight) / 2f

        lastLetterboxInfo = LetterboxInfo(scale, paddingX, paddingY, modelInputSize, modelInputSize)
        // Log.d(TAG, "Letterbox: Orig(${originalWidth}x${originalHeight}), Scale=$scale, Pad(${paddingX},${paddingY}), Scaled(${scaledWidth}x${scaledHeight})")


        val scaledBitmap = Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true)
        val outputBitmap = Bitmap.createBitmap(modelInputSize, modelInputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        canvas.drawColor(Color.BLACK) // Fill with black
        canvas.drawBitmap(scaledBitmap, paddingX, paddingY, null)
        scaledBitmap.recycle() // Recycle intermediate bitmap
        return outputBitmap
    }

    private fun getOutputTensor(modelOutput: IValue): org.pytorch.Tensor {
        return try {
            modelOutput.toTensor()
        } catch (e: Exception) {
            try {
                val tuple = modelOutput.toTuple()
                if (tuple.isNotEmpty() && tuple[0].isTensor) { // Check if first element is tensor
                    tuple[0].toTensor()
                } else {
                    throw Exception("模型输出元组为空或第一个元素不是张量")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "获取输出张量失败: ${e.message} / ${e2.message}", e2)
                throw Exception("无法处理模型输出", e2)
            }
        }
    }

    private fun parseRawDetections(detectionsTensor: org.pytorch.Tensor, confidenceThreshold: Float): MutableList<RawDetection> {
        val detections = mutableListOf<RawDetection>()
        val output = detectionsTensor.dataAsFloatArray
        val outputShape = detectionsTensor.shape() // Expected: [batch, num_detections, num_classes + 5] or [batch, num_boxes, 4+1+num_classes]

        if (outputShape.size == 3 && outputShape[0] == 1L) { // Assuming batch size 1
            val numDetections = outputShape[1].toInt()
            val elementsPerDetection = outputShape[2].toInt() // xc, yc, w, h, conf, class_scores...

            if (elementsPerDetection < 5) {
                 Log.e(TAG, "解析检测结果失败: 每个检测的元素数量不足 ($elementsPerDetection).")
                return detections
            }

            for (i in 0 until numDetections) {
                val offset = i * elementsPerDetection
                val confidence = output[offset + 4]

                if (confidence >= confidenceThreshold) {
                    val xc = output[offset]
                    val yc = output[offset + 1]
                    val w = output[offset + 2]
                    val h = output[offset + 3]
                    
                    // Assuming class is the one with max score if multiple classes present
                    var classIndex = 0
                    if (elementsPerDetection > 5) { // If class scores are present
                        var maxScore = 0f
                        for (cls_idx in 5 until elementsPerDetection) {
                            if (output[offset + cls_idx] > maxScore) {
                                maxScore = output[offset + cls_idx]
                                classIndex = cls_idx - 5
                            }
                        }
                    }


                    if (w <= 0 || h <= 0) continue // Invalid box
                    val x1 = xc - w / 2
                    val y1 = yc - h / 2
                    val x2 = xc + w / 2
                    val y2 = yc + h / 2
                    detections.add(RawDetection(x1, y1, x2, y2, confidence, classIndex))
                }
            }
        } else {
            Log.e(TAG, "解析检测结果失败: 输出张量形状不符合预期 ${outputShape.joinToString(",")}")
        }
        return detections
    }

    private fun applyNMS(detections: List<RawDetection>, iouThreshold: Float): MutableList<RawDetection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<RawDetection>()
        val selected = BooleanArray(sortedDetections.size) { false }

        for (i in sortedDetections.indices) {
            if (selected[i]) continue
            result.add(sortedDetections[i])
            selected[i] = true
            val rect1 = sortedDetections[i].getRectF()
            for (j in (i + 1) until sortedDetections.size) {
                if (selected[j]) continue
                val rect2 = sortedDetections[j].getRectF()
                if (calculateIoU(rect1, rect2) > iouThreshold) {
                    selected[j] = true
                }
            }
        }
        return result
    }

    private fun calculateIoU(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(rect1, rect2)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        val area1 = rect1.width() * rect1.height()
        val area2 = rect2.width() * rect2.height()
        val unionArea = area1 + area2 - intersectionArea
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun scaleBoxes(
        nmsResults: List<RawDetection>,
        letterboxInfo: LetterboxInfo,
        originalImageWidth: Int, // Renamed for clarity
        originalImageHeight: Int // Renamed for clarity
    ): MutableList<CorrectionWellDetection> {
        val scaledDetections = mutableListOf<CorrectionWellDetection>()
        val origWidthF = originalImageWidth.toFloat()
        val origHeightF = originalImageHeight.toFloat()

        for ((index, detection) in nmsResults.withIndex()) {
            val x1 = (detection.x1 - letterboxInfo.paddingX) / letterboxInfo.scale
            val y1 = (detection.y1 - letterboxInfo.paddingY) / letterboxInfo.scale
            val x2 = (detection.x2 - letterboxInfo.paddingX) / letterboxInfo.scale
            val y2 = (detection.y2 - letterboxInfo.paddingY) / letterboxInfo.scale

            // CoerceIn to ensure coordinates are within original image bounds
            val scaledX1 = x1.coerceIn(0f, origWidthF)
            val scaledY1 = y1.coerceIn(0f, origHeightF)
            val scaledX2 = x2.coerceIn(0f, origWidthF)
            val scaledY2 = y2.coerceIn(0f, origHeightF)

            if (scaledX2 - scaledX1 < 1f || scaledY2 - scaledY1 < 1f) continue
            scaledDetections.add(CorrectionWellDetection(index, RectF(scaledX1, scaledY1, scaledX2, scaledY2), detection.confidence))
        }
        return scaledDetections
    }

    private fun convertDetectionsToPoints(detections: List<CorrectionWellDetection>): List<Point> {
        return detections.map { Point(it.rect.centerX().toDouble(), it.rect.centerY().toDouble()) }
    }
}
