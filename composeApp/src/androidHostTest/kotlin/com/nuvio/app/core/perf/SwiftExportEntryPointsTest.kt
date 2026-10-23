package com.nuvio.app.core.perf

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwiftExportEntryPointsTest {
    @Test
    fun performanceCodeIsNotASwiftEntryPoint() {
        val file = listOf(
            File("build/generated/swift-export/entry-points.txt"),
            File("composeApp/build/generated/swift-export/entry-points.txt"),
        ).first { it.isFile }
        val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("//") }
        assertTrue(lines.any { it == "function com.nuvio.app.MainViewController" })
        assertTrue(lines.any { it == "callable com.nuvio.app.features.player.NuvioPlayerBridge.*" })
        assertTrue(lines.any { it == "callable com.nuvio.app.navigation.AppRoute.*" })
        assertTrue(lines.any { it == "callable com.nuvio.app.navigation.HomescreenSettingsRoute.*" })
        assertTrue(lines.any { it == "callable com.nuvio.app.navigation.TabsRoute.*" })
        assertTrue(lines.any { it == "callable com.nuvio.app.navigation.PlayerRoute.*" })
        assertFalse(lines.any { "com.nuvio.app.core.perf" in it })
        assertFalse(lines.any { "compactArtworkUrl" in it || "TmdbImageSizing" in it || "PlaybackQuietMode" in it })
        assertFalse(lines.any { it.startsWith("callable com.nuvio.app.features.home.HomeRepository") })
        assertFalse(lines.any { it.endsWith(".Color") || it.endsWith(".String") || it.endsWith(".first") })
    }
}
