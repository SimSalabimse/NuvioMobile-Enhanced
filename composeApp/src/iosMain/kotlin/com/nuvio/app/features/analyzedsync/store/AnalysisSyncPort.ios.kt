package com.nuvio.app.features.analyzedsync.store

import com.nuvio.app.core.serialization.LenientDefaultsJson
import com.nuvio.app.features.analysis.MediaAnalysisFiles
import com.nuvio.app.features.analysis.MediaAnalysisResult
import com.nuvio.app.features.analysis.MediaAnalysisStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val iosAnalysisSyncStore = IosEpisodeSyncStore()

internal actual fun analysisSyncPort(): AnalysisSyncPort = iosAnalysisSyncStore

private class IosEpisodeSyncStore : EpisodeJsonSyncStore(IosAnalysisTextFiles) {
    override fun <T> exclusive(block: () -> T): T = MediaAnalysisStore.exclusive(block)
}

private object IosAnalysisTextFiles : AnalysisTextFiles {
    override fun read(name: String): String? {
        if ("__" in name) {
            val loaded = MediaAnalysisStore.loadUnlocked(name)
            if (loaded != null) return LenientDefaultsJson.encodeToString(MediaAnalysisResult.serializer(), loaded)
        }
        return MediaAnalysisFiles.read(name)
    }

    override fun write(name: String, text: String) {
        if ("__" in name) {
            val result = runCatching { LenientDefaultsJson.decodeFromString(MediaAnalysisResult.serializer(), text) }.getOrNull()
            if (result != null) {
                MediaAnalysisStore.writeResult(result)
                return
            }
        }
        if (name == "show-aliases") {
            val map = runCatching { LenientDefaultsJson.decodeFromString<Map<String, String>>(text) }.getOrNull()
            if (map != null) {
                MediaAnalysisStore.writeAliasMapUnlocked(map)
                return
            }
        }
        MediaAnalysisFiles.write(name, text)
    }

    override fun list(prefix: String): List<String> = MediaAnalysisFiles.list(prefix)
}
