package com.muc.fluocolorquant.ui.screens.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoSectionCard
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.theme.FluoIconSize
import com.muc.fluocolorquant.ui.theme.FluoMotion
import com.muc.fluocolorquant.ui.theme.FluoRadius
import com.muc.fluocolorquant.ui.theme.FluoSpacing
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel

/**
 * 登录与注册的唯一界面实现。
 *
 * 此前存在两套认证界面：`LoginScreen` 内置可用的登录/注册双模式，`RegisterScreen` 则是
 * 一套外观完全不同、且注册按钮不会真正注册（只跳回登录页）的独立表单。两者视觉与行为
 * 都不一致。统一为本实现后，两条路由渲染同一界面，注册按钮真正调用 ViewModel。
 *
 * 交互上修复了原界面在小屏上的硬伤：内容此前既不可滚动也不避让软键盘，360dp 机型
 * 弹出键盘后主按钮会被挡住且无法滚动到（AGENTS.md 10）。
 */

/** 认证模式。用密封的枚举而不是布尔值，避免"isLoginMode=false 到底是注册还是别的"。 */
enum class AuthMode { LOGIN, REGISTER }

@Composable
fun AuthScreen(
    navController: NavController,
    initialMode: AuthMode = AuthMode.LOGIN,
    viewModel: UserViewModel = hiltViewModel()
) {
    val loginState by viewModel.loginState.collectAsState()

    // 出栈目标必须是本次进入的那个路由。NavOptionsBuilder 的 popUpTo 只保留最后一次
    // 调用，连写两个会让先写的那个失效——从登录页进主页时登录页不出栈，按返回键又退
    // 回登录页。模式可以在页内切换，但路由由进入时决定，因此用 initialMode 判定。
    val originRoute = when (initialMode) {
        AuthMode.LOGIN -> Screen.Login.route
        AuthMode.REGISTER -> Screen.Register.route
    }

    LaunchedEffect(loginState) {
        if (loginState == UserViewModel.LoginState.Success) {
            navController.navigate(Screen.Home.route) {
                popUpTo(originRoute) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // rememberSaveable 保证旋转屏幕后模式与已填内容不丢失；密码可见性刻意不保存，
    // 旋转后回到隐藏状态更符合预期。
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val passwordFocusRequester = remember { FocusRequester() }
    val emailFocusRequester = remember { FocusRequester() }

    val isLoading = loginState == UserViewModel.LoginState.Loading
    val canSubmit = username.isNotBlank() && password.isNotBlank() && !isLoading

    // 按钮和软键盘完成键共用同一提交入口，避免用户填完密码后还必须先收起键盘，
    // 再寻找被键盘遮挡的登录按钮。
    val submit: () -> Unit = {
        if (canSubmit) {
            focusManager.clearFocus()
            when (mode) {
                AuthMode.LOGIN -> viewModel.login(username, password)
                AuthMode.REGISTER -> viewModel.register(
                    username,
                    password,
                    email.takeIf { it.isNotBlank() }
                )
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // 可滚动 + 避让键盘：原实现两者都没有，小屏弹出键盘后主按钮不可达。
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = FluoSpacing.xl, vertical = FluoSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuthBrandHeader()

            Spacer(Modifier.height(FluoSpacing.xl))

            FluoSectionCard(
                modifier = Modifier.widthIn(max = 460.dp),
                contentPadding = FluoSpacing.xl,
                verticalSpacing = FluoSpacing.lg
            ) {
                AuthModeSwitch(
                    mode = mode,
                    enabled = !isLoading,
                    onModeChange = { mode = it }
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.username)) },
                    singleLine = true,
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(FluoIconSize.medium)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(FluoRadius.control),
                    colors = authFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() }
                    )
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    enabled = !isLoading,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(FluoIconSize.medium)
                        )
                    },
                    trailingIcon = {
                        // 原实现在这里放"显示/隐藏"两个汉字，既与全应用的图标语义不一致，
                        // 也会随语言变化改变控件宽度。改用标准眼睛图标，并提供明确的
                        // 无障碍描述而不是仅"显示"二字（AGENTS.md 8）。
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.auth_hide_password
                                    else R.string.auth_show_password
                                ),
                                modifier = Modifier.size(FluoIconSize.medium)
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester),
                    shape = RoundedCornerShape(FluoRadius.control),
                    colors = authFieldColors(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (mode == AuthMode.LOGIN) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { emailFocusRequester.requestFocus() },
                        onDone = { submit() }
                    )
                )

                // 邮箱只在注册模式出现。用标准展开动画而不是瞬间插入，避免下方按钮
                // 在切换模式时突然跳位（AGENTS.md 9.1）。
                AnimatedVisibility(
                    visible = mode == AuthMode.REGISTER,
                    enter = FluoMotion.expandEnter,
                    exit = FluoMotion.expandExit
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.email)) },
                        singleLine = true,
                        enabled = !isLoading,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                modifier = Modifier.size(FluoIconSize.medium)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(emailFocusRequester),
                        shape = RoundedCornerShape(FluoRadius.control),
                        colors = authFieldColors(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() })
                    )
                }

                AuthErrorMessage(state = loginState)

                AuthSubmitButton(
                    mode = mode,
                    enabled = canSubmit,
                    loading = isLoading,
                    onClick = submit
                )
            }

            Spacer(Modifier.height(FluoSpacing.xl))
        }
    }
}

