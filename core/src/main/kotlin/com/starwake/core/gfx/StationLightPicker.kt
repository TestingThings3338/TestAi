package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import com.starwake.core.station.StationInstance
import kotlin.math.sqrt

/**
 * Chooses which of a station's Roblox lights (17-24 per model) the mesh shader evaluates per pixel
 * this frame, at most [capacity], each with a weight 0..1 that scales its colour.
 *
 * Lights are ranked by how far the camera is outside their reach, key = |light - camera| - range,
 * at their current position (a light in a spinning group turns with it). The weights make the
 * choice continuous in the camera position, so a light never switches on or off at once:
 *
 * - The cut is the key of the first light left out (the (K+1)-th, K = maxLights), and at most
 *   [REACH]: lights whose reach is farther than that are off.
 * - A chosen light's weight is smoothstep((cut - key) / w), w = [FADE_MIN] + [FADE_SHARE] * cut:
 *   it reaches 0 just as it would swap places with the first one left out (or reach [REACH]), and
 *   is 1 when it is clearly among the nearest. Two lights of the same key (a left/right pair seen
 *   from the centre line) fade together.
 *
 * Nothing allocates; the candidate list keeps only the K + 1 best keys.
 */
internal class StationLightPicker(val capacity: Int = StationShaders.MAX_LIGHTS) {
    /** Chosen lights (index into the model's lights), best first. */
    val index = IntArray(capacity)

    /** Weight 0..1 per chosen light (scales its colour). */
    val weight = FloatArray(capacity)

    /** Current position and unit direction (spot and surface lights) per chosen light, station space: [j * 3]. */
    val pos = FloatArray(capacity * 3)
    val dir = FloatArray(capacity * 3)

    /** Lights chosen by the last [pick] (weights above zero). */
    var count = 0
        private set

    // candidates: the best K + 1 keys so far, ascending, with their positions
    private val candIndex = IntArray(capacity + 1)
    private val candKey = FloatArray(capacity + 1)
    private val candPos = FloatArray((capacity + 1) * 3)
    private val rows = FloatArray(StationModel.MAX_GROUPS * 12)

    /**
     * Picks at most [maxLights] (<= [capacity]) lights of [st]'s model for a camera at ([cx], [cy],
     * [cz]) in station space. Returns [count].
     */
    fun pick(st: StationInstance, cx: Float, cy: Float, cz: Float, maxLights: Int): Int {
        val model = st.model
        val ls = model.lights
        val k = maxLights.coerceIn(0, capacity)
        count = 0
        if (k == 0 || ls.isEmpty()) return 0
        for (g in model.groups.indices) st.groupRows(g, rows, g * 12)
        var n = 0
        val keep = k + 1
        for (i in ls.indices) {
            val l = ls[i]
            var x = l.x
            var y = l.y
            var z = l.z
            if (l.group != 0) {
                val o = l.group * 12
                val r = rows
                val nx = r[o] * x + r[o + 1] * y + r[o + 2] * z + r[o + 3]
                val ny = r[o + 4] * x + r[o + 5] * y + r[o + 6] * z + r[o + 7]
                val nz = r[o + 8] * x + r[o + 9] * y + r[o + 10] * z + r[o + 11]
                x = nx; y = ny; z = nz
            }
            val dx = x - cx
            val dy = y - cy
            val dz = z - cz
            val key = sqrt(dx * dx + dy * dy + dz * dz) - l.range
            if (key >= REACH) continue
            if (n == keep && key >= candKey[n - 1]) continue
            // insert into the ascending list, dropping its last entry when full
            var j = if (n < keep) n++ else n - 1
            while (j > 0 && candKey[j - 1] > key) {
                candKey[j] = candKey[j - 1]
                candIndex[j] = candIndex[j - 1]
                candPos[j * 3] = candPos[j * 3 - 3]
                candPos[j * 3 + 1] = candPos[j * 3 - 2]
                candPos[j * 3 + 2] = candPos[j * 3 - 1]
                j--
            }
            candKey[j] = key
            candIndex[j] = i
            candPos[j * 3] = x
            candPos[j * 3 + 1] = y
            candPos[j * 3 + 2] = z
        }
        val cut = if (n > k) minOf(candKey[k], REACH) else REACH
        val width = FADE_MIN + FADE_SHARE * maxOf(cut, 0f)
        for (j in 0 until minOf(n, k)) {
            val t = ((cut - candKey[j]) / width).coerceIn(0f, 1f)
            val w = t * t * (3f - 2f * t)
            if (w <= MIN_WEIGHT) continue
            val c = count++
            val i = candIndex[j]
            index[c] = i
            weight[c] = w
            pos[c * 3] = candPos[j * 3]
            pos[c * 3 + 1] = candPos[j * 3 + 1]
            pos[c * 3 + 2] = candPos[j * 3 + 2]
            val l = ls[i]
            var ex = l.dirX
            var ey = l.dirY
            var ez = l.dirZ
            if (l.group != 0) {
                val o = l.group * 12
                val r = rows
                val nx = r[o] * ex + r[o + 1] * ey + r[o + 2] * ez
                val ny = r[o + 4] * ex + r[o + 5] * ey + r[o + 6] * ez
                val nz = r[o + 8] * ex + r[o + 9] * ey + r[o + 10] * ez
                ex = nx; ey = ny; ez = nz
            }
            dir[c * 3] = ex
            dir[c * 3 + 1] = ey
            dir[c * 3 + 2] = ez
        }
        return count
    }

    companion object {
        /** Lights whose reach ends farther than this (m) from the camera are off (detail level 1 starts at 3 km). */
        const val REACH = 2_500f

        /** Fade width (m) at the cut: FADE_MIN + FADE_SHARE * the cut's key. */
        const val FADE_MIN = 20f
        const val FADE_SHARE = 0.2f

        /** Weights at or below this are dropped (no visible light, no shader work). */
        const val MIN_WEIGHT = 1e-3f
    }
}
