package com.starwake.core.ui

import com.starwake.core.Settings
import com.starwake.core.content.Liveries
import com.starwake.core.game.ShipSpec
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.clamp01
import com.starwake.core.math.withAlpha
import kotlin.math.max
import kotlin.math.min

/** Actions the screens trigger on the game. */
interface GameActions : UiHost {
    val settings: Settings
    val deviceInfo: String
    fun launch()
    fun openSettings()
    fun closeSettings()
    fun resume()
    fun quitToMenu()
    fun exitApp()

    /** A setting changed. [display] = render scale / frame rate, [renderer] = quality (GPU rebuild). */
    fun settingsChanged(display: Boolean, renderer: Boolean)
}

/** Screen geometry shared by all screens. */
class Frame {
    var w = 1f
    var h = 1f
    var u = 1f
    var sl = 0f
    var st = 0f
    var sr = 0f
    var sb = 0f
}

class MainMenu(private val game: GameActions) {
    val group = WidgetGroup(game)
    private val launch = group.add(Button("LAUNCH", primary = true) { game.launch() })
    private val settingsBtn = group.add(Button("SETTINGS") { game.openSettings() })
    private val exit = group.add(Button("EXIT") { game.exitApp() })
    private val names = Array(Liveries.all.size) { Liveries.all[it].name }
    private val livery = group.add(Selector("", names, { game.settings.livery }, { game.settings.livery = it; game.settingsChanged(false, false) }))
    private val card = Rect()
    private val buf = TextBuf(48)
    private var titleY = 0f
    private var leftX = 0f
    var dragHint = 1f

    fun layout(f: Frame) {
        val u = f.u
        leftX = f.sl + max(28f * u, 40f * u)
        titleY = f.h * 0.24f
        val bw = 236f * u
        val bh = 46f * u
        var y = f.h * 0.42f
        for (b in arrayOf(launch, settingsBtn, exit)) {
            b.r.set(leftX, y, bw, bh)
            y += bh + 12f * u
        }
        val cw = 262f * u
        val ch = 150f * u
        card.set(f.w - f.sr - 26f * u - cw, f.h - f.sb - 22f * u - ch, cw, ch)
        livery.r.set(card.x + 14f * u, card.y + ch - 42f * u, cw - 28f * u, 34f * u)
        group.slop = 6f * u
    }

    fun draw(ui: UiRenderer, f: Frame, alpha: Float, time: Float) {
        val u = f.u
        val a = alpha
        // soft vignette behind the left column for legibility
        ui.rect(-f.w * 0.1f, -f.h * 0.1f, leftX + 320f * u, f.h * 1.2f, withAlpha(Theme.BLACK, a * 0.35f), 0f, 0f, 160f * u)
        ui.text("STARWAKE", leftX - 2f * u, titleY, 46f * u, withAlpha(Theme.TEXT, a), tracking = 0.2f, weight = 0.05f, shadow = 0.6f)
        ui.rect(leftX, titleY + 14f * u, 56f * u, 3f * u, withAlpha(Theme.ACCENT, a))
        ui.text("DEEP SPACE FLIGHT  ·  PROTOTYPE", leftX + 66f * u, titleY + 18f * u, 10.5f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.25f)
        group.draw(ui, u, a)

        // ship card
        val c = card
        ui.rect(c.x, c.y, c.w, c.h, withAlpha(Theme.PANEL, a * 0.85f), -12f * u)
        ui.rect(c.x, c.y, c.w, c.h, withAlpha(Theme.ACCENT, a * 0.35f), -12f * u, 1.2f * u)
        val spec = ShipSpec.KITE
        buf.clear().append(spec.designation).append("  ").append(spec.name)
        ui.text(buf, c.x + 16f * u, c.y + 28f * u, 17f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f, weight = 0.04f)
        ui.text(spec.role, c.x + 16f * u, c.y + 44f * u, 9.5f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        statBar(ui, u, a, c.x + 16f * u, c.y + 62f * u, c.w - 32f * u, "SPEED", spec.maxSpeed, "M/S", spec.maxSpeed / 400f)
        statBar(ui, u, a, c.x + 16f * u, c.y + 80f * u, c.w - 32f * u, "BOOST", spec.boostSpeed, "M/S", spec.boostSpeed / 500f)
        statBar(ui, u, a, c.x + 16f * u, c.y + 98f * u, c.w - 32f * u, "AGILITY", spec.maxPitchRate * 57.3f, "°/S", spec.maxPitchRate / 1.5f)

        // drag hint
        if (dragHint > 0.01f) {
            val pulse = 0.6f + 0.4f * kotlin.math.sin(time * 2.5f)
            ui.textCentered("◀  DRAG TO ROTATE  ▶", c.cx, c.y - 18f * u, 10f * u, withAlpha(Theme.TEXT_DIM, a * dragHint * pulse), tracking = 0.2f, shadow = 0.6f)
        }
        // version line
        buf.clear().append("v0.1.0  ·  ").append(game.deviceInfo)
        ui.text(buf, leftX, f.h - f.sb - 18f * u, 9.5f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f), tracking = 0.08f)
    }

