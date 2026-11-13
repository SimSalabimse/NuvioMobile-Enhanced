package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SegmentAttributionHostTest {
    private val key = "tt0000001:1:8"
    private val scope = "account-a"

    @Test
    fun publicOnlyIsTheIntroDb() {
        val labels = attribute(public = setOf("intro"))
        assertEquals(listOf(LABEL_THE_INTRO_DB), labels.labelsFor("intro"))
        assertFalse(LABEL_YOUR_MARK in labels.labelsFor("intro"))
    }

    @Test
    fun detectedOnlyIsDetected() {
        val labels = attribute(detected = setOf("recap"))
        assertEquals(listOf(LABEL_DETECTED), labels.labelsFor("recap"))
        assertTrue(labels.own.isEmpty())
    }

    @Test
    fun ownOnlyIsYourMark() {
        val labels = attribute(manual = setOf("intro"), ledger = setOf("recap"))
        assertEquals(listOf(LABEL_YOUR_MARK), labels.labelsFor("intro"))
        assertEquals(listOf(LABEL_YOUR_MARK), labels.labelsFor("recap"))
    }

    @Test
    fun ownAndPublicBothStayVisible() {
        val labels = attribute(manual = setOf("intro"), public = setOf("intro"))
        assertEquals(listOf(LABEL_YOUR_MARK, LABEL_THE_INTRO_DB), labels.labelsFor("intro"))
    }

    @Test
    fun anotherAccountsLedgerDoesNotCount() {
        val labels = attributeEpisodeSegments(
            contentKey = key,
            accountScope = scope,
            ledgerByScope = mapOf(
                "account-b" to OwnAccountMarks(confirmed = mapOf(key to setOf("intro"))),
            ),
            manualTypes = emptySet(),
            publicTypes = setOf("intro"),
            detectedTypes = emptySet(),
        )
        assertEquals(listOf(LABEL_THE_INTRO_DB), labels.labelsFor("intro"))
        assertTrue(labels.own.isEmpty())
    }

    private fun attribute(
        manual: Set<String> = emptySet(),
        ledger: Set<String> = emptySet(),
        public: Set<String> = emptySet(),
        detected: Set<String> = emptySet(),
    ) = attributeEpisodeSegments(
        contentKey = key,
        accountScope = scope,
        ledgerByScope = mapOf(scope to OwnAccountMarks(confirmed = mapOf(key to ledger))),
        manualTypes = manual,
        publicTypes = public,
        detectedTypes = detected,
    )
}
