package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.sqrt

/**
 * The playable star system, in real units (metres, doubles). Data-driven so it can be replaced by
 * the Roblox world export (roblox/world.json) later. Names are placeholders until then.
 *
 * Layout: star at the origin. The home station orbits the ringed gas giant; the asteroid field
 * floats next to the station. Everything is static except planet spin (visual).
 */
class StarSystem {
    enum class BodyType { STAR, LAVA, TERRAN, GAS, ICE, ROCK }

    class Body(
        val name: String,
        val type: BodyType,
        val radius: Double,
        val pos: DVec3,
        /** Orientation of the spin axis (+Y) – also the ring plane normal. */
        val rot: Quat,
        /** Visual spin, radians per second. */
        val spin: Float,
        val hasRings: Boolean,
        val ringInner: Double = 0.0,
        val ringOuter: Double = 0.0,
        /** Atmosphere tint (linear RGB) and relative thickness (0 = none). */
        val atmo: FloatArray = floatArrayOf(0.35f, 0.5f, 0.75f),
        val atmoThickness: Float = 0.02f,
        /** Seed for procedural textures. */
        val seed: Int = 0,
    )

    class Station(
        val name: String,
        /** 0 = ring station (large), 1 = outpost (small). */
        val model: Int,
        val pos: DVec3,
        val rot: Quat,
        val radius: Double,
        /** Index into [bodies] of the body it orbits. */
        val parent: Int,
    )

    val name = "HOME SYSTEM"
    val bodies = ArrayList<Body>()
    val stations = ArrayList<Station>()

    /** Where the player starts (in front of the home station) and the asteroid field centre. */
    val home: DVec3
    val fieldOrigin: DVec3

    /** Systems end here: zone SYSTEM EDGE beyond. */
    val edgeRadius = 2.6e9

    init {
        val sunDirFromHome = Vec3(-0.846f, 0.451f, 0.282f).normalize()
        val giantDirFromHome = Vec3(0.24f, 0.13f, -1f).normalize()
        val homeDist = 1.1e9
        home = DVec3(-sunDirFromHome.x * homeDist, -sunDirFromHome.y * homeDist, -sunDirFromHome.z * homeDist)
        bodies += Body("PRIMARY", BodyType.STAR, 6.0e7, DVec3(), Quat(), 0f, false, atmo = floatArrayOf(1f, 0.85f, 0.6f), atmoThickness = 0.08f)

        // ringed gas giant next to the home station (same framing as the menu backdrop)
        val giantR = 4.0e7
        val giantDist = giantR / kotlin.math.sin(13.5 * Math.PI / 180.0)
        val giant = DVec3(
            home.x + giantDirFromHome.x * giantDist,
            home.y + giantDirFromHome.y * giantDist,
            home.z + giantDirFromHome.z * giantDist,
        )
        val tilt = Quat().setAxisAngle(1f, 0f, -0.45f, -0.42f)
        val roll = Quat().setAxisAngle(giantDirFromHome.x, giantDirFromHome.y, giantDirFromHome.z, 0.32f)
        bodies += Body(
            "PLANET III", BodyType.GAS, giantR, giant, Quat().mul(roll, tilt), 1e-4f, true,
            1.42 * giantR, 2.32 * giantR, floatArrayOf(0.55f, 0.6f, 0.75f), 0.015f, 3,
        )
        bodies += planet("PLANET I", BodyType.LAVA, 3.2e6, 4.0e8, 0.6, 0.05, 1, floatArrayOf(0.9f, 0.4f, 0.2f), 0.01f)
        bodies += planet("PLANET II", BodyType.TERRAN, 6.0e6, 7.2e8, 2.2, -0.04, 2, floatArrayOf(0.35f, 0.55f, 1.0f), 0.025f)
        bodies += planet("PLANET IV", BodyType.ICE, 4.5e6, 1.75e9, 4.0, 0.08, 4, floatArrayOf(0.6f, 0.8f, 1.0f), 0.02f)
        bodies += planet("PLANET V", BodyType.ROCK, 2.2e6, 2.3e9, 5.3, -0.06, 5, floatArrayOf(0.7f, 0.6f, 0.5f), 0.005f)

        stations += Station(
            "HOME STATION", 0,
            DVec3(home.x + HOME_STATION_X, home.y + HOME_STATION_Y, home.z + HOME_STATION_Z),
            Quat().setAxisAngle(0f, 1f, 0f, 0.9f), 915.0, 1,
        )
        val p2 = bodies[3]
        stations += Station("OUTPOST II", 1, offsetFrom(p2, 1.6), Quat().setAxisAngle(0f, 1f, 0f, 1.1f), 257.0, 3)
        val p4 = bodies[4]
        stations += Station("OUTPOST IV", 1, offsetFrom(p4, 1.8), Quat().setAxisAngle(1f, 0f, 0f, 0.3f), 257.0, 4)

        fieldOrigin = DVec3(home.x, home.y, home.z - 1500.0)
    }

