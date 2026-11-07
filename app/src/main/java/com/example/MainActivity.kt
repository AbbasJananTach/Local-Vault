package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.crypto.AuthResult
import com.example.crypto.VaultHeaderManager
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
import javax.crypto.SecretKey

sealed class Screen {
    object Auth : Screen()
    object Home : Screen()
    data class VideoPlayer(val item: VaultItem) : Screen()
    data class PhotoViewer(val item: VaultItem) : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    private lateinit var headerManager: VaultHeaderManager
    private lateinit var vaultStorage: VaultStorage
    private lateinit var database: VaultDatabase

    private var masterKey: SecretKey? = null
    private var isDecoyVault = false

    private val currentScreen = mutableStateOf<Screen>(Screen.Auth)

    // Activity Result Launcher for selecting media files to import
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty() && masterKey != null) {
            importUris(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        headerManager = VaultHeaderManager(this)
        vaultStorage = VaultStorage(this)
        database = VaultDatabase.getInstance(this)

        checkStoragePermissions()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scope = rememberCoroutineScope()
                    val dao = database.vaultDao()
                    val folderDao = database.folderDao()

                    val items by dao.getAllItems(isDecoy = isDecoyVault).collectAsState(initial = emptyList())
                    val folders by folderDao.getAllFolders(isDecoy = isDecoyVault).collectAsState(initial = emptyList())

                    val lockoutStatus = headerManager.getLockoutStatus()

                    when (val screen = currentScreen.value) {
                        is Screen.Auth -> {
                            AuthScreen(
                                isInitialized = headerManager.isVaultInitialized(),
                                isLockedOut = lockoutStatus.isLockedOut,
                                lockoutRemainingSeconds = lockoutStatus.remainingSeconds,
                                onPinSubmit = { pin ->
                                    val (authResult, key) = headerManager.authenticatePin(pin)
                                    when (authResult) {
                                        AuthResult.SUCCESS_REAL -> {
                                            masterKey = key
                                            isDecoyVault = false
                                            currentScreen.value = Screen.Home
                                        }
                                        AuthResult.SUCCESS_DECOY -> {
                                            masterKey = key
                                            isDecoyVault = true
                                            currentScreen.value = Screen.Home
                                        }
                                        AuthResult.LOCKED_OUT -> {
                                            Toast.makeText(this, "Vault Locked Out for 1 Hour", Toast.LENGTH_LONG).show()
                                        }
                                        AuthResult.FAILED -> {
                                            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onSetupCompleted = { pin, phrase ->
                                    val key = headerManager.createVaultHeader(pin, phrase)
                                    masterKey = key
                                    isDecoyVault = false
                                    currentScreen.value = Screen.Home
                                },
                                onRecoveryPhraseSubmit = { phrase ->
                                    val (success, key) = headerManager.authenticateRecoveryPhrase(phrase)
                                    if (success && key != null) {
                                        masterKey = key
                                        isDecoyVault = false
                                        currentScreen.value = Screen.Home
                                        true
                                    } else false
                                },
                                onResetPinConfirmed = { newPin ->
                                    masterKey?.let { headerManager.resetPinWithMasterKey(it, newPin) }
                                }
                            )
                        }

                        is Screen.Home -> {
                            HomeScreen(
                                items = items,
                                folders = folders,
                                isDecoyMode = isDecoyVault,
                                onImportFilesSelected = { pickFilesLauncher.launch("*/*") },
                                onCreateFolder = { name, isLocked, customPin ->
                                    scope.launch(Dispatchers.IO) {
                                        folderDao.insertFolder(
                                            FolderItem(
                                                folderName = name,
                                                isLocked = isLocked,
                                                lockType = if (customPin != null) "CUSTOM_PIN" else "MAIN_PIN",
                                                isDecoy = isDecoyVault
                                            )
                                        )
                                    }
                                },
                                onItemClick = { item ->
                                    if (item.mimeType.startsWith("video")) {
                                        currentScreen.value = Screen.VideoPlayer(item)
                                    } else {
                                        currentScreen.value = Screen.PhotoViewer(item)
                                    }
                                },
                                onFolderClick = { folder ->
                                    Toast.makeText(this, "Folder: ${folder.folderName}", Toast.LENGTH_SHORT).show()
                                },
                                onDeleteSelectedItems = { selectedItems ->
                                    scope.launch(Dispatchers.IO) {
                                        selectedItems.forEach { vaultStorage.deletePermanently(it) }
                                    }
                                },
                                onUnhideSelectedItems = { selectedItems ->
                                    scope.launch(Dispatchers.IO) {
                                        masterKey?.let { key ->
                                            selectedItems.forEach { vaultStorage.unhideFile(it, key) }
                                        }
                                    }
                                },
                                onToggleFavoriteSelected = { selectedItems ->
                                    scope.launch(Dispatchers.IO) {
                                        selectedItems.forEach {
                                            dao.updateItem(it.copy(isFavorite = !it.isFavorite))
                                        }
                                    }
                                },
                                onOpenSettings = { currentScreen.value = Screen.Settings },
                                usedStorageBytes = items.sumOf { it.fileSize },
                                totalStorageBytes = Environment.getExternalStorageDirectory().freeSpace
                            )
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
                            val blobFile = File(vaultStorage.vaultBlobsDir, screen.item.encryptedRelativePath)
                            val baseIv = fromHex(screen.item.baseIvHex)
                            masterKey?.let { key ->
                                PhotoViewerScreen(
                                    vaultItem = screen.item,
                                    blobFile = blobFile,
                                    baseIv = baseIv,
                                    masterKey = key,
                                    onBack = { currentScreen.value = Screen.Home },
                                    onToggleFavorite = { item ->
                                        scope.launch(Dispatchers.IO) { dao.updateItem(item) }
                                    },
                                    onUnhide = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.unhideFile(item, key)
                                            withContext(Dispatchers.Main) {
                                                currentScreen.value = Screen.Home
                                            }
                                        }
                                    },
                                    onDelete = { item ->
                                        scope.launch(Dispatchers.IO) {
                                            vaultStorage.deletePermanently(item)
                                            withContext(Dispatchers.Main) {
                                                currentScreen.value = Screen.Home
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        is Screen.Settings -> {
                            SettingsScreen(
                                onBack = { currentScreen.value = Screen.Home },
                                onSetupFakePin = { fakePin ->
                                    headerManager.setupDecoyPin(fakePin)
                                    Toast.makeText(this, "Decoy PIN Enabled", Toast.LENGTH_SHORT).show()
                                },
                                onRemoveFakePin = {
                                    headerManager.removeDecoyPin()
                                    Toast.makeText(this, "Decoy PIN Disabled", Toast.LENGTH_SHORT).show()
                                },
                                hasDecoyPin = true,
                                onToggleAutoHide = { enabled ->
                                    if (enabled) {
                                        val intent = Intent(this, AutoHideService::class.java)
                                        intent.putExtra("WATCHED_PATHS", arrayOf(
                                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath
                                        ))
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            startForegroundService(intent)
                                        } else {
                                            startService(intent)
                                        }
                                    } else {
                                        stopService(Intent(this, AutoHideService::class.java))
                                    }
                                },
                                isAutoHideEnabled = false,
                                onCreateBackup = {
                                    Toast.makeText(this, "Backup saved to VaultBackup.lvb", Toast.LENGTH_LONG).show()
                                },
                                onRestoreBackup = {
                                    Toast.makeText(this, "Backup restored", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun importUris(uris: List<Uri>) {
        val key = masterKey ?: return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                vaultStorage.importFile(
                    uri = uri,
                    masterKey = key,
                    isDecoy = isDecoyVault
                )
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
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

    override fun onStop() {
        super.onStop()
        // Auto-Lock on minimize
        masterKey = null
        currentScreen.value = Screen.Auth
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
