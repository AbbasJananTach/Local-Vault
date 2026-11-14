package com.example.data

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Unified Task-Specific Progress Dialog State (Point 4):
 * Supports determinate progress (0% to 100%) and item/byte subText counters.
 */
data class VaultProgressState(
    val isVisible: Boolean = false,
    val title: String = "",
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val percentage: Int = 0,
    val subText: String = ""
)

/**
 * Central Singleton VaultRepository (Points 1, 4, 6):
 * 1. Global Import & Scan Mutex Lock (`vaultMutex`):
 *    Serializes all manual file import loops, folder move loops, Auto-Hide background scans,
 *    and physical file index reconciliations on `Dispatchers.IO`. Any background scan waits
 *    until manual import finishes, preventing concurrent duplicate indexing.
 * 2. Throttled Live Progress State (`progressState`):
 *    Emits task-specific progress updates throttled to once every 100ms (plus 0% and 100%).
 * 3. Instant Screen-Off Session & Player Cleanup (Point 6):
 *    Tracks `isVaultUnlocked`, `unlockedFolderIds`, and any active `ExoPlayer` instance so that
 *    `ACTION_SCREEN_OFF` or `!powerManager.isInteractive` immediately stops/releases video playback
 *    and clears all unlocked states in memory before `finishAffinity()` + `finishAndRemoveTask()`.
 */
object VaultRepository {

    val vaultMutex = Mutex()

    @Volatile
    var isVaultUnlocked: Boolean = false

    val unlockedFolderIds: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf<Long>())

    @Volatile
    private var activeExoPlayer: ExoPlayer? = null

    private val _progressState = MutableStateFlow(VaultProgressState())
    val progressState: StateFlow<VaultProgressState> = _progressState.asStateFlow()

    @Volatile
    private var lastProgressEmitElapsedMs: Long = 0L

    /**
     * Executes any vault import, folder move, background scan, or index reconciliation block
     * inside the global `vaultMutex` on `Dispatchers.IO`.
     */
    suspend fun <T> withVaultLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        vaultMutex.withLock {
            block()
        }
    }

    /**
     * Updates the unified determinate progress state, throttling intermediate UI state emissions
     * to once every 100ms to prevent main-thread recomposition pressure during fast streaming.
     */
    fun updateProgress(
        title: String,
        currentItem: Int,
        totalItems: Int,
        percentage: Int,
        subText: String,
        forceEmit: Boolean = false
    ) {
        val clampedPct = percentage.coerceIn(0, 100)
        val now = SystemClock.elapsedRealtime()
        if (forceEmit || clampedPct == 0 || clampedPct >= 100 || (now - lastProgressEmitElapsedMs) >= 100L) {
            lastProgressEmitElapsedMs = now
            _progressState.value = VaultProgressState(
                isVisible = true,
                title = title,
                currentItem = currentItem,
                totalItems = totalItems,
                percentage = clampedPct,
                subText = subText
            )
        }
    }

    fun hideProgress() {
        lastProgressEmitElapsedMs = 0L
        _progressState.value = VaultProgressState(isVisible = false)
    }

    fun registerActivePlayer(player: ExoPlayer) {
        activeExoPlayer = player
    }

    fun unregisterActivePlayer(player: ExoPlayer) {
        if (activeExoPlayer === player) {
            activeExoPlayer = null
        }
    }

    /**
     * Point 6: Immediately stops and releases any active ExoPlayer and clears all in-memory
     * unlocked vault and folder states on Power Button / Screen Off.
     */
    fun stopAndReleaseActivePlayerAndClearSession() {
        isVaultUnlocked = false
        unlockedFolderIds.clear()
        val player = activeExoPlayer
        activeExoPlayer = null
        if (player != null) {
            try {
                player.stop()
            } catch (_: Throwable) {
            }
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
        ThumbnailCacheManager.clearCache()
    }
}
