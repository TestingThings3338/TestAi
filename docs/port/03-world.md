# 03 · World: Sol, star systems, orbits, body visuals, rings, galaxy store

Port reference for the "universe" of **Untitled Space Exploration** (Roblox, by AFloidDEV) → Android/Kotlin (`:core`). It covers:

- the Sol system data;
- building a star system at runtime, including all other systems from the star catalog;
- system and body names;
- orbits;
- station placement (where stations hang; docking itself is in `08-stations-economy.md`);
- body palettes and visual assets (textures, ring images, ring profiles);
- far-body impostors, star light and night side;
- planet rings and ring debris;
- runtime planet textures;
- the galaxy store (first visits, discoveries, deposits, player stations).

**Sources.** All Lua paths are relative to `roblox/lua/`. `path:line` points at the line where a function or table starts. Modules read in full:

- `ShipCore/`: `ShipSolData`, `ShipSystemBuilder`, `ShipStarCatalog`, `ShipNames`, `ShipStations`, `ShipPalettes`, `ShipVisualAssets`, `ShipSphereMesh`.
- `ShipClient/`: `ShipOrbits`, `ShipImpostors`, `ShipStarLight`, `ShipRingDebris`, `ShipPlanetTexture`, `ShipGalaxy`.
- `ShipServer/ShipGalaxy`.
- The `ShipConfig` sections `Impostors`, `Rings`, `StarLight`, `PlanetTexture`, `Stations` and `Scale`.

Also used:

- `ShipClient.lua` (call sites);
- `ShipClient/ShipGravity.lua`, `ShipThrust.lua` and `ShipStation.lua` (placement, carry, speed laws);
- `ShipCore/ShipResources.lua` (player-station slots).

Numbers marked "computed" were derived with scripts from the Lua values. Nothing is estimated unless the text says so.

**Units.** 1 stud = 1 m in the Roblox game (`ShipConfig.Scale.MetresPerStud = 1`, `ShipCore/ShipConfig.lua:2754`). **S** is the uniform world scale factor proposed for the port: a Roblox length of *x* studs becomes *x·S* metres. Section 1.3 summarises it and Appendix B works it out.

**Naming.** The player is **PILOT**. The Roblox comments say "Kommandant" (commander); the port never shows "COMMANDER" or "CMDR".

---

## 0. Key facts in one screen

- **Sol is hand-made** (`ShipSolData.lua`). It has 1 star, 8 planets and 9 moons:
  - Luna;
  - Phobos and Deimos;
  - Io, Europa, Ganymede and Callisto;
  - Titan;
  - Triton.
- **The sizes are deliberately not to scale** (comment `ShipSolData.lua:6`):
  - Rocky bodies are ×10 of "1 stud = 30 km", with a minimum radius of 150.
  - Giants are ×4.
  - The Sun has radius 4,800 and is not to scale at all.
  - Orbits are `828,660 · a_AU^0.62` studs.
  - Moon orbits keep the real ratio to their planet's radius.
- **With S = 3000, every rocky body is real-size:**
  - Earth 6,360 km (real 6,371); Luna 1,740 km at 384,000 km (real 1,737 km at 384,400 km); Mars 3,390 km.
  - The giants come out at 0.4× real, the Sun at 1/48 and the planet orbits at 1/60 of real.
- **Every other system (179 of them) is generated from its seed** (`ShipSystemBuilder.makeProcedural`). The seed comes from a catalog of 180 systems that is itself generated from one number (`ShipConfig.StarMap.Seed = 20260824`).
  - Names, planets, moons, rings and stations are deterministic.
  - Only the current system exists at runtime.
- **Orbits are circles in the XZ plane, tilted about X** (`ShipOrbits.lua:106`):
  - `angle = (phase + clock/period)·2π`
  - `period = 2π·orbit/speed`
  - The clock is server time since 2026-01-01 UTC, so every player sees the planets in the same place.
  - Planets **do not spin**.
- **Stations hang at a fixed offset from their host** (planet or moon):
  - The offset is `Distance` studs, rotated `Angle`° from the host→star direction in the orbit plane and raised `Elevation`°. It is computed once from the host's **rest position** (`ShipStations.offset`, `:321`).
  - Stations translate with the host and never rotate.
  - A ship near a station is **carried** with it (`ShipStation:getCarry`).
- **Body look:**
  - uploaded 4096² images per Sol body, plus 14 class images and 29 named variants (`ShipVisualAssets`);
  - ForceField atmosphere shells at 1.025 R and 1.08 R;
  - a black night-side "ramp" shell at 1.016 R;
  - rings as images with a 128-step opacity profile;
  - **all images are Roblox asset ids**, and the port needs the PNG files (open question Q5).
- **Ring debris:** 280 tumbling rocks in a 1400 m box around the ship, only inside the ring band and near its plane.
  - Rock density follows the ring's opacity profile.
  - Each new contact adds 0.012–0.25 heat (squared with speed) and plays a hit sound.
- **Galaxy store:** one record per system (version 1).
  - It holds `FirstVisit`, `Bodies[name] = {Deposits, Discovered}` and `Stations[id]` (player stations).
  - Deposits are rolled and "frozen" on the first visit. Sol is pre-discovered by "SOL REGISTRY".
  - In the port it becomes a local JSON file.
- **Roblox-only machinery the port drops:**
  - far-body impostors and the sight probe;
  - the star-light lookup table;
  - black night-shade spheres;
  - the 2048-stud part limits;
  - the separate landing scene (scale 470);
  - 32-bit origin shifting;
  - server round trips.
- **The biggest cross-area consequence of S = 3000** (Appendix B):
  - The cruise law `v = MaxSpeed + gap/T` is only *logarithmically* scale-dependent.
  - The **mass-shadow inner law and the approach-zone brake are absolute**. Scaled naively, planet-to-planet trips take 8–23 minutes instead of 1–2.
  - They must be redesigned (flight area). A proposed single law gives 52–95 s planet-to-planet at S = 3000, against 47–113 s in Roblox.

---

## 1. Overview

### 1.1 What the player experiences

- **One star system at a time.** Home is Sol.
  - The Sun is a glowing disc with an orange corona.
  - Planets visibly sit at different distances. From Earth, Venus at its closest is a 1.5° disc (three times the Moon) and Mars is 0.5°. Appendix B.2 lists every angular size.
  - Rings: Jupiter has faint rings, Saturn bright banded rings, and Uranus a ring standing almost vertical ("the planet lies on its side").
- **Bodies move.**
  - Planets circle the Sun in 6.7 days (Mercury) to 382 days (Neptune).
  - Moons go round in 0.24 days (Phobos) to 11 days (Callisto).
  - Because orbit time follows server time, a returning player finds the planets moved on.
- **Six stations in Sol.**
  - Each hangs beside its host (Earth, Luna, Mars, Jupiter, Titan, Mercury) with its mouth facing the host.
  - The host fills 20–55° of the sky as seen from the station.
  - MERIDIAN STATION above Earth is the home and start station.
- **Every body except a star can be approached until the "barrier".**
  - Solid bodies can be landed on. In Roblox that is key L, in a separate scene; in the port it is seamless.
  - Gas and ice giants show **LANDING BLOCKED  NO SOLID SURFACE**.
- **The galaxy map lists 180 systems.**
  - Sol is at the origin. The rest form a disc 60 ly in radius and 16 ly thick, with M dwarfs most common.
  - Jumping to one builds it from its seed: a star coloured by class, 0–9 planets of six classes, moons, sometimes rings, and 0–2 stations with names such as "KOVAC HUB".
  - Every player sees the same system.
- **The first time anyone enters a system**, the pilot gets the toast:
  - **FIRST VISIT**
  - `<SYSTEM NAME>  ·  NO PILOT HAS BEEN HERE BEFORE. YOUR NAME IS ON THE CHART.` (7 s)
- **The first surveyor of a body is recorded.** In Sol everything is pre-charted by "SOL REGISTRY".
- **Flying into a ring plane:**
  - Rocks appear and tumble.
  - The Cassini division of Saturn's rings is almost empty.
  - Hitting rocks heats the ship (deadly only after about 30 s of ramming at full speed) and makes a hit sound.
- **Far bodies never pop out.** Roblox achieves this with "impostor" copies in front of its render-distance limit. In the port it is native.

### 1.2 Port direction for this area

- **Port the data and the rules 1:1:**
  - Sol;
  - the catalog;
  - the procedural generator, with the same draw order and the same seeds;
  - names;
  - station lists and offsets;
  - palettes;
  - the ring profiles;
  - the debris rules;
  - the galaxy store rules.
- **Scale all world lengths by S = 3000**, and keep the near-surface and ship-local rules absolute (Appendix B.6).
- **Seamless world.** The Roblox landing scene (`ShipSurface`, `SceneScale = 470`) disappears, and with it:
  - `ShipImpostors:updateSurface`;
  - `getSurfaceSight`;
  - `SkyRadius`;
  - `TerrainLocked`.
- **Singleplayer:**
  - The galaxy store becomes a local file.
  - The shared clock becomes `UTC now − 2026-01-01`.
  - "First visitor / discoverer" is always the local pilot, except in Sol (SOL REGISTRY).
- **Rendering is already real-scale and double-precision** in Kotlin (`BodyRenderer`, ray-cast sphere impostors). The work is mostly:
  - data;
  - moving bodies;
  - textures;
  - rings;
  - debris;
  - the catalog;
  - the store.

### 1.3 The scale factor in one paragraph

**S = 3000 is recommended**, with conditions; Appendix B has the worked numbers.

What it gives:

- Rocky planets and moons come out **real-size**, and Luna orbits at the real distance.
- Every angle (sky sizes, station views) is **identical to Roblox**, because uniform scaling keeps angles.
- Double precision is ample: 4 µm resolution at the 27 Gm field edge.

What must change with it:

- **Orbital periods** must stay as in Roblox. Orbital speeds therefore become ×S (Earth 11.2 km/s), and a ship near a body must be carried with it (body reference frame).
- **Ship-local and near-surface quantities must not scale**, such as ship speeds, landing speeds, station docking ranges, terrain heights, atmosphere heights and debris fields.
- **The speed-limit chain must be rebuilt** as one scale-free law. Otherwise planet-to-planet trips take 8–23 minutes (Appendix B.3).
- **The cruise cap** (3 Mm/s in Roblox) must be raised to about 0.5 c. Otherwise outer-system trips take up to 28 minutes.

The only alternative worth considering is **S = 470**, the Roblox landing-scene scale: surface tuning would carry over 1:1, but Earth would be 996 km (question Q1).

---

## 2. Modules

### 2.0 Conventions shared by all modules

- **Axes.** Roblox is right-handed and Y-up. A `CFrame`'s `LookVector` is −Z. `CFrame.Angles(rx, ry, rz)` = `Rx·Ry·Rz`, so a vector is rotated about Z first, then Y, then X. The Kotlin engine uses the same axes (−Z forward), so every vector formula below ports unchanged.
- **Universe coordinates.** The star sits at the origin. Planets orbit in the XZ plane, and `angle` runs from +X towards +Z. `ShipOrigin` keeps a 64-bit offset between Roblox's 32-bit "world" and this "universe" (`ShipClient/ShipOrigin.lua:1`). In the port, positions simply are universe doubles.
- **The attribute contract.** Every celestial body is a `BasePart` named `Body` with the CollectionService tag `CelestialBody` (`ShipConfig.Gravity.Tag`, `ShipConfig.lua:449`), plus attributes (`tagBody`, section 2.2.6). *Everything else in the game reads only these attributes*, so hand-made and generated systems look the same to the rest of the code. The port keeps this as a plain `Body` record (section 9).
- **Hierarchy = orbit tree.** `System` (Model) contains one Model per star and per planet. A moon Model sits under its planet Model. `ShipOrbits` walks this tree (section 2.9).
- **Roblox `Random`.** It is used everywhere with integer seeds:
  - `NextNumber()` is uniform in [0, 1).
  - `NextNumber(a, b)` is `a + (b−a)·NextNumber()`.
  - `NextInteger(a, b)` is inclusive.
  - The algorithm is engine-internal (believed to be PCG32 but not documented). **Exact sequences can only be reproduced with golden values exported from Studio** (Appendix C). Luau evaluates table-constructor fields and call arguments left to right; the draw orders below rely on that.

### 2.1 ShipSolData: the Sol system (`ShipCore/ShipSolData.lua`)

**Purpose.** Hand-made definition of Sol. It is pure data, in the same schema that `makeProcedural` produces (section 2.2.9).

**Fields.**

- `StudsPerKm = 1/30` (`:31`, informational only).
- `Name = "Sol"` (`:32`).
- `Star` (`:43`).
- `Planets` (`:72`).

**The scale rules, as the comment states them** (`:6`):

