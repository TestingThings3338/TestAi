package com.starwake.core.gfx

/**
 * GLSL ES 3.00 sources for [BodyRenderer]: ray-cast sphere impostors for planets and the star,
 * ring planes, the star glow and the one-time equirect surface bakes.
 *
 * Every body is described relative to the camera in units of its own radius: the uniform
 * `u_center` holds the unit direction to the centre (xyz) and the distance in radii (w), `u_cc` the
 * value |c|^2/r^2 - 1 computed in double on the CPU. The fragment shaders then work relative to the
 * ray's closest-approach point, so nothing cancels catastrophically, whether the camera hangs a few
 * kilometres above a 6000 km planet or the planet is a dot a billion kilometres away.
 *
 * Lighting is linear; each fragment shader ends with the ACES tonemap + gamma (same as [Shaders]).
 * Output is premultiplied alpha (blend ONE, ONE_MINUS_SRC_ALPHA); alpha 0 = purely additive.
 */
object BodyShaders {
    private const val HEADER = "#version 300 es\n"

    // integer hashing needs 32-bit ints: fragment ints default to mediump (16-bit on Mali)
    private const val PRECISION = """
precision highp float;
precision highp int;
"""

    private const val TONEMAP = """
vec3 tonemap(vec3 c) {
    c *= u_exposure;
    vec3 t = clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
    return pow(t, vec3(0.4545));
}
/** +-0.5/255 interleaved-gradient dither against 8-bit banding of smooth glows. */
vec3 dither(vec3 c) {
    float n = fract(52.982919 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    return max(c + (n - 0.5) * (1.0 / 255.0), 0.0);
}
"""

    /** PCG hash value noise, identical to the one in [Shaders]. Needs `precision highp int`. */
    private const val NOISE = """
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

    /**
     * Value noise with one PCG hash per lattice corner (8 per call; [NOISE]'s vnoise needs 24): the
     * corner keys are a linear combination of the cell coordinates. Runtime surface detail only;
     * the bakes keep the noise shared with [Shaders].
     */
    private const val NOISE_FAST = """
float vnoiseFast(vec3 p) {
    vec3 fl = floor(p);
    ivec3 i = ivec3(fl);
    vec3 f = p - fl;
    vec3 u = f * f * (3.0 - 2.0 * f);
    const uint KX = 0x8DA6B343u;
    const uint KY = 0xD8163841u;
    const uint KZ = 0xCB1AB31Fu;
    uint h = uint(i.x) * KX + uint(i.y) * KY + uint(i.z) * KZ;
    float a = float(pcg(h));
    float b = float(pcg(h + KX));
    float c = float(pcg(h + KY));
    float d = float(pcg(h + KX + KY));
    float e = float(pcg(h + KZ));
    float g = float(pcg(h + KX + KZ));
    float k = float(pcg(h + KY + KZ));
    float l = float(pcg(h + KX + KY + KZ));
    float v = mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), mix(mix(e, g, u.x), mix(k, l, u.x), u.y), u.z);
    return v * (1.0 / 4294967295.0);
}
"""

    /**
     * The ring's opacity from its Roblox profile (`ShipVisualAssets.RingProfiles`, 03-world.md
     * Appendix D): a 128-texel texture over f = r / outer radius, value i at f = (i − 0.5)/128 (the
     * texel centres), linear between. Mipmapped: the footprint [fp] (planet radii) of a pixel, or of
     * the star's penumbra for shadows, picks the level, so far or edge-on rings never shimmer.
     */
    private const val RING_PROFILE = """
float ringOpacity(float r, float fp) {
    float f = r / u_ring.y;
    if (f <= 0.0 || f > 1.0) return 0.0;
    float lod = log2(max(fp / u_ring.y * 128.0, 1.0));
    return textureLod(u_ringTex, vec2(f, 0.5), lod).r;
}
"""

    /**
     * A bigger body drawn before this one (its centre is farther) whose near surface still hides
     * part of this one: the camera-relative centre (in this body's radii) and radius of `u_occ`
     * (w = 0: none). Returns the ray distance (radii) to its near surface, or 1e30.
     */
    private const val OCCLUDER = """
float occluderHit(vec3 d) {
    if (u_occ.w <= 0.0) return 1e30;
    float b = dot(d, u_occ.xyz);
    float c = dot(u_occ.xyz, u_occ.xyz) - u_occ.w * u_occ.w;
    float disc = b * b - c;
    if (disc <= 0.0 || b <= 0.0) return 1e30;
    return b - sqrt(disc);
}
"""

    /**
     * Ray vs. unit sphere at D * cd (cd unit), camera at the origin, unit ray direction d.
     * b = distance along the ray to the closest-approach point, h2 = squared closest-approach
     * distance, disc = 1 - h2 (passed separately: it is tiny near the horizon), pca =
     * closest-approach point relative to the sphere centre. Far away disc comes from cross(d, cd),
     * which stays accurate for nearly parallel vectors; close by from b^2 - cc, where both terms
     * are small near the horizon (D itself only resolves ~1 m of altitude as a float).
     */
    private const val RAY = """
