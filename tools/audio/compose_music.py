#!/usr/bin/env python3
"""Composes the game's music from scratch (DECISIONS 37): the menu music and the flight playlist of
09 2.12.4 (Deep Space, Long Haul, Meridian Lights; Sol Standard I and II stay absent, as in Roblox).

Original procedural ambient pieces: slowly evolving pads, drones and sparse melodic material in a calm
space mood. Every melody is a seeded random walk over the piece's own scale (chord tones on the strong
beats, phrases of 3-6 notes with long rests), so no existing tune can appear. Each piece has its own key,
tempo and timbre (TRACKS). Additive synthesis, FM bells, filtered noise and a convolution reverb in numpy;
44.1 kHz stereo OGG Vorbis into app/src/main/assets/audio/music/.

Loudness: Deep Space at -14 LUFS and the others so that ShipConfig's playlist gains (0.93, 0.73) bring
them to the same playback level (09 3.6: "Gain matches loudness to the quietest"); the menu at -13 LUFS.
The menu piece loops seamlessly (its tail is folded onto its start); the playlist pieces fade in and out.

    python3 tools/audio/compose_music.py                 # all four
    python3 tools/audio/compose_music.py deep_space      # one
    python3 tools/audio/compose_music.py --preview DIR   # also 45 s excerpts music-<name>.ogg there
"""

import argparse
import os
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(__file__))
import audiolib as al  # noqa: E402

SR = al.MUSIC_RATE
CTRL = 256  # control-rate decimation for envelopes and filter sweeps


def hz(midi):
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


SCALES = {
    "aeolian": [0, 2, 3, 5, 7, 8, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11],
    "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "ionian": [0, 2, 4, 5, 7, 9, 11],
}

# Each piece: key (MIDI root), mode, tempo, length, chord progression as scale degrees (0-based, stacked
# from the scale) with lengths in bars, the timbres and the arrangement. Seeds make every rerun identical.
TRACKS = {
    "menu": dict(
        title="Menu", root=50, mode="lydian", bpm=66, bars=40, loop=True, lufs=-13.0, seed=7101,
        chords=[([0, 2, 4, 6], 2), ([4, 6, 8, 11], 2), ([5, 7, 9, 11], 2), ([3, 5, 7, 9], 2)],
        pad=dict(slope=1.1, maxh=12, voices=2, detune=6.0, bright=(900, 2600), period=48.0, attack=3.0, level=0.9),
        drone=dict(level=0.35, fifth=True),
        melody=dict(timbre="bell", octave=5, density=0.55, level=0.30, start=4, ratio=3.5, decay=2.2),
        arp=None,
        air=dict(level=0.10, band=(1500, 6000)),
        reverb=dict(rt60=6.0, mix=0.42, tone=5500),
    ),
    "deep_space": dict(
        title="Deep Space", root=46, mode="aeolian", bpm=52, bars=46, loop=False, lufs=-14.0, seed=7201,
        chords=[([0, 2, 4, 8], 3), ([5, 7, 9, 11], 3), ([3, 5, 7, 9], 3), ([6, 8, 10, 12], 2), ([4, 6, 8, 11], 1)],
        pad=dict(slope=1.6, maxh=9, voices=2, detune=4.0, bright=(500, 1500), period=62.0, attack=5.0, level=1.0),
        drone=dict(level=0.55, fifth=False),
        melody=dict(timbre="glass", octave=5, density=0.35, level=0.26, start=6, ratio=2.76, decay=3.0),
        arp=None,
        air=dict(level=0.07, band=(700, 3500)),
        pings=dict(level=0.06, every=(9.0, 17.0), midi=(86, 93)),
        reverb=dict(rt60=8.0, mix=0.5, tone=4000),
    ),
    "long_haul": dict(
        title="Long Haul", root=53, mode="lydian", bpm=72, bars=56, loop=False, lufs=-13.4, seed=7301,
        chords=[([0, 2, 4, 7], 2), ([1, 3, 5, 8], 2), ([4, 6, 8, 11], 2), ([5, 7, 9, 11], 2), ([3, 5, 7, 10], 2), ([0, 4, 7, 9], 2)],
        pad=dict(slope=1.0, maxh=14, voices=3, detune=9.0, bright=(1100, 3200), period=40.0, attack=2.5, level=0.85),
        drone=dict(level=0.4, fifth=True),
        melody=dict(timbre="lead", octave=5, density=0.45, level=0.22, start=8, ratio=1.0, decay=1.0),
        arp=dict(level=0.11, octave=4, pattern=[0, 2, 1, 3], every=1.0, start=4),
        air=dict(level=0.08, band=(2000, 7000)),
        reverb=dict(rt60=5.0, mix=0.38, tone=6000),
    ),
    "meridian_lights": dict(
        title="Meridian Lights", root=52, mode="mixolydian", bpm=84, bars=62, loop=False, lufs=-11.3, seed=7401,
        chords=[([0, 2, 4, 8], 2), ([3, 5, 7, 9], 2), ([6, 8, 10, 12], 2), ([4, 6, 8, 10], 2)],
        pad=dict(slope=0.85, maxh=16, voices=3, detune=11.0, bright=(1600, 4200), period=36.0, attack=2.0, level=1.0),
        drone=dict(level=0.32, fifth=True),
        melody=dict(timbre="bell", octave=6, density=0.6, level=0.2, start=4, ratio=5.0, decay=1.6),
        arp=dict(level=0.12, octave=5, pattern=[0, 2, 4, 2, 1, 3], every=0.5, start=8),
        air=dict(level=0.12, band=(3000, 9000)),
        reverb=dict(rt60=4.5, mix=0.4, tone=7000),
    ),
}


