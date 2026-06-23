package com.example.mymessenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LocalMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<LocalMessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForChat(chatId: String): Flow<LocalMessageEntity?>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLastMessagesSync(chatId: String, limit: Int): List<LocalMessageEntity>

    @Query("DELETE FROM messages WHERE chatId = :chatId AND timestamp < :beforeTimestamp")
    suspend fun deleteOldMessages(chatId: String, beforeTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCount(chatId: String): Int
}