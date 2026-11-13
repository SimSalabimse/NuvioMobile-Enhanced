package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.analyzedsync.session.ANALYZED_SYNC_DEFAULT_URL
import com.nuvio.app.features.analyzedsync.session.AnalyzedSyncCoordinator
import com.nuvio.app.features.player.playerReduceMotionEnabled
import com.nuvio.app.features.streams.epochMs
import androidx.compose.ui.platform.LocalClipboardManager
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_sync_description
import nuvio.composeapp.generated.resources.settings_sync_back
import nuvio.composeapp.generated.resources.settings_sync_copied
import nuvio.composeapp.generated.resources.settings_sync_copy_code
import nuvio.composeapp.generated.resources.settings_sync_create
import nuvio.composeapp.generated.resources.settings_sync_create_action
import nuvio.composeapp.generated.resources.settings_sync_detections
import nuvio.composeapp.generated.resources.settings_sync_detections_description
import nuvio.composeapp.generated.resources.settings_sync_devices
import nuvio.composeapp.generated.resources.settings_sync_enroll
import nuvio.composeapp.generated.resources.settings_sync_export
import nuvio.composeapp.generated.resources.settings_sync_fingerprints
import nuvio.composeapp.generated.resources.settings_sync_fingerprints_description
import nuvio.composeapp.generated.resources.settings_sync_forget
import nuvio.composeapp.generated.resources.settings_sync_join
import nuvio.composeapp.generated.resources.settings_sync_join_action
import nuvio.composeapp.generated.resources.settings_sync_join_code
import nuvio.composeapp.generated.resources.settings_sync_leave
import nuvio.composeapp.generated.resources.settings_sync_marks
import nuvio.composeapp.generated.resources.settings_sync_marks_description
import nuvio.composeapp.generated.resources.settings_sync_now
import nuvio.composeapp.generated.resources.settings_sync_passphrase
import nuvio.composeapp.generated.resources.settings_sync_server
import nuvio.composeapp.generated.resources.settings_sync_submissions
import nuvio.composeapp.generated.resources.settings_sync_submissions_description
import nuvio.composeapp.generated.resources.settings_sync_syncing
import nuvio.composeapp.generated.resources.settings_sync_this_phone
import nuvio.composeapp.generated.resources.settings_sync_what
import org.jetbrains.compose.resources.stringResource

private enum class SyncPane { Choice, Create, Join }

internal fun LazyListScope.analyzedSyncSettingsContent(isTablet: Boolean) {
    item {
        AnalyzedSyncSettings(isTablet = isTablet)
    }
}

@Composable
private fun AnalyzedSyncSettings(isTablet: Boolean) {
    val ui by AnalyzedSyncCoordinator.ui.collectAsStateWithLifecycle()
    var pane by remember { mutableStateOf(SyncPane.Choice) }
    var url by remember { mutableStateOf(ANALYZED_SYNC_DEFAULT_URL) }
    var enrollSecret by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val reduceMotion = playerReduceMotionEnabled()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { AnalyzedSyncCoordinator.reload() }
    LaunchedEffect(ui.enrolled) {
        if (ui.enrolled) {
            enrollSecret = ""
            passphrase = ""
            joinCode = ""
            pane = SyncPane.Choice
        }
    }
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.compose_settings_root_sync_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        when {
            ui.enrolled -> EnrolledSync(
                isTablet = isTablet,
                status = ui.statusText,
                syncing = ui.syncing,
                reduceMotion = reduceMotion,
                baseUrl = ui.baseUrl,
                syncMarks = ui.syncMarks,
                syncDetections = ui.syncDetections,
                syncSubmissions = ui.syncSubmissions,
                devices = ui.devices.map { device ->
                    if (device.label == "This phone") stringResource(Res.string.settings_sync_this_phone) to device.seenAtMs else device.label to device.seenAtMs
                },
                confirmLeave = confirmLeave,
                copied = copied,
                onMarks = AnalyzedSyncCoordinator::setMarks,
                onDetections = AnalyzedSyncCoordinator::setDetections,
                onSubmissions = AnalyzedSyncCoordinator::setSubmissions,
                onSync = { AnalyzedSyncCoordinator.syncNow() },
                onCopyCode = {
                    val code = AnalyzedSyncCoordinator.joinCodeText() ?: return@EnrolledSync
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                onExport = {
                    clipboard.setText(AnnotatedString(AnalyzedSyncCoordinator.backupText()))
                    copied = true
                },
                onLeave = {
                    if (!confirmLeave) confirmLeave = true else AnalyzedSyncCoordinator.forget()
                },
            )
            pane == SyncPane.Create -> SyncForm(
                isTablet = isTablet,
                title = stringResource(Res.string.settings_sync_create),
                action = stringResource(Res.string.settings_sync_create_action),
                busy = ui.syncing,
                reduceMotion = reduceMotion,
                error = ui.formError,
                onBack = { pane = SyncPane.Choice },
                onSubmit = { AnalyzedSyncCoordinator.create(url, enrollSecret, passphrase) },
            ) {
                SyncField(stringResource(Res.string.settings_sync_server), url, secret = false) { url = it }
                SyncField(stringResource(Res.string.settings_sync_enroll), enrollSecret, secret = true) { enrollSecret = it }
                SyncField(stringResource(Res.string.settings_sync_passphrase), passphrase, secret = true) { passphrase = it }
            }
            pane == SyncPane.Join -> SyncForm(
                isTablet = isTablet,
                title = stringResource(Res.string.settings_sync_join),
                action = stringResource(Res.string.settings_sync_join_action),
                busy = ui.syncing,
                reduceMotion = reduceMotion,
                error = ui.formError,
                onBack = { pane = SyncPane.Choice },
                onSubmit = { AnalyzedSyncCoordinator.join(url, joinCode, passphrase) },
            ) {
                SyncField(stringResource(Res.string.settings_sync_server), url, secret = false) { url = it }
                SyncField(stringResource(Res.string.settings_sync_join_code), joinCode, secret = false) { joinCode = it }
                SyncField(stringResource(Res.string.settings_sync_passphrase), passphrase, secret = true) { passphrase = it }
            }
            else -> SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_sync_create),
                    description = stringResource(Res.string.compose_settings_root_sync_description),
                    isTablet = isTablet,
                    onClick = { pane = SyncPane.Create },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.settings_sync_join),
                    description = stringResource(Res.string.settings_sync_join_code),
                    isTablet = isTablet,
                    onClick = { pane = SyncPane.Join },
                )
            }
        }
    }
}

