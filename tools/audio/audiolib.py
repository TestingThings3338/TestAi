"""Shared helpers of the audio tools (synth_sfx.py, compose_music.py, check_audio.py).

Pure numpy/scipy plus the ffmpeg command line (libvorbis) for encoding and decoding. Everything is
deterministic: random numbers come from numpy Generators with fixed seeds.
"""

import os
import subprocess
import tempfile

import numpy as np
from scipy import signal

# ----------------------------------------------------------------------------- inventory

ASSETS = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets"))

SFX_RATE = 32000
MUSIC_RATE = 44100

# Loops carry this much of their own wrap-around on each end (SoundBank.LOOP_PAD_SECONDS); loaders cut it.
LOOP_PAD_SECONDS = 0.128


def pad_loop(x, rate):
    p = int(round(LOOP_PAD_SECONDS * rate))
    return np.concatenate([x[-p:], x, x[:p]], axis=0)


def unpad_loop(x, rate):
    p = int(round(LOOP_PAD_SECONDS * rate))
    return x[p:-p]


def sfx_path(name):
    return os.path.join(ASSETS, "audio", "sfx", name + ".ogg")


def music_path(name):
    return os.path.join(ASSETS, "audio", "music", name + ".ogg")


# ----------------------------------------------------------------------------- loudness (ITU-R BS.1770-4)

def _k_weighting(rate):
    """The two K-weighting biquads for any sample rate (as pyloudnorm derives them)."""
    g, f0, q = 3.999843853973347, 1681.974450955533, 0.7071752369554196
    k = np.tan(np.pi * f0 / rate)
    vh = 10.0 ** (g / 20.0)
    vb = vh ** 0.4996667741545416
    a0 = 1.0 + k / q + k * k
    shelf_b = [(vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0]
    shelf_a = [1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0]
    f1, q1 = 38.13547087602444, 0.5003270373238773
    k = np.tan(np.pi * f1 / rate)
    a0 = 1.0 + k / q1 + k * k
    hp_b = [1.0, -2.0, 1.0]
    hp_a = [1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q1 + k * k) / a0]
    return (shelf_b, shelf_a), (hp_b, hp_a)


