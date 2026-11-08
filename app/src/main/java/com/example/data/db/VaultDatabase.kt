package com.example.data.db

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Architectural Persistence Fix:
 * Stores `local_vault.db` directly inside the persistent external `/storage/emulated/0/.LocalVaultData/`
 * directory whenever All Files Access (`MANAGE_EXTERNAL_STORAGE`) is granted.
 *
 * Why:
 * Internal app storage (`/data/data/.../databases/local_vault.db`) is wiped by the Android OS upon
 * app uninstallation, which would orphan `.LocalVaultData/blobs/<uuid>.bin` by losing their `baseIvHex`,
 * original filenames, and folder mappings. Placing the database inside `.LocalVaultData/local_vault.db`
 * (using `JournalMode.TRUNCATE` for single-file FUSE reliability) ensures 100% survival across
 * app uninstalls and reinstalls.
 */
@Database(
    entities = [VaultItem::class, FolderItem::class],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun folderDao(): FolderDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        @Volatile
        private var currentDbPath: String? = null

        private fun resolveDatabasePath(context: Context): String {
            val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            if (hasAllFilesAccess) {
                try {
                    val extDir = Environment.getExternalStorageDirectory()
                    val vaultDir = File(extDir, ".LocalVaultData")
                    if (!vaultDir.exists()) {
                        vaultDir.mkdirs()
                    }
                    if (vaultDir.exists() && vaultDir.canWrite()) {
                        return File(vaultDir, "local_vault.db").absolutePath
                    }
                } catch (_: Exception) {
                }
            }

            // Fallback to internal database name only until All Files Access is granted
            return "local_vault.db"
        }

        fun getInstance(context: Context): VaultDatabase {
            val targetPath = resolveDatabasePath(context)
            val existing = INSTANCE
            if (existing != null && currentDbPath == targetPath) {
                return existing
            }

            return synchronized(this) {
                val recheckTarget = resolveDatabasePath(context)
                val curr = INSTANCE
                if (curr != null && currentDbPath == recheckTarget) {
                    return@synchronized curr
                }

                // If transitioning from internal fallback to persistent external DB after permission grant,
                // close the temporary fallback instance cleanly.
                try {
                    curr?.close()
                } catch (_: Exception) {
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    recheckTarget
                )
                    .setJournalMode(JournalMode.TRUNCATE)
                    .fallbackToDestructiveMigration(true)
                    .build()

                INSTANCE = instance
                currentDbPath = recheckTarget
                instance
            }
        }
    }
}
