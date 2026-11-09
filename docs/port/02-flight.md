# 02 · Flight model and flight instruments

Port reference for **Untitled Space Exploration** (Roblox game by AFloidDEV) → Android/Kotlin (`:core`). This file covers how the ship flies and the instruments that show it:

| Topic | Roblox source (paths relative to `roblox/lua/`) |
|---|---|
| Input: virtual cursor, key axis, look-around | `ShipClient/ShipInput.lua` |
| Handling from mass and speed | `ShipClient/ShipHandling.lua` |
| Rotation and orientation | `ShipClient/ShipRotation.lua` |
| Throttle, Flight Assist, boost, cruise | `ShipClient/ShipThrust.lua` |
| Gravity, dynamic speed limit, zones, star heat | `ShipClient/ShipGravity.lua` (+ station and edge composition in `ShipClient.lua:2484-2538`, `ShipClient/ShipStation.lua:1036-1071`) |
| Fuel, heat, G-load | `ShipClient/ShipSystems.lua` (+ blackout overlay in `ShipClient/ShipHud.lua:355-370`) |
| Autopilot | `ShipClient/ShipAutopilot.lua` (+ glue in `ShipClient.lua:964-981, 2389-2423`) |
| Landing-gear animation | `ShipClient/ShipGear.lua` (+ stepping in `ShipClient/ShipHull.lua:504-614`) |
| Attitude indicator | `ShipClient/ShipAttitude.lua` |
| Flight cursor and flight arrow | `ShipClient/ShipCursor.lua`, `ShipCore/ShipFlightArrow.lua` (+ cockpit projection `ShipClient/ShipInterior.lua:483-494, 1275-1300`) |

All of these files were read completely. The `ShipConfig` sections Rotation (`ShipCore/ShipConfig.lua:15`), Thrust (`:27`), Ships (`:87`), Handling (`:388`), Gravity (`:448`), Physics (`:2433`), Autopilot (`:2374`), Systems (`:2399`), Cursor (`:1605`) and Attitude (`:1688`) were read in full, plus the parts of Keys, AltKeys, Hull, Camera, Ship, Interior, Surface, Stations, Warnings and Ui that these modules use.

Sibling port docs: `03-world.md` (Sol data, the scale factor S, orbits), `07-menu-maps-warp.md`, `08-stations-economy.md` (stations, docking, economy). Cockpit/HUD, atmosphere entry, ships import, audio and voice have their own docs; this file names the data it hands to them.

The player is always **PILOT**. Several Roblox comments say the flight model was tuned against a commercial space sim ("Mausflug wie in ...", mass formula, "blue zone"). The port keeps the numbers and behaviour, which are AFloidDEV's own, and never uses that game's names, terms or UI.

---

## 0. Conventions

- **Units.** 1 stud = 1 m (`ShipConfig.Scale.MetresPerStud = 1`, `ShipConfig.lua:2754`). Speeds in m/s, accelerations in m/s², config angles in degrees, code angles in radians.
- **Body axes.** Roblox `CFrame` and the Kotlin `Quat`/`Vec3` use the same right-handed axes: +X right, +Y up, **−Z forward** (`LookVector`). A positive rotation about +X raises the nose, about +Y turns the nose **left**, about +Z rolls **left** (the up vector tips left). `Quat.integrateLocal(wx, wy, wz, dt)` (`core/.../math/Quat.kt:87`) applies body-frame rates with exactly these signs.
- **Screen vectors** (cursor, aim): X right, **Y down**.
- **S** is the world scale factor proposed for the port (a Roblox length of x studs becomes x·S metres, see `03-world.md`). Every value marked **×S** in this file changes with it; section 9.4 explains which ones and why.
- **Helpers** used by several modules:

```text
approach(c, t, maxStep) = c < t ? min(c + maxStep, t) : max(c - maxStep, t)        ShipRotation.lua:42, ShipThrust.lua:49
smooth(c, t, tau, dt)   = tau <= 0 ? t : c + (t - c) * (1 - exp(-dt / tau))         ShipMath.lua:19 (frame-rate independent)
applyDeadzone(v, dz, k) = (|v| <= dz or |v| < 1e-6) ? 0                              ShipMath.lua:52 (RADIAL, not per axis)
                          : unit(v) * clamp((|v| - dz) / max(1 - dz, 1e-6), 0, 1) ^ k
smoothstep(x)           = x*x*(3 - 2x), x clamped to 0..1                             ShipGear.lua:43, ShipAttitude.lua:421
orthonormalize(cf)      = rebuild from LookVector and UpVector:                       ShipMath.lua:78
                          look = unit(look); right = unit(look x up) (fallback axis Y, or X if |look.y| >= 0.9);
                          up = unit(right x look)
```

- **Roblox rounding.** Luau `math.round` rounds half away from zero; Kotlin `round()` rounds half to even. Where the Lua rounds pixels or degrees, port it as `sign(x) * floor(abs(x) + 0.5)`. Luau `%` is a floored modulo (`-1 % 8 == 7`); use `Math.floorMod`.
- **Line references** are `File.lua:first-last`.

---

## 1. Overview: what the player experiences

Default Roblox view is the 3D cockpit; V switches to an external camera.

- **Steering is a virtual stick on the mouse.** The mouse is locked to the screen centre and its motion moves a hidden cursor inside a circle (radius 20 % of the short screen side, 130–300 px). The deflection **stays where you leave it** (no spring-back); C recentres it. A small two-tone triangle near the screen centre shows the deflection, a small orange dot marks the centre. Inside the dead zone (20 % of the radius) the triangle is a dim dark red and the ship does not turn.
- **Axes** (setting MOUSE X AXIS, default ROLL): mouse Y pitches, mouse X rolls, Q/E yaw. With YAW: mouse X yaws, Q/E roll.
- **Rotation has inertia.** Turn rates ramp up at the ship's angular acceleration. With Flight Assist (FA) the flight computer brakes the rotation when the stick is centred; a heavy ship visibly overshoots. Without FA, stick input is torque: a spin continues until countered.
- **Throttle is a lever.** W/S move it slowly (0 → 100 % in 4 s), −25 % is full reverse. Holding X is a full stop: the lever runs to 0 in 1.2 s and all drift is killed. A/D strafe sideways, R/F (or Space/Left Ctrl) up and down.
- **Two speed bands.**
  - *Combat band*, up to the ship's TopSpeed (VARROW 260 m/s): real thrust per axis. With FA the computer holds the commanded velocity in ship axes (forward from the lever, sideways from the strafe keys, otherwise zero), each axis only with its own thrusters, drift killed with doubled side thrust; in hard turns a heavy ship still slides outward. Without FA: pure Newton.
  - *Boost* (B): 2 s of 2.2× forward thrust up to BoostSpeed (VARROW 340 m/s), 4 s cooldown, adds heat. Not in cruise and not when overloaded.
  - *Cruise* (FA only): above TopSpeed the speed approaches the target exponentially, at most doubling every 0.8 s; the flight direction follows the nose, side thrusters are off. Above TopSpeed the lever maps exponentially, so the whole combat band stays in the bottom quarter of the lever.
- **The allowed speed is dynamic** and is always explained by a zone name:
  - far from everything it grows with the distance to the next body or station (the gap shrinks by e every 3.5 s when flying towards it, 1.5 s away from it), capped at 3 Mm/s: OPEN SPACE / APPROACH / DEPARTURE;
  - inside a body's mass shadow (5 barrier radii) at most 1 km/s: GRAVITY WELL; inside a star's heat zone at least 1 km/s: HEAT ZONE;
  - close to the surface it falls to landing speed: LOW ALTITUDE;
  - within 6 km of a station 260 m/s, 140 m/s within 2.2 km, 60 m/s in the hangar: STATION ZONE;
  - at the system edge it falls to 150 m/s: SYSTEM EDGE.
- **Mass matters.** Every number of a ship holds at its design mass; fuel and cargo make it heavier, so it accelerates and turns more slowly. Agility is best at half TopSpeed, lower at a standstill, at full speed and (much lower) in cruise.
- **Gravity pulls** near bodies; FA cancels 78 % of it.
- **Fuel** burns with throttle and refills by "scooping" close to a star; it never blocks anything, but its mass changes the handling. **Heat** comes from the star, from thrust, from scooping and from each boost; 100 % warns, 200 % destroys the ship. **G-load** comes from the felt acceleration with direction-dependent tolerance; pulling up greys the screen out, pushing down tints it red.
- **Autopilot** (P) flies to the locked target by producing the same inputs a pilot would; any real stick or yaw input takes over.
- **Landing gear** folds legs, then closes doors (1.6 s + 0.6 s); it is down below 60 m over ground and when the docking logic asks for it.
- **Attitude indicator**: pitch ladder, horizon, flight-path marker, PITCH readout, climb-rate scale and ALT readout, drawn as if on the cockpit's projection glass. Shown only during planet entry, on the surface and during ascent (since 2026-11-07).

---

## 2. Per-frame pipeline (`ShipClient.lua:2321-3196`)

Bound with `RunService:BindToRenderStep("ShipUpdate", Enum.RenderPriority.Camera.Value, ...)`, i.e. once per rendered frame. The order matters; port it as is.

