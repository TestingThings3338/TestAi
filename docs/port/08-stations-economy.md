# 08 · Stations and economy: port reference

Port spec for everything that happens at and around space stations, and for the money side of the game:

- station models in the world;
- docking (request, clearance, pads, approach aid, docking computer, touchdown, clamps, launch);
- station services UI (hub, overview/refuel, market, cartography/data sale, shipyard);
- survey, hand mining, and building your own station (construction);
- the pilot profile and the save data.

**Sources.** All Lua paths are relative to `roblox/lua/`. `path:line` points at the line where a function or table starts. Every number in this document was read from the Lua or from `Assets.rbxmx` (stream-parsed for this spec). Nothing is estimated unless it says so.

**Units and frames.** 1 stud = 1 m, so studs/s = m/s. Roblox is right-handed and Y-up, and a `CFrame`'s `LookVector` is its **-Z** axis. That matches the Kotlin engine: `StationMesh` already uses "-Z = forward".

**Server → local.** In Roblox the server modules (`ShipServer/*`) decide everything, and the client only asks. The port is singleplayer, so those modules become synchronous local logic (section 8.3). The spec keeps their rules, their rejection reasons and their visible timings, such as the 1.6 s "radio delay".

**Naming.** The player is **PILOT** everywhere. The Roblox code calls the pilot "Kommandant/Commander" internally, and one UI frame is named `Commander`. The port must never show "COMMANDER" or "CMDR".

---

## 0. Key facts in one screen

- **Six station types** share **one hangar contract**:
  - Ring, Garden, Foundry, Spire, Hollow and Refinery.
  - Hangar box 360 × 150 × 690 m, centred on the station root.
  - The mouth is at local z = -370. Approach points are Outer (0,0,-1270), Mouth (0,0,-370) and Inner (0,0,-320).
  - The **six pads** are 110 × 2 × 130 m. Their centres are at x = ±85, y = -74, z ∈ {-130, 30, 190}, and every pad faces the mouth (-Z).
  - Only looks and hull shapes differ between types. Verified in `Assets.rbxmx`, section 2.3.
- **The station models need no help from the user.**
  - Stations and the construction site contain **zero UnionOperations and zero MeshParts**. They are built only from Parts (Block, Cylinder, Ball), WedgeParts and five SpecialMesh spheres (dishes).
  - The 69 unions in the export belong to `Assets/Ships`, and the 240 MeshParts to `Assets/Decks`. Both are outside this area.
  - Detail comes from **25,403 SurfaceGui Frames** ("paint": window grids, hazard stripes, deck markings) and 171 TextLabels. The converter has to bake these.
