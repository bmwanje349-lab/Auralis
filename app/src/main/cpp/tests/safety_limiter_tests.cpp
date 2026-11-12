#include "safety_limiter.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

using auralis::SafetyLimiter;

namespace {

constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;
int failures = 0;

void check(bool ok, const std::string& label) {
    if (!ok) {
        std::cerr << "FAIL: " << label << '\n';
        ++failures;
    } else {
        std::cout << "PASS: " << label << '\n';
    }
}

std::vector<float> sine(double hz, double amplitude, double seconds, double phase = 0.0, double rightScale = 1.0) {
    const std::size_t frames = static_cast<std::size_t>(seconds * kRate);
    std::vector<float> b(frames * 2);
    for (std::size_t i = 0; i < frames; ++i) {
        const double v = amplitude * std::sin(2.0 * kPi * hz * static_cast<double>(i) / kRate + phase);
        b[2 * i] = static_cast<float>(v);
        b[2 * i + 1] = static_cast<float>(v * rightScale);
    }
    return b;
}

double samplePeak(const std::vector<float>& b, std::size_t fromFrame = 0) {
    double p = 0.0;
    for (std::size_t i = fromFrame * 2; i < b.size(); ++i) p = std::max(p, static_cast<double>(std::fabs(b[i])));
    return p;
}

// Reference true peak: 8x oversampling with a 64-tap Hann-windowed sinc (independent of the limiter's estimator).
double truePeak(const std::vector<float>& b, std::size_t fromFrame = 0) {
    const long frames = static_cast<long>(b.size() / 2);
    double peak = samplePeak(b, fromFrame);
    constexpr int kTaps = 32;
    for (long i = std::max<long>(static_cast<long>(fromFrame), kTaps); i + kTaps < frames; ++i) {
        for (int sub = 1; sub < 8; ++sub) {
            const double frac = sub / 8.0;
            for (int ch = 0; ch < 2; ++ch) {
                double acc = 0.0;
                for (int t = -kTaps + 1; t <= kTaps; ++t) {
                    const double d = static_cast<double>(t) - frac;
                    const double sinc = std::sin(kPi * d) / (kPi * d);
                    const double w = 0.5 + 0.5 * std::cos(kPi * d / (kTaps + 1));
                    acc += static_cast<double>(b[static_cast<std::size_t>(2 * (i + t) + ch)]) * sinc * w;
                }
                peak = std::max(peak, std::fabs(acc));
            }
        }
    }
    return peak;
}

void runInChunks(SafetyLimiter& l, std::vector<float>& b, std::size_t chunk) {
    const std::size_t frames = b.size() / 2;
    for (std::size_t i = 0; i < frames; i += chunk) {
        l.process(b.data() + 2 * i, std::min(chunk, frames - i));
    }
}

double dbOf(double ratio) { return 20.0 * std::log10(ratio); }

}  // namespace

