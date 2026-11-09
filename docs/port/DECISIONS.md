# Port decisions

The user asked for independent work, without questions. Where the specs list an open question,
this file is the answer. It overrides the "proposed" and "pending the user" notes in the specs.
If the user later sends one of the optional exports listed at the end, the matching decision is
revisited.

## World scale and travel

1. **S = 3000** for every length in the Roblox Sol data (`WorldScale.S`, applied in one place, the
   world builder; `SolData` stays in Roblox studs verbatim). Earth is 6,360 km, Luna orbits at
   384,000 km, and every sky angle stays as in Roblox (03-world.md, Appendix B.6 for what scales
   and what stays absolute).
2. **Speed law: 02-flight.md's S-classified law.** Split `Thrust.MaxSpeed` into an absolute
   1000 m/s (throttle map, limit tracking, brake threshold, HUD, audio) and a scaled shadow
   ceiling (1000·S). Far-field distances, speeds and accelerations scale ×S (shadow ceiling,
   MassShadowFloor, StarShadowMinSpeed, the ApproachBrake inside the far law); ship-local and
   near-ground values stay absolute; times and ratios are unchanged. **Cruise cap 0.5 c
   (150 Mm/s).** At S = 1 the code must reproduce Roblox exactly (parity tests against the
   Roblox formulas); at S = 3000 planet-to-planet trips take about 1 minute (52-72 s to the
   target's mass-shadow edge). The flight velocity is relative to the dominant body's frame.
3. **Near-ground band** (absolute, not scaled): below 11.4 km above the terrain the limit is
   `clamp(0.35·h, 260, 4000)` m/s with the unscaled approach brake (70 m/s²), as 02-flight.md
   proposes. The far law applies above the band; the effective limit is the minimum of all laws.
4. **Units:** m, km, Mm and Mm/s as in Roblox (ShipUnits), with thousands grouping
   ("20,509 Mm"). With the 0.5 c cap no larger speed unit is needed.
5. **Gravity:** 9.81 m/s² per g (real scale), with the landing scene's damping (35 %) everywhere,
   and Flight Assist cancelling 78 % of it as in Roblox.
6. **Body reference frames:** the ship is carried by its dominant body's motion (translation
   only, velocity relative), nested moons inside planets, stations taking priority. Orbits move
   with the Roblox periods once frames work; a static epoch is acceptable as the first step.
7. **Planets never spin** (as in Roblox).
8. **Clock:** a save-game clock that runs while playing (not real UTC).
9. **Stations** keep their scaled distances (MERIDIAN at 19,800 km from Earth's centre, Earth
   37.5° wide). Docking ranges, zones, hangar, pads and every speed inside a station's zone stay
   at real (Roblox) size.
10. **Generated systems:** Roblox `Random` is ported as a best-effort PCG32 re-implementation
    behind an interface. Without the Studio reference vectors, generated systems are "same rules,
    possibly different dice". Sol is exact.
11. **Gas giants:** barrier at the cloud tops with "LANDING BLOCKED · NO SOLID SURFACE", as in
    Roblox.
12. **Lighting:** keep the per-body true star direction and the gentle distance falloff of the
    Kotlin renderer; drop the TERRAN city lights.
13. **Start:** new pilots start docked at MERIDIAN with 1,500 CR. The Kotlin-only asteroid field
    at the old start area is removed.

## Planets and landing

14. **Seamless entry, no landing scene.** Free flight from orbit to the ground under the speed
    law. The Roblox guided landing (L: HEAT → DESCENT → LEVEL, hand-over at 900 m / 320 m/s)
    becomes an optional **landing assist** on the ACTION button, an autopilot that flies the same
    profile.
15. **Attitude indicator** (pitch ladder, ALT, climb rate) shows inside a body's atmosphere, below
    50 km above the terrain on airless bodies, and whenever the landing assist or gear is active.
16. **Impact warnings** against terrain are on (time-to-impact based, as ShipWarnings).
17. Terrain: the Roblox styles (relief + paint, metres, 11 styles chosen as in Roblox) give the
    ground detail and keep their metre size. **A planet-scale layer is added underneath**
    (continents, seas, mountain ranges of a few km on rocky bodies) so a huge planet shows relief
    and coastlines from orbit. Terrain is planet-fixed (the same place always looks the same).
    Water is a solid floor you can set down on (Roblox behaviour).
