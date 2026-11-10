package com.starwake.core.audio

import com.starwake.core.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automatic callouts of `09` 2.14 as pure logic over scripted frames: the debounce, the start
 * and pause read-in, the altitude calls with their re-arming, the ground warnings and the impact
 * prediction against a synthetic height field, gear, cruise, boost, heat, fuel, scooping, gravity,
 * G-load, speed, collision, docking (with the DECISIONS 35 fix), jumps, entry and work, and the
 * gating table of 4.8 (absent sources stay silent).
 */
class CalloutsTest {
    private val out = FakeAudioOut().also { it.loadAllWith(0.6f) }
    private val clock = AudioClock()
    private val voice = Voice(out, clock)
    private val callouts = Callouts(voice, clock)
    private val f = CalloutFrame()
    private val dt = 1f / 60f

    /** Lines in the order the callouts said them (accepted by the voice). */
    private val said = ArrayList<VoiceLine>()

    private fun step(n: Int = 1) {
        repeat(n) {
            clock.now += dt
            val before = HashMap<VoiceLine, Double>()
            for (l in VoiceLine.ALL) before[l] = callouts.saidAgo(l)
            callouts.update(dt, f)
            for (l in VoiceLine.ALL) {
                val a = callouts.saidAgo(l)
                if (!a.isNaN() && a == 0.0 && (before[l]!!.isNaN() || before[l]!! > 0.0)) said += l
            }
            voice.update()
            out.advance(dt)
            // the frame moves along its velocity
            f.posX += f.velX * dt
            f.posY += f.velY * dt
            f.posZ += f.velZ * dt
        }
    }

    private fun seconds(s: Float) = step((s / dt).toInt())

    private fun pastGrace() = seconds(CalloutConfig.START_GRACE + 0.1f)

    // ================================================================== debounce, grace, pauses

    @Test
    fun theStartGraceAndTheGearDebounce() {
        f.hasGear = true
        f.gearDown = false
        seconds(1f)
        f.gearDown = true
        seconds(2f) // held 1 s, but inside the 6 s grace: silent
        assertTrue(said.isEmpty(), "grace: $said")
        pastGrace()
        // a blip shorter than GearHold is ignored
        f.gearDown = false
        seconds(0.5f)
        f.gearDown = true
        seconds(2f)
        assertTrue(said.isEmpty(), "blip: $said")
        f.gearDown = false
        seconds(0.95f)
        assertTrue(said.isEmpty())
        seconds(0.1f)
        assertEquals(listOf(VoiceLine.GearUp), said)
        // each line has its own cooldown (8 s): down is new, up again 2.4 s later is not said
        f.gearDown = true
        seconds(1.2f)
        assertEquals(listOf(VoiceLine.GearUp, VoiceLine.GearDown), said)
        f.gearDown = false
        seconds(1.2f)
        assertEquals(listOf(VoiceLine.GearUp, VoiceLine.GearDown), said, "GearUp within its cooldown")
        seconds(10f)
        f.gearDown = true
        seconds(1.2f)
        assertEquals(listOf(VoiceLine.GearUp, VoiceLine.GearDown, VoiceLine.GearDown), said, "after the cooldown")
    }

    @Test
    fun aPauseReadsInSilentlyAndGrantsOneAndAHalfSeconds() {
        f.hasGear = true
        pastGrace()
        // no update for more than FRAME_GAP (the decks): the trackers reset, 1.5 s grace
        clock.now += 3.0
        f.gearDown = true
        step()
        seconds(1.2f) // gear "changed" during the pause: read in silently, no edge
        assertTrue(said.isEmpty(), "$said")
        assertTrue(callouts.graceUntil > clock.now - 1.2 - 0.01, "grace after the pause")
        f.gearDown = false
        seconds(1.1f)
        assertEquals(listOf(VoiceLine.GearUp), said, "after the read-in, edges count again")
    }

    // ================================================================== landing block

    /** A flat ground at y = 0 (Roblox landing scene, up = +Y). */
    private val flat = CalloutTerrain { _, y, _ -> y }

    private fun land(height: Double, sink: Float, horizontal: Float = 0f) {
        f.surfaceActive = true
        f.terrain = flat
        f.posX = 0.0; f.posY = height; f.posZ = 0.0
        f.velX = horizontal; f.velY = -sink; f.velZ = 0f
        f.speed = kotlin.math.sqrt(sink * sink + horizontal * horizontal)
    }

