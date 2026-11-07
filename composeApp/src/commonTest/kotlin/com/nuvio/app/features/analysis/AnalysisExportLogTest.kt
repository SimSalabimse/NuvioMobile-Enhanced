package com.nuvio.app.features.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalysisExportLogTest {
    @Test
    fun redactsUrlsTokensEmailsAndPersonalPathsAndKeepsTheJobId() {
        val jobId = "550e8400-e29b-41d4-a716-446655440000"
        val secret = "https://user:pass@cdn.example/show/file.mkv?token=abc123&api_key=notasecretvalueabcdefghijklmnopqrstuvwxyz"
        val redacted = redactAnalysisExport(
            "job=$jobId file=/Users/someone/Library/episode.mkv " +
                "addon=$secret mail=person@example.com Bearer notabearervalueabcdefghijklmnopqrstuvwxyz " +
                "api_key=notasecretvalueabcdefghijklmnopqrstuvwxyz",
        )
        assertTrue(redacted.contains(jobId))
        assertTrue(redacted.contains("https://cdn.example"))
        assertFalse(redacted.contains("user:pass"))
        assertFalse(redacted.contains("/show/file.mkv"))
        assertFalse(redacted.contains("token="))
        assertFalse(redacted.contains("notasecretvalue"))
        assertFalse(redacted.contains("notabearervalue"))
        assertFalse(redacted.contains("/Users/someone"))
        assertTrue(redacted.contains("[path]"))
        assertFalse(redacted.contains("person@example.com"))
        assertTrue(redacted.contains("[email]"))
        assertFalse(redacted.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
    }

    @Test
    fun allowlistedReasonCodesSurviveAndSecretsDoNot() {
        val redacted = redactAnalysisExport(
            "why=accepted-previous-ending why=rejected-audio-miss-prior-unused " +
                "why=rejected-dialog-overlay why=skip-user-declined " +
                "why=notasecretvalueabcdefghijklmnopqrstuvwxyz " +
                "Bearer notabearervalueabcdefghijklmnopqrstuvwxyz",
        )
        assertTrue(redacted.contains("why=accepted-previous-ending"))
        assertTrue(redacted.contains("why=rejected-audio-miss-prior-unused"))
        assertTrue(redacted.contains("why=rejected-dialog-overlay"))
        assertTrue(redacted.contains("why=skip-user-declined"))
        assertFalse(redacted.contains("notasecretvalueabcdefghijklmnopqrstuvwxyz"))
        assertFalse(redacted.contains("notabearervalueabcdefghijklmnopqrstuvwxyz"))
        assertTrue(redacted.contains("[redacted]"))
        assertFalse(analysisExportReasonKept("notasecretvalueabcdefghijklmnopqrstuvwxyz"))
        assertTrue(analysisExportReasonKept("rejected-no-cluster"))
    }

    @Test
    fun exportIsBoundedAndReportsReadyContentWithoutTheCoreDiagnosticsFile() {
        var now = 1_000L
        val buffer = AnalysisExportBuffer(maxLines = 3, clock = { now })
        val empty = buffer.export()
        assertEquals(AnalysisExportStatus.Empty, empty.status)
        assertEquals(0, empty.lineCount)
        assertTrue(empty.text.contains("status=empty"))
        assertTrue(empty.text.contains("separate-from-diagnostics"))
        assertFalse(empty.text.contains("diagnostics.jsonl"))

        buffer.record("e5", "stage", "audio", "reading")
        now = 61_000L
        buffer.record(
            "mw4an0ww_1",
            "stage",
            "match",
            "siblings=4 url=https://addon.example/playlist/abc?api_key=secret",
        )
        buffer.record("e7", "error", "file", "missing")
        buffer.record("e8", "queue", "done", "finished-pass")
        val ready = buffer.export()
        assertEquals(AnalysisExportStatus.Ready, ready.status)
        assertEquals(3, ready.lineCount)
        assertFalse(ready.text.contains("job=e5"))
        assertTrue(ready.text.contains("job=mw4an0ww_1"))
        assertTrue(ready.text.contains("kind=stage"))
        assertTrue(ready.text.contains("stage=match"))
        assertTrue(ready.text.contains("atMs=61000"))
        assertTrue(ready.text.contains("https://addon.example"))
        assertFalse(ready.text.contains("playlist"))
        assertFalse(ready.text.contains("api_key=secret"))
        assertTrue(ready.text.contains("job=e8"))
        assertTrue(ready.text.contains("kind=queue"))
        assertTrue(ready.text.contains("status=ready lines=3"))
        assertTrue(ready.text.contains("file=$ANALYSIS_EXPORT_FILE"))
        assertFalse(ANALYSIS_EXPORT_FILE.contains("diagnostics"))
        assertFalse(ready.text.contains("diagnostics.jsonl"))
    }

    @Test
    fun aLongDetailIsCutAndARestoredLogStaysInsideTheCap() {
        val buffer = AnalysisExportBuffer(maxLines = 2, clock = { 5L })
        buffer.record("e6", "stage", "match", "x".repeat(400))
        val stored = buffer.snapshot()
        assertTrue(stored.substringAfter("stage=match ").length <= ANALYSIS_EXPORT_MAX_DETAIL)
        val restored = AnalysisExportBuffer(maxLines = 2, clock = { 5L })
        restored.restore(stored + "\natMs=1 job=old kind=queue stage=idle\natMs=2 job=newer kind=queue stage=idle\n")
        val export = restored.export()
        assertEquals(2, export.lineCount)
        assertFalse(export.text.contains("job=e6"))
        assertTrue(export.text.contains("job=newer"))
    }
}
