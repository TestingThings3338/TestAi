package com.starwake.core.audio

import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import kotlin.math.max
import kotlin.math.sqrt

/** `ShipConfig.Voice.Callouts` (`09` 3.8). Heights in m, speeds m/s, times s. */
object CalloutConfig {
    const val START_GRACE = 6f
    const val QUIET_GRACE = 1.5f

    /** Altitudes: height above the touchdown point and its line, high to low. */
    val ALTITUDES = floatArrayOf(500f, 100f, 50f, 30f, 20f, 10f)
    val ALTITUDE_LINES = arrayOf(
        VoiceLine.AltFiveHundred, VoiceLine.AltOneHundred, VoiceLine.AltFifty,
        VoiceLine.AltThirty, VoiceLine.AltTwenty, VoiceLine.AltTen,
    )
    const val ALTITUDE_REARM = 1.4f
    const val ALTITUDE_MIN_SINK = 1.5f

    val LOOKAHEAD = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 5f)
    const val TERRAIN_INTERVAL = 0.1f
    const val DANGER_SHARE = 0.75f
    const val IMPACT_TIME = 1.2f
    const val PULL_UP_TIME = 3.5f
    const val TERRAIN_AHEAD_TIME = 5f
    const val SINK_RATE_TIME = 8f
    const val SINK_RATE_SHARE = 0.9f
    const val CLOSE_HEIGHT = 10f
    const val CLOSE_SPEED = 90f
    const val CLOSE_AIR_TIME = 4f
    const val FLARE_HEIGHT = 15f
    const val FLARE_SINK = 8f
    const val TOO_FAST_HEIGHT = 45f
    const val TOO_FAST_SHARE = 1.5f
    const val APPROACH_REARM_HEIGHT = 90f
    const val TOUCHDOWN_AIR_TIME = 2f
    const val HARD_LANDING_SHARE = 0.6f
    const val GEAR_HOLD = 1f
    const val HEAT_RISING = 0.65f
    const val HEAT_NORMAL = 0.5f
    const val FUEL_LOW = 0.2f
    const val FUEL_CRITICAL = 0.08f
    const val FUEL_REARM = 0.05f
    const val HIGH_G_SHARE = 0.9f
    const val G_REARM_SHARE = 0.6f
    const val REDOUT_PUSH = 0.55f
    const val OVERSPEED_SHARE = 1.2f
    const val OVERSPEED_MARGIN = 15f
    const val APPROACH_SHARE = 0.93f
    const val APPROACH_REARM_SHARE = 0.75f
    const val SPEED_HOLD = 0.6f
    const val COLLISION_TIME = 2.5f
    const val COLLISION_SPEED = 30f
    const val COLLISION_INTERVAL = 0.15f
    const val HULL_IMPACT_SPEED = 12f

    /**
     * StandByAfter: "Request received. Stand by." while a docking request hangs. Roblox's 2 s never
     * fired (the grant comes after GrantDelay 1.6 s); DECISIONS 35 fixes it to 0.8 s, so traffic
     * control acknowledges before it grants.
     */
    const val STAND_BY_AFTER = 0.8f
    const val ENTRY_HEAT_START = 0.25f
    const val TURBULENCE_DELAY = 3f
    const val TURBULENCE_MIN = 0.3f

    /** Surface.SafeLandingSpeed and Physics.BlackoutStartG. */
    const val SAFE_LANDING_SPEED = 38f
    const val BLACKOUT_START_G = 6.5f

    /** FRAME_GAP: not called for longer is a pause (decks, cinematic). */
    const val FRAME_GAP = 0.5f

    /** Callouts.Cooldown per line (missing = 0); the voice's RepeatCooldown applies on top. */
    val COOLDOWN = FloatArray(VoiceLine.COUNT).also {
        fun c(l: VoiceLine, s: Float) {
            it[l.ordinal] = s
        }
        c(VoiceLine.PullUp, 3.5f); c(VoiceLine.TerrainAhead, 3.5f); c(VoiceLine.Terrain, 10f)
        c(VoiceLine.SinkRate, 3.5f); c(VoiceLine.ImpactImminent, 3f)
        c(VoiceLine.TooFastToLand, 10f); c(VoiceLine.Flare, 8f); c(VoiceLine.Touchdown, 5f); c(VoiceLine.HullImpact, 2f)
        c(VoiceLine.GearDown, 8f); c(VoiceLine.GearUp, 8f); c(VoiceLine.BoostReady, 8f)
        c(VoiceLine.CruiseOn, 6f); c(VoiceLine.CruiseOff, 6f); c(VoiceLine.CruiseDropped, 8f)
        c(VoiceLine.GravityEntered, 15f); c(VoiceLine.GravityLeft, 15f); c(VoiceLine.ScoopStarted, 30f); c(VoiceLine.TankFull, 30f)
        c(VoiceLine.HighG, 8f); c(VoiceLine.Redout, 8f); c(VoiceLine.Overspeed, 6f); c(VoiceLine.ApproachingSpeedLimit, 45f)
        c(VoiceLine.ControlReduceSpeed, 10f); c(VoiceLine.CollisionAlert, 4f); c(VoiceLine.HeatRising, 20f); c(VoiceLine.HeatNormal, 20f)
        c(VoiceLine.EntryHeat, 30f); c(VoiceLine.Turbulence, 20f); c(VoiceLine.CloudDeck, 30f)
        c(VoiceLine.ScanStarted, 8f); c(VoiceLine.DepositDetected, 60f); c(VoiceLine.DockingRequestSent, 3f)
    }
}

