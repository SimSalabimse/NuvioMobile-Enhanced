package com.nuvio.app.core.automation

import com.nuvio.app.features.profiles.MAX_PROFILES

/** Hidden profile id. Outside 1..[MAX_PROFILES], so the profile picker never lists it. */
const val AUTOMATION_PROFILE_ID = 8_001

const val AUTOMATION_KEY_PREFIX = "nuvio_auto_"

const val AUTOMATION_ARGUMENT = "-NuvioAutomation"
const val AUTOMATION_ENV = "NUVIO_AUTOMATION"
const val AUTOMATION_RESET_ARGUMENT = "-NuvioAutomationReset"
const val AUTOMATION_SUBMIT_RESULT_ARGUMENT = "-NuvioAutomationSubmitResult"
const val AUTOMATION_SUBMIT_RESULT_ENV = "NUVIO_AUTOMATION_SUBMIT_RESULT"

/** Accessibility identifier on the root view. Not a visible string. */
const val AUTOMATION_ACCESSIBILITY_ID = "nuvio_automation_test_mode"

const val AUTOMATION_ANALYSIS_DIRECTORY = "NuvioAnalysisAutomation"
const val LIVE_ANALYSIS_DIRECTORY = "NuvioAnalysis"

const val ADDON_URLS_KEY = "installed_manifest_urls"
const val ADDON_ENABLED_KEY = "installed_manifest_enabled_states"

enum class AutomationStoreKind {
    /** `base_1` .. `base_6` become `base_8001`. */
    ProfileSuffix,
    /** Unscoped key is stored as `nuvio_auto_<key>`. */
    Prefixed,
    /** Same key as the owner. Writes are dropped. */
    ReadOnly,
    /** Separate directory. The owner's folder is not opened. */
    Directory,
}

data class AutomationStore(
    val id: String,
    val sampleOwnerKey: String,
    val kind: AutomationStoreKind,
)

/**
 * Every store the automation namespace has to account for.
 * Host tests resolve [sampleOwnerKey] through [isolateStorageKey].
 */
