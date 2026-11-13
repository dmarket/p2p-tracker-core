package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackerStateTest {
    @Test
    fun aBlockBeforeTheFirstReadShowsInTheSteamSession() {
        val rows = listOf(
            Triple(TrackerBlock.STEAM_SESSION_MISSING, null, SteamWebSessionState.GONE),
            Triple(TrackerBlock.STEAM_ACCOUNT_MISMATCH, null, SteamWebSessionState.OTHER_ACCOUNT),
            Triple(TrackerBlock.NONE, null, null),
            Triple(TrackerBlock.DM_SESSION_MISSING, null, null),
            Triple(TrackerBlock.DM_CONNECTION_ERROR, null, null),
            // A read wins over the block.
            Triple(TrackerBlock.STEAM_SESSION_MISSING, SteamWebSessionState.ALIVE, SteamWebSessionState.ALIVE),
            Triple(TrackerBlock.NONE, SteamWebSessionState.OTHER_ACCOUNT, SteamWebSessionState.OTHER_ACCOUNT),
        )
        for ((block, read, expected) in rows) {
            val state = TrackerState(running = true, block = block, steamSession = read).withSteamSessionFromBlock()
            assertEquals(expected, state.steamSession, "$block, read $read")
        }
    }

    @Test
    fun noLoopNoSteamSession() {
        val state = TrackerState(running = false, block = TrackerBlock.STEAM_SESSION_MISSING).withSteamSessionFromBlock()

        assertEquals(null, state.steamSession)
    }
}
