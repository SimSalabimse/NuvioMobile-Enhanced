package com.nuvio.app.core.security

import com.nuvio.app.features.mdblist.MdbListAuthPersistence
import com.nuvio.app.features.mdblist.MdbListAuthStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtectedCredentialStartupTest {
    @Test
    fun missingEntitlementIsUnavailableAndDoesNotReadAsEmptyOrDelete() {
        assertEquals(
            ProtectedCredentialRead.Unavailable,
            protectedCredentialRead(ERR_SEC_MISSING_ENTITLEMENT),
        )
        assertEquals(
            ProtectedCredentialDelete.Refused,
            protectedCredentialDelete(ERR_SEC_MISSING_ENTITLEMENT),
        )
        var reads = 0
        val value = readStartupCredential(
            isolated = false,
            status = ERR_SEC_MISSING_ENTITLEMENT,
            stored = "still-in-keychain",
        ) { reads += 1 }
        assertNull(value)
        assertEquals(1, reads)
    }

    @Test
    fun lockedAndUnavailableKeychainStayRefused() {
        listOf(ERR_SEC_INTERACTION_NOT_ALLOWED, ERR_SEC_NOT_AVAILABLE).forEach { status ->
            assertEquals(ProtectedCredentialRead.Unavailable, protectedCredentialRead(status))
            assertEquals(ProtectedCredentialDelete.Refused, protectedCredentialDelete(status))
        }
    }

    @Test
    fun aConfirmedMissIsAbsentAndARealItemStaysPresent() {
        assertEquals(ProtectedCredentialRead.Absent, protectedCredentialRead(ERR_SEC_ITEM_NOT_FOUND))
        assertEquals(ProtectedCredentialDelete.AlreadyAbsent, protectedCredentialDelete(ERR_SEC_ITEM_NOT_FOUND))
        assertEquals(ProtectedCredentialRead.Present, protectedCredentialRead(ERR_SEC_SUCCESS))
        assertEquals(ProtectedCredentialDelete.Removed, protectedCredentialDelete(ERR_SEC_SUCCESS))
        assertEquals(
            "token",
            readStartupCredential(isolated = false, status = ERR_SEC_SUCCESS, stored = "token") {},
        )
    }

    @Test
    fun aGenuineStatusStillFails() {
        val failure = assertFailsWith<IllegalStateException> {
            protectedCredentialRead(-50)
        }
        assertTrue(failure.message.orEmpty().contains("OSStatus -50"))
        assertFailsWith<IllegalStateException> {
            protectedCredentialDelete(-50)
        }
    }

    @Test
    fun fixtureAndAutomationDoNotTouchTheStore() {
        assertTrue(protectedCredentialIntegrationsIsolated(automationActive = true, fixtureActive = false))
        assertTrue(protectedCredentialIntegrationsIsolated(automationActive = false, fixtureActive = true))
        assertFalse(protectedCredentialIntegrationsIsolated(automationActive = false, fixtureActive = false))
        var reads = 0
        assertNull(
            readStartupCredential(isolated = true, status = ERR_SEC_SUCCESS, stored = "secret") {
                reads += 1
            },
        )
        assertEquals(0, reads)
        var registered = 0
        registerCredentialBackedIntegrations(isolated = true) { registered += 1 }
        assertEquals(0, registered)
        registerCredentialBackedIntegrations(isolated = false) { registered += 1 }
        assertEquals(1, registered)
    }

    @Test
    fun anUnavailableReadDoesNotSignOutByWriting() {
        var writes = 0
        var clears = 0
        val persistence = object : MdbListAuthPersistence {
            override fun read(profileId: Int): String? {
                check(protectedCredentialRead(ERR_SEC_MISSING_ENTITLEMENT) == ProtectedCredentialRead.Unavailable)
                return null
            }

            override fun write(profileId: Int, value: String?) {
                writes += 1
            }

            override fun clear() {
                clears += 1
            }
        }
        val store = MdbListAuthStore(persistence)
        assertFalse(store.state.value.isAuthenticated)
        assertEquals(0, writes)
        assertEquals(0, clears)
    }
}
