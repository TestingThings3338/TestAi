package com.starwake.core.station

import com.starwake.core.content.StationModel
import kotlin.math.atan2
import kotlin.math.floor

/**
 * One station (or build site) in the world: the runtime state ShipStation keeps per entry, on top
 * of its shared [model]. Spin angles of the rotating groups, blinkers, pad lamps, PAPI lamps, the
 * accent colour and, for a site, which build stages stand. All in station space; the caller places
 * the station in the world (StationPlacement) and passes positions in station space.
 *
 * [update] runs once per frame; nothing allocates. The lamp table ([lamps], 4 floats per slot:
 * sRGB r, g, b and opacity, a negative value = "as built") changes only when a lamp switches, and
 * then [lampVersion] counts up, so the renderer uploads it only on change, as Roblox writes parts
 * only on change.
 */
class StationInstance(val model: StationModel, seed: Int = 0) {
    /** Seconds since creation (ShipStation's clock). */
    var clock = 0.0
        private set

    /** Spin multiplier for every group: 1, or 0 for a build site (nothing turns until it is finished). */
    var spin = if (model.isSite) 0f else 1f

    /** Current angle (radians, 0..2pi) per group; group 0 never turns. */
    val angles = FloatArray(model.groups.size)

    /** Where each group is now (rotation about its centre), and which groups' colliders are solid. */
    val frames = GroupFrames(model.groups.size)

    /** Lamp slot values: [slot * 4 + 0..3] = sRGB r, g, b (0..1) and opacity; -1 keeps the built value. */
    val lamps = FloatArray(LAMP_SLOTS * 4)

    /** Counts up whenever [lamps] changes. */
    var lampVersion = 0
        private set

    /** Per group: x = drawn solid (1/0), y = hologram strength (0 = none); [g * 4]. Counts with [groupVersion]. */
    val groupModes = FloatArray(model.groups.size * 4)
    var groupVersion = 0
        private set

    private val padState = IntArray(model.pads.size + 1)
    private val padShown = IntArray(model.pads.size + 1) { -1 }
    private val blinkOn = BooleanArray(model.lamps.size + 1)
    private val blinkPhase = FloatArray(model.lamps.size + 1)
    private val papiHigh = IntArray(model.lamps.size + 1) { -1 }

    /** Built stages of a site (0..4) and whether the next one is being built. */
    var siteBuilt = 4
        private set
    var siteBuilding = false
        private set

    init {
        require(model.lamps.size < LAMP_SLOTS) { "${model.type}: ${model.lamps.size} lamp slots, the shader holds ${LAMP_SLOTS - 1}" }
        lamps.fill(-1f)
        // a random start angle per spinning group, as Roblox; a site's groups are its build stages and never turn
        if (!model.isSite) for (g in 1 until model.groups.size) angles[g] = (hash(seed, g * 7919) * TWO_PI).toFloat()
        for (s in 1..model.lamps.size) {
            val l = model.lamps[s - 1]
            blinkOn[s] = true
            blinkPhase[s] = if (l.phase >= 0f) l.phase else (hash(seed, s * 104729) * l.period).toFloat()
        }
        for (g in model.groups.indices) {
            groupModes[g * 4] = 1f
            groupModes[g * 4 + 1] = 0f
        }
        for (p in model.pads) setPadState(p.index, PAD_IDLE)
        if (model.isSite) setSiteStages(0, false)
        placeGroups()
        update(0f, Float.NaN, 0f, 0f)
    }

    /**
     * Advances the clock by [dt] seconds: spins the groups and runs the lights (ShipStation.place
     * and updateLights). ([shipX], [shipY], [shipZ]) is the player's ship in station space for the
     * PAPI lamps; pass NaN for x when there is none (the PAPI lamps keep their colour).
     */
    fun update(dt: Float, shipX: Float, shipY: Float, shipZ: Float) {
        clock += dt
        if (spin != 0f && dt != 0f) {
            for (g in 1 until model.groups.size) {
                var a = angles[g] + model.groups[g].radiansPerSecond * spin * dt
                a -= (floor(a / TWO_PI_F) * TWO_PI_F)
                angles[g] = a
            }
            placeGroups()
        }
        updateLights(shipX, shipY, shipZ)
    }

