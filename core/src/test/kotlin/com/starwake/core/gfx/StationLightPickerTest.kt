package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import com.starwake.core.station.StationInstance
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-pixel Roblox lights of a station ([StationLightPicker]): no light switches on or off at once
 * while the camera flies a docking route, lights are on from the Outer approach point, and lights in
 * spinning groups are ranked where they are now.
 */
class StationLightPickerTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }
    private fun model(t: String) = StationModel.load(t) { File(assets, it).readBytes() }

    /** Weight per model light (0 = off) for a camera at ([x], [y], [z]). */
    private fun weights(p: StationLightPicker, st: StationInstance, x: Float, y: Float, z: Float, out: FloatArray): FloatArray {
        out.fill(0f)
        val n = p.pick(st, x, y, z, StationShaders.MAX_LIGHTS)
        for (j in 0 until n) out[p.index[j]] = p.weight[j]
        return out
    }

    /** The first renderer's choice: the 8 smallest keys at rest positions within 600 m, all at full strength. */
    private fun oldWeights(m: StationModel, x: Float, y: Float, z: Float, out: FloatArray): FloatArray {
        out.fill(0f)
        val keys = m.lights.indices.map { i ->
            val l = m.lights[i]
            val dx = l.x - x
            val dy = l.y - y
            val dz = l.z - z
            i to sqrt(dx * dx + dy * dy + dz * dz) - l.range
        }.filter { it.second <= 600f }.sortedBy { it.second }.take(StationShaders.MAX_LIGHTS)
        for ((i, _) in keys) out[i] = 1f
        return out
    }

    /** The docking route: Outer -> Mouth -> Inner -> 34 m over pad [pad], then down the hangar centre line. */
    private fun route(m: StationModel, pad: Int): List<FloatArray> {
        val p = FloatArray(3)
        val pts = ArrayList<FloatArray>()
        m.approachPoint(StationModel.APPROACH_OUTER, p); pts += p.copyOf()
        m.approachPoint(StationModel.APPROACH_MOUTH, p); pts += p.copyOf()
        m.approachPoint(StationModel.APPROACH_INNER, p); pts += p.copyOf()
        val pd = m.pad(pad)!!
        pts += floatArrayOf(pd.x, pd.y + 44.5f, pd.z)
        pts += floatArrayOf(-25f, -30f, 300f)
        return pts
    }

    @Test
    fun noLightPopsAlongTheDockingRoutes() {
        val step = 0.25f
        for (t in StationModel.TYPES) {
            val m = model(t)
            val st = StationInstance(m, seed = 3)
            val p = StationLightPicker()
            val prev = FloatArray(m.lights.size)
            val cur = FloatArray(m.lights.size)
            val oldPrev = FloatArray(m.lights.size)
            val oldCur = FloatArray(m.lights.size)
            var maxStep = 0f
            var oldSwitches = 0
            var newSwitches = 0
            for (pad in intArrayOf(1, 4)) {
                val pts = route(m, pad)
                var first = true
                for (seg in 0 until pts.size - 1) {
                    val a = pts[seg]
                    val b = pts[seg + 1]
                    val len = sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]))
                    val n = (len / step).toInt()
                    for (k in 0..n) {
                        val s = k / n.toFloat()
                        val x = a[0] + (b[0] - a[0]) * s
                        val y = a[1] + (b[1] - a[1]) * s
                        val z = a[2] + (b[2] - a[2]) * s
                        weights(p, st, x, y, z, cur)
                        oldWeights(m, x, y, z, oldCur)
                        assertTrue(p.count <= StationShaders.MAX_LIGHTS)
                        if (!first) {
                            for (i in cur.indices) {
                                val d = abs(cur[i] - prev[i])
                                if (d > maxStep) maxStep = d
                                if ((cur[i] > 0f) != (prev[i] > 0f)) newSwitches++
                                if (oldCur[i] != oldPrev[i]) oldSwitches++
                            }
                        }
                        first = false
                        cur.copyInto(prev)
                        oldCur.copyInto(oldPrev)
                    }
                }
            }
            println("%-16s lights %2d: largest weight change per %.2f m step %.4f; set changes %d (each at weight 0), first renderer: %d hard switches"
                .format(t, m.lights.size, step, maxStep, newSwitches, oldSwitches))
            // smoothstep (slope <= 1.5) over at least FADE_MIN = 20 m of cut - key, which moves at most 2 * 0.25 m
            // per step: at most 0.0375 per step (a switched light would jump by up to 1)
            assertTrue(maxStep < 0.04f, "$t: a light weight jumped by $maxStep in one $step m step")
        }
    }

    @Test
    fun lightsAreOnFromTheOuterApproachPoint() {
        val p = StationLightPicker()
        val o = FloatArray(3)
        for (t in StationModel.TYPES) {
            val m = model(t)
            val st = StationInstance(m)
            m.approachPoint(StationModel.APPROACH_OUTER, o)
            val n = p.pick(st, o[0], o[1], o[2], StationShaders.MAX_LIGHTS)
            var sum = 0f
            for (j in 0 until n) sum += p.weight[j]
            assertTrue(n > 0 && sum > 1f, "$t: $n lights, total weight $sum at the Outer point")
            // far beyond the reach (detail level 2): none
            assertEquals(0, p.pick(st, 0f, 0f, -20_000f, StationShaders.MAX_LIGHTS), t)
            // maxLights = 0 (low quality): none
            assertEquals(0, p.pick(st, o[0], o[1], o[2], 0), t)
        }
    }

    @Test
    fun spinningLightsAreRankedWhereTheyAre() {
        // Foundry WeldRingA (3 deg/s about X through (580, 0, -120)) carries two lights 133 m off its axis
        val m = model("FoundryStation")
        val g = m.groups.indexOfFirst { it.name == "WeldRingA" }
        val li = m.lights.indices.filter { m.lights[it].group == g }
        assertEquals(2, li.size)
        val st = StationInstance(m, seed = 11)
        val rows = FloatArray(12)
        val p = StationLightPicker()
        repeat(4) {
            st.update(15f, Float.NaN, 0f, 0f) // 45 degrees a step
            st.groupRows(g, rows, 0)
            val l = m.lights[li[0]]
            val x = rows[0] * l.x + rows[1] * l.y + rows[2] * l.z + rows[3]
            val y = rows[4] * l.x + rows[5] * l.y + rows[6] * l.z + rows[7]
            val z = rows[8] * l.x + rows[9] * l.y + rows[10] * l.z + rows[11]
            // the camera 5 m from where the light is now: it is the nearest light, at full weight
            val n = p.pick(st, x + 5f, y, z, StationShaders.MAX_LIGHTS)
            assertTrue(n > 0)
            assertEquals(li[0], p.index[0], "the turned light ranks first")
            assertEquals(1f, p.weight[0], 1e-6f)
            assertEquals(x, p.pos[0], 1e-3f); assertEquals(y, p.pos[1], 1e-3f); assertEquals(z, p.pos[2], 1e-3f)
            // its direction turned with it
            val dx = rows[0] * l.dirX + rows[1] * l.dirY + rows[2] * l.dirZ
            assertEquals(dx, p.dir[0], 1e-4f)
        }
    }

    @Test
    fun pickingDoesNotAllocate() {
        val m = model("SpireStation")
        val st = StationInstance(m)
        val p = StationLightPicker()
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        val id = Thread.currentThread().id
        var n = 0
        repeat(5000) { n += p.pick(st, 0f, -30f, -400f + it * 0.1f, StationShaders.MAX_LIGHTS) }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(5000) { n += p.pick(st, 0f, -30f, -400f + it * 0.1f, StationShaders.MAX_LIGHTS) }
        val bytes = bean.getThreadAllocatedBytes(id) - before
        assertTrue(n > 0)
        assertTrue(bytes < 1024, "$bytes B allocated")
    }
}
