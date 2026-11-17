package com.nuvio.app.features.details

import com.nuvio.app.core.format.extractReleaseYearForDisplay

private fun isTvSeriesType(type: String): Boolean =
    when (type.trim().lowercase()) {
        "series", "tv", "show", "tvshow" -> true
        else -> false
    }

private fun isEndedSeriesStatus(status: String?): Boolean {
    if (status.isNullOrBlank()) return false
    val s = status.trim().lowercase()
    if ("returning" in s || "in production" in s) return false
    return "ended" in s || "canceled" in s || "cancelled" in s
}

/**
 * Compact release line under the details hero.
 * Movies show the year. An ended series with a later last-air year shows "2021 - 2028".
 * A missing end year is just the start year, never "2024 -".
 */
fun formatMetaReleaseLineForDetails(meta: MetaDetails): String? {
    val raw = meta.releaseInfo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!isTvSeriesType(meta.type)) {
        return extractReleaseYearForDisplay(raw)?.toString()
    }
    val startYear = extractReleaseYearForDisplay(raw)?.toString()
    if (startYear == null) return joinMetadataParts(listOf(raw))
    val endYear = meta.lastAirDate
        ?.let { extractReleaseYearForDisplay(it)?.toString() }
        ?.takeIf { isEndedSeriesStatus(meta.status) && it != startYear }
    return joinMetadataParts(listOf(startYear, endYear))
}

private val danglingSeparatorEdge = Regex("""^[\s·•|/–—-]+|[\s·•|/–—-]+$""")

/**
 * Joins the parts that are present. A blank part is dropped with the separator
 * that would have hung off it, and a part that is only a trailing dash is dropped too.
 */
internal fun joinMetadataParts(parts: List<String?>, separator: String = " - "): String? {
    val present = parts.mapNotNull { part ->
        part?.trim()?.takeIf { it.isNotEmpty() }?.replace(danglingSeparatorEdge, "")?.trim()?.takeIf { it.isNotEmpty() }
    }
    return present.takeIf { it.isNotEmpty() }?.joinToString(separator)
}
