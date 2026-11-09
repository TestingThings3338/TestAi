package com.starwake.core.game

import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The flight camera of the Roblox game (ShipClient/ShipCamera.lua and the look-around half of
 * ShipInput.lua, 01-core-loop.md 2.3): rigidly attached to the ship, no lag.
 *
 * Cockpit (default): `ship · T(SeatOffset) · head · T(neck) · turn · T(neck)⁻¹ · shake`. The head turns
 * about the neck, so the eye travels along an arc when looking around, as a real head does.
 * External: `ship · turn · T(offset) · RotX(pitch) · shake`, with the ship's own offset and pitch
 * (ShipConfig.Ships[..].ExternalCamera); looking around orbits the ship.
 * `turn = RotY(−look.x) · RotX(look.y)` (degrees, X right, Y up): yaw about the ship's up, then pitch.
 *
 * Look-around: limits ±140° yaw, 80° up, 70° down in the cockpit, ±180° / ±60° outside; when the
 * pilot lets go, the look returns with τ = 0.12 s and snaps to zero below 0.05°.
 *
 * Positions are doubles (camera-relative rendering); everything is allocation free.
 */
class ShipCamera {
    /** Camera pose written by [update]. */
    val pos = DVec3()
    val rot = Quat()

    var external = false
        private set

    /** Look-around, degrees: X right, Y up. */
    var lookX = 0f
        private set
    var lookY = 0f
        private set

    /** Debug "look x y": the view holds where it is put instead of returning (Roblox `lookHold`). */
    var lookHold = false

    var yawLimit = LOOK_YAW
        private set
    var upLimit = LOOK_UP
        private set
    var downLimit = LOOK_DOWN
        private set

    /** The pilot's eye relative to the ship origin (ShipConfig.Camera.SeatOffset). */
    val seat = Vec3(0f, 0.5f, 0f)

    /** Head pivot relative to the eye: down and back (ShipConfig.Interior.NeckOffset). */
    val neck = Vec3(0f, -0.35f, 0.3f)

    /** External view: offset from the ship origin (right, up, back; m) and pitch (degrees). */
    val externalOffset = Vec3(0f, 16f, 95f)
    var externalPitchDeg = -8f

    /**
     * Head offset from the cockpit motion (G sway, surge, roll tilt; `06` ShipCockpit): identity
     * until that port feeds it.
     */
    val headRot = Quat()
    val headPos = Vec3()

    /** Camera shake, camera space (m), already scaled by the CAMERA SHAKE setting. */
    val shake = Vec3()

    private val turn = Quat()
    private val tmpQ = Quat()
    private val seatPos = Vec3()
    private val v = Vec3()
    private val w = Vec3()

    /** Sets the ship's external camera (Roblox ExternalCamera: offset in m, pitch in degrees). */
    fun setExternalCamera(x: Float, y: Float, z: Float, pitchDeg: Float) {
        externalOffset.set(x, y, z)
        externalPitchDeg = pitchDeg
    }

    /**
     * Cockpit ↔ external (`toggleExternalView`): switches the look limits (Interior.ExternalLook*
     * or Look*) and clamps the current look into them at once, as `setLookLimits` does.
     */
    fun setExternal(ext: Boolean) {
        external = ext
        if (ext) {
            yawLimit = EXTERNAL_YAW; upLimit = EXTERNAL_UP; downLimit = EXTERNAL_DOWN
        } else {
            yawLimit = LOOK_YAW; upLimit = LOOK_UP; downLimit = LOOK_DOWN
        }
        lookX = clamp(lookX, -yawLimit, yawLimit)
        lookY = clamp(lookY, -downLimit, upLimit)
    }

    /** Adds look-around in degrees (X right, Y up), clamped to the limits of the current view. */
    fun addLook(dx: Float, dy: Float) {
        lookX = clamp(lookX + dx, -yawLimit, yawLimit)
        lookY = clamp(lookY + dy, -downLimit, upLimit)
    }

    /** Puts the look at ([x], [y]) degrees, clamped (debug "look"). */
    fun setLook(x: Float, y: Float) {
        lookX = clamp(x, -yawLimit, yawLimit)
        lookY = clamp(y, -downLimit, upLimit)
    }

