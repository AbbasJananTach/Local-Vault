package com.example.ui.settings

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AutoLockMode
import com.example.data.DeviceFolderSummary
import com.example.data.VaultPreferences
import com.example.data.VaultStorage
import com.example.ui.components.CustomPinKeypadDialog
import com.example.ui.components.CustomPinSetupDialog
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.VaultThemeMode
import com.example.ui.theme.VioletPrimary
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vaultStorage: VaultStorage? = null,
    isDecoyMode: Boolean = false,
    onVerifyCurrentPin: (String) -> Boolean = { true },
    // Security callbacks
    onChangeMasterPin: (oldPin: String, newPin: String) -> Boolean,
    onUpdateRecoveryPhrase: (newPhrase: String) -> Boolean,
    hasRecoveryPhrase: Boolean,
    onSetupFakePin: (fakePin: String) -> Boolean,
    onRemoveFakePin: () -> Unit,
    hasDecoyPin: Boolean,
    bruteForceLockoutEnabled: Boolean,
    onToggleBruteForceLockout: (Boolean) -> Unit,
    blockScreenshots: Boolean = true,
    onToggleBlockScreenshots: (Boolean) -> Unit = {},
    // Auto-Lock
    autoLockMode: AutoLockMode,
    onAutoLockModeChanged: (AutoLockMode) -> Unit,
    // Display & Theme
    themeMode: VaultThemeMode,
    onThemeModeChanged: (VaultThemeMode) -> Unit,
    defaultGridColumns: Int,
    onDefaultGridColumnsChanged: (Int) -> Unit,
    showFileNamesOnGrid: Boolean,
    onToggleShowFileNames: (Boolean) -> Unit,
    // Auto-Hide Background Service
    isAutoHideEnabled: Boolean,
    onToggleAutoHide: (Boolean) -> Unit,
    watchedFolders: Set<String>,
    onToggleWatchedFolder: (String) -> Unit,
    onAddCustomWatchedFolder: (String) -> Unit,
    onSweepWatchedFoldersNow: () -> Unit = {},
    onRequestBatteryOptimizationExemption: () -> Unit = {},
    // Backup & Storage
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    totalPhotosCount: Int,
    totalVideosCount: Int,
    totalFoldersCount: Int,
    usedVaultBytes: Long
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    val isBatteryUnrestricted = remember(context, isAutoHideEnabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    var showVerifyCurrentPinForChange by remember { mutableStateOf(false) }
    var verifiedOldPinForChange by remember { mutableStateOf<String?>(null) }
    var showRecoveryPhraseDialog by remember { mutableStateOf(false) }
    var showFakePinDialog by remember { mutableStateOf(false) }
    var showVisualFolderPickerSheet by remember { mutableStateOf(false) }

    val presetFolders = remember { VaultPreferences.getPresetWatchableFolders() }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = { Text("Vault Control Center", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. GALLERY & DISPLAY CONTROLS (THEME, COLUMNS, FILENAMES)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Appearance & Gallery Display",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "Brand Theme Palette",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VaultThemeMode.entries.forEach { mode ->
                            val isSelected = themeMode == mode
                            val swatchColor = when (mode) {
                                VaultThemeMode.MIDNIGHT_VIOLET -> VioletPrimary
                                VaultThemeMode.CYBER_EMERALD -> EmeraldPrimary
                                VaultThemeMode.CRIMSON_STEALTH -> CrimsonPrimary
                            }
                            Card(
                                onClick = { onThemeModeChanged(mode) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, swatchColor, RoundedCornerShape(12.dp))
                                        else Modifier
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(swatchColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = mode.displayName.replace(" ", "\n"),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        "Thumbnail Grid Size",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            4 to "Small",
                            3 to "Medium",
                            2 to "Large"
                        ).forEach { (cols, label) ->
                            val isSelected = defaultGridColumns.coerceIn(2, 4) == cols
                            FilterChip(
                                selected = isSelected,
                                onClick = { onDefaultGridColumnsChanged(cols) },
                                label = { Text("$label ($cols)") },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.GridView, null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show File Names on Grid", fontWeight = FontWeight.Medium)
                            Text(
                                "Display file name labels over gallery thumbnails",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showFileNamesOnGrid,
                            onCheckedChange = onToggleShowFileNames
                        )
                    }
                }
            }

            // 2. AUTO-LOCK BEHAVIOR SECTION
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LockClock, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Auto-Lock Behavior",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Control when Local Vault locks after minimizing or turning off the screen. File pickers automatically bypass locking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    AutoLockMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onAutoLockModeChanged(mode) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = autoLockMode == mode,
                                onClick = { onAutoLockModeChanged(mode) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(mode.label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 3. SECURITY & VAULT LOCK CONTROLS (Point 12 & Point 20: Clean everyday English, hide decoy options in Decoy Mode)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Secure Vault Lock",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Screenshots are blocked for your privacy",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showVerifyCurrentPinForChange = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_master_pin_button")
                        ) {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change PIN")
                        }

                        if (!isDecoyMode) {
                            OutlinedButton(
                                onClick = { showRecoveryPhraseDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("update_recovery_phrase_button")
                            ) {
                                Text(if (hasRecoveryPhrase) "Update Phrase" else "Set Phrase")
                            }
                        }
                    }

                    if (!isDecoyMode) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Decoy PIN Setup (Hidden in Decoy Mode so decoy space is completely undetectable)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Decoy PIN Setup", fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (hasDecoyPin) MaterialTheme.colorScheme.secondaryContainer
                                                else MaterialTheme.colorScheme.surfaceContainerHigh
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (hasDecoyPin) "ACTIVE" else "OFF",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (hasDecoyPin) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    "Secondary PIN that opens a separate space titled 'Local Vault'",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { showFakePinDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("setup_fake_pin")
                            ) {
                                Text(if (hasDecoyPin) "Change Decoy PIN" else "Enable Decoy PIN")
                            }
                            if (hasDecoyPin) {
                                OutlinedButton(
                                    onClick = onRemoveFakePin,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("remove_fake_pin"),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Disable Decoy")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))

                        // 1-Hour Lockout toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("1-Hour Security Lockout", fontWeight = FontWeight.Medium)
                                Text(
                                    "Lock keypad for 60 minutes after 3 wrong PIN attempts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = bruteForceLockoutEnabled,
                                onCheckedChange = onToggleBruteForceLockout
                            )
                        }
                    }
                }
            }

            if (!isDecoyMode) {
                // 4. BACKGROUND AUTO-HIDE FOLDER WATCHER (CLEAN SELECTED LIST + FULL-SCREEN FOLDER SEARCH)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.VisibilityOff, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Background Auto-Hide Watcher",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        if (isAutoHideEnabled) "Active • Watching ${watchedFolders.size} folder(s)"
                                        else "Stopped",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isAutoHideEnabled) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isAutoHideEnabled,
                                onCheckedChange = onToggleAutoHide,
                                modifier = Modifier.testTag("autohide_switch")
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Selected device folders are monitored automatically. Any photo or video saved into these folders is encrypted into Local Vault and wiped from public gallery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val presetLabelMap = remember(presetFolders) {
                            presetFolders.associate { (label, path) -> path to label }
                        }
                        val sortedWatched = remember(watchedFolders) {
                            watchedFolders.sortedBy { File(it).name.lowercase() }
                        }

                        if (sortedWatched.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "No folders selected for Auto-Hide. Tap 'Add More Folders' below to search and select folders.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            sortedWatched.forEach { folderPath ->
                                val folderTitle = presetLabelMap[folderPath]
                                    ?: File(folderPath).name.ifBlank { "Watched Folder" }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onToggleWatchedFolder(folderPath) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = true,
                                        onCheckedChange = { onToggleWatchedFolder(folderPath) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(folderTitle, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            folderPath,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onToggleWatchedFolder(folderPath) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove Watched Folder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showVisualFolderPickerSheet = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browse_watched_folders_button")
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add More Folders")
                            }

                            if (watchedFolders.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = onSweepWatchedFoldersNow,
                                    modifier = Modifier.testTag("sweep_watched_now_button")
                                ) {
                                    Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Sweep Now")
                                }
                            }
                        }

                        if (!isBatteryUnrestricted) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = onRequestBatteryOptimizationExemption,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("battery_optimization_button")
                            ) {
                                Text(
                                    text = "Allow Unrestricted Battery (Recommended for 24/7 Auto-Hide)",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // 5. OFFLINE ENCRYPTED BACKUP & LOCAL VAULT SUMMARY
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            if (isDecoyMode) "Vault Storage Summary" else "Offline Backup (.lvb) & Vault Summary",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    if (!isDecoyMode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Save a complete backup of your Vault folders and media into a single .lvb file, or safely restore a backup without deleting existing files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onCreateBackup,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("create_backup_button")
                            ) {
                                Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export .lvb Backup")
                            }
                            Button(
                                onClick = onRestoreBackup,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restore_backup_button")
                            ) {
                                Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore .lvb Backup")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Column {
                        Text(
                            text = "$totalPhotosCount Photos • $totalVideosCount Videos • $totalFoldersCount Folders",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Total Vault Size: ${formatBytes(usedVaultBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Point 7: Step 1 of Change Vault PIN — Verify Current PIN via In-App 3x4 Keypad
    if (showVerifyCurrentPinForChange) {
        var latestVerifiedPin by remember { mutableStateOf("") }
        CustomPinKeypadDialog(
            title = "Enter Current Vault PIN",
            subtitle = "Verify your current PIN and tap Unlock (✓)",
            doneButtonLabel = "Unlock",
            onVerifyPin = { inputPin ->
                val ok = onVerifyCurrentPin(inputPin)
                if (ok) {
                    latestVerifiedPin = inputPin
                }
                ok
            },
            onSuccess = {
                showVerifyCurrentPinForChange = false
                verifiedOldPinForChange = latestVerifiedPin
            },
            onDismiss = { showVerifyCurrentPinForChange = false }
        )
    }

    // Point 7: Step 2 of Change Vault PIN — Enter & Confirm New PIN via In-App 3x4 Keypad
    if (verifiedOldPinForChange != null) {
        val oldPin = verifiedOldPinForChange!!
        CustomPinSetupDialog(
            title = "New Vault PIN",
            step1Subtitle = "Enter a new 4 to 10 digit PIN, then tap Done (✓)",
            step2Subtitle = "Re-enter the new PIN to confirm, then tap Done (✓)",
            onPinConfirmed = { newPin ->
                val ok = onChangeMasterPin(oldPin, newPin)
                if (ok) {
                    verifiedOldPinForChange = null
                }
                ok
            },
            mismatchOrInvalidError = "New PIN cannot conflict with another PIN.",
            onDismiss = { verifiedOldPinForChange = null }
        )
    }

    // Update Recovery Phrase Dialog
    if (showRecoveryPhraseDialog) {
        var newPhrase by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showRecoveryPhraseDialog = false },
            title = { Text("Update Custom Recovery Phrase", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Your recovery phrase lets you unlock your Secure Vault Lock if you ever forget your PIN.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newPhrase,
                        onValueChange = { newPhrase = it },
                        label = { Text("New Memorable Recovery Phrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (newPhrase.trim().isEmpty()) {
                        errorMsg = "Please enter a non-empty phrase"
                        return@Button
                    }
                    if (onUpdateRecoveryPhrase(newPhrase.trim())) {
                        showRecoveryPhraseDialog = false
                    } else {
                        errorMsg = "Failed to update recovery phrase"
                    }
                }) {
                    Text("Save Phrase")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryPhraseDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Point 7: Decoy / Fake PIN Setup Dialog using In-App 3x4 Custom Numeric Keypad
    if (showFakePinDialog && !isDecoyMode) {
        CustomPinSetupDialog(
            title = "Decoy PIN",
            step1Subtitle = "Enter a 4 to 10 digit Decoy PIN (different from your Master PIN), then tap Done (✓)",
            step2Subtitle = "Re-enter the Decoy PIN to confirm, then tap Done (✓)",
            onPinConfirmed = { fakePin ->
                val ok = onSetupFakePin(fakePin)
                if (ok) {
                    showFakePinDialog = false
                }
                ok
            },
            mismatchOrInvalidError = "Decoy PIN must be different from your Real Master PIN",
            onDismiss = { showFakePinDialog = false }
        )
    }

    // Full-Screen Device Folder Search & Selection Dialog for Auto-Hide (Point 5)
    if (showVisualFolderPickerSheet) {
        FullScreenFolderSearchDialog(
            vaultStorage = vaultStorage,
            watchedFolders = watchedFolders,
            onToggleWatchedFolder = onToggleWatchedFolder,
            onAddCustomWatchedFolder = onAddCustomWatchedFolder,
            onDismiss = { showVisualFolderPickerSheet = false }
        )
    }
}

/**
 * Point 5: Full-Screen Folder Search Dialog for Auto-Hide Settings.
 * - Features a top Search Bar with a one-tap `X` clear button.
 * - Traverses `/storage/emulated/0` (excluding `/Android/data` and `/Android/obb`) on `Dispatchers.IO`
 *   via `File.listFiles()` so ALL folders on the phone—including completely empty folders—appear
 *   and can be searched by name or path and selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenFolderSearchDialog(
    vaultStorage: VaultStorage?,
    watchedFolders: Set<String>,
    onToggleWatchedFolder: (String) -> Unit,
    onAddCustomWatchedFolder: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var allDeviceFolders by remember { mutableStateOf<List<DeviceFolderSummary>>(emptyList()) }

    LaunchedEffect(vaultStorage) {
        isLoading = true
        if (vaultStorage != null) {
            allDeviceFolders = vaultStorage.scanAllDeviceFoldersForSearch()
        }
        isLoading = false
    }

    val filteredFolders = remember(allDeviceFolders, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            allDeviceFolders
        } else {
            allDeviceFolders.filter {
                it.name.contains(q, ignoreCase = true) || it.path.contains(q, ignoreCase = true)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        title = {
                            Column {
                                Text(
                                    text = "Add Auto-Hide Folders",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${watchedFolders.size} selected • ${allDeviceFolders.size} device folders found",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("folder_search_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("folder_search_done_button")
                            ) {
                                Text("Done", fontWeight = FontWeight.Bold)
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Top Search Bar with One-Tap 'X' Clear Button
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search any folder name or path...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { searchQuery = "" },
                                    modifier = Modifier.testTag("folder_search_clear_button")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear Search")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("folder_search_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Scanning device storage folders...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (filteredFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No folders matching '$searchQuery'"
                                    else "No device folders found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredFolders, key = { it.path }) { folderSummary ->
                                val isChecked = watchedFolders.contains(folderSummary.path)
                                Card(
                                    onClick = {
                                        if (isChecked) onToggleWatchedFolder(folderSummary.path)
                                        else onAddCustomWatchedFolder(folderSummary.path)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isChecked) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = {
                                                if (isChecked) onToggleWatchedFolder(folderSummary.path)
                                                else onAddCustomWatchedFolder(folderSummary.path)
                                            }
                                        )
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isChecked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = folderSummary.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 15.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (folderSummary.mediaCount > 0) "${folderSummary.mediaCount} media"
                                                    else "Empty folder",
                                                    fontSize = 11.sp,
                                                    color = if (folderSummary.mediaCount > 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            Text(
                                                text = folderSummary.path,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .height(50.dp)
                    ) {
                        Text("Done (${watchedFolders.size} Selected)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    if (mb >= 1024.0) {
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }
    return String.format("%.1f MB", mb)
}

