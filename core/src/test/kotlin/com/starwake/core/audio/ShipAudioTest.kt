package com.starwake.core.audio

import com.starwake.core.math.Rng
import com.starwake.core.math.smooth
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ShipAudio against `09` 2.12 and Appendix B.3: the layer formulas, the smoothing, the flight sound
 * events (boost, Flight Assist, cruise with its delay, minimum time and cooldown, touchdowns, clamps,
 * resync and quiet), cues with fades and MaxTime, hits, the warp loop and the music rotation.
 */
class ShipAudioTest {
    private val out = FakeAudioOut().also { it.loadAllWith(200f) }
    private val clock = AudioClock()
    private val audio = ShipAudio(out, clock, Rng(42))
    private val st = AudioState()
    private val dt = 1f / 60f

    private fun near(expected: Float, actual: Float, eps: Float, what: String = "") =
        assertTrue(abs(expected - actual) <= eps, "$what expected $expected ± $eps, was $actual")

    /** One flight frame: the clock runs, the audio updates, the sounds play on. */
    private fun frame(n: Int = 1) {
        repeat(n) {
            clock.now += dt
            audio.update(dt, st)
            out.advance(dt)
        }
    }

    /** Time passes without ShipAudio.update (decks, a crash, a load pause). */
    private fun gap(seconds: Float) {
        clock.now += seconds
        out.advance(seconds)
    }

    private fun cueSlot(i: Int) = SoundBank.SLOT_CUE0 + i

    // ================================================================== B.3 vectors

    @Test
    fun appendixB3Vectors() {
        // engine amount and pitch target
        for ((thr, amount, pitch) in listOf(Triple(0f, 0.55f, 0.993f), Triple(0.5f, 0.775f, 1.071f), Triple(1f, 1f, 1.15f))) {
            val a = ShipAudio.engineAmount(thr)
            near(amount, a, 1e-4f, "engine amount at throttle $thr")
            near(pitch, 0.80f + (1.15f - 0.80f) * a, 6e-4f, "engine pitch target at throttle $thr")
        }
        // rumble amount by speed fraction
        for ((f, v) in listOf(0f to 0.30f, 0.25f to 0.415f, 0.5f to 0.584f, 1f to 1.0f)) near(v, ShipAudio.rumbleAmount(f), 6e-4f, "rumble $f")
        // cruise amount (top 250 and 400)
        for ((s, v) in listOf(250f to 0.35f, 1000f to 0.556f, 3000f to 0.719f, 20000f to 1.0f, 50000f to 1.0f)) near(v, ShipAudio.cruiseAmount(s, 250f), 6e-4f, "cruise $s @250")
        near(0.502f, ShipAudio.cruiseAmount(1000f, 400f), 6e-4f, "cruise 1000 @400")
        near(0.685f, ShipAudio.cruiseAmount(3000f, 400f), 6e-4f, "cruise 3000 @400")
        // no topSpeed: Thrust.MaxSpeed x 0.25 = 250
        near(ShipAudio.cruiseAmount(1000f, 250f), ShipAudio.cruiseAmount(1000f, 0f), 1e-6f, "topSpeed absent")
        // touchdown scale
        for ((s, v) in listOf(0f to 0.4f, 5f to 0.532f, 10f to 0.663f, 20f to 0.926f, 30.4f to 1.2f, 80f to 1.2f)) near(v, ShipAudio.touchdownScale(s), 6e-4f, "touchdown $s")
        // smoothing alpha per 60 fps frame
        for ((tau, alpha) in listOf(0.12f to 0.1297f, 0.35f to 0.0465f, 0.6f to 0.0274f, 0.8f to 0.0206f, 1.0f to 0.0165f)) {
            near(alpha, smooth(0f, 1f, tau, 1f / 60f), 6e-5f, "alpha tau $tau")
        }
        assertEquals(1f, smooth(0.3f, 1f, 0f, dt), "tau 0 jumps to the target")
    }

    // ================================================================== layers

    @Test
    fun startPlaysTheSixLoopsSilentAtPitchMin() {
        audio.start()
        for (i in 0 until 6) {
            val slot = SoundBank.SLOT_LAYER0 + i
            assertEquals(AudioConfig.LAYERS[i].sound, out.sound[slot])
            assertTrue(out.loop[slot], "layer $i loops")
            assertEquals(0f, out.gain[slot])
            assertEquals(AudioConfig.LAYERS[i].pitchMin, out.rate[slot])
        }
        audio.start()
        assertEquals(6, out.plays, "start twice plays nothing more")
    }

