# 07 · Main menu, system map, star map and warp jump

Port reference for the Android version of *Untitled Space Exploration* (AFloidDEV).

**Area:** the main menu (start screen, LAUNCH modes, OPTIONS, HELP & INFO, CREDITS), the system map, the galaxy/star map, and the jump between star systems with its tunnel.

**Sources read in full** (paths relative to `roblox/lua/`):

| File | Lines | Role |
|---|---|---|
| `ShipClient/ShipMenu.lua` | 2444 | Main menu logic: pages, focus, input, camera, news, sounds |
| `ShipClient/ShipMenu/MainMenuInstaller.lua` | 1636 | Builds the `StarterGui.MainMenu` GUI once (all layout numbers) |
| `ShipCore/ShipMenuData.lua` | 405 | Menu texts and data: news, modes, options, help, credits, sounds, camera |
| `ShipClient/ShipSystemMap.lua` | 2104 | System map (key N): SCHEMATIC and ORBITS views, panels |
| `ShipClient/ShipStarMap.lua` | 1877 | Galaxy/star map (key M): 3D star field, search, filters, HOLD TO JUMP |
| `ShipClient/ShipWarp.lua` | 601 | Jump state machine ALIGN → COUNTDOWN → TUNNEL → ARRIVE, warp HUD |
| `ShipClient/ShipWarpTunnel.lua` | 299 | 3D tunnel around the camera |
| `ShipCore/ShipStarCatalog.lua` | 106 | 180-system catalog from one seed |
| `ShipCore/ShipConfig.lua` | sections `SystemMap` (1100-1152), `Warp` (1157-1233), `StarMap` (1241-1279) | Tunables |

Also used: `ShipClient.lua` (call sites), `ShipCore/ShipUi.lua`, `ShipSettings.lua`, `ShipUnits.lua`, `ShipKeys.lua`, `ShipIcons.lua`, `ShipNames.lua`, `ShipMath.lua`, parts of `ShipSystemBuilder.lua`, `ShipSolData.lua`, `ShipStations.lua`, `ShipResources.lua`, `ShipClient/ShipGalaxy.lua`, `ShipProfile.lua`, `ShipTraining.lua`, `ShipCallouts.lua`, `ShipAudio.lua`, `ShipServer/ShipData.lua`.

Kotlin references are relative to `core/src/main/kotlin/com/starwake/core/`.

**Conventions in this document**

- **design px**: Roblox GUI pixels at the 1080 px reference height. The menu scales by `clamp(viewportH / 1080, 0.75, 2.2)` (`ShipMenu.lua:484-497`); both maps use a 1920 × 1080 design canvas scaled by `max(min(W/1920, H/1080), 0.05)` (`ShipSystemMap.lua:518-543`, `ShipStarMap.lua:577-615`).
- **u**: the Kotlin UI unit (`Frame.u`, `Game.kt:239-254`), about 1 dp on phones. The reference phone in this document is the harness default, 2400 × 1080 px at density 2.75, which is **873 × 393 u** (20:9).
- **studs = metres** (`ShipConfig.Scale.MetresPerStud = 1`). **LY** = light years (star map units only).
- Lua system indices are **1-based, Sol = 1**. Keep 1-based system numbers in save data and keys such as `"1/Earth"`. Kotlin arrays may be 0-based internally.
- Colours are given as Lua RGB and as hex for Kotlin (`0xFFRRGGBB`).
- "Roblox `Tween` Quad/Quart/Sine/Quint Out" refer to the standard easing curves; `Out` means fast start and slow end.
- Everything marked **(inferred)** is not in the exported code. It is reconstructed from how the code uses a GUI element, and needs confirming against the real GUI (see §10).

---

## 1. Overview: what the player experiences

1. **Start.** The app opens on black. The main menu fades in over 1.4 s, and the menu music fades in over 2.5 s. Behind the menu, a 3D hangar is filmed by a slow camera that glides back and forth between fixed camera points, 25 s per leg, with a barely visible sway.
   - Top left: the logo. A planet emblem with a small moon orbiting it, then "U N T I T L E D" over "S P A C E   E X P L O R A T I O N", an orange rule, and "I N T E R S T E L L A R   F L I G H T".
   - Top right: the pilot block. "PILOT <NAME>", the credits ("12,345 CR"), the location ("SOL  /  IN FLIGHT") and a framed portrait.
   - Left column: three big entries, LAUNCH, OPTIONS and HELP & INFO. An orange bar slides behind the focused entry, and the focused label turns dark and grows 6 %.
   - Below the entries: a LATEST NEWS card that rotates through 6 news items every 9 s, each with a drawn title picture (planet, star or warp).
   - Bottom left: the version. Bottom right: key hints ("↑ ↓ SELECT", "ENTER CONFIRM").
   - Every move plays a hover tick, every choice a select click, and going back a back sound.
