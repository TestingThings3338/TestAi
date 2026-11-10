package com.starwake.core.audio

import com.starwake.core.math.Rng
import com.starwake.core.ui.TextBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The announcer queue of `09` 2.13: the worked cases of 4.7, tier order and stability, cut-ins,
 * eviction, repeat protection, duplicates, expiry per tier, the WARNING tone, NoTone, the deadline of
 * a line whose length never becomes known, the gaps; and a spam test in which nothing ever overlaps.
 */
class VoiceQueueTest {
    private val out = FakeAudioOut().also { it.loadAllWith(1.0f) }
    private val clock = AudioClock()
    private val voice = Voice(out, clock)
    private val dt = 1f / 60f

    /** Line plays / tone plays in order, as "TONE" or the key. */
    private val heard = ArrayList<String>()
    private val heardAt = ArrayList<Double>()
    private var lastKey: Int = -1
    private var lastTone = false

    private fun step(n: Int = 1) {
        repeat(n) {
            clock.now += dt
            voice.update()
            out.advance(dt)
            val p = voice.playing
            val now = if (p == null) -1 else p.ordinal
            if (p != null && (now != lastKey || voice.playingTone != lastTone)) {
                heard += if (voice.playingTone) "TONE>${p.name}" else p.name
                heardAt += clock.now
            }
            lastKey = now
            lastTone = voice.playingTone
            // never two at once
            assertFalse(out.isPlaying(SoundBank.SLOT_VOICE_LINE) && out.isPlaying(SoundBank.SLOT_VOICE_TONE), "line and tone overlap")
        }
    }

    private fun seconds(s: Float) = step((s / dt).toInt())

    private fun len(line: VoiceLine, s: Float) = out.setLength(SoundBank.voice(line), s)

    @Test
    fun spammingTheAutopilotKeyNeverOverlaps() {
        // 4.7 #1: AutopilotOn / Off pressed 5x within 1 s
        len(VoiceLine.AutopilotOn, 1.2f)
        len(VoiceLine.AutopilotOff, 1.3f)
        val presses = listOf(VoiceLine.AutopilotOn, VoiceLine.AutopilotOff, VoiceLine.AutopilotOn, VoiceLine.AutopilotOff, VoiceLine.AutopilotOn)
        val results = ArrayList<Boolean>()
        for (p in presses) {
            results += voice.say(p)
            step(12) // 0.2 s
        }
        assertEquals(listOf(true, true, false, false, false), results, "a playing or queued key is rejected")
        seconds(5f)
        assertEquals(listOf("AutopilotOn", "AutopilotOff"), heard)
        // within RepeatCooldown 2.5 s of playing: still rejected; after it, accepted
        assertTrue(heardAt[1] - heardAt[0] >= 1.2 + 0.35 - 1e-6, "the gap of 0.35 s between lines")
        assertTrue(voice.say(VoiceLine.AutopilotOn), "long after: accepted")
    }

    @Test
    fun aWarningCutsTheAltitudeCallPlaysItsToneThenTheLine() {
        // 4.7 #2
        len(VoiceLine.AltOneHundred, 1.0f)
        len(VoiceLine.PullUp, 0.6f)
        out.setLength(SoundBank.WARNING_PHASE1, 0.9f)
        voice.say(VoiceLine.AltOneHundred)
        step(12)
        assertEquals(VoiceLine.AltOneHundred, voice.playing)
        assertTrue(voice.say(VoiceLine.PullUp))
        assertFalse(out.isPlaying(SoundBank.SLOT_VOICE_LINE), "cut at once")
        step()
        assertTrue(voice.playingTone, "the tone first")
        assertEquals(VoiceLine.PullUp, voice.playing)
        val toneAt = clock.now
        seconds(1.3f)
        val i = heard.indexOf("PullUp")
        assertTrue(i > 0, "then the line: $heard")
        val after = heardAt[i] - toneAt
        // tone 0.9 s (deadline 0.9 + END_SLACK 0.15) + ToneGap 0.08: between 0.9 and 1.15 s
        assertTrue(after in 0.88..1.2, "line ${after}s after the tone")
    }

    @Test
    fun aWarningExpiresAfterOneAndAHalfSeconds() {
        len(VoiceLine.HullFailure, 3f)
        len(VoiceLine.PullUp, 0.5f)
        voice.say(VoiceLine.HullFailure) // WARNING, NoTone, long
        step()
        voice.say(VoiceLine.PullUp) // same rank: does not cut, waits
        seconds(3.5f)
        assertEquals(listOf("HullFailure"), heard, "PullUp waited > 1.5 s and expired")
    }

