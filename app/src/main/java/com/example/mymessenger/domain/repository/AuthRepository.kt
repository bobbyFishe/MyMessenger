package com.example.mymessenger.domain.repository

interface AuthRepository {
    suspend fun registerWithEmail(name: String, email: String, pass: String): Result<String>
    suspend fun isNameTaken(name: String): Result<Boolean>
    suspend fun resetPassword(email: String): Result<Unit>
    suspend fun loginWithEmail(email: String, pass: String): Result<String>
}