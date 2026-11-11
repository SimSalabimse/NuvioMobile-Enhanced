package com.nuvio.app.features.player.skip

import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    /** Present when a title has more than one segment of a type, or a segment carries times. */
    val confirmedRows: Map<String, List<AccountSegment>> = emptyMap(),
    val pendingRows: Map<String, List<AccountSegment>> = emptyMap(),
)

internal object OwnTheIntroDbLedger {
    const val FIXTURE_SCOPE = "fixture"
    private const val STORE_KEY = "own_theintrodb_submissions_v2"
    private var cached: Map<String, OwnAccountMarks>? = null
    private val lock = SynchronizedObject()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun load(scope: String): OwnAccountMarks = synchronized(lock) {
        if (scope.isBlank()) return@synchronized OwnAccountMarks()
        read()[scope] ?: OwnAccountMarks()
    }

    fun remember(scope: String, contentKey: String, segmentType: String, status: OwnMarkStatus) = synchronized(lock) {
        if (scope.isBlank()) return@synchronized
        val next = read().toMutableMap()
        next[scope] = (next[scope] ?: OwnAccountMarks()).withStatus(contentKey, segmentType, status)
        write(next)
    }

    /** A later range of the same type is a new row. The first row of a type stays the legacy id. */
    fun rememberSegment(
        scope: String,
        contentKey: String,
        segmentType: String,
        status: OwnMarkStatus,
        startMs: Long? = null,
        endMs: Long? = null,
    ) = synchronized(lock) {
        if (scope.isBlank()) return@synchronized
        val next = read().toMutableMap()
        val written = recordSubmittedSegment(
            marks = next[scope] ?: OwnAccountMarks(),
            contentKey = contentKey,
            segmentType = segmentType,
            status = status,
            startMs = startMs,
            endMs = endMs,
            remoteCount = 0,
        )
        if (!written.accepted) return@synchronized
        next[scope] = written.marks
        write(next)
    }

    /**
     * One locked read of this account's rows, then the new segment, then the write.
     * Null when this account already has [MAX_SUBMISSIONS_PER_TYPE] of that type,
     * counting this account's remote rows and not the shared profile flag list.
     */
    fun reserveSegment(
        scope: String,
        contentKey: String,
        segmentType: String,
        status: OwnMarkStatus,
        startMs: Long?,
        endMs: Long?,
        remoteCount: Int,
    ): SubmittedSegmentWrite? = synchronized(lock) {
        if (scope.isBlank()) return@synchronized null
        val next = read().toMutableMap()
        val written = recordSubmittedSegment(
            marks = next[scope] ?: OwnAccountMarks(),
            contentKey = contentKey,
            segmentType = segmentType,
            status = status,
            startMs = startMs,
            endMs = endMs,
            remoteCount = remoteCount,
        )
        if (!written.accepted) return@synchronized null
        next[scope] = written.marks
        write(next)
        written
    }

    fun confirmSegment(scope: String, contentKey: String, segment: AccountSegment) = synchronized(lock) {
        if (scope.isBlank()) return@synchronized
        val next = read().toMutableMap()
        next[scope] = (next[scope] ?: OwnAccountMarks()).withSegment(contentKey, segment, OwnMarkStatus.Confirmed)
        write(next)
    }

    fun dropSegment(scope: String, contentKey: String, id: String) = synchronized(lock) {
        if (scope.isBlank() || id.isEmpty()) return@synchronized
        val marks = read()[scope] ?: return@synchronized
        val hasRows = contentKey in marks.confirmedRows || contentKey in marks.pendingRows
        val confirmedList = (if (hasRows) marks.confirmedRows[contentKey].orEmpty() else marks.confirmed[contentKey].orEmpty().map { AccountSegment.legacy(it) })
            .filter { it.id != id }
        val pendingList = (if (hasRows) marks.pendingRows[contentKey].orEmpty() else marks.pending[contentKey].orEmpty().map { AccountSegment.legacy(it) })
            .filter { it.id != id }
        val next = read().toMutableMap()
        next[scope] = marks.publishSegmentRows(contentKey, confirmedList, pendingList)
        write(next)
    }