    private fun planet(
        name: String, type: BodyType, r: Double, orbit: Double, angle: Double, incl: Double,
        seed: Int, atmo: FloatArray, thick: Float,
    ): Body {
        val pos = DVec3(orbit * kotlin.math.cos(angle), orbit * incl, orbit * kotlin.math.sin(angle))
        val rot = Quat().setAxisAngle(0.3f, 0f, 1f, 0.2f + seed * 0.1f)
        // solid worlds don't spin visually: a landed ship would otherwise slide over the ground
        return Body(name, type, r, pos, rot, 0f, false, atmo = atmo, atmoThickness = thick, seed = seed)
    }

    /** A point [radii] planet radii above [b]'s surface on the side facing the star. */
    private fun offsetFrom(b: Body, radii: Double): DVec3 {
        val d = sqrt(b.pos.x * b.pos.x + b.pos.y * b.pos.y + b.pos.z * b.pos.z)
        val k = 1.0 - (b.radius * radii) / d
        return DVec3(b.pos.x * k, b.pos.y * k + b.radius * 0.3, b.pos.z * k)
    }

    // ------------------------------------------------------------------ queries

    /**
     * Direction from [pos] to the star (written to [outDir]) and how much sunlight reaches [pos]:
     * 1 in the open, 0 inside a planet's shadow, with a soft edge.
     */
    fun sunlight(pos: DVec3, outDir: Vec3): Float {
        val star = bodies[0]
        val sx = star.pos.x - pos.x
        val sy = star.pos.y - pos.y
        val sz = star.pos.z - pos.z
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
        for (i in 1 until bodies.size) {
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
        var nearestBody = -1
        var bodyDistance = 0.0 // to centre
        var altitude = 0.0 // to surface
        var radialSpeed = 0f // + moving away from the nearest body
        var nearestStation = -1
        var stationDistance = 0.0
        var zone = Zone.OPEN_SPACE
        var speedLimit = 0f
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
     * moving with [vel]. [localMax] = the ship's normal top speed.
     */
    fun locate(pos: DVec3, vel: Vec3, localMax: Float, out: Locality): Locality {
        var best = -1
        var bestAlt = Double.MAX_VALUE
        var bestDist = 0.0
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
        }
        out.nearestBody = best
        out.bodyDistance = bestDist
        val b = bodies[best]
        out.altitude = bestDist - b.radius
        if (bestDist > 1e-3) {
            val inv = 1.0 / bestDist
            out.radialSpeed = (((pos.x - b.pos.x) * vel.x + (pos.y - b.pos.y) * vel.y + (pos.z - b.pos.z) * vel.z) * inv).toFloat()
        } else {
            out.radialSpeed = 0f
        }
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

        val r = b.radius
        val fromStar = sqrt(pos.x * pos.x + pos.y * pos.y + pos.z * pos.z)
        out.zone = when {
            stD < STATION_ZONE_RADIUS -> Zone.STATION_ZONE
            b.type != BodyType.STAR && out.altitude < LOW_ALTITUDE -> Zone.LOW_ALTITUDE
            bestDist < r * GRAVITY_WELL_RADII -> Zone.GRAVITY_WELL
            fromStar > edgeRadius -> Zone.SYSTEM_EDGE
            bestDist < r * APPROACH_RADII && out.radialSpeed < -localMax -> Zone.APPROACH
            bestDist < r * APPROACH_RADII && out.radialSpeed > localMax -> Zone.DEPARTURE
            else -> Zone.OPEN_SPACE
        }
        out.speedLimit = speedLimit(out, localMax)
        return out
    }

    /**
     * Dynamic speed: the limit grows with distance from the nearest surface (or station), faster
     * when departing than when approaching, from [localMax] up to [MAX_SPEED] (3 Mm/s).
     */
    fun speedLimit(l: Locality, localMax: Float): Float {
        val clearance = minOf(l.altitude, l.stationDistance - STATION_CLEARANCE)
        if (clearance <= 0.0) return localMax
        val k = if (l.radialSpeed >= 0f) DEPART_RATE else APPROACH_RATE
        return (clearance * k).toFloat().coerceIn(localMax, MAX_SPEED)
    }

    companion object {
        /** Home station position relative to [home] (metres). */
        const val HOME_STATION_X = -2600.0
        const val HOME_STATION_Y = 250.0
        const val HOME_STATION_Z = -900.0
        const val MAX_SPEED = 3.0e6f
        const val DEPART_RATE = 0.6
        const val APPROACH_RATE = 0.32
        const val STATION_ZONE_RADIUS = 8000.0
        const val STATION_CLEARANCE = 4000.0
        const val LOW_ALTITUDE = 60_000.0
        const val GRAVITY_WELL_RADII = 3.0
        const val APPROACH_RADII = 12.0
    }
}
