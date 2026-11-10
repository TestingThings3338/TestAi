package com.starwake.core.flight

import com.starwake.core.content.StationMesh
import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Everything the flight needs to know about where the ship is (Roblox `gravityState`,
 * `ShipGravity:getState` plus the station and edge composition of `ShipClient.lua:2484-2538`).
 * One instance per session, filled every frame; no allocation.
 */
class GravityState {
    // ---- the nearest body: the smallest altitude, not the nearest centre
    /** Index of the nearest body, -1 for none. */
    var body = -1

    /** Above the nearest body's law radius (the surface in the port, the barrier in Roblox), m. */
    var altitude = Double.POSITIVE_INFINITY

    /** Above the ground under the ship (the sphere until the terrain lands), m. */
    var groundAltitude = Double.POSITIVE_INFINITY

    /** To the nearest body's centre, m. */
    var distance = Double.POSITIVE_INFINITY

    /** The nearest body's law radius, m. */
    var surfaceRadius = 0.0
    var belowSurface = false
    var inMassShadow = false
    var massShadowRadius = 0.0
    var inInfluence = false
    var influenceRadius = 0.0

    /** Velocity towards the nearest body's centre, m/s (+ closing). */
    var closingSpeed = 0.0

    /** Altitude / closing speed when closing faster than 0.05 m/s, else +∞ (warnings use time, not distance). */
    var timeToImpact = Double.POSITIVE_INFINITY

    /** The nearest body's surface gravity and what of it arrives here, g. */
    var surfaceGravityG = 0.0
    var gravityG = 0.0

    /** The pull, m/s² (damped in the port, DECISIONS 5); zero inside a station's frame. */
    val gravity = DVec3()

    /** `LandingBlocked` of the nearest body, null when landing is allowed. */
    var landingBlocked: String? = null

    // ---- the speed limit: the minimum over bodies, stations and the edge
    var speedLimit = Double.POSITIVE_INFINITY
    var limitZone = LimitZone.OPEN

    /** Cosine between the velocity and the direction to what limits (+1 straight at it). */
    var limitCosine = 0.0

    /** The body whose own law is the lowest (-1 when a station or the edge limits, or nothing). */
    var limitingBody = -1

    // ---- the star (tracked apart: rarely the nearest body, its heat reaches far)
    var star = -1
    var starAltitude = Double.POSITIVE_INFINITY
    var starRadius = 0.0

    /** 0 at the heat zone's edge, 1 at the surface. */
    var starHeat = 0.0

    /**
     * The ship's heat, 0..2.2 (1 = 100 %): Roblox's `gravityState.heat = systems:getHeat()` after the
     * systems' update (C27), which the warnings read. The session writes it; [reset] keeps it.
     */
    var heat = 0.0

    // ---- stations
    /** The nearest station, -1 for none, and the distance to its centre (m). */
    var station = -1
    var stationDistance = Double.POSITIVE_INFINITY

    /** Inside a station's docking range: the station holds the speed (no cruise, no jump). */
    var stationLock = false

    /** Inside the nearest station's hangar (its docking slot, 20 m out of the mouth included). */
    var inHangar = false

    // ---- the field edge
    /** To the field edge, m (+∞ without a field). */
    var edgeGap = Double.POSITIVE_INFINITY

    /** The zone caption (`ShipGravity.zoneLabel` of [limitZone] and [limitCosine]). */
    val zoneLabel: String get() = SpeedLaw.zoneLabel(limitZone, limitCosine)

    fun reset(cap: Double) {
        body = -1
        altitude = Double.POSITIVE_INFINITY
        groundAltitude = Double.POSITIVE_INFINITY
        distance = Double.POSITIVE_INFINITY
        surfaceRadius = 0.0
        belowSurface = false
        inMassShadow = false
        massShadowRadius = 0.0
        inInfluence = false
        influenceRadius = 0.0
        closingSpeed = 0.0
        timeToImpact = Double.POSITIVE_INFINITY
        surfaceGravityG = 0.0
        gravityG = 0.0
        gravity.zero()
        landingBlocked = null
        speedLimit = cap
        limitZone = LimitZone.OPEN
        limitCosine = 0.0
        limitingBody = -1
        star = -1
        starAltitude = Double.POSITIVE_INFINITY
        starRadius = 0.0
        starHeat = 0.0
        station = -1
        stationDistance = Double.POSITIVE_INFINITY
        stationLock = false
        inHangar = false
        edgeGap = Double.POSITIVE_INFINITY
    }
}

