package com.example.mymessenger.domain.model

data class MessageDocument(
    val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val encryptedText: String = "",
    val timestamp: Long = 0L,
    val isDelivered: Boolean = false,
    val isRead: Boolean = false
)
