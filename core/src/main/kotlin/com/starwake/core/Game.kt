package com.starwake.core

import com.starwake.core.content.Content
import com.starwake.core.content.ShipCatalog
import com.starwake.core.content.ShipModel
import com.starwake.core.game.AsteroidField
import com.starwake.core.game.Branch
import com.starwake.core.game.FrameTrace
import com.starwake.core.game.GearRig
import com.starwake.core.game.OrbitCamera
import com.starwake.core.game.Overlay
import com.starwake.core.game.Session
import com.starwake.core.game.SessionHost
import com.starwake.core.game.ShipSpec
import com.starwake.core.game.ShipState
import com.starwake.core.game.ShipView
import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.hud.SessionHud
import com.starwake.core.input.Action
import com.starwake.core.input.GamepadBindings
import com.starwake.core.input.GamepadState
import com.starwake.core.input.InputFrame
import com.starwake.core.input.InputGate
import com.starwake.core.input.InputQueue
import com.starwake.core.input.PointerHandler
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The platform-facing shell of the game, driven by the platform's GL thread (01-core-loop.md 9.1,
 * DECISIONS 21d): the GPU lifecycle, the screens and the one overlay slot, pointer routing, the
 * curtain and fade timeline (menu ⇄ LOADING → REVEAL → in world), Back and pause, the debug
 * commands, and the [InputFrame] built each frame from touch and gamepad. It owns one [Session] per
 * flight, which runs the Roblox frame order.
 *
 * Only PAUSE and SETTINGS freeze the simulation (DECISIONS 21): the map and the docked station
 * services are overlays the flight keeps running under. The per-frame path performs no heap
 * allocations.
 */
class Game(private val platform: Platform) : GameActions, MapHost, PointerHandler, SessionHost {
    /** Pointer events in render-surface pixels (push from any thread). */
    val input = InputQueue()
    val gamepad = GamepadState()
    private val savedSettings = platform.loadSettings()
    override val settings = Settings().also { it.deserialize(savedSettings) }

    /** CPU time the last [drawFrame] took, for Android performance-hint reporting. */
    var lastFrameCpuNanos = 0L
        private set

    /** Current screen: "menu", "loading", "flight", "map", "docked", "paused", "settings". */
    val stateName: String
        get() = when {
            overlay == Overlay.SETTINGS -> "settings"
            overlay == Overlay.PAUSE -> "paused"
            overlay == Overlay.MAP -> "map"
            overlay == Overlay.SERVICES -> "docked"
            phase == PHASE_LOADING -> "loading"
            inWorld -> "flight"
            else -> "menu"
        }

    override val deviceInfo: String get() = platform.deviceInfo

    /** Harness/test read-out of the game and flight state (allocates; never called per frame by the game). */
    val debugInfo: String
        get() = "state=$stateName phase=${PHASE_NAMES[phase]} curtain=${"%.3f".format(fade)} frame=$frameCount " +
            "build=$buildFrame safety=$revealTimedOut ship=${model?.id} " +
            "aim=${"%.2f".format(inputFrame.aimX)},${"%.2f".format(inputFrame.aimY)} hints=${"%.2f".format(hud.hints)} " +
            (session?.debugState() ?: "menu")

    /** The text on the message line ("" without one; harness read-out, it may contain spaces). */
    val messageLine: String get() = session?.message ?: ""

    /** The step order of the last frame traced with the "trace" command ("F0 C1 C2 ..."), "" before. */
    var frameTrace = ""
        private set

    /** Frame counter (rendered frames since the start). */
    var frameCount = 0L
        private set

    /** The frame the current session was built in (LOADING), -1 without one. */
    var buildFrame = -1L
        private set

    /** The last reveal came from the 12 s safety net, not from the rendered frames (diagnostics). */
    var revealTimedOut = false
        private set

    // ------------------------------------------------------------------ state
    private var phase = PHASE_MENU
    private var phaseTime = 0f
    private var overlay = Overlay.NONE

    /** Opacity of the black curtain drawn over everything (fades and LOADING). */
    private var fade = 1f
    private var menuTime = 0f
    private var welcomed = false
    private var session: Session? = null
    private val inWorld: Boolean get() = session != null && phase != PHASE_MENU && phase != PHASE_FADE_OUT

    @Volatile private var backRequests = 0
    private var backHandled = 0

