package com.nuvio.app.features.profiles

internal const val ProfileCacheReadTimeoutMs = 1_500L

internal enum class ProfileCacheReadKind {
    NoPayload,
    OtherAccount,
    Empty,
    Profiles,
}

internal enum class ProfileStartupDestination {
    Loading,
    ProfileSelection,
    Main,
}

internal data class ProfileStartupProfile(
    val profileIndex: Int,
    val pinEnabled: Boolean,
)

internal data class ProfileStartupGateInput(
    val phase: ProfileCachePhase,
    val profiles: List<ProfileStartupProfile>,
    val rememberLastProfileEnabled: Boolean,
    val hasEverSelectedProfile: Boolean,
    val activeProfileIndex: Int,
    val readTimedOut: Boolean,
)

internal data class ProfileStartupGateDecision(
    val destination: ProfileStartupDestination,
    val selectProfileIndex: Int? = null,
    val snapHomeOverPicker: Boolean = false,
)

internal fun classifyProfileCacheRead(
    payloadPresent: Boolean,
    storedUserId: String?,
    expectedUserId: String?,
    profileCount: Int,
): ProfileCacheReadKind = when {
    !payloadPresent -> ProfileCacheReadKind.NoPayload
    expectedUserId != null && storedUserId != expectedUserId -> ProfileCacheReadKind.OtherAccount
    profileCount <= 0 -> ProfileCacheReadKind.Empty
    else -> ProfileCacheReadKind.Profiles
}

internal fun profileCachePhaseFor(kind: ProfileCacheReadKind): ProfileCachePhase = when (kind) {
    ProfileCacheReadKind.NoPayload,
    ProfileCacheReadKind.OtherAccount,
    ProfileCacheReadKind.Empty,
    -> ProfileCachePhase.ConfirmedNone
    ProfileCacheReadKind.Profiles -> ProfileCachePhase.Ready
}

internal fun profileCachePhaseForProfiles(profiles: List<*>): ProfileCachePhase =
    if (profiles.isEmpty()) ProfileCachePhase.ConfirmedNone else ProfileCachePhase.Ready

internal fun profileStartupReadSettled(phase: ProfileCachePhase, readTimedOut: Boolean): Boolean =
    phase != ProfileCachePhase.Reading || readTimedOut

internal fun profileStartupContinuesAfterReadTimeout(elapsedMs: Long, phase: ProfileCachePhase): Boolean =
    phase == ProfileCachePhase.Reading && elapsedMs >= ProfileCacheReadTimeoutMs

internal fun decideProfileStartupGate(input: ProfileStartupGateInput): ProfileStartupGateDecision {
    if (input.phase == ProfileCachePhase.Reading && !input.readTimedOut) {
        return ProfileStartupGateDecision(ProfileStartupDestination.Loading)
    }
    val remembered = if (input.rememberLastProfileEnabled) {
        input.profiles.firstOrNull { it.profileIndex == input.activeProfileIndex }
    } else {
        null
    }
    if (remembered != null && !remembered.pinEnabled) {
        return ProfileStartupGateDecision(
            destination = ProfileStartupDestination.Main,
            selectProfileIndex = remembered.profileIndex,
        )
    }
    if (input.profiles.size == 1 && !input.profiles.first().pinEnabled) {
        return ProfileStartupGateDecision(
            destination = ProfileStartupDestination.Main,
            selectProfileIndex = input.profiles.first().profileIndex,
        )
    }
    // Timed out with no pinless profile yet: keep waiting. Settled storage may open the picker.
    if (input.phase == ProfileCachePhase.Reading) {
        return ProfileStartupGateDecision(ProfileStartupDestination.Loading)
    }
    return ProfileStartupGateDecision(ProfileStartupDestination.ProfileSelection)
}

internal fun nextStartupGate(
    current: ProfileStartupDestination,
    input: ProfileStartupGateInput,
    pickerOpenedByUser: Boolean,
): ProfileStartupGateDecision {
    if (pickerOpenedByUser && current == ProfileStartupDestination.ProfileSelection) {
        return ProfileStartupGateDecision(ProfileStartupDestination.ProfileSelection)
    }
    if (current == ProfileStartupDestination.Main) {
        return ProfileStartupGateDecision(ProfileStartupDestination.Main)
    }
    val decision = decideProfileStartupGate(input)
    return decision.copy(
        snapHomeOverPicker = current == ProfileStartupDestination.ProfileSelection &&
            decision.destination == ProfileStartupDestination.Main,
    )
}

