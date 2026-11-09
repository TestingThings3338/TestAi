package com.starwake.core.input

import com.starwake.core.game.Branch
import com.starwake.core.game.Overlay
import com.starwake.core.game.PlayerMode
import com.starwake.core.game.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The input gating matrix of 01-core-loop.md 4.5 (port version) and the input owner of 8.1.2. */
class InputGateTest {
    private fun mode(set: PlayerMode.() -> Unit = {}) = PlayerMode().apply { revealed = true }.apply(set)

    private val flightActions = listOf(
        Action.THROTTLE_UP, Action.STRAFE_LEFT, Action.YAW_LEFT, Action.FULL_STOP, Action.TOGGLE_FLIGHT_ASSIST,
        Action.BOOST, Action.CYCLE_TARGET, Action.CLEAR_TARGET, Action.SYSTEM_MAP, Action.STAR_MAP, Action.AUTOPILOT,
        Action.LAND, Action.DOCK, Action.EXTERNAL_VIEW, Action.CANCEL_JUMP, Action.SURVEY, Action.MINE,
    )

    @Test
    fun everythingFliesInFreeFlight() {
        val m = mode()
        for (a in flightActions) assertTrue(InputGate.allows(a, m), "$a in free flight")
        assertTrue(InputGate.thrustEnabled(m))
        assertEquals(InputGate.OWNER_FLIGHT, InputGate.owner(m))
        assertEquals(PlayerState.FLYING, m.state)
    }

    @Test
    fun flightActionsAreIgnoredOnADeckAboardAndInTheCinematic() {
        for (m in listOf(mode { branch = Branch.DECK }, mode { onFoot = true }, mode { branch = Branch.DESTROYED })) {
            for (a in flightActions) assertFalse(InputGate.allows(a, m), "$a must not fly (${m.state})")
            assertFalse(InputGate.thrustEnabled(m))
        }
        assertEquals(InputGate.OWNER_WALKER, InputGate.owner(mode { branch = Branch.DECK }))
        assertEquals(InputGate.OWNER_WALKER, InputGate.owner(mode { onFoot = true }))
        assertEquals(InputGate.OWNER_NONE, InputGate.owner(mode { branch = Branch.DESTROYED }))
        assertTrue(InputGate.allows(Action.INTERACT, mode { onFoot = true }))
        assertFalse(InputGate.allows(Action.INTERACT, mode()))
    }

    @Test
    fun leaveSeatAndMusicPassWhereRobloxLetsThem() {
        // music works everywhere, the cinematic and the curtain included
        for (m in listOf(mode(), mode { branch = Branch.DECK }, mode { onFoot = true }, mode { branch = Branch.DESTROYED }, mode { curtain = true }, mode { travelling = true })) {
            assertTrue(InputGate.allows(Action.TOGGLE_MUSIC, m), "music in ${m.state}")
        }
        // leaving the seat: in flight, in a jump and on a deck (the deck decides the hangar); not aboard,
        // not under the docking computer, not with the services open, not in the cinematic
        assertTrue(InputGate.allows(Action.LEAVE_SEAT, mode()))
        assertTrue(InputGate.allows(Action.LEAVE_SEAT, mode { travelling = true }))
        assertTrue(InputGate.allows(Action.LEAVE_SEAT, mode { branch = Branch.DECK }))
        assertFalse(InputGate.allows(Action.LEAVE_SEAT, mode { onFoot = true }))
        assertFalse(InputGate.allows(Action.LEAVE_SEAT, mode { controlled = true }))
        assertFalse(InputGate.allows(Action.LEAVE_SEAT, mode { overlay = Overlay.SERVICES }))
        assertFalse(InputGate.allows(Action.LEAVE_SEAT, mode { branch = Branch.DESTROYED }))
    }

    @Test
    fun stationPanelNeverAboardAndOnlyAfterTheReveal() {
        assertTrue(InputGate.allows(Action.STATION_PANEL, mode()))
        assertTrue(InputGate.allows(Action.STATION_PANEL, mode { branch = Branch.DECK }), "works on decks")
        assertFalse(InputGate.allows(Action.STATION_PANEL, mode { onFoot = true }), "not aboard")
        assertFalse(InputGate.allows(Action.STATION_PANEL, mode { travelling = true }), "not in a jump")
        assertFalse(InputGate.allows(Action.STATION_PANEL, mode { revealed = false }), "not before the reveal")
    }

    @Test
    fun cancelJumpNotWhileTravelling() {
        assertTrue(InputGate.allows(Action.CANCEL_JUMP, mode { plotted = true }))
        assertFalse(InputGate.allows(Action.CANCEL_JUMP, mode { travelling = true }))
    }

