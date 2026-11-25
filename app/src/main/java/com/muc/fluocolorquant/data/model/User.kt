package com.muc.fluocolorquant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    /** 仅保存版本化 PBKDF2 摘要；无前缀值只用于旧数据库一次性登录迁移。 */
    val password: String,
    val email: String? = null,
    val profilePicUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
