package com.starwake.android

import android.content.Context
import android.os.Bundle
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.starwake.core.audio.MixerAudioOut
import com.starwake.core.audio.Pcm
import com.starwake.core.audio.VoiceFx
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The announcer's voice until the user's recordings exist (DECISIONS 38): the device's
 * text-to-speech engine speaks every Appendix A.5 line once, in English whatever the phone's own
 * language (en-US, else en-GB, else any installed English voice; never another language), on a
 * background thread at the first launch. Each rendering runs through the Computer or Control chain
 * ([VoiceFx]), is loudness-normalised and cached in app storage under a key of text + engine + voice
 * + chain version, so it is made again only when one of them changes. No engine or no English voice:
 * the lines stay silent and the game does not care.
 *
 * The engine is bound to the application context, not the activity's: the baker outlives an
 * activity that is destroyed while it bakes, and an activity's leaked binding, force-unbound at its
 * destruction, would make the late shutdown throw on the baker thread.
 */
internal class AndroidVoiceBaker(context: Context, private val out: MixerAudioOut) {
    private class Request(val sound: Int, val key: String, val text: String, val radio: Boolean)

    private val app: Context = context.applicationContext
    private val queue = LinkedBlockingQueue<Request>()
    @Volatile private var thread: Thread? = null
    @Volatile private var stopped = false
    private var tts: TextToSpeech? = null
    private val dir = File(app.filesDir, "voice-tts")

    /** Lines baked or loaded from the cache, and why the voice is silent if it is (diagnostics). */
    @Volatile var ready = 0
        private set
    @Volatile var status = "waiting"
        private set

    /** Any thread: a line to speak. The first request starts the baker. */
    fun request(sound: Int, key: String, text: String, radio: Boolean) {
        queue.add(Request(sound, key, text, radio))
        if (thread == null) {
            synchronized(this) {
                if (thread == null) thread = Thread({ run() }, "voice-tts").also { it.start() }
            }
        }
    }

