package com.nuvio.app.core.uifixture

import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.DownloadsRoute

/**
 * Debug-only screenshot states. Release never reads the launch argument or the
 * connectivity file: [decideUiFixture] returns before the reader runs.
 *
 * Launch with `-NuvioUiFixture <name>` or the environment variable
 * [UI_FIXTURE_ENVIRONMENT]. A sibling file [UI_FIXTURE_CONNECTIVITY_FILE] in
 * Documents (`online` / `offline`) drives the real connectivity watcher.
 */
enum class UiFixture(
    val wireName: String,
    val startsOnline: Boolean,
) {
    HomeOnline("home-online", startsOnline = true),
    HomeOffline("home-offline", startsOnline = false),
    DetailsOnline("details-online", startsOnline = true),
    DetailsOffline("details-offline", startsOnline = false),
    DetailsMovieOffline("details-movie-offline", startsOnline = false),
    Downloads("downloads", startsOnline = true),
    LongTitles("long-titles", startsOnline = true),
    SmartDownloads("smart-downloads", startsOnline = true),
    PlayerSubmitBar("player-submit-bar", startsOnline = true),
    PlayerSubmitBarLong("player-submit-bar-long", startsOnline = true),
    SeasonOverview("season-overview", startsOnline = true),
    ;

    fun launchRoute(): AppRoute? = when (this) {
        HomeOnline, HomeOffline, LongTitles, PlayerSubmitBar, PlayerSubmitBarLong -> null
        DetailsOnline, DetailsOffline, SmartDownloads, SeasonOverview -> DetailRoute(
            type = UiFixtureCatalog.SERIES_TYPE,
            id = UiFixtureCatalog.SERIES_ID,
            title = UiFixtureCatalog.SERIES_TITLE,
        )
        DetailsMovieOffline -> DetailRoute(
            type = UiFixtureCatalog.MOVIE_TYPE,
            id = UiFixtureCatalog.MOVIE_ID,
            title = UiFixtureCatalog.MOVIE_TITLE,
        )
        Downloads -> DownloadsRoute()
    }

    companion object {
        fun fromWireName(raw: String?): UiFixture? {
            val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wireName == name }
        }
    }
}

const val UI_FIXTURE_ARGUMENT = "-NuvioUiFixture"
const val UI_FIXTURE_ENVIRONMENT = "NUVIO_UI_FIXTURE"
const val UI_FIXTURE_CONNECTIVITY_FILE = "nuvio-ui-connectivity.txt"
const val UI_FIXTURE_CONNECTIVITY_POLL_MS = 300L

data class UiFixtureDecision(
    val fixture: UiFixture?,
    val widthDp: Int? = null,
) {
    val status: String
        get() = fixture?.wireName ?: "disabled"
}

/** Landscape widths the submit-bar fixture can pin with a `@dp` suffix. */
internal const val PlayerSubmitBarDefaultWidthDp = 844
internal const val PlayerSubmitBarLongDefaultWidthDp = 667

internal fun UiFixture.acceptsWidthSuffix(): Boolean =
    this == UiFixture.PlayerSubmitBar || this == UiFixture.PlayerSubmitBarLong

internal fun UiFixture.resolvedSubmitBarWidthDp(suffix: Int?): Int? = when (this) {
    UiFixture.PlayerSubmitBar -> suffix ?: PlayerSubmitBarDefaultWidthDp
    UiFixture.PlayerSubmitBarLong -> suffix ?: PlayerSubmitBarLongDefaultWidthDp
    else -> null
}

/**
 * Release gate. When [isDebugBinary] is false the [readRaw] lambda is not called,
 * so a release binary does not touch process arguments or the environment.
 */
fun decideUiFixture(isDebugBinary: Boolean, readRaw: () -> String?): UiFixtureDecision {
    if (!isDebugBinary) return UiFixtureDecision(null)
    val request = parseUiFixtureRequest(readRaw()) ?: return UiFixtureDecision(null)
    return UiFixtureDecision(request.fixture, request.widthDp)
}

data class UiFixtureRequest(
    val fixture: UiFixture,
    val widthDp: Int?,
)

/**
 * A bare name, `-NuvioUiFixture <name>`, or `NUVIO_UI_FIXTURE=<name>`.
 * `player-submit-bar@667` is [UiFixture.PlayerSubmitBar] at 667 dp.
 */
fun parseUiFixtureLaunch(raw: String?): UiFixture? = parseUiFixtureRequest(raw)?.fixture

fun parseUiFixtureRequest(raw: String?): UiFixtureRequest? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val value = when {
        text.startsWith("$UI_FIXTURE_ARGUMENT ") -> text.substringAfter(' ').trim()
        text.startsWith("$UI_FIXTURE_ARGUMENT=") -> text.substringAfter('=').trim()
        text.startsWith("$UI_FIXTURE_ENVIRONMENT=") -> text.substringAfter('=').trim()
        else -> text
    }.substringBefore('\n').trim()
    val (name, width) = splitUiFixtureWidth(value)
    val fixture = UiFixture.fromWireName(name) ?: return null
    if (width != null && !fixture.acceptsWidthSuffix()) return null
    return UiFixtureRequest(fixture, width)
}

private fun splitUiFixtureWidth(value: String): Pair<String, Int?> {
    val at = value.lastIndexOf('@')
    if (at <= 0) return value to null
    val width = value.substring(at + 1).toIntOrNull()
    if (width == null || width !in 1..4_000) return value to null
    return value.substring(0, at) to width
}

/**
 * Environment wins when it is non-blank, otherwise the `-NuvioUiFixture` argument.
 * Unknown names are ignored.
 */
fun parseUiFixtureArguments(arguments: List<String>, environmentValue: String?): UiFixture? {
    parseUiFixtureLaunch(environmentValue)?.let { return it }
    val flag = arguments.indexOf(UI_FIXTURE_ARGUMENT)
    if (flag >= 0) return parseUiFixtureLaunch(arguments.getOrNull(flag + 1))
    val inline = arguments.firstOrNull { argument ->
        argument.startsWith("$UI_FIXTURE_ARGUMENT=")
    }
    return parseUiFixtureLaunch(inline)
}

/** Words the connectivity file and the script agree on. Null means "leave it". */
fun parseConnectivityWord(raw: String?): Boolean? {
    val word = raw?.trim()?.lowercase()?.substringBefore('\n')?.trim().orEmpty()
    return when (word) {
        "online", "on", "true", "yes", "1" -> true
        "offline", "off", "false", "no", "0" -> false
        else -> null
    }
}

object UiFixtureSession {
    var pendingRoute: AppRoute? = null
    var submitBarWidthDp: Int? = null
}

expect fun activeUiFixture(): UiFixture?

internal expect fun startConnectivityFixturePoller()

private val capturedFixture: UiFixture? by lazy { activeUiFixture() }

fun currentUiFixture(): UiFixture? = capturedFixture

fun uiFixturesActive(): Boolean = currentUiFixture() != null
