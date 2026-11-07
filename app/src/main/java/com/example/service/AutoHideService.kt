package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.crypto.VaultHeaderManager
import com.example.data.VaultStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class AutoHideService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val observers = mutableListOf<FileObserver>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val watchedPaths = intent?.getStringArrayExtra("WATCHED_PATHS") ?: emptyArray()
        val targetFolderId = intent?.getLongExtra("TARGET_FOLDER_ID", -1L)?.let { if (it == -1L) null else it }
        val isDecoy = intent?.getBooleanExtra("IS_DECOY", false) ?: false

        setupFileObservers(watchedPaths, targetFolderId, isDecoy)
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

        startForeground(1001, notification)
    }


    private fun setupFileObservers(paths: Array<String>, targetFolderId: Long?, isDecoy: Boolean) {
        stopObservers()
        val vaultStorage = VaultStorage(this)
        val headerManager = VaultHeaderManager(this)

        paths.forEach { pathStr ->
            val folder = File(pathStr)
            if (folder.exists() && folder.isDirectory) {
                // First sweep existing files
                sweepFolder(folder, vaultStorage, targetFolderId, isDecoy)

                // Start observing new files
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
                observers.add(observer)
            }
        }
    }

    private fun sweepFolder(folder: File, vaultStorage: VaultStorage, targetFolderId: Long?, isDecoy: Boolean) {
        serviceScope.launch {
            folder.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.startsWith(".")) {
                    importFileInternal(file, vaultStorage, targetFolderId, isDecoy)
                }
            }
        }
    }

    private fun processNewFile(file: File, vaultStorage: VaultStorage, targetFolderId: Long?, isDecoy: Boolean) {
        if (!file.exists() || !file.isFile || file.name.startsWith(".")) return
        serviceScope.launch {
            // Short delay to allow file write to finish
            kotlinx.coroutines.delay(500)
            if (file.exists()) {
                importFileInternal(file, vaultStorage, targetFolderId, isDecoy)
            }
        }
    }

    private suspend fun importFileInternal(
        file: File,
        vaultStorage: VaultStorage,
        targetFolderId: Long?,
        isDecoy: Boolean
    ) {
        try {
            // Retrieve master key if available or skip
            Log.i("AutoHideService", "Auto-hiding file: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("AutoHideService", "Failed to auto-hide file", e)
        }
    }

    private fun stopObservers() {
        observers.forEach { it.stopWatching() }
        observers.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        serviceScope.cancel()
    }
}
