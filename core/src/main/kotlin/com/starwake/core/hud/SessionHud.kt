package com.starwake.core.hud

import com.starwake.core.Settings
import com.starwake.core.flight.FlightConfig
import com.starwake.core.game.Session
import com.starwake.core.game.ShipView
import com.starwake.core.gfx.Camera
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.lerpColor
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Frame
import com.starwake.core.ui.TextBuf
import com.starwake.core.ui.Theme
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The flat flight HUD, drawn over the session's scene: the Roblox flight arrow (DECISIONS 20: no
 * nose reticle, no velocity marker, no GEAR bar), target markers and the lock (with the off-screen
 * arrow), the pre-port landing ladder, the speed and systems readouts beside the lever, zone,
 * autopilot and warning lines, the G blackout overlay, the message line, and the touch controls.
 * `06` (FlightHud, TargetMarkers, Notices) and `02` (AttitudeHud) replace the rest piece by piece.
 * Numbers go through [Units]. No allocation.
 */
class SessionHud(private val frame: Frame, private val settings: Settings) {
    private val buf = TextBuf(64)
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val screenPt = Vec3()
    private val upV = Vec3()
    private val fwdV = Vec3()
    private val rightV = Vec3()

    /** The Roblox flight arrow (02 3.9). */
    val arrow = FlightArrow()

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

        // the arrow on the cockpit's projection glass is part of the cockpit: under the G overlay
        val glass = s.mode.view == ShipView.COCKPIT
        if (glass && s.glassCursorShown) drawFlightArrow(ui, s, cam, a)
        drawTargets(ui, s, cam, a)
        drawLandingHud(ui, s, cam, a)

