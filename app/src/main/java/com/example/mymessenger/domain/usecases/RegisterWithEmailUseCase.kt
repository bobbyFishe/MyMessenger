package com.example.mymessenger.domain.usecases

import com.example.mymessenger.domain.repository.AuthRepository

class RegisterWithEmailUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(name: String, email: String, pass: String): Result<String> {
        return repository.registerWithEmail(name, email, pass)
    }
}