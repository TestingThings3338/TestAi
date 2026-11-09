package com.starwake.core.ui

import com.starwake.core.gfx.FontData
import com.starwake.core.hud.Units
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
        if (selKind == 3) {
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

    // label boxes: 4 panels, the ship marker and YOU, a disc and a name per body, a name per station
    private val labels = LabelBoxes(6 + 2 * system.bodies.size + system.stations.size)

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

    /** Distance (m) from the star of the farthest planet: the opening zoom fits this circle. */
    private val outerOrbit: Double = run {
        var r = 0.0
        val s = system.bodies[system.star].pos
        for (b in system.bodies) {
            if (b.parent != system.star) continue
            val dx = b.pos.x - s.x
            val dz = b.pos.z - s.z
            r = max(r, sqrt(dx * dx + dz * dz))
        }
        if (r > 0.0) r else system.edgeRadius
    }

    /** Called when the map opens: centre on the ship and pick a zoom that shows its surroundings. */
    fun open(ship: DVec3, lockedKind: Int, lockedIndex: Int) {
        tab = TAB_SYSTEM
        centerX = ship.x
        centerZ = ship.z
        // fit the planets' orbits (Neptune at 20.5 Gm in Sol) into the shorter screen side
        metresPerPx = clamp((outerOrbit * 2.2 / min(w, h)).toFloat(), MIN_MPP, MAX_MPP).toDouble()
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
            // two fingers: the system point under their midpoint stays under it, so the pinch
            // zooms about the fingers (not the screen centre) and moving both fingers pans
            val wx = worldX((px[0] + px[1]) * 0.5f)
            val wz = worldZ((py[0] + py[1]) * 0.5f)
            px[slot] = x; py[slot] = y
            val d = dist()
            if (tab == TAB_SYSTEM) {
                if (pinchDist > 1f && d > 1f) {
                    metresPerPx = clamp((metresPerPx * pinchDist / d).toFloat(), MIN_MPP, MAX_MPP).toDouble()
                }
                // anchor with the clamped zoom so the point stays put at the zoom limits too
                anchor(wx, wz, (px[0] + px[1]) * 0.5f, (py[0] + py[1]) * 0.5f)
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

    /**
     * Mouse-wheel / gamepad style zoom step (factor > 1 zooms out) about the screen point
     * ([fx], [fy]), the screen centre by default: the system point under it stays put.
     */
    fun zoom(factor: Float, fx: Float = w * 0.5f, fy: Float = h * 0.5f) {
        val wx = worldX(fx)
        val wz = worldZ(fy)
        metresPerPx = clamp((metresPerPx * factor).toFloat(), MIN_MPP, MAX_MPP).toDouble()
        anchor(wx, wz, fx, fy)
    }

    /** Pans so that system point ([wx], [wz]) sits at screen point ([x], [y]) at the current zoom. */
    private fun anchor(wx: Double, wz: Double, x: Float, y: Float) {
        centerX = wx - (x - w * 0.5) * metresPerPx
        centerZ = wz - (y - h * 0.5) * metresPerPx
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
        // only what is drawn can be picked: moons and stations once they stand clear of their parent
        for (i in system.stations.indices) {
            val p = system.stations[i].pos
            if (!separated(p.x, p.z, system.stations[i].parent)) continue
            val d = screenDist(p, x, y)
            if (d < bestD) {
                bestD = d; best = 2; bestIdx = i
            }
        }
        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            if (isMoon(i) && !separated(b.pos.x, b.pos.z, b.parent)) continue
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

    /** Current zoom of the system tab, in metres per pixel. */
    val metresPerPixel: Double get() = metresPerPx

    /** Screen x (px) of system coordinate [x] (metres) at the current pan and zoom. */
    fun screenX(x: Double): Float = (w * 0.5 + (x - centerX) / metresPerPx).toFloat()

    /** Screen y (px) of system coordinate [z] (metres); the map looks down the y axis. */
    fun screenY(z: Double): Float = (h * 0.5 + (z - centerZ) / metresPerPx).toFloat()

    /** System x (metres) under screen x [x]: the inverse of [screenX]. */
    fun worldX(x: Float): Double = centerX + (x - w * 0.5) * metresPerPx

    /** System z (metres) under screen y [y]: the inverse of [screenY]. */
    fun worldZ(y: Float): Double = centerZ + (y - h * 0.5) * metresPerPx

    private fun screenDist(p: DVec3, x: Float, y: Float): Float {
        val dx = screenX(p.x) - x
        val dy = screenY(p.z) - y
        return sqrt(dx * dx + dy * dy)
    }

    /** Drawn radius: true scale when zoomed in, a readable minimum otherwise (moons smaller). */
    private fun bodyPx(b: StarSystem.Body): Float {
        val trueR = (b.radius / metresPerPx).toFloat()
        val min = when {
            b.isStar -> 9f * u
            b.parent != system.star -> 3.5f * u
            else -> 5f * u
        }
        return max(trueR, min)
    }

    /**
     * A moon or a station is drawn and named only once it stands clear of its parent on screen
     * ([SEPARATE] u between the centres, beyond the parent's disc): zoomed out, Luna sits on Earth.
     */
    private fun separated(x: Double, z: Double, parent: Int): Boolean {
        val p = system.bodies[parent]
        val dx = (x - p.pos.x) / metresPerPx
        val dz = (z - p.pos.z) / metresPerPx
        val d = sqrt(dx * dx + dz * dz).toFloat()
        return d > bodyPx(p) + SEPARATE * u
    }

    /** True when body [i] is a moon (its parent is not the star). */
    private fun isMoon(i: Int): Boolean {
        val p = system.bodies[i].parent
        return p >= 0 && p != system.star
    }

    // ------------------------------------------------------------------ drawing

    fun draw(ui: UiRenderer, a: Float, ship: DVec3, shipRot: Quat, time: Float) {
        // stations are selectable for their card but not lockable from the map (Roblox, DECISIONS 21)
        lock.visible = tab == TAB_SYSTEM && selKind == 3
        ui.rect(0f, 0f, w, h, withAlpha(MAP_BG, a))
        if (tab == TAB_SYSTEM) drawSystem(ui, a, ship, shipRot, time) else drawGalaxy(ui, a, time)
        // tab highlight
        val t = if (tab == TAB_SYSTEM) tabSystem.r else tabGalaxy.r
        ui.rect(t.x, t.y + t.h + 3f * u, t.w, 2f * u, withAlpha(Theme.ACCENT, a))
        group.draw(ui, u, a)
    }

    private fun drawSystem(ui: UiRenderer, a: Float, ship: DVec3, shipRot: Quat, time: Float) {
        // labels go on after every shape (so discs and markers never cover text), dodging the boxes
        // collected here: panels, small discs, the ship marker and the labels placed before them
        val lb = labels
        lb.reset(sl, st, w - sr, h - sb)

        // scale bar
        val barM = niceLength(metresPerPx * 120f * u)
        val barPx = (barM / metresPerPx).toFloat()
        val bx = sl + 22f * u
        val by = h - sb - 26f * u
        ui.rect(bx, by, barPx, 2f * u, withAlpha(Theme.TEXT_DIM, a))
        ui.rect(bx, by - 5f * u, 1.5f * u, 12f * u, withAlpha(Theme.TEXT_DIM, a))
        ui.rect(bx + barPx - 1.5f * u, by - 5f * u, 1.5f * u, 12f * u, withAlpha(Theme.TEXT_DIM, a))
        buf.clear()
        val unit = Units.distanceParts(buf, barM)
        val tw = ui.text(buf, bx, by - 10f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), face = FontData.MONO)
        val uw = ui.text(unit, bx + tw + 4f * u, by - 10f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a))
        lb.add(bx, by - 22f * u, bx + max(barPx, tw + 4f * u + uw), by + 7f * u)

        ui.text(system.displayName, sl + 22f * u, st + 78f * u, 15f * u, withAlpha(Theme.TEXT, a), tracking = 0.2f, face = FontData.TITLE)
        val hw = ui.text(HINT, sl + 22f * u, st + 96f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        // tabs, title and hint (top left), CLOSE, and the selection card with LOCK TARGET
        lb.add(sl, 0f, max(tabGalaxy.r.x + tabGalaxy.r.w, sl + 22f * u + hw), st + 100f * u)
        lb.add(close.r.x, close.r.y, close.r.x + close.r.w, close.r.y + close.r.h)
        if (selKind != 0) lb.add(w - sr - 18f * u - CARD_W * u, h - sb - 18f * u - 44f * u - 12f * u - CARD_H * u, w - sr, h - sb)

        val star = system.bodies[system.star]
        // orbits: planets around the star, moons around their planet (once they stand clear of it),
        // through each body's current position, in the x/z plane
        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            if (b.parent < 0) continue
            val p = system.bodies[b.parent]
            val ox = b.pos.x - p.pos.x
            val oz = b.pos.z - p.pos.z
            val r = (sqrt(ox * ox + oz * oz) / metresPerPx).toFloat()
            if (b.parent != system.star && r < bodyPx(p) + SEPARATE * u) continue
            if (r > 4f && r < 20000f) ui.circle(screenX(p.pos.x), screenY(p.pos.z), r, withAlpha(ORBIT, a * (if (b.parent == system.star) 1f else 0.7f)), 1f * u)
        }
        val edge = (system.edgeRadius / metresPerPx).toFloat()
        if (edge < 20000f) ui.circle(screenX(star.pos.x), screenY(star.pos.z), edge, withAlpha(Theme.DANGER, a * 0.25f), 1f * u)

        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            if (isMoon(i) && !separated(b.pos.x, b.pos.z, b.parent) && !(selKind == 3 && selIndex == i)) continue
            val x = screenX(b.pos.x)
            val y = screenY(b.pos.z)
            val r = bodyPx(b)
            if (x < -r - 200f || x > w + r + 200f || y < -r - 200f || y > h + r + 200f) continue
            val col = bodyColor(b)
            ui.circle(x, y, r, withAlpha(col, a))
            if (b.hasRings) ui.circle(x, y, max((b.ringOuter / metresPerPx).toFloat(), r + 4f * u), withAlpha(ringColor(b), a * 0.6f), 1.2f * u)
            if (selKind == 3 && selIndex == i) ui.circle(x, y, r + 7f * u, withAlpha(Theme.LOCK, a), 1.6f * u)
            // small discs keep other labels off them; a zoomed-in planet is a backdrop text may cross
            val e = bodyExtent(b, r)
            if (e < DISC_BLOCK * u) lb.add(x - e, y - e, x + e, y + e, i)
        }
        for (i in system.stations.indices) {
            val s = system.stations[i]
            val sel = selKind == 2 && selIndex == i
            if (!sel && !separated(s.pos.x, s.pos.z, s.parent)) continue
            val x = screenX(s.pos.x)
            val y = screenY(s.pos.z)
            if (x < -100f || x > w + 100f || y < -100f || y > h + 100f) continue
            val col = if (sel) Theme.LOCK else Theme.ACCENT
            ui.rect(x - 4f * u, y - 4f * u, 8f * u, 8f * u, withAlpha(col, a), -2f * u)
        }

        // the ship: heading arrow in the x/z plane
        val x = screenX(ship.x)
        val y = screenY(ship.z)
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
        lb.add(x - SHIP_BOX * u, y - SHIP_BOX * u, x + SHIP_BOX * u, y + SHIP_BOX * u, LabelBoxes.OWNER_SHIP)

        // labels by priority: the selection (always drawn), YOU, the star, the planets, the moons, stations
        if (selKind == 3) bodyLabel(ui, a, selIndex, true) else if (selKind == 2) stationLabel(ui, a, selIndex, true)
        val size = 9f * u
        if (lb.place(x, y, 12f * u, 10f * u, ui.textWidth(YOU, size, 0.2f), ui.capHeight(size), LABEL_PAD * u, YOU_SIDES, LabelBoxes.OWNER_SHIP, false)) {
            ui.text(YOU, lb.textX, lb.textY, size, withAlpha(Theme.OK, a), tracking = 0.2f)
        }
        for (pass in 0..1) {
            for (i in system.bodies.indices) {
                if (isMoon(i) != (pass == 1)) continue
                if (selKind == 3 && selIndex == i) continue
                if (pass == 1 && !separated(system.bodies[i].pos.x, system.bodies[i].pos.z, system.bodies[i].parent)) continue
                bodyLabel(ui, a, i, false)
            }
        }
        // a station is named once it stands clear of its host
        for (i in system.stations.indices) {
            val s = system.stations[i]
            if ((selKind != 2 || selIndex != i) && separated(s.pos.x, s.pos.z, s.parent)) stationLabel(ui, a, i, false)
        }

        if (selKind != 0) drawSelection(ui, a, ship)
    }

    /** Ring radius for ringed worlds, else the disc radius: how far a label keeps from the centre. */
    private fun bodyExtent(b: StarSystem.Body, r: Float): Float =
        if (b.hasRings) max((b.ringOuter / metresPerPx).toFloat(), r + 4f * u) else r

    /** Name under (or, when that spot is taken, above / right of / left of) body [i]. [sel] always draws it. */
    private fun bodyLabel(ui: UiRenderer, a: Float, i: Int, sel: Boolean) {
        val b = system.bodies[i]
        val x = screenX(b.pos.x)
        val y = screenY(b.pos.z)
        val r = bodyPx(b)
        if (x < -r - 200f || x > w + r + 200f || y < -r - 200f || y > h + r + 200f) return
        val size = (if (isMoon(i)) 8.5f else 9.5f) * u
        val ch = ui.capHeight(size)
        // the old spacing (baseline 15u below the disc), never closer than the selection ring
        val gap = max(15f * u - ch, 8.5f * u)
        val name = b.displayName
        if (labels.place(x, y, bodyExtent(b, r), gap, ui.textWidth(name, size, 0.18f), ch, LABEL_PAD * u, BODY_SIDES, i, sel, LABEL_FAR * u)) {
            val col = if (sel) Theme.LOCK else if (isMoon(i)) Theme.TEXT_DIM else Theme.TEXT
            ui.text(name, labels.textX, labels.textY, size, withAlpha(col, a), tracking = 0.18f)
        }
    }

    /** Name right of (or left of / below / above) station [i]. [sel] always draws it. */
    private fun stationLabel(ui: UiRenderer, a: Float, i: Int, sel: Boolean) {
        val s = system.stations[i]
        val x = screenX(s.pos.x)
        val y = screenY(s.pos.z)
        if (x < -100f || x > w + 100f || y < -100f || y > h + 100f) return
        val size = 9f * u
        val tw = ui.textWidth(s.name, size, 0.15f)
        if (labels.place(x, y, 4f * u, 5f * u, tw, ui.capHeight(size), LABEL_PAD * u, STATION_SIDES, LabelBoxes.NO_OWNER, sel, LABEL_FAR * u)) {
            ui.text(s.name, labels.textX, labels.textY, size, withAlpha(if (sel) Theme.LOCK else Theme.ACCENT, a), tracking = 0.15f)
        }
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
            parentName = system.bodies[s.parent].displayName
        } else {
            val b = system.bodies[selIndex]
            name = b.displayName; pos = b.pos; radius = b.radius
            kindLabel = b.classLabel
            if (isMoon(selIndex)) parentName = system.bodies[b.parent].displayName
        }
        val pw = CARD_W * u
        val x = w - sr - 18f * u - pw
        val y = h - sb - 18f * u - 44f * u - 12f * u - CARD_H * u
        ui.rect(x, y, pw, CARD_H * u, withAlpha(Theme.PANEL, a * 0.95f), -10f * u)
        ui.rect(x, y, pw, CARD_H * u, withAlpha(Theme.LOCK, a * 0.5f), -10f * u, 1.2f * u)
        ui.text(name, x + 14f * u, y + 26f * u, 15f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f, face = FontData.TITLE)
        val kw = ui.text(kindLabel, x + 14f * u, y + 44f * u, 9f * u, withAlpha(Theme.LOCK, a), tracking = 0.15f)
        if (parentName != null) {
            val ow = if (selKind == 2) 0f else ui.text(OF, x + 20f * u + kw, y + 44f * u, 9f * u, withAlpha(Theme.LOCK, a), tracking = 0.15f) + 6f * u
            ui.text(parentName, x + 20f * u + kw + ow, y + 44f * u, 9f * u, withAlpha(Theme.LOCK, a), tracking = 0.15f)
        }
        val dx = pos.x - ship.x
        val dy = pos.y - ship.y
        val dz = pos.z - ship.z
        val d = max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - radius)
        buf.clear()
        val unit = Units.distanceParts(buf, d)
        ui.text("DISTANCE", x + 14f * u, y + 68f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        val tw = ui.text(buf, x + 14f * u, y + 84f * u, 13f * u, withAlpha(Theme.TEXT, a), face = FontData.MONO)
        ui.text(unit, x + 18f * u + tw, y + 84f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a))
        val eta = etaSeconds(d).toInt()
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
        ui.text(system.displayName, cx, cy + 32f * u, 12f * u, withAlpha(Theme.TEXT, a), UiRenderer.CENTER, tracking = 0.2f, face = FontData.TITLE)
        ui.text("YOU ARE HERE", cx, cy + 48f * u, 9f * u, withAlpha(Theme.OK, a), UiRenderer.CENTER, tracking = 0.25f)
        ui.text("UNCHARTED SPACE  ·  OTHER SYSTEMS ARRIVE WITH THE FULL PORT", cx, h - sb - 24f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), UiRenderer.CENTER, tracking = 0.15f)
    }

    /** The body's Roblox colour (maps use the data colour, 03-world.md 2.7.3), the star a warm white. */
    private fun bodyColor(b: StarSystem.Body): Int = if (b.isStar) STAR_COLOUR else (0xFF000000.toInt() or b.colour)

    private fun ringColor(b: StarSystem.Body): Int = 0xFF000000.toInt() or b.ringColour

    companion object {
        const val TAB_SYSTEM = 0
        const val TAB_GALAXY = 1
        private const val MAP_BG = 0xF2050A10.toInt()
        private const val ORBIT = 0x553F5F7A
        private const val STAR_COLOUR = 0xFFFFE2A8.toInt()
        const val MIN_MPP = 50f

        /** Zoomed out to the play field's edge (27 Gm in Sol). */
        const val MAX_MPP = 1.0e8f
        private const val HINT = "PINCH TO ZOOM  ·  DRAG TO PAN  ·  TAP TO SELECT"
        private const val YOU = "YOU"
        private const val OF = "OF"

        // selection card size, clear margin around labels, the extra distance of the fallback
        // label spots (clears the ship marker), ship marker half-size, the disc radius above
        // which labels may cross a body, and how far a moon or station must stand clear of its
        // parent's disc to be drawn and named (all in u)
        private const val CARD_W = 250f
        private const val CARD_H = 96f
        private const val LABEL_PAD = 2f
        private const val LABEL_FAR = 10f
        private const val SHIP_BOX = 13f
        private const val DISC_BLOCK = 32f
        private const val SEPARATE = 14f

        // label spots to try, best first: bodies keep their name below, stations to the right,
        // YOU at the arrow's upper right
        private val BODY_SIDES = intArrayOf(LabelBoxes.BELOW, LabelBoxes.ABOVE, LabelBoxes.RIGHT, LabelBoxes.LEFT)
        private val STATION_SIDES = intArrayOf(LabelBoxes.RIGHT, LabelBoxes.LEFT, LabelBoxes.BELOW, LabelBoxes.ABOVE)
        private val YOU_SIDES = intArrayOf(LabelBoxes.UP_RIGHT, LabelBoxes.DOWN_RIGHT, LabelBoxes.UP_LEFT, LabelBoxes.DOWN_LEFT)

        /**
         * Rough trip time (s) to a target [d] metres away under the pre-port speed law
         * ([StarSystem.speedLimit]): the approach limit is APPROACH_RATE·altitude, capped at
         * MAX_SPEED, so the last stretch takes ln(d/1 km)/rate, plus the cruise at the cap and a
         * departure allowance.
         */
        fun etaSeconds(d: Double): Double {
            if (d <= 1000.0) return d / 220.0
            val rate = StarSystem.APPROACH_RATE
            val capAlt = StarSystem.MAX_SPEED / rate
            val approach = if (d > capAlt) (d - capAlt) / StarSystem.MAX_SPEED + ln(capAlt / 1000.0) / rate else ln(d / 1000.0) / rate
            return approach + ETA_DEPARTURE
        }

        /** Departure allowance of [etaSeconds] (s): leaving a station or a planet's low orbit. */
        private const val ETA_DEPARTURE = 15.0

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
    }
}

