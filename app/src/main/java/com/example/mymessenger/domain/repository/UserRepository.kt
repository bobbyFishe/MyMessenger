package com.example.mymessenger.domain.repository

import com.example.mymessenger.domain.model.User

interface UserRepository {
    suspend fun getCurrentUser(uid: String): Result<User>
    suspend fun saveCurrentUser(user: User): Result<Unit>
}