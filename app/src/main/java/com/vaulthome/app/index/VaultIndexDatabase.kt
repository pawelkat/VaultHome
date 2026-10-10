package com.vaulthome.app.index

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val path: String,
    val mtime: Long,
)

@Entity(
    tableName = "chunks",
    indices = [Index("path")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val heading: String,
    val chunkText: String,
    val vector: ByteArray,
    val sourceMtime: Long,
)

@Dao
interface ChunkDao {
    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE path = :path")
    suspend fun deleteNote(path: String)

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM chunks WHERE path = :path")
    suspend fun deleteChunks(path: String)

    @Insert
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks")
    suspend fun allChunks(): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE path = :path ORDER BY id")
    suspend fun chunksFor(path: String): List<ChunkEntity>

    @Query("DELETE FROM chunks")
    suspend fun clearChunks()

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun chunkCount(): Int
}

@Database(
    entities = [NoteEntity::class, ChunkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VaultIndexDatabase : RoomDatabase() {
    abstract fun chunks(): ChunkDao

    companion object {
        fun create(context: Context): VaultIndexDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                VaultIndexDatabase::class.java,
                "vaulthome-index.db",
            ).build()
        }
    }
}