/**
 * Greedy placement of map labels: a fixed-size list of taken screen boxes (labels already drawn
 * plus the markers and panels a label must not cover). Allocation free after construction, so it
 * runs every frame.
 */
internal class LabelBoxes(capacity: Int) {
    private val box = FloatArray(capacity * 4) // left, top, right, bottom
    private val owner = IntArray(capacity)
    private var minX = 0f
    private var minY = 0f
    private var maxX = 0f
    private var maxY = 0f

    /** Boxes taken since the last [reset]. */
    var count = 0
        private set

    /** Left edge of the text placed by the last successful [place]. */
    var textX = 0f
        private set

    /** Baseline of the text placed by the last successful [place]. */
    var textY = 0f
        private set

    /** Forgets every box; labels must stay inside ([left], [top]) - ([right], [bottom]). */
    fun reset(left: Float, top: Float, right: Float, bottom: Float) {
        count = 0
        minX = left; minY = top; maxX = right; maxY = bottom
    }

    /**
     * Marks a box as taken. A label placed with `self == owner` may overlap it (a body's name and
     * its own disc, YOU and the ship marker); [NO_OWNER] boxes block every label.
     */
    fun add(left: Float, top: Float, right: Float, bottom: Float, owner: Int = NO_OWNER) {
        if (count == this.owner.size) return // sized for the system, so only a misuse lands here
        val o = count * 4
        box[o] = left; box[o + 1] = top; box[o + 2] = right; box[o + 3] = bottom
        this.owner[count] = owner
        count++
    }

