package com.example.ui.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AnimatedPinDotsDisplay
import com.example.ui.components.CustomNumericKeypad
import kotlinx.coroutines.delay

/**
 * Point 7: Vault Lock & First-Time Setup Screen.
 * - Uses the in-app 3x4 `CustomNumericKeypad` (`1-9`, `Clear/Backspace ⌫`, `0`, and `Unlock / Done ✓`).
 * - Strictly disables auto-submit on digit count: verification ONLY executes when the user
 *   explicitly taps the `Unlock / Done (✓)` button.
 * - When an incorrect PIN is submitted on `Unlock`, shakes the dot indicator, displays
 *   `"Incorrect PIN"`, and clears the input.
 */
@Composable
fun AuthScreen(
    isInitialized: Boolean,
    isLockedOut: Boolean,
    lockoutRemainingSeconds: Long,
    onPinSubmit: (String) -> Boolean,
    onSetupCompleted: (pin: String, recoveryPhrase: String) -> Unit,
    onRecoveryPhraseSubmit: (String) -> Boolean,
    onResetPinConfirmed: (newPin: String) -> Unit,
    onRestoreBackupClick: (() -> Unit)? = null
) {
    var pinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var setupStep by remember { mutableIntStateOf(1) } // 1: Enter PIN, 2: Confirm PIN, 3: Recovery Phrase
    var firstEnteredPin by remember { mutableStateOf("") }
    var recoveryPhraseText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var isRecoveryMode by remember { mutableStateOf(false) }

    var timerSeconds by remember(lockoutRemainingSeconds) { mutableLongStateOf(lockoutRemainingSeconds) }

    LaunchedEffect(isLockedOut, lockoutRemainingSeconds) {
        if (isLockedOut) {
            timerSeconds = lockoutRemainingSeconds
            while (timerSeconds > 0) {
                delay(1000)
                timerSeconds--
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Vault Lock Icon",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isLockedOut || timerSeconds > 0) {
                // 1-HOUR HARDENED LOCKOUT UI
                Text(
                    text = "Vault Locked Out",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Too many failed attempts. Security timer active.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))

                val minutes = timerSeconds / 60
                val seconds = timerSeconds % 60
                Text(
                    text = String.format("%02d:%02d", minutes, seconds),
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(24.dp))

                TextButton(
                    onClick = { isRecoveryMode = true },
                    modifier = Modifier.testTag("forgot_pin_button")
                ) {
                    Text("Forgot PIN? Unlock via Recovery Phrase")
                }
            } else if (isRecoveryMode) {
                // RECOVERY PHRASE UNLOCK
                Text(
                    text = "Recovery Phrase Unlock",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your custom recovery phrase to unwrap the vault key",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = recoveryPhraseText,
                    onValueChange = {
                        recoveryPhraseText = it
                        errorMessage = ""
                    },
                    label = { Text("Recovery Phrase") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recovery_phrase_input"),
                    singleLine = true
                )

                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        isRecoveryMode = false
                        errorMessage = ""
                    }) {
                        Text("Back to PIN")
                    }
                    Button(
                        onClick = {
                            val success = onRecoveryPhraseSubmit(recoveryPhraseText)
                            if (!success) {
                                errorMessage = "Incorrect recovery phrase"
                            } else {
                                isRecoveryMode = false
                            }
                        },
                        modifier = Modifier.testTag("submit_recovery_button")
                    ) {
                        Text("Unlock Vault")
                    }
                }
            } else if (!isInitialized) {
                // FIRST TIME SETUP FLOW (In-App 3x4 Keypad for PIN, no system keyboard for PINs)
                when (setupStep) {
                    1 -> {
                        Text(
                            text = "Create Master PIN",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Enter 4 to 10 digits, then tap Done (✓)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        AnimatedPinDotsDisplay(
                            pinLength = pinText.length,
                            shakeTrigger = shakeTrigger,
                            isError = errorMessage.isNotEmpty()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                            if (errorMessage.isNotEmpty()) {
                                Text(
                                    text = errorMessage,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        CustomNumericKeypad(
                            doneLabel = "Done",
                            onDigitClick = { digit ->
                                errorMessage = ""
                                if (pinText.length < 10) pinText += digit
                            },
                            onBackspaceClick = {
                                errorMessage = ""
                                if (pinText.isNotEmpty()) pinText = pinText.dropLast(1)
                            },
                            onClearAllClick = {
                                errorMessage = ""
                                pinText = ""
                            },
                            onDoneClick = {
                                if (pinText.length >= 4) {
                                    firstEnteredPin = pinText
                                    pinText = ""
                                    errorMessage = ""
                                    setupStep = 2
                                } else {
                                    errorMessage = "PIN must be at least 4 digits"
                                    shakeTrigger++
                                }
                            }
                        )
                        if (onRestoreBackupClick != null) {
                            Spacer(modifier = Modifier.height(18.dp))
                            TextButton(
                                onClick = onRestoreBackupClick,
                                modifier = Modifier.testTag("setup_restore_backup_button")
                            ) {
                                Text("Migrating phones? Restore Encrypted Backup (.lvb)")
                            }
                        }
                    }
                    2 -> {
                        Text(
                            text = "Confirm Master PIN",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Re-enter the exact same PIN, then tap Done (✓)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        AnimatedPinDotsDisplay(
                            pinLength = confirmPinText.length,
                            shakeTrigger = shakeTrigger,
                            isError = errorMessage.isNotEmpty()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                            if (errorMessage.isNotEmpty()) {
                                Text(
                                    text = errorMessage,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        CustomNumericKeypad(
                            doneLabel = "Done",
                            onDigitClick = { digit ->
                                errorMessage = ""
                                if (confirmPinText.length < 10) confirmPinText += digit
                            },
                            onBackspaceClick = {
                                errorMessage = ""
                                if (confirmPinText.isNotEmpty()) confirmPinText = confirmPinText.dropLast(1)
                            },
                            onClearAllClick = {
                                errorMessage = ""
                                confirmPinText = ""
                            },
                            onDoneClick = {
                                if (confirmPinText == firstEnteredPin && confirmPinText.isNotEmpty()) {
                                    setupStep = 3
                                    errorMessage = ""
                                } else {
                                    errorMessage = "PINs do not match. Try again."
                                    shakeTrigger++
                                    confirmPinText = ""
                                }
                            }
                        )
                    }
                    3 -> {
                        // RECOVERY PHRASE SETUP STEP
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Set Custom Recovery Phrase",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Type a secret sentence or word to recover your vault if you ever forget your PIN.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = recoveryPhraseText,
                            onValueChange = { recoveryPhraseText = it },
                            label = { Text("Custom Recovery Phrase") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_recovery_phrase_input"),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                if (recoveryPhraseText.trim().isNotEmpty()) {
                                    onSetupCompleted(firstEnteredPin, recoveryPhraseText)
                                }
                            },
                            enabled = recoveryPhraseText.trim().isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("complete_setup_button")
                        ) {
                            Text("Complete Vault Setup")
                        }
                    }
                }
            } else {
                // NORMAL LOCK SCREEN UNLOCK (Point 7: Never auto-submits on digit count)
                Text(
                    text = "Local Vault",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your PIN and tap Unlock (✓)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))

                AnimatedPinDotsDisplay(
                    pinLength = pinText.length,
                    shakeTrigger = shakeTrigger,
                    isError = errorMessage.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                CustomNumericKeypad(
                    doneLabel = "Unlock",
                    onDigitClick = { digit ->
                        if (errorMessage.isNotEmpty()) errorMessage = ""
                        if (pinText.length < 10) pinText += digit
                    },
                    onBackspaceClick = {
                        if (errorMessage.isNotEmpty()) errorMessage = ""
                        if (pinText.isNotEmpty()) pinText = pinText.dropLast(1)
                    },
                    onClearAllClick = {
                        errorMessage = ""
                        pinText = ""
                    },
                    onDoneClick = {
                        if (pinText.isEmpty()) {
                            errorMessage = "Incorrect PIN"
                            shakeTrigger++
                            return@CustomNumericKeypad
                        }
                        val ok = onPinSubmit(pinText)
                        pinText = ""
                        if (!ok) {
                            errorMessage = "Incorrect PIN"
                            shakeTrigger++
                        } else {
                            errorMessage = ""
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        errorMessage = ""
                        isRecoveryMode = true
                    },
                    modifier = Modifier.testTag("forgot_pin_link")
                ) {
                    Text("Forgot PIN?")
                }
            }
        }
    }
}
