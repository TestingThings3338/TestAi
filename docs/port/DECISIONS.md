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
2. **One speed law, scale-free:** `limit = MaxSpeed + gap / T(cos)` all the way down, with
   T = 2.5 s approaching and 1.0 s departing (blended by the radial fraction as today), and a
   **cap of 0.5 c (150 Mm/s)**. The Roblox inner mass-shadow law, `MassShadowFloor` and the
   2.2·Rc approach zone are replaced by it. Target: planet-to-planet trips of about 1 minute
   (03-world.md B.3: 52-89 s).
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
17. Terrain heights keep their metre size (they do not scale with S); continents, seas and biome
    layout scale with the planet. Details in 04-planet-surface.md.

## Ships, HUD and controls

18. All 8 ships are available (none is "not available yet").
19. **Touch:** the left lever is an absolute throttle (−25 % to 100 %, zero line at 20 %), its
    sideways motion is analog yaw; double-tap and hold = full stop; the right stick feeds the
    Roblox cursor and springs back to centre; ◀ ▶ strafe buttons are added. Gamepad as in
    02-flight.md.
20. Remove the nose reticle, the velocity marker and the GEAR HUD bar; use the Roblox flight
    arrow.
21. **Maps:** the flight keeps running while a map is open (Roblox behaviour). Stations are not
    lockable from the system map (Roblox behaviour).

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

## Optional exports that would improve fidelity

Not blocking; the port proceeds without them: Studio GUI exports or screenshots of SystemMap,
GalaxyMap, StationServices, Construction, FlightNotices, AttitudeHud, StationLife and Training;
the galaxy reference vectors (03-world.md Appendix C); the `Tools/VisualGen` planet images;
the user's own audio files.
