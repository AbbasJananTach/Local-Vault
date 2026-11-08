package com.example.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.crypto.CryptoEngine
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

class EncryptedFileDataSource(
    private val blobFile: File,
    private val masterKey: SecretKey,
    private val baseIv: ByteArray
) : BaseDataSource(/* isNetwork = */ false) {

    private var randomAccessFile: RandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var cipher: Cipher? = null
    private var isOpen = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val file = blobFile
        if (!file.exists()) {
            throw EOFException("Blob file not found: ${file.absolutePath}")
        }

        val raf = RandomAccessFile(file, "r")
        randomAccessFile = raf
        val fileLength = raf.length()

        if (dataSpec.position > fileLength) {
            throw EOFException("Position ${dataSpec.position} exceeds file length $fileLength")
        }

        raf.seek(dataSpec.position)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileLength - dataSpec.position
        }

        // Align AES/CTR cipher to the 16-byte block boundary
        val intraBlockOffset = (dataSpec.position % 16L).toInt()
        val adjustedIv = CryptoEngine.calculateCtrIvForOffset(baseIv, dataSpec.position)

        val aesCipher = Cipher.getInstance("AES/CTR/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(adjustedIv))

        if (intraBlockOffset > 0) {
            // Discard the initial intra-block keystream bytes so the cipher keystream
            // aligns precisely with the mid-block position in randomAccessFile
            val dummy = ByteArray(intraBlockOffset)
            aesCipher.update(dummy)
        }

        cipher = aesCipher
        isOpen = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
        val encryptedBuffer = ByteArray(bytesToRead)
        val bytesRead = raf.read(encryptedBuffer, 0, bytesToRead)

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        val c = cipher ?: return C.RESULT_END_OF_INPUT
        val decrypted = c.update(encryptedBuffer, 0, bytesRead)
        if (decrypted != null && decrypted.isNotEmpty()) {
            System.arraycopy(decrypted, 0, buffer, offset, decrypted.size)
        }

        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        cipher = null
        try {
            randomAccessFile?.close()
        } finally {
            randomAccessFile = null
            if (isOpen) {
                isOpen = false
                transferEnded()
            }
        }
    }
}
