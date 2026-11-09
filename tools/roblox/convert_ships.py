#!/usr/bin/env python3
"""Converts the 8 Roblox ships into the game's compact binary ship assets.

Usage (from the repository root):

    python3 tools/roblox/convert_ships.py [--gltf roblox/source/Ships.gltf]
        [--rbxmx Assets.rbxmx | --rar roblox/source/Assets.rar] [--out app/src/main/assets/ships]

Python 3 standard library only. Without --rbxmx the Roblox XML is streamed straight out of
roblox/source/Assets.rar with `bsdtar -xOf` (219 MB, never written to disk or loaded whole).

INPUTS
  Ships.gltf  (Roblox Studio export): one root node per ship (Varrow, Brannock, Calder, Dornoch,
      Kessock, Lochran, Morven, Torrin) with the groups Hull, Cockpit, Canopy, Engines, Gear,
      Lights (+ Interior, Bridge, Movers on the walkable ships). Every BasePart is a node whose
      scale is the part's Size and whose mesh is a unit primitive (Block, Wedge, Cylinder, Ball,
      CornerWedge) or a union mesh, all normalised to -0.5..0.5. COLOR_0 holds the part colour as
      sRGB bytes (unions without UsePartColor keep per-face colours); node extras hold the Roblox
      Material, Transparency and Shape. A part parented to a part sits under a "ScaleRemoval"
      node that undoes the parent's size.
  Assets.rbxmx: the same ships as Roblox instances, read for what the glTF lacks: attributes
      (HideInside, HideSeated, CockpitOnly, GearRole + joint data, GearClosed, EngineGlow, Glass)
      and Light instances. The model attributes BoundsSize, RootToBoundsCenter and RootToGround
      feed the collision probes (ShipStation.probeFor).

COORDINATES
  glTF units are studs = metres, and the axes are Roblox's: +X right, +Y up, forward -Z. Every
  glTF node matches its rbxmx part to float precision (position, rotation, size), so the
  transform glTF -> Roblox is the identity. The ship's Root part (its PrimaryPart) sits at the
  pilot's eye at the origin. ShipHull places parts at SeatOffset * Root^-1 * part.CFrame with
  ShipConfig.Camera.SeatOffset = (0, 0.5, 0): the ship origin (the flight model's reference
  point and pivot) is 0.5 m below the eye. All output is in that ship space: eye = (0, 0.5, 0).

OUTPUT (all little-endian; str = u8 length + UTF-8 bytes)
  ships/<id>.bin, one per ship (id = lower-case class name):
    char[4] "USES", u16 version (1), u16 chunkCount
    str     id, str name (upper case)
    f32[3]  eye                           pilot's eye, ship space
    f32[6]  boundsMin, boundsMax          every vertex of the ship (ship space)
    f32[3]  rbxBoundsSize, f32[3] rbxRootToBoundsCenter, f32 rbxRootToGround   (model attributes)
    chunkCount x str chunkFile            "" = the chunk is stored inline at the end of this file
    u8 hasDoors, u8 boneCount, then per bone b >= 3 (the first three are fixed, see BONES):
        u8 kind (1 Leg, 2 Foot, 3 Mount, 4 Door, 5 Link), f32[12] pivot (pos, then 3x3 row-major),
        f32 rad, f32 lift, f32 from, f32 swingEnd, f32[3] ankle,
        Link only: f32[3] fixed, f32[3] move, f32[12] base, f32[3] size, u8 axis
    u8 nozzleCount, per nozzle: f32[3] centre, f32 diameter, u8[4] sRGB colour + 0,
        f32 lightBrightness, f32 lightRange
    u8 lightCount, per light on an outside part (groups Hull, Lights, Engines, Canopy, Gear):
        u8 type (0 point, 1 spot, 2 surface), u8 visibility (see CLASSES),
        u8[3] sRGB colour, u8 enabled, f32[3] centre, f32[3] direction, f32 brightness,
        f32 range, f32 angle, f32[3] part size
    inline chunks
  ships/cockpit-<id>.bin, a cockpit shared by several ships (deduplicated by content):
    char[4] "USEC", u16 version (1), u16 0, then one chunk

  Chunk:
    f32[3] origin, f32 scale              position = origin + quantised * scale (uniform scale)
    u32 vertexCount, u32 indexCount, u8 indexSize (2 or 4), u8[3] 0
    u32[2][3] index counts per pass (0 opaque, 1 glass) and class (cockpit only, both, exterior
        only). Indices are stored in that order, so each view draws one contiguous range per pass.
    vertexCount x 16-byte vertex:
        u16 x, y, z     quantised position
        u16 matBone     material id (low byte, see MATERIALS) + 256 * bone (high byte)
        s8  nx, ny, nz, 0   unit normal (snorm8)
        u8  r, g, b, a      sRGB colour, a = opacity (1 - Transparency); on ForceField the
                            strength of its glow (also 1 - Transparency)
    indexCount x u16/u32 (padded to 4 bytes)

  Classes (ShipHull.isHidden): cockpit only = under a CockpitOnly model (pilot's arms and legs),
  exterior only = under HideInside (canopy cage, SeatHidden shells) or HideSeated (interior behind
  the bridge); both = the rest. The exterior view draws both + exterior only, the cockpit view
  cockpit only + both.

  BONES (ShipGear): 0 static, 1 landing gear without joint data (hidden when stowed), 2 GearClosed
  hull doors (shown only when stowed), 3.. one per rigid gear group (same role, hinge, angle, lift,
  liftFrom, ankle: they move as one) and one per Link item (rods that stretch). The game poses
  them like ShipGear.pose, in ship space. At most MAX_BONES (the ship shader's array).

  Passes: glass = every part with 0 < Transparency < 1 (canopy panes, windows, holo emitters,
  translucent Neon) and ForceField; the rest is opaque. Fully transparent parts are not in the glTF.
  Materials: Neon is drawn unlit; parts with the EngineGlow attribute get the "engine" material,
  whose glow follows the throttle (ShipHull.updatePlumes). ForceField (the bridge holograms) is
  see-through even at Transparency 0, as in Roblox: the shader draws it as an added rim glow.

  Left out: the cockpit holo backboards (Cockpit/Holo/*/Back), which ShipInterior shows only
  while the holo displays their text (SurfaceGuis, not part of the glTF).

  The output is deterministic: identical inputs give identical bytes.
"""
import argparse
import base64
import hashlib
import json
import math
import os
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEAT_OFFSET = (0.0, 0.5, 0.0)  # ShipConfig.Camera.SeatOffset
VERSION = 1

