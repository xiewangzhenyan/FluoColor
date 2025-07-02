package com.muc.fluocolorquant.data.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.muc.fluocolorquant.data.enums.FittingFunction
import com.muc.fluocolorquant.data.enums.PixelType
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
} 