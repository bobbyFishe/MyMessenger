package com.example.mymessenger.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.mymessenger.data.local.dao.ChatDao
import com.example.mymessenger.data.local.dao.ChatKeyDao
import com.example.mymessenger.data.local.dao.ContactDao
import com.example.mymessenger.data.local.dao.MessageDao
import com.example.mymessenger.data.local.entities.ChatEntity
import com.example.mymessenger.data.local.entities.ChatKeyEntity
import com.example.mymessenger.data.local.entities.ContactEntity
import com.example.mymessenger.data.local.entities.LocalMessageEntity

@Database(
    entities = [
        ChatKeyEntity::class,
        LocalMessageEntity::class,
        ContactEntity::class,
        ChatEntity::class
    ],
    version = 6,
    exportSchema = false
)

@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatKeyDao(): ChatKeyDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun chatDao(): ChatDao
}