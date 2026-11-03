package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerTransportVisibilityTest {
    @Test
    fun `play pause and seek stay available while the submit bar is open`() {
        val submits = listOf(false, true)
        val shown = listOf(false, true)
        val layouts = listOf(false, true)
        val portraits = listOf(false, true)
        for (submitOpen in submits) {
            for (controlsShown in shown) {
                for (legacy in layouts) {
                    for (portrait in portraits) {
                        val transport = playerTransportVisibility(
                            submitOpen = submitOpen,
                            controlsShown = controlsShown,
                            legacy = legacy,
                            portrait = portrait,
                        )
                        val otherLayout = playerTransportVisibility(
                            submitOpen = submitOpen,
                            controlsShown = controlsShown,
                            legacy = !legacy,
                            portrait = !portrait,
                        )
                        assertTrue(transport == otherLayout)
                        if (submitOpen) {
                            assertTrue(transport.playPause, "submit open play")
                            assertTrue(transport.seek, "submit open seek")
                        } else if (controlsShown) {
                            assertTrue(transport.playPause)
                            assertTrue(transport.seek)
                        } else {
                            assertFalse(transport.playPause)
                            assertFalse(transport.seek)
                        }
                    }
                }
            }
        }
    }
}