void rayInfo(vec3 d, out float b, out float h2, out float disc, out vec3 pca) {
    float D = u_center.w;
    vec3 cr = cross(d, u_center.xyz);
    b = D * dot(d, u_center.xyz);
    disc = D < 4.0 ? b * b - u_cc : 1.0 - D * D * dot(cr, cr);
    h2 = max(1.0 - disc, 0.0);
    pca = D * cross(d, cr);
}
/** Anti-aliased silhouette coverage; u_pxR = one pixel at the limb distance, in radii. */
float coverage(float b, float h2, float disc) {
    if (b <= 0.0) return 0.0;
    float edge = disc / (1.0 + sqrt(max(h2, 0.0)));
    return clamp(0.5 + edge / max(u_pxR, 1e-12), 0.0, 1.0);
}
"""

    // ---------------------------------------------------------------- shared vertex shader

    /**
     * Coverage geometry. Quad mode: a camera-facing square around the body's direction, placed in
     * "scaled space" 1000 units from the camera (floats stay small; only the direction matters).
     * Fullscreen mode (u_full = 1, fullscreen triangle): the ray comes from the camera basis,
     * exactly like the sky shader, including the lens shift.
     */
    val vs = HEADER + """
layout(location = 0) in vec2 a_pos;
uniform mat4 u_viewProj;
uniform vec3 u_qCenter;
uniform vec3 u_qRight;
uniform vec3 u_qUp;
uniform float u_full;
uniform vec3 u_fwd;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec2 u_shift;
out vec3 v_ray;
void main() {
    if (u_full > 0.5) {
        v_ray = u_fwd + (a_pos.x - u_shift.x) * u_right + (a_pos.y - u_shift.y) * u_up;
        gl_Position = vec4(a_pos, 0.0, 1.0);
    } else {
        vec3 p = u_qCenter + a_pos.x * u_qRight + a_pos.y * u_qUp;
        v_ray = p;
        gl_Position = u_viewProj * vec4(p, 1.0);
    }
}
"""

    // ---------------------------------------------------------------- planets

    /** Surface type codes shared by the bake and the planet shader (see [BodyRenderer]). */
    const val T_LAVA = 1
    const val T_TERRAN = 2
    const val T_GAS = 3
    const val T_ICE = 4
    const val T_ROCK = 5

    private const val TYPES = """
#define T_LAVA 1
#define T_TERRAN 2
#define T_GAS 3
#define T_ICE 4
#define T_ROCK 5
"""

    private const val PLANET_FS = """
in vec3 v_ray;
uniform vec4 u_center;
uniform float u_cc;
uniform float u_pxR;
uniform float u_pxAngle;
uniform mat4 u_toBody;
uniform vec2 u_spin;      // cos, sin of the spin angle
uniform vec3 u_sunDir;    // world space, towards the star
uniform vec3 u_sunBody;   // the same in body space
uniform vec3 u_sunColor;
uniform vec4 u_atmo;      // rgb scattering colour (max component 1), a = shell thickness in radii
uniform vec2 u_atmoK;     // x = vertical optical depth at the ground, y = brightness
uniform vec4 u_ring;      // inner, outer radius (planet radii), z = 1 when ringed, w = star radius seen from the planet (rad)
uniform vec4 u_seed;
uniform int u_type;
uniform sampler2D u_tex;
uniform float u_texScale;  // texels per radian (texture height / pi)
uniform vec4 u_sunCam;     // camera -> star unit direction, w = drawn angular radius (0 = no star)
uniform vec4 u_sunGlare;   // x = glare width, y = fade-out angle, z = cos(fade-out), w = disc radiance
uniform vec3 u_glareColor;
uniform vec3 u_coronaColor;
uniform float u_starBehind;  // 1 = the star is drawn behind this sky, 0 = not (the sky draws all of it)
uniform float u_noSurface;   // 0, or k > 0: the terrain draws the surface; the disc is a black core of k radii
                             // (the terrain's lowest point: never above its horizon), haze in front kept
uniform float u_procDetail;  // 1: procedural albedo detail at close range; 0: the image is the body's own
                             // far look (its terrain's colours), shown as it is, as the terrain's far look shows it
uniform vec4 u_cloudGeo;     // x = deck height above the radius (radii), y = radius (m), z = fog around the camera 0..1
uniform float u_exposure;
uniform sampler2D u_ringTex; // the ring's opacity profile (when u_ring.z = 1)
uniform vec4 u_occ;          // occluder: centre (camera space, this body's radii) and radius; w = 0 none
out vec4 o_color;
"""

    /** Single scattering through an exponential shell; also used for the ground transmittance. */
    private const val ATMOSPHERE = """
float airmass(float mu, float muH) {
    return min(1.0 / (max(mu - muH, 0.0) + 0.035), 40.0);
}
/**
 * In-scattered sunlight along the ray between x0 and x1 (distances along the ray measured from
 * the closest-approach point pca); writes the transmittance over that segment.
 */
vec3 atmosphere(vec3 d, vec3 pca, float h2, float x0, float x1, out vec3 trans) {
    float H = u_atmo.a * 0.18;
    vec3 beta = u_atmo.rgb;
    float k = u_atmoK.x / H;
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
        // sun -> sample and sample -> camera transmittance in one exp
        sum += exp(-beta * (u_atmoK.x * rho * airmass(mu, muH) + tau + 0.5 * dt)) * (vis * dt);
        tau += dt;
    }
    trans = exp(-beta * tau);
    float phase = 0.75 * (1.0 + sunD * sunD) + 0.5 * pow(max(sunD, 0.0), 24.0);
    return sum * beta * phase * u_atmoK.y * u_sunColor;
}
/**
 * The star as the star and glow shaders draw it (limb-darkened disc, corona, glare), for a sky
 * that hides the real one. Zero beyond the glow's fade-out angle.
 */
