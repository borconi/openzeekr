package com.openzeekr.app.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

internal enum class LoginFieldKind { Email, Password }

/** Compose 1.7 requires explicit autofill nodes for account credentials. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    kind: LoginFieldKind,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val isPassword = kind == LoginFieldKind.Password
    val autofill = LocalAutofill.current
    val autofillTree = LocalAutofillTree.current
    val onFill by rememberUpdatedState(onValueChange)
    val node = remember(kind) {
        AutofillNode(
            autofillTypes = if (isPassword) listOf(AutofillType.Password)
                else listOf(AutofillType.Username, AutofillType.EmailAddress),
            onFill = { onFill(it) },
        )
    }
    var focused by remember(node) { mutableStateOf(false) }

    DisposableEffect(autofillTree, autofill, node) {
        autofillTree += node
        onDispose {
            autofill?.cancelAutofillForNode(node)
            autofillTree.children.remove(node.id)
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(if (isPassword) "Password" else "Email") },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Email,
            imeAction = if (isPassword) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone(); defaultKeyboardAction(ImeAction.Done) }),
        modifier = modifier
            .onGloballyPositioned {
                val firstPlacement = node.boundingBox == null
                node.boundingBox = it.boundsInWindow().translate(it.positionOnScreen() - it.positionInWindow())
                // Handle focus arriving before the field has been laid out.
                if (firstPlacement && focused) autofill?.requestAutofillForNode(node)
            }
            .onFocusChanged {
                focused = it.isFocused
                if (focused && node.boundingBox != null) autofill?.requestAutofillForNode(node)
                else if (!focused) autofill?.cancelAutofillForNode(node)
            },
    )
}
