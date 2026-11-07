package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items WHERE isDecoy = :isDecoy ORDER BY dateAdded DESC")
    fun getAllItems(isDecoy: Boolean): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE isFavorite = 1 AND isDecoy = :isDecoy ORDER BY dateAdded DESC")
    fun getFavorites(isDecoy: Boolean): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE folderId = :folderId AND isDecoy = :isDecoy ORDER BY dateAdded DESC")
    fun getItemsInFolder(folderId: Long, isDecoy: Boolean): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): VaultItem?

    @Query("SELECT * FROM vault_items WHERE fileName LIKE '%' || :query || '%' AND isDecoy = :isDecoy ORDER BY dateAdded DESC")
    fun searchItems(query: String, isDecoy: Boolean): Flow<List<VaultItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: VaultItem): Long

    @Update
    suspend fun updateItem(item: VaultItem)

    @Delete
    suspend fun deleteItem(item: VaultItem)

    @Query("DELETE FROM vault_items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: List<Long>)

    @Query("SELECT SUM(fileSize) FROM vault_items WHERE isDecoy = :isDecoy")
    suspend fun getTotalVaultSize(isDecoy: Boolean): Long?
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder_items WHERE isDecoy = :isDecoy ORDER BY folderName ASC")
    fun getAllFolders(isDecoy: Boolean): Flow<List<FolderItem>>

    @Query("SELECT * FROM folder_items WHERE id = :id LIMIT 1")
    suspend fun getFolderById(id: Long): FolderItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderItem): Long

    @Update
    suspend fun updateFolder(folder: FolderItem)

    @Delete
    suspend fun deleteFolder(folder: FolderItem)
}
