package com.starwake.core.terrain

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** Result of [PlanetSurface.floorHit]. */
class FloorHit {
    /** The floor point below the query (world space). */
    val point = DVec3()

    /** Floor normal (world space), Roblox's `unit(4·up − dE·east − dN·north)`. */
    val normal = Vec3()

    /** Height of the query point above the floor (m). */
    var altitude = 0.0

    /** True when the floor there is water (Roblox: one can set down on water). */
    var water = false
}

/**
 * Ground queries (§9.10) from the same height function the tiles use, at the finest band limit
 * (exact): altitude above ground or water, up, gravity at 35 %, the analytic floor and its normal,
 * the ground state for the crash rule, colour and material at a point. Game thread; allocation
 * free (one [FieldScratch]); about 2–5 µs per floor evaluation (the scratch keeps the features of
 * a 48 m box around the last point, so consecutive queries nearby skip the feature search).
 *
 * Positions are world space (doubles); the body pose is set with [setPose] (planets never spin,
 * DECISIONS 7, so this is constant in practice).
 */
class PlanetSurface(val field: SurfaceField) {
    private val s = FieldScratch()
    private val sample = SurfaceSample()
    private val colour = VertexColour()
    private val q = DoubleArray(3)

    /** Body centre (world) and rotation (body → world) as a double matrix (rows). */
    val centre = DVec3()
    private val m = DoubleArray(9).also { it[0] = 1.0; it[4] = 1.0; it[8] = 1.0 }

    /** The quadtree whose drawn leaves decide [groundState] (null: always SOLID). */
    var tree: TerrainQuadtree? = null

    /** Surface gravity in g (DECISIONS 5: 9.81 m/s² per g, damped to 35 %). */
    var gravityG = 1.0

    fun setPose(pos: DVec3, rot: Quat) {
        centre.set(pos)
        // normalise in double: a float quaternion is off unit length by ~1e-7, 0.6 m at Earth's radius
        val x = rot.x.toDouble()
        val y = rot.y.toDouble()
        val z = rot.z.toDouble()
        val w = rot.w.toDouble()
        val l = sqrt(x * x + y * y + z * z + w * w)
        val qx = x / l
        val qy = y / l
        val qz = z / l
        val qw = w / l
        m[0] = 1 - 2 * (qy * qy + qz * qz); m[1] = 2 * (qx * qy - qw * qz); m[2] = 2 * (qx * qz + qw * qy)
        m[3] = 2 * (qx * qy + qw * qz); m[4] = 1 - 2 * (qx * qx + qz * qz); m[5] = 2 * (qy * qz - qw * qx)
        m[6] = 2 * (qx * qz - qw * qy); m[7] = 2 * (qy * qz + qw * qx); m[8] = 1 - 2 * (qx * qx + qy * qy)
    }

    /** The body → world rotation matrix (row-major, 9 doubles; read only). */
    fun rotation(): DoubleArray = m

    /** World point → body space (metres from the centre), into out[0..2]. */
    fun toBody(px: Double, py: Double, pz: Double, out: DoubleArray) {
        val dx = px - centre.x
        val dy = py - centre.y
        val dz = pz - centre.z
        out[0] = m[0] * dx + m[3] * dy + m[6] * dz
        out[1] = m[1] * dx + m[4] * dy + m[7] * dz
        out[2] = m[2] * dx + m[5] * dy + m[8] * dz
    }

    /** Body-space vector → world, into [out] (float). */
    fun toWorld(bx: Double, by: Double, bz: Double, out: Vec3) {
        out.set(
            (m[0] * bx + m[1] * by + m[2] * bz).toFloat(),
            (m[3] * bx + m[4] * by + m[5] * bz).toFloat(),
            (m[6] * bx + m[7] * by + m[8] * bz).toFloat(),
        )
    }

    /** Radius of the ground at unit direction n (body space). */
    fun heightAt(nx: Double, ny: Double, nz: Double): Double {
        field.sampleAt(nx, ny, nz, s, sample)
        return sample.groundR
    }

    /** Radius of what one stands on (ground or water) at unit direction n (body space). */
    fun floorAt(nx: Double, ny: Double, nz: Double): Double {
        field.sampleAt(nx, ny, nz, s, sample)
        return sample.floorR
    }

    /** Height of world point [p] above the ground or water below it (m): hAgl. */
    fun altitudeAt(p: DVec3): Double {
        toBody(p.x, p.y, p.z, q)
        val r = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
        if (r < 1e-6) return -field.radius
        return r - floorAt(q[0] / r, q[1] / r, q[2] / r)
    }