int main() {
    const double ceiling = std::pow(10.0, -0.3 / 20.0);

    // Quiet material: untouched apart from the fixed delay, and the gain never leaves 1.0.
    {
        SafetyLimiter l(kRate);
        std::vector<float> in = sine(440.0, 0.25, 0.5);
        std::vector<float> out = in;
        l.process(out.data(), out.size() / 2);
        const std::size_t d = l.latencyFrames();
        bool same = true;
        for (std::size_t i = d; i < in.size() / 2; ++i) {
            if (out[2 * i] != in[2 * (i - d)] || out[2 * i + 1] != in[2 * (i - d) + 1]) { same = false; break; }
        }
        check(same, "quiet audio passes through bit-exact, delayed by latencyFrames");
        check(l.currentGain() == 1.0 && l.maxReductionDb() == 0.0, "quiet audio never reduces gain");
        check(d > 0 && d < static_cast<std::size_t>(kRate / 100), "latency is a few milliseconds at most");
    }

    // Hot sine at +6 dBFS: nothing above the ceiling, ever, from the first sample.
    {
        SafetyLimiter l(kRate);
        std::vector<float> b = sine(100.0, 2.0, 1.0);
        l.process(b.data(), b.size() / 2);
        check(samplePeak(b) <= ceiling + 1e-6, "+6 dBFS sine never exceeds the ceiling");
        check(samplePeak(b, kRate / 2) > ceiling * 0.97, "limiting is not over-aggressive once settled");
        check(l.maxReductionDb() < -5.0 && l.maxReductionDb() > -7.0, "reduction matches the overshoot (about 6 dB)");
    }

    // Inter-sample peaks: samples sit at 0.92 but the true waveform reaches 1.3.
    {
        SafetyLimiter l(kRate);
        std::vector<float> b = sine(kRate / 4.0, 1.3, 0.5, kPi / 4.0);
        check(samplePeak(b) < 0.93 && truePeak(b) > 1.25, "test signal really has hidden inter-sample peaks");
        l.process(b.data(), b.size() / 2);
        const double tp = truePeak(b, kRate / 10);
        check(tp <= 1.0, "inter-sample (true) peak is held at or below full scale");
        check(dbOf(tp) < 0.0, "true peak ends below 0 dBFS");
    }

    // Stereo link: loud left, quiet right keep their balance.
    {
        SafetyLimiter l(kRate);
        std::vector<float> b = sine(220.0, 2.0, 1.0, 0.0, 0.1);
        l.process(b.data(), b.size() / 2);
        bool linked = true;
        for (std::size_t i = kRate / 4; i < b.size() / 2; ++i) {
            const double L = b[2 * i], R = b[2 * i + 1];
            if (std::fabs(L) > 0.05 && std::fabs(R / L - 0.1) > 1e-3) { linked = false; break; }
        }
        check(linked, "gain reduction is stereo-linked (image does not shift)");
    }

    // Release: after a loud burst the gain comes back to 1.0.
    {
        SafetyLimiter l(kRate);
        std::vector<float> burst = sine(200.0, 3.0, 0.2);
        l.process(burst.data(), burst.size() / 2);
        check(l.currentGain() < 0.5, "burst pulls gain down hard");
        std::vector<float> quiet = sine(200.0, 0.1, 1.5);
        l.process(quiet.data(), quiet.size() / 2);
        check(l.currentGain() > 0.999, "gain recovers to unity after the burst");
        // Recovery is gradual, not an instant jump (no pumping artefacts).
        SafetyLimiter m(kRate);
        std::vector<float> b2 = sine(200.0, 3.0, 0.2);
        m.process(b2.data(), b2.size() / 2);
        std::vector<float> q2 = sine(200.0, 0.1, 0.01);
        m.process(q2.data(), q2.size() / 2);
        check(m.currentGain() < 0.9, "release is slow (gain still recovering 10 ms after the burst)");
    }

    // Chunk-size invariance: block boundaries must not change a single sample.
    {
        std::vector<float> src = sine(97.0, 1.8, 0.7);
        for (std::size_t i = 0; i < src.size(); ++i) src[i] += 0.3f * static_cast<float>(std::sin(0.001 * static_cast<double>(i * i % 977)));
        SafetyLimiter a(kRate), b(kRate), c(kRate);
        std::vector<float> one = src, seven = src, big = src;
        runInChunks(a, one, one.size() / 2);
        runInChunks(b, seven, 7);
        runInChunks(c, big, 1024);
        check(one == seven && one == big, "output is identical for any chunk size (1024, 7, whole)");
    }

    // Loud noise, crest factor like a hot master: no sample above full scale, no non-finite output.
    {
        SafetyLimiter l(kRate);
        std::srand(7);
        std::vector<float> b(kRate * 4);
        for (float& v : b) v = (static_cast<float>(std::rand()) / static_cast<float>(RAND_MAX) * 2.0f - 1.0f) * 2.8f;
        l.process(b.data(), b.size() / 2);
        bool finite = true;
        for (float v : b) if (!std::isfinite(v)) finite = false;
        check(finite && samplePeak(b) <= 1.0, "loud noise (+9 dBFS) stays within full scale");
        check(samplePeak(b) <= ceiling + 1e-6, "loud noise respects the ceiling, not just full scale");
    }

    // Bad input values cannot poison the stream.
    {
        SafetyLimiter l(kRate);
        std::vector<float> b = sine(300.0, 0.5, 0.2);
        b[100] = std::numeric_limits<float>::quiet_NaN();
        b[101] = std::numeric_limits<float>::infinity();
        l.process(b.data(), b.size() / 2);
        bool finite = true;
        for (float v : b) if (!std::isfinite(v)) finite = false;
        check(finite, "NaN and infinity in the input never reach the output");
        std::vector<float> after = sine(300.0, 0.5, 0.2);
        l.process(after.data(), after.size() / 2);
        check(l.currentGain() > 0.99, "stream recovers after bad input");
    }

    // Disabled: bit-exact, zero latency. Re-enabling starts from clean state.
    {
        SafetyLimiter l(kRate);
        l.setEnabled(false);
        std::vector<float> b = sine(100.0, 2.0, 0.1);
        const std::vector<float> original = b;
        l.process(b.data(), b.size() / 2);
        check(b == original, "disabled limiter is bit-exact pass-through with no delay");
        l.setEnabled(true);
        check(l.currentGain() == 1.0, "re-enabling starts from unity gain");
    }

    // Sample rate scaling: window and delay track the rate.
    {
        SafetyLimiter lo(44100), hi(96000);
        check(hi.latencyFrames() > lo.latencyFrames(), "latency scales with the sample rate");
        std::vector<float> b(96000 * 2);
        for (std::size_t i = 0; i < b.size() / 2; ++i) {
            const float v = static_cast<float>(2.0 * std::sin(2.0 * kPi * 150.0 * static_cast<double>(i) / 96000.0));
            b[2 * i] = v; b[2 * i + 1] = v;
        }
        hi.process(b.data(), b.size() / 2);
        check(samplePeak(b) <= ceiling + 1e-6, "96 kHz stream is limited the same way");
    }

    if (failures != 0) {
        std::cerr << failures << " check(s) failed\n";
        return 1;
    }
    std::cout << "All safety limiter checks passed\n";
    return 0;
}
