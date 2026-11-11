package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.crypto.AuthResult
import com.example.crypto.VaultHeaderManager
import com.example.data.AutoLockMode
import com.example.data.ThumbnailCacheManager
import com.example.data.VaultPreferences
import com.example.data.VaultStorage
import com.example.data.db.FolderItem
import com.example.data.db.VaultDatabase
import com.example.data.db.VaultItem
import com.example.service.AutoHideService
import com.example.ui.auth.AuthScreen
import com.example.ui.home.HomeScreen
import com.example.ui.player.VideoPlayerScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewer.PhotoViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKey

sealed class Screen {
    object Auth : Screen()
    object Home : Screen()
    data class VideoPlayer(val item: VaultItem) : Screen()
    data class PhotoViewer(val initialItemId: Long, val photoIds: List<Long>) : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var headerManager: VaultHeaderManager
    private lateinit var vaultStorage: VaultStorage
    private lateinit var database: VaultDatabase
    private lateinit var vaultPrefs: VaultPreferences

    private var masterKey: SecretKey? = null
    private var isDecoyVault by mutableStateOf(false)
    private var dbRefreshTick by mutableIntStateOf(0)

    private val currentScreen = mutableStateOf<Screen>(Screen.Auth)

    // Prevents locking when launching system file picker, backup picker, or permission screen
    private var isLaunchingExternalPicker = false
    private var backgroundedElapsedTime = 0L
    private var pendingImportFolderId: Long? = null

    private val isImporting = mutableStateOf(false)
    private val importProgressText = mutableStateOf("")

