package com.starwake.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Process
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowInsets
import com.starwake.core.Game
import com.starwake.core.input.InputQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The game's GL surface: ES 3.0 context, vsync-paced on-demand rendering ([FramePacer]), touch
 * forwarding, render-scale (fixed-size surface scaled up by the display hardware), safe insets and
 * back-gesture exclusion zones.
 *
 * Threads: everything here runs on the UI thread except [GameRenderer], which runs on the GL thread.
 * UI-to-GL data (view size, density, insets) goes through @Volatile fields plus a generation counter
 * that makes the GL thread re-send the layout to the game before its next frame.
 */
@SuppressLint("ViewConstructor") // created from code only; never inflated
internal class GameSurfaceView(context: Context, private val game: Game) : GLSurfaceView(context) {
    val pacer = FramePacer(this)
    val perfHint = PerfHint(context)

    // ---- UI thread -> GL thread
    @Volatile private var viewWidth = 0
    @Volatile private var viewHeight = 0
    @Volatile private var baseDensity = resources.displayMetrics.density
    @Volatile private var safeLeft = 0f
    @Volatile private var safeTop = 0f
    @Volatile private var safeRight = 0f
    @Volatile private var safeBottom = 0f
    @Volatile private var layoutGeneration = 0

    // ---- UI thread only
    private var renderScale = 1f
    private var fixedWidth = 0 // 0 = surface follows the view size
    private var fixedHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var touchScaleX = 1f
    private var touchScaleY = 1f
    private var surfaceValid = false
    private var desiredFrameRate = 0f
    private var appliedFrameRate = -1f
    private val exclusionLeft = Rect()
    private val exclusionRight = Rect()
    private val exclusionRects = listOf(exclusionLeft, exclusionRight)

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(GameEglConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(GameRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY
        holder.addCallback(SurfaceCallback())

        // Focusable so controller D-pad keys are never swallowed by "leave touch mode" handling.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        isHapticFeedbackEnabled = true
    }

    // ================================================================ settings (UI thread)

    /** Fraction (0.5..1) of the view resolution to render at; the compositor upscales. */
    fun setRenderScale(scale: Float) {
        renderScale = scale.coerceIn(0.25f, 1f)
        updateFixedSize()
    }

    /** Frame rate hint for the compositor / refresh-rate selection (API 30+). 0 = no preference. */
    fun setSurfaceFrameRate(fps: Float) {
        desiredFrameRate = fps
        applySurfaceFrameRate()
    }

    /** Density or display size changed without recreating the Activity. */
    fun onDisplayConfigurationChanged() {
        baseDensity = resources.displayMetrics.density
        updateGestureExclusion()
        layoutGeneration++
    }

    // ================================================================ layout (UI thread)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        updateFixedSize()
        updateTouchScale()
        updateGestureExclusion()
        layoutGeneration++
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var l = 0f
        var t = 0f
        var r = 0f
        var b = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout?.let {
                l = it.safeInsetLeft.toFloat()
                t = it.safeInsetTop.toFloat()
                r = it.safeInsetRight.toFloat()
                b = it.safeInsetBottom.toFloat()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Keep UI hugging a corner visible: content at distance d from both edges of a corner
            // with radius R is unclipped when d >= R * (1 - 1/sqrt(2)).
            val tl = cornerRadius(insets, RoundedCorner.POSITION_TOP_LEFT)
            val tr = cornerRadius(insets, RoundedCorner.POSITION_TOP_RIGHT)
            val bl = cornerRadius(insets, RoundedCorner.POSITION_BOTTOM_LEFT)
            val br = cornerRadius(insets, RoundedCorner.POSITION_BOTTOM_RIGHT)
            l = max(l, max(tl, bl) * CORNER_INSET_FACTOR)
            t = max(t, max(tl, tr) * CORNER_INSET_FACTOR)
            r = max(r, max(tr, br) * CORNER_INSET_FACTOR)
            b = max(b, max(bl, br) * CORNER_INSET_FACTOR)
        }
        if (l != safeLeft || t != safeTop || r != safeRight || b != safeBottom) {
            safeLeft = l
            safeTop = t
            safeRight = r
            safeBottom = b
            layoutGeneration++ // e.g. a 180-degree flip moves the cutout without a surface resize
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun cornerRadius(insets: WindowInsets, position: Int): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (insets.getRoundedCorner(position)?.radius ?: 0).toFloat()
        } else {
            0f
        }

    private fun updateFixedSize() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return // applied from onSizeChanged once laid out
        if (renderScale >= 0.999f) {
            if (fixedWidth != 0) {
                fixedWidth = 0
                fixedHeight = 0
                holder.setSizeFromLayout()
            }
        } else {
            val fw = max(1, (w * renderScale).roundToInt())
            val fh = max(1, (h * renderScale).roundToInt())
            if (fw != fixedWidth || fh != fixedHeight) {
                fixedWidth = fw
                fixedHeight = fh
                holder.setFixedSize(fw, fh)
            }
        }
    }

