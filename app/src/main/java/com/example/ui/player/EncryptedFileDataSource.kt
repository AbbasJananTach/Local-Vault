package com.example.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.crypto.CryptoEngine
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

        randomAccessFile = RandomAccessFile(blobFile, "r")
        val fileLength = randomAccessFile!!.length()

        if (dataSpec.position > fileLength) {
            throw java.io.EOFException("Position ${dataSpec.position} is past end of file $fileLength")
        }

        randomAccessFile!!.seek(dataSpec.position)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            fileLength - dataSpec.position
        }

        // Initialize AES-CTR cipher for position offset
        val adjustedIv = CryptoEngine.calculateCtrIvForOffset(baseIv, dataSpec.position)
        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher!!.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(adjustedIv))

        isOpen = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val encryptedBuffer = ByteArray(bytesToRead)
        val bytesRead = randomAccessFile!!.read(encryptedBuffer, 0, bytesToRead)

        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }

        val decrypted = cipher!!.update(encryptedBuffer, 0, bytesRead)
        System.arraycopy(decrypted, 0, buffer, offset, bytesRead)

        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
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