2. **LAUNCH** shows three tall mode cards: SOLO, OPEN PLAY and TRAINING. The focused card fills orange and grows 2 %. Until a new pilot has flown the training, TRAINING is pre-selected and reads "NEW PILOTS START HERE", while SOLO and OPEN PLAY read "TRAINING FIRST" and lead into the training. Picking a mode plays the launch sound, fades the music out over 1 s and the screen to black over 0.9 s. The world is then built, and the black curtain lifts over 0.6 s once the first flight frame has been drawn.
3. **OPTIONS** shows four cards: CONTROLS, GRAPHICS, AUDIO and CREDITS. Each opens a panel with sliders and ON/OFF toggles and a RESET TO DEFAULTS button. CONTROLS also lists every key binding in two columns, and CREDITS is a scrolling text.
4. **HELP & INFO** is a scrolling page with three columns of key help (FLIGHT, NAVIGATION, VIEW & SYSTEM) and a fourth column of numbered PILOT TIPS.
5. **System map (N).** It opens with a short rise-and-fade animation. The flight keeps running behind it.
   - **SCHEMATIC** view (default): the star sits on the left and the planets follow in orbit order on a horizontal axis. Each planet is a real textured sphere, sized by the square root of its radius, with its ring tilted as in space. Its moons and stations hang below it on a thin stem, and its orbit radius and name are shown above and below it. A "you are here" marker sits over the body you are near, or on the axis between two orbits.
   - Left panel: the system overview (class, name, counts, "YOU CHARTED x OF y BODIES") and a list of all bodies.
   - Right panel: the selected body. It has a slowly rotating preview, distance, orbit, radius, gravity, atmosphere, landing, a survey record and a LOCK TARGET button.
   - Clicking a body selects it **and locks it as the flight target** in one step.
   - **ORBITS** view: a top-down orbit diagram with dots, orbit circles, a 6-dot "where it was" trail per body, and the ship with its heading. Moons appear from zoom ×4.
6. **Star map (M).** This is a 3D perspective view of 180 star systems in a 120 LY wide disc.
   - A 10 LY grid lies on the galactic plane and fades with distance. Thin drop lines run from each star down to the plane, and each ends in a small ellipse foot.
   - Names are placed so that they never overlap. The current system pulses cyan and reads "YOU ARE HERE".
   - On opening, the map flies in to the current system.
   - Mouse controls: drag pans, the right button turns the view, the wheel zooms, and a double click centres on a star. A search box finds stars by name, and filters hide star classes, names, the grid or drop lines.
   - The info panel shows the selected star: class, planets, moons, giants, landable bodies, ringed planets, a mini system diagram, the survey record and coordinates.
   - Selecting another star draws an animated dashed route with its length in LY. Holding **HOLD TO JUMP** for 0.7 s, or pressing Enter, plots the jump. A single click never jumps.
7. **Jump.**
   - The map closes. **ALIGN:** an orange ring on screen shows the jump direction, and the status line reads "ALIGN TO <STAR>   BACKSPACE TO CANCEL". The voice says the jump is charging.
   - With the nose within about 10° of the direction, **COUNTDOWN** starts: big numbers 5 … 1 with the voice counting. The enter-warp sound starts 2 s before the jump so that its hit lands on the jump. Drifting more than about 16° off returns to ALIGN and stops the sound.
   - At zero (**TUNNEL**), the screen flashes white, the field of view kicks about 19° wider and settles at +12°, and the ship is inside a spinning dark-blue tube. A glowing core sits at its end, and light rails, streak particles and light rings rush past, with a blue light flickering in the cockpit. The controls are locked.
   - Meanwhile the old system is torn down and the new one is built, and the ship is placed outside the new star's heat zone, facing the star and at rest. The tunnel lasts at least 6 s, and longer until the build reports done (at most 20 s).
   - **ARRIVE:** a second flash, the tube fades out over 1.2 s, the field of view returns, and the voice reports the arrival.

