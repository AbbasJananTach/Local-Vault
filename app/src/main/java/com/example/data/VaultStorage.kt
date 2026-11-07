package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.crypto.CryptoEngine
import com.example.data.db.FolderItem
import com.example.data.db.VaultDatabase
import com.example.data.db.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
    private val folderDao = db.folderDao()

    val vaultBlobsDir: File
        get() {
            val extDir = Environment.getExternalStorageDirectory()
            val primaryDir = File(extDir, ".LocalVaultData/blobs")
            if (!primaryDir.exists()) primaryDir.mkdirs()
            if (primaryDir.exists() && primaryDir.canWrite()) return primaryDir

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
        var fileName = ""
        var reportedSize = 0L
        var mimeType = resolver.getType(uri) ?: ""
        var resolvedFsPath = originalPathOverride ?: ""

        // 1. Extract DISPLAY_NAME and SIZE via OpenableColumns for content:// URIs
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                            fileName = cursor.getString(nameIdx) ?: ""
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                            reportedSize = cursor.getLong(sizeIdx)
                        }
                        if (resolvedFsPath.isEmpty()) {
                            val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIdx != -1 && !cursor.isNull(dataIdx)) {
                                resolvedFsPath = cursor.getString(dataIdx) ?: ""
                            } else {
                                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                                if (relIdx != -1 && !cursor.isNull(relIdx) && fileName.isNotEmpty()) {
                                    val rel = cursor.getString(relIdx) ?: ""
                                    if (rel.isNotEmpty()) {
                                        val candidate = File(Environment.getExternalStorageDirectory(), "$rel$fileName")
                                        if (candidate.exists()) {
                                            resolvedFsPath = candidate.absolutePath
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("VaultStorage", "OpenableColumns query warning", e)
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: ""
            if (path.isNotEmpty()) {
                resolvedFsPath = path
                val f = File(path)
                fileName = f.name
                reportedSize = f.length()
            }
        }

        if (fileName.isEmpty() && resolvedFsPath.isNotEmpty()) {
            fileName = File(resolvedFsPath).name
        }
        if (fileName.isEmpty()) {
            val ext = when {
                mimeType.startsWith("video") -> ".mp4"
                mimeType.endsWith("png") -> ".png"
                mimeType.endsWith("webp") -> ".webp"
                else -> ".jpg"
            }
            fileName = "Media_${System.currentTimeMillis()}$ext"
        }

        if (mimeType.isBlank() || mimeType == "application/octet-stream") {
            mimeType = guessMimeTypeFromExtension(fileName)
        }

        // 2. Extract video duration from source URI BEFORE encrypting & deleting original
        var durationMs = 0L
        if (mimeType.startsWith("video")) {
            val retriever = MediaMetadataRetriever()
            try {
                if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                    retriever.setDataSource(resolvedFsPath)
                } else {
                    retriever.setDataSource(context, uri)
                }
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                Log.w("VaultStorage", "Failed to extract video duration from source", e)
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

        val blobName = "${UUID.randomUUID()}.bin"
        val blobFile = File(vaultBlobsDir, blobName)
        val baseIv = CryptoEngine.generateRandomBytes(16)

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var totalWritten = 0L

        try {
            inputStream = resolver.openInputStream(uri)
                ?: if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                    FileInputStream(File(resolvedFsPath))
                } else null

            if (inputStream == null) {
                Log.e("VaultStorage", "Could not open input stream for $uri")
                return@withContext null
            }

            outputStream = FileOutputStream(blobFile)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(baseIv))
            val cipherOut = CipherOutputStream(outputStream, cipher)

            val buffer = ByteArray(16384)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                cipherOut.write(buffer, 0, read)
                totalWritten += read
            }
            cipherOut.flush()
            cipherOut.close()
            inputStream.close()

            if (!blobFile.exists() || blobFile.length() <= 0L) {
                blobFile.delete()
                return@withContext null
            }

            val finalOriginalPath = if (resolvedFsPath.isNotEmpty()) {
                resolvedFsPath
            } else {
                val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                File(File(dcim, "RestoredVault"), fileName).absolutePath
            }

            val item = VaultItem(
                fileName = fileName,
                originalPath = finalOriginalPath,
                encryptedRelativePath = blobName,
                mimeType = mimeType,
                fileSize = if (totalWritten > 0L) totalWritten else reportedSize,
                dateAdded = System.currentTimeMillis(),
                folderId = targetFolderId,
                isFavorite = false,
                isDecoy = isDecoy,
                durationMs = durationMs,
                baseIvHex = toHex(baseIv)
            )

            val insertedId = vaultDao.insertItem(item)

            // 3. Delete original file & MediaStore record if accessible
            deleteOriginalFileSafely(uri, resolvedFsPath, resolver)

            return@withContext item.copy(id = insertedId)
        } catch (e: Exception) {
            Log.e("VaultStorage", "Failed to import file: $uri", e)
            blobFile.delete()
            return@withContext null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                outputStream?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun deleteOriginalFileSafely(uri: Uri, fsPath: String, resolver: ContentResolver) {
        try {
            if (fsPath.isNotEmpty()) {
                val origFile = File(fsPath)
                if (origFile.exists() && origFile.isFile) {
                    val deleted = origFile.delete()
                    if (deleted) {
                        val parent = origFile.parentFile
                        if (parent != null && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                            parent.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VaultStorage", "Filesystem delete skipped: $fsPath", e)
        }

        try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                resolver.delete(uri, null, null)
            }
        } catch (e: Exception) {
            // Document / Photo Picker URIs may not support direct delete without All Files path
            Log.d("VaultStorage", "ContentResolver delete skipped for picker URI: ${e.message}")
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

        var targetFile = File(targetPath)
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        if (parentDir == null || !parentDir.canWrite()) {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val fallbackDir = File(dcim, "RestoredVault")
            fallbackDir.mkdirs()
            targetFile = File(fallbackDir, item.fileName)
        }

        try {
            FileInputStream(blobFile).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val baseIv = fromHex(item.baseIvHex)
                    CryptoEngine.decryptStreamCtr(inputStream, outputStream, masterKey, baseIv)
                    outputStream.flush()
                }
            }

            blobFile.delete()
            vaultDao.deleteItem(item)
            ThumbnailCacheManager.clearCache()

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

    suspend fun exportEncryptedBackup(destUri: Uri, headerFile: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val outStream = resolver.openOutputStream(destUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open backup output stream"))

            val allFolders = folderDao.getAllFoldersSync()
            val allItems = vaultDao.getAllItemsSync()
            val headerText = if (headerFile.exists()) headerFile.readText() else "{}"

            val foldersJson = JSONArray()
            allFolders.forEach { folder ->
                foldersJson.put(
                    JSONObject().apply {
                        put("id", folder.id)
                        put("folderName", folder.folderName)
                        put("isLocked", folder.isLocked)
                        put("lockType", folder.lockType)
                        put("customPinSaltHex", folder.customPinSaltHex ?: "")
                        put("customPinSlotAHex", folder.customPinSlotAHex ?: "")
                        put("isDecoy", folder.isDecoy)
                    }
                )
            }

            val itemsJson = JSONArray()
            val validItems = mutableListOf<Pair<VaultItem, File>>()
            allItems.forEach { item ->
                val blob = File(vaultBlobsDir, item.encryptedRelativePath)
                if (blob.exists()) {
                    validItems.add(item to blob)
                    itemsJson.put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("fileName", item.fileName)
                            put("originalPath", item.originalPath)
                            put("encryptedRelativePath", item.encryptedRelativePath)
                            put("mimeType", item.mimeType)
                            put("fileSize", item.fileSize)
                            put("dateAdded", item.dateAdded)
                            put("folderId", item.folderId ?: -1L)
                            put("isFavorite", item.isFavorite)
                            put("isDecoy", item.isDecoy)
                            put("durationMs", item.durationMs)
                            put("baseIvHex", item.baseIvHex)
                        }
                    )
                }
            }

            DataOutputStream(BufferedOutputStream(outStream)).use { dos ->
                // Magic Header
                dos.writeBytes("LVB1")
                // Vault Header JSON
                val headerBytes = headerText.toByteArray(Charsets.UTF_8)
                dos.writeInt(headerBytes.size)
                dos.write(headerBytes)

                // Folders JSON
                val foldersBytes = foldersJson.toString().toByteArray(Charsets.UTF_8)
                dos.writeInt(foldersBytes.size)
                dos.write(foldersBytes)

                // Items JSON
                val itemsBytes = itemsJson.toString().toByteArray(Charsets.UTF_8)
                dos.writeInt(itemsBytes.size)
                dos.write(itemsBytes)

                // Blobs count & binary content
                dos.writeInt(validItems.size)
                val buf = ByteArray(16384)
                for ((item, blobFile) in validItems) {
                    val nameBytes = item.encryptedRelativePath.toByteArray(Charsets.UTF_8)
                    dos.writeInt(nameBytes.size)
                    dos.write(nameBytes)
                    val len = blobFile.length()
                    dos.writeLong(len)
                    FileInputStream(blobFile).use { fis ->
                        var r: Int
                        while (fis.read(buf).also { r = it } != -1) {
                            dos.write(buf, 0, r)
                        }
                    }
                }
                dos.flush()
            }
            Result.success(validItems.size)
        } catch (e: Exception) {
            Log.e("VaultStorage", "Failed to create .lvb backup", e)
            Result.failure(e)
        }
    }

    suspend fun restoreEncryptedBackup(sourceUri: Uri, headerFile: File): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val inStream = resolver.openInputStream(sourceUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open backup file"))

            DataInputStream(BufferedInputStream(inStream)).use { dis ->
                val magic = ByteArray(4)
                dis.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "LVB1") {
                    return@withContext Result.failure(IllegalArgumentException("Invalid .lvb backup archive format"))
                }

                val headerLen = dis.readInt()
                val headerBytes = ByteArray(headerLen)
                dis.readFully(headerBytes)
                val headerJsonStr = String(headerBytes, Charsets.UTF_8)
                if (headerJsonStr.isNotBlank() && headerJsonStr != "{}") {
                    headerFile.writeText(headerJsonStr)
                }

                val foldersLen = dis.readInt()
                val foldersBytes = ByteArray(foldersLen)
                dis.readFully(foldersBytes)
                val foldersArray = JSONArray(String(foldersBytes, Charsets.UTF_8))

                for (i in 0 until foldersArray.length()) {
                    val obj = foldersArray.getJSONObject(i)
                    folderDao.insertFolder(
                        FolderItem(
                            id = obj.optLong("id", 0L),
                            folderName = obj.getString("folderName"),
                            isLocked = obj.optBoolean("isLocked", false),
                            lockType = obj.optString("lockType", "MAIN_PIN"),
                            customPinSaltHex = obj.optString("customPinSaltHex", "").ifEmpty { null },
                            customPinSlotAHex = obj.optString("customPinSlotAHex", "").ifEmpty { null },
                            isDecoy = obj.optBoolean("isDecoy", false)
                        )
                    )
                }

                val itemsLen = dis.readInt()
                val itemsBytes = ByteArray(itemsLen)
                dis.readFully(itemsBytes)
                val itemsArray = JSONArray(String(itemsBytes, Charsets.UTF_8))

                val blobCount = dis.readInt()
                val buf = ByteArray(16384)
                for (i in 0 until blobCount) {
                    val nameLen = dis.readInt()
                    val nameBytes = ByteArray(nameLen)
                    dis.readFully(nameBytes)
                    val blobName = String(nameBytes, Charsets.UTF_8)
                    val blobSize = dis.readLong()

                    val targetBlob = File(vaultBlobsDir, blobName)
                    FileOutputStream(targetBlob).use { fos ->
                        var remaining = blobSize
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = dis.read(buf, 0, toRead)
                            if (read == -1) break
                            fos.write(buf, 0, read)
                            remaining -= read
                        }
                        fos.flush()
                    }
                }

                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.getJSONObject(i)
                    val rawFolderId = obj.optLong("folderId", -1L)
                    vaultDao.insertItem(
                        VaultItem(
                            id = obj.optLong("id", 0L),
                            fileName = obj.getString("fileName"),
                            originalPath = obj.optString("originalPath", ""),
                            encryptedRelativePath = obj.getString("encryptedRelativePath"),
                            mimeType = obj.optString("mimeType", "image/jpeg"),
                            fileSize = obj.optLong("fileSize", 0L),
                            dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                            folderId = if (rawFolderId == -1L) null else rawFolderId,
                            isFavorite = obj.optBoolean("isFavorite", false),
                            isDecoy = obj.optBoolean("isDecoy", false),
                            durationMs = obj.optLong("durationMs", 0L),
                            baseIvHex = obj.getString("baseIvHex")
                        )
                    )
                }

                return@withContext Result.success(itemsArray.length())
            }
        } catch (e: Exception) {
            Log.e("VaultStorage", "Failed to restore .lvb backup", e)
            Result.failure(e)
        }
    }

    private fun guessMimeTypeFromExtension(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
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
