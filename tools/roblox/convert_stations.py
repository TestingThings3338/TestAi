#!/usr/bin/env python3
"""Converts the six Roblox space stations and the construction site into the game's binary station assets.

Usage (from the repository root):

    python3 tools/roblox/convert_stations.py [--rbxmx Assets.rbxmx | --rar roblox/source/Assets.rar]
        [--out app/src/main/assets/stations] [--report report.json]

Python 3 standard library only. Without --rbxmx the Roblox XML is streamed straight out of
roblox/source/Assets.rar with `bsdtar -xOf` (219 MB, never written to disk or loaded whole).

INPUT
  Assets/Stations/{RingStation, GardenStation, FoundryStation, SpireStation, HollowStation,
  RefineryStation} and Assets/Construction/Site. Each is a Model whose PrimaryPart "Root" sits at the
  identity; everything below is expressed in that root space (Roblox axes: +X right, +Y up, the
  station's "look" -Z points out of the hangar mouth; 1 stud = 1 m). Read per instance:
    BaseParts (Part shape Ball/Block/Cylinder, WedgePart, CornerWedgePart): CFrame, Size, Color,
      Material, Transparency, attributes (Collider, Blink, BlinkPhase, BlinkDuty, Papi, Accent,
      PadIndex), a SpecialMesh Sphere child (an ellipsoid filling the box), lights;
    SurfaceGuis ("paint"): Face, PixelsPerStud, LightInfluence, Brightness, ZOffset, with Frames
      (Size/Position UDim2, AnchorPoint, Rotation, ZIndex, background colour and transparency,
      ClipsDescendants, UICorner, UIStroke, UIGradient) and TextLabels (signs);
    Models with SpinDegreesPerSecond (+ SpinAxis, SpinCentre) and the Model "Ring" (2 deg/s about Z):
      rotating groups; the site's Stage1..Stage4 models: build stages;
    Pads/PadN (PadIndex, Lights), Approach/{Outer, Mouth, Inner}, Sign, model attributes (Radius,
      HangarSize, HangarCentre, MouthSize, PartCount), Point/Spot/SurfaceLights, the Spire's Beam.
  The rbxmx stores every UTF-8 byte above 127 as its own character reference ("·" is &#194;&#183;):
  texts are re-encoded as Latin-1 and decoded as UTF-8.

SURFACEGUI FACES (canvas axes in part space; verified on the data: offset-placed frames fit only
  with these extents, and floor texts then read the same way for pilots entering the hangar)
  Right (+X): right -Z, down -Y    Left (-X): right +Z, down -Y    Back (+Z): right +X, down -Y
  Front (-Z): right -X, down -Y    Top (+Y): right -Z, down +X     Bottom (-Y): right -Z, down -X
  The canvas is the face (studs) times PixelsPerStud. A GUI draws even on an invisible part.

OUTPUT  stations/<Type>.stm, one per model (all little-endian; str = u8 length + UTF-8 bytes):
    char[4] "USTM", u16 version (2), u16 flags (bit 0: construction site, groups are build stages)
    str     type ("RingStation"...), str builtBy
    f32     radius (attribute Radius)
    f32[3]  hangarSize, f32[3] hangarCentre, f32[3] mouthSize (0 when the model has none)
    f32[6]  bounds min xyz, max xyz of every rendered vertex
    u8      hasApproach, f32[9] approach points Outer, Mouth, Inner
    u8      groupCount G (group 0 = the static hull; stations: spin groups, site: Stage1..4)
            per group 1..G-1: str name, f32 degrees per second, f32[3] axis (unit), f32[3] centre
    u8      padCount, per pad: u8 index, u8 lamp slot, f32[3] size, f32[12] top (CFrame of the pad's
            top face: position, then the 3x3 rotation row-major as Roblox's R00..R22)
    u8      lampCount L (slots 1..L, L <= 63, the game's uniform table; slot 0 = none), per slot:
              u8 kind (0 accent, 1 pad, 2 PAPI, 3 blinker), u8 pass (0 opaque, 2 lamp), u8 pad index,
              u8 0, u8[4] sRGB colour + opacity (as built), f32 period, f32 phase (-1 = not set:
              the game picks one), f32 duty, f32 PAPI threshold (radians), f32[3] PAPI position,
              u16 parts. Blinkers share a slot only when period, phase, duty and transparency agree;
              a blinker without BlinkPhase gets a slot of its own, since Roblox gives each such part
              its own random phase (ShipStation.readModel).
    u16     colliderCount, per collider: u8 shape (0 block, 1 ball, 2 cylinder (axis X), 3 wedge,
            4 corner wedge), u8 group, f32[12] CFrame, f32[3] size
    u8      lightCount, per light: u8 type (0 point, 1 spot, 2 surface), u8 group, u8[3] sRGB colour,
            u8 shadows, f32 brightness, f32 range, f32 angle (degrees), f32[3] position,
            f32[3] direction (spot and surface lights: the face normal), f32[2] surface size
    u16     textCount, per label: str text, u8 font (0 UI, 1 TITLE, 2 MONO: the game's atlas faces),
            u8 flags (1 station name, 2 pad number, 4 TextScaled, 8 TextWrapped), u8 xAlign (0 left,
            1 right, 2 centre), u8 yAlign (0 top, 1 centre, 2 bottom), u8 group, u8 layer (decal
            depth layer), u8 pad, u8 0, u8[4] sRGB colour + opacity, f32 lightInfluence,
            f32 brightness, f32 textSize (px), f32 px per metre, f32[3] origin (top-left corner),
            f32[3] u (along the text's x, length = width m), f32[3] v (down, length = height m),
            f32[3] normal
    u8      beamCount, per beam: u8 group, f32[3] p0, f32[3] p1, f32 width0, f32 width1,
            u8[3] sRGB colour, u8 0, f32[3] transparency at 0, 0.6 and 1 along the beam
    mesh:   f32[3] origin, f32 step (metres per quantum: position = origin + q * step, q < 2^19)
            u32 vertexCount, u32 indexCount, u8 indexSize (2 or 4), u8[3] 0
            u32 counts[4 passes][3 LOD classes][G groups]: index counts, stored in that order
            the vertices as 16 planes of vertexCount bytes (the loader interleaves them into the GPU's
            16-byte vertex below; planes with deltas deflate to a third of the interleaved bytes):
              x lo, x hi, y lo, y hi, z lo, z hi  (each word stored as the difference to the previous
                vertex's, mod 65536), w lo, w hi, nx, ny, nz, slot, r, g, b, a
            then the indices as indexSize planes of indexCount bytes (low byte first), each index
            stored as the difference to the previous one (mod 2^bits); padded to 4 bytes.
            GPU vertex (16 bytes):
              u16 x, y, z  the top 16 bits of the 19-bit quantised position
              u16 w        bits 0-2/3-5/6-8: the low 3 bits of x/y/z, bits 9-12 material (MATERIALS),
                           bits 13-15 group
              s8  nx, ny, nz  unit normal (snorm8)
              u8  slot     lamp slot (0 = none); on paint (materials PAINT1..6): emission packed as
                           low nibble = LightInfluence * 15, high nibble = round((1 - LI) * Brightness / 0.15)
              u8  r, g, b, a  sRGB colour, a = opacity (1 - Transparency)

  Passes: 0 opaque (with opaque paint, accent and PAPI lamps), 1 translucent (0 < Transparency < 1,
  glass, ForceField), 2 lamps (blinkers and pad lamps, blended: their opacity changes at runtime),
  3 translucent paint (frames and strokes with transparency). Pass 3 is drawn before the sign text
  and pass 1 after it, so a label stays on top of the paint under it as in Roblox (neither the text
  nor the blended passes write depth, so the draw order decides); the few translucent frames that
  Roblox draws over a label of their SurfaceGui (later in the GUI's draw order, overlapping the
  label) go to pass 1 instead.
  LOD classes, nested so each detail level is one prefix of its pass: class 2 (parts >= 40 m, paint
  >= 12 m on them) is drawn at every level, class 1 (parts >= 3 m, paint >= 1.5 m or 20 m^2) at
  levels 0 and 1, class 0 only at level 0. Level 0 < 3 km, level 1 3-15 km, level 2 15-60 km,
  beyond that only a marker.

  Paint: each visible Frame becomes a flat convex polygon (rotated, UICorner-rounded, clipped by
  ClipsDescendants ancestors and the canvas) PAINT_OFFSET above its face, UIStrokes become outline
  rings. Frames that overlap an earlier frame on the same face go one layer up (LAYER_STEP higher;
  the shader adds a depth bias per layer), so overlapping paint never z-fights.

  The output is deterministic: identical inputs give identical bytes.
"""
import argparse
import base64
import json
import math
import os
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VERSION = 2
MODELS = ["RingStation", "GardenStation", "FoundryStation", "SpireStation", "HollowStation", "RefineryStation"]
SITE = "Site"

