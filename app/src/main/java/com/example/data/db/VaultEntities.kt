package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Architectural De-duplication Fix (Point 1):
 * Enforces a strict SQLite UNIQUE index on `encryptedRelativePath` (`vaultFilePath`) so multiple
 * metadata entries can never point to the same physical encrypted file inside `.LocalVaultData/blobs/`.
 */
@Entity(
    tableName = "vault_items",
    indices = [
        Index(value = ["encryptedRelativePath"], unique = true)
    ]
)
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val originalPath: String,
    val encryptedRelativePath: String,
    val mimeType: String,
    val fileSize: Long,
    val dateAdded: Long = System.currentTimeMillis(),
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val isDecoy: Boolean = false,
    val durationMs: Long = 0L,
    val baseIvHex: String
) {
    val vaultFilePath: String
        get() = encryptedRelativePath
}

@Entity(tableName = "folder_items")
data class FolderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderName: String,
    val isLocked: Boolean = false,
    val lockType: String = "MAIN_PIN", // "MAIN_PIN" or "CUSTOM_PIN"
    val customPinSaltHex: String? = null,
    val customPinSlotAHex: String? = null,
    val isDecoy: Boolean = false
) {
    /**
     * Point 8: Returns true when this folder has its own custom Folder PIN configured.
     */
    fun hasCustomPin(): Boolean {
        return isLocked &&
            lockType == "CUSTOM_PIN" &&
            !customPinSaltHex.isNullOrEmpty() &&
            !customPinSlotAHex.isNullOrEmpty()
    }

    val pinHash: String?
        get() = customPinSlotAHex
}
