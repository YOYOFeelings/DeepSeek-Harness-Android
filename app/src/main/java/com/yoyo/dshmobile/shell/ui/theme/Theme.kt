package com.yoyo.dshmobile.shell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 品牌主色：荧光青蓝。按钮、激活状态、高亮文字。 */
val Brand = Color(0xFF00E5FF)
/** 主背景 */
val C_Bg = Color(0xFF0F0F0F)
/** 卡片 / 面板背景 */
val C_Card = Color(0xFF1C1C1E)
/** 局部高亮 */
val C_Highlight = Color.White.copy(alpha = 0.05f)
/** 危险色（未授权 / 错误） */
val C_Red = Color(0xFFFF4444)
/** 非当前圆点 / 非授权指示灯 */
val C_Inactive = Color(0xFF444444)
/** 日志文字（偏青） */
val C_Log = Color(0xFF8FF3FF)

val DataDarkColorScheme = darkColorScheme(
  primary = Brand,
  onPrimary = Color(0xFF001C1F),
  secondary = Brand,
  onSecondary = Color(0xFF001C1F),
  background = C_Bg,
  onBackground = Color(0xFFE8E8E8),
  surface = C_Card,
  onSurface = Color(0xFFE8E8E8),
  surfaceVariant = C_Highlight,
  onSurfaceVariant = Color(0xFFAAAAAA),
  outline = Color(0xFF3A3A3C),
)

/**
 * ModuleData 深色主题：强制深色（主背景 #0F0F0F）、品牌荧光青蓝 #00E5FF、卡片 #1C1C1E。
 * 全程无白色背景。
 */
@Composable
fun ModuleDataTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = DataDarkColorScheme,
    content = content,
  )
}