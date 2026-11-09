package com.starwake.core.game

import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Station hulls are solid: coarse collision proxies for every [StationMesh] model, and auto-dock
 * routing around them.
 *
 * Proxies live in station-local space (metres, +Y up, docking entrance towards -Z) and follow the
 * mesh within a few metres. Each is a polygonal solid of revolution (habitat ring, hub, spindle,
 * dock head, tanks, modules, pad, dish) and/or a set of half-spaces (boxes, ring sectors, the cut
 * around the docking slot). The slot is a real opening: a ship can fly into the tunnel and park at
 * half its depth without touching anything, while the tunnel's side walls and back wall stop it.
 *
 * Everything called per frame is allocation free; the proxies are built once, when the class loads.
 */
class StationCollision(private val flight: FlightModel) {
    // scratch (no per-frame allocation)
    private val local = Vec3()
    private val world = Vec3()
    private val portPos = Vec3()
    private val portDir = Vec3()
    private val slot = Vec3()

    // normal of the last [distance] / [sceneDistance] query (station-local, unit, pointing out)
    private var nx = 0f
    private var ny = 0f
    private var nz = 0f

    /**
     * Resolves the ship's collision sphere against [st]'s proxies: every contact goes through
     * [FlightModel.resolveContact]. Returns the largest impact speed in m/s (0 = no contact).
     * Cheap when the ship is more than the station radius + 50 m from its centre.
     */
    fun collide(ship: ShipState, st: StarSystem.Station): Float {
        val dx = ship.pos.x - st.pos.x
        val dy = ship.pos.y - st.pos.y
        val dz = ship.pos.z - st.pos.z
        val reach = st.radius + COLLIDE_RANGE
        if (dx * dx + dy * dy + dz * dz > reach * reach) return 0f
        // the delta is small here, so floats are exact enough in local space
        st.rot.rotateInverse(dx.toFloat(), dy.toFloat(), dz.toFloat(), local)
        var px = local.x
        var py = local.y
        var pz = local.z
        val r = flight.spec.collisionRadius
        var impact = 0f
        for (q in PROXIES[st.model]) {
            val bx = px - q.bx
            val by = py - q.by
            val bz = pz - q.bz
            val lim = q.br + r
            if (bx * bx + by * by + bz * bz >= lim * lim) continue
            val d = distance(q, px, py, pz)
            if (d >= r) continue
            val push = r - d
            st.rot.rotate(nx, ny, nz, world)
            val hit = flight.resolveContact(ship, world.x, world.y, world.z, push.toDouble())
            if (hit > impact) impact = hit
            // keep the local position in step with the push, so later proxies see the moved ship
            px += nx * push
            py += ny * push
            pz += nz * push
        }
        return impact
    }

    /**
     * Gap in metres between the ship's collision sphere and [st]'s nearest proxy (negative while
     * touching), for proximity warnings. Exact within the station radius + 120 m of its centre;
     * further out it returns a lower bound that is at least 113 m.
     */
    fun clearance(ship: ShipState, st: StarSystem.Station): Float {
        val dx = ship.pos.x - st.pos.x
        val dy = ship.pos.y - st.pos.y
        val dz = ship.pos.z - st.pos.z
        val r = flight.spec.collisionRadius
        val d2 = dx * dx + dy * dy + dz * dz
        val reach = st.radius + CLEARANCE_RANGE
        if (d2 > reach * reach) return (sqrt(d2) - st.radius).toFloat() - r
        st.rot.rotateInverse(dx.toFloat(), dy.toFloat(), dz.toFloat(), local)
        return sceneDistance(PROXIES[st.model], local.x, local.y, local.z, Float.MAX_VALUE) - r
    }

    /**
     * Signed distance (metres) from the station-local point (x, y, z) to the proxies of [model]:
     * negative inside a proxy. A lower bound of the true distance outside. For debug views and tests.
     */
    fun localDistance(model: Int, x: Float, y: Float, z: Float): Float =
        sceneDistance(PROXIES[model], x, y, z, Float.MAX_VALUE)

