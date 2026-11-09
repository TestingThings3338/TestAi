# Roblox source for the Android port

`source/` holds the exports from Roblox Studio:

- `scripts.rar`: ShipClient, ShipServer and ShipCore as Roblox XML (`.rbxmx`), 98 scripts.
- `Ships.gltf`: the 8 ships (Varrow, Brannock, Calder, Dornoch, Kessock, Lochran, Morven, Torrin),
  triangulated by Studio, with Hull, Cockpit, Canopy, Engines, Gear and Lights (plus Interior,
  Bridge and Movers on the bigger ships) as separate nodes.
- `Assets.rar`: the `Assets` folder as Roblox XML: Ships, Stations (Ring, Garden, Foundry, Spire,
  Hollow, Refinery), Decks (Concourse, Hangar, Quarters) and Construction.

Readable Lua (git-ignored, regenerate any time):

```sh
mkdir -p /tmp/rbx && bsdtar -xf roblox/source/scripts.rar -C /tmp/rbx
python3 tools/roblox/extract_lua.py roblox/lua /tmp/rbx/*.rbxmx
```

`roblox/lua/INDEX.tsv` lists every script with its line count. The port specs are in
`docs/port/`.
