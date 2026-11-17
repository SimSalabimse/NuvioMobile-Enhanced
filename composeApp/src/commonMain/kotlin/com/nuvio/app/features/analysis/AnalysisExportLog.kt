package com.nuvio.app.features.analysis

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Stored beside the analysis files. It is not the diagnostics log. */
internal const val ANALYSIS_EXPORT_FILE = "analysis-export-log"

internal const val ANALYSIS_EXPORT_MAX_LINES = 240
internal const val ANALYSIS_EXPORT_MAX_DETAIL = 160

internal enum class AnalysisExportStatus { Empty, Ready }

internal data class AnalysisExportDocument(
    val status: AnalysisExportStatus,
    val lineCount: Int,
    val text: String,
)

/**
 * One bounded line for a later export. Job ids stay. Secrets, full URLs, and personal paths do not.
 * [atMs] is the wall clock, so the gap between two lines is how long that step ran.
 */
internal class AnalysisExportBuffer(
    private val maxLines: Int = ANALYSIS_EXPORT_MAX_LINES,
    private val clock: () -> Long = { 0L },
) {
    private val lines = ArrayDeque<String>()

    fun record(jobId: String, kind: String, stage: String, detail: String = "") {
        val safeJob = sanitizeToken(jobId, max = 64).ifEmpty { "-" }
        val safeKind = sanitizeToken(kind, max = 16).ifEmpty { "note" }
        val safeStage = sanitizeToken(stage, max = 24).ifEmpty { "-" }
        val safeDetail = redactAnalysisExport(detail).take(ANALYSIS_EXPORT_MAX_DETAIL).trim()
        val line = buildString {
            append("atMs=")
            append(clock().coerceAtLeast(0L))
            append(" job=")
            append(safeJob)
            append(" kind=")
            append(safeKind)
            append(" stage=")
            append(safeStage)
            if (safeDetail.isNotEmpty()) {
                append(' ')
                append(safeDetail)
            }
        }
        if (lines.size >= maxLines) lines.removeFirst()
        lines.addLast(line)
    }

    fun restore(stored: String) {
        lines.clear()
        stored.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("atMs=") }
            .toList()
            .takeLast(maxLines)
            .forEach { lines.addLast(redactAnalysisExport(it)) }
    }

    fun snapshot(): String = lines.joinToString(separator = "\n")

    fun export(): AnalysisExportDocument {
        if (lines.isEmpty()) {
            return AnalysisExportDocument(
                status = AnalysisExportStatus.Empty,
                lineCount = 0,
                text = "status=empty lines=0 file=$ANALYSIS_EXPORT_FILE separate-from-diagnostics\n",
            )
        }
        val body = lines.joinToString(separator = "\n", postfix = "\n")
        val text = "status=ready lines=${lines.size} maxLines=$maxLines file=$ANALYSIS_EXPORT_FILE separate-from-diagnostics\n$body"
        return AnalysisExportDocument(AnalysisExportStatus.Ready, lines.size, text)
    }
}

/**
 * Persistent analysis steps for Settings › Debug › Export analysis log.
 * Written only when a step changes. Export is a separate call and does not upload.
 */
internal object AnalysisExportLog {
    private val lock = SynchronizedObject()
    private val buffer = AnalysisExportBuffer(clock = { com.nuvio.app.features.streams.epochMs() })
    private var loaded = false

    fun record(jobId: String, kind: String, stage: String, detail: String = "") {
        synchronized(lock) {
            ensureLoaded()
            buffer.record(jobId, kind, stage, detail)
            MediaAnalysisFiles.write(ANALYSIS_EXPORT_FILE, buffer.snapshot())
        }
    }

    fun export(): AnalysisExportDocument = synchronized(lock) {
        ensureLoaded()
        buffer.export()
    }

    internal fun clearForTests() {
        synchronized(lock) {
            loaded = true
            buffer.restore("")
            MediaAnalysisFiles.delete(ANALYSIS_EXPORT_FILE)
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        buffer.restore(MediaAnalysisFiles.read(ANALYSIS_EXPORT_FILE).orEmpty())
    }
}

internal fun redactAnalysisExport(raw: String): String {
    val held = mutableListOf<String>()
    var text = analysisReasonCode.replace(raw) { match ->
        val code = match.groupValues[1].lowercase()
        if (!analysisExportReasonKept(code)) return@replace match.value
        held += code
        "\u0001R${held.lastIndex}\u0001"
    }
    text = webUrl.replace(text) { match ->
        val rawUrl = match.value
        val scheme = rawUrl.substringBefore("://")
        val rest = rawUrl.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfter('@').substringBefore(':')
        "$scheme://$host"
    }
    text = personalPath.replace(text, "[path]")
    text = email.replace(text, "[email]")
    text = labeledSecret.replace(text) { match ->
        match.groupValues[1] + "=[redacted]"
    }
    text = bearer.replace(text, "Bearer [redacted]")
    text = longToken.replace(text, "[redacted]")
    held.forEachIndexed { index, code ->
        text = text.replace("\u0001R$index\u0001", code)
    }
    return text
}

/** accepted-, rejected-, and skip- codes stay. A long secret in the same line does not. */
internal fun analysisExportReasonKept(code: String): Boolean =
    code.matches(Regex("""^(accepted|rejected|skip)-[a-z0-9]{1,16}(?:-[a-z0-9]{1,16}){0,6}$"""))

private val analysisReasonCode = Regex(
    """(?i)\b((?:accepted|rejected|skip)-[a-z0-9]{1,24}(?:-[a-z0-9]{1,24}){0,8})\b""",
)

private fun sanitizeToken(value: String, max: Int): String =
    value.trim().lowercase().map { char ->
        if (char.isLetterOrDigit() || char == '_' || char == '-' || char == '.') char else '-'
    }.joinToString("").take(max)

private val webUrl = Regex("""(?i)\b(?:https?|addon)://\S+""")
private val personalPath = Regex(
    """(?i)(?:file://\S+|(?:/Users|/home|/private|/var|/data/user|/storage/emulated)/\S+)""",
)
private val email = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
private val labeledSecret = Regex(
    """(?i)\b(api[_-]?key|token|authorization|password|secret)\s*[:=]\s*\S+""",
)
private val bearer = Regex("""(?i)\bBearer\s+\S+""")
private val longToken = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9+/_]{32,}={0,2}(?![A-Za-z0-9])""")
