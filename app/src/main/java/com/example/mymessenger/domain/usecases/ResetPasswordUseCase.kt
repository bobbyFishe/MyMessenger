package com.example.mymessenger.domain.usecases

import com.example.mymessenger.domain.repository.AuthRepository

class ResetPasswordUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(email: String): Result<Unit> = repository.resetPassword(email)
}