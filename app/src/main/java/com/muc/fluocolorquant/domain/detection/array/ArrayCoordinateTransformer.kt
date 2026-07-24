package com.muc.fluocolorquant.domain.detection.array

import kotlin.math.abs

/** 方向矩阵生成器的稳定版本，写入运行快照用于历史解释。 */
const val ARRAY_ORIENTATION_PROCESSOR_V1: String = "array-orientation-transform-v1"

/**
 * 规则阵列方向和图像坐标的双向转换器。
 *
 * 网格转换与像素矩阵使用同一语义：先在原图坐标系执行可选水平镜像，再执行顺时针
 * 四分之一圈旋转，使结果进入载体标准方向。所有函数均为纯Kotlin，可在JVM单测中固定。
 */
object ArrayCoordinateTransformer {
    /** 将原图中的零基行列转换为标准阵列行列。 */
    fun sourceToCanonical(
        sourceCoordinate: ArrayGridCoordinate,
        orientation: ArrayOrientationSnapshot
    ): ArrayGridCoordinate {
        orientation.requireValid()
        require(sourceCoordinate.rowIndex in 0 until orientation.sourceRows) { "原图行号越界" }
        require(sourceCoordinate.columnIndex in 0 until orientation.sourceColumns) { "原图列号越界" }

        val mirroredColumn = if (orientation.mirrored) {
            orientation.sourceColumns - 1 - sourceCoordinate.columnIndex
        } else {
            sourceCoordinate.columnIndex
        }
        val row = sourceCoordinate.rowIndex
        val canonical = when (orientation.rotation) {
            ArrayQuarterTurn.ROTATE_0 -> ArrayGridCoordinate(row, mirroredColumn)
            ArrayQuarterTurn.ROTATE_90_CW -> ArrayGridCoordinate(
                rowIndex = mirroredColumn,
                columnIndex = orientation.sourceRows - 1 - row
            )
            ArrayQuarterTurn.ROTATE_180 -> ArrayGridCoordinate(
                rowIndex = orientation.sourceRows - 1 - row,
                columnIndex = orientation.sourceColumns - 1 - mirroredColumn
            )
            ArrayQuarterTurn.ROTATE_270_CW -> ArrayGridCoordinate(
                rowIndex = orientation.sourceColumns - 1 - mirroredColumn,
                columnIndex = row
            )
        }
        require(canonical.rowIndex in 0 until orientation.canonicalRows) { "标准行号越界" }
        require(canonical.columnIndex in 0 until orientation.canonicalColumns) { "标准列号越界" }
        return canonical
    }

    /** 将标准阵列行列反向映射回原图行列。 */
    fun canonicalToSource(
        canonicalCoordinate: ArrayGridCoordinate,
        orientation: ArrayOrientationSnapshot
    ): ArrayGridCoordinate {
        orientation.requireValid()
        require(canonicalCoordinate.rowIndex in 0 until orientation.canonicalRows) { "标准行号越界" }
        require(canonicalCoordinate.columnIndex in 0 until orientation.canonicalColumns) { "标准列号越界" }

        val rowBeforeMirror: Int
        val columnBeforeMirror: Int
        when (orientation.rotation) {
            ArrayQuarterTurn.ROTATE_0 -> {
                rowBeforeMirror = canonicalCoordinate.rowIndex
                columnBeforeMirror = canonicalCoordinate.columnIndex
            }
            ArrayQuarterTurn.ROTATE_90_CW -> {
                rowBeforeMirror = orientation.sourceRows - 1 - canonicalCoordinate.columnIndex
                columnBeforeMirror = canonicalCoordinate.rowIndex
            }
            ArrayQuarterTurn.ROTATE_180 -> {
                rowBeforeMirror = orientation.sourceRows - 1 - canonicalCoordinate.rowIndex
                columnBeforeMirror = orientation.sourceColumns - 1 - canonicalCoordinate.columnIndex
            }
            ArrayQuarterTurn.ROTATE_270_CW -> {
                rowBeforeMirror = canonicalCoordinate.columnIndex
                columnBeforeMirror = orientation.sourceColumns - 1 - canonicalCoordinate.rowIndex
            }
        }
        val sourceColumn = if (orientation.mirrored) {
            orientation.sourceColumns - 1 - columnBeforeMirror
        } else {
            columnBeforeMirror
        }
        return ArrayGridCoordinate(rowBeforeMirror, sourceColumn).also { source ->
            require(source.rowIndex in 0 until orientation.sourceRows) { "反向映射后的原图行号越界" }
            require(source.columnIndex in 0 until orientation.sourceColumns) { "反向映射后的原图列号越界" }
        }
    }