# Shading classes (vertex material id). Keep in sync with StationModel.kt.
MATERIALS = ["smooth", "rough", "metal", "plate", "foil", "organic", "ice", "glass", "neon", "forcefield",
             "paint1", "paint2", "paint3", "paint4", "paint5", "paint6"]
M = {n: i for i, n in enumerate(MATERIALS)}
PAINT1 = M["paint1"]
MAX_LAYERS = 6
# Roblox Enum.Material value -> shading class
ROBLOX_MATERIAL = {
    256: "rough", 272: "smooth", 288: "neon", 512: "organic", 528: "organic", 784: "rough", 788: "rough",
    800: "rough", 804: "rough", 816: "rough", 820: "rough", 832: "rough", 836: "rough", 848: "rough",
    864: "rough", 880: "rough", 896: "rough", 912: "rough", 1040: "plate", 1056: "plate", 1072: "foil",
    1088: "metal", 1280: "organic", 1284: "organic", 1296: "rough", 1312: "organic", 1328: "ice",
    1344: "rough", 1360: "rough", 1376: "rough", 1392: "rough", 1536: "ice", 1552: "ice", 1568: "glass",
    1584: "forcefield",
}
FORCEFIELD = M["forcefield"]

PASS_OPAQUE, PASS_TRANSLUCENT, PASS_LAMP, PASS_PAINT = 0, 1, 2, 3
PASSES = 4
# lamp slots the game's shader holds (StationInstance.LAMP_SLOTS - 1; slot 0 = none)
MAX_LAMP_SLOTS = 63
LAMP_ACCENT, LAMP_PAD, LAMP_PAPI, LAMP_BLINK = 0, 1, 2, 3
SHAPE_BLOCK, SHAPE_BALL, SHAPE_CYLINDER, SHAPE_WEDGE, SHAPE_CORNER = 0, 1, 2, 3, 4
POS_BITS = 19

PAINT_OFFSET = 0.02  # metres above the face for layer 1
LAYER_STEP = 0.01  # metres per further layer
LOD2_PART = 40.0
LOD1_PART = 3.0
LOD2_PAINT = 12.0
LOD1_PAINT = 1.5
LOD1_PAINT_AREA = 20.0
RING_DEFAULT_SPIN = 2.0  # ShipStation.readModel: the Model "Ring" without the attribute

# NormalId -> (normal axis, sign), canvas right (axis, sign), canvas down (axis, sign)
FACES = {
    0: ((0, 1), (2, -1), (1, -1)),  # Right
    1: ((1, 1), (2, -1), (0, 1)),  # Top
    2: ((2, 1), (0, 1), (1, -1)),  # Back
    3: ((0, -1), (2, 1), (1, -1)),  # Left
    4: ((1, -1), (2, -1), (0, -1)),  # Bottom
    5: ((2, -1), (0, -1), (1, -1)),  # Front
}

FONT_UI, FONT_TITLE, FONT_MONO = 0, 1, 2
# Roblox font family -> atlas face (tools/gen_font.py): the signage fonts are not in the game's atlas
FONT_FAMILY = {"michroma": FONT_TITLE, "oswald": FONT_TITLE, "gothamssm": FONT_UI, "sourcesanspro": FONT_UI,
               "nunito": FONT_UI, "fredokaone": FONT_UI, "robotomono": FONT_MONO}

PART_CLASSES = {"Part", "WedgePart", "CornerWedgePart"}
LIGHT_CLASSES = {"PointLight": 0, "SpotLight": 1, "SurfaceLight": 2}
GUI_CLASSES = {"Frame", "TextLabel"}


