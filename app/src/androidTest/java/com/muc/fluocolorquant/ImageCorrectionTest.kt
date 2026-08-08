package com.muc.fluocolorquant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class ImageCorrectionTest {
    private val TAG = "ImageCorrectionTest"
    private lateinit var context: Context
    
    @Before
    fun setup() {
        // 初始化OpenCV
        if (!OpenCVLoader.initDebug()) {
            throw Exception("OpenCV初始化失败")
        }
        
        context = ApplicationProvider.getApplicationContext()
    }
    
    /**
     * 从资源中加载测试图像
     */
    private fun loadTestImage(resourceId: Int): Bitmap {
        // PNG/JPEG/WebP 等栅格资源优先走 BitmapFactory，避免无意义的 Drawable 绘制开销。
        val decodedBitmap = context.resources.openRawResource(resourceId).use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        }
        if (decodedBitmap != null) return decodedBitmap

        // `test_grid` 是 VectorDrawable XML，BitmapFactory 对 XML 会正常返回 null 而不是抛异常。
        // 仪器测试必须显式把矢量资源绘制到 Bitmap，不能依赖 Kotlin 非空返回值触发 NPE。
        val drawable = requireNotNull(context.getDrawable(resourceId)) {
            "无法加载测试图像资源：$resourceId"
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
        }
    }
    
    /**
     * 从assets文件夹加载测试图像
     */
    private fun loadTestImageFromAssets(fileName: String): Bitmap {
        val inputStream = context.assets.open(fileName)
        return BitmapFactory.decodeStream(inputStream).also {
            inputStream.close()
        }
    }
    
    /**
     * 从外部存储加载测试图像
     */
    private fun loadTestImageFromExternalStorage(fileName: String): Bitmap? {
        try {
            // 检查外部存储中的几个常见位置
            val possiblePaths = listOf(
                File(Environment.getExternalStorageDirectory(), fileName),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), fileName),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName),
                File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName),
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            )
            
            for (file in possiblePaths) {
                if (file.exists()) {
                    Log.d(TAG, "找到测试图像: ${file.absolutePath}")
                    return BitmapFactory.decodeFile(file.absolutePath)
                }
            }
            
            Log.e(TAG, "在外部存储中未找到测试图像: $fileName")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "加载外部测试图像失败: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 保存bitmap到文件
     */
    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String): File {
        val file = File(context.externalCacheDir, fileName)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        return file
    }
    
    /**
     * 测试图像畸变矫正功能
     */
    @Test
    fun testImageCorrection() {
        runBlocking {
            // 1. 从assets加载测试图像（可以替换为你的测试图像路径）
            val testImageName = "test_image.jpg" // 替换为实际测试图像名称
            val originalBitmap = try {
                loadTestImageFromAssets(testImageName)
            } catch (e: Exception) {
                // 如果assets中没有图像，则使用默认测试资源
                Log.w(TAG, "Assets中未找到测试图像，使用默认测试图像")
                loadTestImage(R.drawable.test_grid) // 替换为实际资源ID
            }
            
            Log.d(TAG, "加载的原始图像大小: ${originalBitmap.width}x${originalBitmap.height}")
            
            // 2. 保存原始图像用于比较
            val originalFile = saveBitmapToFile(originalBitmap, "original_test.jpg")
            Log.d(TAG, "原始图像已保存到: ${originalFile.absolutePath}")
            
            // 3. 执行畸变矫正
            val correctedBitmap = correctImageDistortion(originalBitmap, 12, 8) // 12行8列的孔阵
            
            // 4. 保存矫正后的图像用于比较
            val correctedFile = saveBitmapToFile(correctedBitmap, "corrected_test.jpg")
            Log.d(TAG, "矫正后图像已保存到: ${correctedFile.absolutePath}")
            
            // 5. 输出测试结果
            Log.i(TAG, "图像畸变矫正测试完成")
            Log.i(TAG, "原始图像: ${originalFile.absolutePath}")
            Log.i(TAG, "矫正后图像: ${correctedFile.absolutePath}")
            
            // 6. 可以添加更多自动化验证，如检查结构相似度等
        }
    }
    
    /**
     * 测试图像畸变矫正功能 - 使用外部测试图像
     */
    @Test
    fun testImageCorrectionWithExternalImage() {
        runBlocking {
            // 1. 尝试加载外部测试图像
            val fileName = "screen01.png" // 外部测试图像文件名
            val originalBitmap = loadTestImageFromExternalStorage(fileName)
            
            if (originalBitmap == null) {
                Log.w(TAG, "未找到外部测试图像 $fileName，测试跳过")
                return@runBlocking
            }
            
            Log.d(TAG, "加载的外部测试图像大小: ${originalBitmap.width}x${originalBitmap.height}")
            
            // 2. 保存原始图像用于比较
            val originalFile = saveBitmapToFile(originalBitmap, "original_external_test.jpg")
            Log.d(TAG, "原始图像已保存到: ${originalFile.absolutePath}")
            
            // 3. 执行畸变矫正
            val correctedBitmap = correctImageDistortion(originalBitmap, 12, 8) // 12行8列的孔阵
            
            // 4. 保存矫正后的图像用于比较
            val correctedFile = saveBitmapToFile(correctedBitmap, "corrected_external_test.jpg")
            Log.d(TAG, "矫正后图像已保存到: ${correctedFile.absolutePath}")
            
            // 5. 输出测试结果
            Log.i(TAG, "外部图像畸变矫正测试完成")
            Log.i(TAG, "原始图像: ${originalFile.absolutePath}")
            Log.i(TAG, "矫正后图像: ${correctedFile.absolutePath}")
        }
    }
    
    /**
     * 执行图像畸变矫正的核心逻辑
     * 简化版本的ImageCorrectionViewModel中的实现
     */
    private fun correctImageDistortion(bitmap: Bitmap, rows: Int, columns: Int): Bitmap {
        // 1. 转换为OpenCV格式
        val inputMat = Mat()
        Utils.bitmapToMat(bitmap, inputMat)
        
        // 灰度化
        val grayMat = Mat()
        Imgproc.cvtColor(inputMat, grayMat, Imgproc.COLOR_BGR2GRAY)
        
        // 2. 检测孔位
        val wellCenters = detectWellCenters(grayMat, rows, columns)
        
        if (wellCenters.isEmpty()) {
            Log.e(TAG, "未检测到足够的孔位点")
            return bitmap.copy(bitmap.config, true)
        }
        
        // 3. 生成理想网格
        val idealGridPoints = generateIdealGrid(rows, columns)
        
        // 4. 计算透视变换
        val resultMat = applyPerspectiveTransform(inputMat, wellCenters, idealGridPoints)
        
        // 5. 转换回Bitmap
        val resultBitmap = Bitmap.createBitmap(
            resultMat.cols(), resultMat.rows(), Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(resultMat, resultBitmap)
        
        // 6. 释放资源
        inputMat.release()
        grayMat.release()
        resultMat.release()
        
        return resultBitmap
    }
    
    /**
     * 检测孔位中心
     */
    private fun detectWellCenters(grayMat: Mat, rows: Int, columns: Int): List<Point> {
        // 增强对比度
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 3.0
        val enhancedMat = Mat()
        clahe.apply(grayMat, enhancedMat)
        
        // 降噪处理
        Imgproc.GaussianBlur(enhancedMat, enhancedMat, Size(5.0, 5.0), 2.0, 2.0)
        
        // 使用自适应二值化增强边缘对比度
        val binaryMat = Mat()
        Imgproc.adaptiveThreshold(
            enhancedMat,
            binaryMat,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            11,
            2.0
        )
        
        // 计算孔半径范围
        val minRadius = (enhancedMat.width() / (columns * 3).toDouble()).toInt()
        val maxRadius = (enhancedMat.width() / columns.toDouble()).toInt()
        
        // 使用霍夫圆变换检测孔位
        val circles = Mat()
        Imgproc.HoughCircles(
            enhancedMat,
            circles,
            Imgproc.HOUGH_GRADIENT,
            1.0,                    // 分辨率比例
            enhancedMat.rows() / (Math.max(rows, columns) + 2).toDouble(),  // 最小圆心距离
            150.0,                  // Canny边缘检测器的高阈值
            30.0,                   // 累加器阈值
            minRadius,              // 最小半径
            maxRadius               // 最大半径
        )
        
        val detectedCircles = mutableListOf<Point>()
        
        if (circles.cols() > 0) {
            Log.d(TAG, "霍夫变换检测到圆的数量: ${circles.cols()}")
            
            for (i in 0 until kotlin.math.min(circles.cols(), rows * columns * 2)) {
                val circle = FloatArray(3)
                circles.get(0, i, circle)
                detectedCircles.add(Point(circle[0].toDouble(), circle[1].toDouble()))
            }
        } else {
            // 使用轮廓检测作为备选方法
            try {
                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(
                    binaryMat, 
                    contours, 
                    hierarchy, 
                    Imgproc.RETR_EXTERNAL, 
                    Imgproc.CHAIN_APPROX_SIMPLE
                )
                
                for (contour in contours) {
                    val area = Imgproc.contourArea(contour)
                    if (area < 50) continue
                    
                    val contour2f = MatOfPoint2f(*contour.toArray())
                    val center = Point()
                    val radius = FloatArray(1)
                    Imgproc.minEnclosingCircle(contour2f, center, radius)
                    
                    if (radius[0] >= minRadius && radius[0] <= maxRadius * 1.5) {
                        detectedCircles.add(center)
                        if (detectedCircles.size >= rows * columns) break
                    }
                    
                    contour2f.release()
                }
                
                hierarchy.release()
                for (contour in contours) {
                    contour.release()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "轮廓检测失败", e)
            }
        }
        
        // 清理资源
        enhancedMat.release()
        binaryMat.release()
        circles.release()
        
        // 如果检测到的点太少，生成均匀网格
        if (detectedCircles.size < kotlin.math.min(rows * columns / 2, 16)) {
            Log.w(TAG, "检测到的圆太少，使用均匀网格")
            return generateUniformGrid(grayMat.width(), grayMat.height(), rows, columns)
        }
        
        return detectedCircles
    }
    
    /**
     * 生成均匀网格
     */
    private fun generateUniformGrid(width: Int, height: Int, rows: Int, columns: Int): List<Point> {
        val points = mutableListOf<Point>()
        
        // 假设所有孔位均匀分布
        val marginX = width * 0.1
        val marginY = height * 0.1
        val stepX = (width - 2 * marginX) / (columns - 1)
        val stepY = (height - 2 * marginY) / (rows - 1)
        
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = marginX + c * stepX
                val y = marginY + r * stepY
                points.add(Point(x, y))
            }
        }
        
        return points
    }
    
    /**
     * 生成理想网格模型
     */
    private fun generateIdealGrid(rows: Int, columns: Int): List<Point> {
        val gridPoints = mutableListOf<Point>()
        
        // 使用归一化坐标，将孔间距设为1个单位
        val halfWidth = (columns - 1) / 2.0
        val halfHeight = (rows - 1) / 2.0
        
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                val x = (c - halfWidth) * 1.0
                val y = (r - halfHeight) * 1.0
                gridPoints.add(Point(x, y))
            }
        }
        
        return gridPoints
    }
    
    /**
     * 应用透视变换
     */
    private fun applyPerspectiveTransform(inputMat: Mat, wellCenters: List<Point>, idealPoints: List<Point>): Mat {
        try {
            // 创建匹配点Mat对象
            val srcPoints = MatOfPoint2f()
            val dstPoints = MatOfPoint2f()
            
            // 选择最多点来估计变换，但不能超过实际检测到的点数
            val pointCount = kotlin.math.min(wellCenters.size, idealPoints.size)
            
            // 确保点的数量足够
            val minPoints = kotlin.math.min(4, pointCount)
            if (pointCount < minPoints) {
                Log.e(TAG, "匹配点数量不足: $pointCount")
                return inputMat.clone()
            }
            
            // 添加点到匹配列表
            srcPoints.fromList(wellCenters.take(pointCount))
            dstPoints.fromList(idealPoints.take(pointCount))
            
            // 使用RANSAC方法估计透视变换矩阵
            val homography = Calib3d.findHomography(
                srcPoints, 
                dstPoints, 
                Calib3d.RANSAC, 
                10.0
            )
            
            if (homography.empty()) {
                Log.e(TAG, "无法估计单应性矩阵")
                return inputMat.clone()
            }
            
            // 应用透视变换到图像
            val result = Mat()
            Imgproc.warpPerspective(
                inputMat,
                result,
                homography,
                inputMat.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0)
            )
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "透视变换过程中出错", e)
            return inputMat.clone()
        }
    }
}
