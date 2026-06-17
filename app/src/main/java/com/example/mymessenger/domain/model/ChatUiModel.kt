package com.example.mymessenger.domain.model

data class ChatUiModel(
    val id: String,
    val peerName: String,
    val lastMessage: String = "",
    val isHandshakeComplete: Boolean
)