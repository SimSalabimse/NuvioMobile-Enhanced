package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerSettingsProfileSyncTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
    }

    @Test
    fun skipAndSubtitleSettingsRoundTripThroughProfileSync() {
        val appearance = SubtitleAppearance(
            fontId = "custom-11111111-1111-1111-1111-111111111111",
            shadowEnabled = true,
            shadowOpacity = 40,
        ).encode()
        PlayerSettingsStorage.saveSkipBackwardSeconds(15)
        PlayerSettingsStorage.saveSubtitleAppearance(appearance)

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(15, payload.decodeSyncInt("skip_backward_seconds"))
        assertEquals(appearance, payload.decodeSyncString("subtitle_appearance"))

        PlayerSettingsStorage.saveSkipBackwardSeconds(10)
        PlayerSettingsStorage.saveSubtitleAppearance(SubtitleAppearance().encode())
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(15, PlayerSettingsStorage.loadSkipBackwardSeconds())
        assertEquals(appearance, PlayerSettingsStorage.loadSubtitleAppearance())

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertNull(PlayerSettingsStorage.loadSkipBackwardSeconds())
        assertNull(PlayerSettingsStorage.loadSubtitleAppearance())
    }
}
