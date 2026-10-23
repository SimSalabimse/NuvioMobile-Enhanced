package com.nuvio.app.features.details

import com.nuvio.app.features.details.components.filterCast
import com.nuvio.app.core.ui.foldForSearch
import kotlin.test.Test
import kotlin.test.assertEquals

class CastSearchTest {
    private val cast = listOf(
        MetaPerson(name = "Simon Baker", role = "Patrick Jane"),
        MetaPerson(name = "Robin Tunney", role = "Teresa Lisbon"),
        MetaPerson(name = "Zoë Kravitz", role = "Bonnie"),
        MetaPerson(name = "Penélope Cruz", role = "María Elena"),
    ).withIndex().toList()

    @Test
    fun `folds case and accents`() {
        assertEquals("zoe penelope maria strasse", foldForSearch("Zoë PENÉLOPE María Straße"))
    }

    @Test
    fun `matches actor or character without accents or case`() {
        assertEquals(listOf("Zoë Kravitz"), filterCast(cast, "ZOE").map { it.value.name })
        assertEquals(listOf("Penélope Cruz"), filterCast(cast, "maria").map { it.value.name })
        assertEquals(listOf("Robin Tunney"), filterCast(cast, "lisbon").map { it.value.name })
        assertEquals(4, filterCast(cast, "  ").size)
        assertEquals(0, filterCast(cast, "nobody").size)
    }

    @Test
    fun `keeps the original index`() {
        assertEquals(listOf(3), filterCast(cast, "cruz").map { it.index })
    }
}