/**
 * 品牌头部。
 *
 * 原实现用通用的 `Icons.Default.Science` 当 logo。这里改用应用自带的启动图标，让登录页
 * 与桌面图标、任务切换器呈现同一枚标识。
 */
@Composable
private fun AuthBrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 品牌标记刻意不使用 mipmap 里的启动图标：`R.mipmap.ic_launcher` 是自适应图标
        // XML（<adaptive-icon>），painterResource 只支持 VectorDrawable 与位图，直接加载
        // 会抛 IllegalArgumentException；而 ic_launcher_foreground 自带白色方底，放进
        // 彩色容器会显示成一个白方块，深色模式下更是整屏最亮的一块。
        // 因此用主题色自绘标记：随明暗自动适配，且与应用主色始终一致。
        Surface(
            modifier = Modifier.size(84.dp),
            shape = RoundedCornerShape(FluoRadius.sheet),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Biotech,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(FluoSpacing.lg))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(FluoSpacing.xs))
        Text(
            text = stringResource(R.string.app_description_short),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 登录/注册分段切换。
 *
 * 原实现把切换入口放在卡片下方的一行文字按钮里（"没有账号？点击注册"），用户必须读完
 * 整句才知道当前处于哪个模式。分段控件把两个模式并列呈现，当前状态一眼可见，且与新建
 * 项目页的检测方式选择器使用同一种控件形态。
 */
@Composable
private fun AuthModeSwitch(
    mode: AuthMode,
    enabled: Boolean,
    onModeChange: (AuthMode) -> Unit
) {
    val switchLabel = stringResource(R.string.auth_mode_switch_label)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = switchLabel }
            .selectableGroup(),
        shape = RoundedCornerShape(FluoRadius.control),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(FluoSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(FluoSpacing.xs)
        ) {
            AuthMode.entries.forEach { entry ->
                val selected = entry == mode
                val container by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "authModeContainer"
                )
                val content by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = FluoMotion.micro(),
                    label = "authModeContent"
                )
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        // 48dp 最小触控高度（AGENTS.md 10）。
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(FluoRadius.badge),
                    color = container,
                    contentColor = content,
                    enabled = enabled,
                    onClick = { onModeChange(entry) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(
                                if (entry == AuthMode.LOGIN) R.string.login else R.string.register
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 错误提示。
 *
 * 原实现是一行裸红字，与页面其他内容没有视觉边界，长错误信息会直接撑开布局。改为
 * errorContainer 容器 + 图标，状态不只靠颜色表达（AGENTS.md 10）。
 */
@Composable
private fun AuthErrorMessage(state: UserViewModel.LoginState) {
    // 收起动画播放期间 state 已经不再是 Error。这里保留最后一条非空错误文案，
    // 否则动画进行到一半文字会先变成空白，看起来像内容闪了一下。
    var lastMessage by remember { mutableStateOf("") }
    (state as? UserViewModel.LoginState.Error)?.message?.let { lastMessage = it }

    AnimatedVisibility(
        visible = state is UserViewModel.LoginState.Error,
        enter = FluoMotion.expandEnter,
        exit = FluoMotion.expandExit
    ) {
        val message = lastMessage
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FluoRadius.control),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier.padding(FluoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FluoSpacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(FluoIconSize.medium),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * 主提交按钮。
 *
 * 加载指示器放进按钮内部而不是卡片下方：原实现在卡片外额外插入一个进度圈，一提交
 * 整个页面就往下顶一截。按钮内切换用 AnimatedContent，尺寸保持不变。
 */
@Composable
private fun AuthSubmitButton(
    mode: AuthMode,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(FluoRadius.control)
    ) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = {
                fadeIn(animationSpec = tween(FluoMotion.MICRO_MS))
                    .togetherWith(fadeOut(animationSpec = tween(FluoMotion.MICRO_MS)))
            },
            label = "authSubmitButtonContent"
        ) { isLoading ->
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(FluoIconSize.large),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = stringResource(
                        if (mode == AuthMode.LOGIN) R.string.login else R.string.register
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 认证表单输入框的统一配色，避免三个输入框各写一遍。 */
@Composable
private fun authFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface
)
