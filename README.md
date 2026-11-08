# Starwake

Starwake is an original space-flight game for Android phones: open-space flight in six degrees of freedom, with flight assist and boost. It is built to run smoothly at 120 Hz on high-end phones such as the Xiaomi 15, and to scale down for mid-range devices.

**Status:** prototype, milestone 1. It has the main menu, the first ship (KT-1 *Kite*), and the flight controls.

## Install the latest build on your phone

Every push builds a signed release APK on GitHub Actions and publishes it as the **`dev-latest`** pre-release:

1. On the phone, open the repo's **Releases** page, then **Starwake dev build**, then tap the `.apk` file.
2. Open the downloaded file and allow "Install unknown apps" for your browser when asked.
3. Builds are signed with the same development key, so newer APKs install over older ones.

## Controls

The game is played in landscape with two thumbs.

| Control | Action |
|---|---|
| **Left thumb** (left side of the screen) | Throttle lever. Slide up or down to set your speed; the throttle stays where you leave it. The bottom part of the lever is reverse. Slide sideways for **yaw** (rudder). Double-tap to stop. |
| **Right thumb** (right side of the screen) | Floating flight stick. Up/down **pitches**, sideways **rolls** (you can switch sideways to yaw in Settings). |
| **BOOST** | A short burst up to 400 m/s. It uses one capacitor charge (the pips), which recharge over time. |
| **FA** | Flight Assist. **On:** the ship flies where it points and stops turning when you let go. **Off:** Newtonian flight; you keep drifting and spinning until you counter-thrust. |
| **CAM** | Cycles chase, far chase and cockpit views. |
| **II** / Back | Pause. |

**Gamepads (Bluetooth controllers) are supported:**

| Input | Action |
|---|---|
| Left stick | Pitch and roll |
| Right stick | Yaw (sideways) and vertical thrust (up/down) |
| RT / LT | Throttle up / down |
| LB / RB | Strafe left / right |
| A | Boost |
| X | Flight Assist |
| Y | Camera |
| Start | Pause |
| B | Back |

**Settings:**

- Graphics quality: resolution scale, star, dust and asteroid counts.
- Frame-rate limit: max / 60 / 30.
- FPS counter.
- Stick axis mapping and pitch direction.
- Stick sensitivity.
- Left-handed layout.
- Haptics.

## How it is optimized for phones

- **Custom OpenGL ES 3.0 renderer, with no game-engine overhead.** The APK is tiny and starts instantly.
  - The scene is drawn in about 12 draw calls. The asteroid field uses instanced rendering: one draw call per rock shape.
  - There are no post-processing passes. Tone mapping and gamma are done inside each shader, so no extra full-screen framebuffer traffic is needed.
  - The space background (nebula) and the planet surface are baked into textures once at startup. After that, drawing the sky costs a single texture lookup per pixel.
  - Text is drawn from one signed-distance-field font texture, which stays sharp at any size.
- **Mobile GPU friendly.**
  - It uses 4× MSAA where supported; tile-based mobile GPUs do this almost for free.
  - The depth buffer is cleared every frame and then discarded instead of being written back to memory.
  - Flat-shaded, low-poly models avoid texture bandwidth.
- **Frame pacing.**
  - Each frame is triggered by the display's refresh signal (vsync), so motion stays smooth at 60, 90 or 120 Hz.
  - The game asks Android for the highest refresh-rate display mode.
  - It reports frame work to Android's performance-hint system so the CPU clocks itself correctly.
- **No garbage-collection stutter.** The per-frame code makes no memory allocations. All math objects and draw buffers are allocated once and reused.
- **Big coordinates without jitter.** World positions are stored with high precision and drawn relative to the camera. Ships stay precise no matter how far you fly.

## Project layout

```
core/     Engine and game in pure Kotlin. No Android dependencies, so it can be unit tested.
  math/      vectors, quaternions, matrices, random numbers and noise (allocation free)
  gfx/       Gl interface, shaders, scene and UI renderers, camera
  content/   procedural ship, asteroid and starfield generators
  game/      flight model, asteroid field, cameras
  input/     input queue, gamepad state, touch flight controls
  ui/        widgets and screens (menu, settings, pause)
app/      Android shell: Activity, GLSurfaceView, GLES30 binding, touch and gamepad,
          refresh rate and performance hints
desktop/  Headless preview tool: runs the real engine on Mesa (software OpenGL) and saves
          PNG screenshots plus CPU and allocation numbers
tools/    gen_font.py builds the SDF font atlas (from Inter, OFL)
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

- UI font: [Inter](https://github.com/rsms/inter) (SIL Open Font License 1.1); the license is in `app/src/main/assets/font/OFL.txt`.
- All ships, planets and effects are generated procedurally in code.