    @Test
    fun everyLayerConvergesToItsFormula() {
        audio.start()
        st.throttle = -0.25f // reverse: abs() drives the engine
        st.speed = 250f // fraction 0.25 of Thrust.MaxSpeed 1000
        st.forwardAccel = 12.5f // half of AccelReference
        st.rotationAmount = 0.6f
        st.rollAmount = 1f // x RollWeight 0.8 = 0.8 > 0.6
        st.cruising = true
        st.topSpeed = 250f
        frame(60 * 20)
        fun check(i: Int, amount: Float) {
            val l = AudioConfig.LAYERS[i]
            near(amount * l.volume, audio.layerVolume(i), 2e-4f, "${l.name} volume")
            near(l.pitchMin + (l.pitchMax - l.pitchMin) * amount, audio.layerPitch(i), 2e-4f, "${l.name} pitch")
            assertEquals(audio.layerVolume(i), out.gain[SoundBank.SLOT_LAYER0 + i], "${l.name} written to its slot")
        }
        check(AudioConfig.L_ENGINE, 0.55f + 0.45f * 0.25f)
        check(AudioConfig.L_THRUST, 0.5f)
        check(AudioConfig.L_DECEL, 0f)
        check(AudioConfig.L_RCS, 0.8f)
        check(AudioConfig.L_RUMBLE, ShipAudio.rumbleAmount(0.25f))
        check(AudioConfig.L_CRUISE, ShipAudio.cruiseAmount(250f, 250f))
        // braking: Decel takes over, Thrust falls back; amounts clamp at 1
        st.forwardAccel = -100f
        st.warp = true // the cruise layer is silent in the tunnel
        frame(60 * 20)
        check(AudioConfig.L_THRUST, 0f)
        check(AudioConfig.L_DECEL, 1f)
        check(AudioConfig.L_CRUISE, 0f)
    }

    @Test
    fun layersSmoothWithTheirTimeConstants() {
        audio.start()
        st.throttle = 1f
        frame()
        // one frame from 0: target x alpha(FadeTime), pitch from PitchMin towards PitchMax x alpha(PitchTime)
        near(0.75f * (1f - exp(-dt / 0.6f)), audio.layerVolume(AudioConfig.L_ENGINE), 1e-6f, "engine volume after one frame")
        near(0.80f + (1.15f - 0.80f) * (1f - exp(-dt / 0.9f)), audio.layerPitch(AudioConfig.L_ENGINE), 1e-6f, "engine pitch after one frame")
        // after FadeTime 63 %, after 3x 95 %
        frame(35)
        near(0.75f * 0.63f, audio.layerVolume(AudioConfig.L_ENGINE), 0.75f * 0.02f, "63 % after tau")
    }

    // ================================================================== events

    @Test
    fun boostOnTheRisingEdgeOnlyAndNotWhenQuiet() {
        st.boosting = true
        frame() // first frame: resync, adopts the state
        assertFalse(out.played.contains(SoundBank.BOOST), "no boost on resync")
        st.boosting = false
        frame(10)
        st.boosting = true
        frame()
        assertEquals(1, out.played.count { it == SoundBank.BOOST })
        near(0.12f + dt, out.pos[cueSlot(AudioConfig.C_BOOST)], 1e-5f, "from StartTime 0.12")
        frame(30)
        assertEquals(1, out.played.count { it == SoundBank.BOOST }, "held: once")
        // the tunnel and QuietTime after it
        st.boosting = false
        st.warp = true
        frame(10)
        st.warp = false
        frame(30) // 0.5 s into the 1.5 s quiet
        st.boosting = true
        frame()
        assertEquals(1, out.played.count { it == SoundBank.BOOST }, "quiet after the tunnel")
        st.boosting = false
        frame(70)
        st.boosting = true
        frame()
        assertEquals(2, out.played.count { it == SoundBank.BOOST }, "after QuietTime again")
    }

