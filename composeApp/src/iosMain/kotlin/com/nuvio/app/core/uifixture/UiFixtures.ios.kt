package com.nuvio.app.core.uifixture

import com.nuvio.app.core.network.NetworkStatusRepository
import kotlin.experimental.ExperimentalNativeApi
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
@OptIn(ExperimentalNativeApi::class)
actual fun activeUiFixture(): UiFixture? {
    val decision = decideUiFixture(Platform.isDebugBinary) {
        val raw = readLaunchRaw()
        if (!raw.isNullOrBlank() && parseUiFixtureLaunch(raw) == null) {
            println("[ui-fixture] ignored unknown fixture '$raw'")
        }
        raw
    }
    UiFixtureSession.submitBarWidthDp = decision.widthDp
    UiFixtureSession.walkId = decision.walkId
    decision.fixture?.let { fixture ->
        val width = decision.widthDp?.let { "@$it" }.orEmpty()
        val walk = decision.walkId?.let { ":$it" }.orEmpty()
        println("[ui-fixture] ${fixture.wireName}$width$walk")
    }
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

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun startConnectivityFixturePoller() {
    if (!Platform.isDebugBinary || !uiFixturesActive()) return
    val documents = "${NSHomeDirectory()}/Documents"
    NSFileManager.defaultManager.createDirectoryAtPath(documents, true, null, null)
    val path = "$documents/$UI_FIXTURE_CONNECTIVITY_FILE"
    val actionPath = "$documents/$UI_FIXTURE_ACTION_FILE"
    println("[ui-fixture] connectivity file $path")
    ConnectivityFixturePoller.start(path, actionPath)
}

private object ConnectivityFixturePoller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun start(path: String, actionPath: String) {
        if (started) return
        started = true
        scope.launch {
            var last: String? = null
            var lastAction: String? = null
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
                val actionRaw = runCatching { readText(actionPath) }.getOrNull()
                if (actionRaw != null && actionRaw != lastAction) {
                    lastAction = actionRaw
                    val action = parseFixtureAction(actionRaw)
                    if (action == "dismiss") {
                        println("[ui-fixture] action dismiss")
                        UiWalkSession.dismissSignal.value = true
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
