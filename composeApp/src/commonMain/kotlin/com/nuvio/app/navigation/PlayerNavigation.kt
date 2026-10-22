package com.nuvio.app.navigation

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore

/** Opens the player for [launch]; with [replaceStreamRoute] the sources screen is dropped from the back stack. */
internal fun NuvioNavigator.launchPlayer(launch: PlayerLaunch, replaceStreamRoute: Boolean = false) {
    val launchId = PlayerLaunchStore.put(launch)
    navigate(PlayerRoute(launchId = launchId, title = launch.title)) {
        if (replaceStreamRoute) popUpTo<StreamRoute> { inclusive = true }
    }
}