    @Volatile private var pauseRequested = false
    private val debugQueue = ConcurrentLinkedQueue<String>()
    private val debugPresses = IntArray(Action.COUNT)
    private var traceNext = false
    private val trace = FrameTrace()

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

    // ------------------------------------------------------------------ ship, input, screens
    private var spec = ShipSpec.STANDARD
    override val shipSpec: ShipSpec get() = spec

    /** The flown ship's hull (Settings.ship); models already loaded are kept for quick switching. */
    private var model: ShipModel? = null
    private val models = arrayOfNulls<ShipModel>(ShipCatalog.all.size)
    private var shipIndex = -1
    private val gearRig = GearRig()
    private val system = StarSystem()
    private val menuShip = ShipState()
    private val orbit = OrbitCamera()
    private val touch = TouchFlightControls(settings, this)
    private val pad = GamepadBindings(gamepad, settings)
    private val inputFrame = InputFrame()
    private val hud = SessionHud(frame, settings)
    private val menu = MainMenu(this)
    private val settingsScreen = SettingsScreen(this)
    private val pause = PauseScreen(this)
    private val map = MapScreen(this, system)
    private val dockedScreen = com.starwake.core.ui.DockedScreen(this)
    private var time = 0f
    private var lastFrameNanos = 0L
    private var settingsDirty = false
    private var activePlay = -1 // last value sent to Platform.setActivePlay (-1 = never)
    private var appliedQuality = -1

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
    private val buf = TextBuf(64)
    private val menuShipRot = Quat()
    private val menuCentre = com.starwake.core.math.DVec3()

    init {
        // nose towards the menu column, the sunlit flank and top towards the camera
        menuShipRot.setAxisAngle(0f, 1f, 0f, 1.25f)
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
        sceneFrame.gear = gearRig
    }

    /**
     * Makes ship [index] (ShipCatalog) the flown one: loads its model once, takes its spec (Roblox
     * collision probes, ground clearance), landing gear and menu framing, hands it to the renderer and
     * the session (cameras). GL thread; not per frame (the first load of a ship reads its asset).
     */
    private fun selectShip(index: Int) {
        val i = index.coerceIn(0, ShipCatalog.all.size - 1)
        val entry = ShipCatalog.all[i]
        val m = models[i] ?: ShipModel.load(entry.id) { platform.readAsset(it) }.also { models[i] = it }
        shipIndex = i
        model = m
        spec = ShipSpec.fromModel(entry, m)
        gearRig.setModel(m, session?.gearWanted() ?: false)
        orbit.distance = m.radius * MENU_ORBIT
        scene?.setShipModel(m)
        session?.setShip(entry, spec, m)
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
        // the hull: loaded once, uploaded again with every new GL context
        if (shipIndex != settings.ship) selectShip(settings.ship) else scene?.setShipModel(model)
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
        settings.quality = defaultQuality(renderer)
        settingsDirty = true
    }

    /** Persists pending settings changes (GL thread, e.g. queued when the app is paused). */
    fun flushSettings() = saveSettingsIfDirty()

    /** System back button/gesture. Any thread. */
    fun requestBack() {
        backRequests++
    }

    /** App went to background (pauses the flight). Any thread. */
    fun onPause() {
        pauseRequested = true
    }

