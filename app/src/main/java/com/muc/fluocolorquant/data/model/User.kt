package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val password: String,
    val email: String? = null,
    val profilePicUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) 