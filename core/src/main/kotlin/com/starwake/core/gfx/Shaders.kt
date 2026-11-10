package com.starwake.core.gfx

import com.starwake.core.content.ShipModel

/**
 * All GLSL ES 3.00 shaders. Lighting is done in linear space and tone-mapped (ACES fit) + gamma
 * encoded in each shader, so no post-processing pass (and no extra framebuffer bandwidth) is needed.
 */
object Shaders {
    private const val HEADER = "#version 300 es\n"

    private const val TONEMAP = """
vec3 tonemap(vec3 c) {
    c *= u_exposure;
    vec3 t = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
    return pow(t, vec3(0.4545));
}
"""

    /** PCG-style integer hash based value noise (stable on mobile GPUs, unlike sin-hash). */
    private const val NOISE = """
// the hash needs 32-bit integer wraparound: fragment ints default to mediump (16-bit on many GPUs)
precision highp int;
uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}
float hash3(ivec3 p) {
    uint h = pcg(uint(p.x) + pcg(uint(p.y) + pcg(uint(p.z))));
    return float(h) * (1.0 / 4294967295.0);
}
float vnoise(vec3 p) {
    vec3 fl = floor(p);
    ivec3 i = ivec3(fl);
    vec3 f = p - fl;
    vec3 u = f * f * (3.0 - 2.0 * f);
    float a = hash3(i);
    float b = hash3(i + ivec3(1, 0, 0));
    float c = hash3(i + ivec3(0, 1, 0));
    float d = hash3(i + ivec3(1, 1, 0));
    float e = hash3(i + ivec3(0, 0, 1));
    float g = hash3(i + ivec3(1, 0, 1));
    float h = hash3(i + ivec3(0, 1, 1));
    float k = hash3(i + ivec3(1, 1, 1));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), mix(mix(e, g, u.x), mix(h, k, u.x), u.y), u.z);
}
float fbm(vec3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += vnoise(p) * a;
        p = p * 2.03 + vec3(17.1, 9.3, 4.7);
        a *= 0.5;
    }
    return s / 0.96875;
}
"""

    // ---------------------------------------------------------------- lit meshes (stations, ring debris)

    private const val LIT_VS = """
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_normal;
layout(location = 2) in vec2 a_mat;
#ifdef INSTANCED
// ring debris (RingDebris.instances): position and scale, rotation quaternion, squash and shade
layout(location = 4) in vec4 i_posScale;
layout(location = 5) in vec4 i_rot;
layout(location = 6) in vec4 i_shape;
#endif
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform vec4 u_palette[10];
uniform vec4 u_matParams[10];
uniform float u_time;
out vec3 v_pos;
out vec3 v_normal;
flat out vec3 v_albedo;
flat out vec4 v_params;
void main() {
    int mi = int(a_mat.x + 0.5);
    vec3 p = a_pos;
    vec3 n = a_normal;
#ifdef INSTANCED
    // a squashed rock (Roblox: sphere.mesh scaled by its radii), turned by its quaternion
    p *= i_shape.xyz;
    n = normalize(n / i_shape.xyz);
    p += 2.0 * cross(i_rot.xyz, cross(i_rot.xyz, p) + i_rot.w * p);
    n += 2.0 * cross(i_rot.xyz, cross(i_rot.xyz, n) + i_rot.w * n);
    p = p * i_posScale.w + i_posScale.xyz;
#endif
    vec4 wp = u_model * vec4(p, 1.0);
    v_pos = wp.xyz;
    v_normal = mat3(u_model) * n;
    vec4 pal = u_palette[mi];
    v_albedo = pal.rgb * a_mat.y;
#ifdef INSTANCED
    // the rock's shade (VertexColor = tint x shade)
    v_albedo *= i_shape.w;
#endif
    v_params = vec4(u_matParams[mi].xyz, pal.a);
    gl_Position = u_viewProj * wp;
}
"""

