package com.example.crypto

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE_BITS = 256
    private const val PBKDF2_ITERATIONS = 100_000
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    private val random = SecureRandom()

    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    fun deriveKey(secret: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_SIZE_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptAesGcm(data: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = generateRandomBytes(GCM_IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(data)
        // Combine IV (12 bytes) + Ciphertext
        return iv + ciphertext
    }

    fun decryptAesGcm(encryptedWithIv: ByteArray, secretKey: SecretKey): ByteArray {
        require(encryptedWithIv.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted data size" }
        val iv = encryptedWithIv.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = encryptedWithIv.copyOfRange(GCM_IV_LENGTH_BYTES, encryptedWithIv.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Calculates the AES-CTR IV for a given byte offset for random-access streaming.
     * AES block size is 16 bytes.
     */
    fun calculateCtrIvForOffset(baseIv: ByteArray, byteOffset: Long): ByteArray {
        require(baseIv.size == 16) { "CTR IV must be 16 bytes" }
        val blockIndex = byteOffset / 16
        val ivBigInt = BigInteger(1, baseIv)
        val offsetIvBigInt = ivBigInt.add(BigInteger.valueOf(blockIndex))
        val offsetIvBytes = offsetIvBigInt.toByteArray()
        val resultIv = ByteArray(16)
        val copyOffset = if (offsetIvBytes.size > 16) offsetIvBytes.size - 16 else 0
        val copyLength = if (offsetIvBytes.size > 16) 16 else offsetIvBytes.size
        val destOffset = 16 - copyLength
        System.arraycopy(offsetIvBytes, copyOffset, resultIv, destOffset, copyLength)
        return resultIv
    }

    fun decryptStreamCtr(
        inputStream: InputStream,
        outputStream: OutputStream,
        secretKey: SecretKey,
        baseIv: ByteArray,
        startOffset: Long = 0L,
        lengthToRead: Long = -1L
    ) {
        val startBlockOffset = startOffset % 16
        val adjustedIv = calculateCtrIvForOffset(baseIv, startOffset)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val ivSpec = IvParameterSpec(adjustedIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val buffer = ByteArray(8192)
        var remainingBytesToRead = lengthToRead
        var isFirstBlock = true

        while (remainingBytesToRead != 0L) {
            val readSize = if (remainingBytesToRead > 0 && remainingBytesToRead < buffer.size) {
                remainingBytesToRead.toInt()
            } else {
                buffer.size
            }

            val read = inputStream.read(buffer, 0, readSize)
            if (read == -1) break

            val decrypted = cipher.update(buffer, 0, read)
            if (decrypted != null && decrypted.isNotEmpty()) {
                if (isFirstBlock && startBlockOffset > 0) {
                    val actualLength = decrypted.size - startBlockOffset.toInt()
                    if (actualLength > 0) {
                        outputStream.write(decrypted, startBlockOffset.toInt(), actualLength)
                    }
                    isFirstBlock = false
                } else {
                    outputStream.write(decrypted)
                }
            }

            if (remainingBytesToRead > 0) {
                remainingBytesToRead -= read
            }
        }
    }
}
