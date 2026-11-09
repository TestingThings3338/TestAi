package com.starwake.core.ui

import com.starwake.core.Settings
import com.starwake.core.content.ShipCatalog
import com.starwake.core.game.ShipSpec
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.clamp01
import com.starwake.core.math.withAlpha
import kotlin.math.max
import kotlin.math.min

/** Actions the screens trigger on the game. */
interface GameActions : UiHost {
    val settings: Settings
    val deviceInfo: String

    /** The ship being flown (follows Settings.ship once its model is loaded). */
    val shipSpec: ShipSpec
    fun launch()
    fun openSettings()
    fun closeSettings()
    fun resume()
    fun quitToMenu()
    fun exitApp()

    /** A setting changed. [display] = render scale / frame rate, [renderer] = quality (GPU rebuild). */
    fun settingsChanged(display: Boolean, renderer: Boolean)

    /** Leave the station the ship is docked at. */
    fun launchFromStation()
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
    private val names = Array(ShipCatalog.all.size) { ShipCatalog.all[it].name }
    private val ship = group.add(Selector("", names, { game.settings.ship }, { game.settings.ship = it; game.settingsChanged(false, false) }))
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
        ship.r.set(card.x + 14f * u, card.y + ch - 42f * u, cw - 28f * u, 34f * u)
        group.slop = 6f * u
    }

    fun draw(ui: UiRenderer, f: Frame, alpha: Float, time: Float) {
        val u = f.u
        val a = alpha
        // soft vignette behind the left column for legibility
        ui.rect(-f.w * 0.1f, -f.h * 0.1f, leftX + 320f * u, f.h * 1.2f, withAlpha(Theme.BLACK, a * 0.35f), 0f, 0f, 160f * u)
        ui.text("UNTITLED", leftX, titleY - 40f * u, 17f * u, withAlpha(Theme.ACCENT, a), tracking = 0.45f, shadow = 0.6f, face = FontData.TITLE)
        ui.text("SPACE EXPLORATION", leftX - 2f * u, titleY, 33f * u, withAlpha(Theme.TEXT, a), tracking = 0.08f, weight = 0.03f, shadow = 0.6f, face = FontData.TITLE)
        ui.rect(leftX, titleY + 14f * u, 56f * u, 3f * u, withAlpha(Theme.ACCENT, a))
        ui.text("A GAME BY AFloidDEV", leftX + 66f * u, titleY + 18f * u, 10.5f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.2f)
        group.draw(ui, u, a)

        // ship card
        val c = card
        ui.rect(c.x, c.y, c.w, c.h, withAlpha(Theme.PANEL, a * 0.85f), -12f * u)
        ui.rect(c.x, c.y, c.w, c.h, withAlpha(Theme.ACCENT, a * 0.35f), -12f * u, 1.2f * u)
        val spec = game.shipSpec
        ui.text(spec.name, c.x + 16f * u, c.y + 28f * u, 19f * u, withAlpha(Theme.TEXT, a), tracking = 0.15f, face = FontData.TITLE)
        ui.text(spec.role, c.x + 16f * u, c.y + 44f * u, 9.5f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        buf.clear().append(spec.price / 1000).append(',').appendPadded(spec.price % 1000, 3).append(" CR")
        ui.text(buf, c.x + c.w - 16f * u, c.y + 28f * u, 12f * u, withAlpha(Theme.TEXT, a), UiRenderer.RIGHT, face = FontData.MONO)
        statBar(ui, u, a, c.x + 16f * u, c.y + 62f * u, c.w - 32f * u, "SPEED", spec.maxSpeed, "M/S", spec.maxSpeed / 400f)
        statBar(ui, u, a, c.x + 16f * u, c.y + 80f * u, c.w - 32f * u, "BOOST", spec.boostSpeed, "M/S", spec.boostSpeed / 500f)
        statBar(ui, u, a, c.x + 16f * u, c.y + 98f * u, c.w - 32f * u, "AGILITY", spec.maxPitchRate * 57.3f, "°/S", spec.maxPitchRate / 1.5f)

        // drag hint
        if (dragHint > 0.01f) {
            val pulse = 0.6f + 0.4f * kotlin.math.sin(time * 2.5f)
            ui.textCentered("◀  DRAG TO ROTATE  ▶", (leftX + 236f * u + c.x) * 0.5f, f.h - f.sb - 22f * u, 10f * u, withAlpha(Theme.TEXT, a * dragHint * pulse * 0.8f), tracking = 0.2f, shadow = 0.8f)
        }
        // version line
        buf.clear().append("v0.1.0  ·  ").append(game.deviceInfo)
        ui.text(buf, leftX, f.h - f.sb - 18f * u, 9.5f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f), tracking = 0.08f)
    }

    private fun statBar(ui: UiRenderer, u: Float, a: Float, x: Float, y: Float, w: Float, label: String, value: Float, unit: String, frac: Float) {
        ui.textCentered(label, x, y, 9.5f * u, withAlpha(Theme.TEXT_DIM, a), UiRenderer.LEFT, tracking = 0.15f)
        buf.clear().append(value.toInt()).append(' ').append(unit)
        ui.textCentered(buf, x + w, y, 10f * u, withAlpha(Theme.TEXT, a), UiRenderer.RIGHT, face = FontData.MONO)
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
    private var infoX = 0f
    private var infoY = 0f

    init {
        graphics += group.add(Selector("QUALITY", arrayOf("LOW", "MEDIUM", "HIGH"), { s.quality }, {
            s.quality = it; game.settingsChanged(display = true, renderer = true)
        }))
        graphics += group.add(Selector("FRAME RATE", arrayOf("MAX", "60 FPS", "30 FPS"), { s.fpsMode }, {
            s.fpsMode = it; game.settingsChanged(display = true, renderer = false)
        }))
        // Roblox FIELD OF VIEW 60–100° (vertical) and CAMERA SHAKE 0–100 %
        graphics += group.add(Slider("FIELD OF VIEW", Settings.FOV_MIN, Settings.FOV_MAX, { s.fovDeg }, {
            s.fovDeg = it; game.settingsChanged(false, false)
        }, step = 1f, format = Slider.FORMAT_DEGREES))
        graphics += group.add(Slider("CAMERA SHAKE", 0f, 1f, { s.cameraShake }, {
            s.cameraShake = it; game.settingsChanged(false, false)
        }, step = 0.05f, format = Slider.FORMAT_PERCENT))
        // the lagging chase camera of the pre-port game as a third VIEW step (DECISIONS 21a)
        graphics += group.add(Selector("CHASE VIEW", arrayOf("OFF", "ON"), { if (s.chaseView) 1 else 0 }, {
            s.chaseView = it == 1; game.settingsChanged(false, false)
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
        val ph = min(f.h - f.st - f.sb - 24f * u, 360f * u)
        panel.set(f.sl + (f.w - f.sl - f.sr - pw) * 0.5f, f.st + (f.h - f.st - f.sb - ph) * 0.5f, pw, ph)
        val pad = 22f * u
        val colW = (pw - pad * 3f) * 0.5f
        val top = panel.y + 72f * u
        val doneY = panel.y + ph - pad - 42f * u
        // six rows above DONE: 40 u each where there is room, never below 32 u (touch targets)
        val rows = maxOf(graphics.size, controls.size)
        val rowH = maxOf(32f * u, minOf(40f * u, (doneY - 10f * u - top) / rows))
        for ((i, wdg) in graphics.withIndex()) wdg.r.set(panel.x + pad, top + i * rowH, colW, rowH)
        for ((i, wdg) in controls.withIndex()) wdg.r.set(panel.x + pad * 2f + colW, top + i * rowH, colW, rowH)
        back.r.set(panel.x + pad, doneY, 150f * u, 42f * u)
        infoX = panel.x + pad + 150f * u + 18f * u
        infoY = doneY + 17f * u
        group.slop = 2f * u
    }

    fun draw(ui: UiRenderer, f: Frame, alpha: Float) {
        val u = f.u
        val a = alpha
        val p = panel
        ui.rect(0f, 0f, f.w, f.h, withAlpha(Theme.BLACK, a * 0.55f))
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.PANEL, a * 0.97f), -14f * u)
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.ACCENT, a * 0.4f), -14f * u, 1.2f * u)
        ui.text("SETTINGS", p.x + 22f * u, p.y + 36f * u, 20f * u, withAlpha(Theme.TEXT, a), tracking = 0.2f, face = FontData.TITLE)
        val pad = 22f * u
        val colW = (p.w - pad * 3f) * 0.5f
        ui.text("GRAPHICS & VIEW", p.x + pad, p.y + 60f * u, 10f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        ui.text("CONTROLS", p.x + pad * 2f + colW, p.y + 60f * u, 10f * u, withAlpha(Theme.ACCENT, a), tracking = 0.25f)
        group.draw(ui, u, a)
        ui.text(game.deviceInfo, infoX, infoY, 9.5f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.05f)
        ui.text("QUALITY sets resolution, stars, dust and ring rocks.", infoX, infoY + 15f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f))
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
        ui.textCentered("PAUSED", cx, top - 34f * u, 26f * u, withAlpha(Theme.TEXT, alpha), tracking = 0.3f, shadow = 0.6f, face = FontData.TITLE)
        group.draw(ui, u, alpha)
    }
}