def loudness(x, rate):
    """Integrated loudness (LUFS) of a mono (n,) or multichannel (n, c) signal."""
    x = np.asarray(x, dtype=np.float64)
    if x.ndim == 1:
        x = x[:, None]
    (sb, sa), (hb, ha) = _k_weighting(rate)
    y = signal.lfilter(hb, ha, signal.lfilter(sb, sa, x, axis=0), axis=0)
    block = int(0.4 * rate)
    hop = max(1, block // 4)
    if y.shape[0] <= block:
        powers = np.array([np.sum(np.mean(y ** 2, axis=0))])
    else:
        starts = np.arange(0, y.shape[0] - block + 1, hop)
        sq = np.cumsum(np.vstack([np.zeros((1, y.shape[1])), y ** 2]), axis=0)
        powers = np.sum((sq[starts + block] - sq[starts]) / block, axis=1)
    lufs = -0.691 + 10.0 * np.log10(np.maximum(powers, 1e-12))
    gated = powers[lufs > -70.0]
    if gated.size == 0:
        return -70.0
    rel = -0.691 + 10.0 * np.log10(np.mean(gated)) - 10.0
    g2 = gated[-0.691 + 10.0 * np.log10(gated) > rel]
    if g2.size == 0:
        g2 = gated
    return float(-0.691 + 10.0 * np.log10(np.mean(g2)))


def peak_db(x):
    p = float(np.max(np.abs(x))) if x.size else 0.0
    return 20.0 * np.log10(max(p, 1e-9))


def to_lufs(x, rate, target, ceiling_db=-1.0):
    """Scales x to the target loudness, less if the sample peak would pass the ceiling."""
    g = 10.0 ** ((target - loudness(x, rate)) / 20.0)
    p = np.max(np.abs(x)) * g
    ceil = 10.0 ** (ceiling_db / 20.0)
    if p > ceil:
        g *= ceil / p
    return x * g


def to_peak(x, target_db):
    p = np.max(np.abs(x))
    return x * (10.0 ** (target_db / 20.0) / max(p, 1e-12))


# ----------------------------------------------------------------------------- periodic material (seamless loops)

def periodic_noise(n, rate, shape, seed):
    """Noise of exactly n samples that wraps seamlessly: random phases under a magnitude spectrum.

    shape(freqs) -> amplitude per frequency (Hz). The result is periodic with period n, so a loop of
    it has no seam at all; normalised to unit RMS.
    """
    rng = np.random.default_rng(seed)
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    mag = np.asarray(shape(freqs), dtype=np.float64)
    mag[0] = 0.0
    spec = mag * np.exp(1j * rng.uniform(0, 2 * np.pi, freqs.size))
    x = np.fft.irfft(spec, n)
    return x / max(np.sqrt(np.mean(x ** 2)), 1e-12)


def loop_freq(f, n, rate):
    """f snapped to a whole number of cycles in n samples (so a sine of it loops seamlessly)."""
    cycles = max(1, int(round(f * n / rate)))
    return cycles * rate / n


def loop_sine(f, n, rate, phase=0.0):
    f = loop_freq(f, n, rate)
    t = np.arange(n) / rate
    return np.sin(2 * np.pi * f * t + phase)


def loop_lfo(f, n, rate, depth, phase=0.0):
    """1 + depth * sin, with a whole number of cycles."""
    return 1.0 + depth * loop_sine(f, n, rate, phase)


def circular_filter(x, rate, shape):
    """Zero-phase filtering in the frequency domain; keeps a periodic signal periodic."""
    n = x.size
    freqs = np.fft.rfftfreq(n, 1.0 / rate)
    return np.fft.irfft(np.fft.rfft(x) * shape(freqs), n)


def band(lo, hi, slope=4.0):
    """A smooth band-pass magnitude (Butterworth-like edges) for spectral shaping."""
    def f(freqs):
        fr = np.maximum(freqs, 1e-3)
        hp = 1.0 / np.sqrt(1.0 + (lo / fr) ** (2 * slope)) if lo > 0 else 1.0
        lp = 1.0 / np.sqrt(1.0 + (fr / hi) ** (2 * slope)) if hi > 0 else 1.0
        return hp * lp
    return f


def peak_shape(center, width_oct, floor=0.0):
    """A log-gaussian bump around center, width in octaves (sigma)."""
    def f(freqs):
        fr = np.maximum(freqs, 1e-3)
        return floor + np.exp(-0.5 * (np.log2(fr / center) / width_oct) ** 2)
    return f


def pink(slope_db_per_oct=-3.0, ref=1000.0):
    def f(freqs):
        fr = np.maximum(freqs, 1.0)
        return (fr / ref) ** (slope_db_per_oct / 6.0206)
    return f


# ----------------------------------------------------------------------------- one-shot helpers

def t_axis(seconds, rate):
    return np.arange(int(round(seconds * rate))) / rate


def env_ad(t, attack, decay, start=0.0):
    """Linear attack, exponential decay (time constant decay), starting at start."""
    e = np.zeros_like(t)
    u = t - start
    a = (u >= 0) & (u < attack)
    e[a] = u[a] / attack
    d = u >= attack
    e[d] = np.exp(-(u[d] - attack) / decay)
    return e


def sweep(f0, f1, t, curve="exp"):
    """Phase of a frequency sweep from f0 to f1 across t (exponential or linear)."""
    dur = max(t[-1] - t[0], 1e-9)
    u = (t - t[0]) / dur
    if curve == "exp":
        f = f0 * (f1 / f0) ** u
    else:
        f = f0 + (f1 - f0) * u
    dt = np.diff(t, prepend=t[0])
    return 2 * np.pi * np.cumsum(f * dt), f


def butter(x, rate, kind, f, order=4):
    sos = signal.butter(order, f, btype=kind, fs=rate, output="sos")
    return signal.sosfilt(sos, x)


def noise(n, seed):
    return np.random.default_rng(seed).standard_normal(n)


def reverb_ir(seconds, rate, decay, seed, lowpass=6000.0):
    """A synthetic room/space impulse response: decaying filtered noise, faded out over its last third
    (a tail that stops abruptly would cut the reverb off audibly)."""
    t = t_axis(seconds, rate)
    ir = noise(t.size, seed) * np.exp(-t / decay)
    ir = butter(ir, rate, "low", lowpass, 2)
    ir[0] = 0.0
    n = ir.size // 3
    ir[-n:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, n))
    return ir / np.sqrt(np.sum(ir ** 2))