    /**
     * Scripting hook for tests and the preview harness (Roblox ShipDebug equivalents, 01 9.6). Words
     * separated by spaces; "x=y" forms of the older commands still work. Any thread (applied next
     * frame, parsed off the frame path).
     *
     * Screens: `launch [space|fade]` (instant docked start / instant free-flight start / the real
     * LAUNCH with fade, LOADING and reveal), `menu`, `settings`, `pause`, `resume`, `map`, `reveal`.
     * Flight: `fa [on|off]`, `boost`, `throttle v`, `stick x y` / `stick off` (held cursor, radius
     * fractions, Y down), `look x y` / `look off` (held head, degrees), `view cockpit|external|chase`,
     * `camera` (next view), `fov deg`, `key <Action>` (an action edge), `lock [next|clear]`, `action`,
     * `undock`, `gear down|up|auto`, `ship [id]`, `quality 0|1|2`. Places: `teleport x y z [lx ly lz]`,
     * `near <body> [alt]`, `teleport-low`, `teleport-surface`, `lock-outpost`, `dock [i]`, `docked [i]`,
     * `behind-station [i]`, `ram-station`. Diagnostics: `state` (see [debugInfo]), `trace` (the next
     * frame's step order into [frameTrace]), `branch deck|destroyed|flight`.
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
        dt = min(dt, Session.MAX_DT)
        time += dt
        frameCount++

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
                scene = SceneRenderer(gl, c, q).also { it.setShipModel(model) }
            }
            appliedQuality = q
        }

        handleSystemEvents()
        // a ship picked in the menu (or by the harness): swap the hull (allocates, only then)
        if (settings.ship != shipIndex) selectShip(settings.ship)
        input.drain(this)
        update(dt)
        val s = session
        val active = if (s != null && inWorld && !overlay.pauses) 1 else 0
        if (active != activePlay) {
            activePlay = active
            platform.setActivePlay(active == 1)
        }
        render(gl)
        lastFrameCpuNanos = System.nanoTime() - cpuStart
        updateFps(dt)
    }

    private fun handleSystemEvents() {
        while (true) {
            val cmd = debugQueue.poll() ?: break
            runDebugCommand(cmd)
        }
        if (pauseRequested) {
            pauseRequested = false
            touch.releaseAll()
            if (inWorld && !overlay.pauses) openPause()
            saveSettingsIfDirty()
        }
        val br = backRequests
        while (backHandled != br) {
            backHandled++
            onBack()
        }
        // gamepad buttons that act like system navigation (not while a fade runs)
        if (overlay == Overlay.SERVICES && gamepad.consumePress(GamepadState.A)) launchFromStation()
        if (gamepad.consumePress(GamepadState.START) && phase != PHASE_FADE_OUT && phase != PHASE_QUITTING) {
            if (inWorld) {
                if (overlay == Overlay.PAUSE) resume() else if (!overlay.pauses) openPause()
            } else if (overlay == Overlay.NONE) {
                launch()
            }
        }
        if (gamepad.consumePress(GamepadState.B)) onBack()
        if (phase == PHASE_MENU && overlay == Overlay.NONE && gamepad.consumePress(GamepadState.A)) launch()
    }

    private fun onBack() {
        when {
            phase == PHASE_FADE_OUT || phase == PHASE_QUITTING -> Unit // a transition is running
            overlay == Overlay.SETTINGS -> closeSettings()
            overlay == Overlay.MAP -> closeMap()
            overlay == Overlay.SERVICES -> openPause()
            overlay == Overlay.PAUSE -> resume()
            inWorld -> openPause()
            else -> exitApp()
        }
    }

    // ================================================================== input routing

    override fun onPointerDown(id: Int, x: Float, y: Float) {
        if (phase == PHASE_FADE_OUT || phase == PHASE_QUITTING) return // transition in progress
        val s = session
        when {
            overlay == Overlay.SETTINGS -> settingsScreen.group.down(id, x, y)
            overlay == Overlay.PAUSE -> pause.group.down(id, x, y)
            overlay == Overlay.MAP -> map.down(id, x, y)
            overlay == Overlay.SERVICES -> dockedScreen.group.down(id, x, y)
            s == null || !inWorld -> {
                if (!menu.group.down(id, x, y) && orbitPointer < 0) {
                    orbitPointer = id
                    orbitLastX = x; orbitLastY = y
                    orbitVelX = 0f; orbitVelY = 0f
                }
            }
            else -> when (InputGate.owner(s.mode)) {
                InputGate.OWNER_FLIGHT -> touch.onDown(id, x, y)
                // nobody owns the touches (LOADING curtain, cinematic, warp tunnel): only PAUSE
                InputGate.OWNER_NONE -> if (touch.pauseBtn.contains(x, y, 8f * frame.u)) touch.onDown(id, x, y)
            }
        }
    }

    override fun onPointerMove(id: Int, x: Float, y: Float) {
        if (settingsScreen.group.move(id, x, y)) return
        if (pause.group.move(id, x, y)) return
        if (dockedScreen.group.move(id, x, y)) return
        if (overlay == Overlay.MAP) {
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
        if (overlay == Overlay.MAP) map.up(id, x, y, cancelled)
        menu.group.up(id, x, y, cancelled)
        if (id == orbitPointer) {
            orbitPointer = -1
            if (!cancelled) orbit.fling(orbitVelX, orbitVelY, ORBIT_PX_PER_RAD * frame.u)
        }
        touch.onUp(id, x, y, cancelled)
    }

    // ================================================================== actions (GameActions, MapHost, SessionHost)

    override fun haptic(type: Int) {
        if (settings.haptics) platform.haptic(type)
    }

    /** LAUNCH in the menu: fade to black (0.9 s, sine in); after 1 s the world loads under the curtain. */
    override fun launch() {
        if (phase == PHASE_MENU && overlay == Overlay.NONE) setPhase(PHASE_FADE_OUT)
    }

