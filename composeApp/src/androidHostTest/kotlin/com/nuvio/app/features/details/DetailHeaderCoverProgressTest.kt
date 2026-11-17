package com.nuvio.app.features.details

import com.nuvio.app.features.details.components.detailHeaderCoverProgress
import com.nuvio.app.features.details.components.detailHeaderProtectedTopPx
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SE is 375×667 pt with a 20 pt status inset. 16 Pro is 402×874 pt with a 59 pt inset.
 * The header bottom is that inset plus the 56 pt back/+ row. The hero title on a wide
 * phone backdrop starts at the 16:9 band, which is the first control that can meet the row.
 */
class DetailHeaderCoverProgressTest {
    @Test
    fun seIsClearAtRestAndSolidWhenTheTitleMeetsTheRow() {
        assertPhone(Phone("SE", width = 375f, height = 667f, safeTop = 20f))
    }

    @Test
    fun proIsClearAtRestAndSolidWhenTheTitleMeetsTheRow() {
        assertPhone(Phone("16 Pro", width = 402f, height = 874f, safeTop = 59f))
    }

    @Test
    fun reduceMotionSnapsAtTheSameLineOnBothPhones() {
        listOf(
            Phone("SE", width = 375f, height = 667f, safeTop = 20f),
            Phone("16 Pro", width = 402f, height = 874f, safeTop = 59f),
        ).forEach { phone ->
            val title = titleTop(phone)
            val header = headerBottom(phone)
            val meet = title - header
            assertEquals(0f, cover(phone, scroll = 0f, reduceMotion = true))
            assertEquals(0f, cover(phone, scroll = meet - 0.5f, reduceMotion = true))
            assertEquals(1f, cover(phone, scroll = meet, reduceMotion = true))
        }
    }

    @Test
    fun theHigherOfTheTitleAndThePlayPillSetsTheLine() {
        val title = 220f
        val play = 340f
        // Visual top 220, list origin 12, already scrolled 40: the resting top is 248.
        assertEquals(248f, detailHeaderProtectedTopPx(title, play, listOriginY = 12f, scrollOffsetPx = 40f))
        assertEquals(180f, detailHeaderProtectedTopPx(headingRootTopPx = 400f, playRootTopPx = 180f, listOriginY = 0f, scrollOffsetPx = 0f))
        assertTrue(detailHeaderProtectedTopPx(Float.NaN, Float.NaN, 0f, 0f).isNaN())
        assertEquals(0f, detailHeaderCoverProgress(30f, Float.NaN, headerBottomPx = 76f, fadePx = 16f, reduceMotion = false))
    }

    private fun assertPhone(phone: Phone) {
        val title = titleTop(phone)
        val header = headerBottom(phone)
        val meet = title - header
        assertTrue(title < phone.height, phone.name)
        assertTrue(meet > 16f, phone.name)
        assertEquals(0f, cover(phone, scroll = 0f, reduceMotion = false), phone.name)
        assertEquals(0f, cover(phone, scroll = meet - 16f - 1f, reduceMotion = false), phone.name)
        assertTrue(cover(phone, scroll = meet - 16f * 0.04f, reduceMotion = false) >= 0.95f, phone.name)
        assertEquals(1f, cover(phone, scroll = meet, reduceMotion = false), phone.name)
        assertEquals(1f, cover(phone, scroll = meet + 40f, reduceMotion = false), phone.name)
    }

    private fun cover(phone: Phone, scroll: Float, reduceMotion: Boolean): Float =
        detailHeaderCoverProgress(
            scrollOffsetPx = scroll,
            protectedTopPx = titleTop(phone),
            headerBottomPx = headerBottom(phone),
            fadePx = 16f,
            reduceMotion = reduceMotion,
        )

    private fun titleTop(phone: Phone): Float = phone.width * 9f / 16f

    private fun headerBottom(phone: Phone): Float = phone.safeTop + 56f

    private data class Phone(val name: String, val width: Float, val height: Float, val safeTop: Float)
}
