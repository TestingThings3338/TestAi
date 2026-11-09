package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.audio.AudioConfig
import com.starwake.core.audio.Bus
import com.starwake.core.audio.FakeAudioOut
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.VoiceConfig
import com.starwake.core.audio.VoiceLine
import com.starwake.core.input.InputFrame
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session adapter (`09` 9.3): what the current game sounds like through [SessionAudio] — the
 * docked start, the launch, Flight Assist, the welcome lines, hull hits, the deck and destruction
 * branches, the pause, the menu music, the settings volumes; the frame order is unchanged and the
 * flight frame with audio on allocates nothing.
 */
class SessionAudioTest {
    private val system = StarSystem()
    private val host = RecordingHost()
    private val settings = Settings()
    private val input = InputFrame()
    private val out = FakeAudioOut().also { it.loadAllWith(1.2f) }
    private val audio = SessionAudio(out, settings, seed = 3)
    private val dt = 1f / 60f

    private fun session(docked: Boolean): Session {
        val s = Session(system, null, settings, host, GearRig())
        if (docked) s.startDocked() else s.startInSpace()
        s.audio = audio
        audio.begin(s)
        return s
    }

    private fun run(s: Session, seconds: Float, simulating: Boolean = true) {
        var t = 0f
        while (t < seconds) {
            audio.tick(dt, false, simulating)
            if (simulating) {
                input.begin()
                s.frame(dt, input)
            }
            out.advance(dt)
            t += dt
        }
    }

    private fun lines(): List<VoiceLine> =
        out.played.filter { it >= SoundBank.VOICE_BASE }.map { VoiceLine.of(it - SoundBank.VOICE_BASE) }

    @Test
    fun aDockedStartIsSilentAndTheLaunchOpensTheClamps() {
        val s = session(docked = true)
        run(s, 2f)
        val cues = out.played.filter { it < SoundBank.VOICE_BASE && it !in SoundBank.MUSIC_MENU..SoundBank.MUSIC_MERIDIAN_LIGHTS }
        assertEquals((0 until 6).map { AudioConfig.LAYERS[it].sound }, cues, "only the six layers, no cue")
        out.clearLog()
        s.launchFromStation()
        run(s, 3f)
        assertTrue(SoundBank.RELEASE in out.played, "the clamps open: ${out.playedNames()}")
        assertEquals(listOf(VoiceLine.ControlClearedToLaunch, VoiceLine.LaunchInitiated), lines(), "the launch comms (pre-port message stand-in)")
    }

    @Test
    fun flightAssistClicksChirpsAndSpeaks() {
        val s = session(docked = false)
        run(s, 1f)
        out.clearLog()
        s.toggleFlightAssist()
        run(s, 2f)
        assertTrue(out.played.containsAll(listOf(SoundBank.FA_SWITCH, SoundBank.FA_OFF)), out.playedNames().toString())
        assertEquals(listOf(VoiceLine.FlightAssistOff), lines())
        s.toggleFlightAssist()
        run(s, 3f)
        assertTrue(SoundBank.FA_ON in out.played)
        assertEquals(listOf(VoiceLine.FlightAssistOff, VoiceLine.FlightAssistOn), lines())
    }

    @Test
    fun theWelcomeLinesFiveSecondsAfterTheReveal() {
        val s = session(docked = true)
        s.afterReveal(welcome = true)
        run(s, 4.9f)
        assertTrue(lines().isEmpty())
        run(s, 3f)
        assertEquals(listOf(VoiceLine.Welcome, VoiceLine.SystemsOnline), lines())
    }

    @Test
    fun hullHitsAndTheImpactLine() {
        val s = session(docked = false)
        run(s, 7f) // past the read-in
        out.clearLog()
        audio.hullHit(3f)
        assertTrue(out.played.isEmpty(), "under 4 m/s nothing")
        audio.hullHit(10f)
        run(s, 0.5f)
        assertTrue(out.played.any { it in SoundBank.HIT1..SoundBank.HIT3 })
        assertFalse(VoiceLine.HullImpact in lines(), "not above 12 m/s")
        audio.hullHit(30f)
        run(s, 0.5f)
        assertTrue(VoiceLine.HullImpact in lines())
    }

