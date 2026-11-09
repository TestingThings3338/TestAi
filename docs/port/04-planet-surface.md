# 04 · Planet surface: landing scene, terrain, far field, surface profiles

Port reference for the ground of **Untitled Space Exploration** (Roblox, by AFloidDEV) → Android/Kotlin (`:core`). It covers everything between "the planet fills the window" and "the ship stands on the ground":

- the Roblox **landing scene** (`ShipSurface`): the body copied ×470, its frame, ground, re-anchoring, sky copies and every ground query the rest of the game uses;
- the **near voxel terrain** at the landing site (`ShipSurfaceTerrain`): 11 terrain styles, the image-driven sea/ice/desert classification, materials and colours, chunk streaming;
- the **far-field terrain mesh** (`ShipFarTerrain`) and the matching squash rule for objects on the ground (`ShipFarSquash`);
- the legacy **voxel spheres** for small bodies (`ShipPlanetTerrain`, switched off);
- the **surface profiles**: sky, air, cloud deck, light, entry glow and arrival title per body (`ShipSurfaceProfiles`), plus the class palettes (`ShipPalettes`);
- and the design that replaces the scene, the voxels and the far field on the phone: **one seamless, planet-fixed terrain from orbit to the ground** (section 9).

**Sources.** Lua paths are relative to `roblox/lua/`; `path:line` is the line where a function or table starts. Read in full:

- `ShipClient/ShipSurface.lua` (1079 lines), `ShipClient/ShipSurfaceTerrain.lua` (2753), `ShipClient/ShipFarTerrain.lua` (1858), `ShipClient/ShipFarSquash.lua` (632), `ShipClient/ShipPlanetTerrain.lua` (368);
- `ShipCore/ShipSurfaceProfiles.lua` (262), `ShipCore/ShipPalettes.lua` (239);
- `ShipConfig.Surface` (`ShipCore/ShipConfig.lua:743`), `ShipConfig.SurfaceTerrain` (`:833`), `ShipConfig.Terrain` (`:1011`).

Also used, for call sites and consumers only: `ShipClient.lua`; `ShipClient/ShipSky.lua` and `ShipClouds.lua` (they consume the profiles); `ShipAtmosphere.lua` (entry flow, arrival toast); `ShipEntryFx.lua` (glow colours); `ShipCallouts.lua`, `ShipDestruction.lua`, `ShipMining.lua`, `ShipTraffic.lua`, `ShipTraining.lua` (they consume the ground queries). The sibling specs `02-flight.md` (near-ground speed law, body reference frame, gravity) and `03-world.md` (Sol data, the scale S, palettes, `ShipSphereMesh`, image conversion) are referenced instead of repeated.

**Units and the three scales.** 1 stud = 1 m.

| Space | Scale | Earth radius | Used for |
|---|---|---:|---|
| Roblox *space* | ×1 | 2,120 m | the body as built; seen from orbit only |
| Roblox *landing scene* | ×470 (`Surface.SceneScale`) | 996,400 m | everything near the ground |
| Port world | ×3000 (S, `03-world.md` §1.3) | 6,360,000 m | everything |

**Every terrain number in this document is in landing-scene metres**: relief heights and wavelengths, crater radii, water levels, `ArmHeight`, cloud decks, exit and level altitudes. Section 9.4 says which of them keep their metre size in the port (almost all) and which follow the planet (the image classification). Numbers marked *computed* were derived with scratch scripts from the Lua values; nothing is estimated unless the text says so.

**Section numbers.** Sections 1–8 follow the common spec layout. Section 9 is the seamless design this area adds; the Kotlin port plan and the open questions follow as sections 10 and 11.

**Naming.** The player is PILOT. Style names, arrival titles and toast strings are the user's own Roblox strings and are ported verbatim.

---

## 0. Key facts in one screen

- **There is no terrain on the space-scale planet.** From orbit a planet is a sphere mesh carrying an uploaded image (`03-world.md` §2.7). Earth's clouds are painted into its image.
- **Landing is a scene switch.** The guided entry (key L, `ShipAtmosphere`) flies the ship down to `0.028·R` above the surface (Earth: 59 m in space = 27.9 km in the scene). There `ShipSurface:begin` swaps the whole solar system for a **separate scene** in which the same body is **×470**. The ground of that scene is the planet's own sphere mesh and image ×470 (flat triangle facets, up to 2.4 km below the ideal sphere on Earth). The picture is identical at the moment of the switch.
- **Real terrain only below 4,500 m.** Under `SurfaceTerrain.ArmHeight` (4,500 m, off again above 6,500 m), voxel terrain grows out of the image around the predicted level-off point: chunks of 32×32 voxels of 4 m (128 m), nearest first, about 1.5 km around the focus.
- **Behind the voxels a far-field mesh** shows the same formula to the horizon (`T` up to 160 km), squashed along each sight line into ≤ 1,580 m because Roblox only draws to its graphics-quality distance. It fades in over 1.2 s: "the relief grows out of the planet image, the colours go from the image to the palette".
- **The look is a per-body *style*** chosen by the arrival title (`TEMPERATE WORLD`, `RED DESERT`, `CRACKED ICE SHELL` …). Eleven styles: temperate, desert, titan, cratered, volcanic, icecrack, grooved, nitrogen, ridges, ocean, floes. Each is `relief(u, v) → height, tag` plus `paint(height, slope, tag) → top material, under material`, in metres on a tangent plane, built from Luau `math.noise` (improved Perlin).
- **Relief is modest:** a few hundred metres at most (computed upper bound about 420 m; 99 % of the ground lies below about 230 m): temperate peaks add up to 300 m, ridges 320 m, CANYON MESAS 195 m. Seas sit 8–12 m above the datum with a bed down to −24 m.
- **The image decides the big picture.** On Earth-like styles the planet image is read into *wet / cold / dry* fields: sea where the image shows sea, snow and ice where it is white (also under painted clouds, except over open sea), dunes where it is desert. Other styles tint their colours towards the image.
- **Ground queries** (altitude, up, gravity at 35 %, analytic floor and normal, speed limit `clamp(0.35·h, 260, 4000)`) serve flight, HUD, callouts, crash, mining and destruction. **A ship crashes only on ground it can see**: impact > 38 m/s on "solid" or "image" ground, never on a chunk that is missing or less than 0.6 s old.
- **Surface profiles** give each body its sky (air colour, haze, day/haze/space sky, zenith and dusk colours, star count), cloud deck (bottom, top, cover, colour), light (brightness, ambient, night ambient) and entry glow. Sol bodies are hand-made; generated bodies derive theirs from class, seed and atmosphere colour.
- **Legacy:** `ShipPlanetTerrain` (voxel spheres, `Terrain.SpherePlanets = false`) and the material half of `ShipPalettes` are unused.
- **Port:** one planet-fixed cube-sphere quadtree, present at every altitude; the same relief and paint functions in metres, evaluated on a 3-D domain on the sphere; the image classification follows the planet; no scene switch, no voxels, no squash, no re-anchoring, no sight measurement. Section 9.

---

## 1. Overview

### 1.1 What the player experiences (Roblox)

1. **Orbit.** The planet is a textured ball. Within `Atmosphere.HoverEntryAltitude` (0.35 R above the barrier) the HUD prompts "PRESS L TO LAND" (atmosphere area).
2. **Entry (HEAT).** After L, the ship is flown down; the air glows in the body's entry colour (Earth orange-pink, Mars deep red, Titan gold).
3. **Switch.** At `0.028·R` above the surface the game changes scene. Nothing visible happens: the ground is the same image, ×470, seen from the same place.
4. **Descent through the clouds.** On bodies with a deck (Earth 2,100–2,900 m) the ship slows to 600 m/s in the deck; the picture whites out, then the land appears under a dark deck.
5. **Terrain grows.** Below 4,500 m, voxel terrain appears around the point where the guidance will level off; the far field fades in over 1.2 s. Earth shows rolling hills, lakes, beaches, snowy peaks, steppe and dunes where the image shows desert, and the sea where the image shows sea. Mars shows dune fields, layered mesas and craters; Titan long dunes and dark methane lakes; Luna crater fields with boulders; Europa cracked ice with double ridges; Ganymede grooved bright bands; Io sulfur plains with lava; Triton pitted nitrogen ice with dark streaks; Venus scorched basalt plains under yellow haze.
6. **Level-off and release.** The guidance levels out at 900 m and hands over at 320 m/s.
7. **Free flight.** Speed limit grows with height above ground (`clamp(0.35·h, 260, 4000)` m/s). Gravity points to the planet centre at 35 % strength. Gear comes down below 60 m.
8. **Touchdown.** Safe below 38 m/s impact. A toast shows the body: **EARTH** / `TEMPERATE WORLD  ·  1.00 G  ·  ATMOSPHERE` for 6 s (atmosphere area). Above 38 m/s on visible ground: the destruction sequence, recovery at a station.
9. **Leaving.** Climbing above the exit altitude (8,000 m on Earth, less on small bodies) starts the guided ascent (CLIMB, then EMERGE in space).

### 1.2 Port direction for this area

- **Port the look and the rules 1:1:** the 11 styles with their relief and paint formulas, colours and materials; the image classification; the style selection by title and class; the profiles; the ground queries; the "crash only on visible ground" rule; the build priorities (ground under and ahead of the ship first).
- **Drop the Roblox machinery:** the landing scene and its ×470 frame, re-anchoring, the ground copy and the plate, the sky copies, voxels and material slots, the far-field squash and `ShipFarSquash`, the sight probe, `ShipPlanetTerrain`.
- **One seamless world:** at S = 3000 the planets are real-size (Earth 6,360 km). The terrain is planet-fixed and identical on every visit, visible from orbit to the ground, streamed by distance (section 9).
- **Singleplayer:** nothing in this area talks to the server; nothing changes.

### 1.3 The scale in one paragraph

The Roblox terrain was designed in the landing scene, where Earth has a radius of 996 km. The port's Earth is 6,360 km, 6.38 times larger. **The relief keeps its metre size** (hills of a few tens of metres, peaks up to 300 m above them, craters up to 520 m radius, water 8 m above the datum): it is what the pilot sees and flies through, and every ground rule (speed law, gear, crash) is in metres. **The classification follows the planet:** sea, ice and desert come from the planet image, which is stretched over the larger sphere; its texels grow from about 6 km (Roblox reads a 1024² image) to about 10 km (the full 4096² image at S = 3000). Curvature and horizon grow with the radius: from 4,500 m the horizon is 95 km in the Roblox scene and 239 km in the port. Section 9.4 has the full table.

---

## 2. Modules

### 2.0 Shared math and conventions

All of it is in `ShipClient/ShipSurfaceTerrain.lua:163-252` unless noted. The same helpers appear in `ShipFarTerrain` and `ShipPlanetTerrain`.

**Axes.** The landing scene is Roblox's Y-up world. The scene origin is the ground point under the anchor, `+Y` is "up" there (section 2.1.3).

**`noise(x, y, z)`** is Luau's `math.noise`: Ken Perlin's 2002 *improved noise* with the reference permutation table, evaluated in 32-bit float on the float-cast arguments, period 256 in each axis, exactly 0 at integer lattice points. The implementation is open source (Luau, `VM/src/lmathlib.cpp`); Appendix A reproduces it. Evidence that this is the right function (*computed* with a Python transcription): 5-octave `fbm` has standard deviation 0.165 and range ±0.65, where the Lua comment says "etwa -0.6..0.6 (Streuung 0.16)" (`:183`); the `floes` comment says the distribution of its noise has "median −0.01, about 0.14 per 10 % of area" (`:946`), the transcription gives median −0.004 and deciles 0.13–0.14 apart around it. Confirm with Studio golden values (Appendix B) before relying on bit-exactness.

**`smooth(a, b, x)`** (`:167`): `t = (x − a)/(b − a)`; 0 if `t ≤ 0`, 1 if `t ≥ 1`, else `t²(3 − 2t)`. **With `a > b` it is a falling step**: 1 below `b`, 0 above `a` (used for example as `smooth(0.55, 0.4, light)` and `smooth(0.58, 0.42, coast)`).

**`hash(i, j, k)`** (`:178`): `n = sin(i·12.9898 + j·78.233 + k·37.719)·43758.5453; return n − floor(n)` (double precision; `hash(1, 2, 3) = 0.020163`, computed).

**`fbm(x, y, z, octaves)`** (`:184`):

```
total = 0; amplitude = 1; frequency = 1; norm = 0
repeat octaves times:
    total += noise(x·frequency, y·frequency, z·frequency) · amplitude
    norm += amplitude; amplitude *= 0.5; frequency *= 2.07
return total / norm
```

Note that the third argument (the "salt" constant) is multiplied by the frequency too. Computed statistics (uniform random positions): std 0.206 / 0.181 / 0.170 / 0.165 for 2 / 3 / 4 / 5 octaves; 1st–99th percentile ±0.37…±0.45. `fbm·3` is therefore roughly a unit-scale field (std ≈ 0.5).

**`ridged(x, y, z, octaves)`** (`:196`): like `fbm`, but each octave adds `n²·amplitude` with `n = max(0, 1 − |noise|·2.2)`, frequency ×2.03. Output 0..1, mean 0.40, std 0.19–0.23 (computed). 1 on a ridge line.

**`dune(t)`** (`:212`): dune cross-section, period 1. `t = frac(t)`; if `t < 0.78` → `smoothstep01(t/0.78)` (long windward slope) else `1 − smoothstep01((t − 0.78)/0.22)` (short steep lee).

**`terrace(v, steps, sharp)`** (`:223`): `s = v·steps; base = floor(s); return (base + smooth(0.5 − sharp, 0.5 + sharp, s − base))/steps` (flat tables with steep edges).

**`shade(c, k)`** (`:229`) = `clamp(c·k)` per channel. **`lerp(a, b, t)`** = `Color3:Lerp` (linear in the stored 0..1 sRGB values). **`mix4(a, b, c, d, fx, fz)`** (`:238`) = bilinear: `a` at (0,0), `b` at (1,0), `c` at (0,1), `d` at (1,1).

**`toneOf(r, g, b)`** (`:246`): what the planet image shows at a point (colour 0..1):

```
light = (r + g + b)/3
wet  = smooth(0.04, 0.14, b − max(r, 0.92·g)) · smooth(0.55, 0.4, light)          -- dark blue: sea
cold = smooth(0.58, 0.75, min(r, g, b))                                              -- bright white: ice or painted cloud
dry  = smooth(0.06, 0.16, r − b) · smooth(0.3, 0.45, light) · smooth(0.06, 0, g − r) -- sand-coloured: desert
return { wet, cold, dry, r, g, b }
```

**The (u, v) plane** (section 2.2.4): metres on a planet-fixed tangent plane at the point where the terrain was armed. `o = {x, y, ca, sa}` are per-body offsets from the seed and `salt` a per-body constant (`ShipSurfaceTerrain:begin`, `:1280`):

```
angle = hash(seed, 3, 0.5)·π
o.x = (hash(seed, 1, 0.5) − 0.5)·2000      o.y = (hash(seed, 2, 0.5) − 0.5)·2000
o.ca = cos(angle)                           o.sa = sin(angle)
salt = (seed mod 997) + 0.123
```

The values for every Sol body are in section 3.6.

### 2.1 ShipSurface: the landing scene (`ShipClient/ShipSurface.lua`) · **dropped; its queries survive**

#### 2.1.1 Purpose and the reasons for a separate scene (header comment `:1-47`)

- **Why a scene.** At space scale Earth has a radius of about 2,100 studs; a ship next to it is a mountain. During entry the ship moves into a scene where the same body is `SceneScale` (470) times larger: Earth almost a million studs, the horizon curves only far up.
- **The ground is the planet image.** It is a copy of the body's sphere part with the same mesh, texture and orientation, only enlarged, so the switch is invisible ("Keep them as Texture", 2026-11-03).
- **Real terrain only far down** ("Yes create the Terrain", 2026-11-04): below `ArmHeight` it grows out of the image around the ship (section 2.2). While it stands, the scene re-anchors less often (voxels cannot rotate), gravity points to the centre, and altitude and footing follow the terrain.
- **Sight distance.** Roblox draws a part only if its nearest point is within the quality level's distance (about 600 studs at level 6). Every part of the scene is therefore squashed about the eye: position `e + (P − e)·k`, mesh `×k`. Direction and angular size stay the same; only depth shrinks. On the ground `k = 1`.
- **The frame.** `scene = centre + rotB·(local·s)`: `local` is the body's mesh space in real studs; `rotB` puts the anchor point on `+Y` and turns the compass so that Roblox's sun (set through `ClockTime`) shines exactly from the light direction; `centre` lies so deep that the ground at the anchor is at `y = 0`. After a long horizontal flight the scene re-anchors: numbers stay small, ship, camera and clouds move with it.
- **The light** comes from the direction Roblox lit the body in space (`ShipStarLight`, which the night shades follow), not exactly from the star. Roblox's sun stands at least 24° high there; with the true star direction the bright side seen from space lay up to 30° away from the day on the ground ("I landed on the sunny side", 2026-11-04).
- **The ground under the ship is the real triangle facet** of the mesh (`ShipSphereMesh`), not the ideal sphere: enlarged ×470, a facet centre lies more than 2 km below the sphere on Earth. An invisible plate on this facet carries the character and the mining beam.

#### 2.1.2 State (`ShipSurface.new`, `:220`)

| Field | Initial | Meaning |
|---|---|---|
| `active` | false | scene running |
| `body`, `bodyName` | nil | landed body part; `DisplayName` or name |
| `gravityG` | 1 | body `SurfaceGravityG` (default 0.5 at `begin`) |
| `profile`, `prepared` | nil | `ShipSurfaceProfiles.forBody`; pre-fetched at entry start |
| `sky` | `ShipSky.new(camera)` | lighting, atmosphere, dome, clouds (atmosphere area) |
| `terrain` | `ShipSurfaceTerrain.new(self)` | section 2.2 |
| `s` | 470 | scene scale |
| `rotB`, `centre`, `radius` | identity, 0, 1 | frame (section 2.1.3); `radius` = facet radius at the anchor in *space* studs |
| `meshScale`, `faceted` | | from `meshInfo` |
| `lightLocal`, `lightScene` | +Y | light direction in body space and in the scene |
| `spaceLight` | nil | `Lighting:GetSunDirection()` captured at `begin` |
| `star`, `sunSize` | nil, 1 | star part; its angular diameter in degrees |
| `skyEntries` | {} | copies of moons/planets in the sky |
| `moveParts`, `moveFrames` | {} | reused lists for `BulkMoveTo` |
| `lastReanchor` | | `os.clock()` of the last re-anchor |
| `plate`, `plateCentre`, `plateFacet` | | standing plate |
| `probeEye`, `probeUp`, `probeDip` | | sight-probe state of the last frame |

#### 2.1.3 Frame math (scene units)

