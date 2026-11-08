package com.starwake.core.ui

import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.clamp
import com.starwake.core.math.withAlpha
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the map wants the game to do. */
interface MapHost : UiHost {
    fun closeMap()

    /** Lock a target picked on the map: kind 2 = station, 3 = body. */
    fun lockFromMap(kind: Int, index: Int)
}

/**
 * Galaxy and system maps: flat dark background, uppercase markers, top-down orbits. Drag to pan,
 * pinch to zoom, tap a body or station to select it, then LOCK it as the flight target.
 */
class MapScreen(private val host: MapHost, private val system: StarSystem) {
    val group = WidgetGroup(host)
    private var tab = TAB_SYSTEM
    private val tabSystem = group.add(Button("SYSTEM") { tab = TAB_SYSTEM })
    private val tabGalaxy = group.add(Button("GALAXY") { tab = TAB_GALAXY })
    private val close = group.add(Button("CLOSE") { host.closeMap() })
    private val lock = group.add(Button("LOCK TARGET", primary = true) {
        if (selKind != 0) {
            host.lockFromMap(selKind, selIndex)
            host.closeMap()
        }
    })

    private var w = 1f
    private var h = 1f
    private var u = 1f
    private var sl = 0f
    private var sr = 0f
    private var st = 0f
    private var sb = 0f

    // view: map centre in system units (metres, x/z plane) and log-ish zoom (metres per pixel)
    private var centerX = 0.0
    private var centerZ = 0.0
    private var metresPerPx = 1.0e7
    private var selKind = 0
    private var selIndex = -1

    // gestures
    private val pid = intArrayOf(-1, -1)
    private val px = FloatArray(2)
    private val py = FloatArray(2)
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pinchDist = 0f

    private val buf = TextBuf(64)
    private val galaxy: FloatArray

    init {
        // decorative charted-space backdrop for the galaxy tab (real systems arrive with the export)
        val rng = Rng(77L)
        galaxy = FloatArray(600 * 3) {
            when (it % 3) {
                0 -> rng.gaussian() * 0.35f
                1 -> rng.gaussian() * 0.22f
                else -> 0.3f + rng.nextFloat() * 0.7f
            }
        }
    }

    fun layout(f: Frame) {
        w = f.w; h = f.h; u = f.u
        sl = f.sl; sr = f.sr; st = f.st; sb = f.sb
        val top = st + 14f * u
        tabSystem.r.set(sl + 18f * u, top, 112f * u, 38f * u)
        tabGalaxy.r.set(sl + 18f * u + 120f * u, top, 112f * u, 38f * u)
        close.r.set(w - sr - 18f * u - 112f * u, top, 112f * u, 38f * u)
        lock.r.set(w - sr - 18f * u - 200f * u, h - sb - 18f * u - 44f * u, 200f * u, 44f * u)
        group.slop = 4f * u
    }

    /** Called when the map opens: centre on the ship and pick a zoom that shows its surroundings. */
    fun open(ship: DVec3, lockedKind: Int, lockedIndex: Int) {
        tab = TAB_SYSTEM
        centerX = ship.x
        centerZ = ship.z
        // fit the whole system (~2.4e9 m radius) into the shorter screen side
        metresPerPx = system.edgeRadius * 2.2 / min(w, h)
        selKind = if (lockedKind == 2 || lockedKind == 3) lockedKind else 0
        selIndex = lockedIndex
        pid[0] = -1; pid[1] = -1
    }

    // ------------------------------------------------------------------ input

    fun down(id: Int, x: Float, y: Float) {
        if (group.down(id, x, y)) return
        val slot = if (pid[0] < 0) 0 else if (pid[1] < 0) 1 else return
        pid[slot] = id
        px[slot] = x; py[slot] = y
        if (slot == 0) {
            downX = x; downY = y; moved = false
        } else {
            pinchDist = dist()
            moved = true
        }
    }

