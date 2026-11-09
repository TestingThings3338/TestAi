package com.starwake.core.station

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where each group of a station is right now: per group a rotation (3x3 row-major) about a centre
 * point, and whether its colliders count (a build site's unbuilt stages do not). Group 0 never
 * moves. StationInstance keeps one up to date; [identity] is the model at rest.
 */
class GroupFrames(val groupCount: Int) {
    /** Row-major 3x3 rotation per group. */
    @JvmField val rot = FloatArray(groupCount * 9)

    /** Centre of rotation per group (station space). */
    @JvmField val centre = FloatArray(groupCount * 3)

    /** Whether the group's colliders take part in queries. */
    @JvmField val solid = BooleanArray(groupCount) { true }

    init {
        identity()
    }

    fun identity(): GroupFrames {
        rot.fill(0f)
        for (g in 0 until groupCount) {
            rot[g * 9] = 1f; rot[g * 9 + 4] = 1f; rot[g * 9 + 8] = 1f
        }
        centre.fill(0f)
        return this
    }

    /** Sets group [g] to a rotation of [angle] radians about the unit [axis] through ([cx], [cy], [cz]). */
    fun set(g: Int, ax: Float, ay: Float, az: Float, angle: Float, cx: Float, cy: Float, cz: Float) {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val t = 1f - c
        val o = g * 9
        rot[o] = t * ax * ax + c
        rot[o + 1] = t * ax * ay - s * az
        rot[o + 2] = t * ax * az + s * ay
        rot[o + 3] = t * ax * ay + s * az
        rot[o + 4] = t * ay * ay + c
        rot[o + 5] = t * ay * az - s * ax
        rot[o + 6] = t * ax * az - s * ay
        rot[o + 7] = t * ay * az + s * ax
        rot[o + 8] = t * az * az + c
        centre[g * 3] = cx
        centre[g * 3 + 1] = cy
        centre[g * 3 + 2] = cz
    }

    companion object {
        /** All groups at rest (shared, never written). */
        fun identity(groupCount: Int) = GroupFrames(groupCount)
    }
}

/** Result of [StationColliders.sphereContact]: the deepest contact. Station space. */
class StationContact {
    /** Unit normal from the collider surface towards the sphere centre. */
    @JvmField var nx = 0f
    @JvmField var ny = 0f
    @JvmField var nz = 0f

    /** Penetration depth (m): sphere radius minus the distance from its centre to the collider. */
    @JvmField var depth = 0f

    /** Closest point on the collider (station space). */
    @JvmField var px = 0f
    @JvmField var py = 0f
    @JvmField var pz = 0f

    /** Index of the collider in the model's set. */
    @JvmField var collider = -1
}

/** Result of [StationColliders.sphereCast]. Station space. */
class StationHit {
    /** Fraction (0..1) of the move at first contact. */
    @JvmField var fraction = 1f

    /** Unit contact normal (pointing away from the collider). */
    @JvmField var nx = 0f
    @JvmField var ny = 0f
    @JvmField var nz = 0f
    @JvmField var collider = -1
}

/**
 * The invisible collider parts of a station model (attribute Collider = true, ShipStation's only
 * solid parts), as struct-of-arrays in station space: blocks (oriented boxes), balls, cylinders
 * (axis local X, Roblox's radius min(Y, Z) / 2) and wedges (slope from front-bottom to back-top).
 *
 * Queries take a sphere in station space and the current [GroupFrames] (colliders in a spinning
 * group turn with it: the query is moved into the group's rest frame instead of moving colliders).
 * Nothing allocates. A query rejects colliders by bounding sphere first, so five probe spheres
 * against ~300 colliders cost a few microseconds.
 */
class StationColliders(shapes: IntArray, groups: IntArray, frames: FloatArray, sizes: FloatArray) {
    val count = shapes.size
    private val shape = shapes.copyOf()
    private val group = groups.copyOf()

    /** Centre (x, y, z) per collider. */
    private val cx = FloatArray(count)
    private val cy = FloatArray(count)
    private val cz = FloatArray(count)

    /** Row-major rotation per collider (columns are the part's axes). */
    private val r = FloatArray(count * 9)

    /** Half size per collider. */
    private val hx = FloatArray(count)
    private val hy = FloatArray(count)
    private val hz = FloatArray(count)

    /** Bounding sphere radius per collider. */
    private val bound = FloatArray(count)

    /** Largest group index + 1 that any collider uses. */
    val groupCount: Int

    // scratch: the query point(s) in each group's rest frame
    private val qx = FloatArray(MAX_GROUPS)
    private val qy = FloatArray(MAX_GROUPS)
    private val qz = FloatArray(MAX_GROUPS)
    private val ex = FloatArray(MAX_GROUPS)
    private val ey = FloatArray(MAX_GROUPS)
    private val ez = FloatArray(MAX_GROUPS)
    private val tmp = FloatArray(7)

    init {
        var gmax = 0
        for (i in 0 until count) {
            cx[i] = frames[i * 12]
            cy[i] = frames[i * 12 + 1]
            cz[i] = frames[i * 12 + 2]
            System.arraycopy(frames, i * 12 + 3, r, i * 9, 9)
            hx[i] = sizes[i * 3] * 0.5f
            hy[i] = sizes[i * 3 + 1] * 0.5f
            hz[i] = sizes[i * 3 + 2] * 0.5f
            bound[i] = sqrt(hx[i] * hx[i] + hy[i] * hy[i] + hz[i] * hz[i])
            gmax = maxOf(gmax, group[i])
        }
        groupCount = gmax + 1
        require(groupCount <= MAX_GROUPS) { "collider group $gmax out of range" }
    }

    fun shape(i: Int) = shape[i]
    fun group(i: Int) = group[i]

    /** Collider [i]'s centre and half size at rest (station space): x, y, z, hx, hy, hz into [out]. */
    fun box(i: Int, out: FloatArray) {
        out[0] = cx[i]; out[1] = cy[i]; out[2] = cz[i]
        out[3] = hx[i]; out[4] = hy[i]; out[5] = hz[i]
    }

    /** Collider [i]'s rotation (row-major 3x3) at rest into [out]. */
    fun rotation(i: Int, out: FloatArray) = System.arraycopy(r, i * 9, out, 0, 9)

    /**
     * Deepest contact of the sphere ([x], [y], [z], [radius]) with any solid collider, or false when
     * it touches none. [frames] = where the groups are now (null: at rest).
     */
    fun sphereContact(x: Float, y: Float, z: Float, radius: Float, frames: GroupFrames?, out: StationContact): Boolean {
        toGroups(x, y, z, frames, qx, qy, qz)
        var best = -1
        var bestDepth = 0f
        for (i in 0 until count) {
            val g = group[i]
            if (frames != null && !frames.solid[g]) continue
            val px = qx[g]
            val py = qy[g]
            val pz = qz[g]
            val dx = px - cx[i]
            val dy = py - cy[i]
            val dz = pz - cz[i]
            val reach = bound[i] + radius
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            val d = signedDistance(i, px, py, pz, tmp)
            val depth = radius - d
            if (depth > 0f && (best < 0 || depth > bestDepth)) {
                best = i
                bestDepth = depth
                out.nx = tmp[1]; out.ny = tmp[2]; out.nz = tmp[3]
                out.px = tmp[4]; out.py = tmp[5]; out.pz = tmp[6]
            }
        }
        if (best < 0) return false
        out.depth = bestDepth
        out.collider = best
        // back from the group's rest frame to station space
        if (frames != null && group[best] != 0) {
            val g = group[best]
            rotate(frames, g, out.nx, out.ny, out.nz, tmp)
            out.nx = tmp[0]; out.ny = tmp[1]; out.nz = tmp[2]
            transform(frames, g, out.px, out.py, out.pz, tmp)
            out.px = tmp[0]; out.py = tmp[1]; out.pz = tmp[2]
        }
        return true
    }

    /** True when the sphere touches a solid collider. */
    fun overlaps(x: Float, y: Float, z: Float, radius: Float, frames: GroupFrames?): Boolean {
        toGroups(x, y, z, frames, qx, qy, qz)
        for (i in 0 until count) {
            val g = group[i]
            if (frames != null && !frames.solid[g]) continue
            val dx = qx[g] - cx[i]
            val dy = qy[g] - cy[i]
            val dz = qz[g] - cz[i]
            val reach = bound[i] + radius
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            if (signedDistance(i, qx[g], qy[g], qz[g], tmp) < radius) return true
        }
        return false
    }

    /**
     * Distance from the sphere's surface to the nearest solid collider (negative: penetration), or
     * [Float.MAX_VALUE] beyond [maxDistance].
     */
    fun clearance(x: Float, y: Float, z: Float, radius: Float, maxDistance: Float, frames: GroupFrames?): Float {
        toGroups(x, y, z, frames, qx, qy, qz)
        var best = Float.MAX_VALUE
        for (i in 0 until count) {
            val g = group[i]
            if (frames != null && !frames.solid[g]) continue
            val dx = qx[g] - cx[i]
            val dy = qy[g] - cy[i]
            val dz = qz[g] - cz[i]
            val reach = bound[i] + radius + maxDistance
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            val d = signedDistance(i, qx[g], qy[g], qz[g], tmp) - radius
            if (d < best) best = d
        }
        return if (best <= maxDistance) best else Float.MAX_VALUE
    }

    /**
     * Sweeps a sphere of [radius] from ([x0], [y0], [z0]) to ([x1], [y1], [z1]) (ShipStation.collide's
     * Spherecast): the first solid collider it touches, with the fraction of the move and the
     * contact normal. Like Roblox's Spherecast, colliders the sphere already overlaps at the start
     * are ignored (use [sphereContact] to push out of those). Conservative advancement on the exact
     * distance to each candidate; allocation free.
     */
    fun sphereCast(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, radius: Float,
        frames: GroupFrames?, out: StationHit,
    ): Boolean {
        toGroups(x0, y0, z0, frames, qx, qy, qz)
        toGroups(x1, y1, z1, frames, ex, ey, ez)
        var bestT = 2f
        var best = -1
        for (i in 0 until count) {
            val g = group[i]
            if (frames != null && !frames.solid[g]) continue
            val ax = qx[g]
            val ay = qy[g]
            val az = qz[g]
            val mx = ex[g] - ax
            val my = ey[g] - ay
            val mz = ez[g] - az
            // bounding sphere vs the swept segment
            val len2 = mx * mx + my * my + mz * mz
            var t = 0f
            if (len2 > 1e-12f) t = (((cx[i] - ax) * mx + (cy[i] - ay) * my + (cz[i] - az) * mz) / len2).coerceIn(0f, 1f)
            val dx = ax + mx * t - cx[i]
            val dy = ay + my * t - cy[i]
            val dz = az + mz * t - cz[i]
            val reach = bound[i] + radius
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue
            if (signedDistance(i, ax, ay, az, tmp) < radius) continue // overlapping at the start
            val len = sqrt(len2)
            if (len < 1e-6f) continue
            var s = 0f
            var hit = false
            for (k in 0 until MAX_STEPS) {
                val d = signedDistance(i, ax + mx * s, ay + my * s, az + mz * s, tmp) - radius
                if (d <= CONTACT_EPS) {
                    hit = true
                    break
                }
                s += d / len
                if (s > 1f || s >= bestT) break
            }
            if (hit && s < bestT) {
                bestT = s
                best = i
                out.nx = tmp[1]; out.ny = tmp[2]; out.nz = tmp[3]
            }
        }
        if (best < 0) return false
        out.fraction = bestT.coerceIn(0f, 1f)
        out.collider = best
        val g = group[best]
        if (frames != null && g != 0) {
            rotate(frames, g, out.nx, out.ny, out.nz, tmp)
            out.nx = tmp[0]; out.ny = tmp[1]; out.nz = tmp[2]
        }
        return true
    }

    // ------------------------------------------------------------------ internals

    /** The point in each group's rest frame: p' = R^T (p - c) + c. */
    private fun toGroups(x: Float, y: Float, z: Float, frames: GroupFrames?, ox: FloatArray, oy: FloatArray, oz: FloatArray) {
        for (g in 0 until groupCount) {
            if (frames == null || g == 0) {
                ox[g] = x; oy[g] = y; oz[g] = z
                continue
            }
            val m = frames.rot
            val o = g * 9
            val c = g * 3
            val dx = x - frames.centre[c]
            val dy = y - frames.centre[c + 1]
            val dz = z - frames.centre[c + 2]
            ox[g] = m[o] * dx + m[o + 3] * dy + m[o + 6] * dz + frames.centre[c]
            oy[g] = m[o + 1] * dx + m[o + 4] * dy + m[o + 7] * dz + frames.centre[c + 1]
            oz[g] = m[o + 2] * dx + m[o + 5] * dy + m[o + 8] * dz + frames.centre[c + 2]
        }
    }

    private fun rotate(frames: GroupFrames, g: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        val m = frames.rot
        val o = g * 9
        out[0] = m[o] * x + m[o + 1] * y + m[o + 2] * z
        out[1] = m[o + 3] * x + m[o + 4] * y + m[o + 5] * z
        out[2] = m[o + 6] * x + m[o + 7] * y + m[o + 8] * z
    }

    private fun transform(frames: GroupFrames, g: Int, x: Float, y: Float, z: Float, out: FloatArray) {
        val c = g * 3
        rotate(frames, g, x - frames.centre[c], y - frames.centre[c + 1], z - frames.centre[c + 2], out)
        out[0] += frames.centre[c]
        out[1] += frames.centre[c + 1]
        out[2] += frames.centre[c + 2]
    }

    /**
     * Signed distance from (px, py, pz) (the collider's group rest frame = station space at rest) to
     * collider [i]: positive outside, negative inside. Writes the unit normal (from the surface
     * towards the point) to out[1..3] and the closest surface point to out[4..6]; out[0] = distance.
     */
    internal fun signedDistance(i: Int, px: Float, py: Float, pz: Float, out: FloatArray): Float {
        val o = i * 9
        val dx = px - cx[i]
        val dy = py - cy[i]
        val dz = pz - cz[i]
        // into the collider's local frame (columns of r are its axes)
        val lx = r[o] * dx + r[o + 3] * dy + r[o + 6] * dz
        val ly = r[o + 1] * dx + r[o + 4] * dy + r[o + 7] * dz
        val lz = r[o + 2] * dx + r[o + 5] * dy + r[o + 8] * dz
        val d = when (shape[i]) {
            SHAPE_BALL -> ball(i, lx, ly, lz, out)
            SHAPE_CYLINDER -> cylinder(i, lx, ly, lz, out)
            SHAPE_WEDGE -> wedge(i, lx, ly, lz, out)
            SHAPE_CORNER_WEDGE -> cornerWedge(i, lx, ly, lz, out)
            else -> box(hx[i], hy[i], hz[i], lx, ly, lz, out)
        }
        // local normal and closest point (out[1..6]) back to station space
        val nx = out[1]
        val ny = out[2]
        val nz = out[3]
        out[1] = r[o] * nx + r[o + 1] * ny + r[o + 2] * nz
        out[2] = r[o + 3] * nx + r[o + 4] * ny + r[o + 5] * nz
        out[3] = r[o + 6] * nx + r[o + 7] * ny + r[o + 8] * nz
        val qx = out[4]
        val qy = out[5]
        val qz = out[6]
        out[4] = cx[i] + r[o] * qx + r[o + 1] * qy + r[o + 2] * qz
        out[5] = cy[i] + r[o + 3] * qx + r[o + 4] * qy + r[o + 5] * qz
        out[6] = cz[i] + r[o + 6] * qx + r[o + 7] * qy + r[o + 8] * qz
        out[0] = d
        return d
    }

    private fun box(ex: Float, ey: Float, ez: Float, lx: Float, ly: Float, lz: Float, out: FloatArray): Float {
        val qx = lx.coerceIn(-ex, ex)
        val qy = ly.coerceIn(-ey, ey)
        val qz = lz.coerceIn(-ez, ez)
        val dx = lx - qx
        val dy = ly - qy
        val dz = lz - qz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > 0f) {
            val d = sqrt(d2)
            out[1] = dx / d; out[2] = dy / d; out[3] = dz / d
            out[4] = qx; out[5] = qy; out[6] = qz
            return d
        }
        // inside: leave through the nearest face
        val fx = ex - abs(lx)
        val fy = ey - abs(ly)
        val fz = ez - abs(lz)
        out[1] = 0f; out[2] = 0f; out[3] = 0f
        out[4] = lx; out[5] = ly; out[6] = lz
        return if (fx <= fy && fx <= fz) {
            val s = if (lx >= 0f) 1f else -1f
            out[1] = s; out[4] = s * ex
            -fx
        } else if (fy <= fz) {
            val s = if (ly >= 0f) 1f else -1f
            out[2] = s; out[5] = s * ey
            -fy
        } else {
            val s = if (lz >= 0f) 1f else -1f
            out[3] = s; out[6] = s * ez
            -fz
        }
    }

    private fun ball(i: Int, lx: Float, ly: Float, lz: Float, out: FloatArray): Float {
        val rad = minOf(hx[i], hy[i], hz[i])
        val l = sqrt(lx * lx + ly * ly + lz * lz)
        if (l < 1e-9f) {
            out[1] = 0f; out[2] = 1f; out[3] = 0f
            out[4] = 0f; out[5] = rad; out[6] = 0f
            return -rad
        }
        out[1] = lx / l; out[2] = ly / l; out[3] = lz / l
        out[4] = out[1] * rad; out[5] = out[2] * rad; out[6] = out[3] * rad
        return l - rad
    }

    private fun cylinder(i: Int, lx: Float, ly: Float, lz: Float, out: FloatArray): Float {
        val half = hx[i]
        val rad = minOf(hy[i], hz[i])
        val rr = sqrt(ly * ly + lz * lz)
        // radial unit direction (any when on the axis)
        val uy = if (rr > 1e-9f) ly / rr else 1f
        val uz = if (rr > 1e-9f) lz / rr else 0f
        val ax = abs(lx) - half
        val ar = rr - rad
        if (ax <= 0f && ar <= 0f) {
            // inside: nearest of the caps and the mantle
            if (ax > ar) {
                val s = if (lx >= 0f) 1f else -1f
                out[1] = s; out[2] = 0f; out[3] = 0f
                out[4] = s * half; out[5] = ly; out[6] = lz
                return ax
            }
            out[1] = 0f; out[2] = uy; out[3] = uz
            out[4] = lx; out[5] = uy * rad; out[6] = uz * rad
            return ar
        }
        val qx = lx.coerceIn(-half, half)
        val qr = minOf(rr, rad)
        val qy = uy * qr
        val qz = uz * qr
        val dx = lx - qx
        val dy = ly - qy
        val dz = lz - qz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        out[1] = dx / d; out[2] = dy / d; out[3] = dz / d
        out[4] = qx; out[5] = qy; out[6] = qz
        return d
    }

    /**
     * WedgePart: the box cut by the slope plane through the front-bottom edge (-Z, -Y) and the
     * back-top edge (+Z, +Y). Outside the box's clamp point lies in the wedge or the nearest point
     * is on the slope rectangle; inside, the nearest of the five faces.
     */
    private fun wedge(i: Int, lx: Float, ly: Float, lz: Float, out: FloatArray): Float {
        val ex = hx[i]
        val ey = hy[i]
        val ez = hz[i]
        // slope plane: n . p = 0 through the box centre, n = (0, ez, -ey) / |.| (outwards: up and forward)
        val nl = sqrt(ey * ey + ez * ez)
        val ny = ez / nl
        val nz = -ey / nl
        val sd = ly * ny + lz * nz
        val inBox = abs(lx) <= ex && abs(ly) <= ey && abs(lz) <= ez
        if (inBox && sd <= 0f) {
            // inside the wedge: the nearest face
            var best = -sd
            out[1] = 0f; out[2] = ny; out[3] = nz
            out[4] = lx; out[5] = ly - ny * sd; out[6] = lz - nz * sd
            val fx = ex - abs(lx)
            if (fx < best) {
                best = fx
                val s = if (lx >= 0f) 1f else -1f
                out[1] = s; out[2] = 0f; out[3] = 0f
                out[4] = s * ex; out[5] = ly; out[6] = lz
            }
            val fb = ly + ey // bottom
            if (fb < best) {
                best = fb
                out[1] = 0f; out[2] = -1f; out[3] = 0f
                out[4] = lx; out[5] = -ey; out[6] = lz
            }
            val fk = ez - lz // back
            if (fk < best) {
                best = fk
                out[1] = 0f; out[2] = 0f; out[3] = 1f
                out[4] = lx; out[5] = ly; out[6] = ez
            }
            return -best
        }
        val qx = lx.coerceIn(-ex, ex)
        var qy = ly.coerceIn(-ey, ey)
        var qz = lz.coerceIn(-ez, ez)
        if (qy * ny + qz * nz > 0f) {
            // the box's nearest point is cut away: nearest point on the slope rectangle, which runs
            // from (y, z) = (-ey, -ez) to (ey, ez)
            val sy = 2f * ey
            val sz = 2f * ez
            val sl2 = sy * sy + sz * sz
            var t = ((ly + ey) * sy + (lz + ez) * sz) / sl2
            t = t.coerceIn(0f, 1f)
            qy = -ey + sy * t
            qz = -ez + sz * t
        }
        val dx = lx - qx
        val dy = ly - qy
        val dz = lz - qz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-9f) {
            out[1] = 0f; out[2] = ny; out[3] = nz
        } else {
            out[1] = dx / d; out[2] = dy / d; out[3] = dz / d
        }
        out[4] = qx; out[5] = qy; out[6] = qz
        return d
    }

    /**
     * CornerWedgePart (none in the current models): apex above the right-front corner. Treated as
     * its box cut by its two slopes; outside distances are the box's (a slight over-estimate of the
     * hull near the cut corner).
     */
    private fun cornerWedge(i: Int, lx: Float, ly: Float, lz: Float, out: FloatArray): Float =
        box(hx[i], hy[i], hz[i], lx, ly, lz, out)

    companion object {
        const val SHAPE_BLOCK = 0
        const val SHAPE_BALL = 1
        const val SHAPE_CYLINDER = 2
        const val SHAPE_WEDGE = 3
        const val SHAPE_CORNER_WEDGE = 4
        const val MAX_GROUPS = 8
        private const val MAX_STEPS = 48
        private const val CONTACT_EPS = 1e-3f
    }
}
