# Untitled Space Exploration (Android)

A native Android version of **Untitled Space Exploration**, a game by AFloidDEV originally built on Roblox. It is single-player for now. The goal is a 1:1 port of the Roblox game: its ships, cockpits, flight feel, HUD and look. It runs at 60 fps by default, and smoothly up to 120 Hz (Settings) on phones such as the Xiaomi 15.

**Status:** you fly in **Sol at real scale**, from MERIDIAN STATION down to the ground of the Earth and back, without a loading screen.

- **Real sizes.** The Earth is 6,360 km in radius, the Moon 1,740 km at 384,000 km, Mars 3,390 km. MERIDIAN hangs 19,800 km from the Earth's centre, where the planet fills 37° of the sky. The Sun, 8 planets, 9 moons and 6 stations are there. (As in the Roblox game, the giants are 0.4× real and the planets' orbits 1/60 of real, so a trip between planets takes about a minute.)
- **Procedural planets, seamless from orbit to the ground.** Every solid body (the Earth, the Moon, Mars, Venus, Mercury, the big moons) is generated on the phone from its seed: continents, seas, mountain ranges, then relief and ground cover down to 2.5 m. The same functions draw the planet from 20,000 km and the hill you land on.
- **Sea.** Swells move over the water, the sun glitters on them, and the sky is mirrored at grazing angles.
- **Atmosphere and weather.** The air scatters sunlight (blue days, red dusks, a thin bright limb from space). A cloud deck covers the worlds with air: weather systems with cyclones seen from orbit, broken cloud you fly down through (it fogs the cockpit inside), and cloud shadows on the ground.
- **Physics of the place.**
  - Gravity is the real 9.81 m/s² on the Earth (1.62 on the Moon) and falls with the square of the distance. With Flight Assist on, the thrusters hold the ship against it. With Flight Assist off the ship falls, and at 7.7 km/s it stays in orbit.
  - Air drags on the ship (density falls by e every 8.5 km on the Earth). The ship cannot hold orbital speed in thick air.
  - A fast entry heats the hull: a shock of glowing air forms around the ship (outside the canopy from the cockpit, a sheath of fire around the hull from the external view), it lights the cabin, the HEAT bar fills, and a hull kept beyond its limit burns. The star burns a hull that comes too close.
  - Hard ground contacts cost hull. A lost ship is recovered at the nearest station.
- **Landing.** Tap LAND below 60 km: the gear comes down. Set down slowly on level ground (or on water) and the ship stands on its gear on the real terrain.

You fly the real ships of the Roblox game: VARROW (the starter ship), TORRIN, CALDER, LOCHRAN, BRANNOCK, KESSOCK, DORNOCH and MORVEN, with their 3D cockpits, canopy glass, engine glow and animated landing gear. Pick one with the arrows on the ship card in the main menu. For now they all share one flight model; each ship's own handling comes with the flight-model port.

### A first flight to the ground

1. Tap **LAUNCH** in the menu, then **LAUNCH** on the station screen. The ship leaves the slot with the Earth ahead.
2. Push the throttle up and point at the planet. The speed limit grows with the distance from everything, so the 13,000 km to the atmosphere take well under a minute.
3. From 300 km up the top of the screen shows **ALT** (height above the real ground), **VS** (climb rate) and the local gravity.
4. Below about 90 km the air starts to glow. Watch the **HEAT** bar and pull the throttle back if it turns amber.
5. Below 60 km **LAND** appears. Tap it, slow down, and use ▼ to settle onto level ground.

## Install the latest build on your phone

Every push builds a signed release APK on GitHub Actions and publishes it as the **`dev-latest`** pre-release:

1. On the phone, open the repo's **Releases** page, then the dev build, then tap the `.apk` file.
2. Open the downloaded file and allow "Install unknown apps" for your browser when asked.
3. Builds are signed with the same development key, so newer APKs install over older ones.

## Controls

The game is played in landscape with two thumbs.

