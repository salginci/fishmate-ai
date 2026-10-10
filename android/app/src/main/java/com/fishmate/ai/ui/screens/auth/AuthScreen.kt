package com.fishmate.ai.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.R
import com.fishmate.ai.ui.components.ErrorBanner
import com.fishmate.ai.ui.theme.GloveFriendlyTouchTarget

@Composable
fun AuthScreen(
    language: String,
    onSignedIn: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        state.error?.let { error ->
            ErrorBanner(message = error.message)
            Spacer(Modifier.height(8.dp))
        }

        if (state.mode == AuthMode.REGISTER) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text(stringResource(R.string.auth_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::setEmail,
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.submit(language) },
            enabled = !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = GloveFriendlyTouchTarget),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(
                    stringResource(
                        if (state.mode == AuthMode.REGISTER) R.string.auth_create_account
                        else R.string.auth_sign_in,
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = viewModel::toggleMode,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                stringResource(
                    if (state.mode == AuthMode.REGISTER) R.string.auth_have_account
                    else R.string.auth_need_account,
                ),
            )
        }
    }
}