vec3 starLight(vec3 d) {
    float c = dot(d, u_sunCam.xyz);
    if (u_sunCam.w <= 0.0 || c < u_sunGlare.z) return vec3(0.0);
    float th = atan(length(cross(d, u_sunCam.xyz)), c);
    float R = u_sunCam.w;
    float w = u_sunGlare.x;
    float x = max(th / R - 1.0, 0.0);
    float e = max(th - R, 0.0);
    float corona = 0.30 * exp(-x * 7.0) + 0.08 * exp(-x * 1.8);
    float glare = 0.35 * exp(-e / (w * 0.22)) + 0.09 * exp(-e / (w * 1.1)) + 0.012 * exp(-e / (w * 4.0));
    float fade = 1.0 - smoothstep(u_sunGlare.y * 0.45, u_sunGlare.y, th);
    vec3 col = (u_coronaColor * corona + u_glareColor * glare) * fade;
    float cov = clamp(0.5 + (R - th) / u_pxAngle, 0.0, 1.0);
    if (cov > 0.0) {
        float s = min(sin(th) / sin(R), 1.0);
        float mu = sqrt(1.0 - s * s);
        float m = 1.0 - mu;
        float limb = 1.0 - 0.40 * m - 0.15 * m * m;
        col = mix(col, u_sunGlare.w * limb * mix(vec3(1.0, 0.58, 0.28), vec3(1.0, 0.96, 0.88), sqrt(mu)), cov);
    }
    return col;
}
"""

    private const val DETAIL = """
/**
 * Fractal albedo detail for close range. Octave j has features of 2^-j radii; only the octaves
 * whose features cover ~4..4*2^N footprints [fp] (radii) are evaluated, with the finest faded in
 * and the coarsest faded out, so detail grows smoothly while descending instead of popping.
 */
float detail(vec3 ns, float fp) {
#if DETAIL_OCTAVES > 0
    float top = min(log2(1.0 / (fp * 4.0)), 17.0);
    if (top < 7.0) return 0.0;
    vec3 q = u_type == T_GAS ? vec3(ns.x, ns.y * 5.0, ns.z) : ns;
    float fl = floor(top);
    float fr = top - fl;
    float s = 0.0;
    for (int k = 0; k <= DETAIL_OCTAVES; k++) {
        float j = fl - float(k);
        float w = k == 0 ? fr : (k == DETAIL_OCTAVES ? 1.0 - fr : 1.0);
        w *= step(7.0, j);
        s += (vnoiseFast(q * exp2(j) + u_seed.xyz) - 0.5) * w;
    }
    return s * (1.3 / sqrt(float(DETAIL_OCTAVES)));
#else
    return 0.0;
#endif
}
"""

    private const val PLANET_MAIN = """
vec3 shadeSurface(vec3 N, vec3 d, float tHit) {
    vec3 nb = mat3(u_toBody) * N;
    vec3 ns = vec3(nb.x * u_spin.x - nb.z * u_spin.y, nb.y, nb.x * u_spin.y + nb.z * u_spin.x);
    // atan(0, 0) is undefined: the exact poles get longitude 0
    float lon = abs(ns.x) + abs(ns.z) < 1e-7 ? 0.0 : atan(ns.z, ns.x);
    vec2 uv = vec2(lon * 0.15915494 + 0.5, asin(clamp(ns.y, -1.0, 1.0)) * 0.31830989 + 0.5);
    // pixel footprint on the ground (radii), stretched by 1 / cos(incidence) at grazing angles.
    // Explicit LOD from it (radii -> texels): screen-space derivatives of atan() jump at the seam
    // and would pick the smallest mip there; this never does, and small/far planets get a properly
    // filtered texture instead of shimmering
    float fp = max(tHit * u_pxAngle, 1e-12);
    float fpS = fp / max(dot(N, -d), 0.05);
    float lod = log2(max(fpS * u_texScale, 1.0));
    vec4 tx = textureLod(u_tex, uv, lod);
    vec3 alb = tx.rgb * tx.rgb;
    // open water (blue-dominant, dark: b - r is 0.19..0.25 in the sqrt-encoded bake, land, ice
    // and clouds stay below 0.06) gets no ground detail, and skips evaluating it
    float water = u_type == T_TERRAN ? clamp((tx.b - tx.r) * 8.0 - 0.45, 0.0, 1.0) : 0.0;
    // gas bands are noise stretched 5x in y: 5x smaller footprint-relative features
    float det = water < 1.0 && u_procDetail > 0.0 ? detail(ns, u_type == T_GAS ? fpS * 5.0 : fpS) * (1.0 - water) : 0.0;
    alb *= clamp(1.0 + det * vec3(1.0, 0.92, 0.8), 0.25, 2.0);

    float ndl = dot(N, u_sunDir);
    float soft = u_type == T_GAS ? 0.16 : 0.07;
    float diff = smoothstep(-soft, 1.0, ndl);
    float shadow = 1.0;
    if (u_ring.z > 0.5 && abs(u_sunBody.y) > 1e-4) {
        float t = -nb.y / u_sunBody.y;
        if (t > 0.0) {
            vec3 q = nb + u_sunBody * t;
            // the ring's shadow, blurred by the star's disc (penumbra) and by the pixel
            shadow = 1.0 - 0.85 * ringOpacity(length(q.xz), max(2.0 * u_ring.w * t, fpS));
        }
    }
    if (u_cloud.x < 1.5 && ndl > 0.02) {
        // the deck's shadow: where the ray from the ground to the star crosses it
        vec3 ps = N + u_sunDir * (u_cloudGeo.x / max(ndl, 0.12));
        shadow *= cloudShadow(mat3(u_toCloud) * normalize(ps), vec3(0.0), vec3(0.0), fpS * u_cloudGeo.y, 0.0);
    }
    vec3 sun = u_sunColor * (diff * shadow);
    if (u_atmo.a > 0.0) sun *= exp(-u_atmo.rgb * (u_atmoK.x * airmass(ndl, 0.0)));
    vec3 col = alb * (sun + vec3(0.004));
    float night = 1.0 - smoothstep(-0.06, 0.14, ndl);
    // half vector; u_sunDir - d vanishes when looking straight away from the star
    vec3 H = u_sunDir - d;
    H *= inversesqrt(max(dot(H, H), 1e-12));
    float ndh = max(dot(N, H), 0.0);
    if (u_type == T_LAVA) {
        float e = tx.a * tx.a * max(1.0 + 2.0 * det, 0.0);
        col += vec3(3.0, 0.85, 0.20) * e * (0.3 + 0.7 * night);
    } else if (u_type == T_TERRAN) {
        // sun glint on open water; no city lights (DECISIONS 12)
        col += sun * (pow(ndh, 80.0) * 2.5 + pow(ndh, 12.0) * 0.06) * water;
    } else if (u_type == T_ICE) {
        col += sun * pow(ndh, 24.0) * 0.12;
    }
    return col;
}

