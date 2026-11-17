package com.nuvio.app.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.launch

internal expect fun dismissPlatformSoftwareKeyboard()

/**
 * The details column listens for taps so it can dismiss the keyboard.
 * A row that already took the tap is left alone.
 */
internal enum class KeyboardTap {
    ChildHandled,
    DismissKeyboard,
    Ignore,
}

internal fun keyboardTapAction(childConsumed: Boolean, imeVisible: Boolean): KeyboardTap = when {
    childConsumed -> KeyboardTap.ChildHandled
    imeVisible -> KeyboardTap.DismissKeyboard
    else -> KeyboardTap.Ignore
}

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

/**
 * For a screen with a search field: a drag that scrolls it, or a tap on empty space, puts the
 * keyboard away (taps on buttons and rows still go to them).
 */
@Composable
internal fun Modifier.dismissKeyboardOnScrollOrTap(): Modifier {
    val dismiss = rememberDismissSoftwareKeyboard()
    val imeVisible by rememberUpdatedState(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
    val connection = remember(dismiss) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (imeVisible && source == NestedScrollSource.UserInput && available.y != 0f) dismiss()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(connection).pointerInput(dismiss) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Final, requireUnconsumed = false)
            if (keyboardTapAction(down.isConsumed, imeVisible) == KeyboardTap.ChildHandled) return@awaitEachGesture
            val up = waitForUpOrCancellation(pass = PointerEventPass.Final) ?: return@awaitEachGesture
            if (keyboardTapAction(up.isConsumed, imeVisible) == KeyboardTap.DismissKeyboard) dismiss()
        }
    }
}
