# 01 · Core loop, state flow and shared infrastructure

Port reference for **Untitled Space Exploration** (Roblox game by AFloidDEV) → Android/Kotlin (`:core`). This file covers the main client script with the per-frame order of everything, the player's states, and the shared infrastructure every other area builds on:

| Topic | Roblox source (paths relative to `roblox/lua/`) |
|---|---|
| Main LocalScript: boot, module wiring, event handlers, the per-frame loop, crash and recovery, deck and aboard glue, debug hooks | `ShipClient.lua` (4,025 lines) |
| Camera: cockpit seat, head, look-around, external view | `ShipClient/ShipCamera.lua` |
| The Roblox character as a physics-free anchor (and walking on decks) | `ShipClient/ShipRig.lua` |
| Floating origin (32-bit world vs 64-bit universe) | `ShipClient/ShipOrigin.lua` |
| Performance mode | `ShipClient/ShipPerformance.lua` |
| Frame-rate independent maths | `ShipCore/ShipMath.lua` |
| Key names and key lookups (layout-aware, alternate keys) | `ShipCore/ShipKeys.lua` |
| Player settings | `ShipCore/ShipSettings.lua` |
| UI scale, exclusive screens, open animation | `ShipCore/ShipUi.lua` |
| Units and number formatting | `ShipCore/ShipUnits.lua` |
| Networking layer | `ShipCore/ShipNet.lua` |
| Server entry script | `ShipServer.lua` |

All twelve files were read completely. The `ShipConfig` sections Keys (`ShipCore/ShipConfig.lua:2763`), AltKeys (`:2845`), Camera (`:2651`), Ship (`:2668`), Origin (`:2416`), Performance (`:2710`), Scale (`:2754`) and Runtime (`:3069`) were read in full, plus every value the loop reads from other sections (3.8). Also read for this file: `ShipClient/ShipInput.lua` (mouse capture and look-around feed the camera), the key handlers of every module that binds a key (8.1), `ShipCore/ShipMenuData.lua` (settings rows, key list, help), the state queries of ShipDocking, ShipWarp, ShipAtmosphere, ShipAutopilot, ShipDecks, ShipAboard and ShipWarnings, and the Kotlin code listed in section 7.

Sibling port docs: `02-flight.md` (flight model; its section 2 is the flight subset of the order given here), `03-world.md` (Sol, S, orbits, system build), `04-planet-surface.md` (terrain, seamless surface), `06-cockpit-hud.md` (cockpit, HUD, markers, notices, `Units` and `InputLabels` file plan), `07-menu-maps-warp.md` (menu, settings screens, maps, warp), `08-stations-economy.md` (stations, docking, economy, saves), `09-life-npc-audio.md` (decks, aboard, traffic, training, audio, voice). `05-atmosphere-entry.md` (guided landing → landing assist, sky, clouds, entry effects, warnings, crash cinematic; its 4.7 covers the crash flow from the warnings' side, consistent with 2.1.10 here). `DECISIONS.md` overrides proposals here; 10.1 lists what it settles for this area.

The player is always **PILOT**. Two Roblox comments compare the mouse scheme to a commercial space sim (`ShipSettings.lua:25`, `ShipInput.lua:13`); they describe AFloidDEV's own control scheme. The port keeps the scheme and uses no names, terms or designs from that game.

---

## 0. Key facts in one screen

- **One render-step callback runs the game.** `ShipUpdate` is bound at `RenderPriority.Camera` (`ShipClient.lua:2321`) with `dt = min(dt, 0.1)`. Each frame takes exactly one of three branches: **decks** (on a station deck; flight paused), **destruction** (crash cinematic; flight paused) or the **flight frame** (76 steps, section 2.2). No fixed time step, no sub-steps.
- **Order principle** (`:5-11`): input → rotation → thrust → anchor → cockpit → camera → sound → GUI. Gravity and all speed limits are composed *before* thrust (the limit acts in the same frame); crash checks, the floating origin, stations and docking run after the move; the camera after the cockpit motion; HUD, flight arrow and attitude at the end; save reports last.
- **Boot:** performance check → main menu (blocks until LAUNCH, returns the mode) → performance values applied → training/solo routing (teleports) → every flight module built → profile fetched → system built and the ship placed → render step bound → two rendered frames later the black curtain fades (0.6 s) and the cockpit boots (2.6 s). Welcome voice 5 s after the character attaches. The menu reveals itself after 12 s if the flight never reports.
- **Player states** (4.3): MENU, LOADING, FLYING (with autopilot, plotted jump, docking request as sub-states), WARP TRAVEL, DOCKING (computer flies), DOCKED, LAUNCHING, ON DECK (seated or walking), ATMOSPHERE ENTRY/EXIT (Roblox guided landing), ON SURFACE / LANDED, ABOARD (walking in the own ship), CRASHING (fade), DESTROYED (cinematic), RESPAWN. **Maps, station services and the construction panel are overlays that do not pause the flight.**
- **Camera:** default 3D cockpit, `V` external. Cockpit eye = ship · T(0, 0.5, 0) · head offset · neck pivot (0, −0.35, 0.3) · look · neck⁻¹ · shake; external = ship · look · per-ship offset (VARROW (0, 16, 95) pitched −8°) · shake. Look-around: right mouse held, 0.25°/px × sensitivity, ±140° / +80° / −70° in the cockpit, ±180° / ±60° outside, returns with τ = 0.12 s.
- **Floating origin** (`RebaseDistance 1`: the world is shifted every frame the ship moves) is a 32-bit Roblox workaround. Kotlin already stores positions in doubles and renders camera-relative: **not ported**.
- **ShipRig, the spawn lock, `ShipNet` and `ShipServer`** are Roblox and multiplayer plumbing. The port replaces them with a local service layer (`LocalServices`: the server modules' rules as plain classes, initialised in the server's order) and the save manager of `08`.
- **Settings** (8 rows): MOUSE SENSITIVITY 0.25–2 (1), INVERT PITCH (off), MOUSE X AXIS ROLL/YAW (ROLL), FIELD OF VIEW 60–100 (80), CAMERA SHAKE 0–1 (1), PERFORMANCE MODE (off, auto), MUSIC 0–1 (1), EFFECTS 0–1 (1). Roblox does not persist them; the port does.
- **Performance mode** switches itself on when Roblox's fixed graphics level is ≤ 3 or the median menu frame rate is < 38 fps, unless the player has changed the setting; it lowers 16 values (particle and beam counts to about half, per-frame build budgets from 4–5 ms to 3 ms, the ground range from 4 to 1), switches shadows off and culls ship detail parts shorter than 0.6 m.
- **Units:** m → km → Mm with thresholds that never print "1000 m" or "10.00 km"; "Mm" stays mixed-case inside upper-case text; grouping from 100 Mm up ("1,240 Mm"). The current Kotlin formatters differ (they print "MM").
- **28 keys** + 2 alternates + mouse, wheel and E: every one has a touch and a gamepad equivalent in 8.1; the gamepad map reconciles the proposals of `02`, `06`, `07`, `08` and `09` (8.2).

## 0.1 Conventions

- **Units.** 1 stud = 1 m (`ShipConfig.Scale.MetresPerStud = 1`). Lengths marked **×S** scale with the world factor S = 3000 (`DECISIONS.md` D1); unmarked values are absolute.
- **Axes.** Roblox `CFrame` and Kotlin `Quat`/`Vec3` share right-handed axes: +X right, +Y up, **−Z forward** (`LookVector`). `CFrame.Angles(0, a, 0)` with a > 0 turns the nose **left**; `CFrame.Angles(a, 0, 0)` with a > 0 raises it. `T(v)` below = `CFrame.new(v)` (a translation).
- **Universe vs world** (Roblox only): universe = system coordinates with the star at 0, kept in three Lua doubles; world = universe − origin, what `Part.Position` holds (32-bit).
- **GUI.** `UDim2 {xScale, xOffset, yScale, yOffset}`, Y down; `AnchorPoint` is the point of the element placed at `Position`. **Design px** = pixels at 1080 lines (the Roblox reference height). **u** = the Kotlin layout unit (`Frame.u`, 1 dp on phones that are at least 720 × 380 dp).
- **Colours** sRGB 0–255. Roblox transparency T means opacity 1 − T.
- **Luau details.** `math.round` rounds half away from zero (Kotlin: `sign(x)·floor(|x| + 0.5)`); `%` is a floored modulo; `os.clock()` is a monotonic clock in seconds; `task.delay/spawn/wait` are coroutine timers. In Kotlin every such timer becomes a game-time timer on the GL thread.
- **Line references** are `File.lua:first-last`; a bare `:n` means `ShipClient.lua:n`.

---

## 1. Overview: what the player experiences

**Start.** The game opens in the main menu (hangar backdrop, `07`). None of the flight exists yet: no flight camera, no flight music, no character. Choosing a mode under LAUNCH (SOLO, OPEN PLAY, TRAINING) fades to black. A new pilot is routed into the flight training first, whatever card was chosen (`Training.Required`). Behind the black curtain the game loads the pilot's profile (ship, fuel, last location), builds the star system the pilot left and places the ship: **docked** at the station the pilot left from (on a free pad; if none is free, just outside that station's mouth), otherwise at that system's star "as after a jump" (outside the heat zone, looking at the star). Two frames after the world is first drawn, the curtain lifts over 0.6 s; the cockpit is dark and boots in 2.6 s (roof lights flicker, lamp test, needles sweep, boot screens). Five seconds after the character attached, the ship computer says "Ship computer online." and then "All systems online." A pilot who left docked sees the station services at once (with a hangar deck: the lift is already down).

**Flying.** The default view is the cockpit. `V` switches to an external view behind and above the ship (a fixed offset per ship, rigidly attached, no lag). Holding the right mouse button turns the head: up to 140° over each shoulder, 80° up and 70° down. The eye moves on an arc around the neck, as a real head does; on release the view swings back in a fraction of a second (time constant 0.12 s). In the external view the same gesture orbits the camera fully around the ship. Field of view (60–100°, default 80° vertical) and camera shake (0–100 %) are options.

**Maps and panels never pause.** The system map (N) and star map (M) free the mouse; the ship keeps flying at its throttle setting; opening or closing a map recentres the flight cursor so the ship does not keep turning unseen. The station services and the construction panel also free the mouse; holding the right mouse button there captures it again for looking around.

**Messages from the loop.** Entering a system nobody has visited: "FIRST VISIT · <SYSTEM> · NO PILOT HAS BEEN HERE BEFORE. YOUR NAME IS ON THE CHART." Reaching the edge of the play field: "SYSTEM EDGE · THE SYSTEM ENDS HERE · PRESS M FOR THE STAR MAP AND JUMP ON" (at most once every 30 s while it brakes). Trying to jump near a station: "JUMP BLOCKED · STATION MASS LOCK · FLY 6 KM AWAY". Touching a planet's barrier in space (Roblox only): "MINIMUM ALTITUDE · HOLDING ABOVE EARTH".

**Losing the ship.** Overheating at a star (ship heat 200 %), touching down too hard (above 38 m/s on the landing scene, 20 m/s on the old surface) or hitting a station hull above 45 m/s destroys the ship. Running warnings stop, the computer says "Hull failure.", an 11 s cinematic shows the explosion from above with a SHIP DESTROYED plate counting down, and the losses are listed (replacement fee, cargo, unsold survey data, each also spoken). The picture fades to black, the systems reset and the replacement ship waits in front of the station nearest to the crash site. The cockpit reboots ("Rebooting.") and, once the picture is back, "All systems online."

**On foot.** With a hangar deck, docking lowers the ship into the pilot's own hangar (the flight world is hidden and the flight loop rests). Larger ships can be left at the helm (key 1) to walk around on board, even in flight: the autopilot or a running jump keeps flying, otherwise the ship brakes to a stop with Flight Assist on.

**Performance.** PERFORMANCE MODE (OPTIONS > GRAPHICS) halves particles and streaks, shortens the ground render range, switches off shadows and drops tiny ship detail parts. It switches itself on for slow PCs unless the pilot has changed it.

**Saving.** In free flight the client reports position, look and fuel every 10 s (Roblox stores them, but since 2026-11-03 a start ignores the stored position, `:766-771`); docking, undocking, jumps and losses are saved by the server when they happen.

---

## 2. Modules

### 2.1 ShipClient (`ShipClient.lua`): structure, boot and event handlers

**Purpose.** The single LocalScript that owns the game on the client: it shows the menu, builds every module, wires them together with callbacks, places the ship, runs the per-frame loop (2.2) and handles every discrete event (keys, cockpit buttons, docking, jumps, crashes, decks, walking aboard). It holds no game rules of its own beyond glue, spawn rules and the crash flow. Because the script sits near Luau's limit of 200 locals, several things hang off other objects (`hud.attitude`, `:297-300`) or live in `do` blocks (`:111-127`, `:2288-2315`, `:3130-3151`).

#### 2.1.1 Boot sequence (top-level script order; steps S1–S29)

| # | Lines | Step |
|---|---|---|
| S1 | 20-29 | Services, `player`, `camera = workspace.CurrentCamera`, `playerGui`. |
| S2 | 42-55 | `launchMenu = nil`. `joinMode = ShipTraining.arrivalMode()`: `"TRAINING"` on the private training server, `"SOLO"` on a private solo server, `"GALAXY"`/`"OPEN"` after a teleport, `nil` on a normal join. `trainingServer = joinMode == "TRAINING"`. `launchMode` = `"TRAINING"`, `"OPEN"` (for OPEN or GALAXY) or `"SOLO"`. |
| S3 | 59 | `ShipPerformance.start()` (2.6): checks the fixed graphics level and starts the frame-rate measurement in the menu. |
| S4 | 61-105 | **Menu.** If `joinMode`: disable the default camera, show the arrival cover (black, a title per mode, `ShipTraining.arrivalCover`), move `workspace.MenuHangar` to ReplicatedStorage. Else if the module `ShipMenu` exists: disable the default camera, `menu = ShipMenu.new(player, playerGui, camera)`, `result = menu:run()` **(yields until LAUNCH)**, `launchMode = result.mode`. If the menu throws: warn, `menu:destroy()`, disable `MainMenu`, enable a legacy `Main` GUI, move the hangar away; the flight starts directly ("a broken menu must not prevent flying"). |
| S5 | 109 | `ShipPerformance.apply()` **before any flight module is built**: modules read their counts at construction. |
| S6 | 111-127 | Training routing: if `joinMode == nil`, `launchMode ≠ "TRAINING"` and `ShipTraining.required(player, 10)` (waits ≤ 10 s for the save attribute `TrainingDone`) → `trainingNext = launchMode; launchMode = "TRAINING"`. If `launchMode == "TRAINING"` and this is not the training server → `ShipTraining.transfer(playerGui, trainingNext)` (teleport; returns only if it failed or in Studio). |
| S7 | 132-137 | `launchMode == "SOLO"`, `joinMode == nil`, menu shown → `ShipTraining.transferSolo(playerGui)` (teleport to a private server; returns only if the player stays). A return value `"TRAINING"` (the server demands training first) → `launchMode = "TRAINING"`, `transfer(playerGui, "SOLO")`. |
| S8 | 139-197, 221-222 | `require` every flight module and ShipCore module. |
| S9 | 203-220 | Background probe of the smooth sphere mesh; on failure `ShipSystemBuilder.smoothSpheres = false` (planets fall back to `MeshType.Sphere`). Roblox only. |
| S10 | 231-256 | `profile = ShipProfile.new()`; `startInfo = profile:fetch()` (yields; the server waits ≤ 25 s for the save, then returns `{profile, now, pad, docked}`); `startView = startInfo.profile`; `galaxy = ShipGalaxy.new(profile)`. If `startView.ActiveShip` has a model and a config entry → `ShipConfig.Hull.Model = it`, else warn and fly the default. No profile → warn "start in Sol without saving". |
| S11 | 262-346 | Construct the flight modules (table 2.1.2). |
| S12 | 352-477 | Stations, traffic, docking (+ probe), survey, station services, deck UI, crowd, docking callbacks, `systemFolder` ("CelestialBodies"), pilots. |
| S13 | 481-483 | `START_SPAWN = lookAt(Ship.StartPosition, StartPosition + StartLookDirection)`; `spaceSpawn = START_SPAWN`. |
| S14 | 485-523 | Atmosphere callbacks (landing scene switch, 2.1.3). |
| S15 | 532-663 | System helpers (2.1.4) and the initial build. |
| S16 | 675-764 | Galaxy change listener, survey description, mining, callouts, construction panel. |
| S17 | 772-800 | Start placement (2.1.5). |
| S18 | 806-831 | Roblox clean-ups: every `SpawnLocation` made non-colliding, non-queryable and invisible locally (the origin follows the ship, the spawn plate would hang in space); a deferred safety net clears voxel terrain and resets `LocalTransparencyModifier` and `TerrainLocked` on every body. |
| S19 | 834-933 | Star map confirm gate, `arriveInSystem`, warp callbacks (2.1.6). |
| S20 | 937-1150 | Key and button handlers (2.1.7). |
| S21 | 1152-1190 | Global `InputBegan` for LeaveSeat, L, K, V, P. |
| S22 | 1192-1254 | Character attach, ready handshake, welcome (2.1.8). |
| S23 | 1256-1290 | Frame-local state (2.1.9). |
| S24 | 1302-1688 | Crash flow and `getPitchAngle` (2.1.10, 2.1.11). |
| S25 | 1695-2083 | Deck glue, training, aboard glue (2.1.12-2.1.14). |
| S26 | 2085-2315 | `deckFrame`, `destructionFrame`, `updatePilots`. |
| S27 | 2321-3196 | **`RunService:BindToRenderStep("ShipUpdate", RenderPriority.Camera, …)`** (2.2). |
| S28 | 3208-3264 | Reveal and `afterReveal` (2.1.15). |
| S29 | 3270-4025 | `_G.Ship` and the Studio-only `ShipDebug` bridge (2.1.16). |

#### 2.1.2 Construction order (S11, S12, S16, S25)

| Line | Object | Notes |
|---|---|---|
| 262 | `rig = ShipRig.new(player)` | 2.4 |
| 263 | `input = ShipInput.new(camera)` | cursor, key axis, look-around, mouse capture (`02` 3.1, 2.3 here) |
| 264 | `rotation = ShipRotation.new()` | pose starts at `Ship.StartPosition` looking `StartLookDirection` |
| 265 | `thrust = ShipThrust.new()` | |
| 267 | `handling = ShipHandling.new(ShipHandling.getProfile())` | from `Ships[Hull.Model]` |
| 268 | `cockpit = ShipCockpit.new(playerGui, camera)` | head motion, shake (`06` 3.3) |
| 269 | `shipCamera = ShipCamera.new(camera)` | 2.3 |
| 270-273 | `audio`, `cursor`, `gravity`, `warnings` | |
| 275 | `destruction = ShipDestruction.new(camera, playerGui)` | crash cinematic |
| 276-278 | `markers`, `dust`, `planetTerrain` | `planetTerrain` = old voxel planets |
| 279-282 | `orbits = ShipOrbits.new(nil)`; `orbits:setShared(not trainingServer)` | orbits follow the server clock; the training server runs its own clock from 0 |
| 283-284 | `starLight`, `speedLines` | |
| 287-292 | `systemMap = ShipSystemMap.new(playerGui, galaxy, profile)`; `setTargetCallback(part → markers:setLocked(part))` | a click on a body locks it |
| 294-295 | `starMap = ShipStarMap.new(playerGui, galaxy, profile)`; `setCurrentSystem(1)` | |
| 296-300 | `hud = ShipHud.new(playerGui)`; `hud.attitude = ShipAttitude.new(playerGui)` | |
| 301-306 | `voice`, `autopilot`, `systems`, `ringDebris(camera)`, `warp(playerGui, camera)`, `atmosphere(playerGui)` | |
| 309-320 | `surface = ShipSurface.new(camera)`, `planetTexture`, `impostors`; `atmosphere:setSurface(surface)`; `surface:setSightSource(impostors:getSurfaceSight)` | landing scene (Roblox only) |
| 324-329 | `hull = ShipHull.new(); hull:build(workspace); hull:setInterior(true)`; `entryFx = ShipEntryFx.new(playerGui); entryFx:setHull(model)` | |
| 335-341 | `interior = ShipInterior.new(hull, playerGui, camera)`; if `interior.active`: `cockpit:setImageEnabled(false)`, `hud:setCockpitMode(true)`, `markers:setPanelVisible(false)`; `input:setLookEnabled(interior.active)` | the 3D cockpit replaces the painted image, the flat flight data and the target panel |
| 344-346 | `systems:setFuelFraction(profile:getFuel() or 1)` | only with a profile |
| 352-363 | `notices` (+ `setVoice(voice)`), `stations`, `traffic`, `trafficContext = {}`, `docking = ShipDocking.new(stations, profile, notices)`, `shipProbe = ShipStation.probeFor(hull:getModel())`, `docking:setProbe(shipProbe)`, `survey = ShipSurvey.new(profile, notices)` | |
| 374-424 | `stationUi = ShipStationUi.new(playerGui, profile, ctx)`, `deckUi`, `crowd` | ctx in 2.1.3 |
| 465-477 | `systemFolder = Folder "CelestialBodies"` in workspace; `pilots = ShipPilots.new{traffic, stations, notices, systemFolder}`, `pilots:setMode(launchMode)` | the folder must exist before the atmosphere callbacks (a later `local` would be invisible to them, `:461-464`) |
| 716-764 | `mining = ShipMining.new(profile, galaxy, notices)`; `callouts = ShipCallouts.new(voice, {atmosphere, warp, thrust, systems, warnings, hull, docking, surface, stations, traffic, survey, mining})`; `calloutFrame = {}`; `construction = ShipConstruction.new(playerGui, profile, galaxy, notices, mining, ctx)` | |
| 1749-1783 | `decks = ShipDecks.new(ctx)` | ctx in 2.1.12 |
| 1815 | `training = launchMode == "TRAINING" ? newTraining() : nil` | |
| 1974-2027 | `aboard = ShipAboard.new(ctx)` | ctx in 2.1.14 |

#### 2.1.3 Callbacks wired into other modules

