"""Measure the late-network onset frame (first write into the all-pass buffer family) for many
Pre-delay values and sample rates, using build/reverb_trace with RATE set. Prints the frame so a
formula can be fitted. usage: python3 predelay_onset.py
"""
import os
import re
import subprocess

D = [0.55, 0.5, 0.45, 0.15, 1.0, 0.5, 0.5]


def onset(rate, p3):
    p = list(D)
    p[3] = p3
    frames = int(rate * 0.30) + 200
    env = dict(os.environ, RATE=str(rate), ONLYOFF='0x008')
    out = subprocess.run(['./build/reverb_trace', str(frames)] + [repr(float(v)) for v in p] + ['left'],
                         capture_output=True, text=True, check=True, env=env).stdout
    cur = None
    for line in out.splitlines():
        m = re.match(r'frame (\d+) out', line)
        if m:
            cur = int(m.group(1))
            continue
        if cur and re.match(r'\s+buf@0x008\[', line):
            return cur
    return None


if __name__ == '__main__':
    values = [0.0, 0.005, 0.0999, 0.1, 0.1499, 0.15, 0.1501, 0.333, 0.5, 0.9999, 1.0]
    for rate in (44100, 48000):
        print('rate', rate)
        for v in values:
            f = onset(rate, v)
            print('  p3=%-7s onset=%s   int(p*200)=%d ms -> int-ms model %s, continuous floor(p*200*Fs/1000)=%d' % (
                v, f, int(v * 200), (int(v * 200) * rate) // 1000, int(v * 200.0 * rate / 1000.0)))
