package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.crypto.AutoHideKeyStoreHelper
import com.example.data.VaultPreferences
import com.example.data.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Fix 3: Thread-Safe Non-Cancelling Auto-Hide Foreground Service:
 * 1. Completely eliminates `sweepFolder(...)` so `onStartCommand` never launches duplicate
 *    per-file imports concurrently alongside `sweepWatchedFoldersNow`.
 * 2. Maintains `@Volatile private var currentWatchedPaths: Set<String>` and an `AtomicBoolean`
 *    `isSweepInProgress`.
 * 3. `stopObservers()` ONLY stops/clears `FileObserver` instances and NEVER cancels an active
 *    sweep coroutine when `MainActivity` refreshes watched paths.
 * 4. A single long-lived coroutine loop in `serviceScope` runs `vaultStorage.sweepWatchedFoldersNow`
 *    immediately upon start and every 10 seconds thereafter inside `NonCancellable` protection.
 */
class AutoHideService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val observers = mutableListOf<FileObserver>()
    private var periodicSweepJob: Job? = null

    @Volatile
    private var activeMasterKey: SecretKey? = null

    @Volatile
    private var currentWatchedPaths: Set<String> = emptySet()

    @Volatile
    private var currentTargetFolderId: Long? = null

    @Volatile
    private var currentIsDecoy: Boolean = false

    private val isSweepInProgress = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        ensurePeriodicSweepLoopRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()
        val prefs = VaultPreferences(this)
        val watchedPaths = intent?.getStringArrayExtra("WATCHED_PATHS")
            ?: prefs.watchedFolders.value.toTypedArray()
        val targetFolderId = intent?.getLongExtra("TARGET_FOLDER_ID", -1L)?.let { if (it == -1L) null else it }
        val isDecoy = intent?.getBooleanExtra("IS_DECOY", false) ?: false
        val keyBytes = intent?.getByteArrayExtra("MASTER_KEY_BYTES")
        if (keyBytes != null && keyBytes.size == 32) {
            activeMasterKey = SecretKeySpec(keyBytes, "AES")
        } else if (activeMasterKey == null) {
            activeMasterKey = AutoHideKeyStoreHelper.unsealMasterKeyForAutoHide(this)
        }

        currentWatchedPaths = watchedPaths.toSet()
        currentTargetFolderId = targetFolderId
        currentIsDecoy = isDecoy

        setupFileObservers(watchedPaths, targetFolderId, isDecoy)
        ensurePeriodicSweepLoopRunning()
        triggerImmediateNonCancellingSweep()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "local_vault_autohide_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Gallery Sync Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Monitors watched device folders for auto-hiding media"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gallery Sync Active")
            .setContentText("Local Vault folder protection running in background")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1001, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun setupFileObservers(paths: Array<String>, targetFolderId: Long?, isDecoy: Boolean) {
        stopObservers()
        val vaultStorage = VaultStorage(this)

        paths.forEach { pathStr ->
            val folder = File(pathStr)
            if (folder.exists() && folder.isDirectory) {
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(folder, CREATE or CLOSE_WRITE or MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null) {
                                val file = File(folder, path)
                                processNewFile(file, vaultStorage, targetFolderId, isDecoy)
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(folder.absolutePath, CREATE or CLOSE_WRITE or MOVED_TO) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null) {
                                val file = File(folder, path)
                                processNewFile(file, vaultStorage, targetFolderId, isDecoy)
                            }
                        }
                    }
                }
                observer.startWatching()
                synchronized(observers) {
                    observers.add(observer)
                }
            }
        }
    }

    private fun ensurePeriodicSweepLoopRunning() {
        val existing = periodicSweepJob
        if (existing != null && existing.isActive) return

        val vaultStorage = VaultStorage(this)
        periodicSweepJob = serviceScope.launch {
            while (isActive) {
                runSingleSweepSafely(vaultStorage)
                delay(10_000L)
            }
        }
    }

    private fun triggerImmediateNonCancellingSweep() {
        val vaultStorage = VaultStorage(this)
        serviceScope.launch {
            runSingleSweepSafely(vaultStorage)
        }
    }

    private suspend fun runSingleSweepSafely(vaultStorage: VaultStorage) {
        val paths = currentWatchedPaths
        if (paths.isEmpty()) return
        val key = activeMasterKey ?: AutoHideKeyStoreHelper.unsealMasterKeyForAutoHide(this)?.also {
            activeMasterKey = it
        } ?: return

        if (!isSweepInProgress.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                vaultStorage.sweepWatchedFoldersNow(
                    watchedPaths = paths,
                    masterKey = key,
                    isDecoy = currentIsDecoy
                )
            }
        } catch (_: Throwable) {
        } finally {
            isSweepInProgress.set(false)
        }
    }

    private fun processNewFile(file: File, vaultStorage: VaultStorage, targetFolderId: Long?, isDecoy: Boolean) {
        if (!file.exists() || !file.isFile || file.name.startsWith(".") || !isMediaFile(file.name)) return
        serviceScope.launch {
            delay(700)
            if (file.exists() && file.length() > 0L && !isSweepInProgress.get()) {
                val key = activeMasterKey ?: AutoHideKeyStoreHelper.unsealMasterKeyForAutoHide(this@AutoHideService)?.also {
                    activeMasterKey = it
                } ?: return@launch
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        vaultStorage.importFile(
                            uri = Uri.fromFile(file),
                            originalPathOverride = file.absolutePath,
                            targetFolderId = targetFolderId,
                            masterKey = key,
                            isDecoy = isDecoy
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isMediaFile(name: String): Boolean {
        return VaultStorage.isSupportedMediaFileName(name)
    }

    /**
     * Fix 3: Stops ONLY the FileObserver instances without cancelling `periodicSweepJob` or any
     * in-progress `sweepWatchedFoldersNow` import batch!
     */
    private fun stopObservers() {
        synchronized(observers) {
            observers.forEach {
                try {
                    it.stopWatching()
                } catch (_: Throwable) {
                }
            }
            observers.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        periodicSweepJob?.cancel()
        periodicSweepJob = null
        serviceScope.cancel()
    }
}
