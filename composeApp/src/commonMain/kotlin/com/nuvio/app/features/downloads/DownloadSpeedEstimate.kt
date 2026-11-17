package com.nuvio.app.features.downloads

/**
 * One device-wide download speed, kept as a single Double in the downloads settings store.
 * Null until the first accepted sample; the scheduler then uses [CONSERVATIVE_DEFAULT_DOWNLOAD_BYTES_PER_SECOND].
 */
internal object DownloadSpeedEstimate {
    private const val KEY = "download_speed_ema_bytes_per_second"
    private const val PERSIST_INTERVAL_MS = 5_000L
    private val ema = DownloadSpeedEma()
    private val sampler = ActiveDownloadSpeedSampler(ema)
    private var loaded = false
    private var lastPersistAtMs = Long.MIN_VALUE

    fun measuredBytesPerSecond(): Double? {
        ensureLoaded()
        return ema.bytesPerSecond
    }

    fun onItems(items: List<DownloadItem>, nowMs: Long) {
        ensureLoaded()
        val active = items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id to it.downloadedBytes }
        val accepted = sampler.observe(active, nowMs) ?: return
        if (lastPersistAtMs != Long.MIN_VALUE && nowMs - lastPersistAtMs < PERSIST_INTERVAL_MS) return
        lastPersistAtMs = nowMs
        DownloadsSettingsStorage.saveDouble(KEY, accepted)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        ema.restore(DownloadsSettingsStorage.loadDouble(KEY))
    }
}