| # | Step | Lines | Reads | Writes |
|---|---|---|---|---|
| 0 | `dt = min(dt, 0.1)` (tab-out / load pauses) | 2324 | | |
| 0b | Station decks or ship-loss cinematic active: hide attitude (`hideNow`), run their own frame, return | 2327-2342 | | |
| 1 | `input:update(dt)` unless walking aboard | 2346-2349 | mouse, keys | cursor, keyAxis, look |
| 2 | `thrust:setInputEnabled(not travelling and not controlled and not onFoot)`; `controlled = docking:isControlling() or atmosphere:isGuiding()`; clear `boostRequested` when controlled/on foot; `centerCursor()` when warp travel starts or ends | 2354-2364 | | |
| 3 | `pilotAim = input:getNormalizedCursor()`, `pilotKeys = input:getKeyAxis()`, `steering = pilotAim.Magnitude > Autopilot.OverrideDeflection (0.5) or pilotKeys ≠ 0` (hands the ship back from the docking computer); `aim`, `keyAxis` = 0 while travelling, controlled or on foot; `cursorRolls = input:isMouseRoll()` | 2375-2381 | | |
| 4 | `handling:update(dt, thrust:getSpeed(), systems:getFuelFraction(), profile:getCargoUsed())` (speed of the previous frame) | 2384 | | handling |
| 5 | Autopilot: disengage "ATMOSPHERE" (throttle 0) in an atmosphere/landing scene, "STANDBY" while controlled; `compute(...)` → if it switched itself off: throttle 0 and voice; else if the pilot deflects > 0.5 or presses Q/E or FA is off → "MANUAL OVERRIDE"; else `aim = autoCursor`, `keyAxis = autoRoll`, `cursorRolls = false`, `thrust:setThrottle(autoThrottle)` | 2389-2423 | | aim, keyAxis, throttle |
| 6 | Walking aboard without autopilot: throttle 0, FA on | 2427-2430 | | |
| 7 | `rotation:update(dt, aim, keyAxis, handling, thrust.flightAssist, cursorRolls)` | 2432 | | orientation, rates |
| 8 | `orbits:update(dt)`; station carry: `rotation:shiftBy(stations:getCarry())` (ship moves with the station it is in) | 2435-2443 | | position |
| 9 | `gravityState = gravity:getState(position, thrust.velocity)` (velocity of the previous frame) | 2447-2448 | | gravityState |
| 10 | Landing scene re-anchor (moves pose and velocity) — **Roblox only** | 2453-2458 | | |
| 11 | Landing scene active: overwrite gravityState with scene values (altitude over ground, scene gravity, `speedLimit = surface:getSpeedLimit(alt)`, heat 0, zone "landing") — **becomes the seamless near-ground law, section 9.5** | 2465-2482 | | |
| 12 | Space: `timeToImpact = ∞` unless on the old voxel surface; station mass lock / approach limit; zero gravity inside a station frame; system-edge limit and toast | 2484-2529 | stations, universe position | speedLimit, zone |
| 13 | `thrust.speedZone = ShipGravity.zoneLabel(zone, cosine)`; `thrust:setSpeedLimit(limit, zone == "surface")`; `thrust:setGravity(gravityVector)` | 2531-2538 | | thrust inputs |
| 14 | `delta = thrust:update(dt, shipCFrame, handling)`; if `boostStarted`: `systems:addHeat(profile.Boost.Heat)` | 2541-2546 | | velocity, felt accel |
| 15 | `hitNormal = rotation:translate(guiding ? 0 : delta, inSpace)`; if hit: `thrust:cancelVelocityAlong(hitNormal)` (field edge) | 2548-2553 | | position |
| 16 | Atmosphere guidance overrides pose/velocity/acceleration/felt; on release: `centerCursor()` and `setThrottle(throttleFor(speed, min(cap, limit), topSpeed))` | 2557-2580 | | |
| 17 | `systems:update(dt, gravityState.heat (star 0..1), thrust.throttle, thrust:getLocalAcceleration(cf))`; then `gravityState.heat = systems:getHeat()` (ship heat 0..2.2, used by warnings) | 2588-2589 | | fuel, heat, G |
| 18 | Crash checks: heat ≥ 2.0 → crash "HEAT"; ground and barrier holds (section 5.7) | 2594-2699 | | |
| 19 | Floating origin, stations, docking, collision, traffic, pilots, character rig — other areas | 2706-2787 | | |
| 20 | `pitchAngle, localUp = getPitchAngle(cf, gravityState)` (`:1674-1688`) | 2793 | | |
| 21 | Flat cursor hidden while looking around, docked or on foot | 2797-2802 | | |
| 22 | Cockpit state (stick shows the autopilot's axes, swapped when the mouse rolls) | 2806-2878 | | |
| 23 | `hull:update(cf, throttle, boosting)` (steps the gear animation) and `hull:setGearDown((sceneActive and alt < 60) or docking:wantsGear(cf))` | 2880-2884 | | gear |
| 24 | `cockpit:update(dt, localFeltAccel, angularVelocityDeg, speed / 1000)` (head sway) | 2885 | | |
| 25 | Camera, entry glow — other areas | 2889-2906 | | |
| 26 | `audio:update(...)` with throttle, speed, maxSpeed 1000, forward accel, `rotationAmount`, `rollAmount`, topSpeed, velocity, boosting, cruising, FA | 2909-2928 | | |
| 27 | FA changed → voice "FlightAssistOn/Off" | 2930-2935 | | |
| 28 | Warnings, callouts, markers, dust, maps, warp, atmosphere, ring debris heat (`systems:addHeat(debrisHeat)`) | 2939-3083 | | |
| 29 | `hud:update(...)` (section 7) | 3085-3115 | | |
| 30 | Flat cursor: `setCockpit(interior:hasFlightHud() and not external)`, `updateLimit(gravityState.speedLimit)` (raw limit, not the smoothed one), `update(input:getCursorPixels(), input:getRadius())` (the PILOT's cursor even during autopilot), `updateFlight(dt, throttle, speed, forwardAccel, FA)` | 3118-3121 | | |
| 31 | Attitude frame filled and `hud.attitude:update(dt, frame)` | 3130-3151 | | |

Key handlers outside the frame: `toggleAutopilot` (`:964-981`), `autopilotButton` (`:1059-1071`), cockpit buttons `FlightAssist` and `Boost` (`:1089-1092`), ship switch rebuilds `ShipHandling` and sets the new tank (`:1134, 1138`), docking release hands over velocity and sets throttle 0 (`:450-459`), crash respawn resets thrust and systems and recentres the cursor (`:1428-1439`).

---

## 3. Modules

### 3.1 ShipInput (`ShipClient/ShipInput.lua`)

**Purpose.** Turns mouse and keyboard into (a) a virtual cursor offset from the screen centre, (b) a digital key axis from Q/E, (c) a look-around head angle. Rationale in the header: the real mouse leaves the window and sticks to the screen edge, so the mouse is locked to the centre and only its deltas are accumulated into an own position clamped to a circle.

**API.** `new(camera)`, `update(dt)`, `getNormalizedCursor(): Vector2`, `getCursorPixels()`, `getRadius()`, `getKeyAxis()`, `isMouseRoll()`, `centerCursor()`, `setWanted(bool)`, `getLook()`, `isLooking()`, `setLookLimits(yaw, up, down)`, `setLookEnabled(bool)`, `destroy()`. Fields read by others: `mouseCaptured`, `lookHeld`; debug writes `cursorPixels`, `cursorHold`.

**State.** `cursorPixels` (px from centre), `radius`, `keyAxis`, `mouseCaptured`, `wanted`, `userReleased`, `focused`, `cursorHold` (tests), `look` (deg, X right, Y up), `lookHeld`, `looking`, `lookEnabled`, `lookHold` (tests), `lookLimits`.

**Per frame** (`:168-238`):

```text
radius = clamp(min(viewport.x, viewport.y) * Cursor.RadiusScale(0.20), MinRadius 130, MaxRadius 300)   // :126-131, on viewport change
looking = lookHeld && lookEnabled && mouseCaptured
if mouseCaptured and mouseDelta != 0:
    sens = setting MouseSensitivity (0.25..2, default 1); pitchSign = setting InvertPitch ? -1 : 1
    if looking:   look.x = clamp(look.x + d.x * Interior.LookSensitivity(0.25) * sens, -yawLimit, yawLimit)
                  look.y = clamp(look.y - d.y * pitchSign * 0.25 * sens, -downLimit, upLimit)
    else:         cursorPixels += (d.x, d.y * pitchSign) * Cursor.Sensitivity(1.0) * sens;  moved = true
if !looking && !lookHold && look != 0:  look *= exp(-dt / Interior.LookReturnTime 0.12); if |look| < 0.05: look = 0
if Cursor.DecayTime > 0 && ...:  (decay back to centre)   // DecayTime = 0: DISABLED on purpose, see below
if |cursorPixels| > radius: cursorPixels = unit(cursorPixels) * radius
keyAxis = (Q held ? +1 : 0) + (E held ? -1 : 0)      // not while a text box has focus; +1 = Q = "YawLeft"
```

- `getNormalizedCursor() = cursorPixels / radius` (inside the unit circle, Y down).
- `isMouseRoll() = setting MouseYaw != true` (default ROLL → true).
- Look limits: cockpit 140° yaw / 80° up / 70° down; external view 180 / 60 / 60 (`ShipConfig.lua:2858-2868`, `ShipClient.lua:946-951`).
- Mouse capture (`:133-157`): captured only when `wanted && !userReleased && focused`. Left Alt toggles `userReleased`; `setWanted(w)` (game wants the mouse: false while a map, station screen, training card or construction UI is open) clears an old Alt release; losing window focus releases. Right mouse button sets `lookHeld` even over GUI.
- **Decay is deliberately off.** `Cursor.DecayTime = 0` (`ShipConfig.lua:1619-1625`): AFloidDEV tested a self-centring cursor on 2026-11-05 and rejected it ("please do not switch it on again"). The deflection stays put; at the edge the ship simply keeps turning.

### 3.2 ShipHandling (`ShipClient/ShipHandling.lua`)

**Purpose.** Flight properties from the ship profile and the current mass and speed. Called every frame before rotation and thrust (`ShipClient.lua:2384`); rebuilt on ship switch (`:1134`).

**API.** `ShipHandling.getProfile()` = `ShipConfig.Ships[Hull.Model]` or VARROW (`:48-50`); `new(profile)`; `update(dt, speed, fuelFraction, cargoMass)`. Fields: `profile, mass, massRatio, overloaded, topSpeed, boostSpeed, reverseSpeed, strafeSpeed, accel{Forward, Reverse, Lateral, Vertical}, turnRate (rad/s Vector3: X pitch, Y yaw, Z roll), turnAccel (rad/s²), agility`.

**Formulas** (`:72-111`):

```text
mass       = HullMass + ModuleMass + FuelMass * clamp(fuelFraction, 0, 1) + cargoMass(t)
overloaded = mass > MaxMass
ratio      = max(mass / DesignMass, MinMassRatio 0.5)
f(e)       = ratio ^ -e                                     // :30
topSpeed   = TopSpeed   * f(0.15);  boostSpeed = BoostSpeed * f(0.15)
reverseSpeed = ReverseSpeed * f(0.15); strafeSpeed = StrafeSpeed * f(0.15)
accel.X    = Acceleration.X * f(0.75)   for Forward, Reverse, Lateral, Vertical
fraction   = speed / max(topSpeed, 1)
agility    = fraction <= 1 ? blueZone(fraction) : BlueZoneHigh(0.7) * max(CruiseTurnFloor 0.6, fraction ^ -CruiseTurnExponent 0.6)
blueZone(x)= x <= 0.5 ? 0.65 + 0.35 * (x / 0.5) : 1 - 0.3 * clamp((x - 0.5) / 0.5, 0, 1)     // :35-43
turnRate   = rad(TurnRate.{Pitch, Yaw, Roll}) * f(0.2) * agility
turnAccel  = rad(TurnAcceleration.{Pitch, Yaw, Roll}) * f(0.75)          // NO agility factor
```

Agility table: 0 → 0.65, 0.25 → 0.825, 0.5 → 1.0, 0.75 → 0.85, 1.0 → 0.70, 1.5 → 0.549, 2.0 → 0.462, ≥ 2.343·TopSpeed → 0.42 (floor). `cargoMass` is `profile:getCargoUsed()` in tonnes (economy). Overload is only reachable with cargo beyond capacity (VARROW overloads above 110 t extra at full tank).

### 3.3 ShipRotation (`ShipClient/ShipRotation.lua`)

**Purpose.** Holds the ship's pose (orientation and position) and applies rotation in the **local** ship frame each frame (`cframe = cframe * smallRotation`): no world up, no gimbal lock, endless loops are possible. The stick commands a **rate**, not an angle.

**API.** `new()` (pose from `Ship.StartPosition/StartLookDirection`), `update(dt, cursor, keyAxis, handling, flightAssist, cursorRolls)`, `translate(delta, inSpace): hitNormal?`, `shiftBy(delta)` (keeps rates), `setCFrame(cf)` (zeroes rates), `getCFrame()`, `getPosition()`, `setPosition(p)`, `getInputAmount()`, `getRollAmount()`, `getAngularVelocity()` (deg/s, X pitch, Y yaw, Z roll).

**State.** `cframe`, `pitchRate`, `yawRate`, `rollRate` (rad/s, local axes), `inputAmount`, `rollAmount` (0..1, for audio), `agility` (copy).

**Per frame** (`:78-143`):

```text
input = applyDeadzone(cursor, Cursor.Deadzone 0.20, Rotation.InputCurve 1.7)      // cursor only; keys are digital
if cursorRolls:   command = (-input.y,  keyAxis, -input.x)     // (pitch, yaw, roll): mouse Y pitches, Q/E yaw, mouse X rolls
                  inputAmount = clamp(|(input.y, keyAxis)|, 0, 1);  rollAmount = clamp(|input.x|, 0, 1)
else:             command = (-input.y, -input.x,  keyAxis)     // legacy / autopilot: mouse X yaws, key axis rolls
                  inputAmount = clamp(|input|, 0, 1);            rollAmount = clamp(|keyAxis|, 0, 1)
maxRate = handling.turnRate; accel = handling.turnAccel
if flightAssist:  rate.a = approach(rate.a, command.a * maxRate.a, accel.a * dt)                 for a in pitch, yaw, roll
else:             rate.a = clamp(rate.a + command.a * accel.a * FaOffTorque(0.7) * dt, ±maxRate.a * FaOffRateBonus(1.1))
cframe = cframe * CFrame.fromEulerAnglesYXZ(pitchRate*dt, yawRate*dt, rollRate*dt)
cframe = orthonormalize(cframe)
```

Sign logic (`:94-105`): screen Y is down, so cursor up gives `input.y < 0` and `pitch = -input.y > 0` raises the nose. Positive yaw turns left (Roblox convention), so cursor right must give negative yaw (`-input.x`) and Q (+1) yaws left. Positive roll is left, so cursor right rolls right (`-input.x`); in the legacy mapping Q (+1) rolls left.

Port note: `fromEulerAnglesYXZ` of tiny angles differs from `Quat.integrateLocal` (exponential map) only at O(dt²); the Lua comment says the order "practically does not matter". Use `integrateLocal`.

`translate(delta, inSpace)` (`:156-174`): `newPos = pos + delta`; if `Ship.FieldRadius > 0` and in space: `d = |universe(newPos)|`; if `d > FieldRadius` clamp to the sphere and return the outward unit normal; the caller removes the outward velocity (`ShipThrust:cancelVelocityAlong`). Field radius 9,000,000 studs (**×S**).

### 3.4 ShipThrust (`ShipClient/ShipThrust.lua`)

**Purpose.** Translation with mass: throttle lever, Flight Assist, boost, cruise, speed caps, the gravity brake and real gravity. All per-axis accelerations come from `ShipHandling`.

**API.** `ShipThrust.targetSpeed(throttle, reference, top)`, `ShipThrust.throttleFor(speed, reference, top)` (static); `new()`, `readInput()`, `update(dt, shipCFrame, handling): positionDelta`, `setGravity(a)`, `setThrottle(v)` (clamped to MinThrottle..1), `setInputEnabled(b)`, `setSpeedLimit(limit, immediate)`, `advanceSpeedLimit(dt)`, `reset()` (velocity, accelerations, throttle, boost timer, cruising, boosting to zero; keeps cooldown and FA), `cancelVelocityAlong(n)` (`v -= n * max(v·n, 0)`), `getSpeed()`, `getForwardAcceleration()`, `getLocalAcceleration(cf)`.

**State.** `throttle` (−0.25..1), `velocity` (world, m/s), `acceleration` (true Δv/dt), `feltAcceleration`, `flightAssist` (start `Thrust.FlightAssistDefault = true`), `speedLimit` (tracked), `rawSpeedLimit`, `limitImmediate`, `gravity`, `fullStopHeld`, `inputEnabled`, `cruising`, `boosting`, `boostTimer`, `boostCooldown`, `boostRequested`, `boostStarted` (one-frame flag for heat/sound), `speedZone` (label written by ShipClient).

Keys handled here (`:138-154`): Z toggles FA, B sets `boostRequested`. Ignored on station decks or while walking aboard (`ShipConfig.Runtime.Deck/Aboard`).

#### 3.4.1 Throttle mapping (`:65-106`)

```text
targetSpeed(t, reference, top):                // base = Thrust.MaxSpeed = 1000
    if reference <= 1000: return t * reference
    band = clamp(top / 1000, 0.05, 0.95)       // VARROW 0.26
    if t <= band: return t * 1000              // combat band stays linear: lever 26 % = 260 m/s
    return t * 1000 * (reference / 1000) ^ ((t - band) / (1 - band))     // exponential up to the reference at t = 1
throttleFor(speed, reference, top):            // inverse, for the autopilot and handovers
    speed <= 0 → 0; reference <= 1000 → clamp(speed / max(reference, 1e-3), 0, 1); speed >= reference → 1
    band as above; speed <= band*1000 → speed / 1000
    else bisection on [band, 1], 30 iterations, return the midpoint
```

VARROW, reference 3 Mm/s: lever 0.30 → 463 m/s, 0.40 → 1.82 km/s, 0.50 → 6.71 km/s, 0.60 → 23.8 km/s, 0.70 → 81.8 km/s, 0.80 → 276 km/s, 0.90 → 915 km/s, 1.00 → 3.00 Mm/s. The comment: "linear would put the whole combat band in the first percent".

#### 3.4.2 `readInput()` (`:162-209`)

Returns `(throttleAxis, lateral)`. Zero (and `fullStopHeld = false`) when input is disabled or a text box has focus. `throttleAxis = (W ? +1) + (S ? -1)`. `lateral` in ship axes: D +X, A −X, StrafeUp (R or Space) +Y, StrafeDown (F or Left Ctrl) −Y; normalised if longer than 1. `fullStopHeld = X held`.

#### 3.4.3 `update(dt, shipCFrame, handling)` (`:217-431`)

```text
advanceSpeedLimit(dt)                                                   // 3.4.4
(throttleAxis, lateral) = readInput()

// lever                                                                    :225-237
if fullStopHeld:   step = dt / max(FullStopTime 1.2, 1e-3); throttle = |throttle| <= step ? 0 : throttle - sign(throttle)*step
elif throttleAxis: throttle = clamp(throttle + throttleAxis * dt / ThrottleRampTime(4), MinThrottle(-0.25), 1)

// boost                                                                    :240-252
boostCooldown = max(0, boostCooldown - dt); boostTimer = max(0, boostTimer - dt)
if boostRequested:
    boostRequested = false                                                 // a refused request is dropped, not queued
    if boostCooldown <= 0 && !cruising(previous frame) && !handling.overloaded && inputEnabled:
        boostTimer = Boost.Duration; boostCooldown = Boost.Cooldown; boostStarted = true   // cooldown counts from ignition
boosting = boostTimer > 0

prevV = velocity; speed = |velocity|; top = handling.topSpeed
cap = boosting ? handling.boostSpeed : top
fwdAccel = accel.Forward * (boosting ? Boost.AccelerationMultiplier : 1)
reference = min(Thrust.CruiseMaxSpeed 3e6, speedLimit)
targetForward = throttle >= 0 ? targetSpeed(throttle, reference, top)
                              : (throttle / -MinThrottle) * handling.reverseSpeed        // -0.25 → -ReverseSpeed
if boosting: targetForward = max(targetForward, handling.boostSpeed)
local = inverse(R) * velocity; forwardSpeed = -local.z

cruising = flightAssist && !boosting && !fullStopHeld
           && (speed > 1.02*top || (targetForward > top && forwardSpeed >= 0.98*top))      // :279-281

if cruising:                                                                 // :284-321
    goal = max(targetForward, 0)
    massSlowdown = profile.Acceleration.Forward / max(accel.Forward, 1e-3)    // = ratio^0.75, heavier = slower
    if goal > speed:
        gain = (goal - speed) * (1 - exp(-dt / (CruiseTimeUp 0.8 * massSlowdown)))
        gain = min(gain, speed * (2 ^ (dt / (CruiseDoubleTime 0.8 * massSlowdown)) - 1))   // at most one doubling per 0.8 s
        gain = max(gain, accel.Forward * dt)                                  // never slower than normal thrust
        newSpeed = min(speed + gain, goal)
    else:
        newSpeed = speed + (goal - speed) * (1 - exp(-dt / (CruiseTimeDown 1.8 * massSlowdown)))
    dir = speed > 1e-3 ? velocity / speed : forward
    dir = lerp(dir, forward, 1 - exp(-dt / CruiseAlign 0.25)); dir = |dir| > 1e-6 ? unit(dir) : forward
    velocity = dir * newSpeed
    felt = (velocity - prevV) / max(dt, 1e-6) * CruiseFeltFactor(0.1)
    if |felt| > CruiseFeltMaxG(1.5) * FeltStudsPerG(9.81): felt = unit(felt) * 14.715      // a gentle push, never a blackout

elif flightAssist:                                                           // :322-348 combat band, FA on
    desired = (lateral.x * strafeSpeed, lateral.y * strafeSpeed, -min(targetForward, cap))  // -Z is forward
    if fullStopHeld: desired = 0
    zAccel = desired.z < local.z ? fwdAccel : accel.Reverse                 // more forward speed → main engine, else retro
    xAccel = accel.Lateral  * (lateral.x == 0 ? AssistAuthority 2.0 : 1)    // drift killed with doubled side thrust
    yAccel = accel.Vertical * (lateral.y == 0 ? 2.0 : 1)                    // wanted strafing uses real thrust
    local' = (approach(local.x, desired.x, xAccel*dt), approach(local.y, desired.y, yAccel*dt), approach(local.z, desired.z, zAccel*dt))
    velocity = R * local'
    felt = (velocity - prevV) / max(dt, 1e-6)

else:                                                                         // :349-372 FA off, pure Newton
    if fullStopHeld:    // retro burn: every axis against its own motion
        brakeZ = local.z < 0 ? accel.Reverse : fwdAccel
        local' = (approach(local.x, 0, Lateral*dt), approach(local.y, 0, Vertical*dt), approach(local.z, 0, brakeZ*dt))
        thrustLocal = (local' - local) / max(dt, 1e-6)
    else:
        z = throttle >= 0 ? -throttle * fwdAccel : (throttle / MinThrottle) * accel.Reverse
        thrustLocal = (lateral.x * Lateral, lateral.y * Vertical, z)
    velocity += R * thrustLocal * dt
    felt = (velocity - prevV) / max(dt, 1e-6)

if !cruising:                                                                 // :377-388 cap outside cruise (also FA off!)
    s = |velocity|
    if s > cap && s > 1e-4:
        reduced = (s > 1.02*top && !boosting) ? s + (cap - s) * (1 - exp(-dt / CruiseTimeDown 1.8))   // out of cruise: quick
                                              : max(cap, s - accel.Reverse * dt)                       // else retro thrust
        velocity = unit(velocity) * reduced

limit = max(speedLimit, Gravity.MinManoeuvreSpeed 12)                         // :394-408 gravity brake, both FA modes
if limit < ∞ and |velocity| > limit:
    step = Gravity.BrakeAcceleration(80) * dt
    excess = |velocity| - max(limit, Thrust.MaxSpeed 1000)
    if excess > 0: step = max(step, excess * (1 - exp(-dt / Gravity.CruiseBrakeTime 0.4)))
    velocity = unit(velocity) * max(limit, |velocity| - step)

if |gravity| > 1e-5:                                                          // :413-419
    velocity += gravity * dt * (flightAssist ? 1 - 0.78 : 1)

if flightAssist && |velocity| < VelocityCutoff(0.05): velocity = 0           // :423-425

acceleration = dt > 0 ? (velocity - prevV) / dt : 0                           // everything included
feltAcceleration = felt                                                       // NOT including cap, brake, gravity
return velocity * dt
```

Consequences worth keeping (they are behaviour, not bugs):

- FA off still caps the speed at TopSpeed (or BoostSpeed while boosting) through the "cap outside cruise" block.
- With FA off, boost only multiplies the forward acceleration by the lever: at zero throttle a boost does nothing (it still costs heat and cooldown).
- Switching FA off in cruise drops cruise; the speed decays to TopSpeed with τ = 1.8 s.
- The cap, the gravity brake and gravity never show up as felt G (no blackout from braking).
- The boost check uses the cruise flag of the **previous** frame.

#### 3.4.4 Speed-limit tracking (`:461-488`)

`setSpeedLimit(limit, immediate)` stores `rawSpeedLimit` and `limitImmediate`. `advanceSpeedLimit(dt)`:

```text
target = rawSpeedLimit
if speedLimit == ∞ || target >= speedLimit || target >= Thrust.MaxSpeed(1000) || limitImmediate:
    speedLimit = target                         // rising limits and limits above 1 km/s apply at once
else:
    speedLimit = smooth(min(speedLimit, 1000), target, Gravity.LimitFallTime 1.6, dt)   // falling below 1 km/s: soft
```

Reasons in the comments: above MaxSpeed the limit falls continuously during an approach and any lag would shoot the ship into the mass shadow; in the approach zone ("surface") a lag made ships hit the barrier at about 380 m/s (play test 2026-11-06); a rising limit must not feel like being held back.

### 3.5 ShipGravity and the speed-limit composition (`ShipClient/ShipGravity.lua`)

**Important:** in spite of the name, this module mostly does **not** pull the ship. It caps the allowed speed near bodies (pure game design: otherwise landings would be impossible) and reports the nearest body, altitude, closing speed, star heat and a real gravity vector that `ShipThrust` applies.

**Body list.** Every `BasePart` tagged `CelestialBody` (`Gravity.Tag`). Attributes are read every frame so Studio edits apply live (`readBody`, `:97-133`). Attribute contract (set by `ShipCore/ShipSystemBuilder.lua:307-349`):

| Attribute | Value in the built systems | Use |
|---|---|---|
| `CollisionRadius` | planets and moons `ceil(1.105·r) + 6` (the "barrier", above atmosphere shells and mountains); star `r` | radius R for all laws (wins over SurfaceRadius) |
| `SurfaceRadius` | `r` | fallback radius |
| `InfluenceRadius` | not set → `R · DefaultInfluenceFactor 2.2` | approach zone height |
| `MinApproachSpeed` | planets 14, moons 12, star 40 (config default 10) | floor of every limit |
| `SpeedCurve` | 0.75 | **read but unused** (`getState` ignores it) |
| `MassShadowFactor` | planets default 5, moons 4, star 4 | shadow = R · factor |
| `MassShadowRadius` | star only (Sol 92,000; generated systems heatRadius·2.5) | absolute shadow height |
| `IsStar`, `HeatRadius` | star; Sol 37,000 (generated 26,000–48,000) | heat zone, star shadow |
| `SurfaceGravityG` | body gravity in g (Earth 1.0) | gravity pull and HUD |
| `SurfaceGravity` | `g · 196.2` | free-fall-time display only |
| `LandingBlocked`, `DisplayName`, `BodyClass` | strings | HUD |

**Static helpers.**

```text
cruiseLimit(base, gap, time = CruiseDistanceTime 3.5) = min(base + max(gap, 0) / time, CruiseMaxSpeed 3e6)      :142-145
cruiseTime(cos) = depart + (approach - depart) * clamp(0.5 + 0.5*cos, 0, 1)                                      :159-165
                  approach = CruiseDistanceTime 3.5, depart = CruiseDepartTime 1.5   (cos 1 → 3.5 s, 0 → 2.5 s, -1 → 1.5 s)
approachReach(h): brake = Gravity.ApproachBrake 70, t = 2.0, knee = brake*t*t = 280, h = max(h, 0)            :175-184
                  h <= 280 ? h / 2 : sqrt(2 * 70 * (h - 140))      // C1-continuous at the knee (140 m/s, slope 0.5)
edgeLimit(x, y, z, velocity) (universe coords):                                                                   :217-239
                  FieldRadius <= 0 → (∞, ∞); r = |p|; gap = FieldRadius - r
                  cos = (|v| > 1 && r > 1e-3) ? v·p / (|v| r) : 1          // +1 = flying outward
                  margin = EdgeMargin 2000 * clamp(0.5 + 0.5*cos, 0, 1)
                  return cruiseLimit(EdgeSpeed 150, gap - margin, cruiseTime(cos)), gap
zoneLabel(zone, cos): "cruise" → cos >= 0.25 "APPROACH", cos <= -0.25 "DEPARTURE", else "OPEN SPACE";               :189-209
                  open "OPEN SPACE", shadow "GRAVITY WELL", heat "HEAT ZONE", surface "LOW ALTITUDE",
                  station "STATION ZONE", edge "SYSTEM EDGE", landing "SURFACE"; unknown → "OPEN SPACE"
```

`cruiseLimit` meaning: flying at this limit towards the edge of a shadow (or station lock, or field edge), the gap shrinks by 1/e every `time` seconds; long trips take hardly longer than short ones, and the ship arrives at `base`.

**`getState(position, velocity)`** (`:249-459`), the per-body law:

```text
state = { speedLimit = 3e6, limitZone = "open", limitCosine = 0, altitude = ∞, distance = ∞, heat = 0,
          starAltitude = ∞, timeToImpact = ∞, freeFallTime = ∞, gravityVector = 0, gravityG = 0, ... }
for each body b (with a parent):
    (R, influence, minSpeed) = readBody(b)
    toCentre = b.pos - p; d = |toCentre|; h = d - R
    shadow = b.MassShadowRadius ?: R * (b.MassShadowFactor ?: 5)
    floor  = max(MassShadowFloor 150, 0.15 * R)                       // grows with the body: 352 m/s at Earth
    if b.IsStar:
        if b.HeatRadius: shadow = min(shadow, HeatRadius * StarShadowFromHeat 1.0)
        floor = max(floor, StarShadowMinSpeed 1000)                   // stars brake only in their heat zone
    cos = (|v| > 1 && d > 1e-3) ? v·toCentre / (|v| d) : 1             // +1 = straight at the body
    if h < shadow:  lim = max(1000 * (max(h,0) / shadow) ^ MassShadowCurve 0.75, floor);  zone = star ? "heat" : "shadow"
    else:           lim = cruiseLimit(1000, h - shadow, cruiseTime(cos));                  zone = "cruise"
    if h < influence:                                                  // approach zone
        near = max(h, 0) / 2                                           // 2 s to the barrier, any direction
        if cos > 0.05: near = min(near, approachReach(h) / cos)        // the closing part must be stoppable
        near = max(minSpeed, near)
        if near < lim: lim = near; zone = "surface"
    lim = clamp(lim * LimitMultiplier 1.0, minSpeed, 3e6)
    if b.IsStar && h < state.starAltitude:
        state.star = b; starAltitude = h; starRadius = R
        state.heat = clamp(1 - h / (HeatRadius ?: R * HeatRange 0.6), 0, 1)     // 0 at the heat-zone edge, 1 at the surface
    if lim < state.speedLimit:                                        // MINIMUM over all bodies
        speedLimit = lim; limitingBody = b; limitZone = lim >= 3e6 ? "open" : zone; limitCosine = cos
    if h < state.altitude:                                            // NEAREST body = smallest altitude, not centre
        body, bodyName (DisplayName or model name), bodyClass, altitude = h, distance = d, surfaceRadius = R
        belowSurface = h <= 0
        inMassShadow = h < shadow; massShadowRadius = shadow; inInfluence = h < influence; influenceRadius = influence
        closingSpeed = d > 1e-4 ? v · unit(toCentre) : 0
        timeToImpact = (closingSpeed > 0.05 && h > 0) ? h / closingSpeed : ∞     // warnings use TIME, not distance
        if SurfaceGravityG:
            surfaceGravityG = G
            if h < R * SurfaceGravityRange 3.5 && d > 1e-3:
                pull = G * (R / max(d, R)) ^ GravityFalloff 2;  gravityG = pull
                gravityVector = unit(toCentre) * pull * Physics.StudsPerG 60
            else: gravityG = 0; gravityVector = 0
        landingBlocked = b.LandingBlocked
        g0 = b.SurfaceGravity ?: DefaultSurfaceGravity 196.2
        freeFallTime = (g0 > 0 && h > 0) ? sqrt(2h / g0) : 0              // display only
return state
```

Earth in Roblox units (R = 2349, shadow 11,745, influence 5,168, floor 352.35), limits computed from this law:

| h (m above barrier) | towards (cos 1) | level (cos 0) | away (cos −1) |
|---|---|---|---|
| 0 / 28 | 14 surface | 14 surface | 14 surface |
| 100 | 50 surface | 50 surface | 50 surface |
| 280 | 140 surface | 140 surface | 140 surface |
| 500 | 224.5 surface | 250 surface | 250 surface |
| 1,000 | 347.0 surface | 352.3 shadow | 352.3 shadow |
| 3,000 | 359.3 shadow | 359.3 shadow | 359.3 shadow |
| 5,168 | 540.2 shadow | 540.2 shadow | 540.2 shadow |
| 10,000 | 886.4 shadow | 886.4 shadow | 886.4 shadow |
| 11,745 | 1,000 cruise | 1,000 cruise | 1,000 cruise |
| 20,000 | 3,359 cruise | 4,302 cruise | 6,503 cruise |
| 100,000 | 26,216 cruise | 36,302 cruise | 59,837 cruise |
| 1,000,000 | 283,359 cruise | 396,302 cruise | 659,837 cruise |

Sol star (R 4,800, shadow min(92,000, 37,000) = 37,000, floor 1,000, minSpeed 40): HEAT ZONE below 37 km with ≥ 1 km/s, so the pilot can always leave ("Josh 2026-11-04: star mass-locking too extreme").

**Composition in ShipClient (space, `:2484-2538`).**

```text
gs = gravity:getState(p, v)
if not atmosphere:isOnSurface(): gs.timeToImpact = ∞                 // the barrier holds the ship in space: no impact warning
(stationLimit, lock) = stations:getLimit(p)                          // ShipStation.lua:1036-1050, nearest station:
        // d > DockingRange 6000 → (∞, false); else 260 (MassLockSpeed); d < ZoneRadius 2200 → 140; in hangar box → 60
if lock:  if stationLimit < gs.speedLimit: gs.limitZone = "station"
          gs.speedLimit = min(gs.speedLimit, stationLimit); gs.inMassShadow = true      // no cruise, no jump
else:     (approachLimit, cos) = stations:getApproachLimit(p, v)    // ShipStation.lua:1057-1071
          // = cruiseLimit(MassLockSpeed 260, distance - DockingRange 6000, cruiseTime(cos to station centre))
          if approachLimit < gs.speedLimit: gs.limitZone = "cruise"; gs.limitCosine = cos
          gs.speedLimit = min(gs.speedLimit, approachLimit)
if stations:getFrame(): gs.gravityVector = 0                          // inside a station frame nothing pulls
edge = ShipGravity.edgeLimit(universe(p), v)
if edge < ∞: if edge < gs.speedLimit: gs.limitZone = "edge"
             gs.speedLimit = min(gs.speedLimit, edge)
             if edge < speed: toast "SYSTEM EDGE" / "THE SYSTEM ENDS HERE  ·  PRESS <StarMap key> FOR THE STAR MAP AND JUMP ON" (6 s), at most every 30 s
thrust.speedZone = zoneLabel(gs.limitZone, gs.limitCosine)
thrust:setSpeedLimit(gs.speedLimit, gs.limitZone == "surface")     // approach zone: immediate
thrust:setGravity(gs.gravityVector)
```

The station frame (`stations:getFrame()`, `getCarry()`) moves the ship with the station each frame (`ShipClient.lua:2438-2443`): thrust velocity is effectively **relative to the station**. Section 9.4 extends this to planets.

`getPitchAngle(cf, gs)` (`ShipClient.lua:1674-1688`): `up` = scene up on the surface, else `unit(p - nearestBody.pos)`; `pitch = deg(asin(clamp(look·up)))`; `nil` without a body. Feeds HUD, cockpit and the attitude indicator.

### 3.6 ShipSystems (`ShipClient/ShipSystems.lua`)

**Purpose.** Fuel, heat and G-load. Heat runs 0..2 (2.2 max): warnings start at 1.0 (100 %), the ship is destroyed at 2.0, leaving time to react between "critical" and "over". Fuel is purely a mass/visual quantity: it never blocks thrust, it sinks with throttle and refills near a star, which gives heat a second purpose.

**API.** `new()` (full tank), `reset()`, `update(dt, starHeat, throttle, feltLocal)`, `addHeat(a)` (clamped 0..2.2; boost, atmosphere entry, ring debris), `getFuelFraction()`, `setFuelFraction(f)`, `getHeat()`, `getGForce()`, `getStrainG()`, `getRedout()`, `isScooping()`.

```text
update(dt, starHeat 0..1, throttle, a = felt acceleration in SHIP axes (x side, y up, -z forward)):     :59-104
    gain = starHeat * HeatFromStar 0.115 + |throttle| * HeatFromThrust 0.018
    scooping = starHeat > 0.25 && fuel < FuelCapacity 100
    if scooping: fuel = min(100, fuel + FuelScoopRate 3.5 * perk(ship, "ScoopRate") * dt); gain += FuelScoopHeat 0.075 * starHeat
    fuel = max(0, fuel - |throttle| * FuelPerSecondAtFullThrottle 0.22 * perk(ship, "FuelBurn") * dt)
    loss = HeatCooling 0.075 * (0.4 + heat)                              // base cooling always works
    heat = clamp(heat + (gain - loss) * dt, 0, HeatMax 2.2)
    g = a / Handling.FeltStudsPerG 9.81                                  // felt g: 1 m/s² ≈ 1/9.81 g
    fwd = -g.z;  gx = fwd * (fwd >= 0 ? Tol.Forward 0.35 : Tol.Backward 0.5)
    gy = g.x * Tol.Lateral 0.5
    gz = g.y * (g.y >= 0 ? Tol.Up 1.0 : Tol.Down 1.5)                    // +Gz pulling up: blackout; -Gz pushing: redout
    strain = sqrt(gx² + gy² + gz²)
    redoutRaw = (g.y < 0 && strain > 1e-3) ? gz² / strain² : 0
    gForce  = smooth(gForce,  |g|,       0.12, dt)
    strainG = smooth(strainG, strain,    0.12, dt)
    redout  = smooth(redout,  redoutRaw, 0.25, dt)
```

Numbers: a full tank lasts 455 s at full throttle (LOCHRAN 758 s, TORRIN 535 s); an empty tank refills in 28.6 s of scooping (LOCHRAN 19 s). At the star surface (starHeat 1) while scooping at full throttle: `dh/dt = 0.178 - 0.075 h`, equilibrium 2.37: 100 % after 7.3 s, fatal 200 % after 24.7 s. At starHeat 0.5 the equilibrium is 1.11 (warning, not fatal). Thrust alone never heats above 0 (equilibrium −0.16).

**Consumers.** Heat death: `ShipClient.lua:2596` crashes at `Warnings.Overheat.FatalHeat 2.0`; warnings at Phase1Heat 0.80 / Phase2Heat 1.00 with hysteresis 0.88 (`ShipConfig.lua:571-579`). Blackout overlay (`ShipHud.lua:355-370`):

```text
target   = strain > BlackoutStartG 6.5 ? clamp((strain - 6.5) / (BlackoutFullG 11 - 6.5), 0, 1) * BlackoutMaxDarkness 0.82 : 0
darkness = smooth(darkness, target, BlackoutResponse 0.35, dt)
overlay  = full-screen frame, colour lerp(black, RedoutColor (110,0,0), clamp(redout, 0, 1)), alpha = darkness, above the HUD, below warnings
HUD G colour: strain >= 6.5 → (255,70,60); >= 3.9 → (255,165,70); else (206,226,240)
```

In practice: FA drift correction gives at most 2 × lateral/vertical thrust (VARROW 44 m/s² = 4.5 g, below 6.5); pushing the nose down gives −Gz × 1.5 = 6.7, a slight redout; boost forward 77 m/s² = 7.9 g × 0.35 = 2.7. Cruise felt G is capped at 1.5 g.

### 3.7 ShipAutopilot (`ShipClient/ShipAutopilot.lua`) and its glue

**Purpose.** Flies to the locked target by producing the inputs of a very calm pilot: a cursor, a throttle and a roll value. All inertia, limits and brakes still apply. Braking follows v = √(2·a·s) because speeds differ by a factor of seventy; a fixed braking point would be early or late.

**API.** `new()` (status "STANDBY"), `engage(target): bool` (nil → status "NO TARGET", false), `disengage(reason?)` (status = reason or "STANDBY"), `isEngaged()`, `compute(dt, shipCFrame, speed, speedLimit, handling): (cursor, throttle, roll) | nil`, `getStatus()`, `getTarget()`. Status strings: STANDBY, NO TARGET, ENGAGED, TARGET LOST, ARRIVED, ALIGNING, CRUISING, BRAKING, ATMOSPHERE, MANUAL OVERRIDE.

```text
compute(dt, cf, speed, speedLimit = thrust.speedLimit (tracked, previous frame), handling):          :65-189
    if !engaged or target gone: if engaged: disengage("TARGET LOST"); return nil
    R = target.CollisionRadius ?: target.SurfaceRadius ?: 100
    toT = target.pos - cf.pos; d = |toT|; surf = max(d - R, 0)
    if surf <= R * ArriveDistance 0.35 && speed <= ArriveSpeed 25: disengage("ARRIVED"); return nil
    dir = d > 1e-4 ? unit(toT) : cf.look
    // aim (cursor coordinates: x right, y down)
    l = inverse(R_cf) * dir; fwd = -l.z; angle = acos(clamp(fwd, -1, 1)); s = sin(angle)
    if s < 1e-3 && fwd < 0: cursor = (0, -1)                                       // straight behind: pull up
    else: k = s > 1e-4 ? angle / s : 1; cursor = (clamp(l.x * k * TurnGain 4, -1, 1), clamp(-l.y * k * 4, -1, 1))
    roll = 0
    if angle > rad(RollToPitchAngle 20):                                         // roll the target into the pitch plane
        bank = atan2(l.x, l.y)                                                   // 0 = straight up, + = right
        if bank > π/2: bank -= π elif bank < -π/2: bank += π                     // targets below: push, don't roll over
        roll = -clamp(bank * RollGain 2, -1, 1)                                  // positive roll value = left (like Q)
    m = |cursor|; if m > 1e-4: cursor = unit(cursor) * min(Deadzone 0.2 + m * 0.8, MaxCursor 0.95)   // undo the dead zone
    // throttle
    if dir · cf.look >= AlignmentForThrust 0.965:
        dist = max(surf - R * StopAltitude 0.2, 0) / BrakeMargin 1.35
        brake = handling.accel.Reverse; top = handling.topSpeed; band = top² / (2 brake)
        stop = dist <= band ? sqrt(2 brake dist)
             : top + (dist - band) / (CruiseTimeDown 1.8 * profile.Forward / accel.Forward)
        desired = min(stop, speedLimit, CruiseMaxSpeed)
        throttle = throttleFor(desired, max(min(CruiseMaxSpeed, speedLimit), 1e-3), top)
        if speed > desired * 1.05: status = "BRAKING"; throttle = 0 else status = "CRUISING"
    else: status = "ALIGNING"; throttle = 0
    return cursor, throttle, roll                // read in the LEGACY mapping: cursor x = yaw, roll on the key axis
```

The dead-zone compensation means `applyDeadzone(0.2 + 0.8 m, 0.2, 1.7) = m^1.7`. Examples (VARROW, full tank): target 90° right → cursor (0.95, 0), roll −1, ALIGNING; target behind → (0, −0.95); target 10° above → (0, −0.7585) → pitch command +0.543. Luna (R 647): stop speed 179.6 m/s at 1 km surface distance, 40.6 km/s at 100 km; arrival when within 226 m and ≤ 25 m/s.

Glue in ShipClient:

- **Engage** (`toggleAutopilot`, `:964-981`): needs a locked target, not in warp travel, not in an atmosphere/landing scene. On engage: `input:centerCursor()` (a deflected cursor would count as manual input next frame) and **FA forced on** (it brakes through the flight computer). Voice AutopilotOn / AutopilotOff / AutopilotNoTarget.
- **P key** (`autopilotButton`, `:1059-1071`): with a docking clearance it toggles the docking computer instead (docking doc).
- **Disengage**: pilot deflection > `OverrideDeflection 0.5`, any Q/E, FA off → "MANUAL OVERRIDE" (voice); atmosphere → "ATMOSPHERE" (throttle 0); controlled → "STANDBY"; self-disengage (ARRIVED, TARGET LOST) → throttle 0 and voice. W/S during autopilot only nudge the lever for one frame (it is overwritten).
- `DisengageOnInput = true`. The 0.5 threshold exists because the 0.2 dead zone was so fine that a resting hand on the mouse switched it off.

### 3.8 ShipGear (`ShipClient/ShipGear.lua`) and gear stepping (`ShipClient/ShipHull.lua`)

**Purpose.** Animated landing gear (AFloidDEV 2026-11-06: "Can you make animated Landing Gears"). `ShipGear` is pure maths over joint data stored as attributes on the parts of each ship's `Gear` model; `ShipHull` runs the timing and visibility.

**Joint data per part** (attributes; all CFrames in **root space = eye space**, forward −Z):

| Attribute | Type | Meaning |
|---|---|---|
| `GearRole` | "Leg", "Foot", "Link", "Mount", "Door" ("Bay" stays still) | how the part moves |
| `GearLeg` | "Nose", "MainL", "MainR", ... | which leg (grouping/metadata) |
| `Hinge`, `SwingDeg` | CFrame, number | leg joint: its **X axis** is the rotation axis; degrees at t = 1 |
| `GearLift`, `GearLiftFrom` | number (studs), 0..1 | lift into the hull at the end (ships without a gear bay) and from which leg progress |
| `GearAnkle` | Vector3 | Foot: pivot point that follows the leg; the pad stays level |
| `GearLinkFixed`, `GearLinkMove` | Vector3, Vector3 | Link: end fixed to the hull, end moving with the leg; the rod pivots and stretches |
| `DoorHinge`, `DoorSwingDeg` | CFrame, number | Door: model pose = open; closes by SwingDeg |
| `GearClosed` (hull parts) | bool | closed-hull covers, visible only while stowed |

**These attributes are not in `Ships.gltf`** (its node extras carry only Roblox part properties). They are in `Assets.rbxmx` as base64 `AttributesSerialize`. Decoded sample, VARROW nose leg: `Hinge` at (0, −7.525, 0.675) with identity rotation, `SwingDeg` 90; nose doors `DoorHinge` (−1.25, −6.05, −2.0) with `DoorSwingDeg` +104 and (1.199, −6.115, −2.0) with −104, both with rotation id 0x0E; main legs hinge at (∓13.4, −7.325, 43.875); `Brace` is a Link (fixed (0.16, −6.05, 0.3), move (0.16, −7.5, −2.0)); `ActuatorSleeve/Rod` are Links with `GearLift` 0.33 (main legs 0.36); pads are Feet with `GearAnkle` (0, −10.2, −2.0). The ship model also carries `GearDown = true` and `RootToGround = 11.0` (eye to ground with gear down). Axis-aligned CFrames use the standard Roblox rotation-ID table (0x02 identity …); verify the door direction visually against the `GearDoorClosed` parts.

**Build** (`ShipGear.build(entries)`, `:63-139`): entries are `{key, part, base}` with `base` = the part's pose in root space with gear **extended**. Per part: Door uses `DoorHinge/DoorSwingDeg`; Leg/Foot/Mount/Link use `Hinge/SwingDeg` plus lift; a Foot without `GearAnkle` becomes a Leg; a Link without valid ends (or ends < 1e-3 apart) becomes a Leg; Mount has deg 0. Skip parts without a CFrame hinge or numeric degrees. `lift = GearLift or 0`; `from = GearLiftFrom ?: (deg ≠ 0 && lift ≠ 0 ? SWING_SHARE 0.7 : 0)`, clamped to 0..0.95. Parts with identical (role, hinge, deg, lift, from, ankle) form a group: `{pivot = hinge, inverse = hinge⁻¹, rad, lift, from, swingEnd = (lift ≠ 0 && from > 0) ? from : 1, ankle}`; each item stores `rel = hinge⁻¹ · base`. Links additionally store `fixed, move, dir = unit(move − fixed), len`, the part size and the part axis (right/up/look) best aligned with `dir`. Returns `{groups, doors}` or nil.

**Pose** (`ShipGear.pose(rig, legT, doorT, write, resize)`, `:147-192`), t = 0 extended, 1 stowed:

```text
Door:  write(item, pivot * Angles(rad * smoothstep(doorT), 0, 0) * rel)
other: swing = smoothstep(legT / swingEnd); rise = lift ≠ 0 ? smoothstep((legT - from) / (1 - from)) : 0
       L = Translate(0, lift * rise, 0); turn = pivot * Angles(rad * swing, 0, 0)
       Leg:   write(L * turn * rel)
       Foot:  write(L * Translate(turn * (inverse * ankle) - ankle) * base)          // translates, stays level
       Link:  a = fixed; v = turn * (inverse * move) - a; u = unit(v); stretch = |v| - len
              write(L * Translate(u * stretch/2) * Translate(a) * between(dir, u) * Translate(-a) * base)
              resize(size + axis * stretch)
       Mount: write(L * base)
between(u0, u) = rotation about unit(u0 x u) by atan2(|u0 x u|, u0·u) (identity if |u0 x u| < 1e-6)
```

**Timing** (`ShipHull:stepGear`, `:571-614`), run inside `hull:update` each frame only while moving:

```text
dt = min(now - lastStep, 0.1) (0 on the first step)
instant = interior (cockpit) view and the ship has a cockpit      // seated you cannot see it; folded legs can poke into the floor
if gearDown (extend): instant → legT = doorT = 0
                      elif doorT > 0 && rig.doors: doorT = max(0, doorT - dt / GearDoorTime 0.6)    // doors open first
                      else: doorT = 0; legT = max(0, legT - dt / GearLegTime 1.6)                // then legs come down
else (retract):       instant → legT = doorT = 1
                      elif legT < 1: legT = min(1, legT + dt / 1.6)                               // legs fold first
                      elif rig.doors: doorT = min(1, doorT + dt / 0.6) else doorT = 1             // then doors close
stowed = !gearDown && legT >= 1 && doorT >= 1
visibility: gear parts hidden while stowed; GearClosed hull parts shown only while stowed
```

A reversed command mid-way simply runs back. Retract total 2.2 s, extend total 2.2 s (doors first). Gear command (`ShipClient.lua:2882-2884`): down when (landing scene and altitude < `Hull.GearDownAltitude 60`) or `docking:wantsGear(cf)`. The model starts with gear down; the first space frame retracts it. Voice GearDown/GearUp once the command is steady (callouts doc). Without joint data the gear is shown/hidden instantly.

### 3.9 ShipFlightArrow and ShipCursor (`ShipCore/ShipFlightArrow.lua`, `ShipClient/ShipCursor.lua`)

**Purpose.** Since 2026-11-05 the flight circle, cursor dot and guide line are gone ("so the screen is not covered"). At rest only a small dot sits at the screen centre; when steering, a small fixed-size triangle sits in the direction of the deflection, pointing outward, and moves further out with the deflection. In the dead zone it is matte dark red; outside it shows its own two-tone colours. While steering the dot gives its light to the arrow: past the dead zone it turns dark red and fades.

**ShipFlightArrow API.** `build(parent, unit, cfg, z)` (creates `Center` and `Arrow` in `parent`, centre of parent = screen centre), `attach(parent, cfg)` (controller for pre-built parts, used by the cockpit glass; reads attribute `Unit`), `setUnit(unit)`, `update(aim)`, `setVisible(b)`, `getOffset()` (tests). `unit` = pixels per 1080p design pixel.

```text
setUnit(unit):                                                                     :150-166
    dot = max(2, round(DotSize 4 * unit)) px square
    arrowImage = (even(ArrowWidth 17 * unit / (184/256), 6), even(ArrowLength 20 * unit / (207/256), 6))  // image is 256², triangle 184×207
    even(v, least) = max(least, 2 * round(v / 2))
update(aim = cursorPixels / radius, x right, y down):                             :208-261
    m = |aim|
    dotStep = (DotFadeRange 0.5 > 0 && m > Deadzone 0.2) ? round(min((m - 0.2) / 0.5, 1) * 10) : 0
    dot colour = lerp(DotColor (255,170,45), ArrowIdle (150,36,22), dotStep/10); dot transparency = DotDimTransparency 0.8 * dotStep/10
    if hidden or m < ArrowHide 0.04: hide arrow; return
    dir = aim / m; m = min(m, 1)
    distance = (ArrowInner 16 + (ArrowRange 112 - 16) * m) * unit
    arrow centre = screen centre + round(dir * distance)
    rotation = round(deg(atan2(dir.y, dir.x))) + 90              // the image points up; 0° = right
    m > Deadzone ? (tint white, ImageTransparency 0) : (tint ArrowIdleTint (150,46,94), ImageTransparency 0.3)
```

The image (AFloidDEV's upload "HudArrow_TwoTone", `rbxassetid://116615732542595`): 256×256, tip up, left half yellow #FFC93C (255,201,60), right half orange #F07F1A (240,127,26), the seam down the middle; the triangle fills 184×207 px around the centre. The idle tint multiplies the yellow exactly to ArrowIdle; the orange becomes a touch darker. Port: draw it as two triangles (left half, right half), no texture needed.

Examples (unit 1): aim (0.5, 0) → arrow at (+64, 0), rotation 90°, own colours, dot step 6 = (192, 90, 31) at 48 % transparency; aim (0, −0.1) → arrow at (0, −26), rotation 0°, idle tint, dot bright; aim 0.03 → only the dot.

**ShipCursor** (`ShipClient/ShipCursor.lua`) builds a ScreenGui `ShipCursorGui` (DisplayOrder 50, ignores the top-bar inset), a full-screen `Root`, the arrow with `unit = shortSide / 1080` (updated when the viewport changes, `DESIGN_HEIGHT = 1080`), and preloads the image. It also builds a throttle track and a debug text that are **hidden in the shipped game** (`Hud.ShowThrottleBar = false`, `Hud.ShowDebugText = false`; ShipHud took both over). For completeness (useful for the touch throttle lever, section 9.1):

| Element | Layout and style | Behaviour |
|---|---|---|
| `ThrottleTrack` | anchor (0.5, 0.5), position (0.5, −radius − 34, 0.5, 0), size (8, floor(radius · 1.4)) px, bg (20,30,35) at 40 % transparency, stroke 1 px (120,190,220) at 50 % | |
| `ZeroMark` | size (1, +8 px) × 1 px, (120,190,220) at 30 % | at `zeroFraction = 0.25/1.25 = 0.2` from the bottom |
| `ThrottleFill` | anchor (0.5, 1), (255,170,60) at 25 % | from the zero line up; reverse: anchor (0.5, 0), (200,90,60) |
| `SpeedMark` | (1, +12 px) × 2 px, (255,235,190), z 2 | at `0.2 + clamp(speed / 1000, 0, 1) · 0.8` |
| `LimitMark` | (1, +16 px) × 2 px, (90,170,255), z 3 | only when the raw limit < 1000 m/s, at the same scale |
| `FlightInfo` | anchor (0, 1), position (0, 24, 1, −24), size 400×130, Roboto Mono Medium 15 px, (210,230,240), left/bottom aligned, RichText | `THROTTLE %3d%%` / `SPEED    <speed>` / `ACCEL    <a> m/s²` / `F-ASSIST AN` or `AUS` (German on/off; port as ON/OFF; green (150,220,150) / orange (255,150,90)), 10 Hz |

Visibility: `setCockpit(true)` hides the flat arrow and dot when the cockpit has a projection glass; `setVisible(false)` disables the whole GUI while looking around, docked or walking aboard (`ShipClient.lua:2797-2802`); `setExternal` keeps the arrow in the external view.

**Cockpit projection** (`ShipInterior.lua:483-494, 1287-1300`; geometry decoded from `Assets.rbxmx`, VARROW): `Cockpit.FlightHud.Glass` is an invisible part 3.2 studs in front of the eye (= `Attitude.GlassDistance`), 1.431 × 1.431 studs, with a SurfaceGui `Display` at 260 px/stud (372 px canvas), light influence 0; `Main` holds `Center` (5×5 px, (255,170,45)) and `Arrow` (30×32 px), `Main.Unit = 1.2928`. That unit makes the glass arrow exactly as large on screen as the flat one at FOV 80° (1 design px ≈ 1 screen px at 1080p). Shown when the cockpit HUD is on, not in external view and not docked/on foot; driven by the same `update(aim)`. Because it lives on the glass, it moves with the cockpit when the head sways.

### 3.10 ShipAttitude (`ShipClient/ShipAttitude.lua`)

**Purpose.** Attitude display in the cockpit (AFloidDEV 2026-11-06: "help lines for ascend and descend ... professional and high quality"), designed after aircraft HUDs: pitch ladder (solid lines above the horizon, dashed below, end hooks pointing to the horizon, numbers every 10°), horizon, flight-path marker, PITCH on the left, climb/sink rate and ALT on the right.

The GUI is pre-built in Studio (`StarterGui.AttitudeHud`, from `AttitudeHudInstaller` in `ServerStorage.Dev.UI`); the module only clones the bar template into a pool and sets positions, sizes, rotations, texts and transparencies. **That GUI is not in the export** (`scripts.rar` holds only scripts, `Assets.rbxmx` only the Assets folder), so line thickness, tick length, label width, fonts and colours of the template are unknown; section 6.4 proposes values.

**Glass-true projection** (`draw`, `:412-581`): every line sits where that pitch angle lies outside. A direction is turned into a point on a glass `GlassDistance 3.2` studs in front of the eye and that point is projected through the camera. Calm and straight, the camera sits exactly in the eye and the horizon line covers the real horizon; when the head gives way to G or the pilot looks around, the ladder moves with the glass and stays around the centre dot, like everything in the cockpit.

```text
alpha:  wanted = frame.shown && Attitude.Enabled && frame.up != nil
        alpha = clamp(alpha ± dt / FadeTime 0.45, 0, 1); up == nil → alpha = 0; alpha == 0 → GUI off   :587-609
a      = smoothstep(alpha)
focal  = viewport.y / 2 / tan(FOV_vertical / 2);  centre = viewport / 2
uiScale= max(ShipUi.getScale(), 0.01) = clamp(viewport.y / 1080, 0.75, 2.2)
eye    = shipCF * Translate(Camera.SeatOffset (0, 0.5, 0)); nose = eye.look; right = eye.right
origin = frame.external ? camera.pos : eye.pos                 // outside there is no glass: plain directions
project(dir):  along = dir · nose; if along < 0.05 → none
               p = inverse(camera) * (origin + dir * (3.2 / along)); if p.z > -0.01 → none
               k = focal / -p.z; return (cx + p.x k, cy - p.y k)
up     = frame.up; sinP = clamp(nose · up, -1, 1); pitch = deg(asin(sinP))
level  = nose - up * sinP
if |level| < 1e-3: roof = eye.up * (sinP > 0 ? -1 : 1); level = roof - up * (roof · up)    // vertical: ahead = where the roof pointed
if |level| < 1e-6: return
heading = unit(level); across = heading x up (= right in the local level frame); tanGap = tan(Gap 3°)

ladder:  first = ceil((pitch - Window 13) / Step 5) * 5; last = floor((pitch + 13) / 5) * 5
         for angle in first..last step 5, skipping 0 and |angle| >= 90:
             amount = clamp((13 - |angle - pitch|) / Fade 4, 0, 1) * a; skip if <= 0.01
             slot = floorMod(floor(angle / 5 + 0.5), POOL 8)        // a line keeps its frame while pitching
             major = angle % LabelEvery 10 == 0
             drawBar(angle, major, amount, tanSpan = tan(major ? Span 7° : SpanMinor 5.2°))
drawBar: c = heading cos(angle) + up sin(angle)
         lo = P(c - across tanSpan), li = P(c - across tanGap), ri = P(c + across tanGap), ro = P(c + across tanSpan); any none → skip
         u = unit(ro - lo); n = (u.y, -u.x)                               // normal towards higher pitch (screen up when level)
         each half (outer → inner): angle >= 0 solid; angle < 0 dashed = outer → outer + 0.4 d and inner - 0.4 d → inner (DashFraction 0.4)
         hooks at both outer ends: length tickLength * uiScale, direction n * (angle > 0 ? -1 : 1)   // towards the horizon
         major: labels "%d" of angle (e.g. "10", "-10") at lo - u*shift and ro + u*shift, shift = (LabelGap 5 + labelWidth/2) * uiScale,
                rotated with the line, normalised to -90..90° so they are never upside down
         fade all parts by amount
horizon: amount = clamp((13 - |pitch|) / 4, 0, 1) * a; span tan(HorizonSpan 11°); two solid segments lo→li and ri→ro; no hooks or numbers
zenith / nadir: amount = clamp((13 - distance) / 4, 0, 1) * a with distance 90 - pitch (zenith, at P(up)) and pitch + 90 (nadir, at P(-up))
path marker: if ShowPath && |v| > PathMinSpeed 3: dir = unit(v); off = deg(acos(dir · nose))
             if off < PathMaxAngle 25 and P(dir) exists: place it; fade a * clamp((|v| - 3) / 3, 0, 1) * clamp((25 - off) / 3, 0, 1)
readouts (fixed on the glass): PitchReadout at P(nose - right tan 13.5°) (its RIGHT edge), RateScale at P(nose + right tan 14°)
             (its centre), AltReadout at P(nose + right tan 15.5°) (its LEFT edge), each faded by a
rate:    raw = v · up * frame.rateScale; rate += (raw - rate) * (1 - exp(-dt / max(RateSmoothing 0.15, 0.01)))
         fraction = sign(rate) * min(log10(1 + |rate| / RateKnee 5) / log10(1 + RateFull 2000 / 5), 1)
         Fill: anchor (0.5, 1) when fraction >= 0 else (0.5, 0), height |fraction| * half, position stays at the scale centre
         Caret at (caretX, 0.5 - fraction * half); marks Mark1..6 at ±fraction(10), ±fraction(100), ±fraction(1000) = ±0.183, ±0.508, ±0.885
danger:  frame.danger && rate < 0 → Fill, Caret and rate text in Ui.DangerColor (255,70,60), else the template colours
texts (every TEXT_INTERVAL 0.1 s): pitch = floor(pitch * 10 + 0.5) / 10 → "0.0°" if 0 else "%+.1f°" ("+12.3°", "-0.1°")
         ALT: nil/NaN/>= 1e9 → "--", else ShipUnits.upper(ShipUnits.distance(alt)) ("316 M", "3.17 KM", "6.84 Mm")
         rate: |rate| < 0.5 → "0 M/S", else sign + speedParts ("+120 M/S", "-1.24 KM/S", "+3.00 Mm/s")
```

Visible ladder examples: pitch 0 → −10 (dashed, "-10"), −5, 5, 10 ("10") with 10/−10 at 75 % and the horizon full; pitch 12 → 5, 10, 15, 20 and the horizon at 25 %; pitch 47.3 → 35 (17.5 %), 40–55, 60 (7.5 %); pitch 85 → 75 (75 %), 80, 85 and the zenith mark.

**Frame contents** (`ShipClient.lua:3130-3151`): `shown = localUp && !onFoot && !travelling && !mapOpen && !docked && !warpActive && (landingFlow || (ShowOnApproach && inInfluence)) && (!external || ShowExternal) && (!interiorActive || interior:isHudOn())` with `landingFlow = landing scene active or atmosphere state ≠ SPACE`; `camera, shipCFrame, up = localUp, velocity, external, altitude` (scene altitude over ground, else the atmosphere's shown altitude), `rateScale = shownSpeed / speed` (scene number blending), `danger = timeToImpact < Warnings.Phase1Time 10`. `hideNow()` on decks and ship loss. `getStatus()` returns ready, enabled, alpha, pitch, rate, visible bar angles, horizon/path visibility, texts, danger, scale (ShipDebug "attitude").

---

## 4. Tunables

### 4.1 Rotation and cursor input

| Key | Value | Meaning |
|---|---|---|
| `Rotation.InputCurve` | 1.7 | power applied after the dead zone; > 1 = finer in the middle |
| `Cursor.Deadzone` | 0.20 | radial dead zone, fraction of the cursor radius (also the arrow's idle/colour switch) |
| `Cursor.RadiusScale` / `MinRadius` / `MaxRadius` | 0.20 / 130 / 300 px | cursor radius from the short screen side |
| `Cursor.Sensitivity` | 1.0 | cursor px per mouse px (times the MOUSE SENSITIVITY setting) |
| `Cursor.DecayTime` / `DecaySnap` | 0 / 0.03 | self-centring (off; do not enable) |
| `Cursor.MouseXAxis` | "ROLL" | default of the MOUSE X AXIS setting |
| `Interior.LookYaw` / `LookUp` / `LookDown` | 140 / 80 / 70° | look-around limits in the cockpit |
| `Interior.ExternalLookYaw/Up/Down` | 180 / 60 / 60° | in the external view |
| `Interior.LookSensitivity` | 0.25 °/px | look speed (times mouse sensitivity) |
| `Interior.LookReturnTime` | 0.12 s | return to forward on release |
| Settings `MouseSensitivity` | slider 0.25–2, step 0.05, default 1 | `ShipCore/ShipSettings.lua:23` |
| Settings `InvertPitch` | toggle, default false | |
| Settings `MouseYaw` ("MOUSE X AXIS") | toggle ROLL/YAW, default ROLL | |
| Settings `FieldOfView` | 60–100, default 80 | camera; used by the attitude projection |

### 4.2 Thrust (`ShipConfig.lua:27-71`)

| Key | Value | Meaning |
|---|---|---|
| `MaxSpeed` | 1000 m/s | top of the near-body cruise; base of the throttle map; brake and limit-tracking thresholds; HUD/audio reference |
| `CruiseMaxSpeed` | 3,000,000 m/s | safety cap (1 % of c); in Sol trips peak at ~90 km/s (Earth–Venus) to ~1 Mm/s (Uranus–Neptune) — **×S question, 9.4** |
| `CruiseDistanceTime` | 3.5 s | gap e-folding time flying towards a body/station/edge |
| `CruiseDepartTime` | 1.5 s | same, flying away |
| `ThrottleRampTime` | 4 s | W/S from 0 to 100 % |
| `MinThrottle` | −0.25 | full reverse lever |
| `FullStopTime` | 1.2 s | X: lever back to 0 |
| `FlightAssistDefault` | true | |
| `VelocityCutoff` | 0.05 m/s | FA zeroes slower residual speed |

### 4.3 Ships (`ShipConfig.lua:87-370`)

Masses in t, speeds in m/s, accelerations in m/s², turn rates in °/s, turn accelerations in °/s².

| | VARROW | BRANNOCK | MORVEN | KESSOCK | LOCHRAN | CALDER | TORRIN | DORNOCH |
|---|---|---|---|---|---|---|---|---|
| HullMass | 260 | 540 | 1150 | 640 | 236 | 248 | 190 | 760 |
| ModuleMass | 78 | 156 | 360 | 210 | 72 | 75 | 57 | 230 |
| FuelMass (full) | 32 | 64 | 128 | 80 | 48 | 40 | 28 | 96 |
| CargoCapacity | 16 | 64 | 224 | 96 | 8 | 12 | 4 | 64 |
| DesignMass | 370 | 760 | 1640 | 930 | 356 | 363 | 275 | 1086 |
| MaxMass | 480 | 1000 | 2100 | 1210 | 440 | 460 | 344 | 1410 |
| TopSpeed | 260 | 205 | 170 | 190 | 280 | 270 | 300 | 225 |
| BoostSpeed | 340 | 285 | 230 | 255 | 365 | 352 | 400 | 295 |
| ReverseSpeed | 130 | 100 | 80 | 90 | 130 | 130 | 140 | 100 |
| StrafeSpeed | 130 | 90 | 60 | 80 | 120 | 125 | 130 | 85 |
| Accel Forward | 35 | 24 | 16 | 20 | 33 | 34 | 38 | 22 |
| Accel Reverse | 25 | 17 | 11 | 15 | 23 | 24 | 27 | 16 |
| Accel Lateral | 20 | 12 | 7 | 13 | 18 | 19 | 21 | 12 |
| Accel Vertical | 22 | 14 | 9 | 16 | 21 | 22 | 24 | 14 |
| TurnRate Pitch | 38 | 24 | 15 | 20 | 34 | 36 | 40 | 22 |
| TurnRate Yaw | 20 | 13 | 9 | 12 | 18 | 19 | 21 | 13 |
| TurnRate Roll | 100 | 60 | 38 | 50 | 90 | 95 | 110 | 52 |
| TurnAccel Pitch | 120 | 70 | 42 | 56 | 104 | 112 | 128 | 62 |
| TurnAccel Yaw | 60 | 36 | 22 | 30 | 52 | 56 | 63 | 33 |
| TurnAccel Roll | 170 | 100 | 64 | 84 | 150 | 160 | 192 | 88 |
| Boost Duration (s) | 2.0 | 2.4 | 2.8 | 2.6 | 2.2 | 2.1 | 1.9 | 2.5 |
| Boost Cooldown (s) | 4.0 | 5.5 | 7.0 | 6.0 | 4.5 | 4.2 | 3.8 | 6.0 |
| Boost AccelerationMultiplier | 2.2 | 2.0 | 1.8 | 1.9 | 2.1 | 2.15 | 2.3 | 1.95 |
| Boost Heat | 0.06 | 0.08 | 0.10 | 0.09 | 0.07 | 0.065 | 0.07 | 0.085 |
| ExternalCamera Offset, Pitch | default (0,16,95), −8 | (0,24,140), −8 | (0,36,165), −9 | (0,26,135), −9 | (0,15,80), −8 | default | (0,13,72), −8 | (0,24,140), −8 |
| Perks | – | – | – | MineSpeed 2 | SurveyValue 1.5, ScanRange 1.5, FuelBurn 0.6, ScoopRate 1.5 | – | FuelBurn 0.85 | MineSpeed 1.4, ScanRange 1.25 |

Comments (role and price from the config comments; prices are authoritative in the economy data): VARROW starter; BRANNOCK medium freighter 78,000 CR, 4× cargo, heavy; MORVEN large freighter 380,000 CR, two walkable decks, rear ramp; KESSOCK prospector 135,000 CR, one deck, ore hold, mines twice as fast, calm in altitude; LOCHRAN surveyor 56,000 CR, faster than VARROW, better survey, frugal drive, bigger scoops; CALDER multi-purpose between VARROW and LOCHRAN; TORRIN light courier 26,000 CR, fastest, best roll; DORNOCH heavy multi-purpose 140,000 CR, eight thrusters. Yaw is deliberately faster than the reference sim (13 → 20 on VARROW) because it sits on Q/E and aligning must not be a test of patience. `perk(ship, name)` returns the perk or 1 (`:378-383`). `getProfile()` falls back to VARROW.

### 4.4 Handling (`ShipConfig.lua:388-437`)

| Key | Value | Meaning |
|---|---|---|
| `MassExponent.Acceleration` | 0.75 | factor (mass/DesignMass)^−e; cargo must be felt (pure F = m·a would be 1.0) |
| `MassExponent.TopSpeed` | 0.15 | also boost, reverse and strafe speeds |
| `MassExponent.TurnRate` | 0.2 | |
| `MassExponent.TurnAcceleration` | 0.75 | |
| `MinMassRatio` | 0.5 | floor of the mass ratio |
| `BlueZoneCenter` / `Low` / `High` | 0.5 / 0.65 / 0.7 | best agility at half TopSpeed, 65 % standing, 70 % at TopSpeed |
| `CruiseTimeUp` | 0.8 s | cruise speed-up time constant (was 3.5) |
| `CruiseDoubleTime` | 0.8 s | at most one doubling per this time (readout runs evenly through m/s, km/s, Mm/s) |
| `CruiseTimeDown` | 1.8 s | cruise slow-down time constant; also the decay out of cruise |
| `CruiseAlign` | 0.25 s | flight direction follows the nose in cruise |
| `CruiseTurnFloor` | 0.6 | agility floor factor in cruise (× BlueZoneHigh → 0.42) |
| `CruiseTurnExponent` | 0.6 | agility ∝ (speed/top)^−0.6 above TopSpeed |
| `FaOffTorque` | 0.7 | FA off: share of the turn acceleration used as torque |
| `FaOffRateBonus` | 1.1 | FA off: rate cap × 1.1 |
| `AssistAuthority` | 2.0 | FA drift correction uses 2× side/vertical thrust (at 1.0 VARROW slid 4 s after a hard turn) |
| `FeltStudsPerG` | 9.81 | felt G for display and blackout |
| `CruiseFeltFactor` | 0.1 | cruise damps the felt acceleration |
| `CruiseFeltMaxG` | 1.5 | at most this in cruise (else 255 g on the way to 200 km/s) |

### 4.5 Gravity (`ShipConfig.lua:448-534`)

| Key | Value | Meaning | Scale class (9.4) |
|---|---|---|---|
| `Tag` | "CelestialBody" | body tag | – |
| `MassShadowFactor` | 5 | shadow = R × 5 (moons 4, star 4 via attributes) | ratio |
| `MassShadowCurve` | 0.75 | shadow law exponent | ratio |
| `MassShadowFloor` | 150 m/s | slowest speed from the shadow alone (also ≥ 0.15·R) | far speed ×S |
| `StarShadowFromHeat` | 1.0 | star shadow = HeatRadius × this | ratio |
| `StarShadowMinSpeed` | 1000 m/s | star shadow floor | far speed ×S |
| `DefaultInfluenceFactor` | 2.2 | approach zone = R × 2.2 | ratio |
| `DefaultMinApproachSpeed` | 10 m/s | floor (bodies set 14/12/40) | absolute |
| `DefaultSpeedCurve` | 0.75 | unused | – |
| `DefaultSurfaceGravity` | 196.2 | free-fall display only | – |
| `LimitMultiplier` | 1.0 | one knob for the whole brake strength (2 = twice the speed allowed) | ratio |
| `BrakeAcceleration` | 80 m/s² | gravity brake below MaxSpeed ("halved, should feel like braking, not a wall") | absolute |
| `CruiseBrakeTime` | 0.4 s | extra exponential brake above MaxSpeed; must stay well below CruiseDistanceTime/4 | time |
| `LimitFallTime` | 1.6 s | soft tracking of a falling limit below MaxSpeed | time |
| `ApproachBrake` | 70 m/s² | approach-zone braking law; below BrakeAcceleration so the brake keeps up | far ×S; near absolute (9.5) |
| `SafeLandingSpeed` | 20 m/s | crash threshold at the old surface spheres (scene uses `Surface.SafeLandingSpeed 38`) | absolute |
| `MinManoeuvreSpeed` | 12 m/s | always allowed, to get off a surface | absolute |
| `SurfaceGravityRange` | 3.5 | gravity pulls only below 3.5 R altitude | ratio |
| `GravityFalloff` | 2.0 | inverse square | – |
| `FlightAssistGravityCompensation` | 0.78 | FA cancels 78 %; FA off feels all of it | – |
| `HeatRange` | 0.6 | heat zone = R × 0.6 when a star has no HeatRadius | ratio |

### 4.6 Physics (`ShipConfig.lua:2433-2458`)

| Key | Value | Meaning |
|---|---|---|
| `StudsPerG` | 60 | gravity pull per g in m/s². Historically the G-meter unit; the comment says "deliberately lower than real gravity (Roblox's 196.2 studs/s²), otherwise these accelerations never reach noticeable values". The G display now uses `Handling.FeltStudsPerG = 9.81`; only the planet pull still uses 60 |
| `BlackoutStartG` | 6.5 | weighted strain where darkening starts (pilot in a pressure suit) |
| `BlackoutFullG` | 11 | full darkening |
| `BlackoutMaxDarkness` | 0.82 | maximum overlay opacity |
| `BlackoutResponse` | 0.35 s | overlay smoothing |
| `GTolerance.Forward` | 0.35 | +Gx (thrust into the seat): ~3× more tolerable |
| `GTolerance.Backward` | 0.5 | −Gx (braking into the belts) |
| `GTolerance.Lateral` | 0.5 | Gy |
| `GTolerance.Up` | 1.0 | +Gz pulling up: blackout |
| `GTolerance.Down` | 1.5 | −Gz pushing: redout |
| `RedoutColor` | (110, 0, 0) | overlay tint when pushing |

### 4.7 Autopilot (`ShipConfig.lua:2374-2394`)

| Key | Value | Meaning |
|---|---|---|
| `AlignmentForThrust` | 0.965 | cos of ~15°: thrust only when this well aligned |
| `TurnGain` | 4.0 | cursor per radian of error |
| `RollToPitchAngle` | 20° | above this error, roll the target into the pitch plane (pitching is ~2× faster than yawing; turning only by yaw took 14 s) |
| `RollGain` | 2.0 | full roll per 0.5 rad of bank |
| `MaxCursor` | 0.95 | |
| `BrakeMargin` | 1.35 | braking distance safety factor |
| `ArriveDistance` | 0.35 | × target radius above the surface |
| `ArriveSpeed` | 25 m/s | |
| `StopAltitude` | 0.2 | × radius: stop above the barrier (Earth ≈ 470 m); landing stays the pilot's job |
| `DisengageOnInput` | true | |
| `OverrideDeflection` | 0.5 | stick share that counts as manual input (also hands control back from the docking computer) |

### 4.8 Systems (`ShipConfig.lua:2399-2411`) and heat thresholds

| Key | Value | Meaning |
|---|---|---|
| `FuelCapacity` | 100 | units (fraction = fuel/100) |
| `FuelPerSecondAtFullThrottle` | 0.22 | × \|throttle\| × FuelBurn perk |
| `FuelScoopRate` | 3.5 /s | × ScoopRate perk, while starHeat > 0.25 |
| `FuelScoopHeat` | 0.075 | × starHeat, extra heat while scooping |
| `HeatFromStar` | 0.115 /s | × starHeat ("about 12 s to the warning") |
| `HeatFromThrust` | 0.018 /s | × \|throttle\| |
| `HeatCooling` | 0.075 | loss = 0.075 · (0.4 + heat) per s |
| `HeatMax` | 2.2 | clamp |
| `Warnings.Overheat.Phase1Heat` / `Phase2Heat` / `FatalHeat` / `Hysteresis` | 0.80 / 1.00 / 2.00 / 0.88 | warnings and heat death (`ShipConfig.lua:571-579`) |
| `Warnings.Phase1Time` | 10 s | time to impact for the attitude danger colour (`:545`) |

### 4.9 Cursor and flight arrow (`ShipConfig.lua:1605-1671`)

| Key | Value | Meaning |
|---|---|---|
| `DotSize` | 4 px @1080 | centre dot |
| `ArrowLength` / `ArrowWidth` | 20 / 17 px | triangle tip-to-base / base |
| `ArrowInner` / `ArrowRange` | 16 / 112 px | arrow centre distance at zero / full deflection |
| `ArrowHide` | 0.04 | below this deflection only the dot shows |
| `DotColor` | (255,170,45) | |
| `ArrowImage` | `rbxassetid://116615732542595` | HudArrow_TwoTone (the white tintable variant would be `rbxassetid://86259018087330`) |
| `ArrowIdle` | (150,36,22) | matte dark red: dead zone, dot while steering |
| `ArrowIdleTint` | (150,46,94) | image multiplier in the dead zone |
| `ArrowIdleTransparency` | 0.3 | |
| `DotFadeRange` | 0.5 | dot fully dimmed at Deadzone + 0.5 |
| `DotDimTransparency` | 0.8 | |
| `RingColor` / `CursorColor` / `CursorColorActive` | (120,190,220) / (255,170,60) / (255,235,190) | throttle-track colours |

### 4.10 Attitude (`ShipConfig.lua:1688-1727`)

| Key | Value | Meaning |
|---|---|---|
| `Enabled` / `ShowExternal` / `ShowOnApproach` | true / false / false | |
| `FadeTime` | 0.45 s | fade in/out |
| `GlassDistance` | 3.2 studs | projection glass in front of the eye |
| `Step` / `LabelEvery` | 5° / 10° | ladder spacing, numbered lines |
| `Window` / `Fade` | 13° / 4° | visible ± around the nose, outermost degrees fade |
| `Gap` | 3° | half gap in the middle (room for dot and arrow) |
| `Span` / `SpanMinor` / `HorizonSpan` | 7° / 5.2° / 11° | half widths |
| `DashFraction` | 0.4 | below the horizon: two dashes per half, each 40 % long |
| `LabelGap` | 5 px @1080 | line end to number |
| `ShowPath` / `PathMinSpeed` / `PathMaxAngle` | true / 3 m/s / 25° | flight-path marker |
| `ReadoutAngle` / `RateAngle` / `AltAngle` | 13.5° / 14° / 15.5° | PITCH right edge / rate scale centre / ALT left edge, beside the nose |
| `RateKnee` / `RateFull` | 5 / 2000 m/s | log scale, nearly linear up to the knee, half scale full at 2000 |
| `RateMarks` | {10, 100, 1000} | marks on both sides |
| `RateSmoothing` | 0.15 s | |

### 4.11 Other configuration read here

| Key | Value | Where |
|---|---|---|
| `Keys` | W/S throttle, A/D strafe, R/F up/down (alt Space/Left Ctrl), Q/E yaw, X full stop (hold), Z FA, B boost, C centre cursor, Left Alt release mouse, P autopilot, T/Y cycle/clear target, L land, K dock, V external view | `ShipConfig.lua:2763-2848` |
| `Hull.GearDownAltitude` / `GearLegTime` / `GearDoorTime` | 60 m / 1.6 s / 0.6 s | `:2619-2623` |
| `Hull.Model` | "Varrow" (active ship id) | `:2617` |
| `Camera.FieldOfView` / `SeatOffset` | 80° / (0, 0.5, 0) | `:2652-2655` |
| `Ship.StartPosition` / `StartLookDirection` | (0, 5000, −900000) / (0, 0, −1) | `:2669-2670` |
| `Ship.FieldRadius` / `EdgeSpeed` / `EdgeMargin` | 9,000,000 (×S) / 150 / 2000 (absolute) | `:2674-2691` |
| `Stations.DockingRange` / `MassLockSpeed` / `ZoneRadius` / `ZoneSpeed` / `HangarSpeed` | 6000 / 260 / 2200 / 140 / 60 | `:2925-2930` |
| `Surface.MaxSpeed` / `MaxSpeedHigh` / `SpeedPerAltitude` | 260 / 4000 / 0.35 | landing-scene law `clamp(0.35·h, 260, 4000)` (`:744-750`, `ShipSurface.lua:1048-1051`) |
| `Surface.GravityScale` | 0.35 | landing-scene gravity damping (`:757`) |
| `Surface.SafeLandingSpeed` | 38 | scene crash threshold (`:760`) |
| `Terrain.GroundClearance` / `GroundProbe` | 12 / 400 | ground hold (`groundClearance() = max(12, probe.ground + 0.3)`) |
| `Ui.ReferenceHeight` / `MinScale` / `MaxScale` | 1080 / 0.75 / 2.2 | `ShipUi` scale |
| `Ui.DangerColor` | (255,70,60) | |
| `Ui.Fonts.Mono` | Roboto Mono Medium | cursor info text |
| `Hud.TextUpdateInterval` | 0.1 s | |

### 4.12 Constants inside the modules

| Constant | Value | Where |
|---|---|---|
| near-surface time | 2.0 s (`near = h / 2`) | `ShipGravity.lua:345`, `approachReach` `:177` |
| cosine thresholds | > 1 m/s for a direction; > 0.05 to apply approachReach; ±0.25 for APPROACH/DEPARTURE | `ShipGravity.lua:329, 348, 201-205` |
| closing threshold | 0.05 m/s for time to impact | `:417` |
| cruise trigger | 1.02 × top (speed), 0.98 × top (forward speed) | `ShipThrust.lua:280` |
| out-of-cruise decay | above 1.02 × top: exponential (CruiseTimeDown) | `:381-382` |
| throttleFor iterations | 30 | `:97` |
| band clamp | 0.05..0.95 | `:70, 92` |
| autopilot override | 1.05 × desired → BRAKING | `ShipAutopilot.lua:176` |
| G smoothing | 0.12 s (G, strain), 0.25 s (redout) | `ShipSystems.lua:101-103` |
| scoop threshold | starHeat > 0.25 | `:69` |
| base cooling offset | 0.4 | `:78` |
| `SWING_SHARE` | 0.7 | `ShipGear.lua:40` |
| gear dt clamp | 0.1 s | `ShipHull.lua:581` |
| `DESIGN_HEIGHT` | 1080 | `ShipCursor.lua:33` |
| triangle share of image | 184/256 × 207/256 | `ShipFlightArrow.lua:54-55` |
| `DOT_STEPS` | 10 | `:57` |
| attitude `POOL` / `TEXT_INTERVAL` / `FADE_STEPS` | 8 / 0.1 s / 64 | `ShipAttitude.lua:55-59` |
| path marker fade | full 3 m/s above PathMinSpeed; 3° inside PathMaxAngle | `:533-534` |
| barrier notice | toast "MINIMUM ALTITUDE / HOLDING ABOVE <BODY>" (3 s), pause 4 s | `ShipClient.lua:1277, 2692-2697` |
| edge notice | every 30 s | `:2522` |
| frame dt clamp | 0.1 s | `:2324` |

### 4.13 Derived numbers per ship (at agility 1)

| Ship | Tank | Mass t | Ratio | Top | Boost | Accel F / R / L / V | Turn P / Y / R °/s | Turn accel P / Y / R °/s² |
|---|---|---|---|---|---|---|---|---|
| VARROW | full | 370 | 1.00000 | 260.00 | 340.00 | 35 / 25 / 20 / 22 | 38 / 20 / 100 | 120 / 60 / 170 |
| VARROW | empty | 338 | 0.91351 | 263.55 | 344.64 | 37.46 / 26.76 / 21.40 / 23.54 | 38.69 / 20.37 / 101.83 | 128.42 / 64.21 / 181.93 |
| BRANNOCK | full | 760 | 1.00000 | 205.00 | 285.00 | 24 / 17 / 12 / 14 | 24 / 13 / 60 | 70 / 36 / 100 |
| BRANNOCK | empty | 696 | 0.91579 | 207.72 | 288.79 | 25.64 / 18.16 / 12.82 / 14.96 | 24.43 / 13.23 / 61.07 | 74.77 / 38.46 / 106.82 |
| MORVEN | full | 1638 | 0.99878 | 170.03 | 230.04 | 16.02 / 11.01 / 7.01 / 9.01 | 15.00 / 9.00 / 38.01 | 42.04 / 22.02 / 64.06 |
| MORVEN | empty | 1510 | 0.92073 | 172.12 | 232.87 | 17.02 / 11.70 / 7.45 / 9.58 | 15.25 / 9.15 / 38.63 | 44.68 / 23.41 / 68.09 |
| KESSOCK | full | 930 | 1.00000 | 190.00 | 255.00 | 20 / 15 / 13 / 16 | 20 / 12 / 50 | 56 / 30 / 84 |
| KESSOCK | empty | 850 | 0.91398 | 192.58 | 258.46 | 21.40 / 16.05 / 13.91 / 17.12 | 20.36 / 12.22 / 50.91 | 59.91 / 32.09 / 89.86 |
| LOCHRAN | full | 356 | 1.00000 | 280.00 | 365.00 | 33 / 23 / 18 / 21 | 34 / 18 / 90 | 104 / 52 / 150 |
| LOCHRAN | empty | 308 | 0.86517 | 286.15 | 373.02 | 36.79 / 25.64 / 20.07 / 23.41 | 35.00 / 18.53 / 92.65 | 115.93 / 57.97 / 167.21 |
| CALDER | full | 363 | 1.00000 | 270.00 | 352.00 | 34 / 24 / 19 / 22 | 36 / 19 / 95 | 112 / 56 / 160 |
| CALDER | empty | 323 | 0.88981 | 274.77 | 358.22 | 37.11 / 26.20 / 20.74 / 24.01 | 36.85 / 19.45 / 97.24 | 122.25 / 61.13 / 174.64 |
| TORRIN | full | 275 | 1.00000 | 300.00 | 400.00 | 38 / 27 / 21 / 24 | 40 / 21 / 110 | 128 / 63 / 192 |
| TORRIN | empty | 247 | 0.89818 | 304.87 | 406.50 | 41.19 / 29.26 / 22.76 / 26.01 | 40.87 / 21.46 / 112.39 | 138.74 / 68.28 / 208.10 |
| DORNOCH | full | 1086 | 1.00000 | 225.00 | 295.00 | 22 / 16 / 12 / 14 | 22 / 13 / 52 | 62 / 33 / 88 |
| DORNOCH | empty | 990 | 0.91160 | 228.15 | 299.12 | 23.58 / 17.15 / 12.86 / 15.01 | 22.41 / 13.24 / 52.97 | 66.46 / 35.37 / 94.33 |

MORVEN is 2 t below its design mass at a full tank (1638 vs 1640): keep that quirk. Actual turn rates are these × agility (0.65 standing, 1.0 at half TopSpeed, 0.7 at TopSpeed, 0.42 in fast cruise).

---

## 5. State machines, timings, thresholds, edge cases

**5.1 Flight regime** (re-evaluated every frame in `ShipThrust:update`):

```text
BOOST     boostTimer > 0                           (entered on request if cooldown 0, not cruising last frame, not overloaded, input enabled)
CRUISE    FA && !boost && !fullStop && (speed > 1.02 top || (targetForward > top && forwardSpeed >= 0.98 top))
FA BAND   FA, otherwise
NEWTON    !FA (and never cruise)
```

Timings (VARROW, full tank, FA on, from rest): to TopSpeed 7.43 s; lever 0 → 100 % 4 s; full stop lever 1.2 s; reverse to −130 m/s 5.2 s; boost to 340 m/s from 260 m/s in 1.04 s, 2 s burn, next boost 4 s after ignition; after a boost the speed decays from 340 to 265 m/s with τ = 1.8 s, then linearly at 25 m/s². Open space, lever set to 100 % at t = 0 (not ramped), no bodies so the reference is the 3 Mm/s cap (re-implemented ShipThrust, dt 1/60): 7.43 s 260 m/s, 8 s 474 m/s, 9 s 1.13 km/s, 10 s 2.68 km/s, 11 s 6.38 km/s, 12 s 15.2 km/s, 13 s 36.1 km/s, 14 s 85.9 km/s, 15 s 204 km/s, 16 s 486 km/s, 17 s 1.16 Mm/s, 18 s 2.35 Mm/s, 20 s 2.95 Mm/s (×2.38 per second while doubling-limited). The config comment's sequence "24, 57, 137, 325, 722 km/s after 1–5 s" has the same ratio from a different start.

**5.2 Rotation.** FA on: VARROW pitch rate from 0 to its standing maximum 24.7 °/s (38 × 0.65) in 0.21 s. FA off: torque 84 °/s², cap 27.2 °/s standing. Rates are kept by `shiftBy` (origin shift, station carry) and zeroed by `setCFrame` (teleport, respawn, scene change).

**5.3 Speed limit.** Minimum over all bodies, then stations, then the field edge; the label comes from whichever constraint won. Above 1 km/s or rising: applied at once. Falling below 1 km/s: smoothed with 1.6 s, except in the approach zone ("surface", immediate). Brake: 80 m/s² plus an exponential 0.4 s term on the part above max(limit, 1000). Never below 12 m/s.

**5.4 Zone labels:** OPEN SPACE, APPROACH (cos ≥ 0.25), DEPARTURE (cos ≤ −0.25), GRAVITY WELL, HEAT ZONE, LOW ALTITUDE, STATION ZONE, SYSTEM EDGE, SURFACE.

**5.5 Autopilot:** STANDBY → (P with a target, in space, not travelling) → ENGAGED → each frame ALIGNING (alignment < 0.965) / CRUISING / BRAKING (speed > 1.05 × desired) → ARRIVED (within 0.35 R and ≤ 25 m/s) or TARGET LOST, MANUAL OVERRIDE, ATMOSPHERE, STANDBY (P again, controlled, docked, respawn).

**5.6 Gear:** EXTENDED (0,0) ↔ legs moving ↔ doors moving ↔ STOWED (1,1), reversible at any point; instant in the cockpit view.

**5.7 Collision-related holds that touch the flight state** (`ShipClient.lua:2594-2699`; collision itself belongs to the world/landing docs): heat ≥ 2.0 → crash "HEAT"; landing scene: ground ray, `impact = max(sinkRate, -v·n · 0.5)`, crash if > 38 m/s on solid or image ground, else `cancelVelocityAlong(-n)` and snap to `ground + clearance`; space below the barrier: remove only the inward velocity component (not all of it, or a planet moving towards the ship would freeze it forever) and snap to `R + 0.5`, toast "MINIMUM ALTITUDE". Stations: crash above 45 m/s, else slide (`cancelVelocityAlong(-n)`), hit sound above 4 m/s.

**5.8 Edge cases**

- `dt` clamped to 0.1 s; at dt 0 the acceleration outputs are 0.
- No velocity direction: cruise uses the nose; `getForwardAcceleration` returns |a| below 1e-4 m/s.
- Vertical nose in the attitude indicator: heading from the roof vector.
- `limit = ∞` only before the first `setSpeedLimit`; `getState` itself never returns more than 3e6.
- Boost: a refused request is dropped. Boost in Newton mode at zero lever: no thrust.
- Autopilot cursor straight behind: pull up. Targets below: pushed, not rolled over.
- Inside a station frame gravity is zero; heat and fuel still run.
- Walking aboard without autopilot: throttle 0 and FA on; the ship brakes and holds.
- After the atmosphere guidance hands over, the lever is set to the lever position that reproduces the current speed (`throttleFor`).
- Warp travel: input disabled, cursor centred at start and end, lever kept (reset on arrival elsewhere).
- Docking release: velocity from the docking computer, lever 0, cursor centred: the ship rolls out and stops in front of the planet.
- Ship switch: new handling, new tank level from the save, gear rig rebuilt.
- Lua floored modulo in the attitude slot and label tests; integer-valued float angles printed with `%d`.

---

## 6. On screen

### 6.1 Flat flight arrow and centre dot

- Root: full screen, the true screen centre (`IgnoreGuiInset`), not the safe-area centre.
- Dot: square `max(2, round(4·unit))` px, (255,170,45), bright at rest and in the dead zone, dimming in 10 steps to (150,36,22) at 80 % transparency.
- Arrow: two-tone isosceles triangle 17 × 20 design px (tip to base 20), left half (255,201,60), right half (240,127,26), tip pointing away from the centre; dead zone: both halves multiplied by (150,46,94) at 30 % transparency.
- Distance from centre 16–112 design px; `unit = shortSide / 1080`.
- Z order: dot below arrow; HUD DisplayOrder 50 (above the main HUD at 46).

```text
                     ▲  (aim up, outside the dead zone: yellow | orange)
                     
                     ·   ← centre dot (4 px, orange)
```

### 6.2 Cockpit projection

Same arrow and dot, drawn on the projection glass 3.2 m in front of the eye, sized so 1 design px ≈ 1 screen px at 1080 lines and FOV 80°. Port: draw it in screen space at the projection of `eye + nose · 3.2` and scale the design unit by `(focal / focal80)` so it keeps its angular size if FOV changes. Hidden while booting, in the external view, docked or on foot.

### 6.3 Throttle track (Roblox: built, hidden)

See the table in 3.9. Zero line at 20 % from the bottom, forward fill orange (255,170,60), reverse fill (200,90,60), speed marker (255,235,190) at speed/1000, blue limit marker (90,170,255) when the limit is below 1 km/s. Reused for the touch lever (9.1).

### 6.4 Attitude indicator

Positions are computed (section 3.10); sizes, colours and fonts come from the unexported `StarterGui.AttitudeHud`. Proposed values for the port until the GUI is exported (design px at 1080 lines, scaled by `uiScale`; colours from `ShipConfig.Ui`):

| Element | Proposal |
|---|---|
| Ladder lines (`LeftA/LeftB/RightA/RightB`) | 2 px, PanelColor (206,226,240) at 85 % opacity |
| End hooks (`LeftTick/RightTick`) | 8 px long, 2 px, same colour |
| Numbers (`LeftLabel/RightLabel`) | Roboto Mono Medium 14 px, box 28 px wide (labelHalf 14), same colour, text stroke black 60 % |
| Horizon (`Left/Right`) | 3 px, PanelColor at 100 % |
| Zenith / nadir | 16 px circle outline with a small star (zenith) / a cross (nadir), AccentColor (255,170,60) |
| Path marker | 14 px ring with three 7 px stubs (left, right, up), GoodColor (120,220,150) |
| PitchReadout (anchor 1, 0.5) | plate 84 × 30 px, PromptPlateColor (6,10,16) at 70 % opacity, 1 px stroke PanelColor at 65 %; caption "PITCH" Sarpanch SemiBold 11 px DimColor (120,145,165) above; value Roboto Mono 18 px PanelColor |
| RateScale (anchor 0.5, 0.5) | track 6 × 160 px (half 80) DimColor at 40 %; Fill 6 px wide AccentColor at 70 %; Caret 14 × 2 px PanelColor; Mark1–6 10 × 1 px DimColor |
| AltReadout (anchor 0, 0.5) | plate 104 × 30 px like PITCH, caption "ALT", value Roboto Mono 18 px; `Rate` text below it, Roboto Mono 13 px PanelColor (red when in danger) |

```text
                   10 ┐____________   ____________┌ 10
                    ___________         ___________
   ┌──────────┐                                           ┃      ┌────────────┐
   │ +2.5°    │ ═══════════════   ·  ▲    ═══════════════ ┃▬     │ 3.17 KM    │
   └──────────┘   PITCH                                   ┃      └────────────┘
                    _ _       _ _       _ _       _ _              -12 M/S
                  -10 ┘_ _   _ _          _ _   _ _└ -10
```

Text formats: section 3.10. Layout on 20:9 phones: the readouts sit 13.5–15.5° beside the nose. At a 60° vertical FOV (≈104° horizontal at 20:9) that is about ±0.22 screen heights from the centre (±235 px on a 2400×1080 screen), the ladder spans ±0.11 H and the horizon ±0.17 H, so everything stays in the middle third, clear of the throttle lever and the stick at the screen edges. Keep the angular placement and size the text in dp (section 9.1).

### 6.5 G overlay

Full-screen rectangle above the HUD, below warning texts: black blended to (110,0,0) by the redout share, opacity = smoothed darkness (max 0.82).

### 6.6 Units (from `ShipCore/ShipUnits.lua`)

- distance: `< 999.5` → "316 m"; `< 9,995` → "3.17 km"; `< 99,950` → "53.0 km"; `< 999,500` → "828 km"; `< 9,995,000` → "6.84 Mm"; `< 99,950,000` → "53.0 Mm"; else grouped "1,240 Mm".
- speedParts: `< 999.5` → ("260", "M/S"); `< 9,995` → ("1.24", "KM/S"); `< 99,950` → ("53.0", "KM/S"); `< 999,500` → ("250", "KM/S"); `< 9,995,000` → ("1.06", "Mm/s"); else ("%.1f", "Mm/s").
- `upper()` keeps "Mm" and "Mm/s" mixed-case (MM would read as millimetres). The Kotlin `formatSpeed`/`formatDistance` currently print "MM/S"/"MM": change.

---

## 7. Dependencies and data contracts

**Inputs this area needs**

| From | Data |
|---|---|
| Settings | mouse/stick sensitivity, invert pitch, X-axis ROLL/YAW, FOV |
| Ships import | active ship id → profile; per-part gear joint attributes (from `Assets.rbxmx`), `RootToGround`, cockpit presence |
| Economy / save | cargo mass (t), tank level on start and on ship switch, refuel at stations |
| World (`03-world.md`) | body list with centre, radius, minApproach, shadow factor/radius, heat radius, surface gravity, landing-blocked string, display name and class; body velocities (orbits) for the reference frame; universe position for the field edge |
| Stations (`08-stations-economy.md`) | `getLimit(p)`, `getApproachLimit(p, v)`, current station frame and carry; docking: `isControlling`, `wantsGear(cf)`, pose/velocity when it flies the ship, release handover |
| Atmosphere / landing doc | guided state (`isGuiding`, pose, velocity, acceleration, felt acceleration, release), atmosphere state (SPACE, ENTRY, SURFACE, EXIT), heat from entry, terrain height (for the near-ground law), "landing flow" flag for the attitude indicator |
| Target lock (markers) | locked target (position, radius) for the autopilot |
| Warp | travelling flag (input off, cursor centred) |
| Camera | pose, FOV, external flag, head offset (attitude glass, cockpit arrow) |

**Outputs**

| To | Data |
|---|---|
| HUD (`ShipHud`) | throttle, speed, maxSpeed 1000, speedLimit, speedZone, acceleration (forward), gForce, strainG, redout, mass, overloaded, agility, boosting, cruising, boostCooldown, pitchAngle, gravityG, surfaceGravityG, bodyName, landingBlocked, FA, fuelFraction, heat, altitude, scooping, autopilotEngaged, autopilotStatus |
| Cockpit (`ShipInterior`) | the same plus velocity, aim (stick shows the controlling axes; when the autopilot steers in legacy axes and the mouse rolls: `aim = (-keyAxis, aim.y)`, `roll = -aim.x`), cursor (pilot's), cursorShown, gearDown, massLocked (= inMassShadow incl. station lock), timeToImpact |
| Cockpit head sway (`ShipCockpit`) | felt acceleration in ship axes, angular velocity (deg/s), speed/1000 |
| Audio | throttle, speed, forward acceleration, inputAmount, rollAmount, topSpeed, velocity, boosting, cruising, FA; boost started; FA toggled |
| Callouts / voice | FA on/off, autopilot events, gear up/down, cruise on/off/dropped, boost ready, scooping, tank full, strain ≥ HighGShare × 6.5 (redout ≥ RedoutPush → "Redout" else "HighG") |
| Warnings | gravityState (timeToImpact, ship heat in `gravityState.heat`) |
| Hull renderer | pose; gear joint transforms and visibility; throttle and boost for engine glow |
| Save | position, look, fuel every 10 s (`ShipClient.lua:3185-3195`) |

---

## 8. Gap analysis against the Kotlin code

| Roblox | Kotlin today (file, function) | Difference | Action |
|---|---|---|---|
| Virtual cursor + key axis (`ShipInput`) | `input/TouchFlightControls.kt`: `updateStick` (radial dead zone 0.06, per-axis expo 0.25), `update` (premapped pitch/yaw/roll), throttle lever with rudder; `Game.readControls` (gamepad adds axes) | no aim vector + key axis; different dead zone/curve; axes premapped; no look-around | new `PilotInput` (aim, keyAxis, lever, lateral, full stop, edges); feed raw stick vector; dead zone/curve in rotation |
| `ShipHandling` | `game/Flight.kt` `ShipSpec` (fixed: 220 m/s, boost 400, accel 80/60/50, rates 1.15/0.55/2.45 rad/s, angular accel 3.6/2.0/8.5) | no mass, fuel, cargo, agility, per-ship profiles | port; 8 profiles |
| `ShipRotation` | `FlightModel.step` rotation part, `Quat.integrateLocal` | structure close (approach rates; FA-off clamp); numbers differ; FA-off torque 0.7 and cap 1.1 missing; Kotlin yaw/roll inputs are +right while Roblox commands are +left | port with Roblox signs |
| `ShipThrust` | `FlightModel.step` translation | lever −1..1 linear × dynamic limit; accelerations scaled with the limit (`max(accelForward, vmax·0.25)`); no cruise mode, doubling or align; boost = capacitor (cost 0.42, recharge) instead of cooldown; FA lateral 170 m/s² + speed term instead of 2× lateral; hard cap with bleed instead of cap decay + gravity brake; no gravity; no felt acceleration; no full stop; no reverse speed | rewrite |
| `ShipGravity` + composition | `game/StarSystem.kt` `locate`, `speedLimit`, `Zone` | law `clamp(clearance·k, top, 3e6)`, k 0.32–0.6; no mass shadow, approach zone, star heat zone, floors, immediate vs smoothed tracking; nearest body by altitude/radius ratio (Roblox: smallest altitude; limit = minimum over all bodies); station zone 8 km/clearance 4 km instead of 6 km mass lock 260 / 2.2 km 140 / hangar 60 + approach law; edge only a label | rewrite as `GravityField` + `SpeedLaw`; keep `sunlight` |
| `ShipSystems` | none (Game shows a "HEAT" text near the star) | missing fuel, heat, G, blackout | port |
| `ShipAutopilot` | none | missing | port (needs target lock) |
| `ShipGear` + hull stepping | `Game.updateGear` (one scalar over 2.2 s), `drawGear` (HUD bar "GEAR · LEGS/DOORS") | no rig; stand-in hull has no gear; the HUD bar does not exist in Roblox (cockpit gear lamp does) | port rig + animator; drop the bar |
| `ShipAttitude` | `Game.drawLandingHud` (10° rungs, ±32°, fixed PITCH/ALT boxes at ±150 dp, vertical speed text, green) | step, window, fade, dashes, hooks, labels, zenith/nadir, path marker, glass projection, log rate scale, 10 Hz text, colours all differ | rewrite (`ui/AttitudeHud.kt`) |
| `ShipCursor`/`ShipFlightArrow` | `Game.drawSteeringArrow` (line arrow 30–88 dp) + nose reticle and velocity marker in `drawHud` | Roblox shows only dot + two-tone triangle; no reticle, no velocity ring (the path marker exists only in the attitude indicator) | rewrite (`ui/FlightArrow.kt`); remove reticle and velocity marker |
| Throttle display | `TouchFlightControls.draw` gauge: REV 0.22 maps to −100 % reverse, speed marker at forward speed/top | Roblox: lever −25 %..100 %, zero at 20 %, speed marker at speed/1000, limit marker below 1 km/s | adjust (9.1) |
| Settings | `Settings.kt`: `stickXRoll` (≙ MouseYaw inverse), `pitchUpClimb` (≙ !InvertPitch), `sensitivity` 0.5–1.5 | FOV setting missing; camera shake missing | add |
| Collision response | `FlightModel.collideSphere/resolveContact`: bounce (restitution 0.35, 0.8 tangential, spin kick) | Roblox slides (`cancelVelocityAlong`) and crashes above thresholds | change with the collision/landing work |
| Landing (L) | `Game.doAction/landing/holdLanded/collideBodies`: landing mode, cap 200 m/s, vertical × 0.1, touchdown 8/40 m/s, level 0.8 | Roblox: guided entry + scene law + crash > 38 m/s | atmosphere/landing doc; this doc provides the limit law (9.5) |
| Units | `Game.formatSpeed/formatDistance`: "MM/S", "MM" | "Mm/s", "Mm" | fix |
| Precision | `ShipState.vel` is `Vec3` (float) | at S = 3000 speeds reach 10⁸–10⁹ m/s | make velocity `DVec3` |
| Sub-steps | `Game.updateFlight`: up to 8 sub-steps of ≤ 1/120 s | Roblox steps once per frame (dt ≤ 0.1) | keep sub-steps (approach and exp laws are dt-invariant); collision per sub-step |
| Tests | `core/src/test/.../game/FlightModelTest.kt` (570 lines, current model) | will be obsolete | replace with the tests in 10.4 |

---

## 9. Phone adaptation

### 9.1 Touch mapping

Two-thumb landscape layout stays (it exists in `TouchFlightControls`); what it feeds changes.

| Roblox input | Action | Touch | Notes |
|---|---|---|---|
| Mouse → cursor (Y) | pitch | right floating stick, Y | aim = stick offset / stick radius (Y down), clamped to the unit circle; through `applyDeadzone(0.20, 1.7)` in rotation, not the current 0.06 + expo |
| Mouse → cursor (X) | roll (ROLL) / yaw (YAW) | right stick, X | "stick X axis" setting = MOUSE X AXIS |
| C | centre cursor | release the stick | the stick springs back (Roblox's cursor holds; a held thumb holds) |
| Q / E | yaw (ROLL) / roll (YAW) | left lever sideways (rudder), analog | `keyAxis = -rudder` (+1 = left); dead zone 0.18 as today |
| W / S | lever ±25 %/s | left lever drag, absolute position | lever range −25 %..100 %, zero line at 20 % of the gauge (was 22 % = −100 %); zero detent and haptic tick kept |
| X (hold) | full stop | double-tap the lever = lever 0 (today); double-tap **and hold** = full stop while held | full stop also kills drift (FA) or retro-burns (FA off) |
| A / D | strafe | new ◀ ▶ buttons beside ▲ ▼ (a 4-way thruster pad at the inner edge of the left zone) | |
| R / F, Space / L-Ctrl | up / down | ▲ ▼ (exist) | |
| Z | FA toggle | FA button (exists) | |
| B | boost | BOOST button; replace the capacitor pips by a cooldown ring (Roblox: cooldown) | grey while cruising or overloaded |
| P | autopilot / docking computer | new AUTO button, shown while a target is locked | |
| T / Y | cycle / clear target | LOCK tap / long press | markers doc |
| L / K | land / dock | contextual action button (exists) | landing/stations docs |
| V | external view | VIEW (exists) | |
| Right mouse | look around | drag in a look zone (top centre band) or a two-finger drag; returns in 0.12 s on release | camera doc |
| Left Alt | free the mouse | not needed | |
| Mouse sensitivity / invert / X axis | settings | stick sensitivity / pitch direction / stick X axis | Roblox range 0.25–2 for the mouse; for the stick keep 0.5–1.5 scaling the deflection |

Haptics: tick on FA toggle and lever detents; heavy on boost ignition; light on autopilot engage/disengage and gear commands.

Sizes: the Roblox cursor elements are defined in 1080-line pixels and scale with the short side. On a 6.4″ 2670×1200 phone a 4 px dot is 0.2 mm. Keep `unit = shortSide / 1080` but clamp the dot to ≥ 2.5 dp and the triangle base to ≥ 9 dp; size attitude texts in dp (`frame.u`) with the Roblox ratios.

### 9.2 Gamepad mapping

| Input | Action |
|---|---|
| Left stick | aim (pitch on Y, roll or yaw on X); platform dead zone off for this stick, Roblox dead zone 0.20 + curve 1.7 applied in rotation |
| Right stick X / Y | key axis (yaw, analog) / vertical strafe |
| LB / RB | strafe left / right |
| RT / LT | lever up / down at 25 %/s × trigger (W/S ramp) |
| R3 (hold) | full stop |
| A | boost |
| X | Flight Assist |
| Y | view |
| D-pad up / down | cycle / clear target |
| D-pad left | autopilot |
| D-pad right | land / dock |
| Select (tap / hold) | system map / star map |
| Start | pause; B back |
| L3 (hold) + right stick | look around |

### 9.3 Optional keyboard and mouse (ChromeOS, DeX)

Android delivers key events and supports pointer capture (`View.requestPointerCapture`). Mapping `ShipConfig.Keys` 1:1 and feeding captured mouse deltas into the Roblox cursor logic (radius, sensitivity, hold, C) reproduces the original exactly. Low priority.

### 9.4 The planet scale S = 3000 (the big deliberate change)

The cruise law's distance part (`gap / T`) is scale-free: multiply all distances by S and all speeds of that law by S and the travel time stays the same. Everything else in this area falls into four classes:

| Class | Rule | Members |
|---|---|---|
| ratios and times | unchanged | shadow/influence/gravity-range factors, curves, CruiseDistance/DepartTime, CruiseTimeUp/DoubleTime/TimeDown/Align, CruiseBrakeTime, LimitFallTime, autopilot ratios, heat/fuel rates |
| distances | ×S | body radii, orbits, `MassShadowRadius`, `HeatRadius`, `FieldRadius` |
| far-field speeds and accelerations | ×S | the shadow ceiling (Roblox `Thrust.MaxSpeed` **as used inside `getState`**), `MassShadowFloor`, `StarShadowMinSpeed`, `ApproachBrake` **inside the far law**, `CruiseMaxSpeed` (see the cap question) |
| ship-local and near-ground | absolute | ship profiles, `Thrust.MaxSpeed` as throttle base/brake threshold/HUD reference, `BrakeAcceleration`, `MinManoeuvreSpeed`, `MinApproachSpeed`, stations, `EdgeSpeed`, `EdgeMargin` (a braking distance), landing-scene law, gear altitude, arrive speed |

Therefore **split `Thrust.MaxSpeed`**: keep 1000 m/s for the throttle map, `advanceSpeedLimit`, the brake's exponential threshold, HUD/audio/cockpit references; add `Gravity.ShadowCeiling = 1000·S` for the shadow law and the cruise base. At S = 1 both are 1000 and the port reproduces Roblox exactly; tests run at S = 1 (10.4).

Re-implementing `ShipThrust` + `ShipGravity` in a script (two bodies on a line, VARROW, full throttle held, nose on target, start 0.5 barrier radii above the departure barrier at rest; scratch tool, not committed) gives:

| Trip (closest approach) | Roblox S = 1: to target shadow edge / to barrier, peak | S = 3000 scaled far law + 9.5: to shadow edge / to the ground, peak, cap 9 Gm/s | cap 0.5 c | cap 3 Mm/s |
|---|---|---|---|---|
| Earth → Luna | 40.4 s / 45.1 s, 19 km/s | 51.8 s / 85.3 s, 0.19 c | same | 146 s / 179 s |
| Earth → Venus | 41.0 s / 67.0 s, 21 km/s | 52.4 s / 100.9 s, 0.21 c | same | 159 s / 207 s |
| Earth → Mars | 44.1 s / 58.2 s, 38 km/s | 55.5 s / 95.4 s, 0.38 c | same | 261 s / 301 s |
| Earth → Jupiter | 53.5 s / 102.6 s, 234 km/s | 64.9 s / 134.3 s, 2.3 c | 79.0 s / 148 s | 1435 s / 1518 s |
| Uranus → Neptune | 61.5 s / 96.8 s, 270 km/s | 72.3 s / 129.1 s, 2.7 c | 90.0 s / 147 s | 1659 s / 1716 s |

The extra ~11 s to the shadow edge comes from absolute parts (ship acceleration to TopSpeed, doubling limit); halving `CruiseDoubleTime` saves ~4 s. The "to the ground" column includes the near-ground descent (9.5), which Roblox did in a separate guided entry. **The cap is a user decision** (section 11): 3 Mm/s makes outer trips 24–28 minutes; scaling it (9 Gm/s, 30 c) keeps Roblox times but shows superluminal numbers; **0.5 c (150 Mm/s) is the recommended default** (matches `03-world.md`): only giant-planet trips get ~15–20 s longer. Speeds above 1 Gm/s need a display unit (Gm/s or multiples of c) in `ShipUnits`.

**Reference frame.** With orbital periods kept (`03-world.md`), bodies move ×S faster (Earth 11.2 km/s, Luna 4.8 km/s around Earth, Mercury 15 km/s). Roblox already handles stations this way: the ship is carried with its station frame and `thrust.velocity` is relative to it. Do the same for planets and moons: the flight model's velocity is **relative to the dominant body**. Dominant = the nearest body by altitude (as `getState` picks it) while the ship is inside that body's mass shadow (planets 5 R, moons 4 R); leave the frame only beyond 1.2× the shadow (hysteresis); outside every shadow use the star frame. Each frame add the frame's displacement to the position (`shiftBy`, like the station carry), and on a frame switch convert `v_rel' = v_rel + v_frameOld − v_frameNew`. Moons inside a planet's shadow (Phobos, Deimos) take over when they become the nearest body; the switch happens near their own shadow edge, where the limit is still Mm/s and the 3.3 km/s frame difference is negligible. Speed limits, closing speed, time to impact, HUD speed and the attitude rate all use the relative velocity. Planet spin, if the world doc adds it, must rotate the frame too.

**Gravity.** The Roblox pull (60 m/s² per g, within 3.5 R) is absolute and stays. In the landing scene Roblox damped it to 35 % (`Surface.GravityScale`) because full gravity ate the lever's authority at low speed limits ("at 20 % thrust you stop and on Earth even sink"). Recommendation: apply `GravityScale 0.35` everywhere in the seamless world (in space FA hides gravity anyway); question in section 11.

### 9.5 Seamless near-ground law (replaces barrier, landing scene and scene speed law)

Problem: scaling the whole Roblox law makes the approach zone's absolute floors meaningless and the scaled `approachReach` (210 km/s²) unfollowable at low speed: a full-throttle dive reaches the ground at ~410 m/s (simulated). Applying the absolute `approachReach` everywhere instead caps cruise towards a planet at √(140 h) (187 km/s at 250 Mm) and trips take one to three hours (simulated). So the law needs two regimes:

```text
H_BAND = Surface.MaxSpeedHigh / Surface.SpeedPerAltitude = 4000 / 0.35 = 11,429 m above terrain
per body:
  if hAgl >= H_BAND:   (lim, zone) = farLaw(hSphere, cos)          // ShipGravity getState law, distances ×S, far speeds ×S, cap as chosen
  else:                lim = clamp(0.35 * hAgl, 260, 4000); zone = "landing"         // Roblox landing-scene law (ShipSurface.getSpeedLimit)
                       if cos > 0.05:                                                // descending: the closing part must be stoppable
                           closing = max(minApproachSpeed, approachReach(hAgl)) / cos   // ABSOLUTE ApproachBrake 70 m/s², knee 280 m
                           if closing < lim: lim = closing; zone = "surface"           // immediate tracking (as Roblox)
                       lim = max(lim, minApproachSpeed)
then: minimum over bodies, stations and the field edge exactly as in 3.5
```

- `hSphere` = distance − R·S (mean surface; the barrier no longer exists — the shadow and influence zones become ~10 % smaller than Roblox's, which measured from 1.105 R + 6; multiply the factors by 1.105 if exact extents matter). `hAgl` = height above terrain/sea from the planet/terrain module.
- Simulated straight dive at full throttle at the end of the trips of 9.4 (S = 3000; Luna, Venus, Mars, Neptune): the ship crosses 11.4 km at ~7.2 km/s (the 0.4 s exponential brake takes it down), arrives at 14 m/s, and needs about 21 s from 11.4 km to the ground. Level flight near the ground keeps the landing scene's 260 m/s minimum.
- Gear down below 60 m AGL; ground contact and crash thresholds (38 m/s scene, 20 m/s old space spheres) belong to the landing doc.
- The atmosphere doc may keep a guided entry (L): then it sets the pose while guiding (`controlled`) and this law applies when it releases.
- `timeToImpact` against terrain becomes meaningful again (Roblox had it effectively off in space and in the scene); enabling impact warnings and the attitude danger colour is a design decision (warnings doc).

### 9.6 Roblox-only workarounds that go away

- Floating origin (`ShipOrigin`, `RebaseDistance 1`, universe ↔ world conversions in `translate` and `edgeLimit`): Kotlin positions are doubles and rendering is camera-relative.
- Separate landing scene (`ShipSurface`, `SceneScale 470`, re-anchoring that moves pose and velocity, scene gravity/altitude/up overrides, `inSpace` flag): replaced by the seamless world and 9.5.
- The barrier (`CollisionRadius = 1.105 R + 6`, minimum-altitude hold and toast): replaced by terrain contact.
- Atmosphere number blending (`getShownSpeed`, `getShownAltitude`, `rateScale`): real values, `rateScale = 1`.
- Mouse capture logic (`LockCenter`, focus, Alt release), QWERTZ key naming: not needed on touch.
- GUI write caches (write-on-change, `FADE_STEPS`, last-value fields, pooled bar frames with stable slots, RichText cost): an immediate-mode renderer draws directly. Keep the 10 Hz text cadence for readability (numbers flicker otherwise).
- Image preload and asset ids for the arrow: draw two triangles.
- Studio-built GUIs (`AttitudeHudInstaller`, `FlightHudInstaller`): code-built UI.
- `CollectionService` tags and per-frame attribute reads: typed, precomputed body records.
- Server round trips: none in this area; cargo and fuel persistence become local save data.
- `ShipRig` (anchoring the Roblox character), `ShipConfig.Runtime` globals, the 200-locals workaround (`hud.attitude`).

### 9.7 Performance and allocation

Everything here is a few hundred floating-point operations per frame plus one loop over bodies (Sol 18) and stations. Rules:

- No allocation per frame: preallocated `DVec3`/`Vec3`/`Quat` scratch, one reused `GravityState`, `PilotInput`, `AutopilotOutput`; no lambdas (the attitude `project` closure becomes a method writing to out-fields); texts through `TextBuf` at 10 Hz (no `String.format`).
- Doubles for positions, velocities, altitudes and the speed law (at S = 3000 altitudes reach 10¹⁰ m and speeds 10⁸–10⁹ m/s; float ulp at 1e9 is 64 m/s). Floats are fine for rates, accelerations and UI.
- Attitude indicator: at most 8 bars × (4 segments, 2 hooks, 2 labels) + horizon + 3 readouts ≈ 60 quads/glyph runs; the arrow is 2 triangles + 1 quad.
- 120 Hz: all laws are frame-rate independent (exponential smoothing, `approach` with accel·dt, compounding doubling cap).

---

## 10. Kotlin port plan

### 10.1 Files and classes (`core/src/main/kotlin/com/starwake/core/`)

| File | Contents |
|---|---|
| `flight/FlightConfig.kt` | `object`s Rotation, Thrust, Handling, Gravity (+ `ShadowCeiling`, `NearBand`), Physics, Autopilot, Systems, Cursor, Attitude, Gear, with every value of section 4; `scale` S |
| `flight/ShipProfiles.kt` | `class ShipProfile` (all fields of 4.3, perks, external camera) and the 8 instances; `perk(id, name)` |
| `flight/PilotInput.kt` | per-frame input record: `aimX, aimY` (−1..1, Y down), `keyAxis` (+1 = left), `lever` (absolute) or `throttleAxis`, `lateralX/Y`, `fullStop`, `boostPressed`, `faTogglePressed`, `cursorRolls`, `steering` |
| `flight/Handling.kt` | `ShipHandling` |
| `flight/ShipRotation.kt` | rates + `Quat` orientation, `inputAmount`, `rollAmount`, `angularVelocityDeg(out)` |
| `flight/ShipThrust.kt` | state of 3.4, `update(dt, rot, handling, input): writes delta into DVec3`; `ThrottleMap.targetSpeed/throttleFor` |
| `flight/SpeedLaw.kt` | pure functions: `cruiseLimit`, `cruiseTime`, `approachReach`, `edgeLimit`, `zoneLabel`, `bodyLimit(farLaw / near band)` with S |
| `flight/GravityField.kt` | `getState(pos, relVel, out: GravityState)` over the world's bodies; reference-frame selection helper |
| `flight/ShipSystems.kt` | fuel, heat, G, scooping |
| `flight/Autopilot.kt` | 3.7, writes `AutopilotOutput` |
| `flight/GearRig.kt` | `GearRig.build(parts)`, `pose(legT, doorT, out transforms)`, `GearAnimator` (stepGear timing, visibility flags) |
| `ui/FlightArrow.kt` | dot + two-tone triangle, flat and glass placement |
| `ui/AttitudeHud.kt` | 3.10 with the proposals of 6.4 |
| `input/TouchFlightControls.kt` (modify) | produce `PilotInput`; lever −25 %..100 %; full-stop hold; ◀ ▶; AUTO; boost cooldown ring |

Retire: `game/Flight.kt` `ShipSpec`/`ControlInput`/`FlightModel.step` (move `collideSphere`/`resolveContact` into the collision work), `StarSystem.locate`/`speedLimit`/`Zone` (keep `sunlight`), `Game.drawSteeringArrow`, the reticle and velocity marker in `drawHud`, `drawLandingHud`, `drawGear`, `updateGear`.

### 10.2 Hooking into `Game.kt`

`Game.updateFlight` (`core/.../Game.kt:638`) becomes the ShipClient order (section 2), per frame with `dt ≤ 0.1`, optionally sub-stepped:

1. `touch.update(dt)`, gamepad → `PilotInput` (replaces `readControls`, `:746`).
2. Input gating: disabled while docking computer/guidance/warp/overlays; boost and FA edges consumed.
3. `handling.update(dt, speed, fuel, cargo)`.
4. Autopilot (override rules) → may replace aim/keyAxis/lever.
5. `rotation.update(...)`.
6. Orbits, reference-frame carry (planet/station).
7. `gravityField.getState(pos, relVel, gs)`; compose stations, edge; near band (9.5).
8. `thrust.setSpeedLimit`, `setGravity`; `thrust.update(...)`; boost heat.
9. Translate (field edge), guidance overrides, collisions.
10. `systems.update(...)`; heat death.
11. Gear command + animator; HUD/cockpit/audio data; `FlightArrow`, `AttitudeHud` drawn in `drawHud` (`:860`).

Extend `Game.debugCommand` (`:290`) with the ShipDebug equivalents (`ShipClient.lua:3364-3560`): `stick x y` (held), `throttle v`, `fa on|off`, `boost`, `ship <id>`, `teleport x y z [lx ly lz]`, `near <body> [alt]`, `autopilot`, `heat v`, `gear down|up|auto`, and `state` fields in `debugInfo`.

### 10.3 Order of work

1. `FlightConfig`, `ShipProfiles`, pure maths (`applyDeadzone`, `smooth`, `targetSpeed`, `throttleFor`, `cruiseLimit`, `cruiseTime`, `approachReach`, `blueZone`) + tests at S = 1.
2. `ShipHandling` + tests.
3. `ShipRotation` + tests (signs, rates, FA off, orthonormality).
4. `ShipThrust` + tests (lever, boost, cruise curve, caps, brake, gravity); switch velocity to doubles.
5. `SpeedLaw`/`GravityField` at S = 1 (Roblox table of 3.5), then S-scaling, near band and reference frames; replace `StarSystem.locate`.
6. Frame orchestration in `Game.kt`, station and edge composition, zone labels.
7. `ShipSystems` + blackout overlay + HUD hookup.
8. Input: `PilotInput`, touch and gamepad mapping, settings (FOV).
9. `FlightArrow` (flat, cockpit glass).
10. `Autopilot` + AUTO button.
11. `AttitudeHud`.
12. `GearRig` + `GearAnimator` (after the ships import carries the joint attributes).
13. Harness scenarios, allocation check, on-device tuning (dead zone on touch, sizes).

### 10.4 Unit tests (`core/src/test/kotlin/com/starwake/core/flight/`)

Run the far law at **S = 1** so the expected values are Roblox's; add scale tests separately. Optional: generate golden traces by running the Lua modules in the Luau CLI with small `Vector3`/`CFrame` shims, stored as CSV fixtures.

- **Handling:** every row of 4.13 (full and empty tank, ±1e-3); agility table of 3.2; overload at VARROW mass > 480 t; turn acceleration independent of agility.
- **Dead zone/curve:** |v| 0.2 → 0; 0.5 → 0.18874; 0.6 → 0.30779; 1.0 → 1; radial (diagonal (0.5, 0.5) gives 0.46070, the same as (0.7071, 0)).
- **Rotation signs:** cursor up → nose up; cursor right with ROLL → right wing down; Q (+1) → nose left; YAW mapping swaps; FA on reaches 24.7 °/s pitch from rest in 0.206 s; FA off: torque 84 °/s², cap 27.17 °/s, no decay at zero input; 100,000 steps keep the quaternion normalised.
- **Throttle map:** the VARROW table of 3.4.1; `throttleFor(targetSpeed(t)) ≈ t` for t in 0..1 and references 500, 3e6, 9e9; band clamp for a 1200 m/s TopSpeed.
- **Lever:** W held 1 s → 0.25; clamp −0.25..1; full stop from 1 → 0 in 1.2 s; reverse target −130 m/s reached in 5.2 s.
- **Combat band:** 0 → 260 m/s in 7.43 s; drift killed at 40 m/s² without strafe input, 20 m/s² with; full stop zeroes all axes.
- **Boost:** 2 s timer, 4 s cooldown from ignition, refused while cruising/overloaded (request dropped), heat +0.06, decay after boost (τ 1.8 s above 265.2 m/s, then 25 m/s²); FA off at zero lever: no thrust.
- **Cruise:** the open-space curve of 5.1 (±1 %); direction follows the nose with τ 0.25 s; felt acceleration capped at 14.715 m/s²; heavier ship (MORVEN empty vs full) slower by `ratio^0.75`.
- **Gravity brake/limit tracking:** 80 m/s² below 1 km/s; exponential 0.4 s above; limit rises at once, falls with 1.6 s below 1 km/s, at once in "surface"; never below 12 m/s.
- **Speed law (S = 1):** the Earth table of 3.5 (±0.1 m/s and zone), the star values, `cruiseTime` (3.5 / 2.5 / 1.5), `approachReach` continuity at 280, `edgeLimit` (EdgeSpeed reached `EdgeMargin` before the edge when flying outward, no margin flying inward), minimum over bodies, nearest body by altitude, time to impact, gravity vector (Earth at the barrier: 60 m/s², FA → 13.2 m/s²).
- **Scale:** for the far law `v_S(S·h) = S·v_1(h)` (checked at h = 100, 1000, 5000, 11745, 50000, 1e6); near band: 9.5 dive arrives ≤ 14 m/s.
- **Systems:** heat at the star while scooping hits 1.0 after 7.3 s and 2.0 after 24.7 s; equilibrium 1.107 at starHeat 0.5 (scooping, full throttle); fuel 455 s full-throttle endurance, LOCHRAN 758 s; G strain and redout for (+Gz 5 g → strain 5, redout 0), (−Gz 5 g → 7.5, redout 1), (forward 7.85 g → 2.75).
- **Autopilot:** the examples of 3.7 (cursor, roll, ALIGNING/CRUISING/BRAKING/ARRIVED), FA forced on at engage, MANUAL OVERRIDE at deflection 0.51 and at any key axis.
- **Gear:** `pose` of a Leg (hinge X, 90° at legT 1 with smoothstep), Foot stays level, Link stretch and resize; Door at doorT 1; timing retract 1.6 + 0.6 s, extend 0.6 + 1.6 s, reversal mid-way; instant in cockpit; visibility flags.
- **Flight arrow:** the examples of 3.9 (positions, rotations, colours, dot steps, sizes at unit 1 and 1.111: arrow image 24×24 and 26×28 px), hidden below 0.04.
- **Attitude:** visible bar sets for pitch 0 / 12 / 47.3 / 85, slot mapping with negative angles, label rotation never upside down, rate fractions (10 → 0.1833, 100 → 0.5079, 1000 → 0.8848, 2000 → 1), texts ("0.0°", "+12.3°", "-0.1°", "316 M", "3.17 KM", "6.84 Mm", "0 M/S", "+120 M/S", "-1.24 KM/S"), fade 0.45 s, hidden without `up`.
- **No allocation:** run 10,000 frames of the flight loop under the existing allocation counter (desktop `Perf.kt`).

### 10.5 Desktop harness scenarios (`desktop/.../Scenarios.kt`)

| Name | Script | Proves |
|---|---|---|
| `flight-throttle` | rest in open space, lever 100 %, log speed every second for 20 s, shots at 5, 12, 18 s | lever ramp, band, cruise curve, zone label, HUD numbers |
| `flight-handling` | each of the 8 ships: full pitch from rest, from half TopSpeed, in cruise; FA off spin | per-ship rates and agility |
| `flight-boost` | boost from TopSpeed, try a second boost at 2 s and 4.1 s | timer, cooldown, heat, decay |
| `flight-trip` | Earth → Luna and Earth → Mars at S = 3000, full throttle | times of 9.4 (±10 %), peak speed, no tunnelling into the target |
| `flight-dive` | 50 Mm above Earth, nose down, full throttle to the ground | near band, arrival ≤ 14 m/s, gear down at 60 m, attitude indicator on |
| `flight-star` | approach the star to heat death | heat timeline, scooping, warnings, HEAT ZONE limit ≥ 1 km/s |
| `flight-autopilot` | lock Luna, engage, log status changes | ALIGNING → CRUISING → BRAKING → ARRIVED; times |
| `flight-arrow` | `stick` at 0, 0.1, 0.5, 1.0 in 8 directions; cockpit view with head offset | arrow look and placement (shots) |
| `attitude` | pitch −20, 0, +12, +47, ±88 near a planet; with a climb and a sink | ladder, horizon, zenith/nadir, path marker, readouts (shots) |
| `gear` | `gear up/down` in the external view, shots at 0, 0.6, 1.6, 2.2 s | legs, feet level, links stretch, doors, closed covers |
| `flight-alloc` | 10 s of flight with all instruments | zero allocations, CPU ms |

---

## 11. Open questions and risks

**Questions for the user**

1. **Cruise cap at S = 3000.** Keep 3 Mm/s (outer trips 24–28 min), raise to about 0.5 c (planet-to-planet ~1–2.5 min, recommended), or scale it fully (9 Gm/s, peaks up to 2.7 c, exactly Roblox times)? AFloidDEV wrote that cruising near light speed "doesn't make sense either".
2. **Speed readout above 1 Gm/s:** "Gm/s", multiples of c, or never show more than the cap?
3. **Planet entry:** keep the Roblox guided entry on L (HEAT → DESCENT → LEVEL, pilot takes over at 900 m and 320 m/s) in the seamless world, or free flight to the ground under the near-ground law of 9.5?
4. **Attitude indicator trigger:** Roblox shows it only after L (entry, surface, ascent). In a seamless world: on entering the atmosphere, below some altitude, or also on approach (`ShowOnApproach`)?
5. **Please export `StarterGui.AttitudeHud`** (and `CockpitInteract`, plus the `AttitudeHudInstaller` and `FlightHudInstaller` scripts from `ServerStorage.Dev`) as UI JSON (EXPORT_INSTRUCTIONS step 4), so line widths, fonts and colours match.
6. **Gravity near the ground:** use the landing scene's damped gravity (35 %) everywhere?
7. **Touch controls:** is an absolute throttle lever (instead of the slow W/S ramp) and "double-tap and hold = full stop" acceptable? Should the right stick spring back (it does today) or hold its deflection like the Roblox mouse?
8. **Impact warnings:** Roblox had them effectively off (barrier in space, none in the landing scene). Enable them against terrain in the seamless world?

**Risks**

- The near-ground band switch at 11.4 km is a new design; it needs on-device tuning (felt jolt when the limit drops, low-level flight speed, mountains above the band).
- Reference-frame switching between planet and moon frames can make the shown speed jump by the frame-velocity difference (Luna–Earth 4.8 km/s); switch only far out where speeds are Mm/s, with hysteresis.
- The gear joint data exists only in `Assets.rbxmx` attributes; the ships importer must decode `AttributesSerialize` (including axis-aligned CFrame rotation ids) and keep part identity, or gear animation cannot be ported.
- The attitude GUI template is missing; the proposal in 6.4 may not match the original look.
- A 0.20 dead zone on a 52 dp touch stick may feel sluggish; keep it as the 1:1 default but expose it for tuning.
- Retiring `FlightModel` breaks `FlightModelTest` and every harness scenario that relies on the current feel (gameplay, station-game); port them in the same change.
- Double-precision velocity touches `ShipState`, rendering helpers and the station collision code (`StationCollision.kt`) that reads `ship.vel`.
