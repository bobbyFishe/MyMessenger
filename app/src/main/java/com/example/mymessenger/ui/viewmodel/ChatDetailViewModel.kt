package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.repository.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    // 🔥 ИСПРАВЛЕНО: Хранилище для текущей активной корутины подписки на сообщения
    private var messageObservationJob: Job? = null

    private val CACHE_MESSAGE_LIMIT = 300
    private val scrollPositions = mutableMapOf<String, Int>()
    private val loadedChats = mutableSetOf<String>()

    fun initChat(chatId: String) {
        if (currentChatId == chatId) return

        currentChatId = chatId
            //_uiState.value = ChatDetailUiState.Loading

        messageObservationJob?.cancel()

        messageObservationJob = viewModelScope.launch {
            try {
                val initialMessages = withContext(Dispatchers.IO) {
                    chatRepository.getLastMessagesSync(chatId, CACHE_MESSAGE_LIMIT)
                }
                _uiState.value = ChatDetailUiState.Success(messages = initialMessages)
            } catch (e: Exception) {
                android.util.Log.e("ChatDetailVM", "❌ Error loading messages cache", e)
                _uiState.value = ChatDetailUiState.Error(R.string.error_network_failed)
            }

            chatRepository.getLocalMessages(chatId)
                .catch { e ->
                    android.util.Log.e("ChatDetailVM", "❌ Flow messages error", e)
                }
                .collect { newMessages ->
                    if (currentChatId == chatId) {
                        val limited = if (newMessages.size > CACHE_MESSAGE_LIMIT) {
                            newMessages.takeLast(CACHE_MESSAGE_LIMIT)
                        } else {
                            newMessages
                        }
                        _uiState.value = ChatDetailUiState.Success(messages = limited)
                    }
                }
        }
    }

    fun updateMessageText(newText: String) {
        if (newText.length <= Constants.MESSAGE_LENGTH) {
            _messageText.value = newText
        }
    }

    fun sendMessage() {
        val chatId = currentChatId ?: return
        val textToSend = _messageText.value.trim()
        if (textToSend.isEmpty() || textToSend.length > Constants.MESSAGE_LENGTH) return

        _messageText.value = ""

        viewModelScope.launch {
            chatRepository.sendMessage(chatId, textToSend)
        }
    }

    fun getScrollPosition(chatId: String): Int? = scrollPositions[chatId]

    fun saveScrollPosition(chatId: String, position: Int) {
        scrollPositions[chatId] = position
    }

    fun markChatAsLoaded(chatId: String) {
        loadedChats.add(chatId)
    }

    fun isChatLoaded(chatId: String): Boolean = loadedChats.contains(chatId)

    override fun onCleared() {
        super.onCleared()
        messageObservationJob?.cancel() // Зачищаем подписку при уничтожении экрана
        scrollPositions.clear()
        loadedChats.clear()
    }
}
