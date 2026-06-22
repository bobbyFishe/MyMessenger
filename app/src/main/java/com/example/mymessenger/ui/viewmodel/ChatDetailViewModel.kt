// ui/viewmodel/ChatDetailViewModel.kt

package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ChatDetailUiState {
    object Loading : ChatDetailUiState
    data class Success(val messages: List<LocalMessageEntity>) : ChatDetailUiState
    data class Error(val messageResId: Int) : ChatDetailUiState
}

class ChatDetailViewModel(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChatDetailUiState>(ChatDetailUiState.Loading)
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    private var currentChatId: String? = null
    private var isFirstLoad = true

    private val scrollPositions = mutableMapOf<String, Int>()
    private val loadedChats = mutableSetOf<String>()

    fun getScrollPosition(chatId: String): Int? {
        return scrollPositions[chatId]
    }

    fun saveScrollPosition(chatId: String, position: Int) {
        scrollPositions[chatId] = position
    }

    fun markChatAsLoaded(chatId: String) {
        loadedChats.add(chatId)
    }

    fun isChatLoaded(chatId: String): Boolean {
        return loadedChats.contains(chatId)
    }
    private val messageCache = object : LinkedHashMap<String, List<LocalMessageEntity>>(
        16,  // initialCapacity
        0.75f,  // loadFactor
        true  // accessOrder = true (LRU порядок)
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LocalMessageEntity>>?): Boolean {
            return size > 5
        }
    }

    private val CACHE_MESSAGE_LIMIT = 300

    init {
        android.util.Log.d("ChatDetailVM", "🚀 ViewModel created")
    }

    fun initChat(chatId: String) {
        android.util.Log.d("ChatDetailVM", "📱 initChat: $chatId, current: $currentChatId")

        // ✅ Если тот же чат и уже загружен — не перезагружаем
        if (currentChatId == chatId && !isFirstLoad) {
            android.util.Log.d("ChatDetailVM", "⏭️ Same chat, skipping load")
            return
        }

        val chatChanged = currentChatId != chatId
        currentChatId = chatId
        isFirstLoad = false

        viewModelScope.launch {
            // ✅ 1. Показываем кеш (если есть) - МГНОВЕННО!
            val cached = messageCache[chatId]
            if (cached != null) {
                android.util.Log.d("ChatDetailVM", "📦 Cache hit: ${cached.size} messages")
                _uiState.value = ChatDetailUiState.Success(messages = cached)
            } else {
                android.util.Log.d("ChatDetailVM", "💾 Cache miss, loading from Room")
                // ✅ Показываем загрузку только если нет кеша
                _uiState.value = ChatDetailUiState.Loading
            }

            // ✅ 2. Загружаем свежие данные из Room в фоне
            try {
                val freshMessages = withContext(Dispatchers.IO) {
                    // ✅ Загружаем только последние CACHE_MESSAGE_LIMIT сообщений
                    chatRepository.getLastMessagesSync(chatId, CACHE_MESSAGE_LIMIT)
                }

                android.util.Log.d("ChatDetailVM", "📥 Loaded ${freshMessages.size} messages from Room")

                // ✅ Сохраняем в кеш (ограничиваем размер)
                val limitedMessages = if (freshMessages.size > CACHE_MESSAGE_LIMIT) {
                    freshMessages.takeLast(CACHE_MESSAGE_LIMIT)
                } else {
                    freshMessages
                }
                messageCache[chatId] = limitedMessages

                // ✅ Обновляем UI
                _uiState.value = ChatDetailUiState.Success(messages = limitedMessages)

            } catch (e: Exception) {
                android.util.Log.e("ChatDetailVM", "❌ Error loading messages", e)
                _uiState.value = ChatDetailUiState.Error(R.string.error_network_failed)
            }

            // ✅ 3. Подписываемся на обновления (для новых сообщений)
            if (chatChanged) {
                chatRepository.getLocalMessages(chatId)
                    .catch { e ->
                        android.util.Log.e("ChatDetailVM", "❌ Flow error", e)
                    }
                    .collect { newMessages ->
                        android.util.Log.d("ChatDetailVM", "🔄 Flow update: ${newMessages.size} messages")

                        // ✅ Обновляем кеш
                        val limited = if (newMessages.size > CACHE_MESSAGE_LIMIT) {
                            newMessages.takeLast(CACHE_MESSAGE_LIMIT)
                        } else {
                            newMessages
                        }
                        messageCache[chatId] = limited

                        // ✅ Обновляем UI только если чат активен
                        if (currentChatId == chatId) {
                            _uiState.value = ChatDetailUiState.Success(messages = limited)
                        }
                    }
            }
        }
    }

    fun updateMessageText(newText: String) {
        _messageText.value = newText
    }

    fun sendMessage() {
        val chatId = currentChatId ?: return
        val textToSend = _messageText.value.trim()
        if (textToSend.isEmpty()) return

        _messageText.value = ""

        viewModelScope.launch {
            chatRepository.sendMessage(chatId, textToSend).fold(
                onSuccess = {
                    // Сообщение отправлено
                },
                onFailure = {
                    // Ошибка
                }
            )
        }
    }

    fun markMessageAsRead(messageId: String) {
        viewModelScope.launch {
            chatRepository.markMessageAsRead(messageId)
                .onSuccess {
                    android.util.Log.d("ChatDetailVM", "✅ Message marked as read: $messageId")
                }
                .onFailure { e ->
                    android.util.Log.e("ChatDetailVM", "❌ Failed to mark as read", e)
                }
        }
    }

    fun onResume() {
        val chatId = currentChatId ?: return
        viewModelScope.launch {
            chatRepository.forceSync(chatId)
        }
    }

    // ✅ Очистка кеша при уничтожении ViewModel
    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("ChatDetailVM", "🧹 ViewModel cleared, cache size: ${messageCache.size}")
        messageCache.clear()
    }
}