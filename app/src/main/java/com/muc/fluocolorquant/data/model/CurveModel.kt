package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.muc.fluocolorquant.data.converters.Converters
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import java.util.Date
import java.util.UUID

/**
 * 曲线模型数据类
 * 
 * @param id 模型唯一标识符
 * @param name 模型名称
 * @param function 拟合函数类型
 * @param pixelType 像素类型
 * @param parameters 函数参数映射表
 * @param metrics 评估指标映射表
 * @param dataPoints 原始数据点列表
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
@Entity(
    tableName = "curve_models"
)
@TypeConverters(Converters::class)
data class CurveModel(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val function: FittingFunction,
    val pixelType: PixelType,
    val parameters: Map<String, Double>,
    val metrics: Map<String, Double>? = null,
    val dataPoints: List<Pair<Double, Double>>? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
) 