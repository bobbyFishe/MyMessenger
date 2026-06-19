package com.example.mymessenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.ContactDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.ChatKeyEntity
import com.example.mymessenger.data.local.entities.ContactEntity
import com.example.mymessenger.data.local.entities.LocalMessageEntity

@Database(
    entities = [ChatKeyEntity::class, LocalMessageEntity::class, ContactEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatKeyDao(): ChatKeyDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
}