package com.example.mymessenger.data.repository

import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserRepositoryImpl(
    private val firestore: FirebaseFirestore
) : UserRepository {
    override suspend fun getCurrentUser(uid: String): Result<User> {
        return try {
            val document = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                .document(uid)
                .get()
                .await()
            val user = document.toObject(User::class.java)
            if (user != null) {
                Result.success(user)
            } else {
                Result.failure(Exception("USER_NOT_FOUND_IN_FIRESTORE"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveCurrentUser(user: User): Result<Unit> {
        return try {
            firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                .document(user.uid)
                .set(user)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}