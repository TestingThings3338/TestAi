package com.starwake.core.gfx

/**
 * GLSL ES 3.00 sources of the terrain (§9.8): the 24-byte tile vertex is decoded and geomorphed in
 * the vertex shader (distance morph between 0.75·D and D with D = ρ·edge of the parent, or the
 * split-in time morph, whichever is larger), positions are camera-relative floats from a per-tile
 * origin computed in double on the CPU. Aerial perspective is per vertex (the hook the atmosphere
 * port replaces with its LUT): an exponential atmosphere of scale height H and ground extinction
 * σ0 (DECISIONS 17b/17c: H = thickness/3, e-fold ≈ 10× Roblox's), optical depth from the lower end
 * of the sight line with a curvature-limited slant length, in-scatter in the Roblox haze colour.
 *
 * Fragment: albedo (sRGB², as the impostor) × biplanar procedural detail (8 m and 64 m near,
 * 64 m and 512 m beyond 900 m; one RGBA8 texture: a luminance pattern per detail class), the
 * impostor's sun model (smoothstep diffuse,
 * transmittance along the sun path) plus profile ambient by day and night, water glint, the
 * engine's ACES tonemap and dither.
 */
object TerrainShaders {
    private const val HEADER = "#version 300 es\n"

    const val VS = HEADER + """
precision highp float;
layout(location = 0) in vec4 a_pos;
layout(location = 1) in vec4 a_nrm;
layout(location = 2) in vec4 a_col;
layout(location = 3) in vec4 a_par;
layout(location = 4) in vec4 a_cls;

uniform mat4 u_viewProj;
uniform mat4 u_bodyRot4;       // body -> world (rotation only)
// per tile: [0] origin - camera (world, m), morph scale; [1] origin (body, m), time morph;
//           [2] half extents (m), morph start distance; [3] morph end distance, detail origin (xyz mod 512 m)
uniform vec4 u_tile[4];
uniform vec3 u_camCentre;      // camera - body centre (world, m)
uniform vec3 u_sunDir;         // world, towards the star
uniform vec4 u_air;            // R (m), scale height H (m), ground extinction sigma0 (1/m), min grazing sine
uniform vec3 u_airW;           // chromatic extinction weights (mean 1)
uniform vec3 u_airL;           // in-scattered radiance of a thick air column in daylight (linear)

out vec3 v_col;
out float v_water;
out vec3 v_nrm;
out vec3 v_detailPos;
out vec3 v_nrmB;
out vec4 v_cls;
out vec3 v_trans;
out vec3 v_inscatter;
out vec3 v_sunT;
out float v_day;
out vec3 v_view;
out float v_dist;
out float v_morph;

vec3 octDecode(vec2 e) {
    vec3 v = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (v.z < 0.0) {
        vec2 s = vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
        v.xy = (1.0 - abs(v.yx)) * s;
    }
    return normalize(v);
}

float airmass(float mu) {
    return min(1.0 / (max(mu, 0.0) + u_air.w), 1.0 / u_air.w);
}

void main() {
    mat3 u_bodyRot = mat3(u_bodyRot4);
    vec3 local = a_pos.xyz * u_tile[2].xyz;
    vec3 radial = normalize(u_tile[1].xyz + local);
    vec3 world = u_tile[0].xyz + u_bodyRot * local;
    float dist = length(world);
    float m = max(smoothstep(u_tile[2].w, u_tile[3].x, dist), u_tile[1].w);
    float delta = a_pos.w * u_tile[0].w;
    vec3 radialW = u_bodyRot * radial;
    world -= radialW * (m * delta);
    local -= radial * (m * delta);
    gl_Position = u_viewProj * vec4(world, 1.0);

    v_morph = m;
    v_col = mix(a_col.rgb, a_par.rgb, m);
    v_water = mix(a_col.a, a_par.a, m);
    vec3 nB = normalize(mix(octDecode(a_nrm.xy), octDecode(a_nrm.zw), m));
    v_nrm = u_bodyRot * nB;
    v_detailPos = u_tile[3].yzw + local;
    v_nrmB = nB;
    v_cls = a_cls;
    v_view = world;
    v_dist = length(world);

    // aerial perspective: optical depth from the lower end of the sight line
    vec3 pv = u_camCentre + world;          // vertex relative to the centre (world)
    float rv = length(pv);
    float rc = length(u_camCentre);
    float R = u_air.x;
    float H = u_air.y;
    vec3 upV = pv / rv;
    float mus = dot(upV, u_sunDir);
    v_day = smoothstep(-0.10, 0.12, mus);
    float hv = rv - R;
    float hc = rc - R;
    vec3 toCam = -world / max(v_dist, 1e-3);
    float lowH;
    float mu;
    if (hv <= hc) {
        lowH = hv;
        mu = dot(upV, toCam);
    } else {
        lowH = hc;
        mu = dot(u_camCentre / rc, -toCam);
    }
    float lambda = H / max(mu, u_air.w);
    float column = exp(-max(lowH, 0.0) / H) * lambda * (1.0 - exp(-v_dist / lambda));
    vec3 tau = u_airW * (u_air.z * column);
    v_trans = exp(-tau);
    v_inscatter = u_airL * (1.0 - v_trans) * (0.04 + 0.96 * v_day);
    v_sunT = exp(-u_airW * (u_air.z * H * exp(-max(hv, 0.0) / H) * airmass(mus)));
}
"""

    const val FS = HEADER + """
precision highp float;
in vec3 v_col;
in float v_water;
in vec3 v_nrm;
in vec3 v_detailPos;
in vec3 v_nrmB;
in vec4 v_cls;
in vec3 v_trans;
in vec3 v_inscatter;
in vec3 v_sunT;
in float v_day;
in vec3 v_view;
in float v_dist;
in float v_morph;

uniform int u_debug;           // 0 normal, 1 albedo, 2 water share, 3 normal, 4 morph factor, 5 transmittance
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambientDay;
uniform vec3 u_ambientNight;
uniform float u_exposure;
uniform sampler2D u_detail;
uniform vec4 u_water;           // rgb = sky radiance reflected by water, a = the style's reflectance
uniform vec4 u_detailParams;   // x = strength near, y = fade start (m), z = fade end (m), w = time (s, wrapped)
out vec4 o_color;

vec3 tonemap(vec3 c) {
    c *= u_exposure;
    vec3 t = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
    return pow(t, vec3(0.4545));
}
vec3 dither(vec3 c) {
    float n = fract(52.982919 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return max(c + (n - 0.5) * (1.0 / 255.0), 0.0);
}

/**
 * Biplanar sample (two fetches: the major and the median axis of the surface normal, body space)
 * of the detail texture at [period] metres, with explicit gradients so the axis switch never
 * picks a wrong mip. Projecting along the normal (not the sphere's up) keeps cliffs unstretched.
 * [pdx]/[pdy] are the screen derivatives of [p], taken in uniform control flow by the caller.
 */
vec4 biplanar(vec3 p, vec3 pdx, vec3 pdy, vec3 nrm, float period) {
    vec3 n = abs(nrm);
    vec3 q = p / period;
    vec3 dx = pdx / period;
    vec3 dy = pdy / period;
    ivec3 ma = (n.x > n.y && n.x > n.z) ? ivec3(0, 1, 2) : (n.y > n.z) ? ivec3(1, 2, 0) : ivec3(2, 0, 1);
    ivec3 mi = (n.x < n.y && n.x < n.z) ? ivec3(0, 1, 2) : (n.y < n.z) ? ivec3(1, 2, 0) : ivec3(2, 0, 1);
    ivec3 me = ivec3(3) - mi - ma;
    vec4 a = textureGrad(u_detail, vec2(q[ma.y], q[ma.z]), vec2(dx[ma.y], dx[ma.z]), vec2(dy[ma.y], dy[ma.z]));
    vec4 b = textureGrad(u_detail, vec2(q[me.y], q[me.z]), vec2(dx[me.y], dx[me.z]), vec2(dy[me.y], dy[me.z]));
    vec2 w = vec2(n[ma.x], n[me.x]);
    w = clamp((w - 0.5773) / (1.0 - 0.5773), 0.0, 1.0);
    w = w * w * w + 1e-5;   // the epsilon keeps a normal on a body diagonal from going black
    return (a * w.x + b * w.y) / (w.x + w.y);
}

void main() {
    vec3 alb = v_col * v_col;
    vec3 N = normalize(v_nrm);
    vec3 pdx = dFdx(v_detailPos);
    vec3 pdy = dFdy(v_detailPos);
    float detailW = u_detailParams.x * (1.0 - smoothstep(u_detailParams.y, u_detailParams.z, v_dist)) * (1.0 - v_water);
    if (detailW > 0.002) {
        // three scales, two fetched per fragment: 8 m fades out by 900 m (it is sub-pixel there),
        // 512 m fades in beyond it, so no single period repeats across a whole view
        vec4 cls = v_cls / max(v_cls.x + v_cls.y + v_cls.z + v_cls.w, 1e-3);
        vec3 nB = normalize(v_nrmB);
        float mid = dot(biplanar(v_detailPos, pdx, pdy, nB, 64.0), cls) * 2.0;
        float lum;
        if (v_dist < 900.0) {
            float near = dot(biplanar(v_detailPos, pdx, pdy, nB, 8.0), cls) * 2.0;
            lum = mix(near * 0.6 + mid * 0.4, mid, smoothstep(150.0, 900.0, v_dist));
        } else {
            float far = dot(biplanar(v_detailPos, pdx, pdy, nB, 512.0), cls) * 2.0;
            lum = mix(mid, mid * 0.55 + far * 0.45, smoothstep(900.0, 3000.0, v_dist));
        }
        alb *= mix(1.0, lum, detailW);
    }
    float ndl = dot(N, u_sunDir);
    float diff = smoothstep(-0.07, 1.0, ndl);
    vec3 sun = u_sunColor * v_sunT * diff;
    vec3 amb = mix(u_ambientNight, u_ambientDay, v_day);
    vec3 col = alb * (sun + amb);
    if (v_water > 0.01) {
        vec3 V = -v_view / max(v_dist, 1e-3);
        vec3 Hh = normalize(u_sunDir + V);
        float ndh = max(dot(N, Hh), 0.0);
        float schlick = pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float fres = u_water.a * 0.35 + (1.0 - u_water.a * 0.35) * schlick;
        vec3 glint = u_sunColor * v_sunT * (pow(ndh, 160.0) * 3.0 + pow(ndh, 18.0) * 0.06) * step(0.0, ndl);
        col = mix(col, u_water.rgb * (0.25 + 0.75 * v_day), fres * v_water) + glint * v_water;
    }
    col = col * v_trans + v_inscatter;
    o_color = vec4(dither(tonemap(col)), 1.0);
    if (u_debug == 1) o_color = vec4(v_col, 1.0);
    else if (u_debug == 2) o_color = vec4(vec3(v_water), 1.0);
    else if (u_debug == 3) o_color = vec4(N * 0.5 + 0.5, 1.0);
    else if (u_debug == 4) o_color = vec4(v_morph, 1.0 - v_morph, 0.0, 1.0);
    else if (u_debug == 5) o_color = vec4(v_trans, 1.0);
}
"""
}
