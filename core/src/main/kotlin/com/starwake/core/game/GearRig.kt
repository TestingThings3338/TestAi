package com.starwake.core.game

import com.starwake.core.content.ShipModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Landing-gear animation of a [ShipModel]: ShipHull.stepGear (the sequence) and ShipGear.pose (the
 * joints) from the Roblox game.
 *
 * Retracting folds the legs first ([LEG_TIME]), then closes the doors ([DOOR_TIME]); then the gear is
 * stowed: it hides and the closed hull doors (GearClosed) show instead. Extending runs backwards:
 * shown again in the retracted pose, doors open, legs down. A command reversing mid-way just turns
 * round. legT and doorT are 0 when extended and 1 when retracted (as in ShipGear).
 *
 * [bones] holds one affine 3x4 matrix per bone (12 floats: three rows of rotation/scale + translation)
 * mapping the baked ship-space vertices to their posed place; a hidden bone is all zeros (its
 * triangles collapse to a point). Updated only while something moves; [version] counts the changes.
 * No allocation after [setModel].
 */
class GearRig {
    var model: ShipModel? = null
        private set
    var bones = FloatArray(12 * 3)
        private set
    var boneCount = 3
        private set

    /** Legs: 0 down, 1 folded. */
    var legT = 0f
        private set

    /** Doors: 0 open, 1 closed. */
    var doorT = 0f
        private set

    /** Retracted and hidden (the closed hull doors show). */
    var stowed = false
        private set

    /** Last command: true = gear down. */
    var down = true
        private set

    /** Increments whenever [bones] change. */
    var version = 0
        private set

    // scratch matrices (3x4 row-major)
    private val pivot = FloatArray(12)
    private val pivotInv = FloatArray(12)
    private val turn = FloatArray(12)
    private val m0 = FloatArray(12)
    private val m1 = FloatArray(12)
    private val m2 = FloatArray(12)
    private val v0 = FloatArray(3)
    private val v1 = FloatArray(3)

    /** Takes a new ship, in the gear position [down] without animation. */
    fun setModel(m: ShipModel, down: Boolean) {
        model = m
        boneCount = m.boneCount
        if (bones.size < 12 * boneCount) bones = FloatArray(12 * boneCount)
        this.down = down
        legT = if (down) 0f else 1f
        doorT = legT
        stowed = !down
        pose()
    }

    /** True while the doors (not the legs) are moving: the HUD shows the phase. */
    val doorsMoving: Boolean
        get() = (model?.hasDoors ?: false) && doorT > 0f && doorT < 1f

    /** 1 = fully down and locked, 0 = stowed; in between the share of the sequence still to go. */
    val downFraction: Float
        get() {
            val doors = model?.hasDoors ?: false
            val total = LEG_TIME + if (doors) DOOR_TIME else 0f
            return 1f - (legT * LEG_TIME + (if (doors) doorT * DOOR_TIME else 0f)) / total
        }

    /**
     * Advances the sequence towards [wantDown] (ShipHull.stepGear). [instant]: jump to the end (the
     * Roblox game does that while the pilot sits in the cockpit).
     */
    fun update(dt: Float, wantDown: Boolean, instant: Boolean) {
        val m = model ?: return
        down = wantDown
        var leg = legT
        var door = doorT
        if (wantDown) {
            if (leg == 0f && door == 0f && !stowed) return
            when {
                instant -> { leg = 0f; door = 0f }
                door > 0f && m.hasDoors -> door = maxOf(0f, door - dt / DOOR_TIME)
                else -> { door = 0f; leg = maxOf(0f, leg - dt / LEG_TIME) }
            }
        } else {
            if (stowed) return
            when {
                instant -> { leg = 1f; door = 1f }
                leg < 1f -> leg = minOf(1f, leg + dt / LEG_TIME)
                m.hasDoors -> door = minOf(1f, door + dt / DOOR_TIME)
                else -> door = 1f
            }
        }
        val nowStowed = !wantDown && leg >= 1f && door >= 1f
        if (leg != legT || door != doorT || nowStowed != stowed) {
            legT = leg
            doorT = door
            stowed = nowStowed
            pose()
        }
    }