    // File Picker for Importing Photos & Videos
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        isLaunchingExternalPicker = false
        backgroundedElapsedTime = 0L
        if (uris.isNotEmpty() && masterKey != null) {
            importUris(uris, pendingImportFolderId)
        }
        pendingImportFolderId = null
    }

    // Create Document Launcher for Exporting Encrypted .lvb Backup
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { destUri ->
        isLaunchingExternalPicker = false
        backgroundedElapsedTime = 0L
        if (destUri != null) {
            lifecycleScope.launch {
                isImporting.value = true
                importProgressText.value = "Creating encrypted .lvb backup..."
                val result = vaultStorage.exportEncryptedBackup(destUri, headerManager.headerFile)
                isImporting.value = false
                result.onSuccess { count ->
                    Toast.makeText(
                        this@MainActivity,
                        "Encrypted backup saved ($count media items)",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        this@MainActivity,
                        "Backup failed: ${err.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Open Document Launcher for Restoring Encrypted .lvb Backup
    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        isLaunchingExternalPicker = false
        backgroundedElapsedTime = 0L
        if (sourceUri != null) {
            lifecycleScope.launch {
                isImporting.value = true
                importProgressText.value = "Restoring encrypted .lvb backup..."
                val result = vaultStorage.restoreEncryptedBackup(sourceUri, headerManager.headerFile)
                isImporting.value = false
                result.onSuccess { count ->
                    // Architectural Fix: Restoring an .lvb backup replaces vault.header on disk
                    // with the backup's wrapped Master Vault Key (MVK). Clear any stale in-RAM
                    // masterKey and return to Screen.Auth so the user unlocks with the backup's PIN.
                    ThumbnailCacheManager.clearCache()
                    masterKey = null
                    isDecoyVault = false
                    database = VaultDatabase.getInstance(this@MainActivity)
                    dbRefreshTick++
                    currentScreen.value = Screen.Auth
                    Toast.makeText(
                        this@MainActivity,
                        "Restored $count items! Enter your backup's Master PIN to unlock.",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { err ->
                    Toast.makeText(
                        this@MainActivity,
                        "Restore failed: ${err.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                lockVaultNow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        headerManager = VaultHeaderManager(this)
        vaultStorage = VaultStorage(this)
        database = VaultDatabase.getInstance(this)
        vaultPrefs = VaultPreferences(this)

        applyFlagSecure(vaultPrefs.blockScreenshots.value)

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        checkStoragePermissions()

        setContent {
            val themeMode by vaultPrefs.themeMode.collectAsState()
            val autoLockMode by vaultPrefs.autoLockMode.collectAsState()
            val bruteForceLockoutEnabled by vaultPrefs.bruteForceLockoutEnabled.collectAsState()
            val blockScreenshots by vaultPrefs.blockScreenshots.collectAsState()
            val defaultGridColumns by vaultPrefs.defaultGridColumns.collectAsState()
            val showFileNamesOnGrid by vaultPrefs.showFileNamesOnGrid.collectAsState()
            val isAutoHideEnabled by vaultPrefs.autoHideEnabled.collectAsState()
            val watchedFolders by vaultPrefs.watchedFolders.collectAsState()

            LaunchedEffect(blockScreenshots) {
                applyFlagSecure(blockScreenshots)
            }

            MyApplicationTheme(themeMode = themeMode, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scope = rememberCoroutineScope()
                    val activeDb = remember(dbRefreshTick) {
                        VaultDatabase.getInstance(this@MainActivity).also { database = it }
                    }
                    val dao = remember(activeDb) { activeDb.vaultDao() }
                    val folderDao = remember(activeDb) { activeDb.folderDao() }

                    val items by dao.getAllItems(isDecoy = isDecoyVault).collectAsState(initial = emptyList())
                    val folders by folderDao.getAllFolders(isDecoy = isDecoyVault).collectAsState(initial = emptyList())

                    // Trigger recomposition for lockout status & security status
                    var authAttemptTick by remember { mutableIntStateOf(0) }
                    val lockoutStatus = remember(authAttemptTick, bruteForceLockoutEnabled, dbRefreshTick) {
                        headerManager.getLockoutStatus(bruteForceLockoutEnabled)
                    }
                    var hasDecoyState by remember(authAttemptTick, dbRefreshTick) {
                        mutableStateOf(headerManager.hasDecoyPin())
                    }
                    var hasRecoveryState by remember(authAttemptTick, dbRefreshTick) {
                        mutableStateOf(headerManager.hasRecoveryPhrase())
                    }

                    when (val screen = currentScreen.value) {
                        is Screen.Auth -> {
                            AuthScreen(
                                isInitialized = remember(authAttemptTick, dbRefreshTick) {
                                    headerManager.isVaultInitialized()
                                },
                                isLockedOut = lockoutStatus.isLockedOut,
                                lockoutRemainingSeconds = lockoutStatus.remainingSeconds,
                                onPinSubmit = { pin ->
                                    val (authResult, key) = headerManager.authenticatePin(
                                        inputPin = pin,
                                        enforceLockout = bruteForceLockoutEnabled
                                    )
                                    authAttemptTick++
                                    when (authResult) {
                                        AuthResult.SUCCESS_REAL -> {
                                            masterKey = key
                                            isDecoyVault = false
                                            currentScreen.value = Screen.Home
                                            if (key != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    vaultStorage.reconcileAndRecoverCatalog(key)
                                                    if (isAutoHideEnabled && watchedFolders.isNotEmpty()) {
                                                        vaultStorage.sweepWatchedFoldersNow(
                                                            watchedPaths = watchedFolders,
                                                            masterKey = key,
                                                            isDecoy = false
                                                        )
                                                    }
                                                }
                                            }
                                            if (isAutoHideEnabled) {
                                                syncAutoHideService(true, watchedFolders)
                                            }
                                        }
                                        AuthResult.SUCCESS_DECOY -> {
                                            masterKey = key
                                            isDecoyVault = true
                                            currentScreen.value = Screen.Home
                                            if (key != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    vaultStorage.reconcileAndRecoverCatalog(key)
                                                }
                                            }
                                        }
                                        AuthResult.LOCKED_OUT -> {
                                            Toast.makeText(
                                                this,
                                                "Vault Locked Out for 1 Hour",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        AuthResult.FAILED -> {
                                            Toast.makeText(
                                                this,
                                                "Incorrect PIN",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onSetupCompleted = { pin, phrase ->
                                    val key = headerManager.createVaultHeader(pin, phrase)
                                    masterKey = key
                                    isDecoyVault = false
                                    authAttemptTick++
                                    currentScreen.value = Screen.Home
                                    scope.launch(Dispatchers.IO) {
                                        vaultStorage.reconcileAndRecoverCatalog(key)
                                    }
                                },
                                onRecoveryPhraseSubmit = { phrase ->
                                    val (success, key) = headerManager.authenticateRecoveryPhrase(phrase)
                                    authAttemptTick++
                                    if (success && key != null) {
                                        masterKey = key
                                        isDecoyVault = false
                                        currentScreen.value = Screen.Home
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.reconcileAndRecoverCatalog(key)
                                        }
                                        true
                                    } else false
                                },
                                onResetPinConfirmed = { newPin ->
                                    masterKey?.let { headerManager.resetPinWithMasterKey(it, newPin) }
                                },
                                onRestoreBackupClick = {
                                    isLaunchingExternalPicker = true
                                    restoreBackupLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }

                        is Screen.Home -> {
                            val activeKey = masterKey
                            if (activeKey == null) {
                                currentScreen.value = Screen.Auth
                            } else {
                                HomeScreen(
                                    items = items,
                                    folders = folders,
                                    vaultBlobsDir = vaultStorage.vaultBlobsDir,
                                    vaultStorage = vaultStorage,
                                    masterKey = activeKey,
                                    isDecoyMode = isDecoyVault,
                                    defaultGridColumns = defaultGridColumns,
                                    showFileNames = showFileNamesOnGrid,
                                    isImporting = isImporting.value,
                                    importProgressText = importProgressText.value,
                                    onImportFilesSelected = { targetFolderId ->
                                        pendingImportFolderId = targetFolderId
                                        isLaunchingExternalPicker = true
                                        pickFilesLauncher.launch("*/*")
                                    },
                                    onImportDirectFiles = { paths, targetFolderId ->
                                        importDirectFiles(paths, targetFolderId)
                                    },
                                    onCreateFolder = { name, isLocked, lockType, customPin ->
                                        scope.launch(Dispatchers.IO) {
                                            var saltHex: String? = null
                                            var slotHex: String? = null
                                            if (isLocked && lockType == "CUSTOM_PIN" && !customPin.isNullOrEmpty()) {
                                                val pair = headerManager.createFolderCustomPinPair(customPin)
                                                saltHex = pair.first
                                                slotHex = pair.second
                                            }
                                            folderDao.insertFolder(
                                                FolderItem(
                                                    folderName = name,
                                                    isLocked = isLocked,
                                                    lockType = lockType,
                                                    customPinSaltHex = saltHex,
                                                    customPinSlotAHex = slotHex,
                                                    isDecoy = isDecoyVault
                                                )
                                            )
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Folder '$name' created",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onDeleteFolder = { folder ->
                                        scope.launch(Dispatchers.IO) {
                                            val folderItems = dao.getItemsInFolderSync(folder.id, isDecoyVault)
                                            folderItems.forEach { vaultStorage.deletePermanently(it) }
                                            folderDao.deleteFolder(folder)
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Deleted folder '${folder.folderName}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onVerifyFolderAccess = { folder, inputPin ->
                                        if (folder.lockType == "CUSTOM_PIN") {
                                            headerManager.verifyFolderCustomPin(
                                                inputPin = inputPin,
                                                saltHex = folder.customPinSaltHex,
                                                slotHex = folder.customPinSlotAHex
                                            )
                                        } else {
                                            headerManager.verifyRealPinOnly(inputPin)
                                        }
                                    },
                                    onVerifyRecoveryPhrase = { phrase ->
                                        headerManager.authenticateRecoveryPhrase(phrase).first
                                    },
                                    onVerifyMasterPin = { inputPin ->
                                        headerManager.verifyRealPinOnly(inputPin)
                                    },
                                    onItemClick = { item, displayedItems ->
                                        // Pass ALL displayedItems (both photos and videos) so the user can swipe
                                        // left/right across their entire gallery/folder seamlessly, or play videos directly.
                                        if (item.mimeType.startsWith("video")) {
                                            currentScreen.value = Screen.VideoPlayer(item)
                                        } else {
                                            val mediaIds = displayedItems
                                                .map { it.id }
                                                .ifEmpty { listOf(item.id) }
                                            currentScreen.value = Screen.PhotoViewer(
                                                initialItemId = item.id,
                                                photoIds = mediaIds
                                            )
                                        }
                                    },
                                    onDeleteSelectedItems = { selectedItems ->
                                        scope.launch(Dispatchers.IO) {
                                            selectedItems.forEach { vaultStorage.deletePermanently(it) }
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Permanently deleted ${selectedItems.size} item(s)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onUnhideSelectedItems = { selectedItems ->
                                        scope.launch(Dispatchers.IO) {
                                            var restoredCount = 0
                                            selectedItems.forEach {
                                                if (vaultStorage.unhideFile(it, activeKey)) {
                                                    restoredCount++
                                                }
                                            }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Unhidden $restoredCount item(s) to public storage",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onToggleFavoriteSelected = { selectedItems ->
                                        scope.launch(Dispatchers.IO) {
                                            selectedItems.forEach {
                                                val updated = it.copy(isFavorite = !it.isFavorite)
                                                dao.updateItem(updated)
                                                vaultStorage.writeSidecarMetadata(updated, activeKey)
                                            }
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                        }
                                    },
                                    onMoveSelectedItemsToFolder = { selectedItems, targetFolderId ->
                                        scope.launch(Dispatchers.IO) {
                                            dao.moveItemsToFolder(
                                                ids = selectedItems.map { it.id },
                                                targetFolderId = targetFolderId
                                            )
                                            selectedItems.forEach {
                                                vaultStorage.writeSidecarMetadata(
                                                    it.copy(folderId = targetFolderId),
                                                    activeKey
                                                )
                                            }
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Moved ${selectedItems.size} item(s)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onOpenSettings = {
                                        if (!isDecoyVault) {
                                            currentScreen.value = Screen.Settings
                                        }
                                    },
                                    onColumnCountChanged = { cols -> vaultPrefs.setDefaultGridColumns(cols) },
                                    usedStorageBytes = items.sumOf { it.fileSize }
                                )
                            }
                        }

                        is Screen.VideoPlayer -> {
                            val blobFile = File(vaultStorage.vaultBlobsDir, screen.item.encryptedRelativePath)
                            val baseIv = fromHex(screen.item.baseIvHex)
                            masterKey?.let { key ->
                                VideoPlayerScreen(
                                    vaultItem = screen.item,
                                    blobFile = blobFile,
                                    baseIv = baseIv,
                                    masterKey = key,
                                    onBack = { currentScreen.value = Screen.Home }
                                )
                            }
                        }

                        is Screen.PhotoViewer -> {
                            masterKey?.let { key ->
                                val itemMap = remember(items) { items.associateBy { it.id } }
                                val orderedPhotos = remember(screen.photoIds, itemMap) {
                                    screen.photoIds.mapNotNull { itemMap[it] }
                                }
                                PhotoViewerScreen(
                                    photoItems = orderedPhotos,
                                    initialItemId = screen.initialItemId,
                                    vaultBlobsDir = vaultStorage.vaultBlobsDir,
                                    masterKey = key,
                                    onBack = { currentScreen.value = Screen.Home },
                                    onPlayVideo = { videoItem ->
                                        currentScreen.value = Screen.VideoPlayer(videoItem)
                                    },
                                    onToggleFavorite = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            dao.updateItem(item)
                                            vaultStorage.writeSidecarMetadata(item, key)
                                            vaultStorage.syncEncryptedCatalog(key)
                                        }
                                    },
                                    onUnhide = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.unhideFile(item, key)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Restored '${item.fileName}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                if (orderedPhotos.size <= 1) {
                                                    currentScreen.value = Screen.Home
                                                }
                                            }
                                        }
                                    },
                                    onDelete = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.deletePermanently(item, key)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Deleted '${item.fileName}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                if (orderedPhotos.size <= 1) {
                                                    currentScreen.value = Screen.Home
                                                }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        is Screen.Settings -> {
                            if (isDecoyVault) {
                                currentScreen.value = Screen.Home
                            } else {
                                SettingsScreen(
                                    onBack = { currentScreen.value = Screen.Home },
                                    vaultStorage = vaultStorage,
                                    onChangeMasterPin = { oldPin, newPin ->
                                        val key = masterKey
                                        if (key != null && headerManager.verifyRealPinOnly(oldPin)) {
                                            val ok = headerManager.resetPinWithMasterKey(key, newPin)
                                            if (ok) {
                                                Toast.makeText(this, "Master PIN updated", Toast.LENGTH_SHORT).show()
                                            }
                                            ok
                                        } else false
                                    },
                                    onUpdateRecoveryPhrase = { newPhrase ->
                                        val key = masterKey
                                        if (key != null) {
                                            val ok = headerManager.updateRecoveryPhrase(key, newPhrase)
                                            if (ok) {
                                                hasRecoveryState = true
                                                Toast.makeText(this, "Recovery Phrase updated", Toast.LENGTH_SHORT).show()
                                            }
                                            ok
                                        } else false
                                    },
                                    hasRecoveryPhrase = hasRecoveryState,
                                    onSetupFakePin = { fakePin ->
                                        val ok = headerManager.setupDecoyPin(fakePin)
                                        if (ok) {
                                            hasDecoyState = true
                                            Toast.makeText(this, "Decoy PIN Enabled", Toast.LENGTH_SHORT).show()
                                        }
                                        ok
                                    },
                                    onRemoveFakePin = {
                                        headerManager.removeDecoyPin()
                                        hasDecoyState = false
                                        Toast.makeText(this, "Decoy PIN Disabled", Toast.LENGTH_SHORT).show()
                                    },
                                    hasDecoyPin = hasDecoyState,
                                    bruteForceLockoutEnabled = bruteForceLockoutEnabled,
                                    onToggleBruteForceLockout = { enabled ->
                                        vaultPrefs.setBruteForceLockoutEnabled(enabled)
                                    },
                                    blockScreenshots = blockScreenshots,
                                    onToggleBlockScreenshots = { enabled ->
                                        vaultPrefs.setBlockScreenshots(enabled)
                                        applyFlagSecure(enabled)
                                    },
                                    autoLockMode = autoLockMode,
                                    onAutoLockModeChanged = { mode ->
                                        vaultPrefs.setAutoLockMode(mode)
                                    },
                                    themeMode = themeMode,
                                    onThemeModeChanged = { mode ->
                                        vaultPrefs.setThemeMode(mode)
                                    },
                                    defaultGridColumns = defaultGridColumns,
                                    onDefaultGridColumnsChanged = { cols ->
                                        vaultPrefs.setDefaultGridColumns(cols)
                                    },
                                    showFileNamesOnGrid = showFileNamesOnGrid,
                                    onToggleShowFileNames = { show ->
                                        vaultPrefs.setShowFileNamesOnGrid(show)
                                    },
                                    isAutoHideEnabled = isAutoHideEnabled,
                                    onToggleAutoHide = { enabled ->
                                        vaultPrefs.setAutoHideEnabled(enabled)
                                        syncAutoHideService(enabled, watchedFolders)
                                    },
                                    watchedFolders = watchedFolders,
                                    onToggleWatchedFolder = { path ->
                                        vaultPrefs.toggleWatchedFolder(path)
                                        if (isAutoHideEnabled) {
                                            syncAutoHideService(true, vaultPrefs.watchedFolders.value)
                                        }
                                    },
                                    onAddCustomWatchedFolder = { path ->
                                        vaultPrefs.addCustomWatchedFolder(path)
                                        if (isAutoHideEnabled) {
                                            syncAutoHideService(true, vaultPrefs.watchedFolders.value)
                                        }
                                    },
                                    onSweepWatchedFoldersNow = {
                                        val key = masterKey ?: return@SettingsScreen
                                        scope.launch(Dispatchers.IO) {
                                            val count = vaultStorage.sweepWatchedFoldersNow(
                                                watchedPaths = vaultPrefs.watchedFolders.value,
                                                masterKey = key,
                                                isDecoy = isDecoyVault
                                            )
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (count > 0) "Swept & encrypted $count file(s) from watched folders"
                                                    else "Watched folders are clean (0 new files found)",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onCreateBackup = {
                                        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                        isLaunchingExternalPicker = true
                                        createBackupLauncher.launch("VaultBackup_$dateStr.lvb")
                                    },
                                    onRestoreBackup = {
                                        isLaunchingExternalPicker = true
                                        restoreBackupLauncher.launch(arrayOf("*/*"))
                                    },
                                    totalPhotosCount = items.count { !it.mimeType.startsWith("video") },
                                    totalVideosCount = items.count { it.mimeType.startsWith("video") },
                                    totalFoldersCount = folders.size,
                                    usedVaultBytes = items.sumOf { it.fileSize }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun applyFlagSecure(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun syncAutoHideService(enabled: Boolean, paths: Set<String>) {
        if (enabled && paths.isNotEmpty() && masterKey != null) {
            val intent = Intent(this, AutoHideService::class.java).apply {
                putExtra("WATCHED_PATHS", paths.toTypedArray())
                putExtra("IS_DECOY", isDecoyVault)
                putExtra("MASTER_KEY_BYTES", masterKey?.encoded)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(Intent(this, AutoHideService::class.java))
        }
    }

    private fun importUris(uris: List<Uri>, targetFolderId: Long?) {
        val key = masterKey ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isImporting.value = true
                importProgressText.value = "Encrypting ${uris.size} file(s)..."
            }

            var successCount = 0
            uris.forEachIndexed { index, uri ->
                withContext(Dispatchers.Main) {
                    importProgressText.value = "Encrypting ${index + 1} of ${uris.size}..."
                }
                val imported = vaultStorage.importFile(
                    uri = uri,
                    targetFolderId = targetFolderId,
                    masterKey = key,
                    isDecoy = isDecoyVault
                )
                if (imported != null) {
                    successCount++
                }
            }

            withContext(Dispatchers.Main) {
                isImporting.value = false
                importProgressText.value = ""
                if (successCount > 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "Encrypted & imported $successCount media file(s)",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to import selected media. Check storage permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun importDirectFiles(paths: List<String>, targetFolderId: Long?) {
        val key = masterKey ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isImporting.value = true
                importProgressText.value = "Encrypting & wiping ${paths.size} file(s)..."
            }

            var successCount = 0
            paths.forEachIndexed { index, path ->
                withContext(Dispatchers.Main) {
                    importProgressText.value = "Encrypting ${index + 1} of ${paths.size}..."
                }
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val uri = Uri.fromFile(file)
                    val imported = vaultStorage.importFile(
                        uri = uri,
                        originalPathOverride = path,
                        targetFolderId = targetFolderId,
                        masterKey = key,
                        isDecoy = isDecoyVault
                    )
                    if (imported != null) {
                        successCount++
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isImporting.value = false
                importProgressText.value = ""
                if (successCount > 0) {
                    ThumbnailCacheManager.clearCache()
                    Toast.makeText(
                        this@MainActivity,
                        "Imported & deleted $successCount file(s) from device",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to import selected files. Check storage permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                isLaunchingExternalPicker = true
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    private fun lockVaultNow() {
        masterKey = null
        ThumbnailCacheManager.clearCache()
        currentScreen.value = Screen.Auth
    }

    override fun onStop() {
        super.onStop()
        if (isLaunchingExternalPicker || isChangingConfigurations) {
            // Do NOT lock when launching file picker, permission dialog, or rotating screen
            return
        }
        val mode = vaultPrefs.autoLockMode.value
        when (mode) {
            AutoLockMode.IMMEDIATE -> {
                lockVaultNow()
            }
            AutoLockMode.AFTER_30S, AutoLockMode.AFTER_1M -> {
                backgroundedElapsedTime = SystemClock.elapsedRealtime()
            }
            AutoLockMode.SCREEN_OFF_ONLY -> {
                // Handled by screenOffReceiver
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check persistent external database path after returning from permission settings
        database = VaultDatabase.getInstance(this)
        dbRefreshTick++

        if (isLaunchingExternalPicker) {
            isLaunchingExternalPicker = false
            backgroundedElapsedTime = 0L
            return
        }
        if (backgroundedElapsedTime > 0L && masterKey != null) {
            val elapsed = SystemClock.elapsedRealtime() - backgroundedElapsedTime
            val timeout = vaultPrefs.autoLockMode.value.timeoutMs
            if (timeout >= 0L && elapsed >= timeout) {
                lockVaultNow()
            }
            backgroundedElapsedTime = 0L
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
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
}