/** Atmosphere states and phases as the callouts see them (ShipAtmosphere `state`, `phase`). */
object Atmo {
    const val SPACE = 0
    const val SURFACE = 1
    const val OTHER = 2

    const val NONE = -1
    const val HEAT = 0
    const val DESCENT = 1
    const val LEVEL = 2
    const val CLIMB = 3
    const val EMERGE = 4
}

/** ShipWarp states. */
object WarpState {
    const val IDLE = 0
    const val ALIGN = 1
    const val COUNTDOWN = 2
    const val TUNNEL = 3
    const val ARRIVE = 4
}

/** Height of a point above the ground along the local vertical (ShipSurface `getAltitude`). */
fun interface CalloutTerrain {
    fun altitude(x: Double, y: Double, z: Double): Double
}

/** Time to contact with a station hull (ShipStation `timeToContact`): seconds, or a negative value for none; the hull normal into [normal]. */
fun interface CalloutContacts {
    fun timeToContact(
        px: Double, py: Double, pz: Double, vx: Float, vy: Float, vz: Float, radius: Float, horizon: Float, normal: Vec3,
    ): Float
}

/**
 * What the callouts read each frame (ShipClient fills it at `:2941-2958`, plus the module parts the
 * Roblox callouts query directly). Reused. Each block of sources has an availability flag: a block
 * whose source is not ported yet stays false and its callouts are silent, never wrong (`09` R6).
 */
class CalloutFrame {
    // ---- the ship (shipCFrame position, velocity, speed, topSpeed)
    var posX = 0.0
    var posY = 0.0
    var posZ = 0.0
    var velX = 0f
    var velY = 0f
    var velZ = 0f

    /** The local vertical (the Roblox landing scene's +Y; radial on a planet). */
    var upX = 0f
    var upY = 1f
    var upZ = 0f
    var speed = 0f

    /** Handling TopSpeed; <= 0 = absent (math.huge in the comparisons). */
    var topSpeed = 0f
    var onFoot = false
    var crashing = false

    /** The docking computer, the clamps or the landing guidance fly the ship. */
    var controlled = false

    /** Inside a station's control zone (overspeed is "Reduce speed" from traffic control). */
    var stationLock = false

    /** Gear height: the ship's centre above the touchdown point. */
    var clearance = 0f
    var probeRadius = 6f

    // ---- flight (ShipThrust, ShipHull)
    var hasThrust = false
    var cruising = false
    var flightAssist = true
    var boosting = false
    var throttle = 0f

    /** The tracked speed limit and the raw one of a body or station; +inf = none. */
    var speedLimit = Float.POSITIVE_INFINITY
    var rawSpeedLimit = Float.POSITIVE_INFINITY
    var boostCooldown = 0f
    var hasGear = false
    var gearDown = false

    // ---- systems (ShipSystems, ShipWarnings, ShipGravity), each part on its own
    var hasHeat = false
    var heat = 0f
    var heatPhase = 0
    var hasFuel = false
    var fuel = 1f
    var hasScoop = false
    var scooping = false
    var hasGravity = false
    var inInfluence = false
    var hasG = false
    var strainG = 0f
    var redout = 0f

    // ---- atmosphere (ShipAtmosphere)
    var hasAtmosphere = false
    var atmoState = Atmo.SPACE
    var atmoPhase = Atmo.NONE
    var entryHeat = 0f
    var turbulence = 0f
    var guiding = false

