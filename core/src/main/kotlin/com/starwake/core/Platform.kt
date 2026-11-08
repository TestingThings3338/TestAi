package com.starwake.core

/** Services the host platform (Android, desktop harness) provides to the game. */
interface Platform {
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

    /** Closes the app (from the main menu). */
    fun exitApp()

    /** Short device description for the settings screen, e.g. "Xiaomi 24129PN74G · Android 15". */
    val deviceInfo: String
}

object Haptics {
    const val TAP = 0
    const val TICK = 1
    const val BUMP = 2
    const val HEAVY = 3
}