    @Test
    fun altitudeCallsSayOnlyTheLowestAndRearmAbove() {
        land(800.0, 0f)
        pastGrace()
        // descend at 20 m/s from 800 m to 15 m: every step is crossed; each said when crossed
        f.velY = -20f
        while (f.posY > 15.0) step()
        assertEquals(listOf(VoiceLine.AltFiveHundred, VoiceLine.AltOneHundred, VoiceLine.AltFifty, VoiceLine.AltThirty, VoiceLine.AltTwenty), said)
        // a jump past several steps in one frame says only the lowest
        said.clear()
        f.velY = 0f
        f.posY = 120.0 // re-arms 10, 20, 30 (step*1.4 + 5 < 120), 50 (75), not 100 (145)
        step()
        assertTrue(callouts.altitudeArmed(5) && callouts.altitudeArmed(2) && !callouts.altitudeArmed(1))
        f.posY = 8.0
        f.velY = -5f
        step()
        assertEquals(listOf(VoiceLine.AltTen), said, "only the lowest")
        // climbing does not call; sinking slower than 1.5 m/s disarms without a call
        said.clear()
        f.posY = 200.0
        step()
        f.velY = -1f
        f.posY = 49.0
        step()
        assertTrue(said.isEmpty(), "sink 1 m/s: $said")
        assertFalse(callouts.altitudeArmed(2), "passed and disarmed")
    }

    @Test
    fun impactPredictionOnFlatGroundAndOnASlope() {
        land(100.0, 50f)
        f.clearance = 0f
        callouts.predictImpact(f, flat, 100.0)
        assertEquals(2f, callouts.hitTime, 1e-4f, "100 m at 50 m/s")
        assertEquals(50f, callouts.hitImpact, 1e-4f, "sink rate")
        // between two samples: linear interpolation (h 30 at 1.5 s ... -20 at 2 s: 1.8 s)
        land(105.0, 50f)
        f.clearance = 15f
        callouts.predictImpact(f, flat, 90.0)
        assertEquals(1.8f, callouts.hitTime, 1e-4f)
        // nothing within 5 s
        land(1000.0, 50f)
        callouts.predictImpact(f, flat, 1000.0)
        assertTrue(callouts.hitTime.isNaN())
        // a slope rising along +X (ground = 0.5 x): flying level into it at 60 m/s
        val slope = CalloutTerrain { x, y, _ -> y - 0.5 * x }
        f.posX = 0.0; f.posY = 50.0; f.posZ = 0.0
        f.velX = 60f; f.velY = 0f; f.velZ = 0f
        f.clearance = 0f
        callouts.predictImpact(f, slope, 50.0)
        // contact where 50 = 0.5 * 60 t: t = 1.667 (interpolated between 1.5 and 2)
        assertEquals(1.6667f, callouts.hitTime, 2e-3f)
        // normal = normalize(-dx, 4, 0) with dx = ground(+2) - ground(-2) = 2: (-2, 4, 0)/4.47; into = 60 x 2/4.47 x 0.5
        assertEquals((60.0 * 2.0 / kotlin.math.sqrt(20.0) * 0.5).toFloat(), callouts.hitImpact, 1e-3f)
    }

    @Test
    fun impactImminentWithinOneAndAHalfSecondOfAHardImpact() {
        land(400.0, 0f)
        pastGrace()
        // impact > 28.5 predicted within 1.2 s
        land(40.0, 40f)
        seconds(0.2f)
        assertTrue(VoiceLine.ImpactImminent in said, "$said")
        assertTrue(callouts.hitTime <= 1.2f && callouts.hitImpact > 28.5f)
    }

    @Test
    fun pullUpWhileSinkingAndTerrainAheadWhileLevel() {
        land(400.0, 0f)
        pastGrace()
        // ground in 3 s, sinking 30 m/s: PullUp
        land(90.0, 30f)
        seconds(0.15f)
        assertTrue(VoiceLine.PullUp in said, "$said")
        // level flight at 80 m/s into a 2:1 slope 2.7 s ahead: TerrainAhead (impact = half the speed into it, 35.8)
        said.clear()
        val wall = CalloutTerrain { x, y, _ -> if (x < 200.0) y else y - 2.0 * (x - 200.0) }
        f.terrain = wall
        f.posX = 0.0; f.posY = 30.0; f.posZ = 0.0
        f.velX = 80f; f.velY = 0f; f.velZ = 0f
        seconds(0.15f)
        assertTrue(VoiceLine.TerrainAhead in said, "$said hit ${callouts.hitTime} impact ${callouts.hitImpact}")
    }

