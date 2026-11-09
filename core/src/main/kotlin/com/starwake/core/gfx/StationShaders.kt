package com.starwake.core.gfx

import com.starwake.core.content.StationModel
import com.starwake.core.station.StationInstance

/**
 * GLSL ES 3.00 shaders of the Roblox stations (StationRenderer). Same lighting model and ACES tone
 * map as the ships (Shaders.shipFs), plus what the stations need on the GPU: rotating groups and
 * build stages (a 3x4 transform and a mode per group), lamp slots (colour and opacity switched by
 * the CPU only when a lamp changes), paint with its SurfaceGui light influence and a depth bias per
 * paint layer, nearby Roblox lights per pixel, SDF sign text, the Spire's light beam and the far
 * marker.
 */
object StationShaders {
    private const val HEADER = "#version 300 es\n"
    const val MAX_GROUPS = StationModel.MAX_GROUPS
    const val LAMPS = StationInstance.LAMP_SLOTS
    const val MAX_LIGHTS = 8

    /** u_pass values: what the draw call shows (the vertex shader drops what is not part of it). */
    const val PASS_OPAQUE = 0
    const val PASS_TRANSLUCENT = 1
    const val PASS_LAMP = 2
    const val PASS_HOLOGRAM = 3

    private const val TONEMAP = """
vec3 tonemap(vec3 c) {
    c *= u_exposure;
    vec3 t = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
    return pow(t, vec3(0.4545));
}
"""

    /** Roblox lights near the camera, per pixel: point (w of u_lightCol < -1.5), spot and surface lights. */
    private const val LIGHTS = """
uniform int u_lightCount;
uniform vec4 u_lightPos[$MAX_LIGHTS];  // camera-relative position, w = range
uniform vec4 u_lightCol[$MAX_LIGHTS];  // linear colour * brightness, w = cos(half angle) or -2 (point)
uniform vec4 u_lightDir[$MAX_LIGHTS];  // unit direction (spot/surface)
vec3 localLights(vec3 P, vec3 N, vec3 diff) {
    vec3 acc = vec3(0.0);
    for (int i = 0; i < $MAX_LIGHTS; i++) {
        if (i >= u_lightCount) break;
        vec3 L = u_lightPos[i].xyz - P;
        float d = length(L);
        float range = u_lightPos[i].w;
        if (d >= range) continue;
        L /= max(d, 1e-3);
        float k = 1.0 - d / range;
        float att = k * k;
        float cosHalf = u_lightCol[i].w;
        if (cosHalf > -1.5) {
            float c = dot(-L, u_lightDir[i].xyz);
            att *= smoothstep(cosHalf - 0.08, cosHalf + 0.05, c);
        }
        acc += u_lightCol[i].rgb * att * max(dot(N, L), 0.0);
    }
    return diff * acc;
}
"""

    /**
     * Quantised station vertices (StationModel.Mesh): u16 x, y, z + w (low position bits, material,
     * group), normal snorm8, sRGB colour + opacity, lamp slot (a separate u8 attribute at byte 11).
     */
    val meshVs = HEADER + """
layout(location = 0) in vec4 a_pos;
layout(location = 1) in vec4 a_normal;
layout(location = 2) in vec4 a_color;
layout(location = 3) in float a_slot;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform vec4 u_quant;
uniform vec4 u_groups[${MAX_GROUPS * 3}];
uniform vec4 u_groupMode[$MAX_GROUPS];
uniform vec4 u_lamps[$LAMPS];
uniform vec4 u_mat[16];
uniform int u_pass;
uniform float u_decal;
uniform vec3 u_holo;
out vec3 v_pos;
out vec3 v_sp;
out vec3 v_normal;
flat out vec3 v_albedo;
flat out vec4 v_params;
flat out float v_alpha;
flat out float v_field;
void main() {
    float w = a_pos.w;
    float w1 = floor(w * 0.125);
    float lx = w - w1 * 8.0;
    float w2 = floor(w1 * 0.125);
    float ly = w1 - w2 * 8.0;
    float w3 = floor(w2 * 0.125);
    float lz = w2 - w3 * 8.0;
    float gf = floor(w3 * 0.0625);
    int mi = int(w3 - gf * 16.0 + 0.5);
    int gi = int(gf + 0.5);
    vec4 mode = u_groupMode[gi];
    bool hidden = u_pass == ${PASS_HOLOGRAM} ? mode.y <= 0.0 : mode.x < 0.5;
    vec3 q = a_pos.xyz * 8.0 + vec3(lx, ly, lz);
    vec4 p = vec4(u_quant.xyz + q * u_quant.w, 1.0);
    vec4 r0 = u_groups[gi * 3];
    vec4 r1 = u_groups[gi * 3 + 1];
    vec4 r2 = u_groups[gi * 3 + 2];
    vec3 sp = vec3(dot(r0, p), dot(r1, p), dot(r2, p));
    vec3 n = a_normal.xyz;
    vec3 sn = vec3(dot(r0.xyz, n), dot(r1.xyz, n), dot(r2.xyz, n));
    vec4 wp = u_model * vec4(sp, 1.0);
    v_pos = wp.xyz;
    v_sp = sp;
    v_normal = mat3(u_model) * sn;
    vec3 col = a_color.rgb;
    float alpha = a_color.a;
    vec4 params = u_mat[mi];
    float layer = 0.0;
    if (mi >= ${StationModel.MAT_PAINT1}) {
        // paint: SurfaceGui LightInfluence (low nibble) and (1 - LI) * Brightness (high nibble)
        float hi = floor(a_slot * 0.0625);
        params = vec4(0.0, hi * 0.15, (a_slot - hi * 16.0) / 15.0, params.w);
        layer = float(mi - ${StationModel.MAT_PAINT1 - 1});
    } else {
        int slot = int(a_slot + 0.5);
        if (slot > 0) {
            vec4 lamp = u_lamps[slot];
            if (lamp.r >= 0.0) col = lamp.rgb;
            if (lamp.a >= 0.0) alpha = lamp.a;
        }
    }
    float field = mi == ${StationModel.MAT_FORCEFIELD} ? 1.0 : 0.0;
    if (u_pass == ${PASS_HOLOGRAM}) {
        // the next build stage as an orange ForceField hologram
        col = u_holo;
        params = u_mat[${StationModel.MAT_FORCEFIELD}];
        params.y *= 0.4; // many stacked faces of one stage add up: keep the glow orange
        alpha = min(alpha, mode.y);
        field = 1.0;
    }
    v_albedo = pow(col, vec3(2.2));
    v_params = params;
    v_alpha = alpha;
    v_field = field;
    gl_Position = u_viewProj * wp;
    gl_Position.z -= u_decal * layer * gl_Position.w;
    if (hidden) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
"""

