package com.muc.fluocolorquant.ui.screens.settings

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.muc.fluocolorquant.R
import com.muc.fluocolorquant.ui.components.FluoTopBar
import com.muc.fluocolorquant.data.repository.SettingsRepository
import com.muc.fluocolorquant.ui.components.LocalToastManager
import com.muc.fluocolorquant.ui.components.ToastType
import com.muc.fluocolorquant.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

private const val TAG = "AppSettingsScreen"

/**
 * 应用设置页面
 * 提供语言设置和应用外观相关配置
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val toastManager = LocalToastManager.current
    
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    val currentThemeMode by viewModel.currentThemeMode.collectAsState()
    val languageOptions = viewModel.languageOptions.map { SettingsOptionItem(it.code, it.name) }
    val themeModeOptions = listOf(
        SettingsOptionItem(
            SettingsRepository.THEME_MODE_SYSTEM,
            stringResource(R.string.theme_mode_system)
        ),
        SettingsOptionItem(
            SettingsRepository.THEME_MODE_LIGHT,
            stringResource(R.string.theme_mode_light)
        ),
        SettingsOptionItem(
            SettingsRepository.THEME_MODE_DARK,
            stringResource(R.string.theme_mode_dark)
        )
    )

    Scaffold(
        topBar = {
            FluoTopBar(
                title = stringResource(R.string.app_settings),
                onBack = { navController.navigateUp() }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.language_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Text(
                text = stringResource(R.string.language_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            SettingsOptionSelector(
                label = stringResource(R.string.select_language),
                currentCode = currentLanguage,
                onOptionSelected = { languageCode ->
                    if (languageCode != currentLanguage) {
                        coroutineScope.launch {
                            viewModel.setLanguage(languageCode)
                            Log.d(TAG, "Language changed to: $languageCode")
                            toastManager.showToast(
                                message = context.getString(R.string.language_changed),
                                type = ToastType.SUCCESS
                            )
                        }
                    }
                },
                options = languageOptions,
                leadingIcon = Icons.Default.Language
            )
            
            Divider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            Text(
                text = stringResource(R.string.theme_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = stringResource(R.string.theme_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SettingsOptionSelector(
                label = stringResource(R.string.select_theme_mode),
                currentCode = currentThemeMode,
                onOptionSelected = { themeMode ->
                    if (themeMode != currentThemeMode) {
                        coroutineScope.launch {
                            viewModel.setThemeMode(themeMode)
                            Log.d(TAG, "Theme mode changed to: $themeMode")
                            toastManager.showToast(
                                message = context.getString(R.string.theme_changed),
                                type = ToastType.SUCCESS
                            )
                        }
                    }
                },
                options = themeModeOptions,
                leadingIcon = Icons.Default.Palette
            )
        }
    }
}

@Composable
private fun SettingsOptionSelector(
    label: String,
    currentCode: String,
    onOptionSelected: (String) -> Unit,
    options: List<SettingsOptionItem>,
    leadingIcon: ImageVector
) {
    var expanded by remember { mutableStateOf(false) }
    val currentOptionName = remember(currentCode, options) {
        options.find { it.code == currentCode }?.name ?: options.firstOrNull()?.name.orEmpty()
    }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { expanded = true },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Text(
                            text = currentOptionName,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            onOptionSelected(option.code)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private data class SettingsOptionItem(
    val code: String,
    val name: String
)
