package com.nuvio.app.features.player.skip

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Segment types this account submitted to TheIntroDB.
 *
 * [FlagSubmitLedger] is not this record. A successful IntroDB send is written there too,
 * and a public TheIntroDB fetch is not. This ledger is written only when a TheIntroDB
 * submission succeeds, and when TheIntroDB's own "my submissions" list is applied.
 * A fetched public segment never enters it.
 */
internal object OwnTheIntroDbLedger {
    private const val STORE_KEY = "own_theintrodb_submissions_v1"
    private var cached: Map<String, Set<String>>? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun loadAll(): Map<String, Set<String>> =
        cached ?: decodeFlagSubmitLedger(DownloadsSettingsStorage.loadString(STORE_KEY).orEmpty()).also { cached = it }

    fun remember(contentKey: String, segmentType: String) {
        save(rememberOwnTheIntroDbType(loadAll(), contentKey, segmentType))
    }

    /** Account list for one episode. Local successes stay. Rejected rows are dropped. */
    fun applyListing(contentKey: String, accepted: Set<String>, rejected: Set<String>) {
        save(applyOwnTheIntroDbListing(loadAll(), contentKey, accepted, rejected))
    }

    fun saveAll(entries: Map<String, Set<String>>) {
        save(entries)
    }

    private fun save(entries: Map<String, Set<String>>) {
        cached = entries
        val encoded = encodeFlagSubmitLedger(entries)
        DownloadsSettingsStorage.saveString(STORE_KEY, encoded.ifBlank { null })
        _revision.value += 1
    }
}

internal fun shouldRecordOwnTheIntroDb(service: IntroSubmitService, succeeded: Boolean): Boolean =
    succeeded && service == IntroSubmitService.THE_INTRODB

internal fun rememberOwnTheIntroDbType(
    entries: Map<String, Set<String>>,
    contentKey: String,
    segmentType: String,
): Map<String, Set<String>> {
    if (contentKey.isEmpty()) return entries
    val canonical = canonicalFlagSegmentType(segmentType) ?: return entries
    val current = entries[contentKey].orEmpty()
    if (canonical in current) return entries
    return entries + (contentKey to (current + canonical))
}

/**
 * [accepted] and [rejected] come from TheIntroDB's list of this account's submissions.
 * Types already recorded on this device stay, unless that list says they were rejected.
 */
internal fun applyOwnTheIntroDbListing(
    entries: Map<String, Set<String>>,
    contentKey: String,
    accepted: Set<String>,
    rejected: Set<String>,
): Map<String, Set<String>> {
    if (contentKey.isEmpty()) return entries
    val dropped = rejected.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val remote = accepted.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) } - dropped
    val kept = entries[contentKey].orEmpty().mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) } - dropped
    val next = kept + remote
    val mutable = entries.toMutableMap()
    if (next.isEmpty()) mutable.remove(contentKey) else mutable[contentKey] = next
    return mutable
}