    private const val LIT_FS = """
precision highp float;
in vec3 v_pos;
in vec3 v_normal;
flat in vec3 v_albedo;
flat in vec4 v_params; // x metallic, y emissive, z engine-driven, w roughness
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_fillDir;
uniform vec3 u_fillColor;
uniform vec3 u_ambient;
uniform float u_engine;
uniform float u_exposure;
uniform float u_envLod;
uniform samplerCube u_env;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    vec3 N = normalize(v_normal);
    vec3 V = normalize(-v_pos);
    float metal = v_params.x;
    float rough = v_params.w;
    vec3 alb = v_albedo;
    float ndl = max(dot(N, u_sunDir), 0.0);
    vec3 H = normalize(u_sunDir + V);
    float ndh = max(dot(N, H), 0.0);
    float ndv = max(dot(N, V), 0.001);
    float gloss = 1.0 - rough;
    float specPow = exp2(gloss * 10.0 + 1.0);
    float specNorm = (specPow + 8.0) * 0.0398;
    vec3 F0 = mix(vec3(0.04), alb, metal);
    float fres = pow(1.0 - ndv, 5.0);
    vec3 F = F0 + (vec3(1.0) - F0) * fres * gloss;
    vec3 diff = alb * (1.0 - metal);
    vec3 col = (diff * ndl + F * (specNorm * pow(ndh, specPow) * ndl)) * u_sunColor;
    col += diff * max(dot(N, u_fillDir), 0.0) * u_fillColor;
    col += diff * u_ambient * (0.75 + 0.25 * N.y);
    vec3 R = reflect(-V, N);
    vec3 env = textureLod(u_env, R, rough * u_envLod).rgb;
    env *= env;
    col += env * F * (0.6 + 1.4 * metal) * 1.6;
    float emis = v_params.y * mix(1.0, u_engine, v_params.z);
    col += v_albedo * emis;
    o_color = vec4(tonemap(col), 1.0);
}
"""

    fun litVs(instanced: Boolean) = HEADER + (if (instanced) "#define INSTANCED\n" else "") + LIT_VS
    val litFs = HEADER + LIT_FS

    // ---------------------------------------------------------------- Roblox ships (ShipModel)

    /** Bones the ship shader holds (the largest rig, Brannock, has 36). */
    const val SHIP_MAX_BONES = ShipModel.MAX_BONES

    /**
     * Quantised ship vertices (see ShipModel): position u16 x3 + material/bone, normal snorm8,
     * sRGB colour + opacity. Each vertex is moved by its bone (3x4, landing gear) in ship space,
     * then by u_model (camera-relative ship pose).
     */
    val shipVs = HEADER + """
layout(location = 0) in vec4 a_pos;
layout(location = 1) in vec4 a_normal;
layout(location = 2) in vec4 a_color;
uniform mat4 u_viewProj;
uniform mat4 u_model;
uniform vec4 u_quant;
uniform vec4 u_bones[$SHIP_MAX_BONES * 3];
uniform vec4 u_mat[16];
out vec3 v_pos;
out vec3 v_normal;
flat out vec3 v_albedo;
flat out vec4 v_params;
flat out float v_alpha;
flat out float v_field;
void main() {
    float bone = floor(a_pos.w * (1.0 / 256.0));
    int mi = int(a_pos.w - bone * 256.0 + 0.5);
    int bi = int(bone + 0.5) * 3;
    vec4 p = vec4(u_quant.xyz + a_pos.xyz * u_quant.w, 1.0);
    vec4 r0 = u_bones[bi];
    vec4 r1 = u_bones[bi + 1];
    vec4 r2 = u_bones[bi + 2];
    vec3 sp = vec3(dot(r0, p), dot(r1, p), dot(r2, p));
    vec3 n = a_normal.xyz;
    vec3 sn = vec3(dot(r0.xyz, n), dot(r1.xyz, n), dot(r2.xyz, n));
    vec4 wp = u_model * vec4(sp, 1.0);
    v_pos = wp.xyz;
    v_normal = mat3(u_model) * sn;
    v_albedo = pow(a_color.rgb, vec3(2.2));
    v_params = u_mat[mi];
    v_alpha = a_color.a;
    v_field = mi == ${ShipModel.MAT_FORCEFIELD} ? 1.0 : 0.0;
    gl_Position = u_viewProj * wp;
}
"""

