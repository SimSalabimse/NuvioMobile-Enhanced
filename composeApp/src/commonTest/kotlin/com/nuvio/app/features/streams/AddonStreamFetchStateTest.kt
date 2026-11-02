package com.nuvio.app.features.streams

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonStreamFetchStateTest {

    @Test
    fun `state table decides retry and the failed addon chips`() {
        val cases = listOf(
            AddonStreamFetchStatus.Loading,
            AddonStreamFetchStatus.Loaded(count = 3),
            AddonStreamFetchStatus.Loaded(count = 0),
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout),
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = 404),
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline),
        )
        for (status in cases) {
            for (online in listOf(false, true)) {
                for (retryUsed in listOf(false, true)) {
                    for (screenOpenWhenConnectionReturns in listOf(false, true)) {
                        val expectedRetry = expectedRetry(
                            status = status,
                            online = online,
                            retryUsed = retryUsed,
                            screenOpenWhenConnectionReturns = screenOpenWhenConnectionReturns,
                        )
                        assertEquals(
                            expectedRetry,
                            shouldRetryAddon(
                                status = status,
                                online = online,
                                retryUsed = retryUsed,
                                screenOpenWhenConnectionReturns = screenOpenWhenConnectionReturns,
                            ),
                            "retry ${status.describe()} online=$online retryUsed=$retryUsed screenOpen=$screenOpenWhenConnectionReturns",
                        )
                        assertEquals(
                            expectedChips(status),
                            failedAddonChipNames(
                                listOf(AddonFetchRow(name = "North", status = status, retryUsed = retryUsed)),
                            ),
                            "chips ${status.describe()} online=$online retryUsed=$retryUsed screenOpen=$screenOpenWhenConnectionReturns",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `http 4xx does not retry and http 5xx does`() {
        listOf(400, 403, 404, 429).forEach { code ->
            val status = AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = code)
            assertFalse(status.isAutoRetryableFailure(), "status=$code")
            assertFalse(shouldRetryAddon(status, online = true, retryUsed = false, screenOpenWhenConnectionReturns = true))
        }
        listOf(500, 502, 503).forEach { code ->
            val status = AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = code)
            assertTrue(status.isAutoRetryableFailure(), "status=$code")
            assertTrue(shouldRetryAddon(status, online = true, retryUsed = false, screenOpenWhenConnectionReturns = true))
            assertFalse(shouldRetryAddon(status, online = true, retryUsed = true, screenOpenWhenConnectionReturns = true))
            assertFalse(shouldRetryAddon(status, online = false, retryUsed = false, screenOpenWhenConnectionReturns = true))
            assertFalse(shouldRetryAddon(status, online = true, retryUsed = false, screenOpenWhenConnectionReturns = false))
        }
        val unspecified = AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = null)
        assertFalse(unspecified.isAutoRetryableFailure())
    }

    @Test
    fun `failed chip row lists only failed addon names in order`() {
        val names = failedAddonChipNames(
            listOf(
                AddonFetchRow("North", AddonStreamFetchStatus.Loaded(4), retryUsed = false),
                AddonFetchRow("East", AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout), retryUsed = false),
                AddonFetchRow("South", AddonStreamFetchStatus.Loaded(0), retryUsed = false),
                AddonFetchRow("West", AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline), retryUsed = true),
                AddonFetchRow("  ", AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout), retryUsed = false),
                AddonFetchRow("name://hidden", AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, 401), retryUsed = false),
                AddonFetchRow("token=hidden", AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout), retryUsed = false),
            ),
        )
        assertEquals(listOf("East", "West"), names)
    }

    @Test
    fun `offline with every addon failed or still loading is not a spinner`() {
        val failed = listOf(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout),
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline),
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = 503),
        )
        assertEquals(
            StreamListPhase.OfflineEmpty,
            resolveStreamListPhase(failed, streamCounts = listOf(0, 0, 0), deviceOffline = true),
        )
        assertEquals(
            StreamListPhase.OfflineEmpty,
            resolveStreamListPhase(
                listOf(AddonStreamFetchStatus.Loading, AddonStreamFetchStatus.Loading),
                streamCounts = listOf(0, 0),
                deviceOffline = true,
            ),
        )
        assertEquals(
            StreamListPhase.Loading,
            resolveStreamListPhase(
                listOf(AddonStreamFetchStatus.Loading, AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout)),
                streamCounts = listOf(0, 0),
                deviceOffline = false,
            ),
        )
        assertEquals(
            StreamListPhase.Empty,
            resolveStreamListPhase(failed, streamCounts = listOf(0, 0, 0), deviceOffline = false),
        )
        assertEquals(
            StreamListPhase.Results,
            resolveStreamListPhase(
                listOf(AddonStreamFetchStatus.Loaded(2), AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline)),
                streamCounts = listOf(2, 0),
                deviceOffline = true,
            ),
        )
        assertEquals(
            StreamListPhase.Empty,
            resolveStreamListPhase(
                listOf(AddonStreamFetchStatus.Loaded(0), AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout)),
                streamCounts = listOf(0, 0),
                deviceOffline = true,
            ),
        )
    }

    @Test
    fun `empty reason stays offline only when nothing actually loaded`() {
        val failed = AddonStreamGroup(
            addonName = "North",
            addonId = "north",
            streams = emptyList(),
            fetchStatus = AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline),
        )
        val loading = AddonStreamGroup(
            addonName = "East",
            addonId = "east",
            streams = emptyList(),
            isLoading = true,
            fetchStatus = AddonStreamFetchStatus.Loading,
        )
        assertEquals(
            StreamsEmptyStateReason.Offline,
            listOf(failed, loading).toEmptyStateReason(anyLoading = true, deviceOffline = true),
        )
        assertEquals(
            StreamsEmptyStateReason.StreamFetchFailed,
            listOf(failed).toEmptyStateReason(anyLoading = false, deviceOffline = false),
        )
        val emptySuccess = AddonStreamGroup(
            addonName = "South",
            addonId = "south",
            streams = emptyList(),
            fetchStatus = AddonStreamFetchStatus.Loaded(0),
        )
        assertEquals(
            StreamsEmptyStateReason.NoStreamsFound,
            listOf(emptySuccess, failed).toEmptyStateReason(anyLoading = false, deviceOffline = true),
        )
        val pluginError = AddonStreamGroup(
            addonName = "Scraper",
            addonId = "plugin:scraper",
            streams = emptyList(),
            error = "scraper failed",
        )
        assertEquals(
            StreamsEmptyStateReason.StreamFetchFailed,
            listOf(pluginError).toEmptyStateReason(anyLoading = false),
        )
    }

    @Test
    fun `failure classification keeps the reason and drops the transport text`() {
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Timeout),
            classifyAddonFetchFailure(RuntimeException("timed out"), deviceOffline = false),
        )
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = 404),
            classifyAddonFetchFailure(IllegalStateException("Request failed with HTTP 404"), deviceOffline = false),
        )
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError, httpStatus = 503),
            classifyAddonFetchFailure(IllegalStateException("Request failed with HTTP 503"), deviceOffline = false),
        )
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline),
            classifyAddonFetchFailure(IllegalStateException("Request failed with HTTP 503"), deviceOffline = true),
        )
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.Offline),
            classifyAddonFetchFailure(ConnectException(), deviceOffline = false),
        )
        assertEquals(
            AddonStreamFetchStatus.Failed(AddonStreamFailureKind.HttpError),
            classifyAddonFetchFailure(IllegalStateException("Empty response body"), deviceOffline = false),
        )
    }

    @Test
    fun `retry is allowed only when the connection can reach an addon`() {
        assertTrue(NetworkCondition.Online.addonRetriesAllowed())
        assertTrue(NetworkCondition.ServersUnreachable.addonRetriesAllowed())
        assertFalse(NetworkCondition.NoInternet.addonRetriesAllowed())
        assertFalse(NetworkCondition.Unknown.addonRetriesAllowed())
        assertFalse(NetworkCondition.Checking.addonRetriesAllowed())
        assertTrue(NetworkCondition.NoInternet.streamsDeviceOffline())
        assertFalse(NetworkCondition.Online.streamsDeviceOffline())
        assertFalse(NetworkCondition.ServersUnreachable.streamsDeviceOffline())
    }

    private fun expectedRetry(
        status: AddonStreamFetchStatus,
        online: Boolean,
        retryUsed: Boolean,
        screenOpenWhenConnectionReturns: Boolean,
    ): Boolean {
        if (!online || retryUsed || !screenOpenWhenConnectionReturns) return false
        return when (status) {
            AddonStreamFetchStatus.Loading -> false
            is AddonStreamFetchStatus.Loaded -> false
            is AddonStreamFetchStatus.Failed -> when (status.kind) {
                AddonStreamFailureKind.Timeout,
                AddonStreamFailureKind.Offline,
                -> true
                AddonStreamFailureKind.HttpError -> false
            }
        }
    }

    private fun expectedChips(status: AddonStreamFetchStatus): List<String> =
        if (status is AddonStreamFetchStatus.Failed) listOf("North") else emptyList()

    private fun AddonStreamFetchStatus.describe(): String = when (this) {
        AddonStreamFetchStatus.Loading -> "Loading"
        is AddonStreamFetchStatus.Loaded -> "Loaded($count)"
        is AddonStreamFetchStatus.Failed -> "Failed($kind)"
    }

    private class ConnectException : RuntimeException("connection refused")
}