    private fun statBar(ui: UiRenderer, u: Float, a: Float, x: Float, y: Float, w: Float, label: String, value: Float, unit: String, frac: Float) {
        ui.textCentered(label, x, y, 9.5f * u, withAlpha(Theme.TEXT_DIM, a), UiRenderer.LEFT, tracking = 0.15f)
        buf.clear().append(value.toInt()).append(' ').append(unit)
        ui.textCentered(buf, x + w, y, 10f * u, withAlpha(Theme.TEXT, a), UiRenderer.RIGHT)
        val bx = x + 70f * u
        val bw = w - 70f * u - 64f * u
        ui.rect(bx, y - 2f * u, bw, 4f * u, withAlpha(Theme.PANEL_LIGHT, a), 2f * u)
        ui.rect(bx, y - 2f * u, bw * clamp01(frac), 4f * u, withAlpha(Theme.ACCENT, a), 2f * u)
    }
}

class SettingsScreen(private val game: GameActions) {
    val group = WidgetGroup(game)
    private val s get() = game.settings
    private val panel = Rect()
    private val graphics = arrayListOf<Widget>()
    private val controls = arrayListOf<Widget>()
    private val back: Button

    init {
        graphics += group.add(Selector("QUALITY", arrayOf("LOW", "MEDIUM", "HIGH"), { s.quality }, {
            s.quality = it; game.settingsChanged(display = true, renderer = true)
        }))
        graphics += group.add(Selector("FRAME RATE", arrayOf("MAX", "60 FPS", "30 FPS"), { s.fpsMode }, {
            s.fpsMode = it; game.settingsChanged(display = true, renderer = false)
        }))
        graphics += group.add(Selector("SHOW FPS", arrayOf("OFF", "ON"), { if (s.showFps) 1 else 0 }, {
            s.showFps = it == 1; game.settingsChanged(false, false)
        }))
        controls += group.add(Selector("STICK SIDEWAYS", arrayOf("ROLL", "YAW"), { if (s.stickXRoll) 0 else 1 }, {
            s.stickXRoll = it == 0; game.settingsChanged(false, false)
        }))
        controls += group.add(Selector("STICK UP", arrayOf("CLIMB", "DIVE"), { if (s.pitchUpClimb) 0 else 1 }, {
            s.pitchUpClimb = it == 0; game.settingsChanged(false, false)
        }))
        controls += group.add(Slider("SENSITIVITY", 0.5f, 1.5f, { s.sensitivity }, {
            s.sensitivity = it; game.settingsChanged(false, false)
        }))
        controls += group.add(Selector("LAYOUT", arrayOf("RIGHT-HANDED", "LEFT-HANDED"), { if (s.leftHanded) 1 else 0 }, {
            s.leftHanded = it == 1; game.settingsChanged(false, false)
        }))
        controls += group.add(Selector("HAPTICS", arrayOf("OFF", "ON"), { if (s.haptics) 1 else 0 }, {
            s.haptics = it == 1; game.settingsChanged(false, false)
        }))
        back = group.add(Button("DONE", primary = true) { game.closeSettings() })
    }

