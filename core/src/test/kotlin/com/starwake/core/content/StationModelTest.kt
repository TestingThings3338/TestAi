package com.starwake.core.content

import com.starwake.core.station.StationColliders
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The converted Roblox stations (assets/stations, tools/roblox/convert_stations.py) against the
 * numbers read from Assets.rbxmx for docs/port/08-stations-economy.md (section 2.3): the shared
 * hangar contract, pads, approach points, rotating groups, colliders, lamps, labels and the mesh.
 */
class StationModelTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }

    private fun read(path: String): ByteArray = File(assets, path).readBytes()

    private val all = StationModel.TYPES.toList() + StationModel.SITE
    private val models: Map<String, StationModel> = all.associateWith { StationModel.load(it, ::read) }
    private val stations get() = StationModel.TYPES.map { models.getValue(it) }

    private fun near(expected: Float, actual: Float, tol: Float = 1e-3f, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected, got $actual")

    @Test
    fun everyModelLoads() {
        for (t in all) {
            val m = models.getValue(t)
            assertEquals(t, m.type)
            assertEquals(t == StationModel.SITE, m.isSite)
        }
        assertEquals("CONSTRUCTION", models.getValue("Site").builtBy)
        assertEquals("SPIRE", models.getValue("SpireStation").builtBy)
    }

    @Test
    fun hangarContractIsSharedByEveryStation() {
        // 2.3.1: hangar 360 x 150 x 690 centred on the root; approach Outer, Mouth, Inner on the axis
        val mouths = mapOf(
            "RingStation" to floatArrayOf(280f, 130f, 35f), "GardenStation" to floatArrayOf(314f, 150f, 35f),
            "FoundryStation" to floatArrayOf(300f, 150f, 117f), "SpireStation" to floatArrayOf(360f, 150f, 75f),
            "HollowStation" to floatArrayOf(298f, 148f, 135f), "RefineryStation" to floatArrayOf(300f, 143f, 87f),
        )
        val p = FloatArray(3)
        for (m in stations) {
            assertContentEquals(floatArrayOf(360f, 150f, 690f), m.hangarSize, m.type)
            assertContentEquals(floatArrayOf(0f, 0f, 0f), m.hangarCentre, m.type)
            assertContentEquals(mouths.getValue(m.type), m.mouthSize, m.type)
            assertTrue(m.hasApproach)
            assertTrue(m.approachPoint(StationModel.APPROACH_OUTER, p)); assertContentEquals(floatArrayOf(0f, 0f, -1270f), p, m.type)
            assertTrue(m.approachPoint(StationModel.APPROACH_MOUTH, p)); assertContentEquals(floatArrayOf(0f, 0f, -370f), p, m.type)
            assertTrue(m.approachPoint(StationModel.APPROACH_INNER, p)); assertContentEquals(floatArrayOf(0f, 0f, -320f), p, m.type)
        }
        val site = models.getValue("Site")
        assertFalse(site.hasApproach)
        assertFalse(site.approachPoint(StationModel.APPROACH_OUTER, p))
        assertTrue(site.pads.isEmpty())
    }

    @Test
    fun radiusAttributes() {
        val radius = mapOf(
            "RingStation" to 693f, "GardenStation" to 1541f, "FoundryStation" to 1135f, "SpireStation" to 1506f,
            "HollowStation" to 1247f, "RefineryStation" to 1368f, "Site" to 697f,
        )
        for ((t, r) in radius) assertEquals(r, models.getValue(t).radius, t)
    }

    @Test
    fun sixPadsOf110By130FacingTheMouth() {
        val centres = arrayOf(
            floatArrayOf(85f, -130f), floatArrayOf(85f, 30f), floatArrayOf(85f, 190f),
            floatArrayOf(-85f, -130f), floatArrayOf(-85f, 30f), floatArrayOf(-85f, 190f),
        )
        val pos = FloatArray(3)
        val rot = FloatArray(9)
        for (m in stations) {
            assertEquals(6, m.pads.size, m.type)
            for ((i, p) in m.pads.withIndex()) {
                assertEquals(i + 1, p.index)
                near(110f, p.sizeX, what = "${m.type} pad ${p.index} width")
                near(2f, p.sizeY, what = "${m.type} pad ${p.index} height")
                near(130f, p.sizeZ, what = "${m.type} pad ${p.index} depth")
                // top face centre: y = -74 + 1
                near(centres[i][0], p.x, what = "${m.type} pad ${p.index} x")
                near(-73f, p.y, what = "${m.type} pad ${p.index} top")
                near(centres[i][1], p.z, what = "${m.type} pad ${p.index} z")
                // identity rotation: the pad (and a ship clamped on it) looks -Z, at the mouth
                assertContentEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), p.top.copyOfRange(3, 12), "${m.type} pad ${p.index}")
                assertTrue(p.lampSlot > 0, "${m.type} pad ${p.index} has a lamp slot")
                assertEquals(StationModel.LAMP_PAD, m.lamps[p.lampSlot - 1].kind)
                assertEquals(p.index, m.lamps[p.lampSlot - 1].pad)
                assertEquals(8, m.lamps[p.lampSlot - 1].parts, "${m.type} pad ${p.index}: 4 edge strips + 4 corner lamps")
            }
            // ShipStation.padPose: the top face raised by the ship's ground height
            assertTrue(m.padPose(1, 10.5f, pos, rot))
            assertContentEquals(floatArrayOf(85f, -62.5f, -130f), pos)
            assertFalse(m.padPose(7, 10.5f, pos, rot))
            assertNull(m.pad(0))
            assertNotNull(m.pad(6))
        }
    }

    @Test
    fun isInHangarUsesTheBoxWithTwentyMetresSlack() {
        val m = models.getValue("SpireStation")
        assertTrue(m.isInHangar(0f, 0f, 0f))
        assertTrue(m.isInHangar(179.9f, -74.9f, 0f))
        assertFalse(m.isInHangar(180.1f, 0f, 0f))
        assertFalse(m.isInHangar(0f, 75.1f, 0f))
        // the mouth is at z = -345 - 20 slack
        assertTrue(m.isInHangar(0f, 0f, -364.9f))
        assertFalse(m.isInHangar(0f, 0f, -365.1f))
        assertTrue(m.isInHangar(0f, 0f, 364.9f))
        assertFalse(models.getValue("Site").isInHangar(0f, 0f, 0f))
    }

    @Test
    fun rotatingGroupsMatchTheRobloxModels() {
        // name, degrees per second, axis, centre (08-stations-economy.md 2.3.2)
        val expected = mapOf(
            "RingStation" to listOf(g("Ring", 2f, 0f, 0f, 1f, 0f, 0f, 0f)),
            "GardenStation" to listOf(g("Drum", 3f, 1f, 0f, 0f, 0f, 220f, 830f), g("Radar", 12f, 0f, 1f, 0f, 0f, 313f, 394f)),
            "FoundryStation" to listOf(
                g("CommsDish", 6f, 0f, 1f, 0f, 150f, 176f, -170f), g("WeldRingA", 3f, 1f, 0f, 0f, 580f, 0f, -120f),
                g("WeldRingB", -4f, 1f, 0f, 0f, -580f, 0f, -120f),
            ),
            "SpireStation" to listOf(
                g("HaloLow", 2f, 0f, 1f, 0f, 0f, 0f, 520f), g("HaloMid", -3f, 0f, 1f, 0f, 0f, 0f, 520f),
                g("HaloHigh", 4f, 0f, 1f, 0f, 0f, 0f, 520f), g("Lighthouse", 36f, 0f, 1f, 0f, 0f, 0f, 520f),
                g("Radar", 20f, 0f, 1f, 0f, -128f, 0f, 330f),
            ),
            "HollowStation" to listOf(
                g("BucketWheel", 6f, 0.289f, 0f, 0.957f, -855f, -120f, -139f), g("Sheave1", 20f, 0f, 0.120f, 0.993f, 264.9f, 794.0f, 5.7f),
                g("Sheave2", 20f, 0f, 0.075f, 0.997f, -17.8f, 791.4f, 97.5f), g("Sheave3", -20f, 0f, 0.075f, 0.997f, -282.9f, 774.6f, 99.9f),
                g("Radar", 12f, 0.042f, 0.996f, 0.084f, 73.3f, 637.4f, 242.4f),
            ),
            "RefineryStation" to listOf(g("Centrifuge", 10f, 1f, 0f, 0f, 0f, 470f, -110f), g("CommsDish", 15f, 0f, 1f, 0f, -215f, 0f, -160f)),
        )
        for ((t, groups) in expected) {
            val m = models.getValue(t)
            assertEquals(groups.size + 1, m.groups.size, t)
            for ((i, e) in groups.withIndex()) {
                val gr = m.groups[i + 1]
                assertEquals(e.name, gr.name, t)
                near(e.degreesPerSecond, gr.degreesPerSecond, what = "$t ${e.name} speed")
                near(e.axisX, gr.axisX, 2e-3f, "$t ${e.name} axis x"); near(e.axisY, gr.axisY, 2e-3f, "$t ${e.name} axis y")
                near(e.axisZ, gr.axisZ, 2e-3f, "$t ${e.name} axis z")
                near(e.centreX, gr.centreX, 0.06f, "$t ${e.name} centre x"); near(e.centreY, gr.centreY, 0.06f, "$t ${e.name} centre y")
                near(e.centreZ, gr.centreZ, 0.06f, "$t ${e.name} centre z")
                // every spinning group draws something
                var tris = 0
                for (p in 0 until StationModel.PASSES) for (c in 0..2) tris += m.mesh.count(p, c, i + 1)
                assertTrue(tris > 0, "$t ${e.name} has no triangles")
            }
        }
        val site = models.getValue("Site")
        assertEquals(listOf("static", "Stage1", "Stage2", "Stage3", "Stage4"), site.groups.map { it.name })
        assertTrue(site.groups.all { it.degreesPerSecond == 0f })
    }

    private fun g(name: String, dps: Float, ax: Float, ay: Float, az: Float, cx: Float, cy: Float, cz: Float) =
        StationModel.Group(name, dps, ax, ay, az, cx, cy, cz)

    @Test
    fun collidersAreTheInvisibleColliderParts() {
        val counts = mapOf(
            "RingStation" to 66, "GardenStation" to 172, "FoundryStation" to 311, "SpireStation" to 222,
            "HollowStation" to 324, "RefineryStation" to 111, "Site" to 49,
        )
        for ((t, n) in counts) assertEquals(n, models.getValue(t).colliders.count, t)
        // the Refinery is the only one with other shapes: 77 blocks, 14 cylinders, 13 balls, 7 wedges
        val c = models.getValue("RefineryStation").colliders
        val shapes = IntArray(5)
        for (i in 0 until c.count) shapes[c.shape(i)]++
        assertContentEquals(intArrayOf(77, 13, 14, 7, 0), shapes)
        for (t in all) {
            val cs = models.getValue(t).colliders
            for (i in 0 until cs.count) {
                if (t != "RefineryStation") assertEquals(StationColliders.SHAPE_BLOCK, cs.shape(i), t)
                assertTrue(cs.group(i) < models.getValue(t).groups.size)
            }
        }
        // the ring's colliders turn with it (36 in Roblox's Ring group)
        val ring = models.getValue("RingStation").colliders
        assertEquals(36, (0 until ring.count).count { ring.group(it) == 1 })
        // site: 8 in Base, then 10/3/22/6 in the stages
        val site = models.getValue("Site").colliders
        val perStage = IntArray(5)
        for (i in 0 until site.count) perStage[site.group(i)]++
        assertContentEquals(intArrayOf(8, 10, 3, 22, 6), perStage)
    }

    @Test
    fun lampsBlinkersPapiAndAccent() {
        // parts with Blink (the site's stage blinkers become plain parts, as ShipStation drops them)
        val blink = mapOf(
            "RingStation" to 34, "GardenStation" to 28, "FoundryStation" to 41, "SpireStation" to 41,
            "HollowStation" to 46, "RefineryStation" to 65,
        )
        val accent = mapOf(
            "RingStation" to 0, "GardenStation" to 104, "FoundryStation" to 23, "SpireStation" to 117,
            "HollowStation" to 10, "RefineryStation" to 12, "Site" to 0,
        )
        for (t in all) {
            val m = models.getValue(t)
            val blinkParts = m.lamps.filter { it.kind == StationModel.LAMP_BLINK }.sumOf { it.parts }
            if (t in blink) assertEquals(blink.getValue(t), blinkParts, t)
            val papi = m.lamps.filter { it.kind == StationModel.LAMP_PAPI }
            if (t == "RingStation" || t == "Site") {
                assertTrue(papi.isEmpty(), t)
            } else {
                assertEquals(4, papi.size, t)
                val deg = papi.map { Math.toDegrees(it.papiThreshold.toDouble()).toFloat() }.sorted()
                assertContentEquals(floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f), deg.map { Math.round(it * 10) / 10f }.toFloatArray(), t)
                // right of the mouth (+X), in front of it
                for (p in papi) {
                    assertTrue(p.papiX in 180f..335f, "$t PAPI x ${p.papiX}")
                    assertTrue(p.papiZ in -480f..-374f, "$t PAPI z ${p.papiZ}")
                    assertEquals(StationModel.PASS_OPAQUE, p.pass)
                }
            }
            assertEquals(accent.getValue(t), m.lamps.filter { it.kind == StationModel.LAMP_ACCENT }.sumOf { it.parts }, t)
            assertEquals(if (accent.getValue(t) > 0) 1 else 0, m.accentSlot.coerceAtMost(1), t)
            for (l in m.lamps) {
                if (l.kind == StationModel.LAMP_BLINK) {
                    assertTrue(l.period > 0f && l.duty in 0.02f..0.98f, "$t blinker")
                    assertEquals(StationModel.PASS_LAMP, l.pass)
                }
            }
            assertTrue(m.lamps.size < 64, "$t: ${m.lamps.size} lamp slots (shader holds 63)")
        }
        // the Ring's ten mouth guide lamps: period 1.0, phase 0 .. 0.4 (a chase running upwards)
        val ring = models.getValue("RingStation")
        val guides = ring.lamps.filter { it.kind == StationModel.LAMP_BLINK && abs(it.period - 1f) < 1e-4f }
        assertTrue(guides.map { it.phase }.toSet().containsAll(listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f).map { it }), "Ring guide lamp phases")
    }

    @Test
    fun signLabels() {
        val counts = mapOf(
            "RingStation" to 27, "GardenStation" to 12, "FoundryStation" to 22, "SpireStation" to 32,
            "HollowStation" to 19, "RefineryStation" to 57, "Site" to 2,
        )
        assertEquals(171, counts.values.sum())
        for ((t, n) in counts) {
            val m = models.getValue(t)
            assertEquals(n, m.labels.size, t)
            assertEquals(1, m.labels.count { it.isStationName }, "$t: one runtime station name (Sign/Display/Name)")
            assertEquals(if (m.isSite) 0 else 6, m.labels.count { it.isPadNumber }, t)
            for (l in m.labels) {
                assertTrue(l.scaled && l.wrapped, "$t ${l.text}: every Roblox label is TextScaled + TextWrapped")
                assertTrue(l.layer in 1..StationModel.MAX_LAYERS)
                assertTrue(l.pxPerMetre > 0f)
                val w = len(l.u)
                val h = len(l.v)
                assertTrue(w > 0.5f && h > 0.2f, "$t ${l.text}: box $w x $h m")
                // u and v span the label's plane, normal out of it
                assertTrue(abs(dot(l.u, l.normal)) < 1e-2f * w && abs(dot(l.v, l.normal)) < 1e-2f * h, "$t ${l.text}")
            }
        }
        val spire = models.getValue("SpireStation")
        // the rbxmx stores "·" as &#194;&#183;: decoded back to U+00B7
        assertNotNull(spire.labels.firstOrNull { it.text == "FOLLOW THE STROBES  ·  GREEN ON YOUR LEFT" })
        assertEquals("MERIDIAN", spire.labels.first { it.isStationName }.text)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), spire.labels.filter { it.isPadNumber }.map { it.pad }.sorted())
        val site = models.getValue("Site")
        assertEquals(setOf("CONSTRUCTION SITE", "NEW STATION"), site.labels.map { it.text }.toSet())
        // the station name reads from outside the mouth: its normal points out of the hangar (-Z)
        val name = spire.labels.first { it.isStationName }
        assertTrue(name.normal[2] < -0.99f)
        assertTrue(name.u[0] < 0f, "reads left to right for a pilot facing +Z (towards the hangar)")
    }

    @Test
    fun meshIsConsistent() {
        val v = FloatArray(10)
        for (t in all) {
            val m = models.getValue(t)
            val mesh = m.mesh
            assertEquals(mesh.vertexCount * StationModel.VERTEX_BYTES, mesh.vertices.size)
            assertEquals(mesh.indexCount * mesh.indexSize, mesh.indices.size)
            assertEquals(mesh.indexCount, mesh.counts.sum())
            assertEquals(if (mesh.vertexCount <= 65536) 2 else 4, mesh.indexSize)
            for (k in mesh.counts) assertEquals(0, k % 3, "$t: whole triangles per range")
            // quantisation: a few millimetres at worst (19-bit grid over ~2.3 km)
            assertTrue(mesh.step in 1e-4f..5e-3f, "$t: step ${mesh.step} m")
            val b = ByteBuffer.wrap(mesh.indices).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until mesh.indexCount) {
                val idx = if (mesh.indexSize == 2) b.getShort(i * 2).toInt() and 0xFFFF else b.getInt(i * 4)
                assertTrue(idx in 0 until mesh.vertexCount, "$t: index $idx")
            }
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (i in 0 until mesh.vertexCount) {
                vertex(mesh, i, v)
                for (k in 0..2) {
                    lo[k] = minOf(lo[k], v[k]); hi[k] = maxOf(hi[k], v[k])
                }
                val mat = v[3].toInt()
                val group = v[4].toInt()
                val slot = v[5].toInt()
                assertTrue(group < m.groups.size, "$t: group $group")
                if (mat < StationModel.MAT_PAINT1) assertTrue(slot <= m.lamps.size, "$t: lamp slot $slot")
                val nl = v[6] * v[6] + v[7] * v[7] + v[8] * v[8]
                assertTrue(abs(nl - 1f) < 0.03f, "$t: normal length $nl")
            }
            for (k in 0..2) {
                near(m.bounds[k], lo[k], mesh.step * 2f, "$t bounds min $k")
                near(m.bounds[3 + k], hi[k], mesh.step * 2f, "$t bounds max $k")
            }
            // detail levels are nested prefixes and lose triangles as they go
            assertTrue(mesh.triangles(0) >= mesh.triangles(1) && mesh.triangles(1) >= mesh.triangles(2) && mesh.triangles(2) > 0, t)
            for (p in 0 until StationModel.PASSES) {
                var sum = 0
                for (c in 2 downTo 0) for (g in 0 until mesh.groupCount) {
                    assertEquals(mesh.first(p) + sum, mesh.first(p, c, g), "$t pass $p class $c group $g")
                    sum += mesh.count(p, c, g)
                }
                assertEquals(sum, mesh.count(p, 0))
            }
            // budget: one station in view, LOD0 well under 60k triangles
            assertTrue(mesh.triangles(0) < 60_000, "$t: ${mesh.triangles(0)} triangles")
        }
    }

    @Test
    fun boundsFitTheRobloxModels() {
        // 08-stations-economy.md 2.3.2 bounding boxes include invisible parts; the drawn geometry fits inside
        val boxes = mapOf(
            "RingStation" to floatArrayOf(1208f, 1164f, 1240f), "GardenStation" to floatArrayOf(1903f, 1413f, 2202f),
            "FoundryStation" to floatArrayOf(2162f, 458f, 1627f), "SpireStation" to floatArrayOf(686f, 2147f, 2332f),
            "HollowStation" to floatArrayOf(2156f, 1451f, 1553f), "RefineryStation" to floatArrayOf(2308f, 2001f, 1135f),
            "Site" to floatArrayOf(1163f, 1164f, 1236f),
        )
        for ((t, box) in boxes) {
            val b = models.getValue(t).bounds
            for (k in 0..2) {
                val size = b[3 + k] - b[k]
                assertTrue(size <= box[k] + 1f && size >= box[k] * 0.8f, "$t extent $k: $size m, Roblox box ${box[k]} m")
            }
            // the hangar box lies inside the model
            if (t != "Site") {
                assertTrue(b[0] < -180f && b[3] > 180f && b[1] < -75f && b[4] > 75f && b[2] < -345f && b[5] > 345f, t)
            }
        }
    }

    @Test
    fun parsingIsDeterministicAndRejectsGarbage() {
        val bytes = read(StationModel.path("RingStation"))
        val a = StationModel.parse(bytes)
        val b = StationModel.parse(bytes)
        assertContentEquals(a.mesh.vertices, b.mesh.vertices)
        assertContentEquals(a.mesh.indices, b.mesh.indices)
        assertContentEquals(a.bounds, b.bounds)
        assertFailsWith<IllegalArgumentException> { StationModel.parse(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertFailsWith<IllegalArgumentException> { StationModel.parse(bytes.copyOf().also { it[4] = 9 }) }
        assertFailsWith<Exception> { StationModel.parse(bytes.copyOf(bytes.size - 7)) }
    }

    /** Decoded vertex [i]: x, y, z, material, group, slot, nx, ny, nz, alpha. */
    private fun vertex(mesh: StationModel.Mesh, i: Int, out: FloatArray) {
        val b = ByteBuffer.wrap(mesh.vertices).order(ByteOrder.LITTLE_ENDIAN)
        val o = i * StationModel.VERTEX_BYTES
        val w = b.getShort(o + 6).toInt() and 0xFFFF
        val qx = ((b.getShort(o).toInt() and 0xFFFF) shl 3) or (w and 7)
        val qy = ((b.getShort(o + 2).toInt() and 0xFFFF) shl 3) or ((w shr 3) and 7)
        val qz = ((b.getShort(o + 4).toInt() and 0xFFFF) shl 3) or ((w shr 6) and 7)
        out[0] = mesh.originX + qx * mesh.step
        out[1] = mesh.originY + qy * mesh.step
        out[2] = mesh.originZ + qz * mesh.step
        out[3] = ((w shr 9) and 15).toFloat()
        out[4] = (w shr 13).toFloat()
        out[5] = (b.get(o + 11).toInt() and 0xFF).toFloat()
        out[6] = b.get(o + 8) / 127f
        out[7] = b.get(o + 9) / 127f
        out[8] = b.get(o + 10) / 127f
        out[9] = (b.get(o + 15).toInt() and 0xFF) / 255f
    }

    private fun len(a: FloatArray) = kotlin.math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
