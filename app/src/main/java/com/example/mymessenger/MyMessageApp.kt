package com.example.mymessenger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.example.mymessenger.ui.screens.ChatDetailScreen
import com.example.mymessenger.ui.screens.RegisterScreen
import com.example.mymessenger.ui.screens.LoginScreen
import com.example.mymessenger.ui.screens.MainScreen
import com.example.mymessenger.ui.viewmodel.LoginViewModel
import com.example.mymessenger.ui.viewmodel.RegisterViewModel
import com.google.firebase.auth.FirebaseAuth
import org.koin.androidx.compose.koinViewModel

@Composable
fun MyMessageApp() {
    val navController = rememberNavController()
    val firebaseAuth = FirebaseAuth.getInstance()
    val isUserLoggedIn = firebaseAuth.currentUser != null && firebaseAuth.currentUser?.isEmailVerified == true
    val startGraph = if (isUserLoggedIn) "main_graph" else "auth_graph"


    LaunchedEffect(Unit) {
        MainActivity.notificationChatClickFlow.collect { chatId ->
            if (firebaseAuth.currentUser != null) {
                navController.navigate("chat_detail/$chatId") {
                    popUpTo("chats_list") {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startGraph
    ) {
        navigation(
            startDestination = "login?fromRegister={fromRegister}",
            route = "auth_graph"
        ) {
            composable(route = "login?fromRegister={fromRegister}") { backStackEntry ->
                val fromRegister =
                    backStackEntry.arguments?.getString("fromRegister")?.toBoolean() ?: false
                val loginViewModel: LoginViewModel = koinViewModel()
                LoginScreen(
                    viewModel = loginViewModel,
                    showRegisterSuccessMessage = fromRegister,
                    onSuccess = { navController.navigate("main_graph") },
                    onRegisterClick = { navController.navigate("register") }
                )
            }
            composable(route = "register") {
                val registerViewModel: RegisterViewModel = koinViewModel()
                RegisterScreen(
                    viewModel = registerViewModel,
                    onSuccess = {
                        navController.navigate("login?fromRegister=true") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                )
            }
        }
        navigation(
            startDestination = "chats_list",
            route = "main_graph"
        ) {
            composable("chats_list") {
                MainScreen(
                    onLogoutSuccess = {
                        navController.navigate("auth_graph") {
                            popUpTo("main_graph") { inclusive = true }
                        }
                    },
                    onChatClick = { chatId->
                        navController.navigate("chat_detail/$chatId")
                    }
                )
            }
            composable("chat_detail/{chatId}") { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: ""
                ChatDetailScreen(
                    chatId = chatId,
                    onBackClick = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}