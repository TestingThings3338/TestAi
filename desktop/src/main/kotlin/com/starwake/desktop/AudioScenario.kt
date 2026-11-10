package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.audio.Bus
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.VoiceLine
import java.io.File
import java.lang.management.ManagementFactory

/**
 * `09` 9.6 "audio-flight": the sound of a whole first flight on the real Game with every file
 * decoded (ffmpeg) and mixed by the core mixer in step with the simulated clock. Docked start (the
 * menu music hands over to the flight rotation, the welcome lines), launch (the clamps open), throttle
 * up, boost, Flight Assist off and on, cruise on and off, back to the station and the auto-dock
 * (pad touchdown, clamps), the pause menu, leaving the app. Asserts the cue order and the voice
 * lines from the recording log, that the boost dying away near the station is no cruise, that the
 * announcer's two slots never overlap and the mix never clips, that leaving the app holds the ship
 * at once and that every hold and release fades without a click (read back from the WAV); writes
 * the WAV mix and its spectrogram; measures the mixer per 256-frame burst with every slot busy and
 * the game thread's allocation per flight frame with audio on.
 */
object AudioScenario {
    fun run(env: ScenarioEnv) {
        val r = env.report
        val opts = env.opts
        val wav = File(opts.outDir, "audio-flight.wav")
        val platform = DesktopPlatform(opts.assetsDir, opts.settings, audioDecode = true, audioWav = wav)
        val rec = platform.recorder
        val t0 = System.nanoTime()
        val game = Game(platform) // declares every sound: decoded here, synchronously
        r.note("decoded %d sounds in %.1f s (%d failed)".format(rec.loadedCount, (System.nanoTime() - t0) / 1e9, rec.failedCount))
        check(rec.failedCount == 0) { "${rec.failedCount} sounds failed to load (ffmpeg missing?)" }
        val d = GameDriver(game, NoopGl(), null, platform, r, opts, opts.insets)
        d.start()

        var overlapFrames = 0
        var maxEngine = 0f
        fun run(seconds: Float) {
            val n = (seconds * 60).toInt()
            for (i in 0 until n) {
                d.frame()
                if (rec.isPlaying(SoundBank.SLOT_VOICE_LINE) && rec.isPlaying(SoundBank.SLOT_VOICE_TONE)) overlapFrames++
                maxEngine = maxOf(maxEngine, rec.slotGain[SoundBank.SLOT_LAYER0])
            }
        }
        fun mark(what: String) = d.log("--- $what (log ${rec.logCount})")

        // the menu: its music fades in
        run(3f)
        mark("launch: docked start")
        d.cmd("launch")
        run(7f) // the welcome lines 5 s after the reveal; the flight music after its first gap
        val engineIdle = rec.slotGain[SoundBank.SLOT_LAYER0]
        mark("LAUNCH from the services")
        d.cmd("undock")
        run(3f)
        mark("throttle up")
        d.cmd("throttle 1")
        run(4f)
        mark("boost")
        val boostAt = rec.logCount
        d.cmd("boost")
        run(4f)
        mark("flight assist off")
        d.cmd("fa off")
        run(3f)
        mark("flight assist on")
        d.cmd("fa on")
        run(3f)
        mark("on to cruise speed")
        val cruiseAt = rec.logCount
        var waited = 0f
        // the flight model's station lock (6 km) holds TopSpeed: the cruise engages once the ship
        // is out of MERIDIAN's lock and above 1.02 x TopSpeed (ShipThrust.cruising)
        while (d.field("cruising") != "true" && waited < 90f) {
            run(1f)
            waited += 1f
        }
        check(d.field("cruising") == "true") { "never cruised: ${game.debugInfo}" }
        run(6f) // in cruise
        val engineFull = rec.slotGain[SoundBank.SLOT_LAYER0]
        mark("throttle down: out of cruise")
        d.cmd("throttle 0")
        run(8f)
        mark("back to the station: auto-dock")
        d.cmd("behind-station")
        d.frames(2)
        d.cmd("dock")
        waited = 0f
        while (game.stateName != "docked" && waited < 150f) {
            run(1f)
            waited += 1f
        }
        check(game.stateName == "docked") { "never docked: ${game.debugInfo}" }
        run(4f) // the clamps close 1.6 s after the pad touchdown
        // the music (a random track) goes down for the hold measurements, so only the ship's own
        // sound is around the edges; it keeps playing
        d.cmd("volume music 0")
        run(0.5f)
        mark("pause: the ship holds, the music plays on")
        d.cmd("pause")
        run(2f)
        d.cmd("resume")
        run(1f)
        // the app leaves (power button) with the services open: the activity runs Game.onBackground
        // on the GL thread before it pauses, so the pause menu opens and the ship and the announcer
        // are held before the audio track stops, not at the first frame after the return
        mark("the app leaves: Game.onBackground")
        val leaveAt = rec.logCount
        game.onPause()
        game.onBackground()
        val heldNow = rec.events().subList(leaveAt, rec.logCount)
            .filter { it.event == RecordingAudioOut.EV_BUS_PAUSE && it.gain > 0f }.map { Bus.name(it.sound) }
        r.note("Game.onBackground: state ${game.stateName}, held at once: $heldNow")
        check(game.stateName == "paused") { "the pause menu did not open: ${game.stateName}" }
        check(heldNow.containsAll(listOf("SFX", "VOICE"))) { "Game.onBackground held $heldNow, not the ship and the announcer" }
        run(1f) // away (on the phone the track is paused; here the mix goes on): the ship stays held
        d.cmd("resume")
        run(1f)
        d.cmd("audio")
        d.frame()
        r.note("status: ${game.audioReport}")
        rec.close()

        // ---------------------------------------------------------------- the log
        val events = rec.events()
        val plays = events.filter { it.event == RecordingAudioOut.EV_PLAY || it.event == RecordingAudioOut.EV_LOOP }
        val cues = plays.filter { it.slot in SoundBank.SLOT_CUE0 until SoundBank.SLOT_VOICE_LINE }.map { it.sound }
        val lines = plays.filter { it.slot == SoundBank.SLOT_VOICE_LINE }.map { VoiceLine.of(it.sound - SoundBank.VOICE_BASE) }
        val tones = plays.count { it.slot == SoundBank.SLOT_VOICE_TONE }
        r.note("cues in order: " + cues.joinToString(" ") { SoundBank.name(it) })
        r.note("voice lines in order: " + lines.joinToString(" ") { it.name } + " (+$tones warning tones)")
        val logFile = File(opts.outDir, "audio-flight-log.txt")
        logFile.writeText(events.joinToString("\n") + "\n")
        r.note("event log: ${logFile.path} (${events.size} events)")

        fun subsequence(want: List<Int>, got: List<Int>): Boolean {
            var i = 0
            for (g in got) if (i < want.size && g == want[i]) i++
            return i == want.size
        }
        val expectedCues = listOf(
            SoundBank.RELEASE, SoundBank.BOOST, SoundBank.FA_SWITCH, SoundBank.FA_OFF, SoundBank.FA_SWITCH, SoundBank.FA_ON,
            SoundBank.CRUISE_ON, SoundBank.CRUISE_OFF, SoundBank.TOUCHDOWN, SoundBank.CLAMP,
        )
        check(subsequence(expectedCues, cues)) { "cue order: want ${expectedCues.map { SoundBank.name(it) }} in ${cues.map { SoundBank.name(it) }}" }
        // the lines of each event in their order; between events the tiers may reorder (an ADVISORY
        // radio call goes before a waiting STATUS line, as ShipVoice sorts)
        val pairs = listOf(
            listOf(VoiceLine.Welcome, VoiceLine.SystemsOnline),
            listOf(VoiceLine.ControlClearedToLaunch, VoiceLine.LaunchInitiated),
            listOf(VoiceLine.FlightAssistOff, VoiceLine.FlightAssistOn),
            listOf(VoiceLine.CruiseOn, VoiceLine.CruiseOff),
            listOf(VoiceLine.ControlDockingGranted, VoiceLine.DockingComputerEngaged, VoiceLine.Docked),
        )
        for (p in pairs) check(subsequence(p.map { it.ordinal }, lines.map { it.ordinal })) { "voice lines: want $p in order in $lines" }
        check(lines.indexOf(VoiceLine.Docked) == lines.size - 1) { "Docked is the last line: $lines" }
        // the boost dying away near the station is no cruise: no swell and no cruise line before the
        // cruise leg, and never "Cruise dropped" (the cruise ends with the throttle at zero)
        val boostLeg = events.subList(boostAt, cruiseAt).filter { it.event == RecordingAudioOut.EV_PLAY }
        val boostCues = boostLeg.filter { it.slot in SoundBank.SLOT_CUE0 until SoundBank.SLOT_VOICE_LINE }.map { it.sound }
        val boostLines = boostLeg.filter { it.slot == SoundBank.SLOT_VOICE_LINE }.map { VoiceLine.of(it.sound - SoundBank.VOICE_BASE) }
        r.note("boost leg (to the cruise mark): cues ${boostCues.joinToString(" ") { SoundBank.name(it) }}, lines $boostLines")
        check(SoundBank.CRUISE_ON !in boostCues) { "a cruise swell after the boost near the station" }
        check(VoiceLine.CruiseOn !in boostLines && VoiceLine.CruiseDropped !in boostLines) { "cruise lines after the boost: $boostLines" }
        check(VoiceLine.CruiseDropped !in lines) { "Cruise dropped in $lines" }
        check(overlapFrames == 0) { "the voice line and its tone overlapped in $overlapFrames frames" }
        // the six layers loop from the start; the engine follows the lever
        val layerLoops = plays.filter { it.event == RecordingAudioOut.EV_LOOP && it.slot in 0 until 6 }.map { it.sound }.toSet()
        check(layerLoops.size == 6) { "layers started: $layerLoops" }
        check(engineFull > engineIdle * 1.3f) { "engine layer at full throttle $engineFull vs idle $engineIdle" }
        r.note("engine layer gain: idle %.3f, full throttle %.3f (max %.3f)".format(engineIdle, engineFull, maxEngine))
        // music: the menu track in the menu, then the flight rotation after its first gap
        val menuPlay = plays.firstOrNull { it.slot == SoundBank.SLOT_MUSIC_MENU }
        val flightPlay = plays.firstOrNull { it.slot == SoundBank.SLOT_MUSIC_FLIGHT }
        check(menuPlay != null && menuPlay.frame < 10) { "menu music: $menuPlay" }
        check(flightPlay != null) { "no flight track started" }
        r.note("music: menu at frame ${menuPlay!!.frame}, flight track ${SoundBank.name(flightPlay!!.sound)} at frame ${flightPlay.frame}")
        // the pause menu, then leaving the app, hold the ship's sound (both buses paused, then released)
        val pauses = events.filter { it.event == RecordingAudioOut.EV_BUS_PAUSE && it.sound == Bus.SFX }.map { it.gain }
        check(pauses == listOf(1f, 0f, 1f, 0f)) { "SFX bus pause/resume: $pauses" }
        check(events.none { it.event == RecordingAudioOut.EV_BUS_PAUSE && it.sound == Bus.MUSIC }) { "the music was held" }
        holdEdges(wav, events.filter { it.event == RecordingAudioOut.EV_BUS_PAUSE && it.sound == Bus.SFX }, r)
        // the mix: never above full scale (the limiter's ceiling), a sensible level
        check(rec.mixPeak <= 0.981f) { "mix peak ${rec.mixPeak}" }
        r.note("mix: peak %.2f dBFS, RMS %.1f dBFS, limiter active in %d bursts; WAV %s (%.1f s)".format(
            20 * kotlin.math.log10(rec.mixPeak.toDouble()), rec.mixRmsDb, rec.limitingBursts, wav.path, (wav.length() - 44) / 192000.0))
        spectrogram(wav, File(opts.outDir, "audio-flight-spectrum.png"), r)

        // ---------------------------------------------------------------- perf
        mixerBurst(rec, r)
        flightAllocation(env)
    }

