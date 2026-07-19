package com.muc.fluocolorquant.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
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
    val secondaryAccent = MaterialTheme.colorScheme.secondary
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
            R.string.library_curve_model_title,
            R.string.library_curve_model_desc,
            Icons.Default.Analytics,
            Screen.CurveModelLibrary.route,
            R.string.settings_compatibility_badge
        ),
        SettingsEntry(
            R.string.library_template_title,
            R.string.library_template_desc,
            Icons.AutoMirrored.Filled.Article,
            Screen.ExperimentTemplateManagement.route
        )
    )
    val deviceEntries = listOf(
        SettingsEntry(
            R.string.settings_acquisition_library_title,
            R.string.settings_acquisition_library_desc,
            Icons.Default.PhotoCamera,
            Screen.AcquisitionProfileManagement.route
        ),
        SettingsEntry(
            R.string.settings_carrier_library_title,
            R.string.settings_carrier_library_desc,
            Icons.Default.GridOn,
            Screen.CarrierProfileManagement.route
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
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
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
            item {
                SettingsHeroCard()
                Spacer(modifier = Modifier.height(10.dp))
            }

            settingsSection(
                titleRes = R.string.settings_group_app_data,
                descriptionRes = R.string.settings_group_app_data_desc,
                icon = Icons.Default.Settings,
                accent = primaryAccent,
                entries = appDataEntries,
                navController = navController
            )
            settingsSection(
                titleRes = R.string.settings_group_experiment_resources,
                descriptionRes = R.string.settings_group_experiment_resources_desc,
                icon = Icons.Default.Science,
                accent = tertiaryAccent,
                entries = experimentEntries,
                navController = navController
            )
            settingsSection(
                titleRes = R.string.settings_group_device_carrier,
                descriptionRes = R.string.settings_group_device_carrier_desc,
                icon = Icons.Default.GridOn,
                accent = secondaryAccent,
                entries = deviceEntries,
                navController = navController
            )
            settingsSection(
                titleRes = R.string.settings_group_spectrum_resources,
                descriptionRes = R.string.settings_group_spectrum_resources_desc,
                icon = Icons.Default.GraphicEq,
                accent = primaryAccent,
                entries = spectrumEntries,
                navController = navController
            )
        }
    }
}

/** 设置主页顶部说明卡，强调资源优先而不是全局开关优先。 */
@Composable
private fun SettingsHeroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = stringResource(R.string.settings_config_center_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.settings_config_center_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                )
            }
        }
    }
}

/** 向 LazyColumn 写入一个完整分组，避免四组页面结构复制。 */
private fun androidx.compose.foundation.lazy.LazyListScope.settingsSection(
    titleRes: Int,
    descriptionRes: Int,
    icon: ImageVector,
    accent: Color,
    entries: List<SettingsEntry>,
    navController: NavController
) {
    item {
        SettingsSectionHeader(
            title = stringResource(titleRes),
            description = stringResource(descriptionRes),
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

/** 分组标题使用细色条与说明文字建立清晰信息层级。 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    description: String,
    icon: ImageVector,
    accentColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
