package com.example.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.ThumbnailCacheManager
import com.example.data.VaultRepository
import com.example.data.db.FolderItem
import com.example.data.db.VaultItem
import com.example.ui.components.CustomPinKeypadDialog
import com.example.ui.util.findActivity
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import javax.crypto.SecretKey
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class DragGestureMode {
    NONE,
    HORIZONTAL_SEEK,
    VERTICAL_BRIGHTNESS,
    VERTICAL_VOLUME
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    vaultItem: VaultItem,
    blobFile: File,
    baseIv: ByteArray,
    masterKey: SecretKey,
    vaultBlobsDir: File = blobFile.parentFile ?: blobFile,
    playlistVideos: List<VaultItem> = listOf(vaultItem),
    folders: List<FolderItem> = emptyList(),
    onVerifyContextualPin: (Long?, String) -> Boolean = { _, _ -> true },
    onUnhide: (VaultItem) -> Unit = {},
    onDelete: (VaultItem) -> Unit = {},
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val currentView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Supports switching videos inside the player via the "More Videos" YouTube-style thumbnail drawer (Point 26)
    var activeVideoItem by remember(vaultItem.id) { mutableStateOf(vaultItem) }
    val activeBlobFile = remember(activeVideoItem.id, activeVideoItem.encryptedRelativePath, vaultBlobsDir) {
        if (activeVideoItem.id == vaultItem.id) blobFile
        else File(vaultBlobsDir, activeVideoItem.encryptedRelativePath)
    }
    val activeBaseIv = remember(activeVideoItem.id, activeVideoItem.baseIvHex) {
        if (activeVideoItem.id == vaultItem.id) baseIv
        else fromHex(activeVideoItem.baseIvHex)
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember(activeVideoItem.id) {
        mutableLongStateOf(activeVideoItem.durationMs.coerceAtLeast(0L))
    }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }

    // Point 23: Auto-Hide Top Bar & All Control Buttons after 3000ms (3 seconds)
    var areControlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val registerInteraction: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
    }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showMoreVideosSheet by remember { mutableStateOf(false) }
    var isLandscapeMode by remember { mutableStateOf(false) }
    var videoResolutionLabel by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showUnhideConfirmDialog by remember { mutableStateOf(false) }
    var pendingPinActionTitle by remember { mutableStateOf<String?>(null) }
    var pendingPinActionCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Resize modes: RESIZE_MODE_FIT (0), RESIZE_MODE_ZOOM (4), RESIZE_MODE_FILL (3)
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
        )
    }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Subtle HUD & Center 400ms Double-Tap Play/Pause Indicator (Point 24)
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var centerDoubleTapIcon by remember { mutableStateOf<ImageVector?>(null) }
    var centerDoubleTapTick by remember { mutableIntStateOf(0) }
    var playerError by remember { mutableStateOf<String?>(null) }

    // Drop shadow for crisp white text over bright video frames on 100% transparent overlays (Point 25)
    val overlayTextShadow = remember {
        Shadow(
            color = Color.Black.copy(alpha = 0.85f),
            offset = Offset(0f, 1.5f),
            blurRadius = 6f
        )
    }

    val toggleOrientation: () -> Unit = {
        registerInteraction()
        val nextLandscape = !isLandscapeMode
        isLandscapeMode = nextLandscape
        try {
            activity?.requestedOrientation = if (nextLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } catch (_: Throwable) {
        }
        hudIcon = Icons.Default.ScreenRotation
        hudText = if (nextLandscape) "Landscape" else "Portrait"
    }

    // Point 21 (Layer 3) & Point 22 & Hotfix #1: Defensive ExoPlayer with DefaultRenderersFactory decoder fallback,
    // C.WAKE_MODE_LOCAL, and DefaultExtractorsFactory constant-bitrate seeking.
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setWakeMode(C.WAKE_MODE_LOCAL)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoResolutionLabel = "${videoSize.width}×${videoSize.height}"
                        val shouldBeLandscape = videoSize.width > videoSize.height
                        isLandscapeMode = shouldBeLandscape
                        try {
                            activity?.requestedOrientation = if (shouldBeLandscape) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerError = error.localizedMessage ?: "Playback error (${error.errorCodeName})"
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        isPlaying = false
                        areControlsVisible = true
                    }
                }
            })
        }
    }

    // Hotfix #1: Defensively load/switch decrypted streaming MediaSource inside try-catch
    LaunchedEffect(activeVideoItem.id, activeBlobFile, activeBaseIv) {
        playerError = null
        currentPosition = 0L
        duration = activeVideoItem.durationMs.coerceAtLeast(0L)

        try {
            val dataSourceFactory = DataSource.Factory {
                EncryptedFileDataSource(activeBlobFile, masterKey, activeBaseIv)
            }
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)

            val sanitizedExt = extractSanitizedVideoExtension(activeVideoItem.fileName, activeVideoItem.mimeType)
            val streamUri = Uri.parse("vault://video/${activeVideoItem.id}.$sanitizedExt")

            val mediaItem = MediaItem.Builder()
                .setUri(streamUri)
                .build()

            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            isPlaying = true
            areControlsVisible = true
            lastInteractionTime = System.currentTimeMillis()
        } catch (t: Throwable) {
            playerError = t.localizedMessage ?: "Unable to initialize video player"
            isPlaying = false
        }
    }

    // Point 21 & Hotfix #1: Keep screen awake during playback + restore orientation/brightness safely on dispose.
    // Ignores transient config-change lifecycle pauses while rotating so playback & vault session never reset!
    DisposableEffect(lifecycleOwner, exoPlayer, currentView) {
        VaultRepository.registerActivePlayer(exoPlayer)
        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
        currentView.keepScreenOn = true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val screenOffReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                    try {
                        exoPlayer.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        exoPlayer.release()
                    } catch (_: Throwable) {
                    }
                    VaultRepository.stopAndReleaseActivePlayerAndClearSession()
                    try {
                        activity?.finishAffinity()
                        activity?.finishAndRemoveTask()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        try {
            context.registerReceiver(
                screenOffReceiver,
                android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
            )
        } catch (_: Throwable) {
        }

        val observer = LifecycleEventObserver { _, event ->
            if (activity?.isChangingConfigurations == true) {
                return@LifecycleEventObserver
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val isScreenOff = powerManager != null && !powerManager.isInteractive
                if (isScreenOff) {
                    try {
                        exoPlayer.stop()
                    } catch (_: Throwable) {
                    }
                    try {
                        exoPlayer.release()
                    } catch (_: Throwable) {
                    }
                    VaultRepository.stopAndReleaseActivePlayerAndClearSession()
                    try {
                        activity?.finishAffinity()
                        activity?.finishAndRemoveTask()
                    } catch (_: Throwable) {
                    }
                } else {
                    try {
                        exoPlayer.pause()
                    } catch (_: Throwable) {
                    }
                    isPlaying = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            try {
                context.unregisterReceiver(screenOffReceiver)
            } catch (_: Throwable) {
            }
            VaultRepository.unregisterActivePlayer(exoPlayer)

            currentView.keepScreenOn = false
            try {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Throwable) {
            }

            try {
                val params = activity?.window?.attributes
                if (params != null) {
                    params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    activity.window.attributes = params
                }
            } catch (_: Throwable) {
            }

            try {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } catch (_: Throwable) {
            }
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                exoPlayer.stop()
            } catch (_: Throwable) {
            }
            try {
                exoPlayer.release()
            } catch (_: Throwable) {
            }
        }
    }

    // Position poller
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!isScrubbing) {
                currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            if (exoPlayer.duration > 0) {
                duration = exoPlayer.duration
            }
            isPlaying = exoPlayer.isPlaying
            delay(200L)
        }
    }

    // Point 23: Auto-hide ALL controls after 3000ms (3 seconds) when playing
    LaunchedEffect(areControlsVisible, isPlaying, lastInteractionTime, isScrubbing, showSpeedMenu, showMoreVideosSheet) {
        if (areControlsVisible && isPlaying && !isScrubbing && !showSpeedMenu && !showMoreVideosSheet) {
            delay(3000L)
            areControlsVisible = false
        }
    }

    // Point 24: Brief 400ms center play/pause indicator on center double-tap
    LaunchedEffect(centerDoubleTapTick) {
        if (centerDoubleTapIcon != null) {
            delay(400L)
            centerDoubleTapIcon = null
        }
    }

    // Clear HUD after short delay
    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(850L)
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
                .pointerInput(Unit) {
                    // Point 23 & Point 24:
                    // - Single-Tap anywhere toggles areControlsVisible = !areControlsVisible
                    // - Double-Tap in the center 50% width toggles Play/Pause with a 400ms subtle fade-out icon
                    // - Double-Tap on far left 25% seeks -10s; far right 25% seeks +10s
                    detectTapGestures(
                        onTap = {
                            areControlsVisible = !areControlsVisible
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onDoubleTap = { offset ->
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            lastInteractionTime = System.currentTimeMillis()
                            when {
                                offset.x < width * 0.25f -> {
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    hudIcon = Icons.Default.FastRewind
                                    hudText = "-10s"
                                }
                                offset.x > width * 0.75f -> {
                                    val newPos = minOf(duration.coerceAtLeast(0L), exoPlayer.currentPosition + 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    hudIcon = Icons.Default.FastForward
                                    hudText = "+10s"
                                }
                                else -> {
                                    // Center 50% width: Toggle Play / Pause immediately
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                        isPlaying = false
                                        centerDoubleTapIcon = Icons.Default.Pause
                                    } else {
                                        if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                            exoPlayer.seekTo(0L)
                                        }
                                        exoPlayer.play()
                                        isPlaying = true
                                        centerDoubleTapIcon = Icons.Default.PlayArrow
                                    }
                                    centerDoubleTapTick++
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Point 27 (Steps 2 & 3):
                    // - Requires dead-zone threshold > viewConfiguration.touchSlop * 2 before starting seek or vertical adjust
                    // - Smooth horizontal swipe seek (full screen width = 75 seconds max)
                    // - Cumulative vertical delta tracking (totalDragY / screenHeight) with 0.6f damping factor
                    var gestureMode = DragGestureMode.NONE
                    var startX = 0f
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var gestureStartPositionMs = 0L
                    var gestureStartBrightness = 0.5f
                    var gestureStartVolumeFloat = 0f

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            gestureMode = DragGestureMode.NONE
                            startX = startOffset.x
                            totalDragX = 0f
                            totalDragY = 0f
                            gestureStartPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)

                            val currentAttrBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
                            gestureStartBrightness = if (currentAttrBrightness in 0.01f..1.0f) {
                                currentAttrBrightness
                            } else {
                                0.5f
                            }
                            gestureStartVolumeFloat = audioManager
                                .getStreamVolume(AudioManager.STREAM_MUSIC)
                                .toFloat()
                        },
                        onDragCancel = {
                            gestureMode = DragGestureMode.NONE
                            isScrubbing = false
                        },
                        onDragEnd = {
                            gestureMode = DragGestureMode.NONE
                            isScrubbing = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y

                            val deadZone = viewConfiguration.touchSlop * 2f
                            val screenWidth = size.width.toFloat().coerceAtLeast(1f)
                            val screenHeight = size.height.toFloat().coerceAtLeast(1f)

                            if (gestureMode == DragGestureMode.NONE) {
                                if (abs(totalDragX) > deadZone && abs(totalDragX) > abs(totalDragY) * 1.2f) {
                                    gestureMode = DragGestureMode.HORIZONTAL_SEEK
                                    // Reset baseline so seek starts smoothly right after crossing dead-zone
                                    totalDragX = 0f
                                    gestureStartPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                                } else if (abs(totalDragY) > deadZone && abs(totalDragY) > abs(totalDragX) * 1.2f) {
                                    gestureMode = if (startX < screenWidth * 0.5f) {
                                        DragGestureMode.VERTICAL_BRIGHTNESS
                                    } else {
                                        DragGestureMode.VERTICAL_VOLUME
                                    }
                                    totalDragY = 0f
                                }
                            }

                            when (gestureMode) {
                                DragGestureMode.HORIZONTAL_SEEK -> {
                                    // Full screen width drag = 75 seconds (75,000 ms) smooth seek
                                    val seekRangeMs = 75_000f
                                    val deltaMs = ((totalDragX / screenWidth) * seekRangeMs).toLong()
                                    val maxDur = duration.coerceAtLeast(1L)
                                    val target = (gestureStartPositionMs + deltaMs).coerceIn(0L, maxDur)
                                    isScrubbing = true
                                    scrubPosition = target
                                    currentPosition = target
                                    exoPlayer.seekTo(target)
                                    hudIcon = if (deltaMs >= 0L) Icons.Default.FastForward else Icons.Default.FastRewind
                                    val sign = if (deltaMs >= 0L) "+" else "-"
                                    val deltaSec = abs(deltaMs / 1000L)
                                    hudText = "${formatTime(target)} ($sign${deltaSec}s)"
                                }
                                DragGestureMode.VERTICAL_BRIGHTNESS -> {
                                    activity?.let { act ->
                                        val damping = 0.6f
                                        val deltaFraction = (-totalDragY / screenHeight) * damping
                                        val newBrightness = (gestureStartBrightness + deltaFraction).coerceIn(0.02f, 1.0f)
                                        val lp = act.window.attributes
                                        lp.screenBrightness = newBrightness
                                        act.window.attributes = lp
                                        hudIcon = Icons.Default.BrightnessMedium
                                        hudText = "Brightness ${(newBrightness * 100f).roundToInt()}%"
                                    }
                                }
                                DragGestureMode.VERTICAL_VOLUME -> {
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val damping = 0.6f
                                    val deltaFraction = (-totalDragY / screenHeight) * damping
                                    val newVolFloat = (gestureStartVolumeFloat + deltaFraction * maxVol).coerceIn(0f, maxVol.toFloat())
                                    val targetVolInt = newVolFloat.roundToInt().coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolInt, 0)
                                    val pct = ((targetVolInt.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                                    hudIcon = Icons.Default.VolumeUp
                                    hudText = "Volume $pct%"
                                }
                                DragGestureMode.NONE -> Unit
                            }
                        }
                    )
                }
        ) {
            // Point 21 (Layer 3): ExoPlayer Surface View with keepScreenOn = true
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        keepScreenOn = true
                        resizeMode = resizeModes[resizeIndex].first
                        playerViewRef = this
                    }
                },
                update = { view ->
                    view.keepScreenOn = true
                },
                modifier = Modifier.fillMaxSize()
            )

            // Point 24: Center of the screen has NO permanent buttons!
            // Only shows a brief 400ms subtle fade-out Play/Pause icon when double-tapped in the center.
            AnimatedVisibility(
                visible = centerDoubleTapIcon != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                centerDoubleTapIcon?.let { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            // Subtle Transparent HUD Gesture Text Overlay (top-center)
            AnimatedVisibility(
                visible = hudText != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    hudIcon?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = hudText ?: "",
                        color = Color.White,
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = overlayTextShadow
                        )
                    )
                }
            }

            // Error Overlay if a damaged/corrupted file fails
            playerError?.let { err ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                ) {
                    Text(
                        text = "Unable to Play Video",
                        color = MaterialTheme.colorScheme.error,
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = overlayTextShadow
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = err,
                        color = Color.White,
                        style = TextStyle(fontSize = 13.sp, shadow = overlayTextShadow),
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            playerError = null
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    ) {
                        Icon(Icons.Default.Replay, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }

            // Point 23 & Point 25: Auto-Hiding 100% Transparent Top Bar
            // Contains Back arrow, Title, Unhide, Delete, and top-right Small Rotate icon & Fit icon.
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("player_back")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = activeVideoItem.fileName,
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = overlayTextShadow
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        videoResolutionLabel?.let { res ->
                            Text(
                                text = res,
                                color = Color.White.copy(alpha = 0.8f),
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    shadow = overlayTextShadow
                                )
                            )
                        }
                    }

                    // Unhide Video Button (Point 10: LockOpen icon + Confirmation + Contextual PIN)
                    IconButton(
                        onClick = {
                            registerInteraction()
                            exoPlayer.pause()
                            isPlaying = false
                            showUnhideConfirmDialog = true
                        },
                        modifier = Modifier.testTag("player_unhide")
                    ) {
                        Icon(
                            Icons.Default.LockOpen,
                            contentDescription = "Unhide Video",
                            tint = Color.White
                        )
                    }

                    // Delete Video Button (Point 9: Confirmation + Contextual PIN)
                    IconButton(
                        onClick = {
                            registerInteraction()
                            exoPlayer.pause()
                            isPlaying = false
                            showDeleteConfirmDialog = true
                        },
                        modifier = Modifier.testTag("player_delete")
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Video",
                            tint = Color.White
                        )
                    }

                    // Point 25 (Step 2): Small Corner Rotate Screen Icon in Top-Right
                    IconButton(
                        onClick = toggleOrientation,
                        modifier = Modifier.testTag("toggle_orientation_button")
                    ) {
                        Icon(
                            Icons.Default.ScreenRotation,
                            contentDescription = "Rotate Screen",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Point 25 (Step 2): Fit / Aspect Ratio Icon in Top-Right
                    IconButton(
                        onClick = {
                            registerInteraction()
                            resizeIndex = (resizeIndex + 1) % resizeModes.size
                            val nextMode = resizeModes[resizeIndex]
                            playerViewRef?.resizeMode = nextMode.first
                            hudIcon = Icons.Default.AspectRatio
                            hudText = nextMode.second
                        },
                        modifier = Modifier.testTag("player_aspect_ratio")
                    ) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = "Fit / Aspect Ratio",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Hotfix #5: Bottom Auto-Hiding Control Layout:
            // - Row 1 (Upper Row): Compact time text + speed pill, followed by full-width YouTube-style thin Slider
            // - Row 2 (Lower Row — Directly BELOW Slider): Previous Video, Play/Pause, Next Video on left,
            //   and "More Videos" button in the right corner.
            AnimatedVisibility(
                visible = areControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val activePos = if (isScrubbing) scrubPosition else currentPosition
                val sliderInteractionSource = remember { MutableInteractionSource() }
                val currentVideoIndex = remember(activeVideoItem.id, playlistVideos) {
                    playlistVideos.indexOfFirst { it.id == activeVideoItem.id }
                }
                val hasPrevVideo = currentVideoIndex > 0
                val hasNextVideo = currentVideoIndex >= 0 && currentVideoIndex < playlistVideos.lastIndex

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    // ROW 1 (UPPER ROW): Timestamp & Speed badge + Ultra-Thin Full-Width Progress Slider on top
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(activePos)} / ${formatTime(duration)}",
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                shadow = overlayTextShadow
                            )
                        )

                        // Playback Speed selector
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        registerInteraction()
                                        showSpeedMenu = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("player_speed_btn"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Speed,
                                    contentDescription = "Speed",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = Color.White,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        shadow = overlayTextShadow
                                    )
                                )
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = {
                                    showSpeedMenu = false
                                    registerInteraction()
                                }
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
                                            registerInteraction()
                                            playbackSpeed = speed
                                            exoPlayer.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Full-Width Ultra-Thin (2.5.dp) YouTube-Style Progress Slider
                    Slider(
                        value = activePos.toFloat().coerceIn(0f, maxOf(1f, duration.toFloat())),
                        onValueChange = { pos ->
                            registerInteraction()
                            isScrubbing = true
                            scrubPosition = pos.toLong()
                            exoPlayer.seekTo(scrubPosition)
                        },
                        onValueChangeFinished = {
                            registerInteraction()
                            currentPosition = scrubPosition
                            isScrubbing = false
                        },
                        valueRange = 0f..maxOf(1f, duration.toFloat()),
                        interactionSource = sliderInteractionSource,
                        thumb = {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        },
                        track = { sliderState ->
                            SliderDefaults.Track(
                                sliderState = sliderState,
                                modifier = Modifier.height(2.5.dp),
                                thumbTrackGapSize = 0.dp,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .testTag("player_thin_slider")
                    )

                    // ROW 2 (LOWER ROW — DIRECTLY BELOW SLIDER):
                    // Left: Previous Video, Play/Pause, Next Video
                    // Right: "More Videos" Thumbnail Drawer Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (hasPrevVideo) {
                                        activeVideoItem = playlistVideos[currentVideoIndex - 1]
                                    } else {
                                        exoPlayer.seekTo(0L)
                                        currentPosition = 0L
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("player_previous_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Video",
                                    tint = if (hasPrevVideo) Color.White else Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                        isPlaying = false
                                    } else {
                                        if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                            exoPlayer.seekTo(0L)
                                        }
                                        exoPlayer.play()
                                        isPlaying = true
                                    }
                                },
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("player_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (hasNextVideo) {
                                        activeVideoItem = playlistVideos[currentVideoIndex + 1]
                                    }
                                },
                                enabled = hasNextVideo,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("player_next_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Video",
                                    tint = if (hasNextVideo) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    registerInteraction()
                                    showMoreVideosSheet = true
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("player_more_videos_button"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VideoLibrary,
                                contentDescription = "More Videos",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "More Videos",
                                color = Color.White,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    shadow = overlayTextShadow
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // Point 26 (Step 2): "More Videos" YouTube-Style Translucent Thumbnail Sheet
    if (showMoreVideosSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoreVideosSheet = false
                registerInteraction()
            },
            containerColor = Color(0xE6101218),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "More Videos (${playlistVideos.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    TextButton(
                        onClick = {
                            showMoreVideosSheet = false
                            registerInteraction()
                        }
                    ) {
                        Text("Close", color = Color.White.copy(alpha = 0.8f))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(playlistVideos, key = { it.id }) { videoItem ->
                        val isCurrentlyPlaying = videoItem.id == activeVideoItem.id
                        val itemBlob = remember(videoItem.encryptedRelativePath) {
                            File(vaultBlobsDir, videoItem.encryptedRelativePath)
                        }
                        var thumb by remember(videoItem.id) {
                            mutableStateOf<ImageBitmap?>(ThumbnailCacheManager.getCached(videoItem.id))
                        }

                        LaunchedEffect(videoItem.id, itemBlob) {
                            if (thumb == null) {
                                thumb = ThumbnailCacheManager.loadThumbnail(videoItem, itemBlob, masterKey)
                            }
                        }

                        Card(
                            onClick = {
                                showMoreVideosSheet = false
                                registerInteraction()
                                if (videoItem.id != activeVideoItem.id) {
                                    activeVideoItem = videoItem
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isCurrentlyPlaying) {
                                        Modifier.border(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentlyPlaying) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                                } else {
                                    Color.White.copy(alpha = 0.08f)
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 16:9 Video Thumbnail Preview
                                Box(
                                    modifier = Modifier
                                        .width(112.dp)
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb!!,
                                            contentDescription = videoItem.fileName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Movie,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.5f),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }

                                    // Duration badge at bottom-right of thumbnail
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(4.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.78f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = if (videoItem.durationMs > 0L) formatTime(videoItem.durationMs) else "VIDEO",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = videoItem.fileName,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isCurrentlyPlaying) "Now Playing"
                                        else formatVideoSize(videoItem.fileSize),
                                        color = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                                        else Color.White.copy(alpha = 0.65f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Point 10: Step 1 Confirmation Dialog before Unhide -> Step 2 Contextual PIN Gate
    if (showUnhideConfirmDialog) {
        val targetItem = activeVideoItem
        AlertDialog(
            onDismissRequest = { showUnhideConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Unhide Media?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Unhide and restore '${targetItem.fileName}' to your phone?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnhideConfirmDialog = false
                        pendingPinActionTitle = "Confirm Unhide Media"
                        pendingPinActionCallback = {
                            try {
                                exoPlayer.stop()
                            } catch (_: Throwable) {
                            }
                            onUnhide(targetItem)
                        }
                    }
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verify PIN & Unhide")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnhideConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 9: Step 1 Confirmation Dialog before Delete -> Step 2 Contextual PIN Gate
    if (showDeleteConfirmDialog) {
        val targetItem = activeVideoItem
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(16.dp),
            title = { Text("Delete selected item(s) permanently?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Are you sure you want to delete '${targetItem.fileName}' permanently from your Vault? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        pendingPinActionTitle = "Delete Permanently"
                        pendingPinActionCallback = {
                            try {
                                exoPlayer.stop()
                            } catch (_: Throwable) {
                            }
                            onDelete(targetItem)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Point 7 & Point 8: Context-Aware CustomPinKeypadDialog
    if (pendingPinActionTitle != null && pendingPinActionCallback != null) {
        val itemFolder = remember(activeVideoItem.folderId, folders) {
            activeVideoItem.folderId?.let { id -> folders.find { it.id == id } }
        }
        val usesCustomFolderPin = itemFolder != null && itemFolder.hasCustomPin()

        CustomPinKeypadDialog(
            title = if (usesCustomFolderPin) "Unlock '${itemFolder!!.folderName}'" else pendingPinActionTitle!!,
            subtitle = if (usesCustomFolderPin) {
                "Enter '${itemFolder!!.folderName}' Folder PIN and tap Unlock (✓)"
            } else {
                "Enter your Vault PIN and tap Unlock (✓)"
            },
            doneButtonLabel = "Unlock",
            onVerifyPin = { inputPin ->
                onVerifyContextualPin(activeVideoItem.folderId, inputPin)
            },
            onSuccess = {
                val callback = pendingPinActionCallback
                pendingPinActionTitle = null
                pendingPinActionCallback = null
                callback?.invoke()
            },
            onDismiss = {
                pendingPinActionTitle = null
                pendingPinActionCallback = null
            }
        )
    }
}

/**
 * Point 22: Sanitizes any video filename so extra characters after `.mp4` (e.g. `video.mp4 (1)`)
 * or unusual extensions (`.mkv`, `.mov`, `.webm`, `.avi`, `.3gp`) resolve to a clean container extension.
 */
private fun extractSanitizedVideoExtension(fileName: String, mimeType: String): String {
    val lower = fileName.lowercase(Locale.US)
    val regex = Regex("""\.(mp4|m4v|mkv|mov|webm|avi|3gp|3gpp|ts|m2ts|mts|flv|wmv)(?:[^a-z0-9]|$)""")
    val match = regex.find(lower)
    if (match != null) {
        return match.groupValues[1]
    }
    val m = mimeType.lowercase(Locale.US)
    return when {
        m.contains("matroska") || m.contains("mkv") -> "mkv"
        m.contains("quicktime") || m.contains("mov") -> "mov"
        m.contains("webm") -> "webm"
        m.contains("avi") -> "avi"
        m.contains("3gp") -> "3gp"
        m.contains("mp2t") || m.contains("ts") -> "ts"
        else -> "mp4"
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatVideoSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
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
