package com.starwake.android

import android.opengl.GLSurfaceView
import android.view.Choreographer
import kotlin.math.ceil

/**
 * Drives a RENDERMODE_WHEN_DIRTY [GLSurfaceView] from vsync.
 *
 * Every Choreographer frame callback (UI thread) records the vsync timestamp and calls
 * requestRender(), so the GL thread starts each frame right after vsync, with touch input for that
 * vsync already delivered (Choreographer runs input callbacks before animation callbacks). When the
 * GPU/CPU falls behind, requests simply coalesce; eglSwapBuffers provides the back-pressure.
 *
 * An fps cap is honoured by rendering every `skip`-th vsync, with
 * `skip = max(1, ceil(targetInterval / vsyncInterval - 0.05))`: whole vsyncs, rounded up, so the cap
 * is never exceeded and the cadence is deterministic (120 Hz + 60 cap -> 60 fps, 120 Hz + 30 -> 30,
 * 90 Hz + 60 -> 45, 60 Hz + 60 -> 60; the 0.05 absorbs estimate noise on exact multiples). A frame
 * is requested once `frameTime - lastRequested >= (skip - 0.5) * vsyncInterval`; the half-interval
 * margin absorbs timestamp jitter. The vsync interval is estimated from successive callbacks with an
 * EMA, so it follows LTPO/VRR refresh switches without any display queries.
 *
 * UI thread only, except [frameTimeNanos] which the GL thread reads. Allocation-free per frame.
 */
internal class FramePacer(private val view: GLSurfaceView) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()

    /** Vsync timestamp (System.nanoTime base) of the most recently requested frame; 0 before the first. */
    @Volatile
    var frameTimeNanos = 0L
        private set

    /** 0 = render every vsync, otherwise the frames-per-second cap (60, 30). UI thread. */
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
            val interval = vsyncIntervalNanos
            val skip = maxOf(1.0, ceil(NANOS_PER_SECOND / limit / interval - SKIP_TOLERANCE))
            if ((frameTimeNanos - lastRequested) < (skip - 0.5) * interval) return
        }
        lastRequested = frameTimeNanos
        this.frameTimeNanos = frameTimeNanos
        view.requestRender()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val EMA_ALPHA = 0.1

        /** Vsyncs per frame within 5% of a whole number round down (exact multiples stay exact). */
        const val SKIP_TOLERANCE = 0.05
    }
}
