package com.starwake.core.flight

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Translation with mass (Roblox `ShipThrust`, `ShipClient/ShipThrust.lua:217-431`, 02-flight.md
 * 3.4): the throttle lever (−25 %..100 %), Flight Assist, boost, cruise above TopSpeed, the speed
 * caps, the gravity brake and gravity. Every acceleration comes from [ShipHandling].
 *
 * Two bands: up to the ship's TopSpeed real thrust per axis (with Flight Assist the computer holds
 * the commanded velocity in ship axes, each axis with its own thrusters, drift killed with doubled
 * side thrust; without it pure Newton), and above it the cruise (Flight Assist only): the speed
 * approaches the lever's target exponentially, at most doubling every 0.8 s, and the flight
 * direction follows the nose.
 *
 * The velocity is the caller's (`ship.vel`, double precision: at S = 3000 speeds reach 10⁸ m/s), in
 * the ship's reference frame; [update] changes it in place and the caller moves the ship by it.
 * The input of a frame ([throttleAxis], [lateralX]/[lateralY], [fullStopHeld], [boostRequested],
 * an absolute lever through [setThrottle]) is set by the session before [update]. No allocation.
 */
class ShipThrust(private val law: FlightLaw = FlightLaw.PORT) {
    /** The lever, `MinThrottle`..1. */
    var throttle = 0.0
        private set
    var flightAssist = FlightConfig.Thrust.FLIGHT_ASSIST_DEFAULT

    /** The tracked limit (`speedLimit`, +∞ before the first [setSpeedLimit]). */
    var speedLimit = Double.POSITIVE_INFINITY
        private set

    /** The raw limit of this frame and whether it applies at once (the approach zone). */
    var rawSpeedLimit = Double.POSITIVE_INFINITY
        private set
    var limitImmediate = false
        private set

    /** Gravity of this frame, m/s² (the caller sets it; Flight Assist cancels 78 %). */
    val gravity = DVec3()

    // ---- this frame's input (`readInput`): the session writes it, a disabled input zeroes it
    /** W/S: the lever ramps by this × dt / ThrottleRampTime (-1..1; the pad's triggers are analog). */
    var throttleAxis = 0.0

    /** Lateral thrusters in ship axes: X +1 right, Y +1 up (normalised when longer than 1). */
    var lateralX = 0.0
    var lateralY = 0.0

    /** X held: the lever runs to 0 in 1.2 s and every axis brakes. */
    var fullStopHeld = false

    /** Thrust input enabled (`setInputEnabled`): off while travelling, controlled or on foot. */
    var inputEnabled = true
        set(v) {
            field = v
            if (!v) clearInput()
        }

    // ---- state
    var cruising = false
        private set
    var boosting = false
        private set
    var boostTimer = 0.0
        private set
    var boostCooldown = 0.0
        private set

    /** B pressed: handled (or dropped) at the next [update]. */
    var boostRequested = false

    /** True for the frame a boost ignited (heat, sound, haptics); the caller clears it. */
    var boostStarted = false

    /** The true acceleration of the last update (everything included), m/s². */
    val acceleration = DVec3()

    /** What the pilot feels (cockpit, G): cruise damped; cap, brake and gravity not included. */
    val felt = DVec3()

    /** Forward speed (m/s along the nose) at the start of the last update. */
    var forwardSpeed = 0.0
        private set

    /** The forward target of the last update (m/s; negative in reverse). */
    var targetForward = 0.0
        private set

    // scratch
    private val prev = DVec3()
    private val local = DVec3()
    private val fwd = DVec3()
    private val tmp = DVec3()

    /** `setThrottle`: the lever, clamped to `MinThrottle`..1 (autopilot, handovers, the touch lever). */
    fun setThrottle(v: Double) {
        throttle = v.coerceIn(FlightConfig.Thrust.MIN_THROTTLE, 1.0)
    }

    /** `setSpeedLimit`: this frame's raw limit; [immediate] in the approach zone. */
    fun setSpeedLimit(limit: Double, immediate: Boolean) {
        rawSpeedLimit = limit
        limitImmediate = immediate
    }

    /** `setGravity`, m/s². */
    fun setGravity(x: Double, y: Double, z: Double) {
        gravity.set(x, y, z)
    }

    private fun clearInput() {
        throttleAxis = 0.0
        lateralX = 0.0
        lateralY = 0.0
        fullStopHeld = false
    }

