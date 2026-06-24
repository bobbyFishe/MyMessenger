package com.example.mymessenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymessenger.data.local.entities.LocalMessageEntity
import com.example.mymessenger.ui.theme.spacings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: LocalMessageEntity,
    isMyMessage: Boolean
) {
    Column(
        horizontalAlignment = if (isMyMessage) Alignment.End else Alignment.Start,
        modifier = Modifier.padding(vertical = MaterialTheme.spacings.default)
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (isMyMessage)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(
                        topStart = MaterialTheme.spacings.medium,
                        topEnd = MaterialTheme.spacings.medium,
                        bottomStart = if (isMyMessage) MaterialTheme.spacings.medium else MaterialTheme.spacings.default,
                        bottomEnd = if (isMyMessage) MaterialTheme.spacings.default else MaterialTheme.spacings.medium
                    )
                )
                .padding(horizontal = MaterialTheme.spacings.medium, vertical = MaterialTheme.spacings.small)
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
            modifier = Modifier.padding(top = MaterialTheme.spacings.extraSmall),
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