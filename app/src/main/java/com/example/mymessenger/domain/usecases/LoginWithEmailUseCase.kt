package com.example.mymessenger.domain.usecases

import com.example.mymessenger.domain.repository.AuthRepository

class LoginWithEmailUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String, pass: String): Result<String> {
        return repository.loginWithEmail(email, pass)
    }
}