package com.starwake.core.hud

import com.starwake.core.Settings
import com.starwake.core.game.Session
import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Frame
import com.starwake.core.ui.TextBuf
import com.starwake.core.ui.Theme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The flat flight HUD of the pre-port game, drawn over the session's scene: nose reticle and
 * velocity marker, the stick's steering arrow, target markers and the lock (with the off-screen
 * arrow), the landing ladder, the speed readout beside the lever, zone and warning lines, the
 * message line, and the touch controls. `06` (FlightHud, TargetMarkers, Notices) and `02`
 * (FlightArrow, AttitudeHud) replace it piece by piece. Numbers go through [Units]. No allocation.
 */
class SessionHud(private val frame: Frame, private val settings: Settings) {
    private val buf = TextBuf(64)
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val screenPt = Vec3()
    private val upV = Vec3()
    private val fwdV = Vec3()
    private val rightV = Vec3()

    /** Target labels placed this frame (x0, y0, x1, y1), so they never overlap. */
    private val labelBoxes = FloatArray(4 * MAX_LABELS)
    private var labelCount = 0

    /** First-flight hints, 0..1 (fade once lever and stick were used, or after [HINT_TIME] on screen). */
    var hints = 1f
        private set

    /** Seconds the hints have been on screen this session. */
    var hintTime = 0f
        private set

    /** The last [draw] showed the locked target, off screen, with the edge arrow (harness read-out). */
    var lockArrowShown = false
        private set

    /** A new flight: the hints show again, for [HINT_TIME] of their own screen time. */
    fun resetHints() {
        hints = 1f
        hintTime = 0f
    }

    /**
     * Fades the first-flight hints. Call once per simulated frame, and only while the flight HUD is
     * on screen: the hints count their own screen time, so the station services of a docked start
     * (D13), a map or the curtain do not use them up before the pilot has seen them.
     */
    fun updateHints(dt: Float, touch: TouchFlightControls) {
        hintTime += dt
        if (touch.throttleUsed && touch.stickUsed) hints -= dt * 0.8f
        else if (hintTime > HINT_TIME) hints -= dt * 0.3f
        hints = clamp(hints, 0f, 1f)
    }

