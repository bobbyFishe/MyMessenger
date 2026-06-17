package com.example.mymessenger.data.repository

import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.KeyPairGenerator
import android.util.Base64
import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.ContactDao
import com.example.mymessenger.data.local.entities.ChatKeyEntity
import com.example.mymessenger.data.local.entities.ContactEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext


class UserRepositoryImpl(
    private val firestore: FirebaseFirestore,
    private val chatKeyDao: ChatKeyDao,
    private val contactDao: ContactDao
) : UserRepository {
    override suspend fun getCurrentUser(uid: String): Result<User> {
        return withContext(Dispatchers.IO) {
            try {
                val localContact = contactDao.getContactByUid(uid)
                if (localContact != null) {
                    return@withContext Result.success(
                        User(uid = localContact.uid, name = localContact.name)
                    )
                }
                val snapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                    .document(uid)
                    .get()
                    .await()

                val remoteUser = snapshot.toObject(User::class.java)
                if (remoteUser != null) {
                    contactDao.saveContact(
                        ContactEntity(
                            uid = remoteUser.uid,
                            name = remoteUser.name,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    Result.success(remoteUser)
                } else {
                    Result.failure(Exception("USER_NOT_FOUND_IN_FIRESTORE"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
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

    override suspend fun searchUserByName(lowercaseName: String): Result<User> {
        return try {
            val snapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                .whereEqualTo("nameLowercase", lowercaseName.trim())
                .get()
                .await()
            val document = snapshot.documents.firstOrNull()
            val user = document?.toObject(User::class.java)

            if(user != null) Result.success(user)
            else Result.failure(Exception("CONTACT_NOT_FOUND"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createEncryptedChat(
        peerId: String,
        peerPublicKey: String?
    ): Result<Unit> {
        return try {
            val myId = FirebaseAuth.getInstance().currentUser?.uid ?:
            return Result.failure(Exception("Сессия не найдена"))

            val idList = listOf(myId, peerId).sorted()
            val chatId = idList.joinToString("_")

//            val chatSnapshot = firestore.collection("chats")
//                .document(chatId)
//                .get()
//                .await()

            val hasLocalKey = chatKeyDao.getKeyForChat(chatId) != null

//            if (chatSnapshot.exists() && hasLocalKey) {
//                return Result.success(Unit)
//            }

            if (hasLocalKey) {
                // Если ключ на телефоне уже сгенерирован ранее — просто выходим
                return Result.success(Unit)
            }

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val myPublicKeyString = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val myPrivateKeyString = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)

            chatKeyDao.saveKey(
                ChatKeyEntity(
                    chatId = chatId,
                    privateKey = myPrivateKeyString
                )
            )

            val chatDoc = ChatDocument(
                id = chatId,
                participantIds = idList,

                publicKeyUserA = if (myId == idList[0]) myPublicKeyString else "",
                publicKeyUserB = if (myId == idList[1]) myPublicKeyString else "",
                createdAt = System.currentTimeMillis()
            )

            firestore.collection("chats")
                .document(chatId)
                .set(chatDoc, com.google.firebase.firestore.SetOptions.merge())
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("CRYPTO_CHAT_ERR", "Ошибка в createEncryptedChat: ", e)
            Result.failure(e)
        }
    }

    override fun observeUserChats(currentUid: String): Flow<List<ChatDocument>> = callbackFlow {
        val listener = firestore.collection("chats")
            .whereArrayContains("participantIds", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val chatsList = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(ChatDocument::class.java)
                    }
                    trySend(chatsList)
                }
            }

        awaitClose { listener.remove() }
    }


    override suspend fun completeCryptoHandshake(chatDoc: ChatDocument): Result<Unit> {
        return try {
            val myId = FirebaseAuth.getInstance().currentUser?.uid
                ?: return Result.failure(Exception("SESSION_NOT_FOUND"))

            val isUserA = myId == chatDoc.participantIds[0]
            val isMyKeyEmpty = if (isUserA) chatDoc.publicKeyUserA.isEmpty() else chatDoc.publicKeyUserB.isEmpty()

            if (!isMyKeyEmpty) {
                return Result.success(Unit)
            }

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val myPublicKeyString = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val myPrivateKeyString = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)

            chatKeyDao.saveKey(
                ChatKeyEntity(
                    chatId = chatDoc.id,
                    privateKey = myPrivateKeyString
                )
            )

            val updateMap = if (isUserA) {
                mapOf("publicKeyUserA" to myPublicKeyString)
            } else {
                mapOf("publicKeyUserB" to myPublicKeyString)
            }

            firestore.collection("chats")
                .document(chatDoc.id)
                .update(updateMap)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setUserOnlineStatus(uid: String, isOnline: Boolean): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                    .document(uid)
                    .update("isOnline", isOnline)
                    .await()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

}