    @Test
    fun sinkRateWhenTheGroundIsUnderEightSecondsAway() {
        land(400.0, 0f)
        pastGrace()
        land(260.0, 35f) // 35 > 34.2, 260 / 35 = 7.4 s
        seconds(0.15f)
        assertTrue(VoiceLine.SinkRate in said, "$said")
    }

    @Test
    fun terrainWhenLowAndFastAfterFourSecondsAirborne() {
        land(8.0, 0f, 120f)
        pastGrace()
        seconds(0.3f)
        assertTrue(VoiceLine.Terrain in said, "$said")
    }

    @Test
    fun touchdownOrHardImpactAfterTwoSecondsAirborne() {
        land(30.0, 3f)
        pastGrace()
        seconds(2.5f)
        f.landed = true
        step()
        assertEquals(VoiceLine.Touchdown, said.last())
        f.landed = false
        land(30.0, 30f) // > 22.8 = 0.6 x 38
        seconds(0.9f)
        f.landed = true
        step()
        assertFalse(said.last() == VoiceLine.HullImpact, "under 2 s airborne: nothing")
        f.landed = false
        land(300.0, 25f)
        seconds(2.2f)
        f.landed = true
        step()
        assertEquals(VoiceLine.HullImpact, said.last(), "hard landing")
    }

    // ================================================================== flight, systems

    @Test
    fun cruiseOnOffAndDroppedByALimit() {
        f.hasThrust = true
        f.topSpeed = 250f
        f.flightAssist = true
        f.throttle = 1f
        pastGrace()
        f.cruising = true
        seconds(0.6f)
        assertEquals(listOf(VoiceLine.CruiseOn), said)
        // the pilot pulls the throttle back: a normal off
        f.throttle = 0.2f
        f.cruising = false
        seconds(7f)
        assertEquals(listOf(VoiceLine.CruiseOn, VoiceLine.CruiseOff), said)
        // cruising again, then a speed limit chokes it while the pilot still wants speed
        f.throttle = 1f
        f.cruising = true
        seconds(7f)
        f.speedLimit = 260f // <= 1.2 x TopSpeed
        f.cruising = false
        seconds(1f)
        assertEquals(VoiceLine.CruiseDropped, said.last())
    }

    @Test
    fun boostReadyWhenTheCooldownRunsOut() {
        f.hasThrust = true
        pastGrace()
        f.boostCooldown = 3f
        seconds(1f)
        f.boostCooldown = 0f
        step()
        assertEquals(listOf(VoiceLine.BoostReady), said)
    }

    @Test
    fun heatSequenceRisingStagesNormal() {
        f.hasHeat = true
        f.heat = 0.3f
        pastGrace()
        f.heat = 0.66f
        step()
        f.heat = 0.7f
        step()
        assertEquals(listOf(VoiceLine.HeatRising), said, "rising past 0.65")
        f.heat = 0.85f
        f.heatPhase = 1
        step()
        f.heat = 1.0f
        f.heatPhase = 2
        step()
        assertEquals(listOf(VoiceLine.HeatRising, VoiceLine.OverheatStage1, VoiceLine.OverheatCritical), said)
        f.heatPhase = 0
        f.heat = 0.4f
        seconds(21f) // HeatNormal has a 20 s cooldown of its own only after it was said
        assertEquals(VoiceLine.HeatNormal, said.last())
    }

    @Test
    fun fuelThresholdsOnceEachWithRearm() {
        f.hasFuel = true
        f.fuel = 0.5f
        pastGrace()
        f.fuel = 0.19f
        step()
        f.fuel = 0.15f
        seconds(1f)
        assertEquals(listOf(VoiceLine.FuelLow), said, "once")
        f.fuel = 0.07f
        seconds(2f)
        assertEquals(listOf(VoiceLine.FuelLow, VoiceLine.FuelCritical), said)
        // refuel just above the thresholds (not past the re-arm margin): nothing new
        f.fuel = 0.22f
        step()
        f.fuel = 0.18f
        seconds(3f)
        assertEquals(2, said.size, "not re-armed below 0.25")
        f.fuel = 0.3f
        step()
        f.fuel = 0.19f
        seconds(3f)
        assertEquals(VoiceLine.FuelLow, said.last(), "re-armed above 0.25")
    }