# ============================================================================= building blocks

def chord_notes(spec, degrees, prev=None):
    """Scale degrees -> MIDI notes, voiced near the previous chord (voice leading) around octave 3-4."""
    scale = SCALES[spec["mode"]]
    notes = []
    for d in degrees:
        octv, idx = divmod(d, len(scale))
        notes.append(spec["root"] + 12 * octv + scale[idx])
    notes = sorted(notes)
    if prev:
        centre = np.mean(prev)
        shift = int(round((centre - np.mean(notes)) / 12.0))
        notes = [n + 12 * shift for n in notes]
    lo = spec["root"] - 2
    while min(notes) < lo:
        notes = [n + 12 for n in notes]
    while max(notes) > spec["root"] + 26:
        notes = [n - 12 for n in notes]
    return notes


def envelope(n, attack, release, total_hold):
    """Raised-cosine attack, hold, raised-cosine release; n samples."""
    t = np.arange(n) / SR
    e = np.ones(n)
    a = t < attack
    e[a] = 0.5 - 0.5 * np.cos(np.pi * t[a] / attack)
    r = t > total_hold
    u = np.clip((t[r] - total_hold) / release, 0, 1)
    e[r] = 0.5 + 0.5 * np.cos(np.pi * u)
    return e


def brightness(spec, start, n):
    """The pad's low-pass cutoff (Hz) over time: a slow wave between bright[0] and bright[1]."""
    lo, hi = spec["pad"]["bright"]
    period = spec["pad"]["period"]
    tc = (start + np.arange(0, n, CTRL) / SR)
    w = 0.5 - 0.5 * np.cos(2 * np.pi * tc / period + spec["seed"] % 7)
    w2 = 0.5 - 0.5 * np.cos(2 * np.pi * tc / (period * 2.7) + 1.3)
    return tc, lo * (hi / lo) ** (0.65 * w + 0.35 * w2)


def pad_note(spec, f, start, hold, rng, pan_spread=0.7):
    p = spec["pad"]
    rel = p["attack"] * 1.3
    n = int((hold + rel) * SR)
    t = np.arange(n) / SR
    tc, cut = brightness(spec, start, n)
    out = np.zeros((n, 2))
    voices = p["voices"]
    for v in range(voices):
        cents = (v - (voices - 1) / 2) * p["detune"] + rng.normal(0, 1.0)
        fv = f * 2 ** (cents / 1200.0)
        pan = 0.5 + pan_spread * ((v / max(voices - 1, 1)) - 0.5) if voices > 1 else 0.5
        sig = np.zeros(n)
        for k in range(1, p["maxh"] + 1):
            fk = k * fv
            if fk > 7500:
                break
            amp_c = (1.0 / k ** p["slope"]) / np.sqrt(1.0 + (fk / cut) ** 4)
            amp = np.interp(t, tc, amp_c)
            sig += amp * np.sin(2 * np.pi * fk * t + rng.uniform(0, 2 * np.pi))
        out[:, 0] += sig * np.sqrt(1 - pan)
        out[:, 1] += sig * np.sqrt(pan)
    out *= envelope(n, p["attack"], rel, hold)[:, None]
    return out / voices


