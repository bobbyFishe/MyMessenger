package com.example.mymessenger.domain.model

data class User(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val createdAt: Long = 0L
)
