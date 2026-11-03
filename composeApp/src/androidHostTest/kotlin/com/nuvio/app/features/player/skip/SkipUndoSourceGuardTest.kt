package com.nuvio.app.features.player.skip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkipUndoSourceGuardTest {
    @Test
    fun backPillHasALabelAndRole() {
        val button = source("src/commonMain/kotlin/com/nuvio/app/features/player/skip/SkipIntroButton.kt")
        val pill = button.substringAfter("visible = showUndo").substringBefore("visible = isVisible")
        assertTrue(pill.contains("Res.string.action_back"), "Back label")
        assertTrue(pill.contains("Role.Button"), "button role")
        assertTrue(pill.contains("contentDescription"), "accessibility label")
        assertTrue(button.contains("skipUndoMotionSnaps(reduceMotion)"))
        assertTrue(button.contains("EnterTransition.None"))
        assertTrue(button.contains("onSkip()"), "skip tap stays")
        assertTrue(button.contains("onDismiss: () -> Unit"), "dismiss callback stays")
    }

    @Test
    fun existingSkipActionsStayReachable() {
        val files = mapOf(
            "ui" to source("src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeUi.kt"),
            "effects" to source("src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt"),
            "stats" to source("src/commonMain/kotlin/com/nuvio/app/features/stats/WatchStats.kt"),
        )
        val rows = existingSkipActions().associateWith { action ->
            when (action) {
                ExistingSkipAction.ManualSkip -> listOf(
                    "ui" to "WatchStats.recordSkip",
                    "ui" to "playerController?.seekTo(seekMs)",
                    "ui" to "skipIntervalDismissed = true",
                    "ui" to "offerSkipFeedback(interval)",
                )
                ExistingSkipAction.Dismiss -> listOf(
                    "ui" to "onDismissSkipInterval",
                    "ui" to "dismiss type=",
                    "ui" to "skipIntervalDismissed = true",
                )
                ExistingSkipAction.AutoSkipToast -> listOf(
                    "effects" to "autoSkippedIntervals.add(current)",
                    "effects" to "WatchStats.recordSkip",
                    "effects" to "controller.seekTo(seekPositionMs)",
                    "effects" to "skipIntervalDismissed = true",
                    "effects" to "NuvioToastController.show(it, AUTO_SKIP_NOTIFICATION_DURATION_MS)",
                )
            }
        }
        assertEquals(ExistingSkipAction.entries.toList(), rows.keys.toList())
        rows.forEach { (action, checks) ->
            checks.forEach { (file, snippet) ->
                assertTrue(files.getValue(file).contains(snippet), "$action missing $snippet")
            }
        }
        assertTrue(files.getValue("stats").contains("adjustSkippedMs"), "undo uses the skip total")
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("composeApp/$relative"))
        return candidates.first { it.isFile }.readText()
    }
}
