package com.example.mymessenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mymessenger.data.local.entities.ChatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChat(chat: ChatEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChats(chats: List<ChatEntity>): List<Long>

    @Query("""
    SELECT chats.* FROM chats 
    LEFT JOIN (
        SELECT chatId, MAX(timestamp) as max_time 
        FROM messages 
        GROUP BY chatId
    ) msg ON chats.id = msg.chatId
    ORDER BY COALESCE(msg.max_time, chats.createdAt) DESC
""")
    fun getChats(): Flow<List<ChatEntity>>


    @Query("SELECT * FROM chats WHERE id = :chatId LIMIT 1")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("DELETE FROM chats")
    suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun getCount(): Int
}