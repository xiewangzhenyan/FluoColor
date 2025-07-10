package com.muc.fluocolorquant.data.enums

import androidx.compose.ui.graphics.Color

/**
 * 孔位角色类型枚举类
 * 定义孔位在实验中的角色，如标准品、样本、空白对照等
 */
enum class WellRoleType(
    val code: String,
    val displayName: String,
    val shortName: String,
    val color: Color
) {
    STANDARD("STANDARD", "标准品", "Std", Color(0xFF2196F3)), // 蓝色
    SAMPLE("SAMPLE", "样本", "Spl", Color(0xFF4CAF50)),       // 绿色
    BLANK("BLANK", "空白对照", "Blk", Color(0xFFBDBDBD)),     // 灰色
    QUALITY_CONTROL("QC", "质控品", "QC", Color(0xFFFF9800)), // 橙色
    UNKNOWN("UNKNOWN", "未知", "Unk", Color(0xFF9C27B0)),     // 紫色
    NONE("NONE", "未分配", "", Color(0xFFFFFFFF));            // 白色
    
    companion object {
        /**
         * 根据代码获取角色类型
         * @param code 角色类型代码
         * @return 对应的角色类型枚举，如果未找到则返回NONE
         */
        fun fromCode(code: String?): WellRoleType {
            if (code == null) return NONE
            return values().find { it.code.equals(code, ignoreCase = true) } ?: NONE
        }
    }
} 