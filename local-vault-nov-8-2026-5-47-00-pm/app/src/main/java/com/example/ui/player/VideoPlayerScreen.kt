package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.db.VaultItem
import kotlinx.coroutines.delay
import java.io.File
import javax.crypto.SecretKey
import kotlin.math.abs

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    vaultItem: VaultItem,
    blobFile: File,
    baseIv: ByteArray,
    masterKey: SecretKey,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(vaultItem.durationMs) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isTouchLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    // Resize modes: RESIZE_MODE_FIT (0), RESIZE_MODE_ZOOM (4), RESIZE_MODE_FILL (3)
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill/Crop",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
        )
    }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Gestures HUD
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(vaultItem.id) {
        val dataSourceFactory = DataSource.Factory {
            EncryptedFileDataSource(blobFile, masterKey, baseIv)
        }
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(blobFile))
            .setMimeType(vaultItem.mimeType.ifBlank { "video/mp4" })
            .build()

        // Architectural Fix: Provide DefaultExtractorsFactory with constant bitrate seeking enabled
        // so encrypted .bin blobs (MP4, MKV, MOV, WebM) sniff container headers and seek smoothly.
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(mediaItem)

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playerError = error.localizedMessage ?: "Codec/playback error: ${error.errorCodeName}"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        isPlaying = false
                    }
                }
            })
        }
    }

    // Keep screen awake during playback & handle pause on minimize
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

    // Position poller
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            if (exoPlayer.duration > 0) {
                duration = exoPlayer.duration
            }
            isPlaying = exoPlayer.isPlaying
            delay(400)
        }
    }

    // Clear HUD after short delay
    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(900)
            hudText = null
            hudIcon = null
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
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000)
                                    exoPlayer.seekTo(newPos)
                                    hudIcon = Icons.Default.FastRewind
                                    hudText = "-10 sec"
                                } else {
                                    val newPos = minOf(duration, exoPlayer.currentPosition + 10_000)
                                    exoPlayer.seekTo(newPos)
                                    hudIcon = Icons.Default.FastForward
                                    hudText = "+10 sec"
                                }
                            }
                        }
                    )
                }
                .pointerInput(isTouchLocked) {
                    detectDragGestures { change, dragAmount ->
                        if (!isTouchLocked) {
                            change.consume()
                            val width = size.width
                            val x = change.position.x

                            if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                // Horizontal timeline scrub
                                val deltaMs = (dragAmount.x * 250).toLong()
                                val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
                                exoPlayer.seekTo(target)
                                hudText = formatTime(target)
                                hudIcon = if (dragAmount.x >= 0) Icons.Default.FastForward else Icons.Default.FastRewind
                            } else {
                                // Vertical swipe: Left 40% = Brightness, Right 40% = Volume
                                if (x < width * 0.45f) {
                                    activity?.let { act ->
                                        val lp = act.window.attributes
                                        var currentB = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        currentB = (currentB - (dragAmount.y / 600f)).coerceIn(0.02f, 1.0f)
                                        lp.screenBrightness = currentB
                                        act.window.attributes = lp
                                        hudIcon = Icons.Default.BrightnessMedium
                                        hudText = "Brightness: ${(currentB * 100).toInt()}%"
                                    }
                                } else if (x > width * 0.55f) {
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val delta = if (dragAmount.y < 0) 1 else -1
                                    val nextVol = (curVol + delta).coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                    hudIcon = Icons.Default.VolumeUp
                                    val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                                    hudText = "Volume: $pct%"
                                }
                            }
                        }
                    }
                }
        ) {
            // ExoPlayer View
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        resizeMode = resizeModes[resizeIndex].first
                        playerViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // HUD Gesture Badge Overlay
            AnimatedVisibility(
                visible = hudText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        hudIcon?.let { icon ->
                            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = hudText ?: "",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Error Overlay if codec / file fails
            playerError?.let { err ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Playback Error", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(err, color = Color.White, fontSize = 13.sp, maxLines = 4)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            playerError = null
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }) {
                            Icon(Icons.Default.Replay, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
            }

            // Top Bar Controls (with statusBarsPadding to avoid camera notch overlap)
            AnimatedVisibility(
                visible = isControlsVisible || isTouchLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        )

                        // Aspect Ratio / Resize Toggle
                        IconButton(
                            onClick = {
                                resizeIndex = (resizeIndex + 1) % resizeModes.size
                                val nextMode = resizeModes[resizeIndex]
                                playerViewRef?.resizeMode = nextMode.first
                                hudIcon = Icons.Default.AspectRatio
                                hudText = "Display: ${nextMode.second}"
                            },
                            modifier = Modifier.testTag("player_aspect_ratio")
                        ) {
                            Icon(
                                Icons.Default.AspectRatio,
                                contentDescription = "Aspect Ratio",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Screen Touch Lock Button
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

            // Bottom Bar Controls (with navigationBarsPadding to avoid system gesture bar overlap)
            AnimatedVisibility(
                visible = isControlsVisible && !isTouchLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Timeline Slider & Timestamps
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { pos ->
                                exoPlayer.seekTo(pos.toLong())
                            },
                            valueRange = 0f..maxOf(1f, duration.toFloat()),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Controller Action Row (Rewind 10s, Play/Pause, Forward 10s, Single Speed Selector)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Single Clean Playback Speed Selector (No duplicates)
                        Box {
                            Surface(
                                onClick = { showSpeedMenu = true },
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier.testTag("player_speed_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "Speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${speed}x",
                                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            playbackSpeed = speed
                                            exoPlayer.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Center Playback Buttons: Rewind 10s • Play/Pause • Forward 10s
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000)
                                    exoPlayer.seekTo(newPos)
                                    hudIcon = Icons.Default.FastRewind
                                    hudText = "-10 sec"
                                },
                                modifier = Modifier.testTag("player_rewind_10")
                            ) {
                                Icon(
                                    Icons.Default.FastRewind,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

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
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .testTag("player_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val newPos = minOf(duration, exoPlayer.currentPosition + 10_000)
                                    exoPlayer.seekTo(newPos)
                                    hudIcon = Icons.Default.FastForward
                                    hudText = "+10 sec"
                                },
                                modifier = Modifier.testTag("player_forward_10")
                            ) {
                                Icon(
                                    Icons.Default.FastForward,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        // Resize Label
                        Text(
                            text = resizeModes[resizeIndex].second,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
