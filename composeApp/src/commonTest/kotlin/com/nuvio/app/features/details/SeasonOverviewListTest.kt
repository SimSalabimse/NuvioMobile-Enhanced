package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.OwnAccountMarks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonOverviewListTest {
    private val copy = englishSeasonOverviewCopy()

    @Test
    fun eachMarkKindKeepsItsOwnSymbolState() {
        assertEquals(SeasonMarkSymbolState.OwnConfirmed, seasonMarkSymbolState(SegmentMark.Submitted))
        assertEquals(SeasonMarkSymbolState.OwnPending, seasonMarkSymbolState(SegmentMark.Pending))
        assertEquals(SeasonMarkSymbolState.DetectedHere, seasonMarkSymbolState(SegmentMark.Detected))
        assertEquals(SeasonMarkSymbolState.Missing, seasonMarkSymbolState(SegmentMark.Missing))

        val row = row(
            episode = 4,
            own = OwnAccountMarks(
                confirmed = mapOf(key(4) to setOf("intro")),
                pending = mapOf(key(4) to setOf("recap")),
            ),
            detected = setOf("outro"),
        )
        val symbols = seasonOverviewSymbols(row.marks, copy)
        assertEquals(
            listOf(
                SeasonOverviewSymbolKind.Intro,
                SeasonOverviewSymbolKind.Recap,
                SeasonOverviewSymbolKind.Credits,
                SeasonOverviewSymbolKind.Preview,
            ),
            symbols.map { it.kind },
        )
        assertEquals(
            listOf(
                SeasonMarkSymbolState.OwnConfirmed,
                SeasonMarkSymbolState.OwnPending,
                SeasonMarkSymbolState.DetectedHere,
                SeasonMarkSymbolState.Missing,
            ),
            symbols.map { it.state },
        )
        assertEquals("Intro:OwnConfirmed", symbols[0].token)
        assertEquals('C', symbols[0].state.wireCode())
        assertEquals('P', symbols[1].state.wireCode())
        assertEquals('D', symbols[2].state.wireCode())
        assertEquals('M', symbols[3].state.wireCode())
        assertTrue(symbols[0].accessibility.contains("Intro"))
        assertFalse(symbols[0].accessibility.contains("Public"))
    }

    @Test
    fun publicLineStaysApartFromOwnMarks() {
        val owned = listUi(
            rows = listOf(
                row(
                    episode = 1,
                    own = OwnAccountMarks(confirmed = mapOf(key(1) to setOf("intro", "recap", "outro", "preview"))),
                    detected = setOf("intro"),
                ),
            ),
            facts = mapOf(
                key(1) to facts(
                    key = key(1),
                    publicRead = CatalogRead.None,
                    publicTypes = emptySet(),
                    ownConfirmed = setOf("intro"),
                ),
            ),
        )
        val ownedRow = owned.rows.single()
        assertEquals("Public: none", ownedRow.publicLine)
        assertTrue(ownedRow.symbols.all { it.state == SeasonMarkSymbolState.OwnConfirmed })
        assertFalse(ownedRow.publicLine.contains("Yours"))
        assertFalse(ownedRow.spoken.contains("Yours"))
        assertTrue(ownedRow.spoken.contains("Public: none"))

        val published = listUi(
            rows = listOf(row(episode = 2, own = OwnAccountMarks(), detected = emptySet())),
            facts = mapOf(
                key(2) to facts(
                    key = key(2),
                    publicRead = CatalogRead.Listed,
                    publicTypes = setOf("intro", "outro", "recap", "preview"),
                    ownConfirmed = emptySet(),
                ),
            ),
        )
        val publishedRow = published.rows.single()
        assertEquals("Public: Intro, Recap, Credits, Preview", publishedRow.publicLine)
        assertTrue(publishedRow.symbols.all { it.state == SeasonMarkSymbolState.Missing })
        assertFalse(publishedRow.publicLine.contains("submitted"))
        assertFalse(publishedRow.publicLine.contains("pending"))

        val notChecked = listUi(
            rows = listOf(
                row(
                    episode = 3,
                    own = OwnAccountMarks(pending = mapOf(key(3) to setOf("intro"))),
                    detected = setOf("recap"),
                ),
            ),
            facts = mapOf(key(3) to facts(key(3), CatalogRead.NotChecked, emptySet(), emptySet())),
        )
        assertEquals("Public: not checked", notChecked.rows.single().publicLine)
        assertEquals(SeasonMarkSymbolState.OwnPending, notChecked.rows.single().symbols[0].state)
        assertEquals(SeasonMarkSymbolState.DetectedHere, notChecked.rows.single().symbols[1].state)

        val unavailable = seasonOverviewPublicLine(CatalogRead.Unavailable, setOf("intro"), copy)
        assertEquals("Public: unavailable", unavailable)
        val missingFacts = seasonOverviewPublicLine(null, setOf("intro"), copy)
        assertEquals("Public: not checked", missingFacts)
    }

    @Test
    fun offlineEmptyAndLoadingUseOneShortLine() {
        assertEquals("Loading", seasonOverviewStatusText(SeasonOverviewUiState.Loading, copy))
        assertEquals("Offline", seasonOverviewStatusText(SeasonOverviewUiState.Offline, copy))
        assertEquals("No episodes yet", seasonOverviewStatusText(SeasonOverviewUiState.EmptySeason, copy))
        assertNull(seasonOverviewStatusText(SeasonOverviewUiState.Partial, copy))

        val loading = listUi(state = SeasonOverviewUiState.Loading, rows = emptyList(), episodesKnown = false)
        assertEquals("Loading", loading.statusLine)
        assertEquals(emptyList(), loading.rows)
        assertFalse(loading.downloadOffered)

        val offline = listUi(state = SeasonOverviewUiState.Offline, rows = emptyList(), episodesKnown = false)
        assertEquals("Offline", offline.statusLine)
        assertEquals(emptyList(), offline.rows)

        val empty = listUi(state = SeasonOverviewUiState.EmptySeason, rows = emptyList())
        assertEquals("No episodes yet", empty.statusLine)
        assertEquals(emptyList(), empty.rows)
    }

    @Test
    fun selectCountAndDownloadLabel() {
        val all = seasonOverviewToolbar(
            selecting = false,
            hasSelection = false,
            selectedCount = 0,
            episodeCount = 8,
            running = false,
            downloadAllowed = true,
            copy = copy,
        )
        assertEquals("Select", all.selectLabel)
        assertEquals(0, all.selectedCount)
        assertEquals(8, all.chosenCount)
        assertEquals("Download for analysis", all.downloadLabel)
        assertTrue(all.buttonEnabled)

        val included = seasonOverviewToolbar(
            selecting = false,
            hasSelection = true,
            selectedCount = 3,
            episodeCount = 8,
            running = false,
            downloadAllowed = true,
            copy = copy,
        )
        assertEquals("Download 3 for analysis", included.downloadLabel)
        assertEquals(3, included.chosenCount)

        val select = seasonOverviewToolbar(
            selecting = true,
            hasSelection = true,
            selectedCount = 2,
            episodeCount = 8,
            running = false,
            downloadAllowed = true,
            copy = copy,
        )
        assertEquals("Done", select.selectLabel)
        assertEquals(2, select.selectedCount)
        assertEquals(2, select.chosenCount)
        assertEquals("Download 2", select.downloadLabel)

        val noneSelected = seasonOverviewToolbar(
            selecting = true,
            hasSelection = false,
            selectedCount = 0,
            episodeCount = 8,
            running = false,
            downloadAllowed = true,
            copy = copy,
        )
        assertEquals("Download 0", noneSelected.downloadLabel)
        assertEquals(0, noneSelected.chosenCount)
        assertFalse(noneSelected.buttonEnabled)

        val cancel = seasonOverviewToolbar(
            selecting = true,
            hasSelection = true,
            selectedCount = 2,
            episodeCount = 8,
            running = true,
            downloadAllowed = true,
            copy = copy,
        )
        assertEquals("Cancel", cancel.downloadLabel)
        assertTrue(cancel.running)
        assertTrue(cancel.buttonEnabled)
    }

    @Test
    fun swipeActionStaysWhenReduceMotionIsOn() {
        val ui = listUi(
            rows = listOf(row(episode = 1, own = OwnAccountMarks(), detected = emptySet())),
            facts = mapOf(key(1) to facts(key(1), CatalogRead.None, emptySet(), emptySet())),
            reduceMotion = true,
        )
        assertTrue(ui.reduceMotion)
        assertTrue(ui.swipeActionsInstalled)
        assertTrue(ui.rows.all { it.swipeWatchPrivately })
        assertEquals(listOf("open", "watchPrivately", "includeInDownload"), ui.contextActions)

        val parsed = seasonOverviewReadPayload(seasonOverviewPayload(ui))
        assertEquals(true, parsed?.swipeActionsInstalled)
        assertEquals(true, parsed?.rows?.single()?.swipeWatchPrivately)
        assertEquals("Public: none", parsed?.rows?.single()?.publicLine)
        assertEquals(SeasonMarkSymbolState.Missing, parsed?.rows?.single()?.symbols?.first()?.state)
        assertEquals('M', parsed?.rows?.single()?.symbols?.first()?.state?.wireCode())
    }

    @Test
    fun payloadRoundTripKeepsATitleThatContainsSeparators() {
        val ui = listUi(
            title = "Season\n2",
            rows = listOf(
                row(
                    episode = 1,
                    title = "A\nB\u001fC",
                    own = OwnAccountMarks(confirmed = mapOf(key(1) to setOf("preview"))),
                    detected = emptySet(),
                ),
            ),
            facts = mapOf(key(1) to facts(key(1), CatalogRead.Listed, setOf("intro"), emptySet())),
        )
        val parsed = seasonOverviewReadPayload(seasonOverviewPayload(ui))
        assertEquals("Season\n2", parsed?.title)
        assertEquals("A\nB\u001fC", parsed?.rows?.single()?.title)
        assertEquals("Public: Intro", parsed?.rows?.single()?.publicLine)
        assertEquals(SeasonMarkSymbolState.OwnConfirmed, parsed?.rows?.single()?.symbols?.get(3)?.state)
        assertEquals("\u001f", seasonOverviewUnescape(seasonOverviewEscape("\u001f")))
        assertEquals("A\nB", seasonOverviewUnescape(seasonOverviewEscape("A\nB")))
    }

    @Test
    fun buildingTheRowsWritesNothing() {
        val events = mutableListOf<String>()
        val own = OwnAccountMarks(confirmed = mapOf(key(1) to setOf("intro")))
        val episode = row(episode = 1, watched = true, own = own, detected = setOf("recap"))
        val before = episode
        val facts = facts(
            key = key(1),
            publicRead = CatalogRead.Listed,
            publicTypes = setOf("outro"),
            ownConfirmed = setOf("intro"),
        )
        val ui = seasonOverviewListUi(
            title = "Season 1",
            state = SeasonOverviewUiState.Partial,
            rows = listOf(episode),
            facts = mapOf(key(1) to facts),
            selecting = false,
            hasSelection = false,
            selectedCount = 0,
            episodeCount = 1,
            running = false,
            reduceMotion = true,
            fromDownloads = false,
            downloadAllowed = true,
            selectedKeys = emptySet(),
            copy = copy,
            write = SeasonOverviewWrite { events += it },
        )
        assertEquals(emptyList(), events)
        assertEquals(before, episode)
        assertEquals(own, OwnAccountMarks(confirmed = mapOf(key(1) to setOf("intro"))))
        assertTrue(ui.rows.single().watched)
        assertEquals(SeasonMarkSymbolState.OwnConfirmed, ui.rows.single().symbols.first().state)
        assertEquals("Public: Credits", ui.rows.single().publicLine)
        assertFalse(ui.rows.single().spoken.contains("Yours"))
    }

    private fun listUi(
        title: String = "Season 1",
        state: SeasonOverviewUiState = SeasonOverviewUiState.Partial,
        rows: List<EpisodeSegmentOverview> = emptyList(),
        facts: Map<String, EpisodeCatalogFacts> = emptyMap(),
        reduceMotion: Boolean = false,
        episodesKnown: Boolean = true,
        fromDownloads: Boolean = false,
    ): SeasonOverviewListUi {
        val resolved = if (!episodesKnown && state == SeasonOverviewUiState.Loading) {
            SeasonOverviewUiState.Loading
        } else {
            state
        }
        return seasonOverviewListUi(
            title = title,
            state = resolved,
            rows = rows,
            facts = facts,
            selecting = false,
            hasSelection = false,
            selectedCount = 0,
            episodeCount = rows.size,
            running = false,
            reduceMotion = reduceMotion,
            fromDownloads = fromDownloads,
            downloadAllowed = resolved == SeasonOverviewUiState.Partial,
            selectedKeys = emptySet(),
            copy = copy,
        )
    }

    private fun row(
        episode: Int,
        title: String = "Episode $episode",
        watched: Boolean = false,
        own: OwnAccountMarks,
        detected: Set<String>,
    ): EpisodeSegmentOverview = episodeSegmentOverview(
        episode = SeasonEpisodeRef(
            season = 1,
            episode = episode,
            title = title,
            watched = watched,
            contentKey = key(episode),
        ),
        own = own,
        detectedTypes = detected,
    )

    private fun facts(
        key: String,
        publicRead: CatalogRead,
        publicTypes: Set<String>,
        ownConfirmed: Set<String>,
    ) = EpisodeCatalogFacts(
        contentKey = key,
        publicRead = publicRead,
        publicTypes = publicTypes,
        ownRead = if (ownConfirmed.isEmpty()) CatalogRead.None else CatalogRead.Listed,
        ownConfirmed = ownConfirmed,
        ownPending = emptySet(),
    )

    private fun key(episode: Int) = "tt0000101:1:$episode"
}
