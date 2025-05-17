package com.muc.fluocolorquant.data.repository

import com.muc.fluocolorquant.data.dao.UserDao
import com.muc.fluocolorquant.data.model.User
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepository @Inject constructor(
    private val userDao: UserDao
) {
    suspend fun registerUser(username: String, password: String, email: String? = null): Result<User> {
        return try {
            // 检查用户名是否已存在
            val existingUser = userDao.getUserByUsername(username)
            if (existingUser != null) {
                Result.failure(Exception("用户名已被使用"))
            } else {
                val user = User(username = username, password = password, email = email)
                val userId = userDao.insertUser(user)
                Result.success(user.copy(id = userId))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loginUser(username: String, password: String): Result<User> {
        return try {
            val user = userDao.validateCredentials(username, password)
            if (user != null) {
                Result.success(user)
            } else {
                Result.failure(Exception("用户名或密码不正确"))
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
} 