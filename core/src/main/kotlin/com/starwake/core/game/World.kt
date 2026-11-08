package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.expApproach
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A field of tumbling asteroids. Instances are grouped by mesh variant so each variant renders in a
 * single instanced draw call. Positions are relative to [origin].
 */
class AsteroidField(seed: Long, val maxCount: Int, val variants: Int) {
    val origin = DVec3(0.0, 0.0, -1500.0)

    /** Per variant: (x, y, z, scale, axisX, axisY, axisZ, spin) per instance. */
    val instanceData: Array<FloatArray>
    val instanceCount: IntArray

    // flat arrays for collision tests (all variants)
    val px: FloatArray
    val py: FloatArray
    val pz: FloatArray
    val radius: FloatArray
    private val variantOf: IntArray

    /** Number of asteroids actually placed. */
    val total: Int

    init {
        val rng = Rng(seed)
        px = FloatArray(maxCount)
        py = FloatArray(maxCount)
        pz = FloatArray(maxCount)
        radius = FloatArray(maxCount)
        variantOf = IntArray(maxCount)
        val axis = Vec3()
        val tmpData = Array(variants) { FloatArray(maxCount * 8) }
        val counts = IntArray(variants)
        var placed = 0
        var attempts = 0
        // a few clumps give the field structure instead of uniform noise
        val clumps = Array(7) { Vec3(rng.signed() * 1400f, rng.signed() * 180f, rng.signed() * 1100f) }
        while (placed < maxCount && attempts < maxCount * 40) {
            attempts++
            val big = placed < 14
            val s = if (big) rng.range(70f, 150f) else 3f + rng.nextFloat().pow(3.2f) * 48f
            val x: Float
            val y: Float
            val z: Float
            if (rng.nextFloat() < 0.55f) {
                val c = clumps[rng.nextInt(clumps.size)]
                x = c.x + rng.gaussian() * 260f
                y = c.y + rng.gaussian() * 90f
                z = c.z + rng.gaussian() * 260f
            } else {
                val a = rng.nextFloat() * 6.2831855f
                val r = sqrt(rng.nextFloat()) * 2200f
                x = cos(a) * r
                y = rng.gaussian() * 140f
                z = sin(a) * r * 0.8f
            }
            // keep the spawn corridor and the menu showcase clear
            val wx = x + origin.x.toFloat()
            val wy = y + origin.y.toFloat()
            val wz = z + origin.z.toFloat()
            if (clearance(wx, wy, wz) < s + 140f) continue
            var overlap = false
            for (j in 0 until placed) {
                val dx = px[j] - x
                val dy = py[j] - y
                val dz = pz[j] - z
                val min = (radius[j] + s) * 1.05f
                if (dx * dx + dy * dy + dz * dz < min * min) {
                    overlap = true; break
                }
            }
            if (overlap) continue
            val v = rng.nextInt(variants)
            rng.onSphere(axis)
            val spin = rng.range(0.01f, 0.18f) * (20f / (s + 10f))
            val o = counts[v] * 8
            val d = tmpData[v]
            d[o] = x; d[o + 1] = y; d[o + 2] = z; d[o + 3] = s
            d[o + 4] = axis.x; d[o + 5] = axis.y; d[o + 6] = axis.z; d[o + 7] = spin
            counts[v]++
            px[placed] = x; py[placed] = y; pz[placed] = z; radius[placed] = s
            variantOf[placed] = v
            placed++
        }
        instanceData = tmpData
        instanceCount = counts
        total = placed
    }

    /** Distance from a world point to the protected areas (spawn lane, menu showcase). */
    private fun clearance(x: Float, y: Float, z: Float): Float {
        // spawn lane: segment from (0,0,200) to (0,0,-700)
        val cz = clamp(z, -700f, 200f)
        val lane = sqrt(x * x + y * y + (z - cz) * (z - cz))
        val sx = x - SHOWCASE_X
        val sy = y - SHOWCASE_Y
        val sz = z - SHOWCASE_Z
        return minOf(lane, sqrt(sx * sx + sy * sy + sz * sz) - 60f)
    }

    /** How many instances of [variant] to draw for a quality level (0..2). */
    fun drawCount(variant: Int, quality: Int): Int {
        val frac = when (quality) {
            0 -> 0.35f
            1 -> 0.65f
            else -> 1f
        }
        return (instanceCount[variant] * frac).toInt()
    }