def f32(x):
    """Rounds to float32 (so stored values and the report see exactly what the game reads)."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


def fix_text(s):
    """rbxmx writes each UTF-8 byte above 127 as its own character reference."""
    try:
        return s.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


# ---------------------------------------------------------------------------- rbxmx

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
        elif t == 0x0F:
            v = ("Color3",) + take("3f")
        elif t == 0x10:
            v = ("Vector2",) + take("2f")
        elif t == 0x11:
            v = ("Vector3",) + take("3f")
        else:
            raise ValueError("unknown attribute type 0x%02x (%s)" % (t, key))
        out[key] = v
    return out


def _prop(el):
    tag = el.tag
    if tag == "CoordinateFrame":
        d = {c.tag: float(c.text) for c in el}
        return [d[k] for k in ("X", "Y", "Z", "R00", "R01", "R02", "R10", "R11", "R12", "R20", "R21", "R22")]
    if tag in ("Vector3", "Vector2", "Color3"):
        return [float(c.text) for c in el]
    if tag == "Color3uint8":
        v = int(el.text)
        return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
    if tag == "UDim2":
        d = {c.tag: c.text for c in el}
        return [float(d["XS"]), float(d["XO"]), float(d["YS"]), float(d["YO"])]
    if tag == "UDim":
        d = {c.tag: c.text for c in el}
        return [float(d["S"]), float(d["O"])]
    if tag in ("float", "double"):
        return float(el.text)
    if tag in ("int", "int64", "token"):
        return int(el.text or 0)
    if tag == "bool":
        return el.text == "true"
    if tag == "Font":
        fam = el.find("Family")
        url = fam.find("url") if fam is not None else None
        return {"family": url.text if url is not None and url.text else "", "weight": (el.findtext("Weight") or "400")}
    if tag == "Ref":
        return el.text or ""
    if tag in ("NumberSequence", "ColorSequence"):
        return [float(x) for x in (el.text or "").split()]
    return el.text or ""


KEEP_PROPS = {
    "Name", "CFrame", "size", "Color3uint8", "Material", "Transparency", "shape", "AttributesSerialize",
    "PrimaryPart", "MeshType", "Scale", "Offset",
    "Color", "Brightness", "Range", "Angle", "Face", "Enabled", "Shadows",
    "PixelsPerStud", "LightInfluence", "ZOffset", "ClipsDescendants", "SizingMode", "AlwaysOnTop",
    "Size", "Position", "AnchorPoint", "Rotation", "ZIndex", "BackgroundColor3", "BackgroundTransparency",
    "Visible", "BorderSizePixel", "BorderColor3",
    "Text", "TextColor3", "TextTransparency", "TextScaled", "TextWrapped", "TextXAlignment", "TextYAlignment",
    "TextSize", "FontFace",
    "Thickness", "ApplyStrokeMode",
    "TopLeftRadius", "TopRightRadius", "BottomLeftRadius", "BottomRightRadius", "CornerRadius",
    "Attachment0", "Attachment1", "Width0", "Width1", "LightEmission", "FaceCamera", "Segments",
}


def read_models(stream):
    """Stream-parses the Roblox XML; returns {name: model node} for the stations and the site."""
    stack = []
    found = {}
    for ev, el in ET.iterparse(stream, events=("start", "end")):
        if ev == "start":
            if el.tag == "Item":
                parent = stack[-1] if stack else None
                keep = None if parent is None or parent["keep"] is not False else False
                node = {"class": el.get("class"), "ref": el.get("referent"), "props": {}, "children": [],
                        "keep": keep, "parent": None}
                if parent is not None and parent["keep"]:
                    parent["children"].append(node)
                    node["parent"] = parent
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
                # Assets (depth 0) / Stations, Construction (1) / models (2) / ...
                node["keep"] = depth != 1 or name in ("Stations", "Construction")
                if not node["keep"]:
                    node["props"] = {}
                else:
                    node["name"] = name
                    node["attrs"] = decode_attributes(node["props"].pop("AttributesSerialize", ""))
                    if depth == 2 and (name in MODELS or name == SITE):
                        found[name] = node
            el.clear()
        elif el.tag == "Item":
            stack.pop()
            el.clear()
    missing = [n for n in MODELS + [SITE] if n not in found]
    if missing:
        raise ValueError("models missing from the rbxmx: %s" % ", ".join(missing))
    return found


# ---------------------------------------------------------------------------- math

def cf_mul(a, b):
    """Roblox CFrames as [x, y, z, R00..R22]: a * b."""
    ra, rb = a[3:], b[3:]
    r = [sum(ra[3 * i + k] * rb[3 * k + j] for k in range(3)) for i in range(3) for j in range(3)]
    p = [a[i] + sum(ra[3 * i + k] * b[k] for k in range(3)) for i in range(3)]
    return p + r


def cf_inv(a):
    r = a[3:]
    rt = [r[3 * j + i] for i in range(3) for j in range(3)]
    p = [-sum(rt[3 * i + k] * a[k] for k in range(3)) for i in range(3)]
    return p + rt


def cf_point(cf, v):
    r = cf[3:]
    return (cf[0] + r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
            cf[1] + r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
            cf[2] + r[6] * v[0] + r[7] * v[1] + r[8] * v[2])


def cf_vector(cf, v):
    r = cf[3:]
    return (r[0] * v[0] + r[1] * v[1] + r[2] * v[2],
            r[3] * v[0] + r[4] * v[1] + r[5] * v[2],
            r[6] * v[0] + r[7] * v[1] + r[8] * v[2])


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def add(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def mul(a, s):
    return (a[0] * s, a[1] * s, a[2] * s)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def norm(a):
    ln = math.sqrt(dot(a, a))
    return (a[0] / ln, a[1] / ln, a[2] / ln) if ln > 1e-12 else (0.0, 0.0, 0.0)


def axis_vec(axis, sign):
    v = [0.0, 0.0, 0.0]
    v[axis] = float(sign)
    return tuple(v)


def srgb_byte(x):
    return max(0, min(255, int(math.floor(x * 255.0 + 0.5))))


# ---------------------------------------------------------------------------- model walk

class Part:
    def __init__(self, node, cf, group, path):
        self.node = node
        self.cf = cf  # root space
        self.group = group
        self.path = path
        p = node["props"]
        self.cls = node["class"]
        self.size = p.get("size", [1.0, 1.0, 1.0])
        self.color = p.get("Color3uint8", [163, 162, 165])
        self.material = p.get("Material", 256)
        self.transparency = p.get("Transparency", 0.0)
        self.shape = p.get("shape", 1) if self.cls == "Part" else None
        self.attrs = node.get("attrs", {})
        self.slot = 0
        self.mesh_sphere = any(c["class"] == "SpecialMesh" and c["props"].get("MeshType") == 3 for c in node["children"])


def find_child(node, name, cls=None):
    for c in node["children"]:
        if c.get("name") == name and (cls is None or c["class"] == cls):
            return c
    return None


def descendants(node):
    for c in node["children"]:
        yield c
        yield from descendants(c)


def num(v, default=None):
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else default


def vec3(v, default=None):
    return tuple(v[1:4]) if isinstance(v, tuple) and v and v[0] == "Vector3" else default


class Model:
    """Everything read from one station model, in root space."""

    def __init__(self, node, site):
        self.node = node
        self.site = site
        self.name = node["name"]
        self.attrs = node.get("attrs", {})
        prim_ref = node["props"].get("PrimaryPart")
        root = None
        for d in descendants(node):
            if d["ref"] == prim_ref or (root is None and d.get("name") == "Root" and d["class"] in PART_CLASSES):
                root = d
                if d["ref"] == prim_ref:
                    break
        if root is None:
            raise ValueError("%s: no Root part" % self.name)
        self.root_inv = cf_inv(root["props"]["CFrame"])
        self.root = root
        # groups: 0 = static; stations: Ring + SpinDegreesPerSecond models; site: Stage1..4
        self.groups = [None]
        self.group_of = {}
        if site:
            for i in range(1, 5):
                st = find_child(node, "Stage%d" % i)
                if st is None:
                    raise ValueError("Site: Stage%d missing" % i)
                self.group_of[id(st)] = len(self.groups)
                self.groups.append({"name": "Stage%d" % i, "speed": 0.0, "axis": (0.0, 0.0, 1.0), "centre": (0.0, 0.0, 0.0)})
        else:
            ring = find_child(node, "Ring", "Model")

            def add_group(holder, fallback):
                deg = num(holder["attrs"].get("SpinDegreesPerSecond"), fallback)
                if deg is None:
                    return
                axis = vec3(holder["attrs"].get("SpinAxis"))
                axis = norm(axis) if axis is not None and math.sqrt(dot(axis, axis)) > 1e-3 else (0.0, 0.0, 1.0)
                centre = vec3(holder["attrs"].get("SpinCentre"), (0.0, 0.0, 0.0))
                self.group_of[id(holder)] = len(self.groups)
                self.groups.append({"name": holder["name"], "speed": deg, "axis": axis, "centre": centre})

            if ring is not None:
                add_group(ring, RING_DEFAULT_SPIN)
            for d in descendants(node):
                if d["class"] == "Model" and d is not ring and d["attrs"].get("SpinDegreesPerSecond") is not None:
                    add_group(d, None)
        if len(self.groups) > 8:
            raise ValueError("%s: %d groups, the vertex holds 3 bits" % (self.name, len(self.groups)))
        self.parts = []
        self.lights = []
        self.beams = []
        self.attachments = {}

        def walk(n, group, path):
            for c in n["children"]:
                g = self.group_of.get(id(c), group)
                p = path + (c.get("name"),)
                if c["class"] in PART_CLASSES:
                    part = Part(c, cf_mul(self.root_inv, c["props"]["CFrame"]), g, p)
                    self.parts.append(part)
                    for ch in c["children"]:
                        if ch["class"] in LIGHT_CLASSES:
                            self.lights.append((ch, part))
                        elif ch["class"] == "Attachment":
                            self.attachments[ch["ref"]] = (ch, part)
                        elif ch["class"] == "Beam":
                            self.beams.append((ch, part))
                walk(c, g, p)

        walk(node, 0, ())
        self.part_count = len(self.parts)


# ---------------------------------------------------------------------------- mesh building

class Mesh:
    def __init__(self, groups):
        self.verts = []  # (x, y, z, nx, ny, nz, r, g, b, a, mat, slot, group)
        self.tris = {}  # (pass, cls, group) -> [i, ...]
        self.groups = groups

    def add(self, pts, normals, color, mat, slot, group, ps, cls, triangles):
        base = len(self.verts)
        for p, n in zip(pts, normals):
            self.verts.append((p[0], p[1], p[2], n[0], n[1], n[2], color[0], color[1], color[2], color[3], mat, slot, group))
        dst = self.tris.setdefault((ps, cls, group), [])
        for a, b, c in triangles:
            dst.extend((base + a, base + b, base + c))

    def triangle_count(self, lod):
        """Triangles drawn at detail level lod (0, 1, 2)."""
        n = 0
        for (ps, cls, g), idx in self.tris.items():
            if cls >= lod:
                n += len(idx) // 3
        return n


def oriented(tris, pts, normal):
    """Flips triangles whose winding disagrees with [normal] (CCW seen from outside)."""
    out = []
    for a, b, c in tris:
        n = cross(sub(pts[b], pts[a]), sub(pts[c], pts[a]))
        out.append((a, b, c) if dot(n, normal) >= 0 else (a, c, b))
    return out


def lod_class_part(size):
    m = max(size)
    return 2 if m >= LOD2_PART else (1 if m >= LOD1_PART else 0)


def cyl_segments(r):
    return 8 if r < 2 else 12 if r < 6 else 16 if r < 15 else 20 if r < 30 else 24


def ball_segments(r):
    return (6, 4) if r < 2 else (8, 5) if r < 6 else (12, 6) if r < 15 else (16, 8)


def emit_polygon(mesh, cf, pts_local, normal_local, color, mat, slot, group, ps, cls):
    """A flat convex polygon in part space -> root space (triangle fan)."""
    pts = [cf_point(cf, p) for p in pts_local]
    n = norm(cf_vector(cf, normal_local))
    tris = oriented([(0, i, i + 1) for i in range(1, len(pts) - 1)], pts, n)
    mesh.add(pts, [n] * len(pts), color, mat, slot, group, ps, cls, tris)


def tessellate(mesh, part, color, mat, slot, ps, cls):
    sx, sy, sz = (s * 0.5 for s in part.size)
    cf = part.cf
    g = part.group
    if part.cls == "Part" and part.shape == 1 and not part.mesh_sphere or part.cls == "TrussPart":
        for axis in range(3):
            for sign in (-1, 1):
                n = [0.0, 0.0, 0.0]
                n[axis] = float(sign)
                u = (axis + 1) % 3
                v = (axis + 2) % 3
                h = (sx, sy, sz)
                quad = []
                for du, dv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                    p = [0.0, 0.0, 0.0]
                    p[axis] = sign * h[axis]
                    p[u] = du * h[u]
                    p[v] = dv * h[v]
                    quad.append(tuple(p))
                emit_polygon(mesh, cf, quad, tuple(n), color, mat, slot, g, ps, cls)
        return
    if part.cls == "WedgePart":
        # slope from the front-bottom edge (-Z, -Y) to the back-top edge (+Z, +Y)
        a = (-sx, -sy, -sz)
        b = (sx, -sy, -sz)
        c = (sx, -sy, sz)
        d = (-sx, -sy, sz)
        e = (-sx, sy, sz)
        f = (sx, sy, sz)
        emit_polygon(mesh, cf, [a, b, c, d], (0.0, -1.0, 0.0), color, mat, slot, g, ps, cls)
        emit_polygon(mesh, cf, [d, c, f, e], (0.0, 0.0, 1.0), color, mat, slot, g, ps, cls)
        slope = norm((0.0, sz, -sy))
        emit_polygon(mesh, cf, [a, b, f, e], slope, color, mat, slot, g, ps, cls)
        emit_polygon(mesh, cf, [b, c, f], (1.0, 0.0, 0.0), color, mat, slot, g, ps, cls)
        emit_polygon(mesh, cf, [a, d, e], (-1.0, 0.0, 0.0), color, mat, slot, g, ps, cls)
        return
    if part.cls == "CornerWedgePart":
        # apex above the right-front corner (+X, +Y, -Z); square base at -Y
        a = (-sx, -sy, -sz)
        b = (sx, -sy, -sz)
        c = (sx, -sy, sz)
        d = (-sx, -sy, sz)
        t = (sx, sy, -sz)
        emit_polygon(mesh, cf, [a, b, c, d], (0.0, -1.0, 0.0), color, mat, slot, g, ps, cls)
        emit_polygon(mesh, cf, [b, c, t], (1.0, 0.0, 0.0), color, mat, slot, g, ps, cls)
        emit_polygon(mesh, cf, [a, b, t], (0.0, 0.0, -1.0), color, mat, slot, g, ps, cls)
        for tri in ((c, d, t), (d, a, t)):
            n = norm(cross(sub(tri[1], tri[0]), sub(tri[2], tri[0])))
            if dot(n, sub(tri[0], (0.0, 0.0, 0.0))) < 0:
                n = mul(n, -1.0)
            emit_polygon(mesh, cf, list(tri), n, color, mat, slot, g, ps, cls)
        return
    if part.cls == "Part" and part.shape == 2:
        # cylinder along local X: length size.X, diameter min(size.Y, size.Z)
        r = min(sy, sz)
        segs = cyl_segments(r)
        ring = [(math.cos(2 * math.pi * k / segs), math.sin(2 * math.pi * k / segs)) for k in range(segs)]
        pts, nrm, tris = [], [], []
        for k, (cy, cz) in enumerate(ring):
            pts.append((-sx, cy * r, cz * r))
            pts.append((sx, cy * r, cz * r))
            nrm.append((0.0, cy, cz))
            nrm.append((0.0, cy, cz))
        for k in range(segs):
            i0, i1 = 2 * k, 2 * ((k + 1) % segs)
            tris.append((i0, i1, i1 + 1))
            tris.append((i0, i1 + 1, i0 + 1))
        wp = [cf_point(cf, p) for p in pts]
        wn = [norm(cf_vector(cf, n)) for n in nrm]
        # orient by the radial normal of the first vertex of each triangle
        tris = [t if dot(cross(sub(wp[t[1]], wp[t[0]]), sub(wp[t[2]], wp[t[0]])), wn[t[0]]) >= 0 else (t[0], t[2], t[1]) for t in tris]
        mesh.add(wp, wn, color, mat, slot, g, ps, cls, tris)
        for sign in (-1, 1):
            cap = [(sign * sx, cy * r, cz * r) for cy, cz in ring]
            emit_polygon(mesh, cf, cap, (float(sign), 0.0, 0.0), color, mat, slot, g, ps, cls)
        return
    # Ball (diameter = the smallest side) or SpecialMesh sphere (ellipsoid filling the box)
    if part.mesh_sphere:
        rx, ry, rz = sx, sy, sz
    else:
        r = min(sx, sy, sz)
        rx = ry = rz = r
    segs, rings = ball_segments(max(rx, ry, rz))
    pts, nrm, tris = [], [], []
    for i in range(rings + 1):
        th = math.pi * i / rings
        for k in range(segs + 1):
            ph = 2 * math.pi * k / segs
            ux, uy, uz = math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph)
            pts.append((ux * rx, uy * ry, uz * rz))
            nrm.append(norm((ux / rx, uy / ry, uz / rz)))
    for i in range(rings):
        for k in range(segs):
            a = i * (segs + 1) + k
            b = a + segs + 1
            if i > 0:
                tris.append((a, b, a + 1))
            if i < rings - 1:
                tris.append((a + 1, b, b + 1))
    wp = [cf_point(cf, p) for p in pts]
    wn = [norm(cf_vector(cf, n)) for n in nrm]
    out = []
    for t in tris:
        c = mul(add(add(wn[t[0]], wn[t[1]]), wn[t[2]]), 1.0 / 3.0)
        n = cross(sub(wp[t[1]], wp[t[0]]), sub(wp[t[2]], wp[t[0]]))
        out.append(t if dot(n, c) >= 0 else (t[0], t[2], t[1]))
    mesh.add(wp, wn, color, mat, slot, g, ps, cls, out)


# ---------------------------------------------------------------------------- paint (SurfaceGuis)

def clip_polygon(poly, x0, y0, x1, y1):
    """Sutherland-Hodgman against the axis-aligned rectangle [x0, x1] x [y0, y1]."""
    def clip(pts, inside, inter):
        out = []
        n = len(pts)
        for i in range(n):
            a = pts[i]
            b = pts[(i + 1) % n]
            ia, ib = inside(a), inside(b)
            if ia:
                out.append(a)
                if not ib:
                    out.append(inter(a, b))
            elif ib:
                out.append(inter(a, b))
        return out

    def lerp_x(a, b, x):
        t = (x - a[0]) / (b[0] - a[0])
        return (x, a[1] + (b[1] - a[1]) * t) + tuple(a[k] + (b[k] - a[k]) * t for k in range(2, len(a)))

    def lerp_y(a, b, y):
        t = (y - a[1]) / (b[1] - a[1])
        return (a[0] + (b[0] - a[0]) * t, y) + tuple(a[k] + (b[k] - a[k]) * t for k in range(2, len(a)))

    pts = poly
    for inside, inter in (
        (lambda p: p[0] >= x0, lambda a, b: lerp_x(a, b, x0)),
        (lambda p: p[0] <= x1, lambda a, b: lerp_x(a, b, x1)),
        (lambda p: p[1] >= y0, lambda a, b: lerp_y(a, b, y0)),
        (lambda p: p[1] <= y1, lambda a, b: lerp_y(a, b, y1)),
    ):
        if not pts:
            return pts
        pts = clip(pts, inside, inter)
    return pts


def polygon_area(pts):
    a = 0.0
    for i in range(len(pts)):
        x0, y0 = pts[i][0], pts[i][1]
        x1, y1 = pts[(i + 1) % len(pts)][0], pts[(i + 1) % len(pts)][1]
        a += x0 * y1 - x1 * y0
    return abs(a) * 0.5


def rounded_rect(w, h, radius, segs):
    """Convex outline (counter-clockwise in canvas space, y down) of a rectangle 0..w x 0..h."""
    r = max(0.0, min(radius, w * 0.5, h * 0.5))
    if r <= 1e-6:
        return [(0.0, 0.0), (w, 0.0), (w, h), (0.0, h)]
    pts = []
    centres = [(w - r, r, -90.0), (w - r, h - r, 0.0), (r, h - r, 90.0), (r, r, 180.0)]
    for cx, cy, start in centres:
        for k in range(segs + 1):
            a = math.radians(start + 90.0 * k / segs)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def corner_segments(radius_m):
    return max(2, min(12, int(math.ceil(radius_m * 1.5))))


def outline_ring(w, h, radius, thickness, segs):
    """Quads (convex) of an outline of width [thickness] outside a (rounded) rectangle 0..w x 0..h."""
    r = max(0.0, min(radius, w * 0.5, h * 0.5))
    t = thickness
    inner, outer = [], []
    centres = [(w - r, r, -90.0), (w - r, h - r, 0.0), (r, h - r, 90.0), (r, r, 180.0)]
    for cx, cy, start in centres:
        for k in range(segs + 1):
            a = math.radians(start + 90.0 * k / segs)
            ca, sa = math.cos(a), math.sin(a)
            inner.append((cx + r * ca, cy + r * sa))
            outer.append((cx + (r + t) * ca, cy + (r + t) * sa))
    quads = []
    n = len(inner)
    for i in range(n):
        j = (i + 1) % n
        q = [inner[i], outer[i], outer[j], inner[j]]
        if polygon_area(q) > 1e-9:
            quads.append(q)
    return quads


class Affine2:
    """2D affine map (canvas px): x' = a x + b y + c, y' = d x + e y + f."""

    def __init__(self, a=1.0, b=0.0, c=0.0, d=0.0, e=1.0, f=0.0):
        self.m = (a, b, c, d, e, f)

    def apply(self, p):
        a, b, c, d, e, f = self.m
        return (a * p[0] + b * p[1] + c, d * p[0] + e * p[1] + f) + tuple(p[2:])

    def then(self, o):
        """o after self."""
        a, b, c, d, e, f = self.m
        A, B, C, D, E, F = o.m
        return Affine2(A * a + B * d, A * b + B * e, A * c + B * f + C, D * a + E * d, D * b + E * e, D * c + E * f + F)

    @staticmethod
    def rotation(cx, cy, deg):
        """Clockwise on screen (y down) by [deg] about (cx, cy), as Roblox's GuiObject.Rotation."""
        t = math.radians(deg)
        c, s = math.cos(t), math.sin(t)
        return Affine2(c, -s, cx - c * cx + s * cy, s, c, cy - s * cx - c * cy)