    fun shutdown() {
        stopped = true
        thread?.interrupt()
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            // gather the requests of the start (the loader declares them all in a burst), most used first
            val batch = ArrayList<Request>()
            while (true) {
                val r = queue.poll(1500, TimeUnit.MILLISECONDS) ?: break
                batch.add(r)
            }
            batch.sortBy { if (it.key in FIRST) FIRST.indexOf(it.key) else FIRST.size }
            // the engine and voice are part of the cache key: find them first (no engine: old
            // renderings of the same text and chain are still used, nothing new is made)
            val speaking = startEngine()
            for (r in batch) {
                if (stopped) return
                if (!fromCache(r) && speaking) bake(r)
            }
            while (!stopped) {
                val r = queue.poll(5, TimeUnit.SECONDS) ?: break
                if (!fromCache(r) && speaking) bake(r)
            }
            if (ready == 0 && !speaking) Log.i(TAG, "voice silent: $status")
        } catch (_: InterruptedException) {
        } finally {
            try {
                tts?.shutdown()
            } catch (e: RuntimeException) {
                // e.g. "Service not registered" when the binding is already gone: nothing left to free
                Log.w(TAG, "text-to-speech shutdown", e)
            }
            tts = null
        }
    }

    // ================================================================== the engine

    private var engine = ""
    private var voiceName = ""

    private fun startEngine(): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val t = TextToSpeech(app) { s ->
            ok = s == TextToSpeech.SUCCESS
            latch.countDown()
        }
        tts = t
        if (!latch.await(15, TimeUnit.SECONDS) || !ok) {
            status = "no text-to-speech engine"
            Log.i(TAG, status)
            return false
        }
        val voice = pickEnglish(t)
        if (voice == null) {
            status = "no English voice"
            Log.i(TAG, status)
            return false
        }
        engine = t.defaultEngine ?: "?"
        voiceName = voice.name
        t.setSpeechRate(SPEECH_RATE)
        t.setPitch(PITCH)
        status = "speaking with $engine / $voiceName"
        Log.i(TAG, status)
        return true
    }

    /** en-US, else en-GB, else any English voice that is installed and works offline; never another language. */
    private fun pickEnglish(t: TextToSpeech): Voice? {
        val voices = try {
            t.voices ?: emptySet()
        } catch (e: RuntimeException) {
            emptySet()
        }
        fun usable(v: Voice) = !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features && v.locale.language == "en"
        for (locale in listOf(Locale.US, Locale.UK)) {
            if (t.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) continue
            val best = voices.filter { usable(it) && it.locale.country == locale.country }.maxByOrNull { it.quality }
            if (best != null && t.setVoice(best) == TextToSpeech.SUCCESS) return best
            if (t.setLanguage(locale) >= TextToSpeech.LANG_AVAILABLE) {
                val v = t.voice
                if (v != null && v.locale.language == "en") return v
            }
        }
        val any = voices.filter { usable(it) }.maxByOrNull { it.quality } ?: return null
        return if (t.setVoice(any) == TextToSpeech.SUCCESS) any else null
    }

    private fun bake(r: Request) {
        val t = tts ?: return
        val wav = File(app.cacheDir, "tts-${r.key}.wav")
        val done = CountDownLatch(1)
        var failed = false
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                done.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                failed = true
                done.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                failed = true
                done.countDown()
            }
        })
        val result = t.synthesizeToFile(r.text, Bundle(), wav, r.key)
        if (result != TextToSpeech.SUCCESS || !done.await(20, TimeUnit.SECONDS) || failed) {
            Log.w(TAG, "could not speak ${r.key}")
            wav.delete()
            return
        }
        val pcm = readWav(wav)
        wav.delete()
        if (pcm == null) return
        val processed = Pcm(VoiceFx.process(pcm.data, pcm.channels, pcm.rate, r.radio), 1, pcm.rate)
        writeCache(r, processed)
        out.publish(r.sound, processed)
        ready++
    }

    // ================================================================== the cache

    /** What the rendering of a line depends on besides its text (the cache key with it). */
    private fun stampOf(r: Request): String = "$engine|$voiceName|${chainOf(r)}"

    private fun chainOf(r: Request): String = "chain${VoiceFx.CHAIN_VERSION}|radio=${r.radio}|rate=$SPEECH_RATE|pitch=$PITCH"

    /**
     * The cached rendering of [r]: same text and, with an engine running, the same engine, voice and
     * chain; without an engine any rendering of the same text through the same chain.
     */
    private fun fromCache(r: Request): Boolean {
        val f = File(dir, "${r.key}.pcm")
        if (!f.isFile) return false
        return try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val magic = ByteArray(MAGIC.size)
                s.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return false
                val text = ByteArray(s.readInt()).also { s.readFully(it) }
                if (String(text) != r.text) return false
                val stamp = String(ByteArray(s.readInt()).also { s.readFully(it) })
                if (if (engine.isNotEmpty()) stamp != stampOf(r) else !stamp.endsWith(chainOf(r))) return false
                val rate = s.readInt()
                val frames = s.readInt()
                val bytes = ByteArray(frames * 2).also { s.readFully(it) }
                val data = ShortArray(frames)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
                out.publish(r.sound, Pcm(data, 1, rate))
                ready++
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun writeCache(r: Request, pcm: Pcm) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "${r.key}.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.write(MAGIC)
                val text = r.text.toByteArray()
                s.writeInt(text.size)
                s.write(text)
                val stamp = stampOf(r).toByteArray()
                s.writeInt(stamp.size)
                s.write(stamp)
                s.writeInt(pcm.rate)
                s.writeInt(pcm.frames)
                val bb = ByteBuffer.allocate(pcm.frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().put(pcm.data, 0, pcm.frames)
                s.write(bb.array())
            }
            tmp.renameTo(File(dir, "${r.key}.pcm"))
        } catch (e: IOException) {
            Log.w(TAG, "voice cache ${r.key}", e)
        }
    }

    /** 16-bit PCM WAV (what synthesizeToFile writes) to [Pcm]; null for anything else. */
    private fun readWav(f: File): Pcm? {
        val b = try {
            f.readBytes()
        } catch (e: IOException) {
            return null
        }
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var i = 12
        var channels = 1
        var rate = 0
        var bits = 16
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val size = bb.getInt(i + 4)
            if (id == "fmt ") {
                channels = bb.getShort(i + 10).toInt()
                rate = bb.getInt(i + 12)
                bits = bb.getShort(i + 22).toInt()
            } else if (id == "data") {
                if (bits != 16 || rate <= 0) return null
                val n = minOf(size, b.size - i - 8) / 2
                val data = ShortArray(n)
                ByteBuffer.wrap(b, i + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
                return if (n > 0) Pcm(data, maxOf(1, channels), rate) else null
            }
            i += 8 + size + (size and 1)
        }
        return null
    }

    private companion object {
        const val TAG = "StarwakeVoice"
        val MAGIC = "SWVOICE1".toByteArray()

        /** A ship computer speaks a little slower and lower than the default. */
        const val SPEECH_RATE = 0.95f
        const val PITCH = 0.92f

        /** Lines the current game says, baked first (the rest follow). */
        val FIRST = listOf(
            "Welcome", "SystemsOnline", "FlightAssistOn", "FlightAssistOff", "ControlClearedToLaunch", "LaunchInitiated",
            "ControlDockingGranted", "DockingComputerEngaged", "Docked", "CruiseOn", "CruiseOff", "CruiseDropped",
            "BoostReady", "GearDown", "GearUp", "GravityEntered", "GravityLeft", "HullImpact", "Touchdown",
            "ControlDockingDenied",
        )
    }
}