    /** active[i] = asteroid i is drawn at the current quality (collisions only use visible rocks). */
    val active = BooleanArray(maxCount)

    fun setQuality(quality: Int) {
        val seen = IntArray(variants)
        for (i in 0 until total) {
            val v = variantOf[i]
            active[i] = seen[v] < drawCount(v, quality)
            seen[v]++
        }
    }

    companion object {
        const val SHOWCASE_X = 0f
        const val SHOWCASE_Y = 0f
        const val SHOWCASE_Z = 400f
    }
}

/** Third-person chase camera with rotational lag (shows the ship banking into turns). */
class ChaseCamera {
    val pos = DVec3()
    val rot = Quat()
    var mode = 0 // 0 chase, 1 far chase, 2 cockpit
    var fovBoost = 0f
    private val offset = Vec3()
    private val shake = Vec3()
    private var shakeTime = 0f
    private var shakeAmount = 0f
    private val pitchDown = Quat().setAxisAngle(1f, 0f, 0f, -0.06f)

    fun snapTo(ship: ShipState) {
        rot.set(ship.rot)
        update(ship, 0f, 0f, 1f)
    }

    fun addShake(amount: Float) {
        shakeAmount = maxOf(shakeAmount, amount)
    }

    /** [speedFrac] 0..1 of max speed. */
    fun update(ship: ShipState, dt: Float, speedFrac: Float, maxSpeed: Float) {
        if (mode == 2) {
            rot.set(ship.rot)
            ship.rot.rotate(0f, 1.05f, -2.6f, offset)
        } else {
            val k = if (dt <= 0f) 1f else expApproach(if (mode == 0) 7.5f else 5f, dt)
            rot.slerp(rot, ship.rot, k)
            val back = if (mode == 0) 16.5f else 28f
            val upOff = if (mode == 0) 4.0f else 7f
            rot.rotate(0f, upOff, back + speedFrac * 3f, offset)
        }
        shakeTime += dt
        shakeAmount *= kotlin.math.exp(-dt * 3.5f)
        val a = shakeAmount
        shake.set(
            sin(shakeTime * 37f) * a * 0.35f,
            sin(shakeTime * 53f + 1.3f) * a * 0.35f,
            0f,
        )
        pos.set(ship.pos).add(offset).add(shake)
    }

    /** Orientation actually used for rendering (adds the slight downward tilt in chase modes). */
    fun renderRotation(out: Quat): Quat = if (mode == 2) out.set(rot) else out.mul(rot, pitchDown)
}

/** Slow cinematic orbit used behind the main menu; the player can drag to look around. */
class OrbitCamera {
    var yaw = 2.35f
    var pitch = 0.28f
    var distance = 25f
    var autoSpin = 0.06f
    var idle = 10f
    private var yawVel = 0f
    private var pitchVel = 0f

    fun drag(dxPx: Float, dyPx: Float, pxPerRad: Float) {
        yawVel = 0f
        pitchVel = 0f
        yaw -= dxPx / pxPerRad
        pitch = clamp(pitch + dyPx / pxPerRad, -0.9f, 0.9f)
        idle = 0f
    }

    fun fling(vxPx: Float, vyPx: Float, pxPerRad: Float) {
        yawVel = -vxPx / pxPerRad
        pitchVel = vyPx / pxPerRad
    }

    fun update(dt: Float) {
        idle += dt
        yaw += yawVel * dt
        pitch = clamp(pitch + pitchVel * dt, -0.9f, 0.9f)
        val damp = kotlin.math.exp(-dt * 3f)
        yawVel *= damp
        pitchVel *= damp
        if (idle > 2.5f) {
            val k = clamp((idle - 2.5f) / 3f, 0f, 1f)
            yaw += autoSpin * dt * k
            pitch += (0.28f - pitch) * expApproach(0.4f, dt) * k
        }
    }

    /** Writes camera position (relative to target) and rotation looking at the target. */
    fun apply(target: DVec3, outPos: DVec3, outRot: Quat) {
        val cp = cos(pitch)
        val ox = sin(yaw) * cp * distance
        val oy = sin(pitch) * distance
        val oz = cos(yaw) * cp * distance
        outPos.set(target.x + ox, target.y + oy, target.z + oz)
        outRot.lookRotation(-ox, -oy, -oz, 0f, 1f, 0f)
    }
}
