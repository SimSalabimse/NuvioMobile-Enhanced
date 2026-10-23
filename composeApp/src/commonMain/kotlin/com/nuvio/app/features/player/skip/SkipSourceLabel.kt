package com.nuvio.app.features.player.skip

import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER

/**
 * Where skip times came from, in the same words everywhere: the tag next to the Skip button,
 * the analysis chips and the debug readout. Null for an unknown provider.
 */
internal fun skipSourceLabel(provider: String?): String? = when (provider) {
    null -> null
    SkipLearning.PROVIDER -> "Your mark"
    SkipLearning.PROVIDER_IMPORTED -> "TheIntroDB (yours)"
    TheIntroDb.PROVIDER -> "TheIntroDB"
    ON_DEVICE_SKIP_PROVIDER -> "On-device"
    "introdb" -> "IntroDB"
    "aniskip" -> "AniSkip"
    "animeskip" -> "Anime-Skip"
    "heuristic" -> "Guess"
    else -> null
}