    @Test
    fun flightAssistClickAndChirpButNotOnResync() {
        st.flightAssist = true
        frame(5)
        st.flightAssist = false
        frame()
        assertEquals(listOf(SoundBank.FA_SWITCH, SoundBank.FA_OFF), out.played)
        out.clearLog()
        st.flightAssist = true
        frame()
        assertEquals(listOf(SoundBank.FA_SWITCH, SoundBank.FA_ON), out.played)
        out.clearLog()
        // changed during a pause: adopted silently
        gap(1f)
        st.flightAssist = false
        frame()
        assertTrue(out.played.isEmpty(), "resync adopts FA silently")
        // nil: no events at all
        st.hasFlightAssist = false
        st.flightAssist = true
        frame(2)
        assertTrue(out.played.isEmpty())
    }

    @Test
    fun cruiseEngageDisengageWithDelayMinimumTimeAndCooldown() {
        frame(5)
        st.cruising = true
        frame()
        assertEquals(listOf(SoundBank.CRUISE_ON), out.played, "on at once")
        frame(60 * 3)
        // a short drop (< CruiseOffDelay 0.2 s) is not an off
        st.cruising = false
        frame(6) // 0.1 s
        st.cruising = true
        frame()
        assertEquals(listOf(SoundBank.CRUISE_ON), out.played, "0.1 s without cruise is still cruise")
        // off for good after >= 2 s of cruise: the engage cue fades, the disengage plays
        st.cruising = false
        frame(13) // 0.2 s
        assertEquals(listOf(SoundBank.CRUISE_ON, SoundBank.CRUISE_OFF), out.played)
        assertTrue(audio.cueFading(AudioConfig.C_CRUISE_ENGAGE) || !out.isPlaying(cueSlot(AudioConfig.C_CRUISE_ENGAGE)), "the engage cue fades out")
        out.clearLog()
        // on again within CruiseCooldown 4 s of the last engage: silent
        frame(30)
        st.cruising = true
        frame()
        assertTrue(out.played.isEmpty(), "cooldown")
        // off after less than CruiseMinTime: no disengage
        st.cruising = false
        frame(20)
        assertTrue(out.played.isEmpty(), "under the minimum time")
        frame(60 * 4)
        st.cruising = true
        frame()
        assertEquals(listOf(SoundBank.CRUISE_ON), out.played, "after the cooldown")
        // the tunnel ends cruise silently (warp makes it quiet) and so does a crash
        out.clearLog()
        frame(60 * 3)
        st.warp = true
        frame(30)
        assertTrue(out.played.isEmpty(), "cruise into the tunnel: quiet")
    }

    @Test
    fun touchdownOnTerrainUsesTheSinkRateOfTheFrameBefore() {
        frame(2)
        st.sinkRate = 10f
        frame(30) // 0.5 s airborne: too short
        st.landed = true
        st.sinkRate = 0f
        frame()
        assertTrue(out.played.isEmpty(), "under TouchdownAirTime 1 s")
        st.landed = false
        st.sinkRate = 20f
        frame(90)
        st.landed = true
        st.sinkRate = 0f // the contact frame has zeroed it
        frame()
        assertEquals(listOf(SoundBank.TOUCHDOWN), out.played)
        near(0.47f * ShipAudio.touchdownScale(20f), out.gain[cueSlot(AudioConfig.C_TOUCHDOWN)], 1e-5f, "scaled by the sink rate")
        near(0.28f + dt, out.pos[cueSlot(AudioConfig.C_TOUCHDOWN)], 1e-5f, "from StartTime 0.28")
        // TouchdownCooldown 2 s: a bounce right after is silent
        out.clearLog()
        st.landed = false
        st.sinkRate = 5f
        frame(65)
        st.landed = true
        frame()
        assertTrue(out.played.isEmpty(), "cooldown")
    }