def udim2(v, pw, ph):
    return (v[0] * pw + v[1], v[2] * ph + v[3])


def corner_radius_px(frame, w, h):
    for c in frame["children"]:
        if c["class"] == "UICorner":
            p = c["props"]
            ud = p.get("TopLeftRadius") or p.get("CornerRadius") or [0.0, 8.0]
            return max(0.0, ud[0] * min(w, h) + ud[1])
    return None


def gradient_of(frame):
    for c in frame["children"]:
        if c["class"] == "UIGradient" and c["props"].get("Enabled", True):
            return c
    return None


def sample_sequence(seq, stride, t):
    """Roblox ColorSequence (time r g b 0 ...) / NumberSequence (time value envelope ...) at t."""
    keys = [seq[i:i + stride] for i in range(0, len(seq) - stride + 1, stride)]
    if not keys:
        return None
    if t <= keys[0][0]:
        return keys[0][1:]
    for k0, k1 in zip(keys, keys[1:]):
        if t <= k1[0]:
            u = (t - k0[0]) / max(k1[0] - k0[0], 1e-9)
            return [k0[1 + i] + (k1[1 + i] - k0[1 + i]) * u for i in range(stride - 1)]
    return keys[-1][1:]


def gui_items(gui, cw, ch, li, br):
    """Frames and labels of one SurfaceGui in draw order: (order key, kind, data)."""
    items = []
    seq = [0]
    clip_root = (0.0, 0.0, cw, ch) if gui["props"].get("ClipsDescendants", True) else None

    def visit(node, rect, xf, clips, rotated):
        for c in node["children"]:
            if c["class"] not in GUI_CLASSES:
                continue
            p = c["props"]
            if not p.get("Visible", True):
                continue
            px, py, pw, ph = rect
            w, h = udim2(p.get("Size", [0, 0, 0, 0]), pw, ph)
            x, y = udim2(p.get("Position", [0, 0, 0, 0]), pw, ph)
            ax, ay = p.get("AnchorPoint", [0.0, 0.0])
            x0 = px + x - ax * w
            y0 = py + y - ay * h
            rot = p.get("Rotation", 0.0) or 0.0
            mine = xf
            if rot:
                mine = Affine2.rotation(x0 + w * 0.5, y0 + h * 0.5, rot).then(xf)
            is_rot = rotated or bool(rot)
            seq[0] += 1
            key = (p.get("ZIndex", 1), seq[0])
            items.append((key, c, (x0, y0, w, h), mine, clips, li, br))
            child_clips = clips
            if p.get("ClipsDescendants", False) and not is_rot:
                cr = (x0, y0, x0 + w, y0 + h)
                child_clips = clips + [cr]
            visit(c, (x0, y0, w, h), mine, child_clips, is_rot)

    visit(gui, (0.0, 0.0, cw, ch), Affine2(), [clip_root] if clip_root else [], False)
    return items


