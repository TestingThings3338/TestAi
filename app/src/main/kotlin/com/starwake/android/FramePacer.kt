package com.starwake.android

import android.opengl.GLSurfaceView
import android.view.Choreographer

/**
 * Drives a RENDERMODE_WHEN_DIRTY [GLSurfaceView] from vsync.
 *
 * Every Choreographer frame callback (UI thread) records the vsync timestamp and calls
 * requestRender(), so the GL thread starts each frame right after vsync, with touch input for that
 * vsync already delivered (Choreographer runs input callbacks before animation callbacks). When the
 * GPU/CPU falls behind, requests simply coalesce; eglSwapBuffers provides the back-pressure.
 *
 * An fps cap is honoured by skipping vsyncs: a frame is requested only when
 * `frameTime - lastRequested + vsyncInterval / 2 >= 1e9 / fpsLimit`. The half-interval slack absorbs
 * timestamp jitter so that, e.g., a 60 fps cap on a 120 Hz panel requests exactly every second vsync.
 * The vsync interval is estimated from successive callbacks with an EMA, so it follows LTPO/VRR
 * refresh switches without any display queries.
 *
 * UI thread only, except [frameTimeNanos] which the GL thread reads. Allocation-free per frame.
 */
internal class FramePacer(private val view: GLSurfaceView) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()

    /** Vsync timestamp (System.nanoTime base) of the most recently requested frame; 0 before the first. */
    @Volatile
    var frameTimeNanos = 0L
        private set

    /** 0 = render every vsync, otherwise the frames-per-second cap (60, 30). */
    var fpsLimit = 0
        set(value) {
            field = value.coerceAtLeast(0)
        }

    private var running = false
    private var lastVsync = 0L
    private var lastRequested = 0L
    private var vsyncIntervalNanos = NANOS_PER_SECOND / 60.0

    /** Starts requesting frames; [refreshHz] seeds the vsync-interval estimate. */
    fun start(refreshHz: Float) {
        if (running) return
        running = true
        if (refreshHz in 10f..500f) vsyncIntervalNanos = NANOS_PER_SECOND / refreshHz
        lastVsync = 0L
        lastRequested = 0L
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)

        val prev = lastVsync
        lastVsync = frameTimeNanos
        if (prev != 0L) {
            val dt = (frameTimeNanos - prev).toDouble()
            if (dt > 0.0) {
                // A late callback (missed vsync) counts as at most 1.5 intervals, so one hiccup barely
                // moves the estimate, while a real refresh-rate drop still converges within a few frames.
                val est = vsyncIntervalNanos
                vsyncIntervalNanos = est + (minOf(dt, est * 1.5) - est) * EMA_ALPHA
            }
        }

        val limit = fpsLimit
        if (limit > 0 && lastRequested != 0L) {
            val target = NANOS_PER_SECOND / limit
            if ((frameTimeNanos - lastRequested) + vsyncIntervalNanos * 0.5 < target) return
        }
        lastRequested = frameTimeNanos
        this.frameTimeNanos = frameTimeNanos
        view.requestRender()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val EMA_ALPHA = 0.1
    }
}
