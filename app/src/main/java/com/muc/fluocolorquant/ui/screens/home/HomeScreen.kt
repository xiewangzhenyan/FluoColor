package com.muc.fluocolorquant.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.AnimatedPrimaryButton
import com.muc.fluocolorquant.ui.components.AnimatedSecondaryButton
import com.muc.fluocolorquant.utils.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.hilt.navigation.compose.hiltViewModel
import com.muc.fluocolorquant.ui.viewmodels.UserViewModel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import android.util.Log
import androidx.compose.material3.Button
import androidx.compose.ui.text.font.FontWeight

data class BottomNavItem(
    val title: String,
    val icon: ImageVector,
    val hasNews: Boolean = false,
    val badgeCount: Int? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    userViewModel: UserViewModel = hiltViewModel()
) {
    // 获取上下文
    val context = LocalContext.current
    
    // 底部导航项
    val bottomNavItems = listOf(
        BottomNavItem(title = "首页", icon = Icons.Default.Home),
        BottomNavItem(title = "历史", icon = Icons.Default.List),
        BottomNavItem(title = "关于", icon = Icons.Default.Info)
    )
    
    // 页面状态
    val pagerState = rememberPagerState(initialPage = 0) { bottomNavItems.size }
    val coroutineScope = rememberCoroutineScope()
    
    // 在顶部操作栏中添加用户信息和退出登录选项
    val currentUser by userViewModel.currentUser.collectAsState()
    
    // 监听导航返回事件，确保用户数据更新
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(navBackStackEntry) {
        // 当从个人信息页面返回时，刷新用户数据
        if (navBackStackEntry?.destination?.route == Screen.Home.route) {
            userViewModel.refreshUserData()
        }
    }
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "FluoColorQuant",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    UserMenu(
                        onLogout = {
                            userViewModel.logout()
                            navController.navigate(Screen.Login.route) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        },
                        userViewModel = userViewModel,
                        navController = navController
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEachIndexed { index, item ->
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
                                        modifier = Modifier.size(if (pagerState.currentPage == index) 28.dp else 24.dp),
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
                                        modifier = Modifier.size(if (pagerState.currentPage == index) 28.dp else 24.dp),
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
                                    modifier = Modifier.size(if (pagerState.currentPage == index) 28.dp else 24.dp),
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            when (page) {
                0 -> HomePageContent(navController)
                1 -> HistoryPageContent(navController)
                2 -> AboutPageContent()
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun HomePageContent(navController: NavController) {
    // 获取上下文
    val context = LocalContext.current

    // 动画状态控制
    val newProjectCardVisible = remember { MutableTransitionState(false) }
    val functionsCardVisible = remember { MutableTransitionState(false) }
    val headerVisible = remember { MutableTransitionState(false) }
    
    // 启动动画序列
    LaunchedEffect(key1 = true) {
        headerVisible.targetState = true
        delay(200)
        newProjectCardVisible.targetState = true
        delay(200)
        functionsCardVisible.targetState = true
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标题动画
        AnimatedVisibility(
            visibleState = headerVisible,
            enter = fadeIn(animationSpec = tween(500)) + 
                    slideInVertically(animationSpec = tween(500)) { it / 2 }
        ) {
            Text(
                text = "检测模式选择",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp, top = 8.dp)
            )
        }
        
        // 新建项目卡片
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
                    .clickable { navController.navigate(Screen.NewProject.route) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 4.dp
                )
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
                            contentDescription = "新建",
                            modifier = Modifier
                                .size(48.dp)
                                .padding(end = 16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "新建检测项目",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "创建新的荧光或比色检测项目",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { 
                            try {
                                navController.navigate(Screen.NewProject.route)
                            } catch (e: Exception) {
                                Log.e("HomeScreen", "导航错误: ${e.message}", e)
                                Toast.makeText(context, "导航错误: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("新建项目")
                    }
                }
            }
        }
        
        // 功能说明卡片
        AnimatedVisibility(
            visibleState = functionsCardVisible,
            enter = fadeIn(animationSpec = tween(500)) +
                    slideInVertically(animationSpec = tween(500)) { it / 2 }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 2.dp
                ),
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
                        text = "主要功能",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    FunctionItem(
                        title = "比色检测",
                        description = "通过颜色变化分析样品浓度"
                    )
                    
                    FunctionItem(
                        title = "荧光检测",
                        description = "通过荧光强度分析样品属性"
                    )
                    
                    FunctionItem(
                        title = "自动孔位识别",
                        description = "智能识别微孔板位置"
                    )
                    
                    FunctionItem(
                        title = "浓度曲线拟合",
                        description = "生成标准曲线并计算未知样品浓度"
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun HistoryPageContent(navController: NavController) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "历史记录",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        
        // 显示历史记录简要信息
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 图标
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = "历史记录",
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "查看您的历史项目记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = "在历史记录页面中，您可以查看、筛选、搜索和管理所有检测项目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 查看更多按钮
                Button(
                    onClick = { navController.navigate(Screen.History.route) },
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(vertical = 8.dp)
                ) {
                    Text("查看详细历史记录")
                }
            }
        }
        
        // 历史功能介绍卡片
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "历史记录功能",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                HistoryFeatureItem(
                    title = "项目管理",
                    description = "查看、筛选和管理您的检测项目"
                )
                
                HistoryFeatureItem(
                    title = "结果查看",
                    description = "随时回顾之前的检测结果"
                )
                
                HistoryFeatureItem(
                    title = "数据筛选",
                    description = "按时间范围和检测模式筛选记录"
                )
                
                HistoryFeatureItem(
                    title = "快速搜索",
                    description = "通过关键词快速定位项目"
                )
            }
        }
    }
}

@Composable
fun HistoryFeatureItem(
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer)
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
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun AboutPageContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "关于我们",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "FluoColorQuant",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "版本 1.0.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Text(
                    text = "FluoColorQuant是一款高效的比色与荧光高通量检测软件，通过先进的图像处理和机器学习算法，为您提供精准的传感器数据分析。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
                    text = "功能特点",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                FunctionItem(
                    title = "高精度检测",
                    description = "支持0.01%精度的荧光和比色分析"
                )
                
                FunctionItem(
                    title = "多种分析模式",
                    description = "支持单样品、标准曲线、多孔板等多种检测模式"
                )
                
                FunctionItem(
                    title = "数据导出与分享",
                    description = "一键导出数据和报告，支持多种格式"
                )
                
                FunctionItem(
                    title = "离线使用",
                    description = "无需网络连接，保护您的数据安全"
                )
            }
        }
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "联系我们",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                Text(
                    text = "邮箱: support@fluocolorquant.com\n网站: www.fluocolorquant.com",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FunctionItem(
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
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

@Composable
fun UserMenu(
    onLogout: () -> Unit,
    userViewModel: UserViewModel = hiltViewModel(),
    navController: NavController
) {
    var expanded by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentUser by userViewModel.currentUser.collectAsState()

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
                    contentDescription = "用户头像",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "用户菜单",
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
                        contentDescription = "用户头像",
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
                    text = currentUser?.username ?: "未知用户",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Divider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            // 设置选项
            DropdownMenuItem(
                text = { Text("设置") },
                onClick = { /* 处理设置点击 */ },
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
                text = { Text("个人信息") },
                onClick = {
                    expanded = false
                    navController.navigate(Screen.Profile.route)
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
                text = { Text("退出登录") },
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
                title = { Text("退出登录") },
                text = { Text("确定要退出登录吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onLogout()
                            showLogoutDialog = false
                            navController.navigate(Screen.Login.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showLogoutDialog = false }
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
} 