---

## 2. Modules

### 2.1 ShipMenu + MainMenuInstaller + ShipMenuData

**Purpose.** This is the full-screen main menu that runs **before** any flight module is loaded (`ShipClient.lua:31-105`). The GUI is not built by code at runtime. `MainMenuInstaller` built it once as instances in `StarterGui.MainMenu`, and it can be edited by hand. `ShipMenu` only shows and hides pages, fills texts, wires input, plays sounds and drives the camera. All texts and data come from `ShipMenuData`.

**Public API** (contract in `ShipMenu.lua:11-31`):

| Call | Lines | Behaviour |
|---|---|---|
| `ShipMenu.new(player, playerGui, camera)` | 205-225 | Creates the state. Opens nothing yet. |
| `menu:run() -> { mode }` | 228-241 | Opens the menu and **yields** until LAUNCH. Returns `"SOLO"`, `"OPEN"` or `"TRAINING"`. If the GUI is missing it returns `"SOLO"` immediately. Before returning it calls `_close(true)`: the screen stays black. |
| `menu:reveal(fadeTime?)` | 246-267 | After `run()` returned: fades the black curtain out (default 0.6 s, Sine Out), then destroys the menu GUI. It can be called more than once, and does nothing while the menu is still open. |
| `menu:destroy()` | 270-277 | Error path: releases a waiting `run()` with `"SOLO"`, closes, reveals instantly. |

**State** (`ShipMenu.lua:205-225` and later fields):

- `screens[name]`, each `{ name, group, items[], index, focused, hints[], back(), enter(instant, direction), scroll, follow }`;
- `current`: the current screen; `launching`; `joining`; `closed`; `waiting`;
- `newsIndex`, `newsTimer`, `newsHover`;
- `held` (key repeat: `{ key, action, nextTime }`); `drag` (slider drag: `{ key, definition, track }`);
- `cams`, `cameraStart`, `fallbackPose`, `nextScan`, `originalFov`;
- `scale`, `colors`, `sounds`, `baseVolume`, `lastHover`;
- `covered`, `revealed`, `barTarget`, `shaking`.

#### Open, close and reveal

`_open` (`ShipMenu.lua:299-380`):

1. Find or clone `MainMenu`. Read its colour attributes, falling back to `FALLBACK_COLORS` (58-68).
2. Move `MenuHangar` from storage back into the workspace if needed.
3. Build the pages: commander block, Main, Launch, Options, Help. Load pictures and audio, wire input, and hide the painted cockpit GUI and Roblox chat and player list.
4. Hide all page groups. Set the **fader opaque**, enable the GUI, show Main instantly, and start the camera.
5. **Fade the fader to transparent over 1.4 s, Sine Out** (376-378).

`_close(keepCover)` (`ShipMenu.lua:384-448`):

1. Unbind the camera step, disconnect everything and stop all sounds.
2. With `keepCover`: keep the fader opaque and hide the canvas. Set `DisplayOrder = 1000` so that it covers every flight GUI (those go up to 100), and set `covered = true`.
3. Re-enable the cockpit GUI, restore the CoreGui and the FOV, and move the hangar back to storage.
4. Start a safety timer: if `reveal()` has not come after **12 s** (`REVEAL_TIMEOUT`), reveal anyway (441-446).

ShipClient calls `reveal()` after waiting for two `RenderStepped` frames once the world is built (`ShipClient.lua:3248-3261`). The cockpit then boots while the curtain lifts.

#### Scaling

`_applyScale` (`ShipMenu.lua:484-497`): `scale = clamp(viewportH / 1080, 0.75, 2.2)`. The canvas is sized `1/scale` of the screen and gets a `UIScale` of `scale`, so all installer numbers are design px at 1080 px height. After 0.06 s, `_relayout` (499-515) runs. It:

- re-places the focus bar;
- sets the logo rule width to the measured wordmark width, using `rule.width = rule.Size.X.Offset × wordmark.AbsoluteSize.X / rule.AbsoluteSize.X`;
- re-fits the news card.

#### Pages, focus and transitions

Pages (`_newScreen`, 521-525) and their items (`_addItem`, 528-549). An item is `{ hover, click, style(on, instant), activate(), adjust(dir), hidden() }`:

- Mouse-enter on `hover` focuses the item and plays the hover sound.
- `Activated` on `click` focuses and activates it.
- Both are ignored while `launching` or `joining`.

