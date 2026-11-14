package com.example.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.crypto.CryptoEngine
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Point 22 & Hotfix #1: Defensive Random-Access Streaming Decryption DataSource for Media3 / ExoPlayer.
 * - Supports arbitrary byte-range seeking (`open(DataSpec)` with exact `dataSpec.position` offset
 *   calculation and `read(buffer, offset, length)`).
 * - Performs deterministic, stateless AES-256-CTR 16-byte block alignment on every read:
 *   1. Computes `blockStart = (currentPosition / 16L) * 16L` and `intraBlockOffset = (currentPosition % 16L).toInt()`.
 *   2. Computes the exact 128-bit counter IV for `blockStart` via `CryptoEngine.calculateCtrIvForOffset`.
 *   3. Validates `baseIv` length and wraps JCE/I/O failures cleanly in `IOException` so ExoPlayer
 *      never throws an uncaught runtime crash during opening or scrubbing.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EncryptedFileDataSource(
    private val blobFile: File,
    private val masterKey: SecretKey,
    private val baseIv: ByteArray
) : BaseDataSource(/* isNetwork = */ false) {

    private var randomAccessFile: RandomAccessFile? = null
    private var uri: Uri? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var isOpen = false
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        if (baseIv.size != 16) {
            throw IOException("Invalid video IV header size (${baseIv.size})")
        }
        if (!blobFile.exists() || !blobFile.isFile) {
            throw EOFException("Encrypted media blob not found: ${blobFile.name}")
        }

        try {
            val raf = RandomAccessFile(blobFile, "r")
            randomAccessFile = raf
            val fileLength = raf.length()

            if (dataSpec.position < 0L) {
                throw IOException("Invalid negative seek position: ${dataSpec.position}")
            }

            if (dataSpec.position > fileLength) {
                bytesRemaining = 0L
                currentPosition = fileLength
                isOpen = true
                transferStarted(dataSpec)
                return 0L
            }

            currentPosition = dataSpec.position
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, fileLength - dataSpec.position)
            } else {
                fileLength - dataSpec.position
            }

            isOpen = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            close()
            throw e
        } catch (t: Throwable) {
            close()
            throw IOException("Failed to open encrypted video stream", t)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        return try {
            val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()

            val blockStart = (currentPosition / 16L) * 16L
            val intraBlockOffset = (currentPosition % 16L).toInt()
            val totalToRead = bytesToRead + intraBlockOffset

            raf.seek(blockStart)
            val encryptedBuffer = ByteArray(totalToRead)
            var totalReadFromDisk = 0
            while (totalReadFromDisk < totalToRead) {
                val r = raf.read(encryptedBuffer, totalReadFromDisk, totalToRead - totalReadFromDisk)
                if (r == -1) break
                totalReadFromDisk += r
            }

            if (totalReadFromDisk <= intraBlockOffset) {
                return C.RESULT_END_OF_INPUT
            }

            val adjustedIv = CryptoEngine.calculateCtrIvForOffset(baseIv, blockStart)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(adjustedIv))
            val decrypted = cipher.doFinal(encryptedBuffer, 0, totalReadFromDisk)

            val availableDecrypted = decrypted.size - intraBlockOffset
            if (availableDecrypted <= 0) {
                return C.RESULT_END_OF_INPUT
            }

            val toCopy = minOf(bytesToRead, availableDecrypted)
            System.arraycopy(decrypted, intraBlockOffset, buffer, offset, toCopy)

            currentPosition += toCopy
            bytesRemaining -= toCopy
            bytesTransferred(toCopy)
            toCopy
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            throw IOException("Failed while decrypting video chunk", t)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            randomAccessFile?.close()
        } catch (_: Throwable) {
        } finally {
            randomAccessFile = null
            if (isOpen) {
                isOpen = false
                try {
                    transferEnded()
                } catch (_: Throwable) {
                }
            }
        }
    }
}
