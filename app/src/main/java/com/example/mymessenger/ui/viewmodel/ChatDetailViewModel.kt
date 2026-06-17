package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

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

    fun onResume() {
        val chatId = currentChatId ?: return
        viewModelScope.launch {
            chatRepository.forceSync(chatId)
        }
    }
    fun initChat(chatId: String) {
        if (currentChatId == chatId) return
        currentChatId = chatId

        viewModelScope.launch {
            chatRepository.startP2PDeliveryEngine(chatId)
                .catch { }
                .collect {}
        }

        viewModelScope.launch {
            chatRepository.getLocalMessages(chatId)
                .catch { _uiState.value = ChatDetailUiState.Error(R.string.error_network_failed) }
                .collect { localMessages ->
                    _uiState.value = ChatDetailUiState.Success(messages = localMessages)
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
                    // Сообщение успешно улетело в Firestore (или сразу в Room, если чат с собой)
                },
                onFailure = {
                    // В будущем тут можно показать Снэкбар "Не удалось отправить"
                }
            )
        }
    }

}