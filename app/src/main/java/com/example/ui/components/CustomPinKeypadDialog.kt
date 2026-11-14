package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * Point 7: Reusable In-App Custom Numeric PIN Keypad & Dot Indicator with Shake Animation.
 * - Completely avoids the Android system soft keyboard for all PIN entries across the app.
 * - Renders a 3x4 numeric grid (`1-9`, `Clear/Backspace ⌫`, `0`, and `Unlock / Done ✓`).
 * - Strictly disables auto-submit on digit count: verification ONLY executes when the user
 *   explicitly taps the `Unlock / Done (✓)` button.
 * - When an incorrect PIN is submitted, shakes the dot indicator horizontally, displays
 *   `"Incorrect PIN"`, and clears the input.
 */
@Composable
fun AnimatedPinDotsDisplay(
    pinLength: Int,
    shakeTrigger: Int = 0,
    isError: Boolean = false
) {
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 420
                    -20f at 50
                    20f at 110
                    -16f at 170
                    16f at 230
                    -10f at 290
                    10f at 350
                    0f at 420
                }
            )
        }
    }

    val totalDots = maxOf(6, pinLength).coerceAtMost(10)
    val dotSize = if (totalDots > 7) 13.dp else 15.dp
    val dotSpacing = if (totalDots > 7) 8.dp else 12.dp

    Row(
        modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalDots) { index ->
            val isFilled = index < pinLength
            val dotColor = when {
                isError -> MaterialTheme.colorScheme.error
                isFilled -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            Box(
                modifier = Modifier
                    .size(if (isFilled) dotSize else dotSize * 0.85f)
                    .clip(CircleShape)
                    .background(dotColor)
                    .then(
                        if (!isFilled) {
                            Modifier.border(
                                width = 1.dp,
                                color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                        } else Modifier
                    )
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomNumericKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onClearAllClick: () -> Unit = {},
    onDoneClick: () -> Unit,
    doneLabel: String = "Unlock",
    compact: Boolean = false
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("BACK", "0", "DONE")
    )

    val buttonSize = if (compact) 62.dp else 70.dp
    val rowSpacing = if (compact) 10.dp else 12.dp
    val colSpacing = if (compact) 14.dp else 16.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(rowSpacing),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(colSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { key ->
                    val isDone = key == "DONE"
                    val isBack = key == "BACK"
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .clip(CircleShape)
                            .background(
                                if (isDone) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .combinedClickable(
                                onClick = {
                                    when (key) {
                                        "BACK" -> onBackspaceClick()
                                        "DONE" -> onDoneClick()
                                        else -> onDigitClick(key)
                                    }
                                },
                                onLongClick = {
                                    if (isBack) {
                                        onClearAllClick()
                                    }
                                }
                            )
                            .testTag("keypad_$key"),
                        contentAlignment = Alignment.Center
                    ) {
                        when (key) {
                            "BACK" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace (Long press to clear)",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(if (compact) 20.dp else 22.dp)
                                )
                                Text(
                                    text = "Clear",
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                )
                            }
                            "DONE" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = doneLabel,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(if (compact) 20.dp else 22.dp)
                                )
                                Text(
                                    text = doneLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            else -> Text(
                                text = key,
                                fontSize = if (compact) 21.sp else 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable Custom PIN Keypad Dialog (`CustomPinKeypadDialog` / `PinEntrySheet`).
 * Never opens the Android system keyboard for PIN entry and never auto-submits on digit count.
 * Verification only executes when the user explicitly taps the `Unlock / Done (✓)` button.
 */
@Composable
fun CustomPinKeypadDialog(
    title: String,
    subtitle: String,
    doneButtonLabel: String = "Unlock",
    onVerifyPin: (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onVerifyRecoveryPhrase: ((String) -> Boolean)? = null
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var recoveryMode by remember { mutableStateOf(false) }
    var recoveryInput by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (recoveryMode) "Verify Recovery Phrase" else title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (recoveryMode && onVerifyRecoveryPhrase != null) {
                    Text(
                        text = "Enter your Vault Recovery Phrase to authorize this action:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = recoveryInput,
                        onValueChange = {
                            recoveryInput = it
                            errorMessage = ""
                        },
                        label = { Text("Recovery Phrase") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_dialog_recovery_input")
                    )
                    if (errorMessage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = {
                            recoveryMode = false
                            errorMessage = ""
                        }) {
                            Text("Back to Keypad")
                        }
                        Row {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    if (onVerifyRecoveryPhrase(recoveryInput.trim())) {
                                        onSuccess()
                                    } else {
                                        errorMessage = "Incorrect Recovery Phrase"
                                    }
                                }
                            ) {
                                Text("Unlock")
                            }
                        }
                    }
                } else {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedPinDotsDisplay(
                        pinLength = enteredPin.length,
                        shakeTrigger = shakeTrigger,
                        isError = errorMessage.isNotEmpty()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                        if (errorMessage.isNotEmpty()) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // In-app 3x4 numeric keypad; NEVER auto-submits on digit count
                    CustomNumericKeypad(
                        compact = true,
                        doneLabel = doneButtonLabel,
                        onDigitClick = { digit ->
                            if (errorMessage.isNotEmpty()) errorMessage = ""
                            if (enteredPin.length < 10) {
                                enteredPin += digit
                            }
                        },
                        onBackspaceClick = {
                            if (errorMessage.isNotEmpty()) errorMessage = ""
                            if (enteredPin.isNotEmpty()) {
                                enteredPin = enteredPin.dropLast(1)
                            }
                        },
                        onClearAllClick = {
                            errorMessage = ""
                            enteredPin = ""
                        },
                        onDoneClick = {
                            if (enteredPin.isEmpty()) {
                                errorMessage = "Incorrect PIN"
                                shakeTrigger++
                                return@CustomNumericKeypad
                            }
                            val verified = onVerifyPin(enteredPin)
                            if (verified) {
                                enteredPin = ""
                                errorMessage = ""
                                onSuccess()
                            } else {
                                errorMessage = "Incorrect PIN"
                                shakeTrigger++
                                enteredPin = ""
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (onVerifyRecoveryPhrase != null) {
                            Arrangement.SpaceBetween
                        } else {
                            Arrangement.End
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onVerifyRecoveryPhrase != null) {
                            TextButton(
                                onClick = {
                                    recoveryMode = true
                                    errorMessage = ""
                                }
                            ) {
                                Text("Forgot PIN?", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("pin_dialog_cancel_button")
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alias for `CustomPinKeypadDialog` to satisfy both naming conventions in the architecture spec.
 */
@Composable
fun PinEntrySheet(
    title: String,
    subtitle: String,
    doneButtonLabel: String = "Unlock",
    onVerifyPin: (String) -> Boolean,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    onVerifyRecoveryPhrase: ((String) -> Boolean)? = null
) {
    CustomPinKeypadDialog(
        title = title,
        subtitle = subtitle,
        doneButtonLabel = doneButtonLabel,
        onVerifyPin = onVerifyPin,
        onSuccess = onSuccess,
        onDismiss = onDismiss,
        onVerifyRecoveryPhrase = onVerifyRecoveryPhrase
    )
}

/**
 * Two-Step Custom Numeric Keypad Dialog for Setting + Confirming a New PIN
 * (e.g., Custom Folder PIN or Decoy PIN) without ever opening the Android soft keyboard.
 */
@Composable
fun CustomPinSetupDialog(
    title: String,
    step1Subtitle: String = "Enter a 4 to 10 digit numeric PIN, then tap Done (✓)",
    step2Subtitle: String = "Re-enter the exact same PIN to confirm, then tap Done (✓)",
    onPinConfirmed: (String) -> Boolean,
    mismatchOrInvalidError: String = "PIN cannot be used. Try a different PIN.",
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var firstPin by remember { mutableStateOf("") }
    var currentPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var shakeTrigger by remember { mutableIntStateOf(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (step == 1) title else "Confirm $title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (step == 1) step1Subtitle else step2Subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedPinDotsDisplay(
                    pinLength = currentPin.length,
                    shakeTrigger = shakeTrigger,
                    isError = errorMessage.isNotEmpty()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(modifier = Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                CustomNumericKeypad(
                    compact = true,
                    doneLabel = "Done",
                    onDigitClick = { digit ->
                        if (errorMessage.isNotEmpty()) errorMessage = ""
                        if (currentPin.length < 10) {
                            currentPin += digit
                        }
                    },
                    onBackspaceClick = {
                        if (errorMessage.isNotEmpty()) errorMessage = ""
                        if (currentPin.isNotEmpty()) {
                            currentPin = currentPin.dropLast(1)
                        }
                    },
                    onClearAllClick = {
                        errorMessage = ""
                        currentPin = ""
                    },
                    onDoneClick = {
                        if (currentPin.length < 4) {
                            errorMessage = "PIN must be at least 4 digits"
                            shakeTrigger++
                            return@CustomNumericKeypad
                        }
                        if (step == 1) {
                            firstPin = currentPin
                            currentPin = ""
                            errorMessage = ""
                            step = 2
                        } else {
                            if (currentPin != firstPin) {
                                errorMessage = "PINs do not match"
                                shakeTrigger++
                                currentPin = ""
                            } else {
                                val ok = onPinConfirmed(currentPin)
                                if (ok) {
                                    onDismiss()
                                } else {
                                    errorMessage = mismatchOrInvalidError
                                    shakeTrigger++
                                    currentPin = ""
                                    step = 1
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step == 2) {
                        TextButton(onClick = {
                            step = 1
                            currentPin = ""
                            errorMessage = ""
                        }) {
                            Text("Back")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
