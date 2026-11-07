"""Fit the reverb's first (input) filter from a frame-by-frame impulse trace.

Runs build/reverb_trace for given UI parameters, takes the first buffer's written sequence,
fits y[n] = p1*y[n-1] + p2*y[n-2] (n >= 3), recovers the numerator scale from the first sample,
and converts to an RBJ low-pass (w, Q). usage: python3 fit_input_filter.py
"""
import re
import subprocess
import numpy as np

D = [0.55, 0.5, 0.45, 0.15, 1.0, 0.5, 0.5]
NAMES = ['p0', 'p1', 'p2', 'p3', 'p4', 'p5', 'p6']


def trace(params, frames=48, buf='0x100'):
    out = subprocess.run(['./build/reverb_trace', str(frames)] + [repr(float(v)) for v in params] + ['left'],
                         capture_output=True, text=True, check=True).stdout
    seq = {}
    for line in out.splitlines():
        m = re.match(r'\s+buf@%s\[(\d+)\] \S+ -> (\S+)' % buf, line)
        if m:
            seq[int(m.group(1))] = float(m.group(2))
    return np.array([seq[i] for i in sorted(seq)])


def fit(seq):
    A = np.array([[seq[n - 1], seq[n - 2]] for n in range(3, len(seq))])
    b = seq[3:]
    (p1, p2), *_ = np.linalg.lstsq(A, b, rcond=None)
    resid = np.abs(A @ np.array([p1, p2]) - b).max()
    k = seq[0]  # numerator is k*(1,2,1)
    a1, a2 = -p1, -p2
    alpha = (1 - a2) / (1 + a2)
    c = -a1 * (1 + alpha) / 2
    w = np.arccos(np.clip(c, -1, 1))
    q = np.sin(w) / (2 * alpha)
    return dict(a1=a1, a2=a2, k=k, w=w, hz48k=w * 48000 / (2 * np.pi), q=q, resid=resid)


if __name__ == '__main__':
    for i in (1, 6, 5, 3):
        print('--- varying', NAMES[i])
        for v in (0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0):
            p = list(D)
            p[i] = v
            try:
                f = fit(trace(p))
                print('%s=%.2f  k=%.6f w=%.5f hz=%.1f Q=%.4f resid=%.1e' % (NAMES[i], v, f['k'], f['w'], f['hz48k'], f['q'], f['resid']))
            except Exception as e:
                print('%s=%.2f  fit failed: %s' % (NAMES[i], v, e))
