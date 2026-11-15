package com.nuvio.app.features.home

import com.nuvio.app.core.ui.posterZoomPreviewFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContinueWatchingPreviewTest {
    @Test
    fun aMissingStillFallsThroughToTheBackdropThenThePosterThenTheTitle() {
        val urls = continueWatchingPreviewUrls(
            anchorImageUrl = null,
            episodeStill = " ",
            backdrop = "https://images.example/backdrop.jpg",
            poster = "https://images.example/poster.jpg",
            imageUrl = null,
        )
        assertEquals(listOf("https://images.example/backdrop.jpg", "https://images.example/poster.jpg"), urls)

        assertEquals(
            emptyList(),
            continueWatchingPreviewUrls(
                anchorImageUrl = " ",
                episodeStill = null,
                backdrop = null,
                poster = null,
                imageUrl = null,
            ),
        )
    }

    @Test
    fun thePressedCardsOwnAddressStaysFirst() {
        val urls = continueWatchingPreviewUrls(
            anchorImageUrl = "https://images.example/still.jpg",
            episodeStill = "https://images.example/still.jpg",
            backdrop = "https://images.example/backdrop.jpg",
            poster = null,
            imageUrl = null,
        )
        assertEquals(
            listOf("https://images.example/still.jpg", "https://images.example/backdrop.jpg"),
            urls,
        )
    }

    @Test
    fun aTallMenuKeepsAPreviewInsteadOfHidingTheCard() {
        val fit = posterZoomPreviewFit(
            availableDp = 420f,
            normalContentDp = 520f,
            compactContentDp = 480f,
            preferredPosterDp = 180f,
            minimumPreviewDp = 96f,
        )
        assertTrue(fit.previewVisible)
        assertTrue(fit.posterHeightDp >= 72f)
        assertTrue(fit.compactRows)
    }
}
