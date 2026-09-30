package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals

class AppVersionLabelTest {
    @Test
    fun releaseLabelIsNameAndCode() {
        assertEquals("0.5.2 (134)", appVersionDetail("0.5.2", 134))
    }

    @Test
    fun gitRevisionIsAppended() {
        assertEquals(
            "0.5.2 (134) · abc1234",
            appVersionDetail("0.5.2", 134, gitRevision = "ABC1234"),
        )
    }

    @Test
    fun dirtyTreeMarksTheRevision() {
        assertEquals(
            "0.5.2 (134) · abc1234*",
            appVersionDetail("0.5.2", 134, gitRevision = " abc1234 ", gitDirty = true),
        )
    }

    @Test
    fun invalidRevisionIsOmitted() {
        assertEquals(
            "0.5.2 (134)",
            appVersionDetail("0.5.2", 134, gitRevision = "not-a-commit", gitDirty = true),
        )
        assertEquals("", appVersionRevisionSuffix("   ", gitDirty = true))
    }
}