@Composable
private fun EnrolledSync(
    isTablet: Boolean,
    status: String,
    syncing: Boolean,
    reduceMotion: Boolean,
    baseUrl: String,
    syncMarks: Boolean,
    syncDetections: Boolean,
    syncSubmissions: Boolean,
    devices: List<Pair<String, Long>>,
    confirmLeave: Boolean,
    copied: Boolean,
    onMarks: (Boolean) -> Unit,
    onDetections: (Boolean) -> Unit,
    onSubmissions: (Boolean) -> Unit,
    onSync: () -> Unit,
    onCopyCode: () -> Unit,
    onExport: () -> Unit,
    onLeave: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val now = epochMs()
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
        SettingsSection(title = stringResource(Res.string.settings_sync_server), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = status, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                        Text(text = baseUrl, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    }
                    if (syncing) {
                        Spacer(modifier = Modifier.width(12.dp))
                        if (reduceMotion) {
                            Text(text = stringResource(Res.string.settings_sync_syncing), color = tokens.colors.textMuted)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = tokens.colors.accent)
                        }
                    }
                }
            }
        }
        SettingsSection(title = stringResource(Res.string.settings_sync_what), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_sync_marks),
                    description = stringResource(Res.string.settings_sync_marks_description),
                    checked = syncMarks,
                    isTablet = isTablet,
                    onCheckedChange = onMarks,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_sync_detections),
                    description = stringResource(Res.string.settings_sync_detections_description),
                    checked = syncDetections,
                    isTablet = isTablet,
                    onCheckedChange = onDetections,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_sync_submissions),
                    description = stringResource(Res.string.settings_sync_submissions_description),
                    checked = syncSubmissions,
                    isTablet = isTablet,
                    onCheckedChange = onSubmissions,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_sync_fingerprints),
                    description = stringResource(Res.string.settings_sync_fingerprints_description),
                    checked = false,
                    enabled = false,
                    isTablet = isTablet,
                    onCheckedChange = {},
                )
            }
        }
        if (devices.isNotEmpty()) {
            SettingsSection(title = stringResource(Res.string.settings_sync_devices), isTablet = isTablet) {
                SettingsGroup(isTablet = isTablet) {
                    devices.forEachIndexed { index, (label, seen) ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val recent = now - seen < 7L * 86_400_000L
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (recent) tokens.colors.accent else tokens.colors.textMuted, CircleShape),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                        }
                    }
                }
            }
        }
        SettingsGroup(isTablet = isTablet) {
            SettingsClickableRow(title = stringResource(Res.string.settings_sync_now), enabled = !syncing, isTablet = isTablet, onClick = onSync)
            SettingsGroupDivider(isTablet = isTablet)
            SettingsClickableRow(title = stringResource(Res.string.settings_sync_copy_code), isTablet = isTablet, onClick = onCopyCode)
            SettingsGroupDivider(isTablet = isTablet)
            SettingsClickableRow(title = stringResource(Res.string.settings_sync_export), isTablet = isTablet, onClick = onExport)
            SettingsGroupDivider(isTablet = isTablet)
            SettingsClickableRow(
                title = stringResource(if (confirmLeave) Res.string.settings_sync_forget else Res.string.settings_sync_leave),
                isTablet = isTablet,
                onClick = onLeave,
            )
        }
        if (copied) {
            Text(
                text = stringResource(Res.string.settings_sync_copied),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun SyncForm(
    isTablet: Boolean,
    title: String,
    action: String,
    busy: Boolean,
    reduceMotion: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    fields: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        SettingsGroup(isTablet = isTablet) {
            SettingsClickableRow(title = stringResource(Res.string.settings_sync_back), isTablet = isTablet, onClick = onBack)
        }
        SettingsSection(title = title, isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    fields()
                }
            }
        }
        SettingsGroup(isTablet = isTablet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsClickableRow(title = action, enabled = !busy, isTablet = isTablet, onClick = onSubmit)
                if (busy) {
                    if (reduceMotion) {
                        Text(
                            text = stringResource(Res.string.settings_sync_syncing),
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(18.dp),
                            strokeWidth = 2.dp,
                            color = tokens.colors.accent,
                        )
                    }
                }
            }
        }
        if (!error.isNullOrBlank()) {
            Text(text = error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SyncField(label: String, value: String, secret: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
    )
}
