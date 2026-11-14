package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.crypto.CryptoEngine
import com.example.data.db.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Architectural All-Format Thumbnail & Photo Decoder (Point 2):
 * 1. Safe Thumbnail Generation on Import:
 *    Generates a downsampled thumbnail inside an isolated `try-catch` block using `ImageDecoder`
 *    (API 28+, which natively supports HEIC, HEIF, WEBP, BMP, GIF, PNG, JPEG) or downsampled
 *    `BitmapFactory.Options` (`inSampleSize`), encrypting the thumbnail into `.LocalVaultData/thumbs/`.
 *    Even if thumbnail generation fails on an exotic format, it NEVER aborts the file import.
 * 2. Two-Pass Streaming & Temp-File `ImageDecoder` Fallback for Full Photo Viewer:
 *    Decodes standard formats via two-pass `CipherInputStream`, and if `BitmapFactory` returns null
 *    for an encrypted HEIC/HEIF container on certain OEM ROMs, streams to an ephemeral cache file,
 *    decodes via `ImageDecoder`, and immediately wipes the cache file.
 */
object ThumbnailCacheManager {
    private const val MAX_CACHE_ENTRIES = 180
    private const val THUMB_TARGET_SIZE = 320

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

    /**
     * Generates and encrypts a 320px thumbnail during import inside an isolated `try-catch` block.
     * Never throws or aborts the caller's import operation.
     */
    fun generateAndSaveSafeThumbnailDuringImport(
        context: Context,
        sourceUri: Uri,
        resolvedFsPath: String,
        mimeType: String,
        thumbFile: File,
        masterKey: SecretKey
    ) {
        try {
            val bitmap: Bitmap? = if (mimeType.startsWith("video")) {
                decodeSourceVideoThumbnail(context, sourceUri, resolvedFsPath)
            } else {
                decodeSourceImageThumbnail(context, sourceUri, resolvedFsPath)
            }
            if (bitmap != null) {
                saveEncryptedThumbFile(bitmap, thumbFile, masterKey)
            }
        } catch (_: Throwable) {
            // Never abort import even if thumbnail generation fails
        }
    }

