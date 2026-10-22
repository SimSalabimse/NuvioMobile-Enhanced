package com.nuvio.app.features.downloads

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Download buttons must queue through DownloadEnqueueService so they all share the debrid fallback. */
class DownloadEnqueueServiceGuardTest {
    private val allowed = setOf("DownloadsRepository.kt", "DownloadEnqueueService.kt")

    @Test
    fun onlyTheServiceCallsEnqueueFromStream() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "run from composeApp: ${root.absolutePath}")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { it.readText().contains("enqueueFromStream(") }
            .map { it.name }
            .toList()
        assertEquals(emptyList(), offenders, "queue downloads through DownloadEnqueueService")
    }
}