    fun move(id: Int, x: Float, y: Float) {
        if (group.move(id, x, y)) return
        val slot = if (pid[0] == id) 0 else if (pid[1] == id) 1 else return
        if (pid[1] >= 0) {
            px[slot] = x; py[slot] = y
            val d = dist()
            if (pinchDist > 1f && d > 1f) {
                metresPerPx = clamp((metresPerPx * pinchDist / d).toFloat(), MIN_MPP, MAX_MPP).toDouble()
            }
            pinchDist = d
            return
        }
        val dx = x - px[0]
        val dy = y - py[0]
        px[0] = x; py[0] = y
        if (kotlin.math.abs(x - downX) + kotlin.math.abs(y - downY) > 10f * u) moved = true
        if (tab == TAB_SYSTEM) {
            centerX -= dx * metresPerPx
            centerZ -= dy * metresPerPx
        }
    }

    fun up(id: Int, x: Float, y: Float, cancelled: Boolean) {
        if (group.up(id, x, y, cancelled)) return
        val slot = if (pid[0] == id) 0 else if (pid[1] == id) 1 else return
        pid[slot] = -1
        if (slot == 0 && pid[1] >= 0) {
            // keep panning with the remaining finger
            pid[0] = pid[1]; px[0] = px[1]; py[0] = py[1]; pid[1] = -1
            return
        }
        if (!moved && !cancelled && tab == TAB_SYSTEM) select(x, y)
    }

    fun releaseAll() {
        group.releaseAll()
        pid[0] = -1; pid[1] = -1
    }

    /** Mouse-wheel / gamepad style zoom step (factor > 1 zooms out). */
    fun zoom(factor: Float) {
        metresPerPx = clamp((metresPerPx * factor).toFloat(), MIN_MPP, MAX_MPP).toDouble()
    }

    private fun dist(): Float {
        val dx = px[0] - px[1]
        val dy = py[0] - py[1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun select(x: Float, y: Float) {
        var best = 0
        var bestIdx = -1
        var bestD = 28f * u
        for (i in system.stations.indices) {
            val p = system.stations[i].pos
            val d = screenDist(p, x, y)
            if (d < bestD) {
                bestD = d; best = 2; bestIdx = i
            }
        }
        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            val d = screenDist(b.pos, x, y) - bodyPx(b)
            if (d < bestD) {
                bestD = d; best = 3; bestIdx = i
            }
        }
        if (best != 0) {
            selKind = best
            selIndex = bestIdx
            host.haptic(com.starwake.core.Haptics.TICK)
        }
    }

    private fun sx(x: Double): Float = (w * 0.5 + (x - centerX) / metresPerPx).toFloat()
    private fun sy(z: Double): Float = (h * 0.5 + (z - centerZ) / metresPerPx).toFloat()

    private fun screenDist(p: DVec3, x: Float, y: Float): Float {
        val dx = sx(p.x) - x
        val dy = sy(p.z) - y
        return sqrt(dx * dx + dy * dy)
    }

    /** Drawn radius: true scale when zoomed in, a readable minimum otherwise. */
    private fun bodyPx(b: StarSystem.Body): Float {
        val trueR = (b.radius / metresPerPx).toFloat()
        val min = if (b.type == StarSystem.BodyType.STAR) 9f * u else 5f * u
        return max(trueR, min)
    }

    // ------------------------------------------------------------------ drawing

    fun draw(ui: UiRenderer, a: Float, ship: DVec3, shipRot: Quat, time: Float) {
        lock.visible = tab == TAB_SYSTEM && selKind != 0
        ui.rect(0f, 0f, w, h, withAlpha(MAP_BG, a))
        if (tab == TAB_SYSTEM) drawSystem(ui, a, ship, shipRot, time) else drawGalaxy(ui, a, time)
        // tab highlight
        val t = if (tab == TAB_SYSTEM) tabSystem.r else tabGalaxy.r
        ui.rect(t.x, t.y + t.h + 3f * u, t.w, 2f * u, withAlpha(Theme.ACCENT, a))
        group.draw(ui, u, a)
    }

    private fun drawSystem(ui: UiRenderer, a: Float, ship: DVec3, shipRot: Quat, time: Float) {
        // scale bar
        val barM = niceLength(metresPerPx * 120f * u)
        val barPx = (barM / metresPerPx).toFloat()
        val bx = sl + 22f * u
        val by = h - sb - 26f * u
        ui.rect(bx, by, barPx, 2f * u, withAlpha(Theme.TEXT_DIM, a))
        ui.rect(bx, by - 5f * u, 1.5f * u, 12f * u, withAlpha(Theme.TEXT_DIM, a))
        ui.rect(bx + barPx - 1.5f * u, by - 5f * u, 1.5f * u, 12f * u, withAlpha(Theme.TEXT_DIM, a))
        buf.clear()
        val unit = distanceText(buf, barM)
        val tw = ui.text(buf, bx, by - 10f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), face = FontData.MONO)
        ui.text(unit, bx + tw + 4f * u, by - 10f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a))

