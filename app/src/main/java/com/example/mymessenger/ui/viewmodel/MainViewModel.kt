package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.local.AppDatabase
import com.example.mymessenger.data.local.entities.ContactEntity
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MainUiState {
    object Loading : MainUiState
    data class Success(val user: com.example.mymessenger.domain.model.User, val chats: List<ChatDocument>) : MainUiState
    data class Error(val messageResId: Int) : MainUiState
}

class MainViewModel(
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth,
    private val chatRepository: ChatRepository,
    private val appDatabase: AppDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _searchError = MutableStateFlow<Int?>(null)
    val searchError: StateFlow<Int?> = _searchError.asStateFlow()

    private val _isChatCreatedSuccessfully = MutableStateFlow(false)
    val isChatCreatedSuccessfully = _isChatCreatedSuccessfully.asStateFlow()

    private val nameCache = mutableMapOf<String, String>()

    private val activeEngineJobs = mutableMapOf<String, Job>()
    private var chatsObservationJob: Job? = null

    init {
        loadCurrentUserData()
    }

    private fun loadCurrentUserData() {
        val uid = firebaseAuth.currentUser?.uid
        android.util.Log.d("MainViewModel", "🔥 1. loadCurrentUserData: uid=$uid")
        if (uid == null) {
            _uiState.value = MainUiState.Error(R.string.error_auth_unknown)
            return
        }

        viewModelScope.launch {
            android.util.Log.d("MainViewModel", "🔥 2. Загружаем пользователя: $uid")
            userRepository.getCurrentUser(uid).fold(
                onSuccess = { userData ->
                    android.util.Log.d("MainViewModel", "✅ 3. Пользователь загружен: ${userData.name}")
                    nameCache[userData.uid] = userData.name
                    _uiState.value = MainUiState.Success(user = userData, chats = emptyList())

                    chatsObservationJob?.cancel()

                    chatsObservationJob = viewModelScope.launch {
                        android.util.Log.d("MainViewModel", "🔥 4. Начинаем observeUserChatsWithCache")
                        userRepository.observeUserChatsWithCache(userData.uid)
                            .catch { exception ->
                                android.util.Log.e("MainViewModel", "❌ Error observing chats", exception)
                                _uiState.value = MainUiState.Error(R.string.error_network_failed)
                            }
                            .collect { chatsList ->
                                android.util.Log.d("MainViewModel", "✅ 5. Получено ${chatsList.size} чатов")
                                chatsList.forEach { chatDoc ->
                                    android.util.Log.d("MainViewModel", "🔥 6. Обрабатываем чат: ${chatDoc.id}")
                                    viewModelScope.launch(Dispatchers.IO) {
                                        userRepository.completeCryptoHandshake(chatDoc)
                                    }
                                }
                                val currentChatIds = chatsList.map { it.id }.toSet()
                                val obsoleteChatIds = activeEngineJobs.keys.filter { it !in currentChatIds }
                                obsoleteChatIds.forEach { id ->
                                    android.util.Log.d("MainViewModel", "🔥 7. Останавливаем engine для $id")
                                    activeEngineJobs[id]?.cancel()
                                    activeEngineJobs.remove(id)
                                }
                                chatsList.forEach { chatDoc ->
                                    if (!activeEngineJobs.containsKey(chatDoc.id)) {
                                        android.util.Log.d("MainViewModel", "🔥 8. Запускаем engine для ${chatDoc.id}")
                                        activeEngineJobs[chatDoc.id] = viewModelScope.launch {
                                            chatRepository.startP2PDeliveryEngine(chatDoc.id)
                                                .catch { e ->
                                                    android.util.Log.e("MainViewModel", "❌ Engine error for ${chatDoc.id}", e)
                                                }
                                                .collect {}
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
                onFailure = { e ->
                    android.util.Log.e("MainViewModel", "❌ Failed to load user", e)
                    _uiState.value = MainUiState.Error(R.string.error_network_failed)
                }
            )
        }
    }

    fun getLastMessageFlow(chatId: String): Flow<LocalMessageEntity?> = chatRepository.getLastLocalMessage(chatId)

    suspend fun getPeerName(peerId: String): String = withContext(Dispatchers.IO) {
        nameCache[peerId]?.let { return@withContext it }

        val cachedContact = userRepository.getCachedContact(peerId)
        if (cachedContact != null) {
            nameCache[peerId] = cachedContact.name
            return@withContext cachedContact.name
        }

        val name = userRepository.getCurrentUser(peerId).getOrNull()?.name ?: "Пользователь"
        nameCache[peerId] = name

        userRepository.saveContact(
            ContactEntity(
                uid = peerId,
                name = name,
                timestamp = System.currentTimeMillis()
            )
        )
        return@withContext name
    }

    fun logout() {
        viewModelScope.launch {
            try {
                val uid = firebaseAuth.currentUser?.uid
                if (uid != null) {
                    val database = FirebaseDatabase.getInstance()
                    database.getReference("users/$uid/status").setValue("offline")

                    // Отправляем уведомление всем чатам
                    userRepository.sendStatusUpdate(uid, "offline")
                }

                firebaseAuth.signOut()
                withContext(Dispatchers.IO) {
                    appDatabase.clearAllTables()
                }
                chatRepository.setActiveChatId(null)
                nameCache.clear()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "❌ Ошибка при выходе из аккаунта", e)
            }
        }
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
                        refreshChats()
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

    override fun onCleared() {
        super.onCleared()
        activeEngineJobs.values.forEach { it.cancel() }
        activeEngineJobs.clear()
        chatsObservationJob?.cancel()
    }

    fun getUnreadCountFlow(chatId: String): Flow<Int> = chatRepository.getUnreadCount(chatId)
}
