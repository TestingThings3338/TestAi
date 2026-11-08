package com.starwake.android

import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Picks an OpenGL ES 3.0 window config, in order of preference:
 *  1. RGB888, depth 24, 4x MSAA   (MSAA resolve is nearly free on tiled mobile GPUs)
 *  2. RGB888, depth 24, no MSAA
 *  3. RGB888, depth 16, no MSAA
 *  4. anything ES3-renderable with depth >= 16 (last resort)
 *
 * eglChooseConfig treats sizes as minimums and sorts deeper colour formats first, so the candidates
 * are filtered by hand: exact 8/8/8 colour (no 10-bit), alpha 0 and no stencil preferred, the
 * smallest depth that satisfies the request, and never an EGL_SLOW_CONFIG. Runs once per context.
 */
internal class GameEglConfigChooser : GLSurfaceView.EGLConfigChooser {
    private val value = IntArray(1)

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val config = pick(egl, display, depth = 24, samples = 4, exactColor = true)
            ?: pick(egl, display, depth = 24, samples = 0, exactColor = true)
            ?: pick(egl, display, depth = 16, samples = 0, exactColor = true)
            ?: pick(egl, display, depth = 16, samples = 0, exactColor = false)
            ?: throw IllegalStateException("No OpenGL ES 3.0 EGLConfig available")
        Log.i(
            TAG,
            "EGL config: R${attr(egl, display, config, EGL10.EGL_RED_SIZE)}" +
                "G${attr(egl, display, config, EGL10.EGL_GREEN_SIZE)}" +
                "B${attr(egl, display, config, EGL10.EGL_BLUE_SIZE)}" +
                "A${attr(egl, display, config, EGL10.EGL_ALPHA_SIZE)} " +
                "D${attr(egl, display, config, EGL10.EGL_DEPTH_SIZE)} " +
                "S${attr(egl, display, config, EGL10.EGL_STENCIL_SIZE)} " +
                "MSAA x${attr(egl, display, config, EGL10.EGL_SAMPLES)}",
        )
        return config
    }

    private fun pick(egl: EGL10, display: EGLDisplay, depth: Int, samples: Int, exactColor: Boolean): EGLConfig? {
        val colorBits = if (exactColor) 8 else 5
        val base = intArrayOf(
            EGL10.EGL_RED_SIZE, colorBits,
            EGL10.EGL_GREEN_SIZE, colorBits,
            EGL10.EGL_BLUE_SIZE, colorBits,
            EGL10.EGL_DEPTH_SIZE, depth,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_WINDOW_BIT,
        )
        val attribs = if (samples > 0) {
            base + intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
        } else {
            base + EGL10.EGL_NONE
        }

        val count = IntArray(1)
        if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) return null
        val configs = arrayOfNulls<EGLConfig>(count[0])
        if (!egl.eglChooseConfig(display, attribs, configs, configs.size, count)) return null

        var best: EGLConfig? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until count[0]) {
            val c = configs[i] ?: continue
            if (attr(egl, display, c, EGL10.EGL_CONFIG_CAVEAT) == EGL10.EGL_SLOW_CONFIG) continue
            val r = attr(egl, display, c, EGL10.EGL_RED_SIZE)
            val g = attr(egl, display, c, EGL10.EGL_GREEN_SIZE)
            val b = attr(egl, display, c, EGL10.EGL_BLUE_SIZE)
            if (exactColor && (r != 8 || g != 8 || b != 8)) continue
            val sampleBuffers = attr(egl, display, c, EGL10.EGL_SAMPLE_BUFFERS)
            val s = attr(egl, display, c, EGL10.EGL_SAMPLES)
            if (samples > 0 && (sampleBuffers != 1 || s != samples)) continue
            if (samples == 0 && sampleBuffers != 0) continue
            val a = attr(egl, display, c, EGL10.EGL_ALPHA_SIZE)
            val d = attr(egl, display, c, EGL10.EGL_DEPTH_SIZE)
            val st = attr(egl, display, c, EGL10.EGL_STENCIL_SIZE)
            val score = (if (a == 0) 0 else 1000) + (d - depth) * 10 + st +
                (if (exactColor) 0 else (24 - (r + g + b)).coerceAtLeast(0) * 100)
            if (score < bestScore) {
                bestScore = score
                best = c
            }
        }
        return best
    }

    private fun attr(egl: EGL10, display: EGLDisplay, config: EGLConfig, attribute: Int): Int =
        if (egl.eglGetConfigAttrib(display, config, attribute, value)) value[0] else 0

    private companion object {
        const val TAG = "Starwake"

        /** EGL_KHR_create_context / EGL 1.5: config supports OpenGL ES 3.x contexts. */
        const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    }
}
