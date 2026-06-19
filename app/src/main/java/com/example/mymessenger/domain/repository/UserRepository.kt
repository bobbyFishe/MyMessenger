package com.example.mymessenger.domain.repository

import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    suspend fun getCurrentUser(uid: String): Result<User>
    suspend fun saveCurrentUser(user: User): Result<Unit>
    suspend fun searchUserByName(lowercaseName: String): Result<User>
    suspend fun createEncryptedChat(peerId: String, peerPublicKey: String? = null): Result<Unit>
    fun observeUserChats(currentUid: String): Flow<List<ChatDocument>>
    suspend fun completeCryptoHandshake(chatDoc: ChatDocument): Result<Unit>
    suspend fun setUserOnlineStatus(uid: String, isOnline: Boolean): Result<Unit>
    fun observeUserChatsWithCache(currentUid: String): Flow<List<ChatDocument>>
    suspend fun refreshChatsCache(currentUid: String)
}