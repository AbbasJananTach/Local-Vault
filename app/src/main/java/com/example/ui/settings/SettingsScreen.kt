package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSetupFakePin: (fakePin: String) -> Unit,
    onRemoveFakePin: () -> Unit,
    hasDecoyPin: Boolean,
    onToggleAutoHide: (Boolean) -> Unit,
    isAutoHideEnabled: Boolean,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    var showFakePinDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vault Settings") },
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
                .padding(16.dp)
        ) {
            // Decoy PIN Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Decoy / Fake PIN Mode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Set a secondary PIN that opens a harmless decoy gallery if forced to unlock in person.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (hasDecoyPin) {
                        Button(
                            onClick = onRemoveFakePin,
                            modifier = Modifier.fillMaxWidth().testTag("remove_fake_pin")
                        ) {
                            Text("Disable Decoy PIN")
                        }
                    } else {
                        Button(
                            onClick = { showFakePinDialog = true },
                            modifier = Modifier.fillMaxWidth().testTag("setup_fake_pin")
                        ) {
                            Text("Setup Decoy PIN")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Background Auto-Hide Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Background Auto-Hide", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                        Switch(
                            checked = isAutoHideEnabled,
                            onCheckedChange = onToggleAutoHide,
                            modifier = Modifier.testTag("autohide_switch")
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monitors device folders (Camera, WhatsApp, Downloads) in background and automatically encrypts incoming media.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Backup & Restore Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Offline Encrypted Backup (.lvb)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Export or import your entire encrypted vault database and blobs to migrate to a new phone without internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = onCreateBackup,
                            modifier = Modifier.weight(1f).padding(end = 4.dp).testTag("create_backup_button")
                        ) {
                            Icon(Icons.Default.Backup, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Create .lvb")
                        }
                        Button(
                            onClick = onRestoreBackup,
                            modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("restore_backup_button")
                        ) {
                            Icon(Icons.Default.Restore, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore .lvb")
                        }
                    }
                }
            }
        }
    }

    // Fake PIN Setup Dialog
    if (showFakePinDialog) {
        var inputFakePin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFakePinDialog = false },
            title = { Text("Set Decoy PIN") },
            text = {
                OutlinedTextField(
                    value = inputFakePin,
                    onValueChange = { inputFakePin = it },
                    label = { Text("Decoy PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("fake_pin_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputFakePin.isNotEmpty()) {
                            onSetupFakePin(inputFakePin)
                            showFakePinDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFakePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