    @Test
    fun scoopStartAndTankFull() {
        f.hasScoop = true
        f.hasFuel = true
        f.fuel = 0.5f
        pastGrace()
        f.scooping = true
        seconds(0.6f)
        assertEquals(listOf(VoiceLine.ScoopStarted), said)
        f.fuel = 1f
        f.scooping = false
        step()
        assertEquals(listOf(VoiceLine.ScoopStarted, VoiceLine.TankFull), said)
    }

    @Test
    fun gravityWellOnlyInSpace() {
        f.hasGravity = true
        pastGrace()
        f.inInfluence = true
        seconds(1.1f)
        assertEquals(listOf(VoiceLine.GravityEntered), said)
        // near the ground (the landing block) the influence calls are off
        f.surfaceActive = true
        f.inInfluence = false
        seconds(16f)
        assertEquals(listOf(VoiceLine.GravityEntered), said)
        f.surfaceActive = false
        f.inInfluence = true
        seconds(1.1f)
        f.inInfluence = false
        seconds(1.1f)
        assertEquals(VoiceLine.GravityLeft, said.last())
    }

    @Test
    fun gLoadHighGOrRedoutWithRearm() {
        f.hasG = true
        pastGrace()
        f.strainG = 6f // >= 5.85
        step()
        assertEquals(listOf(VoiceLine.HighG), said)
        f.strainG = 5f
        seconds(10f)
        f.strainG = 6f
        step()
        assertEquals(1, said.size, "not re-armed above 3.9 g")
        f.strainG = 3f
        step()
        f.strainG = 6.2f
        f.redout = 0.7f
        step()
        assertEquals(VoiceLine.Redout, said.last())
    }

    @Test
    fun overspeedHoldsRepeatsAndApproachingTheLimit() {
        f.hasThrust = true
        f.topSpeed = 400f
        f.rawSpeedLimit = 200f
        f.speed = 100f
        pastGrace()
        // approaching: > 0.93 x 200 while armed (armed below 150)
        f.speed = 190f
        step()
        assertEquals(listOf(VoiceLine.ApproachingSpeedLimit), said)
        // over: > 240 and > 215 for 0.6 s
        f.speed = 300f
        seconds(0.55f)
        assertEquals(1, said.size, "SpeedHold")
        seconds(0.1f)
        assertEquals(VoiceLine.Overspeed, said.last())
        seconds(6.1f)
        assertEquals(2, said.count { it == VoiceLine.Overspeed }, "repeats at its 6 s cooldown")
        // inside a station's zone traffic control speaks
        f.stationLock = true
        seconds(10.1f)
        assertEquals(VoiceLine.ControlReduceSpeed, said.last())
        // controlled (docking computer) or no raw limit: reset, silent
        said.clear()
        f.controlled = true
        seconds(12f)
        assertTrue(said.isEmpty(), "$said")
        f.controlled = false
        f.rawSpeedLimit = Float.POSITIVE_INFINITY
        seconds(12f)
        assertTrue(said.isEmpty(), "no raw limit: $said")
    }

    @Test
    fun collisionAlertFromTheContactsSource() {
        f.speed = 100f
        f.velZ = -100f
        var t = 2f
        f.contacts = CalloutContacts { _, _, _, _, _, _, _, _, normal: Vec3 ->
            normal.set(0f, 0f, 1f)
            t
        }
        pastGrace()
        assertTrue(VoiceLine.CollisionAlert in said)
        said.clear()
        // closing slower than 30 m/s, or too far, or controlled, or no source: silent
        t = 3f
        seconds(5f)
        assertTrue(said.isEmpty())
        t = 2f
        f.controlled = true
        seconds(5f)
        assertTrue(said.isEmpty())
        f.controlled = false
        f.contacts = null
        seconds(5f)
        assertTrue(said.isEmpty())
    }

    @Test
    fun dockingRequestAndTheFixedStandBy() {
        f.hasDocking = true
        f.dockState = DockState.FREE
        pastGrace()
        f.dockState = DockState.REQUESTING
        step()
        assertEquals(listOf(VoiceLine.DockingRequestSent), said)
        // DECISIONS 35: traffic control acknowledges at 0.8 s, before the 1.6 s grant
        seconds(0.85f)
        assertEquals(listOf(VoiceLine.DockingRequestSent, VoiceLine.ControlRequestReceived), said)
        f.dockState = DockState.GRANTED
        seconds(2f)
        assertEquals(2, said.size)
    }