    /**
     * Per frame: unless the pilot holds the look control ([looking]) or [lookHold] is set, the view
     * returns forward with τ = [LOOK_RETURN] (ShipInput.update).
     */
    fun updateLook(dt: Float, looking: Boolean) {
        if (looking || lookHold || (lookX == 0f && lookY == 0f)) return
        val keep = exp(-dt / LOOK_RETURN)
        lookX *= keep
        lookY *= keep
        if (sqrt(lookX * lookX + lookY * lookY) < LOOK_SNAP) {
            lookX = 0f
            lookY = 0f
        }
    }

    /** Centres the view at once (respawn, ship switch). */
    fun resetLook() {
        lookX = 0f
        lookY = 0f
    }

    /** Places the camera for a ship at [shipPos] turned by [shipRot] (ShipCamera.update). */
    fun update(shipPos: DVec3, shipRot: Quat) {
        // turn = RotY(-look.x) * RotX(look.y)
        turn.setAxisAngle(0f, 1f, 0f, -lookX * DEG2RAD)
        tmpQ.setAxisAngle(1f, 0f, 0f, lookY * DEG2RAD)
        turn.mul(turn, tmpQ)
        if (external) {
            // seat = turn * T(offset) * RotX(pitch)
            turn.rotate(externalOffset, seatPos)
            tmpQ.setAxisAngle(1f, 0f, 0f, externalPitchDeg * DEG2RAD)
            rot.mul(turn, tmpQ)
        } else {
            // seat = T(seat) * head * T(neck) * turn * T(-neck): the eye at seat + head + head·(neck - turn·neck)
            turn.rotate(neck, v)
            w.set(neck.x - v.x, neck.y - v.y, neck.z - v.z)
            headRot.rotate(w, v)
            seatPos.set(seat.x + headPos.x + v.x, seat.y + headPos.y + v.y, seat.z + headPos.z + v.z)
            rot.mul(headRot, turn)
        }
        // into the world: rotation ship * seat; the position in doubles
        rot.mul(shipRot, rot)
        shipRot.rotate(seatPos, v)
        // shake lives in camera space (camera = ship * seat * shake)
        rot.rotate(shake, w)
        pos.set(shipPos.x + v.x + w.x, shipPos.y + v.y + w.y, shipPos.z + v.z + w.z)
    }

    /** The eye (or external camera) in ship space after the last [update]: for tests and the HUD. */
    fun seatInShipSpace(out: Vec3): Vec3 = out.set(seatPos)

    companion object {
        /** Look limits in the cockpit (Interior.LookYaw / LookUp / LookDown), degrees. */
        const val LOOK_YAW = 140f
        const val LOOK_UP = 80f
        const val LOOK_DOWN = 70f

        /** Look limits outside (Interior.ExternalLookYaw / Up / Down), degrees. */
        const val EXTERNAL_YAW = 180f
        const val EXTERNAL_UP = 60f
        const val EXTERNAL_DOWN = 60f

        /** Return time constant of the look (Interior.LookReturnTime), s. */
        const val LOOK_RETURN = 0.12f

        /** Below this (degrees) the returning look snaps to zero. */
        const val LOOK_SNAP = 0.05f
    }
}

/**
 * A simple impact shake for the ship camera (the collision bumps of the current flight model):
 * a decaying wobble, written into [ShipCamera.shake] scaled by the CAMERA SHAKE setting. The cockpit
 * motion port (`06` ShipCockpit) replaces it with Roblox's vibration and head offsets.
 */
class ImpactShake {
    private var time = 0f
    private var amount = 0f

    fun add(a: Float) {
        amount = maxOf(amount, a)
    }

    /** Advances by [dt] and writes the offset (camera space, m) scaled by [strength] (0..1) into [out]. */
    fun update(dt: Float, strength: Float, out: Vec3) {
        time += dt
        amount *= exp(-dt * 3.5f)
        if (amount < 1e-4f) amount = 0f
        val a = amount * strength
        out.set(sin(time * 37f) * a * 0.35f, sin(time * 53f + 1.3f) * a * 0.35f, 0f)
    }

    fun reset() {
        amount = 0f
    }
}
