package com.starwake.core.game

import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class StationCollisionTest {
    private val system = StarSystem()

    /** One station per model, with non-trivial orientations and real (1e9 m) coordinates. */
    private val stations = Array(StationMesh.MODELS) { m -> system.stations.first { it.model == m } }
    private val fields = FIELDS
    private val flight = FlightModel(ShipSpec.STANDARD)
    private val sc = StationCollision(flight)
    private val r = ShipSpec.STANDARD.collisionRadius

    // ------------------------------------------------------------------ helpers

    private fun toWorld(st: StarSystem.Station, x: Float, y: Float, z: Float, out: DVec3): DVec3 {
        val w = st.rot.rotate(x, y, z, Vec3())
        return out.set(st.pos.x + w.x, st.pos.y + w.y, st.pos.z + w.z)
    }

    private fun toLocal(st: StarSystem.Station, p: DVec3, out: Vec3): Vec3 =
        st.rot.rotateInverse((p.x - st.pos.x).toFloat(), (p.y - st.pos.y).toFloat(), (p.z - st.pos.z).toFloat(), out)

    // ------------------------------------------------------------------ (a) proxies follow the hull

    @Test
    fun proxiesCoverEveryHullTriangle() {
        val rng = Rng(7)
        for (m in 0 until StationMesh.MODELS) {
            val f = fields[m]
            var worst = -Float.MAX_VALUE
            var worstAt = ""
            var samples = 0
            for (t in 0 until f.triangles) {
                val ax = f.v(t, 0, 0); val ay = f.v(t, 0, 1); val az = f.v(t, 0, 2)
                val e1x = f.v(t, 1, 0) - ax; val e1y = f.v(t, 1, 1) - ay; val e1z = f.v(t, 1, 2) - az
                val e2x = f.v(t, 2, 0) - ax; val e2y = f.v(t, 2, 1) - ay; val e2z = f.v(t, 2, 2) - az
                val cx = e1y * e2z - e1z * e2y; val cy = e1z * e2x - e1x * e2z; val cz = e1x * e2y - e1y * e2x
                val area = 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
                // corners, edge midpoints, centroid, plus random points (one per ~50 m²)
                val n = 7 + (area / 50.0).toInt()
                for (i in 0 until n) {
                    var u: Double
                    var v: Double
                    when (i) {
                        0 -> { u = 0.0; v = 0.0 }
                        1 -> { u = 1.0; v = 0.0 }
                        2 -> { u = 0.0; v = 1.0 }
                        3 -> { u = 0.5; v = 0.0 }
                        4 -> { u = 0.0; v = 0.5 }
                        5 -> { u = 0.5; v = 0.5 }
                        6 -> { u = 1.0 / 3; v = 1.0 / 3 }
                        else -> {
                            u = rng.nextFloat().toDouble(); v = rng.nextFloat().toDouble()
                            if (u + v > 1.0) { u = 1.0 - u; v = 1.0 - v }
                        }
                    }
                    val x = (ax + e1x * u + e2x * v).toFloat()
                    val y = (ay + e1y * u + e2y * v).toFloat()
                    val z = (az + e1z * u + e2z * v).toFloat()
                    val d = sc.localDistance(m, x, y, z)
                    samples++
                    if (d > worst) {
                        worst = d
                        worstAt = "($x, $y, $z)"
                    }
                }
            }
            println("model $m: $samples hull samples, furthest outside the proxies: %.2f m at $worstAt".format(worst))
            assertTrue(worst <= HULL_TOLERANCE, "model $m: hull point $worstAt is %.2f m outside the proxies".format(worst))
        }
    }

    @Test
    fun proxyVolumeIsHull() {
        val rng = Rng(11)
        for (m in 0 until StationMesh.MODELS) {
            val f = fields[m]
            val set = StationCollision.PROXIES[m]
            var total = 0
            var empty = 0
            for ((pi, q) in set.withIndex()) {
                var n = 0
                var bad = 0
                var tries = 0
                while (n < PER_PROXY && tries < 200_000) {
                    tries++
                    val x = q.bx + (rng.nextFloat() * 2f - 1f) * q.br
                    val y = q.by + (rng.nextFloat() * 2f - 1f) * q.br
                    val z = q.bz + (rng.nextFloat() * 2f - 1f) * q.br
                    if (sc.proxyDistance(m, pi, x, y, z) >= 0f) continue
                    n++
                    val px = x.toDouble(); val py = y.toDouble(); val pz = z.toDouble()
                    if (f.distance(px, py, pz, VOLUME_TOLERANCE) < VOLUME_TOLERANCE) continue
                    if (f.inside(px, py, pz)) continue
                    bad++
                }
                total += n
                empty += bad
                assertTrue(n >= PER_PROXY / 2, "model $m proxy ${q.label}: could not sample its inside")
                // every proxy is mostly hull (thin ones are all within the tolerance of a surface)
                if (bad > 0) println("  model $m proxy ${q.label}: $bad of $n inside points are > $VOLUME_TOLERANCE m from the hull")
                assertTrue(bad <= n / 10, "model $m proxy ${q.label}: $bad of $n inside points are > $VOLUME_TOLERANCE m from the hull")
            }
            println("model $m: ${set.size} proxies, $empty of $total proxy-interior samples are empty space")
            assertTrue(empty <= total / 50, "model $m: $empty of $total proxy-interior samples are empty space")
        }
    }

    @Test
    fun knownEmptySpaceIsOpen() {
        // points where a ship fits in the real station: the ring's hole between the spokes, the slot
        // tunnel, in front of the slot, between solar panels, beside the mast, under the outpost pad
        val ring = listOf(
            floatArrayOf(0f, 500f, 0f), floatArrayOf(500f, 0f, 0f), floatArrayOf(-300f, -300f * 0.2f, 0f),
            floatArrayOf(0f, 300f, -60f), floatArrayOf(0f, 700f, 0f), floatArrayOf(0f, 0f, -300f),
            floatArrayOf(0f, 0f, -285f), floatArrayOf(0f, 0f, -360f), floatArrayOf(299f, 0f, 320f),
            floatArrayOf(0f, 40f, 500f), floatArrayOf(400f, 0f, 200f), floatArrayOf(0f, 200f, -150f),
        )
        val outpost = listOf(
            floatArrayOf(0f, 0f, -125f), floatArrayOf(0f, 0f, -170f), floatArrayOf(195f, -40f, 0f),
            floatArrayOf(100f, 60f, 0f), floatArrayOf(-60f, 60f, 60f),
            floatArrayOf(-101f, -80f, 0f), floatArrayOf(0f, 60f, 150f), floatArrayOf(30f, -150f, -40f),
        )
        for ((m, pts) in listOf(0 to ring, 1 to outpost)) for (p in pts) {
            val d = sc.localDistance(m, p[0], p[1], p[2])
            assertTrue(d > r, "model $m: a ship at (${p[0]}, ${p[1]}, ${p[2]}) should fit, but the proxies are %.1f m away".format(d))
            val x = p[0].toDouble()
            val y = p[1].toDouble()
            val z = p[2].toDouble()
            assertTrue(fields[m].distance(x, y, z, 100.0) > r && !fields[m].inside(x, y, z), "test point (${p.joinToString()}) is not free space")
        }
        // and solid points are solid for both: hub, ring tube, dock head beside the slot, spindle, mast
        // beacon; outpost tower, bay wall, pad, tank
        val solidRing = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 845f, 0f), floatArrayOf(100f, 0f, -260f), floatArrayOf(0f, 0f, 200f), floatArrayOf(0f, 0f, 652f))
        val solidOutpost = listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(37f, 0f, -120f), floatArrayOf(195f, -2f, 0f), floatArrayOf(-80f, -44f, 0f))
        for ((m, pts) in listOf(0 to solidRing, 1 to solidOutpost)) for (p in pts) {
            assertTrue(sc.localDistance(m, p[0], p[1], p[2]) < 0f, "model $m: (${p.joinToString()}) should be inside a proxy")
            assertTrue(fields[m].inside(p[0].toDouble(), p[1].toDouble(), p[2].toDouble()), "model $m: (${p.joinToString()}) should be inside the mesh")
        }
    }

    @Test
    fun convexProxiesNeverComeCloserAlongALineHeadingAway() {
        // the escape trace skips a convex proxy once the line is clear of it and not heading into it
        // (StationCollision.traceDistance): check that on every proxy flagged convex, and that the
        // flag is off exactly for the habitat ring pieces and the dish
        val rng = Rng(19)
        val e = 0.05f
        for (m in 0 until StationMesh.MODELS) for ((i, q) in StationCollision.PROXIES[m].withIndex()) {
            val ring = q.label.startsWith("habitat ring") || q.label.startsWith("junction") || q.label.startsWith("rib ") || q.label == "dish"
            assertEquals(!ring, q.convex, "model $m proxy ${q.label}: convex flag")
            if (!q.convex) continue
            var lines = 0
            while (lines < 60) {
                val x = q.bx + (rng.nextFloat() * 2f - 1f) * q.br * 1.5f
                val y = q.by + (rng.nextFloat() * 2f - 1f) * q.br * 1.5f
                val z = q.bz + (rng.nextFloat() * 2f - 1f) * q.br * 1.5f
                val d0 = sc.proxyDistance(m, i, x, y, z)
                if (d0 < 0.5f) continue
                // the outward normal by central differences, and a random direction on its side
                val gx = sc.proxyDistance(m, i, x + e, y, z) - sc.proxyDistance(m, i, x - e, y, z)
                val gy = sc.proxyDistance(m, i, x, y + e, z) - sc.proxyDistance(m, i, x, y - e, z)
                val gz = sc.proxyDistance(m, i, x, y, z + e) - sc.proxyDistance(m, i, x, y, z - e)
                val dir = Vec3(rng.nextFloat() * 2f - 1f, rng.nextFloat() * 2f - 1f, rng.nextFloat() * 2f - 1f)
                if (dir.length() < 0.1f) continue
                dir.normalize()
                val dot = (dir.x * gx + dir.y * gy + dir.z * gz) / sqrt(gx * gx + gy * gy + gz * gz)
                if (kotlin.math.abs(dot) < 0.02f) continue // on a crease the sign is ambiguous
                if (dot < 0f) dir.scale(-1f)
                lines++
                var t = 0f
                while (t < q.br * 3f) {
                    t += q.br * 0.04f
                    val d = sc.proxyDistance(m, i, x + dir.x * t, y + dir.y * t, z + dir.z * t)
                    assertTrue(d >= d0 - 0.01f, "model $m proxy ${q.label}: from ($x, $y, $z) at %.2f m along $dir the distance fell from %.3f to %.3f".format(t, d0, d))
                }
            }
        }
    }

    @Test
    fun routerDetoursOnlyWhenTheLineIsBlocked() {
        val wp = DVec3()
        val p = DVec3()
        val lp = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            // in front of the hull, anywhere: straight to the stand-off
            for (x in floatArrayOf(-2000f, 0f, 2000f)) for (y in floatArrayOf(-2000f, 0f, 2000f)) {
                assertFalse(sc.dockWaypoint(st, toWorld(st, x, y, -(StationCollision.FRONT_EXTENT[m] + 45f), p), wp), "model $m: detour from the front")
            }
            // behind the station: a waypoint outside the bounding sphere, on the ship's side
            assertTrue(sc.dockWaypoint(st, toWorld(st, 0f, 300f, 1500f, p), wp))
            toLocal(st, wp, lp)
            assertTrue(lp.length() > st.radius, "model $m: waypoint $lp inside the bounding sphere")
            assertTrue(lp.y > 0f, "model $m: waypoint $lp on the far side")
        }
        // the hull's front: no mesh vertex reaches further towards the approach (-Z) than FRONT_EXTENT
        for (m in 0 until StationMesh.MODELS) {
            val f = fields[m]
            var front = -Float.MAX_VALUE
            for (t in 0 until f.triangles) for (v in 0 until 3) front = maxOf(front, -f.v(t, v, 2).toFloat())
            assertTrue(front <= StationCollision.FRONT_EXTENT[m], "model $m: hull reaches $front m forward")
            assertTrue(front > StationCollision.FRONT_EXTENT[m] - 1f, "model $m: FRONT_EXTENT is loose ($front)")
        }
    }

    // ------------------------------------------------------------------ (b) ships cannot fly through

    @Test
    fun shipsFlyingAtTheHullStayOutside() {
        val ctl = ControlInput()
        val s = ShipState()
        val lp = Vec3()
        val aim = Vec3()
        val wAim = Vec3()
        val up = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            val f = fields[m]
            val cases = flightCases(m)
            var hits = 0
            var minGap = Double.MAX_VALUE
            for (c in cases) {
                // c = target x, y, z, flight direction x, y, z (local)
                aim.set(c[3], c[4], c[5]).normalize()
                val back = st.radius.toFloat() + 150f + Vec3(c[0], c[1], c[2]).length()
                s.reset()
                toWorld(st, c[0] - aim.x * back, c[1] - aim.y * back, c[2] - aim.z * back, s.pos)
                st.rot.rotate(aim, wAim)
                st.rot.rotate(0f, 1f, 0f, up)
                if (kotlin.math.abs(wAim.dot(up)) > 0.95f) st.rot.rotate(1f, 0f, 0f, up)
                s.rot.lookRotation(wAim.x, wAim.y, wAim.z, up.x, up.y, up.z)
                s.vel.set(wAim).scale(100f)
                ctl.clearAxes()
                ctl.throttle = 100f / ShipSpec.STANDARD.maxSpeed
                var touched = false
                val h = 1f / 120f
                for (i in 0 until (20f / h).toInt()) {
                    flight.step(s, ctl, h, ShipSpec.STANDARD.maxSpeed)
                    if (sc.collide(s, st) > 0f) touched = true
                    toLocal(st, s.pos, lp)
                    val gap = f.distance(lp.x.toDouble(), lp.y.toDouble(), lp.z.toDouble(), 30.0)
                    if (gap < minGap) minGap = gap
                    if (gap < MIN_GAP) {
                        fail("model $m, flight at (${c.joinToString()}): after %.2f s the ship centre is %.2f m from the hull at $lp (ship radius $r m)".format(i * h, gap))
                    }
                }
                if (touched) hits++
            }
            println("model $m: ${cases.size} flights at 100 m/s, $hits hit the hull, closest ship centre to the mesh %.2f m".format(minGap))
            assertTrue(hits >= cases.size - 2, "model $m: only $hits of ${cases.size} flights reached the hull")
        }
    }

    /** Flight cases: target point and flight direction (local), from 14 directions at the centre plus parts. */
    private fun flightCases(m: Int): List<FloatArray> {
        val l = ArrayList<FloatArray>()
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            val n = x * x + y * y + z * z
            if (n == 1 || n == 3) l += floatArrayOf(0f, 0f, 0f, -x.toFloat(), -y.toFloat(), -z.toFloat())
        }
        if (m == StationMesh.RING_STATION) {
            l += floatArrayOf(0f, 845f, 0f, 0f, 0f, -1f) // ring from behind
            l += floatArrayOf(0f, -845f, 0f, 0f, 0f, 1f) // ring from the front
            l += floatArrayOf(0f, 845f, 0f, 0f, -1f, 0f) // ring from outside
            l += floatArrayOf(0f, 790f, 0f, 0f, 1f, 0f) // ring's inner face, from the hub
            l += floatArrayOf(330f, 330f, 0f, 0f, 0f, 1f) // spoke from the front
            l += floatArrayOf(380f, 0f, 340f, 0f, 0f, -1f) // solar panel from behind
            l += floatArrayOf(380f, 0f, 340f, 0.3f, 1f, 0f) // solar panel edge-on
            l += floatArrayOf(0f, 0f, 515f, 1f, 0.2f, 0f) // mast cross bars
            l += floatArrayOf(165f, 0f, -270f, -1f, 0f, 0f) // dock head side
            l += floatArrayOf(0f, 0f, -230f, 0f, 0f, 1f) // straight through the slot at the tunnel's back wall
            l += floatArrayOf(40f, 10f, -230f, 0.25f, 0.1f, 1f) // into the slot at an angle: tunnel walls
            l += floatArrayOf(52f, 22f, -340f, 0f, 0f, 1f) // slot frame corner
            l += floatArrayOf(110f, 0f, -120f, -1f, 0f, 0f) // tank
            l += floatArrayOf(0f, 290f, 200f, 0f, -1f, 0f) // radiator fin edge
        } else {
            l += floatArrayOf(195f, 4f, 0f, 0f, -1f, 0f) // landing pad from above
            l += floatArrayOf(10f, 160f, -10f, 0f, -1f, 0f) // comms dish from above
            l += floatArrayOf(0f, -160f, 60f, 1f, 0f, 0f) // keel fin
            l += floatArrayOf(0f, 0f, -100f, 0f, 0f, 1f) // straight through the slot at the tunnel's back wall
            l += floatArrayOf(20f, 5f, -100f, 0.3f, 0.1f, 1f) // into the slot at an angle
            l += floatArrayOf(-101f, 0f, 0f, 1f, 0f, 0f) // left habitat from the side
            l += floatArrayOf(60f, 0f, 122f, 0f, 1f, 0f) // solar panel edge-on
            l += floatArrayOf(20f, 150f, 20f, -1f, 0f, -1f) // antenna whip
        }
        return l
    }

    // ------------------------------------------------------------------ (c) auto-dock goes around

    /**
     * Same kinematics as Game.updateDocking with the router and its give-up rule (auto-dock ends
     * once [StationCollision.boxedIn] has held for [DOCK_BOXED_GRACE]), as in the integration notes.
     */
    private class DockRun(
        val docked: Boolean, val aborted: Boolean, val time: Float, val minGap: Double, val startGap: Double,
        val detourFrames: Int, val legs: Int, val at: String,
    )

    private fun simulateDock(m: Int, startX: Float, startY: Float, startZ: Float, maxTime: Float = 240f): DockRun {
        val st = stations[m]
        val f = fields[m]
        val portL = Vec3()
        val dirL = Vec3()
        StationMesh.dockPort(m, portL, dirL)
        val dockPos = toWorld(st, portL.x, portL.y, portL.z, DVec3())
        val dockDir = st.rot.rotate(dirL, Vec3())
        val park = StationMesh.slotSize(m, Vec3()).z * 0.5
        val pos = toWorld(st, startX, startY, startZ, DVec3())
        val wp = DVec3()
        val lp = Vec3()
        val dt = 1f / 60f
        var phase = 0
        val startGap = f.distance(startX.toDouble(), startY.toDouble(), startZ.toDouble(), 40.0)
        var minGap = Double.MAX_VALUE
        var detours = 0
        var boxedTime = 0f
        // distinct waypoints so far: the route must never come back to an earlier one
        val seen = ArrayList<DVec3>()
        var at = ""
        var frame = 0
        while (frame * dt < maxTime) {
            frame++
            val detour = phase == 0 && sc.dockWaypoint(st, pos, wp)
            if (detour) {
                detours++
                val last = seen.lastOrNull()
                if (last == null || dist(last, wp) > 1.0) {
                    for (i in 0 until seen.size - 1) {
                        assertTrue(dist(seen[i], wp) > 1.0, "model $m from ($startX, $startY, $startZ): the waypoint went back to an earlier one after %.1f s".format(frame * dt))
                    }
                    seen += DVec3().set(wp)
                }
            }
            if (detour && sc.boxedIn) {
                assertTrue(dist(wp, pos) < 1e-3, "model $m: a boxed-in waypoint must be the ship's own position")
                boxedTime += dt
                if (boxedTime > DOCK_BOXED_GRACE) {
                    toLocal(st, pos, lp)
                    return DockRun(false, true, frame * dt, minGap, startGap, detours, seen.size, "boxed in at $lp")
                }
            } else {
                boxedTime = 0f
            }
            val standOff = if (phase == 0) StationCollision.DOCK_STANDOFF else -park
            val tx = if (detour) wp.x else dockPos.x + dockDir.x * standOff
            val ty = if (detour) wp.y else dockPos.y + dockDir.y * standOff
            val tz = if (detour) wp.z else dockPos.z + dockDir.z * standOff
            val dx = tx - pos.x
            val dy = ty - pos.y
            val dz = tz - pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val speed = if (detour) 180f else if (phase == 0) minOf(180f, (d * 0.6).toFloat() + 5f) else minOf(30f, (d * 0.8).toFloat() + 8f)
            val ox = pos.x
            val oy = pos.y
            val oz = pos.z
            if (d > 0.5) {
                val k = minOf(1.0, speed * dt / d)
                pos.set(pos.x + dx * k, pos.y + dy * k, pos.z + dz * k)
            }
            // the swept path, sampled every metre
            val step = sqrt((pos.x - ox) * (pos.x - ox) + (pos.y - oy) * (pos.y - oy) + (pos.z - oz) * (pos.z - oz))
            val n = 1 + step.toInt()
            for (i in 1..n) {
                val t = i.toDouble() / n
                val sp = DVec3(ox + (pos.x - ox) * t, oy + (pos.y - oy) * t, oz + (pos.z - oz) * t)
                toLocal(st, sp, lp)
                val g = f.distance(lp.x.toDouble(), lp.y.toDouble(), lp.z.toDouble(), 40.0)
                if (g < minGap) {
                    minGap = g
                    at = "$lp at %.1f s".format(frame * dt)
                }
            }
            if (phase == 0 && !detour && d < 15.0) {
                phase = 1
            } else if (phase == 1 && d < 2.0) {
                return DockRun(true, false, frame * dt, minGap, startGap, detours, seen.size, at)
            }
        }
        toLocal(st, pos, lp)
        return DockRun(false, false, frame * dt, minGap, startGap, detours, seen.size, "stuck at $lp (phase $phase)")
    }

    /**
     * The path must keep the ship sphere off the real mesh: [r] from it, or, from a start already
     * closer (a ship wedged against the hull), never more than [GAP_SLACK] closer than it started.
     */
    private fun assertDocks(m: Int, name: String, x: Float, y: Float, z: Float, maxTime: Float = 240f): DockRun {
        val run = simulateDock(m, x, y, z, maxTime)
        println("model $m $name: docked ${run.docked} in %.1f s, %d detour frames, %d legs, closest hull approach %.1f m at ${run.at}".format(run.time, run.detourFrames, run.legs, run.minGap))
        assertTrue(run.docked, "model $m from $name ($x, $y, $z): not docked after ${run.time} s, ${run.at}")
        assertPathClear(m, name, x, y, z, run)
        return run
    }

    private fun assertPathClear(m: Int, name: String, x: Float, y: Float, z: Float, run: DockRun) {
        val floor = minOf(r.toDouble(), run.startGap) - GAP_SLACK
        assertTrue(run.minGap >= floor, "model $m from $name ($x, $y, $z): the path came within %.2f m of the hull (start %.2f m) at ${run.at}".format(run.minGap, run.startGap))
    }

    @Test
    fun autoDockRoutesAroundTheHullFromEverySide() {
        for (m in 0 until StationMesh.MODELS) {
            val d = 1500f
            assertDocks(m, "behind", 0f, 0f, d)
            assertDocks(m, "above", 0f, d, 0f)
            assertDocks(m, "below", 0f, -d, 0f)
            assertDocks(m, "right", d, 0f, 0f)
            assertDocks(m, "left", -d, 0f, 0f)
            assertDocks(m, "behind, off axis", 900f, -700f, 900f)
            assertDocks(m, "behind, just off axis", 0.4f, 0.3f, d)
            assertDocks(m, "beside, just behind the port", d, 200f, 100f)
            val front = assertDocks(m, "in front", 0f, 0f, -d)
            assertEquals(0, front.detourFrames, "model $m: a ship in front needs no detour")
            assertDocks(m, "in front, off axis", 600f, 300f, -1400f)
        }
        // the harness spawn point, behind the home station
        val st = stations[0]
        val spawn = toLocal(st, system.home, Vec3())
        assertDocks(0, "spawn", spawn.x, spawn.y, spawn.z)
    }

    @Test
    fun autoDockEscapesFromCloseIn() {
        // inside the keep-out sphere, behind the hull's front: ring hole, behind the ring tube, behind a
        // solar wing, beside the dock head, inside the slot tunnel, behind the mast
        val ring = listOf(
            floatArrayOf(0f, 500f, 150f), floatArrayOf(0f, 850f, 110f), floatArrayOf(300f, 0f, 362f),
            floatArrayOf(250f, 250f, -150f), floatArrayOf(0f, 0f, -300f), floatArrayOf(600f, 0f, 0f),
            floatArrayOf(0f, 0f, 900f), floatArrayOf(0f, 850f, -110f), floatArrayOf(0f, 330f, 200f),
        )
        val outpost = listOf(
            floatArrayOf(195f, 60f, 0f), floatArrayOf(40f, -150f, -60f), floatArrayOf(-100f, -80f, 30f),
            floatArrayOf(60f, 60f, 200f), floatArrayOf(0f, 0f, -120f), floatArrayOf(-150f, 0f, -60f),
            floatArrayOf(100f, -100f, 100f),
        )
        for ((m, pts) in listOf(0 to ring, 1 to outpost)) for (p in pts) {
            assertTrue(sc.localDistance(m, p[0], p[1], p[2]) > r + 2f, "test start (${p.joinToString()}) touches the hull")
            assertDocks(m, "close in", p[0], p[1], p[2])
        }
        // random free starts inside the keep-out: anywhere the ship fits, and 0-5 m off the hull (where
        // a ship ends up after flying into it). Each docks, or gives up the way Game does; none is
        // left detouring
        val rng = Rng(3)
        for (m in 0 until StationMesh.MODELS) {
            val rad = stations[m].radius.toFloat() + 50f
            var aborted = 0
            for (band in 0..1) {
                var n = 0
                while (n < RANDOM_STARTS) {
                    val x = (rng.nextFloat() * 2f - 1f) * rad
                    val y = (rng.nextFloat() * 2f - 1f) * rad
                    val z = (rng.nextFloat() * 2f - 1f) * rad
                    if (x * x + y * y + z * z > rad * rad || -z > StationCollision.FRONT_EXTENT[m] + 40f) continue
                    val c = sc.localDistance(m, x, y, z) - r
                    if (c < 0f || (band == 1 && c > 5f)) continue
                    n++
                    val run = simulateDock(m, x, y, z)
                    assertTrue(run.docked || run.aborted, "model $m from ($x, $y, $z), %.1f m off the hull: still detouring after ${run.time} s, ${run.at}".format(c))
                    if (run.aborted) aborted++ else assertPathClear(m, "random", x, y, z, run)
                }
            }
            println("model $m: ${2 * RANDOM_STARTS} random close-in starts, $aborted gave up")
            // 40 000 such starts per model all dock (see the integration notes); a give-up here is a regression
            assertEquals(0, aborted, "model $m: random close-in starts gave up")
        }
    }

    @Test
    fun autoDockSlidesOutOfNarrowGaps() {
        // ships wedged against the hull, where the router used to push back and forth between two
        // walls for ever: between the ring station's spindle and a tank (a 17.6 m gap), a tank tip and
        // the dock head's back face, a tank tip and the hub, sliding along the hub face or the dock
        // head's back face; the outpost's tower base over a keel fin, the V between two tanks
        val ring = listOf(
            floatArrayOf(0f, 74f, -100f), floatArrayOf(21.93f, -76.13f, -165.43f), floatArrayOf(125.34f, 0.97f, -50.65f),
            floatArrayOf(-12.82f, -111.23f, -49.57f), floatArrayOf(72.67f, -7.33f, -47.58f), floatArrayOf(-105.06f, 0.78f, -192.82f),
            floatArrayOf(94.16f, -1.99f, -191.57f), floatArrayOf(98.71f, 17.66f, -192.56f),
        )
        val outpost = listOf(
            floatArrayOf(-0.41f, -117.2f, -14.77f), floatArrayOf(-101.18f, -52.52f, 4.83f), floatArrayOf(-46.76f, -39.93f, -10.12f),
        )
        for ((m, pts) in listOf(0 to ring, 1 to outpost)) for (p in pts) {
            val c = sc.localDistance(m, p[0], p[1], p[2]) - r
            assertTrue(c in 0f..5f, "wedge start (${p.joinToString()}) is %.2f m off the hull".format(c))
            val run = assertDocks(m, "wedged %.1f m off the hull".format(c), p[0], p[1], p[2])
            assertFalse(run.aborted)
        }
        // the reported case: 1.3 m of play beside the spindle, nearest wall flipping as the ship moves
        val st = stations[0]
        val wp = DVec3()
        val pos = toWorld(st, 0f, 74f, -100f, DVec3())
        assertTrue(sc.dockWaypoint(st, pos, wp))
        assertFalse(sc.boxedIn, "the spindle-tank gap is not a dead end")
        val lp = toLocal(st, wp, Vec3())
        assertTrue(kotlin.math.abs(lp.y - 74f) < 1f && kotlin.math.abs(lp.z + 100f) < 1f, "escape from the gap should run along it (round the axis), not into a wall: $lp")
    }

    @Test
    fun boxedInHoldsStillSoGameCanAbort() {
        // a ship centre buried where the hub meets the spindle (collide never allows it; a teleport
        // could): no line gains clearance, so the router holds the ship still and says so
        val st = stations[0]
        val x = 54.13f
        val y = 24.76f
        val z = 31.15f
        assertTrue(sc.localDistance(0, x, y, z) < 0f)
        val pos = toWorld(st, x, y, z, DVec3())
        val wp = DVec3()
        for (frame in 0 until 180) {
            assertTrue(sc.dockWaypoint(st, pos, wp))
            assertTrue(sc.boxedIn, "frame $frame: not boxed in")
            assertTrue(dist(wp, pos) < 1e-3, "frame $frame: a boxed-in ship must hold still, waypoint %.3f m away".format(dist(wp, pos)))
        }
        val run = simulateDock(0, x, y, z)
        assertTrue(run.aborted, "Game's give-up rule should end it: ${run.at}")
        assertTrue(run.time <= DOCK_BOXED_GRACE + 0.05f, "gave up after ${run.time} s")
        // the flag is per call: a free ship is not boxed in
        assertTrue(sc.dockWaypoint(st, toWorld(st, 0f, 500f, 150f, DVec3()), wp))
        assertFalse(sc.boxedIn)
    }

    @Test
    fun escapeLegsAreKeptThenDropped() {
        val st = stations[0]
        val wp = DVec3()
        val fresh = DVec3()
        // a leg out of the ring hole: flying it, the waypoint stays exactly the same
        val pos = toWorld(st, 0f, 500f, 150f, DVec3())
        assertTrue(sc.dockWaypoint(st, pos, wp))
        val end = DVec3().set(wp)
        for (i in 0 until 5) {
            val dx = end.x - pos.x
            val dy = end.y - pos.y
            val dz = end.z - pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            pos.set(pos.x + dx * 10.0 / d, pos.y + dy * 10.0 / d, pos.z + dz * 10.0 / d)
            assertTrue(sc.dockWaypoint(st, pos, wp))
            assertTrue(dist(wp, end) < 1e-6, "the leg changed while the ship flew it")
        }
        // off the leg (docking cancelled, flown elsewhere): planned afresh, as a new router would
        for (p in listOf(floatArrayOf(0f, 0f, -300f), floatArrayOf(0f, 330f, 200f), floatArrayOf(250f, 250f, -150f))) {
            toWorld(st, p[0], p[1], p[2], pos)
            val a = sc.dockWaypoint(st, pos, wp)
            val b = StationCollision(flight).dockWaypoint(st, pos, fresh)
            assertEquals(b, a)
            if (a) assertTrue(dist(wp, fresh) < 1e-6, "stale leg reused at (${p.joinToString()})")
        }
    }

    @Test
    fun routerConvergesWithoutOscillating() {
        // along every routed path the waypoint only ever moves on: it never returns to an earlier one.
        // Far starts, close in, and wedged against the hull (the wedges escape in a few partial legs)
        val close = arrayOf(
            listOf(floatArrayOf(0f, 500f, 150f), floatArrayOf(0f, 74f, -100f), floatArrayOf(72.67f, -7.33f, -47.58f), floatArrayOf(94.16f, -1.99f, -191.57f)),
            listOf(floatArrayOf(195f, 60f, 0f), floatArrayOf(-101.18f, -52.52f, 4.83f), floatArrayOf(-0.41f, -117.2f, -14.77f)),
        )
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            val far = listOf(floatArrayOf(0f, 0f, 1500f), floatArrayOf(0f, 1500f, 0f), floatArrayOf(900f, -700f, 900f))
            for (start in far + close[m]) {
                val pos = toWorld(st, start[0], start[1], start[2], DVec3())
                val wp = DVec3()
                val seen = ArrayList<DVec3>()
                var switches = 0
                for (frame in 0 until 60 * 120) {
                    if (!sc.dockWaypoint(st, pos, wp)) break
                    val last = seen.lastOrNull()
                    if (last == null || dist(last, wp) > 1.0) {
                        for (i in 0 until seen.size - 1) {
                            assertTrue(dist(seen[i], wp) > 1.0, "model $m from ${start.joinToString()}: waypoint returned to an earlier one")
                        }
                        seen += DVec3().set(wp)
                        switches++
                    }
                    val dx = wp.x - pos.x
                    val dy = wp.y - pos.y
                    val dz = wp.z - pos.z
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    val k = minOf(1.0, 3.0 / d)
                    pos.set(pos.x + dx * k, pos.y + dy * k, pos.z + dz * k)
                }
                assertFalse(sc.dockWaypoint(st, pos, wp), "model $m from ${start.joinToString()}: still detouring after 2 minutes")
                val limit = if (start in far) 3 else 8
                assertTrue(switches <= limit, "model $m from ${start.joinToString()}: $switches different waypoints")
            }
        }
    }

    private fun dist(a: DVec3, b: DVec3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ------------------------------------------------------------------ (d) the slot is open

    @Test
    fun slideInAndParkTouchNothing() {
        val portL = Vec3()
        val dirL = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            StationMesh.dockPort(m, portL, dirL)
            val park = StationMesh.slotSize(m, Vec3()).z * 0.5f
            val s = ShipState()
            // from the stand-off, along the axis, to the park point at half the slot depth
            var a = StationCollision.DOCK_STANDOFF.toFloat()
            var minClear = Float.MAX_VALUE
            while (a >= -park) {
                val x = portL.x + dirL.x * a
                val y = portL.y + dirL.y * a
                val z = portL.z + dirL.z * a
                minClear = minOf(minClear, sc.localDistance(m, x, y, z) - r)
                s.reset()
                toWorld(st, x, y, z, s.pos)
                st.rot.rotate(dirL, s.vel).scale(-30f)
                val before = DVec3().set(s.pos)
                assertEquals(0f, sc.collide(s, st), "model $m: contact on the slide-in path at along $a")
                assertTrue(dist(before, s.pos) == 0.0, "model $m: the slide-in path was pushed at along $a")
                a -= 1f
            }
            val pk = Vec3().set(portL).addScaled(dirL, -park)
            val parkGap = fields[m].distance(pk.x.toDouble(), pk.y.toDouble(), pk.z.toDouble(), 100.0)
            println("model $m: slide-in keeps %.1f m between the ship sphere and the proxies, parked ship centre %.1f m from the mesh".format(minClear, parkGap))
            assertTrue(minClear > 3f, "model $m: the slide-in path passes %.1f m from the proxies".format(minClear))
            assertTrue(parkGap > r + 3f, "model $m: the parked ship is %.1f m from the mesh".format(parkGap))
        }
    }

    @Test
    fun tunnelWallsStopTheShip() {
        val portL = Vec3()
        val dirL = Vec3()
        val lp = Vec3()
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            StationMesh.dockPort(m, portL, dirL)
            val slot = StationMesh.slotSize(m, Vec3())
            val s = ShipState()
            // parked, then pushed deeper at 40 m/s: stops at the back wall
            s.reset()
            toWorld(st, portL.x - dirL.x * slot.z * 0.5f, portL.y, portL.z - dirL.z * slot.z * 0.5f, s.pos)
            st.rot.rotate(dirL, s.vel).scale(-40f)
            var impact = 0f
            for (i in 0 until 600) {
                s.pos.addScaled(s.vel, 1.0 / 120.0)
                impact = maxOf(impact, sc.collide(s, st))
                toLocal(st, s.pos, lp)
                val depth = (portL.x - lp.x) * dirL.x + (portL.y - lp.y) * dirL.y + (portL.z - lp.z) * dirL.z
                assertTrue(depth <= slot.z - r + 0.5f, "model $m: the ship is %.1f m into a %.0f m tunnel".format(depth, slot.z))
            }
            assertTrue(impact > 30f, "model $m: no impact on the tunnel's back wall")
            // drifting sideways and up inside the tunnel: the side walls and ceiling hold
            s.reset()
            toWorld(st, portL.x, portL.y, portL.z - dirL.z * slot.z * 0.5f, s.pos)
            st.rot.rotate(20f, 10f, 0f, s.vel)
            for (i in 0 until 600) {
                s.pos.addScaled(s.vel, 1.0 / 120.0)
                sc.collide(s, st)
                toLocal(st, s.pos, lp)
                assertTrue(kotlin.math.abs(lp.x) <= slot.x - r + 0.5f, "model $m: ship at x = ${lp.x} in a ±${slot.x} m slot")
                assertTrue(kotlin.math.abs(lp.y) <= slot.y - r + 0.5f, "model $m: ship at y = ${lp.y} in a ±${slot.y} m slot")
            }
        }
    }

    // ------------------------------------------------------------------ cost

    @Test
    fun farAwayIsFreeAndUntouched() {
        val s = ShipState()
        for (m in 0 until StationMesh.MODELS) {
            val st = stations[m]
            toWorld(st, 0f, 0f, st.radius.toFloat() + 60f, s.pos)
            s.vel.set(0f, 0f, -100f)
            val before = DVec3().set(s.pos)
            assertEquals(0f, sc.collide(s, st))
            assertEquals(0.0, dist(before, s.pos))
            assertTrue(sc.clearance(s, st) > 50f)
            // clearance is the gap to the nearest proxy: a ship 30 m off the ring's outer face (between ribs)
            if (m == StationMesh.RING_STATION) {
                val a = 20f * kotlin.math.PI.toFloat() / 180f
                toWorld(st, kotlin.math.sin(a) * (930f + r), kotlin.math.cos(a) * (930f + r), 0f, s.pos)
                val c = sc.clearance(s, st)
                assertTrue(c in 29f..31f, "clearance $c, expected ~30 m")
            }
        }
    }

    @Test
    fun perFrameCallsDoNotAllocate() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val s = ShipState()
        val wp = DVec3()
        // touching the dock head, inside the keep-out (escape traces), wedged (partial legs), buried
        // (boxed in), and far out behind (routing). Each call is off the previous call's leg, so
        // every one inside the keep-out plans afresh
        val touching = Array(StationMesh.MODELS) { toWorld(stations[it], 160f, 0f, -270f, DVec3()) }
        val closeIn = Array(StationMesh.MODELS) { toWorld(stations[it], 0f, 500f, 150f, DVec3()) }
        val wedged = arrayOf(toWorld(stations[0], 72.67f, -7.33f, -47.58f, DVec3()), toWorld(stations[1], -101.18f, -52.52f, 4.83f, DVec3()))
        val buried = Array(StationMesh.MODELS) { toWorld(stations[it], 54.13f, 24.76f, 31.15f, DVec3()) }
        val behind = Array(StationMesh.MODELS) { toWorld(stations[it], 0f, 0f, 1500f, DVec3()) }
        fun round() {
            for (m in 0 until StationMesh.MODELS) {
                val st = stations[m]
                s.pos.set(touching[m])
                s.vel.set(0f, 0f, 0f)
                sc.collide(s, st)
                sc.clearance(s, st)
                sc.dockWaypoint(st, s.pos, wp)
                sc.dockWaypoint(st, closeIn[m], wp)
                sc.dockWaypoint(st, wedged[m], wp)
                sc.dockWaypoint(st, buried[m], wp)
                sc.dockWaypoint(st, behind[m], wp)
            }
        }
        for (i in 0 until 300) round() // warm up (class init, JIT)
        val a = mx.currentThreadAllocatedBytes
        for (i in 0 until 1000) round()
        val b = mx.currentThreadAllocatedBytes
        val perRound = (b - a) / 1000.0
        println("collide + clearance + 5 x dockWaypoint per model: %.2f bytes allocated per round".format(perRound))
        // rough JVM timings, for orientation only
        val st = stations[0]
        fun time(label: String, n: Int, body: () -> Unit) {
            val t0 = System.nanoTime()
            for (i in 0 until n) body()
            println("  $label: %.2f us".format((System.nanoTime() - t0) / 1e3 / n))
        }
        s.pos.set(touching[0])
        time("collide, touching the dock head", 20000) { s.pos.set(touching[0]); sc.collide(s, st) }
        time("clearance, next to the dock head", 20000) { sc.clearance(s, st) }
        time("dockWaypoint, outside the keep-out", 20000) { sc.dockWaypoint(st, behind[0], wp) }
        var flip = false
        time("dockWaypoint, planning an escape (close in / wedged, alternating)", 2000) {
            flip = !flip
            sc.dockWaypoint(st, if (flip) closeIn[0] else wedged[0], wp)
        }
        sc.dockWaypoint(st, closeIn[0], wp)
        time("dockWaypoint, flying a planned leg", 20000) { sc.dockWaypoint(st, closeIn[0], wp) }
        assertTrue(perRound < 1.0, "collide / clearance / dockWaypoint allocate: %.1f bytes per round".format(perRound))
    }

    companion object {
        /** The real meshes with exact distance / inside queries (built once for all tests). */
        val FIELDS: Array<MeshField> by lazy { Array(StationMesh.MODELS) { MeshField(StationMesh.build(it)) } }

        /** Hull points may lie this far outside the proxies (small greebles: window strips, guide lights). */
        const val HULL_TOLERANCE = 2.5f

        /** Proxy-interior points within this of a hull surface (or inside the hull) count as hull. */
        const val VOLUME_TOLERANCE = 2.0
        const val PER_PROXY = 60

        /**
         * A ship flying at the hull keeps its centre this far from the mesh: its collision radius,
         * less what hull greebles may stick out past the proxies. Anything closer is real penetration.
         */
        val MIN_GAP = (ShipSpec.STANDARD.collisionRadius - HULL_TOLERANCE).toDouble()

        /** Random auto-dock starts per model and band (anywhere free / 0-5 m off the hull). */
        const val RANDOM_STARTS = 25

        /** An escape from a ship wedged against the hull may pass this much closer to it than it started. */
        const val GAP_SLACK = 0.5

        /** Game gives up auto-dock once the router has been boxed in for this long (integration notes). */
        const val DOCK_BOXED_GRACE = 0.5f
    }
}
