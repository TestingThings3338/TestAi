package com.starwake.core.audio

/**
 * The sound output the platform provides (`09` 8.4 and 9.1; Roblox's SoundService). The game thread
 * calls only these non-allocating setters and triggers on fixed **voice slots** ([SoundBank] slot
 * constants); the platform mixes on its own thread (Android: one AudioTrack fed by [Mixer]; desktop
 * harness: a recorder). Sounds are numbered ([SoundBank] ids) and loaded once in the background: a
 * sound that is not loaded yet is silent, and the game never waits for audio.
 *
 * Semantics follow the Roblox `Sound` the Lua uses: [play] restarts a slot from [play]'s `startAt`
 * (seconds into the file); [isPlaying] is false while paused and after a one-shot ended; [position]
 * is the playback position in seconds of the file.
 */
interface AudioOut {
    /**
     * Declares sound [sound] as the asset [path] ("audio/sfx/engine.ogg") and starts loading it in
     * the background. [stream]: decode while playing (music) instead of all at once. Not per frame.
     */
    fun load(sound: Int, path: String, stream: Boolean)

    /**
     * Declares voice line [sound]: the user's dry recording at [path] when it exists, else (Android)
     * the device text-to-speech engine speaks [text]; either way the Computer or, with [radio], the
     * Control effect chain ([VoiceFx]) is applied and the line is loudness-normalised (DECISIONS 38).
     * A platform without either leaves the line unloaded (silent). Not per frame.
     */
    fun loadVoice(sound: Int, path: String, text: String, radio: Boolean) = load(sound, path, false)

    /** The sound is decoded (or, for a stream, ready to start) and can be heard. */
    fun isLoaded(sound: Int): Boolean

    /** Loading failed for good (missing or unreadable file); it will never load. */
    fun failed(sound: Int): Boolean

    /** Length of the sound in seconds, 0 until loaded (Roblox `TimeLength`). */
    fun length(sound: Int): Float

    /**
     * Starts [sound] in [slot] from [startAt] seconds, at linear [gain] and playback [rate] (Roblox
     * `PlaybackSpeed`: pitch and speed together), looping when [loop], through [bus]. Replaces what
     * the slot played. A one-shot that is not loaded yet is dropped (silent); a loop waits for it.
     */
    fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int)

    /** New target gain and rate of what [slot] plays (smoothed by the mixer, no clicks). */
    fun set(slot: Int, gain: Float, rate: Float)

    /** Stops [slot] (with a few milliseconds of fade against the click). */
    fun stop(slot: Int)

    /** Holds [slot] at its position (Roblox `Pause`); [resume] continues there. */
    fun pause(slot: Int)

    fun resume(slot: Int)

    /** Playing and not paused (Roblox `IsPlaying`). */
    fun isPlaying(slot: Int): Boolean

    /** Playback position of [slot] in seconds into its sound (Roblox `TimePosition`). */
    fun position(slot: Int): Float

    /** Gain of [bus] ([Bus]): the setting volumes, master and mutes (smoothed by the mixer). */
    fun setBusGain(bus: Int, gain: Float)

    /** Holds every slot of [bus] (the pause menu); clearing it continues them where they were. */
    fun setBusPaused(bus: Int, paused: Boolean) {}
}

/** The mixer buses (`09` 8.4). */
object Bus {
    /** Flight layers, cues, loops: `MasterVolume x EffectsVolume`, muted on the decks and in the crash cinematic. */
    const val SFX = 0

    /** The announcer: `Voice.Volume 1.6 x EffectsVolume`. */
    const val VOICE = 1

    /** Flight and menu music: `MusicVolume`. */
    const val MUSIC = 2

    /** Interface sounds: `EffectsVolume`. */
    const val UI = 3

    /** Deck ambience, hatch and lift (D5: through EFFECTS, not muted with the ship); for the deck port. */
    const val DECK = 4

    const val COUNT = 5

    private val NAMES = arrayOf("SFX", "VOICE", "MUSIC", "UI", "DECK")

    fun name(bus: Int): String = NAMES.getOrElse(bus) { "?" }
}

/** No sound at all (a platform without audio): nothing loads, nothing plays, every line stays silent. */
object NullAudioOut : AudioOut {
    override fun load(sound: Int, path: String, stream: Boolean) {}
    override fun isLoaded(sound: Int): Boolean = false
    override fun failed(sound: Int): Boolean = false
    override fun length(sound: Int): Float = 0f
    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {}
    override fun set(slot: Int, gain: Float, rate: Float) {}
    override fun stop(slot: Int) {}
    override fun pause(slot: Int) {}
    override fun resume(slot: Int) {}
    override fun isPlaying(slot: Int): Boolean = false
    override fun position(slot: Int): Float = 0f
    override fun setBusGain(bus: Int, gain: Float) {}
}
