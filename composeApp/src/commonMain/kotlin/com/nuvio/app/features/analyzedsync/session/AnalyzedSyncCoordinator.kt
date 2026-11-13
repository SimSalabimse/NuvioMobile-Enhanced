package com.nuvio.app.features.analyzedsync.session

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.analyzedsync.SYNC_KDF_ITERATIONS
import com.nuvio.app.features.analyzedsync.crypto.SyncCryptoPlatform
import com.nuvio.app.features.analyzedsync.crypto.deriveKeys
import com.nuvio.app.features.analyzedsync.crypto.encodeBase64
import com.nuvio.app.features.analyzedsync.crypto.keyCheckMatches
import com.nuvio.app.features.analyzedsync.crypto.parseJoinCode
import com.nuvio.app.features.analyzedsync.crypto.randomUuidV4
import com.nuvio.app.features.analyzedsync.engine.AnalyzedSyncEngine
import com.nuvio.app.features.analyzedsync.engine.bearer
import com.nuvio.app.features.analyzedsync.json.canonicalJson
import com.nuvio.app.features.analyzedsync.store.analysisSyncPort
import com.nuvio.app.features.analyzedsync.transport.CreateSpaceBody
import com.nuvio.app.features.analyzedsync.transport.TransportResponse
import com.nuvio.app.features.analyzedsync.transport.WorkerSyncTransport
import com.nuvio.app.features.analyzedsync.transport.honestSyncStatus
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.epochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal data class AnalyzedSyncDevice(val id: String, val seenAtMs: Long, val label: String)

internal data class AnalyzedSyncUi(
    val enrolled: Boolean = false,
    val baseUrl: String = ANALYZED_SYNC_DEFAULT_URL,
    val statusText: String = "Not synced yet",
    val syncing: Boolean = false,
    val syncMarks: Boolean = true,
    val syncDetections: Boolean = true,
    val syncSubmissions: Boolean = true,
    val devices: List<AnalyzedSyncDevice> = emptyList(),
    val deviceId: String = "",
    val formError: String? = null,
    val episodeCount: Int = 0,
) {
    override fun toString(): String = "AnalyzedSyncUi(enrolled=$enrolled, deviceId=$deviceId)"
}

/**
 * Create, join, leave, and debounced sync for one profile. The enroll secret is used once
 * and never stored. Passphrase and keys stay in [AnalyzedSyncVault].
 */