    @Test
    fun theDeckMutesTheShipAndTheCinematicHushesTheMusic() {
        val s = session(docked = false)
        run(s, 1f)
        assertTrue(out.busGain[Bus.SFX] > 0.9f)
        s.decks.begin()
        run(s, 0.2f)
        assertEquals(0f, out.busGain[Bus.SFX], "decks: the ship is silent")
        assertFalse(audio.ship.musicHushed)
        s.decks.end()
        run(s, 0.2f)
        assertTrue(out.busGain[Bus.SFX] > 0.9f)
        s.destruction.start(1f)
        run(s, 0.2f)
        assertEquals(0f, out.busGain[Bus.SFX])
        assertTrue(audio.ship.musicHushed, "the crash cinematic hushes the music")
        run(s, 1.2f)
        assertFalse(audio.ship.musicHushed, "back in flight")
    }

    @Test
    fun thePauseHoldsTheShipAndTheVoiceTheMusicPlaysOn() {
        val s = session(docked = false)
        run(s, 1f)
        run(s, 0.5f, simulating = false)
        assertTrue(out.busPaused[Bus.SFX] && out.busPaused[Bus.VOICE])
        assertFalse(out.busPaused[Bus.MUSIC])
        val now = audio.clock.now
        run(s, 1f, simulating = false)
        assertEquals(now, audio.clock.now, "the audio clock stops with the simulation")
        run(s, 0.1f)
        assertFalse(out.busPaused[Bus.SFX] || out.busPaused[Bus.VOICE])
    }

    @Test
    fun theMenuMusicFadesInAndOutWithTheMenu() {
        repeat(60) {
            audio.tick(dt, true, false)
            out.advance(dt)
        }
        val slot = SoundBank.SLOT_MUSIC_MENU
        assertEquals(SoundBank.MUSIC_MENU, out.sound[slot])
        assertTrue(out.loop[slot])
        assertEquals(AudioConfig.MENU_MUSIC_VOLUME * 1f / AudioConfig.MENU_MUSIC_FADE_IN, out.gain[slot], 0.01f, "1 s into the 2.5 s fade")
        repeat(120) { audio.tick(dt, true, false) }
        assertEquals(AudioConfig.MENU_MUSIC_VOLUME, out.gain[slot], 1e-4f)
        // MUSIC off in the menu: fade out and hold
        settings.musicOn = false
        repeat(70) { audio.tick(dt, true, false) }
        assertFalse(out.isPlaying(slot), "paused")
        settings.musicOn = true
        repeat(10) { audio.tick(dt, true, false) }
        assertTrue(out.isPlaying(slot), "resumed")
        // leaving the menu: out in 1 s and stopped
        repeat(70) { audio.tick(dt, false, true) }
        assertFalse(out.isPlaying(slot))
    }

    @Test
    fun theSettingsSetTheBuses() {
        settings.masterVolume = 0.5f
        settings.effectsVolume = 0.8f
        settings.musicVolume = 0.6f
        val s = session(docked = false)
        run(s, 0.1f)
        assertEquals(0.4f, out.busGain[Bus.SFX], 1e-5f)
        assertEquals(0.5f * VoiceConfig.VOLUME * 0.8f, out.busGain[Bus.VOICE], 1e-5f)
        assertEquals(0.3f, out.busGain[Bus.MUSIC], 1e-5f)
        assertEquals(0.4f, out.busGain[Bus.UI], 1e-5f)
        audio.mutedByDebug = true
        run(s, 0.1f)
        assertEquals(0f, out.busGain[Bus.SFX])
        assertEquals(0f, out.busGain[Bus.VOICE])
    }

    @Test
    fun theFrameOrderIsUnchangedWithAudio() {
        val withAudio = session(docked = false)
        val plain = Session(system, null, settings, host, GearRig()).also { it.startInSpace() }
        fun traced(s: Session): String {
            val t = FrameTrace()
            s.trace = t
            input.begin()
            s.frame(dt, input)
            s.trace = null
            return t.toString()
        }
        assertEquals(traced(plain), traced(withAudio))
    }

    @Test
    fun theFlightFrameWithAudioAllocatesNothing() {
        out.logging = false
        val s = session(docked = false)
        s.afterReveal(welcome = true)
        s.setThrottle(1f)
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        run(s, 12f) // warm up: welcome lines, callouts past the grace, music started
        s.toggleFlightAssist()
        val a = mx.currentThreadAllocatedBytes
        for (i in 0 until 600) {
            audio.tick(dt, false, true)
            input.begin()
            s.frame(dt, input)
            out.advance(dt)
            if (i == 300) s.toggleFlightAssist()
        }
        val bytes = mx.currentThreadAllocatedBytes - a
        println("600 flight frames with ShipAudio, voice and callouts: $bytes B allocated")
        assertTrue(bytes < 2048, "$bytes B over 600 frames")
    }
}
