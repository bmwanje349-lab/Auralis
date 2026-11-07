"""Check a Float32 low-pass biquad model of the reverb input filter against the native trace.

Model: cutoff = 100 + 18500 * Filter (Hz), Q = 1/sqrt(2), RBJ low-pass, input scale 0.5 (mono sum of left
impulse), coefficients normalized by 1/a0, run in direct form I and transposed direct form II in Float32.
Compares to the first buffer's written sequence from build/reverb_trace.
"""
import numpy as np
from fit_input_filter import trace, D

f32 = np.float32


def coeffs(filt, fs=48000.0):
    fc = f32(100.0) + f32(18500.0) * f32(filt)
    w = f32(2.0 * np.pi) * fc / f32(fs)
    c, s = f32(np.cos(w)), f32(np.sin(w))
    q = f32(0.70710678)
    alpha = s / (f32(2.0) * q)
    a0 = f32(1.0) + alpha
    inv = f32(1.0) / a0
    b0 = ((f32(1.0) - c) * f32(0.5)) * inv
    b1 = (f32(1.0) - c) * inv
    a1 = (f32(-2.0) * c) * inv
    a2 = (f32(1.0) - alpha) * inv
    return b0, b1, b0, a1, a2


def run_df1(x, co):
    b0, b1, b2, a1, a2 = co
    y = []
    x1 = x2 = y1 = y2 = f32(0.0)
    for v in x:
        v = f32(v)
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, f32(o)
        y.append(f32(o))
    return np.array(y, dtype=np.float32)


def run_tdf2(x, co):
    b0, b1, b2, a1, a2 = co
    z1 = z2 = f32(0.0)
    y = []
    for v in x:
        v = f32(v)
        o = b0 * v + z1
        z1 = b1 * v - a1 * o + z2
        z2 = b2 * v - a2 * o
        y.append(f32(o))
    return np.array(y, dtype=np.float32)


if __name__ == '__main__':
    for filt in (0.0, 0.1, 0.5, 0.9, 1.0):
        p = list(D)
        p[1] = filt
        ref = trace(p, frames=64).astype(np.float32)
        x = np.zeros(len(ref), dtype=np.float32)
        x[0] = 0.5  # mono sum (L+R)/2 of a unit left impulse
        co = coeffs(filt)
        for name, fn in (('DF1', run_df1), ('TDF2', run_tdf2)):
            y = fn(x, co)
            err = np.abs(y.astype(np.float64) - ref.astype(np.float64))
            exact = int((y == ref).sum())
            print('filter=%.2f %-4s max_abs_err=%.3g  bit_exact_samples=%d/%d' % (filt, name, err.max(), exact, len(ref)))