`_focus(screen, index, withSound, instant)` (551-570) clamps the index, unstyles the old item, styles the new one, and plays "Hover" if `withSound` is set.

`_show(name, direction, instant)` (664-720). `direction` is `+1` going deeper and `-1` going back.

- **The previous page group** fades out over `FAST` (0.14 s, Quad Out) to alpha 0, sliding `-28 × direction` px (`SLIDE = 28`, line 75), and is hidden at the end.
- **The target group** fades in over `MEDIUM` (0.32 s, Quart Out) from alpha 0, sliding from `+28 × direction` to 0.
- When the two share a group (Options ↔ an options panel, Launch ↔ OPEN panel), it only fades from alpha 0.4 to 1 over `MEDIUM`.
- `target.enter(instant, direction)` runs.
- The logo `UIScale` tweens (MEDIUM) to **1 on Main and 0.62 elsewhere** (`LOGO_SMALL`). `Backdrop/ShadeFull` tweens to transparency **1 on Main and 0.5 elsewhere** (`SHADE_SUB`).
- The hint set is applied, all items are unstyled, and the remembered `screen.index` is focused instantly without sound.
- On Main, `newsTimer` is reset and `_relayout` runs after 0.06 s.
- The fades use a temporary `CanvasGroup` holder (`_fadeHolder` / `_fade` / `_settle`, 586-661) because a permanent CanvasGroup made the scaled UI blurry. **Kotlin needs no such trick:** multiply the page alpha and add an x offset at draw time.

`_goBack` (722-728) plays "Back" and calls `screen.back()` (not while launching).

`_setHints(names)` (730-739) shows only the named hint items.

`_styleButton(button, on)` (742-752) is used for Back, Reset, Refresh and Join:

| | Focused | Not focused |
|---|---|---|
| Background | Accent, transparency 0 | Transparency 1 |
| `TextLabel`s | Ink | Accent |
| Parts tagged `Ink="fill"` (the chevron) | Ink | Accent |

**Main page** (`_setupMain`, 836-883). Its items are `Launch`, `Options` and `Help` from `Screens/MainScreen/LeftColumn/MenuList/Entries`. Hints: `Select`, `Confirm`.

- Style: label `TextColor3 = Ink` when focused and `Accent` otherwise; its `UIScale` "Pop" is `1.06` when focused and `1` otherwise.
- The focused entry places the focus bar (`_placeBar`, 886-899). With `s = 1/scale`: `y = (button.absY − list.absY)·s`, `h = button.absH·s`. Then `bar.Position = (0, round(y) − 2)` and `bar.Size = (1, 0, 0, round(h) + 4)`, tweened over `FAST` unless instant.
- Activate plays "Select" and calls `_show(name, +1)`.
- The version text comes from `ShipMenuData.Version`.

**Commander block** (`_setupCommander`, 775-830):

- Callsign: `"PILOT " .. upper(DisplayName)`.
- Amount: `groupDigits(Credits) .. " CR"`, where `groupDigits` (123-130) is the integer part with "," thousands separators.
- Location: `upper(player attribute "Location")`, falling back to `ShipMenuData.Location = "SOL  /  IN ORBIT"`. The server writes the attribute as `"<SYSTEM>  /  <STATION NAME>"` when docked and `"<SYSTEM>  /  IN FLIGHT"` otherwise (`ShipServer/ShipData.lua:310-318`).
- Credits and location refresh on attribute change.
- Portrait: a Roblox avatar headshot. The silhouette shows until the image has loaded.

**News** (`_setupNews` 901-943, `_showNews` 945-1002, `_fitNews` 1006-1041):

- At most 6 entries are shown. With more than one entry, the dots `Dot1..Dot6` are visible and clickable.
- `_showNews(i)`:
  1. Wrap `i` to `1..min(#News, 6)` and reset the timer.
  2. Fade the picture (group transparency → 1), headline, body and tag text (transparency → 1) over `FAST`.
  3. After `FAST + 0.02 s` (skipped if the menu closed or the index changed meanwhile), apply the texts, show only the `Art<entry.Art>` child (default `ArtPlanet`), set the dots (active transparency 0, others 0.65), and fade everything back in over `MEDIUM`.