    fun applyListing(
        scope: String,
        contentKey: String,
        confirmed: Set<String>,
        pending: Set<String>,
        rejected: Set<String>,
        confirmedRows: List<AccountSegment>? = null,
        pendingRows: List<AccountSegment>? = null,
    ) = synchronized(lock) {
        if (scope.isBlank()) return@synchronized
        val next = read().toMutableMap()
        next[scope] = applyAccountListing(
            marks = next[scope] ?: OwnAccountMarks(),
            contentKey = contentKey,
            confirmed = confirmed,
            pending = pending,
            rejected = rejected,
            confirmedRows = confirmedRows,
            pendingRows = pendingRows,
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
    ) = synchronized(lock) {
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

/** Segments for one episode. A legacy type set loads as one segment per type. */
internal fun OwnAccountMarks.segmentRows(contentKey: String): List<AccountSegment> {
    if (contentKey.isEmpty()) return emptyList()
    if (contentKey in confirmedRows || contentKey in pendingRows) {
        return confirmedRows[contentKey].orEmpty() + pendingRows[contentKey].orEmpty()
    }
    return (confirmed[contentKey].orEmpty() + pending[contentKey].orEmpty()).map { AccountSegment.legacy(it) }
}

internal fun OwnAccountMarks.withStatus(
    contentKey: String,
    segmentType: String,
    status: OwnMarkStatus,
): OwnAccountMarks {
    val type = canonicalFlagSegmentType(segmentType) ?: return this
    return withSegment(contentKey, AccountSegment.legacy(type), status)
}

internal fun OwnAccountMarks.withSegment(
    contentKey: String,
    segment: AccountSegment,
    status: OwnMarkStatus,
): OwnAccountMarks {
    if (contentKey.isEmpty()) return this
    val type = canonicalFlagSegmentType(segment.type) ?: return this
    val normalized = segment.copy(id = segment.id.ifBlank { type }, type = type)
    val hasRows = contentKey in confirmedRows || contentKey in pendingRows
    val confirmedList = (if (hasRows) confirmedRows[contentKey].orEmpty() else confirmed[contentKey].orEmpty().map { AccountSegment.legacy(it) }).toMutableList()
    val pendingList = (if (hasRows) pendingRows[contentKey].orEmpty() else pending[contentKey].orEmpty().map { AccountSegment.legacy(it) }).toMutableList()
    val existing = (confirmedList + pendingList).firstOrNull { it.id == normalized.id }
    if (existing == null && (confirmedList + pendingList).count { it.type == type } >= MAX_SUBMISSIONS_PER_TYPE) {
        return this
    }
    if (status == OwnMarkStatus.Pending && confirmedList.any { it.id == normalized.id }) return this
    confirmedList.removeAll { it.id == normalized.id }
    pendingList.removeAll { it.id == normalized.id }
    when (status) {
        OwnMarkStatus.Confirmed -> confirmedList += normalized
        OwnMarkStatus.Pending -> pendingList += normalized
    }
    return publishSegmentRows(contentKey, confirmedList, pendingList)
}

private fun OwnAccountMarks.publishSegmentRows(
    contentKey: String,
    confirmedList: List<AccountSegment>,
    pendingList: List<AccountSegment>,
): OwnAccountMarks {
    val all = confirmedList + pendingList
    val legacy = all.all { it.isLegacy() } && all.distinctBy { it.id }.size == all.size
    val confirmedMap = confirmed.toMutableMap()
    val pendingMap = pending.toMutableMap()
    val confirmedTypes = confirmedList.map { it.type }.toSet()
    val pendingTypes = pendingList.map { it.type }.toSet()
    if (confirmedTypes.isEmpty()) confirmedMap.remove(contentKey) else confirmedMap[contentKey] = confirmedTypes
    if (pendingTypes.isEmpty()) pendingMap.remove(contentKey) else pendingMap[contentKey] = pendingTypes
    val confirmedRowMap = confirmedRows.toMutableMap()
    val pendingRowMap = pendingRows.toMutableMap()
    if (legacy) {
        confirmedRowMap.remove(contentKey)
        pendingRowMap.remove(contentKey)
    } else {
        if (confirmedList.isEmpty()) confirmedRowMap.remove(contentKey) else confirmedRowMap[contentKey] = confirmedList
        if (pendingList.isEmpty()) pendingRowMap.remove(contentKey) else pendingRowMap[contentKey] = pendingList
    }
    return copy(
        confirmed = confirmedMap,
        pending = pendingMap,
        confirmedRows = confirmedRowMap,
        pendingRows = pendingRowMap,
    )
}

internal data class SubmittedSegmentWrite(
    val marks: OwnAccountMarks,
    val accepted: Boolean,
    val created: Boolean,
    val segment: AccountSegment?,
)

/**
 * Adds one segment for this account, or refuses when the account already has three.
 * [remoteCount] is this account's rows for the episode. The profile flag list is not an input.
 * The same start and end updates that row and does not take another slot.
 */
internal fun recordSubmittedSegment(
    marks: OwnAccountMarks,
    contentKey: String,
    segmentType: String,
    status: OwnMarkStatus,
    startMs: Long?,
    endMs: Long?,
    remoteCount: Int,
): SubmittedSegmentWrite {
    val type = canonicalFlagSegmentType(segmentType) ?: return SubmittedSegmentWrite(marks, false, false, null)
    if (contentKey.isEmpty()) return SubmittedSegmentWrite(marks, false, false, null)
    val rows = marks.segmentRows(contentKey).filter { it.type == type }
    val same = rows.firstOrNull { startMs != null && it.startMs == startMs && it.endMs == endMs }
    if (same != null) {
        return SubmittedSegmentWrite(marks.withSegment(contentKey, same, status), true, false, same)
    }
    if (!canAcceptSubmission(accountSubmissionCount(rows, type, remoteCount))) {
        return SubmittedSegmentWrite(marks, false, false, null)
    }
    val segment = if (rows.isEmpty() && startMs == null) {
        AccountSegment.legacy(type)
    } else {
        AccountSegment(
            id = nextSubmissionId(type, marks.segmentRows(contentKey)),
            type = type,
            startMs = startMs,
            endMs = endMs,
        )
    }
    return SubmittedSegmentWrite(marks.withSegment(contentKey, segment, status), true, true, segment)
}

/**
 * Listing rows for this account and this episode.
 * A remote pending status is not confirmed. A silent type keeps the cached row
 * so an offline or partial list does not wipe it. Rejected rows are dropped.
 * A null row list leaves stored start and end in place.
 */
internal fun applyAccountListing(
    marks: OwnAccountMarks,
    contentKey: String,
    confirmed: Set<String>,
    pending: Set<String>,
    rejected: Set<String>,
    confirmedRows: List<AccountSegment>? = null,
    pendingRows: List<AccountSegment>? = null,
): OwnAccountMarks {
    if (contentKey.isEmpty()) return marks
    val rejectedTypes = rejected.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) }
    val remoteConfirmed = confirmed.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - rejectedTypes
    val remotePending = pending.mapNotNullTo(hashSetOf()) { canonicalFlagSegmentType(it) } - rejectedTypes - remoteConfirmed
    val nextConfirmed = ((marks.confirmed[contentKey].orEmpty() - rejectedTypes) - remotePending) + remoteConfirmed
    val nextPending = ((marks.pending[contentKey].orEmpty() - rejectedTypes) + remotePending) - nextConfirmed
    if (confirmedRows == null && pendingRows == null && contentKey !in marks.confirmedRows && contentKey !in marks.pendingRows) {
        val confirmedMap = marks.confirmed.toMutableMap()
        val pendingMap = marks.pending.toMutableMap()
        if (nextConfirmed.isEmpty()) confirmedMap.remove(contentKey) else confirmedMap[contentKey] = nextConfirmed
        if (nextPending.isEmpty()) pendingMap.remove(contentKey) else pendingMap[contentKey] = nextPending
        return marks.copy(confirmed = confirmedMap, pending = pendingMap)
    }
    if (confirmedRows == null && pendingRows == null) {
        val local = marks.segmentRows(contentKey)
        val keptConfirmed = local.filter { it.type in nextConfirmed }
        val keptPending = local.filter { row -> row.type in nextPending && keptConfirmed.none { it.id == row.id } }
        val extraConfirmed = (nextConfirmed - keptConfirmed.map { it.type }.toSet()).map { AccountSegment.legacy(it) }
        val extraPending = (nextPending - keptPending.map { it.type }.toSet()).map { AccountSegment.legacy(it) }
        return marks.publishSegmentRows(contentKey, keptConfirmed + extraConfirmed, keptPending + extraPending)
    }
    val remoteConfirmedRows = confirmedRows.orEmpty().filter { it.type !in rejectedTypes }
    val remotePendingRows = pendingRows.orEmpty().filter { it.type !in rejectedTypes && it.type !in remoteConfirmed }
    val keptTypes = (remoteConfirmedRows + remotePendingRows).map { it.type }.toSet()
    val local = marks.segmentRows(contentKey).filter { it.type !in (rejectedTypes - keptTypes) }
    val keptConfirmed = longerPerType(local.filter { it.type in nextConfirmed }, remoteConfirmedRows)
    val keptPending = longerPerType(local.filter { it.type in nextPending }, remotePendingRows)
        .filter { row -> keptConfirmed.none { it.id == row.id } }
    return marks.publishSegmentRows(contentKey, keptConfirmed, keptPending)
}

/** The longer list wins for each type, so a just-sent third is not replaced by a shorter server list. */
private fun longerPerType(local: List<AccountSegment>, remote: List<AccountSegment>): List<AccountSegment> {
    val types = (local.map { it.type } + remote.map { it.type }).distinct()
    return types.flatMap { type ->
        val left = local.filter { it.type == type }
        val right = remote.filter { it.type == type }
        when {
            right.size > left.size -> right
            right.isEmpty() -> left
            left.size > right.size -> left
            else -> right
        }
    }
}

internal fun encodeOwnAccountLedger(entries: Map<String, OwnAccountMarks>): String {
    return entries.entries
        .sortedBy { it.key }
        .flatMap { (scope, marks) ->
            val keys = (marks.confirmed.keys + marks.pending.keys + marks.confirmedRows.keys + marks.pendingRows.keys).sorted()
            keys.mapNotNull { contentKey ->
                if (scope.isBlank() || contentKey.isBlank()) return@mapNotNull null
                if (scope.contains('\t') || scope.contains('\n') || contentKey.contains('\t') || contentKey.contains('\n')) {
                    return@mapNotNull null
                }
                val confirmedRows = marks.confirmedRows[contentKey]
                val pendingRows = marks.pendingRows[contentKey]
                val confirmedField = if (confirmedRows != null || pendingRows != null) {
                    encodeSegmentField("c", confirmedRows.orEmpty())
                } else {
                    val types = marks.confirmed[contentKey].orEmpty().mapNotNull { canonicalFlagSegmentType(it) }.distinct().sorted()
                    if (types.isEmpty()) null else "c:" + types.joinToString(",")
                }
                val pendingField = if (confirmedRows != null || pendingRows != null) {
                    encodeSegmentField("p", pendingRows.orEmpty())
                } else {
                    val types = marks.pending[contentKey].orEmpty().mapNotNull { canonicalFlagSegmentType(it) }.distinct().sorted()
                    if (types.isEmpty()) null else "p:" + types.joinToString(",")
                }
                if (confirmedField == null && pendingField == null) return@mapNotNull null
                listOfNotNull(scope, contentKey, confirmedField, pendingField).joinToString("\t")
            }
        }
        .joinToString("\n")
}

private fun encodeSegmentField(kind: String, rows: List<AccountSegment>): String? {
    val tokens = rows.mapNotNull { encodeAccountSegment(it) }
    if (tokens.isEmpty()) return null
    return "$kind:" + tokens.joinToString(",")
}

private fun encodeAccountSegment(segment: AccountSegment): String? {
    val type = canonicalFlagSegmentType(segment.type) ?: return null
    val id = segment.id.ifBlank { type }
    if (id.any { it == ',' || it == '\t' || it == '@' || it == ':' || it == '\n' }) return null
    if (id == type && segment.startMs == null && segment.endMs == null) return type
    val times = if (segment.startMs != null && segment.endMs != null) ":${segment.startMs}:${segment.endMs}" else ""
    return "$id@$type$times"
}

internal fun decodeOwnAccountLedger(raw: String): Map<String, OwnAccountMarks> {
    if (raw.isBlank()) return emptyMap()
    val built = mutableMapOf<String, OwnAccountMarks>()
    raw.lineSequence().forEach { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@forEach
        val scope = parts[0].trim()
        val contentKey = parts[1].trim()
        if (scope.isEmpty() || contentKey.isEmpty()) return@forEach
        var marks = built[scope] ?: OwnAccountMarks()
        parts.drop(2).forEach { field ->
            val kind = field.substringBefore(':', missingDelimiterValue = "")
            val body = field.substringAfter(':', missingDelimiterValue = "")
            if (kind != "c" && kind != "p") return@forEach
            val status = if (kind == "c") OwnMarkStatus.Confirmed else OwnMarkStatus.Pending
            val extended = body.split(',').any { '@' in it }
            if (!extended) {
                body.split(',').mapNotNull { canonicalFlagSegmentType(it.trim()) }.forEach { type ->
                    marks = marks.withSegment(contentKey, AccountSegment.legacy(type), status)
                }
            } else {
                decodeSegmentTokens(body).forEach { segment ->
                    marks = marks.withSegment(contentKey, segment, status)
                }
            }
        }
        built[scope] = marks
    }
    return built
}

private fun decodeSegmentTokens(body: String): List<AccountSegment> =
    body.split(',').mapNotNull { token ->
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        if ('@' !in trimmed) {
            val type = canonicalFlagSegmentType(trimmed) ?: return@mapNotNull null
            return@mapNotNull AccountSegment.legacy(type)
        }
        val id = trimmed.substringBefore('@')
        val rest = trimmed.substringAfter('@')
        val type = canonicalFlagSegmentType(rest.substringBefore(':')) ?: return@mapNotNull null
        val pieces = rest.split(':')
        AccountSegment(
            id = id.ifBlank { type },
            type = type,
            startMs = pieces.getOrNull(1)?.toLongOrNull(),
            endMs = pieces.getOrNull(2)?.toLongOrNull(),
        )
    }
