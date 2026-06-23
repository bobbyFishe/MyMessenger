package com.example.mymessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MessageBubbleScreen.kt
@Composable
fun MessageBubble(
    message: LocalMessageEntity,
    isMyMessage: Boolean
) {
    Column(
        horizontalAlignment = if (isMyMessage) Alignment.End else Alignment.Start,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (isMyMessage)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isMyMessage) 12.dp else 0.dp,
                        bottomEnd = if (isMyMessage) 0.dp else 12.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = if (isMyMessage)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val date = Date(timestamp)
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
}