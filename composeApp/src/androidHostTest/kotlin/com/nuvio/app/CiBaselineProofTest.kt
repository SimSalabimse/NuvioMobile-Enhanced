package com.nuvio.app

import kotlin.test.Test
import kotlin.test.fail

class CiBaselineProofTest {
    @Test
    fun newFailureTurnsTheCheckRed() {
        fail("intentional CI proof")
    }
}
