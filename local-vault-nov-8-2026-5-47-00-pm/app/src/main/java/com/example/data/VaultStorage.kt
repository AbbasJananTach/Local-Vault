package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.crypto.CryptoEngine
import com.example.data.db.FolderDao
import com.example.data.db.FolderItem
import com.example.data.db.VaultDao
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

data class DeviceMediaItem(
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val isVideo: Boolean,
    val folderName: String
)

class VaultStorage(private val context: Context) {

    // Dynamically resolve DAOs from VaultDatabase.getInstance(context) so that if All Files Access
    // is granted at runtime, queries immediately target external .LocalVaultData/local_vault.db
    private val vaultDao: VaultDao
        get() = VaultDatabase.getInstance(context).vaultDao()

    private val folderDao: FolderDao
        get() = VaultDatabase.getInstance(context).folderDao()

    val vaultRootDir: File
        get() {
            val extDir = Environment.getExternalStorageDirectory()
            val primaryDir = File(extDir, ".LocalVaultData")
            if (!primaryDir.exists()) primaryDir.mkdirs()
            if (primaryDir.exists() && primaryDir.canWrite()) return primaryDir

            val fallbackDir = File(context.getExternalFilesDir(null) ?: context.filesDir, ".LocalVaultData")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            return fallbackDir
        }

    val vaultBlobsDir: File
        get() {
            val blobsDir = File(vaultRootDir, "blobs")
            if (!blobsDir.exists()) blobsDir.mkdirs()
            return blobsDir
        }

    private val catalogFile: File
        get() = File(vaultRootDir, "vault_catalog.enc")