A flight starts **docked at MERIDIAN STATION**, 13,440 km above Earth: tap **LAUNCH** on the station screen to fly out. The default view is the **cockpit**.

You can land on the rocky planets and moons. Jupiter, Saturn, Uranus and Neptune have no solid surface: their cloud tops stop the ship (**LANDING BLOCKED · NO SOLID SURFACE**).

| Control | Action |
|---|---|
| **Left thumb** (left side of the screen) | Throttle lever. Slide up or down to set your speed; the throttle stays where you leave it. The bottom part of the lever is reverse. Slide sideways for **yaw**. Double-tap to stop. |
| **Right thumb** (right side of the screen) | Floating flight stick. Up/down **pitches**, sideways **rolls** (you can switch sideways to yaw in Settings). It springs back to centre when you let go. An arrow from the screen centre shows the turn direction. |
| **▲ ▼ ◀ ▶** | Thrusters: up, down, left, right. |
| **Top strip** | Drag a finger along the top of the screen to look around (up to 140° to each side, 80° up, 70° down). Let go and the view swings back. |
| **BOOST** | A short burst up to 400 m/s. It uses one capacitor charge (the pips), which recharge over time. |
| **FA** | Flight Assist. **On:** the ship flies where it points and hovers against gravity. **Off:** Newtonian flight: drift, free fall, orbits. |
| **LOCK** | Tap: locks the target nearest your nose, or the next one farther away when something is locked. Amber brackets mark it and show its distance. Hold for half a second: release the lock. |
| **MAP** | System map. The ship keeps flying while it is open. |
| **VIEW** | Cockpit and external view (behind and above the ship). The old chase camera can be added in Settings. |
| **LAND / DOCK** | Appears near a planet or a station: landing mode, auto-dock, take-off, cancel. |
| **II** / Back | Pause. |

**Gamepads (Bluetooth controllers) are supported:**

| Input | Action |
|---|---|
| Left stick | Pitch and roll |
| Right stick | Yaw (sideways) and vertical thrust (up/down) |
| L3 (left stick click) held + right stick | Look around |
| R3 (right stick click) held | Full stop |
| RT / LT | Throttle up / down. The throttle stops at zero when it crosses it. |
| LB / RB | Strafe left / right |
| A | Boost (on the station screen: launch) |
| X | Flight Assist |
| Y | View |
| D-pad up | Next target; hold to release the lock |
| D-pad down | Land / dock / take off (the action shown on screen) |
| Select | Map |
| Start | Pause |
| B | Back |

**Settings:**

- Graphics quality: LOW, MEDIUM, HIGH and ULTRA. On first launch it is picked automatically from the phone's GPU; flagship GPUs (Adreno 740 and newer, Immortalis) start on ULTRA.
  - ULTRA keeps full terrain detail half as far again, marches the air in more steps, uses a sharper far image of the planet (4096 × 2048) and a finer ground texture.
  - HIGH and ULTRA shade the ground's grain as relief.
- Frame-rate limit: max / 60 / 30 (60 by default).
- Field of view (60–100°, 80 by default) and camera shake.
- Chase view: adds the old lagging chase camera as a third VIEW step.
- FPS counter.
- Stick axis mapping and pitch direction.
- Stick sensitivity.
- Left-handed layout.
- Haptics.

## How it is optimized for phones

- **Custom OpenGL ES 3.0 renderer, with no game-engine overhead.** The APK is about 3 MB, 2.8 MB of it the eight ships.
  - The scene is drawn in about 15 draw calls. Planets, moons and the Sun are ray-cast spheres drawn on screen-space quads (no meshes); the atmosphere is computed in the same pass, and a ring is one more quad.
  - A ship is 25,000–60,000 triangles in two meshes (hull, cockpit), drawn in one call per mesh for the solid parts and one for the glass. The landing gear moves in the vertex shader (one matrix per gear joint), so nothing is re-uploaded while it folds.
  - There are no post-processing passes. Tone mapping and gamma are done inside each shader, so no extra full-screen framebuffer traffic is needed.
  - The space background (nebula) and the surface of every planet and moon are baked into textures once at startup.
  - Text uses one signed-distance-field font atlas containing the game's fonts (Titillium Web, Sarpanch, Roboto Mono), which stays sharp at any size.