- **The station mouth always faces its host planet.** Sol stations sit **2.1–5.9 host radii** from the host centre (`ShipStations.lua:41`). Generated stations sit at `floor(r·2.1 + 2200)`. In the port these distances scale with the world factor S, as `docs/port/03-world.md` §2.5 recommends (S = 3000: MERIDIAN 19,800 km from Earth's centre). Docking ranges, zones and everything inside the station stay absolute.
- **Speed control near a station**, measured from the station centre:
  - Inside 6000 m: 260 m/s "mass lock" (no cruise, no jump).
  - Inside 2200 m: 140 m/s.
  - In the hangar: 60 m/s.
  - Outside 6000 m, the cruise limit ramps up with distance.
  - A hull impact above 45 m/s destroys the ship.
- **Docking states:** FREE → REQUESTING (1.6 s) → GRANTED (600 s permit, pad assigned) ⇄ AUTO (docking computer) → CLAMPING (1.6 s smoothstep) → DOCKED → LAUNCHING (computer flies out) → FREE.
- **Economy:**
  - 17 commodities with base prices and 6 economy types.
  - Station price = base × economy factor × (1 + drift ±6 %, new target every 1200 s, smoothstep blend). Buy is +4 %, sell is -4 %.
  - Refuel costs 4 CR per unit, and a full tank is 100 units.
  - Losing the ship costs 5 % of its price.
  - A new pilot starts with 1,500 CR in a VARROW, docked at MERIDIAN STATION (Sol, Earth).
- **Survey and mining:**
  - Survey data is worth a class base of 500–12,000 CR, ×1.2 if landable, ×1.35 if ringed, stars × a spectral factor. Sol pays ×0.3. A first discovery pays ×1.5.
  - Hand mining yields 1 t per 24/18/14/11/8 s (grade 1–5). You must be landed, below 120 m and slower than 40 m/s.
- **Own station:**
  - The charter costs 200,000 CR.
  - There are 4 stages (FRAME, CORE, HABITAT RING, DOCKING BAY), each needing material deliveries, with 30/40/50/60 min build times. The clock runs in wall time.
  - A finished station drills its host at 2/4/6/9/12 t/h per deposit grade, into 200 t of storage.

---

## 1. Overview: what the player experiences

1. **Start.** A new pilot spawns **docked** at MERIDIAN STATION above Earth, on a random pad. The station services window is already open: hub tiles, the pilot card, ship status and a scrolling news ticker. The pilot has 1,500 CR and a VARROW with a full tank.
2. **Launch.** Tapping LAUNCH runs the clamps-release cue ("LAUNCH CLEARED. FOLLOW THE GUIDE LIGHTS OUT."). The **docking computer** lifts the ship 26 m off the pad, flies it through the hangar and out of the mouth, and drops it 1,270 m in front of the station, nose towards the planet. The pilot then has control with the throttle at zero, and Flight Assist bleeds off the remaining speed.
3. **Approach.** Within 6 km of a station the HUD offers "REQUEST DOCKING · <NAME>". The request goes out ("CONTACTING …"). After 1.6 s traffic control answers with "DOCKING REQUEST GRANTED. PROCEED TO PAD 04." The pad's lamps blink amber, and a "PAD 04 ▼" marker floats 40 m above it, visible through walls.
4. **The approach aid** shows pad, distance, a status line, the speed limit and a 10-minute permit timer. The status line says things like "GO AROUND TO THE MOUTH", "ENTER THROUGH THE MOUTH", "FLY TO PAD 04", "SLOW DOWN", "LEVEL THE SHIP", "CENTRE OVER THE PAD" or "DESCEND AND TOUCH DOWN". At the mouth, green (left) and red (right) chase lights run towards the hangar. On some types, four PAPI lamps show white/red for the glide slope.
5. **Two ways in:**
   - **Fly it yourself.** Touch down within 26 m of the pad centre, at ≤ 12 m/s and ≤ ~20° tilt.
   - **Engage the docking computer.** It flies a smooth path (around the hull if needed → outer point → mouth → inside → 34 m over the pad → down at 5 m/s). Any stick input hands control back.
6. **Clamps.** The ship is pulled into the exact pad pose over 1.6 s. The pad lamps turn green, a heavy clamp sound plays, and "LANDING CLAMPS ENGAGED. WELCOME TO …". The services window opens.
7. **Services:**
   - **Refuel** for 4 CR/unit.
   - **Market:** 17 goods. Prices drift every 20 minutes and rows show supply/demand. Buy and sell with a quantity stepper.
   - **Cartography:** sell all survey data carried.
   - **Shipyard** (only at some stations): 8 ships from 22,000 to 265,000 CR, each shown on a turntable preview. You must sell your cargo before switching.
8. **Survey.** Lock a star, planet or moon, fly within range (8 radii above the surface, at least 2.5 km; stars from outside their heat zone), point the nose at it and **hold SURVEY** for 4–6 s. The first pilot to survey a body "puts their name on the chart" and gets ×1.5. The data stays in the hold until sold, and it is lost if the ship is destroyed.
9. **Hand mining.** Land on a surveyed, uninhabited, landable body, hover below 120 m and under 40 m/s, and **hold MINE**. A pulsing orange beam cuts ore, giving 1 t per 8–24 s depending on grade. The best deposit is chosen automatically, or the pilot picks one in the survey panel.
10. **Own station.** The survey panel shows a surveyed body's deposits, a value letter A–E and the station type it would get. For 200,000 CR the pilot founds a **build site** in a free orbit slot and picks a name from 12 options. A wireframe scaffold appears, with the next stage as an orange hologram.
    - Each stage needs deliveries (for example 16 t METAL ORE + 8 t CONSTRUCTION ALLOYS). Once everything is delivered (within 3 km of the site), the stage builds in real time, offline too.
    - After stage 4 the site becomes a real dockable station with your name and the economy of its deposits. It drills around the clock into 200 t of storage, which you load into your hold or sell at its own prices while docked there.
11. **Loss.** If the ship is destroyed, the replacement fee is 5 % of the ship price, and cargo and unsold survey data are lost (the lost bodies can be surveyed again). The replacement ship waits in front of the station nearest the crash.
12. **Save.** Everything persists: credits, ships and fuel, cargo, unsold data, charted bodies, location (docked station or last system), stats, training flag, home station, own station, and per-system galaxy records (discoveries, deposits, the build site).

---

## 2. Modules

### 2.1 ShipStations: which stations exist (`ShipCore/ShipStations.lua`)

**Purpose.** A pure data/catalog module, used by the server and the client alike. It provides Sol's hand-made stations, deterministic generated stations for every other system, the player stations that the galaxy store injects, station offsets, and the body list for survey.

**Sol stations** (`ShipStations.lua:41`). There are six. `HomeStation = "SOL-MERIDIAN"` (`:83`).

| Id | Name | Host (Roblox radius) | Economy | Shipyard | Model | Distance | Angle | Elev. | Dist./radius |
|---|---|---|---|---|---|---|---|---|---|
| SOL-MERIDIAN | MERIDIAN STATION | Earth (2120) | HighTech | yes | SpireStation | 6600 | 90 | 24 | 3.11 |
| SOL-TRANQUILITY | TRANQUILITY YARDS | Luna (580) | Industrial | yes | FoundryStation | 3400 | 200 | -32 | 5.86 |
| SOL-ARES | ARES DEEP | Mars (1130) | Extraction | no | HollowStation | 4600 | 60 | 28 | 4.07 |
| SOL-GALILEAN | GALILEAN REFINERY | Jupiter (9320) | Refinery | no | RefineryStation | 20000 | 120 | 30 | 2.15 |
| SOL-TITAN | TITAN HYDROPONICS | Titan (860) | Agricultural | no | GardenStation | 4100 | 300 | 34 | 4.77 |
| SOL-HELIOS | HELIOS FORGE | Mercury (810) | Industrial | no | RingStation | 4400 | 270 | -26 | 5.43 |

The descriptions are listed in Appendix B. Ares sits steeper than the others because Phobos orbits at 3600 in the plane. Galilean sits outside Jupiter's ring, which reaches 16000.

**Offset from host** (`ShipStations.offset`, `:321`):

```
toStar  = starPos - hostPos;  flat = (toStar.x, 0, toStar.z)
base    = |flat| > 1e-3 ? flat.unit : (0,0,-1)
dir     = RotY(rad(Angle)) * base            // RotY: x' = x·cosθ + z·sinθ, z' = -x·sinθ + z·cosθ
e       = rad(Elevation)
offset  = (dir·cos e + Y·sin e) · Distance    // Distance defaults to 4000
```

Test vector: host (828660,0,0), star (0,0,0), Meridian (90°, 24°, 6600) gives offset (0, 2684.46, 6029.40).

**Generated stations** (`baseForSystem`, `:198`) are cached per system index:
- `rng = Random(system.seed·31 + 7)`.
- `roll = rng:NextNumber()`. If roll < 0.35 there are **0** stations, if < 0.8 there is **1**, otherwise **2**. Never more than the planet count.
- For each station n = 1..count:
  - Host: a random planet `NextInteger(1,#planets)`, advancing cyclically to the first planet that has no station yet.
  - `economy = pickEconomy(planet.class, rng)` (`:130`):
    - `roll = NextNumber()`. If roll < 0.15 → (`NextNumber() < 0.6` ? HighTech : Service).
    - Otherwise by class: Gas Giant → Refinery. Ice Giant → roll < 0.6 ? Refinery : Extraction. Ocean World → Agricultural. Ice World → Extraction. Volcanic Body → roll < 0.55 ? Industrial : Extraction. Anything else → roll < 0.55 ? Extraction : Industrial.
  - `name = NAMES[NextInteger(1,45)] .. " " .. SUFFIX[economy][NextInteger(1,3)]` (lists at `:88` and `:96`).
  - `elevation = NextNumber(35,50) · (NextNumber() < 0.5 ? -1 : 1)`.
  - `id = "S<seed>-<n>"`.
  - `Model = pickModel(id, economy)` (`:117`): `h = Σ (h·31 + byte) % 1000003` starting at 0, then `Types[economy][h % #types + 1]` (`ShipConfig.Stations.Types`). This deliberately does **not** use the rng.
  - `Shipyard = (economy ∈ {HighTech, Industrial}) and NextNumber() < 0.6`. The `and` short-circuits, so this draw happens **only** for those two economies, before the Angle draw.
  - `Distance = floor(planet.radius·2.1 + 2200)` (Roblox radius; ×S in the port, `03-world.md` §2.5), `Angle = NextNumber(0,360)`, `Description = BLURB[economy]` (`:105`).
- The order of rng calls is part of the contract. Keep it exactly when porting.

**Lookup.**
- `forSystem(i)` returns the base list plus player stations (`setPlayerStations`).
- `find(i, id)` searches one system. `findAnywhere(id)`: "SOL-" → Sol, "P<digits>" → loaded player stations, "S<seed>-<n>" → the system with that seed.
- `getBodies(i)` (`:336`) returns name → `{class, starClass, ring, landable, home (= system 1), radius, parent}`. Moons get `class="Moon"`. Bodies without `landable=true` (for example gas giants) are not landable. In Sol, Earth, Venus, Mars, Mercury and all moons are landable.

### 2.2 ShipStationLore: accent colours, operators, news (`ShipCore/ShipStationLore.lua`)

- **Sol entries** (`:19`, colours in RGB):

  | Station | Accent | Operator |
  |---|---|---|
  | Meridian | (80,170,255) | HALDEN-MERIDIAN TRUST |
  | Tranquility | (255,170,70) | TRANQUIL YARDS COLLECTIVE |
  | Ares | (255,96,70) | ARES DEEP MINING CO. |
  | Galilean | (250,210,90) | GALILEAN FUEL COMBINE |
  | Titan | (110,230,140) | TITAN GREENWORKS |
  | Helios | (255,130,40) | HELIOS FOUNDRY GROUP |

- **Generated stations** (`get`, `:46`): `h = 7; h = (h·31 + byte) % 1000003` over the id. `Accent = ACCENTS[h % 6 + 1]` and `Operator = OPERATORS[(h // 7) % 6 + 1]` (lists at `:29` and `:33`). Test vector: "S12345-1" gives h = 176118, accent #1, operator #2. "P1" gives h = 9256, accent #5, operator #3.
- `News` (`:104`) holds 7 lines that feed the hub ticker. `DeckTitles`, `Moods`, `Bar` and `Shops` belong to the decks area.
- Parts with the attribute `Accent = true` take the station accent colour at spawn (`ShipStation.lua:613`).

### 2.3 Station models in `Assets.rbxmx` (data assets of this area)

Stream-parsed with `xml.etree.iterparse` + `el.clear()`, which takes 8 s. The durable source is `roblox/source/Assets.rar` (`bsdtar -xf roblox/source/Assets.rar`). The copy used for this spec was `/tmp/claude-0/-home-user-TestAi/0e57cf1b-6be7-551b-bef0-2494b470412a/scratchpad/assets/Assets.rbxmx` (219 MB). There are 35,431 instances under `Assets/Stations` and `Assets/Construction`. Every model's `PrimaryPart` is `Root` at the identity CFrame, so all coordinates below are already root-local. Attributes are decoded from `AttributesSerialize`: a u32 count, then for each entry a u32-length name, a type byte and a value. The types seen are 0x02 string, 0x03 bool, 0x06 double, 0x11 Vector3.

#### 2.3.1 Shared contract (identical in all six types)

| Item | Value |
|---|---|
| Model attributes | `StationType`, `BuiltBy`, `Radius`, `HangarSize` = (360,150,690), `HangarCentre` = (0,0,0), `MouthSize` (varies, see 2.3.2), `PartCount` |
| `Approach` folder (invisible parts) | Outer (0,0,-1270), Mouth (0,0,-370), Inner (0,0,-320) |
| `Pads` folder | Pad1..Pad6, Part 110 × 2 × 130 at (85,-74,-130), (85,-74,30), (85,-74,190), (-85,-74,-130), (-85,-74,30), (-85,-74,190). Rotation is identity, so `LookVector` is -Z, towards the mouth. Attribute `PadIndex` 1..6. Pad top = y -73. |
| Pad children | `Lights` (8 Neon parts: 4 `EdgeLight` 0.8 × 0.3 × 116 / 96 × 0.3 × 0.8 strips + 4 `CornerLight` 4 × 2 × 4 cubes, colour (176,204,236) in the file and recoloured at runtime). `Markings` (SurfaceGui Top, 5 px/stud, LightInfluence 0.35, 153 Frames: amber (240,168,18) 45° hazard stripes clipped to the border, ring outlines via UIStroke 6 px amber / 3 px and 2 px grey (214,212,204)). `Number` (an invisible 30 × 0.1 × 54 part turned 90°, SurfaceGui `Display` Top, 3.5 px/stud, LightInfluence 0.4, TextLabel `Text` "01".."06", Michroma, scaled, (214,212,204)). |
| `Sign` part | SurfaceGui `Display` on Front, LightInfluence 0 (self-lit), Brightness 1.4, TextLabel **`Name`**. Its text is replaced at spawn with `def.Name` (`ShipStation.lua:630`). Ring: 244 × 26 × 1 at (0, 97, -370.5). |
| Colliders | Invisible (Transparency 1) Parts with attribute `Collider = true`, magenta (255,0,255) in Studio. The only parts used for collision. All visual parts are non-colliding. |
| Pad colours (runtime) | idle (70,120,160), assigned (255,170,60) blinking, clamped (120,220,150) (`ShipConfig.Stations`) |

#### 2.3.2 Per type

Counts: BaseParts (including Root and invisible ones), primitive shapes, WedgeParts, SurfaceGui decoration, lights, and special attributes.

| Model | Used by | Radius attr | Mouth size | Bounding box (m, root space) | Parts | Block/Cyl/Ball/Wedge | Invisible | SurfaceGui / Frame / TextLabel | Spot/Point/Surface lights | Colliders | Accent | Blink | PAPI |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RingStation | Helios, default/fallback | 693 | 280 × 130 × 35 | 1208 × 1164 × 1240 | 793 | 737/52/0/4 | 87 | 122 / 4440 / 27 | 14/10/0 | 66 (Block) | 0 | 34 | 0 |
| GardenStation | Titan, Agricultural | 1541 | 314 × 150 × 35 | 1903 × 1413 × 2202 | 1399 | 1260/89/50/0 | 191 | 165 / 5602 / 12 | 8/2/7 | 172 (Block) | 104 | 28 | 4 |
| FoundryStation | Tranquility, Industrial/Extraction | 1135 | 300 × 150 × 117 | 2162 × 458 × 1627 | 1453 | 1307/87/41/18 | 322 | 174 / 4016 / 22 | 10/9/3 | 311 (Block) | 23 | 41 | 4 |
| SpireStation | Meridian, HighTech/Service | 1506 | 360 × 150 × 75 | 686 × 2147 × 2332 | 1126 | 1022/59/21/24 | 232 | 278 / 4680 / 32 | 20/0/2 | 222 (Block) | 117 | 41 | 4 |
| HollowStation | Ares, Extraction | 1247 | 298 × 148 × 135 | 2156 × 1451 × 1553 | 1588 | 1390/95/12/91 | 352 | 49 / 1884 / 19 | 14/4/6 | 324 (Block) | 10 | 46 | 4 |
| RefineryStation | Galilean, Refinery | 1368 | 300 × 143 × 87 | 2308 × 2001 × 1135 | 1191 | 783/318/76/14 | 133 | 158 / 4779 / 57 | 14/6/2 | 111 (77 Block, 14 Cylinder, 13 Ball, 7 Wedge) | 12 | 65 | 4 |
| Construction/Site | build sites | 697 | none | 1163 × 1164 × 1236 | 658 | 619/26/9/4 | 50 | 1 / 2 / 2 | 4/2/0 | 49 (Block) | 0 | 9 | 0 |

Main materials:

| Model | Materials |
|---|---|
| Ring | Metal 354, SmoothPlastic 224, Neon 190, DiamondPlate 20, Glass 4, ForceField 1 |
| Garden | SmoothPlastic 869, Neon 324, LeafyGrass 104, WoodPlanks 32, Metal 28, Wood 19, Concrete 8, Ice 7 |
| Foundry | Metal 541, SmoothPlastic 534, Neon 316, CorrodedMetal 31, DiamondPlate 13, Concrete 13 |
| Spire | SmoothPlastic 502, Neon 429, Metal 182 |
| Hollow | SmoothPlastic 409, Neon 277, Metal 196, Slate 166, Rock 126, Concrete 126, Plastic 106, Basalt 83, CorrodedMetal 37, Pebble 30 |
| Refinery | SmoothPlastic 535, Metal 370, Neon 256, DiamondPlate 18, Glacier 12 |
| Site | Metal 407, SmoothPlastic 184, Neon 47 |

Top-level groups:

| Model | Groups |
|---|---|
| Ring | Hub 504 (Hangar 279, Shell 84, Front 34, Colliders 30, Rear 25, Modules 20, Booms 20, Tower 12), Ring 190 (spins), Pads 60, Lights 34, Approach 3, Sign |
| Garden | Hub 1118 (Hangar 219, Colliders 151, Ribs 132, Gate 86, Wing1–4 ~80 each, Details, Shell, Porch, DrumFrame, RoofGear, Boom, NavBoards), Drum 178 (spins), Radar 6 (spins), Pads, Lights 32, Sign |
| Foundry | Hub 1234 (Hull4471 139, SlipA 134, SlipB 130, Booms 66, Hull4472 65, CranesA/B, HangarCranes, GateFront, HangarRoof, Shell*, FurnaceHouse, OreDock, RegolithShield …), WeldRingA 56 and WeldRingB 40 (spin), CommsDish 12 (spins), Pads, Lights 46, Sign |
| Spire | Hub 813 (Tower 248, Colliders 172, PodiumSides, MouthFrame, Walls, PadLights, Floor, RoofCraft, Billboards, ApproachPier, Ceiling …), HaloLow/HaloMid/HaloHigh 64 each (spin), Lighthouse 7 (spins), Radar 5 (spins), Pads, Lights 44, Sign |
| Hollow | Hub 1404 (Colliders 322, RockDetail 151, Bore 138, RockShell 128, Cavern 125, CavernDressing 104, BeltDetail 92, MassDriver 87, RockFront 84, WheelWorks 60, Headframes 55, BoreDeck, Belt), BucketWheel 58, Sheave1–3 2 each, Radar 3 (all spin), Pads, Lights 52, Sign |
| Refinery | Hub 1029 (Hangar 149, Mouth 113, Colliders 109, Block 94, SpineEast/West 87 each, Tanks 77, HangarRoof 73, Columns 67, HangarBack 51, Flare 47, Booms 40 …), Centrifuge 14 and CommsDish 7 (spin), Pads, Lights 76, Sign |
| Site | Base 141 (scaffold, keel, office, mast), Stage1 170 ("FRAME"), Stage2 78 ("CORE"), Stage3 163 ("HABITAT RING"), Stage4 104 ("DOCKING BAY"), Sign |

**Rotating groups.** Any Model with the attribute `SpinDegreesPerSecond`, plus the Model named `Ring` (2°/s about Z through the root when it has no attribute) (`ShipStation.lua:418`). `SpinAxis` is a root-space Vector3 (default Z) and `SpinCentre` is root-local (default 0). Groups are never nested, and a random start angle is used.

| Model | Group | deg/s | Axis | Centre |
|---|---|---|---|---|
| Ring | Ring | 2 | Z (default) | (0,0,0) |
| Garden | Drum | 3 | X | (0,220,830) |
| Garden | Radar | 12 | Y | (0,313,394) |
| Foundry | CommsDish | 6 | Y | (150,176,-170) |
| Foundry | WeldRingA | 3 | X | (580,0,-120) |
| Foundry | WeldRingB | -4 | X | (-580,0,-120) |
| Spire | HaloLow | 2 | Y | (0,0,520) |
| Spire | HaloMid | -3 | Y | (0,0,520) |
| Spire | HaloHigh | 4 | Y | (0,0,520) |
| Spire | Lighthouse | 36 | Y | (0,0,520) |
| Spire | Radar | 20 | Y | (-128,0,330) |
| Hollow | BucketWheel | 6 | (0.289,0,0.957) | (-855,-120,-139) |
| Hollow | Sheave1 | 20 | (0,0.120,0.993) | (264.9,794.0,5.7) |
| Hollow | Sheave2 | 20 | (0,0.075,0.997) | (-17.8,791.4,97.5) |
| Hollow | Sheave3 | -20 | (0,0.075,0.997) | (-282.9,774.6,99.9) |
| Hollow | Radar | 12 | (0.042,0.996,0.084) | (73.3,637.4,242.4) |
| Refinery | Centrifuge | 10 | X | (0,470,-110) |
| Refinery | CommsDish | 15 | Y | (-215,0,-160) |

Colliders inside a spinning group spin with it: Ring 36, Drum 20, Halos 16 each, WeldRings 20 and 12, and a few more.

**Lights.**
- *Blinkers* have the attributes `Blink` = period in s, `BlinkPhase` = delay in s (random if missing), and `BlinkDuty` (default 0.5, clamped to 0.02–0.98).
  - Ring: 10 mouth `GuideLamp` 10 × 8 × 1.2 at x = ±175, y = -52..52 step 26, z -372.2, period 1.0, phase 0..0.4 step 0.1 (the chase runs upwards). Green (60,255,120) is at +X and red (255,46,36) at -X. An inbound pilot faces +Z, so green is on their **left**, as the Spire sign says: "GREEN ON YOUR LEFT". 16 `ApproachLight` 7 × 4 × 7 at x = ±160, y = -77, z = -652..-414 step 34, period 1.4, phase 0..0.84 step 0.12, so they run towards the hangar. Beacons: TowerBeacon, MastBeacon, NavLight ±472 green/red, RadiatorTip orange (255,150,40), RearBeacon white, CabBeacon.
  - Spire: 12 GuideLamps (x = ±200, z -424.6, period 1.2), CentreLamps (255,160,50), 12 cylinder **RabbitStrobes** (white (236,242,255), period 0.5 s, duty 0.15, phase 0..0.458 step 1/24 s) along a pier from z = -1460 to -540 at y = -138.5, Obstruction/Keel/Board beacons.
  - Other types have similar sets; Garden, Foundry and Refinery mostly use short duty 0.08–0.15 strobes.
- *PAPI* lamps have the attribute `Papi` = threshold in degrees. Every type except Ring has 4, with thresholds +1.5/+0.5/-0.5/-1.5. They sit right of the mouth at x ≈ +180..+333 and z ≈ -375..-479:

  | Model | Size | x | z |
  |---|---|---|---|
  | Garden | 9 × 8 × 1.2 | 252..297 | -374.6 |
  | Foundry | 1 × 6 × 6 | 185..209 | -464.4 |
  | Spire | 11 × 8 × 1.2 | 282..330 | -469.6 |
  | Hollow | 6 × 5 × 1 | 303..333 | -478.8 |
  | Refinery | 7 × 6 × 1 | 180..216 | -440 |

  None of them carries a Light child in the file.
- *Real lights* (not shadow-casting except 2 + 2 Spire/Refinery spots):
  - Ring: 14 SpotLights (brightness 5, range 60, (214,228,255), 70°), 10 PointLights (1.8–2.2, 60).
  - Spire: 20 SpotLights (12/8/3/2.5, range 120, warm/cool white, 50–120°).
  - The others have 17–24 each. They are listed per model in the converter output.
- *Beam*: Spire `Lighthouse/Lens/LightCone`, two copies. Attachment BeamStart (0,0,0) to BeamEnd (0,-124.7,-586.9) in lens space, Width0 6, Width1 80, colour (255,236,200), transparency keys 0 → 0.55, 0.6 → 0.86, 1 → 1, LightEmission 1, LightInfluence 0, FaceCamera, 10 segments. It turns with the Lighthouse at 36°/s.

**SurfaceGui "paint".** All of it uses `SizingMode = PixelsPerStud` (1–12 px/stud) with CanvasSize 800 × 600 (ignored in that mode).

| Model | Frames | Rotated | Clipping parents |
|---|---|---|---|
| Ring | 4440 | 1334 | 35 |
| Garden | 5602 | 844 | 0 |
| Foundry | 4016 | 2327 | 90 |
| Spire | 4680 | 61 | 1 |
| Hollow | 1884 | 1507 | 42 |
| Refinery | 4779 | 2721 | 70 |

- LightInfluence varies from 0 (self-lit signs, window lights) to 1 (lit like the surface).
- Typical uses: dark (28,30,34) 4-px mullion bars over Neon window strips (Ring/Segments/Windows*), hangar deck markings, hazard chevrons, crane stripes, solar-panel grids.
- There are 164 UICorners, 134 UIStrokes and 1 UIGradient (Garden).

**Signage texts** (TextLabels).

| Model | Texts |
|---|---|
| Ring | "PAD"/"01".."06" pad boards, "DOCKING BAY  A", "TRAFFIC CONTROL", crate labels "SE-1180", "MED-2", "FUEL CELLS", "CARGO 7" … |
| Garden | "ARBORETUM DOCK", "SEED BANK", "VAULT 7 - OPEN", "FRESH PRODUCE", "DOCK AND TRADE" |
| Foundry | "SLIP 1   HULL 4471", "BAY A/B", "DRY DOCK 1", "OUTBOUND  -  CHECK CLEARANCE" |
| Spire | "DOCKING CONTROL", "ARRIVALS  /  LOBBY", "APPROACH LANE", "FOLLOW THE STROBES · GREEN ON YOUR LEFT", "NOVAK HULLWORKS", "REPAIRS WHILE YOU DOCK", "MEMBER OF THE ORBITAL TRADES GUILD", "TALLOW & BRISK", "PALE HARBOUR SUITES", "SLEEP ABOVE THE CLOUDS" |
| Hollow | "PAD 01".."PAD 06" |
| Refinery | 57 labels: "CRYOGENIC FUEL TERMINAL", tank gauges "LH2 · TANK 1", "72 %", "PRESS 4.2 BAR" … |

- The site's sign has two labels: "CONSTRUCTION SITE" (`Caption`, (255,122,0)) and "NEW STATION" (`Name`, replaced with the station name at runtime).
- Fonts:

  | Font | Labels | Licence |
  |---|---|---|
  | Source Sans Pro | 54 | OFL |
  | Michroma | 51 | OFL |
  | GothamSSm | 32 | proprietary, Roblox-licensed |
  | Oswald | 22 | OFL |
  | Nunito | 6 | OFL |
  | Fredoka One | 6 | OFL |

- **Encoding trap.** rbxmx writes every UTF-8 byte above 127 as its own character reference. "·" is stored as `&#194;&#183;`, which an XML parser decodes to "Â·". Re-encode the text as Latin-1 and decode it as UTF-8.

**Other attributes** that runtime scripts do not read (builder metadata, safe to ignore): `CoverLift`, `Glass` (marks glass panes), `Piece` (Foundry), `BuiltBy`, `PartCount`, `StageName` (Site stages).

**SpecialMesh.** There are 5, all `MeshType = 3` (Sphere) with Scale (1,1,1), Offset 0, on Block parts. They render as ellipsoids filling the part box: the Ring dish 38 × 38 × 8.36, the Foundry reflector 58 × 9 × 58, and the Site dish.

**Shape conventions** (verified on all 209 balls and 726 cylinders):
- A Ball is a sphere of diameter `size.X`, and all three components are equal.
- A Cylinder's axis is **local X**, with length `size.X` and diameter `size.Y` (= `size.Z`).
- A WedgePart's slope rises from the front-bottom edge (-Z, -Y) to the back-top edge (+Z, +Y). The full faces are the bottom (-Y) and the back (+Z).

**Construction site** (`Assets/Construction/Site`):
- No Approach or Pads folders, so no docking. Model attribute `Radius` = 697.
- Base: scaffold, keel chords and braces, an office module with windows, a mast. Blinkers: KeelLight red/green 1.8 s, OfficeLight, MastBeacon (255,122,0) 1.0 s with a PointLight, MastLights, JibTip, DeckLights. There are 4 SpotLights 70° (255,214,170) on `LampFace`.
- Stage1 FRAME: longerons, ribs, braces, portals, mouth frame, drum rings, platforms. Bbox (-219,-132,-375)…(219,133,470).
- Stage2 CORE: hull skins, plates, windows, hangar floor/ceiling.
- Stage3 HABITAT RING: 141 ring parts plus its colliders, at radius ~582, z 168..232.
- Stage4 DOCKING BAY: skins, plates, walls, pillars, dish.
- 49 colliders: 8 in Base, 10/3/22/6 in the stages. Stage colliders only collide once their stage is built (2.4.6).

### 2.4 ShipStation: stations in the world (`ShipClient/ShipStation.lua`)

**Purpose.** Spawns the stations and build sites of the current system. Each frame it places them relative to their host and spins their groups. It also runs blinkers, PAPI and pad lamps, hides models beyond 60 km, gives the ship a moving reference frame ("carry"), returns speed limits and the mass lock, provides pad poses, hangar tests, the pad marker, and hull collision.

**Public API.**

| Function | Notes |
|---|---|
| `new()` | |
| `build(systemIndex, systemFolder)` | `:561` |
| `sync(systemIndex, sites, now)` | `:786` |
| `place(dt, shipPos)` | `:846` |
| `updateLights(entry, shipPos)` | `:897` |
| `getEntries()`, `findById(id)`, `findSite(id)`, `getSites()` | |
| `nearest(pos)` | `:968`, real stations only |
| `setVisible(bool)` | `:983` |
| `getFrame()`, `setForcedFrame(entry)` | `:1007` |
| `getCarry()` | `:1018` |
| `isInHangar(entry, pos)` | `:1026` |
| `getLimit(pos)` | `:1036` |
| `getApproachLimit(pos, vel)` | `:1057` |
| `padPose(entry, pad, ground)` | `:1075` |
| `setPadState(entry, pad, state)` | `:1083` |
| `ShipStation.probeFor(shipModel)` | `:1136` |
| `timeToContact(pos, vel, radius, horizon)` | `:1174` |
| `collide(shipCF, vel, probe)` | `:1198` |
| `remember(shipCF)`, `forget()` | `:1235`, `:1246` |

**State per entry** (`readModel`, `:376`):
- `parts`, `offsets` (root-local CFrames), `spinning` (group index per part, 0 = fixed), `groups` `{speed (rad/s), axis, centre, angle}`, `colliders`, `blinkers` `{part, period, phase, duty, base transparency, on}`, `papi` `{part, threshold (rad), glows, high}`, `pads[index]` `{part, top (root-local CFrame of the pad's top face), lights, state, shown}`, `approach` `{Outer, Mouth, Inner}`.
- `radius` (attribute, default 700), `hangarSize` (default (320,130,640)), `hangarCentre`, `spin` (1, or 0 for sites), `visible`, `distance`, `rootCF`, `offset`, `rotation`, `lastPosition`, `lastRel`, `def`, `host`, `beacon`, `economyName`.
- Sites also carry `site = true`, `record`, `stageParts`, `shownKey`.

#### 2.4.1 Spawning

- **Template choice** (`getTemplate`, `:210`): `Assets.Stations[def.Model]`, falling back to `cfg.Model` = "RingStation", then to a generated placeholder (`buildPlaceholder`, `:89`; a box hangar plus pads, kept only for testing). The site uses `Assets.Construction.Site` or `buildSitePlaceholder` (`:239`).
- **Accent recolour** (`:613`): parts with `Accent = true` take `ShipStationLore.get(def.Id).Accent`.
- **Placement** (`placement`, `:576`). It uses the host's **rest position** (`ShipOrbits.restPosition`, its orbit phase at t = 0), not its current position, so a station's offset never rotates with the orbit:

  ```
  rest   = toWorld(restPosition(host))
  offset = ShipStations.offset(def, rest, star = toWorld(0,0,0))
  look   = -offset.unit                         // the mouth (-Z) faces the host
  up     = |look.y| > 0.95 ? X : Y
  rotation = CFrame.lookAt(0, look, up)
  rootCF = CFrame(host.Position + offset) * rotation   // every frame
  ```

- **Beacon** (`makeBeacon`, `:586`): an invisible 2-stud part that stays even when the model is culled. It is the target for markers, the autopilot and the radar. Attributes: `DisplayName = def.Name`, `BodyClass = "STATION  ·  <ECONOMY NAME>"`, `CollisionRadius = radius`, `StationId`, tag `ShipStation`. A site gets `BodyClass = "BUILD SITE  ·  <STAGE> n/4  ·  BUILDING|AWAITING CARGO"`, `SiteId` and `Owner`.
- `economyName = upper(ShipEconomy.Economies[def.Economy].Name)`, for example "HIGH TECH" or "TOURISM" (the Service economy is named TOURISM).
- **Pad numbers** (`:526`): `Pads/PadN/Number/Display/Text.Text = "%02d"`. **Sign**: `Sign/Display/Name.Text = def.Name`.
- **Sync** (`:786`): adds and removes finished player stations (`ShipStations.getPlayerStations`) and sites (from the galaxy record) whenever they change. Cheap if nothing changed.

#### 2.4.2 Per frame: `place(dt, shipPos)` (`:846`)

```
clock += dt
for entry in all (stations + sites):
  rootCF = (host.Position + offset) * rotation ; lastPosition = position ; beacon.CFrame = rootCF
  distance = |position - shipPos|
  visible = distance < LodDistance (60000)        → model parented/unparented on change
  if visible:
     for group g: g.angle = (g.angle + g.speed * entry.spin * dt) mod 2π
                  groupCF[g] = rootCF * T(centre) * AxisAngle(axis, angle) * T(-centre)
     part i: CF = (spinning[i] > 0 ? groupCF[spinning[i]] : rootCF) * offsets[i]    (one BulkMoveTo)
     updateLights(entry, shipPos)
  zone = (entry == frame) ? ZoneRadius*1.25 : ZoneRadius          // hysteresis 2750 / 2200
  if distance < zone and distance < best → frameEntry
frame = forcedFrame or frameEntry
```

#### 2.4.3 Lights: `updateLights` (`:897`), writes only on change

- **Blinker:** `on = ((clock - phase) mod period) < period·duty`. When off, Transparency becomes `max(base, BlinkTransparency 0.85)`.
- **PAPI:**
  ```
  offset = shipPos - lamp.Position
  angle  = atan2(offset·rootUp, max(offset·rootLook, 1e-3))
  high   = angle ≥ threshold
  ```
  When high the lamp is white (240,244,255), otherwise red (255,46,36). Child lights take the same colour. `rootLook` is the station's -Z, the direction the approach comes from.
- **Pad lamps:** `blinkOn = (clock mod 0.8) < 0.5`. An `assigned` pad shows "assigned" when `blinkOn`, else "dim". Colour: assigned or dim → PadAssigned (255,170,60), clamped → PadClamped (120,220,150), idle → PadIdle (70,120,160). Transparency is 0.7 when dim, 0 otherwise.

#### 2.4.4 Queries

- `isInHangar(entry, p)`: `rel = rootCF⁻¹·p - hangarCentre`, true when `|rel.x| ≤ 180 ∧ |rel.y| ≤ 75 ∧ |rel.z| ≤ 345 + 20`.
- `getLimit(p)` (`:1036`). The nearest real station decides; sites never mass-lock.

  ```
  if none or d > DockingRange (6000): return ∞, lock=false
  limit = MassLockSpeed 260; if d < ZoneRadius (2200): limit = ZoneSpeed 140
  if isInHangar: limit = HangarSpeed 60
  return limit, lock=true (no cruise, no jump), entry
  ```

- `getApproachLimit(p, v)` (`:1057`), used outside the lock. Defined in `ShipGravity`; the generic cruise belongs to the flight area.

  ```
  cosine = (|v|>1 and d>1e-3) ? v·(stationPos - p) / (|v|·d) : 1
  time   = depart + (approach - depart)·clamp(0.5+0.5·cosine, 0, 1)   // CruiseDepartTime 1.5 s, CruiseDistanceTime 3.5 s
  limit  = min(260 + max(d - 6000, 0) / time, CruiseMaxSpeed 3e6)
  ```

  In the port, the cruise cap and the far-field law come from the rebuilt speed-limit chain (`03-world.md` §1.3). This area keeps its station part: 260 m/s at the 6 km boundary, rising with distance and faster when leaving.

- `getCarry()` (`:1018`): `host.Position + offset - lastPosition` of the frame entry. It is applied to the ship **before** thrust (`ShipClient.lua:2439`), so approaches and docking are computed relative to the station. In the frame entry, gravity is zeroed (`ShipClient.lua:2508`).
- `setForcedFrame(entry)`: docking holds its station as the frame in every non-FREE state.
- `padPose(entry, i, ground) = rootCF * pad.top * T(0, ground, 0)`. That is the **ship origin pose** on the pad, nose towards the mouth.
- `setPadState` → state ∈ {idle, assigned, clamped}, and the pad marker is shown only while assigned.
- **Pad marker** (`updatePadMarker`, `:1096`):
  - A BillboardGui of `UDim2.fromOffset(140,46)`, LightInfluence 0, holding a TextLabel of `UDim2.fromScale(1,1)`.
  - Font `Ui.Fonts.UiBold` (Sarpanch Bold), TextSize 18, colour PadAssigned, TextStrokeTransparency 0.4, no background, text `"PAD %02d\n▼"`.
  - Pinned 40 m above the pad along the station up axis, visible up to 6000 m, drawn "solid", that is through walls (`ShipOverlay.pin` + `setSolid`). Target labels make room for it (`ShipMarkers:declutter`).
- `setVisible(false)` (landed / surface session) hides every model and clears `lastRel` and the frame.

#### 2.4.5 Collision

- **Probe** (`probeFor`, `:1136`), from ship model attributes:
  ```
  size   = BoundsSize            (default (42,21,66))
  centre = RootToBoundsCenter    (default (0,-0.5,22.6))
  seat   = Camera.SeatOffset (0,0.5,0)
  radius = max(2, min(size)/2);  c = seat + centre
  alongZ = max(0, size.z/2 - radius);  alongX = max(0, size.x/2 - radius)
  points = {c, c + (0,0,-alongZ), c + (0,0,alongZ)}
           ∪ (alongX > radius/2 ? {c ± (alongX,0,0)} : {})
  ground = (RootToGround or 11) - seat.y           // ship origin height above its gear
  ```
  With the defaults: radius 10.5, 5 points, ground 10.5.
- **`collide(shipCF, vel, probe)`** (`:1198`). Stations are tested only if visible and `distance ≤ radius + COLLIDE_MARGIN (400)`, and only when `lastRel` exists. The test runs in the station's own frame:
  ```
  previous = rootCF * lastRel                 // where the ship was, carried along with the station
  for each probe point p: from = previous*p, move = shipCF*p - from
      Spherecast(from, radius, move) against the entry's colliders → smallest fraction
  hit.position = lerp(previous.Position, shipCF.Position, fraction) + normal*0.05
  hit.speed    = max(0, -vel·normal)
  ```
- **Response** (`ShipClient.lua:2723`):
  - If `hit.speed > CrashSpeed (45)`, crash with cause "STATION".
  - Otherwise slide: set the position to `hit.position`, remove the velocity component into the wall (`cancelVelocityAlong(-normal)`). Play the hit sound if speed > 4 (volume `clamp(speed/45, 0.15, 1)`). Voice "HullImpact" if speed > 12.
  - Not run while docking returns a pose (AUTO/CLAMPING/DOCKED/LAUNCHING).
- `remember(shipCF)` at the end of every frame stores `lastRel = rootCF⁻¹·shipCF` for every near station. `forget()` runs after teleports, landing, ship switch and respawn.
- **`timeToContact`** (`:1174`), used by the collision-alert voice callout:
  - Spherecast along v up to `min(speed·horizon, 1000)` against every visible station with `distance ≤ radius + reach`.
  - Returns `hitDistance/speed`.
  - The callout (`ShipCallouts.lua:575`): checked every 0.15 s when speed ≥ 30 and the ship is not controlled. "CollisionAlert" when t ≤ 2.5 s and `-v·normal ≥ 30`.

#### 2.4.6 Build sites: hologram stages (`addSite` `:649`, `showStages` `:715`)

- Sites have `spin = 0` (nothing turns until the station is finished), no pads and no mass lock.
- Each `StageN` keeps its parts' original material, colour, transparency and query flag. Blinkers inside stage groups are dropped, so they cannot overwrite the hologram.
- `built = ShipResources.builtStages(record, now)`. `building = record.BuildEnds and now < BuildEnds`. The key `built..(B|W)` changes only on transitions.

| Stage state | Look |
|---|---|
| stage ≤ built | original look, collides |
| stage = built + 1 ("plan") | `Material = ForceField`, colour (255,122,0), Transparency `max(orig, building ? 0.2 : 0.55)`, no collision |
| later | invisible, no collision |

- The beacon class becomes `"BUILD SITE  ·  <Stages[current].Name> <current>/4  ·  BUILDING|AWAITING CARGO"`, with `current = min(built+1, 4)`.

### 2.5 ShipDocking: the docking state machine (`ShipClient/ShipDocking.lua`)

**Purpose.** Request, clearance, approach aid, docking computer, touchdown, clamps, docked hold, and launch. Everything is computed in the station's root space, so it stays correct while the station moves. `update()` runs **after** `ShipStation:place`.

**Public API.**

| Function | Notes |
|---|---|
| `new(stations, profile, notices)` | |
| `setProbe(probe)` | |
| `getState()`, `isControlling()`, `isDocked()`, `isBusy()` | `isControlling` = AUTO, CLAMPING, DOCKED or LAUNCHING; `isBusy` = state ≠ FREE |
| `getStation()`, `getPad()`, `getCandidate(pos)` | |
| `reset()` | |
| `request(pos)`, `cancel(msg?)` | |
| `handleDockKey(pos)`, `handleComputerKey(shipCF, vel)` | |
| `getPrompt(pos)` | |
| `wantsGear(shipCF)` | |
| `startDocked(entry, pad)` | |
| `launch()` | |
| `update(dt, shipCF, vel, steering) → pose?, vel?` | |
| `getStatus()` | |

Callbacks: `getFuel`, `onGranted`, `onDocked(entry,pad)`, `onUndocked(entry)`, `onReleased(velocity)`.

**State.** `state`, `entry`, `pad`, `expires` (os.clock of permit end), `probe`, `path` `{points, index, position (root space), speed, yaw, pitch, roll, padYaw}`, `clampFrom` (root-local CFrame), `clampStart`, `dockSent`, `launchPending`, `wrongPadWarned`.

`setState(s)` (`:115`) → FREE clears the forced frame and hides the approach aid. Any other state sets the forced frame to `entry`.

#### 2.5.1 Inputs

- **Dock key** (`handleDockKey`, `:244`):

  | State | Action |
  |---|---|
  | FREE | `request(pos)` |
  | GRANTED or AUTO | `cancel("DOCKING REQUEST CANCELLED.")` |
  | DOCKED | `launch()` |
  | anything else | consumed, no action |

- **Autopilot key** (`handleComputerKey`, `:259`). GRANTED → build the inbound path and switch to AUTO, saying "DOCKING COMPUTER ENGAGED." AUTO → drop the path, back to GRANTED, saying "DOCKING COMPUTER DISENGAGED. YOU HAVE CONTROL." Any non-FREE state consumes the key, so the normal autopilot does not start.
- **Candidate** (`:158`): `stations.nearest(pos)` within `DockingRange` (6000 m).
- **Request** (`:182`): only in FREE with a candidate. REQUESTING, then call `RequestDocking(stationId)`, then wait for the rest of **GrantDelay 1.6 s** ("radio delay").
  - Granted with a valid pad: `pad`, `expires = now + seconds` (600), GRANTED, pad state "assigned", comms "DOCKING REQUEST GRANTED. PROCEED TO PAD %02d.", then `onGranted`.
  - Otherwise: FREE, comms "DOCKING REQUEST DENIED. <reason or NO PADS AVAILABLE>."
  - The result is ignored if the state or station changed in the meantime.
- **Cancel** (`:222`): only from GRANTED, AUTO or REQUESTING. Calls `CancelDocking` (fire-and-forget), sets the pad to idle, clears entry, pad and path, goes to FREE, and sends an optional comms line.

#### 2.5.2 Per frame (`update`, `:556`)

```
FREE: return nil
if entry missing or host gone: reset(); return nil
REQUESTING: return nil
remaining = expires - now
GRANTED:
   remaining ≤ 0                    → cancel("DOCKING PERMISSION EXPIRED.")
   |stationPos - shipPos| > 6000·1.15 (6900) → cancel("DOCKING PERMISSION REVOKED. YOU LEFT THE CONTROL ZONE.")
   touchdown(shipCF, vel)           → beginClamp(shipCF); return shipCF, 0
   guide(shipCF, vel, remaining, false); return nil          // pilot flies
AUTO:
   manual = steering or any of ThrottleUp/Down, StrafeLeft/Right/Up/Down, YawLeft/Right held
   manual → path=nil, GRANTED, computerSays("DOCKING COMPUTER DISENGAGED. YOU HAVE CONTROL."); return nil
   pose, v, done = followPath(dt)
   done → beginClamp(pose); return pose, 0
   guide(pose, v, remaining, true); return pose, v
CLAMPING:
   t = clamp((now - clampStart)/ClampSeconds 1.6, 0, 1); s = t²(3-2t)
   pose = (rootCF*clampFrom):Lerp(padPose, s)     // position lerp + rotation slerp
   t ≥ 1 and not dockSent → dockSent = true; sendDock()
   return pose, 0
DOCKED: return padPose, 0
LAUNCHING:
   pose, v, done = followPath(dt)
   done → comms("YOU ARE CLEAR OF THE STATION. FLY SAFE."), clear, FREE, onReleased(v)
   return pose, v
```

`steering` (`ShipClient.lua:2377`) means the pilot is steering: the normalized cursor deflection is greater than `Autopilot.OverrideDeflection` (0.5), or the Q/E key axis is not 0.

**Touchdown** (`:454`). Test every pad:

```
top  = rootCF * pad.top
rel  = top⁻¹ · shipPos
horizontal = |(rel.x, rel.z)| ; height = rel.y - ground
if horizontal ≤ 26·1.5 (39) and height ≤ 10 and height > -8:
   if pad ≠ assigned: warn once per pad "WRONG PAD. YOU ARE CLEARED FOR PAD %02d."; return false
   return horizontal ≤ 26 and |v| ≤ 12 and shipUp·padUp ≥ 0.94
return false
```

**Begin clamp** (`:478`): `clampFrom = rootCF⁻¹·shipCF`, `clampStart = now`, `dockSent = false`, path = nil, state CLAMPING, pad "clamped", approach aid hidden.

**Send dock** (`:488`): `Dock(stationId, clamp(fuel,0,1))`. Ignored if the state or station changed.
- Success: DOCKED, comms "LANDING CLAMPS ENGAGED. WELCOME TO %s.", then `onDocked(entry, pad)`.
- Failure: pad idle, FREE, comms "DOCKING DENIED. <reason>.".

**Start docked** (`:513`): DOCKED with the pad set to clamped, without comms or audio. This is the game start.

**Launch** (`:520`): only from DOCKED, and not while a launch is already pending. Calls `Undock`.
- Failure: toast "LAUNCH DENIED" with the reason (bad colour).
- Success: pad idle, `startPath(buildOutbound(), padPose, 0)`, LAUNCHING, `onUndocked(entry)`, comms "LAUNCH CLEARED. FOLLOW THE GUIDE LIGHTS OUT.".

**Gear** (`wantsGear`, `:293`): down in CLAMPING, DOCKED and LAUNCHING. In GRANTED or AUTO it is down when the ship is in the hangar and less than 70 m above the pad (in pad space).

#### 2.5.3 Docking computer paths (root space)

Each path is a list of points `{pos, speed, level?, pad?}`.

**Inbound** (`buildInbound`, `:311`). `here = rootCF⁻¹·shipPos`, and `a` = approach points.

```
if not inHangar:
   if here.z > a.Mouth.z - 60  (= -430: beside or behind the mouth plane):
      lateral = (here.x, here.y, 0); out = |lateral|>1 ? lateral.unit : X
      R = max(entry.radius·1.3, |lateral|)
      + {out·R + (0,0,here.z), 170}, {out·R + (0,0,a.Outer.z), 170}      // go round, outside the hull
   + {a.Outer, 170}, {a.Mouth, 70}, {a.Inner, 34, level}
+ {padTop * (0, ground + HoverHeight 34, 0), 34, level}
+ {padTop * (0, ground, 0), FinalSpeed 5, level, pad}
```

**Outbound** (`buildOutbound`, `:345`):

```
{padTop*(0, ground + LaunchHeight 26, 0), 10 (FinalSpeed·2), level, pad},
{a.Inner, 34, level}, {a.Mouth, 70, level}, {a.Outer, 170}
```

**Start** (`startPath`, `:362`): `local = rootCF⁻¹·shipCF`, `(yaw, pitch, roll) = toAngles(local)`, `speed = min(currentSpeed, points[1].speed)`, `padYaw = atan2(-padLook.x, -padLook.z)`.
- `toAngles(cf)` (`:74`): `yaw = atan2(-look.x, -look.z)`, `pitch = asin(clamp(look.y,-1,1))`. The roll comes from the right vector of `(RotY(yaw)·RotX(pitch))⁻¹·rot`: `roll = atan2(R.y, R.x)`.

**Follow** (`followPath`, `:380`):

```
target = points[index]
toTarget  = |target.pos - position| ; remaining = toTarget + Σ_{j>index} |p_j - p_{j-1}|
nextSpeed = points[index+1]?.speed ?? target.speed ; finalSpeed = points.last.speed ; a = Accel 45
desired = min(target.speed, sqrt(nextSpeed² + 2·a·toTarget), sqrt(finalSpeed² + 2·a·remaining))
speed   = approach(speed, desired, a·dt)                       // linear slew
step = speed·dt ; walk along the polyline consuming points (index++ on arrival)
done = index > #points
// attitude
aim = points[min(index, n)]
desiredYaw = aim.pad ? padYaw : (|aim.pos-position|>1 ? atan2(-dx,-dz) : yaw)
desiredPitch = (aim.pad or aim.level) ? 0 : clamp(asin(dir.y), -60°, +60°)
blend = 1 - exp(-dt / TurnTime 0.9)
yaw += wrap(desiredYaw - yaw)·blend ; pitch += (desiredPitch - pitch)·blend ; roll += (0 - roll)·blend
localCF = T(position)·RotY(yaw)·RotX(pitch)·RotZ(roll)
velocity = rootCF.rotate((position - old)/max(dt,1e-3)) ; return rootCF*localCF, velocity, done
```

Edge cases:
- An inbound path started **inside** the hangar skips the outer points and flies straight to the hover point.
- On release after LAUNCHING (`ShipClient.lua:450`): the cursor is centred, `thrust.velocity = v` (≤ 170 m/s, along the lane towards the planet), and the throttle is set to 0.

#### 2.5.4 Approach aid (`guide`, `:643`)

The information goes to `ShipNotices:docking` every frame. Writes happen only on change.

| Field | Value |
|---|---|
| pad | `"PAD %02d"` |
| distance | Ship to pad pose. ≥ 1000 m → `"%.1f KM"`, otherwise `"%d M"` (rounded). |
| limit | `"LIMIT %d"` from `stations:getLimit`, or empty when unlimited |
| timer | `"%02d:%02d"` of the remaining permit time |
| warn | Status warn, or remaining < 60 s. Colours the status Accent instead of Ink. |

Status, the first rule that matches:

| Situation | Status | Warn |
|---|---|---|
| AUTO | "DOCKING COMPUTER ACTIVE" | |
| in hangar, horizontal to pad > 52 m | "FLY TO PAD %02d" | |
| in hangar, speed > 12 | "SLOW DOWN" | ✓ |
| in hangar, up·padUp < 0.94 | "LEVEL THE SHIP" | ✓ |
| in hangar, horizontal > 26 | "CENTRE OVER THE PAD" | |
| in hangar, otherwise | "DESCEND AND TOUCH DOWN" | |
| outside, local z > Mouth.z - 60 | "GO AROUND TO THE MOUTH" | |
| outside, otherwise | "ENTER THROUGH THE MOUTH" | |

#### 2.5.5 HUD prompt line (`getPrompt`, `:275`)

| State | Prompt |
|---|---|
| FREE with candidate | `"<DockKey>   REQUEST DOCKING   ·   <NAME>"` |
| REQUESTING | `"CONTACTING <NAME> ..."` |
| GRANTED | `"<AutopilotKey>   DOCKING COMPUTER        <DockKey>   CANCEL REQUEST"` |
| AUTO | `"<AutopilotKey>   TAKE THE CONTROLS"` |
| otherwise | nil |

`ShipClient.hudPrompt` (`:1048`) stacks the landing prompt above the docking prompt when both apply.

#### 2.5.6 Comms

`notices:comms(sender, body, seconds)`. The sender is `"<STATION NAME>  ·  TRAFFIC CONTROL"`, shown for 6 s. Flight computer lines use `"FLIGHT COMPUTER"` for 3 s. The voice lines are mapped in `ShipConfig.Voice.CommsLines` (`ShipConfig.lua:2153`):

| Comms line | Voice lines |
|---|---|
| GRANTED | ControlDockingGranted |
| DENIED | ControlDockingDenied |
| ENGAGED | DockingComputerEngaged |
| CLAMPS | Docked |
| LAUNCH CLEARED | ControlClearedToLaunch + LaunchInitiated |
| CLEAR OF THE STATION | DepartureClear |

`ShipCallouts.updateDocking` (`:609`) adds "DockingRequestSent" on entering REQUESTING, and "ControlRequestReceived" if REQUESTING lasts at least `StandByAfter` (2 s), which never happens with the 1.6 s delay.

Audio (`ShipAudio.lua:768`):

| Transition | Sound |
|---|---|
| → CLAMPING | touchdown thump scaled by the previous speed |
| CLAMPING → DOCKED | `DockClamp` |
| → LAUNCHING | `DockRelease` |
| start docked | silent |

### 2.6 ShipNotices: the flight notices used by this area (`ShipClient/ShipNotices.lua`)

This is the display layer that docking, survey, mining and construction write into. It drives `StarterGui.FlightNotices`, whose layout belongs to the HUD spec (positions are proposed in 5.4). Design canvas 1920 × 1080, scaled by `min(w/1920, h/1080)` (`:97`). All writes go through a set-only-on-change cache (`:137`).

| Call | Effect |
|---|---|
| `comms(sender, body, seconds = 6)` | `Comms.Sender`, `Comms.Body`, sound "Comms", voice via `CommsLines` prefix match. Queued until the GUI exists. |
| `toast(title, body, bad, seconds = 4)` | `Toast.Title` (BadColor if bad, else AccentColor), `Toast.Body`, sound "Toast", voice via `NoticeLines[title]` |
| `docking(info or nil)` | `Docking.Pad/Distance/Status/Limit/Timer`. The status is AccentColor if `warn`, else InkColor. |
| `survey(info or nil)` | `Survey.Label`, `Survey.Bar.Fill` width = progress quantized to 2 % steps (`floor(clamp(p)*50+0.5)/50`), `Survey.Value`, `Survey.Range` (GoodColor if good, else MutedColor). Shared by survey and mining. |
| `update()` | hides comms and toast once their time is up |

Default colours (`:27`): Accent (255,170,60), Ink (230,236,240), Muted (140,150,160), Good (120,220,150), Bad (255,90,80).

Notice voice lines (`ShipConfig.lua:2143`):

| Notice | Voice lines |
|---|---|
| "SURVEY COMPLETE" | ScanComplete, DataStored |
| "FIRST DISCOVERY" | ScanComplete, NewDiscovery, DataStored |
| "ALREADY CHARTED" | ScanComplete |
| "CARGO HOLD FULL" | CargoFull |
| "LAUNCH DENIED" | ControlHoldPosition |
| "JUMP BLOCKED" | JumpBlocked |

### 2.7 ShipStationUi: station services window (`ShipClient/ShipStationUi.lua`)

**Purpose.** Fills, shows and wires `StarterGui.StationServices` while the ship is docked, or while the pilot is on foot on a station deck (`onFoot`: CLOSE instead of LAUNCH, plus the DISEMBARK/QUARTERS tiles). The look comes from a GUI builder that is **not in the export**; this module only fills it. Every action is a request to the server, and the window shows the answer.

**API.** `new(playerGui, profile, callbacks)`, `open(entry, pad, onFoot)`, `close()`, `isShown()`, `setLooking(bool)`, `showPage(name)`, `refresh()`, `update(dt)`, `destroy()`.

Callbacks (`ShipClient.lua:374`):

| Callback | Purpose |
|---|---|
| `getFuel()` → 0..1 | current tank |
| `setFuel(f)` | |
| `onShipChanged(id)` | rebuilds the ship (`switchShip`) |
| `onLaunch()` | the decks lift if active, else `docking:launch()` |
| `onClose()` | |
| `onDisembark()` | |
| `onQuarters()` | |
| `canDisembark()` | |
| `canQuarters()` | |
| `getDeck()` | |
| `hasDeck(deck)` | |

**Constants** (`:35`):

| Constant | Value |
|---|---|
| Design canvas | 1920 × 1080 |
| Tabs | Hub, Overview, Market, Cartography, Shipyard |
| Ticker speed | 70 design px/s |
| Market refresh | 30 s while the market page is open; on opening the page if older than 10 s |
| Toast | 3.5 s |

Colour defaults (`:42`, each can be overridden by a Canvas attribute):

| Name | RGB |
|---|---|
| Accent | (255,170,60) |
| Ink | (230,236,240) |
| Muted | (140,150,160) |
| Good | (120,220,150) |
| Bad | (255,90,80) |
| Panel | (14,18,24) |
| Body | (196,170,146) |
| Deep | (140,66,8) |

**Open** (`:365`):
- Resets the market cache, the selection and quantity 1, and sets `shipSelected` to the active ship.
- Plays the open animation (`ShipUi.animateOpen`, 5.1) unless the window is already open, and the "Open" sound.
- Hides the Shipyard tab when `def.Shipyard` is false.
- Footer: LaunchButton visible unless on foot, CloseButton visible on foot.
- Shows the Hub page (Overview if the GUI has no Hub), refreshes and loads the market.
- `profile:onChanged` triggers `refresh()` while open.

**Page switch** (`showPage`, `:439`):
- Shipyard is refused without a shipyard.
- Shows the page, sets its tab's `Focus` visible, and colours the tab label Ink when active, Accent otherwise.
- Market: reloads if older than 10 s. Shipyard: renders, including the preview. Any other page: clears the preview.

**Buttons** (`wire`/`setButton`, `:295`):
- Clicks act only on `Active` buttons. A `Hover` child is shown on mouse-enter, with the "Hover" sound.
- A disabled button has `Active = false`, label TextTransparency 0.55, and its hover hidden.

**Hub** (`renderHub`, `:525`). The tiles show a label and a sub line:

| Tile | Enabled | Sub |
|---|---|---|
| MARKET | always | `"%d GOODS  ·  BEST SELL: %s"` (highest `Sell` in the loaded market), or "PRICES LOADING" |
| CARTOGRAPHY | always | `"%d SURVEY%s  ·  %s"` (count, plural S, total CR), or "NO DATA ON BOARD" |
| SHIPYARD | when the station has one | `"%d SHIPS FOR SALE"` (8), or "NO SHIPYARD HERE" |
| REFUEL | `units ≥ 1` | `missing = (1-fuel)·100`. If missing < 0.5 → disabled "TANK FULL". Else `units = min(missing, floor(credits/4))`, sub = cost `ceil(units·4)` formatted, or "NO CREDITS". |
| DISEMBARK | `canDisembark()` | "YOU ARE ON FOOT" / "NOT AVAILABLE" (no hangar deck) / "WALK THE STATION" / "PLEASE WAIT" |
| QUARTERS | `canQuarters()` | "YOU ARE HERE" / "NOT AVAILABLE" / "YOUR PRIVATE ROOM" / "PLEASE WAIT" |

Cards:

| Card | Contents |
|---|---|
| Commander (port: **PILOT**) | Avatar = Roblox headshot (port: emblem), Name = display name, Handle = "@name", Credits, Stats as RichText `SHIPS  <ink>n</ink>     WORLDS CHARTED  <ink>n</ink>` (owned ships, charted keys) |
| Ship | Name, `"%s  ·  %d T"` (role · DesignMass), fuel bar + `"%d %%"`, cargo bar + `"%d / %d T"` |
| Station | Name, `"<SYSTEM>  ·  <HOST> ORBIT  ·  <ECONOMY>"`, `"OPERATED BY <operator>"`, description |

- **Ticker** (`buildTicker`, `:619`). Built once per open, and again when the market arrives:
  - The 7 news lines are shuffled (Fisher–Yates) and the first 4 taken. A sender (text before ":") is coloured Accent.
  - The market tip goes in front: `"<accent><STATION> MARKET</accent>  BEST PRICE PAID HERE: <ITEM> AT <price>"`.
  - Items are joined by `"     ·     "` with an accent dot.
- **Ticker scroll** (`updateTicker`, `:644`): `x -= 70·dt` design px. It starts at the tag's right edge + 30. When `x < left - width`, it restarts at the ticker width.
- The hub re-renders every 1 s, so the tiles follow deck state.

**Overview** (`renderOverview`, `:668`):
- Description, `"ECONOMY  <NAME>"`, services `"REFUEL  ·  MARKET  ·  CARTOGRAPHY[  ·  SHIPYARD]"`.
- Ship name and info, fuel and cargo bars.
- Data line `"%d SURVEY%s ON BOARD  ·  %s"`, or "NO SURVEY DATA ON BOARD".
- RefuelButton: label "TANK FULL" (disabled, cost empty), or "REFUEL" with `Cost` = the price, or "NO CREDITS".

**Refuel** (`:713`): busy-guarded. `Refuel(stationId, fuel)`:
- ok → `setFuel(result.fuel)`, sound "Buy", toast "REFUELLED" / `"TANK %d %%  ·  %s"`.
- otherwise → "Error" sound and toast "REFUEL FAILED" (bad).

**Market** (`loadMarket`, `:740`; `renderMarket`, `:778`; `renderTrade`, `:833`):
- Rows are cloned per commodity and keep the commodity order.

  | Column | Content / colour |
  |---|---|
  | Name | |
  | Category | |
  | Buy, Sell | grouped digits, no "CR" |
  | Supply, Demand | HIGH → Good, MED → Body, LOW → Muted |
  | Hold | `"%d T"` in Accent, or "-" in Muted |
  | Selected | the selection frame is visible on the selected row |

- The cargo bar shows `"%d / %d T"`.
- Trade panel:
  ```
  free    = max(0, capacity - used)
  maxBuy  = min(free, floor(credits / max(buy,1)))
  maxSell = hold
  most    = max(maxBuy, maxSell, 1)
  quantity = clamp(quantity, 1, most)        // MAX sets ∞ before clamping
  ```
  - Item, Info, `"%d T"`, Total `"BUY %s  ·  SELL %s"` (price × quantity).
  - Minus is enabled when quantity > 1. Plus and Max are enabled when quantity < most.
  - BuyButton `"BUY  %d T"` is enabled when maxBuy ≥ quantity. SellButton `"SELL  %d T"` is enabled when maxSell ≥ quantity.
  - With nothing selected: "SELECT A COMMODITY", and every button is disabled.
- **Trade** (`:869`): busy-guarded `Trade(stationId, id, quantity, isBuy)`:
  - ok → "Buy"/"Sell" sound, toast "PURCHASED"/"SOLD" `"%d T %s  ·  %s"` (price × quantity), quantity back to 1.
  - otherwise → "TRADE FAILED".
  - The market is reloaded either way.
- A row click selects it (quantity 1, sound "Select"). The first item is preselected.

**Cartography** (`renderCartography`, `:896`):
- One row per unsold survey: Body, System, Class, Value.
- `Empty` is visible when there are none. `"TOTAL  %s"`. SellButton "SELL ALL DATA" is enabled when count > 0.
- `SellData` (`:932`): ok → toast "DATA SOLD" `"%d SURVEY%s  ·  +%s"`, otherwise "SALE FAILED".

**Shipyard** (`renderShipyard`, `:964`):
- One card per catalog ship: Name, Role, Price, Status ("ACTIVE SHIP" / "OWNED" / "FOR SALE"), Selected.
- Detail for the selected ship (default: the active ship):
  - Name and Description.
  - RichText stats: `CARGO HOLD  n T`, `SHIP MASS  n T`, `TOP SPEED  n  ·  BOOST n`, `PITCH / YAW  p / y °/S`.
  - Perks (`PERK_LINES`, `:958`): `SURVEY` (SurveyValue → VALUE, ScanRange → RANGE), `FUEL` (FuelBurn → BURN, ScoopRate → SCOOP), `MINING` (MineSpeed → SPEED). Each shows `"+50 % VALUE"` and so on, where `delta = round((perk-1)·100)`; zeros are hidden.
  - The interior tag shows "WALK-ABOARD  ·  n DECKS" or "WALK-ABOARD INTERIOR" when the ship model is `Walkable`.
- Action button:

  | Condition | Button |
  |---|---|
  | active ship | "ACTIVE SHIP" (disabled) |
  | no flight profile or model | "NOT AVAILABLE YET" (disabled) |
  | cargo > 0 | "SELL YOUR CARGO FIRST" (disabled) |
  | owned | "SWITCH TO THIS SHIP" |
  | otherwise | `"BUY  <price>"`, enabled when credits ≥ price |

- `shipAction` (`:1066`) → `BuyShip` or `SwitchShip`:
  - ok → "SHIP PURCHASED" / "SHIP CHANGED" with `"<NAME>  ·  NOW YOUR ACTIVE SHIP"`, then `onShipChanged(id)`.
  - otherwise → "PURCHASE FAILED" / "SWITCH FAILED".
- **Preview** (`showPreview`, `:1097`; `updatePreview`, `:1143`):
  - Clone the ship model and remove the `Cockpit` and any parts with `GearClosed` (the gear is shown down).
  - Camera FOV 30. `centre = bboxCentre - (0, size.y·0.07, 0)`, `distance = |size|·1.06`, `angle₀ = atan2(-0.85,-0.95)`, `angle += 0.25 rad/s`.
  - `eye = centre + (sin a, 0.3, cos a).unit·distance`, looking at `centre`.

**Per frame** (`update`, `:1164`):
- Hide the toast when its time is up.
- Shipyard page: turn the preview. Hub page: scroll the ticker and re-render every 1 s.
- Market page, not busy: reload every 30 s.

**Look-around** (`setLooking`, `:426`): while the right mouse button is held, the Panel and Shade are hidden so the pilot can look at the hangar (`ShipClient.lua:3000`).

### 2.8 ShipEconomy: commodities, prices, survey values, ship catalogue (`ShipCore/ShipEconomy.lua`)

Pure data and functions, shared by both sides.

**Constants** (`:28`):

| Constant | Value |
|---|---|
| StartCredits | 1500 |
| FuelPricePerUnit | 4 |
| RebuyFraction | 0.05 |
| Spread | 0.04 |
| DriftAmount | 0.06 |
| DriftPeriod | 1200 s |

**Commodities** (`:38`). The order is the market order. The table lists Id, NAME, CATEGORY and base CR/t; the `Info` texts are in Appendix B.

| # | Id | Name | Category | Base |
|---|---|---|---|---|
| 1 | Water | WATER | CHEMICALS | 120 |
| 2 | HydrogenFuel | HYDROGEN FUEL | CHEMICALS | 140 |
| 3 | Grain | GRAIN | FOODS | 260 |
| 4 | SyntheticMeat | SYNTHETIC MEAT | FOODS | 340 |
| 5 | ConstructionAlloys | CONSTRUCTION ALLOYS | METALS | 540 |
| 6 | Copper | COPPER | METALS | 680 |
| 7 | ConsumerGoods | CONSUMER GOODS | CONSUMER ITEMS | 720 |
| 8 | MedicalSupplies | MEDICAL SUPPLIES | MEDICINES | 920 |
| 9 | Titanium | TITANIUM | METALS | 1060 |
| 10 | RoboticsParts | ROBOTICS PARTS | MACHINERY | 1420 |
| 11 | Microprocessors | MICROPROCESSORS | TECHNOLOGY | 1820 |
| 12 | RareIsotopes | RARE ISOTOPES | MINERALS | 2650 |
| 13 | WaterIce | WATER ICE | RAW MATERIALS | 90 |
| 14 | MineralOre | MINERAL ORE | RAW MATERIALS | 160 |
| 15 | MetalOre | METAL ORE | RAW MATERIALS | 190 |
| 16 | Helium3 | HELIUM-3 | GASES | 700 |
| 17 | PreciousOre | PRECIOUS ORE | RAW MATERIALS | 1100 |

**Economies** (`:86`). The factor applies to the base price. Produced goods have a factor below 1 (HIGH supply, LOW demand), consumed goods above 1 (LOW supply, HIGH demand), and anything else is 1 (MED/MED). An unknown economy id falls back to Industrial.

| Id | Name | Produces (factor) | Consumes (factor) |
|---|---|---|---|
| HighTech | HIGH TECH | Microprocessors .72, RoboticsParts .80, MedicalSupplies .84 | Titanium 1.30, Copper 1.26, Grain 1.28, SyntheticMeat 1.24, Water 1.32, ConsumerGoods 1.18, RareIsotopes 1.22, PreciousOre 1.30, Helium3 1.28 |
| Industrial | INDUSTRIAL | ConstructionAlloys .74, ConsumerGoods .78, RoboticsParts .88 | Titanium 1.24, Copper 1.30, HydrogenFuel 1.28, Microprocessors 1.27, Water 1.20, Grain 1.18, MetalOre 1.30, MineralOre 1.30, PreciousOre 1.18 |
| Extraction | EXTRACTION | Titanium .72, Copper .74, RareIsotopes .80, Water .78, MetalOre .70, MineralOre .72, WaterIce .72, PreciousOre .82 | ConstructionAlloys 1.30, Grain 1.32, SyntheticMeat 1.30, MedicalSupplies 1.26, ConsumerGoods 1.22, RoboticsParts 1.24 |
| Refinery | REFINERY | HydrogenFuel .70, Copper .86, Titanium .88, Helium3 .78 | RoboticsParts 1.28, Grain 1.26, SyntheticMeat 1.28, MedicalSupplies 1.22, ConstructionAlloys 1.26, ConsumerGoods 1.20, MetalOre 1.32, MineralOre 1.24, WaterIce 1.28, PreciousOre 1.22 |
| Agricultural | AGRICULTURAL | Grain .70, SyntheticMeat .74, Water .82 | MedicalSupplies 1.28, RoboticsParts 1.25, ConsumerGoods 1.30, HydrogenFuel 1.24, Microprocessors 1.22, ConstructionAlloys 1.20, WaterIce 1.30, MineralOre 1.15 |
| Service | TOURISM | ConsumerGoods .86 | Grain 1.30, SyntheticMeat 1.32, Water 1.25, MedicalSupplies 1.24, RareIsotopes 1.18, Helium3 1.20 |

**Price formula** (`hash01`, `:129`; `drift`, `:138`; `getPrice`, `:149`):

```
hash01(s): h = 5381; for each UTF-8 byte b: h = (h·33 + b) mod 4294967291; return (h mod 100003) / 100003
drift(station, item, now):                                    // now = Unix seconds
   t = now / 1200; step = floor(t); f = t - step
   a = hash01(station + "|" + item + "|" + step)·2 - 1         // step printed as an integer, e.g. "1500000"
   b = hash01(station + "|" + item + "|" + (step+1))·2 - 1
   s = f²(3 - 2f); return (a + (b - a)·s) · 0.06
mid  = base · factor · (1 + drift)
buy  = max(1, round(mid · 1.04)); sell = max(1, round(mid · 0.96))   // round = half away from zero
```

`getMarket(station, economy, now)` returns all 17 rows in order: `{Id, Name, Category, Info, Buy, Sell, Supply, Demand}`. Kotlin caveat: `kotlin.math.round` rounds half to even. Use `floor(x + 0.5)` for these positive values. Test vectors are in Appendix A.

**Survey value** (`surveyValue`, `:214`):

```
base = SurveyValues[class] or 800
Star: base ·= StarFactor[starClass or "G"]    (M 1, K 1.1, G 1.25, F 1.45, A 1.8, B 2.6, O 4)
ring: ·1.35 ; landable: ·1.2 ; home (Sol): ·0.3
value = max(10, round(base/10)·10)
```

SurveyValues:

| Class | Value |
|---|---|
| Terrestrial World | 12000 |
| Ocean World | 7500 |
| Greenhouse World | 3200 |
| Gas Giant | 2600 |
| Ice Giant | 2300 |
| Volcanic Body | 1400 |
| Ice World | 1100 |
| Rocky Body | 900 |
| Moon | 500 |
| Star | 1800 |

The server then applies `·perk SurveyValue` and `round/10·10`, then the first-discovery bonus (not for home bodies) `·1.5` and `round/10·10` (`ShipCommerce.lua:434`).

**Ship catalogue** (`:236`). Order and prices; the 2026-11-06 note says all prices were cut by about 30 %.

| Order | Id | Role | Price | Description (in the shipyard) |
|---|---|---|---|---|
| 1 | Varrow | LIGHT MULTIPURPOSE | 22,000 | "QUICK, FORGIVING AND CHEAP TO RUN. …" |
| 2 | Torrin | LIGHT COURIER | 26,000 | "THE FASTEST SMALL SHIP IN THE YARD …" |
| 3 | Calder | FAST MULTIPURPOSE | 31,000 | "QUICKER THAN A VARROW …" |
| 4 | Lochran | LONG-RANGE EXPLORER | 39,000 | "A LONG-RANGE SURVEYOR FROM MERE & CAIRN …" |
| 5 | Brannock | MEDIUM FREIGHTER | 55,000 | "FOUR TIMES THE HOLD OF A VARROW …" |
| 6 | Kessock | MINING PROSPECTOR | 95,000 | "… FROM TALLAN DEEPWORKS …" |
| 7 | Dornoch | HEAVY MULTIPURPOSE | 140,000 | "… FROM ARDLAW HEAVY YARDS …" |
| 8 | Morven | HEAVY FREIGHTER | 265,000 | "THE BIGGEST HOLD IN THE YARDS …" |

The shipyard also shows these flight values from `ShipConfig.Ships`, which the flight spec owns:

| Ship | Cargo (t) | DesignMass (t) | Top / boost (m/s) | Pitch / yaw (°/s) | Perks |
|---|---|---|---|---|---|
| Varrow | 16 | 370 | 260 / 340 | 38 / 20 | – |
| Torrin | 4 | 275 | 300 / 400 | 40 / 21 | FuelBurn 0.85 |
| Calder | 12 | 363 | 270 / 352 | 36 / 19 | – |
| Lochran | 8 | 356 | 280 / 365 | 34 / 18 | SurveyValue 1.5, ScanRange 1.5, FuelBurn 0.6, ScoopRate 1.5 |
| Brannock | 64 | 760 | 205 / 285 | 24 / 13 | – |
| Kessock | 96 | 930 | 190 / 255 | 20 / 12 | MineSpeed 2 |
| Dornoch | 64 | 1086 | 225 / 295 | 22 / 13 | MineSpeed 1.4, ScanRange 1.25 |
| Morven | 224 | 1640 | 170 / 230 | 15 / 9 | – |

`ShipConfig.perk(ship, name)` returns the factor, or 1 if the ship has none.

**Formatting** (`:306`): `groupDigits(v)` = `floor(|v|+0.5)` with thousands separators ","; a negative value keeps its "-". `formatCredits(v) = groupDigits(v) + " CR"`.

### 2.9 ShipCommerce: docking, trade, refuel, data, shipyard, survey, flight reports (`ShipServer/ShipCommerce.lua`)

In the port this becomes the **local rules engine**: synchronous calls returning `{ok, reason, …}`. Every rule and reason string stays. Rate limits (`Net:Guard` cooldown/burst) and type checks are dropped, except that the docking delay stays in `Docking` (2.5).

**Session state** (not saved): `system` (current system index), `grant {station, pad, expires}`, `docked` (stationId), `pad`. Occupied pads are tracked per station.

| Call (line) | Rules → reply |
|---|---|
| `GetProfile` (`:128`) | Load the profile; `system = Location.System or 1`, clear grant and docked; ensure the galaxy record of the system. If docked at a station that no longer exists → `Location = {System 1, Station HomeStation, Docked true}`. If docked and the station exists → reserve a pad; on success `docked = station`, `pad`. Returns `{profile view (with Charted), now, pad, docked}`. |
| `RequestDocking(stationId)` (`:162`) | "PROFILE NOT LOADED" · "ALREADY DOCKED" · "UNKNOWN STATION" (not in the current system). An existing unexpired grant for the same station returns the same pad and the remaining seconds. Otherwise free your pads and reserve a **random free pad 1..6**; if none → "NO PADS FREE". On success, `grant = {station, pad, now + 600}`, returns `{pad, seconds = 600}`. |
| `CancelDocking` (`:187`) | If there is a grant and the ship is not docked: clear the grant and free the pad. Always ok. |
| `Dock(stationId, fuel 0..1)` (`:196`) | No grant, another station, or expired → "NO DOCKING PERMISSION". Otherwise `docked = station`, `pad = grant.pad`, clear the grant, `Location = {System, Station, Docked = true}`, save the active ship's `Fuel`, `Stats.Dockings += 1`, urgent save. |
| `Undock` (`:217`) | Not docked → "NOT DOCKED". Otherwise `Location = {System, Docked = false, LastStation = docked}`, clear docked and pad, free the pad, urgent save, `onUndock` (decks). |
| `GetMarket(stationId)` (`:237`) | "UNKNOWN STATION" unless the station is in the current system. Does **not** require docking. Rows get `Hold = Cargo[id] or 0`; also returns `now`, `cargoUsed`, `cargoCapacity` (active ship). |
| `Trade(stationId, id, tons 1..2000, isBuy)` (`:261`) | `dockedAt` checks → "NOT DOCKED HERE" / "UNKNOWN STATION". "UNKNOWN COMMODITY". Buy: `used + tons > capacity` → "CARGO HOLD FULL"; `credits < buy·tons` → "NOT ENOUGH CREDITS"; else credits -=, cargo +=, `Stats.Spent`. Sell: `hold < tons` → "NOT ENOUGH IN THE HOLD"; else credits += `sell·tons`, cargo -= (the key is removed at 0), `Stats.Earned`. Always `Stats.Trades += 1`. Returns `{credits, hold, price}`. |
| `Refuel(stationId, fuel)` (`:304`) | `missing = max(0,(1-fuel)·100)`; < 0.5 → "TANK ALREADY FULL"; `units = min(missing, floor(credits/4))`; < 1 → "NOT ENOUGH CREDITS"; `cost = ceil(units·4)`; `newFuel = min(1, fuel + units/100)`, and 1 if `missing - units < 0.5`. Saves Fuel. Returns `{cost, fuel}`. |
| `SellData(stationId)` (`:333`) | Docked here. Sum all `Survey[].Value`; none → "NO SURVEY DATA ON BOARD"; credits +=, `Stats.Earned`, `Survey = {}`. **Charted keys stay set** (the body counts as charted). Returns `{earned, count}`. |
| `BuyShip(stationId, shipId)` (`:369`) | Unknown, missing from `ShipConfig.Ships` or `TestOnly` → "UNKNOWN SHIP". The shipyard check (docked here, station has a shipyard ("NO SHIPYARD HERE"), cargo must be 0 ("SELL YOUR CARGO FIRST")). "ALREADY OWNED", "NOT ENOUGH CREDITS". Then credits -= price, `Ships[id] = {Fuel = 1}`, `ActiveShip = id`. |
| `SwitchShip(stationId, shipId)` (`:394`) | Shipyard check; not owned or TestOnly → "NOT OWNED"; "ALREADY ACTIVE"; then `ActiveShip = id`. |
| `Survey(systemIndex, bodyName)` (`:413`) | System must be current → "WRONG SYSTEM"; "UNKNOWN BODY"; already in `Charted` → "ALREADY CHARTED" (+key). Set `Charted[key] = true` first. `value` = surveyValue → perk → first-discovery bonus (galaxy `discover`). `class` = "CLASS G STAR" or the upper-case class. Append `{System, SystemName, Body, Class, Value, First?}` to `Survey`, `Stats.Surveys += 1`. Returns `{value, class, key, first, discoveredBy, discoveredAt}`. |
| `ReportPosition(sys, x,y,z, lx,ly,lz, fuel)` (`:477`) | Ignored while docked or in another system. `Location = {System, Docked = false, LastStation, Position, Look, Frame = 2}`, saves Fuel. Quiet change (no view push, no urgent save). |
| `ReportJump(sys)` (`:499`) | Ignored while docked. `system = sys`, clear the grant, free the pad, `Location = {System, Docked = false, LastStation}`, `Stats.Jumps += 1`, then galaxy `visit` (first visit). |
| `ReportDestroyed` (`:520`) | Clear the grant (and the pad if not docked). `fee = min(credits, round(shipPrice·0.05))` (Varrow 1,100 CR). `lostCargo = cargoUsed`; `lostData = ΣSurvey.Value`; for each lost entry remove `Charted[key]` (it can be surveyed again); credits -= fee; `Stats.Spent`, `Stats.Losses += 1`; `Cargo = {}`, `Survey = {}`. Returns `{fee, lostCargo, lostData}`. The client shows "REPLACEMENT FEE …  ·  n T CARGO LOST  ·  DATA LOST …" (`ShipClient.lua:1624`). |

Helpers: `ShipCommerce.getDocked(player)`, `getSystem(player)`, `dockedAt(player, id)`, used by the construction server and the decks.

### 2.10 ShipSurvey: scanning a locked body (`ShipClient/ShipSurvey.lua`)

**API.** `new(profile, notices)`, `update(dt, ctx)`, `submit(sys, body, display)`, and the hooks `describe(sys, body) → string?` and `onComplete(value, result)`. The fields `scanning`, `progress`, `target` and `pending` are read by the voice callouts.

**Context** (`ShipClient.lua:3154`):
- `locked` = the locked marker part, `shipCFrame`, `systemIndex`.
- `active` = not travelling, not controlled, not on foot, not on the surface, atmosphere state SPACE, and no crash running.

**Constants:** `SHOW_AFTER_LOCK = 4 s`, `DECAY = 0.8 /s`, survey config in 3.4.

**Range and hold time** (`requirements`, `:65`). `reach = perk(activeShip, "ScanRange")`.

```
Star:   range = max(r·RangeRadii(8)·reach, (HeatRadius or r·1.6)·1.25) ; hold = StarHoldSeconds 6
other:  range = max(r·8, MinRange 2500)·reach ; hold = r ≥ GiantRadius 5000 ? 4·1.5 = 6 : 4
r = SurfaceRadius or CollisionRadius attribute or Size.X/2
```

**Per frame** (`update`, `:87`):

```
scanning = false
if not active or no locked or locked is not a celestial body (Gravity tag): target=nil, progress=0, hide bar; return
if locked ≠ target: target = locked; progress = 0; lockedAt = now
label = "SURVEY  ·  <DISPLAY NAME>"
if profile.isCharted(sys, body):
    show only for 4 s after lock: {progress 1, value "CHARTED", range "DATA ON FILE[  ·  <describe>]", good}
    return
if pending: {progress 1, value "TRANSMITTING", range "", good}; return
altitude = max(0, |body - ship| - r); inRange = altitude ≤ range
angularRadius = deg(asin(clamp(r / max(d,1), 0, 1)))
angle   = deg(acos(clamp(look·toBody.unit)))
aligned = angle ≤ max(MaxAngle 25, angularRadius)
holding = Survey key held (and no text box focused)
scanning = holding and inRange and aligned
progress = scanning ? min(1, progress + dt/hold) : max(0, progress - dt·0.8)
scanning and progress ≥ 1 → submit
hide when not inRange and not holding and 4 s passed since the lock and progress ≤ 0
range text: not inRange → "TOO FAR  ·  <distance(altitude - range)>" ; not aligned → "POINT AT TARGET" ; else "IN RANGE"
value: scanning ? "SCANNING  %d %%" : "HOLD <key>"   ; good = inRange and aligned
```

**Submit** (`:181`): sets `pending`, then `Survey(sys, body)`. Then `pending = false` and `progress = 0`.

| Result | Toast | Also |
|---|---|---|
| ok, first | "FIRST DISCOVERY" `"<NAME>  ·  <CLASS>  ·  <value>  ·  YOUR NAME IS ON THE CHART"` (7 s) | `setCharted(key)`, `onComplete` |
| ok, discovered by another pilot | "SURVEY COMPLETE" `"…  ·  FIRST DISCOVERED BY <name or YOU>"` | `setCharted(key)`, `onComplete` |
| ok, otherwise | "SURVEY COMPLETE" `"…"` | `setCharted(key)`, `onComplete` |
| "ALREADY CHARTED" | "ALREADY CHARTED" | `setCharted` |
| other failure | "SURVEY FAILED" (bad) | |

- `describe` (`ShipClient.lua:692`): "INHABITED  ·  NO MINING", "NO DEPOSITS", or `"<TRAIT>  ·  VALUE <letter>"` from the galaxy record.
- `onComplete` refetches the galaxy record 1.5 s later.

**Sol values**, with all bodies surveyed by the registry and home ×0.3. Lochran values ×1.5 are in parentheses:

| Body | Value |
|---|---|
| Sol | 680 (1,020) |
| Mercury | 320 (480) |
| Venus | 1,150 (1,730) |
| Earth | 4,320 (6,480) |
| Mars | 320 (480) |
| Jupiter, Saturn | 1,050 (1,580) |
| Uranus | 930 (1,400) |
| Neptune | 690 (1,040) |
| every Sol moon | 180 (270) |

Away from Sol, a landable Terrestrial World is worth 14,400, or 21,600 as a first discovery.

### 2.11 ShipMining: mining by hand (`ShipClient/ShipMining.lua`)

**API.** `new(profile, galaxy, notices)`, `update(dt, ctx)`, `setChoice(resourceId?)` (also resets progress), `getChoice()`, `isMining()`, `submit(…)`, the hook `onTon(result)`, and the field `depositInRange` (used by the voice callout "DepositDetected", which waits 0.5 s for a steady signal).

**Context** (`ShipClient.lua:3163`):
- `active` = surface ready, atmosphere state SURFACE, not travelling, not controlled, not on foot, not crashing.
- `body` = the surface-session body, `systemIndex`, `shipCFrame`, `velocity`.

**Constants** (`:31`): `SHOW_AFTER_LAND = 8 s`, `DECAY = 0.5 /s`, `RAY_LENGTH = 800`, `RETRY_DELAY = 2 s`. Beam colour (255,140,40), dust colour (170,130,95).

**Deposit choice** (`depositHere`, `:200`). The first failure wins:

| Check | Reason |
|---|---|
| inhabited | "NO MINING ON INHABITED WORLDS" |
| no galaxy record | "LOADING SURVEY DATA" |
| no body record | "NO SURVEY DATA" |
| body not discovered | "SURVEY IT FROM ORBIT FIRST" |
| no deposits | "NO DEPOSITS HERE" |

Otherwise the chosen deposit (`findDeposit(choice)`) or `bestToMine(deposits)`.

**Per frame** (`update`, `:236`):

```
no body → progress 0, beam off; return    (body change → landedAt = now, progress 0)
holding = Mine key held ; recent = now - landedAt < 8
entry or reason → if no entry: beam off; show {label "MINING  ·  <NAME>", 0, "HOLD <key>", reason, bad} when holding or recent
label = "MINING  ·  <NAME>  ·  <RESOURCE> (<GRADE>)"
height = rayDown(800) against terrain/SurfaceScene ; speed = |v|
problem = used ≥ capacity → "CARGO HOLD FULL"
        | height > 120 → hit ? "TOO HIGH  ·  DESCEND BELOW 120 M" : "TOO HIGH"
        | speed > 40 → "SLOW DOWN" | now < blockedUntil → "STAND BY"
mining = holding and no problem and not pending
progress = mining ? min(1, progress + dt·perk(MineSpeed)/MineSeconds[grade]) : (pending ? progress : max(0, progress - 0.5·dt))
progress ≥ 1 → submit
beam on while (mining or pending) and the ray hit: from ship - shipUp·2 to the hit point
inPosition = hit and height ≤ 120 and speed ≤ 40   (→ depositInRange)
show bar if holding or recent or inPosition or progress > 0 or pending:
   value = pending ? "PROCESSING" : mining ? "MINING  %d %%" : "HOLD <key>"
   range = problem or "HOLD %d / %d T" ; good = no problem
```

**Submit** (`:348`): `Mine(sys, body, resourceId)`.
- ok → `onTon` (sound "Comms" and voice "ExtractionComplete", `ShipClient.lua:717`). If the hold is now full: toast "CARGO HOLD FULL" `"%d T <RESOURCE> FROM <BODY>  ·  SELL IT OR DELIVER IT TO A BUILD SITE"`.
- failure → `blockedUntil = now + 2`, toast "MINING FAILED" with the reason or "NO RESPONSE - TRY AGAIN" (bad).

**Beam visual** (`buildVisual`, `:71`; `showBeam`, `:145`):
- A neon cylinder, length = distance, `pulse = 0.5 + 0.5·sin(24·t)`, width `0.55 + 0.25·pulse`, Transparency `0.15 + 0.2·pulse`.
- An impact point with a PointLight (colour = beam, range 28, brightness `2.5 + 1.5·pulse`).
- Two particle emitters:

  | Emitter | Colour | Light emission | Size | Transparency | Life (s) | Rate (/s) | Speed | Spread | Gravity | Drag |
  |---|---|---|---|---|---|---|---|---|---|---|
  | Dust | (170,130,95) | 0.15 | 2 → 9 | 0.35 → 1 | 1.2–2.2 | 40 | 6–14 | 60° | (0,-4,0) | 1.5 |
  | Sparks | beam colour | 1 | 0.5 → 0 | – | 0.3–0.7 | 60 | 18–36 | 70° | (0,-60,0) | – |

**Server `Mine`** (`ShipServer/ShipConstruction.lua:124`). The first failure wins:

| Check | Reason |
|---|---|
| system must be current | "WRONG SYSTEM" |
| must not be docked | "DOCKED" |
| inhabited | "NO MINING ON INHABITED WORLDS" |
| body must be landable | "LAND ON THE BODY TO MINE IT" |
| body record | "UNKNOWN BODY" / "GALAXY DATA UNAVAILABLE" |
| discovered | "SURVEY THIS BODY FIRST" |
| deposit exists | "NO SUCH DEPOSIT HERE" |
| room for 1 t | "CARGO HOLD FULL" |
| rate limit: `now - lastMine < MineSeconds[grade]·0.8 / perk` | "TOO FAST" |

On success: `Cargo[id] += 1`, `Stats.Mined += 1`. Returns `{resource, hold, cargoUsed, capacity}`. Gas giants are never landable, so only a station can work them.

### 2.12 ShipResources: deposits, ratings, charter, build stages, storage, slots (`ShipCore/ShipResources.lua`)

**Resources** (`:45`):

| Id | Name | Rich trait | Plain trait |
|---|---|---|---|
| MetalOre | METAL ORE | METAL-RICH | METAL DEPOSITS |
| MineralOre | MINERAL ORE | MINERAL-RICH | MINERAL DEPOSITS |
| WaterIce | WATER ICE | ICE-RICH | ICE DEPOSITS |
| Helium3 | HELIUM-3 | HELIUM-3 RICH | HELIUM-3 CLOUDS |
| PreciousOre | PRECIOUS ORE | PRECIOUS-RICH | PRECIOUS ORE |

`Order` is 1..5. Grades: 1 POOR, 2 FAIR, 3 GOOD, 4 RICH, 5 EXCEPTIONAL.

**Deposits.**
- Sol is hand-set (`SolDeposits`, `:86`; listed in Appendix A.3). Earth has none and is **inhabited** (`Inhabited = {"1/Earth"}`).
- Other systems (`roll`, `:166`):
  - `rng = Random(seedOf(sys.."/"..body))`, with `seedOf = djb2 (h = 5381, h·33 + b) mod 2147483647`.
  - Stars get no deposits.
  - `ranges = ClassRanges[class]` (`:114`), or for moons `MoonTypes[rng:NextInteger(1,3)]` (`:126`).
  - For each `{id, lo, hi}`, **always draw**: `u = rng:NextNumber()^1.35`, `grade = min(hi, lo + floor(u·(hi-lo+1)))`. Keep it if grade > 0.
  - Sort with `sort`: by `depositHourly` descending, then resource order.
  - `rng:NextNumber() < 0.08` (BONANZA) → the top deposit gets +1 grade, capped at 5.
  - The galaxy store **freezes** the result on first visit.

**Ratings** (`:146`–`:288`):

```
depositHourly(e) = StationRate[grade] · base(e.Id)           // StationRate = {2,4,6,9,12} t/h
hourly(list) = Σ depositHourly
letter(h) = h ≥ 6000 "A" | ≥ 4000 "B" | ≥ 2500 "C" | ≥ 1200 "D" | > 0 "E" | "-"
trait(list, inhabited):
   inhabited → "INHABITED"; empty → "NO DEPOSITS"
   richest = first entry with the highest grade (scanning in list order)
   richest.grade ≥ 4 → Rich(richest) ; #list ≥ 3 → "MIXED DEPOSITS"
   letter(hourly) == "E" → "POOR DEPOSITS" ; else Plain(top = list[1])
economyFor(list) = empty → "Service" ; list[1] is Helium3 → "Refinery" ; else "Extraction"
bestToMine(list) = argmax base / MineSeconds[grade]           // MineSeconds = {24,18,14,11,8}
```

**Charter and stages** (`:304`): `Charter = 200000`.

| # | Name | Build time | Needs (t) | Info |
|---|---|---|---|---|
| 1 | FRAME | 30 min | MetalOre 16, ConstructionAlloys 8 | "THE SPINE AND SCAFFOLD. EVERYTHING ELSE IS BOLTED TO IT." |
| 2 | CORE | 40 min | MineralOre 16, ConstructionAlloys 12, Water 8 | "REACTOR, LIFE SUPPORT AND THE COMMAND HUB." |
| 3 | HABITAT RING | 50 min | MetalOre 16, Titanium 8, RoboticsParts 4 | "THE SPINNING RING WHERE THE CREW LIVES AND WORKS." |
| 4 | DOCKING BAY | 60 min | Copper 8, Microprocessors 4, MedicalSupplies 4, Grain 8 | "PADS, TRAFFIC CONTROL AND A STOCKED CREW. THEN THE STATION OPENS." |

The total build time is 3 h.

**Station record** (in the galaxy store):

```
{ Id = "P<UserId>", Name, Owner, OwnerId, Host, Economy, Slot, Angle, Elevation, Distance, Founded,
  Stage (1..4), Delivered = {[commodity] = tons}, BuildEnds?, Operational?, OperationalAt?, Storage?, StorageAt? }
```

```
needs(st)        = [] if Operational ; else for each need of Stages[Stage]: {Id, Need, Delivered = min(Need, del[Id]), Missing}
deliver(st, offered, now):  if Operational or BuildEnds → {}
                 for each need: give = min(Missing, floor(offered[Id])) ; Delivered += give ; taken[Id] = give
                 if anything taken and all delivered → BuildEnds = now + Stages[Stage].Seconds
advance(st, now): if Operational or no BuildEnds or now < BuildEnds → false
                 finished = BuildEnds ; BuildEnds = nil ; Delivered = {}
                 Stage ≥ 4 → Stage = 4, Operational = true, OperationalAt = finished, Storage = {}, StorageAt = finished
                 else Stage += 1 ; return true          // one step per call; the server tick and every action call it
isOperational(st, now) = Operational or (Stage ≥ 4 and BuildEnds and now ≥ BuildEnds)
builtStages(st, now)   = Operational ? 4 : (Stage-1) + (BuildEnds and now ≥ BuildEnds ? 1 : 0)
accrue(st, deposits, now): (Operational only) hours = max(0, now - StorageAt)/3600 ; StorageAt = now
                 total = ΣStorage ; rate = ΣStationRate[grade] ; room = max(0, 200 - total)
                 run = min(hours, room/rate) ; Storage[id] += StationRate[grade]·run   // time beyond full is lost
take(st, room):  order = storage ids by base price desc, then id ; per id whole = min(room, floor(storage[id]))
                 storage -= whole (removed when < 1e-6) ; taken[id] = whole ; room -= whole
```

**Names** (`nameOptions`, `:344`). `NameCount = 12`. `rng = Random(seedOf("NAME/"..seed))` with `seed = userId.."/"..sys.."/"..body`. Up to 240 draws of `FIRST[1..40] .. " " .. LAST[1..10]` (lists at `:335`), keeping unique names until there are 12. No free text, so there is nothing to filter.

**Orbit slots** (`slot`, `:537`; `pickSlot`, `:549`). `SlotCount = 8`, `SlotClearance = 2400`.

```
slot(i, r, isMoon) = { Slot = i, Angle = (i-1)·45 + 22.5, Elevation = (isMoon ? 55 : 28)·(i odd ? +1 : -1),
                       Distance = floor(r·2.1 + (isMoon ? 1600 : 2200)) }
pickSlot: host at 0, star at (0,0,-1e7); offsets of all stations and sites on the same host ("others")
          first slot whose offset is ≥ 2400 from every other → slot, else nil ("NO FREE ORBIT LEFT AT THIS BODY")
```

**Station definition from a record** (`stationDef`, `:582`):

```
{ Id, Name, Host, Economy (default "Extraction"), Shipyard = false, Distance, Angle, Elevation,
  Description = "A PRIVATE STATION FOUNDED BY <Owner or AN UNKNOWN PILOT>. " + BLURB[Economy or Service](upper(host)),
  SystemIndex, SystemName, Player = true, Owner, OwnerId, Founded }
```

- `stationDefs(systemRecord, sys, now)` returns the operational ones, sorted by Id.
- `stationId(userId) = "P"..userId`.
- Player stations have **no `Model`**, so they use the default RingStation.

### 2.13 ShipConstruction (client): the survey data and own-station panel (`ShipClient/ShipConstruction.lua`)

**Purpose.** The `StarterGui.Construction` window, toggled by `Keys.StationPanel` (U) unless a text box has focus or the pilot is aboard on foot. Two tabs:
- **SURVEY DATA**: the locked or landed body, its deposits, its value and the charter.
- **MY STATION**: the own build site, or the finished station.

The server decides everything. The clocks run on server time (`galaxy:now()`).

**Context** (`ShipClient.lua:740`): `getSystem`, `getLocked`, `getSurfaceBody`, `getShipCFrame`, `getDockedId`, `findSite`, `canOpen` (notices enabled and not in a jump).

**Constants** (`:42`):

| Constant | Value |
|---|---|
| `REFRESH` | 0.25 s (re-render while open) |
| `CONFIRM_SECONDS` | 4 (second click to confirm) |
| `DELIVER_RANGE` | 3000 m to the site |
| `FETCH_RETRY` | 5 s |
| `SEP` | `"  ·  "` |

Colours (`:50`):

| Name | RGB |
|---|---|
| Accent | (255,122,0) |
| Ink | (242,238,232) |
| Body | (196,170,146) |
| Muted | (150,120,92) |
| Good | (120,220,150) |
| Bad | (255,70,60) |
| Panel | (8,5,3) |
| Deep | (140,66,8) |

Failure toast titles:

| Action | Title |
|---|---|
| FoundStation | "CHARTER REFUSED" |
| Deliver | "DELIVERY FAILED" |
| Abandon | "ABANDON FAILED" |
| Collect | "COLLECT FAILED" |
| SellStorage | "SALE FAILED" |

**Opening.** `openPanel(tab?)` (`:476`): the default tab (`:519`) is MY STATION when you own a station and are docked at it, or have nothing locked/landed, or the body is its host; otherwise SURVEY DATA. Plays the open animation and the "Click" sound. `wantsCursor() = open`.

**Current body** (`currentBody`, `:554`): the surface body, else the locked part if it is a celestial body. The key is the parent model name, else the part name.

**SURVEY DATA** (`renderBody`, `:618`):
- No body:
  - Title = system name, subtitle "NO PLANET OR MOON SELECTED".
  - Message "LOCK A PLANET OR MOON WITH <T>, OR LAND ON ONE, TO SEE WHAT IT HOLDS." and detail "SURVEYED BODIES SHOW THEIR DEPOSITS HERE. WITH 200,000 CR YOU CAN FOUND YOUR OWN STATION ABOVE ONE."
- Subtitle facts: system · CLASS · "MOON OF <P>" · "LANDABLE" / "NO SOLID SURFACE" (not for stars) · "INHABITED".
- Star: "STARS HOLD NO DEPOSITS." / "SURVEY THEM FOR DATA. STATIONS ORBIT PLANETS AND MOONS."
- No galaxy record: "LOADING SURVEY DATA ..." / "THE GALAXY RECORD OF THIS SYSTEM IS ON ITS WAY."
- Not discovered: "NOT SURVEYED YET. LOCK IT WITH <T>, FLY INTO RANGE AND HOLD <G>." / "THE FIRST PILOT TO SURVEY A BODY PUTS THEIR NAME ON THE CHART FOR EVERYONE, AND EARNS MORE FOR THE DATA."
- **Plan view:**
  - Discovery: `FIRST DISCOVERED BY <accent>YOU|name</accent>[  ·  03 NOV 2026]`. The UTC date comes from `Discovered.Time`; Sol's time 0 shows no date.
  - Deposit rows (`DepositTemplate`):
    - Name.
    - 5 grade pips (filled Accent α0, empty Deep α0.4).
    - Grade word.
    - Rate `"%d T/H  ·  <CR>/H"` (StationRate · base).
    - `Selected` marks the mining choice while landed there.
    - Tapping a row while landed calls `mining:setChoice(id)`. Not landed: toast "LAND FIRST" `"LAND ON <BODY> AND HOLD <H> TO MINE <RES> BY HAND."`.
  - Empty text: "INHABITED WORLD. NO MINING AND NO CHARTERS." or "NO DEPOSITS. NOTHING HERE TO MINE OR TO DRILL."
  - ValueCard:
    - `Letter` (A–E, Accent; "-" Muted).
    - `Trait`.
    - `Value` = `"<CR>/H FROM A STATION AT FULL OUTPUT"` or "NOTHING FOR A STATION TO WORK HERE".
    - `Economy` = `"STATION TYPE  ·  EXTRACTION"`.
  - Mine hint, added to the note when the body is landable, has deposits and is not inhabited: landed → " HOVER LOW AND SLOW AND HOLD <H> TO MINE - CLICK A DEPOSIT TO PICK IT."; otherwise " LAND HERE AND HOLD <H> TO MINE BY HAND."
  - Owning a station already hides the stage, name, cost and button rows. Note: "YOU ALREADY HOLD A CHARTER - ONE STATION PER PILOT." + hint.
  - Otherwise:
    - Stage rows: Index, Title, Time ("30 MIN"/"1 H"/"1 H 20 MIN"), Needs "16 T METAL ORE  ·  8 T CONSTRUCTION ALLOYS".
    - Caption `"CONSTRUCTION  ·  3 H BUILD TIME"`.
    - Name row with reroll (cycles 1..12; the index resets to 1 when the body changes).
    - Cost `"CHARTER  <accent>200,000 CR</accent>  ·  YOU HAVE <credits>"`.
    - Note: the reason ("INHABITED WORLD - NO CHARTERS HERE." / "NO DEPOSITS TO WORK HERE." / "YOU NEED <x> MORE FOR THE CHARTER.") or "ONE STATION PER PILOT. IT STAYS IN THE GALAXY FOR EVERYONE, EVEN WHILE YOU ARE OFFLINE." + hint.
    - FOUND button: "FOUND STATION" → armed "CONFIRM  ·  200,000 CR" for 4 s → `FoundStation(sys, body, nameIndex)`. On success, toast "CHARTER GRANTED" `"<NAME>  ·  ORBITING <BODY>  ·  DELIVER STAGE 1 MATERIALS TO START BUILDING"` (8 s), switch to the STATION tab, refetch the galaxy record.

**MY STATION** (`renderOwn`, `:781`):
- No charter: title "NO STATION YET", subtitle "ONE CHARTER PER PILOT". Message "SURVEY A PLANET OR MOON WITH DEPOSITS, THEN FOUND A STATION ABOVE IT ON THE SURVEY DATA TAB." / "THE CHARTER COSTS 200,000 CR. FOUR BUILD STAGES TAKE ABOUT 3 HOURS, AND EACH STARTS WITH A DELIVERY OF MATERIALS. A FINISHED STATION DRILLS ITS HOST AROUND THE CLOCK AND STAYS IN THE GALAXY FOR EVERYONE."
- Record missing: "LOADING STATION DATA ..." (refetch every 5 s) or "YOUR CHARTER HAS NO SITE ON RECORD." / "FOUND A NEW STATION FROM THE SURVEY DATA TAB."
- **Site view** (`renderSite`, `:821`). It uses a probe copy advanced to `now`.
  - Caption "BUILD SITE", title = name, subtitle `"ORBITING <HOST>  ·  <SYSTEM>"`.
  - `"STAGE n / 4"`, stage title and info.
  - Timer:
    - Building: `"BUILDING  ·  1:04:09 LEFT"` in Good. Progress `1 - left/stageSeconds`, fill Good.
    - Waiting: `"AWAITING CARGO  ·  30 MIN TO BUILD"` in Ink. Progress Σdelivered/Σneed, fill Accent.
  - Stages line: done names in Good, current in Accent, the rest plain.
  - Need rows: Name, `"%d / %d T"` (Good when complete), hold `"COMPLETE"` / `"IN HOLD %d T"` (Good if complete or > 0, else Muted), and a bar.
  - Deliver reason, the first that applies:

    | Situation | Note |
    |---|---|
    | building | "THE CREWS ARE AT WORK. THE TIMER KEEPS RUNNING WHILE YOU ARE AWAY, EVEN OFFLINE." |
    | other system | "YOUR SITE IS IN <SYS>. JUMP THERE WITH THE CARGO." |
    | docked | "LAUNCH FIRST, THEN FLY TO THE SITE." |
    | site not spawned | "THE SITE IS NOT IN VIEW YET." |
    | distance > 3000 m | "FLY WITHIN 3.00 KM OF THE SITE (<x> TO GO). LOCK ITS BUILD SITE MARKER WITH <T>." |
    | nothing deliverable in the hold | "NOTHING IN YOUR HOLD FOR THIS STAGE. BUY IT AT A STATION MARKET OR MINE IT." |
    | ready | "READY. DELIVER WHAT YOUR HOLD CARRIES - THE REST CAN FOLLOW LATER." |

  - Buttons: "DELIVER CARGO" (primary) and "ABANDON SITE" (secondary, armed: "CONFIRM - NO REFUND").
  - Deliver success: "CONSTRUCTION STARTED" `"<STAGE>  ·  READY IN <duration>  ·  DELIVERED <cargo list>"` (8 s), or "CARGO DELIVERED" `<cargo list>`.
  - Abandon success: "SITE ABANDONED" "THE CHARTER IS GONE. YOU CAN FOUND A NEW STATION." (bad), back to the body tab.
- **Station view** (`renderStation`, `:919`):
  - Caption "YOUR STATION", title = name, subtitle `"ORBITING <HOST>  ·  <SYS>  ·  <ECONOMY>"`.
  - `"FOUNDED BY <Owner>  ·  OPEN SINCE <date or TODAY>"`.
  - Production rows `"<RES>  (<GRADE>)"` with `"%d T/H"`.
  - Storage accrued to `now` (a probe copy): capacity `"%d / 200 T"` (Bad when ≥ 199.5), bar Accent or Bad.
  - Item rows (≥ 1 t, sorted by base price): Name, `"ABOUT <tons·sell at the own station's price>"`, `"%d T"`.
  - Reason: not docked at the own station → "DOCK AT <NAME> TO LOAD OR SELL THE STORAGE."; empty → "STORAGE EMPTY. THE DRILLS FILL IT AROUND THE CLOCK.".
  - Note: room < 1 → "YOUR HOLD IS FULL - SELL THE STORAGE HERE OR MAKE ROOM FIRST.", else "LOAD WHAT FITS (%d T FREE) OR SELL EVERYTHING AT THIS STATION'S PRICES."; append " STORAGE IS FULL (200 T) - THE DRILLS HAVE STOPPED." when full.
  - Buttons: "LOAD INTO HOLD" (needs room ≥ 1) → toast "LOADED INTO HOLD" `<cargo list>`. "SELL ALL HERE" (secondary) → "STORAGE SOLD" `"%d T  ·  +<income>"`.
- Footer hint: `"<U> CLOSES  ·  <L-ALT> FREES THE MOUSE"`. On the phone: "TAP ✕ TO CLOSE".
- **Requests** (`request`, `:1006`) are busy-guarded. Success plays the "Confirm" sound. Failure shows the toast `FAILED[name]` with the reason. The panel re-renders after each answer.

**Styles.**

| Element | State | Style |
|---|---|---|
| Primary button | enabled | background Accent, text Panel |
| Primary button | disabled | background Deep, text Muted |
| Secondary button | enabled | text Ink |
| Secondary button | disabled | text Muted |
| Tab | active | background Accent α0, text Panel |
| Tab | inactive | background Panel α0.2, text Ink |

### 2.14 ShipServer/ShipConstruction: charter, deliveries, storage (`ShipServer/ShipConstruction.lua`)

Rules, which become local logic. `exclusive(player, fn)` (`:74`) allows one build action at a time ("BUSY - TRY AGAIN"). An error becomes "SERVER ERROR - TRY AGAIN".

- **`ownStation`** (`:90`) fails in this order: "PROFILE NOT LOADED" · "YOU DO NOT OWN A STATION" · "GALAXY DATA UNAVAILABLE" · "STATION NOT FOUND".
- **FoundStation(sys, body, nameIndex 1..12)** (`:182`):
  - Checks in order: "YOU ALREADY OWN A STATION" · "WRONG SYSTEM" · "INHABITED WORLD - NO CHARTERS" · star or unknown body → "STATIONS ORBIT PLANETS AND MOONS" · "GALAXY DATA UNAVAILABLE" · not discovered → "SURVEY THIS BODY FIRST" · "NO DEPOSITS TO WORK HERE" · "NOT ENOUGH CREDITS" · "INVALID NAME".
  - Others = base stations and galaxy stations on the same host. `pickSlot(info.radius or 500, isMoon = info.parent ≠ nil, others)` → otherwise "NO FREE ORBIT LEFT AT THIS BODY".
  - The charter is deducted first and refunded if the write fails.
  - Record: `{Id, Name, Owner, OwnerId, Host, Economy = economyFor(deposits), Slot, Angle, Elevation, Distance, Founded = now, Stage = 1, Delivered = {}}`.
  - Profile: `OwnStation = {Id, System, Body}`, `Stats.Spent += 200000`.
- **Deliver()** (`:277`):
  - Checks: own station, same system ("YOUR SITE IS IN ANOTHER SYSTEM"), not docked ("LAUNCH FIRST"), not operational ("STATION ALREADY COMPLETE"), not building ("CONSTRUCTION IN PROGRESS").
  - **The server never checks the distance**; only the client's 3000 m rule does.
  - `offered[id] = min(missing, cargo[id])`. None → "NOTHING IN YOUR HOLD FOR THIS STAGE". The offer is removed from the cargo first.
  - Under the store write: `advance` + `deliver`. Anything not taken goes back to the cargo. Nothing taken → "NOTHING NEEDED RIGHT NOW".
  - Returns `{taken, stage, buildEnds, now}`.
- **Abandon()** (`:345`): only while not operational ("A FINISHED STATION STAYS"). Removes the record and clears `OwnStation`. A dangling pointer ("STATION NOT FOUND") is also cleared.
- **Collect()** (`:386`): docked at the own station ("DOCK AT YOUR STATION FIRST"), operational ("STATION NOT FINISHED"), room ≥ 1 ("CARGO HOLD FULL"). Then `advance` + `accrue` + `take(room)`. Nothing taken → "STORAGE EMPTY".
- **SellStorage()** (`:427`): the same checks, then `take(∞)`. Income = Σ `getPrice(ownId, economy, id, now).sell · tons`, credits +=, `Stats.Earned`, `Stats.Trades += 1`. Returns `{income, tons}`.
- **Debug hooks** (`:486`), Studio only: `cargo <id> <t>`, `station`, `fill` (deliver everything missing), `skip` (end the build now), `age <hours>` (StorageAt -= hours). Port these as harness/debug commands.

### 2.15 ShipProfile and ShipData: the pilot profile and its persistence

**Client** (`ShipClient/ShipProfile.lua`):
- The profile view, `charted` (a set of `"<sys>/<body>"`), `onChanged` listeners, `call(name, …)` (blocking RPC, never from the render step), `fire(name, …)` (fire-and-forget).
- Getters: `getCredits`, `getCargoUsed`, `getActiveShip`, `getOwnStation`, `getCargo`, `getFuel(ship)`, `isCharted`, `setCharted`.
- The RPC names (`:26`) are the commerce, construction, decks, training and galaxy calls listed in 2.9, 2.14, 8.3 and 6.

**Server** (`ShipServer/ShipData.lua`):
- **Template** (`newData`, `:71`):

  ```
  SchemaVersion = 1, Credits = 1500, ActiveShip = "Varrow", Ships = { Varrow = { Fuel = 1 } },
  Cargo = {}, Survey = {}, Charted = {}, Location = { System = 1, Station = "SOL-MERIDIAN", Docked = true },
  Stats = { Earned, Spent, Trades, Surveys, Jumps, Dockings, Losses, Mined = 0, Created = now },
  Training = { Done = false }          -- plus, when set later: HomeStation (decks), OwnStation {Id, System, Body}
  ```

- **Migrations** (`:94`): an empty list, run in order by `toVersion`.
- **Reconcile** (`:112`):
  - Missing `Training` → `Done = experienced` (any Dockings, Surveys, Trades or Jumps > 0).
  - Fill missing top-level and Stats keys from the template.
  - Empty `Ships` → Varrow. Missing `ActiveShip` in `Ships` → any owned ship.
  - `Credits = max(0, floor)`.
  - Invalid `Location.System` → the template location.
  - Docked at a non-player station that does not exist → the template.
  - A non-table `OwnStation` → nil.
- **View** (`:341`): Credits, ActiveShip, Ships `{id → {Fuel}}`, Cargo, `CargoUsed = ΣCargo`, Survey, Location, Stats, HomeStation, OwnStation, Training, Offline. On first fetch also `Charted` as a list.
- **Change** (`changed(player, urgent, quiet)`, `:394`): marks the profile dirty. `urgent` schedules a save within `URGENT_DELAY` = 8 s. It refreshes the player attributes that the main menu reads (Credits, `Location` text "SOL  /  MERIDIAN STATION" or "SOL  /  IN FLIGHT", ActiveShip, SaveStatus, TrainingDone). Unless `quiet`, it pushes the view.
- **Persistence**: autosave every 90 s, urgent saves within 8 s, save on leave and on shutdown. DataStore session locking (`LOCK_STALE` 420 s, retries) and the owner top-up to 999,999 CR are Roblox-only (8.3).

### 2.16 ShipGalaxy: the shared galaxy store (`ShipClient/ShipGalaxy.lua`, `ShipServer/ShipGalaxy.lua`)

Owned by the maps/galaxy spec, but this area depends on it.

- One record per system:
  ```
  { Version 1, System, Name, FirstVisit {By, UserId, Time}?,
    Bodies {name → {Deposits, Discovered {By, UserId, Time}?}},
    Stations {id → station record}, Updated }
  ```
- `ensure(i)` (server `:370`) creates and **freezes** the deposits of every body (Sol: `Discovered` and `FirstVisit` by "SOL REGISTRY", time 0) the first time a system is entered.
- `discover(player, sys, body)` → `(first, {By, UserId, Time})`. `visit(player, sys)` sets `FirstVisit` once.
- A server tick every 15 s advances due builds.
- The client view hides deposits until a body is discovered, and storage from non-owners.
- Client: `now()` = server time, `fetch(i, timeout)`, `get`, `getBody`, `getStation`, `getSites(i)` (not yet operational, sorted by Id), `refreshStations(i)` (registers newly finished player stations), `onChanged`.
- The **FIRST VISIT** toast (`ShipClient.lua:676`): when the record's FirstVisit is yours and less than 300 s old, "FIRST VISIT" `"<SYSTEM>  ·  NO PILOT HAS BEEN HERE BEFORE. YOUR NAME IS ON THE CHART."` (7 s).

### 2.17 ShipClient integration: where this area is called (`ShipClient.lua`)

| Where | What |
|---|---|
| `:231`–`:238` | `profile = ShipProfile.new(); startInfo = profile:fetch(); galaxy = ShipGalaxy.new(profile)`. The start ship comes from `startView.ActiveShip` if its model exists (`:247`). |
| `:345` | `systems:setFuelFraction(profile:getFuel())` |
| `:352`–`:420` | Create notices, stations, docking (with `ShipStation.probeFor(hull)`), survey and stationUi with its callbacks |
| `:426`–`:459` | Docking callbacks. `onDocked`: `thrust:reset()`, disengage the autopilot, then decks `arrive` or `stationUi:open`. `onUndocked` closes the UI. `onReleased(v)`: centre the cursor, `thrust.velocity = v`, throttle 0. |
| `:501` | Entering a surface session cancels any docking request |
| `:521`, `:1139`, `:1432`, `:1455` | `stations:forget()` after a pose jump |
| `:630` | `stationSpawn(id)`: at `Approach.Outer`, looking at the station root. Used after a crash, or when the start station has no free pad. |
| `:645`–`:663` | Startup: build the system, `galaxy:fetch(i, 8)`, `stations:build`, `stations:sync` |
| `:676`–`:713` | `galaxy:onChanged` → `stations:sync` and the FIRST VISIT toast; `survey.describe`, `survey.onComplete` |
| `:716`–`:764` | mining (`onTon`) and construction (ctx) |
| `:772`–`:800` | Start pose: docked on the pad (`startDocked`), else at the station's outer point, else at the star arrival point |
| `:841` | A jump from the star map is blocked by the station mass lock or busy docking: toast "JUMP BLOCKED" `"STATION MASS LOCK  ·  FLY 6 KM AWAY"` |
| `:868`–`:877` | After a jump: `docking:reset()`, rebuild, `profile:fire("ReportJump")`, `galaxy:fetch(i, 6)`, `stations:build/sync` |
| `:998`, `:1033`, `:1059` | Dock, land and autopilot buttons (2.5.1). Land is ignored while docking controls the ship. |
| `:1048` | HUD prompt stacking |
| `:1108` | `switchShip`: new hull, handling, `probeFor`, `docking:setProbe`, fuel from the profile, `stations:forget()` |
| `:1309`, `:1431`, `:1503`, `:1624` | Crash: the nearest station from host + offset is remembered for respawn; `ReportDestroyed` → loss lines; respawn at `stationSpawn` |
| `:2356`–`:2381` | `controlled = docking:isControlling() or atmosphere:isGuiding()` → thrust input off, boost off; `steering` for the AUTO override |
| `:2439` | carry |
| `:2491`–`:2511` | station limit / mass lock / approach limit; zero gravity in a station frame |
| `:2717`–`:2748` | `stations:place` → `docking:update` (pose override) or `collide` (crash or slide) → `remember` |
| `:2753` | NPC traffic runs only when docking is FREE |
| `:2884` | gear: `docking:wantsGear` |
| `:2924` | audio `dockState` |
| `:2943` | voice callouts get `stationLock` |
| `:2989`–`:3000` | free the mouse while the services UI is open; `setLooking` |
| `:3107` | HUD prompt |
| `:3152`–`:3181` | survey → mining → construction → galaxy refresh every 2 s (`refreshStations` + `sync`) → notices → stationUi update |
| `:3183`–`:3194` | `ReportPosition` every 10 s while free, in space and not crashing |
| `:3208`–`:3222` | After the start fade: notices on; if docked, decks `arrive(…, instant)` or `stationUi:open` |

---

## 3. Tunables

### 3.1 `ShipConfig.Stations` (`ShipConfig.lua:2906`)

| Key | Value | Meaning |
|---|---|---|
| Tag | "ShipStation" | Tag on station and site beacons (markers, autopilot, radar) |
| Model | "RingStation" | Default/fallback model, also used for player stations |
| Types.HighTech | Spire, Ring | Model choice for generated stations, picked by id hash |
| Types.Service | Spire, Ring | |
| Types.Industrial | Foundry, Ring | |
| Types.Extraction | Hollow, Foundry | |
| Types.Refinery | Refinery | |
| Types.Agricultural | Garden, Ring | |
| LodDistance | 60000 m | The full model shows inside this distance; beyond it only the beacon |
| DockingRange | 6000 m | Docking request range and mass-lock radius (no cruise, no jump) |
| MassLockSpeed | 260 m/s | Speed limit inside DockingRange; base of the approach ramp |
| ZoneRadius | 2200 m | Docking zone (frame entry; ×1.25 = 2750 m to leave) |
| ZoneSpeed | 140 m/s | Speed limit inside ZoneRadius |
| HangarSpeed | 60 m/s | Speed limit inside the hangar box |
| GrantDelay | 1.6 s | Radio delay before traffic control answers |
| GrantSeconds | 600 s | Docking permit lifetime |
| TouchdownRadius | 26 m | Maximum horizontal offset from the pad centre (×1.5 = 39 m "over this pad" test) |
| TouchdownHeight | 10 m | Maximum height above the touchdown pose (and > -8) |
| TouchdownSpeed | 12 m/s | Maximum speed at touchdown; above it "SLOW DOWN" |
| TouchdownAlign | 0.94 | Minimum dot product of ship up and pad up (≈ 20°) |
| ClampSeconds | 1.6 s | Clamp pull-in duration (smoothstep) |
| CrashSpeed | 45 m/s | A hull impact faster than this destroys the ship |
| PadIdle | (70,120,160) | Pad lamp colour, idle |
| PadAssigned | (255,170,60) | Pad lamp colour, assigned (blinks 0.5 s on / 0.3 s dim), pad marker text |
| PadClamped | (120,220,150) | Pad lamp colour, clamped/docked |
| BlinkTransparency | 0.85 | Transparency of a blinker when off |

### 3.2 `ShipConfig.Stations.Computer`: the docking computer

| Key | Value | Meaning |
|---|---|---|
| CruiseSpeed | 170 m/s | Speed cap on legs to and around Outer |
| MouthSpeed | 70 m/s | Cap on the leg to the Mouth |
| HangarSpeed | 34 m/s | Cap inside (Inner, hover point) |
| FinalSpeed | 5 m/s | Cap on the last descent onto the pad; ×2 = 10 m/s for the lift-off point |
| Accel | 45 m/s² | Speed slew rate and braking-curve deceleration |
| TurnTime | 0.9 s | Time constant for yaw/pitch/roll easing |
| HoverHeight | 34 m | Hover point above the touchdown pose |
| LaunchHeight | 26 m | Lift-off point above the pad |

### 3.3 Constants in `ShipStation.lua`

| Name | Value | Where | Meaning |
|---|---|---|---|
| COLLIDE_MARGIN | 400 m | `:78` | Collision is tested only within station Radius + 400 |
| PAPI_WHITE / PAPI_RED | (240,244,255) / (255,46,36) | `:80` | PAPI lamp colours |
| HOLO_COLOR | (255,122,0) | `:325` | Hologram colour of the next stage |
| HOLO_WAITING / HOLO_BUILDING | 0.55 / 0.2 | `:326` | Hologram transparency while waiting for cargo / while building |
| default Radius | 700 | `:491` | When the model has no attribute |
| default HangarSize | (320,130,640) | `:493` | When the model has no attribute |
| Ring default spin | 2 °/s about Z | `:420` | Model "Ring" without the attribute |
| hangar Z slack | +20 m | `:1029` | Extra depth in `isInHangar` |
| pad blink | 0.8 s period, 0.5 s on, dim transparency 0.7 | `:926` | Assigned pad lamps |
| pad marker | 140 × 46 px, 18 px Sarpanch Bold, 40 m above the pad, visible ≤ 6000 m | `:1108` | |
| beacon size | 2 × 2 × 2 m, invisible | `:589` | |

### 3.4 Survey (`ShipConfig.Survey`, `:2966`; `ShipSurvey.lua`)

| Key | Value | Meaning |
|---|---|---|
| HoldSeconds | 4 s | Hold time for planets and moons |
| StarHoldSeconds | 6 s | Hold time for stars |
| GiantRadius | 5000 m (Roblox scale!) | Bodies at least this big take 4 × 1.5 = 6 s |
| RangeRadii | 8 | Range above the surface = radius × 8 |
| MinRange | 2500 m | Minimum range above the surface |
| MaxAngle | 25° | Nose-to-centre tolerance, or the angular radius if larger |
| Star range | max(r·8·reach, (HeatRadius or 1.6r)·1.25) | Surveyed from outside the heat zone |
| SHOW_AFTER_LOCK | 4 s | Bar visible after locking even out of range |
| DECAY | 0.8 /s | Progress loss when not scanning |
| Perk ScanRange | Lochran 1.5, Dornoch 1.25 | Range multiplier |
| Perk SurveyValue | Lochran 1.5 | Value multiplier |

### 3.5 Economy (`ShipEconomy.lua`)

| Key | Value | Meaning |
|---|---|---|
| StartCredits | 1500 CR | New pilot |
| FuelPricePerUnit | 4 CR | Per unit; a tank holds `Systems.FuelCapacity` = 100 units |
| RebuyFraction | 0.05 | Replacement fee as a share of the ship price, capped at the credits held |
| Spread | 0.04 | Buy = mid·1.04, sell = mid·0.96, so a round trip at one station always loses money |
| DriftAmount | 0.06 | ±6 % slow price swing per station and item |
| DriftPeriod | 1200 s | A new drift target every 20 min, smoothstep blend |
| SurveyDefault | 800 | Unknown class |
| SurveyRingBonus / SurveyLandableBonus / SurveyHomeFactor / SurveyFirstBonus | 1.35 / 1.2 / 0.3 / 1.5 | Survey multipliers |
| SurveyStarFactor | M 1, K 1.1, G 1.25, F 1.45, A 1.8, B 2.6, O 4 | |

### 3.6 Mining and resources (`ShipResources.lua`, `ShipMining.lua`)

| Key | Value | Meaning |
|---|---|---|
| Grades | POOR, FAIR, GOOD, RICH, EXCEPTIONAL | 1..5 |
| MineSeconds | 24, 18, 14, 11, 8 s | Hand mining per ton by grade, divided by perk MineSpeed (Kessock 2, Dornoch 1.4) |
| StationRate | 2, 4, 6, 9, 12 t/h | Own station output per deposit by grade |
| StorageCap | 200 t | Own station storage, all resources together |
| Mining.MaxHeight | 120 m | Maximum height above ground for hand mining |
| Mining.MaxSpeed | 40 m/s | Maximum speed for hand mining |
| Mining.ServerTolerance | 0.8 | Server accepts the next ton after 0.8 × the mining time |
| SHOW_AFTER_LAND | 8 s | Bar shown after landing without the key |
| DECAY | 0.5 /s | Progress loss when not mining |
| RAY_LENGTH | 800 m | Ground ray length |
| RETRY_DELAY | 2 s | "STAND BY" after a failure |
| SKEW / BONANZA | 1.35 / 0.08 | Deposit roll: grade skew and chance of the top deposit +1 |
| Letters | A ≥ 6000, B ≥ 4000, C ≥ 2500, D ≥ 1200, E > 0, "-" | Station output CR/h at base prices |

### 3.7 Own station

| Key | Value | Where |
|---|---|---|
| Charter | 200,000 CR | `ShipResources.lua:304` |
| Stages | 4: 30/40/50/60 min with needs (2.12) | `:306` |
| NameCount | 12 | `:334` |
| SlotCount / SlotClearance | 8 / 2400 m (×S in the port, `03-world.md` §2.5) | `:532` |
| Slot distance | floor(r·2.1 + 2200), moons + 1600 (×S in the port) | `:543` |
| Slot elevation | ±28° planets, ±55° moons, alternating | `:542` |
| DELIVER_RANGE | 3000 m (client-only rule) | `ShipConstruction.lua:46` |
| CONFIRM_SECONDS | 4 s | `:45` |
| REFRESH | 0.25 s | `:44` |
| FETCH_RETRY | 5 s | `:47` |

### 3.8 Services UI, notices, profile

| Key | Value | Where |
|---|---|---|
| Station UI toast | 3.5 s | `ShipStationUi.lua:40` |
| Ticker | 70 design px/s; 4 random news + market tip | `:38` |
| Market refresh | 30 s (on the page), 10 s staleness when switching to it | `:39`, `:458` |
| Hub re-render | 1 s | `:1179` |
| Shipyard preview | FOV 30, 0.25 rad/s, distance = 1.06 × bounding diagonal | `:1132` |
| Notices comms / toast | 6 s / 4 s (overridable per call) | `ShipNotices.lua:24` |
| Flight computer comms | 3 s | `ShipDocking.lua:154` |
| UI open animation | 0.22 s Quint-Out, canvas rises 14 px, veil (6,10,16) from 35 % transparency | `ShipConfig.lua:2051`, `ShipUi.lua:96` |
| Profile autosave / urgent | 90 s / 8 s | `ShipData.lua:49` |
| ReportPosition | every 10 s | `ShipClient.lua:1273` |
| Galaxy station refresh | every 2 s | `ShipClient.lua:1270` |
| First-visit toast window | 300 s | `ShipClient.lua:683` |

### 3.9 Inputs (`ShipConfig.Keys`, `:2763`), as used by this area

| Action | Key | Use here |
|---|---|---|
| Dock | K | request / cancel / launch |
| Autopilot | P | docking computer toggle while GRANTED or AUTO |
| Land | L | landing only; ignored while docking controls the ship |
| Survey | G (hold) | scan |
| Mine | H (hold) | mine |
| StationPanel | U | construction panel |
| CycleTarget | T | lock the target (survey and site) |
| ReleaseMouse | L-Alt | footer hint only |
| ThrottleUp/Down, Strafe*, Yaw* | W S A D R F Q E (+ Space / L-Ctrl) | any of them held overrides AUTO |
| Mouse | RMB held | look around (services panel hides) |

### 3.10 Values from other areas that this area reads

| Value | Where | Used for |
|---|---|---|
| `Systems.FuelCapacity` = 100 | `ShipConfig.lua:2400` | refuel |
| `Thrust.CruiseDistanceTime` 3.5, `CruiseDepartTime` 1.5, `CruiseMaxSpeed` 3e6 | `:50` | approach limit |
| `Autopilot.OverrideDeflection` 0.5 | `:2393` | AUTO override |
| `Camera.SeatOffset` (0,0.5,0) | `:2655` | probe |
| `Voice.Callouts`: CollisionTime 2.5, CollisionSpeed 30, CollisionInterval 0.15, HullImpactSpeed 12, StandByAfter 2 | `:2234` | callouts |
| `Ui.Fonts` | `:2035` | fonts |
| `Ships[*].CargoCapacity/DesignMass/TopSpeed/BoostSpeed/TurnRate/Perks/TestOnly` | `:87` | economy and shipyard |

---

## 4. State machines, timings, thresholds and edge cases

### 4.1 Docking

```
            Dock key, candidate ≤ 6 km              grant after 1.6 s (pad)
 FREE ────────────────────────────▶ REQUESTING ───────────────────────────▶ GRANTED ◀──────────────┐
  ▲  ▲                                  │ denied (comms)                        │  │ Autopilot key       │ Autopilot key or
  │  │                                  ▼                                       │  ▼                     │ manual input
  │  └────────────── FREE ◀─────────────┘                                       │ AUTO ─────────────────┘
  │                                                                             │  │ path done
  │   cancel: Dock key (GRANTED/AUTO), expiry 600 s, > 6900 m away,           │  │
  │   surface entry, jump, crash, host gone                                     │  │
  │                                                                touchdown ✓  ▼  ▼
  │                                                                          CLAMPING (1.6 s)
  │                                                                              │ Dock ok
  │      path done (released with v ≤ 170 m/s, throttle 0)                      ▼
  └──────────────────────────── LAUNCHING ◀──── Undock ok (Dock key / LAUNCH) ─ DOCKED
                                                            (Dock denied in CLAMPING → FREE)
```

| State | Pose source | Thrust input | Collisions | Forced frame | Pad lamps | Approach aid | Gear |
|---|---|---|---|---|---|---|---|
| FREE | pilot | yes | yes | no | idle | hidden | (others) |
| REQUESTING | pilot | yes | yes | yes | idle | hidden | (others) |
| GRANTED | pilot | yes | yes | yes | assigned (blink) | shown | down in hangar < 70 m |
| AUTO | path | **no** | no | yes | assigned | "DOCKING COMPUTER ACTIVE" | as GRANTED |
| CLAMPING | lerp to pad | no | no | yes | clamped | hidden | down |
| DOCKED | pad | no | no | yes | clamped | hidden | down |
| LAUNCHING | path | no | no | yes | idle | hidden | down |

### 4.2 Station services UI

closed → `open(entry, pad, onFoot)` (on docked, on start docked after the fade, after sitting down in the hangar) → **Hub** ⇄ Overview, Market, Cartography, Shipyard (only with a shipyard) → closed (on undock/launch, when standing up in the hangar, when CLOSE is pressed on foot).
- `busy` blocks overlapping actions: refuel, trade, data sale, ship.
- Looking around (RMB) hides the panel and the shade without closing.

### 4.3 Construction panel

closed ⇄ open (U, if `canOpen`), with tab BODY ⇄ STATION.
- FOUND and ABANDON need a confirm click within 4 s. Rerolling a name or switching tab drops the armed confirm.
- `busy` blocks overlapping requests.

### 4.4 Build site lifecycle

```
none ──FoundStation(-200,000 CR)──▶ Stage 1 AWAITING ──deliver all──▶ Stage 1 BUILDING (30 min) ──advance──▶ Stage 2 AWAITING ...
... Stage 4 BUILDING (60 min) ──advance──▶ OPERATIONAL (dockable, drills into storage, ≤ 200 t)
any non-operational state ──Abandon──▶ none (no refund)
```

- Deliveries can be partial; the timer starts when the last need of a stage is in.
- `advance` moves one stage per call. The server tick (15 s) and every action call it, and the client UI simulates on a probe copy.
- A finished site turns into a station through `ShipGalaxy:refreshStations` (2 s client tick) followed by `ShipStation:sync`.

### 4.5 Survey and mining

- Survey: idle → (locked body, active) → bar visible → scanning (held + in range + aligned) → progress 1 → pending (TRANSMITTING) → charted ("CHARTED", visible for 4 s after each lock).
- Mining: landed → reason or ready → mining (held + ready) → progress 1 → pending (PROCESSING) → +1 t. Failure → STAND BY for 2 s.

### 4.6 Timing summary

| What | Time |
|---|---|
| Grant delay | 1.6 s |
| Permit | 600 s (timer turns warn below 60 s) |
| Clamps | 1.6 s smoothstep |
| Pad lamp blink | 0.8 s period (0.5 bright / 0.3 dim) |
| Blinkers | per part `Blink` (0.5–4 s), `BlinkDuty`, `BlinkPhase` |
| Docking computer turn easing | τ = 0.9 s |
| Docking computer speed slew | 45 m/s² |
| Survey hold | 4 s, 6 s for giants and stars; decay 0.8 /s |
| Mining | 8–24 s per ton; decay 0.5 /s; retry 2 s |
| Market drift step | 1200 s |
| Build stages | 1800 / 2400 / 3000 / 3600 s (wall clock, offline too) |
| Comms | 6 s; flight computer 3 s; toasts 4 s (special: 7 s discoveries, 8 s charter and construction) |
| Services toast | 3.5 s |
| Confirm window | 4 s |
| Saves | autosave 90 s; urgent ≤ 8 s; position report 10 s |

### 4.7 Edge cases (from the Lua)

- **Results that arrive late** (request, dock, launch, market, trade …) are discarded when the state or station has changed (`ShipDocking.lua:200`, `:493`, `:529`; `ShipStationUi.lua:747`). In singleplayer, calls are synchronous except the 1.6 s grant delay. Keep those guards around the delay.
- **Leaving the zone:** while GRANTED, distance > 6900 m revokes the permit. While AUTO the computer flies, so there is no range check (the path stays inside).
- **Wrong pad:** a touchdown attempt over another pad warns once per pad index (`wrongPadWarned` resets on every grant) and does nothing else. The ship can still collide with that pad's colliders.
- **Touchdown too fast or tilted:** in the hangar the status shows SLOW DOWN or LEVEL THE SHIP. The pad itself is solid only through hangar floor colliders. Excess impact speed (> 45 m/s) crashes.
- **Manual override in AUTO:** any steering or throttle/strafe/yaw key returns the ship to GRANTED and hands over at the current pose. The velocity stays whatever the pilot's thrust model had; the path velocity is not handed over (only `onReleased` after LAUNCHING passes a velocity).
- **Launch** cannot be interrupted. Collisions are off until FREE.
- **Start docked:** the server reserves the saved station's pad. If no pad is free (multiplayer only), spawn in front of that station. If the station is gone, use the home station.
- **Station removed** (system change) while busy: `update` sees the host gone → `reset()`.
- **Jump while near a station:** blocked by the mass lock (6 km) and by any non-FREE docking state ("JUMP BLOCKED").
- **Crash during docking:** `docking:reset()` frees the pad. Respawn happens at the outer approach point of the station nearest the crash (`stationSpawn`).
- **Ship switch while docked:** a new probe gives a new pad height; the clamps re-seat the ship on the next frame.
- **Fuel:** saved with the active ship on Dock, ReportPosition and Refuel. Not saved by SwitchShip, which keeps the old ship's last saved value.
- **SellData** keeps bodies charted. **ReportDestroyed** un-charts lost bodies (they can be surveyed again).
- **Market** works without docking (for the UI). **Trade** needs docking at that station.
- **Shipyard:** buying makes the new ship active immediately. Ships without a model in the build show "NOT AVAILABLE YET". `TestOnly` ships are never sold.
- **Survey** is impossible in a surface session, in atmosphere, while controlled (docking or landing guidance) or during a jump. Re-locking resets progress.
- **Mining** uses the deposit choice from the construction panel only while landed on that body; otherwise it uses `bestToMine`.
- **Deliver** checks distance on the client only. Partial deliveries return what is not needed.
- **Storage accrues lazily** (on read or action); time spent full is lost.
- **Own station** id is "P<UserId>". One per pilot.
- **Sites and player stations** of other pilots appear for everyone (multiplayer). In singleplayer only your own.
- **Encoding:** `·` (U+00B7) appears in many strings and must render in the SDF font (8.4).

---

## 5. On screen

### 5.0 What the export contains

The three ScreenGuis this area fills (**StationServices**, **Construction** and **FlightNotices**) are built by Studio-side UI builders (`ServerStorage.Dev.UI.*Installer`) that are **not in `scripts.rar`**. Their UDim2 positions and sizes, the static captions (tab labels, column headers, button labels such as "LAUNCH") and the sound asset IDs are therefore unknown. Open question Q1 asks the user for an export or screenshots.

What is known exactly:
- the element tree contract (names and paths the scripts look up, below);
- every dynamic text, colour and state rule;
- the runtime-built elements (pad marker, open veil, mining beam, hologram).

The layouts below are a **phone-first rebuild** for 20:9 that keeps the structure, colours, fonts and texts.

**Reference screen.** 20:9 landscape, Xiaomi 15: 2670 × 1200 px at 2.875 px/dp ≈ **929 × 417 dp**. The engine's layout unit `u` equals 1 dp here (`Game.onSurfaceChanged`: `u = density·min(1, h/380dp, w/720dp)`). All sizes below are in `u`, inside the safe insets (`frame.sl/st/sr/sb`). The Roblox design canvas is 1920 × 1080, so on this phone 1 design px ≈ 0.386 u. Roblox text sizes (15–18 design px) would come out at 6–7 u, too small, so the phone layout scales type up (minimum 9 u, body 10–12 u) and makes every touch target at least 44 × 44 u.

### 5.1 Shared look

- **Fonts** (`ShipConfig.Ui.Fonts`; the engine's SDF atlas already has these families):

  | Use | Font |
  |---|---|
  | titles, buttons, labels | **Sarpanch** SemiBold/Bold |
  | body text | **Titillium Web** SemiBold/Bold |
  | numbers in columns (prices, credits, timers) | **Roboto Mono** Medium |
  | in-world signage | Michroma (see 9.2) |

- **Station services palette:** Accent (255,170,60), Ink (230,236,240), Muted (140,150,160), Good (120,220,150), Bad (255,90,80), Panel (14,18,24), Body (196,170,146), Deep (140,66,8).
- **Construction palette:** Accent (255,122,0), Ink (242,238,232), Body (196,170,146), Muted (150,120,92), Good (120,220,150), Bad (255,70,60), Panel (8,5,3), Deep (140,66,8).
- **Flight notices:** Accent (255,170,60), Ink (230,236,240), Muted (140,150,160), Good (120,220,150), Bad (255,90,80).
- The existing Kotlin `Theme.ACCENT` is cyan (0xFF4CD6FF). The station screens use the amber palettes above, as Roblox does.
- **Open animation** (`ShipUi.animateOpen`): over 0.22 s with Quint-Out easing, the canvas slides up from +14 design px (5.4 u) to 0, while a full-screen veil (6,10,16) fades from 65 % opacity (transparency 0.35) to 0. There is no close animation (it hides at once).
- **Buttons:** disabled = label at 45 % opacity (TextTransparency 0.55), not tappable, hover hidden. Hover (mouse) has no phone equivalent; show a pressed state (Accent at 20 %) instead.
- **Sounds:** services Select, Hover, Open, Close, Buy, Sell, Error. Construction Click, Confirm. Notices Comms, Toast. The asset IDs are unknown (Q1); use the audio spec's sources.
- **RichText.** Some labels colour part of their text: market tip, ticker sender, stats lines, discovery line, stages line. The `UiRenderer` port needs coloured runs: draw consecutive spans and measure widths. No allocation is needed: use `TextBuf` plus a small span array.

### 5.2 Station services (StationServices)

**Element contract** (paths used by `ShipStationUi.attach`, `:145`):

```
StationServices (ScreenGui) ─ Canvas (UIScale; attrs = colours) ─┬─ Shade
                                                                  ├─ Panel ─┬─ Header: StationName, Subtitle, PadLabel, Credits.Amount
                                                                  │         ├─ Tabs: Hub|Overview|Market|Cartography|Shipyard (each: Label, Focus, Hover)
                                                                  │         ├─ Pages
                                                                  │         │   ├─ Hub: Tiles{Market,Cartography,Shipyard,Refuel,Disembark,Quarters (Label,Sub,Hover)},
                                                                  │         │   │       Commander{Avatar,Name,Handle,Credits,Stats}, Ship{Name,Role,Fuel{Bar.Fill,Value},Cargo{…}},
                                                                  │         │   │       Station{Name,Subtitle,Operator,Description}, Ticker{Tag,Text}
                                                                  │         │   ├─ Overview: Description, Economy, Services, ShipName, ShipInfo, Fuel{…}, Cargo{…}, Data, RefuelButton{Label,Cost}
                                                                  │         │   ├─ Market: List{Row template: Select,Hover,Name,Category,Buy,Sell,Supply,Demand,Hold,Selected},
                                                                  │         │   │          CargoBar.Fill, CargoValue, Trade{Item,Info,Quantity,Total,Minus,Plus,Max,BuyButton,SellButton}
                                                                  │         │   ├─ Cartography: List{Row: Body,System,Class,Value}, Empty, Total, SellButton
                                                                  │         │   └─ Shipyard: List{Card: Select,Hover,Name,Role,Price,Status,Selected},
                                                                  │         │               Detail{Name,Description,Stats,Perks,ActionButton}, Preview (ViewportFrame), PreviewFrame.InteriorTag.Label
                                                                  │         └─ Footer: LaunchButton, CloseButton
                                                                  └─ Toast: Title, Body
                                                     Sounds: Select, Hover, Open, Close, Buy, Sell, Error
```

**Phone layout** (929 × 417 u reference; x/y relative to the panel):

| Element | Rect (x, y, w, h) | Style |
|---|---|---|
| Shade | full screen | (6,10,16) at 55 % |
| Panel | sl+12, st+10, W-sl-sr-24, H-st-sb-20 | Panel colour at 94 %, 1 u Accent border at 35 %, corner radius 10 |
| Header.StationName | 16, 8 | Sarpanch Bold 20, Ink, tracking 0.06 |
| Header.Subtitle | 16, 32 | Titillium SemiBold 9.5, Muted, tracking 0.18. "SOL  ·  EARTH ORBIT  ·  HIGH TECH" |
| Header.Credits.Amount | right 16, 10 | Roboto Mono 16, Ink. "1,500 CR" |
| Header.PadLabel | right 16, 32 | Sarpanch Bold 9.5, Accent, outlined chip. "PAD 04" |
| Tabs (rail) | 8, 54, 112, 5 × 44 (gap 4) | Sarpanch Bold 11, tracking 0.2. Active: 3 u Accent bar on the left, Accent fill at 12 %, label Ink. Inactive: label Accent. The Shipyard tab is removed when the station has none. |
| Content | 128, 54, panelW-136, panelH-110 | |
| Footer.LaunchButton | right 12, bottom 8, 168 × 44 | primary: Accent fill, Panel-colour text, Sarpanch Bold 13, "LAUNCH" |
| Footer.CloseButton (on foot) | same rect | "CLOSE" |
| LOOK (new) | left of LAUNCH, 44 × 44 | eye icon. **Hold** = `setLooking(true)` |
| Toast | centred at top of the panel, 320 × 44 | Title Sarpanch Bold 12 (Accent, or Bad when bad), Body Titillium 10 Ink. Shown 3.5 s. |

**Hub** (content ≈ 769 × 253):
- **Tiles**: 3 × 2 grid at (0,0), each 150 × 64, gap 10. Label Sarpanch Bold 13 Ink, Sub Titillium 9.5 Muted (from 2.7).
- **Station card**: below the tiles at (0,148), 470 × 95. Name 13, subtitle 9.5 Muted, "OPERATED BY …" 9.5 Accent, description 9.5 Body (wraps to 3 lines).
- **Pilot card** (the Roblox "Commander"): (480,0), 289 × 120.
  - A 40 × 40 emblem in place of the avatar.
  - Name "PILOT" or a callsign (Q4), Sarpanch Bold 14.
  - No @handle.
  - Credits in mono 13.
  - `SHIPS  n     WORLDS CHARTED  n` at 9.5, with the numbers in Ink.
- **Ship card**: (480,128), 289 × 125. Name 14, `ROLE  ·  370 T` 9.5 Muted. Fuel bar 160 × 5 with `"100 %"`. Cargo bar with `"0 / 16 T"`. Fill Accent, track Panel lightened.
- **Ticker**: across the bottom of the content, height 22. Tag "STATION NEWS" as an Accent block with Panel-colour text 9. The text is Titillium 10 Ink with Accent spans. It scrolls at **0.0648 × screen height per second** (70 design px/s on a 1080 canvas, ≈ 27 u/s here) and wraps as in 2.7.

**Overview:**
- Left column 400 wide: description 10.5 Body, "ECONOMY  HIGH TECH" 10 Ink, services line 9.5 Muted.
- Right column 330 wide: ship card (name, info, fuel and cargo bars, data line), then **REFUEL** button 330 × 48 (label left, `Cost` right in mono).

**Market:**
- **List** at (0,0), 480 × (content height - 30):
  - Header row in 8.5 Muted: NAME · BUY · SELL · SUPPLY · DEMAND · HOLD.
  - Rows 34 u high, 17 rows, scrolled by drag with inertia and clipped.
  - Columns: NAME x 8–170 (Name 11 Ink, Category 8 Muted under it), BUY right edge at 230, SELL right at 290 (mono 11), SUPPLY at 300 and DEMAND at 365 (Sarpanch 9.5 coloured by level), HOLD right at 472 (mono 10, Accent or "-" Muted).
  - Selected row: Accent at 14 % plus a 3 u Accent bar.
- **Cargo bar** under the list: 480 × 5 with `"12 / 16 T"`.
- **Trade panel** (490,0), 279 wide:
  - Item, Sarpanch Bold 15.
  - Info, Titillium 9.5 Muted, wrapped to 4 lines.
  - Stepper: [−] 48 × 44, quantity `"4 T"` mono 18, [+] 48 × 44, [MAX] 64 × 44. **Hold-to-repeat** on ±: start after 0.4 s, then every 0.08 s (phone addition).
  - Total `"BUY 676 CR  ·  SELL 624 CR"` at 10.
  - BUY and SELL buttons, 134 × 48 each, labels `"BUY  4 T"` / `"SELL  4 T"`.

**Cartography:**
- List rows 34 high: BODY (Sarpanch 11), SYSTEM (9.5 Muted), CLASS (9.5), VALUE (mono 11, right).
- Empty state centred: "NO SURVEY DATA ON BOARD" (the caption is a guess).
- Bottom: `"TOTAL  4,320 CR"` mono 16 on the left, "SELL ALL DATA" 200 × 48 on the right.

**Shipyard:**
- Card list on the left, 250 wide: cards 56 high with Name (Sarpanch 12), Role (9 Muted), Price (mono 10, right), Status (8.5; ACTIVE Good, OWNED Ink, FOR SALE Accent). Selected: Accent border.
- Centre preview, 280 × 190: turntable with FOV 30 and the camera from 2.7. "WALK-ABOARD …" tag at its bottom-left.
- Right detail, 223 wide:
  - Name 16, description 9.5 Body.
  - Stats in 10: caption Muted, value Ink.
  - Perks.
  - Action button, full width × 48, at the bottom.

### 5.3 Construction panel

**Element contract** (`ShipConstruction.attach`, `:246`):

```
Construction ─ Canvas (UIScale; colour attrs) ─ Panel ─┬─ Header{Caption, CloseButton}, Tabs{BodyTab, StationTab}, Title, Subtitle, Footer.Hint
                                                        └─ Views ─┬─ Message{Text, Detail}
                                                                  ├─ Plan{Discovery, DepositsCaption, Deposits(DepositTemplate: Name, Bars.Bar1..5, Grade, Rate, Selected),
                                                                  │        Empty, ValueCard{Letter, Lines{Trait, Value, Economy}}, StagesCaption,
                                                                  │        Stages(StageTemplate: Index, Title, Time, Needs), NameRow{Name, Reroll}, Cost, Note, Buttons.FoundButton}
                                                                  ├─ Site{Stage{StageCaption, Timer}, StageTitle, Info, Progress.Fill, StagesLine,
                                                                  │       Needs(NeedTemplate: Name, Amount, Hold, Bar.Fill), Note, Buttons{DeliverButton, AbandonButton}}
                                                                  └─ Station{Location, Production(ProductionTemplate: Name, Rate), StorageTop.Capacity, CapacityBar.Fill,
                                                                             Storage(StorageTemplate: Name, Value, Amount), Empty, Note, Buttons{CollectButton, SellButton}}
                                                         Sounds: Click, Confirm
```

Templates are cloned into pools as needed, and unused clones are hidden. Rows from a template are named `<TemplateName without "Template"><n>`.

**Phone layout:**
- Centred panel, `min(660, W-sl-sr-24)` × `(H-st-sb-20)`. Panel colour (8,5,3) at 95 %, 1 u Accent border at 40 %, radius 10.
- Header 40: Caption Sarpanch Bold 11 Accent tracking 0.25 ("SURVEY DATA" / "MY STATION" / "BUILD SITE" / "YOUR STATION"), ✕ 44 × 44 on the right.
- Tabs row 40: two tabs 160 × 36, styled as in 2.13.
- Title Sarpanch Bold 18 Ink; Subtitle Titillium 9.5 Muted.
- The view area scrolls vertically, and the button row stays fixed at the bottom (48 high).
- **Plan view:**
  - Discovery line 10.
  - Deposits, two columns: rows 40 high with Name 11, five 10 × 6 pips with gap 3, Grade 9, Rate mono 9 right. Tapping a row picks the deposit.
  - ValueCard 260 × 96: Letter Sarpanch Bold 44, Trait 12 Ink, Value 9.5 Body, Economy 9.5 Muted.
  - Stages: 4 rows of 34 with an index disc, Title 11, Time mono 10, Needs 9 Muted.
  - NameRow: Name Sarpanch Bold 14 and a reroll ↻ button 44 × 44.
  - Cost 10.5, Note 9.5 wrapped, FOUND button 240 × 48 (armed: "CONFIRM  ·  200,000 CR").
- **Site view:**
  - Stage caption with timer, StageTitle 16, Info 9.5.
  - Progress bar full width × 8.
  - StagesLine 10.
  - Need rows 44 (bar 4 high).
  - Note, DELIVER (primary 200 × 48), ABANDON (secondary 180 × 48).
- **Station view:**
  - Location line, production rows.
  - Capacity "137 / 200 T" with a bar.
  - Storage rows: Name, "ABOUT 12,340 CR", "37 T".
  - Note, LOAD INTO HOLD (primary), SELL ALL HERE (secondary).
- Footer hint: "TAP ✕ TO CLOSE" on touch; on gamepad "B CLOSES".

### 5.4 Flight notices (HUD layer; final placement belongs to the HUD spec)

| Element | Proposed rect (u) | Content and style |
|---|---|---|
| Comms | sl+16, st+48, 320 × 54 | Sender Sarpanch Bold 9 Accent tracking 0.2 ("MERIDIAN STATION  ·  TRAFFIC CONTROL"); Body Titillium 11.5 Ink, 2 lines; Panel at 70 % with a 3 u Accent left bar; 6 s (3 s for FLIGHT COMPUTER) |
| Docking (approach aid) | W-sr-16-210, st+48, 210 × 98 | "PAD 04" Sarpanch Bold 16 Accent with the timer `09:12` mono 12 at the right (Ink); distance mono 14 Ink ("820 M"/"1.4 KM"); status Sarpanch Bold 11 (Ink, Accent when warn); "LIMIT 140" 9.5 Muted |
| Survey bar | W/2-170, H-sb-100, 340 × 50 | Label 9.5 Ink tracking 0.15; bar 340 × 5 (track Panel, fill Accent, 2 % steps); Value mono 10 left ("SCANNING  42 %"/"TAP SCAN"); Range 9.5 right (Good/Muted) |
| Toast | centre, st+96, ≤ 440 × 46 | Title Sarpanch Bold 13 (Accent/Bad), Body Titillium 10.5 Ink; default 4 s |
| HUD prompt line | (HUD spec) | docking prompt from 2.5.5, without key names on touch |

These must not collide with the touch controls: the left throttle column, the right stick area and the bottom gear indicator at `H - sb - 30`. The survey bar sits above the gear indicator, between the two thumb zones.

### 5.5 In-world elements

- **Pad marker**: 140 × 46 design px → ≈ 54 × 18 u at the 0.386 scale. Use a minimum readable size of 64 × 22 u, with Sarpanch Bold at 18 design px ≈ 10 u. Text "PAD 04" over "▼" in PadAssigned with a 60 %-opaque dark stroke. It is pinned 40 m above the pad, drawn through walls, and visible ≤ 6 km.
- **Pad lamps**: 4 edge strips plus 4 corner cubes per pad, Neon; colour per state (3.1). Assigned blinks between full and 30 % opacity.
- **PAPI**: 4 lamps, white or red per lamp (2.4.3).
- **Blinkers**: Neon parts toggle opacity (off = transparency 0.85).
- **Signs**: station name on `Sign` (Michroma, self-lit at brightness 1.4); pad numbers "01"–"06" on the pads; site sign "CONSTRUCTION SITE" plus the name.
- **Hologram**: the next build stage in a ForceField look (animated shimmer) in (255,122,0) at 45 % opacity while waiting, 80 % while building.
- **Mining beam**: pulsing orange neon cylinder, dust and sparks, a point light at the impact (2.11).
- **Shipyard turntable**: 2.7.

---

## 6. Dependencies on other modules and areas

| Area | What this area needs from it | What it needs from this area |
|---|---|---|
| **Flight model** (thrust, rotation, handling) | Pose and velocity override while docking controls the ship; `thrust:setInputEnabled(false)` and no boost while controlled; `thrust:reset()` on dock; `setThrottle(0)` and the velocity hand-over on release; `cancelVelocityAlong(normal)` for hull sliding; the active ship's cargo capacity and mass | `isControlling()`; speed limits (`getLimit`, `getApproachLimit`); carry vector; `wantsGear()` |
| **Gravity / dynamic speed** | Current speed limit and `limitZone` ("station", "cruise"); the inMassShadow flag; gravity zeroed in a station frame | station limit and lock (mass lock) |
| **Sol and system builder** (bodies, orbits, scale; `03-world.md`) | Host positions, **rest positions**, radii, `class`, `landable`, `ring`, moons' `parent`, star `class` and **HeatRadius**; system seeds and planet lists for generated stations | station positions as targets and map markers |
| **Galaxy, star map, warp** (`03-world.md`, `07-menu-maps-warp.md`) | Catalog (`getSystemName`, seeds), a Roblox-compatible `Random` (Q6), arrival point, jump events | jump block (mass lock / docking busy); `ReportJump` (stats, first visit) |
| **Ships** (glTF import) | Per ship: model, `BoundsSize`, `RootToBoundsCenter`, `RootToGround`, `Walkable`, `Decks`, the `Cockpit` node and `GearClosed` parts (shipyard preview), gear animation; `ShipConfig.Ships` stats and perks | active ship id, `switchShip` trigger, fuel per ship |
| **HUD, markers, cockpit** | Station and site beacons as targets (name, `BodyClass`), lock (T / LOCK) for survey and sites, the prompt line, placement of comms, docking panel, survey bar and toasts, pad-marker declutter | prompt text, notices content |
| **Atmosphere and surface** | Surface session state, landed body, `surface:isReady()`, ground ray (terrain and scene); `atmosphere:isGuiding()`, state SPACE/SURFACE | `docking:cancel()` on entering a surface session; stations hidden on the surface |
| **Systems** (fuel, heat) | `getFuelFraction`, `setFuelFraction`, `FuelCapacity` | refuel result |
| **Destruction and warnings** | the crash sequence ("STATION" cause), `warnings:isBusy()` | hull hit (speed, normal), `ReportDestroyed` (fee, losses), respawn station |
| **Audio and voice** | `playTouchdown`, `DockClamp`, `DockRelease`, `playHit`; voice lines and callouts (2.5.6, 2.6) | `dockState`, `survey.scanning`, `mining.depositInRange`, `timeToContact` |
| **Decks and aboard** (on foot) | `decks.arrive/launch/disembark/goQuarters`, `canDisembark/canQuarters`, `getDeck`, `hasTemplate`; `HomeStation` (SetHome) | `openServices(onFoot)`, `closeServices`, `isDocked`, `launchFlight`, `onUndock` |
| **NPC traffic** | – | `stations` (entries, sites, `isInHangar`, `nearest`); traffic runs only when docking is FREE |
| **Training** | `Training.Done/Completed/Skipped` in the profile | profile storage |
| **Main menu** (`07-menu-maps-warp.md`) | – | credits, location text ("SOL  /  MERIDIAN STATION" or "…  /  IN FLIGHT"), active ship, save status, training flag |
| **Maps** (system/galaxy, `07-menu-maps-warp.md`) | – | charted set (profile), discovered/first-visit (galaxy store), own station and site positions |
| **Platform** | file storage for saves, wall clock | – |

---

## 7. Gap analysis against the current Kotlin code

| Roblox feature | Kotlin today (file) | Gap and what to do |
|---|---|---|
| Station catalog: Sol 6 + generated + player | `StarSystem` has 3 placeholder stations (HOME STATION ring 915 m, OUTPOST II/IV 257 m) at fixed positions (`game/StarSystem.kt:85`) | New `StationCatalog` and per-frame placement from host + offset; the mouth faces the host. Needs the Sol port for host data. |
| Station models | `content/StationMesh.kt`: 2 procedural models with a letterbox "slot" dock, palette materials, light sprites | Convert the 6 Roblox models and the site (9.2); new renderer with spin groups, dynamic lamps, accent, signage, LOD. StationMesh stays for tests or is removed. |
| Hull collision | `game/StationCollision.kt`: SDF proxies of the procedural meshes, a dock router through a tunnel | Replace with the models' collider sets (OBB, plus spheres, cylinders and wedges on Refinery), swept sphere in station space, slide response, **crash above 45 m/s** (today: bounce with haptics). `clearance()`/proximity can stay, fed by `timeToContact`. |
| Speed zones | `StarSystem.locate`: STATION_ZONE at 8 km, limit from `stationDistance - 4000` clearance | `getLimit` (6000/260, 2200/140, hangar 60) plus `getApproachLimit` (cruise ramp with cosine); mass lock blocks cruise and jump. |
| Moving reference frame | none (stations static) | `getCarry` once the hosts move (Sol orbits); the forced frame while docking. |
| Docking flow | `Game.requestDocking`/`updateDocking`/`launchFromStation`: K = immediate auto-dock with a stand-off point and slide into the slot (180 m/s, 30 m/s in the slot), docked overlay; launch teleports 40 m out at 60 m/s (`Game.kt:1248`–`1333`) | Full 7-state machine, 1.6 s grant, 600 s permit, 6 pads, pad lamps and marker, approach aid panel, manual touchdown rules, docking computer path (around → Outer → Mouth → Inner → hover → pad), 1.6 s clamps, launch path to Outer, comms, audio. |
| Docked screen | `ui/Screens.kt:219` `DockedScreen`: name, "WELCOME, PILOT.", LAUNCH | Full station services (Hub, Overview/Refuel, Market, Cartography, Shipyard), toasts, ticker, preview. |
| Economy | `ShipSpec.price` only (`game/Flight.kt:15`) | Commodities, economies, drift prices, refuel, trade, data, ships. |
| Survey | none | `Survey` module, survey bar, SCAN input, values, first discovery. |
| Mining | none (no surface session yet) | `Mining` module, beam visual, MINE input; depends on surface port. |
| Own station | none | Construction panel, sites with hologram stages, slots, deliveries, wall-clock builds, storage, player stations. |
| Profile and save | `Settings` string via `Platform.loadSettings/saveSettings` only | Profile and galaxy store, JSON save files, autosave, migration, crash losses. |
| Flight notices | single `message` line for 3 s (`Game.showMessage`) | Comms box, docking panel, survey bar, toast with durations and colours (`FlightNotices`). |
| Targets and map | stations lockable, labels from `name` | Beacon class labels ("STATION · HIGH TECH", "BUILD SITE · …"), sites in markers and map. |
| Gamepad and touch | ACTION button (DOCK/LAND/TAKE OFF/CANCEL), LOCK, MAP; pad: A boost, X FA, Y view, D-up lock, D-down action, Select map | AUTO (docking computer), SCAN/MINE, DATA (station panel), LOOK in services, list scrolling, steppers (8.1). |

The parts that can be reused unchanged: `UiRenderer` (needs coloured spans), `WidgetGroup`/`Button`, `TextBuf`, `Quat`/`DVec3` math, the depth-slice station drawing in `SceneRenderer`, the haptics, the desktop harness.

---

## 8. Phone adaptation

### 8.1 Input mapping

| Roblox input | Touch | Gamepad |
|---|---|---|
| **K** Dock (request / cancel / launch) | existing contextual **ACTION** button (`TouchFlightControls.actionLabel`). Labels: "DOCK" (FREE with a candidate within 6 km; takes priority over LAND only when `atmosphere.canLandNow()` is false, as `landButton` does at `ShipClient.lua:1033`), "CALLING…" (REQUESTING, disabled), "CANCEL" (GRANTED/AUTO), hidden in CLAMPING/LAUNCHING; when DOCKED, launch is the services LAUNCH button | D-pad down (existing "action") |
| **P** docking computer (GRANTED/AUTO only) | new **AUTO** button next to ACTION, visible only in GRANTED/AUTO; label "AUTO DOCK" in GRANTED, "MANUAL" in AUTO | D-pad right |
| Manual override of AUTO (steering, or W/S/A/D/R/F/Q/E held) | stick deflection > 0.5 of its radius, **or** any **change** of the throttle lever (it latches, so test movement, not position), **or** ▲/▼ held | left stick > 0.5, right stick > 0.5, triggers > 0.05, LB/RB |
| **G** survey (hold) | **SCAN** button above the throttle column, shown when a body is locked and survey is active. Phone deviation: **tap toggles** "holding" (so the left thumb can return to the throttle); it releases on completion, target change or loss of `active`. The bar shows "TAP SCAN" instead of "HOLD G". | hold D-pad left |
| **H** mine (hold) | same button, labelled **MINE** in a surface session with a deposit (survey and mining never overlap); toggle as above, "TAP MINE" | hold D-pad left |
| **U** station panel | small **DATA** button in the top HUD bar next to MAP; also on the pause screen | long-press Select (short press stays MAP) |
| **T** lock target | existing LOCK button (nose-nearest); the map can also lock stations and bodies | D-pad up (existing) |
| Mouse clicks in panels | taps; list drag-scroll with inertia; hold-to-repeat steppers; ✕ close buttons | D-pad moves focus, A activates, B closes or back, LB/RB switch tabs |
| Mouse hover highlight and sound | pressed-state highlight and the "Hover" sound on press | focus highlight |
| **RMB** look around in services | **LOOK** hold-button (eye) in the services footer; or a two-finger drag on the scrim | hold R3 |
| **L-Alt** release mouse | not needed (remove the footer hint) | – |
| ESC/close | system Back: closes the construction panel; in services opens pause (existing behaviour); the toast queue is unaffected | B |

Haptics: TICK on grant, cancel, tab switch and stepper; BUMP on clamps engaged and touchdown; HEAVY on hull hit > 12 m/s; TICK per mined ton and on survey completion.

### 8.2 Performance (60 fps on Xiaomi 15, playable mid-range; zero allocation per frame)

- **Meshes.** One station is in view at a time (LodDistance 60 km).

  | Level | Distance | Content | Triangles |
  |---|---|---|---|
  | LOD0 | < 3 km | all parts plus SurfaceGui paint | Ring ≈ 14k primitive + 9k paint ≈ 23k; Refinery ≈ 60k with 24-segment cylinders. Use segment counts by size: 8–24 for cylinders, 6×4–16×8 for balls. |
  | LOD1 | 3–15 km | parts with max dimension ≥ 3 m, paint dropped except window strips | |
  | LOD2 | 15–60 km | hull parts ≥ 40 m only | ≈ 2–5k |
  | – | > 60 km | marker only | – |

- **Draw calls** per station: about 8.
  - static opaque (1)
  - static translucent glass, sorted by part (1)
  - one opaque call per spin group (≤ 6), each with its own model matrix
  - dynamic lamps (1, a per-vertex slot index into a uniform table)
  - signage text (1)
  - beam or hologram (0–1)
- **Lighting.** 17–24 real lights per model are too many for per-pixel lighting on mobile. **Bake** them into vertex colours at conversion time (Lambert × range falloff, no shadows). Keep 2 hangar fill lights as uniforms so they light the player's ship on the pad. Neon is emissive and unlit.
- **Dynamic lamps** (blinkers, PAPI, pad lamps, accent): each lamp gets a slot (≤ 255). The CPU writes `vec4 colour/opacity` per slot only on change, as Roblox does. The vertex shader reads `u_lamp[slot]`. No re-upload of meshes.
- **Collision.**
  - ≤ 324 colliders, struct-of-arrays.
  - Reject per collider with a bounding sphere against the swept probe capsule.
  - 5 probe spheres per frame.
  - Spin-group colliders are transformed by the group matrix on the fly (do not rebuild arrays).
  - Budget ≤ 0.1 ms.
- **Strings.** The approach aid, survey bar and services numbers are formatted into `TextBuf`s and written only on change (as `ShipNotices:set` does). Comms and toasts may allocate on the event itself, the same as `Game.showMessage` today.
- **Lists.** Market rows (17), ship cards (8) and stage/need rows are preallocated. The survey list grows on survey events only, not per frame.
- **Saves.** Serialization runs at save points (urgent ≤ 8 s, 90 s autosave, on pause) and the file write happens on a background thread through `Platform`.

### 8.3 Singleplayer replacements for server logic and data stores

| Roblox | Port |
|---|---|
| `ShipProfile:call(name, …)` RPC + `Net:Guard` | Direct calls into `Commerce` and `Construction` returning a reused `Reply` object; keep every reason string; no rate limits; keep the UI `busy` guards (no-ops when calls are synchronous) |
| GrantDelay waited out after the RPC | `Docking` keeps REQUESTING for exactly 1.6 s, then calls `Commerce.requestDocking` |
| Pads reserved per player across a server | Pads are always free in singleplayer; keep `reservePad` (random free pad 1..6) so the pad varies |
| `ShipData` DataStore, session lock, retries, owner credits 999,999 | `SaveManager`: `profile.json` (+ `.bak`), atomic write through `Platform`; no lock; the owner top-up becomes a debug command |
| `ShipData.changed(urgent, quiet)` + ProfileChanged push + player attributes | `Profile.changed(urgent)`: sets dirty, schedules the save, bumps a `revision` counter that UIs compare per frame (no listeners or allocation); the main menu reads `Profile` directly |
| `ShipGalaxy` (DataStore + MessagingService, 120 s refresh, 15 s tick) | `GalaxyStore` in `galaxy.json`; `ensure(i)` rolls and freezes on first visit; the 15 s tick becomes `advance` for the own site, run at load, every 2 s while in its system, and before each action |
| First discovery / first visit by any player | Always the local pilot, except Sol ("SOL REGISTRY"); the ×1.5 bonus applies to every first survey outside Sol (Q5) |
| Other pilots' stations and sites | None; only "P1" (the local pilot's id = 1) |
| Server time `os.time()` and `galaxy.offset` | `Clock.epochSeconds()` (wall clock), clamped so it never goes backwards against the last value saved (Q10) |
| `ReportPosition` every 10 s | Update `Profile.location` in memory; persisted by the next save |
| `GetProfile` start logic | `Commerce.startSession()`: same rules (docked → pad; missing station → home) |
| Avatar headshot, display name, @handle | Pilot emblem plus the name "PILOT" (or a callsign, Q4); no handle |
| Decks server (`DeckSlot`, SetHome) | Decks area; `HomeStation` stays in the profile |

### 8.4 Text and fonts

- New glyphs in the SDF atlas: `▼` (pad marker), `↻` (reroll), `✕` (close), `°`, `·`, `—`, `%`, `&`; or draw them as icons.
- In-world signage fonts are Michroma, Source Sans Pro, Oswald, Nunito, Fredoka One (all OFL) and **GothamSSm** (proprietary, Q7). Either add one signage font to the atlas (Michroma, 51 labels, and use it for all), or bake the signage into textures at conversion time.
- RichText spans (5.1).

---

## 9. Kotlin port plan

### 9.1 Classes and files (`:core`, package `com.starwake.core`)

| File | Port of | Contents |
|---|---|---|
| `station/StationDef.kt` | ShipStations entries, `ShipResources.stationDef` | immutable def: id, name, host, economy, shipyard, model, distance, angle, elevation, description, systemIndex, systemName, player, owner, ownerId, founded |
| `station/StationCatalog.kt` | ShipStations | Sol list, `baseForSystem` generator (same rng call order), player defs, `forSystem/find/findAnywhere`, `offset()`, `bodies(i)` |
| `station/StationLore.kt` | ShipStationLore | accents, operators, news |
| `station/StationModel.kt` | readModel data | loads `assets/stations/<Type>.stm` and meta: mesh sections, spin groups, lamp slots (blinkers, PAPI, pad lamps, accent), pads (`top` transforms), approach points, hangar box, radius, colliders, sign anchors, stages (site) |
| `station/StationWorld.kt` | ShipStation | entries (preallocated, ≤ 8 per system), `place(dt, shipPos)`, `updateLights`, `nearest`, `getLimit`, `getApproachLimit`, `getCarry`, frame/forced frame, `padPose`, `isInHangar`, `setPadState`, `collide`, `timeToContact`, `remember/forget`, `sync(sites)`, `showStages` |
| `station/StationColliders.kt` | Spherecast use | swept sphere vs OBB, sphere, X-cylinder and wedge, returning fraction and normal; allocation-free |
| `station/Docking.kt` | ShipDocking | state machine, `DockPath` (fixed arrays of 8 points), touchdown, clamp, guide → `FlightNotices` |
| `econ/Economy.kt` | ShipEconomy | commodities, economies, `hash01`, `drift`, `price` (into reusable out-params), `surveyValue`, `ShipCatalog`, `formatCredits` into `TextBuf` |
| `econ/Resources.kt` | ShipResources | deposits, roll, ratings, stages, `needs/deliver/advance/accrue/take`, names, slots |
| `econ/Commerce.kt` | ShipCommerce + ShipServer/ShipConstruction | local rules engine: start session, request/cancel/dock/undock, market, trade, refuel, sellData, buy/switch ship, survey, reports, mine, found/deliver/abandon/collect/sellStorage, debug |
| `save/Profile.kt` | ShipData data + ShipProfile view | profile fields, template, reconcile, migrations, getters (`cargoUsed`, `fuel(ship)`, charted set), `revision` |
| `save/GalaxyStore.kt` | ShipGalaxy | per-system records, `ensure/complete`, `discover`, `visit`, `sites(i)`, `stationDefs(i)` |
| `save/SaveManager.kt` + `save/Json.kt` | ShipData load/save | hand-written JSON writer and reader (no dependency); atomic write through `Platform`; autosave timers |
| `game/Survey.kt` | ShipSurvey | requirements, update, submit |
| `game/Mining.kt` + `gfx/MiningBeam.kt` | ShipMining | update, submit, beam and particles |
| `ui/FlightNotices.kt` | ShipNotices | comms, docking panel, survey bar, toast (5.4) |
| `ui/StationServicesScreen.kt` | ShipStationUi | pages, toast, ticker, shipyard preview hook |
| `ui/ConstructionScreen.kt` | ShipConstruction (client) | views, confirm arming, pools |
| `ui/RichSpans.kt` | – | coloured text runs for `UiRenderer` |
| `gfx/StationRenderer.kt` (+ shaders) | – | static, spin and lamp sections, LODs, hologram and beam passes, signage text, depth slices as in `SceneRenderer.drawStations` |
| `Platform.kt` (extend) | – | `readSave(name): ByteArray?`, `writeSave(name, data)` (atomic, background), `epochSeconds()` |

### 9.2 Station converter (`tools/roblox/convert_stations.py`)

The input is `Assets.rbxmx`; the output is `app/src/main/assets/stations/{RingStation,GardenStation,FoundryStation,SpireStation,HollowStation,RefineryStation,Site}.stm` plus `stations.json`. The converter must:

1. **Stream-parse** with `iterparse` + `clear()`. Resolve `SharedString` tags at the end of the file. Decode `AttributesSerialize` (2.3). Fix the text encoding (2.3, the `&#194;&#183;` trap).
2. **Express parts in root space** (`Root` is at identity for all models; still compute `root⁻¹·cf` for safety).
3. **Classify each BasePart:**
   - skip invisible (`Transparency ≥ 1`) parts from the render mesh;
   - export `Collider = true` parts to the collision list: type (Block → OBB, Ball → sphere, Cylinder → X-axis cylinder, Wedge → wedge), CF, size, spin group;
   - record its spin group (nearest ancestor Model with `SpinDegreesPerSecond`, or `Ring` → 2°/s Z), its stage (`Stage1..4` for the site), and its lamp slot (Blink*, Papi, pad Lights, `Accent`).
4. **Tessellate:**
   - Block: 12 triangles.
   - Wedge: 8 triangles (slope from front-bottom to back-top).
   - Cylinder: X axis, length `size.X`, radius `size.Y/2`, 8–24 segments by radius, with caps.
   - Ball: diameter `size.X`, UV sphere 6×4–16×8 by size.
   - SpecialMesh Sphere: an ellipsoid filling the part box.
   - Flat normals. sRGB → linear vertex colour. A material class byte.
5. **Map materials to shading classes:**

   | Roblox material | Shading class |
   |---|---|
   | Neon | emissive (unlit, colour × gain) |
   | Glass | translucent, specular |
   | ForceField | hologram (site plan only) |
   | Metal, DiamondPlate, CorrodedMetal, Foil | metallic, roughness 0.35–0.6 |
   | SmoothPlastic | smooth dielectric |
   | Plastic, Concrete, Slate, Rock, Basalt, Pebble, Sand, Grass, LeafyGrass, Wood, WoodPlanks, Ice, Glacier, Fabric, CrackedLava | rough dielectric (optional detail noise per material id) |

   Partial transparency goes into the translucent section, sorted per part at runtime.
6. **Bake SurfaceGuis to decal geometry.**
   - The canvas in px is the face size (studs, from the part size and `Face`: 0 Right, 1 Top, 2 Back, 3 Left, 4 Bottom, 5 Front) × `PixelsPerStud`.
   - For each `Frame`, resolve the UDim2 `Size` and `Position` against the parent rect, apply `AnchorPoint`, rotate by `Rotation` about its centre, and clip against every ancestor with `ClipsDescendants` and the SurfaceGui bounds (Sutherland–Hodgman, convex).
   - Emit coloured triangles 0.02 m above the face. Alpha = 1 - BackgroundTransparency. Order by ZIndex (+ε per level).
   - `UIStroke` → outline quads (Thickness px, Color, Transparency). `UICorner` → ignore (radius tiny at station scale) or an 8-segment corner fan. `UIGradient` (1) → vertex colours.
   - Emissive factor = `(1 - LightInfluence) · Brightness`.
   - Window grids, hazard stripes and markings are all plain Frames.
7. **Text.** Export TextLabels as runs `{text, font, colour, rect in part space, TextScaled → fit, XAlignment}`. Mark `Sign/Display/Name` (station name), `Pads/PadN/Number/Display/Text`, and on the site `Sign/Display/Name` as runtime-replaced.
8. **Lights.** Bake PointLights, SpotLights (Face, Angle) and SurfaceLights into vertex colours of static parts. Export the two brightest hangar lights as runtime fills.
9. **Beam** (Spire): export as an effect: endpoints, widths, colour, transparency keys, spin group.
10. **LODs** as in 8.2.
11. **Binary layout** of `.stm`: header; sections (static, glass, per spin group, lamps, stages 1–4 for the site, paint); vertex = position int16×3 (quantized, 1/16 m, ±2048 m), normal oct16, colour RGBA8, class/slot u8×2 (16 B); indices u16 per section. Then the JSON meta: attributes, approach, pads, hangar, groups, lamps, colliders, texts, lights, beam.
12. **Validation report:** part counts equal the `PartCount` attribute (793, 1399, 1453, 1126, 1588, 1191, 658); pads equal the table in 2.3.1; collider counts as in 2.3.2; triangle totals per LOD.

### 9.3 Hooks into `Game.kt` (frame order mirrors `ShipClient`)

`updateFlight(dt)`:
1. Read controls. Docking input: `Docking.handleDockKey` (ACTION), `handleComputerKey` (AUTO). Survey and mine toggles. DATA panel.
2. `controlled = docking.isControlling() || landingGuidance`. If controlled, ignore the stick, throttle and boost.
3. Orbits update (Sol area). Then `ship.pos += stationWorld.getCarry()` unless on the surface.
4. `system.locate` and speed limit: `min(bodyLimit, stationWorld.getLimit())`, or the approach limit outside the lock; mass lock flag; zero gravity in a station frame.
5. Flight substeps (`flight.step`). **Collisions:** `stationWorld.collide` against the pose delta since `remember` (run per substep or once per frame with a swept test), crash above 45 m/s, slide otherwise.
6. `stationWorld.place(dt, ship.pos)`. Then `docking.update(dt, pose, vel, steering)` → if it returns a pose, overwrite `ship.pos/rot/vel` and skip collision. Then `stationWorld.remember(pose)`.
7. Gear: `docking.wantsGear(pose)` or the landing gear logic.
8. Survey `update`, then Mining `update`, then Construction `update`. Every 2 s, `galaxyStore.refreshStations` + `stationWorld.sync`.
9. `FlightNotices.update`, `StationServicesScreen.update`.
10. Every 10 s, if docking is FREE and in space, `commerce.reportPosition`. `SaveManager.tick(dt)`.

Other hooks:
- **Overlay:** `OVERLAY_DOCKED` becomes `StationServicesScreen`. Back while docked opens pause, as today. `resume()` returns to services when docked.
- **Start:** `commerce.startSession()` → docked → `docking.startDocked(entry, pad)` with the pose from `padPose`, then services open after the fade. Otherwise spawn in front of the station or at the star (Q8).
- **Remove:** `requestDocking`, `updateDocking`, `stationPort`, `slotParkDepth`, `launchFromStation`, `dockWaypoint` routing, and `debugBehindStation`/`ram-station` (rewrite them for the new models).
- **Debug commands** (harness): `dock-request`, `dock-auto`, `dock-pad <n> <height>`, `launch`, `credits <n>`, `cargo <id> <t>`, `site fill|skip|age <h>`, `time +<s>` (clock offset), `survey <body>`, `save`, `reload`.

### 9.4 Implementation order

| # | Milestone | Done when |
|---|---|---|
| 1 | Data and rules: `Economy`, `Resources`, `StationCatalog`, `StationLore`, `Profile`, `GalaxyStore`, `Commerce`, `SaveManager`/`Json` (no rendering) | unit tests with the Appendix A vectors pass |
| 2 | Converter + `StationModel` + `StationRenderer` (static, spin, lamps, accent, signage, LOD) | harness screenshots of all 7 models; ≤ budget |
| 3 | `StationWorld` (placement, frame, limits, colliders, collide, PAPI, pads) + `Docking` + `FlightNotices` (comms, docking panel, toast) + pad marker + ACTION/AUTO inputs | dock-auto, dock-manual and launch scenarios pass |
| 4 | `StationServicesScreen` (all pages, preview, toasts) + start docked + save/restore | services screenshots at 20:9, 16:9 and 4:3; save-restore scenario |
| 5 | `Survey` + cartography sale + SCAN input | survey scenario |
| 6 | `Mining` (after the surface port) + `ConstructionScreen` + sites, hologram, player stations | construction scenario |
| 7 | Audio and voice cues, haptics, gamepad focus navigation, perf pass on device | 60 fps on Xiaomi 15 with a hangar view, 0 B/frame |

### 9.5 Tests

**Unit tests** (`core/src/test/…`):

| Test | Checks |
|---|---|
| `EconomyTest` | `hash01`, `drift` and price vectors (A.1); half-up rounding; `formatCredits`; `surveyValue` table (A.2) |
| `ResourcesTest` | Sol ratings table (A.3); deliver (partial, over-delivery returns, timer start), advance (one step, final stage sets Operational/Storage/StorageAt), accrue (rate, cap, time lost when full), take (order by base price, whole tons); `pickSlot` vectors (A.5); 12 unique names |
| `CatalogTest` | Sol offsets (A.5); `pickModel`; generated-station rng order (once Q6 is settled) |
| `CommerceTest` | every rule and reason in 2.9 and 2.14; refuel edge cases (`missing < 0.5`, partial credits, `missing - units < 0.5` → full); trade capacity and credits; ship buy/switch with cargo; survey first-bonus rounding; destroy fee `min(credits, round(price·0.05))` and un-charting |
| `DockingTest` (fake clock and world) | 1.6 s grant; 600 s expiry; 6900 m revoke; touchdown boundaries (26/39 m, 10 / -8 m, 12 m/s, 0.94); wrong pad warns once; clamp smoothstep; AUTO override; inbound path ends exactly at the pad pose with speed ≤ 5 m/s; the around-route triggers for `z > Mouth.z - 60`; outbound ends at Outer |
| `StationWorldTest` | `getLimit` zones (5999/6001, 2199, hangar edges incl. +20 z slack); approach limit for cosine ±1; carry; PAPI threshold sign; blink duty; pad lamp colours |
| `ProfileTest` | template; reconcile cases; JSON round trip; migration hook |
| `GalaxyStoreTest` | freeze on ensure; Sol pre-discovered; discover and visit once |
| `SurveyTest` / `MiningTest` | ranges (planet, giant, star with HeatRadius), progress and decay, reason precedence |

**Harness scenarios** (`:desktop`, PNG + log):

| Scenario | What it does |
|---|---|
| `stations` | 7 models × 4 views (mouth, side, rear, hangar over pad 1) |
| `dock-auto` | start at Outer of MERIDIAN → DOCK → assert GRANTED at 1.6–1.7 s and pad marker shown → AUTO → DOCKED within 120 s; pad pose error < 0.05 m / 0.5°; screenshots: approach aid outside, inside the hangar, clamps |
| `dock-manual` | place the ship 15 m over the assigned pad descending at 3 m/s → CLAMPING → DOCKED; repeat over a wrong pad → one "WRONG PAD" comms |
| `dock-expire` | clock +600 s → "DOCKING PERMISSION EXPIRED."; fly beyond 6900 m → revoked |
| `launch` | DOCKED → LAUNCH → FREE at Outer, `|v| ≤ 170`, throttle 0 |
| `services` | each page at 2670×1200, 2400×1080, 1920×1080 and 2048×1536; market selection, cartography with data, shipyard preview |
| `trade` | fixed clock (1,800,000,000): buy 4 t Water at Meridian → -676 CR, cargo 4 t; sell → +624 CR |
| `survey` | lock Earth in range, scan 4.1 s → "SURVEY COMPLETE", +4,320 CR data; sell |
| `construction` | credits 300k → found at Mars → `site fill/skip` ×4 → operational, dockable, collect |
| `save-restore` | trade + survey → save → new `Game` with the same storage → identical credits, cargo, data, docked pad |
| `perf-station` | hangar and approach views, 600 frames: CPU per frame, draws, **0 B allocated per frame** |

---

## 10. Open questions (for the user) and risks

### 10.1 Questions

1. **Missing UI layouts.** Please export `StarterGui.StationServices`, `StarterGui.Construction` and `StarterGui.FlightNotices` (rbxmx, or the `ServerStorage.Dev.UI` installers), or send screenshots of each page. Until then the phone layout in section 5 is a rebuild of the structure, not a pixel copy.
2. **Station distance at real planet scale.** This spec follows `03-world.md` §2.5: station `Distance`, slot distances and `SlotClearance` are ×S (S = 3000). That keeps the Roblox composition: k = 2.1 (Galilean) to 5.9 (Tranquility) host radii, MERIDIAN 19,800 km from Earth's centre at 13,440 km altitude, Earth 37.5° wide. Confirm, or would you rather have stations in a fixed low orbit (for example 400–2,000 km altitude)? The hangar and pads stay ship-sized either way.
3. **Survey constants at real scale.** `RangeRadii 8`, `MinRange 2500 m` and `GiantRadius 5000 m` were tuned for 150–9,320 m bodies. Scale them by the planet scale factor? Otherwise every body counts as a "giant" and ranges become tens of thousands of km.
4. **Pilot name.** Always "PILOT", or let the player set a callsign? It appears on discoveries, "FOUNDED BY …" and the pilot card.
5. **First-discovery bonus.** In singleplayer every first survey outside Sol gets ×1.5. Keep it, or apply it with some probability to mimic other pilots?
6. **Roblox `Random` compatibility.** Generated stations (names, hosts, angles), deposits and station names come from Roblox's `Random` (PCG-based). Must they match Roblox exactly, which needs a bit-exact reimplementation shared with the galaxy port? Or is the same rules with a different generator fine? Sol is unaffected.
7. **Signage fonts.** GothamSSm (32 labels) is proprietary. OK to substitute Montserrat (OFL) or just use Michroma everywhere? Is bundling Michroma, Oswald, Nunito, Fredoka One and Source Sans Pro (all OFL) fine?
8. **Restart position.** Roblox restarts pilots who left in space at the system's star arrival point (`ShipClient.lua:766`). On a phone, where the OS kills apps often, should we restore the exact saved position instead?
9. **Decks.** Should DISEMBARK and QUARTERS (decks area) show as "NOT AVAILABLE" until decks are ported, or be hidden?
10. **Wall-clock timers.** Build timers and price drift use the device clock, so changing it could skip build time. Accept that, or keep a monotonic game clock (which then pauses while the app is closed and breaks "builds while offline")?
11. **Terminology.** Some phrases echo genre terms ("MASS LOCK", "DOCKING COMPUTER", "FIRST DISCOVERED BY") and one lore operator is "OUTER RIM SURVEY TRUST" ("Outer Rim" is a Star Wars term). Rename those, for example "STATION PROXIMITY LOCK" and "FAR REACH SURVEY TRUST"? "FRONTIER HAULERS UNION" is generic but shares a word with an Elite developer.
12. **New pilots.** Keep 1,500 CR docked at Meridian, and the training required first (`ShipConfig.Training.Required`)?
13. **Ships that are not ported yet.** Show them in the shipyard as "NOT AVAILABLE YET", as Roblox does? This depends on the ships area.

### 10.2 Risks

- **Converter scope.** 25k GUI frames with rotation and nested clipping, 171 text labels in 6 fonts, baked lighting. Mitigation: start with parts only (milestone 2a), then paint and text (2b); validate with screenshots against Studio captures (Q1).
- **Triangle and draw budget** on mid-range GPUs (Refinery ≈ 60k triangles at LOD0). Mitigation: segment counts by size, aggressive LOD1 at 3 km, one station in view.
- **Scale mismatch.** The models are small next to real-size planets; stations may look tiny or be hard to find. Mitigation: markers, LodDistance, Q2.
- **Collision.** Thin colliders (4–10 m walls) against fast ships: the 260 m/s lock inside 6 km and swept tests prevent tunnelling, but the cruise approach limit must hold up to the 6 km boundary.
- **Determinism.** `hash01` and `seedOf` must hash UTF-8 bytes and print integers without ".0"; Kotlin `round` must be half-up; the drift step uses integer Unix seconds.
- **Save safety.** Atomic write plus a `.bak`; schema version and migrations from day one; never write on the GL thread.
- **Clock manipulation** (Q10) and the real-time economy (prices change while the app is closed).
- **Dependencies.** Docking needs the flight-model override hooks; mining needs the surface session; placement needs the Sol scale decision. Milestones 3 and 6 can slip.

---

## Appendix A: test vectors (computed with a Python transcription of the Lua)

### A.1 Prices: `getPrice` at `now = 1,800,000,000` (epoch seconds)

Hash and drift vectors:
- `hash01("SOL-MERIDIAN|Water|1500000") = 0.7119186424407268`
- `hash01("abc") = 0.8015859524214274`
- `hash01("") = 0.05380838574842755`
- `drift("SOL-MERIDIAN","Water", 1.8e9) = 0.025430`

SOL-MERIDIAN (HighTech), as buy / sell / supply / demand:

| Item | Buy | Sell | Supply | Demand |
|---|---|---|---|---|
| Water | 169 | 156 | LOW | HIGH |
| HydrogenFuel | 146 | 134 | MED | MED |
| Grain | 357 | 329 | LOW | HIGH |
| SyntheticMeat | 417 | 385 | LOW | HIGH |
| ConstructionAlloys | 545 | 503 | MED | MED |
| Copper | 869 | 802 | LOW | HIGH |
| ConsumerGoods | 892 | 824 | LOW | HIGH |
| MedicalSupplies | 812 | 750 | HIGH | LOW |
| Titanium | 1499 | 1383 | LOW | HIGH |
| RoboticsParts | 1171 | 1080 | HIGH | LOW |
| Microprocessors | 1405 | 1297 | HIGH | LOW |
| RareIsotopes | 3315 | 3060 | LOW | HIGH |
| WaterIce | 92 | 85 | MED | MED |
| MineralOre | 163 | 151 | MED | MED |
| MetalOre | 195 | 180 | MED | MED |
| Helium3 | 899 | 830 | LOW | HIGH |
| PreciousOre | 1422 | 1313 | LOW | HIGH |

SOL-ARES (Extraction):

| Item | Buy | Sell |
|---|---|---|
| MetalOre | 145 | 134 |
| MineralOre | 119 | 110 |
| WaterIce | 70 | 65 |
| Helium3 | 698 | 644 |
| PreciousOre | 909 | 839 |
| Titanium | 781 | 721 |

### A.2 Survey values

| Body | Value |
|---|---|
| Sol (G, home) | 680 |
| Mercury | 320 |
| Venus | 1,150 |
| Earth | 4,320 |
| Mars | 320 |
| Jupiter | 1,050 |
| Saturn | 1,050 |
| Uranus | 930 |
| Neptune | 690 |
| Sol moons | 180 |

With Lochran (×1.5 then round to 10): 1,020 / 480 / 1,730 / 6,480 / 480 / 1,580 / 1,580 / 1,400 / 1,040 / 270.

Outside Sol:

| Body | Value |
|---|---|
| Terrestrial landable | 14,400 |
| Terrestrial landable, first discovery | 21,600 |
| Terrestrial landable, Lochran + first | 32,400 |
| Class O star | 7,200 |
| Ringed gas giant | 3,510 |

### A.3 Sol deposits: sorted → CR/h, letter, trait, own-station economy, best by hand

| Body | Sorted deposits (grade) | CR/h | Letter | Trait | Economy | Best by hand |
|---|---|---|---|---|---|---|
| Mercury | PreciousOre 2, MetalOre 5, MineralOre 3 | 7,640 | A | METAL-RICH | Extraction | PreciousOre |
| Venus | MineralOre 3, MetalOre 2 | 1,720 | D | MINERAL DEPOSITS | Extraction | MineralOre |
| Earth | – | 0 | - | INHABITED | Service | – |
| Luna | Helium3 2, MineralOre 3, MetalOre 1 | 4,140 | B | MIXED DEPOSITS | Refinery | Helium3 |
| Mars | MetalOre 3, MineralOre 3, WaterIce 2 | 2,460 | D | MIXED DEPOSITS | Extraction | MetalOre |
| Phobos | MetalOre 2, MineralOre 2 | 1,400 | D | METAL DEPOSITS | Extraction | MetalOre |
| Deimos | MineralOre 2, WaterIce 1 | 820 | E | POOR DEPOSITS | Extraction | MineralOre |
| Jupiter | Helium3 4 | 6,300 | A | HELIUM-3 RICH | Refinery | Helium3 (no landing) |
| Io | PreciousOre 1, MineralOre 4, MetalOre 2 | 4,400 | B | MINERAL-RICH | Extraction | PreciousOre |
| Europa | WaterIce 5, MineralOre 1 | 1,400 | D | ICE-RICH | Extraction | WaterIce |
| Ganymede | WaterIce 4, MineralOre 2, MetalOre 1 | 1,830 | D | ICE-RICH | Extraction | MineralOre |
| Callisto | MineralOre 2, WaterIce 3 | 1,180 | E | POOR DEPOSITS | Extraction | MineralOre |
| Saturn | Helium3 4 | 6,300 | A | HELIUM-3 RICH | Refinery | – |
| Titan | Helium3 1, MineralOre 2, WaterIce 3 | 2,580 | C | MIXED DEPOSITS | Refinery | Helium3 |
| Uranus | Helium3 4, WaterIce 3 | 6,840 | A | HELIUM-3 RICH | Refinery | – |
| Neptune | Helium3 3, WaterIce 3 | 4,740 | B | HELIUM-3 CLOUDS | Refinery | – |
| Triton | WaterIce 4, MineralOre 1 | 1,130 | E | ICE-RICH | Extraction | WaterIce |

### A.4 Lore and model hashes

- `StationLore.get`: "S12345-1" → h 176118 (accent 1, operator 2); "P1" → h 9256 (accent 5, operator 3); "S987654-2" → h 88290 (accent 1, operator 1).
- `pickModel("S12345-1", HighTech)` = RingStation; `pickModel("S12345-2", Extraction)` = HollowStation.

### A.5 Offsets and slots

- Meridian, host (828660,0,0), star at the origin: offset (0, 2684.46, 6029.40).
- `pickSlot`, planet r = 1130 (Distance 4573), host at the origin, star at (0,0,-1e7):

| Slot | Angle | Elevation | Offset |
|---|---|---|---|
| 1 | 22.5 | +28 | (-1545.2, 2146.9, -3730.4) |
| 2 | 67.5 | -28 | (-3730.4, -2146.9, -1545.2) |
| 3 | 112.5 | +28 | (-3730.4, 2146.9, 1545.2) |
| 4 | 157.5 | -28 | (-1545.2, -2146.9, 3730.4) |
| 5 | 202.5 | +28 | (1545.2, 2146.9, 3730.4) |
| 6 | 247.5 | -28 | (3730.4, -2146.9, 1545.2) |
| 7 | 292.5 | +28 | (3730.4, 2146.9, -1545.2) |
| 8 | 337.5 | -28 | (1545.2, -2146.9, -3730.4) |

### A.6 Formatting

`groupDigits`:

| Input | Output |
|---|---|
| 1500 | "1,500" |
| 999999 | "999,999" |
| 22000 | "22,000" |
| 0.4 | "0" |
| -1234567 | "-1,234,567" |

Refuel at fuel 0.37 with 1,000 CR: missing 63 units, units 63, cost 252, new fuel 1.0. At fuel 0 with 150 CR: units 37, cost 148, new fuel 0.37.

---

## Appendix B: content texts (verbatim)

### B.1 Sol station descriptions (`ShipStations.lua:41`)

| Station | Description |
|---|---|
| MERIDIAN | "THE BUSIEST PORT IN SOL. MERIDIAN BUILDS THE SHIP COMPUTERS HALF THE GALAXY FLIES WITH AND FEEDS ITSELF ON IMPORTED FOOD AND RAW METAL." |
| TRANQUILITY | "LUNAR FOUNDRIES AND THE OLDEST SHIPYARD IN THE SYSTEM. ALLOYS AND CONSUMER GOODS GO OUT, METALS AND FUEL COME IN." |
| ARES | "A MINING HUB ABOVE THE RED PLANET. TITANIUM AND COPPER LEAVE BY THE THOUSAND TONS; THE MINERS PAY WELL FOR FOOD AND MEDICINE." |
| GALILEAN | "SKIMMER FLEETS DIVE INTO JUPITER'S CLOUDS, AND THIS REFINERY TURNS THE GAS INTO FUEL FOR THE WHOLE SYSTEM." |
| TITAN | "GREENHOUSE DECKS IN SATURN'S SHADOW. GRAIN AND PROTEIN FOR ALL OF SOL, PAID FOR IN MEDICINE AND MACHINERY." |
| HELIOS | "SOLAR SMELTERS IN MERCURY'S SHADOW. THE HEAT IS FREE; THE ORE COMES IN FROM MARS." |

### B.2 Generated station blurbs (`ShipStations.lua:105`)

| Economy | Blurb |
|---|---|
| HighTech | "LABS, CLEAN ROOMS AND CHIP FOUNDRIES. IT SELLS TECHNOLOGY AND BUYS FOOD AND METAL." |
| Industrial | "FOUNDRIES AND ASSEMBLY HALLS. IT SELLS ALLOYS AND GOODS AND BUYS METAL, FUEL AND CHIPS." |
| Extraction | "A MINING OUTPOST. ORE AND ICE GO OUT; FOOD, MEDICINE AND MACHINERY COME IN." |
| Refinery | "A GAS REFINERY. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN." |
| Agricultural | "GREENHOUSE DECKS. FOOD GOES OUT; MEDICINE, MACHINERY AND GOODS COME IN." |
| Service | "A STOPOVER FOR CREWS AND TRAVELLERS. IT PAYS WELL FOR FOOD AND COMFORT." |

### B.3 Generated station names (`ShipStations.lua:88`)

NAMES (45): ARDEN, BECKETT, CALLOWAY, EKLUND, FARROW, GALEN, HOLLIS, IVERS, JANSEN, KOVAC, LINDQVIST, MARLOW, NAKAMURA, ORSINI, PETROV, QUILLON, RAVEL, SATO, THORNE, ULRIC, VANCE, WELLER, YATES, ZELLER, ADEYEMI, BRANDT, CASTELLAN, DELACROIX, ESPOSITO, FAIRBANKS, GRIEVE, HARTMANN, IMRE, JUAREZ, KESSLER, LAINE, MOREAU, NOVAK, ODUYA, PALMQVIST, RENNER, SOLBERG, TANAKA, VOSS, WINTER.

SUFFIX:

| Economy | Suffixes |
|---|---|
| HighTech | STATION, HUB, EXCHANGE |
| Industrial | YARDS, WORKS, STATION |
| Extraction | DEEP, DIGS, STATION |
| Refinery | REFINERY, SKIMWORKS, DEPOT |
| Agricultural | HABITAT, GARDENS, HYDROPONICS |
| Service | PORT, TERMINAL, RETREAT |

### B.4 Player station names and blurbs (`ShipResources.lua:335`, `:576`)

FIRST (40): ALDER, BASTION, CINDER, DRIFTWOOD, EMBER, FALLOW, GRANITE, HALCYON, IRONWOOD, JUNIPER, KESTREL, LANTERN, MAGPIE, NORTHWIND, ONYX, PILGRIM, QUARRY, RAVEN, SABLE, TEMPEST, UMBER, VESPER, WARDEN, YARROW, ZENITH, AURORA, BRIMSTONE, CORMORANT, DUSKFALL, FARHOLD, GLACIER, HEARTHSTONE, LODESTAR, NIGHTJAR, OSPREY, PEREGRINE, SKYLARK, THISTLE, WAYPOINT, COPPERLEAF.

LAST (10): OUTPOST, STATION, HOLD, REACH, ANCHORAGE, PLATFORM, DEPOT, HAVEN, WORKS, LANDING.

Blurbs (`%s` = host, upper case):

| Economy | Blurb |
|---|---|
| Extraction | "ITS DRILLS WORK THE DEPOSITS OF %s AROUND THE CLOCK. ORE AND ICE GO OUT; FOOD AND MACHINERY COME IN." |
| Refinery | "ITS SKIMMERS HARVEST HELIUM-3 FROM %s. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN." |
| Service | "A QUIET STOPOVER ABOVE %s FOR PASSING CREWS." |

### B.5 Commodity info lines (`ShipEconomy.lua:38`)

| Commodity | Info |
|---|---|
| WATER | "PURIFIED ICE MELT. EVERY HABITAT DRINKS, WASHES AND GROWS WITH IT." |
| HYDROGEN FUEL | "LIQUID HYDROGEN FOR REACTORS AND SHIP TANKS, REFINED FROM GAS GIANT CLOUDS." |
| GRAIN | "HYDROPONIC WHEAT AND RICE IN VACUUM-SEALED BALES." |
| SYNTHETIC MEAT | "CULTURED PROTEIN, DEEP FROZEN. CHEAPER THAN THE REAL THING AND NOBODY ASKS." |
| CONSTRUCTION ALLOYS | "STRUCTURAL BEAMS AND HULL PLATE FOR STATIONS AND SHIPYARDS." |
| COPPER | "REFINED CATHODES FOR WIRING AND POWER GRIDS." |
| CONSUMER GOODS | "CLOTHING, TOOLS AND HOUSEHOLD TECH FOR STATION MARKETS." |
| MEDICAL SUPPLIES | "SURGICAL KITS, ANTIBIOTICS AND RADIATION TREATMENT." |
| TITANIUM | "LIGHT, STRONG AND HEAT-PROOF. SHIPYARDS NEVER HAVE ENOUGH OF IT." |
| ROBOTICS PARTS | "ACTUATORS, SERVO PACKS AND DRONE CHASSIS." |
| MICROPROCESSORS | "RADIATION-HARDENED CHIPS FOR SHIP COMPUTERS AND HABITAT CONTROL." |
| RARE ISOTOPES | "SHIELDED CANISTERS OF REACTOR-GRADE ISOTOPES. HANDLE WITH CARE." |
| WATER ICE | "DIRTY ICE CUT FROM FROZEN WORLDS. MELTED AND FILTERED, IT BECOMES WATER AND FUEL." |
| MINERAL ORE | "SILICATES, BAUXITE AND RUTILE. THE START OF GLASS, CERAMICS AND ALLOYS." |
| METAL ORE | "UNREFINED IRON AND NICKEL ORE, STRAIGHT FROM THE DRILL. FOUNDRIES BUY IT BY THE TON." |
| HELIUM-3 | "FUSION FUEL SKIMMED FROM GAS GIANTS AND MOON DUST. EVERY REACTOR WANTS IT." |
| PRECIOUS ORE | "ORE VEINED WITH GOLD, PLATINUM AND IRIDIUM. RARE, HEAVY AND WORTH THE TRIP." |

### B.6 Ship descriptions (`ShipEconomy.lua:236`)

| Ship | Description |
|---|---|
| VARROW | "QUICK, FORGIVING AND CHEAP TO RUN. THE VARROW CARRIES A LITTLE CARGO, SURVEYS ANYTHING AND GETS NEW PILOTS HOME." |
| TORRIN | "THE FASTEST SMALL SHIP IN THE YARD: TWIN OUTRIGGER ENGINES, A LOW CANOPY AND A 4 T HOLD. A PILOT'S SHIP FOR URGENT RUNS." |
| CALDER | "QUICKER THAN A VARROW AND SHARPER IN THE TURN: THREE ENGINES, A 12 T HOLD AND A WIDE BRIDGE. NO SPECIALITY, NO WEAKNESS." |
| LOCHRAN | "A LONG-RANGE SURVEYOR FROM MERE & CAIRN: A WRAPAROUND CANOPY, A SENSOR DOME, AND A BUNK AND GALLEY ON BOARD." |
| BRANNOCK | "FOUR TIMES THE HOLD OF A VARROW ON A HEAVY ARMOURED SPINE. SLOWER TO TURN, BUT EVERY TRADE RUN PAYS FOUR TIMES OVER." |
| KESSOCK | "A WORKING PROSPECTOR FROM TALLAN DEEPWORKS: TWIN CUTTING LASERS, A ROOFTOP REFINERY AND SIX ORE BUNKERS." |
| DORNOCH | "AN ARMOURED WORKHORSE FROM ARDLAW HEAVY YARDS: 64 T ON A RACKED SPINE, EIGHT NOZZLES. HAULS, MINES AND SURVEYS." |
| MORVEN | "THE BIGGEST HOLD IN THE YARDS: 224 T BELOW, A CREW DECK WITH QUARTERS, MESS AND SICKBAY ABOVE. BUILT TO LAST." |

### B.7 News ticker (`ShipStationLore.lua:104`)

- "SOL TRANSIT AUTHORITY REMINDS PILOTS: SLOW DOWN INSIDE THE DOCKING ZONE."
- "HELIX CARTOGRAPHIC GUILD: UNCHARTED SYSTEMS PAY UP TO THREE TIMES MORE THAN SOL."
- "THE LONG ORBIT HOSTS OPEN MIC NIGHT EVERY TENTH CYCLE."
- "OKORO-BRANDT HEAVY INDUSTRIES ANNOUNCES LONGER WARRANTIES ON THE BRANNOCK FREIGHTER."
- "HALDEN DRIVE WORKS: THE VARROW REMAINS THE MOST FLOWN STARTER SHIP IN SOL."
- "PILOTS ARE ASKED TO KEEP HANGAR LIFTS CLEAR DURING DESCENT."
- "STATION CLOCKS RUN ON SOL STANDARD TIME."

### B.8 Generated-station operators (`ShipStationLore.lua:33`)

FRONTIER HAULERS UNION, DEEP RANGE CHARTER, KESSLER LANE AUTHORITY, BRIGHTWATER COOPERATIVE, NORTHSTAR LOGISTICS, OUTER RIM SURVEY TRUST (see Q11).

Generated-station accents: (80,170,255), (255,170,70), (110,230,140), (190,120,255), (70,220,220), (255,110,150).
