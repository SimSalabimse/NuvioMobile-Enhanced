package com.nuvio.app.features.player.skip

/**
 * Whose time a segment chip is allowed to claim.
 *
 * [Own] is a mark this account set, or a type in [OwnTheIntroDbLedger] for this account's
 * hashed key. A public TheIntroDB catalogue time and an on-device detection are never [Own].
 * The same episode can carry [Own] and [Public] together. Another account's ledger scope is ignored.
 */
internal enum class SegmentOrigin {
    Own,
    Public,
    Detected,
}

internal const val LABEL_YOUR_MARK = "Your mark"
internal const val LABEL_THE_INTRO_DB = "TheIntroDB"
internal const val LABEL_DETECTED = "Detected"

internal data class EpisodeSegmentLabels(
    val own: Set<String> = emptySet(),
    val publicCatalogue: Set<String> = emptySet(),
    val detected: Set<String> = emptySet(),
) {
    fun labelsFor(type: String): List<String> {
        val key = canonicalFlagSegmentType(type) ?: return emptyList()
        return buildList {
            if (key in own) add(LABEL_YOUR_MARK)
            if (key in publicCatalogue) add(LABEL_THE_INTRO_DB)
            if (key in detected) add(LABEL_DETECTED)
        }
    }
}

internal fun attributeEpisodeSegments(
    contentKey: String,
    accountScope: String,
    ledgerByScope: Map<String, OwnAccountMarks>,
    manualTypes: Set<String>,
    publicTypes: Set<String>,
    detectedTypes: Set<String>,
): EpisodeSegmentLabels {
    val fromLedger = if (accountScope.isBlank() || contentKey.isEmpty()) {
        emptySet()
    } else {
        ownSentTypes(ledgerByScope[accountScope] ?: OwnAccountMarks(), contentKey)
    }
    val manual = manualTypes.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) }
    val own = manual + fromLedger
    val publicCatalogue = publicTypes.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) }
    val detected = detectedTypes.mapNotNullTo(linkedSetOf()) { canonicalFlagSegmentType(it) } - own
    return EpisodeSegmentLabels(
        own = own,
        publicCatalogue = publicCatalogue,
        detected = detected,
    )
}
