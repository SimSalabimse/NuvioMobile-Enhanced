@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nuvio.app.core.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIApplication
import platform.objc.sel_registerName

@OptIn(ExperimentalForeignApi::class)
internal actual fun dismissPlatformSoftwareKeyboard() {
    UIApplication.sharedApplication.sendAction(
        action = sel_registerName("resignFirstResponder"),
        to = null,
        from = null,
        forEvent = null,
    )
}
