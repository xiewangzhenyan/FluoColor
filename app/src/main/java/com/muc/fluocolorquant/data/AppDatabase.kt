package com.muc.fluocolorquant.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.dao.ProjectDao
import com.muc.fluocolorquant.data.dao.DetectionRunDao
import com.muc.fluocolorquant.data.dao.WellResultDao
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.model.Project
import com.muc.fluocolorquant.data.model.DetectionRun
import com.muc.fluocolorquant.data.model.WellResult
import java.util.Date

@Database(
    entities = [
        User::class,
        Project::class,
        DetectionRun::class,
        WellResult::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun projectDao(): ProjectDao
    abstract fun detectionRunDao(): DetectionRunDao
    abstract fun wellResultDao(): WellResultDao
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
} 