    /**
     * The lit model of the rocks and stations plus what Roblox materials need: Neon (and the engine
     * glow discs) is unlit, its colour times the glow; glass is blended with its opacity. Output is
     * premultiplied (the glass pass blends ONE, ONE_MINUS_SRC_ALPHA; opaque parts have alpha 1).
     *
     * ForceField (the holograms aboard the KESSOCK, LOCHRAN and MORVEN) is see-through even at
     * Transparency 0 in Roblox: a glow in the part colour, faint face-on and bright towards the rim,
     * with a drifting shimmer, that only adds light. Here: strength = 1 - Transparency (the vertex
     * alpha), the shimmer two crossed waves anchored to the ship, alpha 0 so the blend adds it to
     * what is behind.
     */
    val shipFs = HEADER + """
precision highp float;
in vec3 v_pos;
in vec3 v_normal;
flat in vec3 v_albedo;
flat in vec4 v_params; // x metallic, y emissive, z engine-driven, w roughness
flat in float v_alpha;
flat in float v_field;
uniform mat4 u_model;
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_fillDir;
uniform vec3 u_fillColor;
uniform vec3 u_ambient;
uniform float u_engine;
uniform float u_exposure;
uniform float u_envLod;
uniform float u_fieldPhase;
uniform samplerCube u_env;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    vec3 N = normalize(v_normal);
    vec3 V = normalize(-v_pos);
    float ndv = max(dot(N, V), 0.001);
    if (v_field > 0.5) {
        vec3 sp = (v_pos - u_model[3].xyz) * mat3(u_model); // ship space
        float rim = 1.0 - ndv;
        float wave = sin(sp.y * 5.0 + u_fieldPhase) * sin(sp.x * 3.0 - sp.z * 4.0 - 2.0 * u_fieldPhase);
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
    vec3 R = reflect(-V, N);
    vec3 env = textureLod(u_env, R, rough * u_envLod).rgb;
    env *= env;
    col += env * F * (0.6 + 1.4 * metal) * 1.6;
    float emis = v_params.y * mix(1.0, u_engine, v_params.z);
    if (v_params.y > 0.0) col = alb * emis;
    o_color = vec4(tonemap(col) * v_alpha, v_alpha);
}
"""

    // ---------------------------------------------------------------- background (cubemap)

    val skyVs = HEADER + """
layout(location = 0) in vec2 a_pos;
uniform vec3 u_fwd;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec2 u_shift;
out vec3 v_dir;
void main() {
    v_dir = u_fwd + (a_pos.x - u_shift.x) * u_right + (a_pos.y - u_shift.y) * u_up;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

    val skyFs = HEADER + """
precision mediump float;
in vec3 v_dir;
uniform samplerCube u_env;
uniform float u_exposure;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    vec3 c = texture(u_env, v_dir).rgb;
    o_color = vec4(tonemap(c * c), 1.0);
}
"""

    // ---------------------------------------------------------------- stars

    val starVs = HEADER + """
layout(location = 0) in vec3 a_dir;
layout(location = 1) in vec4 a_colSize;
uniform mat4 u_viewProj;
uniform float u_pxScale;
uniform float u_bright;
out vec3 v_col;
void main() {
    vec4 c = u_viewProj * vec4(a_dir, 0.0);
    gl_Position = vec4(c.xy, 0.0, c.w);
    float size = a_colSize.w * u_pxScale;
    // keep sub-pixel stars at a stable 1.5px footprint but dimmer (no shimmering)
    float fade = clamp(size / 1.5, 0.15, 1.0);
    gl_PointSize = max(size, 1.5);
    v_col = a_colSize.rgb * u_bright * fade;
}
"""

    val starFs = HEADER + """