    fun draw(ui: UiRenderer, s: Session, cam: Camera, touch: TouchFlightControls, alpha: Float, time: Float) {
        val u = frame.u
        val a = alpha
        val ship = s.ship
        lockArrowShown = false
        // reticle where the nose points (800 m ahead) and the velocity vector marker
        ship.rot.rotate(0f, 0f, -800f, tmp)
        relToCam(s, cam, tmp)
        if (cam.project(tmp.x, tmp.y, tmp.z, screenPt)) {
            val x = screenPt.x
            val y = screenPt.y
            val col = withAlpha(Theme.TEXT, a * 0.85f)
            ui.circle(x, y, 13f * u, col, 1.5f * u)
            ui.rect(x - 24f * u, y - 0.75f * u, 9f * u, 1.5f * u, col)
            ui.rect(x + 15f * u, y - 0.75f * u, 9f * u, 1.5f * u, col)
            ui.rect(x - 0.75f * u, y - 24f * u, 1.5f * u, 9f * u, col)
            ui.circle(x, y, 1.6f * u, col)
        }
        if (ship.speed > 3f) {
            tmp.set(ship.vel).scale(800f / ship.speed)
            relToCam(s, cam, tmp)
            if (cam.project(tmp.x, tmp.y, tmp.z, screenPt)) {
                val x = screenPt.x
                val y = screenPt.y
                val col = withAlpha(if (ship.flightAssist) Theme.ACCENT else Theme.WARN, a * 0.9f)
                ui.circle(x, y, 7f * u, col, 1.5f * u)
                ui.rect(x - 0.75f * u, y - 15f * u, 1.5f * u, 8f * u, col)
                ui.rect(x - 15f * u, y - 0.75f * u, 8f * u, 1.5f * u, col)
                ui.rect(x + 7f * u, y - 0.75f * u, 8f * u, 1.5f * u, col)
            }
        }

        // the flight cursor hides while the pilot looks around (Roblox C35)
        if (!s.mode.looking) drawSteeringArrow(ui, touch, a)
        drawTargets(ui, s, cam, a)
        drawLandingHud(ui, s, cam, a)

        // speed readout beside the throttle lever (keeps the centre clear for the ship)
        val g = touch.gauge
        val mirrored = settings.leftHanded
        val sx = if (mirrored) g.x - 26f * u else g.x + g.w + 26f * u
        val align = if (mirrored) UiRenderer.RIGHT else UiRenderer.LEFT
        val sy = g.y + g.h
        val speed = ship.forwardSpeed
        val unit = Units.speedParts(buf.clear(), abs(speed).toDouble())
        val numW = ui.text(buf, sx, sy - 12f * u, 28f * u, withAlpha(if (speed < -1f) Theme.WARN else Theme.TEXT, a), align, 0f, 0.02f, shadow = 0.7f, face = FontData.MONO)
        val unitX = if (mirrored) sx - numW - 6f * u else sx + numW + 6f * u
        ui.text(unit, unitX, sy - 12f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), align, tracking = 0.15f, shadow = 0.7f)
        // boost capacitor bar
        val spec = s.spec
        val bw = 104f * u
        val bx = if (mirrored) sx - bw else sx
        ui.rect(bx, sy - 3f * u, bw, 4f * u, withAlpha(Theme.PANEL, a * 0.9f), 2f * u)
        val capCol = if (ship.capacitor >= spec.boostCost) Theme.ACCENT else Theme.TEXT_DIM
        ui.rect(bx, sy - 3f * u, bw * ship.capacitor, 4f * u, withAlpha(capCol, a), 2f * u)
        ui.rect(bx + bw * spec.boostCost, sy - 6f * u, 1.2f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f))
        // zone, flight-assist and proximity warnings (top centre)
        val cx = frame.w * 0.5f
        val here = s.here
        val system = s.system
        ui.textCentered(s.zoneLabel, cx, frame.st + 30f * u, 10.5f * u, withAlpha(Theme.TEXT, a * 0.85f), tracking = 0.3f, shadow = 0.6f)
        // below the first flight's "DRAG HERE TO LOOK AROUND" while it shows (it sits at the look strip's foot)
        var warnY = frame.st + 52f * u + if (hints > 0.01f && touch.lookEnabled) 16f * u else 0f
        if (drawFlightData(ui, s, cx, warnY, a, time)) warnY += 38f * u
        if (here.nearestBody == system.star && here.altitude < system.bodies[system.star].radius * 0.5) {
            val blink = if ((time * 3f).toInt() % 2 == 0) 1f else 0.45f
            ui.textCentered("HEAT", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (s.pullUp) {
            val blink = if ((time * 5f).toInt() % 2 == 0) 1f else 0.35f
            ui.textCentered("PULL UP", cx, warnY, 15f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
            warnY += 22f * u
        }
        // the approach prompt of a giant (Roblox ShipAtmosphere reads LandingBlocked, DECISIONS 11)
        if (here.zone == com.starwake.core.game.StarSystem.Zone.LOW_ALTITUDE && here.nearestBody >= 0 &&
            system.bodies[here.nearestBody].landingBlocked != null
        ) {
            ui.textCentered(Session.LANDING_BLOCKED_GAS, cx, warnY, 11f * u, withAlpha(Theme.WARN, a), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (!ship.flightAssist) {
            val blink = if ((time * 2f).toInt() % 2 == 0) 1f else 0.55f
            ui.textCentered("FLIGHT ASSIST OFF", cx, warnY, 11f * u, withAlpha(Theme.WARN, a * blink), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        drawMessage(ui, s, a)
        if (s.proximity < 60f) {
            val blink = if ((time * 4f).toInt() % 2 == 0) 1f else 0.4f
            ui.textCentered("PROXIMITY", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
        }

        touch.draw(
            ui, a, ship.forwardSpeed / spec.maxSpeed, ship.flightAssist,
            ship.capacitor >= spec.boostCost, ship.boosting, ship.capacitor, hints, s.lockKind != Session.LOCK_NONE,
        )
    }

    /**
     * The flight data of a planet's neighbourhood, under the zone line: height above the real
     * ground, climb rate and local gravity while below [ALT_READOUT]; the hull's skin temperature
     * as a bar while the air heats it; the hull's integrity once it is damaged. True when anything
     * was drawn (the warnings move down).
     */
    private fun drawFlightData(ui: UiRenderer, s: Session, cx: Float, y: Float, a: Float, time: Float): Boolean {
        val u = frame.u
        val here = s.here
        val nb = here.nearestBody
        if (nb < 0 || s.mode.docked) return false
        val body = s.system.bodies[nb]
        val planet = body.type != com.starwake.core.game.StarSystem.BodyType.STAR && here.altitude < ALT_READOUT
        val hot = s.heat > 0.06f
        val hurt = s.hull < 0.995f
        if (!planet && !hot && !hurt) return false
        if (planet) {
            // ALT 12.4 km   VS -120 m/s   1.00 g
            val alt = if (s.mode.landed) 0.0 else maxOf(0.0, here.altitude - s.spec.groundClearance)
            buf.clear().append("ALT ")
            Units.distance(buf, alt, upper = true)
            buf.append("    VS ").append(if (s.mode.landed) 0f else here.radialSpeed, if (abs(here.radialSpeed) < 100f) 1 else 0).append(" M/S    ")
            buf.append(s.gravity / 9.81f, 2).append(" G")
            ui.text(buf, cx, y, 9.5f * u, withAlpha(Theme.TEXT, a * 0.9f), UiRenderer.CENTER, tracking = 0.08f, shadow = 0.7f, face = FontData.MONO)
        }
        var x = cx
        val by = y + 13f * u
        val bw = 96f * u
        if (hot && hurt) x -= bw * 0.5f + 34f * u
        if (hot) {
            val h = clamp(s.heat, 0f, 1.25f) / 1.25f
            val col = if (s.heat > 1f) Theme.DANGER else if (s.heat > Session.HEAT_WARNING) Theme.WARN else Theme.ACCENT
            val blink = if (s.heat > 1f && (time * 5f).toInt() % 2 == 0) 0.5f else 1f
            ui.text("HEAT", x - bw * 0.5f - 6f * u, by + 4f * u, 8.5f * u, withAlpha(col, a * blink), UiRenderer.RIGHT, tracking = 0.2f, shadow = 0.7f)
            ui.rect(x - bw * 0.5f, by, bw, 4f * u, withAlpha(Theme.PANEL, a * 0.9f), 2f * u)
            ui.rect(x - bw * 0.5f, by, bw * h, 4f * u, withAlpha(col, a * blink), 2f * u)
            // the skin's limit
            ui.rect(x - bw * 0.5f + bw * (1f / 1.25f), by - 3f * u, 1.2f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f))
            x += bw + 68f * u
        }
        if (hurt) {
            val col = if (s.hull < 0.35f) Theme.DANGER else Theme.WARN
            ui.text("HULL", x - bw * 0.5f - 6f * u, by + 4f * u, 8.5f * u, withAlpha(col, a), UiRenderer.RIGHT, tracking = 0.2f, shadow = 0.7f)
            ui.rect(x - bw * 0.5f, by, bw, 4f * u, withAlpha(Theme.PANEL, a * 0.9f), 2f * u)
            ui.rect(x - bw * 0.5f, by, bw * clamp(s.hull, 0f, 1f), 4f * u, withAlpha(col, a), 2f * u)
        }
        return true
    }

    /**
     * The message line at 30 % of the height (the pre-port notice line; `06` Notices replace it),
     * on a soft dark plate so it reads over a bright station interior as well as over space. Part
     * of the flight HUD, and drawn by the game over the station services, which leave that part
     * of the screen free (voice lines are text, D31).
     */
    fun drawMessage(ui: UiRenderer, s: Session, a: Float) {
        val msg = s.message ?: return
        val u = frame.u
        val cx = frame.w * 0.5f
        val cy = frame.h * 0.30f
        val size = 13f * u
        val tw = ui.textWidth(msg, size, 0.25f)
        ui.rect(cx - tw * 0.5f - 24f * u, cy - 16f * u, tw + 48f * u, 32f * u, withAlpha(Theme.BLACK, a * 0.5f), 8f * u, 0f, 12f * u)
        ui.textCentered(msg, cx, cy, size, withAlpha(Theme.LOCK, a), tracking = 0.25f, shadow = 0.8f)
    }

    /** Converts a ship-relative offset into camera-relative coordinates (in place). */
    private fun relToCam(s: Session, cam: Camera, v: Vec3) {
        val c = cam.pos
        v.x += (s.ship.pos.x - c.x).toFloat()
        v.y += (s.ship.pos.y - c.y).toFloat()
        v.z += (s.ship.pos.z - c.z).toFloat()
    }

    /** Arrow from the screen centre showing which way the stick is turning the ship. */
    private fun drawSteeringArrow(ui: UiRenderer, touch: TouchFlightControls, a: Float) {
        val vx = touch.stickVecX
        val vy = touch.stickVecY
        val mag = sqrt(vx * vx + vy * vy)
        if (mag < 0.05f) return
        val u = frame.u
        val cx = frame.w * 0.5f
        val cy = frame.h * 0.5f
        val ux = vx / mag
        val uy = vy / mag
        val len = 30f * u + 58f * u * min(mag, 1f)
        val ex = cx + ux * len
        val ey = cy + uy * len
        val col = withAlpha(Theme.TEXT, a * (0.35f + 0.5f * min(mag, 1f)))
        ui.line(cx + ux * 20f * u, cy + uy * 20f * u, ex, ey, 2f * u, col)
        // arrow head: back vector rotated ±30°
        val bx = -ux
        val by = -uy
        val hl = 11f * u
        ui.line(ex, ey, ex + (bx * 0.866f - by * 0.5f) * hl, ey + (bx * 0.5f + by * 0.866f) * hl, 2f * u, col)
        ui.line(ex, ey, ex + (bx * 0.866f + by * 0.5f) * hl, ey + (-bx * 0.5f + by * 0.866f) * hl, 2f * u, col)
    }

    // ------------------------------------------------------------------ targets, lock and markers

    /**
     * Uppercase markers on stations and bodies; amber brackets on the locked target. Distance is
     * shown only for the locked target, or the one the nose is aimed at when nothing is locked.
     */
    private fun drawTargets(ui: UiRenderer, s: Session, cam: Camera, a: Float) {
        // cycleTarget runs its own wider search, so nothing needs the aim while a target is locked
        val locked = s.lockKind != Session.LOCK_NONE
        if (!locked) s.findAimed(Session.AIM_CONE_COS, Session.AIM_CONE_SIN)
        val showKind = if (locked) s.lockKind else s.aimKind
        val showIndex = if (locked) s.lockIndex else s.aimIndex
        // labels claim screen space in priority order (locked/aimed, stations, bodies); a label
        // that would overlap one already placed is left out (its marker ring still shows)
        labelCount = 0
        if (showKind != Session.LOCK_NONE) drawMarker(ui, s, cam, a, showKind, showIndex, showKind, showIndex)
        val system = s.system
        for (i in system.stations.indices) {
            if (showKind != Session.LOCK_STATION || i != showIndex) drawMarker(ui, s, cam, a, Session.LOCK_STATION, i, showKind, showIndex)
        }
        for (i in system.bodies.indices) {
            if (showKind != Session.LOCK_BODY || i != showIndex) drawMarker(ui, s, cam, a, Session.LOCK_BODY, i, showKind, showIndex)
        }
    }

    /**
     * Claims the screen box of a label at ([x], [y]) reaching [left]/[right] from x, [lines] lines; false
     * (nothing claimed) if it would overlap a label already placed this frame.
     */
    private fun claimLabel(x: Float, y: Float, left: Float, right: Float, lines: Int, force: Boolean): Boolean {
        val u = frame.u
        val x0 = x - left - 3f * u
        val x1 = x + right + 3f * u
        val y0 = y - 9f * u
        val y1 = y + (if (lines > 1) 20f else 5f) * u
        if (!force) {
            for (k in 0 until labelCount) {
                val o = k * 4
                if (x0 < labelBoxes[o + 2] && x1 > labelBoxes[o] && y0 < labelBoxes[o + 3] && y1 > labelBoxes[o + 1]) return false
            }
        }
        if (labelCount < MAX_LABELS) {
            val o = labelCount * 4
            labelBoxes[o] = x0; labelBoxes[o + 1] = y0; labelBoxes[o + 2] = x1; labelBoxes[o + 3] = y1
            labelCount++
        }
        return true
    }

    private fun drawMarker(ui: UiRenderer, s: Session, cam: Camera, a: Float, kind: Int, i: Int, showKind: Int, showIndex: Int) {
        s.targetRel(kind, i, cam.pos, tmp)
        val locked = kind == s.lockKind && i == s.lockIndex
        val onScreen = cam.project(tmp.x, tmp.y, tmp.z, screenPt)
        val u = frame.u
        val x = screenPt.x
        val y = screenPt.y
        val inside = onScreen && x >= 0f && y >= 0f && x <= frame.w && y <= frame.h
        if (!inside) {
            // the locked target always shows where it is: an arrow on the screen edge
            if (locked) drawLockArrow(ui, s, cam, a, kind, i)
            return
        }
        val shown = kind == showKind && i == showIndex
        // hidden behind a planet: no marker (the lock brackets still show where it is)
        if (!locked && hiddenBehindBody(s, cam, kind, i)) return
        val dist = s.targetDistance(kind, i)
        val pxPerUnit = frame.h * 0.5f / cam.tanHalfY / screenPt.z
        val screenR = (s.targetRadius(kind, i) * pxPerUnit).toFloat()
        // a body filling the view needs no marker (the zone label names it)
        if (screenR > frame.h * 0.35f && !locked) return
        if (locked) {
            // big bodies: keep the brackets and the distance readable on screen
            val half = minOf(maxOf(screenR * 1.05f, 16f * u), 0.42f * min(frame.w, frame.h))
            val arm = maxOf(half * 0.3f, 8f * u)
            val col = withAlpha(Theme.LOCK, a)
            val w = 2f * u
            for (sxs in 0..1) for (sys in 0..1) {
                val sxv = if (sxs == 0) -1f else 1f
                val syv = if (sys == 0) -1f else 1f
                val px = x + sxv * half
                val py = y + syv * half
                ui.line(px, py, px - sxv * arm, py, w, col)
                ui.line(px, py, px, py - syv * arm, w, col)
            }
            val ly = minOf(y + half + 16f * u, frame.h - frame.sb - 64f * u)
            labelAndDistance(ui, s, a, kind, i, x, ly, col, true, dist)
        } else {
            val col = withAlpha(if (kind == Session.LOCK_STATION) Theme.ACCENT else Theme.TEXT, a * 0.85f)
            val r = maxOf(screenR, 5f * u)
            ui.circle(x, y, r, col, 1.2f * u)
            labelAndDistance(ui, s, a, kind, i, x, y + r + 13f * u, col, shown, dist)
        }
    }

    /**
     * Amber chevron on an ellipse near the screen edge, pointing towards the locked target ([tmp]
     * = target relative to the camera), with its name and distance just inside it.
     */
    private fun drawLockArrow(ui: UiRenderer, s: Session, cam: Camera, a: Float, kind: Int, i: Int) {
        val u = frame.u
        lockArrowShown = true
        // view-space direction (camera looks down -Z, +Y up); screen y grows downwards
        cam.rot.rotateInverse(tmp, tmp2)
        var dx = tmp2.x
        var dy = -tmp2.y
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-6f * (abs(tmp2.z) + 1f)) {
            dx = 0f; dy = 1f // dead astern: point down
        } else {
            dx /= len; dy /= len
        }
        // a ring around the crosshair, clear of the control columns at the screen edges
        val cx = frame.w * 0.5f
        val cy = frame.h * 0.5f
        val ex = cx + dx * frame.w * 0.27f
        val ey = cy + dy * frame.h * 0.36f
        val col = withAlpha(Theme.LOCK, a)
        // chevron: tip at (ex, ey), arms swept back ±40°
        val hl = 16f * u
        val bx = -dx
        val by = -dy
        val c = 0.766f
        val sn = 0.643f
        ui.line(ex, ey, ex + (bx * c - by * sn) * hl, ey + (bx * sn + by * c) * hl, 3f * u, col)
        ui.line(ex, ey, ex + (bx * c + by * sn) * hl, ey + (-bx * sn + by * c) * hl, 3f * u, col)
        // name + distance: inward beside a side arrow, to the right of a top/bottom one (the
        // message line sits under the top of the ring)
        val lx: Float
        val ly: Float
        if (abs(dx) > 0.6f) {
            lx = ex - dx * 64f * u
            ly = ey - 4f * u
        } else {
            lx = ex + 58f * u
            ly = ey - 2f * u
        }
        labelAndDistance(ui, s, a, kind, i, lx, ly, col, true, s.targetDistance(kind, i))
    }

    private fun labelAndDistance(
        ui: UiRenderer, s: Session, a: Float, kind: Int, i: Int, x: Float, y: Float, col: Int, withDistance: Boolean, dist: Double,
    ) {
        val u = frame.u
        val name = s.targetName(kind, i)
        // measure the whole label (name, and the distance line with its unit to the right)
        val nameHalf = ui.textWidth(name, 9f * u, 0.18f) * 0.5f
        var left = nameHalf
        var right = nameHalf
        var unit = ""
        var numW = 0f
        if (withDistance) {
            unit = Units.distanceParts(buf.clear(), dist)
            numW = ui.textWidth(buf, 11f * u, 0f, FontData.MONO)
            left = maxOf(left, numW * 0.5f)
            right = maxOf(right, numW * 0.5f + 4f * u + ui.textWidth(unit, 8.5f * u))
        }
        // the locked/aimed label always shows (it is placed first, so nothing is under it yet)
        if (!claimLabel(x, y, left, right, if (withDistance) 2 else 1, withDistance)) return
        ui.text(name, x, y, 9f * u, col, UiRenderer.CENTER, tracking = 0.18f, shadow = 0.7f)
        if (withDistance) {
            ui.text(buf, x, y + 14f * u, 11f * u, col, UiRenderer.CENTER, shadow = 0.7f, face = FontData.MONO)
            ui.text(unit, x + numW * 0.5f + 4f * u, y + 14f * u, 8.5f * u, col, shadow = 0.7f)
        }
    }

    /** True if a body other than the target itself blocks the line of sight from the camera. */
    private fun hiddenBehindBody(s: Session, cam: Camera, kind: Int, i: Int): Boolean {
        val eye = cam.pos
        val system = s.system
        val p = when (kind) {
            Session.LOCK_STATION -> system.stations[i].pos
            Session.LOCK_BODY -> system.bodies[i].pos
            else -> return false
        }
        val dx = p.x - eye.x
        val dy = p.y - eye.y
        val dz = p.z - eye.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.0) return false
        for (j in system.bodies.indices) {
            if (kind == Session.LOCK_BODY && j == i) continue
            val b = system.bodies[j]
            val cx = b.pos.x - eye.x
            val cy = b.pos.y - eye.y
            val cz = b.pos.z - eye.z
            val t = (cx * dx + cy * dy + cz * dz) / len
            if (t <= 0.0 || t >= len) continue
            val c2 = cx * cx + cy * cy + cz * cz
            if (c2 - t * t < b.radius * b.radius) return true
        }
        return false
    }

    // ------------------------------------------------------------------ landing ladder and gear

    /**
     * Pitch ladder (±10° steps relative to the local horizon) with PITCH and ALT boxes. Shown only
     * in landing mode, like the Roblox version's L landing.
     */
    private fun drawLandingHud(ui: UiRenderer, s: Session, cam: Camera, a: Float) {
        val mode = s.mode
        val gear = s.gearRig.downFraction
        if (!(mode.landing || mode.landed)) {
            if (gear > 0f) drawGear(ui, s, a)
            return
        }
        val i = if (mode.landed) s.landedBody else s.here.nearestBody
        if (i < 0) return
        val ship = s.ship
        val b = s.system.bodies[i]
        val u = frame.u
        val dx = ship.pos.x - b.pos.x
        val dy = ship.pos.y - b.pos.y
        val dz = ship.pos.z - b.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
        ship.rot.rotate(0f, 0f, -1f, fwdV)
        val pitchDeg = kotlin.math.asin(clamp(fwdV.dot(upV), -1f, 1f)) * 57.29578f
        // horizon heading and right vectors
        fwdV.addScaled(upV, -fwdV.dot(upV))
        if (fwdV.lengthSq() < 1e-6f) ship.rot.rotate(0f, 1f, 0f, fwdV).addScaled(upV, -fwdV.dot(upV))
        fwdV.normalize()
        rightV.cross(fwdV, upV).normalize()
        val col = withAlpha(Theme.OK, a * 0.85f)
        val dim = withAlpha(Theme.OK, a * 0.5f)
        val half = 70f * u
        // rungs every 10°, only near the current pitch
        var deg = -90
        while (deg <= 90) {
            if (abs(deg - pitchDeg) <= 32f) {
                val rad = deg * 0.017453292f
                val c = kotlin.math.cos(rad)
                val sn = kotlin.math.sin(rad)
                // a far point in that direction, camera relative (ship is near the camera)
                val px = (fwdV.x * c + upV.x * sn) * 5000f + (ship.pos.x - cam.pos.x).toFloat()
                val py = (fwdV.y * c + upV.y * sn) * 5000f + (ship.pos.y - cam.pos.y).toFloat()
                val pz = (fwdV.z * c + upV.z * sn) * 5000f + (ship.pos.z - cam.pos.z).toFloat()
                if (cam.project(px, py, pz, screenPt)) {
                    val x0 = screenPt.x
                    val y0 = screenPt.y
                    if (cam.project(px + rightV.x * 500f, py + rightV.y * 500f, pz + rightV.z * 500f, tmp2)) {
                        var ex = tmp2.x - x0
                        var ey = tmp2.y - y0
                        val el = sqrt(ex * ex + ey * ey).coerceAtLeast(1e-3f)
                        ex /= el; ey /= el
                        val gap = 26f * u
                        val wdt = if (deg == 0) 1.8f * u else 1.2f * u
                        val cc = if (deg == 0) col else dim
                        ui.line(x0 - ex * half, y0 - ey * half, x0 - ex * gap, y0 - ey * gap, wdt, cc)
                        ui.line(x0 + ex * gap, y0 + ey * gap, x0 + ex * half, y0 + ey * half, wdt, cc)
                        if (deg != 0) {
                            buf.clear().append(deg)
                            ui.text(buf, x0 + ex * (half + 14f * u), y0 + ey * (half + 14f * u) + 4f * u, 9f * u, dim, UiRenderer.CENTER, face = FontData.MONO)
                        }
                    }
                }
            }
            deg += 10
        }
        // PITCH and ALT boxes either side of the centre
        val cx = frame.w * 0.5f
        val cy = frame.h * 0.5f
        val bw = 74f * u
        val bh = 36f * u
        val lx = cx - 150f * u - bw
        val rx = cx + 150f * u
        for (k in 0..1) {
            val x = if (k == 0) lx else rx
            ui.rect(x, cy - bh * 0.5f, bw, bh, withAlpha(Theme.PANEL, a * 0.8f), -5f * u)
            ui.rect(x, cy - bh * 0.5f, bw, bh, col, -5f * u, 1.2f * u)
            ui.text(if (k == 0) "PITCH" else "ALT", x + bw * 0.5f, cy - bh * 0.5f - 5f * u, 8.5f * u, col, UiRenderer.CENTER, tracking = 0.2f, shadow = 0.6f)
            buf.clear()
            if (k == 0) {
                buf.append(pitchDeg, 0).append('°')
                ui.textCentered(buf, x + bw * 0.5f, cy, 13f * u, col, face = FontData.MONO)
            } else {
                // above the real ground while flying (the session's altitude), 0 once landed
                val alt = if (mode.landed) 0.0 else maxOf(0.0, s.here.altitude - s.spec.groundClearance)
                val unit = Units.distanceParts(buf, alt)
                val tw = ui.textWidth(buf, 13f * u, face = FontData.MONO)
                ui.textCentered(buf, x + bw * 0.5f - 6f * u, cy, 13f * u, col, face = FontData.MONO)
                ui.text(unit, x + bw * 0.5f - 6f * u + tw * 0.5f + 3f * u, cy + 4f * u, 8f * u, col)
            }
        }
        // vertical speed under the ALT box, total speed under the PITCH box (touchdown limits)
        val vs = ship.vel.dot(upV)
        buf.clear().append(vs, 1).append(" M/S")
        ui.text(buf, rx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (vs <= -Session.TOUCHDOWN_SPEED) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
        buf.clear().append(ship.speed, 0).append(" M/S")
        ui.text(buf, lx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (ship.speed >= Session.TOUCHDOWN_MAX_SPEED) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
        drawGear(ui, s, a)
    }

    private fun drawGear(ui: UiRenderer, s: Session, a: Float) {
        val u = frame.u
        val rig = s.gearRig
        val gear = rig.downFraction
        val label = when {
            gear >= 1f -> if (s.mode.landed) "LANDED" else "GEAR DOWN"
            rig.doorsMoving -> "GEAR · DOORS"
            else -> "GEAR · LEGS"
        }
        val x = frame.w * 0.5f
        val y = frame.h - frame.sb - 30f * u
        val col = withAlpha(if (gear >= 1f) Theme.OK else Theme.WARN, a)
        ui.textCentered(label, x, y - 12f * u, 10f * u, col, tracking = 0.25f, shadow = 0.6f)
        ui.rect(x - 50f * u, y, 100f * u, 4f * u, withAlpha(Theme.PANEL, a), 2f * u)
        ui.rect(x - 50f * u, y, 100f * u * gear, 4f * u, col, 2f * u)
    }

    companion object {
        /** The altitude line shows below this height above a planet's ground, m. */
        const val ALT_READOUT = 300_000.0

        /** Most target labels the flight HUD places per frame. */
        private const val MAX_LABELS = 32

        /** The first-flight hints start to fade after this long on screen, s. */
        const val HINT_TIME = 25f
    }
}
