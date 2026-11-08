# Exporting the Roblox game for the Android port

The Android version rebuilds the Roblox game from its real data. Everything below is produced on the PC that has Roblox Studio and the `SpaceGame` folder. Run it in the same kind of Claude Code session that built the game: with the Roblox Studio MCP connected and the place open.

## Prompt to paste into the PC session

> Export the game for the Android port. Do not change anything in the place. Only read from it and write files into a clone of the GitHub repo `TestingThings3338/TestAi`, branch `claude/cloud-xiaomi-15-compat-p860ky`, under `roblox/`:
>
> 1. **Scripts:** every Script, LocalScript and ModuleScript in the place, saved as `roblox/scripts/<full.instance.path>.lua`. Also write a `roblox/scripts/INDEX.md` listing path, class, line count and a one-line purpose for each.
> 2. **Ships:** for each ship model (VARROW, CALDER, LOCHRAN, BRANNOCK, KESSOCK, MORVEN, TORRIN, DORNOCH), plus each ship's cockpit/CockpitPlus model if it is separate, write `roblox/ships/<NAME>.json` in the format described in "Ship JSON format" below. Include every BasePart (Part, WedgePart, CornerWedgePart, TrussPart, MeshPart, UnionOperation), with CFrames relative to the ship's PrimaryPart (or its pivot). Also include SurfaceGui, Decal and light instances and their properties.
> 3. **Ship stats:** export ShipConfig/ShipEconomy as `roblox/ships/stats.json`. Include every number used by the flight model and the economy: speeds, rates, mass, prices, cargo and fuel.
> 4. **UI:** for every ScreenGui, write `roblox/ui/<name>.json`. For each GuiObject include class, Name, Size and Position (UDim2), AnchorPoint, ZIndex, colours, transparency, font, text, TextSize, image asset IDs and UICorner, UIStroke and UIGradient settings. Also take screenshots of the main menu, the HUD in flight, the galaxy map, the system map and each cockpit into `roblox/screenshots/`.
> 5. **World:** export the star system definition as `roblox/world.json`: star, planets (radius, orbit, colours, textures), stations (type, position) and zones.
> 6. **Audio and art:** list every Sound (SoundId, volume, where it's used) and every texture or mesh asset ID in `roblox/assets.md`. Mark which ones are original (made by AFloidDEV, Suno music, ElevenLabs voice) and which come from the Toolbox or other creators. Copy the original audio files the folder has into `roblox/audio/`.
> 7. **Docs:** copy `SPACE_ENGINE_ANALYSIS.md`, `MEMORY.md`, the `space-engine-state.md` notes and the `status/` docs into `roblox/docs/`.
>
> Commit in small chunks and push to the branch above.

## Ship JSON format

```json
{
  "name": "CALDER",
  "maker": "MERE & CAIRN",
  "pivot": "PrimaryPart name",
  "parts": [
    {
      "name": "HullTop",
      "class": "WedgePart",
      "shape": "Block|Ball|Cylinder|Wedge|CornerWedge",
      "cframe": [x, y, z, r00, r01, r02, r10, r11, r12, r20, r21, r22],
      "size": [x, y, z],
      "color": [r, g, b],
      "material": "SmoothPlastic",
      "transparency": 0,
      "reflectance": 0,
      "meshId": "rbxassetid://… (MeshPart/SpecialMesh only)",
      "meshScale": [1, 1, 1],
      "textureId": "",
      "tags": ["engine", "canopy", "light", "gear", "interior"]
    }
  ],
  "lights": [ { "class": "PointLight", "parent": "partName", "color": [r, g, b], "brightness": 1, "range": 8 } ],
  "guis":   [ { "parent": "partName", "face": "Front", "class": "SurfaceGui", "json": "…" } ]
}
```

Positions are in studs. The port converts them as 1 stud = 0.28 m, the usual Roblox scale; tell me if the game used a different scale. MeshParts and unions can't be read as plain parts. For those, also export the mesh file (`.obj`) via Studio's "Export Selection" if it was made by AFloidDEV.
