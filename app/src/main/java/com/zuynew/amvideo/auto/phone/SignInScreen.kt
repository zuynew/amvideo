package com.zuynew.amvideo.auto.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.data.PHONE_LENGTH
import com.zuynew.amvideo.auto.data.normalizePhone
import com.zuynew.amvideo.auto.ui.resolve

@Composable
fun SignInScreen(
    state: UiState,
    onSignIn: (phone: String, password: String, pin: String) -> Unit,
    onSignInWithToken: (String) -> Unit,
    onRequestPin: (phone: String) -> Unit,
    onNoticeShown: () -> Unit,
) {
    var phone by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: saved state is written to a bundle on disk.
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    // Not saveable: a revealed password should not outlive the screen.
    var passwordVisible by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var tokenEntry by rememberSaveable { mutableStateOf(false) }

    val phoneReady = normalizePhone(phone).length == PHONE_LENGTH
    val canSubmit = phoneReady && password.isNotBlank() && !state.busy

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .imePadding()
                // In landscape, or once the PIN field appears, the keyboard
                // leaves no room for the button unless this can scroll.
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.sign_in_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.sign_in_hint),
                style = MaterialTheme.typography.bodyMedium,
            )

            // In token mode none of this applies, and two live sign-in
            // buttons on one screen is just a way to tap the wrong one.
            if (!tokenEntry) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.phone_label)) },
                    isError = phone.isNotEmpty() && !phoneReady,
                    supportingText = {
                        if (phone.isNotEmpty() && !phoneReady) {
                            Text(stringResource(R.string.phone_invalid))
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password_label)) },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(
                                stringResource(
                                    if (passwordVisible) R.string.password_hide else R.string.password_show,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSubmit) onSignIn(phone, password, pin) },
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Only shown once the server has told us this device is new to it.
                if (state.pinRequired) {
                    // The server sends no code of its own accord, so without this
                    // the field below would ask for one that never arrives.
                    TextButton(
                        onClick = { onRequestPin(phone) },
                        enabled = phoneReady && !state.busy,
                    ) {
                        Text(stringResource(R.string.pin_request_action))
                    }

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text(stringResource(R.string.pin_label)) },
                        supportingText = { Text(stringResource(R.string.pin_hint)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (canSubmit) onSignIn(phone, password, pin) },
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Logins are capped per day, so carrying a session across from a
            }

            // device that is already signed in costs nothing.
            TextButton(onClick = { tokenEntry = !tokenEntry }) {
                Text(
                    stringResource(
                        if (tokenEntry) R.string.token_sign_in_hide else R.string.token_sign_in_show,
                    ),
                )
            }

            if (tokenEntry) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.token_label)) },
                    supportingText = { Text(stringResource(R.string.token_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSignInWithToken(token) },
                    enabled = token.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.token_sign_in_action))
                }
            }

            val notice = state.notice
            if (notice != null) {
                Text(
                    text = notice.resolve(LocalContext.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notice.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            if (!tokenEntry) {
                Button(
                    onClick = { onSignIn(phone, password, pin) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.busy) {
                        BusyIndicator(Modifier.padding(end = 8.dp))
                    }
                    Text(stringResource(R.string.sign_in_action))
                }
            }
        }
    }
}

/** A spinner small enough to sit inside a button, and announced to TalkBack. */
@Composable
fun BusyIndicator(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.busy)
    CircularProgressIndicator(
        modifier = modifier
            .size(18.dp)
            .semantics { contentDescription = description },
        strokeWidth = 2.dp,
    )
}
