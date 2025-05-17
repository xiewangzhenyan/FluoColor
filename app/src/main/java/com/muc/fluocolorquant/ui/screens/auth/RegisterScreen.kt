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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.muc.fluocolorquant.ui.components.PrimaryButton
import com.muc.fluocolorquant.ui.components.StandardTextField
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(navController: NavController) {
    val username = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val confirmPassword = remember { mutableStateOf("") }
    
    // 动画状态
    val isFormVisible = remember { mutableStateOf(false) }
    val isHeaderVisible = remember { mutableStateOf(false) }
    
    // 启动动画序列
    LaunchedEffect(key1 = true) {
        delay(100) // 短暂延迟使动画顺序可见
        isHeaderVisible.value = true
        delay(300)
        isFormVisible.value = true
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // 标题部分带动画
                AnimatedVisibility(
                    visible = isHeaderVisible.value,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -50 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -50 })
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "创建账户",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        Text(
                            text = "加入FluoColorQuant，开始您的高通量检测之旅",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )
                    }
                }
                
                // 表单部分带动画
                AnimatedVisibility(
                    visible = isFormVisible.value,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { 150 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { 150 })
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        StandardTextField(
                            value = username.value,
                            onValueChange = { username.value = it },
                            label = "用户名",
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        StandardTextField(
                            value = email.value,
                            onValueChange = { email.value = it },
                            label = "电子邮箱",
                            keyboardType = KeyboardType.Email,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        StandardTextField(
                            value = password.value,
                            onValueChange = { password.value = it },
                            label = "密码",
                            keyboardType = KeyboardType.Password,
                            isPassword = true,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        StandardTextField(
                            value = confirmPassword.value,
                            onValueChange = { confirmPassword.value = it },
                            label = "确认密码",
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                            isPassword = true,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                        
                        PrimaryButton(
                            text = "注册",
                            onClick = {
                                // 这里应该实现注册逻辑
                                // 成功注册后跳转到登录界面
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.Register.route) { inclusive = true }
                                }
                            },
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
} 