package com.nuvio.app.features.debrid

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Play and download entry points must go through DebridSourceFallback.playable, so a source
 * that isn't cached falls back and ends with the "Add to TorBox" offer. Only these files may
 * call the resolver directly.
 */
class DebridSourceGuardTest {
    private val allowed = mapOf(
        "DirectDebridResolver.kt" to "defines it",
        "DebridSourceFallback.kt" to "the shared fallback path",
        "DirectDebridStreamPreparer.kt" to "background pre-resolve, plays nothing",
        "SiblingClipReference.kt" to "intro/credits analysis, not play or download",
        "DownloadLinkRefresh.kt" to "refreshes the same file's link; another source would not resume",
    )

    @Test
    fun noEntryPointBypassesTheFallback() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "run from composeApp: ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { it.readText().contains("resolveToPlayableStream(") }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), offenders, "use DebridSourceFallback.playable instead of resolveToPlayableStream")
    }
}
