package com.starwake.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Greedy map-label placement: no two placed labels overlap, the selection is always placed. */
class LabelBoxesTest {
    private val body = intArrayOf(LabelBoxes.BELOW, LabelBoxes.ABOVE, LabelBoxes.RIGHT, LabelBoxes.LEFT)
    private val tw = 60f
    private val ch = 7f
    private val pad = 2f

    /** Text boxes (left, top, right, bottom) of every label placed so far. */
    private val placed = ArrayList<FloatArray>()

    private fun LabelBoxes.label(x: Float, y: Float, self: Int, force: Boolean = false): Boolean {
        val ok = place(x, y, 5f, 8f, tw, ch, pad, body, self, force)
        if (ok) placed += floatArrayOf(textX, textY - ch, textX + tw, textY)
        return ok
    }

    private fun assertNoOverlap() {
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val a = placed[i]
            val b = placed[j]
            if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) {
                fail("labels $i ${a.contentToString()} and $j ${b.contentToString()} overlap")
            }
        }
    }

    @Test
    fun crowdedMarkersGetMovedOrSkippedLabelsThatNeverOverlap() {
        val lb = LabelBoxes(64)
        lb.reset(0f, 0f, 1000f, 600f)
        // a tight cluster like the star, PLANET I and OUTPOST II at the opening zoom, plus a ring of
        // markers around it; discs go in first, labels after
        val xs = floatArrayOf(500f, 520f, 480f, 505f, 540f, 460f, 500f, 530f, 470f, 515f)
        val ys = floatArrayOf(300f, 315f, 322f, 340f, 290f, 285f, 270f, 345f, 350f, 300f)
        for (i in xs.indices) lb.add(xs[i] - 5f, ys[i] - 5f, xs[i] + 5f, ys[i] + 5f, i)
        var drawn = 0
        for (i in xs.indices) if (lb.label(xs[i], ys[i], i)) drawn++
        assertNoOverlap()
        assertTrue(drawn >= 4, "most labels should still find a spot, got $drawn")
    }

    @Test
    fun labelsKeepOffOtherDiscsButNotTheirOwn() {
        val lb = LabelBoxes(8)
        lb.reset(0f, 0f, 1000f, 600f)
        // star at (500, 300); a planet's disc sits right where the star's name would go
        lb.add(495f, 295f, 505f, 305f, 0)
        lb.add(510f, 315f, 520f, 325f, 1)
        assertTrue(lb.label(500f, 300f, 0))
        // the name moved above the star: BELOW would cross the planet's disc
        assertEquals(300f - 5f - 8f, lb.textY)
        // the planet's own name may sit next to its own disc
        assertTrue(lb.label(515f, 320f, 1))
        assertEquals(320f + 5f + 8f + ch, lb.textY)
        assertNoOverlap()
    }

    @Test
    fun theSelectionIsAlwaysPlaced() {
        val lb = LabelBoxes(8)
        lb.reset(0f, 0f, 1000f, 600f)
        // a panel (owned by 7, so only label 7 may cross it) covers every side of the marker
        lb.add(300f, 200f, 700f, 400f, 7)
        assertFalse(lb.label(500f, 300f, 3))
        assertTrue(lb.isFree(480f, 314f, 520f, 320f, 7))
        assertTrue(lb.label(500f, 300f, 3, force = true))
        // forced labels use their first (preferred) side
        assertEquals(500f - tw * 0.5f, lb.textX)
        assertEquals(300f + 5f + 8f + ch, lb.textY)
        // and later labels keep off it
        assertFalse(lb.isFree(480f, 314f, 520f, 320f, 7))
    }

    @Test
    fun labelsStayOnScreen() {
        val lb = LabelBoxes(8)
        lb.reset(0f, 0f, 1000f, 600f)
        // at the bottom edge BELOW is off-screen, so the name goes above
        assertTrue(lb.label(500f, 595f, 0))
        assertEquals(595f - 5f - 8f, lb.textY)
        // at the right edge only LEFT keeps the name on screen
        assertTrue(lb.label(990f, 300f, 1))
        assertTrue(lb.textX + tw <= 1000f)
        assertNoOverlap()
    }

    @Test
    fun onlyTheOwnerMayCoverAnOwnedBox() {
        val lb = LabelBoxes(4)
        lb.reset(0f, 0f, 1000f, 600f)
        lb.add(90f, 90f, 110f, 110f, LabelBoxes.OWNER_SHIP)
        val r = intArrayOf(LabelBoxes.UP_RIGHT)
        // YOU's spot touches the ship marker: fine for YOU, not for anybody else
        assertFalse(lb.place(100f, 100f, 8f, 5f, 30f, ch, pad, r, LabelBoxes.NO_OWNER, false))
        assertFalse(lb.place(100f, 100f, 8f, 5f, 30f, ch, pad, r, 0, false))
        assertTrue(lb.place(100f, 100f, 8f, 5f, 30f, ch, pad, r, LabelBoxes.OWNER_SHIP, false))
        assertEquals(108f, lb.textX)
        assertEquals(95f, lb.textY)
    }

    @Test
    fun aMarkerUnderTheShipGetsItsNameFurtherOut() {
        val lb = LabelBoxes(8)
        lb.reset(0f, 0f, 1000f, 600f)
        // ship marker box around (500, 300), a station square right under it
        lb.add(487f, 287f, 513f, 313f, LabelBoxes.OWNER_SHIP)
        val station = intArrayOf(LabelBoxes.RIGHT, LabelBoxes.LEFT, LabelBoxes.BELOW, LabelBoxes.ABOVE)
        // every near spot touches the marker
        assertFalse(lb.place(502f, 301f, 4f, 5f, tw, ch, pad, station, LabelBoxes.NO_OWNER, false))
        // the fallback ring 10 px further out clears it, on the preferred side
        assertTrue(lb.place(502f, 301f, 4f, 5f, tw, ch, pad, station, LabelBoxes.NO_OWNER, false, far = 10f))
        assertEquals(502f + 4f + 5f + 10f, lb.textX)
        assertTrue(lb.textX - pad >= 513f)
    }

    @Test
    fun aFullListStopsGrowing() {
        val lb = LabelBoxes(2)
        lb.reset(0f, 0f, 1000f, 600f)
        lb.add(0f, 0f, 1f, 1f)
        lb.add(2f, 2f, 3f, 3f)
        lb.add(4f, 4f, 5f, 5f)
        assertEquals(2, lb.count)
    }
}