/**
 * The cloud deck where the ray meets it at x (distance along the ray from its closest approach to
 * the centre, radii), t radii from the camera: rgb = radiance, a = opacity.
 */
vec4 deck(vec3 d, vec3 pca, float x, float t, float below) {
    vec3 nw = normalize(pca + d * x);
    mat3 M = mat3(u_toCloud);
    vec3 n = M * nw;
    float fp = t * u_pxAngle * u_cloudGeo.y / max(abs(dot(nw, d)), 0.06);
    float mu = dot(nw, u_sunDir);
    vec3 sunLight = u_sunColor;
    vec3 amb = vec3(0.004);
    if (u_atmo.a > 0.0) {
        // the deck lies under three quarters of the air
        sunLight *= exp(-u_atmo.rgb * (u_atmoK.x * 0.75 * airmass(mu, 0.0)));
        amb += u_sunColor * u_atmo.rgb * (0.10 * smoothstep(-0.12, 0.25, mu));
    }
    vec3 q = n * u_cloud.z;
    return cloudShade(n, q, cloudBank(q), fp, M * u_sunDir, sunLight, amb, below);
}

void main() {
    vec3 d = normalize(v_ray);
    float b;
    float h2;
    float disc;
    vec3 pca;
    rayInfo(d, b, h2, disc, pca);
    float cov = coverage(b, h2, disc);
    float sqHit = sqrt(max(disc, 0.0));
    // the cloud deck: a sphere of 1 + kc radii; discC = its squared half chord along the ray,
    // ccC > 0 while the camera is above it (both without rounding (1 + kc)^2 - 1)
    bool clouds = u_cloud.x < 1.5;
    float kc2 = u_cloudGeo.x * (2.0 + u_cloudGeo.x);
    float discC = kc2 + disc;
    float ccC = u_cc - kc2;
    float xc = sqrt(max(discC, 0.0));
    float cloudA = 0.0;
    if (u_noSurface > 0.0) {
        // the black core instead of the surface: a sphere of k radii (the same coverage rule)
        float discK = disc - (1.0 - u_noSurface * u_noSurface);
        cov = b <= 0.0 ? 0.0 : clamp(0.5 + discK / (u_noSurface + sqrt(h2)) / max(u_pxR, 1e-12), 0.0, 1.0);
        sqHit = sqrt(max(discK, 0.0));
    }
    bool atmo = u_atmo.a > 0.0;
    float discA = u_atmo.a * (2.0 + u_atmo.a) + disc;   // A^2 - h2 without rounding A^2 - 1
    if (cov <= 0.0 && (!atmo || discA <= 0.0)) discard;
    float xa = sqrt(max(discA, 0.0));
    float x0 = max(-xa, -b);
    // behind a bigger body drawn earlier (a moon behind its planet's limb): that body stays in front
    float tOcc = occluderHit(d);
    if (tOcc < 1e29 && tOcc < (cov > 0.0 ? u_cc / (b + sqrt(max(disc, 0.0))) : b)) discard;
    vec3 ts = vec3(0.0);
    vec3 tk = vec3(0.0);
    float skyA = 0.0;
    if (cov > 0.0) {
        float sq = sqHit;
        vec3 surf = vec3(0.0);
        if (u_noSurface <= 0.0) {
            vec3 N = normalize(pca - d * sq);
            float tHit = u_cc / (b + sq);
            surf = shadeSurface(N, d, tHit);
            if (clouds && ccC > 0.0 && discC > 0.0) {
                // from above: the deck in front of the ground
                vec4 c = deck(d, pca, -xc, ccC / (b + xc), 0.0);
                surf = mix(surf, c.rgb, c.a);
            }
        }
        if (atmo && -sq > x0) {
            vec3 T;
            vec3 ins = atmosphere(d, pca, h2, x0, -sq, T);
            surf = surf * T + ins;
        }
        ts = tonemap(surf);
    }
    if (cov < 1.0 && atmo && xa > x0) {
        vec3 skyT;
        vec3 sky = atmosphere(d, pca, h2, x0, xa, skyT);
        if (clouds && discC > 0.0) {
            if (ccC > 0.0) {
                // from above, past the horizon: the deck where the ray enters it
                if (b > 0.0) {
                    vec4 c = deck(d, pca, -xc, ccC / (b + xc), 0.0);
                    sky = mix(sky, c.rgb, c.a);
                    cloudA = c.a;
                }
            } else {
                // from below: the deck overhead, fading into the haze towards the horizon
                float t = b >= 0.0 ? b + xc : -ccC / (xc - b);
                vec4 c = deck(d, pca, xc, t, 1.0);
                cloudA = c.a * exp(-t * u_cloudGeo.y * (1.0 / 70000.0));
                sky = mix(sky, c.rgb, cloudA);
            }
        }
        tk = tonemap(sky);
        // a bright sky hides what is behind it (stars in daylight), a thin one barely does
        skyA = clamp(1.0 - dot(skyT, vec3(0.3333)) + 2.5 * dot(tk, vec3(0.3, 0.59, 0.11)), 0.0, 1.0);
        skyA = max(skyA, cloudA);
        // that includes the star (drawn before, being farther away): put the hidden part back,
        // dimmed and reddened by the sky
        vec3 sl = starLight(d);
        // (a cloud in front of it lets little of it through)
        if (sl.r + sl.g + sl.b > 0.0) tk = tonemap(sky + sl * (skyT * (mix(1.0, skyA, u_starBehind) * (1.0 - 0.93 * cloudA))));
    }
    vec3 outC = ts * cov + tk * (1.0 - cov);
    float outA = 1.0 - (1.0 - cov) * (1.0 - skyA);
    if (u_cloudGeo.z > 0.0) {
        // inside the deck: the cloud around the camera
        float mu = dot(-u_center.xyz, u_sunDir);
        vec3 fog = tonemap(u_cloudCol.rgb * u_sunColor * (0.10 + 0.42 * smoothstep(-0.1, 0.3, mu)));
        outC = mix(outC, fog, u_cloudGeo.z);
        outA = max(outA, u_cloudGeo.z);
    }
    o_color = vec4(dither(outC), outA);
}
"""

    fun planetFs(atmoSteps: Int, detailOctaves: Int) = HEADER +
        "#define ATMO_STEPS $atmoSteps\n#define DETAIL_OCTAVES $detailOctaves\n" +
        PRECISION + TYPES + PLANET_FS + CloudShaders.UNIFORMS + TONEMAP + NOISE + NOISE_FAST + RING_PROFILE + OCCLUDER + RAY + ATMOSPHERE + DETAIL +
        CloudShaders.FUNCTIONS + PLANET_MAIN

    // ---------------------------------------------------------------- rings

    val ringFs = HEADER + PRECISION + """
