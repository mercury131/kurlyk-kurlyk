package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.ui.theme.LocalAuroraChatColors

@Composable
fun ChatWallpaper(
    wallpaperId: Int,
    modifier: Modifier = Modifier,
) {
    val chatColors = LocalAuroraChatColors.current
    val isDark = chatColors.isDark
    val base = chatColors.wallpaperBase

    val bgBrush = when (wallpaperId) {
        1 -> if (isDark) {
            Brush.verticalGradient(listOf(Color(0xFF0C1524), Color(0xFF15263B), Color(0xFF0E1726)))
        } else {
            Brush.verticalGradient(listOf(Color(0xFFD5E4F0), Color(0xFFE6EFF7), Color(0xFFD2E2EE)))
        }
        2 -> if (isDark) {
            Brush.linearGradient(listOf(Color(0xFF121826), Color(0xFF1C2438)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFE2E8F0), Color(0xFFF1F5F9)))
        }
        3 -> if (isDark) {
            Brush.linearGradient(listOf(Color(0xFF0B191E), Color(0xFF122830)))
        } else {
            Brush.linearGradient(listOf(Color(0xFFD1ECEB), Color(0xFFE8F6F5)))
        }
        else -> Brush.verticalGradient(listOf(base, base))
    }

    Box(modifier = modifier.fillMaxSize().background(bgBrush)) {
        if (wallpaperId != 2) {
            val patternColor = if (isDark) Color.White.copy(alpha = 0.035f) else Color(0xFF334155).copy(alpha = 0.06f)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = 110f
                val cols = (size.width / step).toInt() + 2
                val rows = (size.height / step).toInt() + 2
                for (r in 0..rows) {
                    for (c in 0..cols) {
                        val cx = c * step + if (r % 2 == 0) 0f else step / 2f
                        val cy = r * step
                        when (wallpaperId) {
                            0 -> {
                                // Subtle geometric circles + diamonds
                                drawCircle(
                                    color = patternColor,
                                    radius = 12f,
                                    center = Offset(cx, cy),
                                    style = Stroke(width = 2f),
                                )
                                drawCircle(
                                    color = patternColor,
                                    radius = 3f,
                                    center = Offset(cx + step * 0.45f, cy + step * 0.35f),
                                )
                            }
                            1 -> {
                                // Aurora flowing arcs
                                val path = Path().apply {
                                    moveTo(cx - 35f, cy)
                                    quadraticTo(cx, cy - 26f, cx + 35f, cy)
                                }
                                drawPath(path, color = patternColor, style = Stroke(width = 2.5f))
                            }
                            3 -> {
                                // Constellation dots & lines
                                drawCircle(color = patternColor, radius = 4f, center = Offset(cx, cy))
                                drawLine(
                                    color = patternColor,
                                    start = Offset(cx, cy),
                                    end = Offset(cx + step * 0.5f, cy + step * 0.5f),
                                    strokeWidth = 1.5f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
