"""For each native parameter index, change it alone and report the first buffer write that differs
from the default trace: (frame, buffer, slot, default value, new value). Localizes where each
control acts in the signal path. Uses build/reverb_trace.  usage: python3 first_diff.py [frames]
"""
import re
import subprocess
import sys

D = [0.55, 0.5, 0.45, 0.15, 1.0, 0.5, 0.5]
FRAMES = int(sys.argv[1]) if len(sys.argv) > 1 else 2200


def events(params):
    out = subprocess.run(['./build/reverb_trace', str(FRAMES)] + [repr(float(v)) for v in params] + ['left'],
                         capture_output=True, text=True, check=True).stdout
    ev, outs, cur = [], [], None
    for line in out.splitlines():
        m = re.match(r'frame (\d+) out=\(\s*(\S+),\s*(\S+)\)', line)
        if m:
            cur = int(m.group(1))
            outs.append((float(m.group(2)), float(m.group(3))))
            continue
        m = re.match(r'\s+buf@(0x[0-9a-f]+)\[(\d+)\] \S+ -> (\S+)', line)
        if m:
            ev.append((cur, m.group(1), int(m.group(2)), float(m.group(3))))
    return ev, outs


def first_difference(a, b):
    sa = {(f, buf, i): v for f, buf, i, v in a}
    sb = {(f, buf, i): v for f, buf, i, v in b}
    keys = sorted(set(sa) | set(sb))
    for k in keys:
        if sa.get(k) != sb.get(k):
            return k, sa.get(k), sb.get(k)
    return None, None, None


if __name__ == '__main__':
    base_ev, base_out = events(D)
    for i in range(7):
        for v in (0.1, 0.9):
            p = list(D)
            p[i] = v
            ev, out = events(p)
            k, x, y = first_difference(base_ev, ev)
            fo = next((n for n, (a, b) in enumerate(zip(base_out, out)) if a != b), None)
            print('idx%d=%.1f  first differing write: %s default=%s new=%s | first differing output frame: %s' % (i, v, k, x, y, fo))
