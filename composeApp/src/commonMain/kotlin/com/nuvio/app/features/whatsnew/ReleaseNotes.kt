package com.nuvio.app.features.whatsnew

import nuvio.composeapp.generated.resources.whats_new_version_label

import nuvio.composeapp.generated.resources.whats_new_sheet_title
import nuvio.composeapp.generated.resources.whats_new_sheet_welcome
import nuvio.composeapp.generated.resources.whats_new_sheet_continue
import nuvio.composeapp.generated.resources.whats_new_section_new
import nuvio.composeapp.generated.resources.whats_new_section_improved
import nuvio.composeapp.generated.resources.whats_new_section_fixed

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import nuvio.composeapp.generated.resources.app_icon_original
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
        return release to (lastSeen == null && !hasPreviousInstallSignal())
    }

    /** Any sign the app was used before this build (settings, downloads, history, analysis files). */
    private fun hasPreviousInstallSignal(): Boolean = runCatching {
        val storedKeys = listOf(
            "watch_stats_v1", "offline_artwork_index_v1", "smart_downloads_pending", "analysis_failures_v1",
        )
        if (storedKeys.any { DownloadsSettingsStorage.loadString(it) != null }) return@runCatching true
        val flagKeys = listOf(
            "delete_when_finished", "allow_mobile_data_downloads", "show_download_button",
            "analysis_subtitle_sync", "analysis_intro_detection", "smart_downloads_enabled",
        )
        if (flagKeys.any { DownloadsSettingsStorage.loadFlag(it) != null }) return@runCatching true
        com.nuvio.app.features.downloads.DownloadsRepository.ensureLoaded()
        if (com.nuvio.app.features.downloads.DownloadsRepository.uiState.value.items.isNotEmpty()) return@runCatching true
        if (com.nuvio.app.features.watchprogress.WatchProgressRepository.uiState.value.entries.isNotEmpty()) return@runCatching true
        if (com.nuvio.app.features.analysis.MediaAnalysisFiles.list("").isNotEmpty()) return@runCatching true
        com.nuvio.app.features.updater.AppUpdaterPlatform.getWhatsNewCache() != null
    }.getOrDefault(true)

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
    val colors = MaterialTheme.nuvio.colors
    NuvioModalBottomSheet(onDismissRequest = onContinue, sheetState = sheetState, fullHeight = true) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.app_icon_original),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (firstRun) {
                        stringResource(Res.string.whats_new_sheet_welcome, release.version)
                    } else {
                        stringResource(Res.string.whats_new_sheet_title, release.version)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = releaseSubtitle(release),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val scroll = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    ReleaseSections(release)
                }
                val background = MaterialTheme.nuvio.colors.surfaceSheet
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().height(16.dp)
                        .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0f)))),
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp)
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))),
                )
            }
            NuvioPrimaryButton(
                text = stringResource(Res.string.whats_new_sheet_continue),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp, bottom = nuvioSafeBottomPadding(16.dp)),
                onClick = onContinue,
            )
        }
    }
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun formatReleaseDate(iso: String?): String? {
    val parts = iso?.split("-")?.takeIf { it.size == 3 } ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    return "$day ${MONTHS[month - 1]} ${parts[0]}"
}

@Composable
private fun releaseSubtitle(release: BundledRelease): String {
    val build = if (release.version == AppVersionConfig.VERSION_NAME) " (${AppVersionConfig.VERSION_CODE})" else ""
    val version = stringResource(Res.string.whats_new_version_label, release.version + build)
    return listOfNotNull(version, formatReleaseDate(release.date)).joinToString("  ·  ")
}

@Composable
private fun ReleaseSections(release: BundledRelease) {
    NotesSection(Icons.Rounded.AutoAwesome, stringResource(Res.string.whats_new_section_new), release.new)
    NotesSection(Icons.Rounded.Bolt, stringResource(Res.string.whats_new_section_improved), release.improved)
    NotesSection(Icons.Rounded.Build, stringResource(Res.string.whats_new_section_fixed), release.fixed)
}

@Composable
private fun NotesSection(icon: ImageVector, title: String, notes: List<String>) {
    if (notes.isEmpty()) return
    val colors = MaterialTheme.nuvio.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
        }
        notes.forEach { note ->
            Row(
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(colors.accent.copy(alpha = 0.8f)),
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = colors.textPrimary.copy(alpha = 0.92f),
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
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = stringResource(Res.string.whats_new_sheet_title, release.version),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.nuvio.colors.textPrimary,
                        )
                        Text(releaseSubtitle(release), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.nuvio.colors.textSecondary)
                        ReleaseSections(release)
                    }
                }
            }
        }
    }
}