in vec3 v_ray;
uniform vec4 u_center;
uniform float u_cc;
uniform float u_pxR;
uniform float u_pxAngle;
uniform mat4 u_toBody;
uniform vec3 u_sunBody;
uniform vec3 u_sunColor;
uniform float u_sunAng;   // angular radius of the star seen from the planet
uniform vec4 u_ring;
uniform float u_exposure;
uniform sampler2D u_ringTex;  // the opacity profile
uniform vec3 u_ringColor;     // the ring's data colour, linear
out vec4 o_color;
""" + TONEMAP + RING_PROFILE + RAY + """
/**
 * One stretch [ts.x, ts.y] (distances along the ray's ground line) of the annulus, seen from
 * height y0 above the ring plane by a pixel whose rays have db.y in [ey - k, ey + k]. The ray with
 * db.y = s meets the plane at t = -y0 / s, so the stretch covers the db.y interval between
 * -y0 / ts.x and -y0 / ts.y; it is widened to at least 2k (one pixel) around its middle. Returns
 * the covered fraction of the pixel and the distance of the point under the pixel's centre.
 */
vec2 ringStretch(vec2 ts, float y0, float ey, float k) {
    float t1 = max(ts.x, 1e-6);
    float t2 = ts.y;
    if (t2 <= t1) return vec2(0.0);
    float s1 = -y0 / t1;
    float s2 = -y0 / t2;
    float lo = min(s1, s2);
    float hi = max(s1, s2);
    float mid = 0.5 * (lo + hi);
    float hw = max(0.5 * (hi - lo), k);
    float ov = min(mid + hw, ey + k) - max(mid - hw, ey - k);
    if (ov <= 0.0) return vec2(0.0);
    // the pixel centre's place across the (widened) band, mapped onto the stretch, so it is never
    // sampled at its ends (the annulus edges, where the density is 0); a stretch thinner than the
    // pixel is seen whole: sample its middle, or the sample point would wander with the line's
    // sub-pixel phase (dashes where the stretch crosses the planet's shadow)
    float f = clamp((ey - (mid - hw)) / (2.0 * hw), 0.0, 1.0);
    float sr = mix(lo, hi, mix(0.5, f, clamp((hi - lo) / (2.0 * k), 0.0, 1.0)));
    // 1/t is linear in db.y: interpolate there (also fine for y0 = 0, where s1 = s2)
    float u = hi - lo > 0.0 ? (sr - s1) / (s2 - s1) : 0.5;
    float invT = mix(1.0 / t1, 1.0 / t2, u);
    return vec2(ov / (2.0 * k), 1.0 / invT);
}