    private fun updateTouchScale() {
        val w = width
        val h = height
        touchScaleX = if (surfaceWidth > 0 && w > 0) surfaceWidth.toFloat() / w else 1f
        touchScaleY = if (surfaceHeight > 0 && h > 0) surfaceHeight.toFloat() / h else 1f
    }

    /**
     * Gesture navigation: exempt the vertical middle of both side edges (where the throttle and
     * flight stick live) from the back swipe. The system honours at most 200dp per edge.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val d = resources.displayMetrics.density
        val band = (EXCLUSION_HEIGHT_DP * d).toInt().coerceAtMost(h)
        val edge = (EXCLUSION_WIDTH_DP * d).toInt().coerceAtMost(w / 2)
        val top = (h - band) / 2
        exclusionLeft.set(0, top, edge, top + band)
        exclusionRight.set(w - edge, top, w, top + band)
        systemGestureExclusionRects = exclusionRects // the setter copies the list
    }

    private fun applySurfaceFrameRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!surfaceValid || desiredFrameRate == appliedFrameRate) return
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        try {
            surface.setFrameRate(desiredFrameRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            appliedFrameRate = desiredFrameRate
        } catch (_: RuntimeException) {
            // invalid surface / rate: purely a hint, ignore
        }
    }

    private inner class SurfaceCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceValid = true
            appliedFrameRate = -1f
            applySurfaceFrameRate()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            updateTouchScale()
            applySurfaceFrameRate()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceValid = false
        }
    }

    // ================================================================ touch (UI thread)

    // No click semantics on a game surface; accessibility clicks are not meaningful here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val input = game.input
        val sx = touchScaleX
        val sy = touchScaleY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                input.push(InputQueue.DOWN, event.getPointerId(i), event.getX(i) * sx, event.getY(i) * sy)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    input.push(InputQueue.MOVE, event.getPointerId(i), event.getX(i) * sx, event.getY(i) * sy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // API 33+: an UP flagged FLAG_CANCELED (e.g. a palm or accidental touch the system
                // rejected) must not act as a release, so it becomes a CANCEL for that pointer.
                val type = if (isCanceled(event)) InputQueue.CANCEL else InputQueue.UP
                val i = event.actionIndex
                input.push(type, event.getPointerId(i), event.getX(i) * sx, event.getY(i) * sy)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    input.push(InputQueue.CANCEL, event.getPointerId(i), event.getX(i) * sx, event.getY(i) * sy)
                }
            }
        }
        return true
    }

    private fun isCanceled(event: MotionEvent): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0

    // ================================================================ GL thread

    private inner class GameRenderer : Renderer {
        private val gl = AndroidGl()
        private var glWidth = 0
        private var glHeight = 0
        private var appliedGeneration = -1
        private var lastFrameTime = 0L

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (_: RuntimeException) {
                // not permitted on this device: keep the default priority
            }
            perfHint.bindCurrentThread()
            game.onSurfaceCreated(gl)
        }

        override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
            glWidth = w
            glHeight = h
            dispatchLayout()
        }

        override fun onDrawFrame(unused: GL10?) {
            if (appliedGeneration != layoutGeneration) dispatchLayout()
            // Frames GLSurfaceView renders on its own (first frame, resize, resume) carry no fresh
            // vsync: they reuse the last one, or "now" before the first. Never let time go backwards
            // (a vsync stamp can precede a nanoTime() taken just after it).
            val vsync = pacer.frameTimeNanos
            var t = if (vsync != 0L) vsync else System.nanoTime()
            if (t < lastFrameTime) t = lastFrameTime
            lastFrameTime = t
            game.drawFrame(t)
            perfHint.reportFrame(game.lastFrameCpuNanos)
        }

        /** Sends size, density and safe insets in surface pixels (they differ from view pixels when renderScale < 1). */
        private fun dispatchLayout() {
            val generation = layoutGeneration
            val vw = viewWidth
            val vh = viewHeight
            val sx = if (vw > 0) glWidth.toFloat() / vw else 1f
            val sy = if (vh > 0) glHeight.toFloat() / vh else 1f
            game.onSurfaceChanged(
                glWidth, glHeight, baseDensity * sx,
                safeLeft * sx, safeTop * sy, safeRight * sx, safeBottom * sy,
            )
            appliedGeneration = generation
        }
    }

    private companion object {
        const val EXCLUSION_HEIGHT_DP = 200f
        const val EXCLUSION_WIDTH_DP = 48f
        const val CORNER_INSET_FACTOR = 0.29289322f // 1 - 1/sqrt(2)
    }
}