- **Mobile GPU friendly.**
  - It uses 4× MSAA where supported; tile-based mobile GPUs do this almost for free.
  - The depth buffer is cleared once per frame (the sky and the near scene use separate depth ranges instead of a second clear) and is discarded instead of being written back to memory.
- **Frame pacing.**
  - Each frame is triggered by the display's refresh signal (vsync), so motion stays smooth at 60, 90 or 120 Hz.
  - With the MAX frame-rate setting the game asks Android for the highest refresh-rate display mode; it reports frame work to Android's performance-hint system.
  - Menus and the pause screen drop to 60 fps, and the screen is only kept awake while you are flying.
- **No garbage-collection stutter.** The per-frame code makes no memory allocations.
- **Big coordinates without jitter.** World positions are stored with high precision and drawn relative to the camera.
- **Planet terrain.**
  - The ground is a cube-sphere quadtree of 65 × 65-vertex tiles, built on background threads and uploaded a few per frame, so the frame rate does not depend on how fast you descend.
  - The view is drawn in up to four depth slices, so one 24-bit depth buffer resolves half a metre at the ship and the horizon 300 km away.
  - From orbit the terrain shows the planet's baked far image, the same one the distant planet is drawn with, so the change from "planet in the sky" to "ground below" cannot be seen.
  - Clouds cost a handful of texture lookups per pixel: one weather map for the whole planet and a 64³ tiling noise for the detail. There is no ray marching.

## Porting from Roblox

The Roblox exports are in `roblox/source/` (see `roblox/README.md`): the scripts, the eight ships as glTF (`Ships.gltf`) and the `Assets` folder (`Assets.rar`: ships with their attributes, stations, decks).

The ships in `app/src/main/assets/ships/` are generated from them. To rebuild them after a new export (Python 3, standard library, and `bsdtar` to read the RAR):

```bash
python3 tools/roblox/convert_ships.py
```

The script header documents the binary format: quantised vertices, the view classes (cockpit, outside), the landing-gear joints, nozzles and lights. The output is deterministic, so unchanged inputs give identical files.

## Project layout

```
core/     Engine and game in pure Kotlin. No Android dependencies, so it can be unit tested.
          world/ is the star system (Sol), game/WorldTerrain.kt the terrain body and the
          planets' far looks, terrain/ the height functions and the tile quadtree,
          gfx/CloudField.kt the weather.
app/      Android shell: Activity, GLSurfaceView, GLES30 binding, touch and gamepad,
          refresh rate, keep-awake and performance hints
desktop/  Headless preview tool: runs the real engine on Mesa (software OpenGL) and saves
          PNG screenshots plus CPU and allocation numbers
tools/    gen_font.py builds the SDF font atlas from tools/fonts;
          roblox/convert_ships.py builds the ship assets, roblox/extract_lua.py the readable scripts
roblox/   The Roblox exports (source/) the port is built from
```

## Building

You need JDK 17 or newer and an Android SDK with platform 36 (set it in `local.properties`: `sdk.dir=...`).

```bash
./gradlew :core:test                 # engine unit tests
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
./gradlew :desktop:run --args="--scenario all"   # render preview screenshots to desktop/out
./gradlew :desktop:run --args="--scenario earth,earth-entry"   # MERIDIAN to the ground of the Earth; a real-time entry
```


The keystore in `keystore/` is a **development key**, committed so that test builds install over each other. Create a private key before publishing anywhere.

## Credits

- Game design and original Roblox game: AFloidDEV.
- Fonts:
  - Titillium Web, Sarpanch and Roboto Mono, all SIL Open Font License 1.1.
  - Inter, OFL 1.1, used for symbols only.
  
  The license texts are in `app/src/main/assets/font/`.
