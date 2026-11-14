package com.openzeekr.app.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.openzeekr.app.R

internal enum class LoginFieldKind { Email, Password }

/**
 * A credential field. Declares its autofill [ContentType] via semantics (Compose 1.8+ autofill), so a
 * password manager both FILLS it and can offer to SAVE a new login - the manager commits on submit, or
 * the caller can call `LocalAutofillManager.current?.commit()` after a successful sign-in to prompt it.
 */
@Composable
internal fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    kind: LoginFieldKind,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val isPassword = kind == LoginFieldKind.Password
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(if (isPassword) R.string.ui_login_password else R.string.ui_login_email)) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Email,
            imeAction = if (isPassword) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone(); defaultKeyboardAction(ImeAction.Done) }),
        modifier = modifier.semantics {
            contentType = if (isPassword) ContentType.Password
            else ContentType.Username + ContentType.EmailAddress
        },
    )
}