    // ---- surface (ShipSurface): the landing block runs while [surfaceActive] and [terrain] is set
    var surfaceActive = false
    var landed = false
    var terrain: CalloutTerrain? = null

    // ---- warp (ShipWarp)
    var hasWarp = false
    var warpState = WarpState.IDLE
    var warpTravelling = false
    var warpActive = false

    // ---- docking (ShipDocking)
    var hasDocking = false
    var dockState = DockState.FREE

    // ---- collision (ShipStation timeToContact); null = off
    var contacts: CalloutContacts? = null

    // ---- work (ShipSurvey, ShipMining)
    var hasSurvey = false
    var scanning = false
    var scanProgress = 0f
    var hasMining = false
    var depositInRange = false
}

/**
 * Automatic announcements, a 1:1 port of Roblox `ShipCallouts` (`09` 2.14) as pure logic over a
 * [CalloutFrame]: edges (speak on a change), hysteresis, debounce ([steady]), quiet phases and the
 * silent read-in after the start (6 s) and after every pause (1.5 s). Lines go to [voice]; every
 * line has its own cooldown on top of the voice's repeat protection.
 *
 * Game thread; fixed arrays, no allocation.
 */
class Callouts(private val voice: Voice, private val clock: AudioClock) {
    private val last = DoubleArray(VoiceLine.COUNT) { Double.NaN }
    var graceUntil = clock.now + CalloutConfig.START_GRACE
        private set
    var quiet = false
        private set
    private var lastFrame = Double.NaN
    private var warpState = -1
    private var terrainClock = 0f
    private var collisionClock = 0f

    // trackers that survive pauses (else "Fuel low" after every jump)
    private var fuelLowSaid = false
    private var fuelCriticalSaid = false
    private var heatWarned = false
    private var heatRisingSaid = false
    private var gSaid = false
    private var approachArmed = false
    private var flareArmed = false
    private var tooFastArmed = false

    // steady() flags
    private val flagSet = BooleanArray(FLAGS)
    private val flagValue = BooleanArray(FLAGS)
    private val flagCandidate = BooleanArray(FLAGS)
    private val flagSince = DoubleArray(FLAGS)

    // trackers reset by a pause
    private val armed = BooleanArray(CalloutConfig.ALTITUDES.size)
    private var atmoKnown = false
    private var atmoState = Atmo.SPACE
    private var atmoPhase = Atmo.NONE
    private var dockState = DockState.NONE
    private var heatPhase = -1
    private var lastHeat = Float.NaN
    private var boostCooldown = Float.NaN
    private var rawCruiseKnown = false
    private var rawCruise = false
    private var cruiseForced = false
    private var wasScoopingKnown = false
    private var wasScooping = false

    /** Seconds airborne in the landing block, the previous frame's sink rate. */
    var airTime = 0f
        private set
    private var prevSink = 0f

    /** Predicted seconds to ground contact (NaN = none) and its impact speed. */
    var hitTime = Float.NaN
        private set
    var hitImpact = 0f
        private set
    private var lastWarningAt = Double.NEGATIVE_INFINITY
    private var entryHeatSaid = false
    private var deckSince = Double.NaN
    private var turbulenceSaid = false
    private var requestSince = Double.NaN
    private var standBySaid = false

    private val normal = Vec3()

    init {
        resetTrackers()
    }

    /** Clears the edge trackers: the next frame reads silently again. */
    private fun resetTrackers() {
        for (i in 0 until FLAGS) flagSet[i] = false
        for (i in armed.indices) armed[i] = false
        atmoKnown = false
        atmoState = Atmo.SPACE
        atmoPhase = Atmo.NONE
        dockState = DockState.NONE
        heatPhase = -1
        lastHeat = Float.NaN
        boostCooldown = Float.NaN
        rawCruiseKnown = false
        rawCruise = false
        cruiseForced = false
        wasScoopingKnown = false
        wasScooping = false
        airTime = 0f
        prevSink = 0f
        hitTime = Float.NaN
        hitImpact = 0f
        lastWarningAt = Double.NEGATIVE_INFINITY
        entryHeatSaid = false
        deckSince = Double.NaN
        turbulenceSaid = false
        requestSince = Double.NaN
        standBySaid = false
    }

    private fun resume(now: Double) {
        resetTrackers()
        graceUntil = max(graceUntil, now + CalloutConfig.QUIET_GRACE)
    }

