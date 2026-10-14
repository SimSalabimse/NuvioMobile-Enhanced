package com.nuvio.app.features.whatsnew

import nuvio.composeapp.generated.resources.whats_new_sheet_title
import nuvio.composeapp.generated.resources.whats_new_sheet_welcome
import nuvio.composeapp.generated.resources.whats_new_sheet_continue
import nuvio.composeapp.generated.resources.whats_new_section_new
import nuvio.composeapp.generated.resources.whats_new_section_improved
import nuvio.composeapp.generated.resources.whats_new_section_fixed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.downloads.DownloadsSettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_whats_new
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.stringResource

/**
 * Bundled release notes: composeResources/files/changelog.json, newest first, keyed by version.
 * Each release only edits that file. The sideload workflow reads the same file for the GitHub
 * release body and store.json (scripts/release-notes.py).
 */
@Serializable
data class BundledRelease(
    val version: String,
    val date: String? = null,
    val new: List<String> = emptyList(),
    val improved: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
)

@Serializable
private data class BundledChangelog(val releases: List<BundledRelease> = emptyList())

internal object ReleaseNotes {
    private const val LAST_SEEN_KEY = "whats_new_last_seen_v1"
    private val json = Json { ignoreUnknownKeys = true }
    private var cached: List<BundledRelease>? = null

    val currentKey: String get() = "${AppVersionConfig.VERSION_NAME}(${AppVersionConfig.VERSION_CODE})"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): List<BundledRelease> {
        cached?.let { return it }
        val list = runCatching {
            json.decodeFromString<BundledChangelog>(Res.readBytes("files/changelog.json").decodeToString()).releases
        }.getOrDefault(emptyList())
        cached = list
        return list
    }

    /** Null when nothing should show; otherwise the release for this version and whether it's a first run. */
    suspend fun pendingForLaunch(): Pair<BundledRelease, Boolean>? {
        val lastSeen = DownloadsSettingsStorage.loadString(LAST_SEEN_KEY)
        if (lastSeen == currentKey) return null
        val release = load().firstOrNull { it.version == AppVersionConfig.VERSION_NAME }
        if (release == null) {
            markSeen()
            return null
        }
        return release to (lastSeen == null)
    }

    fun markSeen() {
        DownloadsSettingsStorage.saveString(LAST_SEEN_KEY, currentKey)
    }
}

/** Shows the sheet once per new version/build. Call only once the main UI (after lock/profile) is up. */
@Composable
internal fun WhatsNewLaunchHost(enabled: Boolean) {
    var pending by remember { mutableStateOf<Pair<BundledRelease, Boolean>?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled || checked) return@LaunchedEffect
        checked = true
        kotlinx.coroutines.delay(900)
        pending = ReleaseNotes.pendingForLaunch()
    }
    val current = pending ?: return
    WhatsNewSheet(
        release = current.first,
        firstRun = current.second,
        onContinue = {
            ReleaseNotes.markSeen()
            pending = null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewSheet(release: BundledRelease, firstRun: Boolean, onContinue: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    NuvioModalBottomSheet(onDismissRequest = onContinue, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = nuvioSafeBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (firstRun) {
                    stringResource(Res.string.whats_new_sheet_welcome, release.version)
                } else {
                    stringResource(Res.string.whats_new_sheet_title, release.version)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.nuvio.colors.textPrimary,
            )
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ReleaseSections(release)
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.whats_new_sheet_continue),
                modifier = Modifier.fillMaxWidth(),
                onClick = onContinue,
            )
        }
    }
}

@Composable
private fun ReleaseSections(release: BundledRelease) {
    NotesSection(stringResource(Res.string.whats_new_section_new), release.new)
    NotesSection(stringResource(Res.string.whats_new_section_improved), release.improved)
    NotesSection(stringResource(Res.string.whats_new_section_fixed), release.fixed)
}

@Composable
private fun NotesSection(title: String, notes: List<String>) {
    if (notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.nuvio.colors.accent,
        )
        notes.forEach { note ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", color = MaterialTheme.nuvio.colors.textSecondary)
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                )
            }
        }
    }
}

/** Settings > About > What's new: every bundled release, newest first. */
@Composable
fun BundledWhatsNewScreen(onBack: () -> Unit) {
    var releases by remember { mutableStateOf<List<BundledRelease>>(emptyList()) }
    LaunchedEffect(Unit) { releases = ReleaseNotes.load() }
    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_whats_new),
                onBack = onBack,
            )
        }
        releases.forEach { release ->
            item(key = release.version) {
                NuvioSurfaceCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(Res.string.whats_new_sheet_title, release.version),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.nuvio.colors.textPrimary,
                        )
                        release.date?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
                        }
                        ReleaseSections(release)
                    }
                }
            }
        }
    }
}
