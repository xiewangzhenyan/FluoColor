package com.muc.fluocolorquant.data.security

import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.repository.UserRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    private val hasher = Pbkdf2PasswordHasher()

    @Test
    fun `同一密码使用随机盐且都能验证`() {
        val first = hasher.hash("safe-password")
        val second = hasher.hash("safe-password")

        assertNotEquals(first, second)
        assertTrue(hasher.verify("safe-password", first).matches)
        assertTrue(hasher.verify("safe-password", second).matches)
        assertFalse(hasher.verify("wrong-password", first).matches)
    }

    @Test
    fun `旧明文只在匹配时要求升级且损坏摘要安全失败`() {
        val matchingLegacy = hasher.verify("legacy", "legacy")
        val wrongLegacy = hasher.verify("wrong", "legacy")

        assertTrue(matchingLegacy.matches)
        assertTrue(matchingLegacy.needsUpgrade)
        assertFalse(wrongLegacy.matches)
        assertFalse(hasher.verify("x", "pbkdf2-sha256-v1:bad:not-hex:00").matches)
    }

    @Test
    fun `包含冒号的旧明文仍可登录升级而未知摘要版本失败闭合`() {
        val legacy = "old:password:with:colon"

        val verification = hasher.verify(legacy, legacy)

        assertTrue(verification.matches)
        assertTrue(verification.needsUpgrade)
        assertFalse(hasher.verify("anything", "pbkdf2-unknown-v9:1:00:00").matches)
    }

    @Test
    fun `旧用户成功登录后一次性升级且失败登录不改库`() = runTest {
        val dao = FakeUserDao(User(id = 7, username = "legacy", password = "plain-text"))
        val repository = UserRepository(dao, hasher)

        assertTrue(repository.loginUser("legacy", "wrong").isFailure)
        assertEquals("plain-text", dao.user?.password)

        val loggedIn = repository.loginUser("legacy", "plain-text")
        assertTrue(loggedIn.isSuccess)
        assertNotEquals("plain-text", dao.user?.password)
        assertTrue(hasher.verify("plain-text", requireNotNull(dao.user).password).matches)
    }

    @Test
    fun `注册和修改密码从不写入明文`() = runTest {
        val dao = FakeUserDao()
        val repository = UserRepository(dao, hasher)

        val registered = repository.registerUser("new-user", "first-password")
        assertTrue(registered.isSuccess)
        assertNotEquals("first-password", dao.user?.password)

        assertTrue(repository.changePassword(1, "first-password", "second-password"))
        assertFalse(hasher.verify("first-password", requireNotNull(dao.user).password).matches)
        assertTrue(hasher.verify("second-password", requireNotNull(dao.user).password).matches)
    }

    private class FakeUserDao(initial: User? = null) : UserDao {
        var user: User? = initial

        override suspend fun insertUser(user: User): Long {
            val id = user.id.takeIf { it != 0L } ?: 1L
            this.user = user.copy(id = id)
            return id
        }

        override suspend fun getUserByUsername(username: String): User? =
            user?.takeIf { it.username == username }

        override suspend fun getUserById(userId: Long): User? = user?.takeIf { it.id == userId }

        override suspend fun updateUser(user: User) {
            this.user = user
        }
    }
}
