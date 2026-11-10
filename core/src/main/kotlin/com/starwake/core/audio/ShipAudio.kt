package com.starwake.core.audio

import com.starwake.core.math.Rng
import com.starwake.core.math.clamp
import com.starwake.core.math.smooth
import com.starwake.core.ui.TextBuf
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The audio modules' clock (Roblox `os.clock()`), seconds. The owner advances it while the
 * simulation runs, also on frames where a module is not updated (decks, the cinematic): that gap is
 * what ShipAudio's resync and the callouts' resume detect.
 */
class AudioClock {
    var now = 0.0
}

/** Roblox docking states as ShipAudio and the callouts see them (`08` ShipDocking; NONE = nil). */
object DockState {
    const val NONE = -1
    const val FREE = 0
    const val REQUESTING = 1
    const val GRANTED = 2
    const val AUTO = 3
    const val CLAMPING = 4
    const val DOCKED = 5
    const val LAUNCHING = 6

    private val NAMES = arrayOf("FREE", "REQUESTING", "GRANTED", "AUTO", "CLAMPING", "DOCKED", "LAUNCHING")

    fun name(s: Int): String = NAMES.getOrElse(s) { "-" }
}

/**
 * The flight state ShipAudio reads once per flight frame (the Roblox `state` table of
 * `ShipAudio:update`, `09` 2.12.1). Reused, filled by the session adapter. Fields Roblox lets be nil
 * have a "has" flag or a NONE value.
 */
class AudioState {
    /** The throttle lever, -0.25..1 (abs() drives the engine). */
    var throttle = 0f

    /** m/s. */
    var speed = 0f

    /** Thrust.MaxSpeed (1000): the rumble's reference. */
    var maxSpeed = AudioConfig.THRUST_MAX_SPEED

    /** m/s², + = speeding up along the nose. */
    var forwardAccel = 0f

    /** Rotation input after the dead zone, 0..1, and roll input 0..1. */
    var rotationAmount = 0f
    var rollAmount = 0f

    /** The hull's TopSpeed (handling), m/s; <= 0 = absent (Thrust.MaxSpeed x 0.25 then). */
    var topSpeed = 0f

    /** The touchdown sink rate source: m/s towards the ground (Roblox `max(-velocity.Y, 0)`); NaN = no velocity. */
    var sinkRate = 0f

    var boosting = false
    var cruising = false

    /** Flight Assist; [hasFlightAssist] false = nil (no FA events). */
    var flightAssist = true
    var hasFlightAssist = true

    /** [DockState]; [DockState.NONE] = nil (no docking events). */
    var dockState = DockState.NONE

    /** The ship rests on terrain. */
    var landed = false

    /** In the jump tunnel. */
    var warp = false

    /** A crash runs. */
    var crashing = false
}

/**
 * Layered ship sound and the music rotation, a 1:1 port of Roblox `ShipAudio` (`09` 2.12):
 * - six loops ([AudioConfig.LAYERS]) start once and only move in volume and pitch, every value
 *   smoothed ([update]);
 * - cues with fades and MaxTime ([playCue], [stopCue]), the warp loop ([startLoop], [stopLoop]),
 *   hull hits ([playHit]) and touchdowns ([playTouchdown]);
 * - the flight sound events (boost, Flight Assist, cruise on/off with delay and cooldown,
 *   touchdowns, clamps closed/open) with the resync and quiet rules;
 * - the music: a shuffle bag with gaps, fades, pause/resume at the same position, LoadTimeout, hush
 *   ([updateMusic], on its own tick also where [update] rests).
 *
 * [clock] is `os.clock()`. Game thread; no allocation after construction.
 */
