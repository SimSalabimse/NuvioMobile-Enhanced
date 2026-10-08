package com.nuvio.app.features.player

import com.nuvio.app.core.network.NetworkCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackStallClassifierTest {
    @Test
    fun `local file skips the network rules`() {
        assertEquals(
            PlaybackStallCause.ReadingLocalFile,
            classifyPlaybackStall(
                sample(
                    source = PlaybackStallSource.LocalFile,
                    network = NetworkCondition.NoInternet,
                    failure = PlaybackStallFailure.Http4xx,
                    stalledForMs = 5_000,
                ),
            ),
        )
    }

    @Test
    fun `offline connection wins over a host failure`() {
        assertEquals(
            PlaybackStallCause.ConnectionOffline,
            classifyPlaybackStall(
                sample(
                    network = NetworkCondition.NoInternet,
                    failure = PlaybackStallFailure.Timeout,
                    stalledForMs = 5_000,
                    incomingBytesPerSec = 0,
                ),
            ),
        )
    }

    @Test
    fun `servers unreachable is still a reachable device`() {
        assertEquals(
            PlaybackStallCause.FillingBuffer,
            classifyPlaybackStall(sample(network = NetworkCondition.ServersUnreachable, stalledForMs = 200)),
        )
        assertEquals(
            PlaybackStallCause.NotEnoughEvidence,
            classifyPlaybackStall(sample(network = NetworkCondition.ServersUnreachable, stalledForMs = 5_000)),
        )
    }

    @Test
    fun `a fresh stall is filling the buffer even with a host error`() {
        assertEquals(
            PlaybackStallCause.FillingBuffer,
            classifyPlaybackStall(
                sample(
                    stalledForMs = 1_499,
                    failure = PlaybackStallFailure.Http4xx,
                    incomingBytesPerSec = 0,
                    mediaBitrateBps = 8_000_000,
                ),
            ),
        )
    }

    @Test
    fun `http 4xx is the host rejecting the request`() {
        assertEquals(
            PlaybackStallCause.HostRejected,
            classifyPlaybackStall(sample(stalledForMs = 1_500, failure = PlaybackStallFailure.Http4xx, incomingBytesPerSec = 0)),
        )
    }

    @Test
    fun `timeout http 5xx and connection reset mean the host is not responding`() {
        listOf(
            PlaybackStallFailure.Timeout,
            PlaybackStallFailure.Http5xx,
            PlaybackStallFailure.ConnectionReset,
        ).forEach { failure ->
            assertEquals(
                PlaybackStallCause.HostNotResponding,
                classifyPlaybackStall(sample(stalledForMs = 1_500, failure = failure, incomingBytesPerSec = 1_000_000)),
            )
        }
    }

    @Test
    fun `little incoming data from a reachable network is a silent host`() {
        listOf(NetworkCondition.Online, NetworkCondition.ServersUnreachable).forEach { network ->
            assertEquals(
                PlaybackStallCause.HostSilent,
                classifyPlaybackStall(
                    sample(
                        network = network,
                        stalledForMs = 1_500,
                        incomingBytesPerSec = 8_191,
                        mediaBitrateBps = 8_000_000,
                    ),
                ),
            )
        }
        assertEquals(
            PlaybackStallCause.HostSilent,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 0)),
        )
    }

    @Test
    fun `little incoming data without a known network is not enough evidence`() {
        listOf(NetworkCondition.Unknown, NetworkCondition.Checking).forEach { network ->
            assertEquals(
                PlaybackStallCause.NotEnoughEvidence,
                classifyPlaybackStall(sample(network = network, stalledForMs = 5_000, incomingBytesPerSec = 1_000)),
            )
        }
    }

    @Test
    fun `incoming below 80 percent of the stream bitrate is slower than playback`() {
        assertEquals(
            PlaybackStallCause.ArrivingSlowerThanPlayback,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 100_000, mediaBitrateBps = 8_000_000)),
        )
        assertEquals(
            PlaybackStallCause.ArrivingSlowerThanPlayback,
            classifyPlaybackStall(sample(stalledForMs = 1_500, incomingBytesPerSec = 8_192, mediaBitrateBps = 8_000_000)),
        )
        assertEquals(
            PlaybackStallCause.ArrivingSlowerThanPlayback,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 799_999, mediaBitrateBps = 8_000_000)),
        )
    }

    @Test
    fun `enough incoming data means the player is catching up`() {
        assertEquals(
            PlaybackStallCause.PlayerCatchingUp,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 800_000, mediaBitrateBps = 8_000_000)),
        )
        assertEquals(
            PlaybackStallCause.PlayerCatchingUp,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 900_000, mediaBitrateBps = 8_000_000)),
        )
        assertEquals(
            PlaybackStallCause.PlayerCatchingUp,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 8_192, mediaBitrateBps = null)),
        )
        assertEquals(
            PlaybackStallCause.PlayerCatchingUp,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = 8_192, mediaBitrateBps = 0)),
        )
    }

    @Test
    fun `missing speed or bitrate falls through to not enough evidence`() {
        assertEquals(
            PlaybackStallCause.NotEnoughEvidence,
            classifyPlaybackStall(sample(stalledForMs = 5_000, incomingBytesPerSec = null, mediaBitrateBps = 8_000_000)),
        )
        assertEquals(
            PlaybackStallCause.NotEnoughEvidence,
            classifyPlaybackStall(sample(stalledForMs = 1_500)),
        )
    }

    @Test
    fun `http status codes map to the host failure`() {
        assertEquals(PlaybackStallFailure.None, playbackStallFailureForHttpStatus(399))
        assertEquals(PlaybackStallFailure.Http4xx, playbackStallFailureForHttpStatus(400))
        assertEquals(PlaybackStallFailure.Http4xx, playbackStallFailureForHttpStatus(404))
        assertEquals(PlaybackStallFailure.Http4xx, playbackStallFailureForHttpStatus(499))
        assertEquals(PlaybackStallFailure.Http5xx, playbackStallFailureForHttpStatus(500))
        assertEquals(PlaybackStallFailure.Http5xx, playbackStallFailureForHttpStatus(599))
        assertEquals(PlaybackStallFailure.None, playbackStallFailureForHttpStatus(600))
    }

    @Test
    fun `only a reachable network points at the stream host`() {
        assertTrue(stallPointsAtStreamHost(NetworkCondition.Online))
        assertTrue(stallPointsAtStreamHost(NetworkCondition.ServersUnreachable))
        assertFalse(stallPointsAtStreamHost(NetworkCondition.Unknown))
        assertFalse(stallPointsAtStreamHost(NetworkCondition.Checking))
        assertFalse(stallPointsAtStreamHost(NetworkCondition.NoInternet))
    }

    @Test
    fun `hls bandwidth is only the fallback when the player has no bitrate`() {
        assertEquals(100L, resolveStallMediaBitrateBps(reportedBps = 100, hlsBandwidthBps = 999))
        assertEquals(999L, resolveStallMediaBitrateBps(reportedBps = 0, hlsBandwidthBps = 999))
        assertEquals(999L, resolveStallMediaBitrateBps(reportedBps = null, hlsBandwidthBps = 999))
        assertNull(resolveStallMediaBitrateBps(reportedBps = null, hlsBandwidthBps = 0))
        assertNull(resolveStallMediaBitrateBps(reportedBps = null, hlsBandwidthBps = null))
    }

    @Test
    fun `local paths are local files and remote urls stay remote`() {
        assertEquals(PlaybackStallSource.LocalFile, playbackStallSource("file:///tmp/movie.mkv"))
        assertEquals(PlaybackStallSource.LocalFile, playbackStallSource("FILE:///tmp/movie.mkv"))
        assertEquals(PlaybackStallSource.LocalFile, playbackStallSource("content://media/external/video/12"))
        assertEquals(PlaybackStallSource.LocalFile, playbackStallSource("/storage/emulated/0/Movies/a.mkv"))
        assertEquals(PlaybackStallSource.Remote, playbackStallSource("https://cdn.example.com/a.mkv"))
        assertEquals(PlaybackStallSource.Remote, playbackStallSource("magnet:?xt=urn:btih:abc"))
        assertEquals(PlaybackStallSource.Remote, playbackStallSource(null))
        assertEquals(
            PlaybackStallCause.ReadingLocalFile,
            classifyPlaybackStall(
                sample(
                    source = playbackStallSource("/storage/emulated/0/Movies/a.mkv"),
                    network = NetworkCondition.NoInternet,
                ),
            ),
        )
    }

    @Test
    fun `the displayed host drops userinfo path and query`() {
        val url = "https://user:secret@cdn.example.com:8443/video/a.mkv?token=abc&x=1#t=10"
        assertEquals("cdn.example.com", streamHostFromUrl(url))
        assertEquals("cdn.example.com", displayStreamHost(url, url))
        assertEquals("192.168.1.20", streamHostFromUrl("http://192.168.1.20:8080/stream"))
        assertEquals("2001:db8::1", streamHostFromUrl("http://[2001:db8::1]:443/x"))
        assertEquals("cdn.example.com", displayStreamHost("cdn.example.com", url))
        assertNull(streamHostFromUrl("file:///tmp/movie.mkv"))
        assertNull(streamHostFromUrl("content://media/external/video/12"))
        assertNull(streamHostFromUrl("/storage/emulated/0/Movies/a.mkv"))
        val host = displayStreamHost(null, url)
        assertEquals("cdn.example.com", host)
        assertFalse(host!!.contains("secret"))
        assertFalse(host.contains("token"))
        assertFalse(host.contains("/video"))
        assertFalse(host.contains("://"))
        assertFalse(host.contains("@"))
    }

    private fun sample(
        source: PlaybackStallSource = PlaybackStallSource.Remote,
        network: NetworkCondition = NetworkCondition.Online,
        stalledForMs: Long = 5_000,
        failure: PlaybackStallFailure = PlaybackStallFailure.None,
        incomingBytesPerSec: Long? = null,
        mediaBitrateBps: Long? = null,
    ) = PlaybackStallSample(
        source = source,
        network = network,
        stalledForMs = stalledForMs,
        failure = failure,
        incomingBytesPerSec = incomingBytesPerSec,
        mediaBitrateBps = mediaBitrateBps,
    )
}
