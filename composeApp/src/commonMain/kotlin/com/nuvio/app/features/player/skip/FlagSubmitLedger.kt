package com.nuvio.app.features.player.skip

internal expect object FlagSubmitLedger {
    fun loadAll(): Map<String, Set<String>>
    fun saveAll(entries: Map<String, Set<String>>)
}

internal fun FlagSubmitLedger.loadTypes(contentKey: String): Set<String> {
    if (contentKey.isEmpty()) return emptySet()
    return loadAll()[contentKey].orEmpty()
}

internal fun FlagSubmitLedger.rememberType(contentKey: String, segmentType: String) {
    if (contentKey.isEmpty()) return
    val canonical = canonicalFlagSegmentType(segmentType) ?: return
    val current = loadAll().toMutableMap()
    val types = current[contentKey].orEmpty() + canonical
    if (current[contentKey] == types) return
    current[contentKey] = types
    saveAll(current)
}

internal fun FlagSubmitLedger.replaceTypes(contentKey: String, remoteTypes: Set<String>) {
    if (contentKey.isEmpty()) return
    val current = loadAll()
    val next = replaceFlagSubmitLedgerTypes(current, contentKey, remoteTypes)
    if (next == current) return
    saveAll(next)
}