- Auto-advance runs in `_step` (2266-2273): only on Main, not while the pointer is over the news card, with more than one entry, every `NewsCycleSeconds = 9` s.
- Fit (`_fitNews`):
  1. `limit = Version.absY − 14·scale`; `bottom = LeftColumn` bottom edge.
  2. If the art is visible and `bottom > limit`, hide the art (150 px).
  3. Otherwise, if the art is hidden and `bottom + 150·scale ≤ limit`, show it again.
  4. If still `bottom > limit`, hide the whole news card.
  5. A hidden news card is re-probed: show it without art and measure again after 0.05 s.

**Pictures** (`_loadPictures`, 1050-1090). Each `ImageLabel "Picture"` with an `Icon` attribute gets its image from `ShipIcons`. If loading fails, the drawn shapes next to it are shown instead.

**Cards** (Launch and Options):

- `_fillCard` (1096-1113) sets the title, the description and the status box (hidden when the status is empty).
- `_styleCard(card, on, available)` (1117-1167). `selected = on and available`; `ink = Ink` if selected, `Accent` if available, `Muted` otherwise.

| Element | Selected | Available, not focused | Unavailable |
|---|---|---|---|
| Body fill | Accent, transparency 0 | CardFill, transparency 0.2 | CardFill, transparency 0.2 |
| Body `UIScale` | 1.02 | 1 | 1 |
| Outline (Accent stroke, 2 px) | hidden | hidden | **visible when focused** |
| Art background | transparency 1 | 0.3 | 0.3 |
| Strokes, fills and images tagged `Ink` | Ink | Accent | Muted |
| Title, Description, Underline | Ink | Accent | Muted |
| Status box stroke and label | Ink | Accent | Accent |
| Glow1 / Glow2 | transparency 0.8 / 0.92 | hidden (1) | hidden |

- `_deny(card)` (1170-1185) plays "Back" at pitch 0.8, then shakes the body x offset through `9, −7, 5, −3, 0` px, each step 0.045 s Sine. It does not re-enter while a shake runs.

**Launch page** (`_setupLaunch`, 1187-1306). Its items are the cards `SOLO`, `OPEN` and `TRAINING` in `ShipMenuData.Modes` order, then Back. Hints: `Browse`, `Confirm`, `Back`. Back goes to Main.

Activating a card:

- An unavailable mode calls `_deny`.
- `OPEN`, when the OPEN panel exists and training is not required, goes to `Panel:OPEN`. That panel is multiplayer: **dropped in the port**.
- Anything else calls `_launch(mode.Id)`.

Training logic:

- `recommendTraining = TRAINING card exists and player.TrainingDone == false`.
- `startIndex` is the TRAINING card when recommended, else the first available card.
- `refreshTraining` (1263-1276) runs on the `TrainingDone` and `SaveStatus` changes:
  - The TRAINING status becomes `TrainingRecommended` ("NEW PILOTS START HERE") when recommended, else the mode's own status.
  - SOLO and OPEN show `TrainingFirst` ("TRAINING FIRST") while `ShipTraining.required(player)` holds (`ShipTraining.lua:719-735`: `ShipConfig.Training.Required` and `TrainingDone == false`).
- ShipClient then redirects SOLO/OPEN to TRAINING (`ShipClient.lua:111-127`).

`enter()`:

1. Restore the header "LAUNCH" and the back label "BACK TO MAIN MENU".
2. Hide the panels and slide the cards in.
3. Focus the OPEN card when coming back (`direction == -1`), else `startIndex()`.

**`_launch(mode)`** (1308-1328):

1. Ignored if already launching. Sets `launching`.
2. Clears held keys and drags.
3. Plays "Launch".
4. Tweens the music volume to 0 over 1 s.
5. Tweens the fader to opaque over **0.9 s, Sine In**.
6. **After 1 s**, fires the launched event with the mode.

**Options page** (`_setupOptions`, 1629-1687). Its items are the cards `Controls`, `Graphics`, `Audio` and `Credits` (`ShipMenuData.OptionCards`), then Back. Hints: `Browse`, `Confirm`, `Back`.

- A card opens `Panel:<Id>` (+1).
- `enter()` restores the header "OPTIONS" and the back label, hides the panels, and slides the cards in (`_slideIn`, 1690-1707): the frame starts `HomeX + 28` px and tweens to `HomeX` over `MEDIUM`.

**Options panels** (`_setupPanel`, 1709-1787):

