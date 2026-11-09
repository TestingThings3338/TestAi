#!/usr/bin/env python3
"""Synthesises every sound effect of 09 Appendix A.1, A.2 and the warning alarms (A.4b) from scratch.

Original procedural synthesis (DECISIONS 36): no recordings, no downloads; numpy/scipy with fixed seeds,
so a rerun gives the same files. Each sound follows its inventory description (band, length, envelope,
attack, tail); loops are built from material that is exactly periodic in the loop length (sines with a
whole number of cycles, noise synthesised in the frequency domain, frequency-domain filters), so they
wrap without a seam. Output: mono 32 kHz OGG Vorbis (q5) in app/src/main/assets/audio/sfx/.

Loops are written with LOOP_PAD (0.128 s) of their own wrap-around on both ends: the codec's start and end
artefacts fall into that padding, which every loader cuts off (SoundBank.loopPadded), so the decoded loop
is exactly one period and wraps without a click.

Levels: each file is mastered at the level that, times its Roblox gain (ShipConfig.Audio), gives the
intended balance in the mix (see LEVELS); SoundBank.HEADROOM is 1.0 because the files carry the headroom.

    python3 tools/audio/synth_sfx.py            # all
    python3 tools/audio/synth_sfx.py engine boost   # some
    python3 tools/audio/synth_sfx.py --wav <dir>    # also keep 32-bit WAVs there (listening)
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import audiolib as al  # noqa: E402

R = al.SFX_RATE
LOOP_PAD = al.LOOP_PAD_SECONDS

# Loudness (LUFS) for loops and long cues, sample peak (dBFS) for short transients. The engine hum is the
# reference (-24 LUFS); Thrust (gain 0.22) and Decel (0.18) are mastered hot so that at full amount they sit
# at the engine's full level, as ShipConfig's comment says the Roblox levels were aligned; the rest follows.
LEVELS = {
    "engine": ("lufs", -24.0),
    "thrust": ("lufs", -14.0),
    "decel": ("lufs", -15.0),
    "rcs": ("lufs", -29.0),
    "rumble": ("lufs", -22.0),
    "cruise": ("lufs", -23.0),
    "fa_switch": ("peak", -6.0),
    "fa_on": ("peak", -16.0),
    "fa_off": ("peak", -16.0),
    "hit1": ("peak", -12.0),
    "hit2": ("peak", -12.0),
    "hit3": ("peak", -12.0),
    "warp_enter": ("peak", -9.0),
    "warp_exit": ("peak", -9.0),
    "warp_travel": ("lufs", -27.0),
    "boost": ("peak", -4.0),
    "cruise_on": ("peak", -6.0),
    "cruise_off": ("peak", -4.0),
    "clamp": ("peak", -4.0),
    "release": ("peak", -8.0),
    "touchdown": ("peak", -3.0),
    "warning_phase1": ("peak", -9.0),
    "warning_phase2": ("lufs", -20.0),
    "overheat_loop": ("lufs", -21.0),
}


def level(name, x):
    kind, v = LEVELS[name]
    if kind == "lufs":
        return al.to_lufs(x, R, v, ceiling_db=-1.0)
    return al.to_peak(x, v)


# ============================================================================= A.1 flight layers (loops)

def engine():
    """Engine base hum: a 55 Hz hum with harmonics, slow beating, a faint turbine whine, a soft noise bed."""
    n = int(8.0 * R)
    x = np.zeros(n)
    for k, a in [(1, 1.0), (2, 0.62), (3, 0.34), (4, 0.22), (5, 0.08), (6, 0.10), (8, 0.04)]:
        x += a * al.loop_sine(55.0 * k, n, R, phase=0.7 * k)
        x += 0.35 * a * al.loop_sine(55.0 * k + 0.25 * k, n, R, phase=1.9 * k)  # slow beating
    # turbine whine, a little vibrato (whole cycles)
    t = np.arange(n) / R
    vib = 0.004 * al.loop_sine(0.5, n, R)
    f_wh = al.loop_freq(1210.0, n, R)
    whine = np.sin(2 * np.pi * f_wh * t + 2 * np.pi * f_wh * np.cumsum(vib) / R)
    x += 0.035 * whine * al.loop_lfo(0.25, n, R, 0.3)
    bed = al.periodic_noise(n, R, lambda f: al.band(30, 420, 2)(f) * al.pink(-3)(f), seed=101)
    x += 0.25 * bed * al.loop_lfo(0.375, n, R, 0.2, 1.0)
    x = al.soft_clip(0.6 * x / np.max(np.abs(x)), 1.4)
    return level("engine", x)


def thrust():
    """Muffled engine roar centred 0.5-1 kHz (rocket engine heard from inside), a low rumble under it."""
    n = int(10.0 * R)
    roar = al.periodic_noise(n, R, lambda f: al.peak_shape(720.0, 0.75)(f) * al.band(60, 2200, 3)(f), seed=201)
    low = al.periodic_noise(n, R, lambda f: al.band(40, 180, 3)(f), seed=202)
    crackle = al.periodic_noise(n, R, lambda f: al.band(1500, 5000, 2)(f), seed=203)
    flutter = (al.loop_lfo(5.3, n, R, 0.06) * al.loop_lfo(8.7, n, R, 0.05, 1.3) * al.loop_lfo(2.1, n, R, 0.08, 0.4))
    x = (roar * 1.0 + low * 0.45) * flutter + 0.05 * crackle * al.loop_lfo(13.1, n, R, 0.6)
    x = al.soft_clip(0.35 * x / np.std(x), 1.2)
    return level("thrust", x)


def decel():
    """The same family, lower: retro thrusters, a peak at 350-500 Hz with a slight chuffing."""
    n = int(10.0 * R)
    roar = al.periodic_noise(n, R, lambda f: al.peak_shape(420.0, 0.7)(f) * al.band(45, 1500, 3)(f), seed=211)
    low = al.periodic_noise(n, R, lambda f: al.band(30, 140, 3)(f), seed=212)
    chuff = al.loop_lfo(6.9, n, R, 0.22) * al.loop_lfo(3.1, n, R, 0.1, 2.0)
    x = (roar + 0.6 * low) * chuff
    x = al.soft_clip(0.35 * x / np.std(x), 1.2)
    return level("decel", x)


def rcs():
    """Dull gas hiss (0.8-3.5 kHz, highs rolled off); the first 1.5 s of each round stronger, as the original."""
    n = int(8.0 * R)
    hiss = al.periodic_noise(n, R, lambda f: al.band(700, 3200, 2)(f) * al.pink(-2)(f), seed=301)
    t = np.arange(n) / R
    # +4.5 dB burst from 0.15 s to 1.65 s with 0.12 s ramps; base level at the seam
    burst = np.clip((t - 0.15) / 0.12, 0, 1) * np.clip((1.65 - t) / 0.12, 0, 1)
    g = 1.0 + (10 ** (4.5 / 20) - 1.0) * burst
    flutter = al.loop_lfo(9.5, n, R, 0.08) * al.loop_lfo(4.25, n, R, 0.06, 0.7)
    x = hiss * g * flutter
    return level("rcs", x)


def rumble():
    """Hull rumble ("machine rumble"): 25-200 Hz with a 60 Hz weight, slow surges, a light mid rattle."""
    n = int(10.0 * R)
    low = al.periodic_noise(n, R, lambda f: al.peak_shape(62.0, 0.9)(f) * al.band(22, 260, 3)(f), seed=401)
    tones = 0.25 * al.loop_sine(31.0, n, R) + 0.18 * al.loop_sine(47.0, n, R, 1.1) + 0.1 * al.loop_sine(93.0, n, R, 2.0)
    surge = al.loop_lfo(0.7, n, R, 0.25) * al.loop_lfo(1.9, n, R, 0.15, 0.5) * al.loop_lfo(3.3, n, R, 0.1, 2.1)
    rattle = al.periodic_noise(n, R, lambda f: al.band(280, 900, 3)(f), seed=402)
    rattle *= np.clip(al.loop_lfo(11.0, n, R, 1.0) * al.loop_lfo(0.5, n, R, 0.6), 0, None)
    x = (low + tones) * surge + 0.07 * rattle
    x = al.soft_clip(0.4 * x / np.std(x), 1.3)
    return level("rumble", x)


def cruise():
    """Deep trembling hum 60-120 Hz: a quivering chord with a ~6.5 Hz tremolo and a little narrow noise."""
    n = int(10.0 * R)
    t = np.arange(n) / R
    x = np.zeros(n)
    for f, a, p in [(60.0, 1.0, 0.0), (90.0, 0.55, 1.0), (120.0, 0.42, 2.0), (180.0, 0.12, 0.5)]:
        fm = al.loop_freq(f, n, R)
        wob = 0.6 * al.loop_sine(0.3, n, R, p)  # slight pitch drift (Hz), whole cycles
        x += a * np.sin(2 * np.pi * fm * t + 2 * np.pi * np.cumsum(wob) / R + p)
    quiver = al.loop_lfo(6.5, n, R, 0.45) * al.loop_lfo(0.9, n, R, 0.15, 1.0)
    grit = al.periodic_noise(n, R, lambda f: al.band(55, 130, 4)(f), seed=501)
    x = x * quiver + 0.35 * grit * al.loop_lfo(6.5, n, R, 0.5, 0.4)
    x = al.soft_clip(0.5 * x / np.max(np.abs(x)), 1.5)
    return level("cruise", x)


# ============================================================================= A.2 cues

def _modes(t, base, ratios, decays, amps, seed, start=0.0, detune=0.003):
    rng = np.random.default_rng(seed)
    x = np.zeros_like(t)
    u = np.clip(t - start, 0, None)
    on = t >= start
    for r, d, a in zip(ratios, decays, amps):
        f = base * r * (1 + detune * rng.standard_normal())
        x += on * a * np.sin(2 * np.pi * f * u + rng.uniform(0, 6.28)) * np.exp(-u / d)
    return x


def fa_switch():
    """Switch click (0.7 s): a snap and its bounce, small metal resonances, a short body knock."""
    t = al.t_axis(0.7, R)
    x = np.zeros_like(t)
    for start, amp, seed in [(0.020, 1.0, 601), (0.088, 0.45, 602)]:
        burst = al.noise(t.size, seed) * al.env_ad(t, 0.0004, 0.0018, start)
        ring = _modes(t, 2150.0, [1.0, 2.07, 3.31, 4.6], [0.030, 0.018, 0.012, 0.008], [0.5, 0.35, 0.25, 0.15], seed, start)
        body = np.sin(2 * np.pi * 185.0 * np.clip(t - start, 0, None)) * al.env_ad(t, 0.0008, 0.022, start)
        x += amp * (0.8 * al.butter(burst, R, "high", 1500, 2) + ring + 0.5 * body)
    tail = _modes(t, 3300.0, [1.0, 1.5, 2.2], [0.20, 0.12, 0.08], [0.04, 0.03, 0.02], 603, 0.02)
    x += tail
    x = al.fade(x, R, 0.0, 0.05)
    return level("fa_switch", x)


def _chirp(f0, f1, dur, total, seed):
    t = al.t_axis(total, R)
    seg = t[t < dur]
    ph, f = al.sweep(f0, f1, seg, "exp")
    ph = np.concatenate([ph, ph[-1] + 2 * np.pi * f1 * (t[t >= dur] - dur + 1.0 / R)])
    f = np.concatenate([f, np.full(t.size - f.size, f1)])
    # harmonics only below 0.42 x the sample rate (no aliasing back down)
    lim = 0.42 * R

    def h(k):
        return np.clip((lim - k * f) / 1500.0, 0.0, 1.0)
    tone = np.sin(ph) + 0.18 * h(3) * np.sin(3 * ph) + 0.06 * h(5) * np.sin(5 * ph)
    shimmer = 1.0 + 0.12 * np.sin(2 * np.pi * 38.0 * t)
    env = np.clip(t / 0.006, 0, 1) * np.where(t < dur, 1.0, np.exp(-(t - dur) / 0.035))
    x = tone * shimmer * env
    # a soft electronic echo
    d = int(0.055 * R)
    x[d:] += 0.25 * x[:-d]
    return al.fade(x, R, 0.0, 0.02)


def fa_on():
    """Rising chirp 1.6 -> 3.5 kHz, 0.46 s (electronic computer tone)."""
    return level("fa_on", _chirp(1600.0, 3500.0, 0.30, 0.46, 701))


def fa_off():
    """Falling chirp 2.2 -> 1.2 kHz, 0.39 s."""
    return level("fa_off", _chirp(2200.0, 1200.0, 0.26, 0.39, 702))


def _hit(base, seed, length, brightness):
    t = al.t_axis(length, R)
    # free-plate-like inharmonic modes, higher ones decaying faster
    ratios = [1.0, 1.59, 2.14, 2.30, 2.65, 2.92, 3.60, 4.15, 5.40, 6.20]
    decays = [0.55, 0.42, 0.30, 0.28, 0.22, 0.19, 0.14, 0.11, 0.07, 0.05]
    amps = [1.0, 0.7, 0.55, 0.45, 0.4, 0.3, 0.25, 0.2, 0.15 * brightness, 0.1 * brightness]
    x = _modes(t, base, ratios, decays, amps, seed, 0.004)
    crack = al.butter(al.noise(t.size, seed + 1), R, "high", 900, 2) * al.env_ad(t, 0.0005, 0.006, 0.004)
    thump = np.sin(2 * np.pi * 70.0 * t * (1 - 0.3 * t)) * al.env_ad(t, 0.002, 0.05, 0.004)
    x = 0.55 * x + 0.9 * crack + 0.8 * thump
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.3, 1.6)
    return al.fade(x, R, 0.0, 0.08)


def hit1():
    """Metal impact, heavy (base ~180 Hz)."""
    return level("hit1", _hit(182.0, 801, 0.95, 1.0))


def hit2():
    """Metal impact, lighter and brighter (base ~265 Hz)."""
    return level("hit2", _hit(265.0, 811, 0.8, 1.4))


def hit3():
    """Metal impact, deep and dull (base ~140 Hz)."""
    return level("hit3", _hit(141.0, 821, 1.05, 0.6))


def warp_enter():
    """Jump entry: a 2 s charging rise into a hit exactly 2.0 s in, then an airy decaying tail (4.2 s)."""
    t = al.t_axis(4.2, R)
    hit_t = 2.0
    pre = t < hit_t
    # rising whoosh: noise through a band sweeping 200 Hz -> 3.2 kHz, accelerating
    nz = al.noise(t.size, 901)
    u = np.clip(t / hit_t, 0, 1)
    whoosh = al.svf(nz, R, 200.0 * 16.0 ** (u ** 1.6), q=1.2)
    rise_env = (u ** 2.2) * pre
    # charging tone 80 -> 640 Hz with harmonics
    ph, _ = al.sweep(80.0, 640.0, t, "exp")
    ph = np.where(pre, ph, 0)
    charge = (np.sin(ph) + 0.4 * np.sin(2 * ph) + 0.2 * np.sin(3 * ph)) * (u ** 1.6) * pre
    x = 0.55 * whoosh * rise_env + 0.35 * charge
    # the hit: a deep boom with a pitch drop, a bright crack, a whoomp of air
    v = np.clip(t - hit_t, 0, None)
    post = t >= hit_t
    boom = np.sin(2 * np.pi * np.cumsum(40.0 + 50.0 * np.exp(-v / 0.3)) / R * post) * np.exp(-v / 0.45) * post
    crack = al.butter(al.noise(t.size, 902), R, "high", 2000, 2) * al.env_ad(t, 0.001, 0.05, hit_t)
    air = al.butter(al.noise(t.size, 903), R, "band", [300, 4000], 2) * al.env_ad(t, 0.01, 0.6, hit_t)
    x += 1.2 * boom + 0.7 * crack + 0.45 * air
    # tail: low rumble and a shimmer, through a large space
    rumble = al.butter(al.noise(t.size, 904), R, "low", 160, 2) * al.env_ad(t, 0.05, 0.9, hit_t)
    x += 0.8 * rumble
    x = x + 0.35 * al.convolve(x, al.reverb_ir(2.5, R, 0.7, 905, 5000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.2, 1.4)
    return level("warp_enter", al.fade(x, R, 0.0, 0.3))


def warp_exit():
    """Jump exit: a short in-rush ending in a burst at 0.25 s, a falling tone and a decaying airy tail (3 s)."""
    t = al.t_axis(3.0, R)
    hit_t = 0.25
    nz = al.noise(t.size, 911)
    inrush = al.butter(nz, R, "band", [800, 6000], 2) * np.clip(t / hit_t, 0, 1) ** 3 * (t < hit_t)
    v = np.clip(t - hit_t, 0, None)
    post = t >= hit_t
    burst = al.butter(al.noise(t.size, 912), R, "band", [150, 5000], 2) * al.env_ad(t, 0.004, 0.35, hit_t)
    ph, _ = al.sweep(620.0, 90.0, t, "exp")
    fall = (np.sin(ph) + 0.3 * np.sin(2 * ph)) * np.exp(-v / 0.7) * post
    boom = np.sin(2 * np.pi * 55.0 * v) * np.exp(-v / 0.35) * post
    x = 0.6 * inrush + 0.8 * burst + 0.35 * fall + 0.9 * boom
    x = x + 0.4 * al.convolve(x, al.reverb_ir(2.0, R, 0.6, 913, 5000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.2, 1.4)
    return level("warp_exit", al.fade(x, R, 0.0, 0.3))


def warp_travel():
    """Tunnel loop: swirling wind (band centre moving), a pulsing low drone and a slow phasing shimmer (8 s)."""
    n = int(8.0 * R)
    x = np.zeros(n)
    centers = [180.0, 360.0, 720.0, 1440.0, 2880.0]
    for i, c in enumerate(centers):
        nz = al.periodic_noise(n, R, al.peak_shape(c, 0.35), seed=921 + i)
        # each band swells in turn: the swirl rotates through the spectrum twice per loop
        gain = np.clip(al.loop_sine(0.25, n, R, -2 * np.pi * i / len(centers)), 0, None) ** 1.5
        x += nz * (0.25 + gain)
    drone = (al.loop_sine(55.0, n, R) + 0.6 * al.loop_sine(82.5, n, R, 1.0) + 0.3 * al.loop_sine(110.0, n, R, 2.0))
    drone *= al.loop_lfo(2.0, n, R, 0.35)
    shimmer = al.periodic_noise(n, R, al.band(3000, 9000, 2), seed=929) * al.loop_lfo(0.5, n, R, 0.7)
    x = 0.6 * x / np.std(x) + 0.7 * drone / np.std(drone) + 0.15 * shimmer
    x = al.soft_clip(0.4 * x / np.std(x), 1.2)
    return level("warp_travel", x)


def boost():
    """Fire burst (2.3 s): valve tick, ignition crack and pressure thump at 0.12 s, a roar, a hissing tail."""
    t = al.t_axis(2.3, R)
    ig = 0.12
    valve = al.butter(al.noise(t.size, 1001), R, "band", [2000, 6000], 2) * al.env_ad(t, 0.002, 0.02, 0.03) * 0.15
    crack = al.butter(al.noise(t.size, 1002), R, "high", 1200, 2) * al.env_ad(t, 0.0008, 0.015, ig)
    v = np.clip(t - ig, 0, None)
    thump = np.sin(2 * np.pi * (75.0 * v - 40.0 * v * v)) * al.env_ad(t, 0.004, 0.12, ig)
    roar = al.butter(al.noise(t.size, 1003), R, "band", [180, 3200], 2)
    roar_env = al.env_ad(t, 0.03, 0.45, ig) * (1 + 0.15 * np.sin(2 * np.pi * 11 * t))
    hiss = al.butter(al.noise(t.size, 1004), R, "band", [3000, 9000], 2) * al.env_ad(t, 0.08, 0.7, ig + 0.05)
    x = valve + 0.9 * crack + 1.1 * thump + 0.9 * roar * roar_env + 0.35 * hiss
    x = x + 0.2 * al.convolve(x, al.reverb_ir(1.2, R, 0.35, 1005, 6000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.3, 1.5)
    return level("boost", al.fade(x, R, 0.0, 0.25))


def cruise_on():
    """Swelling rumble with an air burst (3.9 s): a quiet 0.3 s lead, a swell to ~1.8 s, the burst, a long tail."""
    t = al.t_axis(3.9, R)
    lead = 0.3
    peak = 1.8
    u = np.clip((t - lead) / (peak - lead), 0, 1)
    swell_env = np.where(t < peak, u ** 2.0, np.exp(-(t - peak) / 0.9))
    rumble = al.butter(al.noise(t.size, 1101), R, "low", 220, 3)
    ph, _ = al.sweep(38.0, 78.0, t, "exp")
    tone = np.sin(ph) + 0.5 * np.sin(2 * ph)
    burst = al.butter(al.noise(t.size, 1102), R, "band", [400, 5000], 2) * al.env_ad(t, 0.02, 0.45, peak)
    # the burst's band sweeps down a little: a whoosh
    whoosh = al.svf(al.noise(t.size, 1103), R, 600.0 + 2400.0 * np.exp(-np.clip(t - peak, 0, None) / 0.35), q=0.8)
    whoosh *= al.env_ad(t, 0.03, 0.5, peak)
    x = 0.9 * rumble * swell_env + 0.6 * tone * swell_env + 0.6 * burst + 0.5 * whoosh
    x = x + 0.25 * al.convolve(x, al.reverb_ir(1.6, R, 0.5, 1104, 4000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.2, 1.4)
    return level("cruise_on", al.fade(x, R, 0.02, 0.4))


def cruise_off():
    """Deep boom with a rumbling, reverberant tail (4.4 s): pitch drop 70 -> 35 Hz, slow surging rumble."""
    t = al.t_axis(4.4, R)
    st = 0.015
    v = np.clip(t - st, 0, None)
    f = 35.0 + 35.0 * np.exp(-v / 0.25)
    boom = np.sin(2 * np.pi * np.cumsum(f) / R) * al.env_ad(t, 0.006, 0.55, st)
    crack = al.butter(al.noise(t.size, 1201), R, "band", [200, 2500], 2) * al.env_ad(t, 0.002, 0.04, st)
    tail = al.butter(al.noise(t.size, 1202), R, "low", 180, 3) * al.env_ad(t, 0.15, 1.3, st)
    tail *= 1.0 + 0.3 * np.sin(2 * np.pi * 1.7 * t) + 0.2 * np.sin(2 * np.pi * 3.1 * t + 1)
    x = 1.3 * boom + 0.4 * crack + 0.9 * tail
    x = x + 0.4 * al.convolve(x, al.reverb_ir(3.0, R, 1.0, 1203, 1500))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.4, 1.8)
    return level("cruise_off", al.fade(x, R, 0.0, 0.5))


def clamp():
    """Heavy metal clamp (1.6 s): a deep thunk, a big metallic ring and a short mechanical rattle."""
    t = al.t_axis(1.6, R)
    st = 0.01
    v = np.clip(t - st, 0, None)
    thunk = np.sin(2 * np.pi * (95.0 * v - 25.0 * v * v)) * al.env_ad(t, 0.002, 0.09, st)
    knock = al.butter(al.noise(t.size, 1301), R, "band", [120, 1600], 2) * al.env_ad(t, 0.0008, 0.03, st)
    ring = _modes(t, 228.0, [1.0, 1.47, 2.09, 2.71, 3.38, 4.4, 5.9], [0.9, 0.7, 0.5, 0.35, 0.25, 0.15, 0.1],
                  [1.0, 0.7, 0.5, 0.4, 0.3, 0.2, 0.12], 1302, st)
    rattle = np.zeros_like(t)
    rng = np.random.default_rng(1303)
    for k in range(7):
        s = st + 0.05 + 0.035 * k + 0.01 * rng.random()
        rattle += (0.6 ** k) * al.butter(al.noise(t.size, 1310 + k), R, "band", [900, 4000], 2) * al.env_ad(t, 0.0005, 0.006, s)
    x = 1.0 * thunk + 0.7 * knock + 0.35 * ring + 0.5 * rattle
    x = x + 0.2 * al.convolve(x, al.reverb_ir(1.0, R, 0.3, 1304, 5000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.3, 1.5)
    return level("clamp", al.fade(x, R, 0.0, 0.2))


def release():
    """Air release (5 s): a fast-onset pressurised hiss whose peak slides 3 kHz -> 1.4 kHz as it decays."""
    t = al.t_axis(5.0, R)
    nz = al.noise(t.size, 1401)
    x = al.svf(nz, R, 3000.0 * (1400.0 / 3000.0) ** (t / 5.0), q=0.45)
    x = x + 0.12 * al.butter(al.noise(t.size, 1403), R, "high", 5000, 2)
    env = np.clip(t / 0.02, 0, 1) * np.exp(-t / 1.6) * (1 + 0.12 * np.sin(2 * np.pi * 7.3 * t) * np.exp(-t))
    pop = al.butter(al.noise(t.size, 1402), R, "band", [300, 3000], 2) * al.env_ad(t, 0.001, 0.02, 0.0)
    x = x * env + 0.6 * pop
    return level("release", al.fade(x, R, 0.002, 0.5))


def touchdown():
    """Deep dull thump with a rattle (1.9 s): a soft lead, the thump at 0.28 s (60 Hz), a dull knock, a rattle."""
    t = al.t_axis(1.9, R)
    st = 0.28
    lead = al.butter(al.noise(t.size, 1501), R, "band", [200, 1200], 2) * np.sin(np.pi * np.clip(t / st, 0, 1)) * 0.04
    v = np.clip(t - st, 0, None)
    thump = np.sin(2 * np.pi * (68.0 * v - 30.0 * v * v)) * al.env_ad(t, 0.003, 0.16, st)
    knock = al.butter(al.noise(t.size, 1502), R, "low", 600, 3) * al.env_ad(t, 0.001, 0.05, st)
    rattle = al.butter(al.noise(t.size, 1503), R, "band", [300, 1100], 2)
    rattle *= np.clip(np.sin(2 * np.pi * 31.0 * t), 0, None) * al.env_ad(t, 0.01, 0.22, st + 0.04)
    ring = _modes(t, 410.0, [1.0, 1.8, 2.6], [0.25, 0.15, 0.1], [0.3, 0.2, 0.1], 1504, st + 0.01)
    x = lead + 1.2 * thump + 0.6 * knock + 0.35 * rattle + 0.2 * ring
    x = x + 0.2 * al.convolve(x, al.reverb_ir(0.9, R, 0.25, 1505, 3000))
    x = al.soft_clip(x / np.max(np.abs(x)) * 1.3, 1.6)
    return level("touchdown", al.fade(x, R, 0.0, 0.3))


# ============================================================================= warnings (A.4b)

def _beep(t, f, start, dur, square=0.35):
    on = (t >= start) & (t < start + dur)
    u = t - start
    ramp = np.clip(u / 0.007, 0, 1) * np.clip((dur - u) / 0.008, 0, 1)
    ph = 2 * np.pi * f * u
    w = np.sin(ph) + square * (np.sin(3 * ph) / 3 + np.sin(5 * ph) / 5)
    return w * ramp * on


def warning_phase1():
    """The 0.9 s impact alarm (also the WARNING voice tone): two-tone beeps high-low-high-low."""
    t = al.t_axis(0.9, R)
    x = np.zeros_like(t)
    for i, (f, st) in enumerate([(1245.0, 0.0), (932.0, 0.21), (1245.0, 0.42), (932.0, 0.63)]):
        x += _beep(t, f, st + 0.005, 0.17)
    x = x + 0.15 * al.convolve(x, al.reverb_ir(0.4, R, 0.12, 1601, 6000))
    return level("warning_phase1", al.fade(x, R, 0.0, 0.02))


def warning_phase2():
    """Impact acute loop (2 s): a fast, urgent alternation, 8 tones per second."""
    n = int(2.0 * R)
    t = np.arange(n) / R
    x = np.zeros(n)
    for k in range(16):
        f = 1397.0 if k % 2 == 0 else 1047.0
        x += _beep(t, f, k * 0.125 + 0.003, 0.11, 0.5)
    return level("warning_phase2", x)


def overheat_loop():
    """Overheat critical loop (2 s): a rising whoop 380 -> 760 Hz, four per loop, with a growl underneath."""
    n = int(2.0 * R)
    t = np.arange(n) / R
    x = np.zeros(n)
    for k in range(4):
        st = k * 0.5
        m = (t >= st) & (t < st + 0.42)
        u = (t - st)
        f = 380.0 * 2.0 ** (np.clip(u / 0.42, 0, 1))
        ph = 2 * np.pi * np.cumsum(np.where(m, f, 0.0)) / R
        env = np.clip(u / 0.01, 0, 1) * np.clip((0.42 - u) / 0.03, 0, 1) * m
        x += (np.sin(ph) + 0.3 * np.sin(2 * ph) + 0.15 * np.sin(3 * ph)) * env
    growl = al.loop_sine(55.0, n, R) * al.loop_lfo(2.0, n, R, 0.8) * 0.25
    return level("overheat_loop", x + growl)


SOUNDS = [
    ("engine", engine, True), ("thrust", thrust, True), ("decel", decel, True), ("rcs", rcs, True),
    ("rumble", rumble, True), ("cruise", cruise, True),
    ("fa_switch", fa_switch, False), ("fa_on", fa_on, False), ("fa_off", fa_off, False),
    ("hit1", hit1, False), ("hit2", hit2, False), ("hit3", hit3, False),
    ("warp_enter", warp_enter, False), ("warp_exit", warp_exit, False), ("warp_travel", warp_travel, True),
    ("boost", boost, False), ("cruise_on", cruise_on, False), ("cruise_off", cruise_off, False),
    ("clamp", clamp, False), ("release", release, False), ("touchdown", touchdown, False),
    ("warning_phase1", warning_phase1, False), ("warning_phase2", warning_phase2, True),
    ("overheat_loop", overheat_loop, True),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*", help="sounds to make (default: all)")
    ap.add_argument("--wav", help="also write WAVs into this directory")
    ap.add_argument("--quality", type=float, default=5, help="Vorbis quality (09 9.2: q5)")
    args = ap.parse_args()
    want = set(args.names)
    for name, fn, loop in SOUNDS:
        if want and name not in want:
            continue
        x = fn()
        if not np.all(np.isfinite(x)):
            raise SystemExit("%s: not finite" % name)
        path = al.sfx_path(name)
        al.encode_ogg(al.pad_loop(x, R) if loop else x, R, path, args.quality)
        if args.wav:
            os.makedirs(args.wav, exist_ok=True)
            al.write_wav(os.path.join(args.wav, name + ".wav"), x, R)
        print("%-15s %5.2f s  %s  peak %6.1f dBFS  %6.1f LUFS  %6.1f KB" % (
            name, x.size / R, "loop" if loop else "cue ", al.peak_db(x), al.loudness(x, R), os.path.getsize(path) / 1024))


if __name__ == "__main__":
    main()