    /** True when the box is on screen and overlaps no taken box except those owned by [self]. */
    fun isFree(left: Float, top: Float, right: Float, bottom: Float, self: Int): Boolean {
        if (left < minX || top < minY || right > maxX || bottom > maxY) return false
        for (k in 0 until count) {
            if (self != NO_OWNER && owner[k] == self) continue
            val o = k * 4
            if (left < box[o + 2] && box[o] < right && top < box[o + 3] && box[o + 1] < bottom) return false
        }
        return true
    }

    /**
     * Places a label [tw] wide with cap height [ch] next to a marker at ([x], [y]) of half-size
     * [e], [gap] away from it, trying [sides] in order (see [BELOW] etc.) and keeping [pad] clear
     * around the text. If no side fits and [far] > 0, the sides are tried once more [far] further
     * out (a station under the ship marker still gets its name). When a spot fits, its box is
     * taken, [textX]/[textY] hold the text's left edge and baseline, and it returns true. With
     * [force] (the selection) the first side is used even when nothing fits; otherwise the label is
     * skipped and it returns false.
     */
    fun place(
        x: Float, y: Float, e: Float, gap: Float, tw: Float, ch: Float, pad: Float,
        sides: IntArray, self: Int, force: Boolean, far: Float = 0f,
    ): Boolean {
        var g = gap
        for (ring in 0 until 2) {
            for (k in sides.indices) {
                spot(sides[k], x, y, e, g, tw, ch)
                if (isFree(textX - pad, textY - ch - pad, textX + tw + pad, textY + pad, self)) {
                    add(textX - pad, textY - ch - pad, textX + tw + pad, textY + pad)
                    return true
                }
            }
            if (far <= 0f) break
            g += far
        }
        if (!force) return false
        spot(sides[0], x, y, e, gap, tw, ch)
        add(textX - pad, textY - ch - pad, textX + tw + pad, textY + pad)
        return true
    }

