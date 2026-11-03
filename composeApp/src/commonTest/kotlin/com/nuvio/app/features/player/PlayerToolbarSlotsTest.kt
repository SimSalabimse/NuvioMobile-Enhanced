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
    fun `one flag across layout submit tools orientation and the bottom pin`() {
        val layouts = listOf(false, true)
        val folds = listOf(false, true)
        val portraits = listOf(false, true)
        val submits = listOf(false, true)
        val tools = listOf(false, true)
        val bars = listOf(false, true)
        val shown = listOf(false, true)
        val pinned = listOf(false, true)
        for (legacy in layouts) {
            for (folded in folds) {
                for (portrait in portraits) {
                    for (submitAvailable in submits) {
                        for (toolsOpen in tools) {
                            for (submitBarOpen in bars) {
                                for (controlsShown in shown) {
                                    for (bottomSubmitPinned in pinned) {
                                        val entries = visiblePlayerFlagEntries(
                                            legacy = legacy,
                                            folded = folded,
                                            portrait = portrait,
                                            submitAvailable = submitAvailable,
                                            toolsOpen = toolsOpen,
                                            submitBarOpen = submitBarOpen,
                                            controlsShown = controlsShown,
                                            bottomSubmitPinned = bottomSubmitPinned,
                                        )
                                        assertTrue(entries.size <= 1, entries.toString())
                                        assertFalse(entries.size > 1)
                                        val flipped = visiblePlayerFlagEntries(
                                            legacy = legacy,
                                            folded = folded,
                                            portrait = !portrait,
                                            submitAvailable = submitAvailable,
                                            toolsOpen = toolsOpen,
                                            submitBarOpen = submitBarOpen,
                                            controlsShown = controlsShown,
                                            bottomSubmitPinned = !bottomSubmitPinned,
                                        )
                                        assertEquals(entries, flipped)
                                        val chromeUp = submitAvailable && (controlsShown || submitBarOpen) && !(toolsOpen && !submitBarOpen)
                                        if (chromeUp) {
                                            assertEquals(1, entries.size)
                                            assertEquals(
                                                when {
                                                    folded -> PlayerFlagEntry.FoldRow
                                                    legacy -> PlayerFlagEntry.LegacyHeader
                                                    else -> PlayerFlagEntry.TopPill
                                                },
                                                entries.single(),
                                            )
                                        } else {
                                            assertTrue(entries.isEmpty())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
