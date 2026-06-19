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

                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = myId,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isSent = false,
                        isDelivered = false,
                        isRead = false
                    )
                )
                if (!isSelfChat) {
                    launch(Dispatchers.IO) {
                        android.util.Log.d("ChatRepository", "⚡ Immediate flush after send")
                        flushUnsentMessages(chatId)
                    }
                } else {
                    messageDao.markAsSent(messageId)
                    messageDao.markAsDelivered(messageId)
                    messageDao.markAsRead(messageId)
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

        android.util.Log.d("ChatRepository", "✅ My ID: $myId, Peer: $peerId")

        // ⚡ Сразу отправляем все неотправленные при запуске
        launch(Dispatchers.IO) {
            android.util.Log.d("ChatRepository", "⚡ Initial flush")
            flushUnsentMessages(chatId)
        }

        // ============================================================
        // 1️⃣ КАНАЛ: Отправка неотправленных сообщений (из Room в Firestore)
        // ============================================================
        val unsentJob = launch(Dispatchers.IO) {
            android.util.Log.d("ChatRepository", "📡 Subscribing to unsent messages")

            messageDao.getUnsentMessagesFlow(chatId).collect { unsentList ->
                android.util.Log.d("ChatRepository", "📨 Unsent messages: ${unsentList.size}")

                if (unsentList.isNotEmpty()) {
                    try {
                        // Проверяем онлайн статус собеседника
                        val peerSnapshot = firestore.collection("users")
                            .document(peerId)
                            .get()
                            .await()
                        val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

                        android.util.Log.d("ChatRepository", "🌐 Peer online: $isPeerOnline")

                        if (isPeerOnline) {
                            // Получаем документ чата с публичными ключами
                            val chatSnapshot = firestore.collection("chats")
                                .document(chatId)
                                .get()
                                .await()

                            if (!chatSnapshot.exists()) {
                                android.util.Log.e("ChatRepository", "❌ Chat document missing: $chatId")
                                return@collect
                            }

                            val isUserA = myId == uids[0]
                            val peerPublicKey = if (isUserA) {
                                chatSnapshot.getString("publicKeyUserB") ?: ""
                            } else {
                                chatSnapshot.getString("publicKeyUserA") ?: ""
                            }

                            android.util.Log.d("ChatRepository", "🔑 Peer public key exists: ${peerPublicKey.isNotEmpty()}")

                            if (peerPublicKey.isNotEmpty()) {
                                unsentList.forEach { localMsg ->
                                    try {
                                        android.util.Log.d("ChatRepository", "📤 Sending message: ${localMsg.id}")

                                        // Шифруем сообщение
                                        val encryptedText = CryptoManager.encrypt(localMsg.text, peerPublicKey)

                                        val msgDoc = MessageDocument(
                                            id = localMsg.id,
                                            chatId = chatId,
                                            senderId = myId,
                                            encryptedText = encryptedText,
                                            timestamp = localMsg.timestamp,
                                            isDelivered = false,
                                            isRead = false
                                        )

                                        // Отправляем в Firestore
                                        firestore.collection("chats")
                                            .document(chatId)
                                            .collection("messages")
                                            .document(localMsg.id)
                                            .set(msgDoc)
                                            .await()

                                        // Помечаем как отправленное
                                        messageDao.markAsSent(localMsg.id)
                                        android.util.Log.d("ChatRepository", "✅ Message sent: ${localMsg.id}")
                                        trySend(Unit)
                                    } catch (e: Exception) {
                                        android.util.Log.e("ChatRepository", "❌ Failed to send message: ${localMsg.id}", e)
                                    }
                                }
                            } else {
                                android.util.Log.e("ChatRepository", "❌ Peer public key is empty!")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatRepository", "❌ Error in unsent flow", e)
                    }
                }
            }
        }

        // ============================================================
        // 2️⃣ КАНАЛ: Входящие сообщения (от собеседника)
        // ============================================================
        val inboundListener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    android.util.Log.e("ChatRepository", "❌ Inbound listener error", error)
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
                                    // Получаем приватный ключ для расшифровки
                                    val keyEntity = chatKeyDao.getKeyForChat(chatId)
                                    if (keyEntity == null) {
                                        android.util.Log.e("ChatRepository", "❌ No private key for: $chatId")
                                        return@launch
                                    }

                                    val remoteMsg = doc.toObject(MessageDocument::class.java)
                                    if (remoteMsg == null) {
                                        android.util.Log.e("ChatRepository", "❌ Failed to parse message")
                                        return@launch
                                    }

                                    // ✅ Только если сообщение от собеседника (не от себя)
                                    if (remoteMsg.senderId != myId) {
                                        android.util.Log.d("ChatRepository", "🔓 Decrypting message from: ${remoteMsg.senderId}")

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
                                                isSent = true,        // Это входящее, уже отправлено
                                                isDelivered = true,   // Мы его получили
                                                isRead = false        // Еще не прочитано
                                            )
                                        )
                                        android.util.Log.d("ChatRepository", "💾 Message saved to Room: ${remoteMsg.id}")

                                        // ✅ Отправляем подтверждение доставки в ОТДЕЛЬНУЮ коллекцию
                                        // (чтобы отправитель мог отследить статус)
                                        firestore.collection("chats")
                                            .document(chatId)
                                            .collection("message_statuses")
                                            .document(remoteMsg.id)
                                            .set(
                                                mapOf(
                                                    "messageId" to remoteMsg.id,
                                                    "isDelivered" to true,
                                                    "isRead" to false,
                                                    "timestamp" to System.currentTimeMillis()
                                                )
                                            )
                                            .await()
                                        android.util.Log.d("ChatRepository", "📤 Delivery confirmation sent")

                                        // ✅ Удаляем сообщение из транзитной коллекции
                                        doc.reference.delete().await()
                                        android.util.Log.d("ChatRepository", "🗑️ Message deleted from transit")

                                        trySend(Unit)
                                    } else {
                                        // Это сообщение отправил я сам (дубликат) - просто удаляем
                                        android.util.Log.d("ChatRepository", "⏭️ Skipping my own message: ${remoteMsg.id}")
                                        doc.reference.delete().await()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatRepository", "❌ Inbound process error", e)
                                }
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            android.util.Log.d("ChatRepository", "🗑️ Message removed: ${change.document.id}")
                        }
                        DocumentChange.Type.MODIFIED -> {
                            // Обрабатываем обновления статусов (для своих сообщений)
                            val doc = change.document
                            launch(Dispatchers.IO) {
                                try {
                                    val isDelivered = doc.getBoolean("isDelivered") ?: false
                                    val isRead = doc.getBoolean("isRead") ?: false

                                    val remoteMsg = doc.toObject(MessageDocument::class.java)
                                    if (remoteMsg?.senderId == myId) {
                                        if (isDelivered) {
                                            android.util.Log.d("ChatRepository", "✅ My message delivered: ${doc.id}")
                                            messageDao.markAsDelivered(doc.id)
                                        }
                                        if (isRead) {
                                            android.util.Log.d("ChatRepository", "✅ My message read: ${doc.id}")
                                            messageDao.markAsRead(doc.id)
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("ChatRepository", "❌ Status update error", e)
                                }
                            }
                        }
                    }
                }
            }

        // ============================================================
        // 3️⃣ КАНАЛ: Статусы доставки/прочтения (от собеседника)
        // ============================================================
        val deliveryStatusListener = firestore.collection("chats")
            .document(chatId)
            .collection("message_statuses")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    android.util.Log.e("ChatRepository", "❌ Delivery status error", error)
                    return@addSnapshotListener
                }

                android.util.Log.d("ChatRepository", "📊 Status changes: ${snapshot.documentChanges.size}")

                snapshot.documentChanges.forEach { change ->
                    if (change.type == DocumentChange.Type.ADDED) {
                        val doc = change.document
                        launch(Dispatchers.IO) {
                            try {
                                val messageId = doc.getString("messageId") ?: return@launch
                                val isDelivered = doc.getBoolean("isDelivered") ?: false
                                val isRead = doc.getBoolean("isRead") ?: false

                                android.util.Log.d("ChatRepository", "📊 Status update: message=$messageId, delivered=$isDelivered, read=$isRead")

                                // Обновляем статусы в Room (для наших сообщений)
                                if (isDelivered) {
                                    messageDao.markAsDelivered(messageId)
                                }
                                if (isRead) {
                                    messageDao.markAsRead(messageId)
                                }

                                // Удаляем статус после обработки
                                doc.reference.delete().await()
                                android.util.Log.d("ChatRepository", "🗑️ Status deleted after processing")
                            } catch (e: Exception) {
                                android.util.Log.e("ChatRepository", "❌ Status processing error", e)
                            }
                        }
                    }
                }
            }

        // ============================================================
        // 4️⃣ КАНАЛ: Статус онлайн собеседника
        // ============================================================
        val statusListener = firestore.collection("users")
            .document(peerId)
            .addSnapshotListener { snapshot, _ ->
                val isOnline = snapshot?.getBoolean("isOnline") ?: false
                android.util.Log.d("ChatRepository", "👤 Peer online changed: $isOnline")

                if (isOnline) {
                    launch(Dispatchers.IO) {
                        android.util.Log.d("ChatRepository", "⚡ Peer came online, flushing unsent")
                        flushUnsentMessages(chatId)
                    }
                }
            }

        awaitClose {
            android.util.Log.d("ChatRepository", "🛑 Closing engine for: $chatId")
            unsentJob.cancel()
            inboundListener.remove()
            deliveryStatusListener.remove()
            statusListener.remove()
        }
    }

    // В ChatRepository.kt добавить метод
    override suspend fun markMessageAsRead(messageId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                // Получаем сообщение из Room
                val message = messageDao.getMessageById(messageId)
                if (message == null) {
                    android.util.Log.e("ChatRepository", "❌ Message not found: $messageId")
                    return@withContext Result.failure(Exception("Message not found"))
                }

                // Обновляем в Room
                messageDao.markAsRead(messageId)
                android.util.Log.d("ChatRepository", "✅ Message marked as read in Room: $messageId")

                // Отправляем подтверждение прочтения в Firestore
                firestore.collection("chats")
                    .document(message.chatId)
                    .collection("message_statuses")
                    .document(messageId)
                    .set(
                        mapOf(
                            "messageId" to messageId,
                            "isDelivered" to true,
                            "isRead" to true,
                            "timestamp" to System.currentTimeMillis()
                        )
                    )
                    .await()
                android.util.Log.d("ChatRepository", "📤 Read confirmation sent to Firestore")

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Error marking message as read", e)
                Result.failure(e)
            }
        }
    }

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

    override suspend fun flushUnsentMessages(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChatRepository", "🔄 Flush unsent: $chatId")

                val myId = FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext Result.failure(Exception("NO_SESSION"))
                val uids = chatId.split("_")
                val peerId = uids.firstOrNull { it != myId }
                    ?: return@withContext Result.success(Unit)

                val unsent = messageDao.getUnsentMessages(chatId)
                android.util.Log.d("ChatRepository", "📨 Unsent count: ${unsent.size}")

                if (unsent.isEmpty()) {
                    return@withContext Result.success(Unit)
                }

                val peerSnapshot = firestore.collection("users")
                    .document(peerId)
                    .get()
                    .await()
                val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

                if (!isPeerOnline) {
                    android.util.Log.d("ChatRepository", "⏳ Peer offline")
                    return@withContext Result.success(Unit)
                }

                val chatSnapshot = firestore.collection("chats")
                    .document(chatId)
                    .get()
                    .await()

                if (!chatSnapshot.exists()) {
                    android.util.Log.e("ChatRepository", "❌ Chat missing")
                    return@withContext Result.failure(Exception("CHAT_NOT_FOUND"))
                }

                val isUserA = myId == uids[0]
                val peerPublicKey = if (isUserA) {
                    chatSnapshot.getString("publicKeyUserB") ?: ""
                } else {
                    chatSnapshot.getString("publicKeyUserA") ?: ""
                }

                if (peerPublicKey.isEmpty()) {
                    android.util.Log.e("ChatRepository", "❌ No peer key")
                    return@withContext Result.failure(Exception("NO_PUBLIC_KEY"))
                }

                unsent.forEach { localMsg ->
                    try {
                        val encryptedText = CryptoManager.encrypt(localMsg.text, peerPublicKey)
                        val msgDoc = MessageDocument(
                            id = localMsg.id,
                            chatId = chatId,
                            senderId = myId,
                            encryptedText = encryptedText,
                            timestamp = localMsg.timestamp,
                            isDelivered = false,
                            isRead = false
                        )

                        firestore.collection("chats")
                            .document(chatId)
                            .collection("messages")
                            .document(localMsg.id)
                            .set(msgDoc)
                            .await()

                        // Помечаем как отправленное
                        messageDao.markAsSent(localMsg.id)
                        android.util.Log.d("ChatRepository", "✅ Sent: ${localMsg.id}")
                    } catch (e: Exception) {
                        android.util.Log.e("ChatRepository", "❌ Send fail", e)
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Flush error", e)
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