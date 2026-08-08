package com.muc.fluocolorquant.domain.detection.grid

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 使用 PG-Grid 冻结单应矩阵生成无增强透视矫正图。
 *
 * 定位证据、单元分割和孔位预览必须调用同一个实现，否则不同模块各自执行一次稍有差异的
 * warp 参数，会造成“屏幕看到的边界”和“科学采样使用的边界”漂移。
 */
object PgGridImageRectifier {

    fun rectify(source: Bitmap, grid: PgGridResult): Bitmap {
        grid.requireValid()
        require(source.width > 0 && source.height > 0) { "待矫正图像尺寸无效" }

        val input = Mat()
        val output = Mat()
        val matrix = Mat(3, 3, CvType.CV_64F)
        return try {
            Utils.bitmapToMat(source, input)
            matrix.put(0, 0, *grid.homography.forward.toDoubleArray())
            Imgproc.warpPerspective(
                input,
                output,
                matrix,
                Size(grid.rectifiedWidth.toDouble(), grid.rectifiedHeight.toDouble()),
                // 与既有处理证据保持三次插值，确保同一次运行的矫正图像素完全一致。
                Imgproc.INTER_CUBIC
            )
            Bitmap.createBitmap(
                grid.rectifiedWidth,
                grid.rectifiedHeight,
                Bitmap.Config.ARGB_8888
            ).also { bitmap -> Utils.matToBitmap(output, bitmap) }
        } finally {
            matrix.release()
            output.release()
            input.release()
        }
    }
}
