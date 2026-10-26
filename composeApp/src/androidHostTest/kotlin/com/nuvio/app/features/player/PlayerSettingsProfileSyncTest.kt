package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncBoolean
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
        PlayerSettingsStorage.saveSkipBackwardSeconds(15)
        PlayerSettingsStorage.saveSubtitleCustomFont("Noto.ttf")
        PlayerSettingsStorage.saveSubtitleShadowEnabled(true)
        PlayerSettingsStorage.saveSubtitleShadowStrength(40)

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertEquals(15, payload.decodeSyncInt("skip_backward_seconds"))
        assertEquals("Noto.ttf", payload.decodeSyncString("subtitle_custom_font"))
        assertEquals(true, payload.decodeSyncBoolean("subtitle_shadow_enabled"))
        assertEquals(40, payload.decodeSyncInt("subtitle_shadow_strength"))

        PlayerSettingsStorage.saveSkipBackwardSeconds(10)
        PlayerSettingsStorage.saveSubtitleCustomFont(null)
        PlayerSettingsStorage.saveSubtitleShadowEnabled(false)
        PlayerSettingsStorage.saveSubtitleShadowStrength(0)
        PlayerSettingsStorage.replaceFromSyncPayload(payload)

        assertEquals(15, PlayerSettingsStorage.loadSkipBackwardSeconds())
        assertEquals("Noto.ttf", PlayerSettingsStorage.loadSubtitleCustomFont())
        assertEquals(true, PlayerSettingsStorage.loadSubtitleShadowEnabled())
        assertEquals(40, PlayerSettingsStorage.loadSubtitleShadowStrength())

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertNull(PlayerSettingsStorage.loadSkipBackwardSeconds())
        assertNull(PlayerSettingsStorage.loadSubtitleCustomFont())
        assertNull(PlayerSettingsStorage.loadSubtitleShadowEnabled())
        assertNull(PlayerSettingsStorage.loadSubtitleShadowStrength())
    }
}
