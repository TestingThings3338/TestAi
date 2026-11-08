package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.approach
import com.starwake.core.math.clamp
import kotlin.math.sqrt

/** Performance envelope of a ship hull. Speeds in m/s, rates in rad/s, accelerations per second. */
class ShipSpec(
    val name: String,
    val designation: String,
    val role: String,
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
    val collisionRadius: Float = 7.0f,
) {
    companion object {
        val KITE = ShipSpec("KITE", "KT-1", "LIGHT MULTIROLE")
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

    /** Returns true on the step where a boost was triggered. */
    fun step(s: ShipState, c: ControlInput, dt: Float): Boolean {
        val sp = spec
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
                boosting -> -sp.boostSpeed
                throttle >= 0f -> -throttle * sp.maxSpeed
                else -> -throttle * sp.maxReverse
            }
            val fwdAccel = sp.accelForward * if (boosting) sp.boostAccelMultiplier else 1f
            val dz = targetZ - local.z
            // dz < 0: need more forward speed (main engines); dz > 0: braking (retro thrusters)
            val limitZ = (if (dz < 0f) fwdAccel else sp.accelReverse) * dt
            local.z += clamp(dz, -limitZ, limitZ)
            thrust = if (dz < -0.5f) (-dz / 40f).coerceAtMost(1f) else 0.15f
            val lat = sp.assistLateral * dt
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

        // hard speed limit; after a boost the limit bleeds back down to max speed smoothly
        s.speedCap = if (boosting) sp.boostSpeed else maxOf(sp.maxSpeed, s.speedCap - sp.accelReverse * 1.5f * dt)
        val speed = s.vel.length()
        if (speed > s.speedCap) s.vel.scale(s.speedCap / speed)
        s.speed = s.vel.length()
        s.rot.rotate(0f, 0f, -1f, tmp)
        s.forwardSpeed = s.vel.dot(tmp)
        s.pos.addScaled(s.vel, dt.toDouble())
        s.engineOutput += (thrust - s.engineOutput) * (1f - kotlin.math.exp(-dt * 6f))
        return boostStarted
    }

    /** Pushes the ship out of a sphere and bounces it. Returns the impact speed (0 = no contact). */
    fun collideSphere(s: ShipState, cx: Double, cy: Double, cz: Double, radius: Float): Float {
        val dx = (s.pos.x - cx).toFloat()
        val dy = (s.pos.y - cy).toFloat()
        val dz = (s.pos.z - cz).toFloat()
        val minDist = radius + spec.collisionRadius
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 >= minDist * minDist) return 0f
        val d = sqrt(d2)
        // exactly at the centre there is no direction to push along: pick "up"
        val nx = if (d > 1e-4f) dx / d else 0f
        val ny = if (d > 1e-4f) dy / d else 1f
        val nz = if (d > 1e-4f) dz / d else 0f
        val push = (minDist - d).toDouble()
        s.pos.x += nx * push
        s.pos.y += ny * push
        s.pos.z += nz * push
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
