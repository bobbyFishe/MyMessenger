package com.example.mymessenger.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mymessenger.MainActivity
import com.example.mymessenger.R
import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.data.utils.Constants
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
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val messageDao: MessageDao,
    private val chatKeyDao: ChatKeyDao,
    private val userRepository: UserRepository
) : ChatRepository {

    private val activeListeners = mutableSetOf<String>()
    @Volatile
    private var currentActiveChatId: String? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "messenger_messages_channel"

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "Новые сообщения"
                val descriptionText = "Уведомления о входящих зашифрованных сообщениях"
                val importance = NotificationManager.IMPORTANCE_HIGH

                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ Не удалось создать канал уведомлений: ", e)
        }
    }



    override fun setActiveChatId(chatId: String?) {
        currentActiveChatId = chatId
    }

    override fun getActiveChatId(): String? = currentActiveChatId

    override fun getUnreadCount(chatId: String): Flow<Int> = messageDao.getUnreadCountForChat(chatId)

    override suspend fun markMessagesAsRead(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                messageDao.markChatAsRead(chatId)
                notificationManager.cancel(chatId.hashCode())
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun sendMessage(chatId: String, text: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val myId = FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext Result.failure(Exception("NO_SESSION"))

                val uids = chatId.split("_")
                val isSelfChat = uids.size >= 2 && uids[0] == uids[1] && uids[0] == myId

                val messageId = firestore.collection(Constants.FIRESTORE_CHATS)
                    .document(chatId)
                    .collection(Constants.FIRESTORE_MESSAGES)
                    .document()
                    .id

                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = messageId,
                        chatId = chatId,
                        senderId = myId,
                        text = text,
                        timestamp = System.currentTimeMillis(),
                        isMine = true,
                        isRead = true
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

            firestore.collection(Constants.FIRESTORE_CHATS)
                .document(chatId)
                .collection(Constants.FIRESTORE_MESSAGES)
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
        val listener = firestore.collection(Constants.FIRESTORE_CHATS)
            .document(chatId)
            .collection(Constants.FIRESTORE_MESSAGES)
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
            val isUserReadingThisChatRightNow = (chatId == currentActiveChatId)
            android.util.Log.d("ChatRepository", "📱 Входящий chatId=$chatId | Активный в трекере=$currentActiveChatId | Итог проверки=$isUserReadingThisChatRightNow")
            messageDao.insertMessage(
                LocalMessageEntity(
                    id = remoteMsg.id,
                    chatId = chatId,
                    senderId = remoteMsg.senderId,
                    text = decryptedText,
                    timestamp = System.currentTimeMillis(),
                    isMine = false,
                    isRead = isUserReadingThisChatRightNow
                )
            )

            if (!isUserReadingThisChatRightNow) {
                showLocalNotification(chatId, remoteMsg.senderId, decryptedText)
            }

            try {
                doc.reference.delete().await()
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Failed to delete from Firestore", e)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ processIncomingMessage error", e)
        }
    }

    private fun showLocalNotification(chatId: String, senderId: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val cachedContact = userRepository.getCachedContact(senderId)
            val senderName = cachedContact?.name ?: "Новое сообщение"
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("CHAT_ID", chatId)
            }

            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                chatId.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val defaultSoundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(senderName)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setVibrate(longArrayOf(0, 250, 100, 250))
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            notificationManager.notify(chatId.hashCode(), notificationBuilder.build())
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
