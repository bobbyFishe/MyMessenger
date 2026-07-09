package com.example.mymessenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.ui.theme.MyMessengerTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val chatRepository: ChatRepository by inject()
    private var lastActiveChatId: String? = null

    companion object {
        private val _notificationChatClickFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val notificationChatClickFlow = _notificationChatClickFlow.asSharedFlow()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "USER_STATUS_CHANGED") {
                val chatId = intent.getStringExtra("chatId") ?: return
                val isOnline = intent.getBooleanExtra("isOnline", false)
                android.util.Log.d("MainActivity", "📡 Статус чата $chatId: ${if (isOnline) "online" else "offline"}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                updateUserStatus("online")
                lastActiveChatId?.let { chatRepository.setActiveChatId(it) }
            }

            override fun onStop(owner: LifecycleOwner) {
                lastActiveChatId = chatRepository.getActiveChatId()
                chatRepository.setActiveChatId(null)
            }
        })

        handleNotificationIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter("USER_STATUS_CHANGED"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter("USER_STATUS_CHANGED"))
        }

        enableEdgeToEdge()
        setContent {
            MyMessengerTheme {
                MyMessageApp()
            }
        }
    }

    private fun updateUserStatus(status: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference
            .child("users/$uid/status")
            .setValue(status)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra("CHAT_ID")
        if (!chatId.isNullOrBlank()) {
            android.util.Log.d("MainActivity", "📩 Получен CHAT_ID: $chatId")

            // Задержка, чтобы UI успел загрузиться
            window.decorView.postDelayed({
                _notificationChatClickFlow.tryEmit(chatId)
                android.util.Log.d("MainActivity", "📩 Отправлен в Flow: $chatId")
            }, 500)

            intent.removeExtra("CHAT_ID")
        }
    }

    fun getActiveChatId(): String? {
        return chatRepository.getActiveChatId()
    }
}
