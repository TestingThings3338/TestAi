package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.approach
import com.starwake.core.math.clamp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Performance envelope and collision shape of a ship hull. Speeds in m/s, rates in rad/s,
 * accelerations per second, lengths in metres (ship space: origin = flight model pivot, nose -Z).
 *
 * The flight numbers are still the same for every hull (the Roblox handling arrives with the flight
 * model port); the shape comes from the hull: Roblox's collision probes ([fromModel]).
 */
class ShipSpec(
    val name: String,
    val role: String,
    /** Price in credits (CR). */
    val price: Int,
    val maxSpeed: Float = 220f,
    val maxReverse: Float = 90f,
    val boostSpeed: Float = 400f,
    val accelForward: Float = 80f,
    val accelReverse: Float = 60f,
    val accelLateral: Float = 50f,
    /** Extra lateral authority Flight Assist uses to keep velocity aligned with the nose. */
    val assistLateral: Float = 170f,
    val maxStrafeSpeed: Float = 70f,
    val maxPitchRate: Float = 1.15f,
    val maxYawRate: Float = 0.55f,
    val maxRollRate: Float = 2.45f,
    val angAccelPitch: Float = 3.6f,
    val angAccelYaw: Float = 2.0f,
    val angAccelRoll: Float = 8.5f,
    val boostDuration: Float = 1.8f,
    val boostCost: Float = 0.42f,
    val boostAccelMultiplier: Float = 2.6f,
    val capacitorRecharge: Float = 0.12f,
    /** Radius of every collision probe sphere. */
    val collisionRadius: Float = 7.0f,
    /** Centres of the collision probes, ship space (x, y, z per probe). */
    val probes: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Height of the origin above the ground when the ship stands on its landing gear. */
    val groundClearance: Float = collisionRadius,
    /** Size of the hull (x width, y height, z length), metres. */
    val sizeX: Float = 2f * collisionRadius,
    val sizeY: Float = 2f * collisionRadius,
    val sizeZ: Float = 2f * collisionRadius,
) {
    /** Radius around the origin that holds every probe sphere (broad-phase bound). */
    val reach: Float

    /** Centre of the hull, ship space: the first probe sits there. */
    val centreX: Float get() = probes[0]
    val centreY: Float get() = probes[1]
    val centreZ: Float get() = probes[2]

    init {
        require(probes.isNotEmpty() && probes.size % 3 == 0) { "probes are x, y, z triples" }
        var r = 0f
        var i = 0
        while (i < probes.size) {
            r = maxOf(r, sqrt(probes[i] * probes[i] + probes[i + 1] * probes[i + 1] + probes[i + 2] * probes[i + 2]))
            i += 3
        }
        reach = r + collisionRadius
    }

    companion object {
        /** Flight envelope of every hull for now; a single 7 m sphere at the origin (the old stand-in hull). */
        val STANDARD = ShipSpec("STANDARD", "", 0)

        /**
         * Spec of a Roblox ship: name, role and price from the shipyard, the collision shape from the
         * model like ShipStation.probeFor: spheres of radius max(2, smallest bounds side / 2) at the
         * bounds centre (the first probe, [centreX]) and towards both ends (and both sides when the
         * ship is wide), and the ground clearance RootToGround - SeatOffset.Y.
         */
        fun fromModel(entry: com.starwake.core.content.ShipCatalog.Entry, m: com.starwake.core.content.ShipModel): ShipSpec {
            val sx = m.rbxBounds[0]
            val sy = m.rbxBounds[1]
            val sz = m.rbxBounds[2]
            val radius = maxOf(2f, minOf(sx, minOf(sy, sz)) * 0.5f)
            val cx = m.centreX
            val cy = m.centreY
            val cz = m.centreZ
            val alongZ = maxOf(0f, sz * 0.5f - radius)
            val alongX = maxOf(0f, sx * 0.5f - radius)
            val wide = alongX > radius * 0.5f
            val probes = FloatArray(if (wide) 15 else 9)
            fun put(i: Int, x: Float, y: Float, z: Float) {
                probes[i * 3] = x; probes[i * 3 + 1] = y; probes[i * 3 + 2] = z
            }
            put(0, cx, cy, cz)
            put(1, cx, cy, cz - alongZ)
            put(2, cx, cy, cz + alongZ)
            if (wide) {
                put(3, cx - alongX, cy, cz)
                put(4, cx + alongX, cy, cz)
            }
            val ground = m.rbxBounds[6] - m.eyeY
            return ShipSpec(
                entry.name, entry.role, entry.price,
                collisionRadius = radius, probes = probes, groundClearance = ground,
                sizeX = sx, sizeY = sy, sizeZ = sz,
            )
        }
    }
}

/** Pilot input for one simulation step. Axes are -1..1. */
class ControlInput {
    /** +1 = nose up. */
    var pitch = 0f

    /** +1 = nose right. */
    var yaw = 0f

    /** +1 = right wing down. */
    var roll = 0f

    /** -1 (full reverse) .. +1 (full ahead). Latches – it is a lever, not a spring. */
    var throttle = 0f
    var strafeX = 0f
    var strafeY = 0f
    var boost = false

