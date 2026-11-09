package com.starwake.core.game

import com.starwake.core.Haptics
import com.starwake.core.Settings
import com.starwake.core.content.ShipCatalog
import com.starwake.core.content.ShipModel
import com.starwake.core.content.StationMesh
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.input.Action
import com.starwake.core.input.InputFrame
import com.starwake.core.input.InputGate
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.expApproach
import com.starwake.core.math.expoCurve
import com.starwake.core.ui.UiHost
import com.starwake.core.world.RingDebris
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** What a [Session] needs from the game shell. */
interface SessionHost : UiHost {
    /** The station services of [station] open (docked, or the reveal of a docked start). Non-pausing overlay. */
    fun openServices(station: Int)

    /** The services close (launch). */
    fun closeServices()

    /** Lets go of every flight touch (touchdown: a thumb still on the lever must not lift off). */
    fun releaseControls()
}

/**
 * One flight: every world module and the per-frame order of Roblox's ShipClient render step
 * (`ShipClient.lua:2321-3196`, 01-core-loop.md 2.2 and 9.2). [frame] takes exactly one of three
 * branches: the deck frame (A, `09`), the destruction frame (B, `05`) or the flight frame (C). The
 * flight frame runs the steps of [FrameStep] in order; a step whose module is not ported yet is an
 * empty slot (marked in the trace) where that area's port plugs in.
 *
 * The flight itself is still the pre-port model ([FlightModel], [StarSystem.locate], the Kotlin
 * landing mode and auto-dock); it sits in its Roblox slots (C13 rotation input, C16 gravity state,
 * C19-C21 limit, C23 the sub-stepped move with its collisions, C28 ground, C30 docking) until the
 * flight and station ports replace it. The world runs in its slots: C14 moves the bodies on their
 * orbits, C15 carries the ship with its reference frame (03-world.md 2.9.4, DECISIONS 6).
 *
 * Also here: the key handlers (Roblox `InputBegan` glue: view, Flight Assist, boost, target, land,
 * dock, autopilot) and the frame-local state of 2.1.9. GL thread; the frame performs no allocation.
 */
