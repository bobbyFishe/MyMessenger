package com.example.mymessenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey
    val id: String,
    val participantIds: List<String>,
    val publicKeyUserA: String = "",
    val publicKeyUserB: String = "",
    val createdAt: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
)