    /** Text origin (left, baseline) of [side] into [textX]/[textY]. */
    private fun spot(side: Int, x: Float, y: Float, e: Float, gap: Float, tw: Float, ch: Float) {
        when (side) {
            BELOW -> { textX = x - tw * 0.5f; textY = y + e + gap + ch }
            ABOVE -> { textX = x - tw * 0.5f; textY = y - e - gap }
            RIGHT -> { textX = x + e + gap; textY = y + ch * 0.5f }
            LEFT -> { textX = x - e - gap - tw; textY = y + ch * 0.5f }
            // corners: [e] is the horizontal offset from the marker, [gap] the vertical one
            UP_RIGHT -> { textX = x + e; textY = y - gap }
            DOWN_RIGHT -> { textX = x + e; textY = y + gap + ch }
            UP_LEFT -> { textX = x - e - tw; textY = y - gap }
            else -> { textX = x - e - tw; textY = y + gap + ch }
        }
    }

    companion object {
        /** Owner of boxes that block every label; also the [place] self for labels that own nothing. */
        const val NO_OWNER = -1

        /** Owner of the ship marker, so only YOU may touch it. */
        const val OWNER_SHIP = Int.MAX_VALUE

        const val BELOW = 0
        const val ABOVE = 1
        const val RIGHT = 2
        const val LEFT = 3
        const val UP_RIGHT = 4
        const val DOWN_RIGHT = 5
        const val UP_LEFT = 6
        const val DOWN_LEFT = 7
    }
}
