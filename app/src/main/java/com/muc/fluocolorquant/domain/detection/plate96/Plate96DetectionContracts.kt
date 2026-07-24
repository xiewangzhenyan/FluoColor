package com.muc.fluocolorquant.domain.detection.plate96

import android.graphics.Bitmap
import com.muc.fluocolorquant.domain.detection.array.ArrayImageBounds

/** YOLO输出的孔位候选；此时只承诺矩形和置信度，不承诺圆心已经精确。 */
data class Plate96ObjectCandidate(
    val candidateIndex: Int,
    val bounds: ArrayImageBounds,
    val confidence: Double
) {
    init {
        require(candidateIndex >= 0) { "孔位候选索引不能为负数" }
        bounds.requireValid()
        require(confidence in 0.0..1.0) { "孔位候选置信度必须位于0到1" }
    }
}

/** 圆形精定位的来源；BOX_FALLBACK仍保留YOLO证据，但不会伪装成检测到了圆边缘。 */
enum class Plate96CircleSource {
    HOUGH,
    CONTOUR,
    BOX_FALLBACK
}

/** 霍夫圆或轮廓拟合后的圆孔候选。 */
data class Plate96CircleCandidate(
    val candidateIndex: Int,
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
    val confidence: Double,
    val source: Plate96CircleSource,
    val objectBounds: ArrayImageBounds
) {
    init {
        require(candidateIndex >= 0) { "圆孔候选索引不能为负数" }
        require(centerX.isFinite() && centerY.isFinite()) { "圆孔中心必须为有限数值" }
        require(radius.isFinite() && radius > 0.0) { "圆孔半径必须为正有限数值" }
        require(confidence in 0.0..1.0) { "圆孔候选置信度必须位于0到1" }
        objectBounds.requireValid()
    }
}

/** 目标检测接口独立于ViewModel，设备测试可注入确定性候选。 */
interface Plate96ObjectDetector {
    suspend fun detect(
        sourceBitmap: Bitmap,
        confidenceThreshold: Float,
        iouThreshold: Float
    ): List<Plate96ObjectCandidate>
}
