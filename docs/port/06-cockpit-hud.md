# 06 · Cockpit, HUD and flight effects

Port reference for **Untitled Space Exploration** (Roblox game by AFloidDEV) → Android/Kotlin (`:core`). This file covers everything the pilot sees in and around the cockpit while flying, except the flight arrow and the attitude ladder (those are in `02-flight.md`):

| Topic | Roblox source (paths relative to `roblox/lua/`) |
|---|---|
| Flat flight HUD: fuel/heat bars, flight data, prompt line, autopilot label, G blackout | `ShipClient/ShipHud.lua` |
| Head sway, vibration, camera shake (the old painted cockpit image) | `ShipClient/ShipCockpit.lua` (+ `ShipClient/ShipCamera.lua:89-128` for how the offsets are applied) |
| 3D cockpit: screens, gauges, lamps, buttons, levers, radar, holos, lights, boot | `ShipClient/ShipInterior.lua` + the `Cockpit` model of every ship in `Assets.rbxmx` |
| The visible ship: placement, what is hidden from where, engine glow | `ShipClient/ShipHull.lua` |
| Engine exhaust particles | `ShipClient/ShipExhaust.lua` |
| Target markers, target lock, target panel | `ShipClient/ShipMarkers.lua` |
| Billboard anchoring in front of the camera | `ShipClient/ShipOverlay.lua` |
| Flight notices: comms, toasts, docking aid, survey bar | `ShipClient/ShipNotices.lua` |
| Speed dust (and the driver of the two cruise layers) | `ShipClient/ShipDust.lua` |
| Speed streaks (cruise) | `ShipClient/ShipSpeedStreaks.lua` |
| Distance lines (switched off since 2026-11-06) | `ShipClient/ShipDistanceLines.lua` |
| Screen-space speed lines (built, never shown) | `ShipClient/ShipSpeedLines.lua` |
| Icon catalogue | `ShipCore/ShipIcons.lua` |
| UI scale, exclusive screens, open animation | `ShipCore/ShipUi.lua` |
| Number formats (m, km, Mm, m/s ...) | `ShipCore/ShipUnits.lua` |

All fifteen files were read completely. The `ShipConfig` sections Hud (`ShipCore/ShipConfig.lua:1732`), Cockpit (`:1948`), Interior (`:2856`), Markers (`:1542`), Hull (`:2614`), SpeedLines (`:1054`), Dust (`:1318`), SpeedStreaks (`:1386`), DistanceLines (`:1447`), Exhaust (`:1510`) and Ui (`:2017`) were read in full, plus the values these modules take from Physics, Warnings, Thrust, Camera, Keys, Performance and ShipSettings. `ShipClient.lua` was searched for every construction and call of these modules (section 2). The cockpit models were decoded by stream-parsing `Assets.rbxmx` (all eight ships: `Assets/Ships/<Ship>/Cockpit`); section 6.4 describes every screen from that data.

Sibling port docs: `02-flight.md` (flight model, flight arrow, attitude indicator, blackout physics, units, the scale S), `03-world.md` (Sol, bodies, attribute contract), `07-menu-maps-warp.md` (menu, maps, warp, menu icons), `08-stations-economy.md` (docking, survey and mining that write into the flight notices). Ships import (task 7), atmosphere/landing, audio/voice and warnings have their own work items; this file names what it needs from them.

The player is always **PILOT**. Several Roblox comments refer to a reference clip from "the big space flight simulator" (speed streaks, distance lines); the port keeps AFloidDEV's own numbers and looks and uses no names, terms or designs from other games.

---

## 0. Key facts in one screen

