package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Detail level selection and the sign text layout (Roblox TextScaled into the label boxes). */
class StationSignsTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }
    private val font = FontData.parse(File(assets, "font/ui.fnt").readText())

    @Test
    fun detailLevelsByDistance() {
        assertEquals(0, StationRenderer.lodFor(0f))
        assertEquals(0, StationRenderer.lodFor(2_999f))
        assertEquals(1, StationRenderer.lodFor(3_000f))
        assertEquals(1, StationRenderer.lodFor(14_999f))
        assertEquals(2, StationRenderer.lodFor(15_000f))
        assertEquals(2, StationRenderer.lodFor(59_999f))
        // Roblox's LodDistance: beyond 60 km only the marker
        assertEquals(StationRenderer.LOD_MARKER, StationRenderer.lodFor(60_000f))
    }

    /** Visible glyphs (with ink) of [s] in [face]. */
    private fun inked(s: String, face: Int) = s.count { c -> font.index(face, c.code).let { it >= 0 && font.w(it) > 0f } }

    @Test
    fun everyGlyphLiesInsideItsLabel() {
        for (t in StationModel.TYPES.toList() + StationModel.SITE) {
            val m = StationModel.load(t) { File(assets, it).readBytes() }
            val name = "MERIDIAN STATION"
            val buf = SignLayout.build(m, name, font)
            val verts = buf.limit() / SignLayout.VERTEX_BYTES
            var expected = 0
            for (l in m.labels) expected += inked(SignLayout.textOf(l, name), l.font)
            assertEquals(expected * 4, verts, "$t: one quad per visible glyph")
            // walk the labels in order and check each glyph vertex is inside its box (s, t in 0..1)
            var v = 0
            for (l in m.labels) {
                val s = SignLayout.textOf(l, name)
                val n = inked(s, l.font) * 4
                val uu = l.u[0] * l.u[0] + l.u[1] * l.u[1] + l.u[2] * l.u[2]
                val vv = l.v[0] * l.v[0] + l.v[1] * l.v[1] + l.v[2] * l.v[2]
                for (k in 0 until n) {
                    val o = (v + k) * SignLayout.VERTEX_BYTES
                    val dx = buf.getFloat(o) - l.origin[0]
                    val dy = buf.getFloat(o + 4) - l.origin[1]
                    val dz = buf.getFloat(o + 8) - l.origin[2]
                    val su = (dx * l.u[0] + dy * l.u[1] + dz * l.u[2]) / uu
                    val tv = (dx * l.v[0] + dy * l.v[1] + dz * l.v[2]) / vv
                    // glyph boxes carry the SDF spread around the ink: allow a little overhang
                    assertTrue(su in -0.15f..1.15f && tv in -0.25f..1.25f, "$t \"$s\": glyph vertex at ($su, $tv) of its box")
                }
                v += n
            }
        }
    }

    @Test
    fun padNumbersKeepAsciiDigitsInEveryLocale() {
        assertEquals(listOf("01", "02", "03", "04", "05", "06"), (1..6).map { StationModel.padNumber(it) })
        assertEquals("10", StationModel.padNumber(10))
        val m = StationModel.load("SpireStation") { File(assets, it).readBytes() }
        val pads = m.labels.filter { it.isPadNumber }
        assertEquals(6, pads.size)
        val reference = SignLayout.build(m, "MERIDIAN STATION", font)
        val saved = Locale.getDefault()
        try {
            // String.format("%02d") gives native digits in these (U+0660.., U+06F0.., U+0966.., U+09E6.., U+1040..),
            // which the atlas does not hold: the pad numbers would be laid out blank
            for (tag in listOf("ar-EG", "ar-SA", "fa-IR", "mr-IN", "ne-NP", "bn-BD", "my-MM")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                for (l in pads) {
                    val s = SignLayout.textOf(l, "MERIDIAN STATION")
                    assertEquals(StationModel.padNumber(l.pad), s, tag)
                    assertEquals(2, inked(s, l.font), "$tag: pad ${l.pad} \"$s\" has two inked glyphs")
                }
                val buf = SignLayout.build(m, "MERIDIAN STATION", font)
                assertEquals(reference.limit(), buf.limit(), "$tag: same glyphs as in the root locale")
                for (i in 0 until reference.limit()) assertEquals(reference.get(i), buf.get(i), "$tag: byte $i")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
