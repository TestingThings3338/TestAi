package com.starwake.desktop

import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL12
import org.lwjgl.egl.EGLCapabilities
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20
import org.lwjgl.opengles.GLES30
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/** EGL enums used here (kept local so we do not depend on which LWJGL class happens to hold them). */
private object E {
    const val NONE = 0x3038
    const val SUCCESS = 0x3000
    const val RED_SIZE = 0x3024
    const val GREEN_SIZE = 0x3023
    const val BLUE_SIZE = 0x3022
    const val ALPHA_SIZE = 0x3021
    const val SURFACE_TYPE = 0x3033
    const val RENDERABLE_TYPE = 0x3040
    const val PBUFFER_BIT = 0x0001
    const val OPENGL_ES3_BIT_KHR = 0x0040
    const val OPENGL_ES_API = 0x30A0
    const val CONTEXT_CLIENT_VERSION = 0x3098
    const val WIDTH = 0x3057
    const val HEIGHT = 0x3056
    const val VENDOR = 0x3053
    const val VERSION = 0x3054
    const val EXTENSIONS = 0x3055
    const val PLATFORM_SURFACELESS_MESA = 0x31DD
    const val DEFAULT_DISPLAY = 0L
    const val NO_CONTEXT = 0L
    const val NO_SURFACE = 0L
}

class EglException(msg: String) : RuntimeException(msg)

/**
 * Mesa EGL display on the surfaceless platform (no X11/Wayland/GBM device needed). With no render
 * node in the container Mesa falls back to the software rasterizer (llvmpipe).
 */
class HeadlessEglDisplay : AutoCloseable {
    val display: Long
    val major: Int
    val minor: Int
    val caps: EGLCapabilities
    val how: String

    init {
        val client = EGL.getCapabilities()
        var dpy = 0L
        var via = ""
        // Called through the raw function pointers: LWJGL's wrappers null-check native_display,
        // but the surfaceless platform requires EGL_DEFAULT_DISPLAY (NULL).
        MemoryStack.stackPush().use { stack ->
            if (client.eglGetPlatformDisplay != 0L) {
                val attribs = stack.mallocPointer(1).put(0, E.NONE.toLong())
                dpy = JNI.callPPP(E.PLATFORM_SURFACELESS_MESA, E.DEFAULT_DISPLAY, attribs.address(), client.eglGetPlatformDisplay)
                via = "eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA)"
            }
            if (dpy == 0L && client.eglGetPlatformDisplayEXT != 0L) {
                val attribs = stack.ints(E.NONE)
                dpy = JNI.callPPP(E.PLATFORM_SURFACELESS_MESA, E.DEFAULT_DISPLAY, MemoryUtil.memAddress(attribs), client.eglGetPlatformDisplayEXT)
                via = "eglGetPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA)"
            }
        }
        if (dpy == 0L) {
            // relies on EGL_PLATFORM=surfaceless in the environment (set by the Gradle run task)
            dpy = EGL10.eglGetDisplay(E.DEFAULT_DISPLAY)
            via = "eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_PLATFORM=${System.getenv("EGL_PLATFORM")}"
        }
        if (dpy == 0L) throw EglException("no EGL display (error ${eglErr()})")
        val maj = IntArray(1)
        val min = IntArray(1)
        if (!EGL10.eglInitialize(dpy, maj, min)) throw EglException("eglInitialize failed: ${eglErr()}")
        display = dpy
        major = maj[0]
        minor = min[0]
        how = via
        caps = EGL.createDisplayCapabilities(dpy, major, minor)
        if (!EGL12.eglBindAPI(E.OPENGL_ES_API)) throw EglException("eglBindAPI(EGL_OPENGL_ES_API) failed: ${eglErr()}")
    }

    fun describe(): String =
        "EGL $major.$minor via $how, vendor=${EGL10.eglQueryString(display, E.VENDOR)}, " +
            "version=${EGL10.eglQueryString(display, E.VERSION)}, surfaceless=${caps.EGL_KHR_surfaceless_context}"

    /** Creates an ES 3 context and makes it current on the calling thread. */
    fun createContext(): HeadlessContext = HeadlessContext(this)

    override fun close() {
        EGL10.eglMakeCurrent(display, E.NO_SURFACE, E.NO_SURFACE, E.NO_CONTEXT)
        EGL10.eglTerminate(display)
    }
}

class HeadlessContext internal constructor(private val egl: HeadlessEglDisplay) : AutoCloseable {
    val context: Long
    private val pbuffer: Long
    val config: Long

    init {
        val dpy = egl.display
        MemoryStack.stackPush().use { stack ->
            val attribs = stack.ints(
                E.RED_SIZE, 8, E.GREEN_SIZE, 8, E.BLUE_SIZE, 8, E.ALPHA_SIZE, 8,
                E.SURFACE_TYPE, E.PBUFFER_BIT,
                E.RENDERABLE_TYPE, E.OPENGL_ES3_BIT_KHR,
                E.NONE,
            )
            val configs = stack.mallocPointer(1)
            val num = stack.mallocInt(1)
            if (!EGL10.eglChooseConfig(dpy, attribs, configs, num) || num[0] == 0) {
                throw EglException("eglChooseConfig found no ES3 RGBA8 config: ${eglErr()}")
            }
            config = configs[0]
            val ctxAttribs = stack.ints(E.CONTEXT_CLIENT_VERSION, 3, E.NONE)
            context = EGL10.eglCreateContext(dpy, config, E.NO_CONTEXT, ctxAttribs)
            if (context == 0L) throw EglException("eglCreateContext(ES3) failed: ${eglErr()}")
            pbuffer = if (egl.caps.EGL_KHR_surfaceless_context) {
                E.NO_SURFACE
            } else {
                EGL10.eglCreatePbufferSurface(dpy, config, stack.ints(E.WIDTH, 1, E.HEIGHT, 1, E.NONE))
            }
            if (!EGL10.eglMakeCurrent(dpy, pbuffer, pbuffer, context)) {
                throw EglException("eglMakeCurrent failed: ${eglErr()}")
            }
        }
        GLES.createCapabilities()
    }

