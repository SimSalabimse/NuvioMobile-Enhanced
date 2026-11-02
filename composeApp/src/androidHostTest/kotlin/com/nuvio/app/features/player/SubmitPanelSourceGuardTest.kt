package com.nuvio.app.features.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmitPanelSourceGuardTest {
    @Test
    fun markProblemsClampsThroughTheDurationSlop() {
        val logic = source(
            "src/commonMain/kotlin/com/nuvio/app/features/player/PlayerToolsLogic.kt",
        )
        val clamp = logic.substringAfter("fun clampMarkTimeToDuration").substringBefore("fun markProblems")
        assertTrue(clamp.contains("MarkDurationSlopSec"))
        val problems = logic.substringAfter("fun markProblems").substringBefore("fun markLengthSec")
        assertTrue(problems.contains("clampMarkTimeToDuration"))
        assertFalse(Regex("""endSec\s*>""").containsMatchIn(problems))
        assertFalse(Regex("""startSec\s*>""").containsMatchIn(problems))
        val status = source(
            "src/commonMain/kotlin/com/nuvio/app/features/player/PlayerToolsStrips.kt",
        ).substringAfter("fun MarkStatusText").substringBefore("fun markProblemText")
        assertFalse(status.contains("maxLines"))
        assertFalse(status.contains("TextOverflow"))
        assertFalse(status.contains("Ellipsis"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("composeApp/$relative"))
        return candidates.first { it.isFile }.readText()
    }
}
