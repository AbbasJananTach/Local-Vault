package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.example.data.db.VaultItem
import kotlinx.coroutines.delay
import java.io.File
import javax.crypto.SecretKey

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    vaultItem: VaultItem,
    blobFile: File,
    baseIv: ByteArray,
    masterKey: SecretKey,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(vaultItem.durationMs) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isTouchLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var seekIndicatorText by remember { mutableStateOf<String?>(null) }

    // ExoPlayer setup with encrypted custom DataSource
    val exoPlayer = remember {
        val dataSourceFactory = DataSource.Factory {
            EncryptedFileDataSource(blobFile, masterKey, baseIv)
        }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(blobFile)))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    // Keep Screen Awake during playback & pause on screen off/minimize
    DisposableEffect(lifecycleOwner) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Track position update
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            if (exoPlayer.duration > 0) {
                duration = exoPlayer.duration
            }
            isPlaying = exoPlayer.isPlaying
            delay(500)
        }
    }

    // Seek indicator animation clear
    LaunchedEffect(seekIndicatorText) {
        if (seekIndicatorText != null) {
            delay(800)
            seekIndicatorText = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isTouchLocked) {
                    detectTapGestures(
                        onTap = {
                            if (!isTouchLocked) {
                                isControlsVisible = !isControlsVisible
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!isTouchLocked) {
                                val width = size.width
                                if (offset.x < width / 2) {
                                    // Seek -10s
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000)
                                    exoPlayer.seekTo(newPos)
                                    seekIndicatorText = "-10 sec"
                                } else {
                                    // Seek +10s
                                    val newPos = minOf(duration, exoPlayer.currentPosition + 10_000)
                                    exoPlayer.seekTo(newPos)
                                    seekIndicatorText = "+10 sec"
                                }
                            }
                        }
                    )
                }
                .pointerInput(isTouchLocked) {
                    detectDragGestures { change, dragAmount ->
                        if (!isTouchLocked) {
                            change.consume()
                            if (Math.abs(dragAmount.x) > Math.abs(dragAmount.y)) {
                                // Horizontal scrub
                                val deltaMs = (dragAmount.x * 200).toLong()
                                val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
                                exoPlayer.seekTo(target)
                            }
                        }
                    }
                }
        ) {
            // Player View Render
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Seek Indicator Overlay (-10s / +10s)
            seekIndicatorText?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Top Bar Controls
            AnimatedVisibility(
                visible = isControlsVisible || isTouchLocked,
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isTouchLocked) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("player_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Text(
                            text = vaultItem.fileName,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Lock Screen Touch Toggle
                    IconButton(
                        onClick = { isTouchLocked = !isTouchLocked },
                        modifier = Modifier.testTag("player_touch_lock")
                    ) {
                        Icon(
                            imageVector = if (isTouchLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock Controls",
                            tint = if (isTouchLocked) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }
                }
            }

            // Bottom Bar Controls
            AnimatedVisibility(
                visible = isControlsVisible && !isTouchLocked,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { pos ->
                                exoPlayer.seekTo(pos.toLong())
                            },
                            valueRange = 0f..maxOf(1f, duration.toFloat()),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )

                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playback Speed Menu
                        Box {
                            IconButton(onClick = { showSpeedMenu = true }) {
                                Icon(Icons.Default.Speed, "Playback Speed", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { Text("${speed}x") },
                                        onClick = {
                                            playbackSpeed = speed
                                            exoPlayer.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(32.dp))

                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                                isPlaying = !isPlaying
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))

                        Text(
                            text = "${playbackSpeed}x",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
