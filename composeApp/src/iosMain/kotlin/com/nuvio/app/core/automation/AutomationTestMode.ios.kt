package com.nuvio.app.core.automation

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun installAutomationFromPlatform() {
    val launch = readAutomationLaunch()
    AutomationTestMode.install(launch)
    if (!launch.active) return
    if (launch.reset) wipeAutomationNamespace()
    seedAutomationAddons()
}

private fun readAutomationLaunch(): AutomationLaunch {
    val process = NSProcessInfo.processInfo
    val arguments = process.arguments.map { it.toString() }
    val env = process.environment
    val environment = buildMap {
        env[AUTOMATION_ENV]?.toString()?.let { put(AUTOMATION_ENV, it) }
        env[AUTOMATION_SUBMIT_RESULT_ENV]?.toString()?.let { put(AUTOMATION_SUBMIT_RESULT_ENV, it) }
    }
    return parseAutomationLaunch(arguments, environment)
}

@OptIn(ExperimentalForeignApi::class)
private fun wipeAutomationNamespace() {
    val defaults = NSUserDefaults.standardUserDefaults
    val keys = defaults.dictionaryRepresentation().keys.mapNotNull { it as? String }
    keys.filter(::isAutomationNamespaceKey).forEach { defaults.removeObjectForKey(it) }

    val support = NSSearchPathForDirectoriesInDomains(
        NSApplicationSupportDirectory,
        NSUserDomainMask,
        true,
    ).firstOrNull() as? String
    if (support != null) {
        NSFileManager.defaultManager.removeItemAtPath(
            "$support/$AUTOMATION_ANALYSIS_DIRECTORY",
            null,
        )
    }
    NSFileManager.defaultManager.removeItemAtPath(
        "${NSHomeDirectory()}/Library/Application Support/nuvio_plugin_scrapers/$AUTOMATION_PROFILE_ID",
        null,
    )
}

private fun seedAutomationAddons() {
    val defaults = NSUserDefaults.standardUserDefaults
    val payload = defaults.stringForKey("profile_payload").orEmpty()
    val ownerProfileId = Regex(""""activeProfileIndex"\s*:\s*(\d+)""")
        .find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: return
    val destination = defaults.stringForKey("${ADDON_URLS_KEY}_$AUTOMATION_PROFILE_ID")
    val plan = addonSeedPlan(ownerProfileId, destinationAlreadyStored = destination != null) ?: return
    copyAddonValue(defaults, plan.readUrlsKey, plan.writeUrlsKey)
    copyAddonValue(defaults, plan.readEnabledKey, plan.writeEnabledKey)
}

private fun copyAddonValue(defaults: NSUserDefaults, sourceKey: String, destinationKey: String) {
    if (defaults.objectForKey(destinationKey) != null) return
    val value = defaults.stringForKey(sourceKey) ?: return
    defaults.setObject(value, forKey = destinationKey)
}
