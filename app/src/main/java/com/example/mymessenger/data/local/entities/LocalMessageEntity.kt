package com.example.mymessenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class LocalMessageEntity(
    @PrimaryKey
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val timestamp: Long,
    val isSent: Boolean = false,
    val isDelivered: Boolean = false,
    val isRead: Boolean = false
)