| Line | Target | Callback and effect |
|---|---|---|
| 290-292 | systemMap | `setTargetCallback(part)` → `markers:setLocked(part)` |
| 535-537 | systemMap | `setSystemGetter()` → `currentSystemIndex` |
| 374-420 | stationUi | `getFuel` → `systems:getFuelFraction()`; `setFuel(f)` → `systems:setFuelFraction(f)`; `onShipChanged(id)` → `switchShip(id)`; `onLaunch` → `decks:launch()` if decks are active, else `docking:launch()`; `onClose` → `decks:onServicesClosed()`; `onDisembark` → `decks:disembark()`; `onQuarters` → `decks:goQuarters()`; `canDisembark`, `canQuarters`, `getDeck`, `hasDeck(deck)` → decks queries |
| 426-428 | docking | `getFuel` → fuel fraction |
| 429-446 | docking | `onDocked(entry, pad)`: `thrust:reset()`; autopilot engaged → `disengage("STANDBY")`; if `decks:isAvailable()` → spawn `decks:arrive(entry, pad)` and open the services only if that returns false; else `stationUi:open(entry, pad)` |
| 447-449 | docking | `onUndocked()` → `stationUi:close()` |
| 450-459 | docking | `onReleased(velocity)`: `input:centerCursor()`; `thrust.velocity = velocity`; `thrust:setThrottle(0)` (out of the hangar the pilot takes over at the computer's speed with the lever at zero: the ship coasts to a stop in front of the planet instead of flying into the barrier in 30 s) |
| 485-523 | atmosphere | `setCallbacks(onPrepare, onHit, onSwitch)`: **onPrepare(body, wanted)**: wanted → `surface:prepare(body)` (pre-build mesh data and profile), else `planetTerrain:setForced(nil, false)`. **onHit(strength)** → `audio:playHit(strength)`. **onSwitch(direction, body, pose, velocity)** (called mid-flight at the height where space and scene look alike): `"DOWN"` → `docking:cancel()`, `planetTerrain:clear()`, `traffic:clear()`, `(scenePose, sceneVelocity) = surface:begin(body, systemFolder, pose, velocity)`, `planetTexture:releaseAll()`, `markers:setVisible(false)`, return the scene pose and velocity; `"UP"` (only with an active scene) → `realPose = surface:toRealPose(pose)`, `realVelocity = surface:toRealVector(velocity) / surface:getSceneScale()`, `surface:finish()`, `markers:setVisible(true)`, `stations:forget()`, return them. **Dropped** in the seamless port |
| 676-688 | galaxy | `onChanged(index, view, old)`: only for `currentSystemIndex`: `stations:sync(index, galaxy:getSites(index), galaxy:now())`; FIRST VISIT toast (7 s) if `view.FirstVisit.UserId == player.UserId`, not shown before for this index, `old` had no FirstVisit and `galaxy:now() − visit.Time < 300` |
| 692-705 | survey | `describe(systemIndex, bodyName)` → nil unless the galaxy body is `Discovered`; `"INHABITED  ·  NO MINING"` for inhabited bodies; `"NO DEPOSITS"`; else `trait(deposits) .. "  ·  VALUE " .. letter(hourly(deposits))` (`ShipResources`) |
| 706-713 | survey | `onComplete()` → 1.5 s later, if still in the same system: `galaxy:fetch(index, 0)` (refresh in case the push is missing) |
| 717-720 | mining | `onTon()` → `notices:play("Comms")`, `voice:say("ExtractionComplete")` |
| 740-764 | construction | `getSystem`, `getLocked` (markers), `getSurfaceBody` (the scene body when the landing scene is active), `getShipCFrame`, `getDockedId` (docked station id), `findSite(id)`, `canOpen` = `notices.enabled and not warp:isTravelling()` (only after the reveal and never in a jump) |
| 834-849 | starMap | `setConfirmCallback(system)` (2.1.6) |
| 891-933 | warp | `setCallbacks(onJump, onVoice, onSound)` (2.1.6) |
| 1103, 1129 | interior | `onAction = onInteriorAction` (again for every new cockpit) |
| 1749-1783 | decks | ctx (2.1.12) |
| 1787-1814 | training | ctx = `{player, playerGui, profile, trainingServer, markers, docking, atmosphere, surface, survey, thrust, input, autopilot, stations, stationUi, warp, starMap, systemMap, decks, getSystem, getShipCFrame}` |
| 1974-2027 | aboard | ctx (2.1.14) |

#### 2.1.4 System build and spawn helpers

```text
applySkybox(def)                                                   :544-559
  sky = Lighting's Sky; first call remembers the place's six faces (Bk Dn Ft Lf Rt Up)
  chosen = ShipVisualAssets.skybox(def.Seed) or the place faces; assign the six faces      -> 03 (sky per system)

buildSystem(def)                                                   :561-578
  planetTerrain:clear(); planetTexture:releaseAll(); impostors:clear(); traffic:clear()
  systemFolder:ClearAllChildren(); ShipOrigin.reset()              -- build in universe coordinates (star at 0)
  root = ShipSystemBuilder.build(systemFolder, def); orbits:setRoot(root)
  orbits:snap()                                                    -- bodies at the shared-clock position BEFORE stations and ship
  applySkybox(def); return root                                    -- the next frame's origin shift brings the world to the ship

definitionFor(index)                                               :581-587
  target = starMap.systems[index]
  index == 1 or no target or target.isHome  -> (ShipSystemBuilder.sol(), 1)
  else -> (ShipSystemBuilder.makeProcedural(target.seed, target.planets, target.colour, target.class), index)

arrivalPoint(def)       -- universe coordinates, outside the heat AND brake zone :596-602
  danger = max(def.Star.heatRadius or 1.6*r, def.Star.massShadowRadius or 4*r)
  safe   = danger * Warp.ArrivalMargin (1.15)
  return (0, 0.06*safe, -safe)
  -- Sol: danger 92,000 -> safe 105,800 -> (0, 6,348, -105,800) studs; at S: (0, 19.04 Mm, -317.4 Mm), 318.0 Mm from the star

clearOfBodies(p)        -- world coordinates                       :608-620
  for part in CollectionService:GetTagged(Gravity.Tag "CelestialBody") inside systemFolder:
      r    = attr CollisionRadius or attr SurfaceRadius or part.Size.X/2
      away = p - part.Position
      if |away| < 1.5*r:  p = part.Position + (|away| > 1e-3 ? unit(away) : +Y) * 3*r
  return p              -- one pass, tag order; a push can land in a body checked earlier (rare)

spaceSpawnPose()                                                   :623-626
  pose = ShipOrigin.cframeToWorld(spaceSpawn); return pose.Rotation + clearOfBodies(pose.Position)

stationSpawn(id?)       -- outside a station's mouth, looking at it   :630-640
  entry = id ? stations:findById(id) : nil;  entry = entry or stations:nearest(ship position)
  nil if no entry or entry.host has no parent
  outer = entry.rootCF:PointToWorldSpace(entry.approach.Outer)
  return CFrame.lookAt(outer, entry.rootCF.Position)
```

Initial build (`:644-663`): `index = startView.Location.System` (number) or 1; `(def, index) = definitionFor(index)`; `currentSystemIndex = index`; `buildSystem(def)`; `starMap:setCurrentSystem(index)`; `galaxy:fetch(index, 8)` (waits ≤ 8 s for the shared galaxy record: player stations and build sites); `stations:build(index, systemFolder)`; `stations:sync(index, galaxy:getSites(index), galaxy:now())`; `starArrival = clearOfBodies(arrivalPoint(def))`; outside Sol: `spaceSpawn = lookAt(starArrival, starArrival + unit(starArrival))` (back to the star).

#### 2.1.5 Start placement (`:772-800`)

The rule (Josh, 2026-11-03): left docked → docked at the same station (no free pad → in front of it); left in space, landed or mid-jump → at the system's main star as after a jump, looking at it. The reported space position no longer counts. The origin is at zero after the build, so world = universe here.

1. `startInfo.docked` and `startInfo.pad` (the server reserved a pad at that station): `pose = stations:padPose(entry, pad, shipProbe.ground)`; if found → `docking:startDocked(entry, pad)` (state DOCKED, pad lamps "clamped") and `rotation:setCFrame(pose)`.
2. Else `Location.Docked` → `stationSpawn(Location.Station)`.
3. Else a profile exists → `rotation:setCFrame(cframeToWorld(lookAt(starArrival, 0)))`.
4. Else (no profile) → `spaceSpawnPose()`.

The server side (`ShipServer/ShipCommerce.lua:128-159`): a saved docked station that no longer exists resets the location to Sol, home station, docked. A brand-new save starts docked at the home station SOL-MERIDIAN (`ShipServer/ShipData.lua:80`, `ShipStations.HomeStation`), so case 1 is the normal first start; case 4 only happens without a profile (Studio offline).

#### 2.1.6 Warp: confirm gate, arrival and fallback

```text
starMap confirm(system) -> bool                                    :834-849
  surface active or atmosphere state ≠ SPACE        -> false (a landing session would continue in the new system)
  (_, lock) = stations:getLimit(ship pos); lock or docking:isBusy()
                                                    -> toast("JUMP BLOCKED", "STATION MASS LOCK  ·  FLY 6 KM AWAY", danger) ; false
                                                       (6 = ceil(Stations.DockingRange 6000 / 1000))
  home = starMap:getCurrentSystem(); return warp:begin(system, home and home.position or (0,0,0))

warp callbacks                                                     :891-933
  onJump(target): task.spawn { ok = pcall(arriveInSystem, target)
                               if not ok: currentSystemIndex = 1; docking:reset(); buildSystem(sol); starMap:setCurrentSystem(1)
                                          profile:fire("ReportJump", 1); galaxy:fetch(1, 0); stations:build/sync(1)
                                          spaceSpawn = START_SPAWN; rotation:setCFrame(spaceSpawnPose()); thrust:reset()
                               warp:markBuildDone() }          -- always: the tunnel must end even if the build failed
  onVoice(line) -> voice:say(line)
  onSound(event): EnterWarp -> audio:playCue("WarpEnter"); StartTravel -> audio:startLoop("WarpTravel")
                  StopTravel -> audio:stopLoop("WarpTravel", hard); ExitWarp -> audio:playCue("WarpExit")
                  AbortWarp -> audio:stopCue("WarpEnter")

arriveInSystem(target)            -- runs while the tunnel shows   :853-889
  index = position of target in starMap.systems (1 if absent); currentSystemIndex = index
  def = target.isHome ? sol() : makeProcedural(target.seed, target.planets, target.colour, target.class)
  docking:reset(); buildSystem(def); starMap:setCurrentSystem(index)
  profile:fire("ReportJump", index); galaxy:fetch(index, 6); stations:build(index, systemFolder); stations:sync(...)
  arrival = clearOfBodies(arrivalPoint(def))
  rotation:setCFrame(cframeToWorld(lookAt(arrival, star 0)))      -- facing the star, as after a real jump
  spaceSpawn = lookAt(arrival, arrival + unit(arrival))           -- crash recovery later faces away from the star
  thrust:reset(); markers:setLocked(nil); autopilot:disengage("STANDBY")

jumpButton()                      -- cockpit HYPERSPACE JUMP       :986-994
  warp active:  not travelling -> warp:cancel("CANCELLED")         -- a running tunnel cannot be cancelled
  else: if not starMap:confirm() -> starMap:setOpen(true)
```

#### 2.1.7 Key and button handlers

```text
toggleExternalView()                                               :937-960
  external = not shipCamera:isExternal(); shipCamera:setExternal(external)
  hull:setInterior(not external); cockpit:setVisible(not external); cursor:setExternal(external)
  input:setLookLimits(external ? (180, 60, 60) : (140, 80, 70))     -- Interior.ExternalLook* / Look*
  input:setLookEnabled(interior.active or external)
  if interior.active: interior:setExternal(external); hud:setCockpitMode(not external); markers:setPanelVisible(external)

toggleAutopilot()                                                  :964-981
  engaged            -> autopilot:disengage("STANDBY"); voice "AutopilotOff"
  elif not warp travelling and not surface active and atmosphere SPACE and autopilot:engage(markers:getLocked()):
                        input:centerCursor()       -- an old deflection would count as manual steering next frame
                        thrust.flightAssist = true -- the autopilot brakes through the flight computer
                        voice "AutopilotOn"
  elif no locked target -> voice "AutopilotNoTarget"

dockButton()          -- key K                                      :998-1004
  docked and decks active -> decks:launch()  (the lift goes up first)
  else docking:handleDockKey(ship pos)        (request / cancel / launch, 08)

watchLanding()                                                     :1011-1020
  landingWatch += 1; ticket = landingWatch
  after Atmosphere.RequestWindow (3.0) + 0.1 s: if ticket is current and atmosphere SPACE and not warnings busy
      -> voice "LandingBlocked"            -- the request window passed without a landing

landKey()             -- key L (planets only)                      :1022-1029
  docking:isControlling() -> ignore; if atmosphere:requestLanding() -> watchLanding()

landButton()          -- cockpit LANDING (lands or docks)          :1033-1044
  atmosphere:canLandNow() and not docking controlling -> atmosphere:requestLanding()   (no watch)
  elif docking:handleDockKey(pos) -> done
  elif atmosphere:requestLanding() -> watchLanding()

hudPrompt(pos)                                                     :1048-1055
  landing = atmosphere:getPrompt(); dockingPrompt = docking:getPrompt(pos)
  both and atmosphere SPACE -> landing .. "\n" .. dockingPrompt ; else landing or dockingPrompt

autopilotButton()     -- key P                                      :1059-1071
  if docking:handleComputerKey(cf, velocity):           -- the docking computer took the key (GRANTED/AUTO)
      if docking state == AUTO: input:centerCursor(); autopilot engaged -> disengage("STANDBY")
      return
  toggleAutopilot()
```

**Cockpit buttons** (`onInteriorAction`, `:1076-1103`): the same paths as the keys, so a click and a key always behave alike.

| Action | Effect |
|---|---|
| CycleTarget | `markers:cycleTarget()` |
| ClearTarget | `markers:setLocked(nil)` |
| Autopilot | `autopilotButton()` |
| SystemMap / StarMap | `systemMap:toggle()` / `starMap:toggle()` |
| Jump | `jumpButton()` |
| FlightAssist | `thrust.flightAssist = not thrust.flightAssist` |
| Boost | `thrust.boostRequested = true` |
| Land | `landButton()` |
| ExternalView | `toggleExternalView()` |
| Lights | `interior:toggleLights()` |
| Music | `audio:setMusicEnabled(not audio.musicEnabled)` |

**Ship switch** (`switchShip(shipId)`, `:1108-1150`, called by the shipyard while docked): same id → nothing; not available (model or config missing) → warn + toast "SHIP NOT AVAILABLE" / "<ID> IS NOT IN THIS BUILD YET" (danger). Else: `aboard:onShipChanged()` (a walker sits down: pocket and ramp belong to the old hull); `Hull.Model = shipId`; remember `external`; `interior:destroy()`; `hull:build(workspace)`; `hull:setInterior(not external)`; new `ShipInterior` with `onAction`; `interior:setExternal(true)` when external; `interior:boot()`; `handling = ShipHandling.new(getProfile())`; `shipProbe = probeFor(model)`; `entryFx:setHull(model)`; `docking:setProbe(shipProbe)`; `systems:setFuelFraction(profile:getFuel(shipId) or 1)` (each ship has its own tank); `stations:forget()`; `decks:onShipChanged()`; a walkable model (attribute `Walkable`) shows the toast "WALKABLE INTERIOR" / "PRESS 1 TO LEAVE THE SEAT AND WALK AROUND ON BOARD." for 6 s. The clamps put the new hull on the pad in the next frame (pad height from its model).

**Global key handler** (`UserInputService.InputBegan`, `:1152-1190`), ignoring `processed` input (GUI focus, chat):
1. `Keys.LeaveSeat` (1) → `standUp()` — also on decks and aboard.
2. `Runtime.Deck` or `Runtime.Aboard` → stop (no flight keys on foot).
3. `Keys.Land` (L) → `landKey()`; `Keys.Dock` (K) → `dockButton()`; `Keys.ExternalView` (V) → `toggleExternalView()`; `Keys.Autopilot` (P) → `autopilotButton()`.

The other keys are bound inside their modules (8.1).

#### 2.1.8 Character, ready handshake and welcome (`:1192-1254`)

```text
tryAttach(character)                    -- never inside the render step: attach() yields
  aboard active and character ≠ rig.character -> aboard:reset()   (a new character sits at the helm again)
  task.spawn {
    wait for Humanoid (≤ 10 s)
    once: controlsDisabled = ShipRig.disableDefaultControls(player); ShipCamera.disableDefault(player)
    if rig:attach(character):
        rig:setCFrame(rotation:getCFrame())
        once (welcomed): after Voice.WelcomeDelay (5 s): voice "Welcome", then "SystemsOnline"   -- the queue keeps the order
  }
player.CharacterAdded -> tryAttach; an existing character is attached at once
task.spawn { ShipCamera.disableDefault(player); ShipCore.ShipReady:FireServer() }   -- the server spawns the character only now
```

Port: no character, no handshake. The welcome lines play **5 s after the reveal**, once per app session.

#### 2.1.9 Frame-local state (`:1256-1290`)

| Variable | Initial | Meaning |
|---|---|---|
| `lastFlightAssist` | `thrust.flightAssist` | FA voice on change (C44) |
| `mouseWanted` | true | the game wants the mouse captured (C52) |
| `wasTravelling` | false | recentre the cursor when warp travel starts or ends (C6) |
| `attachWatchdog` | 0 | seconds without a ready rig; re-attach every 0.5 s |
| `originShifts` | 0 | number of origin shifts (tests only) |
| `cursorHidden` | false | last flight-cursor visibility; `nil` forces a re-apply next frame |
| `interiorState`, `surveyContext`, `miningContext`, `calloutFrame`, `trafficContext`, `destructionState` | `{}` | one reused table each (no allocation) |
| `galaxyTimer` / `GALAXY_REFRESH` | 0 / **2 s** | refresh player stations and build sites by server time |
| `reportTimer` / `REPORT_INTERVAL` | 0 / **10 s** | position report |
| `barrierHeldAt` / `BARRIER_NOTICE_PAUSE` | −∞ / **4 s** | MINIMUM ALTITUDE toast only on first contact |
| `edgeHeldAt` | −∞ | SYSTEM EDGE toast at most every **30 s** |
| `shipLanded` | false | resting on ground this frame (rays); allows standing up, drives touchdown sound and callouts |
| `crashStationId` | nil | recovery station chosen at the crash |
| `debugCrashSlow` | 1 | slow motion for the next crash (debug) |
| `spaceHidden`, `aboardSaved`, `flightUiSaved` | nil | saved GUI states while hidden (decks, aboard, cinematic) |
| `aboardHints` | 0 | the ON FOOT hint shows the first 2 times |
| `deckShipCFrame`, `deckWalking` | nil, false | deck frame caches |
| `landingWatch` | 0 | ticket for `watchLanding` |
| `firstVisitShown` | `{}` | FIRST VISIT toast once per system index |

`groundClearance()` (`:1287-1290`) = `max(Terrain.GroundClearance 12, shipProbe.ground + 0.3)`: the eye's height above the lowest point of the landing feet plus a breath of air (a fixed value buried large ships to the belly).

#### 2.1.10 Crash flow

```text
nearestStation(p)                       -- computed from the host: on the ground place() does not run    :1308-1320
  argmin over stations:getEntries() with a parented host of |host.Position + entry.offset - p|

crashHull(shipCF) -> (parts{part, cframe}, centre, size)   -- visible outer hull parts NOW, for the debris   :1325-1364
  skip: no parent, "Plume", Transparency >= 0.9, cockpit model parts, forceHidden, stowed gear parts,
        closed gear doors while the gear is out
  cframe = shipCF * hull.offsets[i] (hidden parts rest at the old seat) or part.CFrame
  ship-space AABB of every part (|R|·half extents); none -> (shipCF.Position, (40, 16, 60))

setFlightUiHidden(hidden)               -- called EVERY cinematic frame (modules re-show themselves)    :1369-1412
  true:  close system map and star map; markers, cursor, speedLines, dust invisible
         first call: remember every enabled ScreenGui except warnings.gui and "ShipDestroyed"; disable them
  false: re-enable the remembered GUIs; markers visible unless the landing scene is active; speedLines, dust visible;
         cursorHidden = nil

startCrash(cause, gravityState, hit?)   -- cause HEAT | GROUND | STATION    :1474-1666
  warnings busy or destruction active -> return
  warp active and not travelling -> warp:cancel("DESTROYED");  atmosphere:abort();  aboard:reset()
  voice:interrupt(); voice "HullFailure"
  realPos = landing scene ? surface:toRealPose(shipCF).Position : shipCF.Position
  crashStationId = nearestStation(realPos).def.Id
  lostKeys = "<System>/<Body>" of every unsold survey record on board
  if Destruction.Enabled:
     shipName = upper(Ships[Hull.Model].DisplayName or Hull.Model or "SHIP")
     where = HEAT:    "BURNED UP NEAR <BODY>"  | "BURNED UP"
             STATION: "COLLIDED WITH <STATION>" | "COLLIDED WITH A STATION"
             else:    "CRASHED ON <BODY>"       | "CRASHED"        (<BODY> = scene body or gravityState.bodyName)
     up/pull/ground/dust: scene -> surface up and gravity, ground+dust; GROUND in space -> away from the body,
                          gravityVector or -up*Physics.StudsPerG, ground+dust; STATION -> hit.normal, ground
     destruction:start{cframe, center, velocity, up, gravity, ground, dust, floor(p) (scene altitude), colour(p) (scene image),
                       parts, size, exclude {character, hull model}, detail = shipName .. "  ·  " .. where,
                       station = upper(station name), total = Duration + FadeOutTime (12.2 s), timeScale = slow,
                       onCue: "Title" -> voice "ShipDestroyed", then the loss lines; "Recovery" -> voice "ReplacementReady"}
     started -> setFlightUiHidden(true); hull:setVisible(false); audio:setMuted(true); audio:setMusicHush(true)
  task.spawn: result = profile:call("ReportDestroyed")          -- 08: fee = min(credits, round(price*RebuyFraction))
     ok -> unchart lostKeys; lines = {"REPLACEMENT FEE <cr>"} (+ "%d T CARGO LOST", "DATA LOST <cr>")
           voice lines {ReplacementFee if fee > 0, CargoLost, SurveyDataLost}, said after the title (or at once without cinematic)
           text = join(lines, "  ·  "); cinematic running -> destruction:setLosses(text) else toast("SHIP DESTROYED", text, danger, 7 s)
  started -> warnings:crash(respawnAfterCrash, {delay = Duration/max(slow, 0.05), fadeOut = 1.2, hold = 1.4, fadeIn = 1.2})
  else    -> warnings:crash(respawnAfterCrash)                   -- Warnings.Crash: fade 0.30, hold 1.6, fade in 0.9

respawnAfterCrash()                     -- runs in the black                                   :1419-1467
  cinematic = destruction:isActive(); destruction:finish()
  cinematic -> hull:setVisible(true); audio:setMuted(false); audio:setMusicHush(false)
  traffic:clear(); thrust:reset(); systems:reset() (fuel full, heat 0, G 0); docking:reset(); stations:forget()
  interior:reboot(); voice "Rebooting"; autopilot engaged -> disengage("STANDBY"); input:centerCursor()
  landing scene -> realPose = surface:toRealPose(ship); surface:finish(); atmosphere:resetToSpace(); rotation:setCFrame(realPose)
  stations:setVisible(true); stations:place(0, ship pos)          -- fresh at their hosts (they stood still on the ground)
  spawn = stationSpawn(crashStationId or Location.LastStation or Location.Station)
  rotation:setCFrame(spawn or spaceSpawnPose()); crashStationId = nil; stations:forget()
  setFlightUiHidden(false); markers:setVisible(true)
  after (cinematic ? Destruction 1.4 + 1.2 : Warnings.Crash 1.6 + 0.9) s: not destruction active -> voice "SystemsOnline"
```

A crash on the ground always ends the landing session: the recovery is in space.

#### 2.1.11 Pitch angle (`getPitchAngle`, `:1674-1688`)

```text
up = landing scene ? surface:getUp(pos)
   : gravityState.body ? unit(pos - body.Position) (if |·| > 1e-3)
   : nil
up == nil -> (nil, nil)                       -- far from any body the angle means nothing; displays stay empty
return (deg(asin(clamp(look · up, -1, 1))), up)
```

Used by the HUD, the cockpit screens and the attitude indicator (`localUp` is the attitude frame's "up").

#### 2.1.12 Deck glue (`:1695-1783`, `deckFrame` `:2090-2239`)

While the pilot is on a station deck the flight world rests: no solar system, no stations, no flight displays, no flight loop.

```text
hideSpace()   (once)                                                        :1698-1720
  spaceHidden = {markers = markers.gui.Enabled, hud = hud.gui.Enabled, warnings = warnings.gui.Enabled}
  systemFolder.Parent = nil; traffic:clear(); stations:setVisible(false)
  markers, cursor, speedLines, dust invisible; hud.gui and warnings.gui disabled
showSpace()                                                                 :1722-1747
  systemFolder back unless the landing scene is active; stations visible; markers as saved; speedLines, dust visible
  hud, warnings as saved (default on); cursorHidden = nil; interior.gui enabled
decks ctx: {player, profile, rig, notices, deckUi, audio, getHull, getProbe, hideSpace, showSpace,
            openServices(onFoot) -> stationUi:open(docking:getStation(), docking:getPad(), onFoot),
            closeServices -> stationUi:close(), launchFlight -> docking:launch(), isDocked, isExternal}
```

`deckFrame(dt)` is listed step by step in 2.2 (branch A).

#### 2.1.13 Training (`:1787-1815`)

`newTraining()` builds `ShipTraining` with the ctx of 2.1.3; it exists only in TRAINING mode and starts in `afterReveal`. It runs on its own `Heartbeat` (`ShipTraining.lua:1158`). Port: `09` 2.10; `DECISIONS.md` D28 makes it optional.

#### 2.1.14 Walking aboard glue (`:1825-2083`)

```text
hideFlightUi()   (once)                                                     :1828-1857
  aboardSaved = {markers, hud enabled, flightAssist}
  close maps; construction:close(); markers, cursor, speedLines, dust, cockpit invisible
  hud.gui and interior.gui disabled; interior:setPointer(nil)
showFlightUi()                                                              :1859-1884
  unless spaceHidden (decks restore everything on launch): markers as saved, speedLines, dust visible, hud as saved
  thrust.flightAssist = saved; cockpit visible unless external; hull:setInterior(not external)
  interior.gui enabled unless walking on a deck; cursorHidden = nil; input:centerCursor()
canStand() -> (ok, reason?)                                                 :2032-2064
  aboard active                                  -> false
  model not Walkable                             -> false, "THIS SHIP HAS NO ROOM TO WALK AROUND."
  training active                                -> false, "FINISH THE FLIGHT TRAINING FIRST."
  warnings busy or atmosphere busy (ENTRY/EXIT)  -> false
  decks active                                   -> deck == HANGAR and decks:canDisembark()
  docking busy                                   -> false, docked ? nil : "STAY AT THE HELM WHILE DOCKING."
  landing scene or atmosphere SURFACE            -> shipLanded and speed < 2 ? true : (false, "SET THE SHIP DOWN FIRST.")
  atmosphere ≠ SPACE                             -> false
  warp travelling or autopilot engaged or speed < Aboard.MaxStandSpeed (30) -> true
  else                                           -> false, "SLOW DOWN OR ENGAGE THE AUTOPILOT FIRST."
standUp()                                                                   :2066-2083
  not ok -> reason ? toast("CAN'T STAND UP", reason, danger, 4 s); return
  input:centerCursor(); task.spawn { (done, why) = aboard:stand(); not done and why -> same toast }
aboard ctx: {player, rig, profile, notices, deckUi, getHull,
             getSlot -> player attribute DeckSlot or 1,
             canOpenRamp -> decks active, deck HANGAR, not busy ? true : (false, "THE RAMP ONLY OPENS IN A STATION HANGAR."),
             walkOut(_, view) -> decks:walkOut(view), walkIn -> decks:walkIn(), getStatus = aboardStatus,
             getCargo -> (cargo used, ship CargoCapacity),
             onStand(fromDeck): hideFlightUi(); decks active -> stationUi:close();
                                not fromDeck and aboardHints < 2 -> aboardHints += 1;
                                toast("ON FOOT", "WASD WALK  ·  SPACE JUMP  ·  R-MOUSE LOOK  ·  WHEEL ZOOM  ·  E AT THE HELM TO SIT DOWN", 7 s)
             onSit(seated): showFlightUi(); seated and decks seated and not busy -> stationUi:open(entry, pad, false)}
```

`aboardStatus(kind)` (`:1888-1972`) feeds the walk-up terminals aboard (texts in 5.4).

#### 2.1.15 Reveal (`:3208-3264`)

```text
if the menu (or arrival cover) has reveal():
   task.spawn { wait for PlayerGui.MainMenu (≤ 8 s); wait 2 RenderStepped; pcall(menu:reveal)   -- 0.6 s Sine Out (cover 0.8 s)
                interior:boot()                     -- the cockpit boots while the curtain lifts
                afterReveal() }
else: interior:boot(); afterReveal()
afterReveal():
   notices:setEnabled(true)                          -- toasts, comms only from now on
   docked -> decks:isAvailable() ? spawn { decks:arrive(entry, pad, instant = true) or stationUi:open(entry, pad) }
                                 : stationUi:open(entry, pad)
   training -> training:start()
   OPEN join with a friend id -> after 3 s a toast about where the friend is (multiplayer: dropped)
ShipMenu (07): after run() the curtain stays black (DisplayOrder 1000 above every flight GUI);
               if reveal() has not come within 12 s (REVEAL_TIMEOUT), the curtain opens by itself
```

#### 2.1.16 Debug access (`:3270-4025`)

`_G.Ship` exposes every module (Studio console). In Studio a `BindableFunction` "ShipDebug" in PlayerScripts runs fixed commands (no free code). They are the model for the Kotlin `debugCommand` set and the desktop harness (9.6). Groups: **state** (`state` returns ~45 fields: world and universe position, origin, shifts, look, speed, throttle, FA, input enabled, heat, fuel, crashing, atmosphere, surface, system parented, warp, autopilot, locked, star, textures, impostors, traffic, music, maps, cursor, capture, system index, mass, top speed, agility, cruise, boost, G, turn rate, velocity, external, look, docking, station frame, limit, zone, credits, cargo, ship, services); **flight** (`stick x y` holds a cursor deflection in radius fractions, `cursor x y` in px, `look x y` holds the head, `throttle v`, `flightAssist on`, `boost`, `autopilot`, `addHeat v`, `gear force legTime doorTime`); **places** (`near body alt`, `teleport x y z [look]` in universe coordinates, `station id distance`, `stationView id distance dx dy dz`, `site distance dx dy dz`, `lock body`, `align`); **landing** (`land`, `ascend`, `landing`, `terrain`); **crash** (`crash ["dive"] [slow]`, `destruction`); **warp** (`warp index`, `warpBroken` proves the tunnel still ends in Sol after a failed build); **cockpit** (`interior`, `press action`, `reboot`); **docking and trade** (`docking`, `request`, `key Land|Dock|Autopilot`, `computer`, `launch`, `call name …`, `profile`, `page name`, `ship id`, `survey [body]`); **on foot** (`aboard …`, `decks`, `arrive`, `disembark`, `travel deck`, `board`, `interact kind`, `deckGo`, `deckCamera`); **world** (`galaxy`, `mining`, `panel tab`, `traffic kind`, `pilots`, `performance`, `attitude`, `training…`, `musicNext`). The server has its own Studio hook (2.13).

---
### 2.2 The per-frame order (`ShipUpdate`, `ShipClient.lua:2321-3196`)

Bound with `RunService:BindToRenderStep("ShipUpdate", Enum.RenderPriority.Camera.Value, fn)`: once per rendered frame, after input processing and before rendering. **Port column:** *keep* = port as is (owner doc in brackets), *new* = changed for the port, *drop* = Roblox-only. "prev" marks a value of the previous frame.

#### 2.2.1 Branch selection

| # | Lines | Call | Notes |
|---|---|---|---|
| F0 | 2324 | `dt = min(dt, 0.1)` | tab-outs and load pauses must not throw the ship forward |
| — | 2327 | `decks:isActive()` → **branch A**, then `return` | |
| — | 2337 | `destruction:isActive()` → **branch B**, then `return` | |
| — | 2344 | otherwise **branch C** (the flight frame) | |

#### 2.2.2 Branch A: on a station deck (`:2327-2334`, `deckFrame` `:2090-2239`)

| # | Lines | Call | Reads | Writes / effect | Port |
|---|---|---|---|---|---|
| A1 | 2328 | `impostors:hide()` | | far copies off | drop |
| A2 | 2329 | `hud.attitude:hideNow()` | | attitude indicator off at once | keep (`02`) |
| A3 | 2091 | `decks:update(dt)` | | lift, hatch, scenes, NPCs | keep (`09`) |
| A4 | 2092-2096 | not `decks:isActive()` any more → `deckShipCFrame = nil`; **return** (skips A5-A16 this frame) | | | keep |
| A5 | 2097-2113 | `shipCFrame = decks:getShipCFrame()`; `walking = decks:isWalking()`; on a change of `walking`: walking → `mouseWanted = false`, `input:setWanted(false)`, `interior:setPointer(nil)`; `interior.gui.Enabled = not walking and not aboard active` | deck mode | mouse, pointer, cockpit hint GUI | new (input owner) |
| A6 | 2115-2124 | **walking:** `hull:update(shipCFrame, 0)` only when the parked pose changed (a ship switch); in the HANGAR and not busy: `aboard:watchRamp(shipCFrame)` (walking up the open ramp boards without a fade) | ship pose | hull parts | keep (`09`) |
| A7 | 2128-2131 | **seated:** `onFoot = aboard:isActive()`; not on foot → `input:update(dt)` | mouse, keys | cursor, look | keep |
| A8 | 2132-2147 | anchor: on foot → watchdog 0; rig ready → `rig:setCFrame(shipCFrame)`, `rig:hideLocally()`; else watchdog += dt, ≥ 0.5 s → `tryAttach` | | character | drop |
| A9 | 2149-2207 | `interior.active`: pointer = not external and not on foot ? (looking ? `"center"` : (mouse free and services closed ? `"mouse"` : nil)) : nil; `interior:setPointer`; **parked `interiorState`**: speed 0, throttle 0, aim 0, roll 0, cursor 0, `cursorShown false`, FA, `boosting/cruising false`, boost cooldown, fuel, heat, G, `scooping false`, mass, agility, `gearDown true`, `locked nil`, `bodies {}`, `massLocked false`, `timeToImpact nil`, `pitchAngle 0`, `autopilotEngaged false`, autopilot status, `atmosphereState "SPACE"`, `surface false`, `warpActive false`, warp state, `travelling false`, maps closed, external, music, `crashing false`, **`altitude nil`** (no stale "5.15 km" in the hangar), `gravityG 0`; `interior:update(dt, s)` | systems, handling, warp, audio | cockpit screens | keep (`06` 2.2) |
| A10 | 2208-2211 | `hull:setGearDown(true)`; `hull:update(shipCFrame, 0)`; `deckShipCFrame = shipCFrame`; `cockpit:update(dt, 0, 0, 0)` | | gear, hull, head motion settles | keep |
| A11 | 2212-2220 | camera: on foot → `aboard:frame(dt, shipCFrame)`; else `shipCamera:setShakeOffset(cockpit:getCameraShake())` (no entry shake), `setHead(cockpit:getHeadOffset(), input:getLook())`, `update(shipCFrame)`, `aboard:settleRamp()` (ramp closed at the helm and in the lift) | | camera | keep |
| A12 | 2222 | `entryFx:update(dt, shipCFrame, 0)` | | entry glow fades out | keep |
| A13 | 2226-2233 | `wantMouse = not onFoot and (not stationOpen or input.lookHeld) and not training:wantsCursor() and not construction:wantsCursor()`; on change: `input:setWanted`, `input:centerCursor()`; `stationUi:setLooking(stationOpen and looking)` | services open, RMB | capture | new (input owner) |
| A14 | 2235-2238 | `notices:update(dt)`; `stationUi:update(dt)`; `construction:update(dt)`; `voice:update(dt)` | | | keep |
| A15 | 2332 | `updatePilots(dt, nil, "DOCKED", true)` | | | drop |

#### 2.2.3 Branch B: crash cinematic (`:2337-2342`, `destructionFrame` `:2249-2281`)

| # | Lines | Call | Effect | Port |
|---|---|---|---|---|
| B1 | 2338 | `hud.attitude:hideNow()` | | keep |
| B2 | 2251 | `destruction:update(dt)` | camera, debris, plate | keep (`05` 2.7) |
| B3 | 2252 | `setFlightUiHidden(true)` | every frame (2.1.10) | keep |
| B4 | 2253-2265 | landing scene → `surface:step(dt, pos, false)` (no re-anchoring: the wreck lies in the scene); else `trafficContext.active = false` (NPC ships leave and fade), `traffic:update(dt, ctx)` | | keep traffic (`09`); drop scene |
| B5 | 2267-2270 | `rig:setCFrame(ship)`, `rig:hideLocally()` (the invisible character stays at the crash site) | | drop |
| B6 | 2271 | `entryFx:update(dt, ship, 0)` | glow fades | keep |
| B7 | 2273 | `surface:afterCamera(camera.CFrame)` | | drop |
| B8 | 2274-2278 | scene → `impostors:hide()` else `impostors:update(dt, camera, systemFolder, false)` | | drop |
| B9 | 2279 | `warnings:update(dt, destructionState)` (an empty table: no warnings) | blackout timeline continues | keep |
| B10 | 2280 | `voice:update(dt)` | | keep |
| B11 | 2340 | `updatePilots(dt, nil, "DOWN", true)` | | drop |

#### 2.2.4 Branch C: the flight frame (`:2344-3195`)

| # | Lines | Call | Reads | Writes / effect | Port |
|---|---|---|---|---|---|
| C1 | 2346-2349 | `onFoot = aboard:isActive()`; not on foot → `input:update(dt)` | mouse delta (captured only), RMB, Q/E (not while typing), settings MouseSensitivity, InvertPitch | `cursorPixels` (clamped to the radius), `look`, `looking`, `keyAxis` | new: `InputFrame` build (9.2) |
| C2 | 2354 | `travelling = warp:isTravelling()` (TUNNEL or ARRIVE) | warp | | keep |
| C3 | 2356 | `controlled = docking:isControlling()` (AUTO, CLAMPING, DOCKED, LAUNCHING) `or atmosphere:isGuiding()` (a guidance phase runs) | | | keep; guidance → landing assist (D14) |
| C4 | 2357 | `thrust:setInputEnabled(not travelling and not controlled and not onFoot)` | | `thrust.inputEnabled` (keys read as 0 when off) | keep (`02`) |
| C5 | 2358-2360 | controlled or on foot → `thrust.boostRequested = false` | | | keep |
| C6 | 2361-2364 | `travelling ≠ wasTravelling` → remember, `input:centerCursor()` | | cursor 0 | keep |
| C7 | 2375-2381 | `pilotAim = input:getNormalizedCursor()` (px / radius, Y down); `pilotKeys = input:getKeyAxis()` (+1 = Q); `steering = not onFoot and (‖pilotAim‖ > Autopilot.OverrideDeflection 0.5 or pilotKeys ≠ 0)`; `aim`, `keyAxis` = 0 when travelling, controlled or on foot, else the pilot's; `cursorRolls = input:isMouseRoll()` | input, settings MouseYaw | `aim`, `keyAxis`, `steering`, `cursorRolls` | keep (`02`) |
| C8 | 2384 | `handling:update(dt, thrust:getSpeed() prev, systems:getFuelFraction(), profile:getCargoUsed())` | | mass, agility, top speed, overload | keep (`02` 3.2) |
| C9 | 2389-2393 | autopilot engaged and (scene active or atmosphere ≠ SPACE) → `disengage("ATMOSPHERE")`, `thrust:setThrottle(0)`, voice "AutopilotAtmosphere" | | | keep; condition becomes "inside an atmosphere / near band" |
| C10 | 2395-2397 | autopilot engaged and controlled → `disengage("STANDBY")` | | | keep |
| C11 | 2399-2423 | engaged → `(autoCursor, autoThrottle, autoRoll) = autopilot:compute(dt, ship pose, speed, thrust.speedLimit prev, handling)`; it switched itself off → `setThrottle(0)`, voice "AutopilotTargetReached" (status ARRIVED) or "AutopilotOff"; else if `Autopilot.DisengageOnInput` and (`‖aim‖ > 0.5` or `keyAxis ≠ 0` or FA off) → `disengage("MANUAL OVERRIDE")`, voice "AutopilotManualOverride"; else `aim = autoCursor`, `keyAxis = autoRoll or 0`, `cursorRolls = false`, `thrust:setThrottle(autoThrottle)` | | aim, keyAxis, throttle | keep (`02` 3.7) |
| C12 | 2427-2430 | on foot and no autopilot → `thrust:setThrottle(0)`, `thrust.flightAssist = true` (nobody at the helm: FA brakes and holds) | | | keep |
| C13 | 2432 | `rotation:update(dt, aim, keyAxis, handling, thrust.flightAssist, cursorRolls)` | | orientation, rates, input amounts | keep (`02` 3.3) |
| C14 | 2435 | `orbits:update(dt)` | clock | body positions | keep (`03` 2.9) |
| C15 | 2438-2443 | no landing scene → `carry = stations:getCarry()` (`host.Position + offset − lastPosition` of the station whose frame the ship is in); ≠ 0 → `rotation:shiftBy(carry)` (rates kept) | stations | ship position | new: generalised frame carry (D6) |
| C16 | 2447-2448 | `shipCFrame = rotation:getCFrame()`; `gravityState = gravity:getState(pos, thrust.velocity prev)` | bodies | a **new table each frame** (body, altitude, speedLimit, limitZone, gravityVector, heat, timeToImpact, …) | keep (`02` 3.5), reused record |
| C17 | 2453-2458 | `moved = surface:step(dt, pos, not onFoot and speed > 1)`; moved → `rotation.cframe = moved * cframe`, `thrust.velocity = moved:VectorToWorldSpace(velocity)` (scene re-anchor) | | | drop |
| C18 | 2465-2482 | landing scene → overwrite gravityState: altitude over ground, gravityG and surfaceGravityG = scene g, gravity vector, `speedLimit = surface:getSpeedLimit(alt)`, `timeToImpact = ∞`, `closingSpeed 0`, `belowSurface false`, `inInfluence true`, `inMassShadow false`, `heat 0`, `starAltitude ∞`, `landingBlocked nil`, `bodyName`, `limitZone "landing"` | | | drop → near-ground law (`02` 9.5, D3) |
| C19 | 2483-2529 | space: (a) not `atmosphere:isOnSurface()` → `timeToImpact = ∞` (the barrier holds softly, no impact warning); (b) `(stationLimit, lock) = stations:getLimit(pos)`: lock → zone `"station"` if lower, `speedLimit = min(…)`, `inMassShadow = true`; (c) else `(approachLimit, cos) = stations:getApproachLimit(pos, velocity)`: zone `"cruise"` + `limitCosine` if lower, `speedLimit = min(…)`; (d) `stations:getFrame()` → `gravityVector = 0`; (e) `ux,uy,uz = ShipOrigin.toUniverse(pos)`; `edgeLimit = ShipGravity.edgeLimit(ux, uy, uz, velocity)`; finite → zone `"edge"` if lower, `speedLimit = min`; `edgeLimit < speed` → if `now − edgeHeldAt > 30` toast SYSTEM EDGE (6 s), `edgeHeldAt = now` | stations, universe position | limit, zone, mass shadow, gravity | keep (`02`, `08`); (a) changes (impact warnings on, D16) |
| C20 | 2531 | `thrust.speedZone = ShipGravity.zoneLabel(limitZone, limitCosine)` | | label | keep |
| C21 | 2533 | `thrust:setSpeedLimit(gravityState.speedLimit, limitZone == "surface")` (immediate in the approach zone) | | | keep |
| C22 | 2534-2538 | `thrust:setGravity(scene ? surface:getGravity(pos) : gravityVector or 0)` | | | keep |
| C23 | 2541 | `delta = thrust:update(dt, shipCFrame, handling)` | W/S, A/D, R/F, Space/L-Ctrl, X (when input is enabled), FA, boost request, limit, gravity | throttle, velocity, accelerations, cruising, boosting, `boostStarted`; returns the world delta | keep (`02` 3.4) |
| C24 | 2542-2546 | `boostStarted` → clear, `systems:addHeat(handling.profile.Boost.Heat)` | | heat | keep |
| C25 | 2548-2553 | `guiding = atmosphere:isGuiding()`; `hitNormal = rotation:translate(guiding ? 0 : delta, not scene)` (clamps to `Ship.FieldRadius` in universe coordinates); hit → `thrust:cancelVelocityAlong(hitNormal)` | | position | keep |
| C26 | 2557-2580 | guiding → `(pose, velocity, released) = atmosphere:guide(dt, ship, velocity)`; pose → `setCFrame` (rates 0), velocity, `thrust.acceleration`, `feltAcceleration` from the guidance; scene active now → `gravityState.altitude = surface:getAltitude`; released → `input:centerCursor()`, `thrust:setThrottle(throttleFor(speed, min(Thrust.CruiseMaxSpeed, limit), topSpeed))` | | pose, velocity | new: landing assist (D14) |
| C27 | 2588-2589 | `systems:update(dt, gravityState.heat (star heat 0..1), throttle, thrust:getLocalAcceleration(ship))`; then **`gravityState.heat = systems:getHeat()`** (ship heat 0..2.2; the field changes meaning) | | fuel, heat, G | keep (`02` 3.6) |
| C28 | 2594-2699 | crash checks, `shipLanded = false` first: (a) ship heat ≥ `Overheat.FatalHeat 2.0`, not busy → `startCrash("HEAT")`; (b) scene: `clampPosition` (region edge), ground ray down `Terrain.GroundProbe 400` (floor params), analytic `floorHit` if the ray missed or hit > 2 below it; within `groundClearance()` → `impact = max(−v.y, −(v·n)·0.5)`; `impact > Surface.SafeLandingSpeed 38` on solid or image ground → `startCrash("GROUND")`, else remove the into-ground velocity and set `hit + n·clearance`; `shipLanded = height < clearance + 0.6`; (c) old surface: ray towards the body centre; `closingSpeed > Gravity.SafeLandingSpeed 20` → crash, else hold; `shipLanded`; (d) `belowSurface` and not guiding → **barrier**: remove only the inward velocity, `setPosition(body + outward·(surfaceRadius + 0.5))`, toast MINIMUM ALTITUDE (3 s) if > 4 s since the last hold | | pose, velocity, `shipLanded` | new: seamless ground contact (`04` 9.10, `02` 9.5); heat death keep |
| C29 | 2706-2713 | **floating origin**: no scene, no voxel terrain, `‖pos‖ > Origin.RebaseDistance 1` → `shift = ShipOrigin.shift(pos)` (whole studs; listeners e.g. ShipTraffic), `rotation:shiftBy(−shift)`, `ShipOrigin.moveParts(systemFolder, −shift)` (BulkMoveTo of every body part), `originShifts += 1` | | world shifted | drop (2.5) |
| C30 | 2718-2748 | stations: scene → `setVisible(false)`; else `setVisible(true)`; `stations:place(dt, pos)` (absolute host + offset, after the shift); `(dockPose, dockVel) = docking:update(dt, ship, velocity, steering)`; dockPose → set pose and velocity; else not busy → `hit = stations:collide(ship, velocity, shipProbe)`: `hit.speed > Stations.CrashSpeed 45` → `startCrash("STATION", gs, hit)`; else slide: `setPosition(hit.position)`, `cancelVelocityAlong(−hit.normal)`, speed > 4 → `audio:playHit(clamp(speed/45, 0.15, 1), 0.3)`, speed > `Voice.Callouts.HullImpactSpeed 12` → voice "HullImpact"; finally `stations:remember(ship)` (pose relative to every near station for the next swept test) | stations, docking | pose, velocity | keep (`08` 2.4-2.5) |
| C31 | 2752-2761 | `trafficContext.active = not travelling, not controlled, not onFoot, warp IDLE, docking FREE, no scene, atmosphere SPACE, no training, not busy`; ship, velocity, `sight = impostors:getSight()`, stations, system index; `traffic:update(dt, ctx)` | | NPC ships | keep (`09` 2.8); sight = constant |
| C32 | 2764-2768 | `updatePilots(dt, ship, status, hidden)` (JUMPING / DOCKED / SURFACE / FLYING) | | | drop (multiplayer) |
| C33 | 2772-2787 | anchor: on foot → watchdog 0; rig ready → `rig:setCFrame(ship)`, `rig:hideLocally()`; else watchdog ≥ 0.5 s → `tryAttach` | | character | drop |
| C34 | 2790-2793 | `speed = thrust:getSpeed()`; `speedFraction = clamp(speed / Thrust.MaxSpeed 1000, 0, 1)`; `mapOpen`; `(pitchAngle, localUp) = getPitchAngle(ship, gravityState)` | | | keep |
| C35 | 2797-2802 | `looking = input:isLooking()`; `hideCursor = looking or docked or onFoot`; on change `cursor:setVisible(not hideCursor)` | | flight arrow visibility | keep |
| C36 | 2806-2878 | `interior.active`: pointer = no map, not on foot, cockpit view ? (looking ? `"center"` : (mouse free and services closed ? `"mouse"` : nil)) : nil; `interior:setPointer`; fill `interiorState` (`06` 2.2; stick axes swapped when the autopilot steers while the mouse rolls); `interior:update(dt, s)` — **before** `hull:update`, which moves all parts in one batch | everything | cockpit screens, levers | keep (`06` 3.4) |
| C37 | 2880 | `hull:update(ship, throttle, boosting)` | | hull, gear step, plumes | keep (`06` 3.5) |
| C38 | 2882-2884 | `hull:setGearDown((scene and alt < Hull.GearDownAltitude 60) or docking:wantsGear(ship))` | | gear command | new: altitude over terrain (`02` 3.8) |
| C39 | 2885 | `cockpit:update(dt, thrust:getLocalAcceleration(ship), rotation:getAngularVelocity() (deg/s), speedFraction)` | | head offset, shake | keep (`06` 3.3) |
| C40 | 2889-2897 | camera: on foot → `aboard:frame(dt, ship)`; else `shipCamera:setShakeOffset(cockpit:getCameraShake() * entryFx:getShake())`, `setHead(cockpit:getHeadOffset(), input:getLook())`, `shipCamera:update(ship)`, `aboard:settleRamp()` | | camera pose | keep (2.3) |
| C41 | 2901-2904 | `entryHeat = onFoot ? 0 : atmosphere:getHeat()`; `entryFx:update(dt, ship, entryHeat, colour = ShipEntryFx.colourFor(bodyName) or atmosphere:getHeatColour())` (after the camera: the effects hang on it) | | entry glow | keep (`05` 2.5) |
| C42 | 2906 | `surface:afterCamera(camera.CFrame)` | | scene copies squashed about the eye | drop |
| C43 | 2909-2928 | `forwardAccel = thrust:getForwardAcceleration()`; `audio:update(dt, {throttle, speed, maxSpeed 1000, forwardAccel, rotationAmount, rollAmount, topSpeed, velocity, boosting, cruising, flightAssist, dockState, landed = shipLanded, warp = travelling, crashing})` (**a new table each frame**) | | sound layers, events | keep (`09` 2.12), reused record |
| C44 | 2930-2935 | FA changed → voice "FlightAssistOn" / "FlightAssistOff" | | | keep |
| C45 | 2939 | `warnings:update(dt, gravityState)` | impact time, ship heat | warning texts, tones | keep (`05` 2.6) |
| C46 | 2941-2958 | `stationLock` (no scene: `stations:getLimit` lock); `calloutFrame = {ship, velocity, speed, topSpeed, gravityState, shipLanded, clearance = groundClearance(), onFoot, crashing, controlled, stationLock, probeRadius = shipProbe.radius or 6}`; `callouts:update(dt, f)` | | announcements | keep (`09` 2.14) |
| C47 | 2960 | `markers:update(dt, pos, velocity)` (throttles itself to 0.1 s) | | markers, lock | keep (`06` 3.7) |
| C48 | 2971-2976 | on foot → `dust:setVisible(false)`; else `dust:setAir(scene or atmosphere ≠ SPACE)`, `dust:update(dt, camera position, velocity, ship)` | | speed dust, streaks | keep (`06` 3.10) |
| C49 | 2977 | `planetTerrain:update(dt, gravityState)` | | old voxel planet | drop |
| C50 | 2978 | `speedLines:setVisible(false)` | | | drop |
| C51 | 2981-2983 | no scene → `starLight:update(dt, pos)` | | Roblox sun direction | drop (Kotlin lighting) |
| C52 | 2988-2999 | mouse ownership: `wantMouse = not mapOpen and not onFoot and (not stationOpen or input.lookHeld) and not training:wantsCursor() and not construction:wantsCursor()`; on change → `input:setWanted(wantMouse)` (also clears an Alt release) and `input:centerCursor()` (the ship must not keep turning under a map, and a jump alignment must not be lost unseen) | maps, services, panels | capture, cursor | new: input owner + `releaseAll` (8.1) |
| C53 | 3000 | `stationUi:setLooking(stationOpen and input:isLooking())` | | services dim while looking | keep (`08`) |
| C54 | 3001 | `systemMap:update(dt, pos, look, markers:getLocked())` | | map refresh | keep (`07`) |
| C55 | 3005-3007 | no scene → `surface:sweep()` (no ground atmosphere left in space) | | | drop |
| C56 | 3011-3013 | no scene → `planetTexture:update(dt, pos)` | | texture streaming | new (`03` 2.13 policy) |
| C57 | 3020-3030 | impostors: scene → `updateSurface(…)`; else `update(dt, camera, systemFolder, measure = docking FREE and not travelling and SPACE, pos, stations)` | | far copies, sight probe | drop |
| C58 | 3032 | `starMap:update(dt)` | | | keep (`07`) |
| C59 | 3037-3040 | warp active, not travelling, and (scene or atmosphere ≠ SPACE) → `warp:cancel("ATMOSPHERE")` | | | keep; condition "inside an atmosphere" |
| C60 | 3041 | `warp:update(dt, ship)` | | warp state machine, HUD | keep (`07` 2.5) |
| C61 | 3045-3053 | scene: state SURFACE and altitude > exit altitude → `atmosphere:requestAscent()`; scene ready → `atmosphere:markTerrainReady()` | | | drop |
| C62 | 3055 | `atmosphere:update(dt, gravityState, speed)` | | entry state, heat glow, prompts | new (`05` 2.1, D14) |
| C63 | 3057-3060 | `arrival = atmosphere:takeArrival()` → toast(title, subtitle, 6 s) ("landed" card: name, ground type, gravity) | | | keep |
| C64 | 3061 | `voice:update(dt)` | | voice queue | keep (`09` 2.13) |
| C65 | 3065-3073 | `(hits, heat) = ringDebris:update(dt, pos, velocity)`; hits → `audio:playHit(0.3 + clamp(speed/400, 0, 1.1), Rings.HitCooldownSeconds)`; heat → `systems:addHeat(heat)` | | | keep (`03` 2.12) |
| C66 | 3076-3083 | dust mood: `timeToImpact < Warnings.Phase1Time 10` or ship heat ≥ `Overheat.Phase1Heat 0.8` → "danger"; elif `inInfluence` → "approach"; else "neutral" (applies from the next frame) | | | keep (`06`) |
| C67 | 3085-3115 | `hud:update(dt, {…})` (**new table**; fields `06` 2.3, `prompt = hudPrompt(pos)`) | | flat HUD | keep, reused record |
| C68 | 3118-3121 | `cursor:setCockpit(interior:hasFlightHud() and not external)`; `cursor:updateLimit(gravityState.speedLimit)`; `cursor:update(input:getCursorPixels(), input:getRadius())` (the pilot's cursor, also under autopilot); `cursor:updateFlight(dt, throttle, speed, forwardAccel, FA)` | | flight arrow | keep (`02` 3.9) |
| C69 | 3130-3151 | attitude frame: `shown = localUp and not onFoot, not travelling, no map, not docked, warp IDLE, (landing flow or (Attitude.ShowOnApproach and inInfluence)), (cockpit or Attitude.ShowExternal), (no 3D cockpit or interior:isHudOn())`; camera, ship, up, velocity, external, altitude (scene: over ground; else shown altitude), `rateScale = speed > 0.1 ? shownSpeed/speed : 1`, `danger = timeToImpact < 10`; `hud.attitude:update(dt, af)` | | attitude indicator | new: rule of D15 |
| C70 | 3154-3160 | `surveyContext`: locked, ship, system, `active = not travelling, not controlled, not onFoot, no scene, SPACE, not busy`; `survey:update(dt, sc)` (reads key G) | | survey bar | keep (`08` 2.10) |
| C71 | 3163-3170 | `miningContext`: `active = scene ready and atmosphere SURFACE and not travelling/controlled/onFoot/busy`, body, system, ship, velocity; `mining:update(dt, mc)` (reads key H) | | mining beam | new: "landed" from ground contact (`08` 2.11) |
| C72 | 3171 | `construction:update(dt)` | | panel | keep (`08` 2.13) |
| C73 | 3174-3179 | `galaxyTimer += dt`; ≥ 2 s → 0, `galaxy:refreshStations(index)`, `stations:sync(index, galaxy:getSites(index), galaxy:now())` (a build stage finished or a station opened, by clock, without a server message) | | station list | keep (`08`) |
| C74 | 3180 | `notices:update(dt)` | | toasts and comms expire | keep (`06` 3.9) |
| C75 | 3181 | `stationUi:update(dt)` | | services | keep (`08` 2.7) |
| C76 | 3185-3195 | `reportTimer += dt`; ≥ 10 s → 0; if docking FREE, not travelling, no scene, SPACE, not busy → `profile:fire("ReportPosition", system, ux, uy, uz, look.x, look.y, look.z, clamp(fuel, 0, 1))` | | save | new: local autosave (D23, 9.2) |

**Values carried from the previous frame.** `thrust:getSpeed()` in C8 and C11 and `thrust.velocity` in C16 are the previous frame's; `thrust.speedLimit` in C11 (autopilot) is the previous frame's smoothed limit; C68 feeds the cursor the *raw* limit of this frame.

**Allocation in the Roblox loop.** `gravity:getState` (C16), the audio state (C43) and the HUD state (C67) are new tables every frame; `interiorState`, `calloutFrame`, `trafficContext`, `surveyContext`, `miningContext` and `destructionState` are reused. The port reuses all of them (8.4).

#### 2.2.5 Other per-frame hooks outside `ShipUpdate`

| Hook | Where | Priority / event | What | Port |
|---|---|---|---|---|
| `ShipMenuCamera` | `ShipMenu.lua:2194` | RenderStep, `Camera` | menu hangar camera (menu only) | `07` |
| `DeckCameraKeep` | `ShipDecks.lua:1645` | RenderStep, `Camera + 1` | keeps the deck camera out of walls, after Roblox's camera module | `09` (own walker camera) |
| `ShipOverlay` | `ShipOverlay.lua:83` | RenderStep, `Camera + 10` | moves the marker billboard anchors in front of the camera after it moved | drop: markers are drawn in screen space after the camera (`06` 3.8) |
| Music | `ShipAudio.lua:104` | `Heartbeat` | `updateMusic(dt)` every frame, also on decks | `09`: call from the frame in every branch |
| Training | `ShipTraining.lua:1158` | `Heartbeat` | `training:update(dt)` while active | `09`: call after the session frame |
| Crowd | `ShipCrowd.lua:44` | `Heartbeat` | other players on decks | drop |
| Performance probe | `ShipPerformance.lua:126` | `RenderStepped` | collects frame times for 3 s once | 2.6 |
| Far squash test | `ShipFarSquash.lua:587` | RenderStep `Camera + 2` (Studio) | test step | drop |
| Server pilots | `ShipServer/ShipPilots.lua:330` | `Heartbeat` | multiplayer relay | drop |

`Heartbeat` runs after physics, i.e. after the render step of the same frame. In Kotlin all of it runs inside `Game.update` in the order: session frame → music → training → overlays (9.2).

#### 2.2.6 Timers and rates in the loop

| What | Rate | Clock |
|---|---|---|
| Flight frame | every rendered frame, `dt ≤ 0.1` | render step |
| Markers | 10 Hz (`Markers.UpdateInterval 0.1`) | own accumulator |
| Cockpit screens and holos | 10 Hz (`Interior.ScreenInterval 0.1`) | own |
| HUD text | 10 Hz (`Hud.TextUpdateInterval 0.1`) | own |
| Galaxy station refresh | every 2 s | `galaxyTimer` |
| Position report | every 10 s | `reportTimer` |
| SYSTEM EDGE toast | ≤ 1 per 30 s while braking | `os.clock` |
| MINIMUM ALTITUDE toast | after ≥ 4 s without barrier contact | `os.clock` |
| Rig re-attach | every 0.5 s while not ready | `attachWatchdog` |

---
### 2.3 ShipCamera (`ShipClient/ShipCamera.lua`) and the look-around input (`ShipClient/ShipInput.lua`)

**Purpose.** Puts the camera into the cockpit (or behind the ship) every frame. The header (`:5-19`) explains a Roblox race: if the script ran before Roblox's PlayerModule, the module reset `CameraType` to Custom and then fought the script every frame (hard stutter, zoom-out, own character visible). Two counter-measures: `disableDefault` switches the default camera module off, and `update` re-asserts `Scriptable` every frame (the comparison is free, the write only happens in the fault case).

**API.**

| Function | Lines | Behaviour |
|---|---|---|
| `ShipCamera.disableDefault(player) → ok` | 36-53 | yields (≤ 10 s waits for PlayerScripts and PlayerModule); `playerModule:GetCameras():Disable()` if it exists; warns on failure. Called before the menu, at the ready handshake and on every attach |
| `ShipCamera.new(camera)` | 55-76 | `CameraType = Scriptable`; `FieldOfView = settings FieldOfView`, re-applied on `ShipSettings.Changed`; `shakeOffset = identity`, `headOffset = identity`, `look = (0, 0)` |
| `setExternal(bool)`, `isExternal()` | 81-87 | cockpit or external view |
| `setHead(offset, look)` | 121-124 | head offset from ShipCockpit (G sway, surge, roll tilt; `06` 3.3) and look-around in degrees (X right, Y up) from ShipInput |
| `setShakeOffset(cf)` | 126-128 | camera shake (cockpit shake × entry shake) |
| `update(shipCFrame)` | 89-115 | below |
| `release()` | 130-132 | back to `Custom`; never called |

**`update(shipCF)`:**

```text
if camera.CameraType ≠ Scriptable: camera.CameraType = Scriptable          -- emergency brake against PlayerModule
turn = RotY(-rad(look.x)) · RotX(rad(look.y))                              -- yaw about the ship's up axis, then pitch
if external:
    e    = Ships[Hull.Model].ExternalCamera or Camera.External             -- per ship, else (0, 16, 95), -8°
    seat = turn · T(e.Offset) · RotX(rad(e.Pitch))                         -- orbits the ship origin when looking around
else:
    neck = T(Interior.NeckOffset)                                          -- (0, -0.35, 0.3) from the eye: down and back
    seat = T(Camera.SeatOffset) · headOffset · neck · turn · neck⁻¹        -- the head turns about the neck, the eye moves along
camera.CFrame = shipCF · seat · shakeOffset
```

The ship pose is the ship origin; the pilot's eye is `SeatOffset` (0, 0.5, 0) above it (`06` 0.1). Worked example (cockpit, head offset identity): look (90, 0) → `turn = RotY(−90°)`; the eye sits at `SeatOffset + neck + RotY(−90°)·(−neck)` = (0, 0.5, 0) + (0, −0.35, 0.3) + (0.3, 0.35, 0) = **(0.3, 0.5, 0.3)** in ship space, looking along +X (right). Look (0, 80): eye at (0, 0.5, 0) + (0, −0.35, 0.3) + RotX(80°)·(0, 0.35, −0.3) = (0, 0.5 − 0.35 + 0.35·cos80° + 0.3·sin80°, 0.3 + 0.35·sin80° − 0.3·cos80°) ≈ (0, 0.506, 0.593).

**Per-ship external camera** (`ShipConfig.lua:153-366`; ships without an entry use `Camera.External`):

| Ship | Offset (right, up, back) m | Pitch | Ship | Offset | Pitch |
|---|---|---|---|---|---|
| VARROW | (0, 16, 95) (default) | −8° | KESSOCK | (0, 26, 135) | −9° |
| BRANNOCK | (0, 24, 140) | −8° | LOCHRAN | (0, 15, 80) | −8° |
| MORVEN | (0, 36, 165) | −9° | CALDER | (0, 16, 95) (default) | −8° |
| TORRIN | (0, 13, 72) | −8° | DORNOCH | (0, 24, 140) | −8° |

**Look-around input** (`ShipInput.lua:76-238`; also documented in `02` 3.1, repeated here because the camera consumes it):

```text
lookHeld  = right mouse button held (InputBegan even over a GUI: with a captured mouse the pointer sits on the centre)
looking   = lookHeld and lookEnabled and mouseCaptured
if captured and mouse delta ≠ 0:
   pitchSign = InvertPitch ? -1 : 1;  s = Interior.LookSensitivity 0.25 °/px × MouseSensitivity
   looking: look.x = clamp(look.x + Δx·s, -limits.yaw, +limits.yaw)
            look.y = clamp(look.y - Δy·pitchSign·s, -limits.down, +limits.up)
   else:    cursorPixels += (Δx, Δy·pitchSign) · Cursor.Sensitivity · MouseSensitivity     -- 02
if not looking and not lookHold (debug) and look ≠ 0:
   look *= exp(-dt / Interior.LookReturnTime 0.12);  |look| < 0.05° -> look = 0          -- half-life 0.083 s
limits: cockpit (yaw 140, up 80, down 70); external (180, 60, 60); setLookLimits clamps the current look at once
lookEnabled = interior.active or external (toggleExternalView); at start = interior.active
```

While looking: the flight cursor hides (C35), the cockpit pointer is the screen centre (C36), and the stick holds its last deflection (mouse motion goes to the head, not the cursor).

**Mouse capture** (`ShipInput.lua:47-53, 133-157`): captured = `wanted and not userReleased and focused`. `wanted` is the game's wish (false while a map, the services or a panel is open, on foot or walking on a deck; C52, A5, A13); `userReleased` toggles with Left Alt; `focused` follows window focus (focus lost also releases RMB). `setWanted` clears an Alt release so the pilot can fly at once after closing a map. Captured = `MouseBehavior.LockCenter` + hidden icon; free = default behaviour + visible icon. Only a captured mouse produces deltas.

**Port.** `game/ShipCamera.kt` (9.1): the same composition with `Quat`/`DVec3` (the camera position is computed in doubles: `ship.pos + rot·(seat translation)`), look state and limits, FOV from `Settings.fovDeg` applied to `Camera.fovY` (both vertical). The look input comes from the touch look strip or the gamepad (8.1). The current `ChaseCamera` (`game/World.kt:157`) is replaced for flight (7).

### 2.4 ShipRig (`ShipClient/ShipRig.lua`)

**Purpose.** Turns the Roblox player character into a physics-free anchor: it never falls, walks or dies from a fall; only its CFrame is set (to the ship pose, C33). On station decks it becomes a normal walking character again (`setWalking`). The header (`:8-18`) names two fixed bugs: `attach` could fail silently after a 15 s `WaitForChild` timeout (now it returns a status that ShipClient's watchdog re-tries every 0.5 s), and accessories loaded later were briefly visible (now every new part is hidden as it arrives).

**API and behaviour.**

| Function | Lines | Behaviour |
|---|---|---|
| `new(player)` | 48-60 | `character, rootPart, humanoid = nil`, `hiddenParts = {}`, `attaching = false`, `walking = false` |
| `attach(character) → ok` | 86-182 | yields: Humanoid and HumanoidRootPart (≤ 10 s each); humanoid `WalkSpeed 0`, `JumpPower 0`, `JumpHeight 0`, `AutoRotate false`, `PlatformStand true`, `BreakJointsOnDeath false`, name and health display off; disables 12 humanoid states (Climbing, FallingDown, Flying, Freefall, GettingUp, Jumping, Landed, Ragdoll, Running, Seated, StrafingNoPhysics, Swimming) and enters `Physics`; anchors **only the root** (the limbs follow through Motor6D), `CanCollide false`; remembers the root's original `CanCollide` for walking; hides every BasePart and Decal locally (`LocalTransparencyModifier = 1`) and every later descendant at once (unless walking) |
| `hideLocally()` | 202-209 | every frame (Roblox's camera module resets the modifier); writes only on difference; not while walking |
| `setCFrame(cf)`, `isReady()` | 211-220 | root CFrame; ready = root exists and is parented |
| `setWalking(walking, at?) → ok` | 276-330 | yields (first `getControls`). **true:** anchor, `PivotTo(at)`, re-enable the 12 states, `PlatformStand false`, `AutoRotate true`, `UseJumpPower false`, `WalkSpeed = Decks.WalkSpeed 16`, `JumpHeight = Decks.JumpHeight 7.2`, original `CanCollide`, zero velocities, unanchor, state Freefall (drops briefly, then stands), show the body, enable Roblox's controls. **false:** controls off, anchor, `CanCollide false`, speeds 0, `PlatformStand`, states off, Physics, root to `at`, hide |
| `setControlsEnabled(bool)`, `getControls()` | 227-256 | Roblox `PlayerModule:GetControls()` Enable/Disable |
| `ShipRig.disableDefaultControls(player)` | 336-346 | WASD must not move the character in flight |

**Port.** Not ported. In flight the pilot's body is part of the cockpit model (`06` 3.4). Walking on decks and aboard is a capsule walker (`09` 2.1 and 2.4, `life/Walker.kt`); its constants (walk speed 16, jump height 7.2) come from `Decks`.

### 2.5 ShipOrigin (`ShipClient/ShipOrigin.lua`)

**Purpose** (`:5-25`). Roblox stores positions in 32-bit floats: at 300,000 studs the finest step is 0.03 studs, at 900,000 0.06; a cockpit 2 studs in front of the camera visibly trembled (measured 2.4 px at the start, 19 px at the field edge) and slow motion was lost in the steps. So the ship always stays near the world zero: when it moves away farther than `Origin.RebaseDistance`, the whole world moves back by that distance and the offset accumulates here. Two systems: **world** (what Roblox sees; fine near the ship, coarse far away) and **universe** (system coordinates, star at 0; universe = origin + world; the origin is three Lua doubles, never a `Vector3`). The origin rests during a landing-scene session (the landscape does not belong to the solar system). For multiplayer, ship positions travel as universe coordinates (three numbers) and every player converts them into his own world.

**API.**

| Function | Lines | Behaviour |
|---|---|---|
| `get() → (x, y, z)` | 36-38 | universe position of the world zero |
| `toUniverse(world) → (x, y, z)` | 41-43 | `origin + world`, three doubles |
| `toWorld(x, y, z) → Vector3` | 46-48 | `universe − origin` |
| `cframeToWorld(cf)` | 51-54 | a fixed universe pose (spawn, re-entry) → world: rotation kept, position − origin |
| `shift(v) → rounded` | 65-74 | `rounded = (round(v.x), round(v.y), round(v.z))` (whole studs are exact in 32 bits, so the origin never accumulates rounding error); `origin += rounded`; every listener `callback(rounded)` in the same frame; returns `rounded` |
| `reset()` | 77-79 | origin = 0 (new system: world = universe) |
| `onShift(cb) → unsubscribe` | 83-91 | listeners (ShipTraffic moves its NPCs and flashes by −shift) |
| `moveParts(root, delta) → count` | 98-111 | `workspace:BulkMoveTo` of every BasePart under `root` by `delta` (the solar system: ShipOrbits computes orbits relative to the star, so all stays consistent) |

Per frame (C29): with `RebaseDistance = 1` the world shifts in **every frame the ship moves** (more than 1 stud from zero): the ship practically stands at zero and the world flows past. Reason (`ShipConfig.lua:2417-2426`): Roblox recomputes light and shadow for moved anchored parts only with a delay; with 4096 the cockpit flickered for about two seconds after every rebase (black, then bright without shadows). Cost ≈ 0.12 ms per frame for the 270 body parts (no collision, no shadows). Stations are not moved by `moveParts`: they are placed absolutely after the shift (`ShipStation.lua:44-48`, one BulkMoveTo per visible station per frame).

**Port.** Not ported. `:core` keeps every world position in `DVec3` (`math/Vec3.kt:73`), renders camera-relative (`gfx/Camera.kt:10-14`) and splits depth into slices (`gfx/SceneRenderer.kt:61-69`). At S = 3000 the largest coordinate in a system is the field radius, 2.7·10¹⁰ m, where a double resolves ≈ 4·10⁻⁶ m. The universe frame (star at 0) is the only world frame. Everything that used `toUniverse`/`toWorld` (edge limit, field clamp, position report, debug teleport) uses the position directly; `onShift` listeners and `moveParts` disappear. Velocities stay `Vec3` floats: at the 0.5 c cap (150 Mm/s) a float velocity resolves ≈ 16 m/s, which is invisible against the speed and never used for contact (02's near band is slow).

### 2.6 ShipPerformance (`ShipClient/ShipPerformance.lua`)

**Purpose** (`:3-21`; player feedback 2026-11-06 "reduce lag bc my lower end device cant rlly handle it"). The setting PERFORMANCE MODE sets the values of `ShipConfig.Performance.Low`, switches shadows off (`Lighting.GlobalShadows`, on this machine only) and drops tiny decoration parts of the ship hulls (`cullDetail`). `start()` runs first thing, before the menu: checks the fixed Roblox graphics level and measures the frame rate in the menu; a slow machine switches the mode on **unless the player has changed the setting**. `apply()` runs after the menu, before the flight modules are built (they read their counts at construction). A later switch applies at once to terrain, exhaust and shadows; everything else at the next start.

**State.** `base` (remembered original values per `"Table.Key"`), `stored`, `baseShadows`, `applied` (last applied on/off), `started`, `selfSetting` (we are switching right now), `touched` (the player changed it), `status {on, quality, fps, reason ("quality" | "fps" | "player"), culled, culledGroups}`.

**Algorithms.**

```text
isOn() = settings PerformanceMode == true
setValues(on):                                                        :66-89
   for {table, key, value} in Performance.Low:
       remember base[table.key] the first time; ShipConfig[table][key] = on ? value : base
   Performance.Shadows == false: remember GlobalShadows once; GlobalShadows = on ? false : remembered
apply():  status.on = isOn(); if applied ≠ on: applied = on; setValues(on); return on      :91-99
switchOn(reason):  touched or already on -> return; status.reason = reason
                   selfSetting = true; settings.set(PerformanceMode, true); selfSetting = false   :101-109
start():   once; on settings change of PerformanceMode: not selfSetting -> touched = true, reason "player"; apply()
           quality = Roblox SavedQualityLevel (1..10, nil when Automatic); quality ≤ LowQualityLevel 3 -> switchOn("quality")
           task.spawn(measure)                                                                     :146-167
measure(): wait DetectDelay 2.5 s; collect every RenderStepped dt for DetectTime 3 s
           fewer than 3·4 = 12 frames (window minimised) -> no verdict
           median = sorted[max(1, floor(n/2))]; fps = 1/median; status.fps = round(fps)
           fps < MinFps 38 -> switchOn("fps")                                                       :123-144
cullDetail(model) -> removed      (called by ShipHull on the fresh clone, before anyone reads it)  :180-234
   off -> 0
   roots = children of the model named in DetailGroups (Hull, Canopy, Gear, Engines)
         + dotted paths ("Cockpit.CockpitPlus") whose node has the attribute CullReady = true
   in each root: destroy every BasePart with max(size) < DetailCull 0.6, no children,
                 and no attributes other than Section, Massing, Glass  (anything else marks an essential part)
getStatus() -> {on, applied, quality, fps, reason, culled, culledGroups, shadows, renderScale, dust}
```

**Port.** Performance mode becomes the existing quality setting: **LOW = performance mode on**, MEDIUM and HIGH = off with more or less resolution (`Settings.quality`, `renderScale` 0.7/0.85/1). The Low table maps to per-module counts (3.5). Detection: the existing first-run GPU heuristic (`Game.defaultQuality`, `Game.kt:1807`) plus the Roblox frame-time median (8.4), both only while the player has not chosen a quality ("touched", persisted). `cullDetail` becomes a flag per ship mesh part set at import (parts in those groups with max extent < 0.6 m, no children, plain attributes) and skipped when drawing at LOW. Shadows: the renderer has none today; if cockpit shadows are added, LOW switches them off.

### 2.7 ShipMath (`ShipCore/ShipMath.lua`)

| Function | Lines | Formula |
|---|---|---|
| `smooth(c, t, τ, dt)` | 19-25 | `τ ≤ 0 → t`; else `c + (t − c)·(1 − e^(−dt/τ))`. Frame-rate independent; τ = time to cover 63 % of the gap (the naive `c + (t − c)·0.1` reacts twice as fast at 144 fps as at 72) |
| `smoothVector2/3` | 27-41 | the same per component |
| `applyDeadzone(v, dz, curve)` | 52-66 | **radial**: `m = ‖v‖`; `m ≤ dz or m < 1e-6 → 0`; `s = clamp((m − dz)/max(1 − dz, 1e-6), 0, 1)`; `curve ≠ 1 → s = s^curve`; return `unit(v)·s` (a per-axis dead zone makes a square and feels wrong diagonally; rescaling starts the rate at 0 at the edge) |
| `orthonormalize(cf)` | 78-105 | rebuild from LookVector and UpVector: `look = unit(look)` (‖look‖ < 1e-6 → position only); `right = look × up`; parallel (‖right‖ < 1e-6) → `right = look × (abs(look.y) < 0.9 ? Y : X)` (still degenerate → position only); `up = unit(right × look)`; `CFrame.fromMatrix(pos, right, up, −look)` (fights float drift from multiplying small rotations every frame) |

**Port.** `math/MathX.kt`: `expApproach(rate, dt)` (`MathX.kt:21`) equals `smooth` with `rate = 1/τ`; add `smooth(c, t, tau, dt)` with the `τ ≤ 0` rule so config time constants port verbatim; add `applyDeadzone(x, y, dz, curve, out: Vec3)` (radial, writes into `out`, no allocation; `02` uses it with 0.20 and 1.7). `orthonormalize` maps to `Quat.normalize()` after integration.

### 2.8 ShipKeys (`ShipCore/ShipKeys.lua`)

**Purpose.** Key lookups with readable names after the player's keyboard layout, and the alternate bindings of `ShipConfig.AltKeys`. Roblox `KeyCode`s are key positions (US layout): on QWERTZ `KeyCode.Z` is the key labelled Y, so letters come from `UserInputService:GetStringForKeyCode`; special keys are named by `ShipMenuData.KeyNames`.

| Function | Lines | Behaviour |
|---|---|---|
| `name(keyCode) → string` | 24-39 | not an EnumItem → `"?"`; `KeyNames[keyCode.Name]` (L-CTRL, R-CTRL, L-SHIFT, R-SHIFT, L-ALT, R-ALT, ENTER, BACKSPACE, SPACE, TAB, ESC, UP, DOWN, LEFT, RIGHT, `One → "1"` so AZERTY does not show "&"; pseudo keys MOUSE, WHEEL, R-MOUSE, L-CLICK); else the layout string if it is one alphanumeric character (upper-cased); else `upper(keyCode.Name)` |
| `label(action)`, `altLabel(action)` | 42-50 | names of `Keys[action]` and `AltKeys[action]` (nil without) |
| `isDown(action)` | 53-60 | primary or alternate held |
| `matches(action, keyCode)` | 63-69 | the key code is the primary or the alternate |

**Port.** Becomes the input layer (9.1): an `Action` enum with held/edge state filled from touch, gamepad and an optional keyboard; `InputLabels.label(action, device)` (`06` 9.1) replaces `label`. Texts that embed key names ("PRESS L TO LAND", "HOLD G", "K   REQUEST DOCKING", "PRESS M FOR THE STAR MAP") take the device label.

### 2.9 ShipSettings (`ShipCore/ShipSettings.lua`)

**Purpose.** Player options. The menu writes, the flight modules read. Because the main menu runs before the flight, reading at start is enough; modules that react live subscribe to `Changed`. **Not saved**: the values hold for the session ("saving comes with the DataStore", `:9-10`).

**Definitions** (menu order, `:22-42`; hints and display formats from `ShipMenuData.SettingInfo`, `ShipMenuData.lua:186-195`):

| Key | Label | Kind | Range / step | Default | Display | Hint |
|---|---|---|---|---|---|---|
| `MouseSensitivity` | MOUSE SENSITIVITY | slider | 0.25–2, 0.05 | 1 | `"%.2f×"` | HOW FAR THE FLIGHT ARROW LEANS OUT FOR A MOUSE MOVEMENT. |
| `InvertPitch` | INVERT PITCH | toggle | | false | OFF / ON | MOUSE UP PITCHES THE NOSE DOWN, LIKE A FLIGHT STICK. |
| `MouseYaw` | MOUSE X AXIS | toggle, choices {ROLL, YAW} | | `Cursor.MouseXAxis == "YAW"` → false | ROLL / YAW | ROLL: THE MOUSE PITCHES AND ROLLS, Q / E YAW. YAW: THE MOUSE PITCHES AND YAWS, Q / E ROLL. |
| `FieldOfView` | FIELD OF VIEW | slider | 60–100, 1 | `Camera.FieldOfView` 80 | `"%d°"` | HOW MUCH OF THE COCKPIT AND SPACE YOU SEE AT ONCE. |
| `CameraShake` | CAMERA SHAKE | slider | 0–1, 0.05 | 1 | `"%d %"` | STRENGTH OF COCKPIT SHAKE UNDER THRUST AND IMPACTS. |
| `PerformanceMode` | PERFORMANCE MODE | toggle | | false | OFF / ON | FOR SLOWER PCS: NO SHADOWS, FEWER PARTICLES AND STREAKS, LESS DISTANT TERRAIN. TURNS ITSELF ON WHEN YOUR PC STRUGGLES. |
| `MusicVolume` | MUSIC | slider | 0–1, 0.05 | 1 | `"%d %"` | BACKGROUND MUSIC IN THE MENU AND IN FLIGHT. |
| `EffectsVolume` | EFFECTS | slider | 0–1, 0.05 | 1 | `"%d %"` | ENGINES, THRUSTERS, WARNINGS AND INTERFACE SOUNDS. |

Panels: CONTROLS = MouseSensitivity, InvertPitch, MouseYaw (+ the key list, 5.5); GRAPHICS = FieldOfView, CameraShake, PerformanceMode; AUDIO = MusicVolume, EffectsVolume (`ShipMenuData.OptionPanels`). A RESET button per panel sets that panel's keys to their defaults.

**API and rules** (`:44-85`): `get(key)`; `getDefinition(key)`; `set(key, value)`: unknown key → warn, ignore; slider → `clamp(tonumber(value) or default, min, max)`; toggle → `value == true`; unchanged → nothing; else store and fire `Changed(key, value)`. `reset()` sets every default. The menu snaps slider values (`ShipMenu.lua:164-174`): `v = clamp(v, min, max)`; `v = min + round((v − min)/step)·step`, clamp again, then round to 4 decimals (removes 0.30000000000000004); display formats (`:176-196`): percent `"%d %%"` of `round(v·100)`, degrees `"%d°"` of `round(v)`, multiplier `"%.2f×"`, step ≥ 1 → integer, else `"%.2f"`.

**Consumers.**

| Setting | Read by | When |
|---|---|---|
| MouseSensitivity | ShipInput (cursor and look gain), ShipAboard (walking camera) | every mouse delta |
| InvertPitch | ShipInput | every mouse delta (cursor and look) |
| MouseYaw | ShipInput `isMouseRoll`, ShipTraining texts | every frame |
| FieldOfView | ShipCamera (live via Changed), ShipDestruction (restores it) | live |
| CameraShake | ShipCockpit (vibration, head, shake), ShipEntryFx, ShipDestruction | every frame |
| PerformanceMode | ShipPerformance (Changed) | live (partly next start) |
| MusicVolume | ShipAudio (Changed), ShipMenu | live |
| EffectsVolume | ShipAudio, ShipWarnings, ShipVoice, ShipInterior, ShipEntryFx, ShipDestruction, ShipMenu | at build or live |

**Port.** `Settings.kt` gets the missing fields and keeps persisting them (3.10, 9.1).

### 2.10 ShipUi (`ShipCore/ShipUi.lua`)

**Purpose.** One screen scale for every GUI built at runtime. Roblox `TextSize` is a fixed pixel value: 15 px is readable at 1080p, tiny at 4K and clumsy at 720p. All sizes are given for a reference height and scaled by the actual viewport; modules register to be told when the window changes.

```text
scale       = clamp(viewport.y / Ui.ReferenceHeight 1080, Ui.MinScale 0.75, Ui.MaxScale 2.2)      :25-33
px(v)       = round(v · scale)                         -- whole pixels: half pixels blur edges     :40-42
text(size)  = max(round(size · scale), 8)                                                          :44-46
onChanged(cb): append; cb(scale) at once                                                           :48-51
refresh():  new = scale(); |new − current| < 0.001 → nothing; else current = new; task.spawn(cb, new) for each   :53-62
            bound to Camera.ViewportSize changes; current computed at module load                  :124-129
registerExclusive(owner, close) / openExclusive(owner):                                            :72-84
            full-screen views (system and star map) where only one may be open: both listen to the
            wheel and the right mouse, the hidden one would keep working; opening one calls every other close()
animateOpen(gui, canvas?)        -- player feedback 2026-11-05 "smoother animations"               :96-122
            info = TweenInfo(Ui.OpenTime 0.22 s, Quint, Out)                 -- ease: 1 − (1 − t)^5
            canvas: Position from (0, px(Ui.OpenRise 14)) to (0, 0)          -- slides 14 design px up into place
            veil: a full-screen Frame "OpenVeil" (created once, ZIndex 10000, colour Ui.OpenVeilColor (6, 10, 16)),
                  BackgroundTransparency from Ui.OpenVeil 0.35 to 1, Visible = false when the tween completes
            only Canvas.Position and the veil are touched (scale runs through UIScale); no CanvasGroup (it blurs text)
```

**Port.** `ui/UiScale.kt` (9.1): phone sizes come from `Frame.u`, not from the height ratio (5.7); `animateOpen` becomes an overlay-open transition (rise 14 design px → 8.4 u, veil 65 % → 0 % opacity, 0.22 s quint-out); exclusivity is the single `overlay` slot in `Game`.

### 2.11 ShipUnits (`ShipCore/ShipUnits.lua`)

**Purpose.** Units for every display: 1 stud = 1 m, 1 stud/s = 1 m/s. Before, distances were "light seconds" of 100 studs and speeds "c" of 100 studs/s while the cockpit altitude was in studs (316 studs height read "3.17 Ls" beside it, and a real light second is 300,000 km). From 1000 km and 1000 km/s the units go on in megametres (Josh 2026-11-06: "Currently everything is based in Km"); light speed would be 300 Mm/s. Upper-case texts keep "Mm" (MM would read as millimetres).

```text
metres(v) = v · Scale.MetresPerStud (1)
grouped(v):  s = "%d" of floor(max(v, 0) + 0.5); commas every 3 digits from the right      1240567 -> "1,240,567"   :37-48
distance(v):                                                                                 :50-70
   NaN or +∞ -> "--";  m = max(m, 0)
   m < 999.5        "%d m"    of floor(m + 0.5)                316 m
   m < 9,995        "%.2f km" of m/1000                        3.17 km
   m < 99,950       "%.1f km"                                  53.0 km
   m < 999,500      "%d km"   of floor(m/1000 + 0.5)           828 km
   m < 9,995,000    "%.2f Mm" of m/1e6                         6.84 Mm
   m < 99,950,000   "%.1f Mm"                                  53.0 Mm
   else             grouped(m/1e6) .. " Mm"                    1,240 Mm
speedParts(v) -> (number, unit):                                                             :72-90
   NaN or +∞ -> ("--", "M/S");  v = max(v, 0)
   v < 999.5      ("%d" of floor(v + 0.5), "M/S")              ("260", "M/S")
   v < 9,995      ("%.2f" of v/1000, "KM/S")                   ("1.24", "KM/S")
   v < 99,950     ("%.1f", "KM/S")                             ("53.0", "KM/S")
   v < 999,500    ("%d" of floor(v/1000 + 0.5), "KM/S")        ("250", "KM/S")
   v < 9,995,000  ("%.2f" of v/1e6, "Mm/s")                    ("1.06", "Mm/s")
   else           ("%.1f", "Mm/s")                             ("150.0", "Mm/s")   (no grouping)
speed(v)  = number .. " " .. (unit == "Mm/s" ? unit : lower(unit))        "260 m/s", "53.0 km/s", "1.06 Mm/s"   :92-96
upper(t)  = upper(t); "(%d) MM/S" -> "%1 Mm/s"; "(%d) MM" at a word end -> "%1 Mm"       "6.84 Mm", "1,240 Mm"   :102-107
acceleration(a) = "%.1f m/s²"                                                                :109-111
```

**Invariant.** Every threshold is the rounding boundary of the next format, so no display ever shows "1000 m", "10.00 km", "100.0 km", "1000 km", "10.00 Mm" or "100.0 Mm". Negative values print as 0; −∞ is not caught (`m ~= m or m == math.huge`) and prints "0 m".

Related formatters in other areas: `ShipMarkers.formatDuration` (`06` 3.1, `"%02d:%02d:%02d"`, `"--:--:--"` above 99 h), `ShipEconomy.formatCredits` (`08`).

**Port.** One implementation (`hud/Units.kt` in `06`'s file plan) writing into a `TextBuf` (no `String` per frame): `distance(buf, m)`, `speedParts(numBuf, unitOut, v)`, `speed(buf, v)`, `upper` folded in (emit upper-case units directly: "M", "KM", "Mm", "M/S", "KM/S", "Mm/s"), `acceleration`, `grouped`. Rounding: Lua `%.2f` rounds the binary double to nearest; compute in `Double` (`floor(x·100 + 0.5)`), never in `Float` (6.845 km in Float is 6.8449998). It replaces `Game.formatSpeed`/`formatDistance` (`Game.kt:1757-1780`) and `MapScreen.distanceText` (`ui/MapScreen.kt:496`), which use other thresholds and print "MM" (7). With S = 3000 the largest in-system distance is 27 Gm = "27,000 Mm"; the largest speed at the 0.5 c cap is "150.0 Mm/s" (D2, D4).

### 2.12 ShipNet (`ShipCore/ShipNet.lua`)

**Purpose** (`:3-14`). The only place where remotes are created or looked up (template: Josh's rules, `Net.lua`). The server creates them in code (folder `ShipCore.Net`); the client only picks them up. `Net:Guard(remote, schema, handler, opts)` rate-limits and checks every argument in one call; extra arguments are rejected so no client can smuggle in a hidden fourth value.

| Function | Lines | Behaviour |
|---|---|---|
| folder | 25-35 | server: find or create `Net`; client: `WaitForChild("Net", 20)` and assert |
| `Net:Event(name)`, `Net:Func(name)`, `Net:Unreliable(name)` | 37-64 | find by name and assert the class; client waits ≤ 10 s; server creates. Unreliable = unordered, may be lost, < 1000 bytes, effects only |
| `Net:Allowed(player, key, cooldown, burst = 1) → bool` | 71-83 | token bucket per player and key: `tokens = min(burst, tokens + (now − last)/cooldown)`; < 1 → false; else −1, true |
| `Net.T` | 88-117 | checks: `any`, `string`, `number` (finite, not NaN), `int`, `bool`, `vec3`, `instance`, `range(min, max)`, `intRange`, `oneOf(set)`, `enum(e)`, `isA(class)`, `optional(check)`, `str(maxLen)` |
| `Net:Guard(remote, schema, handler, opts)` | 121-155 | server only; defaults `cooldown 0.2`, `burst 3`; per call: rate → reject "rate"; `#args > #schema` → "extra args"; a failing check → "arg i"; else `handler(player, …)`; RemoteFunction → `OnServerInvoke`, else `OnServerEvent` |
| player cleanup | 157-159 | buckets dropped on PlayerRemoving |

**Remotes in the game** (all through ShipNet except `ShipReady`):

| Remote | Kind | Server module | Client caller | Port |
|---|---|---|---|---|
| GetProfile | Func | ShipCommerce:128 | ShipProfile:fetch | `Commerce.startSession()` |
| RequestDocking, CancelDocking, Dock, Undock | Func | ShipCommerce:162-217 | ShipDocking | local calls (`08`) |
| GetMarket, Trade, Refuel, SellData, BuyShip, SwitchShip, Survey | Func | ShipCommerce:237-413 | station services, survey | local (`08`) |
| ReportDestroyed | Func | ShipCommerce:520 | startCrash | local (`08`) |
| ReportPosition, ReportJump | Event | ShipCommerce:477-499 | the loop (C76), arriveInSystem | autosave / save on jump |
| ProfileChanged | Event (server → client) | ShipData:66 | ShipProfile | `Profile.onChanged` listeners |
| GetSystem, GalaxyChanged | Func / Event | ShipGalaxy:520, :74 | ShipGalaxy client | `GalaxyStore` (`03`, `08`) |
| Mine, FoundStation, Deliver, Abandon, Collect, SellStorage | Func | ShipConstruction:124-427 | mining, construction panel | local (`08`) |
| EnterDeck, LeaveDeck, SetHome, EnterShip, LeaveShip | Func | ShipDecks:115-156 | decks, aboard | local `DeckSession` (`09`) |
| StartTraining, StartSolo, FinishTraining, TrainingTeleportFailed | Func / Event | ShipTraining:155-191 | training, menu | local (no teleports) |
| JoinPilot, PilotInfo, PilotJoinFailed, PilotState, PilotStates | Func / Event / Unreliable | ShipPilots | ShipPilots | dropped |
| ShipReady | RemoteEvent (placed in Studio) | ShipServer:51 | the client at boot | dropped |

**Port.** No network layer. Every guarded handler becomes a method on a local service (`LocalServices`, 2.13, 9.1) with typed parameters; the schema checks become Kotlin types plus the value checks the rules need (amounts, ranges); rate limits disappear. Calls are synchronous, so the client's "pending" flags and `task.spawn` wrappers become plain calls; **game-feel delays that are configured stay timers** (`Stations.GrantDelay 1.6` s before the controller answers, `08`), unconfigured network latency is not imitated. Persistence (DataStore) becomes the save manager (`08` 9: atomic JSON through `Platform`, written off the GL thread).

### 2.13 ShipServer (`ShipServer.lua`)

**Purpose** (`:4-21`). **Spawn lock.** Roblox spawns the character at join, before the client scripts are ready; in that window the anchor was set too late, the character fell and Roblox's camera took over. So `Players.CharacterAutoLoads = false` (`:27`) and the character loads only when the client reports ready (`ShipReady`). Since the main menu the client reports only at LAUNCH (minutes later), and deliberately has no character until then (an earlier 12 s emergency spawn would have spawned players in the menu). Since P2 the script also starts the save games and commerce, already while the menu is open.

**Init order** (`:31-49`; each module receives the ones it needs):

1. `ShipData.init()` (profiles and DataStore)
2. `ShipCommerce.init(ShipData)` (docking, trade, shipyard, reports)
3. `ShipGalaxy.init(ShipData, ShipCommerce)` (shared galaxy store: discoveries, deposits, player stations)
4. `ShipConstruction.init(ShipData, ShipCommerce, ShipGalaxy)` (mining by hand, own stations: charter, construction, storage)
5. `ShipDecks.init(ShipData, ShipCommerce)` (deck slots, who stands on which deck)
6. `ShipTraining.init(ShipData)` (private training session, training result in the save)
7. `ShipPilots.init()` (other pilots, OPEN PLAY relay, joining friends)

**Spawning** (`:51-101`): `spawnPlayer(player, reason)` once per player → `LoadCharacter()` (log "<name> spawned (client ready)"); `ShipReady.OnServerEvent` → spawn; on foot a character can die (reset in the Roblox menu, a fall): `watchDeath` → 3 s after `Humanoid.Died` reload the character if it is still the current one (auto-loading is off, it would never come back); `PlayerRemoving` clears the spawned flag.

**Studio test hook** (`:113-141`, Studio and server only, no remote): BindableFunction `ShipServerDebug` with `credits n` (set the first player's credits), `profile` (whole profile view), `galaxy index` (galaxy record), `pilot…` (fake friend), and `station`, `fill`, `skip`, `age`, `cargo` (construction debug).

**Port.** No server and no spawn lock. `LocalServices.start(save)` runs at app start in the same order (without ShipPilots) so the menu can show the pilot's credits and location; `08` owns Profile, Commerce, Construction and the save manager, `03`/`08` the galaxy store, `09` the deck session and training. The Studio commands become `Game.debugCommand` entries (9.6).

---

## 3. Tunables

Values from `ShipCore/ShipConfig.lua` unless stated. "Port" says how the value carries over (**keep** = same number, **×S** = scaled by S, **drop**, or the owner doc).

### 3.1 `ShipConfig.Keys` (`:2763-2842`) and `ShipConfig.AltKeys` (`:2845-2848`)

| Action | Key | Alt | Meaning (config comment) | Handled in | Gate |
|---|---|---|---|---|---|
| ThrottleUp | W | | throttle lever up (0 → 100 % in `Thrust.ThrottleRampTime` 4 s) | `ShipThrust.readInput` (polled), `ShipThrust.lua:172` | thrust input enabled, no text box focused |
| ThrottleDown | S | | lever down to `MinThrottle` −25 % | same | same |
| StrafeLeft / StrafeRight | A / D | | lateral thrusters (ship space) | same | same |
| StrafeUp | R | Space | vertical thrusters up ("Space and L-Ctrl keep working") | same, via `ShipKeys.isDown` | same |
| StrafeDown | F | LeftControl | vertical thrusters down | same | same |
| YawLeft / YawRight | Q / E | | yaw (with MOUSE X AXIS = YAW they roll, and the mouse yaws) | `ShipInput.update` (polled), `ShipInput.lua:231` | not typing; input read only when not on foot |
| FullStop | X | | emergency stop / retro brake (hold) | `ShipThrust.readInput`, `:201` | thrust input enabled |
| ToggleFlightAssist | Z | | Flight Assist on/off | `ShipThrust` InputBegan, `:148` | not processed, not on deck or aboard |
| ReleaseMouse | LeftAlt | | free the mouse (toggle; "for clicking in Studio") | `ShipInput` InputBegan, `:87` | not processed |
| CenterCursor | C | | flight cursor to the centre at once | `ShipInput`, `:90` | not processed |
| ToggleMusic | J | | background music on/off | `ShipAudio` InputBegan, `:82` | not processed (works on decks and aboard too) |
| CycleTarget | T | | target the next object (repeat presses step on) | `ShipMarkers`, `:220` | not processed, not on deck or aboard |
| ClearTarget | Y | | clear the target ("the key right of T; on QWERTZ it reads Z") | `ShipMarkers`, `:222` | same |
| SystemMap | N | | open/close the system map | `ShipSystemMap`, `:1081` | same |
| StarMap | M | | star map | `ShipStarMap`, `:687` | same |
| ConfirmJump | Return | | confirm the jump (in the star map); a failed confirm with a selection flashes "JUMP NOT POSSIBLE HERE · LEAVE ATMOSPHERE AND STATION RANGE" | `ShipStarMap`, `:689` | star map open; training uses Enter for CONTINUE unless the star map is open (`ShipTraining.lua:1113`) |
| Autopilot | P | | autopilot to the locked target; with docking clearance: docking computer on/off | `ShipClient.lua:1187` | not processed, not on deck or aboard |
| Land | L | | begin a landing on a planet | `ShipClient.lua:1171` | same; ignored while the docking computer flies |
| Dock | K | | request or cancel docking; docked: launch ("an own key so that L also lands near a station") | `ShipClient.lua:1177` | same |
| ExternalView | V | | cockpit ↔ external view | `ShipClient.lua:1182` | same |
| CancelJump | Backspace | | cancel a planned jump while the tunnel has not started | `ShipWarp`, `:218` | ALIGN or COUNTDOWN; not on deck or aboard |
| Boost | B | | boost ("Tab is the Roblox player list") | `ShipThrust`, `:150` | not on deck or aboard |
| Survey | G | | survey the locked body (hold) | `ShipSurvey`, `:143` (polled) | survey context active, no text box |
| Mine | H | | mining by hand: landed, hold low over the ground | `ShipMining`, `:257` (polled) | mining context active, no text box |
| StationPanel | U | | own station: licence, construction site, storage | `ShipConstruction`, `:200` | not processed, no text box, **not aboard** (works on decks); `canOpen` = after the reveal and not in a jump |
| LeaveSeat | One (1) | | walkable ships: stand up from the helm and walk aboard (sit down with E at the helm). "Not O: O and I are taken by Roblox's camera zoom; every letter is taken; Roblox does not use 1 without tools" | `ShipClient.lua:1157` | not processed; **also on decks and aboard** |

Keys outside `ShipConfig.Keys`: right mouse (look-around, map rotate/pan), mouse movement (cursor, look), left click (cockpit buttons in pointer mode, maps, panels), wheel (map zoom, aboard and deck zoom), **E** (aboard: interact, sit at the helm, `ShipAboard.lua:246`, also gamepad ButtonX; decks: ProximityPrompt key, `ShipDecks.lua:986`), walking aboard: W/A/S/D or arrows, Space jump, gamepad Thumbstick1/2 (dead zone 0.2), ButtonA (`ShipAboard.lua:891-915`); decks: Roblox's default character controls; menu: Up/W, Down/S, Left/A, Right/D, Return/KeypadEnter (confirm), Backspace (back), PageUp/PageDown (`ShipMenu.lua:84-98`; Escape belongs to Roblox's own menu). The docking computer gives the controls back when any of ThrottleUp/Down, StrafeLeft/Right/Up/Down, YawLeft/Right is held (`ShipDocking.lua:43-45, 588-601`) or the pilot steers (C7).

### 3.2 `ShipConfig.Camera` (`:2651-2663`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `FieldOfView` | 80 | default vertical field of view (degrees); the setting range is 60–100 | keep (`Settings.fovDeg`) |
| `SeatOffset` | (0, 0.5, 0) | the eye relative to the ship origin (studs, ship space) | keep |
| `External.Offset` | (0, 16, 95) | external view: "the VARROW is 66 studs long, its tail lies 56 behind the eye" | keep; per ship in 2.3 |
| `External.Pitch` | −8 | degrees, slightly down | keep |

### 3.3 `ShipConfig.Ship` (`:2668-2698`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `StartPosition` | (0, 5000, −900,000) | default space spawn in Sol, "just outside Earth's orbit" (Earth orbits at 828,660) | ×S → (0, 15 Mm, −2.7 Gm); fallback only (D13 starts docked) |
| `StartLookDirection` | (0, 0, −1) | | keep |
| `FieldRadius` | 9,000,000 | play-field sphere around the star (studs from the star). At the edge the outward velocity is removed instead of a hard stop (`ShipRotation.translate`); generated orbits are compressed to ≤ 0.8 × this (`ShipSystemBuilder.lua:695`); the server accepts reported coordinates up to 1.05 × this | ×S → 27 Gm |
| `EdgeSpeed` | 150 | m/s at the edge. Below every ship's top speed (MORVEN 170) so the cruise ends there and the distance lines (from 240 m/s) vanish. Before, pilots arrived in full cruise and slid along the edge with travel up to 113° off the nose (play-test 2026-11-06). The edge brakes once its limit falls below the bodies' limit, behind the outermost orbit; leaving the edge uses the faster departure time | keep (absolute) |
| `EdgeMargin` | 2000 | below 1000 m/s the ship brakes only at `Gravity.BrakeAcceleration` 80 m/s² and arrived at ~670 m/s, stopped at the edge in one frame. With the margin the limit reaches `EdgeSpeed` this far before the edge: arrival ≈ 150 m/s (simulation 152 m/s), ~8 s longer | keep absolute (`02` 4.11: a braking distance; `03` 3.6 offers ×S or dropping it) |
| `PositionFrame` | 2 | version of the reported space position in the save (`ShipCommerce` ReportPosition). A position of another version is invalid (orbits ×10 since 2026-11-03); such a pilot starts in front of the last station | new: the port's save keeps a `positionFrame` and starts at 1 (9.1) |

### 3.4 `ShipConfig.Origin` (`:2416-2428`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `RebaseDistance` | 1 | farther than this from the world zero and the world shifts. At 1 it shifts every frame the ship moves: the ship practically stands still and the world flows past. Reason: Roblox recomputes light and shadows of moved anchored parts late; with 4096 the cockpit flickered for ~2 s after every jump (black, then bright without shadows) and at full speed the light was often wrong. Cost ≈ 0.12 ms per frame for the 270 solar-system parts (no collision, no shadows); side effect: the position step at the ship stays as fine as possible | drop |

### 3.5 `ShipConfig.Performance` (`:2710-2749`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `DetectDelay` | 2.5 s | measurement starts this long after the client starts (menu) | keep (8.4) |
| `DetectTime` | 3 s | measuring window (median of frame times) | keep |
| `MinFps` | 38 | below → performance mode on | keep (8.4) |
| `LowQualityLevel` | 3 | a fixed Roblox graphics level 1–10 at or below this switches it on (Automatic does not count) | → GPU heuristic `Game.defaultQuality` |
| `Shadows` | false | `Lighting.GlobalShadows` in performance mode | keep if shadows appear |
| `DetailCull` | 0.6 | studs: hull detail parts with a shorter longest edge are dropped | keep (ship import flag) |
| `DetailGroups` | Hull, Canopy, Gear, Engines, `Cockpit.CockpitPlus` | groups searched (dotted path = deeper node, only with attribute `CullReady`; essential cockpit parts carry attributes such as `Essential`) | keep |
| `Low` | 16 entries | `{table, key, low value}` | per module, below |

| `Low` entry | Normal | Low | What | Port owner |
|---|---|---|---|---|
| `SurfaceTerrain.RenderScale` | 4 | 1 | landing-scene ground ring (at 4: out to 7,200 studs, ≈ 2.7 ms per frame) | `04` (terrain LOD reach at LOW) |
| `SurfaceTerrain.FrameBudget` | 0.005 s | 0.003 s | ground build time per frame | `04` (tile uploads per frame) |
| `Terrain.FrameBudget` | 0.004 s | 0.003 s | old voxel terrain | drop |
| `PlanetTexture.FrameBudget` | 0.004 s | 0.003 s | planet texture generation per frame | `03` |
| `Dust.Count` | 90 | 45 | space dust motes | `06` |
| `Dust.AirCount` | 70 | 35 | air streaks | `06` |
| `SpeedStreaks.Count` | 60 | 30 | cruise streak beams | `06` |
| `DistanceLines.Count` | 48 | 24 | distance lines (switched off) | `06` |
| `Warp.StreakCount` | 190 | 90 | screen tunnel streaks (fallback) | `07` |
| `Warp.TunnelRailCount` | 110 | 55 | tunnel light rails | `07` |
| `Warp.TunnelHoopCount` | 90 | 45 | tunnel hoops | `07` |
| `Warp.TunnelStreakRate` | 160 | 80 | tunnel particles per second | `07` |
| `Exhaust.CoreRate` | 110 | 50 | core particles per second at full thrust | `06` |
| `Exhaust.CoreRateIdle` | 35 | 15 | at idle | `06` |
| `Exhaust.VeilRate` | 45 | 0 | outer veil particles (off) | `06` |
| `Rings.DebrisCount` | 280 | 110 | ring debris rocks | `03` |

### 3.6 `ShipConfig.Scale` (`:2754-2758`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `MetresPerStud` | 1 | 1 stud = 1 m, also for speeds; every display goes through `ShipUnits` | keep (implicit: the port works in metres) |

### 3.7 `ShipConfig.Runtime` (`:3069-3076`): runtime state several modules read (not a tunable)

| Key | Initial | Meaning | Written by | Read by |
|---|---|---|---|---|
| `Deck` | false | true while on a station deck (in the hangar or on foot): no flight key applies | `ShipDecks.lua:1748` (true on arrival), `:1760` (false if the hangar scene fails), `:2106` (false after launch) | ShipClient key handler, ShipThrust, ShipMarkers, ShipSystemMap, ShipStarMap, ShipWarp, ShipTraining (`:608, 1167, 1367`) |
| `Aboard` | false | true while walking aboard the own ship: no flight key, the ship holds its course | `ShipAboard.lua:722` (stand), `:829` (sit or leave) | the same plus ShipConstruction |

Port: a `PlayerMode` value owned by the session (4.2); the input gate (4.5) replaces the scattered checks.

### 3.8 Values from other sections that the loop and this area's modules read

| Value | Line | Used for |
|---|---|---|
| `Interior.LookYaw / LookUp / LookDown` = 140 / 80 / 70 | 2858-2860 | look limits in the cockpit |
| `Interior.ExternalLookYaw / Up / Down` = 180 / 60 / 60 | 2866-2868 | look limits outside |
| `Interior.LookSensitivity` = 0.25 °/px | 2862 | look gain (× MouseSensitivity) |
| `Interior.LookReturnTime` = 0.12 s | 2864 | look return |
| `Interior.NeckOffset` = (0, −0.35, 0.3) | 2872 | head pivot |
| `Interior.ScreenInterval` 0.1 s, `BootTime` 2.6 s, `HintTime` 12 s | 2893-2897 | cockpit rates, boot after the reveal |
| `Cursor.MouseXAxis` "ROLL", `Sensitivity` 1, `RadiusScale` 0.20, `MinRadius` 130, `MaxRadius` 300 | 1605-1631 | MouseYaw default, cursor (`02`) |
| `Ui.ReferenceHeight` 1080, `MinScale` 0.75, `MaxScale` 2.2 | 2018-2020 | ShipUi scale |
| `Ui.OpenTime` 0.22, `OpenRise` 14, `OpenVeil` 0.35, `OpenVeilColor` (6, 10, 16) | 2051-2054 | open animation |
| `Ui` palette: Panel (206, 226, 240), Dim (120, 145, 165), Accent (255, 170, 60), Good (120, 220, 150), Warn (255, 165, 70), Danger (255, 70, 60); fonts Body = Titillium Web SemiBold, BodyBold = Titillium Web Bold, Ui = Sarpanch SemiBold, UiBold = Sarpanch Bold, Mono = Roboto Mono Medium | 2022-2041 | every runtime GUI (`06` 6.1-6.2) |
| `Thrust.MaxSpeed` 1000 | 31 | `speedFraction`, HUD and audio `maxSpeed` |
| `Thrust.CruiseMaxSpeed` 3,000,000 | 50 | throttle after a guidance release (C26) |
| `Autopilot.DisengageOnInput` true, `OverrideDeflection` 0.5 | 2389, 2393 | override and `steering` (C7, C11) |
| `Gravity.Tag` "CelestialBody" | 449 | `clearOfBodies`, debug lookups |
| `Gravity.SafeLandingSpeed` 20 | 512 | old surface crash (C28c) |
| `Surface.SafeLandingSpeed` 38 | 760 | landing-scene crash (C28b) |
| `Terrain.GroundProbe` 400, `GroundClearance` 12 | 1044-1045 | ground rays, clearance |
| `Hull.Model` "Varrow", `GearDownAltitude` 60 | 2617-2619 | active ship, gear |
| `Stations.DockingRange` 6000, `CrashSpeed` 45 | 2925, 2941 | JUMP BLOCKED text, station crash |
| `Voice.WelcomeDelay` 5, `Voice.Callouts.HullImpactSpeed` 12 | 2070, 2238 | welcome, hull impact |
| `Atmosphere.RequestWindow` 3.0 | 958 | `watchLanding` (+0.1 s) |
| `Warp.ArrivalMargin` 1.15 | 1176 | `arrivalPoint` |
| `Warnings.Phase1Time` 10, `Overheat.Phase1Heat` 0.80, `Overheat.FatalHeat` 2.00 | 545, 572, 574 | dust mood, heat death |
| `Warnings.Crash` FadeOutTime 0.30, BlackHoldTime 1.6, FadeInTime 0.9 | 581-585 | crash without cinematic |
| `Destruction.Enabled` true, `Duration` 11.0, `FadeOutTime` 1.2, `BlackHoldTime` 1.4, `FadeInTime` 1.2 | 597-602 | crash with cinematic |
| `Physics.StudsPerG` 60 | 2436 | crash gravity fallback |
| `Markers.UpdateInterval` 0.1, `Hud.TextUpdateInterval` 0.1 | 1561, 1738 | rates |
| `Rings.HitCooldownSeconds` 1.0 | 2601 | ring debris hit sound |
| `Aboard.MaxStandSpeed` 30 | 3038 | `canStand` |
| `Decks.WalkSpeed` 16, `JumpHeight` 7.2 | 2992-2993 | `ShipRig.setWalking` |
| `Attitude.ShowOnApproach` false, `ShowExternal` false | 1690-1691 | attitude visibility (C69) |
| `Training.Required` true | 3065 | training routing (S6); D28 makes training optional |
| `Audio.Music.Enabled` true | 1760 | initial `musicEnabled` |
| `Ships[id].ExternalCamera`, `DisplayName`, `CargoCapacity` | 87-370 | camera, crash text, aboard status |

### 3.9 Constants inside the modules

| Constant | Value | Where | Meaning |
|---|---|---|---|
| dt cap | 0.1 s | `ShipClient.lua:2324` | per frame |
| `GALAXY_REFRESH` | 2 s | `:1270` | station refresh by clock |
| `REPORT_INTERVAL` | 10 s | `:1273` | position report |
| `BARRIER_NOTICE_PAUSE` | 4 s | `:1277` | MINIMUM ALTITUDE toast pause |
| edge toast pause | 30 s | `:2522` | SYSTEM EDGE toast |
| attach watchdog | 0.5 s | `:2140, 2780` | rig re-attach |
| FIRST VISIT window | 300 s | `:683` | visit must be this recent |
| survey re-fetch | 1.5 s | `:708` | after a survey |
| landing watch | `RequestWindow` + 0.1 s | `:1015` | LandingBlocked voice |
| ON FOOT hint | 2 times | `:2012` | per session |
| stand-up speed on the ground | < 2 m/s | `:2052` | `canStand` |
| hull hit sound | speed > 4 m/s, volume `clamp(speed/45, 0.15, 1)`, cooldown 0.3 s | `:2737-2739` | station slide |
| ring hit sound | `0.3 + clamp(speed/400, 0, 1.1)` | `:3069` | |
| `clearOfBodies` | inside 1.5 r → out to 3 r | `:613-615` | |
| `arrivalPoint` height | 0.06 × safe | `:601` | |
| `crashHull` fallback size | (40, 16, 60) | `:1361` | |
| ground stand tolerance | clearance + 0.6 | `:2641, 2667` | `shipLanded` |
| barrier lift | surfaceRadius + 0.5 | `:2687` | |
| prompt join | "\n" between the landing and the docking prompt | `:1052` | `hudPrompt` |
| `REVEAL_DEFAULT` | 0.6 s (Sine Out) | `ShipMenu.lua:78` | curtain fade |
| `REVEAL_TIMEOUT` | 12 s | `ShipMenu.lua:79` | self-reveal |
| `COVER_ORDER` | 1000 | `ShipMenu.lua:81` | curtain above every flight GUI (they go to 100) |
| arrival cover `REVEAL_TIME` | 0.8 s | `ShipTraining.lua:82` | after a teleport |
| reveal wait | `MainMenu` ≤ 8 s, then 2 `RenderStepped` | `:3252-3254` | |
| training required wait | ≤ 10 s | `:117` | `TrainingDone` attribute |
| galaxy fetch waits | 8 s (start), 6 s (jump), 0 (refresh) | `:656, 875, 710` | |
| ShipNet waits | folder 20 s, remote 10 s | `ShipNet.lua:33, 45` | |
| `Guard` defaults | cooldown 0.2 s, burst 3 | `ShipNet.lua:130` | |
| ShipRig waits | Humanoid / root 10 s | `ShipRig.lua:98-99` | |
| ShipServer respawn | 3 s after death | `ShipServer.lua:77` | |
| ShipCommerce GetProfile wait | ≤ 25 s | `ShipCommerce.lua:129` | |
| ShipPerformance plain attributes | Section, Massing, Glass | `ShipPerformance.lua:38` | cull filter |
| ShipPerformance minimum frames | `DetectTime` × 4 = 12 | `:134` | no verdict below |

### 3.10 Settings: Roblox definition → Kotlin `Settings` field

| Roblox key | Kotlin field (today) | Port value | Notes |
|---|---|---|---|
| MouseSensitivity 0.25–2 (1) | `sensitivity` 0.5–1.5 (1) | keep 0.5–1.5, step 0.05, display `"%.2f×"` | the stick scales its deflection (`02` 9.1 decided the range) |
| InvertPitch (false) | `pitchUpClimb` (true) | `invertPitch = !pitchUpClimb` | also inverts the look-around |
| MouseYaw (false = ROLL) | `stickXRoll` (true) | `stickXRoll = !MouseYaw` | |
| FieldOfView 60–100 (80) | — | **new** `fovDeg` 60–100, step 1, default 80 | live into `Camera.fovY` |
| CameraShake 0–1 (1) | — | **new** `cameraShake` 0–1, step 0.05, default 1 | cockpit motion, entry, destruction |
| PerformanceMode | `quality` 0..2 | LOW = on | plus a persisted `qualityTouched` flag (8.4) |
| MusicVolume 0–1 (1) | — | **new** `musicVolume` | `09` |
| EffectsVolume 0–1 (1) | — | **new** `effectsVolume` | `09` |
| — (J key, session only) | — | **new** `musicOn` (default `Audio.Music.Enabled` true) | `09` |
| — | — | **new** `callsign` (≤ 16 chars, A–Z 0–9 space and -) | D29 |
| — | — | **new** `flatHudInCockpit` (true on phones) | `06` decision 13 |
| — | — | **new** `lockArrow` (true) | `06` (Kotlin's off-screen lock arrow) |
| — | `fpsMode`, `showFps`, `leftHanded`, `haptics`, `livery` | keep | phone additions (livery may move to the shipyard, `07` Q8) |

---

## 4. State machines, timings, thresholds and edge cases

### 4.1 Session flow (app level)

**Roblox:**

```text
JOIN ──joinMode (teleport arrival)──▶ ARRIVAL COVER (black, title per mode) ─────────────────────────┐
  └─normal join──▶ MENU (ShipMenu:run yields) ──LAUNCH(mode)──▶ black curtain                         │
        new pilot and Training.Required ─▶ mode = TRAINING ─▶ TRANSFER (teleport to the training server)
        SOLO with a menu ─▶ TRANSFER (teleport to a private server; stays here if it fails)            │
                                         ▼                                                            │
  LOADING: apply performance values → build modules → fetch profile (≤ 25 s) → build system           │
           (galaxy record ≤ 8 s) → place ship → ShipReady → bind ShipUpdate ◀──────────────────────────┘
                                         ▼ 2 rendered frames (MainMenu present, ≤ 8 s wait)
  REVEAL: curtain 0.6 s Sine Out (arrival cover 0.8 s) + interior:boot() (2.6 s) + afterReveal()
          safety: the curtain opens itself 12 s after the menu closed
                                         ▼
  IN WORLD (the per-frame branches of 4.2); there is no way back to the menu inside a session
```

**Port** (singleplayer; `DECISIONS.md` D13, D23, D28; `07` 4.1-4.2 owns the menu pages):

```text
APP START → load settings + save, LocalServices.start(save) → MENU
MENU ──LAUNCH (SOLO; TRAINING if offered)──▶ FADE OUT (07 timing) ──▶ LOADING (black)
LOADING: build the saved system, place the ship (4.6), run frames under the curtain;
         when the second frame after the build has rendered ──▶ REVEAL (0.6 s Sine Out, cockpit boot 2.6 s,
         welcome voice +5 s, afterReveal: services if docked, training if chosen) ──▶ IN WORLD
IN WORLD ──PAUSE / Back / app to background──▶ PAUSED (simulation frozen) ──RESUME──▶ IN WORLD
PAUSED ──SETTINGS──▶ SETTINGS ──DONE──▶ PAUSED;   PAUSED ──MAIN MENU──▶ save, FADE ──▶ MENU
safety: LOADING longer than 12 s ──▶ reveal anyway and log
```

### 4.2 In-world modes and frame branches

Every frame picks the first matching branch (`:2327-2344`):

| Priority | Condition | Branch | Flight sim | Camera owner |
|---|---|---|---|---|
| 1 | `decks:isActive()` | A: deck frame | rests | ShipCamera (seated, parked cockpit), aboard camera (on foot aboard in the hangar), Roblox camera (walking on the deck) |
| 2 | `destruction:isActive()` | B: destruction frame | rests | ShipDestruction |
| 3 | otherwise | C: flight frame | runs | ShipCamera, or ShipAboard while on foot |

Flags derived inside the flight frame (C1-C7): `onFoot` (aboard active), `travelling` (warp TUNNEL/ARRIVE), `controlled` (docking AUTO/CLAMPING/DOCKED/LAUNCHING or guidance phase), `busy` (`warnings:isBusy()`: crash fade running), `docked`, `surface` (landing scene), `atmosphere state`, `shipLanded`.

### 4.3 Player states and transitions

| State | Roblox condition | Entered by | Left by | Branch | Pilot input | Port |
|---|---|---|---|---|---|---|
| MENU | `ShipMenu:run()` waiting | join | LAUNCH | none (menu camera) | menu | MainMenuScreen (`07`) |
| TRANSFER | teleport running | training / solo routing (S6, S7) | the new server | none | none | drop |
| LOADING | after `run()`, before `reveal()` | LAUNCH | reveal (2 frames after the build) | C runs under the curtain | (flight keys already live) | curtain blocks input |
| FLYING | docking FREE (or REQUESTING/GRANTED), warp IDLE, atmosphere SPACE, no deck, no cinematic, not aboard | reveal, released after launch, warp arrival, respawn, ascent | the states below | C | full | keep |
| AUTOPILOT (sub-state) | `autopilot:isEngaged()`: ALIGNING → CRUISING → BRAKING | P with a lock, in SPACE, not travelling | P ("STANDBY"), steering > 0.5 or Q/E or FA off ("MANUAL OVERRIDE"), arrived ("ARRIVED"), target gone ("TARGET LOST"), atmosphere ("ATMOSPHERE"), docking controls ("STANDBY") | C | autopilot writes aim, roll and throttle | keep (`02` 3.7) |
| WARP PLOTTED | warp ALIGN or COUNTDOWN | star map confirm (allowed only in SPACE, no station mass lock, docking FREE) | countdown done → TUNNEL; Backspace / cockpit jump / crash ("DESTROYED") / atmosphere ("ATMOSPHERE") → IDLE; alignment lost in the countdown | C | full (the pilot aligns) | keep (`07` 4.5) |
| WARP TRAVEL | warp TUNNEL or ARRIVE | countdown end | ARRIVE end → IDLE | C | none (inputs off; cursor centred at start and end) | keep; the system build runs off the GL thread |
| DOCKING REQUEST | docking REQUESTING → GRANTED | K within 6 km of a station | K (cancel), grant expiry (600 s), P → AUTO, touchdown → CLAMPING | C | full | keep (`08` 4.1) |
| DOCKING (computer) | AUTO | P while granted | manual input → GRANTED; touchdown → CLAMPING | C, controlled | none; steering > 0.5 or a held thrust/yaw key takes over | keep |
| CLAMPING | CLAMPING | touchdown on the pad | clamps closed (1.6 s) → DOCKED | C, controlled | none | keep |
| DOCKED | DOCKED | clamps; start docked | K or LAUNCH → LAUNCHING (with a hangar deck: the lift first) | C, controlled, services open | services UI | keep; services are a non-pausing overlay |
| ON DECK (seated) | decks active, mode SEATED | `onDocked` with a hangar deck (`decks:arrive`), start docked | launch (lift up 5 s) → DOCKED → LAUNCHING; disembark → walking | A | look-around, services | `09` (later phase) |
| ON DECK (walking) | decks mode WALKING (`Runtime.Deck`) | DISEMBARK | board → seated; elevator → other decks | A | walker | `09` |
| LAUNCHING | LAUNCHING | launch | release → FREE (`onReleased(velocity)`: throttle 0, cursor centred) | C, controlled | none | keep |
| ATMOSPHERE ENTRY | atmosphere ENTRY (phases HEAT → DESCENT → LEVEL) | L in range (request valid 3 s) | hand-over (900 m, 320 m/s per D14) → SURFACE; abort (crash) → SPACE | C, guiding = controlled | none until the hand-over | becomes the optional **landing assist** (D14); seamless otherwise |
| ON SURFACE | atmosphere SURFACE (landing scene active) | guidance release | climbing above the exit altitude → EXIT | C | full | seamless near band (`02` 9.5, `04`) |
| LANDED (flag) | `shipLanded` (ground within clearance + 0.6) | touchdown | lift-off | C | full | from ground contact (`04` 9.10) |
| ATMOSPHERE EXIT | atmosphere EXIT (phases CLIMB, EMERGE) | ascent request | emerge → SPACE | C, guiding | none | drop (seamless climb) |
| ABOARD | aboard state STANDING / WALKING (`Runtime.Aboard`) | key 1 when `canStand` (2.1.14) | E at the helm (sit), crash (`aboard:reset`), ship switch, new character | C with `onFoot` | walker; the ship keeps autopilot or warp, else throttle 0 + FA | `09` (later phase) |
| CRASHING | `warnings:isBusy()` | `startCrash` | fade-in end | B (cinematic) or C | none during the cinematic; **live during the fade-in** (Roblox) | keep; block input until the fade-in starts |
| DESTROYED | `destruction:isActive()` | `startCrash` with the cinematic | `respawnAfterCrash` in the black | B | none | keep (`05` 2.7) |
| RESPAWN (instant) | inside `respawnAfterCrash` | black | at once → FLYING (+ fade-in) | | | keep |

Camera sub-states: COCKPIT ⇄ EXTERNAL (V, `toggleExternalView`), LOOKING (RMB held). Overlays (do not change the state): system map ⇄ star map (exclusive), station services, construction panel, training card.

### 4.4 Sub-machines owned by other areas (summary)

| Machine | States | Doc |
|---|---|---|
| Docking | FREE → REQUESTING → GRANTED ⇄ AUTO → CLAMPING → DOCKED → LAUNCHING → FREE | `08` 2.5, 4.1 |
| Warp | IDLE → ALIGN → COUNTDOWN → TUNNEL → ARRIVE → IDLE | `07` 2.5, 4.5 |
| Atmosphere | SPACE → ENTRY (HEAT, DESCENT, LEVEL) → SURFACE → EXIT (CLIMB, EMERGE) → SPACE | `05` 4.1; port D14 |
| Autopilot | STANDBY, NO TARGET, ENGAGED, ALIGNING, CRUISING, BRAKING, ARRIVED, TARGET LOST, MANUAL OVERRIDE, ATMOSPHERE | `02` 3.7 |
| Decks | inactive, SEATED, WALKING (+ busy sequences: arrive, launch, disembark, board, travel) | `09` 2.1, 4.1 |
| Aboard | NONE, STANDING, WALKING, SITTING, LEAVING | `09` 2.4, 4.3 |
| Warnings crash | idle → delay → fade out → onBlack → hold → fade in → idle | 4.7 |

### 4.5 Input gating (Roblox behaviour)

✓ = works, — = ignored, text = condition. "Deck" = `Runtime.Deck`, "Aboard" = `Runtime.Aboard`.

| Input | Free flight | Warp travel | Controlled (dock computer, guidance) | Map open | Services open | Deck | Aboard | Cinematic |
|---|---|---|---|---|---|---|---|---|
| Mouse → cursor, Q/E | ✓ | zeroed (C7) | zeroed; still counts as `steering` (takes over the docking computer) | mouse free: no deltas; Q/E ✓ | mouse free unless RMB | seated: cursor moves, no effect | — | — |
| W/S, A/D, R/F, Space/L-Ctrl, X | ✓ | — (input off) | — | ✓ (keyboard still flies) | ✓ | — (no flight frame) | — | — |
| Z (FA), B (boost) | ✓ | Z ✓, B consumed by thrust rules | Z ✓, B cleared each frame | ✓ | ✓ | — | — | — |
| T, Y | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — |
| N, M | ✓ | ✓ | ✓ | toggles | ✓ | — | — | — |
| Enter | star map open | | | star map | | — | — | |
| P | engage needs a lock, SPACE, not travelling | toggles off only | docking computer key while granted | ✓ | ✓ | — | — | — |
| L | in SPACE | accepted, no landing ("Landing blocked" after 3.1 s) | ignored while the dock computer flies | ✓ | ✓ | — | — | — |
| K, V | ✓ | ✓ | K cancels or launches | ✓ | ✓ | — | — | — |
| Backspace | ALIGN or COUNTDOWN | — (tunnel) | ALIGN/COUNTDOWN | ✓ | ✓ | — | — | — |
| G (hold) | locked, in range, aligned, SPACE | — | — | ✓ | — (docked) | — | — | — |
| H (hold) | landing scene, SURFACE, ready | — | — | ✓ | — | — | — | — |
| U | after reveal | — (`canOpen`) | ✓ | ✓ | ✓ | ✓ | — | — |
| J | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| 1 (leave seat) | `canStand` | ✓ (allowed in a jump) | — | ✓ | — | HANGAR and `canDisembark` | — (already up) | — |
| C, Left Alt | ✓ | ✓ | ✓ | ✓ | ✓ | seated ✓ | — | — |
| RMB look | ✓ | ✓ | ✓ | — (mouse free) | captures the mouse while held | seated ✓ | aboard camera | — |
| Cockpit click | pointer mode only (Alt-free mouse or look centre) | ✓ | ✓ | — (pointer nil) | — | seated ✓ | — | — |

Additionally every key handler ignores `processed` input (a GUI or the chat consumed it), and polled keys are ignored while a text box has focus (`GetFocusedTextBox`).

### 4.6 Spawn rules

| Situation | Roblox | Port |
|---|---|---|
| First start | a new save is docked at SOL-MERIDIAN (`ShipData.lua:80`), so the pilot starts on a reserved pad there; without any profile (offline, Studio) `spaceSpawnPose()` = `START_SPAWN` (0, 5000, −900,000), looking −Z, pushed out of bodies | **docked at MERIDIAN with 1,500 CR** (D13) |
| Start, left docked | the server reserves a pad at that station → `docking:startDocked`, pad pose; no free pad → `stationSpawn(station)` | docked at that station (singleplayer: a pad is always free); afterReveal opens the services |
| Start, left in flight / landed / mid-jump | at the system's star: `lookAt(starArrival, star)` (`arrivalPoint` + `clearOfBodies`); the stored space position is ignored | **the exact saved pose** (D23): frame body or station + relative position, look; velocity 0, throttle 0 (decision 10.1-6); landed → on the ground at that spot; mid-jump → at the target system's arrival point |
| Saved position unusable (other `positionFrame`, missing system, inside a body) | in front of the last station | same: `stationSpawn(lastStation)`, else star arrival |
| Warp arrival | `clearOfBodies(arrivalPoint(def))`, facing the star; `spaceSpawn` = facing away | keep (×S through the star radii) |
| Crash recovery | `stationSpawn(nearest station to the crash, else LastStation, else Station)`, else `spaceSpawnPose()` of the current system | keep |
| Failed system build in a jump | Sol, `START_SPAWN` | Sol, in front of MERIDIAN (START_SPAWN at S is 2.7 Gm from anything familiar) |

### 4.7 Timelines

**Start and reveal** (Roblox; 0 = LAUNCH):

| t | Event |
|---|---|
| 0 | LAUNCH; the menu fades to black and `run()` returns (`07` 4.2) |
| +≤ 10 s | training check (waits for `TrainingDone`), possible teleports |
| … | modules built, profile fetched (server waits ≤ 25 s), system built (galaxy ≤ 8 s), ship placed, render step bound |
| build + 2 frames | `menu:reveal()`: curtain 0.6 s Sine Out; `interior:boot()` (2.6 s boot, then a 12 s control hint); `notices:setEnabled(true)`; services if docked; training starts |
| close + 12 s | self-reveal if nothing revealed the curtain |
| character + 5 s | "Ship computer online.", "All systems online." |

**Crash with cinematic** (0 = `startCrash`; `Destruction`): 0 – voice "Hull failure.", cinematic starts, flight UI hidden, hull hidden, audio muted, music hushed, warnings busy · title cue – voice "Ship destroyed." and the loss lines · **11.0 s** – blackout fades in over **1.2 s** (Quad Out) · **12.2 s** – `respawnAfterCrash` in the black: cinematic ends, systems reset, ship in front of the recovery station, cockpit reboot, voice "Rebooting." · hold **1.4 s** · **13.6 s** – warning state reset, fade out of the blackout over **1.2 s** · **14.8 s** – warnings idle; voice "All systems online." (12.2 + 2.6). The plate's countdown runs to `Duration + FadeOutTime` = 12.2 s.

**Crash without cinematic** (`Warnings.Crash`): blackout 0.30 s → `respawnAfterCrash` at 0.30 s → hold 1.6 s → fade in 0.9 s → idle at 2.8 s; "All systems online." at 2.8 s.

**Debug slow motion**: `crash … slow` (0.05–1) stretches the cinematic delay to `Duration / slow`.

### 4.8 Thresholds the loop applies itself

| Threshold | Value | Where |
|---|---|---|
| steering takes over the docking computer | cursor > 0.5 of the radius, or Q/E | C7 |
| autopilot manual override | cursor > 0.5, or Q/E, or FA off | C11 |
| heat death | ship heat ≥ 2.0 | C28a |
| ground crash (scene / old surface) | impact > 38 / closing speed > 20 m/s on visible ground | C28b-c |
| station crash | hit speed > 45 m/s; hit sound > 4 m/s; voice > 12 m/s | C30 |
| gear down | scene altitude < 60, or docking wants it | C38 |
| dust "danger" | time to impact < 10 s or ship heat ≥ 0.8 | C66 |
| attitude danger | time to impact < 10 s | C69 |
| stand up | speed < 30 m/s in space (or autopilot, or jump); on the ground landed and < 2 m/s | 2.1.14 |
| JUMP BLOCKED | station mass lock (within 6 km) or docking busy | 2.1.6 |

### 4.9 Edge cases (from the Lua)

1. **The frame a deck session ends** returns early (A4): no notices, services or voice update in that frame; the flight frame starts next frame.
2. **Fade-in after a crash**: the flight frame runs while warnings are busy. No crash checks, no station collisions, no traffic, no survey and no position report, but the flight inputs are live: a pilot can fly blind for 1.2 s (0.9 s without cinematic). The port blocks the flight input until the fade-in starts.
3. **`gravityState.heat` changes meaning** at C27 (star heat 0..1 → ship heat 0..2.2). Everything after C27 (warnings, dust mood, callouts) reads the ship heat.
4. **The autopilot reads last frame's limit** (`thrust.speedLimit` before C21); the flight arrow gets this frame's raw limit (C68).
5. **The flight arrow always shows the pilot's cursor**, also under autopilot; the cockpit stick shows the autopilot's axes (C36).
6. **A map open does not stop the keyboard**: W/S, strafe, FA, boost and target keys keep working; only the mouse is freed. The cursor recentres both when the map opens and when it closes (C52), so an alignment for a jump is not silently lost.
7. **Warp travel start and end** recentre the cursor (C6); the cursor deflection from before the jump never acts on arrival.
8. **Cockpit FA and BOOST buttons** bypass the deck/aboard gate (they cannot be pressed on foot anyway: the pointer is nil).
9. **LeaveSeat and the station panel work on decks**; ToggleMusic works everywhere.
10. **`watchLanding` tickets**: a second L restarts the 3.1 s window; "Landing blocked" only if the atmosphere is still SPACE and no crash runs.
11. **`clearOfBodies` is a single pass** in tag order: a push out of one body can, in theory, land inside another.
12. **`stationSpawn` without an id** takes the station nearest to the ship; it returns nil while the station's host is unparented (landing scene) → `spaceSpawnPose()`.
13. **A crash in the landing scene** first converts the pose back to space, ends the scene and resets the atmosphere, then places the replacement ship.
14. **Double crashes** are impossible: `startCrash` returns while warnings are busy or the cinematic runs.
15. **The edge toast** waits 30 s after the *last braking frame*, not after the last toast: holding against the edge gives one toast. The barrier toast likewise waits 4 s after the last contact.
16. **FIRST VISIT** requires the server visit time within 300 s of the galaxy clock and shows once per system per session.
17. **A failed system build in a jump** lands in Sol at `START_SPAWN`, and the tunnel still ends (`markBuildDone` always runs).
18. **dt is capped at 0.1 s** without catch-up: below 10 fps the game runs in slow motion.
19. **Ship switch in the external view** keeps the external view and the new cockpit starts external.
20. **The welcome voice** waits for the character: on a slow spawn it is late; it plays once per session.
21. **Report position** runs only in free flight in space; docking, jumps and losses are saved by their own events, landed positions not at all.
22. **Menu failure** starts the flight directly (no curtain, `launchMode` stays SOLO).

---

## 5. On screen

This area draws little of its own: the start curtain, the open animation of full-screen views, the decision what is visible in which state, and the texts the loop issues through other modules (toasts, voice, prompts, terminal lines). The HUD, cockpit, notices and menu visuals are specified in `06`, `07` and `08`.

### 5.1 Start curtain and reveal

| Element | Roblox | Port |
|---|---|---|
| Curtain | the `MainMenu` ScreenGui stays enabled after LAUNCH with `DisplayOrder 1000` (above every flight GUI, they use ≤ 100); its full-screen fader Frame (black) at transparency 0; the menu canvas hidden (`ShipMenu.lua:405-425`) | a full-screen black rect drawn last (above HUD and touch controls), `Game.fade = 1` |
| Reveal | `fader.BackgroundTransparency` 0 → 1 over **0.6 s, Sine Out**; then the menu GUI is destroyed (`ShipMenu.lua:246-267`) | opacity `1 − sin(π/2 · t/0.6)`, t from 0 to 0.6 s; starts when the second frame after the build has rendered |
| Arrival cover (after a teleport) | a black cover with a centred box: caption, title, body and a wandering progress bar; titles "ENTERING SOLO PLAY", "JOINING THE GALAXY", "JOINING YOUR FRIEND", "PREPARING YOUR TRAINING FLIGHT"; bodies "A PRIVATE SESSION JUST FOR YOU, NO OTHER PILOTS AROUND. YOUR SHIP IS BEING PREPARED." or "PREPARING YOUR SHIP ..."; reveal 0.8 s (`ShipTraining.lua:858-890`) | no teleports. Proposal: if LOADING lasts longer than 0.8 s (large systems, terrain), show the body text "PREPARING YOUR SHIP ..." (Titillium SemiBold 11 u, Dim colour) and the wandering bar centred on the curtain |
| Safety | the curtain opens itself 12 s after the menu closed | same, measured from the end of the fade-out |
| Cockpit boot | starts with the reveal: 2.6 s boot, then a 12 s control hint (`06` 3.4.2) | same |

### 5.2 Open animation of full-screen views (`ShipUi.animateOpen`)

When a full-screen view opens (system map, star map, station services, construction panel; `07`, `08`): the canvas starts `px(14)` lower and slides to its place, and a veil in (6, 10, 16) starts at 65 % opacity and fades out, both over **0.22 s, quint-out** (`1 − (1 − t)^5`). The veil sits above the canvas content (ZIndex 10000) and hides itself at the end. Phone: rise 14 design px = 8.4 u (5.7); same timing and colour.

### 5.3 What the loop hides and shows

| Situation | Hidden | Saved and restored | Lines |
|---|---|---|---|
| Station deck (`hideSpace` / `showSpace`) | solar system (unparented), NPC traffic (cleared), stations, markers, flight cursor, speed lines, dust, HUD GUI, warnings GUI | markers, HUD and warnings enabled-states; the solar system comes back unless the landing scene is active; the cursor is re-applied next frame; the cockpit hint GUI is re-enabled | 1698-1747 |
| Walking on a deck (deck frame) | cockpit hint and key-label GUI (`interior.gui`) | re-enabled when seated again (unless aboard) | 2110-2112 |
| Walking aboard (`hideFlightUi` / `showFlightUi`) | open maps (closed), construction panel (closed), markers, cursor, speed lines, dust, painted cockpit, HUD GUI, cockpit hint GUI, cockpit pointer | markers and HUD states and **Flight Assist**; on sitting: cockpit and interior visibility by view, cursor recentred | 1828-1884 |
| Crash cinematic (`setFlightUiHidden`, every frame) | maps (closed), markers, cursor, speed lines, dust, every enabled ScreenGui except the warnings GUI and the "ShipDestroyed" plate; the hull itself (`hull:setVisible(false)`); audio muted, music hushed | the list of GUIs disabled at the first call | 1369-1412, 1616-1622 |
| Looking around, docked, on foot | flight cursor | — | 2797-2802 |
| Landing scene (Roblox) | markers (on the way down) | shown again on the way up | 508, 519 |
| External view | painted cockpit, cockpit interior parts; flat HUD stack and target panel **shown** | per view | 937-960 |

### 5.4 Texts the loop issues

**Toasts** (`notices:toast(title, body, danger, seconds)`; default duration 4 s, `06` 3.9):

| Title | Body | Danger | Time | Trigger | Port text (touch) |
|---|---|---|---|---|---|
| FIRST VISIT | `<SYSTEM>  ·  NO PILOT HAS BEEN HERE BEFORE. YOUR NAME IS ON THE CHART.` | no | 7 s | first visit of a system (2.1.3) | same (D25 keeps the first-discovery bonus) |
| JUMP BLOCKED | `STATION MASS LOCK  ·  FLY 6 KM AWAY` | yes | 4 s | jump near a station | `STATION MASS SHADOW  ·  FLY 6 KM AWAY` (D30) |
| SHIP NOT AVAILABLE | `<ID> IS NOT IN THIS BUILD YET` | yes | 4 s | missing model | same (should not happen, D18) |
| WALKABLE INTERIOR | `PRESS 1 TO LEAVE THE SEAT AND WALK AROUND ON BOARD.` | no | 6 s | switching to a walkable ship | `TAP WALK TO LEAVE THE SEAT AND WALK AROUND ON BOARD.` |
| SHIP DESTROYED | `REPLACEMENT FEE <cr>  ·  <n> T CARGO LOST  ·  DATA LOST <cr>` | yes | 7 s | losses without a cinematic (with it, the text goes on the plate) | same |
| MINIMUM ALTITUDE | `HOLDING ABOVE <BODY>` (or `THE SURFACE`) | no | 3 s | barrier contact | dropped (no barrier) |
| SYSTEM EDGE | `THE SYSTEM ENDS HERE  ·  PRESS M FOR THE STAR MAP AND JUMP ON` | no | 6 s | edge braking, ≤ 1 per 30 s | `… ·  TAP MAP FOR THE STAR MAP AND JUMP ON` |
| CAN'T STAND UP | `THIS SHIP HAS NO ROOM TO WALK AROUND.` · `FINISH THE FLIGHT TRAINING FIRST.` · `STAY AT THE HELM WHILE DOCKING.` · `SET THE SHIP DOWN FIRST.` · `SLOW DOWN OR ENGAGE THE AUTOPILOT FIRST.` · or the reason from `aboard:stand()` (e.g. `THE RAMP ONLY OPENS IN A STATION HANGAR.`) | yes | 4 s | key 1 refused | same |
| ON FOOT | `WASD WALK  ·  SPACE JUMP  ·  R-MOUSE LOOK  ·  WHEEL ZOOM  ·  E AT THE HELM TO SIT DOWN` | no | 7 s | first 2 times standing up | `09` 8.5 (touch wording) |
| (arrival card) | title and subtitle from `atmosphere:takeArrival()` (body name, ground type, gravity) | no | 6 s | landed | `05` 2.1 |
| FRIEND NOT HERE / JOINED YOUR FRIEND | | | 7-8 s | OPEN PLAY join | dropped |

**Voice lines started by the loop** (texts and timing in `09` 3.7; keys of `ShipConfig.Voice`):

| Key | When | Lines |
|---|---|---|
| Welcome, SystemsOnline | 5 s after the first attach ("Ship computer online.", "All systems online.") | 1218-1223 |
| AutopilotOn / AutopilotOff / AutopilotNoTarget ("No target selected.") | P | 964-981 |
| AutopilotAtmosphere | autopilot meets an atmosphere | 2392 |
| AutopilotTargetReached / AutopilotOff | autopilot switched itself off | 2406 |
| AutopilotManualOverride | pilot override | 2415 |
| FlightAssistOn / FlightAssistOff | FA changed (any source) | 2930-2935 |
| LandingBlocked | L without a landing within 3.1 s | 1017 |
| HullImpact | station hull slide above 12 m/s | 2740-2742 |
| HullFailure | `startCrash` (after `voice:interrupt()`) | 1491 |
| ShipDestroyed, ReplacementFee, CargoLost, SurveyDataLost | cinematic title cue, then the losses | 1600, 1633-1640 |
| ReplacementReady | cinematic recovery cue | 1604 |
| Rebooting | `respawnAfterCrash` | 1435 |
| SystemsOnline | after the fade-in (2.5 / 2.6 s after the respawn) | 1462-1466 |
| ExtractionComplete | a ton mined | 719 |
| warp lines | `warp` → `onVoice(line)` | 916-918 |

**Prompt** (HUD prompt line, `06`): `hudPrompt` joins the landing prompt and the docking prompt with a line break when both exist in SPACE (e.g. "PRESS L TO LAND" over "K   REQUEST DOCKING   ·   MERIDIAN STATION").

**Crash plate** (cinematic, `05` 2.7): detail `"<SHIP>  ·  <WHERE>"` with WHERE ∈ `BURNED UP NEAR <BODY>`, `BURNED UP`, `COLLIDED WITH <STATION>`, `COLLIDED WITH A STATION`, `CRASHED ON <BODY>`, `CRASHED`; station line = recovery station name in upper case; losses line as the toast body.

**Terminal lines aboard** (`aboardStatus(kind)`, `:1888-1972`; `09` places the terminals):

| Kind | Text |
|---|---|
| Status | `<STATE>  ·  FUEL <f> %  ·  HEAT <h> %`; STATE = `IN HYPERSPACE` (travelling), `AUTOPILOT ENGAGED`, `DOCKED`, `LANDED ON <BODY>` (or `THE SURFACE`), `COMING TO A STOP` (speed > 1), `HOLDING POSITION`; f, h = `floor(x·100 + 0.5)` |
| Reactor | `COOLANT LOOPS RUNNING HOT` (heat ≥ 0.8) or `OUTPUT STABLE`, then `  ·  CORE HEAT <h> %` |
| Manifest | empty hold: `HOLD EMPTY  ·  <capacity> T FREE`; else `CARGO <used> / <capacity> T` + up to 3 × `  ·  <t> T <COMMODITY>` (largest first) |
| Survey | scanner = `LONG-RANGE ARRAY  ·  +<p> % RANGE` (ship perk `ScanRange` > 1, p = round((reach − 1)·100)) or `SCANNER READY`; no data: `<scanner>  ·  NO DATA ON BOARD`; else `<scanner>  ·  <n> SURVEY(S) ON BOARD  ·  <credits>` |
| Mining | lasers = `TWIN LASERS  ·  <k>× SPEED` (perk `MineSpeed` > 1, `%g`) or `LASERS CHARGED`; `<lasers>  ·  LAND AND HOLD H  ·  CARGO <used> / <capacity> T` (touch: `TAP MINE`) |
| Refinery | no ore: `CRUSHERS IDLE  ·  NO ORE IN THE HOLD`; else `CRUSHERS RUNNING  ·  ` + up to 3 × `<t> T <RESOURCE>` |

### 5.5 Settings rows and the key list

The settings rows (labels, hints, formats) are in 2.9; the OPTIONS screens are drawn by `07` (5.3-5.4). The CONTROLS panel lists every key (`ShipMenuData.KeyBindings`, `ShipMenuData.lua:203-261`), the key cap text from `ShipKeys.label` at runtime:

| Column 1 | Rows | Column 2 | Rows |
|---|---|---|---|
| FLIGHT | THROTTLE UP, THROTTLE DOWN, THRUST LEFT, THRUST RIGHT, THRUST UP, THRUST DOWN, YAW LEFT, YAW RIGHT, FULL STOP (HOLD), FLIGHT ASSIST, BOOST | NAVIGATION | NEXT TARGET, CLEAR TARGET, AUTOPILOT / DOCKING COMPUTER, SYSTEM MAP, STAR MAP, CONFIRM JUMP, CANCEL JUMP, LAND ON A PLANET, REQUEST DOCKING / LAUNCH, SURVEY (HOLD) |
| MINING & STATION | MINE BY HAND (HOLD, LANDED), SURVEY DATA / MY STATION | VIEW & SYSTEM | EXTERNAL VIEW, RELEASE MOUSE, CENTER FLIGHT ARROW, MUSIC ON / OFF |
| ON BOARD | LEAVE SEAT / WALK ABOARD | | |

Port: the same groups with the touch control name in the key cap (and the gamepad button when a pad is connected), from the table in 8.1; RELEASE MOUSE and CENTER FLIGHT ARROW are dropped on touch; "DOCKING COMPUTER" reads "AUTO-DOCK" (D30).

### 5.6 Phone flight controls: one consolidated layout (20:9, in u)

Every area proposed its own buttons (`02` ◀ ▶ and AUTO, `06` look gesture, `07` CANCEL JUMP, `08` SCAN/MINE and DATA, `09` WALK). They must share one screen. Reference: Xiaomi 15 landscape 2670 × 1200 px at 2.875 px/dp = **929 × 417 u** (as `06` 6.9); right-handed (mirror for left-handed as `TouchFlightControls.layout` does); offsets from the safe insets. **Existing** rects are from `TouchFlightControls.layout` (`input/TouchFlightControls.kt:118-148`).

| Element | Rect / centre (u) | Visible when | Source |
|---|---|---|---|
| PAUSE | x 14–60, y 12–50 | always in flight | existing |
| WALK | x 70–132, y 12–50 | the ship is walkable; a tap runs `canStand` and toasts the reason if refused | new (`09`) |
| Autopilot label | centre x, y 14–32 | autopilot engaged | `06` 6.9 |
| DATA | x 655–711, y 12–50 | always (opens the construction panel when allowed) | new (`08`) |
| MAP | x 721–777, y 12–50 | always | existing |
| LOCK | x 787–849, y 12–50 (tap cycle, hold 0.5 s clear) | always | existing (`06` hold) |
| VIEW | x 859–915, y 12–50 | always | existing |
| Look strip | x 150–640, y 0–56 (one-finger drag; release returns the view) | in flight, cockpit or external | new (`02`, `06`) |
| ACTION pill | x 822–914, y 75–109 | a label exists (LAND, DOCK, CALLING…, CANCEL, LAUNCH, TAKE OFF) | existing (`04`, `08`) |
| AUTO pill | x 822–914, y 117–149 | a target is locked, the autopilot is engaged, or docking is GRANTED/AUTO; labels AUTOPILOT (lit when engaged), AUTO-DOCK, MANUAL | new (`02`, `08`) |
| FA | circle (776, 108) r 23 | always | existing |
| BOOST | circle (868, **192**) r 31 (was 167, moved down for AUTO) | always | existing, moved |
| SCAN / MINE | x 60–140, y 74–106 | survey possible (body locked) / mining possible (landed with a deposit) | new (`08`) |
| Throttle lever track | x 30–44, y 125–365; the whole left 42 % is the lever zone | always | existing |
| Strafe cross | ▲ (130, 140), ◀ (86, 177), ▶ (174, 177), ▼ (130, 214), r 19 each (was ▲ ▼ at x 104) | always | new layout (`02`) |
| Stick | floating in the right 58 %; rest ring (779, 307) r 52 | always | existing |
| CANCEL JUMP | 140 × 36 centred under the warp status line | warp ALIGN or COUNTDOWN | new (`07` 5.10) |
| Docking aid, flat stack (`06`) | shift right by 60 u to x 200–410 | as `06` | adjusted to clear the strafe cross |

```text
 ┌ II  WALK ·········· look strip ··········· AUTOPILOT CRUISING ······· DATA  MAP  LOCK  VIEW ┐
 │  [SCAN]                                                                    (FA)  [ LAND  ]  │
 │ THR    ▲                                                                         [AUTOPILOT]│
 │  ▮  ◀     ▶    ┌ docking aid ┐                                                     (BOOST)  │
 │  ▮     ▼       └─────────────┘            · ▲ flight arrow                                  │
 │  ▮                                                                                          │
 │  ▮                                   [ CANCEL JUMP ]                         ( stick )      │
 │  ▮ 260 m/s                    ┌──────── survey bar ────────┐                                │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

Touch targets are ≥ 32 u in their short side and ≥ 38 u where they are pressed while flying (≈ 6 mm at density 2.875). On 393 u tall screens the vertical positions scale by `h / 417` and stay clear of the lever track.

### 5.7 UI scale on the phone

Roblox scales every runtime GUI by `viewportHeight / 1080` (clamped 0.75–2.2). On a 1200 px phone that is 1.11: a 15 px label becomes 17 px ≈ 0.9 mm tall, unreadable. The port therefore sizes the flat UI from the layout unit `u`:

| Rule | Value |
|---|---|
| Layout unit | `u = Frame.u = density · min(1, h/(380·density), w/(720·density))` (`Game.kt:250`) |
| Design px → u (sizes, strokes, paddings, icons) | × **0.6** (`07` D3) |
| Text | `max(designPx · 0.6, 9) u` (Roblox's own floor is 8 px) |
| Touch targets | ≥ 38 u pressed in flight, ≥ 32 u elsewhere |
| Exceptions with explicit u tables | markers (`06` 6.9), star map field (`07` 8.1, a "picture" scaled by `max(H/1080, 0.5)`), 3D cockpit canvases (drawn in 3D, `06`) |
| Rounding | whole pixels after scaling (`px` rounds half away from zero) |
| Reference screens | Xiaomi 15 2670 × 1200: 872–971 × 392–436 u for densities 3.06–2.75 (the display-size setting); typical 2400 × 1080 at 2.625: 914 × 411 u; small 1600 × 720 at 2.0: 800 × 360 dp → `u` = 0.947 dp |

`08` and `09` size some panels with `designPx × 0.386 u` plus minimum sizes; whether those move to the 0.6 rule is listed in 10.2.

---

## 6. Dependencies on other modules and areas

The loop is the hub. What it hands each area and what it takes back:

| Area (doc) | Module(s) | The loop gives | The loop takes | Steps |
|---|---|---|---|---|
| Flight (`02`) | ShipInput, ShipHandling, ShipRotation, ShipThrust, ShipGravity, ShipSystems, ShipAutopilot, ShipCursor, ShipAttitude, ShipGear | dt; gated aim and key axis; `inputEnabled`; FA; gravity state with the composed limit and zone; station and edge limits; guidance overrides | pose, rates, velocity, accelerations (world and ship space), throttle, boosting, cruising, boost cooldown, limit and zone, fuel, heat, G, mass, agility, top speed, autopilot outputs | C1-C27, C34, C68, C69 |
| World (`03`) | ShipOrbits, ShipSystemBuilder, ShipStarCatalog, ShipRingDebris, ShipPlanetTexture, ShipGalaxy | system definition (on start and jump), clock, ship position | bodies (tagged parts with attributes), star danger radii (spawn), ring debris hits and heat | C14, C56, C65; `buildSystem`, `arrivalPoint` |
| Planet surface (`04`) | ShipSurface, ShipPlanetTerrain (old), ShipImpostors (old) | ship pose and velocity | ground contact (altitude, normal, ground state), gravity on the ground, the near-band limit | C17-C18, C28, C38, C61 (replaced by `04` 9.10) |
| Atmosphere / landing (`05`) | ShipAtmosphere, ShipEntryFx, ShipSky, ShipClouds | L key, gravity state, speed | state (SPACE/ENTRY/SURFACE/EXIT), guidance pose and velocity, shown speed and altitude, prompt, entry heat and colour, entry shake, arrival card | C3, C9, C26, C41, C59, C62, C63 |
| Cockpit and HUD (`06`) | ShipCockpit, ShipInterior, ShipHull, ShipHud, ShipMarkers, ShipDust, ShipNotices, ShipOverlay | `interiorState`, HUD state, felt acceleration, angular velocity, speed fraction, pointer mode, view, locked target | head offset, camera shake, `hasFlightHud`/`isHudOn`, locked target, ordered bodies, cockpit button actions (`onAction`) | C35-C40, C47, C48, C66, C67, C74 |
| Menu, maps, warp (`07`) | ShipMenu, ShipSystemMap, ShipStarMap, ShipWarp, ShipWarpTunnel | launch mode, reveal call, current system, confirm gate, arrival build | mode, map open states (mouse ownership), lock from the map, warp state (travelling), voice and sound events, `markBuildDone` | S4, C52-C60, 2.1.6 |
| Stations and economy (`08`) | ShipStation, ShipDocking, ShipStationUi, ShipSurvey, ShipMining, ShipConstruction, ShipProfile/ShipData, ShipCommerce | ship pose, velocity, `steering`, probe, fuel callbacks, survey and mining contexts | station limits and mass shadow, station frame carry, docking pose and velocity, docking state, prompts, wants-gear, station hits, profile (ship, fuel, location, cargo, survey) | C15, C19, C30, C70-C76, 2.1.3, 2.1.5 |
| Life, NPCs, audio (`09`) | ShipDecks, ShipDeckUi, ShipAboard, ShipTraffic, ShipTraining, ShipAudio, ShipVoice, ShipCallouts | deck and aboard contexts (2.1.12, 2.1.14), traffic context, audio state, callout frame, voice keys | deck active and walking, parked ship pose, aboard active, camera on foot, training wants-cursor | A, C31, C43-C46, C64 |
| Warnings and destruction (`05`) | ShipWarnings, ShipDestruction | gravity state (impact time, ship heat), crash parameters (2.1.10) | busy flag, crash timeline callbacks, cinematic cues | B, C45, C28, `startCrash` |
| Settings and input (this doc) | ShipSettings, ShipKeys, ShipInput capture | — | values for every module | 2.9, 2.8, 2.3 |

**Data contracts this area defines for the port:**

| Contract | Fields | Producer → consumers |
|---|---|---|
| `InputFrame` (per frame) | `aimX, aimY` (−1..1, Y down), `keyAxis` (+1 = left), lever, lateral X/Y, full stop, look deltas (deg) and look-held, action edges (pressed counts) and held flags per `Action`, `device` (touch, pad, keyboard) | touch, gamepad, keyboard → the session (C1), flight (`02` `PilotInput`), every area's discrete actions |
| `PlayerMode` (per frame) | branch (FLIGHT, DECK, DESTROYED), `onFoot`, `travelling`, `controlled`, `docked`, `crashing`, `landed`, overlay, camera view, looking | session → input gate, HUD visibility, audio, analytics |
| `Settings` | 3.10 | menu/options → everybody |
| `Units` | 2.11 | everybody who prints numbers |
| `UiScale` | 5.7 | every 2D screen |

---

## 7. Gap analysis against the current Kotlin code

| # | Roblox piece | Kotlin today | Status | Action |
|---|---|---|---|---|
| 1 | Render-step entry, `dt = min(dt, 0.1)` | `Game.drawFrame` (`Game.kt:297-334`): events → `input.drain` → `update` → `render`; dt from vsync, first frame 1/60, dt ≤ 0 → 1/120, capped 0.1 (`:301-304`) | exists | keep; insert `InputFrame` build before `update` |
| 2 | Three branches (deck, destruction, flight) | `Game.update` → `updateMenu` or `updateFlight` (`:604-617`) | partial | session dispatcher with the branches (9.2) |
| 3 | Flight-frame order (76 steps) | `updateFlight` (`:638-744`): touch, controls, button edges, `system.locate`, gear, landed hold, landing vertical, lift, docking, limit, ≤ 8 sub-steps of ≤ 1/120 s (`flight.step`, asteroid, body and station collisions), locate again, proximity, landing cancel, action label, message, lock validation, hints; then chase camera, FOV, dust, lighting | differs | rebuild in Roblox order (9.2); keep sub-steps for the move and collisions only (`02` 8, sub-steps row) |
| 4 | Maps, services and panels do not pause | `paused = overlay != OVERLAY_NONE` (`:639`): the map and the docked screen freeze the simulation | differs | pausing overlays = PAUSE and SETTINGS only (D21) |
| 5 | States (4.3) | `STATE_MENU / STATE_FLIGHT` + overlays SETTINGS, PAUSE, MAP, DOCKED (`:83-87`, `:1829-1838`); flags `landing`, `landed`, `docking`, `docked` (`:138-160`) | partial | `PlayerMode` + the area state machines; LOADING, DESTROYED, DECK, ABOARD, WARP missing |
| 6 | Launch, curtain, reveal, boot, welcome | fade by `expApproach` (in 14, out 5, `:606`); `startFlight` puts the ship at `system.home` moving at 30 % throttle (`:557-584`) | differs | LOADING with the curtain until two frames after the build, 0.6 s sine-out reveal, cockpit boot, welcome after 5 s; start docked (D13) or at the saved pose (D23) |
| 7 | Spawn rules (`arrivalPoint`, `clearOfBodies`, `stationSpawn`, crash recovery) | — | missing | `SpawnRules` (9.1) |
| 8 | Crash flow (`startCrash`, timelines, `respawnAfterCrash`) | collisions bounce with haptics and camera shake (`:789-823`, `:1666-1725`); no destruction | missing | crash flow here (`05` 4.7 agrees); cinematic and blackout in `05` 2.6-2.7 |
| 9 | ShipCamera (cockpit default, rigid external per ship, look-around, head offset, shake, FOV setting) | `ChaseCamera` (`game/World.kt:157-203`): chase 16.5 m back / 4 up, far chase 28 / 7, cockpit (0, 1.05, −2.6), rotational lag (rates 7.5 / 5), shake; FOV 52° + 3°·speed fraction + 9° boost, cockpit 60° (`Game.kt:709-717`) | differs | `ShipCamera` (9.1); default cockpit, V = external; the lagging chase as an optional third view (10.2) |
| 10 | Floating origin | `DVec3` positions, camera-relative `Camera` (`gfx/Camera.kt:10-14`), depth slices (`SceneRenderer.kt:61-69`) | exists | not needed |
| 11 | Character rig, spawn lock, ready handshake | — | not needed | walker in `09` |
| 12 | Settings model | `Settings.kt:4-66`: quality, fpsMode, stickXRoll, pitchUpClimb, sensitivity 0.5–1.5, leftHanded, haptics, showFps, livery; persisted as `k=v;` | partial | add the fields of 3.10 |
| 13 | Settings screens | `SettingsScreen` (`ui/Screens.kt:118-186`): GRAPHICS and CONTROLS columns | partial | OPTIONS rebuild in `07` |
| 14 | Performance mode and detection | `quality` 0..2 → render scale and asteroid count; first-run GPU heuristic (`Game.kt:266-271`, `:1807-1827`) | partial | the Low table per module (3.5), frame-time median, `qualityTouched` (8.4) |
| 15 | Key bindings (28 actions) | `TouchFlightControls` (lever, stick, ▲▼, BOOST, FA, VIEW, LOCK, MAP, ACTION, PAUSE) and gamepad in `readControls` and `updateFlight` (`:644-652`, `:746-787`) | partial | `Action` layer with gating (9.1); missing touch: ◀ ▶, full-stop hold, AUTO, clear target, SCAN/MINE, DATA, WALK, CANCEL JUMP, look, music (8.1) |
| 16 | Key names in texts | fixed strings | missing | `InputLabels` (`06`) on the `Action` enum |
| 17 | Units | `Game.formatSpeed`/`formatDistance` (`:1757-1780`): "M/S" < 1000 padded to 3 digits, "KM/S" < 1e6 with 2 decimals, "MM/S" above; "M", "KM" (2 decimals), "MM" (2 decimals); `MapScreen.distanceText` (`ui/MapScreen.kt:496-506`): other decimals, "MM" | differs | one `Units` (2.11); "Mm" never "MM"; grouping; NaN → "--" |
| 18 | UI scale | `Frame.u` from density with a shrink for small screens (`Game.kt:239-254`) | exists (other principle) | the 5.7 rules on top |
| 19 | Open animation | overlays appear at once; only launch and quit fade | missing | `UiScale.openTransition` (5.2) |
| 20 | Exclusive full-screen views | one `overlay` slot | exists | keep |
| 21 | Maths helpers | `expApproach`, `approach`, `deadZone` (1D), `expoCurve`, `smoothstep` (`math/MathX.kt`) | partial | add `smooth(c, t, τ, dt)` and the radial `applyDeadzone` (2.7) |
| 22 | Networking, server entry | — | not needed | `LocalServices` (2.13) with `08`'s profile, commerce and save manager |
| 23 | Debug commands | `Game.debugCommand` (`:290`): launch, menu, settings, pause, resume, fa, boost, camera, map, lock, action, teleport-low, teleport-surface, lock-outpost, dock, docked, behind-station, ram-station, livery (`:336-368`) | partial | add the ShipDebug set (9.6) with arguments |
| 24 | Pause, Back, background | `onBack` (`:393-402`), `onPause` → pause overlay (`:282-285`, `:369-374`), gamepad START/B (`:384-390`) | exists (phone addition) | keep; Back during a plotted jump does not cancel it (`07` D8) |
| 25 | Frame pacing, active play | `Platform.setActivePlay`, `applyDisplaySettings`; vsync pacer and `Surface.setFrameRate` (`GameSurfaceView.kt:196-207, 290-302`) | exists | keep; 8.4 on the default frame rate |
| 26 | Haptics | `Platform.haptic`, `Haptics` (`Platform.kt:15, 38-43`) | exists (phone addition) | keep |
| 27 | Zero allocation per frame | desktop `perf` scenario with the thread allocation counter (`desktop/.../Perf.kt`) | exists | run it on the new session |
| 28 | Toasts and voice from the loop | `showMessage` one-line message (`:1049`), zone label | partial | `Notices` (`06`), `Voice` (`09`) with the 5.4 texts |
| 29 | Position report and saves | — | missing | autosave every 10 s in free flight, on pause, background, dock, undock, jump, landing (D23, `08`) |
| 30 | Landing L, gear, attitude | Kotlin landing mode (`:966-1221`: LANDING_SPEED 200, touchdown < 8 m/s and < 40 m/s, gear 2.2 s) | differs | seamless near band, landing assist (D14), Roblox gear rule (`02` 3.8), attitude rule (D15) |
| 31 | Docking K, docked screen | Kotlin auto-dock into a slot (`:1223-1333`), `DockedScreen` (`ui/Screens.kt:219-244`) | differs | `08` |
| 32 | Off-screen lock arrow | `drawLockArrow` (`:1564-1603`) | Kotlin only | keep behind `lockArrow` (`06`) |

---

## 8. Phone adaptation

### 8.1 Every keyboard and mouse input → touch and gamepad

The master table. Area docs own the details of their controls (linked); this table makes sure every input exists exactly once on each device. Positions of the touch controls: 5.6.

| Roblox input | Action | Touch | Gamepad | Notes |
|---|---|---|---|---|
| Mouse Y → cursor | pitch | right floating stick, Y (springs back on release) | left stick Y | `02` 9.1: Roblox dead zone 0.20 and curve 1.7 in rotation |
| Mouse X → cursor | roll (ROLL) or yaw (YAW) | right stick, X | left stick X | setting STICK X AXIS |
| C | centre the flight cursor | lift the thumb (the stick recentres) | release the stick | no control needed |
| Left Alt | free the mouse | — | — | not needed |
| W / S | throttle lever up / down | left lever drag: absolute position, −25 % to 100 %, zero line at 20 % of the track, detent with haptic tick | RT / LT ramp the lever (25 %/s × trigger; zero detent) | D19, `02` 9.1 |
| X (hold) | full stop | double-tap **and hold** the lever | R3 held | D19, `02` 9.2 |
| A / D | strafe left / right | ◀ ▶ of the strafe cross | LB / RB | `02` |
| R / F, Space / L-Ctrl | strafe up / down | ▲ ▼ | right stick Y | |
| Q / E | yaw (ROLL) / roll (YAW) | lever sideways, analog, dead zone 0.18 | right stick X | D19 |
| Z | Flight Assist on/off | FA | X | |
| B | boost | BOOST (cooldown ring) | A | `02` |
| T | next target | LOCK tap; or a short tap on a marker | D-pad up | `06` 9.1 |
| Y | clear target | LOCK held 0.5 s | D-pad up held 0.5 s | `06` 9.1 |
| N / M | system map / star map | MAP (opens the last used map; header switch SYSTEM \| STAR MAP; ✕ closes) | Select tap; LB/RB switch maps inside | `07` 8.2 |
| Enter | confirm the jump | HOLD TO JUMP button in the star map (0.7 s) | hold A on the jump button (or hold X anywhere in the map) | `07` 8.2 |
| Backspace | cancel a plotted jump | CANCEL JUMP (ALIGN, COUNTDOWN only) | D-pad left tap while a jump is plotted | `07` D8 |
| P | autopilot / auto-dock (one key in Roblox) | AUTO pill | D-pad right | `02`, `08` |
| L | land | ACTION "LAND" (starts the landing assist, D14) | D-pad down | `04` |
| K | request / cancel docking, launch | ACTION "DOCK", "CANCEL", "LAUNCH" | D-pad down | `08` 8.1 |
| V | cockpit ↔ external view | VIEW | Y tap | |
| G (hold) | survey | SCAN: a tap toggles holding | D-pad left held (not while a jump is plotted) | `08` 8.1 |
| H (hold) | mine | MINE (same button, on the ground): tap toggles | D-pad left held | `08` 8.1 |
| U | station panel | DATA (top bar) | Select held 0.5 s | `08` 8.1 |
| J | music on/off | MUSIC row in the pause menu (and the cockpit music button where ported) | pause menu | `09` 8.1 |
| 1 | leave the seat, walk aboard | WALK (top bar, walkable ships) | Y held 0.5 s | `09` 8.1 |
| E | interact; sit down at the helm | tap the prompt card or USE | X (on foot) | `09` 8.1 |
| Right mouse + mouse | look around | one-finger drag in the look strip; release returns the view (τ 0.12 s) | L3 held + right stick (the right stick stops yawing while L3 is held) | `02`, `06`; gain in 8.1.1 |
| Left click on a cockpit button | press it | not by default (caps are ~2 mm); optional console mode | — | `06` 9.1 |
| Wheel | zoom (maps; on foot) | pinch | maps: LT/RT; on foot: LB/RB | `07`, `09` |
| Right / left drag in maps | turn / pan | two-finger / one-finger drag | right / left stick | `07` |
| Left click in maps and panels | select, press | tap | A | |
| W/A/S/D, arrows, Space on foot | walk, jump | walk stick, JUMP | left stick, A | `09` |
| Menu keys (arrows/WASD, Enter, Backspace, PageUp/Down) | navigate | tap, Android Back, drag-scroll | D-pad, A, B, LB/RB | `07` |
| Esc (Roblox's own menu) | — | PAUSE, Android Back | Start | phone addition |

#### 8.1.1 Look-around gain on touch and pad

Roblox: 0.25° per mouse pixel × sensitivity. Touch: **0.5° per u** of finger travel × sensitivity (a 280 u drag = 140°, the full cockpit yaw range, a little more than half of the look strip), clamped to the same limits; on release the view returns with τ = 0.12 s exactly as Roblox. Pad: the right stick sets a look rate of up to 180°/s at full deflection while L3 is held; on L3 release the view returns. INVERT PITCH inverts the vertical look as in Roblox.

#### 8.1.2 Which layer owns the touches (the port of "mouse wanted")

Roblox frees the mouse whenever a map, the station services, a panel or the training's final card is open, or the pilot walks (C52, A13). The phone equivalent is the input owner:

| Owner | When | Effect |
|---|---|---|
| Flight controls | in flight, no full-screen overlay | lever, stick, buttons, look strip |
| Overlay | a map, services, construction panel, training card | flight controls hidden and `releaseAll()`; the lever keeps its position (it latches), the stick returns to centre — the equivalent of Roblox's `centerCursor()` on every ownership change |
| Walker | on a deck or aboard | walk stick, JUMP, USE, look drag (`09`) |
| None | LOADING curtain, crash cinematic, warp TUNNEL/ARRIVE (flight controls dimmed) | touches ignored, except PAUSE |

Looking around in the services (Roblox: RMB captures the mouse while held): a LOOK hold-button in the services footer (`08` 8.1).

### 8.2 Gamepad: one map for all areas

The sibling docs proposed overlapping pad bindings. This is the reconciled map (by button):

| Button | Flight: tap | Flight: hold | In a map | On foot | Menus |
|---|---|---|---|---|---|
| Left stick | aim (pitch, roll/yaw) | | pan | walk | focus |
| Right stick | X: yaw/roll axis; Y: vertical thrust | with L3 held: look | turn (star map) | look | scroll |
| L3 | | look modifier | | | |
| R3 | | full stop | | first person toggle (`09`) | |
| LT / RT | lever down / up | | zoom | | |
| LB / RB | strafe left / right | | switch map | zoom steps (`09`) | page |
| A | boost | | select; hold on the jump button | jump | confirm |
| B | Back (pause) | | close the map | close a panel | back |
| X | Flight Assist | | hold: jump | interact | |
| Y | view | leave the seat (0.5 s) | focus the selection | | |
| D-pad up | next target | clear target (0.5 s) | list up | | focus |
| D-pad down | ACTION (land, dock, cancel, launch) | | list down | | focus |
| D-pad left | cancel a plotted jump | survey / mine | | | focus |
| D-pad right | AUTO (autopilot or auto-dock) | | | | focus |
| Select | MAP (last used) | DATA panel (0.5 s) | close | | |
| Start | pause | | | pause | |

**Reconciliation** (what changes against the sibling docs; the lead confirms in `DECISIONS.md`, 10.2):

| Function | `02` 9.2 | `06` 9.1 | `07` 8.2 | `08` 8.1 | `09` 8.1 | Kotlin today | Here |
|---|---|---|---|---|---|---|---|
| Next / clear target | D-pad up / D-pad down | D-pad up tap / hold | | D-pad up | | D-pad up toggles the lock | `06` |
| Autopilot (P) | D-pad left | | | D-pad right (docking computer) | | — | D-pad right for both (one key in Roblox) |
| Land / dock (L, K) | D-pad right | | | D-pad down | | D-pad down (action) | D-pad down (`04`, `08`, Kotlin) |
| Maps (N, M) | Select tap / hold = system / star | | Select, LB/RB inside | Select hold = U | | Select = map | Select tap, LB/RB inside (`07`) |
| Station panel (U) | | | | Select hold | | — | `08` |
| Cancel jump | | | D-pad left | | | — | `07` |
| Survey / mine hold | | | | D-pad left held | | — | `08`, except while a jump is plotted |
| Leave seat | | | | | Y held | — | `09` |
| Look | L3 + right stick | L3 + right stick | | R3 held in the services | | — | L3 everywhere (R3 is full stop) |

### 8.3 Optional keyboard and mouse (DeX, ChromeOS, Bluetooth keyboards)

When Android reports a hardware keyboard, map `ShipConfig.Keys` 1:1 (3.1) with key names from `KeyEvent.getDisplayLabel` (the layout-aware equivalent of `ShipKeys.name`); with `View.requestPointerCapture` the mouse deltas feed the Roblox cursor logic (radius `0.20 × short side` clamped 130–300 px, `Cursor.Sensitivity × MouseSensitivity`), RMB looks around, LMB presses cockpit buttons in pointer mode, Esc pauses. This reproduces the original exactly. Low priority.

### 8.4 Performance

- **Frame budget.** All game logic is dt-based, so any refresh rate works. Target: the session's CPU part ≤ 4 ms per frame on the Xiaomi 15 and ≤ 8 ms on a mid-range phone; the GPU budget is the renderers' (`03`, `04`, `06`, `08`). The full port at 120 Hz doubles the per-second CPU cost against Roblox's 60; proposal: **default frame-rate setting 60 for the full game** (MAX selectable), decided in 10.2.
- **Zero allocation per frame.** Roblox builds three tables per frame (gravity state, audio state, HUD state); the port reuses one record each, like all the others (`interiorState`, callout frame, traffic, survey, mining, attitude frame, `InputFrame`). Text goes through `TextBuf` and `Units`. Allocation is allowed only on events (toasts, saves, a jump) and is verified by the desktop `perf` scenario.
- **Rates.** Keep the Roblox throttles: markers, cockpit screens and HUD text at 10 Hz (stagger them so they do not land in the same frame), galaxy refresh every 2 s, autosave every 10 s.
- **Threads.** The GL thread owns the game state. Off-thread: the system build after a jump (the tunnel waits up to `Warp.MaxTunnelSeconds` 20 s for it, `07`), terrain tiles (`04`), texture bakes (`03`), save writes (`08`). Results come back through queues drained at the start of a frame.
- **Quality detection** (port of 2.6):
  1. First run: the GPU heuristic `Game.defaultQuality` (exists) picks LOW, MEDIUM or HIGH.
  2. Roblox's measurement: after `DetectDelay` 2.5 s collect `DetectTime` 3 s of frame times (at least 12); median frame rate < `MinFps` 38 → one quality step down. The Kotlin menu is much lighter than a flight, so measure **after the reveal in the first flight** as well (the real load), once per session.
  3. Only while the player has not chosen a quality (`qualityTouched`, persisted); silent like Roblox; the step is persisted.
  4. Optional: Android thermal status (`PowerManager.getThermalHeadroom`) → a temporary step down for the session when severe (a phone has no "fixed graphics level" but heats up).
- **LOW quality = performance mode**: the Low table (3.5) lowers particle and beam counts to about half and the per-frame build budgets to 3 ms; render scale 0.7; ship detail parts flagged at import are skipped; no shadows.

### 8.5 Roblox-only workarounds that go away

| Roblox | Why it existed | Port |
|---|---|---|
| `ShipOrigin` (shift the world every frame, `BulkMoveTo` of 270 parts, universe/world conversions, `onShift` listeners) | 32-bit `Vector3` positions jitter far from zero; late lighting of moved anchored parts | doubles + camera-relative rendering + depth slices (exist) |
| `ShipCamera.disableDefault` and the per-frame `CameraType` check | Roblox's PlayerModule fights a scripted camera | own camera |
| `ShipRig` (anchored invisible character, hide-locally every frame, re-attach watchdog, controls off) | every Roblox player has a physics character | none in flight; the walker of `09` on foot |
| Spawn lock (`CharacterAutoLoads = false`, `ShipReady`, respawn 3 s after a death) | the character spawned before the client was ready | none |
| `ShipNet` remotes, guards, rate limits, waits; `profile:call` yields, `pending` flags, `galaxy:fetch` timeouts | client/server split, untrusted clients | synchronous local services; configured delays stay timers (2.12) |
| Teleports (TRAINING and SOLO private servers, OPEN joins) and arrival covers | Roblox servers | local modes; OPEN dropped |
| Landing scene (`surface:step` re-anchor, gravity override, scene/real conversions, `sweep`, `afterCamera`, scene markers off) and the atmosphere switch callbacks | no real-size planet one can stand on | one seamless world (`04`, D14) |
| `planetTerrain` voxels, `impostors` and the sight probe, `starLight`, `speedLines`, SpawnLocation hiding, terrain safety net, smooth-sphere probe | Roblox terrain, view distance, global lighting and mesh loading | own renderer |
| `ShipOverlay` render step at `Camera + 10` | BillboardGui anchors must follow the camera | screen-space markers after the camera |
| Mouse capture (`LockCenter`), Left Alt release, window-focus handling, `processed` and text-box gating | desktop mouse in a Roblox window | input owner (8.1.2); IME focus for the star map search (`07`) |
| `GetStringForKeyCode` key naming | QWERTY key codes on other layouts | `InputLabels` per device |
| `Lighting.GlobalShadows`, `SavedQualityLevel` | Roblox engine settings | own quality setting |
| Luau's 200-locals limit (`hud.attitude`, `do` blocks) | language limit | plain classes |
| `_G.Ship`, `ShipDebug` BindableFunction, `ShipServerDebug` | Studio test access | `Game.debugCommand` + desktop harness (9.6) |
| `MINIMUM ALTITUDE` barrier | planets are images over a sphere in space | real ground contact |
| `os.clock` timers (edge and barrier toasts), `task.delay` | Roblox scheduling | game-time timers on the GL thread (deterministic in the harness) |

### 8.6 Pause, Back and the app lifecycle

Roblox has no pause: it is an online game, and Esc opens Roblox's own menu while the world runs. The phone port is singleplayer and pauses:

| Event | Effect |
|---|---|
| PAUSE button, Start, Back in free flight | PAUSED: simulation frozen (no dt), audio paused, flight controls released |
| Back with a map, panel or card open | closes it (the flight never paused) |
| Back while docked with the services open | PAUSED (`08`) |
| Back during a plotted jump | PAUSED; the jump is **not** cancelled (`07` D8); the countdown is frozen with the pause |
| App to background (`Game.onPause`) | PAUSED + immediate save (D23) |
| App resumed | stays PAUSED; RESUME continues |
| App killed | next start restores the last save (4.6) |

### 8.7 The scale S = 3000 in this area

| Quantity | Treatment |
|---|---|
| `Ship.StartPosition`, `Ship.FieldRadius` | ×S (2.7 Gm; 27 Gm) |
| `arrivalPoint` | scales through the star's radii (Sol: 318 Mm from the star) |
| `clearOfBodies` | ratios of the body radius (1.5 r, 3 r): unchanged |
| `stationSpawn`, recovery | station geometry is absolute (D9) |
| camera, look, seat, external offsets, ground clearance, crash speeds, `EdgeSpeed`, `EdgeMargin`, every timer | absolute |
| precision | doubles resolve ≈ 4 µm at 27 Gm; no origin shifting needed |
| units | the largest numbers are "27,000 Mm" and "150.0 Mm/s" (D2, D4) |
| saves | store the pose relative to the dominant frame (body or station) plus the save clock (D6, D8) so a restore lands at the same spot even if orbits move |

Nothing in this area argues against S = 3000.

---

## 9. Kotlin port plan

### 9.1 Files and classes (`core/src/main/kotlin/com/starwake/core/`)

| File | Content | Ports |
|---|---|---|
| `Game.kt` (change) | stays the platform-facing shell: GPU lifecycle, screens and overlays, pointer routing, the curtain and fade timeline, Back and pause; owns one `Session` per flight; the flight code moves out | ShipClient boot and reveal (2.1.1, 2.1.15) |
| `game/Session.kt` (new) | every world module of the flight; `frame(dt, input: InputFrame)` with the three branches and the order of 9.2; the event handlers `toggleExternalView`, `toggleAutopilot`, `autopilotButton`, `jumpButton`, `dockButton`, `landAction`, `onCockpitAction`, `switchShip`, `standUp`/`canStand`, `arriveInSystem`, `buildSystem`, `afterReveal`; the frame-local state of 2.1.9 | ShipClient (2.1.3-2.1.14, 2.2) |
| `game/PlayerMode.kt` (new) | `class PlayerMode` (branch, onFoot, travelling, controlled, docked, crashing, landed, overlay, view, looking), derived once per frame; `UiVisibility` (5.3) as pure rules | `Runtime` flags, `hideSpace`, `hideFlightUi`, `setFlightUiHidden` |
| `game/SpawnRules.kt` (new) | `arrivalPoint(star, out)`, `clearOfBodies(p)`, `stationSpawn(station, out pose)`, `startPose(save)` (D13, D23), `recoveryPose(crashPos)` | `:596-640`, `:772-800`, `:1419-1467` |
| `game/CrashFlow.kt` (new) | `start(cause, gs, hit)`, the timeline of 4.7 on game time, `respawn()`, the loss lines, the input block until the fade-in | `:1302-1666` |
| `game/ShipCamera.kt` (new) | cockpit and external composition (2.3), per-ship external offsets from the ship profiles (`02` `ShipProfiles`), look state with limits and return, FOV from settings, head offset and shake inputs; writes `Camera.pos` (double) and `Camera.rot` | ShipCamera, the look part of ShipInput; replaces `ChaseCamera` (`game/World.kt:157`) in flight |
| `input/Action.kt` (new) | `enum class Action` (the 28 keys + `INTERACT`, `LOOK`, `PAUSE`, `BACK`) | `ShipConfig.Keys` |
| `input/InputFrame.kt` (new) | per-frame record: aim X/Y, key axis, lever, lateral X/Y, full stop, look delta, look held, `held` bits and `pressed` counters per `Action`, device; built in one place from touch, pad and keyboard; no allocation | ShipInput output, `ShipKeys.isDown`, every module's `InputBegan` |
| `input/InputGate.kt` (new) | the matrix of 4.5 (port version: the input owner of 8.1.2 replaces mouse capture) as one function of `PlayerMode` and overlays | `Runtime` checks, `processed`, text-box checks |
| `input/GamepadBindings.kt` (new) | the 8.2 map with tap/hold detection (hold = 0.5 s) and the L3 look modifier | today's pad code in `Game.readControls` |
| `input/KeyboardBindings.kt` (optional) | 8.3 | `ShipConfig.Keys` |
| `input/TouchFlightControls.kt` (change) | the 5.6 layout (strafe cross, AUTO, SCAN/MINE, DATA, WALK, CANCEL JUMP, look strip, LOCK hold, lever hold for full stop); writes `InputFrame` | |
| `Settings.kt` (change) | fields of 3.10; `qualityTouched`; serialisation keys `fov`, `shake`, `mus`, `fx`, `music`, `call`, `flat`, `arrow`, `qt`; a `SettingDef` table (key, label, kind, min, max, step, default, hint, format) with `set()` = clamp + snap (2.9) and change listeners that fire only on a real change | ShipSettings, `ShipMenuData.SettingInfo` |
| `game/Quality.kt` (new) | `QualityProfile` per level (the Low table of 3.5 for LOW), detection of 8.4, `current`, listeners for modules that can follow live | ShipPerformance |
| `ui/UiScale.kt` (new) | 5.7 rules (`size(px)`, `text(px)`, `touch(px)`), `roundPx`, `openTransition` state (5.2) | ShipUi |
| `hud/Units.kt` (new; file named in `06` 10) | 2.11 into `TextBuf` | ShipUnits; replaces `Game.formatSpeed/formatDistance`, `MapScreen.distanceText` |
| `math/MathX.kt` (change) | `smooth(c, t, tau, dt)`, `applyDeadzone(x, y, dz, curve, out)`, `roundHalfAway(x)` | ShipMath |
| `services/LocalServices.kt` (new) | holds the local rule engines in the server's order (2.13) with synchronous calls; the clock source (D8, D24); `start(save)` at app start | ShipServer, ShipNet |
| `Platform.kt` (change, optional) | `thermalHeadroom(): Float` | — |
| `desktop/.../Scenarios.kt`, `GameDriver` (change) | the scenarios of 9.5; `cmd` with arguments | — |

### 9.2 Hooking into `Game.kt`: the Kotlin frame

```text
Game.drawFrame(t)                                            (exists, Game.kt:297)
  dt = vsync delta, ≤ 0.1
  handleSystemEvents()       debug commands (now with arguments), Back, pause request, pad navigation
  input.drain(this)          pointer events → overlays or TouchFlightControls
  inputFrame.build(dt)       NEW: touch + gamepad (+ keyboard) → axes, look, action edges; owner = PlayerMode/overlay
  update(dt):
     curtain timeline (FADE OUT → LOADING → REVEAL 0.6 s sine-out; 12 s safety)
     MENU      → menu.update(dt)
     IN WORLD  → if no pausing overlay (PAUSE, SETTINGS): session.frame(dt, inputFrame)
                 audio.updateMusic(dt) (every branch, as Roblox's Heartbeat); training?.update(dt)
                 non-pausing overlays update (maps, services, panels, training card)
  render(gl, dt)             scene from the session's camera; HUD; overlays; curtain on top
```

`Session.frame(dt, in)`, in Roblox order (step ids from 2.2; *dropped* steps omitted):

1. **Branch:** deck active → deck frame (A3-A14, `09`); destruction active → destruction frame (B1-B10); else the flight frame:
2. C2-C7 flags and gated pilot input (`InputGate`); C6 recentring applies to keyboard/mouse aim only (the touch stick and the pad stick spring back by themselves).
3. C8 handling · C9-C11 autopilot · C12 nobody at the helm · C13 rotation.
4. C14 orbits · C15 **frame carry** (station frame first, else the dominant body's motion; D6).
5. C16 gravity state (reused record) with the near-ground band (`02` 9.5) · C19 station, approach and edge limits (impact warnings stay on, D16) · C20-C22 zone label, limit, gravity.
6. C23-C25 thrust and move (sub-steps for the move and the collisions, `02`) · boost heat · field-edge clamp.
7. C26 **landing assist** in place of the guidance (D14) · C27 systems.
8. C28 heat death; ground contact against the terrain (`04` 9.10) with the touchdown/crash thresholds of `02`; `shipLanded`.
9. C30 stations: place, docking, hull collisions · C31 traffic.
10. C34-C40 pitch angle and local up, cursor visibility, cockpit state and update, hull and gear, cockpit motion, **camera** (`ShipCamera`) or the aboard camera.
11. C41 entry effects · C43-C44 audio state, FA voice · C45 warnings · C46 callouts · C47 markers · C48 dust · C52-C53 input owner, services looking · C54 system map · C56 texture streaming · C58-C60 star map, warp guard, warp · C62-C63 atmosphere, arrival card · C64 voice · C65 ring debris · C66 dust mood · C67 HUD state · C68 flight arrow · C69 attitude.
12. C70-C75 survey, mining, construction, galaxy refresh (2 s), notices, services · C76 **autosave** (10 s in free flight; also on pause, background, dock, undock, jump arrival, touchdown and lift-off).

### 9.3 Order of work

1. **Pure helpers with tests:** `Units`, `MathX` additions, `UiScale`, the `Settings` fields and `SettingDef` table. No GL, no behaviour change.
2. **Input layer:** `Action`, `InputFrame`, `InputGate`, `GamepadBindings`; `TouchFlightControls` writes `InputFrame` with today's behaviour first. Replace `Game.readControls`.
3. **Session skeleton:** move today's flight code from `Game.updateFlight` into `Session.frame` in the Roblox order with stubs where modules are missing; `PlayerMode`; the map and the docked screen stop pausing (D21); the `trace` debug command.
4. **Camera:** `ShipCamera` (cockpit default, rigid external per ship, look strip and pad look, FOV setting); `ChaseCamera` only behind an optional third view.
5. **Start and recovery:** LOADING and REVEAL timeline, `afterReveal`, the welcome hook, `SpawnRules` (D13 start docked at MERIDIAN, D23 restore, warp arrival, recovery), `CrashFlow` with the warnings timings and a stub cinematic.
6. **Quality:** detection and the per-module count registry (modules read `Quality.current` at build and follow live where cheap).
7. **Services and saves:** `LocalServices` with `08`'s profile and save manager; autosave triggers; Back/background saves.
8. **Debug and harness:** the 9.6 commands and the 9.5 scenarios.
9. The area ports plug into `Session` in their own order (`02` flight first, then `03` world at S, `06` cockpit/HUD, `08` stations, `07` maps/warp, `04` terrain, `09` life/audio).

### 9.4 Unit tests (`core/src/test/kotlin/com/starwake/core/…`)

| Test | Checks |
|---|---|
| `hud/UnitsTest` | every example of 2.11; the boundaries: 999.4 → "999 m", 999.5 → "1.00 km", 9,994.9 → "9.99 km", 9,995 → "10.0 km", 99,949 → "99.9 km", 99,950 → "100 km", 999,499 → "999 km", 999,500 → "1.00 Mm", 9,994,999 → "9.99 Mm", 9,995,000 → "10.0 Mm", 99,949,999 → "99.9 Mm", 99,950,000 → "100 Mm", 1.24e9 → "1,240 Mm", 2.7e10 → "27,000 Mm"; speeds 260 → ("260", "M/S"), 999.5 → ("1.00", "KM/S"), 99,950 → ("100", "KM/S"), 999,500 → ("1.00", "Mm/s"), 1.5e8 → ("150.0", "Mm/s"); NaN and +∞ → "--"; negatives → 0; `upper` keeps "Mm"; "35.0 m/s²"; no allocation over 10⁴ calls |
| `math/MathXTest` (extend) | `smooth`: τ ≤ 0 → target; two half steps equal one full step (dt invariance, 1e-6); `applyDeadzone`: inside → 0; (0.6, 0) with 0.20 and 1.7 → (0.5^1.7 = 0.3078, 0); radial symmetry on the diagonal |
| `ui/UiScaleTest` | `roundHalfAway(2.5) = 3`, `(−2.5) = −3`; text floor 9 u; Roblox scale clamps (600 px → 0.75, 2400 px → 2.2); 14 design px → 8.4 u |
| `SettingsTest` | defaults equal Roblox (sensitivity 1, invert off, ROLL, FOV 80, shake 1, music 1, effects 1); clamp and snap (FOV 59.6 → 60, 80.4 → 80, shake 0.33 → 0.35, 0.30000000000000004 → 0.3); toggles; round trip with the new keys; unknown keys ignored; corrupt values keep defaults; listeners fire only on change; reset per panel |
| `game/ShipCameraTest` | cockpit look (0, 0) → eye = ship·(0, 0.5, 0), forward −Z; look (90, 0) → eye (0.3, 0.5, 0.3), forward +X; look (0, 80) → eye (0, 0.506, 0.593); VARROW external → camera at (0, 16, 95) pitched −8°; external look (180, 0) → camera at (0, 16, −95) facing back at the ship; switching to the cockpit clamps a 170° look to 140°; after 0.12 s without input the look is × e⁻¹; below 0.05° it snaps to 0; FOV follows the setting |
| `game/SpawnRulesTest` | Sol arrival at S = 3000 = (0, 19.044e6, −317.4e6) m, facing the star; `clearOfBodies` moves a point at 1.4 r to 3 r along the same ray and leaves 1.6 r alone; `stationSpawn` faces the station root; start rules: new pilot → docked at MERIDIAN; docked save → docked; flight save → same pose at rest; unusable save → in front of the last station |
| `game/SessionOrderTest` | recording stubs: the flight frame calls the modules in the 9.2 order; the deck branch skips the flight; the destruction branch; the frame a deck session ends returns early |
| `input/InputGateTest` | flight actions ignored on a deck and aboard; LEAVE_SEAT and TOGGLE_MUSIC always pass; STATION_PANEL not aboard; CANCEL_JUMP only in ALIGN/COUNTDOWN; travelling and controlled zero the axes; maps do not pause, PAUSE does |
| `game/CrashFlowTest` | cinematic: respawn at 12.2 s, warnings idle at 14.8 s, "All systems online." at 14.8 s; plain: respawn 0.30 s, idle 2.8 s; voice order HullFailure → ShipDestroyed → losses → ReplacementReady → Rebooting → SystemsOnline; recovery at the nearest station; a second crash while busy is ignored; flight input blocked until the fade-in starts |
| `game/QualityTest` | fewer than 12 samples → no verdict; median 37 fps → one step down; 39 → none; touched → none; LOW profile values = the Low table |
| `services/LocalServicesTest` | init order; a docked start reserves the pad; results are synchronous |

### 9.5 Desktop harness scenarios (`desktop/.../Scenarios.kt`)

| Scenario | Script | Proves |
|---|---|---|
| `frame-order` | launch; `cmd("trace")` for one frame | the logged call order equals the documented order |
| `boot-reveal` | launch; shots at the build frame, +1 and +2 frames, +0.3 s, +0.6 s, +2.6 s | black until two frames after the build; the sine-out reveal; the cockpit boot; never an empty-space frame |
| `start-docked` | fresh save, launch | D13: docked at MERIDIAN, services after the reveal |
| `restore-flight` | save in flight at a known pose, new `Game`, launch | D23: pose within 1 m, look within 0.1°, at rest |
| `camera-views` | cockpit; look ±140° / +80° / −70°; external for every imported ship; external look 180° | camera composition and limits |
| `map-keeps-flying` | lever 50 %, open the map for 3 s, close | position advanced by ≈ v·3 s; stick recentred; no pause |
| `crash-respawn` | `cmd("crash heat")`; shots at 1 s, 11.5 s, 12.5 s, 15 s | cinematic, fade, black, respawn in front of the nearest station |
| `ui-scale` | flight HUD and settings at 2670×1200 with densities 2.75 / 2.875 / 3.0, 2400×1080 at 2.625, 1600×720 at 2.0, with a cutout | the 5.7 floors; the 5.6 controls fit without overlap (rect check written to the report) |
| `quality-auto` | fresh settings; inject 30 ms frames for 6 s after the reveal | one step down, persisted; none when touched |
| `units-panel` | a debug overlay prints values through `Units` | the glyphs and formats ("Mm", "²", "·") |
| `system-edge` | teleport near the scaled field edge flying outward | the SYSTEM EDGE toast once; the edge limit |
| `perf` (exists) | the new session | zero allocation per frame |

### 9.6 Debug commands (`Game.debugCommand`)

Today's commands (`Game.kt:336-368`) stay. New ones take arguments ("name a b c", parsed off the frame path). This area adds: `state` (the 2.1.16 field list into `debugInfo`), `trace` (one frame's call order), `teleport x y z [lx ly lz]` (universe metres), `near <body> [alt]`, `stick x y` and `look x y` (held), `throttle v`, `fa on|off`, `boost`, `key <Action>` (simulate an action edge), `view cockpit|external`, `crash heat|ground|station [slow]`, `ship <id>`, `quality 0|1|2`, `reveal` (force), `save` / `load`. Area commands (owners): `autopilot`, `heat`, `gear` (`02`); `warp`, `warpBroken`, `align` (`07`); `docking`, `request`, `computer`, `launch`, `station`, `stationView`, `survey`, `call`, `profile`, `page`, `galaxy`, `mining`, `site`, `panel`, `credits` (`08`); `aboard`, `decks`, `arrive`, `disembark`, `travel`, `board`, `interact`, `traffic`, `training*`, `musicNext` (`09`); `interior`, `press`, `reboot`, `lock`, `attitude` (`06`); `land`, `ascend`, `landing`, `destruction` (`05`); `terrain` (`04`).

---

## 10. Open questions and risks

### 10.1 Decisions taken in this file (defaults; `DECISIONS.md` wins)

From `DECISIONS.md`: D13 (start docked at MERIDIAN), D14 (no landing scene; landing assist on ACTION), D16 (impact warnings on), D19 (lever and stick behaviour), D21 (maps keep the flight running), D23 (local saves, restore the exact state), D28 (training optional), D29/D30 (PILOT, MASS SHADOW, AUTO-DOCK), D1-D4 (S, speed law, units). Taken here:

1. **Pausing**: only PAUSE and SETTINGS freeze the simulation; maps, station services, panels and cards never pause (Roblox; D21). Back and app background pause (phone addition, 8.6).
2. **Camera**: default cockpit; VIEW toggles cockpit ↔ external, rigid and per ship (Roblox); FOV 60–100 (default 80, vertical).
3. **Not ported**: `ShipOrigin`, `ShipRig`, the spawn lock, `ShipNet`, the server script as such (replaced by `LocalServices`).
4. **Settings persist** (Roblox keeps them for the session only).
5. **Performance mode = quality LOW**; automatic, silent step-down after a slow median frame rate in the menu or the first flight, only while the player has not chosen a quality.
6. **Restored flight pose is at rest** (velocity 0, lever 0): an app kill must not resume a dive.
7. **Gamepad map** of 8.2 (reconciled); **touch layout** of 5.6.
8. **UI scale**: 1 design px = 0.6 u with a 9 u text floor (`07` D3) as the default for every flat UI.
9. **Welcome lines** once per app session, 5 s after the reveal.
10. **Crash fade-in**: flight input is blocked until the fade-in starts (Roblox lets the pilot fly blind).
11. **LOADING caption** "PREPARING YOUR SHIP ..." after 0.8 s of black.
12. **A failed jump build** falls back to Sol in front of MERIDIAN, not at the scaled `StartPosition`.

### 10.2 Questions for the user (each with the default this file assumes)

1. **Third camera.** Today's build has a lagging chase camera that Roblox does not have. Keep it as an optional third VIEW step, or strictly cockpit and external? *Default: strict two views; chase behind a setting.*
2. **Frame rate.** Default 60 fps for the full game (battery, heat; Roblox's own rate) with MAX (120 Hz) selectable, or keep MAX as the default? *Default: 60.*
3. **Look-around on touch.** One-finger drag in a top strip (default), a two-finger drag on the right half, or press-and-drag on VIEW? *Default: top strip.*
4. **Gamepad.** Confirm the reconciled map of 8.2 (D-pad right = autopilot/auto-dock, D-pad down = land/dock, L3 + right stick = look, Select hold = station panel). *Default: as 8.2.*
5. **UI scale across docs.** `08` and `09` size panels at 0.386 u per design px with minimum sizes; `07` uses 0.6 u. Unify on 0.6? *Default: yes.*
6. **Restore after an app kill**: at rest (default) or with the saved velocity and lever?
7. **Hardware keyboard and mouse** (DeX, ChromeOS): implement the Roblox keys 1:1 now, later, or never? *Default: later.*
8. **Automatic quality step-down**: silent like Roblox (default) or with a one-time toast pointing to SETTINGS?

### 10.3 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The frame order drifts as nine areas plug into `Session` | `SessionOrderTest` and the `frame-order` scenario fail on any reorder |
| R2 | Per-frame allocation creeps in through many modules | reused records everywhere; the `perf` scenario in CI |
| R3 | Touch crowding: 18 controls on one screen, mis-taps while flying | visibility rules (5.6), ≥ 38 u targets, the `ui-scale` overlap check; playtest on the device |
| R4 | Cross-doc input conflicts until the lead confirms 8.2 | one `GamepadBindings` and one `TouchFlightControls` owner |
| R5 | The reveal hangs if a system build throws | the 12 s safety reveal; the Sol fallback |
| R6 | Data races with the off-thread system build during a jump | build into a fresh system object; swap on the GL thread at `markBuildDone` |
| R7 | A restored save lands inside geometry or in a moved frame | frame-relative pose + save clock, `clearOfBodies`, `positionFrame`, backup copy (D23) |
| R8 | 120 Hz doubles CPU work and heat | default 60 (10.2-2); quality step-down; thermal hook |
| R9 | Harness non-determinism from wall-clock timers | all timers on game time; the harness drives a fixed 60 Hz |
| R10 | Players expect the map to pause (today's build does) | D21 accepted; the PAUSE button stays one tap away |
