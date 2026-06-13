package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MainUiState {
    object Loading : MainUiState
    data class Success(val user: User) : MainUiState
    data class Error(val messageResId: Int) : MainUiState
}

class MainViewModel(
    private val userRepository: UserRepository,
    private val firebaseAuth: FirebaseAuth
): ViewModel() {
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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
                    _uiState.value = MainUiState.Success(user = userData)
                },
                onFailure = {
                    _uiState.value = MainUiState.Error(R.string.error_network_failed)
                }
            )
        }
    }

    fun logout() {
        firebaseAuth.signOut()
    }
}