object AutomationStoreCatalog {
    val entries: List<AutomationStore> = listOf(
        AutomationStore("watch_progress", "watch_progress_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("continue_watching_prefs", "continue_watching_preferences_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("cw_enrichment", "cw_enrichment_cache_trakt_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("library", "library_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("library_display", "library_display_settings_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("library_schedule", "library_release_schedule_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("watched", "watched_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("downloads", "downloads_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("download_settings", "allow_mobile_data_downloads", AutomationStoreKind.Prefixed),
        AutomationStore("home_catalog", "catalog_settings_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("collections", "collections_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("search_history", "search_history_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("theme", "selected_theme_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("player_settings", "resize_mode_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("stream_badges", "stream_badge_rules_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("flag_ledger", "flag_submit_ledger_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("own_theintrodb", "own_theintrodb_submissions_v1", AutomationStoreKind.Prefixed),
        AutomationStore("skip_source", "skip_source_ledger_v1", AutomationStoreKind.Prefixed),
        AutomationStore("analysis_temp_ids", "analysis_temp_download_ids_v1", AutomationStoreKind.Prefixed),
        AutomationStore("theintrodb_key", "the_intro_db_api_key_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("introdb_key", "introdb_api_key_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("trakt_auth", "trakt_auth_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("trakt_library", "trakt_library_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("simkl_auth", "simkl_auth_metadata_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("mdblist_key", "mdblist_api_key_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("offline_outbox", "offline_account_outbox", AutomationStoreKind.Prefixed),
        AutomationStore("pin_cache", "profile_pin_cache_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("user_ratings", "user_ratings_disabled_providers_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("plugins", "plugins_state_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("plugin_settings", "settings_scraper", AutomationStoreKind.Prefixed),
        AutomationStore("addons", "installed_manifest_urls_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("addon_enabled", "installed_manifest_enabled_states_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("notifications", "episode_release_notifications_payload_1", AutomationStoreKind.ProfileSuffix),
        AutomationStore("sentry", "sentry_enabled", AutomationStoreKind.Prefixed),
        AutomationStore("analysis_throttle", "NuvioAnalysisThrottleLevel", AutomationStoreKind.Prefixed),
        AutomationStore("analysis_session", "NuvioAnalysisPlayerSessionOpen", AutomationStoreKind.Prefixed),
        AutomationStore("profile_payload", "profile_payload", AutomationStoreKind.ReadOnly),
        AutomationStore("anonymous_user", "anonymous_user_id", AutomationStoreKind.ReadOnly),
        AutomationStore("avatar_catalog", "avatar_catalog_payload", AutomationStoreKind.ReadOnly),
        AutomationStore("member_access", "member_access_payload", AutomationStoreKind.ReadOnly),
        AutomationStore("server", "server_backend_url", AutomationStoreKind.ReadOnly),
        AutomationStore("analysis_files", LIVE_ANALYSIS_DIRECTORY, AutomationStoreKind.Directory),
    )
}

data class AutomationLaunch(
    val active: Boolean,
    val reset: Boolean,
    val submitStatus: Int?,
)

data class AddonSeed(
    val readUrlsKey: String,
    val writeUrlsKey: String,
    val readEnabledKey: String,
    val writeEnabledKey: String,
)

object AutomationTestMode {
    private var launch = AutomationLaunch(active = false, reset = false, submitStatus = null)

    val isActive: Boolean get() = launch.active

    /** Fake HTTP status for TheIntroDB submit and map. 200 when the flag is omitted. */
    val submitStatus: Int get() = launch.submitStatus ?: 200

    fun install(parsed: AutomationLaunch) {
        launch = parsed
        if (parsed.active) {
            println(
                "nuvio-automation on namespace=$AUTOMATION_PROFILE_ID " +
                    "reset=${parsed.reset} submit=${parsed.submitStatus ?: 200}",
            )
        }
    }

    fun installForTests(active: Boolean, reset: Boolean = false, submitStatus: Int? = null) {
        install(AutomationLaunch(active = active, reset = reset, submitStatus = submitStatus))
    }

    fun resetForTests() {
        launch = AutomationLaunch(active = false, reset = false, submitStatus = null)
        TheIntroDbSubmitProbe.networkPosts = 0
    }
}

internal object TheIntroDbSubmitProbe {
    var networkPosts: Int = 0
}

fun parseAutomationLaunch(
    arguments: List<String>,
    environment: Map<String, String>,
): AutomationLaunch {
    val envOn = environment[AUTOMATION_ENV]?.trim()?.lowercase()
    val active = arguments.contains(AUTOMATION_ARGUMENT) || envOn == "1" || envOn == "true"
    if (!active) return AutomationLaunch(active = false, reset = false, submitStatus = null)
    return AutomationLaunch(
        active = true,
        reset = arguments.contains(AUTOMATION_RESET_ARGUMENT),
        submitStatus = readSubmitStatus(arguments, environment),
    )
}

internal expect fun installAutomationFromPlatform()

fun isolateStorageKey(rawKey: String, active: Boolean = AutomationTestMode.isActive): String {
    if (!active) return rawKey
    if (rawKey.startsWith(AUTOMATION_KEY_PREFIX)) return rawKey
    if (isReadOnlyStoreKey(rawKey)) return rawKey
    if (rawKey.endsWith("_$AUTOMATION_PROFILE_ID")) return rawKey
    val suffix = profileSuffix(rawKey)
    if (suffix != null) return rawKey.removeSuffix("_$suffix") + "_$AUTOMATION_PROFILE_ID"
    return AUTOMATION_KEY_PREFIX + rawKey
}

fun allowStorageWrite(rawKey: String, active: Boolean = AutomationTestMode.isActive): Boolean {
    if (!active) return true
    return !isReadOnlyStoreKey(rawKey)
}

fun automationStorageProfileId(profileId: Int, active: Boolean = AutomationTestMode.isActive): Int =
    if (active) AUTOMATION_PROFILE_ID else profileId

fun isAutomationNamespaceKey(key: String): Boolean =
    key.startsWith(AUTOMATION_KEY_PREFIX) || key.endsWith("_$AUTOMATION_PROFILE_ID")

fun analysisDirectoryName(active: Boolean = AutomationTestMode.isActive): String =
    if (active) AUTOMATION_ANALYSIS_DIRECTORY else LIVE_ANALYSIS_DIRECTORY

/**
 * Disk pointer after a session that tried to select [attempted].
 * Automation never stores the attempt.
 */
fun activeProfilePointerAfter(stored: Int, attempted: Int, active: Boolean): Int =
    if (active) stored else attempted

/**
 * Copy the owner's addon list into the automation keys once.
 * Null when the destination already has a value, or the owner id is not a real profile.
 */
fun addonSeedPlan(ownerProfileId: Int, destinationAlreadyStored: Boolean): AddonSeed? {
    if (destinationAlreadyStored) return null
    if (ownerProfileId !in 1..MAX_PROFILES) return null
    return AddonSeed(
        readUrlsKey = "${ADDON_URLS_KEY}_$ownerProfileId",
        writeUrlsKey = "${ADDON_URLS_KEY}_$AUTOMATION_PROFILE_ID",
        readEnabledKey = "${ADDON_ENABLED_KEY}_$ownerProfileId",
        writeEnabledKey = "${ADDON_ENABLED_KEY}_$AUTOMATION_PROFILE_ID",
    )
}

fun isTheIntroDbMutation(url: String): Boolean {
    val path = url.substringBefore('?').trimEnd('/')
    return path.endsWith("/submit") || path.endsWith("/map")
}

fun theIntroDbSubmitPlan(url: String, active: Boolean, requestedStatus: Int?): TheIntroDbSubmitPlan {
    if (!active || !isTheIntroDbMutation(url)) return TheIntroDbSubmitPlan.Network
    return TheIntroDbSubmitPlan.Fake(requestedStatus ?: 200)
}

sealed class TheIntroDbSubmitPlan {
    data object Network : TheIntroDbSubmitPlan()
    data class Fake(val status: Int) : TheIntroDbSubmitPlan()
}

private val readOnlyExact = setOf(
    "profile_payload",
    "anonymous_user_id",
    "avatar_catalog_payload",
    "member_access_payload",
    "member_background_catalog_payload",
    // Android server and membership keys. iOS uses the server_ prefix and the names above.
    "custom_enabled",
    "backend_url",
    "publishable_key",
    "email_password_auth",
    "tv_login",
    "watch_party",
    "discovery_url",
    "access_payload",
    "background_catalog_payload",
)

fun isReadOnlyStoreKey(rawKey: String): Boolean =
    rawKey in readOnlyExact || rawKey.startsWith("server_")

private fun profileSuffix(rawKey: String): Int? {
    val separator = rawKey.lastIndexOf('_')
    if (separator <= 0 || separator == rawKey.lastIndex) return null
    val value = rawKey.substring(separator + 1).toIntOrNull() ?: return null
    return value.takeIf { it in 1..MAX_PROFILES }
}

private fun readSubmitStatus(arguments: List<String>, environment: Map<String, String>): Int? {
    val inline = arguments.firstOrNull { it.startsWith("$AUTOMATION_SUBMIT_RESULT_ARGUMENT=") }
        ?.substringAfter('=')
        ?.trim()
    val flag = arguments.indexOf(AUTOMATION_SUBMIT_RESULT_ARGUMENT)
    val next = if (flag >= 0) arguments.getOrNull(flag + 1)?.trim() else null
    val raw = inline?.takeIf { it.isNotEmpty() }
        ?: next?.takeIf { it.isNotEmpty() && !it.startsWith("-") }
        ?: environment[AUTOMATION_SUBMIT_RESULT_ENV]?.trim()
    return raw?.toIntOrNull()?.takeIf { it in 200..599 }
}