17a. **Planet looks are generated on the device** from the same terrain functions (the Roblox
    planet images were made offline by its VisualGen generator and are not in the export), so the
    orbit view and the ground always match. Clouds are a separate procedural layer.
17b. **Absolute atmosphere heights** (05 Q4): air ends at the profile thickness (Earth 26 km),
    visible limb at 3× (78 km), scale height = thickness/3; cloud decks keep their Roblox metres
    (Earth 2,100-2,900 m). Titan gets a visible orange limb from space.
17c. **Clearer air than Roblox:** ground haze e-fold about 10× Roblox's (Earth about 35 km) so
    the huge planet is visible to the horizon, in the Roblox haze colours.
17d. **Entry glow** only inside the air at high airspeed, for assisted and manual entry alike.
    Ascent is free flight with an optional ORBIT assist; the landing assist can be cancelled.
    Impact warnings count only above 28.5 m/s predicted impact (normal landings never warn);
    crash debris inherit at most 120 m/s. Sun disc sizes as in Roblox.
17e. Phobos and Deimos stay at Roblox proportions (450 km radius at S = 3000).

## Ships, HUD and controls

18. All 8 ships are available (none is "not available yet").
19. **Touch:** the left lever is an absolute throttle (−25 % to 100 %, zero line at 20 %), its
    sideways motion is analog yaw; double-tap and hold = full stop; the right stick feeds the
    Roblox cursor and springs back to centre; ◀ ▶ strafe buttons are added. Gamepad as in
    02-flight.md.
20. Remove the nose reticle, the velocity marker and the GEAR HUD bar; use the Roblox flight
    arrow.
21. **Maps:** the flight keeps running while a map is open (Roblox behaviour). Stations are not
    lockable from the system map (Roblox behaviour). Only PAUSE and SETTINGS freeze the
    simulation.
21a. **Views:** cockpit (default) and external as in Roblox; the current lagging chase camera
    stays as an optional third view behind a setting. Look-around: one-finger drag in a strip at
    the top of the screen. 60 fps default, 120 Hz selectable.
21b. **Cockpit on phones:** a minimal flat readout (speed, limit/zone, throttle, target
    distance/ETA) also in the cockpit view, behind the setting "FLAT HUD IN COCKPIT" (on). The 3D
    cockpit buttons are visual only (they press when the matching touch button is used). LIGHTS
    and MUSIC go into the pause menu. Tap a marker to lock it, tap LOCK to cycle, long-press LOCK
    to clear. The off-screen lock arrow stays as a phone aid behind a setting (on).
21c. **UI scale:** 1 Roblox design pixel = 0.6 u, text never below 9 u, for every flat UI.
21d. **Game.kt becomes a platform shell plus a Session** that runs the Roblox frame order
    (01-core-loop.md). Flight input is blocked during the crash fade-in; a failed system build
    during a jump falls back to Sol in front of MERIDIAN. After the app is killed the ship is
    restored at rest. Hardware keyboard/mouse support comes later. Automatic quality drops stay
    silent.

## Stations, economy and saves

22. Station screens are rebuilt phone-first in the Roblox colours, fonts and texts (the original
    GUI layouts are not in the export).
23. Saves: local JSON (`profile.json`, `galaxy.json`) through Platform, atomic with a backup copy.
    After the app is killed the game restores the last state: docked at that station, or the exact
    flight position.
24. Device clock for prices and build timers is accepted.
25. First-discovery bonus (×1.5) is kept in singleplayer.
26. Survey thresholds that were tuned to Roblox planet sizes scale with S (range in planet radii
    stays a ratio; "giant" from 5 km radius becomes 15,000 km); absolute near-ship distances stay.
27. Tiles for features not ported yet (DISEMBARK, QUARTERS, OPEN PLAY) show "NOT AVAILABLE YET".
28. Flight training: optional (offered to new pilots, skippable) until it is fully ported.

## Names and wording (original content rule)

