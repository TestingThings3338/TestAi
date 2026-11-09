package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.world.Orbits
import kotlin.math.pow
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
 * Still the pre-port flight's companion: [locate] and [speedLimit] (the zones and the dynamic limit)
 * stay until the flight port brings ShipGravity.
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

    /** Result of [locate]; reused, no allocation. */
    class Locality {
        /**
         * The nearest body by altitude relative to its size: LAND, the collisions and the zones but
         * APPROACH / DEPARTURE, which follow [limitingBody].
         */
        var nearestBody = -1
        var bodyDistance = 0.0 // to centre
        var altitude = 0.0 // to surface
        var radialSpeed = 0f // + moving away from the nearest body
        /** radialSpeed / speed: -1 straight down, 0 level, +1 straight up. */
        var radialFraction = 0f
        var nearestStation = -1
        var stationDistance = 0.0
        /** stationRadialSpeed / speed: -1 straight at the nearest station, +1 straight away from it. */
        var stationRadialFraction = 0f
        var zone = Zone.OPEN_SPACE
        var speedLimit = 0f

        /**
         * The body whose own limit is the lowest (`ShipGravity` `limitingBody`): the speed limit is
         * the minimum over all bodies, so a moon beside a giant brakes the ship although the giant
         * is the [nearestBody] by size. Its altitude (m) and radial fraction (as [radialFraction])
         * feed [speedLimit].
         */
        var limitingBody = -1
        var limitAltitude = 0.0
        var limitRadialFraction = 0f
    }

    enum class Zone(val label: String) {
        DEPARTURE("DEPARTURE"),
        APPROACH("APPROACH"),
        OPEN_SPACE("OPEN SPACE"),
        GRAVITY_WELL("GRAVITY WELL"),
        LOW_ALTITUDE("LOW ALTITUDE"),
        STATION_ZONE("STATION ZONE"),
        SYSTEM_EDGE("SYSTEM EDGE"),
    }

    /**
     * Finds the nearest body/station, the zone, and the dynamic speed limit for a ship at [pos]
     * moving with [vel] (relative to its frame). [localMax] = the ship's normal top speed.
     */
    fun locate(pos: DVec3, vel: Vec3, localMax: Float, out: Locality): Locality {
        val spd = vel.length()
        var best = -1
        var bestAlt = Double.MAX_VALUE
        var bestDist = 0.0
        var limiter = -1
        var limiterLaw = Double.MAX_VALUE
        for (i in bodies.indices) {
            val b = bodies[i]
            val dx = pos.x - b.pos.x
            val dy = pos.y - b.pos.y
            val dz = pos.z - b.pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            // compare by altitude relative to size, so a big star doesn't always win
            val alt = d - b.radius
            val score = alt / b.radius
            if (score < bestAlt) {
                bestAlt = score
                best = i
                bestDist = d
            }
            // the limit is the minimum of every body's own law ("Minimum ueber ALLE Koerper"): a
            // moon beside a giant brakes the ship even while the giant is the nearest body by size
            val rf = radialFraction(dx, dy, dz, d, vel, spd)
            val law = alt * rate(rf)
            if (law < limiterLaw) {
                limiterLaw = law
                limiter = i
                out.limitAltitude = alt
                out.limitRadialFraction = rf
            }
        }
        out.nearestBody = best
        out.limitingBody = limiter
        out.bodyDistance = bestDist
        val b = bodies[best]
        out.altitude = bestDist - b.radius
        if (bestDist > 1e-3) {
            val inv = 1.0 / bestDist
            out.radialSpeed = (((pos.x - b.pos.x) * vel.x + (pos.y - b.pos.y) * vel.y + (pos.z - b.pos.z) * vel.z) * inv).toFloat()
        } else {
            out.radialSpeed = 0f
        }
        out.radialFraction = if (spd > 1f) (out.radialSpeed / spd).coerceIn(-1f, 1f) else 0f
        var st = -1
        var stD = Double.MAX_VALUE
        for (i in stations.indices) {
            val s = stations[i].pos
            val dx = pos.x - s.x
            val dy = pos.y - s.y
            val dz = pos.z - s.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < stD) {
                stD = d
                st = i
            }
        }
        out.nearestStation = st
        out.stationDistance = stD
        out.stationRadialFraction = 0f
        if (st >= 0 && stD > 1e-3 && spd > 1f) {
            val s = stations[st].pos
            val radial = ((pos.x - s.x) * vel.x + (pos.y - s.y) * vel.y + (pos.z - s.z) * vel.z) / stD
            out.stationRadialFraction = (radial / spd).toFloat().coerceIn(-1f, 1f)
        }

        val r = b.radius
        val sp = bodies[star].pos
        val fx = pos.x - sp.x
        val fy = pos.y - sp.y
        val fz = pos.z - sp.z
        val fromStar = sqrt(fx * fx + fy * fy + fz * fz)
        // APPROACH / DEPARTURE say why the speed is what it is, so they follow the body that limits
        // it (Roblox `zoneLabel(limitZone, limitCosine)`): closing on Io beside Jupiter is an approach
        val lr = bodies[limiter].radius
        val near = out.limitAltitude + lr < lr * APPROACH_RADII
        val limitRadialSpeed = out.limitRadialFraction * spd
        out.zone = when {
            stD < STATION_ZONE_RADIUS -> Zone.STATION_ZONE
            !b.isStar && out.altitude < LOW_ALTITUDE -> Zone.LOW_ALTITUDE
            bestDist < r * GRAVITY_WELL_RADII -> Zone.GRAVITY_WELL
            fromStar > edgeRadius -> Zone.SYSTEM_EDGE
            near && limitRadialSpeed < -localMax -> Zone.APPROACH
            near && limitRadialSpeed > localMax -> Zone.DEPARTURE
            else -> Zone.OPEN_SPACE
        }
        out.speedLimit = speedLimit(out, localMax)
        return out
    }

    /**
     * Dynamic speed (the pre-port law, kept until the flight port brings the Roblox one): the limit
     * grows with the distance from a surface (or station), faster when departing than when
     * approaching, from [localMax] up to [MAX_SPEED] (0.5 c, DECISIONS 2). The rate blends smoothly
     * with the heading (level flight gets the mean), so a small pitch change never makes the limit
     * jump. Every rate is per second, so the law has no length scale: at S = 3000 a trip between
     * neighbouring planets takes about a minute (TripTimeTest, the "sol-trip" scenario).
     *
     * Every body has its own law (its altitude times the rate for the heading to it) and the
     * lowest one limits ([Locality.limitingBody]), as Roblox's `ShipGravity:getState` takes the
     * minimum over all bodies (03-world.md B.3): Io, 13 Mm away, brakes a ship that Jupiter alone
     * would let through at 78 Mm/s.
     *
     * A station limits the speed while the ship closes on it or is inside its zone. Leaving it (the
     * heading 3 to 27 degrees out from square to it, beyond 8 to 12 km, blended) the ship is free
     * of it, as past Roblox's 6 km station lock (`DockingRange`): at S = 3000 MERIDIAN hangs 13 Mm
     * above Earth, and growing the limit from the station's 4 km clearance held a departure for
     * half a minute. Turning back, the station's limit returns before the ship can close on it.
     */
    fun speedLimit(l: Locality, localMax: Float): Float {
        // 0 closing on the station or inside its zone .. 1 leaving it (blended, so the limit never jumps)
        val heading = ((l.stationRadialFraction - LEAVE_FROM) / (LEAVE_TO - LEAVE_FROM)).coerceIn(0f, 1f)
        val beyond = ((l.stationDistance - STATION_ZONE_RADIUS) / (STATION_ZONE_RADIUS * 0.5)).coerceIn(0.0, 1.0)
        val leave = heading * heading * (3f - 2f * heading) * (beyond * beyond * (3.0 - 2.0 * beyond))
        // the station's clearance, handed over geometrically to the limiting body's altitude as the ship leaves
        val held = l.stationDistance - STATION_CLEARANCE
        val altitude = l.limitAltitude
        val clearance = when {
            held <= 0.0 || altitude <= held -> minOf(altitude, held)
            leave <= 0.0 -> held
            else -> held * (altitude / held).pow(leave)
        }
        if (clearance <= 0.0) return localMax
        return (clearance * rate(l.limitRadialFraction)).toFloat().coerceIn(localMax, MAX_SPEED)
    }

    /** The law's rate (1/s) for a radial fraction: [APPROACH_RATE] heading in, [DEPART_RATE] heading out, smoothly blended over ±[RATE_BLEND]. */
    private fun rate(radialFraction: Float): Double {
        val t = ((radialFraction + RATE_BLEND) / (2f * RATE_BLEND)).coerceIn(0f, 1f)
        return APPROACH_RATE + (DEPART_RATE - APPROACH_RATE) * (t * t * (3f - 2f * t))
    }

    /** Radial speed over speed from a body's centre ([dx], [dy], [dz] at distance [d]): as [Locality.radialFraction]. */
    private fun radialFraction(dx: Double, dy: Double, dz: Double, d: Double, vel: Vec3, spd: Float): Float {
        if (spd <= 1f) return 0f
        val radial = if (d > 1e-3) ((dx * vel.x + dy * vel.y + dz * vel.z) * (1.0 / d)).toFloat() else 0f
        return (radial / spd).coerceIn(-1f, 1f)
    }

    companion object {
        /** `LandingBlocked` of the gas and ice giants (`tagBody`). */
        const val NO_SOLID_SURFACE = "NO SOLID SURFACE"

        /** Cruise cap: half the speed of light (DECISIONS 2). */
        const val MAX_SPEED = 1.5e8f
        const val DEPART_RATE = 0.6
        const val APPROACH_RATE = 0.32
        /** Heading (radial fraction) over which the rate blends from approach to depart: ±~17°. */
        const val RATE_BLEND = 0.3f
        const val STATION_ZONE_RADIUS = 8000.0
        const val STATION_CLEARANCE = 4000.0

        /** Heading away from the nearest station (radial fraction) over which its limit lets go. */
        const val LEAVE_FROM = 0.05f
        const val LEAVE_TO = 0.45f
        const val LOW_ALTITUDE = 60_000.0
        const val GRAVITY_WELL_RADII = 3.0
        const val APPROACH_RADII = 12.0

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
