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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.ui.theme.spacings
import com.example.mymessenger.ui.viewmodel.MainUiState
import kotlinx.coroutines.flow.Flow

@Composable
fun MainScreenChatsContent(
    uiState: MainUiState,
    getLastMessageFlow: (String) -> Flow<LocalMessageEntity?>,
    getUnreadCountFlow: (String) -> Flow<Int>,
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
                        text = "У вас пока нет активных чатов.\nНажмите +, чтобы добавить друга.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)
                    ) {
                        items(
                            items = uiState.chats,
                            key = { chat -> chat.id }
                        ) { chatDoc ->
                            val lastMessageEntity by remember(chatDoc.id) {
                                getLastMessageFlow(
                                    chatDoc.id
                                )
                            }
                                .collectAsState(initial = null)
                            val unreadCount by remember(chatDoc.id) { getUnreadCountFlow(chatDoc.id) }
                                .collectAsState(initial = 0)
                            val uids = chatDoc.id.split("_")
                            val isSelfChat = uids.size >= 2 && uids[0] == uids[1]
                            var peerName by remember(chatDoc.id) {
                                mutableStateOf(if (isSelfChat) "Избранное" else "Загрузка...")
                            }
                            if (!isSelfChat) {
                                LaunchedEffect(chatDoc.id) {
                                    val peerId =
                                        chatDoc.participantIds.firstOrNull { it != uiState.user.uid }
                                            ?: ""
                                    peerName = getPeerName(peerId)
                                }
                            }
                            val lastMessageText = when {
                                lastMessageEntity != null -> lastMessageEntity!!.text
                                isSelfChat -> "Чат с самим собой для заметок"
                                else -> "Нажмите, чтобы открыть переписку"
                            }
                            val hasUnread = unreadCount > 0
                            val messageTextColor = if (hasUnread) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
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
                                                shape = CircleShape
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
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = lastMessageText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = messageTextColor,
                                            fontWeight = if (hasUnread) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isHandshakeComplete = isSelfChat ||
                                                (chatDoc.publicKeyUserA.isNotEmpty() && chatDoc.publicKeyUserB.isNotEmpty())
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = if (isHandshakeComplete) Color(
                                                        0xFF2E7D32
                                                    ) else MaterialTheme.colorScheme.error,
                                                    shape = CircleShape
                                                )
                                        )
                                        if (hasUnread) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center
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
        }
    }
}