    /** ShipGear.pose for the current legT/doorT, plus the visibility bones. */
    private fun pose() {
        val m = model ?: return
        val b = bones
        identity(b, 0)
        if (stowed) zero(b, 12) else identity(b, 12)
        if (stowed) identity(b, 24) else zero(b, 24)
        for (i in 3 until boneCount) {
            val o = i * 12
            if (stowed) {
                zero(b, o)
                continue
            }
            val bone = m.bones[i - 3]
            cframe(bone.pivot, pivot)
            rigidInverse(pivot, pivotInv)
            if (bone.kind == ShipModel.KIND_DOOR) {
                rotX(bone.rad * smooth(doorT), m0)
                mul(pivot, m0, turn)
                mul(turn, pivotInv, m1)
                System.arraycopy(m1, 0, b, o, 12)
                continue
            }
            val swing = smooth(legT / bone.swingEnd)
            val rise = if (bone.lift != 0f) smooth((legT - bone.liftFrom) / (1f - bone.liftFrom)) else 0f
            val liftY = bone.lift * rise
            rotX(bone.rad * swing, m0)
            mul(pivot, m0, turn)
            when (bone.kind) {
                ShipModel.KIND_LEG -> {
                    mul(turn, pivotInv, m1)
                    m1[7] += liftY
                    System.arraycopy(m1, 0, b, o, 12)
                }
                ShipModel.KIND_FOOT -> {
                    // the foot follows the ankle round the hinge and stays level
                    val a = bone.ankle
                    transform(pivotInv, a[0], a[1], a[2], v0)
                    transform(turn, v0[0], v0[1], v0[2], v1)
                    identity(b, o)
                    b[o + 3] = v1[0] - a[0]
                    b[o + 7] = v1[1] - a[1] + liftY
                    b[o + 11] = v1[2] - a[2]
                }
                ShipModel.KIND_LINK -> link(bone, liftY, b, o)
                else -> { // Mount: only lifts
                    identity(b, o)
                    b[o + 7] = liftY
                }
            }
        }
        version++
    }

    /**
     * A rod between hull and leg: turns about its fixed end so the other end follows the leg, and
     * stretches along its long axis (ShipGear "Link").
     */
    private fun link(bone: ShipModel.Bone, liftY: Float, b: FloatArray, o: Int) {
        val fixed = bone.fixed!!
        val move = bone.move!!
        val ax = fixed[0]
        val ay = fixed[1]
        val az = fixed[2]
        var dx = move[0] - ax
        var dy = move[1] - ay
        var dz = move[2] - az
        val len0 = sqrt(dx * dx + dy * dy + dz * dz)
        dx /= len0; dy /= len0; dz /= len0
        transform(pivotInv, move[0], move[1], move[2], v0)
        transform(turn, v0[0], v0[1], v0[2], v1)
        var ux = v1[0] - ax
        var uy = v1[1] - ay
        var uz = v1[2] - az
        val len = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= len; uy /= len; uz /= len
        val stretch = len - len0
        // rotation taking the rod's rest direction onto the new one, about the fixed end
        between(dx, dy, dz, ux, uy, uz, m0)
        m0[3] = ax - (m0[0] * ax + m0[1] * ay + m0[2] * az)
        m0[7] = ay - (m0[4] * ax + m0[5] * ay + m0[6] * az)
        m0[11] = az - (m0[8] * ax + m0[9] * ay + m0[10] * az)
        // then slide by half the length change along the rod, and lift
        m0[3] += ux * stretch * 0.5f
        m0[7] += uy * stretch * 0.5f + liftY
        m0[11] += uz * stretch * 0.5f
        // the part grows along its own axis: base * S(axis) * base^-1, applied before the move
        val base = bone.base!!
        cframe(base, m1)
        val size = bone.size!!
        val k = (size[bone.axis] + stretch) / size[bone.axis]
        rigidInverse(m1, m2)
        // m1 = base * S: scale column `axis` of the rotation
        m1[bone.axis] *= k
        m1[4 + bone.axis] *= k
        m1[8 + bone.axis] *= k
        mul(m1, m2, pivot) // pivot reused as scratch: base * S * base^-1
        mul(m0, pivot, m1)
        System.arraycopy(m1, 0, b, o, 12)
    }

