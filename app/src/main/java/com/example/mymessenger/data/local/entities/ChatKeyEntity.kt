package com.example.mymessenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "chat_keys")
data class ChatKeyEntity(
    @PrimaryKey
    val chatId: String,
    val privateKey: String
)