precision mediump float;
in vec3 v_col;
out vec4 o_color;
void main() {
    vec2 d = gl_PointCoord * 2.0 - 1.0;
    float a = max(0.0, 1.0 - dot(d, d));
    o_color = vec4(v_col * a * a, 1.0);
}
"""

    // ---------------------------------------------------------------- space dust streaks

    val dustVs = HEADER + """
layout(location = 0) in vec3 a_seed;
layout(location = 1) in vec2 a_corner;
uniform mat4 u_viewProj;
uniform vec3 u_camMod;
uniform vec3 u_streak;
uniform float u_box;
uniform float u_width;
uniform float u_aspect;
uniform float u_intensity;
out float v_alpha;
out float v_side;
void main() {
    vec3 p = mod(a_seed - u_camMod, u_box) - 0.5 * u_box;
    vec3 tp = p + u_streak;
    vec4 h = u_viewProj * vec4(p, 1.0);
    vec4 t = u_viewProj * vec4(tp, 1.0);
    float dist = length(p);
    float fade = (1.0 - smoothstep(0.28 * u_box, 0.5 * u_box, dist)) * smoothstep(1.5, 5.0, dist);
    if (h.w < 0.5 || t.w < 0.5) { fade = 0.0; t = h; }
    vec2 hs = h.xy / h.w;
    vec2 ts = t.xy / t.w;
    vec2 dir = (hs - ts) * vec2(u_aspect, 1.0);
    float len = length(dir);
    dir = len > 1e-5 ? dir / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-dir.y, dir.x) / vec2(u_aspect, 1.0);
    vec2 along = dir / vec2(u_aspect, 1.0);
    bool head = a_corner.x < 0.5;
    vec4 base = head ? h : t;
    base.xy += nrm * a_corner.y * u_width * base.w;
    base.xy += along * (head ? 1.0 : -1.0) * u_width * base.w;
    gl_Position = base;
    v_alpha = fade * u_intensity * (head ? 1.0 : 0.25);
    v_side = a_corner.y;
}
"""

    val dustFs = HEADER + """
precision mediump float;
in float v_alpha;
in float v_side;
uniform vec3 u_color;
out vec4 o_color;
void main() {
    float a = (1.0 - abs(v_side)) * v_alpha;
    o_color = vec4(u_color * a, 1.0);
}
"""

    // ---------------------------------------------------------------- additive glow sprites

    val glowVs = HEADER + """
layout(location = 0) in vec3 a_pos;
layout(location = 1) in vec3 a_uvShape;
layout(location = 2) in vec4 a_color;
uniform mat4 u_viewProj;
out vec2 v_uv;
flat out float v_shape;
out vec4 v_color;
void main() {
    v_uv = a_uvShape.xy;
    v_shape = a_uvShape.z;
    v_color = a_color;
    gl_Position = u_viewProj * vec4(a_pos, 1.0);
}
"""

    val glowFs = HEADER + """
precision mediump float;
in vec2 v_uv;
flat in float v_shape;
in vec4 v_color;
uniform float u_time;
out vec4 o_color;
void main() {
    vec3 c = v_color.rgb;
    float a;
    if (v_shape < 0.5) {
        float r2 = dot(v_uv, v_uv);
        a = max(exp(-r2 * 5.0) - 0.0067, 0.0);
    } else if (v_shape < 1.5) {
        // exhaust plume: x = 0 at nozzle .. 1 at tail, y = -1..1 across
        float x = v_uv.x;
        float w = mix(1.0, 0.25, x);
        float d = abs(v_uv.y) / w;
        float flick = 0.88 + 0.12 * sin(u_time * 61.0 + x * 23.0);
        a = (1.0 - smoothstep(0.0, 1.0, d)) * pow(1.0 - x, 1.6) * flick;
        c += vec3(0.9) * exp(-d * d * 7.0) * pow(1.0 - x, 4.0);
    } else {
        // sun: disc + corona + horizontal streak
        float r = length(v_uv);
        a = (1.0 - smoothstep(0.06, 0.075, r)) * 3.0 + exp(-r * 9.0) * 0.9 + exp(-r * 30.0) * 1.5;
        a += exp(-abs(v_uv.y) * 90.0) * exp(-abs(v_uv.x) * 2.5) * 0.45;
    }
    o_color = vec4(c * a * v_color.a, 1.0);
}
"""

    // ---------------------------------------------------------------- UI (shapes + SDF text)

    val uiVs = HEADER + """
