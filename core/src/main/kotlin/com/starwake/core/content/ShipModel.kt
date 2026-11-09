package com.starwake.core.content

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A ship from the Roblox game, parsed from the converter's binary assets (format documented in
 * tools/roblox/convert_ships.py): the baked hull in chunks of quantised vertices, the landing-gear
 * rig, engine nozzles, lights and the Roblox model attributes.
 *
 * Ship space is the flight model's: origin = ShipHull's ship origin (the pivot), +X right, +Y up,
 * nose towards -Z, metres. The pilot's eye is at [eyeX], [eyeY], [eyeZ] (ShipConfig.Camera.SeatOffset).
 *
 * Parsing allocates (once per ship); nothing here is touched per frame except plain fields.
 */
class ShipModel private constructor(
    val id: String,
    val name: String,
    val eyeX: Float,
    val eyeY: Float,
    val eyeZ: Float,
    /** Box around every vertex of the ship, ship space: min x, y, z, max x, y, z. */
    val bounds: FloatArray,
    /** Roblox model attributes: BoundsSize (x, y, z), RootToBoundsCenter (x, y, z, from the eye), RootToGround. */
    val rbxBounds: FloatArray,
    val chunks: Array<Chunk>,
    /** Per chunk: the shared file it was read from ("" = stored in the ship's own file). */
    val chunkFiles: Array<String>,
    val hasDoors: Boolean,
    /** Number of bones including the three fixed ones (see [BONE_STATIC]). */
    val boneCount: Int,
    /** Rig of bones 3 until [boneCount] (index b - 3). */
    val bones: Array<Bone>,
    val nozzles: Array<Nozzle>,
    val lights: Array<Light>,
) {
    /** Quantised mesh data: one vertex/index buffer drawn with one call per pass and view. */
    class Chunk(
        val originX: Float,
        val originY: Float,
        val originZ: Float,
        /** Metres per quantisation step (the same on all axes). */
        val scale: Float,
        val vertexCount: Int,
        val indexCount: Int,
        /** 2 (unsigned short) or 4 (unsigned int). */
        val indexSize: Int,
        /** Index counts per pass and class: [pass * 3 + class]. */
        val counts: IntArray,
        /** [VERTEX_BYTES] per vertex, little-endian, ready for the GPU. */
        val vertices: ByteArray,
        /** indexCount * indexSize bytes, little-endian. */
        val indices: ByteArray,
    ) {
        /** First index of [view]'s range in [pass]. */
        fun first(pass: Int, view: Int): Int {
            val passStart = if (pass == PASS_OPAQUE) 0 else counts[0] + counts[1] + counts[2]
            return passStart + if (view == VIEW_EXTERIOR) counts[pass * 3 + CLASS_COCKPIT] else 0
        }

        /** Number of indices [view] draws in [pass]. */
        fun count(pass: Int, view: Int): Int {
            val b = pass * 3
            return if (view == VIEW_EXTERIOR) counts[b + CLASS_BOTH] + counts[b + CLASS_EXTERIOR] else counts[b + CLASS_COCKPIT] + counts[b + CLASS_BOTH]
        }
    }

    /**
     * One rigid landing-gear group or one Link rod (ShipGear.build), in ship space. CFrames are
     * 12 floats: position, then the 3x3 rotation row-major (Roblox's R00..R22).
     */
    class Bone(
        val kind: Int,
        val pivot: FloatArray,
        val rad: Float,
        val lift: Float,
        val liftFrom: Float,
        val swingEnd: Float,
        val ankle: FloatArray,
        val fixed: FloatArray?,
        val move: FloatArray?,
        val base: FloatArray?,
        val size: FloatArray?,
        val axis: Int,
    )

    /** An engine glow disc (attribute EngineGlow): exhaust plume start, along +Z. */
    class Nozzle(
        val x: Float,
        val y: Float,
        val z: Float,
        val diameter: Float,
        /** Disc colour, sRGB 0..1 (the glow sprites blend in display space). */
        val r: Float,
        val g: Float,
        val b: Float,
        val lightBrightness: Float,
        val lightRange: Float,
    )

    /** A Roblox light (PointLight, SpotLight, SurfaceLight) on an outside part; colour sRGB 0..1. */
    class Light(
        val type: Int,
        val cls: Int,
        val r: Float,
        val g: Float,
        val b: Float,
        val enabled: Boolean,
        val x: Float,
        val y: Float,
        val z: Float,
        val dirX: Float,
        val dirY: Float,
        val dirZ: Float,
        val brightness: Float,
        val range: Float,
        val angle: Float,
        val sizeX: Float,
        val sizeY: Float,
        val sizeZ: Float,
    )

    /** Centre of the Roblox bounding box in ship space. */
    val centreX = eyeX + rbxBounds[3]
    val centreY = eyeY + rbxBounds[4]
    val centreZ = eyeZ + rbxBounds[5]

    /** Radius of the sphere around the bounds centre that holds every vertex. */
    val radius: Float

    init {
        var r = 0f
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            val dx = bounds[cx * 3] - centreX
            val dy = bounds[1 + cy * 3] - centreY
            val dz = bounds[2 + cz * 3] - centreZ
            r = maxOf(r, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz))
        }
        radius = r
    }

    /** Triangles [view] draws (both passes, all chunks). */
    fun triangles(view: Int): Int {
        var n = 0
        for (c in chunks) n += c.count(PASS_OPAQUE, view) + c.count(PASS_GLASS, view)
        return n / 3
    }

    companion object {
        const val VERSION = 1
        const val VERTEX_BYTES = 16
        const val PASS_OPAQUE = 0
        const val PASS_GLASS = 1
        const val CLASS_COCKPIT = 0
        const val CLASS_BOTH = 1
        const val CLASS_EXTERIOR = 2
        const val VIEW_EXTERIOR = 0
        const val VIEW_COCKPIT = 1

        /** Most bones a ship may have, the three fixed ones included: what the ship shader holds. */
        const val MAX_BONES = 48

        /** Bone 0: every part that never moves. */
        const val BONE_STATIC = 0

        /** Bone 1: landing-gear parts without joint data (bays): stay put, hidden when stowed. */
        const val BONE_GEAR = 1

        /** Bone 2: closed gear doors on the hull (GearClosed): shown only when the gear is stowed. */
        const val BONE_CLOSED = 2
        const val KIND_LEG = 1
        const val KIND_FOOT = 2
        const val KIND_MOUNT = 3
        const val KIND_DOOR = 4
        const val KIND_LINK = 5
        const val LIGHT_POINT = 0
        const val LIGHT_SPOT = 1
        const val LIGHT_SURFACE = 2

        /** Material ids written by the converter (Roblox material names). */
        val MATERIAL_NAMES = arrayOf(
            "smoothplastic", "plastic", "metal", "diamondplate", "foil", "glass", "fabric", "rubber",
            "leather", "wood", "slate", "basalt", "rock", "neon", "forcefield", "engine",
        )
        const val MAT_NEON = 13
        const val MAT_FORCEFIELD = 14
        const val MAT_ENGINE = 15

        /**
         * Shading per material id, 4 floats each: metallic, emissive (unlit glow, Roblox Neon),
         * engine-driven (emission follows the throttle), roughness.
         */
        val MATERIAL_PARAMS = floatArrayOf(
            0.00f, 0f, 0f, 0.38f, // SmoothPlastic
            0.00f, 0f, 0f, 0.55f, // Plastic
            0.25f, 0f, 0f, 0.48f, // Metal (Roblox's keeps most of the part colour as diffuse)
            0.40f, 0f, 0f, 0.42f, // DiamondPlate
            0.60f, 0f, 0f, 0.30f, // Foil
            0.30f, 0f, 0f, 0.06f, // Glass
            0.00f, 0f, 0f, 0.92f, // Fabric
            0.00f, 0f, 0f, 0.85f, // Rubber
            0.00f, 0f, 0f, 0.62f, // Leather
            0.00f, 0f, 0f, 0.75f, // Wood
            0.00f, 0f, 0f, 0.90f, // Slate
            0.00f, 0f, 0f, 0.90f, // Basalt
            0.00f, 0f, 0f, 0.92f, // Rock
            0.00f, 1.5f, 0f, 1f, // Neon
            0.00f, 1.0f, 0f, 1f, // ForceField: a see-through glow added to the scene (Shaders.shipFs)
            0.00f, 1.0f, 1f, 1f, // engine glow disc (Neon, brightness follows the throttle)
        )

        /** Asset path of a ship. */
        fun path(id: String) = "ships/$id.bin"

        /** Reads ships/<id>.bin and the chunk files it references through [read] (asset path -> bytes). */
        fun load(id: String, read: (String) -> ByteArray): ShipModel = parse(read(path(id))) { read("ships/$it") }

        /** Parses a ship file; [chunkFile] returns the bytes of a referenced chunk file (by file name). */
        fun parse(data: ByteArray, chunkFile: (String) -> ByteArray): ShipModel {
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            magic(b, "USES")
            val version = b.short.toInt() and 0xFFFF
            require(version == VERSION) { "ship asset version $version, expected $VERSION" }
            val chunkCount = b.short.toInt() and 0xFFFF
            val id = str(b)
            val name = str(b)
            val eyeX = b.float
            val eyeY = b.float
            val eyeZ = b.float
            val bounds = FloatArray(6) { b.float }
            val rbx = FloatArray(7) { b.float }
            val files = Array(chunkCount) { str(b) }
            val hasDoors = b.get().toInt() != 0
            val boneCount = b.get().toInt() and 0xFF
            require(boneCount in 3..MAX_BONES) { "bad bone count $boneCount (the shader holds $MAX_BONES)" }
            val bones = Array(boneCount - 3) {
                val kind = b.get().toInt() and 0xFF
                val pivot = FloatArray(12) { b.float }
                val rad = b.float
                val lift = b.float
                val from = b.float
                val swingEnd = b.float
                val ankle = FloatArray(3) { b.float }
                if (kind == KIND_LINK) {
                    val fixed = FloatArray(3) { b.float }
                    val move = FloatArray(3) { b.float }
                    val base = FloatArray(12) { b.float }
                    val size = FloatArray(3) { b.float }
                    val axis = b.get().toInt() and 0xFF
                    Bone(kind, pivot, rad, lift, from, swingEnd, ankle, fixed, move, base, size, axis)
                } else {
                    Bone(kind, pivot, rad, lift, from, swingEnd, ankle, null, null, null, null, 0)
                }
            }
            val nozzles = Array(b.get().toInt() and 0xFF) {
                val x = b.float
                val y = b.float
                val z = b.float
                val d = b.float
                val r = unorm(b.get())
                val g = unorm(b.get())
                val bl = unorm(b.get())
                b.get()
                Nozzle(x, y, z, d, r, g, bl, b.float, b.float)
            }
            val lights = Array(b.get().toInt() and 0xFF) {
                val type = b.get().toInt() and 0xFF
                val cls = b.get().toInt() and 0xFF
                val r = unorm(b.get())
                val g = unorm(b.get())
                val bl = unorm(b.get())
                val enabled = b.get().toInt() != 0
                Light(
                    type, cls, r, g, bl, enabled, b.float, b.float, b.float, b.float, b.float, b.float,
                    b.float, b.float, b.float, b.float, b.float, b.float,
                )
            }
            val chunks = Array(chunkCount) { i ->
                if (files[i].isEmpty()) {
                    chunk(b)
                } else {
                    val cb = ByteBuffer.wrap(chunkFile(files[i])).order(ByteOrder.LITTLE_ENDIAN)
                    magic(cb, "USEC")
                    val v = cb.short.toInt() and 0xFFFF
                    require(v == VERSION) { "chunk ${files[i]} version $v, expected $VERSION" }
                    cb.short
                    chunk(cb)
                }
            }
            return ShipModel(id, name, eyeX, eyeY, eyeZ, bounds, rbx, chunks, files, hasDoors, boneCount, bones, nozzles, lights)
        }

        private fun chunk(b: ByteBuffer): Chunk {
            val ox = b.float
            val oy = b.float
            val oz = b.float
            val scale = b.float
            val vertexCount = b.int
            val indexCount = b.int
            val indexSize = b.get().toInt()
            b.get(); b.get(); b.get()
            require(indexSize == 2 || indexSize == 4) { "bad index size $indexSize" }
            val counts = IntArray(6) { b.int }
            require(counts.sum() == indexCount) { "index ranges ${counts.sum()} != $indexCount" }
            val vertices = ByteArray(vertexCount * VERTEX_BYTES)
            b.get(vertices)
            val indices = ByteArray(indexCount * indexSize)
            b.get(indices)
            val pad = (4 - (indices.size and 3)) and 3
            b.position(b.position() + pad)
            return Chunk(ox, oy, oz, scale, vertexCount, indexCount, indexSize, counts, vertices, indices)
        }

        private fun magic(b: ByteBuffer, m: String) {
            for (c in m) require(b.get().toInt() == c.code) { "not a ship asset (magic $m)" }
        }

        private fun str(b: ByteBuffer): String {
            val n = b.get().toInt() and 0xFF
            val bytes = ByteArray(n)
            b.get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        private fun unorm(v: Byte): Float = (v.toInt() and 0xFF) / 255f
    }
}