    /**
     * Lit like the ships: sun, planet fill, ambient, environment reflections, plus the nearby Roblox
     * lights. params: x metallic, y emission (Neon, self-lit paint), z lit share (SurfaceGui
     * LightInfluence; 0 for Neon), w roughness. Output premultiplied (the blended passes use ONE,
     * ONE_MINUS_SRC_ALPHA); ForceField adds a rim glow with alpha 0.
     */
    val meshFs = HEADER + """
precision highp float;
in vec3 v_pos;
in vec3 v_sp;
in vec3 v_normal;
flat in vec3 v_albedo;
flat in vec4 v_params;
flat in float v_alpha;
flat in float v_field;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_fillDir;
uniform vec3 u_fillColor;
uniform vec3 u_ambient;
uniform float u_exposure;
uniform float u_envLod;
uniform float u_fieldPhase;
uniform samplerCube u_env;
out vec4 o_color;
""" + LIGHTS + TONEMAP + """
void main() {
    vec3 N = normalize(v_normal);
    vec3 V = normalize(-v_pos);
    float ndv = max(dot(N, V), 0.001);
    if (v_field > 0.5) {
        float rim = 1.0 - ndv;
        float wave = sin(v_sp.y * 0.21 + u_fieldPhase) * sin(v_sp.x * 0.13 - v_sp.z * 0.17 - 2.0 * u_fieldPhase);
        vec3 glow = v_albedo * v_params.y * v_alpha * (0.06 + 0.5 * rim * rim) * (1.0 + 0.3 * wave);
        o_color = vec4(tonemap(glow), 0.0);
        return;
    }
    float metal = v_params.x;
    float rough = v_params.w;
    vec3 alb = v_albedo;
    float ndl = max(dot(N, u_sunDir), 0.0);
    vec3 H = normalize(u_sunDir + V);
    float ndh = max(dot(N, H), 0.0);
    float gloss = 1.0 - rough;
    float specPow = exp2(gloss * 10.0 + 1.0);
    float specNorm = (specPow + 8.0) * 0.0398;
    vec3 F0 = mix(vec3(0.04), alb, metal);
    float fres = pow(1.0 - ndv, 5.0);
    vec3 F = F0 + (vec3(1.0) - F0) * fres * gloss;
    vec3 diff = alb * (1.0 - metal);
    vec3 col = (diff * ndl + F * (specNorm * pow(ndh, specPow) * ndl)) * u_sunColor;
    col += diff * max(dot(N, u_fillDir), 0.0) * u_fillColor;
    col += (diff + F0 * 0.5) * u_ambient * (0.75 + 0.25 * N.y);
    col += localLights(v_pos, N, diff + F0 * 0.3);
    vec3 R = reflect(-V, N);
    vec3 env = textureLod(u_env, R, rough * u_envLod).rgb;
    env *= env;
    col += env * F * (0.6 + 1.4 * metal) * 1.6;
    col = col * v_params.z + alb * v_params.y;
    o_color = vec4(tonemap(col) * v_alpha, v_alpha);
}
"""