    @Test
    fun dockingTransitions() {
        // a start already docked: silent
        st.dockState = DockState.DOCKED
        frame(3)
        assertTrue(out.played.isEmpty(), "docked start")
        // launch: the clamps open (MaxTime 1.8, FadeOut 0.6)
        st.dockState = DockState.LAUNCHING
        frame()
        assertEquals(listOf(SoundBank.RELEASE), out.played)
        out.clearLog()
        st.dockState = DockState.FREE
        frame(60)
        st.dockState = DockState.AUTO
        st.speed = 9f
        frame(30)
        // pad touchdown with the previous frame's speed, then the clamps
        st.dockState = DockState.CLAMPING
        st.speed = 0f
        frame()
        assertEquals(listOf(SoundBank.TOUCHDOWN), out.played)
        near(0.47f * ShipAudio.touchdownScale(9f), out.gain[cueSlot(AudioConfig.C_TOUCHDOWN)], 1e-5f, "pad touchdown at the speed before")
        frame(60)
        st.dockState = DockState.DOCKED
        frame()
        assertEquals(listOf(SoundBank.TOUCHDOWN, SoundBank.CLAMP), out.played)
        assertEquals(0.8f, out.rate[cueSlot(AudioConfig.C_DOCK_CLAMP)], "DockClamp at pitch 0.8")
        out.clearLog()
        // the launch from the hangar falls into a pause: still plays (docking transitions survive resync)
        gap(3f)
        st.dockState = DockState.LAUNCHING
        frame()
        assertEquals(listOf(SoundBank.RELEASE), out.played)
        // a crash suppresses them
        out.clearLog()
        st.dockState = DockState.FREE
        frame(5)
        st.crashing = true
        st.dockState = DockState.LAUNCHING
        frame()
        assertTrue(out.played.isEmpty(), "crashing")
    }

    @Test
    fun resyncAdoptsStatesSilentlyAndResetsAirTime() {
        frame(5)
        st.landed = false
        frame(120) // airborne 2 s
        gap(0.6f) // > ResyncGap 0.5
        st.boosting = true
        st.cruising = true
        st.landed = true
        frame()
        assertTrue(out.played.isEmpty(), "everything adopted silently after a gap: ${out.playedNames()}")
        // just under the gap: not a resync
        st.boosting = false
        frame(10)
        gap(0.4f)
        st.boosting = true
        frame()
        assertEquals(listOf(SoundBank.BOOST), out.played)
    }

    // ================================================================== cues, hits, loop

    @Test
    fun maxTimeFadesTheReleaseOverItsFadeOut() {
        frame(2)
        st.dockState = DockState.DOCKED
        frame()
        st.dockState = DockState.LAUNCHING
        frame()
        val slot = cueSlot(AudioConfig.C_DOCK_RELEASE)
        assertEquals(0.75f, out.gain[slot])
        frame((1.8f / dt).toInt() - 2)
        assertEquals(0.75f, out.gain[slot], "full until MaxTime")
        frame(3) // MaxTime reached: the fade starts
        frame(18) // 0.3 s into the 0.6 s fade
        near(0.75f * 0.5f, out.gain[slot], 0.75f * 0.06f, "half way")
        assertTrue(out.isPlaying(slot))
        frame(20)
        assertFalse(out.isPlaying(slot), "stopped after FadeOut")
        assertFalse(audio.cueFading(AudioConfig.C_DOCK_RELEASE))
    }

    @Test
    fun stopCueFadesLinearlyAndAReplayCancelsTheFade() {
        frame(2)
        audio.playCue(AudioConfig.C_WARP_ENTER)
        frame(10)
        audio.stopCue(AudioConfig.C_WARP_ENTER)
        val slot = cueSlot(AudioConfig.C_WARP_ENTER)
        frame(3) // 0.05 of 0.12 s
        near(2.6f * (1f - 3f * dt / 0.12f), out.gain[slot], 0.02f, "linear fade")
        audio.playCue(AudioConfig.C_WARP_ENTER)
        assertFalse(audio.cueFading(AudioConfig.C_WARP_ENTER), "a fresh play cancels the fade")
        frame(10)
        assertEquals(2.6f, out.gain[slot])
        assertTrue(out.isPlaying(slot))
        audio.stopCue(AudioConfig.C_WARP_ENTER)
        frame(8)
        assertFalse(out.isPlaying(slot), "stopped after 0.12 s")
        assertEquals(2.6f, AudioConfig.CUES[AudioConfig.C_WARP_ENTER].volume)
        // stopping a cue that does not play does nothing
        audio.stopCue(AudioConfig.C_WARP_EXIT)
        assertFalse(audio.cueFading(AudioConfig.C_WARP_EXIT))
    }

