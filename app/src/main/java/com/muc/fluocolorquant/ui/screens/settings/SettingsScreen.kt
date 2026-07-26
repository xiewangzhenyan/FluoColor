package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.ui.navigation.Screen

/** 设置分组中的单个导航定义，只保存资源 ID 和路由，不保存可见硬编码文本。 */
private data class SettingsEntry(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val route: String,
    val badgeRes: Int? = null
)

/**
 * 重构后的系统设置主页。
 *
 * 页面不再把所有入口平铺为同一优先级，而是按应用数据、实验资源、设备载体和光谱
 * 资源分组。检测设置保留为旧流程兼容入口，新的科学参数进入版本化资源库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    // LazyListScope 本身不是 Composable 上下文，因此颜色需要在进入列表 DSL 前读取。
    val primaryAccent = MaterialTheme.colorScheme.primary
    val tertiaryAccent = MaterialTheme.colorScheme.tertiary
    val appDataEntries = listOf(
        SettingsEntry(
            R.string.app_settings,
            R.string.app_settings_desc,
            Icons.Default.Settings,
            Screen.AppSettings.route
        ),
        SettingsEntry(
            R.string.settings_detection_preferences_title,
            R.string.settings_detection_preferences_desc,
            Icons.Default.Tune,
            Screen.DetectionSettings.route,
            R.string.settings_compatibility_badge
        )
    )
    val experimentEntries = listOf(
        SettingsEntry(
            R.string.analyte_management_title,
            R.string.analyte_management_desc,
            Icons.Default.Biotech,
            Screen.AnalyteManagement.route
        ),
        SettingsEntry(
            R.string.library_reagent_title,
            R.string.library_reagent_desc,
            Icons.Default.Science,
            Screen.ReagentLibrary.route
        ),
        SettingsEntry(
            // 普通用户入口统一使用“标准曲线库”。旧资源仍保留给历史页面或兼容代码，
            // 但不再把标准曲线误称为需要用户理解内部实现的“曲线模型”。
            R.string.standard_curve_library_title,
            R.string.library_curve_model_desc,
            Icons.Default.Analytics,
            Screen.CurveModelLibrary.route
        ),
        SettingsEntry(
            R.string.calibration_settings_title,
            R.string.calibration_settings_desc,
            Icons.Default.Functions,
            Screen.CalibrationSettings.route
        ),
        SettingsEntry(
            R.string.library_template_title,
            R.string.library_template_desc,
            Icons.AutoMirrored.Filled.Article,
            Screen.ExperimentTemplateManagement.route
        )
    )
    val spectrumEntries = listOf(
        SettingsEntry(
            R.string.pref_spectrum_settings,
            R.string.pref_spectrum_settings_desc,
            Icons.Default.GraphicEq,
            Screen.SpectrumSettings.route,
            R.string.settings_spectrum_current_badge
        )
    )

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.settings_title),
                onBack = { navController.navigateUp() }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            settingsSection(
                titleRes = R.string.settings_group_app_data,
                icon = Icons.Default.Settings,
                accent = primaryAccent,
                entries = appDataEntries,
                navController = navController
            )
            settingsSection(
                titleRes = R.string.settings_group_experiment_resources,
                icon = Icons.Default.Science,
                accent = tertiaryAccent,
                entries = experimentEntries,
                navController = navController
            )
            settingsSection(
                titleRes = R.string.settings_group_spectrum_resources,
                icon = Icons.Default.GraphicEq,
                accent = primaryAccent,
                entries = spectrumEntries,
                navController = navController
            )
        }
    }
}

/** 向 LazyColumn 写入一个完整分组，避免四组页面结构复制。 */
private fun androidx.compose.foundation.lazy.LazyListScope.settingsSection(
    titleRes: Int,
    icon: ImageVector,
    accent: Color,
    entries: List<SettingsEntry>,
    navController: NavController
) {
    item {
        SettingsSectionHeader(
            title = stringResource(titleRes),
            icon = icon,
            accentColor = accent
        )
    }
    items(entries) { entry ->
        SettingsNavigationItem(
            title = stringResource(entry.titleRes),
            description = stringResource(entry.descriptionRes),
            icon = entry.icon,
            accentColor = accent,
            badge = entry.badgeRes?.let { stringResource(it) },
            onClick = { navController.navigate(entry.route) }
        )
    }
    item { Spacer(modifier = Modifier.height(8.dp)) }
}

/** 分组标题只保留细色条图标与标题，去掉说明文字以贴近学术工具的克制信息密度。 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: ImageVector,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(11.dp),
            color = accentColor.copy(alpha = 0.12f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = accentColor
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}
