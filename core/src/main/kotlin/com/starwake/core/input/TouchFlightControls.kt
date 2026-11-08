package com.starwake.core.input

import com.starwake.core.Haptics
import com.starwake.core.Settings
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.clamp
import com.starwake.core.math.deadZone
import com.starwake.core.math.expoCurve
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Rect
import com.starwake.core.ui.TextBuf
import com.starwake.core.ui.Theme
import com.starwake.core.ui.UiHost
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-thumb touch flight controls.
 *
 * Left thumb (anywhere in the left zone): a throttle *lever* – slide up/down to set the throttle,
 * it stays where you leave it; slide sideways for rudder (spring-centred). Double-tap = all stop.
 * Right thumb (anywhere in the right zone): a floating flight stick – up/down pitch, left/right roll
 * (or yaw, configurable). Buttons: BOOST, FA (flight assist), CAM, pause.
 * "Left-handed" mirrors the whole layout.
 */
class TouchFlightControls(private val settings: Settings, private val host: UiHost) {
    // ---- outputs
    var pitch = 0f
        private set
    var yaw = 0f
        private set
    var roll = 0f
        private set

    /** Latched lever position, -1..1. */
    var throttle = 0f

    /** Vertical thrusters: +1 up, -1 down (held buttons). */
    var vertical = 0f
        private set

    /** Raw stick deflection (screen space, -1..1, +y = down) for the HUD steering arrow. */
    val stickVecX get() = stickOutX
    val stickVecY get() = stickOutY
    private var lockEdge = false
    private var boostEdge = false
    private var faEdge = false
    private var camEdge = false
    private var pauseEdge = false
    var boostHeld = false
        private set
    var stickUsed = false
        private set
    var throttleUsed = false
        private set

    // ---- layout
    private var w = 1f
    private var h = 1f
    private var u = 1f
    private var mirror = false
    private var zoneSplit = 0f
    val gauge = Rect()
    val boostBtn = Rect()
    val faBtn = Rect()
    val camBtn = Rect()
    val pauseBtn = Rect()
    val lockBtn = Rect()
    val mapBtn = Rect()

    /** Contextual action (LAND / DOCK / TAKE OFF); hidden when [actionLabel] is empty. */
    val actionBtn = Rect()
    var actionLabel = ""
    val upBtn = Rect()
    val downBtn = Rect()
    private var stickRadius = 1f
    private var stickRestX = 0f
    private var stickRestY = 0f

    // ---- stick state
    private var stickId = -1
    private var stickCx = 0f
    private var stickCy = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickOutX = 0f
    private var stickOutY = 0f
    private var stickDrawDx = 0f
    private var stickDrawDy = 0f

    // ---- throttle state
    private var thrId = -1
    private var thrGrabY = 0f
    private var thrGrabX = 0f
    private var thrGrabGauge = 0f
    private var thrX = 0f
    private var thrDownTime = 0f
    private var thrMoved = false
    private var lastTapTime = -10f
    private var rudder = 0f

    // ---- buttons
    private var boostId = -1
    private var faId = -1
    private var camId = -1
    private var pauseId = -1
    private var lockId = -1
    private var mapId = -1
    private var actionId = -1
    private var actionEdge = false
    private var mapEdge = false
    private var upId = -1
    private var downId = -1
    private var time = 0f
    private val buf = TextBuf(32)

