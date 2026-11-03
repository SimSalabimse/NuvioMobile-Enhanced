package com.nuvio.app.core.startup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SynchronousLaunchSourceTest {
    @Test
    fun firstFramePathDoesNotSuspendOrReadTheNetwork() {
        val gate = sourceFile("composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles/ProfileStartupGate.kt")
        val launch = sourceFile("composeApp/src/commonMain/kotlin/com/nuvio/app/core/startup/SynchronousLaunch.kt")
        val decision = functionBody(gate, "fun decideFirstFrame")
        assertFalse(decision.contains("suspend fun decideFirstFrame"))
        assertFalse(decision.contains("NetworkStatusRepository"))
        assertFalse(decision.contains("Supabase"))
        assertFalse(launch.contains("suspend fun prepare"))
        assertFalse(launch.contains("NetworkStatusRepository"))
        assertFalse(launch.contains("Supabase"))
        assertTrue(launch.contains("fun prepare()"))
        assertTrue(launch.contains("rememberOn = cache.rememberOn,"))
        assertFalse(launch.contains("cache.rememberOn && cache.hasEverSelectedProfile"))
        val swift = sourceFile("iosApp/iosApp/ContentView.swift")
        val coordinator = swift.substring(swift.indexOf("final class AppNavigationCoordinator"))
        val initBody = coordinator.substring(coordinator.indexOf("init() {"), coordinator.indexOf("PlayerPictureInPictureParking"))
        assertTrue(initBody.contains("coldLaunchSkipsProfilePicker()"))
        assertTrue(initBody.contains("isMainContentMounted = true"))
        assertTrue(decision.contains("StartupNetworkOutcome.Online"))
        assertTrue(decision.contains("StartupNetworkOutcome.Offline"))
        assertTrue(decision.contains("StartupNetworkOutcome.TimedOut"))
    }

    private fun sourceFile(relative: String): String {
        val file = listOf(File(relative), File("composeApp/$relative"), File("../$relative")).firstOrNull { it.isFile }
        check(file != null) { relative }
        return file.readText()
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, signature)
        val next = source.indexOf("\ninternal fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