    /**
     * Sign text: SDF glyph quads in station space (StationRenderer lays them out). a_extra: group,
     * paint layer, light influence (0..255), emission * 100.
     */
    val textVs = HEADER + """
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec2 a_uv;
layout(location = 2) in vec4 a_color;
layout(location = 3) in vec4 a_normal;
layout(location = 4) in vec4 a_extra;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform vec4 u_groups[${MAX_GROUPS * 3}];
uniform vec4 u_groupMode[$MAX_GROUPS];
uniform float u_decal;
out vec3 v_pos;
out vec2 v_uv;
flat out vec3 v_normal;
flat out vec4 v_color;
flat out vec2 v_light;
void main() {
    int gi = int(a_extra.x + 0.5);
    vec4 p = vec4(a_pos, 1.0);
    vec4 r0 = u_groups[gi * 3];
    vec4 r1 = u_groups[gi * 3 + 1];
    vec4 r2 = u_groups[gi * 3 + 2];
    vec3 sp = vec3(dot(r0, p), dot(r1, p), dot(r2, p));
    vec3 n = a_normal.xyz;
    vec3 sn = vec3(dot(r0.xyz, n), dot(r1.xyz, n), dot(r2.xyz, n));
    vec4 wp = u_model * vec4(sp, 1.0);
    v_pos = wp.xyz;
    v_uv = a_uv;
    v_normal = normalize(mat3(u_model) * sn);
    v_color = vec4(pow(a_color.rgb, vec3(2.2)), a_color.a);
    v_light = vec2(a_extra.z / 255.0, a_extra.w / 100.0);
    gl_Position = u_viewProj * wp;
    gl_Position.z -= u_decal * (a_extra.y + 1.0) * gl_Position.w;
    if (u_groupMode[gi].x < 0.5) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
}
"""

    val textFs = HEADER + """
precision highp float;
in vec3 v_pos;
in vec2 v_uv;
flat in vec3 v_normal;
flat in vec4 v_color;
flat in vec2 v_light;
uniform sampler2D u_font;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambient;
uniform float u_exposure;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    float d = texture(u_font, v_uv).r;
    float w = max(fwidth(d) * 0.7, 1e-4);
    float a = clamp((d - 0.5) / w + 0.5, 0.0, 1.0) * v_color.a;
    if (a <= 0.003) discard;
    vec3 N = v_normal;
    vec3 lit = v_color.rgb * (max(dot(N, u_sunDir), 0.0) * u_sunColor + u_ambient * 2.5);
    vec3 col = lit * v_light.x + v_color.rgb * v_light.y;
    o_color = vec4(tonemap(col) * a, a);
}
"""

    /** Light beam (Roblox Beam, FaceCamera): a camera-facing ribbon; a_tw = (t along, side -1/1). */
    val beamVs = HEADER + """
layout(location = 0) in vec2 a_tw;
uniform mat4 u_viewProj;
uniform vec3 u_p0;
uniform vec3 u_p1;
uniform vec2 u_width;
uniform vec3 u_alpha; // opacity at t = 0, 0.6, 1
out float v_alpha;
out float v_side;
void main() {
    float t = a_tw.x;
    vec3 P = mix(u_p0, u_p1, t);
    vec3 dir = u_p1 - u_p0;
    vec3 side = cross(dir, -P);
    float l = length(side);
    side = l > 1e-6 ? side / l : vec3(1.0, 0.0, 0.0);
    P += side * a_tw.y * 0.5 * mix(u_width.x, u_width.y, t);
    v_alpha = t < 0.6 ? mix(u_alpha.x, u_alpha.y, t / 0.6) : mix(u_alpha.y, u_alpha.z, (t - 0.6) / 0.4);
    v_side = a_tw.y;
    gl_Position = u_viewProj * vec4(P, 1.0);
}
"""

    val beamFs = HEADER + """
precision highp float;
in float v_alpha;
in float v_side;
uniform vec3 u_color;
uniform float u_exposure;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    float edge = 1.0 - abs(v_side);
    float a = v_alpha * edge * edge;
    o_color = vec4(tonemap(u_color) * a, 0.0);
}
"""

    /** Far marker: one camera-facing quad with a soft dot (a_corner in -1..1). */
    val markerVs = HEADER + """
layout(location = 0) in vec2 a_corner;
uniform mat4 u_viewProj;
uniform vec3 u_centre;
uniform vec3 u_right;
uniform vec3 u_up;
out vec2 v_uv;
void main() {
    v_uv = a_corner;
    gl_Position = u_viewProj * vec4(u_centre + u_right * a_corner.x + u_up * a_corner.y, 1.0);
}
"""

    val markerFs = HEADER + """
precision highp float;
in vec2 v_uv;
uniform vec4 u_color;
out vec4 o_color;
void main() {
    float r = length(v_uv);
    float core = smoothstep(0.42, 0.22, r);
    float halo = (1.0 - smoothstep(0.0, 1.0, r)) * 0.6;
    float a = max(core, halo * halo) * u_color.a;
    o_color = vec4(u_color.rgb * a, 0.0);
}
"""
}
