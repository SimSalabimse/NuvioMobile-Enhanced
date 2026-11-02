package com.nuvio.app.features.details

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.features.profiles.ProfileRepository

internal enum class OfflineCacheLookup {
    MemoryHit,
    DiskFresh,
    DiskStale,
    Miss,
}

internal enum class OfflineCacheNetwork {
    Online,
    Offline,
    Timeout,
}

internal data class OfflineCacheDecision(
    val showCached: Boolean,
    val refreshRequested: Boolean,
)

internal object OfflineDetailsCacheLimits {
    const val SchemaVersion = 1
    const val MetaTtlMs = 12L * 60 * 60 * 1000
    const val MetaMaxEntries = 150
    const val MetaMaxBytes = 8 * 1024 * 1024
    const val SeasonListTtlMs = 12L * 60 * 60 * 1000
    const val SeasonListMaxEntries = 400
    const val SeasonListMaxBytes = 2 * 1024 * 1024
    const val SeasonRatingsTtlMs = 7L * 24 * 60 * 60 * 1000
    const val SeasonRatingsMaxEntries = 400
    const val SeasonRatingsMaxBytes = 1 * 1024 * 1024
    const val ContinueWatchingTtlMs = 30L * 60 * 1000
    const val ContinueWatchingMaxItems = 60
    const val WriteDebounceMs = 1_000L
}

internal fun offlineDetailsProfileKey(): String? {
    val profile = ProfileRepository.state.value.activeProfile ?: return null
    return "${profile.userId}|${profile.id}|${profile.profileIndex}"
}

internal fun offlineCacheDecision(
    lookup: OfflineCacheLookup,
    network: OfflineCacheNetwork,
): OfflineCacheDecision = when (lookup) {
    OfflineCacheLookup.MemoryHit,
    OfflineCacheLookup.DiskFresh,
    -> OfflineCacheDecision(showCached = true, refreshRequested = false)
    OfflineCacheLookup.DiskStale -> OfflineCacheDecision(
        showCached = true,
        refreshRequested = network != OfflineCacheNetwork.Offline,
    )
    OfflineCacheLookup.Miss -> OfflineCacheDecision(
        showCached = false,
        refreshRequested = network != OfflineCacheNetwork.Offline,
    )
}

internal fun lookupForProfile(
    requestProfile: String,
    storedProfile: String?,
    lookup: OfflineCacheLookup,
): OfflineCacheLookup {
    if (lookup == OfflineCacheLookup.Miss) return OfflineCacheLookup.Miss
    if (storedProfile == null || storedProfile != requestProfile) return OfflineCacheLookup.Miss
    return lookup
}

internal fun offlineNetwork(condition: NetworkCondition): OfflineCacheNetwork = when (condition) {
    NetworkCondition.NoInternet,
    NetworkCondition.ServersUnreachable,
    -> OfflineCacheNetwork.Offline
    NetworkCondition.Online,
    NetworkCondition.Unknown,
    NetworkCondition.Checking,
    -> OfflineCacheNetwork.Online
}

internal fun currentOfflineDetailsNetwork(): OfflineCacheNetwork =
    offlineNetwork(NetworkStatusRepository.uiState.value.condition)

internal fun offlineStampIsFresh(savedAtMs: Long, nowMs: Long, ttlMs: Long): Boolean =
    savedAtMs > 0L && nowMs - savedAtMs in 0..ttlMs
