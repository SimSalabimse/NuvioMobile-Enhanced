package com.nuvio.app.features.details

/**
 * Person id taken from metadata that already names a TMDB person.
 * A name, an IMDb `nm` id, or a missing field is not turned into a person.
 */
internal fun navigablePersonId(raw: Int?): Int? = raw?.takeIf { it > 0 }

/** A row opens a person only when [navigablePersonId] is set. A name or an IMDb id is not that. */
internal fun castRowActionable(tmdbId: Int?): Boolean = navigablePersonId(tmdbId) != null

internal fun navigablePersonId(raw: String?): Int? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.any { it.isLetter() }) return personIdFromUrl(text)
    return text.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * TMDB person URLs and `tmdb:person:` ids. IMDb name links are not TMDB ids.
 */
internal fun personIdFromUrl(url: String?): Int? {
    val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lower = raw.lowercase()
    if ("imdb.com/name/" in lower || "/name/nm" in lower) return null
    val markers = listOf("/person/", "tmdb:person:", "person:")
    for (marker in markers) {
        val index = lower.indexOf(marker)
        if (index >= 0) return leadingPositiveInt(raw.substring(index + marker.length))
    }
    if (lower.startsWith("tmdb:")) return leadingPositiveInt(raw.substringAfter(':'))
    return null
}

internal enum class CastNavigation {
    OpenPerson,
    Unavailable,
}

internal data class CastNavigationPlan(
    val navigation: CastNavigation,
    val personId: Int?,
    val dismissOverlayFirst: Boolean,
)

/**
 * A real id opens the person. An open sheet or zoom layer is dismissed first
 * so it cannot stay above that screen. A missing id does neither.
 */
internal fun castNavigationPlan(tmdbId: Int?, overlayVisible: Boolean): CastNavigationPlan {
    val id = navigablePersonId(tmdbId)
    return if (id == null) {
        CastNavigationPlan(CastNavigation.Unavailable, personId = null, dismissOverlayFirst = false)
    } else {
        CastNavigationPlan(
            navigation = CastNavigation.OpenPerson,
            personId = id,
            dismissOverlayFirst = overlayVisible,
        )
    }
}

private fun leadingPositiveInt(text: String): Int? {
    val digits = text.takeWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    return digits.toIntOrNull()?.takeIf { it > 0 }
}
