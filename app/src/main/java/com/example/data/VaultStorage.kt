package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.crypto.CryptoEngine
import com.example.data.db.VaultDatabase
import com.example.data.db.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

class VaultStorage(private val context: Context) {

    private val db = VaultDatabase.getInstance(context)
    private val vaultDao = db.vaultDao()

    val vaultBlobsDir: File
        get() {
            val extDir = Environment.getExternalStorageDirectory()
            val primaryDir = File(extDir, ".LocalVaultData/blobs")
            if (!primaryDir.exists()) primaryDir.mkdirs()
            if (primaryDir.exists() || primaryDir.canWrite()) return primaryDir

            val fallbackDir = File(context.getExternalFilesDir(null) ?: context.filesDir, ".LocalVaultData/blobs")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            return fallbackDir
        }

    suspend fun importFile(
        uri: Uri,
        originalPathOverride: String? = null,
        targetFolderId: Long? = null,
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): VaultItem? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var fileName = "media_${System.currentTimeMillis()}"
        var mimeType = resolver.getType(uri) ?: "application/octet-stream"
        var originalPath = originalPathOverride ?: ""

        // Resolve original path and file name from Uri if not provided
        if (originalPath.isEmpty()) {
            originalPath = getPathFromUri(resolver, uri) ?: uri.path ?: ""
        }
        if (originalPath.isNotEmpty()) {
            fileName = File(originalPath).name
        }

        if (mimeType == "application/octet-stream" || mimeType.isBlank()) {
            mimeType = guessMimeTypeFromExtension(fileName)
        }

        val blobName = "${UUID.randomUUID()}.bin"
        val blobFile = File(vaultBlobsDir, blobName)
        val baseIv = CryptoEngine.generateRandomBytes(16)

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var totalWritten = 0L

        try {
            inputStream = resolver.openInputStream(uri) ?: FileInputStream(File(originalPath))
            outputStream = FileOutputStream(blobFile)

            // We encrypt blob using AES-CTR with baseIv for random access playback
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(baseIv))
            val cipherOut = CipherOutputStream(outputStream, cipher)

            val buffer = ByteArray(8192)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                cipherOut.write(buffer, 0, read)
                totalWritten += read
            }
            cipherOut.flush()
            cipherOut.close()
            inputStream.close()

            // Verify written size
            if (blobFile.length() <= 0) {
                blobFile.delete()
                return@withContext null
            }

            // Extract duration for video
            var durationMs = 0L
            if (mimeType.startsWith("video")) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(blobFile.absolutePath)
                    val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durStr?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (e: Exception) {
                    Log.w("VaultStorage", "Failed to extract video duration", e)
                }
            }

            val item = VaultItem(
                fileName = fileName,
                originalPath = originalPath,
                encryptedRelativePath = blobName,
                mimeType = mimeType,
                fileSize = totalWritten,
                dateAdded = System.currentTimeMillis(),
                folderId = targetFolderId,
                isFavorite = false,
                isDecoy = isDecoy,
                durationMs = durationMs,
                baseIvHex = toHex(baseIv)
            )

            val insertedId = vaultDao.insertItem(item)

            // Silent deletion of original file & MediaStore entry for zero public trace
            deleteOriginalFile(uri, originalPath, resolver)

            return@withContext item.copy(id = insertedId)
        } catch (e: Exception) {
            Log.e("VaultStorage", "Failed to import file: $originalPath", e)
            blobFile.delete()
            return@withContext null
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { outputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun deleteOriginalFile(uri: Uri, path: String, resolver: ContentResolver) {
        try {
            if (path.isNotEmpty()) {
                val origFile = File(path)
                if (origFile.exists()) {
                    origFile.delete()
                    val parent = origFile.parentFile
                    if (parent != null && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                        parent.delete()
                    }
                }
            }
            // Delete from MediaStore
            resolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w("VaultStorage", "Silent delete of original file failed: $path", e)
        }
    }

    suspend fun unhideFile(item: VaultItem, masterKey: SecretKey): Boolean = withContext(Dispatchers.IO) {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (!blobFile.exists()) return@withContext false

        var targetPath = item.originalPath
        if (targetPath.isEmpty()) {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val restoredDir = File(dcim, "RestoredVault")
            if (!restoredDir.exists()) restoredDir.mkdirs()
            targetPath = File(restoredDir, item.fileName).absolutePath
        }

        val targetFile = File(targetPath)
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        try {
            val inputStream = FileInputStream(blobFile)
            val outputStream = FileOutputStream(targetFile)
            val baseIv = fromHex(item.baseIvHex)

            CryptoEngine.decryptStreamCtr(inputStream, outputStream, masterKey, baseIv)

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Delete encrypted blob
            blobFile.delete()
            // Delete record from DB
            vaultDao.deleteItem(item)

            // Scan restored file back into MediaStore
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(item.mimeType),
                null
            )
            return@withContext true
        } catch (e: Exception) {
            Log.e("VaultStorage", "Failed to unhide file: ${item.fileName}", e)
            return@withContext false
        }
    }

    suspend fun deletePermanently(item: VaultItem): Boolean = withContext(Dispatchers.IO) {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (blobFile.exists()) {
            blobFile.delete()
        }
        vaultDao.deleteItem(item)
        return@withContext true
    }

    private fun getPathFromUri(resolver: ContentResolver, uri: Uri): String? {
        val proj = arrayOf(MediaStore.MediaColumns.DATA)
        resolver.query(uri, proj, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (idx != -1) return cursor.getString(idx)
            }
        }
        return uri.path
    }

    private fun guessMimeTypeFromExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkdir", "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "image/jpeg"
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
