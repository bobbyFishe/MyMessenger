package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.repository.ChatRepository
import com.example.mymessenger.domain.usecases.LoginWithEmailUseCase
import com.example.mymessenger.domain.usecases.ResetPasswordUseCase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface LoginResultState {
    object Idle : LoginResultState
    object Loading : LoginResultState
    object Success : LoginResultState
    object ResetEmailSent : LoginResultState
    data class Error(val messageResId: Int, val dynamicMessage: String? = null) : LoginResultState
}

data class LoginUiState(
    val email: String = "",
    val password: String = ""
)

class LoginViewModel(
    private val resetPasswordUseCase: ResetPasswordUseCase,
    private val loginWithEmailUseCase: LoginWithEmailUseCase,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _resultState = MutableStateFlow<LoginResultState>(LoginResultState.Idle)
    val resultState: StateFlow<LoginResultState> = _resultState.asStateFlow()

    val missingPasswordRequirements: List<String>
        get() = Constants.getMissingPasswordRequirements(_uiState.value.password)


    val isPasswordTooShort: Boolean
        get() {
            val pass = _uiState.value.password
            return pass.isNotEmpty() && pass.length < Constants.MIN_PASSWORD_LENGTH
        }

    val isEmailInvalid: Boolean
        get() {
            val email = _uiState.value.email
            return email.isNotEmpty() && !emailValidate(email)
        }

    fun emailValidate(email: String): Boolean {
        return email.isNotBlank() && Constants.EMAIL_REGEX.toRegex().matches(email.trim())
    }

    val isLoginInputValid: Boolean
        get() {
            val state = _uiState.value
            val isPasswordValid = state.password.length >= Constants.MIN_PASSWORD_LENGTH &&
                    Constants.getMissingPasswordRequirements(state.password).isEmpty()

            return emailValidate(state.email) && isPasswordValid
        }

    fun updateEmail(newEmail: String) {
        _uiState.update { it.copy(email = newEmail) }
    }

    fun updatePassword(newPassword: String) {
        _uiState.update { it.copy(password = newPassword) }
    }

    fun resetPassword() {
        val email = _uiState.value.email

        if (email.isBlank() || !Constants.EMAIL_REGEX.toRegex().matches(email.trim())) {
            _resultState.value = LoginResultState.Error(R.string.enter_valid_email_to_reset_your_password)
            return
        }

        _resultState.value = LoginResultState.Loading

        viewModelScope.launch {
            val result = resetPasswordUseCase(email.trim())
            result.fold(
                onSuccess = {
                    _resultState.value = LoginResultState.ResetEmailSent
                },
                onFailure = { exception ->
                    val errorResId = mapFirebaseExceptionToString(exception)
                    _resultState.value = LoginResultState.Error(messageResId = errorResId)
                }
            )
        }
    }

    fun login() {
        if (!isLoginInputValid) return

        _resultState.value = LoginResultState.Loading

        viewModelScope.launch {
            val state = _uiState.value
            val result = loginWithEmailUseCase(state.email.trim(), state.password)
            result.fold(
                onSuccess = { _ ->
                    setupUserOnlineStatus()
                    refreshAllChatKeys()
                    _resultState.value = LoginResultState.Success
                },
                onFailure = { exception ->
                    val errorResId = mapFirebaseExceptionToString(exception)
                    _resultState.value = LoginResultState.Error(errorResId)
                }
            )
        }
    }

    private suspend fun refreshAllChatKeys() {
        try {
            val myId = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val chatsSnapshot = FirebaseFirestore.getInstance()
                .collection("chats")
                .whereArrayContains("participantIds", myId)
                .get()
                .await()

            for (doc in chatsSnapshot.documents) {
                val chatId = doc.id
                chatRepository.regenerateKeysIfMissing(chatId)
            }
        } catch (e: Exception) {
            android.util.Log.e("LoginViewModel", "❌ Ошибка обновления ключей", e)
        }
    }

    private fun setupUserOnlineStatus() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val statusRef = FirebaseDatabase.getInstance().reference.child("users/$uid/status")
        statusRef.setValue("online")
    }
}



private fun mapFirebaseExceptionToString(exception: Throwable): Int {
    return when (exception) {
        is FirebaseAuthInvalidCredentialsException -> {
            R.string.error_invalid_credentials
        }
        is FirebaseAuthInvalidUserException -> {
            R.string.error_user_not_found
        }
        is FirebaseAuthException -> {
            android.util.Log.d("FIREBASE_AUTH", "Код общей ошибки: ${exception.errorCode}")

            when (exception.errorCode) {
                "ERROR_USER_DISABLED" -> R.string.error_user_disabled
                "ERROR_TOO_MANY_REQUESTS" -> R.string.error_too_many_requests
                "INVALID_LOGIN_CREDENTIALS" -> R.string.error_invalid_credentials
                else -> R.string.error_auth_unknown
            }
        }
        else -> {
            if (exception.message == "EMAIL_NOT_VERIFIED") {
                R.string.error_email_not_verified
            } else {
                R.string.error_network_failed
            }
        }
    }
}