    /**
     * Queries all device media files directly from MediaStore with real filesystem paths.
     * Excludes anything already inside the .LocalVaultData folder.
     */
    suspend fun queryDeviceMediaFiles(folderFilter: String? = null): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DeviceMediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )

        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)" +
            " AND ${MediaStore.MediaColumns.DATA} NOT LIKE '%/.LocalVaultData/%'"

        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val typeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    val file = File(path)
                    if (path.isNotEmpty() && file.exists() && file.isFile && file.length() > 0) {
                        val folderName = if (bucketCol != -1) cursor.getString(bucketCol) ?: file.parentFile?.name ?: "Other" else file.parentFile?.name ?: "Other"
                        if (folderFilter == null || folderName.equals(folderFilter, ignoreCase = true)) {
                            val id = if (idCol != -1) cursor.getLong(idCol) else 0L
                            val name = if (nameCol != -1) cursor.getString(nameCol) ?: file.name else file.name
                            val size = if (sizeCol != -1) cursor.getLong(sizeCol) else file.length()
                            val dateMod = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else file.lastModified()
                            val isVideo = if (typeCol != -1) cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else false

                            result.add(
                                DeviceMediaItem(
                                    id = id,
                                    path = path,
                                    name = name,
                                    size = size,
                                    dateModified = dateMod,
                                    isVideo = isVideo,
                                    folderName = folderName
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return@withContext result
    }

    /**
     * Returns distinct device folders containing media files.
     */
    suspend fun queryDeviceFolders(): List<String> = withContext(Dispatchers.IO) {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)" +
            " AND ${MediaStore.MediaColumns.DATA} NOT LIKE '%/.LocalVaultData/%'"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    if (path.isNotEmpty() && File(path).exists()) {
                        val bName = if (bucketCol != -1) cursor.getString(bucketCol) else null
                        if (!bName.isNullOrBlank()) {
                            folders.add(bName)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return@withContext folders.sorted()
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
        var mediaStoreId: Long? = null

        // 1. Resolve metadata from URI or direct file
        if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
            val f = File(resolvedFsPath)
            fileName = f.name
            reportedSize = f.length()
            if (mimeType.isBlank() || mimeType == "application/octet-stream") {
                mimeType = guessMimeTypeFromExtension(fileName)
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: ""
            if (path.isNotEmpty() && File(path).exists()) {
                resolvedFsPath = path
                val f = File(path)
                fileName = f.name
                reportedSize = f.length()
            }
        } else if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
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
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (dataIdx != -1 && !cursor.isNull(dataIdx)) {
                            val candidate = cursor.getString(dataIdx) ?: ""
                            if (candidate.isNotEmpty() && File(candidate).exists()) {
                                resolvedFsPath = candidate
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. If real filesystem path is not known yet, use All Files access & MediaStore to resolve it
        if (resolvedFsPath.isEmpty() || !File(resolvedFsPath).exists()) {
            val resolved = resolveRealPathAndId(uri, fileName, reportedSize)
            if (resolved.first != null) {
                resolvedFsPath = resolved.first!!
            }
            if (resolved.second != null) {
                mediaStoreId = resolved.second
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

        // 3. Extract video duration before encrypting and deleting original file
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
            } catch (_: Exception) {
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
            inputStream = if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                FileInputStream(File(resolvedFsPath))
            } else {
                resolver.openInputStream(uri)
            }

            if (inputStream == null) {
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
            val savedItem = item.copy(id = insertedId)

            // Write encrypted sidecar metadata (.meta) and update encrypted catalog for 100% DB recovery
            writeSidecarMetadata(savedItem, masterKey)
            syncEncryptedCatalog(masterKey)

            // 4. GUARANTEED SILENT DELETION: Delete physical file, MediaStore entries, and trigger MediaScanner
            deleteOriginalFileSafely(
                uri = uri,
                fsPath = resolvedFsPath,
                fileName = fileName,
                reportedSize = savedItem.fileSize,
                mediaStoreId = mediaStoreId,
                resolver = resolver
            )

            return@withContext savedItem
        } catch (_: Exception) {
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

    /**
     * Architectural Redundancy Fix (Sidecar + Encrypted Catalog):
     * Writes an AES-GCM encrypted `.meta` sidecar file alongside each `.bin` blob AND maintains
     * a synced encrypted catalog (`vault_catalog.enc`) inside `.LocalVaultData/`.
     * If `local_vault.db` is ever deleted, reset, or corrupted, `reconcileAndRecoverCatalog(masterKey)`
     * automatically reconstructs all `FolderItem` and `VaultItem` entries upon unlock.
     */
    fun writeSidecarMetadata(item: VaultItem, masterKey: SecretKey) {
        try {
            val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
            val json = JSONObject().apply {
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
            val encryptedBytes = CryptoEngine.encryptAesGcm(json.toString().toByteArray(Charsets.UTF_8), masterKey)
            metaFile.writeBytes(encryptedBytes)
        } catch (_: Exception) {
        }
    }

    private fun readSidecarMetadata(metaFile: File, masterKey: SecretKey): VaultItem? {
        return try {
            val encryptedBytes = metaFile.readBytes()
            val plainBytes = CryptoEngine.decryptAesGcm(encryptedBytes, masterKey)
            val obj = JSONObject(String(plainBytes, Charsets.UTF_8))
            val rawFolderId = obj.optLong("folderId", -1L)
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
        } catch (_: Exception) {
            null
        }
    }

    suspend fun syncEncryptedCatalog(masterKey: SecretKey) = withContext(Dispatchers.IO) {
        try {
            val allFolders = folderDao.getAllFoldersSync()
            val allItems = vaultDao.getAllItemsSync()

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
            allItems.forEach { item ->
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

            val rootJson = JSONObject().apply {
                put("folders", foldersJson)
                put("items", itemsJson)
            }
            val encrypted = CryptoEngine.encryptAesGcm(rootJson.toString().toByteArray(Charsets.UTF_8), masterKey)
            catalogFile.writeBytes(encrypted)
        } catch (_: Exception) {
        }
    }

    /**
     * Reconstructs any missing `FolderItem` or `VaultItem` rows in `VaultDatabase` from
     * `vault_catalog.enc` and `.meta` sidecar files, and ensures every existing DB item has
     * a `.meta` sidecar written on disk.
     */
    suspend fun reconcileAndRecoverCatalog(masterKey: SecretKey) = withContext(Dispatchers.IO) {
        try {
            val existingItems = vaultDao.getAllItemsSync()
            val existingBlobNames = existingItems.map { it.encryptedRelativePath }.toMutableSet()
            val existingFolders = folderDao.getAllFoldersSync()
            val existingFolderIds = existingFolders.map { it.id }.toMutableSet()

            // 1. Recover from vault_catalog.enc if present
            if (catalogFile.exists() && catalogFile.length() > 0L) {
                try {
                    val decrypted = CryptoEngine.decryptAesGcm(catalogFile.readBytes(), masterKey)
                    val root = JSONObject(String(decrypted, Charsets.UTF_8))
                    val foldersArr = root.optJSONArray("folders")
                    if (foldersArr != null) {
                        for (i in 0 until foldersArr.length()) {
                            val fObj = foldersArr.getJSONObject(i)
                            val fId = fObj.optLong("id", 0L)
                            if (fId !in existingFolderIds) {
                                folderDao.insertFolder(
                                    FolderItem(
                                        id = fId,
                                        folderName = fObj.getString("folderName"),
                                        isLocked = fObj.optBoolean("isLocked", false),
                                        lockType = fObj.optString("lockType", "MAIN_PIN"),
                                        customPinSaltHex = fObj.optString("customPinSaltHex", "").ifEmpty { null },
                                        customPinSlotAHex = fObj.optString("customPinSlotAHex", "").ifEmpty { null },
                                        isDecoy = fObj.optBoolean("isDecoy", false)
                                    )
                                )
                                existingFolderIds.add(fId)
                            }
                        }
                    }

                    val itemsArr = root.optJSONArray("items")
                    if (itemsArr != null) {
                        for (i in 0 until itemsArr.length()) {
                            val obj = itemsArr.getJSONObject(i)
                            val relPath = obj.getString("encryptedRelativePath")
                            val blobFile = File(vaultBlobsDir, relPath)
                            if (relPath !in existingBlobNames && blobFile.exists()) {
                                val rawFolderId = obj.optLong("folderId", -1L)
                                val recovered = VaultItem(
                                    id = obj.optLong("id", 0L),
                                    fileName = obj.getString("fileName"),
                                    originalPath = obj.optString("originalPath", ""),
                                    encryptedRelativePath = relPath,
                                    mimeType = obj.optString("mimeType", "image/jpeg"),
                                    fileSize = obj.optLong("fileSize", blobFile.length()),
                                    dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                                    folderId = if (rawFolderId == -1L) null else rawFolderId,
                                    isFavorite = obj.optBoolean("isFavorite", false),
                                    isDecoy = obj.optBoolean("isDecoy", false),
                                    durationMs = obj.optLong("durationMs", 0L),
                                    baseIvHex = obj.getString("baseIvHex")
                                )
                                vaultDao.insertItem(recovered)
                                existingBlobNames.add(relPath)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Scan blobs directory for any .meta sidecar files not yet in the database
            vaultBlobsDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".bin.meta")) {
                    val blobName = file.name.removeSuffix(".meta")
                    val blobFile = File(vaultBlobsDir, blobName)
                    if (blobName !in existingBlobNames && blobFile.exists()) {
                        val recovered = readSidecarMetadata(file, masterKey)
                        if (recovered != null) {
                            vaultDao.insertItem(recovered)
                            existingBlobNames.add(blobName)
                        }
                    }
                }
            }

            // 3. Ensure all current DB items have up-to-date .meta sidecars and catalog
            vaultDao.getAllItemsSync().forEach { item ->
                val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
                if (!metaFile.exists()) {
                    writeSidecarMetadata(item, masterKey)
                }
            }
            syncEncryptedCatalog(masterKey)
        } catch (_: Exception) {
        }
    }

    private fun resolveRealPathAndId(uri: Uri, fileName: String, reportedSize: Long): Pair<String?, Long?> {
        // A. Document URI resolution
        if (DocumentsContract.isDocumentUri(context, uri)) {
            try {
                val docId = DocumentsContract.getDocumentId(uri)
                if (docId.startsWith("raw:")) {
                    val p = docId.removePrefix("raw:")
                    if (File(p).exists()) return Pair(p, null)
                }
                if (docId.startsWith("primary:")) {
                    val rel = docId.removePrefix("primary:")
                    val candidate = File(Environment.getExternalStorageDirectory(), rel)
                    if (candidate.exists()) return Pair(candidate.absolutePath, null)
                }
                if (docId.contains(":")) {
                    val parts = docId.split(":")
                    val type = parts[0]
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    if (id != null) {
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        val path = queryPathFromMediaStore(contentUri, id)
                        if (path != null) return Pair(path, id)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // B. Path/ID from MediaStore by segment (handles Android Photo Picker URIs like .../picker/0/com.android.providers.media.photopicker/media/<id>)
        val lastSeg = uri.lastPathSegment?.toLongOrNull()
        if (lastSeg != null) {
            val path = queryPathFromMediaStore(MediaStore.Files.getContentUri("external"), lastSeg)
                ?: queryPathFromMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, lastSeg)
                ?: queryPathFromMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, lastSeg)
            if (path != null) return Pair(path, lastSeg)
        }

        // C. Match by DISPLAY_NAME and reported SIZE in MediaStore
        if (fileName.isNotEmpty()) {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE
            )
            val selection = if (reportedSize > 0) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
            } else {
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            }
            val args = if (reportedSize > 0) arrayOf(fileName, reportedSize.toString()) else arrayOf(fileName)

            try {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    args,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                        val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        val id = if (idIdx != -1) cursor.getLong(idIdx) else null
                        val path = if (dataIdx != -1) cursor.getString(dataIdx) else null
                        if (path != null && File(path).exists()) {
                            return Pair(path, id)
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // D. Fallback check common standard directories on external storage
            val ext = Environment.getExternalStorageDirectory()
            val candidateDirs = listOf(
                File(ext, "DCIM/Camera"),
                File(ext, "Pictures/Screenshots"),
                File(ext, "Pictures"),
                File(ext, "Download"),
                File(ext, "DCIM"),
                File(ext, "Movies"),
                File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"),
                File(ext, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"),
                File(ext, "WhatsApp/Media/WhatsApp Images"),
                File(ext, "WhatsApp/Media/WhatsApp Video")
            )

            for (dir in candidateDirs) {
                val candidateFile = File(dir, fileName)
                if (candidateFile.exists() && candidateFile.isFile) {
                    if (reportedSize <= 0 || candidateFile.length() == reportedSize) {
                        return Pair(candidateFile.absolutePath, null)
                    }
                }
            }
        }

        return Pair(null, null)
    }

    private fun queryPathFromMediaStore(contentUri: Uri, id: Long): String? {
        return try {
            context.contentResolver.query(
                contentUri,
                arrayOf(MediaStore.MediaColumns.DATA),
                "${MediaStore.Files.FileColumns._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx != -1) {
                        val p = cursor.getString(idx)
                        if (!p.isNullOrEmpty() && File(p).exists()) p else null
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Guaranteed Original File Wipe:
     * 1. Physically deletes the source `File(fsPath)` using `MANAGE_EXTERNAL_STORAGE`.
     * 2. Purges matching rows from `MediaStore.Images`, `MediaStore.Video`, and `MediaStore.Files`
     *    by `_ID`, by `DATA` path, and by `DISPLAY_NAME` + `SIZE` fallback if `resolver.delete(uri)`
     *    throws `RecoverableSecurityException` or fails on a read-only SAF/PhotoPicker URI.
     * 3. Triggers `MediaScannerConnection.scanFile` so cached thumbnails disappear immediately.
     */
    private fun deleteOriginalFileSafely(
        uri: Uri,
        fsPath: String,
        fileName: String,
        reportedSize: Long,
        mediaStoreId: Long?,
        resolver: ContentResolver
    ) {
        val mediaStoreUris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )

        // 1. Physical file delete with MANAGE_EXTERNAL_STORAGE
        if (fsPath.isNotEmpty()) {
            try {
                val origFile = File(fsPath)
                if (origFile.exists() && origFile.isFile) {
                    origFile.delete()
                }
            } catch (_: Exception) {
            }
        }

        // 2. Delete MediaStore rows across Images, Video, and Files by ID
        if (mediaStoreId != null) {
            for (msUri in mediaStoreUris) {
                try {
                    resolver.delete(
                        msUri,
                        "${MediaStore.Files.FileColumns._ID} = ?",
                        arrayOf(mediaStoreId.toString())
                    )
                } catch (_: Exception) {
                }
            }
        }

        // 3. Delete MediaStore rows across Images, Video, and Files by DATA path
        if (fsPath.isNotEmpty()) {
            for (msUri in mediaStoreUris) {
                try {
                    resolver.delete(
                        msUri,
                        "${MediaStore.MediaColumns.DATA} = ?",
                        arrayOf(fsPath)
                    )
                } catch (_: Exception) {
                }
            }

            // 4. Run MediaScanner on the deleted path so Gallery and File Managers purge thumbnails
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(fsPath),
                    null,
                    null
                )
            } catch (_: Exception) {
            }
        } else if (fileName.isNotEmpty() && reportedSize > 0L) {
            // Fallback when SAF URI did not yield a direct path: query MediaStore for matching DISPLAY_NAME + SIZE,
            // delete the physical file on disk, and purge all MediaStore tables.
            for (msUri in mediaStoreUris) {
                try {
                    resolver.query(
                        msUri,
                        arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DATA),
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                        arrayOf(fileName, reportedSize.toString()),
                        null
                    )?.use { cursor ->
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        while (cursor.moveToNext()) {
                            val candidatePath = if (dataCol != -1) cursor.getString(dataCol) else null
                            if (!candidatePath.isNullOrEmpty() && !candidatePath.contains("/.LocalVaultData/")) {
                                val f = File(candidatePath)
                                if (f.exists()) f.delete()
                                MediaScannerConnection.scanFile(context, arrayOf(candidatePath), null, null)
                            }
                        }
                    }
                    resolver.delete(
                        msUri,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                        arrayOf(fileName, reportedSize.toString())
                    )
                } catch (_: Exception) {
                }
            }
        }

        // 5. Try URI delete as final fallback (catching any SecurityException / RecoverableSecurityException)
        try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                resolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
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
            File(vaultBlobsDir, "${item.encryptedRelativePath}.meta").delete()
            vaultDao.deleteItem(item)
            syncEncryptedCatalog(masterKey)
            ThumbnailCacheManager.clearCache()

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(item.mimeType),
                null
            )
            return@withContext true
        } catch (_: Exception) {
            return@withContext false
        }
    }

    suspend fun deletePermanently(item: VaultItem, masterKey: SecretKey? = null): Boolean = withContext(Dispatchers.IO) {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (blobFile.exists()) {
            blobFile.delete()
        }
        val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
        if (metaFile.exists()) {
            metaFile.delete()
        }
        vaultDao.deleteItem(item)
        if (masterKey != null) {
            syncEncryptedCatalog(masterKey)
        }
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
                dos.writeBytes("LVB1")
                val headerBytes = headerText.toByteArray(Charsets.UTF_8)
                dos.writeInt(headerBytes.size)
                dos.write(headerBytes)

                val foldersBytes = foldersJson.toString().toByteArray(Charsets.UTF_8)
                dos.writeInt(foldersBytes.size)
                dos.write(foldersBytes)

                val itemsBytes = itemsJson.toString().toByteArray(Charsets.UTF_8)
                dos.writeInt(itemsBytes.size)
                dos.write(itemsBytes)

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
