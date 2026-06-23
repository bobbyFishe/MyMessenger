package com.example.mymessenger.data.repository

import com.example.mymessenger.data.local.dao.ContactDao
import com.example.mymessenger.data.local.entities.ContactEntity
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebaseAuthRepositoryImpl(
    private val contactDao: ContactDao
) : AuthRepository {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override suspend fun registerWithEmail(name: String, email: String, pass: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val authResult = firebaseAuth.createUserWithEmailAndPassword(email, pass).await()
                val userId = authResult.user?.uid ?: throw Exception("ID пользователя не найден")
                val userMap = mapOf(
                    "uid" to userId,
                    "name" to name,
                    "nameLowercase" to name.lowercase(),
                    "email" to email,
                    "createdAt" to System.currentTimeMillis()
                )
                firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                    .document(userId)
                    .set(userMap)
                    .await()
                contactDao.saveContact(
                    ContactEntity(
                        uid = userId,
                        name = name,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Result.success(userId)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun loginWithEmail(email: String, pass: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val authResult = firebaseAuth.signInWithEmailAndPassword(email, pass).await()
                val user = authResult.user

                if (user != null && user.isEmailVerified) {
                    val snapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                        .document(user.uid)
                        .get()
                        .await()

                    val name = snapshot.getString("name") ?: "User"
                    contactDao.saveContact(
                        ContactEntity(
                            uid = user.uid,
                            name = name,
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    Result.success(user.uid)
                } else {
                    firebaseAuth.signOut()
                    Result.failure(Exception("EMAIL_NOT_VERIFIED"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun isNameTaken(name: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val querySnapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                .whereEqualTo("name", name)
                .limit(1)
                .get()
                .await()
            Result.success(!querySnapshot.isEmpty)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetPassword(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