    fun layout(width: Float, height: Float, unit: Float, sl: Float, st: Float, sr: Float, sb: Float) {
        releaseAll()
        w = width; h = height; u = unit
        mirror = settings.leftHanded
        zoneSplit = width * 0.42f
        stickRadius = 52f * u
        // positions for the right-handed layout; mirrored afterwards
        // build the right-handed layout; when mirrored, each side must keep its own inset
        val left = if (mirror) sr else sl
        val right = if (mirror) sl else sr
        gauge.set(left + 30f * u, height * 0.30f, 14f * u, height - sb - 52f * u - height * 0.30f)
        pauseBtn.set(left + 14f * u, st + 12f * u, 46f * u, 38f * u)
        camBtn.set(width - right - 14f * u - 56f * u, st + 12f * u, 56f * u, 38f * u)
        val br = 31f * u
        boostBtn.set(width - right - 30f * u - 2 * br, height * 0.40f - br, 2 * br, 2 * br)
        val fr = 23f * u
        faBtn.set(boostBtn.cx - 92f * u - fr, height * 0.26f - fr, 2 * fr, 2 * fr)
        lockBtn.set(camBtn.x - 10f * u - 62f * u, st + 12f * u, 62f * u, 38f * u)
        mapBtn.set(lockBtn.x - 10f * u - 56f * u, st + 12f * u, 56f * u, 38f * u)
        actionBtn.set(boostBtn.cx - 46f * u, height * 0.22f - 17f * u, 92f * u, 34f * u)
        val vr = 21f * u
        val vx = gauge.x + gauge.w + 60f * u
        upBtn.set(vx - vr, gauge.y + 4f * u, 2 * vr, 2 * vr)
        downBtn.set(vx - vr, gauge.y + 4f * u + 2 * vr + 12f * u, 2 * vr, 2 * vr)
        stickRestX = width - right - 150f * u
        stickRestY = height - sb - 110f * u
        if (mirror) {
            for (r in arrayOf(gauge, pauseBtn, camBtn, boostBtn, faBtn, lockBtn, mapBtn, actionBtn, upBtn, downBtn)) r.x = width - r.x - r.w
            stickRestX = width - stickRestX
        }
    }

    fun consumeBoost() = boostEdge.also { boostEdge = false }
    fun consumeFa() = faEdge.also { faEdge = false }
    fun consumeCam() = camEdge.also { camEdge = false }
    fun consumePause() = pauseEdge.also { pauseEdge = false }
    fun consumeLock() = lockEdge.also { lockEdge = false }
    fun consumeMap() = mapEdge.also { mapEdge = false }
    fun consumeAction() = actionEdge.also { actionEdge = false }

    private fun inThrottleZone(x: Float) = if (mirror) x > w - zoneSplit else x < zoneSplit

    private fun hit(r: Rect, x: Float, y: Float, slop: Float) = r.contains(x, y, slop)

    private fun hitCircle(r: Rect, x: Float, y: Float, slop: Float): Boolean {
        val dx = x - r.cx
        val dy = y - r.cy
        val rr = r.w * 0.5f + slop
        return dx * dx + dy * dy <= rr * rr
    }

    fun onDown(id: Int, x: Float, y: Float): Boolean {
        val slop = 8f * u
        when {
            pauseId < 0 && hit(pauseBtn, x, y, slop) -> {
                pauseId = id; host.haptic(Haptics.TAP)
            }
            camId < 0 && hit(camBtn, x, y, slop) -> {
                camId = id; camEdge = true; host.haptic(Haptics.TAP)
            }
            lockId < 0 && hit(lockBtn, x, y, slop) -> {
                lockId = id; lockEdge = true; host.haptic(Haptics.TAP)
            }
            mapId < 0 && hit(mapBtn, x, y, slop) -> {
                mapId = id; mapEdge = true; host.haptic(Haptics.TAP)
            }
            actionId < 0 && actionLabel.isNotEmpty() && hit(actionBtn, x, y, slop) -> {
                actionId = id; actionEdge = true; host.haptic(Haptics.TAP)
            }
            upId < 0 && hitCircle(upBtn, x, y, slop * 0.5f) -> {
                upId = id; host.haptic(Haptics.TICK)
            }
            downId < 0 && hitCircle(downBtn, x, y, slop * 0.5f) -> {
                downId = id; host.haptic(Haptics.TICK)
            }
            boostId < 0 && hitCircle(boostBtn, x, y, slop) -> {
                boostId = id; boostEdge = true; boostHeld = true
            }
            faId < 0 && hitCircle(faBtn, x, y, slop) -> {
                faId = id; faEdge = true; host.haptic(Haptics.TICK)
            }
            thrId < 0 && inThrottleZone(x) -> {
                thrId = id
                thrGrabX = x; thrGrabY = y; thrX = x
                thrGrabGauge = gaugeOf(throttle)
                thrDownTime = time
                thrMoved = false
            }
            stickId < 0 && !inThrottleZone(x) -> {
                stickId = id
                val minX = if (mirror) stickRadius + 10f * u else zoneSplit + stickRadius
                val maxX = if (mirror) w - zoneSplit - stickRadius else w - stickRadius - 10f * u
                // the touch-down point is the stick centre (zero input); the ring is only *drawn*
                // pulled away from the screen edges
                stickCx = x
                stickCy = y
                stickDrawDx = clamp(x, minX, maxX) - x
                stickDrawDy = clamp(y, stickRadius + 10f * u, h - stickRadius - 10f * u) - y
                stickX = x; stickY = y
                updateStick()
            }
            else -> return false
        }
        return true
    }

