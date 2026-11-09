package com.starwake.core.gfx

/**
 * GLSL recipes of the surface bake for every Sol body ([com.starwake.core.world.BodyLooks]): each
 * one paints its body in its Roblox colour (`u_pal[0]`) and class palette (`ShipPalettes.colours`:
 * `u_pal[1..5]` shore, low, high, peak, ice; `u_pal[6..7]` deep and shallow water), with the
 * features that make it recognisable: Earth's oceans, continents and clouds, Luna's maria, Mars's
 * dark provinces, canyon and polar caps, Jupiter's belts and red spot, Saturn's soft bands, the
 * pale ice giants, Io's volcanoes, Europa's cracks. They stand in for the Roblox body images
 * (not in the export, 03-world.md Q5) until the terrain engine generates the looks (D17a).
 *
 * Albedo is linear; the bake stores sqrt(albedo). Needs NOISE, craterLayer, toneDown, ridge and
 * lightDots of [BodyShaders.bakeFs] above it.
 */
internal object BodyLooksShader {
    const val LOOKS = """
#define L_GENERIC_ROCK 0
#define L_EARTH 1
#define L_MERCURY 2
#define L_VENUS 3
#define L_MARS 4
#define L_LUNA 5
#define L_SMALL_MOON 6
#define L_IO 7
#define L_EUROPA 8
#define L_GANYMEDE 9
#define L_CALLISTO 10
#define L_TITAN 11
#define L_TRITON 12
#define L_JUPITER 13
#define L_SATURN 14
#define L_URANUS 15
#define L_NEPTUNE 16
#define L_GENERIC_GAS 17
#define L_GENERIC_ICE_GIANT 18
#define L_GENERIC_ICE 19
#define L_GENERIC_LAVA 20
#define L_GENERIC_OCEAN 21
#define L_GENERIC_MOON 22
#define L_GENERIC_CLOUDY 23

/** The class palette's height ramp (ShipPlanetTexture's knots 0.10 / 0.45 / 0.78). */
vec3 ramp(float t) {
    vec3 shore = u_pal[1].rgb;
    vec3 low = u_pal[2].rgb;
    vec3 high = u_pal[3].rgb;
    vec3 peak = u_pal[4].rgb;
    if (t < 0.10) return shore;
    if (t < 0.45) return mix(shore, low, (t - 0.10) / 0.35);
    if (t < 0.78) return mix(low, high, (t - 0.45) / 0.33);
    return mix(high, peak, (t - 0.78) / 0.22);
}

/** Four octaves of craters scaled by [amount]: an albedo factor offset. */
float craters(vec3 sp, float amount) {
    if (amount <= 0.0) return 0.0;
    return amount * (craterLayer(sp * 2.5, 0.55) + 0.8 * craterLayer(sp * 7.0, 0.65)
        + 0.6 * craterLayer(sp * 19.0, 0.7) + 0.45 * craterLayer(sp * 50.0, 0.75));
}

/** Bright ejecta rays around the crater at [c] (unit), fading out over [reach] radians. */
float rays(vec3 p, vec3 c, float reach) {
    float cosT = dot(p, c);
    if (cosT < 0.0) return 0.0;
    vec3 t1 = normalize(cross(c, abs(c.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 t2 = cross(c, t1);
    float az = atan(dot(p, t2), dot(p, t1));
    float th = acos(clamp(cosT, -1.0, 1.0));
    float streak = smoothstep(0.55, 0.95, vnoise(vec3(az * 9.0, th * 1.5, 3.7)));
    return streak * exp(-th / reach) * smoothstep(0.015, 0.05, th);
}

/** Thin dark cracks where a noise field crosses its middle, [w] wide. */
float cracks(vec3 q, float w) {
    return 1.0 - smoothstep(0.0, w, abs(fbm(q) - 0.5));
}

vec3 earth(vec3 p, vec3 sp) {
    vec3 wv = vec3(fbm(sp * 1.1), fbm(sp * 1.1 + vec3(5.2, 1.3, 2.8)), fbm(sp * 1.1 + vec3(9.1, 4.7, 7.3))) - 0.5;
    float hgt = fbm(sp * 1.6 + wv * 1.4) + (vnoise(sp * 7.0) - 0.5) * 0.10;
    float e = hgt - 0.512;
    float lat = abs(p.y);
    float moist = fbm(sp * 2.3 + vec3(3.3));
    float land = smoothstep(-0.004, 0.004, e);
    // the Terrestrial World palette: deep and shallow water, shore, low (green), high, peak, ice
    vec3 ocean = mix(u_pal[7].rgb * 0.55, u_pal[6].rgb * 0.75, smoothstep(0.0, 0.08, -e));
    float belt = 1.0 - smoothstep(0.08, 0.26, abs(lat - 0.38));
    float dry = smoothstep(0.62, 0.80, (1.0 - moist) * 0.8 + belt * 0.40);
    vec3 green = u_pal[2].rgb * mix(0.38, 0.62, smoothstep(0.3, 0.7, moist));
    vec3 lnd = mix(green, u_pal[1].rgb * 0.55, dry);
    lnd = mix(lnd, u_pal[3].rgb * 0.7, smoothstep(0.09, 0.19, e));
    lnd = mix(lnd, u_pal[5].rgb * 0.9, smoothstep(0.25, 0.31, e + lat * 0.12));
    vec3 col = mix(ocean, lnd, land);
    float ice = smoothstep(u_pal[5].w + 0.17, u_pal[5].w + 0.21, lat + (fbm(sp * 4.0 + vec3(2.0)) - 0.5) * 0.12);
    col = mix(col, u_pal[5].rgb * 0.88, ice);
    // clouds are painted into the image, as Earth's Roblox texture has them
    float cl = fbm(vec3(sp.x, sp.y * 1.9, sp.z) * 2.4 + wv * 2.2 + vec3(20.0));
    float cl2 = fbm(sp * 8.0 + wv * 3.0);
    float cloud = smoothstep(0.52, 0.74, cl * 0.8 + cl2 * 0.25);
    return mix(col, vec3(0.86, 0.88, 0.91), cloud * 0.92);
}

vec3 mercury(vec3 p, vec3 sp) {
    float n = fbm(sp * 2.2);
    vec3 col = mix(ramp(n), u_pal[0].rgb, 0.6) * 0.62;
    // smooth volcanic plains, a little darker and with fewer craters
    float plains = smoothstep(0.56, 0.64, fbm(sp * 1.1 + vec3(7.0)));
    col *= 1.0 - 0.2 * plains;
    col *= max(1.0 + craters(sp, 1.0) * (1.0 - 0.6 * plains), 0.25);
    col += vec3(0.11, 0.105, 0.10) * (rays(p, normalize(vec3(0.3, 0.5, 0.8)), 0.35) + rays(p, normalize(vec3(-0.7, -0.2, -0.4)), 0.25));
    return col;
}

vec3 mars(vec3 p, vec3 sp) {
    float n = fbm(sp * 2.0);
    vec3 col = mix(ramp(n), u_pal[0].rgb, 0.7) * 0.85;
    // dark albedo provinces and bright dusty plains
    float dark = smoothstep(0.52, 0.66, fbm(sp * 0.9 + vec3(3.0)) + 0.15 * (vnoise(sp * 4.0) - 0.5));
    col = mix(col, col * vec3(0.50, 0.43, 0.40), dark * 0.85);
    col *= max(1.0 + 0.45 * craters(sp, 0.6), 0.3);
    // a canyon system just south of the equator
    float lon = atan(p.z, p.x);
    float vy = p.y + 0.12 + 0.025 * sin(lon * 5.0);
    float canyon = (1.0 - smoothstep(0.003, 0.012, abs(vy))) * smoothstep(0.35, 0.75, cos(lon - 1.2));
    col = mix(col, col * 0.42, canyon);
    // the white polar caps
    float cap = smoothstep(0.86, 0.90, abs(p.y) + (fbm(sp * 5.0) - 0.5) * 0.08);
    return mix(col, u_pal[5].rgb * 0.85, cap);
}

vec3 luna(vec3 p, vec3 sp) {
    vec3 col = u_pal[0].rgb * (0.42 + 0.2 * fbm(sp * 3.0));
    // the maria: dark basalt plains, most of them on the hemisphere facing +X
    float m = smoothstep(0.50, 0.60, fbm(sp * 1.3 + vec3(5.0)) + 0.18 * p.x);
    col = mix(col, col * vec3(0.45, 0.45, 0.47), m);
    col *= max(1.0 + craters(sp, 0.9) * (1.0 - 0.7 * m), 0.25);
    return col + vec3(0.13) * rays(p, normalize(vec3(0.6, -0.65, 0.2)), 0.45);
}

vec3 smallMoon(vec3 p, vec3 sp) {
    vec3 col = u_pal[0].rgb * (0.30 + 0.16 * fbm(sp * 4.0));
    col *= max(1.0 + craters(sp * 1.3, 1.3), 0.2);
    // one big impact crater (Stickney on Phobos)
    float d = length(p - normalize(vec3(0.8, 0.1, 0.5))) / 0.42;
    col *= 1.0 - 0.35 * (1.0 - smoothstep(0.6, 1.0, d)) + 0.25 * exp(-(d - 1.0) * (d - 1.0) * 30.0);
    return col;
}

vec3 io(vec3 p, vec3 sp) {
    vec3 base = u_pal[0].rgb * 0.62;
    float n = fbm(sp * 2.4);
    // sulfur yellow to orange, white SO2 frost
    vec3 col = mix(base * vec3(1.05, 0.98, 0.70), base * vec3(0.95, 0.62, 0.30), smoothstep(0.4, 0.7, n));
    col = mix(col, vec3(0.50, 0.49, 0.42), smoothstep(0.62, 0.75, fbm(sp * 1.5 + vec3(4.0))) * 0.7);
    // volcanic centres: dark calderas and red plume deposits around some
    float d1 = lightDots(sp * 9.0);
    float d2 = lightDots(sp * 21.0 + vec3(5.0));
    float halo = lightDots(sp * 4.5 + vec3(11.0));
    col = mix(col, vec3(0.42, 0.11, 0.05), smoothstep(0.08, 0.3, halo) * (1.0 - smoothstep(0.35, 0.8, halo)) * 0.65);
    col = mix(col, vec3(0.035, 0.03, 0.025), clamp((d1 + 0.7 * d2) * 1.5, 0.0, 0.9));
    // darker, reddish poles
    return col * mix(vec3(1.0), vec3(0.75, 0.62, 0.55), smoothstep(0.6, 0.9, abs(p.y)));
}

vec3 europa(vec3 p, vec3 sp) {
    vec3 col = u_pal[0].rgb * (0.55 + 0.12 * fbm(sp * 2.5));
    // reddish-brown mottled chaos terrain
    float chaos = smoothstep(0.55, 0.7, fbm(sp * 1.8 + vec3(2.0)));
    col = mix(col, col * vec3(0.72, 0.58, 0.46), chaos * 0.7);
    // lineae: long thin cracks at two scales
    vec3 line = vec3(0.30, 0.16, 0.09);
    col = mix(col, line, cracks(sp * 2.2 + vec3(1.0), 0.010) * 0.75);
    return mix(col, line * 1.3, cracks(sp * 5.0 + vec3(6.0), 0.007) * 0.45);
}

vec3 ganymede(vec3 p, vec3 sp) {
    vec3 base = u_pal[0].rgb * 0.62;
    // dark ancient regions and bright grooved terrain between them
    float region = smoothstep(0.48, 0.58, fbm(sp * 1.1 + vec3(9.0)));
    float wv = fbm(sp * 1.7) - 0.5;
    float grooves = 0.5 + 0.5 * sin((sp.x * 3.0 + sp.y * 7.0 + wv * 6.0) * 9.0);
    vec3 col = mix(base * (1.12 + 0.14 * grooves), base * vec3(0.52, 0.50, 0.48), region);
    col *= max(1.0 + craters(sp, 0.6), 0.3);
    // frosted polar caps
    return mix(col, vec3(0.55, 0.56, 0.58), smoothstep(0.75, 0.85, abs(p.y)) * 0.7);
}

vec3 callisto(vec3 p, vec3 sp) {
    vec3 col = u_pal[0].rgb * (0.40 + 0.18 * fbm(sp * 2.6));
    col *= max(1.0 + craters(sp, 1.3), 0.3);
    // fresh ice-floored craters as bright specks
    float dots = lightDots(sp * 17.0) + 0.5 * lightDots(sp * 41.0 + vec3(3.0));
    col = mix(col, vec3(0.42, 0.41, 0.40), clamp(dots * 0.9, 0.0, 0.6));
    // the great multi-ring basin
    vec3 c = normalize(vec3(-0.5, 0.25, 0.83));
    float th = acos(clamp(dot(p, c), -1.0, 1.0));
    return col * (1.0 + 0.25 * exp(-th * th * 40.0) + 0.08 * sin(th * 60.0) * exp(-th * 8.0));
}

vec3 titan(vec3 p, vec3 sp) {
    // the orange haze: almost featureless, faint bands, a darker northern hood
    float y = p.y + (fbm(sp * 2.0) - 0.5) * 0.12;
    vec3 col = u_pal[0].rgb * (0.66 + 0.04 * sin(y * 9.0));
    return mix(col, col * vec3(0.78, 0.62, 0.45), smoothstep(0.45, 0.85, y));
}

vec3 triton(vec3 p, vec3 sp) {
    vec3 base = u_pal[0].rgb * 0.62;
    // cantaloupe terrain: dimpled cells
    float cells = craterLayer(sp * 9.0, 0.95);
    vec3 col = base * (0.85 + 0.15 * fbm(sp * 3.0)) * (1.0 + 0.6 * cells);
    // the pinkish southern polar cap with dark plume streaks
    float cap = 1.0 - smoothstep(-0.35, -0.15, p.y + (fbm(sp * 3.0) - 0.5) * 0.15);
    col = mix(col, base * vec3(1.08, 0.94, 0.90), cap);
    float streaks = smoothstep(0.6, 0.8, vnoise(vec3(atan(p.z, p.x) * 6.0, p.y * 2.0, 1.0))) * cap;
    return mix(col, base * 0.45, streaks * 0.5);
}

vec3 venus(vec3 p, vec3 sp) {
    float lon = atan(p.z, p.x);
    // the closed cloud deck: faint bands and the sideways V of the upper clouds
    float v = p.y * 3.0 + 0.6 * abs(sin(lon * 0.5 + 0.3)) * (1.0 - abs(p.y));
    float w = fbm(vec3(p.x * 1.5, p.y * 6.0, p.z * 1.5) + u_seed.xyz) - 0.5;
    float bands = sin(v * 3.0 + w * 4.0) * 0.5 + 0.5;
    vec3 col = mix(u_pal[0].rgb * 0.82, u_air.rgb, 0.35 + 0.35 * bands);
    return toneDown(col * (0.9 + 0.1 * fbm(sp * 6.0)), 0.56, 1.2);
}

vec3 jupiter(vec3 p) {
    float w1 = fbm(p * 3.0 + u_seed.xyz) - 0.5;
    float w2 = fbm(p * 9.0 + u_seed.zxy) - 0.5;
    float y = p.y + w1 * 0.06 + w2 * 0.02;
    // dark belts and pale zones at Jupiter-like latitudes
    float belts = (1.0 - smoothstep(0.03, 0.07, abs(y - 0.12))) + (1.0 - smoothstep(0.03, 0.08, abs(y + 0.14)))
        + 0.6 * (1.0 - smoothstep(0.02, 0.05, abs(y - 0.33))) + 0.6 * (1.0 - smoothstep(0.02, 0.05, abs(y + 0.36)))
        + 0.4 * (1.0 - smoothstep(0.02, 0.04, abs(y - 0.52))) + 0.4 * (1.0 - smoothstep(0.02, 0.04, abs(y + 0.55)));
    vec3 zone = u_pal[4].rgb;
    vec3 belt = u_pal[3].rgb * vec3(0.78, 0.56, 0.40);
    vec3 col = mix(mix(zone, belt, clamp(belts, 0.0, 1.0)), u_pal[0].rgb, 0.18);
    col *= 0.82 + 0.34 * fbm(vec3(y * 30.0, p.x * 2.0, p.z * 2.0) + u_seed.yzx);
    // greyer polar regions
    col = mix(col, col * vec3(0.72, 0.74, 0.80), smoothstep(0.6, 0.9, abs(p.y)));
    // the Great Red Spot at 22 deg S
    vec3 dp = p - normalize(vec3(0.95, -0.37, 0.10));
    float sd = length(vec2(length(dp.xz) * 0.55, dp.y * 1.15));
    float storm = 1.0 - smoothstep(0.05, 0.11 + fbm(p * 22.0 + u_seed.xyz) * 0.03, sd);
    col = mix(col, vec3(0.62, 0.26, 0.12), storm * 0.85);
    col = mix(col, vec3(0.80, 0.62, 0.48), (1.0 - smoothstep(0.0, 0.04, sd)) * 0.4);
    // white ovals further south
    float ov = lightDots(vec3(p.x * 6.0, (p.y + 0.42) * 30.0, p.z * 6.0));
    col = mix(col, zone * 1.08, smoothstep(0.3, 0.6, ov) * (1.0 - smoothstep(0.03, 0.06, abs(p.y + 0.42))));
    return toneDown(col, 0.47, 1.45);
}

vec3 saturn(vec3 p) {
    float w = fbm(p * 2.5 + u_seed.xyz) - 0.5;
    float y = p.y + w * 0.03;
    float b = 0.5 + 0.5 * sin(y * 26.0 + 1.0) * (0.6 + 0.4 * sin(y * 7.0));
    vec3 col = mix(u_pal[4].rgb, u_pal[0].rgb * vec3(0.88, 0.78, 0.62), b * 0.65);
    col *= 0.93 + 0.1 * fbm(vec3(y * 24.0, p.x, p.z) + u_seed.yzx);
    // the pale blue-grey north polar region
    col = mix(col, col * vec3(0.78, 0.84, 0.92), smoothstep(0.75, 0.95, p.y));
    return toneDown(col, 0.50, 1.25);
}

vec3 iceGiant(vec3 p, float bands) {
    vec3 col = u_pal[0].rgb;
    col *= 0.96 + bands * sin(p.y * 14.0 + (fbm(p * 2.0 + u_seed.xyz) - 0.5) * 2.0);
    // the brighter polar haze
    col = mix(col, col * 1.08, smoothstep(0.5, 0.9, p.y));
    return toneDown(col, 0.62, 1.05);
}

vec3 neptune(vec3 p) {
    float w = fbm(p * 3.0 + u_seed.xyz) - 0.5;
    float y = p.y + w * 0.05;
    vec3 base = u_pal[0].rgb;
    vec3 col = base * (0.9 + 0.12 * sin(y * 16.0));
    col = mix(col, base * 0.7, (1.0 - smoothstep(0.03, 0.08, abs(y + 0.3))) * 0.5);
    // the Great Dark Spot at 20 deg S and its white companion cloud
    vec3 dp = p - normalize(vec3(0.6, -0.34, 0.72));
    float sd = length(vec2(length(dp.xz) * 0.6, dp.y * 1.3));
    col = mix(col, base * 0.35, (1.0 - smoothstep(0.04, 0.09, sd)) * 0.8);
    float cd = length(p - normalize(vec3(0.6, -0.27, 0.78)));
    col = mix(col, vec3(0.6, 0.62, 0.66), (1.0 - smoothstep(0.01, 0.04, cd)) * 0.9);
    // bright cirrus streaks at mid latitudes
    float streak = smoothstep(0.72, 0.9, fbm(vec3(p.x * 3.0, y * 40.0, p.z * 3.0) + u_seed.zxy))
        * (1.0 - smoothstep(0.1, 0.25, abs(abs(y) - 0.45)));
    col = mix(col, vec3(0.5, 0.53, 0.58), streak * 0.8);
    return toneDown(col, 0.75, 1.1);
}

/** A generated rocky body: the class ramp over fbm heights, craters, polar ice. */
vec3 genericRock(vec3 p, vec3 sp) {
    vec3 col = mix(ramp(fbm(sp * 2.2)), u_pal[0].rgb, 0.4) * 0.6;
    col *= max(1.0 + craters(sp, 0.7), 0.25);
    float ice = smoothstep(u_pal[5].w, u_pal[5].w + 0.04, abs(p.y) + (fbm(sp * 4.0) - 0.5) * 0.12);
    return mix(col, u_pal[5].rgb * 0.8, ice);
}

/** The recipe of [look]; [em] = emission (generated lava worlds). */
vec3 bodyLook(int look, vec3 p, vec3 sp, out float em) {
    em = 0.0;
    if (look == L_EARTH || look == L_GENERIC_OCEAN) return earth(p, sp);
    if (look == L_MERCURY) return mercury(p, sp);
    if (look == L_VENUS || look == L_GENERIC_CLOUDY) return venus(p, sp);
    if (look == L_MARS) return mars(p, sp);
    if (look == L_LUNA || look == L_GENERIC_MOON) return luna(p, sp);
    if (look == L_SMALL_MOON) return smallMoon(p, sp);
    if (look == L_IO) return io(p, sp);
    if (look == L_EUROPA) return europa(p, sp);
    if (look == L_GANYMEDE) return ganymede(p, sp);
    if (look == L_CALLISTO) return callisto(p, sp);
    if (look == L_TITAN) return titan(p, sp);
    if (look == L_TRITON) return triton(p, sp);
    if (look == L_JUPITER) return jupiter(p);
    if (look == L_SATURN) return saturn(p);
    if (look == L_URANUS) return iceGiant(p, 0.05);
    if (look == L_NEPTUNE) return neptune(p);
    if (look == L_GENERIC_GAS) return gas(p);
    if (look == L_GENERIC_ICE_GIANT) return iceGiant(p, 0.09);
    if (look == L_GENERIC_ICE) return ice(p, sp);
    if (look == L_GENERIC_LAVA) return lava(p, sp, em);
    return genericRock(p, sp);
}
"""
}
