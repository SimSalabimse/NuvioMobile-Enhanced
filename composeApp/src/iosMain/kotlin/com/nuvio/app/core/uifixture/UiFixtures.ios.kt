package com.nuvio.app.core.uifixture

import com.nuvio.app.core.network.NetworkStatusRepository
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * Debug binaries only. [decideUiFixture] does not call [readLaunchRaw] when
 * [Platform.isDebugBinary] is false, so Release never reads arguments or the environment.
 */
actual fun activeUiFixture(): UiFixture? {
    val decision = decideUiFixture(Platform.isDebugBinary) {
        val raw = readLaunchRaw()
        if (!raw.isNullOrBlank() && parseUiFixtureLaunch(raw) == null) {
            println("[ui-fixture] ignored unknown fixture '$raw'")
        }
        raw
    }
    decision.fixture?.let { fixture -> println("[ui-fixture] ${fixture.wireName}") }
    return decision.fixture
}

private fun readLaunchRaw(): String? {
    val environment = NSProcessInfo.processInfo.environment[UI_FIXTURE_ENVIRONMENT]?.toString()
    if (!environment.isNullOrBlank()) return environment
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val flag = arguments.indexOf(UI_FIXTURE_ARGUMENT)
    if (flag >= 0) return arguments.getOrNull(flag + 1)
    return arguments.firstOrNull { argument -> argument.startsWith("$UI_FIXTURE_ARGUMENT=") }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun startConnectivityFixturePoller() {
    if (!Platform.isDebugBinary || !uiFixturesActive()) return
    val documents = "${NSHomeDirectory()}/Documents"
    NSFileManager.defaultManager.createDirectoryAtPath(documents, true, null, null)
    val path = "$documents/$UI_FIXTURE_CONNECTIVITY_FILE"
    println("[ui-fixture] connectivity file $path")
    ConnectivityFixturePoller.start(path)
}

private object ConnectivityFixturePoller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start(path: String) {
        if (started) return
        started = true
        scope.launch {
            var last: String? = null
            while (true) {
                val raw = runCatching { readText(path) }.getOrNull()
                if (raw != null && raw != last) {
                    last = raw
                    val online = parseConnectivityWord(raw)
                    if (online != null) {
                        println("[ui-fixture] connectivity ${if (online) "online" else "offline"}")
                        NetworkStatusRepository.submitFixturePath(online)
                    }
                }
                delay(UI_FIXTURE_CONNECTIVITY_POLL_MS)
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)?.toString()
}
