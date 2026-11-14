package com.nuvio.app.features.details

/**
 * Read-only rows for the season overview sheet.
 * Building this model does not write watch progress, submit to TheIntroDB, or touch the ledger.
 * [write] is the only side-effect hook, and [seasonOverviewListUi] does not call it.
 */
internal fun interface SeasonOverviewWrite {
    fun record(kind: String)
}

/** Shape of one segment symbol. Colour is not the only difference. */
internal enum class SeasonMarkSymbolState {
    /** Filled, and the only state that uses the accent. */
    OwnConfirmed,
    /** Outline. This account, still pending. */
    OwnPending,
    /** Tinted. Found on this phone, not submitted. */
    DetectedHere,
    /** Dashed and dim. Not found. */
    Missing,
}

internal enum class SeasonOverviewSymbolKind {
    Intro,
    Recap,
    Credits,
    Preview,
}

/**
 * What the row says about one segment type.
 * Yours comes only from this account's submissions. Public is TheIntroDB and not yours.
 * A local detection is not a submission, so it is Missing unless TheIntroDB lists it.
 */
internal enum class SeasonSegmentPresence {
    Yours,
    Public,
    Missing,
}

internal data class SeasonOverviewSymbolUi(
    val kind: SeasonOverviewSymbolKind,
    val presence: SeasonSegmentPresence,
    /** Changes only when [presence] changes, so a replace effect can run once. */
    val token: String,
    /** Spoken and visible words, for example "Intro: yours". */
    val accessibility: String,
)

internal data class SeasonOverviewRowUi(
    val contentKey: String,
    val episodeNumber: Int,
    val title: String,
    val watched: Boolean,
    /** Public catalogue only. Never this account's own marks. */
    val publicLine: String,
    /** This account's submissions only. Empty when there are none. */
    val yoursLine: String,
    val symbols: List<SeasonOverviewSymbolUi>,
    val included: Boolean,
    /** System swipe action. Stays on when Reduce Motion is on. */
    val swipeWatchPrivately: Boolean,
    val spoken: String,
)

internal data class SeasonOverviewListUi(
    val title: String,
    val rows: List<SeasonOverviewRowUi>,
    /** One short line when the list has no episodes. Null when rows are shown. */
    val statusLine: String?,
    val selecting: Boolean,
    val selectLabel: String,
    val selectedCount: Int,
    val chosenCount: Int,
    val downloadLabel: String,
    val running: Boolean,
    val buttonEnabled: Boolean,
    val downloadOffered: Boolean,
    val reduceMotion: Boolean,
    /** The list installs the swipe action even when [reduceMotion] is true. */
    val swipeActionsInstalled: Boolean,
    val openLabel: String,
    val watchLabel: String,
    val includeLabel: String,
    val removeLabel: String,
    val watchedLabel: String,
    /** One sentence for the three segment words. Shown above the rows. */
    val legend: String,
    /** Long-press menu, in order. Include is omitted when download is not offered. */
    val contextActions: List<String>,
)

internal data class SeasonOverviewCopy(
    val intro: String,
    val recap: String,
    val credits: String,
    val preview: String,
    val publicNone: String,
    val publicNotChecked: String,
    val publicUnavailable: String,
    val publicListedTemplate: String,
    val emptySeason: String,
    val offline: String,
    val loading: String,
    val downloadAll: String,
    val downloadCountTemplate: String,
    val downloadSelectedTemplate: String,
    val cancel: String,
    val select: String,
    val done: String,
    val open: String,
    val watchPrivately: String,
    val includeDownload: String,
    val removeDownload: String,
    val watched: String,
    val submittedTemplate: String,
    val pendingTemplate: String,
    val detectedTemplate: String,
    val missingTemplate: String,
    val yoursWord: String,
    val publicWord: String,
    val missingWord: String,
    val presenceTemplate: String,
    val yoursLineTemplate: String,
    val yoursCountTemplate: String,
    val legend: String,
)

