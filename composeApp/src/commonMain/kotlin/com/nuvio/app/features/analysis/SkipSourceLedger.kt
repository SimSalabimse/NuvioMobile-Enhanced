package com.nuvio.app.features.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER
import com.nuvio.app.features.player.skip.SkipInterval
import com.nuvio.app.features.player.skip.skipSourceTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Where an episode's skip times came from. Online providers (TheIntroDB, IntroDB, AniSkip…) are
 * only known while the player looks them up, so the player records the provider it used per
 * segment kind here, keyed like the analysis files. Your marks and on-device results come from
 * the analysis file itself.
 */
internal object SkipSourceLedger {
    private const val STORE_KEY = "skip_source_ledger_v1"
    private const val MAX_ENTRIES = 2000
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private var loaded: MutableMap<String, Map<String, String>>? = null
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun all(): MutableMap<String, Map<String, String>> = loaded ?: runCatching {
        DownloadsSettingsStorage.loadString(STORE_KEY)?.let { json.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty().toMutableMap().also { loaded = it }

    /** Online providers per kind ("intro", "recap", "outro", "preview") for one episode. */
    fun online(key: String): Map<String, String> = all()[key].orEmpty()

    /** Called with the intervals the player will use for this episode. */
    fun record(parentMetaId: String, season: Int?, episode: Int?, intervals: List<SkipInterval>) {
        if (parentMetaId.isBlank() || season == null || episode == null) return
        val key = MediaAnalysisStore.keyFor(parentMetaId, season, episode)
        val online = intervals
            .filter { it.provider != ON_DEVICE_SKIP_PROVIDER && !SkipLearning.isUserProvider(it.provider) }
            .associate { skipKind(it.type) to it.provider }
        val map = all()
        if (online.isEmpty() || map[key] == online) return
        map.remove(key)
        map[key] = online
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        runCatching { DownloadsSettingsStorage.saveString(STORE_KEY, json.encodeToString(serializer, map)) }
        _revision.value += 1
    }
}

internal fun skipKind(type: String): String = when (type.lowercase()) {
    "intro", "op", "mixed-op", "opening" -> "intro"
    "recap" -> "recap"
    "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "outro"
    else -> type.lowercase()
}

/**
 * Per kind, the source the player would use, in its order: your mark, then online, then on-device.
 * Returns label pairs like ("Intro", "TheIntroDB").
 */
internal fun skipSourcesFor(item: DownloadItem): List<Pair<String, String>> {
    val key = MediaAnalysisStore.keyFor(item)
    val result = MediaAnalysisStore.load(key)
    val kinds = listOf("intro" to "Intro", "recap" to "Recap", "outro" to "Credits", "preview" to "Preview")
    return kinds.mapNotNull { (kind, label) ->
        val source = when {
            externalSkipSource(key, result, kind) != null -> externalSkipSource(key, result, kind)
            result != null && result.fileName == item.fileName && result.hasOnDevice(kind) ->
                if (kind in result.learnedTypes) "From your mark" else skipSourceTag(ON_DEVICE_SKIP_PROVIDER)
            else -> null
        }
        source?.let { label to it }
    }
}

/**
 * The non-on-device source of one kind's times for an episode: your mark (typed or imported from
 * your TheIntroDB submissions), else the online provider the player last used. Stored data only.
 */
private fun externalSkipSource(key: String, result: MediaAnalysisResult?, kind: String): String? {
    val mark = result?.userSegments?.get(kind)?.takeIf { it.endMs > it.startMs }
    if (mark != null) return if (mark.imported) "Your submission" else "Your mark"
    val online = SkipSourceLedger.online(key)[kind] ?: return null
    return skipSourceTag(online) ?: online
}

/** Intro/credits and recap sources from marks or online providers, for the analysis chips. */
internal data class ExternalSkipSources(val introCredits: String?, val recap: String?)

internal fun externalSkipSources(item: DownloadItem): ExternalSkipSources {
    val key = MediaAnalysisStore.keyFor(item)
    val result = MediaAnalysisStore.load(key)
    return ExternalSkipSources(
        introCredits = externalSkipSource(key, result, "intro") ?: externalSkipSource(key, result, "outro"),
        recap = externalSkipSource(key, result, "recap"),
    )
}

private fun MediaAnalysisResult.hasOnDevice(kind: String): Boolean = when (kind) {
    "intro" -> introStartMs != null && introEndMs != null && introEndMs > introStartMs
    "recap" -> recapStartMs != null && recapEndMs != null && recapEndMs > recapStartMs
    "outro" -> creditsStartMs != null && creditsEndMs != null && creditsEndMs > creditsStartMs
    else -> false
}

/** One line per segment ("Intro · Your mark", "Recap · TheIntroDB") on a downloaded episode card. */
@Composable
fun SkipSourcesRow(item: DownloadItem) {
    if (item.status != DownloadStatus.Completed || item.seasonNumber == null || item.episodeNumber == null) return
    val ledger by SkipSourceLedger.revision.collectAsStateWithLifecycle()
    val analysis by MediaAnalysisStatus.revision.collectAsStateWithLifecycle()
    val sources = remember(item.id, item.fileName, ledger, analysis) { skipSourcesFor(item) }
    if (sources.isEmpty()) return
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.FastForward, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Column {
            sources.forEach { (kind, source) ->
                Text(text = "$kind · $source", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}
