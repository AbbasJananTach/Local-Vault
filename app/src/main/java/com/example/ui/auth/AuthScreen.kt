package com.example.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun AuthScreen(
    isInitialized: Boolean,
    isLockedOut: Boolean,
    lockoutRemainingSeconds: Long,
    onPinSubmit: (String) -> Unit,
    onSetupCompleted: (pin: String, recoveryPhrase: String) -> Unit,
    onRecoveryPhraseSubmit: (String) -> Boolean,
    onResetPinConfirmed: (newPin: String) -> Unit
) {
    var pinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var setupStep by remember { mutableStateOf(1) } // 1: Enter PIN, 2: Confirm PIN, 3: Recovery Phrase
    var firstEnteredPin by remember { mutableStateOf("") }
    var recoveryPhraseText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
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

            Spacer(modifier = Modifier.height(24.dp))

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
                    onValueChange = { recoveryPhraseText = it },
                    label = { Text("Recovery Phrase") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recovery_phrase_input"),
                    singleLine = true
                )

                if (errorMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { isRecoveryMode = false }) {
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
                // FIRST TIME SETUP FLOW
                when (setupStep) {
                    1 -> {
                        Text(
                            text = "Create Master PIN",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Enter 1 to 10 digits for your Vault PIN",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        PinDotsDisplay(pinLength = pinText.length)
                        Spacer(modifier = Modifier.height(32.dp))
                        NumericKeypad(
                            onDigitClick = { digit ->
                                if (pinText.length < 10) pinText += digit
                            },
                            onBackspaceClick = {
                                if (pinText.isNotEmpty()) pinText = pinText.dropLast(1)
                            },
                            onDoneClick = {
                                if (pinText.isNotEmpty()) {
                                    firstEnteredPin = pinText
                                    pinText = ""
                                    setupStep = 2
                                }
                            }
                        )
                    }
                    2 -> {
                        Text(
                            text = "Confirm Master PIN",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Re-enter the exact same PIN to confirm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (errorMessage.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        PinDotsDisplay(pinLength = confirmPinText.length)
                        Spacer(modifier = Modifier.height(32.dp))
                        NumericKeypad(
                            onDigitClick = { digit ->
                                if (confirmPinText.length < 10) confirmPinText += digit
                            },
                            onBackspaceClick = {
                                if (confirmPinText.isNotEmpty()) confirmPinText = confirmPinText.dropLast(1)
                            },
                            onDoneClick = {
                                if (confirmPinText == firstEnteredPin) {
                                    setupStep = 3
                                    errorMessage = ""
                                } else {
                                    errorMessage = "PINs do not match. Try again."
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
                // NORMAL LOCK SCREEN UNLOCK
                Text(
                    text = "Local Vault Unlocking",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your Vault PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                PinDotsDisplay(pinLength = pinText.length)
                Spacer(modifier = Modifier.height(32.dp))
                NumericKeypad(
                    onDigitClick = { digit ->
                        if (pinText.length < 10) pinText += digit
                    },
                    onBackspaceClick = {
                        if (pinText.isNotEmpty()) pinText = pinText.dropLast(1)
                    },
                    onDoneClick = {
                        if (pinText.isNotEmpty()) {
                            onPinSubmit(pinText)
                            pinText = ""
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { isRecoveryMode = true },
                    modifier = Modifier.testTag("forgot_pin_link")
                ) {
                    Text("Forgot PIN?")
                }
            }
        }
    }
}

@Composable
fun PinDotsDisplay(pinLength: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(6) { index ->
            val isFilled = index < pinLength
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFilled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
            )
        }
    }
}

@Composable
fun NumericKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onDoneClick: () -> Unit
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("BACK", "0", "DONE")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { key ->
                    KeypadButton(
                        key = key,
                        onClick = {
                            when (key) {
                                "BACK" -> onBackspaceClick()
                                "DONE" -> onDoneClick()
                                else -> onDigitClick(key)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun KeypadButton(key: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(
                if (key == "DONE") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onClick() }
            .testTag("keypad_$key"),
        contentAlignment = Alignment.Center
    ) {
        when (key) {
            "BACK" -> Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = "Backspace",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            "DONE" -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.onPrimary
            )
            else -> Text(
                text = key,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
