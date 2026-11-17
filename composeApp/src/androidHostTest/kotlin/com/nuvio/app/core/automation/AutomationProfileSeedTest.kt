package com.nuvio.app.core.automation

import android.app.Application
import android.content.Context
import com.nuvio.app.features.profiles.FirstFrameDestination
import com.nuvio.app.features.profiles.FirstFrameInput
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.app.features.profiles.StartupNetworkOutcome
import com.nuvio.app.features.profiles.decideFirstFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationProfileSeedTest {
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        ProfileStorage.initialize(context)
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        AutomationTestMode.resetForTests()
        ProfileRepository.clearInMemory()
    }

    @After
    fun tearDown() {
        AutomationTestMode.resetForTests()
        ProfileRepository.clearInMemory()
    }

    @Test
    fun flagOffLeavesAnEmptyProfileList() {
        AutomationTestMode.installForTests(active = false)
        val cache = ProfileRepository.applyCacheSynchronously()
        assertTrue(ProfileRepository.state.value.profiles.isEmpty())
        assertNull(ProfileRepository.state.value.activeProfile)
        assertEquals(0, cache.profileCount)
        assertEquals(false, cache.cachePresent)
        assertNull(ProfileStorage.loadPayload())
        val frame = decideFirstFrame(
            FirstFrameInput(
                signedIn = cache.cachePresent,
                profileCount = cache.profileCount,
                rememberOn = cache.rememberOn,
                rememberedPin = cache.rememberedPin,
                cachePresent = cache.cachePresent,
                network = StartupNetworkOutcome.Offline,
            ),
        )
        assertEquals(FirstFrameDestination.SignIn, frame.destination)
    }

    @Test
    fun flagOnEmptyStateSelectsOneTestProfile() {
        AutomationTestMode.installForTests(active = true)
        val cache = ProfileRepository.applyCacheSynchronously()
        val state = ProfileRepository.state.value
        val profile = state.profiles.single()
        assertEquals(AUTOMATION_PROFILE_NAME, profile.name)
        assertEquals("Test", profile.name)
        assertEquals(AUTOMATION_PROFILE_ID, profile.profileIndex)
        assertEquals(AUTOMATION_PROFILE_ID.toString(), profile.id)
        assertEquals(profile, state.activeProfile)
        assertEquals(AUTOMATION_PROFILE_ID, ProfileRepository.activeProfileId)
        assertEquals(1, cache.profileCount)
        assertEquals(true, cache.cachePresent)
        assertNull(ProfileStorage.loadPayload())
        val frame = decideFirstFrame(
            FirstFrameInput(
                signedIn = cache.cachePresent,
                profileCount = cache.profileCount,
                rememberOn = cache.rememberOn,
                rememberedPin = cache.rememberedPin,
                cachePresent = cache.cachePresent,
                network = StartupNetworkOutcome.Offline,
            ),
        )
        assertEquals(FirstFrameDestination.Home, frame.destination)
    }

    @Test
    fun flagOnPullFromEmptyMemorySelectsTheSameProfile() {
        AutomationTestMode.installForTests(active = true)
        runBlocking { ProfileRepository.pullProfiles() }
        val state = ProfileRepository.state.value
        val profile = state.profiles.single()
        assertEquals("Test", profile.name)
        assertEquals(AUTOMATION_PROFILE_ID, profile.profileIndex)
        assertEquals(profile, state.activeProfile)
        assertNull(ProfileStorage.loadPayload())
    }

    @Test
    fun flagOnRenamesAStoredProfileWithoutWritingIt() {
        val payload = """
            {"userId":"user-1","activeProfileIndex":1,"hasEverSelectedProfile":true,"rememberLastProfileEnabled":false,"profiles":[{"id":"p1","user_id":"user-1","profile_index":1,"name":"Keeper"}]}
        """.trimIndent()
        ProfileStorage.savePayload(payload)
        AutomationTestMode.installForTests(active = true)
        ProfileRepository.clearInMemory()
        val cache = ProfileRepository.applyCacheSynchronously()
        val profile = ProfileRepository.state.value.profiles.single()
        assertEquals("Test", profile.name)
        assertEquals(1, profile.profileIndex)
        assertEquals(profile, ProfileRepository.state.value.activeProfile)
        assertEquals(1, cache.profileCount)
        assertEquals(payload, ProfileStorage.loadPayload())
    }
}