- Hints: `Select`, `Adjust`, `Back`. Back goes to Options.
- `enter()`:
  1. Header RichText: `'OPTIONS <font color="#ff7a00">/ CONTROLS</font>'`.
  2. Back label "BACK TO OPTIONS".
  3. Hide the cards, show only this panel, scroll it to the top, slide the panels in.
- **Credits**: hints `Scroll`, `Back`. The text is built by `_creditsText` (1940-1953): each heading in Accent, size 17, bold, then its lines, then an empty line.
- **Other panels**:
  1. One row per setting key in `ShipMenuData.OptionPanels[id]` (`_addSettingRow`).
  2. Controls also fills the key rows (`_fillKeyRows`, 1911-1938), whose labels come from `KeyBindings` and whose caps come from `keyName(key)`.
  3. Reset (`FooterBar/Reset`) sets every key of the panel to its definition's default.
  4. Back.

**Setting row** (`_addSettingRow`, 1789-1891). Label = `definition.label`; hint = `SettingInfo[key].Hint` (hidden if empty).

- **Slider**:
  - `refresh` sets `alpha = clamp((v − min) / (max − min), 0, 1)` and the value text `formatValue`. Fill size `(alpha, 1)` and handle position `(alpha, 0.5)` are tweened `FAST`.
  - `adjust(dir)`: `step = definition.step or (max − min)/20`; `set(snap(def, (before or default) + dir·step))`, with "Hover" at pitch 1.2 when the value changed.
  - Mouse down on `Hit` starts a drag. `_dragTo(x)` (1893-1909) computes `alpha = clamp((x − track.absX) / track.absW, 0, 1)` and sets `snap(min + alpha·(max − min))`.
- **Toggle**:
  - `refresh` makes the active button (On when true, Off when false) Accent with Ink text, and the other transparent with Accent text.
  - `adjust(dir)`: left (dir < 0) sets **false**, right sets **true**, and plays "Select" when changed.
  - `activate` flips the value. Clicking On or Off sets the value directly.
- Row style: focused shows the row background (transparency 0; its gradient makes a solid left edge plus a faint orange wash); unfocused is transparency 1.
- `snap` (164-174): `v = clamp(value, min, max)`; if `step > 0`, `v = min + round((v − min)/step)·step`, clamped again and rounded to 4 decimals.
- `formatValue` (176-196):

| Case | Output |
|---|---|
| `nil` | `"-"` |
| toggle | `"ON"` / `"OFF"` |
| `percent` | `string.format("%d %%", round(v·100))`, e.g. "100 %" |
| `degrees` | `"%d°"` |
| `multiplier` | `"%.2f×"` |
| `step ≥ 1` | integer |
| otherwise | `"%.2f"` |

**Help page** (`_setupHelp`, 1959-2021). Hints: `Scroll`, `Back`. The only item is Back, so up and down scroll the content.

- It fills the column titles, the key caps per entry (`keyName`) and the texts, plus the tips: number `"%02d"` and text.
- Scrolling (`_scroll`, 2024-2035): `step = page ? 0.8·window : 90·scale`; `y = clamp(y + dir·step, 0, canvasH − window)`, tweened `FAST`.

**Keyboard input** (`_setupInput` 2041-2072, `_action` 2075-2121; key table `KEY_ACTIONS` 84-99):

| Key | Action |
|---|---|
| Up, W | up |
| Down, S | down |
| Left, A | left |
| Right, D | right |
| Return, KeypadEnter | confirm |
| Backspace | back (Escape belongs to Roblox) |
| PageUp, PageDown | page scroll |

`_action(action, first)` returns the **repeat interval** for a held key:

| Action | Effect | Repeat |
|---|---|---|
| confirm | `focused.activate()` (first press only) | none |
| back | `_goBack()` (first press only) | none |
| pageUp / pageDown | `_scroll(∓1, page)` | 0.25 s |
| left / right on an item with `adjust` | `adjust(dir)` | 0.05 s |
| up / down on a page with `scroll` and ≤ 1 item | `_scroll(dir)` | 0.06 s |
| any other move | focus `index + dir`, skipping hidden items; with `screen.follow` also `_scrollTo` the item (1335-1356) | 0.14 s |

A held key first repeats after `REPEAT_DELAY = 0.38 s` (`_step` 2275-2284). Mouse movement drives an active slider drag, and the mouse button going up ends it.

**Per-frame step** (`_step`, 2236-2285; bound with `BindToRenderStep` at camera priority):