void main() {
    vec3 d = normalize(v_ray);
    mat3 M = mat3(u_toBody);
    vec3 db = M * d;
    vec3 ob = M * (u_center.xyz * -u_center.w);   // camera in body space, planet radii
    float width = u_ring.y - u_ring.x;
    float t;            // distance along the ray to the shaded ring point
    float ey;           // db.y of the (sub-pixel) ray through it
    float cov = 1.0;    // fraction of the pixel on the annulus
    bool slab = abs(db.y) < 0.5;
    if (!slab) {
        t = -ob.y / db.y;
        if (t <= 0.0) discard;
        ey = db.y;
    } else {
        // Shallow views: the rays across the pixel (db.y within +-k, k = half a pixel) all meet
        // the plane on the line ob.xz + db.xz * t, at t = -ob.y / db.y. Coverage is the part of
        // that +-k window the annulus spans, with each stretch at least one pixel thick, so a
        // ring seen edge-on stays a continuous line instead of a dashed staircase.
        vec2 o = ob.xz;
        vec2 v = db.xz;
        float A = dot(v, v);
        float tc = -dot(o, v) / A;
        float cr = o.x * v.y - o.y * v.x;
        float p2 = cr * cr / A;   // squared distance of the line from the axis
        float ro2 = u_ring.y * u_ring.y;
        if (p2 >= ro2) discard;
        float ho = sqrt((ro2 - p2) / A);
        float ri2 = u_ring.x * u_ring.x;
        float hi = p2 < ri2 ? sqrt((ri2 - p2) / A) : 0.0;
        float k = 0.5 * u_pxAngle * sqrt(1.0 - db.y * db.y);
        vec2 sNear = ringStretch(vec2(tc - ho, tc - hi), ob.y, db.y, k);
        vec2 sFar = ringStretch(vec2(tc + hi, tc + ho), ob.y, db.y, k);
        cov = min(sNear.x + sFar.x, 1.0);
        if (cov <= 0.0) discard;
        t = sNear.x > 0.0 ? sNear.y : sFar.y;
        ey = -ob.y / t;
    }
    vec3 q = vec3(ob.x + db.x * t, 0.0, ob.z + db.z * t);
    float r = length(q.xz);
    // pixel footprint across the rings (planet radii): one pixel moves the hit point by
    // t * pxAngle * |g| with |g|^2 = 1 + (radial . db / db.y)^2 (the RMS over both screen axes)
    float sl = dot(q.xz / max(r, 1e-6), db.xz) / max(abs(ey), 1e-5);
    float fpR = t * u_pxAngle * sqrt(1.0 + sl * sl);
    float fp = fpR / width;
    // the slab case keeps the sample inside the annulus (its stretch coverage anti-aliases the edges)
    float dens = ringOpacity(slab ? clamp(r, u_ring.x, u_ring.y) : r, fpR);
    if (dens * cov < 0.004) discard;

    // the part of the ring behind the planet is hidden by it
    float b;
    float h2;
    float disc;
    vec3 pca;
    rayInfo(d, b, h2, disc, pca);
    float front = 0.0;
    if (b > 0.0) {
        float ts = u_cc / (b + sqrt(max(disc, 0.0)));
        if (ts < t) front = coverage(b, h2, disc);
    }
    // planet shadow: does the path from q towards the star pass through the planet? (soft
    // penumbra, widened by the pixel footprint: seen edge-on a pixel spans a long stretch of ring)
    float shadow = 1.0;
    float bq = dot(q, u_sunBody);
    if (bq < 0.0) {
        float hq = sqrt(max(dot(q, q) - bq * bq, 0.0));
        float pen = max(u_sunAng * -bq, 0.004) + fp * width;
        shadow = mix(0.04, 1.0, smoothstep(1.0 - pen, 1.0 + pen, hq));
    }
    // the data colour (the Sol rings' images bring theirs; without them the ring colour), the
    // dense parts a little brighter, as Saturn's B ring
    vec3 alb = u_ringColor * (0.78 + 0.35 * dens);
    float elev = abs(u_sunBody.y);
    vec3 col = alb * u_sunColor * shadow * (0.30 + 0.70 * elev);
    // seen from the unlit side only light diffusing through the thinner parts arrives
    if (ob.y * u_sunBody.y < 0.0) col *= mix(0.75, 0.3, dens);
    // the profile is the opacity seen face-on; along slanted paths the optical depth grows, so
    // rings get more opaque towards edge-on: a = 1 - (1 - dens)^(1/|mu|)
    float tau = -log(1.0 - min(dens, 0.995));
    float a = (1.0 - exp(-tau / max(abs(db.y), 0.05))) * (1.0 - front) * cov;
    o_color = vec4(tonemap(col) * a, a);
}
"""

    // ---------------------------------------------------------------- star

    /** Emissive disc with quadratic limb darkening, white-yellow centre to orange limb. */
    val starFs = HEADER + PRECISION + """
in vec3 v_ray;
uniform vec4 u_center;
uniform float u_cc;
uniform float u_pxR;
uniform vec3 u_color;
uniform float u_exposure;
out vec4 o_color;
""" + TONEMAP + RAY + """
void main() {
    vec3 d = normalize(v_ray);
    float b;
    float h2;
    float disc;
    vec3 pca;
    rayInfo(d, b, h2, disc, pca);
    float cov = coverage(b, h2, disc);
    if (cov <= 0.0) discard;
    float mu = sqrt(max(disc, 0.0));
    float m = 1.0 - mu;
    float limb = 1.0 - 0.40 * m - 0.15 * m * m;
    vec3 c = mix(vec3(1.0, 0.58, 0.28), vec3(1.0, 0.96, 0.88), sqrt(mu));
    o_color = vec4(tonemap(u_color * c * limb) * cov, cov);
}
"""

    /**
     * Additive corona + glare around the star. u_glow: x = angular radius of the disc, y = glare
     * width (the angular radius, but never below a few pixels), z = intensity, w = fade-out angle.
     */
    val glowFs = HEADER + PRECISION + """
