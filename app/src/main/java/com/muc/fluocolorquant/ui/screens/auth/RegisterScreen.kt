package com.muc.fluocolorquant.ui.screens.auth

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * 注册路由入口。
 *
 * 旧实现是一套独立表单，外观与登录页完全不同，而且注册按钮**不会真正注册**——它只是
 * 导航回登录页，源码里留着"这里应该实现注册逻辑"的待办注释。该路由此前也没有任何页面
 * 会导航过来，属于不可达的坏界面。
 *
 * 现在改为复用 [AuthScreen] 并以注册模式进入：外观与登录页一致，按钮真正调用
 * `UserViewModel.register`，注册成功后按统一逻辑进入主页。
 */
@Composable
fun RegisterScreen(navController: NavController) {
    AuthScreen(navController = navController, initialMode = AuthMode.REGISTER)
}
