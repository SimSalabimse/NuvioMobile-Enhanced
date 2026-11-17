package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A 402 by 874 portrait screen. Centering a 320 by 220 card in the rotated
 * 874 by 402 container starts the card at x = 277, in the trailing half, and
 * the card runs past x = 402. The captured confirmation did that. Placement
 * uses the portrait viewport instead.
 */
class ReanalyzeConfirmationFrameTest {
    @Test
    fun portraitViewportKeepsTheCardInsideAndOffTheTrailingEdge() {
        val portraitWidth = 402
        val portraitHeight = 874
        val cardWidth = 320
        val cardHeight = 220
        val shifted = confirmationFrame(
            viewportWidth = portraitHeight,
            viewportHeight = portraitWidth,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
        )
        assertEquals(277, shifted.left)
        assertEquals(91, shifted.top)
        assertFalse(shifted.inside(portraitWidth, portraitHeight))
        assertTrue(shifted.right > portraitWidth)
        assertTrue(shifted.left > portraitWidth / 2)

        val placed = confirmationFrame(
            viewportWidth = portraitWidth,
            viewportHeight = portraitHeight,
            cardWidth = cardWidth,
            cardHeight = cardHeight,
        )
        assertEquals(41, placed.left)
        assertEquals(327, placed.top)
        assertTrue(placed.inside(portraitWidth, portraitHeight))
        assertTrue(placed.left < portraitWidth / 2)
        assertTrue(placed.right <= portraitWidth)
        assertTrue(placed.bottom <= portraitHeight)
    }

    @Test
    fun aCardWiderThanTheViewportIsClampedToIt() {
        val placed = confirmationFrame(
            viewportWidth = 402,
            viewportHeight = 874,
            cardWidth = 900,
            cardHeight = 1200,
        )
        assertEquals(0, placed.left)
        assertEquals(0, placed.top)
        assertEquals(402, placed.width)
        assertEquals(874, placed.height)
        assertTrue(placed.inside(402, 874))
    }
}
