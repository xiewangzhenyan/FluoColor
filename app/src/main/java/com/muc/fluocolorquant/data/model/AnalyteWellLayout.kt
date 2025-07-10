package com.muc.fluocolorquant.data.model

/**
 * 分析物孔位布局数据类
 * 用于存储特定分析物的孔位布局信息
 */
data class AnalyteWellLayout(
    val analyteId: String,                      // 分析物ID
    val analyteName: String,                    // 分析物名称
    val wellAssignments: List<WellAssignment>,  // 孔位分配列表
    val templateId: String? = null              // 关联的模板ID（如果使用了模板）
)

/**
 * 孔位分配数据类
 * 表示单个孔位的分配信息
 */
data class WellAssignment(
    val wellIndex: Int,                         // 孔位索引
    val roleType: String,                       // 角色类型（如STANDARD, SAMPLE等）
    val concentration: Double? = null,          // 浓度值（对于标准品）
    val virtualRow: Int? = null,                // 虚拟布局中的行索引
    val virtualCol: Int? = null                 // 虚拟布局中的列索引
)

/**
 * 模板布局数据类
 * 用于序列化和反序列化实验模板的默认布局
 */
data class TemplateLayout(
    val analyteId: String,                      // 分析物ID
    val wellAssignments: List<WellAssignment>   // 孔位分配列表
) 