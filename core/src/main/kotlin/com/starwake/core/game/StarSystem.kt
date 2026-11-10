package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.world.Orbits
import kotlin.math.sqrt

/**
 * The runtime star system (03-world.md 9.1): the bodies and stations of one system in real units
 * (metres, doubles), built from a definition by [com.starwake.core.world.WorldBuilder]. Every body
 * carries the Roblox attribute contract (03 2.2.6) as plain fields, so hand-made and generated
 * systems look the same to the rest of the game.
 *
 * Bodies move on their orbits ([Orbits]) with a double-precision save-game clock ([clock], DECISIONS
 * 8): [advance] puts every body at its position for the clock and records the frame's displacement
 * ([Body.delta]); stations translate with their host and never rotate. A ship near a body or a
 * station is carried by it ([frameFor], [frameDelta]: translation only, the velocity stays relative,
 * DECISIONS 6).
 *
 * The speed law, the zones and the pull live in the flight port ([com.starwake.core.flight.GravityField],
 * `ShipGravity`), which reads the bodies' attribute contract.
 */
class StarSystem(
    /** System name as in the data ("Sol"). */
    val name: String,
    /** Catalog index (1 = Sol). */
    val index: Int,
    val bodies: List<Body>,
    val stations: List<Station>,
    /** The play field: beyond it the zone is SYSTEM EDGE (`Ship.FieldRadius`·S, 27 Gm in Sol). */
    val edgeRadius: Double,
    /** Distance from the star at which the light falloff and the star's glare are 1 (the home planet's orbit). */
    val lightReferenceDistance: Double,
) {
    /** Render family of a body: picks the planet shader's look (the class stays the data). */
    enum class BodyType { STAR, LAVA, TERRAN, GAS, ICE, ROCK }

    class Body(
        /** `DisplayName` ("Earth"). */
        val name: String,
        /** `BodyClass` ("Terrestrial World", "Moon", "Star G"). */
        val cls: String,
        val type: BodyType,
        /** `SurfaceRadius`, m. */
        val radius: Double,
        /** The body this one orbits (the star for planets, the planet for moons); -1 for the star. */
        val parent: Int,
        /** `Seed`: planets 1..n, moons planet·31 + moon index. */
        val seed: Int,
        val isStar: Boolean = false,
        /** Roblox `CollisionRadius` (the old barrier, `ceil(1.105 R) + 6` studs, ×S): the frame radius and zones use it. */
        val collisionRadius: Double = radius,
        /** `MinApproachSpeed`, m/s (stars 40, planets 14, moons 12; absolute). */
        val minApproachSpeed: Float = 14f,
        /** `SurfaceGravityG`. */
        val gravityG: Double = 0.5,
        /** `Landable`: not a star and not a gas or ice giant. */
        val landable: Boolean = false,
        /** `LandingBlocked` ("NO SOLID SURFACE" on the giants), null when landing is allowed. */
        val landingBlocked: String? = null,
        /** `MassShadowFactor`: planets 5, moons and stars 4. */
        val massShadowFactor: Double = 5.0,
        /** Stars: `HeatRadius` and `MassShadowRadius` (the jump-arrival distance), m. */
        val heatRadius: Double = 0.0,
        val massShadowRadius: Double = 0.0,
        /** Stars: the corona sphere / radius. */
        val coronaScale: Double = 1.0,
        /** `OrbitRadius` (m), `OrbitPeriod` (s), `OrbitPhase` (0..1), `OrbitTilt` (degrees); radius 0 = does not orbit. */
        val orbitRadius: Double = 0.0,
        val orbitPeriod: Double = 0.0,
        val orbitPhase: Double = 0.0,
        val orbitTilt: Double = 0.0,
        /** Body colour (maps, debris, fallback), 0xRRGGBB; stars: the disc colour. */
        val colour: Int = 0xFFFFFF,
        /** Stars: the corona colour, 0xRRGGBB. */
        val corona: Int = 0xFFFFFF,
        /** `HasAtmosphere` and `AtmosphereColour` (0xRRGGBB). */
        val hasAtmosphere: Boolean = false,
        val atmosphereColour: Int = 0,
        /** Orientation: the spin axis (+Y) is tilted like the ring (Uranus 82°), with a fixed random yaw. Never changes. */
        val rot: Quat = Quat(),
        val hasRings: Boolean = false,
        /** `RingInner` / `RingOuter`, m. */
        val ringInner: Double = 0.0,
        val ringOuter: Double = 0.0,
        /** `RingColour` (0xRRGGBB) and `RingDensity` (1 − transparency). */
        val ringColour: Int = 0,
        val ringDensity: Float = 0f,
        /** Ring opacity profile slot ([com.starwake.core.world.RingProfiles]), -1 for none. */
        val ringProfile: Int = -1,
        /** Atmosphere scattering tint (linear RGB, used normalised) and the visible limb's height in radii (0 = none). */
        val atmo: FloatArray = floatArrayOf(0.35f, 0.5f, 0.75f),
        val atmoThickness: Float = 0f,
        /** Ground optical depth of the limb's air (vertical, at the scattering colour's strongest channel). */
        val atmoDepth: Float = 0.08f,
        /** Look of the procedural surface bake ([com.starwake.core.world.BodyLooks]). */
        val look: Int = 0,
    ) {
        /** Upper-case name for every display (precomputed: the HUD and the map draw it each frame). */
        val displayName: String = name.uppercase()

        /** Upper-case class for the map card and the markers. */
        val classLabel: String = cls.uppercase()

        /** Current position (m, the star at the origin); moved in place by [advance]. */
        val pos = DVec3()

        /** `ShipOrbits.restPosition`: the position at clock 0 without the tilt (stations hang from it). */
        val restPos = DVec3()

        /** Displacement during the last [advance] (the carry of a ship in this body's frame). */
        val delta = DVec3()

        /** Unit normal of the ring plane (`RingNormal` = Rz(tilt)·(0, 1, 0)); +Y without rings. */
        val ringNormal = Vec3(0f, 1f, 0f)

        /** Visual spin: planets never spin (DECISIONS 7). */
        val spin: Float get() = 0f

        /** Frame radius (m): a ship this close is carried by the body (set by the builder, see [frameFor]). */
        var frameRadius = 0.0
            internal set
    }

    class Station(
        /** Catalog id ("SOL-MERIDIAN"). */
        val id: String,
        val name: String,
        /** Stand-in mesh ([com.starwake.core.content.StationMesh]) until the converted Roblox models land. */
        val model: Int,
        /** Roblox model name ("SpireStation"). */
        val modelName: String,
        /** Fixed orientation: the mouth (−Z) faces the host. */
        val rot: Quat,
        /** Bounding radius of the stand-in, m. */
        val radius: Double,
        /** Index into [bodies] of the host. */
        val parent: Int,
        /** Host → station, m (from the host's rest position; fixed for the life of the system). */
        val offset: DVec3,
        val economy: String = "",
        val shipyard: Boolean = false,
        val description: String = "",
    ) {
        /** Current position = host position + [offset]. */
        val pos = DVec3()
    }

    /** Save-game clock (s): orbits are at their rest positions at 0 (DECISIONS 8). */
    var clock = 0.0
        private set

    /** Index of the star ([BodyType.STAR]) in [bodies]. */
    val star: Int = bodies.indexOfFirst { it.isStar }.coerceAtLeast(0)

    /** Uppercase system name for displays ("SOL"). */
    val displayName: String = name.uppercase()

    // ------------------------------------------------------------------ orbits

    /** Advances the clock by [dt] seconds and moves everything ([advance] order: Roblox C14). */
    fun advance(dt: Double) {
        clock += dt
        place(true)
    }

    /**
     * Sets the clock (a load, a new system) and puts everything there, without a carry: the deltas
     * are zero ("snap").
     */
    fun snapTo(clockSeconds: Double) {
        clock = clockSeconds
        place(false)
    }

    /** Positions for [clock]; parents come before their moons in [bodies]. */
    private fun place(recordDelta: Boolean) {
        for (i in bodies.indices) {
            val b = bodies[i]
            val ox = b.pos.x
            val oy = b.pos.y
            val oz = b.pos.z
            if (b.parent < 0 || b.orbitRadius <= 0.0 || b.orbitPeriod <= 0.0) {
                if (b.parent >= 0) {
                    val p = bodies[b.parent].pos
                    b.pos.set(p.x, p.y, p.z)
                }
            } else {
                Orbits.position(b.orbitRadius, b.orbitPeriod, b.orbitPhase, b.orbitTilt, clock, bodies[b.parent].pos, b.pos)
            }
            if (recordDelta) b.delta.set(b.pos.x - ox, b.pos.y - oy, b.pos.z - oz) else b.delta.set(0.0, 0.0, 0.0)
        }
        // indices, not an iterator: no allocation per frame (ART has no escape analysis)
        for (i in stations.indices) {
            val s = stations[i]
            val h = bodies[s.parent].pos
            s.pos.set(h.x + s.offset.x, h.y + s.offset.y, h.z + s.offset.z)
        }
    }

    // ------------------------------------------------------------------ reference frames (DECISIONS 6)

    /**
     * The frame a ship at [pos] is carried by, given the frame it was in ([current], from
     * [frameFor] last frame; [FRAME_NONE] for none). Stations have priority: the nearest station
     * within [STATION_FRAME_RADIUS] (×[FRAME_HYSTERESIS] while it is the frame), as
     * `ShipStation`'s reference frame; otherwise the innermost body whose [Body.frameRadius]
     * (×[FRAME_HYSTERESIS] for the current one or its ancestors) contains the ship: moons nest in
     * their planet. A station frame counts as its host's frame for the hysteresis, so leaving a
     * station hands over to its host without a jump. Returns an encoded frame (see [frameBody],
     * [frameStation]).
     */
    fun frameFor(pos: DVec3, current: Int): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in stations.indices) {
            val s = stations[i].pos
            val dx = pos.x - s.x
            val dy = pos.y - s.y
            val dz = pos.z - s.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val reach = if (current == stationFrame(i)) STATION_FRAME_RADIUS * FRAME_HYSTERESIS else STATION_FRAME_RADIUS
            if (d < reach && d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best >= 0) return stationFrame(best)
        // the body the current frame holds on to (a station's host keeps its frame's hysteresis)
        val held = when {
            isStationFrame(current) -> stations[frameStation(current)].parent
            isBodyFrame(current) -> frameBody(current)
            else -> -1
        }
        var body = -1
        var bodyR = Double.MAX_VALUE
        for (i in bodies.indices) {
            val b = bodies[i]
            if (b.frameRadius <= 0.0) continue
            val r = if (held >= 0 && isAncestorOrSelf(i, held)) b.frameRadius * FRAME_HYSTERESIS else b.frameRadius
            val dx = pos.x - b.pos.x
            val dy = pos.y - b.pos.y
            val dz = pos.z - b.pos.z
            if (dx * dx + dy * dy + dz * dz < r * r && b.frameRadius < bodyR) {
                bodyR = b.frameRadius
                body = i
            }
        }
        return if (body >= 0) bodyFrame(body) else FRAME_NONE
    }

    /** True when [a] is [b] or one of [b]'s ancestors in the orbit tree. */
    fun isAncestorOrSelf(a: Int, b: Int): Boolean {
        var k = b
        while (k >= 0) {
            if (k == a) return true
            k = bodies[k].parent
        }
        return false
    }

    /** The displacement of [frame] during the last [advance] into [out] (zero for [FRAME_NONE]). */
    fun frameDelta(frame: Int, out: DVec3): DVec3 = when {
        isStationFrame(frame) -> out.set(bodies[stations[frameStation(frame)].parent].delta)
        isBodyFrame(frame) -> out.set(bodies[frameBody(frame)].delta)
        else -> out.set(0.0, 0.0, 0.0)
    }

    /** The frame's origin (the body's or station's position) into [out]; the star for [FRAME_NONE]. */
    fun frameOrigin(frame: Int, out: DVec3): DVec3 = when {
        isStationFrame(frame) -> out.set(stations[frameStation(frame)].pos)
        isBodyFrame(frame) -> out.set(bodies[frameBody(frame)].pos)
        else -> out.set(bodies[star].pos)
    }

    /** Display name of a frame (harness read-out). */
    fun frameName(frame: Int): String = when {
        isStationFrame(frame) -> stations[frameStation(frame)].name
        isBodyFrame(frame) -> bodies[frameBody(frame)].displayName
        else -> "STAR"
    }

    // ------------------------------------------------------------------ queries

    /** Index of the body called [name] (case-insensitive), or -1. */
    fun bodyIndex(name: String): Int = bodies.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    /** Index of the station with catalog id [id], or -1. */
    fun stationIndex(id: String): Int = stations.indexOfFirst { it.id == id }

    /**
     * A free-space spawn near station [i] ("launch space", the menu backdrop): [SPAWN_DISTANCE] out
     * of its mouth towards the host, nose away from the station. Writes into [outPos]/[outRot].
     */
    fun spaceSpawn(i: Int, outPos: DVec3, outRot: Quat) {
        val st = stations[i]
        val ox = st.offset.x
        val oy = st.offset.y
        val oz = st.offset.z
        val l = sqrt(ox * ox + oy * oy + oz * oz)
        // the mouth faces the host: out along -offset
        val dx = -ox / l
        val dy = -oy / l
        val dz = -oz / l
        outPos.set(st.pos.x + dx * SPAWN_DISTANCE, st.pos.y + dy * SPAWN_DISTANCE, st.pos.z + dz * SPAWN_DISTANCE)
        val up = Vec3()
        st.rot.rotate(0f, 1f, 0f, up)
        outRot.lookRotation(dx.toFloat(), dy.toFloat(), dz.toFloat(), up.x, up.y, up.z)
    }

    /**
     * Direction from [pos] to the star (written to [outDir]) and how much sunlight reaches [pos]:
     * 1 in the open, 0 inside a planet's shadow, with a soft edge.
     */
    fun sunlight(pos: DVec3, outDir: Vec3): Float {
        val st = bodies[star]
        val sx = st.pos.x - pos.x
        val sy = st.pos.y - pos.y
        val sz = st.pos.z - pos.z
        val sd = sqrt(sx * sx + sy * sy + sz * sz)
        if (sd < 1.0) {
            outDir.set(0f, 1f, 0f)
            return 1f
        }
        val dx = sx / sd
        val dy = sy / sd
        val dz = sz / sd
        outDir.set(dx.toFloat(), dy.toFloat(), dz.toFloat())
        var vis = 1.0
        for (i in bodies.indices) {
            if (i == star) continue
            val b = bodies[i]
            val cx = b.pos.x - pos.x
            val cy = b.pos.y - pos.y
            val cz = b.pos.z - pos.z
            val t = cx * dx + cy * dy + cz * dz
            if (t <= 0.0 || t >= sd) continue
            val c2 = cx * cx + cy * cy + cz * cz
            val closest = sqrt(maxOf(0.0, c2 - t * t))
            val r = b.radius
            if (closest < r * 1.03) {
                val e = ((closest - r * 0.97) / (r * 0.06)).coerceIn(0.0, 1.0)
                vis = minOf(vis, e * e * (3 - 2 * e))
            }
        }
        return vis.toFloat()
    }

    companion object {
        /** `LandingBlocked` of the gas and ice giants (`tagBody`). */
        const val NO_SOLID_SURFACE = "NO SOLID SURFACE"

        /** `ShipConfig.Stations.ZoneRadius`: the station reference frame (absolute, DECISIONS 9). */
        const val STATION_FRAME_RADIUS = 2200.0

        /** Hysteresis of every frame: the current one is kept out to ×1.25 (`ShipStation`, 03 2.9.4). */
        const val FRAME_HYSTERESIS = 1.25

        /** The free-space spawn ("launch space") this far out of the home station's mouth, m. */
        const val SPAWN_DISTANCE = 3000.0

        /** No frame: the star's (absolute) frame. */
        const val FRAME_NONE = -1
        private const val STATION_FRAME_BASE = 1_000_000

        fun bodyFrame(i: Int) = i
        fun stationFrame(i: Int) = STATION_FRAME_BASE + i
        fun isBodyFrame(f: Int) = f in 0 until STATION_FRAME_BASE
        fun isStationFrame(f: Int) = f >= STATION_FRAME_BASE
        fun frameBody(f: Int) = f
        fun frameStation(f: Int) = f - STATION_FRAME_BASE
    }
}