def frame_polys(frame, rect, xf, clips, pps):
    """Coloured convex polygons (canvas px) for a Frame: its background and its UIStroke ring."""
    p = frame["props"]
    x0, y0, w, h = rect
    out = []
    if w <= 0 or h <= 0:
        return out
    radius = corner_radius_px(frame, w, h)
    segs = corner_segments((radius or 0.0) / pps)
    bt = p.get("BackgroundTransparency", 0.0)
    if bt < 0.999:
        col = p.get("BackgroundColor3", [1.0, 1.0, 1.0])
        alpha = 1.0 - bt
        grad = gradient_of(frame)
        local = rounded_rect(w, h, radius or 0.0, segs)
        poly = []
        for lx, ly in local:
            c = col
            a = alpha
            if grad is not None:
                gp = grad["props"]
                rot = math.radians(gp.get("Rotation", 0.0))
                # UIGradient: the colour runs along the rotated x axis of the frame (0..1)
                u = 0.5 + ((lx / w - 0.5) * math.cos(rot) + (ly / h - 0.5) * math.sin(rot))
                u = min(1.0, max(0.0, u))
                gc = sample_sequence(gp.get("Color", []), 5, u)
                if gc:
                    c = [col[0] * gc[0], col[1] * gc[1], col[2] * gc[2]]
                gt = sample_sequence(gp.get("Transparency", []), 3, u)
                if gt:
                    a = alpha * (1.0 - gt[0])
            poly.append((x0 + lx, y0 + ly, c[0], c[1], c[2], a))
        out.append(("fill", poly))
    for s in frame["children"]:
        if s["class"] != "UIStroke" or not s["props"].get("Enabled", True):
            continue
        sp = s["props"]
        st = sp.get("Transparency", 0.0)
        th = sp.get("Thickness", 1.0)
        if st >= 0.999 or th <= 0:
            continue
        col = sp.get("Color", [0.0, 0.0, 0.0])
        for q in outline_ring(w, h, radius or 0.0, th, max(segs, corner_segments((radius or 0.0) / pps + th / pps))):
            out.append(("stroke", [(x0 + qx, y0 + qy, col[0], col[1], col[2], 1.0 - st) for qx, qy in q]))
    res = []
    for kind, poly in out:
        pts = [xf.apply(v) for v in poly]
        for c in clips:
            pts = clip_polygon(pts, *c)
            if len(pts) < 3:
                break
        if len(pts) >= 3 and polygon_area(pts) > 1e-6:
            res.append((kind, pts))
    return res


def overlaps(b0, b1):
    return b0[0] < b1[2] and b1[0] < b0[2] and b0[1] < b1[3] and b1[1] < b0[3]


def convex_overlap(p0, p1, eps=1e-4):
    """Separating-axis test of two convex polygons (2D); touching edges do not count."""
    for poly in (p0, p1):
        n = len(poly)
        for i in range(n):
            ax = poly[(i + 1) % n][1] - poly[i][1]
            ay = poly[i][0] - poly[(i + 1) % n][0]
            ln = math.hypot(ax, ay)
            if ln < 1e-12:
                continue
            ax /= ln
            ay /= ln
            a = [v[0] * ax + v[1] * ay for v in p0]
            b = [v[0] * ax + v[1] * ay for v in p1]
            if min(a) >= max(b) - eps or min(b) >= max(a) - eps:
                return False
    return True


def layer_above(placed, pts, key):
    """Lowest layer that is above every earlier paint of another colour overlapping [pts] (face metres)."""
    bb = bbox(pts)
    layer = 1
    for bb2, pts2, key2, l2 in placed:
        if l2 + 1 > layer and key2 != key and overlaps(bb, bb2) and convex_overlap(pts, pts2):
            layer = l2 + 1
    return layer


def bbox(pts):
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return (min(xs), min(ys), max(xs), max(ys))


def face_frame(part, face):
    """Part-space canvas basis of [face]: top-left corner, right, down (unit), normal, canvas size (studs)."""
    (na, ns), (ra, rs), (da, ds) = FACES[face]
    n = axis_vec(na, ns)
    r = axis_vec(ra, rs)
    d = axis_vec(da, ds)
    half = [s * 0.5 for s in part.size]
    centre = mul(n, half[na])
    w, h = part.size[ra], part.size[da]
    tl = sub(sub(centre, mul(r, w * 0.5)), mul(d, h * 0.5))
    return tl, r, d, n, w, h


def emission_byte(li, br):
    lo = max(0, min(15, int(math.floor(li * 15.0 + 0.5))))
    hi = max(0, min(15, int(math.floor((1.0 - li) * br / 0.15 + 0.5))))
    return lo | (hi << 4)


def font_face(font):
    fam = (font or {}).get("family", "").lower()
    base = fam.rsplit("/", 1)[-1].split(".")[0]
    return FONT_FAMILY.get(base, FONT_UI)


