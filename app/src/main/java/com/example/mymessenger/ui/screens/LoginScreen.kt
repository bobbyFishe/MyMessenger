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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymessenger.R
import com.example.mymessenger.data.utils.Constants
import com.example.mymessenger.ui.theme.spacings
import com.example.mymessenger.ui.viewmodel.LoginResultState
import com.example.mymessenger.ui.viewmodel.LoginUiState
import com.example.mymessenger.ui.viewmodel.LoginViewModel

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    showRegisterSuccessMessage: Boolean = false,
    onSuccess: () -> Unit,
    onRegisterClick: () -> Unit
) {
    val uiState = viewModel.uiState.collectAsState()
    val resultState = viewModel.resultState.collectAsState()
    val snackBarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (showRegisterSuccessMessage) {
            snackBarHostState.showSnackbar(
                message = context.resources.getString(R.string.registration_success_verify_email),
                duration = SnackbarDuration.Long
            )
        }
    }

    LaunchedEffect(resultState.value) {
        when (val state = resultState.value) {
            is LoginResultState.Success -> {
                onSuccess()
            }

            is LoginResultState.ResetEmailSent -> {
                snackBarHostState.showSnackbar(
                    message = context.resources.getString(R.string.reset_password_instruction),
                    duration = SnackbarDuration.Long
                )
            }

            is LoginResultState.Error -> {
                snackBarHostState.showSnackbar(context.resources.getString(state.messageResId))
            }

            else -> {}
        }
    }

    LoginScreenContent(
        uiState = uiState.value,
        resultState = resultState.value,
        isInputValid = viewModel.isLoginInputValid,
        snackBarHostState = snackBarHostState,
        onEmailChange = { viewModel.updateEmail(it) },
        isEmailInvalid = viewModel.isEmailInvalid,
        isPasswordTooShort = viewModel.isPasswordTooShort,
        onPasswordChange = { viewModel.updatePassword(it) },
        onLoginClick = { viewModel.login() },
        onResetPasswordClick = { viewModel.resetPassword() },
        onRegisterClick = onRegisterClick,
        missingRequirements = viewModel.missingPasswordRequirements,
    )
}

@Composable
fun LoginScreenContent(
    uiState: LoginUiState,
    resultState: LoginResultState,
    isInputValid: Boolean,
    snackBarHostState: SnackbarHostState,
    onEmailChange: (String) -> Unit,
    isEmailInvalid: Boolean,
    isPasswordTooShort: Boolean,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onResetPasswordClick: () -> Unit,
    onRegisterClick: () -> Unit,
    missingRequirements: List<String>
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    Scaffold(
        topBar = { TopBarScreen(R.string.login) },
        snackbarHost = { SnackbarHost(hostState = snackBarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(MaterialTheme.spacings.large)
                .verticalScroll(scrollState)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(
                MaterialTheme.spacings.small,
                Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = uiState.email,
                onValueChange = onEmailChange,
                label = {
                    Text(
                        stringResource(R.string.email_label),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                placeholder = { Text(stringResource(R.string.email_placeholder)) },
                singleLine = true,
                isError = isEmailInvalid,
                supportingText = {
                    if (isEmailInvalid) {
                        Text(
                            text = stringResource(R.string.invalid_email_message),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
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
                label = {
                    Text(
                        stringResource(R.string.password_label),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true,
                isError = isPasswordTooShort || missingRequirements.isNotEmpty(),
                supportingText = {
                    when {
                        isPasswordTooShort -> {
                            val symbolsLeft =
                                Constants.MIN_PASSWORD_LENGTH - uiState.password.length
                            Text(
                                text = stringResource(R.string.password_hint_or_error, symbolsLeft),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        missingRequirements.isNotEmpty() -> {
                            val missingWords = missingRequirements.map { req ->
                                when (req) {
                                    "LOWERCASE" -> context.resources.getString(R.string.req_lowercase)
                                    "UPPERCASE" -> context.resources.getString(R.string.req_uppercase)
                                    else -> context.resources.getString(R.string.req_digit)
                                }
                            }
                            val combinedRequirements = missingWords.joinToString(", ")
                            Text(
                                text = stringResource(
                                    R.string.password_missing_prefix,
                                    combinedRequirements
                                ),
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
                                    modifier = Modifier.size(MaterialTheme.spacings.medium)
                                )
                                Text(
                                    text = stringResource(R.string.password_is_valid),
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        else -> {
                            Text(
                                stringResource(
                                    R.string.minimum_characters,
                                    Constants.MIN_PASSWORD_LENGTH
                                )
                            )
                        }
                    }
                },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (isInputValid) onLoginClick()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = onResetPasswordClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    stringResource(R.string.forgot_password_button),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = onLoginClick,
                enabled = isInputValid && resultState !is LoginResultState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.spacings.medium)
            ) {
                if (resultState is LoginResultState.Loading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.login_button))
                }
            }
            TextButton(
                onClick = onRegisterClick,
                modifier = Modifier.padding(top = MaterialTheme.spacings.medium)
            ) {
                Text(
                    stringResource(R.string.no_account_link),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    LoginScreenContent(
        uiState = LoginUiState(email = "user@mail.com"),
        resultState = LoginResultState.Idle,
        isInputValid = true,
        snackBarHostState = remember { SnackbarHostState() },
        onEmailChange = {},
        onPasswordChange = {},
        onLoginClick = {},
        onResetPasswordClick = {},
        onRegisterClick = {},
        isEmailInvalid = true,
        isPasswordTooShort = false,
        missingRequirements = listOf(),
    )
}