in vec3 v_ray;
uniform vec4 u_center;
uniform vec4 u_glow;
uniform vec3 u_color;
uniform vec3 u_corona;
uniform float u_exposure;
out vec4 o_color;
""" + TONEMAP + """
void main() {
    vec3 d = normalize(v_ray);
    float th = atan(length(cross(d, u_center.xyz)), dot(d, u_center.xyz));
    // past the fade-out the glow adds nothing: skip the exps and the tonemap (blending keeps dst)
    if (th >= u_glow.w) {
        o_color = vec4(0.0);
        return;
    }
    float w = u_glow.y;
    float x = th / u_glow.x;
    // measured from the limb so the disc keeps a crisp edge
    float e = max(th - u_glow.x, 0.0);
    float corona = 0.30 * exp(-max(x - 1.0, 0.0) * 7.0) + 0.08 * exp(-max(x - 1.0, 0.0) * 1.8);
    float glare = 0.35 * exp(-e / (w * 0.22)) + 0.09 * exp(-e / (w * 1.1)) + 0.012 * exp(-e / (w * 4.0));
    float fade = 1.0 - smoothstep(u_glow.w * 0.45, u_glow.w, th);
    vec3 c = (u_corona * corona + u_color * glare) * (u_glow.z * fade);
    o_color = vec4(dither(tonemap(c)), 0.0);
}
"""

    // ---------------------------------------------------------------- one-time equirect bakes

    val bakeVs = HEADER + """
layout(location = 0) in vec2 a_pos;
out vec2 v_ndc;
void main() {
    v_ndc = a_pos;
    gl_Position = vec4(a_pos, 0.0, 1.0);
}
"""

    /**
     * Equirect surface texture: u = longitude (atan(z, x)), v = latitude (asin(y)), so that a body
     * space direction samples exactly the colour baked for it. RGB = sqrt(albedo); A = emission
     * (the lava glow of generated volcanic worlds). The recipe is the body's look
     * ([BodyLooksShader]: one per Sol body, the class ones for generated bodies) in its data colour
     * and class palette. Rendered in horizontal strips (u_rect = v offset, v scale) so no single draw
     * runs long enough to trip a mobile GPU watchdog.
     */
    val bakeFs = HEADER + PRECISION + TYPES + """
in vec2 v_ndc;
uniform int u_look;
uniform vec4 u_seed;     // xyz noise offset, w = 0..1
uniform vec4 u_pal[8];   // linear colours: 0 body, 1 shore, 2 low, 3 high, 4 peak, 5 ice (w: ice latitude), 6 deep, 7 shallow
uniform vec4 u_air;      // the atmosphere colour, linear
uniform vec2 u_rect;
out vec4 o_color;
""" + NOISE + """
/** Cellular craters: at most one per cell, so the 8 cells around q are enough. Albedo offset. */
float craterLayer(vec3 q, float presence) {
    vec3 fl = floor(q);
    vec3 fr = q - fl;
    ivec3 base = ivec3(fl) + ivec3(step(vec3(0.5), fr)) - ivec3(1);
    float v = 0.0;
    for (int k = 0; k < 8; k++) {
        ivec3 c = base + ivec3(k & 1, (k >> 1) & 1, (k >> 2) & 1);
        uint h = pcg(uint(c.x) + pcg(uint(c.y) + pcg(uint(c.z) + 7919u)));
        uint h2 = pcg(h ^ 0x9E3779B9u);
        if (float(h2 & 1023u) * (1.0 / 1023.0) > presence) continue;
        vec3 j = vec3(float(h & 1023u), float((h >> 10) & 1023u), float((h >> 20) & 1023u)) * (1.0 / 1023.0);
        // centre within [0.25, 0.75] of its cell and influence radius 2.1 * rad <= 0.75 keeps the search exact
        vec3 cp = vec3(c) + 0.25 + 0.5 * j;
        float sz = float((h2 >> 10) & 1023u) * (1.0 / 1023.0);
        float rad = 0.08 + 0.27 * sz * sz;
        float dist = length(q - cp) / rad;
        float fresh = float((h2 >> 20) & 1023u) * (1.0 / 1023.0);
        float floorD = 1.0 - smoothstep(0.50, 0.92, dist);
        float rim = exp(-(dist - 0.95) * (dist - 0.95) * 50.0);
        float ej = smoothstep(0.85, 1.05, dist) * (1.0 - smoothstep(1.05, 2.1, dist));
        v += -0.22 * floorD + 0.28 * rim + 0.14 * fresh * fresh * ej;
    }
    return v;
}
/**
 * Darker and more saturated: bright pale albedos come out near-white under the full sun at
 * exposure 1 (ACES compresses their channel differences away).
 */
vec3 toneDown(vec3 c, float gain, float sat) {
    float l = dot(c, vec3(0.30, 0.59, 0.11));
    return max(mix(vec3(l), c, sat), 0.0) * gain;
}
float ridge(vec3 q) {
    return 1.0 - abs(fbm(q) * 2.0 - 1.0);
}