- **Sizes** (2026-11-02, AFloidDEV's request):
  - Rocky planets and moons are ×10 of "1 stud = 30 km", with a minimum radius of 150.
  - Gas and ice giants are ×4. If they grew as much as the rocky bodies, they would swallow their moons and rings.
  - The Sun is ×3 (it was 1,600 before).
- **Orbits** (2026-11-03): `orbit = 828,660 · a_AU^0.62`. This is ten times what it was before. The root compression keeps the order of the planets. Orbit *speeds* were kept, so each period became 10× longer.
- **Moon orbits** keep the real ratio to their planet's radius. Luna is at 60 Earth radii and appears 0.5° wide from Earth, as in reality (it was 20° before).

**Star** (`:43`):

| Field | Value | Meaning |
|---|---|---|
| `name` | "Sol" | |
| `radius` | 4800 | Size in space. 0.66° from Earth (real 0.53°). The true-scale value would be 23,200. |
| `skyRadius` | 4000 | Size in a planet's sky (landing scene). 0.55° from Earth. |
| `heatRadius` | 37000 | Overheating inward from here (absolute, not tied to the radius). |
| `massShadowRadius` | 92000 | Now only the jump-arrival distance. Braking starts at `heatRadius` (`ShipGravity`). |
| `coronaScale` | 1.35 | Corona sphere = 1.35 R. At 3.4 it looked like a second sun. |
| `colour` | RGB 255,246,224 | Neon disc |
| `corona` | RGB 255,190,110 | Corona colour |
| `class` | "G" | → `BodyClass` "Star G" |

**Every star, planet and moon in Sol.** The first table holds the physical and orbit values; the second holds the look and the flags.

- "at S" columns are for S = 3000 (orbit periods kept, so speeds ×S).
- "Real" values are for comparison only.
- Phase, orbit tilt and spin yaw are **not** in the data. They come from `Random` in `build()` (section 2.2.8, seeds listed below the tables).

| # | Body | Parent | Class | Radius (studs) | Radius at S (km) | Real (km) | Orbit radius (studs) | Orbit at S | Real orbit | Orbit speed (studs/s) | Period (s) | Period (days) | Speed at S (km/s) |
|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | Sol | - | Star G | 4,800 (sky 4,000) | 14,400 (sky 12,000) | 696,000 | 0 | 0 | - | - | - | - | - |
| 1 | Mercury | Sol | Rocky Body | 810 | 2,430 | 2,440 | 460,000 | 1.380 Gm | 57.9 Gm | 5.00 | 578,053 | 6.69 | 15.00 |
| 2 | Venus | Sol | Greenhouse World | 2,020 | 6,060 | 6,052 | 677,710 | 2.033 Gm | 108.2 Gm | 4.12 | 1,033,538 | 11.96 | 12.36 |
| 3 | Earth | Sol | Terrestrial World | 2,120 | 6,360 | 6,371 | 828,660 | 2.486 Gm | 149.6 Gm | 3.72 | 1,399,630 | 16.20 | 11.16 |
| 4 | Luna | Earth | Moon | 580 | 1,740 | 1,737 | 128,000 (60.4 R) | 384.0 Mm | 384,400 km | 1.60 | 502,655 | 5.82 | 4.80 |
| 5 | Mars | Sol | Rocky Body | 1,130 | 3,390 | 3,390 | 1,076,040 | 3.228 Gm | 227.9 Gm | 3.27 | 2,067,571 | 23.93 | 9.81 |
| 6 | Phobos | Mars | Moon | 150 | 450 | 11.3 | 3,600 (3.2 R) | 10.8 Mm | 9,376 km | 1.10 | 20,563 | 0.24 | 3.30 |
| 7 | Deimos | Mars | Moon | 150 | 450 | 6.2 | 7,800 (6.9 R) | 23.4 Mm | 23,463 km | 0.90 | 54,454 | 0.63 | 2.70 |
| 8 | Jupiter | Sol | Gas Giant | 9,320 | 27,960 | 69,911 | 2,303,850 | 6.912 Gm | 778.5 Gm | 2.23 | 6,491,263 | 75.13 | 6.69 |
| 9 | Io | Jupiter | Moon | 610 | 1,830 | 1,822 | 55,000 (5.9 R) | 165.0 Mm | 421,700 km | 2.40 | 143,990 | 1.67 | 7.20 |
| 10 | Europa | Jupiter | Moon | 520 | 1,560 | 1,561 | 87,600 (9.4 R) | 262.8 Mm | 671,034 km | 2.10 | 262,099 | 3.03 | 6.30 |
| 11 | Ganymede | Jupiter | Moon | 880 | 2,640 | 2,634 | 140,000 (15.0 R) | 420.0 Mm | 1,070,412 km | 1.90 | 462,972 | 5.36 | 5.70 |
| 12 | Callisto | Jupiter | Moon | 800 | 2,400 | 2,410 | 245,000 (26.3 R) | 735.0 Mm | 1,882,709 km | 1.60 | 962,113 | 11.14 | 4.80 |
| 13 | Saturn | Sol | Gas Giant | 7,764 | 23,292 | 58,232 | 3,354,380 | 10.063 Gm | 1,432.0 Gm | 1.85 | 11,392,536 | 131.86 | 5.55 |
| 14 | Titan | Saturn | Moon | 860 | 2,580 | 2,575 | 157,600 (20.3 R) | 472.8 Mm | 1,221,870 km | 1.70 | 582,488 | 6.74 | 5.10 |
| 15 | Uranus | Sol | Ice Giant | 3,380 | 10,140 | 25,362 | 5,174,850 | 15.525 Gm | 2,867.0 Gm | 1.49 | 21,821,840 | 252.57 | 4.47 |
| 16 | Neptune | Sol | Ice Giant | 3,284 | 9,852 | 24,622 | 6,836,280 | 20.509 Gm | 4,515.0 Gm | 1.30 | 33,041,242 | 382.42 | 3.90 |
| 17 | Triton | Neptune | Moon | 450 | 1,350 | 1,353 | 47,300 (14.4 R) | 141.9 Mm | 354,759 km | 1.40 | 212,282 | 2.46 | 4.20 |

How to read the look-and-flags table:

- "Gravity (g)" is the value of `gravityG` in `ShipSolData`.
- `SurfaceGravity` is the attribute value `g·196.2` (only used for the free-fall-time display, `ShipGravity.lua:445`).
- The collision radius (the "barrier") is `ceil(1.105·R)+6`, and stars use their radius.
- The mass shadow is an *altitude above the barrier*: Rc·5 for planets (`Gravity.MassShadowFactor`) and Rc·4 for moons. It is given with the distance from the centre in brackets.
- Influence = 2.2·Rc is the approach zone (`Gravity.DefaultInfluenceFactor`).
- `MinApproachSpeed`: 40 for stars, 14 for planets, 12 for moons.
- Moons have no `material` field and get `Concrete`. Moons never have atmospheres in Sol, not even Titan.

| Body | Gravity (g) | SurfaceGravity attr | Colour RGB | Material | Atmosphere colour RGB | Ring | Landable | LandingBlocked | Collision radius Rc | Mass shadow alt. (from centre) | Influence | MinApproach |
|---|---:|---:|---|---|---|---|---|---|---:|---:|---:|---:|
| Sol | 0.5 (default) | 98.1 | 255,246,224 · corona 255,190,110 | Neon · corona ForceField t=0.62 | - | - | no | - | 4,800 | 92,000 abs. (`MassShadowRadius`); brakes from `HeatRadius` 37,000 | 10,560 | 40 |
| Mercury | 0.3770 | 74.0 | 140,130,122 | Slate | none | - | yes | - | 902 | 4,510 (5,412) | 1,984 | 14 |
| Venus | 0.9040 | 177.4 | 216,190,140 | Sandstone | 240,220,170 | - | yes | - | 2,239 | 11,195 (13,434) | 4,926 | 14 |
| Earth | 1.0000 | 196.2 | 52,96,152 | Marble | 120,180,255 | - | yes | - | 2,349 | 11,745 (14,094) | 5,168 | 14 |
| Luna | 0.1650 | 32.37 | 158,154,148 | Concrete | none | - | yes | - | 647 | 2,588 (3,235) | 1,423 | 12 |
| Mars | 0.3780 | 74.2 | 168,96,62 | Basalt | 215,150,110 | - | yes | - | 1,255 | 6,275 (7,530) | 2,761 | 14 |
| Phobos | 0.0006 | 0.12 | 120,110,100 | Concrete | none | - | yes | - | 172 | 688 (860) | 378 | 12 |
| Deimos | 0.0003 | 0.06 | 126,116,106 | Concrete | none | - | yes | - | 172 | 688 (860) | 378 | 12 |
| Jupiter | 2.5280 | 496.0 | 206,172,132 | Sand | 230,200,160 | 1.45–1.72 R (13,514–16,030), tilt 0, RGB 150,130,108, transp. 0.72, 72 segs | no | NO SOLID SURFACE | 10,305 | 51,525 (61,830) | 22,671 | 14 |
| Io | 0.1830 | 35.90 | 216,196,116 | Concrete | none | - | yes | - | 681 | 2,724 (3,405) | 1,498 | 12 |
| Europa | 0.1340 | 26.29 | 212,206,190 | Concrete | none | - | yes | - | 581 | 2,324 (2,905) | 1,278 | 12 |
| Ganymede | 0.1460 | 28.65 | 150,140,130 | Concrete | none | - | yes | - | 979 | 3,916 (4,895) | 2,154 | 12 |
| Callisto | 0.1260 | 24.72 | 110,104,98 | Concrete | none | - | yes | - | 890 | 3,560 (4,450) | 1,958 | 12 |
| Saturn | 1.0650 | 209.0 | 216,194,148 | Sand | 238,218,176 | 1.24–2.27 R (9,627–17,624), tilt 0, RGB 210,194,164, transp. 0.32, 108 segs | no | NO SOLID SURFACE | 8,586 | 42,930 (51,516) | 18,889 | 14 |
| Titan | 0.1380 | 27.08 | 196,150,96 | Concrete | none | - | yes | - | 957 | 3,828 (4,785) | 2,105 | 12 |
| Uranus | 0.8860 | 173.8 | 150,214,220 | Glacier | 170,230,235 | 1.64–2.00 R (5,543–6,760), **tilt 82°**, RGB 120,150,160, transp. 0.78, 64 segs | no | NO SOLID SURFACE | 3,741 | 18,705 (22,446) | 8,230 | 14 |
| Neptune | 1.1370 | 223.1 | 62,96,200 | Glacier | 110,150,255 | - | no | NO SOLID SURFACE | 3,635 | 18,175 (21,810) | 7,997 | 14 |
| Triton | 0.0790 | 15.50 | 200,196,186 | Concrete | none | - | yes | - | 504 | 2,016 (2,520) | 1,109 | 12 |

**Notes on these tables:**

- `landable` in `ShipSolData` is only "for economy and hand mining" (`ShipSystemBuilder.lua:330`). What decides landing is the attribute `Landable = not star and class not in {Gas Giant, Ice Giant}`. Venus was "LANDING BLOCKED - SURFACE PRESSURE" until 2026-11-04 (comment `:80`).
- Ring radii are multiples of the planet radius. Uranus's ring tilt also tilts the planet's axis (section 2.2.8).
- **Every Sol body has its own uploaded texture** (`ShipVisualAssets.Bodies`). So in Sol the body colour above is only used by maps, debris and the untextured fallback.
- **Seeds:**
  - Planet `Seed` is its index 1–8.
  - Moon `Seed` is `planetIndex·31 + moonIndex`: Luna 94, Phobos 125, Deimos 126, Io 156, Europa 157, Ganymede 158, Callisto 159, Titan 187, Triton 249. Terrain and arrival profiles read these.
  - Random streams in `build()`:
    - planet orbit `Random(index·7919+13)`: Mercury 7932 … Neptune 63365;
    - planet yaw `Random(index·6151+7)`;
    - moon orbit `Random(index·104729 + moonIndex)`: Luna 314188, Phobos 418917, …;
    - moon yaw `Random(moonSeed·6151+7)`.

**The definition schema** (consumed by `build()`):

- `Definition { Name, Seed?, Star, Planets[] }`
- `Star { name, radius, colour, corona, class?, skyRadius?, heatRadius?, massShadowRadius?, coronaScale? }`
- `Planet { name, radius, orbit, speed, colour, material, class, landable?, atmosphere?, atmosphereColour?, gravityG?, ring?, moons?, seed?, phase?, tilt?, classColour?, landingBlocked? }`
- `Ring { inner, outer (× R), segments, tilt? (deg), colour, transparency }`
- `Moon { name, radius, orbit, speed, colour, landable?, gravityG?, material?, class?, phase?, tilt? }`

**Port.** Copy the numbers verbatim, in studs, into `SolData.kt`. Apply S in exactly one place, the world builder. That keeps the file diff-able against the Lua.

### 2.2 ShipSystemBuilder: build a system at runtime (`ShipCore/ShipSystemBuilder.lua`)

**Purpose.** Turns a definition into parts in the world, and generates definitions from a seed. Only the current system exists. A warp clears the old one and builds the new one (comment `:5`).

**Public API.**

| Function / field | Line | Returns |
|---|---|---|
| `smoothSpheres` | `:35` | `true`. The client sets it to false if `smooth_sphere.mesh` does not load (`ShipClient.lua:203`). |
| `build(parent, definition)` | `:356` | Root `Model` "System" with attribute `SystemName` |
| `makeProcedural(seed, planetCount, starColour, starClass)` | `:532` | A definition (section 2.2.9) |
| `sol()` | `:730` | `ShipSolData` |

**Constants** (`:37`):

| Name | Value | Meaning |
|---|---|---|
| `MAX_PART` | 2048 | Roblox `Part.Size` limit (workaround) |
| `SCALE_ROCKY` | 10 | Procedural small bodies: base radius ×10 |
| `SCALE_GIANT` | 4 | Procedural giants ×4 |
| `SCALE_STAR` | 2 | Procedural stars ×2 |
| `MIN_RADIUS` | 150 | Smallest body radius |
| `ORBIT_SCALE` | 10 | Procedural orbits ×10 (2026-11-03) |
| `MOON_COLOUR` | RGB 150,146,140 | Base colour of procedural moons (for the tint) |
| `SHADE_OFFSET` | 0.08 | Night sphere offset, as a fraction of R |
| `SHADE_EDGE` | 3° | Night sphere cuts the surface 3° into the day side |
| `RAMP_SHELL` | 1.016 | Night ramp skin radius, as a fraction of R |

#### 2.2.1 `sphere(parent, name, radius, position, colour, material, look?)` (`:60`)

- An anchored Part, with no collision, query, touch or shadow. `Size = min(2048, 2R)`, plus a `SpecialMesh`.
- With `look.smooth` (and `smoothSpheres`):
  - `FileMesh` = `smooth_sphere.mesh`, scaled `R / 85.6281`.
  - The material becomes `SmoothPlastic` unless it is `ForceField`.
  - With `look.texture`: `TextureId = texture`, `VertexColor = look.tint or (1,1,1)`, material `Fabric` (no specular spot), attribute `TextureAsset = texture`.
  - The attribute `SphereRadius = R` is always set.
- Otherwise: `MeshType.Sphere`, scale `2R/size`.
- **Port:** all of this is replaced by the ray-cast sphere impostor in `BodyRenderer`. Keep only the decision "textured or plain colour".

#### 2.2.2 `tintFor(colour, base)` (`:111`)

This tints a class image towards the body colour. `VertexColor` can only darken:

```
tint.c = clamp(colour.c / max(base.c, 0.05), 0.75, 1)     for c in R, G, B
```

It returns (1,1,1) when `base` is nil.

#### 2.2.3 `buildRingImage(holder, centre, outer, tilt, texture, tint)` (`:124`)

- Two square quads (`CompositQuad.mesh`, 1024 mesh units, one corner at the origin, front side only), scaled `2·outer/1024`.
- One faces up and one faces down: `plane·Rx(±90°)·T(−half, −half, 0)`, where `plane = T(centre)·Rz(tilt)`.
- Material `Fabric`, colour white, `Transparency = AlphaPass` (0.02, which enables the image's alpha), `VertexColor = tint`.
- **The image covers the full disc up to `outer`.** The hole and the gaps are in the image (section 2.7).

#### 2.2.4 Night side (`nightShade`, `:205`)

**What it is for.** Roblox lights the far side of a sphere "as if from below". The fix is to cover the night side with black.

**Two modes:**

1. **"Ramp"** (used, because `NightRamp` is uploaded):
   - A black sphere `1.016·R` with the `NightRamp` image: alpha 0 on the day side → 1 on the night side.
   - Its local +X is turned to the light by `towardLight(position, light)` (`:199`):
     ```
     helper = |light.y| < 0.9 ? Y : Z
     up     = normalize(helper − light·dot(helper, light))
     CFrame = fromMatrix(position, light, up)
     ```
   - `Transparency = AlphaPass`, attribute `ShadeMode = "Ramp"`, tag `NightShade`.
2. **"Offset"** (fallback):
   - `d = 0.08·R`
   - a black sphere of radius `sqrt(R² + d² + 2·R·d·sin 3°)`, centred `d` away from the star;
   - this cuts the surface on the circle 3° into the day side;
   - attribute `ShadeOffset = d`.

`ShipStarLight` re-aims both modes (section 2.11). **Port:** not needed. The planet shader computes `max(dot(n, L), 0)` with the real star direction, and the terminator softness comes from the shader.

#### 2.2.5 `buildRing(model, body, ring, texture?, tint?)` (`:233`)

```
R = body.SurfaceRadius; inner = R·ring.inner; outer = R·ring.outer
mid = (inner+outer)/2; width = outer − inner; tilt = ring.tilt or 0
if texture: buildRingImage(...); segments = 0
-- fallback without an image: ring.segments parts
for k in 0..segments−1:
    angle = k/segments·2π
    size  = (width, 4, 2π·mid/segments·1.06)           -- 6 % overlap; larger than 2048 → a Brick mesh stretches it
    part: Sand, ring.colour, Transparency ring.transparency
    CFrame = T(bodyPos)·Rz(tilt)·Ry(−angle)·T(mid, 0, 0)
attributes on the body:
    RingInner = inner; RingOuter = outer
    RingNormal = Rz(tilt)·(0,1,0) = (−sin tilt, cos tilt, 0)      -- never changes: orbits only translate
    RingColour = ring.colour
    RingDensity = 1 − (ring.transparency or 0.5)
    RingImage   = texture or ""
```

For Uranus (tilt 82°) the normal is (−0.990, 0.139, 0).

#### 2.2.6 `tagBody(body, info)`: the attribute contract (`:307`)

| Attribute | Value | Notes |
|---|---|---|
| `SurfaceRadius` | `info.radius` | |
| `CollisionRadius` | star: `radius`; others: `ceil(radius·1.105) + 6` | The "barrier". It sits above the atmosphere shells (≤ 1.08 R), the offset night sphere (1.087 R) and the mountains. Ships never get closer; landing happens with key L. |
| `MinApproachSpeed` | `info.minApproach or 14` | Stars 40, moons 12 |
| `SpeedCurve` | 0.75 | |
| `SurfaceGravityG` | `info.gravityG or 0.5` | **Procedural bodies have no `gravityG`, so every generated planet and moon is 0.5 g** |
| `SurfaceGravity` | `(gravityG or 0.5)·196.2` | |
| `LandingBlocked` | `info.landingBlocked`, else `"NO SOLID SURFACE"` for Gas/Ice Giant | |
| `DisplayName` | `info.name` | |
| `BodyClass` | `info.class` | Stars: `"Star " .. class` |
| `Seed` | `info.seed or 1` | |
| `Landable` | `not star and class ∉ {Gas Giant, Ice Giant}` | |
| `IsStar` | true (stars only) | |
| `MassShadowFactor` | stars 4; moons `info.massShadowFactor` = 4 | Planets use the default 5 (`Gravity.MassShadowFactor`) |
| `OrbitRadius`, `OrbitPeriod`, `OrbitPhase`, `OrbitTilt` | `orbit`, `2π·orbit/max(speed, 0.01)`, `phase` (0..1), `tilt` (deg, default 0) | Only for orbiting bodies |
| tag `CelestialBody` | | Gravity, markers, survey, impostors, debris, textures |

`build()` adds more:

- On the star: `SkyRadius`, `HeatRadius` and `MassShadowRadius`.
- On planets: `HasAtmosphere` and `AtmosphereColour`.
- From `buildRing`: the `Ring*` attributes.
- From `sphere()`: `SphereRadius` and `TextureAsset`.

#### 2.2.7 Star (`build`, `:362`)

- **The star.** `Body` = a `MeshType.Sphere` of `Star.radius` at (0,0,0), material **Neon**, colour `Star.colour`.
- **The corona.** A sphere of `radius·(coronaScale or 1.12)`, material **ForceField**, colour `Star.corona`, `Transparency 0.62`.
- **`tagBody`** with `class = "Star " .. (class or "G")`, `isStar`, `minApproach 40`.
- **Attributes:**
  - `SkyRadius = skyRadius or radius`;
  - `HeatRadius = heatRadius or 1.6·radius`;
  - `MassShadowRadius = massShadowRadius or 4·radius`.

#### 2.2.8 Planets and moons (`build`, `:390` and `:479`)

```
for index, planet in Planets:
    rng   = Random((planet.seed or index)·7919 + 13)
    phase = planet.phase or rng:NextNumber()                    -- draw 1
    pos   = (cos(2π·phase)·orbit, 0, sin(2π·phase)·orbit)       -- REST position: no tilt, y = 0
    look  = Random((planet.seed or index)·6151 + 7)
    texture = VisualAssets.bodyTexture(name, class, planet.seed or index)
    variant = VisualAssets.variantLook(texture)                 -- nil unless texture is a 2026-11-04 variant
    body = sphere("Body", radius, pos, variant ? variant.mean : colour, material,
                  {smooth, texture, tint = (texture and classColour and not variant) ? tintFor(colour, classColour) : nil})
    body.CFrame = T(pos)·Rz(rad(ring?.tilt or 0))·Ry(look:NextNumber()·2π)    -- axis tilt like the ring, fixed random yaw
    if atmosphere:
        air = variant?.haze or atmosphereColour or colour
        shell "Atmosphere"      R·1.025, ForceField, air, Transparency 0.72   (smooth)
        shell "AtmosphereHaze"  R·1.08,  ForceField, air, Transparency 0.86   (smooth)
    tagBody(body, {radius, name, class, landable, seed = planet.seed or index, orbit, speed, phase,
                   tilt = planet.tilt or rng:NextNumber(−1.5, 1.5),          -- draw 2 (degrees)
                   gravityG, landingBlocked})
    if atmosphere: HasAtmosphere = true; AtmosphereColour = air
    nightShade(model, body, radius, star = (0,0,0))
    clouds = VisualAssets.cloudTexture(name)    -- always nil (Clouds.Earth = ""); would be a R·1.012 shell
    if ring:
        ringTexture = VisualAssets.ringTexture(name, planet.seed or index)
        tint = nil
        if ringTexture ≠ Rings[name] and not isRingVariant(ringTexture):   -- only the 2 generic images get tinted
            top = max(c.R, c.G, c.B, 0.05); tint = (c.R/top, c.G/top, c.B/top)   -- c = ring.colour
        buildRing(model, body, ring, ringTexture, tint)
    for moonIndex, moon in planet.moons:
        mrng   = Random((planet.seed or index)·104729 + moonIndex)
        mphase = moon.phase or mrng:NextNumber()
        mpos   = pos + (cos(2π·mphase)·moon.orbit, 0, sin(2π·mphase)·moon.orbit)
        moonSeed = (planet.seed or index)·31 + moonIndex
        moonTexture = bodyTexture(moon.name, moon.class or "Moon", moonSeed); moonVariant = variantLook(moonTexture)
        moonBody = sphere("Body", moon.radius, mpos, moonVariant ? mean : moon.colour, moon.material or Concrete,
                   {smooth, moonTexture, tint = (moonTexture and Bodies[moon.name] == nil and not moonVariant)
                                                 ? tintFor(moon.colour, MOON_COLOUR) : nil})
        moonBody.CFrame = T(mpos)·Ry(Random(moonSeed·6151 + 7):NextNumber()·2π)
        tagBody(moonBody, {radius, name, class = "Moon", landable, seed = moonSeed, orbit, speed, phase = mphase,
                           tilt = moon.tilt or mrng:NextNumber(−5, 5), minApproach 12, massShadowFactor 4, gravityG})
        nightShade(moonModel, moonBody, moon.radius, (0,0,0))
```

**Points that matter for the port:**

- **Planets never rotate over time.** The yaw is a fixed random orientation, so the same class image looks different on each body.
- **Moons have no axis tilt.**
- **The rest position (y = 0, no tilt) is what stations are placed from** (section 2.5.5).

#### 2.2.9 `makeProcedural(seed, planetCount, starColour, starClass)`: generated systems (`:532`)

**Templates** (`PALETTE`, `:523`):

| # | class | colour RGB | material | small |
|---|---|---|---|---|
| 1 | Rocky Body | 126,108,92 | Slate | yes |
| 2 | Ice World | 186,208,220 | Glacier | yes |
| 3 | Volcanic Body | 122,66,54 | Basalt | yes |
| 4 | Ocean World | 58,108,156 | Marble | yes |
| 5 | Gas Giant | 200,168,124 | Sand | no |
| 6 | Ice Giant | 120,190,210 | Glacier | no |

**The algorithm.** The draw order is exact; every `rng` call is one draw from `Random(seed)`.

```
rng = Random(seed)
starRadius = floor((1200 + rng:num()·1400)·2)                   -- 2400..5199
skyRadius  = floor((300 + rng:num()·400)·10)                    -- 3000..6999
heatRadius = floor(26000 + rng:num()·22000)                     -- 26000..47999
shadow     = heatRadius·2.5                                     -- only the jump-arrival distance
orbitSpeed(o) = 5.0·sqrt(heatRadius·1.3·10 / o)                 -- "Kepler-ish", ~5 studs/s inside
name = ShipNames.generate(seed, 0.35)                           -- its own Random(seed); used for system AND star
def = {Name = name, Seed = seed,
       Star = {name, radius = starRadius, colour = starColour or RGB(255,240,210),
               corona = colour:Lerp(RGB(255,150,80), 0.5), class = starClass or "G",
               skyRadius, heatRadius, massShadowRadius = shadow, coronaScale = 1.35},
       Planets = {}}
orbit = (heatRadius·(1.15 + rng:num()·0.5) + starRadius)·10
for i = 1, max(planetCount, 0):
    t    = PALETTE[rng:int(1, 6)]
    base = t.small ? floor(90 + rng:num()·260) : floor(700 + rng:num()·1900)
    R    = t.small ? max(150, base·10) : base·4                 -- small 900..3490, giant 2800..10396
    planet = {name = name .. " " .. i, radius = R, orbit = floor(orbit), speed = orbitSpeed(orbit),
              colour = t.colour:Lerp(WHITE, rng:num(−0.12, 0.15)),
              material = t.material, class = t.class, classColour = t.colour,
              landable = solidGround(t.small, base),   -- draws rng:num() only if small and base < 260 (kept for the
                                                       -- sequence); returns t.small
              atmosphere = rng:num() < 0.7,
              atmosphereColour = t.colour:Lerp(RGB(150,190,255), 0.5),
              seed = seed + i·977}
    if not t.small and rng:num() < 0.45:
        planet.ring = {inner = 1.3 + rng:num()·0.2, outer = 1.9 + rng:num()·0.5, segments = 80,
                       tilt = rng:num(−25, 25), colour = t.colour:Lerp(RGB(210,200,180), 0.6), transparency = 0.45}
    moonCount = t.small ? rng:int(0, 2) : rng:int(1, 4)
    for m = 1, moonCount:
        mR = max(150, floor(R·rng:num(0.12, 0.32)))
        mO = floor(R·(5 + rng:num()·3)·1.6^(m−1))
        if m > 1: mO = max(mO, floor(prev.orbit·1.3))          -- no shared orbits
        moon = {name = planet.name .. " " .. char(96+m) ("a".."d"), landable = true, radius = mR, orbit = mO,
                speed = 1.2 + rng:num()·1.6, colour = RGB(150,146,140):Lerp(WHITE, rng:num(−0.2, 0.2))}
    orbit = orbit·(1.35 + rng:num()·0.75)
-- neighbour spacing
reachOf(p) = max(ceil(p.R·1.105)+6, ring ? p.R·ring.outer : 0, max over moons (orbit + radius))
spacePlanets(): for i = 2..n: need = P[i−1].orbit + reachOf(P[i−1]) + reachOf(P[i]) + 10000·10
                              if P[i].orbit < need: P[i].orbit = ceil(need); P[i].speed = orbitSpeed(P[i].orbit)
spacePlanets()
-- pull into the play field (ratio compression, deterministic)
limit = ShipConfig.Ship.FieldRadius·0.8 = 7,200,000
first = P[1].orbit; power = 1
for p in P:
    reach = max(p.R·1.06, ring ? p.R·ring.outer : 0, max over moons (orbit + radius))
    if p.orbit > first: room = max(limit − reach, first); power = min(power, ln(room/first) / ln(p.orbit/first))
if power < 1: for p in P: p.orbit = floor(first·(p.orbit/first)^power); p.speed = orbitSpeed(p.orbit)
spacePlanets()                                                -- again: compression also shrinks the gaps
```

**Resulting ranges** (computed):

- Star radius 2,400–5,199.
- First orbit ≈ 323,000–844,000.
- Every step outward ×1.35–2.10.
- Moons sit at 5–8 R ×1.6^(m−1) from their planet.
- The outermost orbit is ≤ 7.2 M, unless the second `spacePlanets()` pushes it out again. There is no further check, which is a known edge case.

**What the generated system does not have:**

- `gravityG` (so every body is 0.5 g);
- `phase` and `tilt` (they come from `build()`);
- `landingBlocked` (giants get it from `tagBody`).

**Textures** are chosen by `bodyTexture(name, class, planet.seed)` from the class pool (section 2.7.3). If the pool image is one of the 2026-11-04 variants, the body colour becomes the variant mean and the haze becomes the atmosphere colour.

#### 2.2.10 Port notes for ShipSystemBuilder

- **Drop** `sphere`, the night shade, ring segments, the image quads, `MAX_PART` and `smoothSpheres`.
- **Keep:**
  - all formulas;
  - the attribute contract (as fields);
  - the texture, tint and variant choice;
  - the ring attributes;
  - the procedural generator with its draw order.
- **Determinism:**
  - Use `StrictMath` (or a fixed-point-safe path) in generation code, because `floor()` thresholds must agree on every device.
  - Port Roblox `Random`, or embed golden data (Appendix C). If Roblox parity is not required, any deterministic RNG works, but then names and systems differ from the Roblox game (question Q7).

### 2.3 ShipStarCatalog: the 180-system galaxy (`ShipCore/ShipStarCatalog.lua`)

**Purpose.** It generates the whole star catalog from one seed. Nothing is stored. It is called with `ShipConfig.StarMap = {Seed 20260824, SystemCount 180, RadiusLightYears 60, Thickness 16}` by `ShipStarMap` (`ShipClient/ShipStarMap.lua:197`) and by `ShipStations.getCatalog` (`ShipStations.lua:154`). "Same numbers, same galaxy."

**Spectral classes** (`CLASSES`, `:23`; the weights sum to 100):

| letter | weight | colour RGB | sizeFactor (map dot) |
|---|---:|---|---:|
| M | 76 | 255,130,100 | 0.70 |
| K | 12 | 255,180,120 | 0.85 |
| G | 7 | 255,240,200 | 1.00 |
| F | 3 | 240,245,255 | 1.15 |
| A | 1.4 | 200,220,255 | 1.35 |
| B | 0.5 | 160,195,255 | 1.60 |
| O | 0.1 | 140,175,255 | 1.90 |

`pickClass(rng)` (`:38`): `roll = rng:num()·100`. It returns the first class whose cumulative weight ≥ roll, falling back to M.

**`build(seed, count, radius, thickness)`** (`:53`):

```
rng = Random(seed)
systems[1] = {seed = 0, name = "Sol", class = "G", colour = RGB(255,244,214), sizeFactor = 1.0,
              position = (0,0,0), planets = 8, isHome = true}          -- Sol always first, at the origin
for index = 2..count:
    systemSeed = seed + index·7919
    distance = sqrt(rng:num())·radius            -- sqrt: even density over the disc's area
    angle    = rng:num()·2π
    bulge    = 1 − (distance/radius)·0.7         -- thicker at the centre
    height   = (rng:num() − 0.5)·thickness·bulge
    class    = pickClass(rng)
    systems[index] = {seed = systemSeed, name = ShipNames.generate(systemSeed, 0.35), class = class.letter,
                      colour = class.colour, sizeFactor = class.size,
                      position = (cos(angle)·distance, height, sin(angle)·distance),   -- light years
                      planets = Random(systemSeed):NextInteger(0, 9), visited = false}
```

**Details:**

- Sol's catalog colour (255,244,214) differs from the Sol star colour (255,246,224). The catalog colour is only used for the map dot.
- `describe(system)` (`:99`) returns `"%s  |  Class %s  |  %d planet[s]"`. **It is unused** (grep).
- The system name, the star name and the catalog name are the same string. Planets are named `"<name> <i>"` and moons `"<planet> a|b|c|d"`. Names can repeat between systems, because nothing checks for uniqueness.

**Port.** `StarCatalog.kt`: build it once at startup (180 entries, cheap) and keep it in memory. Positions stay in light years; S does not apply to the galaxy.

### 2.4 ShipNames: deterministic names (`ShipCore/ShipNames.lua`)

**Word lists:**

- **ONSETS** (`:28`, 30): Ar Bel Cor Dre Er Fen Gal Hy Il Ker Lu Mar Nyx Ob Pel Qua Rhe Sol Tar Ulv Ves Wyr Xan Yr Zeph Cae Dro Eth Var Thal
- **MIDDLES** (`:34`, 20): a e i o u an en ar or ur ith oth ael ien yra ova esh una ako eri
- **CODAS** (`:39`, 20): n s th r l x ra ne ka va dor mir tas nis vex lyn ryn sar keth vani
- **CATALOGS** (`:45`): HD HR LTT GJ BD NGC Wolf Ross Kepler Tycho. These are real astronomical catalog prefixes.
- **ROMAN** (`:46`): I II III IV V VI VII VIII IX X
- **LETTERS** (`:47`): A B C D E F G

**The functions:**

```
pick(rng, list) = list[rng:NextInteger(1, #list)]
properName(rng)  = pick(ONSETS) .. pick(MIDDLES) .. (rng:num() < 0.75 ? pick(CODAS) : "")
catalogName(rng) = ("%s %d"):format(pick(CATALOGS), rng:int(100, 9999)) .. (rng:num() < 0.35 ? " " .. pick(LETTERS) : "")
generate(seed, properChance = 0.3):
    rng = Random(seed)
    base = rng:num() < properChance ? properName(rng) : catalogName(rng)
    if rng:num() < 0.5: base ..= " " .. pick(ROMAN)
    return base
```

Every call site passes `properChance = 0.35`. Example shapes: "Erava" (Er+a+va), "Taroketh IV" (Tar+o+keth, numeral), "LTT 4021 C", "HD 882 C II". `classify(seed)` (`:113`, 8 classes) is **unused**; four of its classes exist nowhere else (see question Q9).

### 2.5 ShipStations: which stations exist and where they hang (`ShipCore/ShipStations.lua`)

**Purpose.** This is the station *catalog*, shared by server and client. It holds:

- Sol's hand-made stations;
- deterministic generated stations for every other system;
- the player stations injected by the galaxy store;
- the offset formula;
- the body list used for survey.

Models, docking, the carry and the speed zones are documented in `08-stations-economy.md` (section 2). Only placement and the catalog are repeated here.

#### 2.5.1 Sol stations (`:41`) and the home station (`:83`)

`HomeStation = "SOL-MERIDIAN"`. The host angular diameter is computed as `2·asin(R_host / Distance)` and is the same at any S.

| Id | Name | Host | Economy | Shipyard | Model | Distance | Angle | Elev. | Distance / host R | Host seen from the station |
|---|---|---|---|---|---|---:|---:|---:|---:|---:|
| SOL-MERIDIAN | MERIDIAN STATION | Earth | HighTech | yes | SpireStation | 6600 | 90 | 24 | 3.11 | 37.5° |
| SOL-TRANQUILITY | TRANQUILITY YARDS | Luna | Industrial | yes | FoundryStation | 3400 | 200 | −32 | 5.86 | 19.6° |
| SOL-ARES | ARES DEEP | Mars | Extraction | no | HollowStation | 4600 | 60 | 28 | 4.07 | 28.4° |
| SOL-GALILEAN | GALILEAN REFINERY | Jupiter | Refinery | no | RefineryStation | 20000 | 120 | 30 | 2.15 | 55.5° |
| SOL-TITAN | TITAN HYDROPONICS | Titan | Agricultural | no | GardenStation | 4100 | 300 | 34 | 4.77 | 24.2° |
| SOL-HELIOS | HELIOS FORGE | Mercury | Industrial | no | RingStation | 4400 | 270 | −26 | 5.43 | 21.2° |

Placement comments in the Lua:

- ARES DEEP is "steeper than the others: Phobos orbits at 3600 in the plane".
- The GALILEAN REFINERY is "outside the ring (to 16000) and above its plane".
- HELIOS FORGE "keeps the classic ring station in Sol".

Descriptions, shown verbatim in the station UI:

- **MERIDIAN:** "THE BUSIEST PORT IN SOL. MERIDIAN BUILDS THE SHIP COMPUTERS HALF THE GALAXY FLIES WITH AND FEEDS ITSELF ON IMPORTED FOOD AND RAW METAL."
- **TRANQUILITY:** "LUNAR FOUNDRIES AND THE OLDEST SHIPYARD IN THE SYSTEM. ALLOYS AND CONSUMER GOODS GO OUT, METALS AND FUEL COME IN."
- **ARES:** "A MINING HUB ABOVE THE RED PLANET. TITANIUM AND COPPER LEAVE BY THE THOUSAND TONS; THE MINERS PAY WELL FOR FOOD AND MEDICINE."
- **GALILEAN:** "SKIMMER FLEETS DIVE INTO JUPITER'S CLOUDS, AND THIS REFINERY TURNS THE GAS INTO FUEL FOR THE WHOLE SYSTEM."
- **TITAN:** "GREENHOUSE DECKS IN SATURN'S SHADOW. GRAIN AND PROTEIN FOR ALL OF SOL, PAID FOR IN MEDICINE AND MACHINERY."
- **HELIOS:** "SOLAR SMELTERS IN MERCURY'S SHADOW. THE HEAT IS FREE; THE ORE COMES IN FROM MARS."

#### 2.5.2 Generated stations (`baseForSystem`, `:198`)

**Name parts.**

- **NAMES** (`:88`, 45): ARDEN BECKETT CALLOWAY EKLUND FARROW GALEN HOLLIS IVERS JANSEN KOVAC LINDQVIST MARLOW NAKAMURA ORSINI PETROV QUILLON RAVEL SATO THORNE ULRIC VANCE WELLER YATES ZELLER ADEYEMI BRANDT CASTELLAN DELACROIX ESPOSITO FAIRBANKS GRIEVE HARTMANN IMRE JUAREZ KESSLER LAINE MOREAU NOVAK ODUYA PALMQVIST RENNER SOLBERG TANAKA VOSS WINTER.
- **SUFFIX** (`:96`):

| Economy | Suffixes |
|---|---|
| HighTech | STATION HUB EXCHANGE |
| Industrial | YARDS WORKS STATION |
| Extraction | DEEP DIGS STATION |
| Refinery | REFINERY SKIMWORKS DEPOT |
| Agricultural | HABITAT GARDENS HYDROPONICS |
| Service | PORT TERMINAL RETREAT |

**Descriptions** (`BLURB`, `:105`), by economy:

- **HighTech:** "LABS, CLEAN ROOMS AND CHIP FOUNDRIES. IT SELLS TECHNOLOGY AND BUYS FOOD AND METAL."
- **Industrial:** "FOUNDRIES AND ASSEMBLY HALLS. IT SELLS ALLOYS AND GOODS AND BUYS METAL, FUEL AND CHIPS."
- **Extraction:** "A MINING OUTPOST. ORE AND ICE GO OUT; FOOD, MEDICINE AND MACHINERY COME IN."
- **Refinery:** "A GAS REFINERY. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN."
- **Agricultural:** "GREENHOUSE DECKS. FOOD GOES OUT; MEDICINE, MACHINERY AND GOODS COME IN."
- **Service:** "A STOPOVER FOR CREWS AND TRAVELLERS. IT PAYS WELL FOR FOOD AND COMFORT."

**The algorithm.** The draw order is exact; `rng = Random(system.seed·31 + 7)`:

```
if index == 1: Sol list (each entry cloned + SystemIndex = 1, SystemName = "Sol")
elif #definition.Planets > 0:
    roll  = rng:num(); count = roll < 0.35 ? 0 : roll < 0.8 ? 1 : 2      -- 35 % / 45 % / 20 %
    count = min(count, #Planets); used = {}
    for n = 1..count:
        host = rng:int(1, #Planets); while used[host] (at most #Planets times): host = host % #Planets + 1
        used[host] = true; planet = Planets[host]
        economy = pickEconomy(planet.class, rng)
        name = NAMES[rng:int(1, 45)] .. " " .. SUFFIX[economy][rng:int(1, 3)]
        elevation = rng:num(35, 50)·(rng:num() < 0.5 ? −1 : 1)       -- steep: rings and moons lie in the plane
        id = ("S%d-%d"):format(system.seed, n)
        entry = {Id = id, Model = pickModel(id, economy), Name = name, Host = planet.name, Economy = economy,
                 Shipyard = (economy == HighTech or Industrial) and rng:num() < 0.6,   -- draws only for those two
                 Distance = floor(planet.radius·2.1 + 2200), Angle = rng:num(0, 360), Elevation = elevation,
                 Description = BLURB[economy], SystemIndex = index, SystemName = getSystemName(index)}
```

`pickEconomy(class, rng)` (`:130`):

```
roll = rng:num()
if roll < 0.15: return rng:num() < 0.6 ? "HighTech" : "Service"
Gas Giant → Refinery; Ice Giant → roll < 0.6 ? Refinery : Extraction; Ocean World → Agricultural
Ice World → Extraction; Volcanic Body → roll < 0.55 ? Industrial : Extraction
otherwise (Rocky Body, …) → roll < 0.55 ? Extraction : Industrial
```

`pickModel(id, economy)` (`:117`) chooses the model independently of the RNG, so it never changes the sequence:

```
h = 0; for each byte b of id: h = (h·31 + b) % 1000003
return Types[economy][h % #Types[economy] + 1]
```

`Types` comes from `ShipConfig.Stations.Types` (section 3.5). A missing model falls back to `ShipConfig.Stations.Model` = "RingStation".

**Hosts.** Generated stations only have planets as hosts. Sol also uses moons (Luna, Titan).

#### 2.5.3 Caches and lookups

- `getCatalog()` (`:154`) builds the star catalog once, as described in section 2.3.
- `getDefinition(index)` (`:163`) returns `ShipSolData` for index 1, and otherwise a cached `makeProcedural(seed, planets, colour, class)`.
- `getSystemName(index)` (`:179`) returns "Sol", the catalog name, or "Unknown".
- `baseForSystem(index)` is cached per index.
- `forSystem(index)` (`:260`) is the base list plus `playerStations[index]`, cached until `setPlayerStations(index, defs)` (`:274`) clears it.
- `getPlayerStations(index)` (`:279`).
- `find(index, id)` (`:283`) is a linear scan of `forSystem(index)`.
- `findAnywhere(id)` (`:293`):
  - `"SOL-…"` → `find(1, id)`.
  - `"P<digits>"` (a player station) → search all loaded player lists, otherwise nil.
  - `"S<seed>-<n>"` → find the catalog index whose `seed` matches, then `find`.
  - Anything else → nil.
- `getBodies(index)` (`:336`) is the survey and resource body list: `name → {class, starClass?, ring?, landable, home, radius, parent?}`.
  - The star: `class = "Star"`, `starClass` = its letter, `radius`, and `home = (index == 1)`.
  - Moons: `class = "Moon"`, `parent` = the planet name.
  - **Here `landable` is the definition flag** (`planet.landable == true`), so in Sol it is false for the giants and true for the rest.

#### 2.5.4 The offset (`ShipStations.offset(station, hostPos, starPos)`, `:321`)

```
toStar = starPos − hostPos; flat = (toStar.x, 0, toStar.z)
base   = |flat| > 1e−3 ? normalize(flat) : (0, 0, −1)
dir    = Ry(rad(Angle))·base        -- rotation about +Y: x' = x·cosA + z·sinA, z' = −x·sinA + z·cosA
e      = rad(Elevation)
offset = (dir·cos e + (0,1,0)·sin e)·Distance          -- default Distance 4000 if missing
```

Example: for Meridian, `Angle 90` gives `dir = (b.z, 0, −b.x)`, a quarter turn from the star direction. The station is then raised 24°, at 6600 from Earth's centre.

#### 2.5.5 Runtime placement and carry (`ShipClient/ShipStation.lua`)

- **Building the list.** `ShipStation:build(systemIndex, folder)` (`:561`) calls `addStation(def)` for each entry of `forSystem`. It must run *after* `buildSystem`, because the hosts must exist.
- **Host lookup.** `findHost` (`:330`) picks the tagged body whose parent Model name, or whose `DisplayName`, equals `def.Host` exactly. If there is none, it warns and skips the station.
- **Placement.** `placement(def, host)` (`:576`):
  - The offset is computed from the host's **rest position** (`ShipOrbits.restPosition`, section 2.9) and the star at the universe origin. Every player therefore sees the same spot, whenever they arrived.
  - `look = −offset.unit` points the mouth (−Z) at the host.
  - `up = |look.y| > 0.95 ? X : Y`, and `rotation = lookAt(0, look, up)`.
  - The offset and rotation are fixed for the life of the system.
- **Every frame** (`place`, `:846`):
  - `rootCF = T(host.Position + offset)·rotation`. The station translates with the host and never rotates.
  - The full model is shown within `LodDistance` = 60,000; beyond that only the beacon remains.
- **Reference frame.**
  - The frame is the nearest station within `ZoneRadius` = 2200, or 2750 (×1.25 hysteresis) when it is already the frame. Docking can force the frame.
  - Before thrust, the ship is moved by `getCarry() = host.Position + offset − lastPosition` (`:1018`, used at `ShipClient.lua:2438`).
  - **The ship's velocity is not changed.** The carry is a pure translation.
- **Player stations** (`ShipResources.slot`, `ShipCore/ShipResources.lua:537`) use 8 slots:

  ```
  Angle     = (i−1)·45 + 22.5
  Elevation = (moon ? 55 : 28)·(i odd ? +1 : −1)
  Distance  = floor(R_host·2.1 + (moon ? 1600 : 2200))
  ```

  `pickSlot` (`:549`) takes the first slot that is ≥ 2400 (`SlotClearance`) from every other station on the same host. It tests with the host at the origin and the star at (0, 0, −1e7).

**Port and S.**

- **Scale by S:** `Distance`, the player-slot distance (both terms), and `SlotClearance`. The clearance is compared against scaled offsets, so the slot decisions stay identical.
- **Keep absolute:** `LodDistance` (relative to the model size), `ZoneRadius`, `DockingRange`, and every station-local value.
- At S = 3000, MERIDIAN sits 19,800 km from Earth's centre (13,440 km altitude), and Earth still appears 37.5° wide.
- **Generalise the carry.** Bodies need the same treatment as stations, because planets move at km/s at S = 3000 (section 2.9.4).

### 2.6 ShipPalettes: surface materials and orbit colours by class (`ShipCore/ShipPalettes.lua`)

**Purpose.** One source for two consumers, so that space look and ground never disagree again:

- the **materials** of terrain at the landing site (`ShipPlanetTerrain`, `ShipSurfaceTerrain`, terrain area);
- the **colour ramps** of runtime textures (`ShipPlanetTexture`) and of the terrain style.

The header comment records why: "Mercury looked like Earth from space".

**Material palettes** (`PALETTES`, `:29`; `DEFAULT` `:110` = Sand/Ground/Rock/Slate, no water):

| Class | shore | low | high | peak | water | sea (fraction of R) |
|---|---|---|---|---|---|---|
| Terrestrial World | Sand | Grass | Rock | Snow | yes | 0.998 |
| Ocean World | Sand | Grass | Rock | Snow | yes | 1.012 |
| Water World | Sand | Grass | Rock | Snow | yes | 1.020 |
| Rocky Body | Sand | Ground | Rock | Slate | no | |
| Metal-Rich Body | Slate | Metal | CorrodedMetal | Rock | no | |
| High Metal Content | Slate | CorrodedMetal | Rock | Metal | no | |
| Volcanic Body | Basalt | Basalt | CrackedLava | Rock | no | |
| Ice World | Glacier | Ice | Glacier | Snow | no | |
| Ice Giant | Glacier | Ice | Glacier | Snow | no | |
| Ammonia World | Sandstone | Ice | Glacier | Snow | no | |
| Moon | Sand | Concrete | Rock | Slate | no | |
| Greenhouse World | Sandstone | Sandstone | CorrodedMetal | Rock | no | |
| Gas Giant | Sand | Sand | Sandstone | Sandstone | no | |

- `materialAt(t, palette)` (`:219`), with `t` = *relative* height 0..1 within this body's terrain: `t < 0.30` shore, `< 0.62` low, `< 0.86` high, otherwise peak.
- `seaLevel(palette)` (`:235`) returns `palette.sea` when there is water, otherwise nil (no water at all).

**Colour ramps** (`COLOURS`, `:126`; `DEFAULT_COLOURS` `:199` = the Rocky Body ramp). RGB values; `deep` and `shallow` are used only by water worlds; ice applies only beyond `iceLatitude` (polar fraction):

| Class | deep | shallow | shore | low | high | peak | ice | iceLatitude |
|---|---|---|---|---|---|---|---|---:|
| Terrestrial World | 14,42,84 | 38,92,140 | 196,182,138 | 74,116,56 | 108,96,74 | 150,146,140 | 238,244,250 | 0.74 |
| Ocean World | 10,36,78 | 34,100,150 | 190,180,145 | 66,110,60 | 96,92,72 | 150,150,145 | 238,244,250 | 0.78 |
| Water World | 10,36,78 | 34,100,150 | 190,180,145 | 66,110,60 | 96,92,72 | 150,150,145 | 238,244,250 | 0.80 |
| Rocky Body | | | 150,130,108 | 122,104,86 | 96,86,76 | 140,134,128 | 210,212,216 | 0.92 |
| Metal-Rich Body | | | 120,116,112 | 138,132,124 | 96,92,88 | 168,162,152 | 200,204,210 | 0.95 |
| High Metal Content | | | 126,108,94 | 140,116,96 | 102,88,78 | 160,150,138 | 200,204,210 | 0.95 |
| Volcanic Body | | | 58,38,34 | 74,44,38 | 120,52,30 | 178,74,32 | 90,60,52 | 0.99 |
| Ice World | | | 176,196,210 | 198,218,230 | 214,232,242 | 240,248,255 | 248,252,255 | 0.45 |
| Ice Giant | | | 70,140,180 | 90,170,200 | 120,200,220 | 180,230,240 | 220,245,250 | 0.85 |
| Ammonia World | | | 160,156,120 | 184,176,128 | 200,200,180 | 226,228,214 | 236,240,232 | 0.70 |
| Moon | | | 126,122,116 | 148,144,138 | 104,100,96 | 172,168,162 | 206,208,212 | 0.96 |
| Greenhouse World | | | 196,168,108 | 214,186,124 | 168,132,86 | 226,204,156 | 230,214,176 | 0.99 |
| Gas Giant (`banded = true`) | | | 196,164,118 | 214,188,148 | 164,128,90 | 232,214,184 | 238,228,208 | 0.99 |

The classes actually used by bodies are: Terrestrial, Greenhouse, Rocky, Gas Giant, Ice Giant, Moon (Sol), plus Ice World, Volcanic and Ocean (generated). Metal-Rich, High Metal, Ammonia and Water World are **never assigned to any body** (question Q9).

**Port.**

- Port as `Palettes.kt`, with materials as an enum shared with the terrain area.
- The colour ramps feed the fallback texture generator (section 2.13) and the terrain style.

### 2.7 ShipVisualAssets: images for bodies, rings and sky (`ShipCore/ShipVisualAssets.lua`)

**Purpose.** It holds every image id in one place. The images are made offline by the project's generator `Tools/VisualGen` (list in `VISUALS_UPLOAD.md`, not in this repo) and uploaded by AFloidDEV.

- An empty id means "not uploaded". The game then falls back to the plain sphere, segment ring or old sky.
- **Why uploaded images and not runtime textures** (`:23`): `EditableImage` only draws on a `MeshPart`, which ends at 2048 studs, while `SpecialMesh` accepts only uploaded images.

**Inventory.** Ids are listed in Appendix E.

| Group | Line | Content |
|---|---|---|
| `SphereMesh` / `SphereMeshRadius` | `:40` | `rbxasset://models/MaterialManager/smooth_sphere.mesh`, radius 85.6281 mesh units. It has 1652 vertices (432 for MeshType.Sphere), and its **UVs form a cube cross**: every body image is baked onto this cross (section 2.8). |
| `QuadMesh` / `QuadMeshSize` | `:47` | `CompositQuad.mesh`, 1024 units, one corner at the origin, normal +Z, front side only (carries the ring images) |
| `Bodies` | `:51` | 17 Sol images (4096², cube cross): Mercury, Venus, Earth, Mars, Jupiter, Saturn, Uranus, Neptune, Luna, Phobos, Deimos, Io, Europa, Ganymede, Callisto, Titan, Triton. Earth's clouds are painted into its image. |
| `Clouds` | `:75` | `Earth = ""`. Intentionally empty: Roblox mis-sorted a cloud shell against the night skin. `Earth_clouds.png` exists (uploaded as 88557606026712) but is not used. |
| `Classes` | `:81` | 2 images each (4096²) for Rocky Body, Ice World, Volcanic Body, Ocean World, Gas Giant, Ice Giant and Moon |
| `Variants` | `:100` | 29 named images from 2026-11-04 (`class, id, title, mean, haze?`). They are appended to their class pool at load time (Appendix E). |
| `Fallback1K` | `:136` | The first 1024² uploads of Bodies, Classes and Rings. Only `Fallback1K.Classes` is read, for the debris textures (`rockTextures`). |
| `Rings` | `:165` | Saturn, Jupiter, Uranus (own images, they bring their own colour) and `Generic` (2 images, tinted to the ring colour) |
| `RingProfiles` | `:177` | 128-step opacity per ring image, from the planet centre (f = 0) to the outer edge (f = 1), two digits 00..99 each (Appendix D) |
| `RingVariants` | `:191` | 5 named rings from 2026-11-04, each with an id and a profile. They are appended to `Rings.Generic` and `RingProfiles.Generic`, and bring their own colour. |
| `NightRamp` | `:256` | Black, alpha 0 (day) → 1 (night), +X towards the light |
| `AlphaPass` | `:260` | 0.02: Roblox only uses an image's alpha when Transparency > 0 |
| `Skyboxes` | `:265` | Ember, Glacier, Violet (6 faces each), **all empty**. Sol therefore keeps the place's own sky (question Q6). |

#### 2.7.1 Pools: built at load time (`:201`)

```
for name in sort(keys(Variants)):            -- alphabetical: Var_EarthLike_1..5, Var_GasGiant_1..5, Var_IceGiant_1..3,
    v = Variants[name]                       --   Var_IceWorld_1..5, Var_Moon_1..3, Var_Rocky_1..3, Var_Volcanic_1..5
    id = assetId(v.id)                       -- number or digit string -> "rbxassetid://…"; "" -> skip
    if id: Classes[v.class].append(id); variantLooks[id] = v
for name in sort(keys(RingVariants)):        -- Var_Ring_1..5
    if id: Rings.Generic.append(id); RingProfiles.Generic.append(v.profile or ""); ringVariantIds[id] = true
```

The resulting pool sizes are:

| Pool | Images |
|---|---:|
| Rocky Body | 5 |
| Ice World | 7 |
| Volcanic Body | 7 |
| Ocean World | 7 |
| Gas Giant | 7 |
| Ice Giant | 5 |
| Moon | 5 |
| Rings.Generic | 7 |

#### 2.7.2 Lookup functions

- `pick(list, seed)` (`:275`):
  - With a string, return it if it is non-empty.
  - With a list, keep the non-empty ids → `ready[(|floor(seed or 1)| % #ready) + 1]`, or nil if none.
- `bodyTexture(name, class, seed)` (`:294`): `Bodies[name]` if set, else `pick(Classes[class], seed)`.
- `variantLook(texture)` (`:303`): the variant record `{class, id, title, mean, haze?}` or nil.
- `isRingVariant(texture)` (`:308`).
- `nightRamp()` (`:312`).
- `cloudTexture(name)` (`:316`).
- `ringTexture(name, seed)` (`:320`): `Rings[name]` if set, else `pick(Rings.Generic, seed)`.
- `ringProfile(texture)` (`:332`):
  - It finds the texture's slot in `Rings` (named entry or `Generic[index]`) and takes the same slot in `RingProfiles`.
  - It decodes pairs of digits into `values[i] = tonumber(pair)/99`, i = 1..128, cached.
  - It returns nil if there is no profile or the string is shorter than 2.
- `rockTextures(icy)` (`:366`): the 1K class images of `Moon` (icy rings), or `Moon` + `Rocky Body` (dark rings).
- `skybox(seed)` (`:380`): the complete skyboxes; Sol (no seed) takes `ready[1]`, generated systems take `ready[|floor(seed)| % n + 1]`. Currently this is always nil.

#### 2.7.3 The rules for a body's look (which image, colour and tint)

| Body | Image | Body colour (maps, debris, fallback) | Tint (`VertexColor`) | Atmosphere colour |
|---|---|---|---|---|
| Sol planet or moon | `Bodies[name]` | data `colour` | none | data `atmosphereColour` |
| Generated planet, base class image | `pool[class][abs(seed) % n + 1]` (pool = 2 base images + variants, section 2.7.1) | data `colour` | `tintFor(colour, classColour)` | data `atmosphereColour` |
| Generated planet, variant image | variant | `variant.mean` | none | `variant.haze` or data |
| Generated moon, base image | `pool.Moon[abs(seed) % 5 + 1]` (indices 1–2; seed = planet.seed·31 + m) | data `colour` | `tintFor(colour, RGB 150,146,140)` | n/a |
| Generated moon, variant | variant | `mean` | none | n/a |
| Sol ring (Saturn, Jupiter, Uranus) | `Rings[name]` | | none (the image has the colour) | |
| Generated ring, Generic 1–2 | `Rings.Generic[abs(seed) % 7 + 1]` (indices 1–2) | | ring colour / max channel | |
| Generated ring, variant | variant | | none | |

**The fallback pool index.** With all ids filled, the pool index is `|seed| mod n`. The pools are listed in section 2.7.1, and the seeds are `systemSeed + i·977` for planets and `planet.seed·31 + m` for moons.

**Port.**

- Ship the images as app assets: `world/bodies/<Name>`, `world/classes/<class>_<1|2>`, `world/variants/<Var_…>`, `world/rings/<…>`, `world/night_ramp`.
- Convert them offline from the cube cross to cube maps or equirect, at phone resolution (sections 2.8 and 8.2).
- Keep `mean` and `haze` as data (Appendix E).
- The ring profiles can drive the ring shader on their own, even without the ring images (Appendix D).

### 2.8 ShipSphereMesh: the planet sphere as numbers (`ShipCore/ShipSphereMesh.lua`)

**Purpose.** It reproduces `smooth_sphere.mesh` exactly: vertices, triangles and UVs.

- **Landing.** In the landing scene the planet is ×470. The flat facets then sag up to 0.24 % of R below the ideal sphere (over 2 km on Earth), so the ground must be the real facet (`ShipSurface`).
- **Terrain colour.** `uvAt(direction)` lets the terrain read the planet image at the landing site (`ShipSurfaceTerrain.lua:1618`).

**Data format.**

- `RADIUS = 85.628151`.
- `POS` (`:28`): 12 base64 characters per vertex, three 24-bit values giving the direction (`v/16777215·2 − 1`), normalised and then scaled to RADIUS.
- `UV` (`:30`): 4 characters per vertex, two 12-bit values (`/4095`).
- `TRI` (`:32`): 6 characters per triangle, three 12-bit vertex indices (0-based), wound counter-clockwise seen from outside.
- Base64 alphabet: `A–Z a–z 0–9 + /`. Values are read big-endian (`v = v·64 + digit`).
- Decoded (computed): 1652 vertices, 3072 triangles.

**Algorithms.**

- **`build()`** (`:69`), done once and lazily:
  - points, uvs and corner indices;
  - per triangle: the normal `normalize((b−a)×(c−a))`, `offset = n·a`, and the edge planes through the centre `a×b`, `b×c`, `c×a`;
  - triangles are bucketed by the cube face of the centroid `a+b+c` (`faceOf`: the dominant axis and its sign → 1..6 = +X −X +Y −Y +Z −Z).
- **`locate(u)`** (`:146`): normalise `u` (zero vector → +Y).
  - Try the last hit, then the bucket of `faceOf(u)`, then every triangle, using `inside = u·edge ≥ −1e−4` for all three edges.
  - If no triangle matches, take the triangle with the largest `normal·u`.
  - Return `(k, u·(offset/max(n·u, 1e−6)), n)`.
- **`surfaceFactor(u)`** (`:193`): `|point| / RADIUS`, which is 1 at the vertices and ≈ 0.9976 at facet centres.
- **`uvAt(u)`** (`:201`): the barycentric UV inside the located triangle (`wa`, `wb` from the cross products, `wc = 1 − wa − wb`).

**The cube-cross layout** (computed by decoding the data; the per-face fit residual is 1–3 % of UV, so **use the exact mesh data, not a formula**):

- The faces sit in one vertical strip at u ≈ 0.5: −Z (v ≈ 0.12), −X (v ≈ 0.375), +Z (v ≈ 0.63), +X (v ≈ 0.875).
- −Y sits left of −X (u ≈ 0.24) and +Y right of it (u ≈ 0.76).
- The face boxes are not identical squares; for example −X spans u 0.333–0.667 and +X spans u 0.375–0.625.

**Port.**

- A desktop tool (`tools/` or the `:desktop` module) decodes this mesh and converts each 4096² cube-cross PNG into a cube map. For each cube-map texel direction: `uvAt(dir)`, then a bilinear sample.
- After that, neither the runtime nor the terrain area ever needs the cross again: they sample the cube map by direction.
- Also keep `ShipSphereMesh` as a Kotlin unit-tested utility, so the conversion can be checked against Roblox.

### 2.9 ShipOrbits: moving the bodies (`ShipClient/ShipOrbits.lua`)

**Purpose.** It moves planets around the star and moons around their planet. The hierarchy *is* the orbit tree (comment `:5`).

**Constants:**

- `EPOCH = 1767225600` (2026-01-01 00:00 UTC);
- `SLEW = 0.5` s per s;
- `RESYNC = 120` s.

**API.**

| Function | Line | Behaviour |
|---|---|---|
| `ShipOrbits.sharedClock()` | `:43` | `workspace:GetServerTimeNow() − EPOCH` (the same for all players) |
| `ShipOrbits.restPosition(body)` | `:53` | The universe position at build time (see below) |
| `new(root?)` | `:73` | `clock = 0`, `enabled = true`, `shared = false` |
| `update(dt)` | `:155` | Advances the clock (with slew) and calls `place` |
| `place(dt)` | `:174` | Puts every body at its position for `clock` |
| `setRoot(root)` | `:197` | Called by `buildSystem` |
| `setShared(bool)` | `:203` | True except on the private training server |
| `snap()` | `:212` | After a build: `clock = sharedClock()` (when shared), then `place(0)` |
| `setEnabled(bool)` | `:219` | |

**Position on an orbit** (`positionOnOrbit`, `:106`). Compute it in 64-bit; only the result becomes a Vector3:

```
angle = (OrbitPhase + clock / OrbitPeriod)·2π
x = cos(angle)·OrbitRadius;  z = sin(angle)·OrbitRadius
t = rad(OrbitTilt);  y = −z·sin t;  z = z·cos t            -- circle in XZ, then rotated about +X by the tilt
return centre + (x, y, z)                                   -- centre = the parent's current position
```

- A missing radius or period, or a period ≤ 0, keeps the current position.
- The tilt rotates the circle; it does not add a y offset, which would change the radius (comment `:117`).

**`update(dt)`** (`:155`):

```
clock += dt
if shared: drift = sharedClock() − clock
           clock += |drift| > 120 ? drift : clamp(drift, −0.5·dt, 0.5·dt)   -- never jump, catch up ≤ 0.5 s/s
place(dt)
```

**`place`** (`:174`):

1. Find the star: the root child Model whose `Body` has `IsStar`. `centre` is its position, which never moves except through origin shifts.
2. For each other root Model, call `updateModel(model, centre)`. It:
   - sets `target = TerrainLocked ? current : positionOnOrbit(body, centre, clock)`;
   - moves *all* descendant parts of the planet model by `target − current`, including its moons, rings, shells and night shade;
   - then recurses into child Models (the moons) with `centre = the planet's new position`.

**`restPosition(body)`** (`:53`):

```
x = y = z = 0
walk part = body, then its parent Model's parent's "Body" (moon → planet → stop at the star):
    if part has OrbitRadius and OrbitPhase: x += cos(2π·phase)·radius; z += sin(2π·phase)·radius
return (x, y, z)                                         -- no tilt, y = 0: exactly where build() put it
```

**`TerrainLocked`.** It freezes a body and its moons while voxel terrain is loaded (comment `:10`). Only `ShipPlanetTerrain` sets it, and that is disabled (`ShipConfig.Terrain.SpherePlanets = false`, `ShipConfig.lua:1019`), so in practice it is never set.

#### 2.9.1 Timing facts (computed)

- At `clock = 2.7e7` s (November 2026), Phobos has made about 1,300 orbits. Float32 would quantise `clock` to 2 s, which is 6.6 km of Phobos motion at S = 3000. **Compute the angle in double**, as `phase + (clock mod period)/period`.
- One Earth day/night at a fixed spot on Earth's surface lasts **one orbit = 16.2 days**, because planets do not spin.

#### 2.9.2 Port

- `Orbits.kt` has the same formulas.
- **Clock:**
  - `clock = (System.currentTimeMillis()/1000.0) − 1767225600` (shared-clock equivalent, so planets are where they would be in Roblox if the phases match). Or use a save-game clock (question Q8).
  - Training uses its own clock starting from 0.
  - Drop the slew and resync (no server).
- **Positions.** Compute them directly instead of moving descendants: `pos[planet] = star + orbit(planet)`, `pos[moon] = pos[planet] + orbit(moon)`.
- **Per-frame deltas.** Also record `delta[i] = pos_i(t) − pos_i(t − dt)` for the carry (section 2.9.4).

#### 2.9.3 Order

Call it every frame **before** gravity and speed limits are computed, and before thrust (`ShipClient.lua:2435`). Every other module then works with fresh positions.

#### 2.9.4 The body reference frame (new in the port)

At S = 3000 with Roblox periods, planets move at 3.9–15 km/s and moons at 2.7–7.2 km/s. Roblox never needed a body frame:

- The orbital speeds were 1–5 studs/s.
- The landing scene used its own frame.
- `TerrainLocked` existed for voxel planets.

The port must carry the ship with the body it is near, exactly like `ShipStation:getCarry`:

```
frameBody = innermost body whose frame radius contains the ship
            (frame radius = Rc·(1 + MassShadowFactor), with 1.25 hysteresis; the station frame has priority)
ship.pos += delta[frameBody]            -- before gravity/thrust, every frame
```

**Rules:**

- The velocity stays relative to the frame; there is no velocity jump.
- When the ship leaves the frame it simply stops being carried. At 5 R and beyond, the 11 km/s drift of a planet is visually negligible, and cruise speeds there are ≥ 100 km/s.
- A moon frame (Phobos at 3.2 R_Mars) nests inside its planet frame, and its delta already includes the planet's motion.

### 2.10 ShipImpostors: far bodies as scaled copies (`ShipClient/ShipImpostors.lua`) · **not ported**

**The Roblox problem.** Roblox draws parts only up to a view distance set by the graphics level. Measured values (comment `:5`):

| Graphics level | Approx. view distance (studs) |
|---|---|
| 1 | 350 |
| 6 | 550 |
| 9 | 650 |
| 11 | 1,200 |
| 12 | 1,700 |
| 15 | 3,300 |
| 18 | over 9,000 |
| 21 | over 32,000 |

Jupiter was invisible from its own refinery.

**The workaround.** Each far body gets a copy on the same line of sight, shrunk about the eye. The copy keeps the angle and the direction, sits just inside the view limit, and its depth grows logarithmically with the true distance. Hulls, rings and clouds of the real body are hidden locally (`LocalTransparencyModifier`). The real body stays, because gravity, targets and orbits read it.

The algorithm is kept here for completeness (`update`, `:562`):

```
L = (SightOverride or measured sight)·Safety; handover = L·NearFraction; nearest = L·MinFraction
farthest = L·FarFraction; step = L·LogStep
clear = LightClearance + |shipPos − eye|; nearest, handover, farthest = max(…, clear)   -- ship lamps (60 studs) must not light a copy
for each body entry (rescanned every RescanInterval s; copies of all parts except moons):
    distance = |body − eye|; height = distance − shell (shell = max(sphere radii at the centre, SurfaceRadius))
    active  = height > handover·(1 ∓ Hysteresis)                 -- − when already active
    coreAngle = asin(core/distance); reachAngle = asin(reach/distance); tangent = sqrt(d² − core²)
    if active:
        scale = min(handover + step·ln(height/handover), farthest)/height
        if hasRings (reach > 1.2·shell): scale = min(scale, farthest/(distance + reach))
        scale = clamp(scale, nearest/height, 1)
        guard = stationGuard(): stations in front of the body's disc → (dist + extent)·GuardMargin
        if guard > 0: scale = clamp(max(scale, min(guard, L)/height), nearest/height, 1)
occlusion, 2 passes, for each active entry against every other:
    skip if entry.distance <= other.tangent
    sep = angle between directions
    if sep < other.coreAngle + entry.reachAngle:
        if sep + entry.reachAngle <= other.coreAngle: hidden
        else behind = max(behind, other.tangent·other.scale); centreCovered |= sep < other.coreAngle
    if not hidden and behind > 0:
        depth = behind·1.02; s = depth / max(distance − reach, 1)
        fits = depth <= farthest and (no rings or s <= farthest/(distance + reach))
        if fits: scale = max(scale, s) elif centreCovered: hidden
place each copy: CFrame = rotation + (eye + (pos − eye)·scale); mesh scale ×scale; BulkMoveTo
```

**The sight probe** (`measure`, `:814`), run as a coroutine with one yield per frame:

- **The sphere.** A 97 %-transparent sphere is shown for 2 frames and hidden for 2.
- **The test.** "Drawn" means `Stats.SceneTriangleCount` rose by the sphere's triangle count. Calibration happens at 60 studs (two quiet pairs within 20 %), and noisy rounds are rejected (`ProbeQuiet`, `ProbeDrawnMin/Max`).
- **The search.** A binary search runs over the ladder `120·1.25^k ≤ 48000`.
- **The aim.** 16 directions are tried (`PROBE_AIMS`, `:142`). A direction is skipped if a body blocks it or it is not safely on screen (`inView` at 85 %).
- **The result.** `record` keeps the last 2 results; the sight is their *minimum*, and anything below 280 is discarded. On the ground there is a separate list (`getSurfaceSight`: the *maximum* of the space list; otherwise two agreeing ground probes; otherwise `Surface.UnmeasuredSight` = 12000).
- **The schedule.** Probing is allowed only in free flight (`docking FREE`, not in a warp, `atmosphere == SPACE`). It starts 1 s after start and repeats every 6 s, or every 1 s after a failure.

**Port.**

- **Nothing here is needed.** `BodyRenderer` ray-casts true-scale bodies at any distance.
- **One lesson stays.** Sorting bodies by centre distance is wrong when a small near body sits just behind a big body's limb (the "moon shows through Earth" bug, 2026-11-02). The Kotlin renderer sorts by centre distance and writes no depth. Add an occlusion test:
  - Each body pass gets the 1–2 nearest bigger spheres as occluders.
  - The fragment shader discards pixels whose ray hits an occluder first.
  - Alternatively, use the CPU rule above (`hidden` when the disc is fully behind the occluder's limb).
- **The debug hook `SightOverride`** (`:112`, a player attribute) has no equivalent.

### 2.11 ShipStarLight: aiming Roblox's global light at the star (`ShipClient/ShipStarLight.lua`) · **replaced**

**The Roblox problem.**

- The sun direction can only be set through `Lighting.ClockTime` and `GeographicLatitude`.
- Light can never come from below.

**The workaround.** A lookup table is built once, then searched for the best match (`buildTable`, `:66`).

```
for clock in 0, 0.2, …, 23.8 (ClockStep):  for lat in −88, −85, …, 86 (LatitudeStep 3):
    set ClockTime/GeographicLatitude; dir = Lighting:GetSunDirection()
    keep {dir, clock, lat} if MinElevation (0.40) < dir.y < MaxElevation (0.85)
restore the original ClockTime/latitude
```

`update(dt, shipPos)` (`:86`) runs every `UpdateInterval` = 0.2 s, and only while not in the landing scene (`ShipClient.lua:2981`):

```
star = first tagged part with IsStar inside workspace (searched again if lost: warp or landing)
want = normalize(star − ship)
if want.y < 0.40: want = normalize(normalize((want.x, 0, want.z)) + (0, 0.55, 0))   -- lift: light cannot come from below
wantFlat = normalize(want.x, 0, want.z)
best = argmax over entries of  dot(normalize(dir.xz), wantFlat)·1.0 − |dir.y − want.y|·0.12
       -- azimuth first: a sideways error shows at the terminator, a height error does not
if best changed: apply its ClockTime/latitude
updateShades()
```

The measured average error is 1.5°. The high elevation band is chosen because a low Roblox sun turns orange, and it also hits the azimuth better (0.15° instead of 0.30°).

**`updateShades()`** (`:200`) uses `light = Lighting:GetSunDirection()`, **the same direction for every body**. Far planets therefore show the phase *as seen from the ship*.

- **Ramp mode:** re-aim with `towardLight` if `dot(shade.X, light) < cos 0.5°`, or if the shade is more than 0.5 from the body.
- **Offset mode:** `want = body − light·ShadeOffset`; move it if it is off by more than 2 % of the offset.

**Port.**

- Use a point light at the star.
- Per body: `L = normalize(star − body)`. This already exists as `BodyRenderer.sunFor`, `core/src/main/kotlin/com/starwake/core/gfx/BodyRenderer.kt:284`.
- Per ship: `Game.updateLighting` computes the direction to the star with eclipses (`StarSystem.sunlight`).
- No table, no lift, no night spheres.
- **Deviations from Roblox** (decide in question Q14):
  - Every body shows its *true* phase, where Roblox shows the ship's phase.
  - Kotlin dims the light with distance (`(refDistance/d)^0.35`, clamped 0.7–1.45). Roblox is constant.

### 2.12 ShipRingDebris: rocks in the ring plane (`ShipClient/ShipRingDebris.lua`) · **port 1:1**

**Purpose.**

- From afar a ring is an image; seen edge-on from inside it is only a line. Near the ring, individual rocks therefore appear in a field that wraps around the ship.
- The field moves only within the ring plane, and each rock's height above the plane is fixed. You fly *through* a flat slab instead of carrying a cloud with you.
- Rock density follows the ring's opacity profile.
- Contacts produce heat and a hit sound.

**Constants** (module):

| Name | Value | Meaning |
|---|---|---|
| `ROCK_MESH` | `rbxasset://models/MaterialManager/sphere.mesh`, radius 25 mesh units | Built-in LODs, same cube-cross UV as the planet sphere |
| `FADE_START` | 0.72 | Rocks shrink from 0.72·reach to the field edge |
| `DENSITY_SOFT` | 0.12 | Soft band at the density threshold |
| `MIN_SHOWN` | 0.03 | Below this fraction of full size a rock is hidden |
| `ICY_LUMINANCE` | 0.62 | Ring luminance above this counts as ice (Moon textures only) |
| `MAX_HIT_HEAT` | 0.25 | Heat per contact at ≥ `Thrust.MaxSpeed` (`Rings.MaxHeatPerHit` overrides) |
| `CONTACT_RELEASE` | 1.25 | A contact ends at `HitRadius·1.25` |
| field thickness | `FieldSize·0.12` = 168 | |
| RNG seed | 84213 | The layout is the same for every player |

**Building the field** (`new`, `:114`). There are `DebrisCount` rocks (280, or 110 in performance mode); for each, `rng = Random(84213)` is drawn in this order:

```
radius = (MinSize + (MaxSize − MinSize)·rng:num()^2.2)·0.5                       -- many small, few large (diameters 3..26)
radii  = (radius, radius·rng:num(0.5, 0.85), radius·rng:num(0.7, 1.15))          -- squashed sphere
offset = (rng:num(−0.5, 0.5)·FieldSize, (rng:num() + rng:num() − 1)·thickness·0.5, rng:num(−0.5, 0.5)·FieldSize)
         -- x, z: position in the plane relative to the ship (wrapped); y: fixed height above the plane (triangular, denser in the middle)
spin   = normalize(rng:num(−1,1), rng:num(−1,1), rng:num(−1,1))·rng:num(0.2, 1.2)  -- rad/s per axis
pose   = Angles(rng:num(0, 2π), rng:num(0, 2π), rng:num(0, 2π))
shade  = rng:num(0.7, 1.08)
threshold = rng:num()
```

Each rock is a `Part` (`Fabric`, colour white) with a `FileMesh`, `Scale = radii/25`, and starts hidden.

**Applying a ring's look** (`setRing(body)`, `:209`). This runs when the ringed body or its image changes.

```
colour = RingColour attribute (default Rings.Color RGB 150,136,118)
lum = 0.3R + 0.59G + 0.11B
textures = VisualAssets.rockTextures(lum > 0.62)           -- the 1K Moon (+ Rocky Body) class images
top = max(R, G, B, 0.05); tint = (R, G, B)/top · clamp(lum·1.3, 0.35, 1.05)
rock i: TextureId = textures[(i % #textures) + 1]; VertexColor = tint·shade_i   (no textures: plain part colour)
profile = VisualAssets.ringProfile(RingImage); density = clamp(RingDensity, 0.1, 1) (default 0.6)
```

`densityAt(f)` (`:247`), where `f = radial distance / outer radius` and `n = 128`:

```
if no profile: return density
x = f·n + 0.5; step = floor(x); t = x − step
a = profile[clamp(step, 1, n)]; b = profile[clamp(step + 1, 1, n)]     -- 1-based: value i sits at f = (i − 0.5)/n
return a + (b − a)·t
```

**Every frame** (`update(dt, shipPos, velocity)`, `:284`, called at `ShipClient.lua:3065`). It returns `(hits, heat)`.

```
clock += dt
body = nearest tagged part with RingOuter (by centre distance); none → hideAll, return 0, 0
inner, outer = RingInner, RingOuter
if dist > outer + SpawnRange or dist < inner − SpawnRange: hideAll, return 0, 0
normal = RingNormal (default +Y); frame = ringFrame(normal)
         -- helper = |n.x| < 0.9 ? X : Z; right = normalize(helper − n·dot(helper, n)); frame = fromMatrix(0, right, n): local Y = normal
reach = FieldSize/2 (700); thickness = 168
toShip = ship − body; h = dot(toShip, normal); planar = frame⁻¹·(toShip − normal·h); r = |planar|
if |h| > reach + thickness/2 or r > outer + reach or r < inner − reach: hideAll, return 0, 0
if body or image changed: setRing(body)
lastH = lastHeight or h; lastHeight = h
shift = frame⁻¹·(velocity·dt)                                   -- motion in ring coordinates (y = across the plane)
hitR = HitRadius (26); release = 1.25·hitR
f = clamp(|velocity| / Thrust.MaxSpeed (1000), 0, 1)
heatPerHit = HeatPerHit + (MaxHeat − HeatPerHit)·f²             -- 0.012 when slow … 0.25 at ≥ 1000 m/s
hits = 0; fadeStart = 0.72·reach
for each rock i with stored offset p:
    mx = p.x − shift.x; mz = p.z − shift.z
    before = (p.x, p.y − lastH, p.z); after = (mx, p.y − h, mz)  -- relative to the ship, before wrapping
    p' = (wrap(mx, FieldSize), p.y, wrap(mz, FieldSize))         -- wrap(v, s) = ((v + s/2) mod s) − s/2
    rel = (p'.x, p.y − h, p'.z)
    radial = |(planar.x + p'.x, planar.z + p'.z)|
    shown = 0
    if inner ≤ radial ≤ outer:
        dens = min(densityAt(radial/outer)·1.1, 1)
        band = clamp((dens − threshold_i)/0.12, 0, 1)
        shown = band·(1 − smoothstep(fadeStart, reach, |rel|))
    if shown < 0.03: hide (only if it was shown); contact_i = false
    else:
        if |shown − last_i| > 0.02 or (shown == 1 and last_i ≠ 1): mesh.Scale = full_i·shown; show
        CFrame = T(ship + frame·rel)·pose_i·Angles(spin_i·clock)
        if not contact_i: if shown > 0.5 and sweptDistance(before, after) < hitR: contact_i = true; hits += 1
        elif |rel| > release: contact_i = false
return hits, hits·heatPerHit
```

`sweptDistance(a, b)` (`:104`) is the closest distance of segment a→b to the origin: `t = clamp(−a·d/|d|², 0, 1)`, `|a + d·t|`. It keeps fast ships from tunnelling past rocks.

**What the caller does** (`ShipClient.lua:3066`):

- If `hits > 0`: `audio:playHit(0.3 + clamp(speed/400, 0, 1.1), Rings.HitCooldownSeconds = 1.0)`.
- If `heat > 0`: `systems:addHeat(heat)`. Heat runs 0..2.2; 1.0 is the overheat warning and 2.0 is lethal (`ShipConfig.Systems`).

**Edge cases.**

- The debris uses `RingInner` from the data. The *image's* visible inner edge comes from the profile, and for generated rings this can differ slightly: the generic images start at f = 0.59–0.68 while the data gives 0.54–0.79.
- The hit counting is frame-rate independent, because heat is added once per contact.
- Rocks hidden at the field edge do not count as hits.

**Port and S.**

- The field is ship-local and absolute: the field size, sizes, `HitRadius` and `SpawnRange` stay in metres. Only `inner` and `outer` scale with the planet.
- Draw the rocks as one instanced call: an icosphere or cube-sphere LOD mesh, with per-instance matrix, scale and tint. Use one texture array of the 1K class images.
- Zero allocation: preallocated arrays, as in the Lua.

### 2.13 ShipPlanetTexture: runtime planet textures (`ShipClient/ShipPlanetTexture.lua`) · **port as the fallback generator plus streaming policy**

**Status in Roblox.** The scan **skips every body that has an uploaded image** (attribute `TextureAsset`, `:830`), and all Sol bodies and all class pools have one. So this module only runs when `smooth_sphere.mesh` fails to load (no `TextureAsset` is set then). It is the fallback look. Its *policy* is still the right one for the port's texture streaming:

- texture what is big in the sky;
- at most 3 at a time;
- choose the resolution by angular size;
- only upgrade, never downgrade;
- keep the old texture until the new one is ready.

**Policy** (`update`, `:772`):

```
clock += dt
run jobs: resume each entry's coroutine (1 texture row per resume) until FrameBudget (4 ms; 3 ms in performance mode) is used
follow: shell (and composite patches) to the body position; clouds rotated by clock·spin about Y
every ScanInterval (0.75 s), unless blocked (RetryDelay 20 s after an Editable* budget failure) or the sphere template is missing:
    candidates = tagged bodies, not stars, no TextureAsset, distance > radius, canTexture(radius)
    angle = deg(2·atan(R/distance)); keep if angle ≥ MinAngularSize (0.5°)
    sort by angle descending; keep the first MaxTextured (3); release the rest
    for kept: wanted = resolutionFor(angle)   -- ≥ 8° → 1024, ≥ 2.5° → 512, else 256 (width; height = width/2)
              no entry → begin(part, wanted)
              wanted > entry.resolution and no job running → begin(part, wanted, previous = entry)   -- never downgrade
canTexture(R) = R ≤ MaxTexturedRadius (1024) or (CompositeEnabled (false) and R ≤ MaxCompositeRadius (2600) and the patches are built)
```

**Generation** (`begin`, `:466`; `writeSurfaceRow`, `:372`; `writeCloudRow`, `:433`). This is an equirect image: row y has `v = y/(h−1)`, `lat = (v − 0.5)·π`, so row 0 is the south pole.

```
rng = Random(Seed); offset = (rng:num(−400,400), rng:num(−400,400), rng:num(−400,400))
spin = rng:num(CloudSpinMin 0.004, CloudSpinMax 0.014)·(rng:num() < 0.5 ? −1 : 1)       -- rad/s
palette = Palettes.get(class); colours = Palettes.colours(class); water = palette.water; sea = SeaLevel (−0.02)
fbm(p, oct) = Σ noise(p·freq)·amp / Σ amp, with amp ×0.5 and freq ×2.07 per octave (not 2.0: avoids repetition)
for each pixel: u = x/w; lon = 2π·u; s = (cos lon·cos lat, sin lat, sin lon·cos lat)   -- evaluated on the sphere: no seam, no polar swirl
    n = banded (Gas Giant) ? fbm((s.x·0.6, s.y·6, s.z·0.6) + offset, Octaves 5) : fbm(s·2.2 + offset, 5)
    if water and n < sea: colour = mix(shallow, deep, clamp((sea − n)·4, 0, 1))
    else: t = water ? clamp((n − sea)/(1 − sea), 0, 1) : clamp(n·0.5 + 0.5, 0, 1)
          colour = t < 0.10 ? shore : t < 0.45 ? mix(shore, low, (t−0.10)/0.35)
                 : t < 0.78 ? mix(low, high, (t−0.45)/0.33) : mix(high, peak, (t−0.78)/0.22)
    ice = |v − 0.5|·2 − iceLatitude + n·0.12; if ice > 0: colour = mix(colour, iceColour, clamp(ice·8, 0, 1))
    RGBA8 = (colour·255, 255)
clouds (Clouds = true, HasAtmosphere, resolution ≥ 512, R ≤ 1024, and NO NightRamp → never while NightRamp exists):
    n = fbm(s·3.1 + offset·1.7, 4); alpha = clamp((n − CloudCoverage (−0.02))·CloudSharpness (4.5), 0, 1); colour RGB 250,252,255
```

`noise` is Roblox `math.noise` (3-D Perlin gradient noise, roughly ±1 but mostly within ±0.5; the exact implementation is unverified).

**Display.**

- The texture goes on a **UV sphere** (`buildUvSphere`, `:76`: 48 segments × 24 rings, `uv = (s/segments, 1 − r/rings)`, wound a, c, b / a, d, c so Roblox culls the back faces correctly). It is baked once into a `MeshPart` template and cloned.
- The body sphere is hidden locally.
- The cloud shell is the same sphere at `R·1.004`, `Transparency 0.1`.
- Bodies larger than 1024 studs (MeshPart limit) would need 8×4 composite patches (disabled; "33 live meshes broke the memory budget").

**Port.**

- **Fallback textures.** Implement the generator as a GLSL bake: equirect or cube map, rendered in strips like today's `BodyRenderer.bake`, with Perlin noise. This covers bodies whose images are missing and gives a look close to the Roblox fallback.
- **Streaming.** Reuse the policy for all body textures (uploaded or generated):
  - For the top-N by angular size: a high-resolution cube map (e.g. 1024² per face).
  - Others: 128–256 per face.
  - Stars and sub-pixel bodies: the colour only.
  - Load and decode on a worker thread; upload ≤ 1 face per frame.
- **Drop** the UV-sphere and patch machinery and the Editable* budgets.

### 2.14 ShipGalaxy (client): the galaxy store as seen by the client (`ShipClient/ShipGalaxy.lua`)

**State.**

- `records[System] = view`
- `offset` = server `os.time()` minus local `os.time()`
- `listeners`
- `fetching[index]`
- `connection` to `GalaxyChanged`

**API.**

| Function | Line | Behaviour |
|---|---|---|
| `new(profile)` | `:25` | Subscribes to the RemoteEvent `GalaxyChanged(index, view, now)` → `apply` |
| `now()` | `:51` | `os.time() + offset` (server time: build times, storage) |
| `fetch(index, timeout = 8)` | `:58` | Starts at most one `GetSystem` call per index and waits up to `timeout` s (0 = do not wait); a late answer is still applied. Returns `records[index]` or nil. |
| `apply(index, view, now?)` | `:78` | `offset = now − os.time()`; stores the view; `ShipStations.setPlayerStations(index, ShipResources.stationDefs(view, index, now()))`; spawns every listener `(index, view, old)` |
| `onChanged(cb)` | `:91` | |
| `get(index)` | `:95` | |
| `getBody(index, name)` | `:100` | `{Discovered = {By, UserId, Time}?, Deposits?}` (deposits only after discovery) |
| `getStation(index, id)` | `:105` | |
| `getSites(index)` | `:111` | Records not yet operational at `now()` (`ShipResources.isOperational`), sorted by `Id` |
| `refreshStations(index)` | `:130` | Re-derives the finished player stations; if the count changed, `setPlayerStations` and returns true. Called by the client when a site finishes (`ShipClient.lua:3177`). |
| `destroy()` | `:144` | |

**Call sites** (`ShipClient.lua`):

- `galaxy:fetch(index, 8)` at start (`:656`), `fetch(index, 6)` on a jump (`:875`), and `fetch(1, 0)` on the build-failure fallback (`:906`).
- Each is followed by `stations:build` and `stations:sync(index, getSites(index), now())`.
- `onChanged` (`:676`) re-syncs stations and sites for the current system, and shows the FIRST VISIT toast (section 5.2).

### 2.15 ShipGalaxy (server): the shared galaxy store (`ShipServer/ShipGalaxy.lua`) · **becomes local**

**Storage.**

- The DataStore is `SpaceEngine_Galaxy_v1`, or `SpaceEngine_Galaxy_Studio_v1` in Studio.
- The key is `"system_<index>"`.
- Without API access, a memory-only store is used.

**The record** (comment `:8`, `fresh` `:131`):

```
{ Version = 1, System = index, Name = ShipStations.getSystemName(index),
  FirstVisit = {By, UserId, Time}?,
  Bodies   = { [bodyName] = { Deposits = {{Id, Grade}, …}, Discovered = {By, UserId, Time}? } },
  Stations = { [id] = { Id, Name, Owner, OwnerId, Host, Economy, Slot, Angle, Elevation, Distance, Founded, Stage,
                        Delivered, BuildEnds, Operational, OperationalAt, Storage, StorageAt } },
  Updated  = os.time() of the last write }
```

**Freezing** (`complete`, `:144`):

- Every body of `ShipStations.getBodies(index)` without an entry gets `{Deposits = ShipResources.roll(index, name, body)}`.
- In Sol, every body gets `Discovered = {By = "SOL REGISTRY", UserId = 0, Time = 0}`, and the record gets `FirstVisit` with the same values.
- From then on the record never changes when the roll tables change ("frozen").

**API.**

| Function | Line | Behaviour |
|---|---|---|
| `ensure(index)` | `:370` | Validates `1 ≤ index ≤ 180`. Uses the cache if younger than `REFRESH` (120 s); otherwise reads it, and on the very first read (or with new bodies) `transact` completes and writes it. |
| `get`, `getBody` | `:408`, `:413` | Cache only |
| `update(index, transform)` | `:421` | `transact` (UpdateAsync on the current stored state, `ATTEMPTS = 3`, at most one write per key at a time), then `accept` (cache, `syncStations`, push to clients) and `publish` (MessagingService topic `GalaxyV1`) |
| `visit(player, index)` | `:434` | Sets `FirstVisit = {By = player.Name, UserId, Time = os.time()}` if missing. Returns true if this player was the first. Called on `ReportJump` (`ShipCommerce.lua:513`). |
| `discover(player, index, bodyName)` | `:451` | Same idea for `Bodies[name].Discovered`. Returns `(first, discovered)`. Called by the survey sale (`ShipCommerce.lua:446`): a first discovery outside Sol pays `×SurveyFirstBonus`. |
| `view(record, player?)` | `:479` | A copy without `Deposits` for undiscovered bodies, and without `Storage` for stations not owned by the player |
| `push(index)` | `:313` | Sends `GalaxyChanged(index, view, now)` to the audience: players in the system and owners of a station there |
| `init(shipData, commerce)` | `:510` | `GetSystem` remote (cooldown 1 s, burst 4; failure → `"GALAXY DATA UNAVAILABLE"`); MessagingService subscription; a loop every `TICK` = 15 s |

The 15-second loop:

- `ShipResources.advance(station, now)` for stations whose `BuildEnds` has passed.
- `syncStations` for newly finished stations.
- Re-read records older than `REFRESH` while a player is in that system.

**Port (singleplayer).**

- `GalaxyStore.kt` with the same record and the same functions, as synchronous local calls.
- **Persistence:** `galaxy.json`, written atomically (temp file plus rename) on a background thread, debounced by about 2 s after a change.
- `UserId` = 1 for the local pilot (0 stays SOL REGISTRY). `By` = the pilot name (default "PILOT").
- `now()` = `System.currentTimeMillis()/1000`. Build timers keep running while the app is closed, as on Roblox servers.
- The listeners are called synchronously after a change. The tick runs every 15 s while the game runs, plus once on load.
- **Drop** the MessagingService, the audience, the views (one player sees everything they own), the retries and the Studio store.

### 2.16 Integration in ShipClient (`ShipClient.lua`)

**Construction** (`:262–312`):

- `orbits = ShipOrbits.new(nil)` and `setShared(not trainingServer)`.
- `starLight = ShipStarLight.new()` (builds its table).
- `ringDebris = ShipRingDebris.new(camera)`.
- `planetTexture = ShipPlanetTexture.new()` (starts the sphere template).
- `impostors = ShipImpostors.new()`.
- `galaxy = ShipGalaxy.new(profile)` (`:238`).
- A background check that `smooth_sphere.mesh` loads (`:203`); otherwise `smoothSpheres = false`.

**`buildSystem(definition)`** (`:561`):

```
planetTerrain:clear(); planetTexture:releaseAll(); impostors:clear(); traffic:clear()
systemFolder:ClearAllChildren(); ShipOrigin.reset()               -- build in universe coordinates
root = ShipSystemBuilder.build(systemFolder, definition)
orbits:setRoot(root); orbits:snap()                               -- at the shared-clock position before stations and ship
applySkybox(definition)                                           -- skybox(definition.Seed) or the place's sky
```

**`definitionFor(index)`** (`:581`): Sol for index 1 (or the home entry), otherwise `makeProcedural(target.seed, target.planets, target.colour, target.class)`.

**`arrivalPoint(definition)`** (`:596`):

```
danger = max(Star.heatRadius or 1.6·R, Star.massShadowRadius or 4·R)
safe   = danger·Warp.ArrivalMargin (1.15)
return (0, safe·0.06, −safe)          -- Sol: (0, 6,348, −105,800)
```

**`clearOfBodies(position)`** (`:608`): for every body, if `|p − body| < 1.5·Rc`, move the point to `body + outward·3·Rc`.

**The start pose** (`:772`):

1. Docked at the saved station and pad, if that pad is free.
2. Otherwise in front of the saved station: its `Outer` approach point (local (0, 0, −1270)), looking at the station.
3. Otherwise, if a profile was loaded (the pilot left in space, landed or mid-jump), the ship starts at the star arrival point, looking at the star.
4. Without a profile (no server answer), it starts at `spaceSpawnPose()`, cleared of bodies:
   - in Sol, `Ship.StartPosition` = (0, 5000, −900,000), looking −Z, "just outside Earth's orbit";
   - in other systems, the arrival point, facing away from the star.

The respawn after a crash in space uses the same `spaceSpawn`.

**Jump arrival** (`arriveInSystem`, `:853`):

- `docking:reset()`, `buildSystem`, `starMap:setCurrentSystem`, `profile:fire("ReportJump", index)`.
- `galaxy:fetch(index, 6)`, `stations:build`, `stations:sync`.
- Then `rotation = lookAt(arrival, star)` and `spaceSpawn = lookAt(arrival, arrival + arrival.unit)` (respawn faces away from the star).
- `thrust:reset()`, the lock is cleared, and the autopilot goes to STANDBY.
- On any error the client falls back to Sol (`:897`). The tunnel waits for `markBuildDone`.

**Per-frame order** (`RunService:BindToRenderStep("ShipUpdate", Camera priority)`, `:2321`). `dt = min(dt, 0.1)`.

1. **Decks active:** `impostors:hide()` and return. **Destruction scene:** `impostors:update(…, probeAllowed = false)` and return (`:2275`, `:2328`).
2. Input → rotation (`:2432`).
3. **`orbits:update(dt)`** (`:2435`) → **station carry** (`:2438`) → `gravity:getState` (`:2448`) → landing scene step → thrust and the rest of the flight model.
4. Markers, dust, `planetTerrain:update`.
5. **`starLight:update(dt, shipPos)`** if not in the landing scene (`:2981`).
6. Maps.
7. **`planetTexture:update(dt, shipPos)`** if not in the landing scene (`:3011`).
8. **Impostors:** `impostors:updateSurface` in the landing scene, otherwise `impostors:update(dt, camera, systemFolder, probeAllowed, shipPos, stations)` with `probeAllowed = docking FREE and not travelling and atmosphere SPACE` (`:3020`).
9. Star map, warp, atmosphere.
10. **`ringDebris:update(dt, shipPos, thrust.velocity)`** → hit sound and heat (`:3065`).
11. HUD.

**Debug.** The status block (`:3408`) shows `star = starLight.star:GetFullName()`, `textures = planetTexture:getStatus()` and `impostors = impostors:getStatus()`.

---

## 3. Tunables

**What the "Port" column means:**

| Value | Meaning |
|---|---|
| **×S** | Multiply by S (a world length) |
| **abs** | Keep the value in metres or seconds |
| **drop** | A Roblox workaround with no port equivalent |
| **keep** | Not a length; port unchanged |
| **redesign** | Needs a new rule at the big scale |

### 3.1 `ShipConfig.Impostors` (`ShipConfig.lua:2531`) · all **drop**

All fractions are of `L = measured sight × Safety`.

| Key | Value | Meaning |
|---|---|---|
| `Enabled` | true | |
| `Safety` | 0.9 | Share of the measured sight used (L) |
| `NearFraction` | 0.8 | The copy takes over beyond this distance (× L) from the shell; stations and ships nearer stay in front |
| `LogStep` | 0.03 | Depth gained per e-fold of true distance (× L). Larger keeps moon-before-planet order better but needs more depth. |
| `FarFraction` | 0.97 | No copy farther than this (× L) |
| `MinFraction` | 0.6 | No copy nearer than this (× L), even with rings |
| `LightClearance` | 150 | Minimum copy distance from the ship (+ camera distance). Ship lamps reach 60 and the ship is over 100 long. |
| `GuardMargin` | 1.03 | A station in front pushes the copy behind its far point × this |
| `MinSight` | 280 | The smallest believable measurement; the ladder hits 292 at level 1 |
| `Hysteresis` | 0.03 | Against flicker at the handover |
| `DefaultSight` | 500 | Assumed until the first measurement |
| `FirstProbeDelay` | 1 | s after start (and retry delay after a failure) |
| `ProbeInterval` | 6 | s between measurements |
| `ProbeMin`, `ProbeMax`, `ProbeStep` | 120, 48000, 1.25 | Distance ladder (geometric) |
| `ProbeCalibration` | 60 | Calibration distance (always drawn) |
| `ProbeMinTriangles` | 40 | Fewer triangles: calibration unusable |
| `ProbeTransparency` | 0.97 | Probe sphere while shown |
| `ProbeTries` | 6 | Repeats of a noisy sample |
| `ProbeQuiet` | 0.3 | Allowed baseline drift (share of the probe's triangles); also the "not drawn" band |
| `ProbeDrawnMin`, `ProbeDrawnMax` | 0.6, 1.4 | Band (share) that counts as "drawn" |
| `ProbeYaw`, `ProbePitch` | 15, 12 | Probe aim off the screen centre (degrees), × the `PROBE_AIMS` multipliers |
| `RescanInterval` | 2 | s between body rescans |

### 3.2 `ShipConfig.Rings` (`ShipConfig.lua:2585`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `DebrisCount` | 280 (`Performance.Low`: 110) | Rocks in the field | keep (quality-scaled) |
| `SpawnRange` | 2600 | The field is active within the ring band ± this (centre-distance test) | abs |
| `FieldSize` | 1400 | Edge of the wrapped square around the ship; thickness = 0.12·FieldSize | abs |
| `MinSize`, `MaxSize` | 3, 26 | Rock diameter range (studs); many small, few large (`u^2.2`) | abs |
| `Color` | RGB 150,136,118 | Default ring colour when the attribute is missing | keep |
| `HitRadius` | 26 | A rock closer than this (swept) is a contact | abs |
| `HitCooldownSeconds` | 1.0 | Minimum time between hit sounds | keep |
| `HeatPerHit` | 0.012 | Heat per contact when slow; up to `MAX_HIT_HEAT` 0.25, quadratic in speed/`Thrust.MaxSpeed` | keep |

### 3.3 `ShipConfig.StarLight` (`ShipConfig.lua:1288`) · all **drop**

| Key | Value | Meaning |
|---|---|---|
| `ClockStep` | 0.2 | Table resolution in hours of `ClockTime` |
| `LatitudeStep` | 3 | Table resolution in degrees of latitude (−88..88) |
| `MinElevation` | 0.40 | Keep table directions with y above this; lift wanted directions below it |
| `TargetElevation` | 0.55 | Replacement elevation when the star is below the ship |
| `MaxElevation` | 0.85 | Upper limit of table directions |
| `UpdateInterval` | 0.2 | s between updates |
| `AzimuthWeight` | 1.0 | Score weight of the compass direction |
| `ElevationWeight` | 0.12 | Score weight of the height error |

### 3.4 `ShipConfig.PlanetTexture` (`ShipConfig.lua:2468`)

| Key | Value | Meaning | Port |
|---|---|---|---|
| `MinAngularSize` | 0.5° | Below this, no texture | keep (streaming) |
| `MaxTexturedRadius` | 1024 | MeshPart limit; larger bodies get none | drop |
| `CompositeEnabled` | false | Patch hull for big bodies | drop |
| `CompositeSectors`, `CompositeBands`, `CompositeSteps` | 8, 4, 4 | Patch grid and subdivision | drop |
| `MaxCompositeRadius` | 2600 | | drop |
| `MaxTextured` | 3 | Bodies textured at once | keep (high-res slots) |
| `ScanInterval` | 0.75 | s between scans | keep |
| `RetryDelay` | 20 | s pause after a memory-budget failure | drop |
| `FrameBudget` | 0.004 s (perf mode 0.003) | Generation time per frame | keep (worker thread instead) |
| `Resolutions` | ≥ 8.0° → 1024, ≥ 2.5° → 512, ≥ 0.5° → 256 (equirect width; height = width/2) | | keep (as cube-face sizes ≈ width/4) |
| `SphereSegments` | 48 (rings = 24) | UV sphere | drop |
| `Octaves` | 5 | fbm octaves | keep |
| `SeaLevel` | −0.02 | Noise value below which water lies | keep |
| `Clouds` | true | Runtime cloud shell (never active while NightRamp exists) | keep (fallback) |
| `CloudMinResolution` | 512 | | keep |
| `CloudShell` | 1.004 | Cloud radius / R. No higher: the offset night sphere is only 1.0042 R at the terminator. | redesign (absolute cloud height) |
| `CloudTransparency` | 0.1 | | keep |
| `CloudCoverage` | −0.02 | Smaller = more clouds | keep |
| `CloudSharpness` | 4.5 | Larger = harder edges | keep |
| `CloudSpinMin`, `CloudSpinMax` | 0.004, 0.014 rad/s | Cloud rotation, random sign | keep |

### 3.5 `ShipConfig.Stations` (`ShipConfig.lua:2906`)

Docking semantics are in `08-stations-economy.md`.

| Key | Value | Meaning | Port |
|---|---|---|---|
| `Tag` | "ShipStation" | Beacon tag (target, radar, navigation) | keep |
| `Model` | "RingStation" | Fallback model | keep |
| `Types.HighTech` | SpireStation, RingStation | Generated station models per economy (`pickModel`) | keep |
| `Types.Service` | SpireStation, RingStation | | keep |
| `Types.Industrial` | FoundryStation, RingStation | | keep |
| `Types.Extraction` | HollowStation, FoundryStation | | keep |
| `Types.Refinery` | RefineryStation | | keep |
| `Types.Agricultural` | GardenStation, RingStation | | keep |
| `LodDistance` | 60000 | Full model within this distance, beacon beyond | abs |
| `DockingRange` | 6000 | Request range and mass lock (no cruise, no jump) | abs |
| `MassLockSpeed` | 260 | Speed limit within `DockingRange` | abs |
| `ZoneRadius` | 2200 | Docking zone; also the reference-frame radius (×1.25 hysteresis) | abs |
| `ZoneSpeed`, `HangarSpeed` | 140, 60 | | abs |
| `GrantDelay`, `GrantSeconds` | 1.6, 600 | Radio delay and permit lifetime (s) | keep |
| `TouchdownRadius`, `TouchdownHeight`, `TouchdownSpeed`, `TouchdownAlign` | 26, 10, 12, 0.94 | Manual pad touchdown | abs |
| `ClampSeconds` | 1.6 | | keep |
| `CrashSpeed` | 45 | Hull impact limit (studs/s) | abs |
| `Computer` | CruiseSpeed 170, MouthSpeed 70, HangarSpeed 34, FinalSpeed 5, Accel 45, TurnTime 0.9, HoverHeight 34, LaunchHeight 26 | Docking computer | abs |
| `PadIdle`, `PadAssigned`, `PadClamped` | RGB 70,120,160 · 255,170,60 · 120,220,150 | Pad light colours | keep |
| `BlinkTransparency` | 0.85 | | keep |

Values from other modules that scale:

- The station `Distance` (data) is **×S**.
- The player-slot distance and `SlotClearance` 2400 are **×S** (section 2.5.5).

### 3.6 `ShipConfig.Scale` (`ShipConfig.lua:2754`)

| Key | Value | Meaning |
|---|---|---|
| `MetresPerStud` | 1 | 1 stud = 1 m, also for speed (m/s). All displays go through `ShipCore/ShipUnits`. |

### 3.7 Values from other sections that this area reads or depends on

| Key | Value | Used for | Port |
|---|---|---|---|
| `StarMap.Seed` | 20260824 | Catalog seed | keep |
| `StarMap.SystemCount` | 180 | Catalog size (also galaxy-store index check) | keep |
| `StarMap.RadiusLightYears` | 60 | Disc radius (ly) | keep |
| `StarMap.Thickness` | 16 | Disc thickness (ly) | keep |
| `Ship.StartPosition` | (0, 5000, −900,000) | Default space spawn "just outside Earth's orbit" | ×S |
| `Ship.StartLookDirection` | (0, 0, −1) | | keep |
| `Ship.FieldRadius` | 9,000,000 | Play-field sphere around the star; generated orbits are compressed to 0.8× this | ×S (27 Gm) |
| `Ship.EdgeSpeed` | 150 | Speed at the field edge | abs |
| `Ship.EdgeMargin` | 2000 | `EdgeSpeed` already this far before the edge | ×S, or drop in the redesign |
| `Ship.PositionFrame` | 2 | Version of the saved position. Positions from older versions are invalid (orbits ×10 on 2026-11-03). | Bump when S changes the saved coordinates |
| `Gravity.Tag` | "CelestialBody" | Body tag | keep |
| `Gravity.MassShadowFactor` | 5 | Planet shadow = Rc·5 above the barrier (moons and stars: attribute 4) | ×S implicitly; **redesign** the inner law |
| `Gravity.MassShadowCurve` | 0.75 | Limit inside the shadow: `MaxSpeed·(alt/shadow)^0.75` | redesign |
| `Gravity.MassShadowFloor` | 150 | Floor = max(150, 0.15·Rc) | redesign |
| `Gravity.StarShadowFromHeat` | 1.0 | Stars brake from `HeatRadius`·this | ×S |
| `Gravity.StarShadowMinSpeed` | 1000 | Floor inside the heat zone | abs |
| `Gravity.DefaultInfluenceFactor` | 2.2 | Approach zone = Rc·2.2 | redesign (absolute band) |
| `Gravity.DefaultMinApproachSpeed` | 10 | Overridden by the attributes 14/12/40 | abs |
| `Gravity.DefaultSurfaceGravity` | 196.2 | Only for the free-fall-time display | keep |
| `Gravity.SurfaceGravityRange` | 3.5 | Pull acts within 3.5 R | keep (relative) |
| `Gravity.GravityFalloff` | 2.0 | Inverse square | keep |
| `Gravity.HeatRange` | 0.6 | Default heat zone = R·0.6 above the surface (stars without `HeatRadius`) | keep (relative) |
| `Thrust.MaxSpeed` | 1000 | Cruise base speed; ring-heat speed reference | abs |
| `Thrust.CruiseMaxSpeed` | 3,000,000 | Cruise cap | **redesign** (Appendix B.4) |
| `Thrust.CruiseDistanceTime`, `CruiseDepartTime` | 3.5, 1.5 s | Cruise e-fold times approaching / departing | keep (or 2.5/1.0, Appendix B.3) |
| `Warp.ArrivalMargin` | 1.15 | Arrival = max(heat, shadow)·1.15 | keep |
| `Surface.SceneScale` | 470 | Landing scene ×470 | drop (seamless) |
| `Surface.UnmeasuredSight`, `Surface.ProbeAgree` | 12000, 0.7 | Ground sight fallback / agreement | drop |
| `Terrain.SpherePlanets` | false | Voxel planets in space; the only setter of `TerrainLocked` | drop |
| `Systems.HeatMax` | 2.2 | Heat scale that receives ring heat (1.0 warning, 2.0 lethal) | keep |

### 3.8 Module constants

| Module | Constant | Value | Port |
|---|---|---|---|
| SystemBuilder | atmosphere shell, haze shell | 1.025 R (t 0.72), 1.08 R (t 0.86), ForceField | redesign (absolute atmosphere height, question Q11) |
| SystemBuilder | corona | coronaScale (default 1.12; Sol and generated 1.35), ForceField, t 0.62 | keep (relative) |
| SystemBuilder | cloud shell | 1.012 R (never used) | drop |
| SystemBuilder | barrier `CollisionRadius` | ceil(1.105·R) + 6 | redesign: no barrier on landable bodies; for giants, see question Q10 |
| SystemBuilder | night shade | offset 0.08 R, edge 3°, ramp shell 1.016 R | drop |
| SystemBuilder | ring segments | height 4, overlap 1.06, Sand | drop |
| SystemBuilder | `RingDensity` | 1 − (transparency or 0.5) | keep |
| SystemBuilder | `SurfaceGravity` | gravityG·196.2 | keep |
| SystemBuilder | `SpeedCurve` | 0.75 | keep |
| SystemBuilder | min approach speeds | star 40, planet 14, moon 12 | abs |
| SystemBuilder | `MassShadowFactor` | star 4, moon 4 | keep |
| SystemBuilder | random tilts | planets U(−1.5°, 1.5°), moons U(−5°, 5°) | keep |
| SystemBuilder | procedural constants | see section 2.2.9 | keep; radii, orbits and spacing ×S |
| Orbits | `EPOCH`, `SLEW`, `RESYNC` | 1767225600, 0.5, 120 | `EPOCH` keep; slew and resync drop |
| StarLight | `SHADE_TOLERANCE`, `RAMP_ALIGN` | 0.02, cos 0.5° | drop |
| RingDebris | see section 2.12 | | keep |
| PlanetTexture | fbm frequency step 2.07, scales 0.6/6/0.6 (banded), 2.2 (surface), 3.1 (clouds); offset ±400; cloud offset ×1.7; ice noise 0.12, ice ramp ×8; ramp knots 0.10/0.45/0.78; depth ×4; cloud colour RGB 250,252,255 | | keep |
| VisualAssets | `SphereMeshRadius` 85.6281, `QuadMeshSize` 1024, `AlphaPass` 0.02 | | drop |
| SphereMesh | `RADIUS` 85.628151, `EDGE_TOLERANCE` 1e−4, 24-bit and 12-bit packing | | keep (converter) |
| Galaxy (server) | `VERSION` 1, `REFRESH` 120 s, `TICK` 15 s, `ATTEMPTS` 3, `TOPIC` "GalaxyV1", store `SpaceEngine_Galaxy_v1` | | `VERSION` keep, `TICK` keep, rest drop |
| Resources | `SlotCount` 8, `SlotClearance` 2400, slot formula (section 2.5.5) | | ×S for the lengths |
| Stations (catalog) | count probabilities 0.35/0.80, economy probabilities, Distance `floor(2.1·R + 2200)`, elevation ±U(35°, 50°), shipyard 0.6 | | Distance ×S |
| StarCatalog | class weights, colours and sizes (section 2.3) | | keep |
| Names | coda 0.75, letter 0.35, numeral 0.5, catalog number 100–9999, proper 0.35 | | keep |

---

## 4. State machines, timings, thresholds, edge cases

### 4.1 System lifecycle

```
START  → definitionFor(saved System) → buildSystem → orbits.snap → galaxy.fetch(8 s max) → stations.build/sync
       → place ship (docked pad | station Outer point | star arrival | StartPosition)
JUMP   → (warp area: alignment, countdown 5 s, tunnel ≥ 6 s, ≤ 20 s) → arriveInSystem in the tunnel:
           docking.reset → buildSystem → ReportJump (server: visit) → galaxy.fetch(6 s) → stations.build/sync
           → ship at clearOfBodies(arrivalPoint), facing the star → thrust.reset, lock cleared, autopilot STANDBY
           → markBuildDone (any error: rebuild Sol at StartPosition, then markBuildDone)
CRASH  → respawn at spaceSpawn (current system; after a jump: the arrival point facing away from the star)
```

Jumping is only allowed from free space:

- not in the landing scene;
- atmosphere state `SPACE`;
- not mass-locked by a station (≤ 6000 m; toast `JUMP BLOCKED` / `STATION MASS LOCK  ·  FLY 6 KM AWAY`);
- not while docking.

A planned jump that enters an atmosphere is cancelled (`ShipClient.lua:3037`).

### 4.2 Orbit clock

| State | Rule |
|---|---|
| Shared (normal servers) | `clock` follows server time at 1 ± 0.5 s/s; a jump only if it is off by more than 120 s |
| Local (training) | `clock` starts at 0 and advances by `dt` |
| After a build | `snap()`: shared → `clock = sharedClock()`, then place everything |
| Frozen body | `TerrainLocked` holds the body and its moons in place (unused, section 2.9) |

`dt` is capped at 0.1 s by the main loop, so the clock (and orbit motion) lags after hitches until slewing catches up.

### 4.3 Station reference frame

- **Entering the frame.** The nearest station whose distance is under 2200 (2750 if it is already the frame) becomes the frame. Docking forces its station as the frame.
- **The carry.** It is applied every frame before thrust. It stops at once when no station qualifies.

### 4.4 Ring debris

- **Inactive → active** when all of these hold: a ringed body exists; `inner − 2600 ≤ centre distance ≤ outer + 2600`; `|height| ≤ 784`; and `inner − 700 ≤ planar radius ≤ outer + 700`.
- **Leaving.** Any failed test hides all rocks and clears all contacts.
- **Per rock.**
  - shown = 0 → hidden. shown ≥ 0.03 → visible, scaled, with an update threshold of 0.02 in the shown fraction.
  - A contact is entered when `shown > 0.5` and the swept distance is under 26.
  - A contact is released when `|rel| > 32.5`, or when the rock is hidden.

### 4.5 Planet textures (Roblox fallback; same policy for the port's streaming)

- **The entry lifecycle.** begin (job) → rows (1 per resume, 4 ms per frame) → write → apply (shell over the body, body hidden) → follow every frame. Then either an upgrade (a new job, the old one kept until applied) or a release (the body becomes visible again).
- **Scans** happen every 0.75 s and keep the top 3 bodies by angular size that are ≥ 0.5°.
- **Failures:**
  - An Editable* budget failure → pause 20 s.
  - A template failure → never retried.
  - A patch failure → giants stay plain.

### 4.6 Impostors (dropped)

- **Entry states:** inactive, active and shown, active and hidden-behind. The hysteresis is ±3 % at the handover.
- **Probe:** idle (countdown from 6 s; 1 s after a failure) → calibrate → binary search → record.
- **Failure results:** `"verdeckt"` (blocked), `"unruhig"` (noisy), `"verworfen"` (rejected, < 280) or `"Eichung unklar"` (calibration unclear). Rejected and unclear results also rotate the starting aim.

### 4.7 Galaxy store

```
absent → ensure: fresh() + complete() [deposits rolled, Sol registry] → written ("frozen")
present → ensure: complete() again only if new bodies exist (never for unchanged definitions)
visit:    FirstVisit missing → set {By, UserId, Time}; returns first = true once
discover: Bodies[name].Discovered missing → set; first = true once; deposits become visible in the view
stations: construction writes Stations[id] (economy/stations area); the TICK (15 s) advances BuildEnds → Operational
          → syncStations → ShipStations.setPlayerStations → client re-syncs stations and sites
```

The FIRST VISIT toast shows only if all of these hold:

- `visit.UserId == me`;
- it was not shown yet for this index;
- the previous record had no `FirstVisit`;
- `now − visit.Time < 300 s`.

### 4.8 Edge cases

- **A station host not found** (a name mismatch): a warning, and the station is skipped.
- **A generated system with 0 planets**: no stations and no compression.
- **Generated station host collision**: the cyclic search over all planets can never fail, because `count ≤ #Planets`.
- **Station ids:**
  - `S<seed>-<n>`, where the seed is about 2.0e7–2.2e7.
  - Player stations are `P<UserId>`.
  - `findAnywhere` is the only id parser.
- **Generated orbit compression** can be partly undone by the second spacing pass, which can leave an outer orbit beyond 0.8·FieldRadius (there is no third check). Such a planet sits closer to the field edge, where the edge speed law applies. A run of the generator with a stand-in RNG over the catalog seeds (167 systems with planets) never exceeded the limit: the outermost reach was exactly 7.2 M. Golden data confirms it for the real sequences.
- **Ring image versus data inner radius** (section 2.12).
- **A ring tilt also tilts the planet's texture axis.** Uranus' image is drawn on a sphere rotated 82° about Z.
- **The rest position ignores the tilt.** Stations are computed from the untilted position, and the station offset is fixed in world axes. As a tilted planet moves, the station keeps its offset, so it "orbits" with the host but never rotates around it.
- **`dt` cap 0.1 s** (main loop): after a stall the clock lags real time; the shared clock slews it back at 0.5 s/s.
- **`Color3:Lerp` with negative t** (generated colours) extrapolates away from white. All template channels stay ≥ 0 for the ranges used (computed).
- **Names can collide** between systems and with Sol body names. Station lookups use ids, not names, but `findHost` matches by name within one system, so it is safe.

---

## 5. On screen

This area owns no 2-D HUD panels. What it puts on screen is the **3-D look of the bodies** and a few **texts** shown by other modules. The layout of toasts and prompts lives in the HUD and notices areas.

### 5.1 3-D layers per body (Roblox) and the port equivalent

| Layer | Roblox | Port (`BodyRenderer`) |
|---|---|---|
| Star disc | `MeshType.Sphere`, radius R, **Neon** (glows, unlit), colour `Star.colour` (Sol RGB 255,246,224) | Star shader: limb-darkened disc. Use the data colour instead of the constant `STAR_INTENSITY` white. |
| Corona | Sphere `R·coronaScale` (1.35), **ForceField** (fresnel rim glow), colour `Star.corona` (Sol RGB 255,190,110), Transparency 0.62 | Glow shader, with `gCorona` = the data corona colour. The glow extent should keep a "tight rim" look; at 3.4 R Roblox read as a second sun. |
| Planet or moon surface | Smooth sphere, **Fabric** (matte, no specular), the image (4096² cube cross) × `VertexColor` tint, or a plain colour | Ray-cast sphere: cube-map albedo × tint; Lambert from the true star direction; no specular |
| Atmosphere rim | Two ForceField shells (1.025 R t 0.72, 1.08 R t 0.86), colour = haze / atmosphere colour | Existing single-scattering atmosphere, with β from the atmosphere colour (`atmoParams`) and an absolute shell height (question Q11) |
| Night side | Black ramp shell at 1.016 R, soft terminator, black night | Shader `max(dot(n, L), 0)` plus a small wrap for softness. No city lights unless wanted (the Kotlin TERRAN type has emission, which is not in Roblox). |
| Ring | Two textured quads (image, alpha, front and back), or segment parts (Sand, ring colour, transparency) | Ring shader: opacity = the 128-step profile (Appendix D) at `f = r/outer`. Colour = the image colour (Sol) or the ring colour. The shadow of the planet on the ring and of the ring on the planet already exist in Kotlin. |
| Ring debris | Squashed spheres (`sphere.mesh`) with the 1K Moon/Rocky textures, tint, Fabric | Instanced lit meshes |
| Sky | The place's `Sky` (unknown content); nebula skyboxes Ember/Glacier/Violet are empty | Kotlin's procedural nebula and star field (question Q6) |

### 5.2 Texts

| Text | Where | Shown by |
|---|---|---|
| `FIRST VISIT` / `<SYSTEM NAME uppercase>  ·  NO PILOT HAS BEEN HERE BEFORE. YOUR NAME IS ON THE CHART.` | Toast, not urgent, 7 s | `ShipClient.lua:685` → `notices:toast` |
| `LANDING BLOCKED  NO SOLID SURFACE` | Approach prompt | `ShipAtmosphere.lua:693` reads `LandingBlocked` |
| `NO SOLID SURFACE` / `LANDABLE` | Construction facts | `ShipConstruction.lua:642` |
| System and body names, station names and descriptions | Maps, station UI, markers | Data from this area |
| `"%s  \|  Class %s  \|  %d planet(s)"` | — | `ShipStarCatalog.describe`, **unused** |
| `JUMP BLOCKED` / `STATION MASS LOCK  ·  FLY 6 KM AWAY` | Toast | `ShipClient.lua:843` (warp/stations area; wording, see question Q9) |
| `GALAXY DATA UNAVAILABLE` | Server reason | Not shown in the port |
| Debug: `Sichtweite … \| Boden … \| Doppel a/b \| verdeckt … \| letzte Messung …` and `Kugel: … \| Kacheln: … \| Texturen: …` | Console | Port a developer overlay in English: "TEXTURES: Earth 1024, Luna 256 (BUILDING)" |

All player-facing strings are upper case, and the separator is the middle dot `·` surrounded by two spaces.

### 5.3 Phone layout (20:9)

There is nothing area-specific to lay out. The FIRST VISIT toast uses the shared toast style from the notices spec; at 20:9 it should sit centred in the top third, clear of the camera cutout.

---

## 6. Dependencies

### 6.1 The body attribute contract: producers and consumers

Consumers come from a grep over all 98 scripts.

| Attribute | Producer | Read by |
|---|---|---|
| `SurfaceRadius` | `tagBody` | Gravity, Atmosphere, Surface, PlanetTerrain, PlanetTexture, Impostors, Markers, Survey, SystemMap, Autopilot, Traffic, Pilots, Interior, Training, ShipClient |
| `CollisionRadius` | `tagBody` | Gravity (barrier and limits), Atmosphere, Markers, Survey, Autopilot, Traffic, Interior, Training, ShipClient (`clearOfBodies`) |
| `MinApproachSpeed`, `SpeedCurve`, `SurfaceGravity` | `tagBody` | Gravity |
| `SurfaceGravityG` | `tagBody` | Gravity, Surface, Atmosphere, SystemMap |
| `LandingBlocked` | `tagBody` | Gravity, Atmosphere (prompt), SystemMap |
| `DisplayName` | `tagBody` | Gravity, Markers, Survey, SystemMap, Station (`findHost`), Surface, SurfaceTerrain, SurfaceProfiles, Mining, Construction, Pilots, Interior, Training, PlanetTexture, ShipClient |
| `BodyClass` | `tagBody` | Gravity, Markers, SystemMap, PlanetTerrain, SurfaceTerrain, SurfaceProfiles, PlanetTexture, Interior |
| `Seed` | `tagBody` | Surface, SurfaceTerrain, SurfaceProfiles, PlanetTerrain, PlanetTexture |
| `Landable` | `tagBody` | Atmosphere, SystemMap, PlanetTerrain |
| `IsStar` | `tagBody` | Gravity, Orbits, StarLight, Markers, SystemMap, Surface, SurfaceTerrain, PlanetTexture, Traffic, Pilots, Interior |
| `MassShadowFactor`, `MassShadowRadius` | `tagBody` / build | Gravity, Traffic |
| `HeatRadius` | build (star) | Gravity, Survey |
| `SkyRadius` | build (star) | Surface (sun disc in the landing sky) → **drop** |
| `OrbitRadius`, `OrbitPeriod`, `OrbitTilt` | `tagBody` | Orbits, SystemMap |
| `OrbitPhase` | `tagBody` | Orbits |
| `HasAtmosphere`, `AtmosphereColour` | build | SystemMap, SurfaceProfiles, PlanetTexture (`HasAtmosphere`) |
| `RingInner`, `RingOuter`, `RingColour`, `RingDensity`, `RingImage` | `buildRing` | RingDebris, SystemMap |
| `RingNormal` | `buildRing` | RingDebris |
| `TextureAsset` | `sphere()` | PlanetTexture (skip), SystemMap; SurfaceTerrain reads the image pixels via the mesh `TextureId` |
| `SphereRadius` | `sphere()` | Impostors, Surface, Decks |
| `ShadeMode`, `ShadeOffset` | `nightShade` | StarLight, Decks |
| `TerrainLocked` | PlanetTerrain (disabled) | Orbits |

### 6.2 Data flows

```
StarMap config ─► ShipStarCatalog ─► (ShipStarMap: galaxy map, jump targets) ─► definitionFor ─► ShipSystemBuilder.build
ShipSolData ───────────────────────────────────────────────────────────────────┘          │
ShipVisualAssets ─► build (textures, tints, variants, ring images, profiles) ─────────────┤
                                                                                           ▼
                                            System tree + attributes ─► ShipOrbits (positions every frame)
                                                                     ─► ShipGravity (limits, zones, heat, gravity)
                                                                     ─► Markers / Survey / SystemMap / Autopilot / Traffic
                                                                     ─► Atmosphere / Surface / Terrain (landing)
ShipStations (catalog + galaxy player stations) ─► ShipStation (placement from restPosition, carry, limits) ─► Docking/UI
ShipGalaxy (server) ◄─ Commerce (visit on jump, discover on survey sale, construction) ─► client view ─► stations, sites,
                       survey text, system-map discoveries, FIRST VISIT toast
ShipRingDebris ◄─ ring attributes, ship pos/vel ─► (hits ─► Audio.playHit, heat ─► Systems.addHeat)
ShipPalettes ─► PlanetTexture (colours), PlanetTerrain/SurfaceTerrain (materials, sea level, style)
ShipSphereMesh ─► Surface (ground facets), SurfaceTerrain (uvAt: planet image under the landing site)
ShipImpostors ─► Traffic (getSight), Surface (getSurfaceSight)
```

### 6.3 What other areas need from this one (port contracts)

| Area | Needs |
|---|---|
| Flight and gravity (flight spec) | Per body: radius, collision or terrain height, shadow factor, heat radius, gravity g, `MinApproachSpeed`, positions and **per-frame deltas** (body frame), star index. The speed-law redesign (Appendix B.3) has to be agreed with this area. |
| Terrain, atmosphere, seamless entry | Seed, class, palettes, atmosphere colour, `Landable`/`LandingBlocked`, **the body image as a cube map** (the terrain colours itself from it in Roblox), and S (Appendix B). |
| Stations and economy (`08`) | `ShipStations` catalog, offsets from rest positions, host lookup, body list (`getBodies`), galaxy store API (`visit`, `discover`, records, tick), player slots. |
| Maps, warp (`07`) | Catalog (180), system names, current-system bodies with orbit data (radius, period, tilt, phase) and rings, `arrivalPoint`, `clearOfBodies`. |
| HUD, notices | `LANDING BLOCKED` text, FIRST VISIT toast, zone labels (`ShipGravity.zoneLabel`). |
| Audio | Ring hit events with strength `0.3 + clamp(speed/400, 0, 1.1)` and a 1 s cooldown. |

---

## 7. Gap analysis against the current Kotlin code

Kotlin paths are relative to `core/src/main/kotlin/com/starwake/core/`.

| # | Roblox feature | Kotlin today | Status | Action |
|---|---|---|---|---|
| 1 | Sol: 1 star, 8 planets, 9 moons, real data | `game/StarSystem.kt:59` (`init`): placeholder PRIMARY (R 6e7 m), PLANET I, II, III (ringed giant, R 4e7 m, rings 1.42–2.32 R), IV, V; static; `edgeRadius` 2.6e9 | **missing** (placeholder) | Build from `SolData` at S = 3000 (section 9) |
| 2 | Moons, orbit tree | none | **missing** | Parent index, nested orbits |
| 3 | Body attribute contract (section 2.2.6) | `StarSystem.Body` (`:18`): name, type, radius, pos, rot, spin, hasRings, ringInner/Outer, atmo tint, atmoThickness, seed | partial | Extend: class string, colours, landable/blocked, gravity g, collision/shadow/heat/minApproach, orbit elements, texture ref, tint, ring (normal, colour, density, profile), parent, delta |
| 4 | Body classes (Terrestrial, Greenhouse, Rocky, Moon, Ocean, Ice World, Volcanic, Gas Giant, Ice Giant) | `StarSystem.BodyType` STAR, LAVA, TERRAN, GAS, ICE, ROCK (`:16`); shader codes `gfx/BodyShaders.kt:208` | differs | Keep the class *string* as data. Map to shader types for the fallback bake only (Terrestrial and Ocean → TERRAN; Rocky, Moon and Greenhouse → ROCK with a class palette; Volcanic → LAVA; Ice World → ICE; Gas and Ice Giant → GAS, banded) |
| 5 | Orbits, shared clock | positions are fixed (`pos` per body); `spin` is visual (1e-4 rad/s on the giant only) | **missing** | `Orbits.update` writes `pos` in place and records `delta`; spin 0 for all (Roblox bodies do not spin) |
| 6 | Station placement (rest position + Angle/Elevation/Distance, mouth to host) and station carry | `StarSystem.Station` (`:37`) with fixed `pos`/`rot`; HOME STATION at `home + (−2600, 250, −900)`; outposts via `offsetFrom` (`:109`) | differs | `StationDirectory` + placement (section 2.5); stations move with the host; carry |
| 7 | Station catalog: Sol 6, generated 0–2, ids, `findAnywhere`, `getBodies` | 3 placeholder stations | **missing** | Port `ShipStations` |
| 8 | Star catalog (180) | `ui/MapScreen.kt:73`: 600 decorative galaxy points; `drawGalaxy` `:420` | **missing** | `StarCatalog`; the maps area draws it |
| 9 | Names | none | **missing** | `Names.kt` |
| 10 | Procedural systems | none | **missing** | `ProceduralSystem.kt` |
| 11 | Field edge | `StarSystem.edgeRadius` 2.6e9, `Zone.SYSTEM_EDGE` | differs | `FieldRadius·S` = 2.7e10 m, plus the edge speed law (flight) |
| 12 | Speed limit and zones | `StarSystem.locate` / `speedLimit` (`:182`, `:250`): altitude-based exponential law (0.32/s approaching, 0.6/s departing, ±17° blend, floor = ship top speed, cap 3e6), zones (GRAVITY WELL < 3 R, LOW ALTITUDE < 60 km, STATION ZONE < 8 km, APPROACH/DEPARTURE < 12 R) | differs, but already close to the proposed scale-free law | The flight area ports `ShipGravity` with the redesign (Appendix B.3) and the Roblox zone labels |
| 13 | Body rendering | `gfx/BodyRenderer.kt:42`: ray-cast spheres at any distance, far-to-near sort, scattering atmosphere, star disc + glow, ring shader with shadows, per-body equirect bake (512/1024) at construction | **exists, better than Roblox** | Add: per-system rebuild; data colours (star, corona, ring); cube-map textures from assets; ring profile textures; occluder test (section 2.10); texture streaming; moons |
| 14 | Star light | `BodyRenderer.sunFor` (`:284`, per body), `StarSystem.sunlight` (`:121`, eclipses), `Game.updateLighting` (`Game.kt:1728`, ship light + planet fill), menu `Environment.sunDir` (`gfx/SceneRenderer.kt:23`) | exists | Nothing to port from `ShipStarLight`. Decide the falloff (question Q14). |
| 15 | Rings: images and profiles, colour, tilt | One procedural density (`BodyShaders.RING_DENSITY`: noise bands + a gap at 0.56–0.65) | differs | 1-D profile textures (Appendix D) + ring colour; tilt from data (Uranus 82°) |
| 16 | Ring debris | none. `game/World.kt:18` `AsteroidField` (420 rocks around the start, instanced) is a different, Kotlin-only feature. | **missing** | `RingDebris` reusing the instanced rock pipeline (`content/Procedural.kt` `AsteroidMesh`) |
| 17 | Planet textures: uploaded images, variants, tints | Procedural bake by type (`BodyRenderer.bake` `:464`, `BodyShaders.bakeFs`) | differs | Asset cube maps + streaming; the Roblox generator as the fallback bake |
| 18 | Far-body impostors | not needed (true scale) | n/a | Only the occluder test |
| 19 | Galaxy store | none | **missing** | `GalaxyStore` + `Platform` file I/O |
| 20 | Start pose | `Game.startFlight` (`Game.kt:557`): ship at `system.home`, moving | differs | Docked at MERIDIAN (stations area) / star arrival point / `StartPosition·S` |
| 21 | System map | `MapScreen.drawSystem` uses `StarSystem` bodies and stations, top-down, no moons | differs | Feed the new world (maps area, `07-menu-maps-warp.md`) |
| 22 | Landing | `Game.collideBodies` (`Game.kt:1678`) lands on the smooth sphere; `landable()` (`:969`) excludes STAR and GAS | partial | Seamless terrain (terrain area); `Landable` comes from the class |
| 23 | Floating origin | `math/Vec3.kt:73` `DVec3` positions, camera-relative rendering, depth slices (`SceneRenderer.planSlices`) | **exists** | `ShipOrigin` is not needed |
| 24 | Asteroid field near home | `AsteroidField` (Kotlin only) | extra | Not in Roblox. Keep or remove (question Q15). |

---

## 8. Phone adaptation

### 8.1 Inputs

This area has **no keyboard or mouse input** of its own. Jumping, maps, docking and survey belong to other areas. The only Roblox inputs are developer hooks:

| Roblox | Port |
|---|---|
| Player attribute `SightOverride` (Studio testing of graphics levels) | none (impostors are dropped) |
| Console: `print(_G.Ship.planetTexture:getStatus())`, impostor status, star name | Developer overlay page "WORLD" (texture slots and resolutions, body frame, clock, galaxy-store state). Toggle with a hidden gesture (e.g. a long press on the FPS counter) or a gamepad combination (Select + Y). |

**Phone-only additions** (optional, small):

- A haptic tick on a ring-debris hit, scaled by the hit strength: BUMP when strength > 0.8, otherwise TICK. It follows the audio cooldown of 1 s.
- On a gamepad, the FIRST VISIT toast needs no input; it closes itself after 7 s.

### 8.2 Performance and memory

**Textures** are the main budget.

| Item | Size |
|---|---|
| One Roblox image (4096² RGBA) | 64 MB |
| One cube map at 6 × 1024² ETC2 RGB (4 bpp) | 3 MB, or 4 MB with mips |
| One cube map at 6 × 512² | 1 MB |
| One cube map at 6 × 256² | 0.25 MB |

**Proposal:**

- **Sol bodies:** ship 1024² faces (17 × 4 MB = 68 MB in the APK) and stream them.
  - The top 3 bodies by angular size are resident at 1024 (Xiaomi 15, 3 × 4 MB) or 512 (mid-range).
  - All others stay at 256 (about 0.25 MB each). That is about 16 MB resident in total.
- **Class and variant images** (43): 512² faces (43 × about 1 MB ≈ 45 MB).
- **Rings:** the 128-step profiles (Appendix D, a few hundred bytes) plus, optionally, 1-D colour ramps sampled from the ring images (about 1 KB each).
- **APK size.** Ship ASTC (6×6 or 8×8) where supported and ETC2 otherwise, or WebP plus transcoding on first launch. A Play asset pack can hold the 1024 set.

**Bodies on the GPU:**

- At most 18 bodies, each one quad or a full-screen pass when near.
- The worst case is a planet filling the screen plus its atmosphere: 1–2 full-screen fragment passes. The Snapdragon 8 Elite handles that easily.
- On mid-range phones, keep `atmoSteps` at 5 (quality 0, as today).

**CPU:**

- Orbits: 17 bodies per frame, two sin/cos in double each → under 10 µs.
- Ring debris: 280 rocks per frame → about 30 µs. Use 110 rocks at low quality (the Roblox performance mode).
- The galaxy store and file writes run on a background thread.
- Generating the catalog takes under 5 ms, once.
- Building a generated system takes under 1 ms. Texture decoding runs during the warp tunnel (≥ 6 s).

**Zero per-frame allocation:**

- `Orbits` writes into existing `DVec3`s.
- `RingDebris` uses preallocated float arrays.
- Texture streaming decodes on a worker into reused buffers and uploads on the GL thread.
- No lambdas or boxing in `update`.

**Double precision** (Appendix B.5): positions in double; the clock in double, reduced modulo the period before trigonometry; GPU values camera-relative in float.

### 8.3 Roblox-only workarounds that become unnecessary or different

| Roblox workaround | Why it existed | In the port |
|---|---|---|
| `MAX_PART` 2048 / `SpecialMesh` scaling / probe `smooth_sphere.mesh` / fallback `MeshType.Sphere` | Part size limit; mesh files from the client | Ray-cast spheres, no meshes |
| `ShipImpostors` (copies, log depth, sight probe, triangle statistics, station guard, `LightClearance`) | Graphics-level render distance (350–32,000 studs) | Real-scale rendering at any distance (exists). Keep only a body-body occluder test. |
| `ShipStarLight` lookup table (`ClockTime`/latitude), elevation lift | Sun direction not settable, never from below | Per-body direction to the star in the shader |
| Night-shade spheres (offset/ramp), `AlphaPass` 0.02 | Roblox brightens the night side | Lambert lighting, black night side |
| `ShipPlanetTexture` UV-sphere `MeshPart` templates, composite patches, `MaxTexturedRadius`, budget retries, `LocalTransparencyModifier` toggles | `EditableImage` only on MeshParts ≤ 2048, memory budget | Texture streaming with cube maps; the generator as a bake shader |
| Uploaded image ids (`rbxassetid://…`) | Roblox content pipeline | App assets (PNG → cube maps, offline) |
| `ShipOrigin` 32-bit floating origin (`RebaseDistance` 1, BulkMoveTo of 270 parts) | 32-bit `Vector3` jitter | Double positions + camera-relative rendering (exists) |
| Separate landing scene (`SceneScale` 470, `toRealPose`, unparented system, `getSurfaceSight`, `SkyRadius`, `ShipSphereMesh` facet ground) | Roblox could not render a planet you can stand on at space scale | Seamless world at S; one star radius (no `SkyRadius`) |
| `TerrainLocked` (freeze orbits under voxel terrain) | Voxels are fixed in world space | Body reference frame (carry) |
| Server round trips: `GetSystem`, `GalaxyChanged` pushes, MessagingService, DataStore `UpdateAsync` retries, `REFRESH` caching | Shared multiplayer galaxy | Local synchronous `GalaxyStore` + JSON file |
| Shared server clock with slew/resync | All players see the same orbits | Local UTC clock (or a save clock) |
| `BodyClass`-tagged attributes on parts, CollectionService tags | Roblox data model | Plain Kotlin records and arrays |

---

## 9. Kotlin port plan

### 9.1 New and changed files (package `com.starwake.core.world` unless noted)

| File | Contents | Main API |
|---|---|---|
| `WorldScale.kt` | `S = 3000.0`, plus which quantities scale (Appendix B.6) | `fun len(studs: Double) = studs * S` |
| `SystemDef.kt` | Roblox-unit definitions (studs) | `class SystemDef(name, seed, star: StarDef, planets: List<PlanetDef>)`; `StarDef`, `PlanetDef`, `MoonDef`, `RingDef(inner, outer, segments, tiltDeg, rgb, transparency)`; colours as `Int` RGB |
| `SolData.kt` | Section 2.1 verbatim | `val SOL: SystemDef` |
| `RobloxRandom.kt` **or** `GoldenWorld.kt` | Roblox-compatible RNG (verified against Appendix C vectors), or a loader for the exported JSON (asset `world/golden.json`) | `nextNumber()`, `nextNumber(a, b)`, `nextInteger(a, b)` |
| `Names.kt` | Section 2.4 | `generate(seed: Long, properChance: Double): String` |
| `StarCatalog.kt` | Section 2.3 | `class CatalogEntry(seed, name, cls, rgb, sizeFactor, x, y, z /*ly*/, planets, isHome)`; `build(seed, count, radius, thickness)` |
| `ProceduralSystem.kt` | Section 2.2.9 | `make(seed, planetCount, starRgb, starClass): SystemDef` |
| `Palettes.kt` | Section 2.6 | `materials(cls)`, `colours(cls)`, `materialAt(t, p)`, `seaLevel(p)` |
| `VisualAssets.kt` | Section 2.7 + Appendix E: names → asset paths, variants (mean, haze), pools, ring profiles | `bodyTexture(name, cls, seed)`, `variantLook(tex)`, `ringTexture(name, seed)`, `ringProfile(tex): FloatArray?` |
| `SphereMesh.kt` | Section 2.8 decoder + `locate`/`uvAt` (shared by the converter and the terrain area) | `uvAt(x, y, z, out)` |
| `WorldBuilder.kt` | `build()` (section 2.2.8): definition → runtime bodies at S, phases/tilts/yaws, textures, rings, attributes | `build(def: SystemDef, systemIndex: Int): StarSystem` |
| `game/StarSystem.kt` (changed) | Becomes the data-driven runtime system. Bodies: arrays or records with the attribute contract, `pos`, `restPos`, `delta`, `parent`. Keeps `Locality`/`locate` until the flight port replaces them. | |
| `Orbits.kt` | Section 2.9 | `update(dt)`, `snapTo(clockSeconds)`, `restPosition(i, out)`, `delta(i)` |
| `StationDirectory.kt` | Section 2.5 (Sol, generated, player, offset, `getBodies`, `findAnywhere`) | `forSystem(index)`, `offset(def, host, star, out)` |
| `GalaxyStore.kt` | Section 2.15 (local) | `ensure(index)`, `visit(index)`, `discover(index, body)`, `update(index) { … }`, `sites(index)`, `tick(now)`, `onChanged` |
| `RingDebris.kt` | Section 2.12 | `update(dt, shipPos: DVec3, vel: Vec3): Int` (hits), `heat` field, instance buffer |
| `gfx/BodyTextures.kt` | Streaming manager (policy of section 2.13), cube-map upload, fallback bake | `update(camPos)`, `texture(i)` |
| `gfx/BodyRenderer.kt` / `gfx/BodyShaders.kt` (changed) | Cube-map sampling, data colours, ring profile `sampler2D` (128×1), ring colour, occluder uniforms, `rebuild(system)` | |
| `desktop/.../WorldAssets.kt` (tool) | Converts the cube-cross PNGs to cube-map KTX (ETC2/ASTC) using `SphereMesh.uvAt`; also packs the ring colour ramps | |

### 9.2 Hooking into `Game.kt`

**Fields.** Replace `private val system = StarSystem()` (`Game.kt:109`) with:

- `private var system: StarSystem = WorldBuilder.build(SOL, 1)`;
- `orbits`, `stations`, `galaxy`, `ringDebris` and `bodyTextures`.

`Content(system)` and `SceneRenderer` / `BodyRenderer` must be able to **rebuild for a new system** (on a jump).

**`updateFlight`** (`Game.kt:638`), in the Roblox order:

```
input → rotation (inside flight.step today)
orbits.update(dt)                                   -- before locate(): fresh positions
carry: ship.pos += stationFrameDelta or bodyFrameDelta   (flight area owns the frame choice)
system.locate(...)                                  -- gravity and limits
flight.step(...)                                    -- thrust
ringDebris.update(dt, ship.pos, ship.vel) → hits → audio/haptic; heat → ship systems
```

**`render`.** Call `bodyTextures.update(cam.pos)` on the GL thread before `scene.render`: a scan every 0.75 s, at most one face upload per frame.

**System changes.**

- **Start:** `galaxy.ensure(index)` and `stations.build(index)`.
- **Jump:** in the warp tunnel, `system = WorldBuilder.build(def, index)`, then `orbits.snapTo(now)`, `galaxy.visit(index)` (FIRST VISIT toast if first), `stations.build`, and the ship at `clearOfBodies(arrivalPoint)`.
- **Survey sale:** `galaxy.discover`.
- **Every 15 s:** `galaxy.tick`.

**Map.** `MapScreen(this, system)` must accept a new system (or read it through the host).

### 9.3 Order of work

1. **Data and scale.** Port `SystemDef`, `SolData` and `WorldScale`, then a `WorldBuilder` that turns Sol into the existing `StarSystem` (static at first, provisional phases). Point the existing `BodyRenderer` at it, with the fallback textures and data colours. Add the desktop scenario `sol-views`.
2. **Orbits, rest positions, Sol stations** (`StationDirectory` Sol part, placement from rest positions). The body frame carry is done jointly with the flight port. Add the scenario `orbits`.
3. **Rings** from the profiles (Saturn bands, Cassini gap, Uranus 82°), then **ring debris**. Add the scenario `ring-debris`.
4. **Golden export** (Appendix C, needs the user) → decide between `RobloxRandom` and embedded JSON. Then: catalog, names, procedural systems, generated stations. Add the scenarios `catalog` and `procedural`.
5. **Textures:** converter plus assets (needs the PNGs, question Q5), streaming, occluder test. Add the scenario `sol-textures`.
6. **Galaxy store** + FIRST VISIT + discoveries + player-station hooks (with the economy area).
7. **Tuning:** light falloff, quality tiers, memory checks on a mid-range device.

### 9.4 Unit tests (`core/src/test/kotlin/com/starwake/core/world/`)

| Test | Checks |
|---|---|
| `SolDataTest` | All values of section 2.1. Periods = 2π·orbit/speed (Earth 1,399,630 s ± 1). Scaled Earth radius 6.36e6 m; Luna orbit 3.84e8 m. Collision radii (Earth 2,349). |
| `OrbitsTest` | `positionOnOrbit` at clock 0, P/4 and P/2 with tilt 0 and 1.5° (radius preserved, y = −z·sin t). Moon position = planet + offset. `restPosition` ignores the tilt. Deltas sum to the displacement. The angle computed in double at clock 3e8 s matches the BigDecimal reference. |
| `StationDirectoryTest` | `offset()` against hand-computed vectors. Example: Meridian with the star at the origin and Earth at (828,660, 0, 0): base (−1, 0, 0) → Ry(90°) gives (0, 0, 1) → offset = (0, sin 24°, cos 24°)·6600 = (0, 2684.5, 6029.4) studs. Generated count frequencies over 10,000 seeds ≈ 35/45/20 %. Ids `S<seed>-<n>`. `findAnywhere` parsing. `pickModel` hash values. `getBodies` flags. |
| `NamesTest` / `StarCatalogTest` / `ProceduralSystemTest` | Structure invariants: Sol first at the origin; positions within radius 60 and thickness ±8·bulge; class weight frequencies; orbits increasing; spacing ≥ need; moon orbits ≥ 1.3× the previous; radii floors; names match the grammar. **Golden equality** with the exported JSON once available. |
| `RobloxRandomTest` (if ported) | The Appendix C reference sequences for 6 seeds, 16 values each, to 1e−12 |
| `VisualAssetsTest` | Pool order (variants alphabetical), `pick` modulo, variant mean/haze, ring tint rule, profile decoding (Saturn first non-zero step 69 ↔ inner/outer 0.546; Jupiter 107 ↔ 0.843; Uranus 105 ↔ 0.820) |
| `RingDebrisTest` | `wrap`; swept distance (a fast pass through a rock counts once); contact release at 1.25×; heat 0.012 at 0 m/s, 0.25 at ≥ 1000 m/s; density band; no hits outside the band or above 784 m |
| `GalaxyStoreTest` | `complete()` freezes deposits; Sol registry; `visit` returns true only once; `discover` first-only and reveals deposits; the build-timer tick turns a site operational; JSON save/load round trip; `Version` 1 |
| `BodyTexturesPolicyTest` | Resolution steps by angle; top 3 kept; never downgrade; release of bodies that leave the top 3 |
| `TripTimeTest` (with the flight port) | The Appendix B.3 model on the real Sol data at S = 3000 with the adopted speed law: Earth→Venus, Earth→Mars and Uranus→Neptune barrier-to-barrier times within ±5 % of the chosen row of the B.3 table; no leg slower than 2 min |

### 9.5 Desktop harness scenarios (`desktop/src/main/kotlin/com/starwake/desktop/Scenarios.kt`)

| Scenario | What it renders | Pass criteria |
|---|---|---|
| `sol-views` | Earth from MERIDIAN; Luna from Earth's surface; Saturn from Titan; Jupiter from GALILEAN REFINERY; Mars from Phobos; the Sun from Earth | Angular sizes within 2 % of Appendix B.2 (Earth 37.5°, Luna 0.53°, Saturn 5.67°, Jupiter 55.5°, Mars 36.3°, Sun 0.66°), measured from the silhouette in the PNG |
| `orbits` | The system map (or a top view) at clock t0, t0 + 6 h, t0 + 4 days | Phobos has moved 360°·6 h/5.7 h; Earth 360°·4/16.2; logged positions match `OrbitsTest` |
| `ring-debris` | Fly through Saturn's ring at 300 m/s, at f = 0.75 (B ring) and f = 0.62 (Cassini region) | Rock count high in the B ring, about 0 in the gap; the hit and heat log |
| `uranus-ring` | Uranus from 4 R | Ring nearly vertical (82°), planet axis tilted the same way |
| `catalog` | Galaxy map | 180 dots, Sol at the centre; colours by class; golden names if available |
| `procedural` | Arrival point of systems #2, #17, #120 | No body overlaps; star colour by class; stations at their hosts |
| `first-visit` | Jump to an unvisited system | The toast text and its 7 s lifetime |
| `perf-bodies` | 600 frames at Saturn ring level, Earth full-screen with atmosphere | Frame time on llvmpipe as a regression baseline; 0 allocations (allocation counter) |

---

## 10. Open questions and risks

### 10.1 Questions for the user

1. **Q1 Scale.** Is S = 3000 confirmed?
   - It gives real-size Earth (6,360 km) and Luna orbit, with every sky angle as in Roblox.
   - The alternative is S = 470, the Roblox landing-scene scale: Earth 996 km, and the surface tuning (atmosphere entry, clouds, terrain) carries over 1:1.
   - Appendix B.7 compares them.
2. **Q2 Cruise speed cap.** At S = 3000, trips stay under about 1.5 minutes only if the cruise may reach about 0.3–1 c in the outer system. Roblox caps at 3 Mm/s, and Josh said near-light cruising "doesn't make sense". Options (Appendix B.4):
   - (a) cap 0.5 c with time constants 2.5 s / 1.0 s: 52–89 s trips;
   - (b) cap 0.1 c: up to 4 minutes for Uranus–Neptune;
   - (c) keep 3 Mm/s: up to 28 minutes.
3. **Q3 Moving planets.** Keep the Roblox periods? Planets then move at 4–15 km/s and the ship is carried in each body's frame. The alternative is freezing orbits for a first milestone.
4. **Q4 Planet spin.** Roblox planets never rotate, so one Earth day = one orbit = 16.2 days, and a landed pilot sees the sun stand still. Keep this (1:1, simpler for seamless landing), or add a slow spin?
5. **Q5 Textures.** Please add the Tools/VisualGen output to the repo, or the generator itself:
   - 17 Sol bodies, 14 class images, 29 variants, 3 Sol rings + 2 generic + 5 variant rings, NightRamp, ideally at 4096;
   - also `Earth_clouds.png`, `means.lua` and `ring_profiles.lua`.
   - Without them, only the procedural fallback look is possible. The terrain also derives sea, ice and desert from these images.
6. **Q6 Sky.** What sky does the Roblox place show? The nebula skyboxes are empty, so Sol uses `Lighting.Sky` of the place. A screenshot or its six face ids would settle it; otherwise the Kotlin procedural nebula stays.
7. **Q7 Golden world data.** Could you run the Studio snippet in Appendix C? It makes the 179 generated systems, all names and all generated stations identical to Roblox, and pins Sol's planet phases. Without it, the port's generated systems will differ from the Roblox game.
8. **Q8 Clock.** Use real UTC time, so planets stand where they would in Roblox at the same moment and keep moving while the app is closed? Or a save-game clock that pauses?
9. **Q9 Original content.** Some names come close to Elite Dangerous terms:
   - The unused classes "Metal-Rich Body", "High Metal Content", "Ammonia World" and "Water World" (palettes and `ShipNames.classify`) mirror its body taxonomy. The port drops them because they are unused.
   - "Rocky Body" is used (Mercury, Mars, generated planets). Keep it, or rename it to e.g. "Rocky World"?
   - Player-facing "STATION MASS LOCK" (jump-blocked toast) uses the "mass lock" term. Rename it, e.g. "STATION ZONE  ·  FLY 6 KM AWAY"?
10. **Q10 Gas giants.** In a seamless world, what happens when a pilot dives into Jupiter? Roblox has an invisible barrier at 1.105 R + 6 and "LANDING BLOCKED  NO SOLID SURFACE". Options: keep a barrier at the cloud tops; a crush depth with heat damage; a bounce.
11. **Q11 Atmosphere height.** The Roblox shells (1.025 R and 1.08 R) would be 160 and 509 km thick on Earth at S = 3000. Should atmospheres get absolute heights (Earth about 100–150 km) instead? This decision is for the atmosphere area, but it changes the look from orbit.
12. **Q12 Station distance.** Scaled, MERIDIAN sits at 13,440 km altitude with Earth 37.5° wide. Keep that (same look as Roblox), or lower orbits?
13. **Q13 Generated gravity.** Every generated planet and moon has 0.5 g, because `gravityG` is missing in `makeProcedural`. Keep 1:1, or derive it from class and radius?
14. **Q14 Lighting.**
    - Roblox lights every body from the ship's sun direction, with constant brightness at any distance.
    - The Kotlin renderer uses each body's true phase and a gentle falloff (0.7–1.45×).
    - Keep the Kotlin behaviour, or match Roblox?
15. **Q15 Asteroid field.** The Kotlin start area has an asteroid field that Roblox does not have. Keep it as an extra, or remove it for 1:1?

### 10.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **Speed laws at S = 3000.** Naive scaling makes planet-to-planet trips take 8–23 min (Appendix B.3). | The flight port must adopt the scale-free law; desktop trip-time tests (Appendix B table) as acceptance |
| R2 | **Roblox `Random`/`math.noise` parity.** Without the export, generated systems differ, and the exact algorithm is unknown. | Appendix C golden data; tests against reference vectors |
| R3 | **Texture availability and size.** 71 images at 4096² are too big for the APK, and the assets are only on the user's PC. | Offline conversion to 512/1024 cube faces, ETC2/ASTC, streaming; fallback generator |
| R4 | **Body frame switching** (carry) may jitter or jump the ship. | Translation-only carry (as for stations); hysteresis 1.25; only switch frames well above the surface; unit test of continuity |
| R5 | **Body-body occlusion** (moon through a planet's limb) with centre-distance sorting | Occluder test in the shader (section 2.10) |
| R6 | **Determinism across devices** (trig differences flip `floor()` results in generation) | `StrictMath` in generation; golden tests on device and JVM |
| R7 | **Float time.** Using `Float` for the orbit clock (as `BodyMath.spinAngle(spin: Float, time: Float)` does today) breaks after days of uptime. | Clock and angles in double, reduced modulo the period |
| R8 | **The compression edge case** puts a generated outer planet beyond `0.8·FieldRadius`. It is unlikely: 0 of 167 systems in a stand-in-RNG run. | Check against golden data; clamp in the port if confirmed |
| R9 | **A huge seamless planet versus the Roblox terrain**: Roblox terrain colour comes from about 6 km texels, which becomes about 10 km per texel at S = 3000. | The terrain area adds procedural detail below texture resolution |
| R10 | **Save compatibility.** Saved positions in old coordinates. | `Ship.PositionFrame` mechanism: bump the version and start at the last station |

---

## Appendix A. Where things are

- **The full Sol table** (every body: class, radius, orbit, period, gravity, colours, rings, landable, atmosphere, barrier, shadow) is in **section 2.1**.
- **The station table** is in **section 2.5.1**.
- **The class palettes** are in **section 2.6**.
- **The image catalogue** is in **Appendix E**.

---

## Appendix B. The uniform scale factor S: worked recommendation

### B.1 What uniform scaling means

Every *world length* of the Roblox data (studs) becomes `length · S` metres. This covers body radii, orbit radii, ring radii, star heat and shadow radii, station distances, the field radius and the spawn points. Periods are kept, so orbital speeds become ×S. With **S = 3000**, rocky bodies come out at their real size:

| Body | At S = 3000 | Real |
|---|---:|---:|
| Mercury | 2,430 km | 2,440 km |
| Venus | 6,060 km | 6,052 km |
| Earth | 6,360 km | 6,371 km |
| Luna | 1,740 km | 1,737 km |
| Mars | 3,390 km | 3,390 km |
| Io | 1,830 km | 1,822 km |
| Europa | 1,560 km | 1,561 km |
| Ganymede | 2,640 km | 2,634 km |
| Callisto | 2,400 km | 2,410 km |
| Titan | 2,580 km | 2,575 km |
| Triton | 1,350 km | 1,353 km |

Other results at S = 3000:

- Luna orbits at 384,000 km (real 384,400). Phobos and Deimos orbits are close to real (10,800 and 23,400 km against 9,376 and 23,463), but their radius is 450 km because of the 150-stud minimum.
- Giants are 0.4× real (Jupiter 27,960 km).
- The Sun is 1/48 of real (14,400 km).
- Planet orbits are 1/60 of real (Earth 2.49 Gm). Light needs 8 s from the Sun to Earth.

This is exactly the Roblox design: "×10 of 1 stud = 30 km" means 1 stud = 3 km of reality for rocky bodies.

### B.2 Angular sizes are unchanged, so the look is identical to Roblox

Computed from the Lua values. They do not depend on S.

| View | Angular diameter | Real sky (reference) |
|---|---:|---:|
| Sun from Mercury / Venus / Earth / Mars / Jupiter / Saturn / Uranus / Neptune | 1.20° / 0.81° / **0.66°** / 0.51° / 0.24° / 0.16° / 0.11° / 0.08° (skyRadius: Earth 0.55°) | Earth 0.53° |
| Luna from Earth's surface | **0.53°** | 0.52° |
| Earth from Luna | 1.91° | 1.9° |
| Venus from Earth (closest) | 1.53° | 0.017° |
| Mars from Earth (opposition) | 0.52° | 0.007° |
| Jupiter from Earth (opposition) | 0.72° | 0.014° |
| Saturn from Earth (opposition) | 0.35° | 0.006° |
| Earth from Mars (closest) | 0.99° | |
| Saturn from Jupiter / Jupiter from Saturn (closest) | 0.85° / 1.02° | |
| Phobos from Mars / Mars from Phobos | 6.95° / 36.3° | |
| Deimos from Mars / Mars from Deimos | 2.58° / 16.8° | |
| Io from Jupiter / Jupiter from Io | 1.53° / 19.5° | |
| Europa, Ganymede, Callisto from Jupiter | 0.76°, 0.77°, 0.39° | |
| Titan from Saturn / Saturn from Titan | 0.66° / 5.67° | |
| Triton from Neptune / Neptune from Triton | 1.17° / 8.0° | |
| Host from its station: MERIDIAN / TRANQUILITY / ARES / GALILEAN / TITAN H. / HELIOS | 37.5° / 19.6° / 28.4° / 55.5° / 24.2° / 21.2° | |

**Conclusion.** A uniform S keeps the Roblox sky exactly:

- neighbour planets as moon-sized discs at closest approach;
- a planet filling a third of the sky from its station.

No non-uniform tweak of the orbits is needed for the look.

### B.3 Travel times: the cruise law is *not* fully scale-invariant

The Roblox speed chain, read from `ShipClient/ShipGravity.lua:249`, is the minimum over all bodies:

```
gap above the shadow  ≥ 0:  limit = min(MaxSpeed + (alt − shadow)/T(cos), CruiseMaxSpeed)       ← cruise law
inside the shadow:           limit = max(MaxSpeed·(alt/shadow)^0.75, max(150, 0.15·Rc))           ← absolute 1 km/s scale
inside 2.2·Rc:               limit = min(limit, max(minApproach, min(alt/2, approachReach(alt)/cos)))
                             approachReach(a) = a ≤ 280 ? a/2 : sqrt(2·70·(a − 140))              ← absolute 70 m/s² brake
T(cos) = 1.5 + (3.5 − 1.5)·clamp(0.5 + 0.5·cos, 0, 1)
```

- The `gap/T` term is scale-free. But `MaxSpeed`, the **inner shadow law** (≤ 1 km/s across 5 Rc) and the **constant-deceleration approach brake** are absolute.
- Scaling every length by S multiplies the time spent in those parts by up to S.

**The simulation model.** The script that produced the tables below is not committed. The model is described here in full, so it can be rebuilt as a JVM test (`TripTimeTest`, section 9.4):

- 1-D trips along the line of closest approach, with the Sun included.
- VARROW at full throttle, Flight Assist on.
- The `ShipThrust` cruise model:
  - acceleration time constant 0.8 s, at most ×2 per 0.8 s;
  - deceleration time constant 1.8 s;
  - brake 80 m/s², plus an exponential 0.4 s above 1 km/s;
  - limit fall smoothing 1.6 s below 1 km/s.
- From 50 m above the departure barrier to 50 m above the arrival barrier, at dt = 1/60 s.
- **Validation:** shadow edge to shadow edge, the model gives Earth→Venus 24.5 s, Earth→Mars 27.6 s, Jupiter→Saturn 35.0 s and Uranus→Neptune 37.8 s. The Lua comment says "Reisen dauern 24-47 s" (`ShipConfig.lua:49`).

**The proposed scale-free law** (for the flight port to adopt or refine):

```
gap   = altitude above the barrier / terrain top / cloud deck of the body
limit = min(MaxSpeed + gap / T(cos), Cap)                 -- the cruise law all the way down; no inner shadow region
stars: limit ≥ StarShadowMinSpeed (1000) inside HeatRadius   (as now)
near band, gap < 5 km (ABSOLUTE): limit = min(limit, max(minApproach, min(gap/2, approachReach(gap)/cos)))
"GRAVITY WELL" stays as a zone label while gap < Rc·shadowFactor (and the shadow stays the warp-arrival distance)
```

**Trip times** in seconds, barrier to barrier, with the peak speed in brackets. The "Station" column is MERIDIAN → Earth's barrier.

| Variant | Earth→Luna | Earth→Venus | Earth→Mars | Jupiter→Saturn | Uranus→Neptune | Jump arrival→Earth | Station |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Roblox** (S = 1, Lua laws, cap 3 Mm/s) | 47.2 (19 km/s) | 64.9 (22 km/s) | 58.0 (38 km/s) | 113.4 (157 km/s) | 97.3 (270 km/s) | 54.5 (129 km/s) | 17.4 |
| S = 3000, **naive** (Lua laws, cap ×S) | 471 | 785 | 618 | 1,405 | 967 (2.7 c) | 750 (1.3 c) | 39 |
| S = 3000, proposed, T 3.5/1.5, cap c | 75.8 (0.21 c) | 73.1 (0.25 c) | 79.2 (0.41 c) | 88.4 (1.00 c) | 94.6 (1.00 c) | 73.5 (0.99 c) | 29.0 |
| S = 3000, proposed, T 3.5/1.5, cap 0.5 c | 75.8 | 73.1 | 79.2 | 95.1 | 107.6 | 77.6 | 29.0 |
| **S = 3000, proposed, T 2.5/1.0, cap 0.5 c** | **57.9** (0.28 c) | **52.2** (0.34 c) | **60.6** (0.48 c) | **76.4** (0.5 c) | **88.8** (0.5 c) | **64.5** (0.5 c) | **21.5** |
| S = 3000, proposed, T 2.5/1.0, cap 0.3 c | 58.0 | 52.5 | 62.0 | 88.2 | 109.1 | 72.3 | 21.5 |
| S = 3000, proposed, T 3.5/1.5, cap 0.1 c | 78.6 | 77.2 | 90.5 | 169.3 | 231.6 | 128.5 | 29.0 |
| S = 3000, proposed, T 3.5/1.5, cap 3 Mm/s | 179.1 | 197.0 | 297.8 | 1,085.8 | 1,708.2 | 767.0 | 29.7 |
| S = 1, proposed law (reference) | 34.3 | 31.7 | 37.7 | 45.2 | 47.5 | 38.4 | 11.9 |

**Analytic rule.** Under `v = v0 + gap/T`, a leg from gap D down to the near band h0 takes `T·ln((D + v0·T)/(h0 + v0·T))`.

- Scaling D by S adds about `(T_approach + T_depart)·ln S` per trip. That is 5 s × 8.0 ≈ 40 s for S = 3000 with 3.5/1.5, and 3.5 s × 8.0 = 28 s with 2.5/1.0.
- It is the unavoidable price of real-size planets with absolute landing speeds, and it grows only logarithmically.

### B.4 Peak speeds and the cap

- With departing T = 1.5 and approaching T = 3.5, the two limits cross at 30 % of the gap D. The peak is therefore about 0.2·D per second.
- At S = 3000:
  - Earth–Venus (0.45 Gm) peaks at about 90 Mm/s (0.3 c).
  - Uranus–Neptune (5 Gm) peaks at about 1 Gm/s (3.3 c).
  - Scaling the Roblox cap ×S (9 Gm/s, 30 c) therefore allows *superluminal* cruise in the outer system.
- **Recommendation:** cap at **0.5 c** (150 Mm/s) together with T = 2.5/1.0. All Sol trips stay at 52–89 s, which matches the Roblox 47–113 s; only the outer planets ride the cap.
- If near-light speed is unwanted (Josh, 2026-11-06), 0.1 c costs up to 4 minutes for Uranus–Neptune (question Q2).

### B.5 Double-precision limits

| Quantity | Largest value at S = 3000 | Resolution | Verdict |
|---|---|---|---|
| Position (double) | Field edge 2.7e10 m (2^34–2^35) | 2^−18 m ≈ **3.8 µm** | Ample |
| Position near Earth (double) | 2.5e9 m | 0.5 µm | Ample |
| Orbit clock (double) | 3e8 s (10 years after the epoch) | 6e−8 s | Ample, but **compute `(clock mod period)/period` first**; never use `Float` (Float 2.7e7 s quantises to 2 s, which is Phobos jumping 6.6 km). `BodyMath.spinAngle(spin: Float, time: Float)` must become double. |
| Ship velocity (`Vec3` float) | 0.5 c = 1.5e8 m/s | 16 m/s | Fine: display only. Near bodies velocities are small and relative (body frame). |
| GPU camera-relative float | Terrain and stations within 100 km | 7.8 mm | Fine. Bodies use direction + distance in radii (existing impostor math), so precision is independent of distance. |
| Depth | Bodies are ray-cast (no depth); near objects use depth slices (exists) | | Real-scale terrain will need log depth or terrain slices (terrain area) |

### B.6 What scales and what must not

| ×S (world lengths) | Absolute (metres, seconds, m/s, m/s²) | Must change (redesign) |
|---|---|---|
| Body radii, collision radius (where kept) | Ship sizes, speeds (TopSpeed, Boost, `MaxSpeed` 1000 as the cruise base), accelerations, turn rates | Inner mass-shadow law and `MassShadowFloor` (0.15·Rc becomes 1 Mm/s at S = 3000) |
| Planet and moon orbit radii (periods kept) | Station models and all station-local ranges (`DockingRange` 6000, `ZoneRadius` 2200, `LodDistance` 60 km, 260/140/60 m/s, touchdown, docking computer) | Approach zone 2.2·Rc → an absolute near band (e.g. 5 km) |
| Ring inner and outer (via R) | Landing and approach (`ApproachBrake` 70 m/s², `MinApproachSpeed` 12/14/40, safe landing speeds, `MinManoeuvreSpeed` 12) | Cruise cap 3 Mm/s → 0.5 c (B.4) |
| Star radius, heat radius, mass-shadow radius (label and arrival distance) | Gravity accelerations (g values, `Physics.StudsPerG` 60 per g) | Atmosphere shells 1.025/1.08 R → absolute heights (question Q11) |
| `Ship.FieldRadius` (27 Gm), `StartPosition`, arrival point, `clearOfBodies` | Terrain heights, crust, sea-level offsets; cloud heights | `CloudShell` 1.004 R → an absolute cloud height |
| Station `Distance`, player-slot distance, `SlotClearance` | Ring debris field (1400 m, rock sizes 3–26 m, hit radius 26, spawn ±2600 m) | Gas-giant barrier `1.105·R + 6` (question Q10); no barrier on landable bodies |
| Generated-system constants that are lengths (radii, the 100,000 spacing, the field limit) | All times (periods, e-fold times, countdowns, tunnel) | `Ship.EdgeMargin` 2000 → scale or fold into the new law |
| Map zoom ranges (maps area) | `StarShadowMinSpeed` 1000, `EdgeSpeed` 150 | |

### B.7 Alternatives

All values are computed. Trips use the proposed law with T 2.5/1.0 and cap 0.5 c.

| | S = 470 (Roblox landing scene) | S = 1000 | **S = 3000** |
|---|---:|---:|---:|
| Earth radius | 996 km | 2,120 km | 6,360 km |
| Luna orbit | 60,160 km | 128,000 km | 384,000 km |
| Field radius | 4.2 Gm | 9 Gm | 27 Gm |
| Earth→Venus / Uranus→Neptune | 46.4 s / 60.9 s | 48.7 s / 66.7 s | 52.2 s / 88.8 s |
| Peak speed in Sol | 0.5 c (cap) | 0.5 c | 0.5 c |
| Horizon from 10 km altitude | 141 km | 206 km | 357 km |
| Texel of a 1024² cube face at the equator | 1.5 km | 3.3 km | 9.8 km |
| Roblox surface tuning (scene ×470: switch at 0.028 R, exit altitude 8 km, level 900 m, cloud deck) | **carries over 1:1** | re-tune | re-tune |

### B.8 Recommendation

**Use S = 3000**, as the user asked for huge planets. It gives:

- real-size rocky worlds;
- the exact Roblox sky;
- ample double precision;
- trip times close to Roblox.

These conditions come with it:

1. Keep the Roblox orbit **periods**, and carry the ship in the frame of the body it is near (section 2.9.4).
2. **Replace the inner mass-shadow law and the 2.2·Rc approach zone** with the single scale-free law (B.3). Use T = 2.5/1.0 and a cap of 0.5 c (flight area; question Q2).
3. Keep everything ship-local and near-surface **absolute** (B.6).
4. Give atmospheres, clouds and terrain absolute heights (atmosphere and terrain areas; question Q11).

If the surface team prefers to port the Roblox landing tuning unchanged, S = 470 is the only other consistent choice (question Q1).

---

## Appendix C. Studio export snippet for golden world data

Paste this into the **Roblox Studio command bar** (edit mode, place open).

- It only *reads* the game modules.
- It adds one folder, `ServerStorage.WorldExport`. Export it with right-click → *Save to File…* (`.rbxmx`), then delete it. Do not publish the place with it.
- The JSON parts can be extracted like the scripts (`tools/roblox/extract_lua.py`): join `Part001…` in order and strip the `return [==[` / `]==]` wrapper.

```lua
-- WorldExport: golden data for the Android port (catalog, 180 systems as built, stations, RNG and noise vectors)
local RS = game:GetService("ReplicatedStorage")
local Http = game:GetService("HttpService")
local Core = RS:WaitForChild("ShipCore")
local Config = require(Core.ShipConfig)
local Builder = require(Core.ShipSystemBuilder)
local Stations = require(Core.ShipStations)

local function conv(v)
	local t = typeof(v)
	if t == "Color3" then
		return { math.round(v.R * 255), math.round(v.G * 255), math.round(v.B * 255) }
	elseif t == "Vector3" then
		return { v.X, v.Y, v.Z }
	elseif t == "CFrame" then
		return { v:GetComponents() }
	elseif t == "EnumItem" then
		return v.Name
	elseif t == "table" then
		local o = {}
		for k, x in v do
			o[k] = conv(x)
		end
		return o
	end
	return v
end

local out = { version = 1, seed = Config.StarMap.Seed, catalog = {}, systems = {}, random = {}, noise = {} }
local catalog = Stations.getCatalog()
for i, s in catalog do
	out.catalog[i] = conv(s)
end
for i = 1, #catalog do
	local def = Stations.getDefinition(i)
	local folder = Instance.new("Folder") -- never parented: nothing appears in the world
	local root = Builder.build(folder, def)
	local bodies = {}
	for _, part in root:GetDescendants() do
		if part:IsA("BasePart") and part.Name == "Body" then
			local mesh = part:FindFirstChildOfClass("SpecialMesh")
			local holder = part.Parent.Parent
			local parentBody = holder and holder:IsA("Model") and holder:FindFirstChild("Body")
			local ring = part.Parent:FindFirstChild("Ring")
			local ringImage = ring and ring:FindFirstChild("RingImage")
			local ringMesh = ringImage and ringImage:FindFirstChildOfClass("SpecialMesh")
			table.insert(bodies, {
				name = part.Parent.Name,
				parent = parentBody and parentBody.Parent.Name or nil,
				attributes = conv(part:GetAttributes()), -- phase, tilt, period, seed, rings, colours …
				cframe = conv(part.CFrame), -- position (rest) and rotation (axis tilt, yaw)
				colour = conv(part.Color),
				texture = mesh and mesh.TextureId or "",
				tint = mesh and conv(mesh.VertexColor) or nil,
				ringTint = ringMesh and conv(ringMesh.VertexColor) or nil,
			})
		end
	end
	out.systems[i] = { definition = conv(def), bodies = bodies, stations = conv(Stations.baseForSystem(i)) }
	folder:Destroy()
end
for _, seed in { 0, 1, 13, 7919, 84213, 20260824 } do
	local r = Random.new(seed)
	local seq = {}
	for k = 1, 8 do
		seq[k] = r:NextNumber()
	end
	for k = 9, 16 do
		seq[k] = r:NextInteger(1, 6)
	end
	out.random[tostring(seed)] = seq
end
for k = 1, 32 do
	out.noise[k] = math.noise(k * 0.37 + 0.11, k * 0.23 - 0.5, k * 0.71 + 0.3)
end

local json = Http:JSONEncode(out)
local holder = Instance.new("Folder")
holder.Name = "WorldExport"
holder.Parent = game:GetService("ServerStorage")
local CHUNK = 150000
for n = 0, math.ceil(#json / CHUNK) - 1 do
	local m = Instance.new("ModuleScript")
	m.Name = string.format("Part%03d", n + 1)
	m.Source = "return [==[" .. string.sub(json, n * CHUNK + 1, (n + 1) * CHUNK) .. "]==]"
	m.Parent = holder
end
print(("WorldExport: %d characters in %d parts"):format(#json, #holder:GetChildren()))
```

**What it pins down:**

- **Every system as built.** For each body: names, parent, `OrbitPhase`, `OrbitTilt`, `OrbitPeriod`, `Seed`, ring attributes, texture and tint, and the rest CFrame (axis tilt and yaw).
- **The station lists** of all 180 systems.
- **The catalog** (names, classes, positions).
- **48 reference values** of Roblox `Random` and 32 of `math.noise`, to verify or reject a Kotlin re-implementation.

---

## Appendix D. Ring opacity profiles (verbatim from `ShipVisualAssets.lua:177` and `:191`)

**Format.**

- 128 values from the planet centre (f = 0) to the outer ring edge (f = 1), as two digits each, 00..99 → value / 99. Each string is printed as two halves of 64 values; concatenate them.
- Value *i* (1-based) is the opacity at `f = (i − 0.5)/128`. Interpolate linearly between values (`ShipRingDebris.densityAt`).
- The first non-zero value matches the inner edge: Saturn step 69 (f 0.539) against inner/outer 0.546; Jupiter 107 (0.836) against 0.843; Uranus 105 (0.820) against 0.820.
- The generic slots are indexed exactly like `Rings.Generic` (Appendix E.4).

```
Saturn
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000109101314151718201918212222242524445962697274767880849293939090929188888989918977060710063964666563626261616025525229
Jupiter
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000010405050509161314121415121415202423211405
Uranus
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000000000000000000000000000000000000000000000000001010172102020920250302021527080230140209595407
Generic[1]
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000005242834364253586266677167727068297058247272757679681365561021637475758078767372656460626256545449494932
Generic[2]
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000000000016636765636737002184808374000234333530104544434743100032390912393830164950514910536908
Generic[3] Var_Ring_1 (Frost Divide)
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000808131719626982858384868790858390928587380226727879777776767267728077737472667279831402000000
Generic[4] Var_Ring_2 (Dust Veil)
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000105081113110516131815151514161518242324282830311623232126222825202020232522182024221413171107161306030000
Generic[5] Var_Ring_3 (Grooved Disc)
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000000053757374155534204154146503923493408196025162441032644564657494821025362565339380000000000
Generic[6] Var_Ring_4 (Rose Dawn)
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000103837504334503060314638406585818684878984849088838378837677635755587370536567596439636864191608020000
Generic[7] Var_Ring_5 (Twin Halo)
  00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
  00000000000000000000000000000000000000000000000103276488722200000001000000000000010000000014648863081010080806050506040302010000
```

**Use in the port.**

- Upload all 10 profiles as one 128×10 R8 texture. The ring shader samples the row of its ring at `f = r/outer`.
- Multiply by the ring colour: the Sol rings' image colour sampled once offline, or the generic tint rule of section 2.7.3.
- The ring-debris code reads the same arrays.

---

## Appendix E. Visual asset catalogue (Roblox ids)

These ids only identify images on Roblox. The port needs the **PNG files** (question Q5); use the keys below as file names.

### E.1 Sol bodies (`Bodies`, `ShipVisualAssets.lua:51`, 4096², cube cross)

| Body | Asset id (4096) | Fallback 1K id |
|---|---|---|
| Mercury | `rbxassetid://75544995218873` | `rbxassetid://132158513137957` |
| Venus | `rbxassetid://87610613592614` | `rbxassetid://124680566771835` |
| Earth | `rbxassetid://87254590989383` | `rbxassetid://115812588816011` |
| Mars | `rbxassetid://73431859680302` | `rbxassetid://91067838159955` |
| Jupiter | `rbxassetid://96172966309937` | `rbxassetid://98753803105238` |
| Saturn | `rbxassetid://78905727977017` | `rbxassetid://91842348066832` |
| Uranus | `rbxassetid://83022634768261` | `rbxassetid://138996309380337` |
| Neptune | `rbxassetid://118731590460086` | `rbxassetid://121406505857923` |
| Luna | `rbxassetid://71062908690457` | `rbxassetid://115518382295458` |
| Phobos | `rbxassetid://117933433504933` | `rbxassetid://83736661519908` |
| Deimos | `rbxassetid://76376865849093` | `rbxassetid://90187076252051` |
| Io | `rbxassetid://103669542278338` | `rbxassetid://96964015583315` |
| Europa | `rbxassetid://102372814445883` | `rbxassetid://138678548991980` |
| Ganymede | `rbxassetid://113709438886229` | `rbxassetid://90832114881830` |
| Callisto | `rbxassetid://132603788451840` | `rbxassetid://137997190813418` |
| Titan | `rbxassetid://135414108828121` | `rbxassetid://106544876971059` |
| Triton | `rbxassetid://87237094817819` | `rbxassetid://139755030662396` |

### E.2 Class images (`Classes`, `:81`, 4096²; the 2 base images of each pool)

| Class | Base image 1 | Base image 2 | 1K fallbacks (used for ring debris) |
|---|---|---|---|
| Rocky Body | `rbxassetid://107791218242057` | `rbxassetid://133689343422573` | `rbxassetid://133383877980081`, `rbxassetid://91498965600595` |
| Ice World | `rbxassetid://138338187899739` | `rbxassetid://96514724527481` | `rbxassetid://80956235863862`, `rbxassetid://104569301217254` |
| Volcanic Body | `rbxassetid://138123201215421` | `rbxassetid://91944952652731` | `rbxassetid://124274790804914`, `rbxassetid://93051184913658` |
| Ocean World | `rbxassetid://79269267855008` | `rbxassetid://87087657497111` | `rbxassetid://134959709160535`, `rbxassetid://107233461495264` |
| Gas Giant | `rbxassetid://80192343230689` | `rbxassetid://112455087613690` | `rbxassetid://79329171103693`, `rbxassetid://112956811678651` |
| Ice Giant | `rbxassetid://81990704942093` | `rbxassetid://97023165722903` | `rbxassetid://127760260231929`, `rbxassetid://138648715653689` |
| Moon | `rbxassetid://72651882431467` | `rbxassetid://139078013513324` | `rbxassetid://79729148769977`, `rbxassetid://124662774825931` |

### E.3 Variants (`Variants`, `:100`, 4096²; appended to their class pool in name order)

`mean` becomes the body colour (maps, debris, fallback). `haze`, when present, replaces the atmosphere colour.

| Key | Class | Title | mean RGB | haze RGB | Asset id |
|---|---|---|---|---|---|
| Var_EarthLike_1 | Ocean World | Verdant Shores | 88,110,123 | 150,190,240 | `rbxassetid://117083618351366` |
| Var_EarthLike_2 | Ocean World | Amber Savanna | 121,126,108 | 220,196,160 | `rbxassetid://136509270904976` |
| Var_EarthLike_3 | Ocean World | Coral Archipelago | 52,91,131 | 140,200,240 | `rbxassetid://138177709618367` |
| Var_EarthLike_4 | Ocean World | Boreal Frost | 114,129,137 | 190,214,238 | `rbxassetid://86736502666883` |
| Var_EarthLike_5 | Ocean World | Crimson Tide | 96,102,113 | 200,176,214 | `rbxassetid://139081585513086` |
| Var_GasGiant_1 | Gas Giant | Amber Classic | 185,154,125 | 232,208,172 | `rbxassetid://77839762221314` |
| Var_GasGiant_2 | Gas Giant | Ember Furnace | 158,85,49 | 236,150,86 | `rbxassetid://132783341171010` |
| Var_GasGiant_3 | Gas Giant | Jade Ammonia | 140,182,171 | 176,222,204 | `rbxassetid://81660335436804` |
| Var_GasGiant_4 | Gas Giant | Orchid Exotic | 172,129,176 | 216,180,226 | `rbxassetid://140330979178102` |
| Var_GasGiant_5 | Gas Giant | Saffron Haze | 224,193,128 | 242,214,150 | `rbxassetid://115100520431101` |
| Var_IceGiant_1 | Ice Giant | Cobalt Deep | 44,69,157 | 96,134,226 | `rbxassetid://80473180771806` |
| Var_IceGiant_2 | Ice Giant | Glacier Veil | 197,228,232 | 206,238,242 | `rbxassetid://78481442988069` |
| Var_IceGiant_3 | Ice Giant | Lagoon Streaks | 56,134,142 | 100,188,190 | `rbxassetid://87709728814380` |
| Var_IceWorld_1 | Ice World | Rustvein Crust | 210,205,201 | - | `rbxassetid://133521201693069` |
| Var_IceWorld_2 | Ice World | Glacier Reach | 219,225,232 | 205,222,240 | `rbxassetid://135391012525582` |
| Var_IceWorld_3 | Ice World | Cinder Ice | 148,141,133 | - | `rbxassetid://104035168671461` |
| Var_IceWorld_4 | Ice World | Rose Nitrogen | 194,153,138 | 165,192,230 | `rbxassetid://124035361619241` |
| Var_IceWorld_5 | Ice World | Frozen Sea | 140,159,167 | 205,225,235 | `rbxassetid://75224871619044` |
| Var_Rocky_1 | Rocky Body | Rustwind Erg | 153,82,54 | 205,140,105 | `rbxassetid://70393824461569` |
| Var_Rocky_2 | Rocky Body | Basalt Rays | 60,60,59 | - | `rbxassetid://94373064743782` |
| Var_Rocky_3 | Rocky Body | Sulphur Rift | 180,143,85 | - | `rbxassetid://98616523572683` |
| Var_Moon_1 | Moon | Old Grey | 164,160,153 | - | `rbxassetid://103697916576050` |
| Var_Moon_2 | Moon | Janus Face | 161,155,149 | - | `rbxassetid://136751858067862` |
| Var_Moon_3 | Moon | Frostdust | 188,189,192 | - | `rbxassetid://88549706543947` |
| Var_Volcanic_1 | Volcanic Body | Cinder Rivers | 46,36,30 | - | `rbxassetid://81318535672062` |
| Var_Volcanic_2 | Volcanic Body | Rift Plates | 73,42,34 | - | `rbxassetid://85428409779437` |
| Var_Volcanic_3 | Volcanic Body | Sulphur Calderas | 200,168,99 | - | `rbxassetid://101845747282444` |
| Var_Volcanic_4 | Volcanic Body | Ash Cones | 128,124,119 | 170,162,152 | `rbxassetid://118156024263708` |
| Var_Volcanic_5 | Volcanic Body | Magma Sea | 167,59,17 | 255,150,80 | `rbxassetid://118292184655651` |

### E.4 Ring images (`Rings`, `:165`; `RingVariants`, `:191`; 4096², radial: image centre = planet centre, image edge = outer radius)

| Slot | Title | Colour | Asset id (4096) | 1K fallback |
|---|---|---|---|---|
| Saturn | - | own (no tint) | `rbxassetid://94386445824647` | `rbxassetid://128492421873659` |
| Jupiter | - | own (no tint) | `rbxassetid://104506827511343` | `rbxassetid://111116876474633` |
| Uranus | - | own (no tint) | `rbxassetid://138894720325818` | `rbxassetid://79819800636005` |
| Generic[1] | - | tinted to the ring colour | `rbxassetid://89045652434273` | `rbxassetid://124460179891567` |
| Generic[2] | - | tinted to the ring colour | `rbxassetid://89802101860014` | `rbxassetid://87854319066373` |
| Generic[3] = Var_Ring_1 | Frost Divide | own (no tint) | `rbxassetid://136459058233716` | - |
| Generic[4] = Var_Ring_2 | Dust Veil | own (no tint) | `rbxassetid://136493034183366` | - |
| Generic[5] = Var_Ring_3 | Grooved Disc | own (no tint) | `rbxassetid://109716940451602` | - |
| Generic[6] = Var_Ring_4 | Rose Dawn | own (no tint) | `rbxassetid://86702727073015` | - |
| Generic[7] = Var_Ring_5 | Twin Halo | own (no tint) | `rbxassetid://91087744885987` | - |

**Other ids.** NightRamp `rbxassetid://113752600431188` (`:256`). Earth clouds PNG uploaded as `rbxassetid://88557606026712`, intentionally unused (`:75`). Skyboxes Ember/Glacier/Violet: all faces empty (`:265`).

