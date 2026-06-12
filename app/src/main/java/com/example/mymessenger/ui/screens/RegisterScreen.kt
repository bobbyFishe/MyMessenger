package com.example.mymessenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymessenger.R
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.ui.theme.spacings
import com.example.mymessenger.ui.viewmodel.RegisterResultState
import com.example.mymessenger.ui.viewmodel.RegisterUiState
import com.example.mymessenger.ui.viewmodel.RegisterViewModel
import com.google.protobuf.Internal
import org.koin.androidx.compose.koinViewModel

@Composable
fun RegisterScreen(
    viewModel: RegisterViewModel,
    onSuccess: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState()
    val resultState = viewModel.resultState.collectAsState()

    LaunchedEffect(resultState.value) {
        if (resultState.value is RegisterResultState.Success) {
            onSuccess()
        }
    }

    RegisterScreenContent(
        uiState = uiState.value,
        resultState = resultState.value,
        isInputValid = viewModel.isInputValid,
        isNameChecking = viewModel.isNameChecking.collectAsState().value,
        generateRandomName = {viewModel.generateRandomName() },
        onEmailChange = { viewModel.updateEmail(it) },
        onPasswordChange = { viewModel.updatePassword(it) },
        onPasswordRepeatChange = { viewModel.updatePasswordRepeat(it) },
        isEmailInvalid = viewModel.isEmailInvalid,
        isPasswordTooShort = viewModel.isPasswordTooShort,
        isPasswordMissingLetter = viewModel.isPasswordMissingLetter,
        doPasswordsMatch = viewModel.doPasswordsMatch,
        onRegisterClick = { viewModel.register() }
    )
}


@Composable
fun RegisterScreenContent(
    uiState: RegisterUiState,
    resultState: RegisterResultState,
    isInputValid: Boolean,
    isNameChecking: Boolean,
    generateRandomName: () -> Unit,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordRepeatChange: (String) -> Unit,
    isEmailInvalid: Boolean,
    isPasswordTooShort: Boolean,
    isPasswordMissingLetter: Boolean,
    doPasswordsMatch: Boolean,
    onRegisterClick: () -> Unit
) {
    val snackBarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    LaunchedEffect(resultState) {
        if (resultState is RegisterResultState.Error) {
            val message = if (resultState.dynamicMessage != null) {
                context.resources.getString(resultState.messageResId, resultState.dynamicMessage)
            } else {
                context.resources.getString(resultState.messageResId)
            }
            snackBarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { TopBarScreen(R.string.authorization) },
        snackbarHost = { SnackbarHost(hostState = snackBarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(MaterialTheme.spacings.large)
                .verticalScroll(scrollState)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.unicum_nickname), style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp
                ),
                trailingIcon = {
                    if (isNameChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick =  generateRandomName) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.generate_another_name))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.email,
                onValueChange = onEmailChange,
                label = { Text(stringResource(R.string.email), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                isError = isEmailInvalid,
                supportingText = {
                    when {
                        isEmailInvalid -> {
                            Text(
                                text = stringResource(R.string.invalid_email_format),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        uiState.email.isNotEmpty() && !isEmailInvalid -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.email_is_valid),
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        else -> null
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                isError = isPasswordTooShort || isPasswordMissingLetter,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    when {
                        isPasswordTooShort -> {
                            val symbolsLeft = Constants.MIN_PASSWORD_LENGTH - uiState.password.length
                            Text(
                                text = stringResource(R.string.password_is_too_short, symbolsLeft),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        isPasswordMissingLetter -> {
                            Text(
                                text = stringResource(R.string.password_must_contain_at_least_letter,
                                    Constants.MIN_PASSWORD_WORD),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        uiState.password.isNotEmpty() -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.password_is_valid),
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        else -> {
                            Text(stringResource(R.string.minimum_characters, Constants.MIN_PASSWORD_LENGTH,
                                Constants.MIN_PASSWORD_WORD))
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = uiState.passwordRepeat,
                onValueChange = onPasswordRepeatChange,
                label = { Text(stringResource(R.string.repeat_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = uiState.passwordRepeat.isNotEmpty() && !doPasswordsMatch,
                supportingText = {
                    when {
                        uiState.passwordRepeat.isNotEmpty() && !doPasswordsMatch -> {
                            Text(
                                text = stringResource(R.string.passwords_do_not_match),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        uiState.passwordRepeat.isNotEmpty() && doPasswordsMatch -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.passwords_match_success),
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        else -> null
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (isInputValid) onRegisterClick()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onRegisterClick,
                enabled = isInputValid && resultState !is RegisterResultState.Loading,
                modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.spacings.medium)
            ) {
                if (resultState is RegisterResultState.Loading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.register))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RegisterScreenPreview() {
    RegisterScreenContent(
        uiState = RegisterUiState(name = "Иван", email = "test@test.com", password = "qwe1234"),
        resultState = RegisterResultState.Idle,
        isInputValid = true,
        isNameChecking = true,
        onEmailChange = {},
        onPasswordChange = {},
        onPasswordRepeatChange = {},
        isPasswordTooShort = true,
        doPasswordsMatch = true,
        isEmailInvalid = true,
        onRegisterClick = {},
        generateRandomName = {},
        isPasswordMissingLetter = true
    )
}