29. The player is **PILOT**; an optional callsign can be set in OPTIONS ("PILOT <NAME>").
30. Renames: "MASS LOCK" → **"MASS SHADOW"** (the Roblox game's own concept); "DOCKING COMPUTER"
    → **"AUTO-DOCK"**; "OUTER RIM SURVEY TRUST" → **"FAR REACH SURVEY TRUST"**; "FIRST DISCOVERED
    BY" → **"FIRST SURVEYED BY"**. The unused classes Metal-Rich Body, High Metal Content,
    Ammonia World and Water World are dropped.

## Assets that cannot ship

31. **Audio:** Roblox-library sounds cannot ship. The port uses synthesised or CC0 stand-ins with
    the same roles, gains and triggers. Voice lines are text toasts until recordings exist.
32. **Fonts:** bundle free OFL fonts as needed (Michroma, Sarpanch, Titillium Web, Roboto Mono,
    Oswald, Montserrat for GothamSSm, Source Sans Pro, Nunito).
33. **Icons:** vector shapes drawn in code, not the uploaded PNGs.
34. **Avatars:** an original low-poly crew figure instead of Roblox R15 avatars (later phase).
35. Fix obvious Roblox bugs in the port (silent crash debris, unreachable "request received"
    line, the stations-only collision alert gets its intended NPC check).

## Sol milestone: what the build does today

The user asked for a realistic planet game: real sizes, real physics, seamless entry into the
Earth, graphics not spared on a strong phone. That request decides the points below; where one
differs from a decision above, this section wins until the matching port replaces it.

36. **The world is Sol** (`SolData.kt`, decision 1): the Lua's numbers at S = 3000, 18 bodies and
    the 6 stations, at a **fixed epoch** (decision 6's first step). MERIDIAN hangs at 55° from the
    Earth → Sun direction instead of the Lua's 90°, so the Earth below it is mostly lit. The
    stations are still the two stand-in models. The asteroid field is gone from Sol (decision 13).
37. **The test range stays** as `StarSystem()` (the gas giant, four smooth planets, the asteroid
    field): the unit tests and the harness scenes written for the flight mechanics keep running
    on it. `StarSystem.sol()` is the game.
38. **Gravity is the real one**: 9.81 m/s² per g at the surface, inverse square above it, without
    the landing scene's 35 % damping (this replaces decision 5 for now). Flight Assist cancels all
    of it (the thrusters hold the ship); with Flight Assist off the ship falls and can orbit.
39. **Air**: real ground densities and scale heights (Earth 1.225 kg/m³ and 8.5 km, Mars 0.020,
    Venus 65, Titan 5.4), quadratic drag with Cd·A/m = 0.002 m²/kg, no lift. The speed laws are
    the pre-port ones (limit ∝ clearance, floor at the hull's top speed).
40. **Entry heating** (decision 17d): heat flux ∝ √ρ·v³. The glow follows it over two decades;
    the skin's temperature follows with a 5 s time constant and costs hull beyond its limit. A
    full-throttle dive under Flight Assist stays just inside the limit. The star heats a hull
    within half a radius of its surface.
41. **Hull and loss**: ground impacts above 12 m/s cost hull, only on ground that is already
    drawn at full detail (04 9.10). At 0 the ship is lost: 3.2 s of black, then a new hull in
    front of the nearest station (`SpawnRules.recoveryPose`). The crash cinematic of `05` is not
    ported. Docking restores the hull.
42. **Landing** is still the pre-port landing mode (LAND, gear down, touch down slowly), now on
    the real ground: the gear follows slopes up to 22°; steeper ground refuses the touchdown.
43. **Clouds** (decision 17a): one weather map for every world with air (cover and colour from
    its surface profile, height = the middle of the profile's deck) plus tiling 3-D noise, shaded
    as one thin deck by the impostor and the terrain alike, with shadows on the ground and fog
    inside the deck. They drift at 12 m/s on the Earth's equator.
44. **Far looks**: every solid body's image is baked on the device from its terrain functions
    (decision 17a), 512 × 256 for all of them in the background after the start and 2048 × 1024
    (HIGH) for the terrain body.

## Optional exports that would improve fidelity

Not blocking; the port proceeds without them: Studio GUI exports or screenshots of SystemMap,
GalaxyMap, StationServices, Construction, FlightNotices, AttitudeHud, StationLife and Training;
the galaxy reference vectors (03-world.md Appendix C); the `Tools/VisualGen` planet images;
the user's own audio files.
