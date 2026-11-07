package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.crypto.CryptoEngine
import com.example.data.db.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object ThumbnailCacheManager {
    private const val MAX_CACHE_ENTRIES = 160

    private val memoryCache = object : LruCache<Long, ImageBitmap>(MAX_CACHE_ENTRIES) {
        override fun sizeOf(key: Long, value: ImageBitmap): Int = 1
    }

    fun getCached(itemId: Long): ImageBitmap? = synchronized(memoryCache) {
        memoryCache.get(itemId)
    }

    fun clearCache() = synchronized(memoryCache) {
        memoryCache.evictAll()
    }

    fun getCachedCount(): Int = synchronized(memoryCache) {
        memoryCache.size()
    }

    suspend fun loadThumbnail(
        item: VaultItem,
        blobFile: File,
        masterKey: SecretKey
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        getCached(item.id)?.let { return@withContext it }
        if (!blobFile.exists() || blobFile.length() == 0L) return@withContext null

        val baseIv = fromHex(item.baseIvHex)
        val bitmap: Bitmap? = try {
            if (item.mimeType.startsWith("video")) {
                decodeEncryptedVideoThumbnail(blobFile, masterKey, baseIv)
            } else {
                decodeEncryptedImageThumbnail(blobFile, masterKey, baseIv)
            }
        } catch (e: Exception) {
            Log.w("ThumbnailCache", "Failed to decode thumbnail for ${item.fileName}", e)
            null
        }

        if (bitmap != null) {
            val imageBitmap = bitmap.asImageBitmap()
            synchronized(memoryCache) {
                memoryCache.put(item.id, imageBitmap)
            }
            return@withContext imageBitmap
        }
        return@withContext null
    }

    private fun decodeEncryptedImageThumbnail(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray
    ): Bitmap? {
        val output = ByteArrayOutputStream()
        FileInputStream(blobFile).use { input ->
            CryptoEngine.decryptStreamCtr(
                inputStream = input,
                outputStream = output,
                secretKey = masterKey,
                baseIv = baseIv
            )
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) return null

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val targetSize = 320
        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= targetSize &&
            boundsOptions.outHeight / (sampleSize * 2) >= targetSize
        ) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    }

    private fun decodeEncryptedVideoThumbnail(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val dataSource = object : MediaDataSource() {
            private val raf = RandomAccessFile(blobFile, "r")

            @Synchronized
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= raf.length()) return -1
                if (size == 0) return 0

                val blockStart = (position / 16L) * 16L
                val intraBlockOffset = (position % 16L).toInt()
                val totalToRead = size + intraBlockOffset

                raf.seek(blockStart)
                val encryptedBuf = ByteArray(totalToRead)
                val bytesRead = raf.read(encryptedBuf, 0, totalToRead)
                if (bytesRead <= intraBlockOffset) return -1

                val adjustedIv = CryptoEngine.calculateCtrIvForOffset(baseIv, blockStart)
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(adjustedIv))
                val decrypted = cipher.doFinal(encryptedBuf, 0, bytesRead)

                val actualAvailable = decrypted.size - intraBlockOffset
                val toCopy = minOf(size, actualAvailable)
                System.arraycopy(decrypted, intraBlockOffset, buffer, offset, toCopy)
                return toCopy
            }

            @Synchronized
            override fun getSize(): Long = raf.length()

            @Synchronized
            override fun close() {
                try {
                    raf.close()
                } catch (_: Exception) {
                }
            }
        }

        return try {
            retriever.setDataSource(dataSource)
            val rawFrame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (rawFrame != null) {
                val maxDim = 320f
                val ratio = minOf(maxDim / rawFrame.width, maxDim / rawFrame.height, 1f)
                val w = (rawFrame.width * ratio).toInt().coerceAtLeast(1)
                val h = (rawFrame.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawFrame, w, h, true)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("ThumbnailCache", "Video thumbnail extraction failed", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
            try {
                dataSource.close()
            } catch (_: Exception) {
            }
        }
    }

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