    override fun openSettings() {
        overlay = Overlay.SETTINGS
        touch.releaseAll()
        screenChanged()
    }

    override fun closeSettings() {
        overlay = if (inWorld) Overlay.PAUSE else Overlay.NONE
        saveSettingsIfDirty()
        screenChanged()
    }

    override fun resume() {
        val s = session ?: return
        if (inWorld) {
            overlay = if (s.mode.docked) Overlay.SERVICES else Overlay.NONE
            screenChanged()
        }
    }

    private fun openPause() {
        overlay = Overlay.PAUSE
        touch.releaseAll()
        screenChanged()
    }

    private fun openMap() {
        val s = session ?: return
        overlay = Overlay.MAP
        touch.releaseAll()
        screenChanged()
        map.open(s.ship.pos, s.lockKind, s.lockIndex)
    }

    override fun closeMap() {
        if (overlay == Overlay.MAP) {
            overlay = if (session?.mode?.docked == true) Overlay.SERVICES else Overlay.NONE
            screenChanged()
        }
    }

    override fun lockFromMap(kind: Int, index: Int) {
        session?.lock(kind, index)
    }

    override fun openServices(station: Int) {
        // a pausing overlay stays on top; resume() shows the services while docked
        if (overlay.pauses) return
        overlay = Overlay.SERVICES
        touch.releaseAll()
        screenChanged()
    }

    override fun closeServices() {
        if (overlay == Overlay.SERVICES) {
            overlay = Overlay.NONE
            screenChanged()
        }
    }

    override fun releaseControls() {
        touch.releaseAll()
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
        pad.reset()
    }

