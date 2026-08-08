package com.muc.fluocolorquant.data

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 确保本地数据库始终存在一个可直接登录的默认账户。
 *
 * 当前项目的登录模块仍使用数据库明文密码进行校验，因此这里保持与现有认证协议一致。
 * 初始化采用 `WHERE NOT EXISTS` 而不是 `INSERT OR IGNORE`：`users.username` 当前没有唯一
 * 索引，仅使用 `OR IGNORE` 无法阻止重复用户名。该写法具有以下行为：
 *
 * 1. 新数据库首次打开时创建 `user / 123456`；
 * 2. 已有数据库缺少该账户时自动补齐；
 * 3. 用户修改密码后不会在下次打开数据库时被默认密码覆盖；
 * 4. 不改变数据库表结构，因此不需要额外提升 Room 版本。
 */
object DefaultUserDatabaseCallback : RoomDatabase.Callback() {

    const val DEFAULT_USERNAME = "user"
    const val DEFAULT_PASSWORD = "123456"

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)

        db.execSQL(
            """
            INSERT INTO users (username, password, email, profilePicUrl, createdAt)
            SELECT ?, ?, NULL, NULL, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM users WHERE username = ?
            )
            """.trimIndent(),
            arrayOf<Any>(
                DEFAULT_USERNAME,
                DEFAULT_PASSWORD,
                System.currentTimeMillis(),
                DEFAULT_USERNAME
            )
        )
    }
}