    private fun placeGroups() {
        for (g in 1 until model.groups.size) {
            val gr = model.groups[g]
            frames.set(g, gr.axisX, gr.axisY, gr.axisZ, angles[g], gr.centreX, gr.centreY, gr.centreZ)
        }
    }

    private fun updateLights(shipX: Float, shipY: Float, shipZ: Float) {
        val c = clock
        for (s in 1..model.lamps.size) {
            val l = model.lamps[s - 1]
            when (l.kind) {
                StationModel.LAMP_BLINK -> {
                    val period = l.period.toDouble()
                    var t = (c - blinkPhase[s]) % period
                    if (t < 0) t += period
                    val on = t < period * l.duty
                    if (on != blinkOn[s]) {
                        blinkOn[s] = on
                        // off: Transparency = max(base, BlinkTransparency)
                        val base = 1f - l.a / 255f
                        write(s, -1f, -1f, -1f, if (on) -1f else 1f - maxOf(base, BLINK_TRANSPARENCY))
                    }
                }
                StationModel.LAMP_PAPI -> if (!shipX.isNaN()) {
                    val oy = shipY - l.papiY
                    val oz = shipZ - l.papiZ
                    // elevation seen from the lamp, looking along the station's -Z (where approaches come from)
                    val angle = atan2(oy, maxOf(-oz, 1e-3f))
                    val high = if (angle >= l.papiThreshold) 1 else 0
                    if (high != papiHigh[s]) {
                        papiHigh[s] = high
                        if (high == 1) write(s, PAPI_WHITE[0], PAPI_WHITE[1], PAPI_WHITE[2], -1f)
                        else write(s, PAPI_RED[0], PAPI_RED[1], PAPI_RED[2], -1f)
                    }
                }
            }
        }
        val padBlink = (c % PAD_BLINK_PERIOD) < PAD_BLINK_ON
        for (p in model.pads) {
            val st = padState[p.index]
            val shown = if (st == PAD_ASSIGNED && !padBlink) PAD_DIM else st
            if (shown == padShown[p.index] || p.lampSlot == 0) continue
            padShown[p.index] = shown
            val col = when (shown) {
                PAD_ASSIGNED, PAD_DIM -> PAD_ASSIGNED_COLOR
                PAD_CLAMPED -> PAD_CLAMPED_COLOR
                else -> PAD_IDLE_COLOR
            }
            write(p.lampSlot, col[0], col[1], col[2], if (shown == PAD_DIM) 1f - PAD_DIM_TRANSPARENCY else 1f)
        }
    }

    private fun write(slot: Int, r: Float, g: Float, b: Float, a: Float) {
        val o = slot * 4
        if (lamps[o] == r && lamps[o + 1] == g && lamps[o + 2] == b && lamps[o + 3] == a) return
        lamps[o] = r; lamps[o + 1] = g; lamps[o + 2] = b; lamps[o + 3] = a
        lampVersion++
    }

    /** Pad [index]'s lamp state: PAD_IDLE, PAD_ASSIGNED (blinks) or PAD_CLAMPED (ShipStation.setPadState). */
    fun setPadState(index: Int, state: Int) {
        if (index !in 1 until padState.size) return
        padState[index] = state
        padShown[index] = -1
    }

    fun padState(index: Int): Int = if (index in 1 until padState.size) padState[index] else PAD_IDLE

    /** Whether a blinker slot is lit right now (diagnostics and tests). */
    fun isLit(slot: Int): Boolean = blinkOn[slot]

    /** Whether PAPI slot [slot] shows white (1), red (0) or has not been evaluated (-1). */
    fun papiState(slot: Int): Int = papiHigh[slot]

    /**
     * The station's accent colour (ShipStationLore: sRGB bytes) for every part with Accent = true.
     * Ignored by models without accent parts.
     */
    fun setAccent(r: Int, g: Int, b: Int) {
        val s = model.accentSlot
        if (s == 0) return
        write(s, r / 255f, g / 255f, b / 255f, -1f)
    }

