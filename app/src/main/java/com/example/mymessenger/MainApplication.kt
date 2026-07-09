package com.example.mymessenger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.mymessenger.data.di.appModule
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext.startKoin

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MainApplication)
            modules(appModule)
        }
        FirebaseApp.initializeApp(this)
        setupUserStatus()
        createNotificationChannel()
    }
    private fun setupUserStatus() {
        val auth = FirebaseAuth.getInstance()
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            val database = FirebaseDatabase.getInstance()

            if (user != null && user.isEmailVerified) {
                val statusRef = database.getReference("users/${user.uid}/status")

                // ✅ При входе → "online"
                statusRef.setValue("online")
                android.util.Log.d("MainApplication", "✅ Статус пользователя: online")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "messenger_channel",
                "Messenger Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

}