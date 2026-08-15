package com.grokadile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.grokadile.data.local.entity.VectorMemoryEntity

@Dao
interface VectorMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: VectorMemoryEntity)

    @Query("SELECT * FROM vector_memory WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): VectorMemoryEntity?

    @Query("SELECT * FROM vector_memory ORDER BY createdAt DESC")
    suspend fun getAll(): List<VectorMemoryEntity>

    @Query("DELETE FROM vector_memory WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM vector_memory")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM vector_memory")
    suspend fun count(): Int
}
