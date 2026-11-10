package com.starwake.core.input

import com.starwake.core.Haptics
import com.starwake.core.Settings
import com.starwake.core.flight.FlightConfig
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.clamp
import com.starwake.core.math.deadZone
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Rect
import com.starwake.core.ui.TextBuf
import com.starwake.core.ui.Theme
import com.starwake.core.ui.UiHost
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-thumb touch flight controls in the consolidated layout of 01-core-loop.md 5.6 (reference
 * Xiaomi 15 landscape, 929 × 417 u, right-handed; "left-handed" mirrors everything). They write
 * the pilot's input into an [InputFrame] once per frame ([write]); they hold no flight state of
 * their own except the lever's latched position, which the game keeps in step with the session.
 *
 * - Left thumb (anywhere in the left 42 %): the throttle *lever* (DECISIONS 19): an absolute lever
 *   from −25 % to 100 % with the zero line at 20 % of the gauge; slide up/down to set it, it stays
 *   where you leave it; slide sideways for the analog key axis (yaw, or roll with STICK X = YAW).
 *   Double-tap = lever to zero; double-tap and hold = full stop while held (Roblox X).
 * - Right thumb (the rest): a floating flight stick, the Roblox cursor (springs back to centre).
 * - Strafe cross ▲ ◀ ▶ ▼ beside the lever; BOOST, FA; the contextual ACTION pill; top bar
 *   PAUSE · MAP · LOCK (tap: next target, hold 0.5 s: clear) · VIEW; the look strip along the top
 *   (one-finger drag looks around, release returns the view).
 *
 * Every control of 5.6 is laid out; the ones whose feature does not exist yet (WALK, DATA, SCAN/MINE,
 * CANCEL JUMP) stay hidden until their port switches them on, so nothing moves later. AUTO shows
 * while a target is locked ([autoVisible], set by the game).
 */
class TouchFlightControls(private val settings: Settings, private val host: UiHost) {
    /** Latched lever position, −0.25..1 (the game copies the session's throttle into it every frame). */
    var throttle = 0f

    /** Stick deflection (cursor), X right, Y down, inside the unit circle: drawn as the steering arrow. */
    val stickVecX get() = stickOutX
    val stickVecY get() = stickOutY
    var stickUsed = false
        private set
    var throttleUsed = false
        private set

    // ---- what the game shows this frame
    /** Contextual action caption (LAND / DOCK / CANCEL / TAKE OFF ...); the pill hides when empty. */
    var actionLabel = ""

    /** The look strip takes touches (a view that can look around, flight controls owning the touches). */
    var lookEnabled = true

    /** Controls of later ports, laid out now and hidden until their feature exists. */
    var walkVisible = false
    var dataVisible = false
    var autoVisible = false
    var scanVisible = false
    var cancelJumpVisible = false

    // ---- layout (public for tests, the HUD and the harness)
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
    val walkBtn = Rect()
    val dataBtn = Rect()
    val actionBtn = Rect()
    val autoBtn = Rect()
    val scanBtn = Rect()
    val cancelJumpBtn = Rect()
    val upBtn = Rect()
    val downBtn = Rect()
    val leftBtn = Rect()
    val rightBtn = Rect()

    /** The look strip (01 5.6: x 150–640, y 0–56 u from the safe insets). */
    val lookStrip = Rect()

    /** Plates of the first-flight hints: the lever's beside the gauge, the stick's above its rest ring. */
    val throttleHint = Rect()
    val stickHint = Rect()
    private var stickRadius = 1f
    private var stickRestX = 0f
    private var stickRestY = 0f

    // ---- stick
    private var stickId = -1
    private var stickCx = 0f
    private var stickCy = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var stickOutX = 0f
    private var stickOutY = 0f
    private var stickDrawDx = 0f
    private var stickDrawDy = 0f

    // ---- lever
    private var thrId = -1
    private var thrGrabY = 0f
    private var thrGrabX = 0f
    private var thrGrabGauge = 0f
    private var thrDownTime = 0f
    private var thrMoved = false
    private var leverDirty = false
    private var lastTapTime = -10f
    private var rudder = 0f

    /** The lever touch is a double-tap held down: full stop while it stays (it does not move the lever). */
    private var fullStopTouch = false

    // ---- look strip
    private var lookId = -1
    private var lookLastX = 0f
    private var lookLastY = 0f
    private var pendingLookX = 0f
    private var pendingLookY = 0f

