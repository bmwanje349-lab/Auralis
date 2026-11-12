#include "bass_boost.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

using auralis::BassBoost;

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

double dbOf(double ratio) { return 20.0 * std::log10(ratio); }

std::vector<float> sine(double hz, double amplitude, double seconds) {
    const std::size_t frames = static_cast<std::size_t>(seconds * kRate);
    std::vector<float> buffer(frames * 2);
    for (std::size_t i = 0; i < frames; ++i) {
        const float v = static_cast<float>(amplitude * std::sin(2.0 * kPi * hz * static_cast<double>(i) / kRate));
        buffer[2 * i] = v;
        buffer[2 * i + 1] = v;
    }
    return buffer;
}

void run(BassBoost& stage, std::vector<float>& buffer) {
    const std::size_t frames = buffer.size() / 2;
    for (std::size_t at = 0; at < frames; at += 1024) {
        stage.process(buffer.data() + at * 2, std::min<std::size_t>(1024, frames - at));
    }
}

// RMS of the left channel over the last `seconds` of the buffer.
double tailRms(const std::vector<float>& buffer, double seconds) {
    const std::size_t frames = buffer.size() / 2;
    const std::size_t count = static_cast<std::size_t>(seconds * kRate);
    double sum = 0.0;
    for (std::size_t i = frames - count; i < frames; ++i) {
        const double v = buffer[2 * i];
        sum += v * v;
    }
    return std::sqrt(sum / static_cast<double>(count));
}

// Magnitude of one frequency in the last `seconds` of the left channel (Goertzel).
double tailTone(const std::vector<float>& buffer, double hz, double seconds) {
    const std::size_t frames = buffer.size() / 2;
    const std::size_t count = static_cast<std::size_t>(seconds * kRate);
    const double w = 2.0 * kPi * hz / kRate;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (std::size_t i = frames - count; i < frames; ++i) {
        const double s = static_cast<double>(buffer[2 * i]) + coeff * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    const double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
    return 2.0 * std::sqrt(std::max(0.0, power)) / static_cast<double>(count);
}

// Gain in dB of a steady tone passed through the stage at the given strength.
double toneGainDb(double hz, double amplitude, double amount) {
    BassBoost stage;
    stage.prepare(kRate);
    stage.configure(true, amount);
    std::vector<float> buffer = sine(hz, amplitude, 2.5);
    const double before = tailRms(buffer, 0.5);
    run(stage, buffer);
    return dbOf(tailRms(buffer, 0.5) / before);
}

} // namespace

