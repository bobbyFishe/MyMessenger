package com.example.mymessenger.data.repository

import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.domain.repository.UserRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.KeyPairGenerator
import android.util.Base64
import com.example.mymessenger.data.local.dao.ChatDao
import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.ContactDao
import com.example.mymessenger.data.local.entities.ChatEntity
import com.example.mymessenger.data.local.entities.ChatKeyEntity
import com.example.mymessenger.data.local.entities.ContactEntity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class UserRepositoryImpl(
    private val firestore: FirebaseFirestore,
    private val chatKeyDao: ChatKeyDao,
    private val contactDao: ContactDao,
    private val chatDao: ChatDao
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
            android.util.Log.d("UserRepository", "🔐 createEncryptedChat START")
            android.util.Log.d("UserRepository", "🔐 peerId=$peerId")

            val myId = FirebaseAuth.getInstance().currentUser?.uid
            android.util.Log.d("UserRepository", "🔐 myId=$myId")

            if (myId == null) {
                android.util.Log.e("UserRepository", "❌ No session")
                return Result.failure(Exception("Сессия не найдена"))
            }

            val idList = listOf(myId, peerId).sorted()
            val chatId = idList.joinToString("_")
            android.util.Log.d("UserRepository", "🔐 chatId=$chatId")

            // ✅ Проверяем чат в Firestore
            android.util.Log.d("UserRepository", "🔐 Checking chat in Firestore...")
            val chatSnapshot = firestore.collection("chats")
                .document(chatId)
                .get()
                .await()
            android.util.Log.d("UserRepository", "🔐 chatExists=${chatSnapshot.exists()}")

            val chatExists = chatSnapshot.exists()
            val hasLocalKey = chatKeyDao.getKeyForChat(chatId) != null
            android.util.Log.d("UserRepository", "🔐 hasLocalKey=$hasLocalKey")

            if (chatExists && hasLocalKey) {
                android.util.Log.d("UserRepository", "⏭️ Chat already exists")
                return Result.success(Unit)
            }

            // ✅ Генерируем ключи
            android.util.Log.d("UserRepository", "🔑 Generating RSA keys...")
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val myPublicKeyString = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val myPrivateKeyString = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            android.util.Log.d("UserRepository", "🔑 Keys generated")

            android.util.Log.d("UserRepository", "💾 Saving private key to Room...")
            chatKeyDao.saveKey(
                ChatKeyEntity(
                    chatId = chatId,
                    privateKey = myPrivateKeyString
                )
            )
            android.util.Log.d("UserRepository", "✅ Private key saved")

            val isUserA = myId == idList[0]
            android.util.Log.d("UserRepository", "🔐 isUserA=$isUserA")

            val chatDoc = ChatDocument(
                id = chatId,
                participantIds = idList,
                publicKeyUserA = if (isUserA) myPublicKeyString else (peerPublicKey ?: ""),
                publicKeyUserB = if (!isUserA) myPublicKeyString else (peerPublicKey ?: ""),
                createdAt = System.currentTimeMillis()
            )

            android.util.Log.d("UserRepository", "📤 Saving chat to Firestore: participantIds=${chatDoc.participantIds}")
            firestore.collection("chats")
                .document(chatId)
                .set(chatDoc)
                .await()
            android.util.Log.d("UserRepository", "✅ Chat created: $chatId")

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "❌ Error in createEncryptedChat: ", e)
            Result.failure(e)
        }
    }

    override fun observeUserChatsWithCache(currentUid: String): Flow<List<ChatDocument>> = callbackFlow {
        android.util.Log.d("UserRepository", "📡 observeUserChatsWithCache: $currentUid")

        // ✅ Сначала подписываемся на Firestore
        val listener = firestore.collection("chats")
            .whereArrayContains("participantIds", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("UserRepository", "❌ Error: ${error.message}")
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    android.util.Log.d("UserRepository", "📥 Received ${snapshot.documents.size} chats from Firestore")

                    val chatDocs = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(ChatDocument::class.java)
                    }

                    // ✅ Сохраняем в кеш
                    launch(Dispatchers.IO) {
                        try {
                            chatDao.clearAll()
                            val entities = chatDocs.map { doc ->
                                ChatEntity(
                                    id = doc.id,
                                    participantIds = doc.participantIds,
                                    publicKeyUserA = doc.publicKeyUserA,
                                    publicKeyUserB = doc.publicKeyUserB,
                                    createdAt = doc.createdAt,
                                    lastUpdated = System.currentTimeMillis()
                                )
                            }
                            chatDao.saveChats(entities)
                            android.util.Log.d("UserRepository", "💾 Saved ${entities.size} chats to cache")
                        } catch (e: Exception) {
                            android.util.Log.e("UserRepository", "❌ Error saving to cache", e)
                        }
                    }

                    // ✅ Отправляем в UI
                    trySend(chatDocs)
                }
            }

        // ✅ Потом отправляем кеш из Room (если есть)
        launch(Dispatchers.IO) {
            chatDao.getChats().collect { chatEntities ->
                if (chatEntities.isNotEmpty()) {
                    android.util.Log.d("UserRepository", "📦 Sending ${chatEntities.size} chats from cache")
                    val chatDocs = chatEntities.map { entity ->
                        ChatDocument(
                            id = entity.id,
                            participantIds = entity.participantIds,
                            publicKeyUserA = entity.publicKeyUserA,
                            publicKeyUserB = entity.publicKeyUserB,
                            createdAt = entity.createdAt
                        )
                    }
                    trySend(chatDocs)
                }
            }
        }

        awaitClose {
            android.util.Log.d("UserRepository", "🛑 Stopped observing chats")
            listener.remove()
        }
    }

    override suspend fun refreshChatsCache(currentUid: String) {
        withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("UserRepository", "🔄 Refreshing chats cache")

                val snapshot = firestore.collection("chats")
                    .whereArrayContains("participantIds", currentUid)
                    .get()
                    .await()

                val chatDocs = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChatDocument::class.java)
                }

                chatDao.clearAll()
                val entities = chatDocs.map { doc ->
                    ChatEntity(
                        id = doc.id,
                        participantIds = doc.participantIds,
                        publicKeyUserA = doc.publicKeyUserA,
                        publicKeyUserB = doc.publicKeyUserB,
                        createdAt = doc.createdAt,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                chatDao.saveChats(entities)
                android.util.Log.d("UserRepository", "✅ Cache refreshed: ${entities.size} chats")
            } catch (e: Exception) {
                android.util.Log.e("UserRepository", "❌ Error refreshing cache", e)
            }
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

    override suspend fun getCachedContact(uid: String): ContactEntity? {
        return withContext(Dispatchers.IO) {
            contactDao.getContactByUid(uid)
        }
    }

    // data/repository/UserRepositoryImpl.kt
    override suspend fun getUsersByIds(userIds: List<String>): Result<List<User>> {
        return withContext(Dispatchers.IO) {
            try {
                // ✅ Загружаем ВСЕХ пользователей за 1 запрос!
                val snapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                    .whereIn("uid", userIds)  // ← 1 запрос для всех UID!
                    .get()
                    .await()

                val users = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(User::class.java)
                }

                // ✅ Сохраняем в Room (кеш) — nameCache здесь не используем
                users.forEach { user ->
                    contactDao.saveContact(
                        ContactEntity(
                            uid = user.uid,
                            name = user.name,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                Result.success(users)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun saveContact(contact: ContactEntity): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                contactDao.saveContact(contact)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun getCachedPeerPublicKey(chatId: String, myId: String): String? {
        return withContext(Dispatchers.IO) {
            val chat = chatDao.getChatById(chatId) ?: return@withContext null
            val uids = chatId.split("_")
            val isUserA = myId == uids[0]
            if (isUserA) chat.publicKeyUserB else chat.publicKeyUserA
        }
    }

}