internal object AnalyzedSyncCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private val transport by lazy { WorkerSyncTransport(createApiHttpClient()) }
    private val _ui = MutableStateFlow(AnalyzedSyncUi())
    val ui: StateFlow<AnalyzedSyncUi> = _ui
    private var session: AnalyzedSyncSession? = null
    private var debounceToken = 0

    fun reload() {
        val loaded = AnalyzedSyncVault.readSession(profileId())?.let(::decodeSession)
        session = loaded
        publish(loaded, syncing = _ui.value.syncing, formError = null)
    }

    fun create(url: String, enrollSecret: String, passphrase: String) {
        scope.launch {
            val baseUrl = normalizeSyncUrl(url) ?: return@launch form("Enter the sync server address.")
            val secret = enrollSecret.trim()
            if (secret.length < 16) return@launch form("The enroll secret must be at least 16 characters.")
            if (SyncCryptoPlatform.normalizeNfc(passphrase).length < 8) return@launch form("Use at least 8 characters.")
            if (!gate.tryLock()) return@launch
            try {
                _ui.update { it.copy(syncing = true, formError = null) }
                val salt = SyncCryptoPlatform.randomBytes(16)
                val keys = deriveKeys(passphrase, salt, SYNC_KDF_ITERATIONS)
                val spaceId = randomUuidV4()
                val deviceId = randomUuidV4()
                val response = transport.createSpace(
                    baseUrl,
                    secret,
                    bearer(keys.authToken),
                    CreateSpaceBody(spaceId, encodeBase64(salt), encodeBase64(keys.keyCheck)),
                )
                if (response is TransportResponse.Failed) {
                    publish(session, syncing = false, formError = honestSyncStatus(response))
                    return@launch
                }
                val stored = AnalyzedSyncSecret(SyncCryptoPlatform.normalizeNfc(passphrase), keys.encKey, keys.authToken)
                if (!AnalyzedSyncVault.writeSecret(profileId(), stored)) {
                    publish(session, syncing = false, formError = "Secure storage is not available on this phone.")
                    return@launch
                }
                val created = AnalyzedSyncSession(
                    baseUrl = baseUrl,
                    spaceId = spaceId,
                    saltBase64 = encodeBase64(salt),
                    keyCheckBase64 = encodeBase64(keys.keyCheck),
                    deviceId = deviceId,
                    statusText = "Not synced yet",
                )
                persist(created)
                publish(created, syncing = false, formError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(session, syncing = false, formError = "Sync failed.")
            } finally {
                gate.unlock()
            }
        }
    }

    fun join(url: String, code: String, passphrase: String) {
        scope.launch {
            val baseUrl = normalizeSyncUrl(url) ?: return@launch form("Enter the sync server address.")
            val parsed = parseJoinCode(code) ?: return@launch form("That join code can't be read.")
            if (SyncCryptoPlatform.normalizeNfc(passphrase).length < 8) return@launch form("Use at least 8 characters.")
            if (!gate.tryLock()) return@launch
            try {
                _ui.update { it.copy(syncing = true, formError = null) }
                val keys = deriveKeys(passphrase, parsed.salt, SYNC_KDF_ITERATIONS)
                if (!keyCheckMatches(keys.keyCheck, parsed.keyCheck)) {
                    publish(session, syncing = false, formError = "That passphrase doesn't match this space.")
                    return@launch
                }
                val response = transport.join(baseUrl, parsed.spaceId, bearer(keys.authToken))
                if (response is TransportResponse.Failed) {
                    publish(session, syncing = false, formError = honestSyncStatus(response))
                    return@launch
                }
                val stored = AnalyzedSyncSecret(SyncCryptoPlatform.normalizeNfc(passphrase), keys.encKey, keys.authToken)
                if (!AnalyzedSyncVault.writeSecret(profileId(), stored)) {
                    publish(session, syncing = false, formError = "Secure storage is not available on this phone.")
                    return@launch
                }
                val joined = AnalyzedSyncSession(
                    baseUrl = baseUrl,
                    spaceId = parsed.spaceId,
                    saltBase64 = encodeBase64(parsed.salt),
                    keyCheckBase64 = encodeBase64(parsed.keyCheck),
                    deviceId = randomUuidV4(),
                    statusText = "Not synced yet",
                )
                persist(joined)
                publish(joined, syncing = false, formError = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(session, syncing = false, formError = "Sync failed.")
            } finally {
                gate.unlock()
            }
        }
    }

    fun setMarks(enabled: Boolean) = updateToggles(marks = enabled)
    fun setDetections(enabled: Boolean) = updateToggles(detections = enabled)
    fun setSubmissions(enabled: Boolean) = updateToggles(submissions = enabled)

    fun syncNow() {
        debounceToken += 1
        scope.launch { syncIfEnrolled() }
    }

    /** Foreground and background both sync, after a short quiet period. */
    fun onAppVisibility(foreground: Boolean) {
        val token = ++debounceToken
        scope.launch {
            delay(2_000)
            if (token != debounceToken) return@launch
            if (foreground) reload()
            syncIfEnrolled()
        }
    }

    fun forget() {
        debounceToken += 1
        AnalyzedSyncVault.clearSecret(profileId())
        AnalyzedSyncVault.clearSession(profileId())
        session = null
        _ui.value = AnalyzedSyncUi()
    }

    fun joinCodeText(): String? = session?.joinCodeText()

    fun backupText(): String {
        val records = runCatching { analysisSyncPort().readLocal() }.getOrDefault(emptyList())
        return records.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it.record.toWire()) }
    }

    private fun updateToggles(marks: Boolean? = null, detections: Boolean? = null, submissions: Boolean? = null) {
        val current = session ?: return
        val next = current.copy(
            syncMarks = marks ?: current.syncMarks,
            syncDetections = detections ?: current.syncDetections,
            syncSubmissions = submissions ?: current.syncSubmissions,
        )
        persist(next)
        publish(next, syncing = _ui.value.syncing, formError = null)
    }

    private suspend fun syncIfEnrolled() {
        val current = session ?: return
        val secret = AnalyzedSyncVault.readSecret(profileId())
        if (secret == null) {
            val missing = current.copy(statusText = "Secure storage is not available on this phone.")
            persist(missing)
            publish(missing, syncing = false, formError = null)
            return
        }
        if (!gate.tryLock()) return
        try {
            publish(current, syncing = true, formError = null)
            val now = epochMs()
            val run = AnalyzedSyncEngine(transport, analysisSyncPort(), SyncCryptoPlatform::randomBytes)
                .sync(current.toConfig(secret), current.toState(), now)
            val next = current.copy(
                pullCursor = run.state.pullCursor,
                lastSuccessfulSyncMs = run.state.lastSuccessfulSyncMs,
                clockWallMs = run.state.clockWallMs,
                clockCounter = run.state.clockCounter,
                seenDevices = run.state.seenDevices,
                episodeCount = run.state.episodeCount,
                statusText = run.error ?: syncStatusLine(run.state.lastSuccessfulSyncMs, now, run.state.episodeCount),
            )
            persist(next)
            publish(next, syncing = false, formError = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val failed = current.copy(statusText = "Sync failed.")
            persist(failed)
            publish(failed, syncing = false, formError = null)
        } finally {
            gate.unlock()
        }
    }

    private fun form(message: String) {
        _ui.update { it.copy(formError = message, syncing = false) }
    }

    private fun persist(next: AnalyzedSyncSession) {
        session = next
        AnalyzedSyncVault.writeSession(profileId(), encodeSession(next))
    }

    private fun publish(current: AnalyzedSyncSession?, syncing: Boolean, formError: String?) {
        if (current == null) {
            _ui.value = AnalyzedSyncUi(syncing = syncing, formError = formError)
            return
        }
        val now = epochMs()
        _ui.value = AnalyzedSyncUi(
            enrolled = true,
            baseUrl = current.baseUrl,
            statusText = current.statusText,
            syncing = syncing,
            syncMarks = current.syncMarks,
            syncDetections = current.syncDetections,
            syncSubmissions = current.syncSubmissions,
            devices = current.seenDevices.map { (id, seen) ->
                AnalyzedSyncDevice(
                    id = id,
                    seenAtMs = seen,
                    label = if (id == current.deviceId) "This phone" else id,
                )
            }.sortedByDescending { it.seenAtMs } + if (current.deviceId !in current.seenDevices) {
                listOf(AnalyzedSyncDevice(current.deviceId, current.lastSuccessfulSyncMs ?: now, "This phone"))
            } else {
                emptyList()
            },
            deviceId = current.deviceId,
            formError = formError,
            episodeCount = current.episodeCount,
        )
    }

    private fun profileId(): Int = ProfileRepository.activeProfileId
}