    /** A line with its own cooldown; silent during the read-in (`say`, :112). */
    fun say(line: VoiceLine): Boolean {
        val now = clock.now
        if (now < graceUntil) return false
        val l = last[line.ordinal]
        if (!l.isNaN() && now - l < CalloutConfig.COOLDOWN[line.ordinal]) return false
        if (voice.say(line)) {
            last[line.ordinal] = now
            return true
        }
        return false
    }

    // steady() results of the last call
    private var steadyValue = false
    private var steadyChanged = false

    /** Debounced boolean (`steady`, :135): a new value counts once it held [hold] s; the first call reads silently. */
    private fun steady(flag: Int, raw: Boolean, hold: Float, now: Double) {
        if (!flagSet[flag]) {
            flagSet[flag] = true
            flagValue[flag] = raw
            flagCandidate[flag] = raw
            flagSince[flag] = now
            steadyValue = raw
            steadyChanged = false
            return
        }
        if (raw != flagCandidate[flag]) {
            flagCandidate[flag] = raw
            flagSince[flag] = now
        }
        if (flagCandidate[flag] != flagValue[flag] && now - flagSince[flag] >= hold) {
            flagValue[flag] = flagCandidate[flag]
            steadyValue = flagValue[flag]
            steadyChanged = true
            return
        }
        steadyValue = flagValue[flag]
        steadyChanged = false
    }

    /** Every flight frame, after the warnings (`update`, :159). */
    fun update(dt: Float, f: CalloutFrame) {
        val now = clock.now
        if (!lastFrame.isNaN() && now - lastFrame > CalloutConfig.FRAME_GAP) resume(now)
        lastFrame = now

        // crash: read along silently so no stale edge fires afterwards
        if (f.crashing) {
            quiet = true
            warpState = if (f.hasWarp) f.warpState else -1
            return
        }

        // the jump first: the arrival falls exactly on the end of the quiet
        if (f.hasWarp) updateJump(f)

        // quiet: on foot aboard, the jump tunnel
        if (f.onFoot || (f.hasWarp && f.warpTravelling)) {
            quiet = true
            return
        }
        if (quiet) {
            quiet = false
            resume(now)
        }

        if (f.hasAtmosphere) updateAtmosphere(f, now)
        updateSurface(dt, f, now)
        updateFlight(f, now)
        updateSystems(f, now)
        updateSpeed(f, now)
        updateCollision(dt, f)
        if (f.hasDocking) updateDocking(f, now)
        updateWork(f, now)
    }

    // ================================================================== jump (`updateJump`, :201)

    private fun updateJump(f: CalloutFrame) {
        val state = f.warpState
        val previous = warpState
        warpState = state
        if (previous < 0 || previous == state) return
        if (state == WarpState.ALIGN && previous == WarpState.IDLE) {
            say(VoiceLine.JumpCharging)
        } else if (state == WarpState.IDLE && (previous == WarpState.ALIGN || previous == WarpState.COUNTDOWN)) {
            say(VoiceLine.JumpCancelled)
        } else if (state == WarpState.IDLE && (previous == WarpState.ARRIVE || previous == WarpState.TUNNEL)) {
            say(VoiceLine.JumpArrival)
        }
    }

    // ================================================================== entry and ascent (`updateAtmosphere`, :221)

    private fun updateAtmosphere(f: CalloutFrame, now: Double) {
        val state = f.atmoState
        val phase = f.atmoPhase
        val previousKnown = atmoKnown
        val previousPhase = atmoPhase
        atmoKnown = true
        atmoState = state
        atmoPhase = phase

        if (previousKnown && phase != previousPhase) {
            if (phase == Atmo.HEAT) {
                say(VoiceLine.EntryStarted)
                entryHeatSaid = false
                deckSince = Double.NaN
                turbulenceSaid = false
            } else if (phase == Atmo.CLIMB) {
                say(VoiceLine.AscentStarted)
            } else if (phase == Atmo.EMERGE) {
                say(VoiceLine.LeavingAtmosphere)
            } else if (phase == Atmo.NONE && previousPhase == Atmo.LEVEL && state == Atmo.SURFACE) {
                say(VoiceLine.LevelOff) // the guidance hands over: "Entry complete. You have control."
            } else if (phase == Atmo.NONE && previousPhase == Atmo.EMERGE && state == Atmo.SPACE) {
                say(VoiceLine.OrbitReached)
            }
        }

        // entry glow: once, as soon as it shows
        if ((phase == Atmo.HEAT || phase == Atmo.DESCENT) && !entryHeatSaid && f.entryHeat >= CalloutConfig.ENTRY_HEAT_START) {
            if (say(VoiceLine.EntryHeat)) entryHeatSaid = true
        }

        // the cloud deck sets the turbulence in the descent: the deck first, the turbulence later
        if (phase == Atmo.DESCENT) {
            val turbulence = f.turbulence
            if (turbulence > 0f) {
                if (deckSince.isNaN()) {
                    deckSince = now
                    say(VoiceLine.CloudDeck)
                } else if (!turbulenceSaid && now - deckSince >= CalloutConfig.TURBULENCE_DELAY && turbulence >= CalloutConfig.TURBULENCE_MIN) {
                    turbulenceSaid = true
                    say(VoiceLine.Turbulence)
                }
            }
        }
    }