    /**
     * The pause menu's holds of the SFX and VOICE buses fade the ship out and back in over one burst
     * (review: they cut it within one sample, a click at the pause and at the resume, about 20 x
     * the local sample step). At each hold and release, the largest sample step of the mix around
     * the burst where it takes effect stays within 1.5 x the largest step of the sound on either
     * side of it (the 200 ms before and the 200 ms after).
     */
    private fun holdEdges(wav: File, holds: List<RecordingAudioOut.Event>, r: ScenarioReport) {
        val raw = wav.readBytes()
        val n = (raw.size - 44) / 4
        val bb = java.nio.ByteBuffer.wrap(raw, 44, n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val left = FloatArray(n)
        val right = FloatArray(n)
        for (i in 0 until n) {
            left[i] = bb.short / 32768f
            right[i] = bb.short / 32768f
        }
        fun maxStep(from: Int, to: Int): Float {
            var m = 0f
            for (i in maxOf(1, from) until minOf(n, to)) {
                m = maxOf(m, kotlin.math.abs(left[i] - left[i - 1]), kotlin.math.abs(right[i] - right[i - 1]))
            }
            return m
        }
        for (h in holds) {
            val at = h.at.toInt()
            val edge = maxStep(at - 1, at + 1600)
            val around = maxOf(maxStep(at - 9600, at - 1), maxStep(at + 1600, at + 11200))
            val what = if (h.gain > 0f) "hold" else "release"
            r.note("SFX %s at %.3f s: largest step %.5f at the edge, %.5f in the 200 ms either side (%.2f x)".format(
                what, at / 48000.0, edge, around, edge / maxOf(around, 1e-6f)))
            check(edge <= around * 1.5f + 2e-4f) { "the $what at ${at / 48000.0} s steps $edge (around it: $around): a click" }
        }
    }

    private fun spectrogram(wav: File, png: File, r: ScenarioReport) {
        try {
            val p = ProcessBuilder(
                "ffmpeg", "-v", "error", "-y", "-i", wav.path, "-lavfi",
                "showspectrumpic=s=1800x600:legend=1:scale=log:fscale=lin:color=intensity", png.path,
            ).inheritIO().start()
            p.waitFor()
            r.note("spectrogram: ${png.path}")
        } catch (e: java.io.IOException) {
            r.note("no spectrogram (ffmpeg missing): ${e.message}")
        }
    }

    /** The mixer with every one of the 40 slots playing a real decoded sound, 256-frame bursts. */
    private fun mixerBurst(rec: RecordingAudioOut, r: ScenarioReport) {
        for (s in 0 until SoundBank.SLOTS) {
            val snd = if (s == SoundBank.SLOT_MUSIC_FLIGHT || s == SoundBank.SLOT_MUSIC_MENU) SoundBank.MUSIC_DEEP_SPACE else s % 24
            rec.play(s, snd, 0.05f, 0.85f + (s % 7) * 0.05f, true, 0f, Bus.SFX)
        }
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val buf = FloatArray(512)
        repeat(2000) { rec.renderBurst(buf, 256) }
        val a = mx.currentThreadAllocatedBytes
        val t = System.nanoTime()
        val n = 10_000
        for (i in 0 until n) {
            if (i % 8 == 0) for (s in 0 until SoundBank.SLOTS) rec.set(s, 0.05f + (i % 5) * 0.01f, 0.85f + (s % 7) * 0.05f)
            rec.renderBurst(buf, 256)
        }
        val us = (System.nanoTime() - t) / 1e3 / n
        val bytes = mx.currentThreadAllocatedBytes - a
        r.note("mixer: 40 busy slots, %.1f us per 256-frame burst = %.2f %% of the 5.33 ms it plays (desktop JIT); %d B allocated over %d bursts"
            .format(us, us / 5333.3 * 100, bytes, n))
        check(bytes < 4096) { "the mixer allocated $bytes B" }
    }

    /** The perf drive (two thumbs, 600 frames) on a no-op GL with the audio on: game-thread bytes per frame. */
    private fun flightAllocation(env: ScenarioEnv) {
        val r = env.report
        val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
        val game = Game(platform)
        val d = GameDriver(game, NoopGl(), null, platform, r, env.opts, env.opts.insets)
        val meter = PerfMeter(600, null)
        meter.game = game
        d.start()
        Perf.drive(d, 600, meter)
        meter.summary("flight frame with audio on (no-op GL)", 300, r)
        val window = meter.allocBytes.copyOfRange(300, meter.count)
        r.note("audio adapter, ShipAudio, voice and callouts ran every frame: %d voice lines said, %d loaded sounds"
            .format(game.audio.voice.played, platform.recorder.loadedCount))
        if (window.sum() > 0) r.note("WARNING: the flight frame allocated ${window.sum()} B over 300 frames")
    }
}
