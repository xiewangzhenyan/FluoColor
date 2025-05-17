package com.muc.fluocolorquant.ui.screens.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.ui.components.PrimaryButton
import com.muc.fluocolorquant.ui.components.SecondaryButton
import com.muc.fluocolorquant.ui.components.StandardTextField
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    navController: NavController,
    viewModel: UserViewModel = hiltViewModel()
) {
    val loginState by viewModel.loginState.collectAsState()
    
    // 如果已登录，直接导航到主页
    LaunchedEffect(loginState) {
        if (loginState == UserViewModel.LoginState.Success) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    var isLoginMode by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo和标题部分
            Spacer(modifier = Modifier.height(56.dp))
            
            // Logo图标（如果有的话）
            Icon(
                imageVector = Icons.Default.Science, // 或者使用自定义的logo
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            // 软件名称
            Text(
                text = "FluoColorQuant",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // 软件描述
            Text(
                text = "智能比色与荧光检测分析平台",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // 登录/注册表单
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 4.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isLoginMode) "账号登录" else "注册账号",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // 用户名输入框
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next
                        )
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 密码输入框
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(
                                    text = if (passwordVisible) "隐藏" else "显示",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )
                    
                    // 注册模式显示邮箱字段
                    if (!isLoginMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("邮箱 (可选)") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done
                            )
                        )
                    }

                    // 错误信息显示
                    if (loginState is UserViewModel.LoginState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = (loginState as UserViewModel.LoginState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // 登录/注册按钮
                    PrimaryButton(
                        text = if (isLoginMode) "登录" else "注册",
                        onClick = {
                            if (isLoginMode) {
                                viewModel.login(username, password)
                            } else {
                                viewModel.register(username, password, email.takeIf { it.isNotBlank() })
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = username.isNotBlank() && password.isNotBlank() && 
                                loginState != UserViewModel.LoginState.Loading
                    )
                }
            }
            
            // 加载指示器
            if (loginState == UserViewModel.LoginState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(16.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 切换登录/注册模式
            TextButton(
                onClick = { isLoginMode = !isLoginMode },
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = if (isLoginMode) "没有账号? 点击注册" else "已有账号? 点击登录",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
} 