/**
 * The ground under a ship over body [body] (the terrain module, `04`): its height above the body's
 * sphere at [pos], m. Until the terrain lands the field measures from the sphere.
 */
fun interface GroundHeight {
    fun heightAt(body: Int, pos: DVec3): Double
}

/**
 * `ShipGravity:getState` for the port (02-flight.md 3.5, 9.4, 9.5): per body the S-classified far
 * law ([FlightLaw]) or, below 11.4 km over the ground, the absolute near band (DECISIONS 3); the
 * limit is the minimum over all bodies, the nearest body (smallest altitude) gives altitude,
 * closing speed, time to impact and the pull; the star gives the heat. [composeStations] and
 * [composeEdge] add the station locks and the field edge as `ShipClient` does.
 *
 * In spite of the Roblox name, this mostly does not pull the ship: it caps the allowed speed near
 * bodies (otherwise landings would be impossible). No allocation.
 */
class GravityField(val law: FlightLaw = FlightLaw.PORT) {
    /** The terrain's height over the sphere, when a terrain is attached (null: the sphere). */
    var ground: GroundHeight? = null

    /** The zone of the last [bodyLimit] call. */
    var lastZone = LimitZone.OPEN
        private set

    private val local = Vec3()
    private val portPos = Vec3()
    private val portDir = Vec3()
    private val slot = Vec3()

    /** The radius the law measures from: the surface in the port (no barrier), Roblox's barrier otherwise. */
    fun radiusOf(b: StarSystem.Body): Double = if (law.nearBand) b.radius else b.collisionRadius

    /** The mass-shadow height of [b] (above [radiusOf]), m: `MassShadowRadius`, else radius × factor; the star's at most its heat zone. */
    fun shadowOf(b: StarSystem.Body): Double {
        val r = radiusOf(b)
        var shadow = if (b.massShadowRadius > 0.0) b.massShadowRadius else r * b.massShadowFactor
        if (b.isStar && b.heatRadius > 0.0) shadow = min(shadow, b.heatRadius * FlightConfig.Gravity.STAR_SHADOW_FROM_HEAT)
        return shadow
    }

    /**
     * The limit body [b] alone sets at sphere altitude [h] (m above [radiusOf]), ground altitude
     * [hAgl], heading cosine [cos] (+1 straight at it); the zone goes to [lastZone]. Clamped to the
     * body's minimum approach speed and the cap.
     */
    fun bodyLimit(b: StarSystem.Body, h: Double, hAgl: Double, cos: Double): Double {
        val minSpeed = b.minApproachSpeed.toDouble()
        val cap = law.cruiseCap
        var lim: Double
        var zone: LimitZone
        if (law.nearBand && !b.isStar && hAgl < FlightConfig.Surface.NEAR_BAND) {
            // the near band (DECISIONS 3, 02 9.5): Roblox's landing-scene law, absolute
            lim = SpeedLaw.surfaceLimit(hAgl)
            zone = LimitZone.LANDING
            if (cos > 0.05) {
                // descending: the closing part must be stoppable with the unscaled approach brake
                val closing = max(minSpeed, SpeedLaw.approachReach(hAgl, FlightConfig.Gravity.APPROACH_BRAKE)) / cos
                if (closing < lim) {
                    lim = closing
                    zone = LimitZone.SURFACE
                }
            }
            lim = max(lim, minSpeed)
        } else {
            val r = radiusOf(b)
            val shadow = shadowOf(b)
            var floor = max(law.shadowFloor, 0.15 * r)
            if (b.isStar) floor = max(floor, law.starShadowMinSpeed)
            if (h < shadow) {
                lim = max(law.shadowCeiling * (max(h, 0.0) / max(shadow, 1e-3)).pow(FlightConfig.Gravity.MASS_SHADOW_CURVE), floor)
                zone = if (b.isStar) LimitZone.HEAT else LimitZone.SHADOW
            } else {
                lim = SpeedLaw.cruiseLimit(law.shadowCeiling, h - shadow, SpeedLaw.cruiseTime(cos), cap)
                zone = LimitZone.CRUISE
            }
            val influence = r * FlightConfig.Gravity.DEFAULT_INFLUENCE_FACTOR
            if (law.shadowApproach && cos > 0.05 && h >= influence) {
                // the port: closing on the body, never more than APPROACH_TIME from the surface
                // along the closing part above the approach zone too (FlightLaw.shadowApproach);
                // inside it Roblox's own two-second rule holds (and names the zone)
                val time = max(minSpeed, max(h, 0.0) / FlightConfig.Gravity.APPROACH_TIME) / cos
                if (time < lim) lim = time
            }
            if (h < influence) {
                // close to the body: at most two seconds to the surface, the closing part stoppable
                var near = max(h, 0.0) / FlightConfig.Gravity.APPROACH_TIME
                if (cos > 0.05) near = min(near, SpeedLaw.approachReach(h, law.approachBrake) / cos)
                near = max(minSpeed, near)
                if (near < lim) {
                    lim = near
                    zone = LimitZone.SURFACE
                }
            }
        }
        lastZone = zone
        return (lim * FlightConfig.Gravity.LIMIT_MULTIPLIER).coerceIn(minSpeed, cap)
    }

