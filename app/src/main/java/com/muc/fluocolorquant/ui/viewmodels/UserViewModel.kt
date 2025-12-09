package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.SessionManager
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class UserViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    init {
        // 检查是否已经登录
        checkLoginStatus()
    }

    private fun checkLoginStatus() {
        viewModelScope.launch {
            if (sessionManager.isLoggedIn()) {
                _loginState.value = LoginState.Success
                sessionManager.getCurrentUser()?.let { user ->
                    _currentUser.value = user
                }
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading

            try {
                val result = userRepository.loginUser(username, password)

                if (result.isSuccess) {
                    result.getOrNull()?.let { user ->
                        sessionManager.saveSession(user)
                        _currentUser.value = user
                        _loginState.value = LoginState.Success
                    }
                } else {
                    _loginState.value = LoginState.Error(result.exceptionOrNull()?.message ?: "登录失败")
                }
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(e.message ?: "登录出错")
            }
        }
    }

    fun register(username: String, password: String, email: String? = null) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading

            try {
                val result = userRepository.registerUser(username, password, email)

                if (result.isSuccess) {
                    result.getOrNull()?.let { user ->
                        sessionManager.saveSession(user)
                        _currentUser.value = user
                        _loginState.value = LoginState.Success
                    }
                } else {
                    _loginState.value = LoginState.Error(result.exceptionOrNull()?.message ?: "注册失败")
                }
            } catch (e: Exception) {
                _loginState.value = LoginState.Error(e.message ?: "注册出错")
            }
        }
    }

    // [MODIFIED] 修改 logout 函数，增加 onLogoutComplete 回调
    fun logout(onLogoutComplete: () -> Unit) {
        viewModelScope.launch {
            sessionManager.clearSession()
            _currentUser.value = null
            _loginState.value = LoginState.Idle
            // 切换到主线程执行UI导航操作
            withContext(Dispatchers.Main) {
                onLogoutComplete()
            }
        }
    }

    // 添加刷新用户数据的方法
    fun refreshUserData() {
        viewModelScope.launch {
            // 从 sessionManager 获取最新的用户信息
            sessionManager.getCurrentUser()?.let { user ->
                _currentUser.value = user
            }
        }
    }

    fun updateUserProfile(username: String, email: String) {
        viewModelScope.launch {
            try {
                currentUser.value?.let { user ->
                    val updatedUser = user.copy(
                        username = username,
                        email = email
                    )
                    userRepository.updateUser(updatedUser)
                    _currentUser.value = updatedUser
                }
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    suspend fun updateUserProfile(
        userId: Long,
        username: String? = null,
        email: String? = null,
        profilePicUrl: String? = null
    ) {
        viewModelScope.launch {
            try {
                val user = userRepository.getUserById(userId)
                user?.let {
                    val updatedUser = it.copy(
                        username = username ?: it.username,
                        email = email ?: it.email,
                        profilePicUrl = profilePicUrl ?: it.profilePicUrl
                    )
                    userRepository.updateUser(updatedUser)
                    _currentUser.value = updatedUser
                }
            } catch (e: Exception) {
                // 处理错误
            }
        }
    }

    suspend fun changePassword(
        userId: Long,
        oldPassword: String,
        newPassword: String
    ): Boolean {
        return try {
            val user = userRepository.getUserById(userId)

            // 验证旧密码是否正确
            if (user != null && user.password == oldPassword) {
                // 更新密码
                val updatedUser = user.copy(password = newPassword)
                userRepository.updateUser(updatedUser)
                true
            } else {
                // 旧密码不正确
                false
            }
        } catch (e: Exception) {
            // 发生异常
            false
        }
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object Success : LoginState()
        data class Error(val message: String) : LoginState()
    }
}