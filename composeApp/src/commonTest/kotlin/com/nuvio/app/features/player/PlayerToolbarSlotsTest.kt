package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
