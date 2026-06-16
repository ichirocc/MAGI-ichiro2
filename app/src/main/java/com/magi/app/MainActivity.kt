package com.magi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.magi.app.ui.MagiApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by rememberSaveable { mutableStateOf(0) }
            MagiTheme(themeMode) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .consumeWindowInsets(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MagiApp(themeMode = themeMode, onThemeMode = { themeMode = it })
                }
            }
        }
    }
}

@Composable
private fun MagiTheme(mode: Int = 0, content: @Composable () -> Unit) {
    // mode: 0=システム / 1=ライト / 2=ダーク / 3=高コントラスト(ユニバーサルデザイン)
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val highContrast = lightColorScheme(
        primary = Color(0xFF005048), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF003C36), onPrimaryContainer = Color(0xFFFFFFFF),
        secondary = Color(0xFF0B3A5E), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCFE0EE), onSecondaryContainer = Color(0xFF001A2E),
        background = Color(0xFFFFFFFF), onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE8EDF0), onSurfaceVariant = Color(0xFF1A2226),
        error = Color(0xFF8C0009), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD4), onErrorContainer = Color(0xFF2D0001),
        outline = Color(0xFF000000),
    )
    val colors = if (mode == 3) highContrast else if (dark) darkColorScheme(
        primary = Color(0xFF60A5FA), onPrimary = Color(0xFF06203F),          // 実行中/CTA
        primaryContainer = Color(0xFF1E3A66), onPrimaryContainer = Color(0xFFDCE9FE),
        secondary = Color(0xFFA5B4FC), onSecondary = Color(0xFF1E1B4B),
        secondaryContainer = Color(0xFF312E81), onSecondaryContainer = Color(0xFFE6E9FF),
        tertiary = Color(0xFF4ADE80), onTertiary = Color(0xFF052E16),         // 成功/緑
        tertiaryContainer = Color(0xFF14532D), onTertiaryContainer = Color(0xFFDCFCE7),
        background = Color(0xFF111318), onBackground = Color(0xFFECECF0),
        surface = Color(0xFF1B1D22), onSurface = Color(0xFFECECF0),
        surfaceVariant = Color(0xFF272A30), onSurfaceVariant = Color(0xFF9CA3AF),
        error = Color(0xFFF87171), onError = Color(0xFF3F0A0A),
        errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFEE2E2),
        outline = Color(0xFF3A3E47),
    ) else lightColorScheme(
        primary = Color(0xFF3B82F6), onPrimary = Color(0xFFFFFFFF),           // 実行中/CTA(青)
        primaryContainer = Color(0xFFDCE9FE), onPrimaryContainer = Color(0xFF0B2E66),
        secondary = Color(0xFF6366F1), onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE6E9FF), onSecondaryContainer = Color(0xFF1E1B4B),
        tertiary = Color(0xFF22C55E), onTertiary = Color(0xFFFFFFFF),         // 成功(緑)
        tertiaryContainer = Color(0xFFDCFCE7), onTertiaryContainer = Color(0xFF065F36),
        background = Color(0xFFF5F5F7), onBackground = Color(0xFF111318),     // 明るく静かなベース
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111318),
        surfaceVariant = Color(0xFFF0F1F4), onSurfaceVariant = Color(0xFF6B7280),
        error = Color(0xFFEF4444), onError = Color(0xFFFFFFFF),               // 重大違反(赤)
        errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
        outline = Color(0xFFD9DCE3),
    )
    // 見出し大・本文静か・数値最大（添付テイスト）。spはシステム文字サイズ設定に追従。
    val t = Typography(
        displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp),
        headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
        titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
    // 柔らかいカード(20dp)・タイル(24dp)・ピル(round)。
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    MaterialTheme(colorScheme = colors, typography = t, shapes = shapes, content = content)
}
