package com.starwake.core.content

import com.starwake.core.math.PI_F
import com.starwake.core.math.TAU_F
import com.starwake.core.math.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural space stations: original, flat-shaded low-poly designs in the lit-vertex format of
 * [MeshBuilder] (pos xyz, normal xyz, material, shade). Local frame: metres, +Y up, the docking
 * entrance faces -Z (the forward axis of the engine). Every part is a closed solid, so faces can be
 * checked for outward orientation and the meshes stay correct with back-face culling.
 *
 *  0  Ring station (~1.8 km): a habitat torus on four spokes around a central spindle. The dock head
 *     on the front of the spindle carries a letterbox docking slot: a real recessed tunnel with guide
 *     lights and a glowing back wall, framed by lit edges and green corner lights. Tanks between the
 *     dock head and the hub, radiator fins, solar wings and an antenna mast at the rear.
 *  1  Outpost (~500 m): a vertical core tower with a docking-bay module in front, two habitat
 *     modules, a landing pad with lit edges on a truss arm, a radiator keel and a comms dish.
 *
 * All functions here are build-time / one-off helpers (they allocate); cache the results.
 */
object StationMesh {
    const val MODELS = 2
    const val RING_STATION = 0
    const val OUTPOST = 1

    // ---- material slots (indices into [palette] / [params])
    const val HULL = 0
    const val HULL_DARK = 1
    const val ACCENT = 2
    const val WINDOW = 3
    const val SOLAR = 4
    const val METAL = 5
    const val SLOT_GREEN = 6
    const val LIGHT_WHITE = 7
    const val BEACON = 8
    const val BAY_GLOW = 9
    const val MATERIALS = 10

    /** 10 x (r, g, b, roughness), linear RGB. Same layout as the lit shader's u_palette. */
    val palette: FloatArray = floatArrayOf(
        0.26f, 0.27f, 0.28f, 0.50f, // HULL: light grey hull plating
        0.085f, 0.09f, 0.10f, 0.60f, // HULL_DARK
        0.52f, 0.26f, 0.04f, 0.50f, // ACCENT: muted orange/yellow industrial markings
        0.32f, 0.20f, 0.085f, 0.20f, // WINDOW: dark glass, glows warm (emissive)
        0.022f, 0.045f, 0.13f, 0.16f, // SOLAR: dark blue, glossy
        0.50f, 0.52f, 0.55f, 0.32f, // METAL
        0.04f, 1.00f, 0.18f, 1.00f, // SLOT_GREEN
        1.00f, 0.96f, 0.88f, 1.00f, // LIGHT_WHITE
        1.00f, 0.03f, 0.02f, 1.00f, // BEACON: red
        0.50f, 0.86f, 0.80f, 1.00f, // BAY_GLOW: dock tunnel back wall
    )

    /** 10 x (metallic, emissive, 0, 0). Same layout as the lit shader's u_matParams. */
    val params: FloatArray = floatArrayOf(
        0.10f, 0f, 0f, 0f, // HULL
        0.35f, 0f, 0f, 0f, // HULL_DARK
        0.05f, 0f, 0f, 0f, // ACCENT
        0.50f, 1.6f, 0f, 0f, // WINDOW
        0.55f, 0f, 0f, 0f, // SOLAR
        1.00f, 0f, 0f, 0f, // METAL
        0.00f, 1.0f, 0f, 0f, // SLOT_GREEN (strong emissive washes to white after tonemapping)
        0.00f, 2.4f, 0f, 0f, // LIGHT_WHITE
        0.00f, 1.2f, 0f, 0f, // BEACON
        0.00f, 0.8f, 0f, 0f, // BAY_GLOW
    )

    fun build(model: Int): FloatArray = when (model) {
        RING_STATION -> buildRingStation()
        OUTPOST -> buildOutpost()
        else -> throw IllegalArgumentException("no station model $model")
    }

    /** Bounding radius (metres, around the local origin) of [build]'s mesh. */
    fun radius(model: Int): Float = when (model) {
        RING_STATION -> 915f
        OUTPOST -> 257f
        else -> throw IllegalArgumentException("no station model $model")
    }

    /** Docking port: position (local, metres) of the slot entrance and the outward direction a ship approaches along. */
    fun dockPort(model: Int, outPos: Vec3, outDir: Vec3) {
        when (model) {
            RING_STATION -> outPos.set(0f, 0f, R_FRONT)
            OUTPOST -> outPos.set(0f, 0f, O_FRONT)
            else -> throw IllegalArgumentException("no station model $model")
        }
        outDir.set(0f, 0f, -1f)
    }

