package com.muc.fluocolorquant.ui.screens.home

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.data.model.User
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.screens.history.HistoryScreen
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

data class BottomNavItem(
    val title: String,
    val icon: ImageVector,
    val hasNews: Boolean = false,
    val badgeCount: Int? = null
)

/** 关于页可展开的信息区块；同一时间只允许展开一个区块。 */
private enum class AboutDetailSection {
    LABORATORY,
    CONTACT,
    PRIVACY,
    LICENSE,
    UPDATE
}

private fun isLoginInvalid(currentUser: User?, unknownUserString: String): Boolean {
    return currentUser == null || currentUser.username == unknownUserString
}

private fun navigateToLogin(navController: NavController) {
    navController.navigate(Screen.Login.route) {
        launchSingleTop = true
        // 根导航图本身不是一个可安全弹出的页面目标；直接清除首页可避免会话失效后
        // “只弹提示却仍停留在首页”的死路，同时禁止返回键重新进入无效会话。
        popUpTo(Screen.Home.route) { inclusive = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    userViewModel: UserViewModel = hiltViewModel()
) {
    // 底部导航项
    val bottomNavItems = listOf(
        BottomNavItem(title = stringResource(R.string.home_tab), icon = Icons.Default.Home),
        BottomNavItem(title = stringResource(R.string.history_tab), icon = Icons.Default.List),
        BottomNavItem(title = stringResource(R.string.about_tab), icon = Icons.Default.Info)
    )

    // 页面状态
    val pagerState = rememberPagerState(initialPage = 0) { bottomNavItems.size }
    val coroutineScope = rememberCoroutineScope()

    // 位于历史或关于页时，系统返回键先回到首页；只有首页才交还给导航栈处理。
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage(0)
        }
    }

    val loginRequiredMessage = stringResource(R.string.login_required_redirect)
    val logoutSuccessMessage = stringResource(R.string.logout_success)

    // 监听导航返回事件，确保用户数据更新
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navBackStackEntry) {
        // 当从个人信息页面返回时，刷新用户数据
        if (navBackStackEntry?.destination?.route == Screen.Home.route) {
            userViewModel.refreshUserData()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEachIndexed { index, item ->
                    // 底部导航图标在选中时平滑放大，配合 Pager 的滑动形成连续反馈。
                    val iconSize by animateDpAsState(
                        targetValue = if (pagerState.currentPage == index) 28.dp else 24.dp,
                        animationSpec = tween(durationMillis = 220),
                        label = "bottomNavIconSize"
                    )
                    NavigationBarItem(
                        icon = {
                            if (item.badgeCount != null) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(text = item.badgeCount.toString()) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        modifier = Modifier.size(iconSize),
                                        tint = if (pagerState.currentPage == index)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else if (item.hasNews) {
                                BadgedBox(
                                    badge = { Badge() }
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        modifier = Modifier.size(iconSize),
                                        tint = if (pagerState.currentPage == index)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(iconSize),
                                    tint = if (pagerState.currentPage == index)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            // 三个顶层页面处于同一个 Pager 中，允许用户直接左右拖动切换。
            userScrollEnabled = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            ).absoluteValue.coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 在水平位移之外叠加轻微缩放和淡入淡出，避免生硬的整页切换。
                        val visibleFraction = 1f - pageOffset
                        alpha = lerp(0.82f, 1f, visibleFraction)
                        scaleX = lerp(0.975f, 1f, visibleFraction)
                        scaleY = lerp(0.975f, 1f, visibleFraction)
                    }
            ) {
                when (page) {
                    0 -> HomePagerPage(
                        navController = navController,
                        userViewModel = userViewModel,
                        loginRequiredMessage = loginRequiredMessage,
                        logoutSuccessMessage = logoutSuccessMessage
                    )
                    1 -> HistoryScreen(
                        navController = navController,
                        showBackNavigation = false
                    )
                    2 -> AboutPageContent()
                }
            }
        }
    }
}
/**
 * 首页 Pager 页面。
 *
 * 顶层 Scaffold 只负责共享底部导航，因此首页自己的标题栏放在页面内部，
 * 这样历史和关于页面可以拥有各自的标题，同时横向拖动时不会发生标题突变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePagerPage(
    navController: NavController,
    userViewModel: UserViewModel,
    loginRequiredMessage: String,
    logoutSuccessMessage: String
) {
    val toastManager = LocalToastManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(id = R.string.app_name),
                    style = MaterialTheme.typography.titleLarge
                )
            },
            actions = {
                UserMenu(
                    onLogout = {
                        userViewModel.logout {
                            toastManager.showToast(logoutSuccessMessage, ToastType.SUCCESS)
                            navigateToLogin(navController)
                        }
                    },
                    userViewModel = userViewModel,
                    navController = navController,
                    loginRequiredMessage = loginRequiredMessage
                )
            }
        )

        HomePageContent(
            navController = navController,
            userViewModel = userViewModel,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun HomePageContent(
    navController: NavController,
    userViewModel: UserViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val toastManager = LocalToastManager.current
    val currentUser by userViewModel.currentUser.collectAsState()

    val unknownUserString = stringResource(R.string.unknown_user)
    val loginRequiredMessage = stringResource(R.string.login_required_redirect)
    val navigationFailedMessage = stringResource(R.string.navigation_failed)
    val requireLoginThen: (() -> Unit) -> Unit = { onAuthenticated ->
        if (isLoginInvalid(currentUser, unknownUserString)) {
            toastManager.showToast(loginRequiredMessage, ToastType.WARNING)
            navigateToLogin(navController)
        } else {
            onAuthenticated()
        }
    }

    // 恢复原首页的分段入场动画，让标题、主项目卡和功能说明保持原有展示节奏。
    val newProjectCardVisible = remember { MutableTransitionState(false) }
    val functionsCardVisible = remember { MutableTransitionState(false) }
    val headerVisible = remember { MutableTransitionState(false) }

    LaunchedEffect(key1 = true) {
        headerVisible.targetState = true
        delay(200)
        newProjectCardVisible.targetState = true
        delay(200)
        functionsCardVisible.targetState = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visibleState = headerVisible,
            enter = fadeIn(animationSpec = tween(500)) +
                slideInVertically(animationSpec = tween(500)) { it / 2 }
        ) {
            Text(
                text = stringResource(R.string.new_detection_project),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
            )
        }

        AnimatedVisibility(
            visibleState = newProjectCardVisible,
            enter = fadeIn(animationSpec = tween(500)) +
                slideInVertically(animationSpec = tween(500)) { it / 2 }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        requireLoginThen {
                            navController.navigate(Screen.DirectCreateProject.route)
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(end = 16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.new_detection_project),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.create_new_project),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Image(
                        painter = painterResource(id = R.drawable.banner),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            requireLoginThen {
                                try {
                                    navController.navigate(Screen.DirectCreateProject.route)
                                } catch (e: Exception) {
                                    Log.e("HomeScreen", "导航错误: ${e.message}", e)
                                    toastManager.showToast(
                                        navigationFailedMessage.format(e.message ?: ""),
                                        ToastType.ERROR
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.new_project))
                    }
                }
            }
        }

        AnimatedVisibility(
            visibleState = functionsCardVisible,
            enter = fadeIn(animationSpec = tween(500)) +
                slideInVertically(animationSpec = tween(500)) { it / 2 }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.main_functions),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    FunctionItemWithIcon(
                        icon = Icons.Filled.Palette,
                        title = stringResource(R.string.colorimetric_detection),
                        description = stringResource(R.string.colorimetric_description)
                    )
                    FunctionItemWithIcon(
                        icon = Icons.Filled.Science,
                        title = stringResource(R.string.fluorescence_detection),
                        description = stringResource(R.string.fluorescence_description)
                    )
                    FunctionItemWithIcon(
                        icon = Icons.Filled.GraphicEq,
                        title = stringResource(R.string.spectrum_detection_title),
                        description = stringResource(R.string.spectrum_detection_desc)
                    )
                    FunctionItemWithIcon(
                        icon = Icons.Filled.GridOn,
                        title = stringResource(R.string.auto_well_recognition),
                        description = stringResource(R.string.auto_well_description)
                    )
                    FunctionItemWithIcon(
                        icon = Icons.Filled.ShowChart,
                        title = stringResource(R.string.concentration_curve),
                        description = stringResource(R.string.concentration_description)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPageContent() {
    var expandedSection by remember { mutableStateOf<AboutDetailSection?>(null) }
    val context = LocalContext.current
    val unknownVersion = stringResource(R.string.about_unknown_version)

    // 直接读取当前安装包信息，避免版本号写死，也不依赖项目是否生成 BuildConfig。
    val installedVersion = remember(context, unknownVersion) {
        runCatching {
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            (packageInfo.versionName ?: unknownVersion) to versionCode
        }.getOrElse { unknownVersion to 0L }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.about_tab),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 品牌摘要只展示一次应用名称；图标直接使用当前安装包的启动图标。
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(R.mipmap.ic_launcher),
                        contentDescription = stringResource(R.string.app_icon_description),
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.about_brand_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.about_version_format,
                                    installedVersion.first
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.app_description_short),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.about_core_capabilities),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AboutCapabilityCard(
                        icon = Icons.Default.Science,
                        title = stringResource(R.string.about_high_precision),
                        modifier = Modifier.weight(1f)
                    )
                    AboutCapabilityCard(
                        icon = Icons.Default.Assessment,
                        title = stringResource(R.string.about_multimodal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AboutCapabilityCard(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.about_research_export),
                        modifier = Modifier.weight(1f)
                    )
                    AboutCapabilityCard(
                        icon = Icons.Default.CloudOff,
                        title = stringResource(R.string.about_offline_analysis),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    ExpandableAboutInfoRow(
                        icon = Icons.Default.Science,
                        title = stringResource(R.string.about_laboratory_info),
                        detailText = stringResource(R.string.about_laboratory_detail),
                        expanded = expandedSection == AboutDetailSection.LABORATORY,
                        onToggle = {
                            expandedSection = expandedSection.toggle(AboutDetailSection.LABORATORY)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ExpandableAboutInfoRow(
                        icon = Icons.Default.Email,
                        title = stringResource(R.string.contact_us),
                        detailText = stringResource(R.string.about_contact_detail),
                        expanded = expandedSection == AboutDetailSection.CONTACT,
                        onToggle = {
                            expandedSection = expandedSection.toggle(AboutDetailSection.CONTACT)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ExpandableAboutInfoRow(
                        icon = Icons.Default.Security,
                        title = stringResource(R.string.about_privacy_policy),
                        detailText = stringResource(R.string.about_privacy_detail),
                        expanded = expandedSection == AboutDetailSection.PRIVACY,
                        onToggle = {
                            expandedSection = expandedSection.toggle(AboutDetailSection.PRIVACY)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ExpandableAboutInfoRow(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.about_open_source_license),
                        detailText = stringResource(R.string.about_license_detail),
                        expanded = expandedSection == AboutDetailSection.LICENSE,
                        onToggle = {
                            expandedSection = expandedSection.toggle(AboutDetailSection.LICENSE)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ExpandableAboutInfoRow(
                        icon = Icons.Default.SystemUpdate,
                        title = stringResource(R.string.about_check_updates),
                        detailText = stringResource(
                            R.string.about_update_detail,
                            installedVersion.first,
                            installedVersion.second
                        ),
                        trailingText = stringResource(
                            R.string.about_current_version_short,
                            installedVersion.first
                        ),
                        expanded = expandedSection == AboutDetailSection.UPDATE,
                        onToggle = {
                            expandedSection = expandedSection.toggle(AboutDetailSection.UPDATE)
                        }
                    )
                }
            }

            Text(
                text = stringResource(R.string.about_copyright),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 28.dp)
            )
        }
    }
}

/** 核心能力卡片：只保留图标和短标题，避免关于页再次堆积说明文字。 */
@Composable
private fun AboutCapabilityCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(78.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * 关于页信息手风琴行。
 *
 * 标题区始终保持 48dp 以上触控高度；展开时箭头旋转，详情同时执行淡入和高度动画。
 * 外层 [animateContentSize] 负责让分隔线及后续项目平滑移动，不产生突兀跳变。
 */
@Composable
private fun ExpandableAboutInfoRow(
    icon: ImageVector,
    title: String,
    detailText: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailingText: String? = null,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "aboutDetailArrowRotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 260))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .height(50.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (expanded) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.01f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (trailingText != null) {
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) {
                    stringResource(R.string.about_collapse_detail)
                } else {
                    stringResource(R.string.about_expand_detail)
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrowRotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                expandVertically(
                    animationSpec = tween(durationMillis = 260),
                    expandFrom = Alignment.Top
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 140)) +
                shrinkVertically(
                    animationSpec = tween(durationMillis = 220),
                    shrinkTowards = Alignment.Top
                )
        ) {
            Text(
                text = detailText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = 52.dp,
                    end = 16.dp,
                    bottom = 14.dp
                )
            )
        }
    }
}

