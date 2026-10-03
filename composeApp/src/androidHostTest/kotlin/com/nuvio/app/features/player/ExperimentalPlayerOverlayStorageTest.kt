package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import com.nuvio.app.core.sync.decodeSyncBoolean
import kotlinx.serialization.json.buildJsonObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExperimentalPlayerOverlayStorageTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        PlayerSettingsRepository.clearLocalState()
    }

    @Test
    fun experimentalOverlayDefaultsOffSurvivesReloadAndStaysOutOfSync() {
        PlayerSettingsRepository.ensureLoaded()
        assertFalse(PlayerSettingsRepository.uiState.value.useExperimentalPlayerOverlay)
        assertEquals(
            PlayerOverlayPath.Developer,
            playerOverlayPath(PlayerSettingsRepository.uiState.value.useExperimentalPlayerOverlay),
        )

        PlayerSettingsRepository.setUseExperimentalPlayerOverlay(true)
        assertFalse(PlayerSettingsRepository.uiState.value.useLegacyPlayerLayout)
        PlayerSettingsRepository.clearLocalState()
        PlayerSettingsRepository.ensureLoaded()
        assertTrue(PlayerSettingsRepository.uiState.value.useExperimentalPlayerOverlay)
        assertFalse(PlayerSettingsRepository.uiState.value.useLegacyPlayerLayout)
        assertEquals(
            PlayerOverlayPath.Experimental,
            playerOverlayPath(PlayerSettingsRepository.uiState.value.useExperimentalPlayerOverlay),
        )

        val payload = PlayerSettingsStorage.exportToSyncPayload()
        assertNull(payload.decodeSyncBoolean("use_experimental_player_overlay"))
        assertNull(payload.decodeSyncBoolean("use_legacy_player_layout"))

        PlayerSettingsStorage.replaceFromSyncPayload(buildJsonObject {})
        assertEquals(true, PlayerSettingsStorage.loadUseExperimentalPlayerOverlay())
        assertEquals(false, PlayerSettingsRepository.uiState.value.useLegacyPlayerLayout)
    }
}
