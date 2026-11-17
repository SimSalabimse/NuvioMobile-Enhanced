package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TorboxDeviceAuthTest {
    @Test
    fun `maps unused device code response to pending`() {
        val response = DebridApiResponse(
            status = 400,
            body = TorboxEnvelopeDto<TorboxDeviceTokenDto>(
                success = false,
                detail = "This device code has not been used yet. Please wait for the user to scan the code.",
            ),
            rawBody = "",
        )

        assertEquals(
            DebridDeviceAuthorizationTokenResult.Pending,
            torboxDeviceAuthorizationTokenResult(response),
        )
    }

    @Test
    fun `maps authorized and expired Torbox device states`() {
        assertTrue(
            torboxDeviceAuthorizationTokenResult(
                DebridApiResponse(
                    status = 200,
                    body = TorboxEnvelopeDto(
                        success = true,
                        data = TorboxDeviceTokenDto(accessToken = "tb-token", tokenType = "Bearer"),
                    ),
                    rawBody = "",
                ),
            ) is DebridDeviceAuthorizationTokenResult.Authorized,
        )
        assertEquals(
            DebridDeviceAuthorizationTokenResult.Expired,
            torboxDeviceAuthorizationTokenResult(
                DebridApiResponse(
                    status = 410,
                    body = TorboxEnvelopeDto(success = false, detail = "Device code expired."),
                    rawBody = "",
                ),
            ),
        )
    }

    @Test
    fun `torbox device check stays at or under ten per minute`() {
        assertEquals(6_000L, deviceAuthorizationPollDelayMillis(DebridProviders.TORBOX_ID, 1))
        assertEquals(6_000L, deviceAuthorizationPollDelayMillis(DebridProviders.TORBOX_ID, 5))
        assertEquals(10_000L, deviceAuthorizationPollDelayMillis(DebridProviders.TORBOX_ID, 10))
        assertEquals(1_000L, deviceAuthorizationPollDelayMillis(DebridProviders.PREMIUMIZE_ID, 1))
        assertEquals(5_000L, deviceAuthorizationPollDelayMillis(DebridProviders.PREMIUMIZE_ID, 5))
    }

    @Test
    fun `torbox device start waits out the thirty per minute limit`() {
        assertEquals(0L, deviceAuthorizationStartDelayMillis(DebridProviders.TORBOX_ID, null))
        assertEquals(2_000L, deviceAuthorizationStartDelayMillis(DebridProviders.TORBOX_ID, 0L))
        assertEquals(500L, deviceAuthorizationStartDelayMillis(DebridProviders.TORBOX_ID, 1_500L))
        assertEquals(0L, deviceAuthorizationStartDelayMillis(DebridProviders.TORBOX_ID, 2_000L))
        assertEquals(0L, deviceAuthorizationStartDelayMillis(DebridProviders.PREMIUMIZE_ID, 0L))
    }
}
