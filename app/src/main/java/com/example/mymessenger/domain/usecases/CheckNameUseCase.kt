package com.example.mymessenger.domain.usecases

import com.example.mymessenger.domain.repository.AuthRepository

class CheckNameUseCase(private val repository: AuthRepository) {
    suspend operator fun invoke(name: String): Result<Boolean> = repository.isNameTaken(name)
}
