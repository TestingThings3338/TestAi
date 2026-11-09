#!/usr/bin/env python3
"""Verifies every file of the sound inventory (09 Appendix A as SoundBank.kt ships it).

For each file: present and decodable, format (sfx mono 32 kHz, music stereo 44.1 kHz), length within its
range, loudness (LUFS, BS.1770) or sample peak at its target, never clipping, and for loops a seamless wrap
(no level step, no click: the sample step and the high-band energy at the seam must look like anywhere
else in the file). Music tracks that do not loop must start and end in silence. Also the total size of
app/src/main/assets/audio (budget 12 MB).

    python3 tools/audio/check_audio.py                       # check, exit 1 on any failure
    python3 tools/audio/check_audio.py --spectrograms DIR    # also one ffmpeg showspectrumpic PNG per file
                                                             # and contact sheets (sfx-*.png, music.png)
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import audiolib as al  # noqa: E402

# name, group, kind ("loop" | "cue" | "music" | "music-loop"), (min s, max s), level ("lufs"|"peak", target, tol)
INVENTORY = [
    ("engine", "sfx", "loop", (6, 40), ("lufs", -24.0, 1.0)),
    ("thrust", "sfx", "loop", (6, 40), ("lufs", -14.0, 1.0)),
    ("decel", "sfx", "loop", (6, 40), ("lufs", -15.0, 1.0)),
    ("rcs", "sfx", "loop", (6, 25), ("lufs", -29.0, 1.0)),
    ("rumble", "sfx", "loop", (6, 40), ("lufs", -22.0, 1.0)),
    ("cruise", "sfx", "loop", (6, 40), ("lufs", -23.0, 1.0)),
    ("fa_switch", "sfx", "cue", (0.5, 0.9), ("peak", -6.0, 1.0)),
    ("fa_on", "sfx", "cue", (0.35, 0.6), ("peak", -16.0, 1.0)),
    ("fa_off", "sfx", "cue", (0.3, 0.5), ("peak", -16.0, 1.0)),
    ("hit1", "sfx", "cue", (0.5, 1.5), ("peak", -12.0, 1.0)),
    ("hit2", "sfx", "cue", (0.5, 1.5), ("peak", -12.0, 1.0)),
    ("hit3", "sfx", "cue", (0.5, 1.5), ("peak", -12.0, 1.0)),
    ("warp_enter", "sfx", "cue", (3.0, 6.0), ("peak", -9.0, 1.0)),
    ("warp_exit", "sfx", "cue", (2.0, 5.0), ("peak", -9.0, 1.0)),
    ("warp_travel", "sfx", "loop", (4, 30), ("lufs", -27.0, 1.0)),
    ("boost", "sfx", "cue", (2.0, 2.6), ("peak", -4.0, 1.0)),
    ("cruise_on", "sfx", "cue", (3.5, 4.3), ("peak", -6.0, 1.0)),
    ("cruise_off", "sfx", "cue", (4.0, 4.8), ("peak", -4.0, 1.0)),
    ("clamp", "sfx", "cue", (1.3, 1.9), ("peak", -4.0, 1.0)),
    ("release", "sfx", "cue", (4.5, 5.5), ("peak", -8.0, 1.0)),
    ("touchdown", "sfx", "cue", (1.6, 2.2), ("peak", -3.0, 1.0)),
    ("warning_phase1", "sfx", "cue", (0.85, 0.95), ("peak", -9.0, 1.0)),
    ("warning_phase2", "sfx", "loop", (1.0, 8.0), ("lufs", -20.0, 1.0)),
    ("overheat_loop", "sfx", "loop", (1.0, 8.0), ("lufs", -21.0, 1.0)),
    ("menu", "music", "music-loop", (120, 260), ("lufs", -13.0, 1.0)),
    ("deep_space", "music", "music", (150, 260), ("lufs", -14.0, 1.0)),
    ("long_haul", "music", "music", (150, 260), ("lufs", -13.4, 1.0)),
    ("meridian_lights", "music", "music", (150, 260), ("lufs", -11.3, 1.0)),
]

BUDGET_BYTES = 12 * 1024 * 1024


def seam_report(x, rate):
    """Click and level-step measures at the wrap of a loop (x mono float)."""
    n = x.size
    d = np.abs(np.diff(np.concatenate([x, x[:1]])))
    seam_step = d[-1]
    p999 = np.percentile(d[:-1], 99.9)
    # high band (> 5 kHz, or > 0.3 x Nyquist) energy in 5 ms windows around the circle
    hp = al.circular_filter(x, rate, lambda f: 1.0 / np.sqrt(1.0 + (min(5000.0, 0.3 * rate / 2) / np.maximum(f, 1e-3)) ** 8))
    w = int(0.005 * rate)
    e = hp ** 2
    ring = np.concatenate([e[-w:], e, e[:w]])
    c = np.cumsum(np.concatenate([[0.0], ring]))
    win = (c[2 * w:] - c[:-2 * w]) / (2 * w)  # centred windows, index i = sample i
    seam_win = max(win[0], win[-1])
    # the content's own high-band peaks, and an absolute floor (-80 dBFS RMS: inaudible codec noise)
    typical = max(1.5 * np.percentile(win, 99.9), 10 ** (-80 / 10))
    # level step: 50 ms RMS after vs before the seam, against the same measure everywhere else in the
    # file (a rhythmic loop has attacks inside it too; the seam must not step more than the content does)
    m = int(0.05 * rate)
    sq = np.concatenate([x, x[:m]]) ** 2
    c2 = np.cumsum(np.concatenate([[0.0], sq]))
    hop = max(1, int(0.01 * rate))
    pos = np.arange(m, n + 1, hop)
    after = np.sqrt((c2[pos + m] - c2[pos]) / m) + 1e-9
    before = np.sqrt((c2[pos] - c2[pos - m]) / m) + 1e-9
    steps = np.abs(20 * np.log10(after / before))
    a = np.sqrt(np.mean(x[-m:] ** 2)) + 1e-9
    b = np.sqrt(np.mean(x[:m] ** 2)) + 1e-9
    step_db = 20 * np.log10(b / a)
    allowed = max(1.5, float(np.percentile(steps, 99.0)))
    return seam_step / max(p999, 1e-12), seam_win / max(typical, 1e-12), step_db, allowed


def check(name, group, kind, dur, lvl, verbose):
    path = al.sfx_path(name) if group == "sfx" else al.music_path(name)
    problems = []
    if not os.path.isfile(path):
        return ["missing: %s" % path], None
    info = al.probe(path)
    want_rate = al.SFX_RATE if group == "sfx" else al.MUSIC_RATE
    want_ch = 1 if group == "sfx" else 2
    if info["rate"] != want_rate or info["channels"] != want_ch:
        problems.append("format %d Hz x %d, want %d Hz x %d" % (info["rate"], info["channels"], want_rate, want_ch))
    x, rate = al.decode(path)
    if kind == "loop":
        x = al.unpad_loop(x, rate)  # the loaders cut the codec padding (SoundBank.loopPadded)
    mono = x if x.ndim == 1 else x.mean(axis=1)
    seconds = mono.size / rate
    if not (dur[0] <= seconds <= dur[1]):
        problems.append("length %.2f s outside %.1f..%.1f s" % (seconds, dur[0], dur[1]))
    lufs = al.loudness(x, rate)
    pk = al.peak_db(x)
    kind_l, target, tol = lvl
    got = lufs if kind_l == "lufs" else pk
    if kind_l == "peak":
        tol = max(tol, 2.5)  # lossy coding moves sample peaks by a dB or two
    if abs(got - target) > tol:
        problems.append("%s %.1f, want %.1f +- %.1f" % (kind_l, got, target, tol))
    if pk > -0.3:
        problems.append("peak %.2f dBFS: clips" % pk)
    extra = ""
    if kind in ("loop", "music-loop"):
        z_step, z_hf, step_db, allowed = seam_report(mono, rate)
        extra = "seam step %.2fx p99.9, seam HF %.2fx limit, level step %+.2f dB (content %.1f)" % (z_step, z_hf, step_db, allowed)
        if z_step > 1.0:
            problems.append("seam: sample step %.2f x the file's 99.9th percentile (click)" % z_step)
        if z_hf > 1.0:
            problems.append("seam: high-band burst %.2f x the limit (1.5 x the file's 99.9th percentile, -80 dB floor)" % z_hf)
        if abs(step_db) > allowed:
            problems.append("seam: level step %+.2f dB (the content steps at most %.1f dB)" % (step_db, allowed))
    elif kind == "music":
        m = int(0.01 * rate)
        head = 20 * np.log10(np.sqrt(np.mean(mono[:m] ** 2)) + 1e-12)
        tail = 20 * np.log10(np.sqrt(np.mean(mono[-m:] ** 2)) + 1e-12)
        extra = "head %.0f dBFS, tail %.0f dBFS" % (head, tail)
        if head > -50 or tail > -50:
            problems.append("does not start and end in silence (%s)" % extra)
    line = "%-16s %-5s %6.2f s  %6.1f LUFS  peak %6.1f dBFS  %7.1f KB  %s" % (
        name, kind[:5], seconds, lufs, pk, os.path.getsize(path) / 1024, extra)
    return problems, line


def spectrograms(out_dir):
    from PIL import Image, ImageDraw
    os.makedirs(out_dir, exist_ok=True)
    pngs = {}
    for name, group, kind, _, _ in INVENTORY:
        path = al.sfx_path(name) if group == "sfx" else al.music_path(name)
        if not os.path.isfile(path):
            continue
        png = os.path.join(out_dir, "%s-%s.png" % (group, name))
        size = (1000, 360) if group == "sfx" else (1400, 420)
        al.spectrogram_png(path, png, size[0], size[1], unpad=(kind == "loop"))
        pngs[(group, name)] = png
    # contact sheets with titles: sfx in pages of 6, music on one sheet
    def sheet(items, out, cols):
        items = [k for k in items if k in pngs]
        if not items:
            return None
        ims = [Image.open(pngs[k]).convert("RGB") for k in items]
        w = max(i.width for i in ims)
        h = max(i.height for i in ims) + 22
        rows = (len(ims) + cols - 1) // cols
        canvas = Image.new("RGB", (w * cols, h * rows), (16, 16, 20))
        draw = ImageDraw.Draw(canvas)
        for idx, (k, im) in enumerate(zip(items, ims)):
            x, y = (idx % cols) * w, (idx // cols) * h
            canvas.paste(im, (x, y + 22))
            draw.text((x + 8, y + 4), "%s/%s" % k, fill=(240, 220, 120))
        canvas.save(out)
        return out
    sfx = [(g, n) for n, g, _, _, _ in INVENTORY if g == "sfx"]
    sheets = []
    for p in range(0, len(sfx), 6):
        sheets.append(sheet(sfx[p:p + 6], os.path.join(out_dir, "sheet-sfx-%d.png" % (p // 6 + 1)), 2))
    music = [(g, n) for n, g, _, _, _ in INVENTORY if g == "music"]
    sheets.append(sheet(music, os.path.join(out_dir, "sheet-music.png"), 1))
    return sheets


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--spectrograms", help="write spectrogram PNGs and contact sheets into this directory")
    ap.add_argument("-q", "--quiet", action="store_true")
    args = ap.parse_args()
    failures = 0
    for name, group, kind, dur, lvl in INVENTORY:
        problems, line = check(name, group, kind, dur, lvl, not args.quiet)
        if line and not args.quiet:
            print(line)
        for p in problems:
            failures += 1
            print("  FAIL %s: %s" % (name, p))
    total = 0
    root = os.path.join(al.ASSETS, "audio")
    for dirpath, _, files in os.walk(root):
        for f in files:
            total += os.path.getsize(os.path.join(dirpath, f))
    print("audio assets: %.2f MB (budget %.0f MB)" % (total / 1048576.0, BUDGET_BYTES / 1048576.0))
    if total > BUDGET_BYTES:
        failures += 1
        print("  FAIL: over the size budget")
    if args.spectrograms:
        for s in spectrograms(args.spectrograms):
            if s:
                print("sheet: %s" % s)
    print("%d problem(s)" % failures if failures else "all good")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