    /**
     * `advanceSpeedLimit`: a rising limit, one at or above `Thrust.MaxSpeed` (1 km/s) and one in the
     * approach zone apply at once; a falling limit below 1 km/s is smoothed (τ 1.6 s) from at most
     * 1 km/s.
     */
    fun advanceSpeedLimit(dt: Double) {
        val target = rawSpeedLimit
        val max = FlightConfig.Thrust.MAX_SPEED
        speedLimit = if (speedLimit == Double.POSITIVE_INFINITY || target >= speedLimit || target >= max || limitImmediate) {
            target
        } else {
            smooth(min(speedLimit, max), target, FlightConfig.Gravity.LIMIT_FALL_TIME, dt)
        }
    }

    /**
     * One frame (`update(dt, shipCFrame, handling)`): advances the limit, the lever, the boost, then
     * the velocity [vel] of a ship turned by [rot]. The caller moves the ship by `vel · dt`.
     */
    fun update(dt: Double, rot: Quat, vel: DVec3, h: ShipHandling) {
        val cfg = FlightConfig.Thrust
        val hc = FlightConfig.Handling
        val p = h.profile
        advanceSpeedLimit(dt)
        if (!inputEnabled) clearInput()
        var lx = lateralX
        var ly = lateralY
        val ll = kotlin.math.sqrt(lx * lx + ly * ly)
        if (ll > 1.0) {
            lx /= ll
            ly /= ll
        }

        // ---------- the lever (:225-237)
        if (fullStopHeld) {
            val step = dt / max(cfg.FULL_STOP_TIME, 1e-3)
            throttle = if (abs(throttle) <= step) 0.0 else throttle - kotlin.math.sign(throttle) * step
        } else if (throttleAxis != 0.0) {
            throttle = (throttle + throttleAxis * (dt / max(cfg.THROTTLE_RAMP_TIME, 1e-3))).coerceIn(cfg.MIN_THROTTLE, 1.0)
        }

        // ---------- boost (:240-252): a refused request is dropped, not queued
        boostCooldown = max(0.0, boostCooldown - dt)
        boostTimer = max(0.0, boostTimer - dt)
        if (boostRequested) {
            boostRequested = false
            if (boostCooldown <= 0.0 && !cruising && !h.overloaded && inputEnabled) {
                boostTimer = p.boostDuration
                boostCooldown = p.boostCooldown
                boostStarted = true
            }
        }
        val boost = boostTimer > 0.0
        boosting = boost

        prev.set(vel)
        val speed = vel.length()
        val top = h.topSpeed
        val cap = if (boost) h.boostSpeed else top
        val fwdAccel = h.accelForward * (if (boost) p.boostAccelerationMultiplier else 1.0)

        // the lever refers to what is allowed here
        val reference = min(law.cruiseCap, speedLimit)
        var target = if (throttle >= 0.0) targetSpeed(throttle, reference, top) else (throttle / -cfg.MIN_THROTTLE) * h.reverseSpeed
        if (boost) target = max(target, h.boostSpeed)
        targetForward = target

        rot.rotateInverse(vel.x, vel.y, vel.z, local)
        val forwardNow = -local.z
        forwardSpeed = forwardNow

        // cruise: Flight Assist, above TopSpeed (the previous frame's flag gated the boost above)
        val cruise = flightAssist && !boost && !fullStopHeld &&
            (speed > top * 1.02 || (target > top && forwardNow >= top * 0.98))
        cruising = cruise

        if (cruise) {
            // ---------- cruise (:284-321)
            val goal = max(target, 0.0)
            val massSlowdown = p.accelForward / max(h.accelForward, 1e-3)
            val newSpeed = if (goal > speed) {
                var gain = (goal - speed) * (1.0 - exp(-dt / (hc.CRUISE_TIME_UP * massSlowdown)))
                gain = min(gain, speed * (2.0.pow(dt / (hc.CRUISE_DOUBLE_TIME * massSlowdown)) - 1.0))
                gain = max(gain, h.accelForward * dt)
                min(speed + gain, goal)
            } else {
                speed + (goal - speed) * (1.0 - exp(-dt / (hc.CRUISE_TIME_DOWN * massSlowdown)))
            }
            // the flight direction follows the nose
            rot.rotate(0.0, 0.0, -1.0, fwd)
            if (speed > 1e-3) tmp.set(vel.x / speed, vel.y / speed, vel.z / speed) else tmp.set(fwd)
            val k = 1.0 - exp(-dt / hc.CRUISE_ALIGN)
            tmp.x += (fwd.x - tmp.x) * k
            tmp.y += (fwd.y - tmp.y) * k
            tmp.z += (fwd.z - tmp.z) * k
            val dl = tmp.length()
            if (dl > 1e-6) tmp.scale(1.0 / dl) else tmp.set(fwd)
            vel.set(tmp.x * newSpeed, tmp.y * newSpeed, tmp.z * newSpeed)
            val inv = hc.CRUISE_FELT_FACTOR / max(dt, 1e-6)
            felt.set((vel.x - prev.x) * inv, (vel.y - prev.y) * inv, (vel.z - prev.z) * inv)
            // a gentle push into the seat, never a blackout
            val maxFelt = hc.CRUISE_FELT_MAX_G * hc.FELT_PER_G
            val fl = felt.length()
            if (fl > maxFelt) felt.scale(maxFelt / fl)
        } else if (flightAssist) {
            // ---------- the combat band with Flight Assist (:322-348)
            var dx = lx * h.strafeSpeed
            var dy = ly * h.strafeSpeed
            var dz = -min(target, cap)
            if (fullStopHeld) {
                dx = 0.0; dy = 0.0; dz = 0.0
            }
            // more forward speed: the main engine; else the retro thrusters. Drift is killed with
            // doubled side thrust, wanted strafing uses real thrust
            val zAccel = if (dz < local.z) fwdAccel else h.accelReverse
            val xAccel = h.accelLateral * (if (lx == 0.0) hc.ASSIST_AUTHORITY else 1.0)
            val yAccel = h.accelVertical * (if (ly == 0.0) hc.ASSIST_AUTHORITY else 1.0)
            local.set(
                approach(local.x, dx, xAccel * dt),
                approach(local.y, dy, yAccel * dt),
                approach(local.z, dz, zAccel * dt),
            )
            rot.rotate(local.x, local.y, local.z, vel)
            val inv = 1.0 / max(dt, 1e-6)
            felt.set((vel.x - prev.x) * inv, (vel.y - prev.y) * inv, (vel.z - prev.z) * inv)
        } else {
            // ---------- the combat band without Flight Assist: pure Newton (:349-372)
            if (fullStopHeld) {
                // retro burn: every axis against its own motion
                val brakeZ = if (local.z < 0.0) h.accelReverse else fwdAccel
                val inv = 1.0 / max(dt, 1e-6)
                tmp.set(
                    (approach(local.x, 0.0, h.accelLateral * dt) - local.x) * inv,
                    (approach(local.y, 0.0, h.accelVertical * dt) - local.y) * inv,
                    (approach(local.z, 0.0, brakeZ * dt) - local.z) * inv,
                )
            } else {
                val z = if (throttle >= 0.0) -throttle * fwdAccel else (throttle / cfg.MIN_THROTTLE) * h.accelReverse
                tmp.set(lx * h.accelLateral, ly * h.accelVertical, z)
            }
            rot.rotate(tmp.x, tmp.y, tmp.z, local)
            vel.addScaled(local, dt)
            val inv = 1.0 / max(dt, 1e-6)
            felt.set((vel.x - prev.x) * inv, (vel.y - prev.y) * inv, (vel.z - prev.z) * inv)
        }

        // ---------- the cap outside cruise (:377-388), FA off too: excess decays, it is not cut off
        if (!cruise) {
            val s = vel.length()
            if (s > cap && s > 1e-4) {
                val reduced = if (s > top * 1.02 && !boost) {
                    s + (cap - s) * (1.0 - exp(-dt / hc.CRUISE_TIME_DOWN))
                } else {
                    max(cap, s - h.accelReverse * dt)
                }
                vel.scale(reduced / s)
            }
        }

        // ---------- the gravity brake (:394-408), both modes; never below MinManoeuvreSpeed
        val limit = max(speedLimit, FlightConfig.Gravity.MIN_MANOEUVRE_SPEED)
        if (limit < Double.POSITIVE_INFINITY) {
            val s = vel.length()
            if (s > limit && s > 1e-4) {
                var step = FlightConfig.Gravity.BRAKE_ACCELERATION * dt
                // above MaxSpeed also exponentially: an approach from open space lowers the limit steadily
                val excess = s - max(limit, cfg.MAX_SPEED)
                if (excess > 0.0) step = max(step, excess * (1.0 - exp(-dt / FlightConfig.Gravity.CRUISE_BRAKE_TIME)))
                vel.scale(max(limit, s - step) / s)
            }
        }

        // ---------- gravity (:413-419): Flight Assist cancels most of it
        if (gravity.lengthSq() > 1e-10) {
            val factor = if (flightAssist) 1.0 - FlightConfig.Gravity.FA_GRAVITY_COMPENSATION else 1.0
            vel.addScaled(gravity, factor * dt)
        }

        // a residual crawl reads as standing still
        if (flightAssist && vel.length() < cfg.VELOCITY_CUTOFF) vel.zero()

        if (dt > 0.0) {
            val inv = 1.0 / dt
            acceleration.set((vel.x - prev.x) * inv, (vel.y - prev.y) * inv, (vel.z - prev.z) * inv)
        } else {
            acceleration.zero()
            felt.zero()
        }
    }