internal fun englishSeasonOverviewCopy(): SeasonOverviewCopy = SeasonOverviewCopy(
    intro = "Intro",
    recap = "Recap",
    credits = "Credits",
    preview = "Preview",
    publicNone = "Public: none",
    publicNotChecked = "Public: not checked",
    publicUnavailable = "Public: unavailable",
    publicListedTemplate = "Public: %1\$s",
    emptySeason = "No episodes yet",
    offline = "Offline",
    loading = "Loading",
    downloadAll = "Download for analysis",
    downloadCountTemplate = "Download %1\$d for analysis",
    downloadSelectedTemplate = "Download %1\$d",
    cancel = "Cancel",
    select = "Select",
    done = "Done",
    open = "Open",
    watchPrivately = "Watch privately",
    includeDownload = "Include in download",
    removeDownload = "Remove from download",
    watched = "Watched",
    submittedTemplate = "%1\$s, submitted from this account",
    pendingTemplate = "%1\$s, pending",
    detectedTemplate = "%1\$s. Detected, not submitted yet",
    missingTemplate = "%1\$s, missing",
    yoursWord = "yours",
    publicWord = "public",
    missingWord = "missing",
    presenceTemplate = "%1\$s: %2\$s",
    yoursLineTemplate = "You submitted: %1\$s",
    yoursCountTemplate = "%1\$s ×%2\$d",
    legend = "Yours — you submitted it. Public — on TheIntroDB, not from you. Missing — nothing yet.",
)

internal val seasonOverviewContextActionIds: List<String> = listOf(
    "open",
    "watchPrivately",
    "includeInDownload",
)

internal fun seasonMarkSymbolState(mark: SegmentMark): SeasonMarkSymbolState = when (mark) {
    SegmentMark.Submitted -> SeasonMarkSymbolState.OwnConfirmed
    SegmentMark.Pending -> SeasonMarkSymbolState.OwnPending
    SegmentMark.Detected -> SeasonMarkSymbolState.DetectedHere
    SegmentMark.Missing -> SeasonMarkSymbolState.Missing
}

internal fun SeasonMarkSymbolState.wireCode(): Char = when (this) {
    SeasonMarkSymbolState.OwnConfirmed -> 'C'
    SeasonMarkSymbolState.OwnPending -> 'P'
    SeasonMarkSymbolState.DetectedHere -> 'D'
    SeasonMarkSymbolState.Missing -> 'M'
}

internal fun symbolStateFromWire(code: Char): SeasonMarkSymbolState? = when (code) {
    'C' -> SeasonMarkSymbolState.OwnConfirmed
    'P' -> SeasonMarkSymbolState.OwnPending
    'D' -> SeasonMarkSymbolState.DetectedHere
    'M' -> SeasonMarkSymbolState.Missing
    else -> null
}

/**
 * One word for the row. A pending submission is still yours.
 * Public is used only when this account did not submit that type.
 */
internal fun seasonSegmentPresence(mark: SegmentMark, listedPublicly: Boolean): SeasonSegmentPresence = when {
    mark == SegmentMark.Submitted || mark == SegmentMark.Pending -> SeasonSegmentPresence.Yours
    listedPublicly -> SeasonSegmentPresence.Public
    else -> SeasonSegmentPresence.Missing
}

internal fun SeasonSegmentPresence.wireCode(): Char = when (this) {
    SeasonSegmentPresence.Yours -> 'Y'
    SeasonSegmentPresence.Public -> 'U'
    SeasonSegmentPresence.Missing -> 'M'
}

internal fun presenceFromWire(code: Char): SeasonSegmentPresence? = when (code) {
    'Y' -> SeasonSegmentPresence.Yours
    'U' -> SeasonSegmentPresence.Public
    'M' -> SeasonSegmentPresence.Missing
    else -> null
}

internal fun seasonOverviewPresenceWord(presence: SeasonSegmentPresence, copy: SeasonOverviewCopy): String = when (presence) {
    SeasonSegmentPresence.Yours -> copy.yoursWord
    SeasonSegmentPresence.Public -> copy.publicWord
    SeasonSegmentPresence.Missing -> copy.missingWord
}

internal data class SeasonOverviewToolbar(
    val selecting: Boolean,
    val selectLabel: String,
    val selectedCount: Int,
    val chosenCount: Int,
    val downloadLabel: String,
    val running: Boolean,
    val buttonEnabled: Boolean,
)

/**
 * Select mode labels the button "Download N" and counts only the checks.
 * Otherwise the button keeps today's wording: every episode, or "Download N for analysis".
 * While a download is running the button is Cancel.
 */
internal fun seasonOverviewToolbar(
    selecting: Boolean,
    hasSelection: Boolean,
    selectedCount: Int,
    episodeCount: Int,
    running: Boolean,
    downloadAllowed: Boolean,
    copy: SeasonOverviewCopy,
): SeasonOverviewToolbar {
    val narrowing = selecting || hasSelection
    val chosenCount = if (narrowing) selectedCount else episodeCount
    val downloadLabel = when {
        running -> copy.cancel
        selecting -> formatCount(copy.downloadSelectedTemplate, selectedCount)
        !hasSelection -> copy.downloadAll
        else -> formatCount(copy.downloadCountTemplate, selectedCount)
    }
    return SeasonOverviewToolbar(
        selecting = selecting,
        selectLabel = if (selecting) copy.done else copy.select,
        selectedCount = selectedCount,
        chosenCount = chosenCount,
        downloadLabel = downloadLabel,
        running = running,
        buttonEnabled = running || (downloadAllowed && chosenCount > 0),
    )
}