    @Test
    fun hitsKeepTheirGapClampTheirGainAndVaryPitch() {
        frame(2)
        assertTrue(audio.playHit(0.1f))
        val first = out.played.last()
        val slot = SoundBank.SLOT_CUE0 + AudioConfig.C_METAL_HIT1 + (first - SoundBank.HIT1)
        near(2.4f * 0.15f, out.gain[slot], 1e-6f, "clamped up to 0.15")
        frame(5) // 0.083 s < 0.14
        assertFalse(audio.playHit(1f), "HitCooldown")
        frame(4)
        assertTrue(audio.playHit(5f))
        val s2 = SoundBank.SLOT_CUE0 + AudioConfig.C_METAL_HIT1 + (out.played.last() - SoundBank.HIT1)
        near(2.4f * 1.6f, out.gain[s2], 1e-6f, "clamped down to 1.6")
        // a longer gap on request (station scrape 0.3 s)
        frame(10)
        assertFalse(audio.playHit(1f, 0.3f))
        frame(10)
        assertTrue(audio.playHit(1f, 0.3f))
        // variants and pitch range over many hits
        val variants = HashSet<Int>()
        repeat(60) {
            frame(10)
            audio.playHit()
            val v = out.played.last()
            variants += v
            val r = out.rate[SoundBank.SLOT_CUE0 + AudioConfig.C_METAL_HIT1 + (v - SoundBank.HIT1)]
            assertTrue(r in 0.9f..1.15f, "pitch $r")
        }
        assertEquals(setOf(SoundBank.HIT1, SoundBank.HIT2, SoundBank.HIT3), variants)
    }

    @Test
    fun theWarpLoopFadesInAndOutAndCutsHard() {
        frame(2)
        audio.startLoop()
        assertEquals(SoundBank.WARP_TRAVEL, out.sound[SoundBank.SLOT_WARP_LOOP])
        assertTrue(out.loop[SoundBank.SLOT_WARP_LOOP])
        frame(60)
        near(2.2f * (1f - exp(-1f / 0.25f)), out.gain[SoundBank.SLOT_WARP_LOOP], 0.01f, "fading in (tau 0.25)")
        audio.stopLoop()
        frame(30)
        near(2.2f * 0.982f * exp(-2f), out.gain[SoundBank.SLOT_WARP_LOOP], 0.02f, "fading out")
        frame(60)
        assertFalse(out.isPlaying(SoundBank.SLOT_WARP_LOOP), "stopped below 0.01")
        audio.startLoop()
        frame(30)
        audio.stopLoop(instant = true)
        assertFalse(out.isPlaying(SoundBank.SLOT_WARP_LOOP), "instant")
    }

    @Test
    fun groupsFollowTheSettingsAndTheMute() {
        audio.setVolumes(0.5f, 0.8f, 0.6f)
        near(0.5f * 0.8f, out.busGain[Bus.SFX], 1e-6f)
        near(0.5f * 0.6f, out.busGain[Bus.MUSIC], 1e-6f)
        audio.setMuted(true)
        assertEquals(0f, out.busGain[Bus.SFX])
        near(0.3f, out.busGain[Bus.MUSIC], 1e-6f, "the music plays on")
        audio.setMuted(false)
        near(0.4f, out.busGain[Bus.SFX], 1e-6f)
    }

    // ================================================================== music

    private fun music(seconds: Float, step: Float = 0.05f) {
        var t = 0f
        while (t < seconds) {
            audio.updateMusic(step)
            out.advance(step)
            t += step
        }
    }

    private val musicSlot = SoundBank.SLOT_MUSIC_FLIGHT

    @Test
    fun firstGapThenAFadeInToVolumeTimesGain() {
        assertFalse(audio.musicIsPlaying)
        music(2.9f)
        assertFalse(audio.musicIsPlaying, "FirstGap 3 s")
        music(0.2f)
        assertTrue(audio.musicIsPlaying)
        val name = audio.musicTrackName!!
        val gain = AudioConfig.PLAYLIST.first { it.name == name }.gain
        assertTrue(out.pos[musicSlot] < 0.3f, "from the start")
        // tau = FadeTime / 3 = 1 s: 95 % after 3 s
        music(3f)
        assertTrue(audio.musicLevel > 0.94f, "level ${audio.musicLevel}")
        music(10f)
        near(1f * AudioConfig.MUSIC_VOLUME * gain, out.gain[musicSlot], 1e-3f, "Volume x Gain")
    }