# Roblox material (glTF extras, lower case) -> material id. Keep in sync with ShipModel.kt.
MATERIALS = [
    "smoothplastic", "plastic", "metal", "diamondplate", "foil", "glass", "fabric", "rubber",
    "leather", "wood", "slate", "basalt", "rock", "neon", "forcefield", "engine",
]
MAT = {name: i for i, name in enumerate(MATERIALS)}
NEON = MAT["neon"]
FORCEFIELD = MAT["forcefield"]
ENGINE = MAT["engine"]

CLASS_COCKPIT, CLASS_BOTH, CLASS_EXTERIOR = 0, 1, 2
PASS_OPAQUE, PASS_GLASS = 0, 1
BONE_STATIC, BONE_GEAR, BONE_CLOSED = 0, 1, 2
MAX_BONES = 48  # the three fixed bones included; keep in sync with ShipModel.MAX_BONES (the shader's array)
KIND = {"Leg": 1, "Foot": 2, "Mount": 3, "Door": 4, "Link": 5}
PART_CLASSES = {"Part", "WedgePart", "CornerWedgePart", "UnionOperation", "MeshPart", "TrussPart"}
OUTSIDE_GROUPS = {"Hull", "Lights", "Engines", "Canopy", "Gear"}
LIGHT_CLASSES = {"PointLight": 0, "SpotLight": 1, "SurfaceLight": 2}


# ---------------------------------------------------------------------------- small math

def mat_mul(a, b):
    """4x4 (row-major nested lists)."""
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def quat_mat(q):
    x, y, z, w = q
    return [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]


def node_matrix(n):
    t = n.get("translation", [0.0, 0.0, 0.0])
    r = quat_mat(n.get("rotation", [0.0, 0.0, 0.0, 1.0]))
    s = n.get("scale", [1.0, 1.0, 1.0])
    m = [[r[i][j] * s[j] for j in range(3)] + [t[i]] for i in range(3)]
    m.append([0.0, 0.0, 0.0, 1.0])
    return m


def cframe_matrix(cf):
    """Roblox CFrame (x, y, z, R00..R22 row-major) -> 4x4."""
    x, y, z = cf[0:3]
    r = cf[3:12]
    return [[r[0], r[1], r[2], x], [r[3], r[4], r[5], y], [r[6], r[7], r[8], z], [0.0, 0.0, 0.0, 1.0]]


def rigid_inverse(m):
    r = [[m[j][i] for j in range(3)] for i in range(3)]  # transpose
    t = [-(r[i][0] * m[0][3] + r[i][1] * m[1][3] + r[i][2] * m[2][3]) for i in range(3)]
    return [r[0] + [t[0]], r[1] + [t[1]], r[2] + [t[2]], [0.0, 0.0, 0.0, 1.0]]