int main() {
    // Disabled: bit-exact pass-through.
    {
        BassBoost stage;
        stage.prepare(kRate);
        stage.configure(false, 1.0);
        std::vector<float> buffer = sine(60.0, 0.5, 0.5);
        const std::vector<float> original = buffer;
        run(stage, buffer);
        check(buffer == original, "disabled stage is bit-exact pass-through");
    }

    // Quiet bass gets a strong lift, and a higher strength lifts more.
    const double quietHigh = toneGainDb(60.0, 0.03, 1.0);
    const double quietMedium = toneGainDb(60.0, 0.03, 0.65);
    const double quietLow = toneGainDb(60.0, 0.03, 0.35);
    std::cout << "60 Hz at -30 dBFS: high " << quietHigh << " dB, medium " << quietMedium
              << " dB, low " << quietLow << " dB\n";
    check(quietHigh > 8.0 && quietHigh < 15.0, "high strength lifts quiet 60 Hz bass by roughly 8-15 dB");
    check(quietMedium > 4.0 && quietMedium < quietHigh - 1.0, "medium strength lifts less than high");
    check(quietLow > 1.5 && quietLow < quietMedium - 0.5, "low strength lifts less than medium");

    // Dynamic: the same note played loud is lifted much less, so loud bass stays clean.
    const double loudHigh = toneGainDb(60.0, 0.6, 1.0);
    std::cout << "60 Hz at -4 dBFS, high: " << loudHigh << " dB\n";
    check(loudHigh < quietHigh - 3.0, "boost eases off as the bass gets louder");
    check(loudHigh > 0.5, "loud bass is still lifted a little");

    // Subsonic rumble is not boosted.
    const double rumble = toneGainDb(15.0, 0.1, 1.0);
    std::cout << "15 Hz at -20 dBFS, high: " << rumble << " dB\n";
    check(rumble < 3.0, "subsonic 15 Hz rumble is not boosted");

    // Content above the bass band is left essentially alone (only the small air lift above 4 kHz).
    const double mids = toneGainDb(1000.0, 0.1, 1.0);
    std::cout << "1 kHz at -20 dBFS, high: " << mids << " dB\n";
    check(std::fabs(mids) < 0.5, "1 kHz is not changed");

    // JBL-style consumer voicing: a dip in the boxy region, a lift in presence and air, and
    // everything scaling with the strength.
    {
        const double tuck = toneGainDb(350.0, 0.1, 1.0);
        const double presence = toneGainDb(3000.0, 0.1, 1.0);
        const double air = toneGainDb(10000.0, 0.1, 1.0);
        const double halfTuck = toneGainDb(350.0, 0.1, 0.5);
        std::cout << "voicing at full strength: 350 Hz " << tuck << " dB, 3 kHz " << presence
                  << " dB, 10 kHz " << air << " dB; 350 Hz at half strength " << halfTuck << " dB\n";
        check(tuck < -1.0 && tuck > -3.5, "voicing dips the boxy 350 Hz region by roughly 1-3 dB");
        check(presence > 0.8 && presence < 2.5, "voicing lifts vocal presence at 3 kHz by roughly 1-2 dB");
        check(air > 1.5 && air < 3.5, "voicing adds a little air at 10 kHz");
        check(halfTuck > tuck && halfTuck < 0.0, "voicing scales with the strength");
    }

    // The bass lift must hand over cleanly: no hole in the low mids where the boost ends.
    {
        double worst = 0.0;
        for (double hz : {200.0, 250.0, 300.0, 400.0, 500.0, 700.0}) {
            worst = std::min(worst, toneGainDb(hz, 0.1, 1.0));
        }
        std::cout << "deepest dip between 200 Hz and 700 Hz: " << worst << " dB\n";
        check(worst > -2.6, "no hole in the low mids above the boosted bass");
    }

    // Virtual bass: a pure 60 Hz tone gains harmonics it did not have.
    {
        BassBoost stage;
        stage.prepare(kRate);
        stage.configure(true, 1.0);
        std::vector<float> buffer = sine(60.0, 0.1, 2.5);
        run(stage, buffer);
        const double h3 = tailTone(buffer, 180.0, 0.5);
        const double h2 = tailTone(buffer, 120.0, 0.5);
        std::cout << "harmonics: 120 Hz " << h2 << ", 180 Hz " << h3 << "\n";
        check(h3 > 0.002, "virtual bass adds a 3rd harmonic");
        check(h2 + h3 > 0.004, "virtual bass adds harmonic energy above the fundamental");

        BassBoost off;
        off.prepare(kRate);
        off.configure(false, 1.0);
        std::vector<float> plain = sine(60.0, 0.1, 2.5);
        run(off, plain);
        check(tailTone(plain, 180.0, 0.5) < 1.0e-4, "the plain input has no 180 Hz energy");
    }

    // Safety: a full-scale, bass-heavy signal never reaches or exceeds full scale, and stays finite.
    {
        BassBoost stage;
        stage.prepare(kRate);
        stage.configure(true, 1.0);
        std::vector<float> buffer(static_cast<std::size_t>(3.0 * kRate) * 2);
        std::srand(12345);
        for (std::size_t i = 0; i < buffer.size() / 2; ++i) {
            const double t = static_cast<double>(i) / kRate;
            const double kick = 0.7 * std::sin(2.0 * kPi * 55.0 * t) + 0.25 * std::sin(2.0 * kPi * 90.0 * t);
            const double noise = (static_cast<double>(std::rand()) / RAND_MAX - 0.5) * 0.1;
            const float v = static_cast<float>(std::clamp(kick + noise, -1.0, 1.0));
            buffer[2 * i] = v;
            buffer[2 * i + 1] = v;
        }
        run(stage, buffer);
        double peak = 0.0;
        bool finite = true;
        for (float v : buffer) {
            if (!std::isfinite(v)) finite = false;
            peak = std::max(peak, static_cast<double>(std::fabs(v)));
        }
        std::cout << "peak after boost: " << peak << "\n";
        check(finite, "output stays finite");
        check(peak < 1.0, "output never reaches full scale");
    }

    // Switching on mid-note does not click: no sample step much bigger than the music itself.
    {
        BassBoost stage;
        stage.prepare(kRate);
        stage.configure(false, 1.0);
        std::vector<float> buffer = sine(80.0, 0.5, 1.0);
        const std::size_t half = buffer.size() / 4 * 2;
        std::vector<float> first(buffer.begin(), buffer.begin() + static_cast<long>(half));
        std::vector<float> second(buffer.begin() + static_cast<long>(half), buffer.end());
        run(stage, first);
        stage.configure(true, 1.0);
        run(stage, second);
        double maxStep = 0.0, maxOriginalStep = 0.0;
        std::vector<float> joined = first;
        joined.insert(joined.end(), second.begin(), second.end());
        const std::vector<float> original = sine(80.0, 0.5, 1.0);
        for (std::size_t i = 1; i < joined.size() / 2; ++i) {
            maxStep = std::max(maxStep, static_cast<double>(std::fabs(joined[2 * i] - joined[2 * (i - 1)])));
            maxOriginalStep = std::max(maxOriginalStep, static_cast<double>(std::fabs(original[2 * i] - original[2 * (i - 1)])));
        }
        std::cout << "largest step " << maxStep << " vs original " << maxOriginalStep << "\n";
        check(maxStep < maxOriginalStep * 3.0, "enabling mid-note is click-free");
    }

    // Switching off fades back to exact pass-through, using no processing afterwards.
    {
        BassBoost stage;
        stage.prepare(kRate);
        stage.configure(true, 1.0);
        std::vector<float> warm = sine(60.0, 0.3, 1.0);
        run(stage, warm);
        stage.configure(false, 1.0);
        std::vector<float> fade = sine(60.0, 0.3, 1.0);
        run(stage, fade);
        std::vector<float> after = sine(60.0, 0.3, 0.2);
        const std::vector<float> original = after;
        run(stage, after);
        check(after == original, "after switching off the stage returns to bit-exact pass-through");
    }

    if (failures != 0) {
        std::cerr << failures << " bass boost check(s) failed\n";
        return 1;
    }
    std::cout << "all bass boost checks passed\n";
    return 0;
}