    @Test
    fun aTrackFadesBeforeItsEndThenAGapOf30To90Seconds() {
        // short tracks: 20 s each
        for (t in AudioConfig.PLAYLIST) if (t.sound >= 0) out.setLength(t.sound, 20f)
        music(3.1f)
        val first = audio.musicTrackName
        music(17.5f) // 17.6 s in: inside the last 3 s, fading
        assertTrue(audio.musicLevel < 0.9f, "fading out before the end: ${audio.musicLevel}")
        music(10f)
        assertFalse(audio.musicIsPlaying, "the track finished")
        val gap = audio.musicGapLeft
        assertTrue(gap in 20f..90f, "gap $gap (30..90 drawn, a little elapsed)")
        assertNotEquals(first, audio.musicNextName, "never the same track twice in a row")
    }

    @Test
    fun theShuffleBagNeverRepeatsTheLastTrack() {
        for (t in AudioConfig.PLAYLIST) if (t.sound >= 0) out.setLength(t.sound, 5f)
        var last: String? = null
        var count = 0
        val seen = HashMap<String, Int>()
        repeat(400) {
            music(1f, 0.25f)
            val name = audio.musicTrackName
            if (name != null && name != last) {
                assertNotEquals(last, name)
                last = name
                count++
                seen[name] = (seen[name] ?: 0) + 1
            }
            if (!audio.musicIsPlaying) audio.skipMusic() // no 30-90 s waits
        }
        assertTrue(count > 20, "enough tracks played: $count")
        assertEquals(3, seen.size, "all three uploaded tracks rotate (Sol Standard I/II have no file)")
    }

    @Test
    fun hushAndMusicOffFadeAndPauseThenResumeAtTheSamePlace() {
        music(8f)
        assertTrue(audio.musicIsPlaying)
        audio.setMusicHush(true)
        music(6f)
        assertTrue(audio.musicPaused, "faded out and paused")
        val held = out.pos[musicSlot]
        music(20f)
        assertEquals(held, out.pos[musicSlot], "the position holds while paused")
        audio.setMusicHush(false)
        music(0.1f)
        assertFalse(audio.musicPaused)
        assertTrue(abs(out.pos[musicSlot] - held) < 0.2f, "resumed where it was")
        music(4f)
        assertTrue(audio.musicLevel > 0.9f)
        audio.setMusicEnabled(false)
        music(6f)
        assertTrue(audio.musicPaused)
        audio.setMusicEnabled(true)
        music(1f)
        assertFalse(audio.musicPaused)
    }

    @Test
    fun aGapWaitsWhileTheMusicIsOffAndForATrackThatIsLoading() {
        audio.setMusicEnabled(false)
        music(10f)
        assertFalse(audio.musicIsPlaying, "not audible: the next track waits")
        audio.setMusicEnabled(true)
        music(0.1f)
        assertTrue(audio.musicIsPlaying)
    }

    @Test
    fun aTrackThatDoesNotLoadIsSkippedAfterTheLoadTimeout() {
        for (t in AudioConfig.PLAYLIST) if (t.sound >= 0) out.loaded[t.sound] = false
        music(3.1f)
        val waiting = audio.musicNextName
        music(19f)
        assertEquals(waiting, audio.musicNextName, "still waiting before LoadTimeout 20 s")
        music(1.5f)
        assertNotEquals(waiting, audio.musicNextName, "another one queued")
        // a failed track drops out at once
        out.failedFlags[AudioConfig.PLAYLIST.first { it.name == audio.musicNextName }.sound] = true
        val before = audio.musicNextName
        music(0.1f)
        assertNotEquals(before, audio.musicNextName)
    }

    @Test
    fun skipStartsAGapTrackNowAndFadesAPlayingOne() {
        audio.skipMusic()
        music(0.05f)
        assertTrue(audio.musicIsPlaying, "skip in the first gap starts at once")
        music(5f)
        audio.skipMusic()
        music(3f)
        assertTrue(audio.musicIsPlaying && audio.musicLevel < 0.1f, "fading (tau 1 s)")
        music(3f)
        assertFalse(audio.musicIsPlaying, "faded out and finished")
    }
}