def bake_paint(model, mesh, texts, stats):
    """Every SurfaceGui of every part: paint polygons into [mesh], labels into [texts]. Paint is never
    drawn at a coarser detail level than its part (an invisible part counts by its size)."""
    sign = find_child(model.node, "Sign")
    pads_folder = find_child(model.node, "Pads")
    for part in model.parts:
        part_lod = lod_class_part(part.size)
        guis = [c for c in part.node["children"] if c["class"] == "SurfaceGui" and c["props"].get("Enabled", True)]
        if not guis:
            continue
        by_face = {}
        for g in guis:
            by_face.setdefault(g["props"].get("Face", 5), []).append(g)
        for face, fg in sorted(by_face.items()):
            fg.sort(key=lambda g: g["props"].get("ZOffset", 0.0))  # stable: file order within a ZOffset
            tl, ru, du, nu, wst, hst = face_frame(part, face)
            items = []
            for gi, g in enumerate(fg):
                gp = g["props"]
                if gp.get("SizingMode", 1) != 1:
                    raise ValueError("%s: SurfaceGui %s is not PixelsPerStud" % (model.name, g["name"]))
                pps = gp.get("PixelsPerStud", 50.0)
                li = gp.get("LightInfluence", 1.0)
                br = gp.get("Brightness", 1.0)
                for it in gui_items(g, wst * pps, hst * pps, li, br):
                    items.append(((gp.get("ZOffset", 0.0), it[0][0], gi, it[0][1]), it, pps))
            # Global ZIndexBehavior: by ZIndex, then hierarchy order; SurfaceGuis by ZOffset
            items.sort(key=lambda t: t[0])
            placed = []  # (bbox, polygon in face metres, colour key, layer)
            labels = []  # (bbox, quad in face metres) of the labels drawn so far on this face
            for _, (key, node, rect, xf, clips, li, br), pps in items:
                if node["class"] == "TextLabel":
                    x0, y0, w, h = rect
                    quad = [xf.apply(c) for c in ((x0, y0), (x0 + w, y0), (x0 + w, y0 + h), (x0, y0 + h))]
                    quad = [(q[0] / pps, q[1] / pps) for q in quad]
                    text_layer = layer_above(placed, quad, None)
                    labels.append((bbox(quad), quad))
                    texts.append(make_label(part, node, rect, xf, pps, li, br, tl, ru, du, nu, min(text_layer, MAX_LAYERS),
                                            sign, pads_folder))
                    stats["labels"] += 1
                    continue
                polys = frame_polys(node, rect, xf, clips, pps)
                if not polys:
                    continue
                stats["frames"] += 1
                metres = []
                layer = 1
                for kind, poly in polys:
                    pm = [(v[0] / pps, v[1] / pps) for v in poly]
                    ck = tuple(tuple(srgb_byte(c) for c in v[2:6]) for v in poly)
                    ck = ck[0] if all(c == ck[0] for c in ck) else ck
                    metres.append((pm, ck))
                    layer = max(layer, layer_above(placed, pm, ck))
                for pm, ck in metres:
                    placed.append((bbox(pm), pm, ck, layer))
                layer_c = min(layer, MAX_LAYERS)
                lift = PAINT_OFFSET + LAYER_STEP * (layer - 1)
                for kind, poly in polys:
                    pts = [add(add(add(tl, mul(ru, v[0] / pps)), mul(du, v[1] / pps)), mul(nu, lift)) for v in poly]
                    # alpha and colour per vertex (gradients); a fan of a convex polygon
                    root = [cf_point(part.cf, p) for p in pts]
                    n = norm(cf_vector(part.cf, nu))
                    a_min = min(v[5] for v in poly)
                    bbm = bbox([(v[0] / pps, v[1] / pps) for v in poly])
                    wm, hm = bbm[2] - bbm[0], bbm[3] - bbm[1]
                    area = polygon_area([(v[0] / pps, v[1] / pps) for v in poly])
                    cls = 0
                    if min(wm, hm) >= LOD1_PAINT or area >= LOD1_PAINT_AREA:
                        cls = 1
                    if min(wm, hm) >= LOD2_PAINT:
                        cls = 2
                    cls = min(cls, part_lod)
                    ps = PASS_OPAQUE
                    if a_min < 0.999:
                        # translucent paint goes under the sign text, unless Roblox draws it over a label
                        # (a frame after the label in the GUI's draw order that overlaps it)
                        pm = [(v[0] / pps, v[1] / pps) for v in poly]
                        bp = bbox(pm)
                        over = any(overlaps(bp, bl) and convex_overlap(pm, ql) for bl, ql in labels)
                        ps = PASS_TRANSLUCENT if over else PASS_PAINT
                        stats["paintOverText" if over else "paintUnderText"] += 1
                    base = len(mesh.verts)
                    for p3, v in zip(root, poly):
                        mesh.verts.append((p3[0], p3[1], p3[2], n[0], n[1], n[2], srgb_byte(v[2]), srgb_byte(v[3]),
                                           srgb_byte(v[4]), srgb_byte(v[5]), PAINT1 + layer_c - 1, emission_byte(li, br), part.group))
                    tris = oriented([(0, i, i + 1) for i in range(1, len(poly) - 1)], root, n)
                    dst = mesh.tris.setdefault((ps, cls, part.group), [])
                    for a, b, c in tris:
                        dst.extend((base + a, base + b, base + c))
                    stats["paintPolygons"] += 1
                    stats["layers"] = max(stats["layers"], layer)


def make_label(part, node, rect, xf, pps, li, br, tl, ru, du, nu, layer, sign, pads_folder):
    """A TextLabel as a text run: its (rotated) box in station space, style and runtime flags."""
    p = node["props"]
    x0, y0, w, h = rect
    corners = [xf.apply(c) for c in ((x0, y0), (x0 + w, y0), (x0, y0 + h))]
    lift = PAINT_OFFSET + LAYER_STEP * (layer - 1) + 0.005

    def to_root(c):
        q = add(add(add(tl, mul(ru, c[0] / pps)), mul(du, c[1] / pps)), mul(nu, lift))
        return cf_point(part.cf, q)

    o = to_root(corners[0])
    u = sub(to_root(corners[1]), o)
    v = sub(to_root(corners[2]), o)
    flags = 0
    pad = 0
    # runtime-replaced texts: Sign/Display/Name (station or site name), Pads/PadN/Number/Display/Text
    gui = node["parent"]
    if sign is not None and part.node is sign and node.get("name") == "Name":
        flags |= 1
    if pads_folder is not None and part.node.get("name") == "Number" and part.node["parent"] is not None \
            and part.node["parent"]["parent"] is pads_folder and node.get("name") == "Text":
        flags |= 2
        pad = int(part.node["parent"]["attrs"].get("PadIndex", 0) or 0)
    if p.get("TextScaled", False):
        flags |= 4
    if p.get("TextWrapped", False):
        flags |= 8
    xa = {0: 0, 1: 1, 2: 2}.get(p.get("TextXAlignment", 2), 2)
    ya = {0: 0, 1: 1, 2: 2}.get(p.get("TextYAlignment", 1), 1)
    col = p.get("TextColor3", [0.0, 0.0, 0.0])
    return {
        "text": fix_text(p.get("Text", "")), "font": font_face(p.get("FontFace")), "flags": flags, "xAlign": xa, "yAlign": ya,
        "group": part.group, "layer": layer, "pad": pad,
        "color": [srgb_byte(col[0]), srgb_byte(col[1]), srgb_byte(col[2]), srgb_byte(1.0 - p.get("TextTransparency", 0.0))],
        "lightInfluence": li, "brightness": br, "textSize": p.get("TextSize", 14.0), "pxPerMetre": pps,
        "origin": o, "u": u, "v": v, "normal": norm(cf_vector(part.cf, nu)), "gui": gui.get("name"),
    }


# ---------------------------------------------------------------------------- per model

