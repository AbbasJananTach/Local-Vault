package com.example.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AutoLockMode
import com.example.data.ThumbnailCacheManager
import com.example.data.VaultPreferences
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.VaultThemeMode
import com.example.ui.theme.VioletPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
    // Backup & Storage
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    totalItemsCount: Int,
    usedVaultBytes: Long,
    freeDeviceBytes: Long,
    onClearThumbnailCache: () -> Unit
) {
    BackHandler { onBack() }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showRecoveryPhraseDialog by remember { mutableStateOf(false) }
    var showFakePinDialog by remember { mutableStateOf(false) }
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var cachedThumbCount by remember { mutableIntStateOf(ThumbnailCacheManager.getCachedCount()) }

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
                        "Default Grid Columns",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4, 5).forEach { cols ->
                            FilterChip(
                                selected = defaultGridColumns == cols,
                                onClick = { onDefaultGridColumnsChanged(cols) },
                                label = { Text("$cols Cols") },
                                leadingIcon = if (defaultGridColumns == cols) {
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

            // 3. SECURITY & DUAL-KEY WRAPPING CONTROLS
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Security & Cryptography",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    // Change Master PIN & Update Recovery Phrase Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showChangePinDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_master_pin_button")
                        ) {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Change PIN")
                        }

                        OutlinedButton(
                            onClick = { showRecoveryPhraseDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("update_recovery_phrase_button")
                        ) {
                            Text(if (hasRecoveryPhrase) "Update Phrase" else "Set Phrase")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Decoy / Fake PIN Mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Decoy / Fake PIN Vault", fontWeight = FontWeight.SemiBold)
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
                                "Secondary PIN that opens an isolated decoy gallery",
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

                    // Brute-force 1-hour lockout toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("1-Hour Brute-Force Lockout", fontWeight = FontWeight.Medium)
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

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Block Screenshots & Screen Recording (FLAG_SECURE)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Block Screenshots & Recording", fontWeight = FontWeight.Medium)
                            Text(
                                "Enforce FLAG_SECURE to prevent screenshots, screen recording, and Recent Apps previews",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = blockScreenshots,
                            onCheckedChange = onToggleBlockScreenshots,
                            modifier = Modifier.testTag("toggle_flag_secure")
                        )
                    }
                }
            }

            // 4. BACKGROUND AUTO-HIDE FOLDER WATCHER
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
                        "Select device folders to monitor. Any photo or video saved into checked folders will be encrypted into Local Vault automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    presetFolders.forEach { (label, path) ->
                        val isChecked = watchedFolders.contains(path)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleWatchedFolder(path) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { onToggleWatchedFolder(path) }
                            )
                            Column {
                                Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    path,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Show any custom paths added by user
                    val presetPathSet = presetFolders.map { it.second }.toSet()
                    watchedFolders.filter { it !in presetPathSet }.forEach { customPath ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleWatchedFolder(customPath) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = true,
                                onCheckedChange = { onToggleWatchedFolder(customPath) }
                            )
                            Column {
                                Text("Custom Watched Folder", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text(
                                    customPath,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showAddFolderDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Custom Folder Path")
                    }
                }
            }

            // 5. OFFLINE ENCRYPTED BACKUP & STORAGE MANAGEMENT
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
                            "Storage & Offline Backup (.lvb)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Package your entire encrypted vault database, folder structure, and encrypted blobs into a single binary .lvb file for phone migration.",
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
                            Text("Export .lvb")
                        }
                        Button(
                            onClick = onRestoreBackup,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("restore_backup_button")
                        ) {
                            Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore .lvb")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Vault Media Items: $totalItemsCount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Encrypted Size: ${formatBytes(usedVaultBytes)} • Free: ${formatBytes(freeDeviceBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "In-RAM Cached Thumbnails: $cachedThumbCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                onClearThumbnailCache()
                                cachedThumbCount = 0
                            }
                        ) {
                            Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Cache")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Change Master PIN Dialog
    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = { Text("Change Master Vault PIN", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) currentPin = it },
                        label = { Text("Current Master PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) newPin = it },
                        label = { Text("New Master PIN (1-10 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) confirmPin = it },
                        label = { Text("Confirm New Master PIN") },
                        visualTransformation = PasswordVisualTransformation(),
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
                    if (newPin.isEmpty()) {
                        errorMsg = "New PIN cannot be empty"
                        return@Button
                    }
                    if (newPin != confirmPin) {
                        errorMsg = "New PINs do not match"
                        return@Button
                    }
                    val ok = onChangeMasterPin(currentPin, newPin)
                    if (ok) {
                        showChangePinDialog = false
                    } else {
                        errorMsg = "Current Master PIN is incorrect"
                    }
                }) {
                    Text("Update PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) { Text("Cancel") }
            }
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
                        "Your recovery phrase wraps Slot B of your 256-bit Master Vault Key so you can recover access if you forget your PIN.",
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

    // Decoy / Fake PIN Setup Dialog
    if (showFakePinDialog) {
        var inputFakePin by remember { mutableStateOf("") }
        var confirmFakePin by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showFakePinDialog = false },
            title = { Text("Configure Decoy PIN", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter a numeric Decoy PIN (different from your Real PIN). Unlocking with this PIN opens an isolated decoy gallery.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = inputFakePin,
                        onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) inputFakePin = it },
                        label = { Text("Decoy PIN (1-10 digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("fake_pin_input")
                    )
                    OutlinedTextField(
                        value = confirmFakePin,
                        onValueChange = { if (it.length <= 10 && it.all { c -> c.isDigit() }) confirmFakePin = it },
                        label = { Text("Confirm Decoy PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (errorMsg.isNotEmpty()) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputFakePin.isEmpty()) {
                            errorMsg = "Enter at least 1 digit"
                            return@Button
                        }
                        if (inputFakePin != confirmFakePin) {
                            errorMsg = "Decoy PINs do not match"
                            return@Button
                        }
                        if (onSetupFakePin(inputFakePin)) {
                            showFakePinDialog = false
                        } else {
                            errorMsg = "Decoy PIN must be different from your Real Master PIN"
                        }
                    }
                ) {
                    Text("Save Decoy PIN")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFakePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add Custom Watched Folder Dialog
    if (showAddFolderDialog) {
        var customPath by remember { mutableStateOf("/storage/emulated/0/") }
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text("Add Watched Folder Path", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter the full directory path on your device to watch for auto-hiding:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text("Folder Path") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (customPath.trim().isNotEmpty()) {
                        onAddCustomWatchedFolder(customPath.trim())
                        showAddFolderDialog = false
                    }
                }) {
                    Text("Add Folder")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) { Text("Cancel") }
            }
        )
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
