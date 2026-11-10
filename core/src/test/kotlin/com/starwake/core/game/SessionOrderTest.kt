package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.input.Action
import com.starwake.core.input.InputFrame
import com.starwake.core.math.DVec3
import com.starwake.core.world.WorldBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The frame order of 01-core-loop.md 2.2 / 9.2 as the session runs it: the flight frame calls its
 * steps in the documented order, the deck branch skips the flight, the destruction branch runs its
 * own steps, the frame a deck session ends returns early (9.4 SessionOrderTest; risk R1 of 10.3).
 */
class SessionOrderTest {
    private val system = WorldBuilder.sol()
    private val host = RecordingHost()
    private val settings = Settings()
    private val input = InputFrame()

    /**
     * A free-flight session in [world] (one world per flight: its save-game clock runs with the
     * session's frames, so two sessions flown side by side need a world each).
     */
    private fun session(world: StarSystem = system): Session = Session(world, settings, host, GearRig()).also { it.startInSpace() }

    private fun traced(s: Session, dt: Float = 1f / 60f): List<FrameStep> {
        val t = FrameTrace()
        s.trace = t
        input.begin()
        s.frame(dt, input)
        s.trace = null
        return t.toList()
    }

    private fun steps(vararg ids: String) = ids.map { FrameStep.valueOf(it) }

    /** The documented flight order (01 9.2, steps 1-12): the ids of the Roblox C-list the port keeps. */
    private val flightOrder = steps(
        "F0",
        "C1", "C2", "C3", "C4", "C5", "C6", "C7",
        "C8", "C9", "C10", "C11", "C12", "C13",
        "C14", "C15",
        "C16", "C19", "C20", "C21", "C22",
        "C23", "C24", "C25",
        "C26", "C27",
        "C28",
        "C30", "C31",
        "C34", "C35", "C36", "C37", "C38", "C39", "C40",
        "C41", "C43", "C44", "C45", "C46", "C47", "C48", "C52", "C53", "C54", "C56", "C58", "C59", "C60",
        "C62", "C63", "C64", "C65", "C66", "C67", "C68", "C69",
        "C70", "C71", "C72", "C73", "C74", "C75", "C76",
    )

    @Test
    fun theFlightFrameRunsTheDocumentedOrder() {
        val s = session()
        val order = traced(s)
        assertEquals(flightOrder, order)
        // and the order is the Roblox one: every step after the one before it
        for (i in 2 until order.size) assertTrue(order[i].ordinal > order[i - 1].ordinal, "${order[i]} after ${order[i - 1]}")
        assertEquals(Branch.FLIGHT, s.mode.branch)
    }

    @Test
    fun theOrderHoldsInEveryFlightState() {
        val s = session()
        // docked (controlled, services open as a non-pausing overlay), under the map, landing mode
        s.startDocked()
        s.mode.overlay = Overlay.SERVICES
        assertEquals(flightOrder, traced(s))
        s.mode.overlay = Overlay.MAP
        assertEquals(flightOrder, traced(s))
        s.startInSpace()
        s.debugTeleportLow()
        s.landKey()
        assertTrue(s.mode.landing)
        assertEquals(flightOrder, traced(s))
    }

    @Test
    fun theDeckBranchSkipsTheFlight() {
        val s = session()
        s.setThrottle(1f)
        s.decks.begin()
        val before = DVec3().set(s.ship.pos)
        val order = traced(s)
        assertEquals(steps("F0", "A2", "A3", "A4", "A5", "A6", "A7", "A9", "A10", "A11", "A12", "A13", "A14"), order)
        assertEquals(Branch.DECK, s.mode.branch)
        assertEquals(PlayerState.ON_DECK, s.mode.state)
        assertEquals(before.x, s.ship.pos.x, "the flight rests on a deck")
        assertEquals(before.z, s.ship.pos.z)
    }

    @Test
    fun theFrameADeckSessionEndsReturnsEarly() {
        val s = session()
        s.decks.begin()
        traced(s)
        s.decks.end()
        assertEquals(steps("F0", "A2", "A3", "A4"), traced(s), "the deck ended in A3: A4 returns")
        assertFalse(s.decks.isActive())
        // the next frame is a flight frame again
        assertEquals(flightOrder, traced(s))
    }