layout(location = 0) in vec2 a_pos;
layout(location = 1) in vec2 a_local;
layout(location = 2) in vec4 a_color;
layout(location = 3) in vec4 a_p0;
layout(location = 4) in vec4 a_p1;
uniform vec2 u_screen;
out vec2 v_local;
out vec4 v_color;
flat out vec4 v_p0;
flat out vec4 v_p1;
void main() {
    v_local = a_local;
    v_color = a_color;
    v_p0 = a_p0;
    v_p1 = a_p1;
    gl_Position = vec4(a_pos.x / u_screen.x * 2.0 - 1.0, 1.0 - a_pos.y / u_screen.y * 2.0, 0.0, 1.0);
}
"""

    val uiFs = HEADER + """
precision highp float;
in vec2 v_local;
in vec4 v_color;
flat in vec4 v_p0; // x shape, ...
flat in vec4 v_p1;
uniform sampler2D u_font;
out vec4 o_color;
void main() {
    float shape = v_p0.x;
    float a;
    if (shape < 0.5) {
        float d = texture(u_font, v_local).r;
        a = clamp((d - 0.5 + v_p0.z) * v_p0.y + 0.5, 0.0, 1.0);
    } else {
        float d;
        if (shape < 1.5) {
            vec2 b = v_p0.yz;
            float r = v_p0.w;
            vec2 p = v_local;
            if (r >= 0.0) {
                vec2 q = abs(p) - b + r;
                d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            } else {
                vec2 q = abs(p) - b;
                float db = max(q.x, q.y);
                float dc = (abs(p.x) + abs(p.y) - (b.x + b.y + r)) * 0.70710678;
                d = max(db, dc);
            }
        } else if (shape < 2.5) {
            d = length(v_local) - v_p0.y;
        } else {
            // isosceles triangle, tip up (-y): half base p0.y, height p0.z, centred on the local
            // origin (iq's sdTriangleIsosceles with the tip at (0, -h/2))
            vec2 q = v_p0.yz;
            vec2 p = vec2(abs(v_local.x), v_local.y + q.y * 0.5);
            vec2 e = p - q * clamp(dot(p, q) / dot(q, q), 0.0, 1.0);
            vec2 f = p - q * vec2(clamp(p.x / q.x, 0.0, 1.0), 1.0);
            vec2 m = min(vec2(dot(e, e), -(p.x * q.y - p.y * q.x)), vec2(dot(f, f), -(p.y - q.y)));
            d = -sqrt(m.x) * sign(m.y);
        }
        float s = v_p1.x;
        if (s > 0.0) d = abs(d + s * 0.5) - s * 0.5;
        a = clamp(0.5 - d / max(v_p1.y, 1.0), 0.0, 1.0);
        // one half of the triangle only (p0.w -1 left, +1 right): a hard cut along the seam, so the
        // two halves of a two-tone arrow tile without a gap or a double edge
        if (shape > 2.5 && ((v_p0.w < 0.0 && v_local.x >= 0.0) || (v_p0.w > 0.0 && v_local.x < 0.0))) a = 0.0;
    }
    o_color = vec4(v_color.rgb, v_color.a * a);
}
"""

    // ---------------------------------------------------------------- one-time texture bakes

    val bakeVs = HEADER + """
