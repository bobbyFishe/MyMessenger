package com.example.mymessenger

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                lastActiveChatId?.let { chatRepository.setActiveChatId(it) }
            }

            override fun onStop(owner: LifecycleOwner) {
                lastActiveChatId = chatRepository.getActiveChatId()
                chatRepository.setActiveChatId(null)
            }
        })

        handleNotificationIntent(intent)

        enableEdgeToEdge()
        setContent {
            MyMessengerTheme {
                MyMessageApp()
            }
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
            _notificationChatClickFlow.tryEmit(chatId)
            intent.removeExtra("CHAT_ID")
        }
    }
}
