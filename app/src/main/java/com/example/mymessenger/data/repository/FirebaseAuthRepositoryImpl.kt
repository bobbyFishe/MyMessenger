package com.example.mymessenger.data.repository

import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

class FirebaseAuthRepositoryImpl : AuthRepository {
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    override suspend fun registerWithEmail(name: String, email: String, pass: String): Result<String> =
        suspendCancellableCoroutine { continuation ->
            firebaseAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { authResult ->
                    val userId = authResult.user?.uid ?: ""
                    val userMap = mapOf(
                        "uid" to userId,
                        "name" to name,
                        "email" to email,
                        "createdAt" to System.currentTimeMillis()
                    )

                    firestore.collection(Constants.FIRESTORE_USERS_COLLECTION).document(userId)
                        .set(userMap)
                        .addOnSuccessListener {
                            continuation.resume(Result.success(userId))
                        }
                        .addOnFailureListener { firestoreException ->
                            continuation.resume(Result.failure(firestoreException))
                        }
                }
                .addOnFailureListener { authException ->
                    continuation.resume(Result.failure(authException))
                }
        }

    override suspend fun isNameTaken(name: String): Result<Boolean> = try {
        val querySnapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
            .whereEqualTo("name", name)
            .limit(1)
            .get()
            .await()

        Result.success(!querySnapshot.isEmpty)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun resetPassword(email: String): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            firebaseAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { exception ->
                    continuation.resume(Result.failure(exception))
                }
        }

    override suspend fun loginWithEmail(email: String, pass: String): Result<String> =
        suspendCancellableCoroutine { continuation ->
            firebaseAuth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener { authResult ->
                    val user = authResult.user
                    if (user != null && user.isEmailVerified) {
                        continuation.resume(Result.success(user.uid))
                    } else {
                        firebaseAuth.signOut()
                        continuation.resume(Result.failure(Exception("EMAIL_NOT_VERIFIED")))
                    }
                }
                .addOnFailureListener { exception ->
                    continuation.resume(Result.failure(exception))
                }
        }

}