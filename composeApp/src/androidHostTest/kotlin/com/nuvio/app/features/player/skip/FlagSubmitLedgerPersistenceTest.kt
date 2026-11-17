package com.nuvio.app.features.player.skip

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FlagSubmitLedgerPersistenceTest {
    @Test
    fun rememberTypeIsStillThereOnTheNextRead() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("nuvio_flag_submit_ledger", Context.MODE_PRIVATE)
        val storedKey = ProfileScopedKey.of("flag_submit_ledger")
        val previous = prefs.getString(storedKey, null)
        prefs.edit().remove(storedKey).commit()
        FlagSubmitLedger.initialize(context)
        FlagSubmitLedger.rememberType("tt0000001:4:5", "preview")
        val raw = prefs.getString(storedKey, "").orEmpty()
        assertTrue(raw.contains("tt0000001:4:5=preview"), raw)
        FlagSubmitLedger.saveAll(emptyMap())
        assertEquals(setOf("preview"), decodeFlagSubmitLedger(raw)["tt0000001:4:5"])
        if (previous == null) prefs.edit().remove(storedKey).commit() else prefs.edit().putString(storedKey, previous).commit()
    }
}
