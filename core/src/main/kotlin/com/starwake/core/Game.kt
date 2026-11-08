package com.starwake.core

import com.starwake.core.content.Content
import com.starwake.core.content.Liveries
import com.starwake.core.game.AsteroidField
import com.starwake.core.game.ChaseCamera
import com.starwake.core.game.ControlInput
import com.starwake.core.game.FlightModel
import com.starwake.core.game.OrbitCamera
import com.starwake.core.game.ShipSpec
import com.starwake.core.game.ShipState
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.input.GamepadState
import com.starwake.core.input.InputQueue
import com.starwake.core.input.PointerHandler
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import com.starwake.core.math.clamp
import com.starwake.core.math.deadZone
import com.starwake.core.math.expApproach
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Frame
import com.starwake.core.ui.GameActions
import com.starwake.core.ui.MainMenu
import com.starwake.core.ui.PauseScreen
import com.starwake.core.ui.SettingsScreen
import com.starwake.core.ui.TextBuf
import com.starwake.core.ui.Theme
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Entry point of the game, driven by the platform's GL thread. Owns the state machine
 * (menu ⇄ flight, with settings/pause overlays), simulation, input routing and rendering.
 * The per-frame path performs no heap allocations.
 */
class Game(private val platform: Platform) : GameActions, PointerHandler {
    /** Pointer events in render-surface pixels (push from any thread). */
    val input = InputQueue()
    val gamepad = GamepadState()
    private val savedSettings = platform.loadSettings()
    override val settings = Settings().also { it.deserialize(savedSettings) }

    /** CPU time the last [drawFrame] took, for Android performance-hint reporting. */
    var lastFrameCpuNanos = 0L
        private set

    /** Current screen: "menu", "settings", "flight", "paused". */
    val stateName: String
        get() = when {
            overlay == OVERLAY_SETTINGS -> "settings"
            overlay == OVERLAY_PAUSE -> "paused"
            state == STATE_FLIGHT -> "flight"
            else -> "menu"
        }

    override val deviceInfo: String get() = platform.deviceInfo

    // ------------------------------------------------------------------ state
    private var state = STATE_MENU
    private var overlay = OVERLAY_NONE
    private var pendingAction = ACTION_NONE
    private var fade = 1f // 1 = black
    private var fadeTarget = 0f

    @Volatile private var backRequests = 0
    private var backHandled = 0

    @Volatile private var pauseRequested = false
    private val debugQueue = ConcurrentLinkedQueue<String>()

    // ------------------------------------------------------------------ GPU side
    private var gl: Gl? = null
    private var content: Content? = null
    private var scene: SceneRenderer? = null
    private var ui: UiRenderer? = null
    private var rebuildScene = false
    private val sceneFrame = SceneFrame()
    private val frame = Frame()
    private var surfaceW = 0
    private var surfaceH = 0
    private var displayApplied = false

    // ------------------------------------------------------------------ simulation
    private val spec = ShipSpec.VARROW
    private val ship = ShipState()
    private val menuShip = ShipState()
    private val flight = FlightModel(spec)
    private val controls = ControlInput()
    private val chase = ChaseCamera()
    private val orbit = OrbitCamera()
    private val touch = TouchFlightControls(settings, this)
    private val menu = MainMenu(this)
    private val settingsScreen = SettingsScreen(this)
    private val pause = PauseScreen(this)
    private var time = 0f
    private var lastFrameNanos = 0L
    private var flightTime = 0f
    private var hints = 1f
    private var proximity = Float.MAX_VALUE
    private var bumpCooldown = 0f
    private var settingsDirty = false
    private var activePlay = -1 // last value sent to Platform.setActivePlay (-1 = never)
    private var appliedQuality = -1
    private var lockIndex = -1
    private var padZeroHold = false

    // menu orbit drag
    private var orbitPointer = -1
    private var orbitLastX = 0f
    private var orbitLastY = 0f
    private var orbitVelX = 0f
    private var orbitVelY = 0f

    // fps meter
    private var fpsAccum = 0f
    private var fpsFrames = 0
    private var fpsValue = 0f
    private var cpuMsAvg = 0f

    // scratch
    private val tmp = Vec3()
    private val tmpQ = Quat()
    private val screenPt = Vec3()
    private val buf = TextBuf(64)
    private val menuShipRot = Quat()