    companion object {
        /** ShipConfig.Hull.GearLegTime / GearDoorTime (seconds). */
        const val LEG_TIME = 1.6f
        const val DOOR_TIME = 0.6f

        fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        /** Roblox CFrame (position, rotation row-major) to a 3x4 matrix. */
        fun cframe(cf: FloatArray, out: FloatArray) {
            out[0] = cf[3]; out[1] = cf[4]; out[2] = cf[5]; out[3] = cf[0]
            out[4] = cf[6]; out[5] = cf[7]; out[6] = cf[8]; out[7] = cf[1]
            out[8] = cf[9]; out[9] = cf[10]; out[10] = cf[11]; out[11] = cf[2]
        }

        fun identity(m: FloatArray, o: Int) {
            for (i in 0 until 12) m[o + i] = 0f
            m[o] = 1f; m[o + 5] = 1f; m[o + 10] = 1f
        }

        fun zero(m: FloatArray, o: Int) {
            for (i in 0 until 12) m[o + i] = 0f
        }

        /** out = a * b (3x4 affine). */
        fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (r in 0..2) {
                val a0 = a[r * 4]
                val a1 = a[r * 4 + 1]
                val a2 = a[r * 4 + 2]
                val a3 = a[r * 4 + 3]
                out[r * 4] = a0 * b[0] + a1 * b[4] + a2 * b[8]
                out[r * 4 + 1] = a0 * b[1] + a1 * b[5] + a2 * b[9]
                out[r * 4 + 2] = a0 * b[2] + a1 * b[6] + a2 * b[10]
                out[r * 4 + 3] = a0 * b[3] + a1 * b[7] + a2 * b[11] + a3
            }
        }

        /** Inverse of a rotation + translation. */
        fun rigidInverse(m: FloatArray, out: FloatArray) {
            out[0] = m[0]; out[1] = m[4]; out[2] = m[8]
            out[4] = m[1]; out[5] = m[5]; out[6] = m[9]
            out[8] = m[2]; out[9] = m[6]; out[10] = m[10]
            out[3] = -(out[0] * m[3] + out[1] * m[7] + out[2] * m[11])
            out[7] = -(out[4] * m[3] + out[5] * m[7] + out[6] * m[11])
            out[11] = -(out[8] * m[3] + out[9] * m[7] + out[10] * m[11])
        }

        /** CFrame.Angles(a, 0, 0): rotation about +X (right-handed). */
        fun rotX(a: Float, out: FloatArray) {
            val c = cos(a)
            val s = sin(a)
            identity(out, 0)
            out[5] = c; out[6] = -s
            out[9] = s; out[10] = c
        }

        fun transform(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
            out[0] = m[0] * x + m[1] * y + m[2] * z + m[3]
            out[1] = m[4] * x + m[5] * y + m[6] * z + m[7]
            out[2] = m[8] * x + m[9] * y + m[10] * z + m[11]
        }

        /** ShipGear's between(): the rotation taking unit vector a onto unit vector b (no translation). */
        fun between(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
            var kx = ay * bz - az * by
            var ky = az * bx - ax * bz
            var kz = ax * by - ay * bx
            val s = sqrt(kx * kx + ky * ky + kz * kz)
            identity(out, 0)
            if (s < 1e-6f) return
            kx /= s; ky /= s; kz /= s
            val angle = atan2(s, ax * bx + ay * by + az * bz)
            val c = cos(angle)
            val sn = sin(angle)
            val t = 1f - c
            out[0] = c + kx * kx * t; out[1] = kx * ky * t - kz * sn; out[2] = kx * kz * t + ky * sn
            out[4] = ky * kx * t + kz * sn; out[5] = c + ky * ky * t; out[6] = ky * kz * t - kx * sn
            out[8] = kz * kx * t - ky * sn; out[9] = kz * ky * t + kx * sn; out[10] = c + kz * kz * t
        }
    }
}
