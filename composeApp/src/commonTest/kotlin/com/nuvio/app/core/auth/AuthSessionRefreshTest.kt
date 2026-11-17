package com.nuvio.app.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A refresh that fails for a reason other than a rejected token must not look
 * like an invalid session. [AuthRepository.signOutIfSessionInvalid] clears the
 * local session only when the refresh is rejected.
 */
class AuthSessionRefreshTest {
    @Test
    fun aPlainFailureIsNotAnInvalidRefresh() {
        assertFalse(Exception("timeout").isInvalidRefreshError())
    }
}
