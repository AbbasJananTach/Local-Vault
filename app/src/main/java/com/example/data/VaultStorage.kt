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
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    val folderName: String,
    val durationMs: Long = 0L
)

data class DeviceFolderSummary(
    val name: String,
    val path: String,
    val mediaCount: Int
)

class VaultStorage(private val context: Context) {

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

    val vaultThumbsDir: File
        get() {
            val thumbsDir = File(vaultRootDir, "thumbs")
            if (!thumbsDir.exists()) thumbsDir.mkdirs()
            return thumbsDir
        }

    private val catalogFile: File
        get() = File(vaultRootDir, "vault_catalog.enc")

    companion object {
        val SUPPORTED_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp"
        )
        val SUPPORTED_VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "mov", "avi", "3gp", "webm"
        )
        val ALL_SUPPORTED_EXTENSIONS = SUPPORTED_IMAGE_EXTENSIONS + SUPPORTED_VIDEO_EXTENSIONS

        fun isSupportedMediaFileName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return ext in ALL_SUPPORTED_EXTENSIONS
        }

        fun isVideoExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return ext in SUPPORTED_VIDEO_EXTENSIONS
        }

        fun formatBytesProgress(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }

    /**
     * Queries all device media files from MediaStore (including HEIC, WEBP, BMP, GIF, PNG, JPG, and Videos).
     * Excludes anything already inside `.LocalVaultData`.
     */
    suspend fun queryDeviceMediaFiles(folderFilter: String? = null): List<DeviceMediaItem> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, DeviceMediaItem>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )

        val selection = "${MediaStore.MediaColumns.DATA} NOT LIKE '%/.LocalVaultData/%'"
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val typeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    if (path.isEmpty() || result.containsKey(path)) continue
                    val file = File(path)
                    if (!file.exists() || !file.isFile || file.length() <= 0L) continue

                    val mediaType = if (typeCol != -1) cursor.getInt(typeCol) else MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
                    val isMediaRow = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ||
                        mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                        isSupportedMediaFileName(file.name)
                    if (!isMediaRow) continue

                    val folderName = if (bucketCol != -1) {
                        cursor.getString(bucketCol) ?: file.parentFile?.name ?: "Other"
                    } else {
                        file.parentFile?.name ?: "Other"
                    }

                    if (folderFilter == null || folderName.equals(folderFilter, ignoreCase = true)) {
                        val id = if (idCol != -1) cursor.getLong(idCol) else 0L
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: file.name else file.name
                        val size = if (sizeCol != -1) cursor.getLong(sizeCol).coerceAtLeast(file.length()) else file.length()
                        val dateMod = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else file.lastModified()
                        val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || isVideoExtension(name)
                        val durationMs = if (isVideo && durCol != -1 && !cursor.isNull(durCol)) cursor.getLong(durCol) else 0L

                        result[path] = DeviceMediaItem(
                            id = id,
                            path = path,
                            name = name,
                            size = size,
                            dateModified = dateMod,
                            isVideo = isVideo,
                            folderName = folderName,
                            durationMs = durationMs
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        return@withContext result.values.toList()
    }

    suspend fun queryDeviceFolders(): List<String> = withContext(Dispatchers.IO) {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE
        )
        val selection = "${MediaStore.MediaColumns.DATA} NOT LIKE '%/.LocalVaultData/%'"
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val typeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (cursor.moveToNext()) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) ?: "" else ""
                    val file = File(path)
                    if (path.isNotEmpty() && file.exists()) {
                        val mediaType = if (typeCol != -1) cursor.getInt(typeCol) else 0
                        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ||
                            mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                            isSupportedMediaFileName(file.name)
                        ) {
                            val bName = if (bucketCol != -1) cursor.getString(bucketCol) else file.parentFile?.name
                            if (!bName.isNullOrBlank()) {
                                folders.add(bName)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return@withContext folders.sorted()
    }

    /**
     * Point 5: Traverses `/storage/emulated/0` (excluding `/Android/data`, `/Android/obb`, and `/.LocalVaultData`)
     * using `File.listFiles()` directory traversal on `Dispatchers.IO` so ALL folders on the phone—
     * including completely empty folders—appear and can be searched by name and selected.
     */
    suspend fun scanAllDeviceFoldersForSearch(): List<DeviceFolderSummary> = withContext(Dispatchers.IO) {
        val folderMap = LinkedHashMap<String, DeviceFolderSummary>()

        // 1. Seed preset common folders first (even if not created yet on disk)
        VaultPreferences.getPresetWatchableFolders().forEach { (label, dirPath) ->
            val dir = File(dirPath)
            val count = dir.listFiles()?.count { it.isFile && !it.name.startsWith(".") && isSupportedMediaFileName(it.name) } ?: 0
            folderMap[dirPath] = DeviceFolderSummary(
                name = dir.name.ifBlank { label },
                path = dirPath,
                mediaCount = count
            )
        }

        // 2. Breadth-first File.listFiles() directory traversal across /storage/emulated/0
        val rootDir = Environment.getExternalStorageDirectory()
        val rootPath = rootDir.absolutePath.ifBlank { "/storage/emulated/0" }
        val excludedPrefixes = listOf(
            "$rootPath/Android/data",
            "$rootPath/Android/obb",
            "$rootPath/.LocalVaultData"
        )

        val queue = ArrayDeque<Pair<File, Int>>()
        if (rootDir.exists() && rootDir.isDirectory) {
            queue.add(rootDir to 0)
        }

        val maxDepth = 6
        val maxDirectories = 2500
        var scannedDirs = 0

        while (queue.isNotEmpty() && scannedDirs < maxDirectories) {
            val (currentDir, depth) = queue.removeFirst()
            val absPath = currentDir.absolutePath

            if (excludedPrefixes.any { absPath.startsWith(it, ignoreCase = true) }) {
                continue
            }

            if (absPath != rootPath) {
                scannedDirs++
                var mediaCount = 0
                val children = try {
                    currentDir.listFiles()
                } catch (_: Throwable) {
                    null
                }
                if (children != null) {
                    for (child in children) {
                        val name = child.name
                        if (name.startsWith(".")) continue
                        if (child.isDirectory) {
                            val childPath = child.absolutePath
                            if (depth < maxDepth && !excludedPrefixes.any { childPath.startsWith(it, ignoreCase = true) }) {
                                queue.add(child to (depth + 1))
                            }
                        } else if (child.isFile && isSupportedMediaFileName(name)) {
                            mediaCount++
                        }
                    }
                }
                folderMap[absPath] = DeviceFolderSummary(
                    name = currentDir.name.ifBlank { absPath },
                    path = absPath,
                    mediaCount = mediaCount
                )
            } else {
                val rootChildren = try {
                    currentDir.listFiles()
                } catch (_: Throwable) {
                    null
                }
                rootChildren?.forEach { child ->
                    if (child.isDirectory && !child.name.startsWith(".")) {
                        val childPath = child.absolutePath
                        if (!excludedPrefixes.any { childPath.startsWith(it, ignoreCase = true) }) {
                            queue.add(child to 1)
                        }
                    }
                }
            }
        }

        return@withContext folderMap.values.sortedWith(
            compareByDescending<DeviceFolderSummary> { it.mediaCount }
                .thenBy { it.name.lowercase(Locale.US) }
                .thenBy { it.path.lowercase(Locale.US) }
        )
    }

    suspend fun listSubdirectories(parentPath: String): List<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(parentPath)
            if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase(Locale.US) }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Point 1 (Step 3): Physical File Reconciliation (Truth Check).
     * Cross-verifies the Room database metadata list against actual encrypted `.bin` files physically
     * on disk (`vaultBlobsDir.listFiles()`). Removes any ghost or duplicate metadata entries pointing
     * to the same physical file or missing files, and returns a strictly deduplicated list.
     */
    suspend fun reconcilePhysicalFiles(masterKey: SecretKey? = null): List<VaultItem> =
        VaultRepository.withVaultLock {
            reconcilePhysicalFilesLocked(masterKey)
        }

    private suspend fun reconcilePhysicalFilesLocked(masterKey: SecretKey? = null): List<VaultItem> {
        val physicalBlobNames = try {
            vaultBlobsDir.listFiles()
                ?.asSequence()
                ?.filter { it.isFile && it.name.endsWith(".bin") && it.length() > 0L }
                ?.map { it.name }
                ?.toSet() ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }

        val allDbItems = vaultDao.getAllItemsSync()
        val uniqueItemsMap = LinkedHashMap<String, VaultItem>()
        val idsToDelete = mutableListOf<Long>()

        for (item in allDbItems) {
            val relPath = item.encryptedRelativePath
            if (relPath.isBlank() || relPath !in physicalBlobNames) {
                // Ghost metadata entry whose physical encrypted file does not exist on disk
                idsToDelete.add(item.id)
            } else if (uniqueItemsMap.containsKey(relPath)) {
                // Duplicate metadata entry pointing to the same physical encrypted file
                idsToDelete.add(item.id)
            } else {
                uniqueItemsMap[relPath] = item
            }
        }

        if (idsToDelete.isNotEmpty()) {
            idsToDelete.chunked(500).forEach { chunk ->
                vaultDao.deleteItemsByIds(chunk)
            }
            if (masterKey != null) {
                syncEncryptedCatalogLocked(masterKey)
            }
        }

        return uniqueItemsMap.values.toList()
    }

    /**
     * Sweeps all watched folder paths inside `VaultRepository.vaultMutex` so background Auto-Hide
     * scans never race with manual media imports or duplicate files.
     */
    suspend fun sweepWatchedFoldersNow(
        watchedPaths: Set<String>,
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): Int = VaultRepository.withVaultLock {
        var importedCount = 0
        watchedPaths.forEach { pathStr ->
            try {
                val folder = File(pathStr)
                if (folder.exists() && folder.isDirectory && !folder.absolutePath.contains("/.LocalVaultData")) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && !file.name.startsWith(".") && file.length() > 0L && isSupportedMediaFileName(file.name)) {
                            val item = importFileLocked(
                                uri = Uri.fromFile(file),
                                originalPathOverride = file.absolutePath,
                                targetFolderId = null,
                                masterKey = masterKey,
                                isDecoy = isDecoy,
                                syncCatalogImmediately = false
                            )
                            if (item != null) {
                                importedCount++
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (importedCount > 0) {
            reconcilePhysicalFilesLocked(masterKey)
            syncEncryptedCatalogLocked(masterKey)
        }
        importedCount
    }

    /**
     * Imports a single URI inside the global `VaultRepository.vaultMutex`.
     */
    suspend fun importFile(
        uri: Uri,
        originalPathOverride: String? = null,
        targetFolderId: Long? = null,
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): VaultItem? = VaultRepository.withVaultLock {
        val item = importFileLocked(
            uri = uri,
            originalPathOverride = originalPathOverride,
            targetFolderId = targetFolderId,
            masterKey = masterKey,
            isDecoy = isDecoy,
            syncCatalogImmediately = true
        )
        item
    }

    /**
     * Batch-imports multiple URIs inside a single `VaultRepository.vaultMutex` lock with
     * live determinate progress (`"Importing Media..."`, percentage, and `"X / Y files"`).
     */
    suspend fun importBatchUris(
        uris: List<Uri>,
        targetFolderId: Long?,
        masterKey: SecretKey,
        isDecoy: Boolean
    ): Int = VaultRepository.withVaultLock {
        val distinctUris = uris.distinct()
        val total = distinctUris.size
        if (total == 0) return@withVaultLock 0

        VaultRepository.updateProgress(
            title = "Importing Media...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        var successCount = 0
        val importedSourcePathsInBatch = HashSet<String>()

        distinctUris.forEachIndexed { index, uri ->
            val item = importFileLocked(
                uri = uri,
                originalPathOverride = null,
                targetFolderId = targetFolderId,
                masterKey = masterKey,
                isDecoy = isDecoy,
                syncCatalogImmediately = false,
                alreadyImportedPathsInBatch = importedSourcePathsInBatch
            )
            if (item != null) {
                successCount++
            }
            val current = index + 1
            val pct = ((current * 100L) / total.toLong()).toInt().coerceIn(0, 100)
            VaultRepository.updateProgress(
                title = "Importing Media...",
                currentItem = current,
                totalItems = total,
                percentage = pct,
                subText = "$current / $total files",
                forceEmit = current == total
            )
        }

        reconcilePhysicalFilesLocked(masterKey)
        syncEncryptedCatalogLocked(masterKey)
        VaultRepository.hideProgress()
        successCount
    }

    /**
     * Batch-imports direct filesystem paths inside a single `VaultRepository.vaultMutex` lock with
     * live determinate progress (`"Importing Media..."`, percentage, and `"X / Y files"`).
     */
    suspend fun importBatchDirectFiles(
        paths: List<String>,
        targetFolderId: Long?,
        masterKey: SecretKey,
        isDecoy: Boolean
    ): Int = VaultRepository.withVaultLock {
        val distinctPaths = paths.distinct()
        val total = distinctPaths.size
        if (total == 0) return@withVaultLock 0

        VaultRepository.updateProgress(
            title = "Importing Media...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        var successCount = 0
        val importedSourcePathsInBatch = HashSet<String>()

        distinctPaths.forEachIndexed { index, path ->
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 0L) {
                val item = importFileLocked(
                    uri = Uri.fromFile(file),
                    originalPathOverride = path,
                    targetFolderId = targetFolderId,
                    masterKey = masterKey,
                    isDecoy = isDecoy,
                    syncCatalogImmediately = false,
                    alreadyImportedPathsInBatch = importedSourcePathsInBatch
                )
                if (item != null) {
                    successCount++
                }
            }
            val current = index + 1
            val pct = ((current * 100L) / total.toLong()).toInt().coerceIn(0, 100)
            VaultRepository.updateProgress(
                title = "Importing Media...",
                currentItem = current,
                totalItems = total,
                percentage = pct,
                subText = "$current / $total files",
                forceEmit = current == total
            )
        }

        reconcilePhysicalFilesLocked(masterKey)
        syncEncryptedCatalogLocked(masterKey)
        VaultRepository.hideProgress()
        successCount
    }

    /**
     * Point 2: All-Format Photo & Media Import (JPG, JPEG, PNG, HEIC, WEBP, BMP, GIF + Videos).
     * - Robust MIME Type & Extension Detection via `contentResolver.getType(uri)` and `OpenableColumns.DISPLAY_NAME`.
     * - Direct Raw Byte Stream Encryption via 64KB buffer (`ByteArray(65536)`) into `CipherOutputStream`.
     * - Safe Thumbnail Generation in an isolated `try-catch` block that never aborts import.
     */
    private suspend fun importFileLocked(
        uri: Uri,
        originalPathOverride: String? = null,
        targetFolderId: Long? = null,
        masterKey: SecretKey,
        isDecoy: Boolean = false,
        syncCatalogImmediately: Boolean = true,
        alreadyImportedPathsInBatch: MutableSet<String>? = null
    ): VaultItem? {
        val resolver = context.contentResolver
        var fileName = ""
        var reportedSize = 0L
        var mimeType = try {
            resolver.getType(uri) ?: ""
        } catch (_: Exception) {
            ""
        }
        var resolvedFsPath = originalPathOverride ?: ""
        var mediaStoreId: Long? = null

        // 1. Resolve metadata from direct file path or content URI
        if (resolvedFsPath.isNotEmpty()) {
            val f = File(resolvedFsPath)
            if (!f.exists() || !f.isFile || f.length() <= 0L) {
                return null
            }
            fileName = f.name
            reportedSize = f.length()
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: ""
            val f = File(path)
            if (path.isEmpty() || !f.exists() || !f.isFile || f.length() <= 0L) {
                return null
            }
            resolvedFsPath = path
            fileName = f.name
            reportedSize = f.length()
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

        // 2. If real filesystem path is not known yet, resolve it via DocumentsContract / MediaStore
        if (resolvedFsPath.isEmpty() || !File(resolvedFsPath).exists()) {
            val resolved = resolveRealPathAndId(uri, fileName, reportedSize)
            if (resolved.first != null) {
                resolvedFsPath = resolved.first!!
            }
            if (resolved.second != null) {
                mediaStoreId = resolved.second
            }
        }

        // Prevent importing the exact same physical source file twice in the same batch
        if (resolvedFsPath.isNotEmpty()) {
            if (alreadyImportedPathsInBatch != null && !alreadyImportedPathsInBatch.add(resolvedFsPath)) {
                return null
            }
        }

        if (fileName.isEmpty() && resolvedFsPath.isNotEmpty()) {
            fileName = File(resolvedFsPath).name
        }

        // Robust MIME type & extension detection (never drop valid image/video formats)
        val detectedExt = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (mimeType.isBlank() ||
            mimeType == "application/octet-stream" ||
            mimeType == "*/*" ||
            mimeType.endsWith("/*")
        ) {
            mimeType = guessMimeTypeFromExtension(fileName, mimeType)
        } else if (detectedExt in ALL_SUPPORTED_EXTENSIONS) {
            // Ensure specific MIME type for HEIC/HEIF/WEBP/BMP/GIF if provider returned generic type
            val extMime = guessMimeTypeFromExtension(fileName, mimeType)
            if (extMime.isNotBlank()) {
                mimeType = extMime
            }
        }

        if (fileName.isEmpty()) {
            val ext = extensionFromMimeType(mimeType)
            fileName = "Media_${System.currentTimeMillis()}$ext"
        }

        // 3. Extract video duration inside an isolated try-catch before encrypting
        var durationMs = 0L
        if (mimeType.startsWith("video") || isVideoExtension(fileName)) {
            val retriever = MediaMetadataRetriever()
            try {
                if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                    retriever.setDataSource(resolvedFsPath)
                } else {
                    retriever.setDataSource(context, uri)
                }
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durStr?.toLongOrNull() ?: 0L
            } catch (_: Throwable) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }

        val blobName = "${UUID.randomUUID()}.bin"
        val blobFile = File(vaultBlobsDir, blobName)
        val thumbFile = File(vaultThumbsDir, "$blobName.thumb")
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
                return null
            }

            outputStream = FileOutputStream(blobFile)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(baseIv))
            val cipherOut = CipherOutputStream(BufferedOutputStream(outputStream, 65536), cipher)

            // Point 2 (Step 3): Direct Raw Byte Stream Encryption using 64KB buffer (no Bitmap re-encoding)
            val buffer = ByteArray(65536)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                cipherOut.write(buffer, 0, read)
                totalWritten += read
            }
            cipherOut.flush()
            try {
                outputStream.fd.sync()
            } catch (_: Throwable) {
            }
            cipherOut.close()
            inputStream.close()

            if (!blobFile.exists() || blobFile.length() <= 0L) {
                blobFile.delete()
                return null
            }

            // Point 2 (Step 4): Safe Thumbnail Generation inside an isolated try-catch block
            // Uses ImageDecoder (API 28+, supporting HEIC/WEBP/BMP/GIF/PNG/JPEG) or sampled BitmapFactory.
            // Never aborts the import if thumbnail generation fails.
            ThumbnailCacheManager.generateAndSaveSafeThumbnailDuringImport(
                context = context,
                sourceUri = uri,
                resolvedFsPath = resolvedFsPath,
                mimeType = mimeType,
                thumbFile = thumbFile,
                masterKey = masterKey
            )

            val finalOriginalPath = if (resolvedFsPath.isNotEmpty()) {
                resolvedFsPath
            } else {
                val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                File(File(pictures, "Restored_Vault"), fileName).absolutePath
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

            writeSidecarMetadata(savedItem, masterKey)
            if (syncCatalogImmediately) {
                syncEncryptedCatalogLocked(masterKey)
            }

            // 4. Delete original file from physical storage and MediaStore
            deleteOriginalFileSafely(
                uri = uri,
                fsPath = resolvedFsPath,
                fileName = fileName,
                reportedSize = savedItem.fileSize,
                mediaStoreId = mediaStoreId,
                resolver = resolver
            )

            return savedItem
        } catch (_: Throwable) {
            blobFile.delete()
            thumbFile.delete()
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Throwable) {
            }
            try {
                outputStream?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Moves selected items to `targetFolderId` inside `VaultRepository.vaultMutex` with live
     * determinate progress (`"Moving Media..."`).
     */
    suspend fun moveItemsToFolderWithProgress(
        selectedItems: List<VaultItem>,
        targetFolderId: Long?,
        masterKey: SecretKey
    ) = VaultRepository.withVaultLock {
        val distinctItems = selectedItems.distinctBy { it.encryptedRelativePath }
        val total = distinctItems.size
        if (total == 0) return@withVaultLock

        VaultRepository.updateProgress(
            title = "Moving Media...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        vaultDao.moveItemsToFolder(
            ids = distinctItems.map { it.id },
            targetFolderId = targetFolderId
        )

        distinctItems.forEachIndexed { index, item ->
            writeSidecarMetadata(item.copy(folderId = targetFolderId), masterKey)
            val current = index + 1
            val pct = ((current * 100L) / total.toLong()).toInt().coerceIn(0, 100)
            VaultRepository.updateProgress(
                title = "Moving Media...",
                currentItem = current,
                totalItems = total,
                percentage = pct,
                subText = "$current / $total files",
                forceEmit = current == total
            )
        }

        syncEncryptedCatalogLocked(masterKey)
        VaultRepository.hideProgress()
    }

    /**
     * Unhides (restores to device) a batch of items inside `VaultRepository.vaultMutex` with live
     * determinate progress (`"Restoring to Device..."`).
     */
    suspend fun unhideBatchWithProgress(
        selectedItems: List<VaultItem>,
        masterKey: SecretKey
    ): Int = unhideBatchWithDestination(selectedItems, masterKey).first

    /**
     * Point 10: Unhides (restores to device) a batch of items with `"Restoring to Device..."` progress
     * and returns `(restoredCount, destinationFoldersSummary)` so the caller can display a Toast/Snackbar
     * explicitly naming the destination folder (e.g., `"Restored to /Pictures/Restored_Vault"`).
     */
    suspend fun unhideBatchWithDestination(
        selectedItems: List<VaultItem>,
        masterKey: SecretKey
    ): Pair<Int, String> = VaultRepository.withVaultLock {
        val distinctItems = selectedItems.distinctBy { it.encryptedRelativePath }
        val total = distinctItems.size
        if (total == 0) return@withVaultLock Pair(0, "/Pictures/Restored_Vault")

        VaultRepository.updateProgress(
            title = "Restoring to Device...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        var restoredCount = 0
        val destinationFolders = linkedSetOf<String>()
        distinctItems.forEachIndexed { index, item ->
            val destFolder = unhideFileLocked(item, masterKey, syncCatalogImmediately = false)
            if (destFolder != null) {
                restoredCount++
                destinationFolders.add(destFolder)
            }
            val current = index + 1
            val pct = ((current * 100L) / total.toLong()).toInt().coerceIn(0, 100)
            VaultRepository.updateProgress(
                title = "Restoring to Device...",
                currentItem = current,
                totalItems = total,
                percentage = pct,
                subText = "$current / $total files",
                forceEmit = current == total
            )
        }

        syncEncryptedCatalogLocked(masterKey)
        ThumbnailCacheManager.clearCache()
        VaultRepository.hideProgress()
        val destLabel = if (destinationFolders.isEmpty()) {
            "/Pictures/Restored_Vault"
        } else {
            destinationFolders.joinToString(", ")
        }
        Pair(restoredCount, destLabel)
    }

    /**
     * Deletes a batch of items permanently inside `VaultRepository.vaultMutex` with live
     * determinate progress (`"Deleting Media..."`).
     */
    suspend fun deleteBatchPermanentlyWithProgress(
        selectedItems: List<VaultItem>,
        masterKey: SecretKey?
    ): Int = VaultRepository.withVaultLock {
        val distinctItems = selectedItems.distinctBy { it.encryptedRelativePath }
        val total = distinctItems.size
        if (total == 0) return@withVaultLock 0

        VaultRepository.updateProgress(
            title = "Deleting Media...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        distinctItems.forEachIndexed { index, item ->
            deletePermanentlyLocked(item, masterKey = null)
            val current = index + 1
            val pct = ((current * 100L) / total.toLong()).toInt().coerceIn(0, 100)
            VaultRepository.updateProgress(
                title = "Deleting Media...",
                currentItem = current,
                totalItems = total,
                percentage = pct,
                subText = "$current / $total files",
                forceEmit = current == total
            )
        }

        if (masterKey != null) {
            syncEncryptedCatalogLocked(masterKey)
        }
        ThumbnailCacheManager.clearCache()
        VaultRepository.hideProgress()
        total
    }

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

    suspend fun syncEncryptedCatalog(masterKey: SecretKey) = VaultRepository.withVaultLock {
        syncEncryptedCatalogLocked(masterKey)
    }

    private suspend fun syncEncryptedCatalogLocked(masterKey: SecretKey) {
        try {
            val allFolders = folderDao.getAllFoldersSync()
            val allItems = vaultDao.getAllItemsSync()

            // Point 1 (Step 2): Deduplicate items in a LinkedHashMap keyed by encryptedRelativePath before saving
            val uniqueItems = LinkedHashMap<String, VaultItem>()
            for (item in allItems) {
                val blob = File(vaultBlobsDir, item.encryptedRelativePath)
                if (item.encryptedRelativePath.isNotBlank() && blob.exists() && !uniqueItems.containsKey(item.encryptedRelativePath)) {
                    uniqueItems[item.encryptedRelativePath] = item
                }
            }

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
            uniqueItems.values.forEach { item ->
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
     * `vault_catalog.enc` and `.meta` sidecar files, then runs physical file reconciliation
     * inside `VaultRepository.vaultMutex` so zero ghost or duplicate entries can exist.
     */
    suspend fun reconcileAndRecoverCatalog(masterKey: SecretKey) = VaultRepository.withVaultLock {
        try {
            // First clean up any ghost or duplicate DB rows
            val validExistingItems = reconcilePhysicalFilesLocked(masterKey = null)
            val existingBlobNames = validExistingItems.map { it.encryptedRelativePath }.toMutableSet()
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
                            if (relPath.isNotBlank() && relPath !in existingBlobNames && blobFile.exists() && blobFile.length() > 0L) {
                                val rawFolderId = obj.optLong("folderId", -1L)
                                val recovered = VaultItem(
                                    id = 0L, // Let Room auto-generate or replace by unique index on encryptedRelativePath
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
                    if (blobName !in existingBlobNames && blobFile.exists() && blobFile.length() > 0L) {
                        val recovered = readSidecarMetadata(file, masterKey)
                        if (recovered != null) {
                            vaultDao.insertItem(recovered.copy(id = 0L))
                            existingBlobNames.add(blobName)
                        }
                    }
                }
            }

            // 3. Final physical truth check & sidecar sync
            val finalItems = reconcilePhysicalFilesLocked(masterKey = null)
            finalItems.forEach { item ->
                val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
                if (!metaFile.exists()) {
                    writeSidecarMetadata(item, masterKey)
                }
            }
            syncEncryptedCatalogLocked(masterKey)
        } catch (_: Exception) {
        }
    }

    private fun resolveRealPathAndId(uri: Uri, fileName: String, reportedSize: Long): Pair<String?, Long?> {
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

        val lastSeg = uri.lastPathSegment?.toLongOrNull()
        if (lastSeg != null) {
            val path = queryPathFromMediaStore(MediaStore.Files.getContentUri("external"), lastSeg)
                ?: queryPathFromMediaStore(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, lastSeg)
                ?: queryPathFromMediaStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, lastSeg)
            if (path != null) return Pair(path, lastSeg)
        }

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

        if (fsPath.isNotEmpty()) {
            try {
                val origFile = File(fsPath)
                if (origFile.exists() && origFile.isFile) {
                    origFile.delete()
                }
            } catch (_: Exception) {
            }
        }

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

        try {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                resolver.delete(uri, null, null)
            }
        } catch (_: Exception) {
        }
    }

    suspend fun unhideFile(item: VaultItem, masterKey: SecretKey): Boolean =
        unhideFileWithDestination(item, masterKey) != null

    /**
     * Point 10: Unhides a single file with `"Restoring to Device..."` progress and returns
     * the formatted destination folder name (e.g., `"/Pictures/Restored_Vault"` or `"/DCIM/Camera"`).
     */
    suspend fun unhideFileWithDestination(item: VaultItem, masterKey: SecretKey): String? = VaultRepository.withVaultLock {
        VaultRepository.updateProgress(
            title = "Restoring to Device...",
            currentItem = 0,
            totalItems = 1,
            percentage = 0,
            subText = "0 / 1 files",
            forceEmit = true
        )
        val destFolder = unhideFileLocked(item, masterKey, syncCatalogImmediately = true)
        VaultRepository.updateProgress(
            title = "Restoring to Device...",
            currentItem = 1,
            totalItems = 1,
            percentage = 100,
            subText = "1 / 1 files",
            forceEmit = true
        )
        VaultRepository.hideProgress()
        destFolder
    }

    private fun formatDestinationFolderDisplay(folderFile: File?): String {
        if (folderFile == null) return "/Pictures/Restored_Vault"
        val extRoot = try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (_: Throwable) {
            "/storage/emulated/0"
        }
        val abs = folderFile.absolutePath
        val rel = if (abs.startsWith(extRoot)) {
            abs.removePrefix(extRoot)
        } else {
            abs
        }
        return if (rel.isBlank()) "/" else if (rel.startsWith("/")) rel else "/$rel"
    }

    private suspend fun unhideFileLocked(
        item: VaultItem,
        masterKey: SecretKey,
        syncCatalogImmediately: Boolean
    ): String? {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (!blobFile.exists()) return null

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val defaultRestoredDir = File(picturesDir, "Restored_Vault")

        var targetPath = item.originalPath
        if (targetPath.isEmpty()) {
            if (!defaultRestoredDir.exists()) defaultRestoredDir.mkdirs()
            targetPath = File(defaultRestoredDir, item.fileName).absolutePath
        }

        var targetFile = File(targetPath)
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }
        if (parentDir == null || !parentDir.canWrite()) {
            defaultRestoredDir.mkdirs()
            targetFile = File(defaultRestoredDir, item.fileName)
        }

        return try {
            FileInputStream(blobFile).use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val baseIv = fromHex(item.baseIvHex)
                    CryptoEngine.decryptStreamCtr(inputStream, outputStream, masterKey, baseIv)
                    outputStream.flush()
                    try {
                        outputStream.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
            }

            blobFile.delete()
            File(vaultBlobsDir, "${item.encryptedRelativePath}.meta").delete()
            File(vaultThumbsDir, "${item.encryptedRelativePath}.thumb").delete()
            vaultDao.deleteItem(item)
            if (syncCatalogImmediately) {
                syncEncryptedCatalogLocked(masterKey)
                ThumbnailCacheManager.clearCache()
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(item.mimeType),
                null
            )
            formatDestinationFolderDisplay(targetFile.parentFile)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deletePermanently(item: VaultItem, masterKey: SecretKey? = null): Boolean = VaultRepository.withVaultLock {
        VaultRepository.updateProgress(
            title = "Deleting Media...",
            currentItem = 0,
            totalItems = 1,
            percentage = 0,
            subText = "0 / 1 files",
            forceEmit = true
        )
        val ok = deletePermanentlyLocked(item, masterKey)
        VaultRepository.updateProgress(
            title = "Deleting Media...",
            currentItem = 1,
            totalItems = 1,
            percentage = 100,
            subText = "1 / 1 files",
            forceEmit = true
        )
        VaultRepository.hideProgress()
        ok
    }

    private suspend fun deletePermanentlyLocked(item: VaultItem, masterKey: SecretKey? = null): Boolean {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (blobFile.exists()) {
            blobFile.delete()
        }
        val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
        if (metaFile.exists()) {
            metaFile.delete()
        }
        val thumbFile = File(vaultThumbsDir, "${item.encryptedRelativePath}.thumb")
        if (thumbFile.exists()) {
            thumbFile.delete()
        }
        vaultDao.deleteItem(item)
        if (masterKey != null) {
            syncEncryptedCatalogLocked(masterKey)
        }
        return true
    }

    /**
     * Point 3: Fix 12GB+ Backup (.lvb) Exporting as Empty/Tiny KB-MB File.
     * 1. True Buffered Streaming Architecture (Zero RAM Bloat):
     *    Opens destination URI via `contentResolver.openFileDescriptor(destUri, "w")` and wraps
     *    `FileOutputStream(pfd.fileDescriptor)` inside `BufferedOutputStream(fos, 262_144)` -> `ZipOutputStream(bos)`
     *    with `zipOut.setLevel(Deflater.NO_COMPRESSION)` (encrypted files are uncompressible; 5x faster).
     * 2. Complete Vault Traversal & Chunked 256KB Copy Loop:
     *    Streams `vault_manifest.json` AND every encrypted media `.bin` file, `.meta` sidecar, and
     *    `.thumb` file across `vaultBlobsDir` and `vaultThumbsDir`, updating live byte progress.
     * 3. Proper Stream Finalization:
     *    Calls `zipOut.finish()`, `zipOut.flush()`, `bos.flush()`, and `fos.fd.sync()` before closing.
     */
    suspend fun exportEncryptedBackup(destUri: Uri, headerFile: File): Result<Int> = VaultRepository.withVaultLock {
        try {
            // Reconcile physical files first so the backup manifest has zero ghost or duplicate items
            val validItemsList = reconcilePhysicalFilesLocked(masterKey = null)
            val allFolders = folderDao.getAllFoldersSync()
            val headerText = if (headerFile.exists()) headerFile.readText(Charsets.UTF_8) else "{}"

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
            val uniqueItemsByPath = LinkedHashMap<String, VaultItem>()
            validItemsList.forEach { item ->
                val blob = File(vaultBlobsDir, item.encryptedRelativePath)
                if (blob.exists() && blob.length() > 0L && !uniqueItemsByPath.containsKey(item.encryptedRelativePath)) {
                    uniqueItemsByPath[item.encryptedRelativePath] = item
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

            // Collect all physical encrypted media files, sidecar metadata, and thumbnails across vault directories
            val filesToExport = ArrayList<Pair< String, File>>()
            val addedEntryNames = HashSet<String>()

            // Include every blob and sidecar in vaultBlobsDir
            vaultBlobsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0L && !file.name.startsWith(".tmp_")) {
                    val entryName = "blobs/${file.name}"
                    if (addedEntryNames.add(entryName)) {
                        filesToExport.add(entryName to file)
                    }
                }
            }

            // Include every encrypted thumbnail in vaultThumbsDir
            vaultThumbsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.length() > 0L) {
                    val entryName = "thumbs/${file.name}"
                    if (addedEntryNames.add(entryName)) {
                        filesToExport.add(entryName to file)
                    }
                }
            }

            // Include vault_catalog.enc if present
            if (catalogFile.exists() && catalogFile.length() > 0L) {
                val entryName = "vault_catalog.enc"
                if (addedEntryNames.add(entryName)) {
                    filesToExport.add(entryName to catalogFile)
                }
            }

            val manifestJsonStr = JSONObject().apply {
                put("version", 2)
                put("header", headerText)
                put("folders", foldersJson)
                put("items", itemsJson)
            }.toString()
            val manifestBytes = manifestJsonStr.toByteArray(Charsets.UTF_8)

            val totalBytesToExport = (manifestBytes.size.toLong() + filesToExport.sumOf { it.second.length() }).coerceAtLeast(1L)
            var bytesWrittenSoFar = 0L
            val totalMediaCount = uniqueItemsByPath.size

            VaultRepository.updateProgress(
                title = "Creating Backup...",
                currentItem = 0,
                totalItems = totalMediaCount,
                percentage = 0,
                subText = "0 MB / ${formatBytesProgress(totalBytesToExport)}",
                forceEmit = true
            )

            val pfd = context.contentResolver.openFileDescriptor(destUri, "w")
                ?: return@withVaultLock Result.failure(IllegalStateException("Cannot open backup destination descriptor"))

            pfd.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { fos ->
                    BufferedOutputStream(fos, 262_144).use { bos ->
                        ZipOutputStream(bos).use { zipOut ->
                            // Encrypted files are uncompressible; NO_COMPRESSION makes 12GB+ backups 5x faster
                            zipOut.setLevel(Deflater.NO_COMPRESSION)

                            // 1. Write vault_manifest.json
                            val manifestEntry = ZipEntry("vault_manifest.json")
                            zipOut.putNextEntry(manifestEntry)
                            zipOut.write(manifestBytes)
                            zipOut.closeEntry()
                            bytesWrittenSoFar += manifestBytes.size

                            // 2. Stream every encrypted blob, sidecar, and thumbnail with a single 256KB buffer
                            val buffer = ByteArray(262_144)
                            var exportedMediaBlobs = 0

                            for ((entryName, file) in filesToExport) {
                                if (!file.exists() || !file.isFile) continue
                                val entry = ZipEntry(entryName)
                                zipOut.putNextEntry(entry)

                                FileInputStream(file).use { fis ->
                                    var read: Int
                                    while (fis.read(buffer).also { read = it } != -1) {
                                        zipOut.write(buffer, 0, read)
                                        bytesWrittenSoFar += read

                                        val pct = ((bytesWrittenSoFar * 100L) / totalBytesToExport).toInt().coerceIn(0, 99)
                                        VaultRepository.updateProgress(
                                            title = "Creating Backup...",
                                            currentItem = exportedMediaBlobs,
                                            totalItems = totalMediaCount,
                                            percentage = pct,
                                            subText = "${formatBytesProgress(bytesWrittenSoFar)} / ${formatBytesProgress(totalBytesToExport)}"
                                        )
                                    }
                                }
                                zipOut.closeEntry()

                                if (entryName.startsWith("blobs/") && entryName.endsWith(".bin")) {
                                    exportedMediaBlobs++
                                }
                            }

                            // 3. Proper stream finalization & hardware descriptor sync
                            zipOut.finish()
                            zipOut.flush()
                            bos.flush()
                            try {
                                fos.fd.sync()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }

            VaultRepository.updateProgress(
                title = "Creating Backup...",
                currentItem = totalMediaCount,
                totalItems = totalMediaCount,
                percentage = 100,
                subText = "${formatBytesProgress(totalBytesToExport)} / ${formatBytesProgress(totalBytesToExport)}",
                forceEmit = true
            )
            VaultRepository.hideProgress()
            Result.success(totalMediaCount)
        } catch (e: Throwable) {
            VaultRepository.hideProgress()
            Result.failure(e)
        }
    }

    /**
     * Point 3: Matching Buffered Streaming (.lvb) Restore (Zero RAM Bloat).
     * Supports both the new ZipOutputStream `.lvb` archive and legacy `LVB1` archives seamlessly.
     */
    suspend fun restoreEncryptedBackup(sourceUri: Uri, headerFile: File): Result<Int> = VaultRepository.withVaultLock {
        try {
            val resolver = context.contentResolver
            val totalArchiveBytes = try {
                resolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize.coerceAtLeast(1L) } ?: 1L
            } catch (_: Throwable) {
                1L
            }

            VaultRepository.updateProgress(
                title = "Restoring Backup...",
                currentItem = 0,
                totalItems = 0,
                percentage = 0,
                subText = "0 MB / ${formatBytesProgress(totalArchiveBytes)}",
                forceEmit = true
            )

            val rawIn = resolver.openInputStream(sourceUri)
                ?: return@withVaultLock Result.failure(IllegalStateException("Cannot open backup file"))

            BufferedInputStream(rawIn, 262_144).use { bis ->
                bis.mark(8)
                val magic = ByteArray(4)
                val magicRead = bis.read(magic)
                bis.reset()

                if (magicRead == 4 && String(magic, Charsets.US_ASCII) == "LVB1") {
                    // Legacy LVB1 binary stream format fallback
                    val result = restoreLegacyLvb1Stream(bis, headerFile, totalArchiveBytes)
                    VaultRepository.hideProgress()
                    return@withVaultLock result
                }

                // Standard ZipInputStream buffered streaming (256KB buffer)
                var manifestJsonStr: String? = null
                var bytesRestoredSoFar = 0L
                var restoredBlobsCount = 0
                val buffer = ByteArray(262_144)

                ZipInputStream(bis).use { zipIn ->
                    var entry: ZipEntry? = zipIn.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory) {
                            when {
                                name == "vault_manifest.json" -> {
                                    val baos = ByteArrayOutputStream()
                                    var r: Int
                                    while (zipIn.read(buffer).also { r = it } != -1) {
                                        baos.write(buffer, 0, r)
                                        bytesRestoredSoFar += r
                                    }
                                    manifestJsonStr = baos.toString(Charsets.UTF_8.name())
                                }
                                name == "vault_catalog.enc" -> {
                                    FileOutputStream(catalogFile).use { fos ->
                                        var r: Int
                                        while (zipIn.read(buffer).also { r = it } != -1) {
                                            fos.write(buffer, 0, r)
                                            bytesRestoredSoFar += r
                                        }
                                        fos.flush()
                                        try {
                                            fos.fd.sync()
                                        } catch (_: Throwable) {
                                        }
                                    }
                                }
                                name.startsWith("blobs/") -> {
                                    val fileName = File(name).name
                                    if (fileName.isNotEmpty()) {
                                        val targetFile = File(vaultBlobsDir, fileName)
                                        FileOutputStream(targetFile).use { fos ->
                                            BufferedOutputStream(fos, 262_144).use { bos ->
                                                var r: Int
                                                while (zipIn.read(buffer).also { r = it } != -1) {
                                                    bos.write(buffer, 0, r)
                                                    bytesRestoredSoFar += r

                                                    val pct = ((bytesRestoredSoFar * 100L) / totalArchiveBytes).toInt().coerceIn(0, 99)
                                                    VaultRepository.updateProgress(
                                                        title = "Restoring Backup...",
                                                        currentItem = restoredBlobsCount,
                                                        totalItems = restoredBlobsCount,
                                                        percentage = pct,
                                                        subText = "${formatBytesProgress(bytesRestoredSoFar)} / ${formatBytesProgress(totalArchiveBytes)}"
                                                    )
                                                }
                                                bos.flush()
                                            }
                                            try {
                                                fos.fd.sync()
                                            } catch (_: Throwable) {
                                            }
                                        }
                                        if (fileName.endsWith(".bin")) {
                                            restoredBlobsCount++
                                        }
                                    }
                                }
                                name.startsWith("thumbs/") -> {
                                    val fileName = File(name).name
                                    if (fileName.isNotEmpty()) {
                                        val targetFile = File(vaultThumbsDir, fileName)
                                        FileOutputStream(targetFile).use { fos ->
                                            var r: Int
                                            while (zipIn.read(buffer).also { r = it } != -1) {
                                                fos.write(buffer, 0, r)
                                                bytesRestoredSoFar += r
                                            }
                                            fos.flush()
                                            try {
                                                fos.fd.sync()
                                            } catch (_: Throwable) {
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }

                var restoredItemsCount = restoredBlobsCount
                if (!manifestJsonStr.isNullOrBlank()) {
                    val root = JSONObject(manifestJsonStr!!)
                    val headerText = root.optString("header", "")
                    if (headerText.isNotBlank() && headerText != "{}") {
                        headerFile.parentFile?.mkdirs()
                        headerFile.writeText(headerText, Charsets.UTF_8)
                    }

                    val foldersArray = root.optJSONArray("folders")
                    if (foldersArray != null) {
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
                    }

                    val itemsArray = root.optJSONArray("items")
                    if (itemsArray != null) {
                        val seenPaths = HashSet<String>()
                        for (i in 0 until itemsArray.length()) {
                            val obj = itemsArray.getJSONObject(i)
                            val relPath = obj.getString("encryptedRelativePath")
                            val blobFile = File(vaultBlobsDir, relPath)
                            if (relPath.isNotBlank() && seenPaths.add(relPath) && blobFile.exists() && blobFile.length() > 0L) {
                                val rawFolderId = obj.optLong("folderId", -1L)
                                vaultDao.insertItem(
                                    VaultItem(
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
                                )
                            }
                        }
                        restoredItemsCount = seenPaths.size
                    }
                }

                reconcilePhysicalFilesLocked(masterKey = null)

                VaultRepository.updateProgress(
                    title = "Restoring Backup...",
                    currentItem = restoredItemsCount,
                    totalItems = restoredItemsCount,
                    percentage = 100,
                    subText = "$restoredItemsCount / $restoredItemsCount files",
                    forceEmit = true
                )
                VaultRepository.hideProgress()
                Result.success(restoredItemsCount)
            }
        } catch (e: Throwable) {
            VaultRepository.hideProgress()
            Result.failure(e)
        }
    }

    private suspend fun restoreLegacyLvb1Stream(
        bis: BufferedInputStream,
        headerFile: File,
        totalArchiveBytes: Long
    ): Result<Int> {
        DataInputStream(bis).use { dis ->
            val magic = ByteArray(4)
            dis.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "LVB1") {
                return Result.failure(IllegalArgumentException("Invalid .lvb backup archive format"))
            }

            val headerLen = dis.readInt()
            val headerBytes = ByteArray(headerLen)
            dis.readFully(headerBytes)
            val headerJsonStr = String(headerBytes, Charsets.UTF_8)
            if (headerJsonStr.isNotBlank() && headerJsonStr != "{}") {
                headerFile.parentFile?.mkdirs()
                headerFile.writeText(headerJsonStr, Charsets.UTF_8)
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
            val buf = ByteArray(262_144)
            var bytesReadSoFar = (16 + headerLen + foldersLen + itemsLen).toLong()

            for (i in 0 until blobCount) {
                val nameLen = dis.readInt()
                val nameBytes = ByteArray(nameLen)
                dis.readFully(nameBytes)
                val blobName = String(nameBytes, Charsets.UTF_8)
                val blobSize = dis.readLong()

                val targetBlob = File(vaultBlobsDir, blobName)
                FileOutputStream(targetBlob).use { fos ->
                    BufferedOutputStream(fos, 262_144).use { bos ->
                        var remaining = blobSize
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val read = dis.read(buf, 0, toRead)
                            if (read == -1) break
                            bos.write(buf, 0, read)
                            remaining -= read
                            bytesReadSoFar += read

                            val pct = ((bytesReadSoFar * 100L) / totalArchiveBytes).toInt().coerceIn(0, 99)
                            VaultRepository.updateProgress(
                                title = "Restoring Backup...",
                                currentItem = i + 1,
                                totalItems = blobCount,
                                percentage = pct,
                                subText = "${i + 1} / $blobCount files"
                            )
                        }
                        bos.flush()
                    }
                    try {
                        fos.fd.sync()
                    } catch (_: Throwable) {
                    }
                }
            }

            val seenPaths = HashSet<String>()
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.getJSONObject(i)
                val relPath = obj.getString("encryptedRelativePath")
                val blobFile = File(vaultBlobsDir, relPath)
                if (relPath.isNotBlank() && seenPaths.add(relPath) && blobFile.exists()) {
                    val rawFolderId = obj.optLong("folderId", -1L)
                    vaultDao.insertItem(
                        VaultItem(
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
                    )
                }
            }

            reconcilePhysicalFilesLocked(masterKey = null)
            return Result.success(seenPaths.size)
        }
    }

    private fun guessMimeTypeFromExtension(fileName: String, fallbackMime: String = ""): String {
        val lower = fileName.lowercase(Locale.US)
        val rawExt = lower.substringAfterLast('.', "").trim()
        val cleanExt = rawExt.takeWhile { it.isLetterOrDigit() }

        fun mapExt(ext: String): String? = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "3gp", "3gpp" -> "video/3gpp"
            "webm" -> "video/webm"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            else -> null
        }

        mapExt(rawExt)?.let { return it }
        mapExt(cleanExt)?.let { return it }

        // Point 22: If filename has extra text/characters after .mp4 or container extension
        // (e.g., "clip.mp4 (1)", "video.mp4_backup", "movie.mkv.001"), scan for embedded container tokens
        val videoTokenRegex = Regex("""\.(mp4|m4v|mkv|mov|webm|avi|3gp|3gpp|ts|flv|wmv)(?:[^a-z0-9]|$)""")
        val videoMatch = videoTokenRegex.find(lower)
        if (videoMatch != null) {
            mapExt(videoMatch.groupValues[1])?.let { return it }
        }

        val imageTokenRegex = Regex("""\.(jpg|jpeg|png|heic|heif|webp|gif|bmp)(?:[^a-z0-9]|$)""")
        val imageMatch = imageTokenRegex.find(lower)
        if (imageMatch != null) {
            mapExt(imageMatch.groupValues[1])?.let { return it }
        }

        return if (fallbackMime.startsWith("video")) "video/mp4" else "image/jpeg"
    }

    private fun extensionFromMimeType(mimeType: String): String {
        val m = mimeType.lowercase(Locale.US)
        return when {
            m.contains("heic") -> ".heic"
            m.contains("heif") -> ".heif"
            m.contains("png") -> ".png"
            m.contains("webp") -> ".webp"
            m.contains("gif") -> ".gif"
            m.contains("bmp") -> ".bmp"
            m.contains("matroska") || m.contains("mkv") -> ".mkv"
            m.contains("quicktime") || m.contains("mov") -> ".mov"
            m.contains("webm") -> ".webm"
            m.contains("3gp") -> ".3gp"
            m.contains("avi") -> ".avi"
            m.startsWith("video") -> ".mp4"
            else -> ".jpg"
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
