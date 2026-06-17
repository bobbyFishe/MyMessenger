package com.example.mymessenger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mymessenger.data.local.entities.ChatKeyEntity

@Dao
interface ChatKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveKey(chatKey: ChatKeyEntity): Long

    @Query("SELECT * FROM chat_keys WHERE chatId = :chatId LIMIT 1")
    suspend fun getKeyForChat(chatId: String): ChatKeyEntity?
}