    val surfaceless get() = pbuffer == E.NO_SURFACE

    override fun close() {
        GLES.setCapabilities(null)
        EGL10.eglMakeCurrent(egl.display, E.NO_SURFACE, E.NO_SURFACE, E.NO_CONTEXT)
        if (pbuffer != E.NO_SURFACE) EGL10.eglDestroySurface(egl.display, pbuffer)
        EGL10.eglDestroyContext(egl.display, context)
    }
}

private fun eglErr(): String = "0x" + Integer.toHexString(EGL10.eglGetError())

/**
 * The offscreen "screen": an FBO with RGBA8 color + DEPTH24 renderbuffers. With [samples] > 0 the
 * engine renders into a multisampled FBO that is resolved with glBlitFramebuffer before readback.
 */
class RenderTarget(val width: Int, val height: Int, val samples: Int) : AutoCloseable {
    /** Single-sample FBO that is read back. */
    val resolveFbo: Int
    private val resolveColor: Int
    private val resolveDepth: Int
    private val msaaFbo: Int
    private val msaaColor: Int
    private val msaaDepth: Int

    /** What the engine binds when it means "the screen". */
    val screenFbo: Int get() = if (samples > 0) msaaFbo else resolveFbo

    val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4)

    init {
        val maxSize = GLES20.glGetInteger(GLES20.GL_MAX_RENDERBUFFER_SIZE)
        require(width in 1..maxSize && height in 1..maxSize) { "size ${width}x$height exceeds GL_MAX_RENDERBUFFER_SIZE $maxSize" }
        resolveFbo = GLES20.glGenFramebuffers()
        resolveColor = GLES20.glGenRenderbuffers()
        resolveDepth = GLES20.glGenRenderbuffers()
        build(resolveFbo, resolveColor, resolveDepth, 0)
        if (samples > 0) {
            val maxSamples = GLES20.glGetInteger(GLES30.GL_MAX_SAMPLES)
            require(samples <= maxSamples) { "--msaa $samples > GL_MAX_SAMPLES $maxSamples" }
            msaaFbo = GLES20.glGenFramebuffers()
            msaaColor = GLES20.glGenRenderbuffers()
            msaaDepth = GLES20.glGenRenderbuffers()
            build(msaaFbo, msaaColor, msaaDepth, samples)
        } else {
            msaaFbo = 0; msaaColor = 0; msaaDepth = 0
        }
        // start from opaque black so an engine that draws nothing yields a defined image
        for (fbo in intArrayOf(resolveFbo, msaaFbo)) {
            if (fbo == 0) continue
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, screenFbo)
        checkGl("RenderTarget init")
    }

    private fun build(fbo: Int, color: Int, depth: Int, samples: Int) {
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, color)
        if (samples > 0) GLES30.glRenderbufferStorageMultisample(GLES20.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, width, height)
        else GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES30.GL_RGBA8, width, height)
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth)
        if (samples > 0) GLES30.glRenderbufferStorageMultisample(GLES20.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, width, height)
        else GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_RENDERBUFFER, color)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw IllegalStateException("render FBO incomplete (samples=$samples): 0x${Integer.toHexString(status)}")
        }
    }

    /** Resolves MSAA (if any), waits for the GPU and reads the image into [pixels] (bottom-up rows, RGBA). */
    fun readPixels(): ByteBuffer {
        if (samples > 0) {
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msaaFbo)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, resolveFbo)
            GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, resolveFbo)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4)
        pixels.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, screenFbo)
        checkGl("readPixels")
        return pixels
    }

    override fun close() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        for (fbo in intArrayOf(resolveFbo, msaaFbo)) if (fbo != 0) GLES20.glDeleteFramebuffers(fbo)
        for (rb in intArrayOf(resolveColor, resolveDepth, msaaColor, msaaDepth)) if (rb != 0) GLES20.glDeleteRenderbuffers(rb)
    }
}

/** Throws if GL reported an error (used by harness-side GL code, independent of --gl-debug). */
fun checkGl(where: String) {
    val e = GLES20.glGetError()
    if (e != GLES20.GL_NO_ERROR) throw IllegalStateException("GL error ${glErrorName(e)} in $where")
}

fun glErrorName(e: Int): String = when (e) {
    0x0500 -> "GL_INVALID_ENUM"
    0x0501 -> "GL_INVALID_VALUE"
    0x0502 -> "GL_INVALID_OPERATION"
    0x0505 -> "GL_OUT_OF_MEMORY"
    0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION"
    else -> "0x" + Integer.toHexString(e)
}
