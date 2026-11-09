package com.starwake.core.content

import com.starwake.core.station.StationColliders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A space station (or the construction site) from the Roblox game, parsed from the converter's
 * binary asset (format documented in tools/roblox/convert_stations.py): the baked hull and paint in
 * one quantised mesh with index ranges per pass, detail class and group, the rotating groups, lamp
 * slots, pads, approach points, the hangar box, colliders, lights, sign labels and beams.
 *
 * Station space is the Roblox model's root space: metres, +X right, +Y up, the hangar mouth faces
 * -Z (the station's "look", which points at its host planet). Every query here is in that space;
 * StationInstance adds the runtime state (spin angles, lamps) and StationPlacement the world pose.
 *
 * Parsing allocates (once per model); nothing here changes after loading.
 */
class StationModel private constructor(
    /** Roblox model name: "RingStation", ..., "Site". */
    val type: String,
    /** The model's BuiltBy attribute ("STATION", "GARDEN", ..., "CONSTRUCTION"). */
    val builtBy: String,
    /** True for the construction site: [groups] 1..4 are its build stages, nothing spins. */
    val isSite: Boolean,
    /** Attribute Radius (m): collision and visibility reach around the root (ShipStation.readModel). */
    val radius: Float,
    /** Hangar box size and centre (attributes HangarSize, HangarCentre); zero for the site. */
    val hangarSize: FloatArray,
    val hangarCentre: FloatArray,
    /** Attribute MouthSize (width, height, depth); zero for the site. */
    val mouthSize: FloatArray,
    /** Box around every rendered vertex: min x, y, z, max x, y, z. */
    val bounds: FloatArray,
    val hasApproach: Boolean,
    /** Approach points Outer, Mouth, Inner (x, y, z each). */
    val approach: FloatArray,
    /** Group 0 is the static hull; 1.. spin (stations) or are build stages (site). */
    val groups: Array<Group>,
    /** By pad index 1..6 (pads[i - 1]); empty for the site. */
    val pads: Array<Pad>,
    /** Lamp slot s is lamps[s - 1]; slot 0 means "no lamp". */
    val lamps: Array<Lamp>,
    val colliders: StationColliders,
    val lights: Array<Light>,
    val labels: Array<Label>,
    val beams: Array<Beam>,
    val mesh: Mesh,
) {
    /** A rotating group (stations) or a build stage (site). Group 0 has no motion. */
    class Group(
        val name: String,
        /** Spin rate (Roblox attribute SpinDegreesPerSecond, the Ring's default 2). */
        val degreesPerSecond: Float,
        /** Unit axis and the point it passes through, station space. */
        val axisX: Float, val axisY: Float, val axisZ: Float,
        val centreX: Float, val centreY: Float, val centreZ: Float,
    ) {
        val radiansPerSecond = degreesPerSecond * (Math.PI.toFloat() / 180f)
    }

    /**
     * A landing pad: [top] is the CFrame of its top face centre (x, y, z, then the rotation row-major
     * like Roblox's R00..R22; identity on every model: the pad "looks" -Z, at the mouth).
     */
    class Pad(val index: Int, val lampSlot: Int, val sizeX: Float, val sizeY: Float, val sizeZ: Float, val top: FloatArray) {
        val x get() = top[0]
        val y get() = top[1]
        val z get() = top[2]
    }

    /** A lamp slot: a GPU-switchable colour/opacity shared by the parts that carry it. */
    class Lamp(
        val kind: Int,
        /** PASS_OPAQUE (colour only: accent, PAPI) or PASS_LAMP (blended: blinkers, pad lamps). */
        val pass: Int,
        /** Pad index for KIND_PAD. */
        val pad: Int,
        /** Colour as built: sRGB bytes 0..255 and opacity (1 - Transparency) 0..255. */
        val r: Int, val g: Int, val b: Int, val a: Int,
        /** Blinkers: period (s), phase (s; negative = not set in Roblox: the instance picks one), duty 0.02..0.98. */
        val period: Float, val phase: Float, val duty: Float,
        /** PAPI: threshold elevation (radians) and the lamp's position (station space). */
        val papiThreshold: Float, val papiX: Float, val papiY: Float, val papiZ: Float,
        /** Number of parts that share the slot. */
        val parts: Int,
    )

    /** A Roblox light (PointLight, SpotLight, SurfaceLight); colour sRGB 0..1. */
    class Light(
        val type: Int, val group: Int,
        val r: Float, val g: Float, val b: Float, val shadows: Boolean,
        val brightness: Float, val range: Float, val angle: Float,
        val x: Float, val y: Float, val z: Float,
        val dirX: Float, val dirY: Float, val dirZ: Float,
        val surfaceW: Float, val surfaceH: Float,
    )

    /**
     * A sign label (Roblox TextLabel on a SurfaceGui), laid out at runtime with the game's SDF font:
     * the box spans [origin] + s * [u] + t * [v] for s, t in 0..1 (u along the text, v downwards).
     */
    class Label(
        val text: String,
        /** Font face of the game's atlas (FontData.UI, TITLE, MONO). */
        val font: Int,
        val flags: Int,
        /** 0 left, 1 right, 2 centre. */
        val xAlign: Int,
        /** 0 top, 1 centre, 2 bottom. */
        val yAlign: Int,
        val group: Int,
        /** Paint depth layer the label sits on (1..6). */
        val layer: Int,
        /** Pad index for a pad number label. */
        val pad: Int,
        /** Text colour, sRGB 0..255, and opacity 0..255. */
        val r: Int, val g: Int, val b: Int, val a: Int,
        val lightInfluence: Float,
        val brightness: Float,
        /** Roblox TextSize (px) and the SurfaceGui's pixels per metre. */
        val textSize: Float,
        val pxPerMetre: Float,
        val origin: FloatArray,
        val u: FloatArray,
        val v: FloatArray,
        val normal: FloatArray,
    ) {
        val isStationName get() = flags and FLAG_NAME != 0
        val isPadNumber get() = flags and FLAG_PAD != 0
        val scaled get() = flags and FLAG_SCALED != 0
        val wrapped get() = flags and FLAG_WRAPPED != 0
    }

    /** A light beam (Roblox Beam, FaceCamera): from p0 to p1, widths, colour, transparency at 0, 0.6, 1. */
    class Beam(
        val group: Int,
        val p0: FloatArray, val p1: FloatArray,
        val width0: Float, val width1: Float,
        val r: Int, val g: Int, val b: Int,
        val transparency: FloatArray,
    )

    /**
     * The quantised mesh: [VERTEX_BYTES]-byte vertices (see the converter) and indices in the order
     * pass (opaque, translucent, lamp) / LOD class (2, 1, 0) / group. Detail level L draws classes
     * 2 down to L of a pass, which is one prefix of that pass's range.
     */
    class Mesh(
        val originX: Float, val originY: Float, val originZ: Float,
        /** Metres per quantum of the 19-bit position grid. */
        val step: Float,
        val vertexCount: Int,
        val indexCount: Int,
        val indexSize: Int,
        val groupCount: Int,
        /** Index counts [(pass * 3 + classSlot) * groupCount + group], classSlot 0 = class 2. */
        val counts: IntArray,
        val vertices: ByteArray,
        val indices: ByteArray,
    ) {
        private val passStart = IntArray(PASSES + 1)

        init {
            var n = 0
            for (p in 0 until PASSES) {
                passStart[p] = n
                for (c in 0 until 3) for (g in 0 until groupCount) n += counts[(p * 3 + c) * groupCount + g]
            }
            passStart[PASSES] = n
        }

        /** First index of [pass]. */
        fun first(pass: Int): Int = passStart[pass]

        /** Indices [pass] draws at detail level [lod] (0 full .. 2 coarsest). */
        fun count(pass: Int, lod: Int): Int {
            var n = 0
            for (c in 0..(2 - lod)) for (g in 0 until groupCount) n += counts[(pass * 3 + c) * groupCount + g]
            return n
        }

        /** First index of the triangles of [pass], LOD class [cls] (0..2) and [group]. */
        fun first(pass: Int, cls: Int, group: Int): Int {
            var n = passStart[pass]
            val slot = 2 - cls
            for (c in 0 until slot) for (g in 0 until groupCount) n += counts[(pass * 3 + c) * groupCount + g]
            for (g in 0 until group) n += counts[(pass * 3 + slot) * groupCount + g]
            return n
        }

        /** Index count of [pass], LOD class [cls] and [group]. */
        fun count(pass: Int, cls: Int, group: Int): Int = counts[(pass * 3 + (2 - cls)) * groupCount + group]

        /** Triangles drawn at detail level [lod], all passes. */
        fun triangles(lod: Int): Int {
            var n = 0
            for (p in 0 until PASSES) n += count(p, lod)
            return n / 3
        }
    }

    /** Lamp slot of the station's accent colour (0 if no part carries Accent). */
    val accentSlot: Int = lamps.indexOfFirst { it.kind == LAMP_ACCENT } + 1

    /** Pad [index] (1..6), or null. */
    fun pad(index: Int): Pad? = pads.firstOrNull { it.index == index }

    /**
     * Ship origin pose on pad [index] in station space (ShipStation.padPose: the pad's top face
     * raised by [ground], the ship origin's height above its gear): position into [outPos] (x, y, z),
     * rotation (row-major 3x3) into [outRot]. False when there is no such pad.
     */
    fun padPose(index: Int, ground: Float, outPos: FloatArray, outRot: FloatArray): Boolean {
        val p = pad(index) ?: return false
        val t = p.top
        outPos[0] = t[0] + t[4] * ground
        outPos[1] = t[1] + t[7] * ground
        outPos[2] = t[2] + t[10] * ground
        for (i in 0 until 9) outRot[i] = t[3 + i]
        return true
    }

    /**
     * ShipStation.isInHangar: inside the hangar box (with Roblox's 20 m of extra depth at both ends).
     * Point in station space.
     */
    fun isInHangar(x: Float, y: Float, z: Float): Boolean {
        if (hangarSize[0] <= 0f) return false
        val rx = x - hangarCentre[0]
        val ry = y - hangarCentre[1]
        val rz = z - hangarCentre[2]
        return kotlin.math.abs(rx) <= hangarSize[0] * 0.5f && kotlin.math.abs(ry) <= hangarSize[1] * 0.5f &&
            kotlin.math.abs(rz) <= hangarSize[2] * 0.5f + HANGAR_Z_SLACK
    }

    /** Approach point [which] (APPROACH_OUTER, _MOUTH, _INNER) into [out] (x, y, z). False for the site. */
    fun approachPoint(which: Int, out: FloatArray): Boolean {
        if (!hasApproach) return false
        out[0] = approach[which * 3]
        out[1] = approach[which * 3 + 1]
        out[2] = approach[which * 3 + 2]
        return true
    }

    /** Radius around the root that holds every rendered vertex. */
    val boundingRadius: Float = run {
        var r = 0f
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val x = bounds[cx * 3]
            val y = bounds[1 + cy * 3]
            val z = bounds[2 + cz * 3]
            r = maxOf(r, kotlin.math.sqrt(x * x + y * y + z * z))
        }
        r
    }

    companion object {
        const val VERSION = 1
        const val VERTEX_BYTES = 16
        const val PASS_OPAQUE = 0
        const val PASS_TRANSLUCENT = 1
        const val PASS_LAMP = 2
        const val PASSES = 3
        const val LAMP_ACCENT = 0
        const val LAMP_PAD = 1
        const val LAMP_PAPI = 2
        const val LAMP_BLINK = 3
        const val APPROACH_OUTER = 0
        const val APPROACH_MOUTH = 1
        const val APPROACH_INNER = 2
        const val LIGHT_POINT = 0
        const val LIGHT_SPOT = 1
        const val LIGHT_SURFACE = 2
        const val FLAG_NAME = 1
        const val FLAG_PAD = 2
        const val FLAG_SCALED = 4
        const val FLAG_WRAPPED = 8

        /** ShipStation.isInHangar's extra depth (m) at both ends of the box. */
        const val HANGAR_Z_SLACK = 20f

        /** Most groups a model may have (3 bits in the vertex). */
        const val MAX_GROUPS = 8

        /** Shading classes written by the converter (vertex material id). */
        val MATERIAL_NAMES = arrayOf(
            "smooth", "rough", "metal", "plate", "foil", "organic", "ice", "glass", "neon", "forcefield",
            "paint1", "paint2", "paint3", "paint4", "paint5", "paint6",
        )
        const val MAT_GLASS = 7
        const val MAT_NEON = 8
        const val MAT_FORCEFIELD = 9
        const val MAT_PAINT1 = 10
        const val MAX_LAYERS = 6

        /** The six station types and the site, as named in Roblox (and the asset files). */
        val TYPES = arrayOf("RingStation", "GardenStation", "FoundryStation", "SpireStation", "HollowStation", "RefineryStation")
        const val SITE = "Site"

        /** Asset path of a model. */
        fun path(type: String) = "stations/$type.stm"

        /** Reads stations/<type>.stm through [read] (asset path -> bytes). */
        fun load(type: String, read: (String) -> ByteArray): StationModel = parse(read(path(type)))

        fun parse(data: ByteArray): StationModel {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            for (c in "USTM") require(b.get().toInt() == c.code) { "not a station asset" }
            val version = b.short.toInt() and 0xFFFF
            require(version == VERSION) { "station asset version $version, expected $VERSION" }
            val flags = b.short.toInt() and 0xFFFF
            val type = str(b)
            val builtBy = str(b)
            val radius = b.float
            val hangarSize = floats(b, 3)
            val hangarCentre = floats(b, 3)
            val mouthSize = floats(b, 3)
            val bounds = floats(b, 6)
            val hasApproach = b.get().toInt() != 0
            val approach = floats(b, 9)
            val groupCount = b.get().toInt() and 0xFF
            require(groupCount in 1..MAX_GROUPS) { "bad group count $groupCount" }
            val groups = Array(groupCount) { g ->
                if (g == 0) {
                    Group("static", 0f, 0f, 0f, 1f, 0f, 0f, 0f)
                } else {
                    val name = str(b)
                    Group(name, b.float, b.float, b.float, b.float, b.float, b.float, b.float)
                }
            }
            val pads = Array(b.get().toInt() and 0xFF) {
                val index = b.get().toInt() and 0xFF
                val slot = b.get().toInt() and 0xFF
                Pad(index, slot, b.float, b.float, b.float, floats(b, 12))
            }
            val lamps = Array(b.get().toInt() and 0xFF) {
                val kind = b.get().toInt() and 0xFF
                val pass = b.get().toInt() and 0xFF
                val pad = b.get().toInt() and 0xFF
                b.get()
                val r = b.get().toInt() and 0xFF
                val g = b.get().toInt() and 0xFF
                val bl = b.get().toInt() and 0xFF
                val a = b.get().toInt() and 0xFF
                Lamp(kind, pass, pad, r, g, bl, a, b.float, b.float, b.float, b.float, b.float, b.float, b.float, b.short.toInt() and 0xFFFF)
            }
            val colliderCount = b.short.toInt() and 0xFFFF
            val shapes = IntArray(colliderCount)
            val cgroups = IntArray(colliderCount)
            val frames = FloatArray(colliderCount * 12)
            val sizes = FloatArray(colliderCount * 3)
            for (i in 0 until colliderCount) {
                shapes[i] = b.get().toInt() and 0xFF
                cgroups[i] = b.get().toInt() and 0xFF
                for (k in 0 until 12) frames[i * 12 + k] = b.float
                for (k in 0 until 3) sizes[i * 3 + k] = b.float
            }
            val lights = Array(b.get().toInt() and 0xFF) {
                val t = b.get().toInt() and 0xFF
                val g = b.get().toInt() and 0xFF
                val r = unorm(b.get())
                val gg = unorm(b.get())
                val bl = unorm(b.get())
                val shadows = b.get().toInt() != 0
                Light(t, g, r, gg, bl, shadows, b.float, b.float, b.float, b.float, b.float, b.float, b.float, b.float, b.float, b.float, b.float)
            }
            val labels = Array(b.short.toInt() and 0xFFFF) {
                val text = str(b)
                val font = b.get().toInt() and 0xFF
                val lf = b.get().toInt() and 0xFF
                val xa = b.get().toInt() and 0xFF
                val ya = b.get().toInt() and 0xFF
                val g = b.get().toInt() and 0xFF
                val layer = b.get().toInt() and 0xFF
                val pad = b.get().toInt() and 0xFF
                b.get()
                val r = b.get().toInt() and 0xFF
                val gg = b.get().toInt() and 0xFF
                val bl = b.get().toInt() and 0xFF
                val a = b.get().toInt() and 0xFF
                Label(
                    text, font, lf, xa, ya, g, layer, pad, r, gg, bl, a, b.float, b.float, b.float, b.float,
                    floats(b, 3), floats(b, 3), floats(b, 3), floats(b, 3),
                )
            }
            val beams = Array(b.get().toInt() and 0xFF) {
                val g = b.get().toInt() and 0xFF
                val p0 = floats(b, 3)
                val p1 = floats(b, 3)
                val w0 = b.float
                val w1 = b.float
                val r = b.get().toInt() and 0xFF
                val gg = b.get().toInt() and 0xFF
                val bl = b.get().toInt() and 0xFF
                b.get()
                Beam(g, p0, p1, w0, w1, r, gg, bl, floats(b, 3))
            }
            val mesh = mesh(b, groupCount)
            require(!b.hasRemaining()) { "${b.remaining()} trailing bytes in $type" }
            val colliders = StationColliders(shapes, cgroups, frames, sizes)
            return StationModel(
                type, builtBy, flags and 1 != 0, radius, hangarSize, hangarCentre, mouthSize, bounds, hasApproach, approach,
                groups, pads, lamps, colliders, lights, labels, beams, mesh,
            )
        }

        private fun mesh(b: ByteBuffer, groupCount: Int): Mesh {
            val ox = b.float
            val oy = b.float
            val oz = b.float
            val step = b.float
            val vertexCount = b.int
            val indexCount = b.int
            val indexSize = b.get().toInt()
            b.get(); b.get(); b.get()
            require(indexSize == 2 || indexSize == 4) { "bad index size $indexSize" }
            val counts = IntArray(PASSES * 3 * groupCount) { b.int }
            require(counts.sum() == indexCount) { "index ranges ${counts.sum()} != $indexCount" }
            // 16 planes of vertexCount bytes -> interleaved 16-byte vertices; x, y, z words are deltas
            val planes = ByteArray(vertexCount * 16)
            b.get(planes)
            val v = ByteArray(vertexCount * VERTEX_BYTES)
            var px = 0
            var py = 0
            var pz = 0
            for (i in 0 until vertexCount) {
                val o = i * VERTEX_BYTES
                px = (px + word(planes, 0, vertexCount, i)) and 0xFFFF
                py = (py + word(planes, 2, vertexCount, i)) and 0xFFFF
                pz = (pz + word(planes, 4, vertexCount, i)) and 0xFFFF
                val w = word(planes, 6, vertexCount, i)
                putShort(v, o, px)
                putShort(v, o + 2, py)
                putShort(v, o + 4, pz)
                putShort(v, o + 6, w)
                for (k in 0 until 8) v[o + 8 + k] = planes[(8 + k) * vertexCount + i]
            }
            val ip = ByteArray(indexCount * indexSize)
            b.get(ip)
            val pad = (4 - (ip.size and 3)) and 3
            b.position(b.position() + pad)
            val idx = ByteArray(indexCount * indexSize)
            var last = 0L
            val mask = if (indexSize == 2) 0xFFFFL else 0xFFFFFFFFL
            for (i in 0 until indexCount) {
                var d = 0L
                for (k in 0 until indexSize) d = d or ((ip[k * indexCount + i].toLong() and 0xFF) shl (8 * k))
                last = (last + d) and mask
                val o = i * indexSize
                for (k in 0 until indexSize) idx[o + k] = (last shr (8 * k)).toByte()
                require(last < vertexCount) { "index $last out of range ($vertexCount vertices)" }
            }
            return Mesh(ox, oy, oz, step, vertexCount, indexCount, indexSize, groupCount, counts, v, idx)
        }

        private fun word(planes: ByteArray, plane: Int, n: Int, i: Int): Int =
            (planes[plane * n + i].toInt() and 0xFF) or ((planes[(plane + 1) * n + i].toInt() and 0xFF) shl 8)

        private fun putShort(a: ByteArray, o: Int, v: Int) {
            a[o] = v.toByte()
            a[o + 1] = (v shr 8).toByte()
        }

        private fun floats(b: ByteBuffer, n: Int) = FloatArray(n) { b.float }

        private fun str(b: ByteBuffer): String {
            val n = b.get().toInt() and 0xFF
            val bytes = ByteArray(n)
            b.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun unorm(v: Byte): Float = (v.toInt() and 0xFF) / 255f
    }
}
