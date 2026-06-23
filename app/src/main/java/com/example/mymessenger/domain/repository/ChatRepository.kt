package com.example.mymessenger.domain.repository

import com.example.mymessenger.data.local.entities.LocalMessageEntity
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun sendMessage(chatId: String, text: String): Result<Unit>
    fun startP2PDeliveryEngine(chatId: String): Flow<Unit>
    fun getLocalMessages(chatId: String): Flow<List<LocalMessageEntity>>
    fun getLastLocalMessage(chatId: String): Flow<LocalMessageEntity?>
    suspend fun getMessagesSync(chatId: String): List<LocalMessageEntity>
    suspend fun getLastMessagesSync(chatId: String, limit: Int): List<LocalMessageEntity>
    suspend fun forceSync(chatId: String): Result<Unit>
    suspend fun flushUnsentMessages(chatId: String): Result<Unit>
    fun startMessagesTransit(chatId: String): Flow<Unit>
    suspend fun markMessageAsRead(messageId: String): Result<Unit>
}