package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

internal expect fun dismissPlatformSoftwareKeyboard()

/**
 * Clears Compose focus and asks the platform to resign the software keyboard.
 * The second pass runs on the next frame so a dialog opened by the same click
 * cannot leave the keyboard up.
 */
@Composable
internal fun rememberDismissSoftwareKeyboard(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    return remember(focusManager, keyboardController, scope) {
        {
            fun dismiss() {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                dismissPlatformSoftwareKeyboard()
            }
            dismiss()
            scope.launch {
                withFrameNanos { }
                dismiss()
            }
        }
    }
}