    /**
     * Auto-dock routing. The ship at [shipPos] wants the stand-off point [DOCK_STANDOFF] metres in
     * front of [st]'s docking port. Returns false when it can fly straight there. Otherwise writes a
     * detour waypoint (world space) to [out] and returns true; fly to it this frame and ask again
     * next frame.
     *
     * The keep-out is the station's bounding sphere + 50 m, behind the plane 40 m in front of the
     * hull's front-most point (a ship behind that plane and near the station has to go around).
     * - In front of that plane, the line to the stand-off is always clear.
     * - Outside the sphere: straight to the stand-off if the line misses the keep-out; else to a
     *   point beside the station level with the stand-off (radius + 150 m off the dock axis, on the
     *   ship's side); else first straight out from the dock axis to that radius.
     * - Inside it (close in, e.g. between the ring and the spindle): out along the first clear line
     *   of forward, away from the dock axis, backward, up, down (checked against the proxies).
     *
     * Every leg ends where the next one starts clear and each leg's remainder stays clear, so
     * repeated calls converge (ship -> waypoints -> stand-off) without oscillating.
     */
    fun dockWaypoint(st: StarSystem.Station, shipPos: DVec3, out: DVec3): Boolean {
        val m = st.model
        val set = PROXIES[m]
        StationMesh.dockPort(m, portPos, portDir)
        val ax = portDir.x
        val ay = portDir.y
        val az = portDir.z
        st.rot.rotateInverse(
            (shipPos.x - st.pos.x).toFloat(), (shipPos.y - st.pos.y).toFloat(), (shipPos.z - st.pos.z).toFloat(), local,
        )
        val lx = local.x
        val ly = local.y
        val lz = local.z
        // along = position on the dock axis (it runs through the origin, positive towards the stand-off)
        val along = lx * ax + ly * ay + lz * az
        val front = FRONT_EXTENT[m] + FRONT_MARGIN
        if (along >= front) return false
        // inside the slot tunnel (or in front of its opening): the line out to the stand-off on the
        // axis only narrows towards the axis, so it leaves through the opening (the tunnel runs along Z)
        val portA = portPos.x * ax + portPos.y * ay + portPos.z * az
        StationMesh.slotSize(m, slot)
        if (abs(lx - portPos.x) < slot.x && abs(ly - portPos.y) < slot.y && along >= portA - slot.z) return false
        val standA = portA + DOCK_STANDOFF.toFloat()
        val sx = ax * standA
        val sy = ay * standA
        val sz = az * standA
        // the ship's side of the dock axis (station up when it sits on the axis)
        var ox = lx - ax * along
        var oy = ly - ay * along
        var oz = lz - az * along
        val lat = sqrt(ox * ox + oy * oy + oz * oz)
        if (lat > 1f) {
            ox /= lat; oy /= lat; oz /= lat
        } else {
            val k = ay // station up (0, 1, 0) minus its part along the axis
            ox = -ax * k; oy = 1f - ay * k; oz = -az * k
            val l = sqrt(ox * ox + oy * oy + oz * oz)
            if (l < 1e-3f) {
                ox = 1f; oy = 0f; oz = 0f
            } else {
                ox /= l; oy /= l; oz /= l
            }
        }
        val rb = (st.radius + KEEP_OUT_MARGIN).toFloat()
        val rd = (st.radius + DETOUR_MARGIN).toFloat()
        if (lx * lx + ly * ly + lz * lz >= rb * rb) {
            // outside the sphere: straight there, else level with the stand-off, else out sideways first
            if (!hitsKeepOut(lx, ly, lz, sx, sy, sz, ax, ay, az, rb, front)) return false
            val fx = ox * rd + sx
            val fy = oy * rd + sy
            val fz = oz * rd + sz
            if (!hitsKeepOut(lx, ly, lz, fx, fy, fz, ax, ay, az, rb, front)) {
                toWorld(st, fx, fy, fz, out)
                return true
            }
            // lat < rd here (from further out the leg above never enters the sphere)
            toWorld(st, ox * rd + ax * along, oy * rd + ay * along, oz * rd + az * along, out)
            return true
        }
        // inside the keep-out: leave it along the first clear line
        val r = flight.spec.collisionRadius
        val s0 = sceneDistance(set, lx, ly, lz, Float.MAX_VALUE)
        val awayX = nx
        val awayY = ny
        val awayZ = nz
        // near a wall, accept lines that keep (almost) today's gap; never closer than r + 1.25 m
        val minClear = maxOf(r + 2.5f, minOf(r + ESCAPE_CLEAR, s0 - 1f))
        val tol = minOf(8f, (minClear - r) * 0.5f)
        if (s0 >= minClear) {
            for (k in 0 until ESCAPE_DIRECTIONS) {
                var dx: Float
                var dy: Float
                var dz: Float
                when (k) {
                    0 -> { dx = ax; dy = ay; dz = az }
                    1 -> { dx = ox; dy = oy; dz = oz }
                    2 -> { dx = -ax; dy = -ay; dz = -az }
                    3 -> { dx = 0f; dy = 1f; dz = 0f }
                    else -> { dx = 0f; dy = -1f; dz = 0f }
                }
                // forward ends just in front of the keep-out; the others where they leave the detour sphere
                val len = if (k == 0) front + 30f - along else rayExit(lx, ly, lz, dx, dy, dz, rd)
                if (len <= 0f) continue
                val ex = lx + dx * len
                val ey = ly + dy * len
                val ez = lz + dz * len
                if (pathClear(set, lx, ly, lz, ex, ey, ez, minClear, tol)) {
                    toWorld(st, ex, ey, ez, out)
                    return true
                }
            }
        }
        // touching or boxed in: back away from the nearest surface and look again next frame
        toWorld(st, lx + awayX * 40f, ly + awayY * 40f, lz + awayZ * 40f, out)
        return true
    }

    /** Signed distance from a station-local point to proxy [index] of [model] (tests and debug views). */
    internal fun proxyDistance(model: Int, index: Int, x: Float, y: Float, z: Float): Float =
        distance(PROXIES[model][index], x, y, z)

    // ------------------------------------------------------------------ internals

    private fun toWorld(st: StarSystem.Station, x: Float, y: Float, z: Float, out: DVec3) {
        st.rot.rotate(x, y, z, world)
        out.set(st.pos.x + world.x, st.pos.y + world.y, st.pos.z + world.z)
    }

    /** Distance along the unit direction d from p (inside the sphere of radius [rs]) to its surface. */
    private fun rayExit(px: Float, py: Float, pz: Float, dx: Float, dy: Float, dz: Float, rs: Float): Float {
        val b = px * dx + py * dy + pz * dz
        val c = px * px + py * py + pz * pz - rs * rs
        val disc = b * b - c
        return if (disc <= 0f) 0f else -b + sqrt(disc)
    }

