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
    fun `read timeout keeps waiting when nothing is known`() {
        assertTrue(ProfileCacheReadTimeoutMs in 250L..3_000L)
        assertFalse(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs - 1, ProfileCachePhase.Reading))
        assertTrue(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs, ProfileCachePhase.Reading))
        assertFalse(profileStartupContinuesAfterReadTimeout(ProfileCacheReadTimeoutMs, ProfileCachePhase.Ready))

        val decision = decideProfileStartupGate(
            input(phase = ProfileCachePhase.Reading, profiles = emptyList(), readTimedOut = true),
        )
        assertEquals(ProfileStartupDestination.Loading, decision.destination)
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
    fun `late cache after the read timeout goes home without composing the picker`() {
        var current = ProfileStartupDestination.Loading
        current = nextStartupGate(
            current,
            input(phase = ProfileCachePhase.Reading, profiles = emptyList(), readTimedOut = true),
            pickerOpenedByUser = false,
        ).destination
        assertEquals(ProfileStartupDestination.Loading, current)

        val late = nextStartupGate(
            current,
            input(phase = ProfileCachePhase.Ready, profiles = listOf(pinless(1))),
            pickerOpenedByUser = false,
        )
        assertEquals(ProfileStartupDestination.Main, late.destination)
        assertEquals(1, late.selectProfileIndex)
        assertFalse(late.snapHomeOverPicker)
    }

    @Test
    fun `picker is composed only when remember is off or no pinless profile exists or the user asked`() {
        val moments = ProfileGateMoment.entries
        val signedInValues = listOf(false, true)
        val rememberValues = listOf(false, true)
        val everValues = listOf(false, true)
        val existsValues = listOf(false, true)
        val pinValues = listOf(false, true)
        val counts = listOf(0, 1, 3)
        val askedValues = listOf(false, true)
        for (moment in moments) {
            for (signedIn in signedInValues) {
                for (rememberOn in rememberValues) {
                    for (hasEver in everValues) {
                        for (exists in existsValues) {
                            for (hasPin in pinValues) {
                                for (count in counts) {
                                    for (asked in askedValues) {
                                        if (exists && count == 0) continue
                                        val choice = ProfilePickerChoice(
                                            moment = moment,
                                            signedIn = signedIn,
                                            rememberOn = rememberOn,
                                            hasEverSelectedProfile = hasEver,
                                            rememberedExists = exists,
                                            rememberedHasPin = hasPin,
                                            profileCount = count,
                                            userAskedToSwitch = asked,
                                            network = StartupNetworkOutcome.Online,
                                        )
                                        val expected = startupComposesProfilePicker(choice)
                                        for (network in StartupNetworkOutcome.entries) {
                                            val decision = startupComposesProfilePicker(choice.copy(network = network))
                                            assertEquals(expected, decision, "$choice network=$network")
                                        }
                                        if (expected) {
                                            val allowed = asked ||
                                                !rememberOn ||
                                                !exists ||
                                                hasPin ||
                                                count == 0
                                            assertTrue(allowed, "$choice")
                                        }
                                        val gate = decideProfileStartupGate(choice.toGateInput())
                                        if (!asked && signedIn) {
                                            val gateShowsPicker = gate.destination == ProfileStartupDestination.ProfileSelection
                                            if (
                                                moment == ProfileGateMoment.BeforeStorageRead ||
                                                moment == ProfileGateMoment.BeforeProfileListLoads ||
                                                moment == ProfileGateMoment.RememberedIdNotInLoadedList
                                            ) {
                                                assertFalse(gateShowsPicker, "$choice")
                                            } else if (rememberOn && exists && !hasPin) {
                                                assertEquals(ProfileStartupDestination.Main, gate.destination, "$choice")
                                            } else if (count == 1 && !hasPin) {
                                                assertEquals(ProfileStartupDestination.Main, gate.destination, "$choice")
                                            } else {
                                                assertEquals(
                                                    ProfileStartupDestination.ProfileSelection,
                                                    gate.destination,
                                                    "$choice",
                                                )
                                            }
                                        }
                                        if (asked) {
                                            val stay = nextStartupGate(
                                                ProfileStartupDestination.ProfileSelection,
                                                choice.toGateInput(),
                                                pickerOpenedByUser = true,
                                            )
                                            assertEquals(ProfileStartupDestination.ProfileSelection, stay.destination)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertFalse(
            showStartupLaunchCover(
                destination = FirstFrameDestination.Home,
                gateIsHome = true,
                mainContentReady = false,
            ),
        )
        assertTrue(coldLaunchSkipsProfilePicker(FirstFrameDestination.Home))
        assertFalse(coldLaunchSkipsProfilePicker(FirstFrameDestination.Picker))
        val withEver = startupComposesProfilePicker(settledRemembered(hasEver = true))
        val withoutEver = startupComposesProfilePicker(settledRemembered(hasEver = false))
        assertFalse(withEver)
        assertEquals(withEver, withoutEver)
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

    @Test
    fun `startup frame follows local state and ignores the network`() {
        assertEquals(
            FirstFrameDestination.SignIn,
            decideFirstFrame(frame(signedIn = false, profileCount = 2, rememberOn = true, pin = RememberedPinKind.NoPin)).destination,
        )
        assertEquals(
            FirstFrameDestination.Picker,
            decideFirstFrame(frame(signedIn = true, profileCount = 0, rememberOn = false, pin = RememberedPinKind.Missing, cachePresent = true)).destination,
        )
        assertEquals(
            FirstFrameDestination.Picker,
            decideFirstFrame(frame(signedIn = true, profileCount = 3, rememberOn = false, pin = RememberedPinKind.NoPin)).destination,
        )
        assertEquals(
            FirstFrameDestination.Picker,
            decideFirstFrame(frame(signedIn = true, profileCount = 3, rememberOn = true, pin = RememberedPinKind.HasPin)).destination,
        )
        assertEquals(
            FirstFrameDestination.Picker,
            decideFirstFrame(frame(signedIn = true, profileCount = 3, rememberOn = true, pin = RememberedPinKind.Missing)).destination,
        )
        assertEquals(
            FirstFrameDestination.Picker,
            decideFirstFrame(frame(signedIn = true, profileCount = 1, rememberOn = false, pin = RememberedPinKind.HasPin)).destination,
        )
        val home = decideFirstFrame(frame(signedIn = true, profileCount = 3, rememberOn = true, pin = RememberedPinKind.NoPin))
        assertEquals(FirstFrameDestination.Home, home.destination)
        assertFalse(home.pickerWasFirstFrame)
        val signedInValues = listOf(false, true)
        val counts = listOf(0, 1, 3)
        val rememberValues = listOf(false, true)
        val pins = RememberedPinKind.entries
        val caches = listOf(false, true)
        val networks = StartupNetworkOutcome.entries
        for (signedIn in signedInValues) {
            for (count in counts) {
                for (rememberOn in rememberValues) {
                    for (pin in pins) {
                        for (cachePresent in caches) {
                            val local = FirstFrameInput(
                                signedIn = signedIn,
                                profileCount = count,
                                rememberOn = rememberOn,
                                rememberedPin = pin,
                                cachePresent = cachePresent,
                                network = StartupNetworkOutcome.Online,
                            )
                            val expected = expectedFirstFrame(local)
                            for (network in networks) {
                                val decision = decideFirstFrame(local.copy(network = network))
                                assertEquals(expected, decision.destination, "$local network=$network")
                                if (decision.destination == FirstFrameDestination.Home) {
                                    assertFalse(decision.pickerWasFirstFrame)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `online and offline share the start destination for the same local state`() {
        val local = FirstFrameInput(
            signedIn = true,
            profileCount = 2,
            rememberOn = true,
            rememberedPin = RememberedPinKind.NoPin,
            cachePresent = true,
            network = StartupNetworkOutcome.Online,
        )
        val online = decideFirstFrame(local)
        val offline = decideFirstFrame(local.copy(network = StartupNetworkOutcome.Offline))
        val timedOut = decideFirstFrame(local.copy(network = StartupNetworkOutcome.TimedOut))
        assertEquals(FirstFrameDestination.Home, online.destination)
        assertEquals(online, offline)
        assertEquals(online, timedOut)
        assertFalse(online.pickerWasFirstFrame)
    }

    @Test
    fun `first frame decision does not suspend or read the network`() {
        val decision = decideFirstFrame(
            FirstFrameInput(
                signedIn = true,
                profileCount = 1,
                rememberOn = false,
                rememberedPin = RememberedPinKind.NoPin,
                cachePresent = true,
                network = StartupNetworkOutcome.TimedOut,
            ),
        )
        assertEquals(FirstFrameDestination.Home, decision.destination)
        assertFalse(decision.pickerWasFirstFrame)
        assertEquals(
            decision,
            decideFirstFrame(
                FirstFrameInput(
                    signedIn = true,
                    profileCount = 1,
                    rememberOn = false,
                    rememberedPin = RememberedPinKind.NoPin,
                    cachePresent = true,
                    network = StartupNetworkOutcome.Offline,
                ),
            ),
        )
    }

    private fun frame(
        signedIn: Boolean,
        profileCount: Int,
        rememberOn: Boolean,
        pin: RememberedPinKind,
        cachePresent: Boolean = true,
        network: StartupNetworkOutcome = StartupNetworkOutcome.Offline,
    ) = FirstFrameInput(
        signedIn = signedIn,
        profileCount = profileCount,
        rememberOn = rememberOn,
        rememberedPin = pin,
        cachePresent = cachePresent,
        network = network,
    )

    private fun expectedFirstFrame(input: FirstFrameInput): FirstFrameDestination = when {
        !input.signedIn -> FirstFrameDestination.SignIn
        !input.cachePresent || input.profileCount <= 0 -> FirstFrameDestination.Picker
        input.rememberOn && input.rememberedPin == RememberedPinKind.NoPin -> FirstFrameDestination.Home
        input.profileCount == 1 && input.rememberedPin == RememberedPinKind.NoPin -> FirstFrameDestination.Home
        else -> FirstFrameDestination.Picker
    }

    private fun settledRemembered(hasEver: Boolean) = ProfilePickerChoice(
        moment = ProfileGateMoment.StorageSettled,
        signedIn = true,
        rememberOn = true,
        hasEverSelectedProfile = hasEver,
        rememberedExists = true,
        rememberedHasPin = false,
        profileCount = 3,
        userAskedToSwitch = false,
        network = StartupNetworkOutcome.Offline,
    )

    private fun ProfilePickerChoice.toGateInput(): ProfileStartupGateInput {
        val unresolved = moment == ProfileGateMoment.BeforeStorageRead ||
            moment == ProfileGateMoment.BeforeProfileListLoads ||
            moment == ProfileGateMoment.RememberedIdNotInLoadedList
        val profiles = when {
            unresolved || profileCount <= 0 -> emptyList()
            else -> List(profileCount) { index ->
                val profileIndex = index + 1
                val pin = when {
                    rememberedExists && profileIndex == 1 -> rememberedHasPin
                    !rememberedExists && profileCount == 1 -> rememberedHasPin
                    else -> false
                }
                ProfileStartupProfile(profileIndex = profileIndex, pinEnabled = pin)
            }
        }
        val phase = when {
            unresolved -> ProfileCachePhase.Reading
            profiles.isEmpty() -> ProfileCachePhase.ConfirmedNone
            else -> ProfileCachePhase.Ready
        }
        return input(
            phase = phase,
            profiles = profiles,
            rememberLastProfileEnabled = rememberOn,
            hasEverSelectedProfile = hasEverSelectedProfile,
            activeProfileIndex = if (rememberedExists) 1 else 99,
            readTimedOut = moment == ProfileGateMoment.RememberedIdNotInLoadedList,
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
