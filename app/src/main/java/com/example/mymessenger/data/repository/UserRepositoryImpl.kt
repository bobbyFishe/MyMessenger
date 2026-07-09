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
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class UserRepositoryImpl(
    private val firestore: FirebaseFirestore,
    private val chatKeyDao: ChatKeyDao,
    private val chatDao: ChatDao,
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
            contactDao.saveContact(
                ContactEntity(
                    uid = user.uid,
                    name = user.name,
                    timestamp = System.currentTimeMillis()
                )
            )
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

            if(user != null) {
                contactDao.saveContact(
                    ContactEntity(
                        uid = user.uid,
                        name = user.name,
                        timestamp = System.currentTimeMillis()
                    )
                )
                Result.success(user)
            }
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
            val myId = FirebaseAuth.getInstance().currentUser?.uid
                ?: return Result.failure(Exception("Сессия не найдена"))

            val isSelfChat = myId == peerId
            val idList = if (isSelfChat) listOf(myId, myId) else listOf(myId, peerId).sorted()
            val chatId = idList.joinToString("_")

            val hasLocalKey = chatKeyDao.getKeyForChat(chatId) != null
            val hasLocalChat = chatDao.getChatById(chatId) != null

            if (hasLocalKey && hasLocalChat) {
                return Result.success(Unit)
            }
            if (!isSelfChat) {
                val chatSnapshot = firestore.collection("chats")
                    .document(chatId)
                    .get()
                    .await()

                if (chatSnapshot.exists()) {
                    val chatDoc = chatSnapshot.toObject(ChatDocument::class.java)
                    chatDoc?.let {
                        chatDao.saveChat(
                            ChatEntity(
                                id = it.id,
                                participantIds = it.participantIds,
                                publicKeyUserA = it.publicKeyUserA,
                                publicKeyUserB = it.publicKeyUserB,
                                createdAt = it.createdAt
                            )
                        )
                    }
                    return Result.success(Unit)
                }
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
            val chatDoc = if (isSelfChat) {
                ChatDocument(
                    id = chatId,
                    participantIds = listOf(myId, myId),
                    publicKeyUserA = myPublicKeyString,
                    publicKeyUserB = myPublicKeyString,
                    createdAt = System.currentTimeMillis()
                )
            } else {
                val isUserA = myId == idList[0]
                ChatDocument(
                    id = chatId,
                    participantIds = idList,
                    publicKeyUserA = if (isUserA) myPublicKeyString else (peerPublicKey ?: ""),
                    publicKeyUserB = if (!isUserA) myPublicKeyString else (peerPublicKey ?: ""),
                    createdAt = System.currentTimeMillis()
                )
            }
            chatDao.saveChat(
                ChatEntity(
                    id = chatDoc.id,
                    participantIds = chatDoc.participantIds,
                    publicKeyUserA = chatDoc.publicKeyUserA,
                    publicKeyUserB = chatDoc.publicKeyUserB,
                    createdAt = chatDoc.createdAt
                )
            )
            firestore.collection("chats")
                .document(chatId)
                .set(chatDoc)
                .await()

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("UserRepository", "❌ Error in createEncryptedChat: ", e)
            Result.failure(e)
        }
    }


    override fun observeUserChatsWithCache(currentUid: String): Flow<List<ChatDocument>> = callbackFlow {
        val listener = firestore.collection("chats")
            .whereArrayContains("participantIds", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val chatDocs = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChatDocument::class.java)
                }
                launch(Dispatchers.IO) {
                    try {
                        val entities = chatDocs.map { doc ->
                            ChatEntity(
                                id = doc.id,
                                participantIds = doc.participantIds,
                                publicKeyUserA = doc.publicKeyUserA,
                                publicKeyUserB = doc.publicKeyUserB,
                                createdAt = doc.createdAt
                            )
                        }
                        chatDao.saveChats(entities)
                    } catch (e: Exception) {
                        android.util.Log.e("UserRepository", "❌ Error syncing chats to Room", e)
                    }
                }
            }
        val cacheJob = launch(Dispatchers.IO) {
            chatDao.getChats().collect { chatEntities ->
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

        awaitClose {
            listener.remove()
            cacheJob.cancel()
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

    override suspend fun completeCryptoHandshake(chatDoc: ChatDocument): Result<Unit> {
        return try {
            val myId = FirebaseAuth.getInstance().currentUser?.uid
                ?: return Result.failure(Exception("SESSION_NOT_FOUND"))

            val isUserA = myId == chatDoc.participantIds.getOrNull(0)
            val myKey = if (isUserA) chatDoc.publicKeyUserA else chatDoc.publicKeyUserB

            if (myKey.isNotEmpty()) {
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
            android.util.Log.e("UserRepository", "❌ completeCryptoHandshake error", e)
            Result.failure(e)
        }
    }

    override suspend fun getCachedContact(uid: String): ContactEntity? {
        return withContext(Dispatchers.IO) {
            contactDao.getContactByUid(uid)
        }
    }

    override suspend fun getUsersByIds(userIds: List<String>): Result<List<User>> {
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = firestore.collection(Constants.FIRESTORE_USERS_COLLECTION)
                    .whereIn("uid", userIds)
                    .get()
                    .await()

                val users = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(User::class.java)
                }

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
                android.util.Log.e("UserRepository", "❌ Failed to fetch users by IDs", e)
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
                android.util.Log.e("UserRepository", "❌ Failed to save contact locally", e)
                Result.failure(e)
            }
        }
    }

    override suspend fun getCachedPeerPublicKey(chatId: String, myId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val chat = chatDao.getChatById(chatId) ?: return@withContext null
                val uids = chatId.split("_")
                val isUserA = myId == uids.getOrNull(0)
                if (isUserA) chat.publicKeyUserB else chat.publicKeyUserA
            } catch (e: Exception) {
                android.util.Log.e("UserRepository", "❌ Error getting cached peer public key", e)
                null
            }
        }
    }

    override suspend fun sendStatusUpdate(userId: String, status: String) {
        withContext(Dispatchers.IO) {
            try {
                val chatsSnapshot = firestore.collection("chats")
                    .whereArrayContains("participantIds", userId)
                    .get()
                    .await()

                val rtdb = FirebaseDatabase.getInstance().reference

                for (doc in chatsSnapshot.documents) {
                    val chatId = doc.id
                    val participants = doc.get("participantIds") as? List<String> ?: continue
                    val peerId = participants.firstOrNull { it != userId } ?: continue

                    val tokenSnapshot = rtdb.child("users/$peerId/fcmToken").get().await()
                    val token = tokenSnapshot.getValue(String::class.java) ?: continue
                    val type = if (status == "offline") "USER_OFFLINE" else "USER_ONLINE"
                    val message = RemoteMessage.Builder(token)
                        .setData(
                            mapOf(
                                "type" to type,
                                "chatId" to chatId,
                                "senderId" to userId,
                                "status" to status
                            )
                        )
                        .build()

                    FirebaseMessaging.getInstance().send(message)
                    android.util.Log.d("UserRepository", "📨 Статус $status отправлен для чата $chatId")
                }
            } catch (e: Exception) {
                android.util.Log.e("UserRepository", "❌ Ошибка отправки статуса", e)
            }
        }
    }
}