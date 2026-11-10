package com.nuvio.app.features.autosync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iOS player cannot be compiled on the host. These assertions pin the call
 * site that 2e76ccee1 used: the session receives the plain controller.
 */
class IosAutoSyncCallSiteTest {
    @Test
    fun iosSessionReceivesThePlainController() {
        val engine = locate(
            "composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt",
        ).readText()
        assertTrue(engine.contains("latestOnControllerReady.value(controller)"))
        assertFalse(engine.contains("AutoSyncMpvCoordinator"))
        assertFalse(engine.contains("AutoSyncMpvEngineController"))
        assertFalse(engine.contains("replaceExternalSubtitle"))

        val main = locate(
            "composeApp/src/iosMain/kotlin/com/nuvio/app/MainViewController.kt",
        ).readText()
        assertFalse(main.contains("AutoSyncPreferencesIos"))
    }

    @Test
    fun iosEmbeddedLoaderIsAStubThePlayerDoesNotCall() {
        val loader = locate(
            "composeApp/src/iosMain/kotlin/com/nuvio/app/features/autosync/EmbeddedSubtitleTimelineLoader.ios.kt",
        ).readText()
        assertTrue(loader.contains("IndexedEmbeddedTimeline? = null"))
        val engine = locate(
            "composeApp/src/iosMain/kotlin/com/nuvio/app/features/player/PlayerEngine.ios.kt",
        ).readText()
        assertFalse(engine.contains("EmbeddedSubtitleTimelineLoader"))
    }

    private fun locate(relative: String): File {
        val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." })
        return generateSequence(start) { it.parentFile }
            .take(6)
            .map { File(it, relative) }
            .firstOrNull { it.isFile }
            ?: error("Missing $relative from ${start.absolutePath}")
    }
}