    /**
     * `getState(position, velocity)` over [system]'s bodies into [out]: [pos] in system metres,
     * [vel] relative to the ship's frame (m/s).
     */
    fun getState(system: StarSystem, pos: DVec3, vel: DVec3, out: GravityState) {
        val cap = law.cruiseCap
        out.reset(cap)
        val speed = vel.length()
        val bodies = system.bodies
        for (i in bodies.indices) {
            val b = bodies[i]
            val r = radiusOf(b)
            val tx = b.pos.x - pos.x
            val ty = b.pos.y - pos.y
            val tz = b.pos.z - pos.z
            val d = sqrt(tx * tx + ty * ty + tz * tz)
            val h = d - r
            // the ground under the ship, near the surface only (the terrain answers there)
            val g = ground
            val hAgl = if (g != null && !b.isStar && h < FlightConfig.Surface.NEAR_BAND * 2.0) h - g.heightAt(i, pos) else h
            val cos = if (speed > 1.0 && d > 1e-3) (vel.x * tx + vel.y * ty + vel.z * tz) / (speed * d) else 1.0
            val lim = bodyLimit(b, h, hAgl, cos)

            // heat: 0 at the heat zone's edge, 1 at the surface
            if (b.isStar && h < out.starAltitude) {
                out.star = i
                out.starAltitude = h
                out.starRadius = r
                val range = if (b.heatRadius > 0.0) b.heatRadius else r * FlightConfig.Gravity.HEAT_RANGE
                out.starHeat = (1.0 - h / max(range, 1e-3)).coerceIn(0.0, 1.0)
            }

            // the limit: the MINIMUM over all bodies
            if (lim < out.speedLimit) {
                out.speedLimit = lim
                out.limitingBody = i
                out.limitZone = if (lim >= cap) LimitZone.OPEN else lastZone
                out.limitCosine = cos
            }

            // the nearest body: the smallest altitude
            if (h < out.altitude) {
                out.body = i
                out.altitude = h
                out.groundAltitude = hAgl
                out.distance = d
                out.surfaceRadius = r
                out.belowSurface = h <= 0.0
                val shadow = shadowOf(b)
                out.inMassShadow = h < shadow
                out.massShadowRadius = shadow
                val influence = r * FlightConfig.Gravity.DEFAULT_INFLUENCE_FACTOR
                out.inInfluence = h < influence
                out.influenceRadius = influence
                out.closingSpeed = if (d > 1e-4) (vel.x * tx + vel.y * ty + vel.z * tz) / d else 0.0
                out.timeToImpact = if (out.closingSpeed > 0.05 && hAgl > 0.0) hAgl / out.closingSpeed else Double.POSITIVE_INFINITY
                out.surfaceGravityG = b.gravityG
                if (h < r * FlightConfig.Gravity.SURFACE_GRAVITY_RANGE && d > 1e-3) {
                    val ratio = r / max(d, r)
                    val pull = b.gravityG * ratio.pow(FlightConfig.Gravity.GRAVITY_FALLOFF)
                    out.gravityG = pull
                    val k = pull * law.pullPerG * law.gravityScale / d
                    out.gravity.set(tx * k, ty * k, tz * k)
                } else {
                    out.gravityG = 0.0
                    out.gravity.zero()
                }
                out.landingBlocked = b.landingBlocked
            }
        }
    }

