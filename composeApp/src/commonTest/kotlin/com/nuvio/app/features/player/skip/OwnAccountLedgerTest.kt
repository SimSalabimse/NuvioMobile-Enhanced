package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OwnAccountLedgerTest {
    @Test
    fun scopesDifferByAccountAndNeverStoreTheKey() {
        val firstKey = "account-alpha-sample"
        val secondKey = "account-beta-sample"
        val first = theIntroDbAccountScope(firstKey)
        val second = theIntroDbAccountScope(secondKey)
        assertNotEquals(first, second)
        assertEquals("", theIntroDbAccountScope("  "))
        assertFalse(first.contains(firstKey))
        assertFalse(second.contains(secondKey))
        val encoded = encodeOwnAccountLedger(
            mapOf(
                first to OwnAccountMarks(confirmed = mapOf("tt1:1:1" to setOf("intro"))),
                second to OwnAccountMarks(pending = mapOf("tt1:1:1" to setOf("recap"))),
            ),
        )
        assertFalse(encoded.contains(firstKey))
        assertFalse(encoded.contains(secondKey))
        val decoded = decodeOwnAccountLedger(encoded)
        assertEquals(setOf("intro"), decoded.getValue(first).confirmed.getValue("tt1:1:1"))
        assertTrue(decoded.getValue(first).pending.isEmpty())
        assertEquals(setOf("recap"), decoded.getValue(second).pending.getValue("tt1:1:1"))
        assertTrue(decoded.getValue(second).confirmed.isEmpty())
    }

    @Test
    fun unscopedLegacyLinesAreNotThisAccount() {
        val decoded = decodeOwnAccountLedger("tt1:1:1=intro,outro")
        assertTrue(decoded.isEmpty())
    }
}