    @Test
    fun theCountdownCutsStatusAndAdvisoryButNotCautionOrWarning() {
        // 4.7 #3
        len(VoiceLine.Welcome, 3f)
        len(VoiceLine.Count5, 0.4f)
        voice.say(VoiceLine.Welcome)
        step(6)
        assertTrue(voice.say(VoiceLine.Count5))
        step()
        assertEquals(VoiceLine.Count5, voice.playing, "cut the STATUS line")
        seconds(1f)
        // CAUTION is not cut
        len(VoiceLine.FlightAssistOff, 2f)
        voice.say(VoiceLine.FlightAssistOff)
        step(6)
        voice.say(VoiceLine.Count4)
        step()
        assertEquals(VoiceLine.FlightAssistOff, voice.playing, "CAUTION plays on")
        // and the countdown expires after TimedMaxWait 0.5 s
        seconds(2.5f)
        assertFalse(heard.contains("Count4"), "Count4 waited too long: $heard")
    }

    @Test
    fun timedLinesHaveTheShortGap() {
        len(VoiceLine.Count5, 0.5f)
        len(VoiceLine.Count4, 0.5f)
        voice.say(VoiceLine.Count5)
        step(27) // 0.45 s: the next second's line comes in shortly before this one ends
        voice.say(VoiceLine.Count4)
        seconds(1.5f)
        val g = heardAt[1] - heardAt[0]
        // 0.5 s line + 0.15 END_SLACK at most + TimedGap 0.08
        assertTrue(g in 0.5..0.8, "timed gap: $g")
    }

    @Test
    fun aFullQueueDropsTheLowerAndEvictsForTheHigher() {
        // 4.7 #4
        len(VoiceLine.Welcome, 5f)
        voice.say(VoiceLine.Welcome)
        step()
        for (l in listOf(VoiceLine.GearDown, VoiceLine.GearUp, VoiceLine.Docked, VoiceLine.Rebooting)) assertTrue(voice.say(l))
        assertEquals(4, voice.queued)
        assertFalse(voice.say(VoiceLine.CargoLost), "STATUS: the tail is STATUS -> dropped")
        assertTrue(voice.say(VoiceLine.FuelCritical), "WARNING evicts the STATUS tail")
        assertEquals(4, voice.queued)
        assertEquals(VoiceLine.FuelCritical, voice.queuedAt(0), "and goes to the front")
        assertFalse((0 until voice.queued).any { voice.queuedAt(it) == VoiceLine.Rebooting }, "the tail went")
    }

    @Test
    fun sameTierIsFirstComeFirstServedAndDuplicatesAreRejected() {
        // 4.7 #5 and the tier order
        len(VoiceLine.Docked, 2f)
        voice.say(VoiceLine.Docked)
        step()
        voice.say(VoiceLine.GearDown)
        voice.say(VoiceLine.AltFifty) // ADVISORY
        voice.say(VoiceLine.GearUp)
        voice.say(VoiceLine.FuelLow) // CAUTION
        assertFalse(voice.say(VoiceLine.GearDown), "duplicate")
        assertFalse(voice.say(VoiceLine.Docked), "playing")
        val order = (0 until voice.queued).map { voice.queuedAt(it) }
        assertEquals(listOf(VoiceLine.FuelLow, VoiceLine.AltFifty, VoiceLine.GearDown, VoiceLine.GearUp), order)
    }

    @Test
    fun expiryPerTier() {
        // a long STATUS line holds the queue; each tier waits its own MaxWait
        len(VoiceLine.Welcome, 10f)
        voice.say(VoiceLine.Welcome)
        step()
        voice.say(VoiceLine.GearDown) // STATUS 6 s
        voice.say(VoiceLine.AltFifty) // ADVISORY 5 s
        voice.say(VoiceLine.FuelLow) // CAUTION 3 s
        seconds(10.5f)
        assertEquals(listOf("Welcome"), heard, "all expired while the 10 s line ran")
        // waiting less than MaxWait: plays
        len(VoiceLine.Docked, 4f)
        voice.say(VoiceLine.Docked)
        step()
        voice.say(VoiceLine.AltFifty)
        voice.say(VoiceLine.GearUp)
        seconds(10f)
        assertEquals(listOf("Welcome", "Docked", "AltFifty", "GearUp"), heard, "ADVISORY waited 4.5 s < 5, STATUS < 6")
    }

