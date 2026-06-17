package com.example.mymessenger.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey
    val uid: String,
    val name: String,
    val timestamp: Long
)
