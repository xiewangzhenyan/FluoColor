package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.security.PasswordHasher
import javax.inject.Inject
import javax.inject.Singleton

/** 账户操作失败的稳定领域原因；UI 根据当前语言映射文案，仓库不返回硬编码中文。 */
enum class UserAccountError {
    USERNAME_TAKEN,
    INVALID_CREDENTIALS,
    LOGIN_FAILED,
    REGISTER_FAILED
}

class UserAccountException(
    val reason: UserAccountError,
    cause: Throwable? = null
) : Exception(reason.name, cause)

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao,
    private val passwordHasher: PasswordHasher
) {
    suspend fun registerUser(username: String, password: String, email: String? = null): Result<User> {
        return try {
            // 检查用户名是否已存在
            val existingUser = userDao.getUserByUsername(username)
            if (existingUser != null) {
                Result.failure(UserAccountException(UserAccountError.USERNAME_TAKEN))
            } else {
                val user = User(
                    username = username,
                    password = passwordHasher.hash(password),
                    email = email
                )
                val userId = userDao.insertUser(user)
                Result.success(user.copy(id = userId))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loginUser(username: String, password: String): Result<User> {
        return try {
            val user = userDao.getUserByUsername(username)
            val verification = user?.let { passwordHasher.verify(password, it.password) }
            if (user != null && verification?.matches == true) {
                val securedUser = if (verification.needsUpgrade) {
                    // 旧版明文只允许在一次成功认证后迁移，失败登录绝不能改写数据库。
                    val upgraded = user.copy(password = passwordHasher.hash(password))
                    userDao.updateUser(upgraded)
                    upgraded
                } else {
                    user
                }
                Result.success(securedUser)
            } else {
                Result.failure(UserAccountException(UserAccountError.INVALID_CREDENTIALS))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserById(userId: Long): User? {
        return userDao.getUserById(userId)
    }

    suspend fun updateUser(user: User) {
        userDao.updateUser(user)
    }

    suspend fun changePassword(userId: Long, oldPassword: String, newPassword: String): Boolean {
        val user = userDao.getUserById(userId) ?: return false
        if (!passwordHasher.verify(oldPassword, user.password).matches) return false
        userDao.updateUser(user.copy(password = passwordHasher.hash(newPassword)))
        return true
    }
}