    // ================================================================== landing (`updateSurface`, :307)

    private fun altitude(t: CalloutTerrain, x: Double, y: Double, z: Double): Double = t.altitude(x, y, z)

    /** Ground height at [p] + a·t1 + b·t2 along up (Roblox `probe.Y - surface:getAltitude(probe)`). */
    private fun groundAt(
        t: CalloutTerrain, f: CalloutFrame, px: Double, py: Double, pz: Double, a: Double, b: Double,
    ): Double {
        val x = px + a * t1x + b * t2x
        val y = py + a * t1y + b * t2y
        val z = pz + a * t1z + b * t2z
        return x * f.upX + y * f.upY + z * f.upZ - altitude(t, x, y, z)
    }

    private var t1x = 1.0
    private var t1y = 0.0
    private var t1z = 0.0
    private var t2x = 0.0
    private var t2y = 0.0
    private var t2z = 1.0

    /** Two tangents to the local vertical; for up = +Y they are +X and +Z, as the Roblox scene. */
    private fun tangents(f: CalloutFrame) {
        val ux = f.upX.toDouble()
        val uy = f.upY.toDouble()
        val uz = f.upZ.toDouble()
        // t1 = X projected off up (or Z when up is near X)
        var ax = 1.0
        var az = 0.0
        if (kotlin.math.abs(ux) > 0.9) {
            ax = 0.0
            az = 1.0
        }
        val d = ax * ux + az * uz
        var x = ax - d * ux
        var y = -d * uy
        var z = az - d * uz
        val l = sqrt(x * x + y * y + z * z)
        x /= l; y /= l; z /= l
        t1x = x; t1y = y; t1z = z
        // t2 = t1 x up, oriented so up = +Y gives +Z
        t2x = t1y * uz - t1z * uy
        t2y = t1z * ux - t1x * uz
        t2z = t1x * uy - t1y * ux
        if (uy > 0.9 && t2z < 0) {
            t2x = -t2x; t2y = -t2y; t2z = -t2z
        }
    }

    /**
     * First ground contact along the straight path and the impact speed by the crash rule: the sink
     * rate or half the speed into the slope (`predictImpact`, :282). Writes [hitTime] (NaN = none)
     * and [hitImpact].
     */
    fun predictImpact(f: CalloutFrame, t: CalloutTerrain, height: Double) {
        var previousT = 0.0
        var previousH = height
        val c = f.clearance.toDouble()
        for (step in CalloutConfig.LOOKAHEAD) {
            val s = step.toDouble()
            val h = altitude(t, f.posX + f.velX * s, f.posY + f.velY * s, f.posZ + f.velZ * s) - c
            if (h <= 0.0) {
                val share = if (previousH - h > 1e-3) clamp((previousH / (previousH - h)).toFloat(), 0f, 1f).toDouble() else 1.0
                val hitT = previousT + (s - previousT) * share
                val hx = f.posX + f.velX * hitT
                val hy = f.posY + f.velY * hitT
                val hz = f.posZ + f.velZ * hitT
                tangents(f)
                // the slope at the contact from four neighbours, 2 m each way
                val dx = groundAt(t, f, hx, hy, hz, 2.0, 0.0) - groundAt(t, f, hx, hy, hz, -2.0, 0.0)
                val dz = groundAt(t, f, hx, hy, hz, 0.0, 2.0) - groundAt(t, f, hx, hy, hz, 0.0, -2.0)
                var nx = -dx * t1x + 4.0 * f.upX - dz * t2x
                var ny = -dx * t1y + 4.0 * f.upY - dz * t2y
                var nz = -dx * t1z + 4.0 * f.upZ - dz * t2z
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= nl; ny /= nl; nz /= nl
                val sink = -(f.velX * f.upX + f.velY * f.upY + f.velZ * f.upZ)
                val into = -(f.velX * nx + f.velY * ny + f.velZ * nz) * 0.5
                hitTime = hitT.toFloat()
                hitImpact = max(sink.toDouble(), into).toFloat()
                return
            }
            previousT = s
            previousH = h
        }
        hitTime = Float.NaN
        hitImpact = 0f
    }