/** Shown while docked at a station. Services arrive with the economy port. */
class DockedScreen(private val host: GameActions) {
    val group = WidgetGroup(host)
    private val launch = group.add(Button("LAUNCH", primary = true) { host.launchFromStation() })
    private val panel = Rect()

    fun layout(f: Frame) {
        val u = f.u
        val pw = minOf(f.w - f.sl - f.sr - 40f * u, 420f * u)
        val ph = 210f * u
        panel.set(f.sl + 24f * u, f.h - f.sb - 24f * u - ph, pw, ph)
        launch.r.set(panel.x + 20f * u, panel.y + ph - 20f * u - 46f * u, 200f * u, 46f * u)
        group.slop = 6f * u
    }

    fun draw(ui: UiRenderer, f: Frame, a: Float, stationName: String) {
        val u = f.u
        val p = panel
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.PANEL, a * 0.95f), -12f * u)
        ui.rect(p.x, p.y, p.w, p.h, withAlpha(Theme.ACCENT, a * 0.45f), -12f * u, 1.2f * u)
        ui.text("DOCKED", p.x + 20f * u, p.y + 30f * u, 10f * u, withAlpha(Theme.OK, a), tracking = 0.3f)
        ui.text(stationName, p.x + 20f * u, p.y + 58f * u, 22f * u, withAlpha(Theme.TEXT, a), tracking = 0.12f, face = FontData.TITLE)
        ui.text("WELCOME, PILOT.", p.x + 20f * u, p.y + 82f * u, 11f * u, withAlpha(Theme.TEXT_DIM, a), tracking = 0.15f)
        ui.text("SHIPYARD, MARKET AND REPAIRS ARRIVE WITH THE ECONOMY PORT.", p.x + 20f * u, p.y + 102f * u, 9f * u, withAlpha(Theme.TEXT_DIM, a * 0.8f), tracking = 0.08f)
        group.draw(ui, u, a)
    }
}
