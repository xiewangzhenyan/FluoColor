package com.muc.fluocolorquant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "session_prefs")

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository
) {
    private val USER_ID_KEY = longPreferencesKey("user_id")
    private val USERNAME_KEY = stringPreferencesKey("username")

    // 获取当前登录的用户ID
    val userIdFlow: Flow<Long?> = context.dataStore.data.map { preferences ->
        preferences[USER_ID_KEY]
    }

    // 获取当前登录的用户名
    val usernameFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[USERNAME_KEY]
    }

    // 检查用户是否已登录
    suspend fun isLoggedIn(): Boolean {
        return getCurrentUser() != null
    }

    // 保存登录会话
    suspend fun saveSession(user: User) {
        context.dataStore.edit { preferences ->
            preferences[USER_ID_KEY] = user.id
            preferences[USERNAME_KEY] = user.username
        }
    }

    // 清除会话
    suspend fun clearSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(USER_ID_KEY)
            preferences.remove(USERNAME_KEY)
        }
    }

    // 获取当前登录的用户信息
    suspend fun getCurrentUser(): User? {
        val userId = userIdFlow.first() ?: return null
        val user = userRepository.getUserById(userId)
        if (user == null) {
            clearSession()
        }
        return user
    }
} 