    /** Unit vector away from the centre at [p] (world). */
    fun upAt(p: DVec3, out: Vec3): Vec3 {
        val dx = p.x - centre.x
        val dy = p.y - centre.y
        val dz = p.z - centre.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        if (l < 1e-9) return out.set(0f, 1f, 0f)
        return out.set((dx / l).toFloat(), (dy / l).toFloat(), (dz / l).toFloat())
    }

    /** Gravity at [p]: −up·g·9.81·0.35 (m/s², world). */
    fun gravityAt(p: DVec3, out: Vec3): Vec3 {
        upAt(p, out)
        val a = (gravityG * G * GRAVITY_SCALE).toFloat()
        return out.scale(-a)
    }

    /**
     * The floor below [p] and its normal (§9.10): nil (false) when [p] is more than [reach] above
     * it. The normal is Roblox's `unit(4·up − dE·east − dN·north)` from floor samples ±2 m east and
     * north.
     */
    fun floorHit(p: DVec3, reach: Double, out: FloorHit): Boolean {
        toBody(p.x, p.y, p.z, q)
        val r = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
        if (r < 1e-6) return false
        val ux = q[0] / r
        val uy = q[1] / r
        val uz = q[2] / r
        field.sampleAt(ux, uy, uz, s, sample)
        val floor = sample.floorR
        val alt = r - floor
        if (alt > reach) return false
        out.altitude = alt
        out.water = sample.isWater
        // tangent frame: east = unit(Y × up) (X × up near the poles), north = up × east
        var ex: Double
        var ey: Double
        var ez: Double
        if (abs(uy) < 0.999) {
            ex = uz; ey = 0.0; ez = -ux
        } else {
            ex = 0.0; ey = -uz; ez = uy
        }
        val el = sqrt(ex * ex + ey * ey + ez * ez)
        ex /= el; ey /= el; ez /= el
        val nx = uy * ez - uz * ey
        val ny = uz * ex - ux * ez
        val nz = ux * ey - uy * ex
        val de = floorAlong(ux, uy, uz, floor, ex, ey, ez, 2.0) - floorAlong(ux, uy, uz, floor, ex, ey, ez, -2.0)
        val dn = floorAlong(ux, uy, uz, floor, nx, ny, nz, 2.0) - floorAlong(ux, uy, uz, floor, nx, ny, nz, -2.0)
        var gx = 4 * ux - de * ex - dn * nx
        var gy = 4 * uy - de * ey - dn * ny
        var gz = 4 * uz - de * ez - dn * nz
        val gl = sqrt(gx * gx + gy * gy + gz * gz)
        gx /= gl; gy /= gl; gz /= gl
        toWorld(gx, gy, gz, out.normal)
        // the floor point: along the radial line through p
        val wx = m[0] * ux + m[1] * uy + m[2] * uz
        val wy = m[3] * ux + m[4] * uy + m[5] * uz
        val wz = m[6] * ux + m[7] * uy + m[8] * uz
        out.point.set(centre.x + wx * floor, centre.y + wy * floor, centre.z + wz * floor)
        return true
    }

    /** Floor radius at the point [d] metres along the unit tangent t from the floor point of direction u. */
    private fun floorAlong(ux: Double, uy: Double, uz: Double, r: Double, tx: Double, ty: Double, tz: Double, d: Double): Double {
        val x = ux * r + tx * d
        val y = uy * r + ty * d
        val z = uz * r + tz * d
        val l = sqrt(x * x + y * y + z * z)
        return floorAt(x / l, y / l, z / l)
    }

    /**
     * Ground state for the crash rule (§4.4, §9.10): SOLID when the drawn leaf under [p] is at the
     * finest level (or its geometric error is below 1 m) and has been drawn for ≥ 0.6 s; FRESH when
     * younger; MISSING when the drawn leaf is coarser or nothing is drawn there yet.
     */
    fun groundState(p: DVec3): Int {
        val t = tree ?: return SOLID
        toBody(p.x, p.y, p.z, q)
        val r = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
        if (r < 1e-6) return MISSING
        val leaf = t.leafAt(q[0] / r, q[1] / r, q[2] / r)
        if (leaf < 0) return MISSING
        if (t.level[leaf] < t.maxLevel && t.geomError[leaf] >= 1f) return MISSING
        return if (t.time - t.cutSince[leaf] >= FRESH_GRACE) SOLID else FRESH
    }

    /** The colour at [p] (sRGB 0..1 into out[0..2]) as the finest tiles paint it. */
    fun surfaceColour(p: DVec3, out: FloatArray) {
        material(p)
        out[0] = colour.r.toFloat()
        out[1] = colour.g.toFloat()
        out[2] = colour.b.toFloat()
    }

    /** Top material name at [p] (one of the style's names; no allocation). */
    fun materialAt(p: DVec3): String {
        material(p)
        return field.materialName(colour.material)
    }

