package com.example.mymessenger.data.repository

import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.data.utils.CryptoManager
import com.example.mymessenger.domain.model.MessageDocument
import com.example.mymessenger.domain.repository.ChatRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
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
    private val chatKeyDao: ChatKeyDao
) : ChatRepository {
    override suspend fun sendMessage(chatId: String, text: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.failure(Exception("NO_SESSION"))
                val uids = chatId.split("_")
                val isSelfChat = uids.size >= 2 && uids[0] == uids[1]

                val messageId = firestore.collection("chats").document(chatId).collection("messages").document().id

                // Сохраняем сообщение строго в свой Room!
                // Если это чат с собой — ставим isSent = true, если с другом — false
                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = myId,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isSent = false
                    )
                )
                if (!isSelfChat) {
                    launch(Dispatchers.IO) {
                        android.util.Log.d("ChatRepository", "⚡ Immediate flush after send")
                        flushUnsentMessages(chatId)
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override fun startP2PDeliveryEngine(chatId: String): Flow<Unit> = callbackFlow {
        android.util.Log.d("ChatRepository", "🚀 startP2PDeliveryEngine: $chatId")

        val myId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            android.util.Log.e("ChatRepository", "❌ No user")
            close()
            return@callbackFlow
        }

        val uids = chatId.split("_")
        if (uids.size < 2 || uids[0] == uids[1]) {
            android.util.Log.d("ChatRepository", "⚠️ Self chat, skip")
            close()
            return@callbackFlow
        }

        val peerId = uids.firstOrNull { it != myId } ?: run {
            android.util.Log.e("ChatRepository", "❌ No peer")
            close()
            return@callbackFlow
        }

        android.util.Log.d("ChatRepository", "✅ Peer: $peerId")

        // ✅ 1. Сразу отправляем все неотправленные при запуске
        launch(Dispatchers.IO) {
            android.util.Log.d("ChatRepository", "⚡ Initial flush")
            flushUnsentMessages(chatId)
        }

        // Канал 1: Отправка неотправленных
        val unsentJob = launch(Dispatchers.IO) {
            android.util.Log.d("ChatRepository", "📡 Subscribing to unsent")

            messageDao.getUnsentMessagesFlow(chatId).collect { unsentList ->
                android.util.Log.d("ChatRepository", "📨 Unsent: ${unsentList.size}")

                if (unsentList.isNotEmpty()) {
                    try {
                        val peerSnapshot = firestore.collection("users")
                            .document(peerId)
                            .get()
                            .await()
                        val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

                        android.util.Log.d("ChatRepository", "🌐 Peer online: $isPeerOnline")

                        if (isPeerOnline) {
                            val chatSnapshot = firestore.collection("chats")
                                .document(chatId)
                                .get()
                                .await()

                            if (!chatSnapshot.exists()) {
                                android.util.Log.e("ChatRepository", "❌ Chat doc missing")
                                return@collect
                            }

                            val isUserA = myId == uids[0]
                            val peerPublicKey = if (isUserA) {
                                chatSnapshot.getString("publicKeyUserB") ?: ""
                            } else {
                                chatSnapshot.getString("publicKeyUserA") ?: ""
                            }

                            android.util.Log.d("ChatRepository", "🔑 Peer key exists: ${peerPublicKey.isNotEmpty()}")

                            if (peerPublicKey.isNotEmpty()) {
                                unsentList.forEach { localMsg ->
                                    try {
                                        android.util.Log.d("ChatRepository", "📤 Sending: ${localMsg.id}")

                                        val encryptedText = CryptoManager.encrypt(
                                            localMsg.text,
                                            peerPublicKey
                                        )

                                        val msgDoc = MessageDocument(
                                            id = localMsg.id,
                                            chatId = chatId,
                                            senderId = myId,
                                            encryptedText = encryptedText,
                                            timestamp = localMsg.timestamp
                                        )

                                        // ✅ Отправляем в Firestore
                                        firestore.collection("chats")
                                            .document(chatId)
                                            .collection("messages")
                                            .document(localMsg.id)
                                            .set(msgDoc)
                                            .await()

                                        // ✅ Помечаем как отправленное
                                        messageDao.markAsSent(localMsg.id)
                                        android.util.Log.d("ChatRepository", "✅ Sent: ${localMsg.id}")
                                        trySend(Unit)
                                    } catch (e: Exception) {
                                        android.util.Log.e("ChatRepository", "❌ Send fail", e)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatRepository", "❌ Error in unsent flow", e)
                    }
                }
            }
        }

        // Канал 2: Входящие сообщения (от собеседника)
        val inboundListener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    android.util.Log.e("ChatRepository", "❌ Inbound error", error)
                    return@addSnapshotListener
                }

                android.util.Log.d("ChatRepository", "📥 Inbound changes: ${snapshot.documentChanges.size}")

                snapshot.documentChanges.forEach { change ->
                    when (change.type) {
                        DocumentChange.Type.ADDED -> {
                            val doc = change.document
                            android.util.Log.d("ChatRepository", "📥 New message: ${doc.id}")

                            launch(Dispatchers.IO) {
                                try {
                                    val keyEntity = chatKeyDao.getKeyForChat(chatId)
                                    if (keyEntity == null) {
                                        android.util.Log.e("ChatRepository", "❌ No key for: $chatId")
                                        return@launch
                                    }

                                    val remoteMsg = doc.toObject(MessageDocument::class.java)
                                    if (remoteMsg == null) {
                                        android.util.Log.e("ChatRepository", "❌ Parse fail")
                                        return@launch
                                    }

                                    // ✅ Только если сообщение от собеседника
                                    if (remoteMsg.senderId != myId) {
                                        android.util.Log.d("ChatRepository", "🔓 Decrypting from: ${remoteMsg.senderId}")

                                        val decryptedText = CryptoManager.decrypt(
                                            remoteMsg.encryptedText,
                                            keyEntity.privateKey
                                        )

                                        // ✅ Сохраняем в Room
                                        messageDao.insertMessage(
                                            LocalMessageEntity(
                                                id = remoteMsg.id,
                                                chatId = chatId,
                                                senderId = remoteMsg.senderId,
                                                text = decryptedText,
                                                timestamp = remoteMsg.timestamp,
                                                isSent = true
                                            )
                                        )

                                        // ✅ Удаляем из Firestore
                                        doc.reference.delete().await()
                                        android.util.Log.d("ChatRepository", "✅ Inbound saved and deleted")
                                        trySend(Unit)
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatRepository", "❌ Inbound process fail", e)
                                }
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            // Сообщение было удалено получателем
                            android.util.Log.d("ChatRepository", "🗑️ Message removed: ${change.document.id}")
                        }
                        else -> {}
                    }
                }
            }

        // Канал 3: Статус друга
        val statusListener = firestore.collection("users")
            .document(peerId)
            .addSnapshotListener { snapshot, _ ->
                val isOnline = snapshot?.getBoolean("isOnline") ?: false
                android.util.Log.d("ChatRepository", "👤 Peer online changed: $isOnline")

                if (isOnline) {
                    launch(Dispatchers.IO) {
                        android.util.Log.d("ChatRepository", "⚡ Peer came online, flushing")
                        flushUnsentMessages(chatId)
                    }
                }
            }

        awaitClose {
            android.util.Log.d("ChatRepository", "🛑 Closing engine: $chatId")
            unsentJob.cancel()
            inboundListener.remove()
            statusListener.remove()
        }
    }



    // В ChatRepository
    override suspend fun forceSync(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChatRepository", "🔄 Force sync: $chatId")
                flushUnsentMessages(chatId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Метод принудительной отправки накопившихся оффлайн-сообщений
    override suspend fun flushUnsentMessages(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.failure(Exception("NO_SESSION"))
                val uids = chatId.split("_")
                val peerId = uids.firstOrNull { it != myId } ?: return@withContext Result.success(Unit)

                // Проверяем, в сети ли друг
                val peerSnapshot = firestore.collection("users").document(peerId).get().await()
                val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

                if (isPeerOnline) {
                    val unsent = messageDao.getUnsentMessages(chatId)
                    if (unsent.isNotEmpty()) {
                        val chatSnapshot = firestore.collection("chats").document(chatId).get().await()
                        val isUserA = myId == uids[0]
                        val peerPublicKey = if (!isUserA) chatSnapshot.getString("publicKeyUserA") ?: "" else chatSnapshot.getString("publicKeyUserB") ?: ""

                        if (peerPublicKey.isNotEmpty()) {
                            unsent.forEach { localMsg ->
                                val encryptedText = CryptoManager.encrypt(localMsg.text, peerPublicKey)
                                val msgDoc = MessageDocument(
                                    id = localMsg.id, chatId = chatId, senderId = myId, encryptedText = encryptedText, timestamp = localMsg.timestamp
                                )
                                firestore.collection("chats").document(chatId).collection("messages").document(localMsg.id).set(msgDoc).await()
                                messageDao.markAsSent(localMsg.id)
                            }
                        }
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }



    override fun startMessagesTransit(chatId: String): Flow<Unit> = callbackFlow {
        val myId = FirebaseAuth.getInstance().currentUser?.uid
        if (myId == null) {
            close()
            return@callbackFlow
        }

        val listener = firestore.collection("chats").document(chatId).collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    return@addSnapshotListener
                }

                launch(Dispatchers.IO) {
                    val privateKey = chatKeyDao.getKeyForChat(chatId)?.privateKey ?: return@launch

                    snapshot.documents.forEach { doc ->
                        val remoteMsg = doc.toObject(MessageDocument::class.java) ?: return@forEach

                        if (remoteMsg.senderId != myId) {
                            val decryptedText = CryptoManager.decrypt(remoteMsg.encryptedText, privateKey)
                            messageDao.insertMessage(
                                LocalMessageEntity(
                                    id = remoteMsg.id,
                                    chatId = remoteMsg.chatId,
                                    senderId = remoteMsg.senderId,
                                    text = decryptedText,
                                    timestamp = remoteMsg.timestamp
                                )
                            )
                            firestore.collection("chats").document(chatId)
                                .collection("messages").document(remoteMsg.id)
                                .delete()
                        }
                    }
                    trySend(Unit)
                }
            }
        awaitClose { listener.remove() }
    }


    override fun getLocalMessages(chatId: String): Flow<List<LocalMessageEntity>> {
        return messageDao.getMessagesForChat(chatId)
    }

    override fun getLastLocalMessage(chatId: String): Flow<LocalMessageEntity?> {
        return messageDao.getLastMessageForChat(chatId)
    }

}