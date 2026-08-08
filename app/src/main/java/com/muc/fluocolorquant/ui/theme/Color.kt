package com.muc.fluocolorquant.ui.theme

import androidx.compose.ui.graphics.Color

// 新的主题色 (基于 #495D92)
val Primary = Color(0xFF495D92)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFDAE2FF)
val OnPrimaryContainer = Color(0xFF001946)

// 默认的辅助色和三级色 (您可以根据需要修改)
val Secondary = Color(0xFF595E71)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFDEE1F9)
val OnSecondaryContainer = Color(0xFF161B2C)

val Tertiary = Color(0xFF745570)
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFFFED7F8)
val OnTertiaryContainer = Color(0xFF2B132A)

// 错误色
val Error = Color(0xFFBA1A1A)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)

// 背景色
val Background = Color(0xFFFEFBFF)
val OnBackground = Color(0xFF1B1B1F)
val Surface = Color(0xFFFEFBFF)
val OnSurface = Color(0xFF1B1B1F)

val SurfaceVariant = Color(0xFFE2E2EC)
val OnSurfaceVariant = Color(0xFF45464F)
val Outline = Color(0xFF757680)
val OutlineVariant = Color(0xFFC5C6D0)

// ----------------------------------------------------------------------------
// 浅色表面层级 (Material 3 surfaceContainer 家族)
//
// Material 3 1.2 起，Card、NavigationBar、ModalBottomSheet、Menu 等组件的默认容器色
// 取自 surfaceContainer* 而不再是 surfaceVariant。此前主题没有显式提供这几个角色，
// 组件会回落到 M3 基线调色板的紫调灰 (例如 0xFFF3EDF7)，与 #495D92 科研蓝底色并列时
// 会出现"卡片偏紫、页面偏蓝"的分裂观感。这里按同一冷白蓝灰色相重新定义五级层次，
// 使卡片、面板和导航栏的抬升关系来自明度差，而不是色相差。
// ----------------------------------------------------------------------------
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF8F7FD)
val SurfaceContainer = Color(0xFFF2F2F9)
val SurfaceContainerHigh = Color(0xFFECEDF5)
val SurfaceContainerHighest = Color(0xFFE6E7F1)
val SurfaceBright = Color(0xFFFEFBFF)
val SurfaceDim = Color(0xFFDEDCE6)

val InverseSurface = Color(0xFF303034)
val InverseOnSurface = Color(0xFFF3F0F4)
val InversePrimary = Color(0xFFAFC6FF)
val Scrim = Color(0xFF000000)

// ==========================================================
// 暗色主题 (Dark Theme)
// ==========================================================

// 新的暗色主题主色
val DarkPrimary = Color(0xFFAFC6FF)
val DarkOnPrimary = Color(0xFF192F61)
val DarkPrimaryContainer = Color(0xFF314679)
val DarkOnPrimaryContainer = Color(0xFFD8DDEF)

// 暗色主题的辅助色和三级色
val DarkSecondary = Color(0xFFC2C5DD)
val DarkOnSecondary = Color(0xFF2B3042)
val DarkSecondaryContainer = Color(0xFF424659)
val DarkOnSecondaryContainer = Color(0xFFDEE1F9)

val DarkTertiary = Color(0xFFE2BBDD)
val DarkOnTertiary = Color(0xFF422840)
val DarkTertiaryContainer = Color(0xFF5B3E58)
val DarkOnTertiaryContainer = Color(0xFFFED7F8)

// 暗色主题错误色
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// 暗色主题背景色
val DarkBackground = Color(0xFF1B1B1F)
val DarkOnBackground = Color(0xFFE4E2E6)
val DarkSurface = Color(0xFF1B1B1F)
val DarkOnSurface = Color(0xFFE4E2E6)

val DarkSurfaceVariant = Color(0xFF45464F)
val DarkOnSurfaceVariant = Color(0xFFC5C6D0)
val DarkOutline = Color(0xFF8F909A)
val DarkOutlineVariant = Color(0xFF45464F)

// 深色表面层级：与浅色一致，抬升同样只靠明度而不靠色相。
val DarkSurfaceContainerLowest = Color(0xFF0E0E12)
val DarkSurfaceContainerLow = Color(0xFF1B1B1F)
val DarkSurfaceContainer = Color(0xFF1F2024)
val DarkSurfaceContainerHigh = Color(0xFF2A2A2F)
val DarkSurfaceContainerHighest = Color(0xFF35353B)
val DarkSurfaceBright = Color(0xFF37373D)
val DarkSurfaceDim = Color(0xFF131316)

val DarkInverseSurface = Color(0xFFE4E2E6)
val DarkInverseOnSurface = Color(0xFF303034)
val DarkInversePrimary = Color(0xFF495D92)
val DarkScrim = Color(0xFF000000)


// ============================================================================
// 语义状态色
//
// Material 3 的 ColorScheme 只内置 error 一种状态语义，成功/警告/提示需要主题自行提供。
// 旧的 Success/Warning/Info 是三个不分明暗的高饱和亮色：放在浅色卡片上对比度不足，
// 放进深色模式更会直接刺眼，且没有配套的容器色和前景色，页面只能自行 copy(alpha=…)，
// 结果是同一种"成功"在不同页面呈现出不同颜色。
//
// 这里按 Material 色调阶（浅色用 tone 40 主色 / tone 90 容器，深色用 tone 80 主色 /
// tone 30 容器）补齐四元组，保证：
//   1. 浅色与深色模式下文字对比度都达标；
//   2. 状态色只表达语义，不作为装饰色使用；
//   3. 色相与科研蓝主色共存而不争夺视觉重心（成功偏冷绿、警告偏琥珀、提示偏蓝）。
// 状态本身仍必须同时由图标与文字表达，不能只靠颜色区分（见 AGENTS.md 10）。
// ============================================================================
val Success = Color(0xFF2E6B45)
val OnSuccess = Color(0xFFFFFFFF)
val SuccessContainer = Color(0xFFB4F1C6)
val OnSuccessContainer = Color(0xFF00210F)

val Warning = Color(0xFF7C5800)
val OnWarning = Color(0xFFFFFFFF)
val WarningContainer = Color(0xFFFFDEA6)
val OnWarningContainer = Color(0xFF271900)

val Info = Color(0xFF1B5E9E)
val OnInfo = Color(0xFFFFFFFF)
val InfoContainer = Color(0xFFD3E4FF)
val OnInfoContainer = Color(0xFF001C38)

val DarkSuccess = Color(0xFF98D8AB)
val DarkOnSuccess = Color(0xFF00391E)
val DarkSuccessContainer = Color(0xFF14522F)
val DarkOnSuccessContainer = Color(0xFFB4F1C6)

val DarkWarning = Color(0xFFF0C05F)
val DarkOnWarning = Color(0xFF412D00)
val DarkWarningContainer = Color(0xFF5D4200)
val DarkOnWarningContainer = Color(0xFFFFDEA6)

val DarkInfo = Color(0xFFA3C9FF)
val DarkOnInfo = Color(0xFF00325A)
val DarkInfoContainer = Color(0xFF15497F)
val DarkOnInfoContainer = Color(0xFFD3E4FF)

// 状态颜色 (保留)
val Disabled = Color(0xFFE0E0E0)
val OnDisabled = Color(0xFF757575)
val Selected = Color(0xFFE3F2FD)
val OnSelected = Color(0xFF1976D2)