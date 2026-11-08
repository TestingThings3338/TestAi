package com.starwake.core.content

import com.starwake.core.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * KT-1 "Kite" – the starter ship. An original, procedurally modelled light multirole craft
 * (~15 m long, 13 m span): faceted lifting-body fuselage, bubble canopy, swept wings with
 * down-turned tips, canted twin tails and two side engine nacelles. Nose points along -Z.
 */
object KiteShip {
    const val PRIMARY = 0
    const val SECONDARY = 1
    const val DARK = 2
    const val GLASS = 3
    const val ENGINE = 4
    const val LIGHT = 5
    const val METAL = 6
    const val RED = 7
    const val GREEN = 8
    const val WHITE = 9

    /** Engine nozzle centres (exhaust plumes start here, pointing +Z). */
    val nozzles = arrayOf(Vec3(1.85f, -0.1f, 7.25f), Vec3(-1.85f, -0.1f, 7.25f))
    const val NOZZLE_RADIUS = 0.46f
    val navGreen = Vec3(6.36f, -0.57f, 3.12f)
    val navRed = Vec3(-6.36f, -0.57f, 3.12f)
    val strobes = arrayOf(Vec3(1.56f, 2.42f, 6.66f), Vec3(-1.56f, 2.42f, 6.66f))
    const val LENGTH = 15.4f
    const val COLLISION_RADIUS = 7.0f

    private class Station(val z: Float, val w: Float, val ht: Float, val hb: Float, val yOff: Float)

    private val stations = arrayOf(
        Station(-8.2f, 0.03f, 0.03f, 0.03f, -0.16f),
        Station(-7.2f, 0.45f, 0.28f, 0.22f, -0.12f),
        Station(-5.6f, 0.95f, 0.52f, 0.42f, -0.06f),
        Station(-3.6f, 1.30f, 0.70f, 0.55f, 0.0f),
        Station(-1.2f, 1.55f, 0.80f, 0.62f, 0.02f),
        Station(1.5f, 1.65f, 0.78f, 0.66f, 0.0f),
        Station(4.2f, 1.50f, 0.70f, 0.62f, 0.0f),
        Station(6.2f, 1.20f, 0.58f, 0.52f, 0.0f),
        Station(6.9f, 1.00f, 0.48f, 0.44f, 0.0f),
    )

    // Half cross-section (x factor of width, y factor of top/bottom height); top centre to bottom centre.
    private val halfX = floatArrayOf(0f, 0.16f, 0.5f, 0.85f, 1f, 0.95f, 0.62f, 0f)
    private val halfY = floatArrayOf(1f, 0.995f, 0.95f, 0.68f, 0.18f, -0.35f, -0.9f, -1f)

    fun build(): FloatArray {
        val main = MeshBuilder(11)
        fuselage(main)
        canopy(main)
        navLights(main)
        // dorsal sensor fin (on the centre line, so not part of the mirrored side)
        main.box(-0.04f, 0.74f, 2.4f, 0.04f, 1.05f, 3.3f, DARK)
        val side = MeshBuilder(23)
        wing(side)
        tailFin(side)
        nacelle(side)
        details(side)
        val right = side.build()
        val left = MeshBuilder.mirrorX(right)
        val body = main.build()
        val out = FloatArray(body.size + right.size + left.size)
        System.arraycopy(body, 0, out, 0, body.size)
        System.arraycopy(right, 0, out, body.size, right.size)
        System.arraycopy(left, 0, out, body.size + right.size, left.size)
        return out
    }

    private fun fuselageRing(s: Station): Array<Vec3> {
        val pts = ArrayList<Vec3>(14)
        for (i in 0..7) pts.add(point(s, i, 1f))
        for (i in 6 downTo 1) pts.add(point(s, i, -1f))
        return pts.toTypedArray()
    }

    private fun point(s: Station, i: Int, side: Float): Vec3 {
        val y = if (halfY[i] >= 0f) halfY[i] * s.ht else halfY[i] * s.hb
        return Vec3(halfX[i] * s.w * side, y + s.yOff, s.z)
    }

    private fun topY(z: Float): Float {
        for (i in 0 until stations.size - 1) {
            val a = stations[i]
            val b = stations[i + 1]
            if (z <= b.z) {
                val t = ((z - a.z) / (b.z - a.z)).coerceIn(0f, 1f)
                return (a.yOff + a.ht) + ((b.yOff + b.ht) - (a.yOff + a.ht)) * t
            }
        }
        val l = stations.last()
        return l.yOff + l.ht
    }