def svf(x, rate, fc, q=0.7, mode="band"):
    """Time-varying state-variable filter (topology-preserving transform): fc may change every sample,
    so swept filters stay smooth (no segment seams). mode: band, low, high."""
    fc = np.broadcast_to(np.asarray(fc, dtype=np.float64), x.shape)
    g = np.tan(np.pi * np.clip(fc, 10.0, 0.45 * rate) / rate)
    k = 1.0 / q
    a1 = 1.0 / (1.0 + g * (g + k))
    a2 = g * a1
    a3 = g * a2
    out = np.empty_like(x, dtype=np.float64)
    ic1 = 0.0
    ic2 = 0.0
    xs = x.tolist()
    a1s, a2s, a3s = a1.tolist(), a2.tolist(), a3.tolist()
    m = {"band": 0, "low": 1, "high": 2}[mode]
    res = [0.0] * len(xs)
    for i in range(len(xs)):
        v0 = xs[i]
        v3 = v0 - ic2
        v1 = a1s[i] * ic1 + a2s[i] * v3
        v2 = ic2 + a2s[i] * ic1 + a3s[i] * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        res[i] = v1 if m == 0 else (v2 if m == 1 else v0 - k * v1 - v2)
    out[:] = res
    return out


def convolve(x, ir):
    return signal.fftconvolve(x, ir)[: x.size]


def fade(x, rate, fin=0.0, fout=0.0):
    y = x.copy()
    n_in = int(fin * rate)
    n_out = int(fout * rate)
    if n_in > 0:
        y[:n_in] *= np.linspace(0, 1, n_in)
    if n_out > 0:
        y[-n_out:] *= np.linspace(1, 0, n_out)
    return y


def soft_clip(x, drive=1.0):
    return np.tanh(drive * x) / np.tanh(drive)


# ----------------------------------------------------------------------------- files

def write_wav(path, x, rate):
    from scipy.io import wavfile
    y = np.asarray(x, dtype=np.float64)
    y = np.clip(y, -1.0, 1.0)
    wavfile.write(path, rate, (y * 32767.0).astype(np.int16))


def encode_ogg(x, rate, path, quality, channels=1):
    """float signal (n,) or (n, 2) -> OGG Vorbis at quality q (libvorbis), metadata stripped."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "in.wav")
        write_wav(wav, x, rate)
        cmd = [
            "ffmpeg", "-v", "error", "-y", "-i", wav, "-map_metadata", "-1", "-fflags", "+bitexact",
            "-flags:a", "+bitexact", "-c:a", "libvorbis", "-q:a", str(quality), "-ac", str(channels),
            "-ar", str(rate), path,
        ]
        subprocess.run(cmd, check=True)


def decode(path, rate=None, channels=None):
    """Decodes any file with ffmpeg to float32 (n,) or (n, c) at its own (or the given) rate."""
    if rate is None or channels is None:
        info = probe(path)
        rate = rate or info["rate"]
        channels = channels or info["channels"]
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-acodec", "pcm_f32le", "-ac", str(channels), "-ar", str(rate), "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    x = np.frombuffer(raw, dtype=np.float32).astype(np.float64)
    if channels > 1:
        x = x.reshape(-1, channels)
    return x, rate


def probe(path):
    cmd = ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels",
           "-show_entries", "format=duration", "-of", "default=nw=1", path]
    out = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    d = {}
    for line in out.splitlines():
        k, _, v = line.partition("=")
        d[k] = v
    return {"rate": int(d["sample_rate"]), "channels": int(d["channels"]), "duration": float(d["duration"])}


def spectrogram_png(path, png, width=1200, height=480, legend=1, unpad=False):
    """ffmpeg showspectrumpic of a file; unpad cuts a loop's codec padding first (what the game plays)."""
    os.makedirs(os.path.dirname(png), exist_ok=True)
    pre = ""
    if unpad:
        d = probe(path)["duration"]
        pre = "atrim=start=%.4f:end=%.4f," % (LOOP_PAD_SECONDS, d - LOOP_PAD_SECONDS)
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", path, "-lavfi",
           pre + "showspectrumpic=s=%dx%d:legend=%d:scale=log:fscale=lin:color=intensity" % (width, height, legend), png]
    subprocess.run(cmd, check=True)