    private fun material(p: DVec3) {
        toBody(p.x, p.y, p.z, q)
        val r = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
        val ux = q[0] / r
        val uy = q[1] / r
        val uz = q[2] / r
        // slope from ±2 m differences of the ground (as the finest tiles measure it)
        var ex: Double
        var ey: Double
        var ez: Double
        if (abs(uy) < 0.999) {
            ex = uz; ey = 0.0; ez = -ux
        } else {
            ex = 0.0; ey = -uz; ez = uy
        }
        val el = sqrt(ex * ex + ey * ey + ez * ez)
        ex /= el; ey /= el; ez /= el
        val nx = uy * ez - uz * ey
        val ny = uz * ex - ux * ez
        val nz = ux * ey - uy * ex
        val gr = field.radius
        val ge = (groundAlong(ux, uy, uz, gr, ex, ey, ez, 2.0) - groundAlong(ux, uy, uz, gr, ex, ey, ez, -2.0)) / 4.0
        val gn = (groundAlong(ux, uy, uz, gr, nx, ny, nz, 2.0) - groundAlong(ux, uy, uz, gr, nx, ny, nz, -2.0)) / 4.0
        val slope = sqrt(ge * ge + gn * gn)
        field.sampleAt(ux, uy, uz, s, sample)
        field.colour(sample, slope, 0.0, s, colour)
    }

    private fun groundAlong(ux: Double, uy: Double, uz: Double, r: Double, tx: Double, ty: Double, tz: Double, d: Double): Double {
        val x = ux * r + tx * d
        val y = uy * r + ty * d
        val z = uz * r + tz * d
        val l = sqrt(x * x + y * y + z * z)
        return heightAt(x / l, y / l, z / l)
    }

    private val hit = FloorHit()
    private val upTmp = Vec3()

    /**
     * Ground contact, the Roblox rule (ShipClient.lua:2598-2642, §9.10): within [clearance] of the
     * floor, impact = max(sink rate, −0.5·v·n) (into a slope half the speed counts); a crash when the
     * impact exceeds [SAFE_LANDING_SPEED] on SOLID ground (never on ground that is MISSING or FRESH:
     * "a ship crashes only on ground it can see"); otherwise the velocity into the floor is removed
     * and the ship is placed at floor + n·clearance. Writes [out]; returns true when touching.
     */
    fun contact(pos: DVec3, vel: Vec3, clearance: Double, out: Contact): Boolean {
        out.touching = false
        out.crashed = false
        out.landed = false
        out.impact = 0f
        out.position.set(pos)
        out.velocity.set(vel)
        if (!floorHit(pos, GROUND_PROBE, hit)) return false
        out.altitude = hit.altitude
        out.state = groundState(pos)
        out.landed = hit.altitude < clearance + 0.6
        if (hit.altitude >= clearance) return false
        out.touching = true
        val up = upAt(pos, upTmp)
        val n = hit.normal
        val sink = -(vel.x * up.x + vel.y * up.y + vel.z * up.z)
        val into = -(vel.x * n.x + vel.y * n.y + vel.z * n.z)
        out.impact = maxOf(sink, 0.5f * into)
        if (out.impact > SAFE_LANDING_SPEED && out.state == SOLID) {
            out.crashed = true
            return true
        }
        if (into > 0f) out.velocity.addScaled(n, into)
        out.position.set(
            hit.point.x + n.x * clearance,
            hit.point.y + n.y * clearance,
            hit.point.z + n.z * clearance,
        )
        out.landed = true
        return true
    }

    /** Result of [contact]. */
    class Contact {
        var touching = false
        var crashed = false
        var landed = false
        var impact = 0f
        var altitude = 0.0
        var state = SOLID
        val position = DVec3()
        val velocity = Vec3()
    }

    /** Near-ground speed limit (DECISIONS 3): clamp(0.35·h, 260, 4000) m/s. */
    fun speedLimit(altitude: Double): Double = (altitude * 0.35).coerceIn(260.0, 4000.0)

    companion object {
        const val SOLID = 0
        const val FRESH = 1
        const val MISSING = 2

        const val G = 9.81
        const val GRAVITY_SCALE = 0.35
        const val FRESH_GRACE = 0.6

        /** Roblox `Surface.SafeLandingSpeed`: crash above this impact on visible ground (m/s). */
        const val SAFE_LANDING_SPEED = 38f

        /** Roblox `Terrain.GroundClearance`: minimum hold height (m); the ship uses max(12, probe + 0.3). */
        const val GROUND_CLEARANCE = 12.0

        /** Roblox `GroundProbe`: the ground ray length and floorHit reach. */
        const val GROUND_PROBE = 400.0

        fun stateName(s: Int) = when (s) {
            SOLID -> "solid"
            FRESH -> "fresh"
            else -> "missing"
        }
    }
}
