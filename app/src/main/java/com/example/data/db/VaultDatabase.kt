package com.example.data.db

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Architectural Persistence & De-duplication Fix:
 * 1. Stores `local_vault.db` directly inside `/storage/emulated/0/.LocalVaultData/` whenever
 *    All Files Access (`MANAGE_EXTERNAL_STORAGE`) is granted.
 * 2. Includes non-destructive `MIGRATION_1_2` that cleans up any pre-existing duplicate rows sharing
 *    the same `encryptedRelativePath` before creating the unique index `index_vault_items_encryptedRelativePath`.
 */
@Database(
    entities = [VaultItem::class, FolderItem::class],
    version = 2,
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove any duplicate metadata rows pointing to the same physical encrypted file
                db.execSQL(
                    """
                    DELETE FROM vault_items
                    WHERE id NOT IN (
                        SELECT MIN(id)
                        FROM vault_items
                        GROUP BY encryptedRelativePath
                    )
                    """.trimIndent()
                )
                // Create the unique index on encryptedRelativePath
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_vault_items_encryptedRelativePath` ON `vault_items` (`encryptedRelativePath`)"
                )
            }
        }

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

                try {
                    curr?.close()
                } catch (_: Exception) {
                }

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    recheckTarget
                )
                    .addMigrations(MIGRATION_1_2)
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
