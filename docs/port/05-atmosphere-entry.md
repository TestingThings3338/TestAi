# 05 · Atmosphere and entry: landing assist, sky, clouds, entry effects, warnings, destruction

Port reference for **Untitled Space Exploration** (Roblox, by AFloidDEV) → Android/Kotlin (`:core`). It covers everything between "the planet fills the window" and "the ship is on the ground", and the way back:

- the **guided landing and take-off** (`ShipAtmosphere`): states, the HEAT → DESCENT → LEVEL and CLIMB → EMERGE phases, prompts, the arrival line, hull knocks;
- **sky and light below the air** (`ShipSky`): day and night from the sun's elevation, the light ramp from space to ground light, air density and haze, the coloured sky dome, the sun disc, the sky-image swap, stars;
- the **cloud deck** (`ShipClouds`): a planet-fixed noise field, the deck image, top and bottom view, puffs along the flight path, whiteout;
- **atmospheric entry effects** (`ShipEntryFx`): plasma on the leading edges, streaks, light rails, shock glow, hull and cockpit light, the heat vignette at the screen edge, wind and rumble, camera shake;
- **impact and overheat warnings** and the **blackout** of a crash (`ShipWarnings`);
- the **crash cinematic** (`ShipDestruction`): explosion, debris, smoke, the rising camera, the SHIP DESTROYED card;
- the **per-body profiles** these modules read (`ShipSurfaceProfiles`: sky, cloud deck, entry glow, arrival title);
- and the design that replaces the landing scene on the phone: **one continuous descent on a real-size planet**, with the guided landing as an optional **landing assist** (section 8).

**Sources.** Lua paths are relative to `roblox/lua/`; `path:line` is the line where a function or table starts. Read in full: `ShipClient/ShipAtmosphere.lua` (731 lines), `ShipClient/ShipClouds.lua` (1,454), `ShipClient/ShipSky.lua` (550), `ShipClient/ShipEntryFx.lua` (1,262), `ShipClient/ShipWarnings.lua` (428), `ShipClient/ShipDestruction.lua` (1,551), `ShipCore/ShipSurfaceProfiles.lua` (262); `ShipConfig.Atmosphere` (`ShipCore/ShipConfig.lua:952`), `.Sky` (`:669`), `.Warnings` (`:542`), `.Destruction` (`:597`). Also read for the values and call sites this area uses: `ShipConfig.Surface` (`:743`), `ShipClient/ShipSurface.lua` (switch, level and exit altitudes, `afterCamera`, the queries), `ShipClient/ShipGravity.lua` (altitude and time to impact), `ShipClient/ShipCallouts.lua:221` (the entry callouts), `ShipClient/ShipFarTerrain.lua:700-756` (the haze colour Roblox really draws), and every call in `ShipClient.lua` (section 2.8).

**Sibling specs** (referenced, not repeated): `02-flight.md` (flight model, near-ground speed law §9.5, body reference frame, gravity, attitude indicator), `03-world.md` (Sol data, the scale S, the barrier, atmosphere colours, question Q11 on atmosphere heights), `04-planet-surface.md` (terrain, ground queries, the crash rule "only on visible ground", `SurfaceProfiles.kt`, the aerial-perspective interface §9.11), `06-cockpit-hud.md` (prompt line, toasts, status line, LANDING lamp, cockpit shake composition), `08-stations-economy.md` (losses on destruction, respawn station), `09-life-npc-audio.md` (audio engine, `playHit`, voice lines and the entry callouts). **`DECISIONS.md` overrides proposals here.** It settles for this area: D14 (seamless entry, the guided landing becomes an optional landing assist on the ACTION button that flies the same profile), D15 (when the attitude indicator shows), D16 (impact warnings against terrain are on), D3 (near-ground speed band, absolute), D5 (gravity 9.81 m/s² per g with 35 % damping), D7 (planets never spin), D11 (giants: barrier at the cloud tops, "LANDING BLOCKED · NO SOLID SURFACE"), D12 (true star direction, gentle distance falloff), D17 (terrain heights keep their metre size), D31 (audio stand-ins), D33 (icons drawn in code), D35 (fix the silent crash debris).

**Units and the three scales.** 1 stud = 1 m.

| Space | Scale | Earth radius | Used for |
|---|---|---:|---|
| Roblox *space* | ×1 | 2,120 m | approach and the HEAT and EMERGE phases |
| Roblox *landing scene* | ×470 (`Surface.SceneScale`) | 996,400 m | DESCENT, LEVEL, surface flight, CLIMB; sky, clouds, fog |
| Port world | ×3000 (S, `03-world.md` §1.3) | 6,360,000 m | everything |

Every altitude this area uses near the ground (cloud decks, air thickness, level-off height, exit altitude, heat fade band, deck speed) is **in landing-scene metres** in Roblox. In the port these keep their metre size (D17, `04-planet-surface.md` §9.4.5). Thresholds that Roblox gave as multiples of the body radius in space (prompt and hover ranges, the ascent target) keep their ratio and therefore scale with S.

**Notation.** `smooth(e0, e1, x)` = Roblox smoothstep: `t = clamp((x − e0)/(e1 − e0), 0, 1)`, `t²(3 − 2t)`; with `e0 > e1` it is a falling edge. `ShipEntryFx` and `ShipDestruction` have their own forms with different argument orders (`smoothstep(x, from, to)` and `smoothstep(x)`); they are all written as `smooth(e0, e1, x)` here. `lerp(a, b, t) = a + (b − a)·t` (colours per channel, like `Color3:Lerp`). `scaled(c, k)` = each channel of `c·k` clamped to [0, 1]. `approach(v, target, τ, dt) = v + (target − v)·(1 − e^(−dt/τ))`. `flat(v, up) = v − up·(v·up)`. Colours are sRGB 0–255 as in `Color3.fromRGB`. `noise` = Luau `math.noise` (`04-planet-surface.md` Appendix A). `Random` = Roblox `Random` (port: the PCG32 re-implementation of D10). Ship space: −Z forward, +Y up, origin at the flight model's reference point; the pilot's eye is at `Camera.SeatOffset` (0, 0.5, 0).

**Naming.** The player is PILOT. All strings below are the user's own Roblox strings and are ported verbatim unless a section says otherwise.

---

## 0. Key facts in one screen

- **Roblox landing is an autopilot on key L.** It starts only within 0.35 R above the barrier (`Atmosphere.HoverEntryAltitude`). HEAT flies down in space with a vertical speed of `h/2.2 s` and a forward glide of 2.1 times that; the glow ramps in over 1.2 s. At `0.028·R` above the surface the game swaps into the ×470 landing scene (`ShipSurface`) where both pictures are identical. DESCENT continues with the same law, slows to 600 m/s through the cloud deck (±300 m) and fades the glow between 0.6 and 0.25 of the switch height. LEVEL holds 900 m and hands over at 320 m/s once level (or after 7 s). Earth: **13.9 s from L to hand-over, 56° of arc downrange** (computed, 4.2).
- **Take-off is automatic once the pilot climbs above the exit altitude** (8 km on Earth): CLIMB (`max(h/1.9, 300)` m/s up, 0.55 forward) to the switch height, swap back into space, EMERGE (`max(h/1.5, 20)`) to 0.5 R above the barrier. Earth: 8.9 s.
- **While guided the pilot has no control.** The HUD shows altitude and speed blended into the scene's scale (log blend over 1.2 s), so the numbers never jump at the swap.
- **Impact warnings were effectively off** in the shipped Roblox game: `timeToImpact` is forced to infinity in space (barrier) and in the landing scene. Only the overheat track and "GRAVITATIONAL INFLUENCE" ever showed.
- **Sky below the air** (`ShipSky`, profile `sky.thickness`, Earth 26 km): day = `smooth(−0.10, 0.12, sun elevation)`; light ramps from space to ground light over `0.55·thickness`; air density `(1 − h/thickness)^1.6`; by day an opaque dome in the zenith colour fades to black above the exit altitude; Roblox's fog blends everything far into the colour of the sky image behind (deep blue `46,105,167` under the Earth day sky, the desaturated air colour under the haze sky); the sky image is swapped only while hidden in cloud or after passing the deck.
- **Clouds** (`ShipClouds`): a planet-fixed noise field (swirled 5-octave noise plus patchiness, threshold calibrated so `cover` of all directions is cloud), painted into a 512² gnomonic image 100 km wide around the camera, shown on a dome around the eye by per-vertex ray hits on the deck's top or bottom sphere; puffs spawn ahead on the flight path; whiteout = cloud coverage × depth inside the band.
- **Entry effects** (`ShipEntryFx`): one number, `heat` 0..1, drives six staged layers over a leading edge measured from the hull (7 columns), two flickering lights, 12 light rails, a heat vignette at the screen edge, wind and rumble loops and a camera shake of about ±0.35° (peaks 0.49°).
- **Warnings** (`ShipWarnings`): phases by time to impact (10 s and 3.5 s, exit ×1.35), an overheat track with priority (0.8 / 1.0, exit ×0.88), red text "IMPACT" / "OVERHEAT" at 16 % of the screen height, a crash blackout.
- **Destruction** (`ShipDestruction`): an 11 s cinematic (flash, fireball, sparks, shockwave, dust ring, 34 charred hull copies and 26 fragments, smoke column, fires, after-explosions) with a camera that rises from 2.6 to 8.1 wreck radii and 36° to 68° while orbiting 3.5°/s, and the SHIP DESTROYED card with losses and "RESPAWN IN n"; then a 1.2 s fade, 1.4 s black (respawn at the station nearest the crash), 1.2 s fade-in.
- **Port:** no scene, no swap. Free flight from orbit to the ground under the speed laws of `02-flight.md`. The guided landing becomes the optional **LAND assist** (same law and constants, altitudes in absolute metres, phases kept); the ascent is free flight with an optional **ORBIT assist**. Heat comes from the assist (1:1) or, in free flight, from altitude band × airspeed. One sky model works at every altitude. Clouds are real shells drawn with depth. Impact warnings work against terrain, gated so a normal landing never triggers them. The destruction cinematic is ported 1:1 with a code-drawn card.

---

## 1. Overview

### 1.1 What the player experiences (Roblox, Earth)

1. **Approach.** Within 1.84 R above the barrier (`EntryAltitude 1.15 × 1.6`; Earth: 3,901 studs above it) the HUD prompts **DESCEND TO LAND**. On a gas giant the prompt reads **LANDING BLOCKED  NO SOLID SURFACE**. The barrier (`CollisionRadius = ceil(1.105·R) + 6`) holds every ship softly; sinking alone never leads into the atmosphere.
2. **Hover range.** Within 0.35 R above the barrier the prompt changes to **PRESS L TO LAND** and the cockpit LAND button lands before it docks. A press is remembered for 3 s, so a press just outside the range still counts. If nothing happens within 3.1 s the voice says "LandingBlocked".
3. **HEAT.** After L the pilot loses control. The ship pitches into its flight path and dives; the prompt reads **ATMOSPHERIC ENTRY** (DESCENT on airless bodies). Within 1.2 s plasma builds up on the nose and the leading edges, glowing streaks race past the canopy, the screen edge glows in the planet's entry colour (Earth orange-pink, Mars deep red, Titan gold), wind and rumble swell, the camera shakes and the hull knocks about once a second. The space atmosphere shells fade out as the ship approaches them. The planet grows; the altitude readout counts in landing-scene metres from the start.
4. **Swap.** At 0.028 R (59 studs on Earth) the game swaps into the landing scene. Nothing visible happens: the ground is the same image ×470, seen from the same place (27.9 km scene altitude).
5. **DESCENT.** The glow fades between 16.7 km and 7.0 km. The sky turns from black to deep blue as the air thickens; the light changes from space light to the body's light. Over the cloud deck the descent slows to 600 m/s: a bright deck rushes up, everything turns white (whiteout), puffs tear past, the hull knocks more often, and the ship breaks out under the deck: the land lies below, the deck hangs dark above. The sky image swaps from the haze sky to the blue day sky while hidden in the cloud.
6. **LEVEL.** From 1.62 km the ship flattens out to 900 m (**LEVELLING OFF**) and hands over at 320 m/s with the nose on the horizon; the throttle is set to hold that speed. A toast shows **EARTH** / `TEMPERATE WORLD  ·  1.00 G  ·  ATMOSPHERE` for 6 s. The callout says "Entry complete. You have control."
7. **Surface flight.** **CLIMB TO LEAVE ATMOSPHERE** shows the whole time (CLIMB TO RETURN TO SPACE on airless bodies). Terrain, landing and ground rules are in `04-planet-surface.md`.
8. **Leaving.** Climbing above the exit altitude (8 km on Earth) starts the guided ascent: CLIMB to 27.9 km, swap, EMERGE to 0.5 R above the barrier (**LEAVING ATMOSPHERE**), then the pilot flies again.
9. **Crash.** Too hard on visible ground (impact > 38 m/s), into a station hull (> 45 m/s) or at heat 2.0 near the star: the voice says "Hull failure", the ship explodes, the camera pulls up and away above the wreck, black bars slide in, **SHIP DESTROYED** pops in with `VARROW  ·  CRASHED ON EARTH`, the losses (`REPLACEMENT FEE 1,100 CR  ·  12 T CARGO LOST`) and the recovery station with **RESPAWN IN n**. After 11 s the picture fades to black; the replacement ship waits in front of the station nearest the crash; the picture fades back; "Rebooting", then "All systems online".

### 1.2 What the player experiences in the port (Earth, R = 6,360 km)

