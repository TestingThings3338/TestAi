package com.starwake.core.audio

/**
 * `ShipConfig.Audio` (`ShipConfig.lua:1748`, `09` 3.6) verbatim: the layers, cues, the warp loop,
 * the music rotation and the flight sound events. Volumes are Roblox `Sound.Volume` (linear gain),
 * times in seconds, speeds in m/s.
 */
object AudioConfig {
    /** Audio.MasterVolume: x EffectsVolume = the effects group gain. */
    const val MASTER_VOLUME = 1.0f

    /** Audio.AccelReference: forward acceleration (m/s²) at which Thrust/Decel are full. */
    const val ACCEL_REFERENCE = 25f

    /** Audio.HitCooldown: least time between two hull hits. */
    const val HIT_COOLDOWN = 0.14f

    /** Thrust.MaxSpeed (absolute 1000 m/s, DECISIONS 2): the rumble's speed fraction. */
    const val THRUST_MAX_SPEED = 1000f

    /** Surface.SafeLandingSpeed: the touchdown scale's reference sink rate. */
    const val SAFE_LANDING_SPEED = 38f

    /** One looped flight layer (`Audio.<Name>`). */
    class Layer(
        val name: String,
        val sound: Int,
        val volume: Float,
        val pitchMin: Float,
        val pitchMax: Float,
        val fadeTime: Float,
        val pitchTime: Float,
    )

    val ENGINE = Layer("Engine", SoundBank.ENGINE, 0.75f, 0.80f, 1.15f, 0.6f, 0.9f)
    val THRUST = Layer("Thrust", SoundBank.THRUST, 0.22f, 0.90f, 1.20f, 0.35f, 0.5f)
    val DECEL = Layer("Decel", SoundBank.DECEL, 0.18f, 0.80f, 0.95f, 0.35f, 0.5f)
    val RCS = Layer("Rcs", SoundBank.RCS, 1.2f, 0.95f, 1.25f, 0.12f, 0.2f)
    val RUMBLE = Layer("Rumble", SoundBank.RUMBLE, 0.60f, 0.90f, 1.10f, 0.8f, 1.0f)
    val CRUISE = Layer("Cruise", SoundBank.CRUISE, 0.80f, 0.92f, 1.10f, 0.8f, 1.5f)

    /** LOOP_LAYERS order; layer i plays in slot [SoundBank.SLOT_LAYER0] + i. */
    val LAYERS = arrayOf(ENGINE, THRUST, DECEL, RCS, RUMBLE, CRUISE)
    const val L_ENGINE = 0
    const val L_THRUST = 1
    const val L_DECEL = 2
    const val L_RCS = 3
    const val L_RUMBLE = 4
    const val L_CRUISE = 5

    /** Engine.Idle: level at throttle 0. */
    const val ENGINE_IDLE = 0.55f

    /** Rcs.RollWeight: how much roll input counts. */
    const val RCS_ROLL_WEIGHT = 0.8f

    /** Rumble.SpeedCurve and Rumble.Idle. */
    const val RUMBLE_SPEED_CURVE = 1.3f
    const val RUMBLE_IDLE = 0.30f

    /** Cruise.Idle and Cruise.FullSpeed (20 km/s: full volume). */
    const val CRUISE_IDLE = 0.35f
    const val CRUISE_FULL_SPEED = 20_000f

    /**
     * One cue (`Audio.Cues.<Name>`): [pitch] = PlaybackSpeed, [startTime] skips a lead-in, [maxTime]
     * > 0 fades the cue out over [fadeOut] after that long.
     */
    class Cue(
        val name: String,
        val sound: Int,
        val volume: Float,
        val pitch: Float = 1f,
        val startTime: Float = 0f,
        val maxTime: Float = 0f,
        val fadeOut: Float = 0f,
    )