    fun clearAxes() {
        pitch = 0f; yaw = 0f; roll = 0f; strafeX = 0f; strafeY = 0f; boost = false
    }
}

class ShipState {
    val pos = DVec3()
    val vel = Vec3()
    val rot = Quat()

    /** Body-frame angular velocity (x pitch, y yaw, z roll), rad/s. */
    val angVel = Vec3()
    var flightAssist = true
    var boostTime = 0f
    var capacitor = 1f

    /** Smoothed main-engine output 0..~1.6 for visuals. */
    var engineOutput = 0f
    var speed = 0f

    /** Forward component of velocity (m/s, positive when moving nose-first). */
    var forwardSpeed = 0f

    /** Current speed limit; jumps to boost speed while boosting, then bleeds back smoothly. */
    var speedCap = 0f
    val boosting get() = boostTime > 0f

    fun reset() {
        pos.set(0.0, 0.0, 0.0)
        vel.zero()
        rot.identity()
        angVel.zero()
        flightAssist = true
        boostTime = 0f
        capacitor = 1f
        engineOutput = 0f
        speed = 0f
        forwardSpeed = 0f
        speedCap = 0f
    }
}

/**
 * Six-degrees-of-freedom flight model.
 *
 * Flight Assist ON: the stick commands rotation *rates* and the throttle commands a *speed*; the
 * thrusters fight drift so the ship flies where it points.
 * Flight Assist OFF: inputs are raw thrust/torque – rotation and velocity persist (Newtonian).
 */
class FlightModel(var spec: ShipSpec) {
    private val local = Vec3()
    private val tmp = Vec3()
    private val probe = Vec3()

    /**
     * Advances the ship by [dt]. [limit] is the dynamic speed limit for the current location
     * (≥ the ship's normal top speed; up to 150 Mm/s far from planets and stations). Throttle maps
     * to a fraction of it, and acceleration, braking and drift correction scale with it, so the
     * ship covers interplanetary distances yet slows smoothly when approaching a body.
     * Returns true on the step where a boost was triggered.
     */
    fun step(s: ShipState, c: ControlInput, dt: Float, limit: Float = spec.maxSpeed): Boolean {
        val sp = spec
        // a limit below the hull's normal top speed (landing mode) is a hard cap, boost included
        val capped = limit < sp.maxSpeed
        val vmax = limit
        // boost: the usual 400 m/s locally, +25 % on top of the dynamic limit in deep space
        val boostTop = if (capped) vmax else maxOf(sp.boostSpeed, vmax * 1.25f)
        var boostStarted = false
        if (c.boost && s.boostTime <= 0f && s.capacitor >= sp.boostCost) {
            s.boostTime = sp.boostDuration
            s.capacitor -= sp.boostCost
            boostStarted = true
        }
        val boosting = s.boostTime > 0f
        if (boosting) s.boostTime -= dt else s.capacitor = (s.capacitor + sp.capacitorRecharge * dt).coerceAtMost(1f)

        // ---- rotation
        val tPitch = clamp(c.pitch, -1f, 1f) * sp.maxPitchRate
        val tYaw = -clamp(c.yaw, -1f, 1f) * sp.maxYawRate
        val tRoll = -clamp(c.roll, -1f, 1f) * sp.maxRollRate
        val w = s.angVel
        if (s.flightAssist) {
            w.x = approach(w.x, tPitch, sp.angAccelPitch * dt)
            w.y = approach(w.y, tYaw, sp.angAccelYaw * dt)
            w.z = approach(w.z, tRoll, sp.angAccelRoll * dt)
        } else {
            w.x = clamp(w.x + clamp(c.pitch, -1f, 1f) * sp.angAccelPitch * dt, -sp.maxPitchRate, sp.maxPitchRate)
            w.y = clamp(w.y - clamp(c.yaw, -1f, 1f) * sp.angAccelYaw * dt, -sp.maxYawRate, sp.maxYawRate)
            w.z = clamp(w.z - clamp(c.roll, -1f, 1f) * sp.angAccelRoll * dt, -sp.maxRollRate, sp.maxRollRate)
        }
        s.rot.integrateLocal(w.x, w.y, w.z, dt)

        // ---- translation, in the ship's local frame (forward = -Z)
        s.rot.rotateInverse(s.vel, local)
        val throttle = clamp(c.throttle, -1f, 1f)
        var thrust: Float
        if (s.flightAssist) {
            val targetZ = when {
                boosting -> -boostTop
                throttle >= 0f -> -throttle * vmax
                else -> -throttle * sp.maxReverse
            }
            // engines scale with the regime: ~4 s to reach any limit, braking proportional to speed
            val fwdAccel = maxOf(sp.accelForward, vmax * 0.25f) * if (boosting && !capped) sp.boostAccelMultiplier else 1f
            // above manoeuvring speeds braking becomes proportional to speed (exponential slow-down)
            val fast = maxOf(0f, abs(local.z) - sp.boostSpeed)
            val brake = maxOf(sp.accelReverse, fast * 0.9f)
            val dz = targetZ - local.z
            // dz < 0: need more forward speed (main engines); dz > 0: braking (retro thrusters)
            val limitZ = (if (dz < 0f) fwdAccel else brake) * dt
            local.z += clamp(dz, -limitZ, limitZ)
            thrust = if (dz < -0.5f) (-dz / maxOf(40f, vmax * 0.2f)).coerceAtMost(1f) else 0.15f
            // drift correction gets stronger with speed so the ship follows its nose at Mm/s
            val lat = maxOf(sp.assistLateral, fast * 2.5f) * dt
            local.x = approach(local.x, clamp(c.strafeX, -1f, 1f) * sp.maxStrafeSpeed, lat)
            local.y = approach(local.y, clamp(c.strafeY, -1f, 1f) * sp.maxStrafeSpeed, lat)
        } else {
            val az = when {
                boosting -> -sp.accelForward * sp.boostAccelMultiplier
                throttle >= 0f -> -throttle * sp.accelForward
                else -> -throttle * sp.accelReverse
            }
            local.z += az * dt
            local.x += clamp(c.strafeX, -1f, 1f) * sp.accelLateral * dt
            local.y += clamp(c.strafeY, -1f, 1f) * sp.accelLateral * dt
            thrust = (if (az < 0f) -az / sp.accelForward else 0f).coerceAtMost(1f)
        }
        if (boosting) thrust = 1.6f
        s.rot.rotate(local, s.vel)

        // hard speed limit; after a boost (or when the dynamic limit drops) it bleeds down smoothly
        s.speedCap = if (boosting) {
            boostTop
        } else {
            maxOf(vmax, s.speedCap - maxOf(sp.accelReverse * 1.5f, s.speedCap * 0.9f) * dt)
        }
        val speed = s.vel.length()
        if (speed > s.speedCap) s.vel.scale(s.speedCap / speed)
        s.speed = s.vel.length()
        s.rot.rotate(0f, 0f, -1f, tmp)
        s.forwardSpeed = s.vel.dot(tmp)
        s.pos.addScaled(s.vel, dt.toDouble())
        s.engineOutput += (thrust - s.engineOutput) * (1f - kotlin.math.exp(-dt * 6f))
        return boostStarted
    }

