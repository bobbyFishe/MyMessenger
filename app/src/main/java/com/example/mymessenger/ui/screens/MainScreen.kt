package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.domain.model.ChatDocument
import com.example.mymessenger.domain.model.User
import com.example.mymessenger.ui.theme.MyMessengerTheme
import com.example.mymessenger.ui.viewmodel.MainUiState
import com.example.mymessenger.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.Flow
import org.koin.androidx.compose.koinViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = koinViewModel(),
    onLogoutSuccess: () -> Unit,
    onChatClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchError by viewModel.searchError.collectAsState()
    val isChatCreatedSuccessfully by viewModel.isChatCreatedSuccessfully.collectAsState()

    MainScreenContent(
        uiState = uiState,
        searchError = searchError,
        isChatCreatedSuccessfully = isChatCreatedSuccessfully,
        onSearchClick = { query -> viewModel.startChatWithUser(query) },
        onDismissSearch = { viewModel.resetSearchState() },
        onLogoutClick = {
            viewModel.logout()
            onLogoutSuccess()
        },
        onChatClick = onChatClick,
        getLastMessageFlow = { chatId -> viewModel.getLastMessageFlow(chatId) },
        getPeerName = { peerId -> viewModel.getPeerName(peerId) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    uiState: MainUiState,
    searchError: Int?,
    isChatCreatedSuccessfully: Boolean,
    onSearchClick: (String) -> Unit,
    onDismissSearch: () -> Unit,
    onLogoutClick: () -> Unit,
    onChatClick: (String) -> Unit,
    getLastMessageFlow: (String) -> Flow<LocalMessageEntity?>,
    getPeerName: suspend (String) -> String
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var isShowSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }


    LaunchedEffect(isChatCreatedSuccessfully) {
        if (isChatCreatedSuccessfully) {
            isShowSearchDialog = false
            searchQuery = ""
            onDismissSearch()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (uiState) {
                        is MainUiState.Loading -> Text(stringResource(R.string.loading_user_placeholder))
                        is MainUiState.Success -> Text(
                            text = uiState.user.name,
                            style = MaterialTheme.typography.titleLarge
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
        },

        floatingActionButton = {
            if (uiState is MainUiState.Success) {
                FloatingActionButton(
                    onClick = { isShowSearchDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_chat_content_desc)
                    )
                }
            }
        }
    ) { innerPadding ->
        MainScreenChatsContent(
            uiState = uiState,
            getLastMessageFlow = getLastMessageFlow,
            getPeerName = getPeerName,
            modifier = Modifier.padding(innerPadding),
            onChatClick = onChatClick
        )

        if (isShowSearchDialog) {
            AlertDialog(
                onDismissRequest = {
                    isShowSearchDialog = false
                    searchQuery = ""
                    onDismissSearch()
                },
                title = { Text(stringResource(R.string.search_contact_title)) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text(stringResource(R.string.search_contact_placeholder)) },
                            textStyle = MaterialTheme.typography.titleMedium,
                            singleLine = true,
                            isError = searchError != null,
                            supportingText = {
                                if (searchError != null) {
                                    Text(
                                        text = stringResource(searchError),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                onSearchClick(searchQuery)
                            }
                        },
                        enabled = searchQuery.isNotBlank()
                    ) {
                        Text(stringResource(R.string.search_button))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            isShowSearchDialog = false
                            searchQuery = ""
                            onDismissSearch()
                        }
                    ) {
                        Text(stringResource(R.string.cancel_button))
                    }
                }
            )
        }
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    val mockUser = User(
        uid = "my_test_uid",
        name = "James_Abbott",
        email = "james@gmail.com"
    )
    val mockChats = listOf(
        ChatDocument(
            id = "my_test_uid_my_test_uid",
            participantIds = listOf("my_test_uid", "my_test_uid"),
            publicKeyUserA = "mock_key",
            publicKeyUserB = "mock_key"
        ),
        ChatDocument(
            id = "friend_uid_my_test_uid",
            participantIds = listOf("my_test_uid", "friend_uid"),
            publicKeyUserA = "mock_key_friend",
            publicKeyUserB = ""
        )
    )
    val mockUiState = MainUiState.Success(
        user = mockUser,
        chats = mockChats
    )
    MyMessengerTheme {
        MainScreenContent(
            uiState = mockUiState,
            searchError = null,
            isChatCreatedSuccessfully = false,
            onSearchClick = {},
            onDismissSearch = {},
            onLogoutClick = {},
            onChatClick = {},
            getLastMessageFlow = { _ -> kotlinx.coroutines.flow.flowOf(null) },
            getPeerName = { _ -> "James_Abbott" }
        )
    }
}
