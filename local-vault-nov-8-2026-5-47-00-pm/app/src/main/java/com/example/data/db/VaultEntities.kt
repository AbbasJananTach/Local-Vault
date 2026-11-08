package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vault_items")
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
)

@Entity(tableName = "folder_items")
data class FolderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderName: String,
    val isLocked: Boolean = false,
    val lockType: String = "MAIN_PIN", // "MAIN_PIN" or "CUSTOM_PIN"
    val customPinSaltHex: String? = null,
    val customPinSlotAHex: String? = null,
    val isDecoy: Boolean = false
)
