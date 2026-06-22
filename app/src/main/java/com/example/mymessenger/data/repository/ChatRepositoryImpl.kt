// data/repository/ChatRepositoryImpl.kt
package com.example.mymessenger.data.repository

import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.data.utils.CryptoManager
import com.example.mymessenger.domain.model.MessageDocument
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class ChatRepositoryImpl(
    private val firestore: FirebaseFirestore,
    private val messageDao: MessageDao,
    private val chatKeyDao: ChatKeyDao,
    private val userRepository: UserRepository
) : ChatRepository {

    private val activeEngines = mutableSetOf<String>()

    override suspend fun sendMessage(chatId: String, text: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChatRepository", "📝 sendMessage: chatId=$chatId")

                val myId = FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext Result.failure(Exception("NO_SESSION"))

                val uids = chatId.split("_")
                val isSelfChat = uids.size >= 2 && uids[0] == uids[1]

                val messageId = firestore.collection("chats")
                    .document(chatId)
                    .collection("messages")
                    .document()
                    .id

                // ✅ Сохраняем в Room
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
                    CoroutineScope(Dispatchers.IO).launch {
                        deliverMessageToFirestore(chatId, messageId)
                    }
                } else {
                    messageDao.markAsSent(messageId)
                    messageDao.markAsDelivered(messageId)
                    messageDao.markAsRead(messageId)
                }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ sendMessage error", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun deliverMessageToFirestore(chatId: String, messageId: String) {
        try {
            android.util.Log.d("ChatRepository", "📤 deliverMessage: $messageId")

            val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val uids = chatId.split("_")
            val peerId = uids.firstOrNull { it != myId } ?: return

            val peerPublicKey = getPeerPublicKey(chatId, myId, uids)
            if (peerPublicKey.isEmpty()) {
                android.util.Log.e("ChatRepository", "❌ No peer public key")
                return
            }

            val localMessage = messageDao.getMessageById(messageId) ?: return
            val encryptedText = CryptoManager.encrypt(localMessage.text, peerPublicKey)

            val msgDoc = MessageDocument(
                id = messageId,
                chatId = chatId,
                senderId = myId,
                encryptedText = encryptedText,
                timestamp = localMessage.timestamp,
                isDelivered = false,
                isRead = false
            )

            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .set(msgDoc)
                .await()

            // ✅ Статус "Отправлено" (в Firestore записано)
            messageDao.markAsSent(messageId)
            android.util.Log.d("ChatRepository", "✅ Message sent to Firestore: $messageId")

            // ✅ Пытаемся доставить сразу (если собеседник онлайн)
            deliverAllUndeliveredMessages(chatId)

        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ deliverMessage error", e)
        }
    }

    override fun startP2PDeliveryEngine(chatId: String): Flow<Unit> = callbackFlow {
        if (activeEngines.contains(chatId)) {
            android.util.Log.d("ChatRepository", "⏭️ Engine already running for: $chatId")
            close()
            return@callbackFlow
        }
        activeEngines.add(chatId)

        android.util.Log.d("ChatRepository", "🚀 startP2PDeliveryEngine: $chatId")

        val myId = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            activeEngines.remove(chatId)
            close()
            return@callbackFlow
        }

        val uids = chatId.split("_")
        if (uids.size < 2 || uids[0] == uids[1]) {
            android.util.Log.d("ChatRepository", "⚠️ Self chat, skip")
            activeEngines.remove(chatId)
            close()
            return@callbackFlow
        }

        val peerId = uids.firstOrNull { it != myId } ?: run {
            activeEngines.remove(chatId)
            close()
            return@callbackFlow
        }

        android.util.Log.d("ChatRepository", "✅ My ID: $myId, Peer: $peerId")

        val channel = this

        // ⚡ Доставляем все недоставленные при запуске
        launch(Dispatchers.IO) {
            deliverAllUndeliveredMessages(chatId)
        }

        // ============================================================
        // 1️⃣ КАНАЛ: Доставка НЕДОСТАВЛЕННЫХ сообщений
        // ============================================================
        val undeliveredJob = launch(Dispatchers.IO) {
            messageDao.getUndeliveredMessagesFlow(chatId)
                .catch { e ->
                    android.util.Log.e("ChatRepository", "❌ Undelivered flow error", e)
                }
                .collect { undeliveredList ->
                    if (undeliveredList.isNotEmpty()) {
                        android.util.Log.d("ChatRepository", "📨 Undelivered: ${undeliveredList.size}")
                        checkAndDeliver(chatId, peerId)
                    }
                }
        }

        // ============================================================
        // 2️⃣ КАНАЛ: Входящие сообщения
        // ============================================================
        val inboundListener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    android.util.Log.e("ChatRepository", "❌ Inbound error", error)
                    return@addSnapshotListener
                }

                snapshot.documentChanges.forEach { change ->
                    when (change.type) {
                        DocumentChange.Type.ADDED -> {
                            val doc = change.document
                            launch(Dispatchers.IO) {
                                processIncomingMessage(channel, doc, chatId, myId, peerId)
                            }
                        }
                        DocumentChange.Type.MODIFIED -> {
                            val doc = change.document
                            launch(Dispatchers.IO) {
                                processModifiedMessage(doc, myId)
                            }
                        }
                        DocumentChange.Type.REMOVED -> {
                            android.util.Log.d("ChatRepository", "🗑️ Message removed: ${change.document.id}")
                        }
                    }
                }
            }

        // ============================================================
        // 3️⃣ КАНАЛ: Статус онлайн собеседника
        // ============================================================
        val statusListener = firestore.collection("users")
            .document(peerId)
            .addSnapshotListener { snapshot, _ ->
                val isOnline = snapshot?.getBoolean("isOnline") ?: false
                android.util.Log.d("ChatRepository", "👤 Peer online: $isOnline")

                if (isOnline) {
                    launch(Dispatchers.IO) {
                        android.util.Log.d("ChatRepository", "⚡ Peer online, delivering")
                        deliverAllUndeliveredMessages(chatId)
                    }
                }
            }

        awaitClose {
            android.util.Log.d("ChatRepository", "🛑 Closing engine: $chatId")
            activeEngines.remove(chatId)
            undeliveredJob.cancel()
            inboundListener.remove()
            statusListener.remove()
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private suspend fun checkAndDeliver(chatId: String, peerId: String) {
        try {
            val peerSnapshot = firestore.collection("users")
                .document(peerId)
                .get()
                .await()
            val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

            if (isPeerOnline) {
                deliverAllUndeliveredMessages(chatId)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ checkAndDeliver error", e)
        }
    }

    // ============================================================
    // ОСНОВНАЯ ЛОГИКА ОБРАБОТКИ ВХОДЯЩИХ СООБЩЕНИЙ
    // ============================================================

    private suspend fun processIncomingMessage(
        channel: ProducerScope<Unit>,
        doc: com.google.firebase.firestore.DocumentSnapshot,
        chatId: String,
        myId: String,
        peerId: String
    ) {
        try {
            val remoteMsg = doc.toObject(MessageDocument::class.java) ?: return

            android.util.Log.d("ChatRepository", "📥 processIncoming: ${remoteMsg.id}, sender=${remoteMsg.senderId}, isDelivered=${remoteMsg.isDelivered}, isRead=${remoteMsg.isRead}")

            // ✅ Если это моё сообщение
            if (remoteMsg.senderId == myId) {
                android.util.Log.d("ChatRepository", "⏭️ My message: ${remoteMsg.id}")

                // ✅ Если сообщение ещё не доставлено - помечаем как доставленное
                if (!remoteMsg.isDelivered) {
                    doc.reference.update("isDelivered", true).await()
                    messageDao.markAsDelivered(remoteMsg.id)
                    android.util.Log.d("ChatRepository", "✅ My message delivered: ${remoteMsg.id}")
                }

                // ✅ Если сообщение уже прочитано - обновляем статус и удаляем
                if (remoteMsg.isRead) {
                    messageDao.markAsRead(remoteMsg.id)
                    doc.reference.delete().await()
                    android.util.Log.d("ChatRepository", "🗑️ My message deleted after read: ${remoteMsg.id}")
                }
                return
            }

            // ✅ Сообщение от собеседника
            val keyEntity = chatKeyDao.getKeyForChat(chatId)
            if (keyEntity == null) {
                android.util.Log.e("ChatRepository", "❌ No private key for chat: $chatId")
                return
            }

            // ✅ Расшифровываем
            val decryptedText = CryptoManager.decrypt(
                remoteMsg.encryptedText,
                keyEntity.privateKey
            )

            // ✅ Проверяем, есть ли уже такое сообщение в Room
            val existing = messageDao.getMessageById(remoteMsg.id)
            if (existing == null) {
                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = remoteMsg.id,
                        chatId = chatId,
                        senderId = remoteMsg.senderId,
                        text = decryptedText,
                        timestamp = remoteMsg.timestamp,
                        isSent = true,
                        isDelivered = true,  // ✅ Получено и расшифровано
                        isRead = false
                    )
                )
                android.util.Log.d("ChatRepository", "💾 New message saved: ${remoteMsg.id}")
            }

            // ✅ Отправляем подтверждение доставки
            doc.reference.update("isDelivered", true).await()
            android.util.Log.d("ChatRepository", "📤 Delivery confirmation sent for: ${remoteMsg.id}")

            // ✅ НЕ УДАЛЯЕМ сообщение из Firestore!
            // Оно остаётся до тех пор, пока не будет прочитано
            // Отправитель увидит isDelivered=true и isRead=false

            // ✅ Отправляем сигнал об обновлении
            channel.trySend(Unit)

        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ processIncoming error", e)
        }
    }

    private suspend fun processModifiedMessage(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        myId: String
    ) {
        try {
            val isDelivered = doc.getBoolean("isDelivered") ?: false
            val isRead = doc.getBoolean("isRead") ?: false

            val remoteMsg = doc.toObject(MessageDocument::class.java)
            if (remoteMsg?.senderId == myId) {
                android.util.Log.d("ChatRepository", "📥 processModified: ${doc.id}, isDelivered=$isDelivered, isRead=$isRead")

                if (isDelivered) {
                    messageDao.markAsDelivered(doc.id)
                    android.util.Log.d("ChatRepository", "✅ My message delivered: ${doc.id}")
                }

                if (isRead) {
                    messageDao.markAsRead(doc.id)
                    android.util.Log.d("ChatRepository", "✅ My message read: ${doc.id}")

                    // ✅ Теперь можно удалить из Firestore (оба участника подтвердили прочтение)
                    doc.reference.delete().await()
                    android.util.Log.d("ChatRepository", "🗑️ Message deleted after read confirmation: ${doc.id}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ processModified error", e)
        }
    }

    // ============================================================
    // ДОСТАВКА НЕДОСТАВЛЕННЫХ СООБЩЕНИЙ
    // ============================================================

    private suspend fun deliverAllUndeliveredMessages(chatId: String) {
        try {
            android.util.Log.d("ChatRepository", "📤 deliverAllUndelivered: $chatId")

            val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val uids = chatId.split("_")
            val peerId = uids.firstOrNull { it != myId } ?: return

            // ✅ Получаем НЕДОСТАВЛЕННЫЕ сообщения (isSent = true, isDelivered = false)
            val undelivered = messageDao.getUndeliveredMessages(chatId)

            if (undelivered.isEmpty()) {
                android.util.Log.d("ChatRepository", "📭 No undelivered messages")
                return
            }

            android.util.Log.d("ChatRepository", "📨 Undelivered: ${undelivered.size}")

            // ✅ Проверяем онлайн
            val peerSnapshot = firestore.collection("users")
                .document(peerId)
                .get()
                .await()
            val isPeerOnline = peerSnapshot.getBoolean("isOnline") ?: false

            if (!isPeerOnline) {
                android.util.Log.d("ChatRepository", "⏳ Peer offline, keeping undelivered")
                return
            }

            android.util.Log.d("ChatRepository", "✅ Peer online, delivering ${undelivered.size} messages")

            // ✅ Доставляем все недоставленные
            undelivered.forEach { localMsg ->
                try {
                    val docSnapshot = firestore.collection("chats")
                        .document(chatId)
                        .collection("messages")
                        .document(localMsg.id)
                        .get()
                        .await()

                    if (docSnapshot.exists()) {
                        // ✅ Обновляем статус в Firestore
                        docSnapshot.reference.update("isDelivered", true).await()
                        android.util.Log.d("ChatRepository", "✅ Delivered: ${localMsg.id}")
                    } else {
                        // ❌ Сообщение пропало из Firestore — переотправляем
                        android.util.Log.w("ChatRepository", "⚠️ Message not in Firestore, resending: ${localMsg.id}")
                        deliverMessageToFirestore(chatId, localMsg.id)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatRepository", "❌ Failed to deliver: ${localMsg.id}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ deliverAllUndelivered error", e)
        }
    }

    // ============================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================

    private suspend fun getPeerPublicKey(chatId: String, myId: String, uids: List<String>): String {
        return userRepository.getCachedPeerPublicKey(chatId, myId)
            ?: run {
                val chatSnapshot = firestore.collection("chats")
                    .document(chatId)
                    .get()
                    .await()

                if (!chatSnapshot.exists()) return ""

                val isUserA = myId == uids[0]
                if (isUserA) {
                    chatSnapshot.getString("publicKeyUserB") ?: ""
                } else {
                    chatSnapshot.getString("publicKeyUserA") ?: ""
                }
            }
    }

    // ============================================================
    // ПУБЛИЧНЫЕ МЕТОДЫ
    // ============================================================

    override suspend fun markMessageAsRead(messageId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val message = messageDao.getMessageById(messageId)
                if (message == null) {
                    android.util.Log.e("ChatRepository", "❌ Message not found: $messageId")
                    return@withContext Result.failure(Exception("Message not found"))
                }

                // ✅ Обновляем в Room
                messageDao.markAsRead(messageId)
                android.util.Log.d("ChatRepository", "✅ Marked as read in Room: $messageId")

                // ✅ Обновляем в Firestore
                val docRef = firestore.collection("chats")
                    .document(message.chatId)
                    .collection("messages")
                    .document(messageId)

                // ✅ Проверяем, существует ли документ
                val snapshot = docRef.get().await()
                if (snapshot.exists()) {
                    // ✅ Обновляем статус
                    docRef.update("isRead", true).await()
                    android.util.Log.d("ChatRepository", "📤 Read confirmation sent to Firestore: $messageId")

                    // ✅ Отправитель увидит MODIFIED и удалит документ
                    // ✅ Получатель тоже удаляет документ (у себя)
                    docRef.delete().await()
                    android.util.Log.d("ChatRepository", "🗑️ Message deleted from receiver: $messageId")
                } else {
                    android.util.Log.d("ChatRepository", "⏭️ Message already deleted from Firestore: $messageId")
                }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ markMessageAsRead error", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun forceSync(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChatRepository", "🔄 Force sync: $chatId")
                deliverAllUndeliveredMessages(chatId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun flushUnsentMessages(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("ChatRepository", "🔄 flushUnsent: $chatId")
                deliverAllUndeliveredMessages(chatId)
                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ flushUnsent error", e)
                Result.failure(e)
            }
        }
    }

    override fun startMessagesTransit(chatId: String): Flow<Unit> = callbackFlow {
        close()
    }

    override fun getLocalMessages(chatId: String): Flow<List<LocalMessageEntity>> {
        return messageDao.getMessagesForChat(chatId)
    }

    override fun getLastLocalMessage(chatId: String): Flow<LocalMessageEntity?> {
        return messageDao.getLastMessageForChat(chatId)
    }

    override suspend fun getMessagesSync(chatId: String): List<LocalMessageEntity> {
        return withContext(Dispatchers.IO) {
            messageDao.getMessagesSync(chatId)
        }
    }

    override suspend fun getLastMessagesSync(chatId: String, limit: Int): List<LocalMessageEntity> {
        return withContext(Dispatchers.IO) {
            messageDao.getLastMessagesSync(chatId, limit)
        }
    }
}