/** 点击同一项时收起，点击另一项时切换为新展开项。 */
private fun AboutDetailSection?.toggle(target: AboutDetailSection): AboutDetailSection? {
    return if (this == target) null else target
}

@Composable
fun UserMenu(
    onLogout: () -> Unit,
    userViewModel: UserViewModel = hiltViewModel(),
    navController: NavController,
    loginRequiredMessage: String
) {
    var expanded by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val toastManager = LocalToastManager.current
    val currentUser by userViewModel.currentUser.collectAsState()

    val unknownUserString = stringResource(R.string.unknown_user)
    val requireLoginThen: (() -> Unit) -> Unit = { onAuthenticated ->
        if (isLoginInvalid(currentUser, unknownUserString)) {
            toastManager.showToast(loginRequiredMessage, ToastType.WARNING)
            navigateToLogin(navController)
        } else {
            onAuthenticated()
        }
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            if (currentUser?.profilePicUrl != null) {
                Image(
                    painter = rememberAsyncImagePainter(
                        ImageRequest.Builder(LocalContext.current)
                            .data(Uri.parse(currentUser?.profilePicUrl))
                            .size(Size.ORIGINAL)
                            .error(R.drawable.placeholder_image)
                            .placeholder(R.drawable.placeholder_image)
                            .build()
                    ),
                    contentDescription = stringResource(R.string.user_avatar),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = stringResource(R.string.user_menu),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .width(180.dp)
        ) {
            // 用户信息头部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentUser?.profilePicUrl != null) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(Uri.parse(currentUser?.profilePicUrl))
                                .size(Size.ORIGINAL)
                                .error(R.drawable.placeholder_image)
                                .placeholder(R.drawable.placeholder_image)
                                .build()
                        ),
                        contentDescription = stringResource(R.string.user_avatar),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentUser?.username ?: unknownUserString,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Divider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // 设置选项
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                onClick = {
                    expanded = false
                    requireLoginThen {
                        navController.navigate(Screen.Settings.route)
                    }
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            // 个人信息选项
            DropdownMenuItem(
                text = { Text(stringResource(R.string.profile)) },
                onClick = {
                    expanded = false
                    requireLoginThen {
                        navController.navigate(Screen.Profile.route)
                    }
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            )

            // 退出登录选项
            DropdownMenuItem(
                text = { Text(stringResource(R.string.logout)) },
                onClick = {
                    expanded = false
                    showLogoutDialog = true
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }

        // 退出登录确认对话框
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text(stringResource(R.string.confirm_logout)) },
                text = { Text(stringResource(R.string.confirm_logout_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLogoutDialog = false
                            onLogout()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showLogoutDialog = false }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
fun FunctionItemWithIcon(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
