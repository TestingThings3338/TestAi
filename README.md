# Untitled Space Exploration (Android)

A native Android version of **Untitled Space Exploration**, a game by AFloidDEV originally built on Roblox. It is single-player for now. The goal is a 1:1 port of the Roblox game: its ships, cockpits, flight feel, HUD and look. It is built to run smoothly at 120 Hz on phones such as the Xiaomi 15.

**Status:** port milestone 1. It has the engine, the main menu, the flight model and touch controls, in an asteroid field next to a ringed gas giant.

The ship in this build is a **stand-in hull** labelled VARROW. The real Roblox ships (VARROW, CALDER, LOCHRAN, BRANNOCK, KESSOCK, MORVEN, TORRIN, DORNOCH) and their cockpits will replace it once their part data is exported from Roblox Studio into `roblox/` (see below).

## Install the latest build on your phone

Every push builds a signed release APK on GitHub Actions and publishes it as the **`dev-latest`** pre-release:

1. On the phone, open the repo's **Releases** page, then the dev build, then tap the `.apk` file.
2. Open the downloaded file and allow "Install unknown apps" for your browser when asked.
3. Builds are signed with the same development key, so newer APKs install over older ones.

## Controls

The game is played in landscape with two thumbs.

| Control | Action |
|---|---|
| **Left thumb** (left side of the screen) | Throttle lever. Slide up or down to set your speed; the throttle stays where you leave it. The bottom part of the lever is reverse. Slide sideways for **yaw**. Double-tap to stop. |
| **Right thumb** (right side of the screen) | Floating flight stick. Up/down **pitches**, sideways **rolls** (you can switch sideways to yaw in Settings). An arrow from the screen centre shows the turn direction. |
| **▲ / ▼** | Vertical thrusters (up/down). |
| **BOOST** | A short burst up to 400 m/s. It uses one capacitor charge (the pips), which recharge over time. |
| **FA** | Flight Assist. **On:** the ship flies where it points. **Off:** Newtonian drift. |
| **LOCK** | Locks the target nearest your nose. Amber brackets mark it and show its distance. Tap again to release. |
| **VIEW** | Cycles chase, far chase and cockpit views. |
| **II** / Back | Pause. |

**Gamepads (Bluetooth controllers) are supported:**

| Input | Action |
|---|---|
| Left stick | Pitch and roll |
| Right stick | Yaw (sideways) and vertical thrust (up/down) |
| RT / LT | Throttle up / down. The throttle stops at zero when it crosses it. |
| LB / RB | Strafe left / right |
| A | Boost |
| X | Flight Assist |
| Y | View |
| D-pad up | Lock |
| Start | Pause |
| B | Back |

**Settings:**

- Graphics quality. On first launch it is picked automatically from the phone's GPU.
- Frame-rate limit: max / 60 / 30.
- FPS counter.
- Stick axis mapping and pitch direction.
- Stick sensitivity.
- Left-handed layout.
- Haptics.

## How it is optimized for phones

- **Custom OpenGL ES 3.0 renderer, with no game-engine overhead.** The APK is about 0.2 MB.
  - The scene is drawn in about 12 draw calls. The asteroid field uses instanced rendering: one draw call per rock shape.
  - There are no post-processing passes. Tone mapping and gamma are done inside each shader, so no extra full-screen framebuffer traffic is needed.
  - The space background (nebula) and the gas giant's surface are baked into textures once at startup.
  - Text uses one signed-distance-field font atlas containing the game's fonts (Titillium Web, Sarpanch, Roboto Mono), which stays sharp at any size.
- **Mobile GPU friendly.**
  - It uses 4× MSAA where supported; tile-based mobile GPUs do this almost for free.
  - The depth buffer is cleared once per frame (the sky and the near scene use separate depth ranges instead of a second clear) and is discarded instead of being written back to memory.
- **Frame pacing.**
  - Each frame is triggered by the display's refresh signal (vsync), so motion stays smooth at 60, 90 or 120 Hz.
  - The game asks Android for the highest refresh-rate display mode and reports frame work to Android's performance-hint system.
  - Menus and the pause screen drop to 60 fps, and the screen is only kept awake while you are flying.
- **No garbage-collection stutter.** The per-frame code makes no memory allocations.
- **Big coordinates without jitter.** World positions are stored with high precision and drawn relative to the camera.

## Porting from Roblox

The Roblox source lives in Roblox Studio and on the developer's PC. To port the real content, the export should go into `roblox/` in this repo:

- `roblox/scripts/` – every script as `.lua`.
- `roblox/ships/<NAME>.json` – every part of each ship model: class, shape, position/rotation (CFrame), size, colour, material, transparency, and mesh IDs for MeshParts.
- `roblox/docs/` – `SPACE_ENGINE_ANALYSIS.md`, memory/state notes and UI screenshots.

## Project layout

```
core/     Engine and game in pure Kotlin. No Android dependencies, so it can be unit tested.
app/      Android shell: Activity, GLSurfaceView, GLES30 binding, touch and gamepad,
          refresh rate, keep-awake and performance hints
desktop/  Headless preview tool: runs the real engine on Mesa (software OpenGL) and saves
          PNG screenshots plus CPU and allocation numbers
tools/    gen_font.py builds the SDF font atlas from tools/fonts
```

## Building

You need JDK 17 or newer and an Android SDK with platform 36 (set it in `local.properties`: `sdk.dir=...`).

```bash
./gradlew :core:test                 # engine unit tests
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
./gradlew :desktop:run --args="--scenario all"   # render preview screenshots to desktop/out
```

The keystore in `keystore/` is a **development key**, committed so that test builds install over each other. Create a private key before publishing anywhere.

## Credits

- Game design and original Roblox game: AFloidDEV.
- Fonts:
  - Titillium Web, Sarpanch and Roboto Mono, all SIL Open Font License 1.1.
  - Inter, OFL 1.1, used for symbols only.
  
  The license texts are in `app/src/main/assets/font/`.
