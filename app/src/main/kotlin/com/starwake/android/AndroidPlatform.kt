package com.starwake.android

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import com.starwake.core.Haptics
import com.starwake.core.Platform
import com.starwake.core.save.AsyncSaveStorage
import com.starwake.core.save.FileSaveStorage
import java.io.File
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
 *
 * Active play ([setActivePlay]): the screen is kept on only while the player is flying, and on static
 * screens (menu, pause, settings) the pacer is capped at [IDLE_FPS] (or the user's lower cap) to save
 * battery. The display mode and Surface frame-rate hint keep following the user's setting, so
 * opening a menu never triggers a refresh-rate switch.
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

    /** Latest value from [setActivePlay]; any thread writes, the UI thread applies. Starts in the menu. */
    @Volatile private var activePlay = false

    // UI thread only.
    private var appliedFps = -1 // user's fps limit as applied (0 = display max), -1 before the first
    private var keepScreenOn = false // FLAG_KEEP_SCREEN_ON as currently set on the window

    private val applyDisplayRunnable = Runnable { applyDisplayOnUiThread(force = false) }
    private val applyActivePlayRunnable = Runnable { applyActivePlayOnUiThread() }
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

    override fun setActivePlay(active: Boolean) {
        if (activePlay == active) return
        activePlay = active
        // One preallocated Runnable that reads the latest value: rapid toggles coalesce correctly.
        mainHandler.post(applyActivePlayRunnable)
    }

    override fun exitApp() {
        mainHandler.post(exitRunnable)
    }

    // ================================================================ saves (core/save: profile.json, galaxy.json)

    /**
     * <filesDir>/saves: each file replaced atomically (temp + rename, previous kept as .bak), written
     * off the GL thread. writeSave answers once a write is queued; its outcome reaches the save
     * manager through [finishedWrites] and [writeState].
     */
    private val saves by lazy { AsyncSaveStorage(FileSaveStorage(File(activity.filesDir, "saves"))) }

    override fun readSave(name: String): ByteArray? = saves.readSave(name)

    override fun writeSave(name: String, data: ByteArray): Boolean = saves.writeSave(name, data)

    override val finishedWrites: Int get() = saves.finishedWrites

    override fun writeState(name: String): Int = saves.writeState(name)

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
        v.pacer.fpsLimit = effectiveFps()

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

    /** Keep-screen-on flag and idle frame cap; idempotent. */
    private fun applyActivePlayOnUiThread() {
        val active = activePlay
        if (active != keepScreenOn) {
            keepScreenOn = active
            if (active) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val v = view ?: return
        if (appliedFps < 0) return // display settings not applied yet; they pick up activePlay then
        val fps = effectiveFps()
        if (v.pacer.fpsLimit == fps) return
        v.pacer.fpsLimit = fps
        currentDisplay()?.let { updatePerfTarget(it) }
    }

    /**
     * Pacer cap actually used: the user's limit while flying (0 = display max), otherwise at most
     * [IDLE_FPS] (a lower user cap such as 30 is kept). UI thread.
     */
    private fun effectiveFps(): Int {
        val user = appliedFps.coerceAtLeast(0)
        if (activePlay) return user
        return if (user in 1..IDLE_FPS) user else IDLE_FPS
    }

    /** ADPF frame budget: the effective cap if set, otherwise the display's current refresh rate. */
    private fun updatePerfTarget(display: Display) {
        val v = view ?: return
        val refresh = display.refreshRate.takeIf { it >= 1f } ?: 60f
        val fps = v.pacer.fpsLimit
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
        /** SharedPreferences file (space_exploration.xml); backup_rules / data_extraction_rules name it. */
        const val PREFS_NAME = "space_exploration"
        const val KEY_SETTINGS = "settings"

        /** Frame cap on static screens (menu, pause, settings). */
        const val IDLE_FPS = 60

        fun buildDeviceInfo(): String {
            val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercaseChar() }
            val model = Build.MODEL.orEmpty()
            val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return "${name.trim()} · Android ${Build.VERSION.RELEASE}"
        }
    }
}
