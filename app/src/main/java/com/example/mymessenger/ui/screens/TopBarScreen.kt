package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.mymessenger.R
import com.example.mymessenger.ui.theme.spacings

@Composable
fun TopBarScreen(
    topBarString: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(topBarString),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = MaterialTheme.spacings.large)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TopBarScreenPreview() {
    TopBarScreen(R.string.authorization)
}