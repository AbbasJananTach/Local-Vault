package com.example.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.example.data.db.VaultItem
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VideoPlayerScreen(
    vaultItem: VaultItem,
    blobFile: File,
    baseIv: ByteArray,
    masterKey: SecretKey,
    vaultBlobsDir: File = blobFile.parentFile ?: blobFile,
    playlistVideos: List<VaultItem> = listOf(vaultItem),
    onSavePlaybackPosition: (VaultItem, Long) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

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

    // Controls visibility & Screen Touch Lock
    var areControlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var showUnlockHint by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val registerInteraction: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
    }

    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    // FIX 7: Hold-to-2x temporary speed boost state
    var isLongPress2xActive by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(1.0f) }

    var showSpeedMenu by remember { mutableStateOf(false) }
    var showMoreVideosSheet by remember { mutableStateOf(false) }
    val moreVideosListState = rememberLazyListState()
    var isLandscapeMode by remember { mutableStateOf(false) }
    var videoResolutionLabel by remember { mutableStateOf<String?>(null) }
    var isRepeatOne by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    // FIX 6: Session map of saved playback positions across playlist switches
    val sessionPositions = remember { mutableStateMapOf<Long, Long>() }

    // Interactive Pinch-to-Zoom & Pan state (100% .. 400%)
    var videoZoom by remember { mutableFloatStateOf(1.0f) }
    var videoPanX by remember { mutableFloatStateOf(0f) }
    var videoPanY by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Aspect ratios: FIT -> CROP/FILL -> STRETCH
    val resizeModes = remember {
        listOf(
            Triple(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Fit", "Fit to Screen"),
            Triple(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Crop", "Crop / Fill"),
            Triple(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Stretch", "Stretch")
        )
    }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Subtle HUD & Center 400ms Double-Tap Play/Pause Indicator
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var centerDoubleTapIcon by remember { mutableStateOf<ImageVector?>(null) }
    var centerDoubleTapTick by remember { mutableIntStateOf(0) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val overlayTextShadow = remember {
        Shadow(
            color = Color.Black.copy(alpha = 0.85f),
            offset = Offset(0f, 1.5f),
            blurRadius = 6f
        )
    }

    val showHud: (ImageVector?, String) -> Unit = { icon, text ->
        hudIcon = icon
        hudText = text
        hudTick++
    }

    // FIX 5: Synchronize "More videos" horizontal LazyRow to currently playing video index when opened
    LaunchedEffect(showMoreVideosSheet, activeVideoItem.id, playlistVideos) {
        if (showMoreVideosSheet) {
            val currentIdx = playlistVideos.indexOfFirst { it.id == activeVideoItem.id }
            if (currentIdx >= 0) {
                moreVideosListState.scrollToItem(currentIdx)
            }
        }
    }

    val zoomSteps = remember { listOf(1.0f, 1.25f, 1.5f, 2.0f, 3.0f) }
    val cycleZoomStep: () -> Unit = {
        registerInteraction()
        val nextZoom = zoomSteps.firstOrNull { it > videoZoom + 0.05f } ?: 1.0f
        videoZoom = nextZoom
        if (nextZoom <= 1.01f) {
            videoPanX = 0f
            videoPanY = 0f
        } else {
            val maxX = (containerSize.width * (nextZoom - 1f)) / 2f
            val maxY = (containerSize.height * (nextZoom - 1f)) / 2f
            videoPanX = videoPanX.coerceIn(-maxX, maxX)
            videoPanY = videoPanY.coerceIn(-maxY, maxY)
        }
        showHud(Icons.Default.ZoomIn, "Zoom ${(nextZoom * 100f).roundToInt()}%")
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
        showHud(Icons.Default.ScreenRotation, if (nextLandscape) "Landscape" else "Portrait")
    }

    // FIX 4: Hide Android System Status Bar (Wi-Fi/Battery/Clock) & Navigation Bar (Back/Home/Recents)
    // whenever controls auto-hide or touch lock is active, and restore cleanly on exit.
    val insetsController = remember(activity, currentView) {
        activity?.window?.let { WindowCompat.getInsetsController(it, currentView) }
    }
    LaunchedEffect(insetsController, areControlsVisible, isScreenLocked, showMoreVideosSheet) {
        insetsController?.let { controller ->
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val shouldShowBars = (areControlsVisible && !isScreenLocked) || showMoreVideosSheet
            if (shouldShowBars) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Defensive ExoPlayer with DefaultRenderersFactory decoder fallback, WAKE_MODE_LOCAL,
    // and DefaultExtractorsFactory constant-bitrate seeking.
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context, renderersFactory).build().apply {
            try {
                setWakeMode(C.WAKE_MODE_LOCAL)
            } catch (_: Throwable) {
            }
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        videoResolutionLabel = "${videoSize.width}×${videoSize.height}"
                        val shouldBeLandscape = videoSize.width > videoSize.height
                        if (isLandscapeMode != shouldBeLandscape) {
                            isLandscapeMode = shouldBeLandscape
                            try {
                                val targetOrientation = if (shouldBeLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                }
                                if (activity != null && activity.requestedOrientation != targetOrientation) {
                                    activity.requestedOrientation = targetOrientation
                                }
                            } catch (_: Throwable) {
                            }
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
                    if (state == Player.STATE_ENDED && repeatMode == Player.REPEAT_MODE_OFF) {
                        isPlaying = false
                        areControlsVisible = true
                        sessionPositions[activeVideoItem.id] = 0L
                        onSavePlaybackPosition(activeVideoItem, 0L)
                    }
                }
            })
        }
    }

    // FIX 6: Helper to snapshot and persist current playback position for activeVideoItem
    val saveCurrentPosition: () -> Unit = {
        try {
            val itemSnapshot = activeVideoItem
            val dur = exoPlayer.duration.takeIf { it > 0L } ?: duration
            val rawPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val normalizedPos = if (
                exoPlayer.playbackState == Player.STATE_ENDED ||
                (dur > 0L && rawPos >= dur - 1500L)
            ) {
                0L
            } else {
                rawPos
            }
            sessionPositions[itemSnapshot.id] = normalizedPos
            onSavePlaybackPosition(itemSnapshot, normalizedPos)
        } catch (_: Throwable) {
        }
    }

    // Back closes "More videos" overlay first before saving position and exiting VideoPlayerScreen
    BackHandler {
        if (showMoreVideosSheet) {
            showMoreVideosSheet = false
            registerInteraction()
        } else {
            saveCurrentPosition()
            onBack()
        }
    }

    // Load or switch encrypted video stream safely and resume from last saved playback position (FIX 6)
    LaunchedEffect(activeVideoItem.id, activeBlobFile, activeBaseIv) {
        playerError = null
        val latestItem = playlistVideos.firstOrNull { it.id == activeVideoItem.id } ?: activeVideoItem
        val resumePosMs = (sessionPositions[activeVideoItem.id] ?: latestItem.lastPlaybackPositionMs).coerceAtLeast(0L)
        val knownDur = latestItem.durationMs.coerceAtLeast(0L)
        val shouldResume = resumePosMs > 0L && (knownDur <= 0L || resumePosMs < knownDur - 1500L)

        currentPosition = if (shouldResume) resumePosMs else 0L
        duration = knownDur
        videoZoom = 1.0f
        videoPanX = 0f
        videoPanY = 0f

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

            if (shouldResume) {
                exoPlayer.setMediaSource(mediaSource, resumePosMs)
            } else {
                exoPlayer.setMediaSource(mediaSource)
            }
            exoPlayer.setPlaybackSpeed(if (isLongPress2xActive) 2.0f else playbackSpeed)
            exoPlayer.repeatMode = if (isRepeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            exoPlayer.volume = if (isMuted) 0f else 1f
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            isPlaying = true
            if (shouldResume) {
                showHud(Icons.Default.PlayArrow, "Resumed at ${formatTime(resumePosMs)}")
            }
            if (!isScreenLocked) {
                areControlsVisible = true
            }
            lastInteractionTime = System.currentTimeMillis()
        } catch (t: Throwable) {
            playerError = t.localizedMessage ?: "Unable to initialize video player"
            isPlaying = false
        }
    }

    // FIX 5: Removed duplicate local screenOffReceiver & finishAffinity() from VideoPlayerScreen.
    // MainActivity handles ACTION_SCREEN_OFF -> exitAndRemoveTaskOnScreenOff() centrally.
    DisposableEffect(lifecycleOwner, exoPlayer, currentView, insetsController) {
        VaultRepository.registerActivePlayer(exoPlayer)
        try {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {
        }
        currentView.keepScreenOn = true

        val observer = LifecycleEventObserver { _, event ->
            if (activity?.isChangingConfigurations == true) {
                return@LifecycleEventObserver
            }
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveCurrentPosition()
                try {
                    exoPlayer.pause()
                } catch (_: Throwable) {
                }
                isPlaying = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            saveCurrentPosition()
            VaultRepository.unregisterActivePlayer(exoPlayer)

            currentView.keepScreenOn = false
            try {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (_: Throwable) {
            }

            try {
                insetsController?.show(WindowInsetsCompat.Type.systemBars())
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

    // Auto-hide ALL controls + system bars after 3000ms (3 seconds) when playing
    LaunchedEffect(areControlsVisible, isPlaying, lastInteractionTime, isScrubbing, showSpeedMenu, showMoreVideosSheet, isScreenLocked) {
        if (areControlsVisible && isPlaying && !isScrubbing && !showSpeedMenu && !showMoreVideosSheet && !isScreenLocked) {
            delay(3000L)
            areControlsVisible = false
        }
    }

    // Auto-hide "Tap to Unlock" lock button after 2.5s when screen touch lock is active
    LaunchedEffect(showUnlockHint, isScreenLocked) {
        if (showUnlockHint && isScreenLocked) {
            delay(2500L)
            showUnlockHint = false
        }
    }

    // Brief 400ms center play/pause indicator on center double-tap
    LaunchedEffect(centerDoubleTapTick) {
        if (centerDoubleTapIcon != null) {
            delay(400L)
            centerDoubleTapIcon = null
        }
    }

    // Clear HUD after short delay
    LaunchedEffect(hudTick) {
        if (hudText != null) {
            delay(900L)
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
                .clipToBounds()
                .onSizeChanged { containerSize = it }
                // Handler 1: Two-finger Pinch-to-Zoom (100%..400%) and Zoomed Pan
                .pointerInput(isScreenLocked) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (isScreenLocked) return@awaitEachGesture
                        var isMultiTouchOrZoomPan = false
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            if (pressedCount >= 2 || videoZoom > 1.01f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val shouldHandle = pressedCount >= 2 ||
                                    (videoZoom > 1.01f && (abs(panChange.x) > 3f || abs(panChange.y) > 3f || isMultiTouchOrZoomPan))

                                if (shouldHandle) {
                                    isMultiTouchOrZoomPan = true
                                    val prevZoom = videoZoom
                                    val nextZoom = if (pressedCount >= 2) {
                                        (prevZoom * zoomChange).coerceIn(1.0f, 4.0f)
                                    } else {
                                        prevZoom
                                    }
                                    val maxX = (containerSize.width * (nextZoom - 1f)) / 2f
                                    val maxY = (containerSize.height * (nextZoom - 1f)) / 2f

                                    if (nextZoom <= 1.01f) {
                                        videoZoom = 1.0f
                                        videoPanX = 0f
                                        videoPanY = 0f
                                    } else {
                                        videoZoom = nextZoom
                                        videoPanX = (videoPanX + panChange.x).coerceIn(-maxX, maxX)
                                        videoPanY = (videoPanY + panChange.y).coerceIn(-maxY, maxY)
                                    }

                                    if (pressedCount >= 2 && abs(nextZoom - prevZoom) > 0.002f) {
                                        showHud(Icons.Default.ZoomIn, "Zoom ${(nextZoom * 100f).roundToInt()}%")
                                    }

                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) {
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                // Handler 2: Single-Tap controls toggle, Hold for 2x Speed (FIX 7), & Double-Tap (-10s / Play-Pause / +10s)
                .pointerInput(isScreenLocked) {
                    detectTapGestures(
                        onPress = {
                            try {
                                tryAwaitRelease()
                            } finally {
                                if (isLongPress2xActive) {
                                    isLongPress2xActive = false
                                    exoPlayer.setPlaybackSpeed(speedBeforeLongPress)
                                }
                            }
                        },
                        onLongPress = {
                            if (isScreenLocked) {
                                showUnlockHint = true
                            } else if (!isLongPress2xActive) {
                                speedBeforeLongPress = playbackSpeed
                                isLongPress2xActive = true
                                exoPlayer.setPlaybackSpeed(2.0f)
                            }
                        },
                        onTap = {
                            if (isScreenLocked) {
                                showUnlockHint = !showUnlockHint
                            } else {
                                areControlsVisible = !areControlsVisible
                                registerInteraction()
                            }
                        },
                        onDoubleTap = { offset ->
                            if (isScreenLocked) {
                                showUnlockHint = true
                                return@detectTapGestures
                            }
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            registerInteraction()
                            when {
                                offset.x < width * 0.25f -> {
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    showHud(Icons.Default.FastRewind, "-10s")
                                }
                                offset.x > width * 0.75f -> {
                                    val newPos = minOf(duration.coerceAtLeast(0L), exoPlayer.currentPosition + 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    showHud(Icons.Default.FastForward, "+10s")
                                }
                                else -> {
                                    if (exoPlayer.isPlaying) {
                                        saveCurrentPosition()
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
                // Handler 3: Single-finger Seek / Brightness / Volume drag (only when unlocked & unzoomed)
                .pointerInput(isScreenLocked) {
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
                            if (!isScreenLocked && videoZoom <= 1.01f && !change.isConsumed) {
                                change.consume()
                                totalDragX += dragAmount.x
                                totalDragY += dragAmount.y

                                val deadZone = viewConfiguration.touchSlop * 2f
                                val screenWidth = size.width.toFloat().coerceAtLeast(1f)
                                val screenHeight = size.height.toFloat().coerceAtLeast(1f)

                                if (gestureMode == DragGestureMode.NONE) {
                                    if (abs(totalDragX) > deadZone && abs(totalDragX) > abs(totalDragY) * 1.2f) {
                                        gestureMode = DragGestureMode.HORIZONTAL_SEEK
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
                                        val seekRangeMs = 75_000f
                                        val deltaMs = ((totalDragX / screenWidth) * seekRangeMs).toLong()
                                        val maxDur = duration.coerceAtLeast(1L)
                                        val target = (gestureStartPositionMs + deltaMs).coerceIn(0L, maxDur)
                                        isScrubbing = true
                                        scrubPosition = target
                                        currentPosition = target
                                        exoPlayer.seekTo(target)
                                        val sign = if (deltaMs >= 0L) "+" else "-"
                                        val deltaSec = abs(deltaMs / 1000L)
                                        showHud(
                                            if (deltaMs >= 0L) Icons.Default.FastForward else Icons.Default.FastRewind,
                                            "${formatTime(target)} ($sign${deltaSec}s)"
                                        )
                                    }
                                    DragGestureMode.VERTICAL_BRIGHTNESS -> {
                                        activity?.let { act ->
                                            val damping = 0.6f
                                            val deltaFraction = (-totalDragY / screenHeight) * damping
                                            val newBrightness = (gestureStartBrightness + deltaFraction).coerceIn(0.02f, 1.0f)
                                            val lp = act.window.attributes
                                            lp.screenBrightness = newBrightness
                                            act.window.attributes = lp
                                            showHud(
                                                Icons.Default.BrightnessMedium,
                                                "Brightness ${(newBrightness * 100f).roundToInt()}%"
                                            )
                                        }
                                    }
                                    DragGestureMode.VERTICAL_VOLUME -> {
                                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        val damping = 0.6f
                                        val deltaFraction = (-totalDragY / screenHeight) * damping
                                        val newVolFloat = (gestureStartVolumeFloat + deltaFraction * maxVol).coerceIn(0f, maxVol.toFloat())
                                        val targetVolInt = newVolFloat.roundToInt().coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolInt, 0)
                                        if (targetVolInt > 0 && isMuted) {
                                            isMuted = false
                                            exoPlayer.volume = 1f
                                        }
                                        val pct = ((targetVolInt.toFloat() / maxVol.toFloat()) * 100f).roundToInt()
                                        showHud(Icons.Default.VolumeUp, "Volume $pct%")
                                    }
                                    DragGestureMode.NONE -> Unit
                                }
                            }
                        }
                    )
                }
        ) {
            // Video Playback Surface with Interactive Pinch-to-Zoom & Pan graphicsLayer
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
                    view.resizeMode = resizeModes[resizeIndex].first
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = videoZoom,
                        scaleY = videoZoom,
                        translationX = videoPanX,
                        translationY = videoPanY
                    )
            )

            // Center 400ms Play/Pause indicator on double-tap
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

            // FIX 7: Prominent Top-Center "2x Speed" Pill Indicator while Long-Pressing Screen
            AnimatedVisibility(
                visible = isLongPress2xActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 52.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.78f),
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .testTag("player_2x_hold_indicator")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = "2x Speed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "2x Speed",
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = overlayTextShadow
                            )
                        )
                    }
                }
            }

            // Crisp Translucent Pill HUD Overlay (top-center: Zoom %, Speed, Brightness, Volume, Seek, Loop)
            AnimatedVisibility(
                visible = hudText != null && !isLongPress2xActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 82.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        hudIcon?.let { icon ->
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = hudText ?: "",
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = overlayTextShadow
                            )
                        )
                    }
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

            // Screen Touch Lock Floating "Tap to Unlock" Pill
            AnimatedVisibility(
                visible = isScreenLocked && showUnlockHint,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
            ) {
                Surface(
                    onClick = {
                        isScreenLocked = false
                        showUnlockHint = false
                        areControlsVisible = true
                        registerInteraction()
                        showHud(Icons.Default.LockOpen, "Screen Unlocked")
                    },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                        .testTag("player_unlock_touch_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Unlock Screen",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tap to Unlock",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // FIX 1: TOP OVERLAY BAR
            // Left: Back Button + Video Title & Resolution Subtitle
            // Right: Interactive Zoom % Pill + Playback Speed Pill + Aspect Ratio Pill + Rotate Button
            // (NO Unhide or Delete buttons)
            AnimatedVisibility(
                visible = areControlsVisible && !isScreenLocked && !showMoreVideosSheet,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            saveCurrentPosition()
                            onBack()
                        },
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
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = activeVideoItem.fileName,
                            color = Color.White,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = overlayTextShadow
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        videoResolutionLabel?.let { res ->
                            Text(
                                text = res,
                                color = Color.White.copy(alpha = 0.78f),
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    shadow = overlayTextShadow
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // a) Interactive Zoom Badge / Button (100% .. 400%)
                        val isZoomed = videoZoom > 1.01f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isZoomed) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                    else Color.Black.copy(alpha = 0.55f)
                                )
                                .combinedClickable(
                                    onClick = cycleZoomStep,
                                    onLongClick = {
                                        registerInteraction()
                                        videoZoom = 1.0f
                                        videoPanX = 0f
                                        videoPanY = 0f
                                        showHud(Icons.Default.ZoomIn, "Zoom 100%")
                                    }
                                )
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                .testTag("player_zoom_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ZoomIn,
                                contentDescription = "Zoom",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${(videoZoom * 100f).roundToInt()}%",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // b) Playback Speed Button (0.25x .. 2.0x)
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (abs(playbackSpeed - 1.0f) > 0.01f) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                        } else {
                                            Color.Black.copy(alpha = 0.55f)
                                        }
                                    )
                                    .clickable {
                                        registerInteraction()
                                        showSpeedMenu = true
                                    }
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                                    .testTag("player_speed_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Playback Speed",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${formatSpeed(playbackSpeed)}x",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = {
                                    showSpeedMenu = false
                                    registerInteraction()
                                }
                            ) {
                                listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                                    val formatted = "${formatSpeed(speed)}x"
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = formatted,
                                                fontWeight = if (abs(playbackSpeed - speed) < 0.01f) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            registerInteraction()
                                            playbackSpeed = speed
                                            exoPlayer.setPlaybackSpeed(speed)
                                            showSpeedMenu = false
                                            showHud(Icons.Default.Speed, "Speed $formatted")
                                        }
                                    )
                                }
                            }
                        }

                        // c) Video Size / Aspect Ratio Button (Fit / Crop / Stretch)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable {
                                    registerInteraction()
                                    resizeIndex = (resizeIndex + 1) % resizeModes.size
                                    val nextMode = resizeModes[resizeIndex]
                                    playerViewRef?.resizeMode = nextMode.first
                                    showHud(Icons.Default.AspectRatio, nextMode.third)
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                                .testTag("player_aspect_ratio")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Video Size",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = resizeModes[resizeIndex].second,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // d) Rotate Screen Button
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { toggleOrientation() }
                                .testTag("toggle_orientation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate Screen",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            // FIX 2: 3-LAYER BOTTOM OVERLAY
            // Layer 1 (Above Slider): Left Timestamp Pill | Right Quick Fullscreen/Fit Button
            // Layer 2: Full-Width Ultra-Thin (2.5.dp) Seek Slider
            // Layer 3 (Below Slider): Left Action Pill Container | Right "More videos" Pill with Mini 16:9 Preview
            AnimatedVisibility(
                visible = areControlsVisible && !isScreenLocked && !showMoreVideosSheet,
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

                // Next video preview for the bottom-right "More videos" pill
                val nextPreviewVideo = remember(activeVideoItem.id, playlistVideos, currentVideoIndex) {
                    when {
                        hasNextVideo -> playlistVideos[currentVideoIndex + 1]
                        playlistVideos.size > 1 -> playlistVideos.first()
                        else -> activeVideoItem
                    }
                }
                val previewBlob = remember(nextPreviewVideo.id, nextPreviewVideo.encryptedRelativePath, vaultBlobsDir) {
                    File(vaultBlobsDir, nextPreviewVideo.encryptedRelativePath)
                }
                var miniPreviewThumb by remember(nextPreviewVideo.id) {
                    mutableStateOf<ImageBitmap?>(ThumbnailCacheManager.getCached(nextPreviewVideo.id))
                }
                LaunchedEffect(nextPreviewVideo.id, previewBlob) {
                    if (miniPreviewThumb == null) {
                        miniPreviewThumb = ThumbnailCacheManager.loadThumbnail(nextPreviewVideo, previewBlob, masterKey)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // LAYER 1 (Directly Above the Slider): Timestamp Pill on Left + Quick Fullscreen/Fit Toggle on Right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "${formatTime(activePos)} / ${formatTime(duration)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val isExpandedMode = resizeIndex != 0
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable {
                                    registerInteraction()
                                    resizeIndex = if (resizeIndex == 0) 1 else 0
                                    val nextMode = resizeModes[resizeIndex]
                                    playerViewRef?.resizeMode = nextMode.first
                                    showHud(Icons.Default.AspectRatio, nextMode.third)
                                }
                                .testTag("player_quick_fullscreen_toggle")
                        ) {
                            Icon(
                                imageVector = if (isExpandedMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isExpandedMode) "Fit to Screen" else "Fill Screen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // LAYER 2: Full-Width Ultra-Thin (2.5.dp) YouTube-Style Progress Slider
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

                    Spacer(modifier = Modifier.height(6.dp))

                    // LAYER 3 (Directly Below the Slider):
                    // Bottom-Left Action Pill Container & Bottom-Right "More videos" Pill with Mini Preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bottom-Left Pill Container: Prev, -10s, Play/Pause, +10s, Next, Loop, Mute, Lock
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (hasPrevVideo) {
                                        saveCurrentPosition()
                                        activeVideoItem = playlistVideos[currentVideoIndex - 1]
                                    } else {
                                        exoPlayer.seekTo(0L)
                                        currentPosition = 0L
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_previous_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Video",
                                    tint = if (hasPrevVideo) Color.White else Color.White.copy(alpha = 0.55f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    val newPos = maxOf(0L, exoPlayer.currentPosition - 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    showHud(Icons.Default.FastRewind, "-10s")
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_rewind_10s_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = "Rewind 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (exoPlayer.isPlaying) {
                                        saveCurrentPosition()
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
                                    .size(42.dp)
                                    .testTag("player_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    val newPos = minOf(duration.coerceAtLeast(0L), exoPlayer.currentPosition + 10_000L)
                                    exoPlayer.seekTo(newPos)
                                    currentPosition = newPos
                                    showHud(Icons.Default.FastForward, "+10s")
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_forward_10s_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = "Forward 10s",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    if (hasNextVideo) {
                                        saveCurrentPosition()
                                        activeVideoItem = playlistVideos[currentVideoIndex + 1]
                                    }
                                },
                                enabled = hasNextVideo,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_next_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Video",
                                    tint = if (hasNextVideo) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    val nextRepeat = !isRepeatOne
                                    isRepeatOne = nextRepeat
                                    exoPlayer.repeatMode = if (nextRepeat) {
                                        Player.REPEAT_MODE_ONE
                                    } else {
                                        Player.REPEAT_MODE_OFF
                                    }
                                    showHud(
                                        if (nextRepeat) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                        if (nextRepeat) "Loop On" else "Loop Off"
                                    )
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_loop_button")
                            ) {
                                Icon(
                                    imageVector = if (isRepeatOne) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                    contentDescription = if (isRepeatOne) "Loop On" else "Loop Off",
                                    tint = if (isRepeatOne) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    registerInteraction()
                                    val nextMuted = !isMuted
                                    isMuted = nextMuted
                                    exoPlayer.volume = if (nextMuted) 0f else 1f
                                    showHud(
                                        if (nextMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                        if (nextMuted) "Muted" else "Unmuted"
                                    )
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_mute_button")
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = if (isMuted) MaterialTheme.colorScheme.error else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isScreenLocked = true
                                    areControlsVisible = false
                                    showUnlockHint = true
                                    showHud(Icons.Default.Lock, "Screen Locked")
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("player_lock_touch_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Lock Screen Touches",
                                    tint = Color.White,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Bottom-Right "More videos" Pill with Mini 16:9 Thumbnail Preview
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .clickable {
                                    registerInteraction()
                                    showMoreVideosSheet = true
                                }
                                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                                .testTag("player_more_videos_button"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "More videos",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(22.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1A1D24)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (miniPreviewThumb != null) {
                                    Image(
                                        bitmap = miniPreviewThumb!!,
                                        contentDescription = nextPreviewVideo.fileName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // FIX 3: REDESIGNED "MORE VIDEOS" FULL/BOTTOM OVERLAY WITH LARGE 16:9 THUMBNAIL CARDS
            AnimatedVisibility(
                visible = showMoreVideosSheet,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.88f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showMoreVideosSheet = false
                            registerInteraction()
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { /* consume click inside drawer */ }
                            .navigationBarsPadding()
                            .padding(top = 16.dp, bottom = 18.dp)
                    ) {
                        // Header Row: "More videos" + Count Badge on Left, Close (X) on Right
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "More videos",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.White.copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${playlistVideos.size}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    showMoreVideosSheet = false
                                    registerInteraction()
                                },
                                modifier = Modifier.testTag("more_videos_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close More Videos",
                                    tint = Color.White
                                )
                            }
                        }

                        // Large Horizontal 16:9 Video Cards (Title & Size Directly BELOW Thumbnail)
                        LazyRow(
                            state = moreVideosListState,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
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

                                Column(
                                    modifier = Modifier
                                        .width(240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showMoreVideosSheet = false
                                            registerInteraction()
                                            if (videoItem.id != activeVideoItem.id) {
                                                saveCurrentPosition()
                                                activeVideoItem = videoItem
                                            }
                                        }
                                ) {
                                    // Top of Card: Large 240.dp x 135.dp (16:9) Rounded Thumbnail
                                    Box(
                                        modifier = Modifier
                                            .width(240.dp)
                                            .height(135.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF14171F))
                                            .then(
                                                if (isCurrentlyPlaying) {
                                                    Modifier.border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            ),
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
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.5f),
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }

                                        if (isCurrentlyPlaying) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(8.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "NOW PLAYING",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // Bottom-Right Duration Badge
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.Black.copy(alpha = 0.80f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = if (videoItem.durationMs > 0L) formatTime(videoItem.durationMs) else "VIDEO",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Bottom of Card: Title & File Size Directly BELOW Thumbnail
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = videoItem.fileName,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = formatVideoSize(videoItem.fileSize),
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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

private fun formatSpeed(speed: Float): String {
    val scaled = (speed * 100f).roundToInt()
    return when {
        scaled % 100 == 0 -> "${scaled / 100}.0"
        scaled % 10 == 0 -> "${scaled / 100}.${(scaled % 100) / 10}"
        else -> String.format(Locale.US, "%.2f", speed)
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
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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
