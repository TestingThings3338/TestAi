package com.starwake.core.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inventory as shipped: every effect and music file of [SoundBank] exists in the app's assets
 * with a sane length read from its OGG headers, the loops carry their padding, the cue and layer
 * tables point at the right sounds, and the 92 voice keys of Appendix A.5 are all there.
 */
class SoundBankTest {
    private val assets = File("../app/src/main/assets")

    private fun seconds(path: String): Float {
        val bytes = File(assets, path).readBytes()
        val head = bytes.copyOf(minOf(4096, bytes.size))
        val tail = bytes.copyOfRange(maxOf(0, bytes.size - 65536), bytes.size)
        return OggInfo.seconds(head, tail)
    }

    @Test
    fun everyFileExistsWithItsLength() {
        // (min, max) seconds of the decoded sound, loops without their padding
        val expect = mapOf(
            "engine" to 6f..40f, "thrust" to 6f..40f, "decel" to 6f..40f, "rcs" to 6f..25f, "rumble" to 6f..40f,
            "cruise" to 6f..40f, "fa_switch" to 0.5f..0.9f, "fa_on" to 0.35f..0.6f, "fa_off" to 0.3f..0.5f,
            "hit1" to 0.5f..1.5f, "hit2" to 0.5f..1.5f, "hit3" to 0.5f..1.5f, "warp_enter" to 3f..6f,
            "warp_exit" to 2f..5f, "warp_travel" to 4f..30f, "boost" to 2f..2.6f, "cruise_on" to 3.5f..4.3f,
            "cruise_off" to 4f..4.8f, "clamp" to 1.3f..1.9f, "release" to 4.5f..5.5f, "touchdown" to 1.6f..2.2f,
            "warning_phase1" to 0.85f..0.95f, "warning_phase2" to 1f..8f, "overheat_loop" to 1f..8f,
        )
        assertEquals(24, SoundBank.SFX_FILES.size)
        for (i in SoundBank.SFX_FILES.indices) {
            val path = SoundBank.SFX_FILES[i]
            val name = SoundBank.name(i)
            assertTrue(File(assets, path).isFile, "missing $path")
            var s = seconds(path)
            if (SoundBank.loopPadded(i)) s -= 2 * SoundBank.LOOP_PAD_SECONDS
            val range = expect.getValue(name)
            assertTrue(s in range, "$name: $s s outside $range")
        }
        for (i in SoundBank.MUSIC_FILES.indices) {
            val path = SoundBank.MUSIC_FILES[i]
            assertTrue(File(assets, path).isFile, "missing $path")
            assertTrue(seconds(path) in 120f..260f, "$path: ${seconds(path)} s")
        }
        // the whole audio folder stays under the budget (09 / DECISIONS 37: about 12 MB)
        val total = File(assets, "audio").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue(total < 12L * 1024 * 1024, "audio assets $total B")
    }

    @Test
    fun theLoopsAreTheSoundsThatLoop() {
        val loops = (0 until SoundBank.CAPACITY).filter { SoundBank.loopPadded(it) }.toSet()
        assertEquals(
            setOf(SoundBank.ENGINE, SoundBank.THRUST, SoundBank.DECEL, SoundBank.RCS, SoundBank.RUMBLE, SoundBank.CRUISE,
                SoundBank.WARP_TRAVEL, SoundBank.WARNING_PHASE2, SoundBank.OVERHEAT_LOOP),
            loops,
        )
        // unpad cuts 0.128 s at each end at any rate
        val pcm = Pcm(ShortArray(48000 * 2) { it.toShort() }, 2, 48000)
        val u = SoundBank.unpad(pcm)
        assertEquals(48000 - 2 * 6144, u.frames)
        assertEquals((6144 * 2).toShort(), u.data[0])
        // and OggInfo.trim cuts a decoder's surplus at the end
        assertEquals(1000, OggInfo.trim(Pcm(ShortArray(1200), 1, 32000), 1000).frames)
        assertEquals(800, OggInfo.trim(Pcm(ShortArray(800), 1, 32000), 1000).frames)
    }

    @Test
    fun tablesPointAtTheirSounds() {
        assertEquals(6, AudioConfig.LAYERS.size)
        assertEquals(listOf(SoundBank.ENGINE, SoundBank.THRUST, SoundBank.DECEL, SoundBank.RCS, SoundBank.RUMBLE, SoundBank.CRUISE), AudioConfig.LAYERS.map { it.sound })
        assertEquals(14, AudioConfig.CUES.size, "one slot per Roblox cue")
        assertEquals(SoundBank.SLOT_VOICE_LINE, SoundBank.SLOT_CUE0 + AudioConfig.CUES.size, "the cue slots end where the voice begins")
        assertEquals("DockRelease", AudioConfig.CUES[AudioConfig.C_DOCK_RELEASE].name)
        assertEquals("Touchdown", AudioConfig.CUES[AudioConfig.C_TOUCHDOWN].name)
        assertEquals(AudioConfig.C_METAL_HIT1 + 2, AudioConfig.cueIndex("MetalHit3"))
        assertEquals(3, AudioConfig.PLAYLIST.count { it.sound >= 0 }, "Sol Standard I and II are not uploaded")
        // 8.4: 6 layers, 1 warp loop, 14 cues, 2 voice, 6 deck, 6 UI, 2 music, 3 warnings = 40
        assertEquals(40, SoundBank.SLOTS)
        assertEquals(SoundBank.SLOT_WARN0 + 3, SoundBank.SLOTS)
    }

    @Test
    fun theNinetyTwoVoiceKeysOfAppendixA5() {
        assertEquals(92, VoiceLine.COUNT)
        assertTrue(SoundBank.VOICE_BASE + VoiceLine.COUNT <= SoundBank.CAPACITY)
        val radio = VoiceLine.ALL.filter { it.radio }.map { it.name }.toSet()
        assertEquals(
            setOf("ControlRequestReceived", "ControlDockingGranted", "ControlDockingDenied", "ControlHoldPosition", "ControlClearedToLaunch", "ControlReduceSpeed"),
            radio,
        )
        assertEquals(13, VoiceLine.ALL.count { it.tier == Tier.WARNING })
        assertEquals(17, VoiceLine.ALL.count { it.tier == Tier.CAUTION })
        assertEquals(19, VoiceLine.ALL.count { it.tier == Tier.ADVISORY })
        assertEquals(6, VoiceLine.ALL.count { it.timed })
        assertEquals(setOf("OverheatStage1", "OverheatCritical", "HullImpact", "HullFailure", "ImpactImminent"), VoiceLine.ALL.filter { it.noTone }.map { it.name }.toSet())
        assertEquals(3.5f, VoiceLine.Count3.rank)
        for (l in VoiceLine.ALL) assertTrue(l.text.endsWith("."), "${l.name}: '${l.text}'")
        // no "commander", no "docking computer" (DECISIONS 29, 30)
        for (l in VoiceLine.ALL) {
            assertTrue(!l.text.contains("ommander", ignoreCase = true) && !l.text.contains("CMDR", ignoreCase = true))
            assertTrue(!l.text.contains("docking computer", ignoreCase = true), l.name)
        }
        assertEquals(VoiceConfig.NOTICE_TITLES.size, VoiceConfig.NOTICE_LINES.size)
        assertEquals(VoiceConfig.COMMS_PREFIXES.size, VoiceConfig.COMMS_LINES.size)
    }
}