    private fun fuselage(b: MeshBuilder) {
        val rings = stations.map { fuselageRing(it) }
        b.loft(rings, closed = true, ref = { s ->
            val a = stations[s]
            val c = stations[s + 1]
            Vec3(0f, (a.yOff + c.yOff) * 0.5f, (a.z + c.z) * 0.5f)
        }) { s, e ->
            when {
                s == 0 && e != 6 && e != 7 -> SECONDARY // coloured nose cone
                e == 0 || e == 13 -> SECONDARY // dorsal stripe
                (e == 6 || e == 7) && s >= 2 -> DARK // keel
                (e == 5 || e == 8) && s in 4..7 -> DARK
                s == 7 && (e == 4 || e == 9) -> DARK
                else -> PRIMARY
            }
        }
        val tail = rings.last()
        b.cap(tail, DARK, Vec3(0f, 0f, 5.5f))
        // glowing exhaust vent in the tail cap
        b.box(-0.55f, -0.12f, 6.9f, 0.55f, 0.1f, 6.96f, ENGINE)
    }

    private fun canopy(b: MeshBuilder) {
        val st = arrayOf(
            floatArrayOf(-4.7f, 0.04f, 0.02f),
            floatArrayOf(-3.9f, 0.56f, 0.33f),
            floatArrayOf(-2.7f, 0.73f, 0.47f),
            floatArrayOf(-1.3f, 0.66f, 0.40f),
            floatArrayOf(-0.2f, 0.46f, 0.22f),
            floatArrayOf(0.6f, 0.05f, 0.02f),
        )
        val xs = floatArrayOf(1f, 0.82f, 0.45f, 0f)
        val ys = floatArrayOf(0f, 0.55f, 0.9f, 1f)
        val rings = st.map { (z, w, h) ->
            val base = topY(z) - 0.12f
            val pts = ArrayList<Vec3>(7)
            for (i in 0..3) pts.add(Vec3(xs[i] * w, base + ys[i] * (h + 0.12f), z))
            for (i in 2 downTo 0) pts.add(Vec3(-xs[i] * w, base + ys[i] * (h + 0.12f), z))
            pts.toTypedArray()
        }
        b.loft(rings, closed = false, ref = { s ->
            val z = (st[s][0] + st[s + 1][0]) * 0.5f
            Vec3(0f, topY(z) - 0.6f, z)
        }) { s, e ->
            when {
                s == 4 -> DARK // rear fairing
                s == 2 && (e == 2 || e == 3) -> DARK // centre frame
                else -> GLASS
            }
        }
    }

    private fun wing(b: MeshBuilder) {
        val root = arrayOf(Vec3(1.35f, -0.06f, -1.6f), Vec3(1.35f, 0.16f, 1.4f), Vec3(1.35f, -0.06f, 4.9f), Vec3(1.35f, -0.26f, 1.4f))
        val tip = arrayOf(Vec3(6.3f, -0.6f, 3.1f), Vec3(6.3f, -0.52f, 4.1f), Vec3(6.3f, -0.6f, 5.1f), Vec3(6.3f, -0.67f, 4.1f))
        val mid = Array(4) { Vec3().set(root[it]).lerp(tip[it], 0.58f) }
        val rings = listOf(root, mid, tip)
        b.loft(rings, closed = true, ref = { s ->
            MeshBuilder.centroid(rings[s]).add(MeshBuilder.centroid(rings[s + 1])).scale(0.5f)
        }) { s, e ->
            when {
                e >= 2 -> DARK
                s == 1 -> SECONDARY
                else -> PRIMARY
            }
        }
        b.cap(tip, DARK, MeshBuilder.centroid(tip).add(-0.4f, 0f, 0f))
        // down-turned wingtip fin
        val top = arrayOf(Vec3(6.25f, -0.56f, 3.3f), Vec3(6.33f, -0.56f, 4.15f), Vec3(6.25f, -0.56f, 5.05f), Vec3(6.17f, -0.56f, 4.15f))
        val bot = arrayOf(Vec3(6.55f, -1.65f, 4.35f), Vec3(6.6f, -1.65f, 4.8f), Vec3(6.55f, -1.65f, 5.25f), Vec3(6.5f, -1.65f, 4.8f))
        val finRings = listOf(top, bot)
        b.loft(finRings, closed = true, ref = { _ ->
            MeshBuilder.centroid(top).add(MeshBuilder.centroid(bot)).scale(0.5f)
        }) { _, _ -> SECONDARY }
        b.cap(bot, DARK, MeshBuilder.centroid(bot).add(0f, 0.3f, 0f))
    }

    private fun tailFin(b: MeshBuilder) {
        val root = arrayOf(Vec3(0.62f, 0.5f, 2.7f), Vec3(0.7f, 0.5f, 4.4f), Vec3(0.62f, 0.5f, 6.5f), Vec3(0.54f, 0.5f, 4.4f))
        val tip = arrayOf(Vec3(1.55f, 2.3f, 5.1f), Vec3(1.6f, 2.3f, 5.7f), Vec3(1.55f, 2.3f, 6.7f), Vec3(1.5f, 2.3f, 5.7f))
        val mid = Array(4) { Vec3().set(root[it]).lerp(tip[it], 0.62f) }
        val rings = listOf(root, mid, tip)
        b.loft(rings, closed = true, ref = { s ->
            MeshBuilder.centroid(rings[s]).add(MeshBuilder.centroid(rings[s + 1])).scale(0.5f)
        }) { s, _ -> if (s == 1) SECONDARY else PRIMARY }
        b.cap(tip, SECONDARY, MeshBuilder.centroid(tip).add(0f, -0.3f, 0f))
        // strobe housing on the fin tip
        b.box(1.5f, 2.3f, 6.55f, 1.62f, 2.42f, 6.78f, WHITE)
    }