layout(location = 0) in vec2 a_pos;
out vec2 v_ndc;
void main() {
    v_ndc = a_pos;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

    val nebulaFs = HEADER + """
precision highp float;
in vec2 v_ndc;
uniform vec3 u_fz;
uniform vec3 u_fx;
uniform vec3 u_fy;
out vec4 o_color;
""" + NOISE + """
void main() {
    vec3 d = normalize(u_fz + v_ndc.x * u_fx + v_ndc.y * u_fy);
    vec3 gN = normalize(vec3(0.28, 0.9, 0.33));
    float gl = dot(d, gN);
    float band = exp(-gl * gl * 10.0);
    float n1 = fbm(d * 2.1 + vec3(3.1, 1.7, 5.3));
    float n2 = fbm(d * 4.7 + vec3(11.2, 3.4, 7.7));
    float n3 = fbm(d * 10.0 + vec3(-4.0, 2.0, 9.0));
    float cloud = smoothstep(0.36, 0.86, n1 * 0.72 + n2 * 0.28);
    vec3 c = vec3(0.004, 0.006, 0.013);
    vec3 neb1 = vec3(0.13, 0.05, 0.24);
    vec3 neb2 = vec3(0.02, 0.15, 0.20);
    vec3 neb3 = vec3(0.30, 0.10, 0.05);
    c += mix(neb1, neb2, smoothstep(0.35, 0.68, n2)) * cloud * (0.25 + 1.1 * band);
    c += neb3 * smoothstep(0.6, 0.9, n3) * cloud * band * 0.9;
    c += vec3(0.075, 0.068, 0.062) * band * (0.35 + 0.65 * n2);
    c *= 1.0 - 0.8 * smoothstep(0.5, 0.74, n3) * band;
    o_color = vec4(sqrt(c), 1.0);
}
"""

    val gasGiantFs = HEADER + """
precision highp float;
in vec2 v_ndc;
out vec4 o_color;
""" + NOISE + """
void main() {
    vec2 uv = v_ndc * 0.5 + 0.5;
    float lon = uv.x * 6.2831853;
    float lat = (uv.y - 0.5) * 3.14159265;
    vec3 p = vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon));
    float w1 = fbm(p * 3.0 + vec3(1.3)) - 0.5;
    float w2 = fbm(p * 9.0 + vec3(7.7)) - 0.5;
    float y = p.y + w1 * 0.11 + w2 * 0.035;
    float b1 = sin(y * 15.0 + 0.5) * 0.5 + 0.5;
    float b2 = sin(y * 38.0 + 2.0) * 0.5 + 0.5;
    float b3 = fbm(vec3(y * 26.0, p.x * 1.5, p.z * 1.5));
    vec3 cA = vec3(0.80, 0.70, 0.55);
    vec3 cB = vec3(0.56, 0.41, 0.31);
    vec3 cC = vec3(0.92, 0.88, 0.78);
    vec3 cD = vec3(0.42, 0.47, 0.56);
    vec3 col = mix(cA, cB, smoothstep(0.25, 0.75, b1));
    col = mix(col, cC, smoothstep(0.55, 0.95, b2) * 0.45);
    col = mix(col, cD, smoothstep(0.55, 0.95, abs(p.y)) * 0.65);
    col *= 0.82 + 0.36 * b3;
    vec3 sc = normalize(vec3(0.62, -0.30, 0.72));
    vec3 dp = p - sc;
    float sd = length(vec2(length(dp.xz) * 0.55, dp.y * 1.1));
    float swirl = fbm(p * 22.0) * 0.04;
    float storm = 1.0 - smoothstep(0.04, 0.13 + swirl, sd);
    col = mix(col, vec3(0.76, 0.40, 0.26), storm * 0.85);
    col = mix(col, vec3(0.95, 0.85, 0.75), (1.0 - smoothstep(0.0, 0.05, sd)) * 0.5);
    o_color = vec4(sqrt(clamp(col, 0.0, 1.0)), 1.0);
}
"""
}
