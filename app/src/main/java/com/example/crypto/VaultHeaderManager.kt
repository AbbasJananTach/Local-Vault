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

    val headerFile: File
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
    fun hasDecoyPin(): Boolean {
        if (!isVaultInitialized()) return false
        return try {
            val json = JSONObject(headerFile.readText())
            json.optBoolean("hasDecoy", false) && json.has("saltDecoy") && json.has("slotDecoy")
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun hasRecoveryPhrase(): Boolean {
        if (!isVaultInitialized()) return false
        return try {
            val json = JSONObject(headerFile.readText())
            json.has("saltB") && json.has("slotB")
        } catch (e: Exception) {
            false
        }
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
    fun getLockoutStatus(enforceLockout: Boolean = true): LockoutStatus {
        if (!enforceLockout || !isVaultInitialized()) return LockoutStatus(false, 0L, 0)
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

            val elapsedDeltaSeconds = if (nowElapsed >= elapsedRealtime && elapsedRealtime > 0L) {
                (nowElapsed - elapsedRealtime) / 1000
            } else {
                // Device may have rebooted; rely on wall clock delta if positive
                if (nowWall >= wallTime) (nowWall - wallTime) / 1000 else 0L
            }

            val wallDeltaSeconds = if (nowWall >= wallTime) {
                (nowWall - wallTime) / 1000
            } else 0L

            val passedSeconds = minOf(elapsedDeltaSeconds, wallDeltaSeconds)
            val lockoutDurationSeconds = 3600L

            if (passedSeconds >= lockoutDurationSeconds) {
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
    fun authenticatePin(inputPin: String, enforceLockout: Boolean = true): Pair<AuthResult, SecretKey?> {
        val lockout = getLockoutStatus(enforceLockout)
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

                json.put("failedAttempts", 0)
                json.put("lockoutTimestampWall", 0L)
                json.put("lockoutElapsedRealtime", 0L)
                headerFile.writeText(json.toString())

                return Pair(AuthResult.SUCCESS_DECOY, decoyMasterKey)
            } catch (_: Exception) {
                // Not decoy PIN
            }
        }

        if (enforceLockout) {
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
        }
        return Pair(AuthResult.FAILED, null)
    }

    @Synchronized
    fun verifyRealPinOnly(inputPin: String): Boolean {
        if (!isVaultInitialized()) return false
        return try {
            val json = JSONObject(headerFile.readText())
            val saltA = fromHex(json.getString("saltA"))
            val slotAEncrypted = fromHex(json.getString("slotA"))
            val pinKey = CryptoEngine.deriveKey(inputPin, saltA)
            CryptoEngine.decryptAesGcm(slotAEncrypted, pinKey)
            true
        } catch (_: Exception) {
            false
        }
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
    fun updateRecoveryPhrase(masterKey: SecretKey, newPhrase: String): Boolean {
        if (!isVaultInitialized()) return false
        return try {
            val json = JSONObject(headerFile.readText())
            val normalizedPhrase = newPhrase.trim().lowercase()
            val saltB = CryptoEngine.generateRandomBytes(16)
            val phraseDerivedKey = CryptoEngine.deriveKey(normalizedPhrase, saltB)
            val slotBEncrypted = CryptoEngine.encryptAesGcm(masterKey.encoded, phraseDerivedKey)

            json.put("saltB", toHex(saltB))
            json.put("slotB", toHex(slotBEncrypted))
            headerFile.writeText(json.toString())
            true
        } catch (e: Exception) {
            Log.e("VaultHeader", "Failed to update recovery phrase", e)
            false
        }
    }

    @Synchronized
    fun setupDecoyPin(decoyPin: String): Boolean {
        if (!isVaultInitialized()) return false
        if (verifyRealPinOnly(decoyPin)) return false // Decoy PIN must differ from Real PIN
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

    fun createFolderCustomPinPair(customPin: String): Pair<String, String> {
        val salt = CryptoEngine.generateRandomBytes(16)
        val key = CryptoEngine.deriveKey(customPin, salt)
        val verifierBytes = "FOLDER_LOCK_VERIFIED".toByteArray(Charsets.UTF_8)
        val slotEncrypted = CryptoEngine.encryptAesGcm(verifierBytes, key)
        return Pair(toHex(salt), toHex(slotEncrypted))
    }

    fun verifyFolderCustomPin(inputPin: String, saltHex: String?, slotHex: String?): Boolean {
        if (saltHex.isNullOrEmpty() || slotHex.isNullOrEmpty()) return false
        return try {
            val salt = fromHex(saltHex)
            val slot = fromHex(slotHex)
            val key = CryptoEngine.deriveKey(inputPin, salt)
            val decrypted = CryptoEngine.decryptAesGcm(slot, key)
            String(decrypted, Charsets.UTF_8) == "FOLDER_LOCK_VERIFIED"
        } catch (_: Exception) {
            false
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
