package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.ContactEntity
import com.example.mymessenger.domain.model.ChatDocument
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
    private val nameCache = mutableMapOf<String, String>()

    init {
        loadCurrentUserData()
    }
    // ui/viewmodel/MainViewModel.kt
    private fun loadCurrentUserData() {
        val uid = firebaseAuth.currentUser?.uid
        if (uid == null) {
            _uiState.value = MainUiState.Error(R.string.error_auth_unknown)
            return
        }

        viewModelScope.launch {
            userRepository.getCurrentUser(uid).fold(
                onSuccess = { userData ->
                    nameCache[userData.uid] = userData.name
                    _uiState.value = MainUiState.Success(user = userData, chats = emptyList())

                    viewModelScope.launch {
                        userRepository.observeUserChatsWithCache(userData.uid)
                            .catch { exception ->
                                android.util.Log.e("MainViewModel", "❌ Error observing chats", exception)
                                _uiState.value = MainUiState.Error(R.string.error_network_failed)
                            }
                            .collect { chatsList ->
                                android.util.Log.d("MainViewModel", "📋 Chats updated: ${chatsList.size}")

                                // ✅ Загружаем имена всех собеседников за 1 запрос
                                val peerIds = chatsList.mapNotNull { chatDoc ->
                                    chatDoc.participantIds.firstOrNull { it != userData.uid }
                                }.distinct()

                                if (peerIds.isNotEmpty()) {
                                    viewModelScope.launch {
                                        val result = userRepository.getUsersByIds(peerIds)
                                        result.onSuccess { users ->
                                            // ✅ Сохраняем в nameCache
                                            users.forEach { user ->
                                                nameCache[user.uid] = user.name
                                            }
                                            android.util.Log.d("MainViewModel", "✅ Loaded ${users.size} peer names in 1 query")
                                        }
                                    }
                                }

                                chatsList.forEach { chatDoc ->
                                    userRepository.completeCryptoHandshake(chatDoc)

                                    val uids = chatDoc.id.split("_")
                                    val peerId = uids.firstOrNull { it != userData.uid }
                                    if (peerId != null && !nameCache.containsKey(peerId)) {
                                        viewModelScope.launch {
                                            val peerName = userRepository.getCurrentUser(peerId)
                                                .getOrNull()?.name ?: "Пользователь"
                                            nameCache[peerId] = peerName
                                        }
                                    }
                                }

                                // Запускаем engine для новых чатов
                                val currentChatIds = (uiState.value as? MainUiState.Success)?.chats?.map { it.id } ?: emptyList()
                                chatsList.forEach { chatDoc ->
                                    if (!currentChatIds.contains(chatDoc.id)) {
                                        viewModelScope.launch {
                                            android.util.Log.d("MainViewModel", "🚀 Starting P2P engine for: ${chatDoc.id}")
                                            chatRepository.startP2PDeliveryEngine(chatDoc.id)
                                                .catch { e ->
                                                    android.util.Log.e("MainViewModel", "❌ Engine error for ${chatDoc.id}", e)
                                                }
                                                .collect {
                                                    android.util.Log.d("MainViewModel", "✅ Engine emitted for ${chatDoc.id}")
                                                }
                                        }
                                    }
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

    // ui/viewmodel/MainViewModel.kt
    suspend fun getPeerName(peerId: String): String {
        // 1️⃣ Кеш памяти
        nameCache[peerId]?.let { return it }

        // 2️⃣ Room (0 чтений)
        val cachedContact = userRepository.getCachedContact(peerId)
        if (cachedContact != null) {
            nameCache[peerId] = cachedContact.name
            return cachedContact.name
        }

        // 3️⃣ Firestore (1 чтение)
        val name = userRepository.getCurrentUser(peerId).getOrNull()?.name ?: "Пользователь"
        nameCache[peerId] = name

        // Сохраняем в Room
        viewModelScope.launch {
            userRepository.saveContact(
                ContactEntity(
                    uid = peerId,
                    name = name,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        return name
    }

    fun logout() {
        firebaseAuth.signOut()
    }

    fun startChatWithUser(inputName: String) {
        _searchError.value = null
        _isChatCreatedSuccessfully.value = false
        val currentState = _uiState.value

        android.util.Log.d("MainViewModel", "🔍 startChatWithUser: inputName=$inputName")


        if (currentState is MainUiState.Success) {
            if (inputName.trim().equals(currentState.user.name, ignoreCase = true)) {

                android.util.Log.d("MainViewModel", "❌ Cannot search self")

                _searchError.value = R.string.cannot_search_self
                return
            }
        }
        viewModelScope.launch {

            android.util.Log.d("MainViewModel", "🔍 Searching for user: $inputName")

            userRepository.searchUserByName(inputName.lowercase()).fold(
                onSuccess = { foundUser ->

                    android.util.Log.d("MainViewModel", "✅ User found: ${foundUser.uid}, name: ${foundUser.name}")

                    val chatResult = userRepository.createEncryptedChat(
                        peerId = foundUser.uid,
                        peerPublicKey = null
                    )

                    android.util.Log.d("MainViewModel", "📝 Chat creation result: ${chatResult.isSuccess}")

                    if (chatResult.isSuccess) {
                        _isChatCreatedSuccessfully.value = true
                        refreshChats()
                    } else {
                        _searchError.value = R.string.error_network_failed
                    }
                },
                onFailure = { exception ->

                    android.util.Log.e("MainViewModel", "❌ Search failed: ${exception.message}")

                    if (exception.message == "CONTACT_NOT_FOUND" || exception.message == "USER_NOT_FOUND_IN_FIRESTORE") {
                        _searchError.value = R.string.contact_not_found
                    } else {
                        _searchError.value = R.string.error_network_failed
                    }
                }
            )
        }
    }

    fun refreshChats() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        viewModelScope.launch {
            userRepository.refreshChatsCache(uid)
        }
    }

    fun resetSearchState() {
        _searchError.value = null
        _isChatCreatedSuccessfully.value = false
    }
}