    init {
        menuShipRot.setAxisAngle(0f, 1f, 0f, 2.75f)
        val roll = Quat().setAxisAngle(0f, 0f, 1f, 0.3f)
        val pitch = Quat().setAxisAngle(1f, 0f, 0f, 0.06f)
        menuShipRot.mul(menuShipRot, pitch).mul(menuShipRot, roll)
        menuShip.pos.set(AsteroidField.SHOWCASE_X.toDouble(), AsteroidField.SHOWCASE_Y.toDouble(), AsteroidField.SHOWCASE_Z.toDouble())
        menuShip.rot.set(menuShipRot)
        menuShip.engineOutput = 0.15f
        orbit.yaw = 0.18f
    }

    // ================================================================== platform callbacks

    /** GL context (re)created: (re)build all GPU resources. GL thread. */
    fun onSurfaceCreated(gl: Gl) {
        this.gl = gl
        // old GPU objects died with the previous context – just drop the references
        scene = null
        ui = null
        val c = content ?: Content(
            String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8),
            platform.readAsset("font/ui.sdf"),
        ).also { content = it }
        if (savedSettings == null && appliedQuality < 0) pickDefaultQuality(gl.getString(GL.RENDERER) ?: "")
        c.field.setQuality(settings.quality)
        scene = SceneRenderer(gl, c, settings.quality)
        appliedQuality = settings.quality
        ui = UiRenderer(gl, c.font, c.fontAtlas)
        lastFrameNanos = 0L
        if (!displayApplied) {
            displayApplied = true
            platform.applyDisplaySettings(settings.renderScale, settings.fpsLimit)
        }
    }

    /**
     * Surface size in pixels. [density] = surface pixels per dp (already multiplied by the render
     * scale). Safe insets (display cutout / rounded corners) are in surface pixels. GL thread.
     */
    fun onSurfaceChanged(
        width: Int, height: Int, density: Float,
        safeLeft: Float, safeTop: Float, safeRight: Float, safeBottom: Float,
    ) {
        surfaceW = width
        surfaceH = height
        val f = frame
        f.w = width.toFloat()
        f.h = height.toFloat()
        val d = if (density > 0f) density else 1f
        // shrink the UI on small/short screens so everything fits
        val scale = min(1f, min(f.h / d / 380f, f.w / d / 720f))
        f.u = d * scale
        f.sl = safeLeft; f.st = safeTop; f.sr = safeRight; f.sb = safeBottom
        relayout()
    }

    private fun relayout() {
        if (surfaceW == 0) return
        menu.layout(frame)
        settingsScreen.layout(frame)
        pause.layout(frame)
        touch.layout(frame.w, frame.h, frame.u, frame.sl, frame.st, frame.sr, frame.sb)
    }

    /** First run only: weak GPUs start on LOW (resolution scale, fewer effects, smaller bakes). */
    private fun pickDefaultQuality(renderer: String) {
        val r = renderer.lowercase()
        val adreno = Regex("adreno[^0-9]*([0-9]{3})").find(r)?.groupValues?.get(1)?.toIntOrNull()
        val weak = when {
            adreno != null -> adreno < 610
            r.contains("mali-t") || r.contains("mali-4") -> true
            Regex("mali-g(3[0-9]|5[0-9]|6[0-9])").containsMatchIn(r) -> true
            r.contains("powervr") -> true
            else -> false
        }
        val mid = adreno != null && adreno < 700 || Regex("mali-g(7[0-9])").containsMatchIn(r)
        settings.quality = if (weak) 0 else if (mid) 1 else 2
        settingsDirty = true
    }

    /** Persists pending settings changes (GL thread, e.g. queued when the app is paused). */
    fun flushSettings() = saveSettingsIfDirty()

    /** System back button/gesture. Any thread. */
    fun requestBack() {
        backRequests++
    }

    /** App went to background (auto-pauses flight). Any thread. */
    fun onPause() {
        pauseRequested = true
    }

    /**
     * Scripting hook for tests / the preview harness. Commands: "launch", "menu", "settings",
     * "pause", "resume", "fa", "boost", "camera", "livery". Any thread (applied next frame).
     */
    fun debugCommand(cmd: String) {
        debugQueue.add(cmd)
    }

    // ================================================================== frame

    /** Renders one frame. [frameTimeNanos] = vsync timestamp (System.nanoTime base). GL thread. */
    fun drawFrame(frameTimeNanos: Long) {
        val cpuStart = System.nanoTime()
        val gl = gl ?: return
        if (surfaceW == 0) return
        var dt = if (lastFrameNanos == 0L) 1f / 60f else (frameTimeNanos - lastFrameNanos) * 1e-9f
        lastFrameNanos = frameTimeNanos
        if (dt <= 0f) dt = 1f / 120f
        dt = min(dt, 0.1f)
        time += dt

        if (rebuildScene) {
            rebuildScene = false
            val c = content!!
            val q = settings.quality
            c.field.setQuality(q)
            val sc = scene
            if (sc != null && !sc.needsRebake(q)) {
                sc.setQuality(q)
            } else {
                sc?.dispose()
                scene = SceneRenderer(gl, c, q)
            }
            appliedQuality = q
            if (lockIndex >= 0 && !c.field.active[lockIndex]) lockIndex = -1
        }

        handleSystemEvents()
        input.drain(this)
        update(dt)
        val active = if (state == STATE_FLIGHT && overlay == OVERLAY_NONE) 1 else 0
        if (active != activePlay) {
            activePlay = active
            platform.setActivePlay(active == 1)
        }
        render(gl, dt)
        lastFrameCpuNanos = System.nanoTime() - cpuStart
        updateFps(dt)
    }

    private fun handleSystemEvents() {
        while (true) {
            val cmd = debugQueue.poll() ?: break
            when (cmd) {
                "launch" -> if (state == STATE_MENU) { startFlight(); fade = 0f }
                "menu" -> { enterMenu(); fade = 0f }
                "settings" -> openSettings()
                "pause" -> if (state == STATE_FLIGHT) openPause()
                "resume" -> resume()
                "fa" -> toggleFlightAssist()
                "boost" -> controls.boost = true
                "camera" -> chase.mode = (chase.mode + 1) % 3
                "livery" -> settings.livery = (settings.livery + 1) % Liveries.all.size
            }
        }
        if (pauseRequested) {
            pauseRequested = false
            touch.releaseAll()
            if (state == STATE_FLIGHT && overlay == OVERLAY_NONE) openPause()
            saveSettingsIfDirty()
        }
        val br = backRequests
        while (backHandled != br) {
            backHandled++
            onBack()
        }
        // gamepad buttons that act like system navigation
        if (gamepad.consumePress(GamepadState.START)) {
            if (state == STATE_FLIGHT) {
                if (overlay == OVERLAY_PAUSE) resume() else if (overlay == OVERLAY_NONE) openPause()
            } else if (overlay == OVERLAY_NONE) launch()
        }
        if (gamepad.consumePress(GamepadState.B)) onBack()
        if (state == STATE_MENU && overlay == OVERLAY_NONE && gamepad.consumePress(GamepadState.A)) launch()
    }

    private fun onBack() {
        when {
            overlay == OVERLAY_SETTINGS -> closeSettings()
            overlay == OVERLAY_PAUSE -> resume()
            state == STATE_FLIGHT -> openPause()
            else -> exitApp()
        }
    }

    // ================================================================== input routing

    override fun onPointerDown(id: Int, x: Float, y: Float) {
        if (fadeTarget > 0.5f) return // transition in progress
        when {
            overlay == OVERLAY_SETTINGS -> settingsScreen.group.down(id, x, y)
            overlay == OVERLAY_PAUSE -> pause.group.down(id, x, y)
            state == STATE_MENU -> {
                if (!menu.group.down(id, x, y) && orbitPointer < 0) {
                    orbitPointer = id
                    orbitLastX = x; orbitLastY = y
                    orbitVelX = 0f; orbitVelY = 0f
                }
            }
            else -> touch.onDown(id, x, y)
        }
    }

    override fun onPointerMove(id: Int, x: Float, y: Float) {
        if (settingsScreen.group.move(id, x, y)) return
        if (pause.group.move(id, x, y)) return
        if (menu.group.move(id, x, y)) return
        if (id == orbitPointer) {
            val dx = x - orbitLastX
            val dy = y - orbitLastY
            orbit.drag(dx, dy, ORBIT_PX_PER_RAD * frame.u)
            orbitVelX = orbitVelX * 0.5f + dx * 30f
            orbitVelY = orbitVelY * 0.5f + dy * 30f
            orbitLastX = x; orbitLastY = y
            menu.dragHint = 0f
            return
        }
        touch.onMove(id, x, y)
    }

    override fun onPointerUp(id: Int, x: Float, y: Float, cancelled: Boolean) {
        settingsScreen.group.up(id, x, y, cancelled)
        pause.group.up(id, x, y, cancelled)
        menu.group.up(id, x, y, cancelled)
        if (id == orbitPointer) {
            orbitPointer = -1
            if (!cancelled) orbit.fling(orbitVelX, orbitVelY, ORBIT_PX_PER_RAD * frame.u)
        }
        touch.onUp(id, x, y, cancelled)
    }

    // ================================================================== actions (GameActions)

    override fun haptic(type: Int) {
        if (settings.haptics) platform.haptic(type)
    }

    override fun launch() {
        if (state == STATE_MENU && pendingAction == ACTION_NONE) {
            pendingAction = ACTION_LAUNCH
            fadeTarget = 1f
        }
    }

    override fun openSettings() {
        overlay = OVERLAY_SETTINGS
        touch.releaseAll()
        screenChanged()
    }

    override fun closeSettings() {
        overlay = if (state == STATE_FLIGHT) OVERLAY_PAUSE else OVERLAY_NONE
        saveSettingsIfDirty()
        screenChanged()
    }

    override fun resume() {
        if (state == STATE_FLIGHT) {
            overlay = OVERLAY_NONE
            screenChanged()
        }
    }

    /** Drops input that belonged to the previous screen (held widgets, queued pad presses). */
    private fun screenChanged() {
        settingsScreen.group.releaseAll()
        pause.group.releaseAll()
        menu.group.releaseAll()
        orbitPointer = -1
        gamepad.discardPresses()
    }

    override fun quitToMenu() {
        if (pendingAction == ACTION_NONE) {
            pendingAction = ACTION_MENU
            fadeTarget = 1f
        }
    }

    override fun exitApp() {
        saveSettingsIfDirty()
        platform.exitApp()
    }

    override fun settingsChanged(display: Boolean, renderer: Boolean) {
        settingsDirty = true
        if (renderer) rebuildScene = true
        if (display) platform.applyDisplaySettings(settings.renderScale, settings.fpsLimit)
        // layout depends on handedness
        touch.layout(frame.w, frame.h, frame.u, frame.sl, frame.st, frame.sr, frame.sb)
    }

    private fun saveSettingsIfDirty() {
        if (settingsDirty) {
            settingsDirty = false
            platform.saveSettings(settings.serialize())
        }
    }

    private fun openPause() {
        overlay = OVERLAY_PAUSE
        touch.releaseAll()
        controls.clearAxes()
        screenChanged()
    }

    private fun startFlight() {
        state = STATE_FLIGHT
        overlay = OVERLAY_NONE
        ship.reset()
        ship.pos.set(0.0, 0.0, 0.0)
        touch.releaseAll()
        touch.throttle = 0.3f
        controls.clearAxes()
        controls.throttle = 0.3f
        ship.vel.set(0f, 0f, -0.3f * spec.maxSpeed)
        chase.mode = 0
        chase.snapTo(ship)
        flightTime = 0f
        hints = 1f
        menu.dragHint = 0f
        lockIndex = -1
        padZeroHold = false
        screenChanged()
    }

    private fun enterMenu() {
        state = STATE_MENU
        overlay = OVERLAY_NONE
        touch.releaseAll()
        controls.clearAxes()
        orbit.idle = 10f
        lockIndex = -1
        screenChanged()
    }

    private fun toggleFlightAssist() {
        ship.flightAssist = !ship.flightAssist
        haptic(Haptics.TICK)
    }

    // ================================================================== update

    private fun update(dt: Float) {
        // fade transitions
        fade += (fadeTarget - fade) * expApproach(if (fadeTarget > fade) 14f else 5f, dt)
        if (pendingAction != ACTION_NONE && fade > 0.97f) {
            when (pendingAction) {
                ACTION_LAUNCH -> startFlight()
                ACTION_MENU -> enterMenu()
            }
            pendingAction = ACTION_NONE
            fade = 1f
            fadeTarget = 0f
        }
        if (state == STATE_MENU) updateMenu(dt) else updateFlight(dt)
    }

    private fun updateMenu(dt: Float) {
        orbit.update(dt)
        menuShip.pos.y = AsteroidField.SHOWCASE_Y + sin(time * 0.7f) * 0.35
        tmpQ.setAxisAngle(0f, 0f, 1f, sin(time * 0.5f) * 0.03f)
        menuShip.rot.mul(menuShipRot, tmpQ)
        menuShip.engineOutput = 0.12f + 0.03f * sin(time * 3f)
        val cam = sceneFrame.camera
        orbit.apply(menuShip.pos, cam.pos, cam.rot)
        cam.fovY = 42f * DEG2RAD
        // lens shift moves the ship to the right of the menu column on wide screens
        cam.shiftX = if (frame.w > frame.h * 1.4f) 0.30f else 0f
        cam.shiftY = 0.04f
        sceneFrame.dustVelocity.zero()
        sceneFrame.dustIntensity = 0.35f
        sceneFrame.ship = menuShip
    }

    private fun updateFlight(dt: Float) {
        val paused = overlay != OVERLAY_NONE
        if (!paused) {
            flightTime += dt
            touch.update(dt)
            readControls(dt)
            if (touch.consumeFa() || gamepad.consumePress(GamepadState.X)) toggleFlightAssist()
            if (touch.consumeCam() || gamepad.consumePress(GamepadState.Y)) {
                chase.mode = (chase.mode + 1) % 3
            }
            if (touch.consumePause()) openPause()
            if (touch.consumeBoost() || gamepad.consumePress(GamepadState.A)) controls.boost = true
            if (touch.consumeLock() || gamepad.consumePress(GamepadState.DPAD_UP)) toggleLock()

            val steps = clamp(ceil(dt / MAX_STEP).toInt().toFloat(), 1f, 8f).toInt()
            val h = dt / steps
            for (i in 0 until steps) {
                if (flight.step(ship, controls, h)) {
                    haptic(Haptics.HEAVY)
                    chase.addShake(0.8f)
                }
                controls.boost = false
                // collide every substep so fast ships can't tunnel through small rocks
                collide(h)
            }
            validateLock()
            if (touch.throttleUsed && touch.stickUsed) hints -= dt * 0.8f
            else if (flightTime > 25f) hints -= dt * 0.3f
            hints = clamp(hints, 0f, 1f)
        }
        val speedFrac = ship.speed / spec.maxSpeed
        if (!paused) chase.update(ship, dt, clamp(speedFrac, 0f, 1.8f), spec.maxSpeed)
        val cam = sceneFrame.camera
        cam.pos.set(chase.pos)
        chase.renderRotation(cam.rot)
        val boostFov = if (ship.boosting) 9f else 0f
        chase.fovBoost += (boostFov - chase.fovBoost) * expApproach(4f, dt)
        cam.fovY = (if (chase.mode == 2) 60f else 52f + speedFrac * 3f + chase.fovBoost) * DEG2RAD
        cam.shiftX = 0f
        cam.shiftY = 0f
        sceneFrame.dustVelocity.set(ship.vel)
        sceneFrame.dustIntensity = 0.6f
        // speed lines: white in open space, red for danger (blue is reserved for gravity wells)
        val danger = proximity < 80f
        val dc = sceneFrame.dustColor
        val k = expApproach(4f, dt)
        dc.x += ((if (danger) 1.0f else 0.66f) - dc.x) * k
        dc.y += ((if (danger) 0.30f else 0.72f) - dc.y) * k
        dc.z += ((if (danger) 0.26f else 0.80f) - dc.z) * k
        sceneFrame.ship = if (chase.mode == 2) null else ship
    }

    private fun readControls(dt: Float) {
        val c = controls
        c.pitch = touch.pitch
        c.yaw = touch.yaw
        c.roll = touch.roll
        c.strafeX = 0f
        c.strafeY = touch.vertical
        if (gamepad.connected) {
            val gp = gamepad
            val sens = settings.sensitivity
            // the platform already applies a radial dead zone and rescales
            val lx = gp.leftX * sens
            val ly = gp.leftY * sens
            val rx = gp.rightX * sens
            val ry = gp.rightY
            if (lx != 0f || ly != 0f || rx != 0f) {
                val gpPitch = if (settings.pitchUpClimb) -ly else ly
                c.pitch = clamp(c.pitch + gpPitch, -1f, 1f)
                c.roll = clamp(c.roll + lx, -1f, 1f)
                c.yaw = clamp(c.yaw + rx, -1f, 1f)
            }
            c.strafeY = clamp(c.strafeY - ry, -1f, 1f)
            if (gp.isHeld(GamepadState.LB)) c.strafeX -= 1f
            if (gp.isHeld(GamepadState.RB)) c.strafeX += 1f
            val thr = gp.rightTrigger - gp.leftTrigger
            if (abs(thr) > 0.05f) {
                val old = touch.throttle
                var t = clamp(old + thr * 0.8f * dt, -1f, 1f)
                // zero detent: crossing zero stops at zero until the triggers are released
                if (padZeroHold || (old > 0f && t <= 0f) || (old < 0f && t >= 0f)) {
                    if (!padZeroHold) haptic(Haptics.TICK)
                    t = 0f
                    padZeroHold = true
                }
                touch.throttle = t
            } else {
                padZeroHold = false
                if (abs(touch.throttle) < 0.03f) touch.throttle = 0f
            }
        }
        c.throttle = touch.throttle
    }

    private fun collide(dt: Float) {
        val field = content?.field ?: return
        bumpCooldown -= dt
        var nearest = Float.MAX_VALUE
        val ox = field.origin.x
        val oy = field.origin.y
        val oz = field.origin.z
        val sx = (ship.pos.x - ox).toFloat()
        val sy = (ship.pos.y - oy).toFloat()
        val sz = (ship.pos.z - oz).toFloat()
        for (i in 0 until field.total) {
            if (!field.active[i]) continue
            val dx = field.px[i] - sx
            val dy = field.py[i] - sy
            val dz = field.pz[i] - sz
            val r = field.collisionRadius[i]
            val reach = r + 400f
            if (abs(dx) > reach || abs(dy) > reach || abs(dz) > reach) continue
            val d = sqrt(dx * dx + dy * dy + dz * dz) - r - spec.collisionRadius
            if (d < nearest) nearest = d
            if (d < 0f) {
                val impact = flight.collideSphere(ship, ox + field.px[i], oy + field.py[i], oz + field.pz[i], r)
                if (impact > 2f && bumpCooldown <= 0f) {
                    bumpCooldown = 0.25f
                    haptic(if (impact > 40f) Haptics.HEAVY else Haptics.BUMP)
                    chase.addShake(clamp(impact / 50f, 0.2f, 1.2f))
                }
            }
        }
        proximity = nearest
    }

    // ================================================================== render

    private fun render(gl: Gl, dt: Float) {
        val scene = scene ?: return
        val ui = ui ?: return
        val f = sceneFrame
        f.time = time
        f.livery = settings.livery
        f.quality = scene.quality
        f.pxScale = surfaceH / 1080f
        f.camera.update(surfaceW, surfaceH)
        scene.render(f)

        ui.begin(surfaceW, surfaceH)
        val u = frame.u
        if (state == STATE_MENU) {
            if (overlay == OVERLAY_NONE) menu.draw(ui, frame, 1f, time)
        } else {
            if (overlay == OVERLAY_NONE) drawHud(ui, 1f)
        }
        when (overlay) {
            OVERLAY_SETTINGS -> settingsScreen.draw(ui, frame, 1f)
            OVERLAY_PAUSE -> pause.draw(ui, frame, 1f)
        }
        if (settings.showFps) {
            buf.clear().append(fpsValue.toInt()).append(" FPS  ").append(cpuMsAvg, 1).append(" MS CPU")
            ui.text(buf, frame.w - frame.sr - 14f * u, frame.st + 66f * u, 10f * u, withAlpha(Theme.OK, 0.9f), UiRenderer.RIGHT, shadow = 0.7f)
        }
        if (fade > 0.003f) ui.rect(-2f, -2f, frame.w + 4f, frame.h + 4f, withAlpha(Theme.BLACK, fade))
        ui.end()
        gl.invalidateDepth()
    }

    private fun drawHud(ui: UiRenderer, alpha: Float) {
        val u = frame.u
        val cam = sceneFrame.camera
        val a = alpha
        // reticle where the nose points (800 m ahead) and the velocity vector marker
        ship.rot.rotate(0f, 0f, -800f, tmp)
        relToCam(tmp)
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
            relToCam(tmp)
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

        drawSteeringArrow(ui, a)
        drawLock(ui, a)

        // speed readout beside the throttle lever (keeps the centre clear for the ship)
        val g = touch.gauge
        val mirrored = settings.leftHanded
        val sx = if (mirrored) g.x - 26f * u else g.x + g.w + 26f * u
        val align = if (mirrored) UiRenderer.RIGHT else UiRenderer.LEFT
        val sy = g.y + g.h
        val speed = ship.forwardSpeed
        val unit = formatSpeed(buf.clear(), abs(speed))
        val numW = ui.text(buf, sx, sy - 12f * u, 28f * u, withAlpha(if (speed < -1f) Theme.WARN else Theme.TEXT, a), align, 0f, 0.02f, shadow = 0.7f, face = FontData.MONO)
        val unitX = if (mirrored) sx - numW - 6f * u else sx + numW + 6f * u
        ui.text(unit, unitX, sy - 12f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a), align, tracking = 0.15f, shadow = 0.7f)
        // boost capacitor bar
        val bw = 104f * u
        val bx = if (mirrored) sx - bw else sx
        ui.rect(bx, sy - 3f * u, bw, 4f * u, withAlpha(Theme.PANEL, a * 0.9f), 2f * u)
        val capCol = if (ship.capacitor >= spec.boostCost) Theme.ACCENT else Theme.TEXT_DIM
        ui.rect(bx, sy - 3f * u, bw * ship.capacitor, 4f * u, withAlpha(capCol, a), 2f * u)
        ui.rect(bx + bw * spec.boostCost, sy - 6f * u, 1.2f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f))
        // zone, flight-assist and proximity warnings (top centre)
        val cx = frame.w * 0.5f
        ui.textCentered("OPEN SPACE", cx, frame.st + 30f * u, 10.5f * u, withAlpha(Theme.TEXT, a * 0.85f), tracking = 0.3f, shadow = 0.6f)
        var warnY = frame.st + 52f * u
        if (!ship.flightAssist) {
            val blink = if ((time * 2f).toInt() % 2 == 0) 1f else 0.55f
            ui.textCentered("FLIGHT ASSIST OFF", cx, warnY, 11f * u, withAlpha(Theme.WARN, a * blink), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (proximity < 60f) {
            val blink = if ((time * 4f).toInt() % 2 == 0) 1f else 0.4f
            ui.textCentered("PROXIMITY", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
        }

        touch.draw(
            ui, a, ship.forwardSpeed / spec.maxSpeed, ship.flightAssist,
            ship.capacitor >= spec.boostCost, ship.boosting, ship.capacitor, hints, lockIndex >= 0,
        )
    }

    /** Arrow from the screen centre showing which way the stick is turning the ship. */
    private fun drawSteeringArrow(ui: UiRenderer, a: Float) {
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

    /** Locks the asteroid closest to the nose (within 18° and 8 km), or releases the lock. */
    private fun toggleLock() {
        if (lockIndex >= 0) {
            lockIndex = -1
            haptic(Haptics.TICK)
            return
        }
        val field = content?.field ?: return
        ship.rot.rotate(0f, 0f, -1f, tmp)
        val ox = field.origin.x - ship.pos.x
        val oy = field.origin.y - ship.pos.y
        val oz = field.origin.z - ship.pos.z
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in 0 until field.total) {
            if (!field.active[i]) continue
            val dx = (ox + field.px[i]).toFloat()
            val dy = (oy + field.py[i]).toFloat()
            val dz = (oz + field.pz[i]).toFloat()
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > LOCK_RANGE || dist < 1f) continue
            val cos = (dx * tmp.x + dy * tmp.y + dz * tmp.z) / dist
            if (cos < LOCK_CONE_COS) continue
            val score = (1f - cos) * 20000f + dist
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        lockIndex = best
        haptic(if (best >= 0) Haptics.TICK else Haptics.BUMP)
    }

    private fun validateLock() {
        val i = lockIndex
        if (i < 0) return
        val field = content?.field ?: return
        if (!field.active[i] || lockDistance(i) > LOCK_RANGE * 1.5f) lockIndex = -1
    }

    /** Distance from the ship to the surface of asteroid [i]. */
    private fun lockDistance(i: Int): Float {
        val field = content!!.field
        val dx = (field.origin.x + field.px[i] - ship.pos.x).toFloat()
        val dy = (field.origin.y + field.py[i] - ship.pos.y).toFloat()
        val dz = (field.origin.z + field.pz[i] - ship.pos.z).toFloat()
        return sqrt(dx * dx + dy * dy + dz * dz) - field.collisionRadius[i]
    }

    /** Amber lock brackets with the target distance (distance is shown only for the locked target). */
    private fun drawLock(ui: UiRenderer, a: Float) {
        val i = lockIndex
        if (i < 0) return
        val field = content?.field ?: return
        val cam = sceneFrame.camera
        val c = cam.pos
        val rx = (field.origin.x + field.px[i] - c.x).toFloat()
        val ry = (field.origin.y + field.py[i] - c.y).toFloat()
        val rz = (field.origin.z + field.pz[i] - c.z).toFloat()
        if (!cam.project(rx, ry, rz, screenPt)) return
        val u = frame.u
        val pxPerUnit = frame.h * 0.5f / cam.tanHalfY / screenPt.z
        val half = maxOf(field.radius[i] * pxPerUnit * 0.9f, 16f * u)
        val x = screenPt.x
        val y = screenPt.y
        val arm = maxOf(half * 0.35f, 8f * u)
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
        val unit = formatDistance(buf.clear(), lockDistance(i))
        val tw = ui.text(buf, x, y + half + 18f * u, 12f * u, col, UiRenderer.CENTER, shadow = 0.7f, face = FontData.MONO)
        ui.text(unit, x + tw * 0.5f + 4f * u, y + half + 18f * u, 9f * u, col, shadow = 0.7f)
    }

    /** Appends a speed value in m/s, km/s or Mm/s to [b]; returns the unit label. */
    private fun formatSpeed(b: TextBuf, mps: Float): String = when {
        mps < 1000f -> {
            b.appendPadded(mps.toInt(), 3); "M/S"
        }
        mps < 1_000_000f -> {
            b.append(mps / 1000f, 2); "KM/S"
        }
        else -> {
            b.append(mps / 1_000_000f, 2); "MM/S"
        }
    }

    /** Appends a distance in m, km or Mm to [b]; returns the unit label. */
    private fun formatDistance(b: TextBuf, m: Float): String = when {
        m < 1000f -> {
            b.append(maxOf(m, 0f).toInt()); "M"
        }
        m < 1_000_000f -> {
            b.append(m / 1000f, 2); "KM"
        }
        else -> {
            b.append(m / 1_000_000f, 2); "MM"
        }
    }

    /** Converts a ship-relative offset into camera-relative coordinates (in place). */
    private fun relToCam(v: Vec3) {
        val c = sceneFrame.camera.pos
        v.x += (ship.pos.x - c.x).toFloat()
        v.y += (ship.pos.y - c.y).toFloat()
        v.z += (ship.pos.z - c.z).toFloat()
    }

    private fun updateFps(dt: Float) {
        fpsAccum += dt
        fpsFrames++
        cpuMsAvg += (lastFrameCpuNanos / 1e6f - cpuMsAvg) * 0.05f
        if (fpsAccum >= 0.5f) {
            fpsValue = fpsFrames / fpsAccum
            fpsAccum = 0f
            fpsFrames = 0
        }
    }

    companion object {
        private const val STATE_MENU = 0
        private const val STATE_FLIGHT = 1
        private const val OVERLAY_NONE = 0
        private const val OVERLAY_SETTINGS = 1
        private const val OVERLAY_PAUSE = 2
        private const val ACTION_NONE = 0
        private const val ACTION_LAUNCH = 1
        private const val ACTION_MENU = 2
        private const val MAX_STEP = 1f / 120f
        private const val ORBIT_PX_PER_RAD = 260f
        private const val LOCK_RANGE = 8000f
        private const val LOCK_CONE_COS = 0.951f // 18°
    }
}
