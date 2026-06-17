package com.example.mymessenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: LocalMessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<LocalMessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForChat(chatId: String): Flow<LocalMessageEntity?>

    @Query("UPDATE messages SET isSent = 1 WHERE id = :messageId")
    suspend fun markAsSent(messageId: String): Int

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isSent = 0 ORDER BY timestamp ASC")
    suspend fun getUnsentMessages(chatId: String): List<LocalMessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND isSent = 0 ORDER BY timestamp ASC")
    fun getUnsentMessagesFlow(chatId: String): Flow<List<LocalMessageEntity>>
}