1. Force `CameraType = Scriptable`, a free mouse and a visible cursor.
2. While there are no hangar cameras, rescan every 1 s.
3. Camera pose and FOV, plus sway (below).
4. `_animateEmblem(now)`.
5. News auto-advance.
6. Key repeat.

**Hangar camera** (`_scanCams` 2127-2146, `_cameraPose` 2200-2234, `_fallbackPose` 2150-2182). The cameras are `Workspace.MenuHangar.Cameras.Cam<N>` parts, sorted by N. A part may carry a `FieldOfView` attribute.

- **n ≥ 2: ping-pong.**
  - `t = (now − start) / max(LegSeconds, 1)` with `LegSeconds = 25`; `leg = floor(t) mod 2(n−1)`; `α = frac(t)`; `eased = (1 − cos(απ))/2`.
  - Forward legs (`leg < n−1`) go from camera `leg+1` to `leg+2`. Backward legs use `back = leg − (n−1)` and go from `n − back` to `n − back − 1`.
  - `pose = lerp(A.CFrame, B.CFrame, eased)` (position lerp plus rotation slerp); `fov = fovA + (fovB − fovA)·eased`.
- **n = 1:** that camera.
- **n = 0:** `_fallbackPose`.
  - The focus is the bounding box of `MenuHangar.Ship`, or of the hangar, or the midpoint of all its parts, or the origin with size (30, 12, 30).
  - `distance = clamp(|size| × 0.65, 18, 220)`; `eye = focus + unit(0.72, 0.3, 0.9) × distance`; look at the focus.
  - Without a hangar: `lookAt((0,12,40), (0,0,0))`.
- **Sway:** `s = elapsed × SwaySpeed (0.12)`, then `pose = pose × Angles(noise(s, 0.37, 1.7) × sway, noise(s, 4.13, 2.9) × sway, 0)` with `sway = rad(0.35°)`. `noise` is Roblox Perlin `math.noise`; any smooth 1D noise works.
- Default FOV: `ShipMenuData.Camera.FieldOfView = 50` (vertical degrees).

**Emblem moon** (`_animateEmblem`, 2289-2326). The moon runs along a stadium-shaped track, the `Orbit` frame (104 × 26 design px, rotated −18°).

- `r = h/2 = 13`; `straight = max(w − h, 0)/2 = 39`; `perimeter = 4·straight + 2πr ≈ 237.7`; `s = (now × 24) mod perimeter`. That is 24 px/s, about 9.9 s per lap.
- The four segments, in order:

| Segment | Arc length | Position |
|---|---|---|
| Top straight (back side) | `0 ≤ s < 2·straight` | `x = −straight + s`, `y = −r` |
| Right half circle | next `πr` | `a = (s − 2·straight)/r − π/2`; `(straight + r cos a, r sin a)` |
| Bottom straight | next `2·straight` | `x = straight − (s − 2·straight − πr)`, `y = r` |
| Left half circle | rest | `a = (s − 4·straight − πr)/r + π/2`; `(−straight + r cos a, r sin a)` |

- Rotate by `orbit.Rotation` (−18°) and offset by the orbit position from the emblem centre.
- The moon is **hidden** (transparency 1) while on the back segment **and** inside the planet radius (`rx² + ry² < 26²`).

**Audio** (`_setupAudio` 2332-2365, `_updateMusic` 2367-2375, `_play` 2377-2395):

- Sounds `Hover`, `Select`, `Back` and `Launch` come from `ShipMenuData.Sounds`. The base volume is multiplied by the `EffectsVolume` setting. `PlaybackSpeed = pitch or 1`, and each play restarts from 0. "Hover" is rate-limited to once per 0.06 s.
- Music uses `ShipConfig.Audio.Music.SoundId` and loops. It starts at volume 0 and tweens to `MusicVolume(0.3) × setting MusicVolume` over `MusicFadeIn = 2.5 s`. A setting change re-tweens it over 0.2 s, and launch tweens it to 0 over 1 s.

**Hiding other GUIs** (2401-2441). This is Roblox-only: it hides the cockpit `Main` GUI and the chat and player list. **Not ported.**

**Not ported:** the OPEN PLAY panel (`_setupOpenPanel`, 1362-1623: friends list, JOIN, teleports), `_guardDuplicates` (454-478, a StarterGui spawn quirk), CoreGui handling, and headshot loading.

