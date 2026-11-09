package com.starwake.android

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.starwake.core.audio.Bus
import com.starwake.core.audio.MixerAudioOut
import com.starwake.core.audio.OggInfo
import com.starwake.core.audio.Pcm
import com.starwake.core.audio.SoundBank
import com.starwake.core.audio.VoiceFx
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport

/**
 * The game's sound on Android (`09` 8.4): the core [com.starwake.core.audio.Mixer] fed into one
 * AudioTrack (float, 48 kHz stereo, PERFORMANCE_MODE_LOW_LATENCY, USAGE_GAME) by a mixer thread at
 * urgent-audio priority; every effect and voice line decoded once (MediaExtractor + MediaCodec) on
 * a loader thread; the two music slots streamed by MediaPlayers on their own thread, with pause and
 * resume at the same position and gapless loops; audio focus (DECISIONS 40: see [Focus]);
 * [onPause]/[onResume] hold and continue everything.
 *
 * The game thread only calls the non-allocating [com.starwake.core.audio.AudioOut] methods; nothing
 * here blocks it. A sound that is not decoded yet is silent; a voice line without the user's
 * recording is spoken by [AndroidVoiceBaker] (text-to-speech, cached).
 */
internal class AndroidAudio(private val context: Context) : MixerAudioOut() {
    private val assets: AssetManager = context.assets
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "audio-loader")
    }
    private val baker = AndroidVoiceBaker(context, this)

    // ---------------------------------------------------------------- output
    private var track: AudioTrack? = null
    private var mixerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var held = false
    @Volatile var underruns = 0
        private set

    // ---------------------------------------------------------------- music (MediaPlayer per music slot)
    private val musicThread = HandlerThread("audio-music").also { it.start() }
    private val musicHandler = Handler(musicThread.looper)
    private val music = arrayOf(MusicSlot(SoundBank.SLOT_MUSIC_FLIGHT), MusicSlot(SoundBank.SLOT_MUSIC_MENU))
    private val streamPath = arrayOfNulls<String>(SoundBank.CAPACITY)
    @Volatile private var musicBus = 1f

    // ---------------------------------------------------------------- focus
    /** The audio focus state ([Focus]); written by the UI thread and the focus listener. */
    @Volatile private var focus = Focus.FULL
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(ATTRIBUTES)
        .setAcceptsDelayedFocusGain(true) // refused during a call: granted later, through onFocusChange
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, musicHandler)
        .build()

    init {
        mixer.headroom = SoundBank.HEADROOM
    }

    // ================================================================== lifecycle (UI thread)

    /** Starts the output (first resume) or continues it. */
    fun onResume() {
        if (track == null) startOutput()
        // focus first, so the first burst and the music already know it; refused or delayed (a call
        // in progress): held until AUDIOFOCUS_GAIN arrives
        focus = when (audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> Focus.FULL
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Focus.HELD
            else -> Focus.LOST
        }
        held = false
        track?.play()
        mixerThread?.let { LockSupport.unpark(it) }
        for (m in music) musicHandler.post(m.applyState)
    }

    /** The app leaves the screen: everything holds where it is. */
    fun onPause() {
        settle()
        held = true
        track?.pause()
        for (m in music) musicHandler.post(m.applyState)
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * Before the track stops. The game has just held the ship's sound and the announcer (the activity
     * runs Game.onBackground on the GL thread before it pauses): let the mixer render that one-burst
     * fade and play out what the track has queued, so nothing of the ship waits in the track's buffer
     * for the return. Bounded by [SETTLE_NANOS].
     */
    private fun settle() {
        val t = track ?: return
        if (held || !running) return
        // the fade lands in the next burst or the one after; then the track's buffer plays out
        val bursts = mixer.bursts + t.bufferSizeInFrames / BURST + 3
        val deadline = System.nanoTime() + SETTLE_NANOS
        while (mixer.bursts < bursts && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L)
    }

    fun release() {
        running = false
        held = false
        mixerThread?.let { LockSupport.unpark(it) }
        mixerThread?.join(500)
        track?.release()
        track = null
        for (m in music) musicHandler.post(m.release)
        musicThread.quitSafely()
        loader.shutdownNow()
        baker.shutdown()
    }

    private fun startOutput() {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, BURST * 2 * 4 * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: RuntimeException) {
            Log.w(TAG, "no audio output", e)
            return
        }
        track = t
        mixer.snapBuses()
        running = true
        mixerThread = Thread({ mixLoop(t) }, "audio-mixer").also { it.start() }
    }

    /** The mixer thread: one burst after the other, the blocking write paces it. Allocates nothing. */
    private fun mixLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(BURST * 2)
        var written = buf.size // floats of the current burst already in the track
        var level = 1f // the output's focus level at the end of the last burst
        while (running) {
            if (held) {
                LockSupport.park(this)
                continue
            }
            if (written == buf.size) {
                renderBurst(buf, BURST)
                level = applyFocus(buf, level)
                written = 0
            }
            // pause() can cut a blocking write short: the rest of the burst goes out after the resume,
            // so the stream continues where it stopped instead of jumping
            val n = t.write(buf, written, buf.size - written, AudioTrack.WRITE_BLOCKING)
            if (n < 0) {
                // a dead track (device change): drop the burst and wait a little instead of spinning
                written = buf.size
                LockSupport.parkNanos(this, 20_000_000L)
            } else {
                written += n
                if (n == 0 && !held) LockSupport.parkNanos(this, 1_000_000L)
            }
            underruns = t.underrunCount
        }
    }

    /**
     * The effects and the announcer under audio focus ([Focus]): [buf] fades from [from] to the level
     * the focus wants now over the burst; returns that level. Mixer thread, no allocation.
     */
    private fun applyFocus(buf: FloatArray, from: Float): Float {
        val to = when (focus) {
            Focus.HELD -> 0f
            Focus.DUCKED -> DUCK
            else -> 1f
        }
        if (from == 1f && to == 1f) return to
        val step = (to - from) / (buf.size / 2)
        var g = from
        var i = 0
        while (i < buf.size) {
            g += step
            buf[i] *= g
            buf[i + 1] *= g
            i += 2
        }
        return to
    }

    // ================================================================== loading (loader thread)

    override fun load(sound: Int, path: String, stream: Boolean) {
        if (stream) {
            streamPath[sound] = path
            loader.execute { probeStream(sound, path) }
        } else {
            loader.execute { loadEffect(sound, path) }
        }
    }

    override fun loadVoice(sound: Int, path: String, text: String, radio: Boolean) {
        val key = path.substringAfterLast('/').substringBefore('.')
        loader.execute {
            // the user's own dry recording wins; else the device's voice speaks the text
            val pcm = if (exists(path)) decode(path) else null
            if (pcm != null) {
                publish(sound, Pcm(VoiceFx.process(pcm.data, pcm.channels, pcm.rate, radio), 1, pcm.rate))
            } else {
                baker.request(sound, key, text, radio)
            }
        }
    }

    private fun loadEffect(sound: Int, path: String) {
        val pcm = decode(path)
        if (pcm == null) {
            markFailed(sound)
            return
        }
        publish(sound, if (SoundBank.loopPadded(sound)) SoundBank.unpad(pcm) else pcm)
    }

    private fun probeStream(sound: Int, path: String) {
        val ex = MediaExtractor()
        try {
            assets.openFd(path).use { afd ->
                ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                val f = ex.getTrackFormat(0)
                val us = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                if (us > 0) publishStream(sound, us / 1e6f) else markFailed(sound)
            }
        } catch (e: IOException) {
            markFailed(sound)
        } catch (e: RuntimeException) {
            markFailed(sound)
        } finally {
            ex.release()
        }
    }

    private fun exists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (e: IOException) {
        false
    }

    /** Decodes an asset to 16-bit PCM at its own rate, cut to its exact length (OGG end trim). */
    private fun decode(path: String): Pcm? {
        val bytes = try {
            assets.open(path).use { it.readBytes() }
        } catch (e: IOException) {
            return null
        }
        val exact = OggInfo.frames(bytes)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            assets.openFd(path).use { afd -> ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            if (ex.trackCount < 1) return null
            val inFormat = ex.getTrackFormat(0)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            ex.selectTrack(0)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var out = ShortArray(65536)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            while (!done) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val b = c.getInputBuffer(i)!!
                        val size = ex.readSampleData(b, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    val count = if (float) info.size / 4 else info.size / 2
                    if (n + count > out.size) out = out.copyOf(maxOf(out.size * 2, n + count))
                    if (float) {
                        val fb = b.asFloatBuffer()
                        for (k in 0 until count) out[n + k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    } else {
                        b.asShortBuffer().get(out, n, count)
                    }
                    n += count
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            if (n == 0 || channels < 1) return null
            return OggInfo.trim(Pcm(out.copyOf(n - n % channels), channels, rate), exact)
        } catch (e: IOException) {
            Log.w(TAG, "decode $path", e)
            return null
        } catch (e: RuntimeException) {
            Log.w(TAG, "decode $path", e)
            return null
        } finally {
            codec?.let {
                try {
                    it.stop()
                } catch (_: RuntimeException) {
                }
                it.release()
            }
            ex.release()
        }
    }

    // ================================================================== music slots (game thread side)

    private fun musicSlot(slot: Int): MusicSlot? =
        if (slot == SoundBank.SLOT_MUSIC_FLIGHT) music[0] else if (slot == SoundBank.SLOT_MUSIC_MENU) music[1] else null

    override fun play(slot: Int, sound: Int, gain: Float, rate: Float, loop: Boolean, startAt: Float, bus: Int) {
        val m = musicSlot(slot)
        if (m != null && sound in streamPath.indices && streamPath[sound] != null) {
            m.play(streamPath[sound]!!, gain, loop, startAt)
        } else {
            super.play(slot, sound, gain, rate, loop, startAt, bus)
        }
    }

    override fun set(slot: Int, gain: Float, rate: Float) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.setGain(gain) else super.set(slot, gain, rate)
    }

    override fun stop(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.stop() else super.stop(slot)
    }

    override fun pause(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.pause() else super.pause(slot)
    }

    override fun resume(slot: Int) {
        val m = musicSlot(slot)
        if (m != null && m.active) m.resume() else super.resume(slot)
    }

    override fun isPlaying(slot: Int): Boolean {
        val m = musicSlot(slot)
        return if (m != null && m.active) m.isPlaying() else super.isPlaying(slot)
    }

    override fun position(slot: Int): Float {
        val m = musicSlot(slot)
        return if (m != null && m.active) m.positionMs / 1000f else super.position(slot)
    }

    override fun setBusGain(bus: Int, gain: Float) {
        if (bus == Bus.MUSIC) {
            if (gain != musicBus) {
                musicBus = gain
                for (m in music) m.volumeChanged()
            }
        } else {
            super.setBusGain(bus, gain)
        }
    }

    /** Music thread (the listener's handler). */
    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> focus = Focus.FULL
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focus = Focus.DUCKED
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focus = Focus.HELD
            AudioManager.AUDIOFOCUS_LOSS -> focus = Focus.LOST
        }
        for (m in music) m.applyState.run()
    }

    /** The music's level under audio focus (it pauses in [Focus.HELD] and [Focus.LOST]). */
    private val musicDuck: Float get() = if (focus == Focus.DUCKED) DUCK else 1f

    /** The music plays: focus held or ducked. */
    private val musicAllowed: Boolean get() = focus == Focus.FULL || focus == Focus.DUCKED

    /**
     * Audio focus (DECISIONS 40). The music follows it (ducked, or paused and continued later); the
     * effects and the announcer duck with a duckable loss and fall silent while another app needs the
     * sound to itself (a call, the assistant, a refused request during a call), but keep playing under
     * another app's music after a lasting loss, as the game's own feedback.
     */
    private object Focus {
        /** Ours: everything plays. */
        const val FULL = 0

        /** A duckable transient loss: the music and the effects at [DUCK]. */
        const val DUCKED = 1

        /** A transient loss, or a request refused or delayed (a call): the music paused, the effects silent. */
        const val HELD = 2

        /** A lasting loss (another app's music): the music paused, the effects on; asked again on resume. */
        const val LOST = 3
    }

    /**
     * One streamed music slot. The game thread writes the wanted state into volatile fields and posts
     * a preallocated Runnable; the music thread applies it to its MediaPlayer (prepare, seek, start,
     * pause, volume) and reports position and completion back.
     *
     * A looped track (the menu music, the flight fallback) is not looped with setLooping, which
     * rewinds only after the output has drained (a dropout at the loop point). Two players hold the
     * same file instead, the second set as the first's next player: the framework hands the output
     * over to it without a gap, and the finished one is prepared again as the next of the other.
     */
    private inner class MusicSlot(val slot: Int) {
        @Volatile var active = false
        @Volatile private var wantPath: String? = null
        @Volatile private var wantLoop = false
        @Volatile private var wantStart = 0f
        @Volatile private var wantPlaying = false
        @Volatile private var wantPaused = false
        @Volatile private var gain = 0f
        @Volatile private var generation = 0
        @Volatile private var ended = false
        @Volatile var positionMs = 0

        // ---- music thread
        /** The player that plays (or holds) the track. */
        private var player: MediaPlayer? = null

        /** A looped track's second player: prepared on the same file, set as [player]'s next. */
        private var next: MediaPlayer? = null
        private var appliedGeneration = -1
        private var prepared = false
        @Volatile private var pendingVolume = false

        private val completion = MediaPlayer.OnCompletionListener { mp -> completed(mp) }

        val applyState = Runnable { apply() }
        val release = Runnable { releasePlayers() }
        private val applyVolume = Runnable {
            pendingVolume = false
            volume()
        }
        private val poll = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && prepared) {
                    try {
                        positionMs = p.currentPosition
                    } catch (_: IllegalStateException) {
                    }
                }
                if (active) musicHandler.postDelayed(this, 100)
            }
        }

        fun play(path: String, g: Float, loop: Boolean, startAt: Float) {
            wantPath = path
            wantLoop = loop
            wantStart = startAt
            wantPlaying = true
            wantPaused = false
            gain = g
            ended = false
            positionMs = (startAt * 1000).toInt()
            generation++
            if (!active) {
                active = true
                musicHandler.post(poll)
            }
            musicHandler.post(applyState)
        }

        fun setGain(g: Float) {
            if (g == gain) return
            gain = g
            volumeChanged()
        }

        fun volumeChanged() {
            if (!pendingVolume) {
                pendingVolume = true
                musicHandler.post(applyVolume)
            }
        }

        fun stop() {
            wantPlaying = false
            active = false
            musicHandler.post(applyState)
        }

        fun pause() {
            wantPaused = true
            musicHandler.post(applyState)
        }

        fun resume() {
            wantPaused = false
            musicHandler.post(applyState)
        }

        fun isPlaying(): Boolean = active && wantPlaying && !wantPaused && !ended

        // ---- music thread
        private fun apply() {
            if (!wantPlaying) {
                releasePlayers()
                appliedGeneration = -1
                return
            }
            val gen = generation
            if (gen != appliedGeneration) {
                // a new track: fresh players (a completion still queued from the old ones finds them
                // released and is dropped)
                appliedGeneration = gen
                releasePlayers()
                val path = wantPath ?: return
                try {
                    val p = newPlayer()
                    player = p
                    load(p, path)
                    prepared = true
                    if (wantStart > 0f) p.seekTo((wantStart * 1000).toInt())
                    if (wantLoop) armNext(p, path)
                } catch (e: IOException) {
                    Log.w(TAG, "music $wantPath", e)
                    releasePlayers()
                    ended = true
                    return
                } catch (e: RuntimeException) {
                    Log.w(TAG, "music $wantPath", e)
                    releasePlayers()
                    ended = true
                    return
                }
            }
            val p = player ?: return
            if (!prepared) return
            volume()
            val run = shouldRun()
            try {
                if (run && !p.isPlaying && !ended) p.start() else if (!run && p.isPlaying) p.pause()
            } catch (_: IllegalStateException) {
            }
        }

        /** Not paused by the game, the app on screen, and the focus lets the music play. */
        private fun shouldRun(): Boolean = !wantPaused && !held && musicAllowed

        private fun newPlayer(): MediaPlayer = MediaPlayer().also { it.setOnCompletionListener(completion) }

        /** [mp] (idle) on [path], prepared; its volume set. */
        private fun load(mp: MediaPlayer, path: String) {
            mp.setAudioAttributes(ATTRIBUTES)
            assets.openFd(path).use { afd -> mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            mp.prepare()
            setVolume(mp)
        }

        /**
         * [mp] (a new player, or one that finished) prepared on [path] as the next player of [current].
         * Should that fail, the loop goes on with a gap ([completed]) rather than not at all.
         */
        private fun armNext(current: MediaPlayer, path: String, mp: MediaPlayer = newPlayer()) {
            next = mp
            try {
                load(mp, path)
                current.setNextMediaPlayer(mp)
            } catch (e: IOException) {
                Log.w(TAG, "music loop $path", e)
                mp.release()
                next = null
            } catch (e: RuntimeException) {
                Log.w(TAG, "music loop $path", e)
                mp.release()
                next = null
            }
        }

        /** A player reached the end of the file (music thread). */
        private fun completed(mp: MediaPlayer) {
            if (mp !== player) return // a released or superseded player
            if (!wantLoop) {
                ended = true
                return
            }
            val n = next
            if (n == null) {
                // no second player: loop with a gap rather than not at all
                try {
                    mp.seekTo(0)
                    if (shouldRun()) mp.start()
                } catch (_: IllegalStateException) {
                }
                return
            }
            // the framework has started the next player on the finished one's output: it plays now,
            // and the finished one, prepared again on the same file, becomes its next
            player = n
            next = null
            positionMs = 0
            val path = wantPath
            if (path == null) {
                mp.release()
                return
            }
            try {
                mp.reset()
            } catch (_: IllegalStateException) {
            }
            armNext(n, path, mp)
        }

        private fun releasePlayers() {
            player?.release()
            next?.release()
            player = null
            next = null
            prepared = false
        }

        private fun volume() {
            if (!prepared) return
            player?.let { setVolume(it) }
            next?.let { setVolume(it) }
        }

        private fun setVolume(mp: MediaPlayer) {
            val v = (gain * musicBus * musicDuck * SoundBank.HEADROOM).coerceIn(0f, 1f)
            try {
                mp.setVolume(v, v)
            } catch (_: IllegalStateException) {
            }
        }
    }

    private companion object {
        const val TAG = "StarwakeAudio"
        const val RATE = 48_000

        /** Frames per mixer burst (5.3 ms at 48 kHz). */
        const val BURST = 256

        /** Music and effects level while another app holds duckable transient focus. */
        const val DUCK = 0.25f

        /** The longest [onPause] waits for the mixer to play out the held sound (ns). */
        const val SETTLE_NANOS = 80_000_000L

        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