def lamp_slots(model):
    """Assigns lamp slots: accent (1), pads, PAPI, blinker classes. Returns the slot table."""
    slots = []
    by_key = {}

    def slot(key, rec):
        if key not in by_key:
            slots.append(rec)
            by_key[key] = len(slots)
        rec_ = slots[by_key[key] - 1]
        rec_["parts"] += 1
        return by_key[key]

    pads = find_child(model.node, "Pads")
    pad_lamp = {}
    if pads is not None:
        for pad in pads["children"]:
            if pad["class"] not in PART_CLASSES:
                continue
            idx = int(pad["attrs"].get("PadIndex", 0) or 0)
            lights = find_child(pad, "Lights")
            if lights is not None:
                for d in descendants(lights):
                    if d["class"] in PART_CLASSES:
                        pad_lamp[id(d)] = idx
    in_stage = set()
    if model.site:
        for i in range(1, 5):
            for d in descendants(find_child(model.node, "Stage%d" % i)):
                in_stage.add(id(d))
    for part in model.parts:
        a = part.attrs
        nid = id(part.node)
        col = list(part.color) + [srgb_byte(1.0 - min(1.0, part.transparency))]
        if nid in pad_lamp:
            part.slot = slot(("pad", pad_lamp[nid]), {"kind": LAMP_PAD, "pass": PASS_LAMP, "pad": pad_lamp[nid], "color": col,
                                                       "period": 0.0, "phase": 0.0, "duty": 0.0, "papi": 0.0,
                                                       "pos": (0.0, 0.0, 0.0), "parts": 0})
        elif num(a.get("Papi")) is not None:
            pos = tuple(part.cf[0:3])
            part.slot = slot(("papi", nid), {"kind": LAMP_PAPI, "pass": PASS_OPAQUE, "pad": 0, "color": col,
                                             "period": 0.0, "phase": 0.0, "duty": 0.0, "papi": math.radians(a["Papi"]),
                                             "pos": pos, "parts": 0})
        elif num(a.get("Blink"), 0.0) > 0 and nid not in in_stage:
            period = float(a["Blink"])
            phase = num(a.get("BlinkPhase"), -1.0)
            duty = min(0.98, max(0.02, num(a.get("BlinkDuty"), 0.5)))
            if phase < 0:
                # no BlinkPhase: Roblox draws a random phase per part, so the part needs a slot of its own
                key = ("blink-part", nid)
            else:
                key = ("blink", f32(period), f32(phase), f32(duty), f32(part.transparency))
            part.slot = slot(key, {"kind": LAMP_BLINK, "pass": PASS_LAMP, "pad": 0, "color": col, "period": period,
                                   "phase": phase, "duty": duty, "papi": 0.0, "pos": (0.0, 0.0, 0.0), "parts": 0})
        elif a.get("Accent") is True:
            part.slot = slot(("accent",), {"kind": LAMP_ACCENT, "pass": PASS_OPAQUE, "pad": 0, "color": col,
                                           "period": 0.0, "phase": 0.0, "duty": 0.0, "papi": 0.0,
                                           "pos": (0.0, 0.0, 0.0), "parts": 0})
    if len(slots) > MAX_LAMP_SLOTS:
        raise ValueError("%s: %d lamp slots, the game holds %d" % (model.name, len(slots), MAX_LAMP_SLOTS))
    return slots, pad_lamp


def convert(model):
    mesh = Mesh(model.groups)
    slots, pad_lamp = lamp_slots(model)
    stats = {"frames": 0, "paintPolygons": 0, "labels": 0, "layers": 0, "rendered": 0, "invisible": 0,
             "paintUnderText": 0, "paintOverText": 0}
    colliders = []
    for part in model.parts:
        a = part.attrs
        shape = SHAPE_BLOCK
        if part.cls == "WedgePart":
            shape = SHAPE_WEDGE
        elif part.cls == "CornerWedgePart":
            shape = SHAPE_CORNER
        elif part.shape == 0:
            shape = SHAPE_BALL
        elif part.shape == 2:
            shape = SHAPE_CYLINDER
        if a.get("Collider") is True:
            colliders.append({"shape": shape, "group": part.group, "cf": part.cf, "size": part.size})
        if part.transparency >= 0.999:
            stats["invisible"] += 1
            continue
        name = ROBLOX_MATERIAL.get(part.material)
        if name is None:
            raise ValueError("%s: unmapped Roblox material %d on %s" % (model.name, part.material, "/".join(part.path)))
        mat = M[name]
        slot = part.slot
        alpha = srgb_byte(1.0 - part.transparency)
        ps = PASS_OPAQUE
        if slot and slots[slot - 1]["pass"] == PASS_LAMP:
            ps = PASS_LAMP
        elif part.transparency > 0.001 or mat == FORCEFIELD:
            ps = PASS_TRANSLUCENT
        cls = lod_class_part(part.size)
        tessellate(mesh, part, (part.color[0], part.color[1], part.color[2], alpha), mat, slot, ps, cls)
        stats["rendered"] += 1
    texts = []
    bake_paint(model, mesh, texts, stats)
    return mesh, slots, colliders, texts, stats


def light_records(model):
    out = []
    for lt, part in model.lights:
        p = lt["props"]
        if not p.get("Enabled", True):
            continue
        cf = part.cf
        face = p.get("Face", 5)
        (na, ns), _, _ = FACES[face]
        d = norm(cf_vector(cf, axis_vec(na, ns)))
        (fa, _), (ra, _), (da, _) = FACES[face]
        col = p.get("Color", [1.0, 1.0, 1.0])
        out.append({"type": LIGHT_CLASSES[lt["class"]], "group": part.group,
                    "color": [srgb_byte(min(1.0, max(0.0, c))) for c in col], "shadows": 1 if p.get("Shadows") else 0,
                    "brightness": p.get("Brightness", 1.0), "range": p.get("Range", 8.0),
                    "angle": p.get("Angle", 90.0) if lt["class"] != "PointLight" else 0.0,
                    "pos": tuple(cf[0:3]), "dir": d if lt["class"] != "PointLight" else (0.0, 0.0, 0.0),
                    "surface": (part.size[ra], part.size[da]) if lt["class"] == "SurfaceLight" else (0.0, 0.0)})
    return out


def beam_records(model):
    out = []
    for bm, part in model.beams:
        p = bm["props"]
        a0 = model.attachments.get(p.get("Attachment0"))
        a1 = model.attachments.get(p.get("Attachment1"))
        if a0 is None or a1 is None or not p.get("Enabled", True):
            continue
        p0 = cf_point(a0[1].cf, a0[0]["props"]["CFrame"][0:3])
        p1 = cf_point(a1[1].cf, a1[0]["props"]["CFrame"][0:3])
        col = sample_sequence(p.get("Color", []), 5, 0.0) or [1.0, 1.0, 1.0]
        tr = p.get("Transparency", [])
        out.append({"group": part.group, "p0": p0, "p1": p1, "w0": p.get("Width0", 1.0), "w1": p.get("Width1", 1.0),
                    "color": [srgb_byte(c) for c in col[:3]],
                    "transparency": [(sample_sequence(tr, 3, t) or [0.0])[0] for t in (0.0, 0.6, 1.0)]})
    return out


def snorm8(v):
    return max(-127, min(127, int(math.floor(v * 127.0 + 0.5))))


def pstr(s):
    b = s.encode("utf-8")
    if len(b) > 255:
        raise ValueError("string too long: %r" % s)
    return struct.pack("<B", len(b)) + b


def encode_mesh(mesh):
    G = len(mesh.groups)
    lists = [[[mesh.tris.get((ps, cls, g), []) for g in range(G)] for cls in (2, 1, 0)] for ps in range(PASSES)]
    order = []
    counts = []
    for ps in range(PASSES):
        for ci in range(3):
            for g in range(G):
                order.append(lists[ps][ci][g])
                counts.append(len(lists[ps][ci][g]))
    used = sorted({i for lst in order for i in lst})
    remap = {old: new for new, old in enumerate(used)}
    verts = [mesh.verts[i] for i in used]
    lo = [min(v[k] for v in verts) for k in range(3)]
    hi = [max(v[k] for v in verts) for k in range(3)]
    origin = [f32(x) for x in lo]
    top = (1 << POS_BITS) - 1
    extent = max(hi[k] - origin[k] for k in range(3))
    step = f32(extent / top * (1.0 + 1e-6))
    out = bytearray()
    out += struct.pack("<3ff", origin[0], origin[1], origin[2], step)
    index_count = sum(counts)
    isize = 2 if len(verts) <= 65536 else 4
    out += struct.pack("<IIB3x", len(verts), index_count, isize)
    out += struct.pack("<%dI" % len(counts), *counts)
    # vertex planes: x, y, z (top 16 bits, delta-coded), w, then the 8 byte attributes
    planes = [bytearray() for _ in range(16)]
    prev = [0, 0, 0]
    for v in verts:
        q = [int(math.floor((v[k] - origin[k]) / step + 0.5)) for k in range(3)]
        if min(q) < 0 or max(q) > top:
            raise ValueError("quantisation out of range")
        w = (q[0] & 7) | ((q[1] & 7) << 3) | ((q[2] & 7) << 6) | (v[10] << 9) | (v[12] << 13)
        words = []
        for k in range(3):
            hi16 = q[k] >> 3
            words.append((hi16 - prev[k]) & 0xFFFF)
            prev[k] = hi16
        words.append(w)
        for k, word in enumerate(words):
            planes[2 * k].append(word & 255)
            planes[2 * k + 1].append(word >> 8)
        for k, byte in enumerate((snorm8(v[3]) & 255, snorm8(v[4]) & 255, snorm8(v[5]) & 255, v[11], v[6], v[7], v[8], v[9])):
            planes[8 + k].append(byte)
    for pl in planes:
        out += pl
    # index planes (low byte first), delta-coded
    mask = (1 << (8 * isize)) - 1
    iplanes = [bytearray() for _ in range(isize)]
    last = 0
    for lst in order:
        for i in lst:
            d = (remap[i] - last) & mask
            last = remap[i]
            for k in range(isize):
                iplanes[k].append((d >> (8 * k)) & 255)
    ib = bytearray()
    for pl in iplanes:
        ib += pl
    while len(ib) % 4:
        ib += b"\0"
    out += ib
    bounds = ([f32(x) for x in lo], [f32(x) for x in hi])
    return bytes(out), bounds, counts, len(verts), index_count, isize, step


