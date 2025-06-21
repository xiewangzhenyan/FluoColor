package com.muc.fluocolorquant.ui.screens.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import com.muc.fluocolorquant.ui.navigation.Screen
import kotlinx.coroutines.delay
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.ui.graphics.ColorFilter
import androidx.hilt.navigation.compose.hiltViewModel

//应用启动动画界面。
@Composable
fun SplashScreen(
    navController: NavController,
    userViewModel: UserViewModel = hiltViewModel()
) {
    // 动画状态
    var startAnimation by remember { mutableStateOf(false) }
    var progressAnimation by remember { mutableStateOf(0f) }
    var showProgressText by remember { mutableStateOf(false) }
    
    // 多个动画效果
    val alphaAnim = animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000)
    )
    
    val scaleAnim = animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.5f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
    )
    
    // 启动加载进度动画
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(500)
        showProgressText = true
        
        // 模拟加载进度
        repeat(100) {
            progressAnimation = (it + 1) / 100f
            delay(10)
        }
        
        delay(300)
        
        // 根据用户登录状态决定导航
        if (userViewModel.loginState.value == UserViewModel.LoginState.Success) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Splash.route) { inclusive = true }
            }
        } else {
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.Splash.route) { inclusive = true }
            }
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        // 中心内容区域
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .alpha(alphaAnim.value)
                .scale(scaleAnim.value)
                .padding(horizontal = 32.dp)
        ) {
            // 应用图标/Logo
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_holder),
                    contentDescription = stringResource(R.string.app_icon_description),
                    modifier = Modifier.size(80.dp),
                    colorFilter = ColorFilter.tint(Color.White),
                    contentScale = ContentScale.Fit
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 应用名称
            Text(
                text = stringResource(R.string.app_name_short),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            // 应用描述
            Text(
                text = stringResource(R.string.app_subtitle),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
            )
            
            // 进度指示器
            CircularProgressIndicator(
                progress = progressAnimation,
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
            
            // 进度文本
            AnimatedVisibility(
                visible = showProgressText,
                enter = fadeIn() + expandVertically()
            ) {
                Text(
                    text = stringResource(R.string.loading_progress, (progressAnimation * 100).toInt()),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        
        // 底部版权信息
        Text(
            text = stringResource(R.string.copyright),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .alpha(alphaAnim.value)
        )
    }
} 