    /**
     * The stations (`stations:getLimit` / `getApproachLimit`, absolute: DECISIONS 9): within the
     * docking range of the nearest station its mass lock (260 m/s, 140 within 2.2 km, 60 in the
     * hangar), else the gap law towards its docking range. [inStationFrame]: the ship rides in a
     * station's frame, where nothing pulls.
     */
    fun composeStations(system: StarSystem, pos: DVec3, vel: DVec3, inStationFrame: Boolean, out: GravityState) {
        var best = -1
        var bestD = Double.MAX_VALUE
        val stations = system.stations
        for (i in stations.indices) {
            val s = stations[i].pos
            val dx = s.x - pos.x
            val dy = s.y - pos.y
            val dz = s.z - pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        out.station = best
        out.stationDistance = if (best >= 0) bestD else Double.POSITIVE_INFINITY
        if (inStationFrame) out.gravity.zero()
        if (best < 0) return
        val st = stations[best]
        val sc = FlightConfig.Stations
        if (bestD <= sc.DOCKING_RANGE) {
            var lim = sc.MASS_LOCK_SPEED
            if (bestD < sc.ZONE_RADIUS) lim = sc.ZONE_SPEED
            out.inHangar = inHangar(st, pos)
            if (out.inHangar) lim = sc.HANGAR_SPEED
            if (lim < out.speedLimit) {
                out.limitZone = LimitZone.STATION
                out.limitingBody = -1
            }
            out.speedLimit = min(out.speedLimit, lim)
            out.inMassShadow = true
            out.stationLock = true
        } else {
            val speed = vel.length()
            val cos = if (speed > 1.0 && bestD > 1e-3) {
                (vel.x * (st.pos.x - pos.x) + vel.y * (st.pos.y - pos.y) + vel.z * (st.pos.z - pos.z)) / (speed * bestD)
            } else {
                1.0
            }
            val approach = SpeedLaw.cruiseLimit(sc.MASS_LOCK_SPEED, bestD - sc.DOCKING_RANGE, SpeedLaw.cruiseTime(cos), law.cruiseCap)
            if (approach < out.speedLimit) {
                out.limitZone = LimitZone.CRUISE
                out.limitCosine = cos
                out.limitingBody = -1
            }
            out.speedLimit = min(out.speedLimit, approach)
        }
    }

    /** `ShipStation:isInHangar` for the stand-in stations: inside the docking slot, or within 20 m in front of its mouth. */
    fun inHangar(st: StarSystem.Station, pos: DVec3): Boolean {
        st.rot.rotateInverse((pos.x - st.pos.x).toFloat(), (pos.y - st.pos.y).toFloat(), (pos.z - st.pos.z).toFloat(), local)
        StationMesh.dockPort(st.model, portPos, portDir)
        StationMesh.slotSize(st.model, slot)
        // the tunnel runs from the port against the approach direction (+Z inward for -Z ports)
        val along = -((local.x - portPos.x) * portDir.x + (local.y - portPos.y) * portDir.y + (local.z - portPos.z) * portDir.z)
        if (along < -HANGAR_MOUTH || along > slot.z) return false
        return abs(local.x - portPos.x) <= slot.x && abs(local.y - portPos.y) <= slot.y
    }

    /**
     * The field edge (`ShipGravity.edgeLimit` in `ShipClient`): a sphere of the system's edge radius
     * round the star; flying outward the limit falls to `EdgeSpeed` (150 m/s) `EdgeMargin` before it.
     * Returns the edge limit.
     */
    fun composeEdge(system: StarSystem, pos: DVec3, vel: DVec3, out: GravityState): Double {
        val star = system.bodies[system.star].pos
        val px = pos.x - star.x
        val py = pos.y - star.y
        val pz = pos.z - star.z
        val r = sqrt(px * px + py * py + pz * pz)
        val speed = vel.length()
        val cos = if (speed > 1.0 && r > 1e-3) (vel.x * px + vel.y * py + vel.z * pz) / (speed * r) else 1.0
        val edge = SpeedLaw.edgeLimit(system.edgeRadius, r, cos, law.cruiseCap)
        out.edgeGap = if (system.edgeRadius > 0.0) system.edgeRadius - r else Double.POSITIVE_INFINITY
        if (edge < Double.POSITIVE_INFINITY) {
            if (edge < out.speedLimit) {
                out.limitZone = LimitZone.EDGE
                out.limitingBody = -1
            }
            out.speedLimit = min(out.speedLimit, edge)
        }
        return edge
    }

    companion object {
        /** The hangar box reaches this far out of the mouth (`isInHangar`: `half.Z + 20`), m. */
        const val HANGAR_MOUTH = 20.0
    }
}
