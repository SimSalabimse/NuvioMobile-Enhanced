package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class AddonLoadRetryTest {
    private val manifest = AddonManifest(
        id = "addon",
        name = "Addon",
        description = "",
        version = "1.0.0",
        resources = emptyList(),
        types = emptyList(),
        transportUrl = "https://example.com/manifest.json",
    )

    @Test
    fun failsOnceThenSucceeds() = runTest {
        var calls = 0
        val result = loadAddonWithRetry(failureMessage = { it.message ?: "failed" }) {
            calls += 1
            if (calls == 1) throw IllegalStateException("Request failed with HTTP 503")
            manifest
        }
        val loaded = assertIs<AddonLoadResult.Loaded>(result)
        assertEquals(manifest, loaded.manifest)
        assertEquals(2, calls)
    }

    @Test
    fun failsTwiceSetsErrorAfterTwoCalls() = runTest {
        var calls = 0
        val result = loadAddonWithRetry(failureMessage = { it.message ?: "failed" }) {
            calls += 1
            throw Exception("network unreachable")
        }
        val failed = assertIs<AddonLoadResult.Failed>(result)
        assertEquals("network unreachable", failed.errorMessage)
        assertEquals(2, calls)
    }

    @Test
    fun http4xxAndInvalidManifestDoNotRetry() = runTest {
        var clientErrors = 0
        val client = loadAddonWithRetry(failureMessage = { it.message ?: "failed" }) {
            clientErrors += 1
            throw IllegalStateException("Request failed with HTTP 404")
        }
        assertIs<AddonLoadResult.Failed>(client)
        assertEquals(1, clientErrors)

        var parseErrors = 0
        val parsed = loadAddonWithRetry(failureMessage = { it.message ?: "failed" }) {
            parseErrors += 1
            throw IllegalArgumentException("manifest missing id")
        }
        assertIs<AddonLoadResult.Failed>(parsed)
        assertEquals(1, parseErrors)
    }

    @Test
    fun previouslyFailedAddonLaunchesRefresh() {
        val failed = ManagedAddon(
            manifestUrl = "https://example.com/manifest.json",
            manifest = null,
            isRefreshing = false,
            errorMessage = "Request failed with HTTP 503",
        )
        // pullFromServer rebuilds this into a pending row that looks busy and clears the error.
        val rebuilt = failed.copy(isRefreshing = true, errorMessage = null)
        assertFalse(rebuilt.manifest == null && !rebuilt.isRefreshing)

        assertTrue(shouldLaunchAddonRefresh(existing = failed, refreshJobActive = false))
        assertTrue(shouldLaunchAddonRefresh(existing = null, refreshJobActive = false))
        assertFalse(shouldLaunchAddonRefresh(existing = failed, refreshJobActive = true))
        assertFalse(shouldLaunchAddonRefresh(existing = failed.copy(manifest = manifest), refreshJobActive = false))
    }

    @Test
    fun cancellationIsNotStoredAsAnError() = runTest {
        var calls = 0
        var stored: String? = null
        assertFailsWith<CancellationException> {
            val result = loadAddonWithRetry(failureMessage = { it.message ?: "failed" }) {
                calls += 1
                throw CancellationException("stopped")
            }
            stored = (result as? AddonLoadResult.Failed)?.errorMessage
        }
        assertEquals(1, calls)
        assertNull(stored)
    }

    @Test
    fun catalogTimeoutRetriesOnce() = runTest {
        var calls = 0
        val value = withTransientRetry {
            calls += 1
            if (calls == 1) throw IllegalStateException("Catalog request timed out: Popular")
            "row"
        }
        assertEquals("row", value)
        assertEquals(2, calls)
    }
}