    /**
     * Docking slot opening: [out] = (half width, half height, tunnel depth) in metres. The tunnel runs
     * from [dockPort]'s position for `depth` metres against the approach direction.
     */
    fun slotSize(model: Int, out: Vec3): Vec3 = when (model) {
        RING_STATION -> out.set(R_SLOT_W, R_SLOT_H, R_SLOT_DEPTH)
        OUTPOST -> out.set(O_SLOT_W, O_SLOT_H, O_SLOT_DEPTH)
        else -> throw IllegalArgumentException("no station model $model")
    }

    /**
     * Room in metres a docking hull keeps from the slot opening's edge on every side. The lit trim
     * reaches up to 0.3 x its thickness (1.5 m at the ring station) into the opening; the rest is
     * clearance for the slide-in.
     */
    const val SLOT_CLEARANCE = 3f

    /**
     * True when a hull [width] x [height] metres, centred and lined up on the dock axis (as auto-dock
     * parks it), fits [model]'s slot opening with [SLOT_CLEARANCE] to spare on every side. Its length
     * does not matter: a hull longer than the tunnel parks with its tail out of the entrance.
     * In Roblox every station's hangar takes every ship, so both slots are sized for the biggest hull
     * (MORVEN, 61.5 x 40.3 m); auto-dock, which flies without collision, refuses a hull that does not
     * fit. Allocation free.
     */
    fun slotFits(model: Int, width: Float, height: Float): Boolean {
        val sw: Float
        val sh: Float
        when (model) {
            RING_STATION -> { sw = R_SLOT_W; sh = R_SLOT_H }
            OUTPOST -> { sw = O_SLOT_W; sh = O_SLOT_H }
            else -> throw IllegalArgumentException("no station model $model")
        }
        return width * 0.5f + SLOT_CLEARANCE <= sw && height * 0.5f + SLOT_CLEARANCE <= sh
    }

    /**
     * The outpost's docking bay, the block that holds its slot: [out] = (half width, half height, z
     * of its back face) in local metres. It runs from the slot face ([dockPort]) back to that z, into
     * the core tower.
     */
    fun outpostBay(out: Vec3): Vec3 = out.set(O_BOX_W, O_BOX_H, O_BACK)

    /**
     * Landing pad: [outPos] = centre of the pad's top surface, [outUp] = its normal. Returns the usable
     * pad radius in metres, or 0 when the model has no pad (outputs then untouched).
     */
    fun landingPad(model: Int, outPos: Vec3, outUp: Vec3): Float {
        if (model != OUTPOST) return 0f
        outPos.set(PAD_X, PAD_TOP, 0f)
        outUp.set(0f, 1f, 0f)
        return PAD_INNER
    }

    /** Positions (local) of lights for glow sprites: navigation beacons, slot lights. x,y,z, r,g,b per light (a fresh copy). */
    fun lights(model: Int): FloatArray = when (model) {
        RING_STATION -> ringLights.copyOf()
        OUTPOST -> outpostLights.copyOf()
        else -> throw IllegalArgumentException("no station model $model")
    }

    // ================================================================== model 0: ring station

    private const val RING_R = 840f
    private const val RING_SEGS = 96

    /** Habitat ring cross-section (radial offset from [RING_R], z) walked counter-clockwise. */
    private val ringSection = floatArrayOf(
        60f, -24f, 60f, 24f, // outer face
        38f, 46f, -36f, 46f, // back-outer chamfer, back face
        -50f, 28f, -50f, -28f, // back-inner chamfer, inner face
        -36f, -46f, 38f, -46f, // front-inner chamfer, front face (then front-outer chamfer)
    )

    // dock head (front of the spindle); the slot takes the biggest hull (see [slotFits])
    private const val R_FRONT = -340f
    private const val R_SLOT_W = 50f
    private const val R_SLOT_H = 25f
    private const val R_SLOT_DEPTH = 110f
    private const val R_HEAD_R = 150f
    private const val R_HEAD_R2 = 172f
    private const val R_HEAD_BACK = -200f
    private val R_HEAD_APOTHEM = R_HEAD_R2 * cos(PI_F / 8f)

    private const val BEACON_R = 908f
    private const val GREEN_R = 0.22f
    private const val GREEN_G = 1.0f
    private const val GREEN_B = 0.40f

