package com.example.crypto

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

enum class AuthResult {
    SUCCESS_REAL,
    SUCCESS_DECOY,
    FAILED,
    LOCKED_OUT
}

data class LockoutStatus(
    val isLockedOut: Boolean,
    val remainingSeconds: Long,
    val failedAttempts: Int
)

class VaultHeaderManager(private val context: Context) {

    private val headerFile: File
        get() {
            val extDir = Environment.getExternalStorageDirectory()
            val localVaultDir = File(extDir, ".LocalVaultData")
            if (!localVaultDir.exists()) {
                localVaultDir.mkdirs()
            }
            val primaryHeader = File(localVaultDir, "vault.header")
            if (primaryHeader.exists() || localVaultDir.canWrite()) {
                return primaryHeader
            }
            // Fallback to app external directory if external root not accessible yet
            val appExtDir = context.getExternalFilesDir(null) ?: context.filesDir
            val fallbackVaultDir = File(appExtDir, ".LocalVaultData")
            if (!fallbackVaultDir.exists()) fallbackVaultDir.mkdirs()
            return File(fallbackVaultDir, "vault.header")
        }

    @Synchronized
    fun isVaultInitialized(): Boolean {
        return headerFile.exists() && headerFile.length() > 0
    }

    @Synchronized
    fun createVaultHeader(pin: String, recoveryPhrase: String): SecretKey {
        val masterKeyBytes = CryptoEngine.generateRandomBytes(32) // 256-bit MVK
        val masterKey = SecretKeySpec(masterKeyBytes, "AES")

        val saltA = CryptoEngine.generateRandomBytes(16)
        val pinDerivedKey = CryptoEngine.deriveKey(pin, saltA)
        val slotAEncrypted = CryptoEngine.encryptAesGcm(masterKeyBytes, pinDerivedKey)

        val normalizedPhrase = recoveryPhrase.trim().lowercase()
        val saltB = CryptoEngine.generateRandomBytes(16)
        val phraseDerivedKey = CryptoEngine.deriveKey(normalizedPhrase, saltB)
        val slotBEncrypted = CryptoEngine.encryptAesGcm(masterKeyBytes, phraseDerivedKey)

        val json = JSONObject().apply {
            put("version", 1)
            put("saltA", toHex(saltA))
            put("slotA", toHex(slotAEncrypted))
            put("saltB", toHex(saltB))
            put("slotB", toHex(slotBEncrypted))
            put("hasDecoy", false)
            put("failedAttempts", 0)
            put("lockoutTimestampWall", 0L)
            put("lockoutElapsedRealtime", 0L)
        }

        headerFile.writeText(json.toString())
        Log.i("VaultHeader", "Vault Header created successfully at ${headerFile.absolutePath}")
        return masterKey
    }

    @Synchronized
    fun getLockoutStatus(): LockoutStatus {
        if (!isVaultInitialized()) return LockoutStatus(false, 0L, 0)
        try {
            val json = JSONObject(headerFile.readText())
            val failedAttempts = json.optInt("failedAttempts", 0)
            val wallTime = json.optLong("lockoutTimestampWall", 0L)
            val elapsedRealtime = json.optLong("lockoutElapsedRealtime", 0L)

            if (failedAttempts < 3 || wallTime == 0L) {
                return LockoutStatus(false, 0L, failedAttempts)
            }

            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()

            // Monotonic delta check + Wall clock check
            val elapsedDeltaSeconds = if (nowElapsed >= elapsedRealtime) {
                (nowElapsed - elapsedRealtime) / 1000
            } else 0L

            val wallDeltaSeconds = if (nowWall >= wallTime) {
                (nowWall - wallTime) / 1000
            } else 0L

            // Lockout duration is 1 Hour (3600 seconds)
            val passedSeconds = minOf(elapsedDeltaSeconds, wallDeltaSeconds)
            val lockoutDurationSeconds = 3600L

            if (passedSeconds >= lockoutDurationSeconds) {
                // Reset lockout
                json.put("failedAttempts", 0)
                json.put("lockoutTimestampWall", 0L)
                json.put("lockoutElapsedRealtime", 0L)
                headerFile.writeText(json.toString())
                return LockoutStatus(false, 0L, 0)
            } else {
                val remainingSeconds = lockoutDurationSeconds - passedSeconds
                return LockoutStatus(true, remainingSeconds, failedAttempts)
            }
        } catch (e: Exception) {
            Log.e("VaultHeader", "Error reading lockout status", e)
            return LockoutStatus(false, 0L, 0)
        }
    }

