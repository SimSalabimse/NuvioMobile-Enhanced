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
    if (!profileStartupReadSettled(input.phase, input.readTimedOut)) {
        return ProfileStartupGateDecision(ProfileStartupDestination.Loading)
    }

    val remembered = if (input.rememberLastProfileEnabled && input.hasEverSelectedProfile) {
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
