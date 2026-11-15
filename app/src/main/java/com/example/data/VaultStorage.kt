package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
        private const val IO_CHUNK_BUFFER_BYTES = 1024 * 1024 // 1MB high-throughput, low-heap buffer
        private const val MAX_CONCURRENT_IO_WORKERS = 3

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
     * Physical File Reconciliation & Automatic Orphan Recovery:
     * Cross-verifies the Room database metadata list against actual encrypted files physically
     * on disk. If `masterKey` is provided, also recovers any orphaned/unindexed physical files
     * so zero files are ever invisible in the UI.
     */
    suspend fun reconcilePhysicalFiles(masterKey: SecretKey? = null): List<VaultItem> =
        VaultRepository.withVaultLock {
            if (masterKey != null) {
                recoverOrphanedVaultFilesLocked(masterKey = masterKey, isDecoy = false, showProgress = false)
            }
            reconcilePhysicalFilesLocked(masterKey)
        }

    private suspend fun reconcilePhysicalFilesLocked(masterKey: SecretKey? = null): List<VaultItem> {
        val physicalBlobNames = try {
            vaultBlobsDir.listFiles()
                ?.asSequence()
                ?.filter {
                    it.isFile &&
                        !it.name.endsWith(".meta") &&
                        !it.name.endsWith(".thumb") &&
                        !it.name.startsWith(".tmp_") &&
                        it.length() > 0L
                }
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
     * Emergency & Automatic Orphaned / Invisible Vault File Recovery (Requirement 1):
     * 1. Consolidates any files from internal/external app-specific `.LocalVaultData` fallback
     *    directories and any rows from internal `local_vault.db`.
     * 2. Scans EVERY physical file across `vaultRootDir.walkTopDown().filter { it.isFile }`,
     *    skipping thumbnail files (`*.thumb`) and metadata/database files (`*.meta`, `*.json`,
     *    `vault_catalog.enc`, `vault.header`, `local_vault.db*`).
     * 3. Compares every physical vault file on disk against the loaded metadata index
     *    (`vaultFilePath` / `encryptedRelativePath`).
     * 4. For EVERY physical file on disk that is missing from the metadata index:
     *    - Recovers from `.meta` sidecar, `vault_catalog.enc`, or legacy DB if available.
     *    - Otherwise inspects its decrypted header / magic bytes (first 16..64 bytes decrypted)
     *      or file extension to automatically detect whether it is a Video (`ftyp`, `mp4`, `mkv`,
     *      `webm`, `avi`, `mov`) or an Image (`FF D8 FF` JPG, `89 50 4E 47` PNG, `WEBP`, `HEIC`, `GIF`).
     *    - Reconstructs a valid `VaultItem` metadata entry (assigning to root vault or subfolder,
     *      setting real file size and lastModified timestamp, and generating missing thumbnails).
     *    - Saves all recovered items into the metadata index immediately.
     */
    suspend fun recoverOrphanedVaultFiles(
        masterKey: SecretKey,
        isDecoy: Boolean = false,
        showProgress: Boolean = false
    ): VaultRecoveryReport = VaultRepository.withVaultLock {
        recoverOrphanedVaultFilesLocked(
            masterKey = masterKey,
            isDecoy = isDecoy,
            showProgress = showProgress
        )
    }

    internal suspend fun recoverOrphanedVaultFilesLocked(
        masterKey: SecretKey?,
        isDecoy: Boolean = false,
        showProgress: Boolean = false
    ): VaultRecoveryReport {
        try {
            if (showProgress) {
                VaultRepository.updateProgress(
                    title = "Scanning Vault Directories...",
                    currentItem = 0,
                    totalItems = 100,
                    percentage = 5,
                    subText = "Checking physical vault storage...",
                    forceEmit = true
                )
            }

            // Step 1: Migrate any stranded blobs/thumbs/sidecars from internal/app-specific fallback directories
            consolidateFallbackVaultDirectories()

            // Step 2: Load any legacy rows from internal app database if separate from active external DB
            val legacyDbItemsByPath = LinkedHashMap<String, VaultItem>()
            val legacyDbFoldersById = LinkedHashMap<Long, FolderItem>()
            loadLegacyInternalDatabaseItems(legacyDbItemsByPath, legacyDbFoldersById)

            // Step 3: Load existing folders and recover any missing folders from catalog or legacy DB
            val existingFolders = folderDao.getAllFoldersSync().associateBy { it.id }.toMutableMap()
            for ((fId, legFolder) in legacyDbFoldersById) {
                if (!existingFolders.containsKey(fId)) {
                    val newId = folderDao.insertFolder(legFolder)
                    existingFolders[newId] = legFolder.copy(id = newId)
                }
            }

            val catalogItemsByPath = LinkedHashMap<String, VaultItem>()
            if (masterKey != null && catalogFile.exists() && catalogFile.length() > 0L) {
                try {
                    val decrypted = CryptoEngine.decryptAesGcm(catalogFile.readBytes(), masterKey)
                    val root = JSONObject(String(decrypted, Charsets.UTF_8))
                    val foldersArr = root.optJSONArray("folders")
                    if (foldersArr != null) {
                        for (i in 0 until foldersArr.length()) {
                            val fObj = foldersArr.getJSONObject(i)
                            val fId = fObj.optLong("id", 0L)
                            if (fId !in existingFolders) {
                                val folder = FolderItem(
                                    id = fId,
                                    folderName = fObj.getString("folderName"),
                                    isLocked = fObj.optBoolean("isLocked", false),
                                    lockType = fObj.optString("lockType", "MAIN_PIN"),
                                    customPinSaltHex = fObj.optString("customPinSaltHex", "").ifEmpty { null },
                                    customPinSlotAHex = fObj.optString("customPinSlotAHex", "").ifEmpty { null },
                                    isDecoy = fObj.optBoolean("isDecoy", false)
                                )
                                folderDao.insertFolder(folder)
                                existingFolders[fId] = folder
                            }
                        }
                    }

                    val itemsArr = root.optJSONArray("items")
                    if (itemsArr != null) {
                        for (i in 0 until itemsArr.length()) {
                            val obj = itemsArr.getJSONObject(i)
                            val relPath = obj.optString("encryptedRelativePath", "")
                            if (relPath.isNotBlank()) {
                                val rawFolderId = obj.optLong("folderId", -1L)
                                catalogItemsByPath[relPath] = VaultItem(
                                    id = 0L,
                                    fileName = obj.optString("fileName", relPath),
                                    originalPath = obj.optString("originalPath", ""),
                                    encryptedRelativePath = relPath,
                                    mimeType = obj.optString("mimeType", "image/jpeg"),
                                    fileSize = obj.optLong("fileSize", 0L),
                                    dateAdded = obj.optLong("dateAdded", System.currentTimeMillis()),
                                    folderId = if (rawFolderId == -1L) null else rawFolderId,
                                    isFavorite = obj.optBoolean("isFavorite", false),
                                    isDecoy = obj.optBoolean("isDecoy", false),
                                    durationMs = obj.optLong("durationMs", 0L),
                                    baseIvHex = obj.optString("baseIvHex", "")
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // Step 4: Scan all .meta sidecar files across vaultRootDir
            val sidecarItemsByPath = LinkedHashMap<String, VaultItem>()
            if (masterKey != null) {
                try {
                    vaultRootDir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".meta") && it.length() > 0L }
                        .forEach { metaFile ->
                            val recovered = readSidecarMetadata(metaFile, masterKey)
                            if (recovered != null && recovered.encryptedRelativePath.isNotBlank()) {
                                sidecarItemsByPath[recovered.encryptedRelativePath] = recovered.copy(id = 0L)
                            }
                        }
                } catch (_: Throwable) {
                }
            }

            // Step 5: Walk EVERY physical media/blob file across vaultRootDir
            val rootCanonical = try {
                vaultRootDir.canonicalPath
            } catch (_: Throwable) {
                vaultRootDir.absolutePath
            }
            val blobsCanonical = try {
                vaultBlobsDir.canonicalPath
            } catch (_: Throwable) {
                vaultBlobsDir.absolutePath
            }
            val thumbsCanonical = try {
                vaultThumbsDir.canonicalPath
            } catch (_: Throwable) {
                vaultThumbsDir.absolutePath
            }

            val skippedNames = setOf(
                "vault_catalog.enc",
                "vault.header",
                "local_vault.db",
                "local_vault.db-wal",
                "local_vault.db-shm",
                "local_vault.db-journal",
                "vault_manifest.json"
            )

            // Collect (blobFileInBlobsDir, optionalSubfolderName)
            val physicalFilesWithSubfolder = ArrayList<Pair<File, String?>>()
            val seenBlobFileNames = HashSet<String>()

            val allCandidateDiskFiles = try {
                vaultRootDir.walkTopDown().filter { it.isFile }.toList()
            } catch (_: Throwable) {
                vaultBlobsDir.listFiles()?.filter { it.isFile } ?: emptyList()
            }

            for (diskFile in allCandidateDiskFiles) {
                val name = diskFile.name
                if (diskFile.length() <= 0L) continue
                if (name in skippedNames) continue
                if (name.startsWith(".tmp_") || name.startsWith("local_vault.db")) continue
                if (name.endsWith(".thumb") || name.endsWith(".meta") || name.endsWith(".json") || name.endsWith(".enc") || name.endsWith(".header")) continue

                val parentCanonical = try {
                    diskFile.parentFile?.canonicalPath ?: ""
                } catch (_: Throwable) {
                    diskFile.parentFile?.absolutePath ?: ""
                }
                if (parentCanonical.startsWith(thumbsCanonical)) continue

                var subfolderName: String? = null
                var finalBlobFile = diskFile

                if (parentCanonical != blobsCanonical) {
                    if (parentCanonical != rootCanonical && parentCanonical.startsWith(rootCanonical)) {
                        val relParent = parentCanonical.removePrefix(rootCanonical).trimStart('/', '\\')
                        val topFolder = relParent.substringBefore('/').substringBefore('\\')
                        if (topFolder.isNotBlank() && topFolder != "blobs" && topFolder != "thumbs") {
                            subfolderName = topFolder
                        }
                    }
                    // Ensure the physical file is inside vaultBlobsDir so standard blob lookups succeed
                    val targetName = if (name.endsWith(".bin")) name else "${UUID.randomUUID()}.bin"
                    val destInBlobs = File(vaultBlobsDir, targetName)
                    if (!destInBlobs.exists()) {
                        try {
                            if (!diskFile.renameTo(destInBlobs)) {
                                diskFile.copyTo(destInBlobs, overwrite = true)
                                diskFile.delete()
                            }
                            finalBlobFile = destInBlobs
                        } catch (_: Throwable) {
                        }
                    } else {
                        finalBlobFile = destInBlobs
                    }
                }

                if (finalBlobFile.exists() && finalBlobFile.length() > 0L && seenBlobFileNames.add(finalBlobFile.name)) {
                    physicalFilesWithSubfolder.add(finalBlobFile to subfolderName)
                }
            }

            // Step 6: Reconcile existing DB rows, collect sibling 16-byte plaintext headers,
            // and repair any previously recovered items whose baseIvHex had a fallback IV
            val validExistingItems = reconcilePhysicalFilesLocked(masterKey = null)
            val siblingPlainHeaders = if (masterKey != null) {
                collectSiblingPlainHeaders(masterKey, validExistingItems)
            } else {
                ArrayList()
            }

            val indexedByPath = ConcurrentHashMap<String, VaultItem>()
            var repairedExistingCount = 0

            for (item in validExistingItems) {
                var repairedItem = item
                var needsUpdate = false
                if (repairedItem.folderId != null && repairedItem.folderId !in existingFolders) {
                    repairedItem = repairedItem.copy(folderId = null)
                    needsUpdate = true
                }
                // Ensure items in Real Vault have isDecoy = false so they are never hidden
                if (!isDecoy && repairedItem.isDecoy) {
                    repairedItem = repairedItem.copy(isDecoy = false)
                    needsUpdate = true
                }

                // If an existing item has an unverified IV (e.g. from an earlier fallback recovery),
                // check if its decrypted header is valid; if not, run mathematical AES-CTR baseIv recovery!
                if (masterKey != null) {
                    val blobFile = File(vaultBlobsDir, repairedItem.encryptedRelativePath)
                    if (blobFile.exists() && blobFile.length() >= 16L) {
                        val currentIv = fromHex(repairedItem.baseIvHex)
                        val headerSample = readFirstNBytes(blobFile, 64)
                        val currentDecrypted = if (currentIv.size == 16) {
                            CryptoEngine.decryptHeaderBytesCtr(headerSample, masterKey, currentIv)
                        } else {
                            ByteArray(0)
                        }
                        if (CryptoEngine.verifyDecryptedHeader64(currentDecrypted) == null) {
                            val recoveredIvAndMime = recoverOrphanedBinBaseIvAndMime(
                                blobFile = blobFile,
                                masterKey = masterKey,
                                siblingPlainHeaders = siblingPlainHeaders
                            )
                            if (recoveredIvAndMime != null) {
                                val (trueIv, trueMime) = recoveredIvAndMime
                                val newExt = extensionFromMimeType(trueMime)
                                val baseTitle = repairedItem.fileName.substringBeforeLast('.')
                                    .replace("Recovered_Photo", if (trueMime.startsWith("video")) "Recovered_Video" else "Recovered_Photo")
                                val updatedFileName = "$baseTitle$newExt"
                                val unlockedDecrypted = CryptoEngine.decryptHeaderBytesCtr(headerSample, masterKey, trueIv)
                                if (unlockedDecrypted.size >= 16) {
                                    siblingPlainHeaders.add(0, unlockedDecrypted.copyOfRange(0, 16))
                                }
                                val durMs = if (trueMime.startsWith("video") && repairedItem.durationMs <= 0L) {
                                    extractEncryptedVideoDurationMs(blobFile, masterKey, trueIv)
                                } else {
                                    repairedItem.durationMs
                                }
                                repairedItem = repairedItem.copy(
                                    fileName = updatedFileName,
                                    mimeType = trueMime,
                                    durationMs = durMs,
                                    baseIvHex = toHex(trueIv),
                                    isDecoy = false
                                )
                                needsUpdate = true
                                repairedExistingCount++
                            }
                        }
                    }
                }

                if (needsUpdate) {
                    vaultDao.updateItem(repairedItem)
                    if (masterKey != null) {
                        writeSidecarMetadata(repairedItem, masterKey)
                    }
                }
                indexedByPath[repairedItem.encryptedRelativePath] = repairedItem
            }

            // Map folder names to IDs for subfolder assignment
            val foldersByNameLower = existingFolders.values
                .associateBy { it.folderName.lowercase(Locale.US) }
                .toMutableMap()

            val newlyRecoveredItems = CopyOnWriteArrayList<VaultItem>()
            val totalPhysicalCount = physicalFilesWithSubfolder.size
            var seqCounter = indexedByPath.size + 1

            for ((index, pair) in physicalFilesWithSubfolder.withIndex()) {
                val (blobFile, subfolderName) = pair
                val blobName = blobFile.name

                if (showProgress && totalPhysicalCount > 0) {
                    val pct = (((index + 1) * 90L) / totalPhysicalCount.toLong()).toInt().coerceIn(5, 95)
                    VaultRepository.updateProgress(
                        title = "Scanning Vault Directories...",
                        currentItem = index + 1,
                        totalItems = totalPhysicalCount,
                        percentage = pct,
                        subText = "Checked ${index + 1} / $totalPhysicalCount physical files"
                    )
                }

                if (indexedByPath.containsKey(blobName)) {
                    continue
                }

                // Resolve subfolder ID if physical file was inside a named subfolder
                var resolvedSubfolderId: Long? = null
                if (!subfolderName.isNullOrBlank()) {
                    val existingFolder = foldersByNameLower[subfolderName.lowercase(Locale.US)]
                    if (existingFolder != null) {
                        resolvedSubfolderId = existingFolder.id
                    } else {
                        val newFolder = FolderItem(
                            folderName = subfolderName,
                            isLocked = false,
                            lockType = "MAIN_PIN",
                            isDecoy = false
                        )
                        val newFolderId = folderDao.insertFolder(newFolder)
                        val savedFolder = newFolder.copy(id = newFolderId)
                        existingFolders[newFolderId] = savedFolder
                        foldersByNameLower[subfolderName.lowercase(Locale.US)] = savedFolder
                        resolvedSubfolderId = newFolderId
                    }
                }

                // Try Sidecar -> Catalog -> Legacy DB -> Mathematical AES-CTR Header Reconstruction
                val knownMeta = sidecarItemsByPath[blobName]
                    ?: catalogItemsByPath[blobName]
                    ?: legacyDbItemsByPath[blobName]

                val recoveredItem = if (knownMeta != null && knownMeta.baseIvHex.length == 32) {
                    val validFolderId = knownMeta.folderId?.takeIf { it in existingFolders } ?: resolvedSubfolderId
                    // Verify if knownMeta's baseIvHex actually decrypts to a valid media header; if not, try mathematical recovery
                    val verifiedKnownItem = if (masterKey != null) {
                        val headerSample = readFirstNBytes(blobFile, 64)
                        val knownIv = fromHex(knownMeta.baseIvHex)
                        val dec = CryptoEngine.decryptHeaderBytesCtr(headerSample, masterKey, knownIv)
                        if (CryptoEngine.verifyDecryptedHeader64(dec) != null) {
                            if (dec.size >= 16) siblingPlainHeaders.add(0, dec.copyOfRange(0, 16))
                            knownMeta
                        } else {
                            val mathRecovered = recoverOrphanedBinBaseIvAndMime(blobFile, masterKey, siblingPlainHeaders)
                            if (mathRecovered != null) {
                                val (trueIv, trueMime) = mathRecovered
                                val decTrue = CryptoEngine.decryptHeaderBytesCtr(headerSample, masterKey, trueIv)
                                if (decTrue.size >= 16) siblingPlainHeaders.add(0, decTrue.copyOfRange(0, 16))
                                knownMeta.copy(
                                    mimeType = trueMime,
                                    baseIvHex = toHex(trueIv)
                                )
                            } else {
                                knownMeta
                            }
                        }
                    } else {
                        knownMeta
                    }

                    verifiedKnownItem.copy(
                        id = 0L,
                        encryptedRelativePath = blobName,
                        fileSize = blobFile.length(),
                        folderId = validFolderId,
                        isDecoy = false
                    )
                } else {
                    reconstructVaultItemFromPhysicalFile(
                        blobFile = blobFile,
                        subfolderId = resolvedSubfolderId,
                        sequenceIndex = seqCounter++,
                        masterKey = masterKey,
                        isDecoy = false,
                        siblingPlainHeaders = siblingPlainHeaders
                    )
                }

                newlyRecoveredItems.add(recoveredItem)
                indexedByPath[blobName] = recoveredItem
            }

            if (newlyRecoveredItems.isNotEmpty()) {
                val toInsert = newlyRecoveredItems.toList()
                val insertedIds = ArrayList<Long>(toInsert.size)
                toInsert.chunked(500).forEach { chunk ->
                    insertedIds.addAll(vaultDao.insertItems(chunk))
                }
                if (masterKey != null) {
                    toInsert.forEachIndexed { idx, item ->
                        val savedId = insertedIds.getOrElse(idx) { item.id }
                        val savedItem = item.copy(id = savedId)
                        writeSidecarMetadata(savedItem, masterKey)
                        // Link existing thumbnail or generate missing mid-video/image thumbnail
                        val thumbFile = File(vaultThumbsDir, "${savedItem.encryptedRelativePath}.thumb")
                        if (!thumbFile.exists() || thumbFile.length() <= 0L) {
                            try {
                                ThumbnailCacheManager.loadThumbnail(
                                    item = savedItem,
                                    blobFile = File(vaultBlobsDir, savedItem.encryptedRelativePath),
                                    masterKey = masterKey
                                )
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }

            if (masterKey != null) {
                // Ensure every indexed item has a sidecar metadata file on disk
                val finalItems = vaultDao.getAllItemsSync()
                for (item in finalItems) {
                    val metaFile = File(vaultBlobsDir, "${item.encryptedRelativePath}.meta")
                    if (!metaFile.exists() || metaFile.length() <= 0L) {
                        writeSidecarMetadata(item, masterKey)
                    }
                }
                syncEncryptedCatalogLocked(masterKey)
            }

            val totalRecoveredOrRepaired = newlyRecoveredItems.size + repairedExistingCount

            if (showProgress) {
                VaultRepository.updateProgress(
                    title = "Scanning Vault Directories...",
                    currentItem = totalPhysicalCount,
                    totalItems = totalPhysicalCount,
                    percentage = 100,
                    subText = "Recovered $totalRecoveredOrRepaired file(s) • $totalPhysicalCount total",
                    forceEmit = true
                )
                VaultRepository.hideProgress()
            }

            return VaultRecoveryReport(
                recoveredCount = totalRecoveredOrRepaired,
                totalVaultFilesOnDisk = indexedByPath.size
            )
        } catch (_: Throwable) {
            if (showProgress) {
                VaultRepository.hideProgress()
            }
            val currentCount = try {
                vaultDao.getAllItemsSync().size
            } catch (_: Throwable) {
                0
            }
            return VaultRecoveryReport(recoveredCount = 0, totalVaultFilesOnDisk = currentCount)
        }
    }

    /**
     * Fix 4A: Mathematical AES-CTR `baseIv` + `mimeType` recovery for `.bin` files missing `.bin.meta`.
     */
    fun recoverOrphanedBinBaseIvAndMime(
        blobFile: File,
        masterKey: SecretKey,
        siblingPlainHeaders: List<ByteArray> = collectSiblingPlainHeaders(masterKey)
    ): Pair<ByteArray, String>? {
        return CryptoEngine.recoverOrphanedBinBaseIvAndMime(
            blobFile = blobFile,
            masterKey = masterKey,
            siblingPlainHeaders = siblingPlainHeaders
        )
    }

    /**
     * Builds a prioritized list of 16-byte plaintext media headers ($P_0$) from:
     * 1) Already-indexed vault `.bin` blobs (decrypted using their known `baseIvHex`).
     * 2) Existing media files in `/storage/emulated/0/Pictures/Restored_Vault`, watched folders,
     *    `DCIM/Camera`, `Movies`, `Download`, and `Pictures` (up to 60 files).
     */
    private fun collectSiblingPlainHeaders(
        masterKey: SecretKey,
        existingVaultItems: List<VaultItem> = emptyList()
    ): MutableList<ByteArray> {
        val headers = ArrayList<ByteArray>(80)
        val seenHex = HashSet<String>()

        // 1. From already-indexed vault blobs with valid baseIvHex
        for (item in existingVaultItems) {
            if (headers.size >= 40) break
            if (item.baseIvHex.length != 32) continue
            try {
                val blob = File(vaultBlobsDir, item.encryptedRelativePath)
                if (!blob.exists() || blob.length() < 64L) continue
                val iv = fromHex(item.baseIvHex)
                val enc64 = readFirstNBytes(blob, 64)
                val dec64 = CryptoEngine.decryptHeaderBytesCtr(enc64, masterKey, iv)
                if (CryptoEngine.verifyDecryptedHeader64(dec64) != null) {
                    val p0 = dec64.copyOfRange(0, 16)
                    val hex = toHex(p0)
                    if (seenHex.add(hex)) {
                        headers.add(p0)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. From sibling files on device storage (Restored_Vault, watched folders, DCIM/Camera, Movies, Download)
        val extRoot = Environment.getExternalStorageDirectory()
        val candidateDirs = LinkedHashSet<File>().apply {
            add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Restored_Vault"))
            try {
                VaultPreferences(context).watchedFolders.value.forEach { add(File(it)) }
            } catch (_: Throwable) {
            }
            add(File(extRoot, "DCIM/Camera"))
            add(File(extRoot, "Movies"))
            add(File(extRoot, "Download"))
            add(File(extRoot, "Pictures"))
            add(File(extRoot, "WhatsApp/Media/WhatsApp Video"))
            add(File(extRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video"))
            add(File(extRoot, "Telegram/Telegram Video"))
            add(File(extRoot, "Movies/Instagram"))
        }

        var deviceFilesSampled = 0
        for (dir in candidateDirs) {
            if (deviceFilesSampled >= 50) break
            if (!dir.exists() || !dir.isDirectory) continue
            val files = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue

            for (f in files) {
                if (deviceFilesSampled >= 50) break
                if (!f.isFile || f.length() < 64L || !isSupportedMediaFileName(f.name)) continue
                val first16 = readFirstNBytes(f, 16)
                if (first16.size == 16 && CryptoEngine.detectMediaSignature(first16) != null) {
                    deviceFilesSampled++
                    val hex = toHex(first16)
                    if (seenHex.add(hex)) {
                        headers.add(first16)
                    }
                }
            }
        }

        return headers
    }

    private fun readFirstNBytes(file: File, count: Int): ByteArray {
        val buf = ByteArray(count)
        var total = 0
        try {
            FileInputStream(file).use { fis ->
                while (total < count) {
                    val r = fis.read(buf, total, count - total)
                    if (r <= 0) break
                    total += r
                }
            }
        } catch (_: Throwable) {
            return ByteArray(0)
        }
        return if (total == count) buf else if (total > 0) buf.copyOfRange(0, total) else ByteArray(0)
    }

    /**
     * Extracts the duration in milliseconds of an AES-CTR encrypted video blob using a streaming
     * `MediaDataSource` over `RandomAccessFile` + `CryptoEngine.calculateCtrIvForOffset`.
     */
    private fun extractEncryptedVideoDurationMs(
        blobFile: File,
        masterKey: SecretKey,
        baseIv: ByteArray
    ): Long {
        if (!blobFile.exists() || blobFile.length() < 64L || baseIv.size != 16) return 0L
        val retriever = MediaMetadataRetriever()
        return try {
            val raf = java.io.RandomAccessFile(blobFile, "r")
            val fileLen = blobFile.length()
            val dataSource = object : android.media.MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= fileLen) return -1
                    val toRead = minOf(size.toLong(), fileLen - position).toInt()
                    if (toRead <= 0) return -1
                    val blockAlignedStart = (position / 16L) * 16L
                    val skipBytes = (position - blockAlignedStart).toInt()
                    val cipherLen = skipBytes + toRead
                    val cipherBuf = ByteArray(cipherLen)
                    val actualRead: Int
                    synchronized(raf) {
                        raf.seek(blockAlignedStart)
                        actualRead = raf.read(cipherBuf, 0, cipherLen)
                    }
                    if (actualRead <= skipBytes) return -1
                    val counterIv = CryptoEngine.calculateCtrIvForOffset(baseIv, blockAlignedStart)
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, masterKey, IvParameterSpec(counterIv))
                    val plainBuf = cipher.doFinal(cipherBuf, 0, actualRead)
                    val available = (plainBuf.size - skipBytes).coerceAtMost(toRead)
                    System.arraycopy(plainBuf, skipBytes, buffer, offset, available)
                    return available
                }

                override fun getSize(): Long = fileLen

                override fun close() {
                    try {
                        raf.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            retriever.setDataSource(dataSource)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            try {
                dataSource.close()
            } catch (_: Throwable) {
            }
            dur
        } catch (_: Throwable) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Reconstructs a valid `VaultItem` for an unindexed physical `.bin` file on disk:
     * 1. Checks if raw header bytes match unencrypted media (and encrypts in-place if so).
     * 2. Uses `recoverOrphanedBinBaseIvAndMime(blobFile, masterKey, siblingPlainHeaders)` to
     *    mathematically recover the exact 16-byte `baseIv` via AES-ECB inversion ($C_0 \oplus P_0$)
     *    verified against Block 1/2/3 (`moov`/`mdat`/`free` atoms or `IHDR`/`JFIF` markers).
     * 3. Extracts video duration and links existing `.bin.thumb` in `vaultThumbsDir`.
     * 4. Even if an exotic file doesn't match a known header, still recovers and indexes the `.bin`
     *    file with a fallback IV and its decrypted `.bin.thumb` thumbnail so NO `.bin` file is ever left behind.
     */
    private fun reconstructVaultItemFromPhysicalFile(
        blobFile: File,
        subfolderId: Long?,
        sequenceIndex: Int,
        masterKey: SecretKey?,
        isDecoy: Boolean,
        siblingPlainHeaders: MutableList<ByteArray> = ArrayList()
    ): VaultItem {
        val blobName = blobFile.name
        val actualHeader = readFirstNBytes(blobFile, 64)

        val deterministicIv = if (masterKey != null) {
            CryptoEngine.deriveDeterministicIv(masterKey, blobName)
        } else {
            ByteArray(16)
        }

        var chosenIv: ByteArray = deterministicIv
        var detectedMime: String? = null
        var detectedExt: String? = null

        // 1. Check if raw header bytes match unencrypted media magic bytes
        val rawSig = CryptoEngine.detectMediaSignature(actualHeader)
        if (rawSig != null && masterKey != null) {
            val tempEnc = File(vaultBlobsDir, ".tmp_enc_$blobName")
            try {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(deterministicIv))
                BufferedInputStream(FileInputStream(blobFile), IO_CHUNK_BUFFER_BYTES).use { bis ->
                    FileOutputStream(tempEnc).use { fos ->
                        BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bos ->
                            CipherOutputStream(bos, cipher).use { cos ->
                                val buf = ByteArray(IO_CHUNK_BUFFER_BYTES)
                                var r: Int
                                while (bis.read(buf).also { r = it } != -1) {
                                    cos.write(buf, 0, r)
                                }
                                cos.flush()
                            }
                            bos.flush()
                        }
                        try {
                            fos.fd.sync()
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (tempEnc.exists() && tempEnc.length() == blobFile.length()) {
                    blobFile.delete()
                    tempEnc.renameTo(blobFile)
                } else {
                    tempEnc.delete()
                }
            } catch (_: Throwable) {
                tempEnc.delete()
            }
            chosenIv = deterministicIv
            detectedMime = rawSig.mimeType
            detectedExt = rawSig.extension
        } else if (rawSig != null) {
            detectedMime = rawSig.mimeType
            detectedExt = rawSig.extension
        }

        // 2. Mathematical AES-CTR baseIv + mimeType recovery (checks deterministicIv, zeroIv, sibling headers, and ftyp/PNG/JPEG matrix)
        if (detectedMime == null && masterKey != null && actualHeader.size >= 16) {
            val mathRecovered = recoverOrphanedBinBaseIvAndMime(
                blobFile = blobFile,
                masterKey = masterKey,
                siblingPlainHeaders = siblingPlainHeaders
            )
            if (mathRecovered != null) {
                chosenIv = mathRecovered.first
                detectedMime = mathRecovered.second
                detectedExt = extensionFromMimeType(detectedMime!!)
                val dec64 = CryptoEngine.decryptHeaderBytesCtr(actualHeader, masterKey, chosenIv)
                if (dec64.size >= 16) {
                    siblingPlainHeaders.add(0, dec64.copyOfRange(0, 16))
                }
            }
        }

        // 3. Fallback to file extension or size heuristic if an exotic container didn't match standard headers
        if (detectedMime == null) {
            val baseNameWithoutBin = blobName.removeSuffix(".bin")
            val extCandidate = baseNameWithoutBin.substringAfterLast('.', "").lowercase(Locale.US)
            when {
                extCandidate in SUPPORTED_VIDEO_EXTENSIONS -> {
                    detectedMime = guessMimeTypeFromExtension(baseNameWithoutBin, "video/mp4")
                    detectedExt = ".$extCandidate"
                }
                extCandidate in SUPPORTED_IMAGE_EXTENSIONS -> {
                    detectedMime = guessMimeTypeFromExtension(baseNameWithoutBin, "image/jpeg")
                    detectedExt = ".$extCandidate"
                }
                blobFile.length() >= 2L * 1024L * 1024L -> {
                    detectedMime = "video/mp4"
                    detectedExt = ".mp4"
                }
                else -> {
                    detectedMime = "image/jpeg"
                    detectedExt = ".jpg"
                }
            }
        }

        val finalMime = detectedMime ?: "video/mp4"
        val finalExt = detectedExt ?: extensionFromMimeType(finalMime)
        val isVideo = finalMime.startsWith("video")
        val prefix = if (isVideo) "Recovered_Video" else "Recovered_Photo"
        val cleanOriginalName = if (!blobName.endsWith(".bin") && isSupportedMediaFileName(blobName)) {
            blobName
        } else {
            String.format(Locale.US, "%s_%03d%s", prefix, sequenceIndex, finalExt)
        }

        val durationMs = if (isVideo && masterKey != null) {
            extractEncryptedVideoDurationMs(blobFile, masterKey, chosenIv)
        } else {
            0L
        }

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val restoredPath = File(File(picturesDir, "Restored_Vault"), cleanOriginalName).absolutePath
        val lastMod = blobFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()

        return VaultItem(
            id = 0L,
            fileName = cleanOriginalName,
            originalPath = restoredPath,
            encryptedRelativePath = blobName,
            mimeType = finalMime,
            fileSize = blobFile.length(),
            dateAdded = lastMod,
            folderId = subfolderId,
            isFavorite = false,
            isDecoy = false,
            durationMs = durationMs,
            baseIvHex = toHex(chosenIv)
        )
    }

    /**
     * Fix 4C: Imports external `.bin` (and optional `.meta` / `.thumb`) files selected by the user
     * from anywhere on their phone into `vaultBlobsDir` / `vaultThumbsDir`, runs mathematical
     * `baseIv` recovery (`reconcileAndRecoverCatalog`), and optionally decrypts/exports them
     * directly to `/storage/emulated/0/Pictures/Restored_Vault` as playable `.mp4` / `.jpg` files.
     *
     * Returns `Pair(importedToVaultCount, exportedToGalleryCount)`.
     */
    suspend fun importAndRecoverExternalBinFiles(
        uris: List<Uri>,
        masterKey: SecretKey,
        exportToGallery: Boolean
    ): Pair<Int, Int> = VaultRepository.withVaultLock {
        if (uris.isEmpty()) return@withVaultLock Pair(0, 0)

        val resolver = context.contentResolver
        val importedBlobNames = LinkedHashSet<String>()
        val totalUris = uris.size

        VaultRepository.updateProgress(
            title = if (exportToGallery) "Importing & Decrypting .bin Files..." else "Importing .bin Files to Vault...",
            currentItem = 0,
            totalItems = totalUris,
            percentage = 0,
            subText = "0 / $totalUris files",
            forceEmit = true
        )

        try {
            uris.forEachIndexed { idx, uri ->
                try {
                    var displayName = ""
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1 && !c.isNull(nameIdx)) {
                                displayName = c.getString(nameIdx) ?: ""
                            }
                        }
                    }
                    if (displayName.isBlank()) {
                        displayName = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                            ?: "${UUID.randomUUID()}.bin"
                    }
                    // Strip Android duplicate suffixes like " (1)" before extension if needed
                    val sanitizedName = displayName.replace(Regex("\\s+\\(\\d+\\)(?=\\.)"), "")

                    val targetFile = when {
                        sanitizedName.endsWith(".thumb", ignoreCase = true) -> File(vaultThumbsDir, sanitizedName)
                        sanitizedName.endsWith(".meta", ignoreCase = true) -> File(vaultBlobsDir, sanitizedName)
                        sanitizedName.endsWith(".bin", ignoreCase = true) -> {
                            importedBlobNames.add(sanitizedName)
                            File(vaultBlobsDir, sanitizedName)
                        }
                        else -> {
                            val binName = "$sanitizedName.bin"
                            importedBlobNames.add(binName)
                            File(vaultBlobsDir, binName)
                        }
                    }

                    resolver.openInputStream(uri)?.use { input ->
                        BufferedInputStream(input, IO_CHUNK_BUFFER_BYTES).use { bis ->
                            FileOutputStream(targetFile).use { fos ->
                                BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bos ->
                                    val buf = ByteArray(IO_CHUNK_BUFFER_BYTES)
                                    var r: Int
                                    while (bis.read(buf).also { r = it } != -1) {
                                        bos.write(buf, 0, r)
                                    }
                                    bos.flush()
                                }
                                try {
                                    fos.fd.sync()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }

                val done = idx + 1
                val pct = ((done * 60L) / totalUris.toLong()).toInt().coerceIn(5, 60)
                VaultRepository.updateProgress(
                    title = if (exportToGallery) "Importing & Decrypting .bin Files..." else "Importing .bin Files to Vault...",
                    currentItem = done,
                    totalItems = totalUris,
                    percentage = pct,
                    subText = "Copied $done / $totalUris files"
                )
            }

            // Reconcile and mathematically recover all .bin files in vaultBlobsDir
            val report = recoverOrphanedVaultFilesLocked(
                masterKey = masterKey,
                isDecoy = false,
                showProgress = false
            )

            var exportedCount = 0
            if (exportToGallery) {
                val allItemsByPath = vaultDao.getAllItemsSync().associateBy { it.encryptedRelativePath }
                val targetsToExport = if (importedBlobNames.isNotEmpty()) {
                    importedBlobNames.mapNotNull { allItemsByPath[it] }
                } else {
                    allItemsByPath.values.toList()
                }

                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val exportDir = File(picturesDir, "Restored_Vault")
                if (!exportDir.exists()) exportDir.mkdirs()
                val scannedPaths = ArrayList<String>()

                targetsToExport.forEachIndexed { idx, item ->
                    val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
                    if (blobFile.exists() && blobFile.length() > 0L) {
                        val baseIv = fromHex(item.baseIvHex)
                        var outFile = File(exportDir, item.fileName)
                        if (outFile.exists()) {
                            val namePart = item.fileName.substringBeforeLast('.')
                            val extPart = item.fileName.substringAfterLast('.', "")
                            val suffix = if (extPart.isNotEmpty()) ".$extPart" else ""
                            outFile = File(exportDir, "${namePart}_${System.currentTimeMillis()}$suffix")
                        }
                        try {
                            BufferedInputStream(FileInputStream(blobFile), IO_CHUNK_BUFFER_BYTES).use { bis ->
                                FileOutputStream(outFile).use { fos ->
                                    BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bos ->
                                        CryptoEngine.decryptStreamCtr(
                                            inputStream = bis,
                                            outputStream = bos,
                                            secretKey = masterKey,
                                            baseIv = baseIv
                                        )
                                        bos.flush()
                                    }
                                    try {
                                        fos.fd.sync()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                            if (outFile.exists() && outFile.length() > 0L) {
                                exportedCount++
                                scannedPaths.add(outFile.absolutePath)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    val pct = 60 + (((idx + 1) * 40L) / targetsToExport.size.coerceAtLeast(1)).toInt().coerceIn(0, 40)
                    VaultRepository.updateProgress(
                        title = "Exporting Decrypted Media...",
                        currentItem = idx + 1,
                        totalItems = targetsToExport.size,
                        percentage = pct,
                        subText = "Decrypted ${idx + 1} / ${targetsToExport.size} to /Pictures/Restored_Vault"
                    )
                }

                if (scannedPaths.isNotEmpty()) {
                    try {
                        MediaScannerConnection.scanFile(context, scannedPaths.toTypedArray(), null, null)
                    } catch (_: Throwable) {
                    }
                }
            }

            Pair(maxOf(importedBlobNames.size, report.recoveredCount), exportedCount)
        } finally {
            VaultRepository.hideProgress()
        }
    }

    /**
     * Consolidates any `.bin`, `.meta`, and `.thumb` files that may have been created inside
     * internal or app-specific fallback `.LocalVaultData` directories into the primary `vaultRootDir`.
     */
    private fun consolidateFallbackVaultDirectories() {
        val activeRootPath = try {
            vaultRootDir.canonicalPath
        } catch (_: Throwable) {
            vaultRootDir.absolutePath
        }

        val fallbackRoots = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, ".LocalVaultData") },
            File(context.filesDir, ".LocalVaultData")
        )

        for (fbRoot in fallbackRoots) {
            if (!fbRoot.exists() || !fbRoot.isDirectory) continue
            val fbCanonical = try {
                fbRoot.canonicalPath
            } catch (_: Throwable) {
                fbRoot.absolutePath
            }
            if (fbCanonical == activeRootPath) continue

            val fbBlobs = File(fbRoot, "blobs")
            if (fbBlobs.exists() && fbBlobs.isDirectory) {
                fbBlobs.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 0L) {
                        val dest = File(vaultBlobsDir, f.name)
                        if (!dest.exists()) {
                            try {
                                f.copyTo(dest, overwrite = false)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }

            val fbThumbs = File(fbRoot, "thumbs")
            if (fbThumbs.exists() && fbThumbs.isDirectory) {
                fbThumbs.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 0L) {
                        val dest = File(vaultThumbsDir, f.name)
                        if (!dest.exists()) {
                            try {
                                f.copyTo(dest, overwrite = false)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads any rows from the internal `local_vault.db` SQLite file if the app previously ran
     * before `MANAGE_EXTERNAL_STORAGE` switched the database path to external `.LocalVaultData`.
     */
    private fun loadLegacyInternalDatabaseItems(
        outItems: MutableMap<String, VaultItem>,
        outFolders: MutableMap<Long, FolderItem>
    ) {
        try {
            val internalDbFile = context.getDatabasePath("local_vault.db")
            val externalDbFile = File(vaultRootDir, "local_vault.db")
            if (!internalDbFile.exists() || internalDbFile.length() <= 0L) return
            if (internalDbFile.canonicalPath == externalDbFile.canonicalPath) return

            SQLiteDatabase.openDatabase(
                internalDbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                try {
                    db.rawQuery("SELECT id, folderName, isLocked, lockType, customPinSaltHex, customPinSlotAHex, isDecoy FROM folder_items", null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            outFolders[id] = FolderItem(
                                id = id,
                                folderName = c.getString(1) ?: "Folder",
                                isLocked = c.getInt(2) != 0,
                                lockType = c.getString(3) ?: "MAIN_PIN",
                                customPinSaltHex = c.getString(4),
                                customPinSlotAHex = c.getString(5),
                                isDecoy = c.getInt(6) != 0
                            )
                        }
                    }
                } catch (_: Throwable) {
                }

                try {
                    db.rawQuery(
                        "SELECT fileName, originalPath, encryptedRelativePath, mimeType, fileSize, dateAdded, folderId, isFavorite, isDecoy, durationMs, baseIvHex FROM vault_items",
                        null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val relPath = c.getString(2) ?: ""
                            if (relPath.isNotBlank()) {
                                outItems[relPath] = VaultItem(
                                    id = 0L,
                                    fileName = c.getString(0) ?: relPath,
                                    originalPath = c.getString(1) ?: "",
                                    encryptedRelativePath = relPath,
                                    mimeType = c.getString(3) ?: "image/jpeg",
                                    fileSize = c.getLong(4),
                                    dateAdded = c.getLong(5),
                                    folderId = if (c.isNull(6)) null else c.getLong(6),
                                    isFavorite = c.getInt(7) != 0,
                                    isDecoy = c.getInt(8) != 0,
                                    durationMs = c.getLong(9),
                                    baseIvHex = c.getString(10) ?: ""
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Requirement 1 & 3: Scans all physical vault directories, recovers any missing/orphaned
     * physical files into the metadata index, and immediately unhides (restores) ALL vault files
     * back to `/storage/emulated/0/Pictures/Restored_Vault` (or their original paths).
     */
    suspend fun recoverAndUnhideAllPhysicalFiles(
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): Pair<Int, String> {
        // First recover any orphaned physical files on disk into the database
        recoverOrphanedVaultFiles(masterKey = masterKey, isDecoy = isDecoy, showProgress = false)
        val allItems = VaultRepository.withVaultLock {
            vaultDao.getAllItemsByDecoySync(isDecoy = isDecoy)
        }
        if (allItems.isEmpty()) {
            return Pair(0, "/Pictures/Restored_Vault")
        }
        return unhideBatchWithDestination(allItems, masterKey)
    }

    /**
     * Holds a staged encrypted file produced by a parallel worker BEFORE deleting the original
     * source file from the phone's gallery (Requirement 2: Two-Phase Commit Transaction Safety).
     */
    private data class StagedImportItem(
        val item: VaultItem,
        val blobFile: File,
        val sourceUri: Uri,
        val resolvedFsPath: String,
        val mediaStoreId: Long?
    )

    private val folderCreationLock = Any()

    /**
     * Fix 2: Sweeps all watched folder paths inside `VaultRepository.vaultMutex` using bounded
     * parallel workers (`Semaphore(3)`), where `encryptAndPrepareItemWorker` commits each file
     * atomically inside `withContext(NonCancellable)` (`tempBlobFile` -> `fd.sync()` -> `renameTo`
     * -> `vaultDao.insertItem` -> `writeSidecarMetadata` -> verify DB + `.meta`) BEFORE deleting
     * the original file from the phone.
     */
    suspend fun sweepWatchedFoldersNow(
        watchedPaths: Set<String>,
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): Int = VaultRepository.withVaultLock {
        val candidateFiles = ArrayList<File>()
        watchedPaths.forEach { pathStr ->
            try {
                val folder = File(pathStr)
                if (folder.exists() && folder.isDirectory && !folder.absolutePath.contains("/.LocalVaultData")) {
                    folder.listFiles()?.forEach { file ->
                        if (file.isFile && !file.name.startsWith(".") && file.length() > 0L && isSupportedMediaFileName(file.name)) {
                            candidateFiles.add(file)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (candidateFiles.isEmpty()) return@withVaultLock 0

        val semaphore = Semaphore(permits = MAX_CONCURRENT_IO_WORKERS)
        val seenPaths = ConcurrentHashMap.newKeySet<String>()
        val deletedPaths = CopyOnWriteArrayList<String>()
        val importedItems = CopyOnWriteArrayList<VaultItem>()

        try {
            val workerResults = coroutineScope {
                candidateFiles.map { file ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            encryptAndPrepareItemWorker(
                                uri = Uri.fromFile(file),
                                originalPathOverride = file.absolutePath,
                                targetFolderId = null,
                                masterKey = masterKey,
                                isDecoy = isDecoy,
                                alreadyImportedPathsInBatch = seenPaths,
                                deletedPathsOut = deletedPaths
                            )
                        }
                    }
                }.awaitAll()
            }
            importedItems.addAll(workerResults.filterNotNull())
        } finally {
            if (importedItems.isNotEmpty()) {
                syncEncryptedCatalogLocked(masterKey)
                if (deletedPaths.isNotEmpty()) {
                    try {
                        MediaScannerConnection.scanFile(context, deletedPaths.distinct().toTypedArray(), null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        importedItems.size
    }

    /**
     * Imports a single URI inside the global `VaultRepository.vaultMutex` using the self-contained
     * atomic `encryptAndPrepareItemWorker`.
     */
    suspend fun importFile(
        uri: Uri,
        originalPathOverride: String? = null,
        targetFolderId: Long? = null,
        masterKey: SecretKey,
        isDecoy: Boolean = false
    ): VaultItem? = VaultRepository.withVaultLock {
        val deletedPaths = CopyOnWriteArrayList<String>()
        val savedItem = encryptAndPrepareItemWorker(
            uri = uri,
            originalPathOverride = originalPathOverride,
            targetFolderId = targetFolderId,
            masterKey = masterKey,
            isDecoy = isDecoy,
            alreadyImportedPathsInBatch = null,
            deletedPathsOut = deletedPaths
        ) ?: return@withVaultLock null

        syncEncryptedCatalogLocked(masterKey)
        if (deletedPaths.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(context, deletedPaths.distinct().toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        savedItem
    }

    /**
     * Fix 2: Thread-Safe Parallel Batch URI Import where each file is atomically encrypted,
     * persisted to Room DB, and written to `.bin.meta` inside `withContext(NonCancellable)`
     * BEFORE its original source file is deleted.
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

        val semaphore = Semaphore(permits = MAX_CONCURRENT_IO_WORKERS)
        val completedCount = AtomicInteger(0)
        val importedSourcePathsInBatch = ConcurrentHashMap.newKeySet<String>()
        val importedItems = CopyOnWriteArrayList<VaultItem>()
        val deletedPaths = CopyOnWriteArrayList<String>()

        try {
            val workerOutputs = coroutineScope {
                distinctUris.map { uri ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val res = encryptAndPrepareItemWorker(
                                uri = uri,
                                originalPathOverride = null,
                                targetFolderId = targetFolderId,
                                masterKey = masterKey,
                                isDecoy = isDecoy,
                                alreadyImportedPathsInBatch = importedSourcePathsInBatch,
                                deletedPathsOut = deletedPaths
                            )
                            val done = completedCount.incrementAndGet()
                            val pct = ((done * 95L) / total.toLong()).toInt().coerceIn(0, 95)
                            VaultRepository.updateProgress(
                                title = "Importing Media...",
                                currentItem = done,
                                totalItems = total,
                                percentage = pct,
                                subText = "$done / $total files"
                            )
                            res
                        }
                    }
                }.awaitAll()
            }

            importedItems.addAll(workerOutputs.filterNotNull())

            VaultRepository.updateProgress(
                title = "Importing Media...",
                currentItem = total,
                totalItems = total,
                percentage = 100,
                subText = "${importedItems.size} / $total files",
                forceEmit = true
            )
        } finally {
            syncEncryptedCatalogLocked(masterKey)
            if (deletedPaths.isNotEmpty()) {
                try {
                    MediaScannerConnection.scanFile(context, deletedPaths.distinct().toTypedArray(), null, null)
                } catch (_: Exception) {
                }
            }
            VaultRepository.hideProgress()
        }
        importedItems.size
    }

    /**
     * Fix 2: Thread-Safe Parallel Batch Direct File Import where each file is atomically encrypted,
     * persisted to Room DB, and written to `.bin.meta` inside `withContext(NonCancellable)`
     * BEFORE its original source file is deleted.
     */
    suspend fun importBatchDirectFiles(
        paths: List<String>,
        targetFolderId: Long?,
        masterKey: SecretKey,
        isDecoy: Boolean
    ): Int = VaultRepository.withVaultLock {
        val validPaths = paths.distinct().filter { p ->
            val f = File(p)
            f.exists() && f.isFile && f.length() > 0L
        }
        val total = validPaths.size
        if (total == 0) return@withVaultLock 0

        VaultRepository.updateProgress(
            title = "Importing Media...",
            currentItem = 0,
            totalItems = total,
            percentage = 0,
            subText = "0 / $total files",
            forceEmit = true
        )

        val semaphore = Semaphore(permits = MAX_CONCURRENT_IO_WORKERS)
        val completedCount = AtomicInteger(0)
        val importedSourcePathsInBatch = ConcurrentHashMap.newKeySet<String>()
        val importedItems = CopyOnWriteArrayList<VaultItem>()
        val deletedPaths = CopyOnWriteArrayList<String>()

        try {
            val workerOutputs = coroutineScope {
                validPaths.map { path ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val file = File(path)
                            val res = encryptAndPrepareItemWorker(
                                uri = Uri.fromFile(file),
                                originalPathOverride = path,
                                targetFolderId = targetFolderId,
                                masterKey = masterKey,
                                isDecoy = isDecoy,
                                alreadyImportedPathsInBatch = importedSourcePathsInBatch,
                                deletedPathsOut = deletedPaths
                            )
                            val done = completedCount.incrementAndGet()
                            val pct = ((done * 95L) / total.toLong()).toInt().coerceIn(0, 95)
                            VaultRepository.updateProgress(
                                title = "Importing Media...",
                                currentItem = done,
                                totalItems = total,
                                percentage = pct,
                                subText = "$done / $total files"
                            )
                            res
                        }
                    }
                }.awaitAll()
            }

            importedItems.addAll(workerOutputs.filterNotNull())

            VaultRepository.updateProgress(
                title = "Importing Media...",
                currentItem = total,
                totalItems = total,
                percentage = 100,
                subText = "${importedItems.size} / $total files",
                forceEmit = true
            )
        } finally {
            syncEncryptedCatalogLocked(masterKey)
            if (deletedPaths.isNotEmpty()) {
                try {
                    MediaScannerConnection.scanFile(context, deletedPaths.distinct().toTypedArray(), null, null)
                } catch (_: Exception) {
                }
            }
            VaultRepository.hideProgress()
        }
        importedItems.size
    }

    /**
     * Fix 2: Self-Contained, Atomic, Non-Cancellable Per-File Encryption & Persistence Worker:
     * 1) Encrypts the stream into a temporary file `val tempBlobFile = File(vaultBlobsDir, ".tmp_$blobName")`.
     * 2) Flushes `cipherOut`, flushes `bufferedOut`, and calls `fos.fd.sync()` before closing `FileOutputStream`.
     * 3) Verifies `tempBlobFile.exists() && tempBlobFile.length() == totalWritten && totalWritten > 0L`.
     *    Renames `tempBlobFile.renameTo(blobFile)` and verifies `blobFile.exists() && blobFile.length() == totalWritten`.
     * 4) Generates the thumbnail into `thumbFile`.
     * 5) Inside `withContext(NonCancellable)`:
     *    - Constructs the `VaultItem`.
     *    - Immediately inserts it into Room Database: `val insertedId = vaultDao.insertItem(item)` and creates `val savedItem = item.copy(id = insertedId)`.
     *    - Immediately writes and `fd.sync()`s the sidecar metadata file `writeSidecarMetadata(savedItem, masterKey)` (`.bin.meta`)
     *      so `baseIvHex` and all metadata are permanently on disk before the original file is ever touched!
     *    - Verifies that `vaultDao.getItemByEncryptedPath(blobName) != null` AND `File(vaultBlobsDir, "$blobName.meta").let { it.exists() && it.length() > 0L }`.
     * 6) ONLY AFTER `blobFile`, `metaFile` (`.bin.meta`), and the Room DB row (`insertedId > 0L`) are all verified on disk,
     *    calls `deleteOriginalFileSafely(...)`! If any check fails, deletes `blobFile`/`tempBlobFile` and NEVER deletes the user's original file!
     */
    private suspend fun encryptAndPrepareItemWorker(
        uri: Uri,
        originalPathOverride: String? = null,
        targetFolderId: Long? = null,
        masterKey: SecretKey,
        isDecoy: Boolean = false,
        alreadyImportedPathsInBatch: MutableSet<String>? = null,
        deletedPathsOut: MutableList<String>? = null
    ): VaultItem? {
        val resolver = context.contentResolver
        var fileName = ""
        var reportedSize = 0L
        var mimeType = try {
            resolver.getType(uri) ?: ""
        } catch (_: Throwable) {
            ""
        }
        var resolvedFsPath = originalPathOverride ?: ""
        var mediaStoreId: Long? = null

        try {
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
                } catch (_: Throwable) {
                }
            }

            // 2. Resolve real filesystem path via DocumentsContract / MediaStore
            if (resolvedFsPath.isEmpty() || !File(resolvedFsPath).exists()) {
                val resolved = resolveRealPathAndId(uri, fileName, reportedSize)
                if (resolved.first != null) {
                    resolvedFsPath = resolved.first!!
                }
                if (resolved.second != null) {
                    mediaStoreId = resolved.second
                }
            }

            // Deduplicate only by exact canonical source path or unique URI string when path is unavailable
            val dedupKey = if (resolvedFsPath.isNotEmpty()) resolvedFsPath else uri.toString()
            if (alreadyImportedPathsInBatch != null && !alreadyImportedPathsInBatch.add(dedupKey)) {
                return null
            }

            if (fileName.isEmpty() && resolvedFsPath.isNotEmpty()) {
                fileName = File(resolvedFsPath).name
            }

            val detectedExt = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (mimeType.isBlank() ||
                mimeType == "application/octet-stream" ||
                mimeType == "*/*" ||
                mimeType.endsWith("/*")
            ) {
                mimeType = guessMimeTypeFromExtension(fileName, mimeType)
            } else if (detectedExt in ALL_SUPPORTED_EXTENSIONS) {
                val extMime = guessMimeTypeFromExtension(fileName, mimeType)
                if (extMime.isNotBlank()) {
                    mimeType = extMime
                }
            }

            if (fileName.isEmpty()) {
                val ext = extensionFromMimeType(mimeType)
                fileName = "Media_${System.currentTimeMillis()}$ext"
            }

            // 3. Extract video duration before encrypting
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
            val tempBlobFile = File(vaultBlobsDir, ".tmp_$blobName")
            val blobFile = File(vaultBlobsDir, blobName)
            val metaFile = File(vaultBlobsDir, "$blobName.meta")
            val thumbFile = File(vaultThumbsDir, "$blobName.thumb")
            // Deterministic 16-byte IV derived from masterKey + blobName so it is 100% recoverable even if sidecars are lost
            val baseIv = CryptoEngine.deriveDeterministicIv(masterKey, blobName)

            var rawInputStream: InputStream? = null
            var totalWritten = 0L

            try {
                rawInputStream = if (resolvedFsPath.isNotEmpty() && File(resolvedFsPath).exists()) {
                    FileInputStream(File(resolvedFsPath))
                } else {
                    resolver.openInputStream(uri)
                }

                if (rawInputStream == null) {
                    return null
                }

                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, masterKey, IvParameterSpec(baseIv))

                // Step 1 & 2: Encrypt stream into tempBlobFile and flush + fd.sync() before closing
                BufferedInputStream(rawInputStream, IO_CHUNK_BUFFER_BYTES).use { bufferedIn ->
                    FileOutputStream(tempBlobFile).use { fos ->
                        BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bufferedOut ->
                            CipherOutputStream(bufferedOut, cipher).use { cipherOut ->
                                val buffer = ByteArray(IO_CHUNK_BUFFER_BYTES)
                                var read: Int
                                while (bufferedIn.read(buffer).also { read = it } != -1) {
                                    cipherOut.write(buffer, 0, read)
                                    totalWritten += read
                                }
                                cipherOut.flush()
                            }
                            bufferedOut.flush()
                        }
                        try {
                            fos.fd.sync()
                        } catch (_: Throwable) {
                        }
                    }
                }

                // Step 3: Verify tempBlobFile and atomically rename to blobFile
                if (!tempBlobFile.exists() || tempBlobFile.length() != totalWritten || totalWritten <= 0L) {
                    tempBlobFile.delete()
                    return null
                }

                if (!tempBlobFile.renameTo(blobFile)) {
                    tempBlobFile.copyTo(blobFile, overwrite = true)
                    tempBlobFile.delete()
                }

                if (!blobFile.exists() || blobFile.length() != totalWritten || totalWritten <= 0L) {
                    tempBlobFile.delete()
                    blobFile.delete()
                    return null
                }

                // Step 4: Generate smart mid-video (35%/50%/65%) or image thumbnail into thumbFile
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

                // Step 5 & 6: Inside withContext(NonCancellable), commit DB row + .bin.meta sidecar,
                // verify both exist on disk, and ONLY THEN delete the original source file!
                return withContext(NonCancellable) {
                    val item = VaultItem(
                        fileName = fileName,
                        originalPath = finalOriginalPath,
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
                    val savedItem = item.copy(id = insertedId)
                    writeSidecarMetadata(savedItem, masterKey)

                    val verifiedDbRow = insertedId > 0L && vaultDao.getItemByEncryptedPath(blobName) != null
                    val verifiedMetaOnDisk = metaFile.exists() && metaFile.length() > 0L
                    val verifiedBlobOnDisk = blobFile.exists() && blobFile.length() == totalWritten && totalWritten > 0L

                    if (!verifiedDbRow || !verifiedMetaOnDisk || !verifiedBlobOnDisk) {
                        // Rollback encrypted artifacts and NEVER delete user's original file
                        if (insertedId > 0L) {
                            try {
                                vaultDao.deleteItem(savedItem)
                            } catch (_: Throwable) {
                            }
                        }
                        tempBlobFile.delete()
                        blobFile.delete()
                        metaFile.delete()
                        thumbFile.delete()
                        return@withContext null
                    }

                    val deletedPath = deleteOriginalFileSafely(
                        uri = uri,
                        fsPath = resolvedFsPath,
                        fileName = savedItem.fileName,
                        reportedSize = savedItem.fileSize,
                        mediaStoreId = mediaStoreId,
                        resolver = resolver,
                        runMediaScanImmediately = false
                    )
                    if (deletedPath != null) {
                        deletedPathsOut?.add(deletedPath)
                    }

                    savedItem
                }
            } catch (_: Throwable) {
                tempBlobFile.delete()
                blobFile.delete()
                metaFile.delete()
                thumbFile.delete()
                return null
            } finally {
                try {
                    rawInputStream?.close()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            return null
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

    private data class UnhideWorkerResult(
        val item: VaultItem,
        val restoredAbsPath: String,
        val destFolderDisplay: String
    )

    /**
     * Ultra-Fast Batch Unhide (Hotfix #2):
     * - Decrypts using 4MB buffered File streams with `Semaphore(permits = 3)` bounded concurrency.
     * - Updates Room and flushes `syncEncryptedCatalogLocked` ONLY ONCE in `finally`.
     * - Executes ONE batch `MediaScannerConnection.scanFile` across all restored file paths at the end.
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

        val semaphore = Semaphore(permits = MAX_CONCURRENT_IO_WORKERS)
        val completedCount = AtomicInteger(0)
        val reservedTargetPaths = ConcurrentHashMap.newKeySet<String>()
        val restoredResults = ArrayList<UnhideWorkerResult>()

        try {
            val workerOutputs = coroutineScope {
                distinctItems.map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val res = decryptAndRestoreFileWorker(
                                item = item,
                                masterKey = masterKey,
                                reservedTargetPaths = reservedTargetPaths
                            )
                            val done = completedCount.incrementAndGet()
                            val pct = ((done * 100L) / total.toLong()).toInt().coerceIn(0, 100)
                            VaultRepository.updateProgress(
                                title = "Restoring to Device...",
                                currentItem = done,
                                totalItems = total,
                                percentage = pct,
                                subText = "$done / $total files",
                                forceEmit = done == total
                            )
                            res
                        }
                    }
                }.awaitAll()
            }

            workerOutputs.filterNotNullTo(restoredResults)
            if (restoredResults.isNotEmpty()) {
                val restoredItems = restoredResults.map { it.item }
                restoredItems.chunked(500).forEach { chunk ->
                    vaultDao.deleteItems(chunk)
                }
            }
        } finally {
            syncEncryptedCatalogLocked(masterKey)
            if (restoredResults.isNotEmpty()) {
                val pathsArray = restoredResults.map { it.restoredAbsPath }.toTypedArray()
                val mimesArray = restoredResults.map { it.item.mimeType }.toTypedArray()
                try {
                    MediaScannerConnection.scanFile(context, pathsArray, mimesArray, null)
                } catch (_: Exception) {
                }
            }
            ThumbnailCacheManager.clearCache()
            VaultRepository.hideProgress()
        }

        val destinationFolders = restoredResults.mapTo(linkedSetOf()) { it.destFolderDisplay }
        val destLabel = if (destinationFolders.isEmpty()) {
            "/Pictures/Restored_Vault"
        } else {
            destinationFolders.joinToString(", ")
        }
        Pair(restoredResults.size, destLabel)
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
            FileOutputStream(metaFile).use { fos ->
                fos.write(encryptedBytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Throwable) {
                }
            }
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
     * `vault_catalog.enc`, `.meta` sidecar files, AND any unindexed/orphaned physical files on disk
     * inside `VaultRepository.vaultMutex` so all 70+ physical files are always visible.
     */
    suspend fun reconcileAndRecoverCatalog(masterKey: SecretKey, isDecoy: Boolean = false) = VaultRepository.withVaultLock {
        recoverOrphanedVaultFilesLocked(masterKey = masterKey, isDecoy = isDecoy, showProgress = false)
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
        resolver: ContentResolver,
        runMediaScanImmediately: Boolean = true
    ): String? {
        val mediaStoreUris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        var deletedFsPath: String? = null

        if (fsPath.isNotEmpty()) {
            try {
                val origFile = File(fsPath)
                if (origFile.exists() && origFile.isFile) {
                    if (origFile.delete()) {
                        deletedFsPath = fsPath
                    }
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
            deletedFsPath = fsPath
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

            if (runMediaScanImmediately) {
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(fsPath),
                        null,
                        null
                    )
                } catch (_: Exception) {
                }
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
                                deletedFsPath = candidatePath
                                if (runMediaScanImmediately) {
                                    MediaScannerConnection.scanFile(context, arrayOf(candidatePath), null, null)
                                }
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
        return deletedFsPath
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
        val result = decryptAndRestoreFileWorker(item, masterKey, null)
        if (result != null) {
            vaultDao.deleteItem(item)
            syncEncryptedCatalogLocked(masterKey)
            ThumbnailCacheManager.clearCache()
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(result.restoredAbsPath),
                    arrayOf(item.mimeType),
                    null
                )
            } catch (_: Exception) {
            }
        }
        VaultRepository.updateProgress(
            title = "Restoring to Device...",
            currentItem = 1,
            totalItems = 1,
            percentage = 100,
            subText = "1 / 1 files",
            forceEmit = true
        )
        VaultRepository.hideProgress()
        result?.destFolderDisplay
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

    private fun resolveUniqueRestoreTargetFile(
        desiredPath: String,
        fallbackName: String,
        defaultRestoredDir: File,
        reservedTargetPaths: MutableSet<String>?
    ): File {
        synchronized(folderCreationLock) {
            var candidate = if (desiredPath.isNotEmpty()) File(desiredPath) else File(defaultRestoredDir, fallbackName)
            var parentDir = candidate.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }
            if (parentDir == null || !parentDir.exists() || !parentDir.canWrite()) {
                if (!defaultRestoredDir.exists()) defaultRestoredDir.mkdirs()
                parentDir = defaultRestoredDir
                candidate = File(defaultRestoredDir, fallbackName)
            }

            if (reservedTargetPaths != null) {
                var finalCandidate = candidate
                var counter = 1
                val baseName = finalCandidate.nameWithoutExtension
                val ext = finalCandidate.extension
                while (!reservedTargetPaths.add(finalCandidate.absolutePath)) {
                    val numberedName = if (ext.isNotEmpty()) "${baseName}_$counter.$ext" else "${baseName}_$counter"
                    finalCandidate = File(parentDir, numberedName)
                    counter++
                }
                return finalCandidate
            }
            return candidate
        }
    }

    private fun decryptAndRestoreFileWorker(
        item: VaultItem,
        masterKey: SecretKey,
        reservedTargetPaths: MutableSet<String>?
    ): UnhideWorkerResult? {
        val blobFile = File(vaultBlobsDir, item.encryptedRelativePath)
        if (!blobFile.exists() || blobFile.length() <= 0L) return null

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val defaultRestoredDir = File(picturesDir, "Restored_Vault")

        val targetFile = resolveUniqueRestoreTargetFile(
            desiredPath = item.originalPath,
            fallbackName = item.fileName,
            defaultRestoredDir = defaultRestoredDir,
            reservedTargetPaths = reservedTargetPaths
        )

        return try {
            // Inspect first 64 bytes to choose the best IV (stored baseIvHex vs deterministicIv vs raw unencrypted)
            val headerSample = ByteArray(64)
            var headerLen = 0
            try {
                FileInputStream(blobFile).use { fis ->
                    headerLen = fis.read(headerSample)
                }
            } catch (_: Throwable) {
            }
            val actualHeader = if (headerLen > 0) headerSample.copyOfRange(0, headerLen) else ByteArray(0)

            val rawSig = CryptoEngine.detectMediaSignature(actualHeader)
            if (rawSig != null) {
                // Physical file is already raw unencrypted media; copy directly to targetFile
                BufferedInputStream(FileInputStream(blobFile), IO_CHUNK_BUFFER_BYTES).use { bis ->
                    FileOutputStream(targetFile).use { fos ->
                        BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bos ->
                            val buf = ByteArray(IO_CHUNK_BUFFER_BYTES)
                            var r: Int
                            while (bis.read(buf).also { r = it } != -1) {
                                bos.write(buf, 0, r)
                            }
                            bos.flush()
                        }
                    }
                }
            } else {
                val storedIv = fromHex(item.baseIvHex).let { if (it.size == 16) it else CryptoEngine.deriveDeterministicIv(masterKey, item.encryptedRelativePath) }
                val detIv = CryptoEngine.deriveDeterministicIv(masterKey, item.encryptedRelativePath)
                val zeroIv = ByteArray(16)

                val effectiveIv = when {
                    CryptoEngine.detectMediaSignature(CryptoEngine.decryptHeaderBytesCtr(actualHeader, masterKey, storedIv)) != null -> storedIv
                    CryptoEngine.detectMediaSignature(CryptoEngine.decryptHeaderBytesCtr(actualHeader, masterKey, detIv)) != null -> detIv
                    CryptoEngine.detectMediaSignature(CryptoEngine.decryptHeaderBytesCtr(actualHeader, masterKey, zeroIv)) != null -> zeroIv
                    else -> storedIv
                }

                BufferedInputStream(FileInputStream(blobFile), IO_CHUNK_BUFFER_BYTES).use { bufferedIn ->
                    FileOutputStream(targetFile).use { fos ->
                        BufferedOutputStream(fos, IO_CHUNK_BUFFER_BYTES).use { bufferedOut ->
                            CryptoEngine.decryptStreamCtr(bufferedIn, bufferedOut, masterKey, effectiveIv)
                            bufferedOut.flush()
                        }
                    }
                }
            }

            if (!targetFile.exists() || targetFile.length() <= 0L) {
                targetFile.delete()
                return null
            }

            blobFile.delete()
            File(vaultBlobsDir, "${item.encryptedRelativePath}.meta").delete()
            File(vaultThumbsDir, "${item.encryptedRelativePath}.thumb").delete()

            UnhideWorkerResult(
                item = item,
                restoredAbsPath = targetFile.absolutePath,
                destFolderDisplay = formatDestinationFolderDisplay(targetFile.parentFile)
            )
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
     * Requirement 3: Physical-Scan Offline Backup (.lvb) Export:
     * 1. Scans physical vault folders (`recoverOrphanedVaultFilesLocked` + `vaultBlobsDir.listFiles()`)
     *    so even if a file was missing from the JSON/Room index, it is recovered, included in
     *    `vault_manifest.json`, and exported into the `.lvb` archive without losing a single file.
     * 2. Uses buffered `ZipOutputStream` (`Deflater.NO_COMPRESSION`) and `fos.fd.sync()`.
     */
    suspend fun exportEncryptedBackup(
        destUri: Uri,
        headerFile: File,
        masterKey: SecretKey? = null
    ): Result<Int> = VaultRepository.withVaultLock {
        try {
            // Recover any physical orphaned files on disk before building the backup manifest
            recoverOrphanedVaultFilesLocked(masterKey = masterKey, isDecoy = false, showProgress = false)
            val validItemsList = reconcilePhysicalFilesLocked(masterKey = masterKey)
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
                }
            }

            // Also scan vaultBlobsDir directly so any physical .bin file on disk is guaranteed to be in itemsJson
            var fallbackSeq = uniqueItemsByPath.size + 1
            vaultBlobsDir.listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.length() > 0L &&
                    !file.name.endsWith(".meta") &&
                    !file.name.endsWith(".thumb") &&
                    !file.name.startsWith(".tmp_") &&
                    !uniqueItemsByPath.containsKey(file.name)
                ) {
                    val reconstructed = reconstructVaultItemFromPhysicalFile(
                        blobFile = file,
                        subfolderId = null,
                        sequenceIndex = fallbackSeq++,
                        masterKey = masterKey,
                        isDecoy = false
                    )
                    uniqueItemsByPath[file.name] = reconstructed
                }
            }

            uniqueItemsByPath.values.forEach { item ->
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

                // Also recover any extracted physical .bin files that were not listed in vault_manifest.json
                val report = recoverOrphanedVaultFilesLocked(masterKey = null, isDecoy = false, showProgress = false)
                val finalCount = maxOf(restoredItemsCount, report.totalVaultFilesOnDisk)

                VaultRepository.updateProgress(
                    title = "Restoring Backup...",
                    currentItem = finalCount,
                    totalItems = finalCount,
                    percentage = 100,
                    subText = "$finalCount / $finalCount files",
                    forceEmit = true
                )
                VaultRepository.hideProgress()
                Result.success(finalCount)
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
