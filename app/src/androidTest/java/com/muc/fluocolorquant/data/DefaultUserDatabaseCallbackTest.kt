package com.muc.fluocolorquant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muc.fluocolorquant.data.security.Pbkdf2PasswordHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 默认登录账户数据库回调的真实 Room 验收测试。
 *
 * 测试直接打开和重新打开磁盘数据库，确认默认账户无需注册即可登录，同时保证用户修改过
 * 的密码不会在下一次启动时被初始化逻辑覆盖。
 */
@RunWith(AndroidJUnit4::class)
class DefaultUserDatabaseCallbackTest {

    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "default-user-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun 新数据库打开后可以使用默认账户登录() = runBlocking {
        val database = openDatabase()

        val defaultUser = database.userDao().getUserByUsername("user")

        assertNotNull(defaultUser)
        assertEquals("user", defaultUser?.username)
        assertNotEquals("123456", defaultUser?.password)
        assertEquals(
            true,
            Pbkdf2PasswordHasher().verify("123456", requireNotNull(defaultUser).password).matches
        )
        database.close()
    }

    @Test
    fun 再次打开数据库不会覆盖用户已经修改的密码() = runBlocking {
        val firstDatabase = openDatabase()
        val defaultUser = requireNotNull(firstDatabase.userDao().getUserByUsername("user"))
        val changedHash = Pbkdf2PasswordHasher().hash("changed-password")
        firstDatabase.userDao().updateUser(defaultUser.copy(password = changedHash))
        firstDatabase.close()

        val reopenedDatabase = openDatabase()

        val reopenedUser = requireNotNull(
            reopenedDatabase.userDao().getUserByUsername("user")
        )
        val hasher = Pbkdf2PasswordHasher()
        assertEquals(true, hasher.verify("changed-password", reopenedUser.password).matches)
        assertEquals(false, hasher.verify("123456", reopenedUser.password).matches)
        reopenedDatabase.close()
    }

    /** 创建与正式应用相同版本的数据库，并显式触发底层连接打开。 */
    private fun openDatabase(): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addCallback(DefaultUserDatabaseCallback)
            .build()
            .also { database -> database.openHelper.writableDatabase }
    }
}
