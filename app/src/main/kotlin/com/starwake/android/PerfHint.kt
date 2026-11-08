package com.starwake.android

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log

/**
 * Android Dynamic Performance Framework (API 31+): tells the CPU governor how long each frame's
 * work took versus the frame budget, so it can ramp clocks before frames are missed instead of
 * after (and drop them again when there is headroom).
 *
 * The session is bound to the GL thread. [targetNanos] may be written from any thread; everything
 * else runs on the GL thread. Every platform call is guarded: a failing hint must never take the
 * game down. No-op below API 31 or when the device has no ADPF support.
 */
internal class PerfHint(context: Context) {
    private val appContext = context.applicationContext

    /** Frame budget in nanoseconds (1e9 / effective frame rate). */
    @Volatile
    var targetNanos = DEFAULT_TARGET_NANOS

    private var session: HintSession? = null
    private var sessionTid = -1
    private var appliedTarget = 0L

    /** GL thread: (re)creates the session for the calling thread if needed. */
    fun bindCurrentThread() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val tid = Process.myTid()
        if (session != null && sessionTid == tid) return
        session?.close()
        val target = targetNanos
        session = HintSession.create(appContext, tid, target)
        sessionTid = tid
        appliedTarget = target
    }

    /** GL thread, after every frame. */
    fun reportFrame(actualNanos: Long) {
        val s = session ?: return
        val target = targetNanos
        if (target != appliedTarget) {
            appliedTarget = target
            s.updateTarget(target)
        }
        if (actualNanos > 0L) s.report(actualNanos)
    }

    fun close() {
        session?.close()
        session = null
        sessionTid = -1
    }

    // @RequiresApi lives in androidx.annotation, which this app deliberately does not depend on;
    // the only construction path (bindCurrentThread) is guarded by an SDK_INT check.
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.S)
    private class HintSession(private val session: PerformanceHintManager.Session) {
        fun report(actualNanos: Long) {
            try {
                session.reportActualWorkDuration(actualNanos)
            } catch (_: RuntimeException) {
            }
        }

        fun updateTarget(targetNanos: Long) {
            try {
                session.updateTargetWorkDuration(targetNanos)
            } catch (_: RuntimeException) {
            }
        }

        fun close() {
            try {
                session.close()
            } catch (_: RuntimeException) {
            }
        }

        companion object {
            fun create(context: Context, tid: Int, targetNanos: Long): HintSession? = try {
                val manager = context.getSystemService(PerformanceHintManager::class.java)
                val s = manager?.createHintSession(intArrayOf(tid), targetNanos)
                if (s == null) Log.i(TAG, "ADPF hint session not supported on this device")
                s?.let(::HintSession)
            } catch (e: RuntimeException) {
                Log.w(TAG, "ADPF hint session unavailable", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "Starwake"
        const val DEFAULT_TARGET_NANOS = 16_666_667L
    }
}