        ui.text(system.name, sl + 22f * u, st + 78f * u, 15f * u, withAlpha(Theme.TEXT, a), tracking = 0.2f, face = FontData.TITLE)
        ui.text("PINCH TO ZOOM  ·  DRAG TO PAN  ·  TAP TO SELECT", sl + 22f * u, st + 96f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)

        val star = system.bodies[0]
        val cx = sx(star.pos.x)
        val cy = sy(star.pos.z)
        // orbits (circles around the star through each planet, in the x/z plane)
        for (i in 1 until system.bodies.size) {
            val b = system.bodies[i]
            val ox = b.pos.x - star.pos.x
            val oz = b.pos.z - star.pos.z
            val r = (sqrt(ox * ox + oz * oz) / metresPerPx).toFloat()
            if (r > 4f && r < 20000f) ui.circle(cx, cy, r, withAlpha(ORBIT, a), 1f * u)
        }
        val edge = (system.edgeRadius / metresPerPx).toFloat()
        if (edge < 20000f) ui.circle(cx, cy, edge, withAlpha(Theme.DANGER, a * 0.25f), 1f * u)

        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            val x = sx(b.pos.x)
            val y = sy(b.pos.z)
            val r = bodyPx(b)
            if (x < -r - 200f || x > w + r + 200f || y < -r - 200f || y > h + r + 200f) continue
            val col = bodyColor(b)
            ui.circle(x, y, r, withAlpha(col, a))
            if (b.hasRings) ui.circle(x, y, max(r * 1.9f, r + 4f * u), withAlpha(col, a * 0.5f), 1.2f * u)
            val sel = selKind == 3 && selIndex == i
            if (sel) ui.circle(x, y, r + 7f * u, withAlpha(Theme.LOCK, a), 1.6f * u)
            ui.text(b.name, x, y + r + 15f * u, 9.5f * u, withAlpha(if (sel) Theme.LOCK else Theme.TEXT, a), UiRenderer.CENTER, tracking = 0.18f)
        }
        for (i in system.stations.indices) {
            val s = system.stations[i]
            val x = sx(s.pos.x)
            val y = sy(s.pos.z)
            if (x < -100f || x > w + 100f || y < -100f || y > h + 100f) continue
            val sel = selKind == 2 && selIndex == i
            val col = if (sel) Theme.LOCK else Theme.ACCENT
            ui.rect(x - 4f * u, y - 4f * u, 8f * u, 8f * u, withAlpha(col, a), -2f * u)
            // only label stations once zoomed in enough to separate them from their planet
            if (metresPerPx < 2e6 || sel) {
                ui.text(s.name, x + 9f * u, y + 3f * u, 9f * u, withAlpha(col, a), tracking = 0.15f)
            }
        }

        // the ship: heading arrow in the x/z plane
        val x = sx(ship.x)
        val y = sy(ship.z)
        val fx = -2f * (shipRot.x * shipRot.z + shipRot.w * shipRot.y) // rotate (0,0,-1): x
        val fz = -(1f - 2f * (shipRot.x * shipRot.x + shipRot.y * shipRot.y)) // z
        val fl = sqrt(fx * fx + fz * fz).coerceAtLeast(1e-4f)
        val hx = fx / fl
        val hz = fz / fl
        val pulse = 0.6f + 0.4f * kotlin.math.sin(time * 4f)
        ui.circle(x, y, 9f * u, withAlpha(Theme.OK, a * 0.35f * pulse), 0f, 6f * u)
        ui.line(x - hx * 7f * u, y - hz * 7f * u, x + hx * 11f * u, y + hz * 11f * u, 2.4f * u, withAlpha(Theme.OK, a))
        ui.line(x + hx * 11f * u, y + hz * 11f * u, x + (hx * 0.3f - hz * 0.8f) * 8f * u, y + (hz * 0.3f + hx * 0.8f) * 8f * u, 2f * u, withAlpha(Theme.OK, a))
        ui.line(x + hx * 11f * u, y + hz * 11f * u, x + (hx * 0.3f + hz * 0.8f) * 8f * u, y + (hz * 0.3f - hx * 0.8f) * 8f * u, 2f * u, withAlpha(Theme.OK, a))
        ui.text("YOU", x + 12f * u, y - 10f * u, 9f * u, withAlpha(Theme.OK, a), tracking = 0.2f)

        if (selKind != 0) drawSelection(ui, a, ship)
    }

    private fun drawSelection(ui: UiRenderer, a: Float, ship: DVec3) {
        val name: String
        val pos: DVec3
        val radius: Double
        val kindLabel: String
        var parentName: String? = null
        if (selKind == 2) {
            val s = system.stations[selIndex]
            name = s.name; pos = s.pos; radius = s.radius
            kindLabel = "STATION  ·  ORBITING"
            parentName = system.bodies[s.parent].name
        } else {
            val b = system.bodies[selIndex]
            name = b.name; pos = b.pos; radius = b.radius
            kindLabel = typeLabel(b.type)
        }
        val pw = 250f * u
        val x = w - sr - 18f * u - pw
        val y = h - sb - 18f * u - 44f * u - 12f * u - 96f * u
        ui.rect(x, y, pw, 96f * u, withAlpha(Theme.PANEL, a * 0.95f), -10f * u)
        ui.rect(x, y, pw, 96f * u, withAlpha(Theme.LOCK, a * 0.5f), -10f * u, 1.2f * u)
        ui.text(name, x + 14f * u, y + 26f * u, 15f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f, face = FontData.TITLE)
        val kw = ui.text(kindLabel, x + 14f * u, y + 44f * u, 9f * u, withAlpha(Theme.LOCK, a), tracking = 0.15f)
        if (parentName != null) ui.text(parentName, x + 20f * u + kw, y + 44f * u, 9f * u, withAlpha(Theme.LOCK, a), tracking = 0.15f)
        val dx = pos.x - ship.x
        val dy = pos.y - ship.y
        val dz = pos.z - ship.z
        val d = max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - radius)
        buf.clear()
        val unit = distanceText(buf, d)
        ui.text("DISTANCE", x + 14f * u, y + 68f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        val tw = ui.text(buf, x + 14f * u, y + 84f * u, 13f * u, withAlpha(Theme.TEXT, a), face = FontData.MONO)
        ui.text(unit, x + 18f * u + tw, y + 84f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a))
        // estimated time at the cruise limit (3 Mm/s, slower near the ends)
        val eta = (d / StarSystem.MAX_SPEED + 25.0).toInt()
        buf.clear().append("ETA ").append(eta / 60).append(':').appendPadded(eta % 60, 2)
        ui.text(buf, x + pw - 14f * u, y + 84f * u, 11f * u, withAlpha(Theme.TEXT_DIM, a), UiRenderer.RIGHT, face = FontData.MONO)
    }

    private fun drawGalaxy(ui: UiRenderer, a: Float, time: Float) {
        val cx = w * 0.5f
        val cy = h * 0.52f
        val scale = min(w, h) * 1.1f
        for (i in 0 until galaxy.size / 3) {
            val gx = cx + galaxy[i * 3] * scale
            val gy = cy + galaxy[i * 3 + 1] * scale
            val b = galaxy[i * 3 + 2]
            ui.circle(gx, gy, (0.8f + b) * u, withAlpha(0xFF9FB4CC.toInt(), a * b * 0.6f))
        }
        val pulse = 0.6f + 0.4f * kotlin.math.sin(time * 3f)
        ui.circle(cx, cy, 6f * u, withAlpha(Theme.OK, a))
        ui.circle(cx, cy, 14f * u, withAlpha(Theme.OK, a * pulse), 1.5f * u)
        ui.text(system.name, cx, cy + 32f * u, 12f * u, withAlpha(Theme.TEXT, a), UiRenderer.CENTER, tracking = 0.2f, face = FontData.TITLE)
        ui.text("YOU ARE HERE", cx, cy + 48f * u, 9f * u, withAlpha(Theme.OK, a), UiRenderer.CENTER, tracking = 0.25f)
        ui.text("UNCHARTED SPACE  ·  OTHER SYSTEMS ARRIVE WITH THE FULL PORT", cx, h - sb - 24f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), UiRenderer.CENTER, tracking = 0.15f)
    }

    private fun bodyColor(b: StarSystem.Body): Int = when (b.type) {
        StarSystem.BodyType.STAR -> 0xFFFFE2A8.toInt()
        StarSystem.BodyType.LAVA -> 0xFFE0703A.toInt()
        StarSystem.BodyType.TERRAN -> 0xFF5C9CE0.toInt()
        StarSystem.BodyType.GAS -> 0xFFD9C3A0.toInt()
        StarSystem.BodyType.ICE -> 0xFFCDE6FA.toInt()
        StarSystem.BodyType.ROCK -> 0xFF9C8C7C.toInt()
    }

    private fun typeLabel(t: StarSystem.BodyType): String = when (t) {
        StarSystem.BodyType.STAR -> "STAR"
        StarSystem.BodyType.LAVA -> "VOLCANIC WORLD"
        StarSystem.BodyType.TERRAN -> "OCEAN WORLD"
        StarSystem.BodyType.GAS -> "RINGED GAS GIANT"
        StarSystem.BodyType.ICE -> "ICE WORLD"
        StarSystem.BodyType.ROCK -> "ROCKY WORLD"
    }

    companion object {
        const val TAB_SYSTEM = 0
        const val TAB_GALAXY = 1
        private const val MAP_BG = 0xF2050A10.toInt()
        private const val ORBIT = 0x553F5F7A
        private const val MIN_MPP = 50f
        private const val MAX_MPP = 3.0e7f

        /** 1, 2 or 5 × 10^n not above [m]. */
        fun niceLength(m: Double): Double {
            if (m <= 0.0) return 1.0
            val e = kotlin.math.floor(ln(m) / ln(10.0))
            val base = exp(e * ln(10.0))
            val f = m / base
            return base * when {
                f >= 5 -> 5.0
                f >= 2 -> 2.0
                else -> 1.0
            }
        }

        /** Appends a distance number to [b] and returns its unit (M, KM, MM). */
        fun distanceText(b: TextBuf, m: Double): String = when {
            m < 1000.0 -> {
                b.append(m.toInt()); "M"
            }
            m < 1.0e6 -> {
                b.append((m / 1000.0).toFloat(), if (m < 1.0e4) 2 else 1); "KM"
            }
            else -> {
                b.append((m / 1.0e6).toFloat(), if (m < 1.0e7) 2 else 0); "MM"
            }
        }
    }
}
