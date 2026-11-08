package com.starwake.core.gfx

/**
 * GLSL ES 3.00 sources for [BodyRenderer]: ray-cast sphere impostors for planets and the star,
 * ring planes, the star glow and the one-time equirect surface bakes.
 *
 * Every body is described relative to the camera in units of its own radius: the uniform
 * `u_center` holds the unit direction to the centre (xyz) and the distance in radii (w), `u_cc` the
 * value |c|²/r² - 1 computed in double on the CPU. The fragment shaders then work relative to the
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
     * Ring density profile of the menu ring (Shaders.ringDensity), re-parametrised so that
     * x = 0 at u_ring.x (inner radius, in planet radii) and 1 at u_ring.y (outer radius).
     */
    private const val RING_DENSITY = """
float hash1(float x) {
    uint h = pcg(uint(int(floor(x)) + 100000));
    return float(h) * (1.0 / 4294967295.0);
}
float noise1(float x) {
    float i = floor(x);
    float f = x - i;
    float u = f * f * (3.0 - 2.0 * f);
    return mix(hash1(i), hash1(i + 1.0), u);
}
/** [fine] = 0 drops the narrowest ringlets (shadows are blurred by the star's size anyway). */
float ringProfile(float x, float fine) {
    if (x < 0.0 || x > 1.0) return 0.0;
    float d = 0.55 + 0.45 * noise1(x * 34.2);
    d *= mix(0.8, 0.6 + 0.4 * noise1(x * 126.0), fine);
    d *= smoothstep(0.0, 0.05, x) * (1.0 - smoothstep(0.92, 1.0, x));
    d *= 1.0 - 0.9 * (smoothstep(0.56, 0.58, x) - smoothstep(0.63, 0.65, x));
    d *= mix(0.35, 1.0, smoothstep(0.0, 0.45, x));
    return d;
}
float ringDensityX(float x) {
    return ringProfile(x, 1.0);
}
float ringX(float r) {
    return (r - u_ring.x) / (u_ring.y - u_ring.x);
}
"""

    /**
     * Ray vs. unit sphere at D * cd (cd unit), camera at the origin, unit ray direction d.
     * b = distance along the ray to the closest-approach point, h2 = squared closest-approach
     * distance, disc = 1 - h2 (passed separately: it is tiny near the horizon), pca =
     * closest-approach point relative to the sphere centre. Far away disc comes from cross(d, cd),
     * which stays accurate for nearly parallel vectors; close by from b² - cc, where both terms
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
uniform vec4 u_ring;      // inner, outer radius (planet radii), z = 1 when ringed
uniform vec4 u_seed;
uniform int u_type;
uniform sampler2D u_tex;
uniform float u_texScale;  // texels per radian (texture height / pi)
uniform float u_exposure;
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
        vec3 sunT = exp(-beta * (u_atmoK.x * rho * airmass(mu, muH)));
        vec3 camT = exp(-beta * (tau + 0.5 * dt));
        sum += sunT * camT * (vis * dt);
        tau += dt;
    }
    trans = exp(-beta * tau);
    float phase = 0.75 * (1.0 + sunD * sunD) + 0.5 * pow(max(sunD, 0.0), 24.0);
    return sum * beta * phase * u_atmoK.y * u_sunColor;
}
"""

    private const val DETAIL = """
/**
 * Fractal albedo detail for close range. Octave j has features of 2^-j radii; only the octaves
 * whose features cover ~4..4*2^N pixels are evaluated, with the finest faded in and the coarsest
 * faded out, so detail grows smoothly while descending instead of popping.
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
        s += (vnoise(q * exp2(j) + u_seed.xyz) - 0.5) * w;
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
    vec2 uv = vec2(atan(ns.z, ns.x) * 0.15915494 + 0.5, asin(clamp(ns.y, -1.0, 1.0)) * 0.31830989 + 0.5);
    // explicit LOD from the pixel footprint (radii -> texels): screen-space derivatives of atan()
    // jump at the seam and would pick the smallest mip there; this never does, and small/far
    // planets get a properly filtered texture instead of shimmering
    float fp = max(tHit * u_pxAngle, 1e-12);
    float lod = log2(max(fp * u_texScale / max(dot(N, -d), 0.25), 1.0));
    vec4 tx = textureLod(u_tex, uv, lod);
    vec3 alb = tx.rgb * tx.rgb;
    // open water (blue-dominant, dark) gets no ground detail
    float water = u_type == T_TERRAN ? clamp((tx.b - tx.r) * 4.0 - 0.3, 0.0, 1.0) : 0.0;
    float det = detail(ns, fp) * (1.0 - water);
    alb *= clamp(1.0 + det * vec3(1.0, 0.92, 0.8), 0.25, 2.0);

    float ndl = dot(N, u_sunDir);
    float soft = u_type == T_GAS ? 0.16 : 0.07;
    float diff = smoothstep(-soft, 1.0, ndl);
    float shadow = 1.0;
    if (u_ring.z > 0.5 && abs(u_sunBody.y) > 1e-4) {
        float t = -nb.y / u_sunBody.y;
        if (t > 0.0) {
            vec3 q = nb + u_sunBody * t;
            shadow = 1.0 - 0.8 * ringProfile(ringX(length(q.xz)), 0.0);
        }
    }
    vec3 sun = u_sunColor * (diff * shadow);
    if (u_atmo.a > 0.0) sun *= exp(-u_atmo.rgb * (u_atmoK.x * airmass(ndl, 0.0)));
    vec3 col = alb * (sun + vec3(0.004));
    float night = 1.0 - smoothstep(-0.06, 0.14, ndl);
    vec3 H = normalize(u_sunDir - d);
    float ndh = max(dot(N, H), 0.0);
    if (u_type == T_LAVA) {
        float e = tx.a * tx.a * (1.0 + 2.0 * det);
        col += vec3(3.0, 0.85, 0.20) * e * (0.3 + 0.7 * night);
    } else if (u_type == T_TERRAN) {
        col += sun * (pow(ndh, 80.0) * 2.5 + pow(ndh, 12.0) * 0.06) * water;
        col += vec3(1.0, 0.70, 0.36) * (tx.a * night * 0.3 * clamp(1.0 + 3.0 * det, 0.0, 2.0));
    } else if (u_type == T_ICE) {
        col += sun * pow(ndh, 24.0) * 0.12;
    }
    return col;
}

void main() {
    vec3 d = normalize(v_ray);
    float b;
    float h2;
    float disc;
    vec3 pca;
    rayInfo(d, b, h2, disc, pca);
    float cov = coverage(b, h2, disc);
    bool atmo = u_atmo.a > 0.0;
    float discA = u_atmo.a * (2.0 + u_atmo.a) + disc;   // A² - h2 without rounding A² - 1
    if (cov <= 0.0 && (!atmo || discA <= 0.0)) discard;
    float xa = sqrt(max(discA, 0.0));
    float x0 = max(-xa, -b);
    vec3 surf = vec3(0.0);
    vec3 sky = vec3(0.0);
    vec3 skyT = vec3(1.0);
    if (cov > 0.0) {
        float sq = sqrt(max(disc, 0.0));
        vec3 N = normalize(pca - d * sq);
        float tHit = u_cc / (b + sq);
        surf = shadeSurface(N, d, tHit);
        if (atmo && -sq > x0) {
            vec3 T;
            vec3 ins = atmosphere(d, pca, h2, x0, -sq, T);
            surf = surf * T + ins;
        }
    }
    if (cov < 1.0 && atmo && xa > x0) sky = atmosphere(d, pca, h2, x0, xa, skyT);
    vec3 ts = tonemap(surf);
    vec3 tk = tonemap(sky);
    // a bright sky hides what is behind it (stars in daylight), a thin one barely does
    // (capped, so the star still shines through: it was drawn before this, being farther away)
    float skyA = clamp(1.0 - dot(skyT, vec3(0.3333)) + min(dot(tk, vec3(0.3, 0.59, 0.11)) * 0.6, 0.4), 0.0, 1.0);
    o_color = vec4(dither(ts * cov + tk * (1.0 - cov)), 1.0 - (1.0 - cov) * (1.0 - skyA));
}
"""

    fun planetFs(atmoSteps: Int, detailOctaves: Int) = HEADER +
        "#define ATMO_STEPS $atmoSteps\n#define DETAIL_OCTAVES $detailOctaves\n" +
        PRECISION + TYPES + PLANET_FS + TONEMAP + NOISE + RING_DENSITY + RAY + ATMOSPHERE + DETAIL + PLANET_MAIN

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
out vec4 o_color;
""" + TONEMAP + NOISE + RING_DENSITY + RAY + """
void main() {
    vec3 d = normalize(v_ray);
    mat3 M = mat3(u_toBody);
    vec3 db = M * d;
    vec3 ob = M * (u_center.xyz * -u_center.w);   // camera in body space, planet radii
    if (abs(db.y) < 1e-7) discard;
    float tp = -ob.y / db.y;
    if (tp <= 0.0) discard;
    vec3 q = ob + db * tp;
    float r = length(q.xz);
    float x = ringX(r);
    float dens = ringDensityX(x);
    if (dens < 0.004) discard;
    // fine ringlets once they are several pixels wide
    float fpx = tp * u_pxAngle / ((u_ring.y - u_ring.x) * max(abs(db.y), 0.05));
    float fine = clamp(0.25 / (520.0 * fpx) - 0.5, 0.0, 1.0);
    dens *= 1.0 - fine * 0.3 * noise1(x * 520.0);

    // the part of the ring behind the planet is hidden by it
    float b;
    float h2;
    float disc;
    vec3 pca;
    rayInfo(d, b, h2, disc, pca);
    float front = 0.0;
    if (b > 0.0) {
        float ts = u_cc / (b + sqrt(max(disc, 0.0)));
        if (ts < tp) front = coverage(b, h2, disc);
    }
    // planet shadow: does the path from q towards the star pass through the planet? (soft penumbra)
    float shadow = 1.0;
    float bq = dot(q, u_sunBody);
    if (bq < 0.0) {
        float hq = sqrt(max(dot(q, q) - bq * bq, 0.0));
        float pen = max(u_sunAng * -bq, 0.004);
        shadow = mix(0.04, 1.0, smoothstep(1.0 - pen, 1.0 + pen, hq));
    }
    vec3 alb = mix(vec3(0.60, 0.54, 0.46), vec3(0.36, 0.33, 0.30), noise1(x * 20.0));
    float elev = abs(u_sunBody.y);
    vec3 col = alb * u_sunColor * shadow * (0.30 + 0.70 * elev);
    // seen from the unlit side only light diffusing through the thinner parts arrives
    if (ob.y * u_sunBody.y < 0.0) col *= mix(0.75, 0.3, dens);
    // optical depth grows along slanted paths: rings get more opaque towards edge-on
    float a = (1.0 - exp(-dens * 1.3 / max(abs(db.y), 0.08))) * (1.0 - front);
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
     * (lava glow, city lights). Rendered in horizontal strips (u_rect = v offset, v scale) so no
     * single draw runs long enough to trip a mobile GPU watchdog.
     */
    val bakeFs = HEADER + PRECISION + TYPES + """
in vec2 v_ndc;
uniform int u_type;
uniform vec4 u_seed;     // xyz noise offset, w = 0..1
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

vec3 terran(vec3 p, vec3 sp, out float lights) {
    vec3 wv = vec3(fbm(sp * 1.1), fbm(sp * 1.1 + vec3(5.2, 1.3, 2.8)), fbm(sp * 1.1 + vec3(9.1, 4.7, 7.3))) - 0.5;
    float hgt = fbm(sp * 1.6 + wv * 1.4) + (vnoise(sp * 7.0) - 0.5) * 0.10;
    float e = hgt - (0.52 + 0.04 * (u_seed.w - 0.5));
    float lat = abs(p.y);
    float moist = fbm(sp * 2.3 + vec3(3.3));
    float land = smoothstep(-0.004, 0.004, e);
    vec3 ocean = mix(vec3(0.016, 0.075, 0.14), vec3(0.004, 0.02, 0.065), smoothstep(0.0, 0.08, -e));
    float belt = 1.0 - smoothstep(0.08, 0.26, abs(lat - 0.38));
    float dry = smoothstep(0.62, 0.80, (1.0 - moist) * 0.8 + belt * 0.40);
    vec3 green = mix(vec3(0.028, 0.07, 0.024), vec3(0.10, 0.12, 0.05), smoothstep(0.3, 0.7, moist));
    vec3 lnd = mix(green, vec3(0.40, 0.31, 0.17), dry);
    lnd = mix(lnd, vec3(0.20, 0.17, 0.14), smoothstep(0.09, 0.19, e));
    lnd = mix(lnd, vec3(0.80, 0.82, 0.86), smoothstep(0.25, 0.31, e + lat * 0.12));
    vec3 col = mix(ocean, lnd, land);
    float ice = smoothstep(0.91, 0.95, lat + (fbm(sp * 4.0 + vec3(2.0)) - 0.5) * 0.12);
    col = mix(col, vec3(0.82, 0.86, 0.92), ice);
    float cl = fbm(vec3(sp.x, sp.y * 1.9, sp.z) * 2.4 + wv * 2.2 + vec3(20.0));
    float cl2 = fbm(sp * 8.0 + wv * 3.0);
    float cloud = smoothstep(0.52, 0.74, cl * 0.8 + cl2 * 0.25);
    col = mix(col, vec3(0.86, 0.88, 0.91), cloud * 0.92);
    lights = land * (1.0 - ice) * (1.0 - cloud * 0.85) * (1.0 - dry * 0.7) * (1.0 - smoothstep(0.06, 0.16, e))
        * smoothstep(0.50, 0.68, fbm(sp * 4.0 + vec3(2.0)))
        * (lightDots(sp * 40.0) + 0.7 * lightDots(sp * 70.0 + vec3(7.0)) + 0.08);
    return col;
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
    return col;
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
    return col;
}

vec3 rock(vec3 p, vec3 sp) {
    float n1 = fbm(sp * 3.0);
    vec3 col = mix(vec3(0.10, 0.092, 0.084), vec3(0.24, 0.215, 0.19), n1);
    col = mix(col, vec3(0.06, 0.058, 0.056), smoothstep(0.55, 0.70, fbm(sp * 1.1 + vec3(8.0))) * 0.8);
    col = mix(col, vec3(0.24, 0.17, 0.11), smoothstep(0.5, 0.8, fbm(sp * 0.8 + vec3(13.0))) * 0.5);
    float c = craterLayer(sp * 2.5, 0.55) + 0.8 * craterLayer(sp * 7.0, 0.65)
        + 0.6 * craterLayer(sp * 19.0, 0.7) + 0.45 * craterLayer(sp * 50.0, 0.75);
    return col * max(1.0 + c, 0.2);
}

void main() {
    float u = v_ndc.x * 0.5 + 0.5;
    float v = u_rect.x + (v_ndc.y * 0.5 + 0.5) * u_rect.y;
    float lon = (u - 0.5) * 6.2831853;
    float lat = (v - 0.5) * 3.14159265;
    vec3 p = vec3(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon));
    vec3 sp = p + u_seed.xyz;
    vec3 col;
    float a = 0.0;
    if (u_type == T_LAVA) col = lava(p, sp, a);
    else if (u_type == T_TERRAN) col = terran(p, sp, a);
    else if (u_type == T_GAS) col = gas(p);
    else if (u_type == T_ICE) col = ice(p, sp);
    else col = rock(p, sp);
    o_color = vec4(sqrt(clamp(col, 0.0, 1.0)), clamp(a, 0.0, 1.0));
}
"""
}
