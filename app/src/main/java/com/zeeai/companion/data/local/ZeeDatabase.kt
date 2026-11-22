package com.zeeai.companion.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val owner: String,       // "zee", "zhee", or "shared"
    val category: String,    // preference, creative, project, study, important
    val content: String,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val characterId: String, // "zee" or "zhee"
    val role: String,        // "user" or "assistant"
    val text: String,
    val source: String,      // "gemini-online", "local-llm", "offline-creative", "phone-action"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "offline_drafts")
data class OfflineDraftEntity(
    @PrimaryKey val id: String,
    val platform: String,    // "youtube", "instagram", "whatsapp"
    val title: String,
    val payloadJson: String,
    val status: String = "queued",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ZeeDao {
    @Query("SELECT * FROM memories WHERE enabled = 1 AND (owner = :charId OR (:includeShared = 1 AND owner = 'shared')) ORDER BY updatedAt DESC LIMIT 25")
    suspend fun getActiveMemories(charId: String, includeShared: Boolean): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(item: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: String)

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun observeMessages(): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: ChatMessageEntity)

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("SELECT * FROM offline_drafts ORDER BY createdAt DESC")
    fun observeDrafts(): Flow<List<OfflineDraftEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(draft: OfflineDraftEntity)

    @Query("DELETE FROM offline_drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)
}

@Database(
    entities = [MemoryEntity::class, ChatMessageEntity::class, OfflineDraftEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ZeeDatabase : RoomDatabase() {
    abstract fun dao(): ZeeDao

    companion object {
        @Volatile private var instance: ZeeDatabase? = null

        fun get(context: Context): ZeeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ZeeDatabase::class.java,
                    "zee_ai_native.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
