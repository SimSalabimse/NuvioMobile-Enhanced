package com.nuvio.app.core.automation

import com.nuvio.app.features.player.skip.TheIntroDb
import com.nuvio.app.features.player.skip.TheIntroDbSubmitResult
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationTestModeTest {
    @AfterTest
    fun reset() {
        AutomationTestMode.resetForTests()
    }

    @Test
    fun everyStoreKeyResolvesToTheAutomationNamespace() {
        AutomationTestMode.installForTests(active = true)
        AutomationStoreCatalog.entries.forEach { store ->
            val isolated = isolateStorageKey(store.sampleOwnerKey)
            when (store.kind) {
                AutomationStoreKind.ProfileSuffix -> {
                    assertTrue(isolated.endsWith("_$AUTOMATION_PROFILE_ID"), store.id)
                    assertFalse(isolated.endsWith("_1"), store.id)
                    assertEquals(isolated, isolateStorageKey(isolated), store.id)
                    assertTrue(allowStorageWrite(store.sampleOwnerKey), store.id)
                }
                AutomationStoreKind.Prefixed -> {
                    assertEquals(AUTOMATION_KEY_PREFIX + store.sampleOwnerKey, isolated, store.id)
                    assertEquals(isolated, isolateStorageKey(isolated), store.id)
                    assertTrue(allowStorageWrite(store.sampleOwnerKey), store.id)
                }
                AutomationStoreKind.ReadOnly -> {
                    assertEquals(store.sampleOwnerKey, isolated, store.id)
                    assertFalse(allowStorageWrite(store.sampleOwnerKey), store.id)
                }
                AutomationStoreKind.Directory -> {
                    assertEquals(AUTOMATION_ANALYSIS_DIRECTORY, analysisDirectoryName(), store.id)
                    assertFalse(analysisDirectoryName() == store.sampleOwnerKey, store.id)
                }
            }
        }
    }

    @Test
    fun automationLaunchLeavesTheActiveProfilePointerAlone() {
        val stored = 2
        assertEquals(stored, activeProfilePointerAfter(stored = stored, attempted = AUTOMATION_PROFILE_ID, active = true))
        assertEquals(stored, activeProfilePointerAfter(stored = stored, attempted = 4, active = true))
    }

    @Test
    fun submitUsesTheFakeAndDoesNotPost() {
        runBlocking {
        AutomationTestMode.installForTests(active = true, submitStatus = 409)
        TheIntroDbSubmitProbe.networkPosts = 0
        val plan = theIntroDbSubmitPlan(
            url = TheIntroDb.SUBMIT_URL,
            active = true,
            requestedStatus = AutomationTestMode.submitStatus,
        )
        assertEquals(TheIntroDbSubmitPlan.Fake(409), plan)
        assertEquals(
            TheIntroDbSubmitPlan.Fake(429),
            theIntroDbSubmitPlan(TheIntroDb.MAP_URL, active = true, requestedStatus = 429),
        )
        assertIs<TheIntroDbSubmitResult.Failed>(
            TheIntroDb.submitTimestamp(
                apiKey = "",
                tmdbId = 1,
                type = "movie",
                segment = "intro",
                startSec = 1.0,
                endSec = 2.0,
            ),
        )
        assertEquals(0, TheIntroDbSubmitProbe.networkPosts)
        assertEquals("TheIntroDB error 409", TheIntroDb.lastSubmitError)

        AutomationTestMode.installForTests(active = true, submitStatus = 200)
        assertEquals(
            TheIntroDbSubmitResult.Accepted,
            TheIntroDb.submitTimestamp(
                apiKey = "must-not-be-sent",
                tmdbId = 1,
                type = "tv",
                segment = "intro",
                season = 1,
                episode = 1,
                startSec = 1.0,
                endSec = 2.0,
            ),
        )
        assertEquals(0, TheIntroDbSubmitProbe.networkPosts)
        assertNull(TheIntroDb.lastSubmitError)
        assertFalse(isTheIntroDbMutation(TheIntroDb.MEDIA_URL))
        assertIs<TheIntroDbSubmitPlan.Network>(
            theIntroDbSubmitPlan(TheIntroDb.MEDIA_URL, active = true, requestedStatus = 409),
        )
        }
    }

    @Test
    fun aNormalLaunchDoesNotChangeKeysOrThePointer() {
        val parsed = parseAutomationLaunch(arguments = listOf("/Applications/Nuvio.app/Nuvio"), environment = emptyMap())
        assertFalse(parsed.active)
        assertFalse(parsed.reset)
        assertNull(parsed.submitStatus)
        AutomationTestMode.installForTests(active = false)
        AutomationStoreCatalog.entries.forEach { store ->
            if (store.kind == AutomationStoreKind.Directory) {
                assertEquals(LIVE_ANALYSIS_DIRECTORY, analysisDirectoryName())
            } else {
                assertEquals(store.sampleOwnerKey, isolateStorageKey(store.sampleOwnerKey), store.id)
                assertTrue(allowStorageWrite(store.sampleOwnerKey), store.id)
            }
        }
        assertEquals(4, activeProfilePointerAfter(stored = 2, attempted = 4, active = false))
        assertEquals(TheIntroDbSubmitPlan.Network, theIntroDbSubmitPlan(TheIntroDb.SUBMIT_URL, active = false, requestedStatus = 409))
        assertEquals(TheIntroDbSubmitPlan.Network, theIntroDbSubmitPlan(TheIntroDb.MAP_URL, active = false, requestedStatus = 200))
    }

    @Test
    fun addonSeedCopiesTheOwnerListOnceAndNeverWritesItBack() {
        val plan = addonSeedPlan(ownerProfileId = 3, destinationAlreadyStored = false)
        assertEquals("installed_manifest_urls_3", plan?.readUrlsKey)
        assertEquals("installed_manifest_urls_$AUTOMATION_PROFILE_ID", plan?.writeUrlsKey)
        assertEquals("installed_manifest_enabled_states_3", plan?.readEnabledKey)
        assertEquals("installed_manifest_enabled_states_$AUTOMATION_PROFILE_ID", plan?.writeEnabledKey)
        assertNull(addonSeedPlan(ownerProfileId = 3, destinationAlreadyStored = true))
        assertNull(addonSeedPlan(ownerProfileId = AUTOMATION_PROFILE_ID, destinationAlreadyStored = false))
        assertNull(addonSeedPlan(ownerProfileId = 0, destinationAlreadyStored = false))
    }

    @Test
    fun launchParserReadsTheFlagsOnlyWhenTheModeIsRequested() {
        val on = parseAutomationLaunch(
            arguments = listOf("Nuvio", "-NuvioAutomation", "-NuvioAutomationReset", "-NuvioAutomationSubmitResult=429"),
            environment = emptyMap(),
        )
        assertTrue(on.active)
        assertTrue(on.reset)
        assertEquals(429, on.submitStatus)
        val fromEnv = parseAutomationLaunch(
            arguments = emptyList(),
            environment = mapOf("NUVIO_AUTOMATION" to "true", "NUVIO_AUTOMATION_SUBMIT_RESULT" to "409"),
        )
        assertTrue(fromEnv.active)
        assertEquals(409, fromEnv.submitStatus)
        val resetIgnored = parseAutomationLaunch(
            arguments = listOf("-NuvioAutomationReset"),
            environment = emptyMap(),
        )
        assertFalse(resetIgnored.active)
    }
}