    @Test
    fun theDestructionBranchRunsUntilTheCinematicEnds() {
        val s = session()
        s.destruction.start(0.05f)
        assertEquals(steps("F0", "B1", "B2", "B3", "B4", "B6", "B9", "B10"), traced(s, 0.02f))
        assertEquals(Branch.DESTROYED, s.mode.branch)
        traced(s, 0.02f)
        traced(s, 0.02f) // 0.06 s: over
        assertEquals(flightOrder, traced(s))
    }

    @Test
    fun theDeckBranchWinsOverTheCinematic() {
        val s = session()
        s.decks.begin()
        s.destruction.start(1f)
        assertEquals(FrameStep.A2, traced(s)[1])
    }

    @Test
    fun tracingDoesNotChangeTheFrame() {
        // two sessions, one traced: identical flights (each in its own world: the orbits move)
        val a = session(WorldBuilder.sol())
        val b = session(WorldBuilder.sol())
        for (i in 0 until 120) {
            input.begin()
            a.frame(1f / 60f, input)
            traced(b)
        }
        assertEquals(a.ship.pos.x, b.ship.pos.x)
        assertEquals(a.ship.pos.y, b.ship.pos.y)
        assertEquals(a.ship.pos.z, b.ship.pos.z)
    }

    @Test
    fun mapsAndServicesDoNotStopTheFlight() {
        // the game never calls frame() under PAUSE/SETTINGS; under MAP and SERVICES it does, and the ship flies on
        val s = session()
        s.mode.overlay = Overlay.MAP
        // measured in the ship's reference frame (Earth's: the planet carries the ship along)
        val p0 = s.framePosition(DVec3())
        for (i in 0 until 180) {
            input.begin()
            s.frame(1f / 60f, input)
        }
        val p1 = s.framePosition(DVec3())
        val dx = p1.x - p0.x
        val dy = p1.y - p0.y
        val dz = p1.z - p0.z
        val travelled = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        assertTrue(travelled > 0.3 * s.handling.topSpeed * 2.5, "flew $travelled m in 3 s under the map")
    }

    @Test
    fun keyHandlersRunThroughTheGate() {
        val s = session()
        // FA, view and boost from the input frame
        input.begin()
        input.press(Action.TOGGLE_FLIGHT_ASSIST)
        input.press(Action.EXTERNAL_VIEW)
        s.frame(1f / 60f, input)
        assertFalse(s.thrust.flightAssist)
        assertEquals(ShipView.EXTERNAL, s.mode.view)
        // docked: the thrust keys and the lever do not reach the ship, K launches
        s.startDocked()
        input.begin()
        input.leverSet = true
        input.lever = 1f
        input.aimX = 1f
        s.frame(1f / 60f, input)
        assertEquals(0f, s.throttle, "the lever is gated while docked")
        assertTrue(s.mode.docked)
        input.begin()
        input.press(Action.DOCK)
        s.frame(1f / 60f, input)
        assertFalse(s.mode.docked, "K launches")
        assertTrue(host.servicesClosed > 0)
    }

    @Test
    fun steeringTakesTheShipBackFromTheDockingComputer() {
        val s = session()
        s.debugBehindStation(1, 1500.0, 0f)
        assertTrue(s.requestDocking(1))
        input.begin()
        s.frame(1f / 60f, input)
        assertTrue(s.mode.docking)
        assertTrue(s.mode.controlled)
        // a deflection under 0.5 does not count
        input.begin()
        input.aimX = 0.4f
        s.frame(1f / 60f, input)
        assertTrue(s.mode.docking)
        input.begin()
        input.aimX = 0.6f
        s.frame(1f / 60f, input)
        assertFalse(s.mode.docking, "Autopilot.OverrideDeflection 0.5")
    }

    @Test
    fun viewsCycleCockpitExternalAndChaseOnlyWhenEnabled() {
        val s = session()
        assertEquals(ShipView.COCKPIT, s.mode.view, "the cockpit is the default view")
        s.toggleView()
        assertEquals(ShipView.EXTERNAL, s.mode.view)
        s.toggleView()
        assertEquals(ShipView.COCKPIT, s.mode.view, "no chase view by default")
        settings.chaseView = true
        s.toggleView()
        s.toggleView()
        assertEquals(ShipView.CHASE, s.mode.view)
        s.toggleView()
        assertEquals(ShipView.COCKPIT, s.mode.view)
        // switching the setting off leaves the chase view at the next frame
        s.setView(ShipView.CHASE)
        settings.chaseView = false
        input.begin()
        s.frame(1f / 60f, input)
        assertEquals(ShipView.COCKPIT, s.mode.view)
    }