    val CUES = arrayOf(
        Cue("FlightAssist", SoundBank.FA_SWITCH, 0.18f),
        Cue("FlightAssistOn", SoundBank.FA_ON, 0.65f),
        Cue("FlightAssistOff", SoundBank.FA_OFF, 0.42f),
        Cue("MetalHit1", SoundBank.HIT1, 2.4f),
        Cue("MetalHit2", SoundBank.HIT2, 2.4f),
        Cue("MetalHit3", SoundBank.HIT3, 2.4f),
        Cue("WarpEnter", SoundBank.WARP_ENTER, 2.6f),
        Cue("WarpExit", SoundBank.WARP_EXIT, 2.6f),
        Cue("Boost", SoundBank.BOOST, 0.8f, startTime = 0.12f),
        Cue("CruiseEngage", SoundBank.CRUISE_ON, 0.7f, startTime = 0.3f),
        Cue("CruiseDisengage", SoundBank.CRUISE_OFF, 1.1f),
        Cue("DockClamp", SoundBank.CLAMP, 0.8f, pitch = 0.8f),
        Cue("DockRelease", SoundBank.RELEASE, 0.75f, maxTime = 1.8f, fadeOut = 0.6f),
        Cue("Touchdown", SoundBank.TOUCHDOWN, 0.47f, startTime = 0.28f),
    )

    // cue indices (CUES order; cue i plays in slot SoundBank.SLOT_CUE0 + i)
    const val C_FLIGHT_ASSIST = 0
    const val C_FLIGHT_ASSIST_ON = 1
    const val C_FLIGHT_ASSIST_OFF = 2
    const val C_METAL_HIT1 = 3
    const val C_WARP_ENTER = 6
    const val C_WARP_EXIT = 7
    const val C_BOOST = 8
    const val C_CRUISE_ENGAGE = 9
    const val C_CRUISE_DISENGAGE = 10
    const val C_DOCK_CLAMP = 11
    const val C_DOCK_RELEASE = 12
    const val C_TOUCHDOWN = 13

    /** The cue index of [name], -1 when unknown (debug commands; allocates nothing). */
    fun cueIndex(name: String): Int {
        for (i in CUES.indices) if (CUES[i].name.equals(name, ignoreCase = true)) return i
        return -1
    }

    /** stopCue's default fade (CUE_FADE_TIME): short enough to feel like a cut, long enough not to click. */
    const val CUE_FADE_TIME = 0.12f

    /** Audio.Loops.WarpTravel. */
    const val WARP_TRAVEL_VOLUME = 2.2f
    const val WARP_TRAVEL_FADE = 0.25f

    // ------------------------------------------------------------------ Audio.Music
    /** Music.Volume: the flight rotation's level (final = Volume x Gain x the MUSIC bus). */
    const val MUSIC_VOLUME = 0.10f

    /** Music.Enabled: the rotation starts audible (J / the pause menu's MUSIC toggles it). */
    const val MUSIC_ENABLED = true

    /** One playlist entry; [sound] -1 = not uploaded in Roblox (Sol Standard I and II), skipped. */
    class Track(val name: String, val sound: Int, val gain: Float)

    val PLAYLIST = arrayOf(
        Track("Sol Standard I", -1, 0.88f),
        Track("Sol Standard II", -1, 0.85f),
        Track("Deep Space", SoundBank.MUSIC_DEEP_SPACE, 1.00f),
        Track("Long Haul", SoundBank.MUSIC_LONG_HAUL, 0.93f),
        Track("Meridian Lights", SoundBank.MUSIC_MERIDIAN_LIGHTS, 0.73f),
    )

    /** Music.SoundId: the menu music, and the flight fallback (looped) without any playlist track. */
    const val MUSIC_FALLBACK = SoundBank.MUSIC_MENU

    const val MUSIC_GAP_MIN = 30f
    const val MUSIC_GAP_MAX = 90f
    const val MUSIC_FIRST_GAP = 3f
    const val MUSIC_FADE_TIME = 3f
    const val MUSIC_LOAD_TIMEOUT = 20f

    /** The menu's music (ShipMenuData.Sounds, `07` 2.x): MusicVolume 0.3, fade in 2.5 s, launch fades it out in 1 s. */
    const val MENU_MUSIC_VOLUME = 0.3f
    const val MENU_MUSIC_FADE_IN = 2.5f
    const val MENU_MUSIC_FADE_OUT = 1.0f

    // ------------------------------------------------------------------ Audio.Events
    const val CRUISE_OFF_DELAY = 0.2f
    const val CRUISE_MIN_TIME = 2.0f
    const val CRUISE_COOLDOWN = 4.0f
    const val TOUCHDOWN_AIR_TIME = 1.0f
    const val TOUCHDOWN_COOLDOWN = 2.0f
    const val TOUCHDOWN_MIN_SCALE = 0.4f
    const val TOUCHDOWN_MAX_SCALE = 1.2f
    const val QUIET_TIME = 1.5f
    const val RESYNC_GAP = 0.5f
}