vec3 lava(vec3 p, vec3 sp, out float em) {
    float n1 = fbm(sp * 2.2);
    vec3 wv = vec3(fbm(sp * 1.5 + vec3(2.0)), fbm(sp * 1.5 + vec3(6.0)), fbm(sp * 1.5 + vec3(11.0))) - 0.5;
    float r1 = ridge(sp * 2.6 + wv * 1.3);
    float r2 = ridge(sp * 7.5 + wv * 2.0 + vec3(5.0));
    float cracks = smoothstep(0.93, 0.985, r1) + 0.6 * smoothstep(0.95, 0.99, r2);
    float lakes = smoothstep(0.74, 0.82, fbm(sp * 1.3 + vec3(7.0)));
    em = clamp(cracks + lakes, 0.0, 1.0);
    vec3 crust = mix(vec3(0.025, 0.022, 0.020), vec3(0.075, 0.064, 0.056), n1);
    crust = mix(crust, vec3(0.16, 0.05, 0.02), smoothstep(0.80, 0.93, r1) * 0.4);
    return mix(crust, vec3(0.60, 0.22, 0.05), em);
}

/** One soft dot per cell (centre within [0.3, 0.7], so it never leaks into neighbouring cells). */
float lightDots(vec3 q) {
    vec3 fl = floor(q);
    ivec3 c = ivec3(fl);
    uint h = pcg(uint(c.x) + pcg(uint(c.y) + pcg(uint(c.z) + 1013u)));
    vec3 j = vec3(float(h & 1023u), float((h >> 10) & 1023u), float((h >> 20) & 1023u)) * (1.0 / 1023.0);
    vec3 dq = q - (fl + 0.3 + 0.4 * j);
    float b = float(pcg(h) & 1023u) * (1.0 / 1023.0);
    return exp(-dot(dq, dq) * 30.0) * (0.25 + 0.75 * b);
}

vec3 gas(vec3 p) {
    float w1 = fbm(p * 3.0 + u_seed.xyz) - 0.5;
    float w2 = fbm(p * 9.0 + u_seed.zxy) - 0.5;
    float y = p.y + w1 * 0.11 + w2 * 0.035;
    float ph = u_seed.w * 6.2831853;
    float b1 = sin(y * 15.0 + 0.5 + ph) * 0.5 + 0.5;
    float b2 = sin(y * 38.0 + 2.0 + ph * 2.0) * 0.5 + 0.5;
    float b3 = fbm(vec3(y * 26.0, p.x * 1.5, p.z * 1.5) + u_seed.yzx);
    vec3 cA = vec3(0.60, 0.48, 0.34);
    vec3 cB = vec3(0.38, 0.25, 0.16);
    vec3 cC = vec3(0.74, 0.68, 0.57);
    vec3 cD = vec3(0.28, 0.32, 0.40);
    vec3 col = mix(cA, cB, smoothstep(0.25, 0.75, b1));
    col = mix(col, cC, smoothstep(0.55, 0.95, b2) * 0.45);
    col = mix(col, cD, smoothstep(0.55, 0.95, abs(p.y)) * 0.65);
    col *= 0.82 + 0.36 * b3;
    vec3 sc = normalize(vec3(cos(ph * 3.0) * 0.95, -0.30, sin(ph * 3.0) * 0.95));
    vec3 dp = p - sc;
    float sd = length(vec2(length(dp.xz) * 0.55, dp.y * 1.1));
    float swirl = fbm(p * 22.0 + u_seed.xyz) * 0.04;
    float storm = 1.0 - smoothstep(0.04, 0.13 + swirl, sd);
    col = mix(col, vec3(0.60, 0.27, 0.15), storm * 0.85);
    col = mix(col, vec3(0.78, 0.64, 0.50), (1.0 - smoothstep(0.0, 0.05, sd)) * 0.5);
    return toneDown(col, 0.45, 1.45);
}

vec3 ice(vec3 p, vec3 sp) {
    float n1 = fbm(sp * 2.0);
    vec3 col = mix(vec3(0.46, 0.55, 0.66), vec3(0.72, 0.76, 0.82), n1);
    col = mix(col, vec3(0.28, 0.42, 0.62), smoothstep(0.55, 0.72, fbm(sp * 1.2 + vec3(4.0))) * 0.6);
    float l1 = 1.0 - smoothstep(0.0, 0.022, abs(fbm(sp * 2.6 + vec3(1.0)) - 0.5));
    float l2 = 1.0 - smoothstep(0.0, 0.016, abs(fbm(sp * 6.5 + vec3(3.0)) - 0.5));
    float l3 = 1.0 - smoothstep(0.0, 0.03, abs(vnoise(sp * 18.0) - 0.5));
    col = mix(col, vec3(0.36, 0.20, 0.13), l1 * 0.8);
    col = mix(col, vec3(0.22, 0.30, 0.44), l2 * 0.6);
    col = mix(col, vec3(0.60, 0.66, 0.76), l3 * 0.3 * smoothstep(0.4, 0.6, n1));
    col *= 1.0 + craterLayer(sp * 6.0, 0.2) * 0.4;
    return toneDown(col, 0.48, 1.35);
}

""" + BodyLooksShader.LOOKS + """
void main() {
    float u = v_ndc.x * 0.5 + 0.5;
    float v = u_rect.x + (v_ndc.y * 0.5 + 0.5) * u_rect.y;
    float lon = (u - 0.5) * 6.2831853;
    float lat = (v - 0.5) * 3.14159265;
    vec3 p = vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon));
    vec3 sp = p + u_seed.xyz;
    float a;
    vec3 col = bodyLook(u_look, p, sp, a);
    o_color = vec4(sqrt(clamp(col, 0.0, 1.0)), clamp(a, 0.0, 1.0));
}
"""
}
