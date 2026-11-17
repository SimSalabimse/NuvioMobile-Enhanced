package com.nuvio.app.core.security

import com.nuvio.app.core.automation.AutomationTestMode
import com.nuvio.app.core.uifixture.uiFixturesActive

/** Apple errSecSuccess. */
internal const val ERR_SEC_SUCCESS = 0

/** Apple errSecItemNotFound. The keychain answered: nothing is stored. */
internal const val ERR_SEC_ITEM_NOT_FOUND = -25_300

/** Apple errSecInteractionNotAllowed. The item may exist; the keychain is locked. */
internal const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25_308

/** Apple errSecNotAvailable. The keychain service is not up. */
internal const val ERR_SEC_NOT_AVAILABLE = -25_291

/**
 * Apple errSecMissingEntitlement.
 * An unsigned simulator build has no keychain entitlement, so every query returns this.
 * The item is not deleted.
 */
internal const val ERR_SEC_MISSING_ENTITLEMENT = -34_018

internal enum class ProtectedCredentialRead {
    /** The bytes belong to the caller. */
    Present,
    /** The keychain confirmed there is no item. */
    Absent,
    /**
     * The process cannot read the keychain. The item may still be there.
     * Missing entitlement, a locked keychain, and an unavailable keychain are this case.
     */
    Unavailable,
}

internal enum class ProtectedCredentialDelete {
    Removed,
    AlreadyAbsent,
    /** The keychain refused the delete. The item is unchanged. */
    Refused,
}

internal fun protectedCredentialRead(status: Int): ProtectedCredentialRead = when (status) {
    ERR_SEC_SUCCESS -> ProtectedCredentialRead.Present
    ERR_SEC_ITEM_NOT_FOUND -> ProtectedCredentialRead.Absent
    ERR_SEC_MISSING_ENTITLEMENT,
    ERR_SEC_INTERACTION_NOT_ALLOWED,
    ERR_SEC_NOT_AVAILABLE,
    -> ProtectedCredentialRead.Unavailable
    else -> throw IllegalStateException("Unable to read protected credentials (OSStatus $status)")
}

internal fun protectedCredentialDelete(status: Int): ProtectedCredentialDelete = when (status) {
    ERR_SEC_SUCCESS -> ProtectedCredentialDelete.Removed
    ERR_SEC_ITEM_NOT_FOUND -> ProtectedCredentialDelete.AlreadyAbsent
    ERR_SEC_MISSING_ENTITLEMENT,
    ERR_SEC_INTERACTION_NOT_ALLOWED,
    ERR_SEC_NOT_AVAILABLE,
    -> ProtectedCredentialDelete.Refused
    else -> throw IllegalStateException("Unable to clear protected credentials (OSStatus $status)")
}

/** Fixture and automation launches do not construct credential-backed integrations. */
internal fun registerCredentialBackedIntegrations(isolated: Boolean, register: () -> Unit) {
    if (isolated) return
    register()
}

/** Fixture and automation launches do not open the real keychain. */
internal fun protectedCredentialIntegrationsIsolated(
    automationActive: Boolean = AutomationTestMode.isActive,
    fixtureActive: Boolean = uiFixturesActive(),
): Boolean = automationActive || fixtureActive

/**
 * Startup read. An isolated launch does not call [read].
 * An unavailable keychain returns null and does not delete [stored].
 */
internal fun readStartupCredential(
    isolated: Boolean,
    status: Int,
    stored: String?,
    read: () -> Unit,
): String? {
    if (isolated) return null
    read()
    return when (protectedCredentialRead(status)) {
        ProtectedCredentialRead.Present -> stored
        ProtectedCredentialRead.Absent,
        ProtectedCredentialRead.Unavailable,
        -> null
    }
}
