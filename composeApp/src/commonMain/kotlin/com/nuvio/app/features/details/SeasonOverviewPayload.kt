package com.nuvio.app.features.details

/**
 * Wire format for the iOS list. Swift parses the same lines.
 * Text fields escape `\`, newlines, and the field separator, so a title cannot split a row.
 *
 * Line 1: `SO1`
 * Line 2: header fields, separated by U+001F
 * Later lines: one episode each
 *
 * Header: title, status, selecting, selectLabel, downloadLabel, running, buttonEnabled,
 * downloadOffered, open, watch, include, remove, swipeInstalled, watched, legend.
 * Row: id, episode, title, watched, publicLine, included, swipe, states `Y,U,M`,
 * four symbol labels, spoken, yoursLine.
 * `Y` yours, `U` public, `M` missing. Yours is this account only. Swipe is `1` even with Reduce Motion.
 */
internal const val SEASON_OVERVIEW_PAYLOAD_VERSION = "SO1"

private const val SEASON_OVERVIEW_FIELD = '\u001f'

internal fun seasonOverviewPayload(ui: SeasonOverviewListUi): String {
    val header = seasonOverviewJoin(
        listOf(
            ui.title,
            ui.statusLine.orEmpty(),
            flag(ui.selecting),
            ui.selectLabel,
            ui.downloadLabel,
            flag(ui.running),
            flag(ui.buttonEnabled),
            flag(ui.downloadOffered),
            ui.openLabel,
            ui.watchLabel,
            ui.includeLabel,
            ui.removeLabel,
            flag(ui.swipeActionsInstalled),
            ui.watchedLabel,
            ui.legend,
        ),
    )
    val rows = ui.rows.joinToString("\n") { row ->
        val states = row.symbols.joinToString(",") { it.presence.wireCode().toString() }
        seasonOverviewJoin(
            listOf(
                row.contentKey,
                row.episodeNumber.toString(),
                row.title,
                flag(row.watched),
                row.publicLine,
                flag(row.included),
                flag(row.swipeWatchPrivately),
                states,
                row.symbols.getOrNull(0)?.accessibility.orEmpty(),
                row.symbols.getOrNull(1)?.accessibility.orEmpty(),
                row.symbols.getOrNull(2)?.accessibility.orEmpty(),
                row.symbols.getOrNull(3)?.accessibility.orEmpty(),
                row.spoken,
                row.yoursLine,
            ),
        )
    }
    return if (rows.isEmpty()) {
        "$SEASON_OVERVIEW_PAYLOAD_VERSION\n$header"
    } else {
        "$SEASON_OVERVIEW_PAYLOAD_VERSION\n$header\n$rows"
    }
}

internal fun seasonOverviewReadPayload(payload: String): SeasonOverviewListUi? {
    val lines = payload.split('\n')
    if (lines.size < 2 || lines[0] != SEASON_OVERVIEW_PAYLOAD_VERSION) return null
    val header = seasonOverviewSplit(lines[1])
    if (header.size != 15) return null
    val rows = lines.drop(2).map { line ->
        val fields = seasonOverviewSplit(line)
        if (fields.size != 14) return null
        val states = fields[7].split(',')
        if (states.size != 4) return null
        val symbols = List(4) { index ->
            val presence = presenceFromWire(states[index].singleOrNull() ?: return null) ?: return null
            val kind = SeasonOverviewSymbolKind.entries[index]
            SeasonOverviewSymbolUi(
                kind = kind,
                presence = presence,
                token = "${kind.name}:${presence.name}",
                accessibility = fields[8 + index],
            )
        }
        SeasonOverviewRowUi(
            contentKey = fields[0],
            episodeNumber = fields[1].toIntOrNull() ?: return null,
            title = fields[2],
            watched = fields[3] == "1",
            publicLine = fields[4],
            yoursLine = fields[13],
            symbols = symbols,
            included = fields[5] == "1",
            swipeWatchPrivately = fields[6] == "1",
            spoken = fields[12],
        )
    }
    val downloadOffered = header[7] == "1"
    return SeasonOverviewListUi(
        title = header[0],
        rows = rows,
        statusLine = header[1].ifEmpty { null },
        selecting = header[2] == "1",
        selectLabel = header[3],
        selectedCount = 0,
        chosenCount = 0,
        downloadLabel = header[4],
        running = header[5] == "1",
        buttonEnabled = header[6] == "1",
        downloadOffered = downloadOffered,
        reduceMotion = false,
        swipeActionsInstalled = header[12] == "1",
        openLabel = header[8],
        watchLabel = header[9],
        includeLabel = header[10],
        removeLabel = header[11],
        watchedLabel = header[13],
        legend = header[14],
        contextActions = if (downloadOffered) {
            seasonOverviewContextActionIds
        } else {
            listOf("open", "watchPrivately")
        },
    )
}

internal fun seasonOverviewEscape(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            SEASON_OVERVIEW_FIELD -> append("\\f")
            else -> append(ch)
        }
    }
}

internal fun seasonOverviewUnescape(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            when (value[index + 1]) {
                '\\' -> append('\\')
                'n' -> append('\n')
                'r' -> append('\r')
                'f' -> append(SEASON_OVERVIEW_FIELD)
                else -> {
                    append(ch)
                    index += 1
                    continue
                }
            }
            index += 2
        } else {
            append(ch)
            index += 1
        }
    }
}

private fun seasonOverviewJoin(parts: List<String>): String =
    parts.joinToString(SEASON_OVERVIEW_FIELD.toString(), transform = ::seasonOverviewEscape)

private fun seasonOverviewSplit(line: String): List<String> =
    line.split(SEASON_OVERVIEW_FIELD).map(::seasonOverviewUnescape)

private fun flag(value: Boolean): String = if (value) "1" else "0"