    override fun quitToMenu() {
        if (inWorld && phase != PHASE_QUITTING) setPhase(PHASE_QUITTING)
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

    override fun launchFromStation() {
        session?.launchFromStation()
    }

    // ================================================================== the session's life

    private fun setPhase(p: Int) {
        phase = p
        phaseTime = 0f
    }

    /**
     * Builds the flight (Roblox: modules built, profile fetched, system built, ship placed): one
     * [Session], placed by the start rule. [space] starts in free flight at the pre-port spawn (the
     * harness' "launch space"); otherwise docked at the home station (D13).
     */
    private fun buildSession(space: Boolean): Session {
        val c = content
        val s = Session(system, c?.field, settings, this, gearRig)
        s.setShip(ShipCatalog.get(shipIndex.coerceAtLeast(0)), spec, model)
        if (space) s.startInSpace() else s.startDocked()
        session = s
        buildFrame = frameCount
        overlay = Overlay.NONE
        touch.releaseAll()
        touch.throttle = s.throttle
        hud.resetHints()
        menu.dragHint = 0f
        screenChanged()
        return s
    }

    /** LOADING: the curtain is black; the world is built in this frame and runs under it. */
    private fun enterLoading(space: Boolean) {
        buildSession(space)
        setPhase(PHASE_LOADING)
        fade = 1f
        revealTimedOut = false
    }

    /** REVEAL: the curtain lifts over 0.6 s (sine out) while the cockpit comes alive (Roblox `reveal` + `afterReveal`). */
    private fun startReveal() {
        setPhase(PHASE_REVEAL)
        session?.afterReveal(!welcomed)
        welcomed = true
    }

    /** Debug fast path: the world at once, without the fades (the harness' "launch"). */
    private fun enterWorldNow(space: Boolean) {
        val s = buildSession(space)
        setPhase(PHASE_WORLD)
        fade = 0f
        s.mode.curtain = false
        s.afterReveal(!welcomed)
        welcomed = true
    }

    private fun enterMenu() {
        session = null
        buildFrame = -1L
        setPhase(PHASE_MENU)
        menuTime = 0f
        overlay = Overlay.NONE
        touch.releaseAll()
        orbit.idle = 10f
        gearRig.update(0f, false, true)
        screenChanged()
    }

    // ================================================================== update

    private fun update(dt: Float) {
        phaseTime += dt
        when (phase) {
            PHASE_MENU -> {
                menuTime += dt
                // the menu fades in over 1.4 s (sine out)
                fade = if (menuTime < MENU_FADE_IN) 1f - sin(HALF_PI * menuTime / MENU_FADE_IN) else 0f
                updateMenu(dt)
            }
            PHASE_FADE_OUT -> {
                fade = sineIn(phaseTime / LAUNCH_FADE)
                updateMenu(dt)
                if (phaseTime >= LAUNCH_DELAY) enterLoading(false)
            }
            else -> updateWorld(dt)
        }
    }

    private fun updateWorld(dt: Float) {
        val s = session ?: return
        when (phase) {
            PHASE_LOADING -> {
                fade = 1f
                // two frames rendered after the build frame, then the reveal; 12 s safety net
                val timedOut = phaseTime > REVEAL_TIMEOUT
                if (frameCount - buildFrame >= REVEAL_AFTER_FRAMES || timedOut) {
                    if (timedOut) revealTimedOut = true
                    startReveal()
                    // the tween's first step shows in this frame already, as Roblox's reveal tween
                    phaseTime = dt
                }
            }
            PHASE_QUITTING -> {
                fade = sineIn(phaseTime / LAUNCH_FADE)
                if (phaseTime >= LAUNCH_DELAY) {
                    enterMenu()
                    return
                }
            }
        }
        if (phase == PHASE_REVEAL) {
            fade = 1f - sin(HALF_PI * min(phaseTime / REVEAL_TIME, 1f))
            if (phaseTime >= REVEAL_TIME) {
                setPhase(PHASE_WORLD)
                fade = 0f
            }
        }
        val mode = s.mode
        mode.curtain = phase == PHASE_LOADING
        mode.overlay = overlay
        buildInput(dt, s)
        handleShellActions(s)
        mode.overlay = overlay
        if (!overlay.pauses && phase != PHASE_QUITTING) {
            if (traceNext) {
                traceNext = false
                s.trace = trace
                s.frame(dt, inputFrame)
                s.trace = null
                frameTrace = trace.toString()
            } else {
                s.frame(dt, inputFrame)
            }
            // the first-flight hints count only the time the pilot can see them (not under the
            // services of a docked start, a map or the LOADING curtain)
            if (flightHudShown(s) && !mode.curtain) hud.updateHints(dt, touch)
        }
        // the touch controls mirror the session: the latched lever, the action pill, the look strip
        touch.throttle = s.throttle
        touch.actionLabel = s.actionLabel
        touch.lookEnabled = mode.view != ShipView.CHASE
    }

    /** The flight HUD and the touch controls are on screen: no overlay, the flight branch (render and the hint clock agree). */
    private fun flightHudShown(s: Session): Boolean = overlay == Overlay.NONE && s.mode.branch == Branch.FLIGHT

    /** One [InputFrame] from touch and gamepad for whoever owns the input this frame (01 9.2). */
    private fun buildInput(dt: Float, s: Session) {
        val f = inputFrame
        f.begin()
        f.contextAction = s.actionTarget
        val owner = InputGate.owner(s.mode)
        if (owner == InputGate.OWNER_FLIGHT || owner == InputGate.OWNER_NONE) {
            // under the curtain only PAUSE gets through to here (see onPointerDown)
            touch.write(f, dt)
            if (owner == InputGate.OWNER_FLIGHT) pad.write(f, dt, s.throttle, this)
        }
        for (i in 0 until Action.COUNT) {
            val n = debugPresses[i]
            if (n == 0) continue
            debugPresses[i] = 0
            repeat(n) { f.press(Action.of(i)) }
        }
    }

    /** The actions that belong to the shell: pause and the maps. The rest goes to the session. */
    private fun handleShellActions(s: Session) {
        val f = inputFrame
        if (f.consume(Action.PAUSE)) {
            openPause()
            return
        }
        if (f.consume(Action.BACK)) onBack()
        val mapAsked = f.consume(Action.SYSTEM_MAP) or f.consume(Action.STAR_MAP)
        if (mapAsked && InputGate.allows(Action.SYSTEM_MAP, s.mode)) {
            if (overlay == Overlay.MAP) closeMap() else if (!overlay.pauses) openMap()
        }
    }

    private fun updateMenu(dt: Float) {
        orbit.update(dt)
        tmpQ.setAxisAngle(0f, 0f, 1f, sin(time * 0.5f) * 0.03f)
        menuShip.rot.mul(menuShipRot, tmpQ)
        // the hull's centre floats at the showcase point, bobbing gently; the orbit circles it
        val m = model
        val size = (m?.radius ?: 8f) / 8f
        menuCentre.set(
            system.home.x + AsteroidField.SHOWCASE_X,
            system.home.y + AsteroidField.SHOWCASE_Y + sin(time * 0.7f) * 0.35 * size,
            system.home.z + AsteroidField.SHOWCASE_Z,
        )
        if (m != null) menuShip.rot.rotate(m.centreX, m.centreY, m.centreZ, tmp) else tmp.zero()
        menuShip.pos.set(menuCentre.x - tmp.x, menuCentre.y - tmp.y, menuCentre.z - tmp.z)
        menuShip.engineOutput = 0.12f + 0.03f * sin(time * 3f)
        gearRig.update(dt, false, false)
    }

    /** The menu backdrop's camera and scene (the orbit around the showcase ship). */
    private fun menuScene() {
        val m = model
        val cam = sceneFrame.camera
        orbit.apply(menuCentre, cam.pos, cam.rot)
        cam.fovY = 42f * DEG2RAD
        // lens shift moves the ship to the right of the menu column on wide screens, and up off the ship card
        cam.shiftX = if (frame.w > frame.h * 1.4f) 0.30f else 0f
        cam.shiftY = 0.16f
        sceneFrame.dustVelocity.zero()
        sceneFrame.dustIntensity = 0.35f
        sceneFrame.dustOrigin.set(cam.pos)
        sceneFrame.drawField = true
        sceneFrame.ship = menuShip
        sceneFrame.shipView = ShipModel.VIEW_EXTERIOR
        sceneFrame.shipReach = orbit.distance + (m?.radius ?: 0f)
        updateLighting(menuShip.pos)
    }

    // ================================================================== debug commands

    private fun runDebugCommand(raw: String) {
        val cmd = raw.trim()
        // older "name=value" forms: ship=<id>, dock=1, docked=1, behind-station=1
        val words = if ('=' in cmd && ' ' !in cmd) listOf(cmd.substringBefore('='), cmd.substringAfter('=')) else cmd.split(Regex("\\s+"))
        val name = words[0].lowercase()
        val arg = words.getOrNull(1)
        fun num(i: Int): Double? = words.getOrNull(i)?.toDoubleOrNull()
        val s = session
        val flying = s != null && inWorld
        when (name) {
            "launch" -> if (phase == PHASE_MENU) when (arg) {
                "fade" -> launch()
                "space" -> enterWorldNow(true)
                else -> enterWorldNow(false)
            }
            "menu" -> {
                enterMenu()
                menuTime = MENU_FADE_IN
                fade = 0f
            }
            "settings" -> openSettings()
            "pause" -> if (inWorld) openPause()
            "resume" -> resume()
            "reveal" -> if (phase == PHASE_LOADING) startReveal()
            "map" -> if (flying) openMap()
            "state" -> Unit // debugInfo always carries the full state list
            "trace" -> traceNext = true
            "ship" -> {
                if (arg == null) {
                    settings.ship = (settings.ship + 1) % ShipCatalog.all.size
                    settingsDirty = true
                } else {
                    val i = ShipCatalog.all.indexOfFirst { it.id == arg.lowercase() }
                    if (i >= 0) {
                        settings.ship = i
                        settingsDirty = true
                    }
                }
            }
            "gear-down" -> s?.gearForce = true
            "gear-up" -> s?.gearForce = false
            "gear-auto" -> s?.gearForce = null
            "gear" -> s?.gearForce = when (arg) {
                "down" -> true
                "up" -> false
                else -> null
            }
            "key" -> Action.parse(arg ?: "")?.let { debugPresses[it.ordinal]++ }
            "fov" -> num(1)?.let {
                settings.fovDeg = it.toFloat()
                settingsDirty = true
            }
            "quality" -> num(1)?.let {
                settings.quality = it.toInt().coerceIn(0, 2)
                settingsChanged(display = true, renderer = true)
            }
            "branch" -> if (s != null) when (arg) {
                "deck" -> s.decks.begin()
                "destroyed" -> s.destruction.start((num(2) ?: 2.0).toFloat())
                else -> {
                    s.decks.end()
                    s.destruction.finish()
                }
            }
            else -> if (flying) flightCommand(s!!, name, arg, words)
        }
    }

    /** Commands that need a flight. */
    private fun flightCommand(s: Session, name: String, arg: String?, words: List<String>) {
        fun num(i: Int): Double? = words.getOrNull(i)?.toDoubleOrNull()
        val station = arg?.toIntOrNull()?.takeIf { it in system.stations.indices }
        when (name) {
            "fa" -> if (arg == null || (arg == "on") != s.ship.flightAssist) s.toggleFlightAssist()
            "boost" -> s.requestBoost()
            "throttle" -> num(1)?.let { s.setThrottle(it.toFloat()) }
            "stick" -> if (arg == "off" || arg == null) s.debugStick(false, 0f, 0f) else s.debugStick(true, (num(1) ?: 0.0).toFloat(), (num(2) ?: 0.0).toFloat())
            "look" -> if (arg == "off" || arg == null) s.debugLook(false, 0f, 0f) else s.debugLook(true, (num(1) ?: 0.0).toFloat(), (num(2) ?: 0.0).toFloat())
            "view" -> when (arg) {
                "cockpit" -> s.setView(ShipView.COCKPIT)
                "external" -> s.setView(ShipView.EXTERNAL)
                "chase" -> {
                    if (!settings.chaseView) {
                        settings.chaseView = true
                        settingsDirty = true
                    }
                    s.setView(ShipView.CHASE)
                }
            }
            "camera" -> s.toggleView()
            "lock" -> if (arg == "clear") s.clearTarget() else s.cycleTarget()
            "action" -> s.contextAction()
            "undock" -> s.launchFromStation()
            "teleport" -> {
                val x = num(1)
                val y = num(2)
                val z = num(3)
                if (x != null && y != null && z != null) {
                    val lx = num(4)
                    val ly = num(5)
                    val lz = num(6)
                    val look = lx != null && ly != null && lz != null
                    s.teleport(x, y, z, (lx ?: 0.0).toFloat(), (ly ?: 0.0).toFloat(), (lz ?: -1.0).toFloat(), look)
                }
            }
            "near" -> nearTarget(words, system)?.let { (i, alt) -> s.near(i, alt) }
            "teleport-low" -> s.debugTeleportLow()
            "teleport-surface" -> {
                s.debugTeleportLow()
                s.debugDrop(60.0)
            }
            "lock-outpost" -> s.lock(Session.LOCK_STATION, 1)
            "dock" -> s.requestDocking(station ?: 0)
            "docked" -> s.debugDocked(station ?: 0)
            "behind-station" -> s.debugBehindStation(station ?: 0, 1500.0, 0f)
            "ram-station" -> s.debugBehindStation(0, 400.0, 120f)
        }
    }

    // ================================================================== render

    private fun render(gl: Gl) {
        val scene = scene ?: return
        val ui = ui ?: return
        val f = sceneFrame
        f.time = time
        f.quality = scene.quality
        f.pxScale = surfaceH / 1080f
        val s = session
        val world = s != null && inWorld
        if (world) {
            s!!.writeScene(f)
            f.gear = gearRig
            updateLighting(s.ship.pos)
        } else {
            menuScene()
        }
        f.camera.update(surfaceW, surfaceH)
        scene.render(f)

        ui.begin(surfaceW, surfaceH)
        val u = frame.u
        if (!world) {
            if (overlay == Overlay.NONE) menu.draw(ui, frame, 1f, time)
        } else if (flightHudShown(s!!)) {
            hud.draw(ui, s, f.camera, touch, 1f, time)
        }
        when (overlay) {
            Overlay.SETTINGS -> settingsScreen.draw(ui, frame, 1f)
            Overlay.PAUSE -> pause.draw(ui, frame, 1f)
            Overlay.MAP -> if (s != null) map.draw(ui, 1f, s.ship.pos, s.ship.rot, time)
            Overlay.SERVICES -> if (s != null && s.dockStation >= 0) dockedScreen.draw(ui, frame, 1f, system.stations[s.dockStation].name)
            Overlay.NONE -> Unit
        }
        // the message line over an overlay that leaves room for it (the services: the welcome lines
        // of a docked start show there, D31); with no overlay the HUD draws it
        if (world && overlay != Overlay.NONE && overlay.showsMessages) hud.drawMessage(ui, s!!, 1f)
        if (settings.showFps) {
            buf.clear().append(fpsValue.toInt()).append(" FPS  ").append(cpuMsAvg, 1).append(" MS CPU")
            ui.text(buf, frame.w - frame.sr - 14f * u, frame.st + 66f * u, 10f * u, withAlpha(Theme.OK, 0.9f), UiRenderer.RIGHT, shadow = 0.7f)
        }
        // the curtain, above everything (Roblox: the menu GUI at DisplayOrder 1000)
        if (fade > 0.003f) ui.rect(-2f, -2f, frame.w + 4f, frame.h + 4f, withAlpha(Theme.BLACK, fade))
        if (phase == PHASE_LOADING && phaseTime > LOADING_CAPTION_AFTER) {
            ui.textCentered("PREPARING YOUR SHIP ...", frame.w * 0.5f, frame.h * 0.5f, 11f * u, withAlpha(Theme.TEXT_DIM, 1f), tracking = 0.2f)
            val t = (phaseTime % 1.6f) / 1.6f
            ui.rect(frame.w * 0.5f - 60f * u, frame.h * 0.5f + 14f * u, 120f * u, 2f * u, withAlpha(Theme.PANEL_LIGHT, 1f))
            ui.rect(frame.w * 0.5f - 60f * u + 90f * u * t, frame.h * 0.5f + 14f * u, 30f * u, 2f * u, withAlpha(Theme.TEXT_DIM, 1f))
        }
        ui.end()
        gl.invalidateDepth()
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

        /**
         * The debug command "near <body> [alt]" (01 9.6) as (body index, altitude m), or null for an
         * unknown body. The body is a name (a '-' stands for a space: "PLANET-II") or an index; a
         * number counts as the altitude only after the body, so "near 3" is body 3 at
         * [NEAR_ALTITUDE]. Debug path, allocates.
         */
        internal fun nearTarget(words: List<String>, system: StarSystem): Pair<Int, Double>? {
            val alt = if (words.size > 2) words.last().toDoubleOrNull() else null
            val key = words.subList(minOf(1, words.size), if (alt != null) words.size - 1 else words.size).joinToString(" ")
            val i = key.toIntOrNull() ?: system.bodies.indexOfFirst { it.name.equals(key.replace('-', ' '), ignoreCase = true) }
            return if (i in system.bodies.indices) i to (alt ?: NEAR_ALTITUDE) else null
        }

        /** Altitude of "near <body>" without one, m. */
        const val NEAR_ALTITUDE = 20_000.0

        /** Sine-in ease of a 0..1 progress (Roblox EasingStyle.Sine, In). */
        private fun sineIn(t: Float): Float = 1f - cos(HALF_PI * t.coerceIn(0f, 1f))

        private const val HALF_PI = 1.5707964f

        // app phases (01 4.1, port version)
        private const val PHASE_MENU = 0
        private const val PHASE_FADE_OUT = 1
        private const val PHASE_LOADING = 2
        private const val PHASE_REVEAL = 3
        private const val PHASE_WORLD = 4
        private const val PHASE_QUITTING = 5
        private val PHASE_NAMES = arrayOf("MENU", "FADE_OUT", "LOADING", "REVEAL", "WORLD", "QUITTING")

        /** The menu fades in over 1.4 s, sine out (ShipMenu, `07` 4.2). */
        const val MENU_FADE_IN = 1.4f

        /** LAUNCH fades to black over 0.9 s, sine in; the world starts loading after 1 s (`07` 4.2). */
        const val LAUNCH_FADE = 0.9f
        const val LAUNCH_DELAY = 1.0f

        /** Frames from the build frame to the reveal: the build frame and two more render under the curtain. */
        const val REVEAL_AFTER_FRAMES = 3L

        /** The curtain lifts over 0.6 s, sine out (ShipMenu REVEAL_DEFAULT). */
        const val REVEAL_TIME = 0.6f

        /** Safety net: the curtain opens by itself this long after LOADING began (REVEAL_TIMEOUT). */
        const val REVEAL_TIMEOUT = 12f

        /** "PREPARING YOUR SHIP ..." shows when LOADING lasts longer than this (01 5.1). */
        const val LOADING_CAPTION_AFTER = 0.8f
        private const val ORBIT_PX_PER_RAD = 260f

        /** Menu orbit distance relative to the hull's bounding radius. */
        private const val MENU_ORBIT = 3.6f
    }
}