    fun onMove(id: Int, x: Float, y: Float): Boolean {
        when (id) {
            stickId -> {
                stickX = x; stickY = y
                updateStick()
            }
            thrId -> {
                thrX = x
                val travel = gauge.h
                val g = clamp(thrGrabGauge + (thrGrabY - y) / travel, 0f, 1f)
                // symmetric zero detent: the same screen distance either side of the zero line
                val t = if (abs((g - REV) * travel) < 7f * u) 0f else throttleOf(g)
                if (abs(y - thrGrabY) > 6f * u || abs(x - thrGrabX) > 6f * u) thrMoved = true
                if ((t == 0f) != (throttle == 0f) || (t >= 1f && throttle < 1f) || (t <= -1f && throttle > -1f)) {
                    host.haptic(Haptics.TICK)
                }
                if (t != throttle) throttleUsed = true
                throttle = t
                val dx = (x - thrGrabX) / (48f * u)
                rudder = deadZone(clamp(dx, -1f, 1f), 0.18f)
            }
            else -> return id == boostId || id == faId || id == camId || id == pauseId || id == lockId || id == mapId || id == actionId || id == upId || id == downId
        }
        return true
    }

    fun onUp(id: Int, x: Float, y: Float, cancelled: Boolean): Boolean {
        when (id) {
            stickId -> {
                stickId = -1; stickOutX = 0f; stickOutY = 0f
            }
            thrId -> {
                thrId = -1
                rudder = 0f
                if (!thrMoved && time - thrDownTime < 0.25f) {
                    if (time - lastTapTime < 0.35f) {
                        throttle = 0f
                        host.haptic(Haptics.BUMP)
                        lastTapTime = -10f
                    } else {
                        lastTapTime = time
                    }
                }
            }
            boostId -> {
                boostId = -1; boostHeld = false
            }
            faId -> faId = -1
            camId -> camId = -1
            lockId -> lockId = -1
            mapId -> mapId = -1
            actionId -> actionId = -1
            upId -> upId = -1
            downId -> downId = -1
            pauseId -> {
                pauseId = -1
                if (!cancelled && hit(pauseBtn, x, y, 16f * u)) pauseEdge = true
            }
            else -> return false
        }
        return true
    }

    fun releaseAll() {
        stickId = -1; thrId = -1; boostId = -1; faId = -1; camId = -1; pauseId = -1
        lockId = -1; upId = -1; downId = -1; vertical = 0f; lockEdge = false
        mapId = -1; mapEdge = false; actionId = -1; actionEdge = false
        stickOutX = 0f; stickOutY = 0f; rudder = 0f; boostHeld = false
        boostEdge = false; faEdge = false; camEdge = false; pauseEdge = false
    }

    private fun updateStick() {
        var dx = stickX - stickCx
        var dy = stickY - stickCy
        val len = sqrt(dx * dx + dy * dy)
        val r = stickRadius
        if (len > r) {
            // floating stick: drag the base along so the thumb never "falls off"
            val k = (len - r) / len
            stickCx += dx * k
            stickCy += dy * k
            dx = stickX - stickCx
            dy = stickY - stickCy
        }
        val nx = dx / r
        val ny = dy / r
        val mag = sqrt(nx * nx + ny * ny)
        if (mag < 0.06f) {
            stickOutX = 0f; stickOutY = 0f
        } else {
            // radial dead zone, then per-axis expo for fine aiming near centre
            val scaled = ((mag - 0.06f) / 0.94f) / mag
            stickOutX = expoCurve(nx * scaled, 0.25f)
            stickOutY = expoCurve(ny * scaled, 0.25f)
            stickUsed = true
        }
    }

    /** Updates the axis outputs. Call once per frame. */
    fun update(dt: Float) {
        time += dt
        vertical = (if (upId >= 0) 1f else 0f) - (if (downId >= 0) 1f else 0f)
        val sens = settings.sensitivity
        val sx = clamp(stickOutX * sens, -1f, 1f)
        val sy = clamp(stickOutY * sens, -1f, 1f)
        pitch = if (settings.pitchUpClimb) -sy else sy
        if (settings.stickXRoll) {
            roll = sx; yaw = rudder
        } else {
            yaw = sx; roll = rudder
        }
    }

