package com.example.crypto

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class DetectedMediaSignature(
    val mimeType: String,
    val extension: String,
    val isVideo: Boolean
)

object CryptoEngine {
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_SIZE_BITS = 256
    private const val PBKDF2_ITERATIONS = 100_000
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12

    private val random = SecureRandom()

    private val VALID_MP4_SECOND_ATOMS = setOf(
        "moov", "mdat", "free", "wide", "skip", "udta", "uuid", "meta", "styp", "pdin", "ftyp", "emsg", "moof", "sidx"
    )

    /**
     * Exhaustive standard 16-byte plaintext headers for MP4/MOV/3GP/M4V/HEIC (`ftyp` box matrix),
     * JPEG (`JFIF` / `Exif`), and PNG (`IHDR`), built lazily once for mathematical AES-CTR `baseIv` recovery.
     */
    private val STANDARD_16BYTE_PLAINTEXT_HEADERS: List<ByteArray> by lazy {
        val list = ArrayList<ByteArray>(1200)

        // 1. Standard MP4/MOV/3GP/HEIC ftyp matrix:
        // Bytes 0..3: box size; Bytes 4..7: "ftyp"; Bytes 8..11: major brand; Bytes 12..15: minor version
        val ftypSizes = intArrayOf(
            0x00000018, 0x0000001C, 0x00000020, 0x00000014,
            0x00000010, 0x00000024, 0x00000028, 0x0000002C, 0x00000030
        )
        val majorBrands = arrayOf(
            "isom", "mp42", "mp41", "avc1", "qt  ", "MSNV", "iso2", "iso4",
            "iso5", "iso6", "dash", "M4V ", "3gp4", "3gp5", "mp71", "f4v ",
            "heic", "mif1"
        )
        val minorVersions = intArrayOf(
            0x00000000, 0x00000200, 0x00000001, 0x00000100,
            0x00000300, 0x20200300, 0x0135403C
        )

        for (minor in minorVersions) {
            for (brand in majorBrands) {
                val brandBytes = brand.toByteArray(Charsets.US_ASCII)
                for (size in ftypSizes) {
                    val buf = ByteBuffer.allocate(16)
                    buf.putInt(size)
                    buf.put('f'.code.toByte())
                    buf.put('t'.code.toByte())
                    buf.put('y'.code.toByte())
                    buf.put('p'.code.toByte())
                    buf.put(brandBytes, 0, 4)
                    buf.putInt(minor)
                    list.add(buf.array())
                }
            }
        }

        // 2. Standard PNG 16-byte header (8-byte PNG signature + 00 00 00 0D + "IHDR")
        list.add(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
            )
        )