        // speed readout beside the throttle lever (keeps the centre clear for the ship)
        val g = touch.gauge
        val mirrored = settings.leftHanded
        val sx = if (mirrored) g.x - 26f * u else g.x + g.w + 26f * u
        val align = if (mirrored) UiRenderer.RIGHT else UiRenderer.LEFT
        val sy = g.y + g.h
        val reverse = ship.forwardSpeed < -1.0
        val unit = Units.speedParts(buf.clear(), ship.speed)
        val numW = ui.text(buf, sx, sy - 12f * u, 28f * u, withAlpha(if (reverse) Theme.WARN else Theme.TEXT, a), align, 0f, 0.02f, shadow = 0.7f, face = FontData.MONO)
        val unitX = if (mirrored) sx - numW - 6f * u else sx + numW + 6f * u
        ui.text(unit, unitX, sy - 12f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), align, tracking = 0.15f, shadow = 0.7f)
        drawSystems(ui, s, sx, sy + 6f * u, align, a)

        val cx = frame.w * 0.5f
        val system = s.system
        val gs = s.gs
        drawBlackout(ui, s, a)
        // the flat flight arrow sits above the G overlay (Roblox: ShipCursorGui, DisplayOrder 50, over ShipHud's 46)
        if (!glass && s.cursorShown) drawFlightArrow(ui, s, cam, a)

        // zone, autopilot, heat, flight-assist and proximity lines (top centre)
        ui.textCentered(s.zoneLabel, cx, frame.st + 30f * u, 10.5f * u, withAlpha(Theme.TEXT, a * 0.85f), tracking = 0.3f, shadow = 0.6f)
        // below the first flight's "DRAG HERE TO LOOK AROUND" while it shows (it sits at the look strip's foot)
        var warnY = frame.st + 52f * u + if (hints > 0.01f && touch.lookEnabled) 16f * u else 0f
        warnY = drawFlightLines(ui, s, cx, warnY, a, time)
        // the approach prompt of a giant (Roblox ShipAtmosphere reads LandingBlocked, DECISIONS 11)
        if (gs.body >= 0 && gs.groundAltitude < Session.LAND_RANGE && system.bodies[gs.body].landingBlocked != null) {
            ui.textCentered(Session.LANDING_BLOCKED_GAS, cx, warnY, 11f * u, withAlpha(Theme.WARN, a), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (!s.thrust.flightAssist) {
            val blink = if ((time * 2f).toInt() % 2 == 0) 1f else 0.55f
            ui.textCentered("FLIGHT ASSIST OFF", cx, warnY, 11f * u, withAlpha(Theme.WARN, a * blink), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        drawMessage(ui, s, a)
        if (s.proximity < 60f) {
            val blink = if ((time * 4f).toInt() % 2 == 0) 1f else 0.4f
            ui.textCentered("PROXIMITY", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
        }

        val t = s.thrust
        val cooldown = if (t.boostCooldown > 0.0) (t.boostCooldown / s.profile.boostCooldown).toFloat() else 0f
        touch.draw(
            ui, a, (ship.speed / FlightConfig.Thrust.MAX_SPEED).toFloat(),
            if (gs.speedLimit < FlightConfig.Thrust.MAX_SPEED) (gs.speedLimit / FlightConfig.Thrust.MAX_SPEED).toFloat() else -1f,
            t.flightAssist, t.boosting, cooldown, t.cruising || s.handling.overloaded, hints, s.lockKind != Session.LOCK_NONE,
            s.autopilot.engaged, s.autopilot.status,
        )
    }

    /**
     * The Roblox flight arrow at the screen centre, or in the cockpit on its projection glass 3.2 m
     * in front of the eye (it moves with the head when the cockpit sways; 02 6.2), sized by
     * `shortSide / 1080` (× focal / focal at 80° on the glass) with the phone clamps of 9.1.
     */
    private fun drawFlightArrow(ui: UiRenderer, s: Session, cam: Camera, a: Float) {
        val u = frame.u
        var unit = minOf(frame.w, frame.h) / FlightConfig.Cursor.DESIGN_HEIGHT
        var cx = frame.w * 0.5f
        var cy = frame.h * 0.5f
        if (s.mode.view == ShipView.COCKPIT) {
            // the glass point: eye + nose · GlassDistance, camera-relative
            val eye = s.camera
            s.ship.rot.rotate(0f, 0f, -GLASS_DISTANCE, tmp)
            s.ship.rot.rotate(eye.seat, tmp2)
            tmp.add(tmp2)
            relToCam(s, cam, tmp)
            if (!cam.project(tmp.x, tmp.y, tmp.z, screenPt)) return
            cx = screenPt.x
            cy = screenPt.y
            // keep the glass's angular size when the field of view changes
            unit *= TAN_40 / cam.tanHalfY
        }
        arrow.setUnit(unit, MIN_DOT_DP * u, MIN_BASE_DP * u)
        arrow.update(s.pilotAimX, s.pilotAimY)
        arrow.draw(ui, cx, cy, a)
    }

    /**
     * The G overlay (`ShipHud.lua:355-370`): over the HUD, under the warning lines; black blended to
     * the redout colour by the share of the strain that comes from pushing, at the smoothed darkness.
     */
    private fun drawBlackout(ui: UiRenderer, s: Session, a: Float) {
        val dark = s.systems.darkness.toFloat()
        if (dark <= 0.003f) return
        val tint = lerpColor(Theme.BLACK, OPAQUE or FlightConfig.Physics.REDOUT_COLOR, clamp(s.systems.redout.toFloat(), 0f, 1f))
        ui.rect(-2f, -2f, frame.w + 4f, frame.h + 4f, withAlpha(tint, dark * a))
    }

    /**
     * The flight's status lines from [y] down (returns the next free line): the autopilot's status,
     * the overheat stand-in (`Warnings.Overheat`: 80 % OVERHEATING, from 100 % HEAT CRITICAL, the
     * ship burns up at 200 %; the crash-flow port's ShipWarnings replace it) and the fuel scoop.
     */
    private fun drawFlightLines(ui: UiRenderer, s: Session, cx: Float, y: Float, a: Float, time: Float): Float {
        val u = frame.u
        var warnY = y
        if (s.autopilot.engaged) {
            buf.clear().append("AUTOPILOT  ·  ").append(s.autopilot.status)
            ui.textCentered(buf, cx, warnY, 11f * u, withAlpha(Theme.LOCK, a), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        val heat = s.shipHeat
        if (heat >= FlightConfig.Systems.OVERHEAT_PHASE1) {
            val critical = heat >= FlightConfig.Systems.OVERHEAT_PHASE2
            val blink = if (!critical || (time * 3f).toInt() % 2 == 0) 1f else 0.45f
            buf.clear().append(if (critical) "HEAT CRITICAL  " else "OVERHEATING  ").append((heat * 100.0).toInt()).append('%')
            ui.textCentered(buf, cx, warnY, 13f * u, withAlpha(if (critical) Theme.DANGER else Theme.WARN, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (s.systems.scooping) {
            ui.textCentered("FUEL SCOOPING", cx, warnY, 10f * u, withAlpha(Theme.OK, a * 0.9f), tracking = 0.25f, shadow = 0.7f)
            warnY += 18f * u
        }
        return warnY
    }

    /** Heat, fuel and G under the speed (the systems' numbers; `06` lays the real readouts out). */
    private fun drawSystems(ui: UiRenderer, s: Session, x: Float, y: Float, align: Int, a: Float) {
        val u = frame.u
        val sys = s.systems
        buf.clear().append("HEAT ").append((sys.heat * 100.0).toInt()).append("%   FUEL ").append((sys.fuelFraction * 100.0).toInt()).append('%')
        val heatCol = if (sys.heat >= FlightConfig.Systems.OVERHEAT_PHASE1) Theme.WARN else Theme.TEXT_DIM
        ui.text(buf, x, y, 9f * u, withAlpha(heatCol, a * 0.9f), align, tracking = 0.12f, shadow = 0.7f, face = FontData.MONO)
        val strain = sys.strainG
        if (sys.gForce >= 0.5) {
            val col = when {
                strain >= FlightConfig.Physics.BLACKOUT_START_G -> OPAQUE or FlightConfig.Physics.G_DANGER_COLOR
                strain >= FlightConfig.Physics.BLACKOUT_START_G * FlightConfig.Physics.G_WARN_SHARE -> OPAQUE or FlightConfig.Physics.G_WARN_COLOR
                else -> Theme.TEXT_DIM
            }
            buf.clear().append(sys.gForce, 1).append(" G")
            ui.text(buf, x, y + 13f * u, 9f * u, withAlpha(col, a * 0.9f), align, tracking = 0.12f, shadow = 0.7f, face = FontData.MONO)
        }
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
     * Pitch ladder (±10° steps relative to the local horizon) with PITCH and ALT boxes: the
     * attitude indicator, shown by DECISIONS 15's rule ([Session.attitudeShown]).
     */
    private fun drawLandingHud(ui: UiRenderer, s: Session, cam: Camera, a: Float) {
        val mode = s.mode
        if (!s.attitudeShown) return
        val i = if (mode.landed) s.landedBody else s.gs.body
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
                val alt = maxOf(0.0, d - b.radius - s.spec.groundClearance)
                val unit = Units.distanceParts(buf, alt)
                val tw = ui.textWidth(buf, 13f * u, face = FontData.MONO)
                ui.textCentered(buf, x + bw * 0.5f - 6f * u, cy, 13f * u, col, face = FontData.MONO)
                ui.text(unit, x + bw * 0.5f - 6f * u + tw * 0.5f + 3f * u, cy + 4f * u, 8f * u, col)
            }
        }
        // vertical speed under the ALT box, total speed under the PITCH box (touchdown limits)
        val vs = (ship.vel.x * upV.x + ship.vel.y * upV.y + ship.vel.z * upV.z).toFloat()
        buf.clear().append(vs, 1).append(" M/S")
        ui.text(buf, rx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (vs <= -Session.HARD_TOUCHDOWN) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
        buf.clear().append(ship.speed.toFloat(), 0).append(" M/S")
        ui.text(buf, lx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (ship.speed >= Session.TOUCHDOWN_MAX_SPEED) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
    }

    companion object {
        /** Most target labels the flight HUD places per frame. */
        private const val MAX_LABELS = 32
        private const val OPAQUE = -0x1000000

        /** The cockpit's projection glass in front of the eye (`Attitude.GlassDistance`), m. */
        const val GLASS_DISTANCE = 3.2f

        /** tan(40°): the glass arrow is sized for a vertical FOV of 80°. */
        private const val TAN_40 = 0.8390996f

        /** Phone clamps of 9.1: the dot at least 2.5 dp, the triangle's base at least 9 dp. */
        const val MIN_DOT_DP = 2.5f
        const val MIN_BASE_DP = 9f

        /** The first-flight hints start to fade after this long on screen, s. */
        const val HINT_TIME = 25f
    }
}
