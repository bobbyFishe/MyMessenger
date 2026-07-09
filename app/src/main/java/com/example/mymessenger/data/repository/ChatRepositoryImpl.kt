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
import com.example.mymessenger.data.utils.CryptoManager
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class ChatRepositoryImpl(
    private val context: Context,
    private val firestore: FirebaseFirestore,
    private val messageDao: MessageDao,
    private val chatKeyDao: ChatKeyDao,
    private val userRepository: UserRepository
) : ChatRepository {

    private val activeListeners = mutableSetOf<String>()

    companion object {
        @Volatile
        var currentActiveChatId: String? = null
    }
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "messenger_messages_channel"
    private val rtdb = FirebaseDatabase.getInstance().reference

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
        Companion.currentActiveChatId = chatId
        if (chatId != null) {
            notificationManager.cancel(chatId.hashCode())
        }
    }

    override fun getActiveChatId(): String? = Companion.currentActiveChatId

    override fun getUnreadCount(chatId: String): Flow<Int> =
        messageDao.getUnreadCountForChat(chatId)

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
                if (!isSelfChat) {
                    val peerId = uids.firstOrNull { it != myId } ?: return@withContext Result.failure(Exception("PEER_NOT_FOUND"))

                    // ✅ Проверяем статус получателя
                    val statusSnapshot = rtdb.child("users/$peerId/status").get().await()
                    val status = statusSnapshot.getValue(String::class.java) ?: "online"

                    // ✅ БЛОКИРУЕМ ТОЛЬКО ЕСЛИ СТАТУС "offline"
                    if (status == "offline") {
                        android.util.Log.d("ChatRepository", "📴 Пользователь вышел из аккаунта, сообщение не отправлено")
                        return@withContext Result.failure(Exception("RECIPIENT_OFFLINE"))
                    }
                    android.util.Log.d("ChatRepository", "📨 Статус получателя: $status, отправляем")
                }

                // Генерируем случайный бесплатный ID для сообщения на основе RTDB
                val rtdb = FirebaseDatabase.getInstance().reference
                val messageId = rtdb.child("transit_messages").child(chatId).push().key
                    ?: return@withContext Result.failure(Exception("FAILED_TO_GENERATE_ID"))

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

                val peerPublicKey = userRepository.getCachedPeerPublicKey(chatId, myId)
                    ?: return@withContext Result.failure(Exception("PEER_KEY_NOT_FOUND"))
                val encryptedText = CryptoManager.encrypt(text, peerPublicKey)

                val msgMap = mapOf(
                    "id" to messageId,
                    "chatId" to chatId,
                    "senderId" to myId,
                    "encryptedText" to encryptedText,
                    "timestamp" to System.currentTimeMillis()
                )

                rtdb.child("transit_messages").child(chatId).child(messageId)
                    .setValue(msgMap)
                    .await()
                sendFcmViaHttp(chatId, myId, text)


                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ sendMessage error RTDB", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun sendFcmViaHttp(chatId: String, senderId: String, text: String) {
        try {
            val uids = chatId.split("_")
            val recipientId = uids.firstOrNull { it != senderId } ?: return

            val tokenSnapshot = rtdb.child("users/$recipientId/fcmToken").get().await()
            val token = tokenSnapshot.getValue(String::class.java) ?: return
            val senderName = userRepository.getCachedContact(senderId)?.name ?: "Пользователь"
            // Получаем OAuth токен через Service Account
            val accessToken = getAccessToken()

            val jsonBody = JSONObject().apply {
                put("message", JSONObject().apply {
                    put("token", token)
                    put("data", JSONObject().apply {
                        put("type", "NEW_MESSAGE")
                        put("chatId", chatId)
                        put("senderId", senderId)
                        put("text", text)
                        put("senderName", senderName)
                    })
                    put("android", JSONObject().apply {
                        put("priority", "high")
                    })
                })
            }

            val client = OkHttpClient()
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("https://fcm.googleapis.com/v1/projects/messendger-demo/messages:send")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val responseBody = response.body?.string()
            android.util.Log.d("ChatRepository", "✅ FCM ответ: ${response.code} $responseBody")

        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ Ошибка FCM: ${e.message}")
        }
    }

    private fun getAccessToken(): String {
        val inputStream = context.assets.open("service_account.json")
        val serviceAccountJson = inputStream.bufferedReader().use { it.readText() }

        val credentials = GoogleCredentials
            .fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray(StandardCharsets.UTF_8)))
            .createScoped("https://www.googleapis.com/auth/firebase.messaging")

        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    override fun startP2PDeliveryEngine(chatId: String): Flow<Unit> = callbackFlow {
        android.util.Log.d("ChatRepository", "🔥 startP2PDeliveryEngine вызван для $chatId")
        val myId = FirebaseAuth.getInstance().currentUser?.uid
        if (myId == null) {
            android.util.Log.e("ChatRepository", "❌ Нет UID")
            close()
            return@callbackFlow
        }

        val uids = chatId.split("_")
        if (uids.size < 2 || (uids.firstOrNull() == myId && uids.lastOrNull() == myId)) {
            android.util.Log.d("ChatRepository", "ℹ️ Пропускаем self-chat: $chatId")
            close()
            return@callbackFlow
        }
        activeListeners.add(chatId)
        val chatRef = rtdb.child("transit_messages").child(chatId)
        android.util.Log.d("ChatRepository", "🔥 Начинаем обработку сообщений для $chatId")
        try {
            val existingMessages = chatRef.get().await()
            android.util.Log.d("ChatRepository", "📨 Найдено ${existingMessages.childrenCount} существующих сообщений")
            if (existingMessages.exists()) {
                for (child in existingMessages.children) {
                    android.util.Log.d("ChatRepository", "📨 Обрабатываем сообщение: ${child.key}")
                    processRtdbMessage(child, chatId, myId)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "❌ Ошибка загрузки существующих сообщений", e)
        }

        // 2. Подписываемся на новые
        val childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                CoroutineScope(Dispatchers.IO).launch {  // или CoroutineScope(Dispatchers.IO).launch
                    processRtdbMessage(snapshot, chatId, myId)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.e("ChatRepository", "❌ RTDB listener cancelled: ${error.message}")
            }
        }

        chatRef.addChildEventListener(childListener)

        awaitClose {
            activeListeners.remove(chatId)
            chatRef.removeEventListener(childListener)
        }
    }

    // Выносим логику обработки
    private fun processRtdbMessage(
        snapshot: DataSnapshot,
        chatId: String,
        myId: String
    ) {
        android.util.Log.e("ChatRepository", "🔥🔥🔥 processRtdbMessage ВЫЗВАН для $chatId")
        val msgId = snapshot.child("id").value as? String ?: return
        val senderId = snapshot.child("senderId").value as? String ?: return
        val encryptedText = snapshot.child("encryptedText").value as? String ?: return

        if (senderId == myId) return

        if (encryptedText == "SYSTEM_KEY_RESET") {
            snapshot.ref.removeValue()
            CoroutineScope(Dispatchers.IO).launch(kotlinx.coroutines.NonCancellable) {
                try {
                    userRepository.refreshChatsCache(myId)
                    android.util.Log.d("ChatRepository", "⚡ Кэш обновлен по сигналу!")
                } catch (e: Exception) {
                    android.util.Log.e("ChatRepository", "❌ Ошибка обновления кэша", e)
                }
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch(kotlinx.coroutines.NonCancellable) {
            try {
                val keyEntity = chatKeyDao.getKeyForChat(chatId) ?: return@launch
                val decryptedText = CryptoManager.decrypt(encryptedText, keyEntity.privateKey)

                val isUserReadingThisChatRightNow = (chatId == currentActiveChatId)
                messageDao.insertMessage(
                    LocalMessageEntity(
                        id = msgId,
                        chatId = chatId,
                        senderId = senderId,
                        text = decryptedText,
                        timestamp = System.currentTimeMillis(),
                        isMine = false,
                        isRead = isUserReadingThisChatRightNow
                    )
                )
                snapshot.ref.removeValue()

//                if (!isUserReadingThisChatRightNow) {
//                    showLocalNotification(chatId, senderId, decryptedText)
//                }
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Ошибка обработки сообщения RTDB", e)
            }
        }
    }


    private fun showLocalNotification(chatId: String, senderId: String, text: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val cachedContact = userRepository.getCachedContact(senderId)
            val senderName = cachedContact?.name ?: "Новое сообщение"
            val intent = android.content.Intent(context, MainActivity::class.java).apply {
                flags =
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("CHAT_ID", chatId)
            }

            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                chatId.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val defaultSoundUri =
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
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

    override suspend fun regenerateKeysIfMissing(chatId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val myId = FirebaseAuth.getInstance().currentUser?.uid
                    ?: return@withContext Result.failure(Exception("NO_SESSION"))

                // 1. Проверяем, есть ли локальный приватный ключ для этого чата в Room
                val existingKey = chatKeyDao.getKeyForChat(chatId)
                if (existingKey != null) {
                    // Ключ на месте (всё в порядке), прерываем выполнение без трат квот
                    return@withContext Result.success(Unit)
                }

                // 2. Ключа нет (телефон чистый после переустановки/логаута). Генерируем новую пару RSA
                val keyPair = CryptoManager.generateKeyPair()
                val privateKeyStr = CryptoManager.privateKeyToString(keyPair.private)
                val publicKeyStr = CryptoManager.publicKeyToString(keyPair.public)

                // 3. Сохраняем свежий приватный ключ в локальную базу Room
                chatKeyDao.insertKey(
                    com.example.mymessenger.data.local.entities.ChatKeyEntity(
                        chatId = chatId,
                        privateKey = privateKeyStr
                    )
                )

                // 4. Определяем нашу роль в чате, чтобы обновить открытый ключ в Firestore
                val chatDocRef = firestore.collection("chats").document(chatId)
                val uids = chatId.split("_")

                if (uids.size >= 2 && uids[0] != uids[1]) {
                    val isUserA = uids.firstOrNull() == myId
                    val fieldToUpdate = if (isUserA) "publicKeyUserA" else "publicKeyUserB"

                    // Обновляем открытый ключ на сервере Firestore (это операция Записи/Write)
                    chatDocRef.update(fieldToUpdate, publicKeyStr).await()
                    android.util.Log.d(
                        "ChatRepository",
                        "🔄 Новые RSA ключи созданы и выгружены в Firestore ($fieldToUpdate)"
                    )

                    // 5. 🔥 БЕСПЛАТНЫЙ ХАНДШЕЙК: Отправляем невидимое системное СМС через Realtime Database.
                    // Оно мгновенно и без затрат квот скажет телефону друга: «Я сбросил ключи, обнови кэш!»
                    val systemMsgId = rtdb.child("transit_messages").child(chatId).push().key
                    if (systemMsgId != null) {
                        val systemMsgMap = mapOf(
                            "id" to systemMsgId,
                            "chatId" to chatId,
                            "senderId" to myId,
                            "encryptedText" to "SYSTEM_KEY_RESET", // Специальный текст-маркер
                            "timestamp" to System.currentTimeMillis()
                        )
                        rtdb.child("transit_messages").child(chatId).child(systemMsgId)
                            .setValue(systemMsgMap)
                            .await()
                    }
                }

                Result.success(Unit)
            } catch (e: Exception) {
                android.util.Log.e("ChatRepository", "❌ Ошибка регенерации ключей", e)
                Result.failure(e)
            }
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