    // ---- buttons
    private var boostId = -1
    private var faId = -1
    private var camId = -1
    private var pauseId = -1
    private var lockId = -1
    private var lockDownTime = 0f
    private var lockHoldFired = false
    private var mapId = -1
    private var actionId = -1
    private var autoId = -1
    private var upId = -1
    private var downId = -1
    private var leftId = -1
    private var rightId = -1

    /** Presses since the last [write], per action. */
    private val pending = IntArray(Action.COUNT)

    /** Presses of the contextual ACTION pill since the last [write]. */
    private var pendingContext = 0
    private var used = false
    private var time = 0f
    private val buf = TextBuf(32)

    fun layout(width: Float, height: Float, unit: Float, sl: Float, st: Float, sr: Float, sb: Float) {
        releaseAll()
        w = width; h = height; u = unit
        mirror = settings.leftHanded
        zoneSplit = width * 0.42f
        stickRadius = 52f * u
        // build the right-handed layout; when mirrored, each side keeps its own inset
        val left = if (mirror) sr else sl
        val right = if (mirror) sl else sr
        // vertical positions are fractions of the 417 u reference height (5.6: they scale by h / 417)
        val k = height / REF_H
        gauge.set(left + 30f * u, height * 0.30f, 14f * u, height - sb - 52f * u - height * 0.30f)
        // top bar
        pauseBtn.set(left + 14f * u, st + 12f * u, 46f * u, 38f * u)
        walkBtn.set(left + 70f * u, st + 12f * u, 62f * u, 38f * u)
        camBtn.set(width - right - 14f * u - 56f * u, st + 12f * u, 56f * u, 38f * u)
        lockBtn.set(camBtn.x - 10f * u - 62f * u, st + 12f * u, 62f * u, 38f * u)
        mapBtn.set(lockBtn.x - 10f * u - 56f * u, st + 12f * u, 56f * u, 38f * u)
        dataBtn.set(mapBtn.x - 10f * u - 56f * u, st + 12f * u, 56f * u, 38f * u)
        // the strip between WALK and DATA: x 150 u to 289 u short of the right inset (640 at 929 u)
        lookStrip.set(left + 150f * u, st, maxOf(0f, width - right - 289f * u - (left + 150f * u)), 56f * u)
        // right column: BOOST (moved down for AUTO), FA, ACTION and AUTO pills
        val br = 31f * u
        boostBtn.set(width - right - 30f * u - 2 * br, BOOST_Y * k - br, 2 * br, 2 * br)
        val fr = 23f * u
        faBtn.set(boostBtn.cx - 92f * u - fr, height * 0.26f - fr, 2 * fr, 2 * fr)
        actionBtn.set(boostBtn.cx - 46f * u, height * 0.22f - 17f * u, 92f * u, 34f * u)
        autoBtn.set(actionBtn.x, actionBtn.y + 42f * u, 92f * u, 32f * u)
        // left column: SCAN/MINE above the strafe cross
        scanBtn.set(left + 60f * u, 74f * k, 80f * u, 32f * u)
        val sr2 = 19f * u
        upBtn.set(left + 130f * u - sr2, 140f * k - sr2, 2 * sr2, 2 * sr2)
        leftBtn.set(left + 86f * u - sr2, 177f * k - sr2, 2 * sr2, 2 * sr2)
        rightBtn.set(left + 174f * u - sr2, 177f * k - sr2, 2 * sr2, 2 * sr2)
        downBtn.set(left + 130f * u - sr2, 214f * k - sr2, 2 * sr2, 2 * sr2)
        cancelJumpBtn.set(width * 0.5f - 70f * u, height * 0.62f, 140f * u, 36f * u)
        stickRestX = width - right - 150f * u
        stickRestY = height - sb - 110f * u
        if (mirror) {
            for (r in arrayOf(
                gauge, pauseBtn, walkBtn, camBtn, lockBtn, mapBtn, dataBtn, lookStrip, boostBtn, faBtn, actionBtn,
                autoBtn, scanBtn, upBtn, downBtn, leftBtn, rightBtn, cancelJumpBtn,
            )) r.x = width - r.x - r.w
            // ◀ and ▶ keep their meaning: swap them back after the mirror
            val lx = leftBtn.x
            leftBtn.x = rightBtn.x
            rightBtn.x = lx
            stickRestX = width - stickRestX
        }
        // first-flight hints, on the inner side of the lever and above the stick's rest ring. BOOST
        // (moved down to make room for AUTO) reaches into the stick hint's row on phone screens:
        // there the plate moves inward, clear of the BOOST column, so it never covers a control
        val hy = gauge.y + gauge.h * 0.64f
        if (mirror) throttleHint.set(gauge.x - 270f * u, hy - 26f * u, 260f * u, 44f * u)
        else throttleHint.set(gauge.x + gauge.w + 16f * u, hy - 26f * u, 260f * u, 44f * u)
        val hintW = 280f * u
        val hintH = 46f * u
        val hintY = stickRestY - stickRadius - 44f * u
        val gap = 8f * u
        var hintX = stickRestX - hintW * 0.5f
        if (hintY < boostBtn.y + boostBtn.h + gap && hintY + hintH > boostBtn.y - gap) {
            hintX = if (mirror) maxOf(hintX, boostBtn.x + boostBtn.w + gap) else minOf(hintX, boostBtn.x - gap - hintW)
        }
        stickHint.set(hintX, hintY, hintW, hintH)
    }