def inv3(m):
    a, b, c = m[0][0], m[0][1], m[0][2]
    d, e, f = m[1][0], m[1][1], m[1][2]
    g, h, i = m[2][0], m[2][1], m[2][2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    k = 1.0 / det
    return [[(e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k],
            [(f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k],
            [(d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k]], det


def f32(x):
    """Rounds to float32 (so stored metadata and the hash see exactly what the game reads)."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


# ---------------------------------------------------------------------------- glTF

class Gltf:
    def __init__(self, path):
        with open(path, "r", encoding="utf-8") as f:
            self.doc = json.load(f)
        self.nodes = self.doc["nodes"]
        self._buffers = {}
        self._accessors = {}

    def _buffer(self, i):
        if i not in self._buffers:
            uri = self.doc["buffers"][i]["uri"]
            if not uri.startswith("data:"):
                raise ValueError("external glTF buffers are not supported: %s" % uri[:40])
            self._buffers[i] = base64.b64decode(uri.split(",", 1)[1])
        return self._buffers[i]

    def accessor(self, i):
        if i in self._accessors:
            return self._accessors[i]
        a = self.doc["accessors"][i]
        view = self.doc["bufferViews"][a["bufferView"]]
        data = self._buffer(view["buffer"])
        off = view.get("byteOffset", 0) + a.get("byteOffset", 0)
        comps = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
        fmt = {5126: "f", 5125: "I", 5123: "H", 5121: "B"}[a["componentType"]]
        size = struct.calcsize(fmt)
        stride = view.get("byteStride", 0) or comps * size
        vals = []
        for k in range(a["count"]):
            vals.append(struct.unpack_from("<%d%s" % (comps, fmt), data, off + k * stride))
        self._accessors[i] = vals
        return vals

    def ship_roots(self):
        return {self.nodes[i]["name"]: i for i in self.doc["scenes"][0]["nodes"]}

    def parts(self, root):
        """(node, matrix in model space, path names) for every mesh node under [root], in file order."""
        out = []

        def walk(i, parent, path):
            n = self.nodes[i]
            m = mat_mul(parent, node_matrix(n))
            name = n.get("name", "")
            sub = path if name == "ScaleRemoval" else path + [name]
            if "mesh" in n:
                out.append((n, m, sub))
            for c in n.get("children", []):
                walk(c, m, sub)

        walk(root, [[1.0, 0, 0, 0], [0, 1.0, 0, 0], [0, 0, 1.0, 0], [0, 0, 0, 1.0]], [])
        return out


# ---------------------------------------------------------------------------- rbxmx

ROT_IDS = {}


def _rot_ids():
    vecs = [(1, 0, 0), (0, 1, 0), (0, 0, 1), (-1, 0, 0), (0, -1, 0), (0, 0, -1)]
    for i, c0 in enumerate(vecs):
        for j, c1 in enumerate(vecs):
            if sum(a * b for a, b in zip(c0, c1)) != 0:
                continue
            c2 = (c0[1] * c1[2] - c0[2] * c1[1], c0[2] * c1[0] - c0[0] * c1[2], c0[0] * c1[1] - c0[1] * c1[0])
            ROT_IDS[6 * i + j + 1] = (c0[0], c1[0], c2[0], c0[1], c1[1], c2[1], c0[2], c1[2], c2[2])


_rot_ids()


def decode_attributes(b64):
    """Roblox AttributesSerialize: u32 count, then (string key, u8 type, value) entries."""
    if not b64:
        return {}
    data = base64.b64decode(b64)
    off = 0

    def take(fmt):
        nonlocal off
        v = struct.unpack_from("<" + fmt, data, off)
        off += struct.calcsize("<" + fmt)
        return v

    def string():
        nonlocal off
        (n,) = take("I")
        s = data[off:off + n]
        off += n
        return s.decode("utf-8", "replace")

    out = {}
    (count,) = take("I")
    for _ in range(count):
        key = string()
        t = data[off]
        off += 1
        if t == 0x02:
            v = string()
        elif t == 0x03:
            v = bool(data[off])
            off += 1
        elif t == 0x05:
            v = take("f")[0]
        elif t == 0x06:
            v = take("d")[0]
        elif t == 0x09:
            v = ("UDim",) + take("fi")
        elif t == 0x0A:
            v = ("UDim2",) + take("fifi")
        elif t == 0x0E:
            v = ("BrickColor",) + take("I")
        elif t == 0x0F:
            v = ("Color3",) + take("3f")
        elif t == 0x10:
            v = ("Vector2",) + take("2f")
        elif t == 0x11:
            v = ("Vector3",) + take("3f")
        elif t == 0x14:
            p = take("3f")
            rid = data[off]
            off += 1
            r = take("9f") if rid == 0 else ROT_IDS[rid]
            v = ("CFrame",) + tuple(p) + tuple(r)
        elif t == 0x15:
            v = ("Enum", string()) + take("I")
        elif t == 0x17:
            (n,) = take("I")
            v = ("NumberSequence", [take("3f") for _ in range(n)])
        elif t == 0x19:
            (n,) = take("I")
            v = ("ColorSequence", [take("5f") for _ in range(n)])
        elif t == 0x1B:
            v = ("NumberRange",) + take("2f")
        elif t == 0x1C:
            v = ("Rect",) + take("4f")
        elif t == 0x21:
            w, st = take("HB")
            v = ("Font", w, st, string(), string())
        else:
            raise ValueError("unknown attribute type 0x%02x (%s)" % (t, key))
        out[key] = v
    return out


KEEP_PROPS = {"Name", "CFrame", "size", "Color3uint8", "Material", "Transparency", "shape", "Color",
              "Brightness", "Range", "Angle", "Face", "Enabled", "PrimaryPart", "AttributesSerialize"}


def _prop(el):
    tag = el.tag
    if tag == "CoordinateFrame":
        d = {c.tag: float(c.text) for c in el}
        return [d[k] for k in ("X", "Y", "Z", "R00", "R01", "R02", "R10", "R11", "R12", "R20", "R21", "R22")]
    if tag == "Vector3":
        d = {c.tag: float(c.text) for c in el}
        return [d["X"], d["Y"], d["Z"]]
    if tag == "Color3":
        d = {c.tag: float(c.text) for c in el}
        return [d["R"], d["G"], d["B"]]
    if tag == "Color3uint8":
        v = int(el.text)
        return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
    if tag in ("float", "double"):
        return float(el.text)
    if tag in ("int", "token", "int64"):
        return int(el.text or 0)
    if tag == "bool":
        return el.text == "true"
    return el.text or ""


def read_ships_rbxmx(stream):
    """Stream-parses the Roblox XML; returns the Assets/Ships folder as nested dicts."""
    stack = []
    ships = None
    for ev, el in ET.iterparse(stream, events=("start", "end")):
        if ev == "start":
            if el.tag == "Item":
                parent = stack[-1] if stack else None
                keep = parent is None or parent["keep"] is not False
                node = {"class": el.get("class"), "ref": el.get("referent"), "props": {}, "children": [],
                        "keep": None if keep else False}
                if parent is not None and parent["keep"]:
                    parent["children"].append(node)
                stack.append(node)
            continue
        if el.tag == "Properties" and stack:
            node = stack[-1]
            if node["keep"] is None:
                for p in el:
                    name = p.get("name")
                    if name in KEEP_PROPS:
                        node["props"][name] = _prop(p)
                depth = len(stack) - 1
                name = node["props"].get("Name")
                node["keep"] = depth != 1 or name == "Ships"
                if not node["keep"]:
                    node["props"] = {}
                else:
                    node["name"] = name
                    node["attrs"] = decode_attributes(node["props"].pop("AttributesSerialize", ""))
                if depth == 1 and name == "Ships":
                    ships = node
            el.clear()
        elif el.tag == "Item":
            stack.pop()
            el.clear()
    if ships is None:
        raise ValueError("no Assets/Ships folder in the rbxmx")
    return ships


# ---------------------------------------------------------------------------- per ship

class Part:
    """One glTF mesh node joined with its rbxmx part."""

    def __init__(self, node, matrix, path, rbx, flags):
        self.node = node
        self.matrix = matrix  # model space (Root space before the seat offset)
        self.path = path
        self.rbx = rbx
        self.flags = flags


def collect_rbx_parts(ship):
    """path tuple -> list of (rbx part, inherited flags, gear?) for every BasePart of the ship."""
    out = {}
    lights = []

    def walk(n, path, flags):
        a = n.get("attrs", {})
        f = dict(flags)
        for key in ("HideInside", "HideSeated", "CockpitOnly"):
            if a.get(key) is True:
                f[key] = True
        p = path + (n["name"],)
        if n["class"] in PART_CLASSES:
            out.setdefault(p, []).append((n, f))
            for c in n["children"]:
                if c["class"] in LIGHT_CLASSES:
                    lights.append((c, n, f, p))
        for c in n["children"]:
            walk(c, p, f)

    for c in ship["children"]:
        walk(c, (ship["name"],), {"group": c["name"]})
    return out, lights


def match_parts(gltf, root, ship_rbx):
    rbx_parts, rbx_lights = collect_rbx_parts(ship_rbx)
    used = set()
    parts = []
    worst = 0.0
    for node, m, path in gltf.parts(root):
        key = tuple(path)
        cands = rbx_parts.get(key)
        if not cands:
            raise ValueError("glTF part %s has no rbxmx part" % "/".join(path))
        best = None
        best_cost = None
        for idx, (rp, flags) in enumerate(cands):
            if (key, idx) in used:
                continue
            cf = rp["props"]["CFrame"]
            size = rp["props"]["size"]
            sx = [math.sqrt(m[0][j] ** 2 + m[1][j] ** 2 + m[2][j] ** 2) for j in range(3)]
            cost = sum(abs(m[i][3] - cf[i]) for i in range(3))
            cost += sum(abs(m[i][j] / (sx[j] or 1.0) - cf[3 + 3 * i + j]) for i in range(3) for j in range(3))
            cost += sum(abs(sx[j] - size[j]) for j in range(3))
            if best_cost is None or cost < best_cost:
                best, best_cost = idx, cost
        if best is None:
            raise ValueError("glTF part %s: every rbxmx part of that path is taken" % "/".join(path))
        used.add((key, best))
        worst = max(worst, best_cost)
        rp, flags = cands[best]
        parts.append(Part(node, m, path, rp, flags))
    if worst > 0.01:
        raise ValueError("glTF and rbxmx disagree by %.4f (positions/rotations/sizes)" % worst)
    return parts, rbx_lights, worst


def part_class(flags):
    if flags.get("CockpitOnly"):
        return CLASS_COCKPIT
    if flags.get("HideInside") or flags.get("HideSeated"):
        return CLASS_EXTERIOR
    return CLASS_BOTH


def lua_num(v):
    """tostring() of a Lua number (%.14g), used for ShipGear's group keys."""
    if isinstance(v, bool):
        return str(v).lower()
    if isinstance(v, (int, float)):
        r = "%.14g" % v
        return r
    if isinstance(v, tuple):
        return ",".join(lua_num(x) for x in v[1:])
    return "nil"


def to_ship(v):
    return (v[0] + SEAT_OFFSET[0], v[1] + SEAT_OFFSET[1], v[2] + SEAT_OFFSET[2])


def build_rig(parts, root_inv):
    """ShipGear.build: rigid groups and Link items -> bone index per part, bone records."""
    groups = {}
    bones = []  # records for bones 3..
    bone_of = {}
    doors = False
    for i, p in enumerate(parts):
        if p.flags.get("group") != "Gear":
            continue
        a = p.rbx.get("attrs", {})
        role = a.get("GearRole")
        hinge = deg = lift = frm = ankle = fixed = move = None
        if role == "Door":
            hinge, deg = a.get("DoorHinge"), a.get("DoorSwingDeg")
        elif role in ("Leg", "Foot", "Mount", "Link"):
            hinge, deg = a.get("Hinge"), a.get("SwingDeg")
            lift, frm = a.get("GearLift"), a.get("GearLiftFrom")
            if role == "Foot":
                ankle = a.get("GearAnkle")
                if not (isinstance(ankle, tuple) and ankle[0] == "Vector3"):
                    role = "Leg"
                    ankle = None
            elif role == "Link":
                fixed, move = a.get("GearLinkFixed"), a.get("GearLinkMove")
                ok = isinstance(fixed, tuple) and fixed[0] == "Vector3" and isinstance(move, tuple) and move[0] == "Vector3"
                if not ok or math.dist(fixed[1:], move[1:]) < 1e-3:
                    role = "Leg"
            elif role == "Mount":
                deg = 0.0
        if not (isinstance(hinge, tuple) and hinge[0] == "CFrame") or not isinstance(deg, (int, float)) or isinstance(deg, bool):
            bone_of[i] = BONE_GEAR  # in Gear, no joint data (e.g. GearRole Bay): stays, hides when stowed
            continue
        lift = float(lift) if isinstance(lift, (int, float)) and not isinstance(lift, bool) else 0.0
        if not isinstance(frm, (int, float)) or isinstance(frm, bool):
            frm = 0.7 if (deg != 0 and lift != 0) else 0.0
        frm = min(max(float(frm), 0.0), 0.95)
        swing_end = frm if (lift != 0 and frm > 0) else 1.0
        if role == "Door":
            doors = True
        # pivot and points in ship space (the seat offset is a pure translation)
        hcf = list(hinge[1:])
        pivot = list(to_ship(hcf[0:3])) + hcf[3:12]
        rec = {"kind": KIND[role], "pivot": pivot, "rad": math.radians(deg), "lift": lift, "from": frm,
               "swingEnd": swing_end, "ankle": list(to_ship(ankle[1:])) if ankle else [0.0, 0.0, 0.0]}
        if role == "Link":
            fx, mv = to_ship(fixed[1:]), to_ship(move[1:])
            d = [mv[k] - fx[k] for k in range(3)]
            ln = math.sqrt(sum(x * x for x in d))
            d = [x / ln for x in d]
            cf = p.rbx["props"]["CFrame"]
            base = list(to_ship(cf[0:3])) + list(cf[3:12])
            # the part's long axis: the one of Right/Up/Look best aligned with the rod
            axes = [(cf[3], cf[6], cf[9]), (cf[4], cf[7], cf[10]), (cf[5], cf[8], cf[11])]
            best, best_dot = 0, -1.0
            for k, ax in enumerate(axes):
                dd = abs(sum(ax[j] * d[j] for j in range(3)))
                if dd > best_dot:
                    best, best_dot = k, dd
            rec.update({"fixed": list(fx), "move": list(mv), "base": base,
                        "size": list(p.rbx["props"]["size"]), "axis": best})
            bones.append(rec)
            bone_of[i] = 2 + len(bones)
            continue
        key = (role, lua_num(hinge), lua_num(deg), lua_num(lift), lua_num(frm), lua_num(ankle))
        if key not in groups:
            bones.append(rec)
            groups[key] = 2 + len(bones)
        bone_of[i] = groups[key]
    if 3 + len(bones) > MAX_BONES:
        raise ValueError("%d gear bones: the ship shader holds %d (ShipModel.MAX_BONES)" % (3 + len(bones), MAX_BONES))
    for i, p in enumerate(parts):
        if p.rbx.get("attrs", {}).get("GearClosed") is True:
            bone_of[i] = BONE_CLOSED
    return bone_of, bones, doors


def is_holo_back(p):
    """The dark backboard of a cockpit holo display (Cockpit/Holo/HoloLeft/Back): ShipInterior shows
    it only while the holo shows its text rows (SurfaceGui), so without those it stays off."""
    return p.flags.get("group") == "Cockpit" and len(p.path) >= 2 and p.path[-1] == "Back" and p.path[-2].startswith("Holo")


def part_material(p):
    e = p.node.get("extras", {})
    a = p.rbx.get("attrs", {})
    if a.get("EngineGlow") is True:
        return ENGINE
    name = (e.get("Material") or "").lower()
    if name not in MAT:
        raise ValueError("unmapped Roblox material %r on %s" % (name, "/".join(p.path)))
    return MAT[name]


def build_vertices(gltf, part, seat_root, bone, mat):
    """Baked ship-space vertices (pos, normal, rgba) and triangles (local indices) of one part."""
    m = mat_mul(seat_root, part.matrix)
    lin = [[m[i][j] for j in range(3)] for i in range(3)]
    inv, det = inv3(lin)
    nm = [[inv[j][i] for j in range(3)] for i in range(3)]  # inverse transpose
    prim = gltf.doc["meshes"][part.node["mesh"]]["primitives"][0]
    attrs = prim["attributes"]
    pos = gltf.accessor(attrs["POSITION"])
    nor = gltf.accessor(attrs["NORMAL"])
    col = gltf.accessor(attrs["COLOR_0"])
    idx = gltf.accessor(prim["indices"])
    t = part.node.get("extras", {}).get("Transparency", 0.0) or 0.0
    alpha = int(math.floor((1.0 - t) * 255.0 + 0.5))
    verts = []
    for k in range(len(pos)):
        p = pos[k]
        n = nor[k]
        x = m[0][0] * p[0] + m[0][1] * p[1] + m[0][2] * p[2] + m[0][3]
        y = m[1][0] * p[0] + m[1][1] * p[1] + m[1][2] * p[2] + m[1][3]
        z = m[2][0] * p[0] + m[2][1] * p[1] + m[2][2] * p[2] + m[2][3]
        nx = nm[0][0] * n[0] + nm[0][1] * n[1] + nm[0][2] * n[2]
        ny = nm[1][0] * n[0] + nm[1][1] * n[1] + nm[1][2] * n[2]
        nz = nm[2][0] * n[0] + nm[2][1] * n[1] + nm[2][2] * n[2]
        ln = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
        c = col[k]
        verts.append((x, y, z, nx / ln, ny / ln, nz / ln, c[0], c[1], c[2], alpha, mat, bone))
    tris = []
    for k in range(0, len(idx), 3):
        a, b, c = idx[k][0], idx[k + 1][0], idx[k + 2][0]
        tris.append((a, c, b) if det < 0 else (a, b, c))
    return verts, tris


def snorm8(v):
    return max(-127, min(127, int(math.floor(v * 127.0 + 0.5))))


def encode_chunk(parts_data):
    """parts_data: list of (verts, tris, pass, class). Returns chunk bytes, stats."""
    vertices = []
    lists = {(ps, cl): [] for ps in (PASS_OPAQUE, PASS_GLASS) for cl in (CLASS_COCKPIT, CLASS_BOTH, CLASS_EXTERIOR)}
    for verts, tris, ps, cl in parts_data:
        base = len(vertices)
        vertices.extend(verts)
        dst = lists[(ps, cl)]
        for a, b, c in tris:
            dst.append(base + a)
            dst.append(base + b)
            dst.append(base + c)
    lo = [min(v[k] for v in vertices) for k in range(3)]
    hi = [max(v[k] for v in vertices) for k in range(3)]
    origin = [f32(x) for x in lo]
    extent = max(hi[k] - origin[k] for k in range(3))
    scale = f32(extent / 65535.0 * (1.0 + 1e-6))
    out = bytearray()
    out += struct.pack("<3ff", origin[0], origin[1], origin[2], scale)
    count = sum(len(v) for v in lists.values())
    isize = 2 if len(vertices) <= 65536 else 4
    out += struct.pack("<IIB3x", len(vertices), count, isize)
    for ps in (PASS_OPAQUE, PASS_GLASS):
        for cl in (CLASS_COCKPIT, CLASS_BOTH, CLASS_EXTERIOR):
            out += struct.pack("<I", len(lists[(ps, cl)]))
    vb = bytearray()
    for v in vertices:
        q = [int(math.floor((v[k] - origin[k]) / scale + 0.5)) for k in range(3)]
        if min(q) < 0 or max(q) > 65535:
            raise ValueError("quantisation out of range")
        vb += struct.pack("<4H4b4B", q[0], q[1], q[2], v[10] | (v[11] << 8),
                          snorm8(v[3]), snorm8(v[4]), snorm8(v[5]), 0, v[6], v[7], v[8], v[9])
    out += vb
    ib = bytearray()
    fmt = "<H" if isize == 2 else "<I"
    for ps in (PASS_OPAQUE, PASS_GLASS):
        for cl in (CLASS_COCKPIT, CLASS_BOTH, CLASS_EXTERIOR):
            for i in lists[(ps, cl)]:
                ib += struct.pack(fmt, i)
    while len(ib) % 4:
        ib += b"\0"
    out += ib
    bounds = (lo, hi)
    stats = {"vertices": len(vertices), "triangles": count // 3, "indexSize": isize,
             "ranges": {"%s/%s" % (("opaque", "glass")[ps], ("cockpit", "both", "exterior")[cl]): len(lists[(ps, cl)]) // 3
                        for (ps, cl) in lists}}
    return bytes(out), bounds, stats


def pstr(s):
    b = s.encode("utf-8")
    return struct.pack("<B", len(b)) + b


def convert_ship(gltf, root, ship_rbx):
    name = ship_rbx["name"]
    parts, rbx_lights, worst = match_parts(gltf, root, ship_rbx)
    # Root (PrimaryPart) -> seat: ship space = SeatOffset * Root^-1 * model space
    prim_ref = ship_rbx["props"].get("PrimaryPart")
    root_part = None

    def find(n):
        nonlocal root_part
        if n["ref"] == prim_ref:
            root_part = n
        for c in n["children"]:
            find(c)

    find(ship_rbx)
    if root_part is None:
        raise ValueError("%s: no PrimaryPart" % name)
    root_m = cframe_matrix(root_part["props"]["CFrame"])
    seat = [[1.0, 0, 0, SEAT_OFFSET[0]], [0, 1.0, 0, SEAT_OFFSET[1]], [0, 0, 1.0, SEAT_OFFSET[2]], [0, 0, 0, 1.0]]
    seat_root = mat_mul(seat, rigid_inverse(root_m))
    if any(abs(root_m[i][j] - (1.0 if i == j else 0.0)) > 1e-6 for i in range(3) for j in range(4)):
        print("  note: %s Root is not at the origin; rig data assumes it is" % name)
    bone_of, bones, doors = build_rig(parts, rigid_inverse(root_m))

    chunks = {"ship": [], "cockpit": []}
    nozzles = []
    skipped = 0
    for i, p in enumerate(parts):
        if is_holo_back(p):
            skipped += 1
            continue
        mat = part_material(p)
        bone = bone_of.get(i, BONE_STATIC)
        verts, tris = build_vertices(gltf, p, seat_root, bone, mat)
        t = p.node.get("extras", {}).get("Transparency", 0.0) or 0.0
        ps = PASS_GLASS if (t > 0.001 or mat == FORCEFIELD) else PASS_OPAQUE
        cl = part_class(p.flags)
        chunk = "cockpit" if p.flags["group"] == "Cockpit" else "ship"
        chunks[chunk].append((verts, tris, ps, cl))
        if mat == ENGINE:
            cf = p.rbx["props"]["CFrame"]
            size = p.rbx["props"]["size"]
            c = p.rbx["props"]["Color3uint8"]
            light = next((ch for ch in p.rbx["children"] if ch["class"] == "PointLight"), None)
            nozzles.append({"pos": to_ship(cf[0:3]), "diameter": max(size[1], size[2]), "color": c,
                            "brightness": light["props"].get("Brightness", 0.0) if light else 0.0,
                            "range": light["props"].get("Range", 0.0) if light else 0.0})
    lights = []
    for lt, part, flags, path in rbx_lights:
        # outside lights only: the cockpit and the rooms inside the hull light themselves, and the
        # engine discs' lights belong to their nozzle
        if flags["group"] not in OUTSIDE_GROUPS or part.get("attrs", {}).get("EngineGlow") is True:
            continue
        pr = lt["props"]
        cf = part["props"]["CFrame"]
        face = pr.get("Face", 5)
        # NormalId: Right, Top, Back, Left, Bottom, Front -> the part's local axis
        axis = [(0, 1), (1, 1), (2, 1), (0, -1), (1, -1), (2, -1)][face]
        d = [cf[3 + axis[0]] * axis[1], cf[6 + axis[0]] * axis[1], cf[9 + axis[0]] * axis[1]]
        col = pr.get("Color", [1.0, 1.0, 1.0])
        lights.append({"type": LIGHT_CLASSES[lt["class"]], "class": part_class(flags),
                       "color": [int(math.floor(min(max(x, 0.0), 1.0) * 255 + 0.5)) for x in col],
                       "enabled": 1 if pr.get("Enabled", True) else 0, "pos": to_ship(cf[0:3]), "dir": d,
                       "brightness": pr.get("Brightness", 1.0), "range": pr.get("Range", 0.0),
                       "angle": pr.get("Angle", 0.0) if lt["class"] != "PointLight" else 0.0,
                       "size": part["props"]["size"]})
    return parts, chunks, bones, doors, nozzles, lights, worst, skipped


def write_assets(gltf_path, rbx_stream, out_dir):
    gltf = Gltf(gltf_path)
    print("reading the rbxmx (streamed) ...")
    ships_rbx = read_ships_rbxmx(rbx_stream)
    roots = gltf.ship_roots()
    os.makedirs(out_dir, exist_ok=True)
    shared = {}  # cockpit chunk hash -> file name
    report = {}
    written = []
    for ship in sorted(ships_rbx["children"], key=lambda s: s["name"]):
        name = ship["name"]
        if name not in roots:
            raise ValueError("ship %s is not in the glTF" % name)
        sid = name.lower()
        parts, chunks, bones, doors, nozzles, lights, worst, skipped = convert_ship(gltf, roots[name], ship)
        ship_chunk, ship_bounds, ship_stats = encode_chunk(chunks["ship"])
        cp_chunk, cp_bounds, cp_stats = encode_chunk(chunks["cockpit"])
        digest = hashlib.sha1(cp_chunk).hexdigest()
        if digest not in shared:
            fname = "cockpit-%s.bin" % sid
            shared[digest] = fname
            with open(os.path.join(out_dir, fname), "wb") as f:
                f.write(b"USEC" + struct.pack("<HH", VERSION, 0) + cp_chunk)
            written.append(fname)
        cockpit_file = shared[digest]
        lo = [min(ship_bounds[0][k], cp_bounds[0][k]) for k in range(3)]
        hi = [max(ship_bounds[1][k], cp_bounds[1][k]) for k in range(3)]
        a = ship.get("attrs", {})
        bsize = a.get("BoundsSize", ("Vector3", 0.0, 0.0, 0.0))[1:]
        bcentre = a.get("RootToBoundsCenter", ("Vector3", 0.0, 0.0, 0.0))[1:]
        ground = a.get("RootToGround", 0.0)
        out = bytearray(b"USES")
        out += struct.pack("<HH", VERSION, 2)
        out += pstr(sid) + pstr(name.upper())
        out += struct.pack("<3f", *SEAT_OFFSET)
        out += struct.pack("<6f", *(lo + hi))
        out += struct.pack("<7f", *(list(bsize) + list(bcentre) + [ground]))
        out += pstr("") + pstr(cockpit_file)
        out += struct.pack("<BB", 1 if doors else 0, 3 + len(bones))
        for b in bones:
            out += struct.pack("<B12f4f3f", b["kind"], *b["pivot"], b["rad"], b["lift"], b["from"], b["swingEnd"], *b["ankle"])
            if b["kind"] == KIND["Link"]:
                out += struct.pack("<3f3f12f3fB", *b["fixed"], *b["move"], *b["base"], *b["size"], b["axis"])
        out += struct.pack("<B", len(nozzles))
        for n in nozzles:
            out += struct.pack("<4f4B2f", *n["pos"], n["diameter"], n["color"][0], n["color"][1], n["color"][2], 0,
                               n["brightness"], n["range"])
        out += struct.pack("<B", len(lights))
        for lt in lights:
            out += struct.pack("<BB3BB3f3f3f3f", lt["type"], lt["class"], *lt["color"], lt["enabled"], *lt["pos"],
                               *lt["dir"], lt["brightness"], lt["range"], lt["angle"], *lt["size"])
        out += ship_chunk
        fname = "%s.bin" % sid
        with open(os.path.join(out_dir, fname), "wb") as f:
            f.write(out)
        written.append(fname)
        report[name] = {
            "parts": len(parts) - skipped, "leftOut": skipped, "matchError": worst, "bones": 3 + len(bones), "doors": doors,
            "nozzles": len(nozzles), "lights": len(lights), "file": fname, "bytes": len(out),
            "cockpitFile": cockpit_file, "ship": ship_stats, "cockpit": cp_stats,
            "bounds": [[round(x, 3) for x in lo], [round(x, 3) for x in hi]],
        }
        print("%-9s %5d parts, %6d + %6d vertices, %6d + %6d triangles, %2d bones, %d nozzles, %2d lights, %8d B (+ %s)"
              % (name, len(parts) - skipped, ship_stats["vertices"], cp_stats["vertices"], ship_stats["triangles"],
                 cp_stats["triangles"], 3 + len(bones), len(nozzles), len(lights), len(out), cockpit_file))
    total = sum(os.path.getsize(os.path.join(out_dir, f)) for f in written)
    print("%d files, %d bytes" % (len(written), total))
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--gltf", default=os.path.join(ROOT, "roblox", "source", "Ships.gltf"))
    ap.add_argument("--rbxmx", help="Assets.rbxmx (default: stream it out of --rar with bsdtar)")
    ap.add_argument("--rar", default=os.path.join(ROOT, "roblox", "source", "Assets.rar"))
    ap.add_argument("--out", default=os.path.join(ROOT, "app", "src", "main", "assets", "ships"))
    ap.add_argument("--report", help="write per-ship statistics as JSON")
    args = ap.parse_args()
    if args.rbxmx:
        with open(args.rbxmx, "rb") as stream:
            report = write_assets(args.gltf, stream, args.out)
    else:
        proc = subprocess.Popen(["bsdtar", "-xOf", args.rar, "Assets.rbxmx"], stdout=subprocess.PIPE)
        try:
            report = write_assets(args.gltf, proc.stdout, args.out)
        finally:
            proc.stdout.close()
            if proc.wait() != 0:
                sys.exit("bsdtar failed to read %s" % args.rar)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1, sort_keys=True)


if __name__ == "__main__":
    main()