    @Test
    fun noToneForNoToneLinesAndCautionPlaysWithout() {
        out.setLength(SoundBank.WARNING_PHASE1, 0.9f)
        voice.say(VoiceLine.HullImpact)
        step()
        assertFalse(voice.playingTone, "NoTone")
        seconds(2f)
        voice.say(VoiceLine.FuelLow)
        step()
        assertFalse(voice.playingTone, "CAUTION has no tone")
        seconds(2f)
        voice.say(VoiceLine.CollisionAlert)
        step()
        assertTrue(voice.playingTone, "WARNING")
        // without a loaded alarm the line plays at once
        seconds(3f)
        out.loaded[SoundBank.WARNING_PHASE1] = false
        voice.say(VoiceLine.Overspeed)
        step()
        assertFalse(voice.playingTone)
        assertEquals(VoiceLine.Overspeed, voice.playing)
    }

    @Test
    fun aLineWithoutSoundIsDroppedAndAStuckLineEndsAfterEightSeconds() {
        out.loaded[SoundBank.voice(VoiceLine.AutopilotNoTarget)] = false
        assertFalse(voice.say(VoiceLine.AutopilotNoTarget), "no sound: dropped")
        // a line whose length never becomes known (0): the safety deadline of 8 s
        out.setLength(SoundBank.voice(VoiceLine.Rebooting), 0f)
        out.stuck[SoundBank.SLOT_VOICE_LINE] = true // it hangs, "playing" for ever
        voice.say(VoiceLine.Rebooting)
        step()
        seconds(7.9f)
        assertEquals(VoiceLine.Rebooting, voice.playing, "until the deadline")
        seconds(0.3f)
        assertNull(voice.playing, "stopped after MAX_UNKNOWN_LINE 8 s")
        assertFalse(out.isPlaying(SoundBank.SLOT_VOICE_LINE))
    }

    @Test
    fun theGapBetweenLinesIs035Seconds() {
        len(VoiceLine.GearDown, 0.7f)
        len(VoiceLine.GearUp, 0.7f)
        voice.say(VoiceLine.GearDown)
        voice.say(VoiceLine.GearUp)
        seconds(3f)
        val g = heardAt[1] - heardAt[0]
        assertTrue(g in 0.7 + 0.35 - dt..0.7 + 0.15 + 0.35 + 2 * dt, "gap $g")
    }

    @Test
    fun interruptStopsTheLineAndEmptiesTheQueue() {
        len(VoiceLine.Welcome, 3f)
        voice.say(VoiceLine.Welcome)
        voice.say(VoiceLine.SystemsOnline)
        step()
        voice.interrupt()
        assertNull(voice.playing)
        assertEquals(0, voice.queued)
        assertFalse(out.isPlaying(SoundBank.SLOT_VOICE_LINE))
        // the crash line plays right away
        voice.say(VoiceLine.HullFailure)
        step()
        assertEquals(VoiceLine.HullFailure, voice.playing)
    }

    @Test
    fun spamNeverOverlapsAndKeepsTheRules() {
        val rng = Rng(7)
        out.setLength(SoundBank.WARNING_PHASE1, 0.9f)
        for (l in VoiceLine.ALL) len(l, 0.3f + rng.nextFloat() * 1.5f)
        repeat(60 * 120) {
            // a burst of random requests every frame now and then
            if (rng.nextFloat() < 0.3f) voice.say(VoiceLine.ALL[rng.nextInt(VoiceLine.COUNT)])
            clock.now += dt
            voice.update()
            out.advance(dt)
            val lineOn = out.isPlaying(SoundBank.SLOT_VOICE_LINE)
            val toneOn = out.isPlaying(SoundBank.SLOT_VOICE_TONE)
            assertFalse(lineOn && toneOn, "overlap at ${clock.now}")
            // what the queue thinks plays is what the output plays
            if (voice.playing != null && !voice.playingTone) assertEquals(SoundBank.voice(voice.playing!!), out.sound[SoundBank.SLOT_VOICE_LINE])
            assertTrue(voice.queued <= 5, "queue limit (4, plus a line back from its tone)")
        }
        assertTrue(voice.played > 50, "the queue kept talking: ${voice.played}")
        val buf = TextBuf(256)
        voice.status(buf)
        assertTrue(buf.toString().startsWith("voice "))
    }

    @Test
    fun theVoiceGroupFollowsTheSettings() {
        voice.setVolumes(0.5f, 0.8f)
        assertEquals(0.5f * 1.6f * 0.8f, out.busGain[Bus.VOICE], 1e-6f)
    }
}
