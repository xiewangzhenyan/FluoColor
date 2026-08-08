package com.muc.fluocolorquant.ui.screens.auth

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * 登录路由入口。
 *
 * 界面实现见 [AuthScreen]：登录与注册共用同一套表单、同一套视觉和同一条提交链路，
 * 页面内可直接切换模式，不需要在两个路由之间跳转。
 */
@Composable
fun LoginScreen(navController: NavController) {
    AuthScreen(navController = navController, initialMode = AuthMode.LOGIN)
}
