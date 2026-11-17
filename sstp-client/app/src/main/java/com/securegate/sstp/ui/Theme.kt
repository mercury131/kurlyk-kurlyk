package com.securegate.sstp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Dark = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    secondary = Color(0xFF22D3EE),
    background = Color(0xFF0E1621),
    surface = Color(0xFF0E1621),
    surfaceVariant = Color(0xFF182533),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3F5FE0),
    secondary = Color(0xFF0891B2),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
