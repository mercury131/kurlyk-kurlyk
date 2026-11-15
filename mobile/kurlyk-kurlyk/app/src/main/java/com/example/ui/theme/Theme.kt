package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.data.prefs.ThemeMode

data class AuroraChatColors(
    val incomingBubble: Color,
    val outgoingBubble: Color,
    val outgoingText: Color,
    val incomingText: Color,
    val bubbleMetaText: Color,
    val divider: Color,
    val wallpaperBase: Color,
    val isDark: Boolean,
)

val LocalAuroraChatColors = staticCompositionLocalOf {
    AuroraChatColors(
        incomingBubble = DarkIncomingBubble,
        outgoingBubble = DarkOutgoingBubble,
        outgoingText = DarkTextPrimary,
        incomingText = DarkTextPrimary,
        bubbleMetaText = DarkTextSecondary,
        divider = DarkDivider,
        wallpaperBase = DarkBackground,
        isDark = true,
    )
}

private val AuroraDarkColorScheme = darkColorScheme(
    primary = AuroraAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B3F70),
    onPrimaryContainer = Color.White,
    secondary = AuroraCyan,
    onSecondary = Color(0xFF06202A),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outlineVariant = DarkDivider,
    error = ErrorRed,
)

private val AuroraLightColorScheme = lightColorScheme(
    primary = AuroraAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF142157),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outlineVariant = LightDivider,
    error = ErrorRed,
)

@Composable
fun AuroraMessengerTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) AuroraDarkColorScheme else AuroraLightColorScheme
    val chatColors = if (isDark) {
        AuroraChatColors(
            incomingBubble = DarkIncomingBubble,
            outgoingBubble = DarkOutgoingBubble,
            outgoingText = DarkTextPrimary,
            incomingText = DarkTextPrimary,
            bubbleMetaText = Color(0xFF9FB2C5),
            divider = DarkDivider,
            wallpaperBase = DarkBackground,
            isDark = true,
        )
    } else {
        AuroraChatColors(
            incomingBubble = LightIncomingBubble,
            outgoingBubble = LightOutgoingBubble,
            outgoingText = Color(0xFF111827),
            incomingText = LightTextPrimary,
            bubbleMetaText = Color(0xFF6B7280),
            divider = LightDivider,
            wallpaperBase = Color(0xFFD8E4EE),
            isDark = false,
        )
    }

    CompositionLocalProvider(LocalAuroraChatColors provides chatColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
