package com.nuvio.app.features.player

/** System captioning reads can throw. A failed read stays off instead of crashing playback setup. */
internal fun captioningFlagOrFalse(read: () -> Boolean): Boolean =
    runCatching(read).getOrDefault(false)

internal fun <T> captioningValueOrNull(read: () -> T): T? =
    runCatching(read).getOrNull()
