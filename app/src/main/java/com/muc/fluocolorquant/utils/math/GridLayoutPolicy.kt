package com.muc.fluocolorquant.utils.math

import com.muc.fluocolorquant.data.model.Project

/**
 * 通用规则阵列的行列定义。
 *
 * [rows] 和 [columns] 都表示界面及数据库中的真实方向；所有线性索引统一采用
 * `row * columns + column` 的行优先顺序，禁止再隐式套用固定 8×12 映射。
 */
data class GridDimensions(
    val rows: Int,
    val columns: Int
) {
    /** 当前阵列的物理位点总数。 */
    val siteCount: Int = rows * columns
}

/**
 * 规则阵列尺寸及旧项目方向兼容策略。
 *
 * 载体档案当前允许每个方向 1..99，因此旧检测偏好也复用同一边界。历史版本曾把
 * 默认 96 孔板保存为 12 行 × 8 列，同时又用固定 8×12 虚拟板解释其索引；只有这种
 * 明确的无模板旧项目会被归一化回 8×12。新模板项目即使恰好是 12×8，也必须保留
 * 载体声明的真实方向。
 */
object GridLayoutPolicy {
    const val MIN_DIMENSION: Int = 1
    const val MAX_DIMENSION: Int = 99
    const val LEGACY_PLATE_ROWS: Int = 8
    const val LEGACY_PLATE_COLUMNS: Int = 12

    val legacyPlateDimensions: GridDimensions = GridDimensions(
        rows = LEGACY_PLATE_ROWS,
        columns = LEGACY_PLATE_COLUMNS
    )

    /**
     * 校验自定义行列是否位于载体档案支持的边界内。
     * 使用逐维校验可以避免乘法溢出，也不会再把总位点数错误限制为 96。
     */
    fun isValid(rows: Int, columns: Int): Boolean {
        return isValidDimension(rows) && isValidDimension(columns)
    }

    /** 校验单个方向，供允许行列分别高亮错误的表单复用。 */
    fun isValidDimension(value: Int): Boolean = value in MIN_DIMENSION..MAX_DIMENSION

    /**
     * 解析项目实际应使用的显示与索引尺寸。
     *
     * 损坏的旧记录使用 8×12 只读兜底，保证历史结果页仍可打开；保存新配置时必须
     * 先通过 [isValid]，不能依赖这里的兼容回退。
     */
    fun resolveProject(project: Project?): GridDimensions {
        if (project == null) return legacyPlateDimensions

        val isTemplateBacked = !project.templateId.isNullOrBlank() ||
            !project.templateSnapshotJson.isNullOrBlank()
        return resolve(
            rows = project.rows,
            columns = project.columns,
            isTemplateBacked = isTemplateBacked
        )
    }

    /** 供纯 Kotlin 测试和非 UI 调用使用的基础解析函数。 */
    fun resolve(rows: Int, columns: Int, isTemplateBacked: Boolean): GridDimensions {
        if (!isTemplateBacked && rows == LEGACY_PLATE_COLUMNS && columns == LEGACY_PLATE_ROWS) {
            return legacyPlateDimensions
        }
        return if (isValid(rows, columns)) {
            GridDimensions(rows = rows, columns = columns)
        } else {
            legacyPlateDimensions
        }
    }
}