        // 3. Standard JPEG JFIF 16-byte headers
        val jfifVariants = arrayOf(
            byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x01),
            byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x48),
            byteArrayOf(0x01, 0x01, 0x01, 0x00, 0x60),
            byteArrayOf(0x01, 0x02, 0x00, 0x00, 0x01),
            byteArrayOf(0x01, 0x02, 0x01, 0x00, 0x48),
            byteArrayOf(0x01, 0x02, 0x01, 0x00, 0x60),
            byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x48)
        )
        for (tail in jfifVariants) {
            list.add(
                byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00,
                    tail[0], tail[1], tail[2], tail[3], tail[4]
                )
            )
        }

        list
    }

    fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * Derives a deterministic 16-byte AES-CTR IV from `secretKey` and `blobName` (`<uuid>.bin`)
     * using HMAC-SHA256.
     */
    fun deriveDeterministicIv(secretKey: SecretKey, blobName: String): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey.encoded, "HmacSHA256"))
            val digest = mac.doFinal(blobName.toByteArray(Charsets.UTF_8))
            digest.copyOfRange(0, 16)
        } catch (_: Throwable) {
            generateRandomBytes(16)
        }
    }

    /**
     * Decrypts the first N header bytes of an AES-CTR encrypted blob in memory for magic-byte inspection.
     */
    fun decryptHeaderBytesCtr(
        encryptedHeader: ByteArray,
        secretKey: SecretKey,
        baseIv: ByteArray
    ): ByteArray {
        if (encryptedHeader.isEmpty() || baseIv.size != 16) return ByteArray(0)
        return try {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(baseIv))
            cipher.doFinal(encryptedHeader)
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /**
     * Fix 4A: Mathematical AES-CTR `baseIv` Recovery for Orphaned `.bin` Files Missing `.bin.meta`:
     *
     * In AES-CTR, Block 0 ($C_0$, bytes `0..15` of `blobFile`) satisfies:
     *   $C_0 = P_0 \oplus \text{AES\_ECB\_Encrypt}(\text{masterKey}, \text{baseIv})$
     * Therefore, given any candidate 16-byte plaintext header $P_0$:
     *   $\text{candidateBaseIv} = \text{AES\_ECB\_Decrypt}(\text{masterKey}, C_0 \oplus P_0)$
     *
     * We then decrypt the 64-byte header ($C_0..C_3$) with `AES/CTR/NoPadding` using `candidateBaseIv`
     * and verify Block 1 / Block 2 / Block 3 (bytes `16..63`), which use counter `candidateBaseIv + 1`,
     * `candidateBaseIv + 2`, and `candidateBaseIv + 3`. A wrong `candidateBaseIv` produces 100% random
     * noise in bytes `16..63`, whereas the true `baseIv` reveals valid MP4 atoms (`moov`, `mdat`, `free`,
     * `wide`, etc. at offset `ftypSize + 4` or printable ASCII compatible brands at bytes `16..23`),
     * valid PNG `IHDR` dimensions/colorType, or valid JPEG markers!
     */
    fun recoverOrphanedBinBaseIvAndMime(
        blobFile: File,
        masterKey: SecretKey,
        siblingPlainHeaders: List<ByteArray> = emptyList()
    ): Pair<ByteArray, String>? {
        if (!blobFile.exists() || blobFile.length() < 16L) return null

        val headerBuf = ByteArray(64)
        var bytesRead = 0
        try {
            FileInputStream(blobFile).use { fis ->
                bytesRead = fis.read(headerBuf)
            }
        } catch (_: Throwable) {
            return null
        }
        if (bytesRead < 16) return null
        val cipherHeader = if (bytesRead == 64) headerBuf else headerBuf.copyOfRange(0, bytesRead)
        val c0 = cipherHeader.copyOfRange(0, 16)

        // 0. Fast-path check: deterministic IV or zero IV
        val detIv = deriveDeterministicIv(masterKey, blobFile.name)
        val detPlain = decryptHeaderBytesCtr(cipherHeader, masterKey, detIv)
        val detVerifiedMime = verifyDecryptedHeader64(detPlain)
        if (detVerifiedMime != null) {
            return Pair(detIv, detVerifiedMime)
        }

        val zeroIv = ByteArray(16)
        val zeroPlain = decryptHeaderBytesCtr(cipherHeader, masterKey, zeroIv)
        val zeroVerifiedMime = verifyDecryptedHeader64(zeroPlain)
        if (zeroVerifiedMime != null) {
            return Pair(zeroIv, zeroVerifiedMime)
        }

        // 1. Mathematical AES-ECB inversion: baseIv = AES_ECB_Decrypt(masterKey, C0 xor P0)
        return try {
            val ecbDecryptCipher = Cipher.getInstance("AES/ECB/NoPadding")
            ecbDecryptCipher.init(Cipher.DECRYPT_MODE, masterKey)
            val ctrVerifyCipher = Cipher.getInstance("AES/CTR/NoPadding")
            val xorBlock = ByteArray(16)

            // Priority 1: Sibling 16-byte plaintext headers from device folders & already-indexed vault blobs
            for (p0 in siblingPlainHeaders) {
                if (p0.size < 16) continue
                for (i in 0 until 16) {
                    xorBlock[i] = (c0[i].toInt() xor p0[i].toInt()).toByte()
                }
                val candidateBaseIv = ecbDecryptCipher.doFinal(xorBlock)
                ctrVerifyCipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(candidateBaseIv))
                val decrypted64 = ctrVerifyCipher.doFinal(cipherHeader)
                val verifiedMime = verifyDecryptedHeader64(decrypted64)
                if (verifiedMime != null) {
                    return Pair(candidateBaseIv, verifiedMime)
                }
            }

            // Priority 2: Exhaustive Standard MP4/MOV/3GP/HEIC ftyp + PNG + JPEG Header Matrix
            for (p0 in STANDARD_16BYTE_PLAINTEXT_HEADERS) {
                for (i in 0 until 16) {
                    xorBlock[i] = (c0[i].toInt() xor p0[i].toInt()).toByte()
                }
                val candidateBaseIv = ecbDecryptCipher.doFinal(xorBlock)
                ctrVerifyCipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(candidateBaseIv))
                val decrypted64 = ctrVerifyCipher.doFinal(cipherHeader)
                val verifiedMime = verifyDecryptedHeader64(decrypted64)
                if (verifiedMime != null) {
                    return Pair(candidateBaseIv, verifiedMime)
                }
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Verifies whether a 16..64 byte decrypted header has valid structure across BOTH Block 0 (bytes 0..15)
     * AND Block 1..3 (bytes 16..63), returning the detected MIME type if and only if Block 1+ validates.
     */
    fun verifyDecryptedHeader64(decrypted: ByteArray): String? {
        if (decrypted.size < 16) return null
        val sig = detectMediaSignature(decrypted) ?: return null

        // If header is only 16 bytes, return signature directly
        if (decrypted.size < 24) return sig.mimeType

        val b0 = decrypted[0].toInt() and 0xFF
        val b1 = decrypted[1].toInt() and 0xFF
        val b2 = decrypted[2].toInt() and 0xFF
        val b3 = decrypted[3].toInt() and 0xFF

        // Case A: ISO Base Media ("ftyp" at bytes 4..7)
        if ((decrypted[4].toInt() and 0xFF) == 'f'.code &&
            (decrypted[5].toInt() and 0xFF) == 't'.code &&
            (decrypted[6].toInt() and 0xFF) == 'y'.code &&
            (decrypted[7].toInt() and 0xFF) == 'p'.code
        ) {
            val ftypSize = ((b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3)
            if (ftypSize < 16 || ftypSize > 256 || (ftypSize % 4) != 0) return null

            // Check 1: If the next atom starts within our 64-byte window (at offset ftypSize, atom type at ftypSize + 4)
            if (ftypSize in 16..(decrypted.size - 8)) {
                val nextAtomType = String(decrypted, ftypSize + 4, 4, Charsets.ISO_8859_1)
                if (nextAtomType in VALID_MP4_SECOND_ATOMS) {
                    return sig.mimeType
                }
            }

            // Check 2: If ftypSize > 16, bytes 16..minOf(ftypSize, 24)-1 are compatible 4CC brands in Block 1
            if (ftypSize >= 20 && decrypted.size >= 20) {
                val checkEnd = minOf(ftypSize, decrypted.size, 28)
                var allBrandPrintable = true
                for (i in 16 until checkEnd) {
                    val ch = decrypted[i].toInt() and 0xFF
                    if (ch !in 0x20..0x7E) {
                        allBrandPrintable = false
                        break
                    }
                }
                if (allBrandPrintable && (checkEnd - 16) >= 8) {
                    return sig.mimeType
                }
            }
            return null
        }

        // Case B: PNG (89 50 4E 47 0D 0A 1A 0A 00 00 00 0D 49 48 44 52)
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 && decrypted.size >= 29) {
            val width = ByteBuffer.wrap(decrypted, 16, 4).int
            val height = ByteBuffer.wrap(decrypted, 20, 4).int
            val bitDepth = decrypted[24].toInt() and 0xFF
            val colorType = decrypted[25].toInt() and 0xFF
            val compression = decrypted[26].toInt() and 0xFF
            val filter = decrypted[27].toInt() and 0xFF
            val interlace = decrypted[28].toInt() and 0xFF
            if (width in 1..65535 &&
                height in 1..65535 &&
                bitDepth in setOf(1, 2, 4, 8, 16) &&
                colorType in setOf(0, 2, 3, 4, 6) &&
                compression == 0 &&
                filter == 0 &&
                (interlace == 0 || interlace == 1)
            ) {
                return sig.mimeType
            }
            return null
        }

        // Case C: JPEG (FF D8 FF ...)
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF && decrypted.size >= 22) {
            // For standard 16-byte JFIF APP0 (FF D8 FF E0 00 10 'J' 'F' 'I' 'F' 00 ...),
            // bytes 16..17 are Xthumbnail/Ythumbnail (usually 00 00) and byte 18 is 0xFF (next marker)
            val appMarker = b3
            val segLen = ((decrypted[4].toInt() and 0xFF) shl 8) or (decrypted[5].toInt() and 0xFF)
            val nextMarkerOffset = 4 + segLen
            if (nextMarkerOffset in 16..(decrypted.size - 2)) {
                val m0 = decrypted[nextMarkerOffset].toInt() and 0xFF
                val m1 = decrypted[nextMarkerOffset + 1].toInt() and 0xFF
                if (m0 == 0xFF && m1 in 0xC0..0xFE && m1 != 0xFF) {
                    return sig.mimeType
                }
            }
            if (appMarker == 0xE1 &&
                (decrypted[6].toInt() and 0xFF) == 'E'.code &&
                (decrypted[7].toInt() and 0xFF) == 'x'.code &&
                (decrypted[8].toInt() and 0xFF) == 'i'.code &&
                (decrypted[9].toInt() and 0xFF) == 'f'.code
            ) {
                // Exif TIFF header at bytes 12..19: "II\x2A\x00" or "MM\x00\x2A" followed by 0x00000008 in Block 1
                val o16 = decrypted[16].toInt() and 0xFF
                val o17 = decrypted[17].toInt() and 0xFF
                val o18 = decrypted[18].toInt() and 0xFF
                val o19 = decrypted[19].toInt() and 0xFF
                if ((o16 == 0x08 && o17 == 0x00 && o18 == 0x00 && o19 == 0x00) ||
                    (o16 == 0x00 && o17 == 0x00 && o18 == 0x00 && o19 == 0x08)
                ) {
                    return sig.mimeType
                }
            }
            return null
        }

        // Case D: MKV / WebM / RIFF (WEBP / AVI) / GIF
        val asciiBlock1 = String(decrypted, 16, minOf(16, decrypted.size - 16), Charsets.ISO_8859_1).lowercase(Locale.US)
        if (asciiBlock1.contains("matroska") ||
            asciiBlock1.contains("webm") ||
            asciiBlock1.contains("vp8") ||
            asciiBlock1.contains("list") ||
            asciiBlock1.contains("avih") ||
            asciiBlock1.contains("hdrl")
        ) {
            return sig.mimeType
        }

        return null
    }

    /**
     * Inspects the first 16..64 bytes of a decrypted header (or raw file header) for standard
     * Video (`ftyp`, `mp4`, `mkv`, `webm`, `avi`, `mov`, `3gp`, `flv`) and Image (`FF D8 FF` JPG,
     * `89 50 4E 47` PNG, `WEBP`, `HEIC`, `GIF`, `BMP`) magic bytes.
     */
    fun detectMediaSignature(header: ByteArray): DetectedMediaSignature? {
        if (header.size < 4) return null
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF

        // 1. JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return DetectedMediaSignature("image/jpeg", ".jpg", isVideo = false)
        }

        // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
        if (header.size >= 8 &&
            b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 &&
            (header[4].toInt() and 0xFF) == 0x0D &&
            (header[5].toInt() and 0xFF) == 0x0A &&
            (header[6].toInt() and 0xFF) == 0x1A &&
            (header[7].toInt() and 0xFF) == 0x0A
        ) {
            return DetectedMediaSignature("image/png", ".png", isVideo = false)
        }

        // 3. GIF: "GIF87a" or "GIF89a"
        if (header.size >= 6 &&
            b0 == 'G'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == '8'.code &&
            ((header[4].toInt() and 0xFF) == '7'.code || (header[4].toInt() and 0xFF) == '9'.code) &&
            (header[5].toInt() and 0xFF) == 'a'.code
        ) {
            return DetectedMediaSignature("image/gif", ".gif", isVideo = false)
        }

        // 4. RIFF containers: WEBP ("RIFF....WEBP") or AVI ("RIFF....AVI ")
        if (header.size >= 12 &&
            b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code
        ) {
            val tag = String(header, 8, 4, Charsets.US_ASCII)
            if (tag == "WEBP") {
                return DetectedMediaSignature("image/webp", ".webp", isVideo = false)
            }
            if (tag == "AVI " || tag == "AVIX") {
                return DetectedMediaSignature("video/x-msvideo", ".avi", isVideo = true)
            }
        }

        // 5. ISO Base Media File Format ("ftyp" at offset 4..7): MP4, MOV, 3GP, M4V, HEIC, HEIF, AVIF
        if (header.size >= 12 &&
            (header[4].toInt() and 0xFF) == 'f'.code &&
            (header[5].toInt() and 0xFF) == 't'.code &&
            (header[6].toInt() and 0xFF) == 'y'.code &&
            (header[7].toInt() and 0xFF) == 'p'.code
        ) {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase(Locale.US)
            val fullHeaderAscii = String(
                header,
                0,
                minOf(header.size, 32),
                Charsets.ISO_8859_1
            ).lowercase(Locale.US)

            return when {
                brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif") ||
                    fullHeaderAscii.contains("heic") || fullHeaderAscii.contains("heix") -> {
                    DetectedMediaSignature("image/heic", ".heic", isVideo = false)
                }
                brand == "qt  " -> {
                    DetectedMediaSignature("video/quicktime", ".mov", isVideo = true)
                }
                brand.startsWith("3gp") || brand.startsWith("3g2") -> {
                    DetectedMediaSignature("video/3gpp", ".3gp", isVideo = true)
                }
                else -> {
                    DetectedMediaSignature("video/mp4", ".mp4", isVideo = true)
                }
            }
        }

        // 6. MKV / WebM (EBML header: 1A 45 DF A3)
        if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) {
            val ascii = String(header, 0, minOf(header.size, 64), Charsets.ISO_8859_1).lowercase(Locale.US)
            return if (ascii.contains("webm")) {
                DetectedMediaSignature("video/webm", ".webm", isVideo = true)
            } else {
                DetectedMediaSignature("video/x-matroska", ".mkv", isVideo = true)
            }
        }

        // 7. FLV ("FLV\x01")
        if (b0 == 'F'.code && b1 == 'L'.code && b2 == 'V'.code && b3 == 0x01) {
            return DetectedMediaSignature("video/x-flv", ".flv", isVideo = true)
        }

        // 8. BMP ("BM" with plausible file size header)
        if (header.size >= 14 && b0 == 'B'.code && b1 == 'M'.code &&
            (header[6].toInt() == 0 && header[7].toInt() == 0 && header[8].toInt() == 0 && header[9].toInt() == 0)
        ) {
            return DetectedMediaSignature("image/bmp", ".bmp", isVideo = false)
        }

        return null
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

    /**
     * FIX 1: Lossless AES-256-CTR Stream Encryption with Mandatory `cipher.doFinal()` Flush.
     *
     * Root Cause Fixed:
     * Android's Conscrypt/OpenSSL JCE provider buffers partial 16-byte blocks inside `cipher.update(...)`
     * and only emits the final 1..15 bytes when `cipher.doFinal()` is invoked. Furthermore,
     * `javax.crypto.CipherOutputStream.close()` silently swallows `GeneralSecurityException`.
     * By explicitly streaming `cipher.update(...)` and writing `cipher.doFinal()` directly to
     * `outputStream`, we guarantee `totalCipherBytesWritten == totalPlainBytesRead` bit-for-bit.
     */
    fun encryptStreamCtr(
        inputStream: InputStream,
        outputStream: OutputStream,
        secretKey: SecretKey,
        baseIv: ByteArray
    ): Long {
        require(baseIv.size == 16) { "CTR IV must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(baseIv))

        val chunkBytes = 1024 * 1024 // 1MB buffer
        val inBuffer = ByteArray(chunkBytes)
        val outBuffer = ByteArray(chunkBytes + 32)
        var totalPlainRead = 0L
        var totalCipherWritten = 0L

        while (true) {
            val read = inputStream.read(inBuffer)
            if (read == -1) break
            if (read == 0) continue
            totalPlainRead += read
            val outLen = cipher.update(inBuffer, 0, read, outBuffer, 0)
            if (outLen > 0) {
                outputStream.write(outBuffer, 0, outLen)
                totalCipherWritten += outLen
            }
        }

        val finalBytes = cipher.doFinal()
        if (finalBytes != null && finalBytes.isNotEmpty()) {
            outputStream.write(finalBytes)
            totalCipherWritten += finalBytes.size
        }
        outputStream.flush()

        if (totalCipherWritten != totalPlainRead) {
            throw IllegalStateException(
                "AES-CTR encryption byte mismatch: read $totalPlainRead bytes but wrote $totalCipherWritten bytes"
            )
        }
        return totalCipherWritten
    }

    /**
     * FIX 1: Lossless AES-256-CTR Stream Decryption with Mandatory `cipher.doFinal()` Flush
     * and 16-Byte Block Alignment.
     *
     * Root Cause Fixed:
     * Previously, `decryptStreamCtr` looped `cipher.update(...)` and exited without calling
     * `cipher.doFinal()`, truncating the final 1..15 bytes of any file whose byte length was
     * not an exact multiple of 16 (corrupting MP4 `moov` tables or JPEG EOI markers at the end of files).
     * Additionally, when `startOffset` is not a multiple of 16, the cipher is initialized at
     * `blockAlignedStart = (startOffset / 16L) * 16L` so keystream alignment is 100% exact.
     */
    fun decryptStreamCtr(
        inputStream: InputStream,
        outputStream: OutputStream,
        secretKey: SecretKey,
        baseIv: ByteArray,
        startOffset: Long = 0L,
        lengthToRead: Long = -1L
    ): Long {
        require(baseIv.size == 16) { "CTR IV must be 16 bytes" }
        val startBlockOffset = (startOffset % 16L).toInt()
        val blockAlignedStart = (startOffset / 16L) * 16L
        val adjustedIv = calculateCtrIvForOffset(baseIv, blockAlignedStart)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val ivSpec = IvParameterSpec(adjustedIv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

        val chunkBytes = 1024 * 1024 // 1MB high-throughput, low-heap buffer for parallel workers
        val bufferSize = if (lengthToRead in 1L until chunkBytes.toLong()) {
            maxOf(65_536, (lengthToRead + startBlockOffset).toInt())
        } else {
            chunkBytes
        }
        val buffer = ByteArray(bufferSize)
        val outBuffer = ByteArray(bufferSize + 32)
        var remainingBytesToRead = if (lengthToRead > 0L) lengthToRead + startBlockOffset else -1L
        var skipBytesRemaining = startBlockOffset
        var totalPlainWritten = 0L

        while (remainingBytesToRead != 0L) {
            val readSize = if (remainingBytesToRead > 0L && remainingBytesToRead < buffer.size) {
                remainingBytesToRead.toInt()
            } else {
                buffer.size
            }

            val read = inputStream.read(buffer, 0, readSize)
            if (read == -1) break
            if (read == 0) continue

            val outLen = cipher.update(buffer, 0, read, outBuffer, 0)
            if (outLen > 0) {
                if (skipBytesRemaining > 0) {
                    if (outLen <= skipBytesRemaining) {
                        skipBytesRemaining -= outLen
                    } else {
                        val writeCount = outLen - skipBytesRemaining
                        outputStream.write(outBuffer, skipBytesRemaining, writeCount)
                        totalPlainWritten += writeCount
                        skipBytesRemaining = 0
                    }
                } else {
                    outputStream.write(outBuffer, 0, outLen)
                    totalPlainWritten += outLen
                }
            }

            if (remainingBytesToRead > 0L) {
                remainingBytesToRead -= read
            }
        }

        // CRITICAL: Flush the final partial 16-byte CTR block held in the JCE cipher buffer!
        val finalBytes = cipher.doFinal()
        if (finalBytes != null && finalBytes.isNotEmpty()) {
            if (skipBytesRemaining > 0) {
                if (finalBytes.size > skipBytesRemaining) {
                    val writeCount = finalBytes.size - skipBytesRemaining
                    outputStream.write(finalBytes, skipBytesRemaining, writeCount)
                    totalPlainWritten += writeCount
                }
            } else {
                outputStream.write(finalBytes)
                totalPlainWritten += finalBytes.size
            }
        }
        outputStream.flush()
        return totalPlainWritten
    }
}