1. **Approach.** **DESCEND TO LAND** below 12,389 km. **LAND** appears on the ACTION button below 2,913 km (the same angular size of the planet as Roblox's hover range).
2. **Two ways down.** Tap **LAND** and the assist flies the Roblox profile: the plasma builds within 1.2 s, the planet grows, the HEAT phase lasts until 27.9 km (9.5 s), DESCENT and LEVEL follow as in Roblox, hand-over at 900 m and 320 m/s after **16.8 s** and 56° of arc (6,200 km downrange). Or simply fly down: the speed law (`02-flight.md` §9.5) brakes the descent; a fast dive through the upper air (78 km → 7 km) glows, roars and shakes in proportion to the airspeed.
3. **Continuous sky.** From orbit the limb is a thin real-scale atmosphere (78 km on Earth). Below 26 km the Roblox sky model takes over (dome colour, haze, day and dusk, stars at night) and blends with the space look above 14 km. The procedural cloud deck fades in between 36 km and 27.9 km as a local deck around the ship (100 km wide); the planet image's painted clouds stand in farther out and from higher up.
4. **Cloud deck, level-off, arrival toast, surface flight**: as in Roblox, at the same metre heights.
5. **Leaving.** Free flight: climb under the speed law (departing grows fast). Above the exit altitude the ACTION button offers **ORBIT**, which flies Roblox's CLIMB and EMERGE profile to 3,867 km.
6. **Warnings.** Flying towards terrain at more than about 28 m/s impact speed: **IMPACT** in red with a warning tone 10 s before, a looping alarm 3.5 s before.
7. **Crash**: the Roblox cinematic 1:1.

---

## 2. Modules

### 2.0 Shared helpers and conventions

| Helper | Where | Definition |
|---|---|---|
| `smoothstep(e0, e1, x)` | `ShipAtmosphere.lua:50`, `ShipSky.lua:122`, `ShipClouds.lua:187` (`smooth`) | standard, see Notation |
| `smoothstep(x, from, to)` | `ShipEntryFx.lua:180` | same curve, value first, denominator `max(to − from, 1e-4)` |
| `smoothstep(x)`, `window(t, start, length)` | `ShipDestruction.lua:104`, `:110` | `smooth(0, 1, x)`; `window = clamp((t − start)/max(length, 1e-3), 0, 1)` |
| `approach(current, target, τ, dt)` | `ShipAtmosphere.lua:55` | `current + (target − current)·(1 − e^(−dt/max(τ, 1e-3)))` (vectors) |
| `flat(v, up)` | `ShipAtmosphere.lua:60`, `ShipDestruction.lua:119` | `v − up·(v·up)` |
| `logLerp(from, to, t)` | `ShipAtmosphere.lua:65` | `exp(ln max(from,1) + (ln max(to,1) − ln max(from,1))·t)` ("500 → 400,000 would otherwise look like a jump") |
| `across(up)` | `ShipDestruction.lua:124` | `unit(up × (abs(up.y) < 0.9 ? Y : X))` |
| `randomUnit()` | `ShipDestruction.lua:129` | `z = U(−1,1)`, `a = U(0, 2π)`, `(√(1−z²)·cos a, √(1−z²)·sin a, z)` with a module-wide unseeded `Random` |
| `ShipMath.smooth(cur, target, τ, dt)` | `ShipCore/ShipMath.lua:19` | `cur + (target − cur)·(1 − e^(−dt/τ))`, `τ ≤ 0` → target |
| `scaled(c, k)` | `ShipSky.lua:127`, `ShipClouds.lua:192`, `ShipSurfaceProfiles.lua:74` | per channel `clamp(c·k, 0, 1)` |
| `multiply(a, b)` | `ShipClouds.lua:196` | per channel product |

### 2.1 ShipAtmosphere: landing and take-off (`ShipClient/ShipAtmosphere.lua`) · **port: `EntryAssist` + `AtmosphereState`**

#### 2.1.1 Purpose (header `:1-32`)

"Landing and take-off: one continuous, guided flight from space to just above the ground and back." The older version covered the view with a cloud picture while the terrain was built behind it; since 2026-11-03 everything is visible: "the planet grows, the air glows, you fall through the cloud deck and level off above the ground (Josh: like a real flight through the clouds)". The swap into the landing scene happens on the way, at a height where both pictures look the same.

States (for all other modules): **SPACE** (in space), **ENTRY** (entry running: phases HEAT, DESCENT, LEVEL), **SURFACE** (down, the pilot flies), **EXIT** (ascent: phases CLIMB, EMERGE).

Phases: **HEAT** in space down to the switch altitude, with glow; **DESCENT** in the scene, vertical `v = h/τ` "so the picture falls equally fast at every height", through the deck at `DeckSpeed`; **LEVEL** levels off at `LevelAltitude`, then the pilot takes over; **CLIMB** in the scene up to the switch height; **EMERGE** in space up above the barrier, then free. While a phase runs the ship flies itself (`ShipClient`: `guide` after the thrust step). The HUD counts altitude and speed in the scene's scale so the numbers do not jump at the swap.

#### 2.1.2 Public API

| Function | Line | Behaviour |
|---|---|---|
| `new(_playerGui)` | `:70` | state SPACE, phase nil; fields of 2.1.3 |
| `setCallbacks(onNeedTerrain, onHit, onSwitch)` | `:124` | `onNeedTerrain(body, wanted)`: entry begins (true) / is over (false). `onHit(strength)`: knock on the hull (sound). `onSwitch(direction, body, pose, velocity) → pose, velocity`: "DOWN" space → scene, "UP" scene → space; returns pose and velocity in the new space |
| `setSurface(surface)` | `:131` | the landing scene, for the altitude during guidance |
| `getState()`, `getPhase()` | `:135`, `:139` | |
| `isBusy()` | `:143` | state ENTRY or EXIT |
| `isOnSurface()` | `:147` | state SURFACE |
| `isGuiding()` | `:152` | phase ≠ nil ("then the pilot has no controls") |
| `getCover()` | `:157` | always 0 (the old curtain; kept for older callers) |
| `getProfile()` | `:161` | the body's profile while entering/landed |
| `getHeat()` | `:166` | glow 0..1 for the entry effects |
| `getHeatColour()` | `:170` | `profile.entry.glow` or `DEFAULT_GLOW` RGB 255,140,60 |
| `getTurbulence()` | `:176` | unrest in the cloud deck 0..1 |
| `getFeltAcceleration()`, `getAcceleration()` | `:181`, `:185` | what the guidance does to the ship (felt: clamped to `FeltMaxG`) |
| `getShownAltitude(default)`, `getShownSpeed(default)` | `:190`, `:197` | HUD numbers in the scene's scale during HEAT and EMERGE, else the default |
| `takeArrival()` | `:202` | `{title, subtitle}` once per landing, then nil |
| `requestAscent()` | `:212` | only in SURFACE without phase: sets `ascendRequested` |
| `requestLanding()` | `:222` | only in SPACE (else returns false): sets `landingRequested`, cleared by `task.delay(RequestWindow 3 s)` |
| `getPrompt()` | `:233` | HUD hint or nil |
| `canLandNow()` | `:238` | SPACE and `landReady` (the cockpit button lands before it docks) |
| `markTerrainReady()` | `:243` | no-op (nothing to wait for any more) |
| `abort()` | `:368` | for crashes, see 2.1.4 |
| `resetToSpace()` | `:411` | after a crash on the ground, in the black |
| `guide(dt, shipCFrame, velocity) → (pose?, velocity?, released)` | `:430` | every frame after thrust, 2.1.5 |
| `update(dt, gravityState, speed)` | `:627` | every frame after the camera, 2.1.6 |
| `destroy()` | `:727` | restores the shells |

#### 2.1.3 State (`new`, `:70-115`)

`state` "SPACE"; `phase` nil; `body`, `profile`, `surface` nil; `landingRequested`, `ascendRequested` false; `rearmPending` false ("after an abort armed again only once the entry zone was left"); `landReady` false; `prompt` nil; the three callbacks; `timer` (s in the current phase), `clock` (s since the guidance began), `hitTimer`, `heat`, `turbulence` 0; `acceleration`, `felt` zero vectors; `height` (above the surface: real in space, scene in the scene); `progress` 0 (log progress in HEAT and EMERGE); `startHeight` nil; `relative` nil (ship offset from the body in space); `switchHeight` 1, `switchScene` 1, `levelHeight = Atmosphere.LevelAltitude`, `emergeHeight` 1; `shownAltitude`, `shownSpeed`, `blendOut`, `arrival`, `shells` nil.

#### 2.1.4 Lifecycle

**`startEntry(body)`** (`:294`): state ENTRY, phase HEAT, `profile = ShipSurfaceProfiles.forBody(body)`, `timer = clock = 0`, `hitTimer = HitIntervalMin·0.5` (0.475 s), heat and turbulence 0, both requests false, `blendOut = arrival = nil`,
```
switchHeight = ShipSurface.switchAltitude(body)          = SurfaceRadius · Surface.SwitchAltitude 0.028     (space studs)
switchScene  = switchHeight · Surface.SceneScale 470                                                   (scene metres)
levelHeight  = ShipSurface.levelAltitude(body)           = min(Atmosphere.LevelAltitude 900, 0.4·switchScene)
```
`startHeight = relative = nil`, `collectShells(body)`, `onNeedTerrain(body, true)` (ShipClient prepares the scene: mesh data and profile, so the swap does not stutter).

**`releaseOnSurface()`** (`:321`): phase nil, state SURFACE, heat, turbulence and felt 0. Arrival line:
```
title    = upper(profile.name or body.Name)
subtitle = join({profile.title or "SURFACE", ("%.2f G"):format(SurfaceGravityG) if it is a number,
                 profile.sky.air ? "ATMOSPHERE" : "NO ATMOSPHERE"}, "  ·  ")
```
**`releaseInSpace()`** (`:344`): `restoreShells()`; `blendOut = {from = shownAltitude, t = 0}` if a shown altitude exists; phase nil, state SPACE, heat/turbulence/felt 0, body and relative nil; **`rearmPending = true`** ("not armed at once: we are still inside the entry zone"); `onNeedTerrain(body, false)`.

**`abort() → boolean`** (`:368`), for crashes:
- in **HEAT**: restore shells, state SPACE, phase/body/relative nil, heat/turbulence/felt 0, `landingRequested = false`, shown numbers and blend nil, `rearmPending = true` (else the same entry restarts next frame), `onNeedTerrain(body, false)`; returns **true** (the swap will not happen any more).
- in **DESCENT, LEVEL, CLIMB** (already in the scene): phase nil, state SURFACE, heat/turbulence/felt 0, shown numbers nil; returns false.
- in **EMERGE**: `releaseInSpace()`, shown numbers and blend nil; false. Without phase: false.

**`resetToSpace()`** (`:411`): after a crash on the ground, in the black (the scene is already finished, the replacement ship waits in space): `abort()`; if the state is still not SPACE, `releaseInSpace()`; then phase nil, both requests false, `landReady` false, prompt and arrival nil, shown numbers and blend nil.

#### 2.1.5 `guide(dt, shipCFrame, velocity)` every frame after the thrust step (`:430-621`)

```
if phase == nil: return nil, nil, false
if body == nil or surface == nil: abort(); return nil, nil, false
timer += dt; clock += dt
p = shipCFrame.Position
inSpace = phase ∈ {HEAT, EMERGE}
if inSpace:
    -- the body moves on its orbit (ShipOrbits) and the world shifts (ShipOrigin): work relative to the body
    if relative: p = body.Position + relative; shipCFrame = (p, shipCFrame.Rotation)
    offset = p − body.Position; distance = max(|offset|, 1e-3); up = offset/distance
    height = distance − ShipSurface.surfaceRadius(body, up)        -- radius of the facet under the ship
    fadeShells(distance, SurfaceRadius or distance)                 -- 2.1.7
else:
    up = +Y; height = surface:getAltitude(p)                        -- scene: above terrain or the facet plane
self.height = height

-- the flight direction stays the one of the approach (horizontal)
forward = flat(velocity, up)
if |forward| < 1: forward = flat(look, up); if |forward| < 1e-3: forward = flat(shipUp, up)
forward = |forward| > 1e-6 ? unit(forward) : unit(flat(X, up))
turbulence = 0; released = false

HEAT:
    startHeight = startHeight or max(height, 1.5·switchHeight)
    if height ≤ switchHeight:                                        -- swap; both pictures are equal here
        pose, v = onSwitch("DOWN", body, shipCFrame, velocity)
        if pose == nil: abort(); return nil, nil, false
        restoreShells(); phase = DESCENT; timer = 0; relative = nil; acceleration = 0
        self.height = surface:getAltitude(pose.Position)
        return pose, v, false                                       -- no smoothing step this frame
    down   = height / DescentTau
    target = −up·down + forward·(down·Glide)
    progress = clamp(ln(startHeight/max(height, 1e-3)) / ln(startHeight/switchHeight), 0, 1)
    heat   = entry.heat · smooth(0, HeatRise, clock)
DESCENT:
    if height ≤ levelHeight·LevelStart: phase = LEVEL; timer = 0   -- this frame still uses the DESCENT law
    down = height / DescentTau
    deck = profile.clouds
    if deck and deck.bottom − DeckMargin < height < deck.top + DeckMargin:
        down = min(down, DeckSpeed)                                  -- "slower through the clouds: you should see and feel them"
        turbulence = deck.cover or 0.5
    target = −up·down + forward·(down·Glide)
    heat   = entry.heat · smooth(HeatFadeEnd, HeatFadeStart, height / max(switchScene, 1))
LEVEL:
    heat = 0
    target = up·(−(height − levelHeight)/LevelTau) + forward·ReleaseSpeed
    horizontal = |flat(velocity, up)|
    settled = |height − level| < 0.06·level and |velocity·up| < 15 and |horizontal − ReleaseSpeed| < 60
              and |look·up| < 0.06                                   -- "release only when nose and path are level:
                                                                     --  who then does nothing flies straight on"
    released = settled or timer > LevelTimeout
CLIMB:
    heat = 0
    if height ≥ switchScene:                                         -- swap back to the same place above the body
        pose, v = onSwitch("UP", body, shipCFrame, velocity)
        if pose == nil: abort(); return nil, nil, false
        phase = EMERGE; timer = 0; acceleration = 0; collectShells(body)
        outward = pose.Position − body.Position; relative = outward
        barrier = CollisionRadius or SurfaceRadius or |outward|; radius = SurfaceRadius or barrier
        facet = ShipSurface.surfaceRadius(body, unit(outward))
        startHeight  = max(|outward| − facet, 1)
        emergeHeight = max(barrier + radius·EmergeAltitude − facet, 2·startHeight)
        fadeShells(|outward|, radius); self.height = startHeight
        return pose, v, false
    rise = max(height/ClimbTau, ClimbMinSpeed)
    target = up·rise + forward·(rise·ClimbForward)
EMERGE:
    heat = 0
    rise = max(height/EmergeTau, EmergeMinSpeed)
    target = up·rise + forward·(rise·ClimbForward)
    progress = clamp(ln(max(height, 1)/startHeight) / ln(emergeHeight/startHeight), 0, 1)
    released = height ≥ emergeHeight

-- common: velocity follows softly, the nose follows the flight direction
v' = approach(velocity, target, Response, dt)
p' = p + v'·dt
rot = shipCFrame.Rotation
if |v'| > 1e-3:
    look = (phase == LEVEL) ? forward : unit(v')                     -- levelling: nose on the horizon
    if |look·up| < 0.995: rot = rot:Lerp(lookAt(0, look, up), 1 − e^(−dt/TurnResponse))
if released and phase == LEVEL:                                      -- hand over level even after the timeout
    rot = lookAt(0, forward, up); v' = forward·|flat(v', up)|; p' = p + v'·dt
pose' = (p', rot)
if inSpace: relative = p' − body.Position
acceleration = dt > 0 ? (v' − velocity)/dt : 0
felt = acceleration clamped to length FeltMaxG·Handling.FeltStudsPerG (2.2·9.81 = 21.58)
if released: phase == LEVEL ? releaseOnSurface() : releaseInSpace()
return pose', v', released
```

`phase` inside the branches is the value at the start of the frame (a local).

#### 2.1.6 `update(dt, gravityState, speed)` every frame after the camera (`:627-725`)

`gravityState.altitude` is measured **above the barrier** (`ShipGravity.readBody` uses `CollisionRadius` first); `radius = body.SurfaceRadius or 100`; `landable = body.Landable == true`.

```
-- state changes
if state == SPACE and phase == nil:
    inRange  = landable and altitude < radius·EntryAltitude (1.15)
    hovering = altitude < radius·HoverEntryAltitude (0.35)
    if rearmPending and not inRange: rearmPending = false
    if inRange and hovering and landingRequested and not rearmPending and surface: startEntry(body)
elif state == SURFACE and phase == nil and ascendRequested:
    ascendRequested = false; state = EXIT; phase = CLIMB; timer = clock = 0; blendOut = nil

-- HUD numbers (s = SceneScale 470; real = gravityState.altitude if finite)
if phase == HEAT and height:   blend = smooth(0, ScaleBlend, clock)
                               shownAltitude = logLerp(real or height, height·s, blend); shownSpeed = speed·s^progress
elif phase == EMERGE and height: shownAltitude = max(height, 1)·s; shownSpeed = speed·s^(1 − progress)
elif blendOut:                 blendOut.t += dt; t = smooth(0, ScaleBlend, blendOut.t)
                               shownAltitude = logLerp(blendOut.from, real or 1, t); shownSpeed = nil
                               if t ≥ 1: blendOut = nil; shownAltitude = nil
else:                          shownAltitude = shownSpeed = nil

-- HUD hint
prompt = nil; landReady = false; air = profile and profile.sky.air
if state == SPACE and phase == nil and body:
    blocked = body.LandingBlocked; near = altitude < radius·EntryAltitude·1.6
    if blocked and near:                                  prompt = "LANDING BLOCKED  " .. blocked
    elif landable and not rearmPending and altitude < radius·HoverEntryAltitude:
                                                          prompt = "PRESS " .. key("Land") .. " TO LAND"; landReady = true
    elif landable and not rearmPending and near:          prompt = "DESCEND TO LAND"      -- "L only works close above the barrier"
elif phase ∈ {HEAT, DESCENT}:  prompt = air ? "ATMOSPHERIC ENTRY" : "DESCENT"
elif phase == LEVEL:           prompt = "LEVELLING OFF"
elif phase ∈ {CLIMB, EMERGE}:  prompt = air ? "LEAVING ATMOSPHERE" : "ASCENT"
elif state == SURFACE:         prompt = air ? "CLIMB TO LEAVE ATMOSPHERE" : "CLIMB TO RETURN TO SPACE"
if state == SPACE and not blendOut and phase == nil: profile = nil

-- hull knocks: dense in the glow, lighter in the deck
shaking = max(heat, 0.6·turbulence)
if onHit and phase and shaking > 0.15:
    hitTimer −= dt
    if hitTimer ≤ 0:
        hitTimer = HitIntervalMax − (HitIntervalMax − HitIntervalMin)·shaking      -- 2.2 s … 0.95 s
        onHit(0.35 + 0.8·shaking)                                                  -- ShipAudio:playHit, 0.47 … 1.15
```

`key("Land")` is `ShipKeys.label("Land")` = "L" (`ShipConfig.Keys.Land`, `:2813`).

#### 2.1.7 Air shells in space (`:250-288`)

`SHELLS = {Atmosphere, AtmosphereHaze}`: the ForceField shells a body model carries in space (`03-world.md` §2.2.8: 1.025 R, transparency 0.72 and 1.08 R, 0.86). `collectShells(body)` restores any old list and records `{part, radius, transparency}` per shell (radius = `mesh.Scale.X·ShipSurface.MESH_RADIUS` for a FileMesh, else `Size.X/2`). `fadeShells(distance, radius)`: `above = (distance − shell.radius)/max(radius, 1)`, `visible = smooth(ShellFadeEnd 0.012, ShellFadeStart 0.06, above)`, `Transparency = 1 − (1 − original)·visible` ("via Transparency, not LocalTransparencyModifier: ShipImpostors drives that for its doubles"). The shells are fully visible from 0.06 R above them and gone 0.012 R above them, i.e. before the swap. `restoreShells()` writes the original transparencies back.

#### 2.1.8 Port verdict

- **Keep:** the guide law, all constants, the phase names (callouts and HUD use them), the release conditions, the felt-acceleration clamp, the prompts, the arrival line, the knocks.
- **Change:** the assist is optional (D14) and engaged from the ACTION button; heights near the ground are absolute metres; HEAT ends at the absolute Roblox switch altitude `hs` (no swap); CLIMB and EMERGE become the optional ORBIT assist; the state (SPACE/ENTRY/SURFACE/EXIT) is derived from altitude and motion when no assist runs (8.5).
- **Drop:** `onSwitch`, the shells (`collectShells`/`fadeShells`), `relative` (the port carries the ship in the body frame, `02-flight.md` §9.4), the shown-number blending (`getShownAltitude/Speed`, `progress`, `blendOut`: real numbers), `getCover`, `markTerrainReady`, the 3 s request window and `rearmPending` for the button (the button is only shown when it works).

### 2.2 ShipSurfaceProfiles: how a body looks from below (`ShipCore/ShipSurfaceProfiles.lua`) · **port 1:1 as data (shared with `04-planet-surface.md` §2.6)**

**Purpose** (header `:1-31`): sky, cloud deck, light and entry glow per body. Sol bodies have hand-made profiles; generated bodies derive theirs from class and attributes (`HasAtmosphere`, `AtmosphereColour`). `04-planet-surface.md` §2.6 has the same data with the terrain view (titles pick the terrain style); `world/SurfaceProfiles.kt` is one file for both areas.

**API.** `forBody(body) → profile` (`:232`), always complete:
```
profile = defaults()                                      -- :38, an airless body
name  = DisplayName or Name; class = BodyClass or "Rocky Body"; seed = Seed or 1
root  = topmost ancestor Model; systemName = root.SystemName
if SOL[name] and (systemName == nil or systemName == "Sol"): merge(profile, SOL[name])   -- deep merge, :63
else: profile.title = TITLES[class or Rocky][(seed mod #titles) + 1]
      if body.HasAtmosphere == true: airFor(body, class, profile)                       -- :200
profile.name, .class, .seed = name, class, seed
```

**Fields and defaults** (`defaults`, `:38`):

| Field | Default | Meaning (consumer) |
|---|---|---|
| `title` | "ROCKY SURFACE" | ground type in the arrival line (and terrain style, 04) |
| `sky.air` | false | air: dome, Atmosphere object, fog, clouds possible; arrival line "ATMOSPHERE" |
| `sky.colour` | 190,214,244 | `Atmosphere.Color` (air colour) |
| `sky.decay` | 92,120,160 | `Atmosphere.Decay` (colour away from the sun) |
| `sky.density` | 0.3 | `Atmosphere.Density` before `Sky.FogDensityScale` 0.4 |
| `sky.haze` | 1.6 | `Atmosphere.Haze` before `Sky.FogHazeScale` 0.3 |
| `sky.glare` | 0.25 | `Atmosphere.Glare` |
| `sky.skybox` | "space" | sky image by day: "day" blue day sky under the clouds (haze above them), "haze" white haze sky tinted by the air, "space" stars. Night always stars |
| `sky.thickness` | 24000 | scene metres above ground where the air is gone |
| `sky.brightness` | 2.6 | `Lighting.Brightness` by day on the ground |
| `sky.ambient` | 64,64,70 | ambient by day |
| `sky.nightAmbient` | 14,15,20 | ambient at night |
| `sky.stars` | 3000 | star count at night |
| `sky.zenith` | 70,128,214 | dome colour by day (air only) |
| `sky.dusk` | 232,126,80 | dome colour at dusk |
| `clouds` | nil | `{bottom, top (m above ground), cover 0..1, colour, dark (shadow share underneath)}` |
| `entry.heat` | 0 | entry glow 0..1 |
| `entry.glow` | 255,140,60 | entry glow colour |

**Sol** (`SOL`, `:81-167`), used only in Sol. Only the fields this area reads; titles and the airless bodies' ambient/brightness overrides are in `04-planet-surface.md` §2.6.2.

| Body | colour / decay | density / haze / glare | skybox | thickness | brightness | ambient / night | stars | zenith / dusk | clouds bottom–top, cover, colour, dark | entry heat, glow |
|---|---|---|---|---:|---:|---|---:|---|---|---|
| Earth `:83` | 190,214,244 / 92,120,160 | 0.32 / 1.6 / 0.3 | day | 26,000 | 2.6 | 108,124,146 / 16,20,30 | 2,600 | 70,128,214 / 236,128,84 | 2,100–2,900, 0.56, 255,255,255, 0.3 | 1, 255,136,56 |
| Mars `:95` | 222,166,124 / 124,70,44 | 0.4 / 6 / 0.12 | haze | 22,000 | 2.3 | 120,86,68 / 24,16,14 | 2,400 | 212,152,106 / 120,150,196 ("butterscotch by day, blue dusk as on the real Mars") | 1,900–2,500, 0.2, 236,214,200, 0.2 | 0.6, 255,150,90 |
| Titan `:108` | 214,146,62 / 120,70,22 | 0.6 / 7.5 / 0 | haze | 36,000 | 1.4 | 118,86,50 / 22,14,8 | 600 | 198,130,58 / 146,76,34 | 2,300–3,300, 0.72, 226,166,96, 0.35 | 0.75, 255,150,70 |
| Venus `:157` | 232,196,128 / 140,96,44 | 0.55 / 8 / 0 | haze | 40,000 | 1.5 | 128,100,60 / 30,20,10 | 0 | 214,170,96 / 170,96,40 | 2,600–4,200, 0.92, 236,206,140, 0.45 | 1, 255,150,60 ("closed yellow deck, gloomy light, no stars, glowing entry") |
| Luna, Mercury, Io, Europa, Ganymede, Callisto, Triton | defaults (no air) | | | | Mercury 3.0, Europa 2.2, Triton 1.8 | own ambient (04) | 3,000 | | none | 0 |

**Generated bodies** (`TITLES :172`, `CLOUDS :187`, `airFor :200`): titles by class (04 §2.6.3). With `HasAtmosphere`:
```
colour  = AtmosphereColour or RGB 150,180,230
blueish = colour.B > 1.15·colour.R                   -- blue: day sky under the clouds; else white haze sky tinted by the air
sky = { air = true, colour = lerp(colour, white, 0.45), decay = scaled(colour, 0.5), density = 0.34,
        haze = blueish ? 1.8 : 5.5, glare = 0.2, skybox = blueish ? "day" : "haze", thickness = 26000,
        brightness = 2.4, ambient = lerp(colour, black, 0.45), nightAmbient = scaled(colour, 0.1), stars = 2400,
        zenith = lerp(colour, black, 0.25), dusk = RGB 232,126,80 }
entry  = { heat = 0.85, glow = RGB 255,140,64 }
clouds = copy(CLOUDS[class] or CLOUDS["Rocky Body"]); unless class == "Volcanic Body": clouds.colour = lerp(clouds.colour, colour, 0.15)
```
Cloud decks by class: Ocean 2,200–3,000 / 0.62 / white / 0.3; Terrestrial 2,100–2,900 / 0.52 / white / 0.3; Ice World 1,900–2,600 / 0.42 / 236,244,250 / 0.22; Volcanic 2,000–2,900 / 0.5 / 120,104,96 / 0.45; Rocky (and any other) 1,900–2,500 / 0.25 / 232,222,210 / 0.2; Gas Giant 2,600–4,400 / 0.88 / 236,214,178 / 0.5; Ice Giant 2,400–4,000 / 0.8 / 214,238,244 / 0.4; Greenhouse 2,600–4,200 / 0.92 / 236,206,140 / 0.45.

**Port:** as data, exactly (04 owns the file). Note: Titan's profile has air but the Titan body has no air shell in space (`04-planet-surface.md` Q12); this spec proposes to give every body with `sky.air` a limb (8.7, question Q5).

### 2.3 ShipSky: light, air and sky during a landing (`ShipClient/ShipSky.lua`) · **port: `SkyModel` (formulas) + a profile sky in the body shader**

#### 2.3.1 Purpose (header `:1-32`)

What one sees depends on three things. **Sun elevation:** day, dusk, night; Roblox's sun is set via `ClockTime` to the elevation of the light (`solveSun`), ShipSurface turns the scene so the azimuth matches too; "at night it really gets dark". **Height:** above, space light still applies, below the body's light (`SpaceRampEnd`); the air (Roblox `Atmosphere` object) thickens downwards; bodies without air also get the light transition, only without haze. **Body:** colour, density, haze and thickness of the air come from the profile. A luminous opaque dome in the colour `sky.zenith` (blue, butterscotch, orange) with its own sun disc paints the day sky; it turns black upwards and transparent at night (stars). Roblox's haze blends everything far, the dome too, into the colour of the **sky image behind it**; by day a bright image is therefore placed there (blue or white, the air tints it) instead of the black space image. A black sky by day exists only without air. In the cloud deck the air becomes almost opaque and takes the cloud colour: that is the flight through the clouds, and that is also when the sky image is swapped, so the swap is never seen.

`ShipClouds` is required optionally (`:42-53`): if it is missing or fails, there are no clouds; "the landing itself must never fail because of them".

#### 2.3.2 API and state

| Function | Line | Behaviour |
|---|---|---|
| `ShipSky.solveSun(elevation) → direction` | `:137` | `GeographicLatitude = 23.5` (at 23.5° Roblox's sun stands in the zenith at noon and 43° below the horizon at midnight); bisection on `ClockTime ∈ [12, 23.999]`, 28 steps, for `sunDir.Y = clamp(elevation, Y(23.999), Y(12))`; returns Roblox's sun direction |
| `new(camera)` | `:159` | `active` false, `skyKind` "space", `domeOpacity` 0, `whiteout` 0, `day` 1; saved lighting nil |
| `capture()` | `:187` | once: saves `Lighting.ClockTime, GeographicLatitude, Brightness, Ambient, OutdoorAmbient, FogColor, FogStart, FogEnd` and the Sky object's `StarCount, CelestialBodiesShown, SunTextureId, SunAngularSize, MoonAngularSize` and six faces. "MUST run before solveSun" |
| `begin(profile, folder, ctx)` | `:221` | ctx: `seed, radius (scene ground radius), toBodyDir, eye, height, sunSize (deg)` |
| `update(dt, state)` | `:286` | every frame after the camera; state: `eye, height, sunDir, groundK, sight, exit` |
| `setSkyKind(kind)` | `:447` | writes the six faces of "day", "haze" or the saved space faces |
| `domeReach(direction) → distance` | `:464` | eye → dome along a unit direction, ∞ without a dome |
| `transform(T)` | `:483` | scene re-anchored: moves the clouds |
| `getWhiteout()`, `getDay()`, `isDaySky()` (kind ≠ space), `getSkyKind()`, `isActive()` | `:493-508`, `:178` | |
| `finish()` | `:515` | destroys clouds, the Atmosphere object and the dome, restores the saved lighting and sky; "deliberately without precondition: a forgotten Atmosphere would leave the sky blue in space" (`ShipSurface:sweep` calls it every space frame) |

**`begin`:** `capture()`; active; profile; `skyKind = "space"`; dome opacity and whiteout 0. Sky object: `CelestialBodiesShown = true`, `SunTextureId = Sky.SunTexture` ("rbxasset://sky/sun.jpg"), `SunAngularSize = clamp(sunSize·SunScale 3.2, SunMin 4, SunMax 40)` (the star's real angular diameter ×3.2, at least 4°), `MoonAngularSize = 0`, `StarCount = sky.stars`. Old "ShipAtmosphere" objects are removed; with air a new `Atmosphere` is created (`Density, Haze, Glare, Offset` 0, `Color = sky.colour`, `Decay = sky.decay`). With air: a dome ball and a sun ball (Neon spheres, the planet sphere mesh, invisible until `update`). `groundRadius = ctx.radius`. With a deck: `ShipClouds.new(folder, profile.clouds, ctx)`.

#### 2.3.3 `update(dt, state)` (`:286-444`)

```
height = max(state.height, 0); sun = state.sunDir (scene light direction)
day    = smooth(Sky.DayStart −0.10, Sky.DayFull 0.12, sun.Y)                   -- sun.Y = sine of the elevation
T      = max(sky.thickness or 24000, 1)
ground = 1 − smooth(0, T·SpaceRampEnd 0.55, height)                            -- 1 = body light, 0 = space light
air    = (1 − clamp(height/T, 0, 1))^DensityCurve 1.6

groundAmbient    = lerp(sky.nightAmbient, sky.ambient, max(day, NightLight 0.3))  -- night keeps the ground readable
groundBrightness = lerp(NightBrightness 0.5, sky.brightness, day)
Lighting.Brightness     = lerp(saved.Brightness, groundBrightness, ground)
Lighting.Ambient        = lerp(saved.Ambient, groundAmbient, ground)
Lighting.OutdoorAmbient = lerp(saved.OutdoorAmbient, groundAmbient, ground)

whiteout = clouds ? clamp(clouds:whiteout(eye), 0, 1) : 0                      -- after clouds:update(dt, eye, height, groundK, sight,
                                                                               --   {sunDir = sun, day, ambient = groundAmbient})
if air (Atmosphere object):
    glow = NightGlow 0.3 + 0.7·day                                             -- the air does not glow at night
    thin = air·(NightFogShare 0.35 + 0.65·day)                                 -- thinner at night (Roblox night air dyes everything black)
    cloudColour = profile.clouds ? clouds.colour : sky.colour
    Density = lerp(sky.density·FogDensityScale 0.4·thin, WhiteoutDensity 0.92, whiteout)
    Haze    = lerp(sky.haze·FogHazeScale 0.3·thin, WhiteoutHaze 6, whiteout)
    Glare   = sky.glare·air·day·(1 − whiteout)
    Color   = lerp(scaled(sky.colour, glow), scaled(cloudColour, glow), whiteout)
    Decay   = scaled(lerp(sky.decay, sky.colour, DecayLift 0.35), glow)         -- lighter away from the sun
```
FogDensityScale and FogHazeScale exist because "the scene is SceneScale times larger: with the full profile values the ground vanished in haze after a few hundred studs".

**The dome** (air only). Roblox's atmosphere does not colour the sky; over the star image the day sky would stay black, so a luminous flat ellipsoid around the eye colours it. Opaque by day ("half transparent Roblox draws it over clouds and moons, which all sit around the eye"), transparent only at dusk, gone at night (stars). Horizontally it reaches beyond the horizon of the ground copy, upwards only just above the clouds ("far away Roblox's haze darkens it"). It turns black upwards (space waits).
```
opacity = smooth(DomeClearDay 0.02, DomeSolidDay 0.2, day)
if opacity < 0.01: hide dome and sun disc
else:
    top    = min(T, state.exit)                                             -- exit altitude (Earth 8 km)
    fade   = 1 − smooth(DomeFadeLow 0.3·top, DomeFadeHigh 0.95·top, height)  -- Earth: full below 2.4 km, black above 7.6 km
    tint   = lerp(sky.zenith or sky.colour, sky.dusk or DUSK, (1 − day)·DomeDusk 0.45)
    Color  = scaled(tint, (DomeNightShade 0.35 + 0.65·day)·fade); Transparency = opacity > 0.99 ? 0 : 1 − opacity
    horizon = groundK·√(2·height·groundRadius + height²)
    limit  = min(DomeMax 80000, sight·DomeSight 0.95)                       -- never beyond the drawing distance
    least  = min(DomeMin 3000, limit)
    a = clamp(horizon·DomeMargin 1.25, least, limit)                         -- horizontal semi-axis
    above = deck ? deck.top − height + DomeCloudGap 1500 : 0
    b = clamp(max(DomeTop 6000, above, height·DomeBelow 2.5), least, a)      -- vertical semi-axis
    mesh.Scale = (−a, −b, −a)/MESH_RADIUS (negative scale turns the faces inwards); centred on the eye
    sun disc, only when solid (opacity > 0.99) and sun.Y > −0.03:
        distance = domeReach(sun)·DomeSunShare 0.8; diameter = 2·distance·tan(DomeSunSize 2.2°/2)
        colour DomeSunColour 255,244,222, opaque, at eye + sun·distance
domeReach(d) = 1/√max((d.x² + d.z²)/a² + d.y²/b², 1e-12)
```

**The sky image** behind the dome. Bright only while the dome covers it: then only its colour is seen, the one the haze blends everything far into, and the swap stays invisible. When the dome becomes transparent at dusk, the star image comes back first. Between blue and haze it is swapped only hidden (in cloud) or after passing the deck without cloud.
```
deck = profile.clouds; bottom = deck ? deck.bottom : 0.1·T; top = deck ? deck.top : bottom
lit  = (skyKind == "space") ? opacity > 0.99 : opacity > SkyHold 0.95
want = "space"
if sky.air and lit:
    below = height < (bottom + top)/2
    want  = (sky.skybox == "day" and day ≥ DaySkyMin 0.35 and below) ? "day" : "haze"
if want ≠ skyKind:
    if want == "space" or skyKind == "space": setSkyKind(want)
    else: hidden = whiteout ≥ SwapWhiteout 0.6; past = (want == "day") ? height < bottom : height > top
          if hidden or past: setSkyKind(want)
StarCount = (skyKind == "space") ? sky.stars : 0
```
Faces: "day" = Roblox's built-in `sky512_*.tex`; "haze" = `white.png` on all six; "space" = the saved faces.

#### 2.3.4 The haze colour Roblox really draws (`ShipFarTerrain.lua:700-737`, measured in Play)

Because Roblox's fog blends into the sky image, the effective far colour is not `Atmosphere.Color`. The far-field terrain reproduces it (2026-11-07, "Ultra"): under the **day** sky the fog colour is `FieldFogDay` **RGB 46,105,167** (Earth in Play); under the **haze** sky it is the air colour desaturated by `FieldHazeGrey` 0.35 (`c + (luma(c) − c)·0.35`, luma = 0.299 R + 0.587 G + 0.114 B); multiplied by `NightGlow + (1 − NightGlow)·day`. The e-fold distance of Roblox's fog is `FieldHazeK 454 / Atmosphere.Density` studs (measured on Mars: 2.9 km), clamped to [300, 45000]: at the ground by day Earth 3.5 km, Mars 2.8 km, Venus 2.1 km, Titan 1.9 km (`04-planet-surface.md` §2.3.4). The port uses both facts (8.7).

#### 2.3.5 Port verdict

- **Keep:** every formula of 2.3.3 (day, ground, air, glow, thin, density, colours, dome colour and opacity, sun disc, the sky-kind rules) as the input of the port's sky shader and light; stars per profile.
- **Replace:** `Lighting`/`Atmosphere`/Sky objects, `solveSun` (the port uses the true star direction), `capture`/`finish` (the port's sky is a pure function of state), the dome mesh (a per-pixel formula), the sky images (a colour choice), `transform`. Section 8.7 gives the port model.

### 2.4 ShipClouds: the cloud deck (`ShipClient/ShipClouds.lua`) · **port: `CloudField` + `CloudDeckImage` (worker) + `CloudRenderer` + `CloudPuffs`**

#### 2.4.1 Purpose (header `:1-95`)

"The cloud deck you dive through when landing." The intended sequence (the feel of breaking through clouds in a well-known open-world game, not its look): a bright deck rushes up from below, everything turns white, shreds tear past, and under the deck you break out: the land lies below, the deck hangs dark above. Three parts:

1. **The deck.** Two spherical shells around the planet centre, the top side at `top`, the underside at `bottom` (metres above ground). Both show the same picture: the same cloud, lit from above and shadowed from below.
2. **The shreds (puffs).** Big soft particles in the band, placed in cells ahead of the camera. They stay put; the ship flies through them.
3. **The white.** `whiteout()` tells the caller how deep the camera is in thick cloud; fog and air are the caller's job (ShipSky).

Roblox-only reasons for the geometry (all dropped in the port): an `EditableImage` only draws on a `MeshPart`, which ends at 2048 studs, so the deck lies on a **hemisphere around the eye** (1000 studs) whose corners stand fixed in their view directions; every frame `update()` computes for each corner where its ray hits the deck shell and which image texel lies there. Direction by direction this gives the same picture as a real deck two kilometres up, to the horizon and with the planet's curvature. Because a near dome painted clouds over mountains ("Clouds go trough mountains", 2026-11-04), the dome sits at 1000 studs and below `BandElevation` 32° eight band pieces behind the whole terrain (`BandRadius` 2450) show the deck, so terrain occludes the clouds pixel by pixel. EditableMesh memory limits forced "fixed copies" (`fixedCopy`). Vertex colours carry the light (one colour per ring: day, night, sunset, haze towards the horizon); the single normal is turned into the engine's light each frame so the engine always lights the dome fully.

**Glued to the ground:** the noise hangs on `toBodyDir`, the planet-fixed direction. The image is a tangent plane around the camera (gnomonic, denser towards the centre). When the camera has moved `RegenDistance` from the image centre, a new image is computed in the background around it and swapped when complete; both colour the same direction the same, so the swap is invisible.

#### 2.4.2 API

| Function | Line | Behaviour |
|---|---|---|
| `new(folder, data, ctx)` | `:370` | `data = profile.clouds {bottom, top, cover, colour, dark}`, `ctx = {seed, radius, toBodyDir(scenePoint) → unit, eye}` |
| `update(dt, eye, height, groundK, sight, light)` | `:920` | `light = {sunDir, day 0..1, ambient, haze?}`; steps the image job, regenerates, sizes the dome, tints, places the dome, spawns puffs |
| `whiteout(eye, height) → 0..1` | `:1343` | `densityAt(eye).core` (height unused) |
| `alphaAt(x, z)`, `cloudAlpha(eye, dir)` | `:1281`, `:1324` | coverage under a scene XZ point; opacity in front of a sky body. **No caller in the game** |
| `transform(T)` | `:1355` | scene re-anchored: centre, image frames, last eye, velocity, puff cells move by T |
| `isReady()` | `:1375` | image done and dome built (or failed) |
| `getStatus()`, `destroy()` | `:1380`, `:1412` | |

#### 2.4.3 Construction (`new`, `:370-441`)

```
radius = ctx.radius or 1e6                  -- scene ground radius (Earth 996,400)
seed   = ctx.seed or 1
bottom = data.bottom or 2000; top = max(data.top or bottom + 800, bottom + 50)
cover  = clamp(data.cover or 0.5, 0, 1); colour = data.colour or white
underside = data.dark is a Color3 ? data.dark : lerp(colour, black, clamp(data.dark or Underside 0.3, 0, 1))
centre = (0, −radius, 0)                     -- scene coordinates; transform() moves it
topRadius = radius + top; bottomRadius = radius + bottom; refRadius = radius + (top + bottom)/2
field = makeField(seed, radius); tau = threshold(field, cover, seed); rng = Random(seed + 31)
bandSplit = clamp(floor(DomeRings·(1 − (BandElevation/90)^(1/DomeCurve)) + 0.5), 1, DomeRings − 1)   -- = 10
cells: buildCells(); startDeck(ctx.eye or 0); buildDome() (background task: dome, then 8 band pieces)
```

#### 2.4.4 The noise field and the cover threshold

**`makeField(seed, radius)`** (`:206`) returns `field(d)` for a planet-fixed unit direction `d`:
```
rng = Random(seed); ox, oy, oz = rng:NextNumber(−600, 600) ×3
scale = radius / FeatureSize 6500                         -- largest cloud fields 6.5 km on the scene planet
x0, y0, z0 = d·scale + (ox, oy, oz)
-- swirl: warped shapes instead of round blobs
wx = noise(0.37x0 + 11.3, 0.37y0 − 7.1, 0.37z0 + 3.7)
wy = noise(0.37x0 − 5.9, 0.37y0 + 13.3, 0.37z0 − 9.4)
wz = noise(0.37x0 + 2.2, 0.37y0 + 6.6, 0.37z0 + 15.5)
(x, y, z) = (x0, y0, z0) + Swirl 0.55·(wx, wy, wz)
fbm = Σ_{o=0..4} noise(x·f, y·f, z·f)·a / Σa,  a = Gain 0.5^o, f = Lacunarity 2.07^o   -- "not a clean multiple, else the pattern repeats"
field = fbm + Patchiness 0.12·noise(0.17x0 + 23.1, 0.17y0 − 3.3, 0.17z0 + 8.8)        -- large scale: open sky here, closed deck there
```
The same function fills the image and answers `whiteout()`: a gap in the deck is a gap in the band too.

**`threshold(field, cover, seed)`** (`:235`): the noise value exceeded by exactly the share `cover` of the surface, measured on `CalibrationSamples` 1200 random directions (`Random(seed + 7919):NextUnitVector()`), sorted; `tau = values[clamp(floor((1 − cover)·1199) + 1, 1, 1200)]`. The noise is equally distributed everywhere, so the threshold holds for every deck of the body.

**Coverage** (`coverageAt(point)`, `:1254`): `n = field(unit(toBodyDir(point)))`; `alpha = smooth(tau − Softness 0.07, tau + 0.07, n)`; `thick = clamp((n − tau)/ThickRange 0.22, 0, 1)`.

**Density in the band** (`densityAt(point)`, `:1270`):
```
height = |point − centre| − radius
alpha  = coverageAt(point).alpha
depth  = min(smooth(top + WhiteoutLead 200, top − WhiteoutRamp 0, height), smooth(bottom − 200, bottom + 0, height))
return core = alpha·depth, alpha, height
```
`depth` is 1 inside [bottom, top] and falls to 0 within 200 m outside. "The white starts before the deck fades out in front of the band (WhiteoutLead > Margin) and is full right behind it. Together, white and deck never cover a dense spot below about 80 %: the deck turns into white instead of briefly showing the ground. Where the deck has a gap, the band stays clear too." `whiteout(eye) = core`.

#### 2.4.5 The deck image (`startDeck :618`, `fillDeck :686`, `stepJob :755`, `finishDeck :779`)

```
startDeck(eye):
    image 512² (on memory failure: retry after RetryDelay 12 s; until then only puffs and white)
    up    = unit(eye − centre)
    hint  = old deck ? old frame X axis : X;  axis = unit(hint − up·(hint·up)) (fallback Z − up·up.z)  -- X follows the old deck
    frame = (origin centre + up·refRadius, X = axis, Y = up)                                              -- tangent plane
    grid: cells = 512/GridStep 32 = 16, stride 17, half = Width/2 = 50,000, step = Width/16 = 6,250
          for j, i in 0..16: g[j][i] = unit(toBodyDir(centre + Y·refRadius + X·(−half + i·step) + Z·(−half + j·step)))
          ("on 6 km the direction turns by a third of a degree; the interpolation error stays far below a texel")
    job = coroutine fillDeck(deck), stepped with FrameBudget 0.002 s per frame
fillDeck: buffer pre-filled with 255 (white, so filtered edges never darken)
    for py, px in 0..511:
        du = (px + 0.5)/512 − 0.5; dv = (py + 0.5)/512 − 0.5; rho = √(du² + dv²)
        r  = half·(2·rho)^Warp 1.35                       -- texels denser towards the centre (where the camera is)
        alpha = 0
        if r < half:
            k = r/rho; cx = (du·k + half)/step; cz = (dv·k + half)/step   -- grid cell, bilinear weights
            d = normalize(bilinear(g, cx, cz)); n = field(d)
            if n > tau − 0.07:
                alpha = smooth(tau − 0.07, tau + 0.07, n)
                if r > EdgeFade 0.55·half (27.5 km): alpha *= 1 − smooth(27.5 km, 50 km, r)
                thick = (n − tau)/0.22
                if thick > 0: RGB = 255·(1 − Shade 0.3·min(thick, 1))   -- thick cores darker (seen from below; from above the sun outshines it)
        A = alpha·255 + 0.5
finishDeck: WritePixelsBuffer; the old image is destroyed 1 s later (until the GPU has the new one)
regeneration (update): no job running and (no deck yet and past blockedUntil, or the eye's plane coords (planeXZ)
    are behind the plane or farther than RegenDistance 9,000 from the image centre) → startDeck(eye)
```
**Plane ↔ image** (`planeXZ :836`, `planeToUV :251`): for a point `q` relative to the planet centre, `height = q·Y`; behind the plane (`≤ 0`) none; else `k = refRadius/height`, `(x, z) = (q·X·k, q·Z·k)` (gnomonic). `r = √(x² + z²)`; `rho = r < half ? 0.5·(r/half)^(1/Warp) : OutsideRho 0.66`; `uv = clamp(0.5 + (x, z)·rho/r, 0.002, 0.998)`.

#### 2.4.6 View, tints and the dome per frame (`update :920`, `viewAt :879`, `tints :895`, `updateDome :964`, `updatePiece :1034`)

```
update(dt, eye, height, groundK, sight, light):
    stepJob(); releaseRetired(); regenerate if needed (2.4.5)
    if sight > 0: domeRadius = min(DomeRadius 1000, sight·DomeSightShare 0.4)
                  bandRadius = max(domeRadius, min(BandRadius 2450, sight·BandSightShare 0.9))
    up = unit(eye − centre); (topTint, bottomTint, hazeTint) = tints(light, up)
    (view, fade) = viewAt(eye); updateDome(eye, view, fade); updatePuffs(dt, eye)
viewAt(eye):  above = |eye − centre| − topRadius;    if above > 0: return "top",    smooth(0, Margin 160, above)
              below = bottomRadius − |eye − centre|; if below > 0: return "bottom", smooth(0, 160, below)
              return nil, 0                                  -- inside the band: puffs and white take over
tints(light, up):
    day = clamp(light.day or 1); elevation = light.sunDir ? unit(sunDir)·up : 1
    level = NightLevel 0.16 + 0.84·day
    top = scaled(colour, level); bottom = scaled(underside, level)
    warmth = day·(1 − smooth(0.04, 0.32, elevation))·SunsetWarmth 0.45          -- sun low but still day
    if warmth > 0: top = lerp(top, top·SUNSET, warmth); bottom = lerp(bottom, bottom·SUNSET, 0.6·warmth)   -- SUNSET = (1, 0.7, 0.48)
    if light.ambient and day < 1: night = (1 − day)·NightAmbient 0.5; top, bottom = lerp(·, ambient, night)
    haze = light.haze or scaled(DEFAULT_HAZE 196,212,232, level)                 -- ShipSky passes no haze
```

**Dome geometry** (`buildPiece :278`): rings 0 (zenith point) … `DomeRings` 22 (horizon), ring elevation `(π/2)·(1 − ring/22)^DomeCurve 1.7` (denser towards the horizon, "where the distance to the deck changes fastest"), `DomeSegments` 40 per ring; wound inwards; one colour per ring, one normal. Band pieces: rings `bandSplit` (10, = 32.1°) … 22, 8 sectors of 5 segments.

**Per frame** (`updateDome`): no image, no view or `fade ≤ 0.001` → hide. Else the placement is the eye with Y = up and X = the image X axis projected on the horizontal plane; for the **top** view the dome is turned upside down (looks down). `transparency = AlphaPass 0.02 + 0.98·(1 − fade)`. For every vertex direction `u` (world):
```
e = eye − centre; shell = top view ? topRadius : bottomRadius
b = e·u; cc = (|e| − shell)(|e| + shell); disc = b² − cc            -- stable form, no cancellation
top view:    t = (disc ≥ 0 and b < 0) ? cc/(√disc − b) : −1         -- near hit from above
bottom view: t = disc ≥ 0 ? (b > 0 ? −cc/(b + √disc) : √disc − b) : −1   -- hit from inside
if t > 0 and hit in front of the image plane: uv = planeToUV(planeXZ(e + u·t))
else: uv = radial at OutsideRho (transparent rim) in the direction of u projected on the plane; t = −1
             ("a triangle between hit and miss then does not reach across the image")
ring colour (from the ring's first vertex): amount = t > 0 ? 1 − e^(−t/HazeDistance 45000) : 1
    colour = lerp(tint, hazeTint, amount·HazeColour 0.55); alpha = 1 − amount·HazeAlpha 0.35
    (with the band: dome rings > bandSplit and band rings ≤ bandSplit are alpha 0, so the strip between rings 10 and 11 crossfades;
     the dome computes rings only up to bandSplit + 1)
tint = topTint for the top view, bottomTint for the bottom view
```
Material Fabric (no glint), `Color` white, the vertex colours carry the light.

#### 2.4.7 Puffs (`buildCells :576`, `updatePuffs :1140`, `spawnCell :1182`)

16 cells (invisible anchored boxes 460 × 240 × 460) with one emitter each (`smoke_main` texture, volume emission outward, `LockedToPart` so `transform` can move them, facing the camera, `LightInfluence` 0, `Brightness` 1, speed 0–3, any direction, rotation 0–360, spin ±6°/s, lifetime 4.5–6.5 s).
```
updatePuffs(dt, eye):
    step = eye − lastEye; moved = |step|; if moved > PuffJump 3000: moved = 0      -- a teleport, not travel
    elif dt > 0: velocity = lerp(velocity, step/dt, 1 − e^(−5·dt))
    travel += moved; sinceSpawn += dt
    every 0.25 s: live cells (age < 6.5 s) recoloured with puffColour(cell.height)
    height = |eye − centre| − radius
    if height < bottom − PuffReach 900 or height > top + 900: return
    if travel < PuffSpacing 190 and sinceSpawn < PuffIdleInterval 0.9: return
    travel = sinceSpawn = 0; spawnCell(eye)
spawnCell(eye):
    speed = |velocity|; dir = speed > 12 ? velocity/speed : unit(r − up·(r·up)·0.7) with r = random unit   -- mostly horizontal
    lead = clamp(speed·PuffLead 0.85, PuffLeadMin 140, PuffLeadMax 720)
    side = unit(dir × up) (fallbacks dir × X, dir × Z); lift = unit(side × dir); spread = 0.3·460 = 138
    centre = eye + dir·lead + side·U(−138, 138) + lift·U(−69, 69)
    core, alpha, height = densityAt(centre); nearBand = bottom − 160 < height < top + 160
    wisp = core < 0.22
    if wisp and not (nearBand and alpha > 0.08): return             -- nothing in clear air
    cell = next of 16 (round robin); clear; frame at centre, Y = up, X = side flattened
    size = wisp ? WispSize 55 : PuffSize 120; opacity = wisp ? 0.12 + 0.2·alpha : 0.3 + 0.4·core; base = 1 − opacity
    Size keys: t 0 → 0.7·size (±0.2·size), t 1 → 1.2·size (±0.25·size)
    Transparency keys: 0 → 1, 0.18 → base, 0.7 → base, 1 → 1
    Color = puffColour(height) = lerp(bottomTint, topTint, clamp((height − bottom)/(top − bottom)))
    emit count = wisp ? WispBurst 4 : max(2, floor(PuffBurst 14·core + 0.5))
```

#### 2.4.8 Port verdict

- **Keep 1:1:** `makeField`, `threshold`, `coverageAt`, `densityAt`, `whiteout`, `tints`, the image algorithm (gnomonic plane, warp, edge fade, shading, regeneration distance), the ray–shell maths and the ring haze, the puff rules.
- **Replace:** the EditableImage/EditableMesh machinery, the coroutine budget (a worker thread), the band (depth testing), `fixedCopy`, `transform` (no re-anchoring), the Roblox emitters (a particle pool). `alphaAt` and `cloudAlpha` are not ported (no caller).
- **Scale:** `scale = R/6500` with the port's real radius keeps the 6.5 km feature size; decks keep their metre heights (D17).

### 2.5 ShipEntryFx: plasma, glow and roar (`ShipClient/ShipEntryFx.lua`) · **port: `EntryFx` (pure) + `EntryFxRenderer` + `HeatEdge` (UI)**

#### 2.5.1 Purpose (header `:1-58`)

When the ship dives nose first into the upper atmosphere, a glowing plasma cushion builds up in front of it. This module shows it, purely local and visual. When and how strong is given by the caller as `heat` 0..1; nothing is computed here. A controller (the guidance) keeps the nose on the velocity vector: ship −Z is the flight direction, the air flows towards +Z in ship space. Layers (all follow heat, all flicker):

| Layer | What |
|---|---|
| Mantle (Cap) | flames on the nose and leading edges (`ParticleEmitter`, `LightEmission` 1); the leading edge is measured from the ship model (`setHull`), one anchor per column of the span |
| Flow | flames tearing off backwards at the outer leading edges, only outside the canopy and bridge |
| Streaks | fast glow streaks (`VelocityParallel`) and sparks from a ring in front of the nose, plus fixed light rails (Beams) on which glow races backwards; everything passes outside canopy, bridge and cockpit, never through them |
| Bow shock | soft light spots (additive Beams): hot core on the nose, shock front and a big veil in front, glow at the wing tips |
| Light | two flickering point lights: in front of the seat (colours the cockpit) and in front of the nose (colours the hull) |
| Edge | glow creeping in from the screen edge (`StarterGui.EntryFx`, built by `EntryFxInstaller`); without the GUI the rest runs |
| Sound | wind roar and Josh's "Machine Rumble"; level and pitch follow heat, smoothly in and out |
| Shake | `getShake()`: layered noise for the camera, × setting CAMERA SHAKE |

Everything hangs under the camera (never replicated) and follows `shipCFrame` every frame. The floating origin keeps the ship at the world origin, so a world motion that would stretch particles into streaks does not exist; all emitters are `LockedToPart` and the flow comes only from their velocity in ship space. Built once in `new()`; `update()` only sets properties; sequences only on visible change. At heat 0 emitters, beams, lights and edge are off; the sound fades in about 0.3 s and stops.

Integration: `entryFx = ShipEntryFx.new(playerGui)`; `entryFx:setHull(hull:getModel())` after every hull build; `entryFx:update(dt, shipCFrame, heat, ShipEntryFx.colourFor(body))` after the camera; `shipCamera:setShakeOffset(cockpit:getCameraShake() * entryFx:getShake())`.

#### 2.5.2 Constants and tuning (`:71-174`)

Textures (Roblox client only): FIRE `particles/fire_main.dds` (flame tuft), PUFF `particles/smoke_main.dds` (soft cloud), STAR `particles/sparkles_main.dds` (sparkle), HAZE `shadowblurmask.png` (soft round gradient without alpha, additive: black adds no light). `DEFAULT_COLOUR` 255,122,44 (hot orange), `COLUMNS` 7, `RAILS` 12, `STEP` 4 (sample spacing on big parts), `SKIP = {Cockpit, Interior, Walk, Points, Movers, Gear}` (interior does not shape the outside; the gear is up during entry), `NEAR = {Canopy, Bridge, Cockpit}` (what surrounds the eye). `SPARSE_FILL` 0.2.

**Per-body plasma colours** (`Colours`, `:166`; `colourFor(bodyName)`): **Earth 255,118,92** (orange-pink), **Mars 205,52,26** (deep red), **Titan 255,186,64** (gold); unknown → nil → the caller uses the profile's `entry.glow`, else `DEFAULT_COLOUR`.

**Stages** (`TUNING.Stage`): each layer's strength is `smooth(from, to, heat)`: Glow 0–0.45, Flame 0.05–0.6, Edge 0.1–0.9, Shock 0.2–0.8, Streak 0.3–0.85, Flow 0.35–0.95 ("first nose and light glow, then flames and edge, in the dense part shock front, streaks and the tearing flow").

| Tuning | Values (full heat) |
|---|---|
| `Rate` (particles/s) | Cap 120, Core 40, Flow 70, Streak 130, Ember 45 ("together a good 150 particles at heat 1") |
| `Brightness` | Cap 3, Core 1.6, Flow 2.5, Streak 3.2, Ember 3, CoreGlow 1.3, ShockGlow 1.2, HaloGlow 0.5, EdgeGlow 1.8, Rail 3, CockpitLight 1.9, NoseLight 3.5 |
| `Transparency` (light spots, rails) | Core 0.1, Shock 0.4, Halo 0.78, Edge 0.2, Rail 0.2 (2026-11-04: veil and shock quieter "so you see the planet through the glow"; before: Core 0, Shock 0.25, Halo 0.62) |
| `Flicker` | 0.35 (brightness swings around 1) |
| `StreakSpeed` / `StreakLife` | 190–290 studs/s / 0.28–0.42 s |
| `RailSpeed` | 170–260 studs/s |
| `Shake` | Degrees (pitch 0.35, yaw 0.3, roll 0.22), Studs (0.035, 0.04, 0.02), Curve 1.4 (peak only in the dense part), Gain 2.2 (noise to about ±1) |
| `Sound.Wind` | `rbxasset://sounds/action_falling.ogg`, volume 0.85, pitch 0.72 → 1.12, curve 0.8 |
| `Sound.Rumble` | `rbxassetid://105319176350102` (Josh's "Machine Rumble", an uploaded asset), volume 0.75, pitch 0.8 → 1.05, curve 1.2 |
| `Sound` timing | Attack 0.25 s, Release 0.08 s, Silence 0.02 |
| `Edge` | Alpha 0.65 (the canvas attribute `Alpha` wins), Wash 0.05 (attribute `WashAlpha`), Grow 0.45 (size at weak heat, share of the built size), External 0.55 (outside view: weaker) |

**Noise helpers:** `layered(t, s) = 0.55·noise(1.9t, s, 0.31) + 0.3·noise(6.7t, s, 4.73) + 0.15·noise(17.3t, s, 9.13)` (99 % within ±0.45); `flicker(t, s) = max(1 + 0.35·(1.4·noise(9.1t, s, 2.17) + 0.6·noise(23.3t, s, 7.71)), 0)`.

#### 2.5.3 Measuring the hull (`collect :265`, `measure :312`, `setHull :773`)

Ship space as in ShipHull: `SeatOffset · Root⁻¹ · part` (Root is the pilot's eye); the block hull carries its exact offset as attribute `LocalOffset`. Parts with `Transparency ≥ 0.98`, `HideSeated`, or (without `LocalOffset`) `LocalTransparencyModifier ≥ 0.99` are skipped (ShipHull no longer moves them). The folder directly under the model decides the role: NEAR folders add samples to `near`; non-SKIP folders add to `shape`, or to `rough` when **sparse** (union or mesh whose `mass / (size.x·size.y·size.z·density) < 0.2`: thin plates spread over half the hull whose box front would be a wall across the ship). Samples per part box: corners plus a grid of `clamp(ceil(size/4), 1, 6)` steps per axis.

```
measure(shape, near, rough):
    merge rough into shape if #shape < 8 or shape's Z or X extent < 60 % of rough's     -- e.g. a hull of one big mesh
    if #shape < 8: return nil
    (low, high) = bounds(shape); span = max(high.x, −low.x, 2); length = max(high.z − low.z, 4)
    width = 2·span/7; slot(point) = clamp(floor((x + span)/width) + 1, 1, 7)
    front[slot] = min z; band = max(1.5, 0.05·length)
    bottom/top[slot] = min/max y of points with z ≤ front + band
    column = {x = −span + (slot − 0.5)·width, z = front, bottom − grow, top + grow, width}, grow = max(band − (top − bottom), 0)/2
    nose: reach = max(0.3·span, width); noseZ = min z of columns with |x| ≤ reach (else low.z)
          noseY = mean y of shape points with |x| ≤ reach and z ≤ noseZ + 1; noseColumn = column with the smallest |x|
    near: clearance = max √(dx² + dy²) from the eye axis; nearFront = min z; nearHalfX = max |x|
          (no near points: clearance 6, nearFront = eye.z − 5, nearHalfX 4)
    return {noseZ, noseY, noseWidth, noseHeight = max(top − bottom, 1), tailZ = high.z, length, span, bottom = low.y,
            top = high.y, columns, clearance = clamp(clearance + 1, 7, 22), nearFront = max(nearFront, noseZ + 0.5), nearHalfX}
```
**Default profile** (no model, `:429`): a hull box at (0, −1.6, 8) size 7 × 4.5 × 40, two wings at (∓7.5, −2.2, 14) 8 × 1 × 14, a canopy (near) at (0, 0.9, −1) 6 × 3.6 × 9: "a good 40 studs". `setHull(model)` measures the untouched template `ReplicatedStorage.Assets.Ships.<ShipClass>` (on the flown model resting parts like the canopy are hidden), caches per template (weak table), falls back to the default, then `layout`.

#### 2.5.4 Layout per ship (`layout`, `:793`)

```
scale = clamp(span/15, 0.7, 1.8)
caps (7, one per column):
    front  = 1 − clamp((column.z − noseZ)/(0.45·length), 0, 0.85)
    weight = front·(1.2 − 0.6·|column.x|/span)            -- hottest in front and in the middle; normalised to Σ = 1
    flowWeight = weight if |column.x| − width/2 > nearHalfX else 0   -- normalised; never through the cockpit
    offset = (column.x, (bottom + top)/2, column.z + 0.25); part size (max(0.85·width, 0.4), max(top − bottom, 0.8), 0.4)
    size = clamp(min(width, 1.6·height)·0.75, 1.6, 6.5)
    (missing column: weight 0, at the nose, size 2)
nose: noseSize = clamp(max(noseHeight, noseWidth), 3, 12); offset (0, noseY, noseZ + 0.2);
      part (noseSize/2, max(noseHeight/2, 0.5), 0.4); coreSize = 0.8·noseSize
ring: lead = clamp(0.3·length, 10, 28); inner = clearance; outer = 1.6·clearance
      at (eye.x, eye.y, noseZ − lead), disc of radius outer, ShapePartial inner/outer (0.625: hollow as wide as canopy and bridge)
      k = clamp(scale, 0.8, 1.6): streak size 1.1k → 0.7k, ember size 0.45k → 0.12k
rails (12, Random(1906): every ship the same distribution):
    angle = (i − 0.5)/12·2π + U(−0.18, 0.18); r0 = clearance·U(0.95, 1.15); r1 = r0·U(1.3, 1.6)
    z0 = noseZ − lead·U(0.8, 1.1); z1 = eye.z + clearance·U(1.8, 2.6)
    from (eye.x + cos·r0, eye.y + sin·r0, z0) to (eye.x + cos·r1, eye.y + sin·r1, z1)   -- converge slightly ahead, pass outside the canopy
    width 0.35·scale → 0.9·scale; TextureLength U(10, 22)·scale (Wrap: each repeat is one streak); TextureSpeed = U(170, 260)/TextureLength
light spots (camera-facing quads of the HAZE texture): standoff = clamp(0.35·noseSize, 1.5, 5)
    Core  at (0, noseY, noseZ − 0.6), size 1.3·noseSize
    Shock at (0, noseY + 0.35·(eye.y − noseY), noseZ − standoff), size clamp(1.1·span + noseSize, 14, 60)
    Halo  at (0, eye.y, noseZ − 2.5·standoff), size clamp(2.6·span, 30, 120)
    EdgeLeft/Right at the outermost columns if |x| > nearHalfX: (x, mid y, z − 0.4), size clamp(1.8·width, 4, 16); else size 0
lights: cockpit at (eye.x, eye.y + 1.2, eye.z + clamp(0.45·(nearFront − eye.z), −4.5, −1.5)), range clamp(1.5·clearance, 10, 22)
        nose at (0, noseY + 1.5, noseZ − 3), range clamp(0.4·length, 14, 40)
```

#### 2.5.5 Emitters (built in `new`, `:527-715`; all `LockedToPart`, `LightEmission` 1, `LightInfluence` 0)

| Emitter | Texture | Direction | Speed (studs/s) | Spread | Lifetime (s) | Spin | Transparency keys | Size keys | Other |
|---|---|---|---|---|---|---|---|---|---|
| Cap.Flame (7) | FIRE | Front (out of the skin, into the cushion) | 1.5–5 | 55° | 0.22–0.42 | ±140 | 0:0.4, 0.12:0.08, 0.65:0.45, 1:1 | 0:0.55s, 0.35:s, 1:0.35s; s = cap.size·(0.5 + 0.5·Flame) | ZOffset 0.6 |
| Cap.Flow (7) | FIRE | Back (tears off) | 24–46 | 14° | 0.35–0.6 | ±90 | 0:0.5, 0.15:0.15, 0.7:0.55, 1:1 | 0:0.5f, 0.3:f, 1:0.25f; f = cap.size·(0.45 + 0.35·Flow) | |
| Core | PUFF | Front | 0–1 | 30° | 0.12–0.22 | ±60 | 0:0.5, 0.3:0.15, 1:1 | 0:0.6c, 0.5:c, 1:0.8c; c = coreSize·(0.6 + 0.4·Glow) | ZOffset 1 |
| Streaks | PUFF | Back, disc volume | `StreakSpeed` | 2° | `StreakLife` | – | 0:1, 0.12:0.15, 0.7:0.3, 1:1 | 1.1k → 0.7k | VelocityParallel, Squash −3 (long and thin along the flight) |
| Embers | STAR | Back, disc volume | 70–140 | 8° | 0.35–0.6 | ±300 | 0:0, 0.7:0.2, 1:1 | 0.45k → 0.12k | |

Colours (`applyColour(tint)`, `:898`): `hot = lerp(tint, white, 0.6)`, `warm = lerp(tint, white, 0.25)`, `deep = (0.75·R, 0.32·G, 0.22·B)`. Flame: hot → tint (at 0.4) → deep; Flow: warm → tint → deep; Core: hot → tint; Streaks: warm → hot; Embers: hot → tint; spots Core hot, Shock warm, Halo tint, Edges warm; rails tint → hot; cockpit light `lerp(tint, white, 0.15)`, nose light tint.

Styles (`applyStyle(level)`, `:930`) rebuild the size and transparency sequences only when `round(heat·20)` changes (21 levels, "each call builds a good 30 sequences"): flame and flow sizes as in the table; spot transparencies `Core 1 − 0.9·Glow`, `Shock 1 − 0.6·Shock`, `Halo 1 − 0.22·Shock`, `Edges 1 − 0.8·Flame`; rails `rail = 1 − 0.8·Streak`, keys 0:1, 0.22:rail, 0.7:rail + 0.2·(1 − rail), 1:1 (fade in at the front, out behind the eye).

#### 2.5.6 Per frame (`update(dt, shipCFrame, heat, colour)`, `:994`)

```
heat = NaN ? 0 : clamp(heat, 0, 1); dt = NaN ? 0 : clamp(dt, 0, 0.1); clock += dt
updateSound(dt, heat)
if heat ≤ 0: shake = identity; deactivate (emitters off and rate 0, beams off, lights off and 0, edge GUI off); return
activate; (re-parent under a replaced camera)
tint = colour or DEFAULT_COLOUR; if tint ≠ current: applyColour(tint); key = round(20·heat); if key ≠ styleKey: applyStyle(key/20)
place frame, nose, ring and caps at shipCFrame·offset
t = clock; glow, flame, streak, flow = stage(heat, …)
caps:   f = flicker(t, 3.71·i); flame.Rate = 120·weight·flame·(0.7 + 0.3f); flame.Brightness = 3f
        flow.Rate = 70·flowWeight·flow; flow.Brightness = 2.5f
pulse = flicker(t, 0.5); core.Rate = 40·glow; core.Brightness = 1.6·pulse
streaks.Rate = 130·streak, Brightness 3.2·flicker(t, 41.3); embers.Rate = 45·streak, Brightness 3·flicker(t, 47.9)
spots: Core 1.3·pulse; Shock 1.2·flicker(t, 12.9); Halo 0.5·(0.85 + 0.15·flicker(t, 17.1)); Edges 1.8·flicker(t, 23.4 / 29.8)
rails: 3·flicker(t, 50 + 2.3·i)
lights: cockpit 1.9·glow·pulse; nose 3.5·glow·flicker(t, 61.7)
updateShake(heat); updateEdge(heat, shipCFrame)
```
**Sound** (`updateSound`, `:1086`): `level += (heat − level)·(1 − e^(−dt/τ))`, `τ = heat > level ? Attack 0.25 : Release 0.08`; at heat 0 and `level < 0.02` → 0 and both loops stop. Per loop: `Volume = spec.Volume·level^spec.Curve·effects`, `PlaybackSpeed = lerp(pitch0, pitch1, level)`; through the `ShipAudio` SoundGroup (the EFFECTS slider and the deck mute apply), else `effects = setting EffectsVolume`.

**Shake** (`updateShake`, `:1130`): `amount = heat^1.4·setting CameraShake`; `k = amount·(0.85 + 0.15·clamp(2·noise(0.7t, 77.7, 0.5), −1, 1))` (gusts: the strength itself swings between 70 and 100 %); `axis(s) = clamp(2.2·layered(t, s), −1.4, 1.4)`; `shake = translate(axis(5.1)·0.035k, axis(6.7)·0.04k, axis(8.3)·0.02k) · eulerYXZ(rad(axis(1.3)·0.35k), rad(axis(2.9)·0.3k), rad(axis(3.7)·0.22k))` (pitch, yaw, roll). At heat 1 the peaks are about ±0.49° pitch, ±0.42° yaw, ±0.31° roll and a few centimetres.

**Edge** (`attach :722`, `updateEdge :1154`): the GUI `EntryFx` has a `Canvas` with frames `Top`, `Bottom`, `Left`, `Right` and an optional full-screen `Wash`; their built sizes count as the size at full heat. `amount = stage(heat, Edge)`, ×`External 0.55` when the camera is more than 8 studs from the seat eye. Visible above 0.003. Frame colour = plasma tint. Per frame (written only on 1 % changes): `grow = round2(0.45 + 0.55·amount)`; per side `shimmer = clamp(1 + 0.3·noise(2.7t, 9.7·i, 0.4), 0.7, 1.3)`, `BackgroundTransparency = round2(1 − alpha·amount·shimmer)`; top and bottom scale their height by grow, left and right their width; `Wash.BackgroundTransparency = round2(1 − washAlpha·amount·flicker(t, 71.3))`.

`getShake()` (`:1212`) returns the CFrame (identity at heat 0). `getInfo()` (`:1217`) for debug. `destroy()` stops the sounds, resets the GUI to its built sizes and transparency 1.

#### 2.5.7 Port verdict

- **Keep 1:1:** stages, rates, brightness, transparency and size keys, colours, flicker, shake, sound levels, the hull measurement rules (on mesh vertices instead of part boxes), the layout formulas, the edge behaviour.
- **Replace:** Roblox emitters, beams and lights by a ship-space particle pool, camera-facing quads, scrolling ribbons and two point lights in the lit shader; the Studio GUI by four code-drawn gradients and a wash (5.3); Roblox textures by procedural sprites generated at startup (D33 spirit: nothing uploaded). `LockedToPart` is natural in ship space.
- **Inputs change:** heat comes from the assist (1:1) or from the free-flight heat model (8.6).

### 2.6 ShipWarnings: impact warning, red text, crash blackout (`ShipClient/ShipWarnings.lua`) · **port: `Warnings` + `WarningsOverlay` (UI)**

#### 2.6.1 Purpose (header `:1-24`)

Two stages, both keyed to the **time** to impact, not the distance ("at 90 studs/s 400 studs are deadly, at 3 studs/s harmless", `ShipConfig.lua:537-541`). **Phase 1**: you approach too fast but are still far: plays once or twice, then quiet; red text "IMPACT". **Phase 2**: impact is imminent: a continuous loop that fades out as soon as the danger is over. Two details "without which such a system is useless": **hysteresis** (exit at a higher value than entry, otherwise the warning flickers at the threshold every second) and **priority** (phase 2 interrupts phase 1, not the other way round). Overheat runs as its own track with the same logic and has priority over the impact track ("whoever falls into the star gains nothing from an impact warning").

#### 2.6.2 Construction (`new`, `:38-115`)

ScreenGui `ShipWarningsGui`: `ResetOnSpawn` false, `IgnoreGuiInset` true, **`DisplayOrder` 100** (above everything, so nothing interferes with the black).

| Element | Properties |
|---|---|
| `WarningText` (TextLabel) | `AnchorPoint (0.5, 0)`, `Position {0.5,0, 0.16,0}`, `Size {0,520, 0,56}`, transparent background, font `Ui.Fonts.UiBold` (Sarpanch Bold), `TextSize 34`, `TextColor3 = Warnings.TextColor` RGB 255,55,55, `TextTransparency` 1, text "", ZIndex 2; `UIStroke` black, thickness 2, transparency 1 |
| `Blackout` (Frame) | `Size {1,0, 1,0}`, position 0, black, `BackgroundTransparency` 1, no border, `Visible` false, ZIndex 10 |

SoundGroup `ShipWarnings` in SoundService, `Volume = Warnings.Volume 0.7 × setting EffectsVolume`. Sounds: `Phase1` (`Sounds.Phase1`, one-shot), `Phase2` (looped), `GravityEnter` (optional, empty = none), `Overheat` (looped, `Overheat.Phase2Sound`). Loops are started at volume 0 and never stopped: "start/stop in mid-game clicks audibly". State: `phase` 0, `phase1Plays` 0, `phase1Timer` 0, `phase2Volume` 0, `textTimer` 0, `wasInInfluence` false, `heatPhase` 0, `heatPlays` 0, `heatTimer` 0, `heatVolume` 0, `busy` false.

#### 2.6.3 Per frame (`update(dt, state)`, `:146-234`)

`state` needs `timeToImpact` (s, ∞ = no danger), `inInfluence` and `heat` (the ship heat 0..2.2 from ShipSystems, `ShipClient.lua:2589`).
```
if busy: phase-2 loop → 0 (smoothed), overheat loop 0, fadeText; return          -- during the crash everything is silent
hot = updateHeat(dt, state.heat)
tti = hot ? ∞ : state.timeToImpact
-- hysteresis: enter1 = Phase1Time 10, exit1 = 10·1.35 = 13.5, enter2 = Phase2Time 3.5, exit2 = 3.5·1.35 = 4.725
phase 0: tti ≤ 3.5 → 2; elif tti ≤ 10 → 1
phase 1: tti ≤ 3.5 → 2; elif tti > 13.5 → 0
phase 2: tti > 4.725 → (tti ≤ 13.5 ? 1 : 0)
enterPhase(new): entering 1 from 0 resets phase1Plays and phase1Timer (not when stepping down from 2: "else it beeps for ever");
                 any phase ≥ 1 sets the text colour to TextColor
phase 1: phase1Timer −= dt; if ≤ 0 and plays < Phase1MaxPlays 2: timer = Phase1Cooldown 2.5; plays += 1; play Phase1; showText("IMPACT")
phase-2 loop volume = Phase2Volume 0.9 · smooth(volume, phase == 2 ? 1 : 0, Phase2FadeTime 0.35, dt)
phase 2: showText("IMPACT") every frame
inInfluence false → true: play GravityEnter (none configured), colour InfoColor 120,190,220, showText("GRAVITATIONAL INFLUENCE")
inInfluence true → false: colour back to TextColor
fadeText(dt)
```
**Overheat** (`updateHeat`, `:240-298`), with `Phase1Heat 0.8`, `Phase2Heat 1.0`, `Hysteresis 0.88` (exit **below** threshold × 0.88):
```
0: heat ≥ 1.0 → 2; elif ≥ 0.8 → 1
1: heat ≥ 1.0 → 2; elif heat < 0.704 → 0
2: heat < 0.88 → (heat ≥ 0.8 ? 1 : 0)
entering 1 from 0: heatPlays = heatTimer = 0
phase 1: as impact phase 1, the same Phase1 tone (twice, 2.5 s apart), colour TextColor, text "OVERHEAT"
loop "Overheat" volume = 0.9 · smooth(heatVolume, phase == 2 ? 1 : 0, 0.35, dt)
phase 2: colour TextColor, showText("OVERHEAT") every frame
return heatPhase > 0
```
**Text** (`showText :136`, `fadeText :322`): `showText` sets the text and `textTimer = TextHoldTime 1.4`. While `textTimer > 0` it counts down and text and stroke are opaque; after it, both fade by `dt/TextFadeTime 0.5` per frame to 1.

#### 2.6.4 Crash blackout (`crash(onBlack, timing)`, `:347-417`)

```
if busy: return
busy = true; textTimer = 0                                     -- running texts go at once: the cinematic shows its card
delay   = timing.delay   or 0                                  -- the cinematic's length
fadeOut = timing.fadeOut or Crash.FadeOutTime 0.30
hold    = timing.hold    or Crash.BlackHoldTime 1.6
fadeIn  = timing.fadeIn  or Crash.FadeInTime 0.9
task: wait(delay); blackout visible, transparency 1 → 0 over fadeOut (Quad Out: 1 − (1 − x)²)
      onBlack() (protected: if it throws, busy would stay set and the screen black, and ShipClient checks no collisions meanwhile)
      wait(hold); reset: phase 0, plays 0, phase-2 volume 0, heat phase/plays/volume 0, loops 0, text hidden
      transparency 0 → 1 over fadeIn (Quad Out); hide; busy = false
```
`isBusy()` (`:419`) is read all over ShipClient to stop collisions, prompts, traffic and survey during the sequence.

#### 2.6.5 Port verdict

Port 1:1 (state machines, timings, texts, colours), with three changes: the timer chain without coroutines (a small state enum advanced in `update`); the time to impact comes from terrain prediction with a landing gate (8.9; Roblox had it off); the GUI is code-drawn (5.2). Sounds are stand-ins (D31).

### 2.7 ShipDestruction: the crash cinematic (`ShipClient/ShipDestruction.lua`) · **port: `DestructionScene` (pure) + `DestructionRenderer` + `CrashCamera` + `DestroyedCard` (UI)**

#### 2.7.1 Purpose (header `:1-62`)

"Crash as a film scene (Josh, 2026-11-04): the ship explodes, a camera looks down on the crash site from above and slowly rises, with the card SHIP DESTROYED (`StarterGui.ShipDestroyed`) showing place, losses and a countdown to the recovery at the nearest station. After `Destruction.Duration` ShipWarnings fades out; in the black ShipClient clears everything (`finish`) and puts the replacement ship in front of the station." On screen: **flash** (blinding core, screen flash, light at the spot), **fireball** (explosion particles, sparks, shock wave, after-explosions: two at the wreck, later some at burning debris), **dust ring** (planets only, in the colour of the ground; white spray on water), **debris** (copies of real hull parts and splinters, charred; they fly off, bounce on the ground with dust or spray, some burn), **smoke** (a column above the wreck; on planets it rises and drifts sideways; in space a glowing gas cloud), **scorch mark** (dark disc on the ground). All local; the script moves the debris itself (rays against the ground, no physics): "cheap, and looks the same on every machine".

#### 2.7.2 API and the scene record

`new(camera, playerGui)` (`:250`): SoundGroup `ShipDestruction` with `Volume = Destruction.Sounds.Volume 1.0 × EffectsVolume` (follows setting changes). `start(scene)` (`:356`), `setLosses(text)` (`:1063`, losses from the server fade in after the title), `update(dt)` (`:1120`, every frame while active), `isActive()`, `finish()` (`:1460`, removes everything, in the black), `getStatus()` (`:1506`, for tests), `destroy()`.

| `scene` field | Meaning (ShipClient values, `ShipClient.lua:1567-1608`) |
|---|---|
| `cframe` | ship pose at impact (world) |
| `center` | hull centre (world), else `cframe.Position` |
| `velocity` | velocity at impact |
| `up` | up at the spot (ground or wall normal): landing scene `surface:getUp`; GROUND in space: away from the body; STATION: the hit normal; else the ship's up |
| `gravity` | studs/s², nil in free space: scene gravity (×0.35) or the gravity vector |
| `ground` | true: a ground or wall to bounce on (rays, scorch) — scene, GROUND or STATION |
| `dust` | true: dust ring (planets only) |
| `floor(position) → point?` | the computed ground of the landing scene below a point (its ground copy catches no rays): `position − Y·altitude` |
| `colour(position) → Color3?` | ground colour for the dust (`surface.terrain:colourAt(x, z)`) |
| `parts` | visible hull parts with their pose now (`crashHull`: skips the cockpit model, `Plume` parts, `Transparency ≥ 0.9`, force-hidden parts, stowed gear, closed gear doors while the gear is out) |
| `size` | hull extents (ship-space box of those parts; fallback 40 × 16 × 60) |
| `exclude` | what rays ignore (character, ship model) |
| `detail` | line under the title: `"<SHIP>  ·  <WHERE>"` |
| `station` | recovery station name (upper case) or nil |
| `total` | seconds to the black: `Duration + FadeOutTime` = 12.2 |
| `timeScale` | tests only (< 1 = slow motion) |
| `onCue(name)` | "Title" and "Recovery" (voice) |

`WHERE` (`ShipClient.lua:1540-1550`): cause HEAT → `BURNED UP NEAR <BODY>` (or `BURNED UP`); STATION → `COLLIDED WITH <STATION NAME>` (or `COLLIDED WITH A STATION`); GROUND → `CRASHED ON <BODY>` (or `CRASHED`). `SHIP` = the ship's `DisplayName` upper case (e.g. `VARROW`).

#### 2.7.3 `start(scene)` (`:356-449`)

```
finish(); active; t = 0; timeScale = clamp(scene.timeScale or 1, 0.05, 1); total = scene.total or 12.2
up     = unit(scene.up) or Y
radius r = max(|size|/2, Camera.MinRadius 14)            -- size default (40, 20, 60); "even for tiny ships"
centre = scene.center or cframe.Position
gravity = scene.gravity ? unit(g)·clamp(|g|, Debris.Gravity 30, 95) : nil     -- "less = higher, slower arcs"
rays exclude: the wreck folder + scene.exclude; water counts
ground hit: if scene.ground: ray from centre + up·0.5r along −up·3r; else scene.floor(centre) if within 2.5r below
point = hit ? hit.Position : centre; normal = hit ? hit.Normal : up; water = hit material is Water
forward = unit(flat(velocity, up)) (if < 2: flat(look, up); else across(up))     -- "behind" for the camera
orbitSign = ±1 at random; focus = point + up·0.3r
buildBlast(); buildDebris(); all emitters get TimeScale; parent to the workspace; fire the bursts; startSounds()
camera: Scriptable, FieldOfView Camera.FieldOfView 60; bind and show the card; update(0)
groundColour(): water → RGB 214,226,232; scene.colour(hit) → lerp(c, RGB 150,140,128, 0.3);
                else terrain material colour or the part colour (default 120,108,92) → lerp(c, RGB 150,140,128, 0.45)   -- dust is lighter and greyer
```

#### 2.7.4 The blast (`buildBlast`, `:484-805`)

Frame `base` at `point` with Y = `normal`; `rise = gravity ? up : 0`; `spread = space ? (180, 180) : (75, 75)`. Anchor part "Blast" at `base + normal·0.25r` with attachment `Core`.

- **Flash ball:** Neon sphere RGB 255,236,205, diameter `r·(0.6 + 1.9·smooth(0, 1, t/0.18))`, transparency `0.1 + 0.9·window(t, 0.04, 0.42)`, removed after 0.6 s.
- **Light:** PointLight RGB 255,176,96, range `min(3.5r, 120)`, `brightness = (base + boost)·(1 + 0.6·noise(7.3t, 0.41))`, `base = t < 1.2 ? lerp(10, 2.6, smooth(t/1.2)) : lerp(2.6, 1.4, smooth((t − 1.2)/(Duration − 1.2)))`, `boost` decays by 9/s (set to 5·scale by after-explosions).
- **Scorch** (ground hit, not water, with gravity): a slate cylinder 0.2 thick, diameter 2.4r, RGB 20,17,15, axis = normal, at `point + normal·0.08`; transparency `1 − 0.8·smooth(window(t, 0.1, 1.0))`.

Emitters (all start disabled with rate 0, `Rotation` 0–360, `LightInfluence` 0 unless given; sizes and speeds × r unless noted; `a±b` = value with random envelope):

| Emitter | Texture (`particles/`) | Colour keys | Light | Size keys | Transparency keys | Life (s) | Speed | Spread · direction | Accel / drag / spin | Count |
|---|---|---|---|---|---|---|---|---|---|---|
| Blast | explosion01_core_main | 0 255,244,214 · 0.25 255,186,92 · 0.6 214,84,30 · 1 60,30,20 | LE 1 | 0 0.5±0.1 · 0.25 1.6±0.3 · 1 2.3±0.3 | 0 0 · 0.55 0.25 · 1 1 | 0.55–1.0 | 0.15–0.6 | 180° | drag 4, ±80°/s, ZOffset 1 | burst 14 |
| Fireballs | fire_main | 0 255,220,150 · 0.4 255,130,50 · 1 90,40,25 | LE 0.9 | 0 0.4 · 0.3 0.9±0.2 · 1 1.2±0.2 | 0 0.1 · 0.7 0.4 · 1 1 | 0.9–1.7 | 0.3–0.9 | spread · Top | rise·0.35r, drag 2.5, ±50 | 18 |
| Sparks | fire_sparks_main | 255,214,140 → 255,120,40 | LE 1 | 0 0.06 · 1 0.015 | 0 0 · 0.8 0.2 · 1 1 | 0.7–1.8 | 1.2–3.0 | spread · Top | gravity or 0, drag 1.2, VelocityParallel | 140 |
| Shockwave | explosion01_shockwave_main | 255,226,190 | LE 0.7 | 0 0.6 · 1 6 | 0 0.1 · 0.5 0.5 · 1 1 | 0.75 | 0.01 | 0 · Top | rotation 0; ground: VelocityPerpendicular (flat ring on the ground), space: FacingCamera; ZOffset 0.5 | 1 |
| Cloud (space only) | explosion01_smoke_main | 0 255,170,90 · 0.3 190,80,40 · 1 40,22,30 | LE 0.6 | 0 0.8 · 1 4.2±0.6 | 0 0.3 · 0.4 0.6 · 1 1 | 4.5–7 | 0.15–0.45 | 180° | drag 0.5, ±12 | 16 |
| Smoke (mushroom) | explosion01_smoke_main | 96,88,82 → 62,58,55 | LI 0.9 | 0 0.8 · 0.35 2.0±0.3 · 1 2.8±0.5 | 0 0.25 · 0.55 0.5 · 1 1 | 3.5–5.5 | 0.25–0.75 | spread · Top | rise·0.14r, drag 1.4, ±20 | ground 20, space 7 ("vacuum: the gas cloud carries it") |
| Dust ×12 (dust and ground hit) | smoke_main | groundColour | LI 1 | 0 0.3 · 0.3 0.9±0.2 · 1 1.6±0.3 | 0 0.4 · 0.5 0.6 · 1 1 | 2.2–4.2 | 1.4–2.8 | (14°, 22°) along the nozzle | water: gravity·0.35, else up·0.05r; drag 1.6 (water 1.4); ±25; ZOffset 0.45r | 4 each |
| ImpactDust (ground) | smoke_main | groundColour (water: 150,140,128) | LI 1 | 0 0.15 · 1 0.7±0.15 | 0 0.35 · 0.5 0.55 · 1 1 | 1.2–2.4 | 0.1–0.35 | 60° · Top | up·0.03r, drag 2, ±25, ZOffset 0.15r | per debris impact |
| ImpactSplash (ground) | smoke_main | 226,236,242 | LI 1 | 0 0.1 · 1 0.5±0.1 | 0 0.25 · 0.6 0.5 · 1 1 | 0.8–1.6 | 0.3–0.8 | 25° · Top | gravity·0.5, drag 1.2, ±25, ZOffset 0.15r | per debris impact in water |
| Column smoke (FireBed) | smoke_main | 88,82,78 → 58,55,53 | LI 0.9 | 0 0.5 · 0.4 1.6±0.3 · 1 3.0±0.5 | 0 0.3 · 0.35 0.45 · 0.75 0.7 · 1 1 | 6–9 | 0.15–0.35 | 16° · Top | rise·0.1r + wind, drag 0.45, ±15 | rate, below |
| Wreck fire (FireBed) | fire_main | 0 255,226,160 · 0.5 255,120,40 · 1 120,40,20 | LE 1 | 0 0.5±0.15 · 1 0.12 | 0 0.15 · 0.7 0.4 · 1 1 | 0.6–1.3 | 0.08–0.25 | 25° · Top | rise·0.3r, ZOffset 0.1r | rate, below |

- **Dust nozzles:** for `i = 1..12`: `angle = i/12·2π + U(−0.15, 0.15)`, `outward = X·cos + Z·sin` (base axes), direction `unit(outward + normal·steep)` with `steep = water ? 0.85 : 0.25` (steeper spray on water), placed at `point + normal·0.22r + outward·0.3r` ("higher and with ZOffset forward: otherwise the water surface cuts the images with a hard edge").
- **FireBed** (the whole wreck burns, not a point): space a cube of 0.5r at `base + normal·0.25r`, ground a slab 1.1r × 0.4 × 1.1r at `base + normal·0.06r`. **Wind** (ground only): `side·0.14r·(±1)` with `side = unit(up × forward)` ("across the camera's view: from above the column would otherwise stand straight at the lens and cover everything as a black disc").
- **Continuous rates:** `rate = t ≥ delay ? lerp(from, to, smooth((t − delay)/span)) : 0`, enabled while > 0.05. Column smoke: delay 0.3, ground 10 → 5 over `Duration`, space 8 → 0 over 5 s. Wreck fire: delay 0.2, ground 46 → 22 over `Duration`, space 28 → 0 over 5 s.
- **After-explosions** (`pops`): two at `t = 0.45` and `1.3` s at `point + normal·0.2r + h·U(0.3, 0.7)·r` (h = random horizontal unit). Late ones (`buildDebris`): ground `{2.4, 3.9, 5.8}`, space `{2.2, 3.4, 4.8, 6.4}`, each `± 0.25` s, attached to burning debris pieces in turn (else at the wreck, `U(0.4, 0.9)·r` out), `small` (scale 0.6). A pop moves the `Core` attachment there and emits Blast `ceil(5·scale)`, Fireballs `ceil(7·scale)`, Sparks `ceil(35·scale)`; light boost `5·scale`; pop sound at pitch `U(1.15, 1.4)`.

#### 2.7.5 Debris (`buildDebris :807`, `addPiece :889`, `addBurner :937`, `stepPieces :1263`)

```
candidates = scene parts (archivable) whose longest side ≥ Debris.MinSize 0.06·r, sorted by volume (largest first)
fixed = min(round(HullPieces 34·0.35), #candidates) = 12 largest; then random picks from the rest up to 34
each: charredCopy(part, Char 0.6): clone without children (meshes keep, SpecialMesh texture cleared), no attributes,
      anchored, no collision/query/touch, casts shadow, Neon/ForceField → Metal, Color = lerp(colour, CHAR 26,22,20, 0.6),
      MeshPart texture cleared, unions use the part colour; placed at the part's current pose
      the first Burning 6 copies get a burner
splinters: Fragments 26, the first Embers 8 glow: size s = r·U(0.02, 0.06), box (s·U(0.6,1.6), s·U(0.12,0.45), s·U(0.6,1.8)),
      ember: Neon EMBER 255,128,48, no shadow; else Metal, lerp(CHAR, 92,88,84, U(0, 0.6)); at centre + randomUnit·0.3r, random rotation
addPiece(part, size, ember):
    away = unit(part.Position − centre) or randomUnit; dir = 0.55·away + 0.45·randomUnit
    if ground: dir += normal·0.75; if dir·normal < 0.15: dir = flat(dir, normal) + normal·0.3
    heavy = clamp(1.4 − 0.6·size/r, 0.45, 1.25)                     -- heavy parts fly slower and spin less
    speed = U(Burst 40, 95)·√(r/40)·heavy
    carry = velocity (ground: flattened on the normal)
    lift the start pose above the ground by the part's half thickness ("nothing starts under the ground")
    piece = {cframe, velocity = dir·speed + carry·Carry 0.35, axis = randomUnit, spin = U(0.4, Spin 5)·heavy (rad/s),
             rest = min(part.Size.x, .y, .z)/2, resting = false, ember, life = ember ? U(1.6, 3.4)·(gravity ? 1 : 2) : ∞}
burner: Trail (smoke_main, 64,58,54 → 34,32,30, LI 1, size 0 0.12r · 1 0.7r±0.15r, transp 0 0.3 · 0.5 0.5 · 1 1, life 1.4–2.6,
        speed 0.5–2, 180°, rise·0.08r, drag 0.6, ±20) and Flame (fire_main, 255,220,150 · 0.5 255,120,40 · 1 110,40,20, LE 1,
        size 0.12r → 0.03r, transp 0.15 → 1, life 0.3–0.7, speed 0.5–2.5, 180°, rise·0.2r);
        rates smoke 16·burn, flame 22·burn, burn = gravity ? lerp(1, 0.45, smooth(t/Duration)) : 1 − smooth(t/6), off below 0.03
stepPieces(dt), for each piece not resting:
    v += gravity·dt; v *= e^(−(gravity ? 0.05 : 0.12)·dt); move = v·dt
    contact: ray from the position along move (length + rest) against everything but the wreck, hit only if v·n < 0
             → contact = hit + n·rest; water flag
         or (no ray hit, with floor and gravity): floor(position + move); if height < rest − 1 and v·up < 0 → contact on it
    if contact: into = −v·n; along = v + n·into
        water: v = 0.2·along; sink to contact − n·0.7·rest; resting (floats); puff (splash) if into > 8
        else:  v = along·(1 − Friction 0.38) + n·into·Bounce 0.3; if into > 12: playHit, puff (dust); resting if |v| < 4
        spin *= 0.5
    rotate by axis·spin·dt while spin > 0.02; BulkMoveTo
embers: transparency (t/life)², invisible and resting at t ≥ life
puff(position, n, water, impact): cooldown 0.04 s; move the Impact attachment there; emit clamp(floor(impact/10), 2, 6)
playHit(impact, distance): cooldown 0.09 s; random MetalHit 1–3; volume = def.Volume·HitVolume 0.9·clamp(impact/60, 0.15, 1)
        ·clamp(3r/distance, 0.3, 1)·0.5; pitch U(0.7, 1.05)
```
**Roblox bug (fix, D35):** `startSounds` reads `ShipConfig.Audio["MetalHit"..i]`, which is nil (the hits live in `Audio.Cues`, `09-life-npc-audio.md` Q14), so debris impacts are silent.

#### 2.7.6 Sounds (`startSounds`, `:990`)

`Boom` = `Sounds.Explosion` (`rbxasset://sounds/impact_explosion_03.mp3`) at volume 2.2, pitch `U(0.9, 1.0)`; `Deep` = the same at 1.6 and pitch 0.55 ("the belly of the explosion"); `Pop` = the same at 1.2, pitch 1.25 (re-pitched per pop); `Rumble` loop `rbxassetid://105319176350102` at pitch 0.62 with `Volume = RumbleVolume 1.1·envelope`, `envelope = smooth(window(t, 0, 0.25))·lerp(1, 0.3, smooth(window(t, 0.4, 5)))·(1 − window(t, Duration − 0.5, FadeOutTime + 0.5))` ("swells, decays, is gone at the black"). Boom, Deep and Rumble start at once.

#### 2.7.7 Camera (`updateCamera`, `:1379-1449`)

"High behind the flight path, looking down at an angle. The shock wave pushes it back, then it keeps rising slowly, gets steeper and circles the spot a little. If a slope hides the view it goes steeper at once and only slowly back."
```
distance  = r·(StartDistance 2.6 + Kick 0.9·(1 − e^(−t/KickTime 0.45)) + Rise 0.42·t)
            (with the far-field squash active: seamLimit(distance, max(d0 − 1.2r, 2r)) — Roblox only)
elevation = StartElevation 36 + (EndElevation 68 − 36)·smooth(t/Duration)    (degrees)
azimuth   = rad(Orbit 3.5·t)·orbitSign
back = −forward; side = up × back; horizontal = back·cos(azimuth) + side·sin(azimuth)
lift: for k = 0..5: e = min(elevation + 9k, 86)°; dir = horizontal·cos e + up·sin e; needed = 9k; stop at the first clear ray
      from focus along dir·distance; lift = needed > lift ? needed : lift + (needed − lift)·(1 − e^(−dt/0.8))
e = min(elevation + lift, 86)°; dir as above; eye = focus + dir·distance; if that ray is blocked: eye = hit − dir·3
never below the computed ground: if floor(eye) and height < 6: eye += up·(6 − height)
aim = focus − horizontal·distance·AimAhead 0.06            -- the spot sits just below the image centre
shake amplitude = Shake 2.2·setting CameraShake·e^(−2.5·t/ShakeTime 1.4) (degrees), applied while > 0.02:
    n = 24t; angles (2·noise(n, 0.31, 1.7)·amp, 2·noise(2.1, n, 4.3)·amp, noise(5.7, 3.3, n)·amp)
camera = lookAt(eye, aim, up) · shake; FOV 60 (restored to the setting FieldOfView in finish)
```
Computed for r = 40 (a Varrow-sized wreck): distance 104 m at 0 s, 141 m at 0.6 s, 166 m at 1.6 s, 224 m at 5 s, 325 m at 11 s; elevation 36° → 52° at 5.5 s → 68°; azimuth 38.5° at 11 s; shake 2.2° at 0, 0.75° at 0.6 s, 0.13° at 1.6 s, below 0.02° after 2.6 s.

#### 2.7.8 The card (`bindGui :294`, `showGui :1030`, `updateGui :1072`, `setLosses :1063`)

`StarterGui.ShipDestroyed` (built in Studio; **not in the export**): `Canvas` with a `UIScale`, `Flash`, `BarTop`, `BarBottom`, `Title` (with a `UIScale` "pop", `Detail` and `Losses` texts), `Recovery` (`Timer`, `Station`, `Bar.Fill`). "All sizes are for 1920 × 1080 (like FlightNotices)": `factor = max(min(W/1920, H/1080), 0.05)`, `UIScale = factor`, `Canvas.Size = (1/factor, 1/factor)` scale. Each group fades by scaling every text/image/stroke/background transparency from its design value (`collectFade`/`applyFade`: `value = 1 − (1 − design)·alpha`; "single elements instead of a CanvasGroup: that clips with a large system font").

```
showGui: Detail = scene.detail; Station = scene.station or "NO STATION IN RANGE"; Timer = "RESPAWN IN %d" (ceil(total));
         Fill width 0; all groups alpha 0; bars height 0; flash transparency 1; pop scale Gui.Pop 1.12; enabled
updateGui (t = scene time):
    flash   transparency = 1 − FlashStrength 0.85·f², f = 1 − window(t, 0, FlashTime 0.55)          -- instantly bright, then out
    bars    height = smooth(window(t, 0.05, BarTime 0.7))·BarHeight 0.085 of the screen (each, top and bottom)
    title   alpha = smooth(window(t, TitleIn 0.6, TitleTime 0.8)); pop = 1.12 + (1 − 1.12)·(1 − (1 − a)³), a = window(...)
    losses  alpha = smooth(window(t, max(lossesAt, TitleIn + 0.3), LossesTime 0.5))  (once setLosses arrived)
    recovery alpha = smooth(window(t, RecoveryIn 1.6, RecoveryTime 0.6))
    timer   = remaining > 0 ? "RESPAWN IN %d" (ceil(total − t)) : "RESPAWNING"; Fill width = clamp(t/total, 0, 1)
cues: t ≥ 0.6 → onCue("Title") (voice "ShipDestroyed", then the loss lines); t ≥ 1.6 → onCue("Recovery") ("ReplacementReady")
```
Timeline (total 12.2): timer "RESPAWN IN 13" at 0 s, 12 from 0.2 s, … 1 from 11.2 s, "RESPAWNING" at 12.2 s; title fully in at 1.4 s; recovery at 2.2 s.

#### 2.7.9 Per frame (`update(dt)`, `:1120-1238`) and the end

`step = dt·timeScale; t += step`; cooldowns; flash ball, light, scorch; pops; continuous rates; burners; embers; rumble envelope; `stepPieces(step)`; `updateCamera(dt)`; `updateGui()`; cues. **`finish()`** (`:1460`): destroys the folder and the sounds, clears all lists, resets the card to its design (disabled, alphas 1, bars 0, flash to its design transparency, pop 1, fill 0) and the FOV to the setting.

#### 2.7.10 Port verdict

Port 1:1: every number, the emitter table, the debris rules, the camera path, the card timeline, the sounds (stand-ins, D31) with the hit bug fixed. Replace rays by terrain and station queries (8.10), Roblox emitters by a world-space particle pool anchored at the crash site (double origin, camera-relative), part copies by debris chunks cut from the ship mesh at import, the Studio card by a code-drawn card (5.4). Drop the far-field seam limit.

### 2.8 Integration in ShipClient (`ShipClient.lua`)

| Line | What |
|---|---|
| 148, 164, 166, 191 | requires |
| 273, 275, 306, 315, 328-329 | `warnings = ShipWarnings.new(playerGui)`; `destruction = ShipDestruction.new(camera, playerGui)`; `atmosphere = ShipAtmosphere.new(playerGui)`; `atmosphere:setSurface(surface)`; `entryFx = ShipEntryFx.new(playerGui)`, `entryFx:setHull(hull:getModel())` (again at 1136 after a ship change) |
| 485-521 | `setCallbacks`: need-terrain → `surface:prepare(body)` (else `planetTerrain:setForced(nil, false)`); hit → `audio:playHit(strength)`; switch DOWN → `docking:cancel()`, `planetTerrain:clear()`, `traffic:clear()`, `surface:begin(body, systemFolder, pose, velocity)`, `planetTexture:releaseAll()`, `markers:setVisible(false)`; UP → `realPose = surface:toRealPose(pose)`, `realVelocity = surface:toRealVector(v)/sceneScale`, `surface:finish()`, `markers:setVisible(true)`, `stations:forget()` |
| 837, 968, 3027-3036 | star map jump, autopilot engage only in SPACE; a planned jump is cancelled "ATMOSPHERE" when the state is not SPACE |
| 1012-1046 | `watchLanding` (voice "LandingBlocked" if still SPACE after `RequestWindow + 0.1` s), `landKey` (ignored while the docking computer flies), `landButton` (cockpit: land if `canLandNow`, else dock, else request + watch) |
| 1048 | `hudPrompt`: in SPACE landing and docking prompts stack (`landing .. "\n" .. docking`) |
| 1308-1475 | crash: `nearestStation`, `crashHull`, `setFlightUiHidden` (hides markers, cursor, speed lines, dust and every enabled ScreenGui except the warnings GUI and `ShipDestroyed`; maps closed), `respawnAfterCrash` |
| 1474-1666 | `startCrash(cause, gravityState, hit)` (4.7) |
| 2032-2063 | `canStand`: false while `warnings:isBusy()` or `atmosphere:isBusy()`; landed in SURFACE required |
| 2222, 2271 | decks and destruction frames: `entryFx:update(dt, shipCFrame, 0)` (the glow dies away) |
| 2249-2281 | `destructionFrame`: `destruction:update`, UI hidden, the scene keeps running without re-anchoring, NPC traffic leaves, `surface:afterCamera(camera.CFrame)` (sky and clouds keep updating), `warnings:update(dt, {})` |
| 2337 | main loop: `if destruction:isActive()` → destruction frame, return |
| 2356-2361 | `controlled = docking:isControlling() or atmosphere:isGuiding()` → thrust input off, boost cleared |
| 2389-2393 | autopilot disengages "ATMOSPHERE" (throttle 0, voice "AutopilotAtmosphere") in the scene or when the state is not SPACE |
| 2465-2489 | in the scene `gravityState.timeToImpact = ∞` (and altitude, gravity, speed limit from the scene); in space `timeToImpact = ∞` unless `isOnSurface()` |
| 2548-2580 | 4b: guided → thrust displacement suppressed (`translate(0)`); `guide()` sets pose, velocity, acceleration, felt acceleration; on release `input:centerCursor()` and `thrust:setThrottle(throttleFor(speed, min(CruiseMaxSpeed, limit), topSpeed))` |
| 2589-2699 | systems (heat) before the crash checks; HEAT crash at heat ≥ `Overheat.FatalHeat` 2.0; scene ground crash (impact > 38 on solid or image ground); barrier hold in space |
| 2882-2884 | gear down in the scene below 60 m |
| 2892 | camera shake `cockpit:getCameraShake() * entryFx:getShake()` |
| 2901-2904 | `entryFx:update(dt, shipCFrame, onFoot ? 0 : atmosphere:getHeat(), colourFor(bodyName) or getHeatColour())` |
| 2906 | `surface:afterCamera` (ShipSky:update inside) |
| 2939 | `warnings:update(dt, gravityState)` |
| 3043-3060 | scene: `requestAscent()` above the exit altitude; `atmosphere:update(dt, gravityState, speed)`; arrival toast (6 s) |
| 3076, 3087, 3111, 3133-3149 | dust mood "danger" when tti < 10 s; HUD speed/altitude through `getShown…`; attitude indicator `landingFlow`, `rateScale`, `danger` |

---

## 3. Tunables

### 3.1 `ShipConfig.Atmosphere` (`ShipConfig.lua:952-1002`)

| Key | Value | Meaning | Port |
|---|---:|---|---|
| `EntryAltitude` | 1.15 | × R above the barrier: entry range (re-arm; the "DESCEND TO LAND" hint from 1.6× this = 1.84 R) | ratio, from the virtual barrier (8.5) |
| `HoverEntryAltitude` | 0.35 | × R above the barrier: L works only below this ("the barrier holds every ship softly; sinking alone never leads in") | ratio |
| `RequestWindow` | 3.0 s | a key press stays valid | drop (button shown only when it works) |
| `DescentTau` | 2.2 s | HEAT and DESCENT: vertical speed = height / τ | 1:1 |
| `Glide` | 2.1 | forward speed = vertical × Glide | 1:1 |
| `Response` | 0.45 s | velocity follows the target | 1:1 |
| `TurnResponse` | 0.35 s | the nose follows the flight direction | 1:1 |
| `DeckSpeed` | 600 m/s | maximum vertical speed in the cloud deck | absolute |
| `DeckMargin` | 300 m | band around the deck where it applies | absolute |
| `LevelAltitude` | 900 m | level-off height (small bodies lower: `min(900, 0.4·switch scene height)`) | absolute |
| `LevelStart` | 1.8 | LEVEL begins at 1.8 × level height (1,620 m) | 1:1 |
| `LevelTau` | 1.0 s | vertical correction to the level height | 1:1 |
| `ReleaseSpeed` | 320 m/s | hand-over speed | absolute |
| `LevelTimeout` | 7 s | hand over anyway | 1:1 |
| `ClimbTau` / `ClimbMinSpeed` / `ClimbForward` | 1.9 s / 300 m/s / 0.55 | CLIMB law | 1:1 (ORBIT assist) |
| `EmergeTau` / `EmergeMinSpeed` | 1.5 s / 20 m/s | EMERGE law | 1:1 |
| `EmergeAltitude` | 0.5 | × R above the barrier: free | ratio |
| `HeatRise` | 1.2 s | glow rises after the start | 1:1 |
| `HeatFadeStart` / `HeatFadeEnd` | 0.6 / 0.25 | glow fades between these shares of the switch height (scene) | of the absolute `hs` |
| `ShellFadeStart` / `ShellFadeEnd` | 0.06 / 0.012 | × R above a space air shell: fully visible / gone | drop |
| `FeltMaxG` | 2.2 | the most the pilot feels from the guidance (× `Handling.FeltStudsPerG` 9.81) | 1:1 |
| `ScaleBlend` | 1.2 s | HUD numbers glide into the scene scale | drop |
| `HitIntervalMin` / `HitIntervalMax` | 0.95 s / 2.20 s | hull knocks at full / weak shaking | 1:1 |

### 3.2 `ShipConfig.Sky` (`ShipConfig.lua:669-730`)

| Key | Value | Meaning |
|---|---:|---|
| `DayStart` / `DayFull` | −0.10 / 0.12 | sine of the sun elevation: day begins / full |
| `NightBrightness` | 0.5 | ground brightness at night (moonlight; 0.05 was black) |
| `NightLight` | 0.3 | share of the day ambient kept at night |
| `NightGlow` | 0.3 | lower bound for the air's colours at night |
| `NightFogShare` | 0.35 | share of the air density at night |
| `SpaceRampEnd` | 0.55 | × thickness: above, space light again |
| `DensityCurve` | 1.6 | > 1: dense low for long |
| `FogDensityScale` / `FogHazeScale` | 0.4 / 0.3 | profile density and haze × these (the ×470 scene) |
| `WhiteoutDensity` / `WhiteoutHaze` | 0.92 / 6 | in the cloud deck everything turns white |
| `DaySkyMin` | 0.35 | blue day image only from this day value |
| `SwapWhiteout` | 0.6 | swap day ↔ haze image only at this whiteout |
| `SkyHold` | 0.95 | below this dome opacity the star image returns |
| `DomeSolidDay` / `DomeClearDay` | 0.2 / 0.02 | dome opaque from / gone below this day value |
| `DomeNightShade` | 0.35 | dome brightness at dusk |
| `DomeFadeLow` / `DomeFadeHigh` | 0.3 / 0.95 | × min(thickness, exit altitude): full colour below, black above |
| `DomeDusk` | 0.45 | how strongly dusk tints the dome (`sky.dusk`) |
| `DomeMargin` | 1.25 | horizontal semi-axis = horizon × this |
| `DomeSight` | 0.95 | at most this share of the sight distance |
| `DomeTop` | 6000 m | vertical semi-axis at least ("nearer = truer colour") |
| `DomeCloudGap` | 1500 m | and this far above the deck |
| `DomeBelow` | 2.5 | and height × this |
| `DomeMin` / `DomeMax` | 3000 / 80000 m | size bounds (min never beyond `DomeSight`) |
| `DomeSunSize` | 2.2° | sun disc diameter in front of the dome |
| `DomeSunShare` | 0.8 | its distance as a share of the dome reach |
| `DomeSunColour` | 255,244,222 | |
| `DecayLift` | 0.35 | lighten the sun-away haze towards the air colour |
| `SunScale` / `SunMin` / `SunMax` | 3.2 / 4° / 40° | Roblox sun disc = star angular size × 3.2, clamped |
| `SunTexture` | `rbxasset://sky/sun.jpg` | |

### 3.3 `ShipConfig.Warnings` (`ShipConfig.lua:542-586`)

| Key | Value | Meaning |
|---|---|---|
| `Volume` | 0.7 | sound group (× EffectsVolume) |
| `Phase1Time` / `Phase2Time` | 10.0 s / 3.5 s | time to impact: pre-warning / acute |
| `Hysteresis` | 1.35 | exit at threshold × this (13.5 s / 4.725 s) |
| `Phase1MaxPlays` / `Phase1Cooldown` | 2 / 2.5 s | pre-warning plays |
| `Phase2Volume` / `Phase2FadeTime` | 0.9 / 0.35 s | acute loop |
| `TextPhase1` / `TextPhase2` | "IMPACT" / "IMPACT" | |
| `TextGravity` | "GRAVITATIONAL INFLUENCE" | |
| `TextColor` / `InfoColor` | 255,55,55 / 120,190,220 | |
| `TextHoldTime` / `TextFadeTime` | 1.4 s / 0.5 s | |
| `Sounds.Phase1` / `Phase2` / `GravityEnter` | `rbxassetid://110361439788976` / `…117348053215881` / "" (none) | stand-ins (D31) |
| `Overheat.Phase1Heat` / `Phase2Heat` / `FatalHeat` | 0.80 / 1.00 / 2.00 | ship heat (ShipSystems) |
| `Overheat.Hysteresis` | 0.88 | exit below threshold × this (0.704 / 0.88) |
| `Overheat.Text` / `Phase2Volume` / `Phase2Sound` | "OVERHEAT" / 0.9 / `…90441956558948` | |
| `Crash.FadeOutTime` / `BlackHoldTime` / `FadeInTime` | 0.30 / 1.6 / 0.9 s | crash without the cinematic |

### 3.4 `ShipConfig.Destruction` (`ShipConfig.lua:597-659`)

| Key | Value | Meaning |
|---|---|---|
| `Enabled` | true | false: the old short crash without the scene |
| `Duration` | 11.0 s | cinematic until the fade |
| `FadeOutTime` / `BlackHoldTime` / `FadeInTime` | 1.2 / 1.4 / 1.2 s | the fade after the cinematic |
| `Camera.FieldOfView` | 60 | |
| `Camera.MinRadius` | 14 | smallest reference size (studs) |
| `Camera.StartDistance` / `Kick` / `KickTime` / `Rise` | 2.6 / 0.9 / 0.45 s / 0.42 per s | distance in wreck radii |
| `Camera.StartElevation` / `EndElevation` | 36° / 68° | |
| `Camera.Orbit` / `AimAhead` | 3.5°/s / 0.06 | |
| `Camera.Shake` / `ShakeTime` | 2.2° (× CAMERA SHAKE) / 1.4 s | |
| `Debris.HullPieces` / `Fragments` / `Embers` | 34 / 26 / 8 | |
| `Debris.Burst` | 40–95 studs/s | fly-off speed (a ship of radius 40) |
| `Debris.Carry` / `Spin` / `Bounce` / `Friction` | 0.35 / 5 rad/s / 0.3 / 0.38 | |
| `Debris.Gravity` | 30–95 | gravity for debris, clamped |
| `Debris.Burning` / `Char` / `MinSize` | 6 / 0.6 / 0.06 | |
| `Sounds.Volume` | 1.0 | × EFFECTS |
| `Sounds.Explosion` | `rbxasset://sounds/impact_explosion_03.mp3` | |
| `Sounds.BoomVolume` / `DeepVolume` / `DeepPitch` / `PopVolume` | 2.2 / 1.6 / 0.55 / 1.2 | |
| `Sounds.Rumble` / `RumbleVolume` / `RumblePitch` / `HitVolume` | `rbxassetid://105319176350102` / 1.1 / 0.62 / 0.9 | |
| `Gui.FlashTime` / `FlashStrength` | 0.55 s / 0.85 | |
| `Gui.BarHeight` / `BarTime` | 0.085 / 0.7 s | cinema bars, share of the screen height |
| `Gui.TitleIn` / `TitleTime` / `Pop` | 0.6 s / 0.8 s / 1.12 | |
| `Gui.LossesTime` / `RecoveryIn` / `RecoveryTime` | 0.5 s / 1.6 s / 0.6 s | |

### 3.5 Values from other sections this area reads

| Value | Line | Use |
|---|---|---|
| `Surface.SceneScale` 470 | `:744` | swap, HUD blending, `switchScene` |
| `Surface.SwitchAltitude` 0.028 | `:767` | × R: swap height (port: the absolute `hs = 0.028·Rs·470`) |
| `Surface.ExitAltitude` 8000 / `ExitShare` 0.6 | `:764-765` | exit = `max(1.5·level, min(8000, 0.6·hs))` |
| `Surface.SafeLandingSpeed` 38 | `:760` | crash threshold on the ground (04) |
| `Surface.GravityScale` 0.35 | `:757` | destruction gravity source |
| `Handling.FeltStudsPerG` 9.81 | `:432` | felt-G clamp |
| `Physics.StudsPerG` 60 | `:2436` | GROUND crash in space: default gravity |
| `Camera.SeatOffset` (0, 0.5, 0) | `:2655` | eye in ship space (EntryFx) |
| `Hull.GearDownAltitude` 60 | `:2619` | gear (02) |
| `Ui.Fonts.UiBold` Sarpanch Bold; `Ui.DangerColor` 255,70,60; `WarnColor` 255,165,70; `AccentColor` 255,170,60; `PanelColor` 206,226,240 | `:2017-2046` | warnings text; proposed card colours |
| `Keys.Land` L | `:2813` | prompt |
| `Voice.Callouts.EntryHeatStart` 0.25, `TurbulenceDelay` 3, `TurbulenceMin` 0.3 | `:2243-2245` | entry callouts (09) |
| Settings `CameraShake` (0–1, default 1), `EffectsVolume` (0–1), `FieldOfView` | `ShipSettings.lua:35-41` | shake, volumes, FOV restore |

### 3.6 Module constants

| Module | Constant | Value |
|---|---|---|
| ShipAtmosphere | `DEFAULT_GLOW` | 255,140,60 |
| ShipSky | `LATITUDE` | 23.5° |
| | `DUSK` | 232,126,80 (dome dusk when the profile has none) |
| ShipClouds (`TUNING`, `:112-179`) | image | `Width` 100,000; `ImageSize` 512; `Warp` 1.35; `EdgeFade` 0.55; `OutsideRho` 0.66; `GridStep` 32; `CalibrationSamples` 1200; `FrameBudget` 0.002 s; `RegenDistance` 9,000; `RetryDelay` 12 s |
| | field | `FeatureSize` 6,500; `Octaves` 5; `Gain` 0.5; `Lacunarity` 2.07; `Swirl` 0.55; `Patchiness` 0.12; `Softness` 0.07; `ThickRange` 0.22; `Shade` 0.3 |
| | dome | `DomeRadius` 1,000; `DomeSightShare` 0.4; `DomeRings` 22; `DomeSegments` 40; `DomeCurve` 1.7; `BandElevation` 32°; `BandRadius` 2,450; `BandSightShare` 0.9; `BandSectors` 8; `AlphaPass` 0.02; `Margin` 160 |
| | light | `HazeDistance` 45,000; `HazeColour` 0.55; `HazeAlpha` 0.35; `NightLevel` 0.16; `NightAmbient` 0.5; `Underside` 0.3; `SunsetWarmth` 0.45; `DEFAULT_HAZE` 196,212,232; `SUNSET` (1, 0.7, 0.48); UV range 0.002–0.998 |
| | puffs | `PuffTexture` smoke_main; `PuffCells` 16; `PuffSpacing` 190; `PuffIdleInterval` 0.9 s; `PuffLead` 0.85 s; `PuffLeadMin`/`Max` 140/720; `PuffReach` 900; `PuffJump` 3,000; `PuffCellSize` 460 × 240 × 460; `PuffBurst` 14; `WispBurst` 4; `PuffSize` 120; `WispSize` 55; `PuffLifetime` 4.5–6.5 s |
| | white | `WhiteoutLead` 200; `WhiteoutRamp` 0 |
| ShipEntryFx | see 2.5.2 | `COLUMNS` 7, `RAILS` 12, `STEP` 4, `SPARSE_FILL` 0.2, rail RNG seed 1906, `DEFAULT_COLOUR` 255,122,44 |
| ShipDestruction | `CHAR` / `EMBER` | 26,22,20 / 255,128,48 |
| | `SEAM_MARGIN` | 1.2 wreck radii (Roblox only) |
| | cooldowns | hit 0.09 s, puff 0.04 s |
| | water spray / dust base | 214,226,232 / mixed towards 150,140,128 (0.3 with a ground colour, 0.45 from material) |

### 3.7 Derived numbers per Sol body (computed)

Port radius `R = Rs·3000` (Rs in studs, `03-world.md`). Ranges are measured from the **virtual barrier** `rB = (ceil(1.105·Rs) + 6)·S` so the planet's angular size at each prompt is Roblox's; given here as height above the mean surface. `hs = 0.028·Rs·470` is Roblox's switch altitude in scene metres, kept absolute. Air top = profile `thickness`; limb top = 3 × thickness (8.7). Bodies without air have no heat.

| Body | R port (km) | DESCEND TO LAND below (km) | LAND assist below (km) | re-arm above (km) | `hs` (m) | heat full above / zero below (m) | level (m) | exit (m) | limb / air top (km) |
|---|---:|---:|---:|---:|---:|---|---:|---:|---|
| Mercury | 2,430 | 4,747 | 1,126 | 3,070 | 10,660 | — | 900 | 6,396 | 50 km flow zone |
| Venus | 6,060 | 11,807 | 2,778 | 7,626 | 26,583 | 15,950 / 6,646 | 900 | 8,000 | 120 / 40 |
| Earth | 6,360 | 12,389 | 2,913 | 8,001 | 27,899 | 16,740 / 6,975 | 900 | 8,000 | 78 / 26 |
| Luna | 1,740 | 3,403 | 810 | 2,202 | 7,633 | — | 900 | 4,580 | 50 km flow zone |
| Mars | 3,390 | 6,613 | 1,562 | 4,274 | 14,871 | 8,922 / 3,718 | 900 | 8,000 | 66 / 22 |
| Phobos, Deimos | 450 | 894 | 224 | 584 | 1,974 | — | 790 | 1,184 | 50 km flow zone |
| Io | 1,830 | 3,580 | 854 | 2,318 | 8,028 | — | 900 | 4,817 | 50 km flow zone |
| Europa | 1,560 | 3,053 | 729 | 1,977 | 6,843 | — | 900 | 4,106 | 50 km flow zone |
| Ganymede | 2,640 | 5,155 | 1,221 | 3,333 | 11,581 | — | 900 | 6,948 | 50 km flow zone |
| Callisto | 2,400 | 4,686 | 1,110 | 3,030 | 10,528 | — | 900 | 6,317 | 50 km flow zone |
| Titan | 2,580 | 5,038 | 1,194 | 3,258 | 11,318 | 6,791 / 2,829 | 900 | 6,791 | 108 / 36 |
| Triton | 1,350 | 2,646 | 634 | 1,714 | 5,922 | — | 900 | 3,553 | 50 km flow zone |

---

## 4. State machines, timings, thresholds, edge cases

### 4.1 Roblox states and phases

```
SPACE ──L (≤ 3 s old), in hover range (altitude above barrier < 0.35 R), landable, not rearmPending──▶ ENTRY/HEAT
ENTRY/HEAT ──height ≤ 0.028 R: onSwitch DOWN──▶ ENTRY/DESCENT ──height ≤ 1.8·level──▶ ENTRY/LEVEL
ENTRY/LEVEL ──settled or 7 s──▶ SURFACE (arrival toast)
SURFACE ──scene altitude > exit altitude: requestAscent──▶ EXIT/CLIMB ──height ≥ hs: onSwitch UP──▶ EXIT/EMERGE
EXIT/EMERGE ──height ≥ barrier + 0.5 R──▶ SPACE (rearmPending until out of 1.15 R)
abort (crash): HEAT → SPACE (rearm); DESCENT/LEVEL/CLIMB → SURFACE; EMERGE → SPACE
crash on the ground → in the black: surface:finish + resetToSpace → SPACE
```

### 4.2 Assist timeline (computed: the guide law at dt 1/120 s with the 0.45 s velocity smoothing, curvature included, from the top of the hover range, deck slowdown, LEVEL release rule)

| Body | Roblox: swap / deck in / deck out / LEVEL / release (s) | Port (continuous, HEAT to `hs`): same events (s) | Release height, speed |
|---|---|---|---|
| Earth | 6.43 / 10.03 / 11.37 / 11.63 / **13.91** | 9.53 / 12.97 / 14.27 / 14.54 / **16.81** | 869 m, 328 m/s |
| Mars | 6.43 / 9.23 / 10.43 / 10.43 / 12.74 | 9.53 / 12.19 / 13.34 / 13.34 / 15.65 | 870 m, 327 m/s |
| Venus | 6.43 / 9.41 / 11.45 / 12.32 / 14.47 | 9.53 / 12.36 / 14.35 / 15.21 / 17.37 | 863 m, 332 m/s |
| Titan | 6.43 / 8.38 / 9.91 / 10.44 / 12.67 | 9.53 / 11.36 / 12.82 / 13.34 / 15.56 | 866 m, 329 m/s |
| Luna (no deck) | 6.43 / – / – / 9.04 / 10.98 | 9.53 / – / – / 12.00 / 13.91 | 847 m, 347 m/s |
| Phobos (level 790 m) | 6.43 / – / – / 7.01 / 9.01 | 9.53 / – / – / 10.06 / 11.97 | 745 m, 342 m/s |

- The HEAT phase lasts 6.4 s in Roblox (0.455 R → 0.028 R) and 9.5 s in the port (0.455 R → absolute `hs`): the planet is 6.38 times larger in metres while `hs` stays absolute (3 s more for `2.2·ln(…)`). Speed at the swap height: Earth 39–41 km/s.
- Every descent ends **56° of arc downrange** of the start (the glide covers 2.1 × the altitude lost, scale-free). On the port's Earth that is about 6,200 km: the pilot aims the landing region with the heading before tapping LAND.
- Ascent (CLIMB from the exit altitude in level flight at 320 m/s): Earth Roblox 3.2 s to `hs`, free at 8.9 s (1,290 studs above the surface, 786 studs/s); port 3.2 s to `hs`, free at 12.4 s at 3,884 km and 2.37 Mm/s (inside the far law's departing limit `1000 + h/1.0`).

### 4.3 Heat and glow

| Phase | Roblox heat |
|---|---|
| HEAT | `entry.heat·smooth(0, 1.2, clock)`: full 1.2 s after L, in space |
| DESCENT | `entry.heat·smooth(0.25, 0.6, h/hs)`: Earth full above 16.7 km, zero below 7.0 km |
| LEVEL, CLIMB, EMERGE, SURFACE, SPACE | 0 |

Plasma visible (heat ≥ 0.15) in the Roblox assist: 8.0 s on Earth, full (≥ 0.9) 6.7 s. EntryFx stages switch on at heat 0 (glow), 0.05 (flame), 0.1 (edge), 0.2 (shock), 0.3 (streak), 0.35 (flow); the style rebuilds in 5 % steps; sound attack 0.25 s, release 0.08 s, silent below 0.02.

### 4.4 Sky kind (ShipSky)

```
space ──dome opaque (> 0.99), air──▶ day (skybox day, day ≥ 0.35, below the deck middle) or haze
day ⇄ haze only when whiteout ≥ 0.6 or past the deck (day: below bottom; haze: above top)
day/haze ──dome opacity ≤ 0.95──▶ space (stars)
```
On Earth by day: the blue image under the deck, the haze image above it; at dusk (day < 0.2) the dome turns translucent and the stars return as soon as its opacity drops below 0.95.

### 4.5 Clouds

| Region (Earth deck 2,100–2,900 m) | View | Dome | Whiteout (at full coverage) | Puffs |
|---|---|---|---|---|
| above 3,100 m | top | full | 0 | none above 3,800 m |
| 2,900–3,100 m | top (full above 3,060, fading out below it) | fading | rises from 0 at 3,100 to 1 at 2,900 | spawn |
| 2,100–2,900 m | none | hidden | `alpha` (cloud cover at the eye) | spawn |
| 1,900–2,100 m | bottom (fading in below 2,100, full below 1,940) | fading | falls from 1 at 2,100 to 0 at 1,900 | spawn |
| below 1,900 m | bottom | full | 0 | none below 1,200 m |

Image regeneration: whenever the eye is more than 9 km (in the tangent plane) from the image centre; the old image stays until the new one is complete; a failed image retries after 12 s.

### 4.6 Warnings

| Track | Enter | Exit | Sound | Text |
|---|---|---|---|---|
| Impact phase 1 | tti ≤ 10 s | tti > 13.5 s | Phase1 tone, 2 plays, 2.5 s apart (only on a fresh entry from 0) | IMPACT, held 1.4 s per play |
| Impact phase 2 | tti ≤ 3.5 s | tti > 4.725 s (→ 1 if ≤ 13.5) | loop 0.9, fade τ 0.35 s | IMPACT (held) |
| Overheat 1 | heat ≥ 0.8 | heat < 0.704 | Phase1 tone ×2 | OVERHEAT |
| Overheat 2 | heat ≥ 1.0 | heat < 0.88 (→ 1 if ≥ 0.8) | overheat loop 0.9 | OVERHEAT (held) |
| Gravity | `inInfluence` false → true | | none | GRAVITATIONAL INFLUENCE (blue) |

Priority: overheat > impact (any overheat phase silences the impact track); phase 2 > phase 1. During the crash sequence everything is silent.

### 4.7 Crash flow (`startCrash`, `ShipClient.lua:1474-1666`; `respawnAfterCrash`, `:1419-1468`)

```
startCrash(cause, gravityState, hit):
    if warnings busy or destruction active: return
    cancel a planned (not travelling) jump "DESTROYED"; atmosphere:abort() AT ONCE ("else the entry could still switch
        to the surface in the seconds until the fade"); aboard reset; voice interrupt + "HullFailure"
    station = nearest to the real crash position (scene → real pose); remember its id
    lost survey keys (for the server); cinematic (if Destruction.Enabled): build the scene record (2.7.2), start
        on failure: finish, fall back to the plain crash
    if started: hide the flight UI, hull invisible, audio muted, music hushed
    async: server ReportDestroyed → un-chart lost bodies; lines "REPLACEMENT FEE <cr>", "<n> T CARGO LOST", "DATA LOST <cr>"
        joined by "  ·  "; voice ReplacementFee / CargoLost / SurveyDataLost after the title cue;
        during the cinematic → destruction:setLosses(text), else toast "SHIP DESTROYED" (urgent, 7 s)
    warnings:crash(respawnAfterCrash, started ? {delay = 11/slow, fadeOut 1.2, hold 1.4, fadeIn 1.2} : defaults)
respawnAfterCrash (in the black):
    destruction:finish(); hull visible, audio unmuted; traffic clear; thrust reset; systems reset; docking reset;
    stations forget; interior reboot; voice "Rebooting"; autopilot off; cursor centred
    in the scene: real pose above the body, surface:finish(), atmosphere:resetToSpace()
    stations shown and placed; spawn = stationSpawn(crash station or last/home station) or the system spawn
    flight UI back, markers visible; after hold + fadeIn: voice "SystemsOnline" (unless another cinematic runs)
```

| Time (s) | Cinematic crash |
|---:|---|
| 0 | explosion, flash (screen 85 % → clear by 0.55 s), "Hull failure", bars start (0.05) |
| 0.45, 1.3 | after-explosions at the wreck |
| 0.6 | title fades in (pop 1.12 → 1 by 1.4 s); voice "Ship destroyed", then the loss lines |
| 1.6 | recovery block fades in (by 2.2 s); voice "Replacement ready" |
| 2.2–6.4 | late pops at burning debris |
| 11.0 | fade to black starts (1.2 s, Quad Out) |
| 12.2 | black; respawn; timer "RESPAWNING" |
| 13.6 | fade-in starts (1.2 s) |
| 14.8 | picture back; "All systems online" |

Plain crash: fade 0.3 s, black 1.6 s (respawn), fade-in 0.9 s.

### 4.8 Edge cases handled in the Lua

- **Request just outside the range:** the 3 s window lets a press count when the ship enters the hover range shortly after; a press that never lands gives the voice "LandingBlocked" after 3.1 s.
- **Re-entry right after an abort or an ascent:** `rearmPending` blocks entry and the land prompts until the ship has left the 1.15 R entry range.
- **The body moves on its orbit and the world shifts** during HEAT/EMERGE: the guidance works relative to the body (`relative`).
- **Swap refused** (`onSwitch` returns nil): `abort()`.
- **Crash during entry:** aborted immediately, not in the black.
- **NaN heat or dt spikes** in EntryFx: heat NaN → 0, dt clamped to 0.1.
- **Camera replaced** (EntryFx re-parents its model).
- **No hull model** or one without visible parts: the 40-stud default profile.
- **The cinematic throws:** plain crash; the blackout callback is protected so `busy` cannot stick.
- **Losses arrive before or after the title:** lines are spoken only after the title cue, in order.
- **Impact warnings off** in space and in the scene (tti forced to ∞): the attitude danger colour and the dust "danger" mood therefore never triggered from terrain.
- **Hits only while guided** (`self.phase`): free flight in the scene never knocked.
- **A leftover Atmosphere object in space** would leave the sky blue: `sweep()` every space frame.

---

## 5. On screen

### 5.1 Prompts and the arrival toast (strings; layout in `06-cockpit-hud.md` §6.9)

| String (Roblox) | When | Port string |
|---|---|---|
| `LANDING BLOCKED  <reason>` (e.g. `NO SOLID SURFACE`) | SPACE, near a body with `LandingBlocked` | `LANDING BLOCKED · <reason>` (D11) |
| `PRESS L TO LAND` | SPACE, landable, in hover range | touch `TAP LAND TO BEGIN ENTRY`, gamepad `PRESS ▶ TO BEGIN ENTRY` (D-pad right), keyboard `PRESS L TO LAND` |
| `DESCEND TO LAND` | SPACE, landable, within 1.84 R of the barrier, not re-arming | same |
| `ATMOSPHERIC ENTRY` / `DESCENT` | HEAT or DESCENT, air / no air | same; in free flight while heat ≥ 0.15 |
| `LEVELLING OFF` | LEVEL | same |
| `LEAVING ATMOSPHERE` / `ASCENT` | CLIMB or EMERGE | same |
| `CLIMB TO LEAVE ATMOSPHERE` / `CLIMB TO RETURN TO SPACE` | SURFACE | same; above the exit altitude with ORBIT available: `TAP ORBIT TO LEAVE ATMOSPHERE` / `TAP ORBIT TO RETURN TO SPACE` |
| Arrival toast: title `<BODY>`, body `<TITLE>  ·  <g> G  ·  ATMOSPHERE` or `… NO ATMOSPHERE` (g with 2 decimals), not urgent, 6 s | on release (Roblox); port also on the first descent below the exit altitude per visit | same |
| Toast `SHIP DESTROYED` / `<losses>` (urgent, 7 s) | losses arrive without the cinematic | same |

In SPACE, landing and docking prompts stack in one plate (`ShipClient.lua:1048`).

### 5.2 Warning text and blackout (`ShipWarnings`)

Roblox: label anchored top-centre at **16 % of the screen height**, 520 × 56 px box, **Sarpanch Bold 34 px**, **RGB 255,55,55** (`GRAVITATIONAL INFLUENCE` in **120,190,220**), 2 px black outline, shown fully for 1.4 s after the last trigger, then fading over 0.5 s. Above every other GUI (DisplayOrder 100). Blackout: a full-screen black frame (ZIndex 10) faded with Quad Out.

**Phone** (reference 929 × 417 u, `06` §6.9): centre x, **top at 0.22 H** (92 u: the toast slot occupies st+40…86 u, so 0.16 H would collide), **Sarpanch Bold 20 u** (34/1080 of the height would be 13 u ≈ 2 mm: too small for an alarm), colours as Roblox, 1.5 u black outline, one line, no plate. Blackout: one full-screen quad, drawn last (over the touch controls; they are hidden during the cinematic anyway).

### 5.3 Heat vignette (`EntryFx` edge)

Roblox: `StarterGui.EntryFx` (not exported): four frames `Top`, `Bottom`, `Left`, `Right` at the screen edges whose built sizes are the full-heat sizes, coloured with the plasma tint, opacity `0.65 × stage(Edge) × shimmer` (shimmer 0.7–1.3, slow noise per side), size growing from 45 % to 100 % of the built thickness; plus a full-screen `Wash` at `0.05 × stage × flicker`; ×0.55 in the outside view.

**Phone proposal** (the built sizes are unknown): four gradients drawn in the UI pass, full-heat thickness **top 0.16 H, bottom 0.12 H, left and right 0.09 W**, colour = plasma tint, alpha at the edge `0.65·amount·shimmer` falling quadratically to 0 at the inner edge (Roblox frames are flat colour; a gradient reads better on OLED and hides the rectangle), thickness × `0.45 + 0.55·amount`; wash as Roblox. Drawn under the HUD text and the touch controls, above the world.

### 5.4 The SHIP DESTROYED card (`ShipDestruction`)

Roblox structure (2.7.8; layout not exported): design canvas 1920 × 1080 scaled by `min(W/1920, H/1080)`; `Flash` full screen; `BarTop`/`BarBottom` black cinema bars growing to **8.5 % of the height each**; `Title` ("SHIP DESTROYED", pop scale 1.12 → 1) with `Detail` (`<SHIP>  ·  <WHERE>`) and `Losses`; `Recovery` with `Station`, `Timer` and a progress `Bar`/`Fill`.

**Phone proposal** (u, 929 × 417 reference):

| Element | Rect / anchor | Style |
|---|---|---|
| Flash | full screen | RGB 255,236,205, alpha `0.85·f²` (2.7.8) |
| Bars | top and bottom, height `0.085·H·smooth(…)` | black, opaque |
| Title | centre x, centre y at 0.38 H | "SHIP DESTROYED", Sarpanch Bold **40 u**, tracking 0.12, `Ui.DangerColor` 255,70,60, 2 u dark outline (6,10,16), scaled by the pop factor about its centre |
| Detail | centre x, 26 u under the title centre | Titillium Web SemiBold 13 u, `PanelColor` 206,226,240 |
| Losses | centre x, 18 u under the detail | Roboto Mono 11 u, `WarnColor` 255,165,70 |
| Recovery | centre x, bottom at H − bar − 22 u | station name Sarpanch Bold 14 u `PanelColor` (`NO STATION IN RANGE` if none); timer Roboto Mono 12 u `DimColor` 120,145,165 ("RESPAWN IN 12" / "RESPAWNING"); bar 260 × 4 u, track (6,10,16) 70 %, fill `AccentColor` 255,170,60 |

Alphas per group follow 2.7.8; the HUD, markers, dust and speed lines are hidden while the card shows (Roblox `setFlightUiHidden`).

### 5.5 The 3-D look

**Plasma** (per layer and colour: 2.5.4–2.5.6). Earth orange-pink 255,118,92; Mars deep red 205,52,26; Titan gold 255,186,64; Venus and generated bodies the profile glow (Venus 255,150,60, generated 255,140,64). The hot spots are near white (60 % to white), the flames darken to `(0.75 R, 0.32 G, 0.22 B)` at their end. Everything additive.

**Clouds.** Top side `colour × level`, underside `colour·(1 − dark) × level` (Earth: 255 → 178 grey underneath), thick cores up to 30 % darker in the image, a warm sunset tint `(1, 0.7, 0.48)` when the sun is low, the night ambient at night, towards the horizon 55 % of the way to RGB 196,212,232 × level and 35 % more transparent (e-fold 45 km).

**Sky targets** (computed from the Roblox parameters with the port formulas of 8.7: zenith and horizon colour before tone mapping, sun elevation high (day 1) and low (day 0.4), Lz/Lh as 8.7):

| Body | Height | Day | Sky kind | Density | Zenith share of haze | Zenith RGB | Horizon RGB |
|---|---:|---:|---|---:|---:|---|---|
| Earth | 2 m | 1.0 | day | 0.128 | 0.82 | 50,109,176 | 50,109,175 |
| Earth | 2 m | 0.4 | day | 0.078 | 0.64 | 42,67,101 | 41,67,101 |
| Earth | 5 km | 1.0 | haze | 0.091 | 0.90 | 181,198,219 | 181,198,219 |
| Earth | 15 km | 1.0 | haze | 0.032 | 0.56 | 110,118,129 | 110,118,129 |
| Mars | 2 m | 1.0 | haze | 0.160 | 0.88 | 207,168,138 | 208,167,136 |
| Mars | 15 km | 1.0 | haze | 0.026 | 0.47 | 98,81,68 | 98,81,68 |
| Venus | 2 m | 1.0 | haze | 0.220 | 0.95 | 220,196,150 | 220,196,150 |
| Titan | 2 m | 1.0 | haze | 0.240 | 0.96 | 194,149,94 | 194,147,91 |

Reading: the Earth sky is a deep, fairly even blue under the deck and a pale white-blue above it (Roblox swaps the sky image inside the deck); it darkens to grey above the exit altitude (the dome goes black there) and to black above 26 km. Mars is butterscotch, Venus pale yellow, Titan orange; dusk dims and warms or cools by the profile's dusk colour.

**Destruction.** The emitter table (2.7.4) is the look; sizes scale with the wreck radius r (Varrow about 40 m: blast core up to 2.3r = 92 m, fireballs 48 m, shock ring 6r = 240 m, smoke column puffs up to 3r = 120 m).

---

## 6. Dependencies

### 6.1 Inputs this area needs

| From | Data |
|---|---|
| `02-flight.md` | dominant body and the body-relative position and velocity (§9.4); `hB` above the virtual barrier; Flight Assist state; throttle API (`setThrottle`, `throttleFor`, `CruiseMaxSpeed`, `topSpeed`); the speed limit at an altitude (release); thrust input enable (controlled); felt acceleration sink (systems G); `inInfluence`; ship heat (systems, 0..2.2) |
| `03-world.md` | body records: R (port), Rs (studs, for ratios), `Landable`, `LandingBlocked`, display name, class, seed, `SurfaceGravityG`, `HasAtmosphere`/atmosphere colour; star position (sun direction); the body frame (no spin, D7) |
| `04-planet-surface.md` | `altitudeAt`, `upAt`, `floorAt`, `floorHit`, `groundState`, `surfaceColour`, `materialAt` (water), contact and the crash rule (impact > 38 on solid), `SurfaceProfiles.forBody`, terrain shader hook for fog |
| Ships import (task 7) | hull mesh per ship with node groups (Hull, Cockpit, Canopy, Bridge, Interior, Engines, Gear), bounds, seat offset; debris chunks cut from the hull |
| `06-cockpit-hud.md` | prompt plate, toast, status line (`ATMOS ENTRY`, `ASCENDING`, `SURFACE`, `IMPACT WARNING`, `HULL FAILURE`), LANDING lamp, attitude indicator flags, cockpit shake composition, UI hide during the cinematic |
| `08-stations-economy.md` | nearest station to a position, station spawn pose, `ReportDestroyed` losses (fee, cargo, data), un-charting |
| `09-life-npc-audio.md` | `AudioOut`/`SoundBank` (loops: entry wind, entry rumble, warning phase 2, overheat; one-shots: warning phase 1, explosion boom/deep/pop, metal hits; destruction rumble loop), `ShipAudio.playHit`, voice lines (HullFailure, ShipDestroyed, ReplacementReady, Rebooting, SystemsOnline, ReplacementFee, CargoLost, SurveyDataLost, LandingBlocked, AutopilotAtmosphere), callouts |
| Settings (`07`) | CAMERA SHAKE, EFFECTS volume, FIELD OF VIEW (restored after the cinematic), haptics on/off |

### 6.2 Outputs and who consumes them

| Output | Consumers |
|---|---|
| `state` SPACE/ENTRY/SURFACE/EXIT, `phase`, `isGuiding`, `isBusy` | flight (controlled → input off), autopilot (disengage "ATMOSPHERE"), warp (block/cancel "ATMOSPHERE"), traffic and survey (off), mining (SURFACE), on-foot (`canStand`), HUD status line and LANDING lamp, attitude indicator (D15), dust air mode, callouts (09: EntryStarted, EntryHeat, CloudDeck, Turbulence, LevelOff, AscentStarted, LeavingAtmosphere, OrbitReached) |
| guided pose, velocity, acceleration, felt acceleration, release | flight model, systems (G, blackout), throttle |
| `heat`, `heatColour`, `turbulence`, knock events | EntryFx, audio, callouts, haptics |
| `prompt`, arrival | HUD prompt, notices |
| sky state: sun colour/intensity, ambient, fog density and colour, whiteout, day, sky kind, star visibility, sun disc | SceneRenderer (lit shader), terrain shader (04), clouds, dust, star field, BodyRenderer |
| `timeToImpact` (gated), warning phases, `busy` | HUD (status, WARNING lamp), attitude danger colour, dust mood, audio, callouts |
| crash events (started, black, respawned) | every module that must pause or reset (`setFlightUiHidden`, traffic, docking, systems, autopilot, interior reboot) |

---

## 7. Gap analysis against the current Kotlin code

| Feature | Kotlin today (file, function) | Differs from Roblox | Missing / action |
|---|---|---|---|
| Landing | `Game.doAction`, `landing`, `landable`, `collideBodies`, `holdLanded`, `takeOff`, `updateGear` (`Game.kt:966-1047`, `1677-1725`): a landing *mode* (speed cap 200 m/s, vertical thrust ×0.1, touchdown below 8 m/s vertical and 40 m/s total at ≤ 37° tilt, then parked; "LANDING CANCELLED" above 72 km) | not a guided entry; different touchdown rules (Roblox: crash above 38 m/s on visible ground, otherwise slide and rest) | replace by `EntryAssist` + 04's contact; keep TAKE OFF as a phone convenience |
| ACTION button | `TouchFlightControls.actionBtn`, `Game.actionLabel` (TAKE OFF / CANCEL / DOCK / LAND; LAND in the LOW ALTITUDE zone < 60 km) | availability by zone, not by Roblox's hover range | availability rules of 8.5, ORBIT, priority CANCEL > TAKE OFF > LAND > DOCK > ORBIT |
| Landing HUD | `Game.drawLandingHud`, `drawGear` | 02 and 06 replace them (attitude indicator, D20) | none here |
| Atmosphere from space | `BodyRenderer.atmoParams`, `BodyShaders.ATMOSPHERE` (single scattering, shell thickness relative: TERRAN 0.025 R = 150 km, scale height 0.18 × shell), inside-air full-screen sky, `skyFade` dims stars | relative heights; no profile | absolute `limbTop` and `Hs` per body (8.7); keep the scattering model for the limb |
| Sky inside the air | physical scattering sky, opaque by day (`BodyMath.zenithSkyAlpha`) | not the Roblox colours (zenith dome, haze colour by sky kind, dusk tint) | `profileSky()` in the planet shader with a crossfade |
| Light | `SceneFrame.sunDir/sunVisibility/fillDir/fillColor`, constant `Environment.sunColor` × distance falloff, constant ambient | no ground light, no day/night ambient, no moonlight | `SkyModel` light outputs into `SceneFrame` |
| Fog / haze | none | | fog uniforms in the lit, terrain and cloud shaders |
| Clouds | none | | `CloudField`, `CloudDeckImage`, `CloudRenderer`, `CloudPuffs` |
| Entry effects | none (the speed lines turn red for "danger") | | `EntryFx` + renderer + `HeatEdge` + sounds |
| Camera shake | `ChaseCamera.addShake` (sine shake, decays 3.5/s, on bumps) | different shape | EntryFx shake composed into 06's cockpit shake |
| Warnings | `Game.drawHud`: blinking HEAT (star altitude < 0.5 R), FLIGHT ASSIST OFF, PROXIMITY (< 60 m to a station hull) | not Roblox's texts, timing or sounds | `Warnings` + `WarningsOverlay`; retire the HEAT/PROXIMITY texts (06) |
| Crash | none: `FlightModel.collideSphere`/`resolveContact` bounce (restitution 0.35), haptic HEAVY, shake | Roblox destroys the ship | `CrashFlow`, `DestructionScene`, `CrashCamera`, renderer, card, respawn |
| Particles | dust field (`Procedural.DustField`), glow billboards (`SceneRenderer.drawShipGlows`) | | `ParticlePool` (ship space and world space) + procedural sprite atlas |
| Audio | none (`Platform` has no audio) | | via 09's `AudioOut` |
| Settings | no CAMERA SHAKE, no EFFECTS volume | | added by 07 |
| Haptics | `Platform.haptic` TAP/TICK/BUMP/HEAVY | | reuse (8.1) |
| Harness | `system` scenario: `teleport-low`, `action` (landing mode) | | scenarios of 9.5, debug commands `land`, `orbit`, `heat`, `sun`, `crash`, `crash-dive` |

---

## 8. Phone adaptation and the seamless design

### 8.1 Inputs

| Roblox | Touch | Gamepad (02 §9.2) | Keyboard (optional, ChromeOS/DeX) |
|---|---|---|---|
| L: land (in hover range) | ACTION shows **LAND**; tap engages the assist | D-pad right | L |
| Cockpit LAND button (lands before it docks) | the same ACTION priority | | |
| automatic ascent above the exit altitude | ACTION shows **ORBIT** (opt-in) | D-pad right | L |
| (no way to cancel) | ACTION shows **CANCEL** while an assist flies | D-pad right | L |
| guided: no controls | stick, lever, ▲▼◀▶, BOOST, FA ignored while an assist flies; MAP, VIEW, PAUSE, LOCK still work | same | same |
| CAMERA SHAKE, EFFECTS settings | settings sliders (07) | | |

**ACTION priority:** CANCEL (assist or docking computer) > TAKE OFF (landed) > LAND > DOCK (08) > ORBIT.

**Haptics** (new, respecting the setting): hull knock → BUMP (strength > 0.9 → HEAVY); crash → HEAVY at t = 0 and BUMP at each after-explosion; warning phase 1 → TICK per play; phase 2 entry → BUMP; assist engage/release → TICK.

### 8.2 Performance (60 fps on a Snapdragon 8 Elite, playable on mid-range)

| Item | Cost | Measures |
|---|---|---|
| Assist, state, sky model, warnings | < 0.05 ms CPU | path prediction: 12 `heightAt` calls (≈ 12 µs, shared with callouts) |
| Cloud dome | 881 vertices of double-precision ray/plane math (≈ 0.05 ms) and a 21 KB dynamic upload per frame; one alpha-blended draw that can cover the screen | per-vertex UVs as Roblox; draw only when the view is top or bottom and visible |
| Cloud image | 512² × (3 swirl + 5 octaves + 1 patch) ≈ 2.4 M noise calls per image: ≈ 60–120 ms on a worker (8 Elite), 150–250 ms on a Cortex-A78 | worker thread, double buffer, the old image stays until the new one is ready; regeneration every 9 km of travel (through the deck band at assist speed about one per 1.5 s) |
| Puffs | up to 224 sprites, each up to 144 m wide: overdraw risk inside the deck | cap HIGH 224 / LOW 96; skip sprites within 25 m of the camera; soft depth fade |
| Plasma | ≈ 180 additive particles + 5 glow quads (the halo up to 120 m in front of the nose covers the screen in the cockpit view) + 12 rails | LOW: halo off, rails 6, rates × 0.6 |
| Destruction | ≈ 800 particles at peak (6 burners × 57, column smoke ≈ 90, fire ≈ 60, bursts), 60 debris pieces, one point light | instanced fragments; LOW: rates × 0.6, 20 hull pieces |
| Sky | the existing full-screen in-air pass plus a few ALU per pixel | |
| Memory | 2 × 1 MB deck textures, particle pools, debris chunk VBOs per ship | |

**Zero allocation per frame:** fixed pools (particles, debris, puff cells), reused state records, prompt strings as constants, the timer text through `TextBuf`, the crash detail built into a `TextBuf` at crash time, the deck worker reuses its byte buffers, no lambdas (the scene record's `floor`/`colour` become interface methods of the terrain façade).

### 8.3 Roblox-only workarounds that disappear

- The **landing scene** (×470), `onSwitch`, `toRealPose`/`toScenePose`, the swap altitude as an event, `relative` (body motion and origin shifts), the HUD number blending (`logLerp`, `s^progress`, `blendOut`), `getCover`, `markTerrainReady`.
- The **space air shells** and their fade (`collectShells`, `fadeShells`): the port's limb is a shader.
- `Lighting`, `Atmosphere`, Sky objects, **`solveSun`** (ClockTime bisection) and the scene yaw to match the sun azimuth, `capture`/`finish`/`sweep` (a forgotten Atmosphere object leaving the sky blue).
- The **sky dome mesh** and the **sky-image swap**: a per-pixel formula and a colour choice; the "space" image is the real star field.
- **Clouds:** EditableImage/EditableMesh budgets, `fixedCopy`, the 2048-stud MeshPart limit, the dome at 1000 studs, the **band pieces** behind the terrain (depth testing does it), `AlphaPass`, Fabric material, the normal turned into the engine light, the coroutine frame budget, `transform` (no re-anchoring), `RetryDelay` for memory failures.
- **EntryFx:** `LockedToPart` because of the floating origin (ship space is natural), the Studio-built heat edge GUI, `VelocityParallel` squash quirks, beam `TextureSpeed` default, the attribute-driven ship template lookup.
- **Warnings:** loops kept playing at volume 0 to avoid clicks (the mixer ramps gains), TweenService and `task.spawn`/`task.wait` (a state machine).
- **Destruction:** `BulkMoveTo`, raycast exclude lists, the far-field **seam limit** (`ShipFarSquash`), the 1920 × 1080 UIScale fit and per-element fades (instead of a CanvasGroup), cloning parts (`charredCopy`), Roblox texture ids.
- **Server round trips:** `ReportDestroyed` becomes a local economy call (08); multiplayer: nothing to send.
- **Request window and `watchLanding`:** the LAND button is only shown when landing works.

### 8.4 Every effect on a continuous descent (Earth, R = 6,360 km)

| Effect | Roblox | Port on the 6,000 km planet |
|---|---|---|
| Land prompt | "DESCEND TO LAND" within 1.84 R of the barrier; "PRESS L" within 0.35 R | same angular sizes: below **12,389 km** and **2,913 km**; L becomes the ACTION button |
| Way down | only the guided entry (sinking alone is stopped by the barrier) | free flight under the speed laws (02 §9.5) **or** the LAND assist |
| Guided descent | HEAT in space → swap at 0.028 R → DESCENT in the scene → LEVEL | one world: HEAT to **27.9 km** (9.5 s), DESCENT (deck at 600 m/s), LEVEL at 900 m, release at **16.8 s**, 6,200 km downrange |
| Swap | invisible scene change at 0.028 R | none; terrain detail streams continuously (04 §9) |
| HUD numbers | blended into scene scale | real altitude above terrain and real speed |
| Air shells | ForceField shells fade out before the swap | thin real-scale limb (top **78 km**, scale height 8.7 km) from the impostor shader, crossfading into the profile sky between **26 km and 14.3 km** |
| Plasma, heat edge, cockpit and hull glow | from L (in space) to 7 km | assist: identical (from engagement to **7.0 km**); free flight: in the band **78 → 7 km** in proportion to airspeed (none below 2.5 km/s, full above 6 km/s) |
| Roar and shake | follow heat | follow heat (both cases) |
| Hull knocks | only while guided | whenever heat or turbulence shake (both cases) |
| Light | space light down to the scene, then ramp | continuous ramp from space light above **14.3 km** to ground light at the ground |
| Day and night | Roblox sun set to the light's elevation | the true star direction (D12); bodies do not spin (D7), so day and night are places, not times |
| Haze | Roblox Atmosphere at the camera's air density | the same law per pixel (8.7): e-fold 3.5 km at the ground on Earth, clearing above, none above 26 km |
| Sky colour, sun disc | coloured dome + sun disc, black above 7.6 km | the same formulas in the sky shader |
| Sky image swap | inside the cloud or past the deck | the haze colour choice by the same rule, with a 0.5 s crossfade when it switches outside cloud |
| Stars | only under the space image | star field dimmed by the sky's alpha; count by the profile (Venus none) |
| Cloud deck | only in the scene (below the swap), a 100 km image around the camera | the same local deck below **36 km** (fading in from 1.3 × 27.9 km), real shells with depth; farther and from higher the planet image's own painted clouds |
| Puffs, whiteout | in the scene | anywhere near the deck band |
| Turbulence | guided DESCENT in the deck only | assist as Roblox; free flight in the deck band in proportion to airspeed |
| Level-off, release | the end of the guided entry | the end of the assist; free flight has none |
| Arrival toast | at release | at release, or the first time below the exit altitude (8 km) per visit |
| Ascent | automatic above 8 km (CLIMB/EMERGE) | free flight; ORBIT offers the same profile to **3,867 km** |
| Impact warnings | off | on, against terrain, gated (8.9) |
| Overheat warnings | at the star | unchanged |
| Crash cinematic | rays against the scene | rays against the terrain and stations (8.10) |
| Respawn | finishes the scene first | nothing to finish |

### 8.5 The landing assist, the ORBIT assist and the derived states

**Definitions per body:** `R` (port radius, m), `Rs` (Roblox radius, studs), virtual barrier `rB = (ceil(1.105·Rs) + 6)·S`, `hB = |p − c| − rB` (Roblox's "altitude above the barrier"), `hAgl = altitudeAt(p)` (04), `hs = 0.028·Rs·470` (absolute), `level = min(900, 0.4·hs)`, `exitAlt = max(1.5·level, min(8000, 0.6·hs))`, `airTop = sky.thickness`, `flowTop = sky.air ? 3·airTop : 50 km` (D15).

**LAND availability** (ACTION "LAND"): dominant body `Landable`; not landed; no assist running; docking computer not flying; `hB < 0.35·R`; `hAgl > 1.06·level` (room to level off).

**Engage:** `phase = hAgl > hs ? HEAT : (hAgl > 1.8·level ? DESCENT : LEVEL)`, `timer = clock = 0`, `hitTimer = 0.475`, profile loaded, controlled (thrust input off, boost cleared, autopilot off).

**Per frame** (after the flight step; while guiding the flight step's translation is skipped, as Roblox suppresses it): the Roblox law of 2.1.5 with these replacements:
```
up = upAt(p) (radial); height = hAgl in every phase                        -- no space/scene split
HEAT:    no swap; when height ≤ hs: phase = DESCENT (continue this frame with the DESCENT law)
         heat = entry.heat·smooth(0, 1.2, clock)
DESCENT: heat = entry.heat·smooth(0.25·hs, 0.6·hs, height)·smooth(0, 1.2, clock)   -- the second factor only matters
                                                                                   -- when engaged below hs
LEVEL, release, nose control, smoothing, felt-G clamp: unchanged
the ship's position and velocity are body-relative (02 §9.4): no `relative` bookkeeping
the speed law does not apply while guiding (controlled, as Roblox); collisions and crash rules stay active
```
**Release:** level pose, `v = forward·|horizontal|`, cursor/stick centred, `throttle = throttleFor(speed, min(CruiseMaxSpeed, limit(hAgl)), topSpeed)` (at 900 m the near-ground limit is 315 m/s, the release speed 320), arrival toast, voice "LevelOff".
**Cancel** (ACTION "CANCEL"): phase off, throttle set to hold the current speed, the state follows from altitude. **Crash:** abort at once.

**ORBIT availability:** landable body, inside `flowTop`, not landed, no assist, `hAgl > exitAlt`, `v·up ≥ −20 m/s`, `hB < 0.5·R`.
**ORBIT per frame:** CLIMB (`rise = max(height/1.9, 300)`, forward 0.55) while `hAgl < hs`, then EMERGE (`rise = max(height/1.5, 20)`) with `emergeHeight = max(rB + 0.5·R − R, 2·startHeight)` (Earth 3,867 km); release with `throttleFor` as Roblox; voice "AscentStarted", "LeavingAtmosphere", "OrbitReached" at the phase edges.

**Derived state** (consumers of 6.2):
```
inside = Landable and hAgl < flowTop·(wasInside ? 1.05 : 1.0)
if assist phase ∈ {HEAT, DESCENT, LEVEL}: ENTRY
elif assist phase ∈ {CLIMB, EMERGE}:      EXIT
elif not inside:                          SPACE
elif hAgl ≤ exitAlt:                      SURFACE
else: vr = v·up; vr < −20 → ENTRY; vr > +20 → EXIT; otherwise keep the last of ENTRY/EXIT (from SURFACE: EXIT, from SPACE: ENTRY)
```
`isBusy` = ENTRY or EXIT; `isOnSurface` = SURFACE; `isGuiding` = an assist phase is set. **Re-arm** (prompt only): after leaving the flow zone upwards, "DESCEND TO LAND" stays hidden until `hB ≥ 1.15·R` once (Roblox `rearmPending`). **Arrival toast**: once per visit (re-armed in SPACE).

### 8.6 Free-flight heat, turbulence and knocks

```
air bodies only (entry.heat > 0):
band(h)  = smooth(0.25·hs, 0.6·hs, h)·(1 − smooth(0.5·limbTop, limbTop, h))        -- Earth: full 16.7–39 km, zero below 7.0 and above 78 km
speed(v) = smooth(PlasmaSpeedMin 2,500, PlasmaSpeedFull 6,000, |v_rel|)               -- airspeed = body-relative speed (no wind, D7)
target   = entry.heat·band(hAgl)·speed(v)
heat     = approach(heat, target, target > heat ? HeatRise/3 = 0.4 s : 0.25 s, dt)
effective heat = max(assist heat, free-flight heat)
turbulence (air and a deck): inside [bottom − 300, top + 300]: cover·smooth(80, 400, |v_rel|) (assist DESCENT: cover, as Roblox)
knocks: shaking = max(heat, 0.6·turbulence) > 0.15 → the Roblox timer and strength (2.1.6), in every state
```
Computed for a straight full-throttle dive on Earth under the speed laws (far law `1000 + h/2.5` above 11.4 km, near band below): heat 0 at 80 km, 0.44 at 60 km, 1.0 from 40 km to 16 km, 0.52 at 12 km, 0 below 9 km: about 4 s of plasma. The Roblox-profile assist: 8.0 s visible, 6.7 s full (4.3); an air-gated assist would only glow 3.2 s (question Q1). `PlasmaSpeedMin/Full` are port tuning.

### 8.7 Sky, light and haze in one model

**Heights** (absolute, answering `03-world.md` Q11 for this area): `airTop = sky.thickness` (Earth 26 km, Mars 22, Venus 40, Titan 36, generated 26, airless 24 for the light ramp only); **limb top = 3·airTop** (Earth 78 km, close to the real visible limb); **scale height `Hs = airTop/3`** (Earth 8.7 km, real 8.5). The impostor keeps its scattering model with `shell = limbTop/R` and a per-body `H = Hs/R` uniform instead of `0.18·shell`; its tint stays the world's atmosphere colour (03).

**Per frame on the CPU (`SkyModel`)**, at the camera: `up = upAt(cam)`, `μs = sunDir·up`, `h = hAgl(cam)`; `day, ground, air, glow, thin`, `Density = lerp(sky.density·0.4·thin, 0.92, whiteout)`, `groundBrightness`, `groundAmbient`, dome `opacity`, `fade`, `domeColour`, sky kind (4.4, plus a 0.5 s colour crossfade when it switches outside cloud), `hazeColour = lerp((kind == day ? FieldFogDay 46,105,167 : desat(sky.colour, 0.35))·glow, scaled(cloudColour, glow), whiteout)`, `glare = sky.glare·air·day·(1 − whiteout)`, `Lz = clamp(max(6000, deck ? deck.top − h + 1500 : 0, 2.5h), 3000, 11400)`, `Lh = clamp(1.25·√(2hR + h²), 3000, 11400)` (11,400 = Roblox's 0.95 × 12,000 default sight).

**Sky shader inside the air** (`profileSky(d)`, in `BodyShaders` for the body the camera is in):
```
μ = d·up;  r = 1/√((1 − μ²)/Lh² + μ²/Lz²)                       -- Roblox's dome reach
f = 1 − exp(−Density·r/FogK),  FogK = 454 m                     -- Roblox's fog e-fold (2.3.4)
c = mix(domeColour, hazeColour, f)
c += sunTint·glare·(exp(−θ/0.12) + 0.25·exp(−θ/0.5))             -- Roblox Atmosphere.Glare; θ = angle to the sun,
                                                                --   sunTint = RGB 255,244,222 (lobe widths: port tuning)
if opacity > 0.99 and μs > −0.03: sun disc 2.2°, RGB 255,244,222, 1 px soft edge
alpha = max(opacity, f, whiteout)                               -- stars show through at night except in haze
final = mix(impostorSky, (c, alpha), w),  w = 1 − smooth(0.55·airTop, airTop, h)
```
**Haze on geometry** (terrain, clouds, ship, stations; the aerial-perspective hook of `04` §9.11): `T = exp(−Density·L/454)` at the camera's density (Roblox's uniform fog: zero from above the air, the 3.5 km e-fold at the ground), `colour = surface·T + hazeColour·(1 − T)`; from space the impostor's limb in-scatter is added on top (04's LUT).

**Light** (into `SceneFrame`): `sun = SUN_COLOR·lerp(sunScale(distance), groundBrightness/2.6, ground)·transmittance(μs)` (the ground value replaces the distance falloff of D12 near the ground; transmittance from the impostor model reddens a low sun); `ambient = lerp(Environment.ambient, linear(groundAmbient)·AmbientGain, ground)` with `AmbientGain` ≈ 0.35 (port tuning: Earth's day ambient becomes about 0.05/0.07/0.10 linear); at night a **moon key** from `−sunDir` with intensity `(1 − day)·0.5/2.6` of the sun colour, cool tint (200,210,255), replaces Roblox's moonlight (`NightBrightness` 0.5). Stars: `starBrightness = (1 − alpha_zenith)·min(1, sky.stars/3000)`.

### 8.8 Clouds in the seamless world

- One `CloudField` per body with a deck: `seed` = body seed, `scale = R/6500` with the port radius (feature size stays 6.5 km), threshold from 1,200 samples at load (port RNG, D10: statistically equal, not bit-equal).
- `CloudDeckImage`: the Roblox algorithm on a worker in double precision, body-fixed tangent frame, 512² RGBA8, regenerated at 9 km, double-buffered texture.
- `CloudRenderer`: the Roblox dome (22 × 40, ring curve 1.7) as unit directions; each frame the CPU computes per vertex the hit distance on the visible shell (top from above, bottom from below, stable formulas of 2.4.6), the UV and the ring colour/alpha; vertices are placed at their true hit points (camera-relative floats; misses at 200 km with the transparent UV). Drawn after the terrain with depth test, no depth write, premultiplied alpha, in the depth slices its distances fall into (04 §9.8), fogged by 8.7.
- **Visibility:** the deck shows below `1.3·hs` and fades in between `1.3·hs` and `hs` (Earth 36.3 → 27.9 km); higher up only the planet image (Roblox showed the deck only in the scene). On bodies without a deck nothing.
- `CloudPuffs`: 16 cells, body-fixed positions (double), sprites in the alpha pass, back to front by cell; whiteout from `densityAt(eye)`.

### 8.9 Impact warnings against terrain (D16)

```
c = −v·up (closing speed, body-relative)
radial: tti_r = c > 0.05 ? (hAgl − clearance)/c : ∞
path:   tti_p, n = first contact of p + v·t for t ∈ {0.5, 1, 1.5, 2, 3, 4, 5, 6.5, 8, 10, 12, 14} s (09's predictImpact,
        interpolated; normal from four neighbours ±2 m)
braked closing speed: cB = FA on ? min(c, MinApproachSpeed 14) : c        -- the near-ground law brakes the closing part (02 §9.5)
v_pred = v + up·(c − cB)
impact = max(−v_pred·up, −0.5·v_pred·n)                                 -- the 04 crash rule on the predicted velocity
tti = impact > ImpactWarnSpeed (0.75·38 = 28.5 m/s) ? min(tti_r, tti_p) : ∞
off: while an assist flies, landed, during the crash sequence, hAgl > 50 km (not computed)
```
A gentle landing (sink below 28.5 m/s, FA on) never warns; an FA-off dive warns 10 s and 3.5 s before contact; level flight into a slope warns through the path term. The warnings module, the attitude danger colour, the status line and the dust mood then work as in Roblox.

### 8.10 Crash and destruction in the seamless world

- Causes: GROUND (04 contact: impact > 38 m/s on solid ground; water is ground, 04 Q10), STATION (08: > 45 m/s), HEAT (ship heat ≥ 2.0).
- Scene record from port data: `up = upAt` (GROUND), the hit normal (STATION) or the ship's up; `gravity = gravityAt` (35 %, then clamped to 30–95 for debris as Roblox); `ground`, `dust` as Roblox; `floor` and `colour` from the terrain façade; `parts` = debris chunks of the ship mesh with their current poses; `size` = ship bounds.
- Rays: debris → `floorAt` along the move (one height query per moving piece per frame), station proxies only near stations; camera occlusion → a 16-sample terrain march per test ray (≤ 6 rays).
- **Carry clamp** (port addition): `|carry| ≤ 120 m/s`; in a seamless world a crash can happen at km/s and the debris would leave the screen in a frame.
- No far-field seam limit; the camera overrides the game camera with FOV 60°; the ship mesh hidden; the cockpit not drawn.
- Respawn: no scene to finish; the assist and state reset; the ship placed at the station spawn (08).

---

## 9. Kotlin port plan

### 9.1 New and changed files

Package `com.starwake.core.atmo` unless noted.

| File | Content | Key API |
|---|---|---|
| `AtmoConfig.kt` | `ShipConfig.Atmosphere`, `Sky`, `Warnings`, `Destruction`, the cloud and entry tunables (section 3) as `const val`s; port tuning (`PlasmaSpeedMin/Full`, `AmbientGain`, `ImpactWarnSpeed`, LOW caps) | |
| `EntryAssist.kt` | 2.1.5 with 8.5: phases, availability, engage, guide, release, cancel, abort, felt acceleration, heat | `canLand(ctx)`, `canOrbit(ctx)`, `engageLand()`, `engageOrbit()`, `guide(dt, ship, ctx): Boolean`, `cancel()`, `abort()`, `phase`, `heat`, `turbulence` |
| `AtmosphereState.kt` | derived state, prompts (pre-built constants per input device), arrival toast, free-flight heat and turbulence (8.6), knock timer and events | `update(dt, ctx)`, `state`, `prompt`, `takeArrival()`, `heat`, `heatColour`, `turbulence`, `pollKnock()` |
| `SkyModel.kt` | 2.3.3 + 8.7: day, ground, air, glow, thin, density, colours, dome, sky-kind machine, light, stars, sun disc | `update(dt, cam, body, profile, sunDir, whiteout)`, reads into a reused `SkyState` |
| `CloudField.kt` | `makeField`, `threshold`, `coverageAt`, `densityAt`, `whiteout` (double, `LuauNoise` of 04) | |
| `CloudDeckImage.kt` | tangent frame, grid, `fillDeck` on a worker (04's `TileJobs` pool or its own thread), regeneration rule, double buffer | `update(eye)`, `pollReady()` |
| `CloudPuffs.kt` | cells, spawn rule, sprite records | `update(dt, eye, tints)` |
| `HullProfile.kt` | 2.5.3 on mesh vertices per node group; cached per ship | `measure(mesh): HullProfile` |
| `EntryFx.kt` | 2.5.4–2.5.6: layout, stages, rates, brightness, flicker, style levels, sound levels, shake, edge amounts | `update(dt, heat, tint, external)`, outputs for the renderer, `shake` (quat + offset) |
| `ImpactPredictor.kt` | 8.9 (shared with 09's callouts) | `update(ship, terrain, fa): tti` |
| `Warnings.kt` | 2.6 machines, texts, sound targets, crash fade sequence (enum state + timers) | `update(dt, tti, inInfluence, heat)`, `crash(timing)`, `onBlack` polled by the game, `busy` |
| `DestructionScene.kt` | 2.7: start, emitter definitions as data, pops, rates, debris physics, sound envelope, card timeline, cues | `start(scene)`, `update(dt)`, `finish()`, `status` |
| `CrashCamera.kt` | 2.7.7 | `update(dt, scene, terrain, out camera)` |
| `CrashFlow.kt` | `startCrash` / `respawnAfterCrash` orchestration (cause, detail, station, losses, cues, hide/unhide, respawn) | `start(cause, …)`, `update(dt)` |
| `gfx/ParticlePool.kt`, `gfx/ParticleRenderer.kt` | shared billboard pool (ship-space and world-space emitters, keyframed size/alpha/colour, additive and alpha passes, velocity-parallel streaks), procedural sprite atlas (flame tuft, soft puff, sparkle, shock ring, smoke) generated at startup | `emit(...)`, `update(dt)`, `draw(pass)` |
| `gfx/EntryFxRenderer.kt` | glow quads, rail ribbons (scrolling repeats), two point lights handed to the lit shader | |
| `gfx/CloudRenderer.kt` | dome mesh, dynamic VBO, deck texture upload, cloud shader with fog | |
| `gfx/DestructionRenderer.kt` | debris chunks (charred tint, shadow off on LOW), instanced fragments, flash ball, scorch decal, crash point light | |
| `gfx/BodyShaders.kt` (change) | `profileSky()`, crossfade, per-body `H` uniform | |
| `gfx/BodyRenderer.kt` (change) | absolute `limbTop`/`Hs`; profile sky uniforms for the body the camera is in | |
| `gfx/SceneRenderer.kt` (change) | `SceneFrame.sunColor`, `ambient`, `fogDensity`, `fogColor`, `pointLights[2]`; fog and point lights in `LitProgram`; draw order (per depth slice, 04 §9.8): sky → opaque terrain, stations, ship, debris → transparent with depth test and no depth write: water, cloud shells, entry FX, destruction particles, puffs, dust | |
| `ui/WarningsOverlay.kt`, `ui/HeatEdge.kt`, `ui/DestroyedCard.kt` | 5.2–5.4 | `draw(ui, frame, …)` |
| `Game.kt` (change) | hooks of 9.2; retire the landing mode | |
| `desktop/…/AtmoScenarios.kt` | 9.5 | |

### 9.2 Hooks into `Game.kt` (order within `updateFlight`)

1. If `crashFlow.cinematic` is running → the destruction frame (step the scene, the crash camera, terrain/sky/clouds keep updating, warnings, voice) and return (Roblox `ShipClient.lua:2337`).
2. Read controls; `controlled = docking || assist.isGuiding` → skip stick, lever, boost and FA input (CANCEL/MAP/VIEW/PAUSE still read).
3. `system.locate` / flight state (02): dominant body, body-relative state, `hB`, `hAgl` (04).
4. Flight step (02). While guiding: no translation from the flight model; then `assist.guide(dt, ship, ctx)` sets pose, velocity, acceleration and felt acceleration; on release set the throttle.
5. Collisions and crash checks (02 heat, 04 ground, 08 stations) → `crashFlow.start(cause)` (aborts the assist at once).
6. `atmoState.update(dt, ctx)`: derived state, prompts, free-flight heat, turbulence, knocks (→ audio `playHit`, haptics), arrival toast.
7. ACTION label from 8.1 priority; `doAction` dispatch (LAND/ORBIT/CANCEL).
8. Camera (06) with `entryFx.shake` composed; `entryFx.update(dt, heat, tint, external)`.
9. `skyModel.update(...)` → `SceneFrame` light, fog, sky uniforms; `clouds.update(dt, eye, sky tints)`; whiteout back into the sky model next frame (Roblox order: clouds inside the sky update).
10. `impactPredictor.update(...)`; `warnings.update(dt, tti, inInfluence, heat)`; when `warnings.onBlack` fires → `crashFlow.respawn()`.
11. Render: `SceneRenderer.render` (the draw order of 9.1); UI: HUD → heat edge → warnings text → destroyed card → blackout.

### 9.3 Order of work

| Step | Deliverable | Proven by |
|---|---|---|
| 1 | `AtmoConfig`, `EntryAssist`, `AtmosphereState` (pure), ACTION rules; retire the Kotlin landing mode; sphere altitude until 04 lands | `EntryAssistTest`, `AtmosphereStateTest`; harness `entry-assist` (log only) |
| 2 | `SkyModel`, light and fog in `SceneFrame` and `LitProgram`, `profileSky()`, absolute limb heights | `SkyModelTest`; harness `sky-day-night`, `entry-assist` PNGs |
| 3 | `Warnings`, `ImpactPredictor`, `WarningsOverlay` (sounds when 09's `AudioOut` exists) | `WarningsTest`, `ImpactPredictorTest`; harness `warnings` |
| 4 | `DestructionScene`, `CrashCamera`, `CrashFlow`, `DestroyedCard`, `ParticlePool` (world space), `DestructionRenderer` (generic debris until the ship chunks exist) | `DestructionSceneTest`; harness `crash-*` |
| 5 | `HullProfile`, `EntryFx`, `EntryFxRenderer`, `HeatEdge`, ship-space particles | `EntryFxTest`; harness `entry-fx` |
| 6 | `CloudField`, `CloudDeckImage`, `CloudRenderer`, `CloudPuffs` | `CloudFieldTest`, `CloudDeckImageTest`, `CloudPuffsTest`; harness `clouds` |
| 7 | free-flight heat, turbulence, knocks; haptics; ORBIT assist | harness `entry-manual`, `orbit` |
| 8 | device tuning (8.7 port constants, LOW caps), real ship meshes and debris chunks | `entry-perf` on the 8 Elite and a mid-range phone |

### 9.4 Unit tests (`core/src/test/kotlin/com/starwake/core/atmo/`)

- **`EntryAssistTest`:** Earth port timeline (HEAT end 9.5 ± 0.2 s, deck in 13.0 ± 0.2 s, LEVEL 14.5 ± 0.2 s, release 16.8 ± 0.3 s at 869 ± 30 m and 328 ± 10 m/s, downrange 56 ± 1°); a two-stage Roblox emulation (space ×1, scene ×470) reproduces 13.91 ± 0.2 s; Phobos levels at 790 m; release conditions (each of the four; timeout 7 s forces a level pose); felt acceleration never exceeds 21.58 m/s²; deck vertical speed ≤ 600 m/s inside the band; heat 0 → 1 over 1.2 s in HEAT and the DESCENT fade at 16.74 km (1.0) and 6.97 km (0); engagement below `hs` starts in DESCENT, below 1.62 km in LEVEL; cancel and abort clear the phase; ORBIT reaches 3,867 km in 12.4 ± 0.5 s.
- **`AtmosphereStateTest`:** the ranges of 3.7 for every Sol body (DESCEND, LAND, re-arm); state machine (ENTRY/EXIT by ±20 m/s with hysteresis at `flowTop`); prompts per state and input device; arrival toast once per visit; knock intervals (2.01 s at shaking 0.15 … 0.95 s at 1) and strengths.
- **`HeatModelTest`:** the free-flight dive table of 8.6; `max` with the assist heat; airless bodies 0.
- **`SkyModelTest`:** day/ground/air/glow/thin at sample points; dome opacity and colour; the sky targets table of 5.5 (± 2 per channel); sky-kind swaps only when hidden or past the deck; stars only under the space kind; light ramp ends at 0.55·airTop.
- **`CloudFieldTest`:** cloud share over 20,000 random directions within ± 0.03 of `cover` for 0.2, 0.56 and 0.92; `densityAt` depth edges (1 inside, 0 at 200 m outside); deterministic for a seed.
- **`CloudDeckImageTest`:** image alpha vs `coverageAt` at 200 random plane points (|Δ| < 0.05 after bilinear filtering); `planeToUV` and its inverse; regeneration triggers at 9 km; edge fade at 27.5–50 km.
- **`CloudPuffsTest`:** spawn every 190 m or 0.9 s; lead clamp 140–720 m; wisp rule; burst counts (2–14, wisps 4).
- **`EntryFxTest`:** stage values; rates and brightness at heat 0.5 and 1; style key quantisation (21 levels); sound level reaches 90 % in 0.58 s rising and 0.18 s falling; shake bounded by 1.4 × the degree and stud amplitudes; `HullProfile.measure` on the default boxes matches the Lua default profile (7 columns, clearance and nose values computed once in Luau and pinned).
- **`ImpactPredictorTest`:** FA-on dive → no warning; FA-off dive → phase 1 at 10 s, phase 2 at 3.5 s; level flight into a 30° slope at 300 m/s → warning; landing at 5 m/s sink → none.
- **`WarningsTest`:** hysteresis tables of 4.6; phase-1 plays exactly twice 2.5 s apart; stepping down 2 → 1 does not replay; overheat silences impact; text hold 1.4 s and fade 0.5 s; crash sequence timings (plain: black at 0.3 s, `onBlack` once, done at 2.8 s; cinematic: black at 12.2 s, fade-in from 13.6 s, done at 14.8 s).
- **`DestructionSceneTest`:** camera distance/elevation/azimuth at the times of 2.7.7 (± 1 %); card alphas and timer strings of 2.7.8; cues at 0.6 s and 1.6 s; pops at 0.45, 1.3 and the late times (± 0.25); rumble envelope; debris bounce (0.3), friction (0.38), rest below 4 m/s, float on water; carry clamp; selection 12 largest + 22 random; embers fade.
- **`AllocationTest`:** 600 frames of every per-frame module (assist, state, sky, clouds incl. puffs, entry FX, warnings, destruction) allocate 0 bytes on the calling thread.

### 9.5 Desktop harness scenarios (`desktop/…/AtmoScenarios.kt`)

| Scenario | What it shows and reports |
|---|---|
| `entry-assist` | Earth-like body (PLANET II with Earth's profile until the Sol world exists) from 0.45 R: `land`; PNGs at 0.5 s, 2 s (plasma in space), 6 s, 9.5 s (`hs`), 11 s (glow fading), 13 s (deck from above), 13.5 s (whiteout), 14.3 s (under the deck), 16.8 s (release); a 4 Hz log of phase, height, speed, heat, whiteout, sky kind; asserts the release time, height and speed |
| `entry-manual` | full-throttle dive from 120 km: PNGs at 80, 40, 20, 12 and 5 km; heat log vs 8.6; no warnings with FA on |
| `orbit` | from the ground: climb to 9 km, `orbit`; PNGs at the CLIMB/EMERGE edge and the release; log |
| `sky-day-night` | ground camera on Earth, Mars, Venus, Titan and Luna at sun elevations 60°, 15°, 2°, −5° and −30°: PNGs and zenith/horizon pixels vs 5.5 |
| `clouds` | top view at 4 km, inside at 2.5 km (whiteout), bottom view at 1.5 km, at noon and at dusk; 30 km of flight at 600 m/s with the regeneration log and puff counts |
| `entry-fx` | static ship at heat 0.1, 0.3, 0.6 and 1.0 in cockpit and external view, heat edge visible; later every imported ship |
| `warnings` | FA-off approach over flat ground and into a slope: phase log, sound events, text PNGs |
| `crash-ground`, `crash-water`, `crash-space`, `crash-station` | PNGs at 0.05, 0.3, 0.6, 1.6, 4, 8, 11.5, 12.2 (black) and 14.8 s; status log (pieces resting, camera height, lift, card texts) |
| `entry-perf` | assisted descent on LOW and HIGH: CPU and GPU ms per frame, particle counts, deck regenerations and worker times |

New debug commands: `land`, `orbit`, `heat <0..1>` (force entry FX), `sun <elevation>`, `crash` (in place), `crash-dive` (40 m above ground, nose 35° down, 50 m/s forward and 90 m/s down, as Roblox's `commands.crash("dive")`).

---

## 10. Open questions (for the user) and risks

### 10.1 Questions

1. **Q1 Glow of the assisted entry.** In Roblox the plasma starts 1.2 s after L, while the ship is still far above the air (that kept the 6 s space phase dramatic). The port does the same for the LAND assist (8 s of plasma on Earth) and makes free-flight dives glow only inside the air. Alternative: air-gated glow for both (the assist would glow only 3.2 s). Keep the Roblox look?
2. **Q2 Ascent.** Roblox took the controls automatically once the pilot climbed above the exit altitude (8 km on Earth). The port makes this an opt-in **ORBIT** button and lets the pilot simply fly up. Keep it opt-in, make it automatic as in Roblox, or drop it?
3. **Q3 Cancel.** Roblox had no way to stop a guided entry. The port offers **CANCEL** on the ACTION button. Acceptable?
4. **Q4 Atmosphere heights** (`03-world.md` Q11). Proposed: the air ends at the profile thickness (Earth 26 km), the visible limb at 3 × that (78 km) with a scale height of thickness/3 (8.7 km). The Roblox shells would be 160 and 509 km at S = 3000. Confirm.
5. **Q5 Titan** (`04-planet-surface.md` Q12). Its ground profile has orange air and clouds; in space it had no shell. Proposed: every body with air in its profile gets the limb from space, Titan included.
6. **Q6 Haze** (`04-planet-surface.md` Q2). The Roblox look has a 3.5 km haze e-fold on the ground (Earth) and pale or deep-blue sky depending on the sky image. The port reproduces it. Clearer air would show more of the huge planet. Keep the Roblox haze?
7. **Q7 Impact warning gate.** The port warns only when the predicted impact (with the flight assist's brake) exceeds 28.5 m/s, so normal landings never trigger IMPACT. Acceptable?
8. **Q8 Debris carry clamp.** Crashes can now happen at km/s; the port caps the debris' inherited speed at 120 m/s so the cinematic stays on screen. Acceptable?
9. **Q9 GUI exports (optional).** `StarterGui.ShipDestroyed` and `StarterGui.EntryFx` are not in the export; section 5 proposes layouts. A Studio export or screenshot of both would make them 1:1.
10. **Q10 Sounds.** The entry wind (`action_falling.ogg`), Josh's "Machine Rumble" (`105319176350102`), the warning tones and the explosion are Roblox assets (D31: stand-ins). Can the original Machine Rumble file be provided?
11. **Q11 Sun disc.** Roblox draws a 2.2° sun disc by day and its own sun at 3.2 × the real size (at least 4°) at dusk. Keep both sizes, or use one?

### 10.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | The Roblox look comes from its lighting engine (Atmosphere fog into the sky image, ClockTime sun colours); the port's formulas match the parameters, not the pixels | the target table of 5.5, side-by-side screenshots, a tuning pass on device |
| R2 | Overdraw: puffs (up to 144 m sprites), the plasma halo and the smoke column can cost several full-screen layers on mid-range GPUs | caps per quality (8.2), near-camera culling, soft fades, LOW rates |
| R3 | The cloud image regenerates every 9 km; at assist speeds through the deck that is about once per 1.5 s and a worker takes 60–250 ms | the old image stays until the new one is ready (as Roblox); measure in `entry-perf` |
| R4 | Float precision of the deck geometry on a 6,360 km sphere | per-vertex maths in double on the CPU, camera-relative floats to the GPU |
| R5 | The assist bypasses the speed law (as Roblox): a release state that fights the near-ground law could jerk | release speed 320 m/s equals the law's limit at 900 m (315 m/s); throttle set on release; test in `entry-assist` |
| R6 | The derived ENTRY/EXIT state can flicker for consumers (autopilot, warp, callouts) | ±20 m/s hysteresis, 5 % `flowTop` hysteresis |
| R7 | Debris from real hull parts needs part boundaries in the imported meshes | cut the hull into chunks at import (spatial clustering); generic charred shards until then |
| R8 | Path prediction cost and accuracy on steep terrain | 12 samples, shared with the callouts; tolerate false negatives below 0.5 s (phase 2 already active) |
| R9 | `math.noise` and Roblox `Random` are not bit-exact in the port | statistical tests (cover share, image vs field) instead of golden images |