    private fun decodeSourceImageThumbnail(
        context: Context,
        sourceUri: Uri,
        resolvedFsPath: String
    ): Bitmap? {
        // Prefer ImageDecoder on Android 9+ (API 28+) as it natively decodes HEIC, HEIF, WEBP, PNG, BMP, GIF, JPG
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                    ImageDecoder.createSource(File(resolvedFsPath))
                } else {
                    ImageDecoder.createSource(context.contentResolver, sourceUri)
                }
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > 0 && h > 0) {
                        var sample = 1
                        while (w / (sample * 2) >= THUMB_TARGET_SIZE && h / (sample * 2) >= THUMB_TARGET_SIZE) {
                            sample *= 2
                        }
                        decoder.setTargetSampleSize(sample)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Fallback to two-pass BitmapFactory with inSampleSize
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                BitmapFactory.decodeFile(resolvedFsPath, bounds)
            } else {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= THUMB_TARGET_SIZE &&
                bounds.outHeight / (sampleSize * 2) >= THUMB_TARGET_SIZE
            ) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                BitmapFactory.decodeFile(resolvedFsPath, opts)
            } else {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSourceVideoThumbnail(
        context: Context,
        sourceUri: Uri,
        resolvedFsPath: String
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                retriever.setDataSource(resolvedFsPath)
            } else {
                retriever.setDataSource(context, sourceUri)
            }
            extractBestVideoFrame(retriever)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Extracts a clear mid-video thumbnail avoiding black fade-in opening frames:
     * 1. Queries `METADATA_KEY_DURATION`.
     * 2. Picks primary timestamp at 30% of duration (or 50% for short <= 4s clips).
     * 3. Checks luminance across a 10x10 pixel grid (`isBitmapMostlyBlack`); if too dark (< 18),
     *    falls back to 50% then 70% of duration.
     */
    private fun extractBestVideoFrame(
        retriever: MediaMetadataRetriever,
        knownDurationMs: Long = 0L
    ): Bitmap? {
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: knownDurationMs

        var bestDarkFallback: Bitmap? = null
        var chosenFrame: Bitmap? = null

        if (durationMs > 0L) {
            val fractions = if (durationMs > 4000L) {
                doubleArrayOf(0.30, 0.50, 0.70)
            } else {
                doubleArrayOf(0.50, 0.30, 0.70)
            }

            for (fraction in fractions) {
                val timeUs = (durationMs * fraction * 1000.0).toLong().coerceAtLeast(0L)
                val frame = try {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Throwable) {
                    null
                }
                if (frame != null) {
                    if (!isBitmapMostlyBlack(frame)) {
                        chosenFrame = frame
                        break
                    } else if (bestDarkFallback == null) {
                        bestDarkFallback = frame
                    } else {
                        frame.recycle()
                    }
                }
            }
        }

        if (chosenFrame == null && bestDarkFallback == null) {
            chosenFrame = try {
                retriever.getFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            } catch (_: Throwable) {
                null
            }
        }

        val finalRaw = chosenFrame ?: bestDarkFallback ?: return null
        if (chosenFrame != null && bestDarkFallback != null && bestDarkFallback !== chosenFrame) {
            try {
                bestDarkFallback.recycle()
            } catch (_: Throwable) {
            }
        }

        val maxDim = THUMB_TARGET_SIZE.toFloat()
        val ratio = minOf(maxDim / finalRaw.width, maxDim / finalRaw.height, 1f)
        val w = (finalRaw.width * ratio).toInt().coerceAtLeast(1)
        val h = (finalRaw.height * ratio).toInt().coerceAtLeast(1)
        return if (w == finalRaw.width && h == finalRaw.height) {
            finalRaw
        } else {
            val scaled = Bitmap.createScaledBitmap(finalRaw, w, h, true)
            if (scaled !== finalRaw) {
                try {
                    finalRaw.recycle()
                } catch (_: Throwable) {
                }
            }
            scaled
        }
    }

    /**
     * Samples a 10x10 grid across the bitmap and returns true if average pixel luminance < 18.
     */
    fun isBitmapMostlyBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return true

        var luminanceSum = 0.0
        var sampleCount = 0
        for (gx in 0 until 10) {
            val x = ((gx + 0.5f) * w / 10f).toInt().coerceIn(0, w - 1)
            for (gy in 0 until 10) {
                val y = ((gy + 0.5f) * h / 10f).toInt().coerceIn(0, h - 1)
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                luminanceSum += luminance
                sampleCount++
            }
        }
        val avgLuminance = if (sampleCount > 0) luminanceSum / sampleCount else 0.0
        return avgLuminance < 18.0
    }

    private fun saveEncryptedThumbFile(bitmap: Bitmap, thumbFile: File, masterKey: SecretKey) {
        try {
            thumbFile.parentFile?.mkdirs()
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
            val encrypted = CryptoEngine.encryptAesGcm(baos.toByteArray(), masterKey)
            thumbFile.writeBytes(encrypted)
        } catch (_: Throwable) {
        }
    }

    private fun loadEncryptedThumbFile(thumbFile: File, masterKey: SecretKey): Bitmap? {
        if (!thumbFile.exists() || thumbFile.length() == 0L) return null
        return try {
            val decrypted = CryptoEngine.decryptAesGcm(thumbFile.readBytes(), masterKey)
            BitmapFactory.decodeByteArray(decrypted, 0, decrypted.size)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun loadThumbnail(
        item: VaultItem,
        blobFile: File,
        masterKey: SecretKey
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        getCached(item.id)?.let { return@withContext it }
        if (!blobFile.exists() || blobFile.length() == 0L) return@withContext null

        val thumbsDir = File(blobFile.parentFile?.parentFile ?: blobFile.parentFile, "thumbs")
        val thumbFile = File(thumbsDir, "${item.encryptedRelativePath}.thumb")

        // 1. Check pre-generated encrypted disk thumbnail first
        var bitmap: Bitmap? = loadEncryptedThumbFile(thumbFile, masterKey)

        // 2. If not cached on disk, decode from the encrypted blob and persist to thumbsDir
        if (bitmap == null) {
            val baseIv = fromHex(item.baseIvHex)
            bitmap = try {
                if (item.mimeType.startsWith("video")) {
                    decodeEncryptedVideoThumbnail(blobFile, masterKey, baseIv, item.durationMs)
                } else {
                    decodeEncryptedImageThumbnail(blobFile, masterKey, baseIv)
                }
            } catch (_: Throwable) {
                null
            }
            if (bitmap != null) {
                saveEncryptedThumbFile(bitmap, thumbFile, masterKey)
            }
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

    /**
     * Background migration pass run after vault unlock:
     * Inspects existing video thumbnails in `.LocalVaultData/thumbs/` and regenerates any missing
     * or mostly-black fade-in thumbnails using smart mid-video frame extraction (30%/50%/70%).
     */
    suspend fun regenerateDarkOrMissingVideoThumbnails(
        items: List<VaultItem>,
        vaultBlobsDir: File,
        vaultThumbsDir: File,
        masterKey: SecretKey
    ) = withContext(Dispatchers.IO) {
        val videos = items.filter { it.mimeType.startsWith("video") }
        for (video in videos) {
            try {
                val blobFile = File(vaultBlobsDir, video.encryptedRelativePath)
                if (!blobFile.exists() || blobFile.length() == 0L) continue
                val thumbFile = File(vaultThumbsDir, "${video.encryptedRelativePath}.thumb")
                val existing = loadEncryptedThumbFile(thumbFile, masterKey)
                val needsRegen = existing == null || isBitmapMostlyBlack(existing)
                val existingWasDark = existing != null && isBitmapMostlyBlack(existing)
                existing?.recycle()

                if (needsRegen) {
                    val baseIv = fromHex(video.baseIvHex)
                    val regenerated = decodeEncryptedVideoThumbnail(
                        blobFile = blobFile,
                        masterKey = masterKey,
                        baseIv = baseIv,
                        knownDurationMs = video.durationMs
                    )
                    if (regenerated != null && (!existingWasDark || !isBitmapMostlyBlack(regenerated))) {
                        saveEncryptedThumbFile(regenerated, thumbFile, masterKey)
                        synchronized(memoryCache) {
                            memoryCache.remove(video.id)
                        }
                    }
                    regenerated?.recycle()
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeEncryptedImageThumbnail(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray
    ): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        createDecryptStream(blobFile, masterKey, baseIv).use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
            var sampleSize = 1
            while (boundsOptions.outWidth / (sampleSize * 2) >= THUMB_TARGET_SIZE &&
                boundsOptions.outHeight / (sampleSize * 2) >= THUMB_TARGET_SIZE
            ) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = createDecryptStream(blobFile, masterKey, baseIv).use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
            if (decoded != null) return decoded
        }

        // Fallback for HEIC/HEIF streams that require seekable file source via ImageDecoder (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return decodeViaTempImageDecoder(blobFile, masterKey, baseIv, THUMB_TARGET_SIZE)
        }
        return null
    }

    suspend fun decodeFullPhotoAsync(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray,
        maxDimension: Int = 2560
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!blobFile.exists() || blobFile.length() == 0L) return@withContext null
        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            createDecryptStream(blobFile, masterKey, baseIv).use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            if (boundsOptions.outWidth > 0 && boundsOptions.outHeight > 0) {
                var sampleSize = 1
                while (boundsOptions.outWidth / sampleSize > maxDimension ||
                    boundsOptions.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                val decoded = createDecryptStream(blobFile, masterKey, baseIv).use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions)
                }
                if (decoded != null) return@withContext decoded
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return@withContext decodeViaTempImageDecoder(blobFile, masterKey, baseIv, maxDimension)
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeViaTempImageDecoder(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray,
        targetMaxDim: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val tempFile = File(blobFile.parentFile, ".tmp_dec_${System.nanoTime()}")
        return try {
            createDecryptStream(blobFile, masterKey, baseIv).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buf = ByteArray(65536)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        output.write(buf, 0, r)
                    }
                    output.flush()
                }
            }
            val source = ImageDecoder.createSource(tempFile)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (w > 0 && h > 0) {
                    var sample = 1
                    while (w / sample > targetMaxDim || h / sample > targetMaxDim) {
                        sample *= 2
                    }
                    decoder.setTargetSampleSize(sample)
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createDecryptStream(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray
    ): BufferedInputStream {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(baseIv))
        return BufferedInputStream(CipherInputStream(FileInputStream(blobFile), cipher), 65536)
    }

    private fun decodeEncryptedVideoThumbnail(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray,
        knownDurationMs: Long = 0L
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
            extractBestVideoFrame(retriever, knownDurationMs)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
            try {
                dataSource.close()
            } catch (_: Throwable) {
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
