package com.starwake.core.audio

/**
 * The sound inventory of `09` Appendix A as the port ships it: sound ids, asset paths and the fixed
 * voice slots of 8.4. Every sound effect and music track is an original procedural synthesis made by
 * `tools/audio/synth_sfx.py` and `tools/audio/compose_music.py` (DECISIONS 36, 37); voice lines are
 * the user's dry recordings when present, else the device's text-to-speech (DECISIONS 38).
 *
 * Files: `app/src/main/assets/audio/<group>/<name>.ogg` (sfx: mono 32 kHz Vorbis; music: stereo
 * 44.1 kHz Vorbis, streamed; voice: `voice/<Key>.ogg`, dry). `tools/audio/check_audio.py` verifies
 * that every file listed here exists with the length, loudness, peak and (loops) seamless wrap its
 * description asks for.
 */
object SoundBank {
    // ------------------------------------------------------------------ A.1 flight layers (loops)
    const val ENGINE = 0
    const val THRUST = 1
    const val DECEL = 2
    const val RCS = 3
    const val RUMBLE = 4
    const val CRUISE = 5

    // ------------------------------------------------------------------ A.2 cues and the warp loop
    const val FA_SWITCH = 6
    const val FA_ON = 7
    const val FA_OFF = 8
    const val HIT1 = 9
    const val HIT2 = 10
    const val HIT3 = 11
    const val WARP_ENTER = 12
    const val WARP_EXIT = 13
    const val WARP_TRAVEL = 14
    const val BOOST = 15
    const val CRUISE_ON = 16
    const val CRUISE_OFF = 17
    const val CLAMP = 18
    const val RELEASE = 19
    const val TOUCHDOWN = 20

    // ------------------------------------------------------------------ A.4b warnings (the WARNING voice tone is PHASE1)
    const val WARNING_PHASE1 = 21
    const val WARNING_PHASE2 = 22
    const val OVERHEAT_LOOP = 23

    // ------------------------------------------------------------------ A.3 music (streamed)
    const val MUSIC_MENU = 24
    const val MUSIC_DEEP_SPACE = 25
    const val MUSIC_LONG_HAUL = 26
    const val MUSIC_MERIDIAN_LIGHTS = 27

    /** A.5 voice lines: [VOICE_BASE] + [VoiceLine.ordinal]. */
    const val VOICE_BASE = 32

    /** Size of the sound tables (ids 0 until CAPACITY). */
    const val CAPACITY = 128

    /** The sound of voice line [line]. */
    fun voice(line: VoiceLine): Int = VOICE_BASE + line.ordinal

    /** Effect files (A.1, A.2, A.4b) in inventory order: id to asset path. */
    val SFX_FILES: Array<String> = arrayOf(
        "audio/sfx/engine.ogg",
        "audio/sfx/thrust.ogg",
        "audio/sfx/decel.ogg",
        "audio/sfx/rcs.ogg",
        "audio/sfx/rumble.ogg",
        "audio/sfx/cruise.ogg",
        "audio/sfx/fa_switch.ogg",
        "audio/sfx/fa_on.ogg",
        "audio/sfx/fa_off.ogg",
        "audio/sfx/hit1.ogg",
        "audio/sfx/hit2.ogg",
        "audio/sfx/hit3.ogg",
        "audio/sfx/warp_enter.ogg",
        "audio/sfx/warp_exit.ogg",
        "audio/sfx/warp_travel.ogg",
        "audio/sfx/boost.ogg",
        "audio/sfx/cruise_on.ogg",
        "audio/sfx/cruise_off.ogg",
        "audio/sfx/clamp.ogg",
        "audio/sfx/release.ogg",
        "audio/sfx/touchdown.ogg",
        "audio/sfx/warning_phase1.ogg",
        "audio/sfx/warning_phase2.ogg",
        "audio/sfx/overheat_loop.ogg",
    )

    /** Music files, ids [MUSIC_MENU] onwards. */
    val MUSIC_FILES: Array<String> = arrayOf(
        "audio/music/menu.ogg",
        "audio/music/deep_space.ogg",
        "audio/music/long_haul.ogg",
        "audio/music/meridian_lights.ogg",
    )

    /** The dry recording of [line] (optional: the user's own file, `09` Q1). */
    fun voicePath(line: VoiceLine): String = "audio/voice/" + line.name + ".ogg"

    /** Short name of a sound for logs and the "audio" status. */
    fun name(sound: Int): String = when {
        sound in SFX_FILES.indices -> SFX_FILES[sound].substringAfterLast('/').substringBefore('.')
        sound in MUSIC_MENU until MUSIC_MENU + MUSIC_FILES.size ->
            "music/" + MUSIC_FILES[sound - MUSIC_MENU].substringAfterLast('/').substringBefore('.')
        sound >= VOICE_BASE && sound - VOICE_BASE < VoiceLine.COUNT -> "voice/" + VoiceLine.of(sound - VOICE_BASE).name
        else -> "#$sound"
    }

    /**
     * Declares every sound with [out] (each loads in the background). Not per frame. [voices]
     * false leaves the voice lines out (tests that do not need them).
     */
    fun loadAll(out: AudioOut, voices: Boolean = true) {
        for (i in SFX_FILES.indices) out.load(i, SFX_FILES[i], false)
        for (i in MUSIC_FILES.indices) out.load(MUSIC_MENU + i, MUSIC_FILES[i], true)
        if (voices) {
            for (line in VoiceLine.ALL) out.loadVoice(voice(line), voicePath(line), line.text, line.radio)
        }
    }

    // ================================================================== voice slots (8.4)

    /** The six flight layers, in [AudioConfig.LAYERS] order. */
    const val SLOT_LAYER0 = 0

    /** The warp tunnel loop. */
    const val SLOT_WARP_LOOP = 6

    /** One slot per cue name (14: Roblox gives every cue its own Sound), [AudioConfig.CUES] order. */
    const val SLOT_CUE0 = 7

    /** The announcer's line and its pre-tone. */
    const val SLOT_VOICE_LINE = 21
    const val SLOT_VOICE_TONE = 22

    /** Deck ambience, lounge, radio, hatch, motor (the deck port, 6). */
    const val SLOT_DECK0 = 23

    /** Interface sounds (6). */
    const val SLOT_UI0 = 29

    /** The flight playlist track and the menu music. */
    const val SLOT_MUSIC_FLIGHT = 35
    const val SLOT_MUSIC_MENU = 36

    /** Warnings and destruction (3). */
    const val SLOT_WARN0 = 37

    const val SLOTS = 40

    /**
     * Master gain before the limiter (`09` 8.4's "fixed headroom"). 1.0 here because the synthesised
     * files carry the headroom themselves: `synth_sfx.py` masters each one at the level that gives
     * the Roblox gains their intended balance (a 0.4 headroom on full-scale files would push the
     * quiet-gain layers Thrust 0.22 and Decel 0.18 beyond full scale). The limiter catches the rest.
     */
    const val HEADROOM = 1f
}