def bell(f, dur, ratio, decay, rng, bright=1.0):
    """FM bell/mallet: carrier f, modulator ratio x f, index decaying; amplitude exponential."""
    n = int((dur + decay * 3) * SR)
    t = np.arange(n) / SR
    index = 2.4 * bright * np.exp(-t / (decay * 0.35))
    mod = np.sin(2 * np.pi * f * ratio * t)
    car = np.sin(2 * np.pi * f * t + index * mod)
    amp = np.clip(t / 0.004, 0, 1) * np.exp(-t / decay)
    return car * amp


def glass(f, dur, ratio, decay, rng):
    """Glassy mallet: inharmonic partials (1, 2.76, 5.4, 8.9) with faster decays for the upper ones."""
    n = int((dur + decay * 3) * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for r, a, d in [(1.0, 1.0, 1.0), (2.756, 0.45, 0.5), (5.404, 0.18, 0.28), (8.933, 0.07, 0.15)]:
        if f * r < 15000:
            x += a * np.sin(2 * np.pi * f * r * t + rng.uniform(0, 6.28)) * np.exp(-t / (decay * d))
    return x * np.clip(t / 0.003, 0, 1)


def lead(f, dur, ratio, decay, rng):
    """Soft lead: sine with a little 2nd/3rd harmonic and delayed vibrato, slow attack."""
    rel = 0.6
    n = int((dur + rel) * SR)
    t = np.arange(n) / SR
    vib = 0.004 * np.clip((t - 0.3) / 0.8, 0, 1) * np.sin(2 * np.pi * 4.8 * t)
    ph = 2 * np.pi * f * np.cumsum(1 + vib) / SR
    x = np.sin(ph) + 0.22 * np.sin(2 * ph) + 0.08 * np.sin(3 * ph)
    return x * envelope(n, 0.12, rel, dur)


def pluck(f, rng, decay=0.35):
    n = int(decay * 5 * SR)
    t = np.arange(n) / SR
    x = np.zeros(n)
    for k in range(1, 7):
        if k * f > 9000:
            break
        x += (0.6 ** (k - 1)) * np.sin(2 * np.pi * k * f * t + rng.uniform(0, 6.28)) * np.exp(-t * k / decay)
    return x * np.clip(t / 0.002, 0, 1)


def add(buf, x, start, pan=0.5, gain=1.0):
    i = int(start * SR)
    if i >= buf.shape[0]:
        return
    if x.ndim == 1:
        x = np.stack([x * np.sqrt(1 - pan), x * np.sqrt(pan)], axis=1)
    m = min(x.shape[0], buf.shape[0] - i)
    buf[i:i + m] += gain * x[:m]


def reverb(x, rt60, tone, seed):
    """Stereo convolution reverb: decorrelated decaying noise, darker as it decays."""
    n = int(min(rt60 * 1.1, 9.0) * SR)
    t = np.arange(n) / SR
    tau = rt60 / 6.91
    rng = np.random.default_rng(seed)
    out = np.zeros_like(x)
    for c in range(2):
        nz = rng.standard_normal(n)
        bright = al.butter(nz, SR, "low", tone, 2) * np.exp(-t / (tau * 0.6))
        dark = al.butter(nz, SR, "low", tone * 0.35, 2) * np.exp(-t / tau)
        ir = 0.5 * bright + dark
        pre = int(0.025 * SR)
        ir = np.concatenate([np.zeros(pre), ir])
        q = n // 4
        ir[-q:] *= np.linspace(1, 0, q)
        ir /= np.sqrt(np.sum(ir ** 2))
        out[:, c] = signal.fftconvolve(x[:, c], ir)[: x.shape[0]]
    return out


def melody(spec, total, chords_at, rng):
    """Sparse phrases: a random walk on the scale, chord tones on strong beats, long rests."""
    m = spec["melody"]
    scale = SCALES[spec["mode"]]
    beat = 60.0 / spec["bpm"]
    bar = 4 * beat
    events = []
    tpos = m["start"] * bar
    deg = rng.integers(0, len(scale))
    while tpos < total - 3 * bar:
        if rng.random() > m["density"]:
            tpos += bar * rng.choice([1, 2])
            continue
        count = int(rng.integers(3, 7))
        for i in range(count):
            dur = beat * float(rng.choice([1.0, 1.5, 2.0, 2.0, 3.0]))
            chord = chords_at(tpos)
            step = int(rng.choice([-2, -1, -1, 1, 1, 2, 3, -3]))
            deg = int(np.clip(deg + step, -3, 9))
            note = spec["root"] + 12 * (m["octave"] - 4) + 12 * (deg // len(scale)) + scale[deg % len(scale)]
            strong = (tpos / beat) % 2 < 0.01 or i == count - 1
            if strong and rng.random() < 0.7:
                # nearest chord tone in this octave range
                cands = [c + 12 * o for c in chord for o in range(-1, 4)]
                note = min(cands, key=lambda c: abs(c - note))
            events.append((tpos, note, dur))
            tpos += dur
        tpos += bar * float(rng.choice([1.0, 2.0, 2.0, 3.0, 4.0]))
    return events


# ============================================================================= a piece

def compose(name, spec):
    rng = np.random.default_rng(spec["seed"])
    beat = 60.0 / spec["bpm"]
    bar = 4 * beat
    seconds = spec["bars"] * bar
    tail = 10.0
    total = seconds + tail
    n = int(total * SR)
    pads = np.zeros((n, 2))
    lows = np.zeros((n, 2))
    mel = np.zeros((n, 2))
    tex = np.zeros((n, 2))

    # the chord timeline (repeat the progression), voice-led
    timeline = []
    tpos = 0.0
    prev = None
    k = 0
    while tpos < seconds:
        degrees, bars = spec["chords"][k % len(spec["chords"])]
        notes = chord_notes(spec, degrees, prev)
        timeline.append((tpos, bars * bar, notes))
        prev = notes
        tpos += bars * bar
        k += 1

    def chords_at(t):
        for s, d, notes in timeline:
            if s <= t < s + d:
                return notes
        return timeline[-1][2]

    # pads: every chord note, overlapping into the next chord (the attack crossfades them)
    p = spec["pad"]
    for s, d, notes in timeline:
        for note in notes:
            hold = d + p["attack"] * 0.6
            x = pad_note(spec, hz(note), s, hold, rng)
            add(pads, x, s, gain=p["level"] * 0.22)

    # drone: the root an octave or two down, a slow swell, the fifth in places
    dr = spec["drone"]
    t = np.arange(n) / SR
    f0 = hz(spec["root"] - 12)
    swell = 0.6 + 0.4 * np.sin(2 * np.pi * t / 37.0 + 0.4)
    drone = (np.sin(2 * np.pi * f0 * t) + 0.35 * np.sin(2 * np.pi * 2 * f0 * t + 0.6)
             + 0.12 * np.sin(2 * np.pi * 3 * f0 * t + 1.1)) * swell
    if dr["fifth"]:
        drone += 0.3 * np.sin(2 * np.pi * f0 * 1.5 * t + 0.3) * (0.5 + 0.5 * np.sin(2 * np.pi * t / 53.0))
    sub = 0.5 * np.sin(2 * np.pi * f0 / 2 * t) * swell
    d_env = envelope(n, 8.0, 8.0, seconds - 2.0)
    lows[:, 0] += (drone + sub) * d_env * dr["level"] * 0.3
    lows[:, 1] += (drone + sub) * d_env * dr["level"] * 0.3

    # melody
    m = spec["melody"]
    for s, note, dur in melody(spec, seconds, chords_at, rng):
        f = hz(note)
        if m["timbre"] == "bell":
            x = bell(f, dur, m["ratio"], m["decay"], rng)
        elif m["timbre"] == "glass":
            x = glass(f, dur, m["ratio"], m["decay"], rng)
        else:
            x = lead(f, dur, m["ratio"], m["decay"], rng)
        add(mel, x, s, pan=float(rng.uniform(0.3, 0.7)), gain=m["level"])

    # arpeggio pulse (Long Haul, Meridian Lights): soft plucks on the chord tones
    a = spec.get("arp")
    if a:
        step = beat * a["every"]
        tpos = a["start"] * bar
        i = 0
        while tpos < seconds - bar:
            notes = chords_at(tpos)
            idx = a["pattern"][i % len(a["pattern"])] % len(notes)
            note = notes[idx] + 12 * (a["octave"] - 3)
            # the pulse swells in and out over long spans
            amt = 0.5 - 0.5 * np.cos(2 * np.pi * (tpos - a["start"] * bar) / (bar * 16))
            add(tex, pluck(hz(note), rng), tpos, pan=0.35 + 0.3 * (i % 2), gain=a["level"] * (0.25 + 0.75 * amt))
            tpos += step
            i += 1

    # air: band-limited noise in slow, wide swells
    air = spec["air"]
    lo, hi = air["band"]
    for c in range(2):
        nz = al.butter(rng.standard_normal(n), SR, "band", [lo, hi], 2)
        sw = 0.5 - 0.5 * np.cos(2 * np.pi * t / (23.0 + 7 * c) + c)
        tex[:, c] += nz * sw * air["level"] * 0.25

    # pings (Deep Space): distant high sines
    pg = spec.get("pings")
    if pg:
        tpos = 12.0
        while tpos < seconds - 8:
            note = int(rng.integers(pg["midi"][0], pg["midi"][1]))
            x = np.sin(2 * np.pi * hz(note) * np.arange(int(4 * SR)) / SR) * np.exp(-np.arange(int(4 * SR)) / SR / 0.9)
            add(tex, x * np.clip(np.arange(x.size) / (0.01 * SR), 0, 1), tpos, pan=float(rng.uniform(0.15, 0.85)), gain=pg["level"])
            tpos += float(rng.uniform(*pg["every"]))

    dry = pads + lows + mel + tex
    rv = spec["reverb"]
    wet = reverb(pads + mel + 0.6 * tex, rv["rt60"], rv["tone"], spec["seed"] + 1)
    mix = dry * (1 - rv["mix"] * 0.5) + wet * rv["mix"]
    mix = al.butter(mix.T, SR, "high", 28.0, 2).T  # clear the sub rumble

    if spec["loop"]:
        # fold the tail (the decays past the loop end) onto the start: the loop wraps seamlessly
        L = int(seconds * SR)
        out = mix[:L].copy()
        out[: n - L] += mix[L:]
    else:
        out = mix[: int((seconds + 6.0) * SR)].copy()
        fin, fout = int(3.0 * SR), int(7.0 * SR)
        out[:fin] *= np.linspace(0, 1, fin)[:, None] ** 2
        out[-fout:] *= np.linspace(1, 0, fout)[:, None] ** 2
    return master(out, spec["lufs"])


def master(x, target):
    """Gentle RMS compression, loudness to the target, a soft ceiling at -1 dBFS."""
    for _ in range(2):
        x = al.to_lufs(x, SR, target + 1.0, ceiling_db=6.0)
        level = np.sqrt(signal.lfilter([0.0005], [1, -0.9995], np.mean(x ** 2, axis=1)) + 1e-12)
        thr = 10 ** ((target + 1.0) / 20.0) * 0.9
        gain = np.where(level > thr, (thr / level) ** 0.4, 1.0)
        gain = signal.lfilter([0.0002], [1, -0.9998], gain - 1.0) + 1.0
        x = x * gain[:, None]
    x = al.to_lufs(x, SR, target, ceiling_db=6.0)
    knee = 10 ** (-4.0 / 20)
    ceil = 10 ** (-1.0 / 20)
    a = np.abs(x)
    over = a > knee
    x[over] = np.sign(x[over]) * (knee + (ceil - knee) * np.tanh((a[over] - knee) / (ceil - knee)))
    return x


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*")
    ap.add_argument("--quality", type=float, default=2)
    ap.add_argument("--preview", help="write 45 s excerpts music-<name>.ogg into this directory")
    args = ap.parse_args()
    for name, spec in TRACKS.items():
        if args.names and name not in args.names:
            continue
        x = compose(name, spec)
        path = al.music_path(name)
        al.encode_ogg(x, SR, path, args.quality, channels=2)
        print("%-16s %6.1f s  %6.1f LUFS  peak %5.1f dBFS  %7.0f KB  (%s, %d BPM, %s)" % (
            name, x.shape[0] / SR, al.loudness(x, SR), al.peak_db(x), os.path.getsize(path) / 1024,
            spec["title"], spec["bpm"], spec["mode"]))
        if args.preview:
            os.makedirs(args.preview, exist_ok=True)
            start = int(min(40.0, x.shape[0] / SR - 50) * SR)
            ex = x[start:start + int(45 * SR)].copy()
            f = int(1.5 * SR)
            ex[:f] *= np.linspace(0, 1, f)[:, None]
            ex[-f:] *= np.linspace(1, 0, f)[:, None]
            al.encode_ogg(ex, SR, os.path.join(args.preview, "music-%s.ogg" % name), 4, channels=2)


if __name__ == "__main__":
    main()