class Session(
    val system: StarSystem,
    private val settings: Settings,
    private val host: SessionHost,
    /** The shown ship's landing-gear rig (shared with the menu backdrop, which keeps it stowed). */
    val gearRig: GearRig,
) {
    val mode = PlayerMode()
    val ship = ShipState()
    var spec = ShipSpec.STANDARD
        private set
    private var entry: ShipCatalog.Entry = ShipCatalog.all[ShipCatalog.DEFAULT]
    private var model: ShipModel? = null
    private val flight = FlightModel(spec)
    private val controls = ControlInput()
    val here = StarSystem.Locality()

    /** Rocks in the ring plane around the ship (C65, 03-world.md 2.12). */
    val debris = RingDebris()

    /**
     * Heat brought by ring-debris contacts (`systems:addHeat`). The heat system comes with the
     * flight port (02); until then it only adds up here (debug read-out `rheat`).
     */
    var debrisHeat = 0.0
        private set

    /** Ring-debris contacts so far, and the cooldown of the hit feedback (`Rings.HitCooldownSeconds`). */
    var debrisHits = 0
        private set
    private var debrisHitCooldown = 0f

    /** The Roblox cockpit and external views. */
    val camera = ShipCamera()

    /** The optional lagging chase view (DECISIONS 21a). */
    val chase = ChaseCamera()
    private val impact = ImpactShake()
    private val stationCollision = StationCollision(flight)

    val decks = DeckSession()
    val destruction = Destruction()
    val voice = Voice()

    /** Set to record the next frame's steps (debug "trace", the frame-order test); null = off. */
    var trace: FrameTrace? = null

    /** The throttle lever, -1..1 (Roblox `thrust.throttle`; the touch lever and the pad set it). */
    var throttle = 0f
        private set

    /** Session clock (s): runs with the simulation, stops while paused. */
    var time = 0f
        private set
    var flightTime = 0f
        private set

    // ---- frame-local state (2.1.9)
    private var steering = false
    private var wasTravelling = false
    private var lastFlightAssist = true
    private var welcomeIn = -1f

    /** Closest gap (m) between the hull and a station this frame (PROXIMITY warning). */
    var proximity = Float.MAX_VALUE
        private set
    private var bumpCooldown = 0f

    // ---- the world's reference frame (03 2.9.4): the station or body the ship is carried by
    /** Encoded frame ([StarSystem.frameFor]); [StarSystem.FRAME_NONE] in the star's frame. */
    var refFrame = StarSystem.FRAME_NONE
        private set
    private val carry = DVec3()
    private val frameOrigin = DVec3()

    // ---- targets
    var lockKind = LOCK_NONE
        private set
    var lockIndex = -1
        private set
    var aimKind = LOCK_NONE
        private set
    var aimIndex = -1
        private set

    // ---- landing mode (L) and ground
    var landedBody = -1
        private set

    /** Seconds of assisted climb left after a take-off (Flight Assist would otherwise stop it). */
    private var liftTime = 0f

    /** Gear position forced by debug commands (null = follow the landing mode), like ShipHull.gearForce. */
    var gearForce: Boolean? = null

    // ---- docking (K): the pre-port auto-dock into the slot
    var dockStation = -1
        private set
    private var dockPhase = 0 // 0 approach point, 1 slide into the slot
    private val dockPos = DVec3()
    private val dockDir = Vec3()
    private val dockWaypoint = DVec3()
    private val hullPos = DVec3()
    private var dockBoxedTime = 0f
    private var launchTime = -100f

    // ---- messages (the pre-port notice line; `06` Notices replace it)
    var message: String? = null
        private set
    private var messageTime = 0f

    // ---- HUD state (C67)
    /** Caption of the contextual ACTION control, "" when hidden. */
    var actionLabel = ""
        private set

    /** What the ACTION control sends: [Action.LAND] or [Action.DOCK], null when hidden. */
    var actionTarget: Action? = null
        private set

    /** Zone caption under the top bar (StarSystem zone; `02` brings ShipGravity.zoneLabel). */
    var zoneLabel = ""
        private set

    // ---- render state, written by the frame and read by the game's renderer
    val cameraPos = DVec3()
    val cameraRot = Quat()
    var fovY = 80f * DEG2RAD
        private set
    var shipReach = 0f
        private set
    private val dustOrigin = DVec3()
    private val dustVelocity = Vec3()
    private val dustColor = Vec3(0.66f, 0.72f, 0.80f)

    // ---- debug holds (Roblox ShipDebug "stick", "look")
    private var stickHold = false
    private var stickHoldX = 0f
    private var stickHoldY = 0f

    // scratch
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val upV = Vec3()
    private val fwdV = Vec3()
    private val landRot = Quat()

    // ================================================================== set-up

    /**
     * Flies ship [e] with [newSpec] (and its model's eye, when loaded): the flight model's spec, the
     * Roblox external camera of the ship and the chase framing. Not per frame.
     */
    fun setShip(e: ShipCatalog.Entry, newSpec: ShipSpec, m: ShipModel?) {
        entry = e
        spec = newSpec
        model = m
        flight.spec = newSpec
        camera.setExternalCamera(e.cameraX, e.cameraY, e.cameraZ, e.cameraPitchDeg)
        if (m != null) camera.seat.set(m.eyeX, m.eyeY, m.eyeZ) else camera.seat.set(0f, 0.5f, 0f)
        // the chase view takes the Roblox external camera's place and tilt, circling the hull's centre
        tmp.set(newSpec.centreX, newSpec.centreY, newSpec.centreZ)
        tmp2.set(e.cameraX - tmp.x, e.cameraY - tmp.y, e.cameraZ - tmp.z)
        chase.configure(tmp, tmp2, e.cameraPitchDeg * DEG2RAD)
    }

    private fun resetFlight() {
        mode.reset()
        ship.reset()
        controls.clearAxes()
        throttle = 0f
        flightTime = 0f
        launchTime = -100f
        lockKind = LOCK_NONE
        lockIndex = -1
        liftTime = 0f
        dockStation = -1
        message = null
        proximity = Float.MAX_VALUE
        steering = false
        wasTravelling = false
        lastFlightAssist = true
        stickHold = false
        camera.lookHold = false
        camera.resetLook()
        impact.reset()
        voice.interrupt()
        welcomeIn = -1f
        setView(ShipView.COCKPIT)
    }

    /**
     * The start rule of D13: docked at MERIDIAN (in its slot, services after the reveal); saves
     * (D23) arrive with `08`.
     */
    fun startDocked(station: Int = SpawnRules.startStation(system)) {
        resetFlight()
        val st = system.stations[station]
        SpawnRules.dockedPose(st, spec, ship.pos, ship.rot)
        mode.docked = true
        dockStation = station
        afterPlacement()
    }

    /**
     * Free flight [StarSystem.SPAWN_DISTANCE] out of the home station's mouth, nose away from it
     * (towards Earth from MERIDIAN), moving at 30 % throttle (harness scripts that test flying
     * use it through "launch space").
     */
    fun startInSpace() {
        resetFlight()
        system.spaceSpawn(SpawnRules.startStation(system), ship.pos, ship.rot)
        throttle = 0.3f
        controls.throttle = 0.3f
        ship.rot.rotate(0f, 0f, -0.3f * spec.maxSpeed, ship.vel)
        afterPlacement()
    }

    private fun afterPlacement() {
        dustOrigin.set(ship.pos)
        chase.snapTo(ship)
        gearRig.update(0f, gearWanted(), true)
        refFrame = if (mode.docked && dockStation >= 0) StarSystem.stationFrame(dockStation) else system.frameFor(ship.pos, StarSystem.FRAME_NONE)
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
        zoneLabel = here.zone.label
        updateCamera(0f)
    }

    /**
     * Roblox `afterReveal` (01 2.1.15), at the start of the curtain's reveal: the cockpit comes
     * alive, a docked pilot sees the services, and once per app run the welcome lines follow
     * [Voice.WELCOME_DELAY] later. The cockpit boot (`06`) plugs in here.
     */
    fun afterReveal(welcome: Boolean) {
        mode.revealed = true
        if (mode.docked && dockStation >= 0) host.openServices(dockStation)
        if (welcome) welcomeIn = Voice.WELCOME_DELAY
    }

    // ================================================================== the frame

    private fun mark(s: FrameStep) {
        trace?.add(s)
    }

    /**
     * One frame (Roblox `ShipUpdate`): the deck branch, the destruction branch or the flight frame.
     * [input] is this frame's pilot input; the game does not call this while a pausing overlay is up.
     */
    fun frame(dtIn: Float, input: InputFrame) {
        trace?.clear()
        mark(FrameStep.F0)
        val dt = if (dtIn > MAX_DT) MAX_DT else dtIn
        time += dt
        when {
            decks.isActive() -> {
                mode.branch = Branch.DECK
                deckFrame(dt, input)
            }
            destruction.isActive() -> {
                mode.branch = Branch.DESTROYED
                destructionFrame(dt)
            }
            else -> {
                mode.branch = Branch.FLIGHT
                flightFrame(dt, input)
            }
        }
    }

    /** Branch A (`ShipClient.lua:2327-2334`, `deckFrame`): the flight rests while on a station deck. */
    private fun deckFrame(dt: Float, input: InputFrame) {
        mark(FrameStep.A2) // 02: hud.attitude:hideNow()
        mark(FrameStep.A3)
        decks.update(dt)
        mark(FrameStep.A4)
        if (!decks.isActive()) return // the frame the deck session ends returns early (4.9 #1)
        mark(FrameStep.A5) // 09: parked ship pose from the deck, walking flag
        // 09: walking: hull:update when the parked pose changed, aboard:watchRamp in the HANGAR. In
        // Roblox the walking step and the seated steps A7-A13 are the two arms of one if; the port has
        // no walking yet, so the seated arm always follows (09 makes the choice here)
        mark(FrameStep.A6)
        mark(FrameStep.A7)
        InputGate.filterActions(input, mode)
        camera.addLook(input.lookX, input.lookY)
        camera.updateLook(dt, input.lookHeld)
        mark(FrameStep.A9) // 06: parked cockpit state
        mark(FrameStep.A10)
        gearRig.update(dt, true, false)
        mark(FrameStep.A11)
        updateCamera(dt)
        mark(FrameStep.A12) // 05: entry glow fades
        mark(FrameStep.A13) // input owner: the game releases the flight controls
        mark(FrameStep.A14)
        updateMessages()
    }

    /** Branch B (`ShipClient.lua:2337-2342`, `destructionFrame`): the crash cinematic. */
    private fun destructionFrame(dt: Float) {
        mark(FrameStep.B1) // 02: attitude hidden
        mark(FrameStep.B2)
        destruction.update(dt)
        mark(FrameStep.B3) // the flight UI stays hidden (the HUD reads the branch)
        mark(FrameStep.B4) // 09: traffic leaves
        mark(FrameStep.B6) // 05: entry glow fades
        mark(FrameStep.B9) // 05: warnings run the blackout timeline
        mark(FrameStep.B10)
        updateMessages()
    }

    /** Branch C: the flight frame in Roblox order (`ShipClient.lua:2344-3195`). */
    private fun flightFrame(dt: Float, input: InputFrame) {
        // ---- C1 input: look-around, the key handlers (Roblox InputBegan runs before the render step)
        mark(FrameStep.C1)
        flightTime += dt
        if (stickHold) {
            input.aimX = stickHoldX
            input.aimY = stickHoldY
        }
        // the pilot's raw deflection decides "steering" (C7) before the gate zeroes it
        val rawAim = input.aimMagnitude
        val rawKeys = input.keyAxis
        InputGate.filterActions(input, mode)
        handleActions(input)
        val lookOk = mode.view != ShipView.CHASE && InputGate.allows(Action.LOOK, mode)
        if (lookOk) camera.addLook(input.lookX, input.lookY)
        mode.looking = lookOk && input.lookHeld
        camera.updateLook(dt, mode.looking)

        // ---- C2-C7 flags and the gated pilot input
        mark(FrameStep.C2)
        mode.travelling = false // 07: warp:isTravelling()
        mark(FrameStep.C3)
        mode.controlled = mode.docking || mode.docked || mode.launching // 05: or a guidance phase
        mark(FrameStep.C4)
        val thrustInput = InputGate.thrustEnabled(mode)
        mark(FrameStep.C5)
        if (mode.controlled || mode.onFoot) controls.boost = false
        mark(FrameStep.C6)
        if (mode.travelling != wasTravelling) wasTravelling = mode.travelling // a touch stick recentres by itself
        mark(FrameStep.C7)
        steering = !mode.onFoot && (rawAim > OVERRIDE_DEFLECTION || abs(rawKeys) > OVERRIDE_DEFLECTION)
        InputGate.gateAxes(input, mode)

        // ---- C8-C13 handling, autopilot, rotation
        mark(FrameStep.C8) // 02: handling:update
        mark(FrameStep.C9) // 02: autopilot disengages in an atmosphere
        mark(FrameStep.C10) // 02: autopilot disengages while controlled
        mark(FrameStep.C11) // 02: autopilot computes aim, roll and throttle
        mark(FrameStep.C12) // 09: nobody at the helm -> throttle 0, FA on
        mark(FrameStep.C13)
        rotationInput(input)

        // ---- C14-C22 world, gravity state and the speed limit
        mark(FrameStep.C14)
        system.advance(dt.toDouble()) // orbits:update on the save-game clock
        mark(FrameStep.C15)
        carryWithFrame()
        mark(FrameStep.C16)
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here) // the previous frame's velocity, as Roblox
        mark(FrameStep.C19)
        val limit = if (mode.landing) minOf(here.speedLimit, LANDING_SPEED) else here.speedLimit
        mark(FrameStep.C20)
        mark(FrameStep.C21)
        mark(FrameStep.C22) // 02: thrust:setGravity

        // ---- C23-C25 thrust and move: the pre-port model, sub-stepped with its collisions
        mark(FrameStep.C23)
        thrustInput(input, thrustInput)
        move(dt, limit)
        mark(FrameStep.C24) // 02: boost heat
        mark(FrameStep.C25) // 02: field-edge clamp

        // ---- C26-C31 assist, systems, ground, stations
        mark(FrameStep.C26) // 05: landing assist (the pre-port landing mode caps the limit in C19)
        mark(FrameStep.C27) // 02: systems
        mark(FrameStep.C28)
        if (mode.landed) holdLanded(dt, input)
        mark(FrameStep.C30)
        if (mode.docking) {
            // the pilot's deflection takes the ship back from the docking computer
            if (steering) {
                mode.docking = false
                showMessage("DOCKING CANCELLED")
            } else {
                updateDocking(dt)
            }
        }
        mark(FrameStep.C31) // 09: traffic

        // ---- C34-C40 derived state, cockpit, hull, camera
        mark(FrameStep.C34)
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
        zoneLabel = here.zone.label
        mark(FrameStep.C35) // 02: flight cursor hidden while looking, docked or on foot
        mark(FrameStep.C36) // 06: cockpit state
        mark(FrameStep.C37)
        mark(FrameStep.C38)
        gearRig.update(dt, gearWanted(), false)
        mark(FrameStep.C39)
        impact.update(dt, settings.cameraShake, camera.shake)
        mark(FrameStep.C40)
        updateCamera(dt)

        // ---- C41-C69 effects, warnings, markers, maps, HUD
        mark(FrameStep.C41) // 05: entry effects
        mark(FrameStep.C43) // 09: audio
        mark(FrameStep.C44)
        if (ship.flightAssist != lastFlightAssist) lastFlightAssist = ship.flightAssist // 09: FA voice
        mark(FrameStep.C45)
        updateProximity()
        mark(FrameStep.C46) // 09: callouts
        mark(FrameStep.C47)
        validateLock()
        mark(FrameStep.C48)
        updateDust(dt)
        mark(FrameStep.C52) // input owner: the game releases the touch controls when an overlay opens
        mark(FrameStep.C53) // 08: services dim while looking
        mark(FrameStep.C54) // 07: system map refresh
        mark(FrameStep.C56) // 03: texture streaming
        mark(FrameStep.C58) // 07: star map
        mark(FrameStep.C59) // 07: warp cancelled in an atmosphere
        mark(FrameStep.C60) // 07: warp
        mark(FrameStep.C62)
        updateLandingMode()
        mark(FrameStep.C63) // 05: arrival card
        mark(FrameStep.C64)
        updateVoice(dt)
        mark(FrameStep.C65) // 03: ring debris
        updateDebris(dt)
        mark(FrameStep.C66)
        updateDustMood(dt)
        mark(FrameStep.C67)
        updateHudState()
        mark(FrameStep.C68) // 02: flight arrow
        mark(FrameStep.C69) // 02: attitude
        // ---- C70-C76 survey, mining, construction, galaxy, notices, services, autosave
        mark(FrameStep.C70) // 08
        mark(FrameStep.C71) // 08
        mark(FrameStep.C72) // 08
        mark(FrameStep.C73) // 08
        mark(FrameStep.C74)
        updateMessages()
        mark(FrameStep.C75) // 08
        mark(FrameStep.C76) // 08: autosave every 10 s in free flight
    }

    // ================================================================== key handlers (C1)

    private fun handleActions(f: InputFrame) {
        if (f.consume(Action.TOGGLE_FLIGHT_ASSIST)) toggleFlightAssist()
        if (f.consume(Action.BOOST)) controls.boost = true
        if (f.consume(Action.EXTERNAL_VIEW)) toggleView()
        if (f.consume(Action.CLEAR_TARGET)) clearTarget()
        if (f.consume(Action.CYCLE_TARGET)) cycleTarget()
        if (f.consume(Action.LAND)) landKey()
        if (f.consume(Action.DOCK)) dockButton()
        if (f.consume(Action.AUTOPILOT)) autopilotButton()
    }

    fun toggleFlightAssist() {
        ship.flightAssist = !ship.flightAssist
        host.haptic(Haptics.TICK)
    }

    /** A boost request (Roblox `thrust.boostRequested`), used by the flight step. */
    fun requestBoost() {
        controls.boost = true
    }

    /**
     * VIEW (Roblox V, `toggleExternalView`): cockpit ↔ external, with the chase view as a third step
     * when the setting allows it (DECISIONS 21a).
     */
    fun toggleView() {
        setView(
            when (mode.view) {
                ShipView.COCKPIT -> ShipView.EXTERNAL
                ShipView.EXTERNAL -> if (settings.chaseView) ShipView.CHASE else ShipView.COCKPIT
                ShipView.CHASE -> ShipView.COCKPIT
            },
        )
    }

    fun setView(v: ShipView) {
        val view = if (v == ShipView.CHASE && !settings.chaseView) ShipView.COCKPIT else v
        mode.view = view
        // Roblox: look limits per view, and the look clamps at once
        camera.setExternal(view == ShipView.EXTERNAL)
        if (view == ShipView.CHASE) {
            camera.resetLook()
            chase.snapTo(ship)
        }
    }

    /** Autopilot / auto-dock (Roblox P, `autopilotButton`): the autopilot arrives with `02`. */
    fun autopilotButton() {
        // 02: docking:handleComputerKey, then toggleAutopilot (needs a lock, SPACE, not travelling)
    }

    /** L (Roblox `landKey`): the pre-port landing mode: take off, cancel, or engage it near a solid body. */
    fun landKey() {
        when {
            mode.docking || mode.docked -> host.haptic(Haptics.BUMP) // ignored while the docking computer flies
            mode.landed -> takeOff()
            mode.landing -> {
                mode.landing = false
                showMessage("LANDING CANCELLED")
            }
            here.zone == StarSystem.Zone.LOW_ALTITUDE && landable(here.nearestBody) -> {
                mode.landing = true
                showMessage("LANDING MODE  ·  GEAR DOWN")
            }
            here.zone == StarSystem.Zone.LOW_ALTITUDE && here.nearestBody >= 0 && system.bodies[here.nearestBody].landingBlocked != null -> {
                host.haptic(Haptics.BUMP)
                showMessage(landingBlockedText(system.bodies[here.nearestBody]))
            }
            else -> host.haptic(Haptics.BUMP)
        }
    }

    /** K (Roblox `dockButton`): request docking in a station zone, cancel the auto-dock, or launch when docked. */
    fun dockButton() {
        when {
            mode.docked -> launchFromStation()
            mode.docking -> {
                mode.docking = false
                showMessage("DOCKING CANCELLED")
            }
            here.zone == StarSystem.Zone.STATION_ZONE -> requestDocking(here.nearestStation)
            else -> host.haptic(Haptics.BUMP)
        }
    }

    /** The contextual ACTION (touch pill, pad D-pad down, debug "action"): what its label shows. */
    fun contextAction() {
        when (actionTarget) {
            Action.LAND -> landKey()
            Action.DOCK -> dockButton()
            else -> host.haptic(Haptics.BUMP)
        }
    }

    // ================================================================== targets (T / Y)

    /**
     * T (Roblox `markers:cycleTarget`): nothing locked → the target nearest the nose (a phone aid),
     * else the nearest one; locked → the next one farther away, round to the nearest again.
     */
    fun cycleTarget() {
        if (lockKind == LOCK_NONE) {
            findAimed(LOCK_CONE_COS, LOCK_CONE_SIN)
            if (aimKind != LOCK_NONE) {
                lock(aimKind, aimIndex)
                return
            }
        }
        // next by distance among stations and bodies (no sort: the smallest distance above the current)
        val current = if (lockKind == LOCK_STATION || lockKind == LOCK_BODY) targetDistance(lockKind, lockIndex) else -1.0
        var bestKind = LOCK_NONE
        var bestIndex = -1
        var bestD = Double.MAX_VALUE
        var firstKind = LOCK_NONE
        var firstIndex = -1
        var firstD = Double.MAX_VALUE
        for (k in 0..1) {
            val kind = if (k == 0) LOCK_STATION else LOCK_BODY
            val n = if (k == 0) system.stations.size else system.bodies.size
            for (i in 0 until n) {
                if (kind == lockKind && i == lockIndex) continue
                val d = targetDistance(kind, i)
                if (d < firstD) {
                    firstD = d; firstKind = kind; firstIndex = i
                }
                if (d >= current && d < bestD) {
                    bestD = d; bestKind = kind; bestIndex = i
                }
            }
        }
        if (bestKind == LOCK_NONE) {
            bestKind = firstKind
            bestIndex = firstIndex
        }
        if (bestKind != LOCK_NONE) lock(bestKind, bestIndex) else host.haptic(Haptics.BUMP)
    }

    /** Y (Roblox `markers:setLocked(nil)`). */
    fun clearTarget() {
        if (lockKind != LOCK_NONE) host.haptic(Haptics.TICK)
        lockKind = LOCK_NONE
        lockIndex = -1
    }

    /** Locks a target: kind [LOCK_STATION] or [LOCK_BODY]. */
    fun lock(kind: Int, index: Int) {
        lockKind = kind
        lockIndex = index
        host.haptic(Haptics.TICK)
    }

    /** C47: a lock on something that no longer exists is dropped (every Sol target always exists). */
    private fun validateLock() {
        val n = when (lockKind) {
            LOCK_STATION -> system.stations.size
            LOCK_BODY -> system.bodies.size
            else -> return
        }
        if (lockIndex !in 0 until n) {
            lockKind = LOCK_NONE
            lockIndex = -1
        }
    }

    /**
     * Finds the target closest to the nose: stations and bodies. Writes [aimKind]/[aimIndex]; the
     * score is the angle off the nose minus the target's angular radius, so big bodies are easy to
     * pick.
     */
    fun findAimed(maxAngleCos: Float, maxAngleSin: Float) {
        aimKind = LOCK_NONE
        aimIndex = -1
        ship.rot.rotate(0f, 0f, -1f, tmp)
        var bestScore = Float.MAX_VALUE
        for (i in system.stations.indices) {
            val st = system.stations[i]
            val score = aimScore(st.pos.x - ship.pos.x, st.pos.y - ship.pos.y, st.pos.z - ship.pos.z, st.radius, maxAngleCos, maxAngleSin)
            if (score < bestScore) {
                bestScore = score; aimKind = LOCK_STATION; aimIndex = i
            }
        }
        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            val score = aimScore(b.pos.x - ship.pos.x, b.pos.y - ship.pos.y, b.pos.z - ship.pos.z, b.radius, maxAngleCos, maxAngleSin)
            if (score < bestScore) {
                bestScore = score; aimKind = LOCK_BODY; aimIndex = i
            }
        }
    }

    /** Angle (radians) between the nose ([tmp]) and the target's edge; MAX_VALUE if not eligible. */
    private fun aimScore(dx: Double, dy: Double, dz: Double, radius: Double, maxAngleCos: Float, maxAngleSin: Float): Float {
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1.0) return Float.MAX_VALUE
        val cos = ((dx * tmp.x + dy * tmp.y + dz * tmp.z) / d).toFloat()
        // exact trig-free test for "angle - angular radius > cone": cos(angle) < cos(cone + angRadius)
        // (valid because cone + angRadius < 180°)
        val sr = minOf(1.0, radius / d).toFloat()
        if (cos < maxAngleCos * sqrt(1f - sr * sr) - maxAngleSin * sr) return Float.MAX_VALUE
        val angle = kotlin.math.acos(clamp(cos, -1f, 1f))
        val angRadius = kotlin.math.asin(minOf(1.0, radius / d)).toFloat()
        val off = maxOf(0f, angle - angRadius)
        if (kotlin.math.cos(off) < maxAngleCos) return Float.MAX_VALUE
        // tiny distance tie-breaker so the nearer of two overlapping targets wins
        return off + (d * 1e-12).toFloat()
    }

    /** Target centre relative to [origin] into [out] (camera-relative when origin = camera). */
    fun targetRel(kind: Int, i: Int, origin: DVec3, out: Vec3) {
        when (kind) {
            LOCK_STATION -> system.stations[i].pos.relativeTo(origin, out)
            else -> system.bodies[i].pos.relativeTo(origin, out)
        }
    }

    fun targetRadius(kind: Int, i: Int): Double = when (kind) {
        LOCK_STATION -> system.stations[i].radius
        else -> system.bodies[i].radius
    }

    /** Distance from the ship to the target's surface, in metres (double precision). */
    fun targetDistance(kind: Int, i: Int): Double {
        val p = if (kind == LOCK_STATION) system.stations[i].pos else system.bodies[i].pos
        val dx = p.x - ship.pos.x
        val dy = p.y - ship.pos.y
        val dz = p.z - ship.pos.z
        return maxOf(0.0, sqrt(dx * dx + dy * dy + dz * dz) - targetRadius(kind, i))
    }

    /** The upper-case name a marker shows. */
    fun targetName(kind: Int, i: Int): String = if (kind == LOCK_STATION) system.stations[i].name else system.bodies[i].displayName

    // ================================================================== flight (pre-port model)

    /** C13: the pilot's cursor and key axis as the pre-port flight model's rate commands. */
    private fun rotationInput(f: InputFrame) {
        // the pre-port stick feel: a small radial dead zone and some expo near the centre
        // (02 replaces it with Roblox's applyDeadzone(0.20, 1.7) inside the rotation)
        val mag = f.aimMagnitude
        var sx = 0f
        var sy = 0f
        if (mag >= STICK_DEAD) {
            val k = ((mag - STICK_DEAD) / (1f - STICK_DEAD)) / mag
            sx = clamp(expoCurve(f.aimX * k, STICK_EXPO), -1f, 1f)
            sy = clamp(expoCurve(f.aimY * k, STICK_EXPO), -1f, 1f)
        }
        val c = controls
        c.pitch = -sy // aim Y is down; +pitch raises the nose
        val keys = -f.keyAxis // +1 = right
        if (settings.stickXRoll) {
            c.roll = sx
            c.yaw = keys
        } else {
            c.yaw = sx
            c.roll = keys
        }
    }

    /**
     * C15: the reference frame's carry (03-world.md 2.9.4, `ShipStation:getCarry` generalised to
     * bodies, DECISIONS 6). The ship moves with the displacement its frame made in C14 (a pure
     * translation: the velocity stays relative to the frame, so nothing jumps), then the frame is
     * chosen again for the next frame: the docking station while docking or docked, the landed
     * body, else [StarSystem.frameFor] (stations first, then the innermost body, with hysteresis).
     */
    private fun carryWithFrame() {
        system.frameDelta(refFrame, carry)
        ship.pos.x += carry.x
        ship.pos.y += carry.y
        ship.pos.z += carry.z
        refFrame = when {
            (mode.docking || mode.docked) && dockStation >= 0 -> StarSystem.stationFrame(dockStation)
            mode.landed && landedBody >= 0 -> StarSystem.bodyFrame(landedBody)
            else -> system.frameFor(ship.pos, refFrame)
        }
    }

    /** C23 input half: lever, full stop, lateral thrust (Roblox ShipThrust.readInput). */
    private fun thrustInput(f: InputFrame, enabled: Boolean) {
        val c = controls
        if (enabled) {
            if (f.leverSet) throttle = clamp(f.lever, -1f, 1f)
            if (f.held(Action.FULL_STOP)) throttle = 0f
            c.strafeX = f.lateralX
            c.strafeY = f.lateralY
        } else {
            c.strafeX = 0f
            c.strafeY = 0f
        }
        c.throttle = throttle
        // landing mode: the vertical thrusters give a gentle, safe descent rate
        if (mode.landing && !mode.landed) c.strafeY *= LANDING_VERTICAL
        if (liftTime > 0f) c.strafeY = maxOf(c.strafeY, 1f)
    }

    /** C23-C25 and the collisions: the pre-port flight step in sub-steps of at most 1/120 s. */
    private fun move(dt: Float, limit: Float) {
        if (liftTime > 0f) liftTime -= dt
        val steps = clamp(ceil(dt / MAX_STEP), 1f, 8f).toInt()
        val h = dt / steps
        for (i in 0 until steps) {
            if (mode.landed || mode.docking || mode.docked) break
            if (flight.step(ship, controls, h, limit)) {
                host.haptic(Haptics.HEAVY)
                addShake(0.8f)
            }
            controls.boost = false
            bumpCooldown -= h
            // collide every substep so fast ships can't tunnel through a station's hull
            collideBodies(h)
            collideStation()
        }
        // a press while landed, docking or as an overlay opens is dropped, not latched
        controls.boost = false
    }

    private fun addShake(a: Float) {
        // the cockpit and external cameras scale it by CAMERA SHAKE when they apply it (C39)
        impact.add(a)
        chase.addShake(a * settings.cameraShake)
    }

    /**
     * C65: the ring debris around the ship (`ringDebris:update(dt, shipPos, thrust.velocity)`). The
     * velocity is relative to the ship's frame, which near a ring is its planet's. A contact plays
     * the hit (Roblox `audio:playHit`, at most once a second: until the audio port a bump and a
     * shake as strong as the hit sound would be loud) and brings heat.
     */
    private fun updateDebris(dt: Float) {
        debris.count = if (settings.quality == 0) RingDebris.COUNT_LOW else RingDebris.COUNT
        if (debrisHitCooldown > 0f) debrisHitCooldown -= dt
        val hits = debris.update(dt.toDouble(), system, ship.pos, ship.vel)
        if (hits <= 0) return
        debrisHits += hits
        debrisHeat += debris.heat
        if (debrisHitCooldown <= 0f) {
            debrisHitCooldown = RingDebris.HIT_COOLDOWN
            host.haptic(Haptics.BUMP)
            addShake(0.3f + clamp(ship.speed / 400f, 0f, 1.1f) * 0.5f)
        }
    }

    /** Station hulls are solid (coarse proxies, see StationCollision). */
    private fun collideStation() {
        val i = here.nearestStation
        if (i < 0) return
        val hit = stationCollision.collide(ship, system.stations[i])
        if (hit > 2f && bumpCooldown <= 0f) {
            bumpCooldown = 0.25f
            host.haptic(if (hit > 40f) Haptics.HEAVY else Haptics.BUMP)
            addShake(clamp(hit / 50f, 0.2f, 1.2f))
        }
    }

    /** Planet and star surfaces are solid; in landing mode a slow, level, gear-down contact touches down. */
    private fun collideBodies(dt: Float) {
        val i = here.nearestBody
        if (i < 0) return
        val b = system.bodies[i]
        if (here.altitude > maxOf(spec.reach, spec.groundClearance) * 2 + ship.speed * dt * 2) return
        if (mode.landing && landable(i)) {
            // touchdown: slow, gear down and roughly level -> stand on the gear (RootToGround)
            val dx = ship.pos.x - b.pos.x
            val dy = ship.pos.y - b.pos.y
            val dz = ship.pos.z - b.pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d - b.radius < spec.groundClearance + 0.5) {
                upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
                val radial = ship.vel.dot(upV)
                ship.rot.rotate(0f, 1f, 0f, tmp)
                val level = tmp.dot(upV)
                val gear = gearRig.downFraction
                if (radial < 0.5f) {
                    if (gear >= 1f && radial > -TOUCHDOWN_SPEED && level > TOUCHDOWN_LEVEL && ship.speed < TOUCHDOWN_MAX_SPEED) {
                        mode.landed = true
                        landedBody = i
                        ship.vel.zero()
                        ship.angVel.zero()
                        throttle = 0f
                        // a thumb still on the throttle lever must not lift off by accident
                        host.releaseControls()
                        controls.clearAxes()
                        host.haptic(Haptics.BUMP)
                        showMessage("TOUCHDOWN  ·  " + b.displayName)
                        return
                    }
                    showMessage(
                        when {
                            gear < 1f -> "GEAR NOT DOWN"
                            radial <= -TOUCHDOWN_SPEED -> "HARD LANDING"
                            ship.speed >= TOUCHDOWN_MAX_SPEED -> "TOO FAST  ·  TOUCH DOWN BELOW 40 M/S"
                            else -> "LEVEL OUT TO TOUCH DOWN"
                        },
                    )
                }
            }
        }
        // the surface, or a giant's barrier at its cloud tops (DECISIONS 11)
        val hit = flight.collideSphere(ship, b.pos.x, b.pos.y, b.pos.z, b.radius)
        if (hit > 0f && b.landingBlocked != null && message == null) showMessage(landingBlockedText(b))
        if (hit > 2f && bumpCooldown <= 0f) {
            bumpCooldown = 0.25f
            host.haptic(if (hit > 40f) Haptics.HEAVY else Haptics.BUMP)
            addShake(clamp(hit / 50f, 0.2f, 1.2f))
        }
    }

    // ================================================================== landing mode (L), ground (C28)

    /** The body's `Landable` (not a star, not a gas or ice giant: Roblox `tagBody`). */
    fun landable(i: Int): Boolean = i >= 0 && system.bodies[i].landable

    /** "LANDING BLOCKED  ·  NO SOLID SURFACE" (Roblox's approach prompt from `LandingBlocked`). */
    private fun landingBlockedText(b: StarSystem.Body): String =
        if (b.landingBlocked == StarSystem.NO_SOLID_SURFACE) LANDING_BLOCKED_GAS else "LANDING BLOCKED  ·  " + b.landingBlocked

    private fun takeOff() {
        if (!mode.landed) return
        mode.landed = false
        // leave landing mode: otherwise the touchdown test re-lands the ship on the next substep
        mode.landing = false
        liftTime = LIFT_TIME
        val b = system.bodies[landedBody]
        upV.set(
            (ship.pos.x - b.pos.x).toFloat(), (ship.pos.y - b.pos.y).toFloat(), (ship.pos.z - b.pos.z).toFloat(),
        ).normalize()
        ship.vel.set(upV).scale(25f)
        showMessage("LIFT OFF")
        host.haptic(Haptics.TICK)
    }

    /** While landed: stand on the gear, level out, and wait for a take-off input. */
    private fun holdLanded(dt: Float, f: InputFrame) {
        val b = system.bodies[landedBody]
        val dx = ship.pos.x - b.pos.x
        val dy = ship.pos.y - b.pos.y
        val dz = ship.pos.z - b.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
        val rest = b.radius + spec.groundClearance
        ship.pos.set(b.pos.x + dx / d * rest, b.pos.y + dy / d * rest, b.pos.z + dz / d * rest)
        ship.vel.zero()
        ship.angVel.zero()
        ship.speed = 0f
        ship.forwardSpeed = 0f
        // settle level: keep the heading, align the ship's up with the surface normal
        ship.rot.rotate(0f, 0f, -1f, fwdV)
        fwdV.addScaled(upV, -fwdV.dot(upV))
        if (fwdV.lengthSq() > 1e-6f) {
            fwdV.normalize()
            landRot.lookRotation(fwdV.x, fwdV.y, fwdV.z, upV.x, upV.y, upV.z)
            ship.rot.slerp(ship.rot, landRot, expApproach(3f, dt))
        }
        if (f.lateralY > 0f || throttle > 0.05f || controls.strafeY > 0.2f) takeOff()
    }

    /** C62 (pre-port): the landing mode ends when the ship climbs out of the low band or leaves a solid body. */
    private fun updateLandingMode() {
        if (mode.landing && (here.altitude > StarSystem.LOW_ALTITUDE * 1.2 || !landable(here.nearestBody))) {
            mode.landing = false
            if (!mode.landed) showMessage("LANDING CANCELLED")
        }
    }

    /** Gear position the session asks for: down while landing or landed (or as forced by a debug command). */
    fun gearWanted(): Boolean = gearForce ?: (mode.landing || mode.landed)

    // ================================================================== docking (K): pre-port auto-dock

    /** Engages auto-dock into station [i]'s slot; false (with a message) when the hull does not fit it. */
    fun requestDocking(i: Int): Boolean {
        if (i < 0) return false
        // auto-dock flies without collision: only into a slot the hull clears on every side (every
        // hull fits both stand-in slots, as every Roblox hangar takes every ship)
        if (!StationMesh.slotFits(system.stations[i].model, spec.sizeX, spec.sizeY)) {
            showMessage("DOCKING DENIED  ·  SLOT TOO SMALL")
            host.haptic(Haptics.BUMP)
            return false
        }
        mode.docking = true
        dockStation = i
        dockPhase = 0
        dockBoxedTime = 0f
        mode.landing = false
        showMessage("DOCKING GRANTED  ·  AUTO-DOCK ENGAGED")
        host.haptic(Haptics.TICK)
        return true
    }

    /**
     * Auto-dock: around the hull if needed, to a point in front of the slot, line up, then slide in.
     * The route and the slot place the hull's centre (the ship turns about it), not the ship's
     * origin: that is the pilot's eye, well forward and up on the bigger hulls.
     */
    private fun updateDocking(dt: Float) {
        val st = system.stations[dockStation]
        SpawnRules.stationPort(st, dockPos, dockDir, tmp)
        ship.rot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
        hullPos.set(ship.pos.x + tmp.x, ship.pos.y + tmp.y, ship.pos.z + tmp.z)
        // phase 0: detour around the hull while the router asks for it, then hold in front of the
        // slot; phase 1: glide in and park inside the tunnel
        val detour = dockPhase == 0 && stationCollision.dockWaypoint(st, hullPos, dockWaypoint)
        // the router found no way out (it holds the ship still): give up rather than wait for ever
        dockBoxedTime = if (detour && stationCollision.boxedIn) dockBoxedTime + dt else 0f
        if (dockBoxedTime > DOCK_BOXED_GRACE) {
            mode.docking = false
            ship.vel.zero()
            showMessage("DOCKING ABORTED  ·  MOVE CLEAR OF THE HULL")
            return
        }
        val standOff = if (dockPhase == 0) StationCollision.DOCK_STANDOFF else -SpawnRules.slotParkDepth(st, spec, tmp2)
        val tx = if (detour) dockWaypoint.x else dockPos.x + dockDir.x * standOff
        val ty = if (detour) dockWaypoint.y else dockPos.y + dockDir.y * standOff
        val tz = if (detour) dockWaypoint.z else dockPos.z + dockDir.z * standOff
        val dx = tx - hullPos.x
        val dy = ty - hullPos.y
        val dz = tz - hullPos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // nose along the route while going round the hull, otherwise into the slot (against the
        // outward direction); roll matched to the station's up
        st.rot.rotate(0f, 1f, 0f, upV)
        if (detour && d > 5.0) landRot.lookRotation((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat(), upV.x, upV.y, upV.z)
        else landRot.lookRotation(-dockDir.x, -dockDir.y, -dockDir.z, upV.x, upV.y, upV.z)
        val turn = if (dockPhase == 0) 1.5f else 3f
        ship.rot.slerp(ship.rot, landRot, expApproach(turn, dt))
        val speed = if (detour) DOCK_APPROACH_SPEED else if (dockPhase == 0) minOf(DOCK_APPROACH_SPEED, (d * 0.6).toFloat() + 5f) else minOf(30f, (d * 0.8).toFloat() + 8f)
        val k = if (d > 0.5) minOf(1.0, speed * dt / d) else 0.0
        if (d > 0.5) ship.vel.set((dx / d * speed).toFloat(), (dy / d * speed).toFloat(), (dz / d * speed).toFloat())
        ship.rot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
        ship.pos.set(hullPos.x + dx * k - tmp.x, hullPos.y + dy * k - tmp.y, hullPos.z + dz * k - tmp.z)
        ship.speed = speed
        ship.forwardSpeed = speed
        ship.angVel.zero()
        ship.engineOutput += (0.3f - ship.engineOutput) * expApproach(4f, dt)
        if (dockPhase == 0 && !detour && d < 15.0) {
            dockPhase = 1
            showMessage("ENTERING SLOT")
        } else if (dockPhase == 1 && d < 2.0) {
            // Roblox onDocked: thrust reset, the services open (a non-pausing overlay)
            mode.docking = false
            mode.docked = true
            ship.vel.zero()
            ship.speed = 0f
            ship.forwardSpeed = 0f
            throttle = 0f
            controls.clearAxes()
            host.haptic(Haptics.BUMP)
            message = null
            host.openServices(dockStation)
        }
    }

    /** LAUNCH (services) or K while docked: out of the slot, nose outward, at a gentle push. */
    fun launchFromStation() {
        if (!mode.docked || dockStation < 0) return
        val st = system.stations[dockStation]
        SpawnRules.stationPort(st, dockPos, dockDir, tmp)
        // the hull on the slot axis with its tail LAUNCH_CLEAR metres off the entrance
        st.rot.rotate(0f, 1f, 0f, upV)
        ship.rot.lookRotation(dockDir.x, dockDir.y, dockDir.z, upV.x, upV.y, upV.z)
        val out = spec.sizeZ * 0.5 + LAUNCH_CLEAR
        ship.rot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
        ship.pos.set(dockPos.x + dockDir.x * out - tmp.x, dockPos.y + dockDir.y * out - tmp.y, dockPos.z + dockDir.z * out - tmp.z)
        ship.vel.set(dockDir).scale(60f)
        throttle = 0.3f
        controls.clearAxes()
        chase.snapTo(ship)
        mode.docked = false
        launchTime = flightTime
        host.closeServices()
        showMessage("LAUNCHED  ·  " + st.name)
    }

    // ================================================================== camera, dust, warnings, HUD state

    private fun updateCamera(dt: Float) {
        if (mode.view == ShipView.CHASE && !settings.chaseView) setView(ShipView.COCKPIT)
        val speedFrac = ship.speed / spec.maxSpeed
        val radius = model?.radius ?: spec.reach
        if (mode.view == ShipView.CHASE) {
            chase.update(ship, dt, clamp(speedFrac, 0f, 1.8f), spec.maxSpeed)
            cameraPos.set(chase.pos)
            chase.renderRotation(cameraRot)
            val boostFov = if (ship.boosting) 9f else 0f
            chase.fovBoost += (boostFov - chase.fovBoost) * expApproach(4f, dt)
            fovY = (CHASE_FOV + clamp(speedFrac, 0f, 1.8f) * 3f + chase.fovBoost) * DEG2RAD
            shipReach = chase.viewDistance * (1f + chase.speedPull * 1.8f) + radius + PLUME_REACH
        } else {
            camera.update(ship.pos, ship.rot)
            cameraPos.set(camera.pos)
            cameraRot.set(camera.rot)
            fovY = clamp(settings.fovDeg, Settings.FOV_MIN, Settings.FOV_MAX) * DEG2RAD
            // the near depth slice reaches the far end of the hull and its plumes
            ship.rot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
            val cx = ship.pos.x + tmp.x - cameraPos.x
            val cy = ship.pos.y + tmp.y - cameraPos.y
            val cz = ship.pos.z + tmp.z - cameraPos.z
            shipReach = sqrt(cx * cx + cy * cy + cz * cz).toFloat() + radius + PLUME_REACH
        }
    }

    /** C45 (pre-port): the PROXIMITY warning from station hull gaps. */
    private fun updateProximity() {
        // station hulls make the proximity warning. Not while auto-docking (it flies its own clear
        // route), docked or landed, nor just after a launch, which starts 30 m off the slot
        proximity = Float.MAX_VALUE
        if (mode.docking || mode.docked || mode.landed) return
        if (here.nearestStation >= 0 && flightTime - launchTime > LAUNCH_GRACE) {
            proximity = stationCollision.clearance(ship, system.stations[here.nearestStation])
        }
    }

    /** Clearance (m) between the hull's probes and the nearest station's proxies (HUD, harness). */
    fun stationClearance(): Float =
        if (here.nearestStation >= 0) stationCollision.clearance(ship, system.stations[here.nearestStation]) else Float.MAX_VALUE

    /** C48: speed dust moves at a capped "visual" speed so it stays readable at Mm/s. */
    private fun updateDust(dt: Float) {
        val sp = ship.speed
        val visScale = if (sp > DUST_VISUAL_SPEED) DUST_VISUAL_SPEED / sp else 1f
        tmp.set(ship.vel).scale(visScale)
        dustOrigin.addScaled(tmp, dt.toDouble())
        // longer streaks the faster you go (logarithmic above the visual cap)
        val streak = if (sp > DUST_VISUAL_SPEED) 1f + 0.6f * kotlin.math.log10(sp / DUST_VISUAL_SPEED) else 1f
        dustVelocity.set(tmp).scale(streak)
    }

    /** C66: dust mood: white in open space, blue in a gravity well, red for danger or heat. */
    private fun updateDustMood(dt: Float) {
        val zone = here.zone
        val star = here.nearestBody == system.star
        val danger = proximity < 80f || (star && here.altitude < system.bodies[system.star].radius)
        val well = zone == StarSystem.Zone.GRAVITY_WELL || zone == StarSystem.Zone.LOW_ALTITUDE
        val k = expApproach(4f, dt)
        val tr = if (danger) 1.0f else if (well) 0.40f else 0.66f
        val tg = if (danger) 0.30f else if (well) 0.62f else 0.72f
        val tb = if (danger) 0.26f else if (well) 1.0f else 0.80f
        dustColor.x += (tr - dustColor.x) * k
        dustColor.y += (tg - dustColor.y) * k
        dustColor.z += (tb - dustColor.z) * k
    }

    /** C67: the contextual ACTION (Roblox: L lands, K docks/launches), and what it says. */
    private fun updateHudState() {
        when {
            mode.landed -> { actionLabel = "TAKE OFF"; actionTarget = Action.LAND }
            mode.docking -> { actionLabel = "CANCEL"; actionTarget = Action.DOCK }
            mode.landing -> { actionLabel = "CANCEL"; actionTarget = Action.LAND }
            mode.docked -> { actionLabel = "LAUNCH"; actionTarget = Action.DOCK }
            here.zone == StarSystem.Zone.STATION_ZONE -> { actionLabel = "DOCK"; actionTarget = Action.DOCK }
            here.zone == StarSystem.Zone.LOW_ALTITUDE && landable(here.nearestBody) -> { actionLabel = "LAND"; actionTarget = Action.LAND }
            else -> { actionLabel = ""; actionTarget = null }
        }
    }

    /**
     * C64: the voice queue; the welcome lines once per app run, [Voice.WELCOME_DELAY] after the
     * reveal. Voice lines are text until recordings exist (D31), so a line leaves the queue only
     * while the message line is free and can be seen: with the HUD or over the station services of
     * a docked start, not under a map or the curtain, where the lines wait rather than expire unseen.
     */
    private fun updateVoice(dt: Float) {
        if (welcomeIn > 0f) {
            welcomeIn -= dt
            if (welcomeIn <= 0f) {
                voice.say(Voice.WELCOME)
                voice.say(Voice.SYSTEMS_ONLINE)
            }
        }
        val line = voice.next(message == null && messageShown())
        if (line != null) showMessage(line)
    }

    /** The message line is on screen this frame (the game draws it on these terms). */
    fun messageShown(): Boolean = mode.overlay.showsMessages && !mode.curtain

    /** C74 / A14: the message line expires after [MESSAGE_TIME]. */
    private fun updateMessages() {
        if (message != null && time - messageTime > MESSAGE_TIME) message = null
    }

    fun showMessage(m: String) {
        message = m
        messageTime = time
    }

    /** Copies the camera and the effects of the last frame into the renderer's [f]. */
    fun writeScene(f: SceneFrame) {
        val cam = f.camera
        cam.pos.set(cameraPos)
        cam.rot.set(cameraRot)
        cam.fovY = fovY
        cam.shiftX = 0f
        cam.shiftY = 0f
        f.dustOrigin.set(dustOrigin)
        f.dustVelocity.set(dustVelocity)
        f.dustIntensity = 0.6f
        f.dustColor.set(dustColor)
        f.ship = ship
        f.shipView = if (mode.view == ShipView.COCKPIT) ShipModel.VIEW_COCKPIT else ShipModel.VIEW_EXTERIOR
        f.shipReach = shipReach
        f.debris = debris
    }

    // ================================================================== debug (Roblox ShipDebug equivalents)

    /** "stick x y": holds a cursor deflection (radius fractions, Y down); [hold] false releases it. */
    fun debugStick(hold: Boolean, x: Float, y: Float) {
        stickHold = hold
        stickHoldX = clamp(x, -1f, 1f)
        stickHoldY = clamp(y, -1f, 1f)
    }

    /** "look x y": holds the head at (x, y) degrees; [hold] false lets it return. */
    fun debugLook(hold: Boolean, x: Float, y: Float) {
        camera.lookHold = hold
        if (hold) camera.setLook(x, y)
    }

    /** "speed v": the velocity (relative to the ship's frame) set to [v] m/s along the nose; with FA off it holds. */
    fun debugSpeed(v: Float) {
        if (mode.docked || mode.docking || mode.landed) return
        ship.rot.rotate(0f, 0f, -v, ship.vel)
        ship.speed = abs(v)
    }

    /** "throttle v": the lever. */
    fun setThrottle(v: Float) {
        throttle = clamp(v, -1f, 1f)
        controls.throttle = throttle
    }

    /** "teleport x y z [lx ly lz [ux uy uz]]": system metres, looking along l with up u (default: keep the rotation; up +Y); at rest. */
    fun teleport(x: Double, y: Double, z: Double, lx: Float, ly: Float, lz: Float, look: Boolean, ux: Float = 0f, uy: Float = 1f, uz: Float = 0f) {
        undockQuietly()
        ship.pos.set(x, y, z)
        if (look) ship.rot.lookRotation(lx, ly, lz, ux, uy, uz)
        ship.vel.zero()
        ship.angVel.zero()
        snapAfterJump()
    }

    /** "near body [alt]": [alt] metres above body [i]'s surface on its sunward side, nose level. */
    fun near(i: Int, alt: Double) {
        undockQuietly()
        val b = system.bodies[i]
        val star = system.bodies[system.star]
        var ux = star.pos.x - b.pos.x
        var uy = star.pos.y - b.pos.y
        var uz = star.pos.z - b.pos.z
        var d = sqrt(ux * ux + uy * uy + uz * uz)
        if (d < 1.0) {
            ux = 0.0; uy = 0.0; uz = 1.0; d = 1.0
        }
        ux /= d; uy /= d; uz /= d
        val r = b.radius + alt
        ship.pos.set(b.pos.x + ux * r, b.pos.y + uy * r, b.pos.z + uz * r)
        upV.set(ux.toFloat(), uy.toFloat(), uz.toFloat())
        fwdV.set(0f, 1f, 0f).addScaled(upV, -upV.y)
        if (fwdV.lengthSq() < 1e-6f) fwdV.set(1f, 0f, 0f)
        fwdV.normalize()
        ship.rot.lookRotation(fwdV.x, fwdV.y, fwdV.z, upV.x, upV.y, upV.z)
        ship.vel.zero()
        ship.angVel.zero()
        snapAfterJump()
    }

    /** "face body": turns the nose onto body [i]'s centre (level with the world's +Y), keeping the position and the velocity's speed along the new nose. */
    fun debugFace(i: Int) {
        if (mode.docked || mode.docking || mode.landed) return
        val b = system.bodies[i]
        ship.rot.lookRotation((b.pos.x - ship.pos.x).toFloat(), (b.pos.y - ship.pos.y).toFloat(), (b.pos.z - ship.pos.z).toFloat(), 0f, 1f, 0f)
        val v = ship.vel.length()
        ship.rot.rotate(0f, 0f, -v, ship.vel)
        ship.angVel.zero()
    }

    /**
     * "clock s": sets the save-game clock (the planets jump to their places for [seconds]); the ship
     * keeps its place in its reference frame.
     */
    fun debugClock(seconds: Double) {
        system.frameOrigin(refFrame, frameOrigin)
        val ox = frameOrigin.x
        val oy = frameOrigin.y
        val oz = frameOrigin.z
        system.snapTo(seconds)
        system.frameOrigin(refFrame, frameOrigin)
        if (refFrame != StarSystem.FRAME_NONE) {
            ship.pos.x += frameOrigin.x - ox
            ship.pos.y += frameOrigin.y - oy
            ship.pos.z += frameOrigin.z - oz
        }
        snapAfterJump()
    }

    /** Harness hook: 20 km above the home planet's day side (Earth), nose a little down, in landing range. */
    fun debugTeleportLow() {
        undockQuietly()
        val b = system.bodies[system.stations[SpawnRules.startStation(system)].parent]
        val sp = system.bodies[system.star].pos
        val sx = b.pos.x - sp.x
        val sy = b.pos.y - sp.y
        val sz = b.pos.z - sp.z
        val d = sqrt(sx * sx + sy * sy + sz * sz)
        val ux = -sx / d
        val uy = -sy / d
        val uz = -sz / d
        val alt = b.radius + 20_000.0
        ship.pos.set(b.pos.x + ux * alt, b.pos.y + uy * alt, b.pos.z + uz * alt)
        upV.set(ux.toFloat(), uy.toFloat(), uz.toFloat())
        // heading: any direction along the surface
        fwdV.set(0f, 1f, 0f).addScaled(upV, -upV.y).normalize()
        ship.rot.lookRotation(fwdV.x - upV.x * 0.25f, fwdV.y - upV.y * 0.25f, fwdV.z - upV.z * 0.25f, upV.x, upV.y, upV.z)
        ship.vel.zero()
        throttle = 0.2f
        snapAfterJump()
    }

    /** Harness hook: drop straight down to [alt] metres above the nearest body, level, at rest. */
    fun debugDrop(alt: Double) {
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
        val b = system.bodies[here.nearestBody]
        val dx = ship.pos.x - b.pos.x
        val dy = ship.pos.y - b.pos.y
        val dz = ship.pos.z - b.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val r = b.radius + spec.groundClearance + alt
        ship.pos.set(b.pos.x + dx / d * r, b.pos.y + dy / d * r, b.pos.z + dz / d * r)
        upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
        ship.rot.rotate(0f, 0f, -1f, fwdV)
        fwdV.addScaled(upV, -fwdV.dot(upV)).normalize()
        ship.rot.lookRotation(fwdV.x, fwdV.y, fwdV.z, upV.x, upV.y, upV.z)
        ship.vel.zero()
        ship.angVel.zero()
        throttle = 0f
        snapAfterJump()
    }

    /** Harness hook: [dist] m behind station [i] (opposite its slot), nose on the hub, flying at it at [speed]. */
    fun debugBehindStation(i: Int, dist: Double, speed: Float) {
        undockQuietly()
        val st = system.stations[i]
        SpawnRules.stationPort(st, dockPos, dockDir, tmp)
        ship.pos.set(st.pos.x - dockDir.x * dist, st.pos.y - dockDir.y * dist, st.pos.z - dockDir.z * dist)
        st.rot.rotate(0f, 1f, 0f, upV)
        ship.rot.lookRotation(dockDir.x, dockDir.y, dockDir.z, upV.x, upV.y, upV.z)
        ship.vel.set(dockDir).scale(speed)
        ship.angVel.zero()
        throttle = if (speed > 0f) 0.6f else 0f
        mode.landed = false
        mode.landing = false
        liftTime = 0f
        gearRig.update(0f, gearWanted(), true)
        mode.docking = false
        snapAfterJump()
    }

    /**
     * Harness hook: auto-dock into station [i], already lined up with the hull's centre 20 m in front
     * of the slot ([updateDocking] glides it in).
     */
    fun debugDocked(i: Int) {
        undockQuietly()
        if (!requestDocking(i)) return
        val st = system.stations[i]
        SpawnRules.stationPort(st, dockPos, dockDir, tmp)
        st.rot.rotate(0f, 1f, 0f, upV)
        ship.rot.lookRotation(-dockDir.x, -dockDir.y, -dockDir.z, upV.x, upV.y, upV.z)
        ship.rot.rotate(spec.centreX, spec.centreY, spec.centreZ, tmp)
        ship.pos.set(dockPos.x + dockDir.x * 20.0 - tmp.x, dockPos.y + dockDir.y * 20.0 - tmp.y, dockPos.z + dockDir.z * 20.0 - tmp.z)
        ship.vel.zero()
        ship.angVel.zero()
        dockPhase = 1
        snapAfterJump()
    }

    /** Leaves a docked state without a launch (debug teleports start from anywhere). */
    private fun undockQuietly() {
        if (mode.docked) {
            mode.docked = false
            host.closeServices()
        }
        mode.landed = false
    }

    private fun snapAfterJump() {
        controls.clearAxes()
        dustOrigin.set(ship.pos)
        chase.snapTo(ship)
        // a teleport lands in whatever frame holds the new spot (the docking station forces its own)
        refFrame = if (mode.docking && dockStation >= 0) StarSystem.stationFrame(dockStation) else system.frameFor(ship.pos, StarSystem.FRAME_NONE)
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
        updateCamera(0f)
    }

    /** The ship's position relative to its reference frame's origin into [out] (harness: motion in the frame). */
    fun framePosition(out: DVec3): DVec3 {
        system.frameOrigin(refFrame, frameOrigin)
        return out.set(ship.pos.x - frameOrigin.x, ship.pos.y - frameOrigin.y, ship.pos.z - frameOrigin.z)
    }

    /** Harness read-out (allocates; never per frame). */
    fun debugState(): String {
        val fp = framePosition(DVec3())
        return "branch=${mode.branch} pstate=${mode.state} view=${mode.view} look=${"%.1f".format(camera.lookX)},${"%.1f".format(camera.lookY)} " +
            "landed=${mode.landed} landing=${mode.landing} docking=${mode.docking} docked=${mode.docked} " +
            "controlled=${mode.controlled} revealed=${mode.revealed} station=$dockStation " +
            "gear=${"%.2f".format(gearRig.downFraction)} stowed=${gearRig.stowed} " +
            "alt=${"%.1f".format(here.altitude - spec.groundClearance)} speed=${"%.1f".format(ship.speed)} " +
            "throttle=${"%.2f".format(throttle)} fa=${ship.flightAssist} " +
            "boosting=${ship.boosting} cap=${"%.2f".format(ship.capacitor)} lock=$lockKind:$lockIndex " +
            "hull=${"%.1f".format(stationClearance())} prox=${"%.1f".format(proximity)} " +
            "pos=${"%.1f".format(ship.pos.x)},${"%.1f".format(ship.pos.y)},${"%.1f".format(ship.pos.z)} " +
            "cam=${"%.2f".format(cameraPos.x - ship.pos.x)},${"%.2f".format(cameraPos.y - ship.pos.y)},${"%.2f".format(cameraPos.z - ship.pos.z)} " +
            "fov=${"%.1f".format(fovY / DEG2RAD)} zone=${here.zone.name} action=${actionTarget?.name ?: "-"} " +
            "body=${if (here.nearestBody >= 0) system.bodies[here.nearestBody].name else "-"} limit=${"%.1f".format(here.speedLimit)} " +
            "ref=${system.frameName(refFrame).replace(' ', '_')} fpos=${"%.1f".format(fp.x)},${"%.1f".format(fp.y)},${"%.1f".format(fp.z)} " +
            "clock=${"%.3f".format(system.clock)} rocks=${debris.visible} rhits=$debrisHits rheat=${"%.3f".format(debrisHeat)}"
    }

    companion object {
        const val LOCK_NONE = 0
        const val LOCK_STATION = 2
        const val LOCK_BODY = 3

        /** Roblox's approach prompt for a giant (`LandingBlocked`, DECISIONS 11). */
        const val LANDING_BLOCKED_GAS = "LANDING BLOCKED  ·  NO SOLID SURFACE"

        /** dt cap per frame (F0, `ShipClient.lua:2324`): tab-outs and load pauses must not throw the ship. */
        const val MAX_DT = 0.1f

        /** Autopilot.OverrideDeflection: a deflection beyond this is the pilot steering (C7). */
        const val OVERRIDE_DEFLECTION = 0.5f

        /** Longest flight sub-step, s (the move and its collisions; 02 section 8). */
        const val MAX_STEP = 1f / 120f
        const val LOCK_CONE_COS = 0.951f // 18°
        const val LOCK_CONE_SIN = 0.30920f // sqrt(1 - LOCK_CONE_COS²)
        const val AIM_CONE_COS = 0.9986f // 3°
        const val AIM_CONE_SIN = 0.052896f
        const val DUST_VISUAL_SPEED = 400f
        const val LANDING_SPEED = 200f
        const val TOUCHDOWN_SPEED = 8f
        const val TOUCHDOWN_MAX_SPEED = 40f

        /** cos of the largest tilt from level that still touches down (~37°). */
        const val TOUCHDOWN_LEVEL = 0.8f
        const val LIFT_TIME = 1.2f

        /** Vertical thruster authority in landing mode: 0.1 x 70 m/s = a 7 m/s descent, under the touchdown limit. */
        const val LANDING_VERTICAL = 0.1f

        /** The pre-port stick feel (radial dead zone, expo), until `02` brings Roblox's 0.20 / 1.7. */
        const val STICK_DEAD = 0.06f
        const val STICK_EXPO = 0.25f

        /** Vertical FOV of the chase view at rest (it widens with speed and boost). */
        const val CHASE_FOV = 52f

        /** Longest exhaust plume (m) beyond the hull, for the near depth slice. */
        const val PLUME_REACH = 40f
        const val DOCK_APPROACH_SPEED = 180f

        /** Auto-dock gives up after the router has been boxed in this long (s). */
        const val DOCK_BOXED_GRACE = 0.5f

        /** No station proximity warning for this long (s) after a launch from a slot. */
        const val LAUNCH_GRACE = 1.5f

        /** A launch puts the tail this far (m) off the slot entrance. */
        const val LAUNCH_CLEAR = 30.0

        /** How long the message line shows a message, s. */
        const val MESSAGE_TIME = 3f
    }
}
