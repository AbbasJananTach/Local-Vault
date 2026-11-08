package com.example.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Unpublished
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ThumbnailCacheManager
import com.example.data.db.VaultItem
import kotlinx.coroutines.delay
import java.io.File
import javax.crypto.SecretKey

/**
 * Architectural Photo Viewer Upgrade:
 * 1. Wraps `displayedItems` inside a `HorizontalPager` for smooth left/right photo swiping.
 * 2. Decrypts photos asynchronously on `Dispatchers.IO` via `ThumbnailCacheManager.decodeFullPhotoAsync`
 *    with a loading spinner, preventing Main UI thread freezes and OOM crashes.
 * 3. Implements full pinch-to-zoom WITH bounded 2D pan (`offsetX`, `offsetY`) + double-tap 1x / 2.5x zoom.
 * 4. Includes a top-bar 3-second auto-advancing Slideshow Play/Pause toggle and a permanent delete
 *    confirmation dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photoItems: List<VaultItem>,
    initialItemId: Long,
    vaultBlobsDir: File,
    masterKey: SecretKey,
    onBack: () -> Unit,
    onToggleFavorite: (VaultItem) -> Unit,
    onUnhide: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit
) {
    BackHandler { onBack() }

    if (photoItems.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialIndex = remember(photoItems, initialItemId) {
        photoItems.indexOfFirst { it.id == initialItemId }.coerceAtLeast(0)
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photoItems.size - 1).coerceAtLeast(0)),
        pageCount = { photoItems.size }
    )

    val currentItem = photoItems.getOrNull(pagerState.currentPage) ?: photoItems.first()

    var isControlsVisible by remember { mutableStateOf(true) }
    var isSlideshowActive by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Track per-item zoom scale so HorizontalPager only scrolls between pages when current page scale == 1f
    var activePageScale by remember { mutableFloatStateOf(1f) }
    val rotationMap = remember { mutableStateMapOf<Long, Float>() }
    val favoriteMap = remember(photoItems) {
        mutableStateMapOf<Long, Boolean>().apply {
            photoItems.forEach { put(it.id, it.isFavorite) }
        }
    }

    // Reset activePageScale when page changes
    LaunchedEffect(pagerState.currentPage) {
        activePageScale = 1f
    }

    // Slideshow auto-advance loop (every 3 seconds)
    LaunchedEffect(isSlideshowActive, photoItems.size) {
        while (isSlideshowActive && photoItems.size > 1) {
            delay(3000L)
            if (isSlideshowActive && photoItems.isNotEmpty()) {
                val nextPage = (pagerState.currentPage + 1) % photoItems.size
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    val currentRotation = rotationMap[currentItem.id] ?: 0f
    val currentFavorite = favoriteMap[currentItem.id] ?: currentItem.isFavorite

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = activePageScale <= 1.02f,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val pageItem = photoItems.getOrNull(pageIndex) ?: return@HorizontalPager
                val blobFile = remember(pageItem.encryptedRelativePath) {
                    File(vaultBlobsDir, pageItem.encryptedRelativePath)
                }
                val baseIv = remember(pageItem.baseIvHex) {
                    fromHex(pageItem.baseIvHex)
                }

                var bitmap by remember(pageItem.id) { mutableStateOf<Bitmap?>(null) }
                var isLoading by remember(pageItem.id) { mutableStateOf(true) }

                LaunchedEffect(pageItem.id) {
                    isLoading = true
                    bitmap = ThumbnailCacheManager.decodeFullPhotoAsync(
                        blobFile = blobFile,
                        masterKey = masterKey,
                        baseIv = baseIv
                    )
                    isLoading = false
                }

                var scale by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(1f) }
                var offsetX by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(0f) }
                var offsetY by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(0f) }
                var containerSize by remember { mutableStateOf(IntSize.Zero) }

                val pageRotation = rotationMap[pageItem.id] ?: 0f

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it }
                        .pointerInput(pageItem.id) {
                            detectTapGestures(
                                onTap = {
                                    isControlsVisible = !isControlsVisible
                                },
                                onDoubleTap = { tapOffset ->
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    } else {
                                        val targetScale = 2.5f
                                        val centerX = containerSize.width / 2f
                                        val centerY = containerSize.height / 2f
                                        val maxX = (containerSize.width * (targetScale - 1f)) / 2f
                                        val maxY = (containerSize.height * (targetScale - 1f)) / 2f
                                        offsetX = ((centerX - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxX, maxX)
                                        offsetY = ((centerY - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxY, maxY)
                                        scale = targetScale
                                    }
                                    if (pageIndex == pagerState.currentPage) {
                                        activePageScale = scale
                                    }
                                }
                            )
                        }
                        .then(
                            if (scale > 1.01f) {
                                Modifier.pointerInput(pageItem.id, scale) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                                        val maxX = (containerSize.width * (newScale - 1f)) / 2f
                                        val maxY = (containerSize.height * (newScale - 1f)) / 2f
                                        if (newScale <= 1.01f) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            scale = newScale
                                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                        }
                                        if (pageIndex == pagerState.currentPage) {
                                            activePageScale = scale
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                        bitmap != null -> {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = pageItem.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                        rotationZ = pageRotation
                                    )
                            )
                        }
                        else -> {
                            Text(
                                text = "Unable to decode encrypted photo",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Top Overlay Bar
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.68f))
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("photo_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = currentItem.fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} of ${photoItems.size}" +
                                if (isSlideshowActive) " • Slideshow Active" else "",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp
                        )
                    }

                    // Slideshow Play/Pause Button
                    if (photoItems.size > 1) {
                        IconButton(
                            onClick = { isSlideshowActive = !isSlideshowActive },
                            modifier = Modifier.testTag("slideshow_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isSlideshowActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isSlideshowActive) "Pause Slideshow" else "Play Slideshow (3s)",
                                tint = if (isSlideshowActive) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }

                    // Rotate 90° Button
                    IconButton(
                        onClick = {
                            rotationMap[currentItem.id] = (currentRotation + 90f) % 360f
                        },
                        modifier = Modifier.testTag("rotate_photo")
                    ) {
                        Icon(Icons.Default.RotateRight, "Rotate 90°", tint = Color.White)
                    }
                }
            }

            // Bottom Actions Bar
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Heart Favorite
                    IconButton(
                        onClick = {
                            val nextFav = !currentFavorite
                            favoriteMap[currentItem.id] = nextFav
                            onToggleFavorite(currentItem.copy(isFavorite = nextFav))
                        },
                        modifier = Modifier.testTag("heart_photo")
                    ) {
                        Icon(
                            imageVector = if (currentFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (currentFavorite) Color(0xFFFF4757) else Color.White
                        )
                    }

                    // Unhide to Public Gallery
                    IconButton(
                        onClick = { onUnhide(currentItem) },
                        modifier = Modifier.testTag("unhide_photo")
                    ) {
                        Icon(Icons.Default.Unpublished, "Unhide", tint = MaterialTheme.colorScheme.secondary)
                    }

                    // Delete Permanently (with Confirmation Dialog)
                    IconButton(
                        onClick = { showDeleteConfirmDialog = true },
                        modifier = Modifier.testTag("delete_photo")
                    ) {
                        Icon(Icons.Default.Delete, "Delete Permanently", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // Confirmation Dialog before permanent photo deletion
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete Photo Permanently?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to permanently destroy '${currentItem.fileName}' from your encrypted vault? This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete(currentItem)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun fromHex(hex: String): ByteArray {
    val len = hex.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