    private fun inThrottleZone(x: Float) = if (mirror) x > w - zoneSplit else x < zoneSplit

    private fun hit(r: Rect, x: Float, y: Float, slop: Float) = r.contains(x, y, slop)

    private fun hitCircle(r: Rect, x: Float, y: Float, slop: Float): Boolean {
        val dx = x - r.cx
        val dy = y - r.cy
        val rr = r.w * 0.5f + slop
        return dx * dx + dy * dy <= rr * rr
    }

    private fun queue(a: Action) {
        pending[a.ordinal]++
        used = true
    }

    fun onDown(id: Int, x: Float, y: Float): Boolean {
        val slop = 8f * u
        when {
            pauseId < 0 && hit(pauseBtn, x, y, slop) -> {
                pauseId = id; host.haptic(Haptics.TAP)
            }
            camId < 0 && hit(camBtn, x, y, slop) -> {
                camId = id; queue(Action.EXTERNAL_VIEW); host.haptic(Haptics.TAP)
            }
            lockId < 0 && hit(lockBtn, x, y, slop) -> {
                lockId = id; lockDownTime = time; lockHoldFired = false; host.haptic(Haptics.TAP)
            }
            mapId < 0 && hit(mapBtn, x, y, slop) -> {
                mapId = id; queue(Action.SYSTEM_MAP); host.haptic(Haptics.TAP)
            }
            actionId < 0 && actionLabel.isNotEmpty() && hit(actionBtn, x, y, slop) -> {
                actionId = id; pendingContext++; used = true; host.haptic(Haptics.TAP)
            }
            autoId < 0 && autoVisible && hit(autoBtn, x, y, slop) -> {
                autoId = id; queue(Action.AUTOPILOT); host.haptic(Haptics.TAP)
            }
            upId < 0 && hitCircle(upBtn, x, y, slop * 0.5f) -> {
                upId = id; used = true; host.haptic(Haptics.TICK)
            }
            downId < 0 && hitCircle(downBtn, x, y, slop * 0.5f) -> {
                downId = id; used = true; host.haptic(Haptics.TICK)
            }
            leftId < 0 && hitCircle(leftBtn, x, y, slop * 0.5f) -> {
                leftId = id; used = true; host.haptic(Haptics.TICK)
            }
            rightId < 0 && hitCircle(rightBtn, x, y, slop * 0.5f) -> {
                rightId = id; used = true; host.haptic(Haptics.TICK)
            }
            boostId < 0 && hitCircle(boostBtn, x, y, slop) -> {
                boostId = id; queue(Action.BOOST)
            }
            faId < 0 && hitCircle(faBtn, x, y, slop) -> {
                faId = id; queue(Action.TOGGLE_FLIGHT_ASSIST); host.haptic(Haptics.TICK)
            }
            lookId < 0 && lookEnabled && hit(lookStrip, x, y, 0f) -> {
                lookId = id; lookLastX = x; lookLastY = y; used = true
            }
            thrId < 0 && inThrottleZone(x) -> {
                thrId = id
                thrGrabX = x; thrGrabY = y
                thrGrabGauge = gaugeOf(throttle)
                thrDownTime = time
                thrMoved = false
                // the second touch of a double-tap: full stop for as long as it is held
                fullStopTouch = time - lastTapTime < DOUBLE_TAP
                if (fullStopTouch) host.haptic(Haptics.BUMP)
                used = true
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
                used = true
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
                if (abs(y - thrGrabY) > 6f * u || abs(x - thrGrabX) > 6f * u) thrMoved = true
                val dx = (x - thrGrabX) / (48f * u)
                rudder = deadZone(clamp(dx, -1f, 1f), 0.18f)
                // a held double-tap is the full stop: it steers but leaves the lever alone
                if (fullStopTouch) return true
                val travel = gauge.h
                val g = clamp(thrGrabGauge + (thrGrabY - y) / travel, 0f, 1f)
                // symmetric zero detent: the same screen distance either side of the zero line
                val t = if (abs((g - ZERO) * travel) < 7f * u) 0f else throttleOf(g)
                if ((t == 0f) != (throttle == 0f) || (t >= 1f && throttle < 1f) || (t <= MIN_LEVER && throttle > MIN_LEVER)) {
                    host.haptic(Haptics.TICK)
                }
                if (t != throttle) {
                    throttleUsed = true
                    leverDirty = true
                }
                throttle = t
            }
            lookId -> {
                // 0.5° of look per u of finger travel x sensitivity (01 8.1.1); drag up looks up
                val k = LOOK_DEG_PER_U * settings.sensitivity / u
                val pitchSign = if (settings.pitchUpClimb) 1f else -1f
                pendingLookX += (x - lookLastX) * k
                pendingLookY += -(y - lookLastY) * k * pitchSign
                lookLastX = x; lookLastY = y
            }
            else -> return id == boostId || id == faId || id == camId || id == pauseId || id == lockId ||
                id == mapId || id == actionId || id == autoId || id == upId || id == downId || id == leftId || id == rightId
        }
        used = true
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
                val quick = !thrMoved && time - thrDownTime < TAP_TIME
                if (fullStopTouch) {
                    // the double-tap's second touch: the lever to zero (a held one has run it
                    // there through the full stop already)
                    fullStopTouch = false
                    lastTapTime = -10f
                    if (quick || throttle != 0f) {
                        throttle = 0f
                        leverDirty = true
                    }
                } else if (quick) {
                    lastTapTime = time
                }
            }
            lookId -> lookId = -1
            boostId -> boostId = -1
            faId -> faId = -1
            camId -> camId = -1
            lockId -> {
                lockId = -1
                // a short press steps to the next target; the hold already cleared it
                if (!lockHoldFired && !cancelled) queue(Action.CYCLE_TARGET)
            }
            mapId -> mapId = -1
            actionId -> actionId = -1
            autoId -> autoId = -1
            upId -> upId = -1
            downId -> downId = -1
            leftId -> leftId = -1
            rightId -> rightId = -1
            pauseId -> {
                pauseId = -1
                if (!cancelled && hit(pauseBtn, x, y, 16f * u)) queue(Action.PAUSE)
            }
            else -> return false
        }
        return true
    }

    /** Lets go of every touch (an overlay took the screen): the lever stays latched, the stick recentres. */
    fun releaseAll() {
        stickId = -1; thrId = -1; boostId = -1; faId = -1; camId = -1; pauseId = -1
        lockId = -1; upId = -1; downId = -1; leftId = -1; rightId = -1; lookId = -1
        mapId = -1; actionId = -1; autoId = -1
        fullStopTouch = false
        stickOutX = 0f; stickOutY = 0f; rudder = 0f
        pendingLookX = 0f; pendingLookY = 0f
        leverDirty = false
        pending.fill(0)
        pendingContext = 0
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
        // the cursor: offset / radius, inside the unit circle, no dead zone (the flight model has its own)
        stickOutX = dx / r
        stickOutY = dy / r
        if (stickOutX * stickOutX + stickOutY * stickOutY > STICK_USED * STICK_USED) stickUsed = true
    }

    /**
     * Writes this frame's touch input into [f] (call once per frame, after the pointer events and
     * before the session frame). [dt] drives the long-press timers.
     */
    fun write(f: InputFrame, dt: Float) {
        time += dt
        val pitchSign = if (settings.pitchUpClimb) 1f else -1f
        // SENSITIVITY shapes the deflection; a full one is the cursor's rim at every setting
        if (stickId >= 0) f.addStick(stickOutX, stickOutY, settings.sensitivity, pitchSign)
        if (rudder != 0f) f.keyAxis = clamp(f.keyAxis - rudder, -1f, 1f)
        if (leverDirty) {
            leverDirty = false
            f.leverSet = true
            f.lever = throttle
        }
        if (thrId >= 0 && fullStopTouch) f.hold(Action.FULL_STOP)
        if (upId >= 0) { f.lateralY += 1f; f.hold(Action.STRAFE_UP) }
        if (downId >= 0) { f.lateralY -= 1f; f.hold(Action.STRAFE_DOWN) }
        if (leftId >= 0) { f.lateralX -= 1f; f.hold(Action.STRAFE_LEFT) }
        if (rightId >= 0) { f.lateralX += 1f; f.hold(Action.STRAFE_RIGHT) }
        f.lateralX = clamp(f.lateralX, -1f, 1f)
        f.lateralY = clamp(f.lateralY, -1f, 1f)
        if (boostId >= 0) f.hold(Action.BOOST)
        if (lookId >= 0) {
            f.lookHeld = true
            f.hold(Action.LOOK)
        }
        f.lookX += pendingLookX
        f.lookY += pendingLookY
        pendingLookX = 0f
        pendingLookY = 0f
        // LOCK held 0.5 s clears the target (06 9.1), with a tick
        if (lockId >= 0 && !lockHoldFired && time - lockDownTime >= LOCK_HOLD) {
            lockHoldFired = true
            pending[Action.CLEAR_TARGET.ordinal]++
            host.haptic(Haptics.TICK)
        }
        for (i in 0 until Action.COUNT) {
            val n = pending[i]
            if (n == 0) continue
            pending[i] = 0
            repeat(n) { f.press(Action.of(i)) }
        }
        // the pill sends what its label means (LAND or DOCK), as the game set it for this frame
        val context = f.contextAction
        if (context != null) repeat(pendingContext) { f.press(context) }
        pendingContext = 0
        if (used || thrId >= 0 || stickId >= 0) f.device = InputFrame.TOUCH
        used = false
    }

    // ---- lever gauge mapping (ShipCursor's track): linear over −25 %..100 %, the zero line at 20 %
    private fun gaugeOf(t: Float): Float = (t - MIN_LEVER) / (1f - MIN_LEVER)
    private fun throttleOf(g: Float): Float = MIN_LEVER + g * (1f - MIN_LEVER)

    /**
     * Draws the controls. [speedMark] is speed / 1000 m/s (the track's scale, 02 6.3), [limitMark]
     * the raw limit / 1000 m/s (shown below 1 km/s, else negative); [boostCooldown] 0..1 of the
     * cooldown left, [boostBlocked] while cruising or overloaded; [autoOn]/[autoStatus] the
     * autopilot (the AUTO button shows while [autoVisible]).
     */
    fun draw(
        ui: UiRenderer, alpha: Float, speedMark: Float, limitMark: Float, flightAssist: Boolean,
        boosting: Boolean, boostCooldown: Float, boostBlocked: Boolean, hints: Float, locked: Boolean,
        autoOn: Boolean, autoStatus: String,
    ) {
        val a = alpha
        // -------- throttle gauge (Roblox ShipCursor's throttle track, 02 6.3)
        val g = gauge
        ui.rect(g.x - 4f * u, g.y - 4f * u, g.w + 8f * u, g.h + 8f * u, withAlpha(Theme.PANEL, a * 0.75f), 4f * u)
        val zeroY = g.y + g.h * (1f - ZERO)
        ui.rect(g.x, zeroY, g.w, g.h * ZERO, withAlpha(Theme.WARN, a * 0.12f), 2f * u)
        // fill from zero to the lever: forward orange, reverse rust
        val leverY = g.y + g.h * (1f - gaugeOf(throttle))
        if (throttle > 0f) {
            ui.rect(g.x, leverY, g.w, zeroY - leverY, withAlpha(OPAQUE or FlightConfig.Cursor.FILL_FORWARD, a * 0.75f), 2f * u)
        } else if (throttle < 0f) {
            ui.rect(g.x, zeroY, g.w, leverY - zeroY, withAlpha(OPAQUE or FlightConfig.Cursor.FILL_REVERSE, a * 0.85f), 2f * u)
        }
        // tick marks every 25%
        for (i in 1..3) {
            val ty = g.y + g.h * (1f - gaugeOf(i * 0.25f))
            ui.rect(if (mirror) g.x + g.w - 1f * u else g.x - 3f * u, ty, 4f * u, 1.2f * u, withAlpha(Theme.TEXT_DIM, a * 0.6f))
        }
        ui.rect(g.x - 6f * u, zeroY - 1f * u, g.w + 12f * u, 2f * u, withAlpha(Theme.TEXT, a * 0.8f))
        // the limit below 1 km/s (blue) and the speed (pale) on the track's scale: 0.2 + speed/1000 · 0.8
        if (limitMark in 0f..1f) {
            val ly = g.y + g.h * (1f - (ZERO + (1f - ZERO) * limitMark))
            ui.rect(g.x - 8f * u, ly - 1f * u, g.w + 16f * u, 2f * u, withAlpha(OPAQUE or FlightConfig.Cursor.LIMIT_MARK, a))
        }
        val speedG = ZERO + (1f - ZERO) * clamp(speedMark, 0f, 1f)
        val sy = g.y + g.h * (1f - speedG)
        ui.rect(g.x - 6f * u, sy - 1f * u, g.w + 12f * u, 2f * u, withAlpha(OPAQUE or FlightConfig.Cursor.SPEED_MARK, a))
        ui.textCentered(if (mirror) "▶" else "◀", if (mirror) g.x - 9f * u else g.x + g.w + 9f * u, sy, 9f * u, withAlpha(OPAQUE or FlightConfig.Cursor.SPEED_MARK, a))
        // lever handle
        val active = thrId >= 0
        val hw = g.w + (if (active) 22f else 14f) * u
        ui.rect(g.cx - hw * 0.5f, leverY - 5f * u, hw, 10f * u, withAlpha(Theme.TEXT, a * if (active) 1f else 0.85f), 3f * u)
        buf.clear().append((throttle * 100f).toInt()).append('%')
        ui.textCentered("THR", g.cx, g.y - 30f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        ui.textCentered(buf, g.cx, g.y - 15f * u, 12f * u, withAlpha(if (throttle < 0f) Theme.WARN else Theme.TEXT, a), face = FontData.MONO)
        if (active) {
            // key-axis indicator under the thumb
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

        // -------- look strip: invisible until used (a faint band while a finger is in it)
        if (lookId >= 0) {
            val s = lookStrip
            ui.rect(s.x, s.y, s.w, s.h, withAlpha(Theme.ACCENT, a * 0.06f), 8f * u)
            ui.rect(s.x, s.y + s.h - 2f * u, s.w, 2f * u, withAlpha(Theme.ACCENT, a * 0.35f))
        }

        // -------- buttons
        // BOOST: grey while cruising or overloaded; the cooldown (from ignition) fills the ring back up
        val ready = boostCooldown <= 0f && !boostBlocked
        val boostCol = if (ready || boosting) Theme.ACCENT else Theme.TEXT_DIM
        ui.circle(boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, withAlpha(if (boosting) Theme.ACCENT else Theme.PANEL, a * if (boosting) 0.85f else 0.8f))
        if (boostCooldown > 0f) {
            ui.circle(boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, withAlpha(Theme.PANEL_LIGHT, a * 0.9f), 2f * u)
            ring(ui, boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, 1f - clamp(boostCooldown, 0f, 1f), 2.5f * u, withAlpha(Theme.ACCENT, a * 0.9f))
        } else {
            ui.circle(boostBtn.cx, boostBtn.cy, boostBtn.w * 0.5f, withAlpha(boostCol, a * 0.85f), 2f * u)
        }
        ui.textCentered("BOOST", boostBtn.cx, boostBtn.cy, 10.5f * u, withAlpha(if (boosting) Theme.DARK else boostCol, a), tracking = 0.1f, weight = if (boosting) 0.05f else 0f)

        val faCol = if (flightAssist) Theme.ACCENT else Theme.WARN
        ui.circle(faBtn.cx, faBtn.cy, faBtn.w * 0.5f, withAlpha(Theme.PANEL, a * 0.8f))
        ui.circle(faBtn.cx, faBtn.cy, faBtn.w * 0.5f, withAlpha(faCol, a * 0.85f), 2f * u)
        ui.textCentered("FA", faBtn.cx, faBtn.cy - 5f * u, 11f * u, withAlpha(faCol, a), weight = 0.03f)
        ui.textCentered(if (flightAssist) "ON" else "OFF", faBtn.cx, faBtn.cy + 8f * u, 8f * u, withAlpha(faCol, a), tracking = 0.1f)

        barButton(ui, camBtn, "VIEW", a)
        val lockCol = if (locked) Theme.LOCK else Theme.ACCENT
        val holding = lockId >= 0 && !lockHoldFired
        ui.rect(lockBtn.x, lockBtn.y, lockBtn.w, lockBtn.h, withAlpha(if (locked) Theme.LOCK else Theme.PANEL, a * if (locked) 0.25f else 0.8f), -6f * u)
        if (holding) {
            // the hold-to-clear fills along the bottom edge
            val k = clamp((time - lockDownTime) / LOCK_HOLD, 0f, 1f)
            ui.rect(lockBtn.x, lockBtn.y + lockBtn.h - 3f * u, lockBtn.w * k, 3f * u, withAlpha(Theme.LOCK, a))
        }
        ui.rect(lockBtn.x, lockBtn.y, lockBtn.w, lockBtn.h, withAlpha(lockCol, a * 0.6f), -6f * u, 1.2f * u)
        ui.textCentered("LOCK", lockBtn.cx, lockBtn.cy, 11f * u, withAlpha(if (locked) Theme.LOCK else Theme.TEXT, a), tracking = 0.12f)
        barButton(ui, mapBtn, "MAP", a)

        if (autoVisible) {
            // AUTO (Roblox P): lit while the autopilot flies, its status under the label
            val r = autoBtn
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(if (autoOn) Theme.ACCENT else Theme.PANEL, a * if (autoOn) 0.85f else 0.8f), r.h * 0.5f)
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, a * 0.85f), r.h * 0.5f, 1.5f * u)
            if (autoOn) {
                ui.textCentered("AUTO", r.cx, r.cy - 5f * u, 10f * u, withAlpha(Theme.DARK, a), tracking = 0.15f, weight = 0.04f)
                ui.textCentered(autoStatus, r.cx, r.cy + 7f * u, 7f * u, withAlpha(Theme.DARK, a), tracking = 0.08f)
            } else {
                ui.textCentered("AUTO", r.cx, r.cy, 11f * u, withAlpha(Theme.ACCENT, a), tracking = 0.15f, weight = 0.03f)
            }
        }

        if (actionLabel.isNotEmpty()) {
            val pulse = 0.75f + 0.25f * kotlin.math.sin(time * 4f)
            ui.rect(actionBtn.x, actionBtn.y, actionBtn.w, actionBtn.h, withAlpha(Theme.LOCK, a * (if (actionId >= 0) 0.9f else 0.25f * pulse)), actionBtn.h * 0.5f)
            ui.rect(actionBtn.x, actionBtn.y, actionBtn.w, actionBtn.h, withAlpha(Theme.LOCK, a), actionBtn.h * 0.5f, 1.5f * u)
            ui.textCentered(actionLabel, actionBtn.cx, actionBtn.cy, 11.5f * u, withAlpha(if (actionId >= 0) Theme.DARK else Theme.LOCK, a), tracking = 0.15f, weight = 0.03f)
        }

        // strafe cross
        for (b in 0..3) {
            val r = when (b) {
                0 -> upBtn
                1 -> downBtn
                2 -> leftBtn
                else -> rightBtn
            }
            val held = when (b) {
                0 -> upId >= 0
                1 -> downId >= 0
                2 -> leftId >= 0
                else -> rightId >= 0
            }
            ui.circle(r.cx, r.cy, r.w * 0.5f, withAlpha(if (held) Theme.ACCENT else Theme.PANEL, a * if (held) 0.5f else 0.75f))
            ui.circle(r.cx, r.cy, r.w * 0.5f, withAlpha(Theme.ACCENT, a * 0.55f), 1.5f * u)
            ui.textCentered(
                when (b) {
                    0 -> "▲"
                    1 -> "▼"
                    2 -> "◀"
                    else -> "▶"
                },
                r.cx, r.cy, 10f * u, withAlpha(Theme.TEXT, a),
            )
        }

        ui.rect(pauseBtn.x, pauseBtn.y, pauseBtn.w, pauseBtn.h, withAlpha(Theme.PANEL, a * 0.8f), -6f * u)
        ui.rect(pauseBtn.x, pauseBtn.y, pauseBtn.w, pauseBtn.h, withAlpha(Theme.ACCENT, a * 0.5f), -6f * u, 1.2f * u)
        ui.rect(pauseBtn.cx - 6f * u, pauseBtn.cy - 7f * u, 4f * u, 14f * u, withAlpha(Theme.TEXT, a))
        ui.rect(pauseBtn.cx + 2f * u, pauseBtn.cy - 7f * u, 4f * u, 14f * u, withAlpha(Theme.TEXT, a))

        // -------- first-flight hints (below the strafe cross beside the lever, above the stick)
        if (hints > 0.01f) {
            val ha = a * hints
            if (!throttleUsed) {
                val r = throttleHint
                val tx = if (mirror) r.x + r.w - 10f * u else r.x + 10f * u
                val al = if (mirror) UiRenderer.RIGHT else UiRenderer.LEFT
                ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.BLACK, ha * 0.45f), 8f * u, 0f, 12f * u)
                ui.text("SLIDE UP/DOWN · THROTTLE", tx, r.y + 18f * u, 12f * u, withAlpha(Theme.TEXT, ha), al, 0.08f, shadow = 0.8f)
                ui.text(if (settings.stickXRoll) "SIDEWAYS · YAW   DOUBLE-TAP · ZERO" else "SIDEWAYS · ROLL   DOUBLE-TAP · ZERO",
                    tx, r.y + 36f * u, 10f * u, withAlpha(Theme.TEXT_DIM, ha), al, 0.08f, shadow = 0.8f)
            }
            if (!stickUsed) {
                val r = stickHint
                ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.BLACK, ha * 0.45f), 8f * u, 0f, 12f * u)
                ui.textCentered("DRAG TO STEER", r.cx, r.y + 22f * u, 12f * u, withAlpha(Theme.TEXT, ha), tracking = 0.1f, shadow = 0.8f)
                ui.textCentered(if (settings.stickXRoll) "UP/DOWN · PITCH   SIDEWAYS · ROLL" else "UP/DOWN · PITCH   SIDEWAYS · YAW",
                    r.cx, r.y + 38f * u, 10f * u, withAlpha(Theme.TEXT_DIM, ha), tracking = 0.08f, shadow = 0.8f)
            }
            if (lookEnabled) {
                ui.textCentered("DRAG HERE TO LOOK AROUND", lookStrip.cx, lookStrip.y + lookStrip.h - 8f * u, 9f * u, withAlpha(Theme.TEXT_DIM, ha * 0.8f), tracking = 0.15f, shadow = 0.8f)
            }
        }
    }

    /** An arc of [fraction] of a circle, clockwise from the top, as short line segments (no allocation). */
    private fun ring(ui: UiRenderer, cx: Float, cy: Float, r: Float, fraction: Float, width: Float, color: Int) {
        val n = (RING_SEGMENTS * fraction).toInt()
        var a0 = -HALF_PI
        val step = TWO_PI / RING_SEGMENTS
        for (i in 0 until n) {
            val a1 = a0 + step
            ui.line(cx + kotlin.math.cos(a0) * r, cy + kotlin.math.sin(a0) * r, cx + kotlin.math.cos(a1) * r, cy + kotlin.math.sin(a1) * r, width, color)
            a0 = a1
        }
    }

    private fun barButton(ui: UiRenderer, r: Rect, label: String, a: Float) {
        ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.PANEL, a * 0.8f), -6f * u)
        ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, a * 0.5f), -6f * u, 1.2f * u)
        ui.textCentered(label, r.cx, r.cy, 11f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f)
    }

    companion object {
        /** `MinThrottle`: the lever's full reverse. */
        const val MIN_LEVER = -0.25f

        /** The zero line, from the bottom of the gauge (`0.25 / 1.25`). */
        const val ZERO = 0.2f

        /** A touch shorter than this that did not move is a tap; two taps within [DOUBLE_TAP] a double-tap, s. */
        const val TAP_TIME = 0.25f
        const val DOUBLE_TAP = 0.35f
        private const val OPAQUE = -0x1000000
        private const val RING_SEGMENTS = 32
        private const val HALF_PI = 1.5707964f
        private const val TWO_PI = 6.2831855f

        /** Reference layout height, u (Xiaomi 15 landscape, 01 5.6). */
        const val REF_H = 417f

        /** BOOST centre at the reference height (5.6: moved down from 167 to make room for AUTO). */
        private const val BOOST_Y = 192f

        /** Touch look-around gain, degrees per u of finger travel (01 8.1.1). */
        const val LOOK_DEG_PER_U = 0.5f

        /** Long-press on LOCK that clears the target, s (06 9.1). */
        const val LOCK_HOLD = 0.5f

        /** A stick deflection above this counts as "the stick was used" (hints). */
        private const val STICK_USED = 0.08f
    }
}
