package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.expApproach
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lagging third-person chase camera: the optional third view (DECISIONS 21a; Roblox has only
 * the cockpit and the rigid external view, see [ShipCamera]). Its rotation lags the ship's, so the
 * hull banks into turns, and it backs off a little at speed.
 *
 * It circles the ship's centre [centre] (ship space) at [chaseOffset] (ship space, relative to that
 * centre) with a downward tilt; see [configure].
 */
class ChaseCamera {
    val pos = DVec3()
    val rot = Quat()
    var fovBoost = 0f
    private val offset = Vec3()
    private val shake = Vec3()
    private val centreWorld = Vec3()
    private var shakeTime = 0f
    private var shakeAmount = 0f
    private val chaseTilt = Quat().setAxisAngle(1f, 0f, 0f, -0.06f)

    /** Point the chase view looks after (ship space). */
    val centre = Vec3()
    val chaseOffset = Vec3(0f, 4f, 16.5f)

    /** Extra distance at top speed, as a share of the offset (the camera falls back as you speed up). */
    var speedPull = 3f / 16.5f

    /**
     * Sets the view for a ship: its centre and the chase offset (ship space, from the centre) and
     * tilt (radians, negative = looking down).
     */
    fun configure(centre: Vec3, chase: Vec3, chasePitch: Float) {
        this.centre.set(centre)
        chaseOffset.set(chase)
        chaseTilt.setAxisAngle(1f, 0f, 0f, chasePitch)
    }

    /** Distance from the camera to the ship's centre (no speed pull). */
    val viewDistance: Float get() = chaseOffset.length()

    fun snapTo(ship: ShipState) {
        rot.set(ship.rot)
        update(ship, 0f, 0f, 1f)
    }

    fun addShake(amount: Float) {
        shakeAmount = maxOf(shakeAmount, amount)
    }

    /** [speedFrac] 0..1 of max speed. */
    fun update(ship: ShipState, dt: Float, speedFrac: Float, maxSpeed: Float) {
        val k = if (dt <= 0f) 1f else expApproach(7.5f, dt)
        rot.slerp(rot, ship.rot, k)
        val o = chaseOffset
        val pull = 1f + speedFrac * speedPull
        rot.rotate(o.x * pull, o.y * pull, o.z * pull, offset)
        // circle the ship's centre: lag swings the camera, not the hull through the frame
        ship.rot.rotate(centre, centreWorld)
        offset.add(centreWorld)
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

    /** Orientation actually used for rendering (adds the downward tilt). */
    fun renderRotation(out: Quat): Quat = out.mul(rot, chaseTilt)
}

/** Slow cinematic orbit used behind the main menu; the player can drag to look around. */
class OrbitCamera {
    var yaw = 2.35f
    var pitch = 0.28f
    var distance = 25f
    var autoSpin = 0.06f

    /** The pitch the idle camera drifts back to. */
    var restPitch = 0.28f
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
            pitch += (restPitch - pitch) * expApproach(0.4f, dt) * k
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
