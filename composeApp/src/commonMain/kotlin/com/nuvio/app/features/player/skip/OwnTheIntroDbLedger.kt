package com.nuvio.app.features.player.skip

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Segment types this account submitted to TheIntroDB.
 *
 * Confirmed rows are this account's accepted submissions, including a 409 from a
 * submit made with this account's key. Pending rows were accepted by the server
 * and are not confirmed yet. A public fetch, a local detection, and another
 * account's cache never enter the scope being read.
 *
 * The scope is a hash of the key. The key itself is not stored.
 */
internal enum class OwnMarkStatus {
    Confirmed,
    Pending,
}

internal data class OwnAccountMarks(
    val confirmed: Map<String, Set<String>> = emptyMap(),
    val pending: Map<String, Set<String>> = emptyMap(),
)

internal object OwnTheIntroDbLedger {
    const val FIXTURE_SCOPE = "fixture"
    private const val STORE_KEY = "own_theintrodb_submissions_v2"
    private var cached: Map<String, OwnAccountMarks>? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun load(scope: String): OwnAccountMarks {
        if (scope.isBlank()) return OwnAccountMarks()
        return read()[scope] ?: OwnAccountMarks()
    }

    fun remember(scope: String, contentKey: String, segmentType: String, status: OwnMarkStatus) {
        if (scope.isBlank()) return
        val next = read().toMutableMap()
        next[scope] = (next[scope] ?: OwnAccountMarks()).withStatus(contentKey, segmentType, status)
        write(next)
    }

    fun applyListing(
        scope: String,
        contentKey: String,
        confirmed: Set<String>,
        pending: Set<String>,
        rejected: Set<String>,
    ) {
        if (scope.isBlank()) return
        val next = read().toMutableMap()
        next[scope] = applyAccountListing(
            marks = next[scope] ?: OwnAccountMarks(),
            contentKey = contentKey,
            confirmed = confirmed,
            pending = pending,
            rejected = rejected,
        )
        write(next)
    }

    /** Layout fixture only. Production reads [load] for the signed-in account. */
    fun saveAll(entries: Map<String, Set<String>>) {
        saveFixture(confirmed = entries)
    }

    /** Layout fixture. [pending] is this account's unconfirmed send, never a public count. */
    fun saveFixture(
        confirmed: Map<String, Set<String>>,
        pending: Map<String, Set<String>> = emptyMap(),
    ) {
        fun canon(entries: Map<String, Set<String>>) = entries.mapValues { (_, types) ->
            types.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) }
        }.filterValues { it.isNotEmpty() }
        val next = read().toMutableMap()
        next[FIXTURE_SCOPE] = OwnAccountMarks(confirmed = canon(confirmed), pending = canon(pending))
        write(next)
    }

    private fun read(): Map<String, OwnAccountMarks> =
        cached ?: decodeOwnAccountLedger(DownloadsSettingsStorage.loadString(STORE_KEY).orEmpty()).also { cached = it }

    private fun write(entries: Map<String, OwnAccountMarks>) {
        cached = entries
        val encoded = encodeOwnAccountLedger(entries)
        DownloadsSettingsStorage.saveString(STORE_KEY, encoded.ifBlank { null })
        _revision.value += 1
    }
}

/** Empty when there is no key. Two different keys do not share a scope. */
internal fun theIntroDbAccountScope(apiKey: String): String {
    val trimmed = apiKey.trim()
    if (trimmed.isEmpty()) return ""
    var hash = 0xcbf29ce484222325UL
    val prime = 0x100000001b3UL
    for (ch in trimmed) {
        hash = hash xor ch.code.toULong()
        hash *= prime
    }
    return hash.toString(16)
}

internal fun accountScopeForService(
    service: IntroSubmitService,
    introDbApiKey: String,
    theIntroDbApiKey: String,
): String {
    if (service != IntroSubmitService.THE_INTRODB) return ""
    return theIntroDbAccountScope(savedKeyForService(service, introDbApiKey, theIntroDbApiKey))
}

internal fun currentTheIntroDbAccountScope(): String {
    val settings = PlayerSettingsRepository.uiState.value
    return accountScopeForService(
        IntroSubmitService.THE_INTRODB,
        settings.introDbApiKey,
        settings.theIntroDbApiKey,
    )
}

internal fun shouldRecordOwnTheIntroDb(service: IntroSubmitService, succeeded: Boolean): Boolean =
    succeeded && service == IntroSubmitService.THE_INTRODB

/** Confirmed and pending types this account already sent. Another scope is not read. */
internal fun ownSentTypes(marks: OwnAccountMarks, contentKey: String): Set<String> {
    if (contentKey.isEmpty()) return emptySet()
    return (marks.confirmed[contentKey].orEmpty() + marks.pending[contentKey].orEmpty())
        .mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) }
}

