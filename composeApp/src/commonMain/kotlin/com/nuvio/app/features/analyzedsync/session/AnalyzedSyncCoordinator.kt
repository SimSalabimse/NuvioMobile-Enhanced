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
import com.nuvio.app.features.analyzedsync.store.analysisSyncPort
import com.nuvio.app.features.analyzedsync.store.backupJson
import com.nuvio.app.features.analyzedsync.store.backupJsonIsSafe
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
    private var generation = 0
    private var rerun = false

    fun reload() {
        val loaded = AnalyzedSyncVault.readSession(profileId())?.let(::decodeSession)
        session = loaded
        publish(loaded, syncing = _ui.value.syncing, formError = null)
    }

    fun create(url: String, enrollSecret: String, passphrase: String) {
        val gen = generation
        scope.launch {
            val baseUrl = normalizeSyncUrl(url) ?: return@launch form("Enter the sync server address.")
            val secret = enrollSecret.trim()
            if (secret.length < 16) return@launch form("The enroll secret must be at least 16 characters.")
            if (SyncCryptoPlatform.normalizeNfc(passphrase).length < 8) return@launch form("Use at least 8 characters.")
            _ui.update { it.copy(syncing = true, formError = null) }
            val salt = SyncCryptoPlatform.randomBytes(16)
            val keys = try {
                withContext(Dispatchers.Default) { deriveKeys(passphrase, salt, SYNC_KDF_ITERATIONS) }
            } catch (cancelled: CancellationException) {
                stopBusy()
                throw cancelled
            } catch (_: Exception) {
                publish(session, syncing = false, formError = "Sync failed.")
                return@launch
            }
            coroutineContext.ensureActive()
            if (gen != generation) return@launch stopBusy()
            try {
                gate.lock()
            } catch (cancelled: CancellationException) {
                stopBusy()
                throw cancelled
            }
            try {
                if (gen != generation) return@launch stopBusy()
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
                if (rerun) scope.launch { syncIfEnrolled() }
            }
        }
    }

    fun join(url: String, code: String, passphrase: String) {
        val gen = generation
        scope.launch {
            val baseUrl = normalizeSyncUrl(url) ?: return@launch form("Enter the sync server address.")
            val parsed = parseJoinCode(code) ?: return@launch form("That join code can't be read.")
            if (SyncCryptoPlatform.normalizeNfc(passphrase).length < 8) return@launch form("Use at least 8 characters.")
            _ui.update { it.copy(syncing = true, formError = null) }
            val keys = try {
                withContext(Dispatchers.Default) { deriveKeys(passphrase, parsed.salt, SYNC_KDF_ITERATIONS) }
            } catch (cancelled: CancellationException) {
                stopBusy()
                throw cancelled
            } catch (_: Exception) {
                publish(session, syncing = false, formError = "Sync failed.")
                return@launch
            }
            coroutineContext.ensureActive()
            if (gen != generation) return@launch stopBusy()
            try {
                gate.lock()
            } catch (cancelled: CancellationException) {
                stopBusy()
                throw cancelled
            }
            try {
                if (gen != generation) return@launch stopBusy()
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
                if (rerun) scope.launch { syncIfEnrolled() }
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

    /** Foreground, background, and a finished analysis all sync after a short quiet period. */
    fun onAppVisibility(foreground: Boolean) {
        val token = ++debounceToken
        scope.launch {
            delay(2_000)
            if (token != debounceToken) return@launch
            if (foreground) reload()
            syncIfEnrolled()
        }
    }

    fun onLocalStoreChanged() = onAppVisibility(foreground = false)

    fun forget() {
        generation += 1
        debounceToken += 1
        val gen = generation
        scope.launch {
            gate.lock()
            try {
                if (gen != generation) return@launch
                AnalyzedSyncVault.clearSecret(profileId())
                AnalyzedSyncVault.clearSession(profileId())
                session = null
                _ui.value = AnalyzedSyncUi()
            } finally {
                gate.unlock()
                if (rerun) scope.launch { syncIfEnrolled() }
            }
        }
    }

    fun joinCodeText(): String? = session?.joinCodeText()

    /** Writes plaintext sync records to a file on this phone. The passphrase and keys are not in it. */
    fun saveBackup(): Boolean {
        val port = analysisSyncPort()
        return runCatching {
            port.exclusive {
                val json = backupJson(port.readLocal())
                backupJsonIsSafe(json) && port.writeBackup(json)
            }
        }.getOrDefault(false)
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
        val gen = generation
        if (!gate.tryLock()) {
            rerun = true
            return
        }
        try {
            do {
                rerun = false
                if (gen != generation) return
                runSync(gen)
            } while (rerun && gen == generation)
        } finally {
            gate.unlock()
        }
    }

    private suspend fun runSync(gen: Int) {
        val current = session ?: return
        val secret = AnalyzedSyncVault.readSecret(profileId())
        if (secret == null) {
            if (gen != generation) return
            val missing = current.copy(statusText = "Secure storage is not available on this phone.")
            persist(missing)
            publish(missing, syncing = false, formError = null)
            return
        }
        try {
            publish(current, syncing = true, formError = null)
            val now = epochMs()
            var tracked = current
            val run = AnalyzedSyncEngine(
                transport,
                analysisSyncPort(),
                SyncCryptoPlatform::randomBytes,
                onClock = { wall, counter ->
                    if (gen != generation) return@AnalyzedSyncEngine
                    tracked = tracked.copy(clockWallMs = wall, clockCounter = counter)
                    persist(tracked)
                },
                stillCurrent = { gen == generation },
            ).sync(tracked.toConfig(secret), tracked.toState(), now)
            if (gen != generation) return
            val base = session ?: tracked
            val next = base.copy(
                pullCursor = run.state.pullCursor,
                lastSuccessfulSyncMs = if (run.error == null) run.state.lastSuccessfulSyncMs else base.lastSuccessfulSyncMs,
                clockWallMs = run.state.clockWallMs,
                clockCounter = run.state.clockCounter,
                seenDevices = run.state.seenDevices,
                episodeCount = if (run.error == null) run.state.episodeCount else base.episodeCount,
                statusText = run.error ?: syncStatusLine(run.state.lastSuccessfulSyncMs, now, run.state.episodeCount),
            )
            persist(next)
            publish(next, syncing = false, formError = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (gen != generation) return
            val failed = (session ?: current).copy(statusText = "Sync failed.")
            persist(failed)
            publish(failed, syncing = false, formError = null)
        }
    }

    private fun form(message: String) {
        _ui.update { it.copy(formError = message, syncing = false) }
    }

    private fun stopBusy() {
        _ui.update { it.copy(syncing = false) }
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
