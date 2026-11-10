package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.audio.AudioClock
import com.starwake.core.audio.AudioConfig
import com.starwake.core.audio.AudioOut
import com.starwake.core.audio.AudioState
import com.starwake.core.audio.Bus
import com.starwake.core.audio.CalloutFrame
import com.starwake.core.audio.Callouts
import com.starwake.core.audio.DockState
import com.starwake.core.audio.ShipAudio
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.Voice
import com.starwake.core.audio.VoiceConfig
import com.starwake.core.audio.VoiceLine
import com.starwake.core.math.Rng
import com.starwake.core.math.clamp
import com.starwake.core.ui.TextBuf
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The one adapter between the session and the sound (`09` 9.3): it owns [ShipAudio], the [Voice]
 * queue and the [Callouts], fills their frames from the session's current flight and docking state,
 * and is called at the Roblox frame slots (C43 audio, C44 Flight Assist line, C46 callouts, C64 /
 * A14 / B10 voice). [tick] runs every game frame: the clock, the settings volumes, the pause, the
 * music rotation (its own Heartbeat in Roblox) and the menu music.
 *
 * Until the flight, world, docking and notices ports land, some inputs are **stand-ins** computed
 * from the pre-port model; each is marked "stand-in" below and listed in DECISIONS 39. Blocks whose
 * source does not exist at all stay off (silent, never a wrong line: `09` R6).
 *
 * Game thread; nothing here allocates per frame.
 */
