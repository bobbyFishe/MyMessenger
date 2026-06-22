package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mymessenger.R
import com.example.mymessenger.ui.viewmodel.ChatDetailUiState
import com.example.mymessenger.ui.viewmodel.ChatDetailViewModel
import com.google.firebase.auth.FirebaseAuth
import org.koin.androidx.compose.koinViewModel

// ui/screens/ChatDetailScreen.kt

@Composable
fun ChatDetailScreen(
    chatId: String,
    viewModel: ChatDetailViewModel = koinViewModel(),
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()

    val myId = remember { FirebaseAuth.getInstance().currentUser?.uid ?: "" }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Создаем lazyListState здесь, чтобы управлять им и сохранять его состояние
    val lazyListState = rememberLazyListState()

    LaunchedEffect(chatId) {
        viewModel.initChat(chatId)
    }

    LaunchedEffect(uiState) {
        if (uiState is ChatDetailUiState.Success) {
            val messages = (uiState as ChatDetailUiState.Success).messages

            // 💡 ЭТОТ БЛОК ОТВЕЧАЕТ ЗА ВОССТАНОВЛЕНИЕ ИЗНАЧАЛЬНОГО СКРОЛЛА
            if (!viewModel.isChatLoaded(chatId)) {
                if (messages.isNotEmpty()) {
                    val savedPos = viewModel.getScrollPosition(chatId)
                    if (savedPos != null) {
                        // Если позиция была сохранена, восстанавливаем её без анимации
                        lazyListState.scrollToItem(savedPos)
                    } else {
                        // Если открываем впервые и позиции нет, плавно скроллим в самый конец
                        lazyListState.scrollToItem(messages.size - 1)
                    }
                    viewModel.markChatAsLoaded(chatId)
                }
            }

            val unreadMessages = messages.filter {
                it.senderId != myId && !it.isRead
            }

            if (unreadMessages.isNotEmpty()) {
                android.util.Log.d("ChatDetailScreen", "📖 Marking ${unreadMessages.size} messages as read")
                unreadMessages.forEach { message ->
                    viewModel.markMessageAsRead(message.id)
                }
            }
        }
    }

    // Сохраняем позицию скролла при закрытии экрана или смене chatId
    DisposableEffect(chatId) {
        viewModel.onResume()
        onDispose {
            // Сохраняем индекс первого видимого элемента как текущую позицию
            val currentPosition = lazyListState.firstVisibleItemIndex
            viewModel.saveScrollPosition(chatId, currentPosition)
        }
    }

    ChatDetailContent(
        chatId = chatId,
        uiState = uiState,
        messageText = messageText,
        myId = myId,
        lazyListState = lazyListState, // Передаем состояние скролла внутрь
        onMessageTextChange = { viewModel.updateMessageText(it) },
        onSendClick = {
            viewModel.sendMessage()
            keyboardController?.hide()
        },
        onBackClick = onBackClick
    )
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatDetailContent(
    chatId: String,
    uiState: ChatDetailUiState,
    messageText: String,
    myId: String,
    lazyListState: LazyListState, // Принимаем состояние из родителя
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onBackClick: () -> Unit
) {
    val isKeyboardVisible = WindowInsets.isImeVisible

    // 💡 УМНЫЙ СКРОЛЛ ДЛЯ НОВЫХ СООБЩЕНИЙ И КЛАВИАТУРЫ
    if (uiState is ChatDetailUiState.Success && uiState.messages.isNotEmpty()) {
        val messages = uiState.messages

        // Отслеживаем появление новых сообщений
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                val lastIndex = messages.size - 1
                val isMyLastMessage = messages.last().senderId == myId

                // Проверяем, видит ли пользователь последнее сообщение прямо сейчас
                val isAtBottom = lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == lazyListState.layoutInfo.totalItemsCount - 1

                // Скроллим вниз ТОЛЬКО если это наше сообщение ИЛИ если пользователь уже был в самом низу чата
                if (isMyLastMessage || isAtBottom) {
                    lazyListState.animateScrollToItem(lastIndex)
                }
            }
        }

        // Скролл при открытии клавиатуры
        LaunchedEffect(isKeyboardVisible) {
            if (isKeyboardVisible && messages.isNotEmpty()) {
                lazyListState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Чат", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.ime) // Идеальный отступ под клавиатуру и кнопки
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
                            stringResource(uiState.messageResId),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    is ChatDetailUiState.Success -> {
                        LazyColumn(
                            state = lazyListState, // Используем переданный state
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.messages.size) { index ->
                                val msg = uiState.messages[index]
                                val isMyMessage = msg.senderId == myId

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
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

            // Поле ввода (остается без изменений)
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        shape = RoundedCornerShape(24.dp),
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
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_content_desc)
                        )
                    }
                }
            }
        }
    }
}
