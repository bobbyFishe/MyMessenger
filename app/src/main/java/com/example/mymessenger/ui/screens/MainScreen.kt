package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.mymessenger.R
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.ui.theme.MyMessengerTheme
import com.example.mymessenger.ui.viewmodel.MainUiState
import com.example.mymessenger.ui.viewmodel.MainViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
    onLogoutSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    MainScreenContent(
        uiState = uiState,
        onLogoutClick = {
            viewModel.logout()
            onLogoutSuccess()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    uiState: MainUiState,
    onLogoutClick: () -> Unit
) {
    var isMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (uiState) {
                        is MainUiState.Loading -> Text(stringResource(R.string.loading_user_placeholder))
                        is MainUiState.Success -> Text(
                            text = uiState.user.name,
                            style = MaterialTheme.typography.titleMedium
                        )

                        is MainUiState.Error -> Text(text = stringResource(R.string.error))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { isMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logout_menu_item)) },
                                onClick = {
                                    isMenuExpanded = false
                                    onLogoutClick()
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState) {
                is MainUiState.Loading -> CircularProgressIndicator()
                is MainUiState.Success -> Text("Добро пожаловать, ${uiState.user.name}!")
                is MainUiState.Error -> Text(
                    stringResource(uiState.messageResId),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MyMessengerTheme {
        MainScreenContent(
            uiState = MainUiState.Success(
                user = User(uid = "1", name = "John_Doe", email = "john@gmail.com")
            ),
            onLogoutClick = {}
        )
    }
}