    /**
     * A build site's stages (ShipStation.showStages): stages 1..[built] stand finished and solid,
     * stage built + 1 shows as the orange hologram (stronger while [building]), later ones are
     * hidden. No effect on stations.
     */
    fun setSiteStages(built: Int, building: Boolean) {
        if (!model.isSite) return
        val b = built.coerceIn(0, model.groups.size - 1)
        if (b == siteBuilt && building == siteBuilding && groupVersion > 0) return
        siteBuilt = b
        siteBuilding = building
        for (g in 1 until model.groups.size) {
            val o = g * 4
            when {
                g <= b -> {
                    groupModes[o] = 1f; groupModes[o + 1] = 0f
                    frames.solid[g] = true
                }
                g == b + 1 -> {
                    groupModes[o] = 0f; groupModes[o + 1] = if (building) 1f - HOLO_BUILDING else 1f - HOLO_WAITING
                    frames.solid[g] = false
                }
                else -> {
                    groupModes[o] = 0f; groupModes[o + 1] = 0f
                    frames.solid[g] = false
                }
            }
        }
        groupVersion++
    }

    /** Group [g]'s current transform as 3 rows of (rotation | translation), station space, into [out] at [offset]. */
    fun groupRows(g: Int, out: FloatArray, offset: Int) {
        val m = frames.rot
        val o = g * 9
        val cx = frames.centre[g * 3]
        val cy = frames.centre[g * 3 + 1]
        val cz = frames.centre[g * 3 + 2]
        for (row in 0 until 3) {
            val a = m[o + row * 3]
            val b = m[o + row * 3 + 1]
            val c = m[o + row * 3 + 2]
            val cr = if (row == 0) cx else if (row == 1) cy else cz
            out[offset + row * 4] = a
            out[offset + row * 4 + 1] = b
            out[offset + row * 4 + 2] = c
            // p' = R (p - c) + c
            out[offset + row * 4 + 3] = cr - (a * cx + b * cy + c * cz)
        }
    }

    companion object {
        /** Lamp slots the shader holds (slot 0 = none). */
        const val LAMP_SLOTS = 64
        const val PAD_IDLE = 0
        const val PAD_ASSIGNED = 1
        const val PAD_CLAMPED = 2

        /** Assigned and blinking dim: shown with transparency 0.7. */
        const val PAD_DIM = 3

        /** ShipConfig.Stations: pad lamp colours (sRGB 0..1), blink, and blinker transparency. */
        val PAD_IDLE_COLOR = floatArrayOf(70 / 255f, 120 / 255f, 160 / 255f)
        val PAD_ASSIGNED_COLOR = floatArrayOf(255 / 255f, 170 / 255f, 60 / 255f)
        val PAD_CLAMPED_COLOR = floatArrayOf(120 / 255f, 220 / 255f, 150 / 255f)
        const val PAD_BLINK_PERIOD = 0.8
        const val PAD_BLINK_ON = 0.5
        const val PAD_DIM_TRANSPARENCY = 0.7f
        const val BLINK_TRANSPARENCY = 0.85f

        /** ShipStation PAPI_WHITE / PAPI_RED. */
        val PAPI_WHITE = floatArrayOf(240 / 255f, 244 / 255f, 255 / 255f)
        val PAPI_RED = floatArrayOf(255 / 255f, 46 / 255f, 36 / 255f)

        /** ShipStation HOLO_WAITING / HOLO_BUILDING (transparency of the next stage's hologram). */
        const val HOLO_WAITING = 0.55f
        const val HOLO_BUILDING = 0.2f

        /** HOLO_COLOR (255, 122, 0), sRGB 0..1. */
        val HOLO_COLOR = floatArrayOf(1f, 122 / 255f, 0f)

        private const val TWO_PI = Math.PI * 2.0
        private const val TWO_PI_F = (Math.PI * 2.0).toFloat()

        /** Deterministic 0..1 from a seed and a key (replaces Roblox's math.random for start angles and phases). */
        internal fun hash(seed: Int, key: Int): Double {
            var h = seed * 0x27d4eb2d + key * 0x165667b1
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6d
            h = h xor (h ushr 12)
            h *= 0x297a2d39
            h = h xor (h ushr 15)
            return (h.toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }
}
