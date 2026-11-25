package com.muc.fluocolorquant.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.muc.fluocolorquant.data.SessionManager
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.data.repository.UserRepository
import com.muc.fluocolorquant.data.repository.UserAccountError
import com.muc.fluocolorquant.data.repository.UserAccountException
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
        checkLoginStatus()
    }

    /**
     * 启动时同步一次会话状态，避免残留的 userId 让界面误判为已登录。
     */
    private fun checkLoginStatus() {
        viewModelScope.launch {
            syncSessionState()
        }
    }

    /**
     * 以“能拿到有效用户实体”为准同步登录状态。
     * 当 DataStore 中只剩残留 userId、用户实体已不存在时，会自动回到未登录状态。
     */
    private suspend fun syncSessionState() {
        val user = sessionManager.getCurrentUser()
        if (user != null) {
            _currentUser.value = user
            _loginState.value = LoginState.Success
        } else {
            _currentUser.value = null
            _loginState.value = LoginState.Idle
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
                    _loginState.value = LoginState.Error(
                        (result.exceptionOrNull() as? UserAccountException)?.reason
                            ?: UserAccountError.LOGIN_FAILED
                    )
                }
            } catch (_: Exception) {
                _loginState.value = LoginState.Error(UserAccountError.LOGIN_FAILED)
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
                    _loginState.value = LoginState.Error(
                        (result.exceptionOrNull() as? UserAccountException)?.reason
                            ?: UserAccountError.REGISTER_FAILED
                    )
                }
            } catch (_: Exception) {
                _loginState.value = LoginState.Error(UserAccountError.REGISTER_FAILED)
            }
        }
    }

    /**
     * 退出登录时先清理会话，再统一回调页面做导航，避免出现“未知用户”中间态卡住页面。
     */
    fun logout(onLogoutComplete: () -> Unit) {
        viewModelScope.launch {
            sessionManager.clearSession()
            _currentUser.value = null
            _loginState.value = LoginState.Idle
            withContext(Dispatchers.Main) {
                onLogoutComplete()
            }
        }
    }

    /**
     * 页面恢复时刷新会话状态；如果当前会话已经失效，会同步回到未登录状态。
     */
    fun refreshUserData() {
        viewModelScope.launch {
            syncSessionState()
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
            } catch (_: Exception) {
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
            } catch (_: Exception) {
            }
        }
    }

    suspend fun changePassword(
        userId: Long,
        oldPassword: String,
        newPassword: String
    ): Boolean {
        return try {
            userRepository.changePassword(userId, oldPassword, newPassword)
        } catch (_: Exception) {
            false
        }
    }

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object Success : LoginState()
        data class Error(val reason: UserAccountError) : LoginState()
    }
}
