package com.starwake.core.flight

import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.applyDeadzone
import com.starwake.core.math.approach
import com.starwake.core.math.clamp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The ship's rotation (Roblox `ShipRotation`, `ShipClient/ShipRotation.lua:78-143`, 02-flight.md
 * 3.3): the stick commands a turn RATE in the ship's own frame (`cframe = cframe * smallRotation`:
 * no world up, no gimbal lock, endless loops).
 *
 * Sign conventions are Roblox's (and [Quat.integrateLocal]'s): +X raises the nose, +Y turns it
 * LEFT, +Z rolls LEFT. The cursor is the pilot's aim inside the unit circle with Y down; the key
 * axis is +1 for Q (yaw left). With [cursorRolls] (MOUSE X AXIS = ROLL, the default) cursor Y
 * pitches, cursor X rolls and the key axis yaws; otherwise (YAW, and always the autopilot) cursor X
 * yaws and the key axis rolls.
 *
 * With Flight Assist the rate approaches the command at the ship's turn acceleration (the computer
 * brakes the rotation when the stick is centred); without it the stick is torque (70 % of the turn
 * acceleration) and a spin continues until countered, up to 110 % of the turn rate.
 *
 * The rates live in the caller's [rates] vector (x pitch, y yaw, z roll, rad/s): the ship state
 * keeps them across a frame-of-reference shift and a teleport zeroes them. No allocation.
 */
class ShipRotation {
    /** Pitch + yaw input after the dead zone, 0..1 (audio). */
    var inputAmount = 0f
        private set

    /** Roll input, 0..1 (audio). */
    var rollAmount = 0f
        private set

    /** Agility of the last update (copy of the handling's). */
    var agility = 1f
        private set

    /** The command of the last update (rate fractions -1..1: x pitch, y yaw, z roll; tests, cockpit). */
    val command = Vec3()
    private val input = Vec3()

    /**
     * One frame: [aimX]/[aimY] the cursor (Y down, unit circle), [keyAxis] (+1 = Q), the handling,
     * Flight Assist and the cursor mapping. Updates [rates] and integrates [rot] by them.
     */
    fun update(
        rot: Quat, rates: Vec3, dt: Float, aimX: Float, aimY: Float, keyAxis: Float,
        h: ShipHandling, flightAssist: Boolean, cursorRolls: Boolean,
    ) {
        applyDeadzone(aimX, aimY, FlightConfig.Cursor.DEADZONE, FlightConfig.Rotation.INPUT_CURVE, input)
        val ix = input.x
        val iy = input.y
        agility = h.agility.toFloat()
        if (cursorRolls) {
            command.set(-iy, keyAxis, -ix)
            inputAmount = clamp(sqrt(iy * iy + keyAxis * keyAxis), 0f, 1f)
            rollAmount = clamp(abs(ix), 0f, 1f)
        } else {
            command.set(-iy, -ix, keyAxis)
            inputAmount = clamp(sqrt(ix * ix + iy * iy), 0f, 1f)
            rollAmount = clamp(abs(keyAxis), 0f, 1f)
        }
        if (flightAssist) {
            rates.x = approach(rates.x, command.x * h.turnRatePitch, h.turnAccelPitch * dt)
            rates.y = approach(rates.y, command.y * h.turnRateYaw, h.turnAccelYaw * dt)
            rates.z = approach(rates.z, command.z * h.turnRateRoll, h.turnAccelRoll * dt)
        } else {
            val k = FlightConfig.Handling.FA_OFF_TORQUE * dt
            val b = FlightConfig.Handling.FA_OFF_RATE_BONUS
            rates.x = clamp(rates.x + command.x * h.turnAccelPitch * k, -h.turnRatePitch * b, h.turnRatePitch * b)
            rates.y = clamp(rates.y + command.y * h.turnAccelYaw * k, -h.turnRateYaw * b, h.turnRateYaw * b)
            rates.z = clamp(rates.z + command.z * h.turnAccelRoll * k, -h.turnRateRoll * b, h.turnRateRoll * b)
        }
        // fromEulerAnglesYXZ of tiny angles differs from the exponential map only at O(dt²); the
        // quaternion is renormalised by integrateLocal (Roblox orthonormalize)
        rot.integrateLocal(rates.x, rates.y, rates.z, dt)
    }

    companion object {
        /** `getAngularVelocity`: [rates] (rad/s) in degrees per second into [out] (x pitch, y yaw, z roll). */
        fun angularVelocityDeg(rates: Vec3, out: Vec3): Vec3 =
            out.set(rates.x * RAD2DEG, rates.y * RAD2DEG, rates.z * RAD2DEG)

        private const val RAD2DEG = 57.29578f
    }
}
