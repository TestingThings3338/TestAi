package com.starwake.core.content

import com.starwake.core.game.GearRig
import com.starwake.core.game.ShipSpec
import com.starwake.core.math.Vec3
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The converted Roblox ships (assets/ships, tools/roblox/convert_ships.py) and their parser:
 * structure, counts, bounds, the view classes, the landing-gear rig and the collision probes.
 */
class ShipModelTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "ships").isDirectory }

    private fun read(path: String): ByteArray = File(assets, path).readBytes()

    private val models: Map<String, ShipModel> = ShipCatalog.all.associate { it.id to ShipModel.load(it.id, ::read) }

    /** Decoded vertex [v] of [c]: x, y, z (ship space), material, bone, nx, ny, nz, alpha. */
    private fun vertex(c: ShipModel.Chunk, v: Int, out: FloatArray) {
        val b = ByteBuffer.wrap(c.vertices).order(ByteOrder.LITTLE_ENDIAN)
        val o = v * ShipModel.VERTEX_BYTES
        out[0] = c.originX + (b.getShort(o).toInt() and 0xFFFF) * c.scale
        out[1] = c.originY + (b.getShort(o + 2).toInt() and 0xFFFF) * c.scale
        out[2] = c.originZ + (b.getShort(o + 4).toInt() and 0xFFFF) * c.scale
        val mb = b.getShort(o + 6).toInt() and 0xFFFF
        out[3] = (mb and 0xFF).toFloat()
        out[4] = (mb shr 8).toFloat()
        out[5] = b.get(o + 8) / 127f
        out[6] = b.get(o + 9) / 127f
        out[7] = b.get(o + 10) / 127f
        out[8] = (b.get(o + 15).toInt() and 0xFF) / 255f
    }

    private fun index(c: ShipModel.Chunk, i: Int): Int {
        val b = ByteBuffer.wrap(c.indices).order(ByteOrder.LITTLE_ENDIAN)
        return if (c.indexSize == 2) b.getShort(i * 2).toInt() and 0xFFFF else b.getInt(i * 4)
    }

    @Test
    fun everyCatalogShipLoads() {
        assertEquals(8, ShipCatalog.all.size)
        for (e in ShipCatalog.all) {
            val m = models.getValue(e.id)
            assertEquals(e.id, m.id)
            assertEquals(e.name, m.name)
            assertEquals(2, m.chunks.size, "${m.id}: hull + cockpit")
            // ShipConfig.Camera.SeatOffset: the eye is 0.5 m above the ship origin
            assertEquals(0f, m.eyeX); assertEquals(0.5f, m.eyeY); assertEquals(0f, m.eyeZ)
        }
        assertEquals("varrow", ShipCatalog.get(ShipCatalog.DEFAULT).id)
    }

    @Test
    fun chunksAreConsistent() {
        val v = FloatArray(9)
        for (m in models.values) {
            for ((ci, c) in m.chunks.withIndex()) {
                assertTrue(c.vertexCount > 1000, "${m.id} chunk $ci: ${c.vertexCount} vertices")
                assertEquals(c.vertexCount * ShipModel.VERTEX_BYTES, c.vertices.size)
                assertEquals(c.indexCount * c.indexSize, c.indices.size)
                assertEquals(c.indexCount, c.counts.sum())
                assertEquals(0, c.indexCount % 3)
                for (k in c.counts) assertEquals(0, k % 3, "${m.id}: ranges hold whole triangles")
                assertEquals(if (c.vertexCount <= 65536) 2 else 4, c.indexSize)
                // quantisation: millimetres at worst (the biggest hull is ~107 m long)
                assertTrue(c.scale in 1e-5f..2e-3f, "${m.id} chunk $ci: step ${c.scale} m")
                for (i in 0 until c.indexCount) {
                    val idx = index(c, i)
                    assertTrue(idx in 0 until c.vertexCount, "${m.id} chunk $ci: index $idx out of range")
                }
                val b = m.bounds
                val tol = c.scale * 2f
                for (k in 0 until c.vertexCount) {
                    vertex(c, k, v)
                    assertTrue(v[0] >= b[0] - tol && v[0] <= b[3] + tol && v[1] >= b[1] - tol && v[1] <= b[4] + tol && v[2] >= b[2] - tol && v[2] <= b[5] + tol, "${m.id}: vertex outside the bounds")
                    assertTrue(v[3] < 16f, "${m.id}: material ${v[3]}")
                    assertTrue(v[4] < m.boneCount, "${m.id}: bone ${v[4]} of ${m.boneCount}")
                    val len = sqrt(v[5] * v[5] + v[6] * v[6] + v[7] * v[7])
                    assertTrue(abs(len - 1f) < 0.03f, "${m.id}: normal length $len")
                }
            }
        }
    }

    @Test
    fun boundsMatchTheRobloxModel() {
        for (m in models.values) {
            val b = m.bounds
            val r = m.rbxBounds
            // the converted geometry spans the model's BoundsSize (gear down) ...
            assertTrue(abs((b[3] - b[0]) - r[0]) < 0.5f, "${m.id}: width ${b[3] - b[0]} vs BoundsSize.X ${r[0]}")
            assertTrue(abs((b[4] - b[1]) - r[1]) < 0.5f, "${m.id}: height ${b[4] - b[1]} vs BoundsSize.Y ${r[1]}")
            assertTrue(abs((b[5] - b[2]) - r[2]) < 0.5f, "${m.id}: length ${b[5] - b[2]} vs BoundsSize.Z ${r[2]}")
            // ... and stands on its gear RootToGround below the eye
            assertTrue(abs(b[1] - (m.eyeY - r[6])) < 0.05f, "${m.id}: lowest point ${b[1]}, ground ${m.eyeY - r[6]}")
            // symmetric about x = 0, nose towards -Z: the eye is nearer the nose than the tail
            assertTrue(abs(b[0] + b[3]) < 0.05f, "${m.id}: not centred on x")
            assertTrue((m.eyeZ - b[2]) < (b[5] - m.eyeZ), "${m.id}: the eye is not in the front half")
            assertTrue(m.radius > 0.5f * (b[5] - b[2]) && m.radius < 0.75f * (b[5] - b[2]), "${m.id}: radius ${m.radius}")
        }
        val v = models.getValue("varrow")
        assertTrue(abs(v.rbxBounds[0] - 41.8f) < 0.01f && abs(v.rbxBounds[2] - 65.97f) < 0.01f, "VARROW BoundsSize")
    }

    @Test
    fun viewClassesSplitCockpitAndOutside() {
        for (m in models.values) {
            val hull = m.chunks[0]
            val cockpit = m.chunks[1]
            fun n(c: ShipModel.Chunk, pass: Int, cls: Int) = c.counts[pass * 3 + cls]
            // the pilot's arms and legs (CockpitOnly) are in the cockpit, seen only from the seat
            assertTrue(n(cockpit, ShipModel.PASS_OPAQUE, ShipModel.CLASS_COCKPIT) > 1000, "${m.id}: no pilot")
            assertEquals(0, n(hull, ShipModel.PASS_OPAQUE, ShipModel.CLASS_COCKPIT), "${m.id}: cockpit-only parts outside the cockpit")
            // the canopy (HideInside) is glass seen only from outside
            assertTrue(n(hull, ShipModel.PASS_GLASS, ShipModel.CLASS_EXTERIOR) > 0, "${m.id}: no canopy glass")
            // views: the cockpit view leaves out the canopy cage, the exterior view the pilot
            assertTrue(m.triangles(ShipModel.VIEW_EXTERIOR) > 15_000, "${m.id}: ${m.triangles(ShipModel.VIEW_EXTERIOR)} triangles outside")
            assertTrue(m.triangles(ShipModel.VIEW_COCKPIT) > 10_000)
            for (c in m.chunks) for (pass in 0..1) for (view in 0..1) {
                assertTrue(c.first(pass, view) + c.count(pass, view) <= c.indexCount)
            }
        }
        // ships whose hull shells hide from the seat (SeatHidden) draw far less from the cockpit
        val calder = models.getValue("calder")
        assertTrue(calder.triangles(ShipModel.VIEW_COCKPIT) < calder.triangles(ShipModel.VIEW_EXTERIOR) * 0.6f)
    }

    @Test
    fun cockpitsAreSharedByContent() {
        // identical cockpits are stored once (convert_ships.py dedupes by content hash)
        val files = models.values.map { it.chunkFiles[1] }.toSet()
        assertEquals(4, files.size, "cockpit files $files")
        assertEquals(models.getValue("varrow").chunkFiles[1], models.getValue("brannock").chunkFiles[1])
        assertEquals(models.getValue("varrow").chunkFiles[1], models.getValue("dornoch").chunkFiles[1])
        assertEquals(models.getValue("kessock").chunkFiles[1], models.getValue("morven").chunkFiles[1])
        for (m in models.values) assertEquals("", m.chunkFiles[0], "${m.id}: the hull is stored inline")
    }

    @Test
    fun nozzlesAndLights() {
        for (m in models.values) {
            assertTrue(m.nozzles.size in 3..8, "${m.id}: ${m.nozzles.size} nozzles")
            for (n in m.nozzles) {
                // at the stern, looking aft (+Z), a few metres across
                assertTrue(n.z > m.bounds[5] - 8f, "${m.id}: nozzle at z ${n.z}, stern at ${m.bounds[5]}")
                assertTrue(n.diameter in 1f..7f, "${m.id}: nozzle diameter ${n.diameter}")
                assertTrue(n.r + n.g + n.b > 0.5f, "${m.id}: dark engine colour")
            }
            assertTrue(m.lights.isNotEmpty(), "${m.id}: no lights")
        }
        // VARROW: main engine 5.04 m at the centre line, pods at ±13.4 m; red port, green starboard
        val v = models.getValue("varrow")
        assertTrue(v.nozzles.any { abs(it.x) < 0.01f && abs(it.diameter - 5.04f) < 0.01f })
        assertTrue(v.nozzles.count { abs(abs(it.x) - 13.4f) < 0.01f } == 2)
        val port = v.lights.first { it.x < -20f && it.type == ShipModel.LIGHT_POINT }
        val starboard = v.lights.first { it.x > 20f && it.type == ShipModel.LIGHT_POINT }
        assertTrue(port.r > 0.9f && port.g < 0.3f, "port light is red")
        assertTrue(starboard.g > 0.9f && starboard.r < 0.3f, "starboard light is green")
    }

    @Test
    fun parsingIsDeterministic() {
        for (e in ShipCatalog.all) {
            val a = ShipModel.load(e.id, ::read)
            val b = ShipModel.load(e.id, ::read)
            assertContentEquals(a.bounds, b.bounds)
            for (i in a.chunks.indices) {
                assertContentEquals(a.chunks[i].vertices, b.chunks[i].vertices)
                assertContentEquals(a.chunks[i].indices, b.chunks[i].indices)
                assertContentEquals(a.chunks[i].counts, b.chunks[i].counts)
            }
            assertEquals(a.boneCount, b.boneCount)
            for (i in a.bones.indices) assertContentEquals(a.bones[i].pivot, b.bones[i].pivot)
        }
    }

    @Test
    fun rejectsOtherFiles() {
        assertFailsWith<IllegalArgumentException> { ShipModel.parse(ByteArray(64)) { ByteArray(0) } }
        val bytes = read("ships/varrow.bin")
        bytes[4] = 9 // version
        assertFailsWith<IllegalArgumentException> { ShipModel.parse(bytes) { read("ships/$it") } }
        // a rig with more bones than the ship shader holds
        val rig = read("ships/varrow.bin")
        val at = 8 + (1 + 6) * 2 + 4 * (3 + 6 + 7) + 1 + (1 + "cockpit-brannock.bin".length) + 1
        assertEquals(models.getValue("varrow").boneCount, rig[at].toInt(), "bone count offset")
        rig[at] = (ShipModel.MAX_BONES + 1).toByte()
        assertFailsWith<IllegalArgumentException> { ShipModel.parse(rig) { read("ships/$it") } }
    }

    // ---------------------------------------------------------------- landing gear (ShipGear)

    private fun isIdentity(b: FloatArray, bone: Int, eps: Float = 1e-4f): Boolean {
        val o = bone * 12
        for (r in 0..2) for (c in 0..3) {
            val want = if (r == c) 1f else 0f
            if (abs(b[o + r * 4 + c] - want) > eps) return false
        }
        return true
    }

    private fun isZero(b: FloatArray, bone: Int): Boolean = (0 until 12).all { b[bone * 12 + it] == 0f }

    @Test
    fun gearRetractsLegsThenDoorsAndStows() {
        val m = models.getValue("varrow")
        assertTrue(m.hasDoors)
        assertEquals(24, m.boneCount)
        val rig = GearRig()
        rig.setModel(m, down = true)
        assertEquals(1f, rig.downFraction)
        for (b in 0 until m.boneCount) {
            if (b == ShipModel.BONE_CLOSED) assertTrue(isZero(rig.bones, b), "closed doors hidden while down")
            else assertTrue(isIdentity(rig.bones, b), "bone $b rests at the modelled (extended) pose")
        }
        val dt = 1f / 60f
        var t = 0f
        var doorsStartedAt = -1f
        while (!rig.stowed && t < 5f) {
            rig.update(dt, false, false)
            t += dt
            if (rig.doorT > 0f && doorsStartedAt < 0f) doorsStartedAt = t
            if (rig.doorT > 0f) assertEquals(1f, rig.legT, "doors close only after the legs are up")
        }
        assertTrue(abs(doorsStartedAt - GearRig.LEG_TIME) < 2 * dt, "doors started at $doorsStartedAt s")
        assertTrue(abs(t - (GearRig.LEG_TIME + GearRig.DOOR_TIME)) < 2 * dt, "stowed after $t s")
        assertEquals(0f, rig.downFraction)
        assertTrue(isIdentity(rig.bones, ShipModel.BONE_STATIC))
        assertTrue(isIdentity(rig.bones, ShipModel.BONE_CLOSED), "closed hull doors show when stowed")
        assertTrue(isZero(rig.bones, ShipModel.BONE_GEAR))
        for (b in 3 until m.boneCount) assertTrue(isZero(rig.bones, b), "stowed gear bone $b hidden")
        // and back down: doors open first, then the legs; ends exactly at the modelled pose
        t = 0f
        var legsMovedAt = -1f
        while (rig.downFraction < 1f && t < 5f) {
            rig.update(dt, true, false)
            t += dt
            if (rig.legT < 1f && legsMovedAt < 0f) legsMovedAt = t
            if (rig.legT < 1f) assertEquals(0f, rig.doorT, "legs move only once the doors are open")
        }
        assertTrue(abs(legsMovedAt - GearRig.DOOR_TIME) < 2 * dt, "legs started at $legsMovedAt s")
        for (b in 0 until m.boneCount) if (b != ShipModel.BONE_CLOSED) assertTrue(isIdentity(rig.bones, b), "bone $b back at rest")
    }

    @Test
    fun gearWithoutDoorsSkipsTheDoorPhase() {
        val m = models.getValue("brannock")
        kotlin.test.assertFalse(m.hasDoors)
        val rig = GearRig()
        rig.setModel(m, down = true)
        var t = 0f
        while (!rig.stowed && t < 5f) {
            rig.update(1f / 60f, false, false)
            t += 1f / 60f
        }
        assertTrue(abs(t - GearRig.LEG_TIME) < 0.05f, "no doors: stowed after the legs ($t s)")
        // the legs fold, then lift into the belly (GearLift): some bone moves its parts upwards
        rig.setModel(m, down = true)
        while (rig.legT < 0.99f) rig.update(1f / 60f, false, false)
        var lifted = false
        for (b in 3 until m.boneCount) if (rig.bones[b * 12 + 7] > 1f) lifted = true
        assertTrue(lifted, "no gear part lifted into the hull")
    }

    @Test
    fun gearPosesMoveTheLegs() {
        for (m in models.values) {
            val rig = GearRig()
            rig.setModel(m, down = true)
            while (rig.legT < 0.5f) rig.update(1f / 60f, false, false)
            var moved = 0
            for (b in 3 until m.boneCount) if (!isIdentity(rig.bones, b, 1e-3f)) moved++
            assertTrue(moved >= (m.boneCount - 3) / 2, "${m.id}: only $moved of ${m.boneCount - 3} gear bones move half-way")
            // instant (the Roblox cockpit view): straight to the end
            rig.update(0f, true, true)
            assertEquals(1f, rig.downFraction)
        }
    }

    @Test
    fun gearRodsStayAttached() {
        // a Link rod turns about its hull end and stretches so that its other end follows the leg
        // (ShipGear "Link"): posed half-way, the hull end stays put and the far end sits where the
        // leg carried the rod's attachment point
        val p = FloatArray(3)
        val q = FloatArray(3)
        val pivot = FloatArray(12)
        val inv = FloatArray(12)
        val rot = FloatArray(12)
        val turn = FloatArray(12)
        var rods = 0
        var worstHull = 0f
        var worstLeg = 0f
        for (m in models.values) {
            val rig = GearRig()
            rig.setModel(m, down = true)
            while (rig.legT < 0.5f) rig.update(1f / 60f, false, false)
            for (b in 3 until m.boneCount) {
                val bone = m.bones[b - 3]
                if (bone.kind != ShipModel.KIND_LINK) continue
                rods++
                val base = bone.base!!
                val ax = bone.axis
                // the rod's ends: its centre -+ half its length along its long axis (a rotation column)
                val half = bone.size!![ax] * 0.5f
                val dx = base[3 + ax] * half
                val dy = base[6 + ax] * half
                val dz = base[9 + ax] * half
                val fixed = bone.fixed!!
                val move = bone.move!!
                val bm = rig.bones.copyOfRange(b * 12, b * 12 + 12)
                val rise = if (bone.lift != 0f) GearRig.smooth((rig.legT - bone.liftFrom) / (1f - bone.liftFrom)) else 0f
                val liftY = bone.lift * rise
                // where the leg's turn takes the rod's leg point (ShipGear: turn * pivot^-1 * move)
                GearRig.cframe(bone.pivot, pivot)
                GearRig.rigidInverse(pivot, inv)
                GearRig.rotX(bone.rad * GearRig.smooth(rig.legT / bone.swingEnd), rot)
                GearRig.mul(pivot, rot, turn)
                GearRig.transform(inv, move[0], move[1], move[2], q)
                GearRig.transform(turn, q[0], q[1], q[2], q)
                // a telescopic rod is two parts (sleeve at the hull, piston at the leg): check the
                // ends that sit on an attachment point
                for (end in 0..1) {
                    val sgn = if (end == 0) -1f else 1f
                    val ex = base[0] + sgn * dx
                    val ey = base[1] + sgn * dy
                    val ez = base[2] + sgn * dz
                    GearRig.transform(bm, ex, ey, ez, p)
                    val atFixed = sqrt((ex - fixed[0]) * (ex - fixed[0]) + (ey - fixed[1]) * (ey - fixed[1]) + (ez - fixed[2]) * (ez - fixed[2]))
                    val atMove = sqrt((ex - move[0]) * (ex - move[0]) + (ey - move[1]) * (ey - move[1]) + (ez - move[2]) * (ez - move[2]))
                    if (atFixed < 0.01f) {
                        // the hull end: only the lift moves it
                        worstHull = maxOf(worstHull, sqrt((p[0] - fixed[0]) * (p[0] - fixed[0]) + (p[1] - fixed[1] - liftY) * (p[1] - fixed[1] - liftY) + (p[2] - fixed[2]) * (p[2] - fixed[2])))
                    } else if (atMove < 0.01f) {
                        worstLeg = maxOf(worstLeg, sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1] - liftY) * (p[1] - q[1] - liftY) + (p[2] - q[2]) * (p[2] - q[2])))
                    }
                }
            }
        }
        println("gear rods: $rods, hull ends moved at most %.4f m, leg ends off by at most %.4f m".format(worstHull, worstLeg))
        assertTrue(rods > 40, "only $rods rods")
        assertTrue(worstHull < 0.01f, "a rod's hull end moved $worstHull m")
        assertTrue(worstLeg < 0.01f, "a rod's leg end is $worstLeg m off the leg")
    }

    @Test
    fun gearUpdateDoesNotAllocate() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val rig = GearRig()
        rig.setModel(models.getValue("brannock"), down = true)
        var down = false
        fun cycle() {
            for (i in 0 until 160) rig.update(1f / 60f, down, false)
            down = !down
        }
        for (i in 0 until 50) cycle()
        val a = mx.currentThreadAllocatedBytes
        for (i in 0 until 200) cycle()
        val b = mx.currentThreadAllocatedBytes
        assertTrue((b - a) / 200.0 < 1.0, "GearRig.update allocates ${(b - a) / 200.0} bytes per cycle")
    }

    // ---------------------------------------------------------------- collision shape (ShipStation.probeFor)

    @Test
    fun specsTakeTheRobloxCollisionProbes() {
        val v = ShipSpec.fromModel(ShipCatalog.get(0), models.getValue("varrow"))
        assertEquals("VARROW", v.name)
        assertEquals(22_000, v.price)
        // radius = smallest BoundsSize side / 2 (21.01 / 2); five probes on a 42 m wide hull
        assertTrue(abs(v.collisionRadius - 10.507f) < 0.01f, "radius ${v.collisionRadius}")
        assertEquals(15, v.probes.size)
        // centre = SeatOffset + RootToBoundsCenter, ends at +-(33 - r) along Z, sides at +-(20.9 - r)
        assertTrue(abs(v.probes[2] - 22.615f) < 0.01f && abs(v.probes[1] - 0.007f) < 0.01f)
        assertTrue(abs(v.probes[5] - (22.615f - (65.97f / 2f - 10.507f))) < 0.01f)
        assertTrue(abs(v.probes[9] + (20.9f - 10.507f)) < 0.01f)
        // stands RootToGround - SeatOffset.Y = 11 - 0.5 above the ground
        assertTrue(abs(v.groundClearance - 10.5f) < 0.01f)
        // the probes reach the ground exactly when standing: lowest probe bottom = -ground
        for (m in models.values) {
            val s = ShipSpec.fromModel(ShipCatalog.all.first { it.id == m.id }, m)
            var low = Float.MAX_VALUE
            var i = 0
            while (i < s.probes.size) {
                low = minOf(low, s.probes[i + 1] - s.collisionRadius)
                i += 3
            }
            assertTrue(abs(low + s.groundClearance) < 0.6f, "${m.id}: probes bottom at $low, ground clearance ${s.groundClearance}")
            assertTrue(s.reach < m.radius * 1.6f && s.reach > s.collisionRadius)
            // the hull's centre (auto-dock and launches place it) is the first probe
            assertEquals(m.centreX, s.centreX)
            assertEquals(m.centreY, s.centreY)
            assertEquals(m.centreZ, s.centreZ)
        }
    }

    @Test
    fun everyHullFitsEveryStationSlot() {
        // auto-dock parks the hull's centre on the slot axis and flies without collision, so every
        // hull must clear every slot, as every Roblox hangar takes every ship (Game refuses one that
        // does not fit)
        val slot = Vec3()
        var tightest = Float.MAX_VALUE
        var tightestAt = ""
        for (m in models.values) {
            val s = ShipSpec.fromModel(ShipCatalog.all.first { it.id == m.id }, m)
            // the real outline around the hull's centre, gear down (its largest)
            val halfW = maxOf(m.centreX - m.bounds[0], m.bounds[3] - m.centreX)
            val halfH = maxOf(m.centreY - m.bounds[1], m.bounds[4] - m.centreY)
            for (model in 0 until StationMesh.MODELS) {
                assertTrue(StationMesh.slotFits(model, s.sizeX, s.sizeY), "${m.id} (${s.sizeX} x ${s.sizeY} m) does not fit station model $model")
                StationMesh.slotSize(model, slot)
                val room = minOf(slot.x - halfW, slot.y - halfH)
                assertTrue(room >= StationMesh.SLOT_CLEARANCE - 0.5f, "${m.id} in station model $model: %.2f m to the slot's edge".format(room))
                if (room < tightest) {
                    tightest = room
                    tightestAt = "${m.id} in station model $model"
                }
            }
        }
        println("tightest slot fit: %s, %.2f m to the opening's edge".format(tightestAt, tightest))
    }
}