    /** `reset` (crash respawn): velocity, accelerations, lever, boost timer, cruise and boost off; keeps the cooldown and FA. */
    fun reset(vel: DVec3) {
        vel.zero()
        acceleration.zero()
        felt.zero()
        throttle = 0.0
        boostTimer = 0.0
        cruising = false
        boosting = false
        boostRequested = false
        boostStarted = false
    }

    /** `cancelVelocityAlong(n)`: removes the part of [vel] along the unit normal (nx, ny, nz) when it points along it. */
    fun cancelVelocityAlong(vel: DVec3, nx: Double, ny: Double, nz: Double) = cancelAlong(vel, nx, ny, nz)

    /** `getForwardAcceleration`: the acceleration along the flight direction (|a| below 1e-4 m/s). */
    fun forwardAcceleration(vel: DVec3): Double {
        val s = vel.length()
        if (s < 1e-4) return acceleration.length()
        return acceleration.dot(vel) / s
    }

    /** `getLocalAcceleration`: the felt acceleration in ship axes (x side, y up, −z forward) into [out]. */
    fun localFelt(rot: Quat, out: DVec3): DVec3 = rot.rotateInverse(felt.x, felt.y, felt.z, out)

    companion object {
        /**
         * `targetSpeed` (`:65-75`): the forward speed the lever [t] (0..1) asks for when
         * [reference] is allowed. Up to 1 km/s linear; above it the combat band stays linear up to
         * TopSpeed ([top]) and the rest of the lever grows exponentially to the reference.
         */
        fun targetSpeed(t: Double, reference: Double, top: Double): Double {
            val base = FlightConfig.Thrust.MAX_SPEED
            if (reference <= base) return t * reference
            val band = (top / base).coerceIn(0.05, 0.95)
            if (t <= band) return t * base
            return t * base * (reference / base).pow((t - band) / (1.0 - band))
        }

        /** `throttleFor` (`:83-106`): the lever that gives [speed] (bisection, 30 steps, the midpoint). */
        fun throttleFor(speed: Double, reference: Double, top: Double): Double {
            val base = FlightConfig.Thrust.MAX_SPEED
            if (speed <= 0.0) return 0.0
            if (reference <= base) return (speed / max(reference, 1e-3)).coerceIn(0.0, 1.0)
            if (speed >= reference) return 1.0
            val band = (top / base).coerceIn(0.05, 0.95)
            if (speed <= band * base) return speed / base
            var lo = band
            var hi = 1.0
            repeat(30) {
                val mid = (lo + hi) * 0.5
                if (targetSpeed(mid, reference, top) < speed) lo = mid else hi = mid
            }
            return (lo + hi) * 0.5
        }

        /** `approach(c, t, maxStep)` in doubles. */
        fun approach(c: Double, t: Double, maxStep: Double): Double = if (c < t) min(c + maxStep, t) else max(c - maxStep, t)

        /** `ShipMath.smooth` in doubles. */
        fun smooth(c: Double, t: Double, tau: Double, dt: Double): Double = if (tau <= 0.0) t else c + (t - c) * (1.0 - exp(-dt / tau))

        /** Removes the part of [v] along the unit normal (nx, ny, nz) when it points along it (`cancelVelocityAlong`). */
        fun cancelAlong(v: DVec3, nx: Double, ny: Double, nz: Double) {
            val out = v.x * nx + v.y * ny + v.z * nz
            if (out > 0.0) {
                v.x -= nx * out
                v.y -= ny * out
                v.z -= nz * out
            }
        }
    }
}