class ShipAudio(
    private val out: AudioOut,
    private val clock: AudioClock,
    /** Roblox `Random.new()` / `math.random` (seed it in tests). */
    private val rng: Rng = Rng(System.nanoTime()),
) {
    // ---- groups (`new`, :55)
    var muted = false
        private set
    private var masterVolume = AudioConfig.MASTER_VOLUME
    private var effectsVolume = 1f
    private var musicSetting = 1f

    /** Audio.Music.Volume (setMusicVolume writes it). */
    var musicVolume = AudioConfig.MUSIC_VOLUME
        private set

    // ---- layers
    private val volumes = FloatArray(AudioConfig.LAYERS.size)
    private val pitches = FloatArray(AudioConfig.LAYERS.size) { AudioConfig.LAYERS[it].pitchMin }
    private var started = false

    // ---- cues (`cues`, `cueFades`, `cueStops`)
    private val cueVolume = FloatArray(AudioConfig.CUES.size) { AudioConfig.CUES[it].volume }
    private val cueRate = FloatArray(AudioConfig.CUES.size) { AudioConfig.CUES[it].pitch }
    private val fading = BooleanArray(AudioConfig.CUES.size)
    private val fadeFrom = FloatArray(AudioConfig.CUES.size)
    private val fadeElapsed = FloatArray(AudioConfig.CUES.size)
    private val fadeTime = FloatArray(AudioConfig.CUES.size)
    private val stopAt = DoubleArray(AudioConfig.CUES.size) { Double.NaN }
    private var lastHit = Double.NEGATIVE_INFINITY

    // ---- the warp loop
    private var loopTarget = 0f
    private var loopVolume = 0f
    private var loopPlaying = false

    // ---- events (`self.events`)
    private var evClock = Double.NaN
    private var evQuietUntil = 0.0
    private var evBoosting = false
    private var evFlightAssist = -1 // -1 nil, 0 off, 1 on
    private var evCruiseOff = 0f
    private var evCruising = false
    private var evEngagedAt = Double.NEGATIVE_INFINITY
    private var evCruiseSince = Double.NaN
    private var evLanded = false
    private var evAirTime = 0f
    private var evSink = 0f
    private var evDockState = DockState.NONE
    private var evSpeed = 0f
    private var evSpeedKnown = false
    private var evTouchdownAt = Double.NEGATIVE_INFINITY

    // ---- music (`buildMusic`, :214)
    private class Track(val name: String, val sound: Int, val gain: Float, val looped: Boolean) {
        var paused = false
    }

    private val tracks: Array<Track>
    private val bag: Array<Track?>
    private var bagSize = 0
    var musicEnabled = AudioConfig.MUSIC_ENABLED
        private set
    var musicHushed = false
        private set
    private var musicPlaying = false // musicState: false = GAP, true = PLAYING
    private var musicTimer = AudioConfig.MUSIC_FIRST_GAP
    private var musicTrack: Track? = null
    private var musicNext: Track? = null
    private var musicLast: Track? = null

    /** The smoothed fade 0..1 of the current track. */
    var musicLevel = 0f
        private set
    private var musicSince = 0f
    private var musicWait = 0f
    private var musicSkip = false

    init {
        val list = ArrayList<Track>()
        for (e in AudioConfig.PLAYLIST) if (e.sound >= 0) list.add(Track(e.name, e.sound, e.gain, false))
        if (list.isEmpty()) list.add(Track("Music", AudioConfig.MUSIC_FALLBACK, 1f, true))
        tracks = list.toTypedArray()
        bag = arrayOfNulls(tracks.size)
        queueNextTrack()
    }

    // ================================================================== groups and settings

    /** Starts the six layers (looped, volume 0, PitchMin), as `ShipAudio.new` does. Not per frame. */
    fun start() {
        if (started) return
        started = true
        for (i in AudioConfig.LAYERS.indices) {
            val layer = AudioConfig.LAYERS[i]
            volumes[i] = 0f
            pitches[i] = layer.pitchMin
            out.play(SoundBank.SLOT_LAYER0 + i, layer.sound, 0f, layer.pitchMin, true, 0f, Bus.SFX)
        }
    }

    /** Stops every ship sound and forgets the event state (the flight ends: back to the menu). */
    fun stopAll() {
        started = false
        for (i in AudioConfig.LAYERS.indices) out.stop(SoundBank.SLOT_LAYER0 + i)
        for (i in AudioConfig.CUES.indices) {
            out.stop(SoundBank.SLOT_CUE0 + i)
            fading[i] = false
            stopAt[i] = Double.NaN
            cueVolume[i] = AudioConfig.CUES[i].volume
        }
        out.stop(SoundBank.SLOT_WARP_LOOP)
        loopPlaying = false
        loopTarget = 0f
        loopVolume = 0f
        resetEvents()
    }

    private fun resetEvents() {
        evClock = Double.NaN
        evQuietUntil = 0.0
        evBoosting = false
        evFlightAssist = -1
        evCruiseOff = 0f
        evCruising = false
        evEngagedAt = Double.NEGATIVE_INFINITY
        evCruiseSince = Double.NaN
        evLanded = false
        evAirTime = 0f
        evSink = 0f
        evDockState = DockState.NONE
        evSpeed = 0f
        evSpeedKnown = false
        evTouchdownAt = Double.NEGATIVE_INFINITY
    }

    /**
     * The settings (Roblox ShipSettings EffectsVolume and MusicVolume, plus the port's master): the
     * effects group = master x Audio.MasterVolume x effects (0 while muted), the music group =
     * master x music. Applied live; sends only changes.
     */
    fun setVolumes(master: Float, effects: Float, music: Float) {
        if (master == masterVolume && effects == effectsVolume && music == musicSetting) return
        masterVolume = master
        effectsVolume = effects
        musicSetting = music
        applyGroups()
    }

    private fun applyGroups() {
        out.setBusGain(Bus.SFX, if (muted) 0f else masterVolume * AudioConfig.MASTER_VOLUME * effectsVolume)
        out.setBusGain(Bus.MUSIC, masterVolume * musicSetting)
    }

    /** Sends the group gains (at start-up). */
    fun applyVolumes() = applyGroups()

    /** On the decks and in the crash cinematic the ship is silent; the music plays on (`setMuted`, :815). */
    fun setMuted(m: Boolean) {
        if (m == muted) return
        muted = m
        applyGroups()
    }

    /** setMasterVolume (:807): the effects group directly (debug). */
    fun setMasterVolume(volume: Float) {
        out.setBusGain(Bus.SFX, clamp(volume, 0f, 1f))
    }

    /** setMusicVolume (:820): writes Music.Volume; updateMusic picks it up. */
    fun setMusicVolume(volume: Float) {
        musicVolume = clamp(volume, 0f, 1f)
    }

    /** J / the cockpit's Music button: fade and pause, never stop (:825). */
    fun setMusicEnabled(enabled: Boolean) {
        musicEnabled = enabled
    }

    /** Decks with their own music and the crash cinematic hush the flight music (:836). */
    fun setMusicHush(hushed: Boolean) {
        musicHushed = hushed
    }

    // ================================================================== layers (`applyLayer`, `update`)

    private fun applyLayer(dt: Float, i: Int, amountIn: Float) {
        val layer = AudioConfig.LAYERS[i]
        val amount = clamp(amountIn, 0f, 1f)
        val targetVolume = amount * layer.volume
        val targetPitch = layer.pitchMin + (layer.pitchMax - layer.pitchMin) * amount
        volumes[i] = smooth(volumes[i], targetVolume, layer.fadeTime, dt)
        pitches[i] = smooth(pitches[i], targetPitch, layer.pitchTime, dt)
        out.set(SoundBank.SLOT_LAYER0 + i, volumes[i], pitches[i])
    }

    /** Smoothed volume of layer [i] (tests, status). */
    fun layerVolume(i: Int): Float = volumes[i]

    /** Smoothed pitch of layer [i]. */
    fun layerPitch(i: Int): Float = pitches[i]

    /** Once per flight frame (`ShipClient.lua:2911`). */
    fun update(dt: Float, s: AudioState) {
        val speedFraction = clamp(s.speed / max(s.maxSpeed, 1e-3f), 0f, 1f)
        val throttleFraction = clamp(abs(s.throttle), 0f, 1f)

        // Engine: follows the LEVER, so the engine spools before the ship moves
        applyLayer(dt, AudioConfig.L_ENGINE, engineAmount(throttleFraction))

        // Thrust / Decel: mutually exclusive, from the real acceleration
        val accelNorm = s.forwardAccel / max(AudioConfig.ACCEL_REFERENCE, 1e-3f)
        applyLayer(dt, AudioConfig.L_THRUST, max(accelNorm, 0f))
        applyLayer(dt, AudioConfig.L_DECEL, max(-accelNorm, 0f))

        // RCS: rotation and roll both count
        applyLayer(dt, AudioConfig.L_RCS, max(s.rotationAmount, s.rollAmount * AudioConfig.RCS_ROLL_WEIGHT))

        updateLoops(dt)
        updateCueFades(dt)

        // Rumble: grows more than linearly with speed
        applyLayer(dt, AudioConfig.L_RUMBLE, rumbleAmount(speedFraction))

        // Cruise: only in cruise and not in the tunnel; quiet at top speed, louder per tenfold speed
        val cruiseAmount = if (s.cruising && !s.warp) cruiseAmount(s.speed, s.topSpeed) else 0f
        applyLayer(dt, AudioConfig.L_CRUISE, cruiseAmount)

        updateEvents(dt, s)
    }

    // ================================================================== cues, loops, hits

    /** Plays cue [index] ([AudioConfig.CUES]) from its StartTime, at Volume x [volumeScale] (`playCue`, :627). */
    fun playCue(index: Int, volumeScale: Float = 1f) {
        if (index !in AudioConfig.CUES.indices) return
        val cue = AudioConfig.CUES[index]
        // a running fade-out no longer counts: it would choke the fresh start
        fading[index] = false
        cueVolume[index] = cue.volume * volumeScale
        cueRate[index] = cue.pitch
        out.play(SoundBank.SLOT_CUE0 + index, cue.sound, cueVolume[index], cue.pitch, false, cue.startTime, Bus.SFX)
        stopAt[index] = if (cue.maxTime > 0f) clock.now + cue.maxTime else Double.NaN
    }

    /** Fades cue [index] out linearly over [fade] s, then stops it (`stopCue`, :650). */
    fun stopCue(index: Int, fade: Float = AudioConfig.CUE_FADE_TIME) {
        if (index !in AudioConfig.CUES.indices) return
        stopAt[index] = Double.NaN
        if (!out.isPlaying(SoundBank.SLOT_CUE0 + index) || fading[index]) return
        fading[index] = true
        fadeFrom[index] = cueVolume[index]
        fadeElapsed[index] = 0f
        fadeTime[index] = max(fade, 0.01f)
    }

    /** The cue is fading out (tests). */
    fun cueFading(index: Int): Boolean = fading[index]

    private fun updateCueFades(dt: Float) {
        val now = clock.now
        // cues with MaxTime: fade at the planned time
        for (i in AudioConfig.CUES.indices) {
            val at = stopAt[i]
            if (!at.isNaN() && now >= at) {
                stopAt[i] = Double.NaN
                stopCue(i, AudioConfig.CUES[i].fadeOut)
            }
        }
        for (i in AudioConfig.CUES.indices) {
            if (!fading[i]) continue
            val slot = SoundBank.SLOT_CUE0 + i
            fadeElapsed[i] += dt
            val t = fadeElapsed[i] / fadeTime[i]
            if (t >= 1f || !out.isPlaying(slot)) {
                out.stop(slot)
                // the configured level again for the next play
                cueVolume[i] = AudioConfig.CUES[i].volume
                fading[i] = false
            } else {
                cueVolume[i] = fadeFrom[i] * (1f - t)
                out.set(slot, cueVolume[i], cueRate[i])
            }
        }
    }

    /** Starts the warp tunnel loop and fades it in (`startLoop`, :467). */
    fun startLoop() {
        loopTarget = 1f
        if (!loopPlaying || !out.isPlaying(SoundBank.SLOT_WARP_LOOP)) {
            loopPlaying = true
            out.play(SoundBank.SLOT_WARP_LOOP, SoundBank.WARP_TRAVEL, loopVolume * AudioConfig.WARP_TRAVEL_VOLUME, 1f, true, 0f, Bus.SFX)
        }
    }

    /** Fades the loop out; [instant] cuts it at once, where another sound takes over (`stopLoop`, :481). */
    fun stopLoop(instant: Boolean = false) {
        loopTarget = 0f
        if (instant) {
            loopVolume = 0f
            loopPlaying = false
            out.stop(SoundBank.SLOT_WARP_LOOP)
        }
    }

    private fun updateLoops(dt: Float) {
        if (!loopPlaying) return
        loopVolume = smooth(loopVolume, loopTarget, AudioConfig.WARP_TRAVEL_FADE, dt)
        out.set(SoundBank.SLOT_WARP_LOOP, loopVolume * AudioConfig.WARP_TRAVEL_VOLUME, 1f)
        if (loopTarget == 0f && loopVolume < 0.01f) {
            loopPlaying = false
            out.stop(SoundBank.SLOT_WARP_LOOP)
        }
    }

    /**
     * A hull hit: a random MetalHit variant at 2.4 x clamp([strength], 0.15, 1.6) and pitch
     * 0.9..1.15, refused within [minGap] of the last one (`playHit`, :501).
     */
    fun playHit(strength: Float = 1f, minGap: Float = AudioConfig.HIT_COOLDOWN): Boolean {
        val now = clock.now
        if (now - lastHit < minGap) return false
        lastHit = now
        val pick = AudioConfig.C_METAL_HIT1 + rng.nextInt(3)
        val cue = AudioConfig.CUES[pick]
        fading[pick] = false
        cueVolume[pick] = cue.volume * clamp(strength, 0.15f, 1.6f)
        val pitch = 0.9f + rng.nextFloat() * 0.25f
        cueRate[pick] = pitch
        out.play(SoundBank.SLOT_CUE0 + pick, cue.sound, cueVolume[pick], pitch, false, 0f, Bus.SFX)
        return true
    }

    /** A touchdown, at most every TouchdownCooldown s, louder with the sink rate (`playTouchdown`, :795). */
    fun playTouchdown(sink: Float) {
        val now = clock.now
        if (now - evTouchdownAt < AudioConfig.TOUCHDOWN_COOLDOWN) return
        evTouchdownAt = now
        playCue(AudioConfig.C_TOUCHDOWN, touchdownScale(sink))
    }

    // ================================================================== flight sound events (`updateEvents`, :702)

    private fun updateEvents(dt: Float, s: AudioState) {
        val now = clock.now
        val resync = evClock.isNaN() || now - evClock > AudioConfig.RESYNC_GAP
        evClock = now

        if (s.warp || s.crashing) evQuietUntil = now + AudioConfig.QUIET_TIME
        val quiet = resync || now < evQuietUntil

        // Boost: on ignition
        if (s.boosting && !evBoosting && !quiet) playCue(AudioConfig.C_BOOST)
        evBoosting = s.boosting

        // Flight Assist: the click and the chirp (the spoken line is the session's)
        if (s.hasFlightAssist) {
            val fa = if (s.flightAssist) 1 else 0
            if (evFlightAssist >= 0 && fa != evFlightAssist && !resync) {
                playCue(AudioConfig.C_FLIGHT_ASSIST)
                playCue(if (s.flightAssist) AudioConfig.C_FLIGHT_ASSIST_ON else AudioConfig.C_FLIGHT_ASSIST_OFF)
            }
            evFlightAssist = fa
        }

        // Cruise: on at once, off only after CruiseOffDelay without
        val rawCruise = s.cruising && !s.warp && !s.crashing
        evCruiseOff = if (rawCruise) 0f else evCruiseOff + dt
        val cruising = rawCruise || (evCruising && evCruiseOff < AudioConfig.CRUISE_OFF_DELAY)
        if (cruising != evCruising) {
            if (cruising) {
                if (!quiet && now - evEngagedAt >= AudioConfig.CRUISE_COOLDOWN) {
                    playCue(AudioConfig.C_CRUISE_ENGAGE)
                    evEngagedAt = now
                }
                evCruiseSince = now
            } else if (!quiet && now - (if (evCruiseSince.isNaN()) now else evCruiseSince) >= AudioConfig.CRUISE_MIN_TIME) {
                // the engage sound must not run into the disengage
                stopCue(AudioConfig.C_CRUISE_ENGAGE)
                playCue(AudioConfig.C_CRUISE_DISENGAGE)
            }
        }
        evCruising = cruising

        // Touchdown on terrain: the sink rate of the frame before (the contact frame has zeroed it)
        if (s.landed) {
            if (!evLanded && !quiet && evAirTime >= AudioConfig.TOUCHDOWN_AIR_TIME) playTouchdown(evSink)
            evAirTime = 0f
        } else {
            evAirTime = if (resync) 0f else evAirTime + dt
            evSink = if (s.sinkRate.isNaN()) 0f else max(s.sinkRate, 0f)
        }
        evLanded = s.landed

        // Docking: only real transitions; a docked start jumps straight to DOCKED and stays silent
        val dock = s.dockState
        if (dock != DockState.NONE) {
            val previous = evDockState
            if (dock != previous && !s.crashing) {
                if (dock == DockState.CLAMPING && previous != DockState.NONE) {
                    playTouchdown(if (evSpeedKnown) evSpeed else 0f) // on the pad, at the speed of the frame before
                } else if (dock == DockState.DOCKED && previous == DockState.CLAMPING) {
                    playCue(AudioConfig.C_DOCK_CLAMP)
                } else if (dock == DockState.LAUNCHING) {
                    // LAUNCHING always follows DOCKED; the first frame after the hangar often sees only LAUNCHING
                    playCue(AudioConfig.C_DOCK_RELEASE)
                }
            }
            evDockState = dock
        }
        evSpeed = s.speed
        evSpeedKnown = true
    }

    // ================================================================== music (`updateMusic`, :350)

    /** Plans the next track and preloads it; an empty bag is reshuffled without repeating the last (`queueNextTrack`, :266). */
    private fun queueNextTrack() {
        // drop failed tracks from the bag
        var n = 0
        for (i in 0 until bagSize) {
            val t = bag[i]!!
            if (!isFailed(t)) bag[n++] = t
        }
        for (i in n until bagSize) bag[i] = null
        bagSize = n
        if (bagSize == 0) {
            for (t in tracks) if (!isFailed(t)) bag[bagSize++] = t
            // Fisher-Yates as the Lua (1-based i from #bag down to 2, j in 1..i)
            for (i in bagSize downTo 2) {
                val j = rng.nextInt(i) + 1
                val tmp = bag[i - 1]
                bag[i - 1] = bag[j - 1]
                bag[j - 1] = tmp
            }
            if (bagSize > 1 && bag[bagSize - 1] === musicLast) {
                val j = rng.nextInt(bagSize - 1) + 1
                val tmp = bag[bagSize - 1]
                bag[bagSize - 1] = bag[j - 1]
                bag[j - 1] = tmp
            }
        }
        val track = if (bagSize > 0) bag[--bagSize] else null
        if (track != null) bag[bagSize] = null
        musicNext = track
        musicWait = 0f
    }

    private fun isFailed(t: Track): Boolean = out.failed(t.sound)

    private fun startTrack(t: Track) {
        musicPlaying = true
        musicTrack = t
        musicNext = null
        musicLast = t
        musicLevel = 0f
        musicSince = 0f
        t.paused = false
        out.play(SoundBank.SLOT_MUSIC_FLIGHT, t.sound, 0f, 1f, t.looped, 0f, Bus.MUSIC)
    }

    /** The track ended or faded out: silence, then the next (`finishTrack`, :329). */
    private fun finishTrack() {
        val t = musicTrack
        if (t != null) {
            out.stop(SoundBank.SLOT_MUSIC_FLIGHT)
            t.paused = false
        }
        musicPlaying = false
        musicTrack = null
        musicLevel = 0f
        musicSkip = false
        musicTimer = AudioConfig.MUSIC_GAP_MIN + rng.nextFloat() * (AudioConfig.MUSIC_GAP_MAX - AudioConfig.MUSIC_GAP_MIN)
        queueNextTrack()
    }

    /** One tick of the rotation, every frame, also on the decks where [update] rests. */
    fun updateMusic(dt: Float) {
        val audible = musicEnabled && !musicHushed
        if (!musicPlaying) {
            musicTimer -= dt
            val next = musicNext
            if (next != null && musicTimer <= 0f) {
                if (isFailed(next)) {
                    queueNextTrack()
                } else if (!out.isLoaded(next.sound)) {
                    // loading too long: another one; this one is back in the next shuffle
                    musicWait += dt
                    if (musicWait > AudioConfig.MUSIC_LOAD_TIMEOUT) queueNextTrack()
                } else if (audible) {
                    startTrack(next)
                }
            }
            if (!musicPlaying) return
        }

        val t = musicTrack ?: return
        val slot = SoundBank.SLOT_MUSIC_FLIGHT
        musicSince += dt

        // played to the end (the own pause does not count)
        if (!t.paused && !out.isPlaying(slot) && musicSince > 0.5f) {
            finishTrack()
            return
        }

        // fade out shortly before the end, a skipped one at once; the looped fallback never ends
        val fade = max(AudioConfig.MUSIC_FADE_TIME, 0.1f)
        val length = out.length(t.sound)
        val ending = musicSkip || (!t.looped && length > 0f && out.position(slot) >= length - fade)
        val target = if (audible && !ending) 1f else 0f

        // tau a third of the fade: 95 % after FadeTime
        musicLevel = smooth(musicLevel, target, fade / 3f, dt)

        if (target == 1f && t.paused) {
            t.paused = false
            musicSince = 0f
            out.resume(slot)
        } else if (target == 0f && musicLevel < 0.005f) {
            if (ending) {
                finishTrack()
                return
            } else if (!t.paused) {
                // faded out: hold here, continue at the same point later
                t.paused = true
                out.pause(slot)
            }
        }
        out.set(slot, musicLevel * musicVolume * t.gain, 1f)
    }

    /** Debug "music skip" (`skipMusic`, :423): in a gap the next starts now; a playing track fades out. */
    fun skipMusic() {
        if (musicTrack != null) musicSkip = true else musicTimer = 0f
    }

    /** The playing (or paused) track's name, null in a gap. */
    val musicTrackName: String? get() = musicTrack?.name

    /** Seconds left in the gap (tests). */
    val musicGapLeft: Float get() = if (musicPlaying) 0f else musicTimer

    val musicNextName: String? get() = musicNext?.name

    val musicIsPlaying: Boolean get() = musicPlaying

    val musicPaused: Boolean get() = musicTrack?.paused == true

    /** Short status for the "audio" debug command (`getMusicStatus`, :433). */
    fun musicStatus(buf: TextBuf): TextBuf {
        val t = musicTrack
        if (t != null) {
            buf.append(if (t.paused) "PAUSE " else "TRACK ").append(t.name).append(' ')
            clockText(buf, out.position(SoundBank.SLOT_MUSIC_FLIGHT)).append('/')
            clockText(buf, out.length(t.sound)).append(" fade ").append(musicLevel, 2)
        } else {
            buf.append("GAP ")
            clockText(buf, musicTimer).append(" next ").append(musicNext?.name ?: "-")
            val n = musicNext
            if (n != null) buf.append(if (out.isLoaded(n.sound)) " (loaded)" else " (loading)")
        }
        buf.append(" | ").append(tracks.size).append(" tracks")
        if (!musicEnabled) buf.append(" | OFF")
        if (musicHushed) buf.append(" | hushed")
        return buf
    }

    private fun clockText(buf: TextBuf, seconds: Float): TextBuf {
        val s = max(seconds, 0f).toInt()
        buf.append(s / 60).append(':')
        if (s % 60 < 10) buf.append('0')
        return buf.append(s % 60)
    }

    companion object {
        /** Engine amount: Idle + (1 - Idle) x |throttle|. */
        fun engineAmount(throttleFraction: Float): Float =
            AudioConfig.ENGINE_IDLE + (1f - AudioConfig.ENGINE_IDLE) * throttleFraction

        /** Rumble amount: Idle + (1 - Idle) x speedFraction^1.3. */
        fun rumbleAmount(speedFraction: Float): Float =
            AudioConfig.RUMBLE_IDLE + (1f - AudioConfig.RUMBLE_IDLE) * speedFraction.pow(AudioConfig.RUMBLE_SPEED_CURVE)

        /**
         * Cruise amount while cruising: quiet at top speed, louder with every tenfold speed, full at
         * FullSpeed: top = max(topSpeed or 0.25 x Thrust.MaxSpeed, 1), span = ln(max(Full/top, 1.01)),
         * share = clamp(ln(max(speed, top)/top)/span), Idle + (1 - Idle) x share.
         */
        fun cruiseAmount(speed: Float, topSpeed: Float): Float {
            val top = max(if (topSpeed > 0f) topSpeed else AudioConfig.THRUST_MAX_SPEED * 0.25f, 1f)
            val span = ln(max(AudioConfig.CRUISE_FULL_SPEED / top, 1.01f))
            val share = clamp(ln(max(speed, top) / top) / span, 0f, 1f)
            return AudioConfig.CRUISE_IDLE + (1f - AudioConfig.CRUISE_IDLE) * share
        }

        /** Touchdown scale: clamp(MinScale + sink / SafeLandingSpeed, MinScale, MaxScale). */
        fun touchdownScale(sink: Float): Float {
            val safe = max(AudioConfig.SAFE_LANDING_SPEED, 1f)
            return clamp(AudioConfig.TOUCHDOWN_MIN_SCALE + sink / safe, AudioConfig.TOUCHDOWN_MIN_SCALE, AudioConfig.TOUCHDOWN_MAX_SCALE)
        }
    }
}