    /**
     * Does the segment p0 -> p1 (local) enter the keep-out: the ball of radius [rb] around the
     * origin, behind the plane along = [front] (along = p·a)? Exact (doubles), so a leg that misses
     * it keeps missing it as the ship flies along.
     */
    private fun hitsKeepOut(
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        ax: Float, ay: Float, az: Float, rb: Float, front: Float,
    ): Boolean {
        val px = x0.toDouble()
        val py = y0.toDouble()
        val pz = z0.toDouble()
        val dx = x1 - px
        val dy = y1 - py
        val dz = z1 - pz
        val qa = dx * dx + dy * dy + dz * dz
        val qb = 2.0 * (px * dx + py * dy + pz * dz)
        val qc = px * px + py * py + pz * pz - rb.toDouble() * rb
        var t0: Double
        var t1: Double
        if (qa < 1e-9) {
            if (qc >= 0.0) return false
            t0 = 0.0
            t1 = 1.0
        } else {
            val disc = qb * qb - 4.0 * qa * qc
            if (disc <= 0.0) return false
            val sq = sqrt(disc)
            t0 = maxOf(0.0, (-qb - sq) / (2.0 * qa))
            t1 = minOf(1.0, (-qb + sq) / (2.0 * qa))
        }
        if (t0 >= t1) return false
        val a0 = px * ax + py * ay + pz * az
        val da = dx * ax + dy * ay + dz * az
        if (abs(da) < 1e-9) return a0 < front
        val tc = (front - a0) / da
        if (da > 0.0) t1 = minOf(t1, tc) else t0 = maxOf(t0, tc)
        return t0 < t1
    }