    private fun updateSurface(dt: Float, f: CalloutFrame, now: Double) {
        val t = f.terrain
        if (!f.surfaceActive || t == null || f.guiding || f.controlled) {
            hitTime = Float.NaN
            airTime = 0f
            return
        }
        val safe = CalloutConfig.SAFE_LANDING_SPEED
        // height above the touchdown point (ship centre minus gear)
        val height = altitude(t, f.posX, f.posY, f.posZ) - f.clearance
        val vUp = f.velX * f.upX + f.velY * f.upY + f.velZ * f.upZ
        val sink = -vUp
        val hx = f.velX - vUp * f.upX
        val hy = f.velY - vUp * f.upY
        val hz = f.velZ - vUp * f.upZ
        val horizontal = sqrt(hx * hx + hy * hy + hz * hz)

        // touchdown after a while in the air; the sink rate of the frame before
        if (f.landed) {
            if (airTime >= CalloutConfig.TOUCHDOWN_AIR_TIME) {
                say(if (prevSink > safe * CalloutConfig.HARD_LANDING_SHARE) VoiceLine.HullImpact else VoiceLine.Touchdown)
            }
            airTime = 0f
            hitTime = Float.NaN
            return
        }
        airTime += dt
        prevSink = sink

        // look-ahead, throttled
        terrainClock -= dt
        if (terrainClock <= 0f) {
            terrainClock = CalloutConfig.TERRAIN_INTERVAL
            predictImpact(f, t, height)
        }

        // ground warnings, the most urgent first
        var alarm: VoiceLine? = null
        val ht = hitTime
        if (!ht.isNaN() && hitImpact > safe * CalloutConfig.DANGER_SHARE) {
            alarm = when {
                ht <= CalloutConfig.IMPACT_TIME -> VoiceLine.ImpactImminent
                ht <= CalloutConfig.PULL_UP_TIME && sink > 3f -> VoiceLine.PullUp
                ht <= CalloutConfig.TERRAIN_AHEAD_TIME && sink <= 3f -> VoiceLine.TerrainAhead
                else -> null
            }
        }
        if (alarm == null && sink > safe * CalloutConfig.SINK_RATE_SHARE && height / sink < CalloutConfig.SINK_RATE_TIME) {
            alarm = VoiceLine.SinkRate
        }
        if (alarm == null && height < CalloutConfig.CLOSE_HEIGHT && horizontal > CalloutConfig.CLOSE_SPEED && sink < 4f &&
            airTime >= CalloutConfig.CLOSE_AIR_TIME
        ) {
            alarm = VoiceLine.Terrain
        }
        if (alarm != null) {
            say(alarm)
            lastWarningAt = now
        }
        // no altitude or approach calls right after a warning
        val calm = alarm == null && now - lastWarningAt > 1.5

        // approach: armed once the ship was high enough again
        if (height > CalloutConfig.APPROACH_REARM_HEIGHT) {
            flareArmed = true
            tooFastArmed = true
        }
        if (calm && sink > 2f) {
            if (tooFastArmed && height < CalloutConfig.TOO_FAST_HEIGHT && horizontal > safe * CalloutConfig.TOO_FAST_SHARE) {
                if (say(VoiceLine.TooFastToLand)) tooFastArmed = false
            } else if (flareArmed && height < CalloutConfig.FLARE_HEIGHT && sink > CalloutConfig.FLARE_SINK) {
                if (say(VoiceLine.Flare)) flareArmed = false
            }
        }

        // altitude calls: every step passed is disarmed, only the lowest is spoken
        val steps = CalloutConfig.ALTITUDES
        for (i in steps.indices) if (height > steps[i] * CalloutConfig.ALTITUDE_REARM + 5f) armed[i] = true
        var due = -1
        for (i in steps.indices) {
            if (armed[i] && height <= steps[i]) {
                armed[i] = false
                if (sink > CalloutConfig.ALTITUDE_MIN_SINK) due = i
            }
        }
        if (due >= 0 && calm) say(CalloutConfig.ALTITUDE_LINES[due])
    }