/** Public catalogue sentence. Own marks are not an input. */
internal fun seasonOverviewPublicLine(
    read: CatalogRead?,
    publicTypes: Set<String>,
    copy: SeasonOverviewCopy,
): String = when (read) {
    null, CatalogRead.NotChecked -> copy.publicNotChecked
    CatalogRead.Unavailable -> copy.publicUnavailable
    CatalogRead.None -> copy.publicNone
    CatalogRead.Listed -> {
        val names = SEASON_SEGMENT_TYPES.mapNotNull { type ->
            if (type !in publicTypes) null else seasonOverviewTypeLabel(type, copy)
        }
        if (names.isEmpty()) copy.publicNone else formatArg(copy.publicListedTemplate, names.joinToString(", "))
    }
}

internal fun seasonOverviewStatusText(state: SeasonOverviewUiState, copy: SeasonOverviewCopy): String? = when (state) {
    SeasonOverviewUiState.Loading -> copy.loading
    SeasonOverviewUiState.Offline -> copy.offline
    SeasonOverviewUiState.EmptySeason -> copy.emptySeason
    SeasonOverviewUiState.AllSubmitted,
    SeasonOverviewUiState.NoneSubmitted,
    SeasonOverviewUiState.Partial,
    -> null
}

internal fun seasonOverviewSymbols(
    marks: Map<String, SegmentMark>,
    publicTypes: Set<String>,
    copy: SeasonOverviewCopy,
): List<SeasonOverviewSymbolUi> = listOf(
    SeasonOverviewSymbolKind.Intro to "intro",
    SeasonOverviewSymbolKind.Recap to "recap",
    SeasonOverviewSymbolKind.Credits to "outro",
    SeasonOverviewSymbolKind.Preview to "preview",
).map { (kind, type) ->
    val label = seasonOverviewTypeLabel(type, copy)
    val presence = seasonSegmentPresence(
        mark = marks[type] ?: SegmentMark.Missing,
        listedPublicly = type in publicTypes,
    )
    SeasonOverviewSymbolUi(
        kind = kind,
        presence = presence,
        token = "${kind.name}:${presence.name}",
        accessibility = formatArgs(copy.presenceTemplate, label, seasonOverviewPresenceWord(presence, copy)),
    )
}

/**
 * Names from this account's own rows. A count above one is written out.
 * Public types are not an input.
 */
internal fun seasonOverviewYoursLine(ownCounts: Map<String, Int>, copy: SeasonOverviewCopy): String {
    val names = listOf(
        "intro" to copy.intro,
        "recap" to copy.recap,
        "outro" to copy.credits,
        "preview" to copy.preview,
    ).mapNotNull { (type, label) ->
        val count = ownCounts[type] ?: 0
        when {
            count <= 0 -> null
            count == 1 -> label
            else -> formatCountArg(copy.yoursCountTemplate, label, count)
        }
    }
    if (names.isEmpty()) return ""
    return formatArg(copy.yoursLineTemplate, names.joinToString(", "))
}