    @Test
    fun jumpChargingCancelledArrival() {
        f.hasWarp = true
        f.warpState = WarpState.IDLE
        pastGrace()
        f.warpState = WarpState.ALIGN
        step()
        f.warpState = WarpState.IDLE
        seconds(3f)
        assertEquals(listOf(VoiceLine.JumpCharging, VoiceLine.JumpCancelled), said)
        f.warpState = WarpState.ALIGN
        step()
        f.warpState = WarpState.COUNTDOWN
        step()
        f.warpState = WarpState.TUNNEL
        f.warpTravelling = true
        f.hasGear = true
        f.gearDown = true
        seconds(6f) // quiet in the tunnel (the gear change is not said)
        f.warpState = WarpState.IDLE
        f.warpTravelling = false
        step()
        assertEquals(VoiceLine.JumpArrival, said.last(), "the arrival falls exactly on the end of the quiet")
        assertFalse(VoiceLine.GearDown in said)
    }

    @Test
    fun entryPhasesAndHeatAndTheCloudDeck() {
        f.hasAtmosphere = true
        f.atmoState = Atmo.SPACE
        f.atmoPhase = Atmo.NONE
        pastGrace()
        f.atmoState = Atmo.OTHER
        f.atmoPhase = Atmo.HEAT
        step()
        f.entryHeat = 0.3f
        step()
        f.atmoPhase = Atmo.DESCENT
        f.turbulence = 0.1f
        step()
        f.turbulence = 0.5f
        seconds(3.2f)
        f.atmoPhase = Atmo.LEVEL
        step()
        f.atmoState = Atmo.SURFACE
        f.atmoPhase = Atmo.NONE
        step()
        assertEquals(
            listOf(VoiceLine.EntryStarted, VoiceLine.EntryHeat, VoiceLine.CloudDeck, VoiceLine.Turbulence, VoiceLine.LevelOff),
            said,
        )
        f.atmoPhase = Atmo.CLIMB
        step()
        f.atmoPhase = Atmo.EMERGE
        seconds(1f)
        f.atmoState = Atmo.SPACE
        f.atmoPhase = Atmo.NONE
        step()
        assertEquals(listOf(VoiceLine.AscentStarted, VoiceLine.LeavingAtmosphere, VoiceLine.OrbitReached), said.takeLast(3))
    }

    @Test
    fun workScanAndDeposit() {
        f.hasSurvey = true
        f.hasMining = true
        pastGrace()
        f.scanning = true
        f.scanProgress = 0.05f
        seconds(0.3f)
        f.depositInRange = true
        seconds(0.6f)
        assertEquals(listOf(VoiceLine.ScanStarted, VoiceLine.DepositDetected), said)
    }

    // ================================================================== gating (4.8)

    @Test
    fun gatingTable() {
        f.hasGear = true
        f.hasThrust = true
        f.hasFuel = true
        f.fuel = 0.5f
        pastGrace()
        // crash: silent (and the warp state is tracked)
        f.crashing = true
        f.fuel = 0.05f
        f.gearDown = true
        seconds(2f)
        assertTrue(said.isEmpty(), "crash: $said")
        f.crashing = false
        seconds(1.4f)
        assertTrue(said.isEmpty(), "1.5 s grace after the quiet: $said")
        seconds(0.3f)
        assertEquals(listOf(VoiceLine.FuelCritical), said, "after the grace the persistent fuel tracker speaks")
        // on foot aboard: silent, the persistent trackers keep their state
        said.clear()
        f.onFoot = true
        f.gearDown = false
        seconds(3f)
        assertTrue(said.isEmpty())
        f.onFoot = false
        seconds(3f)
        assertTrue(said.isEmpty(), "gear read in silently after the quiet, fuel already said: $said")
        // absent sources stay silent whatever their fields say
        f.hasFuel = false
        f.fuel = 0f
        f.hasG = false
        f.strainG = 99f
        f.hasHeat = false
        f.heat = 9f
        f.heatPhase = 2
        seconds(3f)
        assertTrue(said.isEmpty(), "absent sources: $said")
    }
}