- **Default view is the 3D cockpit.** All eight ships share one cockpit layout (identical positions in every ship model, section 6.4). The flat HUD stack (bars and flight data) and the target panel are **hidden in the cockpit** and come back in the external view (V). In the cockpit, the same data sits on two 732 × 540 px screens, two round gauges, two holo panels, eight warning lamps, five status chips and a radar.
- **Cockpit boot:** 2.6 s after the menu fades: light flicker, lamp test, needle sweep, boot screens with status lines, holos flicker on, then a 12 s control hint.
- **Head, not image:** G-forces and turn rates move the camera head (max 55 design px ≙ 0.11 m, surge 0.12 m), plus noise vibration and a tiny camera shake (≤ 0.3°). The painted cockpit image is gone (`Cockpit.ImagePath = {}`).
- **Markers:** a 22 px ring on every body and station, name below; the **distance only on the locked and the focused** marker (focused = nearest to the screen centre within 14 % of the screen height). Labels never overlap (priority: locked, bigger body, stations last). Moons and stations are named only within 2,000 km (×S in the port). Lock: T cycles by centre distance, Y clears; 4 corner brackets snap in from 1.8× size in 0.22 s.
- **Speed effects** are 3D beams around the camera: **dust** (90 motes, 1.5 m/s to 600–2000 m/s), **speed streaks** (60 beams, fade in 240–650 m/s, denser, longer and faster up to 1 Mm/s), **air streaks** in an atmosphere. Colour by situation: white, blue (inside a body's influence), red (impact < 10 s or heat ≥ 80 %), cross-faded in 0.45 s. Distance lines exist but are **off**; the screen-space speed lines are built but **never shown**.
- **Exhaust:** two particle layers per nozzle (white-hot core, wider veil); length 0.4 → 3.4 nozzle diameters with throttle, ×1.6 and brighter when boosting.
- **Notices:** comms (6 s), toast (4 s), docking aid and survey bar, all in a Studio-built GUI that is **not in the export** (positions proposed in 6.9).
- **Missing from the export:** `StarterGui.CockpitInteract`, `StarterGui.FlightNotices`, `StarterGui.AttitudeHud`, the uploaded icon PNGs and the sounds. Everything the scripts look up is specified; the looks of those GUIs are proposals.
- **Phone reality check (section 9.2):** at Roblox's 80° vertical FOV on a 20:9 phone the cockpit screens' 30 px labels come out about 1 mm tall and the buttons 2–3 mm wide: the screens read as atmosphere, not as instruments. The plan keeps the 3D cockpit 1:1 and adds a minimal flat readout in the cockpit view (a user decision, Q1).

---

## 0.1 Conventions

- **Units.** 1 stud = 1 m (`ShipConfig.Scale.MetresPerStud = 1`). Cockpit geometry is given in metres in **eye space**: origin at the pilot's eye (`Root` of the ship model), +X right, +Y up, **−Z forward**. The ship origin is `Camera.SeatOffset = (0, 0.5, 0)` *below* the eye (`ShipConfig.lua:2655`): a part's ship-space position is `eye-space + (0, 0.5, 0)`.
- **S** is the world scale factor (`02-flight.md` 9.4, `03-world.md`). Values marked **×S** change with it; the cockpit and all ship-local effects are absolute.
- **GUI coordinates.** Roblox `UDim2 {xScale, xOffset, yScale, yOffset}` = `xScale·parentWidth + xOffset` px; Y grows **down**. `AnchorPoint (ax, ay)` is the point of the element placed at its `Position`. Written here as `pos {0.5,0, 1,-26}` / `size {0,280, 0,9}`. **Design px** are pixels at 1080 lines (`ShipUi.px`, section 3.1); SurfaceGui canvases are in **canvas px** (part size × `PixelsPerStud`).
- **Colours** are sRGB `(r, g, b)` 0–255. Roblox **transparency** T means opacity `1 − T`.
- **Roblox enums seen in the data.** `TextXAlignment` 0 Left, 1 Right, 2 Center; `TextYAlignment` 0 Top, 1 Center, 2 Bottom; `ScaleType` 3 Fit; `NormalId` (SurfaceGui `Face`) 1 Top, 5 Front; `SizingMode` 1 PixelsPerStud; `Material` 256 Plastic, 272 SmoothPlastic, 288 Neon, 1088 Metal (2309/2311 are newer material ids used on grips and the stick boot); `ApplyStrokeMode` 1 Border. `TextTruncate` 1 AtEnd.
- **GUI rotation.** Positive `Rotation` turns an element **clockwise** on screen; 0° of a gauge needle points up.
- **Helpers used below** (`ShipMath.lua`, module locals):

```text
smooth(c, t, tau, dt)      = tau <= 0 ? t : c + (t - c) * (1 - exp(-dt / tau))           ShipMath.smooth (frame-rate independent)
smoothstep(x)              = x' * x' * (3 - 2 x'),  x' = clamp(x, 0, 1)
steps(v, n)                = floor(v * n + 0.5) / n                                         ShipInterior.lua:164
approachColor(c, t, k)     = c == t ? c : (|c-t|_1 < 0.002 ? t : lerp(c, t, k))             ShipDust.lua:141 (L1 distance on 0..1 channels)
moodK(dt)                  = 1 - exp(-dt / max(MoodFade, 1e-3))
noise(x, y, z)             = Roblox math.noise: Perlin gradient noise, 0 at integer lattice points, |value| < ~0.5 typical, ≤ 1
```

- **Luau details.** `%` is a floored modulo (`Math.floorMod` / `x - floor(x/m)*m` in Kotlin). `math.round` rounds half away from zero. `string.format("%3.0f%%", 0.5*100)` → `" 50%"`.
- **Line references** are `File.lua:first-last`.

---

## 1. Overview: what the player experiences

**Start.** The menu fades out over the cockpit. The cabin is dark; the four instrument screens show a dark boot frame. Then (2.6 s) the roof strips flicker twice and come up, all eight warning lamps test in amber, both gauge needles sweep to full and back, the two big screens count through POWER BUS → SENSOR ARRAY → FLIGHT CONTROL → NAV COMPUTER → ALL SYSTEMS GO with a progress bar, the button faces light up, the two holo panels flicker on, and the projection glass (flight arrow) comes on with them. A control hint shows for 12 s. After a crash the same sequence runs again (without the hint).

**The cockpit (looking forward).** A wide canopy without a visible frame (the canopy cage is hidden from inside; the cockpit's own struts with LED strips frame the view). Low in the centre sits a tilted radar disc with a cyan ship symbol, sweeping amber; above it on the glare shield a row of eight square warning lamps (FA, GEAR, MASS LOCK, SCOOP, AUTOPILOT, FUEL, HEAT, WARNING). Left of the radar the round THR gauge, right the SPD gauge (logarithmic up to the cruise cap). Lower left the NAVIGATION screen (system name, locked target with class, distance and ETA, the three nearest bodies, autopilot footer); lower right the SHIP screen (big speed number with unit, allowed speed with its zone in colour, throttle bar with a zero mark, fuel and heat bars, drive mode, G, five status chips). Beside each screen two columns of three physical buttons with an LED and a printed label: TARGET, CLEAR TARGET, AUTOPILOT, SYSTEM MAP, STAR MAP, HYPERSPACE JUMP on the left; FLIGHT ASSIST, BOOST, LANDING, EXTERNAL VIEW, COCKPIT LIGHTS, MUSIC on the right. Higher up at the sides float two cyan holo panels: BODY / ALTITUDE / GRAVITY / PITCH and MASS / AGILITY / BOOST / STATUS. The pilot's legs, arms and hands are visible; the left hand holds the throttle lever, the right hand the stick, and both move with the controls (lever back at −25 %, forward at 100 %; the stick tilts with the steering arrow and twists with yaw).

**Looking around** (hold the right mouse button): up to 140° to each side, 80° up, 70° down; a small ring appears at the screen centre, grows when it is over a button and shows the button's label and key; a click presses it (the cap dips 3 cm and springs back). With the mouse released (Left Alt) the buttons are clickable under the free mouse.

**Motion.** Accelerating forward pushes the head back into the seat (up to 12 cm) and the view sags slightly; lateral G and turns make the view lag; a noise-driven tremble grows with speed and acceleration; the camera itself shakes by fractions of a degree. A setting CAMERA SHAKE (0–1) scales all of it.

**Markers.** Every body and station carries a thin ring and its name in upper case; the planet you point at and the locked target also show the surface distance. Moons and stations far away show only their ring. Overlapping names are hidden in favour of the more important one. T locks the nearest body or station (by centre distance), pressing again steps outward; the locked marker turns orange with four corner brackets that snap in. In the external view a TARGET LOCKED panel in the lower right gives name, class, distance and ETA.

**Lines and messages.** A one- or two-line prompt sits above the glare shield ("PRESS L TO LAND", "K   REQUEST DOCKING   ·   MERIDIAN STATION"); forbidden actions are red. While the autopilot flies, "AUTOPILOT  CRUISING" shows at the top. Radio calls, short notices, a docking aid and a survey bar appear when their systems run.

**Speed.** Below 1.5 m/s nothing moves. Faster, fine light motes stream past and stretch into short strokes; at about 600 m/s they start to fade and are gone at 2 km/s. From 240 m/s long, hair-thin streaks begin to radiate from the vanishing point of the flight direction; in cruise they are fully there and become denser, longer and faster up to 1 Mm/s. All of them are white in open space, blue inside a body's influence and red when an impact is less than 10 s away or the ship overheats. In an atmosphere and on the landing scene the motes turn into thinner, longer, paler air streaks and the cruise streaks fade out.

**Engines.** Every nozzle glows with the throttle and trails a soft particle flame (a white-hot core and a wider pale veil); boosting stretches and brightens it.

**External view (V).** The canopy is visible, the pilot and cockpit details are not; the flat HUD stack (bottom left) and the target panel (bottom right) return; looking around orbits the camera around the ship.

---

## 2. Per-frame pipeline and entry points (`ShipClient.lua`)

The flight frame is the render-step callback `ShipUpdate` bound at `Enum.RenderPriority.Camera` (`ShipClient.lua:2321`), `dt = min(dt, 0.1)`. On station decks (`:2327-2334`) and during the destruction cinematic (`:2336-2342`) the frame branches off early (`hud.attitude:hideNow()`); `deckFrame` (`:2090-2239`) runs a parked variant of the cockpit update.

### 2.1 Order inside the flight frame (this area's calls)

| # | Line | Call | Notes |
|---|---|---|---|
| 1 | 2790-2802 | `speed`, `speedFraction = clamp(speed / Thrust.MaxSpeed 1000, 0, 1)`, `getPitchAngle`, cursor hidden while looking/docked/on foot | |
| 2 | 2806-2878 | `interior:setPointer(pointer)`; fill `interiorState` (2.2); `interior:update(dt, s)` | **before** `hull:update`: levers, caps and blips change part offsets that the hull then moves in one batch |
| 3 | 2880 | `hull:update(shipCFrame, thrust.throttle, thrust.boosting)` | moves all visible parts, steps the gear, updates plumes |
| 4 | 2882-2884 | `hull:setGearDown(landing scene and altitude < 60) or docking:wantsGear(cf))` | |
| 5 | 2885 | `cockpit:update(dt, thrust:getLocalAcceleration(cf), rotation:getAngularVelocity(), speedFraction)` | felt acceleration in ship axes (m/s²), angular velocity in deg/s (X pitch, Y yaw, Z roll) |
| 6 | 2889-2897 | `shipCamera:setShakeOffset(cockpit:getCameraShake() * entryFx:getShake())`, `setHead(cockpit:getHeadOffset(), input:getLook())`, `update(shipCFrame)` | on foot aboard: `aboard:frame` instead |
| 7 | 2960 | `markers:update(dt, shipCFrame.Position, thrust.velocity)` | throttles itself to 10 Hz |
| 8 | 2971-2976 | on foot: `dust:setVisible(false)`; else `dust:setAir(surface active or atmosphere ≠ SPACE)`, `dust:update(dt, camera.CFrame.Position, thrust.velocity, shipCFrame)` | dust drives streaks (and lines) |
| 9 | 2978 | `speedLines:setVisible(false)` | every frame: the screen-space lines stay off |
| 10 | 3075-3083 | `dust:setMood("danger" if timeToImpact < Warnings.Phase1Time 10 or heat ≥ Overheat.Phase1Heat 0.8, elif inInfluence "approach", else "neutral")` | after the dust update: the colour target applies from the next frame |
| 11 | 3085-3115 | `hud:update(dt, {...})` (2.3) | |
| 12 | 3118-3121 | `cursor:*` (flight arrow, `02-flight.md`) | `cursor:setCockpit(interior:hasFlightHud() and not external)` |
| 13 | 3130-3151 | `hud.attitude:update(dt, frame)` (`02-flight.md` 3.10) | uses `interior:isHudOn()` |
| 14 | 3180 | `notices:update(dt)` | hides expired comms/toasts |
| — | render step `Camera + 10` | `ShipOverlay.update()` | after the camera: moves every marker anchor (3.8) |

### 2.2 `interiorState` (one reused table, `ShipClient.lua:1265`, filled at `:2819-2876`)

| Field | Source | Field | Source |
|---|---|---|---|
| `shipCFrame`, `position` | ship pose | `velocity` | `thrust.velocity` |
| `speed` | `atmosphere:getShownSpeed(speed)` | `maxSpeed` | 1000 |
| `speedLimit`, `speedZone` | `thrust.speedLimit`, `thrust.speedZone` (zone label) | `throttle` | lever −0.25..1 |
| `aim`, `roll` | the controlling axes (see below) | `cursor` | `cursorPixels / radius` |
| `cursorShown` | not docked and not on foot | `flightAssist`, `boosting`, `cruising`, `boostCooldown` | thrust |
| `fuel`, `heat`, `gForce`, `scooping` | systems | `mass`, `agility` | handling |
| `gearDown` | `hull.gearDown` | `locked`, `bodies` | `markers:getLocked()`, `markers:getOrdered()` |
| `systemName` | current star map system | `bodyName`, `gravityG`, `massLocked` (= `inMassShadow`), `timeToImpact` | gravity state |
| `altitude` | `atmosphere:getShownAltitude(gravityState.altitude)` | `pitchAngle` | `getPitchAngle` (`:1674-1688`: asin(look·up), up = out of the dominant body) |
| `autopilotEngaged`, `autopilotStatus` | autopilot | `atmosphereState`, `surface` | atmosphere state, landing scene active |
| `warpActive`, `warpState`, `travelling` | warp | `systemMapOpen`, `starMapOpen`, `external`, `musicOn`, `crashing` | maps, camera, audio, `warnings:isBusy()` |

Stick axes: when the mouse rolls and the autopilot steers in yaw axes, the stick shows `aim = (−keyAxis, aim.y)`, `roll = −aim.x`; otherwise `aim = aim`, `roll = keyAxis` (`:2833-2839`). Docked/parked (`:2162-2206`): speed 0, throttle 0, aim 0, `cursorShown = false`, gear down, `locked = nil`, `bodies = {}`, `altitude = nil`, `gravityG = 0`, `pitchAngle = 0`, `atmosphereState = "SPACE"`; `cockpit:update(dt, 0, 0, 0)`.

### 2.3 HUD state (`ShipClient.lua:3085-3115`)

`throttle, speed (shown), maxSpeed 1000, speedLimit, speedZone, acceleration (forward), gForce, strainG, redout, mass, overloaded, agility, boosting, cruising, boostCooldown, pitchAngle, gravityG, surfaceGravityG, bodyName, landingBlocked, atmosphereState, prompt, flightAssist, fuelFraction, heat, altitude (shown), scooping, autopilotEngaged, autopilotStatus`. `prompt = hudPrompt(pos)` (`:1048-1055`): the landing prompt and the docking prompt, joined by a newline when both exist and the atmosphere state is SPACE, else whichever exists.

### 2.4 Event entry points

| Event | Line | Effect on this area |
|---|---|---|
| Start | 268-354 | `ShipCockpit.new`, `ShipMarkers.new`, `ShipDust.new(camera)`, `ShipSpeedLines.new`, `ShipHud.new` (+ `hud.attitude`), `hull:build(workspace)`, `hull:setInterior(true)`, `ShipInterior.new`; if `interior.active`: `cockpit:setImageEnabled(false)`, `hud:setCockpitMode(true)`, `markers:setPanelVisible(false)`; `input:setLookEnabled(interior.active)`; `ShipNotices.new`, `notices:setVoice(voice)` |
| Menu revealed | 3249-3263, 3208-3209 | `interior:boot()`; `notices:setEnabled(true)` |
| V (external view) | 937-960 | `hull:setInterior(not external)`, `cockpit:setVisible(not external)`, look limits 180/60/60 outside and 140/80/70 inside, `interior:setExternal`, `hud:setCockpitMode(not external)`, `markers:setPanelVisible(external)` |
| Cockpit button pressed | 1076-1103 | `onInteriorAction(action)` → same functions as the keys (table in 3.4.6) |
| Ship switched at a shipyard | 1108-1150 | `interior:destroy()`, `hull:build`, `hull:setInterior(not external)`, new `ShipInterior`, `interior:boot()`; toast "WALKABLE INTERIOR" for walkable ships |
| Landing scene in / out | 496-523 | `markers:setVisible(false)` going down, `true` coming up |
| Crash cinematic / respawn | 1370-1412, 1419-1460 | `setFlightUiHidden(true)` hides markers, cursor, speed lines, dust and every enabled ScreenGui except warnings; respawn: `interior:reboot()`, `setFlightUiHidden(false)`, `markers:setVisible(true)` |
| Station decks | 1698-1745, 1830-1880 | `hideSpace`: markers, cursor, speed lines, dust off, `hud.gui.Enabled = false`, `interior.gui` off while walking; `showSpace` restores them |
| Toggles | 1098, 1090, 1092 | Lights button → `interior:toggleLights()`; FA button flips `thrust.flightAssist`; Boost button sets `thrust.boostRequested` |

---

## 3. Modules

### 3.1 Shared helpers: ShipUi, ShipUnits, ShipIcons

**ShipUi** (`ShipCore/ShipUi.lua`) — one UI scale for every runtime-built GUI.

```text
scale        = clamp(viewport.y / Ui.ReferenceHeight 1080, Ui.MinScale 0.75, Ui.MaxScale 2.2)      :25-33
px(v)        = round(v * scale)                                // whole pixels, no blurry half pixels   :40-42
text(size)   = max(round(size * scale), 8)                                                          :44-46
onChanged(cb): cb(scale) now, and again (task.spawn) whenever refresh() finds |new - old| >= 0.001     :48-62
refresh      : bound to Camera.ViewportSize changes                                                 :124-129
registerExclusive(owner, close) / openExclusive(owner): opening one full-screen view closes the others (maps; 07-menu-maps-warp.md)  :72-84
animateOpen(gui, canvas): TweenInfo(Ui.OpenTime 0.22 s, Quint, Out); canvas.Position from (0, px(OpenRise 14)) to (0, 0);
             a full-screen "OpenVeil" frame (ZIndex 10000, colour OpenVeilColor (6,10,16)) fades from transparency
             OpenVeil 0.35 to 1 and hides itself when the tween completes                             :96-122
```

The flat HUD (`ShipHud`) uses `px`/`text`; the target panel, the marker billboards and the cockpit SurfaceGuis do **not** (fixed pixels); `FlightNotices` scales its own 1920 × 1080 canvas (3.9).

**ShipUnits** (`ShipCore/ShipUnits.lua`) — every number on every display goes through these (`m = studs × MetresPerStud 1`):

| Function | Rule (thresholds in m or m/s) | Examples |
|---|---|---|
| `distance(m)` `:50-70` | NaN/∞ → `"--"`; `m = max(m, 0)`; `< 999.5` → `"%d m"` (rounded); `< 9,995` → `"%.2f km"`; `< 99,950` → `"%.1f km"`; `< 999,500` → `"%d km"` (rounded); `< 9,995,000` → `"%.2f Mm"`; `< 99,950,000` → `"%.1f Mm"`; else `grouped(m/1e6) .. " Mm"` | 316 m · 3.17 km · 53.0 km · 828 km · 6.84 Mm · 53.0 Mm · 1,240 Mm |
| `grouped(v)` `:37-48` | `"%d"` of `floor(max(v,0)+0.5)` with commas every three digits | 1240567 → "1,240,567" |
| `speedParts(v)` `:72-90` | NaN/∞ → ("--", "M/S"); `< 999.5` → ("%d", "M/S"); `< 9,995` → ("%.2f", "KM/S"); `< 99,950` → ("%.1f", "KM/S"); `< 999,500` → ("%d", "KM/S"); `< 9,995,000` → ("%.2f", "Mm/s"); else ("%.1f", "Mm/s") | ("260","M/S") · ("1.24","KM/S") · ("1.06","Mm/s") · ("150.0","Mm/s") |
| `speed(v)` `:92-96` | `number .. " " .. (unit == "Mm/s" ? unit : lower(unit))` | "260 m/s", "53.0 km/s", "1.06 Mm/s" |
| `upper(text)` `:102-107` | `upper`, then `"(%d) MM/S" → "%1 Mm/s"` and `"(%d) MM" + word end → "%1 Mm"` (MM would read as millimetres) | "4.48 KM", "250 KM/S", "6.84 Mm", "1.06 Mm/s", "1,240 Mm" |
| `acceleration(a)` `:109-111` | `"%.1f m/s²"` | "35.0 m/s²" |

`ShipMarkers.formatDuration(s)` (`ShipMarkers.lua:60-68`, also used by the nav screen): NaN, ∞, negative or `> 356,400` s (99 h) → `"--:--:--"`, else `"%02d:%02d:%02d"` of `floor(s/3600)`, `floor((s % 3600)/60)`, `floor(s % 60)` (83 s → "00:01:23").

**ShipIcons** (`ShipCore/ShipIcons.lua`) — white PNGs on a transparent background, tinted by the GUI (`ImageColor3`). `Fill` = share of the image's longer side the drawing covers; `size(name, target) = floor(target / Fill + 0.5)` is the ImageLabel edge at which the drawing measures `target` px (`:62-66`). `image(name)` returns the asset id or `""` (`:55-58`). Cockpit/HUD icons (uploaded 2026-11-01, `:38-51`):

| # | Name | Asset id | Fill | Used on (section 6.4) |
|---|---|---|---|---|
| 13 | Fuel | 74844559651490 | 0.71 | ship screen fuel row, FUEL lamp |
| 14 | Heat | 134810177335106 | 0.80 | ship screen heat row, HEAT lamp |
| 15 | FlightAssist | 86813734822918 | 0.83 | FA chip, FA lamp, FLIGHT ASSIST button |
| 16 | Autopilot | 86499246764542 | 0.70 | nav footer, AUTOPILOT lamp and button |
| 17 | TargetLock | 127889420380161 | 0.70 | nav target icon, TARGET button |
| 18 | Hyperspace | 118832247631100 | 0.77 | HYPERSPACE JUMP button |
| 19 | StarMap | 130791406574093 | 0.69 | STAR MAP button |
| 20 | SystemMap | 91363337095472 | 0.76 | SYSTEM MAP button |
| 21 | Landing | 137132837614610 | 0.82 | LANDING button |
| 22 | LandingGear | 85647608599294 | 0.69 | GEAR chip and lamp |
| 23 | Boost | 84000923574904 | 0.64 | BOOST chip and button |
| 24 | MassLock | 74786768557676 | 0.72 | M-LOCK chip, MASS LOCK lamp |
| 25 | FuelScoop | 115064542117157 | 0.67 | SCOOP chip and lamp |
| 26 | Warning | 75439942176454 | 0.77 | WARNING lamp |
| 12 | HelpView (menu set) | 127682334803415 | 0.74 | EXTERNAL VIEW button |
| 8 | Audio (menu set) | 127100075442348 | 0.74 | MUSIC button |

The menu icons 1–11 are documented in `07-menu-maps-warp.md` 5.6. The PNGs themselves are **not in the export** (question Q3).

### 3.2 ShipHud: the flat flight HUD (`ShipClient/ShipHud.lua`)

**Purpose.** Throttle, speed, fuel, heat, G, autopilot status, the prompt line — and the darkening at high G ("it makes the G display felt, not just readable", `:9-13`). In the 3D cockpit the bars and flight data are hidden (`setCockpitMode(true)`), because the screens show them; prompt, autopilot label and blackout stay.

**API.** `new(playerGui)`, `layout()` (on UI scale change only), `setCockpitMode(on)`, `update(dt, state)` (state fields 2.3), `destroy()`. Fields used elsewhere: `gui` (ScreenGui "ShipHudGui", enabled/disabled by deck and crash code), `attitude` (the ShipAttitude instance hangs here only because `ShipClient` hit Luau's 200-locals limit).

**State.** `darkness` (0..0.82), `textTimer`, `promptText` (last prompt string, for write-on-change), `cockpitMode`.

**Build** (`new`, `:144-262`): ScreenGui `ShipHudGui`, `ResetOnSpawn false`, `IgnoreGuiInset true` (full screen incl. the top bar), `DisplayOrder 46`.

| Element | Class / properties |
|---|---|
| `GBlackout` | Frame, size `{1,0,1,0}`, black, transparency 1, ZIndex 20 (above the HUD's own texts, below warnings which live in another ScreenGui with DisplayOrder 100) |
| `FlightStack` | Frame, AnchorPoint (0, 1), transparent, `AutomaticSize Y`, vertical `UIListLayout` (by LayoutOrder) |
| `FlightStack.Bars` (order 1) | Frame, auto height, list layout; holds `Fuel` (order 1, fill (90,190,235)) and `Heat` (order 2, initial fill (255,150,70), 100 % mark visible) built by `makeBar` |
| bar (`makeBar`, `:58-142`) | holder (full width, auto height, list layout) → `Row` (auto height) with `Caption` (TextLabel, size `{0.78,0, 0,0}` auto Y, Titillium Web SemiBold, DimColor (120,145,165), left, truncate at end, text = `name:upper()`) and `Value` (pos `{0.78,0,0,0}`, size `{0.22,0,0,0}` auto Y, Roboto Mono Medium, PanelColor (206,226,240), right); `Track` (order 2, bg (18,26,34) at transparency 0.35, no border, UIStroke 1 px (70,95,115) at 0.4) → `Fill` (size `{fraction,0, 1,0}`, colour per bar) and `Mark` (AnchorPoint (0.5, 0), colour (255,200,120), ZIndex 2, hidden except on Heat) |
| `FlightStack.FlightData` (order 2) | Frame, full width, auto height → `Readout` TextLabel (Roboto Mono Medium, PanelColor, left/top, RichText, size `{1,0,0,0}`, `AutomaticSize XY` so long lines grow the box) |
| `Autopilot` | TextLabel, AnchorPoint (0.5, 0), Sarpanch SemiBold, AccentColor (255,170,60), centred, top aligned, RichText, hidden |
| `Prompt` | TextLabel, AnchorPoint (0.5, 1), Sarpanch SemiBold, centred both ways, RichText, `AutomaticSize XY`, LineHeight 1.15, background PromptPlateColor (6,10,16) at PromptPlateTransparency 0.3, no border; `UIPadding`; `UIStroke` (border mode) AccentColor at PromptStrokeTransparency 0.35; hidden |

**Layout** (`layout`, `:268-318`, all through `px`/`text`):

| Element | Value (design px at 1080 lines) |
|---|---|
| `FlightStack` | pos `{0, 26, 1, -26}` (bottom-left corner 26 px in from both edges, anchor bottom-left), size `{0,400, 0,auto}`, list padding 10 |
| `Readout` | TextSize `text(15)` |
| `Bars` | size `{0,280, 0,auto}`, list padding 8 |
| each bar | inner padding 3 (between row and track); caption and value TextSize `text(12)`; track `{1,0, 0,9}`; mark `{0, max(px(2),1), 1,0}` |
| `Autopilot` | pos `{0.5,0, 0,96}`, size `{0,460, 0,24}`, TextSize `text(17)` |
| `Prompt` | cockpit mode: pos `{0.5,0, 0.64,0}` (its **bottom** edge at 64 % of the screen height: above the glare shield, clear of the radar; the vertical FOV is fixed, so the ratio holds on every aspect); otherwise pos `{0.5,0, 1,-150}`; size `{0,0, 0,30}` (width follows the text, 30 = minimum height); TextSize `text(18)`; padding left/right 18, top/bottom 5; stroke `max(px(1), 1)` |

`setCockpitMode(on)` (`:325-329`): `stack.Visible = not on`, relayout (moves the prompt).

**Per frame** (`update`, `:350-511`):

```text
-- 1) blackout, every frame (physics constants: 02-flight.md 4.6)
g      = state.gForce or 0;  strain = state.strainG or g
target = strain > BlackoutStartG 6.5 ? clamp((strain - 6.5) / max(BlackoutFullG 11 - 6.5, 1e-3), 0, 1) * BlackoutMaxDarkness 0.82 : 0
darkness = smooth(darkness, target, BlackoutResponse 0.35, dt)
blackout.transparency = 1 - darkness
if darkness > 0.002: blackout.colour = lerp(black, RedoutColor (110,0,0), clamp(state.redout, 0, 1))

-- 2) everything below at most 10x per second (Hud.TextUpdateInterval 0.1): RichText re-layout is expensive
textTimer -= dt; if textTimer > 0: return; textTimer = 0.1

-- bars
fuel = state.fuelFraction or 1
fuelBar.fill = clamp(fuel, 0, 1);  value = "%3.0f%%" of fuel*100;  value colour = fuel < 0.2 ? DangerColor : PanelColor
fuelBar.caption = state.scooping ? "FUEL  (SCOOPING)" : "FUEL"
heat = state.heat or 0;  heatMax = Warnings.Overheat.FatalHeat 2.0
heatBar.fill = clamp(heat / 2, 0, 1);  fill colour = heat >= 1.5 ? DangerColor : heat >= 1.0 ? WarnColor : GoodColor
heatBar.value = "%3.0f%%" of heat*100, colour = heat >= 1 ? DangerColor : PanelColor;  mark at x = 1/heatMax = 0.5

-- readout (11 lines, label column "%-9s")
assist   = FA ? <(120,220,150)>ON : <(255,165,70)>OFF
gColour  = strain >= 6.5 ? (255,70,60) : strain >= 3.9 (= 6.5*0.6) ? (255,165,70) : (206,226,240)
drive    = boosting ? <(120,200,255)>BOOST : cruising ? <(120,220,150)>CRUISE : "THRUST"
           + (overloaded ? "  " <(255,70,60)>OVERLOADED
              : cruising or boosting ? ""
              : boostCooldown > 0 ? "  boost %.1fs" : "  boost ready")
agility  = "%3d%%" of round(agility*100); agility >= 0.9 → <(120,220,150)>"... best turn"
mass     = state.mass ? "%.0f t" : "--"
pitch    = pitchAngle ? "%+5.1f deg  " + (angle > 1 ? "climb" : angle < -1 ? "dive" : "level") : "--"
gravity  = surfaceGravityG > 0 ? (gravityG > 0.001 ? "%.2f g  (surface %.2f g)" : "0.00 g  (surface %.2f g)") : "--"
altitude = altitude finite ? ShipUnits.distance(altitude) : "--"
limit    = speedLimit finite ? "  (" + (speedZone ? lower(speedZone) + " · " : "") + "max " + ShipUnits.speed(speedLimit) + ")" : ""
Readout.Text = join("\n",
  "THROTTLE" padded to 9 + "%3d%%" of round(throttle*100),
  "SPEED    " + ShipUnits.speed(speed) + limit,
  "DRIVE    " + drive,
  "ACCEL    " + ShipUnits.acceleration(acceleration),
  "G-FORCE  " + <gColour>"%6.2f G" of g,          -- colour from the strain, number from the plain magnitude
  "AGILITY  " + agility, "MASS     " + mass, "ALTITUDE " + altitude,
  "GRAVITY  " + gravity, "PITCH    " + pitch, "F-ASSIST " + assist)

-- prompt (only rebuilt when the string changes)
if state.prompt: visible; for each line: colour = line contains "BLOCKED" ? DangerColor : AccentColor;
                 RichText-escape (&, <, >) and wrap in <font color="#hex">; stroke = DangerColor if any line was BLOCKED else AccentColor
else hidden

-- autopilot
autopilotEngaged ? '<font color="rgb(255,170,60)">AUTOPILOT</font>  ' + status : hidden
```

Example readout (VARROW approaching Luna in cruise):

```text
THROTTLE 100%
SPEED    53.0 km/s  (approach · max 85.0 km/s)
DRIVE    CRUISE
ACCEL    35.0 m/s²
G-FORCE    1.23 G
AGILITY   41%
MASS     95 t
ALTITUDE 4.12 Mm
GRAVITY  0.02 g  (surface 0.17 g)
PITCH     -3.4 deg  dive        ("%+5.1f" pads to 5 characters)
F-ASSIST ON
```

Strings the prompt receives (owners: atmosphere/landing doc, `08-stations-economy.md` 2.5.5): `"PRESS L TO LAND"`, `"DESCEND TO LAND"`, `"LANDING BLOCKED  <reason>"`, `"ATMOSPHERIC ENTRY"`/`"DESCENT"`, `"LEVELLING OFF"`, `"LEAVING ATMOSPHERE"`/`"ASCENT"`, `"CLIMB TO LEAVE ATMOSPHERE"`/`"CLIMB TO RETURN TO SPACE"` (`ShipAtmosphere.lua:685-709`); `"K   REQUEST DOCKING   ·   <station>"`, `"CONTACTING <station> ..."`, `"P   DOCKING COMPUTER        K   CANCEL REQUEST"`, `"P   TAKE THE CONTROLS"` (`ShipDocking.lua:275`). Autopilot statuses: `STANDBY`, `NO TARGET`, `ENGAGED`, `ALIGNING`, `CRUISING`, `BRAKING`, `ARRIVED`, `TARGET LOST`, `MANUAL OVERRIDE`, `ATMOSPHERE` (`ShipAutopilot.lua`, `ShipClient.lua`).

### 3.3 ShipCockpit: head sway, vibration, camera shake (`ShipClient/ShipCockpit.lua`)

**Purpose.** Originally it moved a painted full-screen cockpit image: (1) G-force offset, (2) lag on turns, (3) Perlin vibration ("random looks like a glitch, noise looks like a machine"), plus a tiny camera shake so the image does not look glued on. Since the 3D cockpit (2026-11-02) `Cockpit.ImagePath = {}`: no image is searched (`findImage` returns at once, `:78-83`), and the same motion drives the **head** of the 3D cockpit camera.

**API.** `new(playerGui, camera)`, `update(dt, localAccel: Vector3 (m/s², ship axes, −Z forward), angularVelocity: Vector3 (deg/s, X pitch, Y yaw, Z roll), speedFraction 0..1)`, `getCameraShake(): CFrame`, `getHeadOffset(): CFrame`, `reset()`, `setVisible(b)`, `setImageEnabled(b)`.

**State.** `sway: Vector2` (px), `tilt` (deg), `clock`, `cameraShake`, `surge`, `headOffset`; image-only: `image`, `imageEnabled`, `wantVisible`, `lastX/lastY/lastRotation`, `warnedMargin`.

**Per frame** (`update`, `:158-251`):

```text
clock += dt
-- 1) G-force (GUI y points down; forward acceleration has negative z, the image should sag)
swayX = -a.x * SwayPerLateralAccel 1.2
swayY = -a.y * SwayPerVerticalAccel 1.2 - a.z * SwayPerForwardAccel 0.9
-- 2) lag on turns
swayX += w.y * SwayPerYaw 0.35
swayY += -w.x * SwayPerPitch 0.35
sway  = smooth(sway, (swayX, swayY), SwayResponseTime 0.35, dt)            (per component)
tilt  = smooth(tilt, clamp(-w.z * TiltPerRoll 0.030, -MaxTilt 3, 3), 0.35, dt)    (deg)
-- 3) vibration, deliberately NOT smoothed
vibration(t, seed) = noise(t * SlowFrequency 1.7, seed, 0) * SlowWeight 0.65 + noise(t * FastFrequency 13, seed, 17.3) * FastWeight 0.35
intensity = Vibration.Idle 0.25 + speedFraction * PerSpeed 0.85 + min(|a.z| / max(AccelReference 25, 1e-3), 1) * PerAccel 0.60      (0.25 .. 1.70)
userShake = setting CameraShake (0..1, default 1)
vibX    = vibration(clock, 0)     * Amplitude 7.0 * intensity * userShake          (px)
vibY    = vibration(clock, 91.7)  * 7.0 * intensity * userShake
vibTilt = vibration(clock, 233.1) * TiltAmplitude 0.35 * intensity * userShake     (deg)
-- 4) head in the 3D cockpit: the same offset as a head movement (image right = head left)
k      = Interior.HeadPerSwayPixel 0.002 (m per px)
headX  = clamp(sway.x + vibX, -MaxOffset 55, 55);  headY = clamp(sway.y + vibY, -55, 55)
surge  = smooth(surge, clamp(-a.z * HeadSurgePerAccel 0.004, -HeadMaxSurge 0.12, 0.12), 0.35, dt)    (+z = back into the seat)
headOffset = Translate(-headX * k, headY * k, surge) * RotZ(rad((tilt + vibTilt) * HeadRollFactor 0.6))
-- 5) camera shake (applied after everything else)
s = intensity * CameraShake.Scale 1.0 * userShake
rx = rad(vibration(clock * SpeedFactor 1.4, 400.5) * MaxDegrees 0.30 * s)
ry = rad(vibration(clock * 1.4, 511.2) * 0.30 * s)
rz = rad(vibration(clock * 1.4, 622.9) * 0.30 * s * 0.5)
cameraShake = CFrame.fromEulerAnglesYXZ(rx, ry, rz)        (Scale <= 0 → identity)
```

Magnitudes: 35 m/s² forward thrust → `swayY` target 31.5 px → head 6.3 cm up and 12 cm back (surge clamps at 0.14 → 0.12); a 30°/s yaw → 10.5 px lag (2.1 cm); vibration at full speed and thrust ≈ ±12 px (2.4 cm), tilt ±0.6°; camera shake ≤ ±0.5°.

**Camera composition** (`ShipCamera.lua:89-115`): `look` = look-around angles in degrees (X right, Y up, from `ShipInput`), `turn = RotY(-rad(look.x)) * RotX(rad(look.y))`, `neck = Translate(Interior.NeckOffset (0, -0.35, 0.3))`.

```text
cockpit:  camera = ship * Translate(Camera.SeatOffset (0, 0.5, 0)) * headOffset * neck * turn * neck^-1 * shakeOffset
external: camera = ship * turn * Translate(ExternalCamera.Offset of the ship, default (0, 16, 95)) * RotX(rad(Pitch, default -8)) * shakeOffset
shakeOffset = cockpit:getCameraShake() * entryFx:getShake()          (ShipClient.lua:2892)
FieldOfView = setting FieldOfView (60..100, default Camera.FieldOfView 80; Roblox FOV is VERTICAL)
```

The head turns around the neck (0.35 m below, 0.3 m behind the eye), so the eye travels on an arc when looking around.

**Painted-image path (dead in the shipped game, `:118-141`, `:204-236`).** Kept for completeness: the image was centred, sized `Overscale 1.25` × viewport, offset by `clamp(sway + vib, ±margin)` with `margin = min(max((imageSize - viewport)/2, 0) * MarginSafety 0.85, MaxOffset 55)` per axis, rounded to whole pixels and written only on change; rotation `tilt + vibTilt` written when it changed by more than `TiltWriteThreshold 0.05°` (`EnableTilt`). `reset()` zeroes sway, tilt, surge and the shake. `setVisible`/`setImageEnabled` only affect that image. **Port: do not port the image path.**

### 3.4 ShipInterior: the 3D cockpit (`ShipClient/ShipInterior.lua`)

**Purpose.** Brings the `Cockpit` model inside every ship to life. The model is built in Studio (`CockpitBuilder`, `ServerStorage.Dev.SHIP`, not exported) with finished SurfaceGuis, buttons, lamps, radar, levers and lights; this module builds nothing. It **fills** screens, holos and lamps ten times per second (writing only what changed), **moves** needles, levers, stick, button caps and radar blips every frame (as extra part offsets inside `ShipHull`'s batch move), **drives** the flight-arrow projection glass, **handles** button hover and clicks, and runs the **boot** sequence. Without a `Cockpit` model it stays inactive (`active = false`) and the old flat behaviour applies (`:23-24`).

**API.** `new(hull, playerGui, camera)`; `update(dt, state)` (state = `interiorState`, 2.2; call **before** `hull:update`); `boot()`; `reboot()` (dark, then boot again; after a crash and for tests); `setPointer(mode: "center" | "mouse" | nil)`; `setExternal(b)`; `press(button)` / `pressAction(action): bool`; `toggleLights(): bool`; `hasFlightHud()`; `isHudOn()`; `getStatus()`; `destroy()`. Fields: `active`, `onAction: (action) -> ()` (set by ShipClient), `gui` (CockpitInteract), `buttons[action]`.

**State.** `cache` (write-on-change memo per property, weak keys), `clock`, `screenTimer`, `sweepTimer`, `bootStart`, `powered`, `facesLit`, `lightsOn` (true), `external`, `pointerMode`, `hover`, `hintStart`, `hintShown`, `hudOn`, plus the collected handles (3.4.1).

#### 3.4.1 Model contract (what `collect`, `:216-497`, looks up)

All paths are under `<Ship>.Cockpit` (dots = children). Section 6.4 gives geometry and every GUI property.

| Path | Use |
|---|---|
| `Screens.NavScreen.Display.Main.{System, Target.Icon, Target.Name, Target.Class, Target.Distance, Target.Eta, List.Row1..3.{Name,Value}, Footer, Footer.Icon, Footer.Text}` | navigation screen (3.4.10) |
| `Screens.ShipScreen.Display.Main.{Name, Speed, SpeedUnit, SpeedC, Throttle.Fill, Throttle.Mark, ThrottleValue, Fuel.Fill, FuelValue, FuelIcon, Heat.Fill, Heat.Mark, HeatValue, HeatIcon, Drive, GForce, Chips.{FA,Boost,Gear,Scoop,MassLock}.{UIStroke,Icon,Text}}` | ship screen (3.4.11). `Name.Text = ShipConfig.Ships[model.ShipClass].DisplayName` (all ships share the cockpit, `:246-253`); `SpeedC.RichText = true`; fixed marks: throttle zero mark at x = `-MinThrottle/(1-MinThrottle)` = 0.25/1.25 = **0.2**, heat 100 % mark at x = 1/FatalHeat = **0.5** (`:287-294`) |
| `Screens.{ThrottleGauge,SpeedGauge}.Display.Face.{Needle,Value}` | round gauges; `angle` memo starts at ∞ |
| `Screens.<any>.Display.Boot` (+ `Status`, `Progress.Fill`, all TextLabels and Frames below it) | boot frames of the four screens |
| `Buttons.<Model>` with attribute `Action` and child `Cap` (+ `Led`, `Cap.Display.Icon` or `Cap.Display.Text`, attributes `Label`, `Key`, `Led.OnColor`) | buttons; the caps are the only raycastable parts (`hull`: `CanQuery = Action ~= nil`) |
| `Lamps.<Part>.Display.Back` (+ `Back.Icon`) | 8 warning lamps, by part name |
| `Controls.Throttle`, `Controls.Stick` (each with `Pivot`, `Grip`) + every part in `CockpitPlus` with attribute `Lever = "Throttle"/"Stick"` (optional `LeverFollow` share and `LeverMask` Vector3) | levers and the pilot's hands/arms |
| `Radar.Disc` (attribute `Radius` 0.62, `Display.Sweep`), `Radar.Blips.Blip1..N` + `Stem1..N` (loop to 64, stops at the first gap; **12** exist) | radar |
| `Lights.<Part>` and `CockpitPlus.Lights.<Part>`: first `Light` child, attributes `OnColor` (strip), `Switched`, `GuiPower` (+ the part's SurfaceGui) | cabin lighting |
| `Holo.{HoloLeft,HoloRight}.Display.Main.Row1..4.{Label,Value}` + `Holo.<name>.Back` | holo panels; labels set once from `HOLO_LABELS` |
| `FlightHud.Glass.Display` (+ `Main` with `Center`, `Arrow`, attribute `Unit`) | projection glass; `ShipFlightArrow.attach(Main, ShipConfig.Cursor)`; `display.Enabled = false` until powered (`02-flight.md` 3.9) |
| `PlayerGui.CockpitInteract.{Reticle, Reticle.Ring (+UIStroke), Tooltip, Tooltip.Label, Tooltip.Key, Hint, Sounds.{Hover,Press}, ButtonHighlight (Highlight)}` | on-screen pointer, tooltip, hint, sounds; the Highlight is re-parented into the cockpit model (a Highlight only renders in the world, `:516-522`) and returned to the GUI in `destroy` |

`find(root, "A.B.C")` always uses `FindFirstChild` (a child named "Face" would otherwise resolve to the SurfaceGui property, `:133-144`). Missing pieces are skipped silently.

**Write-on-change** (`set(object, property, value)`, `:532-545`): a per-property weak table remembers the last value written; reading a Roblox property costs almost as much as writing it. Port: unnecessary (immediate-mode drawing), but keep the 10 Hz cadence for texts.

#### 3.4.2 Power and boot

```text
applyDark() (:634-652, at construction and in reboot):
    every boot frame visible, faded in (transparency 0), Status "" , Progress.Fill width 0
    holos off (Main hidden, Back transparency 1), hudOn = false, all lamps "off", needles at 0 (-135°)
    applyLights(0, 0)
boot() (:655-659): if active and bootStart == nil: bootStart = clock
updateBoot(t) (:661-702), t = clock - bootStart, total = Interior.BootTime 2.6:
    light level = t < 0.3 ? ((0.06 < t < 0.13 or 0.2 < t < 0.26) ? 0.7 : 0) : clamp((t - 0.3) / 0.7, 0, 1)
    applyLights(lightsOn ? level : 0, level)
    progress = clamp(t / (total * 0.72), 0, 1)                       -- full at 1.872 s
    line     = BOOT_LINES[clamp(floor(progress * 5) + 1, 1, 5)]       -- POWER BUS, SENSOR ARRAY, FLIGHT CONTROL, NAV COMPUTER, ALL SYSTEMS GO
    fade     = clamp((t - total * 0.78) / (total * 0.22), 0, 1)     -- boot frames fade out from 2.028 s to 2.6 s
    per boot frame: Status = line, Fill width = steps(progress, 50), every label TextTransparency and every frame BackgroundTransparency = steps(fade, 20)
    lamp test: 0.35 < t < 1.15 → all lamps "caution", else "off"
    needle sweep: f = sin(pi * clamp((t - 0.45) / 1.3, 0, 1))        -- 0 → full at 1.1 s → 0 at 1.75 s
    facesLit = t > total * 0.45 (1.17 s)
    holoOn   = t > total * 0.55 (1.43 s) and not (total * 0.60 < t < total * 0.64) (1.56–1.664 s flicker)
    holos on/off = holoOn; hudOn = holoOn                             -- the projection glass and the attitude HUD flicker on with the holos
finishBoot() (:704-720) when t >= 2.6:
    powered = true, facesLit = true, boot frames hidden, holos on, hudOn = true, applyLights(lightsOn ? 1 : 0, 1),
    screenTimer = 0 (screens fill on the next frame), first time only: hintShown = true, hintStart = clock
reboot() (:1311-1320): powered = false, facesLit = false, bootStart = nil, applyDark(), boot()
```

Boot timeline (s): 0.06–0.13 and 0.20–0.26 flicker at 70 % · 0.30–1.00 lights ramp up · 0.35–1.15 lamp test · 0.45–1.75 needle sweep · 0–1.87 progress bar (lines change at 0.374, 0.749, 1.123, 1.498) · 1.17 button faces lit · 1.43 holos on, off again 1.56–1.664 · 2.03–2.60 boot frames fade · 2.60 powered, hint for 12 s.

#### 3.4.3 Per frame (`update`, `:1232-1268`)

```text
if not active: return
clock += dt
updatePointer(); updatePresses(dt); updateControls(dt, state); updateButtons(state)     -- every frame, even unpowered
if bootStart == nil: return                                     -- dark until boot()
if not powered: updateBoot(clock - bootStart); if t >= BootTime: finishBoot()
else: updateGauges(state)
updateFlightHud(state); updateRadar(dt, state); updateHint()
screenTimer -= dt
if powered and screenTimer <= 0:                               -- 10 Hz (Interior.ScreenInterval 0.1)
    screenTimer = 0.1
    updateNav(state); updateShip(state); updateHolos(state); updateLamps(state); applyLights(lightsOn ? 1 : 0, 1)
```

#### 3.4.4 Pointer, hover and press (`:728-815`)

- `setPointer(mode)`: `pointerMode = (active and not external) ? mode : nil`. ShipClient passes `"center"` while looking around (right mouse held), `"mouse"` when the mouse is free (Left Alt) and no station screen is open, `nil` over a map, on foot or in the external view (`ShipClient.lua:2806-2817`). `setExternal(true)` clears the pointer.
- `updatePointer()` (`:757-797`), every frame: `mode = powered ? pointerMode : nil`. If `mode`: point = viewport centre (`"center"`) or the mouse; ray = `camera:ViewportPointToRay(point)`; raycast **7 m** (`Interior.ReachDistance`) against the caps only; `hit = byCap[result.Instance]`.
  - When `hit` changes: Highlight adornee = the button model (enabled iff hit); on a new hit play sound "Hover", tooltip `Label.Text = button.label`, `Key.Text = "[" .. key .. "]"` (hidden when the key is empty).
  - `Reticle.Visible = (mode == "center")`; ring stroke transparency 0 on a hit, 0.45 otherwise; ring size 28 × 28 px on a hit, 22 × 22 otherwise; tooltip visible iff hit; tooltip position `{0.5,0, 0.5,-24}` in centre mode, else `(floor(mouse.x), floor(mouse.y) - 18)` px.
- Click: `UserInputService.InputBegan`, left mouse button, not processed by a GUI, with `hover` and a pointer mode → `press(hover)` (`:200-207`).
- `press(button)` (`:739-745`): `button.press = Interior.PressTime 0.14`, sound "Press" (volume = its `BaseVolume` attribute or volume × setting EffectsVolume), `onAction(button.action)`.
- `updatePresses(dt)` (`:800-815`): for buttons with `press > 0` or a non-zero depth: `press = max(press - dt, 0)`; `t = 1 - press / 0.14`; `depth = 0.03 * (t < 0.3 ? t / 0.3 : 1 - (t - 0.3) / 0.7)` while `press > 0`, else 0; cap local frame = `home * Translate(0, 0, depth)` (local +Z points away from the pilot, into the console). In: 42 ms, out: 98 ms, 3 cm deep.
- `pressAction(action)` presses a button by name (tests, shortcuts) and returns whether it exists.

#### 3.4.5 Buttons (`collect :328-360`, `updateButtons :817-850`)

Per button: `action`, `label` (attribute `Label` or the action), `key = keyLabel(action, attribute Key)`, `cap`, `home` (cap frame in eye space), `led`, `ledOn` (attribute `OnColor` of the LED, default amber), `face` (the Icon ImageLabel or the Text label) and its colour property (`ImageColor3` or `TextColor3`), `press`, `depth`.

`keyLabel` (`:54-62`): the key names of `ACTION_KEYS[action]` (`Jump → ConfirmJump`, `FlightAssist → ToggleFlightAssist`, `Music → ToggleMusic`, `Land → {Land, Dock}` = "L / K"), else of the action itself, via `ShipKeys.label` (keyboard-layout aware); empty → the model's `Key` attribute.

| Action (label) | Key | LED on when | `onInteriorAction` (`ShipClient.lua:1076-1101`) |
|---|---|---|---|
| CycleTarget (TARGET) | T | a target is locked | `markers:cycleTarget()` |
| ClearTarget (CLEAR TARGET) | Y | never | `markers:setLocked(nil)` |
| Autopilot (AUTOPILOT) | P | autopilot engaged | `autopilotButton()`: docking computer if docking is granted, else toggle autopilot |
| SystemMap (SYSTEM MAP) | N | system map open | `systemMap:toggle()` |
| StarMap (STAR MAP) | M | star map open | `starMap:toggle()` |
| Jump (HYPERSPACE JUMP) | ENTER | warp active | `jumpButton()`: cancel a planned jump (before the tunnel), else confirm, else open the star map |
| FlightAssist (FLIGHT ASSIST) | Z | FA on | `thrust.flightAssist = not thrust.flightAssist` |
| Boost (BOOST) | B | boosting | `thrust.boostRequested = true` |
| Land (LANDING) | L / K | atmosphere state ≠ SPACE | `landButton()`: land if possible and not docking, else dock/launch, else request landing |
| ExternalView (EXTERNAL VIEW) | V | external view (never visible from inside) | `toggleExternalView()` |
| Lights (COCKPIT LIGHTS) | — | `lightsOn` | `interior:toggleLights()` |
| Music (MUSIC) | J | music on | `audio:setMusicEnabled(not ...)` |

`updateButtons` every frame: `on = (rule above) and powered`; LED lit when `on or press > 0` (`setLed`: Neon + `ledOn` colour, else SmoothPlastic + `ledOff` (40,30,20)); face colour = `iconOff` (92,58,26) before `facesLit`, `bright` (255,214,160) when on, otherwise `amber` (255,138,31).

#### 3.4.6 Throttle lever and stick (`lever :372-408`, `moveLever :856-873`, `updateControls :875-890`)

Each rig: `pivot` (frame of `Pivot` in eye space), rigid parts stored as `rel = pivot⁻¹ * home`, `gripRel` = grip centre in pivot space, follower parts from `CockpitPlus` (attribute `Lever` = rig name) either rigid (no `LeverFollow`) or translating followers `{home, follow, mask}`; `current` and `shown` angles (Vector3, radians).

```text
blend = 1 - exp(-dt / 0.07)
throttle: f = clamp((throttle - MinThrottle) / (1 - MinThrottle), 0, 1)        -- 0 at -25 %, 1 at 100 %
          target = (rad(14 - 46 f), 0, 0)                                       -- +14° (back) .. -32° (forward) about the pivot's X
stick:    target = (-aim.y * rad(16), roll * rad(14), -aim.x * rad(16))         -- cursor down → stick forward; Q/E twist; cursor right → tilt right
moveLever(rig, target, blend):
    current = lerp(current, target, blend)
    if |current - shown| < rad(0.15): return                                    -- write threshold
    shown = current; turn = pivot * Angles(current.x, current.y, current.z)
    shift = gripRel ? turn * gripRel - pivot * gripRel : 0                       -- how far the grip moved (eye space)
    rigid parts: frame = turn * rel
    followers:   frame = home + shift * mask * follow                            -- translate only, keep orientation
```

VARROW data: throttle pivot (−1.00, −1.92, −1.02), stick pivot (1.00, −1.95, −1.02); 29 parts carry `Lever` (15 throttle side, 14 stick side), followers with `LeverFollow` 0.6, 0.85 or 1.0 and `LeverMask (1, 0.3, 1)` (the arm follows the hand less in height).

#### 3.4.7 Gauges (`setNeedle :585-591`, `updateGauges :892-903`)

```text
setNeedle(gauge, f): angle = -135 + 270 * clamp(f, 0, 1); write Needle.Rotation when |angle - last| > 0.4°
ThrottleGauge: f = max(throttle, 0)                                  -- reverse shows 0
SpeedGauge:    f = ln(1 + speed / 5) / ln(1 + Thrust.CruiseMaxSpeed / 5)  -- log up to the 3 Mm/s cap: 260 m/s → 30 %, 1 km/s → 40 %, 250 km/s → 81 %, 1 Mm/s → 92 %
values (10 Hz, in updateShip): ThrottleGauge.Value = "%d%%" of round(throttle*100); SpeedGauge.Value = upper(speed(v)) ("260 M/S", "1.24 KM/S")
```

#### 3.4.8 Radar (`placeBlip :905-933`, `updateRadar :935-979`)

```text
sweep: angle = (angle + RadarSweepSpeed 120 * dt) % 360; at most 30 writes per second: Sweep.Visible = powered, Sweep.Rotation = angle (clockwise)
if powered and not state.surface and state.bodies:
    locked = state.locked if still in the world
    candidates: index 0 = locked (if any), then state.bodies in order (= markers:getOrdered(): bodies and stations by centre distance), skipping locked
    for each, until all 12 blips are used:
        rel = shipCFrame:PointToObjectSpace(body.Position)          -- ship axes (x right, y up, z back)
        d = |rel|; skip unless 1e-3 < d < RadarRangeMax 1,000,000
        f = clamp(ln(max(d, RadarRangeMin 300) / 300) / ln(1e6 / 300), 0, 1)
        p = rel * (radius 0.62 * (0.14 + 0.8 f) / d)               -- 300 m → 0.14 R, 1,000 km → 0.94 R, same direction
        h = clamp(p.y, -0.32, 0.32)
        colour = body == locked ? cyan (59,214,255) : IsStar ? (255,214,120) : amber (255,138,31)
        size   = IsStar ? 0.075 : bodyRadius >= 2500 ? 0.055 : 0.04  (m; giants vs rocky bodies; 2500 ×S in the port)
        blip: visible, colour, size³, frame = disc * Translate(p.x, h + 0.012, p.z)
        stem: if |h| > 0.012: transparency 0.35, colour, size (0.008, steps(|h|, 200), 0.008), frame = disc * Translate(p.x, h/2 + 0.006, p.z); else hidden
unused blips and stems: transparency 1
```

`bodyRadius(part) = CollisionRadius or SurfaceRadius or Size.X/2`. The disc's frame: centre (0, −1.47, −2.86), up axis tilted 28° towards the pilot (6.4); ship-forward maps to the far side of the disc.

#### 3.4.9 Navigation screen (`updateNav :985-1051`, 10 Hz)

```text
System.Text = state.systemName or ""                                   ("SOL")
locked (still in the world):
    toBody = locked.pos - position; distance = |toBody|; surface = max(distance - bodyRadius, 0)
    closing = distance > 1e-4 ? velocity · unit(toBody) : 0; eta = closing > 0.05 ? surface / closing : ∞
    Name = upper(DisplayName or parent name), colour cyan; Icon tint cyan
    Class = upper(BodyClass or "Unknown")                              ("TERRESTRIAL WORLD", "STAR G", "MOON", "GAS GIANT", station classes)
    Distance = upper(distance(surface))                                ("4.48 KM", "6.84 Mm")
    Eta = "ETA " + formatDuration(eta)                                 ("ETA 00:01:23", "ETA --:--:--")
none: Name "NO TARGET" (text colour), Icon amber, Class "PRESS TARGET OR " + key(CycleTarget) ("PRESS TARGET OR T"), Distance "", Eta ""
List rows 1..3: the first three of state.bodies (bodies and stations by centre distance) except the locked one:
    Name = upper name, Value = upper(distance(max(|pos - position| - bodyRadius, 0))); unused rows: ""
Footer: warpActive → "HYPERSPACE  " + warpState (IDLE/ALIGN/COUNTDOWN/TUNNEL/ARRIVE), cyan text and icon, bg (8,30,40)
        autopilot  → "AUTOPILOT  " + status, cyan, bg (8,30,40)
        else       → "AUTOPILOT  STANDBY", amber, bg (30,18,8)
```

#### 3.4.10 Ship screen (`updateShip :1053-1138`, 10 Hz)

```text
Speed, SpeedUnit = speedParts(speed)                                  ("1.24", "KM/S")
SpeedC (RichText): limit finite (and not NaN) →
       zone ? '<font color="#<zoneColour>" size="26">' + zone + '</font>  ' + upper(speed(limit))      ("APPROACH  85.0 KM/S", "OPEN SPACE  1.06 Mm/s")
            : "MAX " + upper(speed(limit));   else ""
   zoneColour: APPROACH cyan · DEPARTURE good · HEAT ZONE, SYSTEM EDGE danger · OPEN SPACE text · others (GRAVITY WELL, LOW ALTITUDE, STATION ZONE, SURFACE) amber
Throttle bar (zero = 0.2): f = steps(clamp((throttle + 0.25) / 1.25, 0, 1), 200)
       f >= 0.2: Fill pos x 0.2, width f - 0.2, amber          (forward grows to the right)
       f <  0.2: Fill pos x f,   width 0.2 - f, reverse (200,90,60)   (reverse grows to the left)
ThrottleValue = "%d%%" of round(throttle*100)                         ("-25%", "0%", "100%")
fuel = clamp(fuel); Fuel.Fill width steps(fuel, 200), colour fuel < 0.25 ? danger : cyan; FuelValue "%d%%"; FuelIcon tint scooping ? cyan : muted
heat = max(heat, 0); colour = heat >= 1 ? danger : heat >= Phase1Heat 0.8 ? amber : good
       Heat.Fill width clamp(steps(heat / 2, 200)), that colour; HeatValue "%d%%" (up to "200%"); HeatIcon tint heat >= 0.8 ? colour : muted
Drive: travelling → "HYPERSPACE" cyan · autopilot → "AUTOPILOT" cyan · boosting → "BOOST" cyan · cruising → "CRUISE" amber · throttle < 0 → "REVERSE" (200,90,60) · else "THRUST" text
GForce = "%.1f G"
Chips: FA = FA ? on : caution, text "F-ASSIST" / "FA OFF" · Boost = boosting ? on : cooldown > 0 ? off : ready · Gear = gearDown ? on : off · Scoop = scooping ? on : off · MassLock = massLocked ? caution : off
gauge values (3.4.7)
```

#### 3.4.11 Holos and the status line (`statusLine :1140-1163`, `updateHolos :1165-1191`, 10 Hz)

| Holo | Row label | Value |
|---|---|---|
| HoloLeft | BODY | `upper(bodyName)` or "DEEP SPACE" |
| | ALTITUDE | `upper(distance(altitude))` if finite and < 1e9, else "--" |
| | GRAVITY | `"%.2f G"` of gravityG (0 when nil) |
| | PITCH | `"%+d DEG"` of round(pitchAngle), or "--" |
| HoloRight | MASS | `"%.0f T"` or "--" |
| | AGILITY | `"%d%%"` of round(agility*100) |
| | BOOST | cooldown > 0 ? `"%.1f S"` : "READY" |
| | STATUS | `statusLine` text and colour |

`statusLine` priority: crashing → "HULL FAILURE" danger · heat ≥ 1 → "OVERHEATING" danger · timeToImpact < 10 s → "IMPACT WARNING" danger · travelling → "HYPERSPACE" cyan · atmosphere ENTRY → "ATMOS ENTRY" amber · EXIT → "ASCENDING" amber · landing scene → "SURFACE" good · heat ≥ 0.8 → "HEAT HIGH" amber · mass locked → "MASS LOCKED" amber · else "NOMINAL" holoText (210,245,255).

#### 3.4.12 Lamps and chips (`setLamp :557-564`, `setChip :566-575`, `updateLamps :1193-1208`, 10 Hz)

`blink = (clock % 0.8) < 0.5` (0.5 s on, 0.3 s off).

| Lamp | Rule |
|---|---|
| FlightAssist | FA ? on : caution |
| Gear | gearDown ? on : off |
| MassLock | massLocked ? caution : off |
| Scoop | scooping ? on : off |
| Autopilot | engaged ? on : off |
| Fuel | fuel < 0.1 ? (blink ? warning : off) : fuel < 0.25 ? caution : off |
| Heat | heat ≥ 1 ? (blink ? warning : off) : heat ≥ 0.8 ? caution : off |
| Warning | (crashing or heat ≥ 1.2 or timeToImpact < 10) and blink ? warning : off |

Lamp modes (back colour, icon tint): off (24,18,12)/(70,52,34) · on (8,34,44)/cyan · caution (58,30,8)/amber · warning (66,12,8)/danger (255,70,60). Chip modes (background, stroke, icon and text): off (22,16,10)/(120,62,16)/(150,110,70) · ready (22,16,10)/(120,62,16)/amber · on (8,30,40)/cyan/cyan · caution (44,24,8)/amber/amber.

#### 3.4.13 Lights (`collect :433-461`, `applyLights :594-610`, `toggleLights :1301-1307`)

Per light part: `light` (first Light child), base `brightness`, `strip = OnColor attribute is a Color3`, `switched = (strip and Switched ~= false) or Switched == true`, `gui` = its SurfaceGui if attribute `GuiPower`.

```text
applyLights(roof, panel): roof = steps(roof, 20), panel = steps(panel, 20)
  level = switched ? roof : panel
  light: Enabled = level > 0, Brightness = base * level
  strip: level > 0.4 ? (Neon, OnColor) : (SmoothPlastic, stripOff (58,60,64))
  gui:   Enabled = level > 0.4
powered: applyLights(lightsOn ? 1 : 0, 1) every 0.1 s and on toggle; the Lights button switches only the "switched" set
```

Switched (Lights button): roof strips, strut LEDs, roof slots, sill tubes, foot lens, reading lens, cabin fill. Panel (follow power only): dash glow, foot glow, console glows, pod edges, pod glows, holo lens, pedestal LED and the decorative CockpitPlus screens (6.4.9).

#### 3.4.14 Hint (`updateHint :1210-1226`)

From `hintStart` (first power-up only) for `HintTime 12` s: `alpha = clamp((12 - t) / 1.5, 0, 1) * clamp(t / 0.6, 0, 1)` (0.6 s in, 1.5 s out); `Hint.Visible = not external`; `TextTransparency = steps(1 - alpha, 20)`; `TextStrokeTransparency = steps(0.55 + 0.45 (1 - alpha), 20)`. The text lives in the unexported `CockpitInteract` GUI (6.5).

#### 3.4.15 Projection glass (`updateFlightHud :1287-1299`)

`shown = hudOn and not external and state.cursorShown ~= false`; `Display.Enabled = shown`; when shown, `arrow:update(state.cursor)` (`ShipFlightArrow`, `02-flight.md` 3.9). `hasFlightHud()` tells `ShipCursor` to hide the flat arrow; `isHudOn()` gates the attitude indicator.

#### 3.4.16 Test status (`getStatus :1323-1349`)

`{active, powered, booting, pointer, hover, lights, buttons (sorted action names), blips (visible count), throttleAngle (deg), speedNeedle (deg), flightHud (enabled), flightHudCursor (arrow offset)}` — the port's `debugInfo` should expose the same.

### 3.5 ShipHull: the visible ship (`ShipClient/ShipHull.lua`)

**Purpose.** Places the player's ship model every frame, decides which parts are visible from where (seated in the cockpit, external view, walking aboard), animates the landing gear (`ShipGear`, `02-flight.md` 3.8), drives engine glow and exhaust, and on station decks makes the hull solid for walking. Model source: `ReplicatedStorage.Assets.Ships.<Hull.Model>` (the eight ships of `Assets.rbxmx`); without it a box hull is built (`buildBlocks`).

**API.** `new()`, `build(parent)`, `update(shipCFrame, throttle, boosting)`, `setInterior(inside)`, `setGearDown(down)`, `setClearGlass(clear)`, `setHidden(parts, hidden)`, `getCockpit()`, `getLocalCFrame(part)` / `setLocalCFrame(part, cf)` (eye space, used by ShipInterior), `setVisible(b)` (parents the model in or out of the workspace), `setSolid(b)`, `getModel()`, `destroy()`. Fields read by others: `pieces`, `offsets`, `gearDown`, `gearStowed`, `isGear`, `gearClosed`, `forceHidden`, `exhausts`.

**Build from the model** (`buildFromModel`, `:155-242`):

```text
model = clone(template); ShipPerformance.cullDetail(model)        -- performance mode: drop childless, attribute-less parts < 0.6 m in Hull, Canopy, Gear, Engines, Cockpit.CockpitPlus
root  = model.PrimaryPart or "Root"                                -- Root = the pilot's eye
seat  = Translate(Camera.SeatOffset (0, 0.5, 0)); fromRoot = root.CFrame^-1
marks: HideInside (canopy cage, old inner shell, SeatHidden groups), HideSeated (walkable interior behind the bridge), CockpitOnly (CockpitPlus: pilot, struts, pods)
collectExtras(model)                                               -- see below
for every BasePart: anchored, no collision, CanQuery only for parts with attribute Action (button caps), no touch
    offset = seat * (fromRoot * part.CFrame)                       -- fixed ship-space frame, computed once
    pieces[], offsets[], indexOf[part], castShadow[part]; flags hideInside/hideSeated/cockpitOnly (part or ancestor marked), isGear (under "Gear")
    attribute EngineGlow → addPlume(part, offset)
buildGearRig(); applyVisibility(); setVisible(true)
```

Per ship (from `Assets.rbxmx`): VARROW 3 engine glows (Main Ø5.04, two pods Ø3.38, colour (255,105,28)), BRANNOCK 4 (Ø4.78, (120,186,255)), MORVEN 4 ((255,196,128)), KESSOCK 4 ((255,186,110)), LOCHRAN 3 ((150,220,255)), CALDER 3 ((140,95,235)), TORRIN 3 ((70,200,255)), DORNOCH 8 ((255,150,60)); each glow disc is a thin Neon part (0.17–0.19 m) with a PointLight (brightness 2.5–3, range 8.7–22).

**Per frame** (`update`, `:416-447`): `lastShipCFrame = cf`; `stepGear()` if a gear rig exists; `cframes[n] = cf * offsets[i]` for every **visible** part (`moveIndex`), one `workspace:BulkMoveTo`; `updatePlumes(throttle, boosting)`; box hull only: exhaust spheres glow (`transparency = 1 - (0.25 + 0.75 glow)`, mesh scale `(0.6 + 0.9g, 0.6 + 0.9g, 0.8 + 2.4g)`). Absolute frames from the stored offsets on purpose: `PivotTo` accumulates rounding errors.

**Visibility** (`isHidden :727-740`, `applyVisibility :688-725`):

```text
isHidden(part) =
     forceHidden[part]                                   -- e.g. empty cargo slots (ShipAboard)
  or (gearStowed and isGear[part])                       -- stowed gear is hidden ...
  or (not gearStowed and gearClosed[part])               -- ... and the closed belly covers (attribute GearClosed) shown instead
  or (cockpitOnly[part] and not interior)                -- pilot, struts, pods only from the seat
  or (interior and (cockpit == nil or hideInside[part] or hideSeated[part]))
applyVisibility():
  per part: LocalTransparencyModifier = hidden ? 1 : (tinted glass (Material Glass or attribute Glass) with Transparency < 0.5 ? (clearGlass ? Aboard.GlassClear : 0) : 0)
            CastShadow = original and not hidden          -- a hidden canopy would still shade the whole cockpit
            extras of the part (SurfaceGuis/BillboardGuis by adornee or ancestor, Lights, ParticleEmitters, Beams, Trails, Fire, Smoke, Sparkles, Highlights: Enabled; Decals/Textures: LocalTransparencyModifier) follow the part's hidden state
  exhaust: setEnabled(not isHidden(plume))
  rebuildMoveList(): only visible parts move; newly shown parts are placed immediately
```

Without a cockpit (`cockpit == nil`) the old rule applies: from inside **everything** is hidden (the painted image showed the interior). The `Cockpit` model's own screens and lights are excluded from `extras` (ShipInterior switches them). Why extras matter in Roblox: hidden parts stop moving and the world is rebased every frame, so their signs and screens would hang in space in front of the canopy after a turn (`:631-645`). `setInterior(inside)` (`:462-496`) for the model just stores the flag and calls `applyVisibility`; for the box hull it hides every part whose angular extent comes closer than `InteriorHideAngle 62°` to the view axis.

`setClearGlass(clear)` (walking aboard: lighter glass), `setHidden(parts, b)` (`forceHidden`), `setSolid(b)` (decks: parts with at least two dimensions ≥ 2 m, `Transparency < 1`, outside the `Cockpit` model, not named `Plume`, and not unions/meshes with Box or Hull collision fidelity larger than 8 m become collidable, `:846-880`).

**Gear** (`buildGearRig :533-556`, `poseGear :558-569`, `stepGear :571-614`): see `02-flight.md` 3.8. Timing: retract = legs over `GearLegTime 1.6 s`, then doors over `GearDoorTime 0.6 s`; extend reverses; seated in a ship with a cockpit the gear jumps instantly (folded legs reach under some cockpit floors); `gearStowed` switches visibility at the end of a retract and at the start of an extend. `setGearDown(down)` honours a debug override `gearForce`. Gear stepping uses `os.clock` with `dt ≤ 0.1`.

**Engine glow** (`addPlume :250-276`, `updatePlumes :811-832`): for every `EngineGlow` disc an invisible ship-aligned carrier part `Plume` at the disc centre (ship-space translation only; +Z = backwards) gets a `ShipExhaust(plume, diameter = max(disc.Size.Y, disc.Size.Z), disc.Color, full = true)`.

```text
glow = clamp(|throttle|, 0, 1); boost = boosting == true
skip when |glow - lastGlow| < 0.01 and boost unchanged
lift = boost ? 1.35 : 1
disc.Transparency = 0.5 * (1 - glow)                      -- half transparent at idle, solid at full thrust
exhaust:set(glow, boost)
light.Brightness = base * (0.35 + 1.15 glow) * lift;  light.Range = base * (0.7 + 0.6 glow) * lift
```

Note that reverse thrust also lights the main engines (`|throttle|`).

**Box hull fallback** (`buildBlocks :281-407`): built from `Hull` dimensions (length 54, width 30, height 7.5, struts, dihedral 7°, fins, nacelles R 2.6, colours body (126,156,168), trim (74,96,108), dark (42,52,60), glass (150,200,220) at 0.72, exhaust (120,190,255) Neon spheres, nav lights red/green). The port does not need it (all eight models exist).

### 3.6 ShipExhaust: engine flames (`ShipClient/ShipExhaust.lua`)

**Purpose.** A soft particle flame behind each nozzle instead of the old stretched neon sphere ("better VFX", player feedback 2026-11-05). Also used by NPC traffic with half the rate and no veil.

**Build** (`new(carrier, diameter d, colour, full)`, `:63-108`): carrier made invisible; an Attachment `Exhaust` at `(0, 0, d * StartOffset 0.1)` (just behind the glow disc); emitters with texture `rbxasset://textures/glow.png` (soft round sprite for additive drawing), `LightEmission 1`, `LightInfluence 0`, `LockedToPart true` (particles stay in ship space; at 1000 m/s a world-space flame would leave a string of beads), emission direction Back (+Z), spread 2° × 2°, random rotation 0–360°, rate 0 and speed 0 until `set`; lifetime `life × (0.85 .. 1.15)`.

| Layer | Colour over life | Size over life | Transparency over life | Life | Brightness |
|---|---|---|---|---|---|
| Core `ExhaustCore` | 0: `lerp(colour, white, CoreWhite 0.6)` → 0.35: colour → 1: colour | `d·CoreSize 0.95` → `d·0.95·CoreTaper 0.3` | 0: `CoreTransparency 0.1` → 0.6: 0.55 → 1: 1 | `CoreLife 0.14 s` | `Brightness 2.2` |
| Veil `ExhaustVeil` (full only) | colour | `d·VeilSize 1.5` → `d·1.5·VeilTaper 0.55` | `VeilTransparency 0.72` → 1 | `VeilLife 0.22 s` | 2.2 |

**Update** (`set(glow, boost)`, `:114-138`), only when glow changed by ≥ 0.01 or boost toggled:

```text
stretch   = boost ? BoostLength 1.6 : 1
length    = d * (IdleLength 0.4 + (FullLength 3.4 - 0.4) * glow) * stretch          (flame length, m)
bright    = boost ? BoostBrightness 4 : Brightness 2.2
core.Speed = (0.85 .. 1) * length / CoreLife;  core.Rate = (CoreRateIdle 35 + (CoreRate 110 - 35) * glow) * rateScale (1, traffic 0.5)
veil.Speed = (0.85 .. 1) * length * VeilLength 1.5 / VeilLife;  veil.Rate = VeilRate 45 * glow;  veil.Enabled = enabled and glow > VeilThreshold 0.05
both Brightness = bright
```

`setEnabled(b)` (`:142-157`) switches both emitters and clears live particles when disabled (hidden part or pooled NPC). Example, VARROW main nozzle d = 5.04 m: idle flame 2.0 m, full 17.1 m, boost 27.4 m; at full thrust ≈ 110 core particles/s × 0.14 s ≈ 15 alive, ≈ 10 veil particles alive. Performance mode: `CoreRate 50`, `CoreRateIdle 15`, `VeilRate 0`.

### 3.7 ShipMarkers: markers, target lock, target panel (`ShipClient/ShipMarkers.lua`)

**Purpose.** A planet of 2 km radius at 500 km is a few pixels and practically invisible against the stars; without markers one flies blind (`:5-7`). Every tagged body and station gets a ring that is always drawn; T cycles the lock through them by distance; the locked target gets its own colour and an info panel. ETA uses the **closing speed**, so flying past makes the ETA grow (`:18-20`). Other players (multiplayer) get rings in their colour but are never targets.

**API.** `new(playerGui)`, `update(dt, shipPosition, velocity)`, `cycleTarget()`, `setLocked(part | nil)`, `getLocked()`, `getOrdered()` (bodies and stations sorted by centre distance at the last update; used by the radar and the nav screen), `setPanelVisible(b)`, `setVisible(b)`, `destroy()`; statics `formatDuration`, `formatDistance` (= `ShipUnits.distance`).

**State.** `entries[part] = {part, gui (BillboardGui), label, stroke, color, pilot, text, lock, lockScale}`, `ordered`, `labelled` (scratch), `locked`, `timer`, `visible`, `panel` (TextLabel), `lockSound`.

**Sources** (`:197-208`): CollectionService tags `CelestialBody` (`Gravity.Tag`), `ShipStation` (`Stations.Tag`, invisible 2 m beacon parts at the station root with `DisplayName`, `BodyClass`, `CollisionRadius`), `ShipPilot` (multiplayer beacons) — existing instances plus added/removed signals. Attributes read: `DisplayName` (else the parent model's name, else the part name), `BodyClass`, `CollisionRadius` / `SurfaceRadius` / `Size.X/2` (radius), `IsStar`, `MarkerColor`, `MarkerPriority`, `PilotFriend`.

**Marker build** (`addMarker :230-293`): BillboardGui "Marker", `LightInfluence 0`, size 150 × 96 px, pinned by `ShipOverlay.pin(gui, part, nil, nil)` (3.8), `Enabled = visible`, parented to the part (it renders only while the part is in the world). Children: `Ring` (Frame, AnchorPoint (0.5,0.5) at the centre, 22 × 22 px, transparent, `UICorner (1,0)` → circle, `UIStroke` colour, thickness 1.5, transparency 0.3) and `Info` (TextLabel, AnchorPoint (0.5,0), pos `{0.5,0, 0.5,14}` (14 px below the ring centre), size `{1,0, 0,32}`, transparent, Titillium Web SemiBold 14, colour, RichText, top-aligned, centred, `TextStrokeTransparency 0.6` = black outline at 40 %). Colour: `Markers.Color` (150,200,230); pilots: their `MarkerColor`.

**Label text** (`markerText :103-109`): `upper(name)` escaped; with a distance: `name .. "\n<font face=\"RobotoMono\" size=\"12\" transparency=\"0.15\">" .. distance .. "</font>"`. Written only when changed.

**Lock** (`setLocked :307-343`):

```text
previous (if any): stroke and label back to its colour, gui size 150 × 96, lock brackets hidden
locked = part
part: stroke and label LockedColor (255,170,60), gui size 180 × 110
      brackets built on first lock: Frame "Lock" centred, size (22 + 2*8)² = 38 × 38 px, UIScale; 4 corners × 2 bars:
        horizontal 8 × 2 px and vertical 2 × 8 px, AnchorPoint and position at each corner (0,0) (1,0) (0,1) (1,1), LockedColor
      brackets visible; if the target changed: UIScale 1.8 → 1 with TweenInfo(0.22 s, Quad, Out)   -- the brackets close in on the ring
part == nil: panel text ""
```

**Cycle** (`cycleTarget :367-382`, key T): nothing when `ordered` is empty; plays the lock tone (`LockSound rbxassetid://10128760939`, volume 0.45); none locked or the locked one not in the list → `ordered[1]`; else the next in the list, wrapping. Y (`ClearTarget`) → `setLocked(nil)`. Both keys ignore processed input and are dead on station decks and walking aboard (`ShipConfig.Runtime.Deck/Aboard`, `:211-225`). A click on a body in the system map also calls `setLocked` (`ShipClient.lua:290-292`).

**Focus** (`findFocus :445-465`): among enabled markers in front of the camera, the one whose projected centre is nearest to the viewport centre, if closer than `FocusRadius 0.14 × viewport height` px.

**Update** (`:467-580`), every `UpdateInterval 0.1 s`:

```text
ordered = all non-pilot entries whose part is in the world, sorted by |part.Position - shipPosition| (centre distance)
focus = findFocus()
labelled = []
for part in ordered:
    distance = |part - ship|; radius = CollisionRadius or SurfaceRadius or Size.X/2; surface = max(distance - radius, 0)
    detailed = part == locked or part == focus
    label text = markerText(name, detailed ? ShipUnits.distance(surface) : nil)
    showText = detailed or surface < TextRange 2,000,000 or alwaysNamed(part)
    label.Visible = showText; if showText: labelled += {entry, part, distance}
alwaysNamed(part) = IsStar or (has SurfaceRadius and the model's parent has no child "Body")   -- star and planets yes; moons (System.Earth.Luna.Body) and stations no
pilots (multiplayer): colour from MarkerColor; text = markerText(DisplayName or "PILOT", distance(centre distance));
    showText = PilotFriend or distance < Pilots.NameRange 50,000 or locked or focus
declutter(labelled)
panel: locked and in the world →
    closing = distance > 1e-4 ? velocity · unit(toBody) : 0;  eta = closing > 0.05 ? surface / closing : ∞
    panel.Text = '<font color="rgb(255,170,60)">TARGET LOCKED</font>\n' + name + '\n' + (BodyClass or "Unknown") + '\n'
               + format("%-9s%12s", "DISTANCE", distance(surface)) + '\n' + format("%-9s%12s", "ETA", formatDuration(eta))
    locked but gone → setLocked(nil)
```

**Declutter** (`:392-439`): no two labels overlap; the more important one wins and the other shows its ring only.

```text
for each labelled item: priority = locked ? +inf : pilot ? (MarkerPriority or 0) : (SurfaceRadius or 0)     -- stations have none → last
                        point = camera:WorldToViewportPoint(part.Position); onScreen = point.z > 0
                        bounds = label.TextBounds (real size, honours the player's larger-text setting)
                        centre (x, y) = (point.x, point.y + LABEL_OFFSET 14 + bounds.y / 2); w, h = bounds
sort by priority desc, then distance asc
shown = ShipOverlay.getSolidBoxes(camera)                    -- solid signs (the docking pad marker) come first
for item: free = true
          if onScreen: for other in shown: if |x - ox| < (w + ow)/2 + LABEL_GAP 8 and |y - oy| < (h + oh)/2 + 8: free = false
                       if free: shown += item
          label.Visible = free                                  -- off-screen labels stay "visible" (nothing to collide with)
```

From Earth this kept "Luna" over TRANQUILITY YARDS, and Mars over Phobos, Deimos and Ares Deep (`:384-391`).

**Target panel** (`new :163-186`): ScreenGui `ShipTargetGui` (DisplayOrder 45, ignores the inset); TextLabel `TargetPanel`, AnchorPoint (1,1), pos `{1,-28, 1,-28}`, size 340 × 120 px (fixed, not scaled), Roboto Mono Medium 15, `PanelColor` (210,230,240), right/bottom aligned, RichText. Hidden in the cockpit (`setPanelVisible(false)` while the 3D cockpit is active, `ShipClient.lua:339, 958`).

`setVisible(b)` (`:586-592`) enables or disables the panel GUI and every billboard (decks, landing scene, crash). `destroy` unpins and removes everything.

### 3.8 ShipOverlay: billboards in front of the camera (`ShipClient/ShipOverlay.lua`)

**Problem it solves (Roblox only).** `AlwaysOnTop` billboards and SurfaceGuis were not drawn in this place, so anything between camera and marker hid it, and beyond the graphics level's view distance markers were not drawn at all (the Moon had no label, 2026-11-03).

**Workaround.** Every pinned BillboardGui is adorned to an Attachment on one invisible anchor part at the world origin, and each frame (`RunService:BindToRenderStep("ShipOverlay", Camera + 10)`, after the camera and the origin rebase) that attachment is placed **on the sight line** to the real target, just in front of the eye:

```text
offset = part.Position + pin.offset - eye; d = |offset|
if pin.maxDistance and d > maxDistance: attachment = eye - look * 1      -- behind the eye: not drawn
elif d > BASE_DISTANCE 1: offset *= 1 * (1 + log10(d / 1) * SPREAD 0.05) / d        -- 1 km → 1.15 m, 1e9 m → 1.45 m
attachment.WorldPosition = eye + offset
```

The log staggering keeps nearer markers in front of farther ones (Jupiter and its moons). `pin(gui, part, offset, maxDistance)` sets `Adornee`, clears `AlwaysOnTop`, `StudsOffset`, `MaxDistance`; `unpin` on destroy; `setSolid(gui, true)` marks a sign (the docking pad marker, `ShipStation.lua:1123-1124`: 40 m above the pad, `maxDistance = DockingRange`) whose text boxes `getSolidBoxes(camera)` reports to the declutter (centre = projected target, size = each visible TextLabel's `TextBounds`).

**Port: not needed.** Markers are drawn in 2D after the 3D pass, so nothing can occlude them; draw them far to near so nearer ones end on top.

### 3.9 ShipNotices: flight notices (`ShipClient/ShipNotices.lua`)

**Purpose.** Fills `StarterGui.FlightNotices` (built by a Studio UI builder, not exported): traffic-control radio, the docking approach aid, the survey bar and short toasts. Without the GUI everything stays silent (flight still works). `docking()` and `survey()` are called every frame; writes only on change.

**API.** `new(playerGui)` (attaches asynchronously; calls before the GUI exists are queued for `comms`/`toast`, dropped for `docking`/`survey`), `setEnabled(b)` (whole GUI; enabled after the menu, `ShipClient.lua:3209`), `setVoice(voice)`, `comms(sender, body, seconds = 6)`, `toast(title, body?, bad?, seconds = 4)`, `docking(info | nil)`, `survey(info | nil)`, `play(name)`, `update(dt)`, `destroy()`.

```text
attach: Canvas (+ its UIScale), colours from Canvas attributes AccentColor/InkColor/MutedColor/GoodColor/BadColor, else defaults
        (255,170,60) / (230,236,240) / (140,150,160) / (120,220,150) / (255,90,80)
        fit(): factor = max(min(w / 1920, h / 1080), 0.05); UIScale = factor; Canvas.Size = {1/factor, 0, 1/factor, 0}  (design 1920 × 1080 fills the screen after scaling)
        Comms, Docking, Survey, Toast frames start hidden
comms(sender, body, s):   Comms.Sender = sender ("MERIDIAN STATION  ·  TRAFFIC CONTROL", "FLIGHT COMPUTER"), Comms.Body = body, visible, sound "Comms",
                          until = now + s; voice line if the body starts with a prefix in Voice.CommsLines
toast(title, body, bad, s): Toast.Title = title, colour bad ? Bad : Accent; Toast.Body = body or ""; visible; sound "Toast"; until = now + s;
                          voice lines Voice.NoticeLines[title]
docking(info): nil → hide; else Docking.Pad = info.pad ("PAD 04"), Distance ("820 M"), Status (Accent if info.warn else Ink), Limit ("LIMIT 140"), Timer ("09:12")
survey(info):  nil → hide; else Survey.Label ("SURVEY  ·  EARTH"), Bar.Fill width = floor(clamp(progress) * 50 + 0.5) / 50 (2 % steps, height kept),
               Survey.Value ("HOLD G"), Survey.Range (Good if info.good else Muted)
update: hide comms / toast when os.clock() passes their end time
```

Sounds play only while enabled. Senders, bodies and toast titles come from docking, survey, mining, construction, decks, pilots and ShipClient (list in Appendix A; voice lines in `08-stations-economy.md` 2.6).

### 3.10 ShipDust: speed dust and the driver of the cruise layers (`ShipClient/ShipDust.lua`)

**Purpose.** Speed is invisible without reference points: against a star field 5 m/s and 95 m/s look identical. A handful of motes close to the ship that stream past gives the eye something to read motion from (`:7-14`). Since 2026-11-05 every mote is a camera-facing Beam from a bright head to a soft tail (like motion blur), the strokes point along the flight direction and lengthen with speed, the colour follows the situation, and in an atmosphere they become thinner, longer, paler air streaks. ShipDust also drives **ShipSpeedStreaks** and (if enabled) **ShipDistanceLines**; ShipClient only calls `setAir`, `update`, `setMood`, `setVisible`. At most `Dust.Count + SpeedStreaks.Count + DistanceLines.Count` beams (90 + 60 + 48; the lines are off).

**API.** `new(camera)`, `update(dt, cameraPosition, velocity, shipCFrame?)`, `setMood("neutral" | "approach" | "danger")` (forwarded to streaks and lines), `setAir(b)`, `setVisible(false)` (hides all three layers at once; showing happens in `update`), `getStats()` (`{dust, streaks, lines, tier, rate, transition, density, beams}`), `destroy()`.

**State.** `locals[i]` (mote position relative to the anchors), `heads/tails/beams`, `levels/shown/dirty/width0/width1`, `scroll` (common anchor offset), `air` (bool), `mix` (0 space .. 1 air), `layer` (visibility by speed, rate-limited), `direction` (last flight direction), `mood/moodTarget/colorShown`, `view` (one reused cone table for all three layers). Everything lives in a Folder under `workspace.CurrentCamera` (local only). Seeds: dust `Random(20260824)`, streaks `20261105`, lines `20261106`.

**Build** (`:165-263`): `Count` motes at uniform random positions in a cube of edge `FieldSize 260` m; per mote a head Attachment on `DustAnchor` and a tail Attachment on `DustTailAnchor`, Beam `FaceCamera`, 2 segments, `LightEmission 1`, `LightInfluence 0`, colour `MoodNeutral`, disabled. Transparency profile (`SEQUENCES`, `:95-105`, 17 levels): at the head `h = level/16`, at the middle `h + (1 − h)·0.45`, at the tail 1.

**View cone** (`:113-133`, filled each frame, `:346-360`): `tanHalf = tan(FOV/2)` (vertical), `aspect = max(w/h, 16/9)`, `tanX = tanHalf·aspect·1.1`, `tanY = tanHalf·1.1`, `pad = 1.5` m, camera right/up. `outside(p0, p1)` (camera space x right, y up, d depth) is true only when both ends are behind the same edge: `d0, d1 < 0.05`, or both `x > d·tanX + pad` (or both `< −(…)`), or the same for y. Better to draw once too often than to pop.

**Per frame** (`update`, `:326-573`):

```text
skip the frame if dt or |velocity| or |position| is NaN/inf (one bad frame must not poison the session)
mix  = mix + ((air ? 1 : 0) - mix) * (1 - exp(-dt / ModeFade 0.6))          -- space ↔ air cross-fade
pixelScale = tan(FOV/2) / (1080 / 2)                                         -- metres of width per metre of depth for 1 px at 1080 lines
fill the view cone; streaks:update(dt, pos, look, velocity, mix, pixelScale, view); lines:update(... , shipCFrame)
mood = approachColor(mood, moodTarget, 1 - exp(-dt / MoodFade 0.45))
-- how visible the dust layer is
maxSpeed  = Thrust.MaxSpeed 1000
fadeStart = 1000 * lerp(CruiseFadeStart 0.6, 1, mix)                         -- 600 m/s in space, 1000 in air
fadeEnd   = 1000 * lerp(CruiseFadeFactor 2, AirCruiseFadeFactor 3, mix)      -- 2000 / 3000 m/s
cruiseFade = clamp((speed - fadeStart) / max(fadeEnd - fadeStart, 1), 0, 1)
target    = clamp((speed - MinSpeed 1.5) / SlowFade 4, 0, 1) * (1 - cruiseFade)
layer moves towards target by at most dt / FadeTime 0.3 per frame (both ways)
if layer <= 0.004: hide all motes; return
direction = speed > 1e-3 ? velocity / speed : last direction
flow      = speed > fadeEnd ? direction * fadeEnd : velocity                  -- never faster than at the end of the fade
scroll   -= flow * dt * RelativeSpeed 1.35                                    -- deliberately exaggerated: 1:1 looks tame
if any |scroll component| > 4096: locals += scroll; scroll = 0               -- (Roblox 32-bit attachment precision)
speedFraction = clamp(speed / 1000, 0, 1)
length    = MinLength 0.6 + (lerp(MaxLength 26, AirMaxLength 40, mix) - 0.6) * speedFraction ^ LengthCurve 0.8      (m)
tailOffset = direction * length                                               -- tail AHEAD of the head: the mote moves backwards, its blur trails forward
headAnchor = cameraPos + scroll; tailAnchor = cameraPos + scroll + tailOffset
slowT = lerp(MaxTransparency 0.6, AirMaxTransparency 0.88, mix); fastT = lerp(MinTransparency 0.2, AirMinTransparency 0.5, mix)
baseVisible = (1 - (slowT - (slowT - fastT) * speedFraction)) * layer
active = round(lerp(Count, min(AirCount, Count), mix))                       -- 90 in space, 70 in air
colour of every beam = mood when it changed by more than 0.012 (L1)
headWidth(depth) = HeadPixels 1.8 * pixelScale * (1 - mix) * max(depth, 0.5) + AirHeadWidth 0.07 * mix
tailWidth(depth) = TailPixels 0.6 * pixelScale * (1 - mix) * max(depth + tailOffset·look, 0.5) + AirTailWidth 0 * mix
shift = look * (ForwardShift 0.6 * 130 * (1 - mix))                          -- cube centre 78 m ahead in space; around the camera in air
for i in 1..Count:
    x, y, z = locals[i] + scroll; wrap each axis into (centre ± 130) of the cube around shift; wrapped → write the attachment positions
    offset = (x, y, z); depth = offset · look
    visible = 0
    if i <= active and not outside(head, head + tailOffset):
        closest = |offset + direction * clamp(-offset·direction, 0, length)|     -- nearest point of the stroke to the eye
        near    = clamp((closest - NearClip 7) / NearFade 6, 0, 1)               -- nothing within 7 m, full from 13 m
        edge    = clamp((130 - |offset - shift|) / (130 * EdgeFade 0.3), 0, 1)   -- fade out towards the cube faces (no visible wrap)
        visible = baseVisible * near * edge
    level = floor((1 - visible) * 16 + 0.5); level >= 16 → beam off; else widths (only when > 15 % off), transparency profile [level], on
```

Numbers: speeds 0 / 1.5 / 5.5 m/s → layer target 0 / 0 / 1; at 300 m/s the stroke is 10.3 m long and its head 52 % opaque × layer; the full 26 m stroke (head 80 % opaque) would come at 1000 m/s, but from 600 m/s the cruise fade already removes the layer (0 at 2000 m/s; at 1000 m/s `layer` ≤ 0.71). In air: 70 motes, 40 m strokes at full speed, 0.07 m fixed head width, 12–50 % opaque, gone between 1000 and 3000 m/s. Performance mode: `Count 45`, `AirCount 35`.

### 3.11 ShipSpeedStreaks: cruise streaks (`ShipClient/ShipSpeedStreaks.lua`)

**Purpose.** Long, hair-thin, pale streaks radiating from the vanishing point of the flight direction (AFloidDEV's reference clip, 2026-11-05). They fade in from high combat speed and are fully there in cruise; the faster, the denser, longer and faster they pass. **Scale trick:** the streaks live in a cylinder around the flight axis at a fixed size around the camera (radius 24–170 m, from 120 m behind to 460 m ahead); the real speed only sets density, length and stream speed (a true field would jump kilometres per frame at 60 km/s). Nothing comes closer than 24 m to the axis, so the cockpit stays clear and cockpit parts occlude them like any object.

**API.** `new(parentFolder)`, `update(dt, position, look, velocity, air, pixelScale, view)`, `setMood(m)`, `setVisible(false)` (hide now, fade in again from 0), `destroy()`. **State.** per streak `xs, ys` (position across the axis), `zs` (centre along the axis in anchor space, ahead positive), `lengths` (1 ± 0.45 jitter), `on` (belongs to the current density), `opens` (per-streak fade-in), `cuts` (0 whole, 1 rear cut, 2 front cut), `dirty`, `width0/1`, `sequences`, `shown`; layer `active`, `fadeShown`, `up`, `scroll`, `lengthScale`, `density`, mood.

**Build** (`:178-269`): angle uniform in 0..2π, radius `InnerRadius 24 + (OuterRadius 170 − 24) · rnd^RadiusCurve 1`, `z ∈ [−BackDepth 120, FrontDepth 460]`; Beam per streak from `Rear` to `Front` attachment on one `StreakAnchor`, `FaceCamera`, `Segments 4`, `LightEmission 1`.

**Transparency profile.** `END_FADE = clamp(round(EndFade 0.25 · 4), 1, 1) / 4 = 0.25`; along the streak (u = 0 rear .. 1 front) at base transparency t: `T(u) = 1 − (1 − t) · clamp(min(u, 1 − u) / 0.25, 0, 1)` (linear ramps over the first and last quarter). Pre-built for 21 levels and, for streaks cut at the camera, 33 cut stages per end (`REAR[stage][level]` uses `T(cut + s(1 − cut))`, `FRONT` uses `T(s(1 − cut))`) so a clipped streak keeps exactly the gradient the uncut one would have there.

**Per frame** (`update`, `:301-522`):

```text
skip on NaN/inf input
mood = approachColor(mood, moodTarget, 1 - exp(-dt / 0.45))
fade = smoothstep((speed - FadeInSpeed 240) / max(FullSpeed 650 - 240, 1)) * (1 - air)
if fade <= 0.004: fadeShown = 0; hide all; active = false; return
fade = min(fade, fadeShown + dt / FadeTime 0.3); fadeShown = fade                 -- the whole layer fades in over >= 0.3 s (cruise start jumps 255 → 1440 m/s in one frame)
wasActive = active; active = true
dir = velocity / speed
up  = up - dir (up·dir); if |up| < 0.3: seed = |dir.y| < 0.9 ? Y : X; up = seed - dir (seed·dir)
up  = unit(up); right = dir × up                                                  -- continuous "up": no flip when turning
density = speed > 650 ? clamp(ln(speed / 650) / ln(TopSpeed 1e6 / 650), 0, 1) : 0  -- every doubling feels the same
stream  = MinStream 280 + (MaxStream 1000 - 280) * density                         (m/s inside the cylinder)
activeCount = Count * (MinActive 0.4 + 0.6 density) + 0.5                          -- compared as index <= activeCount
if not wasActive: on[i] = i <= activeCount for all                                -- density changes only at the invisible wrap otherwise
lengthScale target = MinLength 70 + (MaxLength 200 - 70) * density; relayout all ends only when it moved > 4 %
scroll += stream * dt; if scroll > 4096: zs -= scroll; scroll = 0; relayout       -- (32-bit precision in Roblox)
anchor = CFrame.fromMatrix(position - dir * scroll, right, up)                    -- anchor space: X right, Y up, -Z along dir
colour of every beam = mood when it changed > 0.012
per streak i:
    halfLength = lengths[i] * lengthScale / 2; ahead = zs[i] - scroll               -- centre distance ahead of the camera along dir
    while ahead + halfLength < -BackDepth 120: jump = FrontDepth 460 + 120 + halfLength; zs[i] += jump; ahead += jump; dirty; on[i] = i <= activeCount
    side = x (right·look) + y (up·look); depthRear = side + (ahead - halfLength)(dir·look); depthFront = side + (ahead + halfLength)(dir·look)
    open = 0
    if on[i] and (depthRear > MinDepth 2 or depthFront > 2) and not outside(rear, front):
        open = clamp((460 - ahead) / FrontFade 160, 0, 1) * clamp((ahead + halfLength + 120) / BackFade 90, 0, 1)
    open = min(open, opens[i] + dt / OpenTime 0.12); opens[i] = open                 -- a streak appearing beside the camera opens over 0.12 s
    visible = fade * open
    level = floor((1 - 0.5 * visible) * 20 + 0.5)                                  -- Transparency 0.5: at most half opaque in the middle
    level >= 20 → off
    else: if an end has depth < 2: move it along the streak to depth 2 (part = share cut), pick REAR/FRONT[round(part*32)][level]
          widths: Pixels 1.5 * pixelScale * max(depth, 2) at each end (written when > 15 % off); positions (x, y, -z) in anchor space
```

Numbers: 240 m/s → invisible; 445 m/s → 50 % layer (smoothstep), 24 streaks, 70 m long, 280 m/s stream; 650 m/s → full layer, 24 streaks; 1 km/s → density 0.059, 26 streaks, 78 m; 100 km/s → 0.686, 49 streaks, 159 m, 774 m/s stream; 1 Mm/s → 60 streaks, 200 m, 1000 m/s. Colours: neutral (205,214,230), approach (125,185,255), danger (255,115,100). Performance mode: `Count 30`.

### 3.12 ShipDistanceLines: distance lines (`ShipClient/ShipDistanceLines.lua`) · **switched off**

`DistanceLines.Enabled = false` since 2026-11-06 (AFloidDEV: "can you remove the side speedlines ... the ones that are straight. I think they dont really look good"); `ShipDust` does not even create them. Port: keep the config section and this description; implement only if the user turns them back on (question Q9).

**What they were.** Fine vertical dashes in a band in the ship's horizontal plane **through the eye**, standing along ship-up, appearing at the sides and gliding outwards; the flight direction keeps a free corridor. All dash centres lie on one straight line in the image and each dash looks equally tall (8°); depth shows only through motion. Spacing of the imagined dashes is `BaseSpacing 10 · TierFactor 20^tier` m (tier 1 = 200 m, 2 = 4 km, 3 = 80 km …, max 5); the tier changes when more than `RateUp 12` or fewer than `RateDown 0.45` spacings per second pass, with a 0.8 s cross-fade in which each dash dips at its own moment.

```text
band frame: up → ship up (Frame "SHIP"; "WORLD" = world up), smoothed with AlignTime 0.15 s, snapping when inactive or flipped (up·target < -0.3);
            forward = previous forward projected into the band (minimal rotation: yaw leaves the dashes world-fixed so the turn is felt)
in-plane speed: vx = v·right, vz = v·forward, speedFlat = sqrt(vx² + vz²), flat = speedFlat / speed
fade = smoothstep((speed - 240) / 410) * (1 - air) * smoothstep((flat - FlatMin 0.2) / (FlatFull 0.55 - 0.2)); fade <= 0.004 or speedFlat < 1 → off
fade = min(fade, fadeShown + dt / 0.3)
tier on activation = idealTier(speedFlat) = smallest tier in [1, 5] with speedFlat / spacing(tier) <= 12
tier change (no transition running): rate = speedFlat / spacing(tier); rise = d(speedFlat)/dt; wait one frame if dt = 0 or the rise
            more than doubled; if rate > 12 or rate < 0.45: ahead = rise > 0 ? min(speedFlat + min(rise, lastRise) * 0.8, speedFlat * 20) : speedFlat;
            target = idealTier(ahead); target ≠ tier → transition (two slots, each dash switches when transition passes its threshold ∈ [0.14, 0.86])
field: square 560 m centred ForwardShift 0.5 · 280 m ahead in the view direction; dashes scroll by min(speedFlat / spacing(slotTier), RateCap 15) · (560/√48) · dt along -dir and wrap
per dash: open = smoothstep((atan2(across, along) - 13°) / 6°) * clamp((distance - 34) / 26) * clamp((280 - |p - centre|) / (0.3 · 280)), rate-limited by dt / 0.12
          length = angleScale 2 tan(4°) * distance (equal apparent height); alpha during a transition = min(|transition - threshold| / 0.14, 1)
          visible = fade * alpha * open; level = floor((1 - 0.75 visible) * 20 + 0.5); width 1.4 px at depth
colours: neutral (238,243,255), approach (90,175,255), danger (255,70,55); 4-segment beams with soft tips (tip transparency t + 0.75 (1 - t))
```

### 3.13 ShipSpeedLines: screen-space speed lines (`ShipClient/ShipSpeedLines.lua`) · **never shown**

Built at start (`ShipClient.lua:284`) but replaced by the dust beams on 2026-11-05: ShipClient calls `speedLines:setVisible(false)` every frame and never `update`. Port: do not implement. For the record: ScreenGui `ShipSpeedLinesGui` (DisplayOrder 40), 2 × 16 ticks (Frames, AnchorPoint centre) that run from a vanishing point at `(0.5, 0.5)` + `VerticalOffset −40` px outwards: `distance = InnerGap 120 · (OuterEdge 1000 / 120)^p`, `growth = p^1.6`, height `5 + 41·growth` px, thickness `max(1 + 3·growth, 1)`, `y = −40 + Curvature(−110)·growth`, phase speed `BaseFlow 1.15 · (MinFlow 0.12 + 0.88 · fraction^0.75)` per second (`fraction = speed / maxSpeed`), fade in over the first 10 % and out over the last 22 % of the path, transparency `0.8 − 0.55·fraction`, hidden below 40 m/s, mood colours as the dust.

---

## 4. Tunables

Column **S** marks how a value behaves under the world scale: **abs** = absolute (ship-local, screen or time), **×S** = scale with the world, **cap** = follows the chosen cruise cap (`02-flight.md` Q1).

### 4.1 `ShipConfig.Hud` (`ShipConfig.lua:1732-1739`)

| Key | Value | Meaning |
|---|---|---|
| `ShowThrottleBar` | false | ShipCursor's own throttle track; ShipHud took over (`02-flight.md` 3.9) |
| `ShowDebugText` | false | ShipCursor's debug text; ShipHud took over |
| `TextUpdateInterval` | 0.1 s | HUD texts and bars at most 10× per second (RichText every frame costs noticeably; 10 Hz reads the same) |

### 4.2 `ShipConfig.Ui` (`:2017-2055`)

| Key | Value | Meaning |
|---|---|---|
| `ReferenceHeight` | 1080 | design height of all runtime UIs |
| `MinScale` / `MaxScale` | 0.75 / 2.2 | clamp of `viewport.y / 1080` |
| `PanelColor` | (206,226,240) | HUD text |
| `DimColor` | (120,145,165) | captions |
| `AccentColor` | (255,170,60) | prompt, autopilot, lock |
| `GoodColor` | (120,220,150) | FA ON, best turn, heat bar < 100 % |
| `WarnColor` | (255,165,70) | FA OFF, heat 100–150 % |
| `DangerColor` | (255,70,60) | BLOCKED, heat ≥ 150 %, low fuel |
| `Fonts.Body` / `BodyBold` | Titillium Web SemiBold / Bold | text |
| `Fonts.Ui` / `UiBold` | Sarpanch SemiBold / Bold | prompt, warnings, titles |
| `Fonts.Mono` | Roboto Mono Medium | numbers in columns |
| `PromptPlateColor` | (6,10,16) | prompt plate |
| `PromptPlateTransparency` | 0.3 | plate 70 % opaque |
| `PromptStrokeTransparency` | 0.35 | plate edge in the text colour, 65 % |
| `OpenTime` / `OpenRise` / `OpenVeil` / `OpenVeilColor` | 0.22 s / 14 px / 0.35 / (6,10,16) | `animateOpen` (full-screen panels) |

### 4.3 `ShipConfig.Cockpit` (`:1948-2009`)

| Key | Value | S | Meaning |
|---|---|---|---|
| `ImagePath` | `{}` | | no painted cockpit image (removed 2026-11-02); the motion drives the head |
| `AutoSetup` / `Overscale` / `MarginSafety` | true / 1.25 / 0.85 | | image placement and usable margin (image path only) |
| `SwayPerLateralAccel` | 1.2 px per m/s² | abs | lateral G offset |
| `SwayPerVerticalAccel` | 1.2 px per m/s² | abs | vertical G offset |
| `SwayPerForwardAccel` | 0.9 px per m/s² | abs | forward G: view sags |
| `SwayPerYaw` / `SwayPerPitch` | 0.35 px per °/s | abs | lag on turns |
| `EnableTilt` / `TiltPerRoll` / `MaxTilt` | true / 0.030 ° per °/s / 3° | abs | roll tilt |
| `TiltWriteThreshold` | 0.05° | | image write threshold (image only) |
| `MaxOffset` | 55 px | abs | hard limit (head: 55 × 0.002 = 0.11 m) |
| `SwayResponseTime` | 0.35 s | abs | smoothing of sway, tilt and surge |
| `Vibration.Idle` / `PerSpeed` / `PerAccel` | 0.25 / 0.85 / 0.60 | | intensity terms |
| `Vibration.AccelReference` | 25 m/s² | abs | forward acceleration for full `PerAccel` |
| `Vibration.Amplitude` / `TiltAmplitude` | 7.0 px / 0.35° | | at intensity 1 |
| `Vibration.SlowFrequency` / `SlowWeight` | 1.7 / 0.65 | | slow rumble octave |
| `Vibration.FastFrequency` / `FastWeight` | 13.0 / 0.35 | | fast tremble octave |
| `CameraShake.Scale` / `MaxDegrees` / `SpeedFactor` | 1.0 / 0.30° / 1.4 | | camera shake ("more feels like an earthquake") |

### 4.4 `ShipConfig.Interior` (`:2856-2898`)

| Key | Value | S | Meaning |
|---|---|---|---|
| `LookYaw` / `LookUp` / `LookDown` | 140° / 80° / 70° | | look-around limits in the cockpit |
| `LookSensitivity` | 0.25 ° per px × mouse sensitivity | | look speed |
| `LookReturnTime` | 0.12 s | | return to forward on release |
| `ExternalLookYaw/Up/Down` | 180° / 60° / 60° | | orbit limits in the external view |
| `NeckOffset` | (0, −0.35, 0.3) m | abs | head pivot relative to the eye |
| `HeadPerSwayPixel` | 0.002 m/px | abs | sway → head translation |
| `HeadSurgePerAccel` / `HeadMaxSurge` | 0.004 m per m/s² / 0.12 m | abs | head pressed into the seat |
| `HeadRollFactor` | 0.6 | | share of the tilt applied as head roll |
| `ReachDistance` | 7 m | abs | button raycast length |
| `PressDepth` / `PressTime` | 0.03 m / 0.14 s | abs | button cap travel |
| `RadarRangeMin` | 300 m | abs | inner ring of the log scale |
| `RadarRangeMax` | 1,000,000 m | **×S?** | rim; nothing farther is shown (Q8) |
| `RadarSweepSpeed` | 120 °/s | | sweep (writes capped at 30/s) |
| `ScreenInterval` | 0.1 s | | screens, holos, lamps, lights refresh |
| `BootTime` | 2.6 s | | power-up |
| `HintTime` | 12 s | | control hint after the first power-up |

### 4.5 `ShipConfig.Hull` (`:2614-2646`)

| Key | Value | Meaning |
|---|---|---|
| `Model` | "Varrow" | active ship model (changed by the shipyard) |
| `GearDownAltitude` | 60 m | gear down below this altitude in the landing scene (`02-flight.md`) |
| `GearLegTime` / `GearDoorTime` | 1.6 s / 0.6 s | gear animation phases |
| `Length` / `Width` / `Height` / `StrutWidth` / `WingThickness` / `Dihedral` / `FinThickness` / `EngineRadius` | 54 / 30 / 7.5 / 1.5 / 1.1 / 7° / 0.9 / 2.6 | box hull only |
| `BodyColour` / `TrimColour` / `DarkColour` / `GlassColour` / `GlassTransparency` / `ExhaustColour` | (126,156,168) / (74,96,108) / (42,52,60) / (150,200,220) / 0.72 / (120,190,255) | box hull only |
| `InteriorHideAngle` | 62° | box hull: hide parts this close to the view axis from inside |

### 4.6 `ShipConfig.Markers` (`:1542-1573`)

| Key | Value | S | Meaning |
|---|---|---|---|
| `MarkerSize` | 150 px | | billboard width (locked: +30, height 96 → 110) |
| `RingSize` / `RingThickness` / `RingTransparency` | 22 px / 1.5 px / 0.3 | | target ring |
| `FocusRadius` | 0.14 × screen height | | focus = nearest marker to the centre within this |
| `LockSound` / `LockVolume` | rbxassetid://10128760939 / 0.45 | | lock tone on T (same as the system map) |
| `MaxTextDistance` | 0 | | 0 = no draw limit |
| `Color` / `LockedColor` | (150,200,230) / (255,170,60) | | ring and label |
| `UpdateInterval` | 0.1 s | | ordering, labels, declutter, panel |
| `TextRange` | 2,000,000 m | **×S** | moons and stations named only within this surface distance |
| `PanelColor` | (210,230,240) | | target panel text |
| `DangerColor` / `ApproachColor` | (230,70,60) / (90,170,255) | | helper rings around bodies: **not read anywhere** (feature removed) |

### 4.7 `ShipConfig.Dust` (`:1318-1372`)

| Key | Value | S | Meaning |
|---|---|---|---|
| `Count` / `AirCount` | 90 / 70 (perf 45 / 35) | | motes in space / in air |
| `FieldSize` | 260 m | abs | cube edge around the camera |
| `MinSpeed` / `SlowFade` | 1.5 m/s / 4 m/s | abs | fade in from 1.5 to 5.5 m/s |
| `CruiseFadeStart` / `CruiseFadeFactor` / `AirCruiseFadeFactor` | 0.6 / 2 / 3 × `Thrust.MaxSpeed` (1000) | abs | fade out 600 → 2000 m/s (air 1000 → 3000) |
| `FadeTime` | 0.3 s | | max layer fade rate |
| `NearClip` / `NearFade` | 7 m / 6 m | abs | no strokes within 7 m, full from 13 m |
| `EdgeFade` | 0.3 | | outer share of the half edge where motes fade |
| `ForwardShift` | 0.6 | | cube centre ahead by 0.6 × half edge (space only) |
| `RelativeSpeed` | 1.35 | | flow exaggeration |
| `HeadPixels` / `TailPixels` | 1.8 / 0.6 px at 1080 lines | | width by depth (space) |
| `WidthTolerance` | 0.15 | | rewrite threshold (Roblox only) |
| `AirHeadWidth` / `AirTailWidth` | 0.07 m / 0 | abs | fixed widths in air |
| `MinLength` / `MaxLength` / `AirMaxLength` | 0.6 / 26 / 40 m | abs | stroke length |
| `LengthCurve` | 0.8 | | `<1` = long already at medium speed |
| `MinTransparency` / `MaxTransparency` | 0.2 / 0.6 | | head at full / crawl speed |
| `AirMinTransparency` / `AirMaxTransparency` | 0.5 / 0.88 | | air streaks paler |
| `MoodNeutral` / `MoodApproach` / `MoodDanger` | (225,238,252) / (90,175,255) / (255,70,55) | | colours |
| `MoodFade` / `ModeFade` | 0.45 s / 0.6 s | | colour and space↔air cross-fades |
| `Color` | (235,245,255) | | unused default |
| `StyleInterval` | 0.08 s | | unused (older size throttle) |

### 4.8 `ShipConfig.SpeedStreaks` (`:1386-1425`)

| Key | Value | S | Meaning |
|---|---|---|---|
| `Count` | 60 (perf 30) | | beams |
| `FadeInSpeed` / `FullSpeed` | 240 / 650 m/s | abs | layer fade (smoothstep) |
| `FadeTime` / `OpenTime` | 0.3 s / 0.12 s | | layer and per-streak fade-in limits |
| `TopSpeed` | 1,000,000 m/s | **cap** | densest and longest here |
| `MinActive` | 0.4 | | share of streaks at FullSpeed |
| `InnerRadius` / `OuterRadius` / `RadiusCurve` | 24 / 170 m / 1 | abs | cylinder around the flight axis |
| `FrontDepth` / `BackDepth` | 460 / 120 m | abs | spawn ahead / reach behind |
| `BackFade` / `FrontFade` | 90 / 160 m | abs | fade at the back and front |
| `MinLength` / `MaxLength` / `LengthJitter` | 70 / 200 m / 0.45 | abs | streak length |
| `MinStream` / `MaxStream` | 280 / 1000 m/s | abs | pass-by speed in the cylinder |
| `Pixels` / `MinDepth` / `WidthTolerance` | 1.5 px / 2 m / 0.15 | | width; near clip |
| `Transparency` / `EndFade` / `Segments` | 0.5 / 0.25 / 4 | | middle transparency; soft ends (whole segments) |
| `MoodNeutral` / `MoodApproach` / `MoodDanger` / `MoodFade` | (205,214,230) / (125,185,255) / (255,115,100) / 0.45 s | | colours |

### 4.9 `ShipConfig.DistanceLines` (`:1447-1503`) · off

`Enabled false`, `Count 48` (perf 24), `FadeInSpeed 240`, `FullSpeed 650`, `FadeTime 0.3`, `Frame "SHIP"`, `AlignTime 0.15`, `FlatMin 0.2`, `FlatFull 0.55`, `FieldSize 560`, `NearRadius 34`, `NearFade 26`, `EdgeFade 0.3`, `ForwardShift 0.5`, `Angle 8°`, `LengthJitter 0`, `BandJitter 0`, `CorridorAngle 13°`, `CorridorFade 6°`, `OpenTime 0.12`, `BaseSpacing 10 m`, `TierFactor 20`, `MinTier 1`, `MaxTier 5`, `RateUp 12`, `RateDown 0.45`, `RateCap 15`, `TierFade 0.8 s`, `DipWidth 0.14`, `Pixels 1.4`, `MinDepth 2`, `WidthTolerance 0.15`, `MinTransparency 0.25`, moods (238,243,255) / (90,175,255) / (255,70,55), `MoodFade 0.45`. Module defaults for missing keys (`:103-114`): Frame SHIP, AlignTime 0.15, Angle 8, jitters 0, corridor 13/6, OpenTime 0.12, RateCap 15, FadeTime 0.3.

### 4.10 `ShipConfig.SpeedLines` (`:1054-1089`) · never shown

`CountPerSide 16`, `MinSpeed 40`, `InnerGap 120`, `OuterEdge 1000`, `InnerHeight 5`, `OuterHeight 46`, `InnerThickness 1`, `OuterThickness 4`, `SizeCurve 1.6`, `VerticalOffset −40`, `Curvature −110`, `FlowOutward true`, `BaseFlow 1.15`, `MinFlow 0.12`, `FlowCurve 0.75`, `FadeInPortion 0.10`, `FadeOutPortion 0.22`, `MinTransparency 0.25`, `MaxTransparency 0.8`, moods as dust, `MoodFade 0.45`, `Color (225,238,252)`.

### 4.11 `ShipConfig.Exhaust` (`:1510-1537`)

| Key | Value | Meaning (lengths and sizes in nozzle diameters d) |
|---|---|---|
| `StartOffset` | 0.1 | emitter just behind the glow disc |
| `IdleLength` / `FullLength` / `BoostLength` | 0.4 / 3.4 / ×1.6 | flame length idle / full / boost factor |
| `CoreWhite` | 0.6 | core starts 60 % towards white |
| `CoreSize` / `CoreTaper` | 0.95 / 0.3 | core sprite size, end size relative |
| `CoreTransparency` / `CoreLife` | 0.1 / 0.14 s | |
| `CoreRate` / `CoreRateIdle` | 110 / 35 per s (perf 50 / 15) | particles per second at full / idle |
| `VeilSize` / `VeilTaper` / `VeilTransparency` / `VeilLife` / `VeilLength` | 1.5 / 0.55 / 0.72 / 0.22 s / 1.5 | veil reaches 1.5 × the core length |
| `VeilRate` / `VeilThreshold` | 45 per s (perf 0) / 0.05 | no veil below 5 % throttle |
| `Brightness` / `BoostBrightness` | 2.2 / 4 | emitter brightness |
| `TrafficRateScale` | 0.5 | NPC traffic: half rate, no veil |

### 4.12 Constants inside the modules

| Module | Constant | Value |
|---|---|---|
| ShipInterior `:45-50` | `ACTION_KEYS` | Jump → ConfirmJump, FlightAssist → ToggleFlightAssist, Music → ToggleMusic, Land → {Land, Dock} |
| ShipInterior `:76-91` | `C` (cockpit palette) | amber (255,138,31), amberDim (120,62,16), bright (255,214,160), cyan (59,214,255), text (242,238,232), holoText (210,245,255), muted (150,110,70), good (120,220,150), danger (255,70,60), reverse (200,90,60), star (255,214,120), ledOff (40,30,20), iconOff (92,58,26), stripOff (58,60,64) |
| ShipInterior `:109-114` | `LAMP` | off (24,18,12)/(70,52,34), on (8,34,44)/cyan, caution (58,30,8)/amber, warning (66,12,8)/danger |
| ShipInterior `:117-122` | `CHIP` | off (22,16,10)/amberDim/muted, ready (22,16,10)/amberDim/amber, on (8,30,40)/cyan/cyan, caution (44,24,8)/amber/amber |
| ShipInterior `:125-128` | `HOLO_LABELS` | left BODY, ALTITUDE, GRAVITY, PITCH · right MASS, AGILITY, BOOST, STATUS |
| ShipInterior `:131` | `BOOT_LINES` | POWER BUS, SENSOR ARRAY, FLIGHT CONTROL, NAV COMPUTER, ALL SYSTEMS GO |
| ShipInterior | lever smoothing / write threshold | τ 0.07 s / 0.15° |
| ShipInterior | lever angles | throttle 14° − 46°·f; stick ±16° pitch/roll tilt, ±14° twist |
| ShipInterior | needle | −135° + 270°·f, write threshold 0.4° |
| ShipInterior | radar | 12 blips; radius 0.14–0.94 × 0.62 m; height clamp ±0.32 m; sizes 0.075 / 0.055 / 0.04 m; giant threshold radius 2500 m (**×S**); stem 0.008 m, 35 % opaque; sweep writes 30 Hz |
| ShipInterior | lamp blink | `clock % 0.8 < 0.5` |
| ShipInterior | lights | quantised to 1/20; strip/GUI on above level 0.4 |
| ShipInterior | pointer | ring 22 px → 28 px on hover, stroke transparency 0.45 → 0; tooltip −24 px above centre or −18 px above the mouse |
| ShipMarkers `:46-54` | `LABEL_OFFSET` / `LABEL_HEIGHT` / `LABEL_GAP` | 14 / 32 / 8 px |
| ShipMarkers | `LOCK_ARM` / `LOCK_PAD` / `LOCK_TWEEN` | 8 px / 8 px / 0.22 s Quad Out, scale 1.8 → 1 |
| ShipMarkers | panel | AnchorPoint (1,1), 28 px from the corner, 340 × 120 px, Roboto Mono 15 |
| ShipMarkers | ETA | closing > 0.05 m/s; "--:--:--" beyond 99 h |
| ShipOverlay `:40-46` | `BASE_DISTANCE` / `SPREAD` / step | 1 m / 0.05 / `Camera + 10` |
| ShipNotices `:23-33` | `DESIGN` / `COMMS_SECONDS` / `TOAST_SECONDS` | 1920 × 1080 / 6 s / 4 s; default colours (255,170,60), (230,236,240), (140,150,160), (120,220,150), (255,90,80) |
| ShipDust `:95-117` | `LEVELS` / `SEGMENTS` / `REFERENCE_HEIGHT` / `RECENTER` / `VIEW_MARGIN` / `VIEW_PAD` / `MIN_ASPECT` | 16 / 2 / 1080 / 4096 m / 1.1 / 1.5 m / 16:9 |
| ShipSpeedStreaks `:102-145` | `LEVELS` / `SEGMENTS` / `END_FADE` / `CLIP_STAGES` / `RECENTER` | 20 / 4 / 0.25 / 32 / 4096 m |
| ShipDistanceLines `:127-128` | `LEVELS` / `SEGMENTS` | 20 / 4 |
| ShipExhaust `:29-30` | `GLOW` | `rbxasset://textures/glow.png` (Roblox built-in soft sprite) |
| ShipHull `:646`, `:846` | `EXTRA_CLASSES` / `LOOSE_COLLISION_SIZE` | LayerCollector, Light, ParticleEmitter, Beam, Trail, Fire, Smoke, Sparkles, Highlight / 8 m |
| ShipHull | engine light | brightness × (0.35 + 1.15 g) × lift, range × (0.7 + 0.6 g) × lift, lift 1.35 boosting; disc transparency 0.5 (1 − g); write threshold 0.01 |
| ShipUi | `text()` minimum | 8 px |

### 4.13 Values from other sections this area reads

| Value | Where | Used for |
|---|---|---|
| `Physics.BlackoutStartG 6.5`, `BlackoutFullG 11`, `BlackoutMaxDarkness 0.82`, `BlackoutResponse 0.35`, `RedoutColor (110,0,0)` | `:2440-2457` | blackout; G-FORCE colour thresholds 3.9 / 6.5 |
| `Warnings.Phase1Time 10 s` | `:545` | danger mood, status line, WARNING lamp |
| `Warnings.Overheat.Phase1Heat 0.8`, `FatalHeat 2.0` | `:572-574` | heat colours, marks, lamps, mood |
| `Thrust.MaxSpeed 1000`, `MinThrottle −0.25`, `CruiseMaxSpeed 3,000,000` | `:31`, `:59`, `:50` | speed fraction (vibration, dust), lever/bar zero, speed gauge range (**cap**) |
| `Camera.SeatOffset (0,0.5,0)`, `FieldOfView 80`, `External.Offset (0,16,95)`, `Pitch −8` | `:2651-2663` | eye, FOV default, external view |
| `Keys.CycleTarget T`, `ClearTarget Y` (+ all keys shown on buttons) | `:2763-2842` | lock keys, tooltips |
| `Gravity.Tag "CelestialBody"`, `Stations.Tag "ShipStation"`, `Pilots.Tag "ShipPilot"`, `Pilots.NameRange 50,000` | `:449`, `:2907`, `:1581-1593` | marker sources |
| `Ships[class].DisplayName` | `:89-339` | ship screen name (VARROW, BRANNOCK, MORVEN, KESSOCK, LOCHRAN, CALDER, TORRIN, DORNOCH) |
| `Performance.Low` | `:2731-2748` | Dust 45/35, SpeedStreaks 30, DistanceLines 24, Exhaust 50/15/0 |
| `Performance.DetailCull 0.6`, `DetailGroups` | `:2727-2728` | hull detail culling |
| `Aboard.GlassClear` | `ShipConfig.lua:3033+` | glass when walking aboard |
| Settings `CameraShake` (0–1, default 1), `FieldOfView` (60–100, default 80), `EffectsVolume` | `ShipSettings.lua` | motion, FOV, button sounds |

---

## 5. State machines, timings, thresholds and edge cases

### 5.1 Cockpit power

```text
DARK ──boot()──▶ BOOTING (0..2.6 s, timeline in 3.4.2) ──t ≥ 2.6──▶ POWERED ──reboot()──▶ DARK ──▶ BOOTING ...
```

- `boot()` is called once the menu fade starts (`ShipClient.lua:3249-3263`), after a ship switch (`:1133`) and via `reboot()` after every crash (`:1434`). A second `boot()` while booting does nothing (`bootStart ~= nil`).
- DARK: pointer, presses, levers and button LEDs/faces still update every frame (LEDs off and faces `iconOff`, because `on` requires `powered`); screens show the boot frame with an empty status; holos and the projection glass are off; lamps off; needles at −135°; lights at 0.
- BOOTING: no 10 Hz screen updates, no gauge needles from data (the sweep animates them), radar sweep hidden (`Sweep.Visible = powered`), no blips; the projection glass and the attitude indicator follow `hudOn` (flicker with the holos).
- POWERED: everything; the 12 s hint runs once per session (`hintShown`).

### 5.2 Pointer, hover and press

```text
pointer mode = nil | "center" (looking around) | "mouse" (free mouse)      -- forced nil when unpowered, in the external view, over maps, on foot
hover: none ⇄ button (ray from centre or mouse, 7 m, caps only); entering a button plays Hover once and fills the tooltip
press: click with hover → press timer 0.14 s (cap 3 cm in over 42 ms, back over 98 ms), Press sound, action fired immediately
```

The action fires on mouse **down**; there is no repeat and no long press. A button pressed while unpowered is impossible (no hover when unpowered), but `pressAction` works anytime (tests).

### 5.3 What is visible in which view

| Element | Cockpit (interior active) | External view | Notes |
|---|---|---|---|
| Canopy cage, old shell, `SeatHidden` groups (HideInside) | hidden | shown | |
| Walkable interior behind the bridge (HideSeated) | hidden | shown | walking aboard: shown |
| `CockpitPlus` (pilot, struts, pods, walls, overhead; CockpitOnly) | shown | hidden | walking aboard: hidden |
| Rest of the cockpit (screens, buttons, radar, levers, lamps) | shown | shown | its SurfaceGuis stay enabled outside |
| Stowed gear / closed belly covers | per gear state | per gear state | seated: the gear moves instantly |
| Flat HUD stack (bars + readout) | hidden | shown | `hud:setCockpitMode` |
| Target panel | hidden | shown | `markers:setPanelVisible` |
| Prompt line | at 64 % height | at H − 150 px | |
| Autopilot label, blackout, markers, notices | shown | shown | |
| Projection glass (flight arrow on the glass) | shown when powered | hidden; flat arrow shown | `02-flight.md` |
| Dust / streaks | around the camera | around the camera | the field is centred on the camera, not the ship (`ShipClient.lua:2961-2964`) |
| Exhaust | hidden parts' emitters off | shown | |
| CockpitInteract (reticle, tooltip, hint) | shown | hint hidden, no pointer | |

### 5.4 Flight UI per game state

| State | Markers | Dust/streaks | HUD gui | Cockpit | Notes |
|---|---|---|---|---|---|
| Flying | on | on | on | full update | |
| Landing scene (Roblox) | off (`:508`) | air mode | on | radar without blips, status SURFACE | the port has no scene (9.5) |
| Docked in flight space | on | on (speed ~0 → invisible) | on | parked state (2.2) | cursor hidden |
| Station decks (walking) | off | off | off | `interior.gui` off while walking | `hideSpace` |
| Walking aboard | on | off (`:2971-2972`) | on | not updated | camera by ShipAboard |
| Destruction cinematic | off | off | off (every ScreenGui except warnings and `ShipDestroyed`) | — | `setFlightUiHidden(true)` each frame |
| Respawn | on | on | restored | reboot | |
| System/star map open | on (under the map) | on | on | pointer nil | maps are exclusive screens |

### 5.5 Target lock

```text
NONE ──T──▶ LOCKED(ordered[1]) ──T──▶ LOCKED(next, wrapping) ...
LOCKED ──Y / system map click on another body / map lock──▶ NONE / LOCKED(that)
LOCKED ──target removed (CollectionService) or no longer in the world at the 10 Hz update──▶ NONE
```

- `ordered` is rebuilt every 0.1 s by **centre** distance; pressing T cycles in the order of the last rebuild. A target the list no longer contains restarts at the nearest.
- The lock tone plays on every T press that has something to cycle (not on Y, not on map locks).
- The bracket animation runs only when the target **changes** (re-locking the same part does not replay it).
- Locking does not depend on what is on screen (behind the ship is fine); the radar and the nav screen show the locked target first.

### 5.6 Marker label rules (every 0.1 s)

| Marker | Name | Distance |
|---|---|---|
| Locked | always (orange) | surface distance |
| Focused (nearest to the centre within 0.14 H) | always | surface distance |
| Star, planets (`alwaysNamed`) | always | no |
| Moons, stations | only within `TextRange` (2,000 km surface distance; ×S) | no |
| Other pilots (multiplayer) | friends always, others within 50 km | centre distance |

Then declutter: priority locked > (pilots by `MarkerPriority`) > bigger `SurfaceRadius` > stations (0); ties by distance; 8 px gap; solid signs first. Rings are never hidden by the declutter.

### 5.7 Speed effect thresholds (absolute speeds)

| Speed (m/s) | Dust | Streaks |
|---|---|---|
| < 1.5 | off | off |
| 1.5–5.5 | fades in | off |
| 5.5–240 | on, longer and brighter with speed | off |
| 240–650 | on | layer fades in (smoothstep), 24 streaks |
| 600–2000 | fades out (air: 1000–3000) | full layer, density 0 below 650 |
| > 2000 | off | density `ln(v/650)/ln(1e6/650)`: more (24 → 60), longer (70 → 200 m), faster (280 → 1000 m/s) |
| ≥ 1,000,000 | off | saturated |

Both layers fade **in** at most over 0.3 s (a cruise start jumps from ~255 to ~1440 m/s in one frame) and go **out** at once (streaks) or at the same 0.3 s rate (dust). Air mode removes the streaks (`× (1 − mix)`) and turns dust into air streaks over 0.6 s. Mood colour changes over 0.45 s even while invisible.

### 5.8 Notices timing

Comms 6 s (some callers pass other durations), toast 4 s (crash 7 s, landing arrival 6 s, deck messages 7–8 s), measured with `os.clock()` (wall clock: keeps running while paused). A new comms/toast replaces the current one immediately (no queue). Docking aid and survey bar have no timer: their owners pass `nil` to hide them.

### 5.9 Edge cases worth a test

- `ShipHud`: `state.speedLimit` may be `math.huge` (no limit) → no limit text; `altitude` ∞ → "--"; `surfaceGravityG` 0 (deep space) → GRAVITY "--"; `heat > 2` → bar full but value "250%"; `fuel < 0.2` turns the value red but the bar stays blue.
- Prompt lines are RichText-escaped (station names come from data); a prompt with one BLOCKED line colours only that line red but the stroke red.
- `ShipInterior.updateShip`: `limit ~= limit` (NaN) is skipped (`limit == limit` check); speed gauge uses the **shown** speed (atmosphere blending) — in the port the real speed.
- Radar: the locked body is always blip #1 even if farther than nearer bodies; a locked body beyond 1,000 km is **not** shown (range check applies to it too).
- Nav list: rows come from `markers:getOrdered()` and therefore include **stations**; the locked one is skipped, so the list shows the next three.
- Marker labels behind the camera are never hidden by the declutter (they cannot overlap anything visible).
- `formatDuration` hides absurd values (standing still → ∞ → "--:--:--").
- `ShipDust`/`ShipSpeedStreaks`/`ShipDistanceLines` skip frames with NaN/∞ input instead of propagating NaN into persistent state.
- Dust `direction` keeps the last value below 1 mm/s so a fading layer does not snap.
- Streak `up` is carried over continuously; when it becomes parallel to the flight direction (|up⊥| < 0.3) it is re-seeded from world Y (or X when flying vertically).
- Dust `layer` rises by `dt/0.3` only after the visibility test; at extreme frame rates (≥ 830 fps) a pre-test cap would never pass the 0.004 threshold (`ShipDistanceLines.lua:50-53`).
- Hull: a gear command reversing mid-animation continues from the current pose; seated in the cockpit the gear jumps to the end pose.
- Exhaust is disabled and cleared for hidden parts (external ↔ cockpit switch), or the flame would stay at the old place.
- Ship switch: `interior:destroy()` returns the button Highlight to the GUI so the next cockpit finds it.

---

## 6. On screen

### 6.1 Palettes

| Palette | Colours |
|---|---|
| Flat HUD (`Ui`) | Panel (206,226,240) · Dim (120,145,165) · Accent (255,170,60) · Good (120,220,150) · Warn (255,165,70) · Danger (255,70,60) · plate (6,10,16) · bar track (18,26,34) with stroke (70,95,115) · fuel fill (90,190,235) · heat mark (255,200,120) · BOOST (120,200,255) |
| Cockpit (`ShipInterior.C`, the CockpitBuilder's and main menu's palette) | amber (255,138,31) · amberDim (120,62,16) · bright (255,214,160) · cyan (59,214,255) · text (242,238,232) · holoText (210,245,255) · muted (150,110,70) · good (120,220,150) · danger (255,70,60) · reverse (200,90,60) · star (255,214,120) · screen background (7,9,11) · track (28,22,16) · chip/lamp backgrounds in 4.12 |
| Markers | ring/label (150,200,230) · locked (255,170,60) · target panel (210,230,240) |
| Flight effects | dust (225,238,252)/(90,175,255)/(255,70,55) · streaks (205,214,230)/(125,185,255)/(255,115,100) |
| Notices (defaults) | Accent (255,170,60) · Ink (230,236,240) · Muted (140,150,160) · Good (120,220,150) · Bad (255,90,80) |

The current Kotlin `Theme.ACCENT` is cyan (0xFF4CD6FF); this area uses the Roblox oranges above (the cockpit's own cyan is (59,214,255)).

### 6.2 Fonts

| Roblox | Used for | Kotlin atlas face |
|---|---|---|
| Titillium Web SemiBold / Bold | captions, marker names, holo labels, chip texts, button tags, list names | `FontData.UI` (SemiBold) |
| Sarpanch SemiBold / Bold | prompt, autopilot, screen titles, drive mode, holo values, footer | `FontData.TITLE` (Bold only) |
| Roboto Mono Medium | readout, values, distances, target panel, gauge values | `FontData.MONO` |
| **Michroma** Regular | big cockpit numbers: target name and distance (nav), speed (ship), decorative "B-2" | **missing**: add to `tools/gen_font.py` (SIL OFL) or substitute Sarpanch for the RTT screens |

Text sizes inside SurfaceGuis are canvas px (6.4); on the flat HUD design px × `ShipUi` scale.

### 6.3 Flat HUD at 1080 lines (Roblox layout)

```text
 ┌──────────────────────────────────────────────────────────────────────────────────────────────┐
 │                           AUTOPILOT  CRUISING        ← centre, top 96 px, Sarpanch 17, accent    │
 │                                                                                                  │
 │      (markers: ○ ring 22 px, NAME below in Titillium 14, distance in Roboto Mono 12 when focused)  │
 │                                                                                                  │
 │                         ┌───────────────────────────────┐                                         │
 │                         │ K   REQUEST DOCKING · MERIDIAN │  ← prompt; cockpit: bottom at 64 % H    │
 │                         └───────────────────────────────┘    external: bottom at H − 150 px       │
 │ FUEL  (SCOOPING)      87%                                                  TARGET LOCKED (accent)│
 │ ████████████████░░░░                                                                        LUNA│
 │ HEAT                  64%                                                                   Moon│
 │ ██████░░░░░░░|░░░░░░  (mark at 100 % = half)                         DISTANCE        4.48 km     │
 │ THROTTLE 100%                                                         ETA            00:01:23     │
 │ SPEED    53.0 km/s  (approach · max 85.0 km/s)                    ← 340 × 120 px, 28 px from the │
 │ DRIVE    CRUISE                                                      bottom-right corner (external)│
 │ ... 11 lines, Roboto Mono 15 ...                                                                  │
 │ F-ASSIST ON          ← stack 400 px wide, 26 px from left and bottom (external view only)         │
 └──────────────────────────────────────────────────────────────────────────────────────────────┘
```

Blackout: full-screen black (redout: towards (110,0,0)) up to 82 % opacity, drawn over the markers and the HUD texts but under the flight arrow (DisplayOrder 50), the warp HUD (70) and the warnings (100). ScreenGui order: speed lines 40 < target GUI 45 < HUD 46 < cursor 50 < warp 70 < warnings 100; FlightNotices and CockpitInteract are StarterGui GUIs with unknown orders.

**Marker anatomy** (billboard 150 × 96 px, centre on the target): ring 22 px Ø, 1.5 px stroke at 70 % opacity; label box full width × 32 px whose top is 14 px below the ring centre; name upper case Titillium SemiBold 14 with a 40 % black outline; optional second line Roboto Mono 12 at 85 % opacity. Locked: orange, billboard 180 × 110, four L-brackets (8 px arms, 2 px thick) on a 38 × 38 px square around the ring, scaling from 1.8× to 1× in 0.22 s (Quad Out).

### 6.4 The 3D cockpit (decoded from `Assets.rbxmx`)

All eight ships carry the **same layout** (identical positions and sizes; only decorative part counts differ: VARROW/BRANNOCK/DORNOCH 71 SurfaceGuis, MORVEN/KESSOCK/LOCHRAN 67, TORRIN 61, CALDER 56). Cockpit attributes: `Interior true`, `MoodTone "ultra2-2026-11-07-mood"`; model attribute `CockpitCenterFromRoot (0, 0.2, 1)`, `CockpitSize (12.8, 7.2, 14)`.

#### 6.4.1 Geometry (eye space, m; "front" = the face normal towards the pilot; angles from the eye, azimuth + right, elevation + up)

| Element | Centre | Size | Front normal | Canvas (px/m) | Brightness / ZOffset | Azimuth / elevation span |
|---|---|---|---|---|---|---|
| `Screens.NavScreen` | (−1.975, −1.657, −2.929) | 1.22 × 0.90 × 0.03, colour (4,6,8) SmoothPlastic | (0.471, 0.540, 0.698) | 732 × 540 (600) | 1.3 / 0 | −44.5..−23.5° / −31.3..−18.4° |
| `Screens.ShipScreen` | (1.975, −1.657, −2.929) | same | (−0.471, 0.540, 0.698) | 732 × 540 | 1.3 / 0 | 23.5..44.5° / −31.3..−18.4° |
| `Screens.NavScreenTag` / `ShipScreenTag` | (∓1.807, −2.153, −2.679) | 0.6 × 0.07, invisible carrier | as the screens | 240 × 28 (400) | 1.3 / 50 | 13 cm below each screen |
| `Screens.ThrottleGauge` / `SpeedGauge` | (∓0.820, −1.978, −3.291) | 0.42 × 0.42, invisible carrier | (0, 0.535, 0.845) | 168 × 168 (400) | 1.3 / 50 | ∓(10.1..18.0)° / −33.7..−26.8° |
| `Lamps.*` (8) | x = −0.805 + 0.23 k (k = 0..7: FlightAssist, Gear, MassLock, Scoop, Autopilot, Fuel, Heat, Warning), y −1.302, z −3.720 | 0.19 × 0.15 × 0.03, (14,12,10) | (0, 0.535, 0.845) | 95 × 75 (500) | 1.3 / 0 | −13.8..13.8° / −20.4..−17.7° |
| `Radar.Disc` (attribute `Radius 0.62`) | (0, −1.470, −2.860) | 1.24 × 0.01 × 1.24, invisible | up axis (0, 0.883, 0.469), GUI on the **Top** face | 372 × 372 (300) | 1.6 / 50 | −15..15° / −36.4..−18.7° |
| `Radar.Ship` | (0, −1.459, −2.854) | wedge 0.07 × 0.02 × 0.11, cyan Neon | disc frame | | | ship symbol at the centre |
| `Radar.ProjectorBeam` | (0, −1.710, −2.778) | 0.507 × 0.26 × 0.26, amber Neon at 92 % transparency | | | | faint light cone under the disc |
| `Radar.Blips.Blip1..12` / `Stem1..12` | moved at runtime | balls 0.05 (resized), stems 0.008 × h | | | | Neon, start invisible |
| `Holo.HoloLeft` / `HoloRight` | (∓2.777, −0.690, −3.084) | 0.80 × 0.92 × 0.01, invisible carrier (cyan) | (±0.660, 0.164, 0.733) | 416 × 478 (520) | 1.5 / 50 | ∓(36.4..47.6)° / −15.6..−3.2° |
| `Holo.*.Back` | same | 0.80 × 0.92 × 0.004, (4,12,16) Neon | | | | dark backing, on with the holo |
| `FlightHud.Glass` | (0, 0, −3.200) | 1.4308², invisible carrier | (0, 0, 1) | 372 × 372 (260) | 1.0 / 50 | ±12.6° (`02-flight.md` 3.9) |
| `FlightHud.Emitter` / `Lens` | (0, −1.125, −4.130) / (0, −1.093, −4.110) | 0.62 × 0.05 × 0.22 (30,32,36) Metal / 0.44 × 0.014 × 0.07 (255,170,45) Neon 10 % transparent | | | | the projector on the glare shield |
| `Buttons.*` (12 models: Base, Cap, Led, Tag) | 6.4.8 | cap 0.20 × 0.16 × 0.05 (42,45,50); base 0.24 × 0.20 × 0.05 (16,17,19) | as the screens | cap 100 × 80 (500), tag 120 × 20 (400) | cap 1.2 / 0; tag 1.3 / 50 | outer columns ±(18..50)° |
| `Controls.Throttle` / `Stick` | pivots (∓1.0, −1.92/−1.95, −1.02) | 6.4.12 | | | | grips at ±(38..50)° / −38..−51° (below the default view) |

Projected onto a 20:9 screen at the Roblox default **vertical FOV 80°** (horizontal 123.6°), as fractions of width and height: NavScreen x 24–38 %, y 72–101 %; ShipScreen x 62–76 %; gauges x 41–45 % / 55–59 %, y 82–90 %; radar x 43–57 %, y 71–95 %; lamps x 43–57 %, y 70–72 %; holos x 21–30 % / 70–79 %, y 54–75 %; button columns x 18–41 % / 59–82 %, y 71–102 %; glass x 44–56 %, y 37–63 %. At 70° vertical the screens' lower edge leaves the image (y up to 111 %); at 90° everything moves inward (screens y 68–93 %).

#### 6.4.2 Navigation screen (`NavScreen.Display`, canvas 732 × 540)

`Main`: full canvas, background (7,9,11) with a vertical `UIGradient` (26,18,10) → (6,7,9) (Roblox multiplies: effectively black, ≈(1,1,0) → (0,0,0)).

| Element | Rect on the canvas (px) | Style | Content |
|---|---|---|---|
| `Title` | x 28–467, y 8–68 | Sarpanch Bold 48, amber, left, v-centred | "NAVIGATION" |
| `System` | x 265–704, y 8–68 | Titillium SemiBold 36, muted, right | system name ("SOL") |
| `Rule` | x 24–708, y 72–76 | amber at 55 % opacity | |
| `Target.Icon` | centre (76, 152), 114 × 114 | TargetLock icon (drawing 80 px), Fit; tint amber / cyan when locked | |
| `Target.Name` | x 144–708, y 100–160 | **Michroma** 42, text / cyan when locked, left | "NO TARGET" / "LUNA" |
| `Target.Class` | x 144–708, y 160–204 | Titillium SemiBold 32, muted, left | "PRESS TARGET OR T" / "MOON" (builder default "PRESS T TO LOCK") |
| `Target.Distance` | x 28–482, y 224–304 | **Michroma** 52, amber, left | "4.48 KM" |
| `Target.Eta` | x 439–704, y 224–304 | Titillium SemiBold 36, text, right | "ETA 00:01:23" |
| `Rule2` | x 24–708, y 324–326 | amberDim at 80 % | |
| `List.Row1..3` | rows y 332–380, 380–428, 428–476 | `Name`: x 28–467, Titillium SemiBold 34, text, left · `Value`: x 366–704, Roboto Mono 30, muted, right | nearest three bodies/stations: "EARTH  ·  3.17 KM" |
| `Footer` | x 0–732, y 472–540 | background (30,18,8) standby / (8,30,40) active | |
| `Footer.Icon` | centre (48, 506), 62 × 62 | Autopilot icon (drawing ≈43 px), amber / cyan | |
| `Footer.Text` | x 88–708, y 472–540 | Sarpanch Bold 36, amber / cyan, left | "AUTOPILOT  STANDBY", "AUTOPILOT  CRUISING", "HYPERSPACE  COUNTDOWN" |

All TextLabels truncate at the end (`TextTruncate AtEnd`), backgrounds transparent.

#### 6.4.3 Ship screen (`ShipScreen.Display`, canvas 732 × 540)

`Main` as the nav screen.

| Element | Rect (px) | Style | Content |
|---|---|---|---|
| `Title` | x 28–394, y 8–68 | Sarpanch Bold 48, amber, left | "SHIP" |
| `Name` | x 338–704, y 8–68 | Titillium SemiBold 36, muted, right | ship DisplayName ("VARROW") |
| `Rule` | x 24–708, y 72–76 | amber 55 % | |
| `Speed` | x 28–482, y 80–184 | **Michroma** 80, text, left | "1.24" |
| `SpeedUnit` | x 439–704, y 88–128 | Titillium SemiBold 30, muted, right | "KM/S", "M/S", "Mm/s" |
| `SpeedC` | x 293–704, y 132–176 | Roboto Mono 32, amber, right, RichText (zone in its colour at 26 px) | "APPROACH  85.0 KM/S" / "MAX 260 M/S" |
| `ThrottleLabel` | x 28–108, y 196–228 | Titillium SemiBold 30, muted | "THR" |
| `Throttle` (track) | x 112–564, y 204–222 | bg (28,22,16), stroke 2 px amberDim at 70 % | fill amber (forward) / (200,90,60) (reverse) from the zero mark |
| `Throttle.Mark` | x 202 (0.2), 4 px wide, full height | (242,238,232) at 80 %, above the fill | zero thrust |
| `ThrottleValue` | x 572–704, y 192–232 | Roboto Mono 30, text, right | "-25%" … "100%" |
| `FuelIcon` | centre (56, 266), 56 × 56 | Fuel icon (drawing 40 px), muted / cyan scooping | |
| `Fuel` (track) | x 112–564, y 258–276 | as Throttle; fill cyan / danger < 25 %; mark hidden | |
| `FuelValue` | x 572–704, y 248–288 | Roboto Mono 30, text, right | "100%" |
| `HeatIcon` | centre (56, 318), 50 × 50 | Heat icon (drawing 40 px), muted / heat colour ≥ 80 % | |
| `Heat` (track) | x 112–564, y 310–328 | fill good / amber ≥ 80 % / danger ≥ 100 %; width heat/2; mark at x 338 (0.5 = 100 %) | |
| `HeatValue` | x 572–704, y 300–340 | Roboto Mono 30, text, right | "64%", "200%" |
| `Drive` | x 28–394, y 352–396 | Sarpanch Bold 34, colour per mode, left | THRUST / CRUISE / BOOST / REVERSE / AUTOPILOT / HYPERSPACE |
| `GForce` | x 366–704, y 352–396 | Roboto Mono 32, text, right | "1.2 G" |
| `Chips` | x 16–716, y 408–528 | five chips, each x = 20 + 140 k, 132 × 120, corner 12, stroke 2 px | FA, BOOST, GEAR, SCOOP, M-LOCK |
| chip `Icon` | centre (86 + 140 k, 448), drawing ≈44 px | tint per chip mode | FlightAssist, Boost, LandingGear, FuelScoop, MassLock |
| chip `Text` | x chip, y 480–520, centred | Titillium SemiBold 24 | "F-ASSIST" (runtime "FA OFF"), "BOOST", "GEAR", "SCOOP", "M-LOCK" |

#### 6.4.4 Round gauges (`ThrottleGauge` / `SpeedGauge`, canvas 168 × 168)

`Face`: full canvas, (7,9,11), corner 84 (circle), stroke 3 px amberDim opaque. Nine tick marks at −135°, −101.25°, … , +135° (33.75° apart): amber 3 px wide, radial from r = 75.6 to r = 61.3 px. `Needle`: full-size frame rotated by the needle angle; its `Bar` 4 × 67 px from the centre outwards, (242,238,232), corner 2. `Hub`: 16 px amber circle at the centre. `Title`: y 104–126, Sarpanch Bold 18, muted, centred: "THR" / "SPD". `Value`: y 128–150, Roboto Mono 18, text, centred: "60%" / "1.24 KM/S". Needle 0° = straight up; −135° (7:30) = 0, +135° (4:30) = full.

#### 6.4.5 Boot frames (on all four screens, ZIndex 50 over `Main`/`Face`)

Full canvas (7,9,11). `Title` y 32–52 %, Sarpanch Bold 52 amber centred: "NAV // ONLINE" / "SHIP // ONLINE" (gauges: 26 px, empty). `Status` y 55–69 %, Roboto Mono 28 muted centred (gauges 14): boot line. `Progress` x 20–80 %, y 74 %, height 8 px (gauges 4), (28,22,16); `Fill` amber, width = progress.

#### 6.4.6 Holo panels (`HoloLeft` / `HoloRight`, canvas 416 × 478)

Transparent `Main`; `Edge` 6 px full-height cyan bar at 85 % opacity on the outer side (left edge on the left holo, right edge on the right holo), ZIndex 2. Four rows at y 16, 132, 248, 364, each x 20–396, 100 px tall, background (10,40,52) at 45 % opacity, stroke 2 px cyan at 45 %: `Label` (x +16, y +4, 344 × 32) Titillium SemiBold 26 cyan left (BODY / ALTITUDE / GRAVITY / PITCH, MASS / AGILITY / BOOST / STATUS); `Value` (x +16, y +34, 344 × 62) Sarpanch Bold, `TextScaled` 22–52 px, holoText left ("EARTH", "3.17 KM", "0.12 G", "+12 DEG", "95 T", "100%", "READY", "NOMINAL" in its status colour). The carrier part is invisible; the `Back` panel behind it is a dark Neon plate shown only while the holo is on.

#### 6.4.7 Warning lamps (canvas 95 × 75)

`Back`: full canvas, corner 8, colour per mode; `Icon` centred, the drawing ≈48 px (ImageLabel edges = `ShipIcons.size(name, 48)`: FlightAssist 58, LandingGear 70, MassLock 67, FuelScoop 72, Autopilot 69, Fuel 68, Heat 60, Warning 62 px), tint per mode. Left to right: FA, GEAR, MASS LOCK, SCOOP, AUTOPILOT, FUEL, HEAT, WARNING.

#### 6.4.8 Buttons (12)

Each console carries its screen between two button columns. In the console plane (screen centre origin, x to the pilot's right, y up): columns at x = ±0.83 m, rows at y = +0.32, +0.02, −0.28 m.

| Console | Outer column (top → bottom) | Inner column (top → bottom) |
|---|---|---|
| Left (nav) | TARGET (T) · CLEAR TARGET (Y) · AUTOPILOT (P) | SYSTEM MAP (N) · STAR MAP (M) · HYPERSPACE JUMP (ENTER) |
| Right (ship) | EXTERNAL VIEW (V) · COCKPIT LIGHTS (—) · MUSIC (J) | FLIGHT ASSIST (Z) · BOOST (B) · LANDING (L / K) |

Cap face (100 × 80 px, Brightness 1.2): icon drawing 52 px, amber, centred (TargetLock, Autopilot, SystemMap, StarMap, Hyperspace, FlightAssist, Boost, Landing, HelpView, Audio), or text Sarpanch Bold 30 amber "CLR" (Clear Target) / "LGT" (Lights). LED: 0.12 × 0.018 m strip ~6.5 cm above the cap centre, (40,30,20) off, Neon `OnColor` (255,138,31) on (attribute `CoverLift 0.0122` unused by scripts). Tag: 13 cm below the cap, 120 × 20 px, Titillium SemiBold 15 (scaled 10–15), muted, centred, the `Label`. Model attributes: `Action`, `Icon`, `Key`, `Label`.

#### 6.4.9 Screen tags and decorative panels

`NavScreenTag` "NAV // TARGET" and `ShipScreenTag` "SHIP // STATUS": Sarpanch Bold 20, muted (150,110,70), centred, 240 × 28 px. `CockpitPlus` adds **static** decorative SurfaceGuis (powered via `GuiPower`, off with the lights below level 0.4): two `PodPanel` screens beside the seat ("THRUST", "RCS", "AUX PWR" / a 2 × 4 key grid "SCN COM DAT LNK CRG DCK AUX SEC" and "HULL SEAL", Sarpanch Bold 30 (255,150,44) and Roboto Mono 30 (150,158,168)/(64,214,240)), `HoloKeys` (holo keyboard), `WallScreen` behind the left shoulder ("COOLANT LOOP", "B-2" Michroma, "PUMP A 98%", "41 C"), `UtilityFace` ("AUX 2", "01".."04"), `OverheadPanel` (PRESS / O2 MIX / SCRUB / HEAT / VENT / SEAL, each "ON"), `JunctionBox` labels, and texture-like `Ribs`, `Louvre`, `Clad` SurfaceGuis on struts and walls (80–160 px/m). Port: bake these once into the cockpit texture; never update them.

#### 6.4.10 Radar face (`Radar.Disc.Display`, canvas 372 × 372 on the top face)

`Outer` ring Ø 98 % (365 px) filled amber at 12 % opacity with a 4 px amber stroke; `Mid` ring Ø 66 %, 2 px at 65 %; `Inner` ring Ø 33 %, 2 px at 50 %; axes `AxisH` (96 % × 2 px) and `AxisV` (2 px × 96 %) amber at 45 %. `Sweep` (96 % square, rotated by the sweep angle, clockwise): `Arm0` a 4 px line from the centre to the rim, amber at 95 % with a gradient from opaque at the rim to 10 % at the centre; `Arm1..7` trailing at −3.5° steps, 7 px wide, opacity 39.5 %, 34 %, 28.5 %, 23 %, 17.5 %, 12 %, 6.5 % with the same gradient. Blips are 3D Neon balls above the disc (3.4.8), stems drop to the disc plane. Brightness 1.6 (the brightest surface in the cockpit).

#### 6.4.11 Projection glass

`FlightHud.Glass.Display.Main` (attribute `Unit 1.2928`): `Center` 5 × 5 px (255,170,45), `Arrow` 30 × 32 px (two-tone image, hidden until steering). Behaviour and sizes in `02-flight.md` 3.9 and 6.2.

#### 6.4.12 Controls and pilot

| Rig | Parts (eye space centre, size, colour) |
|---|---|
| Throttle (left) | `Pivot` (−1.00, −1.92, −1.02) invisible · `Arm` (−1.00, −1.71, −1.02) 0.06 × 0.42 × 0.06 (150,156,163) Metal · `Grip` (−1.00, −1.42, −1.05) 0.15 × 0.22 × 0.24 (30,30,33) · `GripCap` 0.16 × 0.05 × 0.25 (27,29,32) · `Thumb` amber Neon 0.05 · `Thumb2` cyan Neon 0.04 |
| Stick (right) | `Pivot` (1.00, −1.95, −1.02) · `Boot` 0.14 × 0.12 × 0.14 (16,17,19) · `Shaft` 0.05 × 0.30 (150,156,163) Metal · `Grip` 0.13 × 0.30 × 0.16 tilted 8° · `Head` 0.14 × 0.07 × 0.18 · `Hat` amber Neon · `Trigger` (176,30,24) |
| Pilot (`CockpitPlus.Pilot`, CockpitOnly) | pelvis, abdomen, chest, shoulders, straps, buckle, lap belt, legs (shins, knees, boots), arms (upper arm, elbow, forearm, cuff) and hands (palm, knuckles, fingers, thumb); 29 parts carry `Lever` (see 3.4.6) |

#### 6.4.13 Cabin lights (`Cockpit.Lights` and `CockpitPlus.Lights`)

| Light | Position | Type, colour, brightness, range | Group |
|---|---|---|---|
| `RoofStrip` ×2 | (±1.36, 3.02, 0.75) | strip (236,242,250) Neon + SurfaceLight down (255,240,225), 1.1, 9 m, 120° | switched |
| `CabinFill` | (0, 1.10, −1.30) | PointLight (255,226,200), 0.35, 6.5 m (attribute `Switched true`) | switched |
| `DashGlow` | (0, −1.35, −2.60) | PointLight (255,150,70), 0.9, 5.5 m | panel |
| `FootGlow` | (0, −3.10, −2.40) | PointLight (90,190,255), 0.6, 4 m | panel |
| `ConsoleGlow` ×2 | (±2.60, −1.20, −0.60) | PointLight (255,170,110), 0.45, 3.5 m | panel |
| `StrutLed` ×6 | along the canopy struts | strip (112,132,150) | switched |
| `RoofSlot` ×2 | (±0.575, 3.10, 0.15) | strip (58,104,140) + SurfaceLight up (110,160,255), 0.55, 3.5 m | switched |
| `PodEdge` ×2, `PedestalLed`, `HoloLens` | seat pods, pedestal | strip (36,146,176), `Switched false` | panel |
| `PodGlow` ×2 | (±1.337, −2.075, −1.917) | strip (124,91,55) + PointLight (255,158,88), 0.55, 2.4 m, `Switched false` | panel |
| `SillTube` ×2 | (±3.595, −0.905, −0.30) | strip (150,172,190) | switched |
| `FootLens` | (0, −2.17, −2.19) | strip (124,91,55) + SpotLight down (255,158,88), 0.8, 3.6 m, 100° | switched |
| `ReadingLens` | (0, 2.925, 1.06) | strip (190,104,36) + SpotLight (255,226,190), 0.9, 4.6 m, 60° | switched |
| `WallScreen` | (−2.40, 0.20, 1.35) | SurfaceLight (255,150,60), 0.4, 2.6 m, 100° + GUI | panel (GuiPower) |

No light casts shadows. Port: bake the static lighting into vertex colours/lightmap of the cockpit mesh in two layers (roof set, panel set) and blend by the two levels; Neon strips are emissive.

### 6.5 CockpitInteract (StarterGui, not exported)

Contract: `Reticle` (Frame, centred, visible only in look-around) with child `Ring` (22 → 28 px, UIStroke transparency 0.45 → 0 on hover); `Tooltip` (Frame) with `Label` (button label) and `Key` ("[T]", hidden when empty), placed 24 px above the centre or 18 px above the mouse; `Hint` (TextLabel with text stroke, 12 s after the first boot); `Sounds.Hover`, `Sounds.Press` (attribute `BaseVolume`); `ButtonHighlight` (Highlight). **Proposal** until exported (design px): ring 22 px circle, 1.5 px stroke PanelColor; tooltip plate (6,10,16) at 70 % with a 1 px Accent stroke, `Label` Sarpanch SemiBold 14 Accent, `Key` Roboto Mono 13 Panel; hint centred at 20 % height, Titillium SemiBold 16 Panel with a 1 px black stroke; Highlight = Accent outline, no fill. Hint text proposal (desktop): "HOLD RIGHT MOUSE TO LOOK AROUND  ·  CLICK THE COCKPIT BUTTONS"; phone: 9.1.

### 6.6 FlightNotices (StarterGui, not exported)

Contract (3.9): `Canvas` (UIScale, colour attributes) with `Comms.{Sender, Body}`, `Docking.{Pad, Distance, Status, Limit, Timer}`, `Survey.{Label, Bar.Fill, Value, Range}`, `Toast.{Title, Body}`, `Sounds.{Comms, Toast}`. Looks and proposed sizes: `08-stations-economy.md` 5.4; final phone positions: 6.9 below.

### 6.7 Icons

The 16 icons of 3.1 are white PNGs tinted at runtime; in the cockpit they appear at drawing sizes 80 (nav target), ≈43 (nav footer), 52 (button caps), 48 (lamps), ≈44 (chips) and 40 px (fuel/heat rows) on their canvases. None are in the export. Port options (Q3): the user exports the PNGs (preferred: 1:1), or the port draws vector stand-ins of AFloidDEV's motifs with the same names and sizes (the menu icons already have vector fallbacks, `07-menu-maps-warp.md` 5.6).

### 6.8 Flight effects, look summary

- **Dust:** thin additive strokes, bright head, transparent tail; 1–2 px wide at any depth (head 1.8 px, tail 0.6 px at 1080 lines); up to 26 m long; nothing within 7 m of the eye; centred 78 m ahead in the view direction.
- **Air streaks:** 70 strokes, 7 cm head width (world units), up to 40 m, 12–50 % opaque.
- **Speed streaks:** 1.5 px wide at both ends at any depth, at most 50 % opaque in the middle, soft quarter-length ends, 70–200 m long, radiating from the flight direction, never closer than 24 m to the axis.
- **Exhaust:** additive soft sprites; core white-hot near the nozzle (60 % towards white), engine colour from 35 % of the life on; veil 1.5 nozzle diameters wide at 28 % opacity.

### 6.9 Phone layout proposal (20:9, in u)

Reference: Xiaomi 15 landscape 2670 × 1200 px at 2.875 px/dp ≈ **929 × 417 u** (`u` = `frame.u`, 1 dp here), inside the safe insets `sl/st/sr/sb`; right-handed (mirror for left-handed, as `TouchFlightControls` does). Existing touch elements (`TouchFlightControls.layout`): lever track x 30–44, y 125–365; ▲▼ at x 83–125, y 129–171 / 183–225; PAUSE (14–60, 12–50); MAP (721–777), LOCK (787–849), CAM (859–915) at y 12–50; FA circle centre (776, 108) r 23; action button (822–914, 75–109); BOOST circle centre (868, 167) r 31; stick rest (779, 307) r 52; the left 42 % (x < 390) is the floating lever zone, the rest the floating stick zone.

| Element | Rect / anchor (u) | Style | Rule |
|---|---|---|---|
| Autopilot label | centre x, y st+14..st+32 | Sarpanch Bold 12, Accent | while engaged (Roblox 96 px ≈ 9 % H → moved into the top row between PAUSE and MAP) |
| Toast | centre x, y st+40.., ≤ 420 × 46 | title Sarpanch Bold 13, body Titillium 10.5 | 4 s |
| Comms | centre x, below the toast when both show (else in the toast slot), 380 × 54 | `08` 5.4 style | 6 s |
| Prompt | centre x, **bottom** at 0.64 H (cockpit and external) | Sarpanch Bold 12, plate (6,10,16) 70 %, 1 u stroke, padding 12/4 u | keeps Roblox's cockpit placement; external view uses the same height so it never meets the survey bar |
| Survey bar | x W/2 ± 170, y H−sb−100 .. −50 | `08` 5.4 | while surveying/mining |
| Docking aid | x sl+140..sl+350, y st+120..st+218 | `08` 5.4 | above the usual left-thumb rest, clear of ▲▼ |
| Flat stack (external view) | x sl+140..sl+360, y st+60.. | bars 150 × 5 u, readout Roboto Mono 9.5 u, 11 lines | hidden while the docking aid shows (both are flight data; the aid is more urgent) |
| Target panel (external view) | right-aligned at W−sr−14, y st+206..st+270 | Roboto Mono 9.5, 5 lines | under BOOST, above the stick rest |
| Cockpit minimal readout (Q1) | beside the lever, x gauge.right+26 | as today: speed 28 u mono + unit 10 u; add the limit/zone line (SpeedC) 9 u and the throttle % (exists) | cockpit view only |
| Markers | anywhere | ring 22 design px → **≥ 10 u** Ø; name Titillium **9 u**; distance Mono **10 u** | Roblox sizes are 1080-line pixels (a 22 px ring would be 3 mm) |
| Blackout | full screen | | over HUD and markers, under the touch controls and warnings |

```text
 ┌ PAUSE ─────────────── AUTOPILOT  CRUISING ─────────────── MAP  LOCK  CAM ┐
 │             ┌──────────── TOAST TITLE / body ───────────┐      (FA) [LAND]│
 │  THR        └──────────── COMMS sender / body ──────────┘           (BOOST)│
 │  ▮   ▲   ┌ DOCKING / stack ┐                                              │
 │  ▮   ▼   └─────────────────┘        · ▲ (flight arrow on the glass)       │
 │  ▮                                                         TARGET PANEL  │
 │  ▮        ┌────────────── PROMPT (bottom at 64 % H) ──────────────┐       │
 │  ▮ 260 m/s    [NAV screen]   (THR)  [radar]  (SPD)  [SHIP screen]   ( stick)│
 │  ▮ APPROACH 85 KM/S        ┌────── SURVEY BAR ──────┐                       │
 └──────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Dependencies and data contracts

### 7.1 Inputs this area needs

| From | Data | Consumers |
|---|---|---|
| Flight model (`02-flight.md`) | throttle (−0.25..1), speed and velocity (in the reference frame), `speedLimit`, `speedZone` label, forward acceleration, felt acceleration in ship axes (m/s²), angular velocity (deg/s, X pitch, Y yaw, Z roll), FA, boosting, cruising, boost cooldown, overloaded, agility, mass, gForce, strainG, redout, fuel, heat, scooping, `pitchAngle`, `localUp`, gravityG, surfaceGravityG, bodyName, `inInfluence`, `inMassShadow`, `timeToImpact`, autopilot engaged + status, aim / key axis / cursor (stick pose, projection glass), gear command | HUD, cockpit screens, levers, lamps, mood, motion |
| World (`03-world.md`) | per body: name, class, collision/surface radius, star flag, "named at any distance" flag (star or planet, not moon), position (double); current system name | markers, radar, nav screen |
| Stations (`08-stations-economy.md`) | per station: name, class, radius, position; docking prompt; docking aid record; survey/mining records; comms and toasts; the pad sign's screen box (solid label) | markers, prompt, notices, declutter |
| Atmosphere / planet entry (own doc) | landing prompt; atmosphere state SPACE/ENTRY/SURFACE/EXIT; "air" flag (inside an atmosphere); near-surface flag (Roblox: landing scene active); arrival toast | prompt, dust air mode, radar, status line, LANDING LED |
| Warp and maps (`07-menu-maps-warp.md`) | warp active, state, travelling; system/star map open; map lock → `setLocked` | nav footer, drive, status, LEDs |
| Ships import (task 7) | cockpit and hull meshes per node with their path names; transforms in eye space; **attributes** (`Action`, `Label`, `Key`, `Icon`, `OnColor`, `Switched`, `GuiPower`, `Lever`, `LeverFollow`, `LeverMask`, `Radius`, `HideInside`, `HideSeated`, `CockpitOnly`, `GearClosed`, `Glass`, `EngineGlow`); the **invisible carrier parts** the glTF lacks (glass, radar disc, gauges, tags, holo carriers, plumes); per-ship `ExternalCamera`, `DisplayName`, engine glow discs with lights | cockpit renderer, interior, hull visibility, exhaust |
| Camera | pose, vertical FOV, external flag, look angles | markers projection, focus, dust/streak view cone, pixel scale |
| Settings | CameraShake 0–1, FieldOfView 60–100, EffectsVolume | motion, FOV, button sounds |
| Audio / voice (own doc) | button Hover/Press, lock tone, Comms/Toast sounds, notice and comms voice lines | interior, markers, notices |
| Warnings (own doc) | crash in progress (`isBusy`), impact phases, overheat phases | status line, WARNING lamp, mood |

### 7.2 Outputs

| To | Data |
|---|---|
| Autopilot, survey, system map | `markers.locked` (target identity, position, radius) |
| Radar, nav screen | `markers.ordered` (bodies and stations by centre distance, 10 Hz) |
| Camera | head offset (translation + roll) and camera shake (Euler YXZ) |
| Flight arrow / attitude (`02-flight.md`) | `interior.hasFlightHud()`, `interior.isHudOn()` |
| Game actions | cockpit button actions (same handlers as keys/touch buttons) |
| Renderer | hull part visibility flags, gear pose, engine glow level, plume particles, cockpit dynamic part transforms (levers, caps, blips), screen textures |
| Docking, survey, mining, construction, decks | the notices API (`comms`, `toast`, `docking`, `survey`) |

---

## 8. Gap analysis against the current Kotlin code

| Roblox | Kotlin today (file, function) | Difference | Action |
|---|---|---|---|
| Flat HUD stack, readout (`ShipHud`) | `Game.drawHud` (`Game.kt:860-941`): speed number (mono 28 u) + unit beside the lever, boost capacitor bar, zone label top centre, blinking HEAT / FLIGHT ASSIST OFF / PROXIMITY texts, `message` line at 0.30 H | no fuel/heat bars, no 11-line readout, no prompt plate, no autopilot label; zone shown as a separate label instead of `"(zone · max limit)"`; the top-centre warnings are not Roblox's (FA OFF shows on the FA lamp/chip and in the readout; heat/impact belong to ShipWarnings) | new `hud/FlightHud.kt`; keep the lever-side speed as the phone's cockpit readout (Q1); move warnings to the warnings port |
| Blackout | none | missing | `FlightHud` (physics in `02`) |
| Units (`ShipUnits`) | `Game.formatSpeed/formatDistance` (`:1757-1781`) | "MM", "MM/S"; different thresholds; no grouping | `hud/Units.kt` writing into `TextBuf` (shared with `02`) |
| UI scale (`ShipUi`) | `frame.u` (dp based) | Roblox sizes are 1080-line px | conversion table in 6.9 / 9.2 |
| Head sway, shake (`ShipCockpit`) | `ChaseCamera` mode 2 (`World.kt:178-198`): eye at (0, 1.05, −2.6) of the KiteShip, collision shake only (`addShake`, sin 37/53 Hz) | no G/turn sway, surge, roll, noise vibration, camera shake setting | `cockpit/CockpitMotion.kt` + `math/Noise.kt` |
| 3D cockpit (`ShipInterior`) | none: cockpit view hides the ship (`sceneFrame.ship = null` in mode 2, `Game.kt:743`) | missing entirely | `cockpit/*` (10.1) |
| Visible hull (`ShipHull`) | `content/KiteShip.kt` procedural mesh, one draw with palette (`SceneRenderer.drawShip`), nav lights and strobes as billboards | real models, visibility rules (HideInside, HideSeated, CockpitOnly, gear), engine glow discs and lights missing; KiteShip nav lights/strobes are not Roblox's | ships import + `game/ShipVisual.kt` |
| Exhaust (`ShipExhaust`) | `SceneRenderer.drawShipGlows` (`:420-458`): one plume quad per nozzle (length 2.2 + 7.5·output m, blue/white), a halo billboard | no particles, no core/veil, lengths not in nozzle diameters, boost colour change instead of length ×1.6 | `fx/Exhaust.kt` + renderer (particles in ship space) |
| Markers (`ShipMarkers`) | `Game.drawTargets/drawMarker/labelAndDistance/claimLabel` (`:1473-1628`): ring sized to the body's screen radius (≥ 5 u), stations in Theme.ACCENT, name 9 u, distance for the locked or the nose-aimed target (3° cone); label claim order locked/aimed → stations → bodies; markers hidden behind planets (`hiddenBehindBody`) and for bodies filling the view; asteroids lockable | Roblox: fixed 22 px rings, one colour, focus = nearest to the screen centre within 0.14 H, declutter by priority (size, stations last) with an 8 px gap, moons/stations unnamed beyond 2,000 km, markers always drawn (on top of everything), no asteroid targets | rewrite as `hud/TargetMarkers.kt` |
| Lock (`cycleTarget`, Y) | `Game.toggleLock/findAimed/aimScore` (`:1342-1418`): LOCK toggles the lock on the target nearest the nose within 18° | Roblox cycles by centre distance and clears with Y | LOCK tap = cycle, long-press = clear, tap a marker = lock (Q5) |
| Lock brackets | amber L-brackets sized to the body, label under them | Roblox: fixed 38 px brackets with a 0.22 s snap-in | port Roblox's |
| Off-screen lock arrow | `Game.drawLockArrow` (`:1564-1603`): chevron on an ellipse | not in Roblox (the radar shows the locked blip first) | keep as a phone aid or drop (Q4) |
| Target panel | none (distance under the brackets) | missing | `TargetMarkers` (external view; cockpit per Q1) |
| Notices (`ShipNotices`) | `Game.showMessage` (one line at 0.30 H for 3 s) | no comms, toast title/body, docking aid, survey bar | `hud/Notices.kt` |
| Prompt line | touch action button label (LAND / DOCK / TAKE OFF) | no prompt plate, no BLOCKED colouring | add the plate; keep the action button for touch |
| Dust (`ShipDust`) | `SceneRenderer.drawDust` + `Shaders.dustVs/Fs`: GPU box `DustField.BOX`, origin moves at a capped visual speed (400 m/s), streak = velocity × 0.045 × (1 + 0.6 log10) , intensity 0.6 constant, colour by zone with τ 0.25 s | no speed fades (1.5–5.5, 600–2000 m/s), no head/tail profile, no near clip 7 m, no edge fade, no air mode, different mood rules (Kotlin danger = proximity < 80 m or close to the star; Roblox danger = impact < 10 s or heat ≥ 80 %, approach = inside the influence) | rewrite shader + `fx/SpeedDust.kt` with Roblox formulas |
| Speed streaks | none | missing | `fx/SpeedStreaks.kt` + shader |
| Distance lines, speed lines | none | off/unused in Roblox | none |
| Icons | text labels on touch buttons | no icon set | icon atlas (Q3) |
| Cockpit FOV | chase 52° (+3° with speed, +9° boost), cockpit 60° vertical | Roblox 80° vertical, setting 60–100, no FOV kick | settings FOV (`02` 10.3) |
| Settings | no camera shake | CAMERA SHAKE 0–1 | add |

