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
import com.starwake.core.game.StarSystem
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
import com.starwake.core.math.expApproach
import com.starwake.core.math.withAlpha
import com.starwake.core.ui.Frame
import com.starwake.core.ui.GameActions
import com.starwake.core.ui.MainMenu
import com.starwake.core.ui.MapHost
import com.starwake.core.ui.MapScreen
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
class Game(private val platform: Platform) : GameActions, MapHost, PointerHandler {
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
            overlay == OVERLAY_MAP -> "map"
            overlay == OVERLAY_DOCKED -> "docked"
            state == STATE_FLIGHT -> "flight"
            else -> "menu"
        }

    override val deviceInfo: String get() = platform.deviceInfo

    /** Harness/test read-out of the flight state (allocates; never called per frame by the game). */
    val debugInfo: String
        get() = "state=$stateName landed=$landed landing=$landing docking=$docking docked=$docked " +
            "alt=${"%.1f".format(here.altitude - spec.collisionRadius)} speed=${"%.1f".format(ship.speed)} " +
            "boosting=${ship.boosting} cap=${"%.2f".format(ship.capacitor)} lock=$lockKind:$lockIndex " +
            "hull=${"%.1f".format(if (here.nearestStation >= 0) stationCollision.clearance(ship, system.stations[here.nearestStation]) else Float.MAX_VALUE)} " +
            "prox=${"%.1f".format(proximity)}"

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
    private val system = StarSystem()
    private val here = StarSystem.Locality()
    private val dustOrigin = com.starwake.core.math.DVec3()
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
    private val map = MapScreen(this, system)
    private val dockedScreen = com.starwake.core.ui.DockedScreen(this)
    private var time = 0f
    private var lastFrameNanos = 0L
    private var flightTime = 0f
    private var hints = 1f
    private var proximity = Float.MAX_VALUE
    private var bumpCooldown = 0f
    private var settingsDirty = false
    private var activePlay = -1 // last value sent to Platform.setActivePlay (-1 = never)
    private var appliedQuality = -1
    private var lockKind = LOCK_NONE
    private var lockIndex = -1
    private var aimKind = LOCK_NONE

    // landing (L): mode, touchdown state and gear animation (legs 1.6 s, then doors 0.6 s)
    private var landing = false
    private var landed = false
    private var landedBody = -1
    /** Seconds of assisted climb left after a take-off (Flight Assist would otherwise stop it). */
    private var liftTime = 0f
    private var gear = 0f
    private var message: String? = null
    private var messageTime = 0f
    private val upV = Vec3()
    private val fwdV = Vec3()
    private val rightV = Vec3()
    private val landRot = Quat()

    // docking (K): automatic approach into the slot, then the docked screen
    private var docking = false
    /** Parked in a slot: pause/resume returns to the docked screen, not free flight. */
    private var docked = false
    private var dockStation = -1
    private var dockPhase = 0 // 0 approach point, 1 slide into the slot
    private val dockPos = com.starwake.core.math.DVec3()
    private val dockDir = Vec3()
    private val portLocal = Vec3()
    private val portDirLocal = Vec3()

    // station hulls: collision proxies, auto-dock routing around them
    private val stationCollision = com.starwake.core.game.StationCollision(flight)

    // flight HUD target labels placed this frame (x0, y0, x1, y1), so they never overlap
    private val labelBoxes = FloatArray(4 * MAX_LABELS)
    private var labelCount = 0
    private val dockWaypoint = com.starwake.core.math.DVec3()
    private var dockBoxedTime = 0f // how long the router has found no way out (it then holds still)
    private var launchTime = -100f // last launch from a slot: no station proximity warning just after
    private var aimIndex = -1
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
    private val tmp2 = Vec3()
    private val buf = TextBuf(64)
    private val menuShipRot = Quat()

    init {
        menuShipRot.setAxisAngle(0f, 1f, 0f, 2.75f)
        val roll = Quat().setAxisAngle(0f, 0f, 1f, 0.3f)
        val pitch = Quat().setAxisAngle(1f, 0f, 0f, 0.06f)
        menuShipRot.mul(menuShipRot, pitch).mul(menuShipRot, roll)
        menuShip.pos.set(
            system.home.x + AsteroidField.SHOWCASE_X,
            system.home.y + AsteroidField.SHOWCASE_Y,
            system.home.z + AsteroidField.SHOWCASE_Z,
        )
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
            system,
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
        map.layout(frame)
        dockedScreen.layout(frame)
        touch.layout(frame.w, frame.h, frame.u, frame.sl, frame.st, frame.sr, frame.sb)
    }

    /** First run only: weak GPUs start on LOW (resolution scale, fewer effects, smaller bakes). */
    private fun pickDefaultQuality(renderer: String) {
        val r = renderer.lowercase()
        settings.quality = defaultQuality(renderer)
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
            if (lockKind == LOCK_ASTEROID && !c.field.active[lockIndex]) lockKind = LOCK_NONE
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
                "map" -> if (state == STATE_FLIGHT) openMap()
                "lock" -> if (state == STATE_FLIGHT) toggleLock()
                "action" -> if (state == STATE_FLIGHT) doAction()
                "teleport-low" -> if (state == STATE_FLIGHT) debugTeleportLow()
                "teleport-surface" -> if (state == STATE_FLIGHT) {
                    debugTeleportLow()
                    debugDrop(60.0)
                }
                "lock-outpost" -> if (state == STATE_FLIGHT) lockFromMap(LOCK_STATION, 1)
                "dock" -> if (state == STATE_FLIGHT) requestDocking(0)
                "docked" -> if (state == STATE_FLIGHT) {
                    requestDocking(0)
                    stationPort(0)
                    ship.pos.set(dockPos.x + dockDir.x * 20.0, dockPos.y + dockDir.y * 20.0, dockPos.z + dockDir.z * 20.0)
                    dockPhase = 1
                }
                "behind-station" -> if (state == STATE_FLIGHT) debugBehindStation(1500.0, 0f)
                "ram-station" -> if (state == STATE_FLIGHT) debugBehindStation(400.0, 120f)
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
        if (overlay == OVERLAY_DOCKED &&
            (gamepad.consumePress(GamepadState.A) || gamepad.consumePress(GamepadState.START))
        ) launchFromStation()
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
            overlay == OVERLAY_MAP -> closeMap()
            overlay == OVERLAY_DOCKED -> openPause()
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
            overlay == OVERLAY_MAP -> map.down(id, x, y)
            overlay == OVERLAY_DOCKED -> dockedScreen.group.down(id, x, y)
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
        if (dockedScreen.group.move(id, x, y)) return
        if (overlay == OVERLAY_MAP) {
            map.move(id, x, y)
            return
        }
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
        dockedScreen.group.up(id, x, y, cancelled)
        if (overlay == OVERLAY_MAP) map.up(id, x, y, cancelled)
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
            overlay = if (docked) OVERLAY_DOCKED else OVERLAY_NONE
            screenChanged()
        }
    }

    private fun openMap() {
        overlay = OVERLAY_MAP
        touch.releaseAll()
        controls.clearAxes()
        screenChanged()
        map.open(ship.pos, lockKind, lockIndex)
    }

    override fun closeMap() {
        if (overlay == OVERLAY_MAP) {
            overlay = OVERLAY_NONE
            screenChanged()
        }
    }

    override fun lockFromMap(kind: Int, index: Int) {
        lockKind = kind
        lockIndex = index
        haptic(Haptics.TICK)
    }

    /** Drops input that belonged to the previous screen (held widgets, queued pad presses). */
    private fun screenChanged() {
        settingsScreen.group.releaseAll()
        pause.group.releaseAll()
        dockedScreen.group.releaseAll()
        map.releaseAll()
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
        ship.pos.set(system.home)
        dustOrigin.set(system.home)
        touch.releaseAll()
        touch.throttle = 0.3f
        controls.clearAxes()
        controls.throttle = 0.3f
        ship.vel.set(0f, 0f, -0.3f * spec.maxSpeed)
        chase.mode = 0
        chase.snapTo(ship)
        flightTime = 0f
        launchTime = -100f
        hints = 1f
        menu.dragHint = 0f
        lockKind = LOCK_NONE
        padZeroHold = false
        landing = false
        landed = false
        docking = false
        docked = false
        liftTime = 0f
        gear = 0f
        message = null
        screenChanged()
    }

    private fun enterMenu() {
        state = STATE_MENU
        overlay = OVERLAY_NONE
        touch.releaseAll()
        controls.clearAxes()
        orbit.idle = 10f
        lockKind = LOCK_NONE
        docked = false
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
        menuShip.pos.y = system.home.y + AsteroidField.SHOWCASE_Y + sin(time * 0.7f) * 0.35
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
        sceneFrame.dustOrigin.set(cam.pos)
        sceneFrame.ship = menuShip
        updateLighting(menuShip.pos)
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
            if (touch.consumeMap() || gamepad.consumePress(GamepadState.SELECT)) openMap()
            if (touch.consumeAction() || gamepad.consumePress(GamepadState.DPAD_DOWN)) doAction()

            system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
            updateGear(dt)
            if (landed) holdLanded(dt)
            // landing mode: the vertical thrusters give a gentle, safe descent rate
            if (landing && !landed) controls.strafeY *= LANDING_VERTICAL
            if (liftTime > 0f) {
                liftTime -= dt
                controls.strafeY = maxOf(controls.strafeY, 1f)
            }
            if (docking) {
                updateDocking(dt)
                if (docking && (abs(touch.pitch) > 0.5f || abs(touch.roll) > 0.5f || abs(touch.yaw) > 0.5f)) {
                    docking = false
                    showMessage("DOCKING CANCELLED")
                }
            }
            val limit = if (landing) minOf(here.speedLimit, LANDING_SPEED) else here.speedLimit
            val steps = clamp(ceil(dt / MAX_STEP).toInt().toFloat(), 1f, 8f).toInt()
            val h = dt / steps
            for (i in 0 until steps) {
                if (landed || docking || overlay != OVERLAY_NONE) break
                if (flight.step(ship, controls, h, limit)) {
                    haptic(Haptics.HEAVY)
                    chase.addShake(0.8f)
                }
                controls.boost = false
                // collide every substep so fast ships can't tunnel through small rocks
                collide(h)
                collideBodies(h)
                collideStation()
            }
            // a press while landed, docking or as an overlay opens is dropped, not latched
            controls.boost = false
            system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
            // station hulls join the proximity warning. Not while auto-docking (it flies its own clear
            // route, and collide() did not run to refresh the value) or landed, nor just after a launch,
            // which starts 40 m off the slot
            // (an overlay opened this frame, e.g. on docking, skipped collide() too)
            if (docking || docked || landed || overlay != OVERLAY_NONE) {
                proximity = Float.MAX_VALUE
            } else if (here.nearestStation >= 0 && flightTime - launchTime > LAUNCH_GRACE) {
                proximity = minOf(proximity, stationCollision.clearance(ship, system.stations[here.nearestStation]))
            }
            if (landing && (here.altitude > StarSystem.LOW_ALTITUDE * 1.2 || !landable(here.nearestBody))) {
                landing = false
                if (!landed) showMessage("LANDING CANCELLED")
            }
            touch.actionLabel = actionLabel()
            if (message != null && time - messageTime > 3f) message = null
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
        // speed lines move at a capped "visual" speed so they stay readable at Mm/s
        val sp = ship.speed
        val visScale = if (sp > DUST_VISUAL_SPEED) DUST_VISUAL_SPEED / sp else 1f
        tmp.set(ship.vel).scale(visScale)
        if (!paused) dustOrigin.addScaled(tmp, dt.toDouble())
        sceneFrame.dustOrigin.set(dustOrigin)
        // longer streaks the faster you go (logarithmic above the visual cap)
        val streak = if (sp > DUST_VISUAL_SPEED) 1f + 0.6f * kotlin.math.log10(sp / DUST_VISUAL_SPEED) else 1f
        sceneFrame.dustVelocity.set(tmp).scale(streak)
        sceneFrame.dustIntensity = 0.6f
        // speed lines: white in open space, blue in a gravity well, red for danger or heat
        val zone = here.zone
        val star = here.nearestBody == 0
        val danger = proximity < 80f || (star && here.altitude < system.bodies[0].radius)
        val well = zone == StarSystem.Zone.GRAVITY_WELL || zone == StarSystem.Zone.LOW_ALTITUDE
        val dc = sceneFrame.dustColor
        val k = expApproach(4f, dt)
        val tr = if (danger) 1.0f else if (well) 0.40f else 0.66f
        val tg = if (danger) 0.30f else if (well) 0.62f else 0.72f
        val tb = if (danger) 0.26f else if (well) 1.0f else 0.80f
        dc.x += (tr - dc.x) * k
        dc.y += (tg - dc.y) * k
        dc.z += (tb - dc.z) * k
        sceneFrame.drawField = nearField(cam.pos)
        updateLighting(ship.pos)
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
        if (!nearField(ship.pos)) {
            proximity = Float.MAX_VALUE
            return
        }
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
                val impact = flight.collideSphere(ship, ox + field.px[i], oy + field.py[i], oz + field.pz[i], r.toDouble())
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
            OVERLAY_MAP -> map.draw(ui, 1f, ship.pos, ship.rot, time)
            OVERLAY_DOCKED -> dockedScreen.draw(ui, frame, 1f, system.stations[dockStation].name)
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
        drawTargets(ui, a)
        drawLandingHud(ui, a)

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
        ui.textCentered(here.zone.label, cx, frame.st + 30f * u, 10.5f * u, withAlpha(Theme.TEXT, a * 0.85f), tracking = 0.3f, shadow = 0.6f)
        var warnY = frame.st + 52f * u
        if (here.nearestBody == 0 && here.altitude < system.bodies[0].radius * 0.5) {
            val blink = if ((time * 3f).toInt() % 2 == 0) 1f else 0.45f
            ui.textCentered("HEAT", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
            warnY += 20f * u
        }
        if (!ship.flightAssist) {
            val blink = if ((time * 2f).toInt() % 2 == 0) 1f else 0.55f
            ui.textCentered("FLIGHT ASSIST OFF", cx, warnY, 11f * u, withAlpha(Theme.WARN, a * blink), tracking = 0.2f, shadow = 0.7f)
            warnY += 20f * u
        }
        val msg = message
        if (msg != null) {
            ui.textCentered(msg, cx, frame.h * 0.30f, 13f * u, withAlpha(Theme.LOCK, a), tracking = 0.25f, shadow = 0.8f)
        }
        if (proximity < 60f) {
            val blink = if ((time * 4f).toInt() % 2 == 0) 1f else 0.4f
            ui.textCentered("PROXIMITY", cx, warnY, 13f * u, withAlpha(Theme.DANGER, a * blink), tracking = 0.3f, weight = 0.03f, shadow = 0.7f)
        }

        touch.draw(
            ui, a, ship.forwardSpeed / spec.maxSpeed, ship.flightAssist,
            ship.capacitor >= spec.boostCost, ship.boosting, ship.capacitor, hints, lockKind != LOCK_NONE,
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

    // ================================================================== landing (L)

    /** Solid bodies only: no landing on the star or the gas giant. */
    private fun landable(i: Int): Boolean {
        if (i < 0) return false
        val t = system.bodies[i].type
        return t != StarSystem.BodyType.STAR && t != StarSystem.BodyType.GAS
    }

    private fun actionLabel(): String = when {
        landed -> "TAKE OFF"
        landing || docking -> "CANCEL"
        here.zone == StarSystem.Zone.STATION_ZONE -> "DOCK"
        here.zone == StarSystem.Zone.LOW_ALTITUDE && landable(here.nearestBody) -> "LAND"
        else -> ""
    }

    private fun doAction() {
        when {
            landed -> takeOff()
            docking -> {
                docking = false
                showMessage("DOCKING CANCELLED")
            }
            landing -> {
                landing = false
                showMessage("LANDING CANCELLED")
            }
            here.zone == StarSystem.Zone.STATION_ZONE -> requestDocking(here.nearestStation)
            here.zone == StarSystem.Zone.LOW_ALTITUDE && landable(here.nearestBody) -> {
                landing = true
                showMessage("LANDING MODE  ·  GEAR DOWN")
            }
            else -> haptic(Haptics.BUMP)
        }
    }

    private fun takeOff() {
        if (!landed) return
        landed = false
        // leave landing mode: otherwise the touchdown test re-lands the ship on the next substep
        landing = false
        liftTime = LIFT_TIME
        val b = system.bodies[landedBody]
        upV.set(
            (ship.pos.x - b.pos.x).toFloat(), (ship.pos.y - b.pos.y).toFloat(), (ship.pos.z - b.pos.z).toFloat(),
        ).normalize()
        ship.vel.set(upV).scale(25f)
        showMessage("LIFT OFF")
        haptic(Haptics.TICK)
    }

    /** Gear follows the landing mode: legs extend over 1.6 s, then the doors close over 0.6 s. */
    private fun updateGear(dt: Float) {
        val target = if (landing || landed) 1f else 0f
        gear = com.starwake.core.math.approach(gear, target, dt / GEAR_TIME)
    }

    /** While landed: stay on the surface, level out, and wait for a take-off input. */
    private fun holdLanded(dt: Float) {
        val b = system.bodies[landedBody]
        val dx = ship.pos.x - b.pos.x
        val dy = ship.pos.y - b.pos.y
        val dz = ship.pos.z - b.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
        val rest = b.radius + spec.collisionRadius
        ship.pos.set(b.pos.x + dx / d * rest, b.pos.y + dy / d * rest, b.pos.z + dz / d * rest)
        ship.vel.zero()
        ship.angVel.zero()
        ship.speed = 0f
        ship.forwardSpeed = 0f
        // settle level: keep the heading, align the ship's up with the surface normal
        ship.rot.rotate(0f, 0f, -1f, fwdV)
        fwdV.addScaled(upV, -fwdV.dot(upV))
        if (fwdV.lengthSq() > 1e-6f) {
            fwdV.normalize()
            landRot.lookRotation(fwdV.x, fwdV.y, fwdV.z, upV.x, upV.y, upV.z)
            ship.rot.slerp(ship.rot, landRot, expApproach(3f, dt))
        }
        if (touch.vertical > 0f || touch.throttle > 0.05f || controls.strafeY > 0.2f) takeOff()
    }

    private fun showMessage(m: String) {
        message = m
        messageTime = time
    }

    /** Harness hook: drop the ship 20 km above PLANET II's day side, nose level, in landing range. */
    private fun debugTeleportLow() {
        val b = system.bodies.first { it.name == "PLANET II" }
        val d = sqrt(b.pos.x * b.pos.x + b.pos.y * b.pos.y + b.pos.z * b.pos.z)
        val ux = -b.pos.x / d
        val uy = -b.pos.y / d
        val uz = -b.pos.z / d
        val alt = b.radius + 20_000.0
        ship.pos.set(b.pos.x + ux * alt, b.pos.y + uy * alt, b.pos.z + uz * alt)
        upV.set(ux.toFloat(), uy.toFloat(), uz.toFloat())
        // heading: any direction along the surface
        fwdV.set(0f, 1f, 0f).addScaled(upV, -upV.y).normalize()
        ship.rot.lookRotation(fwdV.x - upV.x * 0.25f, fwdV.y - upV.y * 0.25f, fwdV.z - upV.z * 0.25f, upV.x, upV.y, upV.z)
        ship.vel.zero()
        touch.throttle = 0.2f
        chase.snapTo(ship)
        dustOrigin.set(ship.pos)
    }

    /** Harness hook: puts the ship [dist] m behind HOME STATION (opposite its slot), nose on the hub, flying at it at [speed]. */
    private fun debugBehindStation(dist: Double, speed: Float) {
        stationPort(0)
        val st = system.stations[0]
        ship.pos.set(st.pos.x - dockDir.x * dist, st.pos.y - dockDir.y * dist, st.pos.z - dockDir.z * dist)
        st.rot.rotate(0f, 1f, 0f, upV)
        ship.rot.lookRotation(dockDir.x, dockDir.y, dockDir.z, upV.x, upV.y, upV.z)
        ship.vel.set(dockDir).scale(speed)
        ship.angVel.zero()
        touch.throttle = if (speed > 0f) 0.6f else 0f
        landed = false
        landing = false
        liftTime = 0f
        gear = 0f
        docking = false
        chase.snapTo(ship)
        dustOrigin.set(ship.pos)
    }

    /** Harness hook: drop straight down to [alt] metres above the nearest body, level, at rest. */
    private fun debugDrop(alt: Double) {
        system.locate(ship.pos, ship.vel, spec.maxSpeed, here)
        val b = system.bodies[here.nearestBody]
        val dx = ship.pos.x - b.pos.x
        val dy = ship.pos.y - b.pos.y
        val dz = ship.pos.z - b.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val r = b.radius + spec.collisionRadius + alt
        ship.pos.set(b.pos.x + dx / d * r, b.pos.y + dy / d * r, b.pos.z + dz / d * r)
        upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
        ship.rot.rotate(0f, 0f, -1f, fwdV)
        fwdV.addScaled(upV, -fwdV.dot(upV)).normalize()
        ship.rot.lookRotation(fwdV.x, fwdV.y, fwdV.z, upV.x, upV.y, upV.z)
        ship.vel.zero()
        ship.angVel.zero()
        touch.throttle = 0f
        chase.snapTo(ship)
        dustOrigin.set(ship.pos)
    }

    /**
     * Pitch ladder (±10° steps relative to the local horizon) with PITCH and ALT boxes. Shown only
     * in landing mode, like the Roblox version's L landing.
     */
    private fun drawLandingHud(ui: UiRenderer, a: Float) {
        if (!(landing || landed)) {
            if (gear > 0f) drawGear(ui, a)
            return
        }
        val i = if (landed) landedBody else here.nearestBody
        if (i < 0) return
        val b = system.bodies[i]
        val u = frame.u
        val cam = sceneFrame.camera
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
            if (kotlin.math.abs(deg - pitchDeg) <= 32f) {
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
                val alt = maxOf(0.0, d - b.radius - spec.collisionRadius)
                val unit = MapScreen.distanceText(buf, alt)
                val tw = ui.textWidth(buf, 13f * u, face = FontData.MONO)
                ui.textCentered(buf, x + bw * 0.5f - 6f * u, cy, 13f * u, col, face = FontData.MONO)
                ui.text(unit, x + bw * 0.5f - 6f * u + tw * 0.5f + 3f * u, cy + 4f * u, 8f * u, col)
            }
        }
        // vertical speed under the ALT box, total speed under the PITCH box (touchdown limits)
        val vs = ship.vel.dot(upV)
        buf.clear().append(vs, 1).append(" M/S")
        ui.text(buf, rx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (vs <= -TOUCHDOWN_SPEED) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
        buf.clear().append(ship.speed, 0).append(" M/S")
        ui.text(buf, lx + bw * 0.5f, cy + bh * 0.5f + 14f * u, 9.5f * u, withAlpha(if (ship.speed >= TOUCHDOWN_MAX_SPEED) Theme.DANGER else Theme.OK, a), UiRenderer.CENTER, shadow = 0.6f, face = FontData.MONO)
        drawGear(ui, a)
    }

    private fun drawGear(ui: UiRenderer, a: Float) {
        val u = frame.u
        val label = when {
            gear >= 1f -> if (landed) "LANDED" else "GEAR DOWN"
            gear * GEAR_TIME < GEAR_LEGS -> "GEAR · LEGS"
            else -> "GEAR · DOORS"
        }
        val x = frame.w * 0.5f
        val y = frame.h - frame.sb - 30f * u
        val col = withAlpha(if (gear >= 1f) Theme.OK else Theme.WARN, a)
        ui.textCentered(label, x, y - 12f * u, 10f * u, col, tracking = 0.25f, shadow = 0.6f)
        ui.rect(x - 50f * u, y, 100f * u, 4f * u, withAlpha(Theme.PANEL, a), 2f * u)
        ui.rect(x - 50f * u, y, 100f * u * gear, 4f * u, col, 2f * u)
    }

    // ================================================================== docking (K)

    /**
     * Docking port of station [i] in world space: [dockPos] = slot entrance, [dockDir] = outward
     * direction a ship approaches along.
     */
    private fun stationPort(i: Int) {
        val st = system.stations[i]
        stationPortLocal(st.model, portLocal, portDirLocal)
        st.rot.rotate(portLocal, tmp)
        dockPos.set(st.pos.x + tmp.x, st.pos.y + tmp.y, st.pos.z + tmp.z)
        st.rot.rotate(portDirLocal, dockDir)
        dockDir.normalize()
    }

    private fun stationPortLocal(model: Int, outPos: Vec3, outDir: Vec3) {
        com.starwake.core.content.StationMesh.dockPort(model, outPos, outDir)
    }

    /** How far inside the slot entrance the ship parks (half the tunnel depth). */
    private fun slotParkDepth(i: Int): Double {
        com.starwake.core.content.StationMesh.slotSize(system.stations[i].model, tmp2)
        return tmp2.z * 0.5
    }

    private fun requestDocking(i: Int) {
        if (i < 0) return
        docking = true
        dockStation = i
        dockPhase = 0
        dockBoxedTime = 0f
        landing = false
        showMessage("DOCKING GRANTED  ·  AUTO-DOCK ENGAGED")
        haptic(Haptics.TICK)
    }

    /** Auto-dock: around the hull if needed, to a point in front of the slot, line up, then slide in. */
    private fun updateDocking(dt: Float) {
        stationPort(dockStation)
        // phase 0: detour around the hull while the router asks for it, then hold in front of the
        // slot; phase 1: glide in and park inside the tunnel
        val detour = dockPhase == 0 && stationCollision.dockWaypoint(system.stations[dockStation], ship.pos, dockWaypoint)
        // the router found no way out (it holds the ship still): give up rather than wait for ever
        dockBoxedTime = if (detour && stationCollision.boxedIn) dockBoxedTime + dt else 0f
        if (dockBoxedTime > DOCK_BOXED_GRACE) {
            docking = false
            ship.vel.zero()
            showMessage("DOCKING ABORTED  ·  MOVE CLEAR OF THE HULL")
            return
        }
        val standOff = if (dockPhase == 0) DOCK_STANDOFF else -slotParkDepth(dockStation)
        val tx = if (detour) dockWaypoint.x else dockPos.x + dockDir.x * standOff
        val ty = if (detour) dockWaypoint.y else dockPos.y + dockDir.y * standOff
        val tz = if (detour) dockWaypoint.z else dockPos.z + dockDir.z * standOff
        val dx = tx - ship.pos.x
        val dy = ty - ship.pos.y
        val dz = tz - ship.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        // nose along the route while going round the hull, otherwise into the slot (against the
        // outward direction); roll matched to the station's up
        system.stations[dockStation].rot.rotate(0f, 1f, 0f, upV)
        if (detour && d > 5.0) landRot.lookRotation((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat(), upV.x, upV.y, upV.z)
        else landRot.lookRotation(-dockDir.x, -dockDir.y, -dockDir.z, upV.x, upV.y, upV.z)
        val turn = if (dockPhase == 0) 1.5f else 3f
        ship.rot.slerp(ship.rot, landRot, expApproach(turn, dt))
        val speed = if (detour) DOCK_APPROACH_SPEED else if (dockPhase == 0) minOf(DOCK_APPROACH_SPEED, (d * 0.6).toFloat() + 5f) else minOf(30f, (d * 0.8).toFloat() + 8f)
        if (d > 0.5) {
            val k = minOf(1.0, speed * dt / d)
            ship.vel.set((dx / d * speed).toFloat(), (dy / d * speed).toFloat(), (dz / d * speed).toFloat())
            ship.pos.set(ship.pos.x + dx * k, ship.pos.y + dy * k, ship.pos.z + dz * k)
        }
        ship.speed = speed
        ship.forwardSpeed = speed
        ship.angVel.zero()
        ship.engineOutput += (0.3f - ship.engineOutput) * expApproach(4f, dt)
        if (dockPhase == 0 && !detour && d < 15.0) {
            dockPhase = 1
            showMessage("ENTERING SLOT")
        } else if (dockPhase == 1 && d < 2.0) {
            docking = false
            docked = true
            ship.vel.zero()
            ship.speed = 0f
            ship.forwardSpeed = 0f
            touch.throttle = 0f
            controls.clearAxes()
            overlay = OVERLAY_DOCKED
            touch.releaseAll()
            screenChanged()
            haptic(Haptics.BUMP)
            message = null
        }
    }

    override fun launchFromStation() {
        if (overlay != OVERLAY_DOCKED) return
        stationPort(dockStation)
        // out of the slot, nose outward, at a gentle push
        ship.pos.set(dockPos.x + dockDir.x * 40.0, dockPos.y + dockDir.y * 40.0, dockPos.z + dockDir.z * 40.0)
        system.stations[dockStation].rot.rotate(0f, 1f, 0f, upV)
        ship.rot.lookRotation(dockDir.x, dockDir.y, dockDir.z, upV.x, upV.y, upV.z)
        ship.vel.set(dockDir).scale(60f)
        touch.throttle = 0.3f
        controls.clearAxes()
        chase.snapTo(ship)
        docked = false
        launchTime = flightTime
        overlay = OVERLAY_NONE
        screenChanged()
        showMessage("LAUNCHED  ·  " + system.stations[dockStation].name)
    }

    // ================================================================== targets, lock and markers

    /**
     * Finds the target closest to the nose: asteroids (when near the field), stations and bodies.
     * Writes [aimKind]/[aimIndex]; the score is the angle off the nose minus the target's
     * angular radius, so big bodies are easy to pick.
     */
    private fun findAimed(maxAngleCos: Float, maxAngleSin: Float) {
        aimKind = LOCK_NONE
        aimIndex = -1
        ship.rot.rotate(0f, 0f, -1f, tmp)
        var bestScore = Float.MAX_VALUE
        val field = content?.field
        if (field != null && nearField(ship.pos)) {
            for (i in 0 until field.total) {
                if (!field.active[i]) continue
                val dx = field.origin.x + field.px[i] - ship.pos.x
                val dy = field.origin.y + field.py[i] - ship.pos.y
                val dz = field.origin.z + field.pz[i] - ship.pos.z
                val score = aimScore(dx, dy, dz, field.radius[i].toDouble(), maxAngleCos, maxAngleSin, LOCK_RANGE.toDouble())
                if (score < bestScore) {
                    bestScore = score; aimKind = LOCK_ASTEROID; aimIndex = i
                }
            }
        }
        for (i in system.stations.indices) {
            val st = system.stations[i]
            val score = aimScore(st.pos.x - ship.pos.x, st.pos.y - ship.pos.y, st.pos.z - ship.pos.z, st.radius, maxAngleCos, maxAngleSin, Double.MAX_VALUE)
            if (score < bestScore) {
                bestScore = score; aimKind = LOCK_STATION; aimIndex = i
            }
        }
        for (i in system.bodies.indices) {
            val b = system.bodies[i]
            val score = aimScore(b.pos.x - ship.pos.x, b.pos.y - ship.pos.y, b.pos.z - ship.pos.z, b.radius, maxAngleCos, maxAngleSin, Double.MAX_VALUE)
            if (score < bestScore) {
                bestScore = score; aimKind = LOCK_BODY; aimIndex = i
            }
        }
    }

    /** Angle (radians) between the nose ([tmp]) and the target's edge; MAX_VALUE if not eligible. */
    private fun aimScore(
        dx: Double, dy: Double, dz: Double, radius: Double, maxAngleCos: Float, maxAngleSin: Float, range: Double,
    ): Float {
        if (range < Double.MAX_VALUE) {
            // cheap box reject before the square root (asteroids: most are out of range)
            val reach = range + radius
            if (abs(dx) > reach || abs(dy) > reach || abs(dz) > reach) return Float.MAX_VALUE
        }
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1.0 || d - radius > range) return Float.MAX_VALUE
        val cos = ((dx * tmp.x + dy * tmp.y + dz * tmp.z) / d).toFloat()
        // exact trig-free test for "angle - angular radius > cone": cos(angle) < cos(cone + angRadius)
        // (valid because cone + angRadius < 180°)
        val sr = minOf(1.0, radius / d).toFloat()
        if (cos < maxAngleCos * sqrt(1f - sr * sr) - maxAngleSin * sr) return Float.MAX_VALUE
        val angle = kotlin.math.acos(clamp(cos, -1f, 1f))
        val angRadius = kotlin.math.asin(minOf(1.0, radius / d)).toFloat()
        val off = maxOf(0f, angle - angRadius)
        if (kotlin.math.cos(off) < maxAngleCos) return Float.MAX_VALUE
        // tiny distance tie-breaker so the nearer of two overlapping targets wins
        return off + (d * 1e-12).toFloat()
    }

    private fun toggleLock() {
        if (lockKind != LOCK_NONE) {
            lockKind = LOCK_NONE
            haptic(Haptics.TICK)
            return
        }
        findAimed(LOCK_CONE_COS, LOCK_CONE_SIN)
        lockKind = aimKind
        lockIndex = aimIndex
        haptic(if (lockKind != LOCK_NONE) Haptics.TICK else Haptics.BUMP)
    }

    private fun validateLock() {
        if (lockKind == LOCK_ASTEROID) {
            val field = content?.field ?: return
            if (!field.active[lockIndex] || targetDistance(LOCK_ASTEROID, lockIndex) > LOCK_RANGE * 1.5f) lockKind = LOCK_NONE
        }
    }

    /** Target centre relative to [origin] into [out] (camera-relative when origin = camera). */
    private fun targetRel(kind: Int, i: Int, origin: com.starwake.core.math.DVec3, out: Vec3) {
        when (kind) {
            LOCK_ASTEROID -> {
                val f = content!!.field
                out.set(
                    (f.origin.x + f.px[i] - origin.x).toFloat(),
                    (f.origin.y + f.py[i] - origin.y).toFloat(),
                    (f.origin.z + f.pz[i] - origin.z).toFloat(),
                )
            }
            LOCK_STATION -> system.stations[i].pos.relativeTo(origin, out)
            else -> system.bodies[i].pos.relativeTo(origin, out)
        }
    }

    private fun targetRadius(kind: Int, i: Int): Double = when (kind) {
        LOCK_ASTEROID -> content!!.field.collisionRadius[i].toDouble()
        LOCK_STATION -> system.stations[i].radius
        else -> system.bodies[i].radius
    }

    /** Distance from the ship to the target's surface, in metres (double precision). */
    private fun targetDistance(kind: Int, i: Int): Double {
        val dx: Double
        val dy: Double
        val dz: Double
        when (kind) {
            LOCK_ASTEROID -> {
                val f = content!!.field
                dx = f.origin.x + f.px[i] - ship.pos.x; dy = f.origin.y + f.py[i] - ship.pos.y; dz = f.origin.z + f.pz[i] - ship.pos.z
            }
            LOCK_STATION -> {
                val p = system.stations[i].pos
                dx = p.x - ship.pos.x; dy = p.y - ship.pos.y; dz = p.z - ship.pos.z
            }
            else -> {
                val p = system.bodies[i].pos
                dx = p.x - ship.pos.x; dy = p.y - ship.pos.y; dz = p.z - ship.pos.z
            }
        }
        return maxOf(0.0, sqrt(dx * dx + dy * dy + dz * dz) - targetRadius(kind, i))
    }

    private fun targetName(kind: Int, i: Int): String = when (kind) {
        LOCK_STATION -> system.stations[i].name
        LOCK_BODY -> system.bodies[i].name
        else -> "ASTEROID"
    }

    /**
     * Uppercase markers on stations and bodies; amber brackets on the locked target. Distance is
     * shown only for the locked target, or the one the nose is aimed at when nothing is locked.
     */
    private fun drawTargets(ui: UiRenderer, a: Float) {
        // toggleLock runs its own wider search, so nothing needs the aim while a target is locked
        if (lockKind == LOCK_NONE) findAimed(AIM_CONE_COS, AIM_CONE_SIN) else { aimKind = LOCK_NONE; aimIndex = -1 }
        val showKind = if (lockKind != LOCK_NONE) lockKind else aimKind
        val showIndex = if (lockKind != LOCK_NONE) lockIndex else aimIndex
        // labels claim screen space in priority order (locked/aimed, stations, bodies); a label
        // that would overlap one already placed is left out (its marker ring still shows)
        labelCount = 0
        if (showKind != LOCK_NONE) drawMarker(ui, a, showKind, showIndex, showKind, showIndex)
        for (i in system.stations.indices) {
            if (showKind != LOCK_STATION || i != showIndex) drawMarker(ui, a, LOCK_STATION, i, showKind, showIndex)
        }
        for (i in system.bodies.indices) {
            if (showKind != LOCK_BODY || i != showIndex) drawMarker(ui, a, LOCK_BODY, i, showKind, showIndex)
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

    private fun drawMarker(ui: UiRenderer, a: Float, kind: Int, i: Int, showKind: Int, showIndex: Int) {
        val cam = sceneFrame.camera
        targetRel(kind, i, cam.pos, tmp)
        val locked = kind == lockKind && i == lockIndex
        val onScreen = cam.project(tmp.x, tmp.y, tmp.z, screenPt)
        val u = frame.u
        val x = screenPt.x
        val y = screenPt.y
        val inside = onScreen && x >= 0f && y >= 0f && x <= frame.w && y <= frame.h
        if (!inside) {
            // the locked target always shows where it is: an arrow on the screen edge
            if (locked) drawLockArrow(ui, a, kind, i)
            return
        }
        val shown = kind == showKind && i == showIndex
        // hidden behind a planet: no marker (the lock brackets still show where it is)
        if (!locked && hiddenBehindBody(kind, i)) return
        val dist = targetDistance(kind, i)
        val pxPerUnit = frame.h * 0.5f / cam.tanHalfY / screenPt.z
        val screenR = (targetRadius(kind, i) * pxPerUnit).toFloat()
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
            labelAndDistance(ui, a, kind, i, x, ly, col, true, dist)
        } else if (kind != LOCK_ASTEROID) {
            val col = withAlpha(if (kind == LOCK_STATION) Theme.ACCENT else Theme.TEXT, a * 0.85f)
            val r = maxOf(screenR, 5f * u)
            ui.circle(x, y, r, col, 1.2f * u)
            labelAndDistance(ui, a, kind, i, x, y + r + 13f * u, col, shown, dist)
        }
    }

    /**
     * Amber chevron on an ellipse near the screen edge, pointing towards the locked target ([tmp]
     * = target relative to the camera), with its name and distance just inside it.
     */
    private fun drawLockArrow(ui: UiRenderer, a: Float, kind: Int, i: Int) {
        val cam = sceneFrame.camera
        val u = frame.u
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
        labelAndDistance(ui, a, kind, i, lx, ly, col, true, targetDistance(kind, i))
    }

    private fun labelAndDistance(ui: UiRenderer, a: Float, kind: Int, i: Int, x: Float, y: Float, col: Int, withDistance: Boolean, dist: Double) {
        val u = frame.u
        val name = targetName(kind, i)
        // measure the whole label (name, and the distance line with its unit to the right)
        val nameHalf = ui.textWidth(name, 9f * u, 0.18f) * 0.5f
        var left = nameHalf
        var right = nameHalf
        var unit = ""
        var numW = 0f
        if (withDistance) {
            unit = formatDistance(buf.clear(), dist.toFloat())
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
    private fun hiddenBehindBody(kind: Int, i: Int): Boolean {
        val eye = sceneFrame.camera.pos
        val p = when (kind) {
            LOCK_STATION -> system.stations[i].pos
            LOCK_BODY -> system.bodies[i].pos
            else -> return false
        }
        val dx = p.x - eye.x
        val dy = p.y - eye.y
        val dz = p.z - eye.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.0) return false
        for (j in system.bodies.indices) {
            if (kind == LOCK_BODY && j == i) continue
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

    /** True when [p] is close enough to the asteroid field to draw and collide with it. */
    private fun nearField(p: com.starwake.core.math.DVec3): Boolean {
        val o = system.fieldOrigin
        val dx = p.x - o.x
        val dy = p.y - o.y
        val dz = p.z - o.z
        return dx * dx + dy * dy + dz * dz < FIELD_RANGE * FIELD_RANGE
    }

    /** Station hulls are solid (coarse proxies, see StationCollision). */
    private fun collideStation() {
        val i = here.nearestStation
        if (i < 0) return
        val impact = stationCollision.collide(ship, system.stations[i])
        if (impact > 2f && bumpCooldown <= 0f) {
            bumpCooldown = 0.25f
            haptic(if (impact > 40f) Haptics.HEAVY else Haptics.BUMP)
            chase.addShake(clamp(impact / 50f, 0.2f, 1.2f))
        }
    }

    /** Planet/star surfaces are solid. */
    private fun collideBodies(dt: Float) {
        val i = here.nearestBody
        if (i < 0) return
        val b = system.bodies[i]
        if (here.altitude > spec.collisionRadius * 4 + ship.speed * dt * 2) return
        if (landing && landable(i)) {
            // touchdown: slow, gear down and roughly level -> park on the surface
            val dx = ship.pos.x - b.pos.x
            val dy = ship.pos.y - b.pos.y
            val dz = ship.pos.z - b.pos.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d - b.radius < spec.collisionRadius + 0.5) {
                upV.set((dx / d).toFloat(), (dy / d).toFloat(), (dz / d).toFloat())
                val radial = ship.vel.dot(upV)
                ship.rot.rotate(0f, 1f, 0f, tmp)
                val level = tmp.dot(upV)
                if (radial < 0.5f) {
                    if (gear >= 1f && radial > -TOUCHDOWN_SPEED && level > TOUCHDOWN_LEVEL && ship.speed < TOUCHDOWN_MAX_SPEED) {
                        landed = true
                        landedBody = i
                        ship.vel.zero()
                        ship.angVel.zero()
                        touch.throttle = 0f
                        // a thumb still on the throttle lever must not lift off by accident
                        touch.releaseAll()
                        controls.clearAxes()
                        haptic(Haptics.BUMP)
                        showMessage("TOUCHDOWN  ·  " + b.name)
                        return
                    }
                    showMessage(
                        when {
                            gear < 1f -> "GEAR NOT DOWN"
                            radial <= -TOUCHDOWN_SPEED -> "HARD LANDING"
                            ship.speed >= TOUCHDOWN_MAX_SPEED -> "TOO FAST  ·  TOUCH DOWN BELOW 40 M/S"
                            else -> "LEVEL OUT TO TOUCH DOWN"
                        },
                    )
                }
            }
        }
        val impact = flight.collideSphere(ship, b.pos.x, b.pos.y, b.pos.z, b.radius)
        if (impact > 2f && bumpCooldown <= 0f) {
            bumpCooldown = 0.25f
            haptic(if (impact > 40f) Haptics.HEAVY else Haptics.BUMP)
            chase.addShake(clamp(impact / 50f, 0.2f, 1.2f))
        }
    }

    /** Sun direction/visibility (eclipses) and the fill light bounced off the nearest planet. */
    private fun updateLighting(at: com.starwake.core.math.DVec3) {
        val f = sceneFrame
        f.sunVisibility = system.sunlight(at, f.sunDir)
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 1 until system.bodies.size) {
            val b = system.bodies[i]
            val dx = b.pos.x - at.x
            val dy = b.pos.y - at.y
            val dz = b.pos.z - at.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val score = d / b.radius
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        if (best < 0) return
        val b = system.bodies[best]
        b.pos.relativeTo(at, f.fillDir)
        f.fillDir.normalize()
        // bounce light falls off with apparent size; the lit fraction depends on the sun angle
        val size = (1.0 / (bestScore * bestScore)).coerceAtMost(1.0).toFloat()
        val lit = 0.5f + 0.5f * -(f.fillDir.dot(f.sunDir))
        val k = size * lit * 1.4f
        f.fillColor[0] = 0.10f * k; f.fillColor[1] = 0.085f * k; f.fillColor[2] = 0.07f * k
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
        /**
         * First-run quality from the GL_RENDERER string: 0 LOW for weak GPUs, 1 MEDIUM, 2 HIGH.
         * Model numbers are compared as numbers (a prefix match would put a Mali-G610 with the
         * old G61).
         */
        internal fun defaultQuality(renderer: String): Int {
            val r = renderer.lowercase()
            val adreno = Regex("adreno[^0-9]*([0-9]{3})(?![0-9])").find(r)?.groupValues?.get(1)?.toIntOrNull()
            // Mali/Immortalis: two digits (G31..G78) or three (G310..G925)
            val mali = Regex("(?:mali|immortalis)-g([0-9]{2,3})(?![0-9])").find(r)?.groupValues?.get(1)?.toIntOrNull()
            val weak = when {
                // Adreno 5xx and the entry 6xx parts (605..613)
                adreno != null -> adreno < 615
                r.contains("mali-t") || r.contains("mali-4") -> true
                mali != null -> if (mali < 100) mali < 70 else mali < 600 // G31..G68, G310, G510
                r.contains("powervr") -> true
                else -> false
            }
            val mid = when {
                // 6xx, and the lower tiers of later generations (710, 720, 810)
                adreno != null -> adreno < 700 || adreno % 100 < 25
                mali != null -> if (mali < 100) mali < 80 else mali < 700 // G71..G78, G610..G625
                else -> false
            }
            return if (weak) 0 else if (mid) 1 else 2
        }

        private const val STATE_MENU = 0
        private const val STATE_FLIGHT = 1
        private const val OVERLAY_NONE = 0
        private const val OVERLAY_SETTINGS = 1
        private const val OVERLAY_PAUSE = 2
        private const val OVERLAY_MAP = 3
        private const val OVERLAY_DOCKED = 4
        private const val ACTION_NONE = 0
        private const val ACTION_LAUNCH = 1
        private const val ACTION_MENU = 2
        private const val MAX_STEP = 1f / 120f
        private const val ORBIT_PX_PER_RAD = 260f
        private const val LOCK_RANGE = 8000f
        private const val LOCK_CONE_COS = 0.951f // 18°
        private const val LOCK_CONE_SIN = 0.30920f // sqrt(1 - LOCK_CONE_COS²)
        private const val AIM_CONE_COS = 0.9986f // 3°
        private const val AIM_CONE_SIN = 0.052896f
        private const val LOCK_NONE = 0
        private const val LOCK_ASTEROID = 1
        private const val LOCK_STATION = 2
        private const val LOCK_BODY = 3
        private const val FIELD_RANGE = 60_000.0
        private const val DUST_VISUAL_SPEED = 400f
        private const val LANDING_SPEED = 200f
        private const val TOUCHDOWN_SPEED = 8f
        private const val TOUCHDOWN_MAX_SPEED = 40f
        /** cos of the largest tilt from level that still touches down (~37°). */
        private const val TOUCHDOWN_LEVEL = 0.8f
        private const val LIFT_TIME = 1.2f
        /** Vertical thruster authority in landing mode: 0.1 x 70 m/s = a 7 m/s descent, under the touchdown limit. */
        private const val LANDING_VERTICAL = 0.1f
        private const val GEAR_LEGS = 1.6f
        private const val GEAR_TIME = 2.2f
        private const val DOCK_STANDOFF = 300.0
        private const val DOCK_APPROACH_SPEED = 180f

        /** Auto-dock gives up after the router has been boxed in this long (s). */
        private const val DOCK_BOXED_GRACE = 0.5f

        /** No station proximity warning for this long (s) after a launch from a slot. */
        private const val LAUNCH_GRACE = 1.5f

        /** Most target labels the flight HUD places per frame. */
        private const val MAX_LABELS = 32
    }
}
