package com.nuvio.app.features.details

import com.nuvio.app.features.player.skip.AccountSegment
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
        val symbols = seasonOverviewSymbols(row.marks, publicTypes = emptySet(), copy)
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
                SeasonSegmentPresence.Yours,
                SeasonSegmentPresence.Yours,
                SeasonSegmentPresence.Missing,
                SeasonSegmentPresence.Missing,
            ),
            symbols.map { it.presence },
        )
        assertEquals("Intro:Yours", symbols[0].token)
        assertEquals('Y', symbols[0].presence.wireCode())
        assertEquals('Y', symbols[1].presence.wireCode())
        assertEquals('M', symbols[2].presence.wireCode())
        assertEquals('M', symbols[3].presence.wireCode())
        assertEquals("Intro: yours", symbols[0].accessibility)
        assertEquals("Recap: yours", symbols[1].accessibility)
        assertEquals("Credits: missing", symbols[2].accessibility)
        assertEquals("Preview: missing", symbols[3].accessibility)
        assertFalse(symbols[0].accessibility.contains("Public"))

        val publishedSymbols = seasonOverviewSymbols(
            marks = row.marks,
            publicTypes = setOf("outro", "preview"),
            copy = copy,
        )
        assertEquals(SeasonSegmentPresence.Yours, publishedSymbols[0].presence)
        assertEquals(SeasonSegmentPresence.Public, publishedSymbols[2].presence)
        assertEquals("Credits: public", publishedSymbols[2].accessibility)
        assertEquals(SeasonSegmentPresence.Public, publishedSymbols[3].presence)
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
        assertEquals("You submitted: Intro, Recap, Credits, Preview", ownedRow.yoursLine)
        assertTrue(ownedRow.symbols.all { it.presence == SeasonSegmentPresence.Yours })
        assertFalse(ownedRow.publicLine.contains("yours"))
        assertTrue(ownedRow.spoken.contains("You submitted: Intro, Recap, Credits, Preview"))
        assertTrue(ownedRow.spoken.contains("Intro: yours"))
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
        assertEquals("", publishedRow.yoursLine)
        assertTrue(publishedRow.symbols.all { it.presence == SeasonSegmentPresence.Public })
        assertEquals("Intro: public", publishedRow.symbols[0].accessibility)
        assertFalse(publishedRow.publicLine.contains("submitted"))
        assertFalse(publishedRow.publicLine.contains("pending"))
        assertFalse(publishedRow.yoursLine.contains("Public"))

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
        assertEquals(SeasonSegmentPresence.Yours, notChecked.rows.single().symbols[0].presence)
        assertEquals("You submitted: Intro", notChecked.rows.single().yoursLine)
        assertEquals(SeasonSegmentPresence.Missing, notChecked.rows.single().symbols[1].presence)

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
        assertFalse(seasonOverviewPlaysSymbolEffects(ui.reduceMotion))
        assertTrue(seasonOverviewPlaysSymbolEffects(reduceMotion = false))
        assertTrue(ui.swipeActionsInstalled)
        assertTrue(ui.rows.all { it.swipeWatchPrivately })
        assertEquals(listOf("open", "watchPrivately", "includeInDownload"), ui.contextActions)

        val parsed = seasonOverviewReadPayload(seasonOverviewPayload(ui))
        assertEquals(true, parsed?.swipeActionsInstalled)
        assertEquals(true, parsed?.rows?.single()?.swipeWatchPrivately)
        assertEquals("Public: none", parsed?.rows?.single()?.publicLine)
        assertEquals(SeasonSegmentPresence.Missing, parsed?.rows?.single()?.symbols?.first()?.presence)
        assertEquals('M', parsed?.rows?.single()?.symbols?.first()?.presence?.wireCode())
        assertEquals("", parsed?.rows?.single()?.yoursLine)
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
        assertEquals(SeasonSegmentPresence.Yours, parsed?.rows?.single()?.symbols?.get(3)?.presence)
        assertEquals(SeasonSegmentPresence.Public, parsed?.rows?.single()?.symbols?.first()?.presence)
        assertEquals("You submitted: Preview", parsed?.rows?.single()?.yoursLine)
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
        assertTrue(ui.rows.single().spoken.startsWith("1, Watched,"))
        assertEquals(SeasonSegmentPresence.Yours, ui.rows.single().symbols.first().presence)
        assertEquals("Public: Credits", ui.rows.single().publicLine)
        assertEquals("You submitted: Intro", ui.rows.single().yoursLine)
        assertFalse(ui.rows.single().publicLine.contains("yours"))
        assertEquals("Intro: yours", ui.rows.single().symbols.first().accessibility)
        ui.rows.single().symbols.forEach { symbol ->
            assertTrue(ui.rows.single().spoken.contains(symbol.accessibility))
        }
    }

    @Test
    fun accessibilityScaleWrapsAndTheSheetKeepsBothHeights() {
        assertTrue(seasonOverviewWrapsSymbols(3.12f))
        assertTrue(seasonOverviewWrapsSymbols(1.5f))
        assertFalse(seasonOverviewWrapsSymbols(1f))
        assertFalse(seasonOverviewSkipsPartialDetent())
        assertTrue(seasonOverviewFillsNativeDetent(nativeList = true, nativeSheet = true))
        assertFalse(seasonOverviewFillsNativeDetent(nativeList = true, nativeSheet = false))
        assertFalse(seasonOverviewFillsNativeDetent(nativeList = false, nativeSheet = true))

        val selecting = seasonOverviewListUi(
            title = "Season 1",
            state = SeasonOverviewUiState.Partial,
            rows = listOf(row(episode = 1, own = OwnAccountMarks(), detected = emptySet())),
            facts = mapOf(key(1) to facts(key(1), CatalogRead.None, emptySet(), emptySet())),
            selecting = true,
            hasSelection = true,
            selectedCount = 1,
            episodeCount = 1,
            running = false,
            reduceMotion = false,
            fromDownloads = false,
            downloadAllowed = true,
            selectedKeys = setOf(key(1)),
            copy = copy,
        )
        assertTrue(selecting.rows.single().spoken.startsWith("Remove from download. "))
        assertEquals("Download 1", selecting.downloadLabel)
    }

    @Test
    fun yoursLineCountsEveryOwnSegmentAndIgnoresPublicTimes() {
        val contentKey = key(4)
        val episode = episodeSegmentOverview(
            episode = SeasonEpisodeRef(1, 4, "Two intros", false, contentKey),
            own = OwnAccountMarks(
                confirmed = mapOf(contentKey to setOf("intro")),
                confirmedRows = mapOf(
                    contentKey to listOf(
                        AccountSegment("intro-a", "intro", 0L, 1_000L),
                        AccountSegment("intro-b", "intro", 8_000L, 9_000L),
                    ),
                ),
            ),
            detectedTypes = emptySet(),
        )
        assertEquals(2, episode.ownCounts["intro"])
        val ui = listUi(
            rows = listOf(episode),
            facts = mapOf(
                contentKey to facts(contentKey, CatalogRead.Listed, setOf("intro", "outro"), setOf("intro")),
            ),
        )
        val shown = ui.rows.single()
        assertEquals("You submitted: Intro ×2", shown.yoursLine)
        assertFalse(shown.yoursLine.contains("Credits"))
        assertEquals(SeasonSegmentPresence.Yours, shown.symbols[0].presence)
        assertEquals(SeasonSegmentPresence.Public, shown.symbols[2].presence)
        assertEquals("4", shown.episodeNumber.toString())
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
