package com.nuvio.app.features.player.skip

internal enum class IntroSubmitService {
    INTRODB,
    THE_INTRODB,
}

internal enum class IntroSubmitKeyProblem {
    NONE,
    MISSING,
    INTRODB_PREFIX,
    THEINTRODB_PREFIX,
}

internal enum class IntroSubmitBlockReason {
    MISSING_KEY,
    INTRODB_KEY_SHAPE,
    THEINTRODB_KEY_SHAPE,
    INTRODB_MOVIE,
    INTRODB_EPISODE,
}

internal fun defaultIntroSubmitService(introDbApiKey: String, theIntroDbApiKey: String): IntroSubmitService {
    val introDbKey = introDbApiKey.trim()
    val theIntroDbKey = theIntroDbApiKey.trim()
    return when {
        introDbKey.startsWith(INTRODB_KEY_PREFIX, ignoreCase = true) -> IntroSubmitService.INTRODB
        theIntroDbKey.isNotBlank() || legacyTheIntroDbKey(introDbKey).isNotBlank() -> IntroSubmitService.THE_INTRODB
        else -> IntroSubmitService.INTRODB
    }
}

internal fun savedKeyForService(
    service: IntroSubmitService,
    introDbApiKey: String,
    theIntroDbApiKey: String,
): String {
    val introDbKey = introDbApiKey.trim()
    val theIntroDbKey = theIntroDbApiKey.trim()
    return when (service) {
        IntroSubmitService.INTRODB -> introDbKey.takeIf { it.startsWith(INTRODB_KEY_PREFIX, ignoreCase = true) }.orEmpty()
        IntroSubmitService.THE_INTRODB -> theIntroDbKey.ifBlank { legacyTheIntroDbKey(introDbKey) }
    }
}

/** A saved key is written only after a live check, so a well-shaped saved key counts as working. */
internal fun introSubmitShowsApiKeyField(service: IntroSubmitService, savedKey: String): Boolean =
    introSubmitKeyProblem(service, savedKey) != IntroSubmitKeyProblem.NONE

internal fun introSubmitKeyProblem(service: IntroSubmitService, apiKey: String): IntroSubmitKeyProblem {
    val key = apiKey.trim()
    if (key.isEmpty()) return IntroSubmitKeyProblem.MISSING
    val isIntroDbAppKey = key.startsWith(INTRODB_KEY_PREFIX, ignoreCase = true)
    return when (service) {
        IntroSubmitService.INTRODB -> if (isIntroDbAppKey) IntroSubmitKeyProblem.NONE else IntroSubmitKeyProblem.INTRODB_PREFIX
        IntroSubmitService.THE_INTRODB -> if (isIntroDbAppKey) IntroSubmitKeyProblem.THEINTRODB_PREFIX else IntroSubmitKeyProblem.NONE
    }
}

internal fun introSubmitBlockReason(
    service: IntroSubmitService,
    apiKey: String,
    isMovie: Boolean,
    imdbId: String,
    season: Int,
    episode: Int,
): IntroSubmitBlockReason? {
    when (introSubmitKeyProblem(service, apiKey)) {
        IntroSubmitKeyProblem.MISSING -> return IntroSubmitBlockReason.MISSING_KEY
        IntroSubmitKeyProblem.INTRODB_PREFIX -> return IntroSubmitBlockReason.INTRODB_KEY_SHAPE
        IntroSubmitKeyProblem.THEINTRODB_PREFIX -> return IntroSubmitBlockReason.THEINTRODB_KEY_SHAPE
        IntroSubmitKeyProblem.NONE -> Unit
    }
    if (service == IntroSubmitService.INTRODB) {
        if (isMovie) return IntroSubmitBlockReason.INTRODB_MOVIE
        if (!imdbId.startsWith("tt", ignoreCase = true) || season <= 0 || episode <= 0) {
            return IntroSubmitBlockReason.INTRODB_EPISODE
        }
    }
    return null
}

/**
 * TheIntroDB "no intro / recap / credits / preview" submissions use 0 for both times.
 * A normal range still has to end after it starts. IntroDB has no absent-segment submit.
 */
internal fun resolveIntroSubmitTimes(
    service: IntroSubmitService,
    absentSegment: Boolean,
    startTimeStr: String,
    endTimeStr: String,
): Pair<Double, Double>? {
    if (absentSegment) {
        if (service != IntroSubmitService.THE_INTRODB) return null
        return 0.0 to 0.0
    }
    val start = parseTimeToSeconds(startTimeStr)
    val end = parseTimeToSeconds(endTimeStr)
    if (start == null || end == null || end <= start) return null
    return start to end
}

private fun legacyTheIntroDbKey(introDbApiKey: String): String {
    return introDbApiKey.takeIf { it.isNotBlank() && !it.startsWith(INTRODB_KEY_PREFIX, ignoreCase = true) }.orEmpty()
}

private const val INTRODB_KEY_PREFIX = "idb_"
