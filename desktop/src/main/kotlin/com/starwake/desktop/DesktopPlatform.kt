package com.starwake.desktop

import com.starwake.core.Haptics
import com.starwake.core.Platform
import java.io.File

/**
 * [Platform] for the harness: assets straight from the app module's source tree, settings kept in
 * memory, side effects only logged. Logging is suppressed while [quiet] (allocation measurement).
 */
class DesktopPlatform(private val assetsDir: File, initialSettings: String? = null) : Platform {
    private var settings: String? = initialSettings
    @Volatile var quiet = false

    /** Haptic pulses per type (TAP, TICK, BUMP, HEAVY), counted without allocating. */
    val haptics = IntArray(4)
    var exitRequested = false
        private set
    var lastRenderScale = 1f
        private set
    var lastFpsLimit = 0
        private set

    override fun readAsset(path: String): ByteArray {
        val f = File(assetsDir, path)
        if (!f.isFile) throw IllegalArgumentException("asset not found: ${f.path} (assets dir ${assetsDir.absolutePath})")
        return f.readBytes()
    }

    override fun loadSettings(): String? = settings

    override fun saveSettings(data: String) {
        settings = data
        log { "[platform] saveSettings($data)" }
    }

    override fun haptic(type: Int) {
        if (type in haptics.indices) haptics[type]++
        log { "[platform] haptic(${hapticName(type)})" }
    }

    override fun applyDisplaySettings(renderScale: Float, fpsLimit: Int) {
        lastRenderScale = renderScale
        lastFpsLimit = fpsLimit
        log { "[platform] applyDisplaySettings(renderScale=$renderScale, fpsLimit=$fpsLimit)" }
    }

    override fun exitApp() {
        exitRequested = true
        log { "[platform] exitApp()" }
    }

    override val deviceInfo: String = "Desktop preview (Mesa llvmpipe)"

    private inline fun log(msg: () -> String) {
        if (!quiet) println(msg())
    }

    companion object {
        fun hapticName(type: Int) = when (type) {
            Haptics.TAP -> "TAP"
            Haptics.TICK -> "TICK"
            Haptics.BUMP -> "BUMP"
            Haptics.HEAVY -> "HEAVY"
            else -> type.toString()
        }
    }
}
