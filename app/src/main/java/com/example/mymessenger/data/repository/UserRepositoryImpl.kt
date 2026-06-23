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

            // 1. Проверяем локальную Room
            val hasLocalKey = chatKeyDao.getKeyForChat(chatId) != null
            val hasLocalChat = chatDao.getChatById(chatId) != null

            if (hasLocalKey && hasLocalChat) {
                return Result.success(Unit)
            }

            // 2. ОПТИМИЗАЦИЯ: В Firestore идем ТОЛЬКО если это не чат с самим собой
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

            // 3. Создаем новый чат и генерируем RSA ключи
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val myPublicKeyString = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val myPrivateKeyString = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)

            // 4. Сохраняем закрытый ключ в Room
            chatKeyDao.saveKey(
                ChatKeyEntity(
                    chatId = chatId,
                    privateKey = myPrivateKeyString
                )
            )

            // 5. Формируем документ для Firestore и Room
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

            // 6. Записываем кэш чата в Room
            chatDao.saveChat(
                ChatEntity(
                    id = chatDoc.id,
                    participantIds = chatDoc.participantIds,
                    publicKeyUserA = chatDoc.publicKeyUserA,
                    publicKeyUserB = chatDoc.publicKeyUserB,
                    createdAt = chatDoc.createdAt
                )
            )

            // 7. Отправляем публичные метаданные в Firestore
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
        // 1. Настраиваем слушатель обновлений из сети (Firestore)
        val listener = firestore.collection("chats")
            .whereArrayContains("participantIds", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val chatDocs = snapshot.documents.mapNotNull { doc ->
                    doc.toObject(ChatDocument::class.java)
                }

                // Синхронизируем изменения с Room на фоновом потоке
                launch(Dispatchers.IO) {
                    try {
                        // Используем маппинг. Room сам обновит измененные чаты благодаря OnConflictStrategy.REPLACE
                        val entities = chatDocs.map { doc ->
                            ChatEntity(
                                id = doc.id,
                                participantIds = doc.participantIds,
                                publicKeyUserA = doc.publicKeyUserA,
                                publicKeyUserB = doc.publicKeyUserB,
                                createdAt = doc.createdAt
                            )
                        }
                        chatDao.saveChats(entities) // Предполагается, что тут внутри @Insert(onConflict = REPLACE)
                    } catch (e: Exception) {
                        android.util.Log.e("UserRepository", "❌ Error syncing chats to Room", e)
                    }
                }
            }

        // 2. 🔥 ЕДИНСТВЕННЫЙ ИСТОЧНИК ПРАВДЫ ДЛЯ UI — ЭТО ROOM
        // Мы просто перенаправляем поток из локальной базы прямо в этот callbackFlow
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
                trySend(chatDocs) // UI мгновенно получает кэш, а затем актуальные данные, когда Room обновится сетью
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


}