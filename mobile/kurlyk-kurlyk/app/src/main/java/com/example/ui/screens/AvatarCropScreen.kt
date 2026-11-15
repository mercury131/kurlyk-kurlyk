package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.R
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropScreen(
    imageUri: Uri,
    onCancel: () -> Unit,
    onCropped: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val bitmap = remember(imageUri) {
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                title = { Text(stringResource(R.string.crop_avatar_title)) },
                actions = {
                    IconButton(
                        enabled = bitmap != null,
                        onClick = {
                            bitmap?.let { src ->
                                val jpeg = cropTo512SquareJpeg(src, containerSize, scale, offset)
                                onCropped(jpeg)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Avatar preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }

            // Circular crop overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = (minOf(size.width, size.height) * 0.42f)
                val circlePath = Path().apply {
                    addOval(
                        androidx.compose.ui.geometry.Rect(
                            center = center,
                            radius = radius,
                        )
                    )
                }
                clipPath(circlePath, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.6f))
                }
                drawCircle(
                    color = Color.White,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

private fun cropTo512SquareJpeg(
    src: Bitmap,
    containerSize: IntSize,
    scale: Float,
    offset: Offset,
): ByteArray {
    val cw = containerSize.width.coerceAtLeast(1).toFloat()
    val ch = containerSize.height.coerceAtLeast(1).toFloat()
    val fitScale = minOf(cw / src.width, ch / src.height)
    val totalScale = fitScale * scale

    val cropRadiusScreen = minOf(cw, ch) * 0.42f
    val cropSideSrc = (cropRadiusScreen * 2f / totalScale).coerceAtLeast(1f)

    val centerSrcX = src.width / 2f - offset.x / totalScale
    val centerSrcY = src.height / 2f - offset.y / totalScale

    val left = (centerSrcX - cropSideSrc / 2f).toInt().coerceIn(0, (src.width - 1).coerceAtLeast(0))
    val top = (centerSrcY - cropSideSrc / 2f).toInt().coerceIn(0, (src.height - 1).coerceAtLeast(0))
    val size = cropSideSrc.toInt().coerceIn(1, minOf(src.width - left, src.height - top).coerceAtLeast(1))

    val square = Bitmap.createBitmap(src, left, top, size, size, Matrix(), true)
    val out512 = Bitmap.createScaledBitmap(square, 512, 512, true)

    var quality = 85
    var bytes: ByteArray
    do {
        val baos = ByteArrayOutputStream()
        out512.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bytes = baos.toByteArray()
        quality -= 10
    } while (bytes.size >= 500 * 1024 && quality >= 35)
    return bytes
}