def write_model(model, out_dir):
    mesh, slots, colliders, texts, stats = convert(model)
    mesh_bytes, bounds, counts, nverts, nidx, isize, step = encode_mesh(mesh)
    a = model.attrs
    G = len(model.groups)
    out = bytearray(b"USTM")
    out += struct.pack("<HH", VERSION, 1 if model.site else 0)
    out += pstr(model.name) + pstr(str(a.get("BuiltBy", "")))
    out += struct.pack("<f", num(a.get("Radius"), 700.0))
    hs = vec3(a.get("HangarSize"), (0.0, 0.0, 0.0))
    hc = vec3(a.get("HangarCentre"), (0.0, 0.0, 0.0))
    ms = vec3(a.get("MouthSize"), (0.0, 0.0, 0.0))
    out += struct.pack("<9f", *(list(hs) + list(hc) + list(ms)))
    out += struct.pack("<6f", *(bounds[0] + bounds[1]))
    approach = find_child(model.node, "Approach")
    pts = []
    if approach is not None:
        for nm in ("Outer", "Mouth", "Inner"):
            mk = find_child(approach, nm)
            if mk is None:
                raise ValueError("%s: Approach/%s missing" % (model.name, nm))
            pts += list(cf_mul(model.root_inv, mk["props"]["CFrame"])[0:3])
    out += struct.pack("<B", 1 if pts else 0)
    out += struct.pack("<9f", *(pts or [0.0] * 9))
    out += struct.pack("<B", G)
    for g in model.groups[1:]:
        out += pstr(g["name"]) + struct.pack("<7f", g["speed"], *g["axis"], *g["centre"])
    pads = []
    pf = find_child(model.node, "Pads")
    if pf is not None:
        for pad in pf["children"]:
            if pad["class"] not in PART_CLASSES:
                continue
            idx = int(pad["attrs"].get("PadIndex", 0) or 0)
            cf = cf_mul(model.root_inv, pad["props"]["CFrame"])
            top = cf_mul(cf, [0.0, pad["props"]["size"][1] * 0.5, 0.0, 1, 0, 0, 0, 1, 0, 0, 0, 1])
            slot = next((i + 1 for i, s in enumerate(slots) if s["kind"] == LAMP_PAD and s["pad"] == idx), 0)
            pads.append((idx, slot, pad["props"]["size"], top))
    pads.sort(key=lambda p: p[0])
    out += struct.pack("<B", len(pads))
    for idx, slot, size, top in pads:
        out += struct.pack("<BB3f12f", idx, slot, *size, *top)
    out += struct.pack("<B", len(slots))
    for s in slots:
        out += struct.pack("<BBBB4B4f3fH", s["kind"], s["pass"], s["pad"], 0, *s["color"], s["period"], s["phase"], s["duty"],
                           s["papi"], *s["pos"], s["parts"])
    out += struct.pack("<H", len(colliders))
    for c in colliders:
        out += struct.pack("<BB12f3f", c["shape"], c["group"], *c["cf"], *c["size"])
    lights = light_records(model)
    out += struct.pack("<B", len(lights))
    for lt in lights:
        out += struct.pack("<BB3BBfff3f3f2f", lt["type"], lt["group"], *lt["color"], lt["shadows"], lt["brightness"], lt["range"],
                           lt["angle"], *lt["pos"], *lt["dir"], *lt["surface"])
    out += struct.pack("<H", len(texts))
    for t in texts:
        out += pstr(t["text"])
        out += struct.pack("<8B4B4f12f", t["font"], t["flags"], t["xAlign"], t["yAlign"], t["group"], t["layer"], t["pad"], 0,
                           *t["color"], t["lightInfluence"], t["brightness"], t["textSize"], t["pxPerMetre"],
                           *t["origin"], *t["u"], *t["v"], *t["normal"])
    beams = beam_records(model)
    out += struct.pack("<B", len(beams))
    for b in beams:
        out += struct.pack("<B3f3fff3BB3f", b["group"], *b["p0"], *b["p1"], b["w0"], b["w1"], *b["color"], 0, *b["transparency"])
    out += mesh_bytes
    fname = "%s.stm" % model.name
    with open(os.path.join(out_dir, fname), "wb") as f:
        f.write(out)
    tri = {"lod%d" % l: mesh.triangle_count(l) for l in range(3)}
    shapes = {}
    for c in colliders:
        k = ["block", "ball", "cylinder", "wedge", "cornerWedge"][c["shape"]]
        shapes[k] = shapes.get(k, 0) + 1
    report = {
        "file": fname, "bytes": len(out), "parts": model.part_count, "partCountAttribute": num(a.get("PartCount")),
        "renderedParts": stats["rendered"], "invisibleParts": stats["invisible"], "paintFrames": stats["frames"],
        "paintPolygons": stats["paintPolygons"], "paintLayers": stats["layers"], "labels": len(texts),
        "translucentPaintUnderText": stats["paintUnderText"], "translucentPaintOverText": stats["paintOverText"],
        "vertices": nverts, "indices": nidx, "indexSize": isize, "stepMm": round(step * 1000, 3), "triangles": tri,
        "groups": [g["name"] for g in model.groups[1:]], "lampSlots": len(slots),
        "lampKinds": {k: sum(1 for s in slots if s["kind"] == i) for i, k in enumerate(["accent", "pad", "papi", "blink"])},
        "colliders": len(colliders), "colliderShapes": shapes, "pads": len(pads), "lights": len(lights), "beams": len(beams),
        "bounds": [[round(x, 2) for x in bounds[0]], [round(x, 2) for x in bounds[1]]],
        "counts": counts,
    }
    return fname, report


def write_assets(stream, out_dir):
    print("reading the rbxmx (streamed) ...")
    models = read_models(stream)
    os.makedirs(out_dir, exist_ok=True)
    report = {}
    total = 0
    for name in MODELS + [SITE]:
        model = Model(models[name], name == SITE)
        fname, rep = write_model(model, out_dir)
        report[name] = rep
        total += rep["bytes"]
        print("%-16s %5d parts (%4d drawn), %5d frames -> %5d polygons (%d layers), %3d labels, %6d vertices, "
              "triangles LOD0 %6d / LOD1 %6d / LOD2 %5d, %2d lamp slots, %3d colliders, %2d lights, %8d B"
              % (name, rep["parts"], rep["renderedParts"], rep["paintFrames"], rep["paintPolygons"], rep["paintLayers"], rep["labels"],
                 rep["vertices"], rep["triangles"]["lod0"], rep["triangles"]["lod1"], rep["triangles"]["lod2"], rep["lampSlots"],
                 rep["colliders"], rep["lights"], rep["bytes"]))
    print("%d files, %d bytes" % (len(report), total))
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--rbxmx", help="Assets.rbxmx (default: stream it out of --rar with bsdtar)")
    ap.add_argument("--rar", default=os.path.join(ROOT, "roblox", "source", "Assets.rar"))
    ap.add_argument("--out", default=os.path.join(ROOT, "app", "src", "main", "assets", "stations"))
    ap.add_argument("--report", help="write per-model statistics as JSON")
    args = ap.parse_args()
    if args.rbxmx:
        with open(args.rbxmx, "rb") as stream:
            report = write_assets(stream, args.out)
    else:
        proc = subprocess.Popen(["bsdtar", "-xOf", args.rar, "Assets.rbxmx"], stdout=subprocess.PIPE)
        try:
            report = write_assets(proc.stdout, args.out)
        finally:
            proc.stdout.close()
            if proc.wait() != 0:
                sys.exit("bsdtar failed to read %s" % args.rar)
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1, sort_keys=True)


if __name__ == "__main__":
    main()
