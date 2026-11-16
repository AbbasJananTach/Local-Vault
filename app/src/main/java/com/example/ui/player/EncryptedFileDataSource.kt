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
 * FIX 1B: Stateless 16-Byte Block-Aligned AES-256-CTR Streaming DataSource for Media3 / ExoPlayer.
 *
 * Root Cause & Architectural Guarantee:
 * - AES-CTR generates keystream in 16-byte (128-bit) blocks indexed by `blockIndex = byteOffset / 16`.
 * - Whenever ExoPlayer seeks or reads from an arbitrary `currentPosition` (e.g., when reading the
 *   `moov` atom at the end of a large MP4 or scrubbing the timeline), initializing AES-CTR with
 *   `baseIv` directly or misaligning the file pointer corrupts the decrypted stream.
 * - On EVERY `read(...)` call, this DataSource:
 *   1. Computes the exact 16-byte block-aligned start offset: `blockStart = (currentPosition / 16L) * 16L`
 *      and intra-block byte offset: `intraBlockOffset = (currentPosition % 16L).toInt()`.
 *   2. Seeks the `RandomAccessFile` to `blockStart` and reads `intraBlockOffset + bytesToRead` bytes
 *      (padded up to a full 16-byte block boundary when available on disk).
 *   3. Computes the exact 128-bit counter IV for `blockStart` via `CryptoEngine.calculateCtrIvForOffset(baseIv, blockStart)`.
 *   4. Decrypts the aligned chunk with `cipher.doFinal(...)` and copies `[intraBlockOffset .. intraBlockOffset + toCopy)`
 *      into ExoPlayer's output buffer without ever modifying `blobFile` on disk.
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
    private var fileLength: Long = 0L
    private var isOpen = false
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        if (baseIv.size != 16) {
            throw IOException("Invalid video IV header size (${baseIv.size})")
        }
        if (!blobFile.exists() || !blobFile.isFile || blobFile.length() <= 0L) {
            throw EOFException("Encrypted media blob not found or empty: ${blobFile.name}")
        }

        try {
            val raf = RandomAccessFile(blobFile, "r")
            randomAccessFile = raf
            fileLength = raf.length()

            if (dataSpec.position < 0L) {
                throw IOException("Invalid negative seek position: ${dataSpec.position}")
            }

            if (dataSpec.position >= fileLength) {
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
            if (bytesToRead <= 0) return C.RESULT_END_OF_INPUT

            // Stateless 16-byte AES-CTR block alignment
            val blockStart = (currentPosition / 16L) * 16L
            val intraBlockOffset = (currentPosition % 16L).toInt()
            val rawNeeded = intraBlockOffset + bytesToRead
            // Align read size to 16-byte multiple (clamped to remaining file length from blockStart)
            val alignedNeeded = ((rawNeeded + 15) / 16) * 16
            val maxAvailableFromBlockStart = (fileLength - blockStart).coerceAtLeast(0L).toInt()
            val totalToRead = minOf(alignedNeeded, maxAvailableFromBlockStart)

            if (totalToRead <= intraBlockOffset) {
                return C.RESULT_END_OF_INPUT
            }

            val encryptedBuffer = ByteArray(totalToRead)
            var totalReadFromDisk = 0
            synchronized(raf) {
                raf.seek(blockStart)
                while (totalReadFromDisk < totalToRead) {
                    val r = raf.read(encryptedBuffer, totalReadFromDisk, totalToRead - totalReadFromDisk)
                    if (r == -1) break
                    totalReadFromDisk += r
                }
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
