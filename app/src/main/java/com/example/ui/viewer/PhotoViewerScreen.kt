package com.example.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
 * Architectural Unified Media Viewer (Photos & Videos):
 * 1. Wraps all `displayedItems` (both photos and videos) inside a `HorizontalPager` so the user
 *    can swipe left/right seamlessly across their entire gallery or folder.
 * 2. Video pages display their decrypted preview frame, duration badge, and a glowing central
 *    Play button (`viewer_play_video_button`) that launches `VideoPlayerScreen`.
 * 3. Fixes multi-touch Pinch-to-Zoom (1x to 5x) and bounded 2D Pan (`offsetX`, `offsetY`) from 1x:
 *    Uses an always-attached `awaitEachGesture` loop that inspects active pointer count:
 *    - When 2+ fingers are down (`pointerCount >= 2`) OR the image is already zoomed (`scale > 1.05f`),
 *      zoom and bounded pan are applied and pointer changes are consumed.
 *    - When only 1 finger is down at `1f` scale, events are NOT consumed so `HorizontalPager`
 *      swipes left/right without friction.
 * 4. Smooth animated 90-degree rotation (`animateFloatAsState`) + double-tap 1x / 2.8x zoom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerScreen(
    photoItems: List<VaultItem>,
    initialItemId: Long,
    vaultBlobsDir: File,
    masterKey: SecretKey,
    onBack: () -> Unit,
    onPlayVideo: (VaultItem) -> Unit = {},
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

    // Track per-item zoom scale so HorizontalPager only scrolls between pages when current page scale <= 1.05f
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
                userScrollEnabled = activePageScale <= 1.05f,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val pageItem = photoItems.getOrNull(pageIndex) ?: return@HorizontalPager
                val isVideo = pageItem.mimeType.startsWith("video")
                val blobFile = remember(pageItem.encryptedRelativePath) {
                    File(vaultBlobsDir, pageItem.encryptedRelativePath)
                }
                val baseIv = remember(pageItem.baseIvHex) {
                    fromHex(pageItem.baseIvHex)
                }

                var fullBitmap by remember(pageItem.id) { mutableStateOf<Bitmap?>(null) }
                var videoThumb by remember(pageItem.id) { mutableStateOf<ImageBitmap?>(null) }
                var isLoading by remember(pageItem.id) { mutableStateOf(true) }

                LaunchedEffect(pageItem.id) {
                    isLoading = true
                    if (isVideo) {
                        videoThumb = ThumbnailCacheManager.loadThumbnail(pageItem, blobFile, masterKey)
                    } else {
                        fullBitmap = ThumbnailCacheManager.decodeFullPhotoAsync(
                            blobFile = blobFile,
                            masterKey = masterKey,
                            baseIv = baseIv
                        )
                    }
                    isLoading = false
                }

                var scale by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(1f) }
                var offsetX by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(0f) }
                var offsetY by remember(pageItem.id, pagerState.currentPage) { mutableFloatStateOf(0f) }
                var containerSize by remember { mutableStateOf(IntSize.Zero) }

                val targetRotation = rotationMap[pageItem.id] ?: 0f
                val animatedRotation by animateFloatAsState(
                    targetValue = targetRotation,
                    label = "viewer_rotation"
                )

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
                                        val targetScale = 2.8f
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
                        .pointerInput(pageItem.id) {
                            // Always-attached multi-touch pinch-to-zoom & pan handler:
                            // Processes zoom immediately on 2-finger pinch from 1x scale, and pans when zoomed in,
                            // while allowing 1-finger horizontal drags at 1x scale to pass to HorizontalPager.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val pressedCount = event.changes.count { it.pressed }
                                    if (pressedCount >= 2 || scale > 1.05f) {
                                        val zoomChange = event.calculateZoom()
                                        val panChange = event.calculatePan()

                                        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                        val maxX = (containerSize.width * (newScale - 1f)) / 2f
                                        val maxY = (containerSize.height * (newScale - 1f)) / 2f

                                        if (newScale <= 1.02f && pressedCount < 2) {
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        } else {
                                            scale = newScale
                                            offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                            offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                                        }

                                        if (pageIndex == pagerState.currentPage) {
                                            activePageScale = scale
                                        }

                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) {
                                                change.consume()
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                        isVideo -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (videoThumb != null) {
                                    Image(
                                        bitmap = videoThumb!!,
                                        contentDescription = pageItem.fileName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = scale,
                                                scaleY = scale,
                                                translationX = offsetX,
                                                translationY = offsetY,
                                                rotationZ = animatedRotation
                                            )
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        modifier = Modifier.size(96.dp)
                                    )
                                }

                                // Central Play Video Button Overlay
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        MaterialTheme.colorScheme.primary,
                                                        MaterialTheme.colorScheme.secondary
                                                    )
                                                )
                                            )
                                            .border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                            .clickable { onPlayVideo(pageItem) }
                                            .testTag("viewer_play_video_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Encrypted Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color.Black.copy(alpha = 0.72f))
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (pageItem.durationMs > 0) {
                                                "Tap to Play Video (${formatDuration(pageItem.durationMs)})"
                                            } else {
                                                "Tap to Play Encrypted Video"
                                            },
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                        fullBitmap != null -> {
                            Image(
                                bitmap = fullBitmap!!.asImageBitmap(),
                                contentDescription = pageItem.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offsetX,
                                        translationY = offsetY,
                                        rotationZ = animatedRotation
                                    )
                            )
                        }
                        else -> {
                            Text(
                                text = "Unable to decode encrypted media",
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
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
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
                                (if (currentItem.mimeType.startsWith("video")) " • Video" else " • Photo") +
                                (if (isSlideshowActive) " • Slideshow Active" else ""),
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
                            rotationMap[currentItem.id] = currentRotation + 90f
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
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
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

    // Confirmation Dialog before permanent deletion
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete Media Permanently?", fontWeight = FontWeight.Bold) },
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

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format("%02d:%02d", m, s)
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