    /**
     * Pushes the ship's collision probes out of a sphere and bounces the ship (each probe in turn,
     * against the position the previous one left). Returns the largest impact speed (0 = no contact).
     * Double precision throughout: planet radii are millions of metres.
     */
    fun collideSphere(s: ShipState, cx: Double, cy: Double, cz: Double, radius: Double): Float {
        val p = spec.probes
        val minDist = radius + spec.collisionRadius
        var impact = 0f
        var i = 0
        while (i < p.size) {
            s.rot.rotate(p[i], p[i + 1], p[i + 2], probe)
            i += 3
            val dx = s.pos.x + probe.x - cx
            val dy = s.pos.y + probe.y - cy
            val dz = s.pos.z + probe.z - cz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 >= minDist * minDist) continue
            val d = sqrt(d2)
            // exactly at the centre there is no direction to push along: pick "up"
            val hit = if (d <= 1e-4) {
                resolveContact(s, 0f, 1f, 0f, minDist - d)
            } else {
                resolveContact(s, (dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat(), minDist - d)
            }
            if (hit > impact) impact = hit
        }
        return impact
    }

    /** Gap (m) between the nearest collision probe and a sphere; negative while touching. */
    fun clearance(s: ShipState, cx: Double, cy: Double, cz: Double, radius: Double): Double {
        val p = spec.probes
        var best = Double.MAX_VALUE
        var i = 0
        while (i < p.size) {
            s.rot.rotate(p[i], p[i + 1], p[i + 2], probe)
            i += 3
            val dx = s.pos.x + probe.x - cx
            val dy = s.pos.y + probe.y - cy
            val dz = s.pos.z + probe.z - cz
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < best) best = d
        }
        return best - radius - spec.collisionRadius
    }

    /**
     * Resolves a contact: moves the ship [push] metres along the unit normal (nx, ny, nz) that
     * points out of the obstacle, then bounces it. Returns the impact speed (0 if separating).
     */
    fun resolveContact(s: ShipState, nx: Float, ny: Float, nz: Float, push: Double): Float {
        if (push > 0.0) {
            s.pos.x += nx * push
            s.pos.y += ny * push
            s.pos.z += nz * push
        }
        val vn = s.vel.dot(nx, ny, nz)
        if (vn >= 0f) return 0f
        // reflect the normal component with restitution, keep some tangential speed
        val restitution = 0.35f
        s.vel.add(-nx * vn * (1f + restitution), -ny * vn * (1f + restitution), -nz * vn * (1f + restitution))
        s.vel.scale(0.8f)
        // knock the ship's rotation a little (normal converted to the ship's own frame)
        s.rot.rotateInverse(nx, ny, nz, tmp)
        val k = vn * 0.004f
        s.angVel.add((tmp.y + 0.3f * tmp.z) * k, -tmp.x * k, 0.75f * (tmp.x - tmp.y) * k)
        return -vn
    }
}