- **`meshInfo(body)`** (`:78`) → `(scale, faceted)`. Faceted when the body has a `SpecialMesh` of type `FileMesh` whose id contains `smooth_sphere`: `scale = mesh.Scale.X`. Otherwise `radius / MESH_RADIUS` with `radius = SphereRadius or SurfaceRadius or Size.X/2`. `MESH_RADIUS = ShipSphereMesh.RADIUS = 85.628151` (`03-world.md` §2.8).
- **`alignUp(u)`** (`:88`): shortest rotation taking `u` to `+Y`: axis `u × Y`, angle `atan2(|u × Y|, u·Y)`; if `|u × Y| < 1e-9`: identity when `u·Y > 0`, else 180° about X.
- **`heading(v)`** (`:99`) = `atan2(−v.Z, v.X)` (compass angle like Roblox's sun path).
- **`towardLight(position, light)`** (`:104`): CFrame at `position` whose `+X` is `light` (the night ramp is baked along +X, `03-world.md` §2.2.4); helper up = Y unless `|light.Y| ≥ 0.9`, then Z.
- **`anchorAt(anchorLocal)`** (`:382`), `anchorLocal` = unit direction in body space:

  ```
  up    = alignUp(anchorLocal)                      -- anchor direction → +Y
  light = up · lightLocal
  sun   = ShipSky.solveSun(light.Y)                 -- sets ClockTime so Roblox's sun has this elevation; returns its direction
  yaw   = heading(sun.xz) − heading(light.xz)       -- 0 if either flat part < 1e-4
  rotB  = RotY(yaw) · up
  radius = localRadius(anchorLocal)                 -- facet radius in that direction, space studs
  centre = (0, −radius·s, 0)                        -- ground at the anchor at y = 0
  lightScene = rotB · lightLocal
  ```

- **Conversions** (`:399-427`):
  - `toScene(p) = centre + rotB·(bodyRot⁻¹·(p − bodyPos)·s)`; `toReal(q) = bodyPos + bodyRot·(rotB⁻¹·(q − centre)/s)` (the body may have moved since).
  - Vectors: `toSceneVector(v) = rotB·bodyRot⁻¹·v`, `toRealVector` the inverse; **no** factor s (callers multiply velocities by s themselves).
  - Poses: rotation `rotB·bodyRot⁻¹·R` and back.
- **`localRadius(dir)`** (`:347`): faceted → `|ShipSphereMesh.locate(dir).point|·meshScale`; else `meshScale·MESH_RADIUS`.
- **`refreshLight()`** (`:357`): `light = spaceLight`, else the unit vector to the star, else the `NightShade` part's `+X`, else the current sun; `lightLocal = bodyRot⁻¹·light`.
- **`groundAt(q)`** (`:433`) → `(y, normal, facet)`: the facet plane height under `q.x, q.z`:

  ```
  dirLocal = rotB⁻¹·(q − centre)
  if faceted: (facet, point, normal) = ShipSphereMesh.locate(dirLocal); point *= meshScale·s
  else:       facet = 0; normal = unit(dirLocal) (or +Y); point = normal·meshScale·MESH_RADIUS·s
  p0 = centre + rotB·point;  n = rotB·normal
  y  = (n.Y < 1e-3) ? p0.Y : p0.Y − (n.X·(q.X − p0.X) + n.Z·(q.Z − p0.Z))/n.Y
  ```

**Static helpers** (also used before landing):

| Function | Line | Formula |
|---|---|---|
| `surfaceRadius(body, dir)` | `:199` | facet radius in world direction `dir` (space studs) |
| `switchAltitude(body)` | `:209` | `(SurfaceRadius or meshScale·MESH_RADIUS)·Surface.SwitchAltitude` = `0.028·R` (space studs) |
| `levelAltitude(body)` | `:215` | `min(Atmosphere.LevelAltitude 900, switchAltitude·470·0.4)`; only Phobos and Deimos come out lower (790 m, computed) |

#### 2.1.4 Lifecycle

**`prepare(body)`** (`:337`): at entry start (`ShipClient.lua:490`), decode the sphere mesh (`ShipSphereMesh.warm()`) and fetch the profile, so the switch itself does not stutter.

**`begin(body, systemFolder, pose, velocity)`** (`:462`) → `(scenePose, sceneVelocity)`; called by the atmosphere's switch callback (`ShipClient.lua:504`):

1. `finish()` any previous scene.
2. Without a pose: start `(SurfaceRadius or 100)·(1 + 0.028)` above the body along its up vector.
3. `active = true`; `body`, `bodyName`, `gravityG = SurfaceGravityG or 0.5`; profile (prepared or fresh); `s = 470`; `meshScale, faceted = meshInfo(body)`; `lastReanchor = now`.
4. `sky:capture()` (remember space lighting **before** the sun is moved); `spaceLight = Lighting:GetSunDirection()`.
5. Star: the `IsStar` body in the system; `sunSize = deg(2·atan(radius/distance))` with `radius = SkyRadius or SurfaceRadius or Size.X/2`.
6. `refreshLight()`; `plan = planSky(body, systemFolder, pose.Position)`.
7. **Unparent the whole system folder** (it would lie in the middle of the scene).
8. `anchorAt(unit(bodyRot⁻¹·(pose.Position − bodyPos)))` (`+Y` if zero).
9. `build(plan)`; `terrain:begin(body, profile, faceted ? ground.mesh.TextureId : nil)`.
10. `scenePose = toScenePose(pose)`; **`sceneVelocity = toSceneVector(velocity)·s`** (a descent of 27 m/s in space becomes 12.7 km/s in the scene: the guided law `v = h/τ` is scale-free).
11. `sky:begin(profile, folder, {seed, radius = radius·s, toBodyDir(q) = unit(rotB⁻¹·(q − centre)), eye, height = eye.y − groundAt(eye).y, sunSize})`.
12. `updatePlate(scenePose.Position)`; `afterCamera(scenePose)`.

**`planSky(body, systemFolder, from)`** (`:543`): which other bodies get a copy in the sky. `familyRoot` = the body's model, or its parent model when the body is a moon. Every tagged non-star body with `distance > radius` and angular diameter `a = deg(2·atan(radius/distance))` is taken when **(family and `a ≥ MinAngularSize·0.2` = 0.18°) or `a ≥ FarMinAngularSize` (8°)**. Each entry lists the body and its visible parts (`collectParts`, `:169`: all non-MeshPart parts of its model that belong to it, not its moons).

**`build(plan)`** (`:571`): a folder `SurfaceScene` in workspace with:

- `Ground`: copy of the body part (mesh and texture), anchored, no collision, no query, no shadow;
- `GroundShade`: copy of the body's `NightShade` (transparency 1 at start, original transparency remembered);
- `GroundPlate`: invisible part `PlateSize × 4 × PlateSize` (2048 × 4 × 2048), collidable and queryable: the standing plate;
- `SkyBodies`: copies of every part of every sky entry (`copyPart` strips tags and attributes so gravity, markers and targeting never find them).

**`finish()`** (`:639`): `terrain:finish()`, `sky:finish()`, destroy the folder, re-parent the system, clear all state.

**`sweep()`** (`:668`): called **every frame in space** (`ShipClient.lua:3005`): removes any stray `SurfaceScene` or old `SurfaceSky` folder, a leftover `Lighting.ShipAtmosphere`, terrain or material slots of a landing that did not end cleanly, and resets the sky. Otherwise the sky in space would stay blue.

#### 2.1.5 Per frame: `step` before physics (`:712`)

`step(dt, position, allowReanchor)` returns the scene shift `T` (a CFrame) when it re-anchored, else nil. `ShipClient` applies `T` to the ship pose and velocity (`ShipClient.lua:2453-2458`); re-anchoring is allowed only when not on foot and the ship moves faster than 1 m/s (`allowReanchor`), never during the destruction sequence (`:2255`).

```
height = position.y − groundAt(position).y                -- above the facet, not the terrain
T = nil
if terrain.armed:
    if height > terrain.DisarmHeight (6500): terrain:disarm()              -- frame may wander again
    elif free and terrain:wantsReanchor(position):                           -- 16 km or 3.5° of tilt
        T = reanchor(position, force = true)
        if T: terrain:noteShift(T); position = T·position; terrain:rebuild(position)
elif terrain:canArm() and height < terrain.ArmHeight (4500):
    if free:
        T = reanchor(terrain:predictLanding(position, height), force = true) -- anchor jumps to the level-off point
        if T: terrain:noteShift(T); position = T·position
    terrain:arm()
if not T and free and not terrain.armed:
    T = reanchor(position)                                                   -- the normal, distance-based re-anchor
    if T: terrain:noteShift(T); position = T·position
terrain:update(dt, position, height)
updatePlate(position)
return T
```

**`reanchor(position, force)`** (`:756`):

```
if not force:
    flat = |(position.x, 0, position.z)|
    if flat < min(ReanchorDistance 2500, ReanchorShare 0.03 · radius·s): return nil
    if now − lastReanchor < ReanchorInterval 0.25: return nil
lastReanchor = now
oldRot, oldCentre = rotB, centre
refreshLight()
anchorAt(unit(oldRot⁻¹·(position − oldCentre)))
T = CFrame(centre) · (rotB·oldRot⁻¹) · CFrame(−oldCentre)       -- old scene coords → new scene coords
sky:transform(T); plateCentre = nil
return T
```

**`updatePlate(position)`** (`:781`):

- **Terrain armed:** the terrain carries itself. If the chunk under the ship exists (`isReadyAt`), the plate stops colliding. Otherwise the plate lies **horizontal** at `terrain:floorAt(x, z)` (moved only if it shifted more than 16 m), so the ship never falls through a missing chunk.
- **Not armed:** the plate collides and lies on the facet under the ship: `(y, normal, facet) = groundAt(position)`, re-placed when the facet changes or the foot point moved ≥ `PlateRefresh` (400 m); oriented `CFrame.lookAt(foot, foot + side, normal)` with `side = normal × Z` (or `× X`), shifted down by half its thickness.

#### 2.1.6 Per frame: `afterCamera` (`:833`)

Runs **after** the camera (`ShipClient.lua:2906`, also `:2273` in the destruction frame); otherwise the copies lag one frame and shake.

```
eye    = camera position;  terrain:noteEye(eye)
sight  = getSight()·Safety (0.9)
height = max(eye.y − groundAt(eye).y, 0.5)
k      = min(1, GroundShare (0.82)·sight / height)                -- squash of the ground copy about the eye
probeEye = eye; probeUp = unit(eye − centre); probeDip = acos((|eye − centre| − height)/|eye − centre|)
centreK = eye + (centre − eye)·k
Ground: mesh.Scale = base·s·k; CFrame = CFrame(centreK)·rotB
Shade (night side seen from above):
    low  = max(ShadeFadeLow 1500, ShadeMinGap 0.0055·radius·s)    -- Earth: 5,480 m
    high = max(ShadeFadeHigh 4000, 1.6·low)                        -- Earth: 8,768 m
    opacity = clamp((height − low)/(high − low), 0, 1)
    if opacity > 0: radius' = |eye − centre| − height/2 (halfway between ground and eye)
                    transparency = 1 − (1 − originalTransparency)·opacity
                    mesh.Scale = radius'/MESH_RADIUS·k; CFrame = towardLight(centreK, lightScene)
Sky entries (moons and big neighbours):
    eyeReal = toReal(eye); toScene = rotB·bodyRot⁻¹; up = unit(eye − centre)
    dip = acos((|eye − centre| − height)/|eye − centre|)          -- horizon depression
    for each entry: dir = toScene·unit(other.Position − eyeReal)
        visible = asin(dir·up) > −dip                               -- below the horizon: unparented
        if visible:
            probeDirection = dir; probeCos = cos(asin(min(1, 2.5·radius/distance)) + 3°)   -- incl. rings
            reach = min(SkyShare 0.94·sight, sky:domeReach(dir)·SkyDomeShare 0.85)
            kk = reach/distance
            each part: position = eye + toScene·(part.Position − eyeReal)·kk; mesh = base·kk
                       night shades face the space light (or the star), others keep toScene·rotation
BulkMoveTo(all parts, frames)
sky:update(dt, {eye, height, sunDir = lightScene, groundK = k, sight, exit = getExitAltitude()})
```

#### 2.1.7 Queries (`:965-1077`) and who calls them

| Query | Line | Result | Callers |
|---|---|---|---|
| `getAltitude(p)` | `:965` | armed: `p.y − terrain:floorAt(p.x, p.z)` (ground **or water**); else `p.y − groundAt(p).y`; `math.huge` if inactive | flight state, HUD, gear, guidance, callouts, training, destruction floor |
| `getUp(p)` | `:979` | `unit(p − centre)` (≈ +Y right after a re-anchor; tilts up to `TiltLimit` while terrain holds the frame) | pitch angle, gravity, crash |
| `getGravity(p)` | `:991` | `−getUp(p)·gravityG·Physics.StudsPerG (60)·Surface.GravityScale (0.35)` m/s² | thrust (`ShipClient.lua:2534`) |
| `floorHit(p, reach)` | `:1001` | armed only: `{Position = (x, floorAt(x,z), z), Normal = unit(−dx, 4, −dz)}`, `dx = floorAt(x+2,z) − floorAt(x−2,z)`, `dz` likewise; nil if `p.y − y > reach` | ground collision (`ShipClient.lua:2616`) |
| `groundState(p)` | `:1022` | `"image"` (no terrain), `"missing"`, `"fresh"`, `"solid"` (section 4.4) | crash rule |
| `getFloorParams()` | `:1032` | raycast filter: `workspace.Terrain` + the plate only (other parts below were invisible walls) | ground ray |
| `getSpeedLimit(alt)` | `:1048` | `clamp(alt·SpeedPerAltitude 0.35, MaxSpeed 260, MaxSpeedHigh 4000)` | speed limit, release throttle |
| `getExitAltitude()` | `:1054` | `max(1.5·levelAltitude, min(ExitAltitude 8000, ExitShare 0.6·switchAltitude·s))` | ascent trigger (`ShipClient.lua:3046`), sky dome fade |
| `getSight()` | `:289` | the sight source (`ShipImpostors:getSurfaceSight`: measured, else `UnmeasuredSight` 12,000), else `DefaultSight` 500 | far field, dome cap, squash |
| `probeReach(dir)` | `:304` | how far the sight probe may test in `dir` (none below `ProbeMinElevation` 6° above the horizon dip, within 5° of the sun or 3° beyond a body's rim, or with terrain within 6,000 m); `domeReach·0.8` | `ShipImpostors:updateSurface` |
| `getSpawnCFrame()` | `:1065` | 420 m above the anchor | unused |
| `clampPosition()` | `:1075` | nil ("the planet is round") | crash block (no-op) |
| `isReady()`, `getProgress()` | `:263` | `active`; 1 or 0 | `atmosphere:markTerrainReady()` |

#### 2.1.8 Port verdict

The scene, its frame (`rotB`, `centre`, `s`), `toScene/toReal`, re-anchoring, the ground and shade copies, the plate, the sky copies, the light trick (`solveSun`, `spaceLight`) and the sight probe all exist only because Roblox could not render a real-size planet one can stand on. **None of it is ported.** What survives is the **query API**, now answered by the seamless terrain (section 9.10): altitude above ground or water, up, gravity, analytic floor and normal, ground state, speed limit near the ground, surface colour.

---

### 2.2 ShipSurfaceTerrain: terrain at the landing site (`ShipClient/ShipSurfaceTerrain.lua`) · **port the formulas, not the voxels**

#### 2.2.1 Purpose (header `:1-62`)

- From space the planet stays the image (section 2.1). Only below `ArmHeight` does voxel terrain grow out of the ground around the ship: hills and lakes on Earth, dunes and mesas on Mars, methane lakes on Titan, crater fields on Luna, ice cracks on Europa. **What the arrival title promises ("CANYON MESAS", "CRACKED ICE SHELL") is what stands there.**
- **The image decides too.** For an uploaded image (all Sol bodies, all class images), the module reads it once per landing (`EditableImage`). On Earth it becomes sea, ice or desert: where the image shows sea the terrain is sea, and the edge of the terrain matches the image behind it. On other bodies the colours move towards the image at the landing site. A texel there measures about 6 km, so only chunk corners sample it; in between it is blended. White areas (ice, but also painted clouds) become snow, because from orbit that is what the ground around looks like — **except over the sea**: if the surroundings are sea, it is sea under a painted cloud too (`imageTone`). The water then takes the image's sea colour, dark and opaque (bright and transparent water stood out of the ocean around it).
- **How it sits on the image:** each column stands on the facet surface (`groundAt`) plus `Lift` plus relief. The relief is **planet-fixed**: it hangs on a tangent plane in body space (`project`), not on the scene. After a rebuild the same mountains stand at the same place. It mostly stays low (a few dozen metres) so the drop at the edge of the terrain to the image is small.
- **The frame stands still** while terrain exists (voxels cannot move or rotate): re-anchor only after `ReanchorDistance` or `TiltLimit`, then rebuild. Meanwhile gravity points to the planet centre.
- **Chunks:** 32×32 voxels (128 m), nearest first, a fixed time budget per frame. During descent the build aims at the predicted level-off point, in flight a little ahead (`focusFor`). Low down, the chunk under the ship and the path of the next `PathSeconds` are always built first (`ensurePath`). **A ship crashes only on ground it can see** (`groundState`): where a chunk was missing, the formula used to count as ground — an invisible wall ("invisible walls … I die instantly", 2026-11-04).
- **Materials:** with Expanded Terrain (Roblox 2026-10) each style has its own material slots (base material + colour, the same material in several colours). Without it, base materials with `SetMaterialColor`, one colour per material. Client-only, restored on departure.
- **Far field** (`ShipFarTerrain`, "Ultra" run 2026-11-06): with `FarField = true` voxels reach only just past the far field's seam; no ring from the spiral; beyond it an angle-preservingly squashed mesh shows the same formula to the horizon (`farPoint`, `lookColours`).

#### 2.2.2 Constants and options (`:89-137`)

| Constant | Value | Meaning |
|---|---|---|
| `VOXEL` | 4 | voxel edge (m) |
| `CHUNK` | 32 | voxels per chunk edge |
| `SPAN` | 128 | chunk edge (m) |
| `GRID` | 34 | height grid per chunk incl. a 1-voxel border (for slopes) |
| `FIRST_SLOT` | 23 | first custom material slot (0 air, 1 water, 2..22 built-in) |
| `NEAR_STEP` | 3 | texels between the 5×5 samples of `seaAround` |
| `FAR_STEPS` | 4096 | spiral entries inspected per frame at most (`:2053`) |

`option(name)` (`:130`) reads `ShipConfig.SurfaceTerrain[name]`, falling back to `DEFAULTS` (`:97`). Defaults that differ from the config: `RenderScale` 1 (config 4), `FarField` false (config true), `FieldMaxSight` 0 (config 8000). Chunk key: `keyOf(cx, cz) = cx·100000 + cz` (`:139`).

#### 2.2.3 Reading the planet image (`:1302-1692`)

**`loadImage(textureId)`** (`:1307`): only `rbxassetid://` textures. Asynchronously `AssetService:CreateEditableImageAsync` + `ReadPixelsBuffer` → `{pixels (RGBA8 buffer), w, h}`; a token discards stale results; `imagePending` until done. Failure → warning, terrain without image. **The read resolution is not in the code**; the header's "a texel measures about six kilometres" matches a 1024² read of the 4096² upload on Earth's 996 km scene radius (90° of arc over 256 px = 6.1 km, computed), which is consistent with `EditableImage`'s 1024 px size cap.

**`imageColour(uv)`** (`:1530`): bilinear RGB at texture coordinates `uv` (pixel centres at `uv·size − 0.5`, clamped to the image).

**`seaAround(cx, cy)`** (`:1558`), cached per texel: the sea share around texel `(cx, cy)` and the mean sea colour there:

```
sea = land = 0; sumRGB = 0
for dy in −2..2, dx in −2..2:                      -- 5 × 5 samples, NEAR_STEP = 3 texels apart
    (x, y) = (cx + 3dx, cy + 3dy); skip outside the image
    skip if alpha ≤ 200 or r + g + b ≤ 0.03         -- empty areas of the cube cross
    t = toneOf(pixel)
    if t.wet > 0.5: sea++, sumRGB += pixel
    elif t.cold < 0.5: land++                       -- white (cloud, ice) counts as neither
share  = (sea ≥ 4) ? sea/(sea + land) : 0
colour = (sea > 0) ? sumRGB/sea : nil
```

**`imageTone(direction)`** (`:1617`), direction in body space:

```
uv = ShipSphereMesh.uvAt(direction)                 -- 03-world §2.8 (cube-cross UVs)
tone = toneOf(imageColour(uv))
share = Σ over the 4 texels around uv of seaAround(texel).share · bilinearWeight
tone.sea = seaAround(texel with the largest weight that has a colour).colour
green = g − max(r, b)
clear = smooth(0.03, 0, green) · smooth(0.5, 0.3, tone.dry)  -- not clearly land (green or sandy)
k = smooth(0.6, 0.8, share) · clear
if k > 0: tone.wet = max(tone.wet, k); tone.cold *= 1 − k; tone.dry *= 1 − k
return tone   -- {wet, cold, dry, r, g, b, sea?}
```

So a painted cloud over open ocean stays ocean, while a cloud over land becomes snow (`cold`), and coast texels blend smoothly (the share is interpolated like the colour, so no edge appears at texel borders).

**`cornerTone(ix, iz)`** (`:1651`): tone at chunk corner `(ix·128, iz·128)`: `y = groundAt(x, 0, z).y`; `direction = unit(rotB⁻¹·((x, y, z) − centre))`; `imageTone(direction)`; cached until the next `bindFrame`.

**`toneAt(x, z)`** (`:1668`): `mix4` of the four corner tones of the chunk containing `(x, z)` (wet, cold, dry); 0, 0, 0 without image. **The voxels therefore sample the image only every 128 m** and blend bilinearly; the far field samples it at every mesh point (`farPoint`).

**`colourAt(x, z)`** (`:1683`): the image colour at the nearest chunk corner, for the dust of the destruction sequence (terrain rays report a wrong material with slots); nil without image.

#### 2.2.4 The planet-fixed plane (`:1694-1757`)

**`arm()`** (`:1734`), called with the scene just anchored at the level-off point:

```
toOrigin = rotB⁻¹·(0 − centre)          -- body-space vector from the centre to the scene origin (ground at the anchor)
r0 = |toOrigin|;  d0 = unit(toOrigin)
helper = (|d0.y| < 0.9) ? Y : X          -- body axes
e1 = unit(helper × d0)                   -- with helper = Y: horizontal, along a parallel
e2 = unit(d0 × e1)                       -- with helper = Y: along the meridian
lift = Lift (4); thickness = Thickness (10); topDepth = TopDepth (16)
image = loaded                           -- an image that arrived after the wait counts from the next arm
bindFrame(); clearAll(); applyTone(); armed = true; planFrom = nil; far:arm()
```

**`bindFrame()`** (`:1700`): rotates `d0, e1, e2` into the current scene (`rotB·…`), stores the scene `centre` and `r0`, clears the corner cache. `d0, e1, e2` live in body space, so the plane survives every re-anchor.

**`project(x, y, z)`** (`:1719`) → `(u, v)`: **gnomonic projection onto the tangent plane at distance `r0` along `d0`**:

```
q = (x, y, z) − centre
kk = r0 / (q · d)
u = (q · a)·kk;  v = (q · b)·kk          -- a = rotB·e1, b = rotB·e2, d = rotB·d0
```

`(u, v) = (0, 0)` is the arm point; `u` runs along `e1` (east-west), `v` along `e2` (north-south). **The plane is chosen anew at every `arm`** (after a climb above 6,500 m and a new descent, or a new landing), so the same place on the planet gets different terrain on different landings: the terrain is planet-fixed only within one armed period. The port makes it globally planet-fixed (section 9.4).

#### 2.2.5 Feature layers: craters, boulders, pits (`:254-371`)

Each layer is a grid in the (u, v) plane; every cell rolls (with `hash`) whether it carries a feature, where and how big. `collect` gathers once per chunk everything that reaches into it; each column then only walks that short list.

Layer fields: `kind` (`crater`, `boulder`, `pit`), `size` (cell edge, m), `chance`, `rmin`, `rmax`, `depth`, `rim`, `flat`, `reach` (spread, default 1.6), `skew` (default 2), `squash` (boulders, default 0.75).

**`collect(layers, u0, v0, u1, v1, salt, out)`** (`:263`):

```
for index, layer in layers (1-based):
    spread = layer.reach or 1.6; reachR = layer.rmax·spread; key = salt + index·7.31
    for i in floor((u0 − reachR)/size) .. floor((u1 + reachR)/size):
      for j in floor((v0 − reachR)/size) .. floor((v1 + reachR)/size):
        if hash(i, j, key) < chance:
            r  = rmin + (rmax − rmin)·hash(i, j, key + 1.7)^(skew or 2)
            cu = (i + 0.15 + 0.7·hash(i, j, key + 2.9))·size
            cv = (j + 0.15 + 0.7·hash(i, j, key + 4.3))·size
            outer = r·spread
            if the box [cu ± outer] × [cv ± outer] overlaps [u0, u1] × [v0, v1]:
                out += {cu, cv, r, outer², layer}
```

**`collectFar(…, minSize)`** (`:293`): the same, but skips layers whose `rmax·spread < 1.5·minSize` and features whose `r·spread < 1.5·minSize` (they would fall between mesh points or hit one point as a spike).

**`features(list, u, v)`** (`:326`) → `(height, tag)`:

```
total = 0; tag = nil; strongest = 0
for f in list with d² = |(u, v) − (cu, cv)|² < outer²:
    d = sqrt(d²)/r; h = 0; mark = nil
    crater:  dd = max(d, flat or 0)
             if d < 1: h = depth·(dd² − 1); mark = "crater"        -- bowl, flat floor for large craters
             x = (d − 1)·4.2
             h = (h + rim·exp(−x²))·r                               -- rim ring, also outside d = 1
             if 0.82 < d < 1.3: mark = "rim"
    boulder: if d < 1: h = r·sqrt(1 − d²)·(squash or 0.75); mark = "boulder"
    pit:     if d < 1: w = 1 − d²; h = −r·depth·w^1.5; mark = "pit"
    total += h
    if mark and |h| > strongest: strongest = |h|; tag = mark
return total, tag
```

#### 2.2.6 The styles (`:373-984`)

A style is a table:

| Field | Meaning |
|---|---|
| `key` | name for stats (set by `chooseStyle`) |
| `materials` | list of `{name, Roblox material, colour}`; without Expanded Terrain the first colour per base material wins |
| `layers` | feature layers (section 2.2.5), optional |
| `water` | water level W (m above the datum), nil = dry |
| `floor` | lowest relief allowed (only styles with water: −24); default 0 |
| `waterLook` | `{colour, transparency, reflectance, wave, speed}` → `Terrain.Water*` |
| `usesTone` | reads wet/cold/dry from the image itself (only `temperate`); otherwise the colours are tinted towards the image |
| `relief(u, v, list, o, wet, cold, dry)` | → height h (m above the datum), tag |
| `paint(h, slope, tag, u, v, wet, cold, dry)` | → top material name, under material name |

**Heights below `floor` (default 0) are lifted to it**: deeper ground would lie under the opaque planet image. This gives large craters their flat floors at the datum. `slope` is the gradient magnitude `|∇h|` (rise over run, section 2.2.11).

Every formula below is transcribed literally; `o` = seed offsets, `noise`, `fbm`, `ridged`, `smooth`, `dune`, `terrace` from section 2.0.

##### temperate (`temperate(c, level)`, `:395`) — Earth, TEMPERATE WORLD, GREEN LOWLANDS

`W = level or 8` (two voxels above the ground at least: with one, the cell holding the ground lost its water and sand showed in stripes, test 2026-11-04 with W = 4). `water = W`, `floor = −24`, `usesTone = true`, `waterLook = {RGB 26,94,108, transparency 0.72, reflectance 0.45, wave 0.12, speed 8}`.

Materials: `grass` Grass, `lush` LeafyGrass, `dirt` Ground, `sand` Sand, `mud` Mud, `rock` Rock, `cliff` Slate, `snow` Snow, `dry` Grass (colours from the set).

```
relief(u, v, _, o, wet, cold, dry):
    hills = fbm(u/900 + o.x, v/900 + o.y, 0.37, 5)·3
    roll  = fbm(u/2600 + o.y, v/2600 + o.x, 1.9, 3)·3
    mask  = smooth(0.2, 0.5, fbm(u/5200 + o.y, v/5200 + o.x, 4.1, 3)·3)
    peaks = mask > 0 ? ridged(u/1700 + o.x, v/1700 + o.y, 7.7, 4)·mask : 0
    calm  = 1 − 0.6·cold                                         -- ice sheets flatter
    h = W + 14 + (hills·22 + roll·18 + peaks·300)·calm + noise(u/48, v/48, 9.1)·3
    tag = nil
    if cold > 0.4: h = max(h, W + 1 + cold·6)                    -- no open water in the ice
    if dry > 0.35:                                               -- dry: hardly any lakes; dunes only in real desert
        h = max(h, W + 1.5)
        w = (u·0.83 + v·0.56)/190 + noise(u/650, v/650, 2.8)·0.9
        d = dune(w)·12·smooth(0.6, 0.9, dry);  h += d;  if d > 3: tag = "dune"
    if wet > 0.02:                                               -- coast: the image says sea, noise draws the line
        coast = wet + noise(u/700 + o.x, v/700 + o.y, 2.2)·0.2 + noise(u/170, v/170, 3.3)·0.06
        share = smooth(0.58, 0.42, coast)                        -- 1 = land, 0 = sea
        if share < 1:
            bed = W − 7 − 24·smooth(0.62, 0.88, coast) + noise(u/260, v/260, 6.1)   -- open sea ≥ 30 m deep
            h = bed + (h − bed)·share
            if share < 0.5: tag = "sea"
    return h, tag

paint(h, slope, tag, u, v, _, cold, dry):
    if h < W − 1:  return (tag ≠ "sea" and noise(u/90, v/90, 3.3) > 0.12) ? "mud" : "sand",  "sand"
    if h < W + 2.5 + noise(u/60, v/60, 5.5)·4 and cold < 0.5:  return "sand", "sand"
    if slope > 1.05:  return "cliff", "rock"
    if h > 215 − 260·cold + noise(u/150, v/150, 8.8)·50 and slope < 0.9:  return "snow", "rock"
    if slope > 0.62 + 0.4·dry:  return (noise(u/40, v/40, 2.2) > 0 ? "rock" : "dirt"), "rock"
    if dry > 0.3:                                                -- steppe to desert
        q = dry + noise(u/160, v/160, 4.7)·0.35
        if tag == "dune" or q > 0.9:  return "sand", "sand"
        if q > 0.4:
            bare = noise(u/150, v/150, 6.3) + noise(u/36, v/36, 2.9)·0.3
            return (bare > 0.42 ? "sand" : "dry"), "dirt"
        return "grass", "dirt"
    p = noise(u/260, v/260, 6.6) + noise(u/70, v/70, 1.4)·0.5
    if p > 0.22:  return "lush", "dirt"
    if p < −0.3:  return "dry", "dirt"
    if p < −0.2 and noise(u/30, v/30, 4.4) > 0.2:  return "dirt", "dirt"
    return "grass", "dirt"
```

##### desert (`desert(c, p)`, `:503`) — Mars, CANYON MESAS, WIND-SWEPT DUNES, ASH PLAINS, BASALT FLATS

Parameters `dunes` (1), `mesas` (1), `canyons` (0), `craters` (1). No water, floor 0.

Materials: `dust` Ground, `sand` Sand, `rock` Rock, `dark` Basalt, `layered` Sandstone, `pale` Salt.

Layers: crater `size 900, chance 0.3·craters, r 60–320, depth 0.16, rim 0.05, flat 0.35`; crater `220, 0.35·craters, 10–55, 0.22, 0.07`; boulder `48, 0.2, 3–8`.

```
relief(u, v, list, o):
    plains = fbm(u/1100 + o.x, v/1100 + o.y, 0.37, 4)·3·24
    m      = fbm(u/2600 + o.y, v/2600 + o.x, 3.9, 3)·3
    mesa   = terrace(smooth(0.15, 0.9, m), 3, 0.08)·150·mesas
    h = 14 + plains + mesa;  tag = nil
    if canyons > 0:
        cut = smooth(0.55, 0.85, ridged(u/1500 + o.x, v/1500 + o.y, 5.3, 2))·70·canyons
        h −= cut;  if cut > 8: tag = "canyon"
    field = smooth(0, 0.5, fbm(u/1900 + 0.7·o.x, v/1900 + 0.7·o.y, 6.2, 3)·3) · (1 − smooth(0.05, 0.3, m)) · dunes
    if field > 0.01:
        w = (u·0.83 + v·0.56)/210 + noise(u/700, v/700, 2.8)·0.9
        d = dune(w)·(14 + 10·noise(u/400, v/400, 5.1))·field;  h += d;  if d > 4: tag = "dune"
    crater, mark = features(list, u, v)
    h += crater + noise(u/36, v/36, 9.4)·2
    if mesa > 10 and not tag: tag = "mesa"
    return h, mark or tag

paint(h, slope, tag, u, v):
    if tag == "boulder": return "dark", "rock"
    if slope > 0.85:                                              -- layering on steep faces
        band = sin(h·0.21 + noise(u/80, v/80, 1.1)·2);  return (band > 0.2 ? "layered" : "rock"), "rock"
    if tag == "crater" or tag == "canyon": return "dust", "rock"
    if tag == "rim":  return "pale", "rock"
    if tag == "dune": return "sand", "sand"
    q = noise(u/180, v/180, 7.7)
    if q > 0.18: return "sand", "dust"
    if q < −0.25: return "rock", "rock"
    return "dust", "rock"
```

##### titan (`titan(c)`, `:582`) — Titan

`W = 8`, `water = 8`, `floor = −24` (deep lakes stay closed in the distance), `waterLook = {RGB 40,30,20, transparency 0.18, reflectance 0.85, wave 0.04, speed 4}`. Materials: `sand` Sand, `plain` Ground, `mud` Mud, `ice` Glacier, `rock` Rock, `dune` Sand.

```
relief(u, v, _, o):
    basin = fbm(u/1500 + o.x, v/1500 + o.y, 0.37, 4)·3
    field = smooth(−0.2, 0.3, basin)
    w = (u·0.97 + v·0.24)/300 + noise(u/1200, v/1200, 4.4)·0.5
    d = dune(w)·(20 + 10·noise(u/600, v/600, 6.6))·field
    rise  = smooth(0.25, 0.6, fbm(u/4000 + o.y, v/4000 + o.x, 2.2, 2)·3)
    hills = rise > 0 ? ridged(u/1300 + o.x, v/1300 + o.y, 8.8, 3)·rise·90 : 0
    h = 14 + basin·18 + d + hills + noise(u/40, v/40, 9.9)·1.5
    return h, (d > 5 ? "dune" : hills > 8 ? "ice" : nil)

paint(h, slope, tag, u, v):
    if h < W − 0.5: return "mud", "mud"
    if h < W + 1.5: return "plain", "mud"
    if slope > 1:   return "rock", "rock"
    if tag == "ice" or slope > 0.75: return "ice", "rock"
    if tag == "dune": return "dune", "sand"
    return (noise(u/150, v/150, 3.1) > 0.1 ? "sand" : "plain"), "sand"
```

##### cratered (`cratered(c, p)`, `:629`) — Luna, Mercury, Callisto, Moon class, Rocky "CRATERED HIGHLANDS", metal-rich

Parameters `dense` (1), `boulders` (1), `scarps` (0), `basins` (0). No water, floor 0. Materials: `regolith` Sand, `dust` Sand (same surface, slightly darker: soft patches instead of camouflage), `rock` Rock, `bright` Concrete, `mare` Basalt.

Layers: crater `1400, 0.45·dense, 120–520, depth 0.16, rim 0.045, flat 0.4`; crater `360, 0.5·dense, 24–120, 0.22, 0.07`; crater `90, 0.45·dense, 5–26, 0.25, 0.08`; boulder `44, 0.16·boulders, 2.5–7`; and when `boulders > 1` a boulder layer `140, 0.25, 8–18`.

```
relief(u, v, list, o):
    h = 18 + fbm(u/1400 + o.x, v/1400 + o.y, 0.37, 4)·3·16
    if scarps > 0:
        s = fbm(u/2400 + o.y, v/2400 + o.x, 6.1, 3)·3;  h += smooth(−0.05, 0.05, s)·40·scarps   -- 40 m steps
    tag = nil
    if basins > 0:
        flat = smooth(0.05, 0.3, fbm(u/3000 + o.x, v/3000 + o.y, 2.6, 3)·3)·basins
        if flat > 0: h = h·(1 − flat) + 6·flat;  if flat > 0.6: tag = "mare"
    crater, mark = features(list, u, v)
    return h + crater + noise(u/30, v/30, 5.5)·1.2, mark or tag

paint(_, slope, tag, u, v):
    if tag == "boulder" or slope > 0.9: return "rock", "rock"
    if tag == "rim":  return "bright", "regolith"
    if tag == "mare": return "mare", "regolith"
    if tag == "crater" and noise(u/200, v/200, 2.2) > 0.1: return "dust", "regolith"
    if noise(u/700, v/700, 4.4) < −0.4: return "mare", "regolith"
    spot = noise(u/240, v/240, 7.1) + noise(u/60, v/60, 3.7)·0.25
    return (spot > 0.3 ? "dust" : "regolith"), "regolith"
```

##### volcanic (`volcanic(c, p)`, `:693`) — Io, Venus, LAVA FIELDS, SCORCHED BASALT PLAINS, greenhouse

Parameters `flows` (0), `lava` (1). No water, floor 0. Materials: `sulfur` Sand, `orange` Ground, `pale` Salt, `basalt` Basalt, `lava` CrackedLava, `rock` Rock.

Layers: crater `1600, 0.35, 150–400, depth 0.1, rim 0.03, flat 0.6`; crater `300, 0.2, 20–70, 0.18, 0.05`; boulder `60, 0.12, 3–7`.

```
relief(u, v, list, o):
    plain   = fbm(u/1200 + o.x, v/1200 + o.y, 0.37, 4)·3
    channel = flows > 0 ? ridged(u/900 + o.y, v/900 + o.x, 3.4, 2)·14·flows : 0
    crater, mark = features(list, u, v)
    h = 20 + plain·16 + crater + noise(u/50, v/50, 3.3)·1.5 − channel
    if h < 9: return 9 − (9 − h)·0.12, "lava"                   -- lava lake: almost flat
    return h, mark

paint(_, slope, tag, u, v):
    if tag == "lava": return (lava > 0 ? "lava" : "basalt"), "basalt"
    if slope > 0.8 or tag == "boulder": return "basalt", "basalt"
    if tag == "rim": return "pale", "sulfur"
    q = noise(u/320, v/320, 1.9) + noise(u/90, v/90, 6.4)·0.4
    if q > 0.25: return "orange", "sulfur"
    if q < −0.3: return "pale", "sulfur"
    if q < −0.15 and noise(u/40, v/40, 2.7) > 0.25: return "basalt", "basalt"
    return "sulfur", "sulfur"
```

##### icecrack (`icecrack(c)`, `:748`) — Europa, CRACKED ICE SHELL

No layers, no water. Materials: `ice` Ice, `snow` Snow, `glacier` Glacier, `crack` Salt, `stain` Ground.

```
relief(u, v, _, o):
    plain = fbm(u/1600 + o.x, v/1600 + o.y, 0.37, 3)·3·6
    a = noise(u/650 + o.x, v/650 + o.y, 2.3)
    b = noise(u/1500 + o.y, v/1500 + o.x, 5.9)
    ridgeA = (1 − smooth(0, 0.05, |a|)) − (1 − smooth(0, 0.014, |a|))·0.75          -- double ridge: wall with a groove
    ridgeB = ((1 − smooth(0, 0.035, |b|)) − (1 − smooth(0, 0.01, |b|))·0.6)·0.8
    ridge  = max(ridgeA, ridgeB)
    chaos  = smooth(0.2, 0.35, fbm(u/2800 + o.x, v/2800 + o.y, 7.2, 2)·3)
    blocks = chaos > 0 ? terrace(noise(u/140, v/140, 4.4) + 0.5, 2, 0.06)·18·chaos : 0
    h = 14 + plain + ridge·16 + blocks
    return h, (ridge > 0.2 ? "crack" : blocks > 3 ? "chaos" : nil)

paint(_, slope, tag, u, v):
    if tag == "crack": return "crack", "ice"
    if slope > 0.9:    return "glacier", "ice"
    if tag == "chaos": return (noise(u/60, v/60, 3.6) > 0 ? "glacier" : "stain"), "ice"
    q = noise(u/400, v/400, 8.1)
    if q > 0.2:   return "snow", "ice"
    if q < −0.32: return "stain", "ice"
    return "ice", "ice"
```

##### grooved (`grooved(c)`, `:794`) — Ganymede

No water. Materials: `ice` Ice, `frost` Snow, `dark` Slate, `dust` Ground. Layers: crater `700, 0.35, 40–220, depth 0.18, rim 0.06, flat 0.3`; crater `160, 0.3, 8–40, 0.22, 0.07`.

```
relief(u, v, list, o):
    band   = fbm(u/2200 + o.x, v/2200 + o.y, 0.37, 3)·3
    bright = smooth(−0.05, 0.12, band)
    w = (u·o.ca + v·o.sa)/46 + noise(u/400, v/400, 6.6)·1.2       -- grooves along a seed direction
    g = sin(w·6.2832)
    crater, mark = features(list, u, v)
    h = 18 + band·10 + g²·14·bright + crater
    return h, mark or (bright > 0.5 ? "bright" : "dark")

paint(_, slope, tag, u, v):
    if tag == "rim":    return "frost", "ice"
    if tag == "crater": return "dust", "dark"
    if slope > 0.9:     return "dark", "dark"
    if tag == "bright": return (noise(u/120, v/120, 2.4) > 0.15 ? "frost" : "ice"), "ice"
    return (noise(u/90, v/90, 5.2) > 0.2 ? "dust" : "dark"), "dark"
```

##### nitrogen (`nitrogen(c)`, `:832`) — Triton, PITTED ICE PLAINS

No water. Materials: `frost` Snow, `nitro` Glacier, `ice` Ice, `streak` Ground. Layer: pit `size 90, chance 0.85, r 26–46, depth 0.16, reach 1.0, skew 1` (cantaloupe cells).

```
relief(u, v, list, o):
    plain = fbm(u/1800 + o.x, v/1800 + o.y, 0.37, 3)·3
    lake  = smooth(0.18, 0.3, plain)                              -- smooth frozen lakes
    cells = features(list, u, v)                                  -- height only
    h = 22 + plain·12 + cells·(1 − lake)
    if lake > 0: h = h·(1 − lake) + 26·lake
    streak = noise((u·0.9 + v·0.43)/900 + o.x, (v·0.9 − u·0.43)/90 + o.y, 4.4)   -- 10:1 stretched dark plumes
    return h, (lake > 0.5 ? "lake" : streak > 0.3 ? "streak" : nil)

paint(_, slope, tag, u, v):
    if tag == "lake":   return "ice", "ice"
    if tag == "streak": return "streak", "nitro"
    if slope > 0.8:     return "nitro", "ice"
    return (noise(u/220, v/220, 1.7) > 0.12 ? "nitro" : "frost"), "ice"
```

##### ridges (`ridges(c, p)`, `:869`) — JAGGED RIDGELANDS, GLACIER RANGES

Parameters `icy` (false), `snowline` (230). No water. Materials: `low` (icy ? Ice : Ground), `rock` (icy ? Glacier : Rock), `peak` Snow, `scree` (icy ? Ice : Slate), `sand` Sand.

```
relief(u, v, _, o):
    base = fbm(u/1200 + o.x, v/1200 + o.y, 0.37, 4)·3·24
    r    = ridged(u/1400 + o.y, v/1400 + o.x, 2.9, 5)
    mask = smooth(−0.15, 0.2, fbm(u/4200 + o.x, v/4200 + o.y, 6.6, 2)·3)
    return 14 + base + r²·320·(0.25 + 0.75·mask), nil

paint(h, slope, _, u, v):
    if h > snowline + noise(u/140, v/140, 3.9)·40 and slope < 1.1: return "peak", "rock"
    if slope > 1:   return "rock", "rock"
    if slope > 0.6: return "scree", "rock"
    if h < 40 and noise(u/200, v/200, 5.5) > 0.2: return "sand", "low"
    return "low", "rock"
```

##### ocean (`ocean(c, p)`, `:904`) — ARCHIPELAGO WORLD, ENDLESS SEAS, REEF SHALLOWS

Parameters `level` (W, 10), `bias` (−6), `amp` (30), `hills` (80), `deep`. `water = W`, `floor = −24`. `waterLook`: deep `{RGB 14,58,92, transparency 0.4, reflectance 0.4, wave 0.2, speed 10}`, else `{RGB 20,104,126, 0.6, 0.4, 0.16, 9}`. Materials: `sand` Sand, `reef` Limestone, `grass` Grass, `rock` Rock, `lush` LeafyGrass.

```
relief(u, v, _, o):
    n    = fbm(u/1100 + o.x, v/1100 + o.y, 0.37, 5)·3
    hill = smooth(0.3, 0.7, n)·ridged(u/700 + o.y, v/700 + o.x, 4.2, 3)·hills
    return W + bias + n·amp + hill + noise(u/36, v/36, 8.2)·1.5, nil

paint(h, slope, _, u, v):
    if h < W − 3: return (noise(u/70, v/70, 1.3) > 0.1 ? "reef" : "sand"), "sand"
    if h < W + 3: return "sand", "sand"
    if slope > 0.8: return "rock", "rock"
    return (noise(u/120, v/120, 6.2) > 0.15 ? "lush" : "grass"), "sand"
```

##### floes (`floes(c, p)`, `:948`) — gas and ice giants (unreachable: giants are not landable)

"Can you make that you can land on every planet?" (2026-11-04): no solid ground on giants; deep under the cloud deck lies a dense calm liquid with drifting ice floes. Later the same day giants became unlandable ("you cant land into Gas Giants", `ShipSystemBuilder.lua:326`), so this style is dead code. `W = 10`, `edge = (0.5 − (cover or 0.45))·1.4 − 0.082` (from the measured noise distribution: median −0.01, 0.14 per 10 % of area; computed: cover 0.45 → 47 % floes, 0.7 → 72 %), `water = W`, `floor = −24`, `waterLook = {c.sea, 0.3, 0.6, 0.08, 5}`. Materials: `ice` Ice, `snow` Snow, `glacier` Glacier, `slush` Salt.

```
relief(u, v, _, o):
    n = fbm(u/1100 + o.x, v/1100 + o.y, 0.37, 4)·3 + noise(u/260, v/260, 3.7)·0.12
    floe  = smooth(edge, edge + 0.08, n)                           -- flat tables with steep edges
    ridge = ridged(u/700 + o.y, v/700 + o.x, 5.1, 2)·floe          -- pressure ridges
    bed = W − 34 + noise(u/300, v/300, 6.4)·2
    top = W + 3 + max(n, 0)·6 + ridge²·18 + noise(u/45, v/45, 2.9)·0.8
    h = bed + (top − bed)·floe
    return h, (floe < 0.5 ? "sea" : ridge > 0.55 ? "ridge" : nil)

paint(h, slope, tag, u, v):
    if h < W − 1: return "slush", "slush"
    if slope > 0.8 or tag == "ridge": return "glacier", "ice"
    q = noise(u/140, v/140, 4.1) + noise(u/40, v/40, 1.3)·0.3
    return (q > 0.15 ? "snow" : q < −0.35 ? "slush" : "ice"), "ice"
```

##### Reference statistics (computed, for the port's tests)

Plane mode, no image (wet = cold = dry = 0), Earth offsets, 4,000 random points in a 80 × 80 km square, slope from ±4 m differences (statistical tolerance about ±2 percentage points):

| Style | h p1 / p50 / p99 / max (m) | Below water | Top materials (share) |
|---|---|---:|---|
| temperate (Earth set) | −9.7 / 30.4 / 226.8 / 299.2 | 11.6 % | grass 41.7, lush 18.8, dry 14.3, sand 11.7, dirt 5.0, mud 3.4, rock 2.9, snow 1.6, cliff 0.8 |
| desert (Mars: craters 0.8, mesas 0.7) | 0 / 22.2 / 134.9 / 146.0 | – | dust 42.3, sand 34.9, rock 17.2, pale 3.8, layered 1.2, dark 0.7 |
| cratered (Luna) | 0 / 18.1 / 38.5 / 53.1 | – | regolith 64.3, dust 14.6, bright 13.0, mare 7.7, rock 0.5 |
| ridges | 0 / 38.4 / 205.6 / 277.9 | – | low 68.7, sand 13.6, scree 10.4, rock 7.1, peak 0.2 |
| ocean (ARCHIPELAGO) | −24 / 6.2 / 96.8 / 121.7 | 58.2 % | sand 45.0, grass 23.8, reef 19.9, lush 9.7, rock 1.7 |

#### 2.2.7 Colours and style selection (`:986-1150`)

**Sol, by hand** (RGB, `:991-1034`):

| Set | Entries |
|---|---|
| `EARTH` | grass 84,124,58 · lush 62,102,44 · dirt 104,86,64 · sand 204,190,146 · mud 84,72,56 · rock 112,108,102 · cliff 96,92,88 · snow 238,242,248 · dry 140,140,82 |
| `MARS` | dust 150,80,48 · sand 176,94,54 · rock 116,66,44 · dark 66,44,36 · layered 170,102,66 · pale 194,134,96 |
| `TITAN` | sand 96,64,38 · plain 86,62,42 · mud 52,40,28 · ice 196,176,136 · rock 128,108,82 · dune 122,84,48 |
| `LUNA` | regolith 132,132,128 · dust 126,126,122 · rock 98,98,96 · bright 166,166,162 · mare 100,100,98 |
| `MERCURY` | regolith 120,112,102 · dust 114,108,100 · rock 90,86,80 · bright 152,146,138 · mare 88,84,78 |
| `CALLISTO` | regolith 84,76,68 · dust 78,70,62 · rock 90,84,78 · bright 196,196,192 · mare 60,54,48 |
| `IO` | sulfur 214,194,84 · orange 198,118,52 · pale 232,224,168 · basalt 44,36,32 · lava 164,66,30 · rock 96,80,60 |
| `EUROPA` | ice 212,224,232 · snow 238,242,246 · glacier 172,198,214 · crack 150,102,80 · stain 128,98,84 |
| `GANYMEDE` | ice 196,200,204 · frost 226,228,230 · dark 98,92,86 · dust 112,104,96 |
| `TRITON` | frost 232,218,214 · nitro 212,204,208 · ice 196,204,214 · streak 90,80,78 |
| `ASH` (ASH PLAINS) | dust 96,94,92 · sand 116,112,108 · rock 70,66,64 · dark 40,38,38 · layered 84,80,78 · pale 150,146,140 |
| `VENUS` | sulfur 150,118,76 · orange 128,92,58 · pale 182,156,112 · basalt 72,58,46 · lava 150,70,34 · rock 104,86,66 |

**Derived sets for generated bodies** (`:1036-1070`); `p` = `ShipPalettes.colours(class)` (section 2.7):

| Function | Formula |
|---|---|
| `temperateSet` | grass = low · lush = shade(low, 0.82) · dirt = lerp(low, high, 0.55) · sand = shore · mud = shade(high, 0.7) · rock = high · cliff = shade(high, 0.85) · snow = ice · dry = lerp(low, shore, 0.45) |
| `desertSet` | dust = low · sand = shore · rock = high · dark = shade(high, 0.6) · layered = lerp(shore, high, 0.5) · pale = peak |
| `moonSet` | regolith = low · dust = shade(low, 0.95) · rock = high · bright = peak · mare = shade(high, 0.88) (little contrast: the image around is evenly grey from above) |
| `volcanicSet` | sulfur = low · orange = high · pale = peak · basalt = shore · lava = RGB 190,82,34 · rock = shade(high, 0.8) |
| `iceSet` | ice = low · snow = peak · glacier = shore · crack = lerp(high, RGB 150,100,80, 0.6) · stain = lerp(shore, RGB 130,100,84, 0.5) |
| `ridgeSet` | low = low · rock = high · peak = ice · scree = shade(high, 0.85) · sand = shore |
| `nitrogenSet` | frost = peak · nitro = high · ice = low · streak = shade(shore, 0.55) |
| `oceanSet` | sand = shore · reef = lerp(shore, RGB 220,200,170, 0.4) · grass = low · rock = high · lush = shade(low, 0.8) |
| `floeSet` | sea = shade(high, 0.5) · ice = ice · snow = peak · glacier = lerp(low, high, 0.5) · slush = shore |

Resolved values for every class/title pair that can occur (computed, RGB):

| Class (palette) | Set | Colours |
|---|---|---|
| Terrestrial World | temperateSet | grass 74,116,56 · lush 61,95,46 · dirt 93,105,66 · sand 196,182,138 · mud 76,67,52 · rock 108,96,74 · cliff 92,82,63 · snow 238,244,250 · dry 129,146,93 |
| Ocean World | oceanSet | sand 190,180,145 · reef 202,188,155 · grass 66,110,60 · rock 96,92,72 · lush 53,88,48 |
| Rocky Body | moonSet | regolith 122,104,86 · dust 116,99,82 · rock 96,86,76 · bright 140,134,128 · mare 84,76,67 |
| Rocky Body | desertSet | dust 122,104,86 · sand 150,130,108 · rock 96,86,76 · dark 58,52,46 · layered 123,108,92 · pale 140,134,128 |
| Rocky Body | ridgeSet | low 122,104,86 · rock 96,86,76 · peak 210,212,216 · scree 82,73,65 · sand 150,130,108 |
| Volcanic Body | volcanicSet | sulfur 74,44,38 · orange 120,52,30 · pale 178,74,32 · basalt 58,38,34 · lava 190,82,34 · rock 96,42,24 |
| Volcanic Body | desertSet (BASALT FLATS) | dust 74,44,38 · sand 58,38,34 · rock 120,52,30 · dark 72,31,18 · layered 89,45,32 · pale 178,74,32 |
| Ice World | iceSet | ice 198,218,230 · snow 240,248,255 · glacier 176,196,210 · crack 176,153,145 · stain 153,148,147 |
| Ice World | ridgeSet (icy) | low 198,218,230 · rock 214,232,242 · peak 248,252,255 · scree 182,197,206 · sand 176,196,210 |
| Ice World | nitrogenSet | frost 240,248,255 · nitro 214,232,242 · ice 198,218,230 · streak 97,108,116 |
| Moon | moonSet | regolith 148,144,138 · dust 141,137,131 · rock 104,100,96 · bright 172,168,162 · mare 92,88,84 |
| Greenhouse World | volcanicSet | sulfur 214,186,124 · orange 168,132,86 · pale 226,204,156 · basalt 196,168,108 · lava 190,82,34 · rock 134,106,69 |
| Metal-Rich / High Metal | moonSet | (Metal-Rich) regolith 138,132,124 · dust 131,125,118 · rock 96,92,88 · bright 168,162,152 · mare 84,81,77 |
| Gas Giant | floeSet | sea 82,64,45 · ice 238,228,208 · snow 232,214,184 · glacier 189,158,119 · slush 196,164,118 |
| Ice Giant | floeSet | sea 60,100,110 · ice 220,245,250 · snow 180,230,240 · glacier 105,185,210 · slush 70,140,180 |

**Selection tables:**

`TITLE_STYLES` (`:1073`), keyed by arrival title, each called with `p = ShipPalettes.colours(class)`:

| Title | Style |
|---|---|
| CRATERED HIGHLANDS | cratered(moonSet, dense 1.1) |
| CANYON MESAS | desert(desertSet, dunes 0.2, mesas 1.3, canyons 1, craters 0.4) |
| WIND-SWEPT DUNES | desert(desertSet, dunes 1.6, mesas 0.2, craters 0.3) |
| JAGGED RIDGELANDS | ridges(ridgeSet) |
| CRACKED ICE SHELL | icecrack(iceSet) |
| GLACIER RANGES | ridges(ridgeSet, icy, snowline 160) |
| PITTED ICE PLAINS | nitrogen(nitrogenSet) |
| LAVA FIELDS | volcanic(volcanicSet, flows 1) |
| ASH PLAINS | desert(**ASH** fixed colours, dunes 0.8, mesas 0.3, craters 0.3) |
| BASALT FLATS | desert(desertSet, dunes 0, mesas 0.8, craters 0.5) |
| ARCHIPELAGO WORLD | ocean(oceanSet, bias −4, amp 34, hills 90) |
| ENDLESS SEAS | ocean(oceanSet, bias −14, amp 22, hills 40, deep) |
| REEF SHALLOWS | ocean(oceanSet, level 8, bias −3, amp 10, hills 20) |
| TEMPERATE WORLD | temperate(temperateSet) |
| GREEN LOWLANDS | temperate(temperateSet, level 12) |
| AIRLESS REGOLITH | cratered(moonSet) |
| DUSTY BASINS | cratered(moonSet, basins 1, dense 0.6) |
| BOULDER FIELDS | cratered(moonSet, boulders 3, dense 0.7) |
| SCORCHED BASALT PLAINS | volcanic(volcanicSet, lava 0, flows 0.6) |
| DRIFTING ICE FLOES, METHANE ICE FLOES | floes(floeSet) |
| AMMONIA ICE SHELVES, FROZEN SLUSH SHELVES | floes(floeSet, cover 0.7) |

`CLASS_STYLES` (`:1100`), when the title matches nothing: Terrestrial → TEMPERATE WORLD; Ocean → ARCHIPELAGO WORLD; Water → ENDLESS SEAS; Rocky → CRATERED HIGHLANDS; Metal-Rich and High Metal → cratered(moonSet, dense 1.2); Volcanic → LAVA FIELDS; Ice World → CRACKED ICE SHELL; Ammonia → PITTED ICE PLAINS; Moon → AIRLESS REGOLITH; Greenhouse → volcanic(volcanicSet, lava 0); Gas Giant → DRIFTING ICE FLOES; Ice Giant → METHANE ICE FLOES.

`SOL_STYLES` (`:1117`), by body name, **only if the profile title is the Sol one** (a generated body may share a name):

| Body | Title | Style |
|---|---|---|
| Earth | TEMPERATE WORLD | temperate(EARTH) |
| Mars | RED DESERT | desert(MARS, craters 0.8, mesas 0.7) |
| Titan | DUNE SEAS UNDER ORANGE HAZE | titan(TITAN) |
| Luna | AIRLESS REGOLITH | cratered(LUNA) |
| Mercury | SCORCHED CRATER FIELDS | cratered(MERCURY, scarps 1, dense 1.2) |
| Io | SULFUR VOLCANIC PLAINS | volcanic(IO) |
| Europa | CRACKED ICE SHELL | icecrack(EUROPA) |
| Ganymede | GROOVED ICE TERRAIN | grooved(GANYMEDE) |
| Callisto | ANCIENT CRATER SHIELD | cratered(CALLISTO, dense 1.5) |
| Triton | FROZEN NITROGEN PLAINS | nitrogen(TRITON) |
| Venus | SCORCHED BASALT PLAINS | volcanic(VENUS, lava 0, flows 0.6) |

**`chooseStyle(body, profile)`** (`:1131`): stars get none; `class = profile.class or BodyClass or "Rocky Body"`; `name = profile.name or DisplayName`; if `SOL_STYLES[name]` exists **and** its title equals `profile.title`, use it (`key = name`); else `TITLE_STYLES[title] or CLASS_STYLES[class] or TITLE_STYLES["CRATERED HIGHLANDS"]`, called with `ShipPalettes.colours(class)` (`key = title or class`).

**What every Sol landable body gets** (profile title from section 2.6; computed):

| Body | Seed | Title | Style | Water | Image use |
|---|---:|---|---|---|---|
| Mercury | 1 | SCORCHED CRATER FIELDS | cratered(MERCURY, scarps 1, dense 1.2) | – | tint |
| Venus | 2 | SCORCHED BASALT PLAINS | volcanic(VENUS, lava 0, flows 0.6) | – | tint |
| Earth | 3 | TEMPERATE WORLD | temperate(EARTH) | W 8 | **wet/cold/dry** |
| Mars | 4 | RED DESERT | desert(MARS, craters 0.8, mesas 0.7) | – | tint |
| Luna | 94 | AIRLESS REGOLITH | cratered(LUNA) | – | tint |
| Phobos | 125 | BOULDER FIELDS (by seed: 125 mod 3 = 2) | cratered(moonSet(Moon), boulders 3, dense 0.7) | – | tint |
| Deimos | 126 | AIRLESS REGOLITH (126 mod 3 = 0) | cratered(moonSet(Moon)) | – | tint |
| Io | 156 | SULFUR VOLCANIC PLAINS | volcanic(IO) | – | tint |
| Europa | 157 | CRACKED ICE SHELL | icecrack(EUROPA) | – | tint |
| Ganymede | 158 | GROOVED ICE TERRAIN | grooved(GANYMEDE) | – | tint |
| Callisto | 159 | ANCIENT CRATER SHIELD | cratered(CALLISTO, dense 1.5) | – | tint |
| Titan | 187 | DUNE SEAS UNDER ORANGE HAZE | titan(TITAN) | W 8 | tint |
| Triton | 249 | FROZEN NITROGEN PLAINS | nitrogen(TRITON) | – | tint |

#### 2.2.8 Materials, water and tinting (`:1152-1523`)

- **`slotsWork()`** (`:1159`): test write to slot 63 (`SetMaterialSlot`, read back, reset); cached.
- **`applyLook()`** (`:1344`): with slots (`UseSlots` and `slotsWork`): style material *i* → slot `22 + i`, `SetMaterialSlot(slot, base, "", colour)`; the voxel channel is `SolidMaterialIndex`, air = 0. Without: the base materials themselves, `SetMaterialColor` once per base material (first colour wins), original colours saved; channel `SolidMaterial`. Then the water look (`WaterColor`, `WaterTransparency`, `WaterReflectance`, `WaterWaveSize`, `WaterWaveSpeed`; originals saved). `lookVersion++`.
- **`applyTone()`** (`:1428`), at `arm` and `rebuild`:

  ```
  tint = nil; lookVersion++
  if style.water: Terrain.Water{Color, Transparency, Reflectance} = waterLook
  if no image: return
  tone = cornerTone(0, 0); armTone = tone                         -- the image at the arm point
  if style.usesTone:                                               -- Earth-like: water takes the sea colour around
      sea colours of the corners (ix, iz) ∈ {−12, −6, 0, 6, 12}² with wet > 0.5 and a sea colour
      seaLook = any found
      if found: WaterColor = mean·0.6; WaterTransparency = 0; WaterReflectance = 0.35   -- dark, opaque
      return
  base = style.materials[1].colour                                  -- first material
  factor(want, have) = clamp(1 + 0.65·(want/max(have, 0.03) − 1), 0.55, 1.8)
  tint = (factor(tone.r, base.R), factor(tone.g, base.G), factor(tone.b, base.B))
  every material colour c → (min(1, c.R·tint.r), min(1, c.G·tint.g), min(1, c.B·tint.b))
  ```

  **The tint is one constant per armed period**, taken from the image at the arm point (the ±12-corner sea search covers ±1.5 km).
- `setColour` (`:1410`), `resetSlots` (`:1484`), `restoreLook` (`:1495`), `fallBack` (`:1516`: slots failed while writing → clear, slots off, base materials).

#### 2.2.9 Arm, disarm, rebuild (`:1726-1864`)

| Function | Line | Does |
|---|---|---|
| `begin(body, profile, textureId)` | `:1264` | `finish()`; style = `chooseStyle` (pcall); offsets and salt from the seed (section 2.0); `ownsTerrain = (Terrain:CountCells() == 0)`; `applyLook()`; `loadImage(textureId)` if `UseImage`. **Builds nothing** |
| `canArm()` | `:1234` | style present, not armed, not failed, enabled, and not still waiting for the image (`imagePending` and less than `ImageWait` 4 s since the request) |
| `arm()` | `:1734` | section 2.2.4 |
| `disarm()` | `:1760` | clear all chunks; `armed = false`; far field fades out (`far:stop()`) |
| `finish()` | `:1770` | destroy the far field; clear; restore look; forget style and image |
| `rebuild(position)` | `:1789` | after a re-anchor with terrain: `bindFrame`, clear, `applyTone`, build the 3×3 chunks around the ship **immediately**, re-plan |
| `noteShift(T)` | `:1803` | shift `lastPosition`, rotate the velocity estimate, shift the eye, re-plan; `far:noteShift(T)` |
| `noteEye(eye)` | `:1821` | remember the camera; `far:place(eye)` every frame after the camera |
| `clearAll()` | `:1829` | `Terrain:Clear()` if the module owns the terrain, else fill every chunk with air; reset the spiral |
| `wantsReanchor(p)` | `:1860` | `flat > ReanchorDistance (16000)` or `deg(flat/(radius·s)) > TiltLimit (3.5°)` (on Phobos the tilt rule fires at 4.3 km, computed) |

#### 2.2.10 Queries: the same formula as the chunks (`:1866-1918`)

```
sample(x, z):                                         -- surface and water level at scene (x, z)
    yb = groundAt(x, 0, z).y
    (u, v) = project(x, yb, z)
    list = collect(style.layers, u − 0.5, v − 0.5, u + 0.5, v + 0.5, salt)
    (wet, cold, dry) = toneAt(x, z)
    h = max(style.relief(u, v, list, o, wet, cold, dry), style.floor or 0)
    datum = yb + lift
    return datum + h, style.water ? datum + water : nil
floorAt(x, z) = max(top, water)                       -- what one stands on: ground or water
isReadyAt(p) = armed and chunk(floor(p.x/128), floor(p.z/128)) exists
groundState(p) = not armed → "image"; no chunk → "missing"; chunk younger than FreshGrace 0.6 s → "fresh"; else "solid"
```

#### 2.2.11 Streaming and building (`:1920-2574`)

**Focus** (`focusFor`, `:1969`; `predictLanding` = the same): with the smoothed velocity `v` (section below) and `flat` its horizontal part:

```
if |flat| < 5: return position
level = levelAltitude(body)                           -- 900 m; small moons lower
ahead = (v.y < −20 and height > 1.2·level) ? |flat|/(−v.y)·(height − level) + 300    -- where the descent reaches level-off
                                           : |flat|·Lookahead (2.5 s)
return position + unit(flat)·min(ahead, MaxLookahead 9000)
```

**Path ahead** (`ensurePath`, `:1926`), every frame below `PathHeight` (1,500 m), before the budget: build the chunk under the ship at once; then walk the horizontal velocity direction in half-chunk steps up to `speed·PathSeconds (2.5 s)` (only when faster than 5 m/s), checking the point and two side points at ±0.75 chunk; build each missing chunk at once, at most `PathChunks` (2) per frame in total.

**Planning** (`plan(position, focus)`, `:2104`), every `PlanInterval` (0.2 s) or when the ship moved > 96 m or the focus > 256 m:

```
radius = LoadRadius 1800; outer = radius·clamp(RenderScale, 1, 8)           -- 7,200 with RenderScale 4
if far field active: radius = min(radius, far:voxelRadius()); outer = radius  -- voxels only to the seam + 256 m
near = (height < NearHeight 1500) ? NearRadius 640 : 0                       -- around ship and camera only when low
path: when near > 0 and |flat v| > 5: direction w and length speed·PathSeconds
eye = camera unless within 64 m of the ship
candidates: every missing chunk in the box around focus ± radius (and ship/eye ± near)
    rank = min(dist to focus, dist to ship/2, dist to eye/2, onPath ? t·0.2 : ∞)  -- on path: within one chunk of the path line
    keep if dist focus < radius or dist ship < near or dist eye < near or onPath
queue sorted so that the lowest rank is built first
ring (only without far field): a precomputed spiral of chunk offsets sorted by distance (spiralFor); a cursor walks
    it from the inside; when the centre moves, the cursor only steps back by the shift
    FarLead: the ring centre leads the focus by FarLead (0.25)·outer, scaled smoothly from 30 to 230 m/s
    dome cap: under an opaque day dome the ring stops just behind the dome (limit = min(Sky.DomeMax,
        sight·Safety·Sky.DomeSight)) if the dome is at most FarCapMaxDome (3400 m)·min(1, chunksPerSecond/120)
        ·Surface.MaxSpeed/currentLimit; ring reach = max(radius, min(outer, limit + max(eyeOffset, lead) + 1.5 chunks))
unload: chunks farther than outer + 384 from the ring centre AND radius + 384 from the focus AND
        0.6·outer + 384 from the ship AND near + 384 from the eye
```

**`update(dt, position, height)`** (`:2278`), every frame from `ShipSurface:step`:

```
v = (position − lastPosition)/dt; if |v| < 20000: velocity = lerp(velocity, v, min(1, 5dt))
frameTime = smoothed dt (≈ 1 s, single hitches count as at most 10 fps)
updateFar(dt, position, height)                       -- also while fading out above DisarmHeight
if not armed: return
re-plan if due (see above)
budget = (building or height > 1500) ? BuildBudget 0.011 s : FrameBudget 0.005 s
if far: if height ≤ 1500 and far:isShown(): budget = FrameBudget
        budget = max(budget/2, budget − far:spent())  -- far field pays from the same budget; voxels keep ≥ half
if height < PathHeight: ensurePath(position)
unload within 25 % of the budget; build from the queue within the budget
building = #queue > 24
if queue empty and a ring exists: buildFar within FarBudget 0.005 s
```

**`buildChunk(cx, cz)`** (`:2381`), one 128 m chunk:

```
(x0, z0) = (128cx, 128cz)
groundAt at the 4 corners; planar = all on one facet and n.y > 1e-3 → datum plane yb = yA + pb(x − x0) + pc(z − z0)
corner tones A, B, C, D (with image)
list = collect(layers, project(corners ± 8 m) bounding box)
for gi, gk in 0..33 (34 × 34 voxel-centre columns, incl. a 1-voxel border):
    x = x0 + (gi − 1)·4 + 2;  z likewise
    yb = planar ? plane : groundAt(x, 0, z).y
    (wet, cold, dry) = mix4 of A..D at ((x − x0)/128, (z − z0)/128)
    (u, v) = project(x, yb, z)
    h = max(relief(u, v, list, o, wet, cold, dry), floor);  top = yb + lift + h
    keep H = h, S = top, D = yb + lift (datum), U, V, tag, wet, cold, dry
    inner columns: lowest top, lowest h, highest max(top, datum + W)
paint, inner columns only:
    gx = (H[i+1,k] − H[i−1,k])·0.125;  gz = (H[i,k+1] − H[i,k−1])·0.125      -- Δh over 8 m
    (top, under) = paint(H, sqrt(gx² + gz²), tag, U, V, wet, cold, dry) → slot or base material
voxels:
    thickness = (lowest h < 0) ? min(Thickness, 3) : Thickness (10)          -- thin base under deep water
    y0 = floor((lowest top − 4·thickness)/4)·4;  y1 = (floor(highest/4) + 1)·4
    per voxel (bottom yb'): depth = S − yb'
        depth > 0: occupancy = depth ≥ 4 ? 1 : depth/4
                   material = depth ≤ 4·TopDepth (64 m) ? top : under
                   partial voxels also get liquid = clamp((D + W − yb')/4, 0, 1)
        depth ≤ 0 with water: liquid = clamp((D + W − yb')/4, 0, 1)
WriteVoxelChannels(region, 4, {SolidOccupancy, SolidMaterial(Index), LiquidOccupancy})
failure: with slots → fallBack (base materials); else failed = true and disarm
record chunk {cx, cz, y0, y1, builtAt = now}; stats (built, time, writeTime, voxels, maxTime)
```

Measured cost (comments `:2008`, `:855`): about 2.8 ms per chunk; 0.005 s per frame is two chunks; the full 7.2 km ring stands after about 80 s at rest, about 15 s under the dome cap at sight 2,800.

#### 2.2.12 Far-field glue (`:2576-2713`)

- **`farWanted()`** (`:2582`): not broken; the LocalPlayer attribute `FarField` (boolean) overrides; else `option("FarField")` and not `farHighSight()`.
- **`farHighSight()`** (`:2603`) ("Ultra" 2026-11-07): above `FieldMaxSight` (8,000) of sight, the old voxel ring shows more (real voxels to 7.2 km where the far field has only the squashed mesh beyond 1.3 km). Performance mode (`RenderScale ≤ 1` or `FrameBudget < 0.004`) always takes the far field. Back to the far field below 90 % of the limit; every change holds at least 10 s.
- **`updateFar(dt, position, height)`** (`:2628`): create the far field (`ShipFarTerrain.new(self)`) once terrain is armed, a style exists and the scene folder exists; on failure `farBroken = true`; destroy it when no longer wanted (re-plan, the ring comes back); `far:update(dt, position, height, armed)`.
- **`farPoint(x, z, minSize)`** (`:2667`): the same formula as `sample`, but features smaller than `minSize` are skipped (`collectFar`) and the image is read **exactly at the point** (`imageTone`) instead of between chunk corners. Returns `yb, h, tag, u, v, wet, cold, dry, r, g, b` (`r = −1` without image).
- **`lookColours()`** (`:2689`): the top colour per material name as the voxels show it: slot colours, or without slots the first colour per base material; both times the tint.

#### 2.2.13 Statistics (`getStats`, `:2715`)

Developer only (`ShipDebug terrain()`): armed, style key, mode, image size or "laedt"/"keins", arm tone, chunk count, queue, unload, spiral progress, `farStop`, `farReach`, `farLead`, `farCap`, render scale, far-field stats or reason, chunks built, average/write/max ms, average voxels, cleared, failed, owns, seaLook.

---

### 2.3 ShipFarTerrain: the far field (`ShipClient/ShipFarTerrain.lua`) · **replaced by real LOD geometry**

#### 2.3.1 Purpose (header `:1-71`)

The far field shows the same mountains, valleys, seas and colours as the voxels, as a mesh to the horizon.

- **Why:** Roblox draws only to a sight distance per graphics level (level 6 about 550 studs, level 13 about 2,200, level 18 over 9,000). By day the opaque haze dome (`ShipSky`) stands at 0.86 of that. The voxel ring of `RenderScale 4` (7,200 m) was invisible at most levels and still cost build time (2.8 ms per chunk, up to 5 ms per frame); behind it lay only the flat ground copy with the planet image.
- **Angle-preserving squash.** Every point P of the real ground moves along its sight line towards the eye E: `W = E + (P − E)·c(t)/t`, `t = |P − E|`. Direction and angular size stay exact; only depth shrinks. Up to the seam `d0`, `c(t) = t`; beyond it `c` grows logarithmically to `c1` at the horizon `T`:

  ```
  c(t) = d0 + (c1 − d0)·ln(t/d0)/ln(T/d0)        (ShipFarTerrain.squash, :170)
  ```

  Strictly increasing, so what is in front still hides what is behind. From `d0` on the mesh lies in front of the voxels. `c1` stays in front of the sky bodies, the cloud band and the day dome, and each piece stays under 2,048 studs (Roblox's mesh limit).
- **Inside (`t ≤ d0`):** where a chunk stands the mesh lies `FieldSink` (6 m) under the ground copy (invisible, voxels show everything); where none stands yet, `FieldBias` (4 m) below the real height, so it shows the relief instead of the flat image.
- **The mesh:** a polar grid around the anchor A (the eye at build time, a little ahead in flight): `FieldSegments` rays (384), `FieldInnerRings` linear rings up to `FieldInner·d0`, `FieldOuterRings` rings to `T`; denser where one looks down from above (`ringRadii`). One MeshPart per sector (`FieldSectors` 16), two layers (one shown, one being built and swapped when complete).
- **Every frame (`place`):** re-place the visible pieces with the largest image error first, within `FieldPlaceBudget`; between re-placements a piece moves by `(1 − k̄)` times the eye movement (k̄ = its mean squash); the edge columns of visible pieces are always exact (no gaps between pieces of different age).
- **In the background (`update`):** sample the new layout with `farPoint`, smooth where the mesh is coarse, derive normals, materials (`paint`), colours with haze, write to the hidden layer, within `FieldBuildBudget` per frame. Rebuild when the eye drifts `FieldDrift·max(d0, height)` from the anchor, seam or reach change by more than 8 %, the horizon grows beyond T, the haze colour or the material colours change.
- **On and off:** created under `ArmHeight` and faded in over `FieldFade` (1.2 s): the relief grows out of the planet image, the colours go from image to palette. Above `DisarmHeight` it fades out. Above the cloud deck (`ShipClouds` view "top") it is hidden (it would stand in front of the band).

#### 2.3.2 Math

- **`squash`** (above). **`evenShare(h)`** (`:178`) = `clamp((h − FieldEvenFrom 60)/FieldEvenSpan 240, 0, 1)`: how much "seen from above" the ring layout is.
- **`ringRadii(inner, outer, d0, T, h, segments)`** (`:195`):

  ```
  tin = FieldInner (0.55)·d0
  radii[i] = tin·i/inner, i = 1..inner                                  -- linear inside
  share = evenShare(h); hidden = clamp(FieldHiddenShare 0.25, 0.05, 1); hiddenEnd = FieldHiddenEnd 0.9·d0
  x0 = ln(tin), x1 = ln(T); geo = outer/(x1 − x0)·(1 + (FieldGeoShare 0.5 − 1)·share)
  even = share·segments/(2π)
  density(r) = max(geo, even·min(1, h/r)) · (r < hiddenEnd ? hidden : 1)  -- per unit ln r
  integrate density over 192 log steps, place the outer rings at equal integral
  radii[inner + outer] = T
  ```

  From above, the ground around `r ≈ h` needs rings as dense as the rays; the dense rings under the voxels (sunk) are thinned to a quarter.
- **`spot(data, g, eye, fade, chunks, span)`** (`:248`): displayed position of grid point `g`:

  ```
  y = yFlat + (yFull − yFlat)·fade                     -- relief fades in from the flat image ground
  t = |(x, y, z) − eye|
  if t ≤ d0:
      if a chunk stands at (x, z) (in the current scene frame): return (x, yFlat − sink, z), k 1, open false
      return (x, y − bias, z), k 1, open true
  k = (d0 + B·ln(t/d0))/t, B = (c1 − d0)/ln(T/d0)
  return eye + (P − eye)·k, k, c = t·k, open true
  ```

#### 2.3.3 Structure

- **Pieces** (`buildPiece`, `:295`; `makePiece`, `:499`): a sector has columns `0..m` (`m = segments/sectors` = 24; the last shared with the neighbour), rings `1..rings` (40), a centre vertex and three box vertices (a zero-area triangle at ±1020 fixes the mesh size at 2,040). Triangles: a fan from the centre to ring 1, then two per quad; winding (inner j, inner j+1, outer j) faces up when the angle grows from x to z. 13 probe vertices per piece (three columns × four rings + centre) for the frustum test. Each piece is built as a free `EditableMesh`, copied to a fixed-size one (only a handful of free ones fit in memory; fixed copies count only their real size), then turned into a MeshPart (material `Fabric`, white, transparency 1).
- **Grid data** `g = (ring − 1)·NS + J + 1` for ring points, `NS·rings + 1` for the centre (15,361 points with 384 × 40, computed); per-point fields (`FIELDS`, `:895`): `px, pz, yFlat, yFull, h, hs, tag, u, v, wet, cold, dry, ir, ig, ib, water, nx, ny, nz, ux, uy, uz, cr, cg, cb, br, bg, bb` and with `FieldPaintMid` `midR, midG, midB`.
- **States:** `off → making → ready`, or `failed` (any error: off, voxel ring as before, reason kept). `make()` (`:587`) builds 2 × 16 pieces, one per frame, in a task.

#### 2.3.4 Target (`target(height)`, `:659`)

```
sight = getSight()·Safety (0.9)
c1 = min(FieldReach 0.78·sight, SkyShare 0.94·sight·0.97, FieldDepthMax 1580)
c1 = min(c1, dome.a·SkyDomeShare 0.85·0.97, clouds.bandRadius·0.95)         -- if they exist
d0 = max(48, min(FieldSeam 0.62·sight, c1·(1 − FieldBand 0.18), LoadRadius − FieldVoxelMargin = 1544))
c1 = max(c1, 1.05·d0)
R  = scene radius; h = max(height, 2); climb = max(0, v.y)·2                  -- horizon for the height in 2 s
T  = (sqrt(2R(h + climb) + (h + climb)²) + sqrt(2R·FieldRelief + FieldRelief²))·FieldHorizon (1.1)   -- FieldRelief 300
T  = clamp(T, 4·d0, max(FieldDistanceMax 160000, 4·d0))
```

Computed: sight 550 → d0 307, c1 386; sight ≥ 2,200 → d0 1,228–1,296, c1 1,544–1,580 (voxels to 1.48–1.55 km). Earth scene, T: 29 km on the ground, 42 km at 100 m, 73 km at 900 m, 131 km at 4,500 m.

**`hazeColour()`** (`:701`), only with air:

```
k = (NightGlow 0.3 + 0.7·day)·FieldHazeShade (1)
c = skyKind == "day" ? FieldFogDay (RGB 46,105,167)                          -- Earth, measured in Play
  : skyKind == "haze" ? desaturate(air colour, FieldHazeGrey 0.35)          -- Mars fog
  : air colour
divide by the light tint: t_ch = max(0.3, 1 + FieldLightTint 0.5·(ambient_ch/luma(ambient) − 1))
return min(1, c_ch·k/t_ch)
```

**`hazeLength()`** (`:742`): `clamp(FieldHazeK 454 / Atmosphere.Density, 300, FieldHaze 45000)`; `Density = sky.density·FogDensityScale (0.4)·thin` (`ShipSky`). Computed at ground level by day: Earth 3.5 km, Mars 2.8 km (Play: 2.9 km), Venus 2.1 km, Titan 1.9 km; airless bodies: no haze.

**`wantsBuild(target, eye, height)`** (`:767`) → reason or nil: `start` (no data); `drift` (anchor drift > `max(FieldDriftMin 200, FieldDrift 0.3·max(d0, height))`); `seam` (|Δd0| > 8 %); `reach` (c1 outside −3 %…+8 %); `horizon` (T > 1.02·old or < 0.55·old); `height` (from above and the height changed by ×1.6); `haze` (colour moved > 0.04); `look` (`lookVersion` changed).

#### 2.3.5 The build job (`startJob` `:858`, `stepJob` `:940`)

Anchor = eye, led in flight by `min(0.7·speed, FieldLead 0.5·driftLimit)` along the horizontal velocity (when faster than 20 m/s). Stages, each resumable within `FieldBuildBudget` (0.0012 s; ×`FieldLowShare` 0.6 in performance mode):

| Stage | Line | Work |
|---|---|---|
| `alloc` | `:907` | reuse the arrays of the last retired layer, else `table.create` per field, spread over frames (28 × 15,361 in one frame cost 2–5 ms) |
| `sample` | `:977` | per point: polar position `A + t·(cos φ, sin φ)`, `minSize = max(Δt, t·Δφ)/2`; `farPoint`; `yFull = max(datum + h, datum + W)`, water flag |
| `smooth` | `:1261` | height filter `hs = h + ¼·wr·(h_in + h_out − 2h) + ¼·wa·(h_left + h_right − 2h)`; weights grow with point spacing from `FieldSmoothFrom` 40 to +`FieldSmoothSpan` 120 m; radial smoothing only beyond 1.5·d0 (the seam must match the voxels); recompute `yFull`/water |
| `derive` | `:1028` | normal `n = A × R` (A along the ring, R outward; flipped up); slope `sqrt(1 − up²)/max(up, 0.05)`; water points: radial normal and colour `WaterColor` moved `FieldWaterSky` (0.9) towards the haze colour; land: `paint(hs, slope·FieldSlope 1.6, tag, u, v, wet, cold, dry)` colour, with `FieldPaintMid`: also the colour at the midpoint to the next ring (averaged inputs, no tag), filtered ½ own + ¼ mid out + ¼ mid in; ×`FieldShade` 0.9; haze: `missing = te − c(te)` with `te = sqrt(t² + h0²)` (beyond d0), colour moves `1 − exp(−missing/hazeLength)` to the haze colour; without image the "image colour" is the palette colour (only the relief fades in) |
| `spread` | `:1176` | seen from above, cells are long and narrow: per ring a circular box filter over `k = round(clamp(share·(long − 1)/2, 0, 6))` rays, `long = Δt·h0/sqrt(h0² + t²)/max(t·Δφ, 1)` |
| `blur` | `:1310` | the same tent filter as `smooth` on the colours |
| `write` | `:1353` | `paintPiece` per piece of the hidden layer: normal `lerp(up, n, fade)`, colour `lerp(image, palette, fade)`; then the layer is "warming" |

#### 2.3.6 Placing (`place` `:1407`, `placeAll` `:1569`)

Every frame after the camera: mark pieces dirty when chunks changed (at most every 0.25 s). For each piece with a placement: its offset = `(E − E_placed)·(1 − k̄)`; visible if any of its 13 probes is inside the camera cone widened by `PROBE_MARGIN` (tan ≈ 0.25, about 14°); error = `|eye move|·sens·pxPerRad` (`sens` = max `|k − k̄|/c`), raised to 4× the threshold when the fade changed or sunk vertices rose by more than half the sink, 2× when dirty; ×0.1 when unseen. Refresh pieces above `FieldErrorPx` (0.6 px) by priority while the measured average piece cost fits into `FieldPlaceBudget` (0.0012 s); at most one unseen piece per frame; the warming layer gets one or two new pieces per frame; refresh only the edge columns of the remaining visible pieces; swap layers when every warming piece is placed and at most two visible ones are stale (or after 6 frames). `refreshPiece` (`:1431`) recomputes every vertex with `spot`, centres the piece at its bounding-box centre, clamps vertices to ±1020 if needed, computes `k̄` (mean k weighted by `1/c`) and `sens`, and repaints colours in steps of 0.2 fade. Finally `BulkMoveTo` all shown pieces to `frame·(origin + offset)` and publish the squash (section 2.4).

`noteShift(T)` (`:1799`): every layer's frame `= T·frame`, `shifted = true`; an ongoing build is dropped.

---

### 2.4 ShipFarSquash: objects in the squashed far field (`ShipClient/ShipFarSquash.lua`) · **dropped**

**Purpose.** Things standing on the ground or flying low (other pilots, NPC traffic, deposits, construction sites) beyond the seam `d0` would lie behind the squashed ground and be hidden. Whoever shows them moves them the same way: `W = E + (P − E)·k`, `k = c(t)/t`, drawn with scale k about W. Markers pointing at the true point still point at the squashed thing.

**State** (`:114`): `active`, `d0`, `c1`, `T`, `eye`, `at`, `fade`, `velocity` (of the eye, for prediction), `objects` (LocalPlayer attribute `FarSquash ≠ false`). `publish(active, d0, c1, T, eye, fade)` (`:155`) once per frame from `ShipFarTerrain:place`; stale after `STALE` 0.5 s; eye velocity from consecutive reports, reset on jumps > `JUMP` 400 m (re-anchor).

**API:** `isActive` (`:187`), `objectsActive` (`:192`), `squash(t)` (`:198`, same curve as the far field), `factor(t)` (`:207`), `eyeNow()` (`:216`, reported eye + velocity·min(age, `PREDICT_MAX` 0.1 s)), `place(point, eye)` (`:226`).

**Hulls** (`fitHull`, `:351`): k for the **farthest** point (`f = centre distance + hull radius`), in steps `STEP^n` (`STEP` 0.92, rounded down so it is never too deep); a step stays while it is valid and at most one step too small; when it must go deeper it goes one further; back to `k = 1` with the original sizes once `f < RETURN·d0` (0.97). Only on a step change are `Size`, mesh scale and offset written, all parts in one frame, at most one hull per frame (`RESCALE_GAP` 0.05 s without reports); in between only `BulkMoveTo` with offsets ×k. Exhaust plumes off below `EXHAUST_MIN` (0.9). `releaseHull` (`:403`) before pooling. A Studio-only debug function `ShipFarSquashDebug` (`:414-630`) spawns test ships (`spawn`, `status`, `ab`, `clear`).

**Users:** `ShipTraffic` (`:792`, `:828`, `:881`), `ShipDestruction` (`:1389`: keep the crash camera in front of the seam, soft limit 75 %→100 % of `d0 − 1.2·wreck radius`).

**Port:** nothing is squashed in a real-depth world. Drop the module and the destruction camera limit.

---

### 2.5 ShipPlanetTerrain: voxel spheres for small bodies (`ShipClient/ShipPlanetTerrain.lua`) · **legacy, dropped**

**Purpose** (header `:1-26`): procedural voxel terrain for landable bodies, a sphere of voxels around the body, only for small bodies (radius 200 ≈ 1.8 M voxels; radius 1000 would be 220 M), generated on the client for the one body being approached, cleared when away, spread over frames. **Switched off** since the planets carry images: `Terrain.SpherePlanets = false` (`ShipConfig.lua:1019`); once the voxels stood, the sphere was hidden, so from afar one saw nothing and close up a lump instead of the texture. `update` (`:304`) only clears leftovers.

**Algorithm, for completeness** (`begin`, `:135`):

```
radius = SurfaceRadius; palette = ShipPalettes.get(BodyClass); offset = Random(seed): 3 × NextNumber(−500, 500)
amplitude = Terrain.HeightAmplitude (0.105); seaRadius = palette.water ? radius·palette.sea : 0
region = cube ± (radius·(1 + amplitude) + 8), chunks of ChunkVoxels 16 × 4 m = 64 m
per chunk: skip if entirely outside max(maxRadius, seaRadius); fill Rock if entirely inside minRadius
per voxel (4 m, centre w):  d = |w − centre|;  dir = (w − centre)/d
    n = fbm(dir·NoiseScale (2.6) + offset, Octaves 4)           -- 3-D noise on the unit sphere
    surface = radius·(1 + amplitude·n)
    d ≤ surface: material = (surface − d > CrustDepth 10) ? palette.high
                          : ShipPalettes.materialAt(((surface/radius) − 1)/amplitude·0.5 + 0.5, palette)
    elif water and d ≤ seaRadius: Water
    else Air
lock the body's orbit while voxels stand (TerrainLocked); hide the sphere once done
```

Triggers: generate below `GenerateAtRadii` (2.5) radii altitude for `Landable` bodies, clear above `ClearAtRadii` (4.0); `setForced(part, wanted)` (`:72`) from the atmosphere entry kept it during a landing. **Port:** not ported. Its one idea worth keeping is the **3-D noise on the unit sphere** (`fbm(dir·scale + offset)`), which is exactly the seamless domain section 9.4 uses.

### 2.6 ShipSurfaceProfiles: how a body looks from below (`ShipCore/ShipSurfaceProfiles.lua`) · **port 1:1 as data**

**Purpose** (header `:1-31`): sky, cloud deck, light and entry glow per body. The header predates the terrain ("no terrain: the ground is the enlarged planet image"); the profile still makes bodies different from below: a blue day sky over Earth, butterscotch over Mars, orange haze on Titan, a black sky with stars on Luna. Sol bodies have hand-made profiles; generated bodies derive theirs from class and attributes (`HasAtmosphere`, `AtmosphereColour`). The **title** also picks the terrain style (section 2.2.7).

#### 2.6.1 Fields and defaults (`defaults()`, `:38`)

| Field | Default (airless) | Meaning and consumer |
|---|---|---|
| `title` | "ROCKY SURFACE" | ground type for the arrival line; picks the terrain style |
| `sky.air` | false | has an atmosphere (haze, dome, clouds possible; arrival line "ATMOSPHERE") |
| `sky.colour` | 190,214,244 | Roblox `Atmosphere.Color` on the ground (air colour); far-field haze base |
| `sky.decay` | 92,120,160 | `Atmosphere.Decay` (colour away from the sun) |
| `sky.density` | 0.3 | `Atmosphere.Density` (×`Sky.FogDensityScale` 0.4) |
| `sky.haze` | 1.6 | `Atmosphere.Haze` (×`Sky.FogHazeScale` 0.3) |
| `sky.glare` | 0.25 | `Atmosphere.Glare` |
| `sky.skybox` | "space" | sky image by day: "day" = blue day sky under the clouds (haze above them), "haze" = white haze sky tinted by the air, "space" = stars. Night is always stars |
| `sky.thickness` | 24000 | metres above ground where the air is gone (light ramp, density, dome) |
| `sky.brightness` | 2.6 | `Lighting.Brightness` by day on the ground |
| `sky.ambient` | 64,64,70 | ambient by day |
| `sky.nightAmbient` | 14,15,20 | ambient at night |
| `sky.stars` | 3000 | star count at night (Roblox `Sky.StarCount`) |
| `sky.zenith` | 70,128,214 | day colour of the sky dome (air only) |
| `sky.dusk` | 232,126,80 | its colour at dusk |
| `clouds` | nil | deck `{bottom, top (m above ground), cover 0..1, colour, dark (share of shadow underneath)}`, nil = none |
| `entry.heat` | 0 | entry glow strength 0..1 |
| `entry.glow` | 255,140,60 | entry glow colour |

`forBody` adds `name`, `class`, `seed`. `merge(base, over)` (`:63`) deep-merges tables; `scaled(c, k)` (`:74`) = `clamp(c·k)`.

#### 2.6.2 Sol (`SOL`, `:81-167`), used only when the body is in Sol (`SystemName` nil or "Sol")

| Body | Title | Overrides |
|---|---|---|
| Earth (`:83`) | TEMPERATE WORLD | air; colour 190,214,244; decay 92,120,160; density 0.32; haze 1.6; glare 0.3; skybox day; thickness 26000; brightness 2.6; ambient 108,124,146; night 16,20,30; stars 2600; zenith 70,128,214; dusk 236,128,84. Clouds 2100–2900, cover 0.56, white, dark 0.3. Entry heat 1, glow 255,136,56 |
| Mars (`:95`) | RED DESERT | air; colour 222,166,124; decay 124,70,44; density 0.4; haze 6; glare 0.12; skybox haze; thickness 22000; brightness 2.3; ambient 120,86,68; night 24,16,14; stars 2400; zenith 212,152,106 (butterscotch by day); dusk 120,150,196 (blue dusk as on the real Mars). Clouds 1900–2500, cover 0.2, 236,214,200, dark 0.2. Entry 0.6, 255,150,90 |
| Titan (`:108`) | DUNE SEAS UNDER ORANGE HAZE | air; colour 214,146,62; decay 120,70,22; density 0.6; haze 7.5; glare 0; skybox haze; thickness 36000; brightness 1.4; ambient 118,86,50; night 22,14,8; stars 600; zenith 198,130,58; dusk 146,76,34. Clouds 2300–3300, cover 0.72, 226,166,96, dark 0.35. Entry 0.75, 255,150,70 |
| Venus (`:157`) | SCORCHED BASALT PLAINS | air; colour 232,196,128; decay 140,96,44; density 0.55; haze 8; glare 0; skybox haze; thickness 40000; brightness 1.5; ambient 128,100,60; night 30,20,10; stars 0; zenith 214,170,96; dusk 170,96,40. Clouds 2600–4200, cover 0.92, 236,206,140, dark 0.45. Entry 1, 255,150,60 ("closed yellow deck, gloomy light, no stars, glowing entry") |
| Luna (`:120`) | AIRLESS REGOLITH | ambient 62,62,66 |
| Mercury (`:125`) | SCORCHED CRATER FIELDS | brightness 3.0; ambient 70,66,62 |
| Io (`:130`) | SULFUR VOLCANIC PLAINS | ambient 72,66,50 |
| Europa (`:135`) | CRACKED ICE SHELL | brightness 2.2; ambient 70,78,86 |
| Ganymede (`:140`) | GROOVED ICE TERRAIN | ambient 66,66,70 |
| Callisto (`:145`) | ANCIENT CRATER SHIELD | ambient 60,58,56 |
| Triton (`:150`) | FROZEN NITROGEN PLAINS | brightness 1.8; ambient 64,62,70 |

Phobos and Deimos have no entry: they fall through to the generated rule (Moon class, by seed): **BOULDER FIELDS** and **AIRLESS REGOLITH**. Jupiter, Saturn, Uranus and Neptune likewise get AMMONIA ICE SHELVES, DRIFTING ICE FLOES, FROZEN SLUSH SHELVES and METHANE ICE FLOES, but are not landable.

**Inconsistency to note:** Titan's profile has air, haze and a cloud deck, but the Titan body has no atmosphere in space (`03-world.md`: "Moons never have atmospheres in Sol, not even Titan"). From orbit Titan shows no air shell; on the ground it is orange haze (question Q12).

#### 2.6.3 Generated bodies

**Titles by class** (`TITLES`, `:172`), picked as `titles[(seed mod n) + 1]`:

| Class | Titles |
|---|---|
| Rocky Body | CRATERED HIGHLANDS, CANYON MESAS, WIND-SWEPT DUNES, JAGGED RIDGELANDS |
| Ice World | CRACKED ICE SHELL, GLACIER RANGES, PITTED ICE PLAINS |
| Volcanic Body | LAVA FIELDS, ASH PLAINS, BASALT FLATS |
| Ocean World | ARCHIPELAGO WORLD, ENDLESS SEAS, REEF SHALLOWS |
| Terrestrial World | TEMPERATE WORLD, GREEN LOWLANDS |
| Moon | AIRLESS REGOLITH, DUSTY BASINS, BOULDER FIELDS |
| Gas Giant | DRIFTING ICE FLOES, AMMONIA ICE SHELVES |
| Ice Giant | METHANE ICE FLOES, FROZEN SLUSH SHELVES |
| Greenhouse World | SCORCHED BASALT PLAINS |
| (other) | the Rocky Body list |

**Cloud decks by class** (`CLOUDS`, `:187`), only for bodies with air:

| Class | Bottom | Top | Cover | Colour | Dark |
|---|---:|---:|---:|---|---:|
| Ocean World | 2200 | 3000 | 0.62 | 255,255,255 | 0.3 |
| Terrestrial World | 2100 | 2900 | 0.52 | 255,255,255 | 0.3 |
| Ice World | 1900 | 2600 | 0.42 | 236,244,250 | 0.22 |
| Volcanic Body | 2000 | 2900 | 0.5 | 120,104,96 | 0.45 |
| Rocky Body (and any other class) | 1900 | 2500 | 0.25 | 232,222,210 | 0.2 |
| Gas Giant | 2600 | 4400 | 0.88 | 236,214,178 | 0.5 |
| Ice Giant | 2400 | 4000 | 0.8 | 214,238,244 | 0.4 |
| Greenhouse World | 2600 | 4200 | 0.92 | 236,206,140 | 0.45 |

**`airFor(body, class, profile)`** (`:200`), when `HasAtmosphere` (set by `ShipSystemBuilder.lua:443` for planets with an atmosphere; colour = variant haze, else data atmosphere colour, else body colour):

```
colour  = AtmosphereColour or RGB 150,180,230
blueish = colour.B > 1.15·colour.R                      -- blue sky: day sky under the clouds; else white haze sky
light   = lerp(colour, white, 0.45)
sky = { air = true, colour = light, decay = scaled(colour, 0.5), density = 0.34,
        haze = blueish ? 1.8 : 5.5, glare = 0.2, skybox = blueish ? "day" : "haze", thickness = 26000,
        brightness = 2.4, ambient = lerp(colour, black, 0.45), nightAmbient = scaled(colour, 0.1),
        stars = 2400, zenith = lerp(colour, black, 0.25), dusk = RGB 232,126,80 }
entry = { heat = 0.85, glow = RGB 255,140,64 }
clouds = copy(CLOUDS[class] or CLOUDS["Rocky Body"]); unless Volcanic: clouds.colour = lerp(clouds.colour, colour, 0.15)
```

**`forBody(body)`** (`:232`): `profile = defaults()`; `name = DisplayName or Name`, `class = BodyClass or "Rocky Body"`, `seed = Seed or 1`; find the root model's `SystemName`; if `SOL[name]` exists and the system is Sol (or unnamed): merge it; else `title = TITLES[class][(seed mod n) + 1]` and `airFor` when `HasAtmosphere`. Then set `name`, `class`, `seed`. **Always returns a complete profile.** Called at entry start (`ShipAtmosphere:startEntry`), at `ShipSurface:prepare/begin`, and at `chooseStyle`.

#### 2.6.4 How the profile is consumed (summary; the sky, cloud and glow renderers belong to the atmosphere spec)

**`ShipSky:update`** (`ShipClient/ShipSky.lua:286`), every frame in the scene, with `height` above ground and the sun direction:

```
day    = smoothstep(Sky.DayStart −0.10, Sky.DayFull 0.12, sun.y)          -- sine of the sun elevation
ground = 1 − smoothstep(0, thickness·SpaceRampEnd 0.55, height)          -- 0 = space light, 1 = body light
air    = (1 − clamp(height/thickness, 0, 1))^DensityCurve 1.6
groundAmbient    = lerp(nightAmbient, ambient, max(day, NightLight 0.3))
groundBrightness = lerp(NightBrightness 0.5, brightness, day)
Lighting.Brightness = lerp(spaceBrightness, groundBrightness, ground); Ambient and OutdoorAmbient likewise
with air:
    glow = NightGlow 0.3 + 0.7·day;  thin = air·(NightFogShare 0.35 + 0.65·day)
    Atmosphere.Density = lerp(density·FogDensityScale 0.4·thin, WhiteoutDensity 0.92, whiteout)
    Atmosphere.Haze    = lerp(haze·FogHazeScale 0.3·thin, WhiteoutHaze 6, whiteout)
    Atmosphere.Glare   = glare·air·day·(1 − whiteout)
    Atmosphere.Color   = lerp(scaled(colour, glow), scaled(cloudColour, glow), whiteout)
    Atmosphere.Decay   = scaled(lerp(decay, colour, DecayLift 0.35), glow)
    dome (Roblox workaround: its atmosphere does not colour the sky): opacity = smoothstep(DomeClearDay 0.02,
        DomeSolidDay 0.2, day); colour = scaled(lerp(zenith, dusk, (1 − day)·DomeDusk 0.45),
        (DomeNightShade 0.35 + 0.65·day)·fade), fade = 1 − smoothstep(0.3·top, 0.95·top, height),
        top = min(thickness, exitAltitude); a sun disc of 2.2° in RGB 255,244,222 in front of it
    sky image: "day" when skybox == "day", day ≥ DaySkyMin 0.35 and below the middle of the deck; else "haze";
        swapped only hidden in a cloud (whiteout ≥ 0.6) or after passing the deck; "space" (stars) when the
        dome is not opaque
stars = profile.sky.stars under the star sky, else 0
```

**`ShipClouds.new(folder, profile.clouds, ctx)`**: the deck between `bottom` and `top` with `cover` and `colour`, underside darkened by `dark`; `whiteout(eye, height)` feeds the formulas above. **`ShipAtmosphere`**: `entry.heat` scales the glow during HEAT (rising over `HeatRise` 1.2 s) and DESCENT (fading between 0.6 and 0.25 of the switch height); the deck slows the descent to `DeckSpeed` 600 within `DeckMargin` 300 of it, turbulence = `cover`; the arrival line is built from `title`, `SurfaceGravityG` and `sky.air`. **`ShipEntryFx.Colours`** (`ShipEntryFx.lua:163`) overrides the glow colour by name: Earth 255,118,92 (orange-pink), Mars 205,52,26 (deep red), Titan 255,186,64 (gold); otherwise `atmosphere:getHeatColour()` = `entry.glow` (default 255,140,60).

**Port:** `SurfaceProfiles.kt` reproduces `forBody` exactly (pure data). The Roblox-engine numbers (`Brightness`, `Atmosphere.Density/Haze/Glare`) are not physical units; the atmosphere port uses them as **relative tuning targets** (for example light levels relative to Earth's 2.6: Mercury ×1.15, Mars ×0.88, Europa ×0.85, Triton ×0.69, Venus ×0.58, Titan ×0.54), together with the far-field haze lengths of section 2.3.4.

---

### 2.7 ShipPalettes: class palettes (`ShipCore/ShipPalettes.lua`) · **port the colour ramps; the material half is legacy**

The full tables are in `03-world.md` §2.6 and are not repeated value by value. What matters here:

- **`colours(class)`** (`:205`): the colour ramp `{shore, low, high, peak, ice, iceLatitude, deep?, shallow?, banded?}` per class (default = the Rocky Body ramp). **It feeds every generated terrain colour set** (section 2.2.7) and the runtime planet textures. The header's point: one file for space look and ground, so they cannot disagree ("Mercury looked like Earth from space").
- **`get(class)`** (`:209`), **`materialAt(t, palette)`** (`:219`: `t < 0.30` shore, `< 0.62` low, `< 0.86` high, else peak), **`seaLevel(palette)`** (`:235`: `sea` fraction of R when `water`, else nil): used only by `ShipPlanetTerrain` (off). The material palettes (`PALETTES`, `:29`) are therefore unused in the running game.

---

### 2.8 Integration in ShipClient (`ShipClient.lua`)

**Construction** (`:278`, `:309-320`): `planetTerrain = ShipPlanetTerrain.new()`; `surface = ShipSurface.new(camera)`; `atmosphere:setSurface(surface)`; `surface:setSightSource(impostors:getSurfaceSight)` (unmeasured sight 12,000, not 500, which put a fog wall at about 430 m).

**Atmosphere callbacks** (`:485-523`): on entry start `surface:prepare(body)`; on the switch "DOWN": `docking:cancel()`, `planetTerrain:clear()`, `traffic:clear()`, `surface:begin(body, systemFolder, pose, velocity)`, `planetTexture:releaseAll()` (after begin: the scene copies keep their textures), markers hidden; on "UP": `toRealPose`, velocity `toRealVector(v)/s`, `surface:finish()`, markers shown, `stations:forget()`.

**Main loop order** (one frame, `ShipClient.lua:2318` ff.):

| Step | Line | What touches this area |
|---|---|---|
| 3 | `:2435` | `orbits:update` (the scene ignores orbit motion: it is body-relative) |
| 3b | `:2447` | `gravity:getState(position, velocity)` |
| — | `:2453` | **`surface:step(dt, pos, notOnFoot and speed > 1)`** → apply `T` to pose and velocity |
| — | `:2465-2482` | **in the scene, override the space state**: `altitude = surface:getAltitude`, `gravityG`, `gravityVector = surface:getGravity`, `speedLimit = surface:getSpeedLimit(alt)`, `timeToImpact = ∞`, `closingSpeed 0`, `heat 0`, `starAltitude ∞`, `bodyName`, `limitZone = "landing"`. (Without it the star, still tagged, overheated the ship on the ground.) |
| — | `:2534` | `thrust:setGravity(surface:getGravity(pos))` |
| 4, 4b | `:2541-2580` | thrust; guidance (`atmosphere:guide`, which calls the switch); after the switch `altitude = surface:getAltitude`; on release the throttle is set for `min(CruiseMaxSpeed, getSpeedLimit(alt))` |
| 5 | `:2598-2642` | **ground contact**: ray down 400 m against terrain + plate; analytic `floorHit` replaces the ray hit when the ray missed or lies more than 2 m below it; within `clearance = max(12, probe.ground + 0.3)`: `impact = max(−v.y, −0.5·v·n)`; crash "GROUND" if `impact > Surface.SafeLandingSpeed (38)` and `groundState ∈ {solid, image}`; else cancel the velocity into the ground and snap to `hit + n·clearance`; `shipLanded = gap < clearance + 0.6` |
| 5b | `:2706` | floating origin only when not in the scene and no voxel planet |
| 5c | `:2718` | stations hidden in the scene |
| — | `:2882` | gear down when in the scene and `altitude < Hull.GearDownAltitude (60)` |
| 6 | `:2889-2906` | camera; entry glow; **`surface:afterCamera(camera.CFrame)`** |
| 8 | `:2974-3013` | dust "air" mode in the scene; `planetTerrain:update` (no-op); star light only in space; **`surface:sweep()` in space**; planet textures only in space |
| — | `:3020-3030` | in the scene `impostors:updateSurface(dt, camera, surface.probeReachFn)` (sight probe) |
| — | `:3045-3060` | in the scene: if state SURFACE and `altitude > getExitAltitude()` → `atmosphere:requestAscent()`; `markTerrainReady()`; `atmosphere:update`; arrival toast `notices:toast(title, subtitle, false, 6)` |

**Other call sites:** respawn after a crash (`:1440`: `toRealPose`, `finish`, `resetToSpace`); crash data (`:1496-1590`: real position for the nearest station, `getUp`, `getGravity`, a `floor(p)` callback = `p − Y·getAltitude(p)` and a `colour(p)` callback = `terrain:colourAt`); destruction frame (`:2253`: `step` without re-anchor, `afterCamera`); pilots (`:2298`: surface pose for other players); pitch angle (`:1676`: `getUp`); disembark only when `shipLanded` and speed < 2 (`:2051`); status line "LANDED ON <BODY>" (`:1899`); debug `landing()` and `terrain()` (`:3542`, `:3569`).

---

## 3. Tunables

"Port" says what happens to each value: **keep** (same number, metres/seconds), **drop** (Roblox workaround), or what it becomes (section 9 numbers).

### 3.1 `ShipConfig.Surface` (`ShipConfig.lua:743`)

| Key | Value | Meaning | Port |
|---|---:|---|---|
| `SceneScale` | 470 | the body is this much larger in the landing scene | drop (S = 3000 world) |
| `SpawnHeight` | 420 | respawn height above ground (unused) | drop |
| `MaxSpeed` | 260 | speed limit floor near the ground (m/s) | keep (`02-flight.md` §9.5) |
| `MaxSpeedHigh` | 4000 | speed limit ceiling in the near band | keep |
| `SpeedPerAltitude` | 0.35 | limit = 0.35·height above ground | keep |
| `GravityScale` | 0.35 | gravity damped to 35 % on the ground (the lever refers to 260 m/s there; full gravity ate it, at 20 % throttle one stood still and on Earth sank) | keep (`02-flight.md` §9.4) |
| `SafeLandingSpeed` | 38 | crash above this impact speed on the ground (m/s) | keep |
| `ExitAltitude` | 8000 | back to space above this height above ground | drop as a switch; atmosphere state boundary (atmosphere spec) |
| `ExitShare` | 0.6 | small bodies: at most this share of the switch height | drop |
| `SwitchAltitude` | 0.028 | scene switch at this fraction of R above the surface | drop |
| `ReanchorDistance` | 2500 | re-anchor after this horizontal distance (m) | drop |
| `ReanchorShare` | 0.03 | … or this share of the scene radius | drop |
| `ReanchorInterval` | 0.25 | at most every 0.25 s | drop |
| `DefaultSight` | 500 | sight when no source | drop |
| `UnmeasuredSight` | 12000 | sight before the first measurement | drop |
| `ProbeMinElevation`, `ProbeSunClear`, `ProbeBodyClear`, `ProbeTerrainCheck`, `ProbeDomeShare`, `ProbeAgree` | 6°, 5°, 3°, 6000, 0.8, 0.7 | ground sight probe | drop |
| `Safety` | 0.9 | share of the sight actually used | drop |
| `GroundShare` | 0.82 | nearest ground point at this share of the sight | drop |
| `SkyShare` | 0.94 | sky copies at this share of the sight | drop |
| `SkyDomeShare` | 0.85 | and at most this share of the dome reach | drop |
| `PlateSize` | 2048 | invisible standing plate edge | drop |
| `PlateRefresh` | 400 | re-place the plate after this distance | drop |
| `ShadeFadeHigh` / `ShadeFadeLow` / `ShadeMinGap` | 4000 / 1500 / 0.0055 | night-side copy fades out near the ground | drop |
| `MinAngularSize` | 0.9° | family bodies need ≥ 0.18° (×0.2) for a sky copy | drop (real bodies are drawn) |
| `FarMinAngularSize` | 8° | other bodies need ≥ 8° | drop |

### 3.2 `ShipConfig.SurfaceTerrain` (`ShipConfig.lua:833`)

| Key | Value | Meaning | Port |
|---|---:|---|---|
| `Enabled` | true | terrain at all | keep (debug switch) |
| `ArmHeight` | 4500 | terrain appears below this height above the image | drop as a switch; see `NearColourDistance` (9.5) |
| `DisarmHeight` | 6500 | and disappears above this | drop |
| `LoadRadius` | 1800 | chunk radius around the focus | drop (quadtree) |
| `RenderScale` | 4 | ring = LoadRadius × this (max 8) | drop |
| `NearRadius` / `NearHeight` | 640 / 1500 | also around ship and camera when lower than 1,500 m | becomes the "path and ship first" priority below 1,500 m (9.6) |
| `UnloadMargin` | 384 | unload hysteresis | becomes the merge hysteresis (9.3) |
| `FrameBudget` | 0.005 | build seconds per frame | drop (worker threads) |
| `BuildBudget` | 0.011 | while much is missing or above 1,500 m | drop |
| `FarBudget` | 0.005 | ring budget | drop |
| `FarLead` | 0.25 | ring centre leads by this share in flight | becomes the focus lead of the scheduler (9.6) |
| `FarCapMaxDome` | 3400 | dome cap limit | drop |
| `Thickness` | 10 | voxels below the lowest point | drop |
| `TopDepth` | 16 | top material depth in voxels (64 m) | drop (surface shading only) |
| `Lift` | 4 | metres above the image (no z-fighting) | keep as a constant datum offset (9.4) |
| `ReanchorDistance` / `TiltLimit` | 16000 / 3.5° | re-anchor with terrain | drop |
| `Lookahead` / `MaxLookahead` | 2.5 s / 9000 | focus ahead in flight | keep (scheduler focus) |
| `PlanInterval` | 0.2 | re-plan every 0.2 s | keep as the quadtree re-plan cadence cap |
| `UseSlots` | true | material slots | drop |
| `PathSeconds` / `PathHeight` / `PathChunks` | 2.5 / 1500 / 2 | the path ahead is always built below 1,500 m | keep as priority rule (9.6) |
| `FreshGrace` | 0.6 | a new chunk only holds softly for 0.6 s | keep (ground state, 9.10) |
| `FarField` | true | far field on | drop (real LOD) |
| `FieldSegments` / `FieldSectors` / `FieldInnerRings` / `FieldOuterRings` | 384 / 16 / 4 / 36 | far-field mesh | drop |
| `FieldInner`, `FieldSeam`, `FieldReach`, `FieldBand`, `FieldDepthMax`, `FieldVoxelMargin`, `FieldSink`, `FieldBias` | 0.55, 0.62, 0.78, 0.18, 1580, 256, 6, 4 | seam, squash depth, sinking | drop |
| `FieldDrift` / `FieldDriftMin` / `FieldLead` | 0.3 / 200 / 0.5 | rebuild on drift | drop |
| `FieldRelief` | 300 | mountains this high count for the horizon | keep as the horizon-culling relief margin (9.3) |
| `FieldHorizon` / `FieldDistanceMax` | 1.1 / 160000 | horizon distance factor and cap | drop |
| `FieldBuildBudget` / `FieldPlaceBudget` / `FieldErrorPx` | 0.0012 / 0.0012 / 0.6 | far-field budgets | drop |
| `FieldHaze` | 45000 | haze e-fold distance cap (m) | keep as a tuning target (9.11) |
| `FieldHazeShade` | 1 | haze colour factor | keep (tuning) |
| `FieldShade` | 0.9 | material colours ×0.9 as the voxels look lit | keep (macro colour factor) |
| `FieldWaterSky` | 0.9 | distant water 90 % towards the haze colour | keep (far water, 9.9) |
| `FieldSlope` | 1.6 | slope ×1.6 on the coarse mesh for materials | keep, by LOD (9.5) |
| `FieldFade` | 1.2 | fade-in seconds | drop (geomorphing) |
| `FieldLowShare` | 0.6 | budget share in performance mode | drop |
| `FieldMaterial` | "Fabric" | matte mesh material | drop |
| `FieldSmoothFrom` / `FieldSmoothSpan` | 40 / 120 | smooth heights and colours where points are farther apart | keep, by vertex spacing (9.5) |
| `FieldEvenFrom` / `FieldEvenSpan` | 60 / 240 | denser rings seen from above | drop |
| `FieldHiddenShare` / `FieldHiddenEnd` / `FieldGeoShare` | 0.25 / 0.9 / 0.5 | ring density | drop |
| `FieldPaintMid` | true | material also between rings | keep in spirit (colour pre-filter, 9.5) |
| `FieldHazeK` | 454 | haze length = 454 / Atmosphere.Density | keep as a tuning target |
| `FieldHazeGrey` | 0.35 | haze sky: air colour 35 % desaturated | keep (tuning) |
| `FieldFogDay` | RGB 46,105,167 | fog colour under the blue day sky (Earth, measured in Play) | keep (tuning) |
| `FieldLightTint` | 0.5 | divide the haze colour by the ambient tint | keep (tuning) |
| `FieldMaxSight` | 8000 | above this sight the voxel ring instead | drop |

### 3.3 `ShipConfig.Terrain` (`ShipConfig.lua:1011`)

| Key | Value | Meaning | Port |
|---|---:|---|---|
| `SpherePlanets` | false | voxel spheres in space (legacy, off) | drop |
| `GenerateAtRadii` / `ClearAtRadii` | 2.5 / 4.0 | generate below / clear above (radii of altitude) | drop |
| `FrameBudget` | 0.004 | build seconds per frame | drop |
| `ChunkVoxels` | 16 | 64 m chunks | drop |
| `HeightAmplitude` | 0.105 | relief ±10.5 % of R | drop (would be ±668 km on Earth at S = 3000) |
| `SeaLevel` | 1.0 | (unused: the palette's `sea` is used) | drop |
| `NoiseScale` / `Octaves` | 2.6 / 4 | sphere-domain fbm | drop |
| `CrustDepth` | 10 | surface material depth | drop |
| `GroundProbe` | 400 | **still used:** ground ray length and `floorHit` reach in the scene | keep (collision query reach) |
| `GroundClearance` | 12 | **still used:** minimum hold height | keep (`02-flight.md`: `max(12, probe.ground + 0.3)`) |

### 3.4 Values from other sections this area reads

| Key | Value | Used for |
|---|---:|---|
| `Atmosphere.LevelAltitude` (`:972`) | 900 | level-off height; terrain focus during descent |
| `Atmosphere.DeckSpeed` / `DeckMargin` | 600 / 300 | descent speed through the deck (profile `clouds`) |
| `Atmosphere.HeatRise` / `HeatFadeStart` / `HeatFadeEnd` | 1.2 / 0.6 / 0.25 | entry glow (profile `entry.heat`) |
| `Physics.StudsPerG` (`:2436`) | 60 | gravity m/s² per g |
| `Hull.GearDownAltitude` (`:2619`) | 60 | gear down below 60 m above ground |
| `Gravity.Tag` (`:449`) | "CelestialBody" | body tag (scene uses it to find the star and sky bodies) |
| `Sky.DomeMax` / `DomeSight` (`:719`, `:714`) | 80000 / 0.95 | dome cap of the voxel ring |
| `Sky.*` (`:669-730`) | | the profile consumer formulas (section 2.6.4) |

### 3.5 Module constants

| Module | Constant | Value | Meaning |
|---|---|---:|---|
| ShipSurface | `MESH_RADIUS` | 85.628151 | `smooth_sphere.mesh` radius |
| | `FOLDER_NAME` / `SHADE_TAG` | "SurfaceScene" / "NightShade" | scene folder; night-shade tag |
| | plate thickness | 4 | `GroundPlate` height |
| | plate re-place (terrain) | 16 | m |
| | sky copy rim | 2.5 radii | probe exclusion around a body incl. rings |
| | `floorHit` step | ±2 m, normal `(−dx, 4, −dz)` | analytic normal |
| ShipSurfaceTerrain | `VOXEL`, `CHUNK`, `SPAN`, `GRID`, `FIRST_SLOT`, `NEAR_STEP`, `FAR_STEPS` | 4, 32, 128, 34, 23, 3, 4096 | section 2.2.2 |
| | velocity smoothing | rate 5/s, rejects > 20,000 m/s | |
| | re-plan triggers | 0.2 s, 96 m ship, 256 m focus | |
| | path side offset | 0.75 chunk | |
| | sea search at arm | corners ±12 step 6 | ±1.5 km |
| | sea water colour | mean sea colour ×0.6, transparency 0, reflectance 0.35 | |
| | tint factor | `clamp(1 + 0.65·(want/max(have, 0.03) − 1), 0.55, 1.8)` | |
| | thin base under deep water | `min(Thickness, 3)` | |
| | queue "building" | > 24 entries | |
| | farHighSight hysteresis | 90 %, 10 s | |
| ShipFarTerrain | `BOX` | 1020 | half piece size (MeshSize 2040 < 2048) |
| | `PROBE_MARGIN` | 0.25 | frustum widening (tan) |
| | `MAKE_TRIES` | 6 (0.25 s apart) | free-EditableMesh retries |
| | `CHUNK_KEY` | 100000 | chunk key like `keyOf` |
| | ring integration steps | 192 | `ringRadii` |
| | dirty re-sink | every 0.25 s | |
| | swap wait | ≤ 2 stale pieces or 6 frames | |
| ShipFarSquash | `STALE`, `STEP`, `RETURN`, `RESCALE_GAP`, `EXHAUST_MIN`, `PREDICT_MAX`, `JUMP`, `MAX_TESTS` | 0.5, 0.92, 0.97, 0.05, 0.9, 0.1, 400, 8 | section 2.4 |
| ShipPlanetTerrain | `RESOLUTION` | 4 | voxel size |

### 3.6 Derived numbers per Sol body (computed)

Roblox space radius R, scene radius ×470, the scene thresholds that depended on R, scene gravity, image texel size and horizon from 4,500 m; port radius at S = 3000. Texel: Roblox reads 1024² (256 px per cube face over 90°), the port uses the full 4096² (1024 px per face).

| Body | R (studs) | Scene R (m) | Switch alt. (scene m) | Level alt. | Exit alt. | Scene gravity (m/s², ×0.35) | Texel Roblox / port (m) | Horizon at 4.5 km Roblox / port (km) | Port R (km) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Mercury | 810 | 380,700 | 10,660 | 900 | 6,396 | 7.92 | 2,336 / 3,728 | 58.7 / 148.0 | 2,430 |
| Venus | 2,020 | 949,400 | 26,583 | 900 | 8,000 | 18.98 | 5,825 / 9,296 | 92.5 / 233.6 | 6,060 |
| Earth | 2,120 | 996,400 | 27,899 | 900 | 8,000 | 21.00 | 6,114 / 9,756 | 94.8 / 239.3 | 6,360 |
| Luna | 580 | 272,600 | 7,633 | 900 | 4,580 | 3.47 | 1,673 / 2,669 | 49.7 / 125.2 | 1,740 |
| Mars | 1,130 | 531,100 | 14,871 | 900 | 8,000 | 7.94 | 3,259 / 5,200 | 69.3 / 174.7 | 3,390 |
| Phobos | 150 | 70,500 | 1,974 | 790 | 1,184 | 0.013 | 433 / 690 | 25.6 / 63.8 | 450 |
| Deimos | 150 | 70,500 | 1,974 | 790 | 1,184 | 0.006 | 433 / 690 | 25.6 / 63.8 | 450 |
| Io | 610 | 286,700 | 8,028 | 900 | 4,817 | 3.84 | 1,759 / 2,807 | 51.0 / 128.4 | 1,830 |
| Europa | 520 | 244,400 | 6,843 | 900 | 4,106 | 2.81 | 1,500 / 2,393 | 47.1 / 118.6 | 1,560 |
| Ganymede | 880 | 413,600 | 11,581 | 900 | 6,948 | 3.07 | 2,538 / 4,050 | 61.2 / 154.2 | 2,640 |
| Callisto | 800 | 376,000 | 10,528 | 900 | 6,317 | 2.65 | 2,307 / 3,682 | 58.3 / 147.0 | 2,400 |
| Titan | 860 | 404,200 | 11,318 | 900 | 6,791 | 2.90 | 2,480 / 3,958 | 60.5 / 152.4 | 2,580 |
| Triton | 450 | 211,500 | 5,922 | 900 | 3,553 | 1.66 | 1,298 / 2,071 | 43.9 / 110.3 | 1,350 |

On Phobos and Deimos the whole scene lies below `ArmHeight` (switch at 1,974 m): terrain arms at once.

**Seed-derived terrain constants** (section 2.0, computed in double precision):

| Body | Seed | o.x | o.y | angle (rad) | o.ca | o.sa | salt |
|---|---:|---:|---:|---:|---:|---:|---:|
| Mercury | 1 | 631.615 | 533.871 | 2.8422 | −0.9555 | 0.2950 | 1.123 |
| Venus | 2 | −59.070 | −570.725 | 0.5776 | 0.8378 | 0.5460 | 2.123 |
| Earth | 3 | 827.928 | 310.175 | 2.7345 | −0.9183 | 0.3959 | 3.123 |
| Mars | 4 | 662.315 | −820.613 | 2.3100 | −0.6737 | 0.7390 | 4.123 |
| Luna | 94 | −755.157 | 138.002 | 1.3425 | 0.2264 | 0.9740 | 94.123 |
| Phobos | 125 | 745.097 | −555.511 | 0.9751 | 0.5611 | 0.8277 | 125.123 |
| Deimos | 126 | −28.976 | 587.860 | 1.3902 | 0.1796 | 0.9837 | 126.123 |
| Io | 156 | 911.935 | −293.594 | 2.3079 | −0.6721 | 0.7404 | 156.123 |
| Europa | 157 | −238.573 | 728.810 | 2.2383 | −0.6191 | 0.7853 | 157.123 |
| Ganymede | 158 | −986.524 | 192.288 | 2.8203 | −0.9488 | 0.3158 | 158.123 |
| Callisto | 159 | 165.586 | −703.469 | 2.5134 | −0.8091 | 0.5877 | 159.123 |
| Titan | 187 | 579.357 | 501.044 | 0.9286 | 0.5989 | 0.8008 | 187.123 |
| Triton | 249 | 13.231 | 678.195 | 1.8974 | −0.3208 | 0.9471 | 249.123 |

---

## 4. State machines, timings, thresholds, edge cases

### 4.1 Scene lifecycle (Roblox)

```
SPACE ──L in hover range──▶ ENTRY/HEAT (space) ──prepare(body)──▶ … height ≤ 0.028·R
   ──onSwitch("DOWN") = surface:begin──▶ ENTRY/DESCENT (scene) ──h ≤ 1.8·level──▶ LEVEL
   ──settled or 7 s──▶ SURFACE (pilot flies) ──altitude > exit──▶ EXIT/CLIMB (scene)
   ──h ≥ switch·470: onSwitch("UP") = toRealPose + finish──▶ EXIT/EMERGE (space) ──▶ SPACE
any time: crash → respawn → finish + resetToSpace;  every space frame: sweep()
```

### 4.2 Terrain (inside the scene)

```
begin: style, offsets, look, image request (async)
not armed ──canArm (image ready or 4 s waited) and height < 4500──▶ anchor jumps to the level-off point; arm
armed ──height > 6500──▶ disarm (chunks cleared; far field fades out)
armed ──flat > 16 km or tilt > 3.5°──▶ re-anchor + rebuild (3×3 chunks at once, rest streams)
finish: everything cleared, look restored
```

Armed per frame: velocity estimate → far field → re-plan (0.2 s / 96 m / 256 m) → path ahead below 1,500 m (≤ 2 chunks at once) → unload (≤ 25 % of budget) → build (5 ms, 11 ms while > 24 queued or above 1,500 m, minus the far field's spend, at least half) → ring.

### 4.3 Far field

```
off ──armed and wanted──▶ making (2 × 16 pieces, one per frame) ──▶ ready ──error──▶ failed (reason kept)
ready: fade 0→1 over 1.2 s after arm, 1→0 after disarm (data dropped at 0)
       job: none → start(reason) → alloc → sample → smooth → derive → spread → blur → write → warming layer
       place: refresh by error; swap when the warming layer is fully placed
hidden above the cloud deck (clouds.view == "top")
```

### 4.4 Ground state and the crash rule

| State | Condition | Crash allowed |
|---|---|---|
| `image` | terrain not armed (the facet plate carries) | yes, impact > 38 m/s |
| `missing` | armed, no chunk under the ship (plate at the formula height carries softly) | **no** |
| `fresh` | chunk younger than `FreshGrace` 0.6 s | **no** |
| `solid` | chunk present and older | yes |

Impact is `max(sink rate, −0.5·v·n)` (into a slope the speed into it counts half). Without a crash: velocity into the ground removed, position snapped to `ground + n·clearance`.

### 4.5 Timings

| What | Time |
|---|---|
| image wait before arming | ≤ 4 s (`ImageWait`) |
| re-plan | 0.2 s |
| fresh chunk grace | 0.6 s |
| far-field fade | 1.2 s |
| far-field dirty re-sink | 0.25 s |
| re-anchor minimum gap | 0.25 s |
| squash report stale | 0.5 s |
| arrival toast | 6 s |
| level-off timeout | 7 s (`Atmosphere.LevelTimeout`) |

### 4.6 Edge cases handled in the Lua

- **Image arrives late:** it counts only from the next arm (chunks built before it would not match later ones).
- **Painted clouds:** white over land → snow; white over sea (sea share ≥ 0.6–0.8 around, not green, not sandy) → sea.
- **Thin water disappearing in the distance:** open sea ≥ 30 m deep (`bed = W − 7 − 24·…`), water level at least two voxels above ground (W ≥ 8).
- **Ice sheets:** no open water where cold > 0.4 (`h ≥ W + 1 + 6·cold`), relief damped by `1 − 0.6·cold`.
- **Deserts:** no lakes where dry > 0.35 (`h ≥ W + 1.5`).
- **Features too small for the far mesh** are skipped (`collectFar`).
- **Material slots fail mid-write:** clear, fall back to base materials, re-tone.
- **Voxel write fails without slots:** terrain off for this landing (`failed`), the plate carries.
- **The star stays tagged while unparented:** the scene overrides the gravity state, otherwise the ship overheated on the ground.
- **Leftovers after an unclean exit:** `sweep()` every frame in space.
- **Small moons:** level-off below the switch height (`0.4·switch·470`), exit at 1,184 m, tilt-based re-anchor.
- **Velocity spikes after re-anchor:** the velocity estimate rejects jumps > 20,000 m/s; `noteShift` moves the reference.
- **Eye far from the ship (on foot, external camera):** chunks around the eye too.

---

## 5. On screen

### 5.1 The ground per style

Section 2.2.6 gives the shapes, 2.2.7 the colours. In words, as the player sees them in Roblox:

| Body / style | Look |
|---|---|
| Earth (temperate) | green rolling hills of a few tens of metres, dark-green lush patches, olive dry grass, sandy beaches up to about 2.5 m (±2 m) above the 8 m water line, mud flats in shallow lakes, grey rock and slate cliffs on steep slopes, snow above ~215 m (lower towards the ice caps), sea where the image shows sea, opaque and in the image's sea colour; steppe and dune fields (12 m dunes, 190 m spacing) where the image is desert |
| Mars (desert) | rust dust plains, sand patches and dune fields, three-step mesas up to about 105 m with layered faces, crater bowls with pale rims, dark boulders |
| Titan | dark sand and long dunes (about 20 m high, 300 m spacing), dark mud methane lakes at 8 m, pale ice hills |
| Luna, Mercury, Callisto (cratered) | grey regolith, darker dust in crater floors, bright crater rims, mare patches, scattered rocks; Mercury with 40 m scarps |
| Io, Venus (volcanic) | sulfur-yellow plains with orange and pale patches, basalt, almost flat lava lakes (Io) or basalt in the hollows (Venus), wide calderas |
| Europa (icecrack) | white ice with snow fields, brownish double ridges, chaos blocks |
| Ganymede (grooved) | bright banded ice with grooves 23 m apart and up to 14 m deep (sin² of a 46 m phase), dark dusty plains, craters |
| Triton (nitrogen) | pinkish frost and nitrogen ice with 26–46 m pits (cantaloupe), smooth frozen lakes, long dark streaks |
| Phobos, Deimos | Moon-class regolith; Phobos with many boulders up to 18 m |

**Water looks** (Roblox `Terrain.Water*`):

| Style | Colour | Transparency | Reflectance | Wave size / speed |
|---|---|---:|---:|---|
| temperate | 26,94,108 (near the sea: image sea colour ×0.6, opaque, 0.35) | 0.72 | 0.45 | 0.12 / 8 |
| titan | 40,30,20 | 0.18 | 0.85 | 0.04 / 4 |
| ocean | 20,104,126 (deep: 14,58,92) | 0.6 (0.4) | 0.4 | 0.16 / 9 (0.2 / 10) |
| floes | floe set "sea" | 0.3 | 0.6 | 0.08 / 5 |

**Far field:** the same colours ×0.9, distant water 90 % towards the haze colour, haze towards `FieldFogDay` (46,105,167) under Earth's day sky or the desaturated air colour under a haze sky.

### 5.2 Texts

| Text | Where | Source |
|---|---|---|
| title **`<BODY NAME>`** (upper case) + subtitle `<PROFILE TITLE>  ·  <g> G  ·  ATMOSPHERE` or `… NO ATMOSPHERE` (g with two decimals, e.g. `0.38 G`), 6 s | toast after landing | `ShipAtmosphere.lua:330`, `ShipClient.lua:3059` |
| `LANDED ON <BODY>` | status line (pilot list) | `ShipClient.lua:1899` |
| `SET THE SHIP DOWN FIRST.` | disembark refused | `ShipClient.lua:2055` |
| `CRASHED ON <BODY>` | destruction plate | `ShipClient.lua:1536` |

Separator `  ·  ` is two spaces, a middle dot, two spaces. The toast's layout is the notices module's (HUD spec); this area only supplies the strings. Developer-only: `ShipDebug terrain()` and `landing()` dumps.

### 5.3 Phone layout

Nothing of this area is drawn as UI: the terrain is the world. The arrival toast keeps its strings and duration; on a 20:9 phone it uses the toast slot of the HUD spec.

---

## 6. Dependencies

### 6.1 Inputs this area reads

| Data | From | Used for |
|---|---|---|
| `SurfaceRadius`, mesh scale | body (`03-world.md` §2.2.6) | scene radius, switch height |
| `Seed` | body | offsets, salt, generated title |
| `BodyClass`, `DisplayName` | body | style, profile, palette |
| `SurfaceGravityG` | body | scene gravity, arrival line |
| `IsStar`, `Landable`, `LandingBlocked` | body | no terrain on stars; giants not landable |
| `HasAtmosphere`, `AtmosphereColour` | body | generated profiles |
| body image (`TextureId`) | `ShipVisualAssets` via the body mesh | classification and tint |
| `ShipSphereMesh.locate/uvAt` | `03-world.md` §2.8 | facets, image UVs |
| sight | `ShipImpostors:getSurfaceSight` | far field and squash (Roblox only) |
| sun / light | `Lighting`, `ShipStarLight` | scene light (Roblox only) |

### 6.2 Who consumes it

| Consumer | Uses | Port |
|---|---|---|
| ShipClient flight loop | `step` shift, altitude, gravity, speed limit, ground ray + `floorHit`, `groundState`, gear | `PlanetSurface` queries (9.10) |
| ShipAtmosphere | `switchAltitude`, `levelAltitude`, `surfaceRadius`, `getAltitude`, `getExitAltitude`, `isReady`, profile (`title`, `sky.air`, `clouds`, `entry`) | same queries; profile |
| ShipSky, ShipClouds | profile, scene radius, `toBodyDir`, eye, height, sun direction, `domeReach` | atmosphere spec, with the profile from here |
| ShipCallouts | `getAltitude` along the predicted path (lookahead samples 0.5–5 s), slope from ±2 m samples | `altitudeAt(p)` must be cheap (9.10) |
| ShipDestruction | `floor(p)`, `colour(p)` = `colourAt`, squash state | `PlanetSurface.floorPoint`, `surfaceColour` |
| ShipMining | ray down against terrain or the scene; "TOO HIGH · DESCEND BELOW %d M" | `altitudeAt` |
| ShipTraffic, ShipPilots | `fitHull` squash; surface pose of other pilots | drop squash; singleplayer |
| ShipTraining | `getAltitude` | `altitudeAt` |
| ShipConstruction | `getSurfaceBody()` | `PlanetSurface.body` |
| HUD / attitude | scene altitude, up | `altitudeAt`, `upAt` |

### 6.3 What the port's terrain module owes other areas

| Area | Needs from the terrain |
|---|---|
| Flight (`02-flight.md` §9.5) | `hAgl` (height above ground or water) per frame, cheap; up; contact (point, normal, ground state) |
| World (`03-world.md`) | the body record (radius, seed, class, image cube map); in return: whether the body has terrain, and its max relief for horizon/shadow tests |
| Atmosphere and clouds | profile (sky, clouds, entry); terrain depth in the depth buffer; the aerial-perspective interface (9.11) |
| Callouts, warnings | `altitudeAt(p)` at arbitrary points (≤ ~20 samples per frame) |
| Destruction | floor point and surface colour at a point |
| Mining, construction, on-foot | ground point, normal, material/colour at a point |

---

## 7. Gap analysis against the current Kotlin code

| # | Roblox | Kotlin today (file : function) | Status | Work |
|---|---|---|---|---|
| 1 | Landing scene ×470, switch, re-anchor (`ShipSurface`) | none; one real-scale world (`StarSystem`, doubles, camera-relative rendering) | not needed | nothing (seamless by construction) |
| 2 | Ground under the ship = facet / terrain formula | `Game.collideBodies` (`Game.kt:1678`) collides with the **smooth sphere** (`Flight.collideSphere`, `Flight.kt:211`) | missing | `PlanetSurface.floorHit` + contact rule (9.10) |
| 3 | Touchdown and crash: impact `max(sink, −0.5 v·n)` > 38 m/s on visible ground → crash; else slide and snap to `clearance` | touchdown needs gear, radial > −8 m/s, level > 0.8, speed < 40 (`Game.kt:1695`); impacts bounce with haptics; no crash | differs | adopt the Roblox rule on terrain; keep the gear/level checks as the "landed" condition (flight spec) |
| 4 | Altitude above ground or water | `StarSystem.locate` (`StarSystem.kt:182`) → `altitude = distance − radius` (`:204`) | differs | `hAgl` from the terrain for the nearest body |
| 5 | Near-ground speed law, gravity 35 % | `StarSystem.speedLimit` (clearance × rate) | differs | `02-flight.md` §9.5 with `hAgl` |
| 6 | Terrain styles, relief, paint, colours | none. `BodyShaders.DETAIL` adds procedural **albedo** noise on the sphere in the fragment shader (value noise, PCG hash) | missing | sections 2.2.6–2.2.7, 9.4, 9.5 |
| 7 | Luau `math.noise` (improved Perlin) | GPU value noise (`BodyShaders.NOISE`, PCG) only | missing | `LuauNoise.kt` (Appendix A) |
| 8 | Image classification (wet/cold/dry, sea colour, tint) | `BodyRenderer.bake()` makes procedural equirect textures; no CPU image access | missing | cube-map images (`03-world.md` §2.8) + CPU tone map (9.4.4) |
| 9 | Terrain geometry and LOD (voxels + far field) | bodies are ray-cast sphere impostors without depth (`BodyRenderer.drawPlanet`, `BodyRenderer.kt:220`) | missing | cube-sphere quadtree (9.3), `TerrainRenderer` (9.8) |
| 10 | Atmosphere over the ground (Roblox fog, dome, haze baked into far colours) | single-scattering ray-march in the impostor shader (`BodyShaders.ATMOSPHERE`), sky alpha (`BodyRenderer.skyAlphaAtCamera`) | partial | aerial-perspective LUT for terrain (9.11); profile-driven tuning (atmosphere spec) |
| 11 | Depth for near and far ground | `SceneRenderer` two depth slices, objects wholly in one slice (`DepthSlices`, `SceneRenderer.kt:760`); camera near 0.5 m, far 40 km (`Camera.kt:19`) | partial | three slices, tiles drawn in every slice they overlap (9.8) |
| 12 | Background generation (Roblox: time-budgeted on the client) | everything on the game thread; no worker threads in `:core` | missing | worker pool + job queue + completion ring (9.6) |
| 13 | Surface profiles (sky, clouds, light, entry, title) | `StarSystem.Body.atmo` tint + `atmoThickness` (relative) only | missing | `SurfaceProfiles.kt` (2.6) |
| 14 | Palettes by class | none (`03-world.md` plans `Palettes.kt`) | missing | shared with the world area |
| 15 | Water (voxel water, far water towards the haze) | `BodyShaders` T_TERRAN water specular from the baked texture | missing | water pass (9.9) |
| 16 | Landable bodies | `Game.landable` (`Game.kt:969`): not STAR, not GAS | partial | from class (`03-world.md`) |
| 17 | Landing flow (L, guided entry, level-off, release) | `Game.doAction` "LAND" in LOW ALTITUDE (60 km), landing mode with gear and `LANDING_SPEED` 200, `holdLanded` on the sphere | partial | atmosphere spec; terrain supplies `hAgl`, normal, ground state |
| 18 | HUD altitude and pitch ladder over the ground | `Game.drawLandingHud` (`Game.kt:1117`) uses the sphere up and `here.altitude` | partial | feed `hAgl` and the terrain up |
| 19 | Arrival toast (title, g, atmosphere) | `showMessage("TOUCHDOWN  ·  " + name)` | differs | Roblox strings (5.2) |
| 20 | Quality levels | `Settings.quality` 0..2 (`Settings.kt:6`), used by `BodyRenderer` bake size and steps | partial | map to terrain LOD, workers, uploads (9.7) |
| 21 | Debug: terrain stats, A/B switches | `Game.debugInfo`, desktop harness | partial | terrain stats line + harness scenarios (10.5) |
| 22 | Sun direction and eclipses on the ground | `StarSystem.sunlight` (`StarSystem.kt:121`) | exists | reuse for terrain lighting |

---

## 8. Phone adaptation

### 8.1 Inputs

This area has no player inputs of its own. Related inputs and the Roblox debug switches:

| Roblox | What | Phone (touch) | Gamepad |
|---|---|---|---|
| L (key "Land", atmosphere area) | start the landing in hover range | the existing action button ("LAND", `Game.actionLabel`) | D-pad down (existing) |
| flying above the exit altitude | ascent | nothing: in the seamless world the ship simply climbs | — |
| LocalPlayer attribute `FarField` | far field on/off | developer setting only (none needed: no far field) | — |
| LocalPlayer attribute `FarSquash` | squash objects on/off | dropped | — |
| `ShipDebug terrain()`, `landing()`, `crash("dive")` | developer dumps and a test crash | `Game.debugCommand("terrain")`, `("crash dive")` and the desktop harness | — |
| `ShipFarSquashDebug` (Studio) | test ships beyond the seam | dropped | — |

Walking on the ground (on foot) uses the same `floorHit` (decks/on-foot spec).

### 8.2 Performance concerns

- **CPU generation is the main cost**: about 20–30 noise calls per vertex (style dependent), 1,225 samples per tile. Workers off the game thread; band-limited noise on coarse tiles (9.5); feature layers skipped when smaller than the vertex spacing.
- **Draw calls**: one per tile (300–600). One VAO per tile slot, uniforms per tile; no per-frame allocation. If the driver struggles, merge coarse tiles (levels ≤ L−6 of the camera) into shared VBO pages.
- **Overdraw**: the impostor's surface shading under the terrain is wasted; skip it for the focus body (9.8).
- **Fragment cost** near the ground at 2,670 × 1,200: keep the terrain shader to one detail-array fetch per layer used, per-vertex aerial perspective, no per-fragment ray-march.
- **Memory**: VBO pool of 900–1,600 tiles (26–47 MB), CPU classification maps about 12 MB for the current body only.
- **Thermal**: the descent is the peak (300–400 tiles/s for a few seconds); cap uploads per frame; on sustained throttling lower ρ (9.7).

### 8.3 Roblox-only workarounds that disappear

| Roblox | Why it existed | Port |
|---|---|---|
| Separate landing scene ×470, `toScene/toReal`, unparenting the system | no real-size planet one can stand on | one world at S = 3000 |
| Re-anchoring the scene (2.5 km / 16 km / 3.5° tilt) | 32-bit float coordinates, voxels cannot move | doubles per tile, camera-relative rendering |
| Ground copy of the image ×470 on flat facets, standing plate | ground had to be the image | terrain everywhere; analytic contact |
| Squashing every copy about the eye (`k = 0.82·sight/height`) | Roblox draws only to its quality distance | real depth (slices) |
| Sky copies of moons in front of the dome | the system was unparented | the bodies are always there |
| Far-field squash `c(t)`, `ShipFarSquash`, crash camera seam limit | same | real LOD geometry |
| Sight probe (`probeReach`, `getSurfaceSight`) | measure the quality distance | none |
| Voxels, material slots, `SetMaterialColor`, water properties | Roblox terrain API | mesh + shaders |
| `EditableImage` reading (1024² cap, async, token) | image pixels only via an API call | images are app assets (cube maps) |
| 2,048-stud mesh limit, fixed-size `EditableMesh` copies, MeshPart per sector | Roblox mesh limits | ordinary VBOs |
| Day dome and sun disc, sky kinds (`day`/`haze`/`space` skyboxes), `solveSun` via `ClockTime` | Roblox's atmosphere does not colour the sky; its sun is set by clock time | physically based sky (atmosphere spec) with the profile colours as targets |
| `sweep()` every space frame | leftovers of an unclean scene exit | nothing to leave behind |
| Image classification only at chunk corners (128 m) | cost | per vertex |
| A new tangent plane per landing | voxels lie in the scene | one planet-fixed terrain |

---

## 9. Seamless design for the phone

### 9.1 Goals and invariants

1. **One continuous world.** No scene switch, no teleport, no loading screen. The ground under the ship is the same at every altitude and on every visit: planet-fixed, deterministic from the body's seed, class, style and image.
2. **The Roblox look at both ends.** From orbit: the body image. On the ground: the Roblox styles (relief, materials, colours, water, image classification). In between: a smooth blend.
3. **One height function for picture and physics.** Contact, altitude, callouts and the finest rendered tiles use the same `heightAt(n)`.
4. **Engine rules.** Zero heap allocation per frame on the game thread (pools sized at startup), GLES 3.0, 60 fps on a Snapdragon 8 Elite, playable on mid-range phones.

### 9.2 Frames and precision

- **Body space.** Terrain lives in the body's frame: origin at the body centre, axes = `Body.rot` (planets do not spin in Roblox; if spin is added, `03-world.md` Q4, the frame rotates and nothing else changes). A surface point is a unit direction `n` (double) and a radius.
- **Tiles: doubles outside, floats inside.** Each tile stores its origin `C` (double ×3, body space: the reference-sphere point at the tile centre) and its vertices as `int16` offsets normalised to the tile's half-extent box (precision box/32767: 0.15 m on a level-0 face, 1.2 mm on a level-17 tile; always ≤ 1/2000 of the vertex spacing).
- **Per frame** (game thread, double): `t = bodyPos + bodyRot·C − cameraPos` → float uniform. The vertex shader works in camera-relative floats only: nearby tiles have `|t|` below a few km (float step < 0.5 mm).
- **Height function** inputs are doubles: `P = n·R` in metres (Earth: 6.36e6). Noise lattice coordinates are formed in double and reduced modulo 256 (the noise period) before the float noise kernel, so precision never degrades with planet size (9.4.1).
- **The ship** is double in heliocentric space (`03-world.md` B.5: 0.5 µm near Earth) and flies in the body frame near a body (`02-flight.md` §9.4).

### 9.3 The cube-sphere quadtree

**Faces.** Six faces with frames `(N, U, V)` in body space: `+X (−Z, +Y)`, `−X (+Z, +Y)`, `+Y (+X, −Z)`, `−Y (+X, +Z)`, `+Z (+X, +Y)`, `−Z (−X, +Y)` (any right-handed set works; fix one).

**Equi-angular mapping** (near-uniform tiles: area ratio about 1.4 instead of 5.2 for the plain cube):

```
face coords (a, b) ∈ [−1, 1]²:   x = tan(a·π/4), y = tan(b·π/4),  n = normalize(N + x·U + y·V)
inverse: face = argmax |n · N_f| with matching sign;  a = atan((n·U)/(n·N))·4/π,  b = atan((n·V)/(n·N))·4/π
```

**Nodes** `(face, L, i, j)`, `0 ≤ i, j < 2^L`, cover `a ∈ [−1 + 2i/2^L, −1 + 2(i+1)/2^L]`, `b` likewise. Edge length ≈ `(π/2)·R/2^L`; a tile is a 33 × 33 vertex grid (32 × 32 quads, 2,048 triangles) plus skirts, generated from 35 × 35 samples (one border ring for normals and slopes).

**Sizes on Earth** (R = 6,360 km, computed):

| Level | 0 | 5 | 8 | 10 | 12 | 14 | 15 | 16 | 17 | 18 |
|---|---|---|---|---|---|---|---|---|---|---|
| Tile edge | 9,990 km | 312 km | 39 km | 9.8 km | 2.4 km | 610 m | 305 m | 152 m | 76 m | 38 m |
| Vertex spacing | 312 km | 9.8 km | 1.2 km | 305 m | 76 m | 19 m | 9.5 m | 4.8 m | 2.4 m | 1.2 m |

**Finest level per body:** `Lmax = ceil(log2((π/2)·R/(32·s_min)))` with `s_min` = 2.5 m (HIGH) or 5 m (LOW) (the Roblox voxels are 4 m). Earth: 17 / 16. Luna: 15 / 14. Phobos: 13 / 12.

**Selection** (every frame, game thread, no allocation; node pool sized at startup, e.g. 12,000 nodes):

- `d` = distance from the camera to the tile's bounding box (its corners at min and max radius).
- **Split** when `d < ρ·edge` and the four children are resident; **merge** when `d > 1.15·ρ·edge` (hysteresis, the analogue of `UnloadMargin`). `ρ` by quality: HIGH 2.5, MEDIUM 2.25, LOW 2.0; a controller lowers ρ by up to 20 % when the GPU frame time exceeds its budget and restores it slowly.
- **Residency by distance in all directions**: splitting ignores the frustum, so turning the camera never needs new tiles. The frustum is used only for drawing.
- **Horizon culling** for splitting and drawing: a tile is beyond the horizon when `angle(n_cam, n_tile) − angularRadius(tile) > acos(R/(R + h_cam)) + acos(R/(R + reliefMax))`, `reliefMax` = the style's maximum height plus `Lift` (Roblox's `FieldRelief` 300 is the analogue; use a computed per-style upper bound, about 420 m for temperate, 380 m for ridges, under 300 m for the others).
- Children are requested only for resident parents (the tree always has a complete cover; a parent stays drawn until all four children are uploaded).

**Tile counts** (computed by simulating this rule on an Earth-size sphere, camera pitched 15° down, 94° × 46° field of view, relief margin 400 m):

| Camera altitude | HIGH: resident / drawn (ρ 2.5, L17) | LOW: resident / drawn (ρ 2.0, L16) |
|---:|---:|---:|
| 2 m | 1,220 / 546 | 704 / 286 |
| 100 m | 1,272 / 582 | 740 / 310 |
| 1 km | 856 / 272 | 688 / 230 |
| 5 km | 628 / 176 | 472 / 142 |
| 20 km | 492 / 136 | 324 / 110 |
| 100 km | 348 / 104 | 252 / 80 |
| 1,000 km | 196 / 46 | 120 / 32 |

With ρ = 3.0 the drawn count at 100 m rises to 696 (1.4 M triangles), with 3.5 to 790 (1.6 M): the budget below is set by ρ.

### 9.4 The height function on the sphere

**Definition** (the port's `SurfaceField.heightAt`):

```
P = n·R                                                       -- body space, metres, on the reference sphere
tone = classify(n)                                            -- wet, cold, dry, sea colour, image colour (9.4.4)
list = features(P)                                            -- craters, boulders, pits near P (9.4.3)
h = max(style.relief(D(P), list, o, tone.wet, tone.cold, tone.dry), style.floor or 0)
rGround = R + Lift + h                                        -- Lift = 4 m (kept so heights match Roblox values)
rWater  = R + Lift + W                                        -- styles with water
rFloor  = max(rGround, rWater)                                -- "what one stands on" (floorAt)
```

`D(P)` is the **domain** the style functions see. The style code is transcribed once from section 2.2.6 against a small interface, with two implementations:

| Domain call (Roblox form) | Plane mode (Roblox-exact, for golden tests) | Sphere mode (the game) |
|---|---|---|
| `noise(u/λ + a, v/λ + b, c)` | `noise(u/λ + a, v/λ + b, c)` | `noise(P.x/λ + a, P.y/λ + b, P.z/λ + c)` |
| `fbm(u/λ + a, v/λ + b, c, k)`, `ridged(…)` | as Roblox | the same with the 3-D point; all three coordinates × octave frequency |
| `(u·α + v·β)/λ` (dune, groove phases) | as Roblox | `(α·X + β·Y)/λ'` in the Mercator chart (9.4.2) |
| nitrogen streak noise | as Roblox | chart coordinates with a seam crossfade (9.4.2) |
| `features(list, u, v)` | 2-D cells (section 2.2.5) | 3-D shell lattice (9.4.3) |

#### 9.4.1 Isotropic terms: a 3-D noise sampled on the sphere

Roblox evaluates `noise(x, y, c)`, a planar slice of a 3-D Perlin field at constant `z = c`. The sphere mode evaluates the same 3-D field on the sphere of radius `R/λ` (in lattice units), with the same offsets and salts added. Because `R/λ` is between 1,200 (Earth, λ = 5,200 m) and 210,000 (λ = 30 m), the surface is locally a plane through the lattice: the value statistics are those of Roblox's slice (to be checked by `StyleStatisticsTest`, section 10.4). Different salts and seed offsets decorrelate exactly as in Roblox.

**Precision rule:** compute `q = (P/λ + offset)·frequency` in double, reduce each coordinate modulo 256 (exact: the noise has period 256 per axis), then call the float kernel. Without the reduction, `P.x/30 ≈ 2·10⁵` would leave the float kernel a lattice fraction resolution of 1/64 (0.5 m steps).

**Periodicity:** the lattice repeats every `256·λ` along each body axis (9.2 km for λ = 36 m, 1,331 km for λ = 5,200 m). This exists in Roblox too and is not visible on the ground; from high orbit the relief is sub-pixel (a 300 m hill on a 6,360 km planet). A 32-bit hashed lattice could remove it but would change the noise; keep Roblox's.

#### 9.4.2 Directional terms: the Mercator chart

Roblox's tangent frame is `e1 = unit(Y × d0)` (along the parallel), `e2 = unit(d0 × e1)` (north), with `Y` the body's pole (section 2.2.4). Every directional term is written against that frame: the dune phases `(0.83u + 0.56v)/190` (temperate), `/210` (desert), `(0.97u + 0.24v)/300` (titan), the grooves `(o.ca·u + o.sa·v)/46` (grooved) and the stretched streak noise (nitrogen). On a sphere a linear phase with a constant bearing cannot be globally exact; the isometric (Mercator) chart keeps the **bearing exact everywhere** and is seamless with one integer condition:

```
lonE = atan2(−n.z, n.x)              -- increases along e1 = unit(Y × n)
lat  = asin(clamp(n.y, −0.9962, 0.9962))   -- ±85°
X = R·lonE,   Y = R·asinh(tan lat)   -- isometric latitude; one chart metre = cos(lat) metres on the ground
phase(α, β, λ) = (α·X + β·Y)/λ',  λ' = α·2πR / round(α·2πR/λ)  -- the phase wraps by an integer at lonE = ±π
weight = smooth(0.25, 0.45, cos lat) -- directional amplitude fades out between 63° and 75° latitude
```

The pattern keeps Roblox's direction at every point; its spacing is `λ·cos(lat)`: equal to Roblox at the equator, 0.87 λ at 30°, 0.5 λ at 60°. Multiply the dune or groove **amplitude** by `weight` (Roblox switched its helper axis above 64° latitude; the poles are ice or plains on every Sol body that has dunes or grooves). The isotropic warps inside the phases (`noise(u/650, v/650, 2.8)·0.9` etc.) use 9.4.1. On Earth, `λ'` for the 190 m dunes differs from 190 m by a relative 3·10⁻⁶ (computed).

The **nitrogen streak** is an anisotropic noise, not a phase: evaluate `noise((0.9X + 0.43Y)/900 + o.x, (0.9Y − 0.43X)/90 + o.y, 4.4)` in the chart and crossfade it with the same expression at `X ∓ 2πR` within 5° of the seam `lonE = ±π` (it only selects a paint colour, so a crossfade is invisible).

#### 9.4.3 Feature layers: a 3-D shell lattice

Roblox rolls one candidate per 2-D cell of edge `size` with probability `chance`: a density of `chance/size²` per square metre. The sphere mode rolls per **3-D** cell and keeps only candidates inside a shell of thickness `size` around the reference sphere, which gives exactly the same areal density:

```
for each layer k (key = salt + 7.31·k), each 3-D cell (i, j, l) of edge s = size near the query box (± reach):
    if hash4(i, j, l, key) < chance:
        C = ((i + 0.15 + 0.7·hash4(…, key + 2.9))·s, (j + 0.15 + 0.7·hash4(…, key + 4.3))·s,
             (l + 0.15 + 0.7·hash4(…, key + 5.7))·s)
        if | |C| − R | < s/2:                                   -- shell: density chance/s² per m², as Roblox
            r = rmin + (rmax − rmin)·hash4(…, key + 1.7)^(skew or 2)
            centre = C·R/|C|                                    -- on the reference sphere
features(P): d = |P − centre|/r (chord; equals the surface distance to 1e−9 for r ≪ R); profiles as section 2.2.5
```

`hash4` is a 32-bit integer hash (for example PCG on `i, j, l` and the key's bits) mapped to [0, 1): Roblox's `sin` hash is kept for plane mode only (it loses quality for large arguments and depends on the platform's `sin` in the last bits). Per tile, collect the candidates for the tile's bounding box plus the layer reach once, like `collect` per chunk. **LOD rule** (from `collectFar`): skip layers and features whose `r·spread < 1.5·spacing/2` (they would fall between vertices). At the finest levels (≤ 2.5 m spacing) nothing is skipped.

#### 9.4.4 The image classification on the sphere

The body image is a cube map after conversion (`03-world.md` §2.8: `uvAt(dir)` and bilinear sampling per cube-map texel). The Roblox tone rules port unchanged with `uvAt` replaced by a cube-map lookup:

- **CPU tone source** for the current body: the image as RGB at 512² per face (4.7 MB) and a per-texel table `{seaShare (u8), seaRGB (3 × u8)}` (6.3 MB), baked offline by the desktop tool from the same rules (`seaAround`), or built by a worker at body load (about 39 M samples).
- **Neighbourhood in angle:** Roblox's `NEAR_STEP` of 3 texels on its 256-px faces is 1.05° and the 5 × 5 window spans ±2.1°. At 512 px per face use a step of 6 texels. The neighbourhood follows the planet (±233 km on the port's Earth).
- **Per sample:** bilinear RGB → `toneOf` (exact Roblox semantics: classify the interpolated colour); bilinear `seaShare` of the four texels; the cloud-over-sea rule (`k = smooth(0.6, 0.8, share)·clear`); `tone.sea` from the texel with the largest weight. Roblox sampled only every 128 m; the port samples per vertex.
- **Missing image** (body without PNG): wet = cold = dry = 0, no tint, no sea colour (Roblox's behaviour without an image).

#### 9.4.5 What keeps its metre size and what follows the planet

| Keeps its metre size (absolute) | Follows the planet (angle-based) | Changes in the port |
|---|---|---|
| every relief term: wavelengths 30 m … 5.2 km, amplitudes, `dune`, `terrace` | sea / land / ice / desert from the image (≈ 10–20 km texels) | datum: the smooth sphere `R` (Roblox: flat facets up to 2.4 km below it) |
| craters, boulders, pits (r 2.5–520 m) and their density per km² | the cloud-over-sea neighbourhood (±2.1°) | terrain is planet-fixed for good (Roblox: a new plane per landing) |
| water level W 8–12 m, sea bed −24 m, `Lift` 4 m, snowline 215 m (− 260·cold) | the colour tint field (non-tone styles) and the sea colour field | the tint and sea colour vary per vertex (Roblox: one value per landing) |
| material rules: slopes, heights, patch noise | far colour = the image | relief exists at every altitude (Roblox: only below 4.5 km) |
| near-ground rules: speed law, gravity factor, gear, crash threshold, cloud deck heights, haze lengths | curvature, horizon (×2.5 at the same height on Earth) | directional patterns via the Mercator chart (9.4.2) |

Consequences: the port's Earth has 41 times the area of the Roblox scene's Earth, so 41 times as many hills and craters; mountains stay at most about 400 m, which is invisible from orbit (a macro relief is question Q1).

### 9.5 Colours and materials

**Per vertex, at generation** (worker):

1. **Paint**: `(top, under) = paint(h, slope·slopeBoost, tag, D, wet, cold, dry)`; `slope = |∇h|` from the 35 × 35 grid (central differences, rise over run) for spacing ≤ 8 m; for coarser tiles `slopeBoost = 1 + 0.6·smooth(8, 64, spacing)` (Roblox's `FieldSlope` 1.6 on the coarse mesh).
2. **Near colour** = the style colour of `top` (section 2.2.7) × **tint** (non-tone styles): `tint_ch = clamp(1 + 0.65·(img_ch/base_ch − 1), 0.55, 1.8)` with `img` = the image colour at `n` (tone source, bilinear) and `base` = the style's first material colour. This is Roblox's `applyTone` evaluated per vertex instead of once at the arm point.
3. **Pre-filter for coarse tiles** (Roblox `FieldPaintMid`, `smoothStep`, `blurStep`): for spacing > 8 m add the paint of the four edge midpoints (inputs averaged, no tag) as `½·own + ⅛·Σ midpoints`; for spacing > 40 m tent-filter colours and heights within the tile with weight `clamp((spacing − 40)/120, 0, 1)`. Heights are filtered **for drawing only**; physics never sees a coarse tile.
4. **Band-limited noise for coarse tiles:** fade out fbm/ridged octaves and noise terms whose effective wavelength is below `2·spacing` (`weight = smooth(2·spacing, 4·spacing, λ_eff)`). Exact at the finest level (the smallest Roblox wavelength is 30 m).
5. **Material class weights** for the detail textures (RGBA8): every Roblox material maps to one of four detail classes: *soil* (Grass, LeafyGrass, Ground, Mud), *rock* (Rock, Slate, Basalt, CrackedLava, Concrete, Limestone, Sandstone), *granular* (Sand, Salt), *ice* (Snow, Ice, Glacier). The detail texture modulates luminance (mean 1) and the normal; the colour stays the style colour.
6. **Water vertices**: `waterDelta = rWater − rGround` (signed, quantised to 1/4 m, clamped to ±31.75 m) and the water colour (9.9).

**Per fragment:**

```
near   = vertexColour · detail(classWeights, uvDetail)
far    = imageCube(n)                                     -- the body image, the same texture the impostor uses
albedo = mix(near, far, smooth(ImageNear 60 km, ImageFar 250 km, distance))
```

From orbit everything is farther than 250 km: the planet looks exactly like its image. Below about 20 km of altitude the ground under the ship is all palette, as in Roblox below 4.5 km. Because the palettes were made to match the images (`ShipPalettes` header) and the tint pulls generated colours to the image, the blend band is a subtle change of texture, not of colour.

**Detail UVs:** metric chart coordinates per vertex relative to the tile origin snapped to a multiple of the detail period (for example 64 m), so neighbouring tiles agree on shared vertices; two scales (8 m and 64 m) blended by distance.

### 9.6 Tile generation in the background

**Pipeline:**

```
game thread (every frame)                           workers (2–3 threads)                 GL thread (= game thread)
quadtree update → wanted children ──▶ job heap ──▶ take best job → sample 35×35 → normals,
(priority, node id, generation)                      slope, paint, colours → pack 24 B
                                                     vertices into a staging buffer
                                                     → completion ring ──────────▶ upload ≤ U per frame
                                                                                           (glBufferSubData into the
                                                                                            slot VBO) → node resident
```

- **Pools sized at startup:** node pool, tile slots (VBO + VAO each), a staging pool for jobs in flight (32 direct `ByteBuffer`s of 29 KB on HIGH, 16 on LOW), job heap (fixed capacity, arrays of `Int`/`Float`), per-worker scratch (35 × 35 arrays, feature lists, noise state). Workers reuse everything; jobs are indices, not objects. A freed slot is reused only after 3 frames (the GPU may still read it).
- **Priority** (lower is sooner; re-planned every `PlanInterval` 0.2 s or when the camera moved more than 1/8 of the finest edge):
  - base: `d / edge` (distance in tile sizes);
  - ×0.25 for tiles under the predicted path `p + v·t`, `t ∈ [0, PathSeconds 2.5 s]`, ±0.75 tile sideways, when `hAgl < PathHeight 1500 m` (Roblox `ensurePath`);
  - ×0.5 around the **focus** (`focusFor`: during descent the point where the descent reaches the level-off height, else 2.5 s ahead, at most 9 km);
  - ×0.5 within `NearRadius` 640 m of ship and camera below `NearHeight` 1,500 m;
  - coarse before fine is implicit (children need resident parents).
- **Workers:** HIGH 3, MEDIUM 2, LOW 2 threads, created once, below the game thread's priority, outside the ADPF performance-hint session (`PerfHint.kt` binds the render thread only).
- **Cost** (estimate; measure on device): about 20–30 Perlin calls per sample at roughly 25–40 ns each plus classification and packing ≈ 1 µs per sample on an Oryon core, ≈ 1.2 ms per tile; about 2.5 ms on a Cortex-A78.
- **Generation steps per tile:** (1) feature candidates for the box; (2) 35 × 35 samples: direction, classification, relief, floor clamp, ground and water radius; (3) parent-interpolated heights for geomorphing: the even vertices coincide with the parent's vertices, odd ones are the average of their even neighbours, `morphDelta = h − h_parent`; (4) normals from the grid; (5) slope, paint, colours, midpoints; (6) coarse-tile filters; (7) pack; (8) bounds (min/max radius), geometric error (`max |morphDelta|`), relief maximum.
- **Upload budget:** HIGH 8 tiles per frame (≈ 230 KB), LOW 4.

### 9.7 Budgets per device

| | Snapdragon 8 Elite (HIGH) | Mid-range (LOW; e.g. Adreno 6xx/7xx, Mali-G7x, 4 big cores) |
|---|---|---|
| finest vertex spacing (Earth) | 2.4 m (L17) | 4.8 m (L16) |
| ρ (split distance / tile edge) | 2.5 (controller: −20 %) | 2.0 |
| tiles resident at 100 m / 20 km / 1,000 km (computed) | 1,272 / 492 / 196 | 740 / 324 / 120 |
| tiles drawn at 100 m (computed) | 582 | 310 |
| terrain triangles drawn | ≤ 1.2 M | ≤ 0.65 M |
| draw calls (terrain + water) | ≤ 700 | ≤ 380 |
| generation demand (computed): level flight 100 m @ 260 m/s; 1.2 km @ 420 m/s; guided descent 28 → 1.6 km in 6 s | 174 /s; 46 /s; 1,160 tiles, peak 368 /s | 61 /s; 48 /s; 876 tiles, peak 272 /s |
| generation capacity (estimate) | 3 workers ≈ 2,400 tiles/s | 2 workers ≈ 700 tiles/s |
| uploads per frame | ≤ 8 | ≤ 4 |
| GPU memory, tile slots × 1,221 vertices × 24 B | 1,600 slots ≈ 47 MB | 900 slots ≈ 26 MB |
| CPU memory (current body) | classification ≈ 11 MB, pools ≈ 6 MB | same |
| game-thread CPU (quadtree, culling, scheduling, draw submission) | ≤ 1.5 ms | ≤ 2.5 ms |

The guided descent's peak (272 tiles/s on LOW) stays below the estimated capacity; should the measured capacity be lower, the priorities still keep the ground under and ahead of the ship ready while coarse tiles cover the rest until their children arrive.

### 9.8 Rendering

**Vertex format (24 bytes):**

| Attribute | Type | Content |
|---|---|---|
| `a_pos` | `short4` normalised | x, y, z in the tile box; w = `morphDelta` / tile morph range |
| `a_nrm` | `byte4` | octahedral normal (2), `waterDelta` (1, ¼ m steps), flags (1: water, coarse) |
| `a_col` | `ubyte4` | near colour RGB, A = detail-class blend helper |
| `a_coarse` | `ubyte4` | parent-interpolated colour RGB (colour morph), A = sea flag |
| `a_class` | `ubyte4` | detail-class weights (soil, rock, granular, ice) |

**Per-tile uniforms:** model (rotation `Body.rot`, translation = camera-relative tile origin), box half-extents, morph range, tile up vector, morph distances, level.

**Geomorphing** (no popping): with `D = ρ·edge(parent)`, `m = smooth(0.75·D, D, distance(vertex))`; `position −= up·m·morphDelta`, `colour = mix(near, coarse, m)`. At the split distance a new child looks exactly like its parent and becomes itself as the camera approaches. **Skirts** (one row per edge, dropped by `max(2·geometricError, spacing)`) hide cracks between tiles of different levels.

**Depth: three slices** (extending `SceneRenderer.DepthSlices`). Default boundaries: near [0.5 m, 1 km], mid [0.8 km, 50 km], far [40 km, horizon]. 24-bit precision (computed, `Δz ≈ z²/(near·2²⁴)`): 0.12 m at 1 km in the near slice, 0.19 m at 50 km in the mid slice, 6 m at 2,000 km in the far slice. Each slice clears depth (or takes a third of `depthRange`; measure which is cheaper on Adreno/Mali), draws far to near; a tile is drawn in every slice its depth range overlaps (the slice planes clip it, the seams meet exactly). Ship and stations keep their slice logic inside the near and mid slices. A reversed-Z float depth path (`EXT_clip_control` + a 32F depth attachment) can replace the slices where available.

**Draw order:** background (stars, nebula) → `BodyRenderer` (other bodies; for the body with terrain only its sky, atmosphere limb and star are drawn — a flag skips its surface shading where the terrain covers it, once the coarse levels 0–2 are resident; until then the impostor draws the surface as today) → per slice, far to near: opaque terrain (front to back within the slice), stations and ship, water, cloud shells (transparent, drawn per slice so terrain and clouds occlude each other correctly) → dust, glows → HUD.

**Shading:** albedo (9.5) × (sun colour · transmittance · max(N·L, 0) + ambient) with the profile's ambient (day/night formula of section 2.6.4); aerial perspective per vertex (9.11); the engine's ACES tonemap and dither. No real-time terrain shadows in the first version; optional for HIGH: per-vertex sun visibility baked at generation for the finest three levels (8 height samples towards the sun), valid for minutes because the sun hardly moves over a landing site (bodies do not spin; Earth's sun moves 0.015° per minute along its orbit).

### 9.9 Water

- **Near water** (tiles with spacing ≤ 40 m whose minimum ground radius is below the water radius): a second draw of the same VBO in a water shader that moves every vertex by `waterDelta` along the tile up vector, so every water vertex sits at `rWater` (on land it ends below ground and fails the depth test). Colour = the style's `waterLook` colour, or near the sea (tone styles) the sea colour field ×0.6, opaque (Roblox's `seaLook`); transparency and reflectance from `waterLook`; Fresnel reflection of the sky colour; two scrolling procedural normal maps scaled by `wave` and `speed`; sun glint.
- **Far water** (coarser tiles): merged into the terrain: the vertex shader lifts vertices with `waterDelta > 0` to the water level and colours them `mix(waterColour, hazeColour, FieldWaterSky 0.9·farWeight)`, Roblox's far-field water.
- **Collision:** `floorAt` returns the water surface where it is higher (Roblox: one can set down on water; crash rules as on ground).

### 9.10 Collision and altitude queries

`PlanetSurface` (game thread; pure functions over immutable style and classification data, so workers may call them too):

| Function | Definition |
|---|---|
| `heightAt(n)` | `rGround` (9.4) |
| `floorAt(n)` | `max(rGround, rWater)` |
| `altitudeAt(p)` | `length(p − c) − floorAt(unit(p − c))` (`hAgl` for flight, HUD, callouts, mining, training) |
| `upAt(p)` | `unit(p − c)` |
| `gravityAt(p)` | `−upAt(p)·g·60·GravityScale (0.35)` (`02-flight.md` §9.4) |
| `floorHit(p, reach)` | point on the floor below `p` and normal `unit(4·up − dE·east − dN·north)`, `dE = floorAt(p + 2·east) − floorAt(p − 2·east)` (Roblox `floorHit`); nil when `altitudeAt(p) > reach` (`GroundProbe` 400) |
| `groundState(p)` | **solid**: the drawn leaf under `p` is at `Lmax` (or its geometric error there < 1 m) and has been drawn for ≥ `FreshGrace` 0.6 s; **fresh**: the same but younger; **missing**: the drawn leaf is coarser (soft contact, no crash) |
| `surfaceColour(p)` | the near colour at `p` (destruction dust: Roblox mixes it 30 % towards RGB 150,140,128) |
| `materialAt(p)` | top material name (mining, sounds, on foot) |

**Contact** (port of `ShipClient.lua:2598-2642`): if `altitudeAt(ship) < clearance` (`max(12, probe.ground + 0.3)`): `impact = max(−v·up, −0.5·v·n)`; crash when `impact > SafeLandingSpeed 38` and `groundState == solid`; otherwise remove the velocity into the ground and place the ship at `floor + n·clearance`; `landed` when within `clearance + 0.6`. **Cost:** about 1 µs per `heightAt` (one feature query for a tiny box); a frame needs at most about 30 calls (contact 5, callouts' 7 lookahead samples × 2, HUD, mining) ≈ 0.03 ms.

### 9.11 How far field, atmosphere and clouds blend

- **There is no separate far field.** The coarse quadtree levels are the far field: real geometry at real distances. The squash and everything built around it disappear; the haze Roblox baked into the far colours for the "missing" distance (`te − c(te)`) is applied with the real distance.
- **Colour:** palette near, image far (9.5), haze over both.
- **Aerial perspective** is owned by the atmosphere area. Interface: `aerial(viewDir, distance, cameraAltitude, sunDir) → (transmittance RGB, in-scatter RGB)`, evaluated per terrain vertex from a small per-frame LUT (for example 32 log-distances from 10 m to 1,000 km × 32 view elevations, RGBA16F, Rayleigh and Mie in-scatter without phase, the phase applied per vertex), so the ground and the existing impostor sky (`BodyShaders.ATMOSPHERE`) meet at the horizon. **Roblox tuning targets** from this area: haze colour by day `FieldFogDay` RGB 46,105,167 under the blue day sky, else the air colour desaturated 35 % and divided by the ambient tint (`hazeColour`); e-fold distance `FieldHazeK/density` at the ground by day: Earth 3.5 km, Mars 2.8 km, Venus 2.1 km, Titan 1.9 km, thinning with height as `(1 − h/thickness)^1.6` and at night to `0.35 + 0.65·day`; distant water 90 % towards the haze colour; airless bodies no haze. Whether the port keeps Roblox's short visibility or shows the huge planet with clearer air is question Q2.
- **Clouds** (clouds area): the profile's deck as two spherical shells at `R + bottom` and `R + top` around the body centre (Roblox's own geometry), drawn per depth slice after the terrain. Relief never reaches a deck on Sol bodies (≤ 420 m against a lowest bottom of 1,900 m), but depth testing handles any case. Roblox hid its far field above the deck; the port does not need to.
- **Painted clouds in the image** (Earth): from orbit they are the clouds; near the ground the Roblox rule turns white over land into snow and white over sea into sea (9.4.4). The procedural deck does not match the painted pattern, exactly as in Roblox. A cloud-free surface image plus the existing cloud image (`Earth_clouds.png`, `03-world.md` §2.7) would fix both (question Q3).
- **Night:** ambient from the profile (`lerp(nightAmbient, ambient, max(day, 0.3))`), no sun term, the sky's stars through thin air.

### 9.12 What the pilot sees from orbit to the ground (Earth, HIGH)

| Altitude | Terrain | Colour | Notes |
|---|---|---|---|
| 1,000 km | ≈ 46 tiles drawn, levels ≤ 5 | image (all ground > 250 km away) | atmosphere limb from the impostor |
| 100 km | ≈ 104 tiles, levels ≤ 8 | image, palette just starting below the ship (100 km away) | |
| 20 km | ≈ 136 tiles, levels ≤ 11 | palette below, image towards the 505 km horizon | 300 m hills show as shading |
| 4.5 km | ≈ 176 tiles, levels ≤ 13 | palette to tens of km, haze | Roblox's arm height: the Roblox look |
| 100 m | ≈ 580 tiles, levels ≤ 17 | palette, detail textures | path ahead generated first |
| on the ground | finest tiles within ~200 m | as Roblox | contact on the same function |

### 9.13 Small bodies and special cases

- **Phobos and Deimos** are 450 km at S = 3000 (real 11 and 6 km, `03-world.md`); gravity with `GravityScale` 0.013 and 0.006 m/s²: a landing is almost weightless. Same system, `Lmax` 13 (HIGH).
- **Gas and ice giants:** not landable, no terrain (the `floes` style is ported but unused; `03-world.md` Q10).
- **Stars:** no terrain.
- **Bodies without image PNGs:** no classification, no tint; far colour = the runtime texture (`03-world.md` §2.13).

---

## 10. Kotlin port plan

### 10.1 New and changed files

Package `com.starwake.core.terrain` unless noted.

| File | Content | Key API |
|---|---|---|
| `LuauNoise.kt` | Appendix A kernel (float, Roblox-exact) and the double front end with modulo-256 reduction; `fbm`, `ridged` | `perlinF(x, y, z): Float`, `noise(x, y, z): Double` |
| `TerrainMath.kt` | `smooth` (incl. falling form), Roblox `hash` (StrictMath `sin`), `hash4` (PCG), `dune`, `terrace`, `mix4`, `toneOf` | top-level functions, no allocation |
| `TerrainDomain.kt` | the interface the styles call (9.4) and its two implementations `PlaneDomain` (u, v) and `SphereDomain` (P, chart X/Y, direction weight); mutable, one per thread | `noise(inv, ox, oy, salt)`, `fbm(…, k)`, `ridged(…, k)`, `phase(α, β, λ)`, `streak(o)`, `dirWeight` |
| `Features.kt` | layer records, a fixed-capacity feature list, plane `collect`/`collectFar`, sphere shell lattice, `features()` | `collectPlane`, `collectSphere`, `evaluate(list, domain, out)` |
| `Styles.kt` | the 11 styles transcribed from section 2.2.6, the Sol colour sets and derived sets (2.2.7), `TITLE_STYLES`, `CLASS_STYLES`, `SOL_STYLES`, `chooseStyle` | `TerrainStyle.relief(d, list, o, wet, cold, dry, out)`, `paint(…)` |
| `Materials.kt` | Roblox material names → detail class (9.5) and flags (water, emissive lava optional) | `detailClass(name)` |
| `ImageTone.kt` | CPU tone source of the current body: RGB cube faces, sea table, `classify(n, out)` (9.4.4) | `classify` |
| `SurfaceField.kt` | per body: style, offsets, salt, tone source; `heightAt`, `floorAt`, `sampleVertex(n, spacing, out)` | pure, thread-safe |
| `world/SurfaceProfiles.kt` | `ShipSurfaceProfiles.forBody` 1:1 (shared with the atmosphere area) | `forBody(body): SurfaceProfile` |
| `world/Palettes.kt` | shared with `03-world.md` (colour ramps) | `colours(class)` |
| `CubeSphere.kt` | faces, equi-angular mapping, node bounds, direction ↔ (face, a, b) | `dirOf(face, a, b, out)`, `locate(n)` |
| `TerrainQuadtree.kt` | node pool, split/merge with hysteresis, horizon and frustum culling, wanted list, leaf lookup | `update(camera, focus, path)`, `forEachDrawn(slice, fn)` |
| `TileJobs.kt` | worker threads, job heap, completion ring, staging-buffer pool | `request(node, priority)`, `pollCompleted()` |
| `TileBuilder.kt` | per-worker tile generation, steps (1)–(8) of 9.6 | `build(node, slot)` |
| `PlanetSurface.kt` | façade for game code: body selection, update, queries (9.10), stats | `update(…)`, `altitudeAt`, `floorHit`, `groundState`, … |
| `gfx/TerrainRenderer.kt` | slot VBO/VAO pool, uploads, per-slice drawing, water pass | `upload(maxTiles)`, `draw(slice)` |
| `gfx/TerrainShaders.kt` | terrain and water GLSL ES 3.00 (geomorph, detail array, aerial perspective varyings, tonemap) | |
| `gfx/DetailTextures.kt` | procedural detail texture array (4 classes × 2 scales) generated at startup | |
| `gfx/SceneRenderer.kt` (change) | three depth slices including terrain tiles; draw order of 9.8 | |
| `gfx/BodyRenderer.kt` (change) | flag to skip the surface shading of the body that has terrain (keep sky and limb) | `setTerrainBody(i)` |
| `desktop/…/TerrainScenarios.kt` | harness scenarios (10.5) | |
| `desktop/…/ToneBake.kt` | offline tone-map bake from the body cube maps | |
| `tools/terrain_golden.luau` | golden values with the Luau CLI (Appendix B) | |

### 10.2 Hooks into `Game.kt`

- **Creation:** with the scene content (`onSurfaceCreated`/`startFlight`), create `PlanetSurface(system, settings.quality)`; workers start once. On quality change rebuild its pools (like `BodyRenderer.needsRebake`).
- **Body selection** each frame after `system.locate(…)` (`Game.updateFlight`, `Game.kt:654`): the nearest landable, non-star body within its mass-shadow distance becomes the terrain body; switching bodies clears the tree (the impostor covers it meanwhile) and loads the style and tone source on a worker.
- **Update** after `locate`: `planetSurface.update(dt, camera.pos, ship.pos, ship.vel, hAglEstimate)` (quadtree, priorities, job submission, completed uploads ≤ U).
- **Physics:** in the substep loop replace `collideBodies(h)` for the terrain body with `planetSurface.contact(ship, clearance)` (9.10); keep the sphere for bodies without terrain (stars stay heat, giants keep their barrier).
- **Altitude:** `here.altitude` becomes `altitudeAt(ship.pos)` for the terrain body (HUD, landing, speed law `02-flight.md` §9.5).
- **Landing mode:** `holdLanded` keeps the ship at `floor + clearance` with the terrain normal; touchdown message and the arrival toast strings of 5.2.
- **Rendering:** `sceneFrame.terrain = planetSurface`; `SceneRenderer.render` plans slices with the terrain bounds and calls `TerrainRenderer.draw(slice)`; `BodyRenderer.setTerrainBody(i)`.
- **Lifecycle:** `onPause` pauses the workers; `dispose` stops them and frees GL objects.
- **Debug:** `debugInfo` adds `tiles=resident/drawn queue=… gen=…ms up=…`; `debugCommand("terrain")`.

### 10.3 Order of work

| Step | Deliverable | Proven by |
|---|---|---|
| 1 | `LuauNoise`, `TerrainMath`, seed constants | golden noise and hash values (Appendix B step 1), statistics of section 2.0 |
| 2 | `Styles` in plane mode, `SurfaceProfiles`, `Palettes`, `chooseStyle` | Luau-CLI golden relief/paint per style (Appendix B step 2), Sol table of 2.2.7, profile table of 2.6 |
| 3 | `SphereDomain`, chart, 3-D features, `ImageTone` (synthetic image first) | statistics plane vs sphere, continuity tests |
| 4 | `CubeSphere`, `TerrainQuadtree`, `TileJobs`, `TileBuilder` (CPU only) | tile counts of 9.3, desktop CPU benchmark, zero-allocation test |
| 5 | `TerrainRenderer`, shaders, slices, geomorph, skirts | harness views (10.5) |
| 6 | `PlanetSurface` queries, Game hooks (contact, altitude, HUD, landing) | `landing-terrain` scenario, contact tests |
| 7 | water pass, detail textures, aerial-perspective hook, cloud shells interplay | `terrain-water`, `terrain-descent` |
| 8 | real images: cube maps and tone bakes (needs the PNGs, Q6) | Earth coastlines and ice caps match the orbit view |
| 9 | device tuning: ρ controller, workers, uploads | `terrain-perf` on the 8 Elite and a mid-range phone |

### 10.4 Unit tests (`core/src/test/kotlin/com/starwake/core/terrain/`)

- **`LuauNoiseTest`:** 0 at integer points; period 256 (`noise(x + 256, y, z) == noise(x, y, z)` for the double front end); golden values from Studio within 1e−6; the double front end equals the float kernel within 1e−5 for |x| < 1000.
- **`TerrainMathTest`:** `smooth` rising and falling forms; `dune(0) = 0`, `dune(0.78) = 1`, `dune(1⁻) → 0`; `terrace`; `hash(1, 2, 3) = 0.020163`; fbm statistics over 400,000 points: std 0.206/0.181/0.170/0.165 for 2/3/4/5 octaves (±0.005), ridged mean 0.40.
- **`SeedConstantsTest`:** o.x, o.y, angle, salt for every Sol body (section 3.6).
- **`StyleGoldenTest`:** plane-mode relief and paint against the Luau-CLI JSON for every style and the parameter variants of `TITLE_STYLES`, including wet/cold/dry cases for temperate (|Δh| < 1e−4 m, same tags and materials).
- **`StyleStatisticsTest`:** plane mode reproduces the table of 2.2.6 (percentiles ±2 m, shares ±2 points); **sphere mode** over 50,000 random directions on an Earth-size sphere matches plane mode (Kolmogorov–Smirnov distance of the height distribution < 0.03, material shares ±2 points).
- **`ChartTest`:** dune phase continuous across `lonE = ±π` (integer wrap); bearing equals Roblox's `e1/e2` frame at random points below 60° latitude; weight 1 below 63°, 0 above 75.5°.
- **`FeatureLatticeTest`:** areal density within ±5 % of `chance/size²` per layer over 10⁴ km²; plane `collect` identical to the Lua algorithm; features continuous across cube-face edges.
- **`ImageToneTest`:** `toneOf` corner cases (pure sea, white, sand, green); `seaAround` share rule (needs ≥ 4 sea samples; white counts as neither); cloud-over-sea override; NEAR_STEP in angle.
- **`ProfilesTest` / `ChooseStyleTest`:** every Sol body's profile and style as tabled in 2.6.2 and 2.2.7 (Phobos BOULDER FIELDS, Deimos AIRLESS REGOLITH); generated `airFor` cases (blueish and not).
- **`CubeSphereTest`:** mapping round trips; neighbours share edge vertices exactly (same directions from both faces).
- **`QuadtreeTest`:** tile counts at the altitudes of 9.3 within ±10 %; complete cover (no holes) at all times; hysteresis (no split/merge oscillation when hovering at a split distance).
- **`TileBuilderTest`:** shared edge vertices of neighbouring tiles are bit-identical; finest-level vertex heights equal `heightAt` within 1 mm; `morphDelta = 0` on even vertices.
- **`PlanetSurfaceTest`:** `floorAt ≥ heightAt`; `floorHit` normal equals the mesh normal within 2°; `groundState` sequence missing → fresh → solid; contact rule (38 m/s, half the speed into a slope).
- **`PrecisionTest`:** camera 1 m above Earth's surface at heliocentric 2.5e9 m: camera-relative vertex positions stable to < 1 mm between frames.
- **`AllocationTest`:** after warm-up, 600 frames of `PlanetSurface.update` + draw-list building allocate 0 bytes on the calling thread (`ThreadMXBean.getThreadAllocatedBytes`).

### 10.5 Desktop harness scenarios (`desktop/…/TerrainScenarios.kt`)

| Scenario | What it shows and reports |
|---|---|
| `terrain-styles` | one PNG per style (all `TITLE_STYLES` and Sol styles) from 600 m at 30° down and from 2 m, plus a top view with material colours; to compare with Roblox screenshots |
| `terrain-descent` | Earth-like body with a synthetic image (sea, land, ice cap, desert, painted cloud over sea and over land): PNGs at 400 km, 100 km, 30 km, 10 km, 4.5 km, 2 km, 500 m, 100 m, 10 m, 2 m; tiles resident/drawn, triangles, queue, game-thread ms |
| `terrain-seams` | a cube-face corner and LOD borders with a wireframe overlay; checks for cracks (pixel test along the seam) and popping (difference image between frames while moving slowly) |
| `terrain-flyover` | 20 s at 100 m and 260 m/s, then 1.2 km at 420 m/s: tiles/s generated, worst upload latency, frames where the leaf under the ship is not `Lmax` below `PathHeight` (must be 0 after the first second) |
| `terrain-bodies` | ground views of Mercury, Venus, Luna, Mars, Phobos, Io, Europa, Ganymede, Callisto, Titan, Triton |
| `terrain-water` | shoreline by day and at dusk; near water transparency, far water towards the haze, sea colour field |
| `terrain-perf` | fixed camera path from orbit to the ground and a low flyover; CPU per frame, GPU time (`EXT_disjoint_timer_query` when present), tiles, triangles, draw calls |
| `landing-terrain` | the real `Game`: approach, LAND, touchdown on a slope and on water, a crash at 45 m/s on solid ground, no crash on a fresh tile |

---

## 11. Open questions (for the user) and risks

### 11.1 Questions

1. **Q1 Macro relief.** Roblox mountains are at most about 400 m, invisible from orbit on a 6,360 km Earth. Keep that (1:1), or add a planet-scale relief (continents and ranges that show from orbit, for example driven by the image)?
2. **Q2 Haze and visibility.** Roblox's ground haze has an e-fold distance of 3.5 km on Earth (2.8 km Mars, 1.9 km Titan). Keep that look, or use clearer air so the huge planet shows to the horizon?
3. **Q3 Earth's painted clouds.** Roblox's Earth image has the clouds painted in; the terrain turns them into snow over land. Can a cloud-free Earth surface image be made (VisualGen), with the existing `Earth_clouds.png` as a cloud layer that also drives the deck's coverage?
4. **Q4 Directional patterns** (dunes, grooves, streaks): on a sphere they keep Roblox's bearing, but their spacing shrinks with latitude (half at 60°) and they fade out above about 70°. Acceptable?
5. **Q5 Planet-fixed terrain.** In Roblox each landing re-rolled the local terrain around the landing point. In the port the same place always looks the same. Confirm.
6. **Q6 Images.** The PNGs of the body images (`03-world.md` Q5) are needed for Earth's seas and ice and for every tint. At what resolution do they exist (4096² cube crosses)?
7. **Q7 Material detail.** Roblox's material textures cannot be shipped; the port uses original procedural detail textures with the same colours. Acceptable?
8. **Q8 Small moons.** Phobos and Deimos are 450 km at S = 3000 with 0.013 and 0.006 m/s² of gravity on the ground (35 % factor). Keep?
9. **Q9 Entry flow.** Keep the guided entry (L, level-off at 900 m, release at 320 m/s) or free flight to the ground (`02-flight.md` Q3)? The guided descent sets the tile-generation peak (9.7).
10. **Q10 Water.** Roblox treats the water surface as the floor (a ship can set down on the sea). Keep?
11. **Q11 Absolute heights** (`03-world.md` Q11): confirm cloud decks (Earth 2,100–2,900 m), air thickness (26 km) and the near-ground rules in metres, unchanged by S.
12. **Q12 Titan's air.** Titan's ground profile has orange haze and a cloud deck, but Titan has no atmosphere shell in space. Give it one, or drop the haze on the ground?

### 11.2 Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | CPU cost of tile generation on mid-range phones (20–30 noise calls per vertex) | band-limited noise and feature skipping on coarse tiles; 2 workers; ρ controller; path-first priorities; measure early (step 4) |
| R2 | Draw-call overhead (300–700 tiles per frame) | VAO per slot, uniforms only; merge coarse tiles into shared VBO pages if needed |
| R3 | Depth slices with tiles spanning slice borders (seams, double cost) | slice borders placed between tile levels where possible; harness seam test; reversed-Z path where `EXT_clip_control` exists |
| R4 | The look drifts from Roblox (3-D domain, Mercator chart, shell lattice) | plane-mode golden tests for exactness; sphere-vs-plane statistics tests; side-by-side screenshots |
| R5 | `math.noise` is not exactly improved Perlin as assumed | Studio golden values first (Appendix B step 1); the statistics already match the Lua comments |
| R6 | Classification artefacts: 10–20 km texels give blobby coasts; painted clouds give snow blobs | coast noise as in Roblox; Q3; higher-resolution tone maps on HIGH |
| R7 | Memory on mid-range (VBO slots, images, tone maps) | slot counts per quality; only the current body's tone source in RAM |
| R8 | Thermal throttling in long low flights | ρ controller; upload caps; worker count 2 on LOW |
| R9 | Concurrency bugs between workers and the GL thread | slots owned by exactly one side at a time; completion ring; 3-frame reuse delay; stress test in the harness |
| R10 | Visible popping of large relief at LOD changes | geomorphing of heights and colours; morph distances tied to ρ |
| R11 | The image→palette blend band shows as a colour change | tint per vertex; tune `ImageNear`/`ImageFar`; check from 20–100 km in `terrain-descent` |
| R12 | Generation stalls when switching bodies (moons near planets) | keep the impostor until the coarse levels are ready; load the tone source on a worker |

---

## Appendix A. Luau `math.noise` (improved Perlin noise)

Luau evaluates `math.noise(x, y, z)` in 32-bit float on `(float)x, (float)y, (float)z` (`VM/src/lmathlib.cpp`). Kotlin transcription of the kernel:

```kotlin
private val P = intArrayOf(  // Ken Perlin's reference permutation, then repeated once (512 entries)
    151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,
    190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,
    136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,
    41,55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,
    116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,
    207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,
    167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,251,34,242,193,
    238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,
    176,115,121,50,45,127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180,
).let { it + it }

private fun fade(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)
private fun lerp(t: Float, a: Float, b: Float) = a + t * (b - a)
private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
    val h = hash and 15
    val u = if (h < 8) x else y
    val v = if (h < 4) y else if (h == 12 || h == 14) x else z
    return (if (h and 1 != 0) -u else u) + (if (h and 2 != 0) -v else v)
}

fun perlinF(x: Float, y: Float, z: Float): Float {
    val fx = floor(x); val fy = floor(y); val fz = floor(z)
    val xi = fx.toInt() and 255; val yi = fy.toInt() and 255; val zi = fz.toInt() and 255
    val xf = x - fx; val yf = y - fy; val zf = z - fz
    val u = fade(xf); val v = fade(yf); val w = fade(zf)
    val a = P[xi] + yi; val aa = P[a] + zi; val ab = P[a + 1] + zi
    val b = P[xi + 1] + yi; val ba = P[b] + zi; val bb = P[b + 1] + zi
    return lerp(w,
        lerp(v, lerp(u, grad(P[aa], xf, yf, zf), grad(P[ba], xf - 1, yf, zf)),
                lerp(u, grad(P[ab], xf, yf - 1, zf), grad(P[bb], xf - 1, yf - 1, zf))),
        lerp(v, lerp(u, grad(P[aa + 1], xf, yf, zf - 1), grad(P[ba + 1], xf - 1, yf, zf - 1)),
                lerp(u, grad(P[ab + 1], xf, yf - 1, zf - 1), grad(P[bb + 1], xf - 1, yf - 1, zf - 1))))
}

/** World front end: double inputs, reduced modulo the period before the float kernel. */
fun noise(x: Double, y: Double, z: Double): Double =
    perlinF(mod256(x).toFloat(), mod256(y).toFloat(), mod256(z).toFloat()).toDouble()
private fun mod256(v: Double): Double { val r = v % 256.0; return if (r < 0) r + 256.0 else r }
```

Plane-mode golden tests call `perlinF(x.toFloat(), y.toFloat(), z.toFloat())` directly (Roblox semantics). The table, the gradient set and the fade curve are Perlin's public reference; the computed statistics in section 2.0 were produced with exactly this kernel.

## Appendix B. Golden values

**Step 1 — Studio command bar** (edit mode, read-only: it only prints). Paste, run, copy the Output line into `core/src/test/resources/terrain/noise_golden.json`:

```lua
local pts = {
	{0.5, 0.5, 0.5}, {1.25, -3.75, 0.37}, {123.456, 789.012, 9.1}, {-1000.3, 2000.7, 4.4},
	{16000/48 + 827.928, -9000/48 + 310.175, 9.1}, {255.9, 0.1, 0.7}, {256.1, 0.1, 0.7}, {-0.25, -0.5, -0.75},
}
local out = {}
for _, p in pts do
	table.insert(out, string.format("[%.6f,%.6f,%.6f,%.9f]", p[1], p[2], p[3], math.noise(p[1], p[2], p[3])))
end
local function hash(i, j, k) local n = math.sin(i * 12.9898 + j * 78.233 + k * 37.719) * 43758.5453 return n - math.floor(n) end
print('{"noise":[' .. table.concat(out, ",") .. '],"hash123":' .. string.format("%.12f", hash(1, 2, 3)) .. '}')
```

**Step 2 — Luau CLI** (offline, `tools/terrain_golden.luau`, run with the open-source `luau` binary, whose `math.noise` is the same VM code; confirm with step 1 first):

- Copy `ShipClient/ShipSurfaceTerrain.lua` lines 76–85 and 87 (the local aliases `noise`, `floor`, `sqrt`, …, `rgb`; leave out `workspace.Terrain`) and lines 163–1129 (helpers, features, styles, colour sets, selection tables) into the tool unchanged.
- Stub what they touch: `Color3.new/fromRGB` with fields `R, G, B` and a `Lerp` method; `Enum.Material` as a table that returns the key name; `ShipPalettes.colours` with the table of `03-world.md` §2.6.
- For every style (each `TITLE_STYLES` entry and each `SOL_STYLES` builder) and the offsets/salt of a fixed seed (Earth's of section 3.6), evaluate on a 64 × 64 grid with 37 m spacing plus 200 random points in ±40 km: `relief` (with `collect` over `u ± 0.5`), the floor clamp, `paint` with the slope from ±4 m differences; for temperate also the wet/cold/dry combinations {0, 0.3, 0.6, 1}³ at 50 points.
- Write JSON `{style, u, v, wet, cold, dry, h, tag, top, under}` to `core/src/test/resources/terrain/golden_<style>.json`; `StyleGoldenTest` compares plane mode against it.

