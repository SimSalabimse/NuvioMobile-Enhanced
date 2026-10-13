package com.nuvio.app.features.downloads

import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * A link refresh after a background-session wake has the few seconds iOS gives for those events.
 * This asks for the usual extra background time so the addon request and the new task can finish.
 */
internal actual object DownloadBackgroundTime {
    actual fun begin(name: String): Any? {
        val app = UIApplication.sharedApplication
        var id: ULong = UIBackgroundTaskInvalid
        id = app.beginBackgroundTaskWithName(name) {
            if (id != UIBackgroundTaskInvalid) app.endBackgroundTask(id)
            id = UIBackgroundTaskInvalid
        }
        return id.takeIf { it != UIBackgroundTaskInvalid }
    }

    actual fun end(token: Any?) {
        val id = token as? ULong ?: return
        UIApplication.sharedApplication.endBackgroundTask(id)
    }

    actual fun isAppActive(): Boolean =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
}