    @Synchronized
    fun authenticatePin(inputPin: String): Pair<AuthResult, SecretKey?> {
        val lockout = getLockoutStatus()
        if (lockout.isLockedOut) {
            return Pair(AuthResult.LOCKED_OUT, null)
        }

        if (!isVaultInitialized()) return Pair(AuthResult.FAILED, null)

        val json = JSONObject(headerFile.readText())

        // 1. Try Real PIN (Slot A)
        try {
            val saltA = fromHex(json.getString("saltA"))
            val slotAEncrypted = fromHex(json.getString("slotA"))
            val pinKey = CryptoEngine.deriveKey(inputPin, saltA)
            val masterKeyBytes = CryptoEngine.decryptAesGcm(slotAEncrypted, pinKey)
            val masterKey = SecretKeySpec(masterKeyBytes, "AES")

            // Success -> Reset failed attempts
            json.put("failedAttempts", 0)
            json.put("lockoutTimestampWall", 0L)
            json.put("lockoutElapsedRealtime", 0L)
            headerFile.writeText(json.toString())

            return Pair(AuthResult.SUCCESS_REAL, masterKey)
        } catch (_: Exception) {
            // Not real PIN
        }

        // 2. Try Decoy PIN (Slot Decoy)
        if (json.optBoolean("hasDecoy", false)) {
            try {
                val saltDecoy = fromHex(json.getString("saltDecoy"))
                val slotDecoyEncrypted = fromHex(json.getString("slotDecoy"))
                val decoyKey = CryptoEngine.deriveKey(inputPin, saltDecoy)
                val decoyMasterBytes = CryptoEngine.decryptAesGcm(slotDecoyEncrypted, decoyKey)
                val decoyMasterKey = SecretKeySpec(decoyMasterBytes, "AES")

                return Pair(AuthResult.SUCCESS_DECOY, decoyMasterKey)
            } catch (_: Exception) {
                // Not decoy PIN
            }
        }

        // Failed attempt -> update counter & enforce 1-hour lockout if 3 fails
        val currentFails = json.optInt("failedAttempts", 0) + 1
        json.put("failedAttempts", currentFails)

        if (currentFails >= 3) {
            json.put("lockoutTimestampWall", System.currentTimeMillis())
            json.put("lockoutElapsedRealtime", SystemClock.elapsedRealtime())
        }

        headerFile.writeText(json.toString())

        if (currentFails >= 3) {
            return Pair(AuthResult.LOCKED_OUT, null)
        }
        return Pair(AuthResult.FAILED, null)
    }

    @Synchronized
    fun authenticateRecoveryPhrase(recoveryPhrase: String): Pair<Boolean, SecretKey?> {
        if (!isVaultInitialized()) return Pair(false, null)
        val json = JSONObject(headerFile.readText())
        try {
            val normalizedPhrase = recoveryPhrase.trim().lowercase()
            val saltB = fromHex(json.getString("saltB"))
            val slotBEncrypted = fromHex(json.getString("slotB"))
            val phraseKey = CryptoEngine.deriveKey(normalizedPhrase, saltB)
            val masterKeyBytes = CryptoEngine.decryptAesGcm(slotBEncrypted, phraseKey)
            val masterKey = SecretKeySpec(masterKeyBytes, "AES")

            // Reset lockout status upon valid recovery phrase
            json.put("failedAttempts", 0)
            json.put("lockoutTimestampWall", 0L)
            json.put("lockoutElapsedRealtime", 0L)
            headerFile.writeText(json.toString())

            return Pair(true, masterKey)
        } catch (e: Exception) {
            Log.e("VaultHeader", "Recovery phrase authentication failed", e)
            return Pair(false, null)
        }
    }

    @Synchronized
    fun resetPinWithMasterKey(masterKey: SecretKey, newPin: String): Boolean {
        if (!isVaultInitialized()) return false
        try {
            val json = JSONObject(headerFile.readText())
            val saltA = CryptoEngine.generateRandomBytes(16)
            val pinDerivedKey = CryptoEngine.deriveKey(newPin, saltA)
            val slotAEncrypted = CryptoEngine.encryptAesGcm(masterKey.encoded, pinDerivedKey)

            json.put("saltA", toHex(saltA))
            json.put("slotA", toHex(slotAEncrypted))
            json.put("failedAttempts", 0)
            json.put("lockoutTimestampWall", 0L)
            json.put("lockoutElapsedRealtime", 0L)
            headerFile.writeText(json.toString())
            return true
        } catch (e: Exception) {
            Log.e("VaultHeader", "Failed to reset PIN", e)
            return false
        }
    }

    @Synchronized
    fun setupDecoyPin(decoyPin: String): Boolean {
        if (!isVaultInitialized()) return false
        try {
            val json = JSONObject(headerFile.readText())
            val decoyKeyBytes = CryptoEngine.generateRandomBytes(32)
            val saltDecoy = CryptoEngine.generateRandomBytes(16)
            val decoyPinKey = CryptoEngine.deriveKey(decoyPin, saltDecoy)
            val slotDecoyEncrypted = CryptoEngine.encryptAesGcm(decoyKeyBytes, decoyPinKey)

            json.put("hasDecoy", true)
            json.put("saltDecoy", toHex(saltDecoy))
            json.put("slotDecoy", toHex(slotDecoyEncrypted))
            headerFile.writeText(json.toString())
            return true
        } catch (e: Exception) {
            Log.e("VaultHeader", "Failed to set up decoy PIN", e)
            return false
        }
    }

    @Synchronized
    fun removeDecoyPin(): Boolean {
        if (!isVaultInitialized()) return false
        try {
            val json = JSONObject(headerFile.readText())
            json.put("hasDecoy", false)
            json.remove("saltDecoy")
            json.remove("slotDecoy")
            headerFile.writeText(json.toString())
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

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