    @Test
    fun fovFollowsTheSetting() {
        val s = session()
        settings.fovDeg = 70f
        input.begin()
        s.frame(1f / 60f, input)
        assertEquals(70f, s.fovY / com.starwake.core.math.DEG2RAD, 1e-3f)
        s.setView(ShipView.EXTERNAL)
        settings.fovDeg = 95f
        input.begin()
        s.frame(1f / 60f, input)
        assertEquals(95f, s.fovY / com.starwake.core.math.DEG2RAD, 1e-3f)
    }

    @Test
    fun cycleTargetLocksTheAimedThenStepsOutwardAndClearRemoves() {
        val s = session()
        s.cycleTarget()
        val first = s.lockKind to s.lockIndex
        assertTrue(s.lockKind != Session.LOCK_NONE)
        val d1 = s.targetDistance(s.lockKind, s.lockIndex)
        s.cycleTarget()
        assertTrue(first != (s.lockKind to s.lockIndex), "the next target")
        assertTrue(s.targetDistance(s.lockKind, s.lockIndex) >= d1)
        // cycling through everything comes back round
        val seen = HashSet<Pair<Int, Int>>()
        repeat(system.stations.size + system.bodies.size + 2) {
            s.cycleTarget()
            seen += s.lockKind to s.lockIndex
        }
        assertEquals(system.stations.size + system.bodies.size, seen.size, "every station and body in turn")
        s.clearTarget()
        assertEquals(Session.LOCK_NONE, s.lockKind)
    }

    @Test
    fun theWelcomeLinesFollowTheReveal() {
        val s = session()
        s.afterReveal(welcome = true)
        var shown: String? = null
        var t = 0f
        while (t < 5.5f) {
            input.begin()
            s.frame(1f / 60f, input)
            if (shown == null && s.message != null) shown = s.message
            t += 1f / 60f
        }
        assertEquals(Voice.WELCOME, shown, "5 s after the reveal")
        // the second line follows once the first has been read
        while (t < 9f) {
            input.begin()
            s.frame(1f / 60f, input)
            t += 1f / 60f
        }
        assertEquals(Voice.SYSTEMS_ONLINE, s.message)
    }

    /** Runs [seconds] of 60 Hz frames; returns the first message that appears in them (not the one already showing), or null. */
    private fun fly(s: Session, seconds: Float): String? {
        val before = s.message
        var seen: String? = null
        var t = 0f
        while (t < seconds) {
            input.begin()
            s.frame(1f / 60f, input)
            val m = s.message
            if (seen == null && m != null && m != before) seen = m
            t += 1f / 60f
        }
        return seen
    }

    @Test
    fun theWelcomeLinesShowOverTheServicesOfADockedStart() {
        // D13 + D31: every start is docked with the services open; the lines are text and show
        // over the services (the game draws the message line there), 5 s after the reveal
        val s = session()
        s.startDocked()
        s.mode.overlay = Overlay.SERVICES
        assertTrue(s.messageShown())
        s.afterReveal(welcome = true)
        assertEquals(null, fly(s, 4.9f), "nothing before 5 s")
        assertEquals(Voice.WELCOME, fly(s, 0.2f))
        assertEquals(Voice.SYSTEMS_ONLINE, fly(s, 3.2f))
        assertTrue(s.mode.docked)
        assertEquals(0, s.voice.pending)
    }

    @Test
    fun voiceLinesWaitWhileTheMessageLineIsHidden() {
        // under a map (it covers the screen) and under the curtain the queue keeps its lines
        val s = session()
        s.afterReveal(welcome = true)
        s.mode.overlay = Overlay.MAP
        assertFalse(s.messageShown())
        assertEquals(null, fly(s, 12f), "no line is used up under the map")
        assertEquals(2, s.voice.pending)
        s.mode.overlay = Overlay.NONE
        s.mode.curtain = true
        assertFalse(s.messageShown())
        assertEquals(null, fly(s, 1f), "nor under the curtain")
        s.mode.curtain = false
        // the map closes: the lines follow in order
        assertEquals(Voice.WELCOME, fly(s, 1f / 60f))
        assertEquals(Voice.SYSTEMS_ONLINE, fly(s, 3.2f))
        assertEquals(0, s.voice.pending)
    }
}
