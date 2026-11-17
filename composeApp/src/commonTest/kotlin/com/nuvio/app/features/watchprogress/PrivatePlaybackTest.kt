package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.applyingPrivateSession
import com.nuvio.app.features.player.applyingSubmitMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivatePlaybackTest {
    @Test
    fun upstreamPrivateViewingDoesNotCoverServerAutoplayOrRewatch() {
        assertFalse(PlaybackSideEffect.ServerPlayback in upstreamPrivateViewingEffects)
        assertFalse(PlaybackSideEffect.Autoplay in upstreamPrivateViewingEffects)
        assertFalse(PlaybackSideEffect.Rewatch in upstreamPrivateViewingEffects)
        val saved = playbackIsolation(globalPrivateViewing = true)
        assertFalse(saved.persistsProgress)
        assertTrue(saved.allowsAutoplay)
        assertFalse(saved.allows(PlaybackSideEffect.ServerPlayback))
        assertFalse(saved.writesGlobalPrivateViewing)
    }

    @Test
    fun privateEpisodeLeavesContinueWatchingAndWritesNothing() {
        val watching = PlaybackRecord(continueWatching = ContinueWatchingRow(episode = 7, positionMs = 400_000))
        val isolation = playbackIsolation(privateSession = true)
        val after = watching.play(
            episode = 3,
            isolation = isolation,
            moments = PlaybackMoment.entries,
        )
        assertEquals(ContinueWatchingRow(7, 400_000), after.continueWatching)
        assertTrue(after.watchedEpisodes.isEmpty())
        assertTrue(after.outbox.isEmpty())
        assertTrue(after.scrobbles.isEmpty())
        assertTrue(after.extensionEvents.isEmpty())
        assertTrue(after.serverEvents.isEmpty())
        assertTrue(after.autoplay.isEmpty())
        assertFalse(after.globalPrivateViewingEnabled)
        assertFalse(isolation.writesGlobalPrivateViewing)
    }

    @Test
    fun submitModeInheritsTheSameIsolation() {
        val watching = PlaybackRecord(continueWatching = ContinueWatchingRow(episode = 7, positionMs = 400_000))
        val after = watching.play(
            episode = 3,
            isolation = playbackIsolation(submitMode = true),
            moments = listOf(PlaybackMoment.Start, PlaybackMoment.Seek, PlaybackMoment.Completion, PlaybackMoment.Exit),
        )
        assertEquals(watching.continueWatching, after.continueWatching)
        assertTrue(after.outbox.isEmpty())
        assertTrue(after.scrobbles.isEmpty())
        assertTrue(after.autoplay.isEmpty())
        assertFalse(playbackIsolation(submitMode = true).allows(PlaybackSideEffect.ExtensionScrobble))
    }

    @Test
    fun laterNormalPlaybackStillTracks() {
        val watching = PlaybackRecord(continueWatching = ContinueWatchingRow(episode = 7, positionMs = 400_000))
        val afterPrivate = watching.play(3, playbackIsolation(privateSession = true), PlaybackMoment.entries)
        val normal = afterPrivate.play(
            episode = 7,
            isolation = playbackIsolation(),
            moments = listOf(PlaybackMoment.Start, PlaybackMoment.Seek),
        )
        assertEquals(ContinueWatchingRow(7, 30_000), normal.continueWatching)
        assertTrue(normal.outbox.any { it.startsWith("7:") })
        assertTrue(normal.scrobbles.any { it.startsWith("trakt:7:") })
        assertTrue(normal.scrobbles.any { it.startsWith("simkl:7:") })
        assertTrue(normal.extensionEvents.any { it.startsWith("7:") })
        assertTrue(normal.serverEvents.any { it.startsWith("7:") })
    }

    @Test
    fun launchFlagsStayOnThisPlayback() {
        val launch = PlayerLaunch(
            profileId = 1,
            title = "Show",
            sourceUrl = "file:///episode.mp4",
            streamTitle = "Episode",
            providerName = "Local",
            parentMetaId = "tt1",
            parentMetaType = "series",
            seasonNumber = 1,
            episodeNumber = 3,
            initialPositionMs = 50_000L,
            initialProgressFraction = 0.4f,
        )
        val privateSession = launch.applyingPrivateSession(true)
        assertTrue(privateSession.privateSession)
        assertFalse(privateSession.submitMode)
        assertFalse(privateSession.persistsProgress)
        assertEquals(0L, privateSession.initialPositionMs)
        assertEquals(null, privateSession.initialProgressFraction)
        val submit = launch.applyingSubmitMode(true)
        assertTrue(submit.submitMode)
        assertFalse(submit.privateSession)
        assertFalse(submit.persistsProgress)
        assertTrue(launch.persistsProgress)
    }
}