    /** Altitude step [i] is armed (tests). */
    fun altitudeArmed(i: Int): Boolean = armed[i]

    // ================================================================== flight (`updateFlight`, :412)

    private fun updateFlight(f: CalloutFrame, now: Double) {
        // gear (automatic): the change must hold
        if (f.hasGear) {
            steady(F_GEAR, f.gearDown, CalloutConfig.GEAR_HOLD, now)
            if (steadyChanged) say(if (steadyValue) VoiceLine.GearDown else VoiceLine.GearUp)
        }
        if (!f.hasThrust) return

        // cruise: ended while the pilot still wants speed = a body's or station's limit choked it
        val cruising = f.cruising
        if (rawCruiseKnown && rawCruise && !cruising) {
            val top = if (f.topSpeed > 0f) f.topSpeed else Float.POSITIVE_INFINITY
            cruiseForced = f.flightAssist && !f.boosting && f.throttle > 0.3f && f.speedLimit <= top * 1.2f
        }
        rawCruise = cruising
        rawCruiseKnown = true
        steady(F_CRUISE, cruising, 0.5f, now)
        if (steadyChanged) {
            say(
                when {
                    steadyValue -> VoiceLine.CruiseOn
                    cruiseForced -> VoiceLine.CruiseDropped
                    else -> VoiceLine.CruiseOff
                },
            )
        }

        // boost ready again
        val cooldown = f.boostCooldown
        if (!boostCooldown.isNaN() && boostCooldown > 0f && cooldown <= 0f) say(VoiceLine.BoostReady)
        boostCooldown = cooldown
    }

    // ================================================================== systems (`updateSystems`, :453)

    private fun updateSystems(f: CalloutFrame, now: Double) {
        if (f.hasHeat) {
            // heat: the stages are ShipWarnings' (own alarm); "Heat rising" before, "Heat normal" after
            val heat = f.heat
            val phase = f.heatPhase
            val previous = heatPhase
            heatPhase = phase
            if (previous >= 0 && phase > previous) say(if (phase >= 2) VoiceLine.OverheatCritical else VoiceLine.OverheatStage1)
            if (phase > 0) {
                heatWarned = true
            } else if (heat >= CalloutConfig.HEAT_RISING) {
                if (!heatRisingSaid && !lastHeat.isNaN() && heat > lastHeat) {
                    if (say(VoiceLine.HeatRising)) {
                        heatRisingSaid = true
                        heatWarned = true
                    }
                }
            } else if (heat < CalloutConfig.HEAT_NORMAL) {
                heatRisingSaid = false
                if (heatWarned && say(VoiceLine.HeatNormal)) heatWarned = false
            }
            lastHeat = heat
        }

        if (f.hasFuel) {
            // fuel: each threshold once, re-armed after refuelling
            val fuel = f.fuel
            if (fuel <= CalloutConfig.FUEL_CRITICAL) {
                if (!fuelCriticalSaid && say(VoiceLine.FuelCritical)) {
                    fuelCriticalSaid = true
                    fuelLowSaid = true
                }
            } else if (fuel <= CalloutConfig.FUEL_LOW) {
                if (!fuelLowSaid && say(VoiceLine.FuelLow)) fuelLowSaid = true
            }
            if (fuel > CalloutConfig.FUEL_CRITICAL + CalloutConfig.FUEL_REARM) fuelCriticalSaid = false
            if (fuel > CalloutConfig.FUEL_LOW + CalloutConfig.FUEL_REARM) fuelLowSaid = false
        }

        if (f.hasScoop) {
            // scooping at a star: the start, and a full tank (then the scooping ends)
            val scooping = f.scooping
            steady(F_SCOOP, scooping, 0.5f, now)
            if (steadyChanged && steadyValue) say(VoiceLine.ScoopStarted)
            if (wasScoopingKnown && wasScooping && !scooping && f.hasFuel && f.fuel >= 0.999f) say(VoiceLine.TankFull)
            wasScooping = scooping
            wasScoopingKnown = true
        }

        if (f.hasGravity) {
            // gravity influence: only in space (on the ground it always applies)
            steady(F_GRAVITY, f.inInfluence, 1f, now)
            val space = !f.hasAtmosphere || f.atmoState == Atmo.SPACE
            if (steadyChanged && !f.surfaceActive && space) say(if (steadyValue) VoiceLine.GravityEntered else VoiceLine.GravityLeft)
        }

        if (f.hasG) {
            // G-load just before grey- or redout
            val strain = f.strainG
            val start = CalloutConfig.BLACKOUT_START_G
            if (strain >= start * CalloutConfig.HIGH_G_SHARE) {
                if (!gSaid) {
                    val line = if (f.redout >= CalloutConfig.REDOUT_PUSH) VoiceLine.Redout else VoiceLine.HighG
                    if (say(line)) gSaid = true
                }
            } else if (strain < start * CalloutConfig.G_REARM_SHARE) {
                gSaid = false
            }
        }
    }

