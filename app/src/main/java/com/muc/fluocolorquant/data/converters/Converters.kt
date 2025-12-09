package com.muc.fluocolorquant.data.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
import com.muc.fluocolorquant.data.enums.SpectrumCalibrationType
import java.util.Date

/**
 * Room数据库类型转换器
 */
class Converters {
    private val gson = Gson()

    // Date 转换器
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    // Map<String, Double> 转换器
    @TypeConverter
    fun fromDoubleMap(map: Map<String, Double>?): String? {
        return map?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toDoubleMap(json: String?): Map<String, Double>? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<Map<String, Double>>() {}.type
        return gson.fromJson(json, type)
    }

    // List<Pair<Double, Double>> 转换器
    @TypeConverter
    fun fromDoublePairList(list: List<Pair<Double, Double>>?): String? {
        return list?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toDoublePairList(json: String?): List<Pair<Double, Double>>? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<List<Pair<Double, Double>>>() {}.type
        return gson.fromJson(json, type)
    }

    // List<Float> 转换器（用于光谱曲线数据）
    @TypeConverter
    fun fromFloatList(list: List<Float>?): String? {
        return list?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toFloatList(json: String?): List<Float>? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<List<Float>>() {}.type
        return gson.fromJson(json, type)
    }

    // Map<Int, Long> 转换器（用于光谱列 -> 分析物映射）
    @TypeConverter
    fun fromIntLongMap(map: Map<Int, Long>?): String? {
        return map?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toIntLongMap(json: String?): Map<Int, Long>? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<Map<Int, Long>>() {}.type
        return gson.fromJson(json, type)
    }

    // List<Double> 转换器（用于拟合系数等）
    @TypeConverter
    fun fromDoubleList(list: List<Double>?): String? {
        return list?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toDoubleList(json: String?): List<Double>? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<List<Double>>() {}.type
        return gson.fromJson(json, type)
    }

    // DoubleArray 转换器（兼容数组存储拟合系数）
    @TypeConverter
    fun fromDoubleArray(array: DoubleArray?): String? {
        return array?.let { gson.toJson(it.toList()) }
    }

    @TypeConverter
    fun toDoubleArray(json: String?): DoubleArray? {
        if (json.isNullOrEmpty()) return null
        val type = object : TypeToken<List<Double>>() {}.type
        return gson.fromJson<List<Double>>(json, type).toDoubleArray()
    }

    // FittingFunction 转换器
    @TypeConverter
    fun fromFittingFunction(function: FittingFunction?): String? {
        return function?.identifier
    }

    @TypeConverter
    fun toFittingFunction(identifier: String?): FittingFunction? {
        return identifier?.let { FittingFunction.fromIdentifier(it) }
    }

    // PixelType 转换器
    @TypeConverter
    fun fromPixelType(pixelType: PixelType?): String? {
        return pixelType?.identifier
    }

    @TypeConverter
    fun toPixelType(identifier: String?): PixelType? {
        return identifier?.let { PixelType.fromIdentifier(it) }
    }

    // SpectrumCalibrationType converter
    @TypeConverter
    fun fromSpectrumCalibrationType(type: SpectrumCalibrationType?): String? {
        return type?.code
    }

    @TypeConverter
    fun toSpectrumCalibrationType(code: String?): SpectrumCalibrationType? {
        return SpectrumCalibrationType.fromCode(code)
    }
}