class SessionAudio(
    val out: AudioOut,
    private val settings: Settings,
    seed: Long = System.nanoTime(),
) {
    val clock = AudioClock()
    private val rng = Rng(seed)

    /** The ship's sound; a new one per flight (as Roblox builds ShipAudio per ShipClient). */
    var ship = ShipAudio(out, clock, rng)
        private set
    var voice = Voice(out, clock)
        private set
    var callouts = Callouts(voice, clock)
        private set

    private var session: Session? = null
    private val state = AudioState()
    private val frame = CalloutFrame()

    // ---- stand-in state
    private var lastFlightAssist = true
    private var dockStand = DockState.NONE
    private var dockTimer = 0f
    private var wasDocked = false
    private var wasDocking = false
    private var lastMessage: String? = null

    // ---- pause and menu music
    private var uiGain = -1f
    private var paused = false
    private var menuLevel = 0f
    private var menuPlaying = false
    private var menuPaused = false

    /** Effects muted for good by the "mute" debug command. */
    var mutedByDebug = false

    private val buf = TextBuf(256)

    init {
        SoundBank.loadAll(out)
        applyGroups()
    }

    /** The groups of a fresh ShipAudio from the settings (it may follow a muted one). */
    private fun applyGroups() {
        ship.setVolumes(settings.masterVolume, if (mutedByDebug) 0f else settings.effectsVolume, settings.musicVolume)
        ship.applyVolumes()
    }

    // ================================================================== the session's life

    /** A flight starts (Game builds a Session): fresh ShipAudio, voice and callouts. Not per frame. */
    fun begin(s: Session) {
        end()
        session = s
        ship = ShipAudio(out, clock, rng)
        voice = Voice(out, clock)
        callouts = Callouts(voice, clock)
        applyGroups()
        ship.start()
        lastFlightAssist = s.thrust.flightAssist
        dockStand = DockState.NONE
        dockTimer = 0f
        wasDocked = s.mode.docked
        wasDocking = s.mode.docking
        lastMessage = s.message
    }

    /** The flight ends (back to the menu): every ship sound stops, the queue empties. */
    fun end() {
        if (session == null) return
        ship.stopAll()
        out.stop(SoundBank.SLOT_MUSIC_FLIGHT)
        voice.interrupt()
        session = null
        if (paused) {
            paused = false
            out.setBusPaused(Bus.SFX, false)
            out.setBusPaused(Bus.VOICE, false)
        }
    }

    // ================================================================== every game frame

    /**
     * Every frame, also paused and in the menu: [inMenu] = the main menu is up (its music),
     * [simulating] = the flight runs (no pausing overlay); the audio clock runs only then.
     */
    fun tick(dt: Float, inMenu: Boolean, simulating: Boolean) {
        if (simulating) clock.now += dt
        val master = settings.masterVolume
        val effects = if (mutedByDebug) 0f else settings.effectsVolume
        ship.setVolumes(master, effects, settings.musicVolume)
        voice.setVolumes(master, effects)
        val ui = master * effects
        if (ui != uiGain) {
            uiGain = ui
            out.setBusGain(Bus.UI, ui)
            out.setBusGain(Bus.DECK, ui)
        }
        ship.setMusicEnabled(settings.musicOn)

        // the pause menu holds the ship and the announcer where they are
        val hold = session != null && !simulating
        if (hold != paused) {
            paused = hold
            out.setBusPaused(Bus.SFX, hold)
            out.setBusPaused(Bus.VOICE, hold)
        }

        if (session != null) ship.updateMusic(dt)
        menuMusic(dt, inMenu)
    }

    /**
     * The menu's own music (`07`, ShipMenu): the menu track looped at 0.3 x MusicVolume, fading in
     * over 2.5 s; LAUNCH fades it out in 1 s; MUSIC off fades it out and holds it.
     */
    private fun menuMusic(dt: Float, inMenu: Boolean) {
        val want = inMenu && settings.musicOn
        if (want && !menuPlaying && out.isLoaded(SoundBank.MUSIC_MENU)) {
            menuPlaying = true
            menuPaused = false
            menuLevel = 0f
            out.play(SoundBank.SLOT_MUSIC_MENU, SoundBank.MUSIC_MENU, 0f, 1f, true, 0f, Bus.MUSIC)
        }
        if (!menuPlaying) return
        if (want) {
            if (menuPaused) {
                menuPaused = false
                out.resume(SoundBank.SLOT_MUSIC_MENU)
            }
            menuLevel = minOf(1f, menuLevel + dt / AudioConfig.MENU_MUSIC_FADE_IN)
        } else {
            menuLevel = max(0f, menuLevel - dt / AudioConfig.MENU_MUSIC_FADE_OUT)
            if (menuLevel <= 0f) {
                if (inMenu) {
                    // MUSIC off in the menu: hold the place
                    if (!menuPaused) {
                        menuPaused = true
                        out.pause(SoundBank.SLOT_MUSIC_MENU)
                    }
                } else {
                    menuPlaying = false
                    out.stop(SoundBank.SLOT_MUSIC_MENU)
                    return
                }
            }
        }
        out.set(SoundBank.SLOT_MUSIC_MENU, menuLevel * AudioConfig.MENU_MUSIC_VOLUME, 1f)
    }

    // ================================================================== the frame slots

    /** C43 (`ShipClient.lua:2908-2928`): the state table, then ShipAudio.update. */
    fun flightFrame(dt: Float) {
        val s = session ?: return
        val sh = s.ship
        val th = s.thrust
        val st = state
        st.throttle = th.throttle.toFloat()
        st.speed = sh.speed.toFloat()
        st.maxSpeed = AudioConfig.THRUST_MAX_SPEED
        st.forwardAccel = th.forwardAcceleration(sh.vel).toFloat() // thrust:getForwardAcceleration()
        st.rotationAmount = s.rotation.inputAmount // rotation:getInputAmount()
        st.rollAmount = s.rotation.rollAmount // rotation:getRollAmount()
        st.topSpeed = s.handling.topSpeed.toFloat()
        // the sink rate: the closing speed on the nearest body's ground
        st.sinkRate = max(s.gs.closingSpeed, 0.0).toFloat()
        st.boosting = th.boosting
        st.cruising = th.cruising
        st.flightAssist = th.flightAssist
        st.hasFlightAssist = true
        st.dockState = dockStateStandIn(s, dt)
        st.landed = s.mode.landed
        st.warp = s.mode.travelling
        st.crashing = s.mode.crashing
        ship.update(dt, st)
    }

    /** C44: "Flight assist on/off." (the click and the chirp come from ShipAudio). */
    fun assistFrame() {
        val s = session ?: return
        val fa = s.thrust.flightAssist
        if (fa != lastFlightAssist) {
            lastFlightAssist = fa
            voice.say(if (fa) VoiceLine.FlightAssistOn else VoiceLine.FlightAssistOff)
        }
    }

    /** C46 (`ShipClient.lua:2940-2958`): the callout frame, then ShipCallouts.update. */
    fun calloutFrame(dt: Float) {
        val s = session ?: return
        val sh = s.ship
        val th = s.thrust
        val sys = s.systems
        val gs = s.gs
        val f = frame
        f.posX = sh.pos.x
        f.posY = sh.pos.y
        f.posZ = sh.pos.z
        f.velX = sh.vel.x.toFloat()
        f.velY = sh.vel.y.toFloat()
        f.velZ = sh.vel.z.toFloat()
        upFrom(s, f)
        f.speed = sh.speed.toFloat()
        f.topSpeed = s.handling.topSpeed.toFloat()
        f.onFoot = s.mode.onFoot
        f.crashing = s.mode.crashing
        f.controlled = s.mode.controlled
        f.stationLock = gs.stationLock // ShipStation getLimit's lock
        f.clearance = s.spec.groundClearance
        f.probeRadius = s.spec.collisionRadius
        // flight block (ShipThrust)
        f.hasThrust = true
        f.cruising = th.cruising
        f.flightAssist = th.flightAssist
        f.boosting = th.boosting
        f.throttle = th.throttle.toFloat()
        f.speedLimit = th.speedLimit.toFloat()
        f.rawSpeedLimit = th.rawSpeedLimit.toFloat()
        f.boostCooldown = th.boostCooldown.toFloat()
        f.hasGear = true
        f.gearDown = s.gearWanted()
        // systems (ShipSystems); the overheat stages come from ShipWarnings (crash flow), 0 until it lands
        f.hasHeat = true
        f.heat = sys.heat.toFloat()
        f.heatPhase = s.heatPhase
        f.hasFuel = true
        f.fuel = sys.fuelFraction.toFloat()
        f.hasScoop = true
        f.scooping = sys.scooping
        f.hasG = true
        f.strainG = sys.strainG.toFloat()
        f.redout = sys.redout.toFloat()
        f.hasGravity = gs.body >= 0
        f.inInfluence = gs.inInfluence
        // off until their ports land: atmosphere, landing (terrain altitude), warp, collision, survey, mining
        f.hasAtmosphere = false
        f.surfaceActive = false
        f.terrain = null
        f.landed = s.mode.landed
        f.hasWarp = false
        f.contacts = null
        f.hasSurvey = false
        f.hasMining = false
        f.hasDocking = true
        f.dockState = if (dockStand == DockState.NONE) DockState.FREE else dockStand
        callouts.update(dt, f)
    }

    /**
     * C64 / A14 / B10: the voice queue, every frame of every branch. Also the stand-in comms (the
     * pre-port message line) and the mutes of the deck and destruction branches (Roblox: the decks
     * mute the ship; the cinematic mutes it and hushes the music).
     */
    fun voiceFrame() {
        val s = session ?: return
        when (s.mode.branch) {
            Branch.FLIGHT -> {
                ship.setMuted(false)
                ship.setMusicHush(false)
            }
            Branch.DECK -> {
                ship.setMuted(true)
                ship.setMusicHush(false)
            }
            Branch.DESTROYED -> {
                ship.setMuted(true)
                ship.setMusicHush(true)
            }
        }
        val m = s.message
        if (m !== lastMessage) {
            lastMessage = m
            if (m != null) speakMessage(m)
        }
        voice.update()
    }

    /** The welcome lines, [VoiceConfig.WELCOME_DELAY] after the reveal (`tryAttach`, :1216). */
    fun welcome() {
        voice.say(VoiceLine.Welcome)
        voice.say(VoiceLine.SystemsOnline)
    }

    /**
     * A hull contact at [speed] m/s (the station scrape of `ShipClient.lua:2736`, also used for the
     * pre-port asteroid and body contacts): a metal hit above 4 m/s, "Impact." above HullImpactSpeed.
     */
    fun hullHit(speed: Float) {
        if (session == null) return
        if (speed > 4f) ship.playHit(clamp(speed / CRASH_SPEED, 0.15f, 1f), 0.3f)
        if (speed > com.starwake.core.audio.CalloutConfig.HULL_IMPACT_SPEED) voice.say(VoiceLine.HullImpact)
    }

    /** Ring debris contact (`ShipClient.lua:3069`): louder with speed, at most one hit per `Rings.HitCooldownSeconds`. */
    fun debrisHit(speed: Float) {
        if (session == null) return
        ship.playHit(0.3f + clamp(speed / 400f, 0f, 1.1f), com.starwake.core.world.RingDebris.HIT_COOLDOWN)
    }

    // ================================================================== notices and comms

    /** ShipNotices.speakComms: the first CommsLines prefix the body starts with says its lines. */
    fun speakComms(body: String) {
        val p = VoiceConfig.COMMS_PREFIXES
        for (i in p.indices) {
            if (body.startsWith(p[i])) {
                voice.sayAll(VoiceConfig.COMMS_LINES[i])
                return
            }
        }
    }

    /** ShipNotices.speakNotice: a toast title in NoticeLines says its lines. */
    fun speakNotice(title: String) {
        val t = VoiceConfig.NOTICE_TITLES
        for (i in t.indices) {
            if (title == t[i]) {
                voice.sayAll(VoiceConfig.NOTICE_LINES[i])
                return
            }
        }
    }

    /**
     * The pre-port message line stands in for FlightNotices' comms and toasts (`08`): the Roblox
     * prefixes first, then the pre-port docking and landing texts (stand-in until `08` lands).
     */
    private fun speakMessage(m: String) {
        val p = VoiceConfig.COMMS_PREFIXES
        for (i in p.indices) {
            if (m.startsWith(p[i])) {
                voice.sayAll(VoiceConfig.COMMS_LINES[i])
                return
            }
        }
        when {
            m.startsWith("DOCKING GRANTED") -> voice.sayAll(GRANTED_LINES)
            m.startsWith("LAUNCHED") -> voice.sayAll(LAUNCH_LINES)
            m.startsWith("TOUCHDOWN") -> callouts.say(VoiceLine.Touchdown)
        }
    }

    // ================================================================== stand-ins

    /**
     * Stand-in for ShipDocking's state from the pre-port auto-dock: AUTO while it flies, CLAMPING for
     * the Roblox clamp time (1.6 s) once it parks, then DOCKED; LAUNCHING for a moment after a
     * launch; FREE otherwise. A start in the slot is DOCKED from the first frame (silent).
     */
    private fun dockStateStandIn(s: Session, dt: Float): Int {
        val docked = s.mode.docked
        val docking = s.mode.docking
        if (dockStand == DockState.NONE) {
            dockStand = if (docked) DockState.DOCKED else if (docking) DockState.AUTO else DockState.FREE
        } else if (docked && !wasDocked) {
            dockStand = if (wasDocking) DockState.CLAMPING else DockState.DOCKED
            dockTimer = CLAMP_TIME
        } else if (!docked && wasDocked) {
            dockStand = DockState.LAUNCHING
            dockTimer = LAUNCH_TIME
        } else if (docking && !wasDocking) {
            dockStand = DockState.AUTO
        } else if (!docking && !docked && dockStand == DockState.AUTO) {
            dockStand = DockState.FREE
        } else if (dockStand == DockState.CLAMPING || dockStand == DockState.LAUNCHING) {
            dockTimer -= dt
            if (dockTimer <= 0f) {
                if (dockStand == DockState.CLAMPING) {
                    dockStand = DockState.DOCKED
                    voice.say(VoiceLine.Docked) // stand-in for the comms "LANDING CLAMPS ENGAGED."
                } else {
                    dockStand = DockState.FREE
                }
            }
        }
        wasDocked = docked
        wasDocking = docking
        return dockStand
    }

    /** The local vertical: away from the nearest body (the surface block's frame, unused while it is off). */
    private fun upFrom(s: Session, f: CalloutFrame) {
        val i = s.gs.body
        if (i < 0) {
            f.upX = 0f; f.upY = 1f; f.upZ = 0f
            return
        }
        val b = s.system.bodies[i].pos
        val dx = s.ship.pos.x - b.x
        val dy = s.ship.pos.y - b.y
        val dz = s.ship.pos.z - b.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-3) {
            f.upX = 0f; f.upY = 1f; f.upZ = 0f
        } else {
            f.upX = (dx / d).toFloat(); f.upY = (dy / d).toFloat(); f.upZ = (dz / d).toFloat()
        }
    }

    // ================================================================== debug

    /** "audio": layers, music, voice, buses (allocates; debug only). */
    fun status(): String {
        buf.clear()
        buf.append("music ")
        ship.musicStatus(buf)
        buf.append(" || ")
        voice.status(buf)
        val text = buf.toString()
        val sb = StringBuilder(text)
        sb.append(" || layers")
        for (i in AudioConfig.LAYERS.indices) {
            sb.append(' ').append(AudioConfig.LAYERS[i].name).append('=').append("%.2f".format(ship.layerVolume(i)))
                .append('@').append("%.2f".format(ship.layerPitch(i)))
        }
        sb.append(" || dock ").append(DockState.name(dockStand)).append(" muted=").append(ship.muted)
            .append(" paused=").append(paused).append(" grace=").append("%.1f".format(max(0.0, callouts.graceUntil - clock.now)))
        return sb.toString()
    }

    /** "say <Key>": straight into the queue (false = dropped). */
    fun say(line: VoiceLine): Boolean = voice.say(line)

    /** "music skip". */
    fun skipMusic() = ship.skipMusic()

    companion object {
        /** Stations.CrashSpeed: the hit volume's reference speed. */
        const val CRASH_SPEED = 45f

        /** Roblox's clamp time (1.6 s smoothstep) for the stand-in CLAMPING phase. */
        const val CLAMP_TIME = 1.6f

        /** How long the stand-in LAUNCHING lasts (the pre-port launch is instant). */
        const val LAUNCH_TIME = 2f

        private val GRANTED_LINES = arrayOf(VoiceLine.ControlDockingGranted, VoiceLine.DockingComputerEngaged)
        private val LAUNCH_LINES = arrayOf(VoiceLine.ControlClearedToLaunch, VoiceLine.LaunchInitiated)
    }
}