    // ================================================================== speed (`updateSpeed`, :538)

    private fun updateSpeed(f: CalloutFrame, now: Double) {
        val limit = if (f.hasThrust) f.rawSpeedLimit else Float.POSITIVE_INFINITY
        val speed = f.speed
        if (limit == Float.POSITIVE_INFINITY || f.controlled || (f.hasWarp && f.warpActive)) {
            steady(F_OVERSPEED, false, 0f, now)
            return
        }
        val over = speed > limit * CalloutConfig.OVERSPEED_SHARE && speed - limit > CalloutConfig.OVERSPEED_MARGIN
        steady(F_OVERSPEED, over, CalloutConfig.SPEED_HOLD, now)
        if (steadyValue) {
            // while it lasts the line repeats at its cooldown; in a station's zone the station speaks
            say(if (f.stationLock) VoiceLine.ControlReduceSpeed else VoiceLine.Overspeed)
        }
        if (speed < limit * CalloutConfig.APPROACH_REARM_SHARE) approachArmed = true
        val top = if (f.topSpeed > 0f) f.topSpeed else Float.POSITIVE_INFINITY
        if (approachArmed && !over && !f.cruising && limit < top && speed > limit * CalloutConfig.APPROACH_SHARE) {
            if (say(VoiceLine.ApproachingSpeedLimit)) approachArmed = false
        }
    }

    // ================================================================== collision (`updateCollision`, :569)

    private fun updateCollision(dt: Float, f: CalloutFrame) {
        collisionClock -= dt
        if (collisionClock > 0f) return
        collisionClock = CalloutConfig.COLLISION_INTERVAL
        val contacts = f.contacts ?: return
        val speed = f.speed
        if (f.controlled || f.surfaceActive || speed < CalloutConfig.COLLISION_SPEED) return
        val horizon = CalloutConfig.COLLISION_TIME
        val time = contacts.timeToContact(f.posX, f.posY, f.posZ, f.velX, f.velY, f.velZ, f.probeRadius, horizon, normal)
        if (time >= 0f && time <= horizon && -(f.velX * normal.x + f.velY * normal.y + f.velZ * normal.z) >= CalloutConfig.COLLISION_SPEED) {
            say(VoiceLine.CollisionAlert)
        }
    }

    // ================================================================== docking (`updateDocking`, :609)

    private fun updateDocking(f: CalloutFrame, now: Double) {
        val state = f.dockState
        val previous = dockState
        dockState = state
        if (previous != DockState.NONE && state != previous && state == DockState.REQUESTING) {
            say(VoiceLine.DockingRequestSent)
            requestSince = now
            standBySaid = false
        }
        if (state == DockState.REQUESTING && !requestSince.isNaN() && !standBySaid && now - requestSince >= CalloutConfig.STAND_BY_AFTER) {
            standBySaid = true
            say(VoiceLine.ControlRequestReceived)
        }
    }

    // ================================================================== work (`updateWork`, :630)

    private fun updateWork(f: CalloutFrame, now: Double) {
        if (f.hasSurvey) {
            steady(F_SCAN, f.scanning, 0.2f, now)
            if (steadyChanged && steadyValue && f.scanProgress < 0.15f) say(VoiceLine.ScanStarted)
        }
        if (f.hasMining) {
            steady(F_DEPOSIT, f.depositInRange, 0.5f, now)
            if (steadyChanged && steadyValue) say(VoiceLine.DepositDetected)
        }
    }

    /** Seconds since [line] was last said by the callouts, NaN when never. */
    fun saidAgo(line: VoiceLine): Double {
        val l = last[line.ordinal]
        return if (l.isNaN()) Double.NaN else clock.now - l
    }

    private companion object {
        const val F_GEAR = 0
        const val F_CRUISE = 1
        const val F_SCOOP = 2
        const val F_GRAVITY = 3
        const val F_OVERSPEED = 4
        const val F_SCAN = 5
        const val F_DEPOSIT = 6
        const val FLAGS = 7
    }
}
