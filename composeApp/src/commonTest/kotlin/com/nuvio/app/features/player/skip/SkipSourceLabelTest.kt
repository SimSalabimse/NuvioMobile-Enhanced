package com.nuvio.app.features.player.skip

import com.nuvio.app.features.analysis.SkipLearning
import com.nuvio.app.features.player.ON_DEVICE_SKIP_PROVIDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipSourceLabelTest {
    @Test fun yourMark() = assertEquals("Your mark", skipSourceLabel(SkipLearning.PROVIDER))
    @Test fun yourTheIntroDbSubmission() = assertEquals("TheIntroDB (yours)", skipSourceLabel(SkipLearning.PROVIDER_IMPORTED))
    @Test fun theIntroDb() = assertEquals("TheIntroDB", skipSourceLabel(TheIntroDb.PROVIDER))
    @Test fun onDevice() = assertEquals("On-device", skipSourceLabel(ON_DEVICE_SKIP_PROVIDER))
    @Test fun introDb() = assertEquals("IntroDB", skipSourceLabel("introdb"))
    @Test fun aniSkip() = assertEquals("AniSkip", skipSourceLabel("aniskip"))
    @Test fun animeSkip() = assertEquals("Anime-Skip", skipSourceLabel("animeskip"))
    @Test fun heuristic() = assertEquals("Guess", skipSourceLabel("heuristic"))
    @Test fun unknownAndMissing() {
        assertNull(skipSourceLabel("something-else"))
        assertNull(skipSourceLabel(null))
    }
}
