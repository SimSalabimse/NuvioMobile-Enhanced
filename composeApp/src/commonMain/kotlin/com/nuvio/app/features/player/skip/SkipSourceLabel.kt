package com.nuvio.app.features.player.skip

import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.CHAPTER_SKIP_PROVIDER
import com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER

/**
 * Where skip times came from, in the same words everywhere: the tag next to the Skip button,
 * the analysis chips and the debug readout. Null for an unknown provider.
 */
internal fun skipSourceLabel(provider: String?): String? = when (provider) {
    null -> null
    SkipLearning.PROVIDER -> LABEL_YOUR_MARK
    // An imported row is "Your mark" only after [attributeEpisodeSegments] sees it in this account's ledger.
    SkipLearning.PROVIDER_IMPORTED -> null
    TheIntroDb.PROVIDER -> LABEL_THE_INTRO_DB
    ON_DEVICE_SKIP_PROVIDER -> LABEL_DETECTED
    CHAPTER_SKIP_PROVIDER -> LABEL_DETECTED
    "introdb" -> "IntroDB"
    "aniskip" -> "AniSkip"
    "animeskip" -> "Anime-Skip"
    "heuristic" -> "Guess"
    else -> null
}