internal fun shouldDeferLaunchNetwork(
    cachedSnapshotVisible: Boolean,
    snapshotRestorePending: Boolean,
    launchPainted: Boolean,
): Boolean = !launchPainted && (cachedSnapshotVisible || snapshotRestorePending)

internal enum class FirstFrameDestination {
    Home,
    Picker,
    SignIn,
}

internal enum class RememberedPinKind {
    NoPin,
    HasPin,
    Missing,
}

internal enum class StartupNetworkOutcome {
    Online,
    Offline,
    TimedOut,
}

internal data class FirstFrameInput(
    val signedIn: Boolean,
    val profileCount: Int,
    val rememberOn: Boolean,
    val rememberedPin: RememberedPinKind,
    val cachePresent: Boolean,
    val network: StartupNetworkOutcome,
)

internal data class FirstFrameDecision(
    val destination: FirstFrameDestination,
    val pickerWasFirstFrame: Boolean,
)

internal fun decideFirstFrame(input: FirstFrameInput): FirstFrameDecision {
    when (input.network) {
        StartupNetworkOutcome.Online,
        StartupNetworkOutcome.Offline,
        StartupNetworkOutcome.TimedOut,
        -> Unit
    }
    val destination = when {
        !input.signedIn -> FirstFrameDestination.SignIn
        !input.cachePresent || input.profileCount <= 0 -> FirstFrameDestination.Picker
        input.rememberOn && input.rememberedPin == RememberedPinKind.NoPin -> FirstFrameDestination.Home
        input.profileCount == 1 && input.rememberedPin == RememberedPinKind.NoPin -> FirstFrameDestination.Home
        else -> FirstFrameDestination.Picker
    }
    return FirstFrameDecision(
        destination = destination,
        pickerWasFirstFrame = destination == FirstFrameDestination.Picker,
    )
}

internal fun rememberedPinKind(
    rememberOn: Boolean,
    activeProfileIndex: Int,
    profiles: List<ProfileStartupProfile>,
): RememberedPinKind {
    if (!rememberOn) return RememberedPinKind.Missing
    val remembered = profiles.firstOrNull { it.profileIndex == activeProfileIndex }
        ?: return RememberedPinKind.Missing
    return if (remembered.pinEnabled) RememberedPinKind.HasPin else RememberedPinKind.NoPin
}

/** Home on the first frame. The profile picker and the launch avatar cover stay off. */
internal fun coldLaunchSkipsProfilePicker(destination: FirstFrameDestination): Boolean =
    destination == FirstFrameDestination.Home

/**
 * The launch avatar cover sits on top of Home until the tabs are ready.
 * A straight-home launch never draws it, so the remembered profile is the first thing on screen.
 */
internal fun showStartupLaunchCover(
    destination: FirstFrameDestination,
    gateIsHome: Boolean,
    mainContentReady: Boolean,
): Boolean = gateIsHome && !mainContentReady && !coldLaunchSkipsProfilePicker(destination)

internal enum class ProfileGateMoment {
    BeforeStorageRead,
    BeforeProfileListLoads,
    AfterSignInRestore,
    RememberedIdNotInLoadedList,
    StorageSettled,
}

internal data class ProfilePickerChoice(
    val moment: ProfileGateMoment,
    val signedIn: Boolean,
    val rememberOn: Boolean,
    val hasEverSelectedProfile: Boolean,
    val rememberedExists: Boolean,
    val rememberedHasPin: Boolean,
    val profileCount: Int,
    val userAskedToSwitch: Boolean,
    val network: StartupNetworkOutcome,
)

/** Picker only when remember is off, no remembered pinless profile exists, or the user asked to switch. */
internal fun startupComposesProfilePicker(choice: ProfilePickerChoice): Boolean {
    when (choice.network) {
        StartupNetworkOutcome.Online,
        StartupNetworkOutcome.Offline,
        StartupNetworkOutcome.TimedOut,
        -> Unit
    }
    if (!choice.signedIn) return false
    if (choice.userAskedToSwitch) return true
    if (
        choice.moment == ProfileGateMoment.BeforeStorageRead ||
        choice.moment == ProfileGateMoment.BeforeProfileListLoads ||
        choice.moment == ProfileGateMoment.RememberedIdNotInLoadedList
    ) {
        return false
    }
    // A remembered pinless profile skips the picker whether or not the older
    // "has ever selected" flag was stored with it.
    val rememberedPinless = choice.rememberOn && choice.rememberedExists && !choice.rememberedHasPin
    val singlePinless = choice.profileCount == 1 && !choice.rememberedHasPin
    if (rememberedPinless || singlePinless) return false
    return true
}
