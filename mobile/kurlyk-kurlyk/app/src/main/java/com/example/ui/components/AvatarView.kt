package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.AuroraAccent
import com.example.ui.theme.AuroraCyan
import com.example.ui.theme.AvatarGradientPalettes
import com.example.ui.theme.OnlineGreen
import java.io.File
import kotlin.math.abs

@Composable
fun AvatarView(
    id: String,
    name: String,
    avatarLocalPath: String?,
    size: Dp = 56.dp,
    isOnline: Boolean = false,
    isGroup: Boolean = false,
    isSavedMessages: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val paletteIndex = abs(id.hashCode()) % AvatarGradientPalettes.size
    val (c1, c2) = AvatarGradientPalettes[paletteIndex]
    val file = avatarLocalPath?.let { File(it) }?.takeIf { it.exists() }

    Box(
        modifier = modifier
            .size(size)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    if (isSavedMessages) {
                        Brush.linearGradient(listOf(AuroraAccent, AuroraCyan))
                    } else {
                        Brush.linearGradient(listOf(c1, c2))
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isSavedMessages -> {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = name,
                        tint = Color.White,
                        modifier = Modifier.size(size * 0.48f),
                    )
                }

                file != null -> {
                    AsyncImage(
                        model = file,
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                isGroup -> {
                    val initials = extractInitials(name)
                    if (initials.isNotEmpty()) {
                        Text(
                            text = initials,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = (size.value * 0.36f).sp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = name,
                            tint = Color.White,
                            modifier = Modifier.size(size * 0.5f),
                        )
                    }
                }

                else -> {
                    Text(
                        text = extractInitials(name),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.36f).sp,
                    )
                }
            }
        }

        if (isOnline && !isGroup && !isSavedMessages) {
            val dotSize = (size * 0.25f).coerceAtLeast(10.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-1).dp, y = (-1).dp)
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(OnlineGreen)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

fun extractInitials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(2).uppercase()
    return "${parts[0].first()}${parts[1].first()}".uppercase()
}
