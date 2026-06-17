package com.example.mymessenger.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymessenger.R
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.repository.UserRepository
import com.example.mymessenger.domain.usecases.CheckNameUseCase
import com.example.mymessenger.domain.usecases.RegisterWithEmailUseCase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RegisterResultState {
    object Idle : RegisterResultState
    object Loading : RegisterResultState
    object Success : RegisterResultState
    data class Error(val messageResId: Int, val dynamicMessage: String? = null) : RegisterResultState
}

data class RegisterUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val passwordRepeat: String = ""
)
class RegisterViewModel(
    private val registerWithEmailUseCase: RegisterWithEmailUseCase,
    private val checkNameUseCase: CheckNameUseCase,
    private val userRepository: UserRepository
): ViewModel() {
    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    private val _isNameChecking = MutableStateFlow(false)
    val isNameChecking = _isNameChecking.asStateFlow()

    private val _resultState = MutableStateFlow<RegisterResultState>(RegisterResultState.Idle)
    val resultState: StateFlow<RegisterResultState>  = _resultState.asStateFlow()
    private val emailRegex = Constants.EMAIL_REGEX.toRegex()

    val isInputValid: Boolean
        get() {
            val state = _uiState.value
            return emailValidate(state.email) &&
                    passwordValidate(state.password, state.passwordRepeat) &&
                    state.name.isNotBlank()
        }

    val isPasswordTooShort: Boolean
        get() {
            val pass = _uiState.value.password
            return pass.isNotEmpty() && pass.length < Constants.MIN_PASSWORD_LENGTH
        }

    val missingPasswordRequirements: List<String>
        get() = Constants.getMissingPasswordRequirements(_uiState.value.password)


    val doPasswordsMatch: Boolean
        get() = _uiState.value.password == _uiState.value.passwordRepeat

    val isEmailInvalid: Boolean
        get() {
            val email = _uiState.value.email
            return email.isNotEmpty() && !emailValidate(email)
        }

    init {
        generateRandomName()
    }

    fun generateRandomName() {
        viewModelScope.launch {
            _isNameChecking.value = true
            var isTaken = true
            var finalName = ""
            var attempts = 0

            while (isTaken && attempts < Constants.GENERATE_NAME_COUNT) {
                attempts++
                val randomFirstName = Constants.FIRST_NAMES.random()
                val randomSurname = Constants.SURNAMES.random()
                finalName = "${randomFirstName}_${randomSurname}"

                val checkResult = checkNameUseCase(finalName)

                if (checkResult.isFailure) {
                    isTaken = false
                } else {
                    isTaken = checkResult.getOrDefault(false)
                }
            }
            if (finalName.isEmpty()) {
                finalName = "User_${System.currentTimeMillis().toString().takeLast(5)}"
            }

            updateName(finalName)
            _isNameChecking.value = false
        }
    }

    fun updateEmail(newEmail: String) {
        _uiState.update { it.copy(email = newEmail) }
    }

    fun emailValidate(email: String): Boolean {
        return email.isNotBlank() && emailRegex.matches(email.trim())
    }

    fun updatePassword(newPassword: String) {
        _uiState.update { it.copy(password = newPassword) }
    }

    fun passwordValidate(pass: String, passRepeat: String): Boolean {
        val isValidFormat = pass.length >= Constants.MIN_PASSWORD_LENGTH &&
                Constants.getMissingPasswordRequirements(pass).isEmpty()
        return isValidFormat && pass == passRepeat
    }

    fun updatePasswordRepeat(newPasswordRepeat: String) {
        _uiState.update { it.copy(passwordRepeat = newPasswordRepeat) }
    }

    fun updateName(newName: String) {
        _uiState.update { it.copy(name = newName) }
    }

    fun register() {
        if (!isInputValid) return
        _resultState.value = RegisterResultState.Loading
        viewModelScope.launch {
            val state = _uiState.value
            val result = registerWithEmailUseCase(state.name, state.email, state.password)
            result.fold(
                onSuccess = { userId ->
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        userRepository.createEncryptedChat(peerId = userId, peerPublicKey = null)
                        currentUser.sendEmailVerification()
                            .addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    _resultState.value = RegisterResultState.Success
                                } else {
                                    val error = task.exception?.localizedMessage ?: ""
                                    _resultState.value = RegisterResultState.Error(
                                        messageResId = R.string.error_sending_email,
                                        dynamicMessage = error
                                    )
                                }
                            }
                    } else {
                        _resultState.value = RegisterResultState.Error(R.string.session_authorization_error)
                    }
                },
                onFailure = { exception ->
                    val errorResId = when (exception) {
                        is FirebaseAuthUserCollisionException -> R.string.error_email_collision
                        else -> R.string.error_unknown_registration
                    }
                    _resultState.value = RegisterResultState.Error(
                        messageResId = errorResId)
                }
            )
        }
    }
}