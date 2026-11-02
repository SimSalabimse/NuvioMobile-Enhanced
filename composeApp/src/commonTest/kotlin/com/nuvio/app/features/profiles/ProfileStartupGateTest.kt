package com.nuvio.app.features.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileStartupGateTest {
    @Test
    fun `still reading does not open the picker`() {
        for (profiles in listOf(emptyList(), listOf(pinless(1)), listOf(locked(1), pinless(2)))) {
            val decision = decideProfileStartupGate(input(phase = ProfileCachePhase.Reading, profiles = profiles))
            assertEquals(ProfileStartupDestination.Loading, decision.destination)
            assertFalse(decision.snapHomeOverPicker)
        }
    }

    @Test
    fun `confirmed none opens the picker`() {
        val decision = decideProfileStartupGate(
            input(phase = ProfileCachePhase.ConfirmedNone, profiles = emptyList()),
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
    }

    @Test
    fun `one pinless profile opens home`() {
        val decision = decideProfileStartupGate(
            input(
                phase = ProfileCachePhase.Ready,
                profiles = listOf(pinless(2)),
                rememberLastProfileEnabled = false,
            ),
        )
        assertEquals(ProfileStartupDestination.Main, decision.destination)
        assertEquals(2, decision.selectProfileIndex)
    }

    @Test
    fun `remembered pinless profile opens home including an index other than 1`() {
        val decision = decideProfileStartupGate(
            input(
                phase = ProfileCachePhase.Ready,
                profiles = listOf(pinless(1), locked(2), pinless(4)),
                rememberLastProfileEnabled = true,
                hasEverSelectedProfile = true,
                activeProfileIndex = 4,
            ),
        )
        assertEquals(ProfileStartupDestination.Main, decision.destination)
        assertEquals(4, decision.selectProfileIndex)
    }

    @Test
    fun `remembered profile with a pin opens the picker`() {
        val decision = decideProfileStartupGate(
            input(
                phase = ProfileCachePhase.Ready,
                profiles = listOf(locked(1), pinless(2)),
                rememberLastProfileEnabled = true,
                hasEverSelectedProfile = true,
                activeProfileIndex = 1,
            ),
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
    }

    @Test
    fun `several profiles with remember off open the picker`() {
        val decision = decideProfileStartupGate(
            input(
                phase = ProfileCachePhase.Ready,
                profiles = listOf(pinless(1), pinless(2)),
                rememberLastProfileEnabled = false,
                hasEverSelectedProfile = true,
                activeProfileIndex = 2,
            ),
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
    }

    @Test
    fun `one profile with a pin opens the picker`() {
        val decision = decideProfileStartupGate(
            input(phase = ProfileCachePhase.Ready, profiles = listOf(locked(3))),
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
    }

    @Test
    fun `read timeout continues with the picker when nothing is known`() {
        assertTrue(ProfileCacheReadTimeoutMs in 250L..3_000L)
        assertFalse(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs - 1, ProfileCachePhase.Reading))
        assertTrue(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs, ProfileCachePhase.Reading))
        assertFalse(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs, ProfileCachePhase.Ready))

        val decision = decideProfileStartupGate(
            input(phase = ProfileCachePhase.Reading, profiles = emptyList(), readTimedOut = true),
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
    }

    @Test
    fun `read timeout uses a known pinless profile instead of the picker`() {
        val decision = decideProfileStartupGate(
            input(
                phase = ProfileCachePhase.Reading,
                profiles = listOf(pinless(2)),
                readTimedOut = true,
            ),
        )
        assertEquals(ProfileStartupDestination.Main, decision.destination)
        assertEquals(2, decision.selectProfileIndex)
    }

    @Test
    fun `late cache arrival never shows the picker`() {
        var current = ProfileStartupDestination.Loading
        val seen = mutableListOf(current)
        val events = listOf(
            input(phase = ProfileCachePhase.Reading, profiles = emptyList()),
            input(phase = ProfileCachePhase.Ready, profiles = listOf(pinless(3))),
        )
        events.forEach { event ->
            val decision = nextStartupGate(current, event, pickerOpenedByUser = false)
            assertFalse(decision.destination == ProfileStartupDestination.ProfileSelection)
            current = decision.destination
            seen += current
        }
        assertEquals(
            listOf(ProfileStartupDestination.Loading, ProfileStartupDestination.Loading, ProfileStartupDestination.Main),
            seen,
        )
        assertFalse(seen.contains(ProfileStartupDestination.ProfileSelection))
    }

    @Test
    fun `late cache after the read timeout snaps home without fading the picker`() {
        var current = ProfileStartupDestination.Loading
        current = nextStartupGate(
            current,
            input(phase = ProfileCachePhase.Reading, profiles = emptyList(), readTimedOut = true),
            pickerOpenedByUser = false,
        ).destination
        assertEquals(ProfileStartupDestination.ProfileSelection, current)

        val late = nextStartupGate(
            current,
            input(phase = ProfileCachePhase.Ready, profiles = listOf(pinless(1))),
            pickerOpenedByUser = false,
        )
        assertEquals(ProfileStartupDestination.Main, late.destination)
        assertEquals(1, late.selectProfileIndex)
        assertTrue(late.snapHomeOverPicker)
    }

    @Test
    fun `a picker the user opened stays up when a late cache could skip it`() {
        val decision = nextStartupGate(
            ProfileStartupDestination.ProfileSelection,
            input(phase = ProfileCachePhase.Ready, profiles = listOf(pinless(1))),
            pickerOpenedByUser = true,
        )
        assertEquals(ProfileStartupDestination.ProfileSelection, decision.destination)
        assertFalse(decision.snapHomeOverPicker)
    }

    @Test
    fun `cache read kinds separate confirmed none from profiles`() {
        assertEquals(
            ProfileCachePhase.ConfirmedNone,
            profileCachePhaseFor(
                classifyProfileCacheRead(
                    payloadPresent = false,
                    storedUserId = null,
                    expectedUserId = "user",
                    profileCount = 0,
                ),
            ),
        )
        assertEquals(
            ProfileCacheReadKind.OtherAccount,
            classifyProfileCacheRead(
                payloadPresent = true,
                storedUserId = "previous",
                expectedUserId = "user",
                profileCount = 2,
            ),
        )
        assertEquals(
            ProfileCachePhase.ConfirmedNone,
            profileCachePhaseFor(ProfileCacheReadKind.OtherAccount),
        )
        assertEquals(
            ProfileCachePhase.ConfirmedNone,
            profileCachePhaseFor(
                classifyProfileCacheRead(
                    payloadPresent = true,
                    storedUserId = "user",
                    expectedUserId = "user",
                    profileCount = 0,
                ),
            ),
        )
        assertEquals(
            ProfileCachePhase.Ready,
            profileCachePhaseFor(
                classifyProfileCacheRead(
                    payloadPresent = true,
                    storedUserId = "user",
                    expectedUserId = null,
                    profileCount = 1,
                ),
            ),
        )
        assertEquals(ProfileCachePhase.Reading, ProfileState().cachePhase)
        assertFalse(ProfileState().isLoaded)
    }

    @Test
    fun `cached home rows defer launch network until the first paint`() {
        assertTrue(
            shouldDeferLaunchNetwork(
                cachedSnapshotVisible = true,
                snapshotRestorePending = false,
                launchPainted = false,
            ),
        )
        assertTrue(
            shouldDeferLaunchNetwork(
                cachedSnapshotVisible = false,
                snapshotRestorePending = true,
                launchPainted = false,
            ),
        )
        assertFalse(
            shouldDeferLaunchNetwork(
                cachedSnapshotVisible = true,
                snapshotRestorePending = true,
                launchPainted = true,
            ),
        )
        assertFalse(
            shouldDeferLaunchNetwork(
                cachedSnapshotVisible = false,
                snapshotRestorePending = false,
                launchPainted = false,
            ),
        )
    }

    private fun pinless(index: Int) = ProfileStartupProfile(profileIndex = index, pinEnabled = false)

    private fun locked(index: Int) = ProfileStartupProfile(profileIndex = index, pinEnabled = true)

    private fun input(
        phase: ProfileCachePhase,
        profiles: List<ProfileStartupProfile>,
        rememberLastProfileEnabled: Boolean = false,
        hasEverSelectedProfile: Boolean = false,
        activeProfileIndex: Int = 1,
        readTimedOut: Boolean = false,
    ) = ProfileStartupGateInput(
        phase = phase,
        profiles = profiles,
        rememberLastProfileEnabled = rememberLastProfileEnabled,
        hasEverSelectedProfile = hasEverSelectedProfile,
        activeProfileIndex = activeProfileIndex,
        readTimedOut = readTimedOut,
    )
}
