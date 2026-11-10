package com.starwake.core.flight

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The autopilot (Roblox `ShipAutopilot`, `ShipClient/ShipAutopilot.lua:65-189`, 02-flight.md 3.7):
 * flies to the locked target by producing the inputs of a very calm pilot (a cursor, a lever and a
 * roll value), so every inertia, limit and brake still applies. Braking follows v = √(2·a·s)
 * because speeds differ by a factor of seventy.
 *
 * The outputs are read in the LEGACY mapping: cursor X yaws, the roll value goes on the key axis
 * (+1 = left, like Q). The session's glue (`ShipClient.lua:2389-2423`) handles the override rules.
 * No allocation.
 */
class Autopilot {
    var engaged = false
        private set

    /** STANDBY, NO TARGET, ENGAGED, TARGET LOST, ARRIVED, ALIGNING, CRUISING, BRAKING, ATMOSPHERE, MANUAL OVERRIDE. */
    var status = STANDBY
        private set

    /** The target, as the session's lock kind and index (-1 for none). */
    var targetKind = -1
        private set
    var targetIndex = -1
        private set

    // ---- outputs of the last [compute]
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    var throttle = 0.0
        private set
    var roll = 0f
        private set

    private val toLocal = DVec3()

    /** `engage(target)`: false (status NO TARGET) without one. */
    fun engage(kind: Int, index: Int): Boolean {
        if (kind < 0 || index < 0) {
            status = NO_TARGET
            return false
        }
        engaged = true
        targetKind = kind
        targetIndex = index
        status = ENGAGED
        return true
    }

    /** `disengage(reason)`: status = [reason] (STANDBY by default). */
    fun disengage(reason: String = STANDBY) {
        engaged = false
        targetKind = -1
        targetIndex = -1
        status = reason
    }

    /**
     * `compute(dt, shipCFrame, speed, speedLimit, handling)`: [pos]/[rot] the ship, [targetPos] and
     * [targetRadius] (its `CollisionRadius`), [speed] m/s, [speedLimit] the tracked limit of the
     * previous frame, [cap] the cruise cap. Returns false when the autopilot is (or just went) off;
     * true with [cursorX]/[cursorY], [throttle] and [roll] set.
     */
    fun compute(
        pos: DVec3, rot: Quat, targetPos: DVec3, targetRadius: Double, speed: Double, speedLimit: Double,
        h: ShipHandling, cap: Double,
    ): Boolean {
        if (!engaged) return false
        val c = FlightConfig.Autopilot
        val tx = targetPos.x - pos.x
        val ty = targetPos.y - pos.y
        val tz = targetPos.z - pos.z
        val d = sqrt(tx * tx + ty * ty + tz * tz)
        val surf = max(d - targetRadius, 0.0)
        if (surf <= targetRadius * c.ARRIVE_DISTANCE && speed <= c.ARRIVE_SPEED) {
            disengage(ARRIVED)
            return false
        }
        // direction to the target in ship space (forward is -Z)
        if (d > 1e-4) rot.rotateInverse(tx / d, ty / d, tz / d, toLocal) else toLocal.set(0.0, 0.0, -1.0)
        val lx = toLocal.x
        val ly = toLocal.y
        val fwd = -toLocal.z
        val angle = acos(fwd.coerceIn(-1.0, 1.0))
        val s = sin(angle)
        var cx: Double
        var cy: Double
        if (s < 1e-3 && fwd < 0.0) {
            // straight behind: pull up
            cx = 0.0
            cy = -1.0
        } else {
            val k = if (s > 1e-4) angle / s else 1.0
            cx = (lx * k * c.TURN_GAIN).coerceIn(-1.0, 1.0)
            cy = (-ly * k * c.TURN_GAIN).coerceIn(-1.0, 1.0)
        }
        var r = 0.0
        if (angle > Math.toRadians(c.ROLL_TO_PITCH_ANGLE_DEG)) {
            // roll the target into the pitch plane: 0 = straight up, + = right; targets below are pushed
            var bank = atan2(lx, ly)
            if (bank > PI / 2) bank -= PI else if (bank < -PI / 2) bank += PI
            r = -(bank * c.ROLL_GAIN).coerceIn(-1.0, 1.0)
        }
        // undo the dead zone, else it eats small corrections and the ship hunts beside the target
        val m = sqrt(cx * cx + cy * cy)
        if (m > 1e-4) {
            val dz = FlightConfig.Cursor.DEADZONE.toDouble()
            val scaled = min(dz + m * (1.0 - dz), c.MAX_CURSOR)
            cx = cx / m * scaled
            cy = cy / m * scaled
        }
        cursorX = cx.toFloat()
        cursorY = cy.toFloat()
        roll = r.toFloat()

        // ---------- the lever: thrust only when well aligned
        if (fwd >= c.ALIGNMENT_FOR_THRUST) {
            val dist = max(surf - targetRadius * c.STOP_ALTITUDE, 0.0) / c.BRAKE_MARGIN
            val brake = h.accelReverse
            val top = h.topSpeed
            val band = top * top / (2.0 * brake)
            val stop = if (dist <= band) {
                sqrt(2.0 * brake * dist)
            } else {
                // the cruise slows exponentially, heavier ships more slowly
                val tau = FlightConfig.Handling.CRUISE_TIME_DOWN * h.profile.accelForward / max(h.accelForward, 1e-3)
                top + (dist - band) / tau
            }
            val desired = min(min(stop, speedLimit), cap)
            val reference = max(min(cap, speedLimit), 1e-3)
            throttle = ShipThrust.throttleFor(desired, reference, top)
            if (speed > desired * 1.05) {
                status = BRAKING
                throttle = 0.0
            } else {
                status = CRUISING
            }
        } else {
            status = ALIGNING
            throttle = 0.0
        }
        return true
    }

    companion object {
        const val STANDBY = "STANDBY"
        const val NO_TARGET = "NO TARGET"
        const val ENGAGED = "ENGAGED"
        const val TARGET_LOST = "TARGET LOST"
        const val ARRIVED = "ARRIVED"
        const val ALIGNING = "ALIGNING"
        const val CRUISING = "CRUISING"
        const val BRAKING = "BRAKING"
        const val ATMOSPHERE = "ATMOSPHERE"
        const val MANUAL_OVERRIDE = "MANUAL OVERRIDE"
    }
}
