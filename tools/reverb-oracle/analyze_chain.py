"""Verify the four-stage all-pass chain against an absolute-index trace and regress the next stage.

Input: build/trace_abs.txt produced with
  ABS=1 ./build/reverb_trace 1700 0.55 0.5 0.45 0.15 1.0 0.5 0.5 left
Model per stage k (Schroeder all-pass, Float32 in native): y = delayed - c*u ; stored = u + c*y.
Stage gains 0.75, 0.75, 0.625, 0.625; stage 1 input is the pre-delayed filtered input s.
"""
import re
import numpy as np

ONSET = 1440
CELL = [158, 764, 1578, 2220]      # absolute index of each stage's write at the onset frame
GAINS = [0.75, 0.75, 0.625, 0.625]
ACC = 2496                          # fixed slot that follows the chain

frames = {}
cur = None
for line in open('build/trace_abs.txt'):
    m = re.match(r'frame (\d+) out', line)
    if m:
        cur = int(m.group(1))
        frames[cur] = {}
        continue
    m = re.match(r'\s+A\[(\d+)\] (\S+) -> (\S+)', line)
    if m:
        frames[cur][int(m.group(1))] = (float(m.group(2)), float(m.group(3)))

def val(frame, idx, which):
    # A write that leaves a slot at exactly zero is not logged, so a missing entry means zero.
    return frames.get(frame, {}).get(idx, (0.0, 0.0))[which]


N = 170  # shortest all-pass delay is 172 samples: before that the chain has no feedback
s = np.array([val(ONSET + n, 37439 + n, 0) for n in range(N)])
u = s.copy()
ys, rings = [], []
for g in GAINS:
    y = -g * u                      # delayed = 0 inside the first 172 samples
    ring = u + g * y
    ys.append(y)
    rings.append(ring)
    u = y
for k in range(4):
    got = np.array([val(ONSET + n, CELL[k] + n, 1) for n in range(N)])
    err = np.abs(got - rings[k]).max()
    print('stage %d stored value: max abs error vs model %.3g (scale %.3g)' % (k + 1, err, np.abs(got).max()))

y4 = ys[3]
acc = np.array([val(ONSET + n, ACC, 1) for n in range(N)])
print('stage-4 output y4[0..3]:', y4[:4])
print('slot A[%d][0..3]:' % ACC, acc[:4])

# Regress the slot on current and past y4 and its own past values.
rows, rhs = [], []
for n in range(3, N):
    rows.append([y4[n], y4[n - 1], y4[n - 2], acc[n - 1], acc[n - 2]])
    rhs.append(acc[n])
A = np.array(rows)
b = np.array(rhs)
coef, *_ = np.linalg.lstsq(A, b, rcond=None)
print('slot = %.6f*y4[n] + %.6f*y4[n-1] + %.6f*y4[n-2] + %.6f*slot[n-1] + %.6f*slot[n-2]' % tuple(coef))
print('max residual %.3g (values up to %.3g)' % (np.abs(A @ coef - b).max(), np.abs(b).max()))

print('--- candidate small-filter models for the slot after stage 4 ---')
def fit(cols, label):
    rows = [[c(n) for c in cols] for n in range(3, N)]
    A = np.array(rows); b = acc[3:N]
    coef, *_ = np.linalg.lstsq(A, b, rcond=None)
    print('%-34s coef=%s  max resid %.3g' % (label, np.round(coef, 6), np.abs(A @ coef - b).max()))
Y = lambda k: (lambda n: y4[n - k])
S = lambda k: (lambda n: acc[n - k])
fit([Y(0), S(1)], 'y[n], s[n-1]')
fit([Y(0), S(1), S(2)], 'y[n], s[n-1], s[n-2]')
fit([Y(0), Y(1), S(1)], 'y[n], y[n-1], s[n-1]')
fit([Y(0), Y(1), S(1), S(2)], 'y[n], y[n-1], s[n-1], s[n-2]')
fit([Y(0), Y(1), Y(2), S(1), S(2)], 'y0..y2, s1, s2')

print('--- full chain with delay lengths: every stage write, all frames ---')
LENGTHS = [230, 172, 609, 446]   # 4.8, 3.6, 12.7, 9.3 ms at 48 kHz
REGION = [0, 624, 1248, 1872]    # each stage's slots start 624 floats apart in the arena
TOTAL = 1700 - ONSET
sfull = np.array([val(ONSET + n, 37439 + n, 0) for n in range(TOTAL)])
state = [np.zeros(L) for L in LENGTHS]
worst = [0.0] * 4
compared = [0] * 4
for n in range(TOTAL):
    u = sfull[n]
    for k in range(4):
        L = LENGTHS[k]
        delayed = state[k][n % L]
        y = delayed - GAINS[k] * u
        w = u + GAINS[k] * y
        state[k][n % L] = w
        for idx, (_, new) in frames.get(ONSET + n, {}).items():
            if REGION[k] <= idx < REGION[k] + 624:
                worst[k] = max(worst[k], abs(new - w))
                compared[k] += 1
                break
        u = y
for k in range(4):
    print('stage %d: %d writes compared, max abs error %.3g' % (k + 1, compared[k], worst[k]))
