package com.example.mymessenger.data.repository

import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.data.utils.CryptoManager
import com.example.mymessenger.domain.model.MessageDocument
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ChatRepositoryImpl(
    private val firestore: FirebaseFirestore,
    private val messageDao: MessageDao,
    private val chatKeyDao: ChatKeyDao,
    private val userRepository: UserRepository
) : ChatRepository {

    private val activeListeners = mutableSetOf<String>()

    override suspend fun sendMessage(chatId: String, text: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val myId = FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext Result.failure(Exception("NO_SESSION"))

                val uids = chatId.split("_")
                val isSelfChat = uids.size >= 2 && uids[0] == uids[1] && uids[0] == myId

                val messageId = firestore.collection("chats")
                    .document(chatId)
                    .collection("messages")
                    .document()
                    .id

                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = myId,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isMine = true
                    )
                )
                if (isSelfChat) {
                    return@withContext Result.success(Unit)
                }
                sendMessageToFirestore(chatId, messageId, text)

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ sendMessage error", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun sendMessageToFirestore(chatId: String, messageId: String, text: String) {
        try {
            val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val peerPublicKey = userRepository.getCachedPeerPublicKey(chatId, myId) ?: return
            val encryptedText = CryptoManager.encrypt(text, peerPublicKey)

            val msgDoc = MessageDocument(
                id = messageId,
                chatId = chatId,
                senderId = myId,
                encryptedText = encryptedText,
                timestamp = System.currentTimeMillis()
            )

            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .set(msgDoc)
                .await()
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ sendMessageToFirestore error", e)
        }
    }


    override fun startP2PDeliveryEngine(chatId: String): Flow<Unit> = callbackFlow {
        if (activeListeners.contains(chatId)) {
            close()
            return@callbackFlow
        }

        val myId = FirebaseAuth.getInstance().currentUser?.uid
        if (myId == null) {
            close()
            return@callbackFlow
        }

        val uids = chatId.split("_")
        if (uids.size < 2 || (uids[0] == uids[1] && uids[0] == myId)) {
            close()
            return@callbackFlow
        }

        activeListeners.add(chatId)
        val peerId = uids.firstOrNull { it != myId }
        if (peerId == null) {
            activeListeners.remove(chatId)
            close()
            return@callbackFlow
        }
        val listener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                snapshot.documentChanges.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        launch(Dispatchers.IO) {
                            processIncomingMessage(change.document, chatId, myId)
                        }
                    }
                }
            }

        awaitClose {
            activeListeners.remove(chatId)
            listener.remove()
        }
    }

    private suspend fun processIncomingMessage(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        chatId: String,
        myId: String,
    ) {
        try {
            val remoteMsg = doc.toObject(MessageDocument::class.java) ?: return
            if (remoteMsg.senderId == myId) return

            val keyEntity = chatKeyDao.getKeyForChat(chatId) ?: return
            val decryptedText = CryptoManager.decrypt(remoteMsg.encryptedText, keyEntity.privateKey)

            messageDao.insertMessage(
                LocalMessageEntity(
                    id = remoteMsg.id,
                    chatId = chatId,
                    senderId = remoteMsg.senderId,
                    text = decryptedText,
                    timestamp = System.currentTimeMillis(),
                    isMine = false
                )
            )

            try {
                doc.reference.delete().await()
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Failed to delete from Firestore", e)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ processIncomingMessage error", e)
        }
    }


    override suspend fun getMessagesSync(chatId: String): List<LocalMessageEntity> =
        withContext(Dispatchers.IO) { messageDao.getLastMessagesSync(chatId, 1000) }

    override suspend fun getLastMessagesSync(chatId: String, limit: Int): List<LocalMessageEntity> =
        withContext(Dispatchers.IO) { messageDao.getLastMessagesSync(chatId, limit) }

    override fun getLocalMessages(chatId: String): Flow<List<LocalMessageEntity>> =
        messageDao.getMessagesForChat(chatId)

    override fun getLastLocalMessage(chatId: String): Flow<LocalMessageEntity?> =
        messageDao.getLastMessageForChat(chatId)

    override suspend fun forceSync(chatId: String): Result<Unit> = Result.success(Unit)

    override suspend fun flushUnsentMessages(chatId: String): Result<Unit> = Result.success(Unit)

    override fun startMessagesTransit(chatId: String): Flow<Unit> = callbackFlow { close() }

    override suspend fun markMessageAsRead(messageId: String): Result<Unit> = Result.success(Unit)

}
