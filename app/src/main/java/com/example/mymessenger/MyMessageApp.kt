package com.example.mymessenger

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.example.mymessenger.ui.screens.RegisterScreen
import com.example.mymessenger.ui.screens.LoginScreen
import com.example.mymessenger.ui.viewmodel.LoginViewModel
import com.example.mymessenger.ui.viewmodel.RegisterViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun MyMessageApp() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = "auth_graph"
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
            composable("chats_list") { Text(text = "chats_list") }
            composable("chat_detail/{chatId}") { /* ... */ }
        }
    }
}