package com.starwake.core.world

import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `ShipRingDebris` (03-world.md 2.12, port 1:1): rocks in the ring plane around the ship. From
 * afar a ring is an image; near it the ship flies *through* a flat slab of tumbling rocks: the
 * field wraps around the ship within the ring plane, every rock keeps its height above the plane,
 * the rock density follows the ring's opacity profile, and a contact counts once (swept, so a
 * fast ship cannot tunnel past a rock) with heat that grows with the speed.
 *
 * The field is ship-local and absolute (field size, rock sizes, hit radius and spawn range in
 * metres); only the ring's inner and outer radii scale with the planet. The layout comes from one
 * seeded stream (84213), the same for every player; [count] is 280, or 110 at the lowest quality
 * (the first 110 rocks of the same stream). Zero allocation per frame: everything is preallocated.
 *
 * Each frame [update] moves the field and writes the visible rocks into [instances] for the
 * renderer (12 floats each: position in ring coordinates relative to [origin] and scale, the
 * rotation quaternion, the squash of the y and z axes and the shade); [frame] rotates ring
 * coordinates (y = the ring normal) into the world.
 */
class RingDebris {
    /** Rocks in use: [COUNT], or [COUNT_LOW] at the lowest graphics quality. */
    var count = COUNT
        set(value) {
            field = value.coerceIn(0, COUNT)
        }

    // ---- the layout (Random(84213), drawn in the Lua's order)
    private val radius = DoubleArray(COUNT)
    private val squashY = FloatArray(COUNT)
    private val squashZ = FloatArray(COUNT)
    private val offX = DoubleArray(COUNT)
    private val offY = DoubleArray(COUNT)
    private val offZ = DoubleArray(COUNT)
    private val spinX = DoubleArray(COUNT)
    private val spinY = DoubleArray(COUNT)
    private val spinZ = DoubleArray(COUNT)
    private val pose = Array(COUNT) { Quat() }
    private val shade = FloatArray(COUNT)
    private val threshold = DoubleArray(COUNT)

    // ---- per rock state
    private val lastShown = FloatArray(COUNT)
    private val contact = BooleanArray(COUNT)

    /** The ringed body the field belongs to, or -1 when inactive (everything hidden). */
    var body = -1
        private set

    /** The field's clock (s), for the tumbling. */
    var clock = 0.0
        private set

    private var lastHeight = 0.0
    private var hasLastHeight = false

    /** Ring coordinates → world: x = [right], y = the ring normal, z = right × normal (`ringFrame`). */
    val frame = Quat()

    /** The ship's position at the last [update]: the origin of [instances]. */
    val origin = DVec3()

    /** Visible rocks (12 floats each, see the class comment) and how many there are. */
    val instances = FloatArray(COUNT * FLOATS)
    var visible = 0
        private set

    /** Contacts entered in the last [update] and the heat they bring (`update` returns `hits, hits·heatPerHit`). */
    var hits = 0
        private set
    var heat = 0.0
        private set

    /** Linear rock tint of the active ring (`setRing`): the ring colour normalised, times its luminance. */
    val tint = FloatArray(3)

    /** True when the ring's colour reads as ice (luminance > 0.62: the Moon textures only). */
    var icy = false
        private set

    // scratch
    private val right = Vec3()
    private val zAxis = Vec3()
    private val q = Quat()
    private val qa = Quat()

