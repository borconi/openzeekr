package com.openzeekr.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import com.openzeekr.app.R
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openzeekr.app.ui.theme.Brand

internal enum class LoginFieldKind { Email, Password }

/**
 * A credential field. Declares its autofill [ContentType] via semantics (Compose 1.8+ autofill), so a
 * password manager both FILLS it and can offer to SAVE a new login - the manager commits on submit, or
 * the caller can call `LocalAutofillManager.current?.commit()` after a successful sign-in to prompt it.
 *
 * In the Studio look it is drawn like [StudioTextField] (rounded, filled with the raised panel colour, accent
 * outline when focused); the Classic look keeps the plain Material outline. The behaviour is the same in both.
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
    val studio = Brand.isStudio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (isPassword) stringResource(R.string.login_password) else stringResource(R.string.login_email)) },
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
        // Classic: the Material defaults, exactly as before.
        shape = if (studio) RoundedCornerShape(16.dp) else OutlinedTextFieldDefaults.shape,
        colors = if (studio) OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Brand.surface2, unfocusedContainerColor = Brand.surface2, disabledContainerColor = Brand.surface2,
            errorContainerColor = Brand.surface2,
            // A hairline when idle, so the field also reads on the page ground, where the fill alone would not show.
            focusedBorderColor = Brand.accent, unfocusedBorderColor = Brand.line, disabledBorderColor = Brand.line,
            errorBorderColor = Brand.crit,
            focusedLabelColor = Brand.accent, unfocusedLabelColor = Brand.muted, cursorColor = Brand.accent,
            focusedTextColor = Brand.text, unfocusedTextColor = Brand.text,
        ) else OutlinedTextFieldDefaults.colors(),
    )
}
