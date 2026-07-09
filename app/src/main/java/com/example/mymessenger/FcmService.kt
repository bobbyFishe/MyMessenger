package com.example.mymessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mymessenger.data.repository.ChatRepositoryImpl
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import androidx.core.content.edit

class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        android.util.Log.d("FcmService", "📱 Новый FCM токен: $token")
        saveTokenToServer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        android.util.Log.d("FcmService", "📨 ПОЛУЧЕНО FCM СООБЩЕНИЕ")
        android.util.Log.d("FcmService", "📨 From: ${message.from}")
        android.util.Log.d("FcmService", "📨 Data: ${message.data}")

        // Всегда обрабатываем data, даже если есть notification
        val data = message.data

        // Если есть notification payload - показываем уведомление с ним
        if (message.notification != null) {
            val title = message.notification?.title ?: "Новое сообщение"
            val body = message.notification?.body ?: ""
            showNotification(
                data["chatId"] ?: "",
                data["senderId"] ?: "",
                body,
                title
            )
            return
        }

        // Обработка data-only сообщений
        if (data.isNotEmpty()) {
            val type = data["type"] ?: return
            val chatId = data["chatId"] ?: ""
            val senderId = data["senderId"] ?: ""
            val text = data["text"] ?: "Новое сообщение"

            when (type) {
                "NEW_MESSAGE" -> {
                    val activeChatId = ChatRepositoryImpl.currentActiveChatId

                    android.util.Log.d("FcmService", "📩 activeChatId: $activeChatId, chatId: $chatId")

                    if (chatId == activeChatId) {
                        android.util.Log.d("FcmService", "⏭️ Пропускаем уведомление — пользователь в чате $chatId")
                        return
                    }

                    android.util.Log.d("FcmService", "✅ Новое сообщение: $text")
                    val senderName = data["senderName"] ?: "Новое сообщение"
                    showNotification(chatId, senderId, text, senderName)
                }
                "USER_OFFLINE" -> {
                    // Отправляем broadcast только если приложение запущено
                    sendStatusBroadcast(chatId, senderId, false)
                }
                "USER_ONLINE" -> {
                    sendStatusBroadcast(chatId, senderId, true)
                }
            }
        }
    }

    private fun showNotification(
        chatId: String,
        senderId: String,
        text: String,
        title: String = "Новое сообщение"
    ) {
        android.util.Log.d("FcmService", "🔔 Показываем уведомление: $text")

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("CHAT_ID", chatId)
            putExtra("OPEN_CHAT", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(), // Уникальный requestCode
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Создаем канал уведомлений
        createNotificationChannel(notificationManager)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Показывать на заблокированном экране
            .build()

        // Используем уникальный ID для каждого уведомления
        val notificationId = chatId.hashCode()
        notificationManager.notify(notificationId, notification)
    }

    private fun createNotificationChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendStatusBroadcast(
        chatId: String,
        userId: String,
        isOnline: Boolean
    ) {
        try {
            val intent = Intent(USER_STATUS_ACTION).apply {
                setPackage(packageName) // Ограничиваем broadcast нашим приложением
                putExtra("chatId", chatId)
                putExtra("userId", userId)
                putExtra("isOnline", isOnline)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            android.util.Log.e("FcmService", "❌ Ошибка отправки broadcast", e)
        }
    }

    private fun saveTokenToServer(token: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            // Сохраняем токен локально, чтобы записать после авторизации
            getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                .edit {
                    putString("pending_fcm_token", token)
                }

            FirebaseAuth.getInstance().addAuthStateListener(
                object : FirebaseAuth.AuthStateListener {
                    override fun onAuthStateChanged(auth: FirebaseAuth) {
                        val currentUser = auth.currentUser
                        if (currentUser != null) {
                            saveTokenToDatabase(token, currentUser.uid)
                            auth.removeAuthStateListener(this)
                        }
                    }
                })
            return
        }
        saveTokenToDatabase(token, user.uid)
    }

    private fun saveTokenToDatabase(token: String, uid: String) {
        // Сохраняем в RTDB
        FirebaseDatabase.getInstance()
            .getReference("users/$uid/fcmToken")
            .setValue(token)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.d("FcmService", "✅ Токен сохранен в RTDB")
                } else {
                    android.util.Log.e("FcmService", "❌ Ошибка сохранения в RTDB", task.exception)
                }
            }

        // Сохраняем в Firestore
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .update("fcmToken", token)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    android.util.Log.d("FcmService", "✅ Токен сохранен в Firestore")
                } else {
                    android.util.Log.e("FcmService", "❌ Ошибка сохранения в Firestore", task.exception)
                }
            }
    }

    companion object {
        const val CHANNEL_ID = "messenger_messages"
        const val USER_STATUS_ACTION = "com.example.mymessenger.USER_STATUS_CHANGED"
    }
}