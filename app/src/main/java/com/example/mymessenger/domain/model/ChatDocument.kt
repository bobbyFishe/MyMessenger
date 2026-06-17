package com.example.mymessenger.domain.model

data class ChatDocument(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val publicKeyUserA: String = "",
    val publicKeyUserB: String = "",
    val createdAt: Long = 0L
)