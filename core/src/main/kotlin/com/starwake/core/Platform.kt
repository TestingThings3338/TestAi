package com.starwake.core

import com.starwake.core.audio.AudioOut
import com.starwake.core.audio.NullAudioOut
import com.starwake.core.save.SaveStorage

/** Services the host platform (Android, desktop harness) provides to the game. */
interface Platform : SaveStorage {
    /** Reads a file from the app's assets, e.g. "font/ui.fnt". */
    fun readAsset(path: String): ByteArray

    /** Returns the previously saved settings string, or null. */
    fun loadSettings(): String?

    /** Persists the settings string (called rarely, from the GL thread). */
    fun saveSettings(data: String)

    /** Short haptic feedback, see [Haptics]. Must be cheap and safe to call from the GL thread. */
    fun haptic(type: Int)

    /**
     * Apply display settings: [renderScale] (0.5..1, fraction of native resolution for the GL
     * surface) and [fpsLimit] (0 = display maximum, otherwise 60 or 30). May trigger a new
     * onSurfaceChanged with a different size. Called from the GL thread.
     */
    fun applyDisplaySettings(renderScale: Float, fpsLimit: Int)

    /**
     * True while the player is actively flying (no menu/pause/settings). The platform keeps the
     * screen awake only then, and may lower the frame rate (e.g. to 60) on static screens to save
     * battery. Called from the GL thread, only when the value changes.
     */
    fun setActivePlay(active: Boolean)

    /** Closes the app (from the main menu). */
    fun exitApp()

    /** Short device description for the settings screen, e.g. "Xiaomi 24129PN74G · Android 15". */
    val deviceInfo: String

    /** The sound output (`09` 8.4): mixer slots, buses, loading. Default: none (silent). */
    val audio: AudioOut get() = NullAudioOut

    /**
     * Reads save file [name] ("profile.json", "galaxy.json" or its ".bak"), null when missing or
     * unreadable (save/SaveManager). Default: no save storage.
     */
    override fun readSave(name: String): ByteArray? = null

    /**
     * Stores save file [name] atomically (temp file + rename), keeping the version it replaces as
     * "<name>.bak"; may finish on a background thread (never block the GL thread on disk), and then
     * reports the outcome through [finishedWrites] and [writeState] (see [SaveStorage]). False when
     * saves cannot be stored. Default: no save storage (the game runs, nothing persists).
     */
    override fun writeSave(name: String, data: ByteArray): Boolean = false
}

object Haptics {
    const val TAP = 0
    const val TICK = 1
    const val BUMP = 2
    const val HEAVY = 3
}
