package com.muc.fluocolorquant.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自定义Toast类型
 */
enum class ToastType {
    SUCCESS, INFO, WARNING, ERROR
}

/**
 * 显示自定义Toast
 * @param message Toast消息内容
 * @param duration 显示时长（毫秒）
 * @param type Toast类型（成功、信息、警告、错误）
 */
fun Context.showCustomToast(
    message: String,
    duration: Long = 2000,
    type: ToastType = ToastType.INFO
) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    
    // 实际项目中，可以在这里使用自定义View实现自定义Toast
    // 但由于在Compose中，更优雅的方式是使用Composable函数
}

/**
 * Composable函数版本的自定义Toast
 * 使用示例：
 * ```
 * var showToast by remember { mutableStateOf(false) }
 * var toastMessage by remember { mutableStateOf("") }
 * var toastType by remember { mutableStateOf(ToastType.INFO) }
 * 
 * if (showToast) {
 *     CustomToast(
 *         message = toastMessage,
 *         type = toastType,
 *         onDismiss = { showToast = false }
 *     )
 * }
 * 
 * // 触发显示Toast
 * Button(onClick = {
 *     toastMessage = "操作成功！"
 *     toastType = ToastType.SUCCESS
 *     showToast = true
 * }) {
 *     Text("显示Toast")
 * }
 * ```
 */
@Composable
fun CustomToast(
    message: String,
    type: ToastType = ToastType.INFO,
    duration: Long = 2000,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(targetValue = if (visible) 1f else 0f)
    
    // 自动消失
    LaunchedEffect(key1 = message) {
        delay(duration - 300) // 提前结束以留出淡出动画时间
        visible = false
        delay(300) // 等待淡出动画完成
        onDismiss()
    }
    
    // 根据类型选择图标和颜色
    val (icon, backgroundColor) = when (type) {
        ToastType.SUCCESS -> Icons.Default.Check to MaterialTheme.colorScheme.primary
        ToastType.INFO -> Icons.Default.Info to MaterialTheme.colorScheme.secondary
        ToastType.WARNING -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        ToastType.ERROR -> Icons.Default.Warning to MaterialTheme.colorScheme.error
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .padding(bottom = 90.dp, start = 16.dp, end = 16.dp)
                .alpha(alpha),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 6.dp,
            color = backgroundColor.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White
                )
                
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 状态管理器，用于在全局范围内显示Toast
 */
class ToastManager {
    var showToast by mutableStateOf(false)
    var toastMessage by mutableStateOf("")
    var toastType by mutableStateOf(ToastType.INFO)
    var toastDuration by mutableStateOf(2000L)
    
    fun showToast(message: String, type: ToastType = ToastType.INFO, duration: Long = 2000) {
        toastMessage = message
        toastType = type
        toastDuration = duration
        showToast = true
    }
    
    fun hideToast() {
        showToast = false
    }
}

/**
 * 全局Toast状态管理器
 */
val LocalToastManager = compositionLocalOf { ToastManager() }

/**
 * Toast容器，用于在Scaffold外部显示Toast
 */
@Composable
fun ToastHost() {
    val toastManager = LocalToastManager.current
    
    if (toastManager.showToast) {
        CustomToast(
            message = toastManager.toastMessage,
            type = toastManager.toastType,
            duration = toastManager.toastDuration,
            onDismiss = { toastManager.hideToast() }
        )
    }
} 