    init {
        val rng = WorldRandom(SEED.toLong())
        val thickness = THICKNESS
        for (i in 0 until COUNT) {
            val r = (MIN_SIZE + (MAX_SIZE - MIN_SIZE) * Math.pow(rng.nextNumber(), 2.2)) * 0.5
            radius[i] = r
            squashY[i] = rng.nextNumber(0.5, 0.85).toFloat()
            squashZ[i] = rng.nextNumber(0.7, 1.15).toFloat()
            offX[i] = rng.nextNumber(-0.5, 0.5) * FIELD_SIZE
            val a = rng.nextNumber()
            val b = rng.nextNumber()
            offY[i] = (a + b - 1.0) * thickness * 0.5
            offZ[i] = rng.nextNumber(-0.5, 0.5) * FIELD_SIZE
            var sx = rng.nextNumber(-1.0, 1.0)
            var sy = rng.nextNumber(-1.0, 1.0)
            var sz = rng.nextNumber(-1.0, 1.0)
            val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-9)
            val rate = rng.nextNumber(0.2, 1.2)
            sx = sx / sl * rate
            sy = sy / sl * rate
            sz = sz / sl * rate
            spinX[i] = sx
            spinY[i] = sy
            spinZ[i] = sz
            val px = rng.nextNumber(0.0, 2 * PI)
            val py = rng.nextNumber(0.0, 2 * PI)
            val pz = rng.nextNumber(0.0, 2 * PI)
            euler(px, py, pz, pose[i])
            shade[i] = rng.nextNumber(0.7, 1.08).toFloat()
            threshold[i] = rng.nextNumber()
        }
    }

    /** Full radius (m, half the diameter) of rock [i]: diameters 3 to 26 m, many small and few large. */
    fun rockRadius(i: Int): Double = radius[i]

    /** The rock's fixed height above the ring plane (m) and its density threshold (tests). */
    fun rockHeight(i: Int): Double = offY[i]
    fun rockThreshold(i: Int): Double = threshold[i]

    /** Rock [i]'s position in the plane relative to the ship, x and z (m, wrapped into the field). */
    fun rockX(i: Int): Double = offX[i]
    fun rockZ(i: Int): Double = offZ[i]

    /** Whether rock [i] is in contact (between a hit and its release). */
    fun inContact(i: Int): Boolean = contact[i]

    /** The shown fraction of rock [i] at the last update (0 = hidden). */
    fun shown(i: Int): Float = lastShown[i]

    /** Places rock [i] in the plane relative to the ship (tests). */
    fun placeRock(i: Int, x: Double, z: Double) {
        offX[i] = x
        offZ[i] = z
    }

    /** Hides everything and forgets every contact (`hideAll`). */
    fun hideAll() {
        for (i in 0 until COUNT) {
            lastShown[i] = 0f
            contact[i] = false
        }
        visible = 0
        body = -1
        hasLastHeight = false
    }

    /**
     * One frame (`update(dt, shipPos, velocity)`, C65): [vel] is the ship's velocity relative to the
     * ringed body (the body's reference frame carries the ship near its rings). Returns the hits.
     */
    fun update(dt: Double, system: StarSystem, shipPos: DVec3, vel: Vec3): Int {
        clock += dt
        hits = 0
        heat = 0.0
        visible = 0
        // the nearest ringed body by centre distance
        var best = -1
        var bestD = Double.MAX_VALUE
        val bodies = system.bodies
        for (i in bodies.indices) {
            val b = bodies[i]
            if (!b.hasRings || b.ringOuter <= 0.0) continue
            val dx = shipPos.x - b.pos.x
            val dy = shipPos.y - b.pos.y
            val dz = shipPos.z - b.pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best < 0) {
            if (body >= 0) hideAll()
            return 0
        }
        val b = bodies[best]
        val inner = b.ringInner
        val outer = b.ringOuter
        if (bestD > outer + SPAWN_RANGE || bestD < inner - SPAWN_RANGE) {
            if (body >= 0) hideAll()
            return 0
        }
        val n = b.ringNormal
        ringFrame(n)
        val tx = shipPos.x - b.pos.x
        val ty = shipPos.y - b.pos.y
        val tz = shipPos.z - b.pos.z
        val h = tx * n.x + ty * n.y + tz * n.z
        val px = tx * right.x + ty * right.y + tz * right.z
        val pz = tx * zAxis.x + ty * zAxis.y + tz * zAxis.z
        val r = sqrt(px * px + pz * pz)
        if (abs(h) > REACH + THICKNESS * 0.5 || r > outer + REACH || r < inner - REACH) {
            if (body >= 0) hideAll()
            return 0
        }
        if (body != best) {
            hideAll()
            setRing(b)
            body = best
        }
        origin.set(shipPos)
        val lastH = if (hasLastHeight) lastHeight else h
        lastHeight = h
        hasLastHeight = true
        // the ship's motion in ring coordinates
        val sx = (vel.x * right.x + vel.y * right.y + vel.z * right.z) * dt
        val sz = (vel.x * zAxis.x + vel.y * zAxis.y + vel.z * zAxis.z) * dt
        val speed = vel.length().toDouble()
        val f = (speed / SPEED_REF).coerceIn(0.0, 1.0)
        val heatPerHit = HEAT_PER_HIT + (MAX_HIT_HEAT - HEAT_PER_HIT) * f * f
        val release = HIT_RADIUS * CONTACT_RELEASE
        val fadeStart = FADE_START * REACH
        val profile = if (b.ringProfile >= 0) RingProfiles.values[b.ringProfile] else null
        val density = b.ringDensity.toDouble().coerceIn(0.1, 1.0)
        var o = 0
        for (i in 0 until count) {
            val ox = offX[i]
            val oz = offZ[i]
            val y = offY[i]
            val mx = ox - sx
            val mz = oz - sz
            // relative to the ship before and after this frame's motion, before the wrap
            val bx = ox
            val by = y - lastH
            val bz = oz
            val ax = mx
            val ay = y - h
            val az = mz
            val wx = wrap(mx, FIELD_SIZE)
            val wz = wrap(mz, FIELD_SIZE)
            offX[i] = wx
            offZ[i] = wz
            val ry = y - h
            val relLen = sqrt(wx * wx + ry * ry + wz * wz)
            val qx = px + wx
            val qz = pz + wz
            val radial = sqrt(qx * qx + qz * qz)
            var shown = 0.0
            if (radial >= inner && radial <= outer) {
                val d = if (profile != null) RingProfiles.densityAt(profile, radial / outer) else density
                val dens = minOf(d * 1.1, 1.0)
                val band = ((dens - threshold[i]) / DENSITY_SOFT).coerceIn(0.0, 1.0)
                shown = band * (1.0 - smoothstep(fadeStart, REACH, relLen))
            }
            if (shown < MIN_SHOWN) {
                lastShown[i] = 0f
                contact[i] = false
                continue
            }
            lastShown[i] = shown.toFloat()
            if (!contact[i]) {
                if (shown > 0.5 && sweptDistance(bx, by, bz, ax, ay, az) < HIT_RADIUS) {
                    contact[i] = true
                    hits++
                }
            } else if (relLen > release) {
                contact[i] = false
            }
            // the instance: position, scale, pose x tumble (Angles(spin·clock)), squash, shade
            instances[o] = wx.toFloat()
            instances[o + 1] = ry.toFloat()
            instances[o + 2] = wz.toFloat()
            instances[o + 3] = (radius[i] * shown).toFloat()
            euler(angle(spinX[i]), angle(spinY[i]), angle(spinZ[i]), qa)
            q.mul(pose[i], qa)
            instances[o + 4] = q.x
            instances[o + 5] = q.y
            instances[o + 6] = q.z
            instances[o + 7] = q.w
            instances[o + 8] = 1f
            instances[o + 9] = squashY[i]
            instances[o + 10] = squashZ[i]
            instances[o + 11] = shade[i]
            o += FLOATS
            visible++
        }
        heat = hits * heatPerHit
        return hits
    }

    /** spin·clock, reduced in double so a long session keeps the tumble smooth. */
    private fun angle(spin: Double): Double {
        val a = spin * clock
        return a - floor(a / (2 * PI)) * (2 * PI)
    }

    /** `setRing`: the rocks' tint from the ring colour (the textures of the Lua come with the asset port). */
    private fun setRing(b: StarSystem.Body) {
        val c = if (b.ringColour != 0) b.ringColour else DEFAULT_COLOUR
        val rr = ((c shr 16) and 0xFF) / 255f
        val gg = ((c shr 8) and 0xFF) / 255f
        val bb = (c and 0xFF) / 255f
        val lum = 0.3f * rr + 0.59f * gg + 0.11f * bb
        icy = lum > ICY_LUMINANCE
        val top = maxOf(rr, gg, bb, 0.05f)
        val k = (lum * 1.3f).coerceIn(0.35f, 1.05f)
        tint[0] = rr / top * k
        tint[1] = gg / top * k
        tint[2] = bb / top * k
    }

    /** `ringFrame(normal)`: helper X unless the normal is close to it, then Z. */
    private fun ringFrame(n: Vec3) {
        val hx: Float
        val hz: Float
        if (abs(n.x) < 0.9f) {
            hx = 1f
            hz = 0f
        } else {
            hx = 0f
            hz = 1f
        }
        val d = hx * n.x + hz * n.z
        right.set(hx - n.x * d, -n.y * d, hz - n.z * d).normalize()
        // z = right × normal (CFrame.fromMatrix(pos, right, normal))
        zAxis.set(right.y * n.z - right.z * n.y, right.z * n.x - right.x * n.z, right.x * n.y - right.y * n.x)
        fromBasis(right, n, zAxis, frame)
    }

    companion object {
        /** `Rings.DebrisCount` and its `Performance.Low` value. */
        const val COUNT = 280
        const val COUNT_LOW = 110

        /** 12 floats per visible rock in [instances]. */
        const val FLOATS = 12

        const val SEED = 84213
        const val SPAWN_RANGE = 2600.0
        const val FIELD_SIZE = 1400.0
        const val THICKNESS = FIELD_SIZE * 0.12
        const val REACH = FIELD_SIZE / 2
        const val MIN_SIZE = 3.0
        const val MAX_SIZE = 26.0
        const val HIT_RADIUS = 26.0
        const val CONTACT_RELEASE = 1.25
        const val HEAT_PER_HIT = 0.012
        const val MAX_HIT_HEAT = 0.25
        const val FADE_START = 0.72
        const val DENSITY_SOFT = 0.12
        const val MIN_SHOWN = 0.03
        const val ICY_LUMINANCE = 0.62f

        /** `Thrust.MaxSpeed`: the speed at which a contact brings the most heat (absolute, 1000 m/s). */
        const val SPEED_REF = 1000.0

        /** `Rings.HitCooldownSeconds`: the least time between two hit sounds. */
        const val HIT_COOLDOWN = 1.0f

        /** `Rings.Color`, the ring colour when the body has none. */
        const val DEFAULT_COLOUR = (150 shl 16) or (136 shl 8) or 118

        /** `wrap(v, s)` = ((v + s/2) mod s) − s/2, with a floored modulo. */
        fun wrap(v: Double, s: Double): Double {
            val t = v + s * 0.5
            return t - floor(t / s) * s - s * 0.5
        }

        /** Closest distance of the segment a→b to the origin (`sweptDistance`). */
        fun sweptDistance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val dz = bz - az
            val dd = dx * dx + dy * dy + dz * dz
            val t = if (dd > 1e-12) (-(ax * dx + ay * dy + az * dz) / dd).coerceIn(0.0, 1.0) else 0.0
            val cx = ax + dx * t
            val cy = ay + dy * t
            val cz = az + dz * t
            return sqrt(cx * cx + cy * cy + cz * cz)
        }

        private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** Roblox `CFrame.Angles(rx, ry, rz)` (= fromEulerAnglesXYZ: Rx·Ry·Rz) as a quaternion. */
        fun euler(rx: Double, ry: Double, rz: Double, out: Quat): Quat {
            val cx = cos(rx * 0.5)
            val sx = sin(rx * 0.5)
            val cy = cos(ry * 0.5)
            val sy = sin(ry * 0.5)
            val cz = cos(rz * 0.5)
            val sz = sin(rz * 0.5)
            // (Rx·Ry)·Rz
            val xyx = sx * cy
            val xyy = cx * sy
            val xyz = sx * sy
            val xyw = cx * cy
            return out.set(
                (xyx * cz + xyy * sz).toFloat(),
                (xyy * cz - xyx * sz).toFloat(),
                (xyw * sz + xyz * cz).toFloat(),
                (xyw * cz - xyz * sz).toFloat(),
            )
        }

        /** The rotation whose columns are [x], [y], [z] (an orthonormal right-handed basis). */
        fun fromBasis(x: Vec3, y: Vec3, z: Vec3, out: Quat): Quat {
            val m00 = x.x
            val m10 = x.y
            val m20 = x.z
            val m01 = y.x
            val m11 = y.y
            val m21 = y.z
            val m02 = z.x
            val m12 = z.y
            val m22 = z.z
            val tr = m00 + m11 + m22
            if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                return out.set((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            }
            if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                return out.set(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                return out.set((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            return out.set((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }
    }
}
