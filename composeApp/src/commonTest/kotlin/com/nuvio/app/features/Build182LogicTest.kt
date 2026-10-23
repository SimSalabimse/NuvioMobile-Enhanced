package com.nuvio.app.features

import com.nuvio.app.features.downloads.AutopilotPlan
import com.nuvio.app.features.home.components.tonightMinutes
import com.nuvio.app.features.settings.keepFreePreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Build182LogicTest {
    @Test
    fun tonightStartsWithTheLastPickOrOneHour() {
        assertEquals(60, tonightMinutes(null))
        assertEquals(90, tonightMinutes("90"))
        assertEquals(60, tonightMinutes("45"))
    }

    @Test
    fun keepFreePreviewSaysWhatWouldGo() {
        assertEquals("Checking…", keepFreePreview(10, null))
        assertTrue(keepFreePreview(10, AutopilotPlan.NONE).startsWith("Nothing would be removed now"))
        assertTrue(keepFreePreview(10, AutopilotPlan(listOf("a", "b"), 3_000_000_000L)).startsWith("Would remove 2 watched episodes, "))
        assertTrue(keepFreePreview(0, AutopilotPlan(listOf("a"), 1L)).startsWith("Watched downloads are never deleted"))
    }
}
