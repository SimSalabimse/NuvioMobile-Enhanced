package com.nuvio.app.features.player.skip

internal expect object FlagSubmitLedger {
    fun loadAll(): Map<String, List<String>>
    fun saveAll(entries: Map<String, List<String>>)
}

internal fun FlagSubmitLedger.loadSegments(contentKey: String): List<String> {
    if (contentKey.isEmpty()) return emptyList()
    return loadAll()[contentKey].orEmpty()
}

internal fun FlagSubmitLedger.loadTypes(contentKey: String): Set<String> = loadSegments(contentKey).toSet()

/** Appends one submission of this type, up to [MAX_SUBMISSIONS_PER_TYPE]. */
internal fun FlagSubmitLedger.rememberType(contentKey: String, segmentType: String) {
    if (contentKey.isEmpty()) return
    val canonical = canonicalFlagSegmentType(segmentType) ?: return
    val current = loadAll().toMutableMap()
    val existing = current[contentKey].orEmpty()
    if (existing.count { it == canonical } >= MAX_SUBMISSIONS_PER_TYPE) return
    current[contentKey] = existing + canonical
    saveAll(current)
}

internal fun FlagSubmitLedger.replaceTypes(contentKey: String, remoteTypes: Set<String>) {
    if (contentKey.isEmpty()) return
    val current = loadAll()
    val canonical = remoteTypes.mapNotNull { canonicalFlagSegmentType(it) }.distinct()
    val next = current.toMutableMap()
    if (canonical.isEmpty()) next.remove(contentKey) else next[contentKey] = canonical
    if (next == current) return
    saveAll(next)
}
