package com.starwake.android

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.HapticFeedbackConstants
import com.starwake.core.Haptics
import com.starwake.core.Platform
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [Platform] for Android. The game calls in from the GL thread; anything touching views or the
 * window is posted to the UI thread with preallocated Runnables, so the hot calls ([haptic]) do not
 * allocate.
 *
 * Display policy ([applyDisplaySettings]): render scale -> fixed-size GL surface; fps limit ->
 * Choreographer pacing + preferred display mode (same resolution, best refresh) + Surface frame rate
 * hint + ADPF frame budget.
 */
internal class AndroidPlatform(private val activity: Activity) : Platform, DisplayManager.DisplayListener {
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayManager = activity.getSystemService(DisplayManager::class.java)

    @Volatile private var view: GameSurfaceView? = null

    // Requested display settings; written by any thread under the lock, applied on the UI thread.
    private val displayLock = Any()
    private var requestedScale = Float.NaN
    private var requestedFps = -1

    // UI thread only.
    private var appliedFps = -1

    private val applyDisplayRunnable = Runnable { applyDisplayOnUiThread(force = false) }
    private val exitRunnable = Runnable { activity.finish() }
    private val hapticTap = HapticRunnable(HapticFeedbackConstants.VIRTUAL_KEY)
    private val hapticTick = HapticRunnable(HapticFeedbackConstants.CLOCK_TICK)
    private val hapticBump = HapticRunnable(HapticFeedbackConstants.CONTEXT_CLICK)
    private val hapticHeavy = HapticRunnable(HapticFeedbackConstants.LONG_PRESS)

    override val deviceInfo: String = buildDeviceInfo()

    /** Must be called (UI thread) before the GL thread starts. */
    fun attach(view: GameSurfaceView) {
        this.view = view
    }

    // ================================================================ Platform

    override fun readAsset(path: String): ByteArray = activity.assets.open(path).use { it.readBytes() }

    override fun loadSettings(): String? = prefs.getString(KEY_SETTINGS, null)

    override fun saveSettings(data: String) {
        prefs.edit().putString(KEY_SETTINGS, data).apply()
    }

    override fun haptic(type: Int) {
        val v = view ?: return
        val r = when (type) {
            Haptics.TAP -> hapticTap
            Haptics.TICK -> hapticTick
            Haptics.BUMP -> hapticBump
            Haptics.HEAVY -> hapticHeavy
            else -> return
        }
        v.post(r) // Handler message from the pool: no allocation
    }

    override fun applyDisplaySettings(renderScale: Float, fpsLimit: Int) {
        val scale = renderScale.coerceIn(0.5f, 1f)
        val fps = if (fpsLimit > 0) fpsLimit else 0
        synchronized(displayLock) {
            if (scale == requestedScale && fps == requestedFps) return
            requestedScale = scale
            requestedFps = fps
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyDisplayOnUiThread(force = false)
        } else {
            mainHandler.post(applyDisplayRunnable)
        }
    }

    override fun exitApp() {
        mainHandler.post(exitRunnable)
    }

    // ================================================================ lifecycle hooks (UI thread)

    fun onResume() {
        displayManager?.registerDisplayListener(this, mainHandler)
        applyDisplayOnUiThread(force = true) // the display may have changed while we were away
    }

    fun onPause() {
        displayManager?.unregisterDisplayListener(this)
    }

    fun onConfigurationChanged() {
        applyDisplayOnUiThread(force = true)
    }

    /** Current refresh rate of the window's display, for seeding the frame pacer. */
    fun currentRefreshRate(): Float = currentDisplay()?.refreshRate ?: 60f

    // ================================================================ DisplayListener

    override fun onDisplayAdded(displayId: Int) {}

    override fun onDisplayRemoved(displayId: Int) {}

    override fun onDisplayChanged(displayId: Int) {
        val display = currentDisplay() ?: return
        if (display.displayId == displayId) updatePerfTarget(display)
    }

    // ================================================================ internals

    private fun applyDisplayOnUiThread(force: Boolean) {
        val v = view ?: return
        val scale: Float
        val fps: Int
        synchronized(displayLock) {
            scale = requestedScale
            fps = requestedFps
        }
        if (scale.isNaN()) return // nothing requested yet

        v.setRenderScale(scale) // no-op when unchanged
        if (!force && fps == appliedFps) return
        appliedFps = fps
        v.pacer.fpsLimit = fps

        val display = currentDisplay() ?: return
        val mode = selectDisplayMode(display, fps)
        if (mode != null) {
            val lp = activity.window.attributes
            if (lp.preferredDisplayModeId != mode.modeId) {
                lp.preferredDisplayModeId = mode.modeId
                activity.window.attributes = lp
            }
        }
        val maxRate = mode?.refreshRate ?: display.refreshRate
        v.setSurfaceFrameRate(if (fps > 0) fps.toFloat() else maxRate)
        updatePerfTarget(display)
    }

    /**
     * Same physical resolution as the current mode (never switch resolution), then:
     * - no cap: the highest refresh rate;
     * - cap: the lowest refresh >= cap that is an integer multiple of it (even pacing), else the
     *   lowest refresh >= cap, else the highest available.
     */
    private fun selectDisplayMode(display: Display, fps: Int): Display.Mode? {
        val current = display.mode
        var best: Display.Mode? = null
        var bestScore = Float.MAX_VALUE
        for (m in display.supportedModes) {
            if (m.physicalWidth != current.physicalWidth || m.physicalHeight != current.physicalHeight) continue
            val rate = m.refreshRate
            val score = if (fps <= 0) {
                -rate
            } else if (rate >= fps - 0.5f) {
                val ratio = rate / fps
                val multiple = abs(ratio - ratio.roundToInt()) < 0.02f
                (if (multiple) 0f else 10_000f) + rate
            } else {
                100_000f - rate
            }
            if (score < bestScore) {
                bestScore = score
                best = m
            }
        }
        return best
    }

    /** ADPF frame budget: the cap if set, otherwise the display's current refresh rate. */
    private fun updatePerfTarget(display: Display) {
        val v = view ?: return
        val refresh = display.refreshRate.takeIf { it >= 1f } ?: 60f
        val fps = appliedFps
        val effective = if (fps > 0) minOf(fps.toFloat(), refresh) else refresh
        v.perfHint.targetNanos = (1_000_000_000.0 / effective).toLong()
    }

    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        }

    private inner class HapticRunnable(private val feedback: Int) : Runnable {
        override fun run() {
            view?.performHapticFeedback(feedback)
        }
    }

    private companion object {
        const val PREFS_NAME = "starwake"
        const val KEY_SETTINGS = "settings"

        fun buildDeviceInfo(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val model = Build.MODEL.orEmpty()
            val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return "${name.trim()} · Android ${Build.VERSION.RELEASE}"
        }
    }
}