    /**
     * Sphere-traces p0 -> p1 through [set]: true when every sample keeps [minClear] from the proxies,
     * which guarantees at least minClear - [tol] along the whole line. Gives up after
     * [MAX_TRACE_STEPS] samples (a line grazing a wall for a long way counts as blocked).
     */
    private fun pathClear(
        set: Array<Proxy>, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, minClear: Float, tol: Float,
    ): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        val dz = z1 - z0
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3f) return sceneDistance(set, x0, y0, z0, minClear + TRACE_CAP) >= minClear
        val ix = dx / len
        val iy = dy / len
        val iz = dz / len
        var t = 0f
        var steps = 0
        while (true) {
            val s = sceneDistance(set, x0 + ix * t, y0 + iy * t, z0 + iz * t, minClear + TRACE_CAP)
            if (s < minClear) return false
            if (t >= len) return true
            if (++steps > MAX_TRACE_STEPS) return false
            // Lipschitz bound: nothing within s - minClear + tol of this sample comes closer than minClear - tol
            t = minOf(len, t + s - minClear + tol)
        }
    }

    /**
     * Distance from (px, py, pz) to the nearest proxy of [set], capped at [cap] (proxies whose
     * bounding sphere is further than the best so far are skipped). Leaves its normal in nx/ny/nz.
     */
    private fun sceneDistance(set: Array<Proxy>, px: Float, py: Float, pz: Float, cap: Float): Float {
        var best = cap
        var bnx = 0f
        var bny = 1f
        var bnz = 0f
        for (q in set) {
            val dx = px - q.bx
            val dy = py - q.by
            val dz = pz - q.bz
            if (sqrt(dx * dx + dy * dy + dz * dz) - q.br >= best) continue
            val d = distance(q, px, py, pz)
            if (d < best) {
                best = d
                bnx = nx; bny = ny; bnz = nz
            }
        }
        nx = bnx; ny = bny; nz = bnz
        return best
    }

    /**
     * Signed distance from (px, py, pz) to proxy [q] (negative inside), outward normal in nx/ny/nz.
     * Inside: the largest of the profile and plane distances (exact for convex solids). Outside:
     * the root of the summed squares of the positive ones (exact where they meet at right angles).
     */
    private fun distance(q: Proxy, px: Float, py: Float, pz: Float): Float {
        var out2 = 0f
        var gx = 0f
        var gy = 0f
        var gz = 0f
        var inner = -Float.MAX_VALUE
        var ix = 0f
        var iy = 1f
        var iz = 0f
        val edges = q.eu.size
        if (edges > 0) {
            val dx = px - q.ox
            val dy = py - q.oy
            val dz = pz - q.oz
            val a = dx * q.ux + dy * q.uy + dz * q.uz
            val b = dx * q.vx + dy * q.vy + dz * q.vz
            val h = dx * q.wx + dy * q.wy + dz * q.wz
            // u: distance from the axis, measured along the normal of the nearest side face
            var u: Float
            var ca = 1f
            var cb = 0f
            val fc = q.faceCos
            if (fc.isEmpty()) {
                u = sqrt(a * a + b * b)
                if (u > 1e-4f) {
                    ca = a / u; cb = b / u
                }
            } else {
                val fs = q.faceSin
                u = -Float.MAX_VALUE
                for (k in fc.indices) {
                    val t = a * fc[k] + b * fs[k]
                    if (t > u) {
                        u = t; ca = fc[k]; cb = fs[k]
                    }
                }
            }
            // signed distance to the convex (u, h) profile
            var sMax = -Float.MAX_VALUE
            var su = 0f
            var sh = 0f
            for (e in 0 until edges) {
                val s = (u - q.pu[e]) * q.nu[e] + (h - q.ph[e]) * q.nh[e]
                if (s > sMax) {
                    sMax = s; su = q.nu[e]; sh = q.nh[e]
                }
            }
            var d2 = sMax
            if (sMax > 0f) {
                // outside: nearest point on the edges that face the point
                var best = Float.MAX_VALUE
                for (e in 0 until edges) {
                    val ru = u - q.pu[e]
                    val rh = h - q.ph[e]
                    if (ru * q.nu[e] + rh * q.nh[e] <= 0f) continue
                    var t = (ru * q.eu[e] + rh * q.eh[e]) * q.einv[e]
                    if (t < 0f) t = 0f else if (t > 1f) t = 1f
                    val wu = ru - q.eu[e] * t
                    val wh = rh - q.eh[e] * t
                    val dd = wu * wu + wh * wh
                    if (dd < best) {
                        best = dd; su = wu; sh = wh
                    }
                }
                d2 = sqrt(best)
                if (d2 > 1e-5f) {
                    su /= d2; sh /= d2
                }
            }
            // back to 3D: radial direction of the chosen face, plus the axis
            val sx = su * (ca * q.ux + cb * q.vx) + sh * q.wx
            val sy = su * (ca * q.uy + cb * q.vy) + sh * q.wy
            val sz = su * (ca * q.uz + cb * q.vz) + sh * q.wz
            if (d2 > 0f) {
                out2 += d2 * d2
                gx += sx * d2; gy += sy * d2; gz += sz * d2
            } else {
                inner = d2
                ix = sx; iy = sy; iz = sz
            }
        }
        val pl = q.planes
        var i = 0
        while (i < pl.size) {
            val s = pl[i] * px + pl[i + 1] * py + pl[i + 2] * pz - pl[i + 3]
            if (s > 0f) {
                out2 += s * s
                gx += pl[i] * s; gy += pl[i + 1] * s; gz += pl[i + 2] * s
            } else if (s > inner) {
                inner = s
                ix = pl[i]; iy = pl[i + 1]; iz = pl[i + 2]
            }
            i += 4
        }
        if (out2 > 0f) {
            val g = sqrt(gx * gx + gy * gy + gz * gz)
            if (g > 1e-9f) {
                nx = gx / g; ny = gy / g; nz = gz / g
            } else {
                nx = 0f; ny = 1f; nz = 0f
            }
            return sqrt(out2)
        }
        nx = ix; ny = iy; nz = iz
        return inner
    }

    /**
     * One collision proxy (station-local, immutable after the build). An optional N-sided solid of
     * revolution (frame o; u, v radial; w axis; N = 0 for round) given by a convex profile in
     * (u = distance along the side-face normal, h = height), intersected with half-spaces
     * n·p <= c ([planes]: nx, ny, nz, c). bx..br is its bounding sphere.
     */
    internal class Proxy(@JvmField val label: String) {
        @JvmField var bx = 0f
        @JvmField var by = 0f
        @JvmField var bz = 0f
        @JvmField var br = 0f
        @JvmField var ox = 0f
        @JvmField var oy = 0f
        @JvmField var oz = 0f
        @JvmField var ux = 1f
        @JvmField var uy = 0f
        @JvmField var uz = 0f
        @JvmField var vx = 0f
        @JvmField var vy = 1f
        @JvmField var vz = 0f
        @JvmField var wx = 0f
        @JvmField var wy = 0f
        @JvmField var wz = 1f
        @JvmField var faceCos = EMPTY
        @JvmField var faceSin = EMPTY

        // profile edges: start point, edge vector, 1 / length², outward unit normal
        @JvmField var pu = EMPTY
        @JvmField var ph = EMPTY
        @JvmField var eu = EMPTY
        @JvmField var eh = EMPTY
        @JvmField var einv = EMPTY
        @JvmField var nu = EMPTY
        @JvmField var nh = EMPTY
        @JvmField var planes = EMPTY
    }

    companion object {
        /** Distance (m) from the docking port to the auto-dock stand-off point; Game's approach uses the same. */
        const val DOCK_STANDOFF = 300.0

        /** [collide] does nothing beyond the station radius + this. */
        const val COLLIDE_RANGE = 50.0
        private const val CLEARANCE_RANGE = 120.0

        /** Routing keep-out: bounding sphere + this ... */
        private const val KEEP_OUT_MARGIN = 50.0

        /** ... behind the plane this far in front of [FRONT_EXTENT]. */
        private const val FRONT_MARGIN = 40f

        /** Detour waypoints sit this far beyond the bounding radius from the station centre or dock axis. */
        private const val DETOUR_MARGIN = 150.0

        /** Clearance (m, beyond the ship radius) an escape line from inside the keep-out must keep. */
        private const val ESCAPE_CLEAR = 15f
        private const val ESCAPE_DIRECTIONS = 5
        private const val MAX_TRACE_STEPS = 96
        private const val TRACE_CAP = 400f

        /** How far each model's hull reaches towards its docking approach (-Z), in metres. */
        internal val FRONT_EXTENT = floatArrayOf(351f, 157f)

        private val EMPTY = FloatArray(0)
        private val O = Vec3(0f, 0f, 0f)
        private val X = Vec3(1f, 0f, 0f)
        private val NX = Vec3(-1f, 0f, 0f)
        private val Y = Vec3(0f, 1f, 0f)
        private val Z = Vec3(0f, 0f, 1f)

        /** Proxies per model (declared after the constants their builders use: companion init runs in order). */
        internal val PROXIES: Array<Array<Proxy>> = arrayOf(ringStation(), outpost())

        // ============================================================ model 0: ring station

        private fun ringStation(): Array<Proxy> {
            val l = ArrayList<Proxy>()
            val deg = (PI / 180.0).toFloat()
            // habitat ring (96 segments: treated as round, at most 0.5 m proud of the faces)
            val ring = floatArrayOf(60f, -24f, 60f, 24f, 38f, 46f, -36f, 46f, -50f, 28f, -50f, -28f, -36f, -46f, 38f, -46f)
            l += revolve("habitat ring", O, X, Y, Z, ringProfile(ring, 1f), 0, 0f)
            for (q in 0 until 4) {
                // spoke junction blocks and rim ribs: wider sections over a few degrees
                val spoke = (PI / 4 + q * PI / 2).toFloat()
                l += sector(revolve("junction $q", O, X, Y, Z, ringProfile(ring, 1.13f), 0, 0f), spoke - 3.2f * deg, spoke + 3.2f * deg)
                val rib = (q * PI / 2).toFloat()
                l += sector(revolve("rib $q", O, X, Y, Z, ringProfile(ring, 1.07f), 0, 0f), rib - 1.3f * deg, rib + 1.3f * deg)
                val c = cos(rib)
                val s = sin(rib)
                l += obox("rim beacon $q", c * 908f, s * 908f, 0f, c, s, 0f, -s, c, 0f, 5f, 5f, 5f)
            }
            // hub collar, spokes with conduits and transit cars
            val oct = (PI / 8).toFloat()
            l += revolve("hub", O, X, Y, Z, floatArrayOf(0f, -40f, 130f, -40f, 150f, -24f, 150f, 24f, 130f, 40f, 0f, 40f), 8, oct)
            for (q in 0 until 4) {
                val a = (PI / 4 + q * PI / 2).toFloat()
                l += radialBox("spoke $q", a, 125f, 800f, 15f, -12f, 12f)
                l += radialBox("conduit $q", a, 140f, 795f, 5f, 11f, 21f)
                l += radialBox("transit car $q", a, 420f, 470f, 22f, -18f, 18f)
            }
            // dock head: the octagonal head minus the slot tunnel, as five pieces around it
            dockHead(l, StationMesh.RING_STATION, floatArrayOf(0f, -340f, 150f, -340f, 172f, -326f, 172f, -200f, 0f, -200f), 8, oct, 5f)
            val top = 172f * cos(oct) // apothem of the head
            l += box("control tower", -36f, top - 9f, -305f, 36f, top + 23f, -235f)
            l += box("sensor block", -24f, -top - 19f, -290f, 24f, -top + 9f, -250f)
            for (sx in floatArrayOf(1f, -1f)) {
                l += mirrorBox("head equipment", sx, top - 9f, -22f, -300f, top + 17f, 22f, -230f)
                l += mirrorBox("head equipment trim", sx, top + 16f, -14f, -290f, top + 20f, 14f, -270f)
            }
            // spindle, tanks with straps, radiator fins, rear module
            l += revolve("spindle", O, X, Y, Z, floatArrayOf(0f, -205f, 70f, -205f, 70f, 300f, 0f, 300f), 8, oct)
            val tank = floatArrayOf(0f, -185f, 20f, -182f, 30f, -170f, 30f, -75f, 20f, -63f, 0f, -60f)
            for (q in 0 until 4) {
                val a = (q * PI / 2).toFloat()
                l += revolve("tank $q", Vec3(cos(a) * 110f, sin(a) * 110f, 0f), X, Y, Z, tank, 8, oct)
                l += radialBox("tank strap $q", a, 62f, 86f, 6f, -128f, -112f)
            }
            for (a in floatArrayOf((PI / 2).toFloat(), (-PI / 2).toFloat())) {
                l += radialBox("radiator fin", a, 62f, 300f, 3f, 130f, 285f)
                l += radialBox("radiator edge", a, 296f, 304f, 4f, 125f, 290f)
            }
            l += revolve("rear module", O, X, Y, Z, floatArrayOf(0f, 290f, 92f, 290f, 112f, 308f, 112f, 372f, 86f, 392f, 0f, 392f), 8, oct)
            // antenna mast, cross bars, beacon
            l += box("mast", -4f, -4f, 385f, 4f, 4f, 650f)
            l += box("mast bar 1", -28f, -2.5f, 467.5f, 28f, 2.5f, 472.5f)
            l += box("mast bar 2", -2.5f, -28f, 512.5f, 2.5f, 28f, 517.5f)
            l += box("mast bar 3", -20f, -2.5f, 557.5f, 20f, 2.5f, 562.5f)
            l += box("mast beacon", -7f, -7f, 645f, 7f, 7f, 659f)
            // solar wings: boom with its tip light (1 m proud of the boom), three panels each
            for (sx in floatArrayOf(1f, -1f)) {
                l += mirrorBox("solar boom", sx, 100f, -7f, 333f, 622f, 7f, 347f)
                for (x0 in floatArrayOf(150f, 306f, 462f)) l += mirrorBox("solar panel", sx, x0, -62f, 338f, x0 + 142f, 62f, 342f)
            }
            return l.toTypedArray()
        }

        // ============================================================ model 1: outpost

        private fun outpost(): Array<Proxy> {
            val l = ArrayList<Proxy>()
            val oct = (PI / 8).toFloat()
            l += revolve("core tower", O, X, Z, Y, floatArrayOf(0f, -110f, 28f, -110f, 42f, -94f, 42f, 94f, 28f, 110f, 0f, 110f), 8, oct)
            // docking bay: 96 x 60 m box from the slot face back into the tower, minus the slot tunnel
            dockBay(l)
            val hab = floatArrayOf(0f, 25f, 22f, 25f, 22f, 136f, 14f, 150f, 0f, 154f)
            l += revolve("rear habitat", O, X, Y, Z, hab, 8, oct)
            l += revolve("left habitat", O, Z, Y, NX, hab, 8, oct)
            val sphere = floatArrayOf(0f, -18f, 12.7f, -12.7f, 18f, 0f, 12.7f, 12.7f, 0f, 18f)
            for (x in floatArrayOf(-80f, -122f)) {
                l += revolve("tank", Vec3(x, -44f, 0f), X, Z, Y, sphere, 6, 0f)
                l += box("tank strut", x - 4f, -30f, -4f, x + 4f, -14f, 4f)
            }
            // landing pad on its truss arm
            l += revolve("landing pad", Vec3(195f, 0f, 0f), X, Z, Y, floatArrayOf(0f, -12f, 38f, -12f, 64f, -4f, 64f, 4f, 0f, 4f), 8, oct)
            l += box("pad truss", 30f, -10f, -7f, 160f, 2f, 7f)
            l += box("pad truss low", 30f, -24f, -4f, 150f, -14f, 4f)
            // radiator keel (spine and beacon; fins with their edges, up to 2 m proud)
            l += box("keel", -5f, -212f, -5f, 5f, -100f, 5f)
            l += box("keel fin +z", -3f, -194f, 3f, 3f, -126f, 122f)
            l += box("keel fin -z", -3f, -194f, -122f, 3f, -126f, -3f)
            // comms dish on its mast: oblique axis, built exactly like the mesh's
            l += box("dish mast", -3f, 100f, -3f, 3f, 152f, 3f)
            val w = Vec3(0.35f, 0.75f, -0.55f).normalize()
            val u = Vec3().cross(w, Y).normalize()
            val v = Vec3().cross(w, u).normalize()
            val o = Vec3(0f, 150f, 0f).addScaled(w, 8f)
            // convex hull of the dish profile (the concave inner face is 1 m deep)
            l += revolve("dish", o, u, v, w, floatArrayOf(0f, -8f, 14f, -6.5f, 30f, 0f, 0f, -4f), 12, 0f)
            val f = Vec3(o.x, o.y, o.z).addScaled(w, 6.5f)
            l += obox("dish feed", f.x, f.y, f.z, w.x, w.y, w.z, u.x, u.y, u.z, 11f, 1.2f, 1.2f)
            l += box("antenna whip", 18f, 95f, 18f, 22f, 186f, 22f)
            l += box("whip beacon", 16f, 184f, 16f, 24f, 192f, 24f)
            // greebles on the tower and cargo on the left module
            l += box("tower top box", -16f, 106f, -22f, 4f, 117f, -6f)
            l += box("tower side box", 34f, -70f, -14f, 48f, -40f, 14f)
            l += box("tower rear box", -14f, 40f, 34f, 14f, 70f, 46f)
            l += box("cargo 1", -130f, 19f, -12f, -101f, 33f, 12f)
            l += box("cargo 2", -97f, 19f, -10f, -70f, 31f, 10f)
            for (sx in floatArrayOf(1f, -1f)) {
                l += mirrorBox("solar boom", sx, 15f, -3f, 118f, 121f, 3f, 126f)
                for (x0 in floatArrayOf(32f, 77f)) l += mirrorBox("solar panel", sx, x0, -20f, 121f, x0 + 42f, 20f, 123f)
            }
            return l.toTypedArray()
        }

        // ============================================================ shared parts

        /**
         * Ring station dock head: the solid of revolution [prof] cut around the slot tunnel (from
         * [StationMesh.slotSize]) into top, bottom, left, right and back pieces, plus the slot's lit
         * frame bars and corner light housings ([t] = trim thickness, as in the mesh).
         */
        private fun dockHead(l: ArrayList<Proxy>, model: Int, prof: FloatArray, sides: Int, a0: Float, t: Float) {
            val port = Vec3()
            val dir = Vec3()
            StationMesh.dockPort(model, port, dir)
            val slot = StationMesh.slotSize(model, Vec3())
            val zf = port.z
            val back = zf + slot.z
            l += clip(revolve("dock head top", O, X, Y, Z, prof, sides, a0), 0f, -1f, 0f, -slot.y)
            l += clip(revolve("dock head bottom", O, X, Y, Z, prof, sides, a0), 0f, 1f, 0f, -slot.y)
            l += clip(revolve("dock head right", O, X, Y, Z, prof, sides, a0), -1f, 0f, 0f, -slot.x)
            l += clip(revolve("dock head left", O, X, Y, Z, prof, sides, a0), 1f, 0f, 0f, -slot.x)
            l += clip(revolve("dock head back", O, X, Y, Z, prof, sides, a0), 0f, 0f, -1f, -back)
            slotTrim(l, zf, slot.x, slot.y, t)
        }

        /** Outpost docking bay: box x ±48, y ±30 from the slot face back to z = -28, minus the tunnel. */
        private fun dockBay(l: ArrayList<Proxy>) {
            val port = Vec3()
            val dir = Vec3()
            StationMesh.dockPort(StationMesh.OUTPOST, port, dir)
            val slot = StationMesh.slotSize(StationMesh.OUTPOST, Vec3())
            val zf = port.z
            val zb = -28f
            val hw = 48f
            val hh = 30f
            l += box("bay top", -hw, slot.y, zf, hw, hh, zb)
            l += box("bay bottom", -hw, -hh, zf, hw, -slot.y, zb)
            l += box("bay right", slot.x, -hh, zf, hw, hh, zb)
            l += box("bay left", -hw, -hh, zf, -slot.x, hh, zb)
            l += box("bay back", -hw, -hh, zf + slot.z, hw, hh, zb)
            slotTrim(l, zf, slot.x, slot.y, 3f)
        }

        /**
         * The lit frame bars, corner housings and hazard outline proud of a slot face at z = [zf]
         * (see StationMesh.slotTrim).
         */
        private fun slotTrim(l: ArrayList<Proxy>, zf: Float, sw: Float, sh: Float, t: Float) {
            val o = 0.12f * t
            l += box("slot frame top", -sw - t, sh - o, zf - t, sw + t, sh + t, zf)
            l += box("slot frame bottom", -sw - t, -sh - t, zf - t, sw + t, -sh + o, zf)
            val gx = sw + t * 2.6f
            val gy = sh + t * 2.6f
            val g = t * 1.2f
            val p = t * 0.5f
            l += box("slot hazard top", -gx - g, gy, zf - p, gx + g, gy + g, zf)
            l += box("slot hazard bottom", -gx - g, -gy - g, zf - p, gx + g, -gy, zf)
            for (sx in floatArrayOf(1f, -1f)) {
                l += mirrorBox("slot hazard side", sx, gx, -gy - 0.1f * g, zf - 0.9f * p, gx + 0.92f * g, gy + 0.1f * g, zf)
                l += mirrorBox("slot frame side", sx, sw - o, -sh, zf - 0.9f * t, sw + 0.8f * t, sh, zf)
                for (sy in floatArrayOf(1f, -1f)) {
                    val h = 0.72f * t
                    val cx = sx * (sw + t)
                    val cy = sy * (sh + t)
                    val cz = zf - 1.45f * t
                    l += box("slot light", cx - h, cy - h, cz - h, cx + h, cy + h, cz + h)
                }
            }
        }

        /** The habitat ring cross-section ([sec]: radial offset, z) scaled by [s] about the tube centre. */
        private fun ringProfile(sec: FloatArray, s: Float) = FloatArray(sec.size) {
            if (it % 2 == 0) 840f + sec[it] * s else sec[it] * s
        }

        /** Axis-aligned box from its min / max corners. */
        private fun box(label: String, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Proxy =
            obox(label, (x0 + x1) * 0.5f, (y0 + y1) * 0.5f, (z0 + z1) * 0.5f, 1f, 0f, 0f, 0f, 1f, 0f, (x1 - x0) * 0.5f, (y1 - y0) * 0.5f, (z1 - z0) * 0.5f)

        /** [box] mirrored to -X when [sx] < 0 (the mesh's mirrored halves). */
        private fun mirrorBox(label: String, sx: Float, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Proxy =
            if (sx > 0f) box(label, x0, y0, z0, x1, y1, z1) else box("$label (-x)", -x1, y0, z0, -x0, y1, z1)

        /** Box around (cx, cy, cz) spanned by unit axes u, v and u x v, with half sizes hu, hv, hw. */
        private fun obox(
            label: String, cx: Float, cy: Float, cz: Float,
            ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
            hu: Float, hv: Float, hw: Float,
        ): Proxy {
            val wx = uy * vz - uz * vy
            val wy = uz * vx - ux * vz
            val wz = ux * vy - uy * vx
            val p = Proxy(label)
            val pl = FloatArray(24)
            var i = 0
            fun plane(nx: Float, ny: Float, nz: Float, half: Float) {
                pl[i] = nx; pl[i + 1] = ny; pl[i + 2] = nz
                pl[i + 3] = nx * cx + ny * cy + nz * cz + half
                i += 4
            }
            plane(ux, uy, uz, hu); plane(-ux, -uy, -uz, hu)
            plane(vx, vy, vz, hv); plane(-vx, -vy, -vz, hv)
            plane(wx, wy, wz, hw); plane(-wx, -wy, -wz, hw)
            p.planes = pl
            p.bx = cx; p.by = cy; p.bz = cz
            p.br = sqrt(hu * hu + hv * hv + hw * hw)
            return p
        }

        /** Box along the radial direction at angle [a] about Z (same parameters as the mesh's radialBox). */
        private fun radialBox(label: String, a: Float, r0: Float, r1: Float, halfT: Float, z0: Float, z1: Float): Proxy {
            val c = cos(a)
            val s = sin(a)
            val rm = (r0 + r1) * 0.5f
            return obox(label, c * rm, s * rm, (z0 + z1) * 0.5f, c, s, 0f, -s, c, 0f, (r1 - r0) * 0.5f, halfT, (z1 - z0) * 0.5f)
        }

        /**
         * Solid of revolution about the axis through [o] along [w] ([u], [v] radial, as in
         * StationMesh.revolve): [prof] = convex (radius, height) loop, where radius is the
         * circumradius of the [sides]-gon whose corners sit at [a0] + k·2π/sides (sides = 0: round).
         * Edges along the axis are not surfaces.
         */
        private fun revolve(label: String, o: Vec3, u: Vec3, v: Vec3, w: Vec3, prof: FloatArray, sides: Int, a0: Float): Proxy {
            val p = Proxy(label)
            p.ox = o.x; p.oy = o.y; p.oz = o.z
            p.ux = u.x; p.uy = u.y; p.uz = u.z
            p.vx = v.x; p.vy = v.y; p.vz = v.z
            p.wx = w.x; p.wy = w.y; p.wz = w.z
            // side faces of an N-gon sit at the apothem, midway between the corners
            val apo = if (sides > 0) cos(PI / sides).toFloat() else 1f
            if (sides > 0) {
                p.faceCos = FloatArray(sides) { cos(a0 + (it + 0.5f) * 2f * PI.toFloat() / sides) }
                p.faceSin = FloatArray(sides) { sin(a0 + (it + 0.5f) * 2f * PI.toFloat() / sides) }
            }
            val n = prof.size / 2
            var cu = 0f
            var ch = 0f
            for (i in 0 until n) {
                cu += prof[2 * i] * apo; ch += prof[2 * i + 1]
            }
            cu /= n; ch /= n
            val pu = ArrayList<Float>()
            val ph = ArrayList<Float>()
            val eu = ArrayList<Float>()
            val eh = ArrayList<Float>()
            val nu = ArrayList<Float>()
            val nh = ArrayList<Float>()
            for (i in 0 until n) {
                val j = (i + 1) % n
                val u0 = prof[2 * i] * apo
                val h0 = prof[2 * i + 1]
                val u1 = prof[2 * j] * apo
                val h1 = prof[2 * j + 1]
                if (u0 == 0f && u1 == 0f) continue // on the axis
                val du = u1 - u0
                val dh = h1 - h0
                val len = sqrt(du * du + dh * dh)
                if (len < 1e-4f) continue
                var mu = dh / len
                var mh = -du / len
                if (mu * ((u0 + u1) * 0.5f - cu) + mh * ((h0 + h1) * 0.5f - ch) < 0f) {
                    mu = -mu; mh = -mh
                }
                // max-over-faces folding assumes faces that look away from the axis; one tilted slightly
                // towards it (the dish's shallow cone) is off by at most |mu| * r * (1 - cos(pi / N))
                require(sides == 0 || mu >= -0.25f) { "$label: profile edge $i faces the axis" }
                pu += u0; ph += h0; eu += du; eh += dh; nu += mu; nh += mh
            }
            for (e in pu.indices) for (i in 0 until n) {
                val s = (prof[2 * i] * apo - pu[e]) * nu[e] + (prof[2 * i + 1] - ph[e]) * nh[e]
                require(s < 1e-3f) { "$label: profile is not convex at point $i" }
            }
            p.pu = pu.toFloatArray(); p.ph = ph.toFloatArray()
            p.eu = eu.toFloatArray(); p.eh = eh.toFloatArray()
            p.nu = nu.toFloatArray(); p.nh = nh.toFloatArray()
            p.einv = FloatArray(p.eu.size) { 1f / (p.eu[it] * p.eu[it] + p.eh[it] * p.eh[it]) }
            // bounding sphere: centred on the axis, mid-height
            var hMin = Float.MAX_VALUE
            var hMax = -Float.MAX_VALUE
            for (i in 0 until n) {
                hMin = minOf(hMin, prof[2 * i + 1]); hMax = maxOf(hMax, prof[2 * i + 1])
            }
            val hc = (hMin + hMax) * 0.5f
            var r2 = 0f
            for (i in 0 until n) {
                val dh = prof[2 * i + 1] - hc
                r2 = maxOf(r2, prof[2 * i] * prof[2 * i] + dh * dh)
            }
            p.bx = o.x + w.x * hc; p.by = o.y + w.y * hc; p.bz = o.z + w.z * hc
            p.br = sqrt(r2)
            return p
        }

        /** Adds the half-space nx·x + ny·y + nz·z <= c to [p]. */
        private fun clip(p: Proxy, nx: Float, ny: Float, nz: Float, c: Float): Proxy {
            val old = p.planes
            val pl = old.copyOf(old.size + 4)
            pl[old.size] = nx; pl[old.size + 1] = ny; pl[old.size + 2] = nz; pl[old.size + 3] = c
            p.planes = pl
            return p
        }

        /**
         * Limits a round solid of revolution about Z (through the origin) to the wedge between angles
         * [t0] and [t1] (end faces through the axis), and shrinks its bounding sphere to the wedge.
         */
        private fun sector(p: Proxy, t0: Float, t1: Float): Proxy {
            clip(p, sin(t0), -cos(t0), 0f, 0f)
            clip(p, -sin(t1), cos(t1), 0f, 0f)
            var r0 = Float.MAX_VALUE
            var r1 = 0f
            var h0 = Float.MAX_VALUE
            var h1 = -Float.MAX_VALUE
            for (e in p.pu.indices) {
                r0 = minOf(r0, p.pu[e]); r1 = maxOf(r1, p.pu[e])
                h0 = minOf(h0, p.ph[e]); h1 = maxOf(h1, p.ph[e])
            }
            // the wedge is narrow: its farthest points from the mid-angle centre are its corners
            val tm = (t0 + t1) * 0.5f
            val rc = (r0 + r1) * 0.5f
            p.bx = cos(tm) * rc; p.by = sin(tm) * rc; p.bz = (h0 + h1) * 0.5f
            var b2 = 0f
            for (t in floatArrayOf(t0, tm, t1)) for (rr in floatArrayOf(r0, r1)) for (hh in floatArrayOf(h0, h1)) {
                val dx = cos(t) * rr - p.bx
                val dy = sin(t) * rr - p.by
                val dz = hh - p.bz
                b2 = maxOf(b2, dx * dx + dy * dy + dz * dz)
            }
            p.br = sqrt(b2)
            return p
        }

    }
}