    /**
     * 为一张原图生成无插值整数旋转对应的正逆3×3矩阵。
     *
     * 坐标采用像素中心索引，因此90°旋转中的平移量使用 `height - 1` 或 `width - 1`。
     */
    fun createImageTransform(
        sourceWidth: Int,
        sourceHeight: Int,
        rotation: ArrayQuarterTurn,
        mirrored: Boolean
    ): ArrayImageTransformSnapshot {
        require(sourceWidth > 0 && sourceHeight > 0) { "原图宽高必须大于0" }
        val mirrorMatrix = if (mirrored) {
            doubleArrayOf(
                -1.0, 0.0, sourceWidth - 1.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
            )
        } else {
            identityMatrix()
        }
        val rotationMatrix = when (rotation) {
            ArrayQuarterTurn.ROTATE_0 -> identityMatrix()
            ArrayQuarterTurn.ROTATE_90_CW -> doubleArrayOf(
                0.0, -1.0, sourceHeight - 1.0,
                1.0, 0.0, 0.0,
                0.0, 0.0, 1.0
            )
            ArrayQuarterTurn.ROTATE_180 -> doubleArrayOf(
                -1.0, 0.0, sourceWidth - 1.0,
                0.0, -1.0, sourceHeight - 1.0,
                0.0, 0.0, 1.0
            )
            ArrayQuarterTurn.ROTATE_270_CW -> doubleArrayOf(
                0.0, 1.0, 0.0,
                -1.0, 0.0, sourceWidth - 1.0,
                0.0, 0.0, 1.0
            )
        }
        val forward = multiply(rotationMatrix, mirrorMatrix)
        val inverse = invertAffine(forward)
        val swapsAxes = rotation == ArrayQuarterTurn.ROTATE_90_CW ||
            rotation == ArrayQuarterTurn.ROTATE_270_CW
        return ArrayImageTransformSnapshot(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            normalizedWidth = if (swapsAxes) sourceHeight else sourceWidth,
            normalizedHeight = if (swapsAxes) sourceWidth else sourceHeight,
            sourceToNormalized = forward.toList(),
            normalizedToSource = inverse.toList(),
            processorVersion = ARRAY_ORIENTATION_PROCESSOR_V1
        ).requireValid()
    }

    /** 将原图像素坐标映射到标准方向工作图。 */
    fun sourceToNormalized(
        point: ArrayImagePoint,
        transform: ArrayImageTransformSnapshot
    ): ArrayImagePoint {
        transform.requireValid()
        return applyMatrix(transform.sourceToNormalized, point)
    }

    /** 将标准方向工作图坐标反向映射到原图。 */
    fun normalizedToSource(
        point: ArrayImagePoint,
        transform: ArrayImageTransformSnapshot
    ): ArrayImagePoint {
        transform.requireValid()
        return applyMatrix(transform.normalizedToSource, point)
    }

    private fun applyMatrix(matrix: List<Double>, point: ArrayImagePoint): ArrayImagePoint {
        val denominator = matrix[6] * point.x + matrix[7] * point.y + matrix[8]
        require(denominator.isFinite() && abs(denominator) > MATRIX_EPSILON) {
            "坐标变换得到无效齐次分母"
        }
        return ArrayImagePoint(
            x = (matrix[0] * point.x + matrix[1] * point.y + matrix[2]) / denominator,
            y = (matrix[3] * point.x + matrix[4] * point.y + matrix[5]) / denominator
        )
    }

    /** 3×3矩阵乘法；数组固定为行主序。 */
    private fun multiply(left: DoubleArray, right: DoubleArray): DoubleArray {
        require(left.size == 9 && right.size == 9) { "矩阵必须为3×3" }
        return DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            (0 until 3).sumOf { pivot -> left[row * 3 + pivot] * right[pivot * 3 + column] }
        }
    }

    /** 当前方向矩阵均为仿射矩阵，使用显式2×2逆可避免引入Android Matrix依赖。 */
    private fun invertAffine(matrix: DoubleArray): DoubleArray {
        val a = matrix[0]
        val b = matrix[1]
        val tx = matrix[2]
        val c = matrix[3]
        val d = matrix[4]
        val ty = matrix[5]
        val determinant = a * d - b * c
        require(determinant.isFinite() && abs(determinant) > MATRIX_EPSILON) { "方向矩阵不可逆" }
        return doubleArrayOf(
            d / determinant,
            -b / determinant,
            (b * ty - d * tx) / determinant,
            -c / determinant,
            a / determinant,
            (c * tx - a * ty) / determinant,
            0.0,
            0.0,
            1.0
        )
    }

    private fun identityMatrix(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )

    private const val MATRIX_EPSILON: Double = 1e-12
}
