package com.muc.fluocolorquant.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.muc.fluocolorquant.data.security.Pbkdf2PasswordHasher

/**
 * 确保本地数据库始终存在一个可直接登录的默认账户。
 *
 * 新建默认账户只写入随机盐 PBKDF2 摘要；旧数据库中的明文默认密码由登录成功后的仓库
 * 一次性升级。初始化先查询再哈希，避免每次打开数据库都无意义执行昂贵 KDF。
 *
 * 1. 新数据库首次打开时创建 `user / 123456`；
 * 2. 已有数据库缺少该账户时自动补齐；
 * 3. 用户修改密码后不会在下次打开数据库时被默认密码覆盖；
 * 4. 不改变数据库表结构，`password` 列继续承载版本化摘要。
 */
object DefaultUserDatabaseCallback : RoomDatabase.Callback() {

    const val DEFAULT_USERNAME = "user"
    const val DEFAULT_PASSWORD = "123456"

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)

        val defaultUserExists = db.query(
            "SELECT EXISTS(SELECT 1 FROM users WHERE username = ? LIMIT 1)",
            arrayOf(DEFAULT_USERNAME)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
        if (defaultUserExists) return

        val passwordHash = Pbkdf2PasswordHasher().hash(DEFAULT_PASSWORD)

        db.execSQL(
            """
            INSERT INTO users (username, password, email, profilePicUrl, createdAt)
            VALUES (?, ?, NULL, NULL, ?)
            """.trimIndent(),
            arrayOf<Any>(
                DEFAULT_USERNAME,
                passwordHash,
                System.currentTimeMillis()
            )
        )
    }
}
