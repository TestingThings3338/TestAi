package com.starwake.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.starwake.core.Game

/**
 * Single-Activity host: immersive fullscreen, draws under the display cutout. The screen is kept on
 * only during active flight (see [AndroidPlatform.setActivePlay]).
 * Every config change is handled in place (see the manifest) so the GL context survives rotation,
 * controller hot-plug, density changes and so on.
 *
 * Back (gesture, nav button, Esc) goes to [Game.requestBack]; the game decides what it means and
 * calls Platform.exitApp() from its main menu. Controller input is intercepted here, ahead of the
 * view tree, so it works regardless of which view has focus.
 */
class GameActivity : Activity() {
    private lateinit var platform: AndroidPlatform
    private lateinit var game: Game
    private lateinit var view: GameSurfaceView
    private lateinit var gamepad: GamepadInput
    private var inputManager: InputManager? = null

    /** Queued to the GL thread in [onPause]; preallocated. */
    private val flushSettingsEvent = Runnable { game.flushSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No FLAG_KEEP_SCREEN_ON here: AndroidPlatform sets it only while the player is flying.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // Order matters: Game reads settings through the platform in its constructor, and the
        // platform needs the view before the GL thread can call back into it.
        platform = AndroidPlatform(this)
        game = Game(platform)
        view = GameSurfaceView(this, game)
        platform.attach(view)
        gamepad = GamepadInput(game.gamepad)
        setContentView(view)
        view.requestFocus()

        enterImmersive()
        registerBackCallback()

        // Apply the persisted display settings before the first surface is created, so the surface
        // starts at the right size and refresh rate (the game re-applies them; repeats are no-ops).
        platform.applyDisplaySettings(game.settings.renderScale, game.settings.fpsLimit)

        inputManager = getSystemService(InputManager::class.java)
        inputManager?.registerInputDeviceListener(gamepad, Handler(Looper.getMainLooper()))
    }

    override fun onResume() {
        super.onResume()
        platform.onResume()
        view.onResume()
        view.pacer.start(platform.currentRefreshRate())
    }

    override fun onPause() {
        super.onPause()
        game.onPause()
        gamepad.reset() // releases arriving while paused would be lost
        view.pacer.stop()
        // Persist settings changed since the last save. Queued before view.onPause(): the GL thread
        // drains its event queue before it honours the pause, and onPause() blocks until it has.
        view.queueEvent(flushSettingsEvent)
        view.onPause()
        platform.onPause()
    }

    override fun onDestroy() {
        inputManager?.unregisterInputDeviceListener(gamepad)
        val hint = view.perfHint
        view.queueEvent { hint.close() } // GL thread owns the session; finalizer covers the rest
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersive()
            view.requestFocus()
        } else {
            // Notification shade, system dialog, another window in multi-window, ...: open the pause
            // menu if flying (only sets a flag), and release controller state whose key-ups would now
            // go to the other window.
            game.onPause()
            gamepad.reset()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        view.onDisplayConfigurationChanged()
        platform.onConfigurationChanged()
    }

    // ================================================================ input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepad.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepad.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    // ================================================================ back

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { game.requestBack() },
            )
        }
    }

    /** Android 12L and lower only; API 33+ uses the OnBackInvokedCallback above. */
    @Deprecated("Replaced by OnBackInvokedCallback on API 33+")
    @SuppressLint("GestureBackNavigation") // the API 33+ path is the callback registered in onCreate
    override fun onBackPressed() {
        game.requestBack() // deliberately not calling super: the game decides whether to exit
    }

    // ================================================================ immersive

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Lay out behind the bars so revealing them transiently never resizes the GL surface.
                // (Edge-to-edge is enforced from API 35 for targetSdk 35+, where this is deprecated.)
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }
}