    fun layout(f: Frame) {
        val u = f.u
        val pw = min(f.w - f.sl - f.sr - 32f * u, 720f * u)
        val ph = min(f.h - f.st - f.sb - 24f * u, 340f * u)
        panel.set(f.sl + (f.w - f.sl - f.sr - pw) * 0.5f, f.st + (f.h - f.st - f.sb - ph) * 0.5f, pw, ph)
        val pad = 22f * u
        val colW = (pw - pad * 3f) * 0.5f
        val top = panel.y + 74f * u
        val rowH = 40f * u
        for ((i, wdg) in graphics.withIndex()) wdg.r.set(panel.x + pad, top + i * rowH, colW, rowH)
        for ((i, wdg) in controls.withIndex()) wdg.r.set(panel.x + pad * 2f + colW, top + i * rowH, colW, rowH)
        back.r.set(panel.x + pad, panel.y + ph - pad - 42f * u, 150f * u, 42f * u)
        group.slop = 2f * u
    }

    fun draw(ui: UiRenderer, f: Frame, alpha: Float) {
        val u = f.u
        val a = alpha
        val p = panel
        ui.rect(0f, 0f, f.w, f.h, withAlpha(Theme.BLACK, a * 0.55f))
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.PANEL, a * 0.97f), -14f * u)
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.ACCENT, a * 0.4f), -14f * u, 1.2f * u)
        ui.text("SETTINGS", p.x + 22f * u, p.y + 36f * u, 20f * u, withAlpha(Theme.TEXT, a), tracking = 0.2f, weight = 0.04f)
        val pad = 22f * u
        val colW = (p.w - pad * 3f) * 0.5f
        ui.text("GRAPHICS", p.x + pad, p.y + 62f * u, 10f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        ui.text("CONTROLS", p.x + pad * 2f + colW, p.y + 62f * u, 10f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        group.draw(ui, u, a)
        val infoY = p.y + 74f * u + 3 * 40f * u + 22f * u
        ui.text(game.deviceInfo, p.x + pad, infoY, 9.5f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.05f)
        ui.text("QUALITY sets resolution, stars, dust and asteroid count.", p.x + pad, infoY + 16f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f))
    }
}

class PauseScreen(private val game: GameActions) {
    val group = WidgetGroup(game)
    private val resume = group.add(Button("RESUME", primary = true) { game.resume() })
    private val settings = group.add(Button("SETTINGS") { game.openSettings() })
    private val menu = group.add(Button("MAIN MENU") { game.quitToMenu() })
    private var cx = 0f
    private var top = 0f

    fun layout(f: Frame) {
        val u = f.u
        cx = f.w * 0.5f
        val bw = 236f * u
        val bh = 46f * u
        top = f.h * 0.5f - (bh * 3f + 24f * u) * 0.5f + 20f * u
        var y = top
        for (b in arrayOf(resume, settings, menu)) {
            b.r.set(cx - bw * 0.5f, y, bw, bh)
            y += bh + 12f * u
        }
        group.slop = 6f * u
    }

    fun draw(ui: UiRenderer, f: Frame, alpha: Float) {
        val u = f.u
        ui.rect(0f, 0f, f.w, f.h, withAlpha(Theme.BLACK, alpha * 0.5f))
        ui.textCentered("PAUSED", cx, top - 34f * u, 26f * u, withAlpha(Theme.TEXT, alpha), tracking = 0.3f, weight = 0.04f, shadow = 0.6f)
        group.draw(ui, u, alpha)
    }
}
