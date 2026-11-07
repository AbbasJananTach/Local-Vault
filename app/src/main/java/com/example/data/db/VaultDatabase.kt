package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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

        fun getInstance(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "local_vault.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