    // ---- throttle gauge mapping: bottom 22% of the gauge is reverse
    private fun gaugeOf(t: Float): Float = if (t >= 0f) REV + (1f - REV) * t else REV * (1f + t)
    private fun throttleOf(g: Float): Float = if (g >= REV) (g - REV) / (1f - REV) else g / REV - 1f

    /** Draws the controls. [speedFrac] is actual forward speed / max (negative in reverse). */
    fun draw(
        ui: UiRenderer, alpha: Float, speedFrac: Float, flightAssist: Boolean,
        boostReady: Boolean, boosting: Boolean, capacitor: Float, hints: Float, locked: Boolean,
    ) {
        val a = alpha
        // -------- throttle gauge
        val g = gauge
        ui.rect(g.x - 4f * u, g.y - 4f * u, g.w + 8f * u, g.h + 8f * u, withAlpha(Theme.PANEL, a * 0.75f), 4f * u)
        val zeroY = g.y + g.h * (1f - REV)
        ui.rect(g.x, zeroY, g.w, g.h * REV, withAlpha(Theme.WARN, a * 0.12f), 2f * u)
        // fill from zero to the lever
        val leverY = g.y + g.h * (1f - gaugeOf(throttle))
        if (throttle > 0f) {
            ui.rect(g.x, leverY, g.w, zeroY - leverY, withAlpha(Theme.ACCENT, a * 0.75f), 2f * u)
        } else if (throttle < 0f) {
            ui.rect(g.x, zeroY, g.w, leverY - zeroY, withAlpha(Theme.WARN, a * 0.8f), 2f * u)
        }
        // tick marks every 25%
        for (i in 1..3) {
            val ty = g.y + g.h * (1f - gaugeOf(i * 0.25f))
            ui.rect(if (mirror) g.x + g.w - 1f * u else g.x - 3f * u, ty, 4f * u, 1.2f * u, withAlpha(Theme.TEXT_DIM, a * 0.6f))
        }
        ui.rect(g.x - 6f * u, zeroY - 1f * u, g.w + 12f * u, 2f * u, withAlpha(Theme.TEXT, a * 0.8f))
        // actual speed marker
        val sf = clamp(speedFrac, -1f, 1.6f)
        val speedG = if (sf >= 0f) REV + (1f - REV) * minOf(sf, 1f) else REV * (1f + sf)
        val sy = g.y + g.h * (1f - speedG)
        ui.textCentered(if (mirror) "▶" else "◀", if (mirror) g.x - 9f * u else g.x + g.w + 9f * u, sy, 9f * u, withAlpha(Theme.TEXT, a))
        // lever handle
        val active = thrId >= 0
        val hw = g.w + (if (active) 22f else 14f) * u
        ui.rect(g.cx - hw * 0.5f, leverY - 5f * u, hw, 10f * u, withAlpha(Theme.TEXT, a * if (active) 1f else 0.85f), 3f * u)
        buf.clear().append((throttle * 100f).toInt()).append('%')
        ui.textCentered("THR", g.cx, g.y - 30f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        ui.textCentered(buf, g.cx, g.y - 15f * u, 12f * u, withAlpha(if (throttle < 0f) Theme.WARN else Theme.TEXT, a), face = FontData.MONO)
        if (active) {
            // rudder indicator under the thumb
            val rx = thrGrabX
            val ry = thrGrabY + 46f * u
            ui.rect(rx - 48f * u, ry - 2f * u, 96f * u, 4f * u, withAlpha(Theme.PANEL_LIGHT, a * 0.9f), 2f * u)
            ui.circle(rx + rudder * 48f * u, ry, 7f * u, withAlpha(Theme.ACCENT, a))
            ui.textCentered(if (settings.stickXRoll) "YAW" else "ROLL", rx, ry + 16f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        }

        // -------- stick
        if (stickId >= 0) {
            val bx = stickCx + stickDrawDx
            val by = stickCy + stickDrawDy
            ui.circle(bx, by, stickRadius, withAlpha(Theme.PANEL, a * 0.35f))
            ui.circle(bx, by, stickRadius, withAlpha(Theme.ACCENT, a * 0.45f), 2f * u)
            ui.line(bx - stickRadius * 0.25f, by, bx + stickRadius * 0.25f, by, 1f * u, withAlpha(Theme.ACCENT, a * 0.3f))
            ui.line(bx, by - stickRadius * 0.25f, bx, by + stickRadius * 0.25f, 1f * u, withAlpha(Theme.ACCENT, a * 0.3f))
            val kx = bx + clamp(stickX - stickCx, -stickRadius, stickRadius)
            val ky = by + clamp(stickY - stickCy, -stickRadius, stickRadius)
            ui.circle(kx, ky, stickRadius * 0.4f, withAlpha(Theme.ACCENT, a * 0.55f))
            ui.circle(kx, ky, stickRadius * 0.4f, withAlpha(Theme.TEXT, a * 0.8f), 1.5f * u)
        } else {
            ui.circle(stickRestX, stickRestY, stickRadius, withAlpha(Theme.ACCENT, a * 0.18f), 1.5f * u)
            ui.circle(stickRestX, stickRestY, stickRadius * 0.4f, withAlpha(Theme.ACCENT, a * 0.12f))
        }

        // -------- buttons
        val boostCol = if (boostReady || boosting) Theme.ACCENT else Theme.TEXT_DIM
        ui.circle(boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, withAlpha(if (boosting) Theme.ACCENT else Theme.PANEL, a * if (boosting) 0.85f else 0.8f))
        ui.circle(boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, withAlpha(boostCol, a * 0.85f), 2f * u)
        // capacitor pips
        for (i in 0 until 3) {
            val full = capacitor >= (i + 1) / 3f - 0.01f
            ui.rect(boostBtn.cx - 15f * u + i * 11f * u, boostBtn.cy + 9f * u, 8f * u, 3f * u, withAlpha(if (full) boostCol else Theme.PANEL_LIGHT, a))
        }
        ui.textCentered("BOOST", boostBtn.cx, boostBtn.cy - 2f * u, 10.5f * u, withAlpha(if (boosting) Theme.DARK else boostCol, a), tracking = 0.1f, weight = if (boosting) 0.05f else 0f)

        val faCol = if (flightAssist) Theme.ACCENT else Theme.WARN
        ui.circle(faBtn.cx, faBtn.cy, faBtn.w * 0.5f, withAlpha(Theme.PANEL, a * 0.8f))
        ui.circle(faBtn.cx, faBtn.cy, faBtn.w * 0.5f, withAlpha(faCol, a * 0.85f), 2f * u)
        ui.textCentered("FA", faBtn.cx, faBtn.cy - 5f * u, 11f * u, withAlpha(faCol, a), weight = 0.03f)
        ui.textCentered(if (flightAssist) "ON" else "OFF", faBtn.cx, faBtn.cy + 8f * u, 8f * u, withAlpha(faCol, a), tracking = 0.1f)

        ui.rect(camBtn.x, camBtn.y, camBtn.w, camBtn.h, withAlpha(Theme.PANEL, a * 0.8f), -6f * u)
        ui.rect(camBtn.x, camBtn.y, camBtn.w, camBtn.h, withAlpha(Theme.ACCENT, a * 0.5f), -6f * u, 1.2f * u)
        ui.textCentered("VIEW", camBtn.cx, camBtn.cy, 11f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f)

        val lockCol = if (locked) Theme.LOCK else Theme.ACCENT
        ui.rect(lockBtn.x, lockBtn.y, lockBtn.w, lockBtn.h, withAlpha(if (locked) Theme.LOCK else Theme.PANEL, a * if (locked) 0.25f else 0.8f), -6f * u)
        ui.rect(lockBtn.x, lockBtn.y, lockBtn.w, lockBtn.h, withAlpha(lockCol, a * 0.6f), -6f * u, 1.2f * u)
        ui.textCentered("LOCK", lockBtn.cx, lockBtn.cy, 11f * u, withAlpha(if (locked) Theme.LOCK else Theme.TEXT, a), tracking = 0.12f)

        ui.rect(mapBtn.x, mapBtn.y, mapBtn.w, mapBtn.h, withAlpha(Theme.PANEL, a * 0.8f), -6f * u)
        ui.rect(mapBtn.x, mapBtn.y, mapBtn.w, mapBtn.h, withAlpha(Theme.ACCENT, a * 0.5f), -6f * u, 1.2f * u)
        ui.textCentered("MAP", mapBtn.cx, mapBtn.cy, 11f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f)

        if (actionLabel.isNotEmpty()) {
            val pulse = 0.75f + 0.25f * kotlin.math.sin(time * 4f)
            ui.rect(actionBtn.x, actionBtn.y, actionBtn.w, actionBtn.h, withAlpha(Theme.LOCK, a * (if (actionId >= 0) 0.9f else 0.25f * pulse)), actionBtn.h * 0.5f)
            ui.rect(actionBtn.x, actionBtn.y, actionBtn.w, actionBtn.h, withAlpha(Theme.LOCK, a), actionBtn.h * 0.5f, 1.5f * u)
            ui.textCentered(actionLabel, actionBtn.cx, actionBtn.cy, 11.5f * u, withAlpha(if (actionId >= 0) Theme.DARK else Theme.LOCK, a), tracking = 0.15f, weight = 0.03f)
        }

        for (b in 0..1) {
            val r = if (b == 0) upBtn else downBtn
            val held = if (b == 0) upId >= 0 else downId >= 0
            ui.circle(r.cx, r.cy, r.w * 0.5f, withAlpha(if (held) Theme.ACCENT else Theme.PANEL, a * if (held) 0.5f else 0.75f))
            ui.circle(r.cx, r.cy, r.w * 0.5f, withAlpha(Theme.ACCENT, a * 0.55f), 1.5f * u)
            ui.textCentered(if (b == 0) "▲" else "▼", r.cx, r.cy, 11f * u, withAlpha(Theme.TEXT, a))
        }

        ui.rect(pauseBtn.x, pauseBtn.y, pauseBtn.w, pauseBtn.h, withAlpha(Theme.PANEL, a * 0.8f), -6f * u)
        ui.rect(pauseBtn.x, pauseBtn.y, pauseBtn.w, pauseBtn.h, withAlpha(Theme.ACCENT, a * 0.5f), -6f * u, 1.2f * u)
        ui.rect(pauseBtn.cx - 6f * u, pauseBtn.cy - 7f * u, 4f * u, 14f * u, withAlpha(Theme.TEXT, a))
        ui.rect(pauseBtn.cx + 2f * u, pauseBtn.cy - 7f * u, 4f * u, 14f * u, withAlpha(Theme.TEXT, a))

        // -------- first-flight hints
        if (hints > 0.01f) {
            val ha = a * hints
            val tx = if (mirror) g.x - 20f * u else g.x + g.w + 26f * u
            val al = if (mirror) UiRenderer.RIGHT else UiRenderer.LEFT
            if (!throttleUsed) {
                val bw = 250f * u
                ui.rect(if (mirror) tx - bw else tx - 10f * u, g.cy - 26f * u, bw + 10f * u, 44f * u, withAlpha(Theme.BLACK, ha * 0.45f), 8f * u, 0f, 12f * u)
                ui.text("SLIDE UP/DOWN · THROTTLE", tx, g.cy - 8f * u, 12f * u, withAlpha(Theme.TEXT, ha), al, 0.08f, shadow = 0.8f)
                ui.text(if (settings.stickXRoll) "SIDEWAYS · YAW   DOUBLE-TAP · STOP" else "SIDEWAYS · ROLL   DOUBLE-TAP · STOP",
                    tx, g.cy + 10f * u, 10f * u, withAlpha(Theme.TEXT_DIM, ha), al, 0.08f, shadow = 0.8f)
            }
            if (!stickUsed) {
                ui.rect(stickRestX - 140f * u, stickRestY - stickRadius - 44f * u, 280f * u, 46f * u, withAlpha(Theme.BLACK, ha * 0.45f), 8f * u, 0f, 12f * u)
                ui.textCentered("DRAG TO STEER", stickRestX, stickRestY - stickRadius - 22f * u, 12f * u, withAlpha(Theme.TEXT, ha), tracking = 0.1f, shadow = 0.8f)
                ui.textCentered(if (settings.stickXRoll) "UP/DOWN · PITCH   SIDEWAYS · ROLL" else "UP/DOWN · PITCH   SIDEWAYS · YAW",
                    stickRestX, stickRestY - stickRadius - 6f * u, 10f * u, withAlpha(Theme.TEXT_DIM, ha), tracking = 0.08f, shadow = 0.8f)
            }
        }
    }

    companion object {
        private const val REV = 0.22f
    }
}
