package com.muc.fluocolorquant.domain.detection.segmentation

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 方形芯片单元与圆形孔板共用的 Bitmap 裁切器。
 *
 * 科学计算始终读取 [ArrayUnitRegion.foregroundMask]；本工具负责生成预览或模型输入图。
 * 圆形/自定义区域可选择把掩膜外像素设为透明，避免界面把矩形外接框四角误认为孔内信号。
 */
object ArrayUnitBitmapCropper {

    fun crop(
        source: Bitmap,
        region: ArrayUnitRegion,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        transparentOutsideMask: Boolean = false
    ): Bitmap {
        region.requireValid(source.width, source.height)
        require(targetWidth == null || targetWidth > 0) { "目标裁切宽度必须大于 0" }
        require(targetHeight == null || targetHeight > 0) { "目标裁切高度必须大于 0" }

        val bounds = region.bounds
        val raw = Bitmap.createBitmap(source, bounds.left, bounds.top, bounds.width, bounds.height)
        val masked = if (transparentOutsideMask) applyTransparentMask(raw, region) else raw
        val resolvedWidth = targetWidth ?: masked.width
        val resolvedHeight = targetHeight ?: masked.height
        if (resolvedWidth == masked.width && resolvedHeight == masked.height) return masked

        val scaled = Bitmap.createScaledBitmap(masked, resolvedWidth, resolvedHeight, true)
        if (scaled !== masked && !masked.isRecycled) masked.recycle()
        return scaled
    }

    /** 用透明像素覆盖区域掩膜之外的背景；不改变掩膜内任何 RGB 数值。 */
    private fun applyTransparentMask(source: Bitmap, region: ArrayUnitRegion): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== source && !source.isRecycled) source.recycle()
        val pixels = IntArray(mutable.width * mutable.height)
        mutable.getPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        region.foregroundMask.forEachIndexed { index, value ->
            if (value.toInt() == 0) pixels[index] = Color.TRANSPARENT
        }
        mutable.setPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        return mutable
    }

    /**
     * 将旧孔板已经检测出的圆/矩形外接框适配为通用区域。
     *
     * 圆形使用内接椭圆掩膜；方形整框保留。这个入口不重新定位圆心，现有 YOLO＋霍夫圆
     * 仍负责识别，通用组件只统一后续裁切与像素区域语义。
     */
    fun detectedGeometryRegion(
        siteIndex: Int,
        rowIndex: Int,
        columnIndex: Int,
        shape: ArrayUnitShape,
        bounds: ArrayUnitBounds
    ): ArrayUnitRegion {
        bounds.requireValid()
        return ArrayUnitRegion(
            siteIndex = siteIndex,
            rowIndex = rowIndex,
            columnIndex = columnIndex,
            shape = shape,
            bounds = bounds,
            source = ArrayUnitRegionSource.DETECTED_GEOMETRY,
            foregroundMask = geometryMask(bounds.width, bounds.height, shape)
        ).requireValid()
    }

    /** 根据声明形状生成兜底掩膜；POINT 与 CIRCLE 使用同一椭圆定义。 */
    internal fun geometryMask(width: Int, height: Int, shape: ArrayUnitShape): ByteArray {
        require(width > 0 && height > 0) { "掩膜宽高必须大于 0" }
        if (shape == ArrayUnitShape.SQUARE || shape == ArrayUnitShape.CUSTOM) {
            return ByteArray(width * height) { 1 }
        }

        val centerX = (width - 1) / 2.0
        val centerY = (height - 1) / 2.0
        val radiusX = (width / 2.0).coerceAtLeast(0.5)
        val radiusY = (height / 2.0).coerceAtLeast(0.5)
        return ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val normalizedX = (x - centerX) / radiusX
            val normalizedY = (y - centerY) / radiusY
            if (normalizedX * normalizedX + normalizedY * normalizedY <= 1.0) 1 else 0
        }
    }
}
