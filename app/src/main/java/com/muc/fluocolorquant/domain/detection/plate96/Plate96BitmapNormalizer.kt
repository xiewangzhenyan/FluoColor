package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.muc.fluocolorquant.domain.detection.array.ArrayCoordinateTransformer
import com.muc.fluocolorquant.domain.detection.array.ArrayOrientationSnapshot
import com.muc.fluocolorquant.domain.detection.array.ArrayQuarterTurn

/**
 * 使用整数旋转/镜像矩阵生成标准方向工作图。
 *
 * Paint明确关闭双线性过滤，因此0°/90°/180°/270°不会混合相邻像素；原始Bitmap也不会
 * 被修改或重新编码为JPEG。该工作图用于定位确认和圆孔裁切预览，原图仍作为科研原件保存。
 */
object Plate96BitmapNormalizer {
    fun normalize(source: Bitmap, orientation: ArrayOrientationSnapshot): Bitmap {
        orientation.requireValid()
        val transform = ArrayCoordinateTransformer.createImageTransform(
            sourceWidth = source.width,
            sourceHeight = source.height,
            rotation = orientation.rotation,
            mirrored = orientation.mirrored
        )
        val output = Bitmap.createBitmap(
            transform.normalizedWidth,
            transform.normalizedHeight,
            Bitmap.Config.ARGB_8888
        )
        // 科学坐标矩阵描述像素中心，因此平移量使用 width-1/height-1；Canvas绘制描述的是
        // 像素边界，必须使用width/height。两者不能混用，否则90°旋转会错开一个角点像素。
        val matrix = Matrix().apply { setValues(renderMatrix(source, orientation)) }
        Canvas(output).drawBitmap(
            source,
            matrix,
            Paint().apply {
                isFilterBitmap = false
                isDither = false
            }
        )
        return output
    }

    private fun renderMatrix(source: Bitmap, orientation: ArrayOrientationSnapshot): FloatArray {
        val mirror = if (orientation.mirrored) {
            floatArrayOf(
                -1f, 0f, source.width.toFloat(),
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        } else {
            identity()
        }
        val rotation = when (orientation.rotation) {
            ArrayQuarterTurn.ROTATE_0 -> identity()
            ArrayQuarterTurn.ROTATE_90_CW -> floatArrayOf(
                0f, -1f, source.height.toFloat(),
                1f, 0f, 0f,
                0f, 0f, 1f
            )
            ArrayQuarterTurn.ROTATE_180 -> floatArrayOf(
                -1f, 0f, source.width.toFloat(),
                0f, -1f, source.height.toFloat(),
                0f, 0f, 1f
            )
            ArrayQuarterTurn.ROTATE_270_CW -> floatArrayOf(
                0f, 1f, 0f,
                -1f, 0f, source.width.toFloat(),
                0f, 0f, 1f
            )
        }
        return multiply(rotation, mirror)
    }

    private fun multiply(left: FloatArray, right: FloatArray): FloatArray {
        return FloatArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0 until 3).sumOf { pivot ->
                (left[row * 3 + pivot] * right[pivot * 3 + column]).toDouble()
            }.toFloat()
        }
    }

    private fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
}
