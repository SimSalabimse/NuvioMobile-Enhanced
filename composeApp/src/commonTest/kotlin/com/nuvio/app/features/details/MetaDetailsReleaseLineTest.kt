package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetaDetailsReleaseLineTest {
    private fun meta(
        type: String,
        releaseInfo: String?,
        status: String? = null,
        lastAirDate: String? = null,
    ) = MetaDetails(
        id = "1",
        type = type,
        name = "X",
        releaseInfo = releaseInfo,
        status = status,
        lastAirDate = lastAirDate,
    )

    @Test
    fun movieShowsYearOnly() {
        assertEquals("2019", formatMetaReleaseLineForDetails(meta("movie", "2019-07-04")))
    }

    @Test
    fun ongoingSeriesDropsDanglingDash() {
        assertEquals(
            "2025",
            formatMetaReleaseLineForDetails(
                meta("series", "2025-01-10", status = "Returning Series", lastAirDate = "2025-03-01"),
            ),
        )
    }

    @Test
    fun ongoingSeriesIgnoresLaterLastAirDate() {
        assertEquals(
            "2024",
            formatMetaReleaseLineForDetails(
                meta("series", "2024", status = "Returning Series", lastAirDate = "2026-02-01"),
            ),
        )
    }

    @Test
    fun endedSeriesShowsClosedRange() {
        assertEquals(
            "2021 - 2028",
            formatMetaReleaseLineForDetails(
                meta("series", "2021-09-01", status = "Ended", lastAirDate = "2028-05-20"),
            ),
        )
    }

    @Test
    fun endedSameYearSingleYear() {
        assertEquals(
            "2022",
            formatMetaReleaseLineForDetails(
                meta("series", "2022-06-01", status = "Ended", lastAirDate = "2022-12-01"),
            ),
        )
    }

    @Test
    fun nullReleaseInfo() {
        assertNull(formatMetaReleaseLineForDetails(meta("movie", null)))
    }

    @Test
    fun endedWithoutLastAirDateShowsSingleYear() {
        assertEquals(
            "2020",
            formatMetaReleaseLineForDetails(meta("series", "2020-04-01", status = "Ended", lastAirDate = null)),
        )
    }

    @Test
    fun unparsedOpenRangeDropsTheTrailingSeparator() {
        assertEquals(
            "2024",
            formatMetaReleaseLineForDetails(meta("series", "2024 -", status = "Returning Series")),
        )
    }

    @Test
    fun joinDropsMissingPartsAndKeepsAClosedRange() {
        assertEquals("2024", joinMetadataParts(listOf("2024", null)))
        assertEquals("2024", joinMetadataParts(listOf("2024", "  ")))
        assertEquals("2024", joinMetadataParts(listOf("2024 -", null)))
        assertEquals("2028", joinMetadataParts(listOf(null, " - 2028")))
        assertEquals("2021 - 2028", joinMetadataParts(listOf("2021", "2028")))
        assertEquals("2021 - 2028", joinMetadataParts(listOf("2021 - 2028")))
        assertEquals("2024 · Drama", joinMetadataParts(listOf("2024", null, "Drama"), separator = " · "))
        assertEquals(null, joinMetadataParts(listOf(null, " - ", "")))
    }
}