    private val ringLights: FloatArray = run {
        val l = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
            l.add(x); l.add(y); l.add(z); l.add(r); l.add(g); l.add(b)
        }
        // slot corner lights (green), in front of the frame
        for (sx in floatArrayOf(1f, -1f)) for (sy in floatArrayOf(1f, -1f)) {
            add(sx * (R_SLOT_W + 5f), sy * (R_SLOT_H + 5f), R_FRONT - 11.1f, GREEN_R, GREEN_G, GREEN_B)
        }
        // red beacons on the ring's outer rim (top, right, bottom, left) and the mast tip
        for (q in 0 until 4) {
            val a = q * PI_F * 0.5f
            add(cos(a) * (BEACON_R + 5.5f), sin(a) * (BEACON_R + 5.5f), 0f, 1f, 0.12f, 0.08f)
        }
        add(0f, 0f, 659.5f, 1f, 0.12f, 0.08f)
        // white lights on the solar wing tips
        add(622.5f, 0f, 340f, 1f, 0.96f, 0.88f)
        add(-622.5f, 0f, 340f, 1f, 0.96f, 0.88f)
        l.toFloatArray()
    }

    private fun buildRingStation(): FloatArray {
        val b = MeshBuilder(0x5717L).also { it.shadeJitter = 0.03f }
        val side = MeshBuilder(0x5718L).also { it.shadeJitter = 0.03f } // +X half of the mirror-symmetric parts
        habitatRing(b)
        hubAndSpokes(b)
        dockHead(b, side)
        spindleParts(b)
        solarWing(side)
        val right = side.build()
        return concat(b.build(), right, MeshBuilder.mirrorX(right))
    }

    private fun habitatRing(b: MeshBuilder) {
        val segs = RING_SEGS
        val quarter = segs / 4
        b.revolve(O, X, Y, Z, ringProfile(1f), true, segs, 0f, TAU_F, true) { k, band ->
            val s = k % quarter // spokes sit between s = quarter/2 - 1 and quarter/2, ribs between quarter - 1 and 0
            val nearSpoke = s == quarter / 2 - 1 || s == quarter / 2
            val nearRib = s == quarter - 1 || s == 0
            when (band) {
                1, 7 -> if (nearSpoke || nearRib) HULL_DARK else WINDOW // window strips between the ribs
                3, 5 -> HULL_DARK
                else -> HULL
            }
        }
        // spoke junction blocks and rim ribs
        for (q in 0 until 4) {
            val spoke = PI_F / 4f + q * PI_F / 2f
            val d = 3.2f * DEG
            b.revolve(O, X, Y, Z, ringProfile(1.13f), true, 2, spoke - d, spoke + d, false) { _, band ->
                if (band == 2 || band == 6) ACCENT else HULL_DARK
            }
            val rib = q * PI_F / 2f
            val e = 1.3f * DEG
            b.revolve(O, X, Y, Z, ringProfile(1.07f), true, 1, rib - e, rib + e, false) { _, _ -> METAL }
            // beacon housing on the rib's outer face
            val c = cos(rib)
            val s = sin(rib)
            b.obox(c * BEACON_R, s * BEACON_R, 0f, c, s, 0f, -s, c, 0f, 5f, 5f, 5f, BEACON)
        }
    }

    private fun hubAndSpokes(b: MeshBuilder) {
        // hub collar: the bearing the spokes plug into
        b.revolve(
            O, X, Y, Z, floatArrayOf(0f, -40f, 130f, -40f, 150f, -24f, 150f, 24f, 130f, 40f, 0f, 40f),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { _, band -> if (band == 1) WINDOW else if (band == 2) HULL else HULL_DARK }
        for (q in 0 until 4) {
            val a = PI_F / 4f + q * PI_F / 2f
            radialBox(b, a, 125f, 800f, 15f, -12f, 12f, HULL)
            radialBox(b, a, 140f, 795f, 5f, 11f, 21f, HULL_DARK) // conduit
            radialBox(b, a, 420f, 470f, 22f, -18f, 18f, ACCENT) // transit car
        }
    }

    private fun dockHead(b: MeshBuilder, side: MeshBuilder) {
        // octagonal head: bevelled front rim, sides and back cap; the front plate carries the slot
        val head = b.revolve(
            O, X, Y, Z, floatArrayOf(R_HEAD_R, R_FRONT, R_HEAD_R2, R_FRONT + 14f, R_HEAD_R2, R_HEAD_BACK, 0f, R_HEAD_BACK),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { _, band -> if (band == 2) HULL_DARK else HULL }
        b.slotPlate(head[0], R_FRONT, R_SLOT_W, R_SLOT_H, R_SLOT_DEPTH, plateMat = { HULL_DARK }, wallMat = { HULL_DARK })
        slotTrim(b, R_FRONT, R_SLOT_W, R_SLOT_H, R_SLOT_DEPTH, 5f, 3)
        // control tower on top of the head, sensor block underneath
        val top = R_HEAD_APOTHEM
        b.box(-36f, top - 9f, -305f, 36f, top + 23f, -235f, HULL)
        b.box(-30f, top + 7f, -307f, 30f, top + 15f, -303f, WINDOW)
        b.box(-24f, -top - 19f, -290f, 24f, -top + 9f, -250f, HULL_DARK)
        // equipment blocks on the side faces (mirrored)
        side.box(top - 9f, -22f, -300f, top + 17f, 22f, -230f, HULL_DARK)
        side.box(top + 16f, -14f, -290f, top + 20f, 14f, -270f, ACCENT)
    }

    private fun spindleParts(b: MeshBuilder) {
        // spindle from inside the dock head to the rear module
        b.revolve(
            O, X, Y, Z,
            floatArrayOf(0f, -205f, 70f, -205f, 70f, -120f, 70f, -108f, 70f, 110f, 70f, 122f, 70f, 300f, 0f, 300f),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { _, band -> if (band == 2 || band == 4) ACCENT else HULL_DARK }
        // four pressure tanks between the dock head and the hub, strapped to the spindle
        val tank = floatArrayOf(0f, -185f, 20f, -182f, 30f, -170f, 30f, -75f, 20f, -63f, 0f, -60f)
        for (q in 0 until 4) {
            val a = q * PI_F / 2f
            val c = Vec3(cos(a) * 110f, sin(a) * 110f, 0f)
            b.revolve(c, X, Y, Z, tank, false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true) { _, band ->
                if (band == 2 && q % 2 == 0) ACCENT else HULL
            }
            radialBox(b, a, 62f, 86f, 6f, -128f, -112f, METAL)
        }
        // radiator fins (top and bottom) with accent edges
        for (a in floatArrayOf(PI_F / 2f, -PI_F / 2f)) {
            radialBox(b, a, 62f, 300f, 3f, 130f, 285f, HULL)
            radialBox(b, a, 296f, 304f, 4f, 125f, 290f, ACCENT)
        }
        // rear engineering module
        b.revolve(
            O, X, Y, Z, floatArrayOf(0f, 290f, 92f, 290f, 112f, 308f, 112f, 372f, 86f, 392f, 0f, 392f),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { _, band -> if (band == 2) METAL else HULL_DARK }
        // antenna mast with cross bars and the red beacon on top
        b.box(-4f, -4f, 385f, 4f, 4f, 650f, METAL)
        b.box(-28f, -2.5f, 467.5f, 28f, 2.5f, 472.5f, METAL)
        b.box(-2.5f, -28f, 512.5f, 2.5f, 28f, 517.5f, METAL)
        b.box(-20f, -2.5f, 557.5f, 20f, 2.5f, 562.5f, METAL)
        b.box(-7f, -7f, 645f, 7f, 7f, 659f, BEACON)
    }

    /** +X solar wing (mirrored to -X): boom, three glossy panels, white tip light. */
    private fun solarWing(b: MeshBuilder) {
        b.box(100f, -6f, 334f, 612f, 6f, 346f, METAL)
        for (x0 in floatArrayOf(150f, 306f, 462f)) b.box(x0, -62f, 338f, x0 + 142f, 62f, 342f, SOLAR)
        b.box(608f, -7f, 333f, 622f, 7f, 347f, LIGHT_WHITE)
    }

    // ================================================================== model 1: outpost

    private const val O_FRONT = -150f
    private const val O_BACK = -28f
    // the slot takes the biggest hull (see [slotFits]); the bay keeps a plate around its trim
    private const val O_BOX_W = 58f
    private const val O_BOX_H = 44f
    private const val O_SLOT_W = 36f
    private const val O_SLOT_H = 25f
    private const val O_SLOT_DEPTH = 50f
    private const val PAD_X = 195f
    private const val PAD_TOP = 4f
    private const val PAD_R = 64f
    private const val PAD_INNER = 58f

    private val outpostLights: FloatArray = run {
        val l = ArrayList<Float>()
        fun add(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
            l.add(x); l.add(y); l.add(z); l.add(r); l.add(g); l.add(b)
        }
        for (sx in floatArrayOf(1f, -1f)) for (sy in floatArrayOf(1f, -1f)) {
            add(sx * (O_SLOT_W + 3f), sy * (O_SLOT_H + 3f), O_FRONT - 6.7f, GREEN_R, GREEN_G, GREEN_B)
        }
        // pad edge lights on four of the octagon's corners
        for (q in 0 until 4) {
            val a = PI_F / 8f + q * PI_F / 2f
            add(PAD_X + cos(a) * PAD_R, PAD_TOP - 1.5f, sin(a) * PAD_R, 1f, 0.96f, 0.88f)
        }
        add(20f, 192.5f, 20f, 1f, 0.12f, 0.08f) // antenna whip
        add(0f, -212.5f, 0f, 1f, 0.12f, 0.08f) // radiator keel
        l.toFloatArray()
    }

    private fun buildOutpost(): FloatArray {
        val b = MeshBuilder(0x0B05L).also { it.shadeJitter = 0.03f }
        val side = MeshBuilder(0x0B06L).also { it.shadeJitter = 0.03f } // +X half of the mirror-symmetric parts
        // core tower (vertical): control deck windows in the middle band
        b.revolve(
            O, X, Z, Y,
            floatArrayOf(0f, -110f, 28f, -110f, 42f, -94f, 42f, -14f, 42f, 14f, 42f, 94f, 28f, 110f, 0f, 110f),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { k, band ->
            when (band) {
                3 -> if (k % 2 == 0) WINDOW else HULL_DARK
                2, 4 -> HULL
                else -> HULL_DARK
            }
        }
        dockingBay(b)
        // habitat modules: rear (+Z) and left (-X), with window rings
        val hab = floatArrayOf(
            0f, 25f, 22f, 25f, 22f, 58f, 22f, 66f, 22f, 96f, 22f, 104f, 22f, 136f, 14f, 150f, 0f, 154f,
        )
        val habMat = { _: Int, band: Int ->
            when (band) {
                2, 4 -> WINDOW
                6 -> ACCENT
                7 -> HULL_DARK
                else -> HULL
            }
        }
        b.revolve(O, X, Y, Z, hab, false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true, habMat)
        b.revolve(O, Z, Y, NX, hab, false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true, habMat)
        // two spherical tanks under the left module
        val sphere = floatArrayOf(0f, -18f, 12.7f, -12.7f, 18f, 0f, 12.7f, 12.7f, 0f, 18f)
        for (x in floatArrayOf(-80f, -122f)) {
            b.revolve(Vec3(x, -44f, 0f), X, Z, Y, sphere, false, 6, 0f, TAU_F, true) { _, band ->
                if (band == 1) ACCENT else HULL
            }
            b.box(x - 4f, -30f, -4f, x + 4f, -14f, 4f, METAL)
        }
        landingPad(b)
        // radiator keel under the core
        b.box(-4f, -200f, -4f, 4f, -100f, 4f, METAL)
        for (sz in floatArrayOf(1f, -1f)) {
            b.box(-2f, -192f, minOf(3f * sz, 118f * sz), 2f, -128f, maxOf(3f * sz, 118f * sz), HULL)
            b.box(-3f, -194f, minOf(114f * sz, 122f * sz), 3f, -126f, maxOf(114f * sz, 122f * sz), ACCENT)
        }
        b.box(-5f, -212f, -5f, 5f, -198f, 5f, BEACON)
        commsDish(b)
        // antenna whip with beacon
        b.box(18f, 95f, 18f, 22f, 186f, 22f, METAL)
        b.box(16f, 184f, 16f, 24f, 192f, 24f, BEACON)
        // greebles: service boxes on the tower
        b.box(-16f, 106f, -22f, 4f, 117f, -6f, HULL_DARK)
        b.box(34f, -70f, -14f, 48f, -40f, 14f, HULL_DARK)
        b.box(-14f, 40f, 34f, 14f, 70f, 46f, ACCENT)
        // cargo containers on the left module
        b.box(-130f, 19f, -12f, -101f, 33f, 12f, ACCENT)
        b.box(-97f, 19f, -10f, -70f, 31f, 10f, HULL_DARK)
        // solar wings on the rear module (mirrored)
        side.box(15f, -3f, 118f, 121f, 3f, 126f, METAL)
        for (x0 in floatArrayOf(32f, 77f)) side.box(x0, -20f, 121f, x0 + 42f, 20f, 123f, SOLAR)
        val right = side.build()
        return concat(b.build(), right, MeshBuilder.mirrorX(right))
    }

    private fun dockingBay(b: MeshBuilder) {
        val outer = rectRing(O_BOX_W, O_BOX_H, O_FRONT)
        b.slotPlate(outer, O_FRONT, O_SLOT_W, O_SLOT_H, O_SLOT_DEPTH, plateMat = { HULL_DARK }, wallMat = { HULL_DARK })
        b.prismBack(outer, O_BACK) { k -> if (k <= 2) HULL_DARK else HULL }
        slotTrim(b, O_FRONT, O_SLOT_W, O_SLOT_H, O_SLOT_DEPTH, 3f, 2)
    }

    private fun landingPad(b: MeshBuilder) {
        b.box(30f, -10f, -7f, 160f, 2f, 7f, METAL)
        b.box(30f, -24f, -4f, 150f, -14f, 4f, HULL_DARK)
        b.box(60f, -22f, -2f, 64f, -8f, 2f, METAL)
        b.box(110f, -22f, -2f, 114f, -8f, 2f, METAL)
        val t = PAD_TOP
        b.revolve(
            Vec3(PAD_X, 0f, 0f), X, Z, Y,
            floatArrayOf(
                0f, -12f, 38f, -12f, PAD_R, -4f, PAD_R, t - 3f, PAD_R, t,
                PAD_INNER, t, 24f, t, 16f, t, 0f, t,
            ),
            false, 8, PI_F / 8f, PI_F / 8f + TAU_F, true,
        ) { _, band ->
            when (band) {
                0 -> HULL_DARK
                1, 2 -> HULL
                3 -> LIGHT_WHITE // lit edge band
                4, 6 -> ACCENT // rim + centre ring markings
                else -> HULL_DARK
            }
        }
    }

    private fun commsDish(b: MeshBuilder) {
        b.box(-3f, 100f, -3f, 3f, 152f, 3f, METAL)
        val w = Vec3(0.35f, 0.75f, -0.55f).normalize()
        val u = Vec3().cross(w, Y).normalize()
        val v = Vec3().cross(w, u).normalize()
        val o = Vec3(0f, 150f, 0f).addScaled(w, 8f)
        b.revolve(
            o, u, v, w, floatArrayOf(0f, -8f, 14f, -6.5f, 30f, 0f, 15f, -3f, 0f, -4f),
            false, 12, 0f, TAU_F, true,
        ) { _, band -> if (band >= 2) HULL else METAL }
        val f = Vec3(o.x, o.y, o.z).addScaled(w, 6.5f)
        b.obox(f.x, f.y, f.z, w.x, w.y, w.z, u.x, u.y, u.z, 11f, 1.2f, 1.2f, METAL)
    }

    // ================================================================== shared parts

    /**
     * Lit trim of a docking slot whose entrance plane is z = [zf]: frame bars around the opening
     * (white top/bottom, green sides), green corner light housings, guide strips along the tunnel
     * walls and [rungs] light bars across the floor and ceiling. [t] = trim thickness.
     */
    private fun slotTrim(b: MeshBuilder, zf: Float, sw: Float, sh: Float, depth: Float, t: Float, rungs: Int) {
        val o = 0.12f * t // overhang into the opening, so the trim never shares edges with the plate
        b.box(-sw - t, sh - o, zf - t, sw + t, sh + t, zf, LIGHT_WHITE)
        b.box(-sw - t, -sh - t, zf - t, sw + t, -sh + o, zf, LIGHT_WHITE)
        for (sx in floatArrayOf(1f, -1f)) {
            val xIn = sx * (sw - o)
            val xOut = sx * (sw + 0.8f * t)
            b.box(minOf(xIn, xOut), -sh, zf - 0.9f * t, maxOf(xIn, xOut), sh, zf, SLOT_GREEN)
            for (sy in floatArrayOf(1f, -1f)) {
                val cx = sx * (sw + t)
                val cy = sy * (sh + t)
                val h = 0.72f * t
                b.box(cx - h, cy - h, zf - 1.45f * t - h, cx + h, cy + h, zf - 1.45f * t + h, SLOT_GREEN)
            }
            // guide strip along the side wall
            val s0 = sx * (sw - 0.3f * t)
            val s1 = sx * (sw + 0.16f * t)
            b.box(minOf(s0, s1), -0.3f * t, zf + 0.06f * depth, maxOf(s0, s1), 0.3f * t, zf + 0.94f * depth, LIGHT_WHITE)
        }
        for (i in 0 until rungs) {
            val z = zf + depth * (0.2f + 0.6f * i / maxOf(1, rungs - 1))
            for (sy in floatArrayOf(1f, -1f)) {
                val y0 = sy * (sh - 0.16f * t)
                val y1 = sy * (sh + 0.16f * t)
                b.box(-sw + 0.4f * t, minOf(y0, y1), z - 0.2f * t, sw - 0.4f * t, maxOf(y0, y1), z + 0.2f * t, LIGHT_WHITE)
            }
        }
        // hazard-marking outline on the plate around the lit frame
        val gx = sw + t * 2.6f
        val gy = sh + t * 2.6f
        val g = t * 1.2f
        val p = t * 0.5f
        b.box(-gx - g, gy, zf - p, gx + g, gy + g, zf, ACCENT)
        b.box(-gx - g, -gy - g, zf - p, gx + g, -gy, zf, ACCENT)
        for (sx in floatArrayOf(1f, -1f)) {
            val x0 = sx * gx
            val x1 = sx * (gx + 0.92f * g)
            b.box(minOf(x0, x1), -gy - 0.1f * g, zf - 0.9f * p, maxOf(x0, x1), gy + 0.1f * g, zf, ACCENT)
        }
    }

    private fun radialBox(b: MeshBuilder, a: Float, r0: Float, r1: Float, halfT: Float, z0: Float, z1: Float, mat: Int) {
        val c = cos(a)
        val s = sin(a)
        val rm = (r0 + r1) * 0.5f
        b.obox(c * rm, s * rm, (z0 + z1) * 0.5f, c, s, 0f, -s, c, 0f, (r1 - r0) * 0.5f, halfT, (z1 - z0) * 0.5f, mat)
    }

    /** 8-point rectangle at z, ordered by angle like the slot opening (see [slotPlate]). */
    private fun rectRing(hw: Float, hh: Float, z: Float): Array<Vec3> = arrayOf(
        Vec3(hw, hh, z), Vec3(0.35f * hw, hh, z), Vec3(-0.35f * hw, hh, z), Vec3(-hw, hh, z),
        Vec3(-hw, -hh, z), Vec3(-0.35f * hw, -hh, z), Vec3(0.35f * hw, -hh, z), Vec3(hw, -hh, z),
    )

    private val O = Vec3(0f, 0f, 0f)
    private val X = Vec3(1f, 0f, 0f)
    private val NX = Vec3(-1f, 0f, 0f)
    private val Y = Vec3(0f, 1f, 0f)
    private val Z = Vec3(0f, 0f, 1f)
    private const val DEG = PI_F / 180f

    /** Pushes the reference point far behind the face, so orientation follows the given normal. */
    private const val FAR = 1000f

    /** The ring cross-section as absolute (radius, z) pairs, scaled by [s] about the tube centre. */
    private fun ringProfile(s: Float) = FloatArray(ringSection.size) {
        if (it % 2 == 0) RING_R + ringSection[it] * s else ringSection[it] * s
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    /** Triangle oriented so its normal points along (nx, ny, nz) (any direction within 90 degrees of it). */
    private fun MeshBuilder.triOut(a: Vec3, b: Vec3, c: Vec3, mat: Int, nx: Float, ny: Float, nz: Float) {
        val cx = (a.x + b.x + c.x) / 3f
        val cy = (a.y + b.y + c.y) / 3f
        val cz = (a.z + b.z + c.z) / 3f
        tri(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, mat, cx - nx * FAR, cy - ny * FAR, cz - nz * FAR)
    }

    private fun MeshBuilder.quadOut(a: Vec3, b: Vec3, c: Vec3, d: Vec3, mat: Int, nx: Float, ny: Float, nz: Float) {
        triOut(a, b, c, mat, nx, ny, nz)
        triOut(a, c, d, mat, nx, ny, nz)
    }

    /** Box spanned by unit axes u, v and w = u x v around (cx, cy, cz) with half sizes hu, hv, hw. */
    private fun MeshBuilder.obox(
        cx: Float, cy: Float, cz: Float,
        ux: Float, uy: Float, uz: Float,
        vx: Float, vy: Float, vz: Float,
        hu: Float, hv: Float, hw: Float, mat: Int,
    ) {
        val wx = uy * vz - uz * vy
        val wy = uz * vx - ux * vz
        val wz = ux * vy - uy * vx
        val p = Array(8) {
            val su = if (it and 1 != 0) hu else -hu
            val sv = if (it and 2 != 0) hv else -hv
            val sw = if (it and 4 != 0) hw else -hw
            Vec3(cx + ux * su + vx * sv + wx * sw, cy + uy * su + vy * sv + wy * sw, cz + uz * su + vz * sv + wz * sw)
        }
        val c = Vec3(cx, cy, cz)
        quad(p[0], p[1], p[3], p[2], mat, c)
        quad(p[4], p[5], p[7], p[6], mat, c)
        quad(p[0], p[1], p[5], p[4], mat, c)
        quad(p[2], p[3], p[7], p[6], mat, c)
        quad(p[0], p[2], p[6], p[4], mat, c)
        quad(p[1], p[3], p[7], p[5], mat, c)
    }

    /**
     * Surface of revolution about the axis through [o] along [w]; [u], [v] span the radial plane
     * (radial(θ) = u cos θ + v sin θ). [prof] holds (radius, height) pairs walked so that the solid
     * lies on the left of the path (radius to the right, height up); a radius of 0 closes that end
     * with a fan. [loop] joins the last point back to the first (ring cross-sections). θ sweeps
     * [a0, a1] in [segs] steps; a partial sweep (`full = false`) gets planar end caps.
     * Returns the vertex grid [profile point][segment].
     */
    private fun MeshBuilder.revolve(
        o: Vec3, u: Vec3, v: Vec3, w: Vec3,
        prof: FloatArray, loop: Boolean, segs: Int, a0: Float, a1: Float, full: Boolean,
        mat: (Int, Int) -> Int,
    ): Array<Array<Vec3>> {
        val np = prof.size / 2
        val cols = if (full) segs else segs + 1
        val pts = Array(np) { i ->
            val r = prof[2 * i]
            val h = prof[2 * i + 1]
            Array(cols) { k ->
                val a = a0 + (a1 - a0) * k / segs
                val rc = r * cos(a)
                val rs = r * sin(a)
                Vec3(o.x + u.x * rc + v.x * rs + w.x * h, o.y + u.y * rc + v.y * rs + w.y * h, o.z + u.z * rc + v.z * rs + w.z * h)
            }
        }
        val bands = if (loop) np else np - 1
        for (i in 0 until bands) {
            val j = (i + 1) % np
            var nr = prof[2 * j + 1] - prof[2 * i + 1]
            var nh = -(prof[2 * j] - prof[2 * i])
            val l = sqrt(nr * nr + nh * nh)
            nr /= l
            nh /= l
            for (k in 0 until segs) {
                val k1 = if (full) (k + 1) % segs else k + 1
                val am = a0 + (a1 - a0) * (k + 0.5f) / segs
                val c = cos(am)
                val s = sin(am)
                val nx = (u.x * c + v.x * s) * nr + w.x * nh
                val ny = (u.y * c + v.y * s) * nr + w.y * nh
                val nz = (u.z * c + v.z * s) * nr + w.z * nh
                quadOut(pts[i][k], pts[i][k1], pts[j][k1], pts[j][k], mat(k, i), nx, ny, nz)
            }
        }
        if (!full) {
            for (end in 0..1) {
                val col = if (end == 0) 0 else segs
                val a = if (end == 0) a0 else a1
                val sgn = if (end == 0) -1f else 1f
                // outward = along the sweep direction at the far end, against it at the start
                val tx = (-u.x * sin(a) + v.x * cos(a)) * sgn
                val ty = (-u.y * sin(a) + v.y * cos(a)) * sgn
                val tz = (-u.z * sin(a) + v.z * cos(a)) * sgn
                val ring = Array(np) { pts[it][col] }
                val c = MeshBuilder.centroid(ring)
                val m = mat(if (end == 0) 0 else segs - 1, -1 - end)
                for (i in 0 until np) triOut(c, ring[i], ring[(i + 1) % np], m, tx, ty, tz)
            }
        }
        return pts
    }

    /**
     * Front plate (plane z = [zf], facing -Z) between the hull outline [outer] and a rectangular
     * docking opening (half sizes [sw] x [sh] around the z axis), plus the recessed tunnel walls
     * ([depth] deep, facing into the tunnel) and the glowing back wall. [outer] has 8 points at z = zf
     * ordered by angle, matched one-to-one with the opening's points: (+,+) corner, two points along
     * the top edge, (-,+) corner, (-,-) corner, two along the bottom, (+,-) corner.
     * Plate quad / wall quad k = 0..2 top, 3 left (-X), 4..6 bottom, 7 right (+X).
     */
    private fun MeshBuilder.slotPlate(
        outer: Array<Vec3>, zf: Float, sw: Float, sh: Float, depth: Float,
        plateMat: (Int) -> Int, wallMat: (Int) -> Int,
    ) {
        val open = rectRing(sw, sh, zf)
        val back = rectRing(sw, sh, zf + depth)
        for (k in 0 until 8) {
            val k1 = (k + 1) % 8
            quadOut(outer[k], outer[k1], open[k1], open[k], plateMat(k), 0f, 0f, -1f)
            // tunnel wall: normal perpendicular to the opening edge, towards the tunnel axis
            val ex = open[k1].x - open[k].x
            val ey = open[k1].y - open[k].y
            var nx = -ey
            var ny = ex
            val mx = (open[k].x + open[k1].x) * 0.5f
            val my = (open[k].y + open[k1].y) * 0.5f
            if (nx * mx + ny * my > 0f) {
                nx = -nx; ny = -ny
            }
            val l = sqrt(nx * nx + ny * ny)
            quadOut(open[k], open[k1], back[k1], back[k], wallMat(k), nx / l, ny / l, 0f)
        }
        val c = Vec3(0f, 0f, zf + depth)
        for (k in 0 until 8) triOut(c, back[k], back[(k + 1) % 8], BAY_GLOW, 0f, 0f, -1f)
    }

    /** Extrudes the convex outline [ring] (constant z, ordered by angle) back to z = [zBack] and caps it. */
    private fun MeshBuilder.prismBack(ring: Array<Vec3>, zBack: Float, mat: (Int) -> Int) {
        val n = ring.size
        val c = MeshBuilder.centroid(ring)
        val back = Array(n) { Vec3(ring[it].x, ring[it].y, zBack) }
        for (k in 0 until n) {
            val k1 = (k + 1) % n
            val mx = (ring[k].x + ring[k1].x) * 0.5f - c.x
            val my = (ring[k].y + ring[k1].y) * 0.5f - c.y
            val ex = ring[k1].x - ring[k].x
            val ey = ring[k1].y - ring[k].y
            var nx = -ey
            var ny = ex
            if (nx * mx + ny * my < 0f) {
                nx = -nx; ny = -ny
            }
            val l = sqrt(nx * nx + ny * ny)
            quadOut(ring[k], ring[k1], back[k1], back[k], mat(k), nx / l, ny / l, 0f)
        }
        val bc = Vec3(c.x, c.y, zBack)
        val s = if (zBack > ring[0].z) 1f else -1f
        for (k in 0 until n) triOut(bc, back[k], back[(k + 1) % n], mat(k), 0f, 0f, s)
    }
}
