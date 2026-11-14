package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.crypto.AutoHideKeyStoreHelper
import com.example.crypto.AuthResult
import com.example.crypto.VaultHeaderManager
import com.example.data.AutoLockMode
import com.example.data.ThumbnailCacheManager
import com.example.data.VaultPreferences
import com.example.data.VaultRepository
import com.example.data.VaultStorage
import com.example.data.db.FolderItem
import com.example.data.db.VaultDatabase
import com.example.data.db.VaultItem
import com.example.service.AutoHideScheduler
import com.example.service.AutoHideService
import com.example.ui.auth.AuthScreen
import com.example.ui.home.HomeScreen
import com.example.ui.home.NavTab
import com.example.ui.home.VaultProgressDialog
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

    // Point 2 (Step 1): Universal Picker Configuration.
    // Launches ACTION_OPEN_DOCUMENT with all-files MIME filter and explicit image/video MIME types,
    // EXTRA_ALLOW_MULTIPLE = true, and FLAG_GRANT_READ_URI_PERMISSION so JPG, JPEG, PNG, HEIC, WEBP,
    // BMP, GIF, and all video formats can be multi-selected reliably.
    private val pickFilesLauncher = registerForActivityResult(
        object : ActivityResultContracts.OpenMultipleDocuments() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
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
                val result = vaultStorage.exportEncryptedBackup(destUri, headerManager.headerFile)
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
                val result = vaultStorage.restoreEncryptedBackup(sourceUri, headerManager.headerFile)
                result.onSuccess { count ->
                    ThumbnailCacheManager.clearCache()
                    VaultRepository.stopAndReleaseActivePlayerAndClearSession()
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

    /**
     * Point 6: Dynamic BroadcastReceiver for `Intent.ACTION_SCREEN_OFF`.
     * Immediately stops/releases any active video player, clears all unlocked vault/folder states
     * in memory, and calls `finishAffinity()` + `finishAndRemoveTask()`.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                exitAndRemoveTaskOnScreenOff()
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

        // Point 13: Keep FLAG_SECURE silently active at all times in MainActivity.onCreate()
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        checkStoragePermissions()

        setContent {
            val themeMode by vaultPrefs.themeMode.collectAsState()
            val autoLockMode by vaultPrefs.autoLockMode.collectAsState()
            val bruteForceLockoutEnabled by vaultPrefs.bruteForceLockoutEnabled.collectAsState()
            val blockScreenshots by vaultPrefs.blockScreenshots.collectAsState()
            val defaultGridColumns by vaultPrefs.defaultGridColumns.collectAsState()
            val sortMode by vaultPrefs.sortMode.collectAsState()
            val showFileNamesOnGrid by vaultPrefs.showFileNamesOnGrid.collectAsState()
            val isAutoHideEnabled by vaultPrefs.autoHideEnabled.collectAsState()
            val watchedFolders by vaultPrefs.watchedFolders.collectAsState()
            val progressState by VaultRepository.progressState.collectAsState()

            // Point 19: Hoisted navigation tab, opened folder ID, and scroll states so pressing Back
            // from PhotoViewerScreen or VideoPlayerScreen returns directly to Folder X at the
            // user's exact previous scroll position—never ejecting them back out to the root Home Screen.
            var currentHomeTab by remember { mutableStateOf(NavTab.HOME) }
            var openedFolderId by remember { mutableStateOf<Long?>(null) }
            val homeGridState = rememberLazyGridState()
            val foldersGridState = rememberLazyGridState()
            val folderGridStates = remember { mutableStateMapOf<Long, LazyGridState>() }
            val activeFolderGridState = remember(openedFolderId) {
                val id = openedFolderId ?: -1L
                folderGridStates.getOrPut(id) { LazyGridState() }
            }

            // Reset folder/tab state when vault locks
            LaunchedEffect(currentScreen.value) {
                if (currentScreen.value is Screen.Auth) {
                    openedFolderId = null
                    currentHomeTab = NavTab.HOME
                    folderGridStates.clear()
                }
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

                    val rawItems by dao.getAllItems(isDecoy = isDecoyVault).collectAsState(initial = emptyList())
                    val folders by folderDao.getAllFolders(isDecoy = isDecoyVault).collectAsState(initial = emptyList())

                    // Point 1 (Step 3 & 4): Cross-verify against physical encrypted files on disk and deduplicate by vaultFilePath
                    val items = remember(rawItems, vaultStorage.vaultBlobsDir) {
                        val physicalNames = try {
                            vaultStorage.vaultBlobsDir.listFiles()
                                ?.asSequence()
                                ?.filter { it.isFile && it.name.endsWith(".bin") && it.length() > 0L }
                                ?.map { it.name }
                                ?.toSet() ?: emptySet()
                        } catch (_: Exception) {
                            emptySet()
                        }
                        rawItems
                            .filter { it.vaultFilePath.isNotBlank() && it.vaultFilePath in physicalNames }
                            .distinctBy { it.vaultFilePath }
                    }

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
                                            VaultRepository.isVaultUnlocked = true
                                            VaultRepository.unlockedFolderIds.clear()
                                            currentScreen.value = Screen.Home
                                            if (key != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    vaultStorage.reconcileAndRecoverCatalog(key)
                                                    if (isAutoHideEnabled) {
                                                        AutoHideKeyStoreHelper.sealMasterKeyForAutoHide(this@MainActivity, key)
                                                        if (watchedFolders.isNotEmpty()) {
                                                            vaultStorage.sweepWatchedFoldersNow(
                                                                watchedPaths = watchedFolders,
                                                                masterKey = key,
                                                                isDecoy = false
                                                            )
                                                        }
                                                    }
                                                    val currentRealItems = dao.getAllItemsByDecoySync(isDecoy = false)
                                                    ThumbnailCacheManager.regenerateDarkOrMissingVideoThumbnails(
                                                        items = currentRealItems,
                                                        vaultBlobsDir = vaultStorage.vaultBlobsDir,
                                                        vaultThumbsDir = vaultStorage.vaultThumbsDir,
                                                        masterKey = key
                                                    )
                                                }
                                            }
                                            if (isAutoHideEnabled) {
                                                AutoHideScheduler.scheduleAll(this@MainActivity)
                                                syncAutoHideService(true, watchedFolders)
                                            }
                                            true
                                        }
                                        AuthResult.SUCCESS_DECOY -> {
                                            masterKey = key
                                            isDecoyVault = true
                                            VaultRepository.isVaultUnlocked = true
                                            VaultRepository.unlockedFolderIds.clear()
                                            currentScreen.value = Screen.Home
                                            if (key != null) {
                                                scope.launch(Dispatchers.IO) {
                                                    vaultStorage.reconcileAndRecoverCatalog(key)
                                                    val currentDecoyItems = dao.getAllItemsByDecoySync(isDecoy = true)
                                                    ThumbnailCacheManager.regenerateDarkOrMissingVideoThumbnails(
                                                        items = currentDecoyItems,
                                                        vaultBlobsDir = vaultStorage.vaultBlobsDir,
                                                        vaultThumbsDir = vaultStorage.vaultThumbsDir,
                                                        masterKey = key
                                                    )
                                                }
                                            }
                                            true
                                        }
                                        AuthResult.LOCKED_OUT -> {
                                            Toast.makeText(
                                                this,
                                                "Vault Locked Out for 1 Hour",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            false
                                        }
                                        AuthResult.FAILED -> {
                                            false
                                        }
                                    }
                                },
                                onSetupCompleted = { pin, phrase ->
                                    val key = headerManager.createVaultHeader(pin, phrase)
                                    masterKey = key
                                    isDecoyVault = false
                                    VaultRepository.isVaultUnlocked = true
                                    VaultRepository.unlockedFolderIds.clear()
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
                                        VaultRepository.isVaultUnlocked = true
                                        VaultRepository.unlockedFolderIds.clear()
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
                                    sortMode = sortMode,
                                    onSortModeChanged = { mode -> vaultPrefs.setSortMode(mode) },
                                    currentTab = currentHomeTab,
                                    onTabChange = { tab -> currentHomeTab = tab },
                                    openedFolderId = openedFolderId,
                                    onOpenedFolderChange = { folderId -> openedFolderId = folderId },
                                    homeGridState = homeGridState,
                                    folderGridState = activeFolderGridState,
                                    foldersGridState = foldersGridState,
                                    showFileNames = showFileNamesOnGrid,
                                    progressState = progressState,
                                    onImportFilesSelected = { targetFolderId ->
                                        pendingImportFolderId = targetFolderId
                                        isLaunchingExternalPicker = true
                                        pickFilesLauncher.launch(arrayOf("image/*", "video/*"))
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
                                    onRenameFolder = { folder, newName ->
                                        scope.launch(Dispatchers.IO) {
                                            val updated = folder.copy(folderName = newName)
                                            folderDao.updateFolder(updated)
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Renamed folder to '$newName'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onUpdateFolderPin = { folder, isLocked, lockType, customPin ->
                                        scope.launch(Dispatchers.IO) {
                                            var saltHex: String? = null
                                            var slotHex: String? = null
                                            if (isLocked && lockType == "CUSTOM_PIN" && !customPin.isNullOrEmpty()) {
                                                val pair = headerManager.createFolderCustomPinPair(customPin)
                                                saltHex = pair.first
                                                slotHex = pair.second
                                            }
                                            val updated = folder.copy(
                                                isLocked = isLocked,
                                                lockType = lockType,
                                                customPinSaltHex = saltHex,
                                                customPinSlotAHex = slotHex
                                            )
                                            folderDao.updateFolder(updated)
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (isLocked) "Folder PIN updated for '${folder.folderName}'"
                                                    else "Removed PIN lock from '${folder.folderName}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onDeleteFolder = { folder ->
                                        scope.launch(Dispatchers.IO) {
                                            val folderItems = dao.getItemsInFolderSync(folder.id, isDecoyVault)
                                            vaultStorage.deleteBatchPermanentlyWithProgress(folderItems, activeKey)
                                            folderDao.deleteFolder(folder)
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                if (openedFolderId == folder.id) {
                                                    openedFolderId = null
                                                }
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Deleted folder '${folder.folderName}'",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onVerifyFolderAccess = { folder, inputPin ->
                                        headerManager.verifyContextualPin(
                                            folder = folder,
                                            inputPin = inputPin,
                                            isDecoyMode = isDecoyVault
                                        )
                                    },
                                    onVerifyRecoveryPhrase = { phrase ->
                                        headerManager.authenticateRecoveryPhrase(phrase).first
                                    },
                                    onVerifyMasterPin = { inputPin ->
                                        headerManager.verifyActiveVaultPin(inputPin, isDecoyVault)
                                    },
                                    onItemClick = { item, displayedItems ->
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
                                            val deletedCount = vaultStorage.deleteBatchPermanentlyWithProgress(selectedItems, activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Deleted $deletedCount item(s) permanently",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onUnhideSelectedItems = { selectedItems ->
                                        scope.launch(Dispatchers.IO) {
                                            val (restoredCount, destFolder) = vaultStorage.unhideBatchWithDestination(selectedItems, activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Restored $restoredCount item(s) to $destFolder",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    },
                                    onToggleFavoriteSelected = { selectedItems ->
                                        // Point 17: Smart Multi-Selection Favorite Toggle:
                                        // - If ANY selected item is NOT favorite -> mark ALL selected items as Favorite (true).
                                        // - ONLY if 100% of selected items are already Favorite -> un-favorite all (false).
                                        val targetFavoriteState = selectedItems.any { !it.isFavorite }
                                        scope.launch(Dispatchers.IO) {
                                            selectedItems.forEach { item ->
                                                if (item.isFavorite != targetFavoriteState) {
                                                    val updated = item.copy(isFavorite = targetFavoriteState)
                                                    dao.updateItem(updated)
                                                    vaultStorage.writeSidecarMetadata(updated, activeKey)
                                                }
                                            }
                                            vaultStorage.syncEncryptedCatalog(activeKey)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (targetFavoriteState) "Marked ${selectedItems.size} item(s) as Favorite"
                                                    else "Removed ${selectedItems.size} item(s) from Favorites",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onMoveSelectedItemsToFolder = { selectedItems, targetFolderId ->
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.moveItemsToFolderWithProgress(
                                                selectedItems = selectedItems,
                                                targetFolderId = targetFolderId,
                                                masterKey = activeKey
                                            )
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
                                        currentScreen.value = Screen.Settings
                                    },
                                    onColumnCountChanged = { cols -> vaultPrefs.setDefaultGridColumns(cols) },
                                    usedStorageBytes = items.sumOf { it.fileSize }
                                )
                            }
                        }

                        is Screen.VideoPlayer -> {
                            val blobFile = File(vaultStorage.vaultBlobsDir, screen.item.encryptedRelativePath)
                            val baseIv = fromHex(screen.item.baseIvHex)
                            val lockedIds = remember(folders) {
                                folders.filter { it.isLocked }.map { it.id }.toSet()
                            }
                            val playlistVideos = remember(items, openedFolderId, screen.item.folderId, lockedIds) {
                                val contextFolderId = openedFolderId ?: screen.item.folderId
                                val allVideos = items.filter { it.mimeType.startsWith("video") }
                                val scoped = if (contextFolderId != null) {
                                    allVideos.filter { it.folderId == contextFolderId }
                                } else {
                                    allVideos.filter { it.folderId == null || it.folderId !in lockedIds }
                                }
                                scoped.ifEmpty { listOf(screen.item) }
                            }
                            masterKey?.let { key ->
                                VideoPlayerScreen(
                                    vaultItem = screen.item,
                                    blobFile = blobFile,
                                    baseIv = baseIv,
                                    masterKey = key,
                                    vaultBlobsDir = vaultStorage.vaultBlobsDir,
                                    playlistVideos = playlistVideos,
                                    folders = folders,
                                    onVerifyContextualPin = { folderId, inputPin ->
                                        val targetFolder = folderId?.let { id -> folders.find { it.id == id } }
                                        headerManager.verifyContextualPin(
                                            folder = targetFolder,
                                            inputPin = inputPin,
                                            isDecoyMode = isDecoyVault
                                        )
                                    },
                                    onUnhide = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            val destFolder = vaultStorage.unhideFileWithDestination(item, key) ?: "/Pictures/Restored_Vault"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Restored to $destFolder",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                currentScreen.value = Screen.Home
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
                                                currentScreen.value = Screen.Home
                                            }
                                        }
                                    },
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
                                    folders = folders,
                                    onVerifyContextualPin = { folderId, inputPin ->
                                        val targetFolder = folderId?.let { id -> folders.find { it.id == id } }
                                        headerManager.verifyContextualPin(
                                            folder = targetFolder,
                                            inputPin = inputPin,
                                            isDecoyMode = isDecoyVault
                                        )
                                    },
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
                                            val destFolder = vaultStorage.unhideFileWithDestination(item, key) ?: "/Pictures/Restored_Vault"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Restored to $destFolder",
                                                    Toast.LENGTH_LONG
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
                            SettingsScreen(
                                onBack = { currentScreen.value = Screen.Home },
                                vaultStorage = vaultStorage,
                                isDecoyMode = isDecoyVault,
                                onVerifyCurrentPin = { inputPin ->
                                    headerManager.verifyActiveVaultPin(inputPin, isDecoyVault)
                                },
                                onChangeMasterPin = { oldPin, newPin ->
                                    val key = masterKey
                                    if (key != null && headerManager.verifyActiveVaultPin(oldPin, isDecoyVault)) {
                                        val ok = if (isDecoyVault) {
                                            headerManager.updateDecoyPinWithCurrentKey(key, newPin)
                                        } else {
                                            headerManager.resetPinWithMasterKey(key, newPin)
                                        }
                                        if (ok) {
                                            Toast.makeText(this, "Vault PIN updated", Toast.LENGTH_SHORT).show()
                                        }
                                        ok
                                    } else false
                                },
                                onUpdateRecoveryPhrase = { newPhrase ->
                                    val key = masterKey
                                    if (key != null && !isDecoyVault) {
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
                                    val activeKey = masterKey
                                    if (enabled && activeKey != null && !isDecoyVault) {
                                        AutoHideKeyStoreHelper.sealMasterKeyForAutoHide(this@MainActivity, activeKey)
                                        AutoHideScheduler.scheduleAll(this@MainActivity)
                                        syncAutoHideService(true, vaultPrefs.watchedFolders.value)
                                        requestBatteryOptimizationExemptionIfNeeded()
                                    } else {
                                        AutoHideKeyStoreHelper.clearAutoHideKey(this@MainActivity)
                                        AutoHideScheduler.cancelAll(this@MainActivity)
                                        syncAutoHideService(false, vaultPrefs.watchedFolders.value)
                                    }
                                },
                                watchedFolders = watchedFolders,
                                onToggleWatchedFolder = { path ->
                                    vaultPrefs.toggleWatchedFolder(path)
                                    if (isAutoHideEnabled) {
                                        AutoHideScheduler.scheduleAll(this@MainActivity)
                                        syncAutoHideService(true, vaultPrefs.watchedFolders.value)
                                    }
                                },
                                onAddCustomWatchedFolder = { path ->
                                    vaultPrefs.addCustomWatchedFolder(path)
                                    if (isAutoHideEnabled) {
                                        AutoHideScheduler.scheduleAll(this@MainActivity)
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
                                onRequestBatteryOptimizationExemption = {
                                    requestBatteryOptimizationExemptionIfNeeded()
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

                    // Show Unified Determinate Progress Dialog on Settings/Auth/PhotoViewer screens as well
                    if (progressState.isVisible && currentScreen.value !is Screen.Home) {
                        VaultProgressDialog(progressState = progressState)
                    }
                }
            }
        }
    }

    private fun syncAutoHideService(enabled: Boolean, paths: Set<String>) {
        if (enabled && paths.isNotEmpty() && masterKey != null) {
            val intent = Intent(this, AutoHideService::class.java).apply {
                putExtra("WATCHED_PATHS", paths.toTypedArray())
                putExtra("IS_DECOY", isDecoyVault)
                putExtra("MASTER_KEY_BYTES", masterKey?.encoded)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (_: Throwable) {
            }
        } else {
            try {
                stopService(Intent(this, AutoHideService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    isLaunchingExternalPicker = true
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Throwable) {
                isLaunchingExternalPicker = false
            }
        }
    }

    private fun importUris(uris: List<Uri>, targetFolderId: Long?) {
        val key = masterKey ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val successCount = vaultStorage.importBatchUris(
                uris = uris,
                targetFolderId = targetFolderId,
                masterKey = key,
                isDecoy = isDecoyVault
            )
            withContext(Dispatchers.Main) {
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
            val successCount = vaultStorage.importBatchDirectFiles(
                paths = paths,
                targetFolderId = targetFolderId,
                masterKey = key,
                isDecoy = isDecoyVault
            )
            withContext(Dispatchers.Main) {
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
        VaultRepository.stopAndReleaseActivePlayerAndClearSession()
        masterKey = null
        isDecoyVault = false
        currentScreen.value = Screen.Auth
    }

    /**
     * Point 6: Instant Complete App Exit on Power Button / Screen Off.
     * Stops and releases any active video player, clears all unlocked vault/folder states in memory,
     * and executes `finishAffinity()` followed by `finishAndRemoveTask()` so the app completely
     * closes and disappears from Android's Recent Apps (Overview).
     */
    private fun exitAndRemoveTaskOnScreenOff() {
        VaultRepository.stopAndReleaseActivePlayerAndClearSession()
        masterKey = null
        isDecoyVault = false
        currentScreen.value = Screen.Auth
        try {
            finishAffinity()
        } catch (_: Throwable) {
        }
        try {
            finishAndRemoveTask()
        } catch (_: Throwable) {
        }
    }

    override fun onPause() {
        super.onPause()
        if (isChangingConfigurations) {
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            exitAndRemoveTaskOnScreenOff()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            exitAndRemoveTaskOnScreenOff()
            return
        }
        if (isLaunchingExternalPicker) {
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
                // Handled by screenOffReceiver and !powerManager.isInteractive check
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        return try {
            val clean = if (hex.length % 2 == 0) hex else "0$hex"
            val len = clean.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
                i += 2
            }
            data
        } catch (_: Throwable) {
            ByteArray(16)
        }
    }
}
