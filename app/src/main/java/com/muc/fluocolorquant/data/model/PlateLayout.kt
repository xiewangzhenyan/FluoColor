package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 孔板布局实体类
 * 定义单个项目中每个孔位的角色和归属，是实现多分析物检测的关键
 */
@Entity(
    tableName = "plate_layouts",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analyte::class,
            parentColumns = ["id"],
            childColumns = ["analyteId"],
            onDelete = ForeignKey.SET_NULL // 如果分析物被删, 布局中对应项设为null
        )
    ],
    indices = [
        Index("projectId"), // 为 projectId 外键列添加索引
        Index("analyteId")  // 为 analyteId 外键列添加索引
    ]
)
data class PlateLayout(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: String,            // 外键: 关联到 Projects 表
    val wellIndex: Int,               // 孔位索引 (0-95)
    val analyteId: String?,           // 外键: 关联到 Analytes 表, 指明该孔属于哪个分析物
    val roleType: String              // 角色: "Sample", "Standard", "Blank", "QC", etc.
) 