@Suppress("UNUSED_PARAMETER")
internal fun seasonOverviewListUi(
    title: String,
    state: SeasonOverviewUiState,
    rows: List<EpisodeSegmentOverview>,
    facts: Map<String, EpisodeCatalogFacts>,
    selecting: Boolean,
    hasSelection: Boolean,
    selectedCount: Int,
    episodeCount: Int,
    running: Boolean,
    reduceMotion: Boolean,
    fromDownloads: Boolean,
    downloadAllowed: Boolean,
    selectedKeys: Set<String>,
    copy: SeasonOverviewCopy,
    write: SeasonOverviewWrite? = null,
): SeasonOverviewListUi {
    val statusLine = seasonOverviewStatusText(state, copy)
    val toolbar = seasonOverviewToolbar(
        selecting = selecting,
        hasSelection = hasSelection,
        selectedCount = selectedCount,
        episodeCount = episodeCount,
        running = running,
        downloadAllowed = downloadAllowed,
        copy = copy,
    )
    val downloadOffered = !fromDownloads && statusLine == null
    val visible = if (statusLine != null) {
        emptyList()
    } else {
        rows.map { row ->
            seasonOverviewRowUi(
                row = row,
                facts = facts[row.contentKey],
                included = row.contentKey in selectedKeys,
                selecting = selecting && downloadOffered,
                copy = copy,
            )
        }
    }
    val contextActions = if (downloadOffered) {
        seasonOverviewContextActionIds
    } else {
        listOf("open", "watchPrivately")
    }
    return SeasonOverviewListUi(
        title = title,
        rows = visible,
        statusLine = statusLine,
        selecting = selecting && downloadOffered,
        selectLabel = toolbar.selectLabel,
        selectedCount = toolbar.selectedCount,
        chosenCount = toolbar.chosenCount,
        downloadLabel = toolbar.downloadLabel,
        running = running,
        buttonEnabled = toolbar.buttonEnabled,
        downloadOffered = downloadOffered,
        reduceMotion = reduceMotion,
        swipeActionsInstalled = true,
        openLabel = copy.open,
        watchLabel = copy.watchPrivately,
        includeLabel = copy.includeDownload,
        removeLabel = copy.removeDownload,
        watchedLabel = copy.watched,
        legend = copy.legend,
        contextActions = contextActions,
    )
}

private fun seasonOverviewRowUi(
    row: EpisodeSegmentOverview,
    facts: EpisodeCatalogFacts?,
    included: Boolean,
    selecting: Boolean,
    copy: SeasonOverviewCopy,
): SeasonOverviewRowUi {
    val publicTypes = if (facts?.publicRead == CatalogRead.Listed) facts.publicTypes else emptySet()
    val publicLine = seasonOverviewPublicLine(
        read = facts?.publicRead,
        publicTypes = publicTypes,
        copy = copy,
    )
    val yoursLine = seasonOverviewYoursLine(row.ownCounts, copy)
    val symbols = seasonOverviewSymbols(row.marks, publicTypes, copy)
    val spokenBody = buildString {
        append(row.episode.toString())
        if (row.watched) {
            append(", ")
            append(copy.watched)
        }
        append(", ")
        append(row.title)
        append(". ")
        append(publicLine)
        if (yoursLine.isNotEmpty()) {
            append(". ")
            append(yoursLine)
        }
        symbols.forEach { symbol ->
            append(". ")
            append(symbol.accessibility)
        }
    }
    val spoken = if (selecting) {
        val mark = if (included) copy.removeDownload else copy.includeDownload
        "$mark. $spokenBody"
    } else {
        spokenBody
    }
    return SeasonOverviewRowUi(
        contentKey = row.contentKey,
        episodeNumber = row.episode,
        title = row.title,
        watched = row.watched,
        publicLine = publicLine,
        yoursLine = yoursLine,
        symbols = symbols,
        included = included,
        swipeWatchPrivately = true,
        spoken = spoken,
    )
}

private fun seasonOverviewTypeLabel(type: String, copy: SeasonOverviewCopy): String = when (type) {
    "intro" -> copy.intro
    "recap" -> copy.recap
    "outro" -> copy.credits
    else -> copy.preview
}

/** AX1 and above, including AX5, put the four symbols on their own line. */
internal fun seasonOverviewWrapsSymbols(fontScale: Float): Boolean =
    accessibilityTextSize(fontScale)

/** Symbol replace and variable-color effects. Reduce Motion turns them off. The swipe stays. */
internal fun seasonOverviewPlaysSymbolEffects(reduceMotion: Boolean): Boolean = !reduceMotion

/**
 * Compose sheets, including Liquid Glass, keep the partial detent.
 * The system sheet does not read this. It already offers medium and large.
 */
internal fun seasonOverviewSkipsPartialDetent(): Boolean = false

/** The system sheet gives the list a real height. Fill that detent, and nowhere else. */
internal fun seasonOverviewFillsNativeDetent(nativeList: Boolean, nativeSheet: Boolean): Boolean =
    nativeList && nativeSheet

internal fun formatCount(template: String, count: Int): String =
    template.replace("%1\$d", count.toString()).replace("%d", count.toString())

internal fun formatArg(template: String, value: String): String =
    template.replace("%1\$s", value).replace("%s", value)

internal fun formatArgs(template: String, first: String, second: String): String =
    template.replace("%1\$s", first).replace("%2\$s", second)

internal fun formatCountArg(template: String, label: String, count: Int): String =
    template.replace("%1\$s", label).replace("%2\$d", count.toString()).replace("%d", count.toString())