    private fun octagon(z: Float, r: Float): Array<Vec3> = Array(8) {
        val a = (22.5f + it * 45f) * (Math.PI.toFloat() / 180f)
        Vec3(1.85f + cos(a) * r, -0.1f + sin(a) * r, z)
    }

    private fun nacelle(b: MeshBuilder) {
        val zs = floatArrayOf(1.0f, 1.5f, 2.3f, 5.3f, 6.6f, 7.2f)
        val rs = floatArrayOf(0.50f, 0.66f, 0.72f, 0.72f, 0.64f, 0.60f)
        val rings = zs.indices.map { octagon(zs[it], rs[it]) }
        b.loft(rings, closed = true, ref = { s -> Vec3(1.85f, -0.1f, (zs[s] + zs[s + 1]) * 0.5f) }) { s, e ->
            when {
                s == 0 -> SECONDARY
                s == 2 && e in 4..6 -> DARK
                s == 4 -> DARK
                else -> PRIMARY
            }
        }
        // intake: funnel facing forward into a dark fan face
        val intake = octagon(1.25f, 0.36f)
        loftToward(b, rings[0], intake, Vec3(1.85f, -0.1f, 0f), DARK)
        b.cap(intake, DARK, Vec3(1.85f, -0.1f, 2.0f))
        // nozzle: flat metal lip, dark inner wall, glowing core
        val lipIn = octagon(7.2f, 0.47f)
        b.loft(listOf(rings.last(), lipIn), closed = true, ref = { Vec3(1.85f, -0.1f, 6.5f) }) { _, _ -> METAL }
        val core = octagon(6.9f, 0.44f)
        loftToward(b, lipIn, core, Vec3(1.85f, -0.1f, 9f), DARK)
        b.cap(core, ENGINE, Vec3(1.85f, -0.1f, 6.0f))
    }

    /** Loft whose faces point towards [ref] (for concave openings such as intakes and nozzles). */
    private fun loftToward(b: MeshBuilder, r0: Array<Vec3>, r1: Array<Vec3>, ref: Vec3, mat: Int) {
        val n = r0.size
        for (e in 0 until n) {
            val e1 = (e + 1) % n
            val c = Vec3().set(r0[e]).add(r0[e1]).add(r1[e1]).add(r1[e]).scale(0.25f)
            // mirror ref through the face centre so "away from mirrored" == "towards ref"
            val away = Vec3(2f * c.x - ref.x, 2f * c.y - ref.y, 2f * c.z - ref.z)
            b.quad(r0[e], r0[e1], r1[e1], r1[e], mat, away)
        }
    }

    private fun details(b: MeshBuilder) {
        // chin-side cannons
        b.box(1.52f, -0.44f, -3.0f, 1.72f, -0.24f, 0.2f, DARK)
        b.box(1.56f, -0.40f, -3.25f, 1.68f, -0.28f, -3.0f, METAL)
        // glowing status strip along the hull side
        b.box(1.58f, 0.09f, -0.8f, 1.67f, 0.16f, 1.8f, LIGHT)
    }

    private fun navLights(b: MeshBuilder) {
        b.box(6.26f, -0.66f, 2.98f, 6.44f, -0.48f, 3.2f, GREEN)
        b.box(-6.44f, -0.66f, 2.98f, -6.26f, -0.48f, 3.2f, RED)
    }
}

/** Paint schemes for the Kite (primary, secondary in linear RGB). */
object Liveries {
    class Livery(val name: String, val primary: FloatArray, val secondary: FloatArray)

    val all = arrayOf(
        Livery("AURORA", floatArrayOf(0.80f, 0.82f, 0.85f), floatArrayOf(0.02f, 0.42f, 0.50f)),
        Livery("EMBER", floatArrayOf(0.13f, 0.14f, 0.16f), floatArrayOf(0.90f, 0.33f, 0.05f)),
        Livery("VANGUARD", floatArrayOf(0.07f, 0.12f, 0.27f), floatArrayOf(0.80f, 0.58f, 0.18f)),
        Livery("MOSS", floatArrayOf(0.20f, 0.27f, 0.15f), floatArrayOf(0.06f, 0.06f, 0.07f)),
        Livery("CRIMSON", floatArrayOf(0.42f, 0.04f, 0.05f), floatArrayOf(0.85f, 0.85f, 0.85f)),
    )
}
