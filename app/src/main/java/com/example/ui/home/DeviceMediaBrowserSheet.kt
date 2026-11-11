package com.example.ui.home

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DeviceMediaItem
import com.example.data.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Dedicated in-memory LRU cache and multi-stage thumbnail extractor for unencrypted device
 * photos and videos inside `DeviceMediaBrowserSheet`.
 * Solves missing video thumbnails in Coil when `coil-video` is not bundled by using:
 * 1. `ContentResolver.loadThumbnail` (Android Q+)
 * 2. `ThumbnailUtils.createVideoThumbnail`
 * 3. `MediaMetadataRetriever.getFrameAtTime` fallback
 */
private object DeviceBrowserThumbnailLoader {
    private val cache = object : LruCache<String, ImageBitmap>(180) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    fun getCached(path: String): ImageBitmap? = synchronized(cache) {
        cache.get(path)
    }

    suspend fun loadThumbnail(context: Context, item: DeviceMediaItem): ImageBitmap? = withContext(Dispatchers.IO) {
        getCached(item.path)?.let { return@withContext it }

        val bitmap: Bitmap? = try {
            decodeDeviceThumbnail(context, item)
        } catch (_: Exception) {
            null
        }

        if (bitmap != null) {
            val img = bitmap.asImageBitmap()
            synchronized(cache) {
                cache.put(item.path, img)
            }
            return@withContext img
        }
        return@withContext null
    }

    private fun decodeDeviceThumbnail(context: Context, item: DeviceMediaItem): Bitmap? {
        // Stage 1: ContentResolver.loadThumbnail on Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.id > 0L) {
            try {
                val baseUri = if (item.isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val contentUri = ContentUris.withAppendedId(baseUri, item.id)
                val thumb = context.contentResolver.loadThumbnail(contentUri, Size(320, 320), null)
                if (thumb != null) return thumb
            } catch (_: Exception) {
            }
        }

        val file = File(item.path)
        if (!file.exists()) return null

        if (item.isVideo) {
            // Stage 2: ThumbnailUtils on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val thumb = ThumbnailUtils.createVideoThumbnail(file, Size(320, 320), null)
                    if (thumb != null) return thumb
                } catch (_: Exception) {
                }
            }
            // Stage 3: MediaMetadataRetriever frame extraction
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val rawFrame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                if (rawFrame != null) {
                    val maxDim = 320f
                    val ratio = minOf(maxDim / rawFrame.width, maxDim / rawFrame.height, 1f)
                    val w = (rawFrame.width * ratio).toInt().coerceAtLeast(1)
                    val h = (rawFrame.height * ratio).toInt().coerceAtLeast(1)
                    return Bitmap.createScaledBitmap(rawFrame, w, h, true)
                }
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
            return null
        } else {
            // Sampled BitmapFactory decode for device photos
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= 320 && bounds.outHeight / (sampleSize * 2) >= 320) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceMediaBrowserSheet(
    vaultStorage: VaultStorage,
    targetFolderName: String?,
    onDismiss: () -> Unit,
    onImportFiles: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isLoading by remember { mutableStateOf(true) }
    var deviceFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFolderFilter by remember { mutableStateOf<String?>(null) }
    var allMediaItems by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    val selectedPaths = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        isLoading = true
        deviceFolders = vaultStorage.queryDeviceFolders()
        allMediaItems = vaultStorage.queryDeviceMediaFiles(null)
        isLoading = false
    }

    LaunchedEffect(selectedFolderFilter) {
        isLoading = true
        allMediaItems = vaultStorage.queryDeviceMediaFiles(selectedFolderFilter)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (targetFolderName != null) "Import into '$targetFolderName'" else "Browse Device Media",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Files are encrypted into vault & deleted from phone storage instantly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Folder Filter Chips
            if (deviceFolders.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedFolderFilter == null,
                            onClick = { selectedFolderFilter = null },
                            label = { Text("All Media (${allMediaItems.size})") }
                        )
                    }
                    items(deviceFolders) { folder ->
                        FilterChip(
                            selected = selectedFolderFilter == folder,
                            onClick = { selectedFolderFilter = folder },
                            label = { Text(folder) },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            // Main Media Content Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(460.dp)
                    .padding(horizontal = 12.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (allMediaItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Photo,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No media found in this device folder",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(allMediaItems, key = { it.path }) { item ->
                            val isSelected = selectedPaths.contains(item.path)
                            var thumb by remember(item.path) {
                                mutableStateOf<ImageBitmap?>(DeviceBrowserThumbnailLoader.getCached(item.path))
                            }

                            LaunchedEffect(item.path) {
                                if (thumb == null) {
                                    thumb = DeviceBrowserThumbnailLoader.loadThumbnail(context, item)
                                }
                            }

                            Card(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                                    .clickable {
                                        if (isSelected) selectedPaths.remove(item.path)
                                        else selectedPaths.add(item.path)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb!!,
                                            contentDescription = item.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.Photo,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                            modifier = Modifier
                                                .size(32.dp)
                                                .align(Alignment.Center)
                                        )
                                    }

                                    // Central Play Icon for Videos
                                    if (item.isVideo) {
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .align(Alignment.Center)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // Bottom Metadata Bar (Duration + File Size)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                                                )
                                            )
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (item.isVideo) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.Movie,
                                                        null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(10.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(
                                                        text = if (item.durationMs > 0L) formatDeviceDuration(item.durationMs) else "VIDEO",
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = item.name,
                                                    color = Color.White.copy(alpha = 0.9f),
                                                    fontSize = 9.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = formatDeviceFileSize(item.size),
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }

                                    // Checkbox Indicator
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        if (selectedPaths.size == allMediaItems.size) {
                            selectedPaths.clear()
                        } else {
                            selectedPaths.clear()
                            selectedPaths.addAll(allMediaItems.map { it.path })
                        }
                    }
                ) {
                    Text(if (selectedPaths.size == allMediaItems.size) "Deselect All" else "Select All (${allMediaItems.size})")
                }

                Button(
                    onClick = {
                        if (selectedPaths.isNotEmpty()) {
                            val paths = selectedPaths.toList()
                            onImportFiles(paths)
                            onDismiss()
                        }
                    },
                    enabled = selectedPaths.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("device_browser_import_button")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import & Delete (${selectedPaths.size})", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun formatDeviceDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatDeviceFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        val kb = (bytes / 1024L).coerceAtLeast(1L)
        "$kb KB"
    }
}
