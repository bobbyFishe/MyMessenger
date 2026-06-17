package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.util.CoilUtils.result
import com.example.mymessenger.R
import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.model.ChatUiModel
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface MainUiState {
    object Loading : MainUiState
    data class Success(
        val user: User,
        val chats: List<ChatDocument>
    ) : MainUiState

    data class Error(val messageResId: Int) : MainUiState
}

class MainViewModel(
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth,
    private val chatRepository: ChatRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _searchError = MutableStateFlow<Int?>(null)
    val searchError: StateFlow<Int?> = _searchError.asStateFlow()

    private val _isChatCreatedSuccessfully = MutableStateFlow(false)
    val isChatCreatedSuccessfully = _isChatCreatedSuccessfully.asStateFlow()

    init {
        loadCurrentUserData()
    }

    private fun loadCurrentUserData() {
        val uid = firebaseAuth.currentUser?.uid
        if (uid == null) {
            _uiState.value = MainUiState.Error(R.string.error_auth_unknown)
            return
        }

        viewModelScope.launch {
            userRepository.getCurrentUser(uid).fold(
                onSuccess = { userData ->
                    _uiState.value = MainUiState.Success(user = userData, chats = emptyList())

                    viewModelScope.launch {
                        userRepository.observeUserChats(userData.uid)
                            .catch { exception ->
                                android.util.Log.e("FIRESTORE_ERROR", "Ошибка подписки на чаты: ${exception.message}")
                                _uiState.value = MainUiState.Error(R.string.error_network_failed)
                            }
                            .collect { chatsList ->

                            chatsList.forEach { chatDoc ->
                                userRepository.completeCryptoHandshake(chatDoc)
                            }

                            _uiState.update { currentState ->
                                if (currentState is MainUiState.Success) {
                                    currentState.copy(chats = chatsList)
                                } else {
                                    currentState
                                }
                            }
                        }
                    }
                },
                onFailure = {
                    _uiState.value = MainUiState.Error(R.string.error_network_failed)
                }
            )
        }
    }

    fun getLastMessageFlow(chatId: String) = chatRepository.getLastLocalMessage(chatId)

    suspend fun getPeerName(peerId: String): String {
        return userRepository.getCurrentUser(peerId).getOrNull()?.name ?: "Пользователь"
    }


    fun logout() {
        firebaseAuth.signOut()
    }

    fun startChatWithUser(inputName: String) {
        _searchError.value = null
        _isChatCreatedSuccessfully.value = false
        val currentState = _uiState.value
        if (currentState is MainUiState.Success) {
            if (inputName.trim().equals(currentState.user.name, ignoreCase = true)) {
                _searchError.value = R.string.cannot_search_self
                return
            }
        }
        viewModelScope.launch {
            userRepository.searchUserByName(inputName.lowercase()).fold(
                onSuccess = { foundUser ->
                    val chatResult = userRepository.createEncryptedChat(
                        peerId = foundUser.uid,
                        peerPublicKey = null
                    )
                    if (chatResult.isSuccess) {
                        _isChatCreatedSuccessfully.value = true
                    } else {
                        _searchError.value = R.string.error_network_failed
                    }
                },
                onFailure = { exception ->
                    if (exception.message == "CONTACT_NOT_FOUND" || exception.message == "USER_NOT_FOUND_IN_FIRESTORE") {
                        _searchError.value = R.string.contact_not_found
                    } else {
                        _searchError.value = R.string.error_network_failed
                    }
                }
            )
        }
    }

    fun resetSearchState() {
        _searchError.value = null
        _isChatCreatedSuccessfully.value = false
    }
}