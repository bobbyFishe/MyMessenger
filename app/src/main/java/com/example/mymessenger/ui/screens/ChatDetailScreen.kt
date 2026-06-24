package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.mymessenger.R
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.ui.theme.MyMessengerTheme
import com.example.mymessenger.ui.theme.spacings
import com.example.mymessenger.ui.viewmodel.ChatDetailUiState
import com.example.mymessenger.ui.viewmodel.ChatDetailViewModel
import com.google.firebase.auth.FirebaseAuth
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatDetailScreen(
    chatId: String,
    viewModel: ChatDetailViewModel = koinViewModel(),
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val myId = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }

    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = 0)
    val previousMessageCount = remember { mutableIntStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(chatId) {
        viewModel.initChat(chatId)
    }

    LaunchedEffect(chatId, uiState) {
        if (uiState is ChatDetailUiState.Success) {
            val messages = (uiState as ChatDetailUiState.Success).messages
            if (!viewModel.isChatLoaded(chatId) && messages.isNotEmpty()) {
                val savedPos = viewModel.getScrollPosition(chatId)
                lazyListState.scrollToItem(savedPos ?: 0)
                viewModel.markChatAsLoaded(chatId)
                previousMessageCount.intValue = messages.size
            }
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is ChatDetailUiState.Success) {
            val messages = (uiState as ChatDetailUiState.Success).messages

            if (messages.isNotEmpty()) {
                val currentCount = messages.size
                val prevCount = previousMessageCount.value

                if (currentCount > prevCount) {
                    val newestMessage = messages.first()
                    val isMyMessage = newestMessage.senderId == myId

                    try {
                        if (isMyMessage) {
                            val mediaPlayer = android.media.MediaPlayer.create(context, R.raw.outgoing_sound)
                            mediaPlayer.setOnCompletionListener { mp -> mp.release() }
                            mediaPlayer.start()
                        } else if (prevCount > 0) {
                            val mediaPlayer = android.media.MediaPlayer.create(context, R.raw.incoming_sound)
                            mediaPlayer.setOnCompletionListener { mp -> mp.release() }
                            mediaPlayer.start()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatDetailScreen", "❌ Ошибка проигрывания звука", e)
                    }

                    val isNearBottom = lazyListState.firstVisibleItemIndex <= 2

                    if (isMyMessage || isNearBottom) {
                        lazyListState.animateScrollToItem(0)
                    }
                }

                previousMessageCount.value = currentCount
            }
        }
    }

    DisposableEffect(chatId) {
        onDispose {
            val currentPosition = lazyListState.firstVisibleItemIndex
            viewModel.saveScrollPosition(chatId, currentPosition)
        }
    }

    ChatDetailContent(
        uiState = uiState,
        messageText = messageText,
        myId = myId,
        lazyListState = lazyListState,
        onMessageTextChange = { viewModel.updateMessageText(it) },
        onSendClick = { viewModel.sendMessage() },
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailContent(
    uiState: ChatDetailUiState,
    messageText: String,
    myId: String,
    lazyListState: LazyListState,
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.chat),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        modifier = Modifier.imePadding()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (uiState) {
                    is ChatDetailUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    is ChatDetailUiState.Error -> {
                        Text(
                            text = stringResource(uiState.messageResId),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is ChatDetailUiState.Success -> {
                        val messages = uiState.messages

                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(MaterialTheme.spacings.medium),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
                            reverseLayout = true
                        ) {
                            itemsIndexed(
                                items = messages,
                                key = { _, msg -> msg.id }
                            ) { _, msg ->
                                val isMyMessage = msg.senderId == myId

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem(
                                            placementSpec = androidx.compose.animation.core.tween(
                                                durationMillis = 250
                                            )
                                        ),
                                    horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
                                ) {
                                    MessageBubble(
                                        message = msg,
                                        isMyMessage = isMyMessage
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                tonalElevation = MaterialTheme.spacings.extraSmall,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacings.extraSmall,
                            vertical = MaterialTheme.spacings.extraSmall
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)
                ) {
                    OutlinedTextField(
                        value = messageText,
                        onValueChange = onMessageTextChange,
                        placeholder = { Text(stringResource(R.string.message_placeholder)) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Send
                        ),
                        shape = RoundedCornerShape(MaterialTheme.spacings.large),
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = onSendClick,
                        enabled = messageText.isNotBlank(),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_content_desc),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ChatDetailContentPreview() {
    val fakeMessages = listOf(
        LocalMessageEntity(
            id = "1", chatId = "chat_123", senderId = "peer_id",
            text = "Привет! Как дела?",
            timestamp = System.currentTimeMillis() - 120000, isMine = false,
            isRead = true
        ),
        LocalMessageEntity(
            id = "2", chatId = "chat_123", senderId = "my_id",
            text = "Привет! Всё отлично, спасибо)",
            timestamp = System.currentTimeMillis() - 60000, isMine = true,
            isRead = true
        ),
        LocalMessageEntity(
            id = "3", chatId = "chat_123", senderId = "peer_id",
            text = "Это длинное сообщение от собеседника, которое занимает несколько строк и проверяет перенос текста в пузыре",
            timestamp = System.currentTimeMillis() - 30000, isMine = false,
            isRead = false
        ),
        LocalMessageEntity(
            id = "4", chatId = "chat_123", senderId = "my_id",
            text = "Окей!",
            timestamp = System.currentTimeMillis(), isMine = true,
            isRead = false
        )
    )
    MyMessengerTheme {
        ChatDetailContent(
            uiState = ChatDetailUiState.Success(messages = fakeMessages),
            messageText = "",
            myId = "my_id",
            lazyListState = rememberLazyListState(),
            onMessageTextChange = {},
            onSendClick = {},
            onBackClick = {}
        )
    }
}