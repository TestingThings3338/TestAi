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
 *
 * Far look (§9.5 far = the body image, the same texture the impostor uses): where the image
 * carries more detail than the tiles (view distance from 32·ρ to 64·ρ image texels, where the
 * tile vertex spacing passes the texel size) the fragment blends to exactly what the impostor
 * draws there: the image as albedo (sampled and filtered as BodyShaders does), the impostor's
 * sun (its colour and distance falloff, the transmittance along the normal, its tiny ambient,
 * its water glint and ice sheen) and the impostor's single-scattering air in front of the
 * ground, marched per fragment with the impostor's step count. From orbit the terrain is then the
 * impostor, so the hand-over is invisible; nearer, the tiles' own colours, light and haze take
 * over, continuously with distance.
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
uniform vec2 u_farDist;        // view distances (m) where the far look starts and is complete (0, 0: none)

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
out vec3 v_dirB;               // body-space direction of the vertex (the far image's coordinates)
out float v_farW;              // weight of the far look
invariant gl_Position;         // the fade-in draws each slice twice (depth, then colour at equal depth)

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
    v_dirB = radial;
    v_farW = u_farDist.y > 0.0 ? smoothstep(u_farDist.x, u_farDist.y, v_dist) : 0.0;

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

    /** The fragment shader with the impostor's step count for the air of the far look ([BodyRenderer.atmoSteps]). */
    fun fs(atmoSteps: Int) = HEADER + "#define ATMO_STEPS $atmoSteps\n" + FS_HEAD + CloudShaders.UNIFORMS + CloudShaders.FUNCTIONS + FS_BODY

    private const val FS_HEAD = """
precision highp float;
precision highp int;
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
in vec3 v_dirB;
in float v_farW;

uniform int u_debug;           // 0 normal, 1 albedo, 2 water share, 3 normal, 4 morph factor, 5 transmittance, 6 far weight
uniform vec3 u_sunDir;
uniform vec3 u_sunColor;
uniform vec3 u_ambientDay;
uniform vec3 u_ambientNight;
uniform float u_exposure;
uniform sampler2D u_detail;
uniform vec4 u_water;           // rgb = sky radiance reflected by water, a = the style's reflectance
uniform vec4 u_detailParams;   // x = strength near, y = fade start (m), z = fade end (m), w = time (s, wrapped)
uniform float u_fade;          // alpha while the terrain fades in over the impostor (1 = opaque)
// the impostor's far look of this body (BodyRenderer.fillTerrainLight)
uniform sampler2D u_farTex;    // its surface image (equirect, sqrt-encoded albedo, A = emission)
uniform float u_farTexScale;   // image texels per radian
uniform int u_farType;         // BodyShaders type code: 1 lava, 2 terran (water glint), 4 ice
uniform vec4 u_farAtmo;        // its air: rgb scattering colour, a = shell thickness (radii; 0 = none)
uniform vec2 u_farAtmoK;       // vertical optical depth at the ground, brightness
uniform vec4 u_farCam;         // camera - body centre (world axes, radii), w = 1 / radius (1/m)
uniform vec3 u_camCentre;      // camera - body centre (world, m)
uniform vec3 u_airL;           // in-scattered radiance of a thick air column in daylight (linear)
uniform vec3 u_cloudCam;       // the camera in the cloud deck's frame (m, wrapped to the billow period)
uniform vec3 u_cloudCamB;      // the same in the bank octave's tile coordinates (wrapped to 1)
uniform vec4 u_cloudGeo;       // x = camera radius - deck radius (m), y = fog around the camera 0..1, z = radians per pixel
out vec4 o_color;
"""

    private const val FS_BODY = """

/** The impostor's (BodyShaders.ATMOSPHERE) airmass and single scattering, with the far uniforms. */
float airmassI(float mu, float muH) {
    return min(1.0 / (max(mu - muH, 0.0) + 0.035), 40.0);
}
vec3 atmosphereI(vec3 d, vec3 pca, float h2, float x0, float x1, out vec3 trans) {
    float H = u_farAtmo.a * 0.18;
    vec3 beta = u_farAtmo.rgb;
    float k = u_farAtmoK.x / H;
    float dx = (x1 - x0) * (1.0 / float(ATMO_STEPS));
    float sunPca = dot(pca, u_sunDir);
    float sunD = dot(d, u_sunDir);
    vec3 sum = vec3(0.0);
    float tau = 0.0;
    for (int i = 0; i < ATMO_STEPS; i++) {
        float x = x0 + (float(i) + 0.5) * dx;
        float r = sqrt(h2 + x * x);
        float rho = exp(min((1.0 - r) / H, 0.0));
        float dt = rho * dx * k;
        float mu = (sunPca + x * sunD) / r;
        float muH = -sqrt(max(1.0 - 1.0 / (r * r), 0.0));
        float vis = smoothstep(muH - 0.02, muH + 0.05, mu);
        sum += exp(-beta * (u_farAtmoK.x * rho * airmassI(mu, muH) + tau + 0.5 * dt)) * (vis * dt);
        tau += dt;
    }
    trans = exp(-beta * tau);
    float phase = 0.75 * (1.0 + sunD * sunD) + 0.5 * pow(max(sunD, 0.0), 24.0);
    return sum * beta * phase * u_farAtmoK.y * u_sunColor;
}

/**
 * What the impostor draws at this ground point (BodyShaders.PLANET_MAIN with its image, no
 * procedural detail): the image texel filtered for the pixel's footprint ([fp], radians, from the
 * screen derivatives of the body direction: continuous across the image's seam), the impostor's
 * sun (the mesh normal [N] for the diffuse term, so relief keeps its shading), its glints, then
 * its air between the camera and the ground. Linear radiance, before the tonemap.
 */
vec3 farLook(vec3 N, vec3 nB, float fp, float cshadow, vec4 cl) {
    float lon = abs(nB.x) + abs(nB.z) < 1e-7 ? 0.0 : atan(nB.z, nB.x);
    vec2 uv = vec2(lon * 0.15915494 + 0.5, asin(clamp(nB.y, -1.0, 1.0)) * 0.31830989 + 0.5);
    vec4 tx = textureLod(u_farTex, uv, log2(max(fp * u_farTexScale, 1.0)));
    vec3 alb = tx.rgb * tx.rgb;
    float water = u_farType == 2 ? clamp((tx.b - tx.r) * 8.0 - 0.45, 0.0, 1.0) : 0.0;
    vec3 d = v_view / max(v_dist, 1e-3);
    // the ground point relative to the centre (radii): the sphere normal the impostor shades with
    vec3 P = u_farCam.xyz + v_view * u_farCam.w;
    vec3 Ns = normalize(P);
    float ndlS = dot(Ns, u_sunDir);
    float diff = smoothstep(-0.07, 1.0, dot(N, u_sunDir));
    vec3 sun = u_sunColor * (diff * cshadow);
    if (u_farAtmo.a > 0.0) sun *= exp(-u_farAtmo.rgb * (u_farAtmoK.x * airmassI(ndlS, 0.0)));
    vec3 col = alb * (sun + vec3(0.004));
    float night = 1.0 - smoothstep(-0.06, 0.14, ndlS);
    vec3 Hh = u_sunDir - d;
    Hh *= inversesqrt(max(dot(Hh, Hh), 1e-12));
    float ndh = max(dot(Ns, Hh), 0.0);
    if (u_farType == 1) {
        col += vec3(3.0, 0.85, 0.20) * (tx.a * tx.a) * (0.3 + 0.7 * night);
    } else if (u_farType == 2) {
        col += sun * (pow(ndh, 80.0) * 2.5 + pow(ndh, 12.0) * 0.06) * water;
        col += vec3(1.0, 0.70, 0.36) * (tx.a * night * 0.3);
    } else if (u_farType == 4) {
        col += sun * pow(ndh, 24.0) * 0.12;
    }
    // the cloud deck in front of the ground, under the air
    col = mix(col, cl.rgb, cl.a);
    if (u_farAtmo.a > 0.0) {
        // the ray from the camera (x = -b) to the ground (x = t - b), measured from its closest
        // approach to the centre, entering the shell at -xa
        float b = -dot(u_farCam.xyz, d);
        vec3 pca = u_farCam.xyz + d * b;
        float h2 = dot(pca, pca);
        float A = 1.0 + u_farAtmo.a;
        float xa = sqrt(max(A * A - h2, 0.0));
        float x0 = max(-xa, -b);
        float x1 = v_dist * u_farCam.w - b;
        if (x1 > x0) {
            vec3 T;
            vec3 ins = atmosphereI(d, pca, h2, x0, x1, T);
            col = col * T + ins;
        }
    }
    return col;
}

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
    // the far image's footprint (radians per pixel), in uniform control flow
    vec3 dirB = normalize(v_dirB);
    float farFp = max(length(dFdx(dirB)), length(dFdy(dirB)));
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
    // the cloud deck: where the sight line crosses it (cl) and its shadow on this ground (cshadow)
    vec4 cl = vec4(0.0);
    float cshadow = 1.0;
    if (u_cloud.x < 1.5) {
        mat3 M = mat3(u_toCloud);
        vec3 dv = v_view / max(v_dist, 1e-3);
        float rc = length(u_camCentre);
        float bq = dot(u_camCentre, dv);
        float cq = u_cloudGeo.x * (rc + u_cloud.z);     // rc^2 - Rc^2: > 0 above the deck
        float dq = bq * bq - cq;
        if (dq > 0.0) {
            float sq = sqrt(dq);
            // above: the near root (looking down); below: the one ahead; both without cancellation
            float t = cq > 0.0 ? (bq < 0.0 ? cq / (sq - bq) : -1.0) : (bq <= 0.0 ? sq - bq : -cq / (bq + sq));
            if (t > 0.0 && t < v_dist) {
                vec3 nw = normalize(u_camCentre + dv * t);
                float fpC = t * u_cloudGeo.z / max(abs(dot(nw, dv)), 0.06);
                float mu = dot(nw, u_sunDir);
                // lit as the impostor lights its deck far away, by the terrain's own air close by
                vec3 sunFar = u_sunColor;
                vec3 ambFar = vec3(0.004);
                if (u_farAtmo.a > 0.0) {
                    sunFar *= exp(-u_farAtmo.rgb * (u_farAtmoK.x * 0.75 * airmassI(mu, 0.0)));
                    ambFar += u_sunColor * u_farAtmo.rgb * (0.10 * smoothstep(-0.12, 0.25, mu));
                }
                vec3 ambNear = mix(u_ambientNight, u_ambientDay, v_day) * 2.2 + u_airL * (0.22 * v_day);
                vec3 qd = (M * dv) * t;
                cl = cloudShade(M * nw, u_cloudCam + qd, u_cloudCamB + cloudBank(qd), fpC, M * u_sunDir,
                    mix(u_sunColor * v_sunT, sunFar, v_farW), mix(ambNear, ambFar, v_farW), cq > 0.0 ? 0.0 : 1.0);
                // seen edge-on from inside the deck the sheet has no depth: it fades out there, into the fog
                cl.a *= smoothstep(0.0, 250.0, abs(u_cloudGeo.x));
            }
        }
        vec3 pf = u_camCentre + v_view;
        float rf = length(pf);
        float mus = dot(pf, u_sunDir) / rf;
        float gap = u_cloud.z - rf;
        if (mus > 0.02 && gap > 0.0) {
            float ts = gap / max(mus, 0.12);
            vec3 qs = M * (v_view + u_sunDir * ts);
            cshadow = cloudShadow(M * normalize(pf + u_sunDir * ts), u_cloudCam + qs, u_cloudCamB + cloudBank(qs),
                v_dist * u_cloudGeo.z, 1.0 - v_farW);
        }
    }
    float ndl = dot(N, u_sunDir);
    float diff = smoothstep(-0.07, 1.0, ndl);
    vec3 sun = u_sunColor * v_sunT * (diff * cshadow);
    vec3 amb = mix(u_ambientNight, u_ambientDay, v_day);
    vec3 col = alb * (sun + amb);
    if (v_water > 0.01) {
        vec3 V = -v_view / max(v_dist, 1e-3);
        vec3 Hh = normalize(u_sunDir + V);
        float ndh = max(dot(N, Hh), 0.0);
        float schlick = pow(1.0 - max(dot(N, V), 0.0), 5.0);
        float fres = u_water.a * 0.35 + (1.0 - u_water.a * 0.35) * schlick;
        vec3 glint = u_sunColor * v_sunT * ((pow(ndh, 160.0) * 3.0 + pow(ndh, 18.0) * 0.06) * step(0.0, ndl) * cshadow);
        col = mix(col, u_water.rgb * (0.25 + 0.75 * v_day), fres * v_water) + glint * v_water;
    }
    col = mix(col, cl.rgb, cl.a);
    col = col * v_trans + v_inscatter;
    // the far look: what the impostor draws here (from orbit all of it: the hand-over is invisible)
    if (v_farW > 0.001) col = mix(col, farLook(N, dirB, farFp, cshadow, cl), v_farW);
    if (u_cloudGeo.y > 0.0) {
        // inside the deck: the cloud around the camera hides what is more than a few hundred metres away
        vec3 fog = u_cloudCol.rgb * u_sunColor * (0.10 + 0.42 * v_day);
        col = mix(col, fog, u_cloudGeo.y * (1.0 - exp(-v_dist * (1.0 / 160.0))));
    }
    o_color = vec4(dither(tonemap(col)), u_fade);
    if (u_debug == 1) o_color = vec4(v_col, 1.0);
    else if (u_debug == 2) o_color = vec4(vec3(v_water), 1.0);
    else if (u_debug == 3) o_color = vec4(N * 0.5 + 0.5, 1.0);
    else if (u_debug == 4) o_color = vec4(v_morph, 1.0 - v_morph, 0.0, 1.0);
    else if (u_debug == 5) o_color = vec4(v_trans, 1.0);
    else if (u_debug == 6) o_color = vec4(v_farW, 0.0, 1.0 - v_farW, 1.0);
}
"""
}
