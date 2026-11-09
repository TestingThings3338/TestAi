package com.starwake.core.gfx

/**
 * GLSL of the cloud deck ([CloudField]), shared by the planet impostor ([BodyShaders]) and the
 * terrain ([TerrainShaders]) so both draw the same clouds: the field (weather map + two octaves of
 * tiling 3-D noise, each fading to its mean once smaller than the pixel through explicit LODs), the
 * deck's opacity, its shading (lit from the sun's side: the map a little towards the sun tells
 * whether a point lies behind a thicker part; dark underneath where it is thick) and its shadow.
 *
 * Points of the deck are given in the deck's own frame (the body's axes, turned by the slow drift):
 * a unit direction `n` for the map, a position `q` in metres for the billows and puffs (any
 * multiple of the billow period may be added to it: the terrain passes camera-relative positions)
 * and the bank octave's own tile coordinates `qb` (`cloudBank(q)` of the true position, any whole
 * number added).
 */
object CloudShaders {
    /** Texel size (m) of the two noise octaves: period / [CloudField.NOISE_SIZE]. */
    private const val BILLOW_TEXEL = CloudField.BILLOW_PERIOD / CloudField.NOISE_SIZE
    private const val PUFF_TEXEL = CloudField.PUFF_PERIOD / CloudField.NOISE_SIZE
    private const val BANK_TEXEL = CloudField.BANK_PERIOD / CloudField.NOISE_SIZE

    /** [CloudField.BANK] / [CloudField.BANK_PERIOD] as a GLSL mat3 (column-major): metres → tiles of the bank octave. */
    private val BANK_MATRIX: String = run {
        val b = CloudField.BANK
        val k = 1.0 / CloudField.BANK_PERIOD
        "mat3(${b[0] * k}, ${b[3] * k}, ${b[6] * k}, ${b[1] * k}, ${b[4] * k}, ${b[7] * k}, ${b[2] * k}, ${b[5] * k}, ${b[8] * k})"
    }

    const val UNIFORMS = """
uniform sampler2D u_cloudMap;            // the weather map (equirect, R)
uniform highp sampler3D u_cloudNoise;    // tiling detail: R billows, G puffs
uniform mat4 u_toCloud;                  // world -> the deck's frame (rotation only)
uniform vec4 u_cloud;                    // x = field threshold (>= 1.5: no deck), y = shadow strength, z = deck radius (m), w = map texels per metre
uniform vec4 u_cloudCol;                 // rgb = colour (linear), a = opacity of the whole deck
"""

    val FUNCTIONS = """
/** The weather map at unit direction n, filtered for a footprint of fp metres. */
float cloudMap(vec3 n, float fp) {
    float lon = abs(n.x) + abs(n.z) < 1e-7 ? 0.0 : atan(n.z, n.x);
    vec2 uv = vec2(lon * 0.15915494 + 0.5, asin(clamp(n.y, -1.0, 1.0)) * 0.31830989 + 0.5);
    return textureLod(u_cloudMap, uv, log2(max(fp * u_cloud.w, 1.0))).r;
}
/** Metres (the deck's frame) -> tile coordinates of the bank octave. */
vec3 cloudBank(vec3 q) {
    return $BANK_MATRIX * q;
}
/**
 * The detail on top of the map: banks at qb (tile coordinates, [cloudBank]), billows and puffs at
 * q (m). x = the field's offset, y = the puffs alone (the deck's texture).
 */
vec2 cloudDetail(vec3 q, vec3 qb, float fp) {
    float d0 = textureLod(u_cloudNoise, qb, log2(max(fp * ${1.0 / BANK_TEXEL}, 1.0))).r;
    float d1 = textureLod(u_cloudNoise, q * ${1.0 / CloudField.BILLOW_PERIOD}, log2(max(fp * ${1.0 / BILLOW_TEXEL}, 1.0))).r;
    float d2 = textureLod(u_cloudNoise, q * ${1.0 / CloudField.PUFF_PERIOD}, log2(max(fp * ${1.0 / PUFF_TEXEL}, 1.0))).g;
    return vec2((d0 - 0.5) * ${CloudField.BANK_GAIN} + (d1 - 0.5) * ${CloudField.BILLOW_GAIN} + (d2 - 0.5) * ${CloudField.PUFF_GAIN}, d2 - 0.5);
}
float cloudAlpha(float v) {
    return smoothstep(u_cloud.x - ${CloudField.EDGE_LOW}, u_cloud.x + ${CloudField.EDGE_HIGH}, v) * u_cloudCol.a;
}
/**
 * The deck at (n, q, qb) seen under a footprint of fp metres: rgb = radiance (linear), a = opacity.
 * sunC = direction to the star in the deck's frame, sunLight = its radiance at the deck, amb = the
 * sky's light on it, below = 1 when seen from underneath.
 */
vec4 cloudShade(vec3 n, vec3 q, vec3 qb, float fp, vec3 sunC, vec3 sunLight, vec3 amb, float below) {
    float m = cloudMap(n, fp);
    vec2 det = cloudDetail(q, qb, fp);
    float v = m + det.x;
    float a = cloudAlpha(v);
    if (a < 0.004) return vec4(0.0);
    float towardSun = cloudMap(normalize(n + sunC * 0.012), max(fp, 20000.0));
    float thick = clamp((v - u_cloud.x) * 3.2, 0.0, 1.0);
    float day = smoothstep(-0.07, 0.32, dot(n, sunC));
    // lit from the sun's side: darker behind a thicker part of the weather, and in the deck's own folds
    float shade = clamp(0.86 + (m - towardSun) * 2.4 + det.x * 0.9, 0.36, 1.08);
    float top = shade * (0.72 + 0.28 * thick);
    float base = mix(0.86, 0.30, thick) * (1.0 + det.y * 0.9 - det.x * 0.6);
    vec3 col = u_cloudCol.rgb * (sunLight * (day * mix(top, base, below)) + amb * (1.0 + det.y * 0.5));
    return vec4(col, a);
}
/** Sunlight left under the deck at the point (n, q) where the ray to the star crosses it. */
float cloudShadow(vec3 n, vec3 q, vec3 qb, float fp, float detail) {
    float v = cloudMap(n, fp);
    if (detail > 0.0) v += cloudDetail(q, qb, max(fp, 400.0)).x * detail;
    return 1.0 - u_cloud.y * cloudAlpha(v);
}
"""
}
