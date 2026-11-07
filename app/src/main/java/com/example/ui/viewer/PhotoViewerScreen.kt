package com.example.ui.viewer

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Unpublished
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.crypto.CryptoEngine
import com.example.data.db.VaultItem
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import javax.crypto.SecretKey

@Composable
fun PhotoViewerScreen(
    vaultItem: VaultItem,
    blobFile: File,
    baseIv: ByteArray,
    masterKey: SecretKey,
    onBack: () -> Unit,
    onToggleFavorite: (VaultItem) -> Unit,
    onUnhide: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit
) {
    BackHandler { onBack() }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    var isFavorite by remember { mutableStateOf(vaultItem.isFavorite) }
    var isSlideshowActive by remember { mutableStateOf(false) }

    // Decrypt photo in memory for instant display
    val decryptedBitmap = remember(vaultItem.id) {
        try {
            val inputStream = FileInputStream(blobFile)
            val outputStream = ByteArrayOutputStream()
            CryptoEngine.decryptStreamCtr(inputStream, outputStream, masterKey, baseIv)
            val bytes = outputStream.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (decryptedBitmap != null) {
                Image(
                    bitmap = decryptedBitmap.asImageBitmap(),
                    contentDescription = vaultItem.fileName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            rotationZ = rotationAngle
                        )
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 5f)
                            }
                        }
                )
            } else {
                Text(
                    text = "Failed to decrypt photo",
                    color = Color.Red,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("photo_back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }

                Text(
                    text = vaultItem.fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )

                IconButton(
                    onClick = { rotationAngle = (rotationAngle + 90f) % 360f },
                    modifier = Modifier.testTag("rotate_photo")
                ) {
                    Icon(Icons.Default.RotateRight, "Rotate 90°", tint = Color.White)
                }
            }

            // Bottom Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Heart Favorite
                IconButton(
                    onClick = {
                        isFavorite = !isFavorite
                        onToggleFavorite(vaultItem.copy(isFavorite = isFavorite))
                    },
                    modifier = Modifier.testTag("heart_photo")
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) Color.Red else Color.White
                    )
                }

                // Unhide
                IconButton(
                    onClick = { onUnhide(vaultItem) },
                    modifier = Modifier.testTag("unhide_photo")
                ) {
                    Icon(Icons.Default.Unpublished, "Unhide", tint = Color.White)
                }

                // Delete Permanently
                IconButton(
                    onClick = { onDelete(vaultItem) },
                    modifier = Modifier.testTag("delete_photo")
                ) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                }
            }
        }
    }
}