    @Test
    fun travellingAndControlledZeroTheAxes() {
        for (m in listOf(mode { travelling = true }, mode { controlled = true }, mode { onFoot = true })) {
            val f = InputFrame()
            f.aimX = 0.7f; f.aimY = -0.2f; f.keyAxis = 1f; f.lateralX = 1f; f.lateralY = -1f
            f.leverSet = true; f.lever = 0.5f
            InputGate.apply(f, m)
            assertEquals(0f, f.aimX)
            assertEquals(0f, f.aimY)
            assertEquals(0f, f.keyAxis)
            assertEquals(0f, f.lateralX)
            assertEquals(0f, f.lateralY)
            assertFalse(f.leverSet, "the lever does not reach the thrust (${m.state})")
            assertFalse(InputGate.allows(Action.THROTTLE_UP, m))
        }
        // FA works in a jump and under the docking computer; boost is cleared while controlled (C5)
        assertTrue(InputGate.allows(Action.TOGGLE_FLIGHT_ASSIST, mode { travelling = true }))
        assertTrue(InputGate.allows(Action.TOGGLE_FLIGHT_ASSIST, mode { controlled = true }))
        assertFalse(InputGate.allows(Action.BOOST, mode { controlled = true }))
        // the docking computer still answers K, and targets, views and maps keep working
        for (a in listOf(Action.DOCK, Action.CYCLE_TARGET, Action.EXTERNAL_VIEW, Action.SYSTEM_MAP)) {
            assertTrue(InputGate.allows(a, mode { controlled = true }))
        }
    }

    @Test
    fun mapsDoNotPausePauseDoes() {
        assertFalse(Overlay.MAP.pauses)
        assertFalse(Overlay.SERVICES.pauses)
        assertTrue(Overlay.PAUSE.pauses)
        assertTrue(Overlay.SETTINGS.pauses)
        assertFalse(Overlay.NONE.pauses)
        // under a map the flight keeps its thrust input (a keyboard still flies); the touches belong to the map
        val map = mode { overlay = Overlay.MAP }
        assertTrue(map.simulating)
        assertTrue(InputGate.thrustEnabled(map))
        assertEquals(InputGate.OWNER_OVERLAY, InputGate.owner(map))
        assertFalse(InputGate.allows(Action.LOOK, map), "the map owns the drag")
        // the services: the world runs, the flight controls are hidden, nothing to steer (docked = controlled)
        assertTrue(mode { overlay = Overlay.SERVICES }.simulating)
        // pause: nothing but the system keys
        val paused = mode { overlay = Overlay.PAUSE }
        assertFalse(paused.simulating)
        for (a in flightActions) assertFalse(InputGate.allows(a, paused), "$a under PAUSE")
        assertTrue(InputGate.allows(Action.PAUSE, paused))
        assertTrue(InputGate.allows(Action.BACK, paused))
    }

    @Test
    fun theCurtainTakesTheTouchesExceptPause() {
        val m = mode { curtain = true }
        assertEquals(InputGate.OWNER_NONE, InputGate.owner(m))
        assertEquals(PlayerState.LOADING, m.state)
        for (a in flightActions) assertFalse(InputGate.allows(a, m))
        assertTrue(InputGate.allows(Action.PAUSE, m))
        val f = InputFrame()
        f.press(Action.BOOST)
        f.press(Action.PAUSE)
        f.hold(Action.STRAFE_UP)
        InputGate.apply(f, m)
        assertFalse(f.pressed(Action.BOOST))
        assertFalse(f.held(Action.STRAFE_UP))
        assertTrue(f.pressed(Action.PAUSE))
    }

    @Test
    fun playerStatesFollowTheFlags() {
        assertEquals(PlayerState.DOCKED, mode { docked = true; controlled = true }.state)
        assertEquals(PlayerState.DOCKING, mode { docking = true; controlled = true }.state)
        assertEquals(PlayerState.LANDED, mode { landed = true }.state)
        assertEquals(PlayerState.LANDING, mode { landing = true }.state)
        assertEquals(PlayerState.WARP_TRAVEL, mode { travelling = true }.state)
        assertEquals(PlayerState.ABOARD, mode { onFoot = true }.state)
        assertEquals(PlayerState.ON_DECK, mode { branch = Branch.DECK }.state)
        assertEquals(PlayerState.DESTROYED, mode { branch = Branch.DESTROYED }.state)
        assertEquals(PlayerState.CRASHING, mode { crashing = true }.state)
        val m = mode { docked = true; overlay = Overlay.MAP; landed = true }
        m.reset()
        assertEquals(PlayerState.FLYING, m.state)
        assertEquals(Overlay.NONE, m.overlay)
    }

    @Test
    fun everyActionHasAnOrdinalBelowCount() {
        assertEquals(Action.COUNT, Action.values().size)
        for (i in 0 until Action.COUNT) assertEquals(i, Action.of(i).ordinal)
        assertEquals(Action.CYCLE_TARGET, Action.parse("cycle_target"))
        assertEquals(Action.LAND, Action.parse("L"))
        assertEquals(Action.EXTERNAL_VIEW, Action.parse("external-view"))
        assertEquals(null, Action.parse("warp drive"))
    }
}