internal fun OwnAccountMarks.withStatus(
    contentKey: String,
    segmentType: String,
    status: OwnMarkStatus,
): OwnAccountMarks {
    if (contentKey.isEmpty()) return this
    val type = canonicalFlagSegmentType(segmentType) ?: return this
    val confirmedMap = confirmed.toMutableMap()
    val pendingMap = pending.toMutableMap()
    fun edit(map: MutableMap<String, Set<String>>, add: Boolean) {
        val current = map[contentKey].orEmpty()
        val next = if (add) current + type else current - type
        if (next.isEmpty()) map.remove(contentKey) else map[contentKey] = next
    }
    when (status) {
        OwnMarkStatus.Confirmed -> {
            edit(confirmedMap, true)
            edit(pendingMap, false)
        }
        OwnMarkStatus.Pending -> {
            if (type in confirmedMap[contentKey].orEmpty()) return this
            edit(pendingMap, true)
        }
    }
    return copy(confirmed = confirmedMap, pending = pendingMap)
}

/**
 * Listing rows for this account and this episode.
 * A remote pending status is not confirmed. A silent type keeps the cached row
 * so an offline or partial list does not wipe it. Rejected rows are dropped.
 */
internal fun applyAccountListing(
    marks: OwnAccountMarks,
    contentKey: String,
    confirmed: Set<String>,
    pending: Set<String>,
    rejected: Set<String>,
): OwnAccountMarks {
    if (contentKey.isEmpty()) return marks
    val rejectedTypes = rejected.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val remoteConfirmed = confirmed.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - rejectedTypes
    val remotePending = pending.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - rejectedTypes - remoteConfirmed
    val nextConfirmed = ((marks.confirmed[contentKey].orEmpty() - rejectedTypes) - remotePending) + remoteConfirmed
    val nextPending = ((marks.pending[contentKey].orEmpty() - rejectedTypes) + remotePending) - nextConfirmed
    val confirmedMap = marks.confirmed.toMutableMap()
    val pendingMap = marks.pending.toMutableMap()
    if (nextConfirmed.isEmpty()) confirmedMap.remove(contentKey) else confirmedMap[contentKey] = nextConfirmed
    if (nextPending.isEmpty()) pendingMap.remove(contentKey) else pendingMap[contentKey] = nextPending
    return marks.copy(confirmed = confirmedMap, pending = pendingMap)
}

internal fun encodeOwnAccountLedger(entries: Map<String, OwnAccountMarks>): String {
    return entries.entries
        .sortedBy { it.key }
        .flatMap { (scope, marks) ->
            val keys = (marks.confirmed.keys + marks.pending.keys).sorted()
            keys.mapNotNull { contentKey ->
                val confirmed = marks.confirmed[contentKey].orEmpty().mapNotNull { canonicalFlagSegmentType(it) }.distinct().sorted()
                val pending = marks.pending[contentKey].orEmpty().mapNotNull { canonicalFlagSegmentType(it) }.distinct().sorted()
                if (scope.isBlank() || contentKey.isBlank() || (confirmed.isEmpty() && pending.isEmpty())) return@mapNotNull null
                if (scope.contains('\t') || scope.contains('\n') || contentKey.contains('\t') || contentKey.contains('\n')) {
                    return@mapNotNull null
                }
                buildString {
                    append(scope)
                    append('\t')
                    append(contentKey)
                    append('\t')
                    if (confirmed.isNotEmpty()) append("c:").append(confirmed.joinToString(","))
                    if (pending.isNotEmpty()) {
                        if (confirmed.isNotEmpty()) append('\t')
                        append("p:").append(pending.joinToString(","))
                    }
                }
            }
        }
        .joinToString("\n")
}

internal fun decodeOwnAccountLedger(raw: String): Map<String, OwnAccountMarks> {
    if (raw.isBlank()) return emptyMap()
    val confirmed = mutableMapOf<String, MutableMap<String, Set<String>>>()
    val pending = mutableMapOf<String, MutableMap<String, Set<String>>>()
    raw.lineSequence().forEach { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@forEach
        val scope = parts[0].trim()
        val contentKey = parts[1].trim()
        if (scope.isEmpty() || contentKey.isEmpty()) return@forEach
        parts.drop(2).forEach { field ->
            val kind = field.substringBefore(':', missingDelimiterValue = "")
            val types = field.substringAfter(':', missingDelimiterValue = "")
                .split(',')
                .mapNotNull { canonicalFlagSegmentType(it) }
                .toSet()
            if (types.isEmpty()) return@forEach
            when (kind) {
                "c" -> confirmed.getOrPut(scope) { mutableMapOf() }[contentKey] = types
                "p" -> pending.getOrPut(scope) { mutableMapOf() }[contentKey] = types
            }
        }
    }
    val scopes = confirmed.keys + pending.keys
    return scopes.associateWith { scope ->
        OwnAccountMarks(
            confirmed = confirmed[scope].orEmpty(),
            pending = pending[scope].orEmpty(),
        )
    }
}
