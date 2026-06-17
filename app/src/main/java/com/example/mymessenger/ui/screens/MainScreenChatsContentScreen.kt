package com.example.mymessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.ui.theme.spacings
import com.example.mymessenger.ui.viewmodel.MainUiState
import com.example.mymessenger.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.Flow

@Composable
fun MainScreenChatsContent(
    uiState: MainUiState,
    getLastMessageFlow: (String) -> Flow<LocalMessageEntity?>,
    getPeerName: suspend (String) -> String,
    modifier: Modifier = Modifier,
    onChatClick: (String) -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when (uiState) {
            is MainUiState.Loading -> CircularProgressIndicator()

            is MainUiState.Error -> {
                Text(
                    text = stringResource(uiState.messageResId),
                    color = MaterialTheme.colorScheme.error
                )
            }

            is MainUiState.Success -> {
                if (uiState.chats.isEmpty()) {
                    Text(
                        text = "У вас пока нет activeных чатов.\nНажмите +, чтобы добавить друга.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)
                    ) {
                        items(uiState.chats.size) { index ->
                            val chatDoc = uiState.chats[index]

                            val lastMessageEntity by getLastMessageFlow(chatDoc.id)
                                .collectAsState(initial = null)

                            val uids = chatDoc.id.split("_")
                            val isSelfChat = uids.size >= 2 && uids[0] == uids[1]

                            var peerName by remember {
                                mutableStateOf(if (isSelfChat) "Избранное" else "Загрузка...")
                            }

                            if (!isSelfChat) {
                                LaunchedEffect(chatDoc.id) {
                                    val peerId = chatDoc.participantIds.firstOrNull { it != uiState.user.uid } ?: ""
                                    peerName = getPeerName(peerId)
                                }
                            }

                            val lastMessageText = when {
                                lastMessageEntity != null -> lastMessageEntity!!.text
                                isSelfChat -> "Чат с самим собой для заметок"
                                else -> "Нажмите, чтобы открыть переписку"
                            }

                            val isHandshakeComplete = isSelfChat ||
                                    (chatDoc.publicKeyUserA.isNotEmpty() && chatDoc.publicKeyUserB.isNotEmpty())

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onChatClick(chatDoc.id) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(MaterialTheme.spacings.small),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = peerName.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peerName,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = lastMessageText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(
                                                color = if (isHandshakeComplete) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}



