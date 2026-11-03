package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerToolbarSlotsTest {
    @Test
    fun `top pill keeps a fixed order and drops missing actions`() {
        assertEquals(
            listOf(
                PlayerToolbarSlot.Download,
                PlayerToolbarSlot.PictureInPicture,
                PlayerToolbarSlot.Rotate,
                PlayerToolbarSlot.Party,
                PlayerToolbarSlot.Cast,
                PlayerToolbarSlot.Flag,
                PlayerToolbarSlot.Lock,
            ),
            playerToolbarSlots(
                download = true,
                pictureInPicture = true,
                rotate = true,
                party = true,
                cast = true,
                flag = true,
            ),
        )
    }

    @Test
    fun `flag stays immediately before lock when earlier actions drop out`() {
        val slots = playerToolbarSlots(
            download = false,
            pictureInPicture = false,
            rotate = true,
            party = false,
            cast = false,
            flag = true,
        )
        assertEquals(
            listOf(PlayerToolbarSlot.Rotate, PlayerToolbarSlot.Flag, PlayerToolbarSlot.Lock),
            slots,
        )
        assertEquals(PlayerToolbarSlot.Lock, slots.last())
        assertEquals(PlayerToolbarSlot.Flag, slots[slots.lastIndex - 1])
    }

    @Test
    fun `hidden flag drops out and lock stays at the trailing edge`() {
        val slots = playerToolbarSlots(download = true, cast = true, flag = false)
        assertEquals(
            listOf(PlayerToolbarSlot.Download, PlayerToolbarSlot.Cast, PlayerToolbarSlot.Lock),
            slots,
        )
        assertFalse(PlayerToolbarSlot.Flag in slots)
        assertEquals(PlayerToolbarSlot.Lock, slots.last())
    }

    @Test
    fun `submit intro stays visible until a saved layout hides it`() {
        val fresh = PlayerToolbarLayout.decode(null)
        val savedWithoutSubmitIntro = fresh.encode()
            .split(',')
            .filterNot { it.substringBefore(':') == PlayerToolbarButton.SUBMIT_INTRO.storageId }
            .joinToString(",")
        val untouched = PlayerToolbarLayout.decode(savedWithoutSubmitIntro)
        val off = untouched.withVisibility(PlayerToolbarButton.SUBMIT_INTRO, false)
        val onAgain = off.withVisibility(PlayerToolbarButton.SUBMIT_INTRO, true)
        val explicitOff = PlayerToolbarLayout.decode("$savedWithoutSubmitIntro,submit_intro:h")
        val cases = listOf(
            false to fresh to true,
            false to untouched to true,
            false to off to false,
            false to onAgain to true,
            false to explicitOff to false,
            true to off to true,
        )
        cases.forEach { (legacyAndLayout, flagShown) ->
            val (legacy, layout) = legacyAndLayout
            assertEquals(
                flagShown,
                submitIntroFlagShown(legacyLayout = legacy, layout = layout),
                "legacy=$legacy visible=${layout.visibleButtons}",
            )
        }
        assertTrue(PlayerToolbarButton.SUBMIT_INTRO in PlayerToolbarLayout.Default.visibleButtons)
        val otherDefaults = PlayerToolbarButton.entries.filter { it != PlayerToolbarButton.SUBMIT_INTRO }
        otherDefaults.forEach { button ->
            assertEquals(
                button == PlayerToolbarButton.NEXT_EPISODE ||
                    button == PlayerToolbarButton.SPEED ||
                    button == PlayerToolbarButton.RESIZE ||
                    button == PlayerToolbarButton.SUBTITLES ||
                    button == PlayerToolbarButton.CHAPTERS,
                button in PlayerToolbarLayout.Default.visibleButtons,
                button.storageId,
            )
        }
    }

    @Test
    fun `each layout has exactly one flag home`() {
        assertEquals(PlayerFlagHome.LegacyHeader, playerFlagHome(useLegacyLayout = true, submitAvailable = true))
        assertEquals(PlayerFlagHome.TopPill, playerFlagHome(useLegacyLayout = false, submitAvailable = true))
        assertEquals(PlayerFlagHome.Hidden, playerFlagHome(useLegacyLayout = true, submitAvailable = false))
        assertEquals(PlayerFlagHome.Hidden, playerFlagHome(useLegacyLayout = false, submitAvailable = false))
        assertTrue(
            listOf(
                playerFlagHome(useLegacyLayout = true, submitAvailable = true),
                playerFlagHome(useLegacyLayout = false, submitAvailable = true),
            ).distinct().size == 2,
        )
    }
}
