package com.example.mymessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.mymessenger.domain.repository.UserRepository
import com.example.mymessenger.ui.theme.MyMessengerTheme
import com.example.mymessenger.ui.viewmodel.MainViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val userRepository: UserRepository by inject()
    private val auth = FirebaseAuth.getInstance()
    private var isAppInBackground = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val currentUid = auth.currentUser?.uid ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    userRepository.setUserOnlineStatus(currentUid, isOnline = true)
                    try {
                        val mainViewModel: MainViewModel by inject()
                        mainViewModel.flushAllUnsentMessages()
                    } catch (e: Exception) {
                    }
                }
            }
            override fun onPause(owner: LifecycleOwner) {
                isAppInBackground = true
                val currentUid = auth.currentUser?.uid ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    userRepository.setUserOnlineStatus(currentUid, isOnline = false)
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                if (isAppInBackground) return
                val currentUid = auth.currentUser?.uid ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    userRepository.setUserOnlineStatus(currentUid, isOnline = false)
                }
            }
        })
        enableEdgeToEdge()
        setContent {
            MyMessengerTheme {
                MyMessageApp()
            }
        }
    }
}
