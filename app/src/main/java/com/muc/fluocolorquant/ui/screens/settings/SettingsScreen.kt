package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.navigation.Screen
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel

private const val TAG = "SettingsScreen"

/**
 * 系统设置主页面
 * 提供所有设置功能的入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val toastManager = LocalToastManager.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 应用设置
            SettingsNavigationItem(
                title = stringResource(R.string.app_settings),
                description = stringResource(R.string.app_settings_desc),
                icon = Icons.Default.Settings,
                onClick = { navController.navigate(Screen.AppSettings.route) }
            )

            // 检测设置
            SettingsNavigationItem(
                title = stringResource(R.string.detection_settings),
                description = stringResource(R.string.detection_settings_desc),
                icon = Icons.Default.DesignServices,
                onClick = { navController.navigate(Screen.DetectionSettings.route) }
            )

            // 光谱检测设置
            SettingsNavigationItem(
                title = stringResource(R.string.pref_spectrum_settings),
                description = stringResource(R.string.pref_spectrum_settings_desc),
                icon = Icons.Default.GraphicEq,
                onClick = { navController.navigate(Screen.SpectrumSettings.route) }
            )

            // 分析物管理
            SettingsNavigationItem(
                title = stringResource(R.string.analyte_management_title),
                description = stringResource(R.string.analyte_management_desc),
                icon = Icons.Default.Biotech,
                onClick = { navController.navigate(Screen.AnalyteManagement.route) }
            )
            
            // 试剂管理
            SettingsNavigationItem(
                title = stringResource(R.string.library_reagent_title),
                description = stringResource(R.string.library_reagent_desc),
                icon = Icons.Default.Science,
                onClick = { navController.navigate(Screen.ReagentLibrary.route) }
            )
            
            // 曲线模型管理
            SettingsNavigationItem(
                title = stringResource(R.string.library_curve_model_title),
                description = stringResource(R.string.library_curve_model_desc),
                icon = Icons.Default.Analytics,
                onClick = { navController.navigate(Screen.CurveModelLibrary.route) }
            )
            
            // 实验模板库
            SettingsNavigationItem(
                title = stringResource(R.string.library_template_title),
                description = stringResource(R.string.library_template_desc),
                icon = Icons.Default.Article,
                onClick = { navController.navigate(Screen.ExperimentTemplateManagement.route